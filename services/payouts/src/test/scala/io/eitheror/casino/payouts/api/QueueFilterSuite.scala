package io.eitheror.casino.payouts.api

import java.time.Instant

/** The queue's query as a filter (contracts/routes.md): the status default, and `stale` on its own. */
class QueueFilterSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-10-31T12:00:00Z")
  private def filter(q: (String, String)*) = Operator.filter(q.toMap.get, now).toOption.get._1

  test("no status is pending review"):
    assertEquals(filter().status, Some("pending_review"))

  test("stale on its own is the approved requests whose payout was initiated over 24 h ago"):
    val f = filter("stale" -> "true")
    assertEquals((f.status, f.payoutStatus), (Some("approved"), Some("initiated")))
    assertEquals(f.staleBefore, Some(now.minusSeconds(24 * 3600)))

  test("an explicit status still wins over stale's default"):
    assertEquals(filter("stale" -> "true", "status" -> "ALL").status, None)
    assertEquals(filter("stale" -> "true", "status" -> "PENDING_REVIEW").status, Some("pending_review"))
