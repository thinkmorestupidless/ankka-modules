package io.eitheror.casino.wallet.it.proto

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentDescriptor, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.wallet.WalletService
import io.eitheror.casino.wallet.api.RgAnswer
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.it.Harness

import java.time.Instant
import scala.concurrent.duration.*

/**
 * Measures the bet-as-a-workflow design (one workflow per provider transaction, the endpoint polling
 * its state) against the direct entity call, on a wallet of many transactions, and checks the two
 * properties the design is for: a duplicate start is answered from the workflow without touching
 * the wallet or `responsible-gambling` twice, and an RG refusal never reaches the wallet.
 *
 * 1,000 transactions by default; 10,000 with `WALLET_PERF=1`. Figures are printed.
 */
class BetWorkflowItSuite extends Harness:

  override def components: Seq[ComponentDescriptor] = WalletService.components(_ => rg) :+ BetWorkflow.descriptor

  private val perf = sys.env.get("WALLET_PERF").contains("1")
  private val size = if perf then 10000 else 1000
  private val t0   = Instant.parse("2026-10-09T10:00:00Z")

  private def client: ComponentClient = testKit.componentClient
  private def wallet(player: String) = client.forEventSourcedEntity(WalletEntity.id(player, "EUR"))
  private def bet(key: String, amount: Long = 100) =
    PlaceBet(key, s"r-$key", "slot", "s1", "prov", "slots", amount, false, BetSwitches(), t0)

  override def beforeAll(): Unit =
    super.beforeAll()
    BetWorkflow.rg.set(rg)

  /** What the endpoint would do: start (or find it started), then poll the state until the outcome lands. */
  private def place(player: String, key: String, amount: Long = 100, bound: FiniteDuration = 5.seconds): Outcome =
    val id = BetWorkflow.id(player, "EUR", key)
    val wf = client.forWorkflow(id)
    try wf.call(BetWorkflow.start).invoke(BetStart(player, "EUR", bet(key, amount))): Unit
    catch case e: CommandError if e.code == ErrorCode.Conflict => ()
    val deadline = System.nanoTime() + bound.toNanos
    var flow     = wf.call(BetWorkflow.status).invoke()
    while flow.outcome.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(1)
      flow = wf.call(BetWorkflow.status).invoke()
    flow.outcome.getOrElse {
      val lifecycle = wf.lifecycle(BetWorkflow).invoke()
      fail(s"no outcome for $key within $bound: ${lifecycle.status} ${lifecycle.failure.getOrElse("")}")
    }

  private def percentiles(times: Seq[Double]): (Double, Double) =
    val sorted = times.sorted
    (sorted(sorted.size / 2), sorted((sorted.size * 99) / 100))

  test(s"a wallet of $size transactions: a bet through the workflow and its poll, against the direct command") {
    val w = wallet("flow")
    w.call(WalletEntity.deposit).invoke(Deposit("seed", Long.MaxValue / 4, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0)): Unit
    (1 until size by 2).foreach { i =>
      w.call(WalletEntity.placeBet).invoke(bet(s"b-$i")): Unit
      w.call(WalletEntity.creditWin).invoke(CreditWin(s"w-$i", s"r-b-$i", 50, t0)): Unit
    }

    val direct = (1 to 1000).map { i =>
      val started = System.nanoTime()
      val o       = w.call(WalletEntity.placeBet).invoke(bet(s"d-$i"))
      assert(o.code.isEmpty, o)
      (System.nanoTime() - started) / 1000000.0
    }
    val flow = (1 to 1000).map { i =>
      val started = System.nanoTime()
      val o       = place("flow", s"f-$i")
      assert(o.code.isEmpty, o)
      (System.nanoTime() - started) / 1000000.0
    }
    val (dm, d99) = percentiles(direct)
    val (fm, f99) = percentiles(flow)
    println(f"wallet of $size%,d transactions, 1,000 bets each way: direct command median $dm%.1f ms, p99 $d99%.1f ms; workflow and poll median $fm%.1f ms, p99 $f99%.1f ms")
    assert(fm < 50 && f99 < 250, s"workflow path: median $fm ms, p99 $f99 ms")
  }

  test("two starts of one transaction at once: one bet, one RG question, both callers get the same outcome") {
    val w = wallet("twice")
    w.call(WalletEntity.deposit).invoke(Deposit("seed", 100000, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0)): Unit
    val asked = new java.util.concurrent.atomic.AtomicInteger
    val counting = new io.eitheror.casino.wallet.api.ResponsibleGambling:
      def mayBet(p: String, c: String, a: Long) = { asked.incrementAndGet(); RgAnswer.Allowed }
      def mayDeposit(p: String, c: String, a: Long, s: DepositSums) = RgAnswer.Allowed
    BetWorkflow.rg.set(counting)
    try
      val pairs = (1 to 50).flatMap(i => Seq(s"dup-$i", s"dup-$i"))
      val outcomes = pairs.map(k => scala.concurrent.Future(place("twice", k, 1000))(using scala.concurrent.ExecutionContext.global))
        .map(ComponentClient.await(_, 30.seconds))
      outcomes.grouped(2).foreach { case Seq(a, b) => assertEquals(a, b) }
      assertEquals(w.call(WalletEntity.balance).invoke().realBalance, 100000L - 50 * 1000L)
      assertEquals(asked.get, 50, "responsible-gambling was asked more than once per transaction")
      assertEquals(w.call(WalletEntity.state).invoke().seen.seen.keySet.count(_.startsWith("dup-")), 50)
    finally BetWorkflow.rg.set(rg)
  }

  test("a bet responsible-gambling refuses never reaches the wallet, and its repeat is the same refusal") {
    val w = wallet("locked")
    w.call(WalletEntity.deposit).invoke(Deposit("seed", 5000, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0)): Unit
    rg.answerBets(RgAnswer.Locked("self-excluded"))
    val first = place("locked", "l-1")
    assertEquals(first.code, Some("PLAYER_LOCKED"))
    rg.allow()
    assertEquals(place("locked", "l-1"), first)
    assertEquals(w.call(WalletEntity.balance).invoke().realBalance, 5000L)
    assert(w.call(WalletEntity.outcomeOf).invoke("l-1").isEmpty, "the wallet saw a refused bet")
  }
