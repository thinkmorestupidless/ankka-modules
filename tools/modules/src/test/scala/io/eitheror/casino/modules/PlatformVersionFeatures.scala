package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * features/brands/platform-version.feature (spec 031) against the working tree, the modules tool's
 * render and its apply with an in-memory control plane. The parity runs are each service's own build.
 */
class PlatformVersionFeatures extends GherkinSuite("features/brands/platform-version.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a service moved to a new version of the platform replays every recording as it did before" ->
      ("each service's parity suites (`tests/port/<service>/`), run in its own build by CI's job for it; that no recording " +
        "changed is spec 031 T031's evidence, `git diff --quiet origin/main -- 'tests/port/**/*.json'`, which CI cannot make")
  )

  private val root   = Fixtures.root
  private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

  private def children(dir: String): Vector[Path] =
    Files.list(root.resolve(dir)).iterator().asScala.toVector.sortBy(_.toString)

  // ── every part names one version ──────────────────────────────────────────

  private var wanted: String = ""
  private var named: Vector[(String, String)] = Vector.empty

  Given("the repository at the platform version {string}") { (v: String) => wanted = v }

  When("every service, every module manifest and the contract are read") { () =>
    val AnkkaVersion = """(?m)^val ankkaVersion = "([^"]+)"""".r
    val ContractDefault = """sys\.props\.getOrElse\("ankka\.version", "([^"]+)"\)""".r
    def runtimes(file: Path): Vector[String] =
      mapper.readTree(file.toFile).findValues("runtime").asScala.toVector.map(_.asText())
    val services = children("services").filter(d => Files.exists(d.resolve("service.json")))
    named =
      ContractDefault.findAllMatchIn(Files.readString(root.resolve("build.sbt"))).map(m => "build.sbt" -> m.group(1)).toVector ++
        services.filter(d => Files.exists(d.resolve("build.sbt"))).flatMap { d =>
          val build = AnkkaVersion.findAllMatchIn(Files.readString(d.resolve("build.sbt"))).map(_.group(1)).toVector
          assertEquals(build.size, 1, s"${root.relativize(d)}/build.sbt names ankkaVersion once")
          build.map(v => s"${root.relativize(d)}/build.sbt" -> v)
        } ++
        services.flatMap(d => runtimes(d.resolve("service.json")).map(v => s"${root.relativize(d)}/service.json" -> v)) ++
        children("modules").map(_.resolve("module.json")).filter(Files.exists(_)).flatMap(f => runtimes(f).map(root.relativize(f).toString -> _)) ++
        children("shared").map(_.resolve("manifest.json")).filter(Files.exists(_)).flatMap(f => runtimes(f).map(root.relativize(f).toString -> _))
  }

  Then("each names the platform version {string} and no other") { (v: String) =>
    assertEquals(v, wanted)
    assert(named.size > 40, s"read ${named.size}: every service, module and shared manifest")
    assertEquals(named.filterNot(_._2 == v), Vector.empty)
  }

  // ── a brand's values are untouched ────────────────────────────────────────

  private var slug: String = ""
  private var plan: Option[BrandPlan] = None

  Given("the values of brand {string}") { (s: String) => slug = s }

  When("the brand's modules are rendered") { () =>
    val v = Values.load(root.resolve(s"brands/$slug/values.json"), Main.valuesView(root)).fold(rs => fail(rs.mkString("; ")), identity)
    val ms = ModuleManifest.validateTree(root, ModuleManifest.ContractView.fromTree(root))._1
    plan = BrandRender.plan(v, Keys.load(root), ms, Vector("casino", "engagement", "support", "player-site"), Map.empty,
      Some(Fixtures.devKind), routes = ModuleManifest.ContractView.contractRoutes(root)).toOption
  }

  Then("every service of the brand runs on the platform version {string}") { (v: String) =>
    val services = plan.getOrElse(fail("the brand did not render")).inOrder.flatMap(_.services)
    assert(services.nonEmpty)
    // The player site is web-hosted: it runs no ankka runtime of its own.
    services.foreach(d => assertEquals(d.service.runtime, if d.service.hosting == "web" then None else Some(v), d.name))
  }

  Then("the brand's values are unchanged") { () =>
    // Nothing in a brand's values names the platform: a new one changes nothing of the brand's own.
    val text = Files.readString(root.resolve(s"brands/$slug/values.json"))
    assert(!"""\b\d+\.\d+\.\d+\b""".r.findFirstIn(text).exists(_.startsWith("0.1")), "the values name a platform version")
    assert(!text.contains("runtime"), "the values name a runtime")
  }

  // ── an installation that cannot run it ────────────────────────────────────

  private var at: Path = root
  private val cp = InMemoryControlPlane(hasGrants = true)
  private var code = 0
  private val out  = Output.Collected()

  Given("an installation that runs no platform version later than {string}") { (v: String) => at = Fixtures.withPlatform(v) }

  When("brand {string} is applied to it") { (s: String) =>
    code = Main.run(Main.parse(Seq("apply", s, "--installation", "dev-kind", "--allow-unbuilt")), at, out, () => cp,
      Some(InMemoryRealms()), () => fail("no vendor in local mode"), Map.empty)
  }

  Then("it is refused, naming the platform version the installation cannot run") { () =>
    assertEquals(code, 1, out.lines.mkString("\n"))
    assert(out.lines.headOption.exists(_.matches("refused: the installation dev-kind runs platform 0\\.16\\.3, which cannot run runtime 0\\.18\\.0")),
      out.lines.mkString("\n"))
  }

  Then("nothing of the brand is deployed") { () =>
    assertEquals(cp.writes, Vector.empty)
  }
