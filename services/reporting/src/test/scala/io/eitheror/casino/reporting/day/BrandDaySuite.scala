package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.reporting.day.DayEvent.*

import java.time.Instant

/** T025: `BrandDay`'s folds, dedup by source and sequence, the test share, the close and the corrections after it. */
class BrandDaySuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-08T10:00:00Z")
  private def kit = EventSourcedTestKit.of(BrandDay, BrandDay.id(1, "2026-11-08"))
  private def fold(k: EventSourcedTestKit[BrandDay, DayState, DayEvent], source: String, seq: Long, d: Delta) =
    k.call(BrandDay.fold)(Fold(source, seq, d, at))

  private val stake    = Delta("EUR", 10, Money(stakes = 1000, poolGgr = 600))
  private val win      = Delta("EUR", 11, Money(wins = 300, poolGgr = -200))
  private val bonus    = Delta("EUR", 11, Money(bonusCost = 100))
  private val dispute  = Delta("EUR", 12, Money(chargebacks = 50, chargebackCount = 1))
  private val testBet  = Delta("EUR", 10, Money(stakes = 400), test = true)

  test("folds add per currency: GGR, the deposit pool's, NGR before fees, and the hours") {
    val k = kit
    Seq(stake, win, bonus, dispute).zipWithIndex.foreach((d, i) => fold(k, s"wallet-book/g1/p-1/EUR", i + 1L, d): Unit)
    fold(k, "wallet-book/g1/p-2/GBP", 1, Delta("GBP", 9, Money(stakes = 70))): Unit
    val eur = k.currentState.currencies("EUR").total
    assertEquals((eur.money.ggr, eur.money.poolGgr, eur.money.ngr), (700L, 400L, 550L))
    assertEquals((eur.hourGgr(10), eur.hourGgr(11), eur.hourNgr(11), eur.hourNgr(12)), (1000L, -300L, -400L, -50L))
    assertEquals(k.currentState.currencies("GBP").total.money.stakes, 70L)
  }

  test("a market's figures are kept per hour too, so the dashboard reads yesterday to the same hour, and survive the close") {
    val k = kit
    val trade = (h: Int, market: String, n: Long) =>
      Delta("EUR", h, predictions = Map(market -> MarketDay(contracts = n, notional = n * 60, trades = 1, fees = n)))
    fold(k, "market-book/g1/m-1", 1, trade(8, "m-1", 10)): Unit
    fold(k, "market-book/g1/m-2", 1, trade(8, "m-2", 3)): Unit
    fold(k, "market-book/g1/m-1", 2, trade(15, "m-1", 4)): Unit
    val eur = k.currentState.currencies("EUR")
    assertEquals((eur.predictionsTo(10).contracts, eur.predictionsTo(10).trades, eur.predictionsTo(23).contracts), (13L, 2L, 17L))
    assertEquals(eur.predictionsTo(23).notional, eur.predictions.values.map(_.notional).sum)
    assertEquals(eur.total.hourGgr.sum, 0L, "no casino figure holds a trade")
    k.call(BrandDay.close)(Close(at, "UTC")): Unit
    assertEquals(k.currentState.currencies("EUR").predictionsTo(10).contracts, 13L)
  }

  test("an event already folded from its source folds nothing, an older one neither") {
    val k = kit
    assert(fold(k, "wallet-book/g1/p-1/EUR", 5, stake).replyValue)
    assert(!fold(k, "wallet-book/g1/p-1/EUR", 5, stake).replyValue)
    assert(!fold(k, "wallet-book/g1/p-1/EUR", 4, stake).persisted)
    assert(fold(k, "payment/g1/pay-1", 1, stake).replyValue)
    assertEquals(k.currentState.currencies("EUR").total.money.stakes, 2000L)
  }

  test("a test account's delta is in the total and in the test share; a read without test accounts subtracts it") {
    val k = kit
    fold(k, "a", 1, stake): Unit
    fold(k, "b", 1, testBet): Unit
    fold(k, "c", 1, Delta(registrations = 1, test = true)): Unit
    fold(k, "d", 1, Delta(registrations = 1)): Unit
    val day = k.currentState.currencies("EUR")
    assertEquals((day.total.money.stakes, day.test.money.stakes), (1400L, 400L))
    assertEquals(day.section(excludeTestAccounts = true).money.stakes, 1000L)
    assertEquals(day.section(excludeTestAccounts = true).hourGgr(10), 1000L)
    assertEquals((k.currentState.registrations, k.currentState.testRegistrations), (2L, 1L))
  }

  test("providers and gateways are kept per name") {
    val k = kit
    fold(k, "a", 1, Delta("EUR", 8, payments = Map("psp-a" -> PaymentFigures(attempts = 1)))): Unit
    fold(k, "a", 2, Delta("EUR", 8, payments = Map("psp-a" -> PaymentFigures(completed = 1, value = 1000)))): Unit
    fold(k, "w", 1, Delta("EUR", 9, Money(withdrawals = 250, withdrawalCount = 1), gateways = Map("unity" -> GatewayFigures(0, 1, 7200)))): Unit
    val t = k.currentState.currencies("EUR").total
    assertEquals(t.payments, Map("psp-a" -> PaymentFigures(1, 1, 1000)))
    assertEquals(t.gateways, Map("unity" -> GatewayFigures(0, 1, 7200)))
    assertEquals(t.money.withdrawals, 250L)
  }

  test("the close fixes the day once, with what it is given; nothing reopens") {
    val k = kit
    fold(k, "a", 1, stake): Unit
    val games = Map("EUR" -> Map("st8/g-1" -> GameFigures("st8", "g-1", Some("Book of Gold"), 1000, 0, 1000, 0)))
    val diff  = Difference("p-1", "EUR", "r-9", "wallet_only", walletStake = 50)
    val c = k.call(BrandDay.close)(Close(at.plusSeconds(86400), "Africa/Johannesburg", CloseInputs(games, Vector(diff), Map("EUR" -> 700), Map("EUR" -> 250))))
    val fixed = c.eventOfType[DayFixed]
    assertEquals((fixed.zone, fixed.differences), ("Africa/Johannesburg", Vector(diff)))
    val eur = fixed.sections("EUR")
    assertEquals((eur.total.money.stakes, eur.games.keySet, eur.liabilityAtClose, eur.pendingHoldsAtClose), (1000L, Set("st8/g-1"), 700L, 250L))
    assert(k.currentState.closed)
    assert(!k.call(BrandDay.close)(Close(at.plusSeconds(90000), "UTC")).persisted)
    assertEquals(k.currentState.zone, "Africa/Johannesburg")
  }

  test("a fold after the close is a correction dated when it arrived; the fixed figures stay") {
    val k = kit
    fold(k, "a", 1, stake): Unit
    k.call(BrandDay.close)(Close(at.plusSeconds(86400), "UTC")): Unit
    val late = k.call(BrandDay.fold)(Fold("a", 2, win, at.plusSeconds(90000)))
    assertEquals(late.events, Vector(Corrected("a", 2, at.plusSeconds(90000), win)))
    assertEquals(k.currentState.currencies("EUR").total.money.wins, 0L)
    assertEquals(k.currentState.corrections, Vector(Correction("a", at.plusSeconds(90000), win)))
    assert(!k.call(BrandDay.fold)(Fold("a", 2, win, at.plusSeconds(90001))).persisted)
  }

  test("the day-end opens with the corrections to earlier days that arrived during it") {
    val k = kit
    val opening = OpeningCorrection("2026-11-07", at, win)
    k.call(BrandDay.close)(Close(at.plusSeconds(86400), "UTC", CloseInputs(openingCorrections = Vector(opening)))): Unit
    assertEquals(k.currentState.openingCorrections, Vector(opening))
  }
