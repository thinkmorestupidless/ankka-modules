package io.eitheror.casino.provider.core

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.TimerRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import io.eitheror.casino.provider.st8.St8Protocol

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `Deliver` over a real runtime and a throwaway Postgres (Docker), with the brand scripted: each
 * callback's answers are a queue of `ok`, `error`, a refusal word, or silence (no answer within the
 * wait). The schedule is short so the suite runs in seconds; its shape is the provider's.
 */
class DeliverSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: Duration = 3.minutes

  /** What the scripted brand answers, per callback route and transaction id, in order. */
  private val scripts = ConcurrentHashMap[String, java.util.concurrent.ConcurrentLinkedQueue[String]]()
  private val bodies  = ConcurrentHashMap[String, java.util.concurrent.CopyOnWriteArrayList[String]]()

  private object Scripted extends Outbound:
    def send(brand: String, route: String, body: Array[Byte], key: String, answerWait: FiniteDuration, attempt: Int): Attempt =
      val text = String(body, "UTF-8")
      val tx   = """"transaction_id":"([^"]+)"""".r.findFirstMatchIn(text).map(_.group(1)).getOrElse("")
      bodies.computeIfAbsent(tx, _ => java.util.concurrent.CopyOnWriteArrayList()).add(text): Unit
      val next = Option(scripts.get(tx)).flatMap(q => Option(q.poll())).getOrElse("ok")
      if next == "silence" then Attempt(attempt, "k1", "sig", Wiring.now(), answerWait.toMillis, None, None, None, None, None, Some("no answer"))
      else
        val answer = s"""{"status":"$next","balance":"10.00","currency":"BRL"}"""
        Attempt(attempt, "k1", "sig", Wiring.now(), 1L, Some(200), Some(next), Some("10.00"), Some("BRL"), Some(answer), None)

  private object Keys extends KeyRing:
    def currentKeyId = "k1"
    def nextKeyId    = None
    def publishNext() = "k2"
    def promote()     = "k1"
    def withdrawOld() = ()

  private object OneBrand extends Brands:
    def known(slug: String)           = slug == "goldvibe"
    def testBrand(slug: String)       = true
    def answerWait(slug: String)      = 100.millis
    def keyRefreshBound(slug: String) = 1.second
    def slugs                         = Vector("goldvibe")

  private lazy val kit: AnkkaTestKit =
    Wiring.install(St8Protocol, Scripted, Keys, OneBrand)
    Deliver.schedule = Schedule(100.millis, Vector(Schedule.Interval(200.millis, None)), 2.seconds, St8Protocol.defaultRetryOn)
    AnkkaTestKit.start(Seq(SessionEntity.descriptor, CallbackEntity.descriptor, Deliver.descriptor), Seq(TimerRuntime(100.millis)))

  override def afterAll(): Unit = kit.stop()

  private val counter = AtomicInteger()

  /** A launched session with one debit planned (its round pending) and not yet sent. */
  private def planned(answers: String*): (String, String, String) =
    val n       = counter.incrementAndGet()
    val token   = s"L$n"
    val tx      = s"t$n"
    val client  = kit.componentClient
    client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.launch)
      .invoke(SessionEntity.Launch("goldvibe", "p1", "BRL", "test-slot", "default", token, 0L, Wiring.now())): Unit
    client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.check).invoke(s"s-$token"): Unit
    scripts.put(tx, java.util.concurrent.ConcurrentLinkedQueue(answers.asJava))
    val session = client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.get).invoke()
    val call    = St8Protocol.stake(session.walletToken, "r1", "BRL", "test-slot", tx, "1.00")
    val id      = s"cb-$n"
    Play(client).plan(session, call, "stub", None, id, awaitingAnswer = true): Unit
    (token, id, tx)

  private def start(token: String, id: String, made: Int = 0): Unit =
    kit.componentClient.forWorkflow(EntityId(id)).call(Deliver.start)
      .invoke(Deliver.Start(id, token, Wiring.now(), made, Some("r1"))): Unit

  private def callback(id: String): Callback = kit.componentClient.forEventSourcedEntity(EntityId(id)).call(CallbackEntity.get).invoke()
  private def session(token: String): Session = kit.componentClient.forEventSourcedEntity(EntityId(token)).call(SessionEntity.get).invoke()

  private def eventually[A](within: FiniteDuration = 30.seconds)(probe: => Option[A]): A =
    val deadline = System.nanoTime() + within.toNanos
    var found    = probe
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
      found = probe
    found.getOrElse(throw AssertionError(s"nothing within $within"))

  private def ended(id: String): Callback = eventually()(Option(callback(id)).filter(c => Callback.Final.contains(c.outcome)))

  test("a brand that accepts at once is acknowledged after one attempt, and the round opens") {
    val (token, id, _) = planned("ok")
    start(token, id)
    assertEquals(ended(id).outcome, Callback.Acknowledged)
    assertEquals(callback(id).attempts.size, 1)
    assertEquals(session(token).rounds("r1").state, Round.Open)
  }

  test("a brand that answers error twice is sent the same body again until it accepts") {
    val (token, id, tx) = planned("error", "error", "ok")
    start(token, id)
    assertEquals(ended(id).outcome, Callback.Acknowledged)
    assertEquals(callback(id).attempts.map(_.attempt), Vector(1, 2, 3))
    assertEquals(bodies.get(tx).asScala.toVector.distinct.size, 1)
  }

  test("a brand that answered late is waited for, then sent again, and its acceptance opens the round") {
    val (token, id, _) = planned("ok")
    start(token, id, made = 1)
    assertEquals(ended(id).outcome, Callback.Acknowledged)
    assertEquals(session(token).rounds("r1").state, Round.Open)
  }

  test("a brand that refuses for good is not sent again, and the round goes away") {
    val (token, id, _) = planned("not_enough_money")
    start(token, id)
    assertEquals(ended(id).outcome, Callback.Refused)
    assertEquals(callback(id).attempts.size, 1)
    assertEquals(eventually()(Option(session(token)).filter(_.round("r1").isEmpty)).round("r1"), None)
  }

  test("a brand that never answers is sent again until the cap, then the callback is exhausted and its round stays pending") {
    val (token, id, _) = planned(Vector.fill(50)("silence")*)
    start(token, id)
    val c = ended(id)
    assertEquals(c.outcome, Callback.Exhausted)
    assert(c.attempts.size > 2, s"attempts: ${c.attempts.size}")
    assertEquals(session(token).rounds("r1").state, Round.Pending)
  }

  test("a delivery started twice delivers once") {
    val (token, id, _) = planned("ok")
    start(token, id)
    start(token, id)
    assertEquals(ended(id).outcome, Callback.Acknowledged)
    assertEquals(callback(id).attempts.size, 1)
  }

  test("an operator abandons a delivery that is waiting; nothing more is sent") {
    val (token, id, tx) = planned(Vector.fill(50)("error")*)
    start(token, id)
    eventually()(Option(callback(id)).filter(_.nextRetryAt.isDefined))
    kit.componentClient.forWorkflow(EntityId(id)).call(Deliver.abandon).invoke(Abandonment("operator", "written off", Wiring.now())): Unit
    val c = ended(id)
    assertEquals(c.outcome, Callback.Abandoned)
    val sent = bodies.get(tx).size
    Thread.sleep(800)
    assertEquals(bodies.get(tx).size, sent)
  }

  test("abandoning a delivery that ended is refused") {
    val (token, id, _) = planned("ok")
    start(token, id)
    ended(id)
    eventually()(scala.util.Try(kit.componentClient.forWorkflow(EntityId(id)).call(Deliver.get).invoke()).toOption.filter(_.state == DeliverState.Done))
    intercept[Exception](kit.componentClient.forWorkflow(EntityId(id)).call(Deliver.abandon).invoke(Abandonment("op", "late", 1L)))
  }
