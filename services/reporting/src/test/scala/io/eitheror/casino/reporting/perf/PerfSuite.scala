package io.eitheror.casino.reporting.perf

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.reporting.it.{FactSet, Reply, SharedKit}

import java.time.LocalDate
import scala.concurrent.duration.*

/**
 * The brand at its size (T069; SC-001, SC-002), on purpose only: `just reporting-perf` (`REPORTING_PERF=1`). 10,000
 * players registered before a month of 30 days ending today, each depositing and playing a round on one day of it, a
 * tenth of them also today, and a withdrawal every fiftieth; then the month's revenue report under two seconds with its
 * totals the facts' to the minor unit, and every dashboard route under one second with today's money the facts'.
 * The ledger of a player with 5,000 transactions (SC-003) is `LedgerSuite`'s. Each read is timed five times after one
 * to warm it, and the slowest is held to the limit.
 */
class PerfSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 60.minutes
  override def munitIgnore: Boolean = !sys.env.get("REPORTING_PERF").contains("1")

  private lazy val kit = SharedKit.kit

  private val Players = sys.env.get("REPORTING_PERF_PLAYERS").flatMap(_.toIntOption).getOrElse(10_000)
  private val Days    = 30
  private val Reads   = 5

  private var today: LocalDate = null
  private def first: LocalDate = today.minusDays(Days - 1L)
  private var facts: FactSet   = null

  private def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  override def beforeAll(): Unit =
    if !munitIgnore then
      today = kit.freshToday()
      val s = FactSet(kit.zone)
      for i <- 0 until Players do
        val p   = f"perf-p$i%05d"
        val day = first.plusDays((i % Days).toLong)
        s.register(p, s.at(first.minusDays(1), "09:00"), country = Vector("ZA", "GB", "DE")(i % 3))
        s.deposit(p, "EUR", 20_00 + i % 100, s.at(day, "08:00"))
        s.bet(p, "EUR", s"$p-r1", 5_00 + i % 50, 5_00 + i % 50, s.at(day, "08:30"))
        if i % 2 == 0 then s.win(p, "EUR", s"$p-r1", 3_00 + i % 70, 3_00 + i % 70, s.at(day, "08:31"))
        if i % 10 == 0 then
          s.deposit(p, "EUR", 10_00, s.at(today, "07:00"))
          s.bet(p, "EUR", s"$p-r2", 2_00 + i % 30, 2_00 + i % 30, s.at(today, "07:30"))
        if i % 50 == 0 then s.withdrawal(p, "EUR", 15_00, s.at(day, "10:00"), s.at(day, "11:00"))
      val started = System.nanoTime()
      s.publish(kit)
      println(f"perf: published ${s.allLegs.size}%,d money legs and $Players%,d registrations in ${(System.nanoTime() - started) / 1e9}%.1f s")
      facts = s

  /** The slowest of `Reads` reads after one to warm, in milliseconds, and the last answer. */
  private def timed(read: => Reply): (Long, Reply) =
    read: Unit
    val runs = (1 to Reads).map { _ =>
      val t = System.nanoTime()
      val r = read
      ((System.nanoTime() - t) / 1_000_000, r)
    }
    (runs.map(_._1).max, runs.last._2)

  test("the month's revenue report over 10,000 players answers within two seconds, its totals the facts' (SC-001)") {
    val want = facts.total(first, today, "EUR")
    val route = s"/reports/ggr?from=$first&to=$today&currency=EUR"
    def summary(r: Reply) = r.json.path("summary")
    kit.eventually("the month folded", 20.minutes)(Some(kit.get(route, kit.asFinance))
      .filter(r => r.status == 200 && summary(r).path("totalBets").asText == money(want.bets) &&
        summary(r).path("totalDeposits").asText == money(want.deposits)))
    val (ms, r) = timed(kit.get(route, kit.asFinance))
    println(s"perf: GET /reports/ggr, $Days days, $Players players: slowest of $Reads reads $ms ms")
    assertEquals(r.status, 200, r.text)
    val s = summary(r)
    assertEquals(
      Vector("totalBets", "totalWins", "ggr", "depositPoolGgr", "ngr", "totalDeposits", "totalWithdrawals").map(s.path(_).asText),
      Vector(want.bets, want.wins, want.ggr, want.pool, want.ngr, want.deposits, want.withdrawals).map(money))
    assertEquals(r.json.path("dailyBreakdown").size, Days)
    assert(ms < 2_000, s"the revenue report took $ms ms")
  }

  test("every dashboard route answers within one second for 10,000 players, today's money the facts' (SC-002)") {
    val want = facts.figures(today, "EUR")
    val kpis = "/dashboard/kpis?currency=EUR"
    kit.eventually("today folded", 20.minutes)(Some(kit.get(kpis, kit.asOperator).json)
      .filter(j => j.path("ggrToday").path("value").asText == money(want.ggr) &&
        j.path("depositsToday").path("amount").asText == money(want.deposits)))
    val routes = Vector(kpis, "/dashboard/trend?range=30d&currency=EUR", "/dashboard/kpi-history?currency=EUR", "/dashboard/geo",
      "/dashboard/rankings?range=30d&currency=EUR", "/dashboard/activity", "/dashboard/bonus-summary?currency=EUR",
      "/dashboard/payment-health?currency=EUR")
    val times = routes.map { route =>
      val (ms, r) = timed(kit.get(route, kit.asOperator))
      assertEquals(r.status, 200, s"$route: ${r.text.take(300)}")
      println(s"perf: GET $route: slowest of $Reads reads $ms ms")
      route -> ms
    }
    val j = kit.get(kpis, kit.asOperator).json
    assertEquals(
      (j.path("ggrToday").path("value").asText, j.path("ngrToday").path("value").asText, j.path("depositsToday").path("amount").asText,
        j.path("depositsToday").path("count").asLong),
      (money(want.ggr), money(want.ngr), money(want.deposits), want.depositCount))
    val geo = kit.get("/dashboard/geo", kit.asOperator).json.path("countries")
    assert((0 until geo.size).map(geo.get(_).path("playerCount").asLong).sum >= Players, geo.toString)
    val slow = times.filter(_._2 >= 1_000)
    assert(slow.isEmpty, s"over one second: $slow")
  }
