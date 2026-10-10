package io.eitheror.casino.brands

import com.thinkmorestupidless.ankka.controlplane.api.ServiceLifecycle
import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/**
 * features/brands/manifest.feature against the tool and an in-memory control plane and Keycloak.
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

  When("the operator applies the manifest for a brand named {string}") { (slug: String) =>
    assertEquals(run("apply", s"brands/$slug/manifest.json", "--installation", "dev-kind"), 0, out.lines.mkString("\n"))
    // the shared projects grant per brand under brands/: applied again now the brand exists
    applyShared()
  }

  Then("brand {string} is running with every part of it at the version its manifest pins") { (slug: String) =>
    val manifest = Fixtures.root.resolve(s"brands/$slug/manifest.json")
    val pins = Manifest.load(manifest).toOption.get.services.map(s => s.name -> s.image).toMap
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
      val path = if slug == "goldvibe" then "brands/goldvibe/manifest.json" else s"tests/fixtures/manifests/$slug.json"
      assertEquals(run("apply", path, "--installation", "dev-kind"), 0, out.lines.mkString("\n"))
    before = cp.services(b).map(s => s.name -> s.generation).toMap
  }

  When("the wallet of brand {string} is moved to a new version") { (slug: String) =>
    val brand = Fixtures.brand(slug)
    val moved = brand.copy(services = brand.services.map(s => if s.name == "wallet" then s.copy(image = s.image.replace(":0.1.0", ":0.2.0")) else s))
    val result = Apply.run(Render.plan(moved, Some(Fixtures.devKind)), cp, Apply.Options("eitheror"))
    assertEquals(result.changes.map(_.takeWhile(_ != ' ')), Vector("service"))
  }

  Then("the wallet of brand {string} keeps its version and serves without interruption") { (slug: String) =>
    val after = cp.services(slug).map(s => s.name -> s.generation).toMap
    assertEquals(after, before, "no service of the other brand gained a generation, so none was restarted")
    assert(cp.services(slug).forall(_.lifecycle == ServiceLifecycle.Ready))
  }

  When("the operator applies a manifest that names a part the contract does not list") { () =>
    run("apply", "tests/fixtures/manifests/unknown-service.json", "--installation", "dev-kind")
  }

  Then("the manifest is refused before anything is created, naming the unknown entry") { () =>
    assertEquals(code, 1)
    assert(out.lines.exists(l => l.startsWith("refused") && l.contains("'jackpots'")), out.lines.mkString("\n"))
    assert(!cp.calls.exists(_.contains("/jackpots")))
    assertEquals(cp.calls.count(_.contains("/projects/goldvibe")), 0)
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
    run(Seq("apply", "tests/fixtures/manifests/real-money.json") ++ installationArgs*)
  }

  When("a manifest not marked for real money is applied") { () =>
    run(Seq("apply", "brands/goldvibe/manifest.json") ++ installationArgs*)
  }

  Then("it is refused, naming the backups") { () => refusedNaming("the backups") }
  Then("it is refused, naming the copies of its facts") { () => refusedNaming("the copies of its facts") }

  private def refusedNaming(condition: String): Unit =
    assertEquals(code, 1)
    val refusals = out.lines.filter(_.startsWith("refused"))
    assertEquals(refusals.size, 1, refusals.mkString("\n"))
    assert(refusals.head.contains(condition), refusals.head)
    assertEquals(cp.calls, Vector.empty)

  Then("the brand is created and its status lists the conditions that were checked") { () =>
    assertEquals(code, 0, out.lines.mkString("\n"))
    assert(cp.project("goldvibe").isDefined)
    out = Output.Collected()
    Main.run(Main.parse(Seq("status", "goldvibe") ++ installationArgs), Fixtures.root, out, () => cp, Some(realms))
    assert(out.lines.contains("real money: fit"), out.lines.mkString("\n"))
    for c <- Vector("the backups", "the erasure of personal data", "the retention of facts", "the copies of its facts") do
      assert(out.lines.exists(l => l.contains(c) && l.trim.startsWith("met")), c)
  }

  Then("the brand is created using only what the local installation has") { () =>
    assertEquals(code, 0, out.lines.mkString("\n"))
    assert(cp.project("goldvibe").isDefined)
    assert(out.lines.exists(_.contains("retention below intent")), "the local installation's retention is used and noted")
  }
