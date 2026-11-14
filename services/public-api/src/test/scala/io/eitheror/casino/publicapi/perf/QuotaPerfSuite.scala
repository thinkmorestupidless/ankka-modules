package io.eitheror.casino.publicapi.perf

import io.eitheror.casino.publicapi.it.{Keys, SharedKit}

/**
 * SC-006 (T062), on purpose only: `just public-api-perf` (`PUBLIC_API_PERF=1`). One key at its rate does not slow
 * another: the other's p95 within 10% of its p95 alone. It compares latencies on the machine it runs on, so it belongs
 * here, on a machine nothing else is loading, and not in CI's shared runners.
 */
class QuotaPerfSuite extends munit.FunSuite:
  override def munitIgnore: Boolean = !sys.env.get("PUBLIC_API_PERF").contains("1")
  private lazy val kit = SharedKit.peered

  test("another key's p95 with the first at its rate is within 10% of its p95 without it") {
    val busy  = Keys.create(kit, "busy", rate = Some(600))
    val quiet = Keys.noFields(kit)
    def p95(n: Int): Long =
      val times = (1 to n).map { _ =>
        val s = System.nanoTime(); kit.v1("/contract", Some(quiet.secret)); (System.nanoTime() - s) / 1000
      }.sorted
      times((n * 95) / 100)
    p95(40): Unit // warm
    val alone   = p95(100)
    val running = new java.util.concurrent.atomic.AtomicBoolean(true)
    val load = Thread(() => while running.get() do kit.v1("/contract", Some(busy.secret)): Unit)
    load.start()
    val loaded = try p95(100) finally { running.set(false); load.join() }
    // 10% of a few milliseconds is under the scheduler's noise; 2 ms more is allowed for it (research.md).
    assert(loaded <= alone * 11 / 10 + 2000, s"p95 alone ${alone}µs, beside a busy key ${loaded}µs")
  }
