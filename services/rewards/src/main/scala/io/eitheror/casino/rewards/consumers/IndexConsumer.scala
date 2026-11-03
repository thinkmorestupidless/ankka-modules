package io.eitheror.casino.rewards.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.player.{PlayerRewards, RewardChanged}
import io.eitheror.casino.rewards.reward.{CancelReason, Reward, RewardEvent, RewardState}

import java.time.Instant

/**
 * Each reward's state carried back to its player's record (research R3, the original's `HAS_REWARD_GRANT` edge), so the
 * evaluator counts it: its status as it stands, whether the provider failed it (not a claim, FR-023), and whether the
 * wallet refused its award (the claim ends). The record writes nothing for a report that changes nothing, so a
 * redelivery is harmless.
 */
final class IndexConsumer(client: ComponentClient) extends Consumer[RewardEvent, Nothing]:
  def onMessage(event: RewardEvent): Effect =
    val rewardId = messageContext.subject
    val state    = client.forEventSourcedEntity(EntityId(rewardId)).call(Reward.get).invoke()
    IndexConsumer.report(state, event, RewardsRuntime.now()) match
      case None => effects.ignore()
      case Some(c) =>
        client.forEventSourcedEntity(EntityId(state.playerId)).call(PlayerRewards.rewardChanged).invoke(c): Unit
        effects.done()

object IndexConsumer
    extends Consumer.Companion[IndexConsumer, RewardEvent, Nothing](ComponentId("rewards-index-consumer"), ChangeSource.eventsOf(Reward)):
  def create(ctx: ConsumerContext) = new IndexConsumer(ctx.componentClient)

  /** The report for the reward as it stands after `event`; none for a reward not opened. */
  def report(s: RewardState, event: RewardEvent, at: Instant): Option[RewardChanged] =
    current(s, at).map(_.copy(awardRefused = event.isInstanceOf[RewardEvent.AwardRefused]))

  /**
   * The report for the reward as it stands, which a path that opens a reward outside a claim sends at once, ahead of
   * this consumer, so the player's record holds it before the view does: the same report, written once.
   */
  def current(s: RewardState, at: Instant): Option[RewardChanged] =
    Option.when(s.opened)(RewardChanged(s.rewardId, s.offerId, s.status,
      providerFailed = s.terminationReason.contains(CancelReason.ProviderFailed.wire), awardRefused = false, at))
