package io.eitheror.casino.brands

class ApplySuite extends munit.FunSuite:

  private val brand = Fixtures.brand("goldvibe")
  private def plan(b: Manifest = brand) = Render.plan(b, Some(Fixtures.devKind))
  private val options = Apply.Options("eitheror")

  test("an apply calls in order: project, topics, secrets, services, exposure, grants") {
    val cp = InMemoryControlPlane(hasGrants = true)
    val result = Apply.run(plan(), cp, options)
    assert(result.ok, result.failure.toString)
    val kinds = cp.writes.map {
      case w if w.startsWith("POST /projects/") && w.endsWith("/grants") => "grants"
      case w if w.contains("/topics/")                                   => "topics"
      case w if w.contains("/secrets/")                                  => "secrets"
      case w if w.endsWith("/expose")                                    => "expose"
      case w if w.startsWith("PUT /services")                            => "services"
      case w if w.startsWith("POST /projects/")                          => "project"
      case w                                                              => w
    }.distinct
    assertEquals(kinds, Vector("project", "topics", "services", "expose", "grants"))
  }

  test("a second apply of the same manifest changes nothing") {
    val cp = InMemoryControlPlane()
    Apply.run(plan(), cp, options)
    val again = Apply.run(plan(), cp, options)
    assertEquals(again.changes, Vector.empty)
  }

  test("a changed tag applies that one service and nothing else") {
    val cp = InMemoryControlPlane()
    Apply.run(plan(), cp, options)
    val moved = brand.copy(services = brand.services.map(s => if s.name == "wallet" then s.copy(image = s.image.replace("0.1.0", "0.2.0")) else s))
    val result = Apply.run(plan(moved), cp, options)
    assertEquals(result.changes.size, 1, result.changes.mkString("; "))
    assert(result.changes.head.startsWith("service wallet applied"))
  }

  test("an invalid manifest is refused before any call") {
    val cp = InMemoryControlPlane()
    val out = Output.Collected()
    val code = Main.run(Main.parse(Seq("apply", "tests/fixtures/manifests/bad-slug.json", "--installation", "dev-kind")), Fixtures.root, out, () => cp)
    assertEquals(code, 1)
    assertEquals(cp.calls, Vector.empty)
  }

  test("a deleted brand's slug cannot be created again") {
    val cp = InMemoryControlPlane()
    Apply.run(plan(), cp, options)
    cp.deleteProject("goldvibe")
    val result = Apply.run(plan(), cp, options)
    assert(result.failure.exists(f => f.startsWith("project") && f.contains("cannot be reused")), result.failure.toString)
  }

  test("an apply does not redeploy over an operator's emergency rollback") {
    val cp = InMemoryControlPlane()
    Apply.run(plan(), cp, options)
    val moved = brand.copy(services = brand.services.map(s => if s.name == "wallet" then s.copy(image = s.image.replace("0.1.0", "0.2.0")) else s))
    Apply.run(plan(moved), cp, options)
    cp.rollback("goldvibe", "wallet", 1)
    val result = Apply.run(plan(moved), cp, options)
    assertEquals(result.changes, Vector.empty)
    assert(result.notes.exists(_.contains("manifest behind cluster")), result.notes.mkString("; "))
  }

  test("without ankka 040 the grants wait and the status says why") {
    val result = Apply.run(plan(), InMemoryControlPlane(hasGrants = false), options)
    assert(result.notes.exists(_.startsWith("grants: platform lacks 040")))
  }

  test("with ankka 040 the grants FR-016 names are made, and the received ones checked") {
    val cp = InMemoryControlPlane(hasGrants = true)
    val result = Apply.run(plan(), cp, options)
    assertEquals(result.changes.count(_.startsWith("grant ")), 5)
    assert(result.notes.exists(_.contains("not yet received")))
    val shared = Render.plan(Fixtures.load("shared-payments"), None, Vector("goldvibe"))
    Apply.run(shared, cp, options)
    val after = Apply.run(plan(), cp, options)
    assert(!after.notes.exists(_.contains("not yet received")), after.notes.mkString("; "))
  }

  test("a brand's realm and its operators' role are created once") {
    val cp = InMemoryControlPlane()
    val realms = InMemoryRealms()
    val json = () => Realm.render(Realm.template(Fixtures.root), "goldvibe", "https://goldvibe.example")
    val first = Apply.run(plan(), cp, options.copy(realm = Some(realms -> json)))
    assert(first.changes.contains("realm casino-goldvibe created"))
    assert(first.changes.contains("role brand_goldvibe added to realm eitheror"))
    assertEquals(Apply.run(plan(), cp, options.copy(realm = Some(realms -> json))).changes, Vector.empty)
    assert(realms.realms("casino-goldvibe").contains("\"casino-goldvibe\""))
  }

  test("two brands from one manifest do not touch") {
    val cp = InMemoryControlPlane()
    Apply.run(plan(), cp, options)
    Apply.run(plan(Fixtures.brand("spinvibe")), cp, options)
    val before = cp.services("goldvibe").map(s => s.name -> s.generation).toMap
    val spinvibe = Fixtures.brand("spinvibe")
    val moved = spinvibe.copy(services = spinvibe.services.map(s => if s.name == "wallet" then s.copy(image = s.image.replace("0.1.0", "0.2.0")) else s))
    Apply.run(plan(moved), cp, options)
    assertEquals(cp.services("goldvibe").map(s => s.name -> s.generation).toMap, before)
  }
