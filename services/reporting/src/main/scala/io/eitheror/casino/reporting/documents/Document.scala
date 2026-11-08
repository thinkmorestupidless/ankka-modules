package io.eitheror.casino.reporting.documents

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.Personals

import java.time.Instant

/**
 * A generated document's record (data-model.md `Document`, `<reference>`; FR-021): what kind, in which format, for which
 * parameters and period, who asked, when it was generated and ready, until when it is kept, the object it is kept under
 * and the file name it is served as, its status (`generating`, `ready`, `expired`, `failed`), what ended it (`lifetime`,
 * `erasure`), the rows it holds and how far a job has come. It names no person (SC-006): the operator by their id, and a
 * document about one player (the dispute report) by the player's id in `subject`, which is not personal (spec 001
 * FR-024). `served` counts the times it was handed out.
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
    expiresAt: Option[Instant] = None,
    objectName: Option[String] = None,
    fileName: Option[String] = None,
    contentType: Option[String] = None,
    subject: Option[String] = None,
    status: String = "",
    endedBy: Option[String] = None,
    endedAt: Option[Instant] = None,
    rows: Long = 0,
    progress: Int = 0,
    served: Int = 0
):
  def recorded: Boolean = reference.nonEmpty
  def kept: Boolean     = status == Document.Ready || status == Document.Generating

  /** Whether it may be served at `at`: ready, and its lifetime not over even if no sweep has come yet. */
  def servable(at: Instant): Boolean = status == Document.Ready && expiresAt.forall(at.isBefore)

/**
 * A document's events (research R9, R15). It is event sourced, not key value as data-model.md first had it, because the
 * audit consumer must see every serving (FR-026), and a consumer over a key value entity sees only its latest state.
 */
enum DocumentEvent:
  case Started(record: DocumentState)
  case Progressed(rows: Long, progress: Int)
  case Readied(at: Instant, objectName: String, rows: Long, expiresAt: Instant)
  case Served(by: String, at: Instant)
  case Expired(at: Instant, endedBy: String)
  case Failed(at: Instant, reason: String)

final case class Progress(rows: Long, progress: Int)
final case class Ready(at: Instant, objectName: String, rows: Long, expiresAt: Instant)
final case class Serve(by: String, at: Instant)
final case class Expire(at: Instant, endedBy: String)
final case class Fail(at: Instant, reason: String)

/** A ready document rendered in the request (the synchronous path): its record, and when it is kept until. */
final case class Kept(record: DocumentState, expiresAt: Instant)

final class Document(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[DocumentState, DocumentEvent]:
  import DocumentEvent.*

  def emptyState: DocumentState = DocumentState()

  def applyEvent(event: DocumentEvent): DocumentState = Document.applied(currentState, event)

  private def same(d: DocumentState): Boolean =
    currentState.reference == d.reference && currentState.kind == d.kind && currentState.askedBy == d.askedBy

  private def conflict = effects.error(s"document ${currentState.reference} is already recorded", ErrorCode.Conflict)

  /** A job's document, generating until its job readies it. The same record again is a no-op. */
  def start(d: DocumentState): Effect[DocumentState] =
    if !currentState.recorded then effects.persist(Started(d.copy(status = Document.Generating))).thenReplyState
    else if same(d) then effects.reply(currentState)
    else conflict

  /** A document rendered in the request and kept, so ready as it is recorded. The same record again is a no-op. */
  def ready(k: Kept): Effect[DocumentState] =
    val d = k.record
    if !currentState.recorded then
      effects.persist(Started(d.copy(status = Document.Generating)),
        Readied(d.readyAt.getOrElse(k.expiresAt), d.objectName.getOrElse(""), d.rows, k.expiresAt)).thenReplyState
    else if same(d) then effects.reply(currentState)
    else conflict

  def progress(p: Progress): Effect[DocumentState] =
    if currentState.status != Document.Generating || (p.rows, p.progress) == (currentState.rows, currentState.progress) then
      effects.reply(currentState)
    else effects.persist(Progressed(p.rows, p.progress)).thenReplyState

  /** The job's document stored. Only a generating one: one erased or failed meanwhile stays as it is, and says so. */
  def readied(r: Ready): Effect[DocumentState] =
    if currentState.status == Document.Generating then effects.persist(Readied(r.at, r.objectName, r.rows, r.expiresAt)).thenReplyState
    else effects.reply(currentState)

  /** A serving, for the audit (FR-026): only of a ready document. */
  def served(s: Serve): Effect[DocumentState] =
    if currentState.status == Document.Ready then effects.persist(Served(s.by, s.at)).thenReplyState
    else effects.error(s"document ${currentState.reference} is not ready", ErrorCode.Conflict)

  /** Its object deleted: by its lifetime or an erasure. Once; the record stays (FR-021). */
  def expire(e: Expire): Effect[DocumentState] =
    if currentState.kept then effects.persist(Expired(e.at, e.endedBy)).thenReplyState
    else effects.reply(currentState)

  def fail(f: Fail): Effect[DocumentState] =
    if currentState.status == Document.Generating then effects.persist(Failed(f.at, f.reason)).thenReplyState
    else effects.reply(currentState)

  def get: ReadOnlyEffect[DocumentState] = effects.reply(currentState)

object Document extends EventSourcedEntity.Companion[Document, DocumentState, DocumentEvent](
      componentId = ComponentId("document"),
      stateSerializer = ReportingCodecs.serializer[DocumentState]("document"),
      eventSerializer = ReportingCodecs.serializer[DocumentEvent]("document-event")
    ):
  given Serializer[DocumentState] = ReportingCodecs.serializer[DocumentState]("document-reply")
  given Serializer[Kept]          = ReportingCodecs.serializer[Kept]("document-kept")
  given Serializer[Progress]      = ReportingCodecs.serializer[Progress]("document-progress")
  given Serializer[Ready]         = ReportingCodecs.serializer[Ready]("document-ready")
  given Serializer[Serve]         = ReportingCodecs.serializer[Serve]("document-serve")
  given Serializer[Expire]        = ReportingCodecs.serializer[Expire]("document-expire")
  given Serializer[Fail]          = ReportingCodecs.serializer[Fail]("document-fail")

  val Generating = "generating"
  val Ready      = "ready"
  val Expired    = "expired"
  val Failed     = "failed"

  /** What ended a document's keeping. */
  val Lifetime = "lifetime"
  val Erasure  = "erasure"

  /** The kinds (data-model.md; `ReportingSettings.Kinds`). */
  val ReportExport  = "report-export"
  val DisputeReport = "dispute-report"

  def applied(s: DocumentState, e: DocumentEvent): DocumentState = e match
    case DocumentEvent.Started(d)              => d.copy(status = Generating)
    case DocumentEvent.Progressed(rows, p)     => s.copy(rows = rows, progress = p)
    case DocumentEvent.Readied(at, name, n, x) =>
      s.copy(status = Ready, readyAt = Some(at), objectName = Some(name), rows = n, expiresAt = Some(x), progress = 100)
    case DocumentEvent.Served(_, _)            => s.copy(served = s.served + 1)
    case DocumentEvent.Expired(at, by)         => s.copy(status = Expired, endedBy = Some(by), endedAt = Some(at))
    case DocumentEvent.Failed(at, _)           => s.copy(status = Failed, endedAt = Some(at))

  /** Where a document naming no single player is kept (research R9): `reports/<kind>/<reference>.<ext>`. */
  def objectName(kind: String, reference: String, extension: String): String = s"reports/$kind/$reference.$extension"

  /**
   * Where a document about one player is kept (research R9): under the player's subject prefix, so `bucket.erase` takes
   * it with everything else of theirs.
   */
  def subjectObjectName(player: String, kind: String, reference: String, extension: String): String =
    ObjectRules.subjectPrefix(Personals.subject(player)) + objectName(kind, reference, extension)

  /** The player ids a document names, beside it (ids are not personal, spec 001 FR-024). */
  def sidecar(objectName: String): String = objectName.take(objectName.lastIndexOf('.').max(0)) + ".players"

  /** A job's page `n`, kept until the job joins its pages, beside the object it becomes. */
  def part(objectName: String, n: Int): String = f"$objectName.parts/$n%05d"

  /** The player ids a job's page names. */
  def partPlayers(objectName: String, n: Int): String = part(objectName, n) + ".players"

  def create(context: EventSourcedEntityContext) = new Document(context)

  val start    = command("start")(_.start)
  val ready    = command("ready")(_.ready)
  val progress = command("progress")(_.progress)
  val readied  = command("readied")(_.readied)
  val served   = command("served")(_.served)
  val expire   = command("expire")(_.expire)
  val fail     = command("fail")(_.fail)
  val get      = query("get")(_.get)
