package io.eitheror.casino.publicapi.games

import java.time.{Duration, Instant}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

/** T070, a port of the original's `ttlCache.test.ts`. */
class CatalogueCacheSuite extends munit.FunSuite:
  private val start = Instant.parse("2026-11-12T08:00:00Z")
  private def cache(now: AtomicReference[Instant]) = TtlCache[Int](Duration.ofSeconds(1), () => now.get)

  private def eventually(f: => Boolean): Unit =
    val until = System.nanoTime() + 5_000_000_000L
    while !f && System.nanoTime() < until do Thread.sleep(10)
    assert(f)

  test("the first load blocks concurrent callers on one load") {
    val c     = cache(AtomicReference(start))
    val loads = AtomicInteger()
    val gate  = CountDownLatch(1)
    val load  = () => { loads.incrementAndGet(); gate.await(5, TimeUnit.SECONDS); 1 }
    val both  = Future.sequence(Vector(Future(c.get(load, 5.seconds)), Future(c.get(load, 5.seconds))))
    Thread.sleep(100); gate.countDown()
    assertEquals(Await.result(both, 5.seconds), Vector(1, 1))
    assertEquals(loads.get, 1)
  }

  test("a failed first load fails its caller and is not cached") {
    val c = cache(AtomicReference(start))
    intercept[IllegalStateException](c.get(() => throw IllegalStateException("down"), 5.seconds))
    assertEquals(c.get(() => 2, 5.seconds), 2)
  }

  test("a stale value is served without waiting for the reload; the reload's value is served after") {
    val now = AtomicReference(start)
    val c   = cache(now)
    c.get(() => 1, 5.seconds): Unit
    now.set(start.plusMillis(1500))
    val gate = CountDownLatch(1)
    assertEquals(c.get(() => { gate.await(5, TimeUnit.SECONDS); 2 }, 5.seconds), 1)
    gate.countDown()
    eventually(c.get(() => 3, 5.seconds) == 2)
  }

  test("concurrent stale reads share one reload") {
    val now = AtomicReference(start)
    val c   = cache(now)
    c.get(() => 1, 5.seconds): Unit
    now.set(start.plusMillis(1500))
    val loads = AtomicInteger(); val gate = CountDownLatch(1)
    val load  = () => { loads.incrementAndGet(); gate.await(5, TimeUnit.SECONDS); 2 }
    assertEquals(Vector.fill(3)(c.get(load, 5.seconds)), Vector(1, 1, 1))
    gate.countDown()
    assertEquals(loads.get, 1)
  }

  test("a failed reload keeps the old value and is tried again by a later read") {
    val now = AtomicReference(start)
    val c   = cache(now)
    c.get(() => 1, 5.seconds): Unit
    now.set(start.plusMillis(1500))
    assertEquals(c.get(() => throw IllegalStateException("down"), 5.seconds), 1)
    Thread.sleep(100)
    assertEquals(c.get(() => 2, 5.seconds), 1)
    eventually(c.get(() => 9, 5.seconds) == 2)
  }
