package io.eitheror.casino.rewards.steps

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.rewards.clients.{AwardRules, AwardTerms}
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.{BetRules, Offer}
import io.eitheror.casino.rewards.player.Claim
import io.eitheror.casino.rewards.reward.{Open, Opening, Reward, RewardState, RewardStatus, Windows}

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
   */
  def start(client: ComponentClient, offer: Offer, playerId: String, claim: Claim, at: Instant): StepRequest =
    val opening = Opening.of(offer, playerId, claim.decision, claim.source, claim.code, claim.tag)
    val reward  = client.forEventSourcedEntity(EntityId(claim.rewardId)).call(Reward.open).invoke(Open(opening, RewardStatus.Available, at))
    val r       = request(reward.state, claim, at)
    client.forWorkflow(EntityId(r.id)).call(WalletStep.start).invoke(r): Unit
    r
