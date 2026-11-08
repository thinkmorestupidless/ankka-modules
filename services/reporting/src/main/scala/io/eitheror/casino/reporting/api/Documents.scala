package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.reporting.documents.{Document, DocumentState, Documents as DocumentsView, Keeping}

import java.time.Instant

import Wire.*

/** A record as `GET /documents` lists it: what, for when, who asked and when, and what became of it. No personal field. */
final case class DocumentLine(reference: String, kind: String, format: String, parameters: Map[String, String], from: Option[String],
    to: Option[String], askedBy: String, generatedAt: Option[String], readyAt: Option[String], expiresAt: Option[String],
    status: String, endedBy: Option[String], rows: Long)
final case class DocumentList(documents: Vector[DocumentLine])

object DocumentList:
  given JsonValueCodec[DocumentList] = Wire.answer[DocumentList]

/**
 * `GET /documents` and `GET /documents/{reference}` (FR-021, FR-022): the records, newest first, of the kinds the caller
 * is admitted to; a document served as its attachment while it is kept, its job's progress while it is generating, and
 * `410 document_expired` once its lifetime is over or an erasure took it. Each serving is recorded for the audit (FR-026).
 */
object Documents:
  val Expired = "document_expired"
  val Failed  = "document_failed"
  val Forbidden = "forbidden"

  /** The roles a document's kind is admitted by (contracts/routes.md, *Admission*); `None` for every operator of the brand. */
  def rolesOf(kind: String): Option[Seq[String]] =
    val reports = Seq("brand_admin", "resource_admin", "platform_admin")
    kind match
      case Document.DisputeReport => None
      case Document.ComplianceExport => Some(reports :+ "brand_compliance")
      case Document.DayEndExport     => Some(reports :+ "brand_finance")
      case _                      => Some(reports)

  /** Whether the operator (already admitted as one of the brand's) may have a document of `kind`. */
  def admitted(p: Principal, kind: String): Boolean = rolesOf(kind).forall(_.exists(p.roles.contains))

  /** An export of the Reports tab is not found while the tab is hidden, as its report is (FR-024). */
  private def hidden(ctx: Ctx, kind: String): Boolean = kind == Document.ReportExport && !ctx.reporting.reportsTab

  private def iso(t: Option[Instant]) = t.map(_.toString)

  private def line(d: DocumentState) = DocumentLine(d.reference, d.kind, d.format, d.parameters, d.from, d.to, d.askedBy,
    iso(d.generatedAt), iso(d.readyAt), iso(d.expiresAt), d.status, d.endedBy, d.rows)

  /** The records generated in the period (the month to date by default) of `kind` (every kind for none), newest first. */
  def list(ctx: Ctx, p: Principal, kind: Option[String], from: Option[String], to: Option[String]): Answer =
    answered(Wire.period(from, to, ctx.today).map { period =>
      val r = ctx.reads
      val k = kind.map(_.trim).filter(_.nonEmpty).getOrElse("all")
      val rows = DocumentsView.listed(ctx.views, k, r.startOf(period.from), r.startOf(period.to.plusDays(1)))
        .filter(d => admitted(p, d.kind) && !hidden(ctx, d.kind))
      ok(DocumentList(rows.map(line)))
    })

  def get(ctx: Ctx, p: Principal, reference: String): Answer =
    val d = Keeping.document(ctx.components, reference).call(Document.get).invoke()
    if !d.recorded || hidden(ctx, d.kind) then notFound
    else if !admitted(p, d.kind) then refuse(403, Refusal(Forbidden, message = Some(s"the ${rolesOf(d.kind).get.head} permission is needed")))
    else
      val at = ctx.now()
      d.status match
        case Document.Generating => json(202, JobStatus(d.reference, d.status, d.progress))
        case Document.Failed     => refuse(500, Refusal(Failed, message = Some(s"document $reference could not be generated")))
        case Document.Ready if d.servable(at) =>
          d.objectName.flatMap(ctx.clients.storage.default.getBytes) match
            case None => refuse(410, Refusal(Expired))
            case Some((stored, body)) =>
              Keeping.served(ctx.components, reference, p.subject, at)
              attachment(d.contentType.getOrElse(stored), d.fileName.getOrElse(reference), body)
        case _ => refuse(410, Refusal(Expired))
