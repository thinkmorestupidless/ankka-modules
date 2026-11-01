package io.eitheror.casino.rewards.features

import io.eitheror.casino.rewards.it.Kit

/** Scenes a feature sets on the components themselves, where the route that makes them is another story's. */
object Scene:
  /** The offer made visible to the player, as a campaign or a code does: `PlayerRewards.Target` (task T033). */
  def target(kit: Kit, player: String, offerId: String): Unit =
    throw AssertionError(s"targeting $offerId at $player needs PlayerRewards (task T033); kit ${kit.slug}")
