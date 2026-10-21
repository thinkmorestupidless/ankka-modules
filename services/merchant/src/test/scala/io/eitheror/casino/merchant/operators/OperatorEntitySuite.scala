package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, KeyValueEntityTestKit}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.merchant.hierarchy.Actor

import java.time.Instant
import scala.concurrent.duration.*

/** FR-027, FR-028 at the entities, with the original's `OperatorInvitationTokenStoreSpec` rules. */
class OperatorEntitySuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-12T10:00:00Z")
  private val op = Actor.operator("op-1")

  private def created =
    val kit = EventSourcedTestKit.of(Operator, "u-1")
    kit.call(Operator.create)(CreateOperator(Personal("ops@acme.example"), "resource_admin", "company", "c-1", Vector("h-1"), None, None, op, at)): Unit
    kit

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
