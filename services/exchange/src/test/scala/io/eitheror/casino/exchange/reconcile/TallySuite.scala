package io.eitheror.casino.exchange.reconcile

import com.thinkmorestupidless.predictions.exchange.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant
import scala.collection.immutable.SortedMap

/** Each side's moves by family and key (R12), and a deliberate difference found by the check. */
class TallySuite extends munit.FunSuite:
  private val at  = Instant.EPOCH
  private val eur = Currency.unsafe("EUR")
  private def u(n: Long) = MinorUnits.unsafe(n)
  private val zero = SignedMinorUnits(0)

  private def leg(owner: String, order: String, side: Side, charge: Long = 0, paid: Long = 0, released: Long = 0) =
    Leg(OwnerId(owner), OrderId(order), OutcomeId("yes"), side, charge, paid, released, if released > 0 then 1 else 0)

  test("the exchange's events move reservations, releases, trades by their path and payments") {
    assertEquals(Tally.moves(ExchangeEvent.OrderPlaced(OrderId("o1"), OwnerId("ada"), OutcomeId("yes"), Side.Buy, 60, 10,
      OrderDuration.RestUntilClose, 600, 1, at)), Map("reservation|o1" -> 600L))
    assertEquals(Tally.moves(ExchangeEvent.OrderPlaced(OrderId("o2"), OwnerId("ada"), OutcomeId("yes"), Side.Sell, 60, 10,
      OrderDuration.RestUntilClose, 0, 2, at)), Map.empty[String, Long])
    assertEquals(Tally.moves(ExchangeEvent.OrderCancelled(OrderId("o1"), OwnerId("ada"), 100, 2, at)), Map("release|o1" -> 100L))
    val direct = ExchangeEvent.TradeMade(TradeId("t1"), 1, Through.Direct, 10, SortedMap.empty,
      Vector(leg("ada", "o1", Side.Buy, charge = 500, released = 100), leg("bob", "o2", Side.Sell, paid = 500)), at)
    assertEquals(Tally.moves(direct), Map("release|o1" -> 100L, "settlement|t1" -> 500L, "settlement_credit|t1" -> 500L))
    val split = ExchangeEvent.TradeMade(TradeId("t2"), 2, Through.Split, 10, SortedMap.empty,
      Vector(leg("ada", "o1", Side.Buy, charge = 600), leg("bob", "o3", Side.Buy, charge = 400)), at)
    assertEquals(Tally.moves(split), Map("split|t2/ada" -> 600L, "split|t2/bob" -> 400L))
    assertEquals(Tally.moves(ExchangeEvent.PositionPaid(PositionKey("ada/yes"), OwnerId("ada"), OutcomeId("yes"), 10, 1000, at)),
      Map("payment|ada/yes" -> 1000L))
  }

  test("the wallet's facts move the same keys, found by the order, the trade or the pool's market") {
    val placed = f.ReservationPlaced("ada", eur, "tx1", "o1", u(600), zero, u(0), u(600), at)
    assertEquals(Tally.moves(placed), Some(Left("order/o1") -> Map("reservation|o1" -> 600L)))
    val debit = f.SettlementDebit("ada", eur, "tx2", "t1", "o1", u(500), u(100), "bob", zero, u(0), u(0), at)
    assertEquals(Tally.moves(debit), Some(Left("trade/t1") -> Map("settlement|t1" -> 500L, "release|o1" -> 100L)))
    val split = f.AccountDebit("bob", eur, "tx3", "pool", "m1", "split", "t2/bob", u(400), zero, u(0), u(0), at, orderId = Some("o3"))
    assertEquals(Tally.moves(split), Some(Right("m1") -> Map("split|t2/bob" -> 400L)))
    val paid = f.ResolutionPayment("ada", eur, "tx4", "m1", "ada/yes", u(1000), zero, u(0), u(0), at)
    assertEquals(Tally.moves(paid), Some(Right("m1") -> Map("payment|ada/yes" -> 1000L)))
    val clearing = f.AccountDebit("bob", eur, "tx5", "clearing", "c1", "clearing", "k", u(1), zero, u(0), u(0), at)
    assertEquals(Tally.moves(clearing), None)
  }

  test("a check names each key whose two sides differ, and only those") {
    val t = Tally(exchange = Map("split|t2/ada" -> 600L, "split|t2/bob" -> 400L, "release|o9" -> 50L),
      wallet = Map("split|t2/ada" -> 600L, "split|t2/bob" -> 399L))
    assertEquals(t.differences, Vector(Difference("release", "o9", 0, 50), Difference("split", "t2/bob", 399, 400)))
  }
