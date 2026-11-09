package io.eitheror.casino.audit.retention

import java.time.{Instant, ZoneOffset}

/** How long a brand's records are kept (spec 018 FR-021): raised, never lowered. */
object Retention:
  val BelowFloor = "below_floor"

  /** The oldest instant still kept: `years` calendar years before `now`, in UTC. */
  def cutoff(now: Instant, years: Int): Instant =
    now.atOffset(ZoneOffset.UTC).minusYears(years.toLong).toInstant

  /** The brand's new retention, refused below its current one or the installation's. */
  def raise(current: Int, asked: Int, floor: Int): Either[String, Int] =
    if asked < floor then Left(s"$BelowFloor: $asked years is below the installation's $floor")
    else if asked < current then Left(s"$BelowFloor: $asked years is below the brand's current $current")
    else Right(asked)
