package io.eitheror.casino.wallet.payments

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.DisputeOutcome

import java.time.Instant

/**
 * One payment's outcomes as `merchant` reports them on the granted outcome route (FR-022a), keyed by
 * the payment id (spec 001 FR-012). An outcome moves no money; the captured deposit is the wallet's
 * own `DepositConfirmed`, from which `payment.captured` is published, so a deposit and its fact are
 * one write (research R8). Each (payment, outcome) is recorded once: a repeat answers the first.
 */
final case class PaymentOutcome(
    outcome: String,
    playerId: String,
    paymentProvider: String,
    terminal: Option[String] = None,
    amount: Option[Long] = None,
    currency: Option[String] = None,
    code: Option[String] = None,
    reason: Option[String] = None,
    disputeOutcome: Option[String] = None,
    at: Instant
)

object PaymentOutcome:
  /** The outcomes `merchant` reports; `captured` is not among them (research R8). */
  val Kinds: Set[String] = Set("initiated", "failed", "expired", "abandoned", "refunded", "dispute_opened", "dispute_closed")
  val DisputeOpened = "dispute_opened"
  val DisputeClosed = "dispute_closed"

final case class Payment(paymentId: String, outcomes: Vector[PaymentOutcome] = Vector.empty)

enum PaymentEvent:
  case PaymentOutcomeRecorded(paymentId: String, recorded: PaymentOutcome)

final case class Recorded(recorded: Boolean, first: PaymentOutcome)

final class PaymentEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[Payment, PaymentEvent]:

  def emptyState: Payment = Payment(context.entityId)

  def applyEvent(event: PaymentEvent): Payment = event match
    case PaymentEvent.PaymentOutcomeRecorded(_, recorded) => currentState.copy(outcomes = currentState.outcomes :+ recorded)

  def record(o: PaymentOutcome): Effect[Recorded] =
    if !PaymentOutcome.Kinds.contains(o.outcome) then
      effects.error(s"'${o.outcome}' is not an outcome merchant reports here; one of ${PaymentOutcome.Kinds.toVector.sorted.mkString(", ")}")
    else if o.playerId.trim.isEmpty || o.paymentProvider.trim.isEmpty then
      effects.error("an outcome names the player and the payment provider (FR-022a)")
    else if o.outcome == PaymentOutcome.DisputeClosed && !o.disputeOutcome.exists(DisputeOutcome.values) then
      effects.error(s"a closed dispute's outcome is one of ${DisputeOutcome.values.toVector.sorted.mkString(", ")} (spec 015)")
    else
      currentState.outcomes.find(_.outcome == o.outcome) match
        case Some(first) => effects.reply(Recorded(recorded = false, first))
        case None =>
          val recorded = withDisputeAmount(o)
          effects.persist(PaymentEvent.PaymentOutcomeRecorded(context.entityId, recorded)).thenReply(_ => Recorded(recorded = true, recorded))

  /**
   * A closed dispute carries the dispute's amount and currency (spec 015 R11): those the close states, and
   * where it states none, those of the dispute as opened on this payment. A close with neither carries none.
   */
  private def withDisputeAmount(o: PaymentOutcome): PaymentOutcome =
    if o.outcome != PaymentOutcome.DisputeClosed then o
    else
      val opened = currentState.outcomes.find(_.outcome == PaymentOutcome.DisputeOpened)
      o.copy(amount = o.amount.orElse(opened.flatMap(_.amount)), currency = o.currency.orElse(opened.flatMap(_.currency)))

  def get: ReadOnlyEffect[Payment] = effects.reply(currentState)

object PaymentEntity
    extends EventSourcedEntity.Companion[PaymentEntity, Payment, PaymentEvent](
      componentId = ComponentId("payment"),
      stateSerializer = Codecs.serializer[Payment]("wallet.payment"),
      eventSerializer = Codecs.serializer[PaymentEvent]("wallet.payment-event")
    ):
  given Serializer[PaymentOutcome] = Codecs.serializer[PaymentOutcome]("wallet.payment-outcome")
  given Serializer[Recorded]       = Codecs.serializer[Recorded]("wallet.payment-recorded")

  def create(context: EventSourcedEntityContext) = new PaymentEntity(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)
