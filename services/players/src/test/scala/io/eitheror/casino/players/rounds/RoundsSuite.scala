package io.eitheror.casino.players.rounds

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

/** Spec 030 P5, P6: a round's settlement replaces its opening and keeps its time; a bet counts once; totals per currency. */
class RoundsSuite extends munit.FunSuite with LogCapturing:
  private val open = RoundRow("st8/r", "p-1", "gs-1", "g-1", "EUR", "st8", "r", RoundStatus.Open, openedAt = Some(1L))
  private val done = open.copy(status = RoundStatus.Settled, outcome = Some("win"), stake = 100, win = 250, net = 150, openedAt = None,
    settledAt = Some(5L))

  test("settled after opened keeps the opening's time; an opening after the settlement changes nothing") {
    val kit = KeyValueEntityTestKit.of(RoundEntry, "round/st8/r")
    kit.call(RoundEntry.opened)(open): Unit
    kit.call(RoundEntry.settled)(done): Unit
    assertEquals(kit.currentState, done.copy(openedAt = Some(1L)))
    assertEquals(kit.call(RoundEntry.opened)(open).replyValue.changed, false)
    assertEquals(kit.currentState.status, RoundStatus.Settled)
    assertEquals(kit.currentState.at, 5L)
  }

  test("a bet is counted once under its transaction id") {
    val kit = KeyValueEntityTestKit.of(SessionWagers, "wagers/gs-1")
    kit.call(SessionWagers.bet)(BetSeen("gs-1", "p-1", "EUR", "t-1", 100)): Unit
    kit.call(SessionWagers.bet)(BetSeen("gs-1", "p-1", "EUR", "t-1", 100)): Unit
    kit.call(SessionWagers.bet)(BetSeen("gs-1", "p-1", "EUR", "t-2", 50)): Unit
    assertEquals(kit.currentState.wagered, 150L)
  }

  test("totals are per currency, never summed across") {
    val rows = Vector(done, done.copy(roundId = "b"), done.copy(roundId = "c", currency = "BRL", stake = 10, win = 0, net = -10))
    assertEquals(RoundTotals.of(rows), Vector(RoundTotals("BRL", 1, 10, 0, -10), RoundTotals("EUR", 2, 200, 500, 300)))
  }
