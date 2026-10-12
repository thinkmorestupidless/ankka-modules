package io.eitheror.casino.gameprovider.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.features.SharedKit

import java.nio.file.{Files, Path}
import java.util.UUID
import scala.collection.mutable

/**
 * SC-003: after a debit, a credit, a zero credit or a cancel, the wallet holds no open bet for the round,
 * over 1,000 rounds including multi-debit ones; and SC-002's timing report over the money callbacks.
 */
class LeakSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")
  private def kit = SharedKit.kit

  test("1,000 rounds, multi-debit, zero credits and cancels included, leave no bet open in the wallet") {
    kit.reset()
    assertEquals(kit.configureSt8().status, 204)
    val run    = UUID.randomUUID().toString.take(8)
    val player = s"$run-leak"
    kit.wallet.set(player, "EUR", 10_000_000)
    val token  = kit.sessionTicket(player)
    val timing = mutable.ArrayBuffer.empty[Long]

    def call(name: String, fields: (String, Any)*): Reply =
      val started = System.nanoTime()
      val r       = kit.callback(name, Json.obj((("token" -> token) +: ("currency" -> "EUR") +: fields)*))
      timing += (System.nanoTime() - started) / 1_000_000
      assertEquals(r.word, "ok", s"$name ${fields.toMap}: ${r.text}")
      r

    (1 to 1000).foreach { i =>
      val round = s"$run-r$i"
      val debits = 1 + i % 3 // one, two or three debits
      (1 to debits).foreach(d => call("debit", "transaction_id" -> s"$round-d$d", "round" -> round, "amount" -> "0.10"))
      i % 4 match
        case 0 => call("credit", "transaction_id" -> s"$round-c", "round" -> round, "amount" -> "0")
        case 1 => (1 to debits).foreach(d => call("cancel", "player" -> player, "cancel_id" -> s"$round-x$d", "transaction_id" -> s"$round-d$d"))
        case _ => call("credit", "transaction_id" -> s"$round-c", "round" -> round, "amount" -> "0.25")
    }

    assertEquals(kit.wallet.openBets(player, "EUR"), Vector.empty, "no round leaves a bet open")
    val sorted = timing.sorted
    val p95    = sorted((sorted.size * 95) / 100)
    val report = s"money callbacks: ${sorted.size}, p50 ${sorted(sorted.size / 2)} ms, p95 $p95 ms, max ${sorted.last} ms (SC-002 asks under 100 ms at p95)\n"
    Files.writeString(Path.of("target/sc-002-timing.txt"), report)
    println(report)
  }
