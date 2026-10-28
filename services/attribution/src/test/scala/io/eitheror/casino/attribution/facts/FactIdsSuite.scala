package io.eitheror.casino.attribution.facts

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/** Research R13's forms; none carries a player id. */
class FactIdsSuite extends munit.FunSuite with LogCapturing:
  private val player = "player-42"

  test("every id form") {
    assertEquals(FactIds.click("c-1"), "click/c-1")
    assertEquals(FactIds.registration("P"), "registration/P")
    assertEquals(FactIds.firstDeposit("P"), "first-deposit/P")
    assertEquals(FactIds.deposit("P", "pay-1"), "deposit/P/pay-1")
    assertEquals(FactIds.money("chargeback", "P", "pay-1/refund"), "chargeback/P/pay-1/refund")
    assertEquals(FactIds.ggrDaily("P", "ZAR", "2026-10-25"), "ggr-daily/P/ZAR/2026-10-25")
    assertEquals(FactIds.ggrAdjustment("P", "ZAR", "2026-10-25", "ce-1"), "ggr-adjustment/P/ZAR/2026-10-25/ce-1")
    assertEquals(FactIds.ggrRound("P", "r1", "ce-1"), "ggr-round/P/r1/ce-1")
    assertEquals(FactIds.playerClosed("P"), "player-closed/P")
  }

  test("no form takes a player id: each is built from the pseudonym and references only") {
    val ids = Vector(FactIds.registration("P"), FactIds.firstDeposit("P"), FactIds.deposit("P", "pay"), FactIds.money("bonus-cost", "P", "aw"),
      FactIds.ggrDaily("P", "ZAR", "d"), FactIds.ggrAdjustment("P", "ZAR", "d", "x"), FactIds.ggrRound("P", "r", "x"), FactIds.playerClosed("P"))
    ids.foreach(id => assert(!id.contains(player), id))
  }
