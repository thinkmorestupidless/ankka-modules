package io.eitheror.casino.reporting.render

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** One round as the dispute report lists it: its number in its game session, time, stake, win and status (FR-016). */
final case class DisputeRound(number: Int, at: Instant, stake: Long, win: Long, status: String):
  def net: Long = win - stake

/** A game session within a login session: the game's name and type (from its rounds), its times and its rounds. */
final case class DisputeGame(gameName: String, gameType: String, startedAt: Option[Instant], endedAt: Option[Instant],
    rounds: Vector[DisputeRound])

/**
 * A login session: its times, close reason, network address and user agent (each already as shown: the value, `[erased]`
 * after erasure, `None` for none) and its game sessions.
 */
final case class DisputeSession(sessionId: String, startedAt: Option[Instant], endedAt: Option[Instant], closeReason: Option[String],
    networkAddress: Option[String], userAgent: Option[String], games: Vector[DisputeGame]):
  def rounds: Vector[DisputeRound] = games.flatMap(_.rounds)

/** What heads the report: its reference, when it was made, the player's email as shown, the brand and the period. */
final case class DisputeMeta(reference: String, generatedAt: Instant, email: String, brand: String, periodFrom: String,
    periodTo: String, currency: String)

/** The period's sums, carried from page to page by a job, so a page never needs the sessions before it. */
final case class DisputeTotals(stake: Long = 0, win: Long = 0, rounds: Long = 0):
  def net: Long = win - stake
  def +(s: DisputeSession): DisputeTotals =
    val r = s.rounds
    DisputeTotals(stake + r.map(_.stake).sum, win + r.map(_.win).sum, rounds + r.size)

/**
 * The dispute report in the original's layout (`ReportService.scala`: its HTML and its CSV), in three parts so a job can
 * render it a page of sessions at a time (research R9): the head, one block per login session, and the tail with the
 * period's totals. A whole report is the three joined. Every value is escaped for HTML, which the original did not do:
 * a user agent may hold `<` or `&`, and the PDF is rendered from the same text as XHTML.
 */
object DisputeReport:
  val HtmlType = "text/html; charset=UTF-8"
  val CsvType  = "text/csv; charset=UTF-8"
  val PdfType  = "application/pdf"

  /** The file's extension and content type for a format the route was given: `csv`, `pdf`, or anything else HTML. */
  def formatOf(asked: Option[String]): String = asked.map(_.trim.toLowerCase) match
    case Some("csv") => "csv"
    case Some("pdf") => "pdf"
    case _           => "html"

  def contentType(format: String): String = format match
    case "csv" => CsvType
    case "pdf" => PdfType
    case _     => HtmlType

  def fileName(reference: String, format: String): String = s"dispute-report-$reference.$format"

  private def esc(s: String): String =
    s.flatMap {
      case '&'  => "&amp;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case '"'  => "&quot;"
      case c    => c.toString
    }

  private def time(t: Option[Instant]): String = t.fold("")(_.toString)

  // ── HTML ────────────────────────────────────────────────────────────────────────────────────────

  def htmlHead(m: DisputeMeta): String =
    s"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Dispute Report - ${esc(m.reference)}</title>
  <style>
    body { font-family: Arial, sans-serif; font-size: 12px; color: #000; }
    h1 { font-size: 18px; margin-bottom: 2px; }
    h2 { font-size: 15px; margin-top: 0; }
    h3 { font-size: 13px; margin-top: 16px; margin-bottom: 4px; }
    h4 { font-size: 12px; margin-top: 12px; margin-bottom: 4px; }
    table { border-collapse: collapse; width: 100%; margin-bottom: 8px; }
    th, td { border: 1px solid #ccc; padding: 4px 6px; text-align: left; }
    tfoot td { font-weight: bold; background: #f5f5f5; }
    .meta { margin-bottom: 12px; }
    .totals { margin-top: 16px; border-top: 2px solid #000; padding-top: 8px; }
    .footer { margin-top: 24px; font-size: 10px; color: #666; border-top: 1px solid #ccc; padding-top: 6px; }
    .no-activity { font-style: italic; color: #666; }
    @media print { body { font-size: 10px; } }
  </style>
</head>
<body>
  <h1>${esc(m.brand)}</h1>
  <h2>Player Betting History Report</h2>
  <div class="meta">
    <p>Reference: ${esc(m.reference)} &nbsp;|&nbsp; Generated: ${m.generatedAt}</p>
    <p>Player: ${esc(m.email)} &nbsp;|&nbsp; Period: ${esc(m.periodFrom)} &mdash; ${esc(m.periodTo)}</p>
  </div>
"""

  /** One login session's block, ending in a line break, so blocks rendered apart join as the original's did. */
  def htmlSession(s: DisputeSession, currency: String): String =
    def money(v: Long) = Money(v, currency)
    val games = s.games.map { g =>
      val rows = g.rounds.map { r =>
        s"""          <tr>
            <td>${r.number}</td>
            <td>${r.at}</td>
            <td>${money(r.stake)}</td>
            <td>${money(r.win)}</td>
            <td>${money(r.net)}</td>
            <td>${esc(r.status)}</td>
          </tr>"""
      }.mkString("\n")
      val stake = g.rounds.map(_.stake).sum
      val win   = g.rounds.map(_.win).sum
      s"""        <h4>${esc(g.gameName)} (${esc(g.gameType)})</h4>
        <p>${time(g.startedAt)} &mdash; ${g.endedAt.fold("ongoing")(_.toString)}</p>
        <table>
          <thead>
            <tr><th>Round</th><th>Time</th><th>Staked</th><th>Win</th><th>Net</th><th>Status</th></tr>
          </thead>
          <tbody>
$rows
          </tbody>
          <tfoot>
            <tr><td colspan="2"><strong>Game totals</strong></td><td>${money(stake)}</td><td>${money(win)}</td><td>${money(win - stake)}</td><td></td></tr>
          </tfoot>
        </table>"""
    }.mkString("\n")
    s"""      <h3>Login Session &mdash; ${time(s.startedAt)} to ${s.endedAt.fold("ongoing")(_.toString)} (${esc(s.closeReason.getOrElse("—"))})</h3>
      <p>IP: ${esc(s.networkAddress.getOrElse("—"))} &nbsp;|&nbsp; Device: ${esc(s.userAgent.getOrElse("—"))}</p>
$games
"""

  def htmlTail(m: DisputeMeta, t: DisputeTotals): String =
    def money(v: Long) = Money(v, m.currency)
    val none = if t.rounds == 0 then """      <p class="no-activity">No betting activity recorded for this period.</p>""" else ""
    s"""$none
  <div class="totals">
    <h3>Period totals</h3>
    <table>
      <thead>
        <tr><th>Total Staked</th><th>Total Won</th><th>Net Result</th></tr>
      </thead>
      <tbody>
        <tr><td>${money(t.stake)}</td><td>${money(t.win)}</td><td>${money(t.net)}</td></tr>
      </tbody>
    </table>
  </div>
  <div class="footer">
    <p>Reference: ${esc(m.reference)} &nbsp;|&nbsp; Data as of: ${m.generatedAt}</p>
  </div>
</body>
</html>"""

  def html(m: DisputeMeta, sessions: Vector[DisputeSession]): String =
    htmlHead(m) + sessions.map(htmlSession(_, m.currency)).mkString + htmlTail(m, sessions.foldLeft(DisputeTotals())(_ + _))

  // ── CSV ─────────────────────────────────────────────────────────────────────────────────────────

  def csvHead(m: DisputeMeta): String =
    Csv.row(Vector("Reference", "Generated", "Player", "Brand", "Period From", "Period To")) +
      Csv.row(Vector(m.reference, m.generatedAt.toString, m.email, m.brand, m.periodFrom, m.periodTo)) + "\r\n" +
      Csv.row(Vector("Login Session", "Game", "Game Type", "Round Time", "Staked", "Win", "Net", "Status"))

  /** A login session's rows: the session named on its first round's row only, as the original. */
  def csvSession(s: DisputeSession, currency: String): String =
    val label = s"${time(s.startedAt)}-${s.endedAt.fold("ongoing")(_.toString)} (${s.closeReason.getOrElse("—")})"
    val rows = for
      g <- s.games
      r <- g.rounds
    yield (g, r)
    rows.zipWithIndex.map { case ((g, r), i) =>
      Csv.row(Vector(if i == 0 then label else "", g.gameName, g.gameType, r.at.toString, Money(r.stake, currency),
        Money(r.win, currency), Money(r.net, currency), r.status))
    }.mkString

  /** The totals row, `TOTAL` and the three sums, with no status. */
  def csvTail(m: DisputeMeta, t: DisputeTotals): String =
    s"TOTAL,,,,${Money(t.stake, m.currency)},${Money(t.win, m.currency)},${Money(t.net, m.currency)},\r\n"

  def csv(m: DisputeMeta, sessions: Vector[DisputeSession]): String =
    csvHead(m) + sessions.map(csvSession(_, m.currency)).mkString + csvTail(m, sessions.foldLeft(DisputeTotals())(_ + _))

  // ── PDF ─────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The HTML as XHTML, which openhtmltopdf's parser reads: the two named entities the layout uses as numbers, and the
   * meta element closed. Values are already escaped, so the rest is well formed.
   */
  def xhtml(html: String): String =
    html.replace("&nbsp;", "&#160;").replace("&mdash;", "&#8212;").replace("<meta charset=\"UTF-8\">", "<meta charset=\"UTF-8\"/>")

  /** The PDF of the same HTML (research R9, R20 item 5): openhtmltopdf over PDFBox, its own fonts, no network. */
  def pdf(html: String): Array[Byte] =
    // Its own logging says little and goes to java.util.logging; a parse error's message could quote the report.
    com.openhtmltopdf.util.XRLog.setLoggingEnabled(false)
    val out = ByteArrayOutputStream()
    val builder = PdfRendererBuilder()
    builder.useFastMode()
    builder.withHtmlContent(xhtml(html), null)
    builder.toStream(out)
    builder.run()
    out.toByteArray

  /** The bytes of a whole report in `format`. */
  def bytes(format: String, html: => String, csv: => String): Array[Byte] = format match
    case "csv" => csv.getBytes(UTF_8)
    case "pdf" => pdf(html)
    case _     => html.getBytes(UTF_8)
