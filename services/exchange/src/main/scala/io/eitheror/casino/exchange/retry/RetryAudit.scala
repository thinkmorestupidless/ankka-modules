package io.eitheror.casino.exchange.retry

import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}
import io.eitheror.casino.exchange.ExchangeRuntime

/**
 * A record on `platform.audit` for each note of a settlement retry (R5, FR-003): the refusal that
 * started it, and its success or its abandoning, naming the call's key and the wallet's route.
 */
final class RetryAudit extends AuditConsumer[RetryState]:
  protected def audit(state: RetryState): Option[AuditRecord] =
    for note <- state.note; call <- state.call yield
      val id = s"settlement-retry/${messageContext.subject}/${note.seq}"
      AuditRecord(recordId = id, brand = ExchangeRuntime.settings.brand, service = "exchange",
        action = s"exchange.settlement_${note.kind}", actorKind = if note.by.isDefined then "operator" else "system",
        actorId = note.by.getOrElse("exchange"), approverId = None, subjectKind = "wallet_call", subjectId = call.key,
        outcome = if note.kind == "refused" then "refused" else "accepted", reasonCode = state.lastRefusal.filter(_ => note.kind == "refused"),
        changes = Vector(AuditChange("route", None, Some(call.path.split('/').last)), AuditChange("attempts", None, Some(state.attempts.toString)))
          ++ note.reason.filter(_ => note.by.isDefined).map(_ => AuditChange("reason", None, None)),
        correlationId = id, causationId = id, recordClass = if note.by.isDefined then "operator" else "system", occurredAt = note.at)

object RetryAudit extends AuditCompanion[RetryAudit, RetryState]("settlement-retry-audit", ChangeSource.stateOf(SettlementRetry)):
  def create(ctx: ConsumerContext) = new RetryAudit
