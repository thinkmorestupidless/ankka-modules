package io.eitheror.casino.programme.accounts


import java.time.Instant

class MembershipsViewSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-01T10:00:00Z")

  test("a row follows the membership's transitions") {
    val events = Vector(Membership.Applied("aff-1", "goldvibe", Application(at, None, "form")), Membership.Approved("plan-1", "m", at),
      Membership.MembershipSuspended("m", Membership.Operator, at))
    val row = events.foldLeft(MembershipState())(Membership.next)
    assertEquals((row.affiliate, row.brand, row.status, row.plan, row.suspendedBy), ("aff-1", "goldvibe", Some("suspended"), Some("plan-1"), Some("operator")))
  }
