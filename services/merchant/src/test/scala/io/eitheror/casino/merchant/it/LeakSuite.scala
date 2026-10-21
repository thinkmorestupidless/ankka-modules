package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.merchant.deposits.DepositFlow
import io.eitheror.casino.merchant.payments.Status

import java.time.Duration as JDuration
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * SC-003: no deposit the provider captured is ever recorded as expired. A thousand deposits, each
 * captured by its provider: nine in ten delivered at once, one in ten held undelivered at the gateway
 * past two deadlines and delivered late; the wallet answering `OUTCOME_PENDING` first for one capture in
 * ten. Afterwards none is expired, and each is credited exactly once.
 */
class LeakSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 20.minutes
  private var kit: Kit = null
  private val pool     = Executors.newFixedThreadPool(32)
  private given ExecutionContext = ExecutionContext.fromExecutor(pool)

  override def beforeAll(): Unit =
    kit = Kit(io.eitheror.casino.merchant.Settings(paymentDeadline = JDuration.ofSeconds(4)))
  override def afterAll(): Unit =
    pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS)
    if kit != null then kit.stop()

  private val Count = sys.env.get("MERCHANT_LEAK_COUNT").flatMap(_.toIntOption).getOrElse(1000)

  test(s"$Count captured deposits, one in ten delivered after two deadlines, leave none expired and each credited once") {
    val r   = kit.subBrand()
    // Every deposit is captured by its provider as soon as it is made, so until the gateway delivers it, its
    // session holds the outcome undelivered: that, and not a pending session, is what the deadline meets.
    val ids = Await.result(Future.traverse((1 to Count).toVector)(_ => Future {
      val id = kit.payment(r)
      kit.gateway.session(id, FakeGateway.Session.Undelivered)
      id
    }), 10.minutes)
    val (late, prompt) = ids.zipWithIndex.partition(_._2 % 10 == 0)
    kit.wallet.pending = Count / 10
    Await.result(Future.traverse(prompt)((id, _) => Future(kit.capture(id))), 10.minutes): Unit
    Thread.sleep(9_000) // two deadlines pass for the late ones
    Await.result(Future.traverse(late)((id, _) => Future(kit.capture(id))), 10.minutes): Unit
    // Every deposit credited within a few minutes, else the statuses of those that are not, to say why.
    val deadline = System.nanoTime() + 5.minutes.toNanos
    var pending  = ids
    while pending.nonEmpty && System.nanoTime() < deadline do
      pending = pending.filterNot(id => kit.flow(id).status == DepositFlow.Credited)
      if pending.nonEmpty then Thread.sleep(1000)
    val stuck = pending.map(id => (kit.flow(id), kit.paymentState(id).status))
    assert(stuck.isEmpty, s"${stuck.size} not credited: " + stuck.groupBy((f, p) => (f.status, p)).view.mapValues(_.size).toMap +
      stuck.take(3).map((f, p) => s"\n  $p ${f.status} attempts=${f.attempts} last=${f.lastAnswer}").mkString)
    val expired = ids.count(id => kit.paymentState(id).status == Status.Expired)
    assertEquals(expired, 0)
    val player = ids.map(id => kit.paymentState(id).opened.get.playerId)
    assert(player.forall(p => kit.wallet.depositCount("acme", p, "EUR") == 1), "each deposit credited exactly once")
  }

  test("a capture above 2^31 minor units credits exactly (research R11)") {
    val r  = kit.subBrand()
    val id = kit.payment(r, amount = 3_000_000_000L)
    assertEquals(kit.capture(id).status, 200)
    assertEquals(kit.wallet.balance("acme", kit.paymentState(id).opened.get.playerId, "EUR"), 3_000_000_000L)
  }
