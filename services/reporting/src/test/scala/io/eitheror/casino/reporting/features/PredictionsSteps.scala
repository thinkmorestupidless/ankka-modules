package io.eitheror.casino.reporting.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.DataSubject
import io.eitheror.casino.reporting.{ReportingRuntime, Settings}
import io.eitheror.casino.reporting.it.{DayEnded, FactSet, Marketed}

import java.time.LocalDate
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import Marketed.fields

/**
 * features/reporting/predictions.feature (US8; FR-028–FR-032, SC-009), over markets of the scenario's own
 * (`Marketed`): their lives, trades, payments and the takers' fees moved into the fee account, published as spec 023
 * and spec 022 publish them. Every figure is held to the facts. The kit runs one brand's service; a brand without the
 * module is that service with `predictions` taken out of its modules for the scenario.
 */
class PredictionsFeatures extends ReportingFeatures("predictions.feature"):
  private var rate: Long        = 100
  private var p: Marketed       = null
  private var from: LocalDate   = null
  private var to: LocalDate     = null
  private var mk: String        = null
  private var open: String      = null
  private var answer: JsonNode  = null
  private var before: Vector[String] = Vector.empty
  private var report: String    = null
  private var running: Option[Settings] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    p = null; from = null; to = null; mk = null; open = null; answer = null; before = Vector.empty; report = null

  override def afterEach(context: AfterEach): Unit =
    running.foreach(ReportingRuntime.settings.set)
    running = None
    super.afterEach(context)

  Given("brand {string} whose entitlement runs the {string} module, with a taker fee rate of {string} basis points") {
    (slug: String, module: String, bp: String) =>
      assertEquals(slug, kit.brand)
      assert(kit.settings.modules.contains(module), kit.settings.modules.toString)
      rate = bp.toLong
  }

  private def period(currency: String) = s"from=$from&to=$to&currency=$currency"

  // ── the summary ─────────────────────────────────────────────────────────────────────────────────

  Given("a period in which markets opened, traded, resolved and were voided in {string}") { (currency: String) =>
    p = Marketed(kit, kit.days(), currency, rate)
    val d = p.day
    from = d; to = d.plusDays(2)
    val (a, b, c) = (p.market(1), p.market(2), p.market(3))
    val (p1, p2, p3) = (p.player(1), p.player(2), p.player(3))
    p.open(a, p.at("09:00"))
    p.split(a, p1, p2, 10, 60, p.at("10:00"))
    p.direct(a, p3, p1, 4, 70, p.at("11:00"))
    p.closed(a, p.at(d.plusDays(1), "09:00"))
    p.resolved(a, "yes", p.at(d.plusDays(1), "10:00"))
    p.finalised(a, p.at(d.plusDays(1), "11:00"))
    p.positionPaid(a, p1, "yes", 6, 6_00, p.at(d.plusDays(1), "11:00"))
    p.positionPaid(a, p3, "yes", 4, 4_00, p.at(d.plusDays(1), "11:00"))
    p.open(b, p.at("09:30"))
    p.direct(b, p1, p2, 5, 40, p.at("12:00"))
    p.voided(b, p.at(d.plusDays(1), "12:00"))
    p.positionPaid(b, p1, "yes", 5, 1_99, p.at(d.plusDays(1), "12:30"))
    p.remainder(b, 1, p.at(d.plusDays(1), "12:30"))
    p.open(c, p.at(d.plusDays(1), "08:00"))
    p.split(c, p2, p3, 3, 50, p.at(d.plusDays(1), "09:00"))
    p.resolved(c, "yes", p.at(d.plusDays(2), "09:00"))
    p.corrected(c, "no", p.at(d.plusDays(2), "10:00"))
    p.finalised(c, p.at(d.plusDays(2), "11:00"))
    p.positionPaid(c, p3, "no", 3, 3_00, p.at(d.plusDays(2), "11:00"))
    p.publish().settled(d).settled(d.plusDays(1))
  }

  When("finance reads the predictions summary for it in {string}") { (currency: String) =>
    answer = kit.eventually("the summary", 60.seconds)(Some(kit.get(s"/predictions/summary?${period(currency)}", kit.asFinance).json)
      .filter(j => j.path("trades").asLong == p.trades.size && j.path("resolutionPayments").asText == p.money(p.payments())))
  }

  Then("it holds contracts traded, notional volume, trades, distinct traders, taker fees, resolution payments and remainders kept, each the exact fold of the period's trades") { () =>
    val got = fields(answer)
    assertEquals(Vector("contractsTraded", "notionalVolume", "trades", "traders", "takerFees", "resolutionPayments", "remaindersKept")
      .map(got), Vector(p.contracts().toString, p.money(p.notional()), p.trades.size.toString, p.traders().toString,
      p.money(p.fees()), p.money(p.payments()), p.money(p.remainders())))
  }

  Then("the numbers of markets opened, resolved, voided and corrected in the period") { () =>
    val got = fields(answer)
    assertEquals(Vector("marketsOpened", "marketsResolved", "marketsVoided", "marketsCorrected").map(got), Vector("3", "2", "1", "1"))
  }

  // ── the market report ───────────────────────────────────────────────────────────────────────────

  Given("a market that opened, traded {string} contracts at {string} in {string} trades between {string} players and resolved") {
    (contracts: String, price: String, n: String, players: String) =>
      p = Marketed(kit, kit.days(), "EUR", rate)
      from = p.day; to = p.day
      mk = p.market(1); open = p.market(2)
      val cents = java.math.BigDecimal(price).movePointRight(2).longValueExact
      val each  = contracts.toLong / n.toLong
      val who   = (1 to players.toInt).map(p.player).toVector
      p.open(mk, p.at("09:00"))
      // Each trade between the next two players in turn, each buyer the taker.
      for i <- 0 until n.toInt do p.direct(mk, who(i % who.size), who((i + 1) % who.size), each, cents, p.at(f"1${i}%d:00"))
      p.closed(mk, p.at("15:00"))
      p.resolved(mk, "yes", p.at("16:00"))
      p.finalised(mk, p.at("16:30"))
      p.positionPaid(mk, who(0), "yes", each, each * 100, p.at("16:30"))
      // An open market: a split of complete sets and a trade of them.
      p.open(open, p.at("09:00"))
      p.split(open, who(0), who(1), 5, 50, p.at("10:30"))
      p.direct(open, who(2), who(0), 2, 55, p.at("11:30"))
      p.publish().settled()
  }

  private def desk = kit.asStaff(Seq("markets"), "desk-1")

  When("an operator with the markets permission reads the market report") { () =>
    answer = kit.eventually("the market report", 60.seconds)(Some(kit.get(s"/predictions/markets?from=$from&to=$to", desk).json)
      .filter(j => p.marketRow(j, mk).exists(r => r.path("trades").asLong == p.trades.count(_.marketId == mk) &&
        r.path("status").asText == "final")))
  }

  private def row: Map[String, String] = fields(p.marketRow(answer, mk).get)

  Then("the market's row shows its status, its opened, closed and resolved times and its resolution") { () =>
    val r = row
    assertEquals(Vector("status", "openedAt", "closedAt", "resolvedAt", "resolution").map(r),
      Vector("final", p.at("09:00").toString, p.at("15:00").toString, p.at("16:00").toString, "yes"))
  }

  Then("notional volume {string}, {string} trades, {string} traders, its taker fees and its resolution payments") {
    (notional: String, trades: String, traders: String) =>
      val r = row
      assertEquals(Vector("notionalVolume", "trades", "traders", "takerFees", "resolutionPayments").map(r),
        Vector(notional, trades, traders, p.money(p.fees(Some(mk))), p.money(p.payments(Some(mk)))))
      assertEquals(p.money(p.notional(Some(mk))), notional)
  }

  Then("an open market's row shows its open interest at the time of reading") { () =>
    val r = fields(p.marketRow(answer, open).get)
    assertEquals((r("status"), r("openInterest")), ("open", p.openInterest(open).toString))
    assertEquals(p.openInterest(open), 10L, "five sets split, two of them sold on, are ten shares held")
  }

  // ── a voided or corrected market ────────────────────────────────────────────────────────────────

  private def oneMarket(): Unit =
    p = Marketed(kit, kit.days(), "EUR", rate)
    from = p.day; to = p.day
    mk = p.market(1)
    p.open(mk, p.at("09:00"))

  Given("a market that was voided after trading") { () =>
    oneMarket()
    p.split(mk, p.player(1), p.player(2), 3, 60, p.at("10:00"))
    p.voided(mk, p.at("12:00"))
    // The pool's 3.00 divided equally over six shares to the cent, 0.49 a share, the rest kept.
    p.positionPaid(mk, p.player(1), "yes", 3, 1_47, p.at("12:30"))
    p.positionPaid(mk, p.player(2), "no", 3, 1_47, p.at("12:30"))
    p.remainder(mk, 6, p.at("12:30"))
    p.finalised(mk, p.at("13:00"))
    p.publish().settled()
  }

  Given("a market that was resolved and then corrected before it was final") { () =>
    oneMarket()
    p.split(mk, p.player(1), p.player(2), 4, 50, p.at("10:00"))
    p.closed(mk, p.at("11:00"))
    p.resolved(mk, "yes", p.at("12:00"))
    p.corrected(mk, "no", p.at("13:00"))
    p.finalised(mk, p.at("14:00"))
    p.positionPaid(mk, p.player(2), "no", 4, 4_00, p.at("14:00"))
    p.publish().settled()
  }

  Then("the market's row shows void, with each share's equal part paid and the remainder kept") { () =>
    val r = row
    assertEquals(Vector("resolution", "resolutionPayments", "remainderKept").map(r), Vector("void", "2.94", "0.06"))
    assertEquals(p.paid.map(x => x.paidCents.value / x.quantity).distinct, Vector(49L), "every share's part is the same")
    assertEquals(p.payments() + p.remainders(), 3L * 100, "the pool paid out whole")
  }

  Then("the market's row shows the outcome first resolved, the corrected outcome and the payments made on it") { () =>
    val r = row
    assertEquals(Vector("firstResolution", "corrected", "resolution", "resolutionPayments").map(r), Vector("yes", "no", "no", "4.00"))
  }

  // ── the traders ─────────────────────────────────────────────────────────────────────────────────

  private def email(n: Int) = s"trader.${p.tag}.$n@markets.example"

  Given("a period in which players traded") { () =>
    p = Marketed(kit, kit.days(), "EUR", rate)
    from = p.day; to = p.day.plusDays(1)
    val people = FactSet(kit.zone)
    for n <- 1 to 3 do people.register(p.player(n), people.at(p.day.minusDays(1), "09:00"), email = Some(email(n)))
    people.publish(kit)
    val (a, b) = (p.market(1), p.market(2))
    p.open(a, p.at("09:00"))
    p.split(a, p.player(1), p.player(2), 10, 60, p.at("10:00"))
    p.direct(a, p.player(3), p.player(1), 4, 70, p.at("11:00"))
    p.resolved(a, "yes", p.at(p.day.plusDays(1), "09:00"))
    p.finalised(a, p.at(p.day.plusDays(1), "10:00"))
    p.positionPaid(a, p.player(1), "yes", 6, 6_00, p.at(p.day.plusDays(1), "10:00"))
    p.positionPaid(a, p.player(3), "yes", 4, 4_00, p.at(p.day.plusDays(1), "10:00"))
    p.open(b, p.at("09:00"))
    p.direct(b, p.player(2), p.player(3), 6, 35, p.at(p.day.plusDays(1), "11:00"))
    p.publish().settled(p.day).settled(p.day.plusDays(1))
  }

  // The traders report admits compliance as the compliance exports do (contracts/routes.md, decided 2026-11-09).
  private def compliance = kit.asStaff(Seq("brand_compliance"), "co-1")

  private def traders(currency: String): Vector[Map[String, String]] =
    kit.get(s"/predictions/traders?${period(currency)}", compliance).json.path("traders").elements.asScala.map(fields).toVector
      .filter(_("playerId").startsWith(s"mk-${p.tag}"))

  private def wanted(shown: Int => String): Vector[Map[String, String]] = (1 to 3).toVector.map { n =>
    val pl = p.player(n)
    Map("playerId" -> pl, "email" -> shown(n), "contractsTraded" -> p.playerContracts(pl).toString,
      "notionalVolume" -> p.money(p.playerNotional(pl)), "takerFees" -> p.money(p.playerFees(pl)),
      "positionsPaid" -> p.money(p.playerPaid(pl)), "netResult" -> p.money(p.net(pl)))
  }

  When("compliance reads the traders report for it in {string}") { (currency: String) =>
    report = currency
    kit.eventually("the traders", 60.seconds)(Option.when(traders(currency) == wanted(email))(()))
  }

  Then("it holds one row per player who traded, with contracts traded, notional volume, taker fees paid, positions paid and net result") { () =>
    assertEquals(traders(report), wanted(email))
  }

  Then("each row names the player by email, read as erased for an erased player with every figure unchanged") { () =>
    kit.testKit.erase(DataSubject(s"player/${p.player(2)}")): Unit
    assertEquals(traders(report), wanted(n => if n == 2 then "[erased]" else email(n)))
  }

  // ── the day-end ─────────────────────────────────────────────────────────────────────────────────

  Given("a reporting day that has closed with trades and a resolution") { () =>
    p = Marketed(kit, kit.days(), "EUR", rate)
    val (a, b) = (p.market(1), p.market(2))
    p.open(a, p.at("09:00"))
    p.split(a, p.player(1), p.player(2), 10, 60, p.at("10:00"))
    p.direct(a, p.player(3), p.player(1), 4, 70, p.at("11:00"))
    p.direct(a, p.player(2), p.player(3), 2, 55, p.at("12:00"), credit = c => Some(c + 1))
    p.resolved(a, "yes", p.at("15:00"))
    p.finalised(a, p.at("16:00"))
    p.positionPaid(a, p.player(1), "yes", 6, 6_00, p.at("16:00"))
    p.positionPaid(a, p.player(3), "yes", 2, 2_00, p.at("16:00"))
    p.open(b, p.at("09:00"))
    p.direct(b, p.player(1), p.player(2), 5, 40, p.at("13:00"), credit = _ => None)
    p.voided(b, p.at("17:00"))
    p.positionPaid(b, p.player(1), "yes", 5, 1_99, p.at("17:30"))
    p.remainder(b, 1, p.at("17:30"))
    p.publish().settled()
    kit.moveClock(6.minutes)
    DayEnded.closed(kit, p.day): Unit
  }

  When("finance reads the day-end") { () =>
    answer = DayEnded.read(kit, p.day).json.path("currencies").path("EUR").path("predictions")
  }

  Then("it holds, per currency and market, the day's taker fees, resolution payments and remainders kept") { () =>
    val markets = answer.path("markets").elements.asScala.map(fields).map(r => r("marketId") -> r).toMap
    for m <- Vector(p.market(1), p.market(2)) do
      assertEquals(Vector("takerFees", "resolutionPayments", "remainderKept", "trades").map(markets(m)),
        Vector(p.money(p.fees(Some(m))), p.money(p.payments(Some(m))), p.money(p.remainders(Some(m))), p.trades.count(_.marketId == m).toString),
        m)
  }

  Then("the fee account's credits beside the fees, listing any trade whose fee and credit differ or appear in one only") { () =>
    val markets = answer.path("markets").elements.asScala.map(fields).map(r => r("marketId") -> r).toMap
    assertEquals(markets(p.market(1))("feeCredits"), p.money(p.fees(Some(p.market(1))) + 1))
    assertEquals(markets(p.market(2))("feeCredits"), p.money(0))
    assertEquals((answer.path("takerFees").asText, answer.path("feeCredits").asText), (p.money(p.fees()), p.money(p.feeMoves.map(_._2).sum)))
    val differ = answer.path("feeDifferences").elements.asScala.map(fields).toVector
    assertEquals(differ.map(r => (r("tradeId"), r("status"))), Vector((s"${p.market(1)}-t3", "differs"), (s"${p.market(2)}-t4", "fee_only")))
    val t3 = differ.head
    assertEquals((t3("takerFee"), t3("feeCredit")), (p.money(p.fee(2, 55)), p.money(p.fee(2, 55) + 1)))
  }

  // ── no casino figure ────────────────────────────────────────────────────────────────────────────

  private val Dashboard = Vector("/dashboard/kpis?currency=EUR", "/dashboard/trend?range=7d&currency=EUR",
    "/dashboard/kpi-history?currency=EUR", "/dashboard/geo", "/dashboard/rankings?range=7d&currency=EUR", "/dashboard/activity",
    "/dashboard/bonus-summary?currency=EUR", "/dashboard/payment-health?currency=EUR")

  private def revenue(day: LocalDate) = kit.get(s"/reports/ggr?from=$day&to=$day&currency=EUR", kit.asFinance).text
  private def screens(day: LocalDate): Vector[String] = revenue(day) +: Dashboard.map(kit.get(_, kit.asOperator).text)

  Given("the revenue report and the dashboard for today") { () =>
    val today = kit.freshToday()
    from = today; to = today
    before = screens(today)
  }

  When("a market trades and resolves today") { () =>
    p = Marketed(kit, from, "EUR", rate)
    val a = p.market(1)
    p.open(a, p.at("08:00"))
    p.split(a, p.player(1), p.player(2), 10, 60, p.at("08:30"))
    p.direct(a, p.player(3), p.player(1), 4, 70, p.at("08:45"))
    p.resolved(a, "yes", p.at("09:00"))
    p.finalised(a, p.at("09:15"))
    p.positionPaid(a, p.player(1), "yes", 6, 6_00, p.at("09:15"))
    p.positionPaid(a, p.player(3), "yes", 4, 4_00, p.at("09:15"))
    p.publish().settled()
    kit.eventually("today's summary", 60.seconds)(Option.when(
      kit.get(s"/predictions/summary?${period("EUR")}", kit.asFinance).json.path("resolutionPayments").asText == p.money(p.payments()))(()))
  }

  Then("every figure of the revenue report is unchanged") { () =>
    assertEquals(revenue(from), before.head)
  }

  Then("every figure of the dashboard but its predictions summary is unchanged") { () =>
    // The casino's routes byte for byte; the predictions summary, its own route, holds the market's trading.
    assertEquals(Dashboard.map(kit.get(_, kit.asOperator).text), before.tail)
    val shown = kit.eventually("the dashboard's predictions", 60.seconds)(
      Some(kit.get("/dashboard/predictions?currency=EUR", kit.asOperator).json).filter(_.path("trades").path("value").asLong == p.trades.size))
    assertEquals((shown.path("contractsTraded").path("value").asLong, shown.path("takerFees").path("value").asText),
      (p.contracts(), p.money(p.fees())))
  }

  // ── the dashboard's predictions summary ─────────────────────────────────────────────────────────

  private var hour = 0

  Given("trades in {string} yesterday before and after the current hour, and today") { (currency: String) =>
    val today = kit.freshToday()
    hour = kit.now.atZone(kit.zone).getHour
    from = today; to = today
    p = Marketed(kit, today.minusDays(1), currency, rate)
    val (a, b) = (p.market(1), p.market(2))
    p.open(a, p.at("07:00"))
    p.split(a, p.player(1), p.player(2), 10, 60, p.at(f"${hour - 2}%02d:00"))
    p.direct(a, p.player(3), p.player(1), 4, 70, p.at(f"${hour + 3}%02d:00"))
    p.open(b, p.at(today, "07:00"))
    p.direct(b, p.player(1), p.player(2), 5, 40, p.at(today, f"${hour - 1}%02d:00"))
    p.direct(b, p.player(3), p.player(4), 3, 50, p.at(today, f"${hour - 1}%02d:30"))
    p.publish().settled(today.minusDays(1)).settled(today)
  }

  When("an operator reads the dashboard's predictions summary in {string}") { (currency: String) =>
    val wanted = p.dashboardWanted(from, hour)
    answer = kit.eventually("the dashboard's predictions", 60.seconds)(
      Some(kit.get(s"/dashboard/predictions?currency=$currency", kit.asOperator).json).filter(j => Marketed.dashboardOf(j) == wanted))
  }

  Then("it holds today's contracts traded, notional volume, trades, distinct traders and taker fees") { () =>
    val got   = Marketed.dashboardOf(answer)
    val today = p.dashboard(p.tradesOf(from))
    assertEquals(Marketed.DashboardFields.map(got(_)._1),
      Marketed.DashboardFields.map(k => if Marketed.DashboardMoney(k) then p.money(today(k)) else today(k).toString))
    assertEquals((today("trades"), today("traders")), (2L, 4L))
    assertEquals(answer.path("currency").asText, p.currency)
  }

  Then("each against yesterday's to the same hour, with its trend") { () =>
    assertEquals(Marketed.dashboardOf(answer), p.dashboardWanted(from, hour))
    assertEquals(Marketed.dashboardOf(answer)("trades"), ("2", "1", "up"), "yesterday's trade after the hour is left out")
  }

  // ── a brand without the module ──────────────────────────────────────────────────────────────────

  Given("brand {string} whose entitlement does not run the {string} module") { (_: String, module: String) =>
    val s = ReportingRuntime.settings.get()
    running = Some(s)
    ReportingRuntime.settings.set(s.copy(modules = s.modules - module))
  }

  When("an operator of {string} asks for the predictions summary") { (_: String) =>
    last = kit.get("/predictions/summary", kit.asFinance)
  }

  Then("it is refused as not found") { () =>
    assertEquals((last.status, last.error), (404, "not_found"), last.text)
  }

  Then("the dashboard shows no predictions summary") { () =>
    val summary = kit.get("/dashboard/predictions?currency=EUR", kit.asOperator)
    assertEquals((summary.status, summary.error), (404, "not_found"), summary.text)
    for route <- Dashboard do
      val r = kit.get(route, kit.asOperator)
      assertEquals(r.status, 200, route)
      assert(!r.text.toLowerCase.contains("prediction"), s"$route: ${r.text.take(300)}")
  }
