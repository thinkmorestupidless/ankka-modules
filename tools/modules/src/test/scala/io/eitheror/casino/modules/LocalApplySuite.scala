package io.eitheror.casino.modules

/** `apply <slug>` in local mode: the agent's order played by the tool (spec 021 T022, contracts/modules-tool.md). */
class LocalApplySuite extends munit.FunSuite:

  private val root    = Fixtures.root
  private val secrets = "tools/modules/src/test/resources/secrets/goldvibe.secrets"

  private val realms = scala.collection.mutable.Map.empty[InMemoryControlPlane, InMemoryRealms]

  private def run(cp: InMemoryControlPlane, args: String*)(using env: Map[String, String] = Map.empty): (Int, Output.Collected) =
    val out  = Output.Collected()
    val code = Main.run(Main.parse(args), root, out, () => cp, Some(realms.getOrElseUpdate(cp, InMemoryRealms())), () => fail("no vendor in local mode"), env)
    (code, out)

  /** goldvibe's project with its secrets set, as an operator does once before the first apply of casino. */
  private def withSecrets(): InMemoryControlPlane =
    val cp = InMemoryControlPlane(hasGrants = true)
    cp.createProject("goldvibe", "Goldvibe", "eitheror")
    val (code, out) = run(cp, "secrets", "goldvibe", "--installation", "dev-kind", "--secrets", secrets)
    assertEquals(code, 0, out.errors.mkString)
    cp

  private def kind(w: String): String = w match
    case w if w.startsWith("POST /projects/") && w.endsWith("/grants") => "grants"
    case w if w.contains("/topics/")                                   => "topics"
    case w if w.contains("/secrets/")                                  => "secrets"
    case w if w.endsWith("/expose")                                    => "expose"
    case w if w.startsWith("PUT /services")                            => "services"
    case w if w.startsWith("POST /projects/")                          => "project"
    case w                                                              => w

  test("the core alone: project, values, then topics, services in manifest order, exposure, grants") {
    val cp = InMemoryControlPlane(hasGrants = true)
    cp.createProject("goldvibe", "Goldvibe", "eitheror")
    cp.setSecret("goldvibe", "tenant-values", Fixtures.CoreSecrets)
    val before = cp.writes.size
    val (code, out) = run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt", "--version", "core=0.3.0")
    assertEquals(code, 0, out.lines.mkString("\n"))
    assertEquals(cp.writes.drop(before).map(kind).distinct, Vector("secrets", "topics", "services", "expose", "grants"))
    assertEquals(cp.services("goldvibe").map(_.name), Vector("wallet", "players", "kyc", "responsible-gambling", "payouts", "attribution"))
    assert(cp.services("goldvibe").forall(_.image.endsWith(":0.3.0")))
  }

  test("core's services, waited for, come before any module's; each module after its dependencies") {
    val cp = withSecrets()
    val (code, out) = run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt", "--modules", "casino,reporting")
    assertEquals(code, 0, out.lines.mkString("\n"))
    val applied = cp.writes.filter(_.startsWith("PUT /services/")).map(_.split('/').last)
    assertEquals(applied, Vector("wallet", "players", "kyc", "responsible-gambling", "payouts", "attribution", "game-provider", "rewards", "reporting"))
    val casinoTopic = cp.writes.indexWhere(_.contains("/topics/casino.rounds"))
    assert(casinoTopic > cp.writes.indexWhere(_ == "PUT /services/goldvibe/attribution"), "casino's topics follow the core's services")
  }

  test("a second apply changes nothing") {
    val cp = withSecrets()
    run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt", "--modules", "casino")
    val (code, out) = run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt", "--modules", "casino")
    assertEquals(code, 0)
    assert(out.lines.contains("changes: 0"), out.lines.mkString("\n"))
  }

  test("a module whose key tenant-values lacks is held, naming it, and so is every module depending on it") {
    val cp = InMemoryControlPlane(hasGrants = true)
    cp.createProject("goldvibe", "Goldvibe", "eitheror")
    cp.setSecret("goldvibe", "tenant-values", Map("st8-api-key" -> "test"))
    val (code, out) = run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt", "--modules", "casino")
    assertEquals(code, 0, out.lines.mkString("\n"))
    assert(out.lines.exists(l => l.startsWith("note module core held: missing kyc-provider-key")), out.lines.mkString("\n"))
    assert(out.lines.exists(_.startsWith("note module casino held: its dependency core is held")), out.lines.mkString("\n"))
    assertEquals(cp.services("goldvibe"), Vector.empty)
  }

  test("an installation with a vendor is refused before any call, naming the vendor") {
    val cp = InMemoryControlPlane()
    val (code, out) = run(cp, "apply", "goldvibe", "--installation", "dev-kind")(using Map("ANKKA_VENDOR_URL" -> "https://vendor.invalid"))
    assertEquals(code, 1)
    assert(out.lines.exists(l => l.startsWith("refused") && l.contains("https://vendor.invalid")), out.lines.mkString("\n"))
    assertEquals(cp.calls, Vector.empty)
  }

  test("a service contract/keys.md lists as not yet built is refused before any call, naming it and its module") {
    val cp = withSecrets()
    val before = cp.calls.size
    val (code, out) = run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--modules", "casino")
    assertEquals(code, 1, out.lines.mkString("\n"))
    val keys = Keys.load(root)
    val named = Vector("players", "kyc", "responsible-gambling", "payouts", "attribution", "rewards").filter(keys.notYetBuilt.contains)
    assert(named.nonEmpty, "the tree lists no core or casino service as not yet built: this test has nothing left to refuse")
    named.foreach { s =>
      assert(out.lines.exists(l => l.startsWith(s"refused service '$s' of module ")), s"$s not named\n${out.lines.mkString("\n")}")
    }
    assert(out.lines.contains("calls: 0"))
    assertEquals(cp.calls.drop(before), Vector.empty)
  }

  test("--allow-unbuilt applies the not-yet-built services anyway") {
    val cp = withSecrets()
    val (code, out) = run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt")
    assertEquals(code, 0, out.lines.mkString("\n"))
    assert(!out.lines.exists(_.startsWith("refused")), out.lines.mkString("\n"))
    assert(cp.services("goldvibe").map(_.name).contains("kyc"))
  }

  test("a real-money brand on dev-kind is refused before any write, naming each unmet fact") {
    val cp = InMemoryControlPlane()
    val (code, out) = run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt", "--values", "tools/modules/src/test/resources/values/goldvibe-real-money.json")
    assertEquals(code, 1)
    assert(out.lines.exists(_.contains("backups.target")), out.lines.mkString("\n"))
    assertEquals(cp.writes, Vector.empty)
  }

  test("a service never Ready fails the module naming it, and nothing after it is applied") {
    val cp = withSecrets()
    val v = Values.load(root.resolve("brands/goldvibe/values.json"), Main.valuesView(root)).toOption.get
    val ms = ModuleManifest.validateTree(root, ModuleManifest.ContractView.fromTree(root))._1
    val plan = BrandRender.plan(v, Keys.load(root), ms, Vector("casino"), Map.empty, Some(Fixtures.devKind)).toOption.get
    val never = (_: ControlPlane, _: String, name: String) => if name == "players" then Left("service players not Ready within 10m") else Right(())
    val result = LocalApply.run(plan, v, Some(Fixtures.devKind), cp, LocalApply.Options("eitheror", ready = never))
    assertEquals(result.failure, Some("module core: service players not Ready within 10m"))
    assert(!cp.writes.exists(_.contains("game-provider")))
  }

  test("status <slug> gives the fitness, then each module with its services, topics and grants") {
    val cp = withSecrets()
    run(cp, "apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt", "--modules", "casino")
    val (code, out) = run(cp, "status", "goldvibe", "--installation", "dev-kind")
    assertEquals(code, 0)
    assert(out.lines.contains("real money: not fit (the brand does not take real money)"), out.lines.mkString("\n"))
    assertEquals(out.lines.filter(_.startsWith("module ")).toVector, Vector("module core", "module casino"))
    assert(out.lines.exists(_.trim.startsWith("game-provider: Ready")), out.lines.mkString("\n"))
  }

  test("a brand's realm and its operators' role are made once") {
    val cp = withSecrets()
    val realms = InMemoryRealms()
    def apply() =
      val out = Output.Collected()
      Main.run(Main.parse(Seq("apply", "goldvibe", "--installation", "dev-kind", "--allow-unbuilt")), root, out, () => cp, Some(realms), () => fail("no vendor"), Map.empty)
      out.lines.toVector
    val first = apply()
    assert(first.contains("changed realm casino-goldvibe created"), first.mkString("\n"))
    assert(first.contains("changed role brand_goldvibe added to realm eitheror"))
    assert(!apply().exists(_.contains("realm casino-goldvibe created")))
  }

  test("a brand manifest is no longer applied: a brand is applied from its values") {
    val cp = InMemoryControlPlane()
    val (code, out) = run(cp, "apply", "tests/fixtures/manifests/goldvibe.json", "--installation", "dev-kind")
    assertEquals(code, 1)
    assert(out.lines.exists(_.contains("a brand is applied from its values")))
    assertEquals(cp.calls, Vector.empty)
  }
