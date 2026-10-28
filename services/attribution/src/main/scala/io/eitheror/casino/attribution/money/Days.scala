package io.eitheror.casino.attribution.money

import java.time.{Duration, Instant, LocalDate, ZoneId}

/**
 * The brand's reporting days (FR-016; research R10, R16): a day is the ISO date in the reporting zone,
 * it closes `CLOSE_GRACE` after its midnight, and the transition from legacy tags ends at the first of 12
 * months from the brand's first day or 90 days without a legacy tag.
 */
object Days:
  val TwelveMonths         = "12_months"
  val NinetyDaysWithout    = "90_days_without_legacy"

  def dayOf(at: Instant, zone: ZoneId): String = at.atZone(zone).toLocalDate.toString

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

  /**
   * The transition's end, once due: the first of `firstDay` plus 12 months and 90 days after the last
   * legacy day (after `firstDay` when none was presented), with what ended it; 12 months on a tie.
   */
  def endOfTransition(firstDay: LocalDate, lastLegacyDay: Option[LocalDate], today: LocalDate): Option[(LocalDate, String)] =
    val twelve = firstDay.plusMonths(12)
    val ninety = lastLegacyDay.getOrElse(firstDay).plusDays(90)
    val (end, by) = if ninety.isBefore(twelve) then (ninety, NinetyDaysWithout) else (twelve, TwelveMonths)
    Option.when(!today.isBefore(end))(end -> by)

  /** When the transition will end if nothing more is presented, for the brand's key document. */
  def transitionEndsAt(firstDay: LocalDate, lastLegacyDay: Option[LocalDate]): LocalDate =
    val ninety = lastLegacyDay.getOrElse(firstDay).plusDays(90)
    val twelve = firstDay.plusMonths(12)
    if ninety.isBefore(twelve) then ninety else twelve
