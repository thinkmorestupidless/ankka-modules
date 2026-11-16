package io.eitheror.casino.payouts.api

import java.time.Instant

/** The queue's query as a filter (contracts/routes.md): the status default, and `stale` on its own. */
class QueueFilterSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-10-31T12:00:00Z")
  private def filter(q: (String, String)*) = Operator.filter(q.toMap.get, now).toOption.get._1
  private def oldest(q: (String, String)*)  = Operator.filter(q.toMap.get, now).map(_._4)

  test("no status is pending review"):
    assertEquals(filter().status, Some("pending_review"))

  test("stale on its own is the approved requests whose payout was initiated over 24 h ago"):
    val f = filter("stale" -> "true")
    assertEquals((f.status, f.payoutStatus), (Some("approved"), Some("initiated")))
    assertEquals(f.staleBefore, Some(now.minusSeconds(24 * 3600)))

  test("an explicit status still wins over stale's default"):
    assertEquals(filter("stale" -> "true", "status" -> "ALL").status, None)
    assertEquals(filter("stale" -> "true", "status" -> "PENDING_REVIEW").status, Some("pending_review"))

  test("newest first unless sort=oldest; another sort is refused, naming it (spec 030 Y2)"):
    assertEquals(oldest(), Right(false))
    assertEquals(oldest("sort" -> "newest"), Right(false))
    assertEquals(oldest("sort" -> "oldest"), Right(true))
    assertEquals(oldest("sort" -> "OLDEST"), Right(true))
    assertEquals(oldest("sort" -> "sideways"), Left("sort"))

  test("a page and its size: from 1, and 1 to 200 (spec 030 Y4)"):
    def paging(q: (String, String)*) = Operator.paging(q.toMap.get)
    assertEquals(paging(), Right((1, 50)))
    assertEquals(paging("page" -> "3", "pageSize" -> "200"), Right((3, 200)))
    assertEquals(paging("page" -> "0"), Left("page"))
    assertEquals(paging("pageSize" -> "201"), Left("pageSize"))
    assertEquals(paging("page" -> "one"), Left("page"))
