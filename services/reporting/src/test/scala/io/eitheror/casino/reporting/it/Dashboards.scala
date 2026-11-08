package io.eitheror.casino.reporting.it

import com.fasterxml.jackson.databind.JsonNode

import java.time.{Instant, LocalDate}
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The dashboard as the facts say it must read (US2; FR-013–FR-015), shared by `DashboardSuite` and the bound
 * dashboard.feature: on a today of its own (`Kit.freshToday`, 10:00 in the zone), every figure the whole brand's over
 * every fact set the kit published (`Kit.everything`), each route's answer reduced to what is compared.
 */
final class Dashboards(kit: Kit, prefix: String):
  val today: LocalDate = kit.freshToday()
  def yest: LocalDate  = today.minusDays(1)
  val hour             = 10

  def players: Vector[String] = (1 to 24).toVector.map(i => f"$prefix-p$i%02d")

  /**
   * 24 players with activity today, the last a test account, four registered today; yesterday's stakes on both sides
   * of the current hour; two payment providers, one with no attempt today; withdrawals through two gateways; open and
   * ended sessions; three restricted funds records, one forfeited; grants, a suspension and a self-exclusion.
   */
  def publish(): Unit =
    val s = FactSet(kit.zone)
    val countries = Vector("ZA", "GB", "DE")
    val ps = players.zipWithIndex.map { (p, i) =>
      val when = if i < 4 then s.at(today, f"07:${i + 1}%02d") else s.at(yest.minusDays(10), "08:00")
      s.register(p, when, test = i == 23, country = countries(i % 3), email = Some(f"player.number.${i + 1}%02d@$prefix.example"))
    }
    ps.zipWithIndex.foreach { (p, i) =>
      val n = i + 1L
      s.deposit(p, "EUR", 50_00 + n, s.at(today, "08:00"))
      s.bet(p, "EUR", s"$p-r", 10_00 + n * 10, 10_00 + n * 10, s.at(today, "08:30"))
      if i % 2 == 0 then s.win(p, "EUR", s"$p-r", 4_00 + n, 4_00 + n, s.at(today, "09:15"))
      // Yesterday: one stake before the current hour, one after it, which yesterday's figure to this hour leaves out.
      s.bet(p, "EUR", s"$p-y1", 3_00 + n, 0, s.at(yest, "09:30"))
      s.bet(p, "EUR", s"$p-y2", 7_00, 0, s.at(yest, "15:00"))
    }
    s.deposit(ps(0), "EUR", 25_00, s.at(yest, "06:00"))
    s.deposit(ps(1), "EUR", 99_00, s.at(yest, "20:00"))
    s.bonus(ps(2), "EUR", 2_50, s.at(today, "09:00"))
    s.bonus(ps(3), "EUR", 1_25, s.at(yest, "08:00"))
    s.chargeback(ps(4), "EUR", 1_00, s.at(today, "09:40"))
    s.withdrawal(ps(5), "EUR", 20_00, s.at(today, "02:00"), s.at(today, "05:30"), gateway = "bank")
    s.withdrawal(ps(6), "EUR", 15_00, s.at(yest, "22:00"), s.at(today, "06:00"), gateway = "crypto")
    s.withdrawal(ps(7), "EUR", 5_00, s.at(yest, "07:00"), s.at(yest, "08:00"), gateway = "bank")
    // psp-a: four attempts today, three captured; psp-b: its one capture today was attempted yesterday.
    for i <- 0 until 3 do s.payment(ps(i), "EUR", s"$prefix-psp-a", 30_00, Some(s.at(today, "07:30")), "captured", Some(s.at(today, "07:35")))
    s.payment(ps(3), "EUR", s"$prefix-psp-a", 30_00, Some(s.at(today, "07:40")), "failed", Some(s.at(today, "07:41")))
    s.payment(ps(4), "EUR", s"$prefix-psp-b", 45_00, Some(s.at(yest, "23:50")), "captured", Some(s.at(today, "00:10")))
    // Five logins and three games open, the rest ended.
    for i <- 0 until 7 do s.login(ps(i), s"$prefix-login-$i", s.at(today, "08:00"), Option.when(i >= 5)(s.at(today, "09:00")))
    for i <- 0 until 4 do
      s.gameSession(ps(i), s"$prefix-game-$i", s"$prefix-g-a", s"$prefix-login-$i", s.at(today, "08:10"), Option.when(i == 3)(s.at(today, "08:50")))
    s.gameSession(ps(5), s"$prefix-game-5", s"$prefix-g-b", s"$prefix-login-5", s.at(yest, "12:00"), Some(s.at(yest, "12:30")))
    s.settled(ps(0), "EUR", s"$prefix-round-a", "pragmatic", s"$prefix-g-a", "Gates of Dashboard", s"$prefix-game-0", 1_00, 0, s.at(today, "08:20"))
    s.settled(ps(5), "EUR", s"$prefix-round-b", "netent", s"$prefix-g-b", "Starburst Dash", s"$prefix-game-5", 1_00, 2_00, s.at(yest, "12:10"))
    s.award(ps(8), "EUR", s"$prefix-a1", 10_00, 100_00, s.at(today, "08:00"))
    s.award(ps(9), "EUR", s"$prefix-a2", 20_00, 50_00, s.at(today, "08:00"))
    s.wager(ps(9), "EUR", s"$prefix-a2", s"$prefix-wr", 5_00, s.at(today, "08:05"), balance = 15_00, progress = 5_00, requirement = 50_00)
    s.award(ps(10), "EUR", s"$prefix-a3", 8_00, 80_00, s.at(today, "08:00"))
    s.forfeitAward(ps(10), "EUR", s"$prefix-a3", 8_00, s.at(today, "08:30"))
    s.status(ps(11), "player_suspended", "suspended", s.at(today, "09:05"))
    s.status(ps(12), "player_self_excluded", "self_excluded", s.at(today, "09:10"))
    s.publish(kit)

  private def everything = kit.everything

  def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  /** The original's arrow, written again (`C/DashboardRoutes.scala:239-243`). */
  def arrow(t: Long, y: Long): String =
    if t == 0 && y == 0 then "flat"
    else if y == 0 || BigDecimal(t) > BigDecimal(y) * BigDecimal("1.02") then "up"
    else if BigDecimal(t) < BigDecimal(y) * BigDecimal("0.98") then "down"
    else "flat"

  def rate(done: Long, attempts: Long): Option[BigDecimal] =
    Option.when(attempts > 0)((BigDecimal(done) * 100 / attempts).setScale(1, BigDecimal.RoundingMode.HALF_UP))

  private def numberOrNull(n: JsonNode): Option[BigDecimal] = Option.when(!n.isNull && !n.isMissingNode)(BigDecimal(n.decimalValue))

  /** An object's values in the order the answer writes them, as one string. */
  private def values(n: JsonNode): String = n.fieldNames.asScala.map(n.get(_).asText).mkString("/")

  private def cut(email: String): String = if email.length > 20 then email.take(20) + "…" else email

  private def emailOf(p: String): String = everything.players.get(p).flatMap(_.email).map(_.toString).getOrElse("")

  /**
   * The route's answer once `shown` of it is `want`, which it is after the facts fold; else the last answer, which the
   * caller holds to `want` and so fails naming the difference.
   */
  def settled[A](path: String, as: Seq[(String, String)] = kit.asOperator)(shown: JsonNode => A, want: => A): JsonNode =
    var last: Reply = null
    val deadline    = 60.seconds.fromNow
    def done        = last != null && last.status == 200 && shown(last.json) == want
    last = kit.get(path, as)
    while !done && deadline.hasTimeLeft() do
      Thread.sleep(250)
      last = kit.get(path, as)
    if last.status != 200 then throw AssertionError(s"$path: ${last.status} ${last.text}")
    last.json

  // ── kpis ──────────────────────────────────────────────────────────────────────────────────────

  def kpisWanted(exclude: Boolean): Map[String, String] =
    val t    = everything.figures(today, "EUR", exclude)
    val y    = everything.figures(yest, "EUR", exclude, upTo = hour)
    val regT = everything.registrations(today, exclude)
    val regY = everything.registrations(yest, exclude, upTo = hour)
    val games = everything.open("game", exclude)
    Map(
      "activeSessions" -> s"$games/0/flat", "activeGames" -> s"$games/0/flat",
      "playersOnline" -> s"${everything.open("player", exclude)}/0/flat",
      "ggrToday" -> s"${money(t.ggr)}/${money(y.ggr)}/${arrow(t.ggr, y.ggr)}/EUR",
      "ngrToday" -> s"${money(t.ngr)}/${money(y.ngr)}/${arrow(t.ngr, y.ngr)}/EUR",
      "depositsToday" -> s"${money(t.deposits)}/${t.depositCount}/${money(y.deposits)}/${arrow(t.deposits, y.deposits)}/EUR",
      "withdrawalsToday" -> s"${money(t.withdrawals)}/${t.withdrawalCount}/${money(y.withdrawals)}/${arrow(t.withdrawals, y.withdrawals)}/EUR",
      "registrationsToday" -> s"$regT/$regY/${arrow(regT, regY)}")

  def kpisShown(j: JsonNode): Map[String, String] =
    Vector("activeSessions", "activeGames", "playersOnline", "ggrToday", "ngrToday", "registrationsToday", "depositsToday",
      "withdrawalsToday").map(k => k -> values(j.path(k))).toMap

  def kpis(exclude: Boolean = false): JsonNode = settled("/dashboard/kpis?currency=EUR")(kpisShown, kpisWanted(exclude))

  // ── payment health ────────────────────────────────────────────────────────────────────────────

  final case class Health(rate: Option[BigDecimal], hours: Option[BigDecimal], providers: Vector[(String, BigDecimal, Long, Option[BigDecimal])])

  def healthWanted: Health =
    val providers = everything.providers(today, "EUR")
    val gates     = everything.gateways(today, today, "EUR")
    val done      = gates.values.map(_._2).sum
    Health(rate(providers.values.map(_._2).sum, providers.values.map(_._1).sum),
      Option.when(done > 0)((BigDecimal(gates.values.map(_._3).sum) / 3600 / done).setScale(1, BigDecimal.RoundingMode.HALF_UP)),
      providers.toVector.sortBy(_._1).map((n, v) => (n, BigDecimal(money(v._3)), v._2, rate(v._2, v._1))))

  def healthShown(j: JsonNode): Health =
    Health(numberOrNull(j.path("depositSuccessRatePct")), numberOrNull(j.path("avgWithdrawalHours")),
      j.path("pspBreakdown").elements.asScala.toVector.map(p => (p.path("pspName").asText, BigDecimal(p.path("depositVolume").decimalValue),
        p.path("depositCount").asLong, numberOrNull(p.path("successRatePct")))))

  def health: JsonNode = settled("/dashboard/payment-health?currency=EUR")(healthShown, healthWanted)

  // ── trend and history ─────────────────────────────────────────────────────────────────────────

  def points(j: JsonNode): Vector[(String, String, String)] =
    j.path("dataPoints").elements.asScala.toVector.map(p => (p.path("label").asText, p.path("ggr").asText, p.path("ngr").asText))

  /** Each span's GGR and NGR, written again from the original's spans: hours of today, days, or 13 ISO-labelled weeks. */
  def trendWanted(range: String): Vector[(String, String, String)] = range match
    case "today" =>
      (0 to hour).toVector.map { h =>
        val to     = everything.figures(today, "EUR", upTo = h)
        val before = if h == 0 then FactSet.Expected() else everything.figures(today, "EUR", upTo = h - 1)
        (f"$h%02d:00", money(to.ggr - before.ggr), money(to.ngr - before.ngr))
      }
    case "90d" =>
      (12 to 0 by -1).toVector.map { w =>
        val start = today.minusDays(w * 7L + 6)
        val e = (0 until 7).map(i => everything.figures(start.plusDays(i), "EUR")).foldLeft(FactSet.Expected())(_ + _)
        (f"${start.getYear}-W${start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}%02d", money(e.ggr), money(e.ngr))
      }
    case other =>
      val n = if other == "30d" then 30 else 7
      (n - 1 to 0 by -1).toVector.map { back =>
        val d = today.minusDays(back.toLong)
        val e = everything.figures(d, "EUR")
        (d.toString, money(e.ggr), money(e.ngr))
      }

  def trend(range: String): JsonNode = settled(s"/dashboard/trend?range=$range&currency=EUR")(points, trendWanted(range))

  def historyWanted: Vector[String] = (6 to 0 by -1).toVector.map { back =>
    val d     = today.minusDays(back.toLong)
    val e     = everything.figures(d, "EUR")
    val label = if back == 0 then "Today" else d.getDayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    Vector(label, d.toString, money(e.ggr), money(e.ngr), money(e.deposits), e.depositCount.toString, money(e.withdrawals),
      e.withdrawalCount.toString, everything.registrations(d).toString).mkString("/")
  }

  def historyShown(j: JsonNode): Vector[String] =
    j.path("days").elements.asScala.toVector.map(values)

  def history: JsonNode = settled("/dashboard/kpi-history?currency=EUR")(historyShown, historyWanted)

  // ── players ───────────────────────────────────────────────────────────────────────────────────

  def geoWanted: Vector[(String, Long)] = everything.countries().toVector.sortBy((c, n) => (-n, c))
  def geoShown(j: JsonNode): Vector[(String, Long)] =
    j.path("countries").elements.asScala.toVector.map(c => c.path("countryCode").asText -> c.path("playerCount").asLong)
  def geo: JsonNode = settled("/dashboard/geo")(geoShown, geoWanted)

  type Ranked = (Vector[(String, String, String, Long)], Vector[(String, String, String, String, String)])

  def rankingsWanted: Ranked =
    val from  = today.minusDays(6)
    val games = everything.gameStarts(from, today).toVector.sortBy((g, n) => (-n, g)).take(10).map { (g, n) =>
      val r = everything.latestRound(g)
      (g, r.fold("")(_.gameName), r.fold("")(_.provider), n)
    }
    val ranked = everything.playerGgr(from, today, "EUR").toVector.sortBy((p, v) => (-v._1, p)).take(10).map { (p, v) =>
      (p, cut(emailOf(p)), everything.players.get(p).flatMap(_.country).getOrElse(""), money(v._1), money(v._2))
    }
    (games, ranked)

  def rankingsShown(j: JsonNode): Ranked = (
    j.path("games").elements.asScala.toVector.map(g => (g.path("gameId").asText, g.path("gameName").asText, g.path("provider").asText,
      g.path("sessionCount").asLong)),
    j.path("players").elements.asScala.toVector.map(p => (p.path("playerId").asText, p.path("truncatedEmail").asText,
      p.path("countryCode").asText, p.path("ggrAmount").asText, p.path("totalWagered").asText)))

  def rankings: JsonNode = settled("/dashboard/rankings?range=7d&currency=EUR")(rankingsShown, rankingsWanted)

  type Entry = (String, String, String, String, Instant)

  def activityWanted: Vector[Entry] =
    everything.activity(today.atStartOfDay(kit.zone).toInstant).take(50).map((id, kind, p, at) => (id, kind, p, cut(emailOf(p)), at))

  def activityShown(j: JsonNode): Vector[Entry] = j.path("events").elements.asScala.toVector.map(e =>
    (e.path("eventId").asText, e.path("eventType").asText, e.path("playerId").asText, e.path("truncatedEmail").asText,
      Instant.parse(e.path("occurredAt").asText)))

  def activity: JsonNode = settled("/dashboard/activity")(activityShown, activityWanted)

  // ── bonus ─────────────────────────────────────────────────────────────────────────────────────

  def bonusWanted: (Long, String, Long, String, Long, Int) =
    val open   = everything.openRecords("EUR")
    val active = open.filter(_.status == "ACTIVE")
    val pcts   = active.filter(_.requirement > 0)
      .map(r => (BigDecimal(r.progress) / r.requirement * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt)
    val owed = open.map(_.balance).sum
    val cost = everything.figures(today, "EUR").bonus
    (active.size.toLong, money(owed), owed, money(cost), cost, if pcts.isEmpty then 0 else pcts.sum / pcts.size)

  def bonusShown(j: JsonNode): (Long, String, Long, String, Long, Int) =
    (j.path("activeBonusCount").asLong, j.path("totalLiability").asText, j.path("totalLiabilityMinorUnits").asLong,
      j.path("bonusCostToday").asText, j.path("bonusCostTodayMinorUnits").asLong, j.path("avgWageringCompletionPct").asInt)

  def bonus: JsonNode = settled("/dashboard/bonus-summary?currency=EUR")(bonusShown, bonusWanted)
