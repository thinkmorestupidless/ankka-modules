package io.eitheror.casino.backoffice

class SettingsSuite extends munit.FunSuite:

  private val env = Map(
    "CASINO_BRAND"       -> "goldvibe",
    "BACKOFFICE_REALM"   -> "https://auth.example/realms/eitheror/",
    "BACKOFFICE_ADDRESS" -> "https://backoffice-goldvibe.example"
  )

  test("the brand's client is backoffice-<slug> unless named"):
    val s = Settings.fromEnvironment(env).toOption.get
    assertEquals(s.client, "backoffice-goldvibe")
    assertEquals(s.tokenUrl.toString, "https://auth.example/realms/eitheror/protocol/openid-connect/token")
    assertEquals(s.callback.toString, "https://backoffice-goldvibe.example/auth/callback")

  test("a missing setting is refused by its name"):
    assert(Settings.fromEnvironment(env - "BACKOFFICE_REALM").left.exists(_.startsWith("BACKOFFICE_REALM")))
    assert(Settings.fromEnvironment(env.updated("BACKOFFICE_ADDRESS", "ftp://x")).left.exists(_.contains("not an http")))
