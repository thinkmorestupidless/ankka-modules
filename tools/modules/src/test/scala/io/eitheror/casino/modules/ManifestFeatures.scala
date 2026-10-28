package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.controlplane.api.ServiceLifecycle
import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/**
 * features/brands/manifest.feature against the tool in local mode (spec 021: a brand is its values and the
 * modules applied to it; `apply <slug>` plays the vendor's agent) and an in-memory control plane and Keycloak.
 * What only a running installation can show — a player registering and signing in, a wallet serving
 * through a move — is checked here as far as the tool decides it (the realm allows registration; the
 * other brand's services are not touched) and end to end by ApplyOnKindSuite.
 */
class ManifestFeatures extends GherkinSuite("features/brands/manifest.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "the same key in two brands names two different things" -> "the wallet's own suites (spec 002): a key carries no brand, and two brands are two projects"
  )

  private var cp: InMemoryControlPlane       = InMemoryControlPlane(hasGrants = true)
  private var realms: InMemoryRealms         = InMemoryRealms()
  private var out: Output.Collected          = Output.Collected()
  private var code: Int                      = -1
  private var installationArgs: Seq[String]  = Seq("--installation", "dev-kind")
  private var before: Map[String, Long]      = Map.empty

  private def fresh(): Unit =
    cp = InMemoryControlPlane(hasGrants = true); realms = InMemoryRealms(); out = Output.Collected(); code = -1

  private def run(args: String*): Int =
    out = Output.Collected()
    code = Main.run(Main.parse(args), Fixtures.root, out, () => cp, Some(realms))
    code

  private def applyShared(): Unit =
    for project <- Vector("payments", "programme", "reasoner") do
      assertEquals(run("apply", s"shared/$project/manifest.json", "--installation", "dev-kind"), 0, out.lines.mkString("\n"))

  Given("an installation whose shared payments, programme and audit are running") { () =>
    fresh(); applyShared()
  }

  /** The modules every part of spec 001's brand is in that need no secret the values do not declare. */
  private val Modules = "casino,reporting"
  private def pinned(version: String) = s"core=$version,casino=$version,reporting=$version"

  private def valuesOf(slug: String): Seq[String] =
    if slug == "goldvibe" then Seq.empty else Seq("--values", s"tools/modules/src/test/resources/values/$slug.json")

  /** The brand's project with its secret keys set, as `secrets` leaves it before the first apply. */
  private def withSecrets(slug: String): Unit =
    if cp.project(slug).isEmpty then cp.createProject(slug, slug, "eitheror")
    cp.setSecret(slug, "tenant-values", Fixtures.CoreSecrets + ("st8-api-key" -> "test"))

  /** At a pinned release, as if every service had its build: the tree's "not yet built" ones are applied anyway. */
  private def applyBrand(slug: String, version: String, extra: String*): Int =
    withSecrets(slug)
    run(Seq("apply", slug, "--allow-unbuilt", "--modules", Modules, "--version", pinned(version)) ++ valuesOf(slug) ++ extra*)

  When("the operator applies the manifest for a brand named {string}") { (slug: String) =>
    assertEquals(applyBrand(slug, "0.1.0", "--installation", "dev-kind"), 0, out.lines.mkString("\n"))
    // the shared projects grant per brand under brands/: applied again now the brand exists
    applyShared()
  }

  Then("brand {string} is running with every part of it at the version its manifest pins") { (slug: String) =>
    val modules = ModuleManifest.treeManifests(Fixtures.root).flatMap(p => ModuleManifest.load(p).toOption)
      .filter(m => ("core" +: Modules.split(',').toVector).contains(m.name))
    val pins = modules.flatMap(_.services).map(s => s.name -> s.image.replace(":0.0.0", ":0.1.0")).toMap
    val running = cp.services(slug).map(s => s.name -> (s.lifecycle, s.image)).toMap
    assertEquals(running.keySet, pins.keySet)
    pins.foreach((name, image) => assertEquals(running(name), (ServiceLifecycle.Ready, image), name))
  }

  Then("a player can register and sign in at brand {string}") { (slug: String) =>
    val realm = realms.realms.get(s"casino-$slug")
    assert(realm.isDefined, s"no realm casino-$slug")
    assert(realm.get.contains("\"registrationAllowed\""), "the realm is the original's, which allows registration")
  }

  Then("the shared payments, programme and audit are admitted to brand {string} as its manifest allows") { (slug: String) =>
    val made = cp.grants(slug).get.map(g => g.principal -> g.effect).toMap
    for principal <- Vector("service:payments/merchant", "service:payments/psp-gateway", "service:programme/affiliate-programme", "service:reasoner/audit") do
      assertEquals(made.get(principal), Some("in effect"), principal)
    val received = cp.receivedGrants(slug).get.map(_.principal).toSet
    assertEquals(received, Set(s"service:$slug/payouts", s"service:$slug/players"))
  }

  Given("brands {string} and {string} created from the same manifest") { (a: String, b: String) =>
    fresh(); applyShared()
    for slug <- Vector(a, b) do
      assertEquals(applyBrand(slug, "0.1.0", "--installation", "dev-kind"), 0, out.lines.mkString("\n"))
    before = cp.services(b).map(s => s.name -> s.generation).toMap
  }

  When("the wallet of brand {string} is moved to a new version") { (slug: String) =>
    // The wallet is the core's: moving it moves the core's release, which is the unit a brand runs at.
    assertEquals(applyBrand(slug, "0.2.0", "--installation", "dev-kind"), 0, out.lines.mkString("\n"))
    assertEquals(cp.services(slug).find(_.name == "wallet").map(_.image), Some("europe-west1-docker.pkg.dev/eitheror/casino/wallet:0.2.0"))
  }

  Then("the wallet of brand {string} keeps its version and serves without interruption") { (slug: String) =>
    val after = cp.services(slug).map(s => s.name -> s.generation).toMap
    assertEquals(after, before, "no service of the other brand gained a generation, so none was restarted")
    assert(cp.services(slug).forall(_.lifecycle == ServiceLifecycle.Ready))
  }

  When("the operator applies a manifest that names a part the contract does not list") { () =>
    // A brand names no part now; what it can name that the contract does not list is a configuration key.
    run("apply", "goldvibe", "--installation", "dev-kind", "--values", "tools/modules/src/test/resources/values/unknown-key.json")
  }

  Then("the manifest is refused before anything is created, naming the unknown entry") { () =>
    assertEquals(code, 1)
    assert(out.lines.exists(l => l.startsWith("refused") && l.contains("'jackpot-size'")), out.lines.mkString("\n"))
    assertEquals(cp.calls.count(_.contains("/goldvibe")), 0)
  }

  private val installations = Map(
    "that keeps no backup copy outside itself" -> "no-offcluster-copy",
    "that keeps its facts in one copy only"    -> "one-copy"
  )

  private def useFixture(name: String): Unit =
    installationArgs = Seq("--installation", s"../../tests/fixtures/installations/$name")

  Given("an installation that keeps no backup copy outside itself") { () => fresh(); useFixture("no-offcluster-copy") }
  Given("an installation that keeps its facts in one copy only") { () => fresh(); useFixture("one-copy") }
  Given("an installation meeting every condition for real money") { () => fresh(); useFixture("fit") }
  Given("a local installation") { () => fresh(); installationArgs = Seq("--installation", "dev-kind") }

  When("a manifest marked for real money is applied") { () =>
    withSecrets("goldvibe")
    run(Seq("apply", "goldvibe", "--allow-unbuilt", "--values", "tools/modules/src/test/resources/values/goldvibe-real-money.json") ++ installationArgs*)
  }

  When("a manifest not marked for real money is applied") { () =>
    withSecrets("goldvibe")
    run(Seq("apply", "goldvibe", "--allow-unbuilt") ++ installationArgs*)
  }

  Then("it is refused, naming the backups") { () => refusedNaming("the backups") }
  Then("it is refused, naming the copies of its facts") { () => refusedNaming("the copies of its facts") }

  private def refusedNaming(condition: String): Unit =
    assertEquals(code, 1)
    val refusals = out.lines.filter(_.startsWith("refused"))
    assert(refusals.nonEmpty && refusals.forall(_.contains(condition)), refusals.mkString("\n"))
    // the project was made by the scenario's secrets; the apply wrote nothing
    assertEquals(cp.writes.filterNot(w => w == "POST /projects/goldvibe" || w.contains("/secrets/tenant-values")), Vector.empty)

  Then("the brand is created and its status lists the conditions that were checked") { () =>
    assertEquals(code, 0, out.lines.mkString("\n"))
    assert(cp.project("goldvibe").isDefined)
    out = Output.Collected()
    Main.run(Main.parse(Seq("status", "goldvibe", "--values", "tools/modules/src/test/resources/values/goldvibe-real-money.json") ++ installationArgs), Fixtures.root, out, () => cp, Some(realms))
    assert(out.lines.contains("real money: fit"), out.lines.mkString("\n"))
    for c <- Vector("the backups", "the erasure of personal data", "the retention of facts", "the copies of its facts") do
      assert(out.lines.exists(l => l.contains(c) && l.trim.startsWith("met")), c)
  }

  Then("the brand is created using only what the local installation has") { () =>
    assertEquals(code, 0, out.lines.mkString("\n"))
    assert(cp.project("goldvibe").isDefined)
    assert(out.lines.exists(_.contains("retention below intent")), "the local installation's retention is used and noted")
  }
