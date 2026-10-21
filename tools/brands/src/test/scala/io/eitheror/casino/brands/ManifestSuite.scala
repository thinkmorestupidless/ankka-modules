package io.eitheror.casino.brands

class ManifestSuite extends munit.FunSuite:

  private def refused(name: String): Vector[Problem] =
    Manifest.load(Fixtures.manifest(name)) match
      case Left(problems) => problems
      case Right(_)       => fail(s"$name was accepted")

  test("the pilot's manifest is valid") {
    val brand = Fixtures.brand("goldvibe")
    assertEquals(brand.slug, "goldvibe")
    assertEquals(brand.services.size, 11)
    assert(!brand.services.exists(_.name == "partner-api"), "the pilot excludes partner-api (FR-007)")
    assertEquals(brand.topics.size, 10)
  }

  test("a reserved slug is refused naming it") {
    val problems = refused("bad-slug")
    assert(problems.exists(p => p.message.contains("payments")), problems.mkString("\n"))
  }

  test("a topic the contract does not list is refused naming it") {
    val problems = refused("unknown-topic")
    assert(problems.exists(_.message.contains("casino.jackpots")), problems.mkString("\n"))
  }

  test("a part the contract does not list is refused naming it") {
    val problems = refused("unknown-service")
    assert(problems.exists(_.message.contains("jackpots")), problems.mkString("\n"))
  }

  test("an image tagged latest is refused") {
    assert(refused("latest-tag").exists(_.message.contains("latest")))
  }

  test("merchant may be exposed: it serves the payments backoffice from a browser (spec 004 research R1)") {
    Manifest.load(Fixtures.manifest("expose-service-only")) match
      case Left(problems) => fail(problems.mkString("\n"))
      case Right(m)       => assert(m.services.exists(p => p.name == "merchant" && p.expose))
  }

  test("a brand missing a service of FR-002 is refused") {
    assert(refused("missing-service").exists(p => p.rule == "services" && p.message.contains("wallet")))
  }

  test("a grant FR-016 does not allow is refused") {
    assert(refused("extra-grant").exists(p => p.rule == "grants-made" && p.message.contains("spinvibe")))
  }

  test("a shared project is checked against FR-003") {
    assert(Manifest.load(Fixtures.manifest("shared-payments")).isRight)
    assert(refused("shared-missing-service").exists(_.message.contains("ingest")))
  }

  test("the tools project holds the game provider simulator and nothing else (spec 020)") {
    val path = Fixtures.root.resolve("shared/tools/manifest.json")
    assert(Manifest.load(path).isRight, Manifest.load(path).left.toOption.mkString)
    val text = java.nio.file.Files.readString(path).replace("\"game-provider-simulator\"", "\"audit\"")
    assert(Manifest.parse(text).isLeft, "a service of another project is refused in tools")
  }

  test("tools is a reserved slug: no brand may take it") {
    val text = java.nio.file.Files.readString(Fixtures.manifest("goldvibe")).replace("\"goldvibe\"", "\"tools\"")
    Manifest.parse(text) match
      case Left(problems) => assert(problems.exists(_.message.contains("tools")), problems.mkString("\n"))
      case Right(_)       => fail("a brand named tools was accepted")
  }

  test("the simulator's project is refused on an installation that takes real money") {
    val out = Output.Collected()
    val code = Main.run(Main.parse(Seq("validate", "shared/tools/manifest.json", "--installation", "dev-kind", "--real-money")), Fixtures.root, out, () => fail("no control plane is needed"))
    assertEquals(code, 1)
    assert(out.lines.exists(_.contains("never runs on an installation that takes real money")), out.lines.mkString("\n"))
  }

  test("a slug longer than 42 characters is refused, so every service's address is one DNS label") {
    val long = "g" * 43
    val text = java.nio.file.Files.readString(Fixtures.manifest("goldvibe")).replace("\"goldvibe\"", s"\"$long\"")
    assert(Manifest.parse(text).isLeft)
  }

  test("validating makes no call") {
    val out = Output.Collected()
    val code = Main.run(Main.parse(Seq("validate", "tests/fixtures/manifests/unknown-topic.json")), Fixtures.root, out, () => fail("no control plane is needed"))
    assertEquals(code, 1)
    assert(out.lines.contains("calls: 0"))
  }
