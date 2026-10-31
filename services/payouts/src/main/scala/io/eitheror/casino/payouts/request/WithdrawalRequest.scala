package io.eitheror.casino.payouts.request

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.payouts.PayoutsCodecs
import io.eitheror.casino.payouts.request.RequestEvent.*

/**
 * The record of one withdrawal request (FR-008; R3), keyed by the request id: what was asked, what was
 * decided and what the provider said. The workflow writes it and the endpoints read it; no rule that needs
 * another component lives here. A command repeated after its event writes nothing; a request not opened
 * is not found.
 */
final class WithdrawalRequest(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[RequestState, RequestEvent]:
  import RequestCodes.*
  import WithdrawalRequest.applied

  def emptyState: RequestState = RequestState()

  def applyEvent(event: RequestEvent): RequestState = applied(currentState, event)

  private def write(event: RequestEvent): Effect[RequestAnswer] = effects.persist(event).thenReply(RequestAnswer(_))
  private def same: Effect[RequestAnswer]                         = effects.reply(RequestAnswer(currentState))
  private def refuse(code: String): Effect[RequestAnswer]         = effects.reply(RequestAnswer(currentState, Some(code)))

  /** Every command but `Open` and `Erase` reads an opened request. */
  private def opened(f: RequestState => Effect[RequestAnswer]): Effect[RequestAnswer] =
    if currentState.opened then f(currentState) else refuse(NotFound)

  def open(c: Open): Effect[RequestAnswer] =
    if currentState.opened then same
    else write(Opened(c.requestId, c.playerId, c.amount, c.paidAmount, c.currency, c.terminal, c.payee, c.forfeit, c.kyc, c.at,
      c.voidedAmount))

  def cancel(c: Cancel): Effect[RequestAnswer] = opened { s =>
    s.status match
      case Status.PendingReview => write(Cancelled(c.by, c.reasonCode, c.at))
      case Status.Cancelled     => same
      case _                    => refuse(NotCancellable)
  }

  /** A second approval or rejection, or a rejection after approval, is not pending (R1). */
  def approve(c: Approve): Effect[RequestAnswer] = opened { s =>
    if s.status == Status.PendingReview then write(Approved(c.operatorId, c.at)) else refuse(NotPending)
  }

  def reject(c: Reject): Effect[RequestAnswer] = opened { s =>
    if s.status == Status.PendingReview then write(Rejected(c.operatorId, c.reason, c.at)) else refuse(NotPending)
  }

  def awaitManual(c: AwaitManual): Effect[RequestAnswer] = opened { s =>
    if s.status == Status.Approved && s.payoutStatus == PayoutStatus.None then write(AwaitingManualPayout(c.reason, c.at)) else same
  }

  def initiated(c: Initiated): Effect[RequestAnswer] = opened { s =>
    if s.status == Status.Approved && s.payoutStatus == PayoutStatus.None then write(PayoutInitiated(c.answer, c.reference, c.at))
    else same
  }

  /** Every answer is kept, whatever the request's state, so a refused outcome is on the record too. */
  def record(c: Record): Effect[RequestAnswer] = opened(_ => write(OutcomeRecorded(c.record)))

  def needsReview(c: NeedsReview): Effect[RequestAnswer] = opened { s =>
    if s.status == Status.Approved && s.payoutStatus == PayoutStatus.Initiated then write(MarkedNeedsReview(c.reason, c.at)) else same
  }

  private def payable(s: RequestState) = s.status == Status.Approved || s.status == Status.AwaitingManualPayout

  def complete(c: Complete): Effect[RequestAnswer] = opened { s =>
    if payable(s) then write(Completed(c.source, c.reference, c.note, c.at, c.operatorId))
    else if s.status == Status.Completed || s.status == Status.Reversed then same
    else refuse(NotAwaitingPayout)
  }

  def fail(c: Fail): Effect[RequestAnswer] = opened { s =>
    if payable(s) then write(Failed(c.source, c.reasonCode, c.note, c.at, c.operatorId))
    else if s.status == Status.Failed then same
    else refuse(NotAwaitingPayout)
  }

  def reverse(c: Reverse): Effect[RequestAnswer] = opened { s =>
    s.status match
      case Status.Completed => write(Reversed(c.reasonCode, c.reference, c.at))
      case Status.Reversed  => same
      case _                => refuse(NotCompleted)
  }

  def referenceMismatch(c: ReferenceMismatch): Effect[RequestAnswer] = opened(_ => write(ReferenceMismatched(c.reference, c.at)))

  def erase(c: Erase): Effect[RequestAnswer] = if currentState.erased then same else write(Erased(c.at))

  def get: ReadOnlyEffect[RequestState] = effects.reply(currentState)

object WithdrawalRequest
    extends EventSourcedEntity.Companion[WithdrawalRequest, RequestState, RequestEvent](
      componentId = ComponentId("withdrawal-request"),
      stateSerializer = PayoutsCodecs.serializer[RequestState]("request-state"),
      eventSerializer = PayoutsCodecs.serializer[RequestEvent]("request-event")
    ):
  given Serializer[RequestState]      = PayoutsCodecs.serializer[RequestState]("request-reply")
  given Serializer[RequestAnswer]     = PayoutsCodecs.serializer[RequestAnswer]("request-answer")
  given Serializer[Open]              = PayoutsCodecs.serializer[Open]("open")
  given Serializer[Cancel]            = PayoutsCodecs.serializer[Cancel]("cancel")
  given Serializer[Approve]           = PayoutsCodecs.serializer[Approve]("approve")
  given Serializer[Reject]            = PayoutsCodecs.serializer[Reject]("reject")
  given Serializer[AwaitManual]       = PayoutsCodecs.serializer[AwaitManual]("await-manual")
  given Serializer[Initiated]         = PayoutsCodecs.serializer[Initiated]("initiated")
  given Serializer[Record]            = PayoutsCodecs.serializer[Record]("record")
  given Serializer[NeedsReview]       = PayoutsCodecs.serializer[NeedsReview]("needs-review")
  given Serializer[Complete]          = PayoutsCodecs.serializer[Complete]("complete")
  given Serializer[Fail]              = PayoutsCodecs.serializer[Fail]("fail")
  given Serializer[Reverse]           = PayoutsCodecs.serializer[Reverse]("reverse")
  given Serializer[ReferenceMismatch] = PayoutsCodecs.serializer[ReferenceMismatch]("reference-mismatch")
  given Serializer[Erase]             = PayoutsCodecs.serializer[Erase]("request-erase")

  def create(context: EventSourcedEntityContext) = new WithdrawalRequest(context)

  val open              = command("open")(_.open)
  val cancel            = command("cancel")(_.cancel)
  val approve           = command("approve")(_.approve)
  val reject            = command("reject")(_.reject)
  val awaitManual       = command("await-manual")(_.awaitManual)
  val initiated         = command("initiated")(_.initiated)
  val record            = command("record")(_.record)
  val needsReview       = command("needs-review")(_.needsReview)
  val complete          = command("complete")(_.complete)
  val fail              = command("fail")(_.fail)
  val reverse           = command("reverse")(_.reverse)
  val referenceMismatch = command("reference-mismatch")(_.referenceMismatch)
  val erase             = command("erase")(_.erase)
  val get               = query("get")(_.get)

  def applied(s: RequestState, event: RequestEvent): RequestState = event match
    case e: Opened =>
      s.copy(opened = true, requestId = e.requestId, playerId = e.playerId, amount = e.amount, paidAmount = e.paidAmount,
        voidedAmount = e.voidedAmount, currency = e.currency, terminal = Some(e.terminal), payee = e.payee, forfeit = e.forfeit, kyc = e.kyc, createdAt = e.at)
    case Cancelled(_, _, at)       => s.copy(status = Status.Cancelled, settledAt = Some(at))
    case Approved(op, at)          => s.copy(status = Status.Approved, decisions = s.decisions :+ Decision(true, op, None, at))
    case Rejected(op, reason, at)  =>
      s.copy(status = Status.Rejected, decisions = s.decisions :+ Decision(false, op, reason, at), settledAt = Some(at))
    case AwaitingManualPayout(_, _) => s.copy(status = Status.AwaitingManualPayout)
    case PayoutInitiated(_, ref, at) =>
      s.copy(payoutStatus = PayoutStatus.Initiated, providerReference = ref.orElse(s.providerReference), initiatedAt = Some(at))
    case OutcomeRecorded(r)        => s.copy(outcomes = s.outcomes :+ r)
    case MarkedNeedsReview(_, _)   => s.copy(payoutStatus = PayoutStatus.NeedsReview)
    case Completed(_, ref, _, at, _) =>
      s.copy(status = Status.Completed, payoutStatus = PayoutStatus.Resolved, providerReference = ref.orElse(s.providerReference),
        settledAt = Some(at))
    case Failed(_, _, _, at, _) =>
      s.copy(status = Status.Failed, payoutStatus = PayoutStatus.Resolved, settledAt = Some(at))
    case Reversed(_, ref, at)      => s.copy(status = Status.Reversed, providerReference = ref.orElse(s.providerReference), settledAt = Some(at))
    case ReferenceMismatched(_, _) => s
    case Erased(_) =>
      s.copy(payee = s.payee.erased, decisions = s.decisions.map(_.copy(reason = None)), outcomes = s.outcomes.map(_.copy(note = None)),
        erased = true)
