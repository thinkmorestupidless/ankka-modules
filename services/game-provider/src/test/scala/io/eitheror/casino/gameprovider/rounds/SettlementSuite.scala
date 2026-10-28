package io.eitheror.casino.gameprovider.rounds

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.gameprovider.rounds.WalletCall.*

/** FR-014's decision, taken from the requirement rather than from the code. */
class SettlementSuite extends munit.FunSuite with LogCapturing:

  private val b1 = OpenBet("t-1", "t-1", 100, "t-1")
  private val b2 = OpenBet("t-2", "t-2", 200, "t-2")
  private val b3 = OpenBet("t-3", "t-3", 300, "t-3")

  test("a credit to a round with two debits settles the first with the win and the second as a loss") {
    assertEquals(Settlement.credit(Vector(b1, b2), "c-1", 500, freeRounds = false),
      Vector(Win("c-1/t-1", "t-1", 500, "t-1"), Loss("c-1/t-2", "t-2", "t-2")))
  }

  test("every open bet is settled, however many debits the round had") {
    val calls = Settlement.credit(Vector(b1, b2, b3), "c-1", 500, freeRounds = false)
    assertEquals(calls.size, 3)
    assertEquals(calls.collect { case w: Win => w.bet; case l: Loss => l.bet }, Vector("t-1", "t-2", "t-3"))
  }

  test("a zero credit settles every open bet as a loss") {
    assertEquals(Settlement.credit(Vector(b1, b2), "c-1", 0, freeRounds = false),
      Vector(Loss("c-1/t-1", "t-1", "t-1"), Loss("c-1/t-2", "t-2", "t-2")))
  }

  test("a credit to a round with no open bet is a provider credit keyed by the credit's id") {
    assertEquals(Settlement.credit(Vector.empty, "c-1", 500, freeRounds = false), Vector(ProviderCredit("c-1", 500)))
  }

  test("a zero credit to a round with no open bet moves nothing") {
    assertEquals(Settlement.credit(Vector.empty, "c-1", 0, freeRounds = false), Vector.empty)
  }

  test("a round marked with free rounds is credited nothing by this service") {
    assertEquals(Settlement.credit(Vector(b1), "c-1", 500, freeRounds = true), Vector.empty)
    assertEquals(Settlement.credit(Vector.empty, "c-1", 500, freeRounds = true), Vector.empty)
  }

  test("each movement's key names the credit and the bet, so each is idempotent on its own") {
    val keys = Settlement.credit(Vector(b1, b2), "c-9", 1, freeRounds = false).map {
      case Win(k, _, _, _) => k; case Loss(k, _, _) => k; case ProviderCredit(k, _) => k
    }
    assertEquals(keys, Vector("c-9/t-1", "c-9/t-2"))
    assertEquals(keys.distinct.size, keys.size)
  }
