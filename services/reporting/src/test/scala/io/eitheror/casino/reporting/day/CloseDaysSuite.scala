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

  /** Every consumer past any day this suite closes. */
  private val caughtUp = () => ToDaysConsumers.names(settings).toVector.map(c =>
    ConsumerProgress(c, ProgressState(c, Some(Instant.parse("2030-01-01T00:00:00Z")), Some(Instant.parse("2030-01-01T00:00:00Z")))))
  private val longAgo = () => Instant.parse("2026-01-01T00:00:00Z")

  test("closes each open day once its end in the zone plus the grace has passed, and not before") {
    val r     = Recorded(_ => "{}".getBytes)
    val clock = MovableClock.at(local("2026-11-09T00:10"))
    val rows  = Vector(OpenDayRow(2, "2026-11-07"), OpenDayRow(2, "2026-11-08"))
    val closing = DayClosing(r.client, _ => rows, settings, _ => CloseInputs(), () => clock.instant(), caughtUp, longAgo)
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
      () => local("2026-11-09T12:00"), caughtUp, longAgo).run(): Unit
    assertEquals(asked, "2026-11-09")
    assertEquals(closes(r).head.as[Close](using BrandDay.given_Serializer_Close).inputs, inputs)
  }

  test("a closed row is not closed again") {
    val r = Recorded(_ => "{}".getBytes)
    val done = DayClosing(r.client, _ => Vector(OpenDayRow(2, "2026-11-01", open = false)), settings, _ => CloseInputs(),
      () => local("2026-11-09T12:00"), caughtUp, longAgo).run()
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

  // ── a due day waits for its consumers (research R7) ───────────────────────────────────────────

  /** The 8th ends at 2026-11-08T22:00Z (midnight in Johannesburg); with the grace, a fact at or after 22:15Z is past it. */
  private val past8 = Instant.parse("2026-11-08T22:15:00Z")
  private val noon9 = local("2026-11-09T12:00")

  private def run(progress: Vector[ConsumerProgress], started: Instant = longAgo(), at: Instant = noon9): Vector[String] =
    val r = Recorded(_ => "{}".getBytes)
    DayClosing(r.client, _ => Vector(OpenDayRow(2, "2026-11-08")), settings, _ => CloseInputs(), () => at, () => progress, () => started).run()

  /** Every consumer past the 8th but `consumer`, whose progress is `state`. */
  private def allBut(consumer: String, state: ProgressState): Vector[ConsumerProgress] =
    ToDaysConsumers.names(settings).toVector.map(c =>
      if c == consumer then ConsumerProgress(c, state) else ConsumerProgress(c, ProgressState(c, Some(past8), Some(noon9))))

  test("behind: a consumer that read a minute ago and has folded nothing past the day's end and the grace holds it") {
    val behind = ProgressState("to-days-wallets", Some(past8.minusSeconds(1)), Some(noon9.minusSeconds(60)))
    assertEquals(run(allBut("to-days-wallets", behind)), Vector.empty)
    assertEquals(DayClosing.behind(allBut("to-days-wallets", behind), "2026-11-08", settings, longAgo(), noon9), Vector("to-days-wallets"))
  }

  test("caught up: every consumer has folded a fact at or after the day's end plus the grace") {
    val at = ProgressState("to-days-wallets", Some(past8), Some(noon9))
    assertEquals(run(allBut("to-days-wallets", at)), Vector("2026-11-08"))
  }

  test("idle: a consumer behind the day that has read nothing for CLOSE_IDLE no longer holds it, one minute short it does") {
    val quiet = ProgressState("to-days-grants", Some(Instant.parse("2026-11-01T10:00:00Z")), Some(noon9.minus(settings.closeIdle)))
    assertEquals(run(allBut("to-days-grants", quiet)), Vector("2026-11-08"))
    val recent = quiet.copy(activeAt = Some(noon9.minus(settings.closeIdle).plusSeconds(60)))
    assertEquals(run(allBut("to-days-grants", recent)), Vector.empty)
  }

  test("never started: a consumer with no progress holds the day until CLOSE_IDLE after the service started") {
    val none = allBut("to-days-players", ProgressState())
    assertEquals(run(none, started = noon9.minus(settings.closeIdle).plusSeconds(1)), Vector.empty)
    assertEquals(run(none, started = noon9.minus(settings.closeIdle)), Vector("2026-11-08"))
  }

  test("restarted: a consumer whose last read was before the restart is given CLOSE_IDLE from the start to resume") {
    val before = ProgressState("to-days-wallets", Some(past8.minusSeconds(3600)), Some(Instant.parse("2026-11-08T21:00:00Z")))
    assertEquals(run(allBut("to-days-wallets", before), started = noon9.minusSeconds(60)), Vector.empty)
    assertEquals(run(allBut("to-days-wallets", before), started = noon9.minus(settings.closeIdle)), Vector("2026-11-08"))
  }

  test("the progress is read only when a day is due") {
    var asked = 0
    val r = Recorded(_ => "{}".getBytes)
    DayClosing(r.client, _ => Vector(OpenDayRow(2, "2026-11-08")), settings, _ => CloseInputs(), () => local("2026-11-09T00:05"),
      () => { asked += 1; Vector.empty }, longAgo).run(): Unit
    assertEquals(asked, 0)
  }
