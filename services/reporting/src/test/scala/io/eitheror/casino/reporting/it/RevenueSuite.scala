package io.eitheror.casino.reporting.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.reporting.documents.Document
import io.eitheror.casino.reporting.it.FactSet.Expected

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The revenue report against the whole service (US1; quickstart §3): three players over two days of the brand's zone
 * and a quiet third, in two currencies, a bet at 23:30 and one at 00:30, a stake from the converted pool and one from
 * restricted funds, a round settled after midnight, a free-spin win, bonus cost, a chargeback and a withdrawal. Every
 * figure is held to `FactSet`'s, computed from the facts alone; the deposit pool's GGR per day to attribution's
 * `RealMoney` fold restated (SC-005); a rebuild into a new generation answers byte for byte the same (SC-004).
 */
class RevenueSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 300.seconds
  private lazy val kit = SharedKit.kit

  private var d1: LocalDate = null
  private def d2 = d1.plusDays(1)
  private def d3 = d1.plusDays(2)
  private var facts: FactSet = null

  override def beforeAll(): Unit =
    d1 = kit.days()
    val s  = FactSet(kit.zone)
    val p1 = s.register("rev-p1", s.at(d1.minusDays(3), "09:00"))
    val p2 = s.register("rev-p2", s.at(d1.minusDays(3), "09:00"))
    val p3 = s.register("rev-p3", s.at(d1.minusDays(3), "09:00"), test = true)
    // p1 in EUR: a round won the same day, a round staked at 23:30 and won after midnight, a rollback, a stake at 00:30.
    s.deposit(p1, "EUR", 100_00, s.at(d1, "10:00"))
    s.bet(p1, "EUR", "rev-r1", 10_00, 10_00, s.at(d1, "10:05"))
    s.win(p1, "EUR", "rev-r1", 4_00, 4_00, s.at(d1, "10:06"))
    s.bet(p1, "EUR", "rev-r2", 5_00, 5_00, s.at(d1, "23:30"))
    s.win(p1, "EUR", "rev-r2", 8_00, 8_00, s.at(d2, "00:10"))
    s.bet(p1, "EUR", "rev-r3", 3_00, 3_00, s.at(d2, "11:00"))
    s.rollback(p1, "EUR", "rev-r3", 3_00, 3_00, s.at(d2, "11:01"))
    s.bet(p1, "EUR", "rev-r8", 1_00, 1_00, s.at(d2, "00:30"))
    s.bonus(p1, "EUR", 5_00, s.at(d1, "12:00"))
    s.chargeback(p1, "EUR", 3_00, s.at(d2, "15:00"))
    s.withdrawal(p1, "EUR", 30_00, s.at(d1, "18:00"), s.at(d2, "09:00"))
    // p2: a converted stake and a restricted one count in GGR only; a provider credit is a win outside a round; USD too.
    s.deposit(p2, "EUR", 20_00, s.at(d2, "08:00"))
    s.bet(p2, "EUR", "rev-r4", 6_00, 0, s.at(d2, "12:00"), convertedPart = 6_00)
    s.loss(p2, "EUR", "rev-r4", s.at(d2, "12:01"))
    s.bet(p2, "EUR", "rev-r5", 2_00, 0, s.at(d2, "12:02"))
    s.credit(p2, "EUR", 1_50, s.at(d2, "12:03"))
    s.freeSpinsWon(p2, "EUR", 1_25, s.at(d2, "12:04"))
    s.deposit(p2, "USD", 50_00, s.at(d1, "07:00"))
    s.bet(p2, "USD", "rev-r6", 7_00, 7_00, s.at(d1, "07:05"))
    s.win(p2, "USD", "rev-r6", 2_00, 2_00, s.at(d1, "07:06"))
    // p3, a test account: counted unless the brand excludes test accounts.
    s.deposit(p3, "EUR", 20_00, s.at(d1, "13:00"))
    s.bet(p3, "EUR", "rev-r7", 9_00, 9_00, s.at(d1, "13:05"))
    s.publish(kit)
    facts = s

  private def ggr(currency: String, from: LocalDate = d1, to: LocalDate = d3, as: Seq[(String, String)] = kit.asFinance): Reply =
    kit.get(s"/reports/ggr?from=$from&to=$to&currency=$currency", as)

  /** Minor units as the original's two-place string, written here rather than by the service's renderer. */
  private def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  private def summaryOf(e: Expected): Map[String, String] = Map(
    "totalBets" -> money(e.bets), "totalWins" -> money(e.wins), "ggr" -> money(e.ggr), "depositPoolGgr" -> money(e.pool),
    "bonusCosts" -> money(e.bonus), "chargebacks" -> money(e.chargebacks), "ngr" -> money(e.ngr),
    "ngrBasis" -> "before programme fees", "totalDeposits" -> money(e.deposits), "totalWithdrawals" -> money(e.withdrawals),
    "netCashFlow" -> money(e.netCashFlow))

  private def dayOf(day: LocalDate, e: Expected): Map[String, String] = Map(
    "date" -> day.toString, "bets" -> money(e.bets), "wins" -> money(e.wins), "ggr" -> money(e.ggr),
    "depositPoolGgr" -> money(e.pool), "bonusCosts" -> money(e.bonus), "ngr" -> money(e.ngr))

  private def fields(n: JsonNode): Map[String, String] = n.properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap

  /** What the report must say for the three days, as fields. */
  private def expected(currency: String, exclude: Boolean = false): (Map[String, String], Vector[Map[String, String]]) =
    (summaryOf(facts.total(d1, d3, currency, exclude)), Vector(d1, d2, d3).map(d => dayOf(d, facts.figures(d, currency, exclude))))

  private def shown(r: Reply): (Map[String, String], Vector[Map[String, String]]) =
    (fields(r.json.path("summary")), r.json.path("dailyBreakdown").elements.asScala.toVector.map(fields))

  /** The report once the days have folded every fact. */
  private def settled(currency: String, exclude: Boolean = false): Reply =
    val want = expected(currency, exclude)
    kit.eventually(s"the $currency report equal to the facts' figures", 60.seconds)(Some(ggr(currency)).filter(r => r.status == 200 && shown(r) == want))

  test("the summary and each day are the exact sums over every player in the currency asked for, in the brand's zone") {
    for c <- Vector("EUR", "USD") do
      val r = settled(c)
      assertEquals(shown(r), expected(c))
      val j = r.json
      assertEquals((j.path("from").asText, j.path("to").asText, j.path("currency").asText), (d1.toString, d3.toString, c))
      assertEquals(j.path("currencies").elements.asScala.map(_.asText).toVector, kit.heldCurrencies)
      assertEquals(j.path("summary").fieldNames.asScala.toVector, Vector("totalBets", "totalWins", "ggr", "depositPoolGgr",
        "bonusCosts", "chargebacks", "ngr", "ngrBasis", "totalDeposits", "totalWithdrawals", "netCashFlow"))
    // Read by hand: d1's EUR stakes are p1's 10 and 5 and p3's 9, its wins p1's 4; d2's wins the 8 after midnight and the credit.
    val days = shown(settled("EUR"))._2
    assertEquals(days.map(_("bets")), Vector("24.00", "9.00", "0.00"))
    assertEquals(days.map(_("wins")), Vector("4.00", "9.50", "0.00"))
  }

  test("a bet at 23:30 counts on its own day, one at 00:30 on the next, and a quiet day shows zero") {
    val days = shown(settled("EUR"))._2
    // The 00:30 stake is 22:30 UTC of the day before: counted on the zone's day.
    assert(facts.at(d2, "00:30").atZone(java.time.ZoneOffset.UTC).toLocalDate == d1)
    assertEquals(days.head("bets"), money(10_00 + 5_00 + 9_00))
    assertEquals(days(1)("bets"), money(1_00 + 6_00 + 2_00))
    assertEquals(days(2).removed("date").values.toSet, Set("0.00"))
  }

  test("the deposit pool's GGR per day equals attribution's RealMoney fold summed over the players (SC-005)") {
    val days = shown(settled("EUR"))._2
    for (day, row) <- Vector(d1, d2, d3).zip(days) do
      assertEquals(row("depositPoolGgr"), money(facts.ggrDaily(day, "EUR").values.sum), s"$day")
    // Round 2's win after midnight takes from its round's day; the converted and restricted stakes add nothing.
    assertEquals(days.map(_("depositPoolGgr")), Vector(money(10_00 - 4_00 + 5_00 - 8_00 + 9_00), money(1_00), "0.00"))
    assertEquals(days(1)("ggr"), money((1_00 + 6_00 + 2_00) - (8_00 + 1_50)))
  }

  test("a test account is left out when the brand excludes test accounts, and counted when it does not") {
    try
      kit.setReporting(_.copy(excludeTestAccounts = true))
      val r = settled("EUR", exclude = true)
      assertEquals(shown(r)._2.head("bets"), money(10_00 + 5_00))
      assertEquals(shown(r)._1("totalDeposits"), money(100_00 + 20_00))
    finally kit.setReporting(_.copy(excludeTestAccounts = false))
    assertEquals(shown(settled("EUR"))._1("totalDeposits"), money(100_00 + 20_00 + 20_00))
  }

  test("the CSV holds the summary row and every daily row with every column, kept with its record and equal to the screen") {
    val screen = shown(settled("EUR"))
    val r = kit.get(s"/reports/ggr?from=$d1&to=$d3&currency=EUR&format=csv", kit.asFinance)
    assertEquals(r.status, 200, r.text)
    val disposition = r.header("Content-Disposition").getOrElse("")
    val reference   = disposition.stripPrefix("attachment; filename=\"ggr-report-").stripSuffix(".csv\"")
    assert(disposition.startsWith("attachment; filename=\"ggr-report-") && reference.length == 36, disposition)
    assert(r.header("Content-Type").exists(_.startsWith("text/csv")), r.headers.toString)
    val lines = r.text.split("\r\n").toVector
    val header = lines.head.split(',').toVector
    assertEquals(header, Vector("date", "bets", "wins", "ggr", "depositPoolGgr", "bonusCosts", "chargebacks", "ngr", "deposits",
      "withdrawals", "netCashFlow", "currency", "ngrBasis"))
    val rows = lines.tail.map(l => header.zip(l.split(',')).toMap)
    assertEquals(rows.size, 4)
    val (summary, days) = screen
    assertEquals(rows.head("date"), s"$d1/$d3")
    for (csv, json) <- Vector("bets" -> "totalBets", "wins" -> "totalWins", "ggr" -> "ggr", "depositPoolGgr" -> "depositPoolGgr",
        "bonusCosts" -> "bonusCosts", "chargebacks" -> "chargebacks", "ngr" -> "ngr", "deposits" -> "totalDeposits",
        "withdrawals" -> "totalWithdrawals", "netCashFlow" -> "netCashFlow", "ngrBasis" -> "ngrBasis")
    do assertEquals(rows.head(csv), summary(json), csv)
    for (row, day) <- rows.tail.zip(days) do
      assertEquals(row.removedAll(Seq("chargebacks", "deposits", "withdrawals", "netCashFlow", "currency", "ngrBasis")), day)
    for (row, d) <- rows.tail.zip(Vector(d1, d2, d3)) do
      val e = facts.figures(d, "EUR")
      assertEquals((row("chargebacks"), row("deposits"), row("withdrawals"), row("currency")),
        (money(e.chargebacks), money(e.deposits), money(e.withdrawals), "EUR"))
    // Kept under the brand's bucket with a record naming who asked and what, and no person.
    val kept = kit.testKit.storage.default.getBytes(s"reports/report-export/$reference.csv").map((_, b) => String(b, UTF_8))
    assertEquals(kept, Some(r.text))
    val record = kit.components.forEventSourcedEntity(EntityId(reference)).call(Document.get).invoke()
    assertEquals((record.kind, record.format, record.status, record.askedBy, record.from, record.to, record.rows),
      ("report-export", "csv", "ready", "finance-1", Some(d1.toString), Some(d3.toString), 4L))
    assertEquals(record.objectName, Some(s"reports/report-export/$reference.csv"))
  }

  test("a currency the brand holds no figures in is refused naming the currencies it holds") {
    val r = ggr("JPY")
    assertEquals((r.status, r.error), (400, "unknown_currency"), r.text)
    assertEquals(r.json.path("currencies").elements.asScala.map(_.asText).toVector, kit.heldCurrencies)
  }

  test("a period before the first complete day is refused naming that day; a bad date is refused naming the field") {
    val r = ggr("EUR", from = Kit.SeedDay.minusDays(1), to = Kit.SeedDay)
    assertEquals((r.status, r.error, r.json.path("completeFrom").asText), (409, "incomplete_period", Kit.SeedDay.toString), r.text)
    val bad = kit.get("/reports/ggr?from=yesterday", kit.asFinance)
    assertEquals((bad.status, bad.error, bad.json.path("field").asText), (400, "invalid_date", "from"), bad.text)
  }

  test("with no period asked the report is the month to date in the brand's zone") {
    val j = kit.get("/reports/ggr?currency=EUR", kit.asFinance).json
    assertEquals((j.path("from").asText, j.path("to").asText), (kit.today.withDayOfMonth(1).toString, kit.today.toString))
    assertEquals(j.path("dailyBreakdown").size, kit.today.getDayOfMonth)
  }

  test("a rebuild into a new generation from the topics answers byte for byte the same (SC-004)") {
    val before = Vector("EUR", "USD").map(c => settled(c).text)
    val generation = kit.settings.generation
    assertEquals(kit.rebuild(), generation + 1)
    for (c, text) <- Vector("EUR", "USD").zip(before) do
      kit.eventually(s"the rebuilt $c report", 60.seconds)(Some(ggr(c)).filter(_.text == text)): Unit
    assertEquals(kit.get("/status", kit.asOperator).json.path("generation").asInt, generation + 1)
  }
