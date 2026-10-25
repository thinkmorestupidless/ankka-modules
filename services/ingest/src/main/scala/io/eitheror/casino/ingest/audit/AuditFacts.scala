package io.eitheror.casino.ingest.audit

import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.AuditRecord
import io.eitheror.casino.ingest.requests.{Outcome, RecordState, RequestRecord}

/**
 * A key or rate refusal is an `audit_record` on `platform.audit` (FR-019, research R9), from the record it
 * left, over the contract's audit base: the actor the sub-brand where the record names one (a disabled key,
 * a rate) and `unknown` otherwise; `ce-id` the record's id. The key never appears: the record never held
 * it. Nor do the record's ticket and request id. Every other change is ignored.
 */
object AuditFacts:
  val Service = "ingest"

  def record(r: RecordState): Option[AuditRecord] =
    Option.when(Outcome.Audited(r.outcome) && r.lostAnswer.isEmpty && !r.seen) {
      AuditRecord(recordId = r.recordId, brand = "payments", service = Service, action = r.route,
        actorKind = r.subBrand.fold("unknown")(_ => "sub-brand"), actorId = r.subBrand.getOrElse("unknown"), approverId = None,
        subjectKind = "request", subjectId = r.recordId, outcome = "refused", reasonCode = Some(r.outcome), changes = Vector.empty,
        correlationId = r.recordId, causationId = r.recordId, recordClass = "security",
        occurredAt = r.receivedAt.getOrElse(java.time.Instant.EPOCH))
    }

final class RequestAudit extends AuditConsumer[RecordState]:
  protected def audit(change: RecordState): Option[AuditRecord] = AuditFacts.record(change)

object RequestAudit extends AuditCompanion[RequestAudit, RecordState]("request-audit", ChangeSource.stateOf(RequestRecord)):
  def create(ctx: ConsumerContext) = new RequestAudit
