package io.eitheror.casino.modules

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

  test("a grant is sent as the control plane's target object and read back in the tool's form (spec 005 T072)") {
    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    val route  = mapper.readTree(GrantJson.request(GrantRequest("service:goldvibe/payouts", "route:psp-gateway POST /v1/payments/withdrawals")))
    assertEquals(route.get("grantee").asText(), "service:goldvibe/payouts")
    assertEquals(route.get("target").get("kind").asText(), "route")
    assertEquals(route.get("target").get("service").asText(), "psp-gateway")
    assertEquals(route.get("target").get("method").asText(), "POST")
    assertEquals(route.get("target").get("path").asText(), "/v1/payments/withdrawals")
    val topic = mapper.readTree(GrantJson.request(GrantRequest("service:reasoner/audit", "topic:platform.audit:consume"))).get("target")
    assertEquals(topic.get("kind").asText(), "topic")
    assertEquals(topic.get("topic").asText(), "platform.audit")
    assertEquals(topic.get("right").asText(), "consume")
    assertEquals(mapper.readTree(GrantJson.request(GrantRequest("service:goldvibe/players", "erasure"))).get("target").get("kind").asText(), "erasure")

    val listed = GrantJson.statuses(
      """[{"id":"1","grantee":"service:goldvibe/payouts","target":{"kind":"route","service":"psp-gateway","method":"POST","path":"/v1/payments/withdrawals"},"state":"accepted","effect":"route not seen"},
        | {"id":"2","grantee":"service:reasoner/audit","target":{"kind":"topic","topic":"platform.audit","right":"consume","decrypt":false},"state":"pending"},
        | {"id":"3","grantee":"service:goldvibe/players","target":{"kind":"erasure"},"state":"accepted","effect":"in effect"}]""".stripMargin)
    assertEquals(listed.map(g => g.principal -> g.target), Vector(
      "service:goldvibe/payouts" -> "route:psp-gateway POST /v1/payments/withdrawals",
      "service:reasoner/audit"   -> "topic:platform.audit:consume",
      "service:goldvibe/players" -> "erasure"))
    assertEquals(listed.map(_.effect), Vector("route not seen", "pending", "in effect"))
  }
