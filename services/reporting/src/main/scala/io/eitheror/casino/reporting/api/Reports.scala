package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Personal
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.day.{DaysView, GatewayFigures, Money as DayMoney, PaymentFigures, Revenue}
import io.eitheror.casino.reporting.figures.Figures
import io.eitheror.casino.reporting.ranges.Ranges.Period
import io.eitheror.casino.reporting.reads.{GrantRow, Grants, Players, TransactionRow, Transactions, Wallets}
import io.eitheror.casino.reporting.render.Money

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

/** `GET /reports/players`: the original's row, the email `[erased]` after erasure, `""` for what is not known. */
final case class PlayerLine(playerId: String, email: String, country: String, registrationDate: String, status: String,
    kycStatus: String, lifetimeDeposits: String, lifetimeWagered: String, lifetimeGgr: String, lastLoginAt: String,
    isTestAccount: Boolean, currency: String)
final case class PlayersReport(from: String, to: String, currency: String, currencies: Vector[String], totalCount: Long,
    players: Vector[PlayerLine])

/** `GET /reports/transactions` and its export: the original's page and row. */
final case class TransactionLine(transactionId: String, timestamp: String, playerId: String, truncatedEmail: String,
    transactionType: String, amount: String, currency: String, reference: String)
final case class TransactionsPage(from: String, to: String, page: Int, pageSize: Int, totalCount: Long, totalPages: Int,
    transactions: Vector[TransactionLine])

/** `GET /reports/bonus`: the original's summary and offer rows; `conversionRatePct` the truncated whole percent. */
final case class BonusTotals(totalGrantsCount: Int, totalGrantedValue: String, convertedCount: Int, conversionRatePct: Int,
    forfeitedCount: Int, cancelledCount: Int, activeCount: Int, currency: String)
final case class BonusOffer(offerId: String, offerName: String, offerType: String, grantsCount: Int, totalGrantedValue: String,
    convertedCount: Int, convertedValue: String, forfeitedCount: Int, cancelledCount: Int, conversionRatePct: Int, currency: String)
final case class BonusReport(from: String, to: String, currency: String, currencies: Vector[String], summary: BonusTotals,
    offers: Vector[BonusOffer])

/** `GET /reports/psp`: figures where the original answered 97 and 0; a rate `null` with no attempts (FR-011). */
final case class PspTotals(depositAttempts: Long, completedDeposits: Long, depositValue: String, depositSuccessRatePct: Option[BigDecimal],
    withdrawalRequests: Long, completedWithdrawals: Long, avgWithdrawalHours: Option[BigDecimal], currency: String)
final case class PspLine(pspName: String, depositAttempts: Long, completedDeposits: Long, depositSuccessRatePct: Option[BigDecimal],
    depositValue: String, withdrawalCount: Long, withdrawalRequests: Long, avgWithdrawalHours: Option[BigDecimal], belowThreshold: Boolean)
final case class PspReport(from: String, to: String, currency: String, currencies: Vector[String], summary: PspTotals,
    pspBreakdown: Vector[PspLine])

object ReportCodecs:
  given JsonValueCodec[PlayersReport]    = Wire.answer[PlayersReport]
  given JsonValueCodec[TransactionsPage] = Wire.answer[TransactionsPage]
  given JsonValueCodec[BonusReport]      = Wire.answer[BonusReport]
  given JsonValueCodec[PspReport]        = Wire.answer[PspReport]

/**
 * The Reports tab's routes (FR-007–FR-012), behind its flag (FR-024). Each report's figures are read by a function of
 * `Reads` and the period, so its route and its export's job (`Exports`, research R9) read the same rows.
 */
object Reports:
  import ReportCodecs.given

  /** What a request asked for, read from its query before anything else is called. */
  final case class Asked(from: Option[String], to: Option[String], currency: Option[String], format: Option[String], operator: String):
    def csv: Boolean = format.exists(_.trim.equalsIgnoreCase("csv"))

  /** The ledger's own parameters (FR-010): the type filter, part of an email, the page and its size. */
  final case class Ledger(tpe: Option[String], playerEmail: Option[String], page: Option[String], pageSize: Option[String]):
    def filter: String = tpe.map(_.trim).filter(_.nonEmpty).getOrElse("all")
    def email: Option[String] = playerEmail.map(_.trim).filter(_.nonEmpty)
    def pageNo: Int = page.flatMap(_.trim.toIntOption).getOrElse(0).max(0)
    def size: Int   = pageSize.flatMap(_.trim.toIntOption).getOrElse(PageSize).max(1).min(PageSize)

  /** The ledger's page, as the original's and FR-010. */
  val PageSize = 100

  /** The ledger's truncated email: 25 characters and `…`, as the original. */
  val LedgerEmail = 25

  /** A promoter's `belowThreshold`: a success rate under 95% (FR-011). */
  val Threshold = BigDecimal(95)

  private def tab(ctx: Ctx)(body: => Answer): Answer = if !ctx.reporting.reportsTab then notFound else body

  /** The period asked for, refused before a topic's first complete day (FR-004). */
  private def periodOf(ctx: Ctx, asked: Asked, topics: Vector[String]): Either[Answer, Period] =
    for
      period <- Wire.period(asked.from, asked.to, ctx.today)
      _      <- Wire.complete(period, Completeness.from(ctx.components, ctx.settings, topics))
    yield period

  private def every(v: Option[String]): Option[String] = v.map(_.trim).filter(s => s.nonEmpty && s != "all")

  // ── revenue ───────────────────────────────────────────────────────────────────────────────────

  def revenue(r: Reads, period: Period, currency: String, excludeTestAccounts: Boolean): Revenue =
    Revenue.of(DaysView.rows(r.views, r.generation, period.from, period.to), period.from, period.to, currency, excludeTestAccounts)

  /**
   * `GET /reports/ggr`: the period's days in one currency, every player's or without the test accounts'; as CSV, the
   * summary row then the daily rows (FR-012, FR-021).
   */
  def ggr(ctx: Ctx, asked: Asked): Answer = tab(ctx) {
    val settings = ctx.reporting
    answered(for
      period <- periodOf(ctx, asked, Completeness.dayTopics(ctx.settings))
      chosen <- Currencies.choose(ctx, asked.currency, settings)
    yield
      if asked.csv then
        Exports.exported(ctx, asked, Exports.Ask(Exports.Ggr, period.from, period.to,
          Map("currency" -> chosen.currency, "excludeTestAccounts" -> settings.excludeTestAccounts.toString)))
      else
        ok(GgrReport.of(revenue(ctx.reads, period, chosen.currency, settings.excludeTestAccounts), period.from.toString,
          period.to.toString, chosen.currencies)))
  }

  // ── players ───────────────────────────────────────────────────────────────────────────────────

  /**
   * The player report's rows (FR-008): one per player registered in the period in the brand's zone, filtered by country
   * and status (`all` or none for every one), in registration order; the lifetimes are the wallet's in `currency`, every
   * player's in the same one as the original (the brand's). Sorting is the screen's.
   */
  def playerLines(r: Reads, period: Period, currency: String, country: Option[String], status: Option[String]): Vector[PlayerLine] =
    val all = Players.registered(r.views, r.generation, r.startOf(period.from), r.startOf(period.to.plusDays(1)))
      .filter(p => every(country).forall(p.country.contains))
      .filter(p => every(status).forall(_ == p.status))
    val wallets = Wallets.ofPlayers(r.views, r.generation, all.map(_.player)).filter(_.currency == currency).map(w => w.player -> w).toMap
    all.map { p =>
      val w = wallets.get(p.player)
      def m(v: Option[Long]) = Money(v.getOrElse(0L), currency)
      PlayerLine(p.player, p.shownEmail, p.country.getOrElse(""), p.registeredAt.fold("")(_.toString), p.status, p.kycStatus,
        m(w.map(_.deposits)), m(w.map(_.wagered)), m(w.map(_.ggr)), p.lastLoginAt.fold("")(_.toString), p.testAccount, currency)
    }

  def players(ctx: Ctx, asked: Asked, country: Option[String], status: Option[String]): Answer = tab(ctx) {
    answered(for
      period <- periodOf(ctx, asked, Vector(f.CasinoPlayers.name, f.MoneyTransactions.name))
      chosen <- Currencies.choose(ctx, asked.currency, ctx.reporting)
    yield
      val c = chosen.currency
      if asked.csv then
        Exports.exported(ctx, asked, Exports.Ask(Exports.PlayersReport, period.from, period.to,
          Map("currency" -> c) ++ every(country).map("country" -> _) ++ every(status).map("status" -> _)))
      else
        val lines = playerLines(ctx.reads, period, c, country, status)
        ok(PlayersReport(period.from.toString, period.to.toString, c, chosen.currencies, lines.size.toLong, lines)))
  }

  // ── the ledger ────────────────────────────────────────────────────────────────────────────────

  /** The players whose email holds `part` (case ignored; an erased email matches nothing). */
  def emailMatches(r: Reads, part: String): Vector[String] =
    val lower = part.toLowerCase
    Players.all(r.views, r.generation).filter(_.email match
      case Some(Personal.Present(_, e)) if e != null => e.toLowerCase.contains(lower)
      case _                                         => false).map(_.player)

  /** The ledger's rows (FR-010): every movement of the period in the brand's zone of `filter`, of `players` (all for none). */
  def ledgerAsked(r: Reads, period: Period, filter: String, players: Option[Iterable[String]]): Transactions.Asked =
    Transactions.Asked(r.generation, r.startOf(period.from), r.startOf(period.to.plusDays(1)), filter, players)

  private def ledgerAsked(ctx: Ctx, period: Period, ledger: Ledger): Transactions.Asked =
    ledgerAsked(ctx.reads, period, ledger.filter, ledger.email.map(emailMatches(ctx.reads, _)))

  def ledgerLines(r: Reads, rows: Vector[TransactionRow]): Vector[TransactionLine] =
    val players = Players.of(r.views, r.generation, rows.map(_.player).distinct)
    rows.map(t => TransactionLine(t.transactionId, t.at.toString, t.player, players.get(t.player).fold("")(_.truncatedEmail(LedgerEmail)),
      t.tpe, Money(t.amount, t.currency), t.currency, t.reference))

  private val LedgerTopics = Vector(f.MoneyTransactions.name)

  /** `GET /reports/transactions` (FR-010): a page of at most one hundred, every transaction counted. */
  def transactions(ctx: Ctx, asked: Asked, ledger: Ledger): Answer = tab(ctx) {
    answered(periodOf(ctx, asked, LedgerTopics).map { period =>
      val q     = ledgerAsked(ctx, period, ledger)
      val total = Transactions.count(ctx.views, q)
      val size  = ledger.size
      val page  = Transactions.page(ctx.views, q, ledger.pageNo * size, size)
      ok(TransactionsPage(period.from.toString, period.to.toString, ledger.pageNo, size, total,
        math.ceil(total.toDouble / size).toInt, ledgerLines(ctx.reads, page)))
    })
  }

  /**
   * `GET /reports/transactions/export` (FR-010, FR-012): every matching row, no cap; JSON as the original's page (page 0,
   * the size every row), streamed (`ledgerJson`), or `format=csv`, a job over the synchronous limit. An email search is handed on as the players it
   * matched, so neither the job nor the record keeps the text (`playerEmail: given` says one was given).
   */
  def transactionsExport(ctx: Ctx, asked: Asked, ledger: Ledger): Answer = tab(ctx) {
    answered(periodOf(ctx, asked, LedgerTopics).map { period =>
      if asked.csv then
        val ids = ledger.email.map(emailMatches(ctx.reads, _))
        Exports.exported(ctx, asked, Exports.Ask(Exports.TransactionsReport, period.from, period.to,
          Map("type" -> ledger.filter, Exports.Ids -> ids.fold("*")(_.mkString(","))) ++ ledger.email.map(_ => "playerEmail" -> "given")))
      else
        val q = ledgerAsked(ctx, period, ledger)
        streamed("application/json", ledgerJson(ctx.reads, q, period, Transactions.count(ctx.views, q)))
    })
  }

  private given JsonValueCodec[TransactionLine] = Wire.answer[TransactionLine]

  /**
   * The JSON export as the original's page (page 0, the size every row), written as its rows are read a page at a time
   * (`Transactions.pages`), so neither the service nor the response holds them all. The counts come first, in the
   * original's field order (`TransactionsPage`), from `Transactions.count` asked before the rows, as the original's page
   * counted: one query more over the period's rows, against the pages' own. A movement folded between the count and the
   * rows (a period ending today) is written and not counted, as in the original. A view failing partway fails the stream after the `200` is sent: ankka
   * writes the body chunked and aborts the connection without its last chunk, so the client reads an error, never a
   * shorter body that parses (`it.StreamedFailureSuite`).
   */
  def ledgerJson(r: Reads, asked: Transactions.Asked, period: Period, total: Long): java.io.InputStream =
    var written = 0L
    val head = s"""{"from":"${period.from}","to":"${period.to}","page":0,"pageSize":$total,"totalCount":$total,""" +
      s""""totalPages":${if total == 0 then 0 else 1},"transactions":["""
    val rows = Transactions.pages(r.views, asked).filter(_.nonEmpty).map { page =>
      val text = ledgerLines(r, page).map(l => com.github.plokhotnyuk.jsoniter_scala.core.writeToString(l)).mkString(",")
      val out  = (if written > 0 then "," else "") + text
      written += page.size
      out
    }
    Wire.concatenated((Iterator.single(head) ++ rows ++ Iterator.single("]}")).map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  // ── bonus ─────────────────────────────────────────────────────────────────────────────────────

  /** An offer's or the summary's counts over its grants. */
  private final case class Counted(grants: Vector[GrantRow]):
    def total: Int     = grants.size
    def converted: Int = grants.count(_.converted)
    def value: Long    = grants.map(_.amount).sum
    def convertedValue: Long = grants.filter(_.converted).map(_.amount).sum
    def of(status: String): Int = grants.count(_.status == status)
    def rate: Int      = Figures.conversionRatePct(converted, total)

  /** The bonus report's figures: the summary, the offers, and the summary's converted value, which the CSV shows. */
  final case class Bonus(summary: BonusTotals, offers: Vector[BonusOffer], convertedValue: String)

  /**
   * The grants granted in the period in `currency` (a free-rounds grant, which has none until it wins, counted in every
   * one), one row per offer by its id, its name and type from its grants; converted counts a grant converted whether its
   * cap is pending or closed (FR-009).
   */
  def bonusFigures(r: Reads, period: Period, currency: String, offer: Option[String]): Bonus =
    val c      = currency
    val grants = Grants.granted(r.views, r.generation, r.startOf(period.from), r.startOf(period.to.plusDays(1)), every(offer))
      .filter(_.currency.forall(_ == c))
    def m(v: Long) = Money(v, c)
    val offers = grants.groupBy(g => if g.offerId.nonEmpty then g.offerId else "unknown").toVector.sortBy(_._1).map { (id, gs) =>
      val n = Counted(gs)
      BonusOffer(id, gs.map(_.name).find(_.nonEmpty).getOrElse(id), gs.map(_.offerType).find(_.nonEmpty).getOrElse("UNKNOWN"),
        n.total, m(n.value), n.converted, m(n.convertedValue), n.of("forfeited"), n.of("cancelled"), n.rate, c)
    }
    val all = Counted(grants)
    Bonus(BonusTotals(all.total, m(all.value), all.converted, all.rate, all.of("forfeited"), all.of("cancelled"), all.of("active"), c),
      offers, m(all.convertedValue))

  /** `GET /reports/bonus` (FR-009). */
  def bonus(ctx: Ctx, asked: Asked, offer: Option[String]): Answer = tab(ctx) {
    answered(for
      period <- periodOf(ctx, asked, Option.when(ctx.settings.casino)(f.CasinoRewards.name).toVector)
      chosen <- Currencies.choose(ctx, asked.currency, ctx.reporting)
    yield
      if asked.csv then
        Exports.exported(ctx, asked, Exports.Ask(Exports.BonusReport, period.from, period.to,
          Map("currency" -> chosen.currency) ++ every(offer).map("offerId" -> _)))
      else
        val b = bonusFigures(ctx.reads, period, chosen.currency, offer)
        ok(BonusReport(period.from.toString, period.to.toString, chosen.currency, chosen.currencies, b.summary, b.offers)))
  }

  // ── payment providers ─────────────────────────────────────────────────────────────────────────

  /**
   * The period's deposit attempts, captures and value per payment provider and the withdrawal requests, completions and
   * hours per gateway, from the days' sections, every player's; a provider and a gateway of the same name are one row; a
   * rate with no attempts is `null`, and `belowThreshold` false for it (FR-011).
   */
  def pspFigures(r: Reads, period: Period, currency: String): (PspTotals, Vector[PspLine]) =
    val c        = currency
    val rows     = DaysView.rows(r.views, r.generation, period.from, period.to)
    val sections = rows.map(d => Revenue.daySection(d, c, excludeTestAccounts = false))
    val pays     = sections.flatMap(_.payments).groupMapReduce(_._1)(_._2)(_ + _)
    val gates    = sections.flatMap(_.gateways).groupMapReduce(_._1)(_._2)(_ + _)
    def m(v: Long) = Money(v, c)
    val names = (pays.keySet ++ gates.keySet).toVector.sorted
    val lines = names.map { n =>
      val p = pays.getOrElse(n, PaymentFigures())
      val w = gates.getOrElse(n, GatewayFigures())
      val rate = Figures.ratePct(p.completed, p.attempts)
      PspLine(n, p.attempts, p.completed, rate, m(p.value), w.completed, w.requests, Figures.averageHours(w.completionSeconds, w.timed),
        rate.exists(_ < Threshold))
    }
    val (p, w) = (pays.values.foldLeft(PaymentFigures())(_ + _), gates.values.foldLeft(GatewayFigures())(_ + _))
    (PspTotals(p.attempts, p.completed, m(p.value), Figures.ratePct(p.completed, p.attempts), w.requests, w.completed,
      Figures.averageHours(w.completionSeconds, w.timed), c), lines)

  /** `GET /reports/psp` (FR-011). */
  def psp(ctx: Ctx, asked: Asked): Answer = tab(ctx) {
    answered(for
      period <- periodOf(ctx, asked, Completeness.dayTopics(ctx.settings))
      chosen <- Currencies.choose(ctx, asked.currency, ctx.reporting)
    yield
      if asked.csv then Exports.exported(ctx, asked, Exports.Ask(Exports.PspReport, period.from, period.to, Map("currency" -> chosen.currency)))
      else
        val (summary, lines) = pspFigures(ctx.reads, period, chosen.currency)
        ok(PspReport(period.from.toString, period.to.toString, chosen.currency, chosen.currencies, summary, lines)))
  }
