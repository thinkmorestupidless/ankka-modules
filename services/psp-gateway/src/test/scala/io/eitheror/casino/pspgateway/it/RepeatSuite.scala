package io.eitheror.casino.pspgateway.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** SC-002: the same success webhook five times at once, and again, is one settlement and one capture. */
class RepeatSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 10.minutes

  private lazy val kit = SharedKit.kit
  private given ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(5))

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))

  Provider.values.foreach { provider =>
    test(s"${provider.wire}: five concurrent deliveries of one success, then five more, make one Settled and one capture") {
      kit.psp.control(provider, "pending")
      val id = UUID.randomUUID().toString
      assertEquals(kit.initiate(id, provider).status, 200)
      kit.relay(id): Unit
      val ref = kit.stubReference(id, provider)
      def burst() =
        Await.result(Future.sequence(Vector.fill(5)(Future(kit.psp.webhook(provider, ref, "outcome" -> "success")._1))), 2.minutes)
      val first = burst()
      assert(first.forall(s => s / 100 == 2), first)
      kit.eventually("delivered")(Option(kit.merchant.deliveredTo(id)).filter(_.nonEmpty))
      burst(): Unit
      Thread.sleep(1000)
      assertEquals(kit.merchant.deliveredTo(id).map(_.key), Vector(s"$id/capture"))
      assert(kit.deposit(id).outcome.exists(_.isInstanceOf[Outcome.Captured]))
    }
  }
