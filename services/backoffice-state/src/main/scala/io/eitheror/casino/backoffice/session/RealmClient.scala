package io.eitheror.casino.backoffice.session

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import io.eitheror.casino.backoffice.Settings

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * A credential of the realm's: an access or a refresh token. It prints as `Secret(redacted)`, so no log
 * line, exception message, trace attribute or `toString` of anything holding one carries it; its value is
 * read only by `reveal`. The session's events record it as a `Personal` of the operator, never as this
 * type (research R2); this codec is for the in-process answers only.
 */
final case class Secret(reveal: String):
  override def toString: String = "Secret(redacted)"

object Secret:
  given JsonValueCodec[Secret] with
    def decodeValue(in: JsonReader, default: Secret): Secret = Secret(in.readString(""))
    def encodeValue(x: Secret, out: JsonWriter): Unit        = out.writeVal(x.reveal)
    def nullValue: Secret                                    = Secret("")

/**
 * What the realm answered a code exchange or a refresh with: the two tokens, when each expires, and the
 * operator as the tokens name them (`sub`, the id token's `name` and `email`, the access token's realm
 * roles). Prints its roles alone.
 */
final case class RealmTokens(accessToken: Secret, refreshToken: Secret, accessExpiresAt: Instant, refreshExpiresAt: Instant,
    subject: String, name: String, email: String, roles: Set[String]):
  override def toString: String = s"RealmTokens(roles ${roles.toVector.sorted.mkString(",")}, until $accessExpiresAt)"

/** Why the realm gave no tokens: it refused the grant (`invalid_grant`), or it could not be asked. Never holds a token. */
enum RealmRefusal:
  case Refused(error: String)
  case Unavailable(why: String)

/** The brand's confidential client at the `eitheror` realm (research R2): the code exchange with PKCE, the refresh, the revocation. */
trait RealmClient:
  def exchange(code: String, verifier: String, redirectUri: String, now: Instant): Either[RealmRefusal, RealmTokens]
  def refresh(refreshToken: Secret, now: Instant): Either[RealmRefusal, RealmTokens]

  /** Revokes the refresh token and ends the realm's session it belongs to (FR-003: signing out ends both). */
  def revoke(refreshToken: Secret): Either[RealmRefusal, Unit]

object RealmClient:
  /** Until the service is wired: every call is unavailable, saying so. */
  val unconfigured: RealmClient = new RealmClient:
    private val no = Left(RealmRefusal.Unavailable("the realm client is not configured"))
    def exchange(code: String, verifier: String, redirectUri: String, now: Instant) = no
    def refresh(refreshToken: Secret, now: Instant)                                 = no
    def revoke(refreshToken: Secret)                                                = no

/**
 * [[RealmClient]] over Keycloak's OpenID Connect endpoints, as the brand's client `settings.client` with
 * its secret (`client_secret_post`). Only `invalid_grant` is a refusal: any other client error (a wrong
 * secret, a client not allowed the flow) is the installation's, so it is unavailable and ends no session.
 * A failure is described by its status or exception class, never by a body or a message that might echo
 * what was sent.
 */
final class KeycloakRealmClient(settings: Settings, http: HttpClient = KeycloakRealmClient.defaultHttp) extends RealmClient:
  import KeycloakRealmClient.*

  private val logoutUrl = settings.logoutUrl

  private def client: Seq[(String, String)] = Seq("client_id" -> settings.client, "client_secret" -> settings.clientSecret)

  def exchange(code: String, verifier: String, redirectUri: String, now: Instant): Either[RealmRefusal, RealmTokens] =
    grant(now, "grant_type" -> "authorization_code", "code" -> code, "code_verifier" -> verifier, "redirect_uri" -> redirectUri)

  def refresh(refreshToken: Secret, now: Instant): Either[RealmRefusal, RealmTokens] =
    grant(now, "grant_type" -> "refresh_token", "refresh_token" -> refreshToken.reveal)

  /** Both are attempted, so a failed revocation still ends the realm's session; the first failure is answered. */
  def revoke(refreshToken: Secret): Either[RealmRefusal, Unit] =
    val revoked = post(settings.revokeUrl, "token" -> refreshToken.reveal, "token_type_hint" -> "refresh_token").flatMap(done("revoke"))
    val loggedOut = post(logoutUrl, "refresh_token" -> refreshToken.reveal).flatMap {
      case (s, b) if s == 400 && errorOf(b) == "invalid_grant" => Right(()) // the realm's session is gone already
      case other                                              => done("logout")(other)
    }
    revoked.flatMap(_ => loggedOut)

  private def grant(now: Instant, fields: (String, String)*): Either[RealmRefusal, RealmTokens] =
    post(settings.tokenUrl, fields*).flatMap {
      case (200, body) => parse(body).toRight(RealmRefusal.Unavailable("the token answer is not JSON")).flatMap(tokens(_, now))
      case (s, body) if s == 400 || s == 401 =>
        errorOf(body) match
          case "invalid_grant" => Left(RealmRefusal.Refused("invalid_grant"))
          case other           => Left(RealmRefusal.Unavailable(s"token: $s ${if other.isEmpty then "" else other}".trim))
      case (s, _) => Left(RealmRefusal.Unavailable(s"token: $s"))
    }

  private def done(what: String)(r: (Int, String)): Either[RealmRefusal, Unit] = r match
    case (s, _) if s / 100 == 2 => Right(())
    case (s, _)                 => Left(RealmRefusal.Unavailable(s"$what: $s"))

  private def post(url: URI, fields: (String, String)*): Either[RealmRefusal, (Int, String)] =
    val form = (fields ++ client).map((k, v) => s"$k=${URLEncoder.encode(v, UTF_8)}").mkString("&")
    val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
      .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(form)).build()
    Try(http.send(request, HttpResponse.BodyHandlers.ofString())).toEither.left
      .map(e => RealmRefusal.Unavailable(e.getClass.getSimpleName))
      .map(r => (r.statusCode, r.body))

object KeycloakRealmClient:
  private val mapper = ObjectMapper()

  val defaultHttp: HttpClient =
    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()

  private def parse(text: String): Option[JsonNode] = Try(mapper.readTree(text)).toOption.filter(n => n != null && n.isObject)

  private def text(n: JsonNode, field: String): Option[String] =
    Option(n.get(field)).filter(_.isTextual).map(_.asText).filter(_.nonEmpty)

  /** The `error` of a Keycloak refusal, or empty. */
  def errorOf(body: String): String = parse(body).flatMap(text(_, "error")).getOrElse("")

  /** A JWT's claims, read from its payload: it came from the realm itself, over its own TLS. */
  def claimsOf(jwt: String): Option[JsonNode] =
    jwt.split('.') match
      case Array(_, payload, _*) => Try(String(Base64.getUrlDecoder.decode(payload), UTF_8)).toOption.flatMap(parse)
      case _                     => None

  /**
   * The token answer read: the roles are the access token's `realm_access.roles`; the name and email the id
   * token's, or the access token's. A refresh answer with no `refresh_expires_in` keeps the session to the
   * access token's life.
   */
  def tokens(node: JsonNode, now: Instant): Either[RealmRefusal, RealmTokens] =
    for
      access  <- text(node, "access_token").toRight(RealmRefusal.Unavailable("no access token in the answer"))
      refresh <- text(node, "refresh_token").toRight(RealmRefusal.Unavailable("no refresh token in the answer"))
      claims  <- claimsOf(access).toRight(RealmRefusal.Unavailable("the access token is not a JWT"))
      subject <- text(claims, "sub").toRight(RealmRefusal.Unavailable("the access token names no subject"))
    yield
      val id        = text(node, "id_token").flatMap(claimsOf).getOrElse(claims)
      val name      = text(id, "name").orElse(text(claims, "name")).orElse(text(id, "preferred_username")).getOrElse("")
      val email     = text(id, "email").orElse(text(claims, "email")).getOrElse("")
      val roles     = Option(claims.path("realm_access").get("roles")).filter(_.isArray).map(_.elements.asScala.map(_.asText).toSet)
      val accessIn  = Option(node.get("expires_in")).map(_.asLong(0L)).getOrElse(0L)
      val refreshIn = Option(node.get("refresh_expires_in")).map(_.asLong(0L)).filter(_ > 0).getOrElse(accessIn)
      RealmTokens(Secret(access), Secret(refresh), now.plusSeconds(accessIn), now.plusSeconds(refreshIn), subject, name, email,
        roles.getOrElse(Set.empty))
