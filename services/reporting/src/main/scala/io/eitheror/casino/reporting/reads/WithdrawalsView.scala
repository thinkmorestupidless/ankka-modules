package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Generation, Withdrawal, WithdrawalEvent, WithdrawalState, Withdrawals}

import java.time.Instant

/**
 * A withdrawal request as the day-end finds it (data-model.md `WithdrawalsView`): the state but `seen`, and whether it
 * is still pending, which the pending holds at close are (research R7).
 */
final case class WithdrawalRow(
    generation: Int,
    payoutId: String = "",
    player: String = "",
    gateway: String = "",
    terminal: String = "",
    amount: Long = 0,
    currency: String = "",
    requestedAt: Option[Instant] = None,
    approvedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    outcome: Option[String] = None,
    pending: Boolean = false
):
  def state: WithdrawalState =
    WithdrawalState(payoutId, player, gateway, terminal, amount, currency, requestedAt, approvedAt, endedAt, outcome)

object WithdrawalRow:
  given Serializer[WithdrawalRow] = ReportingCodecs.row[WithdrawalRow]("withdrawal-row")

  def of(generation: Int, before: WithdrawalRow, e: WithdrawalEvent): WithdrawalRow =
    val s = Withdrawals.apply(before.state, e)
    WithdrawalRow(generation, s.payoutId, s.player, s.gateway, s.terminal, s.amount, s.currency, s.requestedAt, s.approvedAt, s.endedAt,
      s.outcome, s.pending)

final class WithdrawalRows extends View[WithdrawalEvent, WithdrawalRow]:
  def onChange(event: WithdrawalEvent): Effect =
    Generation.of(updateContext.subject).fold(effects.ignore()) { g =>
      effects.updateRow(WithdrawalRow.of(g, rowState.getOrElse(WithdrawalRow(g)), event))
    }

object WithdrawalsView extends View.Companion[WithdrawalRows, WithdrawalEvent, WithdrawalRow](
      componentId = ComponentId("withdrawals"),
      source = ChangeSource.eventsOf(Withdrawal),
      rowSerializer = WithdrawalRow.given_Serializer_WithdrawalRow
    ):
  /** The requests made from `from` (inclusive) to `to` (exclusive), both instants. */
  val requestedBetween = query("requested-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'requestedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'requestedAt')::timestamptz < (:to)::timestamptz ORDER BY payload::jsonb->>'requestedAt', row_key")

  /** The requests completed from `from` (inclusive) to `to` (exclusive). */
  val completedBetween = query("completed-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'outcome' = 'completed' AND (payload::jsonb->>'endedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'endedAt')::timestamptz < (:to)::timestamptz ORDER BY payload::jsonb->>'endedAt', row_key")

  /** The requests made before `at` and not ended by it: the pending holds at a close (research R7). */
  val pendingAt = query("pending-at")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'requestedAt')::timestamptz < (:at)::timestamptz AND (payload::jsonb->>'endedAt' IS NULL OR (payload::jsonb->>'endedAt')::timestamptz >= (:at)::timestamptz) ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new WithdrawalRows

  def pending(views: com.thinkmorestupidless.ankka.runtime.ViewClient, generation: Int, at: Instant): Vector[WithdrawalRow] =
    Paging.bounded(views.forView(WithdrawalsView), pendingAt, "generation" -> generation.toString, "at" -> at.toString)
