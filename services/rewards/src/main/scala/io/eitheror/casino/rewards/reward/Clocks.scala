package io.eitheror.casino.rewards.reward

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rewards.{RewardsCodecs, RewardsRuntime}
import io.eitheror.casino.rewards.reward.RewardEvent.*

import java.time.{Duration, Instant}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

/** A reward's timers (research R10), each named `<clock>-<reward>`. */
enum Clock(val prefix: String):
  case Pending  extends Clock("pending")
  case Claim    extends Clock("claim")
  case Wagering extends Clock("wagering")
  case Cap      extends Clock("cap")
  case Rounds   extends Clock("rounds")

  def name(rewardId: String): String = s"$prefix-$rewardId"

object Clock:
  given JsonValueCodec[Clock] = RewardsCodecs.wire(values, _.prefix)

/** What an event does to a reward's timers: set one at a time (replacing it by name), or cancel one. */
enum TimerChange:
  case Set(clock: Clock, at: Instant)
  case Cancel(clock: Clock)

/** How a timer's wallet step ends the reward, on the wallet's answer and on `NO_ACTIVE_BONUS` or `NOT_PENDING_CAP` alike (FR-015). */
enum Ending:
  case Expire(reason: ExpireReason)
  case Close(reason: CloseReason)

object Ending:
  given JsonValueCodec[Ending] = RewardsCodecs.stored[Ending]

/** What a due timer does to the reward as it stands. */
enum Fire:
  /** The reward left the state the timer watched, or holds another deadline: a no-op (research R10). */
  case Idle
  /** No wallet command: the pending selection's expiry. */
  case Expire(reason: ExpireReason)
  /** A wallet step (research R5's `forfeit` or `close-cap`), then the ending. */
  case Wallet(step: String, ending: Ending)
  /** The free rounds' expiry: `Reward.finalise`, then the payment it answers. */
  case Finalise

/** What a timer carries: the reward, the clock and the deadline it is for, so a stale fire is told apart. */
final case class Due(rewardId: String, clock: Clock, deadline: Instant)

object Due:
  given Serializer[Due] = RewardsCodecs.serializer[Due]("reward-due")

/**
 * The clocks of research R10, pure: which timers each event sets and cancels, and what a due timer does. Every
 * timer is a no-op on a terminal reward, and on a reward whose deadline for it moved.
 */
object Clocks:
  private val all = Clock.values.toVector.map(TimerChange.Cancel(_))

  /** `freeRounds`: whether the reward is free rounds, which an activation's timer depends on. */
  def changes(event: RewardEvent, freeRounds: Boolean): Vector[TimerChange] = event match
    case Selected(_, expiresAt) => Vector(TimerChange.Set(Clock.Pending, expiresAt))
    case Deselected(_)          => Vector(TimerChange.Cancel(Clock.Pending))
    case Activated(_, expiresAt, wagering, _) =>
      Vector(TimerChange.Cancel(Clock.Pending), TimerChange.Set(if freeRounds then Clock.Rounds else Clock.Claim, expiresAt)) ++
        wagering.filter(w => !freeRounds && w != expiresAt).map(TimerChange.Set(Clock.Wagering, _))
    case Converted(_, cap) =>
      Vector(TimerChange.Cancel(Clock.Claim), TimerChange.Cancel(Clock.Wagering), TimerChange.Set(Clock.Cap, cap))
    case _: RewardEvent.Forfeited | _: RewardEvent.Closed | _: RewardEvent.Expired | _: RewardEvent.Cancelled |
        _: RewardEvent.Revoked => all
    case _: FreeRoundsFinalised => Vector(TimerChange.Cancel(Clock.Rounds))
    case _                      => Vector.empty

  /**
   * A timer due at `deadline`. The claim deadline wins over the wagering window when both are due
   * (`expire_reward_grant/handler.go:111-121`).
   */
  def onDue(s: RewardState, clock: Clock, deadline: Instant): Fire =
    import RewardStatus.*
    clock match
      case Clock.Pending if s.status == Pending && s.expiresAt.contains(deadline) => Fire.Expire(ExpireReason.PendingExpired)
      case Clock.Claim if s.status == Active && !s.isFreeRounds && s.expiresAt.contains(deadline) =>
        Fire.Wallet(Steps.Forfeit, Ending.Expire(ExpireReason.ClaimExpired))
      case Clock.Wagering if s.status == Active && s.wageringExpiresAt.contains(deadline) =>
        val reason =
          if s.expiresAt.exists(!_.isAfter(deadline)) then ExpireReason.ClaimExpired else ExpireReason.WageringWindowExpired
        Fire.Wallet(Steps.Forfeit, Ending.Expire(reason))
      case Clock.Cap if s.status == ConvertedPendingCap && s.capExpiresAt.contains(deadline) =>
        Fire.Wallet(Steps.CloseCap, Ending.Close(CloseReason.CapExpired))
      case Clock.Rounds if s.status == Active && s.isFreeRounds && s.expiresAt.contains(deadline) => Fire.Finalise
      case _ => Fire.Idle

  /** The time until `at` by the service's clock; a deadline already past is due at once. */
  def in(at: Instant): FiniteDuration =
    Duration.between(RewardsRuntime.now(), at).toScala.max(FiniteDuration(0, "s"))

/** Research R5's step names for the timers' commands. */
object Steps:
  val Forfeit  = "forfeit"
  val CloseCap = "close-cap"

/**
 * Where a timer, or anything finalising free rounds, starts a wallet command: `WalletStep` (research R5), wired by
 * `RewardsService` once it exists (task T027). Until then a timer that needs the wallet fails, so it runs again
 * rather than being lost.
 */
trait WalletSteps:
  /** A timer's step, `<reward>/<step>`, ending the reward with `ending` on the wallet's answer. */
  def timed(client: ComponentClient, rewardId: String, step: String, ending: Ending): Unit
  /** A free-rounds payment under its own step and key (`Payment.step`, `Payment.key`). */
  def pay(client: ComponentClient, rewardId: String, payment: Payment): Unit

object WalletSteps:
  val unwired: WalletSteps = new WalletSteps:
    private def fail(step: String) = throw IllegalStateException(s"no wallet step is wired for '$step' (WalletStep, task T027)")
    def timed(client: ComponentClient, rewardId: String, step: String, ending: Ending): Unit = fail(step)
    def pay(client: ComponentClient, rewardId: String, payment: Payment): Unit              = fail(payment.step)

  @volatile var current: WalletSteps = unwired

/**
 * Keeps a reward's timers at the deadlines its events set (research R10): a consumer of `Reward`'s events, as kyc's
 * record timers, so a timer is set whatever path moved the reward. A redelivery replaces a timer by name; a stale
 * timer is told apart when it fires.
 */
final class RewardClocks(client: ComponentClient) extends Consumer[RewardEvent, Nothing]:
  def onMessage(event: RewardEvent): Effect =
    val rewardId = messageContext.subject
    val freeRounds = event match
      case _: Activated => client.forEventSourcedEntity(EntityId(rewardId)).call(Reward.get).invoke().isFreeRounds
      case _            => false
    val changes = Clocks.changes(event, freeRounds)
    if changes.isEmpty then effects.ignore()
    else
      val timers = RewardsRuntime.timers()
      changes.foreach {
        case TimerChange.Set(clock, at) =>
          timers.createSingleTimer(clock.name(rewardId), Clocks.in(at), ClockTimer.fire.deferred(Due(rewardId, clock, at)))
        case TimerChange.Cancel(clock) => timers.delete(clock.name(rewardId))
      }
      effects.done()

object RewardClocks
    extends Consumer.Companion[RewardClocks, RewardEvent, Nothing](ComponentId("reward-clocks"), ChangeSource.eventsOf(Reward)):
  def create(ctx: ConsumerContext) = new RewardClocks(ctx.componentClient)

/** A reward's timer due: what it does is `Clocks.onDue` over the reward as it stands. */
final class ClockTimer(context: TimedActionContext) extends TimedAction:
  def due(d: Due): Effect =
    val client = context.componentClient
    val reward = client.forEventSourcedEntity(EntityId(d.rewardId))
    Clocks.onDue(reward.call(Reward.get).invoke(), d.clock, d.deadline) match
      case Fire.Idle        => ()
      case Fire.Expire(reason) => reward.call(Reward.expire).invoke(Expire(reason, RewardsRuntime.now(), Some(d.deadline))): Unit
      case Fire.Wallet(step, ending) => WalletSteps.current.timed(client, d.rewardId, step, ending)
      case Fire.Finalise =>
        reward.call(Reward.finalise).invoke(Finalise(RewardsRuntime.now())).payment
          .foreach(WalletSteps.current.pay(client, d.rewardId, _))
    effects.done()

object ClockTimer extends TimedAction.Companion[ClockTimer](ComponentId("reward-clock")):
  def create(context: TimedActionContext) = new ClockTimer(context)
  val fire = handler("due")(_.due)
