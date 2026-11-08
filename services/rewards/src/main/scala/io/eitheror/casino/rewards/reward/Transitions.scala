package io.eitheror.casino.rewards.reward

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.rewards.RewardsCodecs

/** A reward's state (FR-009): the original's ten, upper case on the wire as the original stored them. */
enum RewardStatus(val wire: String):
  case Available           extends RewardStatus("AVAILABLE")
  case Pending             extends RewardStatus("PENDING")
  case Active              extends RewardStatus("ACTIVE")
  /** Declared and never reached, as in the original. */
  case Paused              extends RewardStatus("PAUSED")
  case ConvertedPendingCap extends RewardStatus("CONVERTED_PENDING_CAP")
  case Closed              extends RewardStatus("CLOSED")
  case Expired             extends RewardStatus("EXPIRED")
  case Cancelled           extends RewardStatus("CANCELLED")
  case Forfeited           extends RewardStatus("FORFEITED")
  case Revoked             extends RewardStatus("REVOKED")

  /** No transition leaves it. */
  def isTerminal: Boolean = !Transitions.allowed.contains(this)

object RewardStatus:
  def from(text: String): Option[RewardStatus] = values.find(_.wire == text)
  given JsonValueCodec[RewardStatus]           = RewardsCodecs.wire(values, _.wire)

/** A move the table does not list, naming both ends. */
final case class InvalidTransition(from: RewardStatus, to: RewardStatus):
  def code: String    = "INVALID_TRANSITION"
  def message: String = s"a reward cannot move from ${from.wire} to ${to.wire}"

/** What a permitted move does: a repeat of the current state writes nothing. */
enum Move:
  case Stay
  case To(status: RewardStatus)

/** The original's table (`RewardStatus.scala:60-66`, feature 216 data model §5); terminal states are never keys. */
object Transitions:
  import RewardStatus.*

  val allowed: Map[RewardStatus, Set[RewardStatus]] = Map(
    Available           -> Set(Pending, Expired, Active),
    Pending             -> Set(Active, Available, Expired, Cancelled),
    Active              -> Set(Paused, ConvertedPendingCap, Closed, Expired, Cancelled, Forfeited, Revoked),
    Paused              -> Set(Active, Cancelled, Revoked),
    ConvertedPendingCap -> Set(Closed, Forfeited, Revoked)
  )

  def move(from: RewardStatus, to: RewardStatus): Either[InvalidTransition, Move] =
    if from == to then Right(Move.Stay)
    else if allowed.getOrElse(from, Set.empty).contains(to) then Right(Move.To(to))
    else Left(InvalidTransition(from, to))

  def canMove(from: RewardStatus, to: RewardStatus): Boolean = move(from, to).isRight
