package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.modules.ModuleManifest.{ContractView, Refusal}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * features/brands/modules.feature (spec 021) against the modules tool, the release's publish loop
 * with the fake vendor plugin, and an in-memory control plane.
 */
class ModulesFeatures extends GherkinSuite("features/brands/modules.feature"):


  private val root = Fixtures.root
  private lazy val view = ContractView.fromTree(root)

  private var refusals: Vector[Refusal]   = Vector.empty
  private var entitled: Map[String, Set[String]] = Map.empty

  private def variant(name: String): Path = Path.of(getClass.getResource(s"/manifests/$name.json").toURI)

  // ── Background ─────────────────────────────────────────────────────────────

  Given("an installation whose shared payments, programme and audit are running") { () =>
    refusals = Vector.empty; entitled = Map.empty
  }

  Given("a brand {string} provisioned by the vendor with an entitlement to the module {string}") { (slug: String, module: String) =>
    entitled = entitled.updated(slug, Set(module))
    brandValues = brandValues.updated(slug, brandOf(slug))
    installationName = "dev-kind"
    agents = Map.empty
    installation = InMemoryControlPlane(hasGrants = true)
  }

  // ── the brand side: values, the agent (US1, US3) ───────────────────────────

  private lazy val manifests = ModuleManifest.validateTree(root, view)._1
  private lazy val keys      = Keys.load(root)
  private lazy val goldvibe  = Values.load(root.resolve("brands/goldvibe/values.json"), Main.valuesView(root)).fold(rs => fail(rs.mkString), identity)

  /** goldvibe's values for another slug, its secrets named after that slug as keys.md reads them. */
  private def brandOf(slug: String): Values =
    goldvibe.copy(slug = slug, name = slug, secrets = goldvibe.secrets.map(s => s.copy(name = s.name.replace("goldvibe", slug))))

  private var brandValues: Map[String, Values]    = Map.empty
  private var installationName: String             = "dev-kind"
  private var agents: Map[String, AgentModel]      = Map.empty
  private var lastRecord: Option[AgentModel.ModuleRecord] = None
  private var writesBefore: Int = 0
  /** One installation's control plane: every brand is a project on it, so "nothing to any other brand" can be false. */
  private var installation: InMemoryControlPlane = InMemoryControlPlane(hasGrants = true)

  /** The brand's project as values push and secrets leave it, and its agent; made once per brand per scenario. */
  private def agent(slug: String): AgentModel =
    agents.getOrElse(slug, {
      val v    = brandValues(slug)
      val inst = Installation.load(root, installationName).toOption
      val cp   = installation
      cp.createProject(slug, slug, "eitheror")
      val secrets = keys.rows.filter(_.secret).filter(r => v.secretFor(r).isDefined).map(_.key -> "test").toMap
      cp.setSecret(slug, BrandRender.TenantValues, Values.all(v, slug, inst) ++ secrets)
      val a = AgentModel(cp, slug, manifests ++ testManifests, inst)
      agents = agents.updated(slug, a)
      a
    })

  /** Manifests the tree does not carry but a scenario needs (the predictions modules are spec 004 of ankka-predictions'). */
  private lazy val testManifests: Vector[ModuleManifest] =
    Vector("predictions").map(n => ModuleManifest.load(variant(n)).fold(rs => fail(rs.mkString("\n")), identity))

  When("the agent applies the brand's entitlements") { () =>
    val a = agent("goldvibe")
    writesBefore = a.cp.writes.size
    entitled("goldvibe").toVector.sortBy(m => if m == "core" then 0 else 1).foreach(m => lastRecord = Some(a.deliver(m, "0.3.0", "the vendor")))
  }

  When("eitheror staff enable the module {string} for brand {string}") { (module: String, slug: String) =>
    val a = agent(slug)
    if !a.entity.get("core").exists(_.status == AgentModel.Enabled) && module != "core" then a.deliver("core", "0.3.0", "the vendor")
    writesBefore = a.cp.writes.size
    entitled = entitled.updated(slug, entitled.getOrElse(slug, Set.empty) + module)
    lastRecord = Some(a.deliver(module, "0.3.0", "staff:eitheror"))
  }

  Given("the values of brand {string} lack a key the module {string} names") { (slug: String, module: String) =>
    val own = manifests.find(_.name == module).get.configuration.map(_.key).filter(k => keys.named(k).exists(_.module == module))
    val drop = own.filterNot(k => keys.named(k).exists(_.secret)).head
    brandValues = brandValues.updated(slug, brandValues(slug).copy(values = brandValues(slug).values - drop))
    missingKey = drop
  }
  private var missingKey = ""

  Then("nothing of the module {string} runs at brand {string}") { (module: String, slug: String) =>
    val a = agent(slug)
    val services = manifests.find(_.name == module).get.services.map(_.name)
    assert(!services.exists(a.cp.servicesBy.get(slug).getOrElse(Map.empty).contains), a.cp.writes.mkString("\n"))
    assertEquals(a.entity(module).status, AgentModel.Failed)
    // render says the same before anything runs (FR-005): the module is held for the key.
    val plan = BrandRender.plan(brandValues(slug), keys, manifests, Vector(module), Map.empty, Installation.load(root, installationName).toOption).toOption.get
    assertEquals(plan.missing, Vector(module -> missingKey))
  }

  Then("the brand's module entity names the missing key") { () =>
    val r = lastRecord.get
    assert(r.missing.contains(missingKey), r.toString)
  }

  Given("an installation that is not fit for real money") { () =>
    installationName = "dev-kind"
    assert(!Readiness.fit(Readiness.conditions(Installation.load(root, installationName).toOption.get)))
  }

  Given("the values of brand {string} mark it for real money") { (slug: String) =>
    brandValues = brandValues.updated(slug, brandValues(slug).copy(realMoney = true))
  }

  Then("nothing is applied, naming the condition the installation fails") { () =>
    val a = agent("goldvibe")
    val r = lastRecord.get
    assertEquals(r.status, AgentModel.Unfit)
    assert(r.reason.exists(_.contains("backups.target")), r.toString)
    assertEquals(a.cp.writes.drop(writesBefore).filterNot(_.startsWith("POST /projects/goldvibe/grants")), Vector.empty)
  }

  When("the modules tool validates a values file that names a version") { () =>
    valuesRefusals = Values.load(Path.of(getClass.getResource("/values/with-version.json").toURI), Main.valuesView(root)).fold(identity, _ => Vector.empty)
  }
  private var valuesRefusals: Vector[Refusal] = Vector.empty

  Then("the values file is refused, naming the entry") { () =>
    assert(valuesRefusals.exists(r => r.path == "version" && r.message.contains("'version' is the module's, not the brand's")), valuesRefusals.mkString("\n"))
  }

  // ── a module is described by one manifest and released by one tag (US2) ───

  When("the modules tool validates a manifest that names a service the contract does not list") { () =>
    refusals = ModuleManifest.validate(variant("unknown-service"), view).fold(identity, _ => Vector.empty)
  }

  Then("the manifest is refused, naming the unknown entry") { () =>
    assert(refusals.exists(r => r.path == "services[1].name" && r.message.contains("service 'jackpots' is not in the contract")), refusals.mkString("\n"))
  }

  When("the modules tool validates a manifest that carries a value for a configuration key") { () =>
    refusals = ModuleManifest.validate(variant("value-for-key"), view).fold(identity, _ => Vector.empty)
  }

  Then("the manifest is refused, naming the key") { () =>
    assert(refusals.exists(r => r.path == "configuration[0]" && r.message.contains("a value where a key is expected: 'game-provider-mode'")), refusals.mkString("\n"))
  }

  private var released: Vector[Path] = Vector.empty
  private var vendorCalls: Vector[String] = Vector.empty
  private var publishOutput: String = ""

  When("the release job runs for a tag") { () =>
    val out = Files.createTempDirectory("release")
    released = ReleaseRender.all(root, "1.4.0", out).fold(rs => fail(rs.mkString("\n")), identity)
    val log = out.resolve("vendor.log")
    val pb = ProcessBuilder("bash", root.resolve("scripts/release-publish.sh").toString, out.toString, "v1.4.0")
    pb.redirectErrorStream(true)
    pb.environment().put("ANKKA_VENDOR_FAKE_LOG", log.toString)
    pb.environment().put("PATH", s"${root.resolve("tests/fakes")}:${sys.env("PATH")}")
    val p = pb.start()
    publishOutput = String(p.getInputStream.readAllBytes())
    assertEquals(p.waitFor(), 0, publishOutput)
    vendorCalls = Files.readAllLines(log).asScala.toVector
  }

  Then("every module is published to the vendor at the tag's version") { () =>
    val modules = ModuleManifest.treeManifests(root).map(_.getParent.getFileName.toString).toSet
    assertEquals(vendorCalls.filter(_.startsWith("modules publish")).map(c => Path.of(c.split(' ')(2)).getFileName.toString.stripSuffix(".json")).toSet, modules)
    assert(vendorCalls.forall(_.endsWith("--version 1.4.0")), vendorCalls.mkString("\n"))
    released.foreach { f =>
      val images = ModuleManifest.load(f).toOption.get.services.map(_.image)
      assert(images.forall(_.endsWith(":1.4.0")), images.mkString(", "))
    }
  }

  Then("no channel is moved") { () =>
    assert(!vendorCalls.exists(_.startsWith("channels")), vendorCalls.mkString("\n"))
  }

  // ── a brand runs the core and the modules it is entitled to (US1) ───────────

  private def servicesOf(module: String): Vector[String] =
    (manifests ++ testManifests).find(_.name == module).getOrElse(fail(s"no module $module")).services.map(_.name)

  private def running(slug: String): Set[String] = installation.servicesBy.get(slug).map(_.keySet.toSet).getOrElse(Set.empty)

  /** The routes a service serves, by the contract's list and the service specs' route contracts. */
  private lazy val routes: Vector[(String, String, String)] =
    ModuleManifest.ContractView.contractRoutes(root).toVector ++ {
      // spec 003's route contract, the game routes the contract's list does not carry
      val gameRoute = """\|\s*(GET|POST|PUT|DELETE)\s*\|\s*`([^`]+)`.*""".r
      Files.readAllLines(root.resolve("specs/003-game-provider/contracts/routes.md")).asScala.toVector.collect {
        case gameRoute(method, path) if path.startsWith("/v1/casino/") => ("game-provider", method, path)
      }
    }

  private def served(slug: String): Vector[(String, String, String)] = routes.filter(r => running(slug).contains(r._1))

  Then("brand {string} runs the services of the module {string} and no other") { (slug: String, module: String) =>
    assertEquals(running(slug), servicesOf(module).toSet)
    assertEquals(agent(slug).entity.values.filter(_.status == AgentModel.Enabled).map(_.name).toVector, Vector(module))
  }

  Then("a player can register, deposit and withdraw at brand {string}") { (slug: String) =>
    // register: the players service (spec 008 owns the route, which the contract's list does not carry yet)
    assert(running(slug).contains("players"), running(slug).toString)
    val s = served(slug)
    assert(s.exists((svc, m, p) => svc == "wallet" && m == "POST" && p.endsWith("/deposits")), s.mkString("\n"))
    assert(s.exists((svc, m, p) => svc == "payouts" && m == "POST" && p.contains("/withdrawals/")), s.mkString("\n"))
  }

  Then("no game route and no market route is served at brand {string}") { (slug: String) =>
    assert(routes.exists(_._1 == "game-provider"), "the game routes were read")
    assertEquals(served(slug).filter(_._1 == "game-provider"), Vector.empty)
    assertEquals(running(slug).intersect((servicesOf("casino") ++ servicesOf("predictions")).toSet), Set.empty)
  }

  Then("no game route is served at brand {string}") { (slug: String) =>
    assertEquals(served(slug).filter(_._1 == "game-provider"), Vector.empty)
    assertEquals(running(slug).intersect(servicesOf("casino").toSet), Set.empty)
  }

  Then("brand {string} serves market routes") { (slug: String) =>
    assertEquals(lastRecord.map(_.status), Some(AgentModel.Enabled), lastRecord.toString)
    assert(servicesOf("predictions").toSet.subsetOf(running(slug)), running(slug).toString)
  }

  private var otherBefore: Option[(String, Map[String, Vector[String]], Vector[String], Vector[String])] = None

  private def snapshot(slug: String) =
    (slug,
      installation.servicesBy.get(slug).map(_.view.mapValues(_.map(_.service.image)).toMap).getOrElse(Map.empty),
      installation.grants(slug).getOrElse(Vector.empty).map(g => s"${g.principal} ${g.target}"),
      installation.topics(slug).map(_.name))

  Given("a brand {string} running the module {string}") { (slug: String, module: String) =>
    brandValues = brandValues.updated(slug, brandOf(slug))
    val a = agent(slug)
    assertEquals(a.deliver(module, "0.3.0", "the vendor").status, AgentModel.Enabled)
    otherBefore = Some(snapshot(slug))
  }

  Then("brand {string} runs the services of the module {string} beside the core") { (slug: String, module: String) =>
    assertEquals(running(slug), (servicesOf("core") ++ servicesOf(module)).toSet)
  }

  Then("what the module {string} allows the core and the shared payments, programme and audit is in force in brand {string}") {
    (module: String, slug: String) =>
      val declared = Vector("core", module).flatMap(m => manifests.find(_.name == m).get.grantsMade).collect {
        case ModuleManifest.MadeGrant(ModuleManifest.Principal.Literal(p), t) => s"$p ${BrandRender.target(t)}"
      }
      // casino declares no grant of its own today: what is in force is exactly the core's and casino's together
      assertEquals(installation.grants(slug).get.map(g => s"${g.principal} ${g.target}").toSet, declared.toSet)
      assert(agent(slug).entity(module).status == AgentModel.Enabled)
  }

  Then("brand {string} is unchanged") { (slug: String) =>
    assertEquals(Some(snapshot(slug)), otherBefore)
    assert(!installation.writes.drop(writesBefore).exists(_.contains(s"/$slug")), installation.writes.drop(writesBefore).mkString("\n"))
  }

  Given("brand {string} running the module {string}") { (slug: String, module: String) =>
    val a = agent(slug)
    assertEquals(a.deliver("core", "0.3.0", "the vendor").status, AgentModel.Enabled)
    assertEquals(a.deliver(module, "0.3.0", "staff:eitheror").status, AgentModel.Enabled)
  }

  When("eitheror staff disable the module {string} for brand {string}") { (module: String, slug: String) =>
    lastRecord = Some(agent(slug).disable(module, "staff:eitheror").fold(e => fail(e), identity))
  }

  Then("the services of the module {string} are removed from brand {string}") { (module: String, slug: String) =>
    assertEquals(running(slug).intersect(servicesOf(module).toSet), Set.empty)
    assertEquals(running(slug), servicesOf("core").toSet)
  }

  Then("the data of the module {string} is kept for the retention its manifest names") { (module: String) =>
    val m = manifests.find(_.name == module).get
    assertEquals(lastRecord.flatMap(_.dataKeptUntil), Some(java.time.Instant.parse("2026-10-22T12:00:00Z").plus(java.time.Duration.ofDays(AgentModel.days(m.retention)))))
    // the topics stay declared: removing a module deletes services, never data
    assert(m.topics.map(_.name).forall(installation.topics("goldvibe").map(_.name).contains))
  }

  // ── who sees and who changes a brand's modules (US4) ───────────────────────
  //
  // The vendor's console itself is proven in ankka-vendor (its quickstart, step 5): here the console's
  // act is the entitlement it delivers to the agent, and what it shows is the agent's module entity.

  Given("an operator of brand {string}") { (slug: String) =>
    val a = agent(slug)
    a.deliver("core", "0.3.0", "the vendor")
    a.deliver("casino", "0.3.0", "staff:eitheror")
  }

  private lazy val moduleRoutes = ModuleManifest.ContractView.contractRoutes(root).toVector.filter(_._3.contains("/modules"))

  When("the operator opens the modules page of the backoffice") { () =>
    // the page reads players' GET …/modules, an operator route that passes the agent's module entity through (spec 008)
    assert(moduleRoutes.contains(("players", "GET", "/v1/casino/brands/{brand}/modules")), moduleRoutes.mkString("\n"))
  }

  Then("the operator sees each module of brand {string} with its version, channel and the state of its services") { (slug: String) =>
    val entity = agent(slug).entity
    assertEquals(entity.keys.toVector, Vector("core", "casino"))
    entity.values.foreach { r =>
      assertEquals(r.version, Some("0.3.0"), r.name)
      assert(r.channel.isDefined, r.name)
      assertEquals(r.services.keySet, servicesOf(r.name).toSet, r.name)
      assert(r.services.values.forall(_ == "Ready"), r.toString)
    }
  }

  Then("the operator cannot enable a module") { () =>
    val writes = moduleRoutes.filterNot((_, m, p) => m == "GET" || (m == "POST" && p.endsWith("/modules/requests")))
    assertEquals(writes, Vector.empty, "no route on the installation enables, disables, pins or moves a module (FR-013)")
    assert(moduleRoutes.exists(_._3.endsWith("/modules/requests")), "the operator can only ask")
  }

  When("eitheror staff enable the module {string} for brand {string} from the vendor's console") { (module: String, slug: String) =>
    val a = agent(slug)
    a.deliver("core", "0.3.0", "the vendor")
    lastRecord = Some(a.deliver(module, "0.3.0", "staff:eitheror"))
  }

  Then("the module {string} is enabled for brand {string}") { (module: String, slug: String) =>
    assertEquals(agent(slug).entity(module).status, AgentModel.Enabled)
  }

  /** What would name a player, a wallet or a configured value. */
  private val Denied = Vector("player", "wallet", "balance", "amount", "email", "value", "secret", "iban", "address")

  Then("the vendor's console shows no player, wallet or configured value of any brand") { () =>
    val a = agent("goldvibe")
    def fields(p: Product) = p.productElementNames.toVector.map(_.toLowerCase)
    val shown = (a.entitlements.values.toVector ++ a.entity.values.toVector).flatMap(fields).distinct
    assert(shown.nonEmpty)
    assertEquals(shown.filter(f => Denied.exists(f.contains)), Vector.empty, shown.mkString(", "))
    // and no value of the brand's crosses in a field that is allowed: no text shown equals a tenant-values entry
    def texts(x: Any): Vector[String] = x match
      case s: String       => Vector(s)
      case Some(v)         => texts(v)
      case m: Map[?, ?]    => m.toVector.flatMap((k, v) => texts(k) ++ texts(v))
      case p: Product      => p.productIterator.toVector.flatMap(texts)
      case _               => Vector.empty
    val shownTexts = (a.entitlements.values.toVector ++ a.entity.values.toVector).flatMap(texts).toSet
    // `casino-modules` is the list of the modules the brand runs, names the vendor holds already: not the brand's.
    assertEquals((a.tenantValues - "casino-modules").values.toSet.intersect(shownTexts), Set.empty[String])
  }

  Then("a fact naming the module {string}, the change, the version and the actor is written on the brand's platform modules topic") { (module: String) =>
    import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
    import io.eitheror.casino.contract.facts.ModuleChange
    val fact = agent("goldvibe").facts.filter(_.module == module).lastOption.getOrElse(fail(s"no fact for $module"))
    assertEquals((fact.change, fact.version, fact.actor), ("enabled", Some("0.3.0"), "staff:eitheror"))
    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    val schema = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
      .getSchema(Files.readString(root.resolve("contract/schemas/platform.modules.v1.json")))
    val problems = schema.validate(mapper.readTree(writeToString(fact))).asScala.toVector
    assertEquals(problems.map(_.getMessage), Vector.empty)
    assert(schema.validate(mapper.readTree(writeToString(fact.copy(change = "moved")))).asScala.nonEmpty, "the schema refuses a change it does not list")
  }

