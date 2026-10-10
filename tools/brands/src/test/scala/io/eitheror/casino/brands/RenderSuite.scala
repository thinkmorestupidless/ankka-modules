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
  }

  test("a shared service trusts the operators' realm only") {
    val shared = Render.plan(Fixtures.load("shared-payments"), None)
    val env = shared.descriptors.head.service.env.flatMap(e => e.value.map(e.name -> _)).toMap
    assertEquals(env("ANKKA_AUTH_ISSUERS"), "staff")
    assert(!env.contains("CASINO_BRAND"))
  }

  test("a secret reference renders as a secretKeyRef, never a value") {
    val st8 = plan.descriptors.find(_.name == "game-provider").get.service.env.find(_.name == "ST8_SIGNING_KEY").get
    assertEquals(st8.value, None)
    assertEquals(st8.secretKeyRef.map(r => r.name -> r.key), Some("game-provider-st8" -> "SIGNING_KEY"))
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
