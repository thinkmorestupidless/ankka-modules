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
    "a brand's values carry no version, no service and no grant" -> "not yet bound: spec 021 T020",
    "a brand whose values lack a key an enabled module names runs nothing of that module until the key is set" -> "not yet bound: spec 021 T020",
    "a real-money brand is refused by the agent on an installation that is not fit for it" -> "not yet bound: spec 021 T020",
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
