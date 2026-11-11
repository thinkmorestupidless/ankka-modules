package io.eitheror.casino.reporting.ranges

import java.time.format.TextStyle
import java.time.temporal.{ChronoUnit, IsoFields}
import java.time.LocalDate
import java.util.Locale

/**
 * Periods and the dashboard's ranges in the brand's reporting days (FR-006, FR-007; research R6). Every date is a
 * reporting day; the caller resolves "today" in the zone.
 */
object Ranges:

  /** A run of reporting days, both ends inclusive. */
  final case class Period(from: LocalDate, to: LocalDate):
    def days: Seq[LocalDate] = (0L to ChronoUnit.DAYS.between(from, to)).map(from.plusDays)
    def length: Long        = ChronoUnit.DAYS.between(from, to) + 1

  /** A bucket of the trend: its label and the days it covers. */
  final case class Bucket(label: String, from: LocalDate, to: LocalDate)

  val Today      = "today"
  val SevenDays  = "7d"
  val ThirtyDays = "30d"
  val NinetyDays = "90d"
  val ranges: Set[String] = Set(Today, SevenDays, ThirtyDays, NinetyDays)

  /** A range as the original reads it: anything else is `7d`. */
  def range(asked: Option[String]): String = asked.filter(ranges.contains).getOrElse(SevenDays)

  /** The reports' default period: the first of the current month to today. */
  def defaultPeriod(today: LocalDate): Period = Period(today.withDayOfMonth(1), today)

  /** A period from the query's `from` and `to`, each defaulting as the original does; a bad date is `Left`. */
  def period(from: Option[String], to: Option[String], today: LocalDate): Either[String, Period] =
    val d = defaultPeriod(today)
    for
      f <- from.fold(Right(d.from))(parse)
      t <- to.fold(Right(d.to))(parse)
    yield Period(f, t)

  def parse(text: String): Either[String, LocalDate] =
    scala.util.Try(LocalDate.parse(text)).toOption.toRight(text)

  /** The days a range covers, ending today: today alone, 7, 30, or the 91 days of 13 weeks. */
  def periodOf(range: String, today: LocalDate): Period = range match
    case Today      => Period(today, today)
    case ThirtyDays => Period(today.minusDays(29), today)
    case NinetyDays => Period(today.minusDays(90), today)
    case _          => Period(today.minusDays(6), today)

  /** An hour's label, `HH:00`, for today's trend; today's buckets run to the current hour. */
  def hourLabel(hour: Int): String = f"$hour%02d:00"

  /**
   * The trend's daily or weekly buckets for a range other than today, oldest first, as the original builds them
   * (`DashboardRoutes.scala:338-361`): days labelled `YYYY-MM-DD`; for 90 days, 13 seven-day weeks ending today,
   * each labelled `YYYY-Www` from its first day's ISO week and calendar year.
   */
  def buckets(range: String, today: LocalDate): Seq[Bucket] = range match
    case NinetyDays =>
      (12 to 0 by -1).map { w =>
        val start = today.minusDays(w * 7L + 6)
        val week  = start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        Bucket(f"${start.getYear}-W$week%02d", start, start.plusDays(6))
      }
    case _ => periodOf(range, today).days.map(d => Bucket(d.toString, d, d))

  /** The KPI history's seven days, oldest first: short English weekdays, then `Today`. */
  def historyDays(today: LocalDate): Seq[(String, LocalDate)] =
    (6 to 0 by -1).map { back =>
      val day = today.minusDays(back.toLong)
      (if back == 0 then "Today" else day.getDayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) -> day
    }
