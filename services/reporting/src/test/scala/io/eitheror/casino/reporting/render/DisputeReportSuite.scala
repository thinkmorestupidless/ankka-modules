package io.eitheror.casino.reporting.render

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

import java.time.Instant

/**
 * T054: the dispute report's HTML and CSV in `ReportService.scala`'s layout, with the original's `ReportServiceSpec` cases
 * ported (its fixtures in minor units, so 500 + 300 stake is 8.00), and the PDF rendered from the same HTML (research R20
 * item 5: openhtmltopdf renders the report's tables on JDK 21).
 */
class DisputeReportSuite extends munit.FunSuite:
  private def t(s: String) = Instant.parse(s)

  private val round1 = DisputeRound(1, t("2024-05-10T10:00:00Z"), 500, 200, "loss")
  private val round2 = DisputeRound(2, t("2024-05-10T10:05:00Z"), 300, 600, "win")
  private val game = DisputeGame("Starburst", "slots", Some(t("2024-05-10T10:00:00Z")), Some(t("2024-05-10T10:30:00Z")),
    Vector(round1, round2))
  private val session = DisputeSession("ps-001", Some(t("2024-05-10T09:50:00Z")), Some(t("2024-05-10T11:00:00Z")),
    Some("player_logout"), Some("1.2.3.4"), Some("Mozilla/5.0"), Vector(game))
  private val meta = DisputeMeta("ref-abc-123", t("2024-06-01T12:00:00Z"), "player@example.com", "spinvibe", "2024-05-01",
    "2024-05-31", "EUR")

  // ── the original's ReportServiceSpec ────────────────────────────────────────────────────────────

  test("(a) the HTML holds the player's email and the reference") {
    val html = DisputeReport.html(meta, Vector(session))
    assert(html.contains("player@example.com"))
    assert(html.contains("ref-abc-123"))
  }

  test("(b) the HTML holds each round's number and the period's net total") {
    val html = DisputeReport.html(meta, Vector(session))
    assert(html.contains(">1<") && html.contains(">2<"))
    assert(html.contains("<tr><td>8.00</td><td>8.00</td><td>0.00</td></tr>"), html)
  }

  test("(d) no sessions: the no-betting-activity message") {
    assert(DisputeReport.html(meta, Vector.empty).toLowerCase.contains("no betting activity"))
    assert(!DisputeReport.html(meta, Vector(session)).toLowerCase.contains("no betting activity"))
  }

  test("(e) a session with no close reason reads — in the HTML") {
    assert(DisputeReport.html(meta, Vector(session.copy(closeReason = None))).contains("(—)"))
  }

  test("(c) the CSV's first row is the original's header") {
    assertEquals(DisputeReport.csv(meta, Vector(session)).linesIterator.next(), "Reference,Generated,Player,Brand,Period From,Period To")
  }

  test("(e) a session with no close reason reads — in the CSV's rows") {
    assert(DisputeReport.csv(meta, Vector(session.copy(closeReason = None))).contains("(—)"))
  }

  // ── the layout ──────────────────────────────────────────────────────────────────────────────────

  test("the CSV: the meta row, a blank line, the rounds' header, the session named on its first row only, the TOTAL row") {
    val lines = DisputeReport.csv(meta, Vector(session)).split("\r\n", -1).toVector
    assertEquals(lines, Vector(
      "Reference,Generated,Player,Brand,Period From,Period To",
      "ref-abc-123,2024-06-01T12:00:00Z,player@example.com,spinvibe,2024-05-01,2024-05-31",
      "",
      "Login Session,Game,Game Type,Round Time,Staked,Win,Net,Status",
      "2024-05-10T09:50:00Z-2024-05-10T11:00:00Z (player_logout),Starburst,slots,2024-05-10T10:00:00Z,5.00,2.00,-3.00,loss",
      ",Starburst,slots,2024-05-10T10:05:00Z,3.00,6.00,3.00,win",
      "TOTAL,,,,8.00,8.00,0.00,",
      ""))
  }

  test("the HTML: the login session's times, close reason, address and device; the game's name, type and times; its totals") {
    val html = DisputeReport.html(meta, Vector(session))
    assert(html.contains("<h3>Login Session &mdash; 2024-05-10T09:50:00Z to 2024-05-10T11:00:00Z (player_logout)</h3>"))
    assert(html.contains("<p>IP: 1.2.3.4 &nbsp;|&nbsp; Device: Mozilla/5.0</p>"))
    assert(html.contains("<h4>Starburst (slots)</h4>"))
    assert(html.contains("<p>2024-05-10T10:00:00Z &mdash; 2024-05-10T10:30:00Z</p>"))
    assert(html.contains("<td>8.00</td><td>8.00</td><td>0.00</td><td></td></tr>"))
    assert(html.contains("Player: player@example.com &nbsp;|&nbsp; Period: 2024-05-01 &mdash; 2024-05-31"))
    assert(html.contains("Data as of: 2024-06-01T12:00:00Z"))
  }

  test("an open session reads ongoing; an absent address reads —") {
    val open = session.copy(endedAt = None, networkAddress = None, games = Vector(game.copy(endedAt = None)))
    val html = DisputeReport.html(meta, Vector(open))
    assert(html.contains("to ongoing (player_logout)") && html.contains("&mdash; ongoing</p>") && html.contains("IP: — "))
    assert(DisputeReport.csv(meta, Vector(open)).contains("-ongoing (player_logout)"))
  }

  test("a value holding markup is escaped, so the HTML stays HTML") {
    val html = DisputeReport.html(meta, Vector(session.copy(userAgent = Some("<script>&\""))))
    assert(html.contains("Device: &lt;script&gt;&amp;&quot;</p>"), html)
  }

  test("rendered a page of sessions at a time, with the totals carried, the report is the same") {
    val other = session.copy(sessionId = "ps-002", games = Vector(game.copy(rounds = Vector(round1.copy(stake = 1_000)))))
    val whole = DisputeReport.html(meta, Vector(session, other))
    val totals = DisputeTotals() + session + other
    val paged = DisputeReport.htmlHead(meta) + DisputeReport.htmlSession(session, "EUR") + DisputeReport.htmlSession(other, "EUR") +
      DisputeReport.htmlTail(meta, totals)
    assertEquals(paged, whole)
    assertEquals(DisputeReport.csvHead(meta) + DisputeReport.csvSession(session, "EUR") + DisputeReport.csvSession(other, "EUR") +
      DisputeReport.csvTail(meta, totals), DisputeReport.csv(meta, Vector(session, other)))
    assertEquals((totals.stake, totals.win, totals.rounds), (1_800L, 1_000L, 3L))
  }

  test("the format and the file name: csv, pdf, html by default") {
    assertEquals(Vector(Some("CSV"), Some("pdf"), Some("xls"), None).map(DisputeReport.formatOf), Vector("csv", "pdf", "html", "html"))
    assertEquals(DisputeReport.fileName("r-1", "pdf"), "dispute-report-r-1.pdf")
  }

  // ── PDF ─────────────────────────────────────────────────────────────────────────────────────────

  test("the PDF is rendered from the HTML on this JDK, its tables' text in it") {
    val pdf = DisputeReport.pdf(DisputeReport.html(meta, Vector(session.copy(userAgent = Some("Agent <&> 1")))))
    assertEquals(String(pdf.take(5), "US-ASCII"), "%PDF-")
    val doc = PDDocument.load(pdf)
    try
      val text = PDFTextStripper().getText(doc)
      for wanted <- Vector("ref-abc-123", "player@example.com", "Player Betting History Report", "Starburst (slots)", "Game totals",
          "Period totals", "8.00", "Agent <&> 1") do assert(text.contains(wanted), s"$wanted not in: $text")
    finally doc.close()
  }
