package io.eitheror.casino.provider.edge.views

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.provider.core.{Round, Session, SessionEntity, SessionEvent}

/** A round that was settled (won, lost or cancelled), as the day export lists it. */
final case class SettledRound(
    roundId: String,
    settledAt: Long,
    outcome: String,
    free: Boolean,
    stakes: Vector[String],
    wins: Vector[String],
    debits: Vector[String],
    credits: Vector[String],
    cancels: Vector[String]
)

/** A session and its settled rounds, flat, so a day's rounds are found by their settlement time. */
final case class DayRow(session: Session, settled: Vector[SettledRound])

/**
 * A brand's settled rounds by when they were settled (data-model, Views; research R14): one row per
 * session holding any, the rounds listed flat with their settlement time. The day is the
 * installation's reporting zone's, turned into a range of times by the export, so the view needs no
 * zone. Eventually consistent, and a finance read, never a game's.
 */
final class RoundsByDayView extends View[SessionEvent, DayRow]:

  def onChange(event: SessionEvent): Effect =
    event match
      case SessionEvent.Discarded => effects.deleteRow()
      case e =>
        val before = rowState.map(_.session).getOrElse(Session.empty(updateContext.subject))
        val after  = Session.applied(before, e)
        val settled = after.roundOrder.flatMap(after.rounds.get).flatMap(RoundsByDay.settled)
        // A session with nothing settled yet keeps its row: its next event folds onto it.
        if after.launched then effects.updateRow(DayRow(after, settled)) else effects.ignore()

object RoundsByDay
    extends View.Companion[RoundsByDayView, SessionEvent, DayRow](
      componentId = ComponentId("rounds-by-day"),
      source = ChangeSource.eventsOf(SessionEntity),
      rowSerializer = Codecs.serializer[DayRow]("rounds-by-day-row")
    ):
  def create(ctx: ViewComponentContext) = new RoundsByDayView

  def settled(r: Round): Option[SettledRound] =
    r.settledAt.filter(_ => Set(Round.Won, Round.Lost, Round.Cancelled).contains(r.state)).map { at =>
      SettledRound(r.roundId, at, r.state, r.free, r.stakes, r.wins, r.debits, r.credits, r.cancels)
    }

  /**
   * A brand's sessions with a round settled in `[from, until)`, in launch order after a cursor
   * (`launchedAt:launchToken`, as `SessionsByBrand.cursor` reads it).
   */
  val settledBetween = query("settled-between")(
    s"""SELECT payload FROM $table
       |WHERE payload::jsonb->'session'->>'brand' = :brand
       |  AND EXISTS (SELECT 1 FROM jsonb_array_elements(payload::jsonb->'settled') AS r
       |              WHERE (r->>'settledAt')::bigint >= (:from)::bigint AND (r->>'settledAt')::bigint < (:until)::bigint)
       |  AND ((payload::jsonb->'session'->>'launchedAt')::bigint, payload::jsonb->'session'->>'launchToken') > ((:after_at)::bigint, :after_id)
       |ORDER BY (payload::jsonb->'session'->>'launchedAt')::bigint, payload::jsonb->'session'->>'launchToken'""".stripMargin
  )
