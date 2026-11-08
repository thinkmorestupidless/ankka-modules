package io.eitheror.casino.engagement.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.engagement.{EngagementRuntime, Json}
import io.eitheror.casino.engagement.api.Signature
import io.eitheror.casino.engagement.config.{ConfigChange, Configuration, CrmConfig, Secrets}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `POST /v1/casino/customerio/webhook/{brand}` (US5; FR-017 to FR-019; contracts/routes.md): every refusal in the
 * original's order and each counted on the brand's status; each action's neighbour answers passed through as they came;
 * the fields the original reads and drops never relayed; and the webhook steps the original answered in the rewards
 * recordings (scenario 21, the free-spins grants, the duplicate check's) replayed with `rewards`' recorded answer.
 */
class WebhookSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeAll(): Unit = kit.reset()

  override def beforeEach(context: BeforeEach): Unit =
    kit.configure(): Unit
    kit.rewards.reset()
    kit.players.reset()

  private def path(brand: String = kit.slug) = s"/v1/casino/customerio/webhook/$brand"

  private def post(text: String, brand: String = kit.slug, key: String = Kit.SigningKey,
      ts: String = Instant.now().getEpochSecond.toString, sign: Boolean = true): Reply =
    val headers = Seq("X-CIO-Timestamp" -> ts) ++
      Option.when(sign)("X-CIO-Signature" -> Signature.sign(key, ts, text.getBytes(UTF_8)))
    kit.request("POST", path(brand), Some(text), headers)

  private def post(body: JsonNode): Reply = post(Json.render(body))

  private def refusals: Map[String, Long] = EngagementRuntime.webhookRefusals.toMap

  private def player() = s"w-${UUID.randomUUID().toString.take(8)}"

  private def nothingRelayed(): Unit =
    assertEquals((kit.rewards.calls("award"), kit.rewards.calls("target"), kit.players.messages), (Vector.empty, Vector.empty, Vector.empty))

  test("the refusals in the original's order, each counted on the brand's status") {
    val body = Json.render(Json.obj("action" -> "grant_bonus", "player_id" -> player(), "offer_id" -> "o-1"))
    def refused(call: => Reply, status: Int, code: String): Unit =
      val before = refusals.getOrElse(code, 0L)
      val r      = call
      assertEquals((r.status, r.str("error")), (status, code), r.text)
      assertEquals(refusals.getOrElse(code, 0L), before + 1, code)

    refused(post(body, brand = "another-brand"), 404, "wrong_brand")
    refused(post(body, ts = "yesterday"), 401, "timestamp_invalid")
    refused(post(body, ts = (Instant.now().getEpochSecond - 300).toString), 401, "timestamp_invalid")
    refused(post(body, sign = false), 401, "invalid_signature")
    refused(post(body, key = "another-key"), 401, "invalid_signature")
    refused(post("[1, 2]"), 400, "invalid_body")
    refused(post(Json.obj("action" -> "delete_everything")), 422, "unknown_action")
    refused(post(Json.obj("player_id" -> player())), 422, "unknown_action")

    kit.secrets.delete(Secrets.signing(kit.slug))
    EngagementRuntime.secrets.forget()
    refused(post(body), 401, "webhook_not_configured")
    kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(kit.slug)).call(Configuration.put)
      .invoke(ConfigChange(CrmConfig(), "suite")): Unit
    refused(post(body), 401, "brand_not_configured")
    nothingRelayed()

    val status = kit.request("GET", s"/v1/casino/backoffice/${kit.slug}/engagement/status", None, kit.asOperator())
    assert(status.json.get("webhookRefusals").path("invalid_signature").asLong >= 2, status.text)
  }

  test("each action's missing fields are refused with the original's literal messages, and nothing is relayed") {
    val p = player()
    for (body, message) <- Vector(
        Json.obj("action" -> "grant_bonus", "player_id" -> p)                -> "player_id and offer_id required",
        Json.obj("action" -> "grant_bonus", "offer_id" -> "o-1")             -> "player_id and offer_id required",
        Json.obj("action" -> "send_message", "delivery_id" -> "d-1")         -> "player_id required",
        Json.obj("action" -> "send_message", "player_id" -> p)               -> "delivery_id_required",
        Json.obj("action" -> "send_message", "player_id" -> p, "delivery_id" -> " ") -> "delivery_id_required",
        Json.obj("action" -> "target_offer", "player_id" -> p)               -> "player_id and role required",
        Json.obj("action" -> "target_offer", "role" -> "welcome")            -> "player_id and role required"
      ) do
      val r = post(body)
      assertEquals((r.status, r.str("error")), (422, message), r.text)
    nothingRelayed()
  }

  test("grant_bonus relays the offer, role and currency and passes every answer of rewards through; the amount is ignored") {
    val p = player()
    val answers = Vector(
      Answer(200, """{"ok":true,"bonusAmount":"500.00"}"""),
      Answer(200, """{"ok":true,"grantId":"g-1","providerStatus":"active"}"""),
      Answer(200, """{"ok":true,"alreadyProcessed":true}"""),
      Answer(422, """{"error":"offer_not_active"}"""),
      Answer(422, """{"error":"SUSPECTED_DUPLICATE"}"""),
      Answer(503, """{"error":"ELIGIBILITY_CHECK_UNAVAILABLE"}"""),
      Answer(502, """{"error":"provider_rejected"}""")
    )
    for a <- answers do
      kit.rewards.answer(p, "award", a)
      val r = post(Json.obj("action" -> "grant_bonus", "player_id" -> p, "offer_id" -> "o-1", "role" -> "welcome",
        "currency" -> "EUR", "amount" -> 99, "delivery_id" -> "d-1"))
      assertEquals((r.status, Json.parse(r.text)), (a.status, Json.parse(a.body)))
    val relayed = kit.rewards.calls("award", p).map(_.json.get)
    assertEquals(relayed.size, answers.size)
    assertEquals(Json.render(relayed.head), """{"offerId":"o-1","role":"welcome","currency":"EUR"}""")
  }

  test("send_message relays a marketing item under the delivery id, drops campaign_id and reward_id, and passes players' answers through") {
    val p = player()
    for a <- Vector(Answer(200, """{"ok":true}"""), Answer(200, """{"ok":true,"alreadyProcessed":true}"""),
        Answer(422, """{"error":"marketing_suppressed"}"""), Answer(422, """{"error":"account_closed"}"""),
        Answer(422, """{"error":"unknown_merge_tag","tag":"first_name"}"""), Answer(404, """{"error":"player_not_found"}""")) do
      kit.players.answer(p, a)
      val r = post(Json.obj("action" -> "send_message", "player_id" -> p, "delivery_id" -> "d-7", "title" -> "Hello",
        "body" -> "Come back", "campaign_id" -> "c-1", "reward_id" -> "r-1"))
      assertEquals((r.status, Json.parse(r.text)), (a.status, Json.parse(a.body)))
    val sent = kit.players.messages(p).map(_.json.get)
    assertEquals(Json.render(sent.head), """{"deliveryId":"d-7","category":"marketing","subject":"Hello","body":"Come back"}""")
  }

  test("target_offer relays the role and the context and passes rewards' answers through") {
    val p = player()
    for a <- Vector(Answer(200, """{"ok":true}"""), Answer(422, """{"error":"free_spins_offer_not_targetable"}"""),
        Answer(422, """{"error":"role_not_found"}"""), Answer(422, """{"error":"target_failed"}""")) do
      kit.rewards.answer(p, "target", a)
      val r = post(Json.obj("action" -> "target_offer", "player_id" -> p, "role" -> "reactivation", "context" -> Json.obj("why" -> "lapsed")))
      assertEquals((r.status, Json.parse(r.text)), (a.status, Json.parse(a.body)))
    assertEquals(Json.render(kit.rewards.calls("target", p).head.json.get), """{"role":"reactivation","context":{"why":"lapsed"}}""")
  }

  test("a neighbour that does not answer is 503 service_unavailable naming it") {
    val p = player()
    kit.rewards.stop()
    try
      val r = post(Json.obj("action" -> "grant_bonus", "player_id" -> p, "offer_id" -> "o-1"))
      assertEquals((r.status, r.str("error"), r.str("service")), (503, "service_unavailable", kit.settings.rewards), r.text)
    finally kit.rewards.start()
  }

  test("the webhook steps of the rewards recordings answer what the original answered, rewards answering as recorded") {
    val dir = Path.of("../../tests/port/rewards")
    val steps = Files.list(dir).iterator.asScala.toVector.filter(_.toString.endsWith(".json")).sortBy(_.toString).flatMap { f =>
      val rec = Json.parse(Files.readString(f)).get
      rec.path("steps").elements.asScala.toVector.filter(_.path("request").path("path").asText.contains("/customerio/webhook/"))
        .map(s => f.getFileName.toString -> s)
    }
    assert(steps.size >= 7, s"${steps.size} webhook steps")
    for (file, step) <- steps do
      val p    = player()
      val text = Json.render(step.path("request").path("body")).replaceAll("""\{[A-Za-z0-9]+\}""", p)
      val want = step.path("response")
      val route = Json.parse(text).get.path("action").asText match
        case "target_offer" => "target"
        case _              => "award"
      kit.rewards.answer(p, route, Answer(want.path("status").asInt, Json.render(want.path("body"))))
      val r = post(text)
      assertEquals((r.status, Json.parse(r.text)), (want.path("status").asInt, Some(want.path("body"))), s"$file: ${r.text}")
  }
