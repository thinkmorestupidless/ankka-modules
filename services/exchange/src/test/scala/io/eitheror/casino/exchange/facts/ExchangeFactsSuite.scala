package io.eitheror.casino.exchange.facts

import com.thinkmorestupidless.predictions.exchange.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.time.Instant
import scala.collection.immutable.SortedMap

/** Each exchange event as its topic's fact (R9, T037), keyed by the market, the player a plain id. */
class ExchangeFactsSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-10-31T12:00:00Z")
  private val eur = Currency.unsafe("EUR")
  private def u(n: Long) = MinorUnits.unsafe(n)

  private val trade = ExchangeEvent.TradeMade(TradeId("t1"), 7, Through.Split, 10, SortedMap(OutcomeId("no") -> 40L, OutcomeId("yes") -> 60L),
    Vector(Leg(OwnerId("ada"), OrderId("o1"), OutcomeId("yes"), Side.Buy, 600, 0, 50, 1),
      Leg(OwnerId("bob"), OrderId("o2"), OutcomeId("no"), Side.Buy, 400, 0, 0, 0)), at)

  test("an order's placement, fill, cancel and expiry on exchange.orders, keyed by the market") {
    val placed = ExchangeFacts.order("m1", eur, ExchangeEvent.OrderPlaced(OrderId("o1"), OwnerId("ada"), OutcomeId("yes"), Side.Buy, 60, 10,
      OrderDuration.RestUntilClose, 600, 3, at)).get
    assertEquals(placed, f.OrderPlaced("m1", "o1", "ada", "yes", "buy", 60, 10, "rest-until-close", u(600), eur, 3, at))
    assertEquals(placed.key, "m1")
    assertEquals(ExchangeFacts.order("m1", fail("a fill needs no currency"), ExchangeEvent.OrderFilled(OrderId("o1"), at)),
      Some(f.OrderFilled("m1", "o1", at)))
    assertEquals(ExchangeFacts.order("m1", eur, ExchangeEvent.OrderCancelled(OrderId("o1"), OwnerId("ada"), 120, 2, at)),
      Some(f.OrderCancelled("m1", "o1", "ada", u(120), 2, eur, at)))
    assertEquals(ExchangeFacts.order("m1", eur, ExchangeEvent.OrderExpired(OrderId("o1"), OwnerId("ada"), 0, 0, at)),
      Some(f.OrderExpired("m1", "o1", "ada", u(0), 0, eur, at)))
    assertEquals(ExchangeFacts.order("m1", eur, trade), None)
  }

  test("a trade with a leg per owner, a position paid and a remainder kept on exchange.trades") {
    val made = ExchangeFacts.trade("m1", eur, trade).get
    assertEquals(made, f.TradeMade("m1", "t1", 7, "split", 10, Vector(f.OutcomePrice("no", 40), f.OutcomePrice("yes", 60)),
      Vector(f.TradeLeg("ada", "o1", "yes", "buy", u(600), u(0), u(50), 1), f.TradeLeg("bob", "o2", "no", "buy", u(400), u(0), u(0), 0)), eur, at))
    assertEquals(made.key, "m1")
    assertEquals(ExchangeFacts.trade("m1", eur, ExchangeEvent.PositionPaid(PositionKey("ada/yes"), OwnerId("ada"), OutcomeId("yes"), 10, 1000, at)),
      Some(f.PositionPaid("m1", "ada/yes", "ada", "yes", 10, u(1000), eur, at)))
    assertEquals(ExchangeFacts.trade("m1", eur, ExchangeEvent.RemainderKept(1, at)), Some(f.RemainderKept("m1", u(1), eur, at)))
    assertEquals(ExchangeFacts.trade("m1", eur, ExchangeEvent.Closed(at)), None)
  }

  test("a trade's last prices on exchange.prices at the trade's sequence; nothing else moves them") {
    assertEquals(ExchangeFacts.price("m1", trade), Some(f.PricesChanged("m1", 7, Vector(f.OutcomePrice("no", 40), f.OutcomePrice("yes", 60)), at)))
    assertEquals(ExchangeFacts.price("m1", ExchangeEvent.OrderFilled(OrderId("o1"), at)), None)
  }

  test("each fact round-trips through its topic's serializer") {
    val placed = ExchangeFacts.order("m1", eur, ExchangeEvent.OrderCancelled(OrderId("o1"), OwnerId("ada"), 1, 1, at)).get
    assertEquals(f.ExchangeOrders.serializer.fromBytes(f.ExchangeOrders.serializer.toBytes(placed)), placed)
    val made = ExchangeFacts.trade("m1", eur, trade).get
    assertEquals(f.ExchangeTrades.serializer.fromBytes(f.ExchangeTrades.serializer.toBytes(made)), made)
  }
