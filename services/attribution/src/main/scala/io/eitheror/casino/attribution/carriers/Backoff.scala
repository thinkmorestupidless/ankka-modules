package io.eitheror.casino.attribution.carriers

import java.time.{Duration, Instant}

/**
 * A webhook's retry schedule (contracts/protocol.md; research R15): 1, 5, 15 and 60 minutes after the
 * failed attempts, then hourly, until 24 hours from the first attempt, when the fact is undelivered.
 */
object Backoff:
  val Life: Duration = Duration.ofHours(24)

  private val schedule = Vector(1L, 5L, 15L, 60L).map(Duration.ofMinutes)

  /** The wait after `attempts` failed attempts (1 for the first failure). */
  def next(attempts: Int): Duration = schedule.lift(attempts - 1).getOrElse(Duration.ofMinutes(60))

  def exhausted(firstAttemptAt: Instant, now: Instant): Boolean = !now.isBefore(firstAttemptAt.plus(Life))
