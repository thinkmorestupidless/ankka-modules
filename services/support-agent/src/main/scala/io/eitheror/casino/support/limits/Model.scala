package io.eitheror.casino.support.limits

import java.time.{Duration, Instant}

/** A player's turns in the last minute (data-model.md `TurnWindow`; FR-024). */
final case class TurnWindowState(times: Vector[Instant] = Vector.empty)

enum Take:
  case Allowed
  case Refused(retryAfterSeconds: Long)

final case class TakeTurn(at: Instant, limit: Int)

/** The rule behind `TurnWindow`, pure: at most `limit` turns in any minute. */
object Rate:
  val Window: Duration = Duration.ofMinutes(1)

  def take(s: TurnWindowState, at: Instant, limit: Int): (TurnWindowState, Take) =
    val recent = s.times.filter(t => t.isAfter(at.minus(Window)))
    if recent.size < limit then (TurnWindowState(recent :+ at), Take.Allowed)
    else
      // The oldest turn in the window leaves it first; the player may try again then, at least a second from now.
      val wait = Duration.between(at, recent.head.plus(Window)).toMillis
      (TurnWindowState(recent), Take.Refused(math.max(1L, (wait + 999L) / 1000L)))

/** A brand's tokens on one UTC day (data-model.md `Budget`; R10), each session's counted once. */
final case class BudgetState(tokens: Long = 0L, counted: Map[String, Long] = Map.empty):
  /** Adds only what `session` has used past what was counted for it: idempotent. */
  def add(session: String, upTo: Long): BudgetState =
    val already = counted.getOrElse(session, 0L)
    if upTo <= already then this else BudgetState(tokens + (upTo - already), counted.updated(session, upTo))

  def spent(limit: Long): Boolean = tokens >= limit

final case class AddTokens(session: String, upTo: Long)
final case class Spend(limit: Long)
final case class Spent(spent: Boolean, used: Long)
