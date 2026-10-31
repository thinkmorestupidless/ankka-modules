package io.eitheror.casino.payouts.limits

import io.eitheror.casino.contract.money.Currency

import java.time.Instant

/** The original's `WithdrawalLimitCheckerSpec` cases, per currency, and each window at its edges (FR-005b). */
class LimitsSuite extends munit.FunSuite:
  private val Eur = Currency.unsafe("EUR")
  private val Gbp = Currency.unsafe("GBP")
  private val now = Instant.parse("2026-10-15T12:00:00Z")

  private def counted(amount: Long, at: Instant, currency: Currency = Eur, counts: Boolean = true) =
    Counted(s"r-$amount-$at", amount, currency, at, counts)
  private def daysAgo(d: Long) = now.minusSeconds(d * 86400)

  private def check(limits: Pair, recent: Vector[Counted], amount: Long = 5000L, currency: Currency = Eur) =
    Limits.check(limits, recent, currency, amount, now)

  test("no limits configured allows"):
    assertEquals(check(Pair(), Vector(counted(1000000L, daysAgo(1)))), None)

  test("the weekly limit exceeded is refused with the limit, the used and the remaining"):
    assertEquals(check(Pair(weekly = Some(10000L)), Vector(counted(9000L, daysAgo(3)))), Some(Block.Weekly(10000L, 9000L, 1000L)))

  test("the monthly limit exceeded is refused when the weekly is not"):
    assertEquals(
      check(Pair(Some(50000L), Some(20000L)), Vector(counted(18000L, daysAgo(1)))),
      Some(Block.Monthly(20000L, 18000L, 2000L))
    )

  test("weekly is checked before monthly"):
    assertEquals(check(Pair(Some(1000L), Some(1000L)), Vector.empty).map(_.ordinal), Some(Block.Weekly(0, 0, 0).ordinal))

  test("a limit not exceeded allows; exactly at the limit allows"):
    assertEquals(check(Pair(weekly = Some(10000L)), Vector(counted(1000L, daysAgo(3)))), None)
    assertEquals(check(Pair(weekly = Some(10000L)), Vector(counted(5000L, daysAgo(3)))), None)
    assertEquals(check(Pair(weekly = Some(10000L)), Vector(counted(5000L, daysAgo(3))), amount = 5001L).isDefined, true)

  test("cancelled, rejected, failed and reversed requests do not count"):
    assertEquals(check(Pair(weekly = Some(10000L)), Vector(counted(9000L, daysAgo(3), counts = false), counted(1000L, daysAgo(3)))), None)

  test("the counting statuses are the original's four"):
    assertEquals(Limits.counting, Set("pending_review", "approved", "awaiting_manual_payout", "completed"))

  test("limits are per currency: another currency's requests do not count against this one"):
    assertEquals(check(Pair(weekly = Some(10000L)), Vector(counted(9000L, daysAgo(1), Gbp))), None)
    assertEquals(check(Pair(weekly = Some(10000L)), Vector(counted(9000L, daysAgo(1), Gbp)), currency = Gbp).isDefined, true)

  test("zero is no limit"):
    assertEquals(check(Pair(Some(0L), Some(0L)), Vector(counted(9500L, daysAgo(3)))), None)

  test("an empty history allows an amount within the limit"):
    assertEquals(check(Pair(weekly = Some(5000L)), Vector.empty, amount = 100L), None)

  test("the rolling week: exactly seven days old is not counted, a second less is"):
    val limits = Pair(weekly = Some(5000L))
    assertEquals(check(limits, Vector(counted(4500L, daysAgo(7)))), None)
    assertEquals(check(limits, Vector(counted(4500L, daysAgo(7).plusSeconds(1)))).isDefined, true)
    assertEquals(check(limits, Vector(counted(4500L, daysAgo(8)))), None)

  test("the UTC calendar month: the last second of the previous month is not counted, the first of this one is"):
    val limits = Pair(monthly = Some(5000L))
    assertEquals(check(limits, Vector(counted(4500L, Instant.parse("2026-09-30T23:59:59Z")))), None)
    assertEquals(check(limits, Vector(counted(4500L, Instant.parse("2026-10-01T00:00:00Z")))).isDefined, true)
