package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.config.{BrandConfig, BrandConfigEntity}
import io.eitheror.casino.players.it.Reply
import io.eitheror.casino.players.player.{Actor, Close, PlayerEntity, PlayerState, SessionId, SetSelfExcluded, Suspend}
import io.eitheror.casino.players.sessions.*

import java.util.UUID

/**
 * features/players/sessions.feature: US2's sign-in scenarios (T039) and US3's game session scenarios
 * (T045). A `blocked` player is skipped: only the migration (spec 019) sets that status.
 */
class SessionsFeatures extends PlayersFeatures("sessions.feature"):
  protected def pending = Map.empty

  private var email    = ""
  private var playerId = ""
  private var token    = ""
  private var refresh  = ""
  private var reply: Reply = null
  private var sessions = Vector.empty[String]
  private var games    = Vector.empty[String]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    email = ""; playerId = ""; token = ""; refresh = ""; reply = null; sessions = Vector.empty; games = Vector.empty

  private def player: PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()
  private def session(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(PlayerSessionEntity.get).invoke()
  private def game(id: String)    = kit.components.forEventSourcedEntity(EntityId(id)).call(GameSessionEntity.get).invoke()
  private def config              = kit.components.forKeyValueEntity(EntityId(kit.slug))

  private def active(): Unit =
    val (e, id) = kit.activePlayer("bonusCode" -> "B7", "country" -> "MT")
    email = e; playerId = id

  private def signIn(): Unit =
    reply = kit.json("POST", kit.playerPath("/login"), Json.obj("email" -> email, "password" -> "Passw0rd!"),
      kit.asVisitor ++ Seq("X-Forwarded-For" -> "203.0.113.8", "User-Agent" -> "Feature/1"))
    if reply.status == 200 then
      token = reply.str("token"); refresh = reply.str("refreshToken")
      sessions = sessions :+ kit.sessionOf(token)

  private def gameUnder(sessionId: String): String =
    val g = s"gs-${UUID.randomUUID().toString.take(8)}"
    kit.components.forEventSourcedEntity(EntityId(sessionId)).call(PlayerSessionEntity.addGameSession).invoke(AddGameSession(g)): Unit
    kit.components.forEventSourcedEntity(EntityId(g)).call(GameSessionEntity.open).invoke(OpenGameSession(playerId, sessionId, "g-1", "EUR", false, kit.now())): Unit
    kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.gameSessionOpened).invoke(SessionId(g, kit.now())): Unit
    g

  private def restrict(status: String): Unit = status match
    case "suspended" =>
      kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.suspend).invoke(Suspend("feature", Some("op-1"), kit.now())): Unit
    case "self-excluded" =>
      kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.setSelfExcluded).invoke(SetSelfExcluded(None, kit.now())): Unit
    case "closed" =>
      kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.close).invoke(Close(Actor.Operator, Some("op-1"), "feature", kit.now())): Unit
    case other =>
      assume(false, s"$other is set only by the migration (spec 019): no command of this service sets it")

  Given("a player of brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  // ── sign-in ────────────────────────────────────────────────────────────────────────────────────

  Given("the player is active with a verified email address") { () => active() }
  When("they sign in with the right password") { () => signIn() }
  When("they sign in") { () => signIn() }
  Then("a player session opens recording the realm's session id, the network address and the user agent") { () =>
    val s = session(sessions.last)
    assertEquals((s.realmSessionId, s.networkAddress.reveal, s.userAgent.reveal), (kit.realmSessionOf(token), "203.0.113.8", "Feature/1"))
  }
  Then("the answer carries the sign-in, the player id, the currency, the country and the pending bonus code") { () =>
    assertEquals((reply.status, reply.str("playerId"), reply.str("currency"), reply.str("country"), reply.str("pendingBonusCode")),
      (200, playerId, "EUR", "MT", "B7"))
    assert(token.nonEmpty)
  }
  Then("a {string} fact is produced with the session id") { (name: String) =>
    assertEquals(name, "player_logged_in")
    kit.eventually(name)(kit.facts(CasinoPlayers).collectFirst { case f: PlayerLoggedIn if f.sessionId == sessions.last => f })
  }

  Given("the player's email address is not verified") { () =>
    email = s"unverified-${UUID.randomUUID().toString.take(8)}@example.test"
    playerId = kit.register("email" -> email).str("playerId")
  }
  Then("they are refused as {string}") { (code: String) => assertEquals(reply.str("error"), code, reply.text) }
  Then("no player session opens") { () =>
    assertEquals(player.openPlayerSessions, Set.empty[String])
    assertEquals(sessions, Vector.empty)
  }

  Given("the player is suspended") { () => active(); restrict("suspended") }
  Given("the player is self-excluded") { () => active(); restrict("self-excluded") }
  Given("the player is blocked") { () => active(); restrict("blocked") }
  Given("the player is closed") { () => active(); restrict("closed") }
  Then("they are refused as {string} naming the status") { (code: String) =>
    assertEquals((reply.status, reply.str("error")), (403, code), reply.text)
    assertEquals(reply.str("reason"), player.status)
  }
  Then("the refused attempt's time is kept on the player") { () => assert(player.lastRefusedSignInAt.isDefined) }

  When("the realm asks whether the player may sign in") { () =>
    kit.realm.clearReceived()
    reply = kit.json("POST", kit.realmPath("/may-sign-in"), Json.obj("email" -> email), kit.asVisitor :+ ("x-realm-secret" -> "kit-realm-secret"))
  }
  Then("the brand answers that they may not, naming the status") { () =>
    assertEquals((reply.json.get("allowed").asBoolean, reply.str("reason")), (false, player.status))
  }
  Then("the realm refuses before issuing any sign-in") { () => assertEquals(kit.realm.receivedOf("grant"), Vector.empty) }

  // ── sign-out and expiry ────────────────────────────────────────────────────────────────────────

  Given("the player is signed in with two open game sessions") { () =>
    active(); signIn()
    games = Vector(gameUnder(sessions.last), gameUnder(sessions.last))
  }
  When("they sign out") { () => reply = kit.request("POST", kit.playerPath(s"/$playerId/logout"), Some("{}"), kit.asToken(token)) }
  Then("the player session ends with reason {string}") { (reason: String) =>
    kit.eventually(s"ended $reason")(Option.when(session(sessions.last).status == EndReason.status(reason))(()))
  }
  Then("every game session under it ends with the same reason") { () =>
    games.foreach(g => assertEquals((game(g).status, game(g).endReason), (GameSessionState.Ended, Some(EndReason.Logout))))
  }
  Then("the realm's session is revoked") { () => assert(!kit.realm.sessions.containsKey(kit.realmSessionOf(token))) }
  Then("a {string} fact and then each {string} fact are produced") { (out: String, ended: String) =>
    assertEquals((out, ended), ("player_logged_out", "game_session_ended"))
    kit.eventually(out)(kit.facts(CasinoPlayers).collectFirst { case f: PlayerLoggedOut if f.sessionId == sessions.last => f })
    games.foreach(g => kit.eventually(ended)(kit.facts(CasinoSessions).collectFirst { case f: GameSessionEnded if f.sessionId == g => f }))
  }

  Given("a player session whose realm session has gone idle") { () =>
    active(); signIn()
    kit.realm.sessions.remove(kit.realmSessionOf(token))
  }
  When("the brand next asks the realm for its live sessions") { () =>
    Thread.sleep(2 * kit.settings.realmReconcileInterval.toMillis)
  }
  Then("the facts are produced as for a sign-out") { () =>
    val ended = kit.eventually("player_session_ended")(kit.facts(CasinoSessions).collectFirst { case f: PlayerSessionEnded if f.sessionId == sessions.last => f })
    val out   = kit.eventually("player_logged_out")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerLoggedOut if f.sessionId == sessions.last => f })
    assertEquals((ended.closeReason, out.closeReason), ("expired", "expired"))
  }

  // ── refresh ────────────────────────────────────────────────────────────────────────────────────

  Given("a sign-in that can be refreshed") { () => active(); signIn() }
  When("its refresh is presented") { () => reply = kit.json("POST", kit.playerPath("/refresh"), Json.obj("refreshToken" -> refresh), kit.asVisitor) }
  When("it is presented") { () => reply = kit.json("POST", kit.playerPath("/refresh"), Json.obj("refreshToken" -> refresh), kit.asVisitor) }
  Then("a new sign-in is issued") { () =>
    assertEquals(reply.status, 200, reply.text)
    assertNotEquals(reply.str("refreshToken"), refresh)
  }
  Given("a refresh of the sign-in that is expired") { () => active(); signIn(); refresh = s"expired-${UUID.randomUUID()}" }
  Given("a refresh of the sign-in that is reused") { () =>
    active(); signIn()
    assertEquals(kit.json("POST", kit.playerPath("/refresh"), Json.obj("refreshToken" -> refresh), kit.asVisitor).status, 200)
  }
  Given("a refresh of the sign-in that is revoked") { () =>
    active(); signIn()
    assertEquals(kit.request("POST", kit.playerPath(s"/$playerId/logout"), Some("{}"), kit.asToken(token)).status, 200)
  }
  Then("it is answered {string}") { (code: String) => assertEquals((reply.status, reply.str("error")), (401, code), reply.text) }

  Given("the player is signed in") { () => active(); signIn() }
  When("they make a request about another player's id") { () =>
    reply = kit.request("POST", kit.playerPath(s"/${UUID.randomUUID()}/logout"), Some("{}"), kit.asToken(token))
  }
  Then("it is refused as {string}") { (code: String) => assertEquals((reply.status, reply.str("error")), (403, code)) }

  // ── one session at a time ──────────────────────────────────────────────────────────────────────

  Given("a brand whose configuration sets one session at a time and a player signed in on one device") { () =>
    config.call(BrandConfigEntity.set).invoke(BrandConfig(singleSession = true)): Unit
    active(); signIn()
    games = Vector(gameUnder(sessions.last))
  }
  Given("a brand with the default configuration and a player signed in on one device") { () =>
    config.call(BrandConfigEntity.set).invoke(BrandConfig()): Unit
    active(); signIn()
  }
  When("the player signs in on a second device") { () =>
    try signIn()
    finally config.call(BrandConfigEntity.set).invoke(BrandConfig()): Unit
  }
  Then("the first player session ends with reason {string} and its game sessions with it") { (reason: String) =>
    assertEquals(session(sessions.head).status, EndReason.status(reason))
    games.foreach(g => assertEquals(game(g).endReason, Some(reason)))
  }
  Then("the second is the player's open session") { () => assertEquals(player.openPlayerSessions, Set(sessions(1))) }
  Then("both player sessions are open") { () =>
    assertEquals(sessions.map(session(_).status), Vector(SessionStatus.Active, SessionStatus.Active))
  }

  // ── game sessions (US3) ────────────────────────────────────────────────────────────────────────

  private var gameId = ""
  private var other  = ""

  private def launch(forPlayer: String = playerId, under: Option[String] = sessions.lastOption): Reply =
    kit.json("POST", kit.servicePath(s"/$forPlayer/game-sessions"),
      Json.obj("gameId" -> "g-9", "currency" -> "EUR", "playerSessionId" -> under), kit.asService("game-provider"))

  private def opened(): String =
    reply = launch()
    assertEquals(reply.status, 201, reply.text)
    gameId = reply.str("sessionId")
    gameId

  private def limitOf(minutes: Long): Unit =
    kit.publish(CasinoPlayers, playerId, RgLimitApplied(playerId, "SESSION_MINUTES", None, Some(minutes), "player", kit.now()))
    kit.eventually("the limit")(Option.when(player.sessionLimitMinutes.contains(minutes.toInt))(()))

  private def endMine(id: String): Reply = kit.request("PATCH", kit.sessionPath(s"/$id/end"), Some("{}"), kit.asToken(token))

  private def gameEnded(id: String) =
    kit.facts(CasinoSessions).collect { case f: GameSessionEnded if f.sessionId == id => f }

  Given("the player is active and verified with a player session") { () => active(); signIn() }
  When("the game provider asks to open a game session for a game and the player's currency") { () => opened(): Unit }
  Then("a game session opens under the player session recording the game, the start time and the currency") { () =>
    val g = game(gameId)
    assertEquals((g.playerSessionId, g.gameId, g.currency, g.startedAt.isDefined), (sessions.last, "g-9", "EUR", true))
    assert(session(sessions.last).gameSessions.contains(gameId))
  }
  Then("a {string} fact is produced") { (name: String) =>
    name match
      case "game_session_started" =>
        kit.eventually(name)(kit.facts(CasinoSessions).collectFirst { case f: GameSessionStarted if f.sessionId == gameId => f })
      case "game_session_ended" => kit.eventually(name)(gameEnded(gameId).headOption)
      case other                => fail(s"no binding for the fact $other")
  }
  Then("the id is returned") { () => assertEquals((reply.status, reply.str("sessionId")), (201, gameId)) }

  When("the game provider asks to open a game session") { () => reply = launch(under = None) }
  Then("it is refused naming the status") { () =>
    assertEquals((reply.status, reply.str("error"), reply.str("reason")), (403, "account_restricted", player.status), reply.text)
  }

  Given("the player has a session limit of {string} minutes and {string} already used today") { (limit: String, used: String) =>
    active(); signIn()
    val first = opened()
    kit.advance(java.time.Duration.ofMinutes(used.toLong))
    assertEquals(endMine(first).status, 200)
    limitOf(limit.toLong)
  }
  When("a game session opens") { () => opened(): Unit }
  Then("its timer is set for {string} minutes") { (minutes: String) =>
    val due = kit.timerDueIn(SessionLimits.name(gameId)).getOrElse(fail("no timer"))
    assert(due > minutes.toLong * 60 - 10 && due <= minutes.toLong * 60, s"due in $due s")
  }

  Given("an open game session whose timer was set for {string} minutes") { (minutes: String) =>
    active(); signIn(); limitOf(minutes.toLong); opened()
    games = Vector(minutes)
  }
  When("the timer fires") { () =>
    kit.advance(java.time.Duration.ofMinutes(games.head.toLong))
    kit.fireTimer(SessionLimits.name(gameId))
  }
  Then("the game session ends with reason {string}") { (reason: String) =>
    kit.eventually(s"ended $reason", scala.concurrent.duration.Duration(5, "s"))(Option.when(game(gameId).endReason.contains(reason))(()))
  }
  Then("the {string} fact says so") { (name: String) =>
    assertEquals(name, "game_session_ended")
    assertEquals(kit.eventually(name)(gameEnded(gameId).headOption).closeReason, game(gameId).endReason)
  }

  Given("an open game session") { () => active(); signIn(); opened(): Unit }
  When("the player ends it") { () =>
    kit.advance(java.time.Duration.ofMinutes(2))
    reply = endMine(gameId)
  }
  Then("it records the end time and the duration in whole minutes") { () =>
    val g = game(gameId)
    assertEquals((reply.status, g.endedAt.isDefined, g.durationMinutes, reply.json.get("durationMinutes").asLong), (200, true, Some(2L), 2L))
  }
  Then("ending it again changes nothing") { () =>
    val before = game(gameId)
    assertEquals(endMine(gameId).status, 200)
    Thread.sleep(300)
    assertEquals((game(gameId), gameEnded(gameId).size), (before, 1))
  }

  When("an operator of the brand ends it by force") { () =>
    reply = kit.request("POST", kit.operatorPath(s"/players/$playerId/sessions/$gameId/force-end"), Some("{}"), kit.asOperator(subject = "op-9"))
  }
  Then("it ends with reason {string}") { (reason: String) => assertEquals(game(gameId).endReason, Some(reason)) }
  Then("the audit record names the operator") { () =>
    kit.eventually("the audit record")(kit.facts(PlatformAudit).collectFirst {
      case f: AuditRecord if f.action == "force-end" && f.subjectId == playerId && f.actorId == "op-9" => f
    })
  }

  Given("the player has game sessions and another player has one") { () =>
    val (e, id) = kit.activePlayer()
    val t       = kit.signIn(e).str("token")
    other = launch(id, Some(kit.sessionOf(t))).str("sessionId")
    active(); signIn()
    games = Vector(opened(), opened())
  }
  When("the player reads their sessions") { () =>
    reply = kit.eventually("both listed")(Option(kit.request("GET", kit.sessionPath(s"/players/$playerId"), None, kit.asToken(token)))
      .filter(_.json.get("sessions").size == 2))
  }
  Then("they see their own sessions and nobody else's") { () =>
    val ids = (0 until reply.json.get("sessions").size).map(reply.json.get("sessions").get(_).get("sessionId").asText).toSet
    assertEquals(ids, games.toSet)
  }
  Then("the other player's session is answered {string}") { (code: String) =>
    assertEquals(kit.request("GET", kit.sessionPath(s"/$other"), None, kit.asToken(token)).str("error"), code)
  }
