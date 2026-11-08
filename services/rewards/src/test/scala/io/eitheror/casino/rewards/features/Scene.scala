package io.eitheror.casino.rewards.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.it.Kit
import io.eitheror.casino.rewards.offers.OfferBook
import io.eitheror.casino.rewards.player.PlayerRewards

import java.util.UUID

/** Scenes a feature sets through the service's own routes. */
object Scene:
  /**
   * The offer made visible to the player as a campaign does (FR-022): the brand's `engagement` targets it by its role
   * (`POST /reward-campaigns/{player}/target`). An offer with no role is given one first, unique to it, as an operator
   * would (`PATCH /offers/{id}`). The reward's id, as `PlayerRewards` reserved it.
   */
  def target(kit: Kit, player: String, offerId: String): String =
    val role = roleOf(kit, offerId)
    val r    = kit.json("POST", kit.campaignsPath(s"/$player/target"), Json.obj("role" -> role), kit.asService("engagement"))
    if r.status != 200 then throw AssertionError(s"$offerId not targeted (${r.status}): ${r.text}")
    kit.components.forEventSourcedEntity(EntityId(player)).call(PlayerRewards.get).invoke().unclaimed(offerId).map(_._1)
      .getOrElse(throw AssertionError(s"$offerId not made visible to $player"))

  /** The offer's role, given one unique to it when it has none. */
  def roleOf(kit: Kit, offerId: String): String =
    val offer = kit.components.forEventSourcedEntity(EntityId(kit.slug)).call(OfferBook.get).invoke().offer(offerId)
      .getOrElse(throw AssertionError(s"no offer $offerId"))
    offer.role.getOrElse {
      val role = s"scene-${UUID.randomUUID().toString.take(8)}"
      val r    = kit.json("PATCH", kit.offersPath(s"/$offerId"), Json.obj("role" -> role), kit.asOperator())
      if r.status != 200 then throw AssertionError(s"$offerId not given a role (${r.status}): ${r.text}")
      role
    }
