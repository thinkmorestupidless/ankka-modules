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

  private val local = "https://api.127.0.0.1.sslip.io:8443"
  private def saved(token: String, expiresAt: Long) =
    s"""{"$local":{"issuer":"i","clientId":"ankka-cli","refreshToken":"r","accessToken":"$token","expiresAt":$expiresAt}}"""

  test("the CLI's login is read before each call and renewed when within 30 s of expiring; a given token never is") {
    val h     = home(config, Some(saved("old", 1000)))
    val file  = h.resolve(".ankka").resolve("credentials.json")
    var asked = 0
    var at    = java.time.Instant.ofEpochSecond(900)
    val bearer = Bearer.Login(file, local, () => { asked += 1; Files.writeString(file, saved(s"new-$asked", at.getEpochSecond + 300)): Unit }, () => at)
    assertEquals((bearer.current(), asked), (Some("old"), 0))
    at = java.time.Instant.ofEpochSecond(975)
    assertEquals((bearer.current(), asked), (Some("new-1"), 1))
    assertEquals((bearer.current(), asked), (Some("new-1"), 1), "a renewed token is not renewed again")
    val (_, fixed, _) = HttpControlPlane.resolve(Map("ANKKA_TOKEN" -> "fixed"), h, () => fail("a given token is not renewed"))
    assertEquals((fixed.current(), fixed.renew()), (Some("fixed"), false))
  }

  test("a 401 renews the login and asks once more; a second 401 is the error") {
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", ex => {
      val ok   = ex.getRequestHeaders.getFirst("Authorization") == "Bearer good"
      val body = (if ok then "[]" else """{"status":401}""").getBytes
      ex.sendResponseHeaders(if ok then 200 else 401, body.length.toLong)
      ex.getResponseBody.write(body); ex.close()
    })
    server.start()
    try
      val url  = s"http://127.0.0.1:${server.getAddress.getPort}"
      val dir  = Files.createTempDirectory("ankka-creds")
      val file = dir.resolve("credentials.json")
      def write(t: String) = Files.writeString(file, s"""{"$url":{"accessToken":"$t","expiresAt":${Long.MaxValue / 2000}}}""")
      write("expired")
      var renewed = 0
      val cp = HttpControlPlane(url, Bearer.Login(file, url, () => { renewed += 1; write("good"): Unit }), None)
      assertEquals((cp.services("goldvibe"), renewed), (Vector.empty, 1))
      write("expired")
      val stuck = HttpControlPlane(url, Bearer.Login(file, url, () => ()), None)
      assertEquals(intercept[ControlPlaneError](stuck.services("goldvibe")).status, 401)
    finally server.stop(0)
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
