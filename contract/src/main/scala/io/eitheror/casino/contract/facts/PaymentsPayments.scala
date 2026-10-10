package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.time.Instant

/**
 * `payments.payments`, produced by the brand's `wallet` from what `merchant` hands it (spec 001
 * FR-011, FR-016). Keyed by the payment id. Every family carries the payment provider.
 */
sealed trait PaymentsPaymentsFact extends Fact:
  def paymentId: String
  def playerId: String
  def paymentProvider: String
  def occurredAt: Instant
  def key: String = paymentId

@named("payment.initiated")
final case class PaymentInitiated(paymentId: String, playerId: String, paymentProvider: String, terminal: String,
    amountCents: MinorUnits, currency: Currency, occurredAt: Instant) extends PaymentsPaymentsFact

@named("payment.captured")
final case class PaymentCaptured(paymentId: String, playerId: String, paymentProvider: String, amountCents: MinorUnits,
    currency: Currency, occurredAt: Instant) extends PaymentsPaymentsFact

@named("payment.failed")
final case class PaymentFailed(paymentId: String, playerId: String, paymentProvider: String, code: String, reason: String,
    occurredAt: Instant) extends PaymentsPaymentsFact

@named("payment.expired")
final case class PaymentExpired(paymentId: String, playerId: String, paymentProvider: String, occurredAt: Instant)
    extends PaymentsPaymentsFact

@named("payment.abandoned")
final case class PaymentAbandoned(paymentId: String, playerId: String, paymentProvider: String, occurredAt: Instant)
    extends PaymentsPaymentsFact

@named("payment.refunded")
final case class PaymentRefunded(paymentId: String, playerId: String, paymentProvider: String, amountCents: MinorUnits,
    currency: Currency, occurredAt: Instant) extends PaymentsPaymentsFact

@named("payment.dispute.opened")
final case class PaymentDisputeOpened(paymentId: String, playerId: String, paymentProvider: String, amountCents: MinorUnits,
    currency: Currency, reason: String, occurredAt: Instant) extends PaymentsPaymentsFact

@named("payment.dispute.closed")
final case class PaymentDisputeClosed(paymentId: String, playerId: String, paymentProvider: String, outcome: String,
    occurredAt: Instant) extends PaymentsPaymentsFact

object PaymentsPaymentsFact:
  given JsonValueCodec[PaymentsPaymentsFact] = FactCodecs.make

object PaymentsPayments extends Topic[PaymentsPaymentsFact]("payments.payments", Vector("wallet")):
  val families = Vector(
    FamilyDef.of[PaymentInitiated]("payment.initiated"),
    FamilyDef.of[PaymentCaptured]("payment.captured"),
    FamilyDef.of[PaymentFailed]("payment.failed"),
    FamilyDef.of[PaymentExpired]("payment.expired"),
    FamilyDef.of[PaymentAbandoned]("payment.abandoned"),
    FamilyDef.of[PaymentRefunded]("payment.refunded"),
    FamilyDef.of[PaymentDisputeOpened]("payment.dispute.opened"),
    FamilyDef.of[PaymentDisputeClosed]("payment.dispute.closed")
  )
