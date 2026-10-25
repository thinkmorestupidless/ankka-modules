package io.eitheror.casino.ingest

import java.time.Duration

/** Research R13: every setting's default, and a value that cannot be read refused by the variable's name. */
class SettingsSuite extends munit.FunSuite:

  test("every default is research R13's") {
    val s = Settings.fromEnvironment(Map.empty).toOption.get
    assertEquals(s, Settings("merchant", Duration.ofSeconds(10), 600, 65536, 128, Duration.ofHours(24), Duration.ofDays(90)))
  }

  test("each variable is read") {
    val s = Settings.fromEnvironment(Map("MERCHANT_SERVICE" -> "cashier", "ANSWER_BOUND" -> "500ms", "RATE_LIMIT" -> "6000",
      "BODY_BOUND" -> "4096", "REQUEST_ID_BOUND" -> "64", "REQUEST_ID_RETENTION" -> "1h", "RECORD_RETENTION" -> "30d")).toOption.get
    assertEquals(s, Settings("cashier", Duration.ofMillis(500), 6000, 4096, 64, Duration.ofHours(1), Duration.ofDays(30)))
  }

  test("a malformed duration or count is refused naming the variable") {
    Seq("ANSWER_BOUND" -> "soon", "REQUEST_ID_RETENTION" -> "0h", "RECORD_RETENTION" -> "-1d", "RATE_LIMIT" -> "lots",
      "BODY_BOUND" -> "0", "REQUEST_ID_BOUND" -> "-3").foreach { (name, value) =>
      val refused = Settings.fromEnvironment(Map(name -> value))
      assert(refused.left.exists(_.startsWith(name)), s"$name=$value: $refused")
    }
  }
