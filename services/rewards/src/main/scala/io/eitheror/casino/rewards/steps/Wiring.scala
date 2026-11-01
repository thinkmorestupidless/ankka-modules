package io.eitheror.casino.rewards.steps

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.reward
import io.eitheror.casino.rewards.reward.*

import java.time.Instant
import java.time.temporal.ChronoUnit

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
      case (WalletCall.Award(t), A.Awarded(a)) =>
        Landing.Activate(reward.Activate(Some(WalletAward(a.recordId, a.wageringRequirement, Some(t.amount), Some(t.wageringMultiplier),
          Some(t.maxWin), Some(request.currency))), at))
      case (_: WalletCall.Award, A.Refused(_, code, _)) => Landing.RefuseAward(reward.RefuseAward(code, at))
      // A timer's step: the reward ends as the timer decided, whether the wallet closed the record or it was not open.
      case (WalletCall.Forfeit | WalletCall.CloseCap, A.Forfeited(_) | A.CapClosed(_) | A.NotOpen(_)) =>
        request.ending match
          case Some(Ending.Expire(reason)) => Landing.Expire(reward.Expire(reason, at))
          case Some(Ending.Close(reason))  => Landing.Close(reward.Close(reason, at))
          case None if request.call == WalletCall.CloseCap => Landing.Close(reward.Close(CloseReason.CapExpired, at))
          // An operator's revoke in `forfeit` mode is the revoke route's to record (task T061).
          case None => Landing.Idle("forfeit_not_by_timer")
      case (WalletCall.Cancel(false), A.Cancelled(c)) =>
        Landing.Cancel(reward.Cancel(CancelReason.PlayerCancel, c.clawbackAmount, at))
      case (_: WalletCall.Switch, A.Switched(s)) =>
        Landing.Switch(reward.Switch(switchNumber(request.call), s.priority, at))
      case (_, A.Refused(_, code, _)) => Landing.Idle(s"refused_$code")
      case _                          => Landing.Idle("nothing_to_move")

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
      val call = (payment.destination, payment.lateRound) match
        case (Destination.RealBalance, None)    => WalletCall.Credit(payment.amount, CreditReason, CreditActor)
        case (Destination.RealBalance, Some(r)) => WalletCall.LateCredit(r, payment.amount, CreditReason, CreditActor)
        case (Destination.RestrictedFunds, round) =>
          val terms = winnings(s, payment.amount, now)
          round.fold[WalletCall](WalletCall.Winnings(terms))(WalletCall.LateAward(_, terms))
      start(client, StepRequest(rewardId, s.playerId, s.currency, call))

  /** The provider credit's reason and actor, as the original's `game_provider_credit`. */
  val CreditReason = "free_spins_winnings"
  val CreditActor  = "rewards"

  /**
   * Winnings as restricted funds: the offer's multiplier and maximum convertible, a 30-day expiry (research R5). The
   * priority, below every open record, is the step's to read from the wallet when it asks (`WalletStep.below`).
   */
  def winnings(s: RewardState, amount: Long, at: Instant): AwardTerms =
    AwardTerms(bonusRef = s.offerId, amount = amount, wageringMultiplier = s.wageringMultiplier, maxWin = s.maxConvertible,
      priority = None, expiresAt = Some(at.plus(Windows.WageringDays.toLong, ChronoUnit.DAYS)),
      rules = Awards.rules(s.snapshot.betRules), maxConcurrentGrants = s.snapshot.maxConcurrentGrants)
