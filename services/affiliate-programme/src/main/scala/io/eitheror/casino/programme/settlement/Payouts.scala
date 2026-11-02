package io.eitheror.casino.programme.settlement

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
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
  /** A brand's payouts, for the usage report's commission paid (FR-042). */
  val byBrand     = query("by-brand")(s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand ORDER BY row_key")
  val byAffiliate = query("by-affiliate")(s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate ORDER BY row_key")
  /** What finance exports: the approved payouts of a brand's period. */
  val approved = query("approved")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'period' = :period " +
      "AND payload::jsonb->>'status' = 'approved' ORDER BY row_key"
  )

  /** A payout's id on the wire, `<affiliate>:<currency>:<period>`: the brand is the route's, and a path segment holds no `/`. */
  def wireId(affiliate: String, currency: String, period: String): String = s"$affiliate:$currency:$period"

  /** The payout a wire id names at a brand. */
  def fromWire(brand: String, id: String): Option[EntityId] = id.split(':') match
    case Array(a, c, p) if a.nonEmpty && c.length == 3 && p.length == 7 => Some(Payout.entityId(a, brand, c, p))
    case _                                                            => None

  def create(ctx: ViewComponentContext) = new PayoutsView
