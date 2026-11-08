package io.eitheror.casino.rewards.clients

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.{Json, Settings}
import io.eitheror.casino.rewards.Json.*

/** A provider bonus's status as `game-provider` answers it (spec 003 `BonusStatus`; `error` the original's). */
enum BonusStatus(val wire: String):
  case Active        extends BonusStatus("active")
  case Processing    extends BonusStatus("processing")
  case NotConfigured extends BonusStatus("not_configured")
  case Rejected      extends BonusStatus("rejected")
  case Failed        extends BonusStatus("failed")
  case Cancelled     extends BonusStatus("cancelled")
  case Error         extends BonusStatus("error")

  /** A create answered so is playable (research R8); the rest cancel the reward `provider_failed`. */
  def playable: Boolean = this == Active || this == Processing || this == NotConfigured

object BonusStatus:
  /** An unrecognised status is the provider's error. */
  def from(text: String): BonusStatus = values.find(_.wire == text).getOrElse(Error)

/** A free-rounds grant (research R8's `create`): `grantId` the reward id, `durationSeconds` expiry days × 86,400. */
final case class FreeRoundsGrant(grantId: String, playerId: String, gameCodes: Vector[String], count: Int, spinValue: String,
    currency: Currency, durationSeconds: Long)

/** The spin values offered for games in a currency; none configured when the brand has no provider configuration. */
final case class SpinValues(values: Vector[String], configured: Boolean)

/** One game (G1): eligible for free rounds when its bonus types hold `free_bets` or `free_spins`. */
final case class ProviderGame(gameCode: String, bonusTypes: Vector[String]):
  def freeRoundsEligible: Boolean = bonusTypes.exists(t => t == "free_bets" || t == "free_spins")

/**
 * `game-provider`'s routes for free rounds (contracts/routes.md, *Calls out*; research R8 and R6 G1), under the
 * brand's service prefix for one provider, ST8 unless told. A game the catalogue does not hold is refused
 * `404 game_not_found`.
 */
trait GameProvider:
  def known(playerId: String, currency: Currency): Called[Unit]
  def create(grant: FreeRoundsGrant): Called[BonusStatus]
  def status(grantId: String): Called[BonusStatus]
  def cancel(grantId: String, playerId: String): Called[BonusStatus]
  def spinValues(gameCodes: Vector[String], currency: Currency): Called[SpinValues]
  def game(gameCode: String): Called[ProviderGame]

object GameProvider:
  val St8 = "st8"

  def http(services: ServiceClients, settings: Settings): GameProvider =
    Http(Neighbour(services, settings.gameProvider), settings.brand, St8)

  val GameNotFound = "game_not_found"

  final class Http(provider: Neighbour, brand: String, name: String) extends GameProvider:
    private val base = s"/v1/casino/brands/${Neighbour.segment(brand)}/game-provider/${Neighbour.segment(name)}"

    private def bonusStatus(reply: Reply): Called[BonusStatus] =
      Called.of(reply, "game-provider")(b => b.text("status").map(BonusStatus.from))

    def known(playerId: String, currency: Currency): Called[Unit] =
      Called.of(provider.post(s"$base/players/${Neighbour.segment(playerId)}/known", Json.obj("currency" -> currency.code)),
        "game-provider")(_ => Some(()))

    def create(g: FreeRoundsGrant): Called[BonusStatus] =
      bonusStatus(provider.post(s"$base/bonuses", Json.obj("grantId" -> g.grantId, "playerId" -> g.playerId,
        "gameCodes" -> g.gameCodes, "count" -> g.count, "spinValue" -> g.spinValue, "currency" -> g.currency.code,
        "durationSeconds" -> g.durationSeconds)))

    def status(grantId: String): Called[BonusStatus] = bonusStatus(provider.get(s"$base/bonuses/${Neighbour.segment(grantId)}"))

    def cancel(grantId: String, playerId: String): Called[BonusStatus] =
      bonusStatus(provider.post(s"$base/bonuses/${Neighbour.segment(grantId)}/cancel", Json.obj("playerId" -> playerId)))

    def spinValues(gameCodes: Vector[String], currency: Currency): Called[SpinValues] =
      val codes = gameCodes.map(Neighbour.segment).mkString(",")
      Called.of(provider.get(s"$base/spin-values?gameCodes=$codes&currency=${currency.code}"), "game-provider") { b =>
        if b.text("status").contains(BonusStatus.NotConfigured.wire) then Some(SpinValues(Vector.empty, configured = false))
        else Option(b.get("values")).filter(_.isArray).map(_ => SpinValues(b.strings("values"), configured = true))
      }

    def game(gameCode: String): Called[ProviderGame] =
      Called.of(provider.get(s"$base/games/${Neighbour.segment(gameCode)}"), "game-provider") { b =>
        Some(ProviderGame(b.textOr("gameCode", gameCode), b.strings("bonusTypes")))
      }
