package io.eitheror.casino.reporting.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.DataSubject
import io.eitheror.casino.reporting.it.{FactSet, Reply}

import java.time.{Instant, LocalDate}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/reporting/players.feature (US3; FR-008, FR-010, FR-012, FR-025), bound by T043 over a `FactSet` published
 * once for the feature on days of its own: six players registered on its first day in three countries and three
 * statuses, one verified and signed in, a test account, one with 250 transactions on its second day, and one erased by
 * its scenario. Every figure is held to the set's.
 */
class PlayersFeatures extends ReportingFeatures("players.feature"):
  private lazy val published: (FactSet, LocalDate) =
    val day = kit.days()
    val s   = FactSet(kit.zone)
    val reg = s.at(day, "09:00")
    s.register("pf-ada", reg, country = "GB", email = Some("ada.lovelace@players.example"))
    s.register("pf-bob", reg.plusSeconds(60), country = "ZA", email = Some("bob.builder@players.example"))
    s.register("pf-cy", reg.plusSeconds(120), country = "DE", email = Some("cy.twombly@players.example"), test = true)
    s.register("pf-dee", reg.plusSeconds(180), country = "ZA", email = Some("dee.dee.ramone.of.many@players.example"))
    s.register("pf-eve", reg.plusSeconds(240), country = "GB", email = Some("eve.to.be.erased@players.example"))
    s.register("pf-fay", reg.plusSeconds(300), country = "ZA", email = Some("fay.wray@players.example"))
    s.status("pf-bob", "player_suspended", "suspended", reg.plusSeconds(3600))
    s.status("pf-fay", "player_self_excluded", "self_excluded", reg.plusSeconds(3700))
    s.kycVerified("pf-ada", reg.plusSeconds(1800))
    s.loggedIn("pf-ada", reg.plusSeconds(2000))
    s.deposit("pf-ada", "EUR", 100_00, s.at(day, "10:00"))
    s.bet("pf-ada", "EUR", "pf-r1", 30_00, 30_00, s.at(day, "10:05"))
    s.win("pf-ada", "EUR", "pf-r1", 45_50, 30_00, s.at(day, "10:06"))
    s.bet("pf-ada", "EUR", "pf-r2", 10_00, 10_00, s.at(day, "10:07"))
    s.rollback("pf-ada", "EUR", "pf-r2", 10_00, 10_00, s.at(day, "10:08"))
    s.credit("pf-ada", "EUR", 1_25, s.at(day, "10:09"))
    s.deposit("pf-bob", "EUR", 20_00, s.at(day, "11:00"))
    s.bet("pf-bob", "EUR", "pf-r3", 20_00, 20_00, s.at(day, "11:01"))
    s.deposit("pf-cy", "EUR", 5_00, s.at(day, "12:00"))
    s.deposit("pf-eve", "EUR", 70_00, s.at(day, "13:00"))
    s.bet("pf-eve", "EUR", "pf-r4", 7_00, 7_00, s.at(day, "13:01"))
    val many = s.at(day.plusDays(1), "02:00")
    (0 until 250).foreach { i =>
      val at = many.plusSeconds(i * 10L)
      i % 5 match
        case 0 => s.deposit("pf-dee", "EUR", 20_00 + i, at)
        case 1 => s.bet("pf-dee", "EUR", s"pf-dee-$i", 1_00 + i, 1_00 + i, at)
        case 2 => s.win("pf-dee", "EUR", s"pf-dee-${i - 1}", 50 + i, 50 + i, at)
        case 3 => s.loss("pf-dee", "EUR", s"pf-dee-$i", at)
        case _ => s.credit("pf-dee", "EUR", 10 + i, at)
    }
    s.publish(kit)
    (s, day)

  private def s: FactSet     = published._1
  private def d1: LocalDate = published._2

  private var json: JsonNode   = null
  private var shown: Vector[Map[String, String]] = null
  private var want: Vector[Map[String, String]]  = null
  private var csv: Reply       = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    json = null; shown = null; want = null; csv = null

  private def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  private def email(p: String): String = s.registered.find(_.playerId == p).flatMap(_.email).map(_.toString).getOrElse("")

  private def fields(n: JsonNode): Map[String, String] = n.fieldNames.asScala.map(k => k -> n.get(k).asText).toMap

  // ── the player report ─────────────────────────────────────────────────────────────────────────

  /** Each player registered on the first day as FR-008 says the row reads, in registration order. */
  private def playersWanted(keep: FactSet => String => Boolean = _ => _ => true, erased: Set[String] = Set.empty) =
    s.registered.filter(p => p.registeredAt.atZone(kit.zone).toLocalDate == d1).filter(p => keep(s)(p.playerId)).map { p =>
      val (dep, wag, won) = s.lifetimes(p.playerId, "EUR")
      Map("playerId" -> p.playerId, "email" -> (if erased(p.playerId) then "[erased]" else email(p.playerId)),
        "country" -> p.country.getOrElse(""), "registrationDate" -> p.registeredAt.toString, "status" -> s.statusOf(p.playerId),
        "kycStatus" -> s.kycOf(p.playerId), "lifetimeDeposits" -> money(dep), "lifetimeWagered" -> money(wag),
        "lifetimeGgr" -> money(wag - won), "lastLoginAt" -> s.lastLogin(p.playerId).fold("")(_.toString),
        "isTestAccount" -> s.testAccounts(p.playerId).toString, "currency" -> "EUR")
    }

  private def players(query: String): Reply = kit.get(s"/reports/players?from=$d1&to=$d1&currency=EUR$query", kit.asFinance)

  private def settledPlayers(query: String, w: => Vector[Map[String, String]]): JsonNode =
    val deadline = 60.seconds.fromNow
    var r        = players(query)
    def got      = r.json.path("players").elements.asScala.toVector.map(fields)
    while !(r.status == 200 && got == w) && deadline.hasTimeLeft() do
      Thread.sleep(250)
      r = players(query)
    assertEquals(r.status, 200, r.text)
    r.json

  Given("brand {string}") { (slug: String) =>
    assertEquals(kit.brand, slug)
    s: Unit
  }

  Given("players registered in a period") { () =>
    assert(playersWanted().size >= 5)
  }

  When("compliance runs the player report for it") { () =>
    want = playersWanted()
    json = settledPlayers("", want)
    shown = json.path("players").elements.asScala.toVector.map(fields)
  }

  Then("one row per player registered in the period shows email address, country, registration date, player status, KYC status, lifetime deposits, lifetime wagered, lifetime GGR, last sign-in and whether the account is a test account") {
    () =>
      assertEquals(shown.map(_("playerId")), want.map(_("playerId")))
      assertEquals(shown.map(_.keySet).distinct, Vector(want.head.keySet))
      assertEquals(shown, want)
      assertEquals(json.path("totalCount").asLong, want.size.toLong)
      assert(shown.exists(_("isTestAccount") == "true") && shown.exists(_("kycStatus") == "verified"))
  }

  Then("each lifetime figure is exact over every transaction") { () =>
    val ada = shown.find(_("playerId") == "pf-ada").get
    // 100.00 deposited; 30.00 + 10.00 staked less the 10.00 rolled back; 45.50 won and 1.25 credited.
    assertEquals((ada("lifetimeDeposits"), ada("lifetimeWagered"), ada("lifetimeGgr")), ("100.00", "30.00", "-16.75"))
  }

  Given("the player report") { () =>
    settledPlayers("", playersWanted()): Unit
  }

  When("compliance filters it by country or player status, or sorts it by a column") { () =>
    val zaSuspended = settledPlayers("&country=ZA&status=suspended",
      playersWanted(s => p => s.registered.find(_.playerId == p).flatMap(_.country).contains("ZA") && s.statusOf(p) == "suspended"))
    val za = settledPlayers("&country=ZA", playersWanted(s => p => s.registered.find(_.playerId == p).flatMap(_.country).contains("ZA")))
    shown = za.path("players").elements.asScala.toVector.map(fields)
    want = playersWanted(s => p => s.registered.find(_.playerId == p).flatMap(_.country).contains("ZA"))
    assertEquals(zaSuspended.path("players").elements.asScala.toVector.map(fields).map(_("playerId")), Vector("pf-bob"))
    csv = players("&country=ZA&format=csv")
  }

  Then("the rows are narrowed or ordered accordingly") { () =>
    assertEquals(shown, want)
    assertEquals(shown.map(_("playerId")).toSet, Set("pf-bob", "pf-dee", "pf-fay"))
    // Sorting is the screen's: every column the sort needs is in the answer, ordered here by lifetime GGR.
    val byGgr = shown.sortBy(r => -BigDecimal(r("lifetimeGgr")))
    assertEquals(byGgr.map(_("playerId")), want.sortBy(r => -BigDecimal(r("lifetimeGgr"))).map(_("playerId")))
  }

  Then("the export holds every matching row regardless of the page shown") { () =>
    assertEquals(csv.status, 200, csv.text)
    assert(csv.header("Content-Disposition").exists(_.matches("attachment; filename=\"players-report-[0-9a-f-]{36}\\.csv\"")))
    val lines  = csv.text.split("\r\n").toVector
    val header = lines.head.split(',').toVector
    assertEquals(header, Vector("playerId", "email", "country", "registrationDate", "status", "kycStatus", "lifetimeDeposits",
      "lifetimeWagered", "lifetimeGgr", "lastLoginAt", "isTestAccount", "currency"))
    assertEquals(lines.tail.map(l => header.zip(l.split(",", -1)).toMap), want)
  }

  // ── the transaction report ────────────────────────────────────────────────────────────────────

  private def d2 = d1.plusDays(1)
  private def instants(day: LocalDate): (Instant, Instant) =
    (day.atStartOfDay(kit.zone).toInstant, day.plusDays(1).atStartOfDay(kit.zone).toInstant)

  private def txWanted(t: FactSet.Tx, erased: Set[String] = Set.empty): Map[String, String] =
    val e = email(t.player)
    Map("transactionId" -> t.transactionId, "timestamp" -> t.at.toString, "playerId" -> t.player,
      "truncatedEmail" -> (if erased(t.player) then "[erased]" else if e.length > 25 then e.take(25) + "…" else e),
      "transactionType" -> t.tpe, "amount" -> money(t.amount), "currency" -> t.currency, "reference" -> t.reference)

  private def ledger(day: LocalDate, query: String): Reply = kit.get(s"/reports/transactions?from=$day&to=$day$query", kit.asFinance)
  private def txRows(j: JsonNode) = j.path("transactions").elements.asScala.toVector.map(fields)

  private def settledLedger(day: LocalDate, query: String, n: Int): JsonNode =
    kit.eventually(s"the ledger holds $n rows for $query", 60.seconds)(
      Option(ledger(day, query)).filter(r => r.status == 200 && r.json.path("totalCount").asLong == n).map(_.json))

  Given("a player with more than {string} transactions in the period") { (n: String) =>
    val (f, t) = instants(d2)
    assert(s.ledger(f, t, of = Some(Set("pf-dee"))).size > n.toInt)
  }

  When("the agent runs the transaction report") { () =>
    val (f, t) = instants(d2)
    want = s.ledger(f, t).map(txWanted(_))
    json = settledLedger(d2, "", want.size)
    val pages = json.path("totalPages").asInt
    shown = (0 until pages).toVector.flatMap(p => txRows(ledger(d2, s"&page=$p").json))
  }

  Then("every transaction appears, newest first, paged at {string} rows") { (n: String) =>
    assertEquals(json.path("pageSize").asInt, n.toInt)
    assertEquals(json.path("totalPages").asInt, math.ceil(want.size / n.toDouble).toInt)
    assertEquals(txRows(json).size, n.toInt)
    assertEquals(shown, want)
  }

  Then("each shows its time, the truncated email address, type, amount, currency and reference") { () =>
    val r = shown.head
    assertEquals(r.keySet, Set("transactionId", "timestamp", "playerId", "truncatedEmail", "transactionType", "amount", "currency",
      "reference"))
    assertEquals(r("truncatedEmail"), "dee.dee.ramone.of.many@pl…")
    assertEquals(shown.map(_("transactionType")).toSet, Set("deposit", "bet", "win", "loss", "game_provider_credit"))
  }

  Given("the transaction report") { () =>
    val (f, t) = instants(d1)
    settledLedger(d1, "", s.ledger(f, t).size): Unit
  }

  When("the agent filters it by type or searches by part of an email address") { () =>
    val (f, t) = instants(d1)
    want = s.ledger(f, t, "bet", of = Some(Set("pf-ada"))).map(txWanted(_))
    json = settledLedger(d1, "&type=bet&playerEmail=LOVELACE", want.size)
    shown = txRows(json)
    csv = kit.get(s"/reports/transactions/export?from=$d1&to=$d1&type=bet&playerEmail=lovelace&format=csv", kit.asFinance)
  }

  Then("only matching rows appear") { () =>
    assertEquals(shown, want)
    assert(shown.nonEmpty && shown.forall(r => r("playerId") == "pf-ada" && r("transactionType") == "bet"))
    val (f, t) = instants(d1)
    val deposits = txRows(settledLedger(d1, "&type=deposit", s.ledger(f, t, "deposit").size))
    assertEquals(deposits, s.ledger(f, t, "deposit").map(txWanted(_)))
  }

  Then("the export holds every matching row across every page") { () =>
    assertEquals(csv.status, 200, csv.text)
    val lines  = csv.text.split("\r\n").toVector
    val header = lines.head.split(',').toVector
    assertEquals(lines.tail.map(l => header.zip(l.split(",", -1)).toMap), want)
  }

  // ── erasure ───────────────────────────────────────────────────────────────────────────────────

  Given("an erased player") { () =>
    val (f, t) = instants(d1)
    settledLedger(d1, "&playerEmail=eve.to.be", s.ledger(f, t, of = Some(Set("pf-eve"))).size): Unit
    settledPlayers("", playersWanted()): Unit
    kit.testKit.erase(DataSubject("player/pf-eve"))
  }

  When("the player report or the transaction report includes their rows") { () =>
    want = playersWanted(erased = Set("pf-eve"))
    json = settledPlayers("", want)
    shown = json.path("players").elements.asScala.toVector.map(fields)
  }

  Then("the email address reads as erased") { () =>
    assertEquals(shown.find(_("playerId") == "pf-eve").map(_("email")), Some("[erased]"))
    val (f, t) = instants(d1)
    val eve = txRows(kit.get(s"/reports/transactions/export?from=$d1&to=$d1", kit.asFinance).json).filter(_("playerId") == "pf-eve")
    assertEquals(eve, s.ledger(f, t, of = Some(Set("pf-eve"))).map(txWanted(_, Set("pf-eve"))))
  }

  Then("every figure is unchanged") { () =>
    assertEquals(shown, want)
    val eve = shown.find(_("playerId") == "pf-eve").get
    assertEquals((eve("lifetimeDeposits"), eve("lifetimeWagered"), eve("lifetimeGgr")), ("70.00", "7.00", "7.00"))
  }
