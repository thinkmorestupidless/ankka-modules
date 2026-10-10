package io.eitheror.casino.brands

import Readiness.Verdict

class ReadinessSuite extends munit.FunSuite:

  private def fixture(name: String): Installation =
    Installation.fromFile(name, Fixtures.root.resolve(s"tests/fixtures/installations/$name/settings.env"))
      .fold(e => throw AssertionError(e), identity)

  private def failing(name: String): Vector[String] =
    Readiness.conditions(fixture(name)).filterNot(_.verdict == Verdict.Met).map(_.name)

  test("an installation meeting every condition is fit") {
    assert(Readiness.fit(Readiness.conditions(fixture("fit"))))
  }

  test("the gke overlay as written is fit") {
    assert(Readiness.fit(Readiness.conditions(Installation.load(Fixtures.root, "gke").toOption.get)))
  }

  test("dev-kind fails every condition") {
    val conditions = Readiness.conditions(Fixtures.devKind)
    assertEquals(conditions.count(_.verdict == Verdict.Failed), 4)
  }

  for (name, condition) <- Vector(
      "no-offcluster-copy" -> "the backups",
      "erasure-off"        -> "the erasure of personal data",
      "one-keyring"        -> "the erasure of personal data",
      "retention-default"  -> "the retention of facts",
      "one-copy"           -> "the copies of its facts",
      "two-brokers"        -> "the copies of its facts"
    )
  do
    test(s"$name fails $condition and nothing else") {
      assertEquals(failing(name), Vector(condition))
    }

  test("a condition that cannot be read refuses as one that fails") {
    val conditions = Readiness.conditions(fixture("unknown-backup"))
    assertEquals(conditions.find(_.name == "the backups").map(_.verdict), Some(Verdict.Unknown))
    assert(!Readiness.fit(conditions))
    assert(Readiness.refusals(conditions).head.message.contains("unknown"))
  }

  test("each refusal names the condition and where it was read") {
    val refusals = Readiness.refusals(Readiness.conditions(fixture("one-copy")))
    assertEquals(refusals.size, 1)
    assert(refusals.head.message.contains("FR-038 the copies of its facts"))
    assert(refusals.head.message.contains("settings.env"))
  }

  test("a real-money manifest on an unfit installation is refused before any call") {
    val out = Output.Collected()
    val code = Main.run(Main.parse(Seq("apply", "tests/fixtures/manifests/real-money.json", "--installation", "dev-kind")),
      Fixtures.root, out, () => fail("no call may be made"))
    assertEquals(code, 1)
    assert(out.lines.exists(_.contains("FR-035 the backups")))
    assert(out.lines.contains("calls: 0"))
  }
