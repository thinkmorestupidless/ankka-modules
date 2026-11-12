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
