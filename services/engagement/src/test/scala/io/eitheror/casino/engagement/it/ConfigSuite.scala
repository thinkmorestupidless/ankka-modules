package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.config.{ConfigChange, Configuration, CrmConfig, Secrets, Seed}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `/v1/casino/crm-config/{brand}` (US7; FR-004, FR-005; contracts/routes.md): the round trip with every trigger and
 * masked keys; the keys in the secret store and none in the entity; a masked value keeping the stored one;
 * `invalid_config`; the audit record naming keys, never values; and the seed's thirty-two pairs at `_seed_<slug>`.
 */
class ConfigSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeAll(): Unit = kit.reset()

  override def beforeEach(context: BeforeEach): Unit =
    kit.configure(): Unit
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)

  override def afterAll(): Unit = kit.configure(): Unit

  private def path(rest: String = "") = s"/v1/casino/crm-config/${kit.slug}$rest"
  private def get()                   = kit.request("GET", path(), None, kit.asOperator())
  private def put(body: String)       = kit.request("PUT", path(), Some(body), kit.asOperator())

  test("the round trip: every trigger present, the keys masked; the keys in the secret store and none in the entity") {
    val body = Json.obj("siteId" -> FakeCrm.SiteId, "region" -> "EU", "fromEmail" -> s"crm@${kit.slug}.example",
      "trackApiKey" -> "track-new-1", "appApiKey" -> "app-new-22", "webhookSigningKey" -> "sig-new-333",
      "triggers" -> Json.obj("DepositConfirmed" -> Json.obj("transactionalId" -> "17"), "PaymentFailed" -> Json.obj("transactionalId" -> "")))
    val r = put(Json.render(body))
    assertEquals(r.status, 200, r.text)
    val shown = get().json
    assertEquals((shown.path("region").asText, shown.path("trackApiKey").asText, shown.path("appApiKey").asText,
      shown.path("webhookSigningKey").asText), ("eu", "tra********", "app*******", "sig********"))
    assertEquals(shown.path("triggers").fieldNames.asScala.toSet, Seed.triggers.toSet)
    assertEquals((shown.path("triggers").path("DepositConfirmed").path("transactionalId").asText,
      shown.path("triggers").path("PaymentFailed").path("transactionalId").asText), ("17", ""))
    assertEquals((kit.secrets.get(Secrets.track(kit.slug)), kit.secrets.get(Secrets.app(kit.slug)), kit.secrets.get(Secrets.signing(kit.slug))),
      (Some("track-new-1"), Some("app-new-22"), Some("sig-new-333")))
    val stored = kit.components.forKeyValueEntity(EntityId(kit.slug)).call(Configuration.get).invoke().toString
    for secret <- Vector("track-new-1", "app-new-22", "sig-new-333") do assert(!stored.contains(secret), stored)
    assert(kit.tables.values.flatten.forall(row => !row.contains("app-new-22")), "no table holds a key")
  }

  test("a masked value sent back keeps the stored key; an empty one clears it; one left out keeps it") {
    put(Json.render(Json.obj("appApiKey" -> "app-kept-1"))): Unit
    put(Json.render(get().json)): Unit
    assertEquals(kit.secrets.get(Secrets.app(kit.slug)), Some("app-kept-1"))
    put(Json.render(Json.obj("siteId" -> FakeCrm.SiteId))): Unit
    assertEquals(kit.secrets.get(Secrets.app(kit.slug)), Some("app-kept-1"))
    put(Json.render(Json.obj("appApiKey" -> ""))): Unit
    assertEquals((kit.secrets.get(Secrets.app(kit.slug)), get().str("appApiKey")), (None, ""))
  }

  test("invalid_config names the field; a body that is not an object is invalid_body") {
    for (body, field) <- Vector(
        Json.obj("siteId" -> 7)                                                       -> "siteId",
        Json.obj("fromEmail" -> "not-an-address")                                     -> "fromEmail",
        Json.obj("triggers" -> "all")                                                 -> "triggers",
        Json.obj("triggers" -> Json.obj("NoSuchTrigger" -> Json.obj("transactionalId" -> "1"))) -> "triggers.NoSuchTrigger",
        Json.obj("triggers" -> Json.obj("DepositConfirmed" -> Json.obj("transactionalId" -> 9)))  -> "triggers.DepositConfirmed.transactionalId"
      ) do
      val r = put(Json.render(body))
      assertEquals((r.status, r.str("error"), r.str("field")), (400, "invalid_config", field), r.text)
    assertEquals(put("[]").str("error"), "invalid_body")
  }

  test("a change is an audit record naming the keys and fields changed, never a value") {
    put(Json.render(Json.obj("trackApiKey" -> "track-audit-9", "fromEmail" -> s"audit@${kit.slug}.example"))): Unit
    val r = kit.eventually("the change audited")(kit.facts(f.PlatformAudit).collect {
      case a: f.AuditRecord if a.action == "engagement.config.changed" && a.actorId == "op-1" => a
    }.lastOption)
    assertEquals(r.changes.map(_.field).toSet, Set("fromEmail", "trackApiKey"))
    assert(!r.toString.contains("track-audit-9") && !r.toString.contains("audit@"), r.toString)
  }

  test("another brand is wrong_brand; an operator without the role is refused") {
    assertEquals(kit.request("GET", "/v1/casino/crm-config/another", None, kit.asOperator()).status, 404)
    assertEquals(kit.request("GET", path(), None, kit.asOperator(roles = "brand_viewer")).status, 403)
  }

  test("the seed: thirty-two identifies and tracks at _seed_<slug>, counted; seeding twice updates the same person") {
    val person = Seed.person(kit.slug)
    val first  = kit.request("POST", path("/seed"), None, kit.asOperator())
    assertEquals((first.status, first.json.path("seeded").asInt, first.str("person")), (200, 32, person), first.text)
    assertEquals((kit.crm.identifies(person).size, kit.crm.tracks(person).size), (32, 32))
    assertEquals(kit.crm.tracks(person).map(_.path("name").asText), Seed.events.map(_._2))
    val again = kit.request("POST", path("/seed"), None, kit.asOperator())
    assertEquals(again.json, first.json)
    assertEquals(kit.crm.entities.map(_.path("identifiers").path("id").asText).distinct, Vector(person))
  }

  test("the seed needs a site, and is crm_unavailable when Customer.io takes none of its calls") {
    kit.crm.fail(503, 64)
    assertEquals(kit.request("POST", path("/seed"), None, kit.asOperator()).str("error"), "crm_unavailable")
    kit.crm.reset()
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(Configuration.put).invoke(ConfigChange(CrmConfig(), "suite")): Unit
    val r = kit.request("POST", path("/seed"), None, kit.asOperator())
    assertEquals((r.status, r.str("error")), (422, "credentials_not_configured"), r.text)
  }
