package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.config.{BrandConfig, BrandConfigEntity}
import io.eitheror.casino.players.it.Reply
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, SessionId, SetSelfExcluded, Suspend}
import io.eitheror.casino.players.sessions.*

import java.util.UUID

/**
 * features/players/sessions.feature: US2's sign-in scenarios (T039). The game session scenarios are
 * US3's (T045) and stay pending until it binds them.
 */
class SessionsFeatures extends PlayersFeatures("sessions.feature"):
  protected def pending = Vector(
    "a game session opened at launch for an active verified player is recorded under the player session",
    "a game session asked for a restricted or closed player is refused naming the status",
    "a game session's timer is set from the minutes left of the session limit",
    "a game session whose timer fires ends with reason limit",
    "a player ending a game session records its end time and duration, and ending it again changes nothing",
    "an operator ending a game session by force ends it with reason forced and is named in the audit record",
    "a player reading their sessions sees their own and nobody else's"
  ).map(_ -> "T045 (pending)").toMap

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
