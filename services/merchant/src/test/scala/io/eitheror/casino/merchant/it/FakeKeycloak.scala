package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*

import java.util.UUID
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Keycloak's admin API as far as the payments backoffice uses it (research R10): the master realm's
 * token, and a realm's users, roles, attributes, passwords and enabled flags, in memory. A test reads
 * what was written and disables a user as an administrator would at the sign-in.
 */
final class FakeKeycloak extends FakeServer:

  final class User(val id: String, val email: String):
    var enabled: Boolean = true
    var emailVerified: Boolean = false
    var password: Option[String] = None
    val roles      = mutable.Set.empty[String]
    var requiredActions: Vector[String] = Vector.empty
    var attributes: com.fasterxml.jackson.databind.node.ObjectNode = Json.obj()

  private val users = mutable.LinkedHashMap.empty[String, User]
  val Roles = Set("platform_admin", "compliance_officer", "fraud_analyst", "resource_owner", "resource_admin", "support_agent",
    "resource_operator", "resource_finance", "resource_developer", "resource_viewer")

  def user(id: String): Option[User] = synchronized(users.get(id))
  def byEmail(email: String): Option[User] = synchronized(users.values.find(_.email == email))
  def disable(id: String): Unit = synchronized(users.get(id).foreach(_.enabled = false))
  def attribute(id: String, name: String): Vector[String] =
    user(id).flatMap(u => Option(u.attributes.get(name))).map(_.elements.asScala.map(_.asText).toVector).getOrElse(Vector.empty)

  private val Users = """/admin/realms/([^/]+)/users""".r
  private val One   = """/admin/realms/([^/]+)/users/([^/]+)""".r
  private val Pwd   = """/admin/realms/([^/]+)/users/([^/]+)/reset-password""".r
  private val Mapping = """/admin/realms/([^/]+)/users/([^/]+)/role-mappings/realm""".r
  private val Role  = """/admin/realms/([^/]+)/roles/([^/]+)""".r

  private def actions(b: com.fasterxml.jackson.databind.JsonNode): Option[Vector[String]] =
    Option(b.get("requiredActions")).filter(_.isArray).map(_.elements.asScala.map(_.asText).toVector)

  protected def route(r: Received): Answer = synchronized {
    (r.method, r.path) match
      case ("POST", "/realms/master/protocol/openid-connect/token") =>
        if r.body.contains("username=admin") && r.body.contains("password=admin") then Answer.ok(Json.obj("access_token" -> "admin-token"))
        else Answer(401, """{"error":"invalid_grant"}""")
      case _ if !r.headers.get("authorization").contains("Bearer admin-token") => Answer(401, """{"error":"unauthorized"}""")
      case ("POST", Users(_)) =>
        val b = r.json.get
        val email = b.textOr("email", "")
        if users.values.exists(_.email == email) then Answer(409, """{"errorMessage":"User exists with same email"}""")
        else
          val u = User(UUID.randomUUID().toString, email)
          u.requiredActions = actions(b).getOrElse(Vector.empty)
          users.update(u.id, u)
          Answer(201, "", headers = Map("Location" -> s"$address/admin/realms/eitheror/users/${u.id}"))
      case ("GET", Users(_)) =>
        Answer.ok(Json.arr(users.values.toVector.map(u => Json.obj("id" -> u.id, "email" -> u.email, "username" -> u.email,
          "enabled" -> u.enabled, "emailVerified" -> u.emailVerified))*))
      case ("GET", Role(_, role)) => if Roles(role) then Answer.ok(Json.obj("id" -> s"role-$role", "name" -> role)) else Answer(404, "{}")
      case ("POST", Mapping(_, id)) =>
        users.get(id).fold(Answer(404, "{}")) { u =>
          r.json.get.elements.asScala.foreach(n => u.roles += n.textOr("name", "")); Answer(204, "")
        }
      case ("PUT", Pwd(_, id)) => users.get(id).fold(Answer(404, "{}")) { u => u.password = r.json.get.text("value"); Answer(204, "") }
      case ("GET", One(_, id)) =>
        users.get(id).fold(Answer(404, "{}"))(u => Answer.ok(Json.obj("id" -> u.id, "email" -> u.email, "username" -> u.email,
          "enabled" -> u.enabled, "emailVerified" -> u.emailVerified, "requiredActions" -> Json.arr(u.requiredActions*),
          "attributes" -> u.attributes)))
      case ("PUT", One(_, id)) =>
        users.get(id).fold(Answer(404, "{}")) { u =>
          val b = r.json.get
          b.bool("emailVerified").foreach(u.emailVerified = _)
          actions(b).foreach(u.requiredActions = _)
          Option(b.get("attributes")).collect { case o: com.fasterxml.jackson.databind.node.ObjectNode => o }.foreach(u.attributes = _)
          Answer(204, "")
        }
      case ("DELETE", One(_, id)) => if users.remove(id).isDefined then Answer(204, "") else Answer(404, "{}")
      case _ => Answer(404, """{"error":"NO_ROUTE"}""")
  }
