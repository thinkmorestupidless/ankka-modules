package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Dispute, Generation, Payment, PaymentEvent, PaymentState, Payments}

import java.time.Instant

/** A payment as the day-end and the dispute report find it (data-model.md `PaymentsView`): the state but `seen`. */
final case class PaymentRow(
    generation: Int,
    paymentId: String = "",
    player: String = "",
    provider: String = "",
    terminal: String = "",
    currency: String = "",
    amount: Long = 0,
    initiatedAt: Option[Instant] = None,
    outcome: Option[String] = None,
    endedAt: Option[Instant] = None,
    refunded: Long = 0,
    dispute: Option[Dispute] = None
):
  def state: PaymentState =
    PaymentState(paymentId, player, provider, terminal, currency, amount, initiatedAt, outcome, endedAt, refunded, dispute)

object PaymentRow:
  given Serializer[PaymentRow] = ReportingCodecs.row[PaymentRow]("payment-row")

  def of(generation: Int, before: PaymentRow, e: PaymentEvent): PaymentRow =
    val s = Payments.apply(before.state, e)
    PaymentRow(generation, s.paymentId, s.player, s.provider, s.terminal, s.currency, s.amount, s.initiatedAt, s.outcome, s.endedAt,
      s.refunded, s.dispute)

final class PaymentRows extends View[PaymentEvent, PaymentRow]:
  def onChange(event: PaymentEvent): Effect =
    Generation.of(updateContext.subject).fold(effects.ignore()) { g =>
      effects.updateRow(PaymentRow.of(g, rowState.getOrElse(PaymentRow(g)), event))
    }

object PaymentsView extends View.Companion[PaymentRows, PaymentEvent, PaymentRow](
      componentId = ComponentId("payments"),
      source = ChangeSource.eventsOf(Payment),
      rowSerializer = PaymentRow.given_Serializer_PaymentRow
    ):
  /** The payments initiated from `from` (inclusive) to `to` (exclusive), both instants. */
  val initiatedBetween = query("initiated-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'initiatedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'initiatedAt')::timestamptz < (:to)::timestamptz ORDER BY payload::jsonb->>'initiatedAt', row_key")

  /** The payments whose dispute closed from `from` (inclusive) to `to` (exclusive): the day-end's chargebacks (FR-018). */
  val disputesClosedBetween = query("disputes-closed-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->'dispute'->>'closedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->'dispute'->>'closedAt')::timestamptz < (:to)::timestamptz ORDER BY payload::jsonb->'dispute'->>'closedAt', row_key")

  def create(ctx: ViewComponentContext) = new PaymentRows
