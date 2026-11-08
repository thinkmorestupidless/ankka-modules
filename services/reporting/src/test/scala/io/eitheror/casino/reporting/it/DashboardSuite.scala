package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * T039: the dashboard against the whole service (US2; FR-013–FR-015) over `Dashboards`' facts on a today of the
 * suite's own: every figure the whole brand's, held to every fact set this JVM published, computed from the facts alone.
 */
class DashboardSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 300.seconds
  private lazy val kit = SharedKit.kit
  private var d: Dashboards = null

  override def beforeAll(): Unit =
    d = Dashboards(kit, "dash")
    d.publish()

  test("today's money figures are the sums over every player, yesterday's are to the same hour, each with the original's arrow") {
    val j = d.kpis()
    assertEquals(d.kpisShown(j), d.kpisWanted(exclude = false))
    // Yesterday to 10:59 holds the 09:30 stakes and not the 15:00 ones.
    val y = kit.everything.figures(d.yest, "EUR", upTo = d.hour)
    assertEquals(y.stakes, (1 to 24).map(n => 3_00L + n).sum)
    assertEquals(j.path("ggrToday").fieldNames.asScala.toVector, Vector("value", "yesterday", "trend", "currency"))
    assertEquals(j.path("depositsToday").fieldNames.asScala.toVector, Vector("amount", "count", "yesterdayAmount", "trend", "currency"))
    assertEquals(j.path("currency").asText, "EUR")
  }

  test("players online counts the open player sessions and active games the open game sessions") {
    val j = d.kpis()
    assertEquals(j.path("playersOnline").path("value").asLong, kit.everything.open("player"))
    assertEquals(j.path("activeGames").path("value").asLong, kit.everything.open("game"))
    assert(kit.everything.open("player") >= 5 && kit.everything.open("game") >= 3)
    assertEquals(j.path("activeSessions").path("value").asLong, j.path("activeGames").path("value").asLong)
  }

  test("payment health: today's success rate per provider, null for a provider with no attempt today, and the hours to a withdrawal") {
    val j = d.health
    assertEquals(d.healthShown(j), d.healthWanted)
    val providers = d.healthShown(j).providers.map(p => p._1 -> p._4).toMap
    assertEquals(providers.get("dash-psp-b"), Some(None), "a provider with no attempt today has no rate")
    assertEquals(providers.get("dash-psp-a"), Some(Some(BigDecimal("75.0"))))
    val b = j.path("pspBreakdown").elements.asScala.find(_.path("pspName").asText == "dash-psp-b").get
    assert(b.path("successRatePct").isNull, b.toString)
    assert(b.path("depositVolume").isNumber, b.toString)
  }

  test("the trend over today is by the hour to the current one, each hour's GGR and NGR in the zone") {
    val j = d.trend("today")
    assertEquals(d.points(j), d.trendWanted("today"))
    assertEquals(j.path("range").asText, "today")
  }

  test("the trend over seven and thirty days is by the day, over ninety by the ISO week, and another range is seven days") {
    for range <- Vector("7d", "30d", "90d") do assertEquals(d.points(d.trend(range)), d.trendWanted(range), range)
    assertEquals(kit.get("/dashboard/trend?range=1y", kit.asOperator).json.path("range").asText, "7d")
  }

  test("the KPI history holds the seven days to today, Today last, each day's money and registrations") {
    assertEquals(d.historyShown(d.history), d.historyWanted)
  }

  test("the geography counts every player of the brand by country, most first") {
    assertEquals(d.geoShown(d.geo), d.geoWanted)
    assert(d.geoWanted.map(_._2).sum > 24, "every suite's players, not a sample")
  }

  test("the rankings order games by game sessions started and players by GGR, with country, the email cut at 20 and total wagered") {
    val shown = d.rankingsShown(d.rankings)
    assertEquals(shown, d.rankingsWanted)
    assertEquals(shown._1.find(_._1 == "dash-g-a").map(g => (g._2, g._3, g._4)), Some(("Gates of Dashboard", "pragmatic", 4L)))
    assert(shown._2.nonEmpty && shown._2.forall(_._2.endsWith("…")), shown._2.toString)
  }

  test("today's registrations, awards, self-exclusions and suspensions appear newest first under the original's event ids") {
    val j = d.activity
    assertEquals(d.activityShown(j), d.activityWanted)
    assert(Set("registration", "bonus_grant", "suspension", "self_exclusion").subsetOf(d.activityWanted.map(_._2).toSet))
    val first = j.path("events").get(0)
    assert(first.path("amountMinorUnits").isNull && first.path("currency").isNull, first.toString)
  }

  test("the bonus summary: active records, every open record's balance as the liability, the mean progress and today's cost, under both names") {
    assertEquals(d.bonusShown(d.bonus), d.bonusWanted)
    assert(!kit.everything.openRecords("EUR").exists(_.award == "dash-a3"), "a forfeited record is no liability")
  }

  test("a brand that excludes test accounts has them left out of the dashboard's figures") {
    try
      kit.setReporting(_.copy(excludeTestAccounts = true))
      assertEquals(d.kpisShown(d.kpis(exclude = true)), d.kpisWanted(exclude = true))
      assertNotEquals(d.kpisWanted(exclude = true)("ggrToday"), d.kpisWanted(exclude = false)("ggrToday"))
    finally kit.setReporting(_.copy(excludeTestAccounts = false))
  }

  test("a currency the brand holds no figures in is refused") {
    val r = kit.get("/dashboard/kpis?currency=JPY", kit.asOperator)
    assertEquals((r.status, r.error), (400, "unknown_currency"), r.text)
  }
