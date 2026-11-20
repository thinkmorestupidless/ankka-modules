package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.merchant.hierarchy.Actor

import java.time.Instant
import scala.concurrent.duration.*

/** FR-027, FR-028 at the entities, with the original's `OperatorInvitationTokenStoreSpec` rules. */
class OperatorEntitySuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-10-12T10:00:00Z")
  private val op = Actor.operator("op-1")

  private def created =
    val kit = EventSourcedTestKit.of(Operator, "u-1")
    kit.call(Operator.create)(CreateOperator(Personal("ops@acme.example"), "resource_admin", "company", "c-1", Vector("h-1"), None, None, op, at)): Unit
    kit

  test("a refused giving is recorded on an operator that exists, bounded, and on no other (FR-014)") {
    val kit = created
    val many = (1 to 200).map(i => f"role_$i%03d").toSet + ("a" * 500)
    kit.call(Operator.rejectBrandRoles)(RejectBrandRoles("goldvibe", "spinvibe", many, "FORBIDDEN", at)): Unit
    val rejected = kit.allEvents.collect { case r: OperatorEvent.BrandRolesRejected => r }
    assertEquals(rejected.map(r => (r.brand, r.caller, r.code)), Vector(("goldvibe", "spinvibe", "FORBIDDEN")))
    assertEquals(rejected.head.roles.size, 64)
    assert(rejected.head.roles.forall(_.length <= 128) && rejected.head.roles("a" * 128), rejected.head.roles.toString)
    val none = EventSourcedTestKit.of(Operator, "u-unknown")
    none.call(Operator.rejectBrandRoles)(RejectBrandRoles("goldvibe", "goldvibe", Set("brand_admin"), "RESERVED_ROLE", at)): Unit
    assertEquals(none.allEvents, Vector.empty)
  }

  test("an operator is created pending with its role, level and ancestors, and invited") {
    val kit = created
    val s   = kit.currentState
    assertEquals((s.role, s.scopeLevel, s.scopeId, s.ancestors, s.pending, s.email.map(_.reveal)),
      ("resource_admin", "company", "c-1", Vector("h-1"), true, Some("ops@acme.example")))
    assertEquals(kit.allEvents.size, 2)
  }

  test("accepting makes it active once; a mirrored enabled flag records only a change; removal is final") {
    val kit = created
    kit.call(Operator.accept)(at): Unit
    assert(!kit.currentState.pending)
    assert(!kit.call(Operator.accept)(at).persisted)
    assert(!kit.call(Operator.mirrorEnabled)(MirrorEnabled(true, at)).persisted)
    assert(kit.call(Operator.mirrorEnabled)(MirrorEnabled(false, at)).persisted)
    kit.call(Operator.remove)(OperatorChange(op, at)): Unit
    assertEquals(kit.call(Operator.accept)(at).error.code, com.thinkmorestupidless.ankka.core.ErrorCode.NotFound)
  }

  test("an invitation token is used once, and not after its 72 hours") {
    val kit = KeyValueEntityTestKit.of(InvitationToken, "digest-1")
    val issued = kit.call(InvitationToken.issue)(IssueInvitation("u-1", Personal("ops@acme.example"), at))
    assert(issued.retention.isDefined, "the token expires")
    assertEquals(kit.call(InvitationToken.consume)(at.plusSeconds(60)).replyValue, Some("u-1"))
    assertEquals(kit.call(InvitationToken.consume)(at.plusSeconds(61)).replyValue, None, "a used token is refused")
    val late = KeyValueEntityTestKit.of(InvitationToken, "digest-2")
    late.call(InvitationToken.issue)(IssueInvitation("u-2", Personal("x@acme.example"), at)): Unit
    assertEquals(late.call(InvitationToken.consume)(at.plusSeconds(72 * 3600)).replyValue, None, "an expired token is refused")
    assertEquals(KeyValueEntityTestKit.of(InvitationToken, "nope").call(InvitationToken.consume)(at).replyValue, None)
  }

  // spec 032: a brand's giving, asked, then in force or refused
  test("a giving is asked as the brand's next version, pending, and in force once applied") {
    val kit = EventSourcedTestKit.of(Operator, "u-9")
    val g1  = kit.call(Operator.askBrandRoles)(AskBrandRoles("goldvibe", Set("brand_finance"), at)).replyValue
    assertEquals((g1.version, g1.state, g1.roles), (1L, GivingState.Pending, Set("brand_finance")))
    assert(kit.call(Operator.brandRolesApplied)(BrandRolesDone("goldvibe", 1, at)).persisted)
    assertEquals(kit.currentState.givings("goldvibe").state, GivingState.InForce)
    assertEquals(kit.currentState.brandRoles("goldvibe"), Set("brand_finance@goldvibe", "brand_goldvibe"))
    assert(!kit.call(Operator.brandRolesApplied)(BrandRolesDone("goldvibe", 1, at)).persisted, "a second report records nothing")
    val g2 = kit.call(Operator.askBrandRoles)(AskBrandRoles("goldvibe", Set("brand_finance"), at)).replyValue
    assertEquals((g2.version, g2.state), (2L, GivingState.Pending), "the same roles again are a new giving")
  }

  test("a stale outcome changes nothing: the later giving stays pending") {
    val kit = EventSourcedTestKit.of(Operator, "u-10")
    kit.call(Operator.askBrandRoles)(AskBrandRoles("goldvibe", Set("brand_support"), at)): Unit
    kit.call(Operator.askBrandRoles)(AskBrandRoles("goldvibe", Set("brand_admin"), at)): Unit
    assert(!kit.call(Operator.brandRolesApplied)(BrandRolesDone("goldvibe", 1, at)).persisted)
    assert(!kit.call(Operator.brandRolesFailed)(BrandRolesRefused("goldvibe", 1, "403", at)).persisted)
    assertEquals(kit.currentState.givings("goldvibe"), Giving(Set("brand_admin"), 2, GivingState.Pending, at))
  }

  test("a refused giving ends failed with the realm's reason; another brand's giving is its own") {
    val kit = EventSourcedTestKit.of(Operator, "u-11")
    kit.call(Operator.askBrandRoles)(AskBrandRoles("goldvibe", Set("brand_admin"), at)): Unit
    kit.call(Operator.askBrandRoles)(AskBrandRoles("spinvibe", Set("brand_support"), at)): Unit
    assert(kit.call(Operator.brandRolesFailed)(BrandRolesRefused("goldvibe", 1, "role: 403 forbidden", at)).persisted)
    assert(!kit.call(Operator.brandRolesApplied)(BrandRolesDone("goldvibe", 1, at)).persisted, "a failed giving is not put in force")
    assertEquals(kit.currentState.givings("goldvibe").state, GivingState.Failed("role: 403 forbidden"))
    assertEquals(kit.currentState.givings("spinvibe").state, GivingState.Pending)
    assertEquals(kit.call(Operator.askBrandRoles)(AskBrandRoles("spinvibe", Set.empty, at)).replyValue.version, 2L)
  }
