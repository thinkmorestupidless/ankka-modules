package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{CasinoPlayers, PlayerRegistered}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, Status}

import java.time.Duration as JDuration
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** US1 through the routes on the kit (FR-001 to FR-006, SC-007; quickstart §5). */
class RegistrationSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def address = s"reg-${UUID.randomUUID().toString.take(8)}@example.test"
  private def player(id: String): PlayerState =
    kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def registered(id: String): PlayerRegistered =
    kit.eventually(s"player_registered for $id")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerRegistered if f.playerId == id => f })

  test("a registration with every field: 201, a UUID player id, pending verification; the realm user carries the id, unverified; the player holds every field") {
    val email = address
    val r = kit.register("email" -> email, "displayName" -> "Ada", "firstName" -> "Ada", "lastName" -> "Lovelace", "country" -> "GB",
      "language" -> "en", "dateOfBirth" -> "1990-12-10", "bonusCode" -> "WELCOME", "btag" -> "a_123b_456c_", "clickId" -> "ck-1",
      "deviceFingerprint" -> "fp-1")
    assertEquals(r.status, 201, r.text)
    val id = r.str("playerId")
    assertEquals(UUID.fromString(id).toString, id)
    assertEquals(r.str("status"), "pending_email_verification")
    val user = kit.realm.userByEmail(email).getOrElse(fail("no realm user"))
    assertEquals((user.playerId, user.verified), (id, false))
    val p = player(id)
    assertEquals(p.status, Status.Pending)
    assertEquals(Vector("displayName", "firstName", "lastName", "country", "language", "dateOfBirth").map(p.profile.get),
      Vector("Ada", "Ada", "Lovelace", "GB", "en", "1990-12-10").map(Some(_)))
    assertEquals(p.primary.map(_.address.reveal), Some(email))
    assertEquals(p.currency, "EUR")
    assertEquals((p.registration.tag, p.registration.clickId, p.registration.bonusCode, p.registration.deviceFingerprint.map(_.reveal)),
      (Some("a_123b_456c_"), Some("ck-1"), Some("WELCOME"), Some("fp-1")))
  }

  test("the fact on casino.players carries the tag, click id and bonus code as sent, no VIP tier, the country, the currency and the time") {
    val r  = kit.register("btag" -> "a_1b_2c_", "clickId" -> "ck-9", "bonusCode" -> "B100", "country" -> "MT", "currency" -> "eur")
    val id = r.str("playerId")
    val f  = registered(id)
    assertEquals((f.btag, f.clickId, f.bonusCode, f.vipTier, f.country, f.currency), (Some("a_1b_2c_"), Some("ck-9"), Some("B100"), None, Some("MT"), Some("EUR")))
    assert(f.registeredAt.isBefore(kit.now().plusSeconds(1)))
  }

  test("a tag that is not well formed: 201 and no tag on the fact; a tag under trackingContext is taken") {
    val bad = kit.register("btag" -> "bad tag!")
    assertEquals(bad.status, 201, bad.text)
    assertEquals(registered(bad.str("playerId")).btag, None)
    val nested = kit.register("trackingContext" -> Json.obj("btag" -> "t-1"))
    assertEquals(registered(nested.str("playerId")).btag, Some("t-1"))
  }

  test("a missing required field: 400 missing_fields naming the three, and nothing is created") {
    Seq("email", "password", "currency").foreach { field =>
      val r = kit.register(field -> None)
      assertEquals((r.status, r.str("error")), (400, "missing_fields"), field)
      assertEquals(r.json.get("fields").elements.asScala.map(_.asText).toVector, Vector("email", "password", "currency"))
    }
    assertEquals(kit.realm.receivedOf("create"), Vector.empty)
  }

  test("an address already registered: 409; a password the realm refuses: 422") {
    val email = address
    assertEquals(kit.register("email" -> email).status, 201)
    assertEquals(kit.register("email" -> email).str("error"), "email_already_exists")
    val weak = kit.register("password" -> "abc")
    assertEquals((weak.status, weak.str("error")), (422, "password_too_weak"))
  }

  test("consents given: recorded with the request's time and the source registration; not given: false with no time") {
    val r = kit.register("emailMarketingConsent" -> true, "smsMarketingConsent" -> false)
    val c = player(r.str("playerId")).consents
    assertEquals((c.email.granted, c.email.source, c.email.at.isDefined), (true, Some("registration"), true))
    assertEquals((c.sms.granted, c.sms.at), (false, None))
  }

  test("the caller's network address and its country kept as personal data; the player's own country unchanged") {
    kit.geo.countries.put("203.0.113.41", "ZA")
    val r = kit.json("POST", kit.playerPath("/register"), Json.obj("email" -> address, "password" -> "Passw0rd!", "currency" -> "EUR",
      "country" -> "GB"), kit.asVisitor :+ ("X-Forwarded-For" -> "203.0.113.41, 10.0.0.1"))
    assertEquals(r.status, 201, r.text)
    val p = player(r.str("playerId"))
    assertEquals((p.registration.networkAddress.map(_.reveal), p.registration.networkCountry), (Some("203.0.113.41"), Some("ZA")))
    assertEquals(p.profile.get("country"), Some("GB"))
    assertEquals(registered(p.playerId).country, Some("GB"))
  }

  test("the verification link: 200 and active, the realm verified; again 200 and nothing changes; past 24 hours 400") {
    val email = address
    val id    = kit.register("email" -> email).str("playerId")
    val first = kit.verify(email)
    assertEquals((first.status, first.str("status")), (200, "verified"), first.text)
    assertEquals(player(id).status, Status.Active)
    assert(kit.realm.userByEmail(email).exists(_.verified))
    val trail = player(id).trail
    assertEquals(kit.verify(email).status, 200)
    assertEquals(player(id).trail, trail)

    val late = address
    kit.register("email" -> late)
    kit.eventually("the link sent")(kit.engagement.lastLink(late))
    kit.advance(JDuration.ofHours(25))
    val expired = kit.verify(late)
    assertEquals((expired.status, expired.str("error")), (400, "invalid_or_expired_token"))
    assertEquals(kit.json("POST", kit.playerPath("/verify-email"), Json.obj(), kit.asVisitor).str("error"), "missing_token")
    assertEquals(kit.json("POST", kit.playerPath("/verify-email"), Json.obj("token" -> "x.y"), kit.asVisitor).str("error"), "invalid_or_expired_token")
  }

  test("a resend within 5 minutes: 429 with retry_after_seconds; after them: 200 and a second send; an unknown address: 200 and no send") {
    val email = address
    val id    = kit.register("email" -> email).str("playerId")
    kit.eventually("the first send")(kit.engagement.lastLink(email))
    // The workflow records the send once engagement has answered, a moment after the fake has it.
    kit.eventually("the send recorded")(player(id).verificationEmail.lastSentAt)
    val soon = kit.json("POST", kit.playerPath("/resend-verification-email"), Json.obj("email" -> email), kit.asVisitor)
    assertEquals((soon.status, soon.str("error")), (429, "rate_limited"), soon.text)
    assert(soon.json.get("retry_after_seconds").asLong > 0)
    kit.advance(JDuration.ofMinutes(5).plusSeconds(1))
    val later = kit.json("POST", kit.playerPath("/resend-verification-email"), Json.obj("email" -> email), kit.asVisitor)
    assertEquals((later.status, later.str("result")), (200, "sent"))
    assertEquals(kit.engagement.sendsTo(email).size, 2)
    val unknown = kit.json("POST", kit.playerPath("/resend-verification-email"), Json.obj("email" -> address), kit.asVisitor)
    assertEquals((unknown.status, unknown.str("result")), (200, "sent"))
    assertEquals(kit.engagement.sends.size, 2)
  }

  test("the calls one registration makes: one realm user created, one email sent, no other service called") {
    val email = address
    kit.register("email" -> email)
    kit.eventually("the send")(kit.engagement.lastLink(email))
    assertEquals(kit.realm.receivedOf("create").size, 1)
    assertEquals(kit.engagement.sends.size, 1)
    assertEquals((kit.rg.received, kit.kyc.received, kit.wallet.received, kit.ingest.received, kit.agent.received),
      (Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty))
  }

  test("the geo lookup with ip-api silent answers nulls") {
    kit.geo.silent = true
    val r = kit.request("GET", "/v1/casino/geo/lookup", None, kit.asVisitor :+ ("X-Forwarded-For" -> s"203.0.113.${Math.floorMod(UUID.randomUUID().hashCode, 200)}"))
    assertEquals((r.status, r.json.get("country").isNull, r.json.get("dialCode").isNull), (200, true, true))
    kit.geo.silent = false
  }

  test("the operator's verification link: served on a brand without real money, 404 on a real-money brand") {
    val email = address
    val id    = kit.register("email" -> email).str("playerId")
    val r     = kit.request("GET", kit.operatorPath(s"/players/$id/verification-link"), None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    assert(r.str("link").contains("/verify-email?token="))
    kit.rewire(kit.settings.copy(realMoney = true))
    try assertEquals(kit.request("GET", kit.operatorPath(s"/players/$id/verification-link"), None, kit.asOperator()).status, 404)
    finally kit.rewire()
  }
