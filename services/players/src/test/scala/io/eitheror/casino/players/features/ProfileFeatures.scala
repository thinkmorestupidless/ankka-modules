package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.config.{AllowList, AllowListEntity}
import io.eitheror.casino.players.it.Reply
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDate, ZoneOffset}
import java.util.UUID

/** features/players/profile.feature (T050). */
class ProfileFeatures extends PlayersFeatures("profile.feature"):
  protected def pending = Map.empty

  private var email    = ""
  private var playerId = ""
  private var token    = ""
  private var second   = ""
  private var reply: Reply = null
  private var before: PlayerState = null
  private var dob      = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    email = ""; playerId = ""; token = ""; second = ""; reply = null; before = null; dob = ""

  private def player: PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()

  private def signedIn(fields: (String, Any)*): Unit =
    val (e, id) = kit.activePlayer(fields*)
    email = e; playerId = id; token = kit.signIn(e).str("token")

  private def send(method: String, path: String, fields: (String, Any)*): Reply =
    kit.request(method, kit.playerPath(path), Some(Json.render(Json.obj(fields*))), kit.asToken(token))

  private def follow(address: String, fields: (String, Any)*): Reply =
    val link  = kit.eventually(s"a link to $address")(kit.engagement.lastLink(address))
    val t     = java.net.URLDecoder.decode(link.split("token=", 2)(1), UTF_8)
    val path  = link.stripPrefix(kit.settings.playerSiteBase).takeWhile(_ != '?')
    kit.json("POST", kit.playerPath(path), Json.obj((("token" -> t) +: fields)*), kit.asVisitor)

  private def allow(countries: String*): Unit =
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(AllowListEntity.set).invoke(AllowList(countries.toSet)): Unit

  Given("a player of brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  // ── the profile ────────────────────────────────────────────────────────────────────────────────

  Given("the player has a profile") { () => signedIn("firstName" -> "Ada", "lastName" -> "King", "phone" -> "+44700") }
  When("they read it") { () => reply = kit.request("GET", kit.playerPath(s"/$playerId"), None, kit.asToken(token)) }
  Then("they see every field of the profile") { () =>
    assertEquals(reply.status, 200, reply.text)
    player.profile.fields.keys.foreach(f => assertEquals(reply.str(f), player.profile.get(f).get, f))
  }
  Then("every personal field is readable to them and to no other player") { () =>
    val (o, _) = kit.activePlayer()
    val theirs = kit.request("GET", kit.playerPath(s"/$playerId"), None, kit.asToken(kit.signIn(o).str("token")))
    assertEquals((theirs.status, theirs.str("error")), (403, "player_id_mismatch"))
    assert(!theirs.text.contains("King"))
  }

  Given("the player's date of birth is set") { () => signedIn("dateOfBirth" -> "1990-01-01") }
  When("they send a different date of birth with other fields") { () =>
    reply = send("PATCH", s"/$playerId", "dateOfBirth" -> "1980-01-01", "firstName" -> "Bo", "city" -> "Valletta")
  }
  Then("the date of birth is unchanged") { () => assertEquals(player.profile.get("dateOfBirth"), Some("1990-01-01")) }
  Then("the other fields sent are applied") { () =>
    assertEquals((reply.status, player.profile.get("firstName"), player.profile.get("city")), (200, Some("Bo"), Some("Valletta")))
  }
  Then("the {string} fact names only the fields the player changed and carries the completeness fields KYC reads") { (name: String) =>
    assertEquals(name, "player_profile_updated")
    val f = kit.eventually(name)(kit.facts(CasinoPlayers).collectFirst { case f: PlayerProfileUpdated if f.playerId == playerId => f })
    assertEquals(f.changedFields.toSet, Set("firstName", "city"))
    assertEquals((f.givenName.map(_.reveal), f.city.map(_.reveal), f.dateOfBirth.map(_.reveal)), (Some("Bo"), Some("Valletta"), Some("1990-01-01")))
  }

  Given("a country the brand does not allow") { () => signedIn("country" -> None); allow("GB") }
  When("the player sets it") { () =>
    before = player
    try reply = send("PATCH", s"/$playerId", "country" -> "FR", "firstName" -> "Cy")
    finally allow()
  }
  Then("the edit is refused as {string}") { (code: String) => assertEquals((reply.status, reply.str("error")), (400, code)) }
  Then("nothing changes") { () => assertEquals(player.profile, before.profile) }

  // ── the addresses ──────────────────────────────────────────────────────────────────────────────

  private def addAndVerify(): Unit =
    second = s"second-${UUID.randomUUID().toString.take(8)}@example.test"
    assertEquals(send("POST", s"/$playerId/emails", "address" -> second).status, 202)
    assertEquals(follow(second).status, 200)

  Given("the player has one email address") { () => signedIn() }
  When("they add a second and follow its verification link") { () => addAndVerify() }
  Then("the second is verified") { () => assert(player.address(second).exists(_.verified)) }
  Then("it is not the sign-in address") { () =>
    assert(player.address(second).exists(!_.primary))
    assertEquals(kit.realm.userByEmail(second), None)
  }

  Given("the player has a verified second email address") { () => signedIn(); addAndVerify() }
  When("they make it their sign-in address") { () =>
    reply = kit.request("POST", kit.playerPath(s"/emails/${URLEncoder.encode(second, UTF_8)}/promote"), Some("{}"), kit.asToken(token))
  }
  Then("it is the address the realm signs them in with") { () =>
    assertEquals(reply.status, 200, reply.text)
    assertEquals(kit.signIn(second).status, 200)
  }
  Then("the first is no longer the sign-in address") { () =>
    assert(player.address(email).exists(!_.primary))
    assertEquals(kit.signIn(email).status, 401)
  }

  // ── the password ───────────────────────────────────────────────────────────────────────────────

  Given("the player changing their password") { () => signedIn() }
  When("they give the current password and the new one") { () => reply = send("POST", "/password-change", "currentPassword" -> "Passw0rd!") }
  Then("a confirmation email is sent") { () =>
    assertEquals(reply.status, 202)
    assert(kit.engagement.sendsTo(email).exists(_.trigger == "PasswordChangeVerification"))
  }
  Then("the password changes only when the email's link is followed") { () =>
    assertEquals(kit.realm.userByEmail(email).map(_.password), Some("Passw0rd!"))
    assertEquals(follow(email, "newPassword" -> "An0ther-one").status, 200)
    assertEquals(kit.realm.userByEmail(email).map(_.password), Some("An0ther-one"))
  }
  When("they give a wrong current password") { () => reply = send("POST", "/password-change", "currentPassword" -> "not-it-at-all") }
  Then("it is refused as {string}") { (code: String) => assertEquals((reply.status, reply.str("error")), (400, code)) }

  // ── consents and cookies ───────────────────────────────────────────────────────────────────────

  Given("the player gave email marketing consent") { () => signedIn("emailMarketingConsent" -> true) }
  When("they withdraw it") { () => reply = send("PATCH", s"/$playerId/consent", "emailMarketingConsent" -> false) }
  Then("the consent reads as withdrawn with the time") { () =>
    val c = kit.request("GET", kit.playerPath(s"/$playerId/consent"), None, kit.asToken(token))
    assertEquals(c.json.get("emailMarketingConsent").asBoolean, false)
    assertEquals(c.str("emailConsentAt"), player.consents.email.at.get.toString)
  }
  Then("a {string} fact is produced") { (name: String) =>
    assertEquals(name, "player_consent_changed")
    kit.eventually(name)(kit.facts(CasinoPlayers).collectFirst { case f: PlayerConsentChanged if f.playerId == playerId && !f.consented => f })
  }
  Then("the brand's engagement stops sending marketing email from that fact") { () =>
    val f = kit.facts(CasinoPlayers).collectFirst { case f: PlayerConsentChanged if f.playerId == playerId && !f.consented => f }.get
    assertEquals((f.channel, f.source), ("email", "player"))
  }

  private val device = Seq[(String, Any)]("deviceId" -> "dev-1", "analyticsConsent" -> true, "marketingConsent" -> false,
    "version" -> "v3", "timestamp" -> "2026-10-25T09:00:00Z")

  Given("the player's device") { () => signedIn() }
  When("the site records a cookie decision for it with analytics, marketing, version and time") { () =>
    reply = send("PATCH", s"/$playerId/cookie-consent", device*)
  }
  Then("it is kept per device") { () =>
    assertEquals(reply.status, 200)
    assertEquals(player.cookieDecisions.get("dev-1").map(d => (d.analytics, d.marketing, d.version)), Some((true, false, "v3")))
  }
  Then("an operator can see it") { () =>
    val crm = kit.request("GET", kit.operatorPath(s"/players/$playerId/crm"), None, kit.asOperator())
    assertEquals(crm.json.get("cookieConsents").get(0).get("deviceId").asText, "dev-1")
  }
  When("the site records a cookie decision for it with a field missing") { () =>
    reply = send("PATCH", s"/$playerId/cookie-consent", device.filterNot(_._1 == "marketingConsent")*)
  }
  Then("it is refused rather than defaulted") { () =>
    assertEquals((reply.status, reply.str("error")), (400, "invalid_cookie_consent_body"))
    assertEquals(player.cookieDecisions, Map.empty)
  }

  // ── the operator's correction ──────────────────────────────────────────────────────────────────

  Given("an operator correcting the player's date of birth with a reason") { () => signedIn("dateOfBirth" -> "1990-01-01") }
  private def correct(years: Int, plusDays: Int): Unit =
    dob = LocalDate.now(ZoneOffset.UTC).minusYears(years).plusDays(plusDays).toString
    reply = kit.request("PATCH", kit.operatorPath(s"/players/$playerId/date-of-birth"),
      Some(Json.render(Json.obj("dateOfBirth" -> dob, "reason" -> "document check"))), kit.asOperator(subject = "op-5"))
  When("the corrected date makes the player younger than {string}") { (years: String) => correct(years.toInt, 1) }
  When("the corrected date makes the player {string} or over") { (years: String) => correct(years.toInt, 0) }
  Then("it is refused") { () =>
    assertEquals((reply.status, reply.str("error")), (400, "underage"))
    assertEquals(player.profile.get("dateOfBirth"), Some("1990-01-01"))
  }
  Then("it is applied") { () => assertEquals((reply.status, player.profile.get("dateOfBirth")), (200, Some(dob))) }
  Then("it is audited with the reason") { () =>
    val a = kit.eventually("the audit record")(kit.facts(PlatformAudit).collectFirst {
      case a: AuditRecord if a.action == "date-of-birth" && a.subjectId == playerId => a
    })
    assertEquals((a.actorId, a.reasonCode), ("op-5", Some("document check")))
  }
