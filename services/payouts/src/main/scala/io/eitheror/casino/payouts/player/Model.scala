package io.eitheror.casino.payouts.player

import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.limits.Counted

import java.time.{Duration, Instant}

/** The player's names from `casino.players`, the profile a destination's holder must match. */
final case class ProfileName(givenName: Personal[String], familyName: Personal[String])

/** A saved payout destination (FR-013); a deleted one keeps its id with every value blanked. */
final case class Destination(
    id: String,
    givenName: Personal[String],
    familyName: Personal[String],
    iban: Personal[String],
    label: Personal[String],
    default: Boolean,
    active: Boolean,
    createdAt: Instant
):
  def blanked: Destination =
    copy(givenName = Personal(""), familyName = Personal(""), iban = Personal(""), label = Personal(""), default = false)

/** What payouts knows of a player (data-model.md `PlayerPayouts`). */
final case class PlayerState(
    known: Boolean = false,
    kyc: Kyc = Kyc.Unverified,
    closed: Boolean = false,
    names: Option[ProfileName] = None,
    email: Option[Personal[String]] = None,
    deposits: Map[String, Set[String]] = Map.empty,
    destinations: Vector[Destination] = Vector.empty,
    recent: Vector[Counted] = Vector.empty,
    erased: Boolean = false
):
  def destination(id: String): Option[Destination] = destinations.find(_.id == id)
  def activeDestinations: Vector[Destination]      = destinations.filter(_.active)

object PlayerState:
  /** The limits look back at most a calendar month, so 32 days of requests are kept. */
  val Kept: Duration = Duration.ofDays(32)

/** What a command answers: the state, the refusal, and the destination it acted on. */
final case class PlayerAnswer(state: PlayerState, refusal: Option[String] = None, destination: Option[Destination] = None)

sealed trait PlayerEvent
object PlayerEvent:
  final case class KycSeen(status: Kyc, at: Instant)                                             extends PlayerEvent
  final case class NamesSeen(names: ProfileName, at: Instant)                                    extends PlayerEvent
  final case class EmailSeen(address: Personal[String], at: Instant)                             extends PlayerEvent
  final case class ClosedSeen(at: Instant)                                                       extends PlayerEvent
  final case class DepositSeen(gateway: String, scheme: Option[String])                          extends PlayerEvent
  final case class DestinationSaved(destination: Destination)                                    extends PlayerEvent
  final case class DefaultSet(id: String, at: Instant)                                           extends PlayerEvent
  final case class DestinationDeleted(id: String, at: Instant)                                   extends PlayerEvent
  final case class RequestCounted(request: Counted)                                              extends PlayerEvent
  final case class RequestUncounted(id: String, at: Instant)                                     extends PlayerEvent
  final case class RecentDropped(before: Instant)                                                extends PlayerEvent
  final case class Erased(at: Instant)                                                           extends PlayerEvent

final case class SeeKyc(status: Kyc, at: Instant)
final case class SeeNames(givenName: Personal[String], familyName: Personal[String], at: Instant)
final case class SeeEmail(address: Personal[String], at: Instant)
final case class SeeClosed(at: Instant)
final case class SeeDeposit(gateway: String, scheme: Option[String], at: Instant)
final case class SaveDestination(id: String, givenName: String, familyName: String, iban: String, label: String,
    setAsDefault: Boolean, at: Instant)
final case class SetDefault(id: String, at: Instant)
final case class DeleteDestination(id: String, at: Instant)
final case class CountRequest(request: Counted, at: Instant)
final case class Uncount(id: String, at: Instant)
final case class Erase(at: Instant)

object PlayerCodes:
  val InvalidIban         = "INVALID_IBAN"
  val NotFound            = "DESTINATION_NOT_FOUND"
  val Deleted             = "DESTINATION_DELETED"
  val AlreadyDefault      = "ALREADY_DEFAULT"
