package io.eitheror.casino.reporting.days

import java.time.{Duration, Instant, LocalDate, ZoneId}

/**
 * The brand's reporting days (FR-006; research R7), copied from attribution's `money/Days` so the day-end
 * closes on the instant spec 012 FR-016 closes `ggr-daily`: a day is the ISO date in the reporting zone and
 * it closes `CLOSE_GRACE` after its midnight.
 */
object Days:
  def dayOf(at: Instant, zone: ZoneId): String = at.atZone(zone).toLocalDate.toString

  /** The hour of the day in the zone, 0 to 23, for the day's hourly buckets. */
  def hourOf(at: Instant, zone: ZoneId): Int = at.atZone(zone).getHour

  /** The instant `day` starts: its midnight in the zone. */
  def startOf(day: String, zone: ZoneId): Instant = LocalDate.parse(day).atStartOfDay(zone).toInstant

  /** The instant `day` ends: the next day's midnight in the zone. */
  def endOf(day: String, zone: ZoneId): Instant = LocalDate.parse(day).plusDays(1).atStartOfDay(zone).toInstant

  /** Whether `day` may be closed at `now`: its end plus the grace has passed. */
  def closeDue(day: String, zone: ZoneId, grace: Duration, now: Instant): Boolean =
    !now.isBefore(endOf(day, zone).plus(grace))

  /** The next close after `now`: the coming midnight in the zone plus the grace. */
  def nextClose(now: Instant, zone: ZoneId, grace: Duration): Instant =
    val today = now.atZone(zone).toLocalDate
    val close = today.atStartOfDay(zone).toInstant.plus(grace)
    if close.isAfter(now) then close else endOf(today.toString, zone).plus(grace)
