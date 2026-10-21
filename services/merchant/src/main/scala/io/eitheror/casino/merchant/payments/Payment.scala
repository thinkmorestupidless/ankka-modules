package io.eitheror.casino.merchant.payments

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs

import java.time.Instant

/** A crypto deposit's destination, as the provider returned it. */
final case class CryptoDetails(paymentAddress: String, cryptoAmount: String, cryptoCurrency: String, validUntil: Option[String])

/** What a provider reported arrived, on a failure or a capture. */
final case class Received(receivedCryptoAmount: Option[String] = None, receivedConvertAmount: Option[String] = None,
    receivedConvertCurrency: Option[String] = None)

/** The credit to the brand's wallet (FR-021, FR-023): its state, the attempts and the wallet's last answer. */
final case class CreditState(status: String, attempts: Int, lastAnswer: Option[String], code: Option[String] = None,
    details: Map[String, String] = Map.empty, txnId: Option[String] = None, at: Option[Instant] = None)

object CreditState:
  val Pending  = "pending"
  val Credited = "credited"
  val Refused  = "refused"

/** What the initiate recorded: the payment's facts, none personal (research R12). */
final case class Opened(
    subBrandId: String,
    brand: Option[String],
    customerId: String,
    playerId: String,
    amount: Long,
    currency: String,
    psp: String,
    scheme: String,
    terminalId: String,
    transactionId: String,
    pspTransactionId: String,
    redirectUrl: String,
    crypto: Option[CryptoDetails],
    createdAt: Instant,
    deadline: Instant
)

/**
 * A payment (FR-016), event sourced under its payment id: what the deposit workflow decided, step by
 * step, and nothing personal. Its statuses are exactly the original's; `captured`, `failed` and
 * `expired` are terminal, and a command arriving after one of them records nothing (FR-019).
 */
final case class PaymentState(
    paymentId: String,
    opened: Option[Opened] = None,
    status: String = "",
    originalAmount: Option[Long] = None,
    creditedAmount: Option[Long] = None,
    capturedAt: Option[Instant] = None,
    failedAt: Option[Instant] = None,
    expiredAt: Option[Instant] = None,
    failureCode: Option[String] = None,
    failureReason: Option[String] = None,
    variance: Option[Variance] = None,
    received: Option[Received] = None,
    waitingReason: Option[String] = None,
    credit: Option[CreditState] = None
):
  def exists: Boolean   = opened.isDefined
  def terminal: Boolean = Status.isTerminal(status)

  /** The amount as the frontend reads it: an underpayment's credited value, else the invoice (FR-026). */
  def amount: Long = creditedAmount.filter(_ => variance.exists(_.kind == Variance.Underpayment)).orElse(opened.map(_.amount)).getOrElse(0L)

  /** Past its deadline with no outcome: read as expired before any timer fires (FR-017). */
  def lazilyExpired(at: Instant): Boolean = !terminal && opened.exists(o => !at.isBefore(o.deadline))

enum PaymentEvent:
  case Initiated(opened: Opened, status: String)
  case Captured(variance: Option[Variance], creditedAmount: Long, at: Instant)
  case CreditAttempted(answer: String, at: Instant)
  case Credited(txnId: Option[String], at: Instant)
  case CreditRefused(code: String, message: String, details: Map[String, String], at: Instant)
  case CreditRetried(by: String, at: Instant)
  case Failed(code: Option[String], reason: String, received: Option[Received], at: Instant)
  case Expired(at: Instant)
  case Waiting(reason: String, at: Instant)

final case class RecordCapture(variance: Option[Variance], creditedAmount: Long, at: Instant)
final case class RecordAttempt(answer: String, at: Instant)
final case class RecordCredited(txnId: Option[String], at: Instant)
final case class RecordCreditRefused(code: String, message: String, details: Map[String, String], at: Instant)
final case class RecordRetry(by: String, at: Instant)
final case class RecordFailure(code: Option[String], reason: String, received: Option[Received], at: Instant)
final case class At(at: Instant)
final case class RecordWaiting(reason: String, at: Instant)
final case class Open(opened: Opened, status: String)

object PaymentCodecs:
  given Serializer[PaymentState]        = MerchantCodecs.serializer[PaymentState]("payment-state")
  given Serializer[PaymentEvent]        = MerchantCodecs.serializer[PaymentEvent]("payment-event")
  given Serializer[Open]                = MerchantCodecs.serializer[Open]("open-payment")
  given Serializer[RecordCapture]       = MerchantCodecs.serializer[RecordCapture]("record-capture")
  given Serializer[RecordAttempt]       = MerchantCodecs.serializer[RecordAttempt]("record-attempt")
  given Serializer[RecordCredited]      = MerchantCodecs.serializer[RecordCredited]("record-credited")
  given Serializer[RecordCreditRefused] = MerchantCodecs.serializer[RecordCreditRefused]("record-credit-refused")
  given Serializer[RecordRetry]         = MerchantCodecs.serializer[RecordRetry]("record-retry")
  given Serializer[RecordFailure]       = MerchantCodecs.serializer[RecordFailure]("record-failure")
  given Serializer[At]                  = MerchantCodecs.serializer[At]("at")
  given Serializer[RecordWaiting]       = MerchantCodecs.serializer[RecordWaiting]("record-waiting")

import PaymentCodecs.given

final class Payment(context: EventSourcedEntityContext) extends EventSourcedEntity[PaymentState, PaymentEvent]:

  def emptyState: PaymentState = PaymentState(context.entityId)

  def applyEvent(event: PaymentEvent): PaymentState = Payment.fold(currentState, event)

  private def open[R](f: PaymentState => Effect[R]): Effect[R] =
    if currentState.exists then f(currentState) else effects.error("PAYMENT_NOT_FOUND", ErrorCode.NotFound)

  /** Recorded once by the initiate step; the step running again records nothing more. */
  def initiate(cmd: Open): Effect[PaymentState] =
    if currentState.exists then effects.reply(currentState)
    else effects.persist(PaymentEvent.Initiated(cmd.opened, cmd.status)).thenReplyState

  /** A capture: the first records it and the credited amount; a repeat keeps the invoice on record and records nothing. */
  def capture(cmd: RecordCapture): Effect[PaymentState] =
    open(s => if s.terminal then effects.reply(s) else effects.persist(PaymentEvent.Captured(cmd.variance, cmd.creditedAmount, cmd.at)).thenReplyState)

  def creditAttempted(cmd: RecordAttempt): Effect[PaymentState] =
    open(s => if s.credit.exists(_.status == CreditState.Credited) then effects.reply(s) else effects.persist(PaymentEvent.CreditAttempted(cmd.answer, cmd.at)).thenReplyState)

  def credited(cmd: RecordCredited): Effect[PaymentState] =
    open(s => if s.credit.exists(_.status == CreditState.Credited) then effects.reply(s) else effects.persist(PaymentEvent.Credited(cmd.txnId, cmd.at)).thenReplyState)

  def creditRefused(cmd: RecordCreditRefused): Effect[PaymentState] =
    open(s => if s.credit.exists(_.status != CreditState.Pending) then effects.reply(s)
      else effects.persist(PaymentEvent.CreditRefused(cmd.code, cmd.message, cmd.details, cmd.at)).thenReplyState)

  def creditRetried(cmd: RecordRetry): Effect[PaymentState] =
    open(s => if !s.credit.exists(_.status == CreditState.Refused) then effects.reply(s) else effects.persist(PaymentEvent.CreditRetried(cmd.by, cmd.at)).thenReplyState)

  def fail(cmd: RecordFailure): Effect[PaymentState] =
    open(s => if s.terminal then effects.reply(s) else effects.persist(PaymentEvent.Failed(cmd.code, cmd.reason, cmd.received, cmd.at)).thenReplyState)

  def expire(cmd: At): Effect[PaymentState] =
    open(s => if s.terminal then effects.reply(s) else effects.persist(PaymentEvent.Expired(cmd.at)).thenReplyState)

  def waiting(cmd: RecordWaiting): Effect[PaymentState] =
    open(s => if s.terminal || s.waitingReason.contains(cmd.reason) then effects.reply(s) else effects.persist(PaymentEvent.Waiting(cmd.reason, cmd.at)).thenReplyState)

  def get: ReadOnlyEffect[PaymentState] = effects.reply(currentState)

object Payment extends EventSourcedEntity.Companion[Payment, PaymentState, PaymentEvent](
      componentId = ComponentId("payment"),
      stateSerializer = PaymentCodecs.given_Serializer_PaymentState,
      eventSerializer = PaymentCodecs.given_Serializer_PaymentEvent
    ):
  def create(context: EventSourcedEntityContext) = new Payment(context)

  val initiate        = command("initiate")(_.initiate)
  val capture         = command("capture")(_.capture)
  val creditAttempted = command("credit-attempted")(_.creditAttempted)
  val credited        = command("credited")(_.credited)
  val creditRefused   = command("credit-refused")(_.creditRefused)
  val creditRetried   = command("credit-retried")(_.creditRetried)
  val fail            = command("fail")(_.fail)
  val expire          = command("expire")(_.expire)
  val waiting         = command("waiting")(_.waiting)
  val get             = query("get")(_.get)

  def fold(s: PaymentState, event: PaymentEvent): PaymentState = event match
    case PaymentEvent.Initiated(o, status) => s.copy(opened = Some(o), status = status)
    case PaymentEvent.Captured(variance, credited, at) =>
      val original = if variance.exists(_.kind == Variance.Underpayment) && s.originalAmount.isEmpty then s.opened.map(_.amount) else s.originalAmount
      s.copy(status = Status.Captured, capturedAt = Some(at), variance = variance, creditedAmount = Some(credited), originalAmount = original,
        waitingReason = None, credit = s.credit.orElse(Some(CreditState(CreditState.Pending, 0, None))))
    case PaymentEvent.CreditAttempted(answer, at) =>
      val c = s.credit.getOrElse(CreditState(CreditState.Pending, 0, None))
      s.copy(credit = Some(c.copy(attempts = c.attempts + 1, lastAnswer = Some(answer), at = Some(at))))
    case PaymentEvent.Credited(txnId, at) =>
      val c = s.credit.getOrElse(CreditState(CreditState.Pending, 0, None))
      s.copy(credit = Some(c.copy(status = CreditState.Credited, attempts = c.attempts + 1, lastAnswer = Some("credited"), txnId = txnId, at = Some(at))))
    case PaymentEvent.CreditRefused(code, message, details, at) =>
      val c = s.credit.getOrElse(CreditState(CreditState.Pending, 0, None))
      s.copy(credit = Some(c.copy(status = CreditState.Refused, attempts = c.attempts + 1, lastAnswer = Some(s"$code: $message"), code = Some(code),
        details = details, at = Some(at))))
    case PaymentEvent.CreditRetried(_, at) => s.copy(credit = s.credit.map(_.copy(status = CreditState.Pending, at = Some(at))))
    case PaymentEvent.Failed(code, reason, received, at) =>
      s.copy(status = Status.Failed, failedAt = Some(at), failureCode = code, failureReason = Some(reason), received = received, waitingReason = None)
    case PaymentEvent.Expired(at)        => s.copy(status = Status.Expired, expiredAt = Some(at), failureReason = Some(FailureReason.Abandoned), waitingReason = None)
    case PaymentEvent.Waiting(reason, _) => s.copy(waitingReason = Some(reason))
