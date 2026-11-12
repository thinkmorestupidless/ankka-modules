package io.eitheror.casino.publicapi.features

/** features/public-api/player-stats.feature, bound by T081. */
class PlayerStatsFeatures extends PublicApiFeatures("player-stats.feature"):
  override protected def ranElsewhere: Map[String, String] = PublicApiFeatures.pending("T081", "player-stats.feature")
