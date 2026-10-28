package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try

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

  /** The confidential client the brand's `players` takes its admin token with (spec 008 FR-007). */
  val PlayersAdmin = "players-admin"

  /**
   * What the brand's `players` needs of its realm (spec 008 contracts/realm.md), added to the rendered
   * template so the template stays the original's: the `players-admin` client with a service account
   * holding `realm-management`'s `manage-users`, `view-users` and `view-clients`, and the realm attributes
   * the plugins read, `players_url` and the two shared secrets. `secrets` is the brand's
   * `<slug>-players` entry of the secrets file; a value it lacks is left out, and the client then has a
   * secret Keycloak mints, which `players-realm-admin` must be set to.
   *
   * It also turns on refresh-token rotation (`revokeRefreshToken`, no reuse), which the template leaves
   * off: the original accepts a refresh token used twice (recorded 2026-10-26), and spec 008 answers a
   * reused one `refresh_expired`.
   *
   * And it declares the realm's user profile ([[PlayersProfile]]), which the template leaves to Keycloak
   * 24's default: that default drops `eitheror_twin_id`, the attribute `players` writes on the user it
   * creates and finds the user by, and requires a first and last name a registration need not give, so
   * every sign-in stops at `VERIFY_PROFILE` (found on the local run, 2026-10-27).
   */
  def withPlayers(realmJson: String, playersUrl: Option[String], secrets: Map[String, String]): String =
    val mapper = ObjectMapper()
    val realm  = mapper.readTree(realmJson).asInstanceOf[ObjectNode]
    realm.put("revokeRefreshToken", true).put("refreshTokenMaxReuse", 0)
    val attributes = Option(realm.get("attributes")).collect { case o: ObjectNode => o }
      .getOrElse(realm.putObject("attributes"))
    playersUrl.foreach(attributes.put("players_url", _))
    secrets.get("REALM_SECRET").foreach(attributes.put("players_realm_secret", _))
    secrets.get("WEBHOOK_SECRET").foreach(attributes.put("players_webhook_secret", _))
    val clients = Option(realm.get("clients")).collect { case a: ArrayNode => a }
      .getOrElse(realm.putArray("clients"))
    if !clients.elements().asScala.exists(_.path("clientId").asText() == PlayersAdmin) then
      val c = clients.addObject()
      c.put("clientId", PlayersAdmin).put("enabled", true).put("publicClient", false).put("serviceAccountsEnabled", true)
        .put("standardFlowEnabled", false).put("directAccessGrantsEnabled", false).put("clientAuthenticatorType", "client-secret")
      // a bare secret or {clientId, clientSecret}, as players reads it
      secrets.get("REALM_ADMIN").map { s =>
        Try(mapper.readTree(s)).toOption.flatMap(n => Option(n).flatMap(n => Option(n.get("clientSecret")))).map(_.asText()).getOrElse(s)
      }.foreach(c.put("secret", _))
      val users = Option(realm.get("users")).collect { case a: ArrayNode => a }
        .getOrElse(realm.putArray("users"))
      val u = users.addObject()
      u.put("username", s"service-account-$PlayersAdmin").put("enabled", true).put("serviceAccountClientId", PlayersAdmin)
      val roles = u.putObject("clientRoles").putArray("realm-management")
      Seq("manage-users", "view-users", "view-clients").foreach(roles.add)
    val components = Option(realm.get("components")).collect { case o: ObjectNode => o }
      .getOrElse(realm.putObject("components"))
    if !components.has(UserProfileProvider) then
      val p = components.putArray(UserProfileProvider).addObject()
      p.put("providerId", "declarative-user-profile").putObject("subComponents")
      p.putObject("config").putArray("kc.user.profile.config").add(PlayersProfile)
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(realm)

  val UserProfileProvider = "org.keycloak.userprofile.UserProfileProvider"

  /**
   * Keycloak 24's default user profile with the names optional and `eitheror_twin_id` declared, seen and
   * edited by admins only (the token mapper still reads it).
   */
  val PlayersProfile: String =
    val mapper = ObjectMapper()
    val p      = mapper.createObjectNode()
    val all    = p.putArray("attributes")
    def attribute(name: String, display: String, required: Boolean, admins: Boolean)(validations: (String, ObjectNode => Unit)*): Unit =
      val a = all.addObject().put("name", name).put("displayName", display)
      val v = a.putObject("validations")
      validations.foreach((k, f) => f(v.putObject(k)))
      if required then a.putObject("required").putArray("roles").add("user")
      val who = if admins then Seq("admin") else Seq("admin", "user")
      val permissions = a.putObject("permissions")
      Seq("view", "edit").foreach(k => who.foreach(permissions.withArray(k).add))
      a.put("multivalued", false): Unit
    def length(max: Int, min: Option[Int] = None): (String, ObjectNode => Unit) =
      "length" -> (o => { min.foreach(o.put("min", _)); o.put("max", max): Unit })
    def none(k: String): (String, ObjectNode => Unit) = k -> (_ => ())
    attribute("username", "${username}", required = false, admins = false)(length(255, Some(3)), none("username-prohibited-characters"),
      none("up-username-not-idn-homograph"))
    attribute("email", "${email}", required = true, admins = false)(none("email"), length(255))
    attribute("firstName", "${firstName}", required = false, admins = false)(length(255), none("person-name-prohibited-characters"))
    attribute("lastName", "${lastName}", required = false, admins = false)(length(255), none("person-name-prohibited-characters"))
    attribute("eitheror_twin_id", "Player id", required = false, admins = true)()
    p.putArray("groups").addObject().put("name", "user-metadata").put("displayHeader", "User metadata")
      .put("displayDescription", "Attributes, which refer to user metadata")
    mapper.writeValueAsString(p)

  def template(root: Path): String =
    Files.readString(root.resolve("brands").resolve("_realm").resolve("casino-template.json"))

  /** The operators' realm an installation must hold before a brand's role is added to it. */
  def operators(root: Path): String =
    Files.readString(root.resolve("brands").resolve("_realm").resolve("eitheror-operators.json"))

  /**
   * Creates what is missing; changes nothing that exists. Returns one line per change. The operators' realm is
   * imported first when the installation lacks it and one is given, so the brand's role has a realm to go in.
   */
  def ensure(admin: RealmAdmin, slug: String, realmJson: => String, operators: Option[String] = None): Vector[String] =
    val realm = s"casino-$slug"
    val role  = s"brand_$slug"
    val made = mutable.ArrayBuffer.empty[String]
    operators.foreach { json =>
      if !admin.realmExists(OperatorsRealm) then
        admin.importRealm(json)
        made += s"realm $OperatorsRealm created"
    }
    if !admin.realmExists(realm) then
      admin.importRealm(realmJson)
      made += s"realm $realm created"
    if !admin.roleExists(OperatorsRealm, role) then
      admin.createRole(OperatorsRealm, role, s"Operators of brand $slug")
      made += s"role $role added to realm $OperatorsRealm"
    made.toVector

/** Keycloak's admin REST API, as the master realm's administrator. */
final class KeycloakAdmin(base: String, username: String, password: String, ca: Option[String] = None) extends RealmAdmin:

  private val client =
    val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
    ca.foreach(path => builder.sslContext(HttpControlPlane.trusting(path)))
    builder.build()
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
  /**
   * The credential is never in a manifest: `CASINO_KEYCLOAK_ADMIN_USERNAME` and `_PASSWORD`. The casino's
   * Keycloak is served through the installation's gateway, so it is trusted as the control plane is:
   * `CASINO_KEYCLOAK_CA`, else `ANKKA_CA` (the local installation's own authority). `CASINO_KEYCLOAK_URL`
   * names another Keycloak than the values' (a service's own, run beside it: spec 008 quickstart §6).
   */
  def fromEnvironment(issuerBase: String, env: Map[String, String] = sys.env): Either[String, KeycloakAdmin] =
    for
      user <- env.get("CASINO_KEYCLOAK_ADMIN_USERNAME").toRight("CASINO_KEYCLOAK_ADMIN_USERNAME is not set")
      pass <- env.get("CASINO_KEYCLOAK_ADMIN_PASSWORD").toRight("CASINO_KEYCLOAK_ADMIN_PASSWORD is not set")
    yield KeycloakAdmin(env.get("CASINO_KEYCLOAK_URL").filter(_.nonEmpty).getOrElse(issuerBase), user, pass,
      env.get("CASINO_KEYCLOAK_CA").orElse(env.get("ANKKA_CA")))

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
