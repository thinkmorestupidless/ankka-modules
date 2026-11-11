package io.eitheror.casino.reporting.perf

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.reporting.it.{FactSet, Reply, SharedKit}

import java.time.LocalDate
import scala.concurrent.duration.*

/**
 * The brand at its size (T069; SC-001, SC-002), on purpose only: `just reporting-perf` (`REPORTING_PERF=1`). 10,000
 * players registered before a month of 30 days ending today, each depositing on one day of it and playing
 * `REPORTING_PERF_ROUNDS` rounds (100 by default: a million bets a month, review should fix 4) spread over the month's
 * days and hours, half of them won; a tenth of them also play today, and a withdrawal every fiftieth. Then the month's
 * revenue report under two seconds with its totals the facts' to the minor unit, every dashboard route under one second
 * with today's money the facts', and the brand-wide ledger's pages timed, with `EXPLAIN ANALYZE` of the view queries
 * behind them printed. The fold's rate (legs a second, publish to the month folded) is printed too. The ledger of a
 * player with 5,000 transactions (SC-003) is `LedgerSuite`'s. Each read is timed five times after one to warm it, and
 * the slowest is held to the limit.
 */
class PerfSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 240.minutes
  override def munitIgnore: Boolean = !sys.env.get("REPORTING_PERF").contains("1")

  private lazy val kit = SharedKit.kit

  private val Players = sys.env.get("REPORTING_PERF_PLAYERS").flatMap(_.toIntOption).getOrElse(10_000)
  private val Rounds  = sys.env.get("REPORTING_PERF_ROUNDS").flatMap(_.toIntOption).getOrElse(100)
  private val Days    = 30
  private val Reads   = 5

  private var today: LocalDate = null
  private def first: LocalDate = today.minusDays(Days - 1L)
  private var facts: FactSet   = null
  private var publishedAt      = 0L

  private def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  override def beforeAll(): Unit =
    if !munitIgnore then
      today = kit.freshToday()
      val s = FactSet(kit.zone)
      for i <- 0 until Players do
        val p   = f"perf-p$i%05d"
        val day = first.plusDays((i % Days).toLong)
        s.register(p, s.at(first.minusDays(1), "09:00"), country = Vector("ZA", "GB", "DE")(i % 3),
          email = Option.when(i == Players / 2)("perf-one@example.com"))
        s.deposit(p, "EUR", 20_00 + i % 100, s.at(day, "08:00"))
        for j <- 0 until Rounds do
          val on   = first.plusDays(((i + j) % Days).toLong)
          val time = f"${8 + (i + j * 7) % 15}%02d:${(i * 7 + j) % 60}%02d"
          s.bet(p, "EUR", s"$p-r1-$j", 5_00 + (i + j) % 50, 5_00 + (i + j) % 50, s.at(on, time))
          if (i + j) % 2 == 0 then s.win(p, "EUR", s"$p-r1-$j", 3_00 + (i + j) % 70, 3_00 + (i + j) % 70, s.at(on, time))
        if i % 10 == 0 then
          s.deposit(p, "EUR", 10_00, s.at(today, "07:00"))
          s.bet(p, "EUR", s"$p-r2", 2_00 + i % 30, 2_00 + i % 30, s.at(today, "07:30"))
        if i % 50 == 0 then s.withdrawal(p, "EUR", 15_00, s.at(day, "10:00"), s.at(day, "11:00"))
      val started = System.nanoTime()
      publishedAt = started
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
    kit.eventually("the month folded", 180.minutes)(Some(kit.get(route, kit.asFinance))
      .filter(r => r.status == 200 && summary(r).path("totalBets").asText == money(want.bets) &&
        summary(r).path("totalDeposits").asText == money(want.deposits)))
    val folded = (System.nanoTime() - publishedAt) / 1e9
    println(f"perf: the month folded ${facts.allLegs.size}%,d legs in $folded%.0f s from the first publish: ${facts.allLegs.size / folded}%,.0f legs a second")
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

  /**
   * The brand-wide ledger (review should fix 4): its first, a middle and its last page timed, one player's ledger, and
   * `EXPLAIN (ANALYZE, BUFFERS)` of the view queries behind the ledger and the revenue report, printed. Measured and
   * reported; SC-003 holds the ledger to listing every row, not to a time.
   */
  test("the brand-wide ledger's pages are timed and the view queries explained") {
    val all = s"/reports/transactions?from=$first&to=$today"
    val total = kit.get(all, kit.asFinance).json.path("totalPages").asInt
    for page <- Vector(0, total / 2, (total - 1).max(0)) do
      val (ms, r) = timed(kit.get(s"$all&page=$page", kit.asFinance))
      assertEquals(r.status, 200, r.text.take(300))
      println(f"perf: GET /reports/transactions brand-wide, page $page of $total%,d: slowest of $Reads reads $ms ms")
    val (ms, r) = timed(kit.get(s"$all&playerEmail=perf-one@example.com", kit.asFinance))
    println(s"perf: GET /reports/transactions for one player: slowest of $Reads reads $ms ms (${r.json.path("totalCount").asLong} rows)")
    val g = kit.settings.generation
    val from = facts.at(first, "00:00").toString
    val to   = facts.at(today.plusDays(1), "00:00").toString
    val where = s"payload::jsonb->>'generation' = '$g' AND (payload::jsonb->>'at')::timestamptz >= '$from'::timestamptz AND " +
      s"(payload::jsonb->>'at')::timestamptz < '$to'::timestamptz AND ('all' = 'all') AND ('*' = '*')"
    val order = "ORDER BY (payload::jsonb->>'at')::timestamptz DESC, row_key DESC"
    explain("Transactions.between, page 0", s"SELECT payload FROM ankka_view_transactions WHERE $where $order OFFSET 0 LIMIT 50")
    explain("Transactions.between, the middle page",
      s"SELECT payload FROM ankka_view_transactions WHERE $where $order OFFSET ${(total / 2) * 50} LIMIT 50")
    explain("Transactions.count", s"SELECT count(*) FROM ankka_view_transactions WHERE $where")
    explain("DaysView.between", s"SELECT payload FROM ankka_view_days WHERE payload::jsonb->>'generation' = '$g' AND " +
      s"payload::jsonb->>'day' >= '$first' AND payload::jsonb->>'day' <= '$today' ORDER BY payload::jsonb->>'day' LIMIT 1000")
  }

  private def explain(what: String, query: String): Unit =
    val c = java.sql.DriverManager.getConnection(kit.testKit.jdbcUrl, "ankka", "ankka")
    try
      val rs = c.createStatement().executeQuery(s"EXPLAIN (ANALYZE, BUFFERS) $query")
      val plan = Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toVector
      println(s"perf: EXPLAIN $what\n" + plan.map("perf:   " + _).mkString("\n"))
    finally c.close()
