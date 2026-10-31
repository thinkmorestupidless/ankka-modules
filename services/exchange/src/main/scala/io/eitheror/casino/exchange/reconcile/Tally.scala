package io.eitheror.casino.exchange.reconcile

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.predictions.exchange.*
import io.eitheror.casino.contract.facts as f

import java.time.Instant

/** One difference a check found: a family's key, what the wallet moved and what the exchange did. */
final case class Difference(family: String, key: String, wallet: Long, exchange: Long)

/** A check, set only on the change that makes it, so the audit consumer writes its differences once. */
final case class Check(seq: Int, at: Instant, last: Boolean, differences: Vector[Difference])

/**
 * One market's two sides of the money (research R12, data-model.md *reconciliation*): per family and
 * key, the sum the exchange's events and placements say moved and the sum the wallet's facts say did.
 * Each side is counted once: the exchange's by its journal's sequence, the wallet's by its transaction
 * id, a refused placement by its order.
 */
final case class Tally(
    exchange: Map[String, Long] = Map.empty,
    wallet: Map[String, Long] = Map.empty,
    exchangeSeq: Long = 0,
    transactions: Set[String] = Set.empty,
    refused: Set[String] = Set.empty,
    checks: Int = 0,
    check: Option[Check] = None
):
  def differences: Vector[Difference] =
    (exchange.keySet ++ wallet.keySet).toVector.sorted.flatMap { k =>
      val (w, x) = (wallet.getOrElse(k, 0L), exchange.getOrElse(k, 0L))
      Option.when(w != x) {
        val (family, key) = k.splitAt(k.indexOf('|'))
        Difference(family, key.drop(1), w, x)
      }
    }

final case class ExchangeMoves(seq: Long, moves: Map[String, Long])
final case class RefusedPlacement(order: String, cost: Long)
final case class WalletMoves(transaction: String, moves: Map[String, Long])
final case class CheckNow(at: Instant, last: Boolean)

object Tally:
  given Serializer[Tally]            = Codecs.serializer[Tally]("exchange-tally")
  given Serializer[ExchangeMoves]    = Codecs.serializer[ExchangeMoves]("exchange-tally-exchange")
  given Serializer[RefusedPlacement] = Codecs.serializer[RefusedPlacement]("exchange-tally-refused")
  given Serializer[WalletMoves]      = Codecs.serializer[WalletMoves]("exchange-tally-wallet")
  given Serializer[CheckNow]         = Codecs.serializer[CheckNow]("exchange-tally-check")

  def add(to: Map[String, Long], moves: Map[String, Long]) =
    moves.foldLeft(to) { case (m, (k, v)) => m.updated(k, m.getOrElse(k, 0L) + v) }

  private def k(family: String, key: String) = s"$family|$key"

  /** What an exchange event moved, by family and key, as the ledger port moves it (R4). */
  def moves(event: ExchangeEvent): Map[String, Long] =
    val pairs: Vector[(String, Long)] = event match
      case e: ExchangeEvent.OrderPlaced if e.side == Side.Buy && e.cost > 0 => Vector(k("reservation", e.order.value) -> e.cost)
      case e: ExchangeEvent.OrderCancelled if e.released > 0                => Vector(k("release", e.order.value) -> e.released)
      case e: ExchangeEvent.OrderExpired if e.released > 0                  => Vector(k("release", e.order.value) -> e.released)
      case e: ExchangeEvent.TradeMade =>
        val released = e.legs.filter(_.released > 0).map(l => k("release", l.order.value) -> l.released)
        val moved = e.through match
          case Through.Direct =>
            e.legs.filter(_.side == Side.Buy).map(l => k("settlement", e.trade.value) -> l.charge) ++
              e.legs.filter(_.side == Side.Sell).map(l => k("settlement_credit", e.trade.value) -> l.paid)
          case Through.Split => e.legs.filter(_.side == Side.Buy).map(l => k("split", s"${e.trade.value}/${l.owner.value}") -> l.charge)
          case Through.Merge => e.legs.filter(_.side == Side.Sell).map(l => k("merge", s"${e.trade.value}/${l.owner.value}") -> l.paid)
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

final class TallyEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Tally]:
  import Tally.{add, refusedMoves}
  def emptyState: Tally = Tally()

  def exchanged(m: ExchangeMoves): Effect[Tally] =
    if m.seq <= currentState.exchangeSeq then effects.reply(currentState)
    else effects.updateState(currentState.copy(exchange = add(currentState.exchange, m.moves), exchangeSeq = m.seq, check = None)).thenReplyState

  def refusedPlacement(r: RefusedPlacement): Effect[Tally] =
    if currentState.refused(r.order) then effects.reply(currentState)
    else
      effects.updateState(currentState.copy(exchange = add(currentState.exchange, refusedMoves(r)), refused = currentState.refused + r.order,
        check = None)).thenReplyState

  def walletMoved(m: WalletMoves): Effect[Tally] =
    if currentState.transactions(m.transaction) then effects.reply(currentState)
    else
      effects.updateState(currentState.copy(wallet = add(currentState.wallet, m.moves), transactions = currentState.transactions + m.transaction,
        check = None)).thenReplyState

  def checkNow(c: CheckNow): Effect[Tally] =
    val n = currentState.checks + 1
    effects.updateState(currentState.copy(checks = n, check = Some(Check(n, c.at, c.last, currentState.differences)))).thenReplyState

  def get: ReadOnlyEffect[Tally] = effects.reply(currentState)

object TallyEntity extends KeyValueEntity.Companion[TallyEntity, Tally](ComponentId("exchange-tally"), Tally.given_Serializer_Tally):
  import Tally.{given_Serializer_ExchangeMoves, given_Serializer_RefusedPlacement, given_Serializer_WalletMoves, given_Serializer_CheckNow}
  def create(context: KeyValueEntityContext) = new TallyEntity(context)

  val exchanged        = command("exchanged")(_.exchanged)
  val refusedPlacement = command("refused-placement")(_.refusedPlacement)
  val walletMoved      = command("wallet-moved")(_.walletMoved)
  val checkNow         = command("check")(_.checkNow)
  val get              = query("get")(_.get)

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
