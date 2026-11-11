package io.eitheror.casino.reporting.ranges

import java.time.LocalDate

class RangesSuite extends munit.FunSuite:
  private val today = LocalDate.parse("2026-11-08")

  test("the default period is the first of the month to today") {
    assertEquals(Ranges.period(None, None, today), Right(Ranges.Period(LocalDate.parse("2026-11-01"), today)))
    assertEquals(Ranges.period(Some("2026-10-01"), None, today).map(_.from), Right(LocalDate.parse("2026-10-01")))
    assertEquals(Ranges.period(Some("01/10/2026"), None, today), Left("01/10/2026"))
  }

  test("an unknown range is 7d, as the original") {
    assertEquals(Ranges.range(Some("1y")), "7d")
    assertEquals(Ranges.range(None), "7d")
    assertEquals(Ranges.range(Some("today")), "today")
  }

  test("7 and 30 days are daily buckets ending today, oldest first") {
    val seven = Ranges.buckets("7d", today)
    assertEquals(seven.map(_.label), (6 to 0 by -1).map(d => today.minusDays(d).toString))
    assertEquals(Ranges.buckets("30d", today).size, 30)
  }

  test("90 days is 13 seven-day weeks labelled by their first day's ISO week") {
    val weeks = Ranges.buckets("90d", today)
    assertEquals(weeks.size, 13)
    assertEquals(weeks.last, Ranges.Bucket("2026-W45", LocalDate.parse("2026-11-02"), today))
    assertEquals(weeks.head.from, today.minusDays(90))
    assert(weeks.sliding(2).forall { case Seq(a, b) => b.from == a.to.plusDays(1) })
  }

  test("hour labels and the KPI history's days") {
    assertEquals(Ranges.hourLabel(7), "07:00")
    assertEquals(Ranges.historyDays(today).map(_._1), Seq("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Today"))
  }

  test("a period's days are inclusive") {
    val p = Ranges.Period(LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-02"))
    assertEquals(p.length, 4L)
    assertEquals(p.days.last, LocalDate.parse("2026-11-02"))
  }
