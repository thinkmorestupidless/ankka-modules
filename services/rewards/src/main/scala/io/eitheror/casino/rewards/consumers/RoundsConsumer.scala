package io.eitheror.casino.rewards.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{CasinoRounds, CasinoRoundsFact, RoundSettled}
import io.eitheror.casino.rewards.player.{PlayerRewards, RecordUnmatchedRound, UnmatchedRound}
import io.eitheror.casino.rewards.reward
import io.eitheror.casino.rewards.reward.{Reward, WalletSteps}

/**
 * `casino.rounds` into this service (data-model.md *Consumers*; FR-024): a `round_settled` naming free rounds
 * (`freeRoundsGrant`, the reward id) is counted on the reward, once per round, and the payment it answers (the
 * finalisation's, or a late round's) is started as its `WalletStep`, keyed, so a round delivered twice pays once. A
 * round naming a reward this service does not hold is recorded on the player's record as unmatched. Every other round
 * is not read.
 */
final class RoundsConsumer(client: ComponentClient) extends Consumer[Option[CasinoRoundsFact], Nothing]:
  def onMessage(fact: Option[CasinoRoundsFact]): Effect = fact match
    case None =>
      Read.unreadable(CasinoRounds.name, messageContext.subject)
      effects.ignore()
    case Some(f) =>
      RoundsConsumer.round(f) match
        case None => effects.ignore()
        case Some((rewardId, round, settled)) =>
          val r = client.forEventSourcedEntity(EntityId(rewardId))
          if !r.call(Reward.get).invoke().opened then
            client.forEventSourcedEntity(EntityId(settled.playerId)).call(PlayerRewards.roundUnmatched)
              .invoke(RecordUnmatchedRound(UnmatchedRound(round.roundId, rewardId, round.win, round.at))): Unit
          else r.call(Reward.roundSettled).invoke(round).payment.foreach(WalletSteps.current.pay(client, rewardId, _))
          effects.done()

object RoundsConsumer
    extends Consumer.Companion[RoundsConsumer, Option[CasinoRoundsFact], Nothing](ComponentId("rewards-rounds-consumer"),
      Read.from(CasinoRounds)):
  def create(ctx: ConsumerContext) = new RoundsConsumer(ctx.componentClient)

  /** The reward a settled round names and the round as the reward counts it; none for a round not of free rounds. */
  def round(fact: CasinoRoundsFact): Option[(String, reward.RoundSettled, RoundSettled)] = fact match
    case f: RoundSettled =>
      f.freeRoundsGrant.map(_.trim).filter(_.nonEmpty).map(g => (g, reward.RoundSettled(f.roundId, f.winCents.value, f.occurredAt), f))
