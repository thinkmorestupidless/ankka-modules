package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.reporting.day.{DayRow, DaysView, Flow, MarketDay, Revenue, Section}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.figures.Figures
import io.eitheror.casino.reporting.intake.{OpenRecord, Sessions as SessionKinds}
import io.eitheror.casino.reporting.predictions.Traders
import io.eitheror.casino.reporting.ranges.Ranges
import io.eitheror.casino.reporting.reads.{Activity, PlayerDays, Players, Rounds, Sessions, Wallets}
import io.eitheror.casino.reporting.render.Money
import io.eitheror.casino.reporting.settings.ReportingSettingsState

import java.time.{Instant, LocalDate}

import Wire.*

// ── the answers, in the original's names and shapes (contracts/routes.md, `C/DashboardRoutes.scala`) ──────────────────

final case class KpiCount(value: Long, yesterday: Long, trend: String)
final case class KpiAmount(value: String, yesterday: String, trend: String, currency: String)
final case class KpiFlow(amount: String, count: Long, yesterdayAmount: String, trend: String, currency: String)

final case class Kpis(activeSessions: KpiCount, activeGames: KpiCount, playersOnline: KpiCount, ggrToday: KpiAmount,
    ngrToday: KpiAmount, depositsToday: KpiFlow, withdrawalsToday: KpiFlow, registrationsToday: KpiCount, currency: String,
    currencies: Vector[String])

final case class TrendPoint(label: String, ggr: String, ngr: String)
final case class Trend(range: String, currency: String, currencies: Vector[String], dataPoints: Vector[TrendPoint])

final case class HistoryDay(label: String, date: String, ggr: String, ngr: String, depositAmount: String, depositCount: Long,
    withdrawalAmount: String, withdrawalCount: Long, registrations: Long)
final case class KpiHistory(currency: String, currencies: Vector[String], days: Vector[HistoryDay])

final case class CountryCount(countryCode: String, playerCount: Long)
final case class Geo(countries: Vector[CountryCount])

final case class RankedGame(gameId: String, gameName: String, provider: String, sessionCount: Long)
final case class RankedPlayer(playerId: String, truncatedEmail: String, countryCode: String, ggrAmount: String, totalWagered: String,
    currency: String)
final case class Rankings(range: String, currency: String, currencies: Vector[String], games: Vector[RankedGame],
    players: Vector[RankedPlayer])

final case class ActivityEvent(eventId: String, eventType: String, playerId: String, truncatedEmail: String,
    amountMinorUnits: Option[Long], currency: Option[String], occurredAt: String)
final case class ActivityFeed(events: Vector[ActivityEvent])

/** Both field-name sets of the original (FR-015): amounts as its strings, and in minor units, which the backoffice reads. */
final case class BonusSummary(activeBonusCount: Long, totalLiability: String, bonusCostToday: String, totalLiabilityMinorUnits: Long,
    bonusCostTodayMinorUnits: Long, avgWageringCompletionPct: Int, currency: String, currencies: Vector[String])

/**
 * `depositVolume` is a 2-place string as every amount here, where the original answered a JSON number (decided
 * 2026-11-08; the backoffice's payment-health panel must parse it).
 */
final case class PspHealth(pspName: String, depositVolume: String, depositCount: Long, successRatePct: Option[BigDecimal])
final case class PaymentHealth(depositSuccessRatePct: Option[BigDecimal], avgWithdrawalHours: Option[BigDecimal], currency: String,
    currencies: Vector[String], pspBreakdown: Vector[PspHealth])

/** Today's prediction markets beside the casino's (FR-028), each figure in the KPIs' shape. Only with the module. */
final case class DashboardPredictions(currency: String, currencies: Vector[String], contractsTraded: KpiCount, notionalVolume: KpiAmount,
    trades: KpiCount, traders: KpiCount, takerFees: KpiAmount)

object DashboardCodecs:
  given JsonValueCodec[Kpis]          = Wire.answer[Kpis]
  given JsonValueCodec[Trend]         = Wire.answer[Trend]
  given JsonValueCodec[KpiHistory]    = Wire.answer[KpiHistory]
  given JsonValueCodec[Geo]           = Wire.answer[Geo]
  given JsonValueCodec[Rankings]      = Wire.answer[Rankings]
  given JsonValueCodec[ActivityFeed]  = Wire.answer[ActivityFeed]
  given JsonValueCodec[BonusSummary]  = Wire.answer[BonusSummary]
  given JsonValueCodec[PaymentHealth] = Wire.answer[PaymentHealth]
  given JsonValueCodec[DashboardPredictions] = Wire.answer[DashboardPredictions]

/**
 * The dashboard's eight routes (US2; FR-013–FR-015), every figure the brand's from views, never a sample: today and
 * yesterday from `Days` in the brand's zone, sessions from `Sessions`, players from `Players`, rankings from
 * `PlayerDays` and `Sessions`, the feed from `Activity`, the liability from `Wallets`. Never hidden by the Reports
 * tab's flag (FR-024); a brand that excludes test accounts has them left out of every figure here.
 */
object Dashboard:
  import DashboardCodecs.given

  /** What a request is read against: the brand's settings, today and its hour in the zone, the test accounts to leave out. */
  private final case class Now(settings: ReportingSettingsState, today: LocalDate, hour: Int, leaveOut: Set[String]):
    def exclude: Boolean = settings.excludeTestAccounts

  private def now(ctx: Ctx): Now =
    val s = ctx.reporting
    Now(s, ctx.today, Days.hourOf(ctx.now(), ctx.settings.reportingZone),
      if s.excludeTestAccounts then Players.tests(ctx.views, ctx.settings.generation) else Set.empty)

  private def g(ctx: Ctx): Int = ctx.settings.generation

  private def startOf(ctx: Ctx, day: LocalDate): Instant = day.atStartOfDay(ctx.settings.reportingZone).toInstant

  private def days(ctx: Ctx, from: LocalDate, to: LocalDate): Map[LocalDate, DayRow] =
    DaysView.rows(ctx.views, g(ctx), from, to).map(r => LocalDate.parse(r.day) -> r).toMap

  private def section(rows: Map[LocalDate, DayRow], day: LocalDate, currency: String, exclude: Boolean): Section =
    rows.get(day).fold(Section())(Revenue.daySection(_, currency, exclude))

  private def flowTo(hours: Vector[Flow], upTo: Int): Flow = hours.take(upTo + 1).foldLeft(Flow())(_ + _)

  // ── kpis ──────────────────────────────────────────────────────────────────────────────────────

  /**
   * `GET /dashboard/kpis` (FR-013): sessions open now (`activeSessions` the alias of `activeGames`, yesterday 0 and
   * flat, as the original); today's GGR, NGR, deposits, withdrawals and registrations against yesterday's to the same
   * hour of the zone, each with the original's trend arrow.
   */
  def kpis(ctx: Ctx, currency: Option[String]): Answer =
    val n = now(ctx)
    answered(Currencies.choose(ctx, currency, n.settings).map { chosen =>
      val c     = chosen.currency
      val rows  = days(ctx, n.today.minusDays(1), n.today)
      val today = section(rows, n.today, c, n.exclude)
      val yest  = section(rows, n.today.minusDays(1), c, n.exclude)
      def m(v: Long) = Money(v, c)
      def amount(t: Long, y: Long) = KpiAmount(m(t), m(y), Figures.trend(t, y), c)
      def flow(t: Flow, y: Flow)   = KpiFlow(m(t.amount), t.count, m(y.amount), Figures.trend(t.amount, y.amount), c)
      val games   = Sessions.openCount(ctx.views, g(ctx), SessionKinds.Game, n.leaveOut)
      val players = Sessions.openCount(ctx.views, g(ctx), SessionKinds.Player, n.leaveOut)
      val regsT   = rows.get(n.today).fold(0L)(_.registrationsTo(23, n.exclude))
      val regsY   = rows.get(n.today.minusDays(1)).fold(0L)(_.registrationsTo(n.hour, n.exclude))
      ok(Kpis(
        activeSessions = KpiCount(games, 0, "flat"),
        activeGames = KpiCount(games, 0, "flat"),
        playersOnline = KpiCount(players, 0, "flat"),
        ggrToday = amount(today.money.ggr, yest.hourGgr.take(n.hour + 1).sum),
        ngrToday = amount(today.money.ngr, yest.hourNgr.take(n.hour + 1).sum),
        depositsToday = flow(Flow(today.money.deposits, today.money.depositCount), flowTo(yest.hourDeposits, n.hour)),
        withdrawalsToday = flow(Flow(today.money.withdrawals, today.money.withdrawalCount), flowTo(yest.hourWithdrawals, n.hour)),
        registrationsToday = KpiCount(regsT, regsY, Figures.trend(regsT, regsY)),
        currency = c, currencies = chosen.currencies))
    })

  // ── trend and history ─────────────────────────────────────────────────────────────────────────

  /**
   * `GET /dashboard/trend` (FR-014): today by the hour to the current one (`HH:00`); seven and thirty days by the day
   * (`YYYY-MM-DD`); ninety by the 13 weeks ending today (`YYYY-Www`); each span's GGR and NGR in the brand's zone.
   */
  def trend(ctx: Ctx, range: Option[String], currency: Option[String]): Answer =
    val n = now(ctx)
    val r = Ranges.range(range)
    answered(Currencies.choose(ctx, currency, n.settings).map { chosen =>
      val c = chosen.currency
      def m(v: Long) = Money(v, c)
      val points =
        if r == Ranges.Today then
          val today = section(days(ctx, n.today, n.today), n.today, c, n.exclude)
          (0 to n.hour).toVector.map(h => TrendPoint(Ranges.hourLabel(h), m(today.hourGgr(h)), m(today.hourNgr(h))))
        else
          val buckets = Ranges.buckets(r, n.today).toVector
          val rows    = days(ctx, buckets.head.from, buckets.last.to)
          buckets.map { b =>
            val money = Ranges.Period(b.from, b.to).days.map(d => section(rows, d, c, n.exclude).money)
            TrendPoint(b.label, m(money.map(_.ggr).sum), m(money.map(_.ngr).sum))
          }
      ok(Trend(r, c, chosen.currencies, points))
    })

  /** `GET /dashboard/kpi-history` (FR-014): the seven days to today, `Today` last, each day's money and registrations. */
  def history(ctx: Ctx, currency: Option[String]): Answer =
    val n = now(ctx)
    answered(Currencies.choose(ctx, currency, n.settings).map { chosen =>
      val c       = chosen.currency
      val history = Ranges.historyDays(n.today).toVector
      val rows    = days(ctx, history.head._2, n.today)
      def m(v: Long) = Money(v, c)
      ok(KpiHistory(c, chosen.currencies, history.map { (label, day) =>
        val s = section(rows, day, c, n.exclude).money
        HistoryDay(label, day.toString, m(s.ggr), m(s.ngr), m(s.deposits), s.depositCount, m(s.withdrawals), s.withdrawalCount,
          rows.get(day).fold(0L)(_.registrationsTo(23, n.exclude)))
      }))
    })

  // ── players ───────────────────────────────────────────────────────────────────────────────────

  /** `GET /dashboard/geo` (FR-014): every player of the brand with a country, counted by it, most first. */
  def geo(ctx: Ctx): Answer =
    val n = now(ctx)
    val counts = Players.all(ctx.views, g(ctx)).filterNot(p => n.leaveOut(p.player))
      .flatMap(_.country.filter(_.nonEmpty)).groupMapReduce(identity)(_ => 1L)(_ + _)
    ok(Geo(counts.toVector.sortBy((cc, count) => (-count, cc)).map(CountryCount.apply)))

  /**
   * `GET /dashboard/rankings` (FR-014): over the range's days in the zone, the ten games with most game sessions
   * started, named by their latest round; and the ten players with the highest GGR, with country, the email cut at 20
   * and total wagered (stakes less rollbacks), every player of the brand counted.
   */
  def rankings(ctx: Ctx, range: Option[String], currency: Option[String]): Answer =
    val n = now(ctx)
    val r = Ranges.range(range)
    answered(Currencies.choose(ctx, currency, n.settings).map { chosen =>
      val c      = chosen.currency
      val period = Ranges.periodOf(r, n.today)
      val starts = Sessions.gameStarts(ctx.views, g(ctx), startOf(ctx, period.from), startOf(ctx, period.to.plusDays(1)))
        .filterNot(s => n.leaveOut(s.player))
      val games = starts.groupMapReduce(_.gameId.getOrElse(""))(_ => 1L)(_ + _).toVector.sortBy((id, count) => (-count, id)).take(10)
        .map { (id, count) =>
          val named = Rounds.latest(ctx.views, g(ctx), id).flatMap(_.provider)
          RankedGame(id, named.flatMap(_.gameName).getOrElse(""), named.fold("")(_.provider), count)
        }
      val top = PlayerDays.top(ctx.views, g(ctx), c, period.from, period.to, n.leaveOut, 10).map(d => d.player -> (d.ggr, d.wagered))
      val players = Players.of(ctx.views, g(ctx), top.map(_._1))
      ok(Rankings(r, c, chosen.currencies, games, top.map { (p, s) =>
        val row = players.get(p)
        RankedPlayer(p, row.fold("")(_.truncatedEmail(20)), row.flatMap(_.country).getOrElse(""), Money(s._1, c), Money(s._2, c), c)
      }))
    })

  /** `GET /dashboard/activity` (FR-014): today's registrations, awards, self-exclusions and suspensions, newest first, 50. */
  def activity(ctx: Ctx): Answer =
    val n       = now(ctx)
    val entries = Activity.from(ctx.views, g(ctx), startOf(ctx, n.today), 50 + n.leaveOut.size).filterNot(e => n.leaveOut(e.player)).take(50)
    val players = Players.of(ctx.views, g(ctx), entries.map(_.player).distinct)
    ok(ActivityFeed(entries.map(e =>
      ActivityEvent(e.eventId, e.eventType, e.player, players.get(e.player).fold("")(_.truncatedEmail(20)), None, None,
        e.occurredAt.toString))))

  // ── bonus and payments ────────────────────────────────────────────────────────────────────────

  /**
   * `GET /dashboard/bonus-summary` (FR-015): the open restricted funds records of the currency's wallets, those `ACTIVE`
   * counted, every open one's balance the liability, the mean progress over the active ones with a requirement, and
   * today's bonus cost; under both field-name sets.
   */
  def bonusSummary(ctx: Ctx, currency: Option[String]): Answer =
    val n = now(ctx)
    answered(Currencies.choose(ctx, currency, n.settings).map { chosen =>
      val c       = chosen.currency
      val records = Wallets.open(ctx.views, g(ctx), c).filterNot(w => n.leaveOut(w.player)).flatMap(_.records.values)
      val active  = records.filter(_.status == "ACTIVE")
      val cost    = section(days(ctx, n.today, n.today), n.today, c, n.exclude).money.bonusCost
      val owed    = records.map(_.balance).sum
      ok(BonusSummary(active.size.toLong, Money(owed, c), Money(cost, c), owed, cost,
        Figures.averageProgressPct(active.map((r: OpenRecord) => (r.progress, r.requirement))), c, chosen.currencies))
    })

  /**
   * `GET /dashboard/payment-health` (FR-015): today's deposit attempts and captures per payment provider, the success
   * rate over them (`null` with no attempts), and the mean hours from request to completion of today's withdrawals.
   */
  def paymentHealth(ctx: Ctx, currency: Option[String]): Answer =
    val n = now(ctx)
    answered(Currencies.choose(ctx, currency, n.settings).map { chosen =>
      val c     = chosen.currency
      val today = section(days(ctx, n.today, n.today), n.today, c, n.exclude)
      val pay   = today.payments.toVector.sortBy(_._1)
      val gates = today.gateways.values
      ok(PaymentHealth(
        Figures.ratePct(pay.map(_._2.completed).sum, pay.map(_._2.attempts).sum),
        Figures.averageHours(gates.map(_.completionSeconds).sum, gates.map(_.timed).sum),
        c, chosen.currencies,
        pay.map((name, p) => PspHealth(name, Money(p.value, c), p.completed, Figures.ratePct(p.completed, p.attempts)))))
    })

  // ── predictions ───────────────────────────────────────────────────────────────────────────────

  /**
   * `GET /dashboard/predictions` (FR-028): today's contracts traded, notional volume, trades, distinct traders and taker
   * fees against yesterday's to the same hour of the zone, each with the trend arrow, as the KPIs. `404 not_found`
   * unless the brand runs the predictions module; no other dashboard route holds a prediction figure (FR-032).
   */
  def predictions(ctx: Ctx, currency: Option[String]): Answer =
    if !ctx.settings.predictions then notFound
    else
      val n = now(ctx)
      answered(Currencies.choose(ctx, currency, n.settings).map { chosen =>
        val c    = chosen.currency
        val yd   = n.today.minusDays(1)
        val rows = days(ctx, yd, n.today)
        val today = rows.get(n.today).flatMap(_.currencies.get(c)).fold(MarketDay())(_.predictionsTo(23))
        val yest  = rows.get(yd).flatMap(_.currencies.get(c)).fold(MarketDay())(_.predictionsTo(n.hour))
        val zone  = ctx.settings.reportingZone
        val traded = Traders.rows(ctx.views, g(ctx), c, yd, n.today).filter(_.contracts > 0)
        val tradersT = traded.filter(_.day == n.today.toString).map(_.player).distinct.size.toLong
        val tradersY = traded.filter(r => r.day == yd.toString && r.firstTraded.forall(Days.hourOf(_, zone) <= n.hour))
          .map(_.player).distinct.size.toLong
        def count(t: Long, y: Long)  = KpiCount(t, y, Figures.trend(t, y))
        def amount(t: Long, y: Long) = KpiAmount(Money(t, c), Money(y, c), Figures.trend(t, y), c)
        ok(DashboardPredictions(c, chosen.currencies, count(today.contracts, yest.contracts), amount(today.notional, yest.notional),
          count(today.trades, yest.trades), count(tradersT, tradersY), amount(today.fees, yest.fees)))
      })
