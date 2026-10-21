package io.eitheror.casino.brands

import java.nio.file.{Files, Path}

class ControlPlaneSuite extends munit.FunSuite:

  private def home(config: String, credentials: Option[String]): Path =
    val dir = Files.createTempDirectory("ankka-home")
    Files.createDirectories(dir.resolve(".ankka"))
    Files.writeString(dir.resolve(".ankka").resolve("config.json"), config)
    credentials.foreach(c => Files.writeString(dir.resolve(".ankka").resolve("credentials.json"), c))
    dir

  private val config = """{"url":"https://api.127.0.0.1.sslip.io:8443","project":"p","ca":"/ca.crt"}"""
  private val credentials =
    """{"https://api.ankka.cloud":{"accessToken":"cloud-token","expiresAt":1},
      | "https://api.127.0.0.1.sslip.io:8443":{"issuer":"i","clientId":"ankka-cli","refreshToken":"r","accessToken":"local-token","expiresAt":2}}""".stripMargin

  test("one `ankka login` serves the tool: the CLI's access token for the configured control plane") {
    assertEquals(HttpControlPlane.settings(Map.empty, home(config, Some(credentials))),
      ("https://api.127.0.0.1.sslip.io:8443", Some("local-token"), Some("/ca.crt")))
  }

  test("ANKKA_URL picks the control plane, and so whose token is read; ANKKA_TOKEN overrides the login") {
    val h = home(config, Some(credentials))
    assertEquals(HttpControlPlane.settings(Map("ANKKA_URL" -> "https://api.ankka.cloud/"), h)._2, Some("cloud-token"))
    assertEquals(HttpControlPlane.settings(Map("ANKKA_TOKEN" -> "given"), h)._2, Some("given"))
  }

  test("with no login for the control plane there is no token") {
    assertEquals(HttpControlPlane.settings(Map.empty, home(config, None))._2, None)
    assertEquals(HttpControlPlane.settings(Map("ANKKA_URL" -> "https://elsewhere"), home(config, Some(credentials)))._2, None)
  }
