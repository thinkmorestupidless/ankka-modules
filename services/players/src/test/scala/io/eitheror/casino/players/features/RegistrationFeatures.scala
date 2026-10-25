package io.eitheror.casino.players.features

import io.eitheror.casino.contract.facts.{CasinoPlayers, PlayerRegistered}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.it.Reply
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState}

import java.time.Duration as JDuration
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** features/players/registration.feature (US1: FR-001 to FR-005), through the public routes. */
class RegistrationFeatures extends PlayersFeatures("registration.feature"):
  protected def pending = Map.empty

  private var email: String                   = ""
  private var fields: Vector[(String, Any)]   = Vector.empty
  private var headers: Seq[(String, String)]  = Nil
  private var reply: Reply                    = null
  private var creates: Int                    = 0
  private var existing: Option[String]        = None

  private def fresh(): String = s"feat-${UUID.randomUUID().toString.take(8)}@example.test"
  private def playerId: String = reply.str("playerId")
  private def player(id: String = playerId): PlayerState =
    kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def fact: PlayerRegistered =
    kit.eventually("the player_registered fact")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerRegistered if f.playerId == playerId => f })

  private def send(): Unit =
    val body = Json.obj((Vector[(String, Any)]("email" -> email, "password" -> "Passw0rd!", "currency" -> "EUR")
      .filterNot(f => fields.exists(_._1 == f._1)) ++ fields.filterNot(_._2 == None))*)
    reply = kit.json("POST", kit.playerPath("/register"), body, kit.asVisitor ++ headers)

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    email = fresh(); fields = Vector.empty; headers = Nil; reply = null; creates = 0; existing = None

  Given("brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  // ── a registration and its refusals ──────────────────────────────────────────────────────────

  Given("an email address not yet registered at the brand") { () => assertEquals(kit.realm.userByEmail(email), None) }
  When("a visitor registers with it, a password the realm accepts and a currency the brand offers") { () =>
    fields = Vector("btag" -> "a_1b_2c_", "clickId" -> "ck-1", "bonusCode" -> "WELCOME", "country" -> "GB")
    send()
  }
  Then("they are a player with player status {string}") { (status: String) =>
    assertEquals(reply.status, 201, reply.text)
    assertEquals((reply.str("status"), player().status), (status, status))
  }
  Then("the answer carries the player id") { () => assertEquals(UUID.fromString(playerId).toString, playerId) }
  Then("a verification email is sent") { () =>
    kit.eventually("the verification email")(kit.engagement.lastLink(email))
    assertEquals(kit.engagement.sendsTo(email).map(_.playerId), Vector(playerId))
  }
  Then("the {string} fact carries the registered time, country, currency, the tag, the click id and the bonus code as presented") {
    (name: String) =>
      assertEquals(name, "player_registered")
      val f = fact
      assertEquals((f.country, f.currency, f.btag, f.clickId, f.bonusCode), (Some("GB"), Some("EUR"), Some("a_1b_2c_"), Some("ck-1"), Some("WELCOME")))
      assertEquals(f.registeredAt, player().registeredAt.get)
  }

  Given("an email address already registered at the brand") { () =>
    val first = kit.register("email" -> email)
    assertEquals(first.status, 201, first.text)
    existing = Some(first.str("playerId"))
  }
  Given("a password below the realm's policy") { () => fields = Vector("password" -> "short") }
  When("a visitor registers with it") { () =>
    creates = kit.realm.users.size
    send()
  }
  Then("the registration is refused as {string}") { (code: String) => assertEquals(reply.str("error"), code, reply.text) }
  Then("nothing is created") { () =>
    assertEquals(kit.realm.users.size, creates)
    val flow = kit.components.forWorkflow(io.eitheror.casino.players.registration.RegistrationWorkflow.idOf(kit.slug, email))
      .call(io.eitheror.casino.players.registration.RegistrationWorkflow.status).invoke()
    // The attempt refused made no player; a player there is the one registered before.
    // Refused while the first still sends its email, the flow is the first's; after it, a new attempt with no player.
    assert(flow.outcome.flatMap(_.playerId).forall(existing.contains), flow.toString)
    if !existing.contains(flow.playerId) then assert(!player(flow.playerId).exists)
  }

  Given("a registration carrying a tag that is not well formed") { () => fields = Vector("btag" -> "bad tag!") }
  When("it is received") { () => send() }
  When("it succeeds") { () =>
    send()
    assertEquals(reply.status, 201, reply.text)
  }
  Then("the registration succeeds") { () => assertEquals(reply.status, 201, reply.text) }
  Then("the {string} fact carries no tag") { (name: String) =>
    assertEquals(name, "player_registered")
    assertEquals(fact.btag, None)
  }

  Given("a registration missing the email address") { () => fields = Vector("email" -> None) }
  Given("a registration missing the password") { () => fields = Vector("password" -> None) }
  Given("a registration missing the currency") { () => fields = Vector("currency" -> None) }
  Then("it is refused naming the email address, the password and the currency as required") { () =>
    assertEquals((reply.status, reply.str("error")), (400, "missing_fields"))
    assertEquals(reply.json.get("fields").elements.asScala.map(_.asText).toVector, Vector("email", "password", "currency"))
  }

  // ── consents, the network address, verification ──────────────────────────────────────────────

  Given("a registration with marketing consents given") { () => fields = Vector("emailMarketingConsent" -> true, "smsMarketingConsent" -> true) }
  Then("the consents are recorded with the time they were given and the source {string}") { (source: String) =>
    val c   = player().consents
    val at  = player().registeredAt
    assertEquals((c.email.granted, c.email.at, c.email.source), (true, at, Some(source)))
    assertEquals((c.sms.granted, c.sms.at, c.sms.source), (true, at, Some(source)))
  }

  Given("a registration from a network address") { () =>
    kit.geo.countries.put("203.0.113.77", "ZA")
    headers = Seq("X-Forwarded-For" -> "203.0.113.77")
    fields = Vector("country" -> "GB")
  }
  Then("the network address and the country it resolves to are kept on the player as personal data") { () =>
    val r = player().registration
    assertEquals((r.networkAddress.map(_.reveal), r.networkCountry), (Some("203.0.113.77"), Some("ZA")))
  }
  Then("the player's own country is not overwritten by it") { () => assertEquals(player().profile.get("country"), Some("GB")) }

  Given("a registered player pending email verification") { () =>
    send()
    assertEquals(reply.status, 201, reply.text)
    kit.eventually("the verification email")(kit.engagement.lastLink(email))
  }
  When("they follow the verification link within {string} hours") { (hours: String) =>
    kit.advance(JDuration.ofHours(hours.toLong).minusMinutes(1))
    val r = kit.verify(email)
    assertEquals(r.status, 200, r.text)
  }
  Then("the realm marks the address verified") { () => assert(kit.realm.userByEmail(email).exists(_.verified)) }
  Then("the player status is {string}") { (status: String) => assertEquals(player().status, status) }
  Then("following the link again changes nothing") { () =>
    val before = player()
    assertEquals(kit.verify(email).status, 200)
    assertEquals(player(), before)
  }
