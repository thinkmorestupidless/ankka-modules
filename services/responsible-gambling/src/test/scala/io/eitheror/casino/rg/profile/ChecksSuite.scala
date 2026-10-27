package io.eitheror.casino.rg.profile

import java.time.{Duration, Instant}

/** Every row of data-model.md's *Checks*, in its order. */
class ChecksSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-10-27T12:00:00Z")

  private def withLimit(p: ProfileState, t: LimitType, value: Long, currency: Option[String] = Some("EUR")): ProfileState =
    val key = LimitKey.of(t, currency)
    p.copy(limits = p.limits + (key.id -> Limit(key, value, now, Actor.Player)))

  private val nothing  = ProfileState("p1")
  private val breakTil = now.plus(Duration.ofDays(2))
  private val onBreak  = nothing.copy(break = Some(Break(breakTil, now, "c1", Actor.Player)))
  private val excluded = onBreak.copy(exclusion = Some(Exclusion(true, None, now, "player", "c2")))
  private val none     = Sums(0, 0, 0)

  test("may-log-in: excluded over a break, a break naming its end, otherwise allowed"):
    assertEquals(Checks.mayLogIn(excluded, now).decision, "excluded")
    assertEquals(Checks.mayLogIn(onBreak, now), CheckAnswer("cooling_off", endsAt = Some(breakTil)))
    assertEquals(Checks.mayLogIn(nothing, now).decision, "allowed")

  test("a break past its end no longer refuses, though its timer has not yet fired"):
    assertEquals(Checks.mayLogIn(onBreak, breakTil).decision, "allowed")

  test("may-deposit: exactly the remainder allowed, a minor unit over refused naming the limit"):
    val p = withLimit(nothing, LimitType.DepositDaily, 10_000)
    assertEquals(Checks.mayDeposit(p, now, "EUR", 4_000, Sums(6_000, 6_000, 6_000)).decision, "allowed")
    assertEquals(Checks.mayDeposit(p, now, "EUR", 4_001, Sums(6_000, 6_000, 6_000)),
      CheckAnswer("over_limit", period = Some("daily"), limit = Some(10_000), used = Some(6_000), remaining = Some(4_000)))

  test("may-deposit: the first of daily, weekly, monthly that the amount passes"):
    val p = withLimit(withLimit(withLimit(nothing, LimitType.DepositDaily, 10_000), LimitType.DepositWeekly, 20_000), LimitType.DepositMonthly, 30_000)
    assertEquals(Checks.mayDeposit(p, now, "EUR", 5_000, Sums(0, 18_000, 29_000)).period, Some("weekly"))
    assertEquals(Checks.mayDeposit(p, now, "EUR", 11_000, Sums(0, 18_000, 29_000)).period, Some("daily"))
    assertEquals(Checks.mayDeposit(p, now, "EUR", 1_500, Sums(0, 18_000, 29_000)).period, Some("monthly"))

  test("may-deposit: used beyond the limit leaves nothing"):
    val p = withLimit(nothing, LimitType.DepositDaily, 10_000)
    assertEquals(Checks.mayDeposit(p, now, "EUR", 1, Sums(12_000, 0, 0)).remaining, Some(0L))

  test("may-deposit: a limit in another currency does not apply"):
    val p = withLimit(nothing, LimitType.DepositDaily, 10_000, Some("GBP"))
    assertEquals(Checks.mayDeposit(p, now, "EUR", 50_000, none).decision, "allowed")

  test("may-deposit: excluded, then cooling off, before any limit"):
    val p = withLimit(excluded, LimitType.DepositDaily, 10)
    assertEquals(Checks.mayDeposit(p, now, "EUR", 50_000, none).decision, "excluded")
    assertEquals(Checks.mayDeposit(withLimit(onBreak, LimitType.DepositDaily, 10), now, "EUR", 50_000, none).decision, "cooling_off")

  test("may-bet: no loss limit needs no sums"):
    assertEquals(Checks.mayBet(nothing, now, "EUR", 500, None).decision, "allowed")
    assertEquals(Checks.mayBet(withLimit(nothing, LimitType.DepositDaily, 10), now, "EUR", 500, None).decision, "allowed")

  test("may-bet: a loss limit with no sums carried is unavailable (R6)"):
    assertEquals(Checks.mayBet(withLimit(nothing, LimitType.LossWeekly, 10_000), now, "EUR", 500, None).decision, "unavailable")

  test("may-bet: a stake that takes a window past its loss limit is spending_limit"):
    val p = withLimit(nothing, LimitType.LossWeekly, 10_000)
    assertEquals(Checks.mayBet(p, now, "EUR", 2_000, Some(Sums(0, 8_000, 8_000))).decision, "allowed")
    assertEquals(Checks.mayBet(p, now, "EUR", 2_001, Some(Sums(0, 8_000, 8_000))),
      CheckAnswer("spending_limit", period = Some("weekly"), limit = Some(10_000), used = Some(8_000), remaining = Some(2_000)))

  test("may-bet: excluded and cooling off refuse before the sums are looked at"):
    assertEquals(Checks.mayBet(excluded, now, "EUR", 1, None).decision, "excluded")
    assertEquals(Checks.mayBet(onBreak, now, "EUR", 1, None).decision, "cooling_off")

  test("may-play: excluded, cooling off, the session limit reached at the limit, otherwise allowed with the figures"):
    assertEquals(Checks.mayPlay(excluded, now, 0), PlayAnswer(false, Some("self_excluded")))
    assertEquals(Checks.mayPlay(onBreak, now, 0), PlayAnswer(false, Some("cooling_off"), coolingOffUntil = Some(breakTil)))
    val limited = withLimit(nothing, LimitType.SessionMinutes, 60, None)
    assertEquals(Checks.mayPlay(limited, now, 60), PlayAnswer(false, Some("session_limit_reached"), limitMinutes = Some(60), usedMinutes = Some(60)))
    assertEquals(Checks.mayPlay(limited, now, 59), PlayAnswer(true, sessionLimitMinutes = Some(60), minutesUsed = Some(59)))
    assertEquals(Checks.mayPlay(nothing, now, 300), PlayAnswer(true, minutesUsed = Some(300)))
