package io.eitheror.casino.publicapi.it

/**
 * T062: a key's rate is counted once across instances (FR-031). That another key is not slowed by it (SC-006) is
 * `perf.QuotaPerfSuite`'s: a latency comparison on a shared machine fails with load from outside the service.
 */
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
