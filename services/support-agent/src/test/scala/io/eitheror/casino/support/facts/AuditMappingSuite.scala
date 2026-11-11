package io.eitheror.casino.support.facts

import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}
import io.eitheror.casino.support.config.ConfigNote
import io.eitheror.casino.support.conversation.{ConversationEvent, EndReason, Outcome, Side}
import io.eitheror.casino.support.conversation.ConversationEvent.*

import java.time.Instant

/**
 * contracts/audit.md's mapping (T053; FR-028, SC-005): each conversation event to its `AuditRecord`, or none, and a
 * configuration version to its own; no record carries message text, `feedback` or a personal value.
 */
class AuditMappingSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-11T10:00:00Z")
  private def of(e: ConversationEvent, seq: Long = 7) = Audit.of("goldvibe", "s-1", seq, "p-1", e)

  private def fields(r: AuditRecord) = (r.action, r.actorKind, r.actorId, r.approverId, r.subjectKind, r.subjectId, r.outcome,
    r.reasonCode, r.recordClass)

  test("a tool's call is support.tool.<tool>, by the service, with its approval and the arguments as recorded"):
    val write = of(ToolCalled("c-1", "request_withdrawal", Map("currency" -> "GBP", "amount" -> "5000"), Outcome.Ok, Some("Withdrawal w-9"),
      Some("a-1"), at)).get
    assertEquals(fields(write),
      ("support.tool.request_withdrawal", "service", "support-agent", Some("a-1"), "player", "p-1", "ok", None, "system"))
    assertEquals(write.changes, Vector(AuditChange("amount", None, Some("5000")), AuditChange("currency", None, Some("GBP"))))
    assertEquals((write.recordId, write.correlationId, write.causationId, write.key), ("support/s-1/7", "s-1", "a-1", "player/p-1"))
    val refused = of(ToolCalled("c-2", "set_deposit_limit", Map.empty, Outcome.Refused("limit_too_high"), None, Some("a-2"), at)).get
    assertEquals((refused.outcome, refused.reasonCode), ("refused", Some("limit_too_high")))
    val read = of(ToolCalled("c-3", "get_wallet_balance", Map.empty, Outcome.Unavailable, None, None, at)).get
    assertEquals((read.outcome, read.approverId, read.causationId), ("unavailable", None, "support/s-1/7"))

  test("an approval request is awaiting, naming the tool and when it expires"):
    val r = of(ApprovalRequested("a-1", "apply_cool_off", Map("period" -> "24h"), "A 24 hour break", at.plusSeconds(900), at)).get
    assertEquals(fields(r), ("support.approval.requested", "service", "support-agent", None, "player", "p-1", "awaiting", None, "system"))
    assertEquals(r.changes, Vector(AuditChange("tool", None, Some("apply_cool_off")), AuditChange("expiresAt", None, Some("2026-11-11T10:15:00Z"))))
    assert(!r.changes.exists(_.after.exists(_.contains("break"))), "the summary is not recorded")

  test("a decision is the player's, or ankka's when it expired"):
    val approved = of(Decided("a-1", "apply_cool_off", approved = true, "player:p-1", expired = false, at)).get
    assertEquals((approved.actorKind, approved.actorId, approved.outcome), ("player", "p-1", "approved"))
    assertEquals(of(Decided("a-1", "apply_cool_off", approved = false, "player:p-1", expired = false, at)).get.outcome, "refused")
    val expired = of(Decided("a-1", "apply_cool_off", approved = false, "ankka", expired = true, at)).get
    assertEquals((expired.actorKind, expired.actorId, expired.outcome, expired.causationId), ("service", "ankka", "expired", "a-1"))

  test("a guardrail's rejection is a security record naming the guardrail, the side and whether it was retried"):
    val r = of(GuardrailRejected("other-player", Side.Output, retried = true, at)).get
    assertEquals(fields(r), ("support.guardrail.rejected", "service", "support-agent", None, "player", "p-1", "refused", Some("other-player"), "security"))
    assertEquals(r.changes, Vector(AuditChange("side", None, Some("output")), AuditChange("retried", None, Some("true"))))

  test("a hand-off is support.handoff; a start, a turn, an end and forgetting make no record"):
    assertEquals(of(HandedOff(at)).map(_.action), Some("support.handoff"))
    Seq(Started("p-1", 1, at), TurnTaken(at, 0, 10), Ended(EndReason.SelfExcluded, at), Forgotten(at))
      .foreach(e => assertEquals(of(e), None, e.toString))

  test("a configuration version is the operator's, with values only for the fields that may carry them"):
    val note = ConfigNote(3, "op-1", Vector("prompt", "turnsPerMinute"), Map("turnsPerMinute" -> "20"), at)
    val r    = ConfigRecords.of("goldvibe", "goldvibe", note)
    assertEquals((r.recordId, r.action, r.actorKind, r.actorId, r.subjectKind, r.subjectId, r.outcome, r.recordClass),
      ("support-config/goldvibe/3", "support.config.changed", "operator", "op-1", "brand", "goldvibe", "ok", "operator"))
    assertEquals(r.changes, Vector(AuditChange("prompt", None, None), AuditChange("turnsPerMinute", None, Some("20"))))
    assertEquals(ConfigRecords.of("goldvibe", "goldvibe", note.copy(fields = Vector("personalFields"), values = Map("personalFields" -> "email")))
      .recordClass, "security")

  test("no record carries a value other than the ids, the recorded arguments, the tool, the times and the codes"):
    val events = Seq(
      ToolCalled("c-1", "apply_cool_off", Map("period" -> "24h", "note" -> "<12 chars>"), Outcome.Ok, Some("Break until Friday, Ann"), Some("a-1"), at),
      ApprovalRequested("a-1", "apply_cool_off", Map("period" -> "24h"), "Ann asked for a break", at, at),
      Decided("a-1", "apply_cool_off", approved = false, "player:p-1", expired = false, at),
      GuardrailRejected("softening", Side.Output, retried = false, at), HandedOff(at))
    val text = events.flatMap(of(_)).flatMap(r => r.changes.flatMap(c => Seq(c.field) ++ c.after) ++ r.reasonCode).mkString(" ")
    assert(!text.contains("Ann") && !text.contains("Friday"), text)
