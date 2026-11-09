package io.eitheror.casino.audit.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.query.{Asked, Filters, Query}
import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.contract.access.Routes

import Wire.*

/**
 * `GET /v1/audit-records` (FR-013, FR-015; contracts/routes.md): the store, filtered, a page at a time in
 * order of occurrence. A brand's compliance officer names a brand their roles hold and sees every record
 * of it, of either visibility (FR-020); the installation's sees every brand, or the one named. Each query is a record of
 * its own, unless it asks after the audit's own records alone (one level, no recursion).
 */
final class RecordsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/audit-records"):
  val acl: Acl = Routes.auditCompliance(ctx.authenticate())

  get("") { () =>
    Query.parse(request.query.toSeq.toMap, ctx.settings.pageLimit).fold(p => refused(400, p), RecordsRoute.query(ctx, principal, _))
  }

object RecordsRoute:
  val BrandNotInRoles = "brand_not_in_roles"
  val Action          = "audit.query"

  /** The filters a caller's roles allow, or the refusal. */
  def scoped(ctx: Ctx, p: Principal, asked: Asked): Either[String, Filters] =
    val brands: Either[String, Set[String]] = Routes.auditScope(p) match
      case Routes.BrandSet.Every => Right(asked.brand.fold(ctx.everyBrand)(Set(_)))
      case Routes.BrandSet.Only(slugs) =>
        asked.brand.filter(slugs) match
          case Some(b) => Right(Set(b))
          case None    => Left(asked.brand.fold(s"$BrandNotInRoles: name one of your brands")(b => s"$BrandNotInRoles: $b is not one of your brands"))
    brands.map { scope =>
      asked.filters.copy(
        brands = Option.when(asked.brand.isDefined)(scope),
        correlations = asked.correlation.fold(Vector.empty)(ctx.correlations(_, scope))
      )
    }

  def query(ctx: Ctx, p: Principal, asked: Asked): Answer =
    scoped(ctx, p, asked) match
      case Left(problem) => refused(403, problem)
      case Right(filters) =>
        val page     = Records.page(ctx.records, filters, asked.after, asked.limit)
        val brand    = asked.brand.getOrElse(Own.Installation)
        val retained = ctx.retainedFrom(brand)
        if !filters.service.contains("audit") then
          Own.record(ctx.calls, brand, Action, Own.staff(ctx.calls, brand, p.subject), "audit", "records", "done", None, Vector(
            Own.change("filters", describe(filters)),
            Own.change("range", s"${filters.from.fold("")(_.toString)}..${filters.to.fold("")(_.toString)}"),
            Own.change("count", page.rows.size.toString)
          ), ctx.now()): Unit
        Wire.page(page.rows, page.next.map(_.encode), filters.from.filter(_.isBefore(retained)).map(_ => retained))

  /** The filters as a record names them: each one asked, by name; the brands sorted. */
  def describe(f: Filters): String =
    Vector(
      f.brands.map(b => s"brand=${b.toVector.sorted.mkString("|")}"),
      f.subject.map(v => s"subject=$v"), f.actor.map(v => s"actor=$v"), f.service.map(v => s"service=$v"),
      f.action.map(v => s"action=$v"), f.outcome.map(v => s"outcome=$v"), f.reasonCode.map(v => s"reasonCode=$v"),
      f.category.map(v => s"category=$v"), f.visibility.map(v => s"visibility=$v"), f.amountMin.map(v => s"amountMin=$v"),
      f.amountMax.map(v => s"amountMax=$v"), f.currency.map(v => s"currency=$v"),
      Option.when(f.correlations.nonEmpty)("correlation")
    ).flatten.mkString("&")
