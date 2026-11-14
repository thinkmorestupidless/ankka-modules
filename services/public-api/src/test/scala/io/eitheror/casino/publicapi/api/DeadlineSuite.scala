package io.eitheror.casino.publicapi.api

import io.eitheror.casino.publicapi.contract.Refusals

import java.time.Duration

class DeadlineSuite extends munit.FunSuite:
  private val deadline = Deadline(Duration.ofMillis(200))

  test("work inside the budget is answered as it answers") {
    assertEquals(deadline.run(Refusals.notFound.respond).status, 404)
  }

  test("work that sleeps past the budget is 503 unavailable, without waiting for it") {
    val started = System.nanoTime()
    val r       = deadline.run { Thread.sleep(2000); Refusals.notFound.respond }
    assertEquals(r.status, 503)
    assert((System.nanoTime() - started) / 1_000_000 < 1500)
    assert(String(r.body.body, "UTF-8").contains("\"unavailable\""))
  }

  test("work that fails, as a view read that throws, is 503 unavailable") {
    assertEquals(deadline.run(throw IllegalStateException("view")).status, 503)
  }

  test("a refusal raised inside the work is answered as itself") {
    assertEquals(deadline.run(Deadline.refuse(Refusals.snapshotExpired)).status, 410)
  }

  test("work that outlives the budget is interrupted, so its thread is free again") {
    val interrupted = java.util.concurrent.CountDownLatch(1)
    val r = deadline.run {
      try { Thread.sleep(10_000); Refusals.notFound.respond }
      catch case e: InterruptedException => { interrupted.countDown(); throw e }
    }
    assertEquals(r.status, 503)
    assert(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS), "the work was not interrupted")
  }

  test("with every thread busy and the queue full, a request is 503 at once rather than another thread") {
    val small   = Deadline(Duration.ofSeconds(5), workers = 1, waiting = 1)
    val release = java.util.concurrent.CountDownLatch(1)
    val started = java.util.concurrent.CountDownLatch(1)
    def blocked = { started.countDown(); release.await(); Refusals.notFound.respond }
    val busy = (1 to 2).map(_ => Thread(() => small.run(blocked): Unit))
    busy.foreach(_.start())
    try
      assert(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
      Thread.sleep(100) // the second is queued behind the first
      val at = System.nanoTime()
      assertEquals(small.run(Refusals.notFound.respond).status, 503)
      assert((System.nanoTime() - at) / 1_000_000 < 1000)
    finally
      release.countDown()
      busy.foreach(_.join())
  }
