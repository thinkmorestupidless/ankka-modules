package io.eitheror.casino.wallet.config

/** Spec 030 W3: what the operator's reward settings accept, and the field each refusal names. */
class RewardSettingsSuite extends munit.FunSuite:

  private val valid =
    """{"contributionRateByGameType":{"slots":100,"table":10,"poker":0},"bonusWageringCounts":false,"partialSplitsAllowed":true,"maxConcurrentAwards":2}"""

  private def refusedAt(body: String): String = RewardSettings.parse(body).fold(_.field, s => fail(s"accepted: $s"))

  test("every value read") {
    assertEquals(RewardSettings.parse(valid),
      Right(RewardSettings(Map("slots" -> 100L, "table" -> 10L, "poker" -> 0L), bonusWageringCounts = false, partialSplitsAllowed = true, Some(2))))
  }

  test("a limit absent or null is no limit; an empty table is every game type at the default") {
    val none = """{"contributionRateByGameType":{},"bonusWageringCounts":true,"partialSplitsAllowed":true}"""
    assertEquals(RewardSettings.parse(none).map(_.maxConcurrentAwards), Right(None))
    assertEquals(RewardSettings.parse(none.dropRight(1) + ""","maxConcurrentAwards":null}""").map(_.maxConcurrentAwards), Right(None))
    assertEquals(RewardSettings.parse(none).map(_.contributionRateByGameType), Right(Map.empty))
  }

  test("a body that is not an object names the body") {
    Seq("", "nope", "[]", "1", "null").foreach(b => assertEquals(refusedAt(b), "body", b))
  }

  test("a rate outside 0–100, not whole, or not a number names its game type") {
    Seq("101", "-1", "10.5", "\"10\"", "null", "true").foreach { v =>
      assertEquals(refusedAt(s"""{"contributionRateByGameType":{"slots":100,"table":$v},"bonusWageringCounts":true,"partialSplitsAllowed":true}"""),
        "contributionRateByGameType.table", v)
    }
  }

  test("a game type blank, padded or too long is refused at its path") {
    assertEquals(refusedAt("""{"contributionRateByGameType":{" ":10},"bonusWageringCounts":true,"partialSplitsAllowed":true}"""),
      "contributionRateByGameType. ")
    assertEquals(refusedAt("""{"contributionRateByGameType":{" slots":10},"bonusWageringCounts":true,"partialSplitsAllowed":true}"""),
      "contributionRateByGameType. slots")
    val long = "g" * 65
    assertEquals(refusedAt(s"""{"contributionRateByGameType":{"$long":10},"bonusWageringCounts":true,"partialSplitsAllowed":true}"""),
      s"contributionRateByGameType.$long")
  }

  test("the table missing, not an object, or past 100 game types names the table") {
    val many = (1 to 101).map(i => s""""g$i":1""").mkString("{", ",", "}")
    Seq("", """"contributionRateByGameType":[],""", s""""contributionRateByGameType":$many,""").foreach { t =>
      assertEquals(refusedAt(s"""{$t"bonusWageringCounts":true,"partialSplitsAllowed":true}"""), "contributionRateByGameType", t.take(40))
    }
  }

  test("each switch missing or not a boolean names it") {
    assertEquals(refusedAt("""{"contributionRateByGameType":{},"partialSplitsAllowed":true}"""), "bonusWageringCounts")
    assertEquals(refusedAt("""{"contributionRateByGameType":{},"bonusWageringCounts":"yes","partialSplitsAllowed":true}"""), "bonusWageringCounts")
    assertEquals(refusedAt("""{"contributionRateByGameType":{},"bonusWageringCounts":true}"""), "partialSplitsAllowed")
    assertEquals(refusedAt("""{"contributionRateByGameType":{},"bonusWageringCounts":true,"partialSplitsAllowed":1}"""), "partialSplitsAllowed")
  }

  test("a limit below 1, not whole or past an Int names maxConcurrentAwards") {
    Seq("0", "-3", "1.5", "\"2\"", "3000000000", "false").foreach { v =>
      assertEquals(refusedAt(s"""{"contributionRateByGameType":{},"bonusWageringCounts":true,"partialSplitsAllowed":true,"maxConcurrentAwards":$v}"""),
        "maxConcurrentAwards", v)
    }
  }
