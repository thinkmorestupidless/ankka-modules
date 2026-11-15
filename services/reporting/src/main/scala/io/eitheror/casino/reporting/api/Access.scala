package io.eitheror.casino.reporting.api

import com.thinkmorestupidless.ankka.http.Acl
import io.eitheror.casino.contract.access.{RoleBags, Routes}

/**
 * The admission table of contracts/routes.md (FR-023, FR-032) as spec 030 amends it (E3): each operator route requires
 * one permission of contracts/roles.md's matrix (`Routes.operatorMay`), decided from the brand's roles as `bags` last
 * read them; no role is named. The dashboard and the status for `dashboard.read`; the Reports tab's reports and the
 * prediction markets' summary and market report for `report.read`; the compliance exports and the traders report
 * (decided 2026-11-09) for `report.compliance`; the day-end for `report.dayEnd`; the dispute report for
 * `export.player`; the settings for `config.brand`. A token of another brand is refused `brand_not_in_roles` by every one.
 */
final class Access(slug: String, authenticate: () => Acl, bags: RoleBags):
  private def may(permission: String): Acl = Routes.operatorMay(slug, permission, bags, authenticate())

  def dashboard: Acl   = may("dashboard.read")
  def reports: Acl     = may("report.read")
  def compliance: Acl  = may("report.compliance")
  def dayEnd: Acl      = may("report.dayEnd")
  def disputes: Acl    = may("export.player")
  def predictions: Acl = reports
  /** The traders report, as the compliance exports (decided 2026-11-09). */
  def traders: Acl     = compliance
  def markets: Acl     = reports
  def settings: Acl    = may("config.brand")
  def status: Acl      = dashboard
  /**
   * An operator of the brand, no permission named: the documents' routes, whose record names the permission
   * (`Documents.permissionOf`), and a path no route serves.
   */
  def operator: Acl    = Routes.operator(slug, authenticate())
  def erasure: Acl     = Routes.playersOnly
