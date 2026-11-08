package io.eitheror.casino.reporting.predictions

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.intake.{Coverage, FactId, Generation, Lenient}

import java.time.Instant

/**
 * One owner's part of a trade as reporting counts it: the shares (every leg of a trade moves its `quantity`), their
 * notional at the leg's outcome's price, what the reservation was charged, what was paid, the release and the taker fee.
 */
final case class LegFigures(player: String, outcome: String, side: String, quantity: Long, notional: Long, charge: Long, paid: Long,
    released: Long, fee: Long):
  /** What the leg moved to and from the player's real balance: paid less charged less the fee (a release moves none). */
  def net: Long = paid - charge - fee

  /** The shares the leg adds to the owner's position in its outcome: a buy adds, a sell takes away. */
  def position: Long = if side == "sell" then -quantity else quantity

sealed trait MarketBookEvent:
  def factId: String
  def market: String
  def currency: String
  def at: Instant

object MarketBookEvent:
  /** A trade: `notional` is the quantity times the price of each outcome its legs trade (FR-028). */
  final case class Traded(factId: String, market: String, tradeId: String, currency: String, quantity: Long, notional: Long, legs: Vector[LegFigures],
      fees: Long, at: Instant) extends MarketBookEvent
  final case class Paid(factId: String, market: String, positionId: String, player: String, outcome: String, quantity: Long, paid: Long,
      currency: String, at: Instant) extends MarketBookEvent
  final case class Kept(factId: String, market: String, amount: Long, currency: String, at: Instant) extends MarketBookEvent

/**
 * A market's trades and payments (data-model.md `MarketBook`, id `g<n>/<market>`): contracts traded, notional, trades,
 * the traders, each owner's shares per outcome (open interest: bought less sold less paid), taker fees, resolution
 * payments and the remainder kept. `trades` and `positions` are the ids already folded, so a fact sent again folds
 * nothing.
 */
final case class MarketBookState(
    market: String = "",
    currency: String = "",
    contracts: Long = 0,
    notional: Long = 0,
    trades: Long = 0,
    traders: Set[String] = Set.empty,
    holdings: Map[String, Long] = Map.empty,
    fees: Long = 0,
    payments: Long = 0,
    remainder: Long = 0,
    tradeIds: Set[String] = Set.empty,
    positionIds: Set[String] = Set.empty,
    seen: Vector[String] = Vector.empty
):
  /** The shares held now, over every owner and outcome (FR-029). */
  def openInterest: Long = holdings.values.filter(_ > 0).sum

object MarketBooks:
  import MarketBookEvent.*

  private def holding(player: String, outcome: String) = s"$player/$outcome"

  def event(s: MarketBookState, id: String, fact: f.ExchangeTradesFact): Option[MarketBookEvent] =
    if s.seen.contains(id) then None
    else fact match
      case t: f.TradeMade if !s.tradeIds(t.tradeId) =>
        val price = t.prices.map(p => p.outcomeId -> p.price).toMap
        val legs = t.legs.map(l => LegFigures(l.playerId, l.outcomeId, l.side, t.quantity, t.quantity * price.getOrElse(l.outcomeId, 0L),
          l.chargeCents.value, l.paidCents.value, l.releasedCents.value, l.feeCents.fold(0L)(_.value)))
        val traded = t.legs.map(_.outcomeId).distinct
        Some(Traded(id, t.marketId, t.tradeId, t.currency.code, t.quantity, traded.map(o => t.quantity * price.getOrElse(o, 0L)).sum, legs,
          legs.map(_.fee).sum, t.occurredAt))
      case p: f.PositionPaid if !s.positionIds(p.positionId) =>
        Some(Paid(id, p.marketId, p.positionId, p.playerId, p.outcomeId, p.quantity, p.paidCents.value, p.currency.code, p.occurredAt))
      case r: f.RemainderKept => Some(Kept(id, r.marketId, r.amountCents.value, r.currency.code, r.occurredAt))
      case _                  => None

  def apply(s0: MarketBookState, e: MarketBookEvent): MarketBookState =
    val s = s0.copy(market = e.market, currency = e.currency, seen = FactId.remember(s0.seen, e.factId))
    e match
      case t: Traded =>
        s.copy(contracts = s.contracts + t.quantity, notional = s.notional + t.notional, trades = s.trades + 1,
          traders = s.traders ++ t.legs.map(_.player), fees = s.fees + t.fees, tradeIds = s.tradeIds + t.tradeId,
          holdings = t.legs.foldLeft(s.holdings)((h, l) => h.updatedWith(holding(l.player, l.outcome))(v => Some(v.getOrElse(0L) + l.position))))
      case p: Paid =>
        s.copy(payments = s.payments + p.paid, positionIds = s.positionIds + p.positionId,
          holdings = s.holdings.updatedWith(holding(p.player, p.outcome))(v => Some(v.getOrElse(0L) - p.quantity)))
      case k: Kept => s.copy(remainder = s.remainder + k.amount)

/** A trades fact and its id; the contract's codec. */
final case class MarketBookFact(factId: String, fact: f.ExchangeTradesFact)

final class MarketBook(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[MarketBookState, MarketBookEvent]:
  def emptyState: MarketBookState = MarketBookState()

  def applyEvent(event: MarketBookEvent): MarketBookState = MarketBooks.apply(currentState, event)

  /** Whether the fact was new. */
  def record(c: MarketBookFact): Effect[Boolean] =
    MarketBooks.event(currentState, c.factId, c.fact).fold(effects.reply(false))(e => effects.persist(e).thenReply(_ => true))

  def get: ReadOnlyEffect[MarketBookState] = effects.reply(currentState)

object MarketBook extends EventSourcedEntity.Companion[MarketBook, MarketBookState, MarketBookEvent](
      componentId = ComponentId("market-book"),
      stateSerializer = ReportingCodecs.serializer[MarketBookState]("market-book"),
      eventSerializer = ReportingCodecs.serializer[MarketBookEvent]("market-book-event")
    ):
  given Serializer[MarketBookFact] =
    Serializer.json[MarketBookFact]("reporting.market-book-fact")(using JsonCodecMaker.make[MarketBookFact])
  import ReportingCodecs.given_Serializer_Boolean

  override def snapshotEvery: Option[Int] = Some(100)

  def id(generation: Int, market: String): String = Generation.id(generation, market)

  def create(context: EventSourcedEntityContext) = new MarketBook(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)

/**
 * `exchange.trades` into each market's `MarketBook` (registered only when the brand runs `predictions`, R12), and each
 * trade's taker fee into its `TradeFee`, which the fee account's credits are held to (FR-031). The fee is told again
 * when the trade is redelivered, which `TradeFee` keeps once.
 */
final class TradesFactsConsumer(client: ComponentClient, settings: Settings) extends Consumer[Option[f.ExchangeTradesFact], Nothing]:
  def onMessage(fact: Option[f.ExchangeTradesFact]): Effect = fact match
    case None =>
      Lenient.unreadable(f.ExchangeTrades.name, messageContext.subject)
      effects.ignore()
    case Some(t) =>
      client.forEventSourcedEntity(EntityId(MarketBook.id(settings.generation, t.marketId))).call(MarketBook.record)
        .invoke(MarketBookFact(FactId.of(f.ExchangeTrades, t), t)): Unit
      t match
        case trade: f.TradeMade =>
          val fee = trade.legs.flatMap(_.feeCents).map(_.value).sum
          client.forKeyValueEntity(EntityId(TradeFee.id(settings.generation, trade.tradeId))).call(TradeFee.traded)
            .invoke(Traded(trade.marketId, trade.currency.code, Days.dayOf(trade.occurredAt, settings.reportingZone), fee)): Unit
        case _ => ()
      Coverage.observed(client, settings, f.ExchangeTrades.name, FactId.occurredAt(t))
      effects.done()

final class TradesFacts(settings: Settings)
    extends Consumer.Companion[TradesFactsConsumer, Option[f.ExchangeTradesFact], Nothing](
      Generation.componentId("trades-facts", settings.generation), Lenient.from(f.ExchangeTrades)):
  def create(ctx: ConsumerContext) = new TradesFactsConsumer(ctx.componentClient, settings)
