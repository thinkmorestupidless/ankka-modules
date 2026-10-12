package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.simulator.core.CallbackEvent.*

/** One callback of a run, as the run view lists it (FR-020). */
final case class RunCallbackRow(
    callbackId: String,
    runId: String,
    sessionId: String,
    brand: String,
    kind: String,
    route: String,
    body: String,
    step: Option[Int],
    firstSentAt: Long,
    lastAnsweredAt: Long,
    attempts: Int,
    outcome: String,
    status: Option[String],
    balance: Option[String],
    attemptsDetail: Vector[Attempt]
)

/**
 * The run, readable (FR-020): a row per callback, built from its events. Queried by run in the
 * order the callbacks were scheduled with keyset paging (the view has a row limit and no offset),
 * by age for the sweep, and by answer word for a load report. Eventually consistent: a verdict is
 * read from the session, never from here (research R5).
 */
final class RunCallbacksView extends View[CallbackEvent, RunCallbackRow]:

  def onChange(event: CallbackEvent): Effect =
    event match
      case Scheduled(run, session, brand, kind, route, body, step, at) =>
        effects.updateRow(RunCallbackRow(updateContext.subject, run, session, brand, kind, route, body, step, at, at, 0, Callback.Pending, None, None, Vector.empty))
      case AttemptRecorded(a) =>
        rowState.fold(effects.ignore()) { row =>
          val detail = row.attemptsDetail.filterNot(_.attempt == a.attempt) :+ a
          effects.updateRow(row.copy(
            attempts = detail.size,
            attemptsDetail = detail.sortBy(_.attempt),
            lastAnsweredAt = math.max(row.lastAnsweredAt, a.sentAt + a.tookMillis),
            status = a.status.orElse(row.status),
            balance = a.balance.orElse(row.balance)
          ))
        }
      case Ended(outcome) => rowState.fold(effects.ignore())(row => effects.updateRow(row.copy(outcome = outcome)))
      case Discarded      => effects.deleteRow()

object RunCallbacks
    extends View.Companion[RunCallbacksView, CallbackEvent, RunCallbackRow](
      componentId = ComponentId("run-callbacks"),
      source = ChangeSource.eventsOf(CallbackEntity),
      rowSerializer = Codecs.serializer[RunCallbackRow]("run-callback-row")
    ):
  def create(ctx: ViewComponentContext) = new RunCallbacksView

  /** A run's callbacks in the order scheduled, after a cursor: `after` is the last row's `firstSentAt:callbackId`. */
  val byRun = query("by-run")(
    s"""SELECT payload FROM $table
       |WHERE payload::jsonb->>'runId' = :run
       |  AND ((payload::jsonb->>'firstSentAt')::bigint, payload::jsonb->>'callbackId') > ((:after_at)::bigint, :after_id)
       |ORDER BY (payload::jsonb->>'firstSentAt')::bigint, payload::jsonb->>'callbackId'""".stripMargin
  )

  /** Runs whose last answer is older than a cutoff, for the sweep (FR-022). */
  val endedBefore = query("ended-before")(
    s"""SELECT payload FROM $table
       |WHERE (payload::jsonb->>'lastAnsweredAt')::bigint < (:cutoff)::bigint
       |ORDER BY (payload::jsonb->>'lastAnsweredAt')::bigint""".stripMargin
  )
