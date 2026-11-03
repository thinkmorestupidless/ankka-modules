package io.eitheror.casino.rewards.steps

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.{Amounts as MoneyAmounts, Currency}
import io.eitheror.casino.rewards.{RewardsCodecs, RewardsRuntime}
import io.eitheror.casino.rewards.clients.{BonusStatus, Called, FreeRoundsGrant}
import io.eitheror.casino.rewards.reward.{Cancel, CancelReason, ProviderStatus, Reward, RewardStatus}

import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

/**
 * What `FreeRounds` is started with: the reward (the provider's grant id), its player and currency, and the rounds
 * as granted: the game, the count, the spin value in minor units, and the expiry in days (the bonus's duration).
 */
final case class FreeRoundsRequest(rewardId: String, playerId: String, currency: String, gameId: String, count: Int, spinValue: Long,
    expiryDays: Int):
  /** Research R8's `create` body: the grant id is the reward id, the duration the expiry in seconds. */
  def grant: FreeRoundsGrant =
    val c = Currency.unsafe(currency)
    FreeRoundsGrant(rewardId, playerId, Vector(gameId), count, MoneyAmounts.render(spinValue, c), c, expiryDays.toLong * 86400L)

/** An operator's revoke asks the provider to cancel the rounds (research R8's `cancel`). */
final case class CancelRounds(rewardId: String, playerId: String)

/**
 * The workflow's state (data-model.md `FreeRounds`): where it is (`phase`), the request, the create's answer once the
 * provider gave one (`created`: a create is never sent again after it), the provider's last word, the polls made, and
 * a revoke's cancel with what it came to (`cancelled`: the provider's status, or `unavailable`).
 */
final case class FreeRoundsFlow(
    phase: String = FreeRoundsFlow.New,
    request: Option[FreeRoundsRequest] = None,
    created: Option[String] = None,
    providerStatus: Option[String] = None,
    polls: Int = 0,
    cancel: Option[CancelRounds] = None,
    cancelled: Option[String] = None
):
  /** The create answered with rounds the player may play. */
  def playable: Boolean = created.exists(s => BonusStatus.from(s).playable)

object FreeRoundsFlow:
  val New        = "new"
  val Creating   = "creating"
  val Polling    = "polling"
  /** The provider confirmed the rounds, or has no configuration: nothing more to do until a revoke. */
  val Live       = "live"
  /** The provider refused or never confirmed: the reward is `CANCELLED provider_failed`. */
  val Failed     = "failed"
  /** The reward left `ACTIVE` while the provider was being polled: the poll ends silently. */
  val Ended      = "ended"
  val Cancelling = "cancelling"
  val Cancelled  = "cancelled"

  /** A cancel the provider could not be reached for, or answered with its error: the revoke is refused. */
  val Unavailable = "unavailable"
  /** What the reward records when the provider never answered the create, as the original's `failed`. */
  val FailedStatus = "failed"

/**
 * Free rounds at the game provider (research R8), id = the reward id. `known` makes the player known (a failure is
 * logged and the create goes ahead); `create` asks for the rounds under the reward id with the expiry as their
 * duration; a create answered `processing` is polled every `pollEvery` (30 s) at most `pollLimit` (50) times; a
 * refusal, the provider's failure, or the last poll cancels the reward `provider_failed`, which moves no money. The
 * provider's answers are recorded on `Reward`. A create is sent once: the simulator replaces a bonus under the same
 * id, so once the provider answered it is never sent again, and a start of a workflow already started answers its
 * state. `cancel` (an operator's revoke) redirects the workflow, whatever it is doing, to the provider's cancel.
 */
final class FreeRounds(context: WorkflowContext) extends Workflow[FreeRoundsFlow]:
  import FreeRoundsFlow.*

  private val log = org.slf4j.LoggerFactory.getLogger(classOf[FreeRounds])

  private def provider = RewardsRuntime.gameProvider(context.services)
  private def reward   = context.componentClient.forEventSourcedEntity(EntityId(context.workflowId.toString))

  def emptyState: FreeRoundsFlow = FreeRoundsFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      // A step throws only when the reward cannot be reached; the provider's answers are values. Each is run again.
      .stepRecovery(FreeRounds.known, RecoverStrategy.maxRetries(5))
      .stepRecovery(FreeRounds.create, RecoverStrategy.maxRetries(5))
      .stepRecovery(FreeRounds.poll, RecoverStrategy.maxRetries(5))
      .stepRecovery(FreeRounds.cancelStep, RecoverStrategy.maxRetries(5))
      .build

  // ── Commands ─────────────────────────────────────────────────────────────────────────────────

  def start(r: FreeRoundsRequest): Effect[FreeRoundsFlow] =
    if currentState.request.isDefined then effects.reply(currentState)
    else if r.rewardId != context.workflowId.toString then
      effects.error(s"the free rounds of ${r.rewardId} are not ${context.workflowId}", ErrorCode.BadRequest)
    else
      val next = FreeRoundsFlow(Creating, Some(r))
      effects.updateState(next).transitionTo(FreeRounds.known.ref).thenReply(next)

  /**
   * An operator's revoke (research R8 `cancel`): the provider's cancel, whatever the workflow was doing; a cancel the
   * provider answered answers again, and one it could not be reached for may be asked again.
   */
  def cancel(c: CancelRounds): Effect[FreeRoundsFlow] =
    if c.rewardId != context.workflowId.toString then
      effects.error(s"the free rounds of ${c.rewardId} are not ${context.workflowId}", ErrorCode.BadRequest)
    else if currentState.cancelled.exists(_ != Unavailable) || (currentState.phase == Cancelling && currentState.cancelled.isEmpty) then
      effects.reply(currentState)
    else
      val next = currentState.copy(phase = Cancelling, cancel = Some(c), cancelled = None)
      effects.updateState(next).transitionTo(FreeRounds.cancelStep.ref).thenReply(next)

  def status: ReadOnlyEffect[FreeRoundsFlow] = effects.reply(currentState)

  // ── Steps ────────────────────────────────────────────────────────────────────────────────────

  def knownStep: StepEffect =
    val r = currentState.request.get
    provider.known(r.playerId, Currency.unsafe(r.currency)) match
      case Called.Done(_) => ()
      case other          => log.warn(s"free rounds ${r.rewardId}: the player could not be made known; creating all the same (${FreeRounds.why(other)})")
    stepEffects.thenTransitionTo(FreeRounds.create.ref)

  def createStep: StepEffect =
    val r = currentState.request.get
    currentState.created match
      // Answered already: never sent again (research R8).
      case Some(status) => after(BonusStatus.from(status), currentState)
      case None =>
        val answered = provider.create(r.grant) match
          case Called.Done(status) => status
          case other =>
            log.warn(s"free rounds ${r.rewardId}: the provider's create was not answered (${FreeRounds.why(other)}); marking it failed")
            BonusStatus.Failed
        val wire = answered.wire
        reward.call(Reward.providerStatus).invoke(ProviderStatus(wire, RewardsRuntime.now())): Unit
        after(answered, currentState.copy(created = Some(wire), providerStatus = Some(wire)))

  /** What follows the create's answer. */
  private def after(status: BonusStatus, s: FreeRoundsFlow): StepEffect = status match
    case BonusStatus.Processing => polling(s)
    case st if st.playable      => stepEffects.updateState(s.copy(phase = Live)).thenEnd
    case _                      => failed(s)

  def pollStep: StepEffect =
    val r = currentState.request.get
    val standing = reward.call(Reward.get).invoke()
    if standing.status != RewardStatus.Active then stepEffects.updateState(currentState.copy(phase = Ended)).thenEnd
    else
      val polls = currentState.polls + 1
      provider.status(r.rewardId) match
        case Called.Done(BonusStatus.Active) =>
          reward.call(Reward.providerStatus).invoke(ProviderStatus(BonusStatus.Active.wire, RewardsRuntime.now())): Unit
          stepEffects.updateState(currentState.copy(phase = Live, polls = polls, providerStatus = Some(BonusStatus.Active.wire))).thenEnd
        case Called.Done(st) if !st.playable =>
          log.warn(s"free rounds ${r.rewardId}: the provider answered ${st.wire} after $polls polls; cancelling them")
          failed(currentState.copy(polls = polls, providerStatus = Some(st.wire)))
        case other =>
          // Still processing, or not answered: a poll all the same (research R8's limit counts polls, not answers).
          other match
            case Called.Done(_) => ()
            case _              => log.warn(s"free rounds ${r.rewardId}: poll $polls not answered (${FreeRounds.why(other)})")
          val next = currentState.copy(polls = polls)
          if polls >= RewardsRuntime.settings.pollLimit then
            log.warn(s"free rounds ${r.rewardId}: not confirmed after $polls polls; cancelling them")
            failed(next.copy(providerStatus = Some(FailedStatus)))
          else polling(next)

  def cancelStep: StepEffect =
    val c = currentState.cancel.get
    val answer = provider.cancel(c.rewardId, c.playerId) match
      case Called.Done(BonusStatus.Error) | Called.Unreachable(_) => Unavailable
      case Called.Done(status)                                    => status.wire
      case Called.Refused(_, code, _)                             => code
    stepEffects.updateState(currentState.copy(phase = Cancelled, cancelled = Some(answer))).thenEnd

  private def polling(s: FreeRoundsFlow): StepEffect =
    stepEffects.updateState(s.copy(phase = Polling)).thenPause(RewardsRuntime.settings.pollEvery.toScala, FreeRounds.poll.ref)

  /** The provider failed the rounds: the reward is cancelled `provider_failed`, no wallet command (SC-005). */
  private def failed(s: FreeRoundsFlow): StepEffect =
    val now = RewardsRuntime.now()
    s.providerStatus.filterNot(s.created.contains).foreach(st => reward.call(Reward.providerStatus).invoke(ProviderStatus(st, now)): Unit)
    reward.call(Reward.cancel).invoke(Cancel(CancelReason.ProviderFailed, 0L, now)): Unit
    stepEffects.updateState(s.copy(phase = Failed)).thenEnd

object FreeRounds
    extends Workflow.Companion[FreeRounds, FreeRoundsFlow](
      componentId = ComponentId("free-rounds"),
      stateSerializer = RewardsCodecs.serializer[FreeRoundsFlow]("free-rounds-state")
    ):
  given Serializer[FreeRoundsRequest] = RewardsCodecs.serializer[FreeRoundsRequest]("free-rounds-request")
  given Serializer[CancelRounds]      = RewardsCodecs.serializer[CancelRounds]("free-rounds-cancel")
  given Serializer[FreeRoundsFlow]    = RewardsCodecs.serializer[FreeRoundsFlow]("free-rounds-reply")

  def create(context: WorkflowContext) = new FreeRounds(context)

  private def why(c: Called[?]): String = c match
    case Called.Refused(status, code, _) => s"$status $code"
    case Called.Unreachable(reason)      => reason
    case Called.Done(_)                  => "answered"

  val known      = step("known")(_.knownStep)
  val create     = step("create")(_.createStep)
  val poll       = step("poll")(_.pollStep)
  val cancelStep = step("cancel")(_.cancelStep)

  val start  = command("start")(_.start)
  val cancel = command("cancel")(_.cancel)
  val status = query("status")(_.status)
