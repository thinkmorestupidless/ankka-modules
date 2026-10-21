package io.eitheror.casino.merchant.deposits

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.payments.*
import io.eitheror.casino.merchant.wallet.OutcomeReport

/**
 * Every outcome of a payment but the capture reaches the brand (FR-022, spec correction 3): the
 * brand's wallet's outcome route is called for `initiated`, `failed`, `expired` and `abandoned` (a
 * failure coded `player_abandoned`), keyed by the payment and the outcome, carrying the provider. A
 * call that is not acknowledged throws, so the event is delivered again; the wallet answers a repeat
 * as recorded. A payment of a sub-brand that names no brand reports nothing.
 */
final class OutcomeReporter(context: ConsumerContext) extends Consumer[PaymentEvent, Unit]:

  def onMessage(event: PaymentEvent): Effect =
    val id = messageContext.subject
    def read = context.componentClient.forEventSourcedEntity(EntityId(id)).call(Payment.get).invoke()
    event match
      case PaymentEvent.Initiated(o, _) =>
        report(OutcomeReporter.of(id, o, "initiated", None, None).map(_.copy(amount = Some(o.amount), currency = Some(o.currency))))
      case PaymentEvent.Failed(code, reason, _, _) =>
        val kind = if code.contains(FailureReason.AbandonedCode) then "abandoned" else "failed"
        report(read.opened.flatMap(o => OutcomeReporter.of(id, o, kind, code, Some(reason))))
      case PaymentEvent.Expired(_) =>
        report(read.opened.flatMap(o => OutcomeReporter.of(id, o, "expired", None, Some(FailureReason.Abandoned))))
      case _ => effects.ignore()

  private def report(r: Option[OutcomeReport]): Effect =
    r match
      case None => effects.ignore()
      case Some(report) =>
        DepositRuntime.wallet(context.services).report(report) match
          case Right(_)  => effects.done()
          case Left(why) => throw IllegalStateException(s"outcome ${report.outcome} of ${report.paymentId} not acknowledged: $why")

object OutcomeReporter extends Consumer.Companion[OutcomeReporter, PaymentEvent, Unit](
      componentId = ComponentId("outcome-reporter"),
      source = ChangeSource.eventsOf(Payment)
    ):
  def create(ctx: ConsumerContext) = new OutcomeReporter(ctx)

  def of(id: String, o: Opened, outcome: String, code: Option[String], reason: Option[String]): Option[OutcomeReport] =
    o.brand.map(brand => OutcomeReport(brand, id, outcome, o.playerId, o.psp, Some(o.terminalId), None, None, code, reason))
