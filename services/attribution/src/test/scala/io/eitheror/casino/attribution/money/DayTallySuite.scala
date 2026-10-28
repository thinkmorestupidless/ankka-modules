package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.attribution.money.RealMoney.Leg
import io.eitheror.casino.attribution.money.Tally.*

import java.time.Instant

class DayTallySuite extends munit.FunSuite:
  private val t0  = Instant.parse("2026-10-25T10:00:00Z")
  private val day = "2026-10-25"
  private def kit = EventSourcedTestKit.of(DayTally, DayTally.id("p-1", "EUR", day))
  private def leg(kind: String, round: String, id: String, amount: Long, s: Long = 0) =
    LegCmd("p-1", "EUR", day, kind, round, id, amount, t0.plusSeconds(s))

  test("stake and win add; rollback reduces the stake; GGR is stake − win") {
    val k = kit
    k.call(DayTally.leg)(leg(Leg.Stake, "r-1", "s-1", 1000)): Unit
    k.call(DayTally.leg)(leg(Leg.Win, "r-1", "w-1", 300)): Unit
    k.call(DayTally.leg)(leg(Leg.Stake, "r-2", "s-2", 500)): Unit
    val s = k.call(DayTally.leg)(leg(Leg.Rollback, "r-2", "rb-2", 500)).replyValue
    assertEquals((s.stake, s.win, s.ggr), (1000L, 300L, 700L))
  }

  test("a loss adds nothing but settles the round; a seen id is a no-op") {
    val k = kit
    k.call(DayTally.leg)(leg(Leg.Stake, "r-1", "s-1", 1000)): Unit
    val loss = k.call(DayTally.leg)(leg(Leg.Loss, "r-1", "l-1", 0))
    assertEquals(loss.eventOfType[LegRecorded].round, Some(RoundDelta(1000, 0, first = true)))
    assertEquals(loss.replyValue.stake, 1000L)
    assert(!k.call(DayTally.leg)(leg(Leg.Stake, "r-1", "s-1", 1000)).persisted)
  }

  test("a settling leg carries the round's unemitted totals and marks them emitted") {
    val k = kit
    assertEquals(k.call(DayTally.leg)(leg(Leg.Stake, "r-1", "s-1", 1000)).eventOfType[LegRecorded].round, None)
    assertEquals(k.call(DayTally.leg)(leg(Leg.Win, "r-1", "w-1", 400)).eventOfType[LegRecorded].round, Some(RoundDelta(1000, 400, true)))
    k.call(DayTally.leg)(leg(Leg.Stake, "r-1", "s-2", 200)): Unit
    assertEquals(k.call(DayTally.leg)(leg(Leg.Win, "r-1", "w-2", 50)).eventOfType[LegRecorded].round, Some(RoundDelta(200, 50, false)))
    assertEquals(k.currentState.rounds("r-1"), Round(1200, 450, 1200, 450))
  }

  test("close once, with the totals in Closed") {
    val k = kit
    k.call(DayTally.leg)(leg(Leg.Stake, "r-1", "s-1", 1000)): Unit
    k.call(DayTally.leg)(leg(Leg.Win, "r-1", "w-1", 300)): Unit
    val c = k.call(DayTally.close)(t0.plusSeconds(3600))
    assertEquals(c.events, Vector(Closed("p-1", "EUR", day, t0.plusSeconds(3600), 1000, 300)))
    assert(!k.call(DayTally.close)(t0.plusSeconds(7200)).persisted)
  }

  test("a leg after the close is Adjusted by its GGR change, counted, the totals unchanged") {
    val k = kit
    k.call(DayTally.leg)(leg(Leg.Stake, "r-1", "s-1", 1000)): Unit
    k.call(DayTally.close)(t0.plusSeconds(3600)): Unit
    val a = k.call(DayTally.leg)(leg(Leg.Win, "r-1", "w-1", 300, 4000))
    assertEquals(a.events, Vector(Adjusted("p-1", "EUR", day, Leg.Win, "r-1", "w-1", -300, t0.plusSeconds(4000))))
    assertEquals((a.replyValue.adjustments, a.replyValue.win), (1, 0L))
    assert(!k.call(DayTally.leg)(leg(Leg.Loss, "r-1", "l-1", 0)).persisted)
    assert(k.call(DayTally.leg)(leg("bet", "r-1", "x", 1)).isError)
  }
