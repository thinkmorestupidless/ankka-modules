package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.time.Instant

// The exchange's three topics, produced by `exchange` (spec 023 FR-006), each keyed by the market.
// Prices are minor units of the market's currency per share, 1 to 99 (FR-012); quantities are shares.

// ── exchange.orders ──────────────────────────────────────────────────────

sealed trait ExchangeOrdersFact extends MarketFact:
  def orderId: String
  def occurredAt: Instant

/** An order accepted onto a book; for a buy, `cost_cents` is what the wallet holds under the order. */
@named("order_placed")
final case class OrderPlaced(marketId: String, orderId: String, playerId: String, outcomeId: String, side: String,
    price: Long, quantity: Long, duration: String, costCents: MinorUnits, currency: Currency, sequence: Long,
    occurredAt: Instant) extends ExchangeOrdersFact

/** An order filled in full; its trades name its player. */
@named("order_filled")
final case class OrderFilled(marketId: String, orderId: String, occurredAt: Instant) extends ExchangeOrdersFact

/** A resting order cancelled; a buy's remaining cost released under the order and `release_ordinal`. */
@named("order_cancelled")
final case class OrderCancelled(marketId: String, orderId: String, playerId: String, releasedCents: MinorUnits,
    releaseOrdinal: Int, currency: Currency, occurredAt: Instant) extends ExchangeOrdersFact

/** A resting order expired at the close; its remaining cost released likewise. */
@named("order_expired")
final case class OrderExpired(marketId: String, orderId: String, playerId: String, releasedCents: MinorUnits,
    releaseOrdinal: Int, currency: Currency, occurredAt: Instant) extends ExchangeOrdersFact

object ExchangeOrdersFact:
  given JsonValueCodec[ExchangeOrdersFact] = FactCodecs.make

object ExchangeOrders extends Topic[ExchangeOrdersFact]("exchange.orders", Vector("exchange")):
  val families = Vector(
    FamilyDef.of[OrderPlaced]("order_placed"),
    FamilyDef.of[OrderFilled]("order_filled"),
    FamilyDef.of[OrderCancelled]("order_cancelled"),
    FamilyDef.of[OrderExpired]("order_expired")
  )

// ── exchange.trades ──────────────────────────────────────────────────────

/**
 * One owner's part of a trade: what they bought or sold of an outcome, what their reservation was
 * charged, what they were paid, and what was released from their order in the same move. `fee_cents` is
 * the taker fee the leg paid, when spec 023 charges one (spec 015 R11); absent means none.
 */
final case class TradeLeg(playerId: String, orderId: String, outcomeId: String, side: String, chargeCents: MinorUnits,
    paidCents: MinorUnits, releasedCents: MinorUnits, releaseOrdinal: Int, feeCents: Option[MinorUnits] = None)

object TradeLeg:
  given FieldType[TradeLeg] = FieldType.nested[TradeLeg]

/** One outcome's price in a trade, which becomes its last price. */
final case class OutcomePrice(outcomeId: String, price: Long)

object OutcomePrice:
  given FieldType[OutcomePrice] = FieldType.nested[OutcomePrice]

sealed trait ExchangeTradesFact extends MarketFact:
  def occurredAt: Instant

/**
 * A trade of `quantity` shares, the exchange's `sequence`th, keyed for the wallet by `trade_id`:
 * `direct` between a buyer and a seller, `split` of complete sets into the pool, or `merge` of them
 * out of it. A leg per owner.
 */
@named("trade_made")
final case class TradeMade(marketId: String, tradeId: String, sequence: Long, through: String, quantity: Long,
    prices: Vector[OutcomePrice], legs: Vector[TradeLeg], currency: Currency, occurredAt: Instant) extends ExchangeTradesFact

/** A position paid from the market's pool at finality, keyed for the wallet by the market and `position_id`. */
@named("position_paid")
final case class PositionPaid(marketId: String, positionId: String, playerId: String, outcomeId: String, quantity: Long,
    paidCents: MinorUnits, currency: Currency, occurredAt: Instant) extends ExchangeTradesFact

/** What a void's equal division left undivided, kept in the market's pool. */
@named("remainder_kept")
final case class RemainderKept(marketId: String, amountCents: MinorUnits, currency: Currency, occurredAt: Instant)
    extends ExchangeTradesFact

object ExchangeTradesFact:
  given JsonValueCodec[ExchangeTradesFact] = FactCodecs.make

object ExchangeTrades extends Topic[ExchangeTradesFact]("exchange.trades", Vector("exchange")):
  val families = Vector(
    FamilyDef.of[TradeMade]("trade_made"),
    FamilyDef.of[PositionPaid]("position_paid"),
    FamilyDef.of[RemainderKept]("remainder_kept")
  )

// ── exchange.prices ──────────────────────────────────────────────────────

sealed trait ExchangePricesFact extends MarketFact:
  def occurredAt: Instant

/** The last price of every outcome a trade moved, at the exchange's `sequence`, so a reader sees a gap. */
@named("prices_changed")
final case class PricesChanged(marketId: String, sequence: Long, prices: Vector[OutcomePrice], occurredAt: Instant)
    extends ExchangePricesFact

object ExchangePricesFact:
  given JsonValueCodec[ExchangePricesFact] = FactCodecs.make

object ExchangePrices extends Topic[ExchangePricesFact]("exchange.prices", Vector("exchange")):
  val families = Vector(FamilyDef.of[PricesChanged]("prices_changed"))
