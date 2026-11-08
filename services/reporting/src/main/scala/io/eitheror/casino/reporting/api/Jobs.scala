package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.reporting.documents.{Document, DocumentJob, DocumentState, JobRequest, Keeping}

import java.nio.charset.StandardCharsets.UTF_8

import Wire.*

/** `202 {reference, status}`, and `GET /documents/{reference}` while a job runs: `{reference, status, progress}`. */
final case class JobAnswer(reference: String, status: String)
final case class JobStatus(reference: String, status: String, progress: Int)

object JobAnswer:
  given JsonValueCodec[JobAnswer] = Wire.answer[JobAnswer]

object JobStatus:
  given JsonValueCodec[JobStatus] = Wire.answer[JobStatus]

/** What a `DocumentJob` renders, by the document's kind: an export's CSV pages, or a dispute report's (research R9). */
object Jobs:
  /** A job's page: at most this many rows of an export, or of rounds for a dispute report. */
  val PageRows = 1_000

  /** A page: its text, the player ids it names, its rows, the whole's, what it carries to the next, and whether it is the last. */
  final case class Rendered(text: String, named: Vector[String], rows: Long, total: Long, carried: Map[String, Long], last: Boolean)

  def page(r: Reads, req: JobRequest, n: Int, carried: Map[String, Long]): Rendered =
    if req.kind == Document.DisputeReport then Disputes.jobPage(r, req, n, carried) else Exports.jobPage(r, req, n, carried)

  /** The document from its pages joined: an export as they are; a dispute report with its tail, and as PDF when asked. */
  def finish(r: Reads, req: JobRequest, pages: String, carried: Map[String, Long]): Array[Byte] =
    if req.kind == Document.DisputeReport then Disputes.jobFinish(r, req, pages, carried) else pages.getBytes(UTF_8)

  /** How far a job has come, as a whole percent: the last page is 100. */
  def percent(out: Rendered): Int =
    if out.last then 100
    else if out.total <= 0 then 0
    else ((out.carried.getOrElse("done", out.rows) * 100) / out.total).toInt.min(99)

  /** The record started generating and its job started: `202 {reference, status}` (FR-022). */
  def started(ctx: Ctx, record: DocumentState, request: JobRequest): Answer =
    Keeping.document(ctx.components, record.reference).call(Document.start).invoke(record): Unit
    ctx.components.forWorkflow(EntityId(record.reference)).call(DocumentJob.start).invoke(request): Unit
    json(202, JobAnswer(record.reference, Document.Generating))
