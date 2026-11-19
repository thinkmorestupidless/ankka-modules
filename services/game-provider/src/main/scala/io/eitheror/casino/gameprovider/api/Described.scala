package io.eitheror.casino.gameprovider.api

import com.thinkmorestupidless.ankka.http.openapi.Description

/**
 * What the launch and the catalogue's reads take and answer, for their descriptions (spec 031,
 * contracts/descriptions.md). The routes build their JSON as trees (`Launch`, `CatalogueReads`), so
 * these types exist only to give the document their shapes, field for field.
 */
private[api] object Described:

  /** `Context.refuse`: the code and the fields the refusal names. */
  final case class Refusal(error: String)

  final case class Launch(playerId: String, gameId: String, demo: Option[Boolean], provider: Option[String],
      currency: Option[String], freeSpinsGrantRef: Option[String])

  final case class Launched(launchToken: String, gameUrl: String, sessionId: String, freeSpinsGrantRef: Option[String])

  /** `CatalogueReads.toJson`: one listed game. */
  final case class Game(
      gameId: String,
      provider: String,
      name: String,
      category: String,
      developerId: String,
      developerName: String,
      tags: Vector[String],
      enabled: Boolean,
      funMode: Boolean,
      bonusTypes: Vector[String],
      features: Vector[String],
      themes: Vector[String],
      restrictedTerritories: Vector[String],
      prohibitedTerritories: Vector[String],
      thumbnailUrl: Option[String],
      rtp: Option[String],
      volatility: Option[Long],
      hitRatio: Option[String],
      maxPayoutCoeff: Option[String],
      releaseDate: Option[String],
      deprecationDate: Option[String]
  )

  /** A page of the lobby; `total` and `nextOffset` with a `limit` only. */
  final case class Games(games: Vector[Game], total: Option[Int], nextOffset: Option[Int])

  final case class Developer(developerId: String, developerName: String)
  final case class Developers(developers: Vector[Developer])
  final case class Popular(gameIds: Vector[String])

  val refusals: Description = Description.none.refusalShape[Refusal]
