package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.reporting.api.Disputes
import io.eitheror.casino.reporting.documents.Document
import io.eitheror.casino.reporting.render.Money
import org.apache.pdfbox.text.PDFTextStripper

import java.time.LocalDate
import scala.concurrent.duration.*

/**
 * The dispute report against the whole service (US5; FR-016, FR-017, FR-022): one login session or a range of days, every
 * game session and round of it held to the facts published, the original's refusals in its texts, the three formats
 * served as attachments under `dispute-report-{reference}.{ext}`, a round only the wallet settled included and marked
 * unmatched, and a range over the brand's seven days, or more rounds than its synchronous limit, made a job.
 */
class DisputeSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 30.minutes
  private lazy val kit = SharedKit.kit

  private var d: LocalDate = null

  override def beforeAll(): Unit = d = kit.days()

  private def money(v: Long) = Money(v, "EUR")

  /** The CSV's lines: the meta header and its row, a blank line, the rounds' header, the rounds, and `TOTAL`. */
  private def lines(r: Reply): Vector[Vector[String]] = r.text.split("\r\n", -1).toVector.dropRight(1).map(_.split(",", -1).toVector)

  /** The rows the facts say the report must hold, in order: a login session's games by start, their rounds by time. */
  private def expected(p: Disputed, login: Disputed.Login): Vector[Vector[String]] =
    val label = s"${login.started}-${login.ended.fold("ongoing")(_.toString)} (${login.reason})"
    val rows = for
      g <- p.games.filter(_.login == login.id).sortBy(_.started)
      r <- p.rounds.filter(_.game.contains(g.id)).sortBy(_.at)
    yield Vector(g.name, "slots", r.at.toString, money(r.stake), money(r.win), money(r.net), r.status)
    rows.zipWithIndex.map((row, i) => (if i == 0 then label else "") +: row)

  test("a login session's report holds its sessions, games and rounds, the totals and a reference of its own (FR-016)") {
    val p = Disputed(kit, d).usual().publish()
    val l = p.logins.head
    val r = p.bySession(format = "csv")
    assertEquals(r.status, 200, r.text)
    val ref = Disputed.reference(r)
    val all = lines(r)
    assertEquals(all(0), Vector("Reference", "Generated", "Player", "Brand", "Period From", "Period To"))
    assertEquals((all(1)(0), all(1)(2), all(1)(3), all(1)(4), all(1)(5)), (ref, p.email, kit.brand, l.started.toString, l.ended.get.toString))
    assertEquals(all(3), Vector("Login Session", "Game", "Game Type", "Round Time", "Staked", "Win", "Net", "Status"))
    assertEquals(all.slice(4, all.size - 1), expected(p, l))
    val (stake, win) = (p.rounds.map(_.stake).sum, p.rounds.map(_.win).sum)
    assertEquals(all.last, Vector("TOTAL", "", "", "", money(stake), money(win), money(win - stake), ""))
    // The HTML: the brand, the email, the period, the session's address, device and close reason, each game's span.
    val h = p.bySession()
    assertEquals(h.status, 200, h.text)
    assertNotEquals(Disputed.reference(h), ref)
    for want <- Vector(kit.brand, p.email, l.address, l.agent, "(player_logout)", s"${l.started} to ${l.ended.get}", "Period totals") ++
        p.games.flatMap(g => Vector(s"${g.name} (slots)", s"${g.started} &mdash; ${g.ended.get}"))
    do assert(h.text.contains(want), want)
    // Kept under the player's prefix, with a record naming the player by id only.
    val record = Disputed.document(kit, ref)
    assertEquals((record.kind, record.format, record.status, record.askedBy, record.subject, record.rows),
      (Document.DisputeReport, "csv", Document.Ready, "op-1", Some(p.player), 5L))
    assertEquals(record.objectName, Some(Document.subjectObjectName(p.player, Document.DisputeReport, ref, "csv")))
    assertEquals(Disputed.text(kit, record.objectName.get), Some(r.text))
    assert(!record.parameters.values.exists(_.contains(p.email)), record.parameters)
  }

  test("a range of days includes every login session of the player started in it, and none outside it") {
    val p  = Disputed(kit, d.plusDays(2))
    val before = p.login(p.at(d.plusDays(1), "23:00"), Some(p.at(d.plusDays(1), "23:30")))
    p.round(p.game(before, p.at(d.plusDays(1), "23:05"), Some(p.at(d.plusDays(1), "23:10"))), p.at(d.plusDays(1), "23:06"), 1_00, 0)
    val in = Vector(d.plusDays(2), d.plusDays(3)).map { day =>
      val l = p.login(p.at(day, "09:00"), Some(p.at(day, "09:30")))
      p.round(p.game(l, p.at(day, "09:05"), Some(p.at(day, "09:20"))), p.at(day, "09:06"), 2_00, 3_00)
      l
    }
    val after = p.login(p.at(d.plusDays(4), "00:10"), None)
    p.round(p.game(after, p.at(d.plusDays(4), "00:11"), None), p.at(d.plusDays(4), "00:12"), 4_00, 0)
    p.publish()
    val r = p.byRange(d.plusDays(2), d.plusDays(3), "csv")
    assertEquals(r.status, 200, r.text)
    val all = lines(r)
    assertEquals(all.slice(4, all.size - 1), in.flatMap(expected(p, _)))
    // The period is the range's days in the brand's zone, from the first's midnight to the last's final second.
    assertEquals((all(1)(4), all(1)(5)),
      (p.at(d.plusDays(2), "00:00").toString, p.at(d.plusDays(4), "00:00").minusSeconds(1).toString))
    assertEquals(all.last, Vector("TOTAL", "", "", "", money(4_00), money(6_00), money(2_00), ""))
  }

  test("the original's five refusals, in its texts and order, and its two not-founds") {
    val p = Disputed(kit, d.plusDays(5)).usual().publish()
    val l = p.logins.head
    def refused(q: String) =
      val r = p.report(q)
      (r.status, r.error)
    assertEquals(refused(s"sessionId=${l.id}&fromDate=$d&toDate=$d"), (400, Disputes.NotBoth))
    assertEquals(refused(s"sessionId=${l.id}&toDate=$d"), (400, Disputes.NotBoth))
    assertEquals(refused(""), (400, Disputes.OneOf))
    assertEquals(refused(s"fromDate=$d"), (400, Disputes.OneOf))
    assertEquals(refused(s"fromDate=$d&toDate=${d.plusDays(366)}"), (400, Disputes.OverAYear))
    assertEquals(refused(s"fromDate=${d.plusDays(1)}&toDate=$d"), (400, Disputes.Backwards))
    assertEquals(refused(s"fromDate=$d&toDate=yesterday"), (400, Disputes.BadDate))
    // A year exactly is allowed.
    assertEquals(p.report(s"fromDate=${d.minusDays(365)}&toDate=$d").status, 202)
    // A player the brand never registered; a session that is none, another player's, or a game session.
    assertEquals((kit.get(s"/players/nobody-${p.tag}/dispute-report?sessionId=${l.id}", kit.asOperator).error), Disputes.PlayerNotFound)
    assertEquals(kit.get(s"/players/nobody-${p.tag}/dispute-report?sessionId=${l.id}", kit.asOperator).status, 404)
    val other = Disputed(kit, d.plusDays(5)).usual().publish()
    for s <- Vector(s"ls-none-${p.tag}", other.logins.head.id, p.games.head.id) do
      val r = p.report(s"sessionId=$s")
      assertEquals((r.status, r.error), (404, Disputes.SessionNotFound), s)
  }

  test("HTML, CSV and PDF are attachments named dispute-report-{reference}.{ext}") {
    val p = Disputed(kit, d.plusDays(6)).usual().publish()
    for (format, ext, tpe) <- Vector(("", "html", "text/html"), ("html", "html", "text/html"), ("csv", "csv", "text/csv"),
        ("pdf", "pdf", "application/pdf"))
    do
      val (status, headers, body) = kit.getBytes(s"/players/${p.player}/dispute-report?sessionId=${p.logins.head.id}&format=$format",
        kit.asOperator)
      assertEquals(status, 200, String(body))
      val r   = Reply(status, "", headers)
      val ref = Disputed.reference(r)
      assert(r.header("Content-Disposition").get.contains(s"attachment; filename=\"dispute-report-$ref.$ext\""),
        r.header("Content-Disposition"))
      assert(r.header("Content-Type").get.startsWith(tpe), r.header("Content-Type"))
      if ext == "pdf" then
        assertEquals(String(body.take(5), "US-ASCII"), "%PDF-")
        val doc = org.apache.pdfbox.Loader.loadPDF(body)
        try
          val text = PDFTextStripper().getText(doc)
          for want <- Vector(ref, p.email, p.games.head.name, "Period totals") do assert(text.contains(want), want)
        finally doc.close()
  }

  test("a round the wallet settled that the provider's record lacks is included from the wallet's facts, unmatched (FR-017)") {
    val p  = Disputed(kit, d.plusDays(7))
    val l  = p.login(p.at(d.plusDays(7), "14:00"), Some(p.at(d.plusDays(7), "15:00")))
    val g  = p.game(l, p.at(d.plusDays(7), "14:05"), Some(p.at(d.plusDays(7), "14:20")))
    p.round(g, p.at(d.plusDays(7), "14:06"), 2_00, 0)
    p.walletOnly(Some(g), p.at(d.plusDays(7), "14:07"), 3_00, 5_00)
    // Outside every game session of the login session: shown under the unknown game.
    val stray = p.walletOnly(None, p.at(d.plusDays(7), "14:40"), 1_00, 0)
    p.publish()
    val r = p.bySession(l, "csv")
    assertEquals(r.status, 200, r.text)
    val rows = lines(r).slice(4, lines(r).size - 1)
    val label = s"${l.started}-${l.ended.get} (logout)"
    assertEquals(rows, Vector(
      Vector(label, g.name, "slots", p.at(d.plusDays(7), "14:06").toString, money(2_00), money(0), money(-2_00), "loss"),
      Vector("", g.name, "slots", p.at(d.plusDays(7), "14:07").toString, money(3_00), money(5_00), money(2_00), "unmatched"),
      Vector("", Disputes.UnknownGame, "", stray.at.toString, money(1_00), money(0), money(-1_00), "unmatched")))
  }

  test("a range over the brand's seven days is a job: 202, its progress read, its document served once ready (FR-022)") {
    val p = Disputed(kit, d.plusDays(8))
    for day <- Vector(d.plusDays(8), d.plusDays(13)) do
      val l = p.login(p.at(day, "08:00"), Some(p.at(day, "08:30")))
      p.round(p.game(l, p.at(day, "08:01"), Some(p.at(day, "08:20"))), p.at(day, "08:02"), 1_50, 0)
    p.publish()
    val r = p.byRange(d.plusDays(8), d.plusDays(15), "csv")
    assertEquals(r.status, 202, r.text)
    val ref = r.json.path("reference").asText
    assertEquals(r.json.path("status").asText, Document.Generating)
    val first = kit.get(s"/documents/$ref", kit.asOperator)
    if first.status == 202 then
      val progress = first.json.path("progress").asInt(-1)
      assert(progress >= 0 && progress <= 100, first.text)
    val served = Disputed.served(kit, ref, kit.asOperator)
    assertEquals(served.status, 200, served.text)
    assert(served.header("Content-Disposition").get.contains(s"dispute-report-$ref.csv"))
    val all = lines(served)
    assertEquals(all(1)(0), ref)
    assertEquals(all(1)(2), p.email)
    assertEquals(all.slice(4, all.size - 1), p.logins.flatMap(expected(p, _)))
    assertEquals(all.last, Vector("TOTAL", "", "", "", money(3_00), money(0), money(-3_00), ""))
    val record = Disputed.document(kit, ref)
    assertEquals((record.status, record.rows, record.served), (Document.Ready, 2L, 1))
    // No page left beside the document.
    assertEquals(kit.testKit.storage.default.list(record.objectName.get + ".parts/").size, 0)
  }

  test("more rounds than the synchronous limit is a job too, and a PDF job renders") {
    val before = kit.reporting
    kit.setReporting(_.copy(syncRows = 2))
    try
      val p = Disputed(kit, d.plusDays(9)).usual().publish()
      val r = p.bySession(format = "pdf")
      assertEquals(r.status, 202, r.text)
      val ref = r.json.path("reference").asText
      Disputed.served(kit, ref, kit.asOperator): Unit
      val (status, headers, body) = kit.getBytes(s"/documents/$ref", kit.asOperator)
      assertEquals(status, 200)
      assert(Reply(status, "", headers).header("Content-Type").get.startsWith("application/pdf"))
      val doc = org.apache.pdfbox.Loader.loadPDF(body)
      try assert(PDFTextStripper().getText(doc).contains(ref))
      finally doc.close()
    finally kit.setReporting(_ => before)
  }
