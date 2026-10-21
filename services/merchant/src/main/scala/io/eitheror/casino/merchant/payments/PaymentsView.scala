package io.eitheror.casino.merchant.payments

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs

import java.time.Instant

/**
 * One row per payment (research R2), written from its events, so every question over payments is one
 * query: the backoffice list and summary (FR-029), a customer's captured deposits for routing
 * (FR-012), the captured payments not yet credited (FR-023) and a customer's payments for erasure
 * (FR-032). A count is exactly once by construction: a row is a payment.
 */
final case class PaymentRow(
    paymentId: String,
    subBrandId: String,
    brand: Option[String],
    customerId: String,
    playerId: String,
    amount: Long,
    originalAmount: Option[Long],
    currency: String,
    status: String,
    psp: String,
    scheme: String,
    terminalId: String,
    transactionId: String,
    pspTransactionId: String,
    createdAt: Instant,
    capturedAt: Option[Instant] = None,
    failureCode: Option[String] = None,
    failureReason: Option[String] = None,
    variance: Option[String] = None,
    credited: Boolean = false,
    creditStatus: Option[String] = None,
    attempts: Int = 0,
    lastAnswer: Option[String] = None,
    /** Set while a captured payment is not credited: what `/status` counts past the bound (FR-023). */
    uncredited: Boolean = false
)

final class PaymentsViewRows extends View[PaymentEvent, PaymentRow]:
  def onChange(event: PaymentEvent): Effect = PaymentsView.next(rowState, updateContext.subject, event).fold(effects.ignore())(effects.updateRow)

object PaymentsView extends View.Companion[PaymentsViewRows, PaymentEvent, PaymentRow](
      componentId = ComponentId("payments"),
      source = ChangeSource.eventsOf(Payment),
      rowSerializer = MerchantCodecs.serializer[PaymentRow]("payment-row")
    ):
  def create(ctx: ViewComponentContext) = new PaymentsViewRows

  /** A customer's captured deposits at a sub-brand, what a routing context counts (FR-012). */
  def depositCount(views: ViewClient, subBrandId: String, customerId: String): Long =
    views.forView(PaymentsView).count(jsonText("subBrandId") ++ sql" = $subBrandId" ++ sql" AND " ++ jsonText("customerId") ++
      sql" = $customerId" ++ sql" AND " ++ jsonText("status") ++ sql" = ${Status.Captured}")

  def next(row: Option[PaymentRow], id: String, event: PaymentEvent): Option[PaymentRow] = (row, event) match
    case (_, PaymentEvent.Initiated(o, status)) =>
      Some(PaymentRow(id, o.subBrandId, o.brand, o.customerId, o.playerId, o.amount, None, o.currency, status, o.psp, o.scheme, o.terminalId,
        o.transactionId, o.pspTransactionId, o.createdAt))
    case (Some(r), PaymentEvent.Captured(variance, credited, at)) =>
      val under    = variance.exists(_.kind == Variance.Underpayment)
      val original = if under && r.originalAmount.isEmpty then Some(r.amount) else r.originalAmount
      Some(r.copy(status = Status.Captured, capturedAt = Some(at), variance = variance.map(_.kind), amount = if under then credited else r.amount,
        originalAmount = original, uncredited = r.brand.isDefined && !r.credited, creditStatus = r.creditStatus.orElse(Some(CreditState.Pending))))
    case (Some(r), PaymentEvent.CreditAttempted(answer, _)) => Some(r.copy(attempts = r.attempts + 1, lastAnswer = Some(answer)))
    case (Some(r), PaymentEvent.Credited(_, _)) =>
      Some(r.copy(credited = true, uncredited = false, creditStatus = Some(CreditState.Credited), attempts = r.attempts + 1, lastAnswer = Some("credited")))
    case (Some(r), PaymentEvent.CreditRefused(code, message, _, _)) =>
      Some(r.copy(creditStatus = Some(CreditState.Refused), attempts = r.attempts + 1, lastAnswer = Some(s"$code: $message")))
    case (Some(r), PaymentEvent.CreditRetried(_, _)) => Some(r.copy(creditStatus = Some(CreditState.Pending)))
    case (Some(r), PaymentEvent.Failed(code, reason, _, _)) => Some(r.copy(status = Status.Failed, failureCode = code, failureReason = Some(reason)))
    case (Some(r), PaymentEvent.Expired(_)) => Some(r.copy(status = Status.Expired, failureReason = Some(FailureReason.Abandoned)))
    case (other, _) => other
