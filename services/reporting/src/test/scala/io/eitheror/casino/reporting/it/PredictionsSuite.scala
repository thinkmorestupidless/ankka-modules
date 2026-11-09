package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.reporting.ReportingRuntime

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import Marketed.fields

/**
 * The prediction markets' reports against the whole service (US8; FR-028–FR-032, SC-009): two markets of a block of
 * days, one resolved and paid, one voided with a remainder, beside a casino day; the summary, the market report, the
 * traders and the day-end's fee reconciliation from those facts alone; the casino's figures untouched by them; and
 * no route for a brand without the module.
 */
class PredictionsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 300.seconds
  private lazy val kit = SharedKit.kit

  private var p: Marketed = null
  private var casino: FactSet = null
  private def a = p.market(1)
  private def b = p.market(2)

  override def beforeAll(): Unit =
    p = Marketed(kit, kit.days())
    val d = p.day
    val (p1, p2, p3) = (p.player(1), p.player(2), p.player(3))
    // A casino day beside the markets, so what the markets do is seen to change none of its figures.
    casino = FactSet(kit.zone)
    casino.register(p1, casino.at(d.minusDays(1), "09:00"))
    casino.deposit(p1, "EUR", 50_00, casino.at(d, "08:00"))
    casino.bet(p1, "EUR", s"mk-${p.tag}-r1", 10_00, 10_00, casino.at(d, "08:30"))
    casino.publish(kit)
    p.open(a, p.at("09:00"))
    p.open(b, p.at("09:00"), category = "politics")
    p.split(a, p1, p2, 10, 60, p.at("10:00"))
    p.direct(a, p3, p1, 4, 70, p.at("11:00"))
    // The fee account credits one cent short of this trade's fee; the next has no movement at all.
    p.direct(a, p2, p3, 2, 55, p.at("12:00"), credit = c => Some(c - 1))
    p.direct(b, p1, p2, 5, 40, p.at("13:00"), credit = _ => None)
    p.feeMove(p3, s"$b-unknown", 7, p.at("13:30"))
    p.closed(a, p.at("15:00"))
    p.resolved(a, "yes", p.at("16:00"))
    p.finalised(a, p.at("17:00"))
    p.positionPaid(a, p1, "yes", 6, 6_00, p.at("17:00"))
    p.positionPaid(a, p3, "yes", 2, 2_00, p.at("17:00"))
    p.voided(b, p.at("18:00"))
    p.positionPaid(b, p1, "yes", 5, 1_99, p.at("18:30"))
    p.remainder(b, 1, p.at("18:30"))
    p.publish().settled()

  private def period = s"from=${p.day}&to=${p.day}&currency=EUR"

  test("the summary folds the period's trades, fees, payments and remainders, and counts the markets' moves (FR-028)") {
    val j = kit.eventually("the summary", 60.seconds)(Some(p.get(s"/predictions/summary?$period").json)
      .filter(_.path("trades").asLong == 4))
    assertEquals(fields(j), Map(
      "contractsTraded" -> p.contracts().toString, "notionalVolume" -> p.money(p.notional()), "trades" -> "4", "traders" -> "3",
      "takerFees" -> p.money(p.fees()), "resolutionPayments" -> p.money(p.payments()), "remaindersKept" -> p.money(p.remainders()),
      "marketsOpened" -> "2", "marketsResolved" -> "1", "marketsVoided" -> "1", "marketsCorrected" -> "0"))
    assertEquals(p.notional(), 10L * 100 + 4 * 70 + 2 * 55 + 5 * 40, "a split's notional is both outcomes' prices")
  }

  test("the market report holds each market's status, times, resolution and figures, open interest from fills and payments (FR-029)") {
    val j = kit.eventually("the markets", 60.seconds)(Some(p.get(s"/predictions/markets?from=${p.day}&to=${p.day}").json)
      .filter(j => p.marketRow(j, a).exists(_.path("trades").asLong == 3)))
    val ra = fields(p.marketRow(j, a).get)
    assertEquals((ra("status"), ra("resolution"), ra("firstResolution"), ra("corrected")), ("final", "yes", "yes", ""))
    assertEquals((ra("openedAt"), ra("closedAt"), ra("resolvedAt")), (p.at("09:00").toString, p.at("15:00").toString, p.at("16:00").toString))
    assertEquals((ra("contractsTraded"), ra("notionalVolume"), ra("traders"), ra("openInterest"), ra("takerFees"), ra("resolutionPayments")),
      (p.contracts(Some(a)).toString, p.money(p.notional(Some(a))), p.traders(Some(a)).toString, p.openInterest(a).toString,
        p.money(p.fees(Some(a))), p.money(p.payments(Some(a)))))
    val rb = fields(p.marketRow(j, b).get)
    assertEquals((rb("status"), rb("resolution"), rb("resolutionPayments"), rb("remainderKept")), ("voided", "void", "1.99", "0.01"))
    // Filtered by status and category.
    val politics = p.get(s"/predictions/markets?from=${p.day}&to=${p.day}&category=politics").json
    assertEquals((p.marketRow(politics, a), p.marketRow(politics, b).isDefined), (None, true))
    val finals = p.get(s"/predictions/markets?from=${p.day}&to=${p.day}&status=final").json
    assertEquals((p.marketRow(finals, a).isDefined, p.marketRow(finals, b)), (true, None))
  }

  test("the traders report holds each player's contracts, notional, fees, positions paid and net result (FR-030)") {
    val j = kit.eventually("the traders", 60.seconds)(Some(p.get(s"/predictions/traders?$period").json)
      .filter(_.path("traders").elements.asScala.count(_.path("playerId").asText.startsWith(s"mk-${p.tag}")) == 3))
    val rows = j.path("traders").elements.asScala.map(fields).filter(_("playerId").startsWith(s"mk-${p.tag}")).toVector
    val want = (1 to 3).map(p.player).map(pl => Map("playerId" -> pl, "email" -> "",
      "contractsTraded" -> p.playerContracts(pl).toString, "notionalVolume" -> p.money(p.playerNotional(pl)),
      "takerFees" -> p.money(p.playerFees(pl)), "positionsPaid" -> p.money(p.playerPaid(pl)), "netResult" -> p.money(p.net(pl)))).toVector
    assertEquals(rows, want)
    val csv = p.get(s"/predictions/traders?$period&format=csv")
    assertEquals(csv.status, 200, csv.text)
    assert(csv.header("Content-Disposition").exists(_.contains("predictions-traders-report-")), csv.headers.toString)
    assertEquals(csv.text.split("\r\n").head, "playerId,email,contractsTraded,notionalVolume,takerFees,positionsPaid,netResult")
  }

  test("the day-end holds each market's fees, payments and remainder and the fee account's credits, listing every trade they differ on (FR-031)") {
    kit.moveClock(6.minutes)
    val pr = DayEnded.closed(kit, p.day).path("currencies").path("EUR").path("predictions")
    val markets = pr.path("markets").elements.asScala.map(fields).map(r => r("marketId") -> r).toMap
    assertEquals((markets(a)("takerFees"), markets(a)("resolutionPayments"), markets(a)("feeCredits")),
      (p.money(p.fees(Some(a))), p.money(p.payments(Some(a))), p.money(p.fees(Some(a)) - 1)))
    assertEquals((markets(b)("takerFees"), markets(b)("remainderKept"), markets(b)("feeCredits")),
      (p.money(p.fees(Some(b))), "0.01", "0.00"))
    val differ = pr.path("feeDifferences").elements.asScala.map(fields).toVector.map(r => r("tradeId") -> r("status")).toMap
    assertEquals(differ, Map(s"$a-t3" -> "differs", s"$b-t4" -> "fee_only", s"$b-unknown" -> "credit_only"))
    assertEquals(pr.path("feeCredits").asText, p.money(p.feeMoves.map(_._2).sum))
  }

  test("no casino figure holds a trade, a fee or a payment (SC-009)") {
    val r = kit.get(s"/reports/ggr?$period", kit.asFinance).json.path("summary")
    val want = kit.everything.figures(p.day, "EUR")
    assertEquals((r.path("totalBets").asText, r.path("ggr").asText, r.path("totalDeposits").asText, r.path("totalWithdrawals").asText),
      (p.money(want.bets), p.money(want.ggr), p.money(want.deposits), p.money(want.withdrawals)))
  }

  test("the market report admits the markets permission, the traders report compliance; the summary neither") {
    val desk       = kit.asStaff(Seq("markets"))
    val compliance = kit.asStaff(Seq("brand_compliance"))
    assertEquals(kit.get(s"/predictions/markets?from=${p.day}&to=${p.day}", desk).status, 200)
    assertEquals(kit.get(s"/predictions/summary?$period", desk).status, 403)
    assertEquals(kit.get(s"/predictions/traders?$period", desk).status, 403)
    assertEquals(kit.get(s"/predictions/traders?$period", compliance).status, 200)
    assertEquals(kit.get(s"/predictions/summary?$period", compliance).status, 403)
    assertEquals(kit.get(s"/predictions/summary?$period", kit.asOperator).status, 403)
  }

  test("the dashboard holds today's prediction figures against yesterday's to the same hour, in the KPIs' shape (FR-028)") {
    val today = kit.freshToday()
    val hour  = kit.now.atZone(kit.zone).getHour
    val d     = Marketed(kit, today.minusDays(1))
    val (x, y) = (d.market(1), d.market(2))
    d.open(x, d.at("07:00"))
    // Yesterday: a split before the current hour, a direct trade after it, which yesterday's figures leave out.
    d.split(x, d.player(1), d.player(2), 10, 60, d.at("08:00"))
    d.direct(x, d.player(3), d.player(1), 4, 70, d.at("15:00"))
    d.open(y, d.at(today, "07:00"))
    d.direct(y, d.player(1), d.player(2), 5, 40, d.at(today, "09:00"))
    d.direct(y, d.player(3), d.player(4), 3, 50, d.at(today, "09:30"))
    d.publish().settled(today.minusDays(1)).settled(today)
    val wanted = d.dashboardWanted(today, hour)
    val j = kit.eventually("the dashboard's predictions", 60.seconds)(Some(kit.get("/dashboard/predictions?currency=EUR", kit.asOperator).json)
      .filter(j => Marketed.dashboardOf(j) == wanted))
    assertEquals(wanted("trades"), ("2", "1", "up"))
    assertEquals(wanted("traders"), ("4", "2", "up"), "a player is counted yesterday from the first trade before the hour")
    assertEquals((j.path("currency").asText, j.path("notionalVolume").path("currency").asText), ("EUR", "EUR"))
    assertEquals(j.properties.asScala.map(_.getKey).toSet,
      Set("currency", "currencies", "contractsTraded", "notionalVolume", "trades", "traders", "takerFees"))
  }

  test("a brand that does not run the predictions module is served no prediction report or dashboard figure (FR-032)") {
    val before = ReportingRuntime.settings.get()
    try
      ReportingRuntime.settings.set(before.copy(modules = before.modules - "predictions"))
      for route <- Vector(s"/predictions/summary?$period", s"/predictions/traders?$period", s"/predictions/markets?$period",
          "/dashboard/predictions?currency=EUR") do
        val r = kit.get(route, kit.asFinance)
        assertEquals((r.status, r.error), (404, "not_found"), route)
      for route <- Vector("kpis", "trend", "kpi-history", "geo", "rankings", "activity", "bonus-summary", "payment-health") do
        val r = kit.get(s"/dashboard/$route", kit.asOperator)
        assertEquals(r.status, 200, route)
        assert(!r.text.toLowerCase.contains("prediction"), s"$route: ${r.text.take(300)}")
      assert(!DayEnded.read(kit, p.day).text.contains("predictions"))
    finally ReportingRuntime.settings.set(before)
  }
