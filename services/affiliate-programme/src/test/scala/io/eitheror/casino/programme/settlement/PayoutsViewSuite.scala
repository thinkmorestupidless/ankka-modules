package io.eitheror.casino.programme.settlement

import io.eitheror.casino.programme.commission.Claim

import java.time.Instant

class PayoutsViewSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-02T10:00:00Z")

  test("a row follows the payout to approved") {
    val events = Vector(Payout.Opened("aff-1", "goldvibe", "EUR", "2026-10", Claim.Figures(cpa = 5_000), at), Payout.PayoutApproved("m", at))
    val row    = events.foldLeft(PayoutState())(Payout.next)
    assertEquals((row.status, row.total, row.period, row.approvedBy), (Some("approved"), 5_000L, "2026-10", Some("m")))
  }
