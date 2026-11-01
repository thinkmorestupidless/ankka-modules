package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*

/** One row per membership, keyed `<affiliate>/<brand>` (data-model.md *Views*). */
final class MembershipsView extends View[Membership.Event, MembershipState]:
  def onChange(event: Membership.Event): Effect =
    val row  = rowState.getOrElse(MembershipState())
    val next = Membership.next(row, event)
    if rowState.isDefined && next == row then effects.ignore() else effects.updateRow(next)

object Memberships extends View.Companion[MembershipsView, Membership.Event, MembershipState](
      componentId = ComponentId("memberships"),
      source = ChangeSource.eventsOf(Membership),
      rowSerializer = MembershipCodecs.state
    ):
  val byBrandAndStatus = query("by-brand-and-status")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'status' = :status ORDER BY row_key"
  )
  val byBrand     = query("by-brand")(s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand ORDER BY row_key")
  val byAffiliate = query("by-affiliate")(s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new MembershipsView
