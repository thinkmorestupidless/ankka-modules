package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.players.rounds.RoundRow


/** Spec 030 P2, P10: what a list answers when what it sums or counts is more than it reads in time. */
class ListBoundsSuite extends munit.FunSuite with LogCapturing:

  private def round(stake: Long, win: Long) = RoundRow(roundId = s"r-$stake-$win", currency = "EUR", stake = stake, win = win, net = win - stake)

  test("bets' sums say when they reached the cap and so are of the first rounds only") {
    val rows = Vector(round(100, 0), round(50, 150))
    val under = OperatorSessionRoutes.summary(rows, cap = 3)
    assertEquals((under.get("staked").asLong, under.get("won").asLong, under.get("truncated").asBoolean), (150L, 150L, false))
    assert(OperatorSessionRoutes.summary(rows, cap = 2).get("truncated").asBoolean)
  }
