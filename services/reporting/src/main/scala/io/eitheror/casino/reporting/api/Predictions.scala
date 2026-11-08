package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.reporting.day.{DaysView, MarketDay}
import io.eitheror.casino.reporting.predictions.{MarketFigures, MarketRow, Markets, Traders}
import io.eitheror.casino.reporting.ranges.Ranges.Period
import io.eitheror.casino.reporting.reads.Players
import io.eitheror.casino.reporting.render.Money

import java.time.Instant

import Wire.*

/** `GET /predictions/summary` (contracts/routes.md, *New routes*; FR-028). */
final case class PredictionsSummary(contractsTraded: Long, notionalVolume: String, trades: Long, traders: Long, takerFees: String,
    resolutionPayments: String, remaindersKept: String, marketsOpened: Long, marketsResolved: Long, marketsVoided: Long,
    marketsCorrected: Long)

/** A row of `GET /predictions/markets` (FR-029); `corrected` the outcome a correction moved the market to. */
final case class MarketLine(marketId: String, question: String, category: String, currency: String, status: String,
    openedAt: Option[String], closedAt: Option[String], resolvedAt: Option[String], resolution: Option[String],
    firstResolution: Option[String], corrected: Option[String], contractsTraded: Long, notionalVolume: String, trades: Long,
    traders: Long, openInterest: Long, takerFees: String, resolutionPayments: String, remainderKept: String)
final case class MarketsAnswer(markets: Vector[MarketLine])

/** A row of `GET /predictions/traders` (FR-030): the email as the player report shows it, `[erased]` after erasure. */
final case class TraderLine(playerId: String, email: String, contractsTraded: Long, notionalVolume: String, takerFees: String,
    positionsPaid: String, netResult: String)
final case class TradersAnswer(traders: Vector[TraderLine])

object PredictionsCodecs:
  given JsonValueCodec[PredictionsSummary] = Wire.answer[PredictionsSummary]
  given JsonValueCodec[MarketsAnswer]      = Wire.answer[MarketsAnswer]
  given JsonValueCodec[TradersAnswer]      = Wire.answer[TradersAnswer]

/**
 * The prediction markets' reports (US8; FR-028–FR-032): served only to a brand whose entitlement runs the predictions
 * module, `404 not_found` otherwise. The summary and the traders are per period and currency in the brand's zone, the
 * market report one row per market alive in the period, filterable by status and category. Their figures are the
 * markets' own folds and touch no casino figure.
 */
object Predictions:
  import PredictionsCodecs.given

  private def module(ctx: Ctx)(body: => Answer): Answer = if !ctx.settings.predictions then notFound else body

  private def every(v: Option[String]): Option[String] = v.map(_.trim).filter(s => s.nonEmpty && s != "all")

  private def iso(t: Option[Instant]) = t.map(_.toString)

  private def in(t: Option[Instant], from: Instant, to: Instant) = t.exists(x => !x.isBefore(from) && x.isBefore(to))

  // ── summary ───────────────────────────────────────────────────────────────────────────────────

  def summaryOf(r: Reads, period: Period, currency: String): PredictionsSummary =
    val (from, to) = (r.startOf(period.from), r.startOf(period.to.plusDays(1)))
    val days = DaysView.rows(r.views, r.generation, period.from, period.to)
    val day = days.flatMap(_.currencies.get(currency)).flatMap(_.predictions.values).foldLeft(MarketDay())(_ + _)
    val traders = Traders.rows(r.views, r.generation, currency, period.from, period.to).filter(_.contracts > 0).map(_.player).distinct
    val markets = Markets.all(r.views).filter(_.currency == currency)
    def m(v: Long) = Money(v, currency)
    PredictionsSummary(day.contracts, m(day.notional), day.trades, traders.size.toLong, m(day.fees), m(day.payments), m(day.remainder),
      markets.count(x => in(x.openedAt, from, to)).toLong, markets.count(x => in(x.resolvedAt, from, to)).toLong,
      markets.count(x => in(x.voidedAt, from, to)).toLong, markets.count(x => in(x.correctedAt, from, to)).toLong)

  /** `GET /predictions/summary`. */
  def summary(ctx: Ctx, asked: Reports.Asked): Answer = module(ctx) {
    answered(for
      period <- Wire.period(asked.from, asked.to, ctx.today)
      chosen <- Currencies.choose(ctx, asked.currency, ctx.reporting)
    yield ok(summaryOf(ctx.reads, period, chosen.currency)))
  }

  // ── markets ───────────────────────────────────────────────────────────────────────────────────

  def marketLines(r: Reads, period: Period, status: Option[String], category: Option[String]): Vector[MarketLine] =
    val (from, to) = (r.startOf(period.from), r.startOf(period.to.plusDays(1)))
    val rows = Markets.all(r.views).filter(x => x.during(from, to) && status.forall(_ == x.status) && category.forall(_ == x.category))
    val figures = MarketFigures.of(r.views, r.generation, rows.map(_.marketId))
    rows.map { (x: MarketRow) =>
      val f = figures.get(x.marketId)
      def m(v: Long) = Money(v, x.currency)
      MarketLine(x.marketId, x.question, x.category, x.currency, x.status, iso(x.openedAt), iso(x.closedAt), iso(x.resolvedAt),
        x.resolution, x.firstResolution, x.corrected, f.fold(0L)(_.contracts), m(f.fold(0L)(_.notional)), f.fold(0L)(_.trades),
        f.fold(0L)(_.traders), f.fold(0L)(_.openInterest), m(f.fold(0L)(_.fees)), m(f.fold(0L)(_.payments)), m(f.fold(0L)(_.remainder)))
    }

  val MarketHeader: Vector[String] = Vector("marketId", "question", "category", "currency", "status", "openedAt", "closedAt",
    "resolvedAt", "resolution", "firstResolution", "corrected", "contractsTraded", "notionalVolume", "trades", "traders",
    "openInterest", "takerFees", "resolutionPayments", "remainderKept")

  def marketRows(r: Reads, period: Period, status: Option[String], category: Option[String]): Vector[Vector[String]] =
    marketLines(r, period, status, category).map(l => Vector(l.marketId, l.question, l.category, l.currency, l.status,
      l.openedAt.getOrElse(""), l.closedAt.getOrElse(""), l.resolvedAt.getOrElse(""), l.resolution.getOrElse(""),
      l.firstResolution.getOrElse(""), l.corrected.getOrElse(""), l.contractsTraded.toString, l.notionalVolume, l.trades.toString,
      l.traders.toString, l.openInterest.toString, l.takerFees, l.resolutionPayments, l.remainderKept))

  /** `GET /predictions/markets`: open interest is what is held when it is read. */
  def markets(ctx: Ctx, asked: Reports.Asked, status: Option[String], category: Option[String]): Answer = module(ctx) {
    answered(Wire.period(asked.from, asked.to, ctx.today).map { period =>
      val (s, c) = (every(status), every(category))
      if asked.csv then
        Exports.exported(ctx, asked, Exports.Ask(Exports.MarketsReport, period.from, period.to,
          s.map("status" -> _).toMap ++ c.map("category" -> _)))
      else ok(MarketsAnswer(marketLines(ctx.reads, period, s, c)))
    })
  }

  // ── traders ───────────────────────────────────────────────────────────────────────────────────

  def traderLines(r: Reads, period: Period, currency: String): Vector[TraderLine] =
    val rows   = Traders.rows(r.views, r.generation, currency, period.from, period.to)
    val people = Players.of(r.views, r.generation, rows.map(_.player).distinct)
    def m(v: Long) = Money(v, currency)
    rows.groupBy(_.player).toVector.sortBy(_._1).map { (player, rs) =>
      TraderLine(player, people.get(player).fold("")(_.shownEmail), rs.map(_.contracts).sum, m(rs.map(_.notional).sum),
        m(rs.map(_.fees).sum), m(rs.map(_.positionsPaid).sum), m(rs.map(_.net).sum))
    }

  val TraderHeader: Vector[String] =
    Vector("playerId", "email", "contractsTraded", "notionalVolume", "takerFees", "positionsPaid", "netResult")

  def traderRows(r: Reads, period: Period, currency: String): Vector[Vector[String]] =
    traderLines(r, period, currency).map(l => Vector(l.playerId, l.email, l.contractsTraded.toString, l.notionalVolume, l.takerFees,
      l.positionsPaid, l.netResult))

  /** `GET /predictions/traders`. */
  def traders(ctx: Ctx, asked: Reports.Asked): Answer = module(ctx) {
    answered(for
      period <- Wire.period(asked.from, asked.to, ctx.today)
      chosen <- Currencies.choose(ctx, asked.currency, ctx.reporting)
    yield
      if asked.csv then
        Exports.exported(ctx, asked, Exports.Ask(Exports.TradersReport, period.from, period.to, Map("currency" -> chosen.currency)))
      else ok(TradersAnswer(traderLines(ctx.reads, period, chosen.currency))))
  }
