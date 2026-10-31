package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{CasinoWithdrawals, WithdrawalCompleted}
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.request.Status
import io.eitheror.casino.payouts.workflow.Flow

import java.util.concurrent.{CountDownLatch, Executors}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * SC-003: a payout initiated, a timeout, then `completed` arriving on the outcome route and on the status read
 * at once. Whichever lands first settles the request; the other moves nothing. One completed request, one
 * confirm at the wallet, one `withdrawal_completed`.
 */
class DoublePaySuite extends munit.FunSuite with LogCapturing:
  private lazy val kit   = SharedKit.kit
  private lazy val scene = Scene(kit)
  override def munitTimeout = 300.seconds
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    scene.terminals()

  private val pool             = Executors.newFixedThreadPool(4)
  private given ExecutionContext = ExecutionContext.fromExecutor(pool)
  override def afterAll(): Unit = pool.shutdown()

  /** The outcome, sent again while the workflow is busy (503), as the gateway retries. */
  private def deliver(id: String, ref: String): Reply =
    val r = scene.outcome(id, "completed", ref)
    if r.status == 503 then
      Thread.sleep(20)
      deliver(id, ref)
    else r

  test("completed on the outcome route and the status read at once settles once"):
    (1 to 5).foreach { round =>
      val id  = scene.request(scene.player())
      val ref = s"ref-$id"
      kit.gateway.initiateFor(id, FakeGateway.answer(id, "initiated", Some(ref)))
      assertEquals(scene.approve(id).status, 200)
      scene.at(id, Flow.Waiting)
      // The first poll is due; both answers become available together.
      val go = CountDownLatch(1)
      val viaRoute = Future { go.await(); deliver(id, ref) }
      val viaRead  = Future { go.await(); kit.gateway.statusFor(id, FakeGateway.answer(id, "completed", Some(ref))) }
      go.countDown()
      val answered = Await.result(viaRoute, 30.seconds)
      Await.result(viaRead, 30.seconds)
      assertEquals((answered.status, answered.str("status")), (200, "COMPLETED"), s"round $round: ${answered.text}")
      scene.at(id, Flow.Completed)
      // Long enough for a further poll, were the workflow still asking.
      Thread.sleep(2500)
      assertEquals(scene.state(id).status, Status.Completed)
      val confirms = kit.wallet.calls("/confirm").flatMap(_.json).filter(_.text("requestId").contains(id))
      assertEquals(confirms.size, 1, s"round $round")
      def completed = kit.facts(CasinoWithdrawals).collect { case f: WithdrawalCompleted if f.payoutId == id => f }
      kit.eventually(s"round $round: published")(Option.when(completed.nonEmpty)(()))
      Thread.sleep(500)
      assertEquals(completed.size, 1, s"round $round")
    }
