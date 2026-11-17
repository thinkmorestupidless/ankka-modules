package io.eitheror.casino.backoffice

class SettingsSuite extends munit.FunSuite:

  private val env = Map(
    "CASINO_BRAND"       -> "goldvibe",
    "ANKKA_AUTH_STAFF_ISSUER"   -> "https://auth.example/realms/eitheror/",
    "BACKOFFICE_HOST" -> "https://backoffice-goldvibe.example",
    "BACKOFFICE_CLIENT_SECRET" -> "s3cret"
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

  test("the service calls the realm at KEYCLOAK_URL when named, and sends the browser to the issuer") {
    val s = Settings.fromEnvironment(env.updated("KEYCLOAK_URL", "http://casino-keycloak.casino-identity.svc/")).toOption.get
    assertEquals(s.tokenUrl.toString, "http://casino-keycloak.casino-identity.svc/realms/eitheror/protocol/openid-connect/token")
    assertEquals(s.revokeUrl.toString, "http://casino-keycloak.casino-identity.svc/realms/eitheror/protocol/openid-connect/revoke")
    assertEquals(s.logoutUrl.toString, "http://casino-keycloak.casino-identity.svc/realms/eitheror/protocol/openid-connect/logout")
    assertEquals(s.authorizeUrl.toString, "https://auth.example/realms/eitheror/protocol/openid-connect/auth")
    assert(Settings.fromEnvironment(env.updated("KEYCLOAK_URL", "ftp://x")).left.exists(_.startsWith("KEYCLOAK_URL")))
  }

  test("an empty or missing client secret is refused at start, naming the variable, outside the explicit local mode") {
    Seq(env - "BACKOFFICE_CLIENT_SECRET", env.updated("BACKOFFICE_CLIENT_SECRET", "  ")).foreach { e =>
      val refused = Settings.fromEnvironment(e)
      assert(refused.left.exists(_.startsWith("BACKOFFICE_CLIENT_SECRET")), refused.toString)
    }
    val local = Settings.fromEnvironment(env - "BACKOFFICE_CLIENT_SECRET" + ("BACKOFFICE_LOCAL" -> "true"))
    assertEquals(local.map(_.clientSecret), Right(""))
  }
