package io.eitheror.casino.reporting.features

import io.eitheror.casino.reporting.api.Disputes
import io.eitheror.casino.reporting.documents.Document
import io.eitheror.casino.reporting.it.{Disputed, Reply}
import io.eitheror.casino.reporting.render.Money

import java.time.LocalDate
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/reporting/disputes.feature (US5; FR-016, FR-017, FR-021, FR-022), over a player of the scenario's own
 * (`Disputed`): login sessions, game sessions and rounds published as the original's services would, and the report
 * held to them. The CSV is read for what a row holds and the HTML for what the page shows.
 */
class DisputesFeatures extends ReportingFeatures("disputes.feature"):
  private var p: Disputed      = null
  private var d: LocalDate     = null
  private var ask: () => Reply = null
  private var format: String   = ""
  private var reference: String = null
  private var restore: Option[() => Unit] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    p = null; d = null; ask = null; format = ""; reference = null; restore = None

  override def afterEach(context: AfterEach): Unit =
    restore.foreach(_())
    super.afterEach(context)

  private def money(v: Long) = Money(v, "EUR")
  private def lines(r: Reply) = r.text.split("\r\n", -1).toVector.dropRight(1).map(_.split(",", -1).toVector)
  private def rows(r: Reply)  = lines(r).slice(4, lines(r).size - 1)

  Given("a player of brand {string}") { (slug: String) =>
    assertEquals(slug, kit.brand)
    d = kit.days()
    p = Disputed(kit, d)
  }

  // ── one login session ───────────────────────────────────────────────────────────────────────────

  Given("a player session of the player with game sessions and rounds") { () =>
    p.usual().publish(): Unit
    ask = () => p.bySession(format = format)
  }

  When("the agent generates the dispute report for it") { () =>
    last = ask()
  }

  Then("the document holds the brand, the player's email address, the period, and the player session's start, end, close reason, network address and user agent") { () =>
    assertEquals(last.status, 200, last.text)
    val l = p.logins.head
    for want <- Vector(kit.brand, p.email, s"Period: ${l.started} &mdash; ${l.ended.get}", s"${l.started} to ${l.ended.get}",
        s"(${l.reason})", l.address, l.agent)
    do assert(last.text.contains(want), want)
  }

  Then("each game session's game, type, start and end") { () =>
    for g <- p.games do
      assert(last.text.contains(s"<h4>${g.name} (slots)</h4>"), g.name)
      assert(last.text.contains(s"${g.started} &mdash; ${g.ended.get}"), g.id)
  }

  Then("each round's number, time, stake, win, net result and status") { () =>
    for g <- p.games do
      for (r, i) <- p.rounds.filter(_.game.contains(g.id)).sortBy(_.at).zipWithIndex do
        val row = Vector(i + 1, r.at, money(r.stake), money(r.win), money(r.net), r.status).map(v => s"<td>$v</td>")
        assert(last.text.replaceAll("\\s+", "").contains(row.mkString), s"${g.id} round ${i + 1}")
  }

  Then("the period totals, the generation time and a unique reference number") { () =>
    val (stake, win) = (p.rounds.map(_.stake).sum, p.rounds.map(_.win).sum)
    assert(last.text.replaceAll("\\s+", "").contains(s"<td>${money(stake)}</td><td>${money(win)}</td><td>${money(win - stake)}</td>"))
    val ref = Disputed.reference(last)
    assert(last.text.contains(s"Reference: $ref"))
    assert(Disputed.document(kit, ref).generatedAt.exists(t => last.text.contains(s"Generated: $t")))
    assertNotEquals(Disputed.reference(ask()), ref)
  }

  // ── a range of days ─────────────────────────────────────────────────────────────────────────────

  Given("a date range of up to a year") { () =>
    for day <- Vector(d, d.plusDays(3)) do
      val l = p.login(p.at(day, "12:00"), Some(p.at(day, "12:30")))
      p.round(p.game(l, p.at(day, "12:01"), Some(p.at(day, "12:20"))), p.at(day, "12:02"), 1_00, 2_00)
    val out = p.login(p.at(d.plusDays(5), "12:00"), Some(p.at(d.plusDays(5), "12:30")))
    p.round(p.game(out, p.at(d.plusDays(5), "12:01"), None), p.at(d.plusDays(5), "12:02"), 9_00, 0)
    p.publish(): Unit
    ask = () => p.byRange(d, d.plusDays(4), "csv")
  }

  Then("every player session of the player in the range is included") { () =>
    assertEquals(last.status, 200, last.text)
    val labels = rows(last).map(_.head).filter(_.nonEmpty)
    assertEquals(labels, p.logins.take(2).map(l => s"${l.started}-${l.ended.get} (${l.reason})"))
  }

  // ── refusals ────────────────────────────────────────────────────────────────────────────────────

  private var rule: String = null

  Given("a date range over a year") { () =>
    rule = Disputes.OverAYear
    ask = () => p.byRange(d, d.plusDays(366))
  }

  Given("a date range with its end before its start") { () =>
    rule = Disputes.Backwards
    ask = () => p.byRange(d.plusDays(1), d)
  }

  Given("a player session and a date range together") { () =>
    rule = Disputes.NotBoth
    ask = () => p.report(s"sessionId=ls-${p.tag}&fromDate=$d&toDate=$d")
  }

  Then("it is refused naming the rule") { () =>
    assertEquals((last.status, last.error), (400, rule), last.text)
  }

  // ── formats ─────────────────────────────────────────────────────────────────────────────────────

  private var bytes: (Int, Map[String, Vector[String]], Array[Byte]) = null

  Given("a dispute report asked for as {string}") { (f: String) =>
    p.usual().publish(): Unit
    format = f.toLowerCase
  }

  When("it is generated") { () =>
    bytes = kit.getBytes(s"/players/${p.player}/dispute-report?sessionId=${p.logins.head.id}&format=$format", kit.asOperator)
  }

  Then("it is served as an attachment under a file name carrying the reference number") { () =>
    val (status, headers, body) = bytes
    assertEquals(status, 200, String(body))
    val r   = Reply(status, "", headers)
    val ref = Disputed.reference(r)
    assertEquals(r.header("Content-Disposition"), Some(s"attachment; filename=\"dispute-report-$ref.$format\""))
    val tpe = Map("html" -> "text/html", "csv" -> "text/csv", "pdf" -> "application/pdf")(format)
    assert(r.header("Content-Type").exists(_.startsWith(tpe)), r.header("Content-Type"))
    if format == "pdf" then assertEquals(String(body.take(5), "US-ASCII"), "%PDF-")
  }

  // ── a job ───────────────────────────────────────────────────────────────────────────────────────

  Given("a date range whose rounds exceed the synchronous limit") { () =>
    val before = kit.reporting
    kit.setReporting(_.copy(syncRows = 3))
    restore = Some(() => kit.setReporting(_ => before))
    p.usual().publish(): Unit
  }

  When("the agent asks for the dispute report") { () =>
    last = p.byRange(d, d, "csv")
  }

  Then("a job and its reference number are returned") { () =>
    assertEquals((last.status, last.json.path("status").asText), (202, Document.Generating), last.text)
    reference = last.json.path("reference").asText
    assert(reference.nonEmpty)
  }

  Then("the agent can read its progress") { () =>
    val r = kit.get(s"/documents/$reference", kit.asOperator)
    assert(r.status == 200 || r.status == 202, r.text)
    if r.status == 202 then
      assertEquals(r.json.path("reference").asText, reference)
      assert((0 to 100).contains(r.json.path("progress").asInt(-1)), r.text)
  }

  Then("the document is served once ready") { () =>
    val r = Disputed.served(kit, reference, kit.asOperator)
    assertEquals(r.status, 200, r.text)
    assertEquals(lines(r)(1)(0), reference)
    assertEquals(rows(r).size, p.rounds.size)
  }

  // ── a document's lifetime ───────────────────────────────────────────────────────────────────────

  Given("a generated dispute report document") { () =>
    p.usual().publish(): Unit
    val r = p.bySession(format = "csv")
    assertEquals(r.status, 200, r.text)
    reference = Disputed.reference(r)
    Disputed.inView(kit, reference)
  }

  When("{string} hours have passed") { (hours: String) =>
    kit.moveClock(hours.toInt.hours + 1.minute)
  }

  Then("the document is no longer served") { () =>
    val r = kit.get(s"/documents/$reference", kit.asOperator)
    assertEquals((r.status, r.error), (410, "document_expired"), r.text)
  }

  Then("the record of the report, its reference number, who asked, the period and when, remains") { () =>
    def listed = kit.get(s"/documents?kind=${Document.DisputeReport}&from=${kit.today.minusDays(3)}&to=${kit.today}", kit.asOperator)
    // The list is a view: the record shows once the sweep's expiry reaches it.
    val (r, mine) = kit.eventually(s"$reference listed expired") {
      val r = listed
      r.json.path("documents").elements.asScala.find(_.path("reference").asText == reference)
        .filter(_.path("status").asText == Document.Expired).map(r -> _)
    }
    assertEquals(mine.path("endedBy").asText, Document.Lifetime)
    val l = p.logins.head
    assertEquals(mine.path("askedBy").asText, "op-1")
    assertEquals(mine.path("parameters").path("sessionId").asText, l.id)
    assertEquals(mine.path("parameters").path("periodFrom").asText, l.started.toString)
    assert(mine.path("generatedAt").asText.nonEmpty)
    assert(!r.text.contains(p.email), "a record names no person")
  }

  // ── a round the provider's record lacks ─────────────────────────────────────────────────────────

  Given("a round the wallet settled that the game provider's record lacks") { () =>
    val l = p.login(p.at(d, "16:00"), Some(p.at(d, "17:00")))
    val g = p.game(l, p.at(d, "16:01"), Some(p.at(d, "16:30")))
    p.round(g, p.at(d, "16:02"), 1_00, 0)
    p.walletOnly(Some(g), p.at(d, "16:03"), 2_50, 4_00)
    p.publish(): Unit
  }

  When("the dispute report is generated") { () =>
    last = p.bySession(format = "csv")
  }

  Then("the round is included from the wallet's facts") { () =>
    assertEquals(last.status, 200, last.text)
    val w = p.rounds.last
    assert(rows(last).exists(r => r(3) == w.at.toString && r(4) == money(2_50) && r(5) == money(4_00)), last.text)
  }

  Then("it is marked as unmatched") { () =>
    val w = p.rounds.last
    assertEquals(rows(last).find(_(3) == w.at.toString).map(_.last), Some(Disputes.Unmatched))
  }
