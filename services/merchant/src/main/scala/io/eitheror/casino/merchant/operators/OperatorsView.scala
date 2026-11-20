package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewClient}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs

/** The operators the backoffice lists (FR-028), one row each, a removed one gone; the email stays on the entity. */
final case class OperatorRow(operatorId: String, role: String, scopeLevel: String, scopeId: String, ancestors: Vector[String],
    pending: Boolean, enabled: Boolean, createdAt: Long = 0L)

final class OperatorsViewRows extends View[OperatorEvent, OperatorRow]:
  def onChange(event: OperatorEvent): Effect = (rowState, event) match
    case (_, OperatorEvent.Created(_, role, level, id, anc, _, _, _, at)) =>
      effects.updateRow(OperatorRow(updateContext.subject, role, level, id, anc, pending = true, enabled = true, at.toEpochMilli))
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

  /** 2: rows carry `createdAt`, so the list pages in the view (spec 030 H5). */
  override def version: Option[Int] = Some(2)

  /** [[inScope]] as SQL. */
  def scoped(caller: Scope): SqlFragment =
    if caller.level == Level.Platform then SqlFragment.raw("TRUE")
    else
      SqlFragment.raw("(COALESCE(payload::jsonb->>'scopeId', '') = ") ++ sql"${caller.id}" ++
        SqlFragment.raw(" OR COALESCE(payload::jsonb->'ancestors', '[]'::jsonb) @> jsonb_build_array(CAST(") ++ sql"${caller.id}" ++
        SqlFragment.raw(" AS text)))")

  /** The caller's operators, newest first, from `offset`. */
  def page(views: ViewClient, caller: Scope, offset: Int, limit: Int): Vector[OperatorRow] =
    // the view client puts its LIMIT after the order, and Postgres takes OFFSET and LIMIT in either order
    views.forView(OperatorsView).ordered(scoped(caller),
      SqlFragment.raw(s"COALESCE((payload::jsonb->>'createdAt')::bigint, 0) DESC, row_key ASC OFFSET ${offset.max(0)}"), limit)

  def total(views: ViewClient, caller: Scope): Long = views.forView(OperatorsView).count(scoped(caller))

  /** An operator the caller's level covers: the platform every one; otherwise their own entity or one under it. */
  def inScope(caller: Scope, row: OperatorRow): Boolean =
    caller.level == Level.Platform || row.scopeId == caller.id || row.ancestors.contains(caller.id)
