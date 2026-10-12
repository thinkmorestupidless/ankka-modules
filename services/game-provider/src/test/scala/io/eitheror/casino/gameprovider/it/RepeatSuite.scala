package io.eitheror.casino.gameprovider.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.features.SharedKit

import java.util.UUID

/** SC-004: a repeated callback never calls the wallet, nor `may-bet` (research R18). 1,000 repeats of debits, credits and cancels. */
class RepeatSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")
  private def kit = SharedKit.kit

  test("1,000 repeats, zero wallet calls and zero may-bet asks, each answered as the first was") {
    kit.reset()
    assertEquals(kit.configureSt8().status, 204)
    val run    = UUID.randomUUID().toString.take(8)
    val player = s"$run-repeat"
    kit.wallet.set(player, "EUR", 1_000_000)
    val token = kit.sessionTicket(player)

    val firsts = (1 to 10).flatMap { i =>
      val round = s"$run-r$i"
      Seq(
        "debit"  -> Json.obj("token" -> token, "transaction_id" -> s"$round-d", "round" -> round, "amount" -> "1.00", "currency" -> "EUR"),
        "credit" -> Json.obj("token" -> token, "transaction_id" -> s"$round-c", "round" -> round, "amount" -> "2.00", "currency" -> "EUR"),
        "cancel" -> Json.obj("token" -> token, "player" -> player, "cancel_id" -> s"$round-x", "transaction_id" -> s"$round-c", "currency" -> "EUR")
      ).map((name, body) => (name, body, kit.callback(name, body)))
    }
    firsts.foreach((name, _, r) => assertEquals(r.word, "ok", s"$name: ${r.text}"))

    val calls = kit.wallet.moneyCalls.size
    val asked = kit.neighbours.mayBetAsked.size
    (1 to 1000).foreach { i =>
      val (name, body, first) = firsts(i % firsts.size)
      assertEquals(kit.callback(name, body).text, first.text)
    }
    assertEquals(kit.wallet.moneyCalls.size, calls, "not one repeat reached the wallet")
    assertEquals(kit.neighbours.mayBetAsked.size, asked, "not one repeat asked responsible gambling")
  }
