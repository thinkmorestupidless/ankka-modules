package io.eitheror.casino.programme.periods

import java.time.{Instant, LocalDate, YearMonth, ZoneId}
import java.time.temporal.ChronoUnit
import scala.util.Try

/**
 * Periods and days, every one in the brand's reporting zone (research R14, change 15): a fact's period
 * is the month of its `occurredAt` there, and a period ends at the first instant of the next month
 * there. The original counted periods in UTC and days in Berlin.
 */
object Periods:

  /** The `yyyy-MM` an instant falls in, in `zone`. */
  def periodOf(at: Instant, zone: ZoneId): String = YearMonth.from(at.atZone(zone)).toString

  /** The local day an instant falls on, in `zone`. */
  def dayOf(at: Instant, zone: ZoneId): LocalDate = at.atZone(zone).toLocalDate

  /** The first instant of the month after `period`, in `zone`: the exclusive end of the period. */
  def periodEnd(period: String, zone: ZoneId): Instant =
    YearMonth.parse(period).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant

  /** Whether `text` is a `yyyy-MM` period. */
  def isPeriod(text: String): Boolean = text.length == 7 && Try(YearMonth.parse(text)).isSuccess

  /**
   * What a read's `period` selects, in the original's one grammar (`S/ReportingRange.scala`): `yyyy-MM`,
   * `yyyy-MM..yyyy-MM` (inclusive, at most 240 months), `yyyy-MM-dd..yyyy-MM-dd` (inclusive, days in the
   * reporting zone), or `all`. A month range selects on a fact's period; a day range on its instant.
   */
  enum Range:
    case Months(from: YearMonth, to: YearMonth)
    case Days(from: LocalDate, to: LocalDate)
    case AllTime

    /** The periods a month range covers; empty for the others. */
    def periods: Vector[String] = this match
      case Months(from, to) => Iterator.iterate(from)(_.plusMonths(1)).takeWhile(!_.isAfter(to)).map(_.toString).toVector
      case _                => Vector.empty

    /** Whether a fact of `period` that occurred `at` is in the range. */
    def holds(period: String, at: Instant, zone: ZoneId): Boolean = this match
      case Months(from, to) => period.length == 7 && period >= from.toString && period <= to.toString
      case Days(from, to)   => val d = dayOf(at, zone); !d.isBefore(from) && !d.isAfter(to)
      case AllTime          => true

    /** The instants bounding the range in `zone`, `[from, until)`; `None` for all time. */
    def bounds(zone: ZoneId): Option[(Instant, Instant)] = this match
      case Months(from, to) => Some(from.atDay(1).atStartOfDay(zone).toInstant -> to.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant)
      case Days(from, to)   => Some(from.atStartOfDay(zone).toInstant -> to.plusDays(1).atStartOfDay(zone).toInstant)
      case AllTime          => None

  object Range:
    val MaxMonths = 240

    private val MonthRe = """(\d{4}-\d{2})""".r
    private val DayRe   = """(\d{4}-\d{2}-\d{2})""".r

    private def month(s: String): Option[YearMonth] = s match
      case MonthRe(m) => Try(YearMonth.parse(m)).toOption
      case _          => None

    private def day(s: String): Option[LocalDate] = s match
      case DayRe(d) => Try(LocalDate.parse(d)).toOption
      case _        => None

    /** `None` for a malformed token, mixed granularity, an end before the start, or more than 240 months. */
    def parse(token: String): Option[Range] =
      if token == "all" then Some(AllTime)
      else
        token.split("\\.\\.", -1) match
          case Array(single) => month(single).map(m => Months(m, m))
          case Array(a, b) =>
            (month(a), month(b)) match
              case (Some(f), Some(t)) if !t.isBefore(f) && f.until(t, ChronoUnit.MONTHS) < MaxMonths => Some(Months(f, t))
              case (Some(_), Some(_)) => None
              case _ =>
                (day(a), day(b)) match
                  case (Some(f), Some(t)) if !t.isBefore(f) => Some(Days(f, t))
                  case _                                    => None
          case _ => None
