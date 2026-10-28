package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs
import io.eitheror.casino.attribution.money.Tally.*

import java.time.Instant

/**
 * One tally (data-model.md *Views*): open until `Closed`, then the day's totals for the publisher's
 * `before-binding` facts. One row per tally id; a closed row stays, marked, for `closedFor`.
 */
final case class DayRow(
    player: String,
    currency: String,
    day: String,
    open: Boolean = true,
    stake: Long = 0,
    win: Long = 0,
    closedAt: Option[Instant] = None
)

object DayRow:
  given Serializer[DayRow] = AttributionCodecs.serializer[DayRow]("day-row")

final class OpenDaysView extends View[Tally.Event, DayRow]:
  def onChange(event: Tally.Event): Effect = event match
    case c: Closed =>
      effects.updateRow(DayRow(c.player, c.currency, c.day, open = false, c.stake, c.win, Some(c.at)))
    case e if rowState.isEmpty => effects.updateRow(DayRow(e.player, e.currency, e.day))
    case _                     => effects.ignore()

object OpenDays extends View.Companion[OpenDaysView, Tally.Event, DayRow](
      componentId = ComponentId("open-days"),
      source = ChangeSource.eventsOf(DayTally),
      rowSerializer = DayRow.given_Serializer_DayRow
    ):
  /** The open tallies of days before `day` (an ISO date, so text order is date order). */
  val openBefore = query("open-before")(
    s"SELECT payload FROM $table WHERE (payload::jsonb->>'open')::boolean IS NOT FALSE AND payload::jsonb->>'day' < :day ORDER BY payload::jsonb->>'day'")

  /** A player's closed days, in day order. */
  val closedFor = query("closed-for")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'player' = :player AND (payload::jsonb->>'open')::boolean = false ORDER BY payload::jsonb->>'day'")

  def create(ctx: ViewComponentContext) = new OpenDaysView
