package io.eitheror.casino.reporting.days

import java.time.{Duration, Instant, ZoneId}

class DaysSuite extends munit.FunSuite:
  private val jhb    = ZoneId.of("Africa/Johannesburg") // UTC+2, no daylight saving
  private val london = ZoneId.of("Europe/London")
  private val grace  = Duration.ofMinutes(15)

  test("23:30 in Johannesburg is that day there; 22:30 UTC is the next day there") {
    assertEquals(Days.dayOf(Instant.parse("2026-10-25T21:30:00Z"), jhb), "2026-10-25")
    assertEquals(Days.dayOf(Instant.parse("2026-10-25T22:30:00Z"), jhb), "2026-10-26")
    assertEquals(Days.hourOf(Instant.parse("2026-10-25T21:30:00Z"), jhb), 23)
  }

  test("a day closes 15 minutes after its midnight in the zone and not before") {
    val midnight = Instant.parse("2026-10-25T22:00:00Z")
    assert(!Days.closeDue("2026-10-25", jhb, grace, midnight.plus(Duration.ofMinutes(14))))
    assert(Days.closeDue("2026-10-25", jhb, grace, midnight.plus(grace)))
    assertEquals(Days.endOf("2026-10-25", jhb), midnight)
  }

  test("the 23-hour day: London's clocks go forward on 2026-03-29") {
    val day = "2026-03-29"
    assertEquals(Duration.between(Days.startOf(day, london), Days.endOf(day, london)), Duration.ofHours(23))
    assertEquals(Days.dayOf(Instant.parse("2026-03-29T22:59:59Z"), london), "2026-03-29")
    assertEquals(Days.dayOf(Instant.parse("2026-03-29T23:00:00Z"), london), "2026-03-30")
  }

  test("the 25-hour day: London's clocks go back on 2026-10-25") {
    val day = "2026-10-25"
    assertEquals(Duration.between(Days.startOf(day, london), Days.endOf(day, london)), Duration.ofHours(25))
    assertEquals(Days.dayOf(Instant.parse("2026-10-25T23:59:59Z"), london), "2026-10-25")
    assertEquals(Days.endOf(day, london), Instant.parse("2026-10-26T00:00:00Z"))
  }

  test("the next close is the coming midnight plus the grace") {
    assertEquals(Days.nextClose(Instant.parse("2026-10-25T12:00:00Z"), jhb, grace), Instant.parse("2026-10-25T22:15:00Z"))
    assertEquals(Days.nextClose(Instant.parse("2026-10-25T22:05:00Z"), jhb, grace), Instant.parse("2026-10-25T22:15:00Z"))
    assertEquals(Days.nextClose(Instant.parse("2026-10-25T22:20:00Z"), jhb, grace), Instant.parse("2026-10-26T22:15:00Z"))
  }

  test("attribution's rule and this one give the same close for every hour of a 25-hour week (spec 012 FR-016)") {
    // attribution's Days, as it stands, restated here: the copy must not drift from it.
    def attributionClose(day: String, zone: ZoneId): Instant =
      java.time.LocalDate.parse(day).plusDays(1).atStartOfDay(zone).toInstant.plus(grace)
    val start = Instant.parse("2026-10-22T00:00:00Z")
    (0 until 24 * 7).map(h => start.plus(Duration.ofHours(h))).foreach { at =>
      Seq(jhb, london, ZoneId.of("UTC")).foreach { zone =>
        val day = Days.dayOf(at, zone)
        assertEquals(Days.endOf(day, zone).plus(grace), attributionClose(day, zone), s"$at in $zone")
      }
    }
  }
