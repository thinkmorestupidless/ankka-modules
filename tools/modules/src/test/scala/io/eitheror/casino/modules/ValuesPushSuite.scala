package io.eitheror.casino.modules

import scala.collection.mutable

/** `values push`, `secrets`, `hostnames` and `validate --real-money` (spec 021 T019, contracts/values.md). */
class ValuesPushSuite extends munit.FunSuite:

  private final class FakeVendor extends Vendor:
    val set = mutable.ArrayBuffer.empty[(String, Map[String, String])]
    private val log = mutable.ArrayBuffer.empty[String]
    def channelVersion(module: String, channel: String) = Left("not asked")
    def setValues(tenant: String, entries: Map[String, String]) = { log += s"tenants values set $tenant"; set += tenant -> entries; Right(()) }
    def schema() = Left("not asked")
    def calls = log.toVector

  private val Local  = Map.empty[String, String]
  private val Remote = Map("ANKKA_VENDOR_URL" -> "https://vendor.invalid")

  private def run(args: Seq[String], cp: ControlPlane, vendor: Vendor = FakeVendor(), env: Map[String, String] = Local) =
    val out  = Output.Collected()
    val code = Main.run(Main.parse(args), Fixtures.root, out, () => cp, None, () => vendor, env)
    (code, out)

  private def withProject(id: String) =
    val cp = InMemoryControlPlane()
    cp.createProject(id, id, "eitheror")
    cp

  test("locally, values push writes the values and the derived keys to the project's tenant-values, and no secret") {
    val cp = withProject("goldvibe")
    val (code, out) = run(Seq("values", "push", "goldvibe", "--installation", "dev-kind"), cp)
    assertEquals(code, 0, out.lines.mkString("\n") + out.errors.mkString("\n"))
    val written = cp.secretsBy("goldvibe")("tenant-values")
    assertEquals(written("auth-realm"), "casino-goldvibe")
    assertEquals(written("casino-real-money"), "false")
    assertEquals(written("game-provider-mode"), "demo")
    assertEquals(written("keycloak-jwks-base"), "http://casino-keycloak.casino-identity.svc")
    assert(!written.contains("st8-api-key") && !written.contains("players-realm-admin"), written.keys.toString)
  }

  test("through the vendor, values push sets the tenant's values there and calls no control plane") {
    val vendor = FakeVendor()
    val (code, _) = run(Seq("values", "push", "goldvibe", "--organization", "goldco"), InMemoryControlPlane(), vendor, Remote)
    assertEquals(code, 0)
    assertEquals(vendor.set.map(_._1).toVector, Vector("goldco"))
    assertEquals(vendor.set.head._2("casino-project"), "goldco-vendor")
    assert(!vendor.set.head._2.keys.exists(_.endsWith("-key")), "no secret goes through the vendor")
  }

  test("a values push with no project yet says apply creates it, and writes nothing") {
    val cp = InMemoryControlPlane()
    val (code, out) = run(Seq("values", "push", "goldvibe", "--installation", "dev-kind"), cp)
    assertEquals(code, 1)
    assert(out.lines.exists(_.contains("project goldvibe does not exist")))
    assertEquals(cp.writes, Vector.empty)
  }

  test("a real-money brand is refused on dev-kind before anything is written, naming each unmet fact") {
    val (code, out) = run(Seq("validate", "brands/goldvibe/values.json", "--installation", "dev-kind", "--real-money"), InMemoryControlPlane())
    assertEquals(code, 1)
    assert(out.lines.exists(_.contains("backups.target")), out.lines.mkString("\n"))
    assert(out.lines.exists(_.contains("erasure.enabled")))
    assert(out.lines.exists(_.contains("broker.nodes")))
    assert(out.lines.contains("calls: 0"))
  }

  test("a real-money brand passes validate on the gke overlay") {
    val (code, out) = run(Seq("validate", "brands/goldvibe/values.json", "--installation", "gke", "--real-money"), InMemoryControlPlane())
    assertEquals(code, 0, out.lines.mkString("\n"))
  }

  test("values push reads the facts from GET /installation when the control plane answers, and refuses on what it reports") {
    val cp = withProject("goldvibe")
    cp.installationJson = Some("""{"platformVersion":"0.11.0","backups":{"backupTarget":"object-store","copyRequired":true,"sharesFailureDomain":false}}""")
    val facts = BrandCommands.facts(Installation.load(Fixtures.root, "gke").toOption, Some(cp)).get
    assertEquals(facts.source, "GET /installation")
    assertEquals(facts.get("backups.target"), Some("object-store"))
    // ankka does not report erasure, broker or topics yet: unmet until it does, though the overlay says otherwise.
    val unmet = Readiness.check(Readiness.core.requires, Map("casino-real-money" -> "true"), facts).map(_.fact)
    assertEquals(unmet.toSet, Set("erasure.enabled", "erasure.keyringInstances", "broker.nodes", "broker.defaultCopies", "broker.minInSync", "topics.longestRetention"))
  }

  test("secrets fills tenant-values from the lines keys.md names and each named secret, never through the vendor") {
    val cp = withProject("goldvibe")
    val vendor = FakeVendor()
    val (code, out) = run(Seq("secrets", "goldvibe", "--installation", "dev-kind", "--secrets", "tools/modules/src/test/resources/secrets/goldvibe.secrets"), cp, vendor)
    assertEquals(code, 0, out.errors.mkString)
    val tenant = cp.secretsBy("goldvibe")("tenant-values")
    assertEquals(tenant.filterNot(_._1.startsWith("players-")), Map("st8-api-key" -> "test-st8"))
    assertEquals((tenant("players-realm-admin"), tenant("players-merchant-key"), tenant.count(_._1.startsWith("players-"))), ("test-realm-admin", "test-merchant", 6))
    assertEquals(cp.secretsBy("goldvibe")("goldvibe-st8"), Map("API_KEY" -> "test-st8"))
    assertEquals(vendor.calls, Vector.empty)
    assert(!out.lines.exists(_.contains("test-st8")), "a secret value is never printed")
  }

  test("hostnames adds each of the values' hostnames, or says it waits where the platform has none") {
    val cp = withProject("goldvibe")
    val (code, out) = run(Seq("hostnames", "goldvibe", "--installation", "dev-kind"), cp)
    assertEquals(code, 0)
    assert(out.lines.forall(!_.startsWith("failed")))
    val v = Values.load(Fixtures.root.resolve("brands/goldvibe/values.json"), Main.valuesView(Fixtures.root)).toOption.get
      .copy(hostnames = Map("wallet" -> "wallet.goldvibe.example"))
    val more = Output.Collected()
    BrandCommands.addHostnames(v, "goldvibe", cp, more)
    assertEquals(cp.hostnames(("goldvibe", "wallet")), Vector("wallet.goldvibe.example"))
    val without = InMemoryControlPlane(hasHostnames = false)
    val waits = Output.Collected()
    BrandCommands.addHostnames(v, "goldvibe", without, waits)
    assertEquals(waits.lines.toVector, Vector("note hostname wallet.goldvibe.example for wallet waits: the platform has no custom hostnames"))
  }
