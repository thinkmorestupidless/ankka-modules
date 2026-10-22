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

  override protected def ranElsewhere: Map[String, String] = Map(
    "a brand runs the core alone until a module is enabled" -> "not yet bound: spec 021 T023",
    "enabling a module adds its services and grants to the brand and nothing to any other brand" -> "not yet bound: spec 021 T023",
    "a brand entitled to the prediction market and not the casino serves markets and no games" -> "not yet bound: spec 021 T023",
    "disabling a module removes its services and keeps its data for the module's retention" -> "not yet bound: spec 021 T023",
    "a brand operator sees their brand's modules and cannot enable one" -> "not yet bound: spec 021 T026",
    "eitheror staff enable a module from the vendor's console and see no player of any brand" -> "not yet bound: spec 021 T026",
    "a module's every change is a fact on the brand's platform topic" -> "not yet bound: spec 021 T026"
  )

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
    brandValues = brandValues.updated(slug, goldvibe.copy(slug = slug, name = slug))
    installationName = "dev-kind"
    agents = Map.empty
  }

  // ── the brand side: values, the agent (US1, US3) ───────────────────────────

  private lazy val manifests = ModuleManifest.validateTree(root, view)._1
  private lazy val keys      = Keys.load(root)
  private lazy val goldvibe  = Values.load(root.resolve("brands/goldvibe/values.json"), Main.valuesView(root)).fold(rs => fail(rs.mkString), identity)

  private var brandValues: Map[String, Values]    = Map.empty
  private var installationName: String             = "dev-kind"
  private var agents: Map[String, AgentModel]      = Map.empty
  private var lastRecord: Option[AgentModel.ModuleRecord] = None
  private var writesBefore: Int = 0

  /** The brand's project as values push and secrets leave it, and its agent; made once per brand per scenario. */
  private def agent(slug: String): AgentModel =
    agents.getOrElse(slug, {
      val v    = brandValues(slug)
      val inst = Installation.load(root, installationName).toOption
      val cp   = InMemoryControlPlane(hasGrants = true)
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
