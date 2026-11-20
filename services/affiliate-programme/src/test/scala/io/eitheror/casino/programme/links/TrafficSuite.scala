package io.eitheror.casino.programme.links

import io.eitheror.casino.programme.periods.Periods
import io.eitheror.casino.programme.players.{ClickState, PlayerFact, PlayerFactRow}

import java.time.{Instant, ZoneId}

/** The traffic fold (spec 030 A3): counts per brand, affiliate and link, and a click held to its period in the brand's zone. */
class TrafficSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-09-10T12:00:00Z")
  private def click(aff: String, link: String, when: Instant = at) =
    ClickState(s"goldvibe/${link}-${when.toEpochMilli}-${aff}", "goldvibe", link, aff, occurredAt = Some(when))
  private def row(aff: String, link: String, kind: String, outcome: String, ps: String = "ps-1") =
    PlayerFactRow("goldvibe", ps, aff, s"$kind-$ps-$outcome", kind, outcome, period = "2026-09", occurredAt = at, link = Some(link))

  test("clicks, registrations, first deposits and qualified first deposits are counted per link, per affiliate and for the brand") {
    val clicks  = Vector(click("a", "l1"), click("a", "l1", at.plusSeconds(1)), click("a", "l2"), click("b", "l3"))
    val players = Vector(
      row("a", "l1", PlayerFact.Registration, "bound"),
      row("a", "l1", PlayerFact.FirstDeposit, Traffic.Qualified),
      row("a", "l1", PlayerFact.Deposit, "deposit"),
      row("a", "l2", PlayerFact.Registration, "bound", "ps-2"),
      row("a", "l2", PlayerFact.FirstDeposit, "deposit", "ps-2"),
      // a later deposit that met the minimum sets the guard: qualified, and no first deposit
      row("a", "l2", PlayerFact.Deposit, Traffic.Qualified, "ps-2"),
      row("b", "l3", PlayerFact.Registration, "duplicate", "ps-3"),
      row("b", "l3", PlayerFact.GgrDaily, "ggr", "ps-3")
    )
    val t = Traffic.answer("goldvibe", "2026-09", clicks, players)
    assertEquals((t.clickCount, t.registrationCount, t.ftdCount, t.qftdCount), (4L, 2L, 2L, 2L))
    assertEquals(t.affiliates.map(_.affiliateId), Vector("a", "b"))
    val a = t.affiliates.head
    assertEquals((a.clickCount, a.registrationCount, a.ftdCount, a.qftdCount), (3L, 2L, 2L, 2L))
    assertEquals(a.links, Vector(LinkTraffic("l1", 2, 1, 1, 1), LinkTraffic("l2", 1, 1, 1, 1)))
    assertEquals(t.affiliates(1).links, Vector(LinkTraffic("l3", 1, 0, 0, 0)))
  }

  test("a brand with no traffic answers zeros and no affiliate") {
    assertEquals(Traffic.answer("goldvibe", "2026-09", Nil, Nil), TrafficAnswer("goldvibe", "2026-09", 0, 0, 0, 0, Vector.empty))
  }

  test("a click is held to the month its time falls in in the brand's zone, not in UTC") {
    val zone  = ZoneId.of("America/Sao_Paulo")
    val late  = click("a", "l1", Instant.parse("2026-11-01T02:30:00Z")) // 23:30 on 31 October there
    val oct   = Periods.Range.parse("2026-10").get
    val nov   = Periods.Range.parse("2026-11").get
    assert(Traffic.holds(oct, late, zone))
    assert(!Traffic.holds(nov, late, zone))
    assert(Traffic.holds(nov, late, ZoneId.of("UTC")))
    assert(Traffic.holds(Periods.Range.parse("2026-10-31..2026-10-31").get, late, zone))
    assert(Traffic.holds(Periods.Range.AllTime, late, zone))
    assert(!Traffic.holds(oct, late.copy(occurredAt = None), zone))
  }

  test("the view is asked a day wider than the range on each side, in any zone") {
    val oct = Periods.Range.parse("2026-10").get
    assertEquals(Traffic.days(oct, ZoneId.of("UTC")), ("2026-09-30", "2026-11-03"))
    assertEquals(Traffic.days(oct, ZoneId.of("Pacific/Kiritimati")), ("2026-09-29", "2026-11-02"))
    assertEquals(Traffic.days(Periods.Range.AllTime, ZoneId.of("UTC")), ("0000-00-00", "9999-99-99"))
    // An ISO instant compares with the day bounds as text: a click on the range's first or last day is between them.
    val (from, until) = Traffic.days(oct, ZoneId.of("America/Sao_Paulo"))
    for t <- Seq("2026-10-01T03:00:00Z", "2026-11-01T02:59:59.999Z") do assert(t >= from && t < until, s"$t not in [$from, $until)")
  }
