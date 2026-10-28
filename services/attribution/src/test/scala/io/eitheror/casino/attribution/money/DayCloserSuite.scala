package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.{Recorded, Settings}
import io.eitheror.casino.attribution.settings.{BrandSettings, EndTransition, SettingsCodecs, TransitionEnd}

import com.thinkmorestupidless.ankka.core.Serializers.given

import java.time.{Duration, Instant, ZoneId}

/** The day closer's run (T026), called directly over a client that records every call. */
class DayCloserSuite extends munit.FunSuite with LogCapturing:
  private val zone     = ZoneId.of("Africa/Johannesburg")
  private val settings = Settings("goldvibe", zone, closeGrace = Duration.ofMinutes(15))
  private def at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant

  private def recorded(brand: BrandSettings) =
    Recorded(c => if c.component == "settings" then SettingsCodecs.given_Serializer_BrandSettings.toBytes(brand) else "{}".getBytes)

  private val started = BrandSettings(firstDay = Some("2026-10-01"), lastLegacyDay = Some("2026-10-20"))

  private def closes(r: Recorded) = r.calls.filter(_.method == "close").map(_.entity)

  test("closes every open day due by the grace, leaves the rest") {
    val r = recorded(started)
    val rows = Vector(DayRow("p-1", "EUR", "2026-10-24"), DayRow("p-2", "EUR", "2026-10-23"))
    // 00:20 on the 25th in the zone: the 24th ended 20 minutes ago, past the 15-minute grace.
    val done = DayClosing(r.client, _ => rows, settings, () => at("2026-10-25T00:20")).run()
    assertEquals(done.closed, Vector("p-1/EUR/2026-10-24", "p-2/EUR/2026-10-23"))
    assertEquals(closes(r), Vector("p-1/EUR/2026-10-24", "p-2/EUR/2026-10-23"))
    val early = recorded(started)
    assertEquals(DayClosing(early.client, _ => rows.take(1), settings, () => at("2026-10-25T00:10")).run().closed, Vector.empty)
    assertEquals(closes(early), Vector.empty)
  }

  test("asks for the open days before the zone's today") {
    var asked = ""
    DayClosing(recorded(started).client, d => { asked = d; Vector.empty }, settings, () => at("2026-10-25T00:20")).run(): Unit
    assertEquals(asked, "2026-10-25")
  }

  test("ends the transition 90 days after the last legacy tag, once due") {
    val r = recorded(started)
    val done = DayClosing(r.client, _ => Vector.empty, settings, () => at("2027-01-18T12:00")).run()
    assertEquals(done.transitionEnded, Some("2027-01-18" -> "90_days_without_legacy"))
    val call = r.calls.find(_.method == "end-transition").get
    assertEquals(call.as[EndTransition](using SettingsCodecs.given_Serializer_EndTransition), EndTransition("2027-01-18", "90_days_without_legacy"))
    val before = recorded(started)
    assertEquals(DayClosing(before.client, _ => Vector.empty, settings, () => at("2027-01-17T12:00")).run().transitionEnded, None)
    val over = recorded(started.copy(transitionEnd = Some(TransitionEnd("2027-01-18", "90_days_without_legacy"))))
    assertEquals(DayClosing(over.client, _ => Vector.empty, settings, () => at("2027-06-01T12:00")).run().transitionEnded, None)
    assert(!over.calls.exists(_.method == "end-transition"))
  }

  test("prunes legacy counts older than 90 days; with nothing to do it only reads the settings") {
    val r = recorded(started.copy(legacyDaily = Map("2026-07-01" -> 2L, "2026-10-20" -> 1L)))
    DayClosing(r.client, _ => Vector.empty, settings, () => at("2026-10-25T12:00")).run(): Unit
    assertEquals(r.calls.filter(_.method == "prune-legacy").map(_.as[String]), Vector("2026-10-25"))
    val idle = recorded(BrandSettings())
    assertEquals(DayClosing(idle.client, _ => Vector.empty, settings, () => Instant.parse("2026-10-25T12:00:00Z")).run(), Closing(Vector.empty, None))
    assertEquals(idle.calls.map(_.method), Vector("get"))
  }
