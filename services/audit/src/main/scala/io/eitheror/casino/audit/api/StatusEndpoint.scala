package io.eitheror.casino.audit.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.audit.exports.ExportFormat
import io.eitheror.casino.audit.ingest.{Quarantine, SourceState}
import io.eitheror.casino.audit.stored.Source
import io.eitheror.casino.contract.access.Routes

import java.time.Duration

import Wire.*

/**
 * `GET /v1/audit-status` (FR-024; contracts/routes.md): per source, what was stored and passed over, the
 * last arrival and the lag behind it, what is quarantined and skewed; per brand, its retention and last
 * export; and which installation sources are read (FR-019: modules and erasures; the rest "not stated"
 * until ankka publishes a project's events, research R7). The installation's compliance role and the
 * owner see every source; a brand's compliance role names its brand.
 */
final class StatusEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/audit-status"):
  val acl: Acl = Routes.staffMember(ctx.authenticate())

  get("") { () => StatusRoute.status(ctx, principal, request.query.toSeq.toMap.get("brand")) }

object StatusRoute:
  val Read      = "read"
  val NotStated = "not stated"

  /** What the installation's actions on a brand are read from, and what is not yet stated (research R7). */
  val InstallationSources: Vector[(String, String)] = Vector(
    "modules" -> Read, "erasures" -> Read, "rollbacks" -> NotStated, "restores" -> NotStated, "grants" -> NotStated, "secrets" -> NotStated)

  /** The brands a caller may see the status of, or the refusal. */
  def scoped(ctx: Ctx, p: Principal, brand: Option[String]): Either[(Int, String), (Vector[String], Boolean)] =
    val every = p.issuer.contains(Routes.StaffIssuer) && (p.roles.contains("platform_admin") || Routes.auditScope(p) == Routes.BrandSet.Every)
    val brands = ctx.settings.brands
    if every then
      brand match
        case Some(b) if !brands.contains(b) => Left(404 -> s"unknown_brand: no brand $b is read here")
        case Some(b)                        => Right(Vector(b) -> false)
        case None                           => Right(brands -> true)
    else
      Routes.auditScope(p) match
        case Routes.BrandSet.Only(slugs) if slugs.nonEmpty =>
          brand.filter(slugs) match
            case Some(b) => Right(Vector(b) -> false)
            case None    => Left(403 -> s"${RecordsRoute.BrandNotInRoles}: name one of your brands")
        case _ => Left(403 -> s"${Routes.NotAuditCompliance}: the audit's compliance roles or platform_admin are required")

  private def source(ctx: Ctx, s: Source): String =
    val c   = ctx.clients.componentClient.forKeyValueEntity(SourceState.entityId(s.name)).call(SourceState.get).invoke()
    val lag = c.lastReceived.map(l => math.max(0L, Duration.between(l, ctx.now()).getSeconds))
    obj(
      "source"       -> Some(text(s.name)),
      "stored"       -> Some(c.stored.toString),
      "passedOver"   -> Some(c.passedOver.toString),
      "lastReceived" -> Some(c.lastReceived.fold("null")(i => text(i.toString))),
      "lagSeconds"   -> Some(lag.fold("null")(_.toString)),
      "quarantined"  -> Some(Quarantine.countBySource(ctx.clients.viewClient.forView(Quarantine), s.name).toString),
      "skewed"       -> Some(c.skewed.toString)
    )

  private def brand(ctx: Ctx, b: String): String =
    val format = ctx.clients.componentClient.forKeyValueEntity(ExportFormat.entityId(b)).call(ExportFormat.get).invoke()
    obj(
      "brand"               -> Some(text(b)),
      "retentionYears"      -> Some(ctx.retentionYears(b).toString),
      "lastExport"          -> Some(format.lastExport.fold("null")(i => text(i.toString))),
      "sources"             -> Some(Vector(Source.Brand(b), Source.Modules(b)).map(source(ctx, _)).mkString("[", ",", "]")),
      "installationSources" -> Some(obj(InstallationSources.map((k, v) => k -> Some(text(v)))*))
    )

  def status(ctx: Ctx, p: Principal, asked: Option[String]): Answer =
    scoped(ctx, p, asked) match
      case Left((status, problem)) => refused(status, problem)
      case Right((brands, shared)) =>
        json(200, obj(
          "brands" -> Some(brands.map(brand(ctx, _)).mkString("[", ",", "]")),
          "shared" -> Option.when(shared)(Vector(Source.Payments).map(source(ctx, _)).mkString("[", ",", "]"))
        ))
