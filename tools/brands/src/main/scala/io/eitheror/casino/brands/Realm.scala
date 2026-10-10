package io.eitheror.casino.brands

import com.fasterxml.jackson.databind.ObjectMapper

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration
import scala.collection.mutable

/** What the tool asks of the casino's Keycloak: a brand's realm, and its role in `eitheror`. */
trait RealmAdmin:
  def realmExists(realm: String): Boolean
  def importRealm(json: String): Unit
  def roleExists(realm: String, role: String): Boolean
  def createRole(realm: String, role: String, description: String): Unit
  def calls: Vector[String]

object Realm:

  val OperatorsRealm = "eitheror"

  /** The original's template with the brand substituted, and nothing else changed (brands/_realm/README.md). */
  def render(template: String, slug: String, frontendBaseUrl: String): String =
    template.replace("${brandId}", slug).replace("${frontendBaseUrl}", frontendBaseUrl)

  def template(root: Path): String =
    Files.readString(root.resolve("brands").resolve("_realm").resolve("casino-template.json"))

  /** Creates what is missing; changes nothing that exists. Returns one line per change. */
  def ensure(admin: RealmAdmin, slug: String, realmJson: => String): Vector[String] =
    val realm = s"casino-$slug"
    val role  = s"brand_$slug"
    val made = mutable.ArrayBuffer.empty[String]
    if !admin.realmExists(realm) then
      admin.importRealm(realmJson)
      made += s"realm $realm created"
    if !admin.roleExists(OperatorsRealm, role) then
      admin.createRole(OperatorsRealm, role, s"Operators of brand $slug")
      made += s"role $role added to realm $OperatorsRealm"
    made.toVector

/** Keycloak's admin REST API, as the master realm's administrator. */
final class KeycloakAdmin(base: String, username: String, password: String) extends RealmAdmin:

  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
  private val mapper = ObjectMapper()
  private val log    = mutable.ArrayBuffer.empty[String]
  def calls: Vector[String] = log.toVector

  private lazy val token: String =
    val form = s"grant_type=password&client_id=admin-cli&username=${enc(username)}&password=${enc(password)}"
    val request = HttpRequest.newBuilder(URI.create(s"${base.stripSuffix("/")}/realms/master/protocol/openid-connect/token"))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(form)).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode != 200 then throw ControlPlaneError(response.statusCode, s"Keycloak refused the administrator: ${response.body}")
    mapper.readTree(response.body).get("access_token").asText()

  private def enc(s: String) = java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)

  private def send(method: String, path: String, body: Option[String]): Int =
    log += s"$method $path"
    val builder = HttpRequest.newBuilder(URI.create(s"${base.stripSuffix("/")}/admin$path"))
      .header("Authorization", s"Bearer $token")
    body match
      case Some(b) => builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(b))
      case None    => builder.method(method, HttpRequest.BodyPublishers.noBody())
    client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode

  private def created(status: Int, what: String): Unit =
    if status != 201 && status != 204 then throw ControlPlaneError(status, s"Keycloak did not create $what ($status)")

  def realmExists(realm: String) = send("GET", s"/realms/$realm", None) == 200
  def importRealm(json: String)  = created(send("POST", "/realms", Some(json)), "the realm")
  def roleExists(realm: String, role: String) = send("GET", s"/realms/$realm/roles/$role", None) == 200
  def createRole(realm: String, role: String, description: String) =
    created(send("POST", s"/realms/$realm/roles", Some(mapper.writeValueAsString(java.util.Map.of("name", role, "description", description)))), s"role $role")

object KeycloakAdmin:
  /** The credential is never in a manifest: `CASINO_KEYCLOAK_ADMIN_USERNAME` and `_PASSWORD`. */
  def fromEnvironment(issuerBase: String): Either[String, KeycloakAdmin] =
    for
      user <- sys.env.get("CASINO_KEYCLOAK_ADMIN_USERNAME").toRight("CASINO_KEYCLOAK_ADMIN_USERNAME is not set")
      pass <- sys.env.get("CASINO_KEYCLOAK_ADMIN_PASSWORD").toRight("CASINO_KEYCLOAK_ADMIN_PASSWORD is not set")
    yield KeycloakAdmin(issuerBase, user, pass)

/** A Keycloak held in memory for the tests. */
final class InMemoryRealms extends RealmAdmin:
  val realms = mutable.Map.empty[String, String]
  val roles  = mutable.Map.empty[String, mutable.Set[String]]
  private val log = mutable.ArrayBuffer.empty[String]
  def calls: Vector[String] = log.toVector

  private val mapper = ObjectMapper()

  def realmExists(realm: String) = { log += s"GET /realms/$realm"; realms.contains(realm) }
  def importRealm(json: String) =
    log += "POST /realms"
    val name = mapper.readTree(json).get("realm").asText()
    realms(name) = json
  def roleExists(realm: String, role: String) = { log += s"GET /realms/$realm/roles/$role"; roles.get(realm).exists(_.contains(role)) }
  def createRole(realm: String, role: String, description: String) =
    log += s"POST /realms/$realm/roles"
    roles.getOrElseUpdate(realm, mutable.Set.empty) += role
