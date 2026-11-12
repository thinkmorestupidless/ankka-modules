package io.eitheror.casino.publicapi.features

/** features/public-api/games.feature, bound by T073. */
class GamesFeatures extends PublicApiFeatures("games.feature"):
  override protected def ranElsewhere: Map[String, String] = PublicApiFeatures.pending("T073", "games.feature")
