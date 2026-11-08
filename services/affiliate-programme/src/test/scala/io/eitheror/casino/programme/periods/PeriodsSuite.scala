package io.eitheror.casino.programme.periods

import java.time.{Instant, LocalDate, YearMonth, ZoneId, ZoneOffset}

class PeriodsSuite extends munit.FunSuite:
  import Periods.*

  private val joburg = ZoneId.of("Africa/Johannesburg")
  private val berlin = ZoneId.of("Europe/Berlin")

  test("a bet at 23:30 local on the month's last day is that month's, though it is the next month nowhere") {
    // 23:30 in Johannesburg (UTC+2) is 21:30 UTC: the same month either way.
    assertEquals(periodOf(Instant.parse("2026-08-31T21:30:00Z"), joburg), "2026-08")
  }

  test("an instant past local midnight on the first is the new month there, while UTC still says the old one") {
    val at = Instant.parse("2026-08-31T22:30:00Z") // 00:30 on 1 September in Johannesburg
    assertEquals(periodOf(at, joburg), "2026-09")
    assertEquals(periodOf(at, ZoneOffset.UTC), "2026-08")
  }

  test("a period ends at the first instant of the next month in the zone") {
    assertEquals(periodEnd("2026-08", joburg), Instant.parse("2026-08-31T22:00:00Z"))
    assertEquals(periodEnd("2026-08", ZoneOffset.UTC), Instant.parse("2026-09-01T00:00:00Z"))
    assertEquals(periodEnd("2026-12", ZoneOffset.UTC), Instant.parse("2027-01-01T00:00:00Z"))
  }

  test("a period's end across a daylight-saving change is the zone's midnight") {
    // Berlin changes to summer time on the last Sunday of March: April starts at 22:00 UTC on 31 March.
    assertEquals(periodEnd("2026-03", berlin), Instant.parse("2026-03-31T22:00:00Z"))
    assertEquals(periodEnd("2026-02", berlin), Instant.parse("2026-02-28T23:00:00Z"))
  }

  test("the grammar: a month, a month range, a day range, all time") {
    assertEquals(Range.parse("2026-10"), Some(Range.Months(YearMonth.of(2026, 10), YearMonth.of(2026, 10))))
    assertEquals(Range.parse("2026-08..2026-10").map(_.periods), Some(Vector("2026-08", "2026-09", "2026-10")))
    assertEquals(Range.parse("2026-10-03..2026-10-05"), Some(Range.Days(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5))))
    assertEquals(Range.parse("2026-10-03..2026-10-03"), Some(Range.Days(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 3))))
    assertEquals(Range.parse("all"), Some(Range.AllTime))
    assertEquals(Range.parse("2025-11..2026-02").map(_.periods), Some(Vector("2025-11", "2025-12", "2026-01", "2026-02")))
  }

  test("the original's refusals") {
    Seq("", "2026", "2026-1", "2026-13", "2026-00", "2026-10-32", "2026-02-30", "ALL", "all..all", "2026-10..",
      "..2026-10", "2026-10..2026-10-05", "2026-10-01..2026-11", "2026-10..2026-09", "2026-10-05..2026-10-03",
      "2026-01..2026-02..2026-03", "2026-10,2026-11", " 2026-10", "2026-10 ").foreach { token =>
      assertEquals(Range.parse(token), None, s"'$token'")
    }
  }

  test("at most 240 months") {
    assertEquals(Range.parse("1900-01..2999-12"), None)
    assertEquals(Range.parse("2000-01..2019-12").map(_.periods.size), Some(240))
    assertEquals(Range.parse("2000-01..2020-01"), None)
  }

  test("a month range holds by the fact's period; a day range by its local day; all time always") {
    val months = Range.parse("2026-08..2026-10").get
    assert(months.holds("2026-08", Instant.parse("2020-01-01T00:00:00Z"), joburg))
    assert(!months.holds("2026-11", Instant.parse("2026-10-05T00:00:00Z"), joburg))
    val days = Range.parse("2026-10-03..2026-10-04").get
    assert(days.holds("1999-01", Instant.parse("2026-10-04T21:59:00Z"), joburg))
    assert(!days.holds("2026-10", Instant.parse("2026-10-04T22:00:00Z"), joburg), "00:00 on the 5th locally")
    assert(Range.AllTime.holds("", Instant.EPOCH, joburg))
  }

  test("bounds in the zone") {
    assertEquals(Range.parse("2026-09").get.bounds(joburg),
      Some(Instant.parse("2026-08-31T22:00:00Z") -> Instant.parse("2026-09-30T22:00:00Z")))
    assertEquals(Range.AllTime.bounds(joburg), None)
  }
