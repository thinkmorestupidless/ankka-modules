package io.eitheror.casino.engagement.profile

import java.time.{Instant, LocalDate}

/**
 * The deposit day (FR-009), from `daily_deposit_count_test.go` and `DailyDepositCountSpec`'s day cases;
 * the attribute cases of the same files are in `AttributesSuite`.
 */
class DepositDaySuite extends munit.FunSuite:
  private def at(s: String) = Instant.parse(s)
  private val today         = LocalDate.parse("2026-08-02")

  test("TestCurrentDepositDay_RollsOverAtThreeUTC: the day begins at 03:00 UTC, not midnight") {
    assertEquals(DepositDay.of(at("2026-08-02T02:59:59Z")), LocalDate.parse("2026-08-01"))
    assertEquals(DepositDay.of(at("2026-08-02T03:00:00Z")), LocalDate.parse("2026-08-02"))
    assertEquals(DepositDay.of(at("2026-08-02T00:00:00Z")), LocalDate.parse("2026-08-01"))
  }

  test("a play session across midnight stays on one deposit day") {
    assertEquals(DepositDay.of(at("2026-08-01T23:30:00Z")), DepositDay.of(at("2026-08-02T01:30:00Z")))
  }

  test("TestResetHourMatchesScala: the reset hour is 3") {
    assertEquals(DepositDay.ResetHourUtc, 3)
  }

  test("the next reset is the next 03:05 UTC strictly after now") {
    assertEquals(DepositDay.nextReset(at("2026-08-02T03:04:59Z")), at("2026-08-02T03:05:00Z"))
    assertEquals(DepositDay.nextReset(at("2026-08-02T03:05:00Z")), at("2026-08-03T03:05:00Z"))
    assertEquals(DepositDay.nextReset(at("2026-08-02T12:00:00Z")), at("2026-08-03T03:05:00Z"))
    assertEquals(DepositDay.nextReset(at("2026-08-01T23:59:59Z")), at("2026-08-02T03:05:00Z"))
    assertEquals(DepositDay.nextReset(at("2026-12-31T04:00:00Z")), at("2027-01-01T03:05:00Z"))
  }

  test("TestBuildIdentifyAttrs_DailyDepositCountCurrentDay: the count stamped today is read") {
    assertEquals(DepositDay.countToday(ProfileState(dailyDepositCount = 3, depositDay = Some(today)), today), 3)
  }

  test("TestBuildIdentifyAttrs_DailyDepositCountStaleDayReadsZero: a count from an earlier day reads 0") {
    val p = ProfileState(dailyDepositCount = 3, depositDay = Some(LocalDate.parse("2020-01-01")))
    assertEquals(DepositDay.countToday(p, today), 0)
  }

  test("a player who never deposited, or a count with no day stamp, reads 0") {
    assertEquals(DepositDay.countToday(ProfileState(), today), 0)
    assertEquals(DepositDay.countToday(ProfileState(dailyDepositCount = 3), today), 0)
  }

  test("a deposit at 02:59 counts for the day before, the day the reset at 03:05 zeroes") {
    val p = ProfileState(dailyDepositCount = 1, depositDay = Some(DepositDay.of(at("2026-08-02T02:59:00Z"))))
    assertEquals(DepositDay.countToday(p, DepositDay.of(at("2026-08-02T02:59:30Z"))), 1)
    assertEquals(DepositDay.countToday(p, DepositDay.of(at("2026-08-02T03:00:00Z"))), 0)
  }
