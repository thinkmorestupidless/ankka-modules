package io.eitheror.casino.programme.settlement

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.programme.commission.Claim

import java.time.Instant

class PayoutSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-02T10:00:00Z")
  private val f1  = Claim.Figures(cpa = 5_000, revShare = 2_000)
  private def kit = EventSourcedTestKit.of(Payout, "aff-1/goldvibe/EUR/2026-10")
  private def open(k: EventSourcedTestKit[Payout, PayoutState, Payout.Event], f: Claim.Figures = f1, when: Instant = at) =
    k.call(Payout.open)(OpenPayout("aff-1", "goldvibe", "EUR", "2026-10", f, when))
  private def opened =
    val k = kit
    open(k): Unit
    k

  test("open makes a pending payout with the close's figures; the same figures change nothing") {
    val k = kit
    val s = open(k).replyValue
    assertEquals((s.status, s.total, s.period), (Some("pending"), 7_000L, "2026-10"))
    assert(!open(k).persisted)
    assertEquals(open(k, f1.copy(adjustments = 100)).replyValue.total, 7_100L)
  }

  test("pending → approved → paid") {
    val k = opened
    assertEquals(k.call(Payout.approve)(ApprovePayout("m-1", at)).replyValue.status, Some("approved"))
    val s = k.call(Payout.pay)(PayPayout("fin-1", at.plusSeconds(60))).replyValue
    assertEquals((s.status, s.paidBy), (Some("paid"), Some("fin-1")))
  }

  test("a close that changes an approved payout reverts it to pending and records the superseded approval") {
    val k = opened
    k.call(Payout.approve)(ApprovePayout("m-1", at)): Unit
    assert(!open(k).persisted)
    val s = open(k, f1.copy(cpa = 5_700), at.plusSeconds(3600)).replyValue
    assertEquals((s.status, s.total, s.approvedBy), (Some("pending"), 7_700L, None))
    assertEquals(s.supersededApprovals, Vector(Superseded("m-1", at, 7_000L, at.plusSeconds(3600))))
  }

  test("a paid payout never changes, and every other transition is refused naming the status") {
    val k = opened
    k.call(Payout.approve)(ApprovePayout("m-1", at)): Unit
    k.call(Payout.pay)(PayPayout("fin-1", at)): Unit
    assertEquals(open(k, f1.copy(cpa = 1)).errorMessage, "must be pending or approved, currently paid")
    assertEquals(k.call(Payout.approve)(ApprovePayout("m-1", at)).errorMessage, "must be pending, currently paid")
    val r = opened
    r.call(Payout.reject)(RejectPayout("m-1", Some("fraud"), at)): Unit
    assertEquals(r.call(Payout.pay)(PayPayout("fin-1", at)).errorMessage, "must be approved, currently rejected")
    assertEquals(r.call(Payout.reject)(RejectPayout("m-1", None, at)).errorMessage, "must be pending, currently rejected")
    assertEquals(opened.call(Payout.pay)(PayPayout("fin-1", at)).errorMessage, "must be approved, currently pending")
    assertEquals(kit.call(Payout.approve)(ApprovePayout("m-1", at)).errorMessage, "must be pending, currently none")
  }
