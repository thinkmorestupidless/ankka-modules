package io.eitheror.casino.merchant.terminals

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs

/** A sub-brand's terminals, one row each, a deleted one removed (FR-007, FR-009). */
final case class TerminalRow(id: String, subBrandId: String, gateway: String, scheme: String, name: String, status: String,
    supportsDeposit: Boolean, supportsWithdrawal: Boolean, requiresPayoutDestination: Boolean)

final class TerminalsBySubBrandView extends View[TerminalEvent, TerminalRow]:
  def onChange(event: TerminalEvent): Effect = (rowState, event) match
    case (_, TerminalEvent.Created(sb, gw, scheme, name, _, _, _, cap, _, _)) =>
      effects.updateRow(TerminalRow(updateContext.subject, sb, gw, scheme, name, TerminalState.Inactive, cap.supportsDeposit,
        cap.supportsWithdrawal, cap.requiresPayoutDestination))
    case (Some(r), TerminalEvent.Activated(_, _)) => effects.updateRow(r.copy(status = TerminalState.Active))
    case (Some(r), TerminalEvent.Disabled(_, _))  => effects.updateRow(r.copy(status = TerminalState.Inactive))
    case (Some(r), TerminalEvent.CapabilityChanged(cap, _, _)) =>
      effects.updateRow(r.copy(supportsDeposit = cap.supportsDeposit, supportsWithdrawal = cap.supportsWithdrawal,
        requiresPayoutDestination = cap.requiresPayoutDestination))
    case (Some(_), TerminalEvent.Deleted(_, _)) => effects.deleteRow()
    case _                                      => effects.ignore()

object TerminalsBySubBrand extends View.Companion[TerminalsBySubBrandView, TerminalEvent, TerminalRow](
      componentId = ComponentId("terminals-by-sub-brand"),
      source = ChangeSource.eventsOf(Terminal),
      rowSerializer = MerchantCodecs.serializer[TerminalRow]("terminal-row")
    ):
  def create(ctx: ViewComponentContext) = new TerminalsBySubBrandView
