package io.eitheror.casino.support.facts

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}
import io.eitheror.casino.support.SupportRuntime
import io.eitheror.casino.support.conversation.{Conversation, ConversationEvent, Outcome}
import io.eitheror.casino.support.conversation.ConversationEvent.*

/**
 * contracts/audit.md's records, pure: one per tool call, approval request, decision, guardrail rejection and hand-off
 * of a conversation. The player is named by id only; a tool's arguments are those `Redaction` kept when the call was
 * recorded; no message text, model output or `feedback` is anywhere in a conversation's events, so none is here.
 */
object Audit:
  val Service = "support-agent"

  def of(brand: String, session: String, seq: Long, playerId: String, event: ConversationEvent): Option[AuditRecord] =
    val id = s"support/$session/$seq"
    def record(action: String, outcome: String, actor: (String, String) = ("service", Service), approver: Option[String] = None,
        reason: Option[String] = None, changes: Vector[AuditChange] = Vector.empty, causation: Option[String] = None,
        recordClass: String = "system") =
      AuditRecord(recordId = id, brand = brand, service = Service, action = action, actorKind = actor._1, actorId = actor._2,
        approverId = approver, subjectKind = "player", subjectId = playerId, outcome = outcome, reasonCode = reason,
        changes = changes, correlationId = session, causationId = causation.getOrElse(id), recordClass = recordClass,
        occurredAt = event.at)
    def change(field: String, value: String) = AuditChange(field, None, Some(value))
    event match
      case e: ToolCalled =>
        val reason = e.outcome match
          case Outcome.Refused(code) => Some(code)
          case _                     => None
        Some(record(s"support.tool.${e.tool}", e.outcome.wire, approver = e.approvalId, reason = reason,
          changes = e.arguments.toVector.sortBy(_._1).map(change), causation = e.approvalId))
      case e: ApprovalRequested =>
        Some(record("support.approval.requested", "awaiting",
          changes = Vector(change("tool", e.tool), change("expiresAt", e.expiresAt.toString)), causation = Some(e.approvalId)))
      case e: Decided =>
        val actor   = if e.expired then ("service", "ankka") else ("player", playerId)
        val outcome = if e.expired then "expired" else if e.approved then "approved" else "refused"
        Some(record("support.approval.decided", outcome, actor = actor,
          changes = Vector(change("tool", e.tool), change("approvalId", e.approvalId)), causation = Some(e.approvalId)))
      case e: GuardrailRejected =>
        Some(record("support.guardrail.rejected", "refused", reason = Some(e.guardrail),
          changes = Vector(change("side", e.side.toString.toLowerCase), change("retried", e.retried.toString)), recordClass = "security"))
      case _: HandedOff => Some(record("support.handoff", "ok"))
      case _: Started | _: TurnTaken | _: Ended | _: Forgotten => None

/** Each conversation event's record on `platform.audit`, the player read from the conversation it belongs to. */
final class AuditPublisher(context: ConsumerContext) extends AuditConsumer[ConversationEvent]:
  protected def audit(change: ConversationEvent): Option[AuditRecord] =
    val session = messageContext.subject
    val player = change match
      case s: Started => s.playerId
      case _ => context.componentClient.forEventSourcedEntity(EntityId(session)).call(Conversation.get).invoke().playerId
    Audit.of(SupportRuntime.settings.brand, session, messageContext.sequenceNumber, player, change)

object AuditPublisher extends AuditCompanion[AuditPublisher, ConversationEvent]("support-audit-publisher", ChangeSource.eventsOf(Conversation)):
  def create(ctx: ConsumerContext) = new AuditPublisher(ctx)
