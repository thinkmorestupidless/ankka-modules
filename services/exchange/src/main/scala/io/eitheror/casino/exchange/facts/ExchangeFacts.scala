package io.eitheror.casino.exchange.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.ExchangeEntity
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.exchange.terms.MarketTerms

/**
 * The exchange's events as the facts of its three topics (R9, FR-006), each keyed by the market: the
 * market is the event's subject, the currency the market's terms. A fact's `sequence` is the
 * exchange's own (an order's or a trade's), so a reader of a topic sees a gap.
 */
object ExchangeFacts:
  private def u(n: Long) = MinorUnits.unsafe(n)

  /** `c` is read only for a fact that carries it: a fill does not. */
  def order(m: String, c: => Currency, event: ExchangeEvent): Option[f.ExchangeOrdersFact] = event match
    case e: ExchangeEvent.OrderPlaced =>
      Some(f.OrderPlaced(m, e.order.value, e.owner.value, e.outcome.value, Side.word(e.side), e.price, e.quantity,
        OrderDuration.word(e.duration), u(e.cost), c, e.sequence, e.at))
    case e: ExchangeEvent.OrderFilled    => Some(f.OrderFilled(m, e.order.value, e.at))
    case e: ExchangeEvent.OrderCancelled => Some(f.OrderCancelled(m, e.order.value, e.owner.value, u(e.released), e.releaseOrdinal, c, e.at))
    case e: ExchangeEvent.OrderExpired   => Some(f.OrderExpired(m, e.order.value, e.owner.value, u(e.released), e.releaseOrdinal, c, e.at))
    case _                               => None

  private def prices(p: scala.collection.immutable.SortedMap[OutcomeId, Long]) = p.toVector.map((o, v) => f.OutcomePrice(o.value, v))

  def trade(m: String, c: Currency, event: ExchangeEvent): Option[f.ExchangeTradesFact] = event match
    case e: ExchangeEvent.TradeMade =>
      Some(f.TradeMade(m, e.trade.value, e.sequence, Through.word(e.through), e.quantity, prices(e.prices),
        e.legs.map(l => f.TradeLeg(l.owner.value, l.order.value, l.outcome.value, Side.word(l.side), u(l.charge), u(l.paid), u(l.released),
          l.releaseOrdinal)), c, e.at))
    case e: ExchangeEvent.PositionPaid  => Some(f.PositionPaid(m, e.key.value, e.owner.value, e.outcome.value, e.quantity, u(e.paid), c, e.at))
    case e: ExchangeEvent.RemainderKept => Some(f.RemainderKept(m, u(e.amount), c, e.at))
    case _                              => None

  def price(m: String, event: ExchangeEvent): Option[f.ExchangePricesFact] = event match
    case e: ExchangeEvent.TradeMade => Some(f.PricesChanged(m, e.sequence, prices(e.prices), e.at))
    case _                          => None

  /** The market's currency, from the terms its opening wrote. */
  def currency(context: ConsumerContext, m: String): Currency =
    Currency.unsafe(context.componentClient.forKeyValueEntity(EntityId(m)).call(MarketTerms.get).invoke().currency)

  def ceId(kind: String, m: String, seq: Long) = s"$kind/$m/$seq"

/** `exchange.orders`: an order placed, filled, cancelled or expired. */
final class OrderFactsPublisher(context: ConsumerContext) extends Consumer[ExchangeEvent, f.ExchangeOrdersFact]:
  def onMessage(event: ExchangeEvent): Effect =
    val m = messageContext.subject
    ExchangeFacts.order(m, ExchangeFacts.currency(context, m), event).fold(effects.ignore())(fact =>
      effects.produceAll(Seq(effects.message(fact).withKey(fact.key)
        .withMetadata(Metadata.empty.set("ce-id", ExchangeFacts.ceId("order", m, messageContext.sequenceNumber))))))

object OrderFactsPublisher
    extends Consumer.Companion[OrderFactsPublisher, ExchangeEvent, f.ExchangeOrdersFact](ComponentId("exchange-order-facts"),
      ChangeSource.eventsOf(ExchangeEntity)):
  override val outputSerializer: Option[Serializer[f.ExchangeOrdersFact]] = Some(f.ExchangeOrders.serializer)
  override val produceTo: Option[String]                                 = Some(f.ExchangeOrders.name)
  override val produces: Option[Publication] = Some(Publication(f.ExchangeOrders.name, Some(f.ExchangeOrders.contract), None))
  def create(ctx: ConsumerContext) = new OrderFactsPublisher(ctx)

/** `exchange.trades`: a trade and its legs, a position paid, a remainder kept. */
final class TradeFactsPublisher(context: ConsumerContext) extends Consumer[ExchangeEvent, f.ExchangeTradesFact]:
  def onMessage(event: ExchangeEvent): Effect =
    val m = messageContext.subject
    event match
      case _: (ExchangeEvent.TradeMade | ExchangeEvent.PositionPaid | ExchangeEvent.RemainderKept) =>
        val fact = ExchangeFacts.trade(m, ExchangeFacts.currency(context, m), event).get
        effects.produceAll(Seq(effects.message(fact).withKey(fact.key)
          .withMetadata(Metadata.empty.set("ce-id", ExchangeFacts.ceId("trade", m, messageContext.sequenceNumber)))))
      case _ => effects.ignore()

object TradeFactsPublisher
    extends Consumer.Companion[TradeFactsPublisher, ExchangeEvent, f.ExchangeTradesFact](ComponentId("exchange-trade-facts"),
      ChangeSource.eventsOf(ExchangeEntity)):
  override val outputSerializer: Option[Serializer[f.ExchangeTradesFact]] = Some(f.ExchangeTrades.serializer)
  override val produceTo: Option[String]                                 = Some(f.ExchangeTrades.name)
  override val produces: Option[Publication] = Some(Publication(f.ExchangeTrades.name, Some(f.ExchangeTrades.contract), None))
  def create(ctx: ConsumerContext) = new TradeFactsPublisher(ctx)

/** `exchange.prices`: the last prices a trade set. */
final class PriceFactsPublisher extends Consumer[ExchangeEvent, f.ExchangePricesFact]:
  def onMessage(event: ExchangeEvent): Effect =
    val m = messageContext.subject
    ExchangeFacts.price(m, event).fold(effects.ignore())(fact =>
      effects.produceAll(Seq(effects.message(fact).withKey(fact.key)
        .withMetadata(Metadata.empty.set("ce-id", ExchangeFacts.ceId("prices", m, messageContext.sequenceNumber))))))

object PriceFactsPublisher
    extends Consumer.Companion[PriceFactsPublisher, ExchangeEvent, f.ExchangePricesFact](ComponentId("exchange-price-facts"),
      ChangeSource.eventsOf(ExchangeEntity)):
  override val outputSerializer: Option[Serializer[f.ExchangePricesFact]] = Some(f.ExchangePrices.serializer)
  override val produceTo: Option[String]                                 = Some(f.ExchangePrices.name)
  override val produces: Option[Publication] = Some(Publication(f.ExchangePrices.name, Some(f.ExchangePrices.contract), None))
  def create(ctx: ConsumerContext) = new PriceFactsPublisher
