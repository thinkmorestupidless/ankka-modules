package io.eitheror.casino.merchant.hierarchy

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs

import java.time.Instant

/**
 * The backoffice's hierarchy (FR-002): one row per holding, company and sub-brand, so a parent's
 * children are a query by parent and never a list the parent keeps (FR-001). One view per kind, since
 * a view reads one entity's events.
 */
final case class OrgRow(id: String, name: String, status: String, parentId: Option[String], createdAt: Instant)

final case class SubBrandRow(id: String, companyId: String, holdingId: String, name: String, subBrandType: String, status: String,
    brand: Option[String], createdAt: Instant)

private def orgRow(row: Option[OrgRow], id: String, event: OrgEvent): Option[OrgRow] = (row, event) match
  case (_, OrgEvent.Created(name, parent, _, at))     => Some(OrgRow(id, name, OrgStatus.Active, parent, at))
  case (Some(r), OrgEvent.StatusChanged(status, _, _)) => Some(r.copy(status = status))
  case (other, _)                                     => other

final class HoldingsView extends View[OrgEvent, OrgRow]:
  def onChange(event: OrgEvent): Effect = orgRow(rowState, updateContext.subject, event).fold(effects.ignore())(effects.updateRow)

object HoldingsView extends View.Companion[HoldingsView, OrgEvent, OrgRow](
      componentId = ComponentId("holdings"),
      source = ChangeSource.eventsOf(Holding),
      rowSerializer = MerchantCodecs.serializer[OrgRow]("org-row")
    ):
  def create(ctx: ViewComponentContext) = new HoldingsView

final class CompaniesView extends View[OrgEvent, OrgRow]:
  def onChange(event: OrgEvent): Effect = orgRow(rowState, updateContext.subject, event).fold(effects.ignore())(effects.updateRow)

object CompaniesView extends View.Companion[CompaniesView, OrgEvent, OrgRow](
      componentId = ComponentId("companies"),
      source = ChangeSource.eventsOf(Company),
      rowSerializer = MerchantCodecs.serializer[OrgRow]("org-row")
    ):
  def create(ctx: ViewComponentContext) = new CompaniesView

final class SubBrandsView extends View[SubBrandEvent, SubBrandRow]:
  def onChange(event: SubBrandEvent): Effect = (rowState, event) match
    case (_, SubBrandEvent.Created(company, holding, name, t, brand, _, at)) =>
      effects.updateRow(SubBrandRow(updateContext.subject, company, holding, name, t, OrgStatus.Active, brand, at))
    case (Some(r), SubBrandEvent.StatusChanged(status, _, _)) => effects.updateRow(r.copy(status = status))
    case _                                                    => effects.ignore()

object SubBrandsView extends View.Companion[SubBrandsView, SubBrandEvent, SubBrandRow](
      componentId = ComponentId("sub-brands"),
      source = ChangeSource.eventsOf(SubBrand),
      rowSerializer = MerchantCodecs.serializer[SubBrandRow]("sub-brand-row")
    ):
  def create(ctx: ViewComponentContext) = new SubBrandsView
