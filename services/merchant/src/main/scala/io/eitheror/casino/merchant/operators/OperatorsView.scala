package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs

/** The operators the backoffice lists (FR-028), one row each, a removed one gone; the email stays on the entity. */
final case class OperatorRow(operatorId: String, role: String, scopeLevel: String, scopeId: String, ancestors: Vector[String],
    pending: Boolean, enabled: Boolean)

final class OperatorsViewRows extends View[OperatorEvent, OperatorRow]:
  def onChange(event: OperatorEvent): Effect = (rowState, event) match
    case (_, OperatorEvent.Created(_, role, level, id, anc, _, _, _, _)) =>
      effects.updateRow(OperatorRow(updateContext.subject, role, level, id, anc, pending = true, enabled = true))
    case (Some(r), OperatorEvent.Accepted(_))                      => effects.updateRow(r.copy(pending = false))
    case (Some(r), OperatorEvent.ScopeChanged(level, id, anc, _, _)) => effects.updateRow(r.copy(scopeLevel = level, scopeId = id, ancestors = anc))
    case (Some(r), OperatorEvent.EnabledMirrored(enabled, _))      => effects.updateRow(r.copy(enabled = enabled))
    case (Some(_), OperatorEvent.Removed(_, _))                    => effects.deleteRow()
    case _                                                         => effects.ignore()

object OperatorsView extends View.Companion[OperatorsViewRows, OperatorEvent, OperatorRow](
      componentId = ComponentId("operators"),
      source = ChangeSource.eventsOf(Operator),
      rowSerializer = MerchantCodecs.serializer[OperatorRow]("operator-row")
    ):
  def create(ctx: ViewComponentContext) = new OperatorsViewRows

  /** An operator the caller's level covers: the platform every one; otherwise their own entity or one under it. */
  def inScope(caller: Scope, row: OperatorRow): Boolean =
    caller.level == Level.Platform || row.scopeId == caller.id || row.ancestors.contains(caller.id)
