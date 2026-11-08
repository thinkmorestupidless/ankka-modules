package io.eitheror.casino.reporting.documents

import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}
import io.eitheror.casino.reporting.ReportingRuntime

/**
 * `report.generated` when a document is ready and `report.served` each time it is served (FR-026; contracts/facts.md,
 * *The audit record*), for every document: a report's export kept synchronously or by a job, a dispute report, a
 * day-end or compliance export. Made from the record's events, so a serving is never missed, as a key value state's
 * latest value could miss it. The record names the operator, the reference, the kind and the period; no person.
 */
final class DocumentAudit(context: ConsumerContext) extends AuditConsumer[DocumentEvent]:
  protected def audit(event: DocumentEvent): Option[AuditRecord] =
    val made = event match
      case DocumentEvent.Readied(at, _, _, _) => Some(("report.generated", None, at))
      case DocumentEvent.Served(by, at)       => Some(("report.served", Some(by), at))
      case _                                  => None
    made.map { case (action, by, at) =>
      val reference = messageContext.subject
      // The kind, period and who asked are fixed when the record starts; the record is read for them.
      val d  = Keeping.document(context.componentClient, reference).call(Document.get).invoke()
      val id = s"audit/document/$reference/${messageContext.sequenceNumber}"
      AuditRecord(recordId = id, brand = ReportingRuntime.settings.get().brand, service = "reporting", action = action,
        actorKind = "operator", actorId = by.getOrElse(d.askedBy), approverId = None, subjectKind = "document",
        subjectId = reference, outcome = "ok", reasonCode = None,
        changes = Vector(AuditChange("kind", None, Some(d.kind)), AuditChange("period", None, Some(DocumentAudit.period(d)))),
        correlationId = reference, causationId = id, recordClass = "operational", occurredAt = at)
    }

object DocumentAudit extends AuditCompanion[DocumentAudit, DocumentEvent]("document-audit", ChangeSource.eventsOf(Document)):
  def create(ctx: ConsumerContext) = new DocumentAudit(ctx)

  /** The period a document covers, `<from>/<to>` in reporting days, or empty for one with none. */
  def period(d: DocumentState): String = (d.from, d.to) match
    case (Some(f), Some(t)) => s"$f/$t"
    case (Some(f), None)    => f
    case _                  => ""
