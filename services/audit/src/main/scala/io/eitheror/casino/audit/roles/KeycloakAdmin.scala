package io.eitheror.casino.audit.roles

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Why a call to the realm did not do what was asked. */
enum RealmError:
  case NotFound(userId: String)
  case NotConfigured(why: String)
  case Failed(why: String)

/**
 * The calls the shared audit makes to Keycloak's admin API for the `eitheror` realm (research R8):
 * copied from the programme's `KeycloakAdmin` and narrowed to a realm role given, taken, and its
 * holders listed.
 */
trait KeycloakAdmin:
  def assignRole(userId: String, role: String): Either[RealmError, Unit]
  def removeRole(userId: String, role: String): Either[RealmError, Unit]
  /** The ids of the realm's users holding a realm role. */
  def usersWithRole(role: String): Either[RealmError, Vector[String]]

object KeycloakAdmin:
  final case class Credentials(username: String, password: String)

  def http(baseUrl: String, realm: String, credentials: () => Option[Credentials]): KeycloakAdmin =
    HttpKeycloak(baseUrl.stripSuffix("/"), realm, credentials)

  /** No realm configured (a local run): every call says so. */
  val unconfigured: KeycloakAdmin = new KeycloakAdmin:
    private def no = Left(RealmError.NotConfigured("KEYCLOAK_URL is not set"))
    def assignRole(userId: String, role: String) = no
    def removeRole(userId: String, role: String) = no
    def usersWithRole(role: String)              = no

private final class HttpKeycloak(base: String, realm: String, credentials: () => Option[KeycloakAdmin.Credentials]) extends KeycloakAdmin:
  private val mapper = ObjectMapper()
  private val nodes  = JsonNodeFactory.instance
  private val http   = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
  private def enc(s: String) = URLEncoder.encode(s, UTF_8)
  private val admin          = s"$base/admin/realms/$realm"

  private def parse(body: String): Option[JsonNode] = Try(mapper.readTree(body)).toOption.filter(_ != null)

  private final case class Held(token: String, until: Instant)
  private val held = AtomicReference[Option[Held]](None)

  /** The admin token, kept until shortly before it expires: one password grant per token's life, not per call. */
  private def token: Either[RealmError, String] =
    held.get().filter(_.until.isAfter(Instant.now())).map(_.token).toRight(()).left.flatMap(_ => fresh)

  private def fresh: Either[RealmError, String] =
    credentials() match
      case None => Left(RealmError.NotConfigured("no realm admin credentials"))
      case Some(c) =>
        val form = s"grant_type=password&client_id=admin-cli&username=${enc(c.username)}&password=${enc(c.password)}"
        send(HttpRequest.newBuilder(URI.create(s"$base/realms/master/protocol/openid-connect/token"))
          .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)))
          .flatMap { (status, body) =>
            if status / 100 != 2 then Left(RealmError.Failed(s"admin token: $status"))
            else
              val j = parse(body)
              j.flatMap(j => Option(j.get("access_token"))).map(_.asText).toRight(RealmError.Failed("no access token")).map { t =>
                val life = j.flatMap(j => Option(j.get("expires_in"))).filter(_.canConvertToLong).fold(60L)(_.asLong)
                held.set(Some(Held(t, Instant.now().plusSeconds(math.max(0L, life - 15)))))
                t
              }
          }

  private def send(b: HttpRequest.Builder): Either[RealmError, (Int, String)] =
    Try(http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()))
      .toEither.left.map(e => RealmError.Failed(s"${e.getClass.getSimpleName}: ${e.getMessage}"))
      .map(r => (r.statusCode, r.body))

  private def call(method: String, path: String, body: Option[JsonNode] = None): Either[RealmError, (Int, String)] =
    def once(t: String) =
      val b = HttpRequest.newBuilder(URI.create(admin + path)).header("Authorization", s"Bearer $t")
        .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(j => HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(j))))
      if body.isDefined then b.header("Content-Type", "application/json")
      send(b)
    // A token the realm no longer takes (revoked, or its clock ahead of ours) is fetched again, once.
    token.flatMap(once).flatMap {
      case (401, _) => held.set(None); fresh.flatMap(once)
      case r        => Right(r)
    }

  private def ok(userId: String)(r: (Int, String)): Either[RealmError, Unit] = r match
    case (s, _) if s / 100 == 2 => Right(())
    case (404, _)               => Left(RealmError.NotFound(userId))
    case (s, b)                 => Left(RealmError.Failed(s"$s ${b.take(200)}"))

  private def roleMapping(method: String, userId: String, role: String): Either[RealmError, Unit] =
    call("GET", s"/roles/${enc(role)}").flatMap {
      case (200, body) =>
        parse(body).toRight(RealmError.Failed(s"role $role: not JSON"))
          .flatMap(r => call(method, s"/users/${enc(userId)}/role-mappings/realm", Some(nodes.arrayNode().add(r)))).flatMap(ok(userId))
      case (s, b) => Left(RealmError.Failed(s"role $role: $s ${b.take(200)}"))
    }

  def assignRole(userId: String, role: String): Either[RealmError, Unit] = roleMapping("POST", userId, role)
  def removeRole(userId: String, role: String): Either[RealmError, Unit] = roleMapping("DELETE", userId, role)

  def usersWithRole(role: String): Either[RealmError, Vector[String]] =
    call("GET", s"/roles/${enc(role)}/users?first=0&max=10000").flatMap {
      case (200, body) =>
        Right(parse(body).filter(_.isArray).fold(Vector.empty[String])(_.elements.asScala.toVector.flatMap(u => Option(u.get("id")).map(_.asText))))
      case (404, _) => Right(Vector.empty)
      case (s, b)   => Left(RealmError.Failed(s"$s ${b.take(200)}"))
    }
