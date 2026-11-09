package io.eitheror.casino.audit.exports

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.{AuditCodecs, Wiring}
import io.eitheror.casino.audit.ingest.IngestCalls
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.query.{Cursor, Filters}
import io.eitheror.casino.audit.store.Records

import java.io.{BufferedWriter, FileInputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.Instant
import scala.concurrent.duration.*

/**
 * One export (research R10), id the request's key: a brand's records for a period, in the brand's
 * format, as one object in the bucket `exports`. The actor is held as a pseudonym only, so the
 * workflow's state names no member of staff. `visibility` is a brand officer's limit.
 */
final case class ExportState(
    brand: String = "",
    format: String = "",
    from: Option[Instant] = None,
    to: Option[Instant] = None,
    visibility: Option[String] = None,
    actor: String = "",
    status: String = "",
    count: Option[Long] = None,
    objectName: Option[String] = None,
    startedAt: Option[Instant] = None
):
  def started: Boolean = status.nonEmpty

final case class StartExport(brand: String, format: String, from: Option[Instant], to: Option[Instant], visibility: Option[String],
    actor: String, at: Instant)

final class Export(context: WorkflowContext) extends Workflow[ExportState]:
  def emptyState: ExportState = ExportState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(10.minutes)
      .stepRecovery(Export.write, RecoverStrategy.maxRetries(2).failoverTo(Export.failed)).build

  /** Started once; the same key again answers the export as it stands. */
  def start(c: StartExport): Effect[ExportState] =
    if currentState.started then effects.reply(currentState)
    else
      effects.updateState(ExportState(c.brand, c.format, c.from, c.to, c.visibility, c.actor, Export.Running, startedAt = Some(c.at)))
        .transitionTo(Export.write.ref).thenReplyState

  /** Every matching record, a page at a time, to a temporary file; then the file into the bucket, and its record. */
  def writeStep: StepEffect =
    val s       = currentState
    val filters = Filters(brands = Some(Set(s.brand)), visibility = s.visibility, from = s.from, to = s.to)
    val file    = Files.createTempFile("audit-export-", s".${ExportFormat.extension(s.format)}")
    try
      val out   = BufferedWriter(OutputStreamWriter(Files.newOutputStream(file), UTF_8))
      var count = 0L
      try
        if s.format == ExportFormat.Csv then { out.write(Csv.header); out.write("\n") }
        var after: Option[Cursor] = None
        var more                  = true
        while more do
          val page = Records.page(Wiring.views().forView(Records), filters, after, Export.Page)
          page.rows.foreach { r =>
            out.write(if s.format == ExportFormat.Jsonl then JsonLines.line(r) else Csv.line(r))
            out.write("\n")
          }
          count += page.rows.size
          after = page.next
          more = page.next.isDefined
      finally out.close()
      val name = Export.objectName(s.brand, context.workflowId, s.format)
      val in   = FileInputStream(file.toFile)
      try context.storage.bucket(Export.Bucket).put(name, in, Files.size(file), ExportFormat.contentType(s.format))
      finally in.close()
      Export.recorded(IngestCalls.of(context.componentClient), context.workflowId, Export.Done, s, Own.change("count", count.toString))
      context.componentClient.forKeyValueEntity(ExportFormat.entityId(s.brand)).call(ExportFormat.exported)
        .invoke(Wiring.clock.get().instant()): Unit
      stepEffects.updateState(s.copy(status = Export.Done, count = Some(count), objectName = Some(name))).thenEnd
    finally Files.deleteIfExists(file): Unit

  def failedStep: StepEffect =
    Export.recorded(IngestCalls.of(context.componentClient), context.workflowId, Export.Failed, currentState, Own.change("status", Export.Failed),
      outcome = Export.Failed)
    stepEffects.updateState(currentState.copy(status = Export.Failed)).thenEnd

  def get: ReadOnlyEffect[ExportState] = effects.reply(currentState)

object Export extends Workflow.Companion[Export, ExportState](
      componentId = ComponentId("export"),
      stateSerializer = AuditCodecs.serializer[ExportState]("export")
    ):
  val Bucket  = "exports"
  val Running = "running"
  val Done    = "done"
  val Failed  = "failed"
  val Action  = "audit.export"
  /** Rows a step reads from the view at once. */
  val Page = 1000

  def objectName(brand: String, key: String, format: String): String = s"$brand/$key.${ExportFormat.extension(format)}"

  def entityId(key: String): EntityId = EntityId(key)

  /**
   * The export's record: who exported what range of which brand, in which format, and how it ended. Named
   * `export/<key>/<stage>`, so a retried step or a second request under the key writes no second record.
   */
  def recorded(calls: IngestCalls, key: String, stage: String, s: ExportState, last: io.eitheror.casino.audit.stored.StoredChange,
      outcome: String = "done"): Unit =
    Own.record(calls, s.brand, Action, s.actor, "export", s.brand, outcome, None, Vector(
      Own.change("brand", s.brand),
      Own.change("range", s"${s.from.fold("")(_.toString)}..${s.to.fold("")(_.toString)}"),
      Own.change("format", s.format),
      last
    ), Wiring.clock.get().instant(), id = Some(s"export/$key/$stage")): Unit

  given Serializer[StartExport] = AuditCodecs.serializer[StartExport]("start-export")

  def create(context: WorkflowContext) = new Export(context)

  val write  = step("write")(_.writeStep)
  val failed = step("failed")(_.failedStep)

  val start = command("start")(_.start)
  val get   = query("get")(_.get)
