package io.eitheror.casino.rewards.steps

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.rewards.clients.{AwardRules, AwardTerms}
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.{BetRules, Offer}
import io.eitheror.casino.rewards.player.Claim
import io.eitheror.casino.rewards.reward.{Expire, ExpireReason, Open, Opening, Reward, RewardState, RewardStatus, Windows}

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The award path (FR-013, FR-018; research R5's `award` step): a claim reserved on `PlayerRewards` becomes
 * `WalletStep(<reward>/award)`. The reward is opened first when it is not yet (an award on another path), so the
 * wallet's answer always has a reward to land on. The award's terms are the claim's decision and the reward's rule
 * snapshot, copied from the offer when the reward opened: the bet-time rules, the offer's maximum of open rewards (W1),
 * its priority, and the expiry at the wagering window (30 days when the offer sets none).
 */
object Awards:
  /** The step's request for a claim on the reward as it stands. */
  def request(reward: RewardState, claim: Claim, at: Instant): StepRequest =
    StepRequest(claim.rewardId, reward.playerId, claim.decision.currency.code, WalletCall.Award(terms(reward, claim.decision, at)))

  def terms(reward: RewardState, d: Decision, at: Instant): AwardTerms =
    val snapshot = reward.snapshot
    AwardTerms(bonusRef = reward.offerId, amount = d.amount, wageringMultiplier = d.wageringMultiplier, maxWin = d.maxConvertible,
      priority = reward.priority, expiresAt = Some(at.plus(Windows.days(snapshot.wageringWindowDays, Windows.WageringDays), ChronoUnit.DAYS)),
      rules = rules(snapshot.betRules), maxConcurrentGrants = snapshot.maxConcurrentGrants)

  /** The offer's bet-time rules as the wallet's award names them (spec 002 `AwardRequest`); none left out. */
  def rules(r: BetRules): AwardRules =
    AwardRules(maxStake = r.maxStake, maxStakeEnforcement = r.maxStakeEnforcement.getOrElse(""), contributionDefault = r.contributionDefault,
      contributionOverrides = r.contributionOverrides, scopeInclude = r.scopeInclude, scopeExclude = r.scopeExclude,
      scopeViolationMode = r.scopeViolationMode.getOrElse(""), fundingOrder = r.fundingOrder.getOrElse(""),
      winAllocation = r.winAllocation.getOrElse(""), forfeitOnWithdrawal = r.forfeitOnWithdrawal.getOrElse(""))

  /**
   * Opens the reward when it is not (`Open` is a no-op on a reward already opened) and starts its award step; a step
   * already started answers its first state, so a claim answered again starts nothing new.
   *
   * A claim replacing a reward the wallet refused (`Claim.replaces`) opens its fresh reward as the refused one stood,
   * pending or available, and retires the refused one (`EXPIRED`, `superseded`; nothing is published for a reward that
   * was never active, research R11), so the player's rewards list it once.
   */
  def start(client: ComponentClient, offer: Offer, playerId: String, claim: Claim, at: Instant): StepRequest =
    startOpening(client, Opening.of(offer, playerId, claim.decision, claim.source, claim.code, claim.tag), claim, at)

  /**
   * As `start`, with the reward's opening given: an operator's award names the operator (`by`, for the audit's
   * `rewards.awarded`), and a free bonus with no offer its type `MANUAL` (FR-017).
   */
  def startOpening(client: ComponentClient, opening: Opening, claim: Claim, at: Instant): StepRequest =
    val replaced = claim.replaces.map(id => client.forEventSourcedEntity(EntityId(id)))
    val initial  = replaced.map(_.call(Reward.get).invoke().status).filter(_ == RewardStatus.Pending).getOrElse(RewardStatus.Available)
    val reward   = client.forEventSourcedEntity(EntityId(claim.rewardId)).call(Reward.open).invoke(Open(opening, initial, at))
    replaced.foreach(_.call(Reward.expire).invoke(Expire(ExpireReason.Superseded, at)): Unit)
    val r = request(reward.state, claim, at)
    client.forWorkflow(EntityId(r.id)).call(WalletStep.start).invoke(r): Unit
    r
