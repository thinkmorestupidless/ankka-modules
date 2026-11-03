package io.eitheror.casino.rewards.steps

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.clients.{AwardTerms, Awarded}
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward
import io.eitheror.casino.rewards.reward.*

import java.time.Instant

/** What a step's answer does to its reward: a `Reward` command, or nothing. */
enum Landing:
  case Activate(command: reward.Activate)
  case RefuseAward(command: reward.RefuseAward)
  case Expire(command: reward.Expire)
  case Close(command: reward.Close)
  case Cancel(command: reward.Cancel)
  case Switch(command: reward.Switch)
  /** Nothing to move: a payment's answer, a step a later story moves, or a refusal the caller reads from the step. */
  case Idle(why: String)

/**
 * The wiring of research R5 between `WalletStep` and `Reward`, installed by `RewardsService.started` and the test kit:
 * where a step's answer goes (`RewardsRuntime.delivery`), and how a timer or a finalisation starts a step
 * (`WalletSteps.current`).
 *
 * An award the wallet made for a reward that ended while the award was in flight (the pending selection expired, the
 * player cancelled) leaves an open record at the wallet the reward no longer stands for: `activate` is refused
 * `INVALID_TRANSITION`, and the delivery starts the reward's `cancel` step, which closes the record at the wallet and
 * whose own answer moves nothing, the reward being terminal already.
 */
object Wiring:
  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.rewards.steps.Wiring")

  def install(): Unit =
    RewardsRuntime.delivery = client => Deliver(client)
    WalletSteps.current = Timed

  /** What the answer to `request` does to the reward (pure). */
  def move(request: StepRequest, answer: WalletAnswer, at: Instant): Landing =
    import WalletAnswer as A
    (request.call, answer) match
      case (WalletCall.Award(t), A.Awarded(a))    => activation(request, t, a, at)
      case (WalletCall.Winnings(t), A.Awarded(a)) => activation(request, t, a, at)
      case (_: WalletCall.Award | _: WalletCall.Winnings, A.Refused(_, code, _)) => Landing.RefuseAward(reward.RefuseAward(code, at))
      // A timer's step: the reward ends as the timer decided, whether the wallet closed the record or it was not open.
      case (WalletCall.Forfeit | WalletCall.CloseCap, A.Forfeited(_) | A.CapClosed(_) | A.NotOpen(_)) =>
        request.ending match
          case Some(Ending.Expire(reason)) => Landing.Expire(reward.Expire(reason, at))
          case Some(Ending.Close(reason))  => Landing.Close(reward.Close(reason, at))
          case None if request.call == WalletCall.CloseCap => Landing.Close(reward.Close(CloseReason.CapExpired, at))
          case None => Landing.Idle("forfeit_not_by_timer")
      case (WalletCall.Cancel(false), A.Cancelled(c)) =>
        Landing.Cancel(reward.Cancel(CancelReason.PlayerCancel, c.clawbackAmount, at))
      case (_: WalletCall.Switch, A.Switched(s)) =>
        Landing.Switch(reward.Switch(switchNumber(request.call), s.priority, at))
      // The revoke route records the revoke, with the operator's reason, which no step holds (`revoked`).
      case (_: WalletCall.Revoke, _)  => Landing.Idle("revoke_recorded_by_route")
      case (_, A.Refused(_, code, _)) => Landing.Idle(s"refused_$code")
      case _                          => Landing.Idle("nothing_to_move")

  /**
   * What an operator's revoke step answered (FR-017): the revoke to record, with the clawback the wallet applied and the
   * real balance after it; or the wallet's refusal code. The reason is the route's, held by the `Revoked` event alone
   * (research R12), so it is never part of the step: the route records the revoke once the step answers, and a revoke
   * asked again after the request bound answers from the step and records it then.
   */
  def revoked(mode: RevokeMode, by: String, reason: Option[Personal[String]], answer: WalletAnswer, at: Instant)
      : Either[String, (reward.Revoke, Long)] =
    answer match
      case WalletAnswer.Forfeited(f) => Right(reward.Revoke(mode, by, reason, 0L, at) -> f.balances.realBalance)
      case WalletAnswer.Cancelled(c) => Right(reward.Revoke(mode, by, reason, c.clawbackAmount, at) -> c.balances.realBalance)
      case WalletAnswer.Refused(_, code, _) => Left(code)
      case WalletAnswer.NotOpen(code)       => Left(code)
      case other                            => Left(s"unexpected_${other.getClass.getSimpleName}")

  private def activation(request: StepRequest, t: AwardTerms, a: Awarded, at: Instant): Landing =
    Landing.Activate(reward.Activate(Some(WalletAward(a.recordId, a.wageringRequirement, Some(t.amount), Some(t.wageringMultiplier),
      Some(t.maxWin), Some(request.currency))), at))

  private def switchNumber(call: WalletCall): Int = call match
    case WalletCall.Switch(n) => n
    case _                    => 0

  /** The delivery to `Reward`: one command per answer, each repeatable (a move to the state it is in writes nothing). */
  final class Deliver(client: ComponentClient) extends Delivery:
    def deliver(rewardId: String, request: StepRequest, answer: WalletAnswer): Unit =
      val entity = client.forEventSourcedEntity(EntityId(rewardId))
      val result = move(request, answer, RewardsRuntime.now()) match
        case Landing.Activate(c)    => Some(entity.call(Reward.activate).invoke(c))
        case Landing.RefuseAward(c) => Some(entity.call(Reward.refuseAward).invoke(c))
        case Landing.Expire(c)      => Some(entity.call(Reward.expire).invoke(c))
        case Landing.Close(c)       => Some(entity.call(Reward.close).invoke(c))
        case Landing.Cancel(c)      => Some(entity.call(Reward.cancel).invoke(c))
        case Landing.Switch(c)      => Some(entity.call(Reward.switch).invoke(c))
        case Landing.Idle(why) =>
          log.debug(s"wallet step ${request.id}: nothing to move ($why)")
          None
      result.flatMap(a => a.refusal.map(a.state -> _)).foreach { (state, refused) =>
        if request.call.isInstanceOf[WalletCall.Award] && state.status.isTerminal then
          log.warn(s"wallet step ${request.id}: awarded after the reward ended ${state.status.wire}; cancelling the record")
          start(client, request.copy(call = WalletCall.Cancel(), byTimer = false, ending = None))
        else log.warn(s"wallet step ${request.id}: the reward refused the answer: ${refused.code}")
      }

  private def start(client: ComponentClient, r: StepRequest): Unit =
    client.forWorkflow(EntityId(r.id)).call(WalletStep.start).invoke(r): Unit

  /** A timer's step and a finalisation's payment, from the reward as it stands. */
  object Timed extends WalletSteps:
    def timed(client: ComponentClient, rewardId: String, step: String, ending: Ending): Unit =
      val s = client.forEventSourcedEntity(EntityId(rewardId)).call(Reward.get).invoke()
      val call = step match
        case Steps.Forfeit  => WalletCall.Forfeit
        case Steps.CloseCap => WalletCall.CloseCap
        case other          => throw IllegalArgumentException(s"no timer's wallet step is named '$other'")
      start(client, StepRequest(rewardId, s.playerId, s.currency, call, byTimer = true, ending = Some(ending)))

    def pay(client: ComponentClient, rewardId: String, payment: Payment): Unit =
      val s   = client.forEventSourcedEntity(EntityId(rewardId)).call(Reward.get).invoke()
      val now = RewardsRuntime.now()
      (payment.destination, payment.lateRound) match
        case (Destination.RealBalance, None) =>
          start(client, StepRequest(rewardId, s.playerId, s.currency, WalletCall.Credit(payment.amount, CreditReason, CreditActor)))
        case (Destination.RealBalance, Some(r)) =>
          start(client, StepRequest(rewardId, s.playerId, s.currency, WalletCall.LateCredit(r, payment.amount, CreditReason, CreditActor)))
        case (Destination.RestrictedFunds, _) =>
          // A reward of their own, as the original's `award_bonus` grant: its claim deadline, cap, cancel and switch are any reward's.
          val own = payment.key(rewardId)
          client.forEventSourcedEntity(EntityId(own)).call(Reward.open).invoke(Open(winningsOpening(s, payment.amount), RewardStatus.Available, now)): Unit
          start(client, StepRequest(own, s.playerId, s.currency, WalletCall.Winnings(winnings(s, payment.amount, now))))

  /**
   * The winnings' own reward, opened from the free rounds' (`award_bonus/handler.go:210-238`): the same offer, bonus
   * funds of the amount won at the offer's multiplier and maximum convertible, given by the system.
   */
  def winningsOpening(s: RewardState, amount: Long): Opening =
    Opening(s.playerId, s.offerId, s.offerName, s.offerType, PayloadType.BonusFunds,
      Decision(amount, amount * s.wageringMultiplier, s.maxConvertible, s.wageringMultiplier, Currency.unsafe(s.currency)),
      source = Source.System, snapshot = s.snapshot)

  /** The provider credit's reason and actor, as the original's `game_provider_credit`. */
  val CreditReason = "free_spins_winnings"
  val CreditActor  = "rewards"

  /**
   * Winnings as restricted funds: the offer's multiplier and maximum convertible, expiring with the reward's claim
   * deadline, the offer's wagering window or 30 days (research R5). The priority, below every open record's, is the
   * step's to read from the wallet when it asks (`WalletStep.below`).
   */
  def winnings(s: RewardState, amount: Long, at: Instant): AwardTerms =
    AwardTerms(bonusRef = s.offerId, amount = amount, wageringMultiplier = s.wageringMultiplier, maxWin = s.maxConvertible,
      priority = None, expiresAt = Some(Reward.activeDeadline(s.snapshot, freeRounds = false, at)),
      rules = Awards.rules(s.snapshot.betRules), maxConcurrentGrants = s.snapshot.maxConcurrentGrants)
