package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.config.{BrandConfig, BrandConfigEntity}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, Suspend}
import io.eitheror.casino.players.sessions.{GameSessionEntity, GameSessionState, PlayerSessionEntity, SessionLimits, SessionStatus}

import java.time.Instant
import scala.concurrent.duration.*

/** US2's sign-in cases through the routes (FR-010 to FR-015, SC-003, SC-007). */
class SessionsSuite extends munit.FunSuite with LogCapturing:
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
    assertEquals(kit.realm.received.flatMap(FakeRealm.callOf).filterNot(Set("admin-token", "liveSessions")), Vector("grant", "revoke"))
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

  // ── game sessions (US3; FR-016 to FR-022) ───────────────────────────────────────────────────────

  private def game(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(GameSessionEntity.get).invoke()

  /** A signed-in player: the player, the token and the player session. */
  private def signedIn(): (String, String, String) =
    val (email, id) = kit.activePlayer()
    val token = login(email).str("token")
    (id, token, kit.sessionOf(token))

  private def launch(playerId: String, playerSession: Option[String] = None, caller: String = "game-provider"): Reply =
    kit.json("POST", kit.servicePath(s"/$playerId/game-sessions"),
      Json.obj("gameId" -> "g-7", "currency" -> "EUR", "demo" -> false, "playerSessionId" -> playerSession), kit.asService(caller))

  private def limit(playerId: String, minutes: Long): Unit =
    kit.publish(CasinoPlayers, playerId, RgLimitApplied(playerId, "SESSION_MINUTES", None, Some(minutes), "player", kit.now()))
    kit.eventually("the limit")(Option.when(player(playerId).sessionLimitMinutes.contains(minutes.toInt))(()))

  private def endMine(token: String, sessionId: String): Reply =
    kit.request("PATCH", kit.sessionPath(s"/$sessionId/end"), Some("{}"), kit.asToken(token))

  test("a launch: 201 with the id, the game session under the player session, game_session_started; no outbound call") {
    val (id, _, ps) = signedIn()
    kit.fakes.foreach(_.clearReceived())
    val r = launch(id, Some(ps))
    assertEquals(r.status, 201, r.text)
    val gs = r.str("sessionId")
    val g  = game(gs)
    assertEquals((g.playerId, g.playerSessionId, g.gameId, g.currency, g.status), (id, ps, "g-7", "EUR", GameSessionState.Active))
    assert(session(ps).gameSessions.contains(gs))
    val f = kit.eventually("game_session_started")(kit.facts(CasinoSessions).collectFirst { case f: GameSessionStarted if f.sessionId == gs => f })
    assertEquals((f.gameId, f.playerSessionId), ("g-7", ps))
    assertEquals(kit.fakes.filterNot(_ == kit.realm).flatMap(_.received), Seq.empty)
    assertEquals(kit.realm.received.flatMap(FakeRealm.callOf).filterNot(Set("admin-token", "liveSessions")), Vector.empty, "only the reconciler")
    assertEquals(kit.timerDueIn(SessionLimits.name(gs)), None)
  }

  test("a launch refused: another service 403; a player token 403; no open player session 409 session_expired; suspended 403 naming it") {
    val (id, token, ps) = signedIn()
    assertEquals(launch(id, caller = "wallet").status, 403)
    assertEquals(kit.json("POST", kit.servicePath(s"/$id/game-sessions"), Json.obj("gameId" -> "g"), kit.asToken(token)).status, 403)
    assertEquals(kit.request("POST", kit.playerPath(s"/$id/logout"), Some("{}"), kit.asToken(token)).status, 200)
    val expired = launch(id, Some(ps))
    assertEquals((expired.status, expired.str("error")), (409, "session_expired"))
    kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.suspend).invoke(Suspend("kit", None, kit.now())): Unit
    val r = launch(id)
    assertEquals((r.status, r.str("error"), r.str("reason")), (403, "account_restricted", "suspended"))
    assertEquals(launch("nobody").status, 404)
  }

  test("the limit: 30 minutes with 25 played today sets the timer at 5; firing ends it limit; reached is 403 with the minutes") {
    val (id, token, ps) = signedIn()
    val first = launch(id, Some(ps)).str("sessionId")
    kit.advance(java.time.Duration.ofMinutes(25))
    assertEquals(endMine(token, first).json.get("durationMinutes").asLong, 25L)
    limit(id, 30)
    val gs  = launch(id, Some(ps)).str("sessionId")
    val due = kit.timerDueIn(SessionLimits.name(gs)).getOrElse(fail("no timer"))
    assert(due > 290 && due <= 300, s"due in $due s")
    kit.advance(java.time.Duration.ofMinutes(5))
    kit.fireTimer(SessionLimits.name(gs))
    kit.eventually("ended limit", 5.seconds)(Option.when(game(gs).endReason.contains("limit"))(()))
    val f = kit.eventually("game_session_ended")(kit.facts(CasinoSessions).collectFirst { case f: GameSessionEnded if f.sessionId == gs => f })
    assertEquals(f.closeReason, Some("limit"))
    val reached = launch(id, Some(ps))
    assertEquals((reached.status, reached.str("error")), (403, "session_limit_reached"), reached.text)
    assertEquals((reached.json.get("limitMinutes").asInt, reached.json.get("usedMinutes").asInt), (30, 30))
  }

  test("a new limit under the minutes played ends the open game session at once") {
    val (id, _, ps) = signedIn()
    val gs = launch(id, Some(ps)).str("sessionId")
    kit.advance(java.time.Duration.ofMinutes(25))
    limit(id, 20)
    kit.eventually("ended limit", 5.seconds)(Option.when(game(gs).endReason.contains("limit"))(()))
  }

  test("the player ends their own: 200 with the minutes, again 200 and no second fact; another's 403; none 404; reads") {
    val (id, token, ps)        = signedIn()
    val (other, _, otherPs)    = signedIn()
    val gs     = launch(id, Some(ps)).str("sessionId")
    val theirs = launch(other, Some(otherPs)).str("sessionId")
    kit.advance(java.time.Duration.ofMinutes(3))
    val r = endMine(token, gs)
    assertEquals((r.status, r.str("status"), r.str("sessionId"), r.json.get("durationMinutes").asLong), (200, "completed", gs, 3L), r.text)
    assertEquals(endMine(token, gs).status, 200)
    kit.eventually("ended")(kit.facts(CasinoSessions).collectFirst { case f: GameSessionEnded if f.sessionId == gs => f })
    Thread.sleep(300)
    assertEquals(kit.facts(CasinoSessions).count { case f: GameSessionEnded => f.sessionId == gs; case _ => false }, 1)
    assertEquals(endMine(token, theirs).str("error"), "player_id_mismatch")
    assertEquals(endMine(token, "no-such").status, 404)
    assertEquals(kit.request("GET", kit.sessionPath(s"/$gs"), None, kit.asToken(token)).str("status"), "completed")
    assertEquals(kit.request("GET", kit.sessionPath(s"/$theirs"), None, kit.asToken(token)).str("error"), "session_not_found")
    val mine = kit.eventually("listed")(Option(kit.request("GET", kit.sessionPath(s"/players/$id"), None, kit.asToken(token)).json.get("sessions"))
      .filter(_.size == 1))
    assertEquals(mine.get(0).get("sessionId").asText, gs)
    assertEquals(kit.request("GET", kit.sessionPath(s"/players/$other"), None, kit.asToken(token)).str("error"), "player_id_mismatch")
  }

  test("an operator's force-end: forced and an audit record naming the operator; force-end-all ends the rest") {
    val (id, _, ps) = signedIn()
    val a = launch(id, Some(ps)).str("sessionId")
    val b = launch(id, Some(ps)).str("sessionId")
    val c = launch(id, Some(ps)).str("sessionId")
    val r = kit.request("POST", kit.operatorPath(s"/players/$id/sessions/$a/force-end"), Some("{}"), kit.asOperator(subject = "op-7"))
    assertEquals((r.status, r.str("status")), (200, "ended"), r.text)
    assertEquals(game(a).endReason, Some("forced"))
    val audit = kit.eventually("audit")(kit.facts(PlatformAudit).collectFirst { case f: AuditRecord if f.action == "force-end" && f.subjectId == id => f })
    assertEquals((audit.actorId, audit.changes.head.before), ("op-7", Some(a)))
    assertEquals(kit.request("POST", kit.operatorPath(s"/players/other/sessions/$b/force-end"), Some("{}"), kit.asOperator()).status, 404)
    val all = kit.request("POST", kit.operatorPath(s"/players/$id/sessions/force-end-all"), Some("{}"), kit.asOperator())
    assertEquals(all.json.get("ended").asInt, 2)
    assertEquals(Vector(b, c).map(game(_).endReason), Vector(Some("forced"), Some("forced")))
    assertEquals(player(id).todaysGameSessions.count(_.endedAt.isEmpty), 0)
  }

  test("the service routes: status for game-provider; history and a forced end for support-agent") {
    val (id, _, ps) = signedIn()
    val s = kit.request("GET", kit.servicePath(s"/$id/status"), None, kit.asService("game-provider"))
    assertEquals((s.status, s.str("status"), s.str("verification")), (200, "active", "unverified"))
    val gs = launch(id, Some(ps)).str("sessionId")
    kit.eventually("history")(Option(kit.request("GET", kit.servicePath(s"/$id/game-sessions"), None, kit.asService("support-agent")).json.get("sessions"))
      .filter(_.size == 1))
    val end = kit.json("POST", kit.servicePath(s"/game-sessions/$gs/end"), Json.obj("approvalId" -> "ap-1"), kit.asService("support-agent"))
    assertEquals(end.status, 200, end.text)
    assertEquals(game(gs).endReason, Some("forced"))
    kit.eventually("audit")(kit.facts(PlatformAudit).collectFirst { case f: AuditRecord if f.actorId == "ap-1" => f })
  }

  test("sign-out ends the open game sessions with reason logout") {
    val (id, token, ps) = signedIn()
    val gs = launch(id, Some(ps)).str("sessionId")
    assertEquals(kit.request("POST", kit.playerPath(s"/$id/logout"), Some("{}"), kit.asToken(token)).status, 200)
    assertEquals(game(gs).endReason, Some("logout"))
    kit.eventually("ended")(kit.facts(CasinoSessions).collectFirst { case f: GameSessionEnded if f.sessionId == gs => f })
    kit.eventually("logged out")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerLoggedOut if f.sessionId == ps => f })
  }
