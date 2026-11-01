package io.eitheror.casino.programme.settlement

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*

/** One row per payout (data-model.md *Views*). */
final class PayoutsView extends View[Payout.Event, PayoutState]:
  def onChange(event: Payout.Event): Effect =
    val row  = rowState.getOrElse(PayoutState())
    val next = Payout.next(row, event)
    if rowState.isDefined && next == row then effects.ignore() else effects.updateRow(next)

object Payouts extends View.Companion[PayoutsView, Payout.Event, PayoutState](
      componentId = ComponentId("payouts"),
      source = ChangeSource.eventsOf(Payout),
      rowSerializer = PayoutCodecs.state
    ):
  val byBrandAndPeriod = query("by-brand-and-period")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'period' = :period ORDER BY row_key"
  )
  val byAffiliate = query("by-affiliate")(s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate ORDER BY row_key")
  /** What finance exports: the approved payouts of a brand's period. */
  val approved = query("approved")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'period' = :period " +
      "AND payload::jsonb->>'status' = 'approved' ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new PayoutsView
