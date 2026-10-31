package io.eitheror.casino.gameprovider.clients

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*

/**
 * The paths this service calls on `players`, `kyc`, `responsible-gambling` and `rewards`. Each is
 * *expected* (contracts/neighbours.md) until that neighbour's spec is planned; the test fakes use these
 * same constants, so one edit here moves both.
 */
object NeighbourPaths:
  def accountStatus(brand: String, player: String)  = s"/v1/casino/brands/${Http.segment(brand)}/players/${Http.segment(player)}/status"
  def gameSessions(brand: String, player: String)   = s"/v1/casino/brands/${Http.segment(brand)}/players/${Http.segment(player)}/game-sessions"
  def recentlyPlayed(brand: String, player: String) = s"/v1/casino/brands/${Http.segment(brand)}/players/${Http.segment(player)}/recently-played"
  def profile(brand: String, player: String)        = s"/v1/casino/brands/${Http.segment(brand)}/players/${Http.segment(player)}/profile"
  def playCheck(brand: String, player: String)      = s"/v1/casino/brands/${Http.segment(brand)}/kyc/${Http.segment(player)}/play-check"
  def mayPlay(brand: String, player: String)        = s"/v1/casino/brands/${Http.segment(brand)}/rg/${Http.segment(player)}/may-play"
  /** The bet-time check, the path spec 002 used before it moved here (contracts/neighbours.md, research R18). */
  def mayBet(brand: String)                         = s"/v1/casino/brands/${Http.segment(brand)}/rg/may-bet"
  def eligibleGrant(brand: String, player: String, provider: String, gameCode: String) =
    s"/v1/casino/brands/${Http.segment(brand)}/rewards/${Http.segment(player)}/free-rounds/eligible?provider=${Http.segment(provider)}&gameCode=${Http.segment(gameCode)}"

/** The service that could not answer, refusing the launch closed (FR-007): `service_unavailable` naming it. */
final case class ServiceUnavailable(service: String, reason: String)

/** The profile fields FR-018 and the launch need, read from `players` at the time of the call and never kept (FR-028). */
final case class PlayerProfile(
    defaultCurrency: Option[String],
    country: Option[String],
    dateOfBirth: Option[String],
    registeredAt: Option[String],
    dailyDepositLimit: Option[Long]
)

enum ProfileAnswer:
  case Found(profile: PlayerProfile)
  case NotFound
  case Unavailable(reason: String)

final class PlayersClient(services: ServiceClients, brand: String):
  private val http = Http(services, "players")

  /** The account status (008 FR-018): `active`, `suspended`, `self_excluded`, `blocked`, `closed`, … */
  def accountStatus(player: String): Either[ServiceUnavailable, String] =
    http.get(NeighbourPaths.accountStatus(brand, player)) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 && b.text("status").isDefined => Right(b.text("status").get)
      case Reply.Answered(s, _, text) => Left(ServiceUnavailable("players", s"account status answered $s: ${text.take(200)}"))
      case Reply.Unreachable(reason)  => Left(ServiceUnavailable("players", reason))

  /** Opens a game session (008 FR-017); a failure does not fail the launch (FR-009), so none is answered. */
  def openGameSession(player: String, gameId: String, gameName: Option[String], gameCategory: Option[String], currency: String,
      demo: Boolean, playerSessionId: Option[String]): Option[String] =
    http.post(NeighbourPaths.gameSessions(brand, player), Json.obj("gameId" -> gameId, "gameName" -> gameName,
      "gameCategory" -> gameCategory, "currency" -> currency, "demo" -> demo, "playerSessionId" -> playerSessionId)) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 => b.text("sessionId")
      case _                                            => None

  /** Records the game as recently played; best effort (FR-009). */
  def recentlyPlayed(player: String, gameId: String): Unit =
    http.post(NeighbourPaths.recentlyPlayed(brand, player), Json.obj("gameId" -> gameId)): Unit

  def profile(player: String): ProfileAnswer =
    http.get(NeighbourPaths.profile(brand, player)) match
      case Reply.Answered(404, _, _) => ProfileAnswer.NotFound
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 =>
        ProfileAnswer.Found(PlayerProfile(b.text("defaultCurrency"), b.text("country"), b.text("dateOfBirth"),
          b.text("registeredAt"), b.long("dailyDepositLimit")))
      case Reply.Answered(s, _, text) => ProfileAnswer.Unavailable(s"profile answered $s: ${text.take(200)}")
      case Reply.Unreachable(reason)  => ProfileAnswer.Unavailable(reason)

enum PlayCheck:
  case Allowed
  case Blocked(kycStatus: String, message: String)

final class KycClient(services: ServiceClients, brand: String):
  private val http = Http(services, "kyc")

  /** The play gate (009 FR-020). */
  def playCheck(player: String): Either[ServiceUnavailable, PlayCheck] =
    http.get(NeighbourPaths.playCheck(brand, player)) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 || s == 403 =>
        b.bool("allowed") match
          case Some(true)  => Right(PlayCheck.Allowed)
          case Some(false) => Right(PlayCheck.Blocked(b.textOr("kycStatus", "unknown"), b.textOr("message", "")))
          case None        => Left(ServiceUnavailable("kyc", "the play check carried no answer"))
      case Reply.Answered(s, _, text) => Left(ServiceUnavailable("kyc", s"play check answered $s: ${text.take(200)}"))
      case Reply.Unreachable(reason)  => Left(ServiceUnavailable("kyc", reason))

enum MayPlay:
  case Allowed(sessionLimitMinutes: Option[Long], minutesUsed: Option[Long])
  case Refused(reason: String, coolingOffUntil: Option[String], limitMinutes: Option[Long], usedMinutes: Option[Long])

/** `may-bet`'s decision (010 FR-010): allowed, or the refusal the bet workflow answers `player_locked`. */
enum MayBet:
  case Allowed
  case Refused(decision: String)

final class ResponsibleGamblingClient(services: ServiceClients, brand: String):
  private val http = Http(services, "responsible-gambling")

  /**
   * Whether the player may stake `amount` now, asked by the bet workflow before the wallet (FR-012,
   * research R18). It fails closed: a non-2xx or an answer it cannot read is unavailable, and the bet is
   * not placed; the 500 ms bound is the workflow step's timeout (`BetWorkflow`), since a step may call a
   * service only from its own thread.
   */
  def mayBet(player: String, currency: String, amount: Long): Either[ServiceUnavailable, MayBet] =
    val body = Json.obj("playerId" -> player, "currency" -> currency, "amount" -> amount)
    http.post(NeighbourPaths.mayBet(brand), body) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 =>
        b.text("decision") match
          case Some("allowed")                                          => Right(MayBet.Allowed)
          case Some(d @ ("locked" | "excluded" | "cooling_off"))         => Right(MayBet.Refused(d))
          case other => Left(ServiceUnavailable("responsible-gambling", s"may-bet answered no decision it knows: $other"))
      case Reply.Answered(s, _, text) => Left(ServiceUnavailable("responsible-gambling", s"may-bet answered $s: ${text.take(200)}"))
      case Reply.Unreachable(reason)  => Left(ServiceUnavailable("responsible-gambling", reason))

  /** Break, exclusion and session minutes (010 FR-008). */
  def mayPlay(player: String): Either[ServiceUnavailable, MayPlay] =
    http.get(NeighbourPaths.mayPlay(brand, player)) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 || s == 403 =>
        b.bool("allowed") match
          case Some(true)  => Right(MayPlay.Allowed(b.long("sessionLimitMinutes"), b.long("minutesUsed")))
          case Some(false) => Right(MayPlay.Refused(b.textOr("reason", "excluded"), b.text("coolingOffUntil"), b.long("limitMinutes"), b.long("usedMinutes")))
          case None        => Left(ServiceUnavailable("responsible-gambling", "may-play carried no answer"))
      case Reply.Answered(s, _, text) => Left(ServiceUnavailable("responsible-gambling", s"may-play answered $s: ${text.take(200)}"))
      case Reply.Unreachable(reason)  => Left(ServiceUnavailable("responsible-gambling", reason))

object ResponsibleGamblingClient:
  val MayBetWithin: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.FiniteDuration(500, "ms")

final class RewardsClient(services: ServiceClients, brand: String):
  private val http = Http(services, "rewards")

  /** The player's oldest eligible free-rounds grant for a game (FR-008); none when `rewards` has none or cannot say. */
  def oldestEligibleGrant(player: String, provider: String, gameCode: String): Option[String] =
    http.get(NeighbourPaths.eligibleGrant(brand, player, provider, gameCode)) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 => b.text("grantId")
      case _                                            => None
