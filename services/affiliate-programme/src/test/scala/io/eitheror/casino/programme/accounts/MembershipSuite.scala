package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

import java.time.Instant

class MembershipSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T10:00:00Z")
  private val app = Application(at, Some("v1"), "form")
  private def kit = EventSourcedTestKit.of(Membership, "aff-1/goldvibe")
  private def applied =
    val k = kit
    k.call(Membership.applyFor)(Apply("aff-1", "goldvibe", app)): Unit
    k
  private def active =
    val k = applied
    k.call(Membership.approve)(Approve("plan-1", "m-1", at)): Unit
    k

  test("apply makes a pending membership; applying while pending is refused") {
    val k = kit
    val s = k.call(Membership.applyFor)(Apply("aff-1", "goldvibe", app)).replyValue
    assertEquals((s.status, s.affiliate, s.brand, s.holds), (Some("pending"), "aff-1", "goldvibe", true))
    assert(k.call(Membership.applyFor)(Apply("aff-1", "goldvibe", app)).errorMessage.startsWith("already_applied"))
  }

  test("pending becomes active on a plan, or rejected; a rejected applicant may apply again") {
    assertEquals(active.call(Membership.get).replyValue.plan, Some("plan-1"))
    val k = applied
    assertEquals(k.call(Membership.reject)(Reject(Some("no traffic"), "m-1", at)).replyValue.status, Some("rejected"))
    assertEquals(k.call(Membership.applyFor)(Apply("aff-1", "goldvibe", app)).replyValue.status, Some("pending"))
  }

  test("active and suspended go both ways") {
    val k = active
    assertEquals(k.call(Membership.suspend)(Suspend("m-1", Membership.Operator, at)).replyValue.status, Some("suspended"))
    assertEquals(k.call(Membership.reactivate)(Reactivate("m-1", Membership.Operator, at)).replyValue.status, Some("active"))
  }

  test("every other transition is refused, naming the status") {
    val r = applied
    assertEquals(r.call(Membership.suspend)(Suspend("m-1", Membership.Operator, at)).errorMessage, "must be active, currently pending")
    assertEquals(r.call(Membership.reactivate)(Reactivate("m-1", Membership.Operator, at)).errorMessage, "must be suspended, currently pending")
    val rejected = applied
    rejected.call(Membership.reject)(Reject(None, "m-1", at)): Unit
    assertEquals(rejected.call(Membership.suspend)(Suspend("m-1", Membership.Operator, at)).errorMessage, "must be active, currently rejected")
    assertEquals(active.call(Membership.approve)(Approve("p", "m-1", at)).errorMessage, "must be pending, currently active")
    assertEquals(kit.call(Membership.reject)(Reject(None, "m-1", at)).errorMessage, "must be pending, currently none")
  }

  test("an operator's suspension stands over an ancestor's reactivation") {
    val k = active
    k.call(Membership.suspend)(Suspend("m-1", Membership.Operator, at)): Unit
    assert(k.call(Membership.reactivate)(Reactivate("aff-0", Membership.Ancestor, at)).errorMessage.startsWith("suspended_by_operator"))
    val a = active
    a.call(Membership.suspend)(Suspend("aff-0", Membership.Ancestor, at)): Unit
    assertEquals(a.call(Membership.reactivate)(Reactivate("aff-0", Membership.Ancestor, at)).replyValue.status, Some("active"))
  }

  test("an operator's suspension stands over an ancestor's reactivation when the ancestor suspended first, too") {
    val k = active
    k.call(Membership.suspend)(Suspend("aff-0", Membership.Ancestor, at)): Unit
    assertEquals(k.call(Membership.suspend)(Suspend("m-1", Membership.Operator, at)).replyValue.suspendedBy, Some(Membership.Operator))
    assert(k.call(Membership.reactivate)(Reactivate("aff-0", Membership.Ancestor, at)).errorMessage.startsWith("suspended_by_operator"))
    // An ancestor's second suspension is still refused, naming the status.
    assertEquals(k.call(Membership.suspend)(Suspend("aff-0", Membership.Ancestor, at)).errorMessage, "must be active, currently suspended")
  }

  test("an invitation's membership is active at once; one already held is refused") {
    val k = kit
    val s = k.call(Membership.activateInvited)(ActivateInvited("aff-1", "goldvibe", "plan-1", app.copy(source = "invite"), "m-1")).replyValue
    assertEquals((s.status, s.plan), (Some("active"), Some("plan-1")))
    assert(k.call(Membership.activateInvited)(ActivateInvited("aff-1", "goldvibe", "plan-1", app, "m-1")).isError)
  }

  test("the plan and the RevShare override are set; an override over 100% is refused") {
    val k = active
    assertEquals(k.call(Membership.setPlan)(SetPlan("plan-2", "m-1", at)).replyValue.plan, Some("plan-2"))
    assertEquals(k.call(Membership.setRevShareOverride)(SetRevShareOverride(Some(3_000), "m-1", at)).replyValue.revShareOverride, Some(3_000))
    assert(!k.call(Membership.setRevShareOverride)(SetRevShareOverride(Some(3_000), "m-1", at)).persisted)
    assertEquals(k.call(Membership.setRevShareOverride)(SetRevShareOverride(None, "m-1", at)).replyValue.revShareOverride, None)
    assert(k.call(Membership.setRevShareOverride)(SetRevShareOverride(Some(10_001), "m-1", at)).isError)
    assertEquals(kit.call(Membership.setPlan)(SetPlan("p", "m", at)).errorMessage, "not_a_member")
  }
