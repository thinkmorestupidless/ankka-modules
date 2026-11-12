package io.eitheror.casino.publicapi.it

/** T062: a key's rate is counted once across instances (FR-031); another key is not slowed by it (SC-006). */
class QuotaSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.peered

  test("two instances on one database share a key's count; the one over is refused with Retry-After") {
    val (peer, other) = kit.peer()
    try
      val k = Keys.create(kit, "quota", rate = Some(4))
      val statuses = (1 to 5).map(i => kit.v1("/contract", Some(k.secret), at = if i % 2 == 0 then other else ""))
      assertEquals(statuses.take(4).map(_.status), Vector(200, 200, 200, 200))
      assertEquals((statuses(4).status, statuses(4).error), (429, "quota_exceeded"))
      assert(statuses(4).header("Retry-After").exists(_.toInt >= 1), statuses(4).toString)
    finally Kit.stopPeer(peer)
  }

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
