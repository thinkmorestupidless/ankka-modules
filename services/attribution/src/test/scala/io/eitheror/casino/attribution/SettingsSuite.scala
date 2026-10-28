package io.eitheror.casino.attribution

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.{Duration, ZoneId}

class SettingsSuite extends munit.FunSuite with LogCapturing:
  private val brand = Map("CASINO_BRAND" -> "goldvibe")

  test("the defaults: UTC, no modules, CF-IPCountry, 10 s documents, a 15 minute grace, 10 s webhooks") {
    val s = Settings.fromEnvironment(brand).toOption.get
    assertEquals(s, Settings("goldvibe"))
    assertEquals(s.reportingZone, ZoneId.of("UTC"))
    assertEquals(s.countryHeader, "CF-IPCountry")
    assertEquals(s.documentTimeout, Duration.ofSeconds(10))
    assertEquals(s.closeGrace, Duration.ofMinutes(15))
    assertEquals(s.webhookTimeout, Duration.ofSeconds(10))
    assert(!s.casino)
  }

  test("every variable is read") {
    val s = Settings.fromEnvironment(brand ++ Map("CASINO_REPORTING_ZONE" -> "Africa/Johannesburg", "CASINO_MODULES" -> "casino, reporting",
      "COUNTRY_HEADER" -> "X-Country", "DOCUMENT_TIMEOUT" -> "3s", "CLOSE_GRACE" -> "30m", "WEBHOOK_TIMEOUT" -> "500ms")).toOption.get
    assertEquals(s.reportingZone, ZoneId.of("Africa/Johannesburg"))
    assertEquals(s.modules, Set("casino", "reporting"))
    assert(s.casino)
    assertEquals(s.countryHeader, "X-Country")
    assertEquals(s.documentTimeout, Duration.ofSeconds(3))
    assertEquals(s.closeGrace, Duration.ofMinutes(30))
    assertEquals(s.webhookTimeout, Duration.ofMillis(500))
  }

  test("a value that cannot be read is refused naming its variable") {
    assert(Settings.fromEnvironment(Map.empty).left.exists(_.startsWith("CASINO_BRAND")))
    assert(Settings.fromEnvironment(brand + ("CASINO_REPORTING_ZONE" -> "Mars/Olympus")).left.exists(_.startsWith("CASINO_REPORTING_ZONE")))
    assert(Settings.fromEnvironment(brand + ("CLOSE_GRACE" -> "soon")).left.exists(_.startsWith("CLOSE_GRACE")))
    assert(Settings.fromEnvironment(brand + ("DOCUMENT_TIMEOUT" -> "0s")).left.exists(_.startsWith("DOCUMENT_TIMEOUT")))
  }

  test("an empty module list runs the core alone") {
    assertEquals(Settings.fromEnvironment(brand + ("CASINO_MODULES" -> "")).toOption.get.modules, Set.empty[String])
  }
