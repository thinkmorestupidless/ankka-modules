package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{CasinoPlayers, CasinoRounds, PlayerRegistered, RoundSettled}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.players.reads.{ActivityView, RecentlyPlayedView}
import io.eitheror.casino.players.PlayersService
import io.eitheror.casino.players.player.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** T025: the service starts with every component, each prefix answers its audience, and the read and fact paths run. */
class SmokeSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  override def beforeAll(): Unit = kit.reset()

  test("every component of plan.md's table is registered, under its own id") {
    val ids = PlayersService.descriptors.map(_.componentId.toString)
    assertEquals(ids.distinct.size, ids.size, ids.diff(ids.distinct).mkString(", "))
    Seq("player", "player-session", "game-session", "message", "note", "fraud-case", "lookup-index", "brand-config",
      "currency-config", "allow-list", "players", "sessions", "messages", "notes", "fraud-cases", "wallet-summary",
      "recently-played", "activity", "players-publisher", "audit-publisher", "rg-limits-consumer", "session-limits",
      "realm-reconcile").foreach(id => assert(ids.contains(id), s"$id is not registered"))
  }

  test("each prefix answers its audience: 401 with no token, 501 for a shell, a filled route reached, never 404") {
    def status(method: String, path: String, headers: Seq[(String, String)]) =
      kit.request(method, path, if method == "GET" then None else Some("{}"), headers).status
    assertEquals(status("GET", kit.playerPath("/p-1"), kit.asVisitor), 401)
    assertEquals(status("GET", kit.playerPath("/p-1"), kit.asPlayer("p-1")), 501)
    assertEquals(status("POST", kit.playerPath("/login"), kit.asVisitor), 400)
    assertEquals(status("GET", kit.sessionPath("/s-1"), kit.asVisitor), 401)
    assertEquals(status("GET", kit.sessionPath("/s-1"), kit.asPlayer("p-1")), 501)
    assertEquals(status("GET", kit.operatorPath("/players"), kit.asVisitor), 401)
    assertEquals(status("GET", kit.operatorPath("/players"), kit.asOperator(roles = "brand_other")), 403)
    assertEquals(status("GET", kit.operatorPath("/players"), kit.asOperator()), 501)
    assertEquals(status("GET", s"/v1/casino/brands/${kit.slug}/modules/", kit.asOperator()), 501)
    assertEquals(status("GET", kit.servicePath("/p-1/status"), kit.asService("game-provider")), 501)
    assertEquals(status("POST", kit.realmPath("/webhook"), kit.asVisitor), 401)
  }

  test("a player token of another brand's realm is refused on the player routes") {
    val other = FakeRealm.jwt(io.eitheror.casino.players.Json.obj("iss" -> "https://auth.example/realms/casino-other",
      "eitheror_twin_id" -> "p-1", "session_state" -> "s"))
    assertEquals(kit.request("GET", kit.playerPath("/p-1"), None, kit.asToken(other)).status, 403)
  }

  test("a Registered event reaches PlayersView, and player_registered reaches casino.players") {
    val id = s"p-smoke-${UUID.randomUUID().toString.take(8)}"
    kit.components.forEventSourcedEntity(EntityId(id)).call(PlayerEntity.register)
      .invoke(Register(id, Profile(), s"$id@example.test", "EUR", Consents(), Registration(), Instant.now())): Unit
    assertEquals(kit.eventually(s"$id in the view")(PlayersView.one(kit.views, id)).playerId, id)
    kit.eventually(s"player_registered for $id")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerRegistered if f.playerId == id => f })
  }

  test("a settled round on casino.rounds is a line of the player's activity and a recently played game") {
    val id = s"p-round-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now()
    kit.publish(CasinoRounds, s"$id/EUR", RoundSettled(id, Currency.unsafe("EUR"), "r-1", "st8", "pr", "g-7", "gs-1",
      MinorUnits.unsafe(100), MinorUnits.unsafe(0), SignedMinorUnits(-100), "loss", Vector.empty, Vector.empty, Vector.empty, None, at))
    assertEquals(kit.eventually("the round in the activity")(ActivityView.ofPlayer(kit.views, id, None, 0, 10).headOption).eventType, "round_settled")
    assertEquals(kit.eventually("the game recently played")(RecentlyPlayedView.ofPlayer(kit.views, id, 10).headOption).gameId, "g-7")
  }

  test("the realm-reconcile timer has read the realm's sessions within 3 s") {
    kit.eventually("the realm asked for its live sessions", 3.seconds)(kit.realm.receivedOf("liveSessions").headOption)
  }

  test("the geo lookup answers nulls when ip-api is silent, and the country when it answers") {
    kit.geo.countries.put("8.8.4.4", "DE")
    val found = kit.request("GET", "/v1/casino/geo/lookup", None, kit.asVisitor :+ ("x-forwarded-for" -> "8.8.4.4"))
    assertEquals((found.status, found.json.get("country").asText, found.json.get("dialCode").asText), (200, "DE", "+49"))
    kit.geo.silent = true
    try
      val r = kit.request("GET", "/v1/casino/geo/lookup", None, kit.asVisitor :+ ("x-forwarded-for" -> "9.9.9.9"))
      assertEquals((r.status, r.json.get("country").isNull, r.json.get("dialCode").isNull), (200, true, true))
    finally kit.geo.silent = false
  }
