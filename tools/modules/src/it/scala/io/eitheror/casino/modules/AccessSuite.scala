package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.controlplane.api.{EnvVar, ServiceDescriptor, ServiceSpec}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * The route classes on a real installation (quickstart section 5): the sample service
 * (tests/fixtures/sample-service) deployed into a brand, called with tokens from the casino's own
 * Keycloak. Runs only with CASINO_IT=1 and:
 *   - CASINO_IT_BRAND: a brand already applied there (its realm and `brand_<slug>` role exist)
 *   - CASINO_KEYCLOAK_URL, and test users with direct grants: CASINO_IT_PLAYER (in casino-<slug>),
 *     CASINO_IT_OTHER_PLAYER (in another brand's realm), CASINO_IT_OPERATOR (eitheror, role
 *     brand_<slug>), CASINO_IT_STRANGER (eitheror, no brand role); each `user:password`
 *   - CASINO_IT_GATEWAY: the base the gateway serves exposed services under (`https://` + base)
 */
class AccessSuite extends munit.FunSuite:

  private val enabled = sys.env.get("CASINO_IT").contains("1") && sys.env.contains("CASINO_IT_BRAND")
  override def munitTests() = if enabled then super.munitTests() else Seq.empty

  private lazy val brand    = sys.env("CASINO_IT_BRAND")
  private lazy val keycloak = sys.env("CASINO_KEYCLOAK_URL").stripSuffix("/")
  private lazy val gateway  = sys.env("CASINO_IT_GATEWAY").stripSuffix("/")
  private val http = HttpClient.newHttpClient()

  private def token(realm: String, credentials: String): String =
    val Array(user, password) = credentials.split(":", 2): @unchecked
    val form = s"grant_type=password&client_id=casino-frontend&username=$user&password=$password"
    val response = http.send(
      HttpRequest.newBuilder(URI.create(s"$keycloak/realms/$realm/protocol/openid-connect/token"))
        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
      HttpResponse.BodyHandlers.ofString())
    assertEquals(response.statusCode, 200, response.body)
    com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body).get("access_token").asText()

  private def call(route: String, bearer: Option[String], segment: String = brand): Int =
    val builder = HttpRequest.newBuilder(URI.create(s"https://sample-service-$brand.$gateway/v1/casino/brands/$segment/access/$route"))
    bearer.foreach(t => builder.header("Authorization", s"Bearer $t"))
    http.send(builder.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode

  override def beforeAll(): Unit =
    if enabled then
      val cp = HttpControlPlane.fromEnvironment()
      val manifest = Manifest.load(Fixtures.root.resolve(s"brands/$brand/manifest.json")).toOption.collect { case b: BrandManifest => b }
        .getOrElse(Fixtures.brand("goldvibe").copy(slug = brand))
      val env = Render.descriptor(manifest, ServicePin("sample-service", "sample-service:latest")).service.env
      cp.apply(brand, ServiceDescriptor("sample-service", ServiceSpec("sample-service:latest", env = env :+ EnvVar("CASINO_BRAND", Some(brand)),
        runtime = Some("0.13.0"), database = Some("none"))))
      cp.expose(brand, "sample-service")
      Thread.sleep(60000)

  test("a player of the brand is served; a player of another brand and a visitor are refused") {
    assertEquals(call("player", Some(token(s"casino-$brand", sys.env("CASINO_IT_PLAYER")))), 200)
    assert(Set(401, 403).contains(call("player", Some(token("casino-spinvibe", sys.env("CASINO_IT_OTHER_PLAYER"))))))
    assertEquals(call("player", None), 401)
  }

  test("an operator is served where a role names the brand, refused otherwise") {
    assertEquals(call("operator", Some(token("eitheror", sys.env("CASINO_IT_OPERATOR")))), 200)
    assertEquals(call("operator", Some(token("eitheror", sys.env("CASINO_IT_STRANGER")))), 403)
  }

  test("a public route is served; a service route is refused to the internet") {
    assertEquals(call("public", None), 200)
    assertEquals(call("service", None), 403)
  }

  test("a request about another brand is refused naming this one") {
    assertEquals(call("public", None, segment = "not-this-brand"), 404)
  }
