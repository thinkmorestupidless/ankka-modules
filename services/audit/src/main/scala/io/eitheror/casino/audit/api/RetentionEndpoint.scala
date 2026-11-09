package io.eitheror.casino.audit.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.stored.StoredChange
import io.eitheror.casino.audit.sweep.{Raise, RetentionEntity}
import io.eitheror.casino.contract.access.Routes

import scala.util.Try

import Wire.*

final case class RetentionAsked(years: Int = 0)

/**
 * `/v1/audit-retention/{brand}` (FR-021; contracts/routes.md): a brand's retention and the installation's;
 * raised by the installation's owner, never lowered, and each raise a record.
 */
final class RetentionEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/audit-retention"):
  val acl: Acl = Routes.staffMember(ctx.authenticate())

  get("/{brand}") { (brand: String) => RetentionRoute.read(ctx, principal, brand) }
  putBody("/{brand}") { (brand: String, raw: String) => RetentionRoute.raise(ctx, principal, brand, raw) }

object RetentionRoute:
  given JsonValueCodec[RetentionAsked] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))

  private def owner(p: Principal): Boolean = p.issuer.contains(Routes.StaffIssuer) && p.roles.contains("platform_admin")

  private def reads(p: Principal, brand: String): Boolean =
    owner(p) || (p.issuer.contains(Routes.StaffIssuer) && (Routes.auditScope(p) match
      case Routes.BrandSet.Every       => true
      case Routes.BrandSet.Only(slugs) => slugs(brand)))

  private def known(ctx: Ctx, brand: String): Boolean = ctx.everyBrand(brand)

  def read(ctx: Ctx, p: Principal, brand: String): Answer =
    if !reads(p, brand) then refuse(403, Routes.NotAuditCompliance, s"reading $brand's retention needs its compliance role, the installation's, or platform_admin")
    else if !known(ctx, brand) then refuse(404, "unknown_brand", s"no brand $brand is read here")
    else json(200, obj("brand" -> Some(text(brand)), "years" -> Some(ctx.retentionYears(brand).toString),
      "installation" -> Some(ctx.settings.retentionYears.toString)))

  def raise(ctx: Ctx, p: Principal, brand: String, raw: String): Answer =
    if !owner(p) then refuse(403, Routes.NotInstallationOwner, "raising a retention needs platform_admin")
    else if !known(ctx, brand) then refuse(404, "unknown_brand", s"no brand $brand is read here")
    else
      Try(readFromString[RetentionAsked](raw)).toOption.filter(_.years > 0) match
        case None => refuse(400, "bad_request", "the body is {\"years\": <a whole number above 0>}")
        case Some(asked) =>
          val r = ctx.clients.componentClient.forKeyValueEntity(RetentionEntity.entityId(brand)).call(RetentionEntity.raise)
            .invoke(Raise(asked.years, ctx.settings.retentionYears))
          r.refusal match
            case Some(problem) => refused(409, problem)
            case None =>
              Own.record(ctx.calls, brand, "audit.retention", Own.staff(ctx.calls, brand, p.subject), "brand", brand, "done", None,
                Vector(StoredChange("retentionYears", Some(r.before.toString), r.after.map(_.toString))), ctx.now()): Unit
              json(200, obj("brand" -> Some(text(brand)), "years" -> r.after.map(_.toString),
                "installation" -> Some(ctx.settings.retentionYears.toString)))
