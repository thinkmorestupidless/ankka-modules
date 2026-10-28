package io.eitheror.casino.attribution.inputs

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.MoneyEvent
import io.eitheror.casino.attribution.record.AttributionRecord
import io.eitheror.casino.contract.facts.*
import org.slf4j.LoggerFactory

/**
 * `payments.payments` (research R9): `payment.captured` is a deposit, which sets the first deposit whether
 * or not the player is bound; `payment.dispute.opened` a chargeback under the payment id and
 * `payment.refunded` one under `<paymentId>/refund`; every other family, `payment.dispute.closed` among
 * them, is nothing. The dedupe id is `<family>/<paymentId>`: ankka 0.13 does not hand a consumer the
 * message's `ce-id` (T057).
 */
final class PaymentsFacts(context: ConsumerContext) extends Consumer[Option[PaymentsPaymentsFact], Unit]:
  def onMessage(fact: Option[PaymentsPaymentsFact]): Effect = fact match
    case Some(f: PaymentCaptured) =>
      record(f.playerId, MoneyEvent(MoneyEvent.Deposit, f.currency.code, f.amountCents.value, f.paymentId, f.occurredAt,
        s"payment.captured/${f.paymentId}"))
    case Some(f: PaymentDisputeOpened) =>
      record(f.playerId, MoneyEvent(MoneyEvent.Chargeback, f.currency.code, f.amountCents.value, f.paymentId, f.occurredAt,
        s"payment.dispute.opened/${f.paymentId}"))
    case Some(f: PaymentRefunded) =>
      record(f.playerId, MoneyEvent(MoneyEvent.Chargeback, f.currency.code, f.amountCents.value, s"${f.paymentId}/refund",
        f.occurredAt, s"payment.refunded/${f.paymentId}"))
    case Some(_) => effects.ignore()
    case None =>
      PaymentsFacts.log.warn("a payments.payments fact this service cannot read was skipped (subject {})", messageContext.subject)
      effects.ignore()

  private def record(player: String, event: MoneyEvent): Effect =
    context.componentClient.forEventSourcedEntity(EntityId(player)).call(AttributionRecord.money).invoke(event): Unit
    effects.done()

object PaymentsFacts extends Consumer.Companion[PaymentsFacts, Option[PaymentsPaymentsFact], Unit](
      componentId = ComponentId("payments-facts"),
      source = ChangeSource.fromTopic(PaymentsPayments.name, Lenient.serializer(PaymentsPayments.serializer), StartFrom.Earliest,
        TopicOptions(Some(PaymentsPayments.contract), None, false))
    ):
  private val log = LoggerFactory.getLogger(classOf[PaymentsFacts])

  def create(ctx: ConsumerContext) = new PaymentsFacts(ctx)
