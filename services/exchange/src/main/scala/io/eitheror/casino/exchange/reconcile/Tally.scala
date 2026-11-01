package io.eitheror.casino.exchange.reconcile

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.predictions.exchange.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.exchange.ports.Ledger

import java.time.Instant

/** One difference a check found: a family's key, what the wallet moved and what the exchange did. */
final case class Difference(family: String, key: String, wallet: Long, exchange: Long)

/** A check, set only on the change that makes it, so the audit consumer writes its differences once. */
final case class Check(seq: Int, at: Instant, last: Boolean, differences: Vector[Difference])

/**
 * One money key of one market (research R12, data-model.md *reconciliation*): the sum the exchange's
 * events and placements say moved under it, the sum the wallet's facts say did, and the sources
 * already counted: an exchange event by its journal's sequence (`x/<seq>`), a wallet fact by its
 * transaction id (`w/<id>`), a refused placement by its order (`r/<order>`). A key is moved by a few
 * sources only (an order's placement, its fills and its end), so its state stays small however long
 * the market trades, and each source is counted once in one write.
 */
final case class KeyTally(market: String = "", family: String = "", key: String = "", wallet: Long = 0, exchange: Long = 0,
    sources: Set[String] = Set.empty):
  def balanced: Boolean = wallet == exchange

final case class KeyMove(market: String, family: String, key: String, source: String, wallet: Long, exchange: Long)

/** A market's checks; the keys and their sums are the key tallies', read through [[UnbalancedKeys]] when a check falls due. */
final case class Tally(checks: Int = 0, check: Option[Check] = None)

final case class RefusedPlacement(order: String, cost: Long)
final case class CheckNow(at: Instant, last: Boolean, differences: Vector[Difference] = Vector.empty)

object Tally:
  given Serializer[Tally]            = Codecs.serializer[Tally]("exchange-tally-checks")
  given Serializer[KeyTally]         = Codecs.serializer[KeyTally]("exchange-tally-key")
  given Serializer[KeyMove]          = Codecs.serializer[KeyMove]("exchange-tally-key-move")
  given Serializer[RefusedPlacement] = Codecs.serializer[RefusedPlacement]("exchange-tally-refused")
  given Serializer[CheckNow]         = Codecs.serializer[CheckNow]("exchange-tally-check")

  /** A move's `family|key` split in two. */
  def split(familyKey: String): (String, String) =
    val i = familyKey.indexOf('|')
    (familyKey.take(i), familyKey.drop(i + 1))

  /** The keys whose two sides differ, in key order. */
  def differences(keys: Vector[KeyTally]): Vector[Difference] =
    keys.filterNot(_.balanced).sortBy(k => (k.family, k.key)).map(k => Difference(k.family, k.key, k.wallet, k.exchange))

  private def k(family: String, key: String) = s"$family|$key"

  /** One source's moves onto each key it names; each key counts the source once, so a redelivery moves nothing. */
  def record(client: ComponentClient, market: String, source: String, moves: Map[String, Long], exchange: Boolean): Unit =
    moves.foreach { (familyKey, amount) =>
      val (family, key) = split(familyKey)
      client.forKeyValueEntity(EntityId(KeyTallyEntity.id(market, family, key))).call(KeyTallyEntity.moved)
        .invoke(KeyMove(market, family, key, source, if exchange then 0 else amount, if exchange then amount else 0)): Unit
    }

  /** What an exchange event moved, by family and key, as the ledger port moves it (R4): under the market's ids. */
  def moves(market: MarketId, event: ExchangeEvent): Map[String, Long] =
    def order(o: OrderId) = Ledger.order(market, o)
    val pairs: Vector[(String, Long)] = event match
      case e: ExchangeEvent.OrderPlaced if e.side == Side.Buy && e.cost > 0 => Vector(k("reservation", order(e.order)) -> e.cost)
      case e: ExchangeEvent.OrderCancelled if e.released > 0                => Vector(k("release", order(e.order)) -> e.released)
      case e: ExchangeEvent.OrderExpired if e.released > 0                  => Vector(k("release", order(e.order)) -> e.released)
      case e: ExchangeEvent.TradeMade =>
        val trade    = Ledger.trade(market, e.trade)
        val released = e.legs.filter(_.released > 0).map(l => k("release", order(l.order)) -> l.released)
        val moved = e.through match
          case Through.Direct =>
            e.legs.filter(_.side == Side.Buy).map(l => k("settlement", trade) -> l.charge) ++
              e.legs.filter(_.side == Side.Sell).map(l => k("settlement_credit", trade) -> l.paid)
          case Through.Split => e.legs.filter(_.side == Side.Buy).map(l => k("split", Ledger.key(market, e.trade, l)) -> l.charge)
          case Through.Merge => e.legs.filter(_.side == Side.Sell).map(l => k("merge", Ledger.key(market, e.trade, l)) -> l.paid)
        released ++ moved
      case e: ExchangeEvent.PositionPaid if e.paid > 0 => Vector(k("payment", e.key.value) -> e.paid)
      case _                                           => Vector.empty
    pairs.groupMapReduce(_._1)(_._2)(_ + _)

  /**
   * What a wallet fact moved for the exchange, by family and key, and the market or the id that finds
   * it: `Left` an order or trade id to look up, `Right` the market itself (a pool account's name). None
   * for a fact that is not the exchange's.
   */
  def moves(fact: f.MoneyTransactionsMessage): Option[(Either[String, String], Map[String, Long])] = fact match
    case x: f.ReservationPlaced   => Some(Left(s"order/${x.orderId}") -> Map(k("reservation", x.orderId) -> x.amountCents.value))
    case x: f.ReservationReleased => Some(Left(s"order/${x.orderId}") -> Map(k("release", x.orderId) -> x.amountCents.value))
    case x: f.SettlementDebit =>
      Some(Left(s"trade/${x.tradeId}") -> (Map(k("settlement", x.tradeId) -> x.amountCents.value) ++
        Option.when(x.releasedCents.value > 0)(k("release", x.orderId) -> x.releasedCents.value)))
    case x: f.SettlementCredit => Some(Left(s"trade/${x.tradeId}") -> Map(k("settlement_credit", x.tradeId) -> x.amountCents.value))
    case x: f.AccountDebit if x.accountKind == "pool" && x.purpose == "split" =>
      val released = for o <- x.orderId; r <- x.releasedCents if r.value > 0 yield k("release", o) -> r.value
      Some(Right(x.accountName) -> (Map(k("split", x.reference) -> x.amountCents.value) ++ released))
    case x: f.AccountCredit if x.accountKind == "pool" && x.purpose == "merge" =>
      Some(Right(x.accountName) -> Map(k("merge", x.reference) -> x.amountCents.value))
    case x: f.ResolutionPayment => Some(Right(x.marketId) -> Map(k("payment", x.positionId) -> x.amountCents.value))
    case _                      => None

  def refusedMoves(r: RefusedPlacement): Map[String, Long] = Map(k("reservation", r.order) -> r.cost, k("release", r.order) -> r.cost)

final class KeyTallyEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[KeyTally]:
  def emptyState: KeyTally = KeyTally()

  def moved(m: KeyMove): Effect[KeyTally] =
    val k = currentState
    if k.sources(m.source) then effects.reply(k)
    else
      effects.updateState(KeyTally(m.market, m.family, m.key, k.wallet + m.wallet, k.exchange + m.exchange, k.sources + m.source))
        .thenReplyState

  def get: ReadOnlyEffect[KeyTally] = effects.reply(currentState)

object KeyTallyEntity extends KeyValueEntity.Companion[KeyTallyEntity, KeyTally](ComponentId("exchange-tally-key"), Tally.given_Serializer_KeyTally):
  import Tally.given_Serializer_KeyMove
  def create(context: KeyValueEntityContext) = new KeyTallyEntity(context)

  /** A key tally's id: the family, the market and the key (an entity id holds no `|`; neither a family nor a market holds a `/`). */
  def id(market: String, family: String, key: String) = s"$family/$market/$key"

  val moved = command("moved")(_.moved)
  val get   = query("get")(_.get)

/** Each key tally as a row, so a check finds a market's unbalanced keys without holding them all in one value. */
final class UnbalancedKeysView extends View[KeyTally, KeyTally]:
  def onChange(k: KeyTally): Effect = effects.updateRow(k)

object UnbalancedKeys extends View.Companion[UnbalancedKeysView, KeyTally, KeyTally](
      componentId = ComponentId("exchange-unbalanced-keys"),
      source = ChangeSource.stateOf(KeyTallyEntity),
      rowSerializer = Tally.given_Serializer_KeyTally
    ):
  def create(ctx: ViewComponentContext) = new UnbalancedKeysView

  /** A market's keys whose wallet side differs from its exchange side; a side the row leaves out is 0. */
  val of = query("of-market")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'market' = :market " +
      "AND COALESCE((payload::jsonb->>'wallet')::bigint, 0) <> COALESCE((payload::jsonb->>'exchange')::bigint, 0) ORDER BY row_key")

/** A market's checks: each one numbered and kept with its differences, for the audit consumer. */
final class TallyEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Tally]:
  def emptyState: Tally = Tally()

  def checkNow(c: CheckNow): Effect[Tally] =
    val n = currentState.checks + 1
    effects.updateState(currentState.copy(checks = n, check = Some(Check(n, c.at, c.last, c.differences)))).thenReplyState

  def get: ReadOnlyEffect[Tally] = effects.reply(currentState)

object TallyEntity extends KeyValueEntity.Companion[TallyEntity, Tally](ComponentId("exchange-tally-checks"), Tally.given_Serializer_Tally):
  import Tally.given_Serializer_CheckNow
  def create(context: KeyValueEntityContext) = new TallyEntity(context)

  val checkNow = command("check")(_.checkNow)
  val get      = query("get")(_.get)

/** Which market an order or a trade belongs to, for the wallet's facts that name only the order or the trade. */
final case class MarketOf(market: String = "")

object MarketOf:
  given Serializer[MarketOf] = Codecs.serializer[MarketOf]("exchange-market-of")

final class MarketOfEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[MarketOf]:
  def emptyState: MarketOf = MarketOf()
  def set(m: MarketOf): Effect[MarketOf] =
    if currentState.market.nonEmpty then effects.reply(currentState) else effects.updateState(m).thenReplyState
  def get: ReadOnlyEffect[MarketOf] = effects.reply(currentState)

object MarketOfEntity extends KeyValueEntity.Companion[MarketOfEntity, MarketOf](ComponentId("exchange-market-of"), summon[Serializer[MarketOf]]):
  def create(context: KeyValueEntityContext) = new MarketOfEntity(context)
  val set = command("set")(_.set)
  val get = query("get")(_.get)
