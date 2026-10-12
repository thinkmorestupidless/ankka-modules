package io.eitheror.casino.simulator.core

import java.nio.file.{Files, Paths}

/** Scenarios are data (FR-019): every shipped file validates against the spec's schema and cites its source (FR-018). */
class ScenarioSuite extends munit.FunSuite:

  private val dir = Paths.get("src/main/resources/scenarios")

  private val files =
    String(Files.readAllBytes(dir.resolve("index.txt")), "UTF-8").linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toVector

  test("every file in the directory is listed in the index, and every listed file exists") {
    val present = Files.list(dir).toArray.map(_.toString).map(p => Paths.get(p).getFileName.toString).filter(_.endsWith(".json")).toSet
    assertEquals(present, files.toSet)
  }

  files.foreach { file =>
    test(s"$file validates against contracts/scenario.schema.json and names its source") {
      val text = String(Files.readAllBytes(dir.resolve(file)), "UTF-8")
      assertEquals(Scenario.problems(text), Vector.empty)
      val scenario = Scenario.parse(text).fold(fail(_), identity)
      assertEquals(s"${scenario.name}.json", file)
      assert(scenario.source.trim.nonEmpty, s"$file has no source (FR-018)")
    }
  }

  test("the shipped scenarios are FR-017's list, the default and the harness's groups") {
    val names = Scenario.shipped.map(_.name).toSet
    val fr017 = Set("slow-operator", "cancel-after-credit", "out-of-order", "retry-storm", "rotated-key", "wrong-key",
      "unknown-token", "win-larger-than-any-bet", "orphaned-bet", "malformed-body")
    assert(fr017.subsetOf(names), (fr017 -- names).toString)
    assert(names.contains("default"))
  }

  test("the default scenario is the stand-in: one pass-through step, the stub's bodies, no retry") {
    val default = Scenario.shipped.find(_.name == "default").get
    assert(default.passThrough)
    assertEquals(default.shape, "stub")
    assertEquals(default.schedule.map(_.intervals), Some(Vector.empty))
  }

  test("a scenario that is not JSON, or breaks the schema, is refused naming where") {
    assert(Scenario.problems("{").head.contains("not JSON"))
    val problems = Scenario.problems("""{"name":"x","version":1,"source":"s","shape":"stub","schedule":{"wait":"5 seconds","intervals":[],"cap":"0s"},"steps":[]}""")
    assert(problems.exists(_.contains("wait")), problems.toString)
  }

  test("an expectation must judge a send") {
    val text = """{"name":"x","version":1,"source":"s","shape":"harness","schedule":{"wait":"1s","intervals":[],"cap":"0s"},
                 |"steps":[{"kind":"wait","for":"1s"},{"kind":"expect","of":0,"status":["ok"]}]}""".stripMargin
    assert(Scenario.parse(text).left.exists(_.contains("is not a send")))
  }

  test("a load profile may only play a scenario whose every send waits for an instruction") {
    assert(Scenario.shipped.find(_.name == "default").get.instructionDriven)
    assert(!Scenario.shipped.find(_.name == "slow-operator").get.instructionDriven)
  }
