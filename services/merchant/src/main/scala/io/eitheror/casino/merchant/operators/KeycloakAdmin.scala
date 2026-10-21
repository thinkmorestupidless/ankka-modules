package io.eitheror.casino.merchant.operators

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*

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

/** A user of the operators' realm, as the sync reads it back. */
final case class RealmUser(id: String, email: String, enabled: Boolean)

/**
 * The calls the payments backoffice makes to Keycloak's admin API for the `eitheror` realm (FR-027,
 * research R10; the original's `KeycloakAdminClient`, the calls `OperatorRoutes` makes): create a user,
 * give a realm role, write the scope attributes decision 0050 reads, set a password, mark the email
 * verified, list the users, delete one. Spec 001 FR-021 is the exception that lets this service
 * administer a realm.
 */
trait KeycloakAdmin:
  def createUser(email: String, firstName: Option[String], lastName: Option[String]): Either[RealmError, String]
  def assignRole(userId: String, role: String): Either[RealmError, Unit]
  def setScope(userId: String, scopeId: String, scopeLevel: String, ancestors: Vector[String]): Either[RealmError, Unit]
  def resetPassword(userId: String, password: String): Either[RealmError, Unit]
  def setEmailVerified(userId: String): Either[RealmError, Unit]
  def listUsers(): Either[RealmError, Vector[RealmUser]]
  def deleteUser(userId: String): Either[RealmError, Unit]

object KeycloakAdmin:
  /** The admin client's credentials, from the secret store entry `keycloak/operators-admin`: `{username, password}`. */
  final case class Credentials(username: String, password: String)

  def http(baseUrl: String, realm: String, credentials: () => Option[Credentials]): KeycloakAdmin = HttpKeycloak(baseUrl.stripSuffix("/"), realm, credentials)

private final class HttpKeycloak(base: String, realm: String, credentials: () => Option[KeycloakAdmin.Credentials]) extends KeycloakAdmin:
  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
  private def enc(s: String) = URLEncoder.encode(s, UTF_8)
  private val admin = s"$base/admin/realms/$realm"

  private def token: Either[RealmError, String] =
    credentials() match
      case None => Left(RealmError.NotConfigured("no admin credentials at keycloak/operators-admin"))
      case Some(c) =>
        val form = s"grant_type=password&client_id=admin-cli&username=${enc(c.username)}&password=${enc(c.password)}"
        send(HttpRequest.newBuilder(URI.create(s"$base/realms/master/protocol/openid-connect/token"))
          .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)))
          .flatMap { (status, body) =>
            if status / 100 == 2 then Json.parse(body).flatMap(_.text("access_token")).toRight(RealmError.Failed("no access token"))
            else Left(RealmError.Failed(s"admin token: $status"))
          }

  private def send(b: HttpRequest.Builder): Either[RealmError, (Int, String)] =
    Try(http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()))
      .toEither.left.map(e => RealmError.Failed(s"${e.getClass.getSimpleName}: ${e.getMessage}"))
      .map(r => (r.statusCode, r.body))

  private def call(method: String, path: String, body: Option[JsonNode] = None): Either[RealmError, (Int, String)] =
    token.flatMap { t =>
      val b = HttpRequest.newBuilder(URI.create(admin + path)).header("Authorization", s"Bearer $t")
        .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(j => HttpRequest.BodyPublishers.ofString(Json.render(j))))
      if body.isDefined then b.header("Content-Type", "application/json")
      send(b)
    }

  private def ok(userId: String)(r: (Int, String)): Either[RealmError, Unit] = r match
    case (s, _) if s / 100 == 2 => Right(())
    case (404, _)               => Left(RealmError.NotFound(userId))
    case (s, b)                 => Left(RealmError.Failed(s"$s ${b.take(200)}"))

  def createUser(email: String, firstName: Option[String], lastName: Option[String]): Either[RealmError, String] =
    val user = Json.obj("username" -> email, "email" -> email, "enabled" -> true, "emailVerified" -> false,
      "requiredActions" -> Json.arr("VERIFY_EMAIL"), "firstName" -> firstName, "lastName" -> lastName)
    token.flatMap { t =>
      Try(http.send(HttpRequest.newBuilder(URI.create(s"$admin/users")).header("Authorization", s"Bearer $t")
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.render(user))).build(),
        HttpResponse.BodyHandlers.ofString())).toEither.left.map(e => RealmError.Failed(e.getMessage)).flatMap { r =>
        r.statusCode match
          case 201 => r.headers.firstValue("Location").toScala.map(_.split('/').last).toRight(RealmError.Failed("no Location"))
          case 409 => Left(RealmError.AlreadyExists(email))
          case s   => Left(RealmError.Failed(s"create user: $s ${r.body.take(200)}"))
      }
    }

  def assignRole(userId: String, role: String): Either[RealmError, Unit] =
    call("GET", s"/roles/${enc(role)}").flatMap {
      case (200, body) => call("POST", s"/users/$userId/role-mappings/realm", Some(Json.arr(Json.parse(body).get))).flatMap(ok(userId))
      case (s, b)      => Left(RealmError.Failed(s"role $role: $s ${b.take(200)}"))
    }

  def setScope(userId: String, scopeId: String, scopeLevel: String, ancestors: Vector[String]): Either[RealmError, Unit] =
    call("GET", s"/users/$userId").flatMap {
      case (200, body) =>
        val user  = Json.parseObject(body).get
        val attrs = Option(user.get("attributes")).collect { case o: com.fasterxml.jackson.databind.node.ObjectNode => o }.getOrElse(Json.obj())
        attrs.set("eitheror_scope_id", Json.arr(scopeId)); attrs.set("eitheror_scope_level", Json.arr(scopeLevel))
        attrs.set("eitheror_scope_ancestors", Json.arr(ancestors*)): Unit
        user.set("attributes", attrs): Unit
        call("PUT", s"/users/$userId", Some(user)).flatMap(ok(userId))
      case (404, _) => Left(RealmError.NotFound(userId))
      case (s, b)   => Left(RealmError.Failed(s"$s ${b.take(200)}"))
    }

  def resetPassword(userId: String, password: String): Either[RealmError, Unit] =
    call("PUT", s"/users/$userId/reset-password", Some(Json.obj("type" -> "password", "value" -> password, "temporary" -> false))).flatMap(ok(userId))

  def setEmailVerified(userId: String): Either[RealmError, Unit] =
    call("GET", s"/users/$userId").flatMap {
      case (200, body) =>
        val user    = Json.parseObject(body).get
        val pending = Option(user.get("requiredActions")).map(_.elements.asScala.map(_.asText).toVector).getOrElse(Vector.empty)
        user.put("emailVerified", true)
        user.set("requiredActions", Json.arr(pending.filterNot(_ == "VERIFY_EMAIL")*)): Unit
        call("PUT", s"/users/$userId", Some(user)).flatMap(ok(userId))
      case (404, _) => Left(RealmError.NotFound(userId))
      case (s, b)   => Left(RealmError.Failed(s"$s ${b.take(200)}"))
    }

  def listUsers(): Either[RealmError, Vector[RealmUser]] =
    call("GET", "/users?max=500").flatMap {
      case (200, body) =>
        Right(Json.parse(body).filter(_.isArray).map(_.elements.asScala.toVector).getOrElse(Vector.empty)
          .map(u => RealmUser(u.textOr("id", ""), u.textOr("email", ""), u.bool("enabled").getOrElse(true))))
      case (s, b) => Left(RealmError.Failed(s"$s ${b.take(200)}"))
    }

  def deleteUser(userId: String): Either[RealmError, Unit] = call("DELETE", s"/users/$userId").flatMap(ok(userId))
