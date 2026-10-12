package io.eitheror.casino.gameprovider.provider

import io.eitheror.casino.gameprovider.catalogue.GameFields
import io.eitheror.casino.gameprovider.config.ProviderConfiguration

/** What a launch asks the provider for (FR-008, contracts/provider-api.md). */
final case class LaunchRequest(
    gameCode: String,
    currency: String,
    token: String,
    playerId: String,
    funMode: Boolean,
    siteId: String,
    lobbyUrl: String,
    depositUrl: String,
    walletUrl: String,
    defaultCurrency: String,
    regCountry: String,
    jurisdiction: String,
    birthDate: String,
    regDate: String,
    betLimits: String
)

/** One game of the provider's list, with its code. */
final case class ListedGame(code: String, fields: GameFields)

/** The provider's bonus status, as `rewards` is told it (FR-025). */
object BonusStatus:
  val Active        = "active"
  val Processing    = "processing"
  val Rejected      = "rejected"
  val Failed        = "failed"
  val Cancelled     = "cancelled"
  val NotConfigured = "not_configured"

final case class BonusRequest(grantId: String, playerId: String, gameCodes: Vector[String], count: Int, spinValue: String,
    currency: String, durationSeconds: Long, startTime: String)

/**
 * The seam between the money rules and one provider's protocol (research R1): everything ST8 is behind
 * this, so a second provider is a second implementation and a second configuration. The callbacks'
 * protocol is the adapter's too (the St8 package); these are the calls this service makes to it.
 */
trait ProviderAdapter:
  def name: String

  /** The game URL, or why there is none (a launch failure, FR-008). */
  def launch(configuration: ProviderConfiguration, request: LaunchRequest): Either[String, String]

  /** The provider's whole game list for the brand's site, or why it could not be fetched (FR-021). */
  def games(configuration: ProviderConfiguration): Either[String, Vector[ListedGame]]

  def createBonus(configuration: ProviderConfiguration, request: BonusRequest): Either[String, String]
  def bonus(configuration: ProviderConfiguration, grantId: String): Either[String, String]
  def cancelBonus(configuration: ProviderConfiguration, grantId: String, playerId: String): Either[String, String]

  /** The spin values offered for the games in a currency, sorted. */
  def spinValues(configuration: ProviderConfiguration, gameCodes: Vector[String], currency: String): Either[String, Vector[String]]
