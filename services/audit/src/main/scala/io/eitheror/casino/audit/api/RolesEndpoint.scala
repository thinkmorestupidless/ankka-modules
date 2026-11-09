package io.eitheror.casino.audit.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.roles.{Grant, Grants, RealmError, Revoke, RoleGrant}
import io.eitheror.casino.contract.access.Routes


import Wire.*

/**
 * `/v1/audit-roles` (FR-016; contracts/routes.md): the installation's owner grants and revokes the
 * audit's roles through the realm, and lists each role's holders and how each came to hold it. Each
 * grant and revoke is a record.
 */
final class RolesEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/audit-roles"):
  val acl: Acl = Routes.installationOwner(ctx.authenticate())

  put("/{role}/{person}") { (role: String, person: String) => RolesRoute.grant(ctx, principal, role, person) }
  delete("/{role}/{person}") { (role: String, person: String) => RolesRoute.revoke(ctx, principal, role, person) }
  get("/{role}") { (role: String) => RolesRoute.holders(ctx, role) }

object RolesRoute:
  private def brandsOf(ctx: Ctx): Set[String] = ctx.settings.brands.toSet

  private def realmRefused(e: RealmError): Answer = e match
    case RealmError.NotFound(id)       => refuse(404, "unknown_person", s"no user $id in the realm")
    case RealmError.NotConfigured(why) => refuse(503, "realm_unavailable", why)
    case RealmError.Failed(why)        => refuse(502, "realm_failed", why)

  /** `{person}` is a holder's installation pseudonym, as the listing answers, or a realm id. */
  def grant(ctx: Ctx, p: Principal, name: String, named: String): Answer =
    Grants.role(name, brandsOf(ctx)) match
      case Left(problem) => refused(400, problem)
      case Right(role) =>
        val person = Grants.person(ctx.clients.componentClient, named)
        Grants.realm.assignRole(person, role.name) match
          case Left(e) => realmRefused(e)
          case Right(()) =>
            val actor = Own.staff(ctx.calls, role.recordBrand, p.subject)
            val now   = ctx.now()
            val ref   = ctx.clients.componentClient.forKeyValueEntity(RoleGrant.entityId(role.name, Grants.holder(ctx.calls, person)))
            if ref.call(RoleGrant.grant).invoke(Grant(actor, now)).changed then
              Grants.recorded(ctx.calls, Grants.GrantAction, role, actor, person, now)
            json(200, obj("role" -> Some(text(role.name)), "brand" -> role.brand.map(text), "status" -> Some(text(RoleGrant.Granted))))

  /** Removes exactly the role named; `{person}` as for a grant. */
  def revoke(ctx: Ctx, p: Principal, name: String, named: String): Answer =
    Grants.role(name, brandsOf(ctx)) match
      case Left(problem) => refused(400, problem)
      case Right(role) =>
        val person = Grants.person(ctx.clients.componentClient, named)
        val ref    = ctx.clients.componentClient.forKeyValueEntity(RoleGrant.entityId(role.name, Grants.holder(ctx.calls, person)))
        Grants.realm.removeRole(person, role.name) match
          case Left(e) => realmRefused(e)
          case Right(()) =>
            val actor = Own.staff(ctx.calls, role.recordBrand, p.subject)
            val now   = ctx.now()
            if ref.call(RoleGrant.revoke).invoke(Revoke(actor, now)).changed then
              Grants.recorded(ctx.calls, Grants.RevokeAction, role, actor, person, now)
            json(200, obj("role" -> Some(text(role.name)), "brand" -> role.brand.map(text), "status" -> Some(text(RoleGrant.Revoked))))

  /** The role's holders in the realm, each by installation pseudonym (SC-002), and `granted` or `outside`. */
  def holders(ctx: Ctx, name: String): Answer =
    if !Grants.managed(ctx.settings.brands).contains(name) then refuse(400, "unknown_role", s"$name is not a role the audit grants")
    else
      Grants.realm.usersWithRole(name) match
        case Left(e) => realmRefused(e)
        case Right(people) =>
          val rows = people.map { person =>
            val pseudonym = Grants.holder(ctx.calls, person)
            val s   = ctx.clients.componentClient.forKeyValueEntity(RoleGrant.entityId(name, pseudonym)).call(RoleGrant.get).invoke()
            val how = if s.status == RoleGrant.Granted then RoleGrant.Granted else RoleGrant.Outside
            obj("person" -> Some(text(pseudonym)), "held" -> Some(text(how)))
          }
          json(200, obj("role" -> Some(text(name)), "holders" -> Some(rows.mkString("[", ",", "]"))))
