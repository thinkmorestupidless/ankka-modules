package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{EntityId, Personal}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.day.{DaysView, GatewayFigures, Money as DayMoney, PaymentFigures, Revenue}
import io.eitheror.casino.reporting.documents.{Document, DocumentState}
import io.eitheror.casino.reporting.figures.Figures
import io.eitheror.casino.reporting.ranges.Ranges.Period
import io.eitheror.casino.reporting.reads.{GrantRow, Grants, Players, TransactionRow, Transactions, Wallets}
import io.eitheror.casino.reporting.render.{Csv, Money, RevenueCsv}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
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

/** The Reports tab's routes (FR-007–FR-012), behind its flag (FR-024). */
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

  private def startOf(ctx: Ctx, day: java.time.LocalDate): Instant = day.atStartOfDay(ctx.settings.reportingZone).toInstant

  /** The period asked for, refused before a topic's first complete day (FR-004). */
  private def periodOf(ctx: Ctx, asked: Asked, topics: Vector[String]): Either[Answer, Period] =
    for
      period <- Wire.period(asked.from, asked.to, ctx.today)
      _      <- Wire.complete(period, Completeness.from(ctx.components, ctx.settings, topics))
    yield period

  // ── revenue ───────────────────────────────────────────────────────────────────────────────────

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
      val rows    = DaysView.rows(ctx.views, ctx.settings.generation, period.from, period.to)
      val revenue = Revenue.of(rows, period.from, period.to, chosen.currency, settings.excludeTestAccounts)
      if asked.csv then
        exported(ctx, asked, "ggr", period, Map("currency" -> revenue.currency),
          RevenueCsv.render(revenue, period.from, period.to), revenue.days.size + 1L, Vector.empty)
      else ok(GgrReport.of(revenue, period.from.toString, period.to.toString, chosen.currencies)))
  }

  // ── players ───────────────────────────────────────────────────────────────────────────────────

  /**
   * `GET /reports/players` (FR-008): one row per player registered in the period in the brand's zone, filtered by
   * country and status (`all` or none for every one), in registration order; the lifetimes are the wallet's in the
   * currency answered, every player's in the same one as the original (the brand's). Sorting is the screen's.
   */
  def players(ctx: Ctx, asked: Asked, country: Option[String], status: Option[String]): Answer = tab(ctx) {
    val g = ctx.settings.generation
    answered(for
      period <- periodOf(ctx, asked, Vector(f.CasinoPlayers.name, f.MoneyTransactions.name))
      chosen <- Currencies.choose(ctx, asked.currency, ctx.reporting)
    yield
      def every(v: Option[String]) = v.map(_.trim).filter(s => s.nonEmpty && s != "all")
      val c   = chosen.currency
      val all = Players.registered(ctx.views, g, startOf(ctx, period.from), startOf(ctx, period.to.plusDays(1)))
        .filter(p => every(country).forall(p.country.contains))
        .filter(p => every(status).forall(_ == p.status))
      val wallets = Wallets.ofPlayers(ctx.views, g, all.map(_.player)).filter(_.currency == c).map(w => w.player -> w).toMap
      val lines = all.map { p =>
        val w = wallets.get(p.player)
        def m(v: Option[Long]) = Money(v.getOrElse(0L), c)
        PlayerLine(p.player, p.shownEmail, p.country.getOrElse(""), p.registeredAt.fold("")(_.toString), p.status, p.kycStatus,
          m(w.map(_.deposits)), m(w.map(_.wagered)), m(w.map(_.ggr)), p.lastLoginAt.fold("")(_.toString), p.testAccount, c)
      }
      if asked.csv then
        exported(ctx, asked, "players", period, Map("currency" -> c) ++ every(country).map("country" -> _) ++
          every(status).map("status" -> _), playersCsv(lines), lines.size.toLong, lines.map(_.playerId))
      else ok(PlayersReport(period.from.toString, period.to.toString, c, chosen.currencies, lines.size.toLong, lines)))
  }

  private val PlayerHeader = Vector("playerId", "email", "country", "registrationDate", "status", "kycStatus", "lifetimeDeposits",
    "lifetimeWagered", "lifetimeGgr", "lastLoginAt", "isTestAccount", "currency")

  private def playersCsv(lines: Vector[PlayerLine]): String =
    Csv(PlayerHeader, lines.map(l => Vector(l.playerId, l.email, l.country, l.registrationDate, l.status, l.kycStatus,
      l.lifetimeDeposits, l.lifetimeWagered, l.lifetimeGgr, l.lastLoginAt, l.isTestAccount.toString, l.currency)))

  // ── the ledger ────────────────────────────────────────────────────────────────────────────────

  /**
   * The ledger's rows (FR-010): every movement of the period in the brand's zone of the type asked, of the players
   * whose email holds `playerEmail` (case ignored; an erased email matches nothing), newest first.
   */
  private def ledgerAsked(ctx: Ctx, period: Period, ledger: Ledger): Transactions.Asked =
    val g = ctx.settings.generation
    val players = ledger.email.map { part =>
      val lower = part.toLowerCase
      Players.all(ctx.views, g).filter(_.email match
        case Some(Personal.Present(_, e)) if e != null => e.toLowerCase.contains(lower)
        case _                                         => false).map(_.player)
    }
    Transactions.Asked(g, startOf(ctx, period.from), startOf(ctx, period.to.plusDays(1)), ledger.filter, players)

  private def lines(ctx: Ctx, rows: Vector[TransactionRow]): Vector[TransactionLine] =
    val players = Players.of(ctx.views, ctx.settings.generation, rows.map(_.player).distinct)
    rows.map(r => TransactionLine(r.transactionId, r.at.toString, r.player, players.get(r.player).fold("")(_.truncatedEmail(LedgerEmail)),
      r.tpe, Money(r.amount, r.currency), r.currency, r.reference))

  private val LedgerTopics = Vector(f.MoneyTransactions.name)

  /** `GET /reports/transactions` (FR-010): a page of at most one hundred, every transaction counted. */
  def transactions(ctx: Ctx, asked: Asked, ledger: Ledger): Answer = tab(ctx) {
    answered(periodOf(ctx, asked, LedgerTopics).map { period =>
      val q     = ledgerAsked(ctx, period, ledger)
      val total = Transactions.count(ctx.views, q)
      val size  = ledger.size
      val page  = Transactions.page(ctx.views, q, ledger.pageNo * size, size)
      ok(TransactionsPage(period.from.toString, period.to.toString, ledger.pageNo, size, total,
        math.ceil(total.toDouble / size).toInt, lines(ctx, page)))
    })
  }

  private val TransactionHeader = Vector("transactionId", "timestamp", "playerId", "truncatedEmail", "transactionType", "amount",
    "currency", "reference")

  /**
   * `GET /reports/transactions/export` (FR-010, FR-012): every matching row, no cap; JSON as the original's page (page 0,
   * the size every row), or `format=csv`.
   */
  def transactionsExport(ctx: Ctx, asked: Asked, ledger: Ledger): Answer = tab(ctx) {
    answered(periodOf(ctx, asked, LedgerTopics).map { period =>
      val all = lines(ctx, Transactions.all(ctx.views, ledgerAsked(ctx, period, ledger)))
      if asked.csv then
        exported(ctx, asked, "transactions", period, Map("type" -> ledger.filter) ++ ledger.email.map(_ => "playerEmail" -> "given"),
          Csv(TransactionHeader, all.map(l => Vector(l.transactionId, l.timestamp, l.playerId, l.truncatedEmail, l.transactionType,
            l.amount, l.currency, l.reference))), all.size.toLong, all.map(_.playerId).distinct)
      else ok(TransactionsPage(period.from.toString, period.to.toString, 0, all.size, all.size.toLong, if all.isEmpty then 0 else 1, all))
    })
  }

  // ── bonus ─────────────────────────────────────────────────────────────────────────────────────

  /** An offer's or the summary's counts over its grants. */
  private final case class Counted(grants: Vector[GrantRow]):
    def total: Int     = grants.size
    def converted: Int = grants.count(_.converted)
    def value: Long    = grants.map(_.amount).sum
    def convertedValue: Long = grants.filter(_.converted).map(_.amount).sum
    def of(status: String): Int = grants.count(_.status == status)
    def rate: Int      = Figures.conversionRatePct(converted, total)

  /**
   * `GET /reports/bonus` (FR-009): the grants granted in the period in the currency answered (a free-rounds grant,
   * which has none until it wins, counted in every one), one row per offer by its id, its name and type from its
   * grants; converted counts a grant converted whether its cap is pending or closed.
   */
  def bonus(ctx: Ctx, asked: Asked, offer: Option[String]): Answer = tab(ctx) {
    answered(for
      period <- periodOf(ctx, asked, Option.when(ctx.settings.casino)(f.CasinoRewards.name).toVector)
      chosen <- Currencies.choose(ctx, asked.currency, ctx.reporting)
    yield
      val c       = chosen.currency
      val one     = offer.map(_.trim).filter(o => o.nonEmpty && o != "all")
      val grants  = Grants.granted(ctx.views, ctx.settings.generation, startOf(ctx, period.from), startOf(ctx, period.to.plusDays(1)), one)
        .filter(_.currency.forall(_ == c))
      def m(v: Long) = Money(v, c)
      val offers = grants.groupBy(g => if g.offerId.nonEmpty then g.offerId else "unknown").toVector.sortBy(_._1).map { (id, gs) =>
        val n = Counted(gs)
        BonusOffer(id, gs.map(_.name).find(_.nonEmpty).getOrElse(id), gs.map(_.offerType).find(_.nonEmpty).getOrElse("UNKNOWN"),
          n.total, m(n.value), n.converted, m(n.convertedValue), n.of("forfeited"), n.of("cancelled"), n.rate, c)
      }
      val all = Counted(grants)
      val summary = BonusTotals(all.total, m(all.value), all.converted, all.rate, all.of("forfeited"), all.of("cancelled"),
        all.of("active"), c)
      if asked.csv then
        exported(ctx, asked, "bonus", period, Map("currency" -> c) ++ one.map("offerId" -> _), bonusCsv(summary, offers, all),
          offers.size + 1L, Vector.empty)
      else ok(BonusReport(period.from.toString, period.to.toString, c, chosen.currencies, summary, offers)))
  }

  private val BonusHeader = Vector("offerId", "offerName", "offerType", "grantsCount", "totalGrantedValue", "convertedCount",
    "convertedValue", "forfeitedCount", "cancelledCount", "conversionRatePct", "activeCount", "currency")

  /** The summary first, as the revenue CSV's, under `all` (the filter's every-offer value), then one row per offer. */
  private def bonusCsv(s: BonusTotals, offers: Vector[BonusOffer], all: Counted): String =
    Csv(BonusHeader, Vector("all", "", "", s.totalGrantsCount.toString, s.totalGrantedValue, s.convertedCount.toString,
      Money(all.convertedValue, s.currency), s.forfeitedCount.toString, s.cancelledCount.toString, s.conversionRatePct.toString,
      s.activeCount.toString, s.currency) +: offers.map(o => Vector(o.offerId, o.offerName, o.offerType, o.grantsCount.toString, o.totalGrantedValue,
      o.convertedCount.toString, o.convertedValue, o.forfeitedCount.toString, o.cancelledCount.toString, o.conversionRatePct.toString,
      "", o.currency)))

  // ── payment providers ─────────────────────────────────────────────────────────────────────────

  /**
   * `GET /reports/psp` (FR-011): the period's deposit attempts, captures and value per payment provider and the
   * withdrawal requests, completions and hours per gateway, from the days' sections, every player's; a provider and a
   * gateway of the same name are one row; a rate with no attempts is `null`, and `belowThreshold` false for it.
   */
  def psp(ctx: Ctx, asked: Asked): Answer = tab(ctx) {
    answered(for
      period <- periodOf(ctx, asked, Completeness.dayTopics(ctx.settings))
      chosen <- Currencies.choose(ctx, asked.currency, ctx.reporting)
    yield
      val c        = chosen.currency
      val rows     = DaysView.rows(ctx.views, ctx.settings.generation, period.from, period.to)
      val sections = rows.map(r => Revenue.daySection(r, c, excludeTestAccounts = false))
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
      val (p, w) = (pays.values.foldLeft(PaymentFigures())(_ + _),
        gates.values.foldLeft(GatewayFigures())(_ + _))
      val summary = PspTotals(p.attempts, p.completed, m(p.value), Figures.ratePct(p.completed, p.attempts), w.requests, w.completed,
        Figures.averageHours(w.completionSeconds, w.timed), c)
      if asked.csv then
        exported(ctx, asked, "psp", period, Map("currency" -> c), pspCsv(summary, lines), lines.size + 1L, Vector.empty)
      else ok(PspReport(period.from.toString, period.to.toString, c, chosen.currencies, summary, lines)))
  }

  private val PspHeader = Vector("pspName", "depositAttempts", "completedDeposits", "depositSuccessRatePct", "depositValue",
    "withdrawalCount", "withdrawalRequests", "avgWithdrawalHours", "belowThreshold", "currency")

  /** The summary first under `all`, then one row per provider; a `null` rate an empty cell. */
  private def pspCsv(s: PspTotals, lines: Vector[PspLine]): String =
    def o(v: Option[BigDecimal]) = v.fold("")(_.toString)
    Csv(PspHeader, Vector("all", s.depositAttempts.toString, s.completedDeposits.toString, o(s.depositSuccessRatePct), s.depositValue,
      s.completedWithdrawals.toString, s.withdrawalRequests.toString, o(s.avgWithdrawalHours),
      s.depositSuccessRatePct.exists(_ < Threshold).toString, s.currency) +:
      lines.map(l => Vector(l.pspName, l.depositAttempts.toString, l.completedDeposits.toString, o(l.depositSuccessRatePct),
        l.depositValue, l.withdrawalCount.toString, l.withdrawalRequests.toString, o(l.avgWithdrawalHours), l.belowThreshold.toString,
        s.currency)))

  // ── the synchronous document path ─────────────────────────────────────────────────────────────

  /**
   * The synchronous document path (research R9): rendered, kept under `reports/report-export/<reference>.csv` with the
   * player ids it names beside it in `<reference>.players` (ids are not personal), recorded, served as an attachment
   * `<report>-report-<reference>.csv`. An export over the synchronous limit renders here too until `DocumentJob` (T053)
   * exists; this is the one call it will hand to the job.
   */
  private def exported(ctx: Ctx, asked: Asked, report: String, period: Period, parameters: Map[String, String], csv: String,
      rows: Long, named: Vector[String]): Answer =
    val reference = UUID.randomUUID().toString
    val name      = Document.objectName(Document.ReportExport, reference, "csv")
    val body      = csv.getBytes(UTF_8)
    val at        = ctx.now()
    val bucket    = ctx.clients.storage.default
    bucket.putBytes(name, body, RevenueCsv.ContentType)
    if named.nonEmpty then
      bucket.putBytes(Document.objectName(Document.ReportExport, reference, "players"), named.distinct.mkString("\n").getBytes(UTF_8),
        "text/plain; charset=UTF-8")
    ctx.components.forKeyValueEntity(EntityId(reference)).call(Document.ready).invoke(DocumentState(
      reference = reference, kind = Document.ReportExport, format = "csv",
      parameters = Map("report" -> report) ++ parameters ++ asked.currency.map("asked" -> _),
      from = Some(period.from.toString), to = Some(period.to.toString), askedBy = asked.operator, generatedAt = Some(at),
      readyAt = Some(at), objectName = Some(name), rows = rows)): Unit
    attachment(RevenueCsv.ContentType, s"$report-report-$reference.csv", body)
