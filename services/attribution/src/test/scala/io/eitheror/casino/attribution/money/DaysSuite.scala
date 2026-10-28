package io.eitheror.casino.attribution.money

import java.time.{Duration, Instant, LocalDate, ZoneId}

class DaysSuite extends munit.FunSuite:
  private val jhb   = ZoneId.of("Africa/Johannesburg") // UTC+2, no daylight saving
  private val grace = Duration.ofMinutes(15)

  test("23:30 in Johannesburg is that day there; 21:30 UTC is the next day there") {
    assertEquals(Days.dayOf(Instant.parse("2026-10-25T21:30:00Z"), jhb), "2026-10-25")
    assertEquals(Days.dayOf(Instant.parse("2026-10-25T21:30:00Z"), ZoneId.of("UTC")), "2026-10-25")
    assertEquals(Days.dayOf(Instant.parse("2026-10-25T22:30:00Z"), jhb), "2026-10-26")
  }

  test("a day closes 15 minutes after its midnight in the zone and not before") {
    val midnight = Instant.parse("2026-10-25T22:00:00Z") // 00:00 on the 26th in Johannesburg
    assert(!Days.closeDue("2026-10-25", jhb, grace, midnight.plus(Duration.ofMinutes(14))))
    assert(Days.closeDue("2026-10-25", jhb, grace, midnight.plus(grace)))
    assertEquals(Days.endOf("2026-10-25", jhb), midnight)
  }

  test("the next close is the coming midnight plus the grace") {
    assertEquals(Days.nextClose(Instant.parse("2026-10-25T12:00:00Z"), jhb, grace), Instant.parse("2026-10-25T22:15:00Z"))
    assertEquals(Days.nextClose(Instant.parse("2026-10-25T22:05:00Z"), jhb, grace), Instant.parse("2026-10-25T22:15:00Z"))
    assertEquals(Days.nextClose(Instant.parse("2026-10-25T22:20:00Z"), jhb, grace), Instant.parse("2026-10-26T22:15:00Z"))
  }

  test("12 months from 2026-11-01 is 2027-11-01 when legacy tags keep coming") {
    val first = LocalDate.parse("2026-11-01")
    assertEquals(Days.endOfTransition(first, Some(LocalDate.parse("2027-10-30")), LocalDate.parse("2027-10-31")), None)
    assertEquals(Days.endOfTransition(first, Some(LocalDate.parse("2027-10-30")), LocalDate.parse("2027-11-01")),
      Some(LocalDate.parse("2027-11-01") -> Days.TwelveMonths))
  }

  test("90 days after the last legacy day, or after the first day when none was presented; the earlier wins") {
    val first = LocalDate.parse("2026-11-01")
    assertEquals(Days.endOfTransition(first, Some(LocalDate.parse("2027-01-10")), LocalDate.parse("2027-04-10")),
      Some(LocalDate.parse("2027-04-10") -> Days.NinetyDaysWithout))
    assertEquals(Days.endOfTransition(first, Some(LocalDate.parse("2027-01-10")), LocalDate.parse("2027-04-09")), None)
    assertEquals(Days.endOfTransition(first, None, LocalDate.parse("2027-01-30")), Some(LocalDate.parse("2027-01-30") -> Days.NinetyDaysWithout))
    assertEquals(Days.transitionEndsAt(first, None), LocalDate.parse("2027-01-30"))
  }
