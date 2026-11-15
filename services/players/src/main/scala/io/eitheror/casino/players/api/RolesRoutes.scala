package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.contract.Layout
import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.clients.{Neighbour, Target}
import io.eitheror.casino.players.roles.*

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

import Answers.*

/**
 * The brand's roles (spec 030 amendments P12, P13; contracts/roles.md): every role and what it carries,
 * a role made, changed, reset and deleted, and an operator's roles given. Every change is checked against
 * every role as the view holds them, and is in force in `players` at once and in every other service of
 * the brand within its next read of `GET …/players/roles`.
 */
final class RolesRoutes(ctx: Ctx):
  private def components = ctx.clients.componentClient
  private def views      = ctx.clients.viewClient
  private def now(): Instant = PlayersRuntime.now()

  /** `{"roles": [{name, description, permissions, default}]}`: what `RoleBags.parse` reads. */
  def list: Reply = ok(Json.obj("roles" -> Json.arr(Roles.all(views).map(rowOf)*)))

  /** `{"description", "permissions": […]}`: the role made, or changed to carry exactly these. */
  def set(p: Principal, name: String, text: String): Reply =
    val body = Json.parse(text).filter(_.isObject)
    RoleRules.nameRefusal(name) match
      case Some((code, message)) => refusal(400, code, message)
      case None =>
        body match
          case None => refusal(400, "invalid_body", "a role is {\"description\": …, \"permissions\": […]}")
          case Some(b) =>
            val permissions = b.strings("permissions").toSet
            val description = b.textOr("description", "").take(200)
            RoleRules.unknown(permissions) match
              case Some(unknown) =>
                refusal(400, RoleRules.UnknownPermission, s"$unknown is not a permission", "permission" -> unknown)
              case None if RoleRules.leavesNoChanger(Roles.bags(views), name, Some(permissions)) =>
                lastChanger(name)
              case None =>
                done(components.forEventSourcedEntity(Role.id(name)).call(Role.set).invoke(SetRole(name, description, permissions, author(p), now())))

  def reset(p: Principal, name: String): Reply =
    OperatorPermissions.DefaultRoles.get(name) match
      case None if RoleRules.nameRefusal(name).isDefined =>
        val (code, message) = RoleRules.nameRefusal(name).get
        refusal(400, code, message)
      case None => refusal(409, RoleRules.NotDefault, s"$name is not a default role: it has no default permissions to reset to")
      case Some(bag) if RoleRules.leavesNoChanger(Roles.bags(views), name, Some(bag)) => lastChanger(name)
      case Some(_) => done(components.forEventSourcedEntity(Role.id(name)).call(Role.reset).invoke(RoleAct(author(p), now())))

  def delete(p: Principal, name: String): Reply =
    RoleRules.nameRefusal(name) match
      case Some((code, message)) => refusal(400, code, message)
      case None                  => deleteRole(p, name)

  private def deleteRole(p: Principal, name: String): Reply =
    if OperatorPermissions.DefaultRoles.contains(name) then
      refusal(409, RoleRules.DefaultRole, s"$name is a default role: it is reset, never deleted")
    else if RoleRules.leavesNoChanger(Roles.bags(views), name, None) then lastChanger(name)
    else done(components.forEventSourcedEntity(Role.id(name)).call(Role.delete).invoke(RoleAct(author(p), now())))

  /**
   * `{"roles": […]}`: every role the brand gives `operator`, replacing what it gave before. Refused, naming
   * the permission, when the giver's own permissions lack one the roles carry; then merchant is asked to set
   * them in the realm (amendment H6), and only once it has, the giving is recorded.
   */
  def give(p: Principal, operator: String, text: String): Reply =
    val roles = Json.parse(text).filter(_.isObject).map(_.strings("roles").toSet)
    roles match
      case None => refusal(400, "invalid_body", "a giving is {\"roles\": […]}")
      case Some(asked) =>
        val bags = Roles.bags(views)
        asked.toVector.sorted.find(r => RoleRules.nameRefusal(r).isDefined || !bags.contains(r)) match
          case Some(r) if RoleRules.nameRefusal(r).isDefined =>
            refusal(400, RoleRules.ReservedRole, s"$r is not a role the brand gives", "role" -> r)
          case Some(r) => refusal(404, RoleRules.NoSuchRole, s"the brand has no role $r", "role" -> r)
          case None =>
            val holds = OperatorPermissions.permissionsOf(p.roles, bags)
            val carried = asked.flatMap(bags)
            RoleRules.escalation(holds, carried) match
              case Some(missing) =>
                refusal(403, RoleRules.GiverLacks, s"the $missing permission is needed to give a role that carries it", "permission" -> missing)
              case None => setAtMerchant(p, operator, asked)

  private def setAtMerchant(p: Principal, operator: String, roles: Set[String]): Reply =
    val target = Target(RolesRoutes.Merchant, "", Some(RolesRoutes.PaymentsProject))
    val path   = s"/v1/operators/${Neighbour.segment(operator)}/brand-roles/${Neighbour.segment(ctx.slug)}"
    val call   = Neighbour.call(ctx.clients.services, target, "PUT", path, Some(Json.obj("roles" -> Json.arr(roles.toVector.sorted*))))
    Try(Await.result(call, RolesRoutes.MerchantBound)).toEither match
      case Left(e) => refusal(503, "merchant_unavailable", s"the shared payments could not be asked to set the roles: ${e.getMessage}")
      case Right(r) if !r.ok =>
        val code = r.body.flatMap(_.text("error")).getOrElse("merchant_refused")
        refusal(if r.status == 404 then 404 else 502, code, s"the shared payments refused to set the roles (${r.status})")
      case Right(_) =>
        val s = components.forEventSourcedEntity(RoleAssignment.id(operator)).call(RoleAssignment.give)
          .invoke(Give(operator, roles, author(p), now()))
        ok(Json.obj("operator" -> operator, "brand" -> ctx.slug, "roles" -> Json.arr(s.roles.toVector.sorted*)))

  private def lastChanger(name: String): Reply =
    refusal(409, RoleRules.LastRoleChanger, s"$name is the last role that may change roles: nobody could change roles after this")

  private def done(a: RoleAnswer): Reply =
    a.refusal match
      case Some(RoleRules.NoSuchRole)  => refusal(404, RoleRules.NoSuchRole, "no such role")
      case Some(RoleRules.DefaultRole) => refusal(409, RoleRules.DefaultRole, s"${a.state.name} is a default role: it is reset, never deleted")
      case Some(code)                  => refusal(409, code, code.replace('_', ' '))
      case None =>
        PlayersRuntime.bags.refresh()
        ok(stateOf(a.state))

  private def stateOf(s: RoleState): ObjectNode =
    Json.obj("name" -> s.name, "description" -> s.description, "permissions" -> Json.arr(s.permissions.toVector.sorted*),
      "default" -> s.default, "deleted" -> s.deleted)

  private def rowOf(r: RoleRow): ObjectNode =
    Json.obj("name" -> r.name, "description" -> r.description, "permissions" -> Json.arr(r.permissions*), "default" -> r.default)

  private def author(p: Principal): String = p.subject

object RolesRoutes:
  val Merchant        = "merchant"
  val PaymentsProject = Layout.SharedProjects.collectFirst { case (p, ss) if ss.contains(Merchant) => p }.getOrElse("payments")
  val MerchantBound: FiniteDuration = 10.seconds

  /** The description a default role is seeded with. */
  val Descriptions: Map[String, String] = Map(
    OperatorPermissions.Support    -> "Reads a player's account and writes notes and messages",
    OperatorPermissions.Finance    -> "Money: adjustments, withdrawals, rewards and the finance reports",
    OperatorPermissions.Compliance -> "A player's standing, identity, protection and fraud",
    OperatorPermissions.Admin      -> "Everything, the brand's configuration and its roles"
  )

  /** Seeds the role matrix's roles a brand does not yet have; a role that exists is left as it stands. */
  def seed(components: com.thinkmorestupidless.ankka.sdk.ComponentClient, at: Instant): Unit =
    OperatorPermissions.DefaultRoles.toVector.sortBy(_._1).foreach { (name, bag) =>
      components.forEventSourcedEntity(Role.id(name)).call(Role.seed).invoke(SetRole(name, Descriptions(name), bag, "seed", at)): Unit
    }
