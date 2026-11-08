package io.eitheror.casino.reporting.render

import io.eitheror.casino.reporting.day.{Money as DayMoney, Revenue}
import io.eitheror.casino.reporting.figures.Figures

import java.time.LocalDate

/**
 * The revenue report as CSV (FR-012; contracts/routes.md `GET /reports/ggr`, `format=csv`): the summary row, then one
 * row per reporting day, every column of both, in the original's quoting. The summary's date is the period as
 * `<from>/<to>`; a daily row carries the day's chargebacks, deposits and withdrawals too, which the screen sums.
 */
object RevenueCsv:
  val ContentType = "text/csv; charset=UTF-8"

  val Header: Vector[String] = Vector("date", "bets", "wins", "ggr", "depositPoolGgr", "bonusCosts", "chargebacks", "ngr",
    "deposits", "withdrawals", "netCashFlow", "currency", "ngrBasis")

  private def row(date: String, m: DayMoney, currency: String): Vector[String] =
    def s(v: Long) = Money(v, currency)
    Vector(date, s(m.stakes - m.rollbacks), s(m.wins), s(m.ggr), s(m.poolGgr), s(m.bonusCost), s(m.chargebacks), s(m.ngr),
      s(m.deposits), s(m.withdrawals), s(m.deposits - m.withdrawals), currency, Figures.NgrBasis)

  /** The summary row, then one row per day. */
  def rows(revenue: Revenue, from: LocalDate, to: LocalDate): Vector[Vector[String]] =
    row(s"$from/$to", revenue.total, revenue.currency) +: revenue.days.map((d, m) => row(d.toString, m, revenue.currency)).toVector

  def render(revenue: Revenue, from: LocalDate, to: LocalDate): String = Csv(Header, rows(revenue, from, to))
