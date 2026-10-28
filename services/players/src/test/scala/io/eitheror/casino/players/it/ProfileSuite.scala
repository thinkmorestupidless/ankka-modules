package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.config.{AllowList, AllowListEntity}
import io.eitheror.casino.players.inbox.MessagesView
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.concurrent.duration.*

/** US4: the profile, the addresses, the password, consents, cookies, favourites, the histories and the inbox (FR-023 to FR-030). */
class ProfileSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player(id: String): PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def allow(countries: String*): Unit =
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(AllowListEntity.set).invoke(AllowList(countries.toSet)): Unit

  /** An active player, signed in: the address, the id and the token. */
  private def signedIn(fields: (String, Any)*): (String, String, String) =
    val (email, id) = kit.activePlayer(fields*)
    (email, id, kit.signIn(email).str("token"))

  private def as(token: String, method: String, path: String, body: Option[io.eitheror.casino.players.Json.type => com.fasterxml.jackson.databind.JsonNode] = None): Reply =
    kit.request(method, kit.playerPath(path), body.map(b => Json.render(b(Json))), kit.asToken(token))

  private def send(token: String, method: String, path: String, fields: (String, Any)*): Reply =
    kit.request(method, kit.playerPath(path), Some(Json.render(Json.obj(fields*))), kit.asToken(token))

  private def follow(link: String, fields: (String, Any)*): (String, Reply) =
    val token = java.net.URLDecoder.decode(link.split("token=", 2)(1), UTF_8)
    val path  = link.stripPrefix(kit.settings.playerSiteBase).takeWhile(_ != '?')
    path -> kit.json("POST", kit.playerPath(path), Json.obj((("token" -> token) +: fields)*), kit.asVisitor)

  private def enc(a: String) = URLEncoder.encode(a, UTF_8)

  test("the profile: read whole by its player, 403 to another; an edit applies, keeps the locked date, names what changed") {
    val (_, id, token) = signedIn("firstName" -> "Ana", "dateOfBirth" -> "1990-01-01", "country" -> "MT")
    val (_, _, other)  = signedIn()
    val r = as(token, "GET", s"/$id")
    assertEquals((r.status, r.str("firstName"), r.str("dateOfBirth"), r.str("playerId")), (200, "Ana", "1990-01-01", id), r.text)
    assert(!r.text.contains("token"), "no token on the profile")
    assertEquals(as(other, "GET", s"/$id").str("error"), "player_id_mismatch")
    val e = send(token, "PATCH", s"/$id", "firstName" -> "Bea", "phone" -> "+35699", "dateOfBirth" -> "1980-05-05")
    assertEquals((e.status, e.json.get("updated").asBoolean), (200, true), e.text)
    val p = player(id).profile
    assertEquals((p.get("firstName"), p.get("phone"), p.get("dateOfBirth")), (Some("Bea"), Some("+35699"), Some("1990-01-01")))
    val f = kit.eventually("player_profile_updated")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerProfileUpdated if f.playerId == id => f })
    assertEquals(f.changedFields.toSet, Set("firstName", "phone"))
    assertEquals(f.givenName.map(_.reveal), Some("Bea"))
    assertEquals(send(token, "PATCH", s"/$id").str("error"), "no_fields_provided")
    assertEquals(send(token, "PATCH", s"/$id", "mobile" -> "+1").status, 200)
    assertEquals(send(token, "PATCH", s"/$id", "mobile" -> "").status, 200)
    assertEquals(player(id).profile.get("mobile"), None)
  }

  test("the allow-list: a country outside a non-empty list is 400 invalid_country and nothing changes; an empty list admits any") {
    val (_, id, token) = signedIn("country" -> None)
    allow("GB", "MT")
    try
      val r = send(token, "PATCH", s"/$id", "country" -> "FR", "firstName" -> "Cy")
      assertEquals((r.status, r.str("error")), (400, "invalid_country"))
      assertEquals(player(id).profile.get("firstName"), None)
    finally allow()
    assertEquals(send(token, "PATCH", s"/$id", "country" -> "FR").status, 200)
    assertEquals(player(id).profile.get("country"), Some("FR"))
  }

  test("a second address: 202 and its link; followed, verified not primary; promoted, the realm signs in with it") {
    val (email, id, token) = signedIn()
    val second = s"second-${UUID.randomUUID().toString.take(8)}@example.test"
    val r = send(token, "POST", s"/$id/emails", "address" -> second)
    assertEquals((r.status, r.str("status")), (202, "verification_email_sent"), r.text)
    assertEquals(kit.request("POST", kit.playerPath(s"/emails/${enc(second)}/promote"), Some("{}"), kit.asToken(token)).str("error"), "not_verified")
    val (_, verified) = follow(kit.engagement.lastLink(second).getOrElse(fail("no link")))
    assertEquals((verified.status, verified.str("address")), (200, second), verified.text)
    val list = as(token, "GET", s"/$id/emails").json.get("emails")
    assertEquals(list.size, 2)
    assert(player(id).address(second).exists(a => a.verified && !a.primary))
    assertEquals(kit.request("DELETE", kit.playerPath(s"/emails/${enc(email)}"), None, kit.asToken(token)).str("error"), "cannot_remove_primary")
    val p = kit.request("POST", kit.playerPath(s"/emails/${enc(second)}/promote"), Some("{}"), kit.asToken(token))
    assertEquals((p.status, p.str("newPrimary")), (200, second), p.text)
    assertEquals(kit.realm.userByEmail(second).map(_.playerId), Some(id))
    assertEquals(kit.signIn(second).status, 200)
    assert(player(id).address(email).exists(!_.primary))
  }

  test("an address another player holds is 409 address_already_registered; the same twice 409 address_already_in_list") {
    val (otherEmail, _) = kit.activePlayer()
    val (_, id, token)  = signedIn()
    kit.eventually("the other's row")(io.eitheror.casino.players.player.PlayersView.find(kit.views, otherEmail, 5).headOption)
    assertEquals(send(token, "POST", s"/$id/emails", "address" -> otherEmail).str("error"), "address_already_registered")
    val mine = s"again-${UUID.randomUUID().toString.take(6)}@example.test"
    assertEquals(send(token, "POST", s"/$id/emails", "address" -> mine).status, 202)
    assertEquals(send(token, "POST", s"/$id/emails", "address" -> mine).str("error"), "address_already_in_list")
  }

  test("the sign-in address changed: the current password, then a link to the new address; followed, the realm's address changes") {
    val (_, id, token) = signedIn()
    val next = s"next-${UUID.randomUUID().toString.take(8)}@example.test"
    assertEquals(send(token, "POST", s"/$id/email-change", "newEmail" -> next, "currentPassword" -> "nope-nope").str("error"), "invalid_password")
    assertEquals(send(token, "POST", s"/$id/email-change", "newEmail" -> next, "currentPassword" -> "Passw0rd!").status, 202)
    val (_, r) = follow(kit.engagement.lastLink(next).getOrElse(fail("no link")))
    assertEquals((r.status, r.str("status")), (200, "email_changed"), r.text)
    assertEquals(kit.realm.userByEmail(next).map(_.playerId), Some(id))
    assertEquals(player(id).primary.map(_.address.reveal), Some(next))
  }

  test("the password: a wrong current one 400 invalid_password; the right one 202 and a link; followed, the realm's changes") {
    val (email, _, token) = signedIn()
    assertEquals(send(token, "POST", "/password-change", "currentPassword" -> "wrong-wrong").str("error"), "invalid_password")
    assertEquals(send(token, "POST", "/password-change", "currentPassword" -> "Passw0rd!").status, 202)
    val link = kit.engagement.lastLink(email).getOrElse(fail("no link"))
    assert(link.contains("/password-change/verify"), link)
    assertEquals(kit.realm.userByEmail(email).map(_.password), Some("Passw0rd!"))
    val (_, r) = follow(link, "newPassword" -> "N3w-passw0rd")
    assertEquals((r.status, r.str("status")), (200, "password_changed"), r.text)
    assertEquals(kit.realm.userByEmail(email).map(_.password), Some("N3w-passw0rd"))
    val reset = kit.json("POST", kit.playerPath("/password-reset/request"), Json.obj("email" -> email), kit.asVisitor)
    assertEquals(reset.str("status"), "ok")
    assert(kit.realm.userByEmail(email).exists(_.actions.contains("UPDATE_PASSWORD")))
    assertEquals(kit.json("POST", kit.playerPath("/password-reset/request"), Json.obj("email" -> "nobody@example.test"), kit.asVisitor).status, 200)
  }

  test("consent withdrawn: 200, read as withdrawn with its time, player_consent_changed from the player") {
    val (_, id, token) = signedIn("emailMarketingConsent" -> true)
    assertEquals(as(token, "GET", s"/$id/consent").json.get("emailMarketingConsent").asBoolean, true)
    assertEquals(send(token, "PATCH", s"/$id/consent", "emailMarketingConsent" -> false).status, 200)
    val c = as(token, "GET", s"/$id/consent")
    assertEquals((c.json.get("emailMarketingConsent").asBoolean, c.str("consentSource")), (false, "player"))
    assert(c.str("emailConsentAt").nonEmpty)
    val f = kit.eventually("player_consent_changed")(kit.facts(CasinoPlayers).collectFirst {
      case f: PlayerConsentChanged if f.playerId == id && !f.consented => f
    })
    assertEquals((f.channel, f.source), ("email", "player"))
    assertEquals(send(token, "PATCH", s"/$id/consent").str("error"), "no_fields_provided")
  }

  test("cookie decisions: whole, kept per device and shown to the operator, a repeat replaces; a field missing or no device 400") {
    val (_, id, token) = signedIn()
    def decide(fields: (String, Any)*) = send(token, "PATCH", s"/$id/cookie-consent", fields*)
    val whole = Seq("deviceId" -> "d-1", "analyticsConsent" -> true, "marketingConsent" -> false, "version" -> "v1", "timestamp" -> "2026-10-25T10:00:00Z")
    assertEquals(decide(whole*).status, 200)
    assertEquals(decide((whole.filterNot(_._1 == "deviceId") :+ ("deviceId" -> "d-1") :+ ("marketingConsent" -> true)).toMap.toSeq*).status, 200)
    assertEquals(decide(whole.filterNot(_._1 == "version")*).str("error"), "invalid_cookie_consent_body")
    assertEquals(decide(whole.filterNot(_._1 == "deviceId")*).str("error"), "missing_device_id")
    val crm = kit.request("GET", kit.operatorPath(s"/players/$id/crm"), None, kit.asOperator()).json.get("cookieConsents")
    assertEquals((crm.size, crm.get(0).get("marketingConsent").asBoolean), (1, true))
  }

  test("favourites, recently played newest first, and the history by category, never another player's") {
    val (_, id, token) = signedIn()
    assertEquals(kit.request("POST", kit.playerPath(s"/$id/favourites/g-1"), None, kit.asToken(token)).status, 200)
    kit.request("POST", kit.playerPath(s"/$id/favourites/g-2"), None, kit.asToken(token)): Unit
    kit.request("DELETE", kit.playerPath(s"/$id/favourites/g-1"), None, kit.asToken(token)): Unit
    assertEquals(as(token, "GET", s"/$id/favourites").json.get("gameIds").get(0).asText, "g-2")
    val at = Instant.now()
    def round(game: String, when: Instant, who: String = id) = kit.publish(CasinoRounds, s"$who/EUR", RoundSettled(who, Currency.unsafe("EUR"),
      s"r-${UUID.randomUUID()}", "st8", "pr", game, "gs-1", MinorUnits.unsafe(100), MinorUnits.unsafe(0), SignedMinorUnits(-100), "loss",
      Vector.empty, Vector.empty, Vector.empty, None, when))
    round("g-old", at.minusSeconds(60)); round("g-new", at); round("g-theirs", at, "someone-else")
    val recent = kit.eventually("recently played")(Option(as(token, "GET", s"/$id/recently-played").json.get("gameIds")).filter(_.size == 2))
    assertEquals((recent.get(0).asText, recent.get(1).asText), ("g-new", "g-old"))
    val events = kit.eventually("history")(Option(as(token, "GET", s"/$id/activity-history?format=json&category=games").json.get("events"))
      .filter(_.size == 2))
    assertEquals(events.get(0).get("category").asText, "Games")
    val csv = as(token, "GET", s"/$id/activity-history")
    assertEquals(csv.header("content-type").map(_.takeWhile(_ != ';')), Some("text/csv"))
    assert(csv.text.linesIterator.next().startsWith("timestamp,category"), csv.text)
    assert(!csv.text.contains("g-theirs"))
  }

  test("the inbox: engagement's write under a delivery id, again the same; listed unread; read; deleted for the player only") {
    val (_, id, token) = signedIn("emailMarketingConsent" -> true)
    val delivery = s"d-${UUID.randomUUID()}"
    def write(forPlayer: String = id, category: String = "service") = kit.json("POST", kit.servicePath(s"/$forPlayer/messages"),
      Json.obj("deliveryId" -> delivery, "category" -> category, "subject" -> "Hi", "body" -> "Welcome"), kit.asService("engagement"))
    val w = write()
    assertEquals((w.status, w.str("notificationId")), (201, delivery), w.text)
    assertEquals(write().status, 201)
    assertEquals(kit.json("POST", kit.servicePath(s"/$id/messages"), Json.obj("deliveryId" -> "x", "category" -> "service"),
      kit.asService("wallet")).status, 403)
    val listed = kit.eventually("listed")(Option(as(token, "GET", s"/$id/messages")).filter(_.json.get("items").size == 1))
    assertEquals((listed.json.get("unreadCount").asInt, listed.json.get("items").get(0).get("subject").asText), (1, "Hi"))
    assertEquals(kit.request("POST", kit.playerPath(s"/messages/$delivery/read"), None, kit.asToken(token)).status, 200)
    kit.eventually("read")(Option(as(token, "GET", s"/$id/messages")).filter(_.json.get("unreadCount").asInt == 0))
    assertEquals(kit.request("POST", kit.playerPath(s"/messages/$delivery/delete"), None, kit.asToken(token)).status, 200)
    kit.eventually("gone")(Option(as(token, "GET", s"/$id/messages")).filter(_.json.get("items").size == 0))
    assertEquals(MessagesView.ofPlayer(kit.views, id, 0, 10, includeDeleted = true).size, 1)
    assertEquals(kit.request("POST", kit.playerPath(s"/messages/$delivery/read"), None, kit.asToken(token)).str("error"), "message_not_found")
    kit.eventually("player_message_deleted")(kit.factTexts(CasinoPlayers).find(t => t.contains("player_message_deleted") && t.contains(delivery)))
    kit.request("PATCH", kit.operatorPath(s"/players/$id/crm"), Some("""{"messageSuppressed":true}"""), kit.asOperator()): Unit
    assertEquals(kit.json("POST", kit.servicePath(s"/$id/messages"), Json.obj("deliveryId" -> s"d-${UUID.randomUUID()}", "category" -> "service"),
      kit.asService("engagement")).str("error"), "marketing_suppressed")
  }

  test("the operator: date of birth needs a reason, refuses under 18, takes 18 and audits the reason not the date; kyc locks it") {
    val (_, id, _) = signedIn("dateOfBirth" -> "1990-01-01")
    def correct(fields: (String, Any)*) =
      kit.request("PATCH", kit.operatorPath(s"/players/$id/date-of-birth"), Some(Json.render(Json.obj(fields*))), kit.asOperator(subject = "op-3"))
    val today = LocalDate.now(ZoneOffset.UTC)
    assertEquals(correct("dateOfBirth" -> "1991-01-01").str("error"), "reason_required")
    assertEquals(correct("dateOfBirth" -> today.minusYears(17).toString, "reason" -> "typo").str("error"), "underage")
    val eighteen = today.minusYears(18).toString
    val r = correct("dateOfBirth" -> eighteen, "reason" -> "typo")
    assertEquals((r.status, r.str("dateOfBirth")), (200, eighteen), r.text)
    val audit = kit.eventually("audit")(kit.facts(PlatformAudit).collectFirst { case a: AuditRecord if a.action == "date-of-birth" && a.subjectId == id => a })
    assertEquals((audit.actorId, audit.reasonCode), ("op-3", Some("typo")))
    assert(!kit.factTexts(PlatformAudit).exists(_.contains(eighteen)))
    kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.kycVerified).invoke(io.eitheror.casino.players.player.At(kit.now())): Unit
    assertEquals(correct("dateOfBirth" -> "1985-01-01", "reason" -> "typo").str("error"), "kyc_locked")
    val op = kit.request("PATCH", kit.operatorPath(s"/players/$id/profile"), Some("""{"firstName":"Op","dateOfBirth":"1970-01-01"}"""), kit.asOperator())
    assertEquals(op.status, 200)
    assertEquals((player(id).profile.get("firstName"), player(id).profile.get("dateOfBirth")), (Some("Op"), Some(eighteen)))
    val reset = kit.request("POST", kit.operatorPath(s"/players/$id/reset-password"), Some("{}"), kit.asOperator(subject = "op-4"))
    assertEquals(reset.str("status"), "reset_link_sent")
    kit.eventually("reset audited")(kit.facts(PlatformAudit).collectFirst { case a: AuditRecord if a.action == "reset-password" && a.actorId == "op-4" => a })
    val profile = kit.request("GET", kit.operatorPath(s"/players/$id/profile"), None, kit.asOperator())
    assertEquals((profile.status, profile.json.get("player").get("kycStatus").asText), (200, "verified"))
  }
