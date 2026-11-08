package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.reporting.day.{DayRow, DaysView, Money as DayMoney, Revenue}
import io.eitheror.casino.reporting.documents.{Document, DocumentState}
import io.eitheror.casino.reporting.figures.Figures
import io.eitheror.casino.reporting.render.{Money, RevenueCsv}
import io.eitheror.casino.reporting.settings.ReportingSettingsState

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import Wire.*

/** The revenue report's answer (contracts/routes.md `GET /reports/ggr`): the original's names, money as its strings. */
final case class GgrSummary(totalBets: String, totalWins: String, ggr: String, depositPoolGgr: String, bonusCosts: String,
    chargebacks: String, ngr: String, ngrBasis: String, totalDeposits: String, totalWithdrawals: String, netCashFlow: String)

final case class GgrDay(date: String, bets: String, wins: String, ggr: String, depositPoolGgr: String, bonusCosts: String, ngr: String)

final case class GgrReport(from: String, to: String, currency: String, currencies: Vector[String], summary: GgrSummary,
    dailyBreakdown: Vector[GgrDay])

object GgrReport:
  given JsonValueCodec[GgrReport] = Wire.answer[GgrReport]

  /** Bets are stakes less what rollbacks returned, so `ggr = bets − wins` on the screen as in the original. */
  def of(revenue: Revenue, from: String, to: String, currencies: Vector[String]): GgrReport =
    val c = revenue.currency
    def s(v: Long) = Money(v, c)
    val t = revenue.total
    GgrReport(from, to, c, currencies,
      GgrSummary(s(t.stakes - t.rollbacks), s(t.wins), s(t.ggr), s(t.poolGgr), s(t.bonusCost), s(t.chargebacks), s(t.ngr),
        Figures.NgrBasis, s(t.deposits), s(t.withdrawals), s(t.deposits - t.withdrawals)),
      revenue.days.map((d, m: DayMoney) =>
        GgrDay(d.toString, s(m.stakes - m.rollbacks), s(m.wins), s(m.ggr), s(m.poolGgr), s(m.bonusCost), s(m.ngr))))

/** The Reports tab's routes (FR-007–FR-012), behind its flag (FR-024). */
object Reports:
  /** What a request asked for, read from its query before anything else is called. */
  final case class Asked(from: Option[String], to: Option[String], currency: Option[String], format: Option[String], operator: String):
    def csv: Boolean = format.exists(_.trim.equalsIgnoreCase("csv"))

  /**
   * The currency a report opens in with none asked: the brand's setting, else the currency held on most of the
   * period's days (ties by name), else the first the brand holds, else the original's `EUR`. The wallets' count (R6)
   * replaces the days' when the `Wallets` view is built (US2).
   */
  def defaultCurrency(settings: ReportingSettingsState, held: Vector[String], rows: Seq[DayRow]): String =
    settings.defaultCurrency.getOrElse {
      val onDays = rows.flatMap(_.currencies.keys).groupBy(identity).view.mapValues(_.size).toVector
      onDays.sortBy((c, n) => (-n, c)).headOption.map(_._1).orElse(held.headOption).getOrElse("EUR")
    }

  /**
   * `GET /reports/ggr`: the period's days in one currency, every player's or without the test accounts'; as CSV, the
   * summary row then the daily rows, kept under `reports/report-export/<reference>.csv` with a `Document` record and
   * served as an attachment carrying the reference (FR-012, FR-021).
   */
  def ggr(ctx: Ctx, asked: Asked): Answer =
    val settings = ctx.reporting
    if !settings.reportsTab then notFound
    else
      answered(for
        period <- Wire.period(asked.from, asked.to, ctx.today)
        _      <- Wire.complete(period, Completeness.from(ctx.components, ctx.settings, Completeness.dayTopics(ctx.settings)))
        rows    = DaysView.rows(ctx.views, ctx.settings.generation, period.from, period.to)
        held    = DaysView.currencies(ctx.views, ctx.settings.generation)
        c      <- Wire.currency(asked.currency, held, settings.defaultCurrency, defaultCurrency(settings, held, rows))
      yield
        val revenue = Revenue.of(rows, period.from, period.to, c, settings.excludeTestAccounts)
        if asked.csv then exported(ctx, asked, revenue, period.from.toString, period.to.toString)
        else ok(GgrReport.of(revenue, period.from.toString, period.to.toString, (held ++ settings.defaultCurrency).distinct.sorted)))

  /** The synchronous document path (research R9): rendered, kept, recorded, served. */
  private def exported(ctx: Ctx, asked: Asked, revenue: Revenue, from: String, to: String): Answer =
    val reference = UUID.randomUUID().toString
    val name      = Document.objectName(Document.ReportExport, reference, "csv")
    val body      = RevenueCsv.render(revenue, java.time.LocalDate.parse(from), java.time.LocalDate.parse(to)).getBytes(UTF_8)
    val at        = ctx.now()
    ctx.clients.storage.default.putBytes(name, body, RevenueCsv.ContentType)
    ctx.components.forKeyValueEntity(EntityId(reference)).call(Document.ready).invoke(DocumentState(
      reference = reference, kind = Document.ReportExport, format = "csv",
      parameters = Map("report" -> "ggr", "currency" -> revenue.currency) ++ asked.currency.map("asked" -> _),
      from = Some(from), to = Some(to), askedBy = asked.operator, generatedAt = Some(at), readyAt = Some(at),
      objectName = Some(name), rows = revenue.days.size + 1L)): Unit
    attachment(RevenueCsv.ContentType, s"ggr-report-$reference.csv", body)
