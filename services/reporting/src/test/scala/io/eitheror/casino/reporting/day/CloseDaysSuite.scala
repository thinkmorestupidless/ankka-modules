package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.testkit.{MovableClock, ViewTestKit}
import io.eitheror.casino.reporting.{Recorded, Settings}
import io.eitheror.casino.reporting.days.Days

import java.time.{Duration, Instant, LocalDateTime, ZoneId}
import scala.concurrent.duration.*

/** T027: the closer's run over a clock it moves, and the `OpenDays` rows it reads. */
class CloseDaysSuite extends munit.FunSuite:
  private val zone     = ZoneId.of("Africa/Johannesburg")
  private val settings = Settings("goldvibe", reportingZone = zone, closeGrace = Duration.ofMinutes(15), generation = 2)
  private def local(t: String) = LocalDateTime.parse(t).atZone(zone).toInstant

  private def closes(r: Recorded) = r.calls.filter(_.method == "close")

  test("closes each open day once its end in the zone plus the grace has passed, and not before") {
    val r     = Recorded(_ => "{}".getBytes)
    val clock = MovableClock.at(local("2026-11-09T00:10"))
    val rows  = Vector(OpenDayRow(2, "2026-11-07"), OpenDayRow(2, "2026-11-08"))
    val closing = DayClosing(r.client, _ => rows, settings, _ => CloseInputs(), () => clock.instant())
    // 00:10 on the 9th: the 8th ended ten minutes ago, inside the grace; the 7th is long due
    assertEquals(closing.run(), Vector("2026-11-07"))
    clock.moveBy(5.minutes)
    assertEquals(closing.run(), Vector("2026-11-07", "2026-11-08"))
    assertEquals(closes(r).map(_.entity), Vector("g2/2026-11-07", "g2/2026-11-07", "g2/2026-11-08"))
    val sent = closes(r).last.as[Close](using BrandDay.given_Serializer_Close)
    assertEquals((sent.at, sent.zone), (clock.instant(), "Africa/Johannesburg"))
  }

  test("the close's instant is attribution's: midnight in the zone plus the grace") {
    assertEquals(Days.endOf("2026-11-08", zone).plus(settings.closeGrace), Instant.parse("2026-11-08T22:15:00Z"))
  }

  test("asks for the open days before the zone's today and hands each close its inputs") {
    val r     = Recorded(_ => "{}".getBytes)
    var asked = ""
    val inputs = CloseInputs(liability = Map("EUR" -> 700))
    DayClosing(r.client, d => { asked = d; Vector(OpenDayRow(2, "2026-11-08")) }, settings, d => if d == "2026-11-08" then inputs else CloseInputs(),
      () => local("2026-11-09T12:00")).run(): Unit
    assertEquals(asked, "2026-11-09")
    assertEquals(closes(r).head.as[Close](using BrandDay.given_Serializer_Close).inputs, inputs)
  }

  test("a closed row is not closed again") {
    val r = Recorded(_ => "{}".getBytes)
    val done = DayClosing(r.client, _ => Vector(OpenDayRow(2, "2026-11-01", open = false)), settings, _ => CloseInputs(),
      () => local("2026-11-09T12:00")).run()
    assertEquals((done, closes(r)), (Vector.empty, Vector.empty))
  }

  test("OpenDays: a day's first event opens its row, its DayFixed closes it, a later correction leaves it closed") {
    val kit   = ViewTestKit(OpenDays)
    val fold  = DayEvent.Folded("a", 1, Delta("EUR"))
    val fixed = DayEvent.DayFixed("UTC", Instant.parse("2026-11-09T00:15:00Z"), Map.empty, Vector.empty, Vector.empty)
    kit.change("g2/2026-11-08", fold): Unit
    assertEquals(kit.row("g2/2026-11-08"), Some(OpenDayRow(2, "2026-11-08")))
    kit.change("g2/2026-11-08", fold.copy(seq = 2)): Unit
    kit.change("g2/2026-11-08", fixed): Unit
    assertEquals(kit.row("g2/2026-11-08"), Some(OpenDayRow(2, "2026-11-08", open = false, Some(fixed.closedAt))))
    kit.change("g2/2026-11-08", DayEvent.Corrected("a", 3, fixed.closedAt, Delta("EUR"))): Unit
    assertEquals(kit.row("g2/2026-11-08").map(_.open), Some(false))
    assert(kit.queries.exists(_.name == "open-before"))
  }
