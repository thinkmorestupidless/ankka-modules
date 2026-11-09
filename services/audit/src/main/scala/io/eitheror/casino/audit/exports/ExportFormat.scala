package io.eitheror.casino.audit.exports

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs

/**
 * The format a brand's regulator agreed (data-model.md `ExportFormat`): `csv` by default, or `jsonl`;
 * and when the brand's last export finished, for the status (FR-024).
 */
final case class FormatState(format: String = ExportFormat.Csv, lastExport: Option[java.time.Instant] = None)

final class ExportFormat extends KeyValueEntity[FormatState]:
  def emptyState: FormatState = FormatState()

  def set(format: String): Effect[FormatState] =
    effects.updateState(currentState.copy(format = format)).thenReply(identity)

  def exported(at: java.time.Instant): Effect[FormatState] =
    effects.updateState(currentState.copy(lastExport = Some(at))).thenReply(identity)

  def get: ReadOnlyEffect[FormatState] = effects.reply(currentState)

object ExportFormat extends KeyValueEntity.Companion[ExportFormat, FormatState](
      componentId = ComponentId("export-format"),
      stateSerializer = AuditCodecs.serializer[FormatState]("export-format")
    ):
  val Csv   = "csv"
  val Jsonl = "jsonl"
  val Formats: Set[String] = Set(Csv, Jsonl)

  def extension(format: String): String   = if format == Jsonl then "jsonl" else "csv"
  def contentType(format: String): String = if format == Jsonl then "application/x-ndjson" else "text/csv"

  def entityId(brand: String): EntityId = EntityId(brand)

  given Serializer[String]            = AuditCodecs.serializer[String]("format")
  given Serializer[java.time.Instant] = AuditCodecs.serializer[java.time.Instant]("exported-at")

  def create(context: KeyValueEntityContext) = new ExportFormat

  val set      = command("set")(_.set)
  val exported = command("exported")(_.exported)
  val get = query("get")(_.get)
