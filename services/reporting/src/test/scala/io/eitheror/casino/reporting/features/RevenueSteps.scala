package io.eitheror.casino.reporting.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.reporting.day.DaysView
import io.eitheror.casino.reporting.it.{FactSet, Reply}
import io.eitheror.casino.reporting.it.FactSet.Expected

import java.time.{LocalDate, ZoneId}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/reporting/revenue.feature (US1; FR-002–FR-007, FR-012, FR-024; SC-004, SC-005), over a `FactSet` of the
 * scenario's own: three players over two days of the brand's zone and a quiet third, in two currencies, with a bet at
 * 23:30 and a round won after midnight. Every figure is held to the set's, computed from the facts alone.
 */
class RevenueFeatures extends ReportingFeatures("revenue.feature"):
  private var facts: FactSet  = null
  private var d1: LocalDate   = null
  private var from: LocalDate = null
  private var to: LocalDate   = null
  private var screen: Reply   = null
  private var before: String  = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    facts = null; d1 = null; from = null; to = null; screen = null; before = null

  private def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  /** "10.00" as minor units. */
  private def minor(text: String): Long = java.math.BigDecimal(text).movePointRight(2).longValueExact

  private def ggr(currency: Option[String], f: LocalDate = from, t: LocalDate = to, format: String = ""): Reply =
    val c = currency.fold("")(c => s"&currency=$c")
    kit.get(s"/reports/ggr?from=$f&to=$t$c$format", kit.asFinance)

  private def fields(n: JsonNode): Map[String, String] = n.properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap

  private def summaryOf(e: Expected): Map[String, String] = Map(
    "totalBets" -> money(e.bets), "totalWins" -> money(e.wins), "ggr" -> money(e.ggr), "depositPoolGgr" -> money(e.pool),
    "bonusCosts" -> money(e.bonus), "chargebacks" -> money(e.chargebacks), "ngr" -> money(e.ngr),
    "ngrBasis" -> "before programme fees", "totalDeposits" -> money(e.deposits), "totalWithdrawals" -> money(e.withdrawals),
    "netCashFlow" -> money(e.netCashFlow))

  private def dayFields(day: LocalDate, e: Expected): Map[String, String] = Map(
    "date" -> day.toString, "bets" -> money(e.bets), "wins" -> money(e.wins), "ggr" -> money(e.ggr),
    "depositPoolGgr" -> money(e.pool), "bonusCosts" -> money(e.bonus), "ngr" -> money(e.ngr))

  private def days: Vector[LocalDate] = Iterator.iterate(from)(_.plusDays(1)).takeWhile(!_.isAfter(to)).toVector

  private def wanted(currency: String): (Map[String, String], Vector[Map[String, String]]) =
    (summaryOf(facts.total(from, to, currency)), days.map(d => dayFields(d, facts.figures(d, currency))))

  private def shown(r: Reply): (Map[String, String], Vector[Map[String, String]]) =
    (fields(r.json.path("summary")), r.json.path("dailyBreakdown").elements.asScala.toVector.map(fields))

  /**
   * The report once the days have folded the scenario's facts, which they do after the facts are published: asked
   * again until it says what the facts do, then the last answer, which the scenario's Then holds to them.
   */
  private def settled(currency: Option[String], expectedIn: String): Reply =
    val want     = wanted(expectedIn)
    val deadline = 60.seconds.fromNow
    var r        = ggr(currency)
    while !(r.status == 200 && shown(r) == want) && deadline.hasTimeLeft() do
      Thread.sleep(200)
      r = ggr(currency)
    r

  /** The scenario's facts: what revenue.feature's background calls facts for every player. */
  private def everyPlayer(zone: ZoneId): FactSet =
    val s  = FactSet(zone)
    val id = scenarioId.takeRight(24)
    val p1 = s.register(s"p1-$id", s.at(d1.minusDays(2), "09:00"))
    val p2 = s.register(s"p2-$id", s.at(d1.minusDays(2), "09:00"))
    val p3 = s.register(s"p3-$id", s.at(d1.minusDays(2), "09:00"), test = true)
    s.deposit(p1, "EUR", 50_00, s.at(d1, "09:00"))
    s.bet(p1, "EUR", s"r1-$id", 12_00, 12_00, s.at(d1, "09:10"))
    s.win(p1, "EUR", s"r1-$id", 7_00, 7_00, s.at(d1, "09:11"))
    s.bet(p1, "EUR", s"r2-$id", 4_00, 4_00, s.at(d1, "23:30"))
    s.win(p1, "EUR", s"r2-$id", 6_00, 6_00, s.at(d1.plusDays(1), "00:05"))
    s.bonus(p1, "EUR", 2_50, s.at(d1, "10:00"))
    s.chargeback(p1, "EUR", 1_00, s.at(d1.plusDays(1), "14:00"))
    s.withdrawal(p1, "EUR", 20_00, s.at(d1, "16:00"), s.at(d1.plusDays(1), "10:00"))
    s.deposit(p2, "EUR", 30_00, s.at(d1.plusDays(1), "08:00"))
    s.bet(p2, "EUR", s"r3-$id", 5_00, 2_00, s.at(d1.plusDays(1), "08:30"), convertedPart = 3_00)
    s.rollback(p2, "EUR", s"r3-$id", 5_00, 2_00, s.at(d1.plusDays(1), "08:31"))
    s.credit(p2, "EUR", 75, s.at(d1.plusDays(1), "08:40"))
    s.deposit(p2, "USD", 40_00, s.at(d1, "11:00"))
    s.bet(p2, "USD", s"r4-$id", 8_00, 8_00, s.at(d1, "11:05"))
    s.deposit(p3, "EUR", 10_00, s.at(d1, "12:00"))
    s.bet(p3, "EUR", s"r5-$id", 3_00, 3_00, s.at(d1, "12:05"))
    s

  Given("brand {string} with facts for every player") { (slug: String) =>
    assertEquals(kit.brand, slug)
    d1 = kit.days()
    facts = everyPlayer(kit.zone)
    facts.publish(kit)
  }

  Given("a period") { () =>
    from = d1; to = d1.plusDays(2)
  }

  When("finance runs the revenue report for it in {string}") { (currency: String) =>
    last = settled(Some(currency), currency)
  }

  Then("total stakes, total wins, GGR, the deposit pool's GGR, bonus cost, NGR, total deposits, total withdrawals and net cash flow are the exact sums over every player, in {string}") {
    (currency: String) =>
      assertEquals(last.status, 200, last.text)
      assertEquals(last.json.path("currency").asText, currency)
      assertEquals(shown(last)._1, summaryOf(facts.total(from, to, currency)))
  }

  Then("the report names the currencies it holds figures in") { () =>
    assertEquals(last.json.path("currencies").elements.asScala.map(_.asText).toVector, kit.heldCurrencies)
    assert(facts.currencies.forall(kit.heldCurrencies.contains), facts.currencies.toString)
  }

  Given("a period of several days with a bet placed at {string} in the brand's reporting zone") { (time: String) =>
    assertEquals(time, "23:30")
    from = d1; to = d1.plusDays(2)
  }

  When("finance runs the revenue report for it") { () =>
    // With no currency asked the brand's default: none is set, so the currency most of its wallets hold (R6).
    last = settled(None, "EUR")
  }

  Then("it has one row per reporting day in the brand's reporting zone") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(shown(last)._2.map(_("date")), days.map(_.toString))
    assertEquals(shown(last)._2, wanted("EUR")._2)
  }

  Then("a day with no activity shows zero") { () =>
    assertEquals(shown(last)._2.last.removed("date").values.toSet, Set("0.00"))
  }

  Then("the bet counts on the day it was placed in the brand's reporting zone, not the next day elsewhere") { () =>
    val late = facts.at(d1, "23:30")
    // Elsewhere, east of the zone, the bet is already on the next day.
    assertEquals(late.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate, d1.plusDays(1))
    val rows = shown(last)._2
    assertEquals(rows.head("bets"), money(12_00 + 4_00 + 3_00))
    assertEquals(rows(1)("bets"), money(5_00 - 5_00))
  }

  Given("the revenue report on screen") { () =>
    from = d1; to = d1.plusDays(2)
    screen = settled(Some("EUR"), "EUR")
    assertEquals(shown(screen), wanted("EUR"))
  }

  When("finance exports it") { () =>
    last = ggr(Some("EUR"), format = "&format=csv")
  }

  private def csvRows: Vector[Map[String, String]] =
    val lines  = last.text.split("\r\n").toVector
    val header = lines.head.split(',').toVector
    lines.tail.map(l => header.zip(l.split(",", -1)).toMap)

  Then("the file holds the summary row and every daily row with every column") { () =>
    assertEquals(last.status, 200, last.text)
    assert(last.header("Content-Disposition").exists(_.matches("attachment; filename=\"ggr-report-[0-9a-f-]{36}\\.csv\"")),
      last.headers.toString)
    val header = last.text.split("\r\n").head.split(',').toVector
    assertEquals(header, Vector("date", "bets", "wins", "ggr", "depositPoolGgr", "bonusCosts", "chargebacks", "ngr", "deposits",
      "withdrawals", "netCashFlow", "currency", "ngrBasis"))
    assertEquals(csvRows.map(_("date")), s"$from/$to" +: days.map(_.toString))
    assert(csvRows.forall(r => r.size == header.size && r.values.forall(_.nonEmpty)), csvRows.toString)
  }

  Then("the figures equal the screen's") { () =>
    val (summary, daily) = shown(screen)
    val total = csvRows.head
    assertEquals(Vector(total("bets"), total("wins"), total("ggr"), total("depositPoolGgr"), total("bonusCosts"), total("chargebacks"),
      total("ngr"), total("deposits"), total("withdrawals"), total("netCashFlow"), total("ngrBasis")),
      Vector("totalBets", "totalWins", "ggr", "depositPoolGgr", "bonusCosts", "chargebacks", "ngr", "totalDeposits",
        "totalWithdrawals", "netCashFlow", "ngrBasis").map(summary))
    assertEquals(csvRows.tail.map(_.view.filterKeys(daily.head.keySet).toMap), daily)
  }

  Given("the revenue report for a closed day") { () =>
    from = d1; to = d1
    // The day's facts folded first: a close runs over the days the open-days view holds, which follows the days.
    settled(Some("EUR"), "EUR"): Unit
    // The close-days timer runs every five minutes of the clock; each move past a run closes every day then due and
    // in the view, so the clock is moved until this one is closed.
    val deadline = 60.seconds.fromNow
    def closed   = DaysView.rows(kit.views, kit.settings.generation, d1, d1).exists(_.closed)
    while !closed && deadline.hasTimeLeft() do
      val runs = kit.probe.fired("close-days").size
      kit.moveClock(5.minutes)
      kit.eventually("a run of close-days")(Option.when(kit.probe.fired("close-days").size > runs)(())): Unit
      Thread.sleep(500)
    assert(closed, s"$d1 not closed")
    last = settled(Some("EUR"), "EUR")
    assertEquals(shown(last), wanted("EUR"))
  }

  private var programme: Long = 0

  When("the programme's {string} facts for that day are summed over the brand's attributed players") { (family: String) =>
    assertEquals(family, "ggr-daily")
    // Every player of the scenario is attributed; attribution's fold is restated by the fact set, not run here.
    programme = facts.ggrDaily(d1, "EUR").values.sum
  }

  Then("they equal that day's deposit pool GGR for those players") { () =>
    assertEquals(shown(last)._2.head("depositPoolGgr"), money(programme))
    assertEquals(programme, (12_00L - 7_00) + (4_00 - 6_00) + 3_00)
  }

  Given("a day on which a player staked {string} from the deposit pool, {string} from the converted pool and {string} from restricted funds, and won nothing") {
    (deposit: String, converted: String, restricted: String) =>
      val day = d1.plusDays(5)
      val s   = FactSet(kit.zone)
      val p   = s.register(s"pools-${scenarioId.takeRight(24)}", s.at(day, "08:00"))
      s.bet(p, "EUR", s"pool-d-$p", minor(deposit), minor(deposit), s.at(day, "10:00"))
      s.bet(p, "EUR", s"pool-c-$p", minor(converted), 0, s.at(day, "10:01"), convertedPart = minor(converted))
      s.bet(p, "EUR", s"pool-r-$p", minor(restricted), 0, s.at(day, "10:02"))
      for r <- Vector("d", "c", "r") do s.loss(p, "EUR", s"pool-$r-$p", s.at(day, "10:05"))
      s.publish(kit)
      facts = s; from = day; to = day
  }

  When("finance runs the revenue report for that day") { () =>
    last = settled(Some("EUR"), "EUR")
  }

  Then("GGR is {string}") { (amount: String) =>
    assertEquals(last.json.path("summary").path("ggr").asText, amount)
  }

  Then("the deposit pool's GGR is {string}") { (amount: String) =>
    assertEquals(last.json.path("summary").path("depositPoolGgr").asText, amount)
  }

  When("finance runs the revenue report for it in {string}, a currency the brand holds no wallet in") { (currency: String) =>
    last = ggr(Some(currency))
  }

  Then("it is refused naming the currencies the brand holds") { () =>
    assertEquals((last.status, last.error), (400, "unknown_currency"), last.text)
    assertEquals(last.json.path("currencies").elements.asScala.map(_.asText).toVector, kit.heldCurrencies)
  }

  Given("the reporting view rebuilt from the brand's facts") { () =>
    from = d1; to = d1.plusDays(2)
    before = settled(Some("EUR"), "EUR").text
    kit.rebuild(): Unit
  }

  When("finance runs the revenue report again for the same period") { () =>
    last = settled(Some("EUR"), "EUR")
  }

  Then("every figure is identical") { () =>
    assertEquals(last.text, before)
  }
