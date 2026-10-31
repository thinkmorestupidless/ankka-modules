package io.eitheror.casino.payouts.workflow

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.{PayoutsCodecs, PayoutsRuntime}
import io.eitheror.casino.payouts.clients.{Held, Money}
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
final case class Release(outcome: String, by: Actor, operatorId: Option[String] = None, reasonCode: Option[String] = None)

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
    attempts: Int = 0
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

  /** A transport failure waits 1 s, doubling to 30 s, and tries again without end (FR-024). */
  def backoff(attempts: Int): FiniteDuration = math.min(30000L, 1000L << math.min(attempts, 5)).millis

/** What a command answers: the state, and the refusal when there is one. */
final case class FlowAnswer(flow: Flow, refusal: Option[String] = None)

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
          case "rejected"  => request.call(WithdrawalRequest.reject).invoke(Reject(r.operatorId.getOrElse(""), None, at)): Unit
          case _           => request.call(WithdrawalRequest.fail).invoke(Fail(Source.Operator, r.reasonCode.getOrElse(r.outcome), None, at, r.operatorId)): Unit
        stepEffects.updateState(currentState.copy(step = Flow.Released, money = currentState.money + step, attempts = 0)).thenEnd
      case Money.Refused(status, code, _) =>
        log.warn(s"withdrawal ${j.requestId}: the wallet refused the release ($status $code), trying again")
        stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
          .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.releaseStep.ref)
      case Money.Unreachable(why) =>
        log.warn(s"withdrawal ${j.requestId}: the release was not answered, trying again: $why")
        stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
          .thenPause(Flow.backoff(currentState.attempts), WithdrawalWorkflow.releaseStep.ref)

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

  def create(context: WorkflowContext) = new WithdrawalWorkflow(context)

  /** The request id: a name-based UUID of `<player>/<key>`, safe in a path and the same for a repeat (contracts/routes.md). */
  def requestId(playerId: String, key: String): String =
    java.util.UUID.nameUUIDFromBytes(s"$playerId/$key".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString

  val hold        = step("hold")(_.holdStep)
  val open        = step("open")(_.openStep)
  val releaseStep = step("release")(_.releaseStepRun)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
  val cancel = command("cancel")(_.cancel)
