package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.inbox.{NoteEntity, NotesView}
import io.eitheror.casino.players.it.Reply
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, Status}
import io.eitheror.casino.players.sessions.{GameSessionEntity, PlayerSessionEntity, SessionStatus}

import scala.concurrent.duration.*

/** features/players/status.feature (T057). */
class StatusFeatures extends PlayersFeatures("status.feature"):
  protected def pending = Map.empty

  private var email    = ""
  private var playerId = ""
  private var token    = ""
  private var session  = ""
  private var game     = ""
  private var reply: Reply = null
  private var replies  = Vector.empty[Reply]
  private var noteId   = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    email = ""; playerId = ""; token = ""; session = ""; game = ""; reply = null; replies = Vector.empty; noteId = ""

  private def player: PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()

  private def op(method: String, path: String, fields: (String, Any)*): Reply =
    kit.request(method, kit.operatorPath(path), Some(Json.render(Json.obj(fields*))), kit.asOperator(subject = "op-3"))

  /** Active, signed in and in a game, so a cascade has something to end. */
  private def active(): Unit =
    val (e, id) = kit.activePlayer()
    email = e; playerId = id; token = kit.signIn(e).str("token"); session = kit.sessionOf(token)
    game = kit.json("POST", kit.servicePath(s"/$id/game-sessions"), Json.obj("gameId" -> "g-1", "currency" -> "EUR",
      "playerSessionId" -> session), kit.asService("game-provider")).str("sessionId")

  private def gameReason: Option[String] =
    kit.components.forEventSourcedEntity(EntityId(game)).call(GameSessionEntity.get).invoke().endReason
  private def sessionEnded: Boolean =
    kit.components.forEventSourcedEntity(EntityId(session)).call(PlayerSessionEntity.get).invoke().status != SessionStatus.Active

  private def theirs(f: CasinoPlayersFact): Option[(String, String, Option[String], Option[String])] = f match
    case f: PlayerSuspended    if f.playerId == playerId => Some(("player_suspended", f.actor, f.actorId, f.reason))
    case f: PlayerReinstated   if f.playerId == playerId => Some(("player_reinstated", f.actor, f.actorId, f.reason))
    case f: PlayerSelfExcluded if f.playerId == playerId => Some(("player_self_excluded", f.actor, f.actorId, f.reason))
    case f: PlayerClosed       if f.playerId == playerId => Some(("player_closed", f.actor, f.actorId, f.reason))
    case f: PlayerReopened     if f.playerId == playerId => Some(("player_reopened", f.actor, f.actorId, f.reason))
    case _                                               => None

  private def fact(name: String) =
    kit.eventually(name)(kit.facts(CasinoPlayers).flatMap(theirs).find(_._1 == name))

  Given("a player of brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  Given("the player is active and signed in") { () => active() }
  Given("the player is active") { () => active() }
  Given("the player is suspended") { () => active(); assertEquals(op("PATCH", s"/players/$playerId/suspend", "reason" -> "kit").status, 200) }
  Given("the player is at no tier") { () => active(); assertEquals(player.vipTier, None) }
  Given("the player is closed") { () =>
    active(); assertEquals(op("POST", s"/players/$playerId/close", "reason" -> "kit").status, 200); kit.wallet.clearReceived()
  }
  Given("the player closed their own account and the retention hold has not passed") { () =>
    active()
    val r = kit.request("POST", kit.playerPath(s"/$playerId/close"), Some("""{"password":"Passw0rd!"}"""), kit.asToken(token))
    assertEquals(r.status, 200, r.text)
  }

  When("an operator suspends them with a reason") { () => reply = op("PATCH", s"/players/$playerId/suspend", "reason" -> "chargeback") }
  When("an operator reinstates them") { () => reply = op("PATCH", s"/players/$playerId/reinstate") }
  When("an operator excludes them through responsible gambling, which tells the brand's player records to set the status") { () =>
    reply = kit.json("PUT", kit.servicePath(s"/$playerId/exclusion"), Json.obj("at" -> kit.now().toString), kit.asService("responsible-gambling"))
  }
  When("an operator excludes them on the player records directly") { () =>
    replies = Vector(op("PUT", s"/players/$playerId/exclusion"), op("PATCH", s"/players/$playerId/exclusion"),
      kit.request("PUT", kit.servicePath(s"/$playerId/exclusion"), Some("{}"), kit.asOperator()))
  }
  When("an operator sets tier {string}") { (tier: String) => reply = op("PUT", s"/players/$playerId/vip-tier", "tier" -> tier) }
  When("an operator closes the account") { () => reply = op("POST", s"/players/$playerId/close", "reason" -> "operator decision") }
  When("the player closes their account confirming with their password") { () =>
    kit.rg.clearReceived()
    reply = kit.request("POST", kit.playerPath(s"/$playerId/close"), Some("""{"password":"Passw0rd!"}"""), kit.asToken(token))
  }
  When("an operator reopens the account on the player's request with a reason") { () =>
    reply = op("POST", s"/players/$playerId/reopen", "reason" -> "player asked")
  }
  When("they sign in") { () => reply = kit.signIn(email) }

  Then("the player status is {string}") { (status: String) =>
    assertEquals(reply.status, 200, reply.text)
    assertEquals(player.status, status)
  }
  Then("every session ends with reason {string}") { (reason: String) =>
    kit.eventually("the sessions ended", 5.seconds)(Option.when(sessionEnded && gameReason.nonEmpty)(()))
    assertEquals(gameReason, Some(reason))
  }
  Then("every session ends") { () => kit.eventually("the sessions ended", 5.seconds)(Option.when(sessionEnded && gameReason.nonEmpty)(())) }
  Then("the realm's sessions are revoked") { () =>
    assert(kit.realm.receivedOf("revoke").nonEmpty)
    assert(!kit.realm.sessions.values.toArray.exists(_.toString.contains(kit.realmSessionOf(token))))
  }
  Then("the {string} fact carries the previous and new status, the actor and the reason") { (name: String) =>
    assertEquals(name, "player_suspended")
    val f = kit.eventually(name)(kit.facts(CasinoPlayers).collectFirst { case f: PlayerSuspended if f.playerId == playerId => f })
    assertEquals((f.previousValue, f.newValue, f.actor, f.actorId, f.reason), ("active", "suspended", "operator", Some("op-3"), Some("chargeback")))
  }
  Then("a {string} fact is produced") { (name: String) => fact(name): Unit }
  Then("the exclusion time is kept under the name every reader uses") { () =>
    assert(player.exclusionAt.isDefined)
    val f = kit.eventually("player_self_excluded")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerSelfExcluded if f.playerId == playerId => f })
    assertEquals(Some(f.excludedAt), player.exclusionAt)
  }
  Then("it is refused") { () =>
    assertEquals(replies.map(_.status), Vector(404, 404, 403))
    assertEquals(player.status, Status.Active)
  }
  Then("the {string} fact carries an empty previous tier and {string}") { (name: String, tier: String) =>
    assertEquals(name, "vip_tier_changed")
    val f = kit.eventually(name)(kit.facts(CasinoPlayers).collectFirst { case f: VipTierChanged if f.playerId == playerId => f })
    assertEquals((f.previousTier.getOrElse(""), f.newTier), ("", tier))
  }
  Then("the audit record names the operator") { () =>
    kit.eventually("the audit")(kit.facts(PlatformAudit).collectFirst {
      case a: AuditRecord if a.subjectId == playerId && a.actorId == "op-3" && a.actorKind == "operator" => a
    })
  }
  Then("the closure time is kept") { () => assert(player.closedAt.isDefined) }
  Then("the realm user is disabled") { () => assertEquals(kit.realm.userByEmail(email).map(_.enabled), Some(false)) }
  Then("the realm user is enabled") { () => assertEquals(kit.realm.userByEmail(email).map(_.enabled), Some(true)) }
  Then("a {string} fact is produced with the actor and the reason") { (name: String) =>
    val (_, actor, actorId, reason) = fact(name)
    assertEquals((actor, actorId), ("operator", Some("op-3")))
    assert(reason.exists(_.nonEmpty))
  }
  // attribution (spec 012) reads player_closed from casino.players; its own fact is its spec's to test
  Then("the brand's attribution emits its closure fact from it") { () =>
    assert(kit.factTexts(CasinoPlayers).exists(t => t.contains(playerId) && t.contains("closed")))
  }
  Then("a {string} fact is produced with actor {string} and reason {string}") { (name: String, actor: String, reason: String) =>
    val (_, a, _, r) = fact(name)
    assertEquals((a, r), (actor, Some(reason)))
  }
  Then("no responsible-gambling flag is set") { () =>
    assertEquals(kit.rg.received, Vector.empty)
    assertEquals((player.status, player.exclusionAt), (Status.Closed, None))
  }
  Then("the same retention hold starts as for an operator's closure") { () =>
    assert(player.closedAt.isDefined)
    assertEquals(op("POST", s"/players/$playerId/reopen", "reason" -> "within the hold").status, 200)
  }
  Then("they are refused as {string}") { (code: String) =>
    assertEquals((reply.status, reply.str("error"), reply.str("reason")), (403, code, "closed"))
  }
  Then("their wallet balances are untouched by the closure") { () => assertEquals(kit.wallet.received, Vector.empty) }

  Given("an operator without the brand's role") { () => active() }
  When("they act on the player") { () =>
    replies = Vector(
      kit.request("PATCH", kit.operatorPath(s"/players/$playerId/suspend"), Some("{}"), kit.asOperator(roles = "brand_other")),
      kit.request("POST", kit.operatorPath(s"/players/$playerId/close"), Some("{}"), kit.asOperator(roles = "brand_other,support_agent")),
      kit.request("PATCH", kit.operatorPath(s"/players/$playerId/suspend"), Some("{}"), kit.asToken(token)))
  }
  Then("they are refused, however they signed in") { () =>
    assertEquals(replies.map(_.status), Vector(403, 403, 403))
    assertEquals(player.status, Status.Active)
  }

  Given("an operator of the brand") { () => active() }
  When("they write a note about the player and pin it") { () =>
    op("POST", s"/players/$playerId/notes", "content" -> "an older note"): Unit
    noteId = op("POST", s"/players/$playerId/notes", "content" -> "note-text-913").str("noteId")
    assertEquals(op("PATCH", s"/players/$playerId/notes/$noteId/pin").status, 200)
  }
  Then("the note is personal data under the player") { () =>
    val n = kit.components.forKeyValueEntity(EntityId(noteId)).call(NoteEntity.get).invoke()
    assertEquals((n.playerId, n.text.reveal), (playerId, "note-text-913"))
    assert(!kit.factTexts(PlatformAudit).exists(_.contains("note-text-913")))
  }
  Then("it is pinned first in the operator's view") { () =>
    assertEquals(kit.eventually("the notes")(NotesView.ofPlayer(kit.views, playerId).headOption.filter(_.pinned)).noteId, noteId)
    val pinned = op("GET", s"/players/$playerId/profile").json.get("pinnedNotes")
    assertEquals(pinned.get(0).get("noteId").asText, noteId)
  }
  Then("it is invisible to the player") { () =>
    val own = kit.request("GET", kit.playerPath(s"/$playerId"), None, kit.asToken(token))
    assert(own.status == 200 && !own.text.contains("note-text-913"), own.text)
  }
