package io.eitheror.casino.backoffice.it

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration
import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Spec 032 research R1: a realm role named for a brand, `<role>@<slug>`, is accepted by Keycloak's
 * admin API (created, found by name, assigned) and reaches an access token's `realm_access.roles`
 * unchanged. The image is the one `StateIt` runs.
 */
class RealmRoleNameIt extends munit.FunSuite:
  override val munitTimeout: FiniteDuration = 6.minutes

  private val Role   = "brand_finance@goldvibe"
  private val mapper = ObjectMapper()
  private val http   = HttpClient.newHttpClient()

  private val realm =
    """{
      |  "realm": "eitheror", "enabled": true,
      |  "roles": {"realm": [{"name": "brand_goldvibe"}]},
      |  "clients": [{"clientId": "probe", "enabled": true, "publicClient": true, "directAccessGrantsEnabled": true}],
      |  "users": [{
      |    "username": "ada", "enabled": true, "email": "ada@example.com", "emailVerified": true,
      |    "firstName": "Ada", "lastName": "Operator",
      |    "credentials": [{"type": "password", "value": "ada-password", "temporary": false}],
      |    "realmRoles": ["brand_goldvibe"]
      |  }]
      |}""".stripMargin

  final class Keycloak extends GenericContainer[Keycloak](DockerImageName.parse("quay.io/keycloak/keycloak:26.0.7"))

  private lazy val container: Keycloak =
    val c = Keycloak()
      .withExposedPorts(8080)
      .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
      .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
      .withCopyToContainer(Transferable.of(realm), "/opt/keycloak/data/import/eitheror.json")
      .withCommand("start-dev", "--import-realm")
      .waitingFor(Wait.forHttp("/realms/eitheror/.well-known/openid-configuration").forPort(8080)
        .withStartupTimeout(JDuration.ofMinutes(4)))
    c.start()
    c

  private def base = s"http://${container.getHost}:${container.getMappedPort(8080)}"
  private def enc(s: String) = URLEncoder.encode(s, UTF_8).replace("+", "%20")

  private def form(url: String, fields: (String, String)*): JsonNode =
    val body = fields.map((k, v) => s"$k=${enc(v)}").mkString("&")
    val r = http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(r.statusCode, 200, r.body)
    mapper.readTree(r.body)

  private def admin(method: String, path: String, token: String, body: Option[String] = None): HttpResponse[String] =
    val b = HttpRequest.newBuilder(URI.create(s"$base/admin/realms/eitheror$path")).header("Authorization", s"Bearer $token")
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString))
    body.foreach(_ => b.header("Content-Type", "application/json"))
    http.send(b.build(), HttpResponse.BodyHandlers.ofString())

  override def afterAll(): Unit = scala.util.Try(container.stop()): Unit

  test("a role named for a brand is created, assigned and carried in the token unchanged") {
    val token = form(s"$base/realms/master/protocol/openid-connect/token",
      "grant_type" -> "password", "client_id" -> "admin-cli", "username" -> "admin", "password" -> "admin").path("access_token").asText()

    val made = admin("POST", "/roles", token, Some(s"""{"name":"$Role","description":"probe"}"""))
    assertEquals(made.statusCode, 201, made.body)
    val found = admin("GET", s"/roles/${enc(Role)}", token)
    assertEquals(found.statusCode, 200, found.body)
    assertEquals(mapper.readTree(found.body).path("name").asText(), Role)

    val user = mapper.readTree(admin("GET", "/users?username=ada&exact=true", token).body).get(0).path("id").asText()
    val assigned = admin("POST", s"/users/$user/role-mappings/realm", token, Some(s"[${found.body}]"))
    assertEquals(assigned.statusCode, 204, assigned.body)
    val held = mapper.readTree(admin("GET", s"/users/$user/role-mappings/realm", token).body).elements().asScala.map(_.path("name").asText()).toSet
    assert(held.contains(Role), held.toString)

    val access = form(s"$base/realms/eitheror/protocol/openid-connect/token",
      "grant_type" -> "password", "client_id" -> "probe", "username" -> "ada", "password" -> "ada-password", "scope" -> "openid")
      .path("access_token").asText()
    val claims = mapper.readTree(Base64.getUrlDecoder.decode(access.split('.')(1)))
    val roles  = claims.path("realm_access").path("roles").elements().asScala.map(_.asText()).toSet
    assert(roles.contains(Role), roles.toString)
    assert(roles.contains("brand_goldvibe"), roles.toString)
  }
