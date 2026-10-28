package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, Status}
import io.eitheror.casino.players.sessions.{GameSessionEntity, PlayerSessionEntity, SessionStatus}

import java.time.Duration
import scala.concurrent.duration.*

/** T056: US5's operator, `kyc` and `responsible-gambling` routes (FR-031 to FR-034, FR-041, SC-002). */
class OperatorSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player(id: String): PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def op(method: String, path: String, fields: (String, Any)*): Reply =
    kit.request(method, kit.operatorPath(path), Some(Json.render(Json.obj(fields*))), kit.asOperator(subject = "op-7"))
  private def svc(method: String, path: String, caller: String, fields: (String, Any)*): Reply =
    kit.request(method, kit.servicePath(path), Some(Json.render(Json.obj(fields*))), kit.asService(caller))

  /** An active player signed in, holding two game sessions under that sign-in. */
  private def playing(): (String, String, String, Vector[String]) =
    val (email, id) = kit.activePlayer()
    val token = kit.signIn(email).str("token")
    val sid   = kit.sessionOf(token)
    val games = Vector.fill(2)(svc("POST", s"/$id/game-sessions", "game-provider", "gameId" -> "g-7", "currency" -> "EUR",
      "playerSessionId" -> sid).str("sessionId"))
    assert(games.forall(_.nonEmpty))
    (email, id, sid, games)

  private def ended(sessionId: String): Boolean =
    kit.components.forEventSourcedEntity(EntityId(sessionId)).call(PlayerSessionEntity.get).invoke().status != SessionStatus.Active

  private def gameReason(sessionId: String): Option[String] =
    kit.components.forEventSourcedEntity(EntityId(sessionId)).call(GameSessionEntity.get).invoke().endReason

  private def fact[F](name: String)(pf: PartialFunction[CasinoPlayersFact, F]): F =
    kit.eventually(name)(kit.facts(CasinoPlayers).collectFirst(pf))

  test("suspend: every session ended suspended within 5 s, the realm revoked, the fact with both statuses, the actor and the reason") {
    val (email, id, sid, games) = playing()
    val r = op("PATCH", s"/players/$id/suspend", "reason" -> "chargeback")
    assertEquals(r.status, 200, r.text)
    assertEquals(player(id).status, Status.Suspended)
    kit.eventually("the sessions ended", 5.seconds)(Option.when(ended(sid) && games.forall(g => gameReason(g).nonEmpty))(()))
    assertEquals(games.map(gameReason).distinct, Vector(Some("suspended")))
    assert(kit.realm.receivedOf("revoke").nonEmpty)
    val f = fact("player_suspended") { case f: PlayerSuspended if f.playerId == id => f }
    assertEquals((f.previousValue, f.newValue, f.actor, f.actorId, f.reason), ("active", "suspended", "operator", Some("op-7"), Some("chargeback")))
    val again = kit.signIn(email)
    assertEquals((again.status, again.str("error")), (403, "account_restricted"))
    assertEquals(op("PATCH", s"/players/$id/suspend", "reason" -> "again").status, 200)
    assertEquals(player(id).suspension.map(_.reason), Some("again"))
    Thread.sleep(300)
    assertEquals(kit.facts(CasinoPlayers).count { case f: PlayerSuspended => f.playerId == id; case _ => false }, 1)
  }

  test("reinstate: active and player_reinstated; an active player is 409 not_suspended") {
    val (_, id) = kit.activePlayer()
    assertEquals(op("PATCH", s"/players/$id/suspend", "reason" -> "check").status, 200)
    assertEquals(op("PATCH", s"/players/$id/reinstate").status, 200)
    assertEquals(player(id).status, Status.Active)
    fact("player_reinstated") { case f: PlayerReinstated if f.playerId == id => f }
    val r = op("PATCH", s"/players/$id/reinstate")
    assertEquals((r.status, r.str("error")), (409, "not_suspended"))
  }

  test("exclusion: responsible-gambling only; the status, the time, the sessions ended suspended, the fact; lifted to active") {
    val (_, id, sid, games) = playing()
    assertEquals(kit.request("PUT", kit.servicePath(s"/$id/exclusion"), Some("{}"), kit.asOperator()).status, 403)
    assertEquals(svc("PUT", s"/$id/exclusion", "kyc").status, 403)
    assertEquals(op("PUT", s"/players/$id/exclusion").status, 404)
    val r = svc("PUT", s"/$id/exclusion", "responsible-gambling", "at" -> "2026-10-25T10:00:00Z")
    assertEquals(r.status, 200, r.text)
    assertEquals((player(id).status, player(id).exclusionAt.map(_.toString)), (Status.SelfExcluded, Some("2026-10-25T10:00:00Z")))
    kit.eventually("the sessions ended", 5.seconds)(Option.when(ended(sid) && games.forall(g => gameReason(g).nonEmpty))(()))
    assertEquals(games.map(gameReason).distinct, Vector(Some("suspended")))
    val f = fact("player_self_excluded") { case f: PlayerSelfExcluded if f.playerId == id => f }
    assertEquals(f.excludedAt.toString, "2026-10-25T10:00:00Z")
    assertEquals(kit.request("DELETE", kit.servicePath(s"/$id/exclusion"), None, kit.asService("responsible-gambling")).status, 200)
    assertEquals(player(id).status, Status.Active)
  }

  test("kyc's suspension: 200, a repeat 200 and one fact; another service is 403") {
    val (_, id) = kit.activePlayer()
    val body = Seq[(String, Any)]("reason" -> "kyc_window_expired", "deadline" -> "2026-11-01T00:00:00Z")
    assertEquals(svc("POST", s"/$id/suspend", "wallet", body*).status, 403)
    assertEquals(svc("POST", s"/$id/suspend", "kyc", body*).status, 200)
    assertEquals(svc("POST", s"/$id/suspend", "kyc", body*).status, 200)
    val f = fact("player_suspended") { case f: PlayerSuspended if f.playerId == id => f }
    assertEquals(f.reason, Some("kyc_window_expired"))
    Thread.sleep(300)
    assertEquals(kit.facts(CasinoPlayers).count { case f: PlayerSuspended => f.playerId == id; case _ => false }, 1)
  }

  test("vip-tier from none: vip_tier_changed with an empty previous tier, an audit record naming the operator; pot-vip writes a note") {
    val (_, id) = kit.activePlayer()
    val r = op("PUT", s"/players/$id/vip-tier", "tier" -> "gold")
    assertEquals((r.status, r.str("vipTier")), (200, "gold"))
    val f = fact("vip_tier_changed") { case f: VipTierChanged if f.playerId == id => f }
    assertEquals((f.previousTier.getOrElse(""), f.newTier, f.actorId), ("", "gold", "op-7"))
    kit.eventually("the audit")(kit.facts(PlatformAudit).collectFirst { case a: AuditRecord if a.subjectId == id && a.actorId == "op-7" => a })
    assertEquals(op("PUT", s"/players/$id/pot-vip", "potVip" -> true).json.get("potVip").asBoolean, true)
    assert(player(id).potVip)
    kit.eventually("the pot-vip note")(io.eitheror.casino.players.inbox.NotesView.ofPlayer(kit.views, id).headOption)
  }

  test("close: closed, every session ended, the realm user disabled, player_closed; sign-in refused as closed; the wallet asked nothing") {
    val (email, id, sid, _) = playing()
    kit.wallet.clearReceived()
    val r = op("POST", s"/players/$id/close", "reason" -> "responsible request")
    assertEquals(r.status, 200, r.text)
    assertEquals(player(id).status, Status.Closed)
    assert(player(id).closedAt.isDefined)
    kit.eventually("the session ended", 5.seconds)(Option.when(ended(sid))(()))
    assertEquals(kit.realm.userByEmail(email).map(_.enabled), Some(false))
    val f = fact("player_closed") { case f: PlayerClosed if f.playerId == id => f }
    assertEquals((f.actor, f.actorId, f.reason), ("operator", Some("op-7"), Some("responsible request")))
    val s = kit.signIn(email)
    assertEquals((s.status, s.str("error"), s.str("reason")), (403, "account_restricted", "closed"))
    assertEquals(kit.wallet.received, Vector.empty)
    assertEquals(op("POST", s"/players/$id/close").str("error"), "already_closed")
  }

  test("the player's own close: a wrong password 400 invalid_password; the right one closed with actor player, reason player_request, no RG call") {
    val (email, id) = kit.activePlayer()
    val token = kit.signIn(email).str("token")
    kit.rg.clearReceived()
    def close(pw: String) = kit.request("POST", kit.playerPath(s"/$id/close"), Some(Json.render(Json.obj("password" -> pw))), kit.asToken(token))
    assertEquals(close("nope-nope").str("error"), "invalid_password")
    assertEquals(close("Passw0rd!").status, 200)
    assertEquals(player(id).status, Status.Closed)
    val f = fact("player_closed") { case f: PlayerClosed if f.playerId == id => f }
    assertEquals((f.actor, f.reason), ("player", Some("player_request")))
    assertEquals(kit.rg.received, Vector.empty)
  }

  test("reopen: within the hold active, the realm user enabled, player_reopened; an active player 409 not_closed; after the hold 409 hold_lapsed") {
    val (email, id) = kit.activePlayer()
    assertEquals(op("POST", s"/players/$id/reopen", "reason" -> "x").str("error"), "not_closed")
    assertEquals(op("POST", s"/players/$id/close").status, 200)
    assertEquals(op("POST", s"/players/$id/reopen").status, 400)
    val r = op("POST", s"/players/$id/reopen", "reason" -> "player asked")
    assertEquals(r.status, 200, r.text)
    assertEquals((player(id).status, kit.realm.userByEmail(email).map(_.enabled)), (Status.Active, Some(true)))
    val f = fact("player_reopened") { case f: PlayerReopened if f.playerId == id => f }
    assertEquals((f.actorId, f.reason), (Some("op-7"), Some("player asked")))
    assertEquals(op("POST", s"/players/$id/close").status, 200)
    kit.advance(Duration.ofDays(1826))
    assertEquals(op("POST", s"/players/$id/reopen", "reason" -> "late").str("error"), "hold_lapsed")
  }

  test("an operator without the brand's role is 403 on every operator route, however they signed in") {
    val (_, id) = kit.activePlayer()
    val routes = Vector("GET" -> "/players", "GET" -> s"/players/$id/profile", "GET" -> s"/players/$id/wallet",
      "GET" -> s"/players/$id/activity", "GET" -> s"/players/$id/bets", "GET" -> s"/players/$id/sessions", "GET" -> s"/players/$id/crm",
      "PATCH" -> s"/players/$id/suspend", "PATCH" -> s"/players/$id/reinstate", "POST" -> s"/players/$id/close",
      "POST" -> s"/players/$id/reopen", "PUT" -> s"/players/$id/vip-tier", "PUT" -> s"/players/$id/pot-vip",
      "POST" -> s"/players/$id/notes", "GET" -> s"/players/$id/messages", "POST" -> s"/players/$id/messages", "GET" -> "/merge-tags",
      "POST" -> "/messages/preview", "POST" -> s"/players/$id/clear-suspect-flag", "GET" -> "/brand-config", "PUT" -> "/brand-config",
      "GET" -> "/currency-config", "PUT" -> "/currency-config", "GET" -> "/allow-list", "PUT" -> "/allow-list")
    for (m, path) <- routes do
      val r = kit.request(m, kit.operatorPath(path), if m == "GET" then None else Some("{}"), kit.asOperator(roles = "brand_other"))
      assertEquals(r.status, 403, s"$m $path")
    // `platform_admin` is admitted on every brand (contract `Routes.operator`, spec 001; the original's requireBrandAccess)
    assertEquals(kit.request("GET", kit.operatorPath("/players"), None, kit.asOperator(roles = "platform_admin")).status, 200)
    assertEquals(kit.request("GET", kit.operatorPath("/players"), None, kit.asPlayer(id)).status, 403)
    assertEquals(kit.request("GET", s"/v1/casino/brands/${kit.slug}/modules/", None, kit.asOperator(roles = "brand_other")).status, 403)
    assertEquals(player(id).status, Status.Active)
  }

  test("notes: 201, pinned first in the operator's profile, never in the player's own read; the audit never carries the text") {
    val (email, id) = kit.activePlayer()
    val a = op("POST", s"/players/$id/notes", "content" -> "first unpinned")
    val b = op("POST", s"/players/$id/notes", "content" -> "Called about a secret-word-71")
    assertEquals((a.status, b.status), (201, 201))
    assertEquals(op("PATCH", s"/players/$id/notes/${b.str("noteId")}/pin").json.get("isPinned").asBoolean, true)
    assertEquals(op("PATCH", s"/players/$id/notes/nope/pin").status, 404)
    val pinned = kit.eventually("the pinned note")(Option(op("GET", s"/players/$id/profile").json.get("pinnedNotes")).filter(_.size == 1))
    assertEquals(pinned.get(0).get("noteId").asText, b.str("noteId"))
    val own = kit.request("GET", kit.playerPath(s"/$id"), None, kit.asToken(kit.signIn(email).str("token")))
    assert(own.status == 200 && !own.text.contains("secret-word-71"), own.text)
    Thread.sleep(500)
    assert(!kit.factTexts(PlatformAudit).exists(_.contains("secret-word-71")))
  }

  test("messages: merge tags rendered, 422 unknown_merge_tag with the tag, 422 category_required, 422 marketing_suppressed; the tags; a preview") {
    val (_, id) = kit.activePlayer("firstName" -> "Ada")
    val m = op("POST", s"/players/$id/messages", "category" -> "service", "subjectTemplate" -> "Hi {{first_name}}",
      "bodyTemplate" -> "Dear {{first_name}}, body-word-55.")
    assertEquals((m.status, m.str("subject"), m.str("body")), (201, "Hi Ada", "Dear Ada, body-word-55."))
    val unknown = op("POST", s"/players/$id/messages", "category" -> "service", "bodyTemplate" -> "{{nope}}")
    assertEquals((unknown.status, unknown.str("error"), unknown.str("tag")), (422, "unknown_merge_tag", "nope"))
    assertEquals(op("POST", s"/players/$id/messages", "bodyTemplate" -> "x").str("error"), "category_required")
    assertEquals(op("PATCH", s"/players/$id/crm", "messageSuppressed" -> true).status, 200)
    assertEquals(op("POST", s"/players/$id/messages", "category" -> "marketing", "bodyTemplate" -> "x").str("error"), "marketing_suppressed")
    assertEquals(op("POST", "/players/nobody/messages", "category" -> "service", "bodyTemplate" -> "x").status, 404)
    val list = kit.eventually("the message listed")(Option(op("GET", s"/players/$id/messages")).filter(_.json.get("total").asInt >= 1))
    assertEquals(list.json.get("items").get(0).get("origin").asText, "operator")
    assert(op("GET", "/merge-tags").json.get("tags").size > 10)
    val p = op("POST", "/messages/preview", "playerId" -> id, "subjectTemplate" -> "{{first_name}}", "bodyTemplate" -> "{{cash_balance}}")
    assertEquals((p.json.get("resolved").get("subject").asText, p.json.get("resolved").get("body").asText, p.json.get("hasAllValues").asBoolean),
      ("Ada", "€42.50", true))
    Thread.sleep(500)
    assert(!kit.factTexts(PlatformAudit).exists(_.contains("body-word-55")))
  }

  test("the configurations: set and read by an operator and by a service; an invalid field 400 invalid_config naming it") {
    val b = op("PUT", "/brand-config", "reportingZone" -> "Europe/London")
    assertEquals((b.status, b.str("reportingZone")), (200, "Europe/London"))
    assertEquals(kit.request("GET", kit.servicePath("/config/brand-config"), None, kit.asService("reporting")).str("reportingZone"), "Europe/London")
    val bad = op("PUT", "/brand-config", "reportingZone" -> "Mars/Olympus")
    assertEquals((bad.status, bad.str("error"), bad.str("field")), (400, "invalid_config", "reportingZone"))
    assertEquals(op("PUT", "/currency-config", "offered" -> Json.arr("EUR", "GBP"), "defaultCurrency" -> "EUR").status, 200)
    assertEquals(op("PUT", "/currency-config", "offered" -> Json.arr("XYZ1")).str("field"), "offered")
    assertEquals(op("GET", "/currency-config").json.get("offered").size, 2)
    assertEquals(op("PUT", "/allow-list", "countries" -> Json.arr("GB", "MT")).status, 200)
    assertEquals(op("PUT", "/allow-list", "countries" -> Json.arr("ZZ")).str("field"), "countries")
    assertEquals(kit.request("GET", kit.servicePath("/config/allow-list"), None, kit.asService("kyc")).json.get("countries").size, 2)
    op("PUT", "/allow-list", "countries" -> Json.arr()): Unit
    op("PUT", "/brand-config", "reportingZone" -> "UTC"): Unit
    op("PUT", "/currency-config", "offered" -> Json.arr()): Unit
  }

  test("modules: the agent's answer passed through; a request forwarded with the operator as by; a silent agent 503") {
    val path = s"/v1/casino/brands/${kit.slug}/modules"
    val m = kit.request("GET", s"$path/", None, kit.asOperator())
    assertEquals((m.status, m.json.get("modules").get(0).get("module").asText), (200, "casino"))
    val r = kit.request("POST", s"$path/requests", Some(Json.render(Json.obj("module" -> "pool", "note" -> "please"))), kit.asOperator(subject = "op-9"))
    assertEquals(r.status, 204, r.text)
    assertEquals(kit.agent.requests.map(_.textOr("by", "")), Vector("op-9"))
    assertEquals(kit.request("POST", s"$path/requests", Some("""{"module":"nope"}"""), kit.asOperator()).status, 400)
    kit.agent.silent = true
    try assertEquals(kit.request("GET", s"$path/", None, kit.asOperator()).status, 503)
    finally kit.agent.silent = false
  }

  test("the reads: the list finds the player, wallet, activity, bets with 400 invalid_date_range, sessions") {
    val (_, id, _, _) = playing()
    kit.eventually("the player listed")(Option(op("GET", s"/players?search=$id")).filter(_.json.get("total").asInt == 1))
    assertEquals(op("GET", s"/players/$id/wallet").status, 200)
    assertEquals(op("GET", s"/players/$id/activity").json.get("pageSize").asInt, 50)
    assertEquals(op("GET", s"/players/$id/bets?fromDate=2026-10-02&toDate=2026-10-01").str("error"), "invalid_date_range")
    assertEquals(op("GET", s"/players/$id/bets").status, 200)
    kit.eventually("the game sessions")(Option(op("GET", s"/players/$id/sessions").json.get("sessions")).filter(_.size == 2))
  }

  test("clear-suspect-flag: 200 naming the cases cleared") {
    val (_, id) = kit.activePlayer()
    val r = op("POST", s"/players/$id/clear-suspect-flag")
    assertEquals((r.status, r.json.get("flagCleared").asBoolean, r.json.get("linkedCasesFound").asInt), (200, true, 0))
  }
