package io.eitheror.casino.exchange.reads

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.{ChangeSource, KeyedView, ViewComponentContext}
import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.ExchangeEntity

import java.time.Instant

/**
 * A row of `orders-by-owner` (data-model.md): one order, under `<market>/<order>`, or the market's
 * own row, under the market's id, holding the last sequence of its journal the view has seen, so a
 * read can say when it is behind the book (FR-005).
 */
enum OrderRow:
  case Order(market: String, owner: String, order: String, outcome: String, side: String, price: Long, quantity: Long,
      filled: Long, state: String, sequence: Long, at: Instant)
  case Seen(market: String, sequence: Long)

object OrderRow:
  given Serializer[OrderRow] = Codecs.serializer[OrderRow]("exchange-order-row")

/** A row of `trades-by-owner`: one leg of a trade, under `<market>/<trade>/<owner>`, or the market's seen row. */
enum TradeRow:
  case Leg(market: String, owner: String, trade: String, order: String, outcome: String, side: String, price: Long, quantity: Long,
      through: String, at: Instant)
  case Seen(market: String, sequence: Long)

object TradeRow:
  given Serializer[TradeRow] = Codecs.serializer[TradeRow]("exchange-trade-row")

final class OrderRowsView extends KeyedView[OrderRow]:
  def onEvent(event: ExchangeEvent, change: Change): Effect =
    val market = change.subject
    val seen   = effects.updateRow(market, OrderRow.Seen(market, change.sequenceNumber))
    def key(order: OrderId) = OrderRows.key(market, order.value)
    def current(order: OrderId) = change.rows.get(key(order)).collect { case o: OrderRow.Order => o }
    def state(order: OrderId, s: String) = current(order).map(o => key(order) -> o.copy(state = s)).toMap
    val rows: Map[String, OrderRow] = event match
      case e: ExchangeEvent.OrderPlaced =>
        Map(key(e.order) -> OrderRow.Order(market, e.owner.value, e.order.value, e.outcome.value, Side.word(e.side), e.price, e.quantity,
          0, OrderState.word(OrderState.Resting), e.sequence, e.at))
      case e: ExchangeEvent.TradeMade =>
        e.legs.flatMap(l => current(l.order).map { o =>
          val filled = o.filled + e.quantity
          key(l.order) -> o.copy(filled = filled,
            state = OrderState.word(if filled >= o.quantity then OrderState.Filled else OrderState.FilledInPart))
        }).toMap
      case e: ExchangeEvent.OrderFilled    => state(e.order, OrderState.word(OrderState.Filled))
      case e: ExchangeEvent.OrderCancelled => state(e.order, OrderState.word(OrderState.Cancelled))
      case e: ExchangeEvent.OrderExpired   => state(e.order, OrderState.word(OrderState.Expired))
      case _                               => Map.empty
    seen ++ effects.updateRows(rows)

object OrderRows extends KeyedView.Companion[OrderRowsView, OrderRow](ComponentId("orders-by-owner"), summon[Serializer[OrderRow]]):
  def create(ctx: ViewComponentContext): OrderRowsView = new OrderRowsView

  val events = source(ChangeSource.eventsOf(ExchangeEntity))(_.onEvent)

  def key(market: String, order: String) = s"$market/$order"

  /** An owner's orders in every market, oldest first within each. */
  val byOwner = query("by-owner")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'type' = 'Order' AND payload::jsonb->>'owner' = :owner ORDER BY row_key"
  )

  /** The last sequence of a market's journal the view has seen. */
  val seen = query("seen")(s"SELECT payload FROM $table WHERE row_key = :market")

final class TradeRowsView extends KeyedView[TradeRow]:
  def onEvent(event: ExchangeEvent, change: Change): Effect =
    val market = change.subject
    val seen   = effects.updateRow(market, TradeRow.Seen(market, change.sequenceNumber))
    event match
      case e: ExchangeEvent.TradeMade =>
        seen ++ effects.updateRows(e.legs.map { l =>
          TradeRows.key(market, e.trade.value, l.owner.value) -> TradeRow.Leg(market, l.owner.value, e.trade.value, l.order.value,
            l.outcome.value, Side.word(l.side), e.prices.getOrElse(l.outcome, 0L), e.quantity, Through.word(e.through), e.at)
        }.toMap)
      case _ => seen

object TradeRows extends KeyedView.Companion[TradeRowsView, TradeRow](ComponentId("trades-by-owner"), summon[Serializer[TradeRow]]):
  def create(ctx: ViewComponentContext): TradeRowsView = new TradeRowsView

  val events = source(ChangeSource.eventsOf(ExchangeEntity))(_.onEvent)

  def key(market: String, trade: String, owner: String) = s"$market/$trade/$owner"

  val byOwner = query("by-owner")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'type' = 'Leg' AND payload::jsonb->>'owner' = :owner ORDER BY row_key"
  )
