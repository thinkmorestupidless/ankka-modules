package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.config.{BrandConfig, BrandConfigEntity}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, Suspend}
import io.eitheror.casino.players.sessions.{PlayerSessionEntity, SessionStatus}

import java.time.Instant
import scala.concurrent.duration.*

/** US2's sign-in cases through the routes (FR-010 to FR-015, SC-003, SC-007). */
class SessionsSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player(id: String): PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def session(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(PlayerSessionEntity.get).invoke()
  private def login(email: String, headers: Seq[(String, String)] = Nil, password: String = "Passw0rd!"): Reply =
    kit.json("POST", kit.playerPath("/login"), Json.obj("email" -> email, "password" -> password, "deviceFingerprint" -> "fp-9"),
      kit.asVisitor ++ headers)

  test("a sign-in: 200 with the tokens and details; a session with the realm session and where it came from; the facts; the time stamped") {
    val (email, id) = kit.activePlayer("bonusCode" -> "B1", "country" -> "GB")
    val r = login(email, Seq("X-Forwarded-For" -> "203.0.113.5", "User-Agent" -> "Kit/1"))
    assertEquals(r.status, 200, r.text)
    assertEquals((r.str("playerId"), r.str("brandId"), r.str("currency"), r.str("country"), r.str("pendingBonusCode")),
      (id, kit.slug, "EUR", "GB", "B1"))
    assert(r.str("token").nonEmpty && r.str("refreshToken").nonEmpty)
    val sid = kit.sessionOf(r.str("token"))
    val s   = session(sid)
    assertEquals((s.playerId, s.realmSessionId, s.status), (id, kit.realmSessionOf(r.str("token")), SessionStatus.Active))
    assertEquals((s.networkAddress.reveal, s.userAgent.reveal, s.deviceFingerprint.reveal), ("203.0.113.5", "Kit/1", "fp-9"))
    kit.eventually("player_logged_in")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerLoggedIn if f.sessionId == sid => f })
    kit.eventually("player_session_started")(kit.facts(CasinoSessions).collectFirst { case f: PlayerSessionStarted if f.sessionId == sid => f })
    assert(player(id).lastSignInAt.isDefined)
  }

  test("a wrong password is 401 invalid_credentials; an unverified address 401 email_not_verified and no session") {
    val (email, _) = kit.activePlayer()
    assertEquals(login(email, password = "wrong-one").str("error"), "invalid_credentials")
    val pending = s"pending-${java.util.UUID.randomUUID().toString.take(6)}@example.test"
    kit.register("email" -> pending)
    val r = login(pending)
    assertEquals((r.status, r.str("error")), (401, "email_not_verified"))
    assertEquals(kit.realm.sessions.size, 0)
  }

  test("a suspended player: 403 account_restricted naming suspended, the realm session revoked, the refused attempt stamped") {
    val (email, id) = kit.activePlayer()
    kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.suspend).invoke(Suspend("kit", Some("op-1"), kit.now())): Unit
    kit.realm.clearReceived()
    val r = login(email)
    assertEquals((r.status, r.str("error"), r.str("reason")), (403, "account_restricted", "suspended"))
    assertEquals(kit.realm.received.flatMap(FakeRealm.callOf).filterNot(_ == "admin-token"), Vector("grant", "revoke"))
    assert(player(id).lastRefusedSignInAt.isDefined)
  }

  test("responsible gambling cooling off: 403 cooling_off with its end; silent: 403 account_restricted within the bound") {
    val (email, id) = kit.activePlayer()
    val until = Instant.parse("2030-01-01T00:00:00Z")
    kit.rg.coolingOff(id, until)
    val r = login(email)
    assertEquals((r.status, r.str("error"), r.str("until")), (403, "cooling_off", until.toString))
    kit.rg.logIn.clear()
    kit.rg.silent = true
    val started = System.nanoTime()
    val silent  = login(email)
    val took    = (System.nanoTime() - started) / 1_000_000
    kit.rg.silent = false
    assertEquals((silent.status, silent.str("error")), (403, "account_restricted"))
    assert(took < kit.settings.mayLogInBound.toMillis + 1500, s"took $took ms")
    assert(player(id).lastRefusedSignInAt.isDefined)
  }

  test("a realm token with no session claim: 401 missing_token and no session") {
    val (email, id) = kit.activePlayer()
    kit.realm.withoutSession = true
    val r = login(email)
    assertEquals((r.status, r.str("error")), (401, "missing_token"))
    assertEquals(player(id).openPlayerSessions, Set.empty[String])
  }

  test("refresh: 200 with the new tokens; a reused refresh 401 refresh_expired; the realm silent 503") {
    val (email, _) = kit.activePlayer()
    val refresh = login(email).str("refreshToken")
    val r = kit.json("POST", kit.playerPath("/refresh"), Json.obj("refreshToken" -> refresh), kit.asVisitor)
    assertEquals(r.status, 200, r.text)
    assert(r.str("accessToken").nonEmpty && r.str("refreshToken").nonEmpty && r.json.get("expiresIn").asLong > 0)
    val reused = kit.json("POST", kit.playerPath("/refresh"), Json.obj("refreshToken" -> refresh), kit.asVisitor)
    assertEquals((reused.status, reused.str("error")), (401, "refresh_expired"))
    assertEquals(kit.json("POST", kit.playerPath("/refresh"), Json.obj(), kit.asVisitor).str("error"), "missing_refresh_token")
    kit.realm.silent = true
    try
      val silent = kit.json("POST", kit.playerPath("/refresh"), Json.obj("refreshToken" -> r.str("refreshToken")), kit.asVisitor)
      assertEquals((silent.status, silent.str("error")), (503, "service_unavailable"))
    finally kit.realm.silent = false
  }

  test("sign-out: 200, the session completed, the realm session revoked, both facts closed logout; another player's id 403") {
    val (email, id) = kit.activePlayer()
    val token = login(email).str("token")
    val sid   = kit.sessionOf(token)
    val other = kit.request("POST", kit.playerPath(s"/someone-else/logout"), Some("{}"), kit.asToken(token))
    assertEquals((other.status, other.str("error")), (403, "player_id_mismatch"))
    val r = kit.request("POST", kit.playerPath(s"/$id/logout"), Some("{}"), kit.asToken(token))
    assertEquals((r.status, r.str("status")), (200, "logged_out"), r.text)
    assertEquals(session(sid).status, SessionStatus.Completed)
    assert(kit.realm.receivedOf("revoke").nonEmpty)
    val ended = kit.eventually("player_session_ended")(kit.facts(CasinoSessions).collectFirst { case f: PlayerSessionEnded if f.sessionId == sid => f })
    val out   = kit.eventually("player_logged_out")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerLoggedOut if f.sessionId == sid => f })
    assertEquals((ended.closeReason, out.closeReason), ("logout", "logout"))
    assert(!player(id).openPlayerSessions(sid))
  }

  test("the calls one sign-in makes: the realm's grant and may-log-in once each, nothing else") {
    val (email, _) = kit.activePlayer()
    kit.fakes.foreach(_.clearReceived())
    assertEquals(login(email).status, 200)
    assertEquals(kit.realm.received.flatMap(FakeRealm.callOf), Vector("grant"))
    assertEquals(kit.rg.asked("may-log-in").size, 1)
    assertEquals((kit.kyc.received, kit.engagement.received, kit.wallet.received, kit.ingest.received),
      (Vector.empty, Vector.empty, Vector.empty, Vector.empty))
  }

  test("one session at a time when the brand sets it: a second sign-in supersedes the first; otherwise both stay open") {
    val config = kit.components.forKeyValueEntity(EntityId(kit.slug))
    val (email, _) = kit.activePlayer()
    val first  = kit.sessionOf(login(email).str("token"))
    val second = kit.sessionOf(login(email).str("token"))
    assertEquals((session(first).status, session(second).status), (SessionStatus.Active, SessionStatus.Active))
    config.call(BrandConfigEntity.set).invoke(BrandConfig(singleSession = true)): Unit
    try
      val third = kit.sessionOf(login(email).str("token"))
      assertEquals(Vector(first, second).map(session(_).status), Vector(SessionStatus.Superseded, SessionStatus.Superseded))
      assertEquals(session(third).status, SessionStatus.Active)
      val fact = kit.eventually("superseded")(kit.facts(CasinoSessions).collectFirst { case f: PlayerSessionEnded if f.sessionId == first => f })
      assertEquals(fact.closeReason, "superseded")
    finally config.call(BrandConfigEntity.set).invoke(BrandConfig()): Unit
  }
