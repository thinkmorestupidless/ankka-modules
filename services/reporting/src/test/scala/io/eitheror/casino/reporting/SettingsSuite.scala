package io.eitheror.casino.reporting

import java.time.{Duration, ZoneId}

class SettingsSuite extends munit.FunSuite:

  private val brand = Map("CASINO_BRAND" -> "goldvibe")

  test("the defaults: UTC, a fifteen-minute grace, generation 1, no modules, play money, the brand as the project") {
    val s = Settings.fromEnvironment(brand).toOption.get
    assertEquals(s, Settings("goldvibe", "goldvibe", realMoney = false, ZoneId.of("UTC"), Set.empty, Duration.ofMinutes(15), 1))
    assert(!s.casino && !s.predictions)
  }

  test("every variable is read") {
    val s = Settings.fromEnvironment(brand ++ Map(
      "CASINO_PROJECT"        -> "goldvibe-prod",
      "CASINO_REAL_MONEY"     -> "true",
      "CASINO_REPORTING_ZONE" -> "Africa/Johannesburg",
      "CASINO_MODULES"        -> "casino, predictions",
      "CLOSE_GRACE"           -> "30m",
      "REPORTING_GENERATION"  -> "2"
    )).toOption.get
    assertEquals(s.project, "goldvibe-prod")
    assert(s.realMoney)
    assertEquals(s.reportingZone, ZoneId.of("Africa/Johannesburg"))
    assertEquals(s.modules, Set("casino", "predictions"))
    assert(s.casino && s.predictions)
    assertEquals(s.closeGrace, Duration.ofMinutes(30))
    assertEquals(s.generation, 2)
  }

  test("a value that cannot be read is refused naming its variable") {
    assert(Settings.fromEnvironment(Map.empty).left.exists(_.startsWith("CASINO_BRAND")))
    assert(Settings.fromEnvironment(brand + ("CASINO_REPORTING_ZONE" -> "Mars/Olympus")).left.exists(_.startsWith("CASINO_REPORTING_ZONE")))
    assert(Settings.fromEnvironment(brand + ("CLOSE_GRACE" -> "soon")).left.exists(_.startsWith("CLOSE_GRACE")))
    assert(Settings.fromEnvironment(brand + ("REPORTING_GENERATION" -> "0")).left.exists(_.startsWith("REPORTING_GENERATION")))
    assert(Settings.fromEnvironment(brand + ("CASINO_REAL_MONEY" -> "yes")).left.exists(_.startsWith("CASINO_REAL_MONEY")))
  }

  test("the grace reads as attribution's does") {
    assertEquals(Settings.parseDuration("15m"), Some(Duration.ofMinutes(15)))
    assertEquals(Settings.parseDuration("500ms"), Some(Duration.ofMillis(500)))
    assertEquals(Settings.parseDuration("0m"), None)
  }
