package io.eitheror.casino.publicapi

import java.time.Duration

class SettingsSuite extends munit.FunSuite:

  test("the brand is required and names the variable") {
    assertEquals(Settings.fromEnvironment(Map.empty).left.toOption.exists(_.startsWith("CASINO_BRAND")), true)
  }

  test("the defaults: the brand's own project, no host, a five-second budget and a five-minute catalogue") {
    val s = Settings.fromEnvironment(Map("CASINO_BRAND" -> "goldvibe")).toOption.get
    assertEquals(s, Settings("goldvibe", "goldvibe", None, Duration.ofSeconds(5), Duration.ofMinutes(5)))
    assertEquals(s.serverUrl, "/v1")
  }

  test("the host gives the contract's server address, with /v1 appended once") {
    val s = Settings.fromEnvironment(Map("CASINO_BRAND" -> "goldvibe", "PUBLIC_API_HOST" -> "https://api.goldvibe.example/")).toOption.get
    assertEquals(s.serverUrl, "https://api.goldvibe.example/v1")
  }

  test("a host that is not an address, or a budget that is not a duration, is refused naming the variable") {
    assert(Settings.fromEnvironment(Map("CASINO_BRAND" -> "g", "PUBLIC_API_HOST" -> "api.example")).left.toOption.exists(_.startsWith("PUBLIC_API_HOST")))
    assert(Settings.fromEnvironment(Map("CASINO_BRAND" -> "g", "PUBLIC_API_BUDGET" -> "soon")).left.toOption.exists(_.startsWith("PUBLIC_API_BUDGET")))
  }
