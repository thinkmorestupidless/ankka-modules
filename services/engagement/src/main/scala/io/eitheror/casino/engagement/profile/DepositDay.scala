package io.eitheror.casino.engagement.profile

import java.time.{Instant, LocalDate, LocalTime, ZoneOffset}

/**
 * The deposit day (FR-009), as the original's `DepositDay`: it begins at 03:00 UTC, and the reset that
 * pushes yesterday's zero runs at 03:05 UTC (the chart's `5 3 * * *`).
 */
object DepositDay:
  val ResetHourUtc: Int  = 3
  val ResetAt: LocalTime = LocalTime.of(ResetHourUtc, 5)

  def of(at: Instant): LocalDate = at.minusSeconds(ResetHourUtc.toLong * 3600L).atOffset(ZoneOffset.UTC).toLocalDate

  /** The next 03:05 UTC strictly after `now`. */
  def nextReset(now: Instant): Instant =
    val today = now.atOffset(ZoneOffset.UTC).toLocalDate.atTime(ResetAt).toInstant(ZoneOffset.UTC)
    if today.isAfter(now) then today else today.plusSeconds(24L * 3600L)

  /** The count read through its day stamp: a count from an earlier deposit day reads 0, whether or not the reset ran. */
  def countToday(p: ProfileState, today: LocalDate): Int =
    if p.depositDay.contains(today) then p.dailyDepositCount else 0
