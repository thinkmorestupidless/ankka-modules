package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{EnvVar, SecretKeyRef, ServiceDescriptor}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.descriptorCodec

import scala.jdk.CollectionConverters.*

/**
 * SC-001: rendering goldvibe's values against `core` and `casino` at the versions spec 001 pinned
 * yields what spec 001's tool produced for goldvibe (`spec-001-goldvibe/plan.json`), field by field,
 * but for the differences listed here, each of which a contract of spec 021 makes:
 *
 *  - E1: the three identity keys spec 001 did not render (`PLAYER_SITE_BASE`, `KEYCLOAK_ISSUER_BASE`,
 *    `KEYCLOAK_JWKS_BASE`) are named by every service (contracts/module-manifests.md);
 *  - the modules' own keys are new env: `KYC_MAX_UPLOAD_BYTES` on kyc, and on game-provider
 *    `ST8_OPERATOR_ID`, `ST8_API_KEY`, `GAME_PROVIDER_MODE`, and on rewards
 *    `REWARDS_CODE_DOCUMENT_URL` (spec 011 R13); a secret one is a `secretKeyRef` to `tenant-values`;
 *  - `SUPPORT_AGENT_ENABLED` is gone: `support` is a module and being entitled is the switch;
 *  - E2: the three `psp-gateway` routes and the erasure grant are not received by the core's manifest:
 *    `shared/payments/manifest.json` makes them per brand in local mode;
 *  - `platform.modules` is a new topic of the core, and `reasoner/audit` is granted to consume it;
 *  - `casino.push` is engagement's topic, and engagement, reporting and support-agent are modules of
 *    their own, rendered in the second case.
 */
class RenderSuite extends munit.FunSuite:

  private val mapper  = ObjectMapper()
  private val fixture = mapper.readTree(getClass.getResourceAsStream("/spec-001-goldvibe/plan.json"))
  private val keys    = Keys.load(Fixtures.root)
  private val values  = Values.load(Fixtures.root.resolve("brands/goldvibe/values.json"), Main.valuesView(Fixtures.root)).toOption.get
  private val modules = ModuleManifest.validateTree(Fixtures.root, ModuleManifest.ContractView.fromTree(Fixtures.root))._1
  private val at      = Map("core" -> "0.1.0", "casino" -> "0.1.0", "engagement" -> "0.1.0", "reporting" -> "0.1.0", "support" -> "0.1.0",
    "predictions" -> "0.1.0")

  private val spec001: Vector[ServiceDescriptor] =
    fixture.path("descriptors").elements().asScala.toVector.map(n => readFromString[ServiceDescriptor](n.toString))

  private val NewIdentity = Set("PLAYER_SITE_BASE", "KEYCLOAK_ISSUER_BASE", "KEYCLOAK_JWKS_BASE")
  private val PlayersSecrets = Set("REALM_ADMIN", "WEBHOOK_SECRET", "REALM_SECRET", "EMAIL_TOKEN", "LOOKUP_KEY", "MERCHANT_KEY").map("PLAYERS_" + _)
  private val NewOwnKeys  = Map(
    "kyc"           -> Set("KYC_MAX_UPLOAD_BYTES"),
    // spec 023 R13: the erasure targets the brand's modules add.
    "players"       -> (Set("KEYCLOAK_URL", "INGEST_URL", "ERASURE_TARGETS") ++ PlayersSecrets),
    // spec 026 R11: the provider names the brand runs.
    "game-provider" -> Set("ST8_OPERATOR_ID", "ST8_API_KEY", "GAME_PROVIDER_MODE", "CASINO_PROVIDERS"),
    // spec 011 R13: the programme's code document.
    "rewards"       -> Set("REWARDS_CODE_DOCUMENT_URL"),
    // spec 014 R12: the Customer.io secrets enter through the configuration route, not the manifest.
    "engagement"    -> Set(),
    // spec 017 R11, R8: the model provider, compaction and the judge are the service's settings.
    "support-agent" -> Set("MODEL_PROVIDER_KEY", "AGENT_MODEL_PROVIDER", "AGENT_COMPACTION_BYTES", "AGENT_COMPACTION_RECENT", "AGENT_JUDGE"),
    // spec 012 plan research R12, R7, R5, R3, R10, R15: attribution's own settings.
    "attribution"   -> Set("CASINO_REPORTING_ZONE", "CASINO_MODULES", "COUNTRY_HEADER", "DOCUMENT_TIMEOUT", "CLOSE_GRACE", "WEBHOOK_TIMEOUT"),
    // spec 015 research R14: reporting's zone, modules, grace and generation.
    "reporting"     -> Set("CASINO_REPORTING_ZONE", "CASINO_MODULES", "CLOSE_GRACE", "REPORTING_GENERATION")
  )

  /** Services that run on a later ankka than spec 001's 0.13.0, for the `Personal` they store. */
  private val NewRuntimes = Map("engagement" -> "0.16.0", "reporting" -> "0.16.0", "support-agent" -> "0.16.2")

  /**
   * Sizes spec 001 did not set: players and attribution outgrow `small`'s 512Mi on kind (spec 008 T077,
   * spec 012 T073; both OOM-killed).
   */
  private val NewSizes = Map("players" -> "medium", "attribution" -> "medium", "kyc" -> "medium")

  private def plan(named: Vector[String], v: Values = values) =
    BrandRender.plan(v, keys, modules, named, at, Some(Fixtures.devKind), routes = ModuleManifest.ContractView.contractRoutes(Fixtures.root))
      .fold(e => fail(e), identity)

  /** A descriptor with spec 021's listed additions taken out, and spec 001's dropped switch taken out of the other. */
  private def comparable(d: ServiceDescriptor, dropped: Set[String]): ServiceDescriptor =
    // kyc's bucket is exposed for its signed URLs (spec 009 R7), which spec 001 did not render.
    d.copy(service = d.service.copy(env = d.service.env.filterNot(e => dropped.contains(e.name)), exposeObjectStorage = false))

  private def sameAsSpec001(rendered: Vector[ServiceDescriptor]): Unit =
    val byName = spec001.map(d => d.name -> d).toMap
    rendered.foreach { d =>
      val before = byName.getOrElse(d.name, fail(s"${d.name} is not a service spec 001 rendered"))
      val resized = NewSizes.get(d.name).fold(d) { size =>
        assertEquals(d.service.resources.instanceType, size, d.name)
        d.copy(service = d.service.copy(resources = before.service.resources))
      }
      // engagement (spec 014) and reporting (spec 015 R2) run on ankka 0.16.0 for their `Personal`, and support-agent on
      // 0.16.2 for its sessions' data subject (spec 017 R2); spec 001 on 0.13.0.
      val runtime = NewRuntimes.get(d.name).fold(resized) { v =>
        assertEquals(resized.service.runtime, Some(v), d.name)
        resized.copy(service = resized.service.copy(runtime = before.service.runtime))
      }
      val now = comparable(runtime, NewIdentity ++ NewOwnKeys.getOrElse(d.name, Set.empty))
      assertEquals(now, comparable(before, Set("SUPPORT_AGENT_ENABLED")), d.name)
      assertEquals(d.service.env.map(_.name).filter(NewIdentity), Vector("PLAYER_SITE_BASE", "KEYCLOAK_ISSUER_BASE", "KEYCLOAK_JWKS_BASE"), d.name)
    }

  test("core and casino render spec 001's services for goldvibe field by field, but for the listed differences (SC-001)") {
    val p = plan(Vector("casino"))
    assertEquals(p.held, Vector.empty, p.missing.toString)
    assertEquals(p.modules.map(_.name), Vector("core", "casino"))
    assertEquals(p.services.map(_.name).toSet, spec001.map(_.name).toSet -- Set("engagement", "reporting", "support-agent"))
    sameAsSpec001(p.services)
    assertEquals(p.modules.flatMap(_.exposed).toSet, p.services.map(_.name).toSet, "spec 001 exposed every brand service")
  }

  test("kyc's bucket is provisioned and exposed, so the platform gives it ANKKA_S3_PUBLIC_ENDPOINT for signed URLs (spec 009 R7)") {
    val p = plan(Vector("casino"))
    val exposed = p.services.filter(_.service.exposeObjectStorage).map(_.name)
    assertEquals(exposed, Vector("kyc"))
    assert(p.services.find(_.name == "kyc").get.service.provisionObjectStorage)
  }

  test("the identity values are spec 001's, and a secret key is a secretKeyRef to tenant-values, never a value") {
    val p = plan(Vector("casino"))
    def env(s: String) = p.services.find(_.name == s).get.service.env
    assertEquals(env("wallet").find(_.name == "KEYCLOAK_JWKS_BASE").flatMap(_.value), Some("http://casino-keycloak.casino-identity.svc"))
    assertEquals(env("kyc").find(_.name == "KYC_MAX_UPLOAD_BYTES").flatMap(_.value), Some("5242880"))
    assertEquals(env("game-provider").find(_.name == "ST8_API_KEY").flatMap(_.secretKeyRef), Some(SecretKeyRef("tenant-values", "st8-api-key")))
    assertEquals(env("game-provider").find(_.name == "GAME_PROVIDER_MODE").flatMap(_.value), Some("demo"))
  }

  test("players (spec 008 T075): both issuers' ANKKA_AUTH_* variables, the realm and ingest addresses, and its six secrets as secretKeyRefs") {
    val env = plan(Vector("casino")).services.find(_.name == "players").get.service.env
    def value(n: String) = env.find(_.name == n).flatMap(_.value)
    assertEquals(value("ANKKA_AUTH_ISSUERS"), Some("player,staff"))
    assertEquals(value("ANKKA_AUTH_PLAYER_ISSUER"), Some("https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-goldvibe"))
    assertEquals(value("ANKKA_AUTH_PLAYER_JWKS_URL"), Some("http://casino-keycloak.casino-identity.svc/realms/casino-goldvibe/protocol/openid-connect/certs"))
    assertEquals(value("ANKKA_AUTH_STAFF_ISSUER"), Some("https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror"))
    assertEquals(value("ANKKA_AUTH_STAFF_AUDIENCE"), Some("eitheror-casino"))
    assertEquals(value("KEYCLOAK_URL"), Some("http://casino-keycloak.casino-identity.svc"))
    assertEquals(value("INGEST_URL"), Some("https://ingest-payments.127.0.0.1.sslip.io:8443"))
    PlayersSecrets.foreach { n =>
      val key = n.stripPrefix("PLAYERS_").toLowerCase.replace('_', '-')
      assertEquals(env.find(_.name == n), Some(EnvVar(n, secretKeyRef = Some(SecretKeyRef("tenant-values", s"players-$key")))), n)
    }
  }

  test("attribution reads the brand's reporting zone and the modules it runs, which default to UTC and the modules applied (spec 012)") {
    def env(p: BrandPlan, name: String) = p.services.find(_.name == "attribution").get.service.env.find(_.name == name).flatMap(_.value)
    val withCasino = plan(Vector("casino"))
    assertEquals(env(withCasino, "CASINO_REPORTING_ZONE"), Some("Africa/Johannesburg"))
    assertEquals(env(withCasino, "CASINO_MODULES"), Some("core,casino"))
    assertEquals(env(withCasino, "COUNTRY_HEADER"), Some("CF-IPCountry"))
    val coreOnly = plan(Vector.empty, values.copy(values = values.values - "reporting-zone"))
    assertEquals(env(coreOnly, "CASINO_REPORTING_ZONE"), Some("UTC"))
    assertEquals(env(coreOnly, "CASINO_MODULES"), Some("core"), "never empty: the platform refuses an empty entry")
    val written = plan(Vector("casino"), values.copy(values = values.values + ("casino-modules" -> "casino,engagement")))
    assertEquals(env(written, "CASINO_MODULES"), Some("casino,engagement"), "a brand's own value wins")
  }

  test("the topics are spec 001's for core and casino, less engagement's push, plus platform.modules") {
    val p = plan(Vector("casino"))
    val before = fixture.path("topics").elements().asScala.toVector.map(t => t.path("name").asText() -> (t.path("partitions").asInt(), t.path("retention").asText(), t.path("contract").asText())).toMap
    val now = p.modules.flatMap(_.topics).map(t => t.declaration.name -> (t.request.partitions, Manifest.show(t.declaration.retention), t.request.contract.map(_._1).getOrElse(""))).toMap
    assertEquals(now - "platform.modules", before - "casino.push")
    assertEquals(now("platform.modules"), (6, "as a fact topic", "platform.modules.v1"))
  }

  test("the grants made are spec 001's plus audit's consume of platform.modules; none is received by the core (E2)") {
    val p = plan(Vector("casino"))
    val before = fixture.path("grantsMade").elements().asScala.toVector.map(g => GrantRequest(g.path("principal").asText(), g.path("target").asText()))
    assertEquals(p.modules.flatMap(_.grantsMade), before :+ GrantRequest("service:reasoner/audit", "topic:platform.modules:consume"))
    assertEquals(p.modules.flatMap(_.grantsReceived), Vector.empty)
    val received = fixture.path("grantsReceived").elements().asScala.toVector.map(_.path("target").asText())
    assertEquals(received, Vector("route:psp-gateway POST /v1/payments/withdrawals", "route:psp-gateway GET /v1/payments/withdrawals/{requestId}",
      "route:psp-gateway GET /v1/payments/withdrawal-terminals", "erasure"), "what shared payments now makes per brand")
  }

  test("all five modules render spec 001's eleven services, the support switch gone, once the brand names the support secret") {
    val more = values.copy(secrets = values.secrets ++ Vector(SecretRef("goldvibe-model", Vector("API_KEY"))))
    val p = plan(Vector("casino", "engagement", "reporting", "support"), more)
    assertEquals(p.held, Vector.empty)
    assertEquals(p.services.map(_.name).toSet, spec001.map(_.name).toSet)
    sameAsSpec001(p.services)
    assert(!p.services.exists(_.service.env.exists(_.name == "SUPPORT_AGENT_ENABLED")))
  }

  test("without the secret support names, that module is held, naming the key; engagement names none (FR-009)") {
    val p = plan(Vector("casino", "engagement", "support"))
    assertEquals(p.modules.map(_.name), Vector("core", "casino", "engagement"))
    assertEquals(p.missing.toSet, Set("support" -> "model-provider-key"))
  }

  test("a brand entitled to predictions and not casino runs markets and exchange on the core, and no game-provider (spec 023 SC-004)") {
    val p = plan(Vector("predictions"))
    assertEquals(p.held, Vector.empty)
    assertEquals(p.modules.map(_.name), Vector("core", "predictions"))
    val names = p.services.map(_.name).toSet
    assert(Set("markets", "exchange").subsetOf(names), names.toString)
    assert(!names.contains("game-provider") && !names.contains("rewards"), names.toString)
    val exchange = p.services.find(_.name == "exchange").get.service.env.map(e => e.name -> e.value).toMap
    assertEquals(exchange.get("PREDICTIONS_GATES"), Some(Some("play-gate,may-bet")))
  }

  test("players erases at exchange when the brand runs predictions, and at core's own targets only when it does not (spec 023 R13)") {
    def targets(named: Vector[String]) =
      plan(named).services.find(_.name == "players").get.service.env.find(_.name == "ERASURE_TARGETS").flatMap(_.value)
    assertEquals(targets(Vector("predictions")), Some("exchange=/v1/casino/brands/goldvibe/exchange"))
    assertEquals(targets(Vector("casino")), Some("none"))
  }

  test("reporting renders its zone, the brand's modules, attribution's grace and its generation, and erases at players' ask (spec 015 R10, R14)") {
    val p         = plan(Vector("casino", "reporting"))
    val reporting = p.services.find(_.name == "reporting").get.service.env.map(e => e.name -> e.value).toMap
    val attribution = p.services.find(_.name == "attribution").get.service.env.map(e => e.name -> e.value).toMap
    assertEquals(reporting.get("CASINO_REPORTING_ZONE"), attribution.get("CASINO_REPORTING_ZONE"))
    assert(reporting.get("CASINO_REPORTING_ZONE").flatten.isDefined)
    assertEquals(reporting.get("CASINO_MODULES"), Some(Some("core,casino,reporting")))
    assertEquals(reporting.get("CLOSE_GRACE"), Some(Some("15m")))
    assertEquals(reporting.get("REPORTING_GENERATION"), Some(Some("1")))
    val targets = p.services.find(_.name == "players").get.service.env.find(_.name == "ERASURE_TARGETS").flatMap(_.value)
    assertEquals(targets, Some("reporting=/v1/casino/brands/goldvibe/reporting"))
  }

  test("reporting's grace is attribution's, so both close a day at the same instant (spec 015 R7)") {
    def grace(module: String, service: String) =
      modules.find(_.name == module).get.services.find(_.name == service).get.descriptor.path("env").elements().asScala
        .find(_.path("name").asText() == "CLOSE_GRACE").map(_.path("value").asText())
    assertEquals(grace("reporting", "reporting"), grace("core", "attribution"))
    assert(grace("reporting", "reporting").isDefined)
  }

  test("core comes first and a module after its dependencies; a module that does not exist is named") {
    assertEquals(BrandRender.ordered(Vector("casino"), modules).map(_.map(_.name)), Right(Vector("core", "casino")))
    assertEquals(BrandRender.ordered(Vector("jackpots"), modules).left.toOption, Some("no module 'jackpots'"))
  }

  test("the versions come from the channel when one is named") {
    val vendor = new Vendor:
      def channelVersion(module: String, channel: String) = Right(if channel == "stable" then "0.4.0" else "0.5.0")
      def setValues(tenant: String, entries: Map[String, String]) = Right(())
      def schema() = Left("not asked")
      def calls = Vector.empty
    assertEquals(Main.versions(Main.parse(Seq("--channel", "stable")), Vector("core", "casino"), () => vendor), Right(Map("core" -> "0.4.0", "casino" -> "0.4.0")))
    assertEquals(PluginVendor.channel(mapper.readTree("""{"channels":{"stable":"1.4.0"}}"""), "stable"), Some("1.4.0"))
    assertEquals(PluginVendor.channel(mapper.readTree("""{"channels":[{"name":"stable","version":"1.4.0"}]}"""), "stable"), Some("1.4.0"))
  }
