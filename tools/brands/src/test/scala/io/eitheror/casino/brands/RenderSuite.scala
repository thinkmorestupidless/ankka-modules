package io.eitheror.casino.brands

import com.thinkmorestupidless.ankka.controlplane.api.ServiceSpec

class RenderSuite extends munit.FunSuite:

  private val brand = Fixtures.brand("goldvibe")
  private val plan  = Render.plan(brand, Some(Fixtures.devKind))

  private def env(service: String): Map[String, String] =
    plan.descriptors.find(_.name == service).get.service.env.flatMap(e => e.value.map(e.name -> _)).toMap

  test("one descriptor per service, at the image and runtime the manifest pins") {
    assertEquals(plan.descriptors.map(_.name), brand.services.map(_.name))
    plan.descriptors.foreach { d =>
      assertEquals(d.service.runtime, Some("0.11.0"))
      assert(d.service.image.endsWith(":0.1.0"))
      assertEquals(d.problems, Vector.empty, d.name)
    }
  }

  test("Scala services are embedded; the TypeScript ones are processes") {
    assertEquals(plan.descriptors.find(_.name == "wallet").get.service.hosting, ServiceSpec.Embedded)
    val shared = Render.plan(Fixtures.load("shared-payments"), None)
    assertEquals(shared.descriptors.find(_.name == "psp-gateway").get.service.hosting, ServiceSpec.Process)
  }

  test("only kyc and reporting get a bucket") {
    assertEquals(plan.descriptors.filter(_.service.provisionObjectStorage).map(_.name).toSet, Set("kyc", "reporting"))
  }

  test("a brand service trusts its own players' realm and the operators' realm, and knows its brand") {
    val wallet = env("wallet")
    assertEquals(wallet("ANKKA_AUTH_ISSUERS"), "player,staff")
    assert(wallet("ANKKA_AUTH_PLAYER_ISSUER").endsWith("/realms/casino-goldvibe"))
    assert(wallet("ANKKA_AUTH_STAFF_ISSUER").endsWith("/realms/eitheror"))
    assertEquals(wallet("ANKKA_AUTH_PLAYER_AUDIENCE"), "casino-goldvibe")
    assertEquals(wallet("CASINO_BRAND"), "goldvibe")
    assertEquals(wallet("CASINO_REAL_MONEY"), "false")
  }

  test("a service reads its issuers' keys from inside the cluster when the installation names where; the issuer stays public") {
    val pin = brand.services.head
    def envWith(i: Option[Installation]) = Render.descriptor(brand, pin, i).service.env.flatMap(e => e.value.map(e.name -> _)).toMap
    val inside = Fixtures.devKind.copy(settings = Fixtures.devKind.settings + ("KEYCLOAK_JWKS_BASE" -> "http://casino-keycloak.casino-identity.svc"))
    val keys   = envWith(Some(inside))
    assertEquals(keys("ANKKA_AUTH_STAFF_JWKS_URL"), "http://casino-keycloak.casino-identity.svc/realms/eitheror/protocol/openid-connect/certs")
    assertEquals(keys("ANKKA_AUTH_PLAYER_JWKS_URL"), "http://casino-keycloak.casino-identity.svc/realms/casino-goldvibe/protocol/openid-connect/certs")
    assertEquals(keys("ANKKA_AUTH_STAFF_ISSUER"), s"${brand.issuerBase.stripSuffix("/")}/realms/eitheror")
    val public = envWith(Some(Fixtures.devKind.copy(settings = Fixtures.devKind.settings - "KEYCLOAK_JWKS_BASE")))
    assertEquals(public("ANKKA_AUTH_STAFF_JWKS_URL"), s"${brand.issuerBase.stripSuffix("/")}/realms/eitheror/protocol/openid-connect/certs")
  }

  test("a shared service trusts the operators' realm only") {
    val shared = Render.plan(Fixtures.load("shared-payments"), None)
    val env = shared.descriptors.head.service.env.flatMap(e => e.value.map(e.name -> _)).toMap
    assertEquals(env("ANKKA_AUTH_ISSUERS"), "staff")
    assert(!env.contains("CASINO_BRAND"))
  }

  test("a secret reference renders as a secretKeyRef, never a value") {
    // goldvibe's manifest names no project secret since game-provider keeps its provider
    // configuration in its own secret store (spec 003 research R4), so the test names one.
    val withSecret = brand.copy(
      services = brand.services.map(s => if s.name == "engagement" then s.copy(env = s.env + ("SENDER_KEY" -> "secret:engagement-mail/KEY")) else s),
      secrets = brand.secrets :+ SecretRef("engagement-mail", Vector("KEY"))
    )
    val ref = Render.plan(withSecret, Some(Fixtures.devKind)).descriptors.find(_.name == "engagement").get.service.env.find(_.name == "SENDER_KEY").get
    assertEquals(ref.value, None)
    assertEquals(ref.secretKeyRef.map(r => r.name -> r.key), Some("engagement-mail" -> "KEY"))
  }

  test("ten topic declarations with their partitions; the status is told what ankka 0.11 cannot declare") {
    assertEquals(plan.topics.map(_.declaration.name).toSet.size, 10)
    plan.topics.foreach(t => assert(t.notes.exists(_.contains("lacks 043")), t.declaration.name))
  }

  test("a fact topic on an installation keeping facts for days is noted as below its intent") {
    val facts = plan.topics.find(_.declaration.name == "money.transactions").get
    assert(facts.notes.exists(_.startsWith("retention below intent")), facts.notes.mkString("; "))
    val push = plan.topics.find(_.declaration.name == "casino.push").get
    assert(!push.notes.exists(_.startsWith("retention below intent")), push.notes.mkString("; "))
  }

  test("a shared project's grants are made once per brand") {
    val shared = Render.plan(Fixtures.load("shared-payments"), None, Vector("goldvibe", "spinvibe"))
    assertEquals(shared.grantsMade.map(_.principal).toSet,
      Set("service:goldvibe/payouts", "service:goldvibe/players", "service:spinvibe/payouts", "service:spinvibe/players"))
  }
