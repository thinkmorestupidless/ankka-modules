package io.eitheror.casino.reporting.documents

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs

import java.time.Instant

/**
 * A generated document's record (data-model.md `Document`, key value, `<reference>`): what kind, in which format,
 * for which parameters and period, who asked, when it was generated and ready, the object it is kept under, its
 * status and the rows it holds. It names no person (SC-006): the operator by their id, a player by none. This is its
 * first shape, for the documents a request renders itself; the job, the lifetime and erasure (US5) add to it.
 */
final case class DocumentState(
    reference: String = "",
    kind: String = "",
    format: String = "",
    parameters: Map[String, String] = Map.empty,
    from: Option[String] = None,
    to: Option[String] = None,
    askedBy: String = "",
    generatedAt: Option[Instant] = None,
    readyAt: Option[Instant] = None,
    objectName: Option[String] = None,
    status: String = "",
    endedBy: Option[String] = None,
    rows: Long = 0
):
  def recorded: Boolean = reference.nonEmpty

final class Document(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[DocumentState]:
  def emptyState: DocumentState = DocumentState()

  /**
   * A document rendered in the request and kept, so ready when it is recorded. The same record again is a no-op; a
   * reference recorded with something else is refused, since a reference names one document.
   */
  def ready(d: DocumentState): Effect[DocumentState] =
    if !currentState.recorded then effects.updateState(d.copy(status = Document.Ready)).thenReplyState
    else if currentState == d.copy(status = Document.Ready) then effects.reply(currentState)
    else effects.error(s"document ${d.reference} is already recorded", ErrorCode.Conflict)

  def get: ReadOnlyEffect[DocumentState] = effects.reply(currentState)

object Document extends KeyValueEntity.Companion[Document, DocumentState](
      componentId = ComponentId("document"),
      stateSerializer = ReportingCodecs.serializer[DocumentState]("document")
    ):
  given Serializer[DocumentState] = ReportingCodecs.serializer[DocumentState]("document-reply")

  val Generating = "generating"
  val Ready      = "ready"
  val Expired    = "expired"

  /** A report's export (data-model.md's kinds). */
  val ReportExport = "report-export"

  /** Where a document naming no player is kept (research R9): `reports/<kind>/<reference>.<ext>`. */
  def objectName(kind: String, reference: String, extension: String): String = s"reports/$kind/$reference.$extension"

  def create(context: KeyValueEntityContext) = new Document(context)

  val ready = command("ready")(_.ready)
  val get   = query("get")(_.get)
