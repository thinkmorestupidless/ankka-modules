package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.Generation

import java.time.{Instant, LocalDate}

/**
 * A reporting day as the reports read it (data-model.md `Days`): the day's state but `lastSeq`, which only the entity
 * needs to skip a redelivery. One row per `BrandDay`, keyed by its id, so a generation's rows sit beside another's.
 */
final case class DayRow(
    generation: Int,
    day: String,
    closed: Boolean = false,
    zone: String = "",
    closedAt: Option[Instant] = None,
    registrations: Long = 0,
    testRegistrations: Long = 0,
    currencies: Map[String, CurrencyDay] = Map.empty,
    differences: Vector[Difference] = Vector.empty,
    openingCorrections: Vector[OpeningCorrection] = Vector.empty,
    corrections: Vector[Correction] = Vector.empty,
    hourRegistrations: Vector[Long] = Section.NoHours,
    hourTestRegistrations: Vector[Long] = Section.NoHours,
    feeDifferences: Vector[FeeDifference] = Vector.empty
):
  def state: DayState =
    DayState(closed, zone, closedAt, registrations, testRegistrations, currencies, differences, openingCorrections, corrections,
      hourRegistrations = hourRegistrations, hourTestRegistrations = hourTestRegistrations, feeDifferences = feeDifferences)

  /** The day's registrations to the end of hour `upTo` (23 for the whole day), without the test accounts' when asked. */
  def registrationsTo(upTo: Int, excludeTestAccounts: Boolean): Long =
    if upTo >= 23 then registrations - (if excludeTestAccounts then testRegistrations else 0L)
    else hourRegistrations.take(upTo + 1).sum - (if excludeTestAccounts then hourTestRegistrations.take(upTo + 1).sum else 0L)

object DayRow:
  def of(generation: Int, day: String, s: DayState): DayRow =
    DayRow(generation, day, s.closed, s.zone, s.closedAt, s.registrations, s.testRegistrations, s.currencies, s.differences,
      s.openingCorrections, s.corrections, s.hourRegistrations, s.hourTestRegistrations, s.feeDifferences)

  given Serializer[DayRow] = ReportingCodecs.row[DayRow]("day-row")

/**
 * Each `BrandDay` event applied to its row as the entity applies it to its state (`Day.apply`): the view sees every
 * event once and in order, so the row is the entity's state without asking it.
 */
final class DayRows extends View[DayEvent, DayRow]:
  def onChange(event: DayEvent): Effect =
    val subject = updateContext.subject
    Generation.of(subject).fold(effects.ignore()) { generation =>
      val before = rowState.fold(DayState())(_.state)
      effects.updateRow(DayRow.of(generation, Generation.rest(subject), Day.apply(before, event)))
    }

object DaysView extends View.Companion[DayRows, DayEvent, DayRow](
      componentId = ComponentId("days"),
      source = ChangeSource.eventsOf(BrandDay),
      rowSerializer = DayRow.given_Serializer_DayRow
    ):
  /** A generation's days from `from` to `to`, both ISO dates and inclusive (text order is date order). */
  val between = query("between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'day' >= :from AND payload::jsonb->>'day' <= :to ORDER BY payload::jsonb->>'day'")

  /** One row for each currency the generation holds figures in, so the currencies are read without reading every day. */
  val currencyRows = query("currencies")(
    s"SELECT DISTINCT ON (c) payload FROM $table, jsonb_object_keys(payload::jsonb->'currencies') AS c WHERE payload::jsonb->>'generation' = :generation ORDER BY c")

  /** A generation's days before `day` holding a correction: where the corrections that arrived during `day` are. */
  val correctedBefore = query("corrected-before")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'day' < :day AND jsonb_array_length(COALESCE(payload::jsonb->'corrections', '[]'::jsonb)) > 0 ORDER BY payload::jsonb->>'day'")

  def create(ctx: ViewComponentContext) = new DayRows

  /** One day's row, if a fact has reached it. */
  def row(views: ViewClient, generation: Int, day: LocalDate): Option[DayRow] = rows(views, generation, day, day).headOption

  def corrected(views: ViewClient, generation: Int, before: LocalDate): Vector[DayRow] =
    views.forView(DaysView).ask(correctedBefore, 100_000, "generation" -> generation.toString, "day" -> before.toString).toVector

  /** The period's rows; a day no fact has reached has none. */
  def rows(views: ViewClient, generation: Int, from: LocalDate, to: LocalDate): Vector[DayRow] =
    val limit = (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1).max(1L).min(100_000L).toInt
    views.forView(DaysView).ask(between, limit, "generation" -> generation.toString, "from" -> from.toString, "to" -> to.toString).toVector

  /** Every currency the generation holds figures in, in order. */
  def currencies(views: ViewClient, generation: Int): Vector[String] =
    views.forView(DaysView).ask(currencyRows, 1_000, "generation" -> generation.toString).flatMap(_.currencies.keySet).toVector.distinct.sorted

/** A period's money in one currency, one entry per reporting day, a day no fact reached at zero (FR-007). */
final case class Revenue(currency: String, days: Vector[(LocalDate, Money)]):
  def total: Money = days.foldLeft(Money())(_ + _._2)

object Revenue:
  /**
   * A day's money in `currency`: its section (every player's, or without the test accounts' when the brand excludes
   * them, FR-024) and every correction that arrived after its close, so a late fact counts on its own day and a
   * rebuild that closes a day before its last fact is read answers the same figures (SC-004).
   */
  def dayMoney(row: DayRow, currency: String, excludeTestAccounts: Boolean): Money =
    daySection(row, currency, excludeTestAccounts).money

  /** The day's whole section in `currency` as `dayMoney` counts it: its hours and providers with the corrections in. */
  def daySection(row: DayRow, currency: String, excludeTestAccounts: Boolean): Section =
    val fixed = row.currencies.get(currency).fold(Section())(_.section(excludeTestAccounts))
    row.corrections
      .filter(c => c.delta.currency == currency && !(excludeTestAccounts && c.delta.test))
      .foldLeft(fixed)(_ + _.delta)

  def of(rows: Seq[DayRow], from: LocalDate, to: LocalDate, currency: String, excludeTestAccounts: Boolean): Revenue =
    val byDay = rows.map(r => r.day -> r).toMap
    val days  = Iterator.iterate(from)(_.plusDays(1)).takeWhile(!_.isAfter(to)).toVector
    Revenue(currency, days.map(d => d -> byDay.get(d.toString).fold(Money())(dayMoney(_, currency, excludeTestAccounts))))
