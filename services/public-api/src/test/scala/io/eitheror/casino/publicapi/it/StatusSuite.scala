package io.eitheror.casino.publicapi.it

import io.eitheror.casino.publicapi.wire.Times

import scala.concurrent.duration.*

/** T084: `GET /public-api/{brand}/status` for the brand's operators, on `SharedKit.ranked`. */
class StatusSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = Ranking.kit
  private def status(headers: Seq[(String, String)] = kit.asStaff(Nil), brand: String = kit.brand) =
    kit.request("GET", s"/public-api/$brand/status", headers = headers)

  test("after a run: when it was computed, the snapshot, not stale, and each view's latest record") {
    val pl = Ranking.player(); Ranking.registered(Map(pl -> "active"))
    val round = Ranking.play(pl, 100, "DKK", hoursAgo = 1)
    Games.seen(kit, Vector(round))
    val p = Ranking.compute()
    val n = status().json
    val at = Times.of(p.currentSlot.get._2.computedAt.get)
    assertEquals(n.path("statsComputedAt").asText().take(19), at.take(19))
    assertEquals(n.path("statsStale").asBoolean(), false)
    assertEquals(n.path("snapshot").path("id").asText(), Ranking.snapshot(p))
    Vector("players", "sessions", "rounds", "bets", "transactions", "bonusAwards").foreach(v => assert(n.path("views").has(v), s"$v in $n"))
    assert(n.path("views").path("players").path("lastAppliedAt").asText().nonEmpty)
    assert(n.path("views").path("rounds").path("lastAppliedAt").asText().nonEmpty)
  }

  test("stale once the last run is more than three hours old") {
    Ranking.compute(): Unit
    kit.moveClock(3.hours + 1.minute)
    assertEquals(status().json.path("statsStale").asBoolean(), true)
    Ranking.compute(): Unit
    assertEquals(status().json.path("statsStale").asBoolean(), false)
  }

  test("an operator of another brand is refused, another brand's segment is 404, a key is not an operator") {
    assertEquals(status(kit.asStaff(Seq("brand_spinvibe"), brandRole = false)).status, 403)
    assertEquals(status(kit.asStaff(Seq("platform_admin"), brandRole = false)).status, 200)
    assertEquals(status(brand = "spinvibe").status, 404)
    assertEquals(kit.request("GET", s"/public-api/${kit.brand}/status", headers = Seq("Authorization" -> s"Bearer ${Ranking.key.secret}")).status, 401)
  }
