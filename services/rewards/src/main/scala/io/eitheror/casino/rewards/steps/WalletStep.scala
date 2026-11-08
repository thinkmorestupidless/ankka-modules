package io.eitheror.casino.rewards.steps

import com.github.plokhotnyuk.jsoniter_scala.macros.named
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.{RewardsCodecs, RewardsRuntime}
import io.eitheror.casino.rewards.clients.{AwardTerms, Called, Wallet}
import io.eitheror.casino.rewards.reward.{Ending, RevokeMode}

import scala.concurrent.duration.*

/**
 * One wallet command of research R5's table. `step` is its name, the last part of the workflow's id
 * (`<reward>/<step>`); the wallet's key is the reward id for `award` and the workflow's id for the rest.
 */
sealed trait WalletCall:
  def step: String

object WalletCall:
  @named("award") final case class Award(terms: AwardTerms) extends WalletCall:
    def step = "award"
  /**
   * Free rounds' winnings as restricted funds, the award of a reward of their own (`<reward>/winnings`, or
   * `<reward>/late/<round>` for a late round): as `award`, below every open record unless a priority is set.
   */
  @named("winnings") final case class Winnings(terms: AwardTerms) extends WalletCall:
    def step = "winnings"
  @named("forfeit") case object Forfeit extends WalletCall:
    def step = "forfeit"
  /** `requireBaseline` on an operator's reclaim (W4). */
  @named("cancel") final case class Cancel(requireBaseline: Boolean = false) extends WalletCall:
    def step = "cancel"
  /**
   * An operator's revoke (FR-017): `forfeit` the wallet's forfeit, `reclaim` its cancel with `requireBaseline` (W4).
   * Its own step for each mode, apart from the timers' `forfeit` and the player's `cancel`, so a refused revoke
   * (`MISSING_BASELINE`, or a record not open) never stands for those steps, nor a reclaim refused for a later forfeit.
   */
  @named("revoke") final case class Revoke(mode: RevokeMode) extends WalletCall:
    def step = s"revoke-${mode.wire}"
  @named("close-cap") case object CloseCap extends WalletCall:
    def step = "close-cap"
  /** The player's `n`th switch to this reward. */
  @named("switch") final case class Switch(n: Int) extends WalletCall:
    def step = s"switch-$n"
  /** Free rounds' winnings as real balance; `providerRef` is the reward id. */
  @named("credit") final case class Credit(amount: Long, reason: String, actor: String) extends WalletCall:
    def step = "credit"
  /** A round after finalisation paid as real balance (FR-024). */
  @named("late-credit") final case class LateCredit(round: String, amount: Long, reason: String, actor: String) extends WalletCall:
    def step = s"late/$round"

/**
 * A step's request: the reward, its player's wallet, the call, and whether a timer asked for it (`byTimer`), which
 * decides whether `NO_ACTIVE_BONUS` and `NOT_PENDING_CAP` are a refusal or `NotOpen` (FR-015). A timer's step carries
 * how it ends the reward (`ending`), so the delivery moves the reward as the timer decided when it fired.
 */
final case class StepRequest(rewardId: String, playerId: String, currency: String, call: WalletCall, byTimer: Boolean = false,
    ending: Option[Ending] = None):
  def id: String = WalletStep.id(rewardId, call)
  /** The wallet's key: the reward id for an award, which the wallet names its record by; the step's id otherwise. */
  def key: String = call match
    case _: WalletCall.Award | _: WalletCall.Winnings => rewardId
    case _                   => id

/**
 * The step's state (data-model.md *WalletStep*): where it is (`phase`), the request, the attempts at the wallet,
 * the answer once the wallet gave one, and whether the reward has it. `lastError` is the last transport failure.
 */
final case class StepState(
    phase: String = StepState.New,
    request: Option[StepRequest] = None,
    attempts: Int = 0,
    answer: Option[WalletAnswer] = None,
    lastError: Option[String] = None
):
  def step: Option[String] = request.map(_.call.step)

object StepState:
  val New        = "new"
  val Calling    = "calling"
  val Delivering = "delivering"
  val Done       = "done"

  /** 1 s doubling to at most 10 minutes between tries, without end (research R5). */
  def backoff(attempts: Int): FiniteDuration = math.min(600000L, 1000L << math.min(attempts, 10)).millis

/**
 * Where a step's answer goes: the reward the step is for, with the request (which says what was asked and whether
 * a timer asked it). The service wires it to `Reward`; a suite records. Called at least once per step: a delivery
 * repeated after a fault carries the same answer, so the receiver takes one answer per step id.
 */
trait Delivery:
  def deliver(rewardId: String, request: StepRequest, answer: WalletAnswer): Unit

/**
 * A wallet command as a workflow (research R5), id `<reward>/<step>`. `start` is the atomic claim: a second start of
 * the same id answers the first's state and calls nothing. `call` asks the wallet, trying a transport failure or a
 * 5xx again with backoff until it answers; `deliver` hands the answer to the reward. Past the wallet's recent keys a
 * repeat is answered from this state, never sent again.
 */
final class WalletStep(context: WorkflowContext) extends Workflow[StepState]:
  import WalletStep.log

  private def wallet   = RewardsRuntime.wallet(context.services)
  private def delivery = RewardsRuntime.delivery(context.componentClient)

  def emptyState: StepState = StepState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      // A step that throws is run again: the wallet answers a key it has seen with its first outcome, and a delivery
      // carries the answer recorded. Past its retries `recover` waits and runs it again, so no step ends failed.
      .stepRecovery(WalletStep.call, RecoverStrategy.maxRetries(5).failoverTo(WalletStep.recover))
      .stepRecovery(WalletStep.deliver, RecoverStrategy.maxRetries(5).failoverTo(WalletStep.recover))
      .stepRecovery(WalletStep.recover, RecoverStrategy.maxRetries(100))
      .build

  // ── Commands ─────────────────────────────────────────────────────────────────────────────────

  def start(r: StepRequest): Effect[StepState] =
    if currentState.request.isDefined then effects.reply(currentState)
    else if r.id != context.workflowId.toString then
      effects.error(s"the step for ${r.id} is not ${context.workflowId}", ErrorCode.BadRequest)
    else
      val next = StepState(StepState.Calling, Some(r))
      effects.updateState(next).transitionTo(WalletStep.call.ref).thenReply(next)

  def status: ReadOnlyEffect[StepState] = effects.reply(currentState)

  // ── Steps ────────────────────────────────────────────────────────────────────────────────────

  def callStep: StepEffect =
    val r = currentState.request.get
    currentState.answer match
      case Some(_) => stepEffects.updateState(currentState.copy(phase = StepState.Delivering)).thenTransitionTo(WalletStep.deliver.ref)
      case None =>
        WalletStep.answer(r, ask(r)) match
          case Right(a) =>
            stepEffects.updateState(currentState.copy(phase = StepState.Delivering, answer = Some(a), lastError = None))
              .thenTransitionTo(WalletStep.deliver.ref)
          case Left(why) =>
            log.warn(s"wallet step ${r.id}: the wallet did not answer, trying again: $why")
            stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1, lastError = Some(why)))
              .thenPause(StepState.backoff(currentState.attempts), WalletStep.call.ref)

  def deliverStep: StepEffect =
    val r = currentState.request.get
    delivery.deliver(r.rewardId, r, currentState.answer.get)
    stepEffects.updateState(currentState.copy(phase = StepState.Done)).thenEnd

  /** A step that failed past its retries: wait, then run it again. */
  def recoverStep: StepEffect =
    val again = if currentState.phase == StepState.Delivering then WalletStep.deliver else WalletStep.call
    log.error(s"wallet step ${context.workflowId}: the ${currentState.phase} step failed past its retries; running it again")
    stepEffects.updateState(currentState.copy(attempts = currentState.attempts + 1))
      .thenPause(StepState.backoff(currentState.attempts), again.ref)

  private def ask(r: StepRequest): Called[WalletAnswer] =
    val currency = Currency.unsafe(r.currency)
    val (player, key) = (r.playerId, r.key)
    r.call match
      case WalletCall.Award(t)       => wallet.award(player, currency, key, t).map(WalletAnswer.Awarded(_))
      case WalletCall.Winnings(t)    => wallet.award(player, currency, key, WalletStep.below(wallet, player, currency, t)).map(WalletAnswer.Awarded(_))
      case WalletCall.Forfeit        => wallet.forfeit(player, currency, key, r.rewardId).map(WalletAnswer.Forfeited(_))
      case WalletCall.Cancel(base)   => wallet.cancel(player, currency, key, r.rewardId, base).map(WalletAnswer.Cancelled(_))
      case WalletCall.Revoke(RevokeMode.Forfeit) => wallet.forfeit(player, currency, key, r.rewardId).map(WalletAnswer.Forfeited(_))
      case WalletCall.Revoke(RevokeMode.Reclaim) =>
        wallet.cancel(player, currency, key, r.rewardId, requireBaseline = true).map(WalletAnswer.Cancelled(_))
      case WalletCall.CloseCap       => wallet.closePendingCap(player, currency, key, r.rewardId).map(WalletAnswer.CapClosed(_))
      case WalletCall.Switch(_)      => wallet.switch(player, currency, key, r.rewardId).map(WalletAnswer.Switched(_))
      case WalletCall.Credit(amount, reason, actor) =>
        wallet.providerCredit(player, currency, key, amount, r.rewardId, reason, actor).map(WalletAnswer.Credited(_))
      case WalletCall.LateCredit(_, amount, reason, actor) =>
        wallet.providerCredit(player, currency, key, amount, r.rewardId, reason, actor).map(WalletAnswer.Credited(_))

  extension [A](c: Called[A])
    private def map[B](f: A => B): Called[B] = c match
      case Called.Done(a)                => Called.Done(f(a))
      case Called.Refused(s, code, msg)  => Called.Refused(s, code, msg)
      case Called.Unreachable(why)       => Called.Unreachable(why)

object WalletStep
    extends Workflow.Companion[WalletStep, StepState](
      componentId = ComponentId("wallet-step"),
      stateSerializer = RewardsCodecs.serializer[StepState]("wallet-step-state")
    ):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[WalletStep])

  given Serializer[StepRequest] = RewardsCodecs.serializer[StepRequest]("wallet-step-request")
  given Serializer[StepState]   = RewardsCodecs.serializer[StepState]("wallet-step-reply")

  def create(context: WorkflowContext) = new WalletStep(context)

  /** The workflow's id: `<reward>/<step>` (research R5). */
  def id(rewardId: String, call: WalletCall): String = s"$rewardId/${call.step}"

  /**
   * Winnings' terms with no priority set: the open records' lowest priority less one, so they are the primary, spent
   * before every open record (research R5, `award_bonus/handler.go:140-191`); none when nothing is open or the wallet cannot be read.
   * A retry may read another priority: the wallet answers the key with its first award.
   */
  def below(wallet: Wallet, player: String, currency: Currency, t: AwardTerms): AwardTerms =
    if t.priority.isDefined then t
    else
      wallet.restrictedFunds(player, currency) match
        case Called.Done(funds) if funds.records.nonEmpty => t.copy(priority = Some(funds.records.map(_.priority).min - 1))
        case _                                            => t

  /** The refusals that, on a timer's step, mean the record is not open (FR-015). */
  val NotOpenCodes: Set[String] = Set(Wallet.NoActiveBonus, Wallet.NotPendingCap)

  /** What the wallet's reply comes to: an answer for the reward, or a failure to try again (pure). */
  def answer(r: StepRequest, called: Called[WalletAnswer]): Either[String, WalletAnswer] = called match
    case Called.Done(a)                                            => Right(a)
    case Called.Refused(_, code, _) if r.byTimer && NotOpenCodes(code) => Right(WalletAnswer.NotOpen(code))
    case Called.Refused(status, code, message)                     => Right(WalletAnswer.Refused(status, code, message))
    case Called.Unreachable(why)                                   => Left(why)

  val call    = step("call")(_.callStep)
  val deliver = step("deliver")(_.deliverStep)
  val recover = step("recover")(_.recoverStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
