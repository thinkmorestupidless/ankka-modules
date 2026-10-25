package io.eitheror.casino.players.realm

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

/**
 * [[Realm]] over Keycloak's HTTP API, after merchant's `KeycloakAdmin`. The admin token comes from a
 * client-credentials grant on the brand's realm with `players-realm-admin` (`{clientId, clientSecret}`)
 * and is kept until shortly before it expires; `casino-frontend`'s internal id is read once.
 */
final class KeycloakRealm(baseUrl: String, val realm: String, admin: () => Option[KeycloakRealm.AdminClient],
    clock: () => Instant = () => Instant.now()) extends Realm:
  import KeycloakRealm.*

  private val base     = baseUrl.stripSuffix("/")
  private val adminApi = s"$base/admin/realms/$realm"
  private val tokenUrl = s"$base/realms/$realm/protocol/openid-connect/token"
  private val http     = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()

  @volatile private var cached: Option[(String, Instant)] = None
  @volatile private var frontendId: Option[String]         = None

  private def enc(s: String) = URLEncoder.encode(s, UTF_8)

  private def send(b: HttpRequest.Builder): Either[RealmRefusal, (Int, String)] =
    Try(http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())).toEither.left
      .map(e => RealmRefusal.Failed(s"${e.getClass.getSimpleName}: ${e.getMessage}"))
      .map(r => (r.statusCode, r.body))

  private def form(url: String, fields: (String, String)*) =
    HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(fields.map((k, v) => s"$k=${enc(v)}").mkString("&")))

  private[realm] def adminToken: Either[RealmRefusal, String] =
    cached.filter(_._2.isAfter(clock())).map(_._1) match
      case Some(t) => Right(t)
      case None =>
        admin() match
          case None => Left(RealmRefusal.NotConfigured(s"no admin client at players-realm-admin"))
          case Some(c) =>
            send(form(tokenUrl, "grant_type" -> "client_credentials", "client_id" -> c.clientId, "client_secret" -> c.clientSecret))
              .flatMap {
                case (200, body) =>
                  val node = Json.parse(body)
                  node.flatMap(_.text("access_token")).toRight(RealmRefusal.Failed("no access token")).map { t =>
                    val ttl = node.flatMap(_.long("expires_in")).getOrElse(60L)
                    cached = Some(t -> clock().plusSeconds(math.max(0L, ttl - 10)))
                    t
                  }
                case (404, _) => Left(RealmRefusal.RealmNotFound)
                case (s, _)   => Left(RealmRefusal.Failed(s"admin token: $s"))
              }

  private def call(method: String, path: String, body: Option[JsonNode] = None): Either[RealmRefusal, (Int, String)] =
    adminToken.flatMap { t =>
      val b = HttpRequest.newBuilder(URI.create(adminApi + path)).header("Authorization", s"Bearer $t")
        .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(j => HttpRequest.BodyPublishers.ofString(Json.render(j))))
      if body.isDefined then b.header("Content-Type", "application/json")
      send(b)
    }

  private def ok(r: (Int, String)): Either[RealmRefusal, Unit] = r match
    case (s, _) if s / 100 == 2 => Right(())
    case (404, _)               => Left(RealmRefusal.UserNotFound)
    case (s, b)                 => Left(RealmRefusal.Failed(s"$s ${b.take(200)}"))

  def createUser(email: String, password: String, playerId: String): Either[RealmRefusal, String] =
    val user = Json.obj("username" -> email, "email" -> email, "enabled" -> true, "emailVerified" -> false,
      "credentials" -> Json.arr(Json.obj("type" -> "password", "value" -> password, "temporary" -> false)),
      "attributes" -> Json.obj("eitheror_twin_id" -> Json.arr(playerId)))
    adminToken.flatMap { t =>
      Try(http.send(HttpRequest.newBuilder(URI.create(s"$adminApi/users")).header("Authorization", s"Bearer $t")
        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(Json.render(user))).build(), HttpResponse.BodyHandlers.ofString()))
        .toEither.left.map(e => RealmRefusal.Failed(e.getMessage)).flatMap { r =>
          r.statusCode match
            case 201 => r.headers.firstValue("Location").toScala.map(_.split('/').last).toRight(RealmRefusal.Failed("no Location"))
            case 409 => Left(RealmRefusal.EmailExists)
            case 404 => Left(RealmRefusal.RealmNotFound)
            case 400 if passwordPolicy(r.body) => Left(RealmRefusal.PasswordTooWeak)
            case s   => Left(RealmRefusal.Failed(s"create user: $s"))
        }
    }

  def deleteUser(userId: String): Either[RealmRefusal, Unit] = call("DELETE", s"/users/$userId").flatMap(ok)

  /** Reads the user, changes `f`'s fields, writes it back: Keycloak's PUT replaces what it is given. */
  private def update(userId: String)(f: com.fasterxml.jackson.databind.node.ObjectNode => Unit): Either[RealmRefusal, Unit] =
    call("GET", s"/users/$userId").flatMap {
      case (200, body) =>
        Json.parseObject(body).toRight(RealmRefusal.Failed("user not an object")).flatMap { u =>
          f(u)
          call("PUT", s"/users/$userId", Some(u)).flatMap(ok)
        }
      case other => ok(other)
    }

  def setVerified(userId: String): Either[RealmRefusal, Unit] = update(userId) { u =>
    val pending = u.strings("requiredActions").filterNot(_ == "VERIFY_EMAIL")
    u.put("emailVerified", true)
    u.set("requiredActions", Json.arr(pending*)): Unit
  }

  def changeAddress(userId: String, email: String): Either[RealmRefusal, Unit] = update(userId) { u =>
    u.put("email", email); u.put("username", email); u.put("emailVerified", true): Unit
  }

  def setEnabled(userId: String, enabled: Boolean): Either[RealmRefusal, Unit] = update(userId)(_.put("enabled", enabled): Unit)

  def directGrant(email: String, password: String): Either[RealmRefusal, RealmTokens] =
    grant("grant_type" -> "password", "client_id" -> Frontend, "username" -> email, "password" -> password, "scope" -> "openid")

  def refresh(refreshToken: String): Either[RealmRefusal, RealmTokens] =
    grant("grant_type" -> "refresh_token", "client_id" -> Frontend, "refresh_token" -> refreshToken).left.map {
      case RealmRefusal.InvalidCredentials => RealmRefusal.InvalidToken
      case other                           => other
    }

  def verifyPassword(email: String, password: String): Either[RealmRefusal, Unit] =
    directGrant(email, password).flatMap(t => revokeSession(t.realmSessionId).left.flatMap(_ => Right(())))

  private def grant(fields: (String, String)*): Either[RealmRefusal, RealmTokens] =
    send(form(tokenUrl, fields*)).flatMap {
      case (200, body) => Json.parse(body).toRight(RealmRefusal.Failed("token answer not JSON")).flatMap(tokens)
      case (404, _)    => Left(RealmRefusal.RealmNotFound)
      case (s, body) if s == 400 || s == 401 => Left(grantRefusal(body))
      case (s, _)      => Left(RealmRefusal.Failed(s"token: $s"))
    }

  def revokeSession(realmSessionId: String): Either[RealmRefusal, Unit] =
    call("DELETE", s"/sessions/${enc(realmSessionId)}").flatMap {
      case (404, _) => Right(()) // already gone
      case other    => ok(other)
    }

  private def frontend: Either[RealmRefusal, String] =
    frontendId.toRight(()).left.flatMap { _ =>
      call("GET", s"/clients?clientId=$Frontend").flatMap {
        case (200, body) =>
          Json.parse(body).flatMap(_.elements.asScala.toVector.headOption).flatMap(_.text("id"))
            .toRight(RealmRefusal.NotConfigured(s"no client $Frontend in $realm"))
            .map { id => frontendId = Some(id); id }
        case (s, _) => Left(RealmRefusal.Failed(s"clients: $s"))
      }
    }

  def liveSessions(): Either[RealmRefusal, Vector[LiveSession]] =
    frontend.flatMap(id => call("GET", s"/clients/$id/user-sessions?first=0&max=10000")).flatMap {
      case (200, body) =>
        Right(Json.parse(body).filter(_.isArray).map(_.elements.asScala.toVector).getOrElse(Vector.empty).map { s =>
          LiveSession(s.textOr("id", ""), s.textOr("userId", ""), Instant.ofEpochMilli(s.long("start").getOrElse(0L)),
            Instant.ofEpochMilli(s.long("lastAccess").getOrElse(0L)))
        })
      case (s, _) => Left(RealmRefusal.Failed(s"user-sessions: $s"))
    }

  def sendResetEmail(userId: String): Either[RealmRefusal, Unit] = sendActions(userId, Vector("UPDATE_PASSWORD"))

  def sendActions(userId: String, actions: Vector[String]): Either[RealmRefusal, Unit] =
    call("PUT", s"/users/$userId/execute-actions-email", Some(Json.arr(actions*))).flatMap(ok)

  def setPassword(userId: String, password: String): Either[RealmRefusal, Unit] =
    call("PUT", s"/users/$userId/reset-password", Some(Json.obj("type" -> "password", "value" -> password, "temporary" -> false))).flatMap {
      case (400, body) if passwordPolicy(body) => Left(RealmRefusal.PasswordTooWeak)
      case other                               => ok(other)
    }

  def userOf(playerId: String): Either[RealmRefusal, String] =
    call("GET", s"/users?q=${enc(s"eitheror_twin_id:$playerId")}&exact=true").flatMap {
      case (200, body) =>
        Json.parse(body).flatMap(_.elements.asScala.toVector.headOption).flatMap(_.text("id")).toRight(RealmRefusal.UserNotFound)
      case other => ok(other).flatMap(_ => Left(RealmRefusal.UserNotFound))
    }

object KeycloakRealm:
  /** `players-realm-admin`: the realm's administrative client, as the secret store holds it (JSON). */
  final case class AdminClient(clientId: String, clientSecret: String)

  object AdminClient:
    /** `{clientId, clientSecret}`, or a bare secret for client `players-admin`. */
    def parse(text: String): Option[AdminClient] =
      Json.parseObject(text) match
        case Some(o) => for id <- o.text("clientId"); s <- o.text("clientSecret") yield AdminClient(id, s)
        case None    => Option(text).map(_.trim).filter(_.nonEmpty).map(AdminClient("players-admin", _))

  val Frontend = "casino-frontend"

  /** Keycloak's password-policy refusals (`invalidPassword…Message`). */
  def passwordPolicy(body: String): Boolean = body.contains("invalidPassword") || body.toLowerCase.contains("password policy")

  /** Keycloak's `invalid_grant` descriptions, mapped. */
  def grantRefusal(body: String): RealmRefusal =
    val description = Json.parse(body).flatMap(_.text("error_description")).getOrElse("").toLowerCase
    if description.contains("not fully set up") || description.contains("verify") then RealmRefusal.EmailNotVerified
    else if description.contains("disabled") then RealmRefusal.Disabled
    else RealmRefusal.InvalidCredentials

  /** The token answer and the two claims, read from the access token's payload (it came from the realm itself, over its own TLS). */
  def tokens(node: JsonNode): Either[RealmRefusal, RealmTokens] =
    for
      access  <- node.text("access_token").toRight(RealmRefusal.Failed("no access token"))
      claims  <- claimsOf(access).toRight(RealmRefusal.MissingToken)
      player  <- claims.text("eitheror_twin_id").map(_.split('/').last).filter(_.nonEmpty).toRight(RealmRefusal.MissingToken)
      session <- claims.text("session_state").orElse(claims.text("sid")).toRight(RealmRefusal.MissingToken)
    yield RealmTokens(access, node.textOr("refresh_token", ""), node.long("expires_in").getOrElse(0L), player, session)

  def claimsOf(jwt: String): Option[JsonNode] =
    jwt.split('.') match
      case Array(_, payload, _*) => Try(String(Base64.getUrlDecoder.decode(payload), UTF_8)).toOption.flatMap(Json.parse)
      case _                     => None
