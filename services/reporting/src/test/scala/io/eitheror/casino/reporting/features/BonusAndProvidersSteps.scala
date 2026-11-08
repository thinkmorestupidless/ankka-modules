package io.eitheror.casino.reporting.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.reporting.it.{FactSet, Reply}

import java.time.{Duration, LocalDate}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/reporting/bonus-and-providers.feature (US4; FR-009, FR-011, FR-012), bound by T047 over a `FactSet`
 * published once for the feature on days of its own: grants under two offers on its first day, each outcome among
 * them, a free-rounds win; deposits through two payment providers, one below 95% success, and withdrawals through two
 * gateways; its third day has nothing. Every figure is held to the set's, computed from the facts alone.
 */
class BonusAndProvidersFeatures extends ReportingFeatures("bonus-and-providers.feature"):
  private lazy val published: (FactSet, LocalDate) =
    val day = kit.days()
    val s   = FactSet(kit.zone)
    val ps  = (1 to 6).toVector.map(i => s.register(s"bp-p$i", s.at(day, "08:00")))
    val t   = s.at(day, "09:00")
    val welcome = Vector(10_00L, 20_00L, 30_00L, 15_00L, 25_00L).zipWithIndex.map((a, i) =>
      s.grant(ps(i), "EUR", a, t.plusSeconds(i * 60L), offer = "bp-welcome", name = "Welcome Match", offerType = "deposit_match"))
    s.outcome(welcome(0), "converted", t.plusSeconds(3600))
    s.outcome(welcome(1), "converted", t.plusSeconds(3700))
    s.outcome(welcome(2), "forfeited", t.plusSeconds(3800))
    s.outcome(welcome(3), "cancelled", t.plusSeconds(3900))
    val reload = Vector(5_00L, 7_50L, 12_00L).zipWithIndex.map((a, i) =>
      s.grant(ps(i + 3), "EUR", a, t.plusSeconds(600 + i * 60L), offer = "bp-reload", name = "Weekend Reload", offerType = "reload"))
    s.outcome(reload(0), "converted", t.plusSeconds(4000))
    s.outcome(reload(1), "expired", t.plusSeconds(4100))
    // Payments: bp-good converts 19 of 20, bp-weak 3 of 4; withdrawals through bank and crypto.
    for i <- 0 until 20 do
      s.payment(ps(i % 6), "EUR", "bp-good", 10_00 + i, Some(t.plusSeconds(i * 30L)), if i == 0 then "failed" else "captured",
        Some(t.plusSeconds(i * 30L + 5)))
    for i <- 0 until 4 do
      s.payment(ps(i), "EUR", "bp-weak", 50_00, Some(t.plusSeconds(1000 + i * 30L)), if i == 0 then "failed" else "captured",
        Some(t.plusSeconds(1000 + i * 30L + 5)))
    s.withdrawal(ps(0), "EUR", 40_00, s.at(day, "10:00"), s.at(day, "12:30"), gateway = "bp-good")
    s.withdrawal(ps(1), "EUR", 30_00, s.at(day, "10:00"), s.at(day, "11:00"), gateway = "bank")
    s.withdrawalOf(ps(2), "EUR", 20_00, s.at(day, "15:00"), None, gateway = "bank")
    s.publish(kit)
    (s, day)

  private def s: FactSet     = published._1
  private def d1: LocalDate = published._2

  private var json: JsonNode = null
  private var csv: Reply     = null
  private var offer: String  = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    json = null; csv = null; offer = null

  private def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString
  private def fields(n: JsonNode): Map[String, String] = n.fieldNames.asScala.map(k => k -> n.get(k).asText).toMap

  /** The route's answer once `want` holds of it, which it does after the facts fold. */
  private def settled(path: String)(want: JsonNode => Boolean): JsonNode =
    val deadline = 60.seconds.fromNow
    var r        = kit.get(path, kit.asFinance)
    while !(r.status == 200 && want(r.json)) && deadline.hasTimeLeft() do
      Thread.sleep(250)
      r = kit.get(path, kit.asFinance)
    assertEquals(r.status, 200, r.text)
    r.json

  // ── the bonus report ──────────────────────────────────────────────────────────────────────────

  /** The original's rules written again: converted whether its cap is pending or closed; the rate truncated. */
  private def counts(gs: Vector[FactSet.G]): Map[String, String] =
    val converted = gs.filter(_.status == "converted")
    Map("grantsCount" -> gs.size.toString, "totalGrantedValue" -> money(gs.map(_.amount).sum),
      "convertedCount" -> converted.size.toString, "convertedValue" -> money(converted.map(_.amount).sum),
      "forfeitedCount" -> gs.count(_.status == "forfeited").toString, "cancelledCount" -> gs.count(_.status == "cancelled").toString,
      "conversionRatePct" -> (if gs.isEmpty then 0 else converted.size * 100 / gs.size).toString, "currency" -> "EUR")

  private def offersWanted(only: Option[String]): Vector[Map[String, String]] =
    s.grants.filter(g => only.forall(_ == g.offer)).groupBy(_.offer).toVector.sortBy(_._1).map { (id, gs) =>
      counts(gs) ++ Map("offerId" -> id, "offerName" -> gs.head.name, "offerType" -> gs.head.offerType)
    }

  private def summaryWanted(only: Option[String]): Map[String, String] =
    val gs = s.grants.filter(g => only.forall(_ == g.offer))
    val c  = counts(gs)
    Map("totalGrantsCount" -> c("grantsCount"), "totalGrantedValue" -> c("totalGrantedValue"), "convertedCount" -> c("convertedCount"),
      "conversionRatePct" -> c("conversionRatePct"), "forfeitedCount" -> c("forfeitedCount"), "cancelledCount" -> c("cancelledCount"),
      "activeCount" -> gs.count(_.status == "active").toString, "currency" -> "EUR")

  private def bonusShown(j: JsonNode) = (fields(j.path("summary")), j.path("offers").elements.asScala.toVector.map(fields))

  private def bonus(only: Option[String]): JsonNode =
    val q = only.fold("")(o => s"&offerId=$o")
    settled(s"/reports/bonus?from=$d1&to=$d1&currency=EUR$q")(j => bonusShown(j) == (summaryWanted(only), offersWanted(only)))

  Given("brand {string}") { (slug: String) =>
    assertEquals(kit.brand, slug)
    s: Unit
  }

  Given("rewards under two offers in the period") { () =>
    assertEquals(s.grants.map(_.offer).distinct.sorted, Vector("bp-reload", "bp-welcome"))
  }

  When("marketing runs the bonus report for it") { () =>
    json = bonus(None)
  }

  Then("the summary shows total rewards, granted value, converted count, conversion rate, and the forfeited, cancelled and active counts") {
    () =>
      assertEquals(fields(json.path("summary")), summaryWanted(None))
      // 3 of 8 converted: 37.5%, truncated to 37 as the original.
      assertEquals(json.path("summary").path("conversionRatePct").asInt, 37)
  }

  Then("one row per offer shows its name, type and the same figures") { () =>
    val rows = json.path("offers").elements.asScala.toVector.map(fields)
    assertEquals(rows, offersWanted(None))
    assertEquals(rows.map(r => (r("offerName"), r("offerType"))), Vector(("Weekend Reload", "reload"), ("Welcome Match", "deposit_match")))
  }

  Given("the bonus report") { () =>
    bonus(None): Unit
  }

  When("marketing filters it by one offer") { () =>
    offer = "bp-welcome"
    json = bonus(Some(offer))
    csv = kit.get(s"/reports/bonus?from=$d1&to=$d1&currency=EUR&offerId=$offer&format=csv", kit.asFinance)
  }

  Then("only that offer's row and figures appear") { () =>
    assertEquals(bonusShown(json), (summaryWanted(Some(offer)), offersWanted(Some(offer))))
    assertEquals(json.path("offers").elements.asScala.map(_.path("offerId").asText).toVector, Vector(offer))
  }

  Then("the export holds the per-offer table") { () =>
    assertEquals(csv.status, 200, csv.text)
    assert(csv.header("Content-Disposition").exists(_.matches("attachment; filename=\"bonus-report-[0-9a-f-]{36}\\.csv\"")))
    val lines  = csv.text.split("\r\n").toVector
    val header = lines.head.split(',').toVector
    val rows   = lines.tail.map(l => header.zip(l.split(",", -1)).toMap)
    // The summary first under `all`, then the offer's row.
    assertEquals(rows.map(_("offerId")), Vector("all", offer))
    assertEquals(rows.tail.map(_ - "activeCount"), offersWanted(Some(offer)))
    assertEquals(rows.tail.map(_("activeCount")), Vector(""))
    assertEquals(rows.head("activeCount"), summaryWanted(Some(offer))("activeCount"))
    assertEquals(rows.head("grantsCount"), summaryWanted(Some(offer))("totalGrantsCount"))
  }

  // ── the provider report ───────────────────────────────────────────────────────────────────────

  private def rate(done: Long, attempts: Long): String =
    if attempts == 0 then "null" else (BigDecimal(done) * 100 / attempts).setScale(1, BigDecimal.RoundingMode.HALF_UP).toString

  private def hours(seconds: Long, n: Long): String =
    if n == 0 then "null" else (BigDecimal(seconds) / 3600 / n).setScale(1, BigDecimal.RoundingMode.HALF_UP).toString

  private def inDay(day: LocalDate)(t: java.time.Instant) = t.atZone(kit.zone).toLocalDate == day

  /** Per name: (attempts, completed, value, requests, completed withdrawals, seconds to them). */
  private def providers(day: LocalDate): Map[String, (Long, Long, Long, Long, Long, Long)] =
    val names = (s.payments.map(_.provider) ++ s.withdrawals.map(_.gateway)).distinct
    names.map { n =>
      val p    = s.payments.filter(_.provider == n)
      val done = p.filter(x => x.outcome == "captured" && x.ended.exists(inDay(day)))
      val w    = s.withdrawals.filter(_.gateway == n)
      val wd   = w.filter(_.completed.exists(inDay(day)))
      n -> (p.count(_.initiated.exists(inDay(day))).toLong, done.size.toLong, done.map(_.amount).sum, w.count(x => inDay(day)(x.requested)).toLong,
        wd.size.toLong, wd.map(x => Duration.between(x.requested, x.completed.get).getSeconds).sum)
    }.filter(_._2 != (0L, 0L, 0L, 0L, 0L, 0L)).toMap

  private def pspWanted(day: LocalDate): (Map[String, String], Vector[Map[String, String]]) =
    val ps = providers(day)
    val t  = ps.values.foldLeft((0L, 0L, 0L, 0L, 0L, 0L))((a, v) => (a._1 + v._1, a._2 + v._2, a._3 + v._3, a._4 + v._4, a._5 + v._5, a._6 + v._6))
    (Map("depositAttempts" -> t._1.toString, "completedDeposits" -> t._2.toString, "depositValue" -> money(t._3),
      "depositSuccessRatePct" -> rate(t._2, t._1), "withdrawalRequests" -> t._4.toString, "completedWithdrawals" -> t._5.toString,
      "avgWithdrawalHours" -> hours(t._6, t._5), "currency" -> "EUR"),
      ps.toVector.sortBy(_._1).map { (n, v) =>
        Map("pspName" -> n, "depositAttempts" -> v._1.toString, "completedDeposits" -> v._2.toString,
          "depositSuccessRatePct" -> rate(v._2, v._1), "depositValue" -> money(v._3), "withdrawalCount" -> v._5.toString,
          "withdrawalRequests" -> v._4.toString, "avgWithdrawalHours" -> hours(v._6, v._5),
          "belowThreshold" -> (v._1 > 0 && BigDecimal(v._2) * 100 / v._1 < 95).toString)
      })

  private def pspShown(j: JsonNode) = (fields(j.path("summary")), j.path("pspBreakdown").elements.asScala.toVector.map(fields))

  private var pspDay: LocalDate = null

  Given("deposit attempts and outcomes for two payment providers in the period") { () =>
    pspDay = d1
    assertEquals(s.payments.map(_.provider).distinct.sorted, Vector("bp-good", "bp-weak"))
  }

  Given("no deposit attempts in the period") { () =>
    pspDay = d1.plusDays(2)
    assertEquals(providers(pspDay), Map.empty)
  }

  When("operations runs the provider report for it") { () =>
    json = settled(s"/reports/psp?from=$pspDay&to=$pspDay&currency=EUR")(j => pspShown(j) == pspWanted(pspDay))
  }

  Then("the summary shows attempts, completed deposits, deposit value and the success rate") { () =>
    val (summary, _) = pspWanted(pspDay)
    val shown        = fields(json.path("summary"))
    for k <- Vector("depositAttempts", "completedDeposits", "depositValue", "depositSuccessRatePct") do assertEquals(shown(k), summary(k), k)
    assertEquals(shown("depositSuccessRatePct"), "91.7", "22 of 24")
  }

  Then("the summary shows withdrawal requests, completed withdrawals and the average hours to completion") { () =>
    val (summary, _) = pspWanted(pspDay)
    val shown        = fields(json.path("summary"))
    for k <- Vector("withdrawalRequests", "completedWithdrawals", "avgWithdrawalHours") do assertEquals(shown(k), summary(k), k)
    assertEquals((shown("withdrawalRequests"), shown("completedWithdrawals"), shown("avgWithdrawalHours")), ("3", "2", "1.8"))
  }

  Then("one row per payment provider shows the same") { () =>
    assertEquals(pspShown(json), pspWanted(pspDay))
    assertEquals(pspShown(json)._2.map(_("pspName")), Vector("bank", "bp-good", "bp-weak"))
  }

  Then("a provider below {string} percent success is marked") { (pct: String) =>
    assertEquals(pct, "95")
    val marked = pspShown(json)._2.map(r => r("pspName") -> r("belowThreshold")).toMap
    assertEquals(marked, Map("bp-good" -> "false", "bp-weak" -> "true", "bank" -> "false"))
  }

  Then("every rate reads as no rate, not {string}") { (constant: String) =>
    assertEquals(constant, "97")
    val summary = json.path("summary")
    assert(summary.path("depositSuccessRatePct").isNull && summary.path("avgWithdrawalHours").isNull, summary.toString)
    assertEquals(summary.path("depositAttempts").asLong, 0L)
    assertEquals(json.path("pspBreakdown").size, 0)
  }
