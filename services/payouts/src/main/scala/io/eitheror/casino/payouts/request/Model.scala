package io.eitheror.casino.payouts.request

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.PayoutsCodecs
import io.eitheror.casino.payouts.workflow.PayoutTerminal

import java.time.{Duration, Instant}

/** A request's status (FR-008); upper case on the wire, as the original's. */
enum Status(val wire: String):
  case PendingReview        extends Status("pending_review")
  case Approved             extends Status("approved")
  case AwaitingManualPayout extends Status("awaiting_manual_payout")
  case Completed            extends Status("completed")
  case Rejected             extends Status("rejected")
  case Cancelled            extends Status("cancelled")
  case Failed               extends Status("failed")
  case Reversed             extends Status("reversed")

  def upper: String = wire.toUpperCase

object Status:
  def from(text: String): Option[Status] = values.find(_.wire.equalsIgnoreCase(text))
  given JsonValueCodec[Status]          = PayoutsCodecs.wire(values, _.wire)

/** Where the payout stands once approved (FR-022). */
enum PayoutStatus(val wire: String):
  case None        extends PayoutStatus("none")
  case Initiated   extends PayoutStatus("initiated")
  case NeedsReview extends PayoutStatus("needs_review")
  case Resolved    extends PayoutStatus("resolved")

  def upper: String = wire.toUpperCase

object PayoutStatus:
  def from(text: String): Option[PayoutStatus] = values.find(_.wire.equalsIgnoreCase(text))
  given JsonValueCodec[PayoutStatus]          = PayoutsCodecs.wire(values, _.wire)

/** Who closed a request unpaid. */
enum Actor(val wire: String):
  case Player   extends Actor("player")
  case Operator extends Actor("operator")

object Actor:
  given JsonValueCodec[Actor] = PayoutsCodecs.wire(values, _.wire)

/** Where an outcome came from (data-model.md). */
enum Source(val wire: String):
  case Gateway  extends Source("gateway")
  case Outcome  extends Source("outcome")
  case Poll     extends Source("poll")
  case Operator extends Source("operator")

object Source:
  given JsonValueCodec[Source] = PayoutsCodecs.wire(values, _.wire)

/** The terminal as the listing held it when the player asked. */
final case class TerminalRef(
    id: String,
    subBrandId: String,
    name: String,
    gateway: String,
    scheme: String,
    supportsWithdrawal: Boolean,
    hasStatus: Boolean = false,
    reviewWindow: Option[Duration] = None
):
  def payout: PayoutTerminal = PayoutTerminal(id, subBrandId, gateway, scheme, supportsWithdrawal)

/** What the hold forfeited, from its answer. */
final case class Forfeit(bonusForfeited: Boolean = false, forfeitedAmount: Long = 0L, clawbackAmount: Long = 0L)

/** An operator's approval or rejection. */
final case class Decision(approved: Boolean, operatorId: String, reason: Option[Personal[String]], at: Instant)

/** Every gateway answer, outcome, poll and manual settlement, kept on the request (FR-008). */
final case class OutcomeRecord(
    source: Source,
    answer: String,
    reasonCode: Option[String] = None,
    reference: Option[String] = None,
    note: Option[Personal[String]] = None,
    at: Instant
)

/** The record of a withdrawal request (data-model.md); `opened` is false until the hold has been placed. */
final case class RequestState(
    opened: Boolean = false,
    requestId: String = "",
    playerId: String = "",
    amount: Long = 0L,
    paidAmount: Long = 0L,
    currency: String = "",
    terminal: Option[TerminalRef] = None,
    payee: Payee = Payee(),
    forfeit: Forfeit = Forfeit(),
    kyc: String = "",
    status: Status = Status.PendingReview,
    payoutStatus: PayoutStatus = PayoutStatus.None,
    providerReference: Option[String] = None,
    decisions: Vector[Decision] = Vector.empty,
    outcomes: Vector[OutcomeRecord] = Vector.empty,
    createdAt: Instant = Instant.EPOCH,
    initiatedAt: Option[Instant] = None,
    settledAt: Option[Instant] = None,
    erased: Boolean = false
):
  def settled: Boolean = Set(Status.Completed, Status.Rejected, Status.Cancelled, Status.Failed, Status.Reversed).contains(status)
  /** The operator's settlement routes accept these (FR-016). */
  def awaitingSettlement: Boolean =
    status == Status.AwaitingManualPayout || (status == Status.Approved && payoutStatus == PayoutStatus.NeedsReview)
  def currencyOf: Currency = Currency.unsafe(currency)

/** What a command answers: the state, and the refusal when there is one. */
final case class RequestAnswer(state: RequestState, refusal: Option[String] = None)

sealed trait RequestEvent
object RequestEvent:
  final case class Opened(requestId: String, playerId: String, amount: Long, paidAmount: Long, currency: String,
      terminal: TerminalRef, payee: Payee, forfeit: Forfeit, kyc: String, at: Instant)            extends RequestEvent
  final case class Cancelled(by: Actor, reasonCode: Option[String], at: Instant)                 extends RequestEvent
  final case class Approved(operatorId: String, at: Instant)                                    extends RequestEvent
  final case class Rejected(operatorId: String, reason: Option[Personal[String]], at: Instant)  extends RequestEvent
  final case class AwaitingManualPayout(reason: String, at: Instant)                            extends RequestEvent
  final case class PayoutInitiated(answer: String, reference: Option[String], at: Instant)      extends RequestEvent
  final case class OutcomeRecorded(record: OutcomeRecord)                                       extends RequestEvent
  final case class MarkedNeedsReview(reason: String, at: Instant)                               extends RequestEvent
  /** `operatorId` names the operator who settled it by hand, for the audit; none from the gateway or a poll. */
  final case class Completed(source: Source, reference: Option[String], note: Option[Personal[String]], at: Instant,
      operatorId: Option[String] = None) extends RequestEvent
  final case class Failed(source: Source, reasonCode: String, note: Option[Personal[String]], at: Instant,
      operatorId: Option[String] = None) extends RequestEvent
  final case class Reversed(reasonCode: String, reference: Option[String], at: Instant)         extends RequestEvent
  final case class ReferenceMismatched(reference: String, at: Instant)                              extends RequestEvent
  final case class Erased(at: Instant)                                                          extends RequestEvent

final case class Open(requestId: String, playerId: String, amount: Long, paidAmount: Long, currency: String,
    terminal: TerminalRef, payee: Payee, forfeit: Forfeit, kyc: String, at: Instant)
final case class Cancel(by: Actor, reasonCode: Option[String], at: Instant)
final case class Approve(operatorId: String, at: Instant)
final case class Reject(operatorId: String, reason: Option[Personal[String]], at: Instant)
final case class AwaitManual(reason: String, at: Instant)
final case class Initiated(answer: String, reference: Option[String], at: Instant)
final case class Record(record: OutcomeRecord)
final case class NeedsReview(reason: String, at: Instant)
final case class Complete(source: Source, reference: Option[String], note: Option[Personal[String]], at: Instant,
    operatorId: Option[String] = None)
final case class Fail(source: Source, reasonCode: String, note: Option[Personal[String]], at: Instant,
    operatorId: Option[String] = None)
final case class Reverse(reasonCode: String, reference: Option[String], at: Instant)
final case class ReferenceMismatch(reference: String, at: Instant)
final case class Erase(at: Instant)

/** The refusals the request answers (data-model.md). */
object RequestCodes:
  val NotFound          = "REQUEST_NOT_FOUND"
  val NotPending        = "REQUEST_NOT_PENDING"
  val NotCancellable    = "WITHDRAWAL_REQUEST_NOT_CANCELLABLE"
  val NotAwaitingPayout = "WITHDRAWAL_NOT_AWAITING_PAYOUT"
  val NotCompleted      = "WITHDRAWAL_NOT_IN_COMPLETED_STATE"
