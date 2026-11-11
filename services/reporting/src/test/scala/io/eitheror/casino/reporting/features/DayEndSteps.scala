package io.eitheror.casino.reporting.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.reporting.it.{DayEnded, Reply}
import io.eitheror.casino.reporting.reads.Rounds

import java.time.{Instant, LocalDate}
import scala.concurrent.duration.*

import DayEnded.{fields, list}

/**
 * features/reporting/day-end.feature (US6; FR-018, FR-019, FR-021; SC-008), over a day of the scenario's own in the
 * day-end's currency (`DayEnded`): its facts published, folded, and the day closed by `close-days` once its end and the
 * grace have passed. Every figure is held to what the facts say.
 */
class DayEndFeatures extends ReportingFeatures("day-end.feature"):
  private var d: DayEnded         = null
  private var dayEnd: JsonNode    = null
  private var expected: Map[String, String] = Map.empty
  private var exported: Reply     = null
  private var today: LocalDate    = null
  private var arrived: Instant    = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    d = null; dayEnd = null; expected = Map.empty; exported = null; today = null; arrived = null

  Given("brand {string}") { (slug: String) =>
    assertEquals(slug, kit.brand)
  }

  private def usualDay(): Unit =
    d = DayEnded(kit, kit.days()).usual().publish().settle()
    dayEnd = d.close()

  // ── the sections ────────────────────────────────────────────────────────────────────────────────

  Given("a reporting day that has closed") { () => usualDay() }

  When("finance reads the day-end") { () =>
    last = DayEnded.read(kit, d.day)
    assertEquals(last.status, 200, last.text)
  }

  private def currency = last.json.path("currencies").path(d.currency)

  Then("it holds, per currency, deposits and withdrawals per payment provider with counts and values") { () =>
    assertEquals(list(currency.path("deposits")).map(n => (n.path("provider").asText, n.path("count").asLong, n.path("amount").asText)),
      d.deposits)
    assertEquals(list(currency.path("withdrawals")).map(n => (n.path("gateway").asText, n.path("count").asLong, n.path("amount").asText)),
      d.withdrawals)
  }

  Then("GGR per game provider and per game from the settled rounds, beside the wallet's GGR from its transactions") { () =>
    assertEquals(list(currency.path("games")).map(n => (n.path("provider").asText, n.path("gameId").asText, n.path("gameName").asText,
      n.path("providerGgr").asText, n.path("walletGgr").asText)), d.games)
    assertEquals(currency.path("walletGgr").asText, d.totals("walletGgr"))
  }

  Then("bonus cost, chargebacks, and the restricted funds liability and pending holds at close") { () =>
    for k <- Vector("bonusCost", "chargebacks", "liability", "pendingHolds") do assertEquals(currency.path(k).asText, d.totals(k), k)
  }

  // ── differences ─────────────────────────────────────────────────────────────────────────────────

  /** A day with one round of `kind`, and a deposit so the day exists to close. */
  private def oneRound(status: String)(facts: DayEnded => Unit): Unit =
    d = DayEnded(kit, kit.days())
    d.set.deposit(d.player, d.currency, 1_00, d.set.at(d.day, "08:00"))
    facts(d)
    d.publish()
    kit.eventually(s"the round $status", 60.seconds)(Option.when(Rounds.ofDay(kit.views, kit.settings.generation, d.day.toString)
      .exists(r => r.roundId == d.round(1) && r.status == status))(()))
    kit.eventually("the deposit folded", 60.seconds)(io.eitheror.casino.reporting.day.DaysView.row(kit.views, kit.settings.generation, d.day)
      .filter(_.currencies.get(d.currency).exists(_.total.money.deposits == 1_00)))
    dayEnd = d.close()

  private def money(v: Long) = java.math.BigDecimal.valueOf(v, 2).toPlainString

  Given("a settled round whose stake and win differ from the wallet's facts for it") { () =>
    oneRound("differs") { d =>
      d.set.bet(d.player, d.currency, d.round(1), 8_00, 8_00, d.set.at(d.day, "10:00"))
      d.set.win(d.player, d.currency, d.round(1), 1_00, 1_00, d.set.at(d.day, "10:01"))
      d.set.settled(d.player, d.currency, d.round(1), d.provider, d.game, "Book of Gold", "gs", 7_00, 2_00, d.set.at(d.day, "10:01"))
    }
    expected = Map("providerStake" -> money(7_00), "providerWin" -> money(2_00), "walletStake" -> money(8_00), "walletWin" -> money(1_00),
      "provider" -> d.provider, "status" -> "differs")
  }

  Given("a settled round the wallet has no facts for") { () =>
    oneRound("provider_only") { d =>
      d.set.settled(d.player, d.currency, d.round(1), d.provider, d.game, "Book of Gold", "gs", 4_00, 0, d.set.at(d.day, "10:01"))
    }
    expected = Map("providerStake" -> money(4_00), "providerWin" -> money(0), "walletStake" -> money(0), "walletWin" -> money(0),
      "provider" -> d.provider, "status" -> "provider_only")
  }

  Given("a round the wallet settled that the game provider's record lacks") { () =>
    oneRound("wallet_only") { d =>
      d.set.bet(d.player, d.currency, d.round(1), 3_00, 3_00, d.set.at(d.day, "10:00"))
      d.set.win(d.player, d.currency, d.round(1), 6_00, 6_00, d.set.at(d.day, "10:01"))
    }
    expected = Map("providerStake" -> money(0), "providerWin" -> money(0), "walletStake" -> money(3_00), "walletWin" -> money(6_00),
      "provider" -> "", "status" -> "wallet_only")
  }

  When("the day-end is read") { () =>
    last = DayEnded.read(kit, d.day)
    assertEquals(last.status, 200, last.text)
  }

  Then("the round is listed under differences with both figures") { () =>
    val mine = list(last.json.path("differences")).map(fields).filter(_("roundId") == d.round(1))
    assertEquals(mine, Vector(expected + ("roundId" -> d.round(1))))
  }

  // ── late facts ──────────────────────────────────────────────────────────────────────────────────

  Given("a fact for a closed day arriving after the close, a late settlement or a reversal") { () =>
    usualDay()
    val (t, at) = d.arriveLate { late =>
      late.win(d.player, d.currency, d.round(2), 2_50, 2_50, late.at(d.day, "21:00"))
      late.rollback(d.player, d.currency, d.round(4), 2_00, 2_00, late.at(d.day, "21:05"))
    }
    today = t
    arrived = at
  }

  private def lateWin(c: JsonNode)      = c.path("figures").path("wins").asText == money(2_50)
  private def lateRollback(c: JsonNode) = c.path("figures").path("bets").asText == money(-2_00)

  When("it is read") { () =>
    d.corrected(lateWin)
    last = Reply(200, d.corrected(lateRollback).toString)
  }

  Then("the day-end is unchanged") { () =>
    assertEquals(DayEnded.fixed(last.json), DayEnded.fixed(dayEnd))
  }

  Then("the fact appears as a correction dated when it arrived") { () =>
    val cs = list(last.json.path("corrections"))
    for c <- Vector(cs.find(lateWin), cs.find(lateRollback)) do
      assertEquals(c.map(n => (n.path("arrivedAt").asText, n.path("day").asText)), Some((arrived.toString, d.day.toString)))
  }

  Then("it is carried into the next day-end's opening") { () =>
    val next = DayEnded.closeAtGrace(kit, today)
    val opening = list(next.path("openingCorrections")).filter(_.path("day").asText == d.day.toString)
    assert(opening.exists(lateWin) && opening.exists(lateRollback), opening.toString)
    assert(opening.forall(_.path("arrivedAt").asText == arrived.toString), opening.toString)
  }

  // ── the export ──────────────────────────────────────────────────────────────────────────────────

  Given("the day-end") { () => usualDay() }

  When("finance exports it") { () =>
    exported = kit.get(s"/day-end/${d.day}/export", DayEnded.finance(kit))
  }

  Then("the file holds every section with the reference number and the close time") { () =>
    assertEquals(exported.status, 200, exported.text)
    val ref  = exported.header("Content-Disposition").get.stripPrefix("attachment; filename=\"day-end-report-").stripSuffix(".csv\"")
    val rows = exported.text.split("\r\n").toVector.map(_.split(",", -1).toVector)
    def value(section: String, currency: String, item: String, field: String) =
      rows.collectFirst { case Vector(`section`, `currency`, `item`, `field`, v) => v }
    assertEquals(value("day-end", "", "", "reference"), Some(ref))
    assertEquals(value("day-end", "", "", "closedAt"), Some(dayEnd.path("closedAt").asText))
    val sections = rows.tail.map(_.head).toSet
    assertEquals(sections, Set("day-end", "deposits", "withdrawals", "games", "totals", "differences"))
    for (k, v) <- d.totals do assertEquals(value("totals", d.currency, "", k), Some(v), k)
  }
