package io.eitheror.casino.modules

import io.eitheror.casino.contract.access.BrandRoles
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
  /** The realm's representation as the admin API answers it; `None` when there is no such realm. */
  def realmSettings(realm: String): Option[String]
  def importRealm(json: String): Unit
  def roleExists(realm: String, role: String): Boolean
  def createRole(realm: String, role: String, description: String): Unit
  // spec 032 R7: carrying a role with no brand in its name over to the brands its holder works in
  /** The ids of the realm's users who hold `role` directly. */
  def usersWithRole(realm: String, role: String): Vector[String]
  /** The realm roles a user holds directly. */
  def rolesOf(realm: String, userId: String): Set[String]
  def assign(realm: String, userId: String, role: String): Unit
  def remove(realm: String, userId: String, role: String): Unit
  /** The client's representation as the admin API answers it, its secret included; `None` when the realm has no such client. */
  def client(realm: String, clientId: String): Option[String]
  def clientExists(realm: String, clientId: String): Boolean = client(realm, clientId).isDefined
  def createClient(realm: String, json: String): Unit
  /**
   * Changes the fields `patch` names on an existing client and leaves every other field as it is:
   * Keycloak's `PUT /clients/{id}` keeps a field the representation omits, and merges `attributes`
   * (probed on Keycloak 24.0.5, the installation's, 2026-11-17).
   */
  def updateClient(realm: String, clientId: String, patch: String): Unit
  def calls: Vector[String]

object Realm:

  val OperatorsRealm = "eitheror"

  /** The original's template with the brand substituted, and nothing else changed (brands/_realm/README.md). */
  def render(template: String, slug: String, frontendBaseUrl: String): String =
    template.replace("${brandId}", slug).replace("${frontendBaseUrl}", frontendBaseUrl)

  /** The confidential client the brand's `players` takes its admin token with (spec 008 FR-007). */
  val PlayersAdmin = "players-admin"

  /** The template's public client a player signs in with, and the audience mapper [[withPlayers]] gives it. */
  val PlayerClient         = "casino-frontend"
  val PlayerAudienceMapper = "player-audience"

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
   *
   * And it gives the player client ([[PlayerClient]]) an audience mapper naming the realm, the
   * `auth-player-audience` the services verify: ankka checks a token's `aud`, and the template's client
   * sets only `azp`, which the original accepted instead (found on 010's local run, 2026-10-28).
   */
  def withPlayers(realmJson: String, playersUrl: Option[String], secrets: Map[String, String]): String =
    val mapper = ObjectMapper()
    val realm  = mapper.readTree(realmJson) match
      case o: ObjectNode => o
      case other         => throw IllegalArgumentException(s"the brand's realm is not a JSON object: ${other.getNodeType}")
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
    clients.elements().asScala.collect { case c: ObjectNode if c.path("clientId").asText() == PlayerClient => c }.foreach { c =>
      val mappers = Option(c.get("protocolMappers")).collect { case a: ArrayNode => a }.getOrElse(c.putArray("protocolMappers"))
      if !mappers.elements().asScala.exists(_.path("name").asText() == PlayerAudienceMapper) then
        val m = mappers.addObject()
        m.put("name", PlayerAudienceMapper).put("protocol", "openid-connect").put("protocolMapper", "oidc-audience-mapper")
        m.putObject("config").put("included.custom.audience", realm.path("realm").asText())
          .put("access.token.claim", "true").put("id.token.claim", "false")
    }
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

  /** The brand's backoffice client in `eitheror` (spec 030 D3): `backoffice-<slug>`. */
  def backofficeClientId(slug: String): String = s"backoffice-$slug"

  /** The default roles' descriptions, as `brands/_realm/eitheror-operators.json` gives them none. */
  private val DefaultRoleDescriptions = Map(
    "brand_support"    -> "Reads a player's account and writes notes and messages (spec 030 role matrix)",
    "brand_finance"    -> "Money: adjustments, withdrawals, rewards and the finance reports (spec 030 role matrix)",
    "brand_compliance" -> "A player's standing, identity, protection and fraud (spec 030 role matrix)",
    "brand_admin"      -> "Everything, the brand's configuration and its roles (spec 030 role matrix)"
  )

  /**
   * The confidential client the brand's backoffice signs its operators in with (spec 030 D3, research R8):
   * the standard flow with PKCE (S256), redirected only to `<address>/auth/callback`, signed out back to
   * the address, and the realm's default scopes, so its access token carries the `eitheror-casino`
   * audience every casino service checks and the operator's realm roles (V4). `secret` is the brand's
   * `<slug>-backoffice` `CLIENT_SECRET`; without it Keycloak mints one, which `backoffice-client-secret`
   * must then be set to.
   */
  def backofficeClient(slug: String, address: String, secret: Option[String]): String =
    val mapper = ObjectMapper()
    val base   = address.stripSuffix("/")
    val c      = mapper.createObjectNode()
    c.put("clientId", backofficeClientId(slug)).put("name", s"Backoffice of brand $slug").put("enabled", true)
      .put("protocol", "openid-connect").put("publicClient", false).put("clientAuthenticatorType", "client-secret")
      .put("standardFlowEnabled", true).put("directAccessGrantsEnabled", false).put("implicitFlowEnabled", false)
      .put("serviceAccountsEnabled", false).put("frontchannelLogout", false).put("rootUrl", base).put("baseUrl", base)
    secret.foreach(c.put("secret", _))
    c.putArray("redirectUris").add(s"$base/auth/callback")
    c.putArray("webOrigins").add(base)
    val attributes = c.putObject("attributes")
    attributes.put("pkce.code.challenge.method", "S256").put("post.logout.redirect.uris", s"$base/*")
    val scopes = c.putArray("defaultClientScopes")
    Seq("roles", "profile", "eitheror-casino-audience").foreach(scopes.add)
    mapper.writeValueAsString(c)

  private val jsonMapper = ObjectMapper()

  /** The client fields that follow the brand's address, and the attribute among them. */
  private val AddressFields    = Vector("rootUrl", "baseUrl", "redirectUris", "webOrigins")
  private val LogoutAttribute  = "post.logout.redirect.uris"

  /**
   * What of `desired` an existing client lacks: the address-derived fields, the post-logout address and,
   * when `desired` has one, the secret. The patch names only those, with the client id; the changed
   * fields are named, never a value.
   */
  def clientPatch(existing: com.fasterxml.jackson.databind.JsonNode, desired: com.fasterxml.jackson.databind.JsonNode): (ObjectNode, Vector[String]) =
    val patch   = jsonMapper.createObjectNode().put("clientId", desired.path("clientId").asText())
    val changed = mutable.ArrayBuffer.empty[String]
    def same(a: com.fasterxml.jackson.databind.JsonNode, b: com.fasterxml.jackson.databind.JsonNode): Boolean =
      if a.isArray && b.isArray then a.elements().asScala.map(_.asText).toSet == b.elements().asScala.map(_.asText).toSet
      else a.asText() == b.asText() && a.isMissingNode == b.isMissingNode
    AddressFields.foreach { f =>
      if !same(existing.path(f), desired.path(f)) then
        patch.set(f, desired.get(f)): Unit
        changed += f
    }
    val logout = desired.path("attributes").path(LogoutAttribute)
    if existing.path("attributes").path(LogoutAttribute).asText() != logout.asText() then
      patch.putObject("attributes").put(LogoutAttribute, logout.asText()): Unit
      changed += LogoutAttribute
    if desired.has("secret") && existing.path("secret").asText() != desired.path("secret").asText() then
      patch.put("secret", desired.path("secret").asText()): Unit
      changed += "secret"
    (patch, changed.toVector)

  /**
   * The backoffice retries a refresh whose answer was lost with the same refresh token (backoffice-state's
   * `SessionRefresh`), which a realm rotating refresh tokens refuses, signing the operator out. The
   * operators' realm the tool imports leaves rotation off (`brands/_realm/eitheror-operators.json`); one
   * that has it on is refused here, naming the setting, rather than signing operators out at random.
   */
  private def refuseRotation(admin: RealmAdmin): Unit =
    admin.realmSettings(OperatorsRealm).flatMap(t => Try(jsonMapper.readTree(t)).toOption).filter(_.path("revokeRefreshToken").asBoolean(false))
      .foreach { _ =>
        throw ControlPlaneError(409, s"realm $OperatorsRealm rotates refresh tokens (revokeRefreshToken is on): the backoffice " +
          "retries a refresh whose answer was lost with the same token, so an operator would be signed out. Turn it off in the realm's " +
          "Tokens settings, then apply again.")
      }

  /** The operators' realm an installation must hold before a brand's role is added to it. */
  def operators(root: Path): String =
    Files.readString(root.resolve("brands").resolve("_realm").resolve("eitheror-operators.json"))

  /** The roles that hold in one brand and are named for it (spec 032): the default roles, and those routes check by name. */
  val BrandRoleNames: Vector[String] =
    (io.eitheror.casino.contract.access.OperatorPermissions.DefaultRoles.keys.toVector ++ BrandRoles.Named).sorted

  /** `brand_<b>` names a brand; a default role (`brand_admin`) does not. */
  private def brandOfRole(role: String): Option[String] =
    Option.when(role.startsWith("brand_") && !role.contains(BrandRoles.Separator) &&
      !io.eitheror.casino.contract.access.OperatorPermissions.DefaultRoles.contains(role))(role.stripPrefix("brand_"))

  /**
   * FR-013: an operator of this brand holding one of [[BrandRoleNames]] with no brand in its name is given it
   * named for this brand; once they hold it named for every brand they work in, the unnamed one is removed.
   * A second apply finds nothing to do. One line per change.
   */
  def carryOver(admin: RealmAdmin, slug: String): Vector[String] =
    val made = mutable.ArrayBuffer.empty[String]
    for base <- BrandRoleNames; user <- admin.usersWithRole(OperatorsRealm, base).sorted do
      val held  = admin.rolesOf(OperatorsRealm, user)
      val named = BrandRoles.nameIn(slug, base)
      val now =
        if held(s"brand_$slug") && !held(named) then
          admin.assign(OperatorsRealm, user, named)
          made += s"role $base of user $user carried over to $named"
          held + named
        else held
      val brands = now.flatMap(brandOfRole)
      if brands.nonEmpty && brands.forall(b => now(BrandRoles.nameIn(b, base))) then
        admin.remove(OperatorsRealm, user, base)
        made += s"role $base of user $user removed: held named for ${brands.toVector.sorted.mkString(", ")}"
    made.toVector

  /**
   * Creates what is missing; changes nothing that exists. Returns one line per change. The operators' realm is
   * imported first when the installation lacks it and one is given, so the brand's role has a realm to go in.
   */
  def ensure(admin: RealmAdmin, slug: String, realmJson: => String, operators: Option[String] = None,
      backoffice: Option[(String, Option[String])] = None): Vector[String] =
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
    // The brand's compliance and re-identification roles on the shared audit (spec 018 FR-016), granted
    // only through the audit.
    Vector(
      s"audit_compliance_$slug" -> s"Reads brand $slug's records in the shared audit",
      s"reidentify_$slug" -> s"Re-identifies pseudonyms of brand $slug in the shared audit"
    ).foreach { (name, description) =>
      if !admin.roleExists(OperatorsRealm, name) then
        admin.createRole(OperatorsRealm, name, description)
        made += s"role $name added to realm $OperatorsRealm"
    }
    // spec 030 D3: the default roles (brand_support is new to a realm imported before it), and the brand's
    // backoffice client once the brand has a backoffice address
    io.eitheror.casino.contract.access.OperatorPermissions.DefaultRoles.keys.toVector.sorted.foreach { name =>
      if !admin.roleExists(OperatorsRealm, name) then
        admin.createRole(OperatorsRealm, name, DefaultRoleDescriptions.getOrElse(name, name))
        made += s"role $name added to realm $OperatorsRealm"
    }
    // spec 032 FR-010, FR-012: the roles that hold in this brand, named for it
    BrandRoleNames.foreach { base =>
      val name = BrandRoles.nameIn(slug, base)
      if !admin.roleExists(OperatorsRealm, name) then
        admin.createRole(OperatorsRealm, name, s"${DefaultRoleDescriptions.getOrElse(base, base)} in brand $slug")
        made += s"role $name added to realm $OperatorsRealm"
    }
    made ++= carryOver(admin, slug)
    backoffice.foreach { (address, secret) =>
      refuseRotation(admin)
      val id      = backofficeClientId(slug)
      val desired = jsonMapper.readTree(backofficeClient(slug, address, secret))
      admin.client(OperatorsRealm, id) match
        case None =>
          admin.createClient(OperatorsRealm, jsonMapper.writeValueAsString(desired))
          made += s"client $id added to realm $OperatorsRealm" + (if secret.isEmpty then " (its secret minted: set backoffice-client-secret to it)" else "")
        case Some(existing) =>
          // the address-derived fields follow the brand's address, and the secret is the brand's (`<slug>-backoffice`
          // CLIENT_SECRET, also its `backoffice-client-secret`): a client made without it, whose secret Keycloak
          // minted, is given it, so the two always agree
          val (patch, changed) = clientPatch(jsonMapper.readTree(existing), desired)
          if changed.nonEmpty then
            admin.updateClient(OperatorsRealm, id, jsonMapper.writeValueAsString(patch))
            made += s"client $id in realm $OperatorsRealm updated: ${changed.mkString(", ")}"
    }
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
      .header("Content-Type", "application/x-www-form-urlencoded").timeout(Duration.ofSeconds(30))
      .POST(HttpRequest.BodyPublishers.ofString(form)).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode != 200 then throw ControlPlaneError(response.statusCode, s"Keycloak refused the administrator: ${response.body}")
    mapper.readTree(response.body).get("access_token").asText()

  private def enc(s: String) = java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)

  /** Every admin call's bound, beside the connection's. */
  private val RequestTimeout = Duration.ofSeconds(30)

  private def exchange(method: String, path: String, body: Option[String]): HttpResponse[String] =
    log += s"$method $path"
    val builder = HttpRequest.newBuilder(URI.create(s"${base.stripSuffix("/")}/admin$path"))
      .header("Authorization", s"Bearer $token").timeout(RequestTimeout)
    body match
      case Some(b) => builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(b))
      case None    => builder.method(method, HttpRequest.BodyPublishers.noBody())
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  private def send(method: String, path: String, body: Option[String]): Int = exchange(method, path, body).statusCode

  private def created(status: Int, what: String): Unit =
    if status != 201 && status != 204 then throw ControlPlaneError(status, s"Keycloak did not create $what ($status)")

  def realmExists(realm: String) = send("GET", s"/realms/$realm", None) == 200
  def realmSettings(realm: String): Option[String] =
    val r = exchange("GET", s"/realms/$realm", None)
    Option.when(r.statusCode == 200)(r.body)
  def importRealm(json: String)  = created(send("POST", "/realms", Some(json)), "the realm")
  /** One path segment: a role's `@`, or anything else in a name, reaches the one resource. */
  private def seg(s: String) = enc(s).replace("+", "%20")

  def roleExists(realm: String, role: String) = send("GET", s"/realms/$realm/roles/${seg(role)}", None) == 200
  def usersWithRole(realm: String, role: String): Vector[String] =
    val r = exchange("GET", s"/realms/$realm/roles/${seg(role)}/users?first=0&max=10000", None)
    if r.statusCode == 404 then Vector.empty
    else if r.statusCode != 200 then throw ControlPlaneError(r.statusCode, s"Keycloak did not list the users holding $role")
    else mapper.readTree(r.body).elements().asScala.map(_.path("id").asText()).filter(_.nonEmpty).toVector
  def rolesOf(realm: String, userId: String): Set[String] =
    val r = exchange("GET", s"/realms/$realm/users/${seg(userId)}/role-mappings/realm", None)
    if r.statusCode != 200 then throw ControlPlaneError(r.statusCode, s"Keycloak did not list the roles of user $userId")
    mapper.readTree(r.body).elements().asScala.map(_.path("name").asText()).toSet
  private def mapping(method: String, realm: String, userId: String, role: String): Unit =
    val rep = exchange("GET", s"/realms/$realm/roles/${seg(role)}", None)
    if rep.statusCode != 200 then throw ControlPlaneError(rep.statusCode, s"Keycloak has no role $role")
    val status = send(method, s"/realms/$realm/users/${seg(userId)}/role-mappings/realm", Some(s"[${rep.body}]"))
    if status != 204 && status != 200 then throw ControlPlaneError(status, s"Keycloak did not change role $role of user $userId ($status)")
  def assign(realm: String, userId: String, role: String): Unit = mapping("POST", realm, userId, role)
  def remove(realm: String, userId: String, role: String): Unit = mapping("DELETE", realm, userId, role)
  def createRole(realm: String, role: String, description: String) =
    created(send("POST", s"/realms/$realm/roles", Some(mapper.writeValueAsString(java.util.Map.of("name", role, "description", description)))), s"role $role")
  def client(realm: String, clientId: String): Option[String] =
    val r = exchange("GET", s"/realms/$realm/clients?clientId=${enc(clientId)}", None)
    if r.statusCode != 200 then None
    else mapper.readTree(r.body).elements().asScala.find(_.path("clientId").asText() == clientId).map(mapper.writeValueAsString)
  def createClient(realm: String, json: String) = created(send("POST", s"/realms/$realm/clients", Some(json)), "the client")
  def updateClient(realm: String, clientId: String, patch: String): Unit =
    val internal = client(realm, clientId).map(c => mapper.readTree(c).path("id").asText()).filter(_.nonEmpty)
      .getOrElse(throw ControlPlaneError(404, s"Keycloak has no client $clientId in realm $realm to update"))
    val status = send("PUT", s"/realms/$realm/clients/${enc(internal)}", Some(patch))
    if status != 204 && status != 200 then throw ControlPlaneError(status, s"Keycloak did not update client $clientId ($status)")

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
  /** Each realm's users and the roles they hold directly. */
  val users = mutable.Map.empty[(String, String), mutable.Set[String]]
  def usersWithRole(realm: String, role: String) =
    log += s"GET /realms/$realm/roles/$role/users"
    users.collect { case ((`realm`, id), rs) if rs(role) => id }.toVector.sorted
  def rolesOf(realm: String, userId: String) =
    log += s"GET /realms/$realm/users/$userId/role-mappings/realm"
    users.get(realm -> userId).map(_.toSet).getOrElse(Set.empty)
  def assign(realm: String, userId: String, role: String) =
    log += s"POST /realms/$realm/users/$userId/role-mappings/realm"
    if !roles.get(realm).exists(_(role)) then throw ControlPlaneError(404, s"no role $role in realm $realm")
    users.getOrElseUpdate(realm -> userId, mutable.Set.empty) += role
  def remove(realm: String, userId: String, role: String) =
    log += s"DELETE /realms/$realm/users/$userId/role-mappings/realm"
    users.get(realm -> userId).foreach(_ -= role)
  val clients = mutable.Map.empty[(String, String), String]
  def realmSettings(realm: String) = { log += s"GET /realms/$realm"; realms.get(realm) }
  def client(realm: String, clientId: String) = { log += s"GET /realms/$realm/clients?clientId=$clientId"; clients.get(realm -> clientId) }
  def createClient(realm: String, json: String) =
    log += s"POST /realms/$realm/clients"
    clients((realm, mapper.readTree(json).get("clientId").asText())) = json
  /** As Keycloak's PUT: the fields `patch` names replace the client's, `attributes` merged. */
  def updateClient(realm: String, clientId: String, patch: String) =
    log += s"PUT /realms/$realm/clients/$clientId"
    val existing = clients.get(realm -> clientId).map(mapper.readTree).collect { case o: ObjectNode => o }
      .getOrElse(throw ControlPlaneError(404, s"no client $clientId in realm $realm"))
    mapper.readTree(patch).properties().asScala.foreach { e =>
      (e.getKey, existing.get(e.getKey), e.getValue) match
        case ("attributes", current: ObjectNode, named: ObjectNode) => current.setAll[ObjectNode](named: ObjectNode): Unit
        case (key, _, value)                                        => existing.set(key, value): Unit
    }
    clients((realm, clientId)) = mapper.writeValueAsString(existing)
