package io.eitheror.casino.audit.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.roles.{Grant, Grants, RealmError, Revoke, RoleGrant}
import io.eitheror.casino.contract.access.Routes

import scala.util.Try

import Wire.*

final case class RoleAsked(brand: Option[String] = None)

/**
 * `/v1/audit-roles` (FR-016; contracts/routes.md): the installation's owner grants and revokes the
 * audit's roles through the realm, and lists each role's holders and how each came to hold it. Each
 * grant and revoke is a record.
 */
final class RolesEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/audit-roles"):
  val acl: Acl = Routes.installationOwner(ctx.authenticate())

  putBody("/{role}/{person}") { (role: String, person: String, raw: String) => RolesRoute.grant(ctx, principal, role, person, raw) }
  delete("/{role}/{person}") { (role: String, person: String) => RolesRoute.revoke(ctx, principal, role, person, request.query.toSeq.toMap) }
  get("/{role}") { (role: String) => RolesRoute.holders(ctx, role) }

object RolesRoute:
  given JsonValueCodec[RoleAsked] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))

  private def brandsOf(ctx: Ctx): Set[String] = ctx.settings.brands.toSet

  private def realmRefused(e: RealmError): Answer = e match
    case RealmError.NotFound(id)       => refuse(404, "unknown_person", s"no user $id in the realm")
    case RealmError.NotConfigured(why) => refuse(503, "realm_unavailable", why)
    case RealmError.Failed(why)        => refuse(502, "realm_failed", why)

  private def body(text: String): Either[String, RoleAsked] =
    Try(readFromString[RoleAsked](if text.trim.isEmpty then "{}" else text)).toEither.left.map(_ => "bad_request: the body is not the route's shape")

  def grant(ctx: Ctx, p: Principal, name: String, person: String, raw: String): Answer =
    body(raw).flatMap(b => Grants.role(name, b.brand, brandsOf(ctx))) match
      case Left(problem) => refused(400, problem)
      case Right(role) =>
        role.realmRoles.foldLeft[Either[RealmError, Unit]](Right(()))((done, r) => done.flatMap(_ => Grants.realm.assignRole(person, r))) match
          case Left(e) => realmRefused(e)
          case Right(()) =>
            val actor = Own.staff(ctx.calls, role.recordBrand, p.subject)
            val now   = ctx.now()
            val ref   = ctx.clients.componentClient.forKeyValueEntity(RoleGrant.entityId(role.name, Grants.holder(ctx.calls, person)))
            if ref.call(RoleGrant.grant).invoke(Grant(actor, role.brand, now)).changed then
              Grants.recorded(ctx.calls, Grants.GrantAction, role, actor, person, now)
            json(200, obj("role" -> Some(text(role.name)), "brand" -> role.brand.map(text), "status" -> Some(text(RoleGrant.Granted))))

  /** A brand compliance revoke names its brand as `?brand=`; the brand's own `brand_<slug>` is left, being an operator's role too. */
  def revoke(ctx: Ctx, p: Principal, name: String, person: String, query: Map[String, String]): Answer =
    Grants.role(name, query.get("brand"), brandsOf(ctx)) match
      case Left(problem) => refused(400, problem)
      case Right(role) =>
        val ref  = ctx.clients.componentClient.forKeyValueEntity(RoleGrant.entityId(role.name, Grants.holder(ctx.calls, person)))
        val left = ref.call(RoleGrant.get).invoke().brands - role.brand.getOrElse("")
        // The realm role goes once no brand's grant is left for it.
        val taken = if role.name == Routes.BrandCompliance && left.nonEmpty then Right(()) else Grants.realm.removeRole(person, role.name)
        taken match
          case Left(e) => realmRefused(e)
          case Right(()) =>
            val actor = Own.staff(ctx.calls, role.recordBrand, p.subject)
            val now   = ctx.now()
            if ref.call(RoleGrant.revoke).invoke(Revoke(actor, role.brand, now)).changed then
              Grants.recorded(ctx.calls, Grants.RevokeAction, role, actor, person, now)
            json(200, obj("role" -> Some(text(role.name)), "brand" -> role.brand.map(text), "status" -> Some(text(RoleGrant.Revoked))))

  /** The role's holders in the realm, each by realm id, and `granted` or `outside`. */
  def holders(ctx: Ctx, name: String): Answer =
    if !Grants.managed(ctx.settings.brands).contains(name) then refuse(400, "unknown_role", s"$name is not a role the audit grants")
    else
      Grants.realm.usersWithRole(name) match
        case Left(e) => realmRefused(e)
        case Right(people) =>
          val rows = people.map { person =>
            val s   = ctx.clients.componentClient.forKeyValueEntity(RoleGrant.entityId(name, Grants.holder(ctx.calls, person))).call(RoleGrant.get).invoke()
            val how = if s.status == RoleGrant.Granted then RoleGrant.Granted else RoleGrant.Outside
            obj("person" -> Some(text(person)), "held" -> Some(text(how)),
              "brands" -> Option.when(s.brands.nonEmpty)(s.brands.toVector.sorted.map(text).mkString("[", ",", "]")))
          }
          json(200, obj("role" -> Some(text(name)), "holders" -> Some(rows.mkString("[", ",", "]"))))
