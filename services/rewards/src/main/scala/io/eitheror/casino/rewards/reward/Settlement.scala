package io.eitheror.casino.rewards.reward

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.rewards.RewardsCodecs

import java.time.Instant

/**
 * Where free-rounds winnings land (FR-024), by the offer's `winningsDestination` as the original names it, and
 * the `WalletStep` that pays them (research R5).
 */
enum Destination(val wire: String, val step: String):
  /** The wallet's provider credit: the primary open record's restricted funds when one is open, else real balance. */
  case RealBalance     extends Destination("real_balance", "credit")
  /** Restricted funds with the offer's multiplier and maximum convertible amount, ahead of every open record. */
  case RestrictedFunds extends Destination("bonus_balance", "winnings")

object Destination:
  /** `bonus_balance` is restricted funds; anything else, or none, is real balance, the original's default. */
  def from(text: Option[String]): Destination = if text.contains(RestrictedFunds.wire) then RestrictedFunds else RealBalance
  given JsonValueCodec[Destination]            = RewardsCodecs.wire(values, _.wire)

/**
 * A free-rounds reward's play: the rounds granted, the round ids counted, the rounds used, the winnings
 * accumulated in minor units, and the amount paid at finalisation once it is finalised. `used` and `seen` are
 * kept apart as the original kept `spins_used` and `settled_round_refs`, so a tally carried over with either
 * ahead still completes.
 */
final case class Tally(count: Int, seen: Set[String] = Set.empty, used: Int = 0, accumulated: Long = 0L,
    paid: Option[Long] = None):
  def played: Int       = math.max(used, seen.size)
  def remaining: Int    = math.max(0, count - played)
  def complete: Boolean = played >= count
  def finalised: Boolean = paid.isDefined

  /** A round counted before finalisation. */
  def counted(roundId: String, win: Long): Tally = copy(seen = seen + roundId, used = used + 1, accumulated = accumulated + win)

  /** A round after finalisation: remembered so it is paid once, the finalised amount untouched. */
  def late(roundId: String): Tally = copy(seen = seen + roundId)

  def finalise(amount: Long): Tally = copy(paid = Some(amount))

/** A payment of winnings; a late round's is under its own step and key. */
final case class Payment(destination: Destination, amount: Long, lateRound: Option[String] = None):
  def step: String = lateRound.fold(destination.step)(r => s"late-$r")

  /** The wallet command's key (research R5). */
  def key(rewardId: String): String = lateRound.fold(s"$rewardId/${destination.step}")(r => s"$rewardId/late/$r")

/** The end of a reward's free rounds: the amount, the payment when there is something to pay, the state and its reason. */
final case class Finalisation(amount: Long, payment: Option[Payment], status: RewardStatus, reason: String)

/** A round taken. */
enum Settled:
  /** Counted, finalising the reward when it completes the count. */
  case Counted(tally: Tally, finalisation: Option[Finalisation])
  /** After finalisation: paid on its own when it won something. */
  case Late(tally: Tally, payment: Option[Payment])

/** Why a round or the expiry does nothing. */
enum Skip(val reason: String):
  case AlreadyCounted   extends Skip("already_counted")
  case NotLive          extends Skip("not_live")
  case AlreadyFinalised extends Skip("already_finalised")
  case NotDue           extends Skip("not_due")

/**
 * Free-rounds settlement (research R8; `settle_free_spins_round/handler.go:86-275`,
 * `finalize_free_spins_grant/handler.go:83-162`), pure. A round counts once per id; the reward completes at
 * `max(used, seen) >= count` or at its expiry, paying the full total, uncapped, to its destination; nothing won
 * pays nothing. Completion closes the reward; expiry closes it when something was won, else expires it.
 * Unlike the original, a round settling after finalisation is paid on its own (FR-024). A revoked, cancelled or
 * forfeited reward takes no round, as the original's revoked guard.
 */
object Settlement:
  val Finalised     = "free_spins_finalised"
  val ExpiredUnused = "free_spins_expired_unused"

  def round(status: RewardStatus, tally: Tally, destination: Destination, roundId: String, win: Long)
      : Either[Skip, Settled] =
    if tally.seen.contains(roundId) then Left(Skip.AlreadyCounted)
    else if tally.finalised && (status == RewardStatus.Closed || status == RewardStatus.Expired || !status.isTerminal) then
      Right(Settled.Late(tally.late(roundId), Option.when(win > 0)(Payment(destination, win, Some(roundId)))))
    else if status.isTerminal then Left(Skip.NotLive)
    else
      val next = tally.counted(roundId, win)
      Right(Settled.Counted(next, Option.when(next.complete)(finalisation(next, destination, RewardStatus.Closed))))

  /** The `rounds-<reward>` timer: due at `expiresAt`. */
  def expire(status: RewardStatus, tally: Tally, destination: Destination, expiresAt: Instant, at: Instant)
      : Either[Skip, Finalisation] =
    if tally.finalised then Left(Skip.AlreadyFinalised)
    else if status.isTerminal then Left(Skip.NotLive)
    else if at.isBefore(expiresAt) then Left(Skip.NotDue)
    else Right(finalisation(tally, destination, if tally.accumulated > 0 then RewardStatus.Closed else RewardStatus.Expired))

  private def finalisation(tally: Tally, destination: Destination, status: RewardStatus): Finalisation =
    val amount = tally.accumulated
    Finalisation(amount, Option.when(amount > 0)(Payment(destination, amount)), status,
      if status == RewardStatus.Expired then ExpiredUnused else Finalised)
