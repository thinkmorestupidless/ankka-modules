package io.eitheror.casino.rewards.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.it.Kit
import io.eitheror.casino.rewards.offers.OfferBook
import io.eitheror.casino.rewards.player.{Openings, Outcome, PlayerRewards, Target}
import io.eitheror.casino.rewards.reward.{Open, Reward, RewardStatus, Source}

/** Scenes a feature sets on the components themselves, where the route that makes them is another story's. */
object Scene:
  /**
   * The offer made visible to the player, as a campaign does (US6's target route, task T059): `PlayerRewards.Target`
   * reserves the reward and the reward is opened `AVAILABLE` with the offer's fixed amount.
   */
  def target(kit: Kit, player: String, offerId: String, source: Source = Source.CustomerioWebhook): String =
    val offer = kit.components.forEventSourcedEntity(EntityId(kit.slug)).call(OfferBook.get).invoke().offer(offerId)
      .getOrElse(throw AssertionError(s"no offer $offerId"))
    val answer = kit.components.forEventSourcedEntity(EntityId(player)).call(PlayerRewards.target)
      .invoke(Target(offer, source, at = kit.now()))
    answer.outcome match
      case Outcome.Visible(id, _) =>
        val opening = Openings.provisional(offer, player, offer.currency.getOrElse(Currency.unsafe("EUR")), source, None, None)
        kit.components.forEventSourcedEntity(EntityId(id)).call(Reward.open).invoke(Open(opening, RewardStatus.Available, kit.now())): Unit
        id
      case other => throw AssertionError(s"$offerId not made visible: $other")
