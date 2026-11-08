package io.eitheror.casino.engagement.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.engagement.{EngagementRuntime, Json}
import io.eitheror.casino.engagement.api.Signature
import io.eitheror.casino.engagement.config.Secrets
import io.eitheror.casino.engagement.it.{Answer, Kit}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * features/engagement/campaigns.feature (US5; FR-017 to FR-019): Customer.io's webhook as Customer.io calls it, signed
 * under the brand's key, with `rewards` and `players` as the kit's fakes. The award's source is `rewards`' own: its
 * campaign award route records every reward it opens as `customerio_webhook` (spec 011), so the step checks that the
 * webhook reached that route. A repeat's "writes nothing" is `players`' idempotency on the delivery id, which the fake
 * answers as `players` does.
 */
class CampaignsFeatures extends EngagementFeatures("campaigns.feature"):
  private enum Signing:
    case Valid, Missing, Malformed, Wrong

  private var signing = Signing.Valid
  private var offer   = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.rewards.reset()
    kit.players.reset()
    signing = Signing.Valid
    offer = s"offer-${player.drop(2)}"

  override def afterEach(context: AfterEach): Unit =
    kit.configure(EngagementFeatures.Triggers): Unit
    super.afterEach(context)

  private def post(body: JsonNode): Unit =
    val text = Json.render(body)
    val ts   = Instant.now().getEpochSecond.toString
    val signature = signing match
      case Signing.Valid     => Some(Signature.sign(Kit.SigningKey, ts, text.getBytes(UTF_8)))
      case Signing.Missing   => None
      case Signing.Malformed => Some("not-a-signature")
      case Signing.Wrong     => Some(Signature.sign("another-key", ts, text.getBytes(UTF_8)))
    val headers = Seq("X-CIO-Timestamp" -> ts) ++ signature.map("X-CIO-Signature" -> _)
    last = kit.request("POST", s"/v1/casino/customerio/webhook/${kit.slug}", Some(text), headers)

  private def grant(p: String = player) = Json.obj("action" -> "grant_bonus", "player_id" -> p, "offer_id" -> offer, "amount" -> 10)
  private def message(p: String = player, delivery: Option[String] = Some(s"d-$player")) =
    Json.obj("action" -> "send_message", "player_id" -> p, "delivery_id" -> delivery, "title" -> "A message from your casino",
      "body" -> "Something new is waiting in the lobby.", "campaign_id" -> "c-1")

  private def nothingDone(): Unit =
    Thread.sleep(300)
    assertEquals((kit.rewards.calls("award"), kit.rewards.calls("target"), kit.players.messages), (Vector.empty, Vector.empty, Vector.empty))

  private val signed = "a brand with a signing key for its CRM webhook, a valid signature over the request and a time within the window"
  Given(signed) { () => signing = Signing.Valid }
  Given("a brand with a signing key for its CRM webhook and a request with no signature") { () => signing = Signing.Missing }
  Given("a brand with a signing key for its CRM webhook and a request with a malformed signature") { () => signing = Signing.Malformed }
  Given("a brand with a signing key for its CRM webhook and a request with a wrong signature") { () => signing = Signing.Wrong }
  Given("a brand with no signing key for its CRM webhook") { () =>
    kit.secrets.delete(Secrets.signing(kit.slug))
    EngagementRuntime.secrets.forget()
  }

  // grant_bonus

  When("a {string} action names a player and an active offer") { (action: String) =>
    assertEquals(action, "grant_bonus")
    kit.rewards.answer(player, "award", Answer(200, """{"ok":true,"bonusAmount":10}"""))
    post(grant())
  }

  When("a {string} action names a player who already holds a claim of the offer") { (action: String) =>
    assertEquals(action, "grant_bonus")
    kit.rewards.answer(player, "award", Answer(200, """{"ok":true,"alreadyProcessed":true}"""))
    post(grant())
  }

  When("a {string} action names an offer the brand's rewards refuse") { (action: String) =>
    assertEquals(action, "grant_bonus")
    kit.rewards.answer(player, "award", Answer(422, """{"error":"offer_not_found"}"""))
    post(grant())
  }

  Then("the brand's rewards are asked to award it with source {string}") { (source: String) =>
    assertEquals(source, "customerio_webhook")
    val calls = kit.rewards.calls("award", player)
    assertEquals(calls.size, 1)
    assertEquals(calls.head.json.map(_.path("offerId").asText), Some(offer))
  }

  Then("the answer is {string}") { (field: String) =>
    assertEquals(last.status, 200, last.text)
    assertEquals(last.json.path(field).asBoolean, true, last.text)
  }

  Then("the answer is {string} with {string}") { (field: String, also: String) =>
    assertEquals(last.status, 200, last.text)
    assertEquals((last.json.path(field).asBoolean, last.json.path(also).asBoolean), (true, true), last.text)
  }

  Then("the answer carries the rewards' reason, {string}, {string}, {string} or {string}") { (a: String, b: String, c: String, d: String) =>
    assertEquals(last.status, 422, last.text)
    assert(Set(a, b, c, d)(last.str("error")), last.text)
  }

  Then("nothing is awarded") { () =>
    assertEquals(kit.rewards.calls("award", player).size, 1)
    assertEquals(last.json.has("bonusAmount"), false)
  }

  // send_message

  When("a {string} action arrives with a delivery id, a title and a body") { (action: String) =>
    assertEquals(action, "send_message")
    post(message())
  }

  When("a {string} action arrives without a delivery id") { (action: String) =>
    assertEquals(action, "send_message")
    post(message(delivery = None))
  }

  Then("one inbox item of the marketing class is written through the brand's player records under that delivery id") { () =>
    assertEquals((last.status, last.text), (200, """{"ok":true}"""))
    val m = kit.players.messages(player)
    assertEquals(m.size, 1)
    val b = m.head.json.get
    assertEquals((b.path("deliveryId").asText, b.path("category").asText, b.path("subject").asText),
      (s"d-$player", "marketing", "A message from your casino"))
    assertEquals(b.has("campaign_id") || b.has("campaignId"), false)
  }

  Then("when the player records refuse it, the answer is their refusal, {string}, {string} or {string}") { (a: String, b: String, c: String) =>
    for (code, status) <- Vector(a -> 422, b -> 422, c -> 404) do
      val other = newPlayer()
      kit.players.answer(other, Answer(status, s"""{"error":"$code"}"""))
      post(message(other, Some(s"d-$other")))
      assertEquals((last.status, last.str("error")), (status, code), last.text)
  }

  Then("a repeat with the same delivery id writes nothing") { () =>
    kit.players.answer(player, Answer(200, """{"ok":true,"alreadyProcessed":true}"""))
    post(message())
    assertEquals(last.json.path("alreadyProcessed").asBoolean, true, last.text)
    assertEquals(kit.players.messages(player).map(_.json.get.path("deliveryId").asText).distinct, Vector(s"d-$player"))
  }

  Then("it is refused as {string}") { (code: String) =>
    assertEquals((last.status, last.str("error")), (422, code), last.text)
    nothingDone()
  }

  // The refusals.

  When("a request arrives") { () => post(grant()) }

  When("a request arrives naming an action the brand does not know") { () =>
    post(Json.obj("action" -> "delete_everything", "player_id" -> player))
  }

  Then("it is refused as unauthenticated") { () =>
    assertEquals(last.status, 401, last.text)
    assert(Set("invalid_signature", "webhook_not_configured")(last.str("error")), last.text)
  }

  Then("nothing is done") { () => nothingDone() }
