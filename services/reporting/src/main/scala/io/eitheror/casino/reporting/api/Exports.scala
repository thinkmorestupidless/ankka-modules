package io.eitheror.casino.reporting.api

import io.eitheror.casino.reporting.documents.{Document, DocumentState, JobRequest, Keeping}
import io.eitheror.casino.reporting.ranges.Ranges.Period
import io.eitheror.casino.reporting.reads.Transactions
import io.eitheror.casino.reporting.render.{Csv, RevenueCsv}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.UUID

import Wire.*

/**
 * The Reports tab's CSV exports (FR-012, FR-021, FR-022): each report's rows from `Reads` and what the request asked,
 * a page at a time, so an export under the synchronous limit is rendered in the request and one over it by a
 * `DocumentJob` from the same rows (research R9). An export names its players' ids in a `.players` sidecar (research R9),
 * which an erasure reads (R10).
 */
object Exports:
  val Ggr                = "ggr"
  val PlayersReport      = "players"
  val TransactionsReport = "transactions"
  val BonusReport        = "bonus"
  val PspReport          = "psp"

  /** The ledger export's players, by id, comma separated, `*` for every one: the email search it was given, resolved. */
  val Ids = "ids"

  /**
   * What an export is of: the report, its period and the parameters its rows are read with, all of them already chosen
   * (the currency, the filters), so a job reads what the request would have. None is personal.
   */
  final case class Ask(report: String, from: LocalDate, to: LocalDate, parameters: Map[String, String] = Map.empty):
    def period: Period             = Period(from, to)
    def p(k: String): Option[String] = parameters.get(k).filter(_.nonEmpty)
    def currency: String           = p("currency").getOrElse("EUR")

  def header(report: String): Vector[String] = report match
    case Ggr                => RevenueCsv.Header
    case PlayersReport      => PlayerHeader
    case TransactionsReport => TransactionHeader
    case BonusReport        => BonusHeader
    case PspReport          => PspHeader
    case other              => throw IllegalArgumentException(s"no export '$other'")

  /** The column a report's rows name a player in. */
  private def playerColumn(report: String): Option[Int] = report match
    case PlayersReport      => Some(0)
    case TransactionsReport => Some(2)
    case _                  => None

  def named(report: String, rows: Vector[Vector[String]]): Vector[String] =
    playerColumn(report).fold(Vector.empty)(i => rows.map(_(i)).filter(_.nonEmpty).distinct)

  /** The ledger's players: every one for `*` or none given; a search that matched nobody is an empty list, so no rows. */
  private def ledger(r: Reads, ask: Ask): Transactions.Asked =
    val players = ask.parameters.get(Ids) match
      case None | Some("*") => None
      case Some(ids)        => Some(ids.split(',').toVector.filter(_.nonEmpty))
    Reports.ledgerAsked(r, ask.period, ask.p("type").getOrElse("all"), players)

  /** How many rows the export holds, its summary row counted. */
  def count(r: Reads, ask: Ask): Long = ask.report match
    case TransactionsReport => Transactions.count(r.views, ledger(r, ask))
    case _                  => all(r, ask).size.toLong

  /** Rows `offset` to `offset + limit`, in the export's order. */
  def page(r: Reads, ask: Ask, offset: Int, limit: Int): Vector[Vector[String]] = ask.report match
    case TransactionsReport => Reports.ledgerLines(r, Transactions.page(r.views, ledger(r, ask), offset, limit)).map(transactionRow)
    case _                  => all(r, ask).slice(offset, offset + limit)

  /** Every row when there are at most `limit`, else how many there are. */
  def upTo(r: Reads, ask: Ask, limit: Long): Either[Long, Vector[Vector[String]]] = ask.report match
    case TransactionsReport =>
      val n = count(r, ask)
      if n > limit then Left(n) else Right(page(r, ask, 0, n.toInt))
    case _ =>
      val rows = all(r, ask)
      if rows.size > limit then Left(rows.size.toLong) else Right(rows)

  private def all(r: Reads, ask: Ask): Vector[Vector[String]] = ask.report match
    case Ggr =>
      val revenue = Reports.revenue(r, ask.period, ask.currency, ask.p("excludeTestAccounts").contains("true"))
      RevenueCsv.rows(revenue, ask.from, ask.to)
    case PlayersReport =>
      Reports.playerLines(r, ask.period, ask.currency, ask.p("country"), ask.p("status")).map(l => Vector(l.playerId, l.email,
        l.country, l.registrationDate, l.status, l.kycStatus, l.lifetimeDeposits, l.lifetimeWagered, l.lifetimeGgr, l.lastLoginAt,
        l.isTestAccount.toString, l.currency))
    case BonusReport =>
      val b = Reports.bonusFigures(r, ask.period, ask.currency, ask.p("offerId"))
      val s = b.summary
      Vector("all", "", "", s.totalGrantsCount.toString, s.totalGrantedValue, s.convertedCount.toString, b.convertedValue,
        s.forfeitedCount.toString, s.cancelledCount.toString, s.conversionRatePct.toString, s.activeCount.toString, s.currency) +:
        b.offers.map(o => Vector(o.offerId, o.offerName, o.offerType, o.grantsCount.toString, o.totalGrantedValue,
          o.convertedCount.toString, o.convertedValue, o.forfeitedCount.toString, o.cancelledCount.toString, o.conversionRatePct.toString,
          "", o.currency))
    case PspReport =>
      val (s, lines) = Reports.pspFigures(r, ask.period, ask.currency)
      def o(v: Option[BigDecimal]) = v.fold("")(_.toString)
      Vector("all", s.depositAttempts.toString, s.completedDeposits.toString, o(s.depositSuccessRatePct), s.depositValue,
        s.completedWithdrawals.toString, s.withdrawalRequests.toString, o(s.avgWithdrawalHours),
        s.depositSuccessRatePct.exists(_ < Reports.Threshold).toString, s.currency) +:
        lines.map(l => Vector(l.pspName, l.depositAttempts.toString, l.completedDeposits.toString, o(l.depositSuccessRatePct),
          l.depositValue, l.withdrawalCount.toString, l.withdrawalRequests.toString, o(l.avgWithdrawalHours), l.belowThreshold.toString,
          s.currency))
    case TransactionsReport => page(r, ask, 0, Int.MaxValue)
    case other              => throw IllegalArgumentException(s"no export '$other'")

  private def transactionRow(l: TransactionLine): Vector[String] =
    Vector(l.transactionId, l.timestamp, l.playerId, l.truncatedEmail, l.transactionType, l.amount, l.currency, l.reference)

  private val PlayerHeader = Vector("playerId", "email", "country", "registrationDate", "status", "kycStatus", "lifetimeDeposits",
    "lifetimeWagered", "lifetimeGgr", "lastLoginAt", "isTestAccount", "currency")

  private val TransactionHeader = Vector("transactionId", "timestamp", "playerId", "truncatedEmail", "transactionType", "amount",
    "currency", "reference")

  /** The summary first, as the revenue CSV's, under `all` (the filter's every-offer value), then one row per offer. */
  private val BonusHeader = Vector("offerId", "offerName", "offerType", "grantsCount", "totalGrantedValue", "convertedCount",
    "convertedValue", "forfeitedCount", "cancelledCount", "conversionRatePct", "activeCount", "currency")

  /** The summary first under `all`, then one row per provider; a `null` rate an empty cell. */
  private val PspHeader = Vector("pspName", "depositAttempts", "completedDeposits", "depositSuccessRatePct", "depositValue",
    "withdrawalCount", "withdrawalRequests", "avgWithdrawalHours", "belowThreshold", "currency")

  /** The record's parameters: the report, what was asked, never the ledger's players (the record says only that a search was given). */
  private def recorded(asked: Reports.Asked, ask: Ask): Map[String, String] =
    Map("report" -> ask.report) ++ (ask.parameters - Ids) ++ asked.currency.map("asked" -> _)

  /**
   * An export (research R9): over the brand's synchronous limit (`syncRows`), a `DocumentJob` and `202 {reference,
   * status}` (FR-022); under it rendered here, kept under `reports/report-export/<reference>.csv` with the player ids it
   * names in its sidecar, recorded, and served as the attachment `<report>-report-<reference>.csv`.
   */
  def exported(ctx: Ctx, asked: Reports.Asked, ask: Ask): Answer =
    val reads     = ctx.reads
    val reference = UUID.randomUUID().toString
    val name      = Document.objectName(Document.ReportExport, reference, "csv")
    val file      = s"${ask.report}-report-$reference.csv"
    val at        = ctx.now()
    val record = DocumentState(reference = reference, kind = Document.ReportExport, format = "csv", parameters = recorded(asked, ask),
      from = Some(ask.from.toString), to = Some(ask.to.toString), askedBy = asked.operator, generatedAt = Some(at),
      objectName = Some(name), fileName = Some(file), contentType = Some(RevenueCsv.ContentType))
    upTo(reads, ask, ctx.reporting.syncRows.toLong) match
      case Left(_) =>
        Jobs.started(ctx, record, JobRequest(reference, Document.ReportExport, ask.report, "csv", ask.from.toString, ask.to.toString,
          ask.parameters, asked.operator, None, name, file, RevenueCsv.ContentType))
      case Right(rows) =>
        val body = Csv(header(ask.report), rows).getBytes(UTF_8)
        Keeping.keep(ctx.components, ctx.clients.storage.default, record.copy(readyAt = Some(at), rows = rows.size.toLong), body,
          named(ask.report, rows), ctx.reporting.lifetimeOf(Document.ReportExport))
        Keeping.served(ctx.components, reference, asked.operator, at)
        attachment(RevenueCsv.ContentType, file, body)

  /**
   * A job's page `n` of an export (research R9): `Jobs.PageRows` rows from `n` pages in, the header on the first. The
   * row count is read once, on the first page, and carried.
   */
  def jobPage(r: Reads, req: JobRequest, n: Int, carried: Map[String, Long]): Jobs.Rendered =
    val ask    = Ask(req.report, LocalDate.parse(req.from), LocalDate.parse(req.to), req.parameters)
    val total  = carried.getOrElse("total", count(r, ask))
    val offset = n * Jobs.PageRows
    val rows   = page(r, ask, offset, Jobs.PageRows)
    val text   = (if n == 0 then Csv.row(header(ask.report)) else "") + rows.map(Csv.row).mkString
    Jobs.Rendered(text, named(ask.report, rows), rows.size.toLong, total,
      carried.updated("total", total).updated("done", carried.getOrElse("done", 0L) + rows.size),
      last = rows.size < Jobs.PageRows || offset + rows.size >= total)
