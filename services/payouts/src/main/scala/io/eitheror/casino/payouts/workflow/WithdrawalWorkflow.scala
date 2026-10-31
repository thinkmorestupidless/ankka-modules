package io.eitheror.casino.payouts.workflow

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.{PayoutsCodecs, PayoutsRuntime}
import io.eitheror.casino.payouts.clients.{Held, Initiation, Money, PayoutRequest, PayoutStatus as Status2}
import io.eitheror.casino.payouts.player.PlayerPayouts
import io.eitheror.casino.payouts.request.*

import scala.concurrent.duration.*

/**
 * A request as the endpoint passed its gates (R4): who, how much, where to, and what the preview said
 * the hold would forfeit. `fingerprint` is the digest of the body under its key, so a different body
 * under the same key is told apart (`KEY_REUSED`). The payee is kept only until the request has it.
 */
final case class Job(
    requestId: String,
    playerId: String,
    currency: String,
    amount: Long,
    terminal: TerminalRef,
    payee: Payee,
    kyc: String,
    confirmForfeit: Boolean,
    preview: Preview,
    fingerprint: String
):
  def currencyOf: Currency = Currency.unsafe(currency)

/** The wallet's refusal of a money step: its status and code as it answered. */
final case class WalletRefusal(status: Int, code: String, message: String)

/** A release in hand: the wallet's outcome, and what the request records once the wallet answers. */
final case class Release(
    outcome: String,
    by: Actor,
    operatorId: Option[String] = None,
    reasonCode: Option[String] = None,
    reason: Option[Personal[String]] = None,
    source: Source = Source.Operator
)

/**
 * A completion in hand (data-model.md `pendingSettle`): the request records it, then the wallet confirms the
 * hold. `reverseAfter` is a reversal the provider reported before the request was completed, made once it is.
 */
final case class Settle(
    source: Source,
    reference: Option[String] = None,
    note: Option[Personal[String]] = None,
    operatorId: Option[String] = None,
    reverseAfter: Option[String] = None
)

/** An outcome the gateway delivered, or a status read gave (R8). */
final case class Outcome(outcome: String, reference: Option[String] = None, reasonCode: Option[String] = None)

/** An operator's decision or settlement by hand (FR-015, FR-016). */
final case class ByOperator(operatorId: String, reason: Option[Personal[String]] = None)

/**
 * The workflow's state (data-model.md *WithdrawalWorkflow*): the step it is at, the job, the hold's
 * answer, a refusal, the money steps done (never sent again, R9), and the release in hand.
 */
final case class Flow(
    step: String = Flow.New,
    job: Option[Job] = None,
    held: Option[Held] = None,
    refusal: Option[WalletRefusal] = None,
    money: Set[String] = Set.empty,
    release: Option[Release] = None,
    attempts: Int = 0,
    approver: Option[String] = None,
    settle: Option[Settle] = None,
    reversal: Option[Outcome] = None,
    polls: Int = 0
):
  def started: Boolean = step != Flow.New

object Flow:
  val New       = "new"
  val Holding   = "holding"
  val Refused   = "refused"
  val Opening   = "opening"
  val Review    = "review"
  val Releasing = "releasing"
  val Released  = "released"
  val Approving = "approving"
  val Paying    = "paying"
  val Initiating = "initiating"
  val Waiting   = "waiting"
  val NeedsReview = "needs_review"
  val AwaitingManual = "awaiting_manual"
  val Confirming = "confirming"
  val Completed = "completed"
  val Reversing = "reversing"
  val Reversed  = "reversed"

  /** Approved and not yet settled: where an outcome, or a settlement by hand, is taken. */
  val Pursued: Set[String] = Set(Waiting, NeedsReview, AwaitingManual)
  /** A step running a call: a command waits for it, so the caller is told to try again. */
  val Busy: Set[String] = Set(Approving, Paying, Initiating, Holding, Opening)

  /** A provider with no status to ask and no window in the listing is reviewed after the first poll's wait. */
  val NoWindow = "no_window"

  /** A transport failure waits 1 s, doubling to 30 s, and tries again without end (FR-024). */
  def backoff(attempts: Int): FiniteDuration = math.min(30000L, 1000L << math.min(attempts, 5)).millis

/** What a command answers: the state, and the refusal when there is one. */
final case class FlowAnswer(flow: Flow, refusal: Option[String] = None)

/** The workflow's refusals beside the request's: a different outcome on a settled request, or a step running. */
object FlowCodes:
  val AlreadyResolved = "ALREADY_RESOLVED"
  val Busy            = "BUSY"

/**
 * A workflow per withdrawal request (R3, R9), keyed by the request id. `start` is the atomic claim: a
 * second start answers the first's state, a different body under the same id `KEY_REUSED`. It places the
 * hold keyed by the request id, opens the request, and pauses for review; a cancel releases the hold
 * before the request records it. Every money step retries a transport failure without end, and a step the
 * wallet has answered is never sent again.
 */
final class WithdrawalWorkflow(context: WorkflowContext) extends Workflow[Flow]:
  import WithdrawalWorkflow.log

  private val components = context.componentClient
  private def wallet     = PayoutsRuntime.wallet(context.services)
  private def gateway    = PayoutsRuntime.gateway(context.services)
  private def waits      = PayoutsRuntime.settings
  private def now()      = PayoutsRuntime.now()
  private def job: Job   = currentState.job.get

  private def request = components.forEventSourcedEntity(EntityId(job.requestId))

  def emptyState: Flow = Flow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      // A step that throws (a crash mid-call) is run again: the wallet answers a key it has seen with its first outcome.
      .stepRecovery(WithdrawalWorkflow.hold, RecoverStrategy.maxRetries(5))
      .stepRecovery(WithdrawalWorkflow.open, RecoverStrategy.maxRetries(10))
      .stepRecovery(WithdrawalWorkflow.releaseStep, RecoverStrategy.maxRetries(5))
      .stepRecovery(WithdrawalWorkflow.approveStep, RecoverStrategy.maxRetries(10))
      .stepRecovery(WithdrawalWorkflow.pay, RecoverStrategy.maxRetries(10))
      // The gateway claims the request id: an initiation run again answers the first answer (R7).
      .stepRecovery(WithdrawalWorkflow.initiate, RecoverStrategy.maxRetries(5).failoverTo(WithdrawalWorkflow.waitStep))
      .stepRecovery(WithdrawalWorkflow.poll, RecoverStrategy.maxRetries(3).failoverTo(WithdrawalWorkflow.waitStep))
      .stepRecovery(WithdrawalWorkflow.review, RecoverStrategy.maxRetries(10))
      .stepRecovery(WithdrawalWorkflow.confirm, RecoverStrategy.maxRetries(10))
      .stepRecovery(WithdrawalWorkflow.reverse, RecoverStrategy.maxRetries(10))
      .build

  // ── Commands ─────────────────────────────────────────────────────────────────────────────────

  def start(j: Job): Effect[FlowAnswer] =
    currentState.job match
      case Some(first) if first.fingerprint != j.fingerprint => effects.reply(FlowAnswer(currentState, Some(Refusals.KeyReused.code)))
      case Some(_)                                           => effects.reply(FlowAnswer(currentState))
      case None =>
        val next = Flow(Flow.Holding, Some(j))
        effects.updateState(next).transitionTo(WithdrawalWorkflow.hold.ref).thenReply(FlowAnswer(next))

  def status: ReadOnlyEffect[Flow] = effects.reply(currentState)

  /** The player's cancel, accepted only while the request is pending review (FR-014). */
  def cancel(by: Actor): Effect[FlowAnswer] =
    if currentState.step != Flow.Review then effects.reply(FlowAnswer(currentState, Some(RequestCodes.NotCancellable)))
    else
      val next = currentState.copy(step = Flow.Releasing, release = Some(Release("cancelled", by)))
      effects.updateState(next).transitionTo(WithdrawalWorkflow.releaseStep.ref).thenReply(FlowAnswer(next))

  /** The operator's approval, taken only while the request is pending review (FR-015). */
  def approve(by: ByOperator): Effect[FlowAnswer] =
    if Flow.Busy(currentState.step) then effects.reply(FlowAnswer(currentState, Some(FlowCodes.Busy)))
    else if currentState.step != Flow.Review then effects.reply(FlowAnswer(currentState, Some(RequestCodes.NotPending)))
    else
      val next = currentState.copy(step = Flow.Approving, approver = Some(by.operatorId))
      effects.updateState(next).transitionTo(WithdrawalWorkflow.approveStep.ref).thenReply(FlowAnswer(next))

  /** The operator's rejection: the hold is released, then the request records the reason. */
  def reject(by: ByOperator): Effect[FlowAnswer] =
    if Flow.Busy(currentState.step) then effects.reply(FlowAnswer(currentState, Some(FlowCodes.Busy)))
    else if currentState.step != Flow.Review then effects.reply(FlowAnswer(currentState, Some(RequestCodes.NotPending)))
    else releasing(Release("rejected", Actor.Operator, Some(by.operatorId), reason = by.reason))

  /**
   * The provider's outcome (R8): taken once the request is approved and before it is settled. On a settled
   * request the same outcome is answered as it was and moves nothing; another is `ALREADY_RESOLVED`, but a
   * reversal of a completed request. A reversal of a request not completed is `WITHDRAWAL_NOT_IN_COMPLETED_STATE`.
   */
  def outcome(o: Outcome): Effect[FlowAnswer] =
    val s    = currentState
    def same = effects.reply(FlowAnswer(s))
    def no(code: String) = effects.reply(FlowAnswer(s, Some(code)))
    if Flow.Busy(s.step) then no(FlowCodes.Busy)
    else
      o.outcome match
        case "completed" =>
          if Flow.Pursued(s.step) then settling(Settle(Source.Outcome, o.reference))
          else if s.step == Flow.Confirming || s.step == Flow.Completed then same
          else no(FlowCodes.AlreadyResolved)
        case "failed" =>
          if Flow.Pursued(s.step) then
            releasing(Release("failed", Actor.Operator, reasonCode = Some(o.reasonCode.getOrElse("provider_failed")), source = Source.Outcome))
          else if s.release.exists(_.outcome == "failed") then same
          else no(FlowCodes.AlreadyResolved)
        case _ =>
          if s.step == Flow.Completed then
            val next = s.copy(step = Flow.Reversing, reversal = Some(o))
            effects.updateState(next).transitionTo(WithdrawalWorkflow.reverse.ref).thenReply(FlowAnswer(next))
          else if s.step == Flow.Reversing || s.step == Flow.Reversed then same
          else no(RequestCodes.NotCompleted)

  /** The operator's settlement of a request awaiting manual payout or marked needs review (FR-016). */
  def confirmPayout(by: ByOperator): Effect[FlowAnswer] =
    if Flow.Busy(currentState.step) then effects.reply(FlowAnswer(currentState, Some(FlowCodes.Busy)))
    else if !manual(currentState.step) then effects.reply(FlowAnswer(currentState, Some(RequestCodes.NotAwaitingPayout)))
    else settling(Settle(Source.Operator, note = by.reason, operatorId = Some(by.operatorId)))

  def markFailed(by: ByOperator): Effect[FlowAnswer] =
    if Flow.Busy(currentState.step) then effects.reply(FlowAnswer(currentState, Some(FlowCodes.Busy)))
    else if !manual(currentState.step) then effects.reply(FlowAnswer(currentState, Some(RequestCodes.NotAwaitingPayout)))
    else releasing(Release("failed", Actor.Operator, Some(by.operatorId), Some("manual_payout_failed"), by.reason))

  private def manual(step: String) = step == Flow.AwaitingManual || step == Flow.NeedsReview

  private def releasing(r: Release): Effect[FlowAnswer] =
    val next = currentState.copy(step = Flow.Releasing, release = Some(r))
    effects.updateState(next).transitionTo(WithdrawalWorkflow.releaseStep.ref).thenReply(FlowAnswer(next))

  private def settling(s: Settle): Effect[FlowAnswer] =
    val next = currentState.copy(step = Flow.Confirming, settle = Some(s))
    effects.updateState(next).transitionTo(WithdrawalWorkflow.confirm.ref).thenReply(FlowAnswer(next))

  // ── Steps ────────────────────────────────────────────────────────────────────────────────────

  def holdStep: StepEffect =
    val j = job
    if currentState.money("hold") then stepEffects.thenTransitionTo(WithdrawalWorkflow.open.ref)
    else
      wallet.hold(j.playerId, j.currencyOf, j.requestId, j.amount, j.confirmForfeit) match
        case Money.Done(held) =>
          stepEffects.updateState(currentState.copy(step = Flow.Opening, held = Some(held), money = currentState.money + "hold", attempts = 0))
            .thenTransitionTo(WithdrawalWorkflow.open.ref)
        case Money.Refused(status, code, message) =>
          // Nothing was held and no request exists: the payee is not kept.
          stepEffects.updateState(currentState.copy(step = Flow.Refused, refusal = Some(WalletRefusal(status, code, message)),
            job = Some(j.copy(payee = Payee())))).thenEnd
        case Money.Unreachable(why) =>
          log.warn(s"withdrawal ${j.requestId}: the hold was not answered, trying again: $why")
          stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
            .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.hold.ref)

  /** The request opened with what the hold moved; the payee then lives on the request alone. */
  def openStep: StepEffect =
    val j    = job
    val held = currentState.held.get
    val forfeit = Forfeit(held.bonusForfeited, if held.bonusForfeited then j.preview.bonusBalance else 0L, held.clawbackAmount)
    request.call(WithdrawalRequest.open).invoke(Open(j.requestId, j.playerId, j.amount, held.paidAmount, j.currency, j.terminal,
      j.payee, forfeit, j.kyc, now())): Unit
    stepEffects.updateState(currentState.copy(step = Flow.Review, job = Some(j.copy(payee = Payee())))).thenPause()

  /** The hold released with the outcome in hand, then the request records why; a repeat the wallet already made is done (R9). */
  def releaseStepRun: StepEffect =
    val j = job
    val r = currentState.release.get
    val step = s"release/${r.outcome}"
    val answer = if currentState.money(step) then Money.Done(()) else wallet.release(j.playerId, j.currencyOf, j.requestId, r.outcome)
    answer match
      case Money.Done(()) =>
        val at = now()
        r.outcome match
          case "cancelled" => request.call(WithdrawalRequest.cancel).invoke(Cancel(r.by, r.reasonCode, at)): Unit
          case "rejected"  => request.call(WithdrawalRequest.reject).invoke(Reject(r.operatorId.getOrElse(""), r.reason, at)): Unit
          case _           => request.call(WithdrawalRequest.fail).invoke(Fail(r.source, r.reasonCode.getOrElse(r.outcome), r.reason, at, r.operatorId)): Unit
        // The reason lives on the request alone (erasure, FR-022).
        stepEffects.updateState(currentState.copy(step = Flow.Released, money = currentState.money + step, attempts = 0,
          release = Some(r.copy(reason = None)))).thenEnd
      case Money.Refused(status, code, _) =>
        log.warn(s"withdrawal ${j.requestId}: the wallet refused the release ($status $code), trying again")
        stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
          .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.releaseStep.ref)
      case Money.Unreachable(why) =>
        log.warn(s"withdrawal ${j.requestId}: the release was not answered, trying again: $why")
        stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
          .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.releaseStep.ref)

  /** The request records the approval, then the payout is decided. */
  def approveStepRun: StepEffect =
    request.call(WithdrawalRequest.approve).invoke(Approve(currentState.approver.getOrElse(""), now())): Unit
    stepEffects.updateState(currentState.copy(step = Flow.Paying)).thenTransitionTo(WithdrawalWorkflow.pay.ref)

  /** Automated or manual, as the original decided it (R7), from the request and the player's names and email. */
  def payStep: StepEffect =
    val r      = request.call(WithdrawalRequest.get).invoke()
    val player = components.forEventSourcedEntity(EntityId(r.playerId)).call(PlayerPayouts.get).invoke()
    val names  = player.names.map(n => (n.givenName, n.familyName))
    r.terminal.map(t => Approval.decide(t.payout, r.payee, r.playerId, names, player.email)) match
      case Some(Approval.Decision.Automated(_)) =>
        stepEffects.updateState(currentState.copy(step = Flow.Initiating)).thenTransitionTo(WithdrawalWorkflow.initiate.ref)
      case other =>
        val reason = other.collect { case Approval.Decision.Manual(why) => why }.getOrElse("terminal_missing")
        request.call(WithdrawalRequest.awaitManual).invoke(AwaitManual(reason, now())): Unit
        stepEffects.updateState(currentState.copy(step = Flow.AwaitingManual)).thenPause()

  /**
   * One call to the gateway with the request id as its key (R7). The details are read from the request and
   * sent, never held here. Every answer is recorded; only `rejected` and a refusal before any provider
   * release the money, and nothing that may have reached a provider is offered for manual payout.
   */
  def initiateStep: StepEffect =
    val r      = request.call(WithdrawalRequest.get).invoke()
    val player = components.forEventSourcedEntity(EntityId(r.playerId)).call(PlayerPayouts.get).invoke()
    val names  = player.names.map(n => (n.givenName, n.familyName))
    val t      = r.terminal.get
    Approval.decide(t.payout, r.payee, r.playerId, names, player.email) match
      case Approval.Decision.Manual(why) =>
        // The request's details were erased between approval and the call: nothing was sent.
        request.call(WithdrawalRequest.awaitManual).invoke(AwaitManual(why, now())): Unit
        stepEffects.updateState(currentState.copy(step = Flow.AwaitingManual)).thenPause()
      case Approval.Decision.Automated(payout) =>
        val answer = gateway.initiate(PayoutRequest(r.requestId, t.id, r.paidAmount, r.currencyOf, payout))
        val at     = now()
        def recorded(word: String, reason: Option[String] = None, reference: Option[String] = None) =
          request.call(WithdrawalRequest.record).invoke(Record(OutcomeRecord(Source.Gateway, word, reason, reference, None, at))): Unit
        def initiated(word: String, reference: Option[String]) =
          request.call(WithdrawalRequest.initiated).invoke(Initiated(word, reference, at)): Unit
          stepEffects.updateState(currentState.copy(step = Flow.Waiting, polls = 0)).thenTransitionTo(WithdrawalWorkflow.waitStep.ref)
        answer match
          case Initiation.Completed(reference) =>
            recorded("completed", reference = reference)
            stepEffects.updateState(currentState.copy(step = Flow.Confirming, settle = Some(Settle(Source.Gateway, reference))))
              .thenTransitionTo(WithdrawalWorkflow.confirm.ref)
          case Initiation.Initiated(reference)        => initiated("initiated", reference)
          case Initiation.AlreadyInitiated(reference) => initiated("already_initiated", reference)
          case Initiation.InDoubt(reason) =>
            recorded("in_doubt", Some(reason))
            initiated("in_doubt", None)
          case Initiation.InProgress => initiated("initiation_in_progress", None)
          case Initiation.Unreadable(reason) =>
            log.warn(s"withdrawal ${r.requestId}: the gateway's answer could not be read; it is in doubt: $reason")
            recorded("unreadable", Some(reason))
            initiated("unreadable", None)
          case Initiation.Rejected(reason) =>
            recorded("rejected", Some(reason))
            stepEffects.updateState(currentState.copy(step = Flow.Releasing,
              release = Some(Release("failed", Actor.Operator, reasonCode = Some("provider_rejected"), source = Source.Gateway))))
              .thenTransitionTo(WithdrawalWorkflow.releaseStep.ref)
          case Initiation.Refused(status, code) =>
            recorded("refused", Some(s"$status $code"))
            stepEffects.updateState(currentState.copy(step = Flow.Releasing,
              release = Some(Release("failed", Actor.Operator, reasonCode = Some(code), source = Source.Gateway))))
              .thenTransitionTo(WithdrawalWorkflow.releaseStep.ref)

  /**
   * The wait (R8): a provider with a status to ask is asked after the first poll's wait, then every
   * `pollEvery`; one with none is reviewed when its window from the listing has passed.
   */
  def waitStepRun: StepEffect =
    val t = request.call(WithdrawalRequest.get).invoke().terminal
    if t.exists(_.hasStatus) then
      val after = if currentState.polls == 0 then waits.firstPoll else waits.pollEvery
      stepEffects.updateState(currentState.copy(step = Flow.Waiting)).thenPause(after.toMillis.millis, WithdrawalWorkflow.poll.ref)
    else
      val window = t.flatMap(_.reviewWindow).getOrElse(waits.firstPoll)
      stepEffects.updateState(currentState.copy(step = Flow.Waiting)).thenPause(window.toMillis.millis, WithdrawalWorkflow.review.ref)

  def pollStep: StepEffect =
    val polled = currentState.copy(polls = currentState.polls + 1)
    if currentState.step != Flow.Waiting then stepEffects.thenPause()
    else
      val at = now()
      def recorded(word: String, reason: Option[String], reference: Option[String]) =
        request.call(WithdrawalRequest.record).invoke(Record(OutcomeRecord(Source.Poll, word, reason, reference, None, at))): Unit
      gateway.status(job.requestId) match
        case Status2.Completed(reference) =>
          recorded("completed", None, reference)
          stepEffects.updateState(polled.copy(step = Flow.Confirming, settle = Some(Settle(Source.Poll, reference))))
            .thenTransitionTo(WithdrawalWorkflow.confirm.ref)
        case Status2.Failed(reference, reason) =>
          recorded("failed", Some(reason), reference)
          stepEffects.updateState(polled.copy(step = Flow.Releasing,
            release = Some(Release("failed", Actor.Operator, reasonCode = Some(reason), source = Source.Poll))))
            .thenTransitionTo(WithdrawalWorkflow.releaseStep.ref)
        case Status2.Reversed(reference, reason) =>
          // Paid, then charged back: completed first, then reversed, so the wallet's ledger tells both.
          recorded("reversed", Some(reason), reference)
          stepEffects.updateState(polled.copy(step = Flow.Confirming,
            settle = Some(Settle(Source.Poll, reference, reverseAfter = Some(reason)))))
            .thenTransitionTo(WithdrawalWorkflow.confirm.ref)
        case Status2.NoRecord =>
          recorded("no_record", None, None)
          stepEffects.updateState(polled.copy(step = Flow.NeedsReview)).thenTransitionTo(WithdrawalWorkflow.review.ref)
        case Status2.Pending | Status2.Unavailable(_) =>
          stepEffects.updateState(polled).thenTransitionTo(WithdrawalWorkflow.waitStep.ref)

  /** Marked needs review, and paused for the operator or the provider's outcome (FR-022). */
  def reviewStep: StepEffect =
    if currentState.step != Flow.Waiting && currentState.step != Flow.NeedsReview then stepEffects.thenPause()
    else
      val reason = if currentState.polls > 0 then "no_record" else "provider_window_passed"
      request.call(WithdrawalRequest.needsReview).invoke(NeedsReview(reason, now())): Unit
      stepEffects.updateState(currentState.copy(step = Flow.NeedsReview)).thenPause()

  /**
   * The request is completed first: the provider paid, so it is completed whatever follows (spec Edge Cases).
   * Then the wallet confirms the hold, keyed `<id>/confirm`, until it answers; a confirm it already made
   * answers `REQUEST_NOT_PENDING`, which is the first outcome. The workflow then waits for a reversal.
   */
  def confirmStepRun: StepEffect =
    val j      = job
    val settle = currentState.settle.get
    request.call(WithdrawalRequest.complete).invoke(Complete(settle.source, settle.reference, settle.note, now(), settle.operatorId)): Unit
    val answer =
      if currentState.money("confirm") then Money.Done(())
      else wallet.confirm(j.playerId, j.currencyOf, j.requestId) match
        case Money.Refused(_, "REQUEST_NOT_PENDING", _) => Money.Done(())
        case other                                       => other
    answer match
      case Money.Done(()) =>
        val done = currentState.copy(step = Flow.Completed, money = currentState.money + "confirm", attempts = 0,
          settle = Some(settle.copy(note = None)))
        settle.reverseAfter match
          case Some(reason) =>
            stepEffects.updateState(done.copy(step = Flow.Reversing, reversal = Some(Outcome("reversed", settle.reference, Some(reason)))))
              .thenTransitionTo(WithdrawalWorkflow.reverse.ref)
          case None => stepEffects.updateState(done).thenPause()
      case Money.Refused(status, code, _) =>
        log.warn(s"withdrawal ${j.requestId}: the wallet refused the confirm ($status $code), trying again")
        stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
          .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.confirm.ref)
      case Money.Unreachable(why) =>
        log.warn(s"withdrawal ${j.requestId}: the confirm was not answered, trying again: $why")
        stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
          .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.confirm.ref)

  /** The wallet reverses the confirmed hold, keyed `<id>/reverse`; then the request records it, and the workflow ends. */
  def reverseStepRun: StepEffect =
    val j = job
    val o = currentState.reversal.getOrElse(Outcome("reversed"))
    val answer = if currentState.money("reverse") then Money.Done(()) else wallet.reverse(j.playerId, j.currencyOf, j.requestId)
    answer match
      case Money.Done(()) =>
        request.call(WithdrawalRequest.reverse).invoke(Reverse(o.reasonCode.getOrElse("chargeback"), o.reference, now())): Unit
        stepEffects.updateState(currentState.copy(step = Flow.Reversed, money = currentState.money + "reverse", attempts = 0)).thenEnd
      case Money.Refused(status, code, _) =>
        log.warn(s"withdrawal ${j.requestId}: the wallet refused the reverse ($status $code), trying again")
        stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
          .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.reverse.ref)
      case Money.Unreachable(why) =>
        log.warn(s"withdrawal ${j.requestId}: the reverse was not answered, trying again: $why")
        stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
          .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.reverse.ref)

object WithdrawalWorkflow
    extends Workflow.Companion[WithdrawalWorkflow, Flow](
      componentId = ComponentId("withdrawal"),
      stateSerializer = PayoutsCodecs.serializer[Flow]("withdrawal-flow")
    ):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[WithdrawalWorkflow])

  given Serializer[Job]        = PayoutsCodecs.serializer[Job]("withdrawal-job")
  given Serializer[FlowAnswer] = PayoutsCodecs.serializer[FlowAnswer]("withdrawal-answer")
  given Serializer[Flow]       = PayoutsCodecs.serializer[Flow]("withdrawal-flow-reply")
  given Serializer[Actor]      = PayoutsCodecs.serializer[Actor]("withdrawal-actor")
  given Serializer[ByOperator] = PayoutsCodecs.serializer[ByOperator]("withdrawal-by-operator")
  given Serializer[Outcome]    = PayoutsCodecs.serializer[Outcome]("withdrawal-outcome")

  def create(context: WorkflowContext) = new WithdrawalWorkflow(context)

  /** The request id: a name-based UUID of `<player>/<key>`, safe in a path and the same for a repeat (contracts/routes.md). */
  def requestId(playerId: String, key: String): String =
    java.util.UUID.nameUUIDFromBytes(s"$playerId/$key".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString

  val hold        = step("hold")(_.holdStep)
  val open        = step("open")(_.openStep)
  val releaseStep = step("release")(_.releaseStepRun)
  val approveStep = step("approve")(_.approveStepRun)
  val pay         = step("pay")(_.payStep)
  val initiate    = step("initiate")(_.initiateStep)
  val waitStep    = step("wait")(_.waitStepRun)
  val poll        = step("poll")(_.pollStep)
  val review      = step("needs-review")(_.reviewStep)
  val confirm     = step("confirm")(_.confirmStepRun)
  val reverse     = step("reverse")(_.reverseStepRun)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
  val cancel = command("cancel")(_.cancel)
  val approve       = command("approve")(_.approve)
  val reject        = command("reject")(_.reject)
  val outcome       = command("outcome")(_.outcome)
  val confirmPayout = command("confirm-payout")(_.confirmPayout)
  val markFailed    = command("mark-failed")(_.markFailed)
