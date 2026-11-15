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

  /**
   * The permission a document is admitted by, its kind's (contracts/routes.md, *Admission*, as spec 030 E3 amends it):
   * a dispute report `export.player`, a compliance export `report.compliance`, a day-end export `report.dayEnd`, the
   * traders report's export its report's, `report.compliance` (decided 2026-11-09), every other export `report.read`.
   */
  def permissionOf(d: DocumentState): String =
    d.kind match
      case Document.DisputeReport    => "export.player"
      case Document.ComplianceExport => "report.compliance"
      case Document.DayEndExport     => "report.dayEnd"
      case Document.ReportExport if d.parameters.get("report").contains(Exports.TradersReport) => "report.compliance"
      case _                         => "report.read"

  /**
   * The operator's permissions (already admitted as one of the brand's), from the brand's roles as `ctx.bags` last read
   * them; `None` until they are first read.
   */
  private def held(ctx: Ctx, p: Principal): Option[Set[String]] = ctx.bags.permissionsOf(p.roles)

  /** Whether an operator holding `permissions` may have the document. */
  def admitted(permissions: Set[String], d: DocumentState): Boolean = permissions(permissionOf(d))

  /** A refusal's words, as `Routes.operatorMay`'s. */
  def needed(permission: String): String = s"the $permission permission is needed"

  private val unread = refuse(503, Refusal("unavailable", message = Some("the brand's roles have not been read yet")))

  /**
   * An export is not found when its report is: one of the Reports tab while the tab is hidden (FR-024), a prediction
   * report's while the brand does not run the module, whatever the tab (FR-032).
   */
  private def hidden(ctx: Ctx, d: DocumentState): Boolean =
    d.kind == Document.ReportExport && {
      if d.parameters.get("report").exists(_.startsWith("predictions-")) then !ctx.settings.predictions else !ctx.reporting.reportsTab
    }

  private def iso(t: Option[Instant]) = t.map(_.toString)

  private def line(d: DocumentState) = DocumentLine(d.reference, d.kind, d.format, d.parameters, d.from, d.to, d.askedBy,
    iso(d.generatedAt), iso(d.readyAt), iso(d.expiresAt), d.status, d.endedBy, d.rows)

  /** The records generated in the period (the month to date by default) of `kind` (every kind for none), newest first. */
  def list(ctx: Ctx, p: Principal, kind: Option[String], from: Option[String], to: Option[String]): Answer =
    held(ctx, p).fold(unread) { permissions => answered(Wire.period(from, to, ctx.today).map { period =>
      val r = ctx.reads
      val k = kind.map(_.trim).filter(_.nonEmpty).getOrElse("all")
      val rows = DocumentsView.listed(ctx.views, k, r.startOf(period.from), r.startOf(period.to.plusDays(1)))
        .filter(d => admitted(permissions, d) && !hidden(ctx, d))
      ok(DocumentList(rows.map(line)))
    }) }

  def get(ctx: Ctx, p: Principal, reference: String): Answer =
    val d = Keeping.document(ctx.components, reference).call(Document.get).invoke()
    lazy val permissions = held(ctx, p)
    if !d.recorded || hidden(ctx, d) then notFound
    else if permissions.isEmpty then unread
    else if !admitted(permissions.get, d) then refuse(403, Refusal(Forbidden, message = Some(needed(permissionOf(d)))))
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
