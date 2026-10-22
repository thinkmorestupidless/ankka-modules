package io.eitheror.casino.modules

import AgentModel.*

/** The modules applied in the vendor's agent's order (spec 021 T021), over the tree's manifests. */
class AgentModelSuite extends munit.FunSuite:

  private val root      = Fixtures.root
  private val manifests = ModuleManifest.validateTree(root, ModuleManifest.ContractView.fromTree(root))._1
  private val keys      = Keys.load(root)
  private val values    = Values.load(root.resolve("brands/goldvibe/values.json"), Main.valuesView(root)).toOption.get

  /** goldvibe's project with its values pushed and its secrets set, as `values push` and `secrets` leave it. */
  private def brand(v: Values = values, installation: String = "dev-kind"): AgentModel =
    val inst = Installation.load(root, installation).toOption
    val cp   = InMemoryControlPlane(hasGrants = true)
    cp.createProject("goldvibe", "goldvibe", "eitheror")
    val secrets = keys.rows.filter(_.secret).filter(r => v.secretFor(r).isDefined).map(_.key -> "test").toMap
    cp.setSecret("goldvibe", BrandRender.TenantValues, Values.all(v, "goldvibe", inst) ++ secrets)
    AgentModel(cp, "goldvibe", manifests, inst)

  test("core then casino: topics, then services in manifest order, then grants verified; one enabled fact each") {
    val agent = brand()
    assertEquals(agent.deliver("core", "0.3.0", "staff").status, Enabled)
    assertEquals(agent.deliver("casino", "0.3.0", "staff").status, Enabled)
    val writes = agent.cp.writes.filterNot(_.startsWith("POST /projects/goldvibe/grants"))
    val firstService = writes.indexWhere(_.startsWith("PUT /services/"))
    val lastCoreTopic = writes.lastIndexWhere(w => w.contains("/topics/") && !w.contains("casino.rounds") && !w.contains("casino.rewards"))
    assert(lastCoreTopic < firstService, writes.mkString("\n"))
    assertEquals(writes.filter(_.startsWith("PUT /services/")).map(_.split('/').last),
      Vector("wallet", "players", "kyc", "responsible-gambling", "payouts", "attribution", "game-provider", "rewards"))
    assertEquals(agent.facts.map(f => f.module -> f.change).toVector, Vector("core" -> "enabled", "casino" -> "enabled"))
    assertEquals(agent.cp.servicesBy("goldvibe")("game-provider").last.service.image, "europe-west1-docker.pkg.dev/eitheror/casino/game-provider:0.3.0")
  }

  test("a module whose dependency is not enabled fails naming it, and applies nothing") {
    val agent = brand()
    val r = agent.deliver("casino", "0.3.0", "staff")
    assertEquals(r.status, Failed)
    assertEquals(r.reason, Some("dependency core is not enabled"))
    assert(!agent.cp.writes.exists(_.startsWith("PUT /services/")))
  }

  test("a missing key fails the module naming the first and recording every one, before any topic or service") {
    val agent = brand(values.copy(values = values.values - "game-provider-mode" - "st8-operator-id"))
    agent.deliver("core", "0.3.0", "staff")
    val before = agent.cp.writes
    val r = agent.deliver("casino", "0.3.0", "staff")
    assertEquals(r.status, Failed)
    assertEquals(r.missing, Vector("game-provider-mode", "st8-operator-id"))
    assertEquals(r.reason, Some("missing value game-provider-mode"))
    assertEquals(agent.cp.writes.drop(before.size).filterNot(_.startsWith("POST /projects/goldvibe/grants")), Vector.empty)
    assertEquals(agent.facts.last.change, "failed")
  }

  test("a real-money brand on dev-kind is unfit, naming the fact, and nothing of the core is applied") {
    val agent = brand(values.copy(realMoney = true))
    val before = agent.cp.writes.size
    val r = agent.deliver("core", "0.3.0", "staff")
    assertEquals(r.status, Unfit)
    assert(r.reason.exists(_.contains("backups.target")), r.reason.toString)
    assertEquals(agent.cp.writes.drop(before).filterNot(_.startsWith("POST /projects/goldvibe/grants")), Vector.empty)
  }

  test("disabling a dependency is refused naming the dependent; disabling casino removes its services in reverse and keeps its data 30 days") {
    val agent = brand()
    agent.deliver("core", "0.3.0", "staff"); agent.deliver("casino", "0.3.0", "staff")
    assertEquals(agent.disable("core", "staff").left.toOption, Some("module 'casino' depends on 'core'"))
    val r = agent.disable("casino", "staff").toOption.get
    assertEquals(agent.cp.writes.filter(_.startsWith("DELETE")), Vector("DELETE /services/goldvibe/rewards", "DELETE /services/goldvibe/game-provider"))
    assertEquals(r.dataKeptUntil, Some(java.time.Instant.parse("2026-11-21T12:00:00Z")))
    assert(agent.cp.servicesBy("goldvibe").contains("wallet"))
  }

  test("a grant the hub has not made fails the module naming it") {
    val agent = brand()
    agent.entitlements("core") = Entitlement("core", "0.3.0", None, ended = false)
    val r = agent.apply("core", "staff")
    assertEquals(r.status, Failed)
    assert(r.reason.exists(_.startsWith("grant route:wallet POST")), r.reason.toString)
  }
