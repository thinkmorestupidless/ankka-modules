package io.eitheror.casino.gameprovider.it

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.rounds.*

import java.util.concurrent.{Callable, Executors}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The bet workflow per provider transaction (research R18, T083): the claim, the steps by kind, the
 * outcome written only once the round holds the transaction, `may-bet` failing closed, and a wallet that
 * cannot be reached leaving the transaction unacknowledged until a retry resumes it or a resolve ends it.
 */
class BetWorkflowSuite extends SharedKitSuite:

  private def workflow(tx: String) = kit.testKit.componentClient.forWorkflow(BetWorkflow.id("st8", tx))
  private def flow(tx: String): BetFlow = workflow(tx).call(BetWorkflow.status).invoke()

  private def debit(token: String, tx: String, round: String, amount: String = "1.00") =
    kit.callback("debit", Json.obj("token" -> token, "transaction_id" -> tx, "round" -> round, "amount" -> amount, "currency" -> "EUR"))

  private def credit(token: String, tx: String, round: String, amount: String) =
    kit.callback("credit", Json.obj("token" -> token, "transaction_id" -> tx, "round" -> round, "amount" -> amount, "currency" -> "EUR"))

  test("a debit's start is the claim: the workflow holds the transaction, and a second start is refused Conflict") {
    val player = s"$run-claim"
    kit.wallet.set(player, "EUR", 1000)
    val token = kit.sessionTicket(player)
    assertEquals(debit(token, s"$run-t1", s"$run-r1").word, "ok")
    val state = flow(s"$run-t1")
    assertEquals((state.status, state.outcome.map(_.status), state.job.map(_.player)), (BetFlow.Done, Some("ok"), Some(player)))
    val again = intercept[CommandError](workflow(s"$run-t1").call(BetWorkflow.start).invoke(state.job.get))
    assertEquals(again.code, ErrorCode.Conflict)
  }

  test("every answered debit is already on its round: the outcome is written after record-round") {
    val player = s"$run-order"
    kit.wallet.set(player, "EUR", 100_000)
    val token = kit.sessionTicket(player)
    // A slow `record-round` makes the order observable: an answer given before it would beat the round.
    BetRuntime.recordDelay = 150.millis
    try
      (1 to 5).foreach { i =>
        val (tx, round) = (s"$run-t$i", s"$run-r$i")
        assertEquals(debit(token, tx, round).word, "ok")
        assert(kit.round(round).openBets.exists(_.walletKey == tx), s"debit $i was answered before its round held the bet")
        // The credit sent at once finds the bet and settles it.
        assertEquals(credit(token, s"$run-c$i", round, "2.00").word, "ok")
      }
    finally BetRuntime.recordDelay = Duration.Zero
    assertEquals(kit.wallet.openBets(player, "EUR"), Vector.empty)
  }

  test("may-bet refusing ends the workflow player_locked with the balance; the wallet is not called; a repeat asks nothing") {
    val player = s"$run-locked"
    kit.wallet.set(player, "EUR", 1000)
    kit.neighbours.betRefused(player) = "excluded"
    val token = kit.sessionTicket(player)
    val first = debit(token, s"$run-t1", s"$run-r1")
    assertEquals((first.word, first.json.text("balance")), ("player_locked", Some("10.00")))
    assertEquals(kit.wallet.calls("bets"), Vector.empty)
    val asked = kit.neighbours.mayBetAsked.size
    kit.neighbours.betRefused.remove(player)
    assertEquals(debit(token, s"$run-t1", s"$run-r1").text, first.text, "the refusal is the outcome")
    assertEquals(kit.neighbours.mayBetAsked.size, asked)
  }

  test("may-bet silent past 500 ms ends the workflow error, failing closed, with no wallet call") {
    val player = s"$run-silent"
    kit.wallet.set(player, "EUR", 1000)
    kit.neighbours.betSilent += player
    val token   = kit.sessionTicket(player)
    val started = System.nanoTime()
    assertEquals(debit(token, s"$run-t1", s"$run-r1").word, "error")
    assert((System.nanoTime() - started) / 1_000_000 < 1500, "answered within the bound, not after the neighbour")
    assertEquals(kit.wallet.calls("bets"), Vector.empty)
    assertEquals(flow(s"$run-t1").outcome.map(_.status), Some("error"))
  }

  test("a zero debit and a credit ask responsible gambling nothing") {
    val player = s"$run-zero"
    kit.wallet.set(player, "EUR", 1000)
    val token = kit.sessionTicket(player)
    assertEquals(debit(token, s"$run-t1", s"$run-r1", "0").word, "ok")
    assertEquals(credit(token, s"$run-c1", s"$run-r1", "0").word, "ok")
    assertEquals(kit.neighbours.mayBetAsked.size, 0)
  }

  test("a wallet that cannot be reached leaves the transaction unacknowledged with no outcome; the provider's retry resumes it") {
    val player = s"$run-down"
    kit.wallet.set(player, "EUR", 1000)
    val token = kit.sessionTicket(player)
    kit.wallet.down = true
    assertEquals(debit(token, s"$run-t1", s"$run-r1").word, "error")
    val paused = flow(s"$run-t1")
    assertEquals((paused.status, paused.outcome), (BetFlow.Unacknowledged, None))
    assert(kit.round(s"$run-r1").openUnacknowledged(s"$run-t1").isDefined)
    kit.wallet.down = false
    assertEquals(debit(token, s"$run-t1", s"$run-r1").word, "ok")
    val entry = kit.round(s"$run-r1").unacknowledged.find(_.transactionId == s"$run-t1").get
    assertEquals(entry.cleared.map(_.how), Some(Cleared.ProviderRetry))
    assertEquals(kit.wallet.openBets(player, "EUR").map(_.key), Vector(s"$run-t1"))
  }

  test("a resolved transaction is ended: a late retry is answered error and moves nothing") {
    val player = s"$run-resolve"
    kit.wallet.set(player, "EUR", 1000)
    val token = kit.sessionTicket(player)
    kit.wallet.down = true
    assertEquals(debit(token, s"$run-t1", s"$run-r1").word, "error")
    kit.wallet.down = false
    // The operator's route finds the entry through the view, which follows the round.
    kit.eventually("the entry in the view")(
      kit.testKit.service.viewClient.forView(io.eitheror.casino.gameprovider.views.Unacknowledged).get(s"st8/$run-r1").filter(_.openCount == 1))
    val resolve = kit.request("POST", s"/v1/casino/backoffice/${kit.brand}/game-provider/unacknowledged/$run-t1/resolve",
      Some("""{"reason":"settled by hand"}"""), kit.asOperator())
    assertEquals(resolve.status, 204, resolve.text)
    assertEquals(flow(s"$run-t1").status, BetFlow.Resolved)
    val calls = kit.wallet.moneyCalls.size
    assertEquals(debit(token, s"$run-t1", s"$run-r1").word, "error")
    assertEquals(kit.wallet.moneyCalls.size, calls)
  }

  test("50 pairs of simultaneous duplicate debits place 50 bets, ask may-bet 50 times, and answer each pair alike") {
    val player = s"$run-pairs"
    kit.wallet.set(player, "EUR", 1_000_000)
    val token = kit.sessionTicket(player)
    val pool  = Executors.newVirtualThreadPerTaskExecutor()
    try
      val tasks = (1 to 50).flatMap(i => Seq.fill(2)((() => i -> debit(token, s"$run-t$i", s"$run-r$i").text): Callable[(Int, String)]))
      val answers = pool.invokeAll(tasks.asJava).asScala.map(_.get).groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap
      assert(answers.values.forall(_.size == 1), s"a pair answered differently: ${answers.filter(_._2.size > 1)}")
      assertEquals(kit.wallet.calls("bets").map(_.json.get.text("key").get).distinct.size, 50)
      assertEquals(kit.wallet.openBets(player, "EUR").size, 50)
      assertEquals(kit.neighbours.mayBetAsked.size, 50)
    finally pool.shutdown()
  }
