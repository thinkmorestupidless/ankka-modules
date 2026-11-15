package io.eitheror.casino.backoffice

class SettingsSuite extends munit.FunSuite:

  private val env = Map(
    "CASINO_BRAND"       -> "goldvibe",
    "ANKKA_AUTH_STAFF_ISSUER"   -> "https://auth.example/realms/eitheror/",
    "BACKOFFICE_HOST" -> "https://backoffice-goldvibe.example"
  )

  test("the brand's client is backoffice-<slug> unless named"):
    val s = Settings.fromEnvironment(env).toOption.get
    assertEquals(s.client, "backoffice-goldvibe")
    assertEquals(s.tokenUrl.toString, "https://auth.example/realms/eitheror/protocol/openid-connect/token")
    assertEquals(s.callback.toString, "https://backoffice-goldvibe.example/auth/callback")

  test("a missing setting is refused by its name"):
    assert(Settings.fromEnvironment(env - "ANKKA_AUTH_STAFF_ISSUER").left.exists(_.startsWith("ANKKA_AUTH_STAFF_ISSUER")))
    assert(Settings.fromEnvironment(env.updated("BACKOFFICE_HOST", "ftp://x")).left.exists(_.contains("not an http")))

  test("the client's secret is read from BACKOFFICE_CLIENT_SECRET and never printed") {
    val s = Settings.fromEnvironment(env.updated("BACKOFFICE_CLIENT_SECRET", "s3cret")).toOption.get
    assertEquals(s.clientSecret, "s3cret")
    assert(!s.toString.contains("s3cret"), s.toString)
  }
