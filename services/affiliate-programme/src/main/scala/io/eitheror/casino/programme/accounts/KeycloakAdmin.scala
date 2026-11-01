package io.eitheror.casino.programme.accounts

import com.fasterxml.jackson.databind.node.{JsonNodeFactory, ObjectNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

/** Why a call to the realm did not do what was asked. */
enum RealmError:
  case AlreadyExists(email: String)
  case NotFound(userId: String)
  case NotConfigured(why: String)
  case Failed(why: String)

/**
 * The calls the programme makes to Keycloak's admin API for the `eitheror` realm, after `merchant`'s
 * `KeycloakAdmin`: an affiliate's login made with the attributes the original gives it
 * (`S/NetworkTree.scala:225-328`: scope id, scope level `affiliate`, actor type `affiliate`), the realm
 * roles `affiliate` and `brand_<slug>` given and taken, a user found by email, a password reset, an
 * email changed, a user deleted (the erasure's `realm` step).
 */
trait KeycloakAdmin:
  def createUser(email: String, firstName: Option[String], lastName: Option[String], attributes: Map[String, String]): Either[RealmError, String]
  def assignRole(userId: String, role: String): Either[RealmError, Unit]
  def removeRole(userId: String, role: String): Either[RealmError, Unit]
  def findByEmail(email: String): Either[RealmError, Option[String]]
  def resetPassword(userId: String, password: String): Either[RealmError, Unit]
  def updateEmail(userId: String, email: String): Either[RealmError, Unit]
  def deleteUser(userId: String): Either[RealmError, Unit]

object KeycloakAdmin:
  final case class Credentials(username: String, password: String)

  /** The attributes of an affiliate's login, as the original writes them. */
  def affiliateAttributes(affiliate: String): Map[String, String] =
    Map("eitheror_scope_id" -> affiliate, "eitheror_scope_level" -> "affiliate", "eitheror_actor_type" -> "affiliate")

  /** A display name split as the original splits it: first word, and the rest (or the first again). */
  def names(displayName: Option[String]): (Option[String], Option[String]) =
    displayName.map(_.trim.split("\\s+", 2)) match
      case Some(Array(first, last)) if last.nonEmpty  => (Some(first), Some(last))
      case Some(Array(first)) if first.nonEmpty       => (Some(first), Some(first))
      case _                                          => (None, None)

  def http(baseUrl: String, realm: String, credentials: () => Option[Credentials]): KeycloakAdmin =
    HttpKeycloak(baseUrl.stripSuffix("/"), realm, credentials)

  /** No realm configured (a local run): every call says so. */
  val unconfigured: KeycloakAdmin = new KeycloakAdmin:
    private def no = Left(RealmError.NotConfigured("KEYCLOAK_URL is not set"))
    def createUser(email: String, f: Option[String], l: Option[String], a: Map[String, String]) = no
    def assignRole(userId: String, role: String)                                                 = no
    def removeRole(userId: String, role: String)                                                 = no
    def findByEmail(email: String)                                                               = no
    def resetPassword(userId: String, password: String)                                          = no
    def updateEmail(userId: String, email: String)                                               = no
    def deleteUser(userId: String)                                                               = no

private final class HttpKeycloak(base: String, realm: String, credentials: () => Option[KeycloakAdmin.Credentials]) extends KeycloakAdmin:
  private val mapper = ObjectMapper()
  private val nodes  = JsonNodeFactory.instance
  private val http   = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
  private def enc(s: String) = URLEncoder.encode(s, UTF_8)
  private val admin          = s"$base/admin/realms/$realm"

  private def parse(body: String): Option[JsonNode] = Try(mapper.readTree(body)).toOption.filter(_ != null)

  private def token: Either[RealmError, String] =
    credentials() match
      case None => Left(RealmError.NotConfigured("no realm admin credentials"))
      case Some(c) =>
        val form = s"grant_type=password&client_id=admin-cli&username=${enc(c.username)}&password=${enc(c.password)}"
        send(HttpRequest.newBuilder(URI.create(s"$base/realms/master/protocol/openid-connect/token"))
          .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)))
          .flatMap { (status, body, _) =>
            if status / 100 == 2 then parse(body).flatMap(j => Option(j.get("access_token"))).map(_.asText).toRight(RealmError.Failed("no access token"))
            else Left(RealmError.Failed(s"admin token: $status"))
          }

  private def send(b: HttpRequest.Builder): Either[RealmError, (Int, String, Option[String])] =
    Try(http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()))
      .toEither.left.map(e => RealmError.Failed(s"${e.getClass.getSimpleName}: ${e.getMessage}"))
      .map(r => (r.statusCode, r.body, r.headers.firstValue("Location").toScala))

  private def call(method: String, path: String, body: Option[JsonNode] = None): Either[RealmError, (Int, String, Option[String])] =
    token.flatMap { t =>
      val b = HttpRequest.newBuilder(URI.create(admin + path)).header("Authorization", s"Bearer $t")
        .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(j => HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(j))))
      if body.isDefined then b.header("Content-Type", "application/json")
      send(b)
    }

  private def ok(userId: String)(r: (Int, String, Option[String])): Either[RealmError, Unit] = r match
    case (s, _, _) if s / 100 == 2 => Right(())
    case (404, _, _)               => Left(RealmError.NotFound(userId))
    case (s, b, _)                 => Left(RealmError.Failed(s"$s ${b.take(200)}"))

  def createUser(email: String, firstName: Option[String], lastName: Option[String], attributes: Map[String, String]): Either[RealmError, String] =
    val user = nodes.objectNode()
    user.put("username", email).put("email", email).put("enabled", true).put("emailVerified", false)
    firstName.foreach(user.put("firstName", _))
    lastName.foreach(user.put("lastName", _))
    val attrs = nodes.objectNode()
    attributes.foreach((k, v) => attrs.set(k, nodes.arrayNode().add(v)): Unit)
    user.set("attributes", attrs): Unit
    call("POST", "/users", Some(user)).flatMap {
      case (201, _, Some(location)) => Right(location.split('/').last)
      case (201, _, None)           => Left(RealmError.Failed("no Location"))
      case (409, _, _)              => Left(RealmError.AlreadyExists(email))
      case (s, b, _)                => Left(RealmError.Failed(s"create user: $s ${b.take(200)}"))
    }

  private def roleMapping(method: String, userId: String, role: String): Either[RealmError, Unit] =
    call("GET", s"/roles/${enc(role)}").flatMap {
      case (200, body, _) => call(method, s"/users/$userId/role-mappings/realm", Some(nodes.arrayNode().add(parse(body).get))).flatMap(ok(userId))
      case (s, b, _)      => Left(RealmError.Failed(s"role $role: $s ${b.take(200)}"))
    }

  def assignRole(userId: String, role: String): Either[RealmError, Unit] = roleMapping("POST", userId, role)
  def removeRole(userId: String, role: String): Either[RealmError, Unit] = roleMapping("DELETE", userId, role)

  def findByEmail(email: String): Either[RealmError, Option[String]] =
    call("GET", s"/users?email=${enc(email)}&exact=true").flatMap {
      case (200, body, _) =>
        Right(parse(body).filter(_.isArray).flatMap(_.elements.asScala.toVector.headOption).flatMap(u => Option(u.get("id"))).map(_.asText))
      case (s, b, _) => Left(RealmError.Failed(s"$s ${b.take(200)}"))
    }

  def resetPassword(userId: String, password: String): Either[RealmError, Unit] =
    val body = nodes.objectNode().put("type", "password").put("value", password).put("temporary", false)
    call("PUT", s"/users/$userId/reset-password", Some(body)).flatMap(ok(userId))

  def updateEmail(userId: String, email: String): Either[RealmError, Unit] =
    call("GET", s"/users/$userId").flatMap {
      case (200, body, _) =>
        val user = parse(body).collect { case o: ObjectNode => o }.get
        user.put("email", email).put("username", email).put("emailVerified", false)
        call("PUT", s"/users/$userId", Some(user)).flatMap(ok(userId))
      case (404, _, _) => Left(RealmError.NotFound(userId))
      case (s, b, _)   => Left(RealmError.Failed(s"$s ${b.take(200)}"))
    }

  def deleteUser(userId: String): Either[RealmError, Unit] =
    call("DELETE", s"/users/$userId").flatMap {
      case (404, _, _) => Right(())
      case r           => ok(userId)(r)
    }
