package io.eitheror.casino.rewards.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{BonusCodeEntered, CasinoRewards, CasinoRewardsFact}
import io.eitheror.casino.rewards.player.{PlayerEvent, PlayerRewards}

/**
 * `PlayerRewards`' code entries as `bonus_code_entered` on `casino.rewards` (contracts/facts.md; research R11): the code
 * as entered and its outcome, keyed by the player, `ce-id` `<player>/<seq>`. Nothing else of the record is published.
 */
final class CodesPublisher extends Consumer[PlayerEvent, CasinoRewardsFact]:
  def onMessage(event: PlayerEvent): Effect =
    CodesPublisher.fact(messageContext.subject, event) match
      case None => effects.ignore()
      case Some(fact) =>
        val id = s"${messageContext.subject}/${messageContext.sequenceNumber}"
        effects.produceAll(Seq(effects.message(fact).withKey(fact.key).withMetadata(Metadata.empty.set("ce-id", id))))

object CodesPublisher extends Consumer.Companion[CodesPublisher, PlayerEvent, CasinoRewardsFact](
      ComponentId("rewards-codes-publisher"), ChangeSource.eventsOf(PlayerRewards)):
  override val outputSerializer: Option[Serializer[CasinoRewardsFact]] = Some(CasinoRewards.serializer)
  override val produceTo: Option[String]                               = Some(CasinoRewards.name)
  override val produces: Option[Publication] = Some(Publication(CasinoRewards.name, Some(CasinoRewards.contract), None))
  def create(ctx: ConsumerContext) = new CodesPublisher

  /** The fact a player's event is: a code entry only. */
  def fact(playerId: String, event: PlayerEvent): Option[CasinoRewardsFact] = event match
    case PlayerEvent.CodeEntered(code, outcome, at) => Some(BonusCodeEntered(playerId, code, at, Some(outcome)))
    case _                                          => None
