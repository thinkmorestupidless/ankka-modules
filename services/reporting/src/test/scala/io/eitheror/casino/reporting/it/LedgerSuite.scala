package io.eitheror.casino.reporting.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.DataSubject
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.{Instant, LocalDate}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * T044: the transaction report against the whole service (US3; FR-008, FR-010, FR-012, FR-025; SC-003): a player
 * with 5,000 transactions in a day of the suite's own, read a hundred at a time and exported whole; the search by
 * part of an email and the type filter; an erased player's rows reading `[erased]` in the ledger and the player report
 * with every figure unchanged. Every row is held to the suite's `FactSet`.
 */
class LedgerSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 600.seconds
  private lazy val kit = SharedKit.kit

  private var day: LocalDate = null
  private var s: FactSet     = null
  private val many  = "ledger-many"
  private val ada   = "ledger-ada"
  private val grace = "ledger-grace"
  private val gone  = "ledger-gone"
  private val Count = 5_000

  override def beforeAll(): Unit =
    day = kit.days()
    s = FactSet(kit.zone)
    s.register(many, s.at(day.minusDays(1), "08:00"), email = Some("many.transactions.of.one.player@ledger.example"))
    s.register(ada, s.at(day.minusDays(1), "08:00"), email = Some("Ada.Lovelace@ledger.example"), country = "GB")
    s.register(grace, s.at(day.minusDays(1), "08:00"), email = Some("grace.hopper@ledger.example"), country = "DE")
    s.register(gone, s.at(day.minusDays(1), "08:00"), email = Some("soon.forgotten.player@ledger.example"))
    // One a second from 00:10 in the zone, so the newest-first order has no tie.
    val start = s.at(day, "00:10")
    (0 until Count).foreach { i =>
      val at = start.plusSeconds(i.toLong)
      if i % 2 == 0 then s.deposit(many, "EUR", 10_00 + i, at)
      else s.bet(many, "EUR", s"ledger-r-$i", 1_00 + i, 1_00 + i, at)
    }
    val t = s.at(day, "03:00")
    s.deposit(ada, "EUR", 40_00, t)
    s.bet(ada, "EUR", "ledger-ada-r", 5_00, 5_00, t.plusSeconds(1))
    s.win(ada, "EUR", "ledger-ada-r", 8_00, 5_00, t.plusSeconds(2))
    s.award(ada, "EUR", "ledger-ada-a", 3_00, 30_00, t.plusSeconds(3))
    s.forfeitAward(ada, "EUR", "ledger-ada-a", 3_00, t.plusSeconds(4))
    s.deposit(grace, "EUR", 25_00, t.plusSeconds(5))
    s.bet(grace, "EUR", "ledger-grace-r", 2_00, 2_00, t.plusSeconds(6))
    s.deposit(gone, "EUR", 60_00, t.plusSeconds(7))
    s.bet(gone, "EUR", "ledger-gone-r", 9_00, 9_00, t.plusSeconds(8))
    s.win(gone, "EUR", "ledger-gone-r", 4_00, 4_00, t.plusSeconds(9))
    s.publish(kit)

  private def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString
  private def from: Instant = day.atStartOfDay(kit.zone).toInstant
  private def to: Instant   = day.plusDays(1).atStartOfDay(kit.zone).toInstant

  private def email(p: String): String = s.registered.find(_.playerId == p).flatMap(_.email).map(_.toString).getOrElse("")
  private def cut(e: String): String   = if e.length > 25 then e.take(25) + "…" else e

  /** A ledger row as the report must show it, from the facts. */
  private def wanted(t: FactSet.Tx, shown: String => String = p => cut(email(p))): Vector[String] =
    Vector(t.transactionId, t.at.toString, t.player, shown(t.player), t.tpe, money(t.amount), t.currency, t.reference)

  private def row(n: JsonNode): Vector[String] =
    Vector("transactionId", "timestamp", "playerId", "truncatedEmail", "transactionType", "amount", "currency", "reference")
      .map(n.path(_).asText)

  private def rows(j: JsonNode): Vector[Vector[String]] = j.path("transactions").elements.asScala.toVector.map(row)

  private def ledger(query: String): Reply = kit.get(s"/reports/transactions?from=$day&to=$day$query", kit.asFinance)

  /** The ledger once it holds `n` rows for the query, which it does after the facts fold. */
  private def settled(query: String, n: Int): JsonNode =
    kit.eventually(s"the ledger holds $n rows for $query", 120.seconds)(
      Option(ledger(query)).filter(r => r.status == 200 && r.json.path("totalCount").asLong == n).map(_.json))

  test("a player with 5,000 transactions: every one appears, newest first, a hundred to a page") {
    val want = s.ledger(from, to, of = Some(Set(many))).map(wanted(_))
    assertEquals(want.size, Count)
    val q     = "&playerEmail=many.transactions"
    val first = settled(q, Count)
    assertEquals((first.path("page").asInt, first.path("pageSize").asInt, first.path("totalPages").asInt), (0, 100, 50))
    val pages = (0 until 50).toVector.map(p => ledger(s"$q&page=$p")).map { r =>
      assertEquals(r.status, 200, r.text)
      rows(r.json)
    }
    assert(pages.forall(_.size == 100))
    assertEquals(pages.flatten, want)
    assertEquals(rows(ledger(s"$q&page=50").json), Vector.empty)
    assertEquals(rows(ledger(s"$q&pageSize=500").json).size, 100, "a page is never more than a hundred")
    assert(want.head(3).endsWith("…") && want.head(3).length == 26, want.head(3))
  }

  test("the export holds every one of the 5,000, as JSON and as CSV, the CSV a job over the synchronous limit (FR-022)") {
    val want = s.ledger(from, to, of = Some(Set(many))).map(wanted(_))
    settled("&playerEmail=many.transactions", Count): Unit
    val json = kit.get(s"/reports/transactions/export?from=$day&to=$day&playerEmail=many.transactions", kit.asFinance)
    assertEquals(json.status, 200, json.text.take(500))
    assertEquals((json.json.path("totalCount").asLong, json.json.path("page").asInt), (Count.toLong, 0))
    assertEquals(rows(json.json), want)
    val job = kit.get(s"/reports/transactions/export?from=$day&to=$day&playerEmail=many.transactions&format=csv", kit.asFinance)
    assertEquals((job.status, job.json.path("status").asText), (202, "generating"), job.text.take(500))
    val reference = job.json.path("reference").asText
    val csv = Disputed.served(kit, reference, kit.asFinance)
    assertEquals(csv.status, 200, csv.text.take(500))
    assertEquals(csv.header("Content-Disposition"), Some(s"attachment; filename=\"transactions-report-$reference.csv\""))
    // Rendered a thousand rows a page; the record says how many, and names the email search only as given.
    val record = Disputed.document(kit, reference)
    assertEquals((record.status, record.rows, record.parameters.get("playerEmail")), ("ready", Count.toLong, Some("given")))
    assert(!record.parameters.values.exists(_.contains("many.transactions")), record.parameters)
    val lines = csv.text.split("\r\n").toVector
    assertEquals(lines.head, "transactionId,timestamp,playerId,truncatedEmail,transactionType,amount,currency,reference")
    assertEquals(lines.tail, want.map(_.mkString(",")))
  }

  test("a search by part of an email, any case, shows only that player's rows, and its export holds them all") {
    val want = s.ledger(from, to, of = Some(Set(ada))).map(wanted(_))
    val j    = settled("&playerEmail=LOVELACE", want.size)
    assertEquals(rows(j), want)
    val exported = kit.get(s"/reports/transactions/export?from=$day&to=$day&playerEmail=lovelace", kit.asFinance).json
    assertEquals(rows(exported), want)
    assertEquals(rows(ledger("&playerEmail=nobody-has-this").json).size, 0)
  }

  test("an export of a period with no movement is its header alone, as CSV and as JSON, not a refusal") {
    val empty = Kit.FirstDay.minusDays(2)
    val csv   = kit.get(s"/reports/transactions/export?from=$empty&to=$empty&format=csv", kit.asFinance)
    assertEquals(csv.status, 200, csv.text)
    assertEquals(csv.text.linesIterator.toVector,
      Vector("transactionId,timestamp,playerId,truncatedEmail,transactionType,amount,currency,reference"))
    val json = kit.get(s"/reports/transactions/export?from=$empty&to=$empty", kit.asFinance)
    assertEquals((json.status, json.json.path("totalCount").asLong, rows(json.json).size), (200, 0L, 0))
  }

  test("the type filter: an exact type, and bonus matching every bonus_ type") {
    for tpe <- Vector("deposit", "bet", "win", "bonus") do
      val want = s.ledger(from, to, tpe, of = Some(Set(ada, grace))).map(wanted(_))
      val got  = (rows(settled(s"&type=$tpe&playerEmail=@ledger.example", s.ledger(from, to, tpe, of = Some(Set(many, ada, grace, gone))).size)))
        .filter(r => Set(ada, grace)(r(2)))
      assertEquals(got, want, tpe)
    assertEquals(s.ledger(from, to, "bonus", of = Some(Set(ada))).map(_.tpe), Vector("bonus_cancel", "bonus_credit"))
  }

  test("an erased player's rows read the email as erased in the ledger and the player report, every figure unchanged") {
    val before = rows(settled("&playerEmail=soon.forgotten", 3))
    assertEquals(before, s.ledger(from, to, of = Some(Set(gone))).map(wanted(_)))
    def playerRow: JsonNode =
      kit.get(s"/reports/players?from=${day.minusDays(1)}&to=${day.minusDays(1)}&currency=EUR", kit.asFinance).json
        .path("players").elements.asScala.find(_.path("playerId").asText == gone).get
    val (d, w, won) = s.lifetimes(gone, "EUR")
    val figures = Vector(money(d), money(w), money(w - won))
    def shownFigures(p: JsonNode) = Vector("lifetimeDeposits", "lifetimeWagered", "lifetimeGgr").map(p.path(_).asText)
    kit.eventually("the player's lifetimes")(Option(playerRow).filter(p => shownFigures(p) == figures)): Unit
    assertEquals(playerRow.path("email").asText, "soon.forgotten.player@ledger.example")

    kit.testKit.erase(DataSubject(s"player/$gone"))

    val after = kit.get(s"/reports/transactions?from=$day&to=$day&type=all", kit.asFinance)
    val mine  = kit.eventually("the erased player's rows")(
      Option(rows(kit.get(s"/reports/transactions/export?from=$day&to=$day", kit.asFinance).json).filter(_(2) == gone)).filter(_.nonEmpty))
    assertEquals(mine, s.ledger(from, to, of = Some(Set(gone))).map(wanted(_, _ => "[erased]")))
    assertEquals(after.status, 200)
    assertEquals(playerRow.path("email").asText, "[erased]")
    assertEquals(shownFigures(playerRow), figures)
    assertEquals(rows(ledger("&playerEmail=soon.forgotten").json).size, 0, "an erased email matches no search")
    kit.testKit.assertNoPersonalValue("soon.forgotten.player@ledger.example")
  }
