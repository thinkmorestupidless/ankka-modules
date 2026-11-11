package io.eitheror.casino.reporting.api

import com.thinkmorestupidless.ankka.http.Acl
import io.eitheror.casino.contract.access.Routes

/**
 * The admission table of contracts/routes.md (FR-023, FR-032), as the route classes of `contract.access.Routes`: the
 * reports for the three roles, the dashboard and the dispute report for every operator of the brand, compliance and
 * the day-end for the three and their own role, the traders report as compliance, the market report for the three
 * and `markets`, the settings for
 * `brand_admin` and `platform_admin`. A token of another brand is refused `brand_not_in_roles` by every one of them.
 */
final class Access(slug: String, authenticate: () => Acl):
  /** The Reports tab's roles (the original's `requireBrandAccessWithRoles`). */
  private val reportRoles = Seq("brand_admin", "resource_admin", "platform_admin")

  private def holding(roles: Seq[String]): Acl = Routes.operatorHolding(slug, roles*)(authenticate())

  def reports: Acl     = holding(reportRoles)
  def operator: Acl    = Routes.operator(slug, authenticate())
  def compliance: Acl  = holding(reportRoles :+ "brand_compliance")
  def dayEnd: Acl      = holding(reportRoles :+ "brand_finance")
  def predictions: Acl = reports
  /** The traders report, as the compliance exports (decided 2026-11-09). */
  def traders: Acl     = compliance
  def markets: Acl     = holding(reportRoles :+ "markets")
  def settings: Acl    = holding(Seq("brand_admin", "platform_admin"))
  def status: Acl      = operator
  def erasure: Acl     = Routes.playersOnly
