package io.eitheror.casino.ingest.it

import io.eitheror.casino.contract.facts.MerchantKeyIssued
import io.eitheror.casino.ingest.api.Context
import io.eitheror.casino.ingest.shape.Digests

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** T028 (US1; FR-004, SC-003, SC-004): the key index from merchant's facts, read on every create without asking merchant. */
class KeysSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 10.minutes
  private lazy val kit = Kit()

  override def afterAll(): Unit = kit.stop()

  /** The keys in the index, read from the store: the entity's table holds one row per digest. */
  private def indexed(): Int =
    Kit.query(kit.testKit.jdbcUrl, "SELECT count(*) FROM durable_state WHERE persistence_id LIKE 'key-index%'").head.toInt

  test("10,000 keys over 1,000 sub-brands: merchant asked for keys once, and the key step's p99 is under 5 ms (SC-003)") {
    val keys = (0 until 10_000).map(i => RawKey(s"mk_load_$i", s"k-load-$i", s"sb-load-${i % 1000}"))
    keys.foreach(k => kit.publish(MerchantKeyIssued(k.keyId, k.subBrand, Digests.ofKey(k.raw), kit.now())))
    val waited = System.nanoTime()
    kit.eventually("10,000 keys indexed", 300.seconds)(Option(indexed()).filter(_ >= 10_000)): Unit
    println(s"SC-003: 10,000 keys indexed ${(System.nanoTime() - waited) / 1_000_000} ms after the last was published")

    // SC-003 bounds the steady state: 200 creates first, untimed, so the JIT's first compilations stay out of the tail.
    val random = Random(7)
    (1 to 200).foreach(_ => assertEquals(kit.create(keys(random.nextInt(keys.size))).status, 200))
    val steps = ConcurrentLinkedQueue[Long]()
    Context.onKeyStep = steps.add(_): Unit
    try
      val creates = kit.merchant.received(FakeMerchant.Create).size
      (1 to 1000).foreach(_ => assertEquals(kit.create(keys(random.nextInt(keys.size))).status, 200))
      val sorted = steps.asScala.toVector.sorted
      val p99    = sorted((sorted.size * 0.99).toInt - 1) / 1_000_000.0
      println(f"SC-003: the key step over ${sorted.size} creates: p50 ${sorted(sorted.size / 2) / 1_000_000.0}%.2f ms, p99 $p99%.2f ms")
      assert(p99 < 5.0, f"p99 $p99%.2f ms")
      assertEquals(kit.merchant.received(FakeMerchant.Create).size, creates + 1000)
      assertEquals(kit.merchant.received(FakeMerchant.Keys).size, 1, "only the start refresh")
    finally Context.onKeyStep = _ => ()
  }

  test("a deactivated key that just served is refused within a minute (SC-004)") {
    val key = kit.issueKey()
    assertEquals(kit.create(key).status, 200)
    val started = System.nanoTime()
    kit.deactivateKey(key, await = false)
    kit.eventually("the key refused", 60.seconds)(Some(kit.create(key)).filter(_.status == 401)): Unit
    println(s"SC-004: refused ${(System.nanoTime() - started) / 1_000_000} ms after the deactivation was published")
  }

  test("a deactivation while a create under the key is in flight does not stop it; the next is refused") {
    val key = kit.issueKey()
    kit.merchant.delay = 300
    val inFlight = Future(kit.create(key))
    Thread.sleep(100)
    kit.deactivateKey(key)
    assertEquals(Await.result(inFlight, 10.seconds).status, 200)
    kit.merchant.delay = 0
    assertEquals(kit.create(key).status, 401)
  }

  test("an issue after a deactivation under the same key id leaves it refused") {
    val key = kit.issueKey()
    kit.deactivateKey(key)
    kit.issue(key, await = false)
    Thread.sleep(3000)
    assertEquals(kit.keyRow(key).map(_.active), Some(false))
    assertEquals(kit.create(key).status, 401)
  }

  test("merchant down at start: the service starts, nothing is refreshed, and the facts alone build the index") {
    kit.merchant.stop()
    try
      kit.restart()
      assertEquals(kit.refreshed, 0)
      val key = kit.issueKey()
      assertEquals(kit.create(Some(key), kit.body()).status, 503) // merchant is down: admitted, then not answered
    finally kit.merchant.start()
  }
