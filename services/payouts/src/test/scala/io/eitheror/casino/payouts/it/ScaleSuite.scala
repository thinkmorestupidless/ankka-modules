package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.request.Status

import java.time.Duration as JDuration
import java.util.concurrent.Executors
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * SC-004 and FR-022: the brand's queue holds many requests and a few in flight; only those in flight wake and
 * are asked for their status, each by its own pause, with nothing reading the whole set; all of them reconcile;
 * and the brand's stale count is the requests initiated more than 24 hours ago and still waiting.
 *
 * 1,000 requests by default; 10,000 with `PAYOUTS_SCALE=1` (`just payouts-scale`). 200 in flight either way.
 * The database outlives a test, so the queue's totals are compared with what they were before.
 */
class ScaleSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit   = SharedKit.kit
  private lazy val scene = Scene(kit)
  private val size       = if sys.env.get("PAYOUTS_SCALE").contains("1") then 10000 else 1000
  private val inFlight   = 200
  private val stale      = 20
  override def munitTimeout = 30.minutes
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    scene.terminals()

  private val pool               = Executors.newFixedThreadPool(32)
  private given ExecutionContext = ExecutionContext.fromExecutor(pool)
  override def afterAll(): Unit  = pool.shutdown()

  private def all[A](xs: Iterable[A])(f: A => Unit): Unit =
    Await.result(Future.traverse(xs.toVector)(x => Future(f(x))), 20.minutes): Unit

  private def total(query: String): Long =
    val r = kit.request("GET", s"/v1/casino/brands/${kit.slug}/withdrawal-requests?$query&pageSize=1", None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json.long("total").get

  test(s"$size requests with $inFlight in flight: only those in flight are asked, and all of them reconcile"):
    val pendingBefore = total("status=PENDING_REVIEW")
    val staleBefore   = total("status=APPROVED&stale=true")
    val t0            = System.nanoTime()
    val ids = Await.result(Future.traverse((1 to size).toVector)(_ => Future(scene.request(scene.player("scale")))), 20.minutes)
    println(f"$size%,d requests taken in ${(System.nanoTime() - t0) / 1e9}%.1f s")
    kit.eventually("all queued", 60.seconds)(Option.when(total("status=PENDING_REVIEW") - pendingBefore == size.toLong)(()))
    val (flying, resting) = ids.splitAt(inFlight)
    flying.foreach(id => kit.gateway.initiateFor(id, FakeGateway.answer(id, "initiated", Some(s"ref-$id"))))

    // Some approved a day and an hour ago: initiated then, still waiting now.
    kit.advance(JDuration.ofHours(-25))
    // The approval answers before the payout step runs; the clock stays back until each is initiated.
    try flying.take(stale).foreach { id =>
      assertEquals(scene.approve(id).status, 200)
      scene.at(id, io.eitheror.casino.payouts.workflow.Flow.Waiting): Unit
    }
    finally kit.advance(JDuration.ofHours(25))
    all(flying.drop(stale))(id => assertEquals(scene.approve(id).status, 200))
    kit.eventually("in flight left the queue")(Option.when(total("status=PENDING_REVIEW") - pendingBefore == (size - inFlight).toLong)(()))
    kit.eventually("the stale count")(Option.when(total("status=APPROVED&stale=true") - staleBefore == stale.toLong)(()))

    // Each in-flight request is asked by its own pause; the resting ones are never touched.
    kit.eventually("all asked", 60.seconds)(Option.when(flying.forall(kit.gateway.statusReads(_).nonEmpty))(()))
    val restingSet = resting.toSet
    assert(!kit.gateway.payouts.flatMap(_.json).flatMap(_.text("requestId")).exists(restingSet))
    assert(!ids.exists(id => restingSet(id) && kit.gateway.statusReads(id).nonEmpty))
    val settled = (kit.wallet.calls("/confirm") ++ kit.wallet.calls("/release")).flatMap(_.json).flatMap(_.text("requestId"))
    assert(!settled.exists(restingSet))

    val t1 = System.nanoTime()
    flying.foreach(id => kit.gateway.statusFor(id, FakeGateway.answer(id, "completed", Some(s"ref-$id"))))
    kit.eventually("all reconciled", 120.seconds)(Option.when(flying.forall(scene.state(_).status == Status.Completed))(()))
    println(f"$inFlight%,d in flight reconciled in ${(System.nanoTime() - t1) / 1e9}%.1f s beside $size%,d requests")
    kit.eventually("the stale count falls")(Option.when(total("status=APPROVED&stale=true") == staleBefore)(()))
    assert(resting.take(50).forall(scene.state(_).status == Status.PendingReview))
