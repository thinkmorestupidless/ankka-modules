package io.eitheror.casino.players.it

import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Base64, UUID}
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.*

/**
 * Keycloak for one brand realm, `casino-<slug>`, answering as Keycloak 24 does on the routes
 * `KeycloakRealm` calls (contracts/realm.md): the admin client's token, users by address with their
 * password, verified and enabled flags and the `eitheror_twin_id` attribute, the password and refresh
 * grants issuing HS256 tokens the kit's authenticator checks, sessions listed and revoked, reset and
 * action emails recorded. Knobs: `delayMillis`, `silent`, `refuse(address, reason)`, `minPassword`.
 */
final class FakeRealm(val slug: String, bind: Int = 0) extends FakeServer(bind):
  val realm         = s"casino-$slug"
  val adminClient   = "players-admin"
  val adminSecret   = "fake-admin-secret"
  val frontendId    = "frontend-internal-id"

  final case class User(id: String, var email: String, var password: String, playerId: String, var verified: Boolean = false,
      var enabled: Boolean = true, var actions: Vector[String] = Vector.empty)
  final case class Session(id: String, userId: String, started: Long, var lastAccess: Long, var refresh: String)

  val users    = ConcurrentHashMap[String, User]()
  val sessions = ConcurrentHashMap[String, Session]()
  private val usedRefresh = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  @volatile var delayMillis: Long = 0
  @volatile var silent: Boolean   = false
  @volatile var minPassword: Int  = 8
  @volatile var adminTokens: Int  = 0
  /** Grants issue tokens with no `session_state` (a realm misconfigured), for `missing_token`. */
  @volatile var withoutSession: Boolean = false
  private val refusals = ConcurrentHashMap[String, String]()

  /** The next grant for `address` is refused with Keycloak's `error_description`, e.g. "Account disabled". */
  def refuse(address: String, description: String): Unit = refusals.put(address.toLowerCase, description): Unit

  def reset(): Unit =
    users.clear(); sessions.clear(); usedRefresh.clear(); refusals.clear(); clearReceived()
    delayMillis = 0; silent = false; minPassword = 8; adminTokens = 0; withoutSession = false

  def userByEmail(email: String): Option[User] = users.values.asScala.find(_.email.equalsIgnoreCase(email))

  /** Calls to a route, by a short name: `create`, `grant`, `refresh`, `revoke`, `liveSessions`, `actions`, `delete`, `update`. */
  def receivedOf(call: String): Vector[Received] = received.filter(r => FakeRealm.callOf(r).contains(call))

  private def tokenFor(u: User, s: Session): String =
    val claims = Json.obj("sub" -> u.id, "iss" -> s"https://auth.example/realms/$realm", "eitheror_twin_id" -> u.playerId,
      "session_state" -> s.id, "email" -> u.email, "exp" -> (System.currentTimeMillis / 1000 + FakeRealm.Lifespan))
    if withoutSession then claims.remove("session_state"): Unit
    FakeRealm.jwt(claims)

  private def tokens(u: User, s: Session) =
    Answer.ok(Json.obj("access_token" -> tokenFor(u, s), "refresh_token" -> s.refresh, "expires_in" -> FakeRealm.Lifespan, "token_type" -> "Bearer"))

  private def grantError(status: Int, description: String) =
    Answer.json(status, Json.obj("error" -> "invalid_grant", "error_description" -> description))

  protected def route(r: Received): Answer =
    if silent then Thread.sleep(60_000)
    if delayMillis > 0 then Thread.sleep(delayMillis)
    val admin = s"/admin/realms/$realm"
    (r.method, r.path) match
      case ("POST", p) if p == s"/realms/$realm/protocol/openid-connect/token" => token(r)
      case (_, p) if p.startsWith("/admin/realms/") && !p.startsWith(admin) => Answer(404, """{"error":"Realm not found."}""")
      case (_, p) if p.startsWith(admin) && !r.headers.get("authorization").contains("Bearer admin-token") =>
        Answer(401, """{"error":"HTTP 401 Unauthorized"}""")
      case ("POST", p) if p == s"$admin/users" => create(r)
      case ("GET", p) if p == s"$admin/users" =>
        val q = r.query.getOrElse("q", "")
        val found = users.values.asScala.toVector.filter(u => q.isEmpty || q == s"eitheror_twin_id:${u.playerId}")
        Answer.ok(Json.arr(found.map(u => Json.obj("id" -> u.id, "email" -> u.email, "enabled" -> u.enabled))*))
      case ("GET", p) if p.startsWith(s"$admin/users/") && p.count(_ == '/') == 5 =>
        Option(users.get(p.split('/').last)).fold(Answer(404, """{"error":"User not found"}""")) { u =>
          Answer.ok(Json.obj("id" -> u.id, "username" -> u.email, "email" -> u.email, "emailVerified" -> u.verified, "enabled" -> u.enabled,
            "requiredActions" -> Json.arr((if u.verified then Vector.empty else Vector("VERIFY_EMAIL"))*),
            "attributes" -> Json.obj("eitheror_twin_id" -> Json.arr(u.playerId))))
        }
      case ("PUT", p) if p.startsWith(s"$admin/users/") && p.count(_ == '/') == 5 =>
        Option(users.get(p.split('/').last)).fold(Answer(404, "")) { u =>
          r.json.foreach { j =>
            j.bool("emailVerified").foreach(u.verified = _)
            j.bool("enabled").foreach(u.enabled = _)
            j.text("email").foreach(u.email = _)
          }
          if !u.enabled then sessions.values.asScala.filter(_.userId == u.id).foreach(s => sessions.remove(s.id))
          Answer(204, "")
        }
      case ("DELETE", p) if p.startsWith(s"$admin/users/") =>
        Option(users.remove(p.split('/').last)).fold(Answer(404, ""))(_ => Answer(204, ""))
      case ("PUT", p) if p.endsWith("/execute-actions-email") =>
        Option(users.get(p.split('/')(5))).fold(Answer(404, "")) { u =>
          u.actions = u.actions ++ r.json.map(_.elements.asScala.map(_.asText).toVector).getOrElse(Vector.empty); Answer(204, "")
        }
      case ("PUT", p) if p.endsWith("/reset-password") =>
        Option(users.get(p.split('/')(5))).fold(Answer(404, "")) { u =>
          val value = r.json.flatMap(_.text("value")).getOrElse("")
          if value.length < minPassword then Answer(400, """{"error":"invalidPasswordMinLengthMessage","error_description":"Invalid password: minimum length 8."}""")
          else { u.password = value; Answer(204, "") }
        }
      case ("DELETE", p) if p.startsWith(s"$admin/sessions/") =>
        Option(sessions.remove(p.split('/').last)).fold(Answer(404, ""))(_ => Answer(204, ""))
      case ("GET", p) if p == s"$admin/clients" =>
        Answer.ok(Json.arr(Json.obj("id" -> frontendId, "clientId" -> "casino-frontend")))
      case ("GET", p) if p == s"$admin/clients/$frontendId/user-sessions" =>
        Answer.ok(Json.arr(sessions.values.asScala.toVector.map(s =>
          Json.obj("id" -> s.id, "userId" -> s.userId, "start" -> s.started, "lastAccess" -> s.lastAccess))*))
      case _ => Answer(404, """{"error":"unknown route"}""")

  private def create(r: Received): Answer =
    val body     = r.json.getOrElse(Json.obj())
    val email    = body.textOr("email", "").toLowerCase
    val password = body.objects("credentials").headOption.flatMap(_.text("value")).getOrElse("")
    val player   = Option(body.get("attributes")).map(_.strings("eitheror_twin_id")).flatMap(_.headOption).getOrElse("")
    if userByEmail(email).nonEmpty then Answer(409, """{"errorMessage":"User exists with same username"}""")
    else if password.length < minPassword then Answer(400, """{"errorMessage":"invalidPasswordMinLengthMessage"}""")
    else
      val u = User(UUID.randomUUID().toString, email, password, player)
      users.put(u.id, u)
      Answer(201, "", headers = Map("Location" -> s"http://fake/admin/realms/$realm/users/${u.id}"))

  private def token(r: Received): Answer =
    val form = r.body.split('&').toVector.flatMap(_.split("=", 2) match
      case Array(k, v) => Some(java.net.URLDecoder.decode(k, UTF_8) -> java.net.URLDecoder.decode(v, UTF_8))
      case _           => None).toMap
    form.getOrElse("grant_type", "") match
      case "client_credentials" =>
        if form.get("client_id").contains(adminClient) && form.get("client_secret").contains(adminSecret) then
          adminTokens += 1
          Answer.ok(Json.obj("access_token" -> "admin-token", "expires_in" -> 300L))
        else Answer.json(401, Json.obj("error" -> "unauthorized_client"))
      case "password" =>
        val email = form.getOrElse("username", "").toLowerCase
        Option(refusals.remove(email)) match
          case Some(description) => grantError(400, description)
          case None =>
            userByEmail(email).filter(_.password == form.getOrElse("password", "")) match
              case None                   => grantError(401, "Invalid user credentials")
              case Some(u) if !u.enabled  => grantError(400, "Account disabled")
              case Some(u) if !u.verified => grantError(400, "Account is not fully set up")
              case Some(u) =>
                val now = System.currentTimeMillis
                val s   = Session(UUID.randomUUID().toString, u.id, now, now, UUID.randomUUID().toString)
                sessions.put(s.id, s)
                tokens(u, s)
      case "refresh_token" =>
        val offered = form.getOrElse("refresh_token", "")
        sessions.values.asScala.find(_.refresh == offered) match
          case Some(s) if usedRefresh.add(offered) =>
            s.refresh = UUID.randomUUID().toString
            s.lastAccess = System.currentTimeMillis
            Option(users.get(s.userId)).fold(grantError(400, "Session not active"))(tokens(_, s))
          case _ => grantError(400, "Token is not active")
      case _ => Answer.json(400, Json.obj("error" -> "unsupported_grant_type"))

object FakeRealm:
  val SigningKey = "fake-realm-signing-key"

  /** The access token's lifetime in seconds: the casino realm template's `accessTokenLifespan`. */
  val Lifespan = 3600L

  private def b64(bytes: Array[Byte]) = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** An HS256 token over `claims`, as the kit's authenticator checks it. */
  def jwt(claims: com.fasterxml.jackson.databind.JsonNode): String =
    val head = b64("""{"alg":"HS256","typ":"JWT"}""".getBytes(UTF_8))
    val body = b64(Json.render(claims).getBytes(UTF_8))
    s"$head.$body.${b64(sign(s"$head.$body"))}"

  def sign(text: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(SigningKey.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(text.getBytes(UTF_8))

  /** The claims of a token this fake signed, or none. */
  def verify(token: String): Option[com.fasterxml.jackson.databind.JsonNode] =
    token.split('.') match
      case Array(h, b, s) if java.security.MessageDigest.isEqual(Base64.getUrlDecoder.decode(s), sign(s"$h.$b")) =>
        Json.parse(String(Base64.getUrlDecoder.decode(b), UTF_8))
      case _ => None

  def callOf(r: Received): Option[String] = (r.method, r.path) match
    case ("POST", p) if p.endsWith("/openid-connect/token") =>
      if r.body.contains("grant_type=password") then Some("grant")
      else if r.body.contains("grant_type=refresh_token") then Some("refresh")
      else Some("admin-token")
    case ("POST", p) if p.endsWith("/users")                 => Some("create")
    case ("DELETE", p) if p.contains("/sessions/")            => Some("revoke")
    case ("DELETE", p) if p.contains("/users/")               => Some("delete")
    case ("GET", p) if p.endsWith("/user-sessions")           => Some("liveSessions")
    case ("PUT", p) if p.endsWith("/execute-actions-email")   => Some("actions")
    case ("PUT", p) if p.endsWith("/reset-password")          => Some("setPassword")
    case ("PUT", p) if p.contains("/users/")                  => Some("update")
    case _                                                    => None
