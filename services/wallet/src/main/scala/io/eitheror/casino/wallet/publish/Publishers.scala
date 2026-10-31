package io.eitheror.casino.wallet.publish

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{MoneyTransactions, MoneyTransactionsMessage, PaymentsPayments, PaymentsPaymentsFact, PlatformAudit, PlatformAuditFact}
import io.eitheror.casino.wallet.entity.{WalletEntity, WalletEvent}
import io.eitheror.casino.wallet.payments.{PaymentEntity, PaymentEvent}

/**
 * The wallet's three topics (FR-031, FR-022a), each published by a consumer in the contract's
 * shape, never the journal's (constitution IV). Delivery is at least once: every fact carries the
 * id a reader deduplicates on, also set as its `ce-id`, so a redelivered event is the same fact.
 */
private[publish] object Publish:
  def withId(id: String): Metadata = Metadata.empty.set("ce-id", id)

/** `money.transactions`: one fact per movement, keyed by the wallet (spec 001 FR-012). */
final class MoneyTransactionsPublisher extends Consumer[WalletEvent, MoneyTransactionsMessage]:
  def onMessage(event: WalletEvent): Effect =
    Facts.moneyTransactions(messageContext.subject, event) match
      case facts if facts.isEmpty => effects.ignore()
      case facts                  => effects.produceAll(facts.map(p => effects.message(p.fact).withMetadata(Publish.withId(p.dedupeId))))

object MoneyTransactionsPublisher
    extends Consumer.Companion[MoneyTransactionsPublisher, WalletEvent, MoneyTransactionsMessage](
      componentId = ComponentId("money-transactions-publisher"),
      source = ChangeSource.eventsOf(WalletEntity)
    ):
  def create(ctx: ConsumerContext) = new MoneyTransactionsPublisher
  override val outputSerializer: Option[Serializer[MoneyTransactionsMessage]] = Some(MoneyTransactions.serializer)
  override val produceTo: Option[String]                                   = Some(MoneyTransactions.name)
  // The topic's contract, which the project declares: ankka refuses a publisher that names none.
  override val produces: Option[Publication] = Some(Publication(MoneyTransactions.name, Some(MoneyTransactions.contract), None))

/** `payment.captured` on `payments.payments`, from the wallet's deposit: keyed by the payment id. */
final class PaymentCapturedPublisher extends Consumer[WalletEvent, PaymentsPaymentsFact]:
  def onMessage(event: WalletEvent): Effect =
    Facts.paymentCaptured(messageContext.subject, event) match
      case Some(p) => effects.produceAll(Seq(effects.message(p.fact).withKey(p.fact.key).withMetadata(Publish.withId(p.dedupeId))))
      case None    => effects.ignore()

object PaymentCapturedPublisher
    extends Consumer.Companion[PaymentCapturedPublisher, WalletEvent, PaymentsPaymentsFact](
      componentId = ComponentId("payment-captured-publisher"),
      source = ChangeSource.eventsOf(WalletEntity)
    ):
  def create(ctx: ConsumerContext) = new PaymentCapturedPublisher
  override val outputSerializer: Option[Serializer[PaymentsPaymentsFact]] = Some(PaymentsPayments.serializer)
  override val produceTo: Option[String]                                  = Some(PaymentsPayments.name)
  // The topic's contract, which the project declares: ankka refuses a publisher that names none.
  override val produces: Option[Publication] = Some(Publication(PaymentsPayments.name, Some(PaymentsPayments.contract), None))

/** The other `payments.payments` families, from `merchant`'s outcomes: keyed by the payment id. */
final class PaymentsPublisher extends Consumer[PaymentEvent, PaymentsPaymentsFact]:
  def onMessage(event: PaymentEvent): Effect =
    Facts.payments(event) match
      case Some(p) => effects.produceAll(Seq(effects.message(p.fact).withMetadata(Publish.withId(p.dedupeId))))
      case None    => effects.ignore()

object PaymentsPublisher
    extends Consumer.Companion[PaymentsPublisher, PaymentEvent, PaymentsPaymentsFact](
      componentId = ComponentId("payments-publisher"),
      source = ChangeSource.eventsOf(PaymentEntity)
    ):
  def create(ctx: ConsumerContext) = new PaymentsPublisher
  override val outputSerializer: Option[Serializer[PaymentsPaymentsFact]] = Some(PaymentsPayments.serializer)
  override val produceTo: Option[String]                                  = Some(PaymentsPayments.name)
  // The topic's contract, which the project declares: ankka refuses a publisher that names none.
  override val produces: Option[Publication] = Some(Publication(PaymentsPayments.name, Some(PaymentsPayments.contract), None))

/** `platform.audit`: a record of every money command and refusal, keyed by the player (FR-031). */
final class AuditPublisher(brand: String) extends Consumer[WalletEvent, PlatformAuditFact]:
  def onMessage(event: WalletEvent): Effect =
    Facts.audit(brand, messageContext.subject, event) match
      case Some(p) => effects.produceAll(Seq(effects.message(p.fact).withKey(p.fact.key).withMetadata(Publish.withId(p.dedupeId))))
      case None    => effects.ignore()

object AuditPublisher
    extends Consumer.Companion[AuditPublisher, WalletEvent, PlatformAuditFact](
      componentId = ComponentId("audit-publisher"),
      source = ChangeSource.eventsOf(WalletEntity)
    ):
  def create(ctx: ConsumerContext) = new AuditPublisher(sys.env.getOrElse("CASINO_BRAND", ""))
  override val outputSerializer: Option[Serializer[PlatformAuditFact]] = Some(PlatformAudit.serializer)
  override val produceTo: Option[String]                               = Some(PlatformAudit.name)
  // The topic's contract, which the project declares: ankka refuses a publisher that names none.
  override val produces: Option[Publication] = Some(Publication(PlatformAudit.name, Some(PlatformAudit.contract), None))
