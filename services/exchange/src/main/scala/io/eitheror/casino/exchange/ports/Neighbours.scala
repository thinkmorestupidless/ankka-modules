package io.eitheror.casino.exchange.ports

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.exchange.Json
import io.eitheror.casino.exchange.Json.*

// Copied from services/game-provider/…/clients/Neighbours.scala (research R3), cut to the three
// gate calls an order makes.

/** The paths of the gates (contracts/ports.md); the test fakes use these same constants. */
object NeighbourPaths:
  def accountStatus(brand: String, player: String) = s"/v1/casino/brands/${Http.segment(brand)}/players/${Http.segment(player)}/status"
  def playCheck(brand: String, player: String)     = s"/v1/casino/brands/${Http.segment(brand)}/kyc/${Http.segment(player)}/play-check"
  def mayBet(brand: String)                        = s"/v1/casino/brands/${Http.segment(brand)}/rg/may-bet"

/** What a gate answered: allowed, refused with the casino's word, or no answer it can read. */
enum Gate:
  case Allowed
  case Refused(word: String)
  case Unavailable(reason: String)

final class Neighbours(services: ServiceClients, brand: String):
  private val players = Http(services, "players")
  private val kyc     = Http(services, "kyc")
  private val rg      = Http(services, "responsible-gambling")

  /** The account status (008 FR-018): `active` is allowed, any other status refuses by its word. */
  def account(player: String): Gate =
    players.get(NeighbourPaths.accountStatus(brand, player)) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 && b.text("status").isDefined =>
        b.text("status").get match
          case "active" => Gate.Allowed
          case other    => Gate.Refused(other)
      case Reply.Answered(s, _, text) => Gate.Unavailable(s"account status answered $s: ${text.take(200)}")
      case Reply.Unreachable(reason)  => Gate.Unavailable(reason)

  /** The play gate (009 FR-020): refused by the player's KYC status. */
  def playCheck(player: String): Gate =
    kyc.get(NeighbourPaths.playCheck(brand, player)) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 || s == 403 =>
        b.bool("allowed") match
          case Some(true)  => Gate.Allowed
          case Some(false) => Gate.Refused(b.textOr("kycStatus", "unknown"))
          case None        => Gate.Unavailable("the play check carried no answer")
      case Reply.Answered(s, _, text) => Gate.Unavailable(s"play check answered $s: ${text.take(200)}")
      case Reply.Unreachable(reason)  => Gate.Unavailable(reason)

  /** Whether the player may stake `amount` now (010 FR-010): refused by RG's decision. */
  def mayBet(player: String, currency: String, amount: Long): Gate =
    rg.post(NeighbourPaths.mayBet(brand), Json.obj("playerId" -> player, "currency" -> currency, "amount" -> amount)) match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 =>
        b.text("decision") match
          case Some("allowed")                                 => Gate.Allowed
          case Some(d @ ("locked" | "excluded" | "cooling_off")) => Gate.Refused(d)
          case other                                           => Gate.Unavailable(s"may-bet answered no decision it knows: $other")
      case Reply.Answered(s, _, text) => Gate.Unavailable(s"may-bet answered $s: ${text.take(200)}")
      case Reply.Unreachable(reason)  => Gate.Unavailable(reason)
