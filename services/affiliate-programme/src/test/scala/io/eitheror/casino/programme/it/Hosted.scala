package io.eitheror.casino.programme.it

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.AttributionFact
import io.eitheror.casino.programme.api.Inbound
import io.eitheror.casino.programme.intake.FactInbox

import java.nio.charset.StandardCharsets.UTF_8
import java.util.HexFormat

/** What the hosting suites start from: a hosted brand added as an operator adds one, and its facts posted as its `attribution` posts them. */
object Hosted:
  final case class Added(slug: String, secret: String, reply: Reply)

  /** A hosted brand of the scenario's own, with a default CPA plan and a first manager, through `POST /v1/programme/brands`. */
  def add(kit: Kit, cpa: Long = 2_500, managerEmail: Option[String] = None): Added =
    val slug  = kit.fresh("h").toLowerCase
    val email = managerEmail.getOrElse(s"${kit.fresh("mgr")}@hosted.example")
    val r = kit.request("POST", "/v1/programme/brands", Some(
      s"""{"brandId":"$slug","origin":"hosted","name":"Hosted $slug","reportingZone":"Europe/Malta",
         |"settings":{"payoutCycleDay":5},"programme":{"terms":"Hosted terms","termsVersion":"1"},
         |"defaultPlan":{"planName":"Standard","planType":"cpa","cpaAmount":$cpa,"cpaCurrency":"EUR"},
         |"firstManager":{"email":"$email","name":"Hilda Host"}}""".stripMargin), kit.asPlatformAdmin())
    assert(r.status == 201, r.text)
    Added(slug, r.json.get("webhookSecret").asText, r)

  def json(fact: AttributionFact): String = writeToString(fact)(using AttributionFact.given_JsonValueCodec_AttributionFact)

  def signature(body: String, secret: String): String = s"sha256=${HexFormat.of().formatHex(Inbound.sign(body.getBytes(UTF_8), secret))}"

  /** A fact posted to the brand's webhook with spec 012's headers; each header can be made wrong. */
  def post(kit: Kit, slug: String, secret: String, fact: AttributionFact, signed: Option[String] = None, protocol: String = "1",
      factId: Option[String] = None, body: Option[String] = None): Reply =
    val text = body.getOrElse(json(fact))
    kit.request("POST", s"/v1/programme/inbound/$slug", Some(text), kit.fromInternet ++ Seq(
      "X-Attribution-Protocol"  -> protocol,
      "X-Attribution-Fact-Id"   -> factId.getOrElse(fact.factId),
      "X-Attribution-Kind"      -> "fact",
      "X-Attribution-Signature" -> signed.getOrElse(signature(text, secret))))

  def inbox(kit: Kit, slug: String, factId: String) =
    kit.components.forKeyValueEntity(FactInbox.entityId(slug, factId)).call(FactInbox.get).invoke()
