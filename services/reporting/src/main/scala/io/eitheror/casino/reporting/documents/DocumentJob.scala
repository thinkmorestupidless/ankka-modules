package io.eitheror.casino.reporting.documents

import com.thinkmorestupidless.ankka.core.{ComponentId, DataSubject, EntityId, KeyAnswer, Keyring, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.{ReportingCodecs, ReportingRuntime}
import io.eitheror.casino.reporting.api.{Jobs, Reads}
import io.eitheror.casino.reporting.intake.Personals
import io.eitheror.casino.reporting.settings.{ReportingSettings, ReportingSettingsState}

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*
import scala.language.implicitConversions
import scala.util.Try

/**
 * What a job renders (research R9): the document's reference and kind, the report it is of, its format and period, the
 * parameters its rows are read with, who asked, the player it is about (a dispute report's), and the object, file name
 * and content type it is kept and served under. No personal value: a player is named by id.
 */
final case class JobRequest(reference: String, kind: String, report: String, format: String, from: String, to: String,
    parameters: Map[String, String], askedBy: String, subject: Option[String], objectName: String, fileName: String,
    contentType: String)

/**
 * A job's progress: the pages rendered, the rows they hold, and what a page carries to the next (an export's row count, a
 * dispute report's next login session and running totals), all of it amounts and counts.
 */
final case class JobState(request: Option[JobRequest] = None, pages: Int = 0, rows: Long = 0, carried: Map[String, Long] = Map.empty,
    step: String = JobState.New)

object JobState:
  val New      = "new"
  val Render   = "render"
  val Store    = "store"
  val Kept     = "kept"
  val Done     = "done"
  val Failed   = "failed"

/**
 * A document over the synchronous limit (data-model.md `DocumentJob`, `<reference>`; FR-022): `render` a page at a time
 * (`Jobs.PageRows` rows, or a dispute report's login sessions to as many rounds), each page an object of its own beside
 * the document, so neither the journal nor memory holds the rows; `store` streams an export's pages into the document one
 * after another (`Keeping.joinParts`), so memory holds one chunk of one page at a time, and renders a dispute report from
 * its pages joined, which its PDF needs whole and which hold one player's sessions over the range asked; it keeps the
 * document with the player ids it names (those it holds in memory) and pauses for the kind's lifetime; `expire` deletes it and marks the record. A step that fails
 * is retried three times, then `failed` deletes the pages and marks the record failed. A document erased or failed while
 * its job runs is not kept, and one whose named players were erased meanwhile is dropped (FR-021).
 */
final class DocumentJob(context: WorkflowContext) extends Workflow[JobState]:
  private val components = context.componentClient
  private def bucket     = context.storage.default
  private def now()      = ReportingRuntime.now()
  private def reads      = Reads(ReportingRuntime.views, components, ReportingRuntime.settings.get(), () => now())
  private def document(reference: String) = Keeping.document(components, reference)

  def emptyState: JobState = JobState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(2.minutes)
      .stepRecovery(DocumentJob.renderStep, RecoverStrategy.maxRetries(3).failoverTo(DocumentJob.failedStep))
      .stepRecovery(DocumentJob.storeStep, RecoverStrategy.maxRetries(3).failoverTo(DocumentJob.failedStep))
      .stepRecovery(DocumentJob.expireStep, RecoverStrategy.maxRetries(3))
      .stepRecovery(DocumentJob.failedStep, RecoverStrategy.maxRetries(3))
      .build

  def start(r: JobRequest): Effect[JobState] =
    if currentState.request.isDefined then effects.reply(currentState)
    else
      val next = JobState(Some(r), step = JobState.Render)
      effects.updateState(next).transitionTo(DocumentJob.renderStep).thenReply(next)

  def status: ReadOnlyEffect[JobState] = effects.reply(currentState)

  /** A page rendered, put beside the document, the record's progress moved. A repeat of a page replaces it. */
  def renderStep: StepEffect =
    val s   = currentState
    val req = s.request.get
    if document(req.reference).call(Document.get).invoke().status != Document.Generating then abandon(req)
    else
      val out = Jobs.page(reads, req, s.pages, s.carried)
      bucket.putBytes(Document.part(req.objectName, s.pages), out.text.getBytes(UTF_8), Keeping.PlayersType)
      if out.named.nonEmpty then
        bucket.putBytes(Document.partPlayers(req.objectName, s.pages), out.named.mkString("\n").getBytes(UTF_8), Keeping.PlayersType)
      val rows = s.rows + out.rows
      document(req.reference).call(Document.progress).invoke(Progress(rows, Jobs.percent(out))): Unit
      val next = s.copy(pages = s.pages + 1, rows = rows, carried = out.carried)
      if out.last then stepEffects.updateState(next.copy(step = JobState.Store)).thenTransitionTo(DocumentJob.storeStep)
      else stepEffects.updateState(next).thenTransitionTo(DocumentJob.renderStep)

  /** The pages joined into the document, kept with the players it names, the record ready; then the lifetime's pause. */
  def storeStep: StepEffect =
    val s   = currentState
    val req = s.request.get
    if document(req.reference).call(Document.get).invoke().status != Document.Generating then abandon(req)
    else
      val named = (0 until s.pages).flatMap(n => bucket.getBytes(Document.partPlayers(req.objectName, n)).toVector
        .flatMap((_, b) => String(b, UTF_8).split('\n').toVector)).filter(_.nonEmpty).distinct.toVector
      if (named ++ req.subject).exists(DocumentJob.erased) then
        Keeping.drop(bucket, req.objectName)
        document(req.reference).call(Document.expire).invoke(Expire(now(), Document.Erasure)): Unit
        stepEffects.updateState(s.copy(step = JobState.Done)).thenEnd
      else
        if req.kind == Document.DisputeReport then
          // Rendered whole, as HTML or a PDF of it: one player's sessions over the range asked, joined in memory.
          val parts = (0 until s.pages).map(n => bucket.getBytes(Document.part(req.objectName, n)).fold("")((_, b) => String(b, UTF_8)))
          bucket.putBytes(req.objectName, Jobs.finish(reads, req, parts.mkString, s.carried), req.contentType)
        else Keeping.joinParts(bucket, req.objectName, s.pages, req.contentType)
        Keeping.putNamed(bucket, req.objectName, named)
        Keeping.dropParts(bucket, req.objectName)
        val hours = settingsNow.lifetimeOf(req.kind)
        val at    = now()
        val after = document(req.reference).call(Document.readied).invoke(Ready(at, req.objectName, s.rows, at.plusSeconds(hours * 3600L)))
        if after.status != Document.Ready then
          // Erased or failed while it was being stored: not kept.
          Keeping.drop(bucket, req.objectName)
          stepEffects.updateState(s.copy(step = JobState.Done)).thenEnd
        else stepEffects.updateState(s.copy(step = JobState.Kept)).thenPause(hours.hours, DocumentJob.expireStep)

  /**
   * The lifetime over: the object deleted, the record marked; the sweep does the same for a pause that was lost. ankka
   * reckons a pause's deadline on the system clock, so a wake before the record's own expiry by the service's clock (a
   * test's clock moved past the system's) is not an expiry: the job ends and the sweep expires the document on time.
   */
  def expireStep: StepEffect =
    val req = currentState.request.get
    val d   = document(req.reference).call(Document.get).invoke()
    if d.expiresAt.forall(!_.isAfter(now())) then
      Keeping.drop(bucket, req.objectName)
      document(req.reference).call(Document.expire).invoke(Expire(now(), Document.Lifetime)): Unit
    stepEffects.updateState(currentState.copy(step = JobState.Done)).thenEnd

  /** A step that failed three times: its pages deleted, the record failed, the job ended failed. */
  def failedStep: StepEffect =
    val req = currentState.request.get
    Keeping.dropParts(bucket, req.objectName)
    document(req.reference).call(Document.fail).invoke(Fail(now(), s"the job's ${currentState.step} step failed")): Unit
    stepEffects.updateState(currentState.copy(step = JobState.Failed)).thenFail(s"document ${req.reference} could not be generated")

  private def abandon(req: JobRequest): StepEffect =
    Keeping.dropParts(bucket, req.objectName)
    stepEffects.updateState(currentState.copy(step = JobState.Done)).thenEnd

  private def settingsNow: ReportingSettingsState =
    components.forKeyValueEntity(EntityId(ReportingSettings.Id)).call(ReportingSettings.get).invoke()

object DocumentJob extends Workflow.Companion[DocumentJob, JobState](
      componentId = ComponentId("document-job"),
      stateSerializer = ReportingCodecs.serializer[JobState]("document-job")
    ):
  given Serializer[JobRequest] = ReportingCodecs.serializer[JobRequest]("document-job-request")
  given Serializer[JobState]   = ReportingCodecs.serializer[JobState]("document-job-reply")

  def create(context: WorkflowContext) = new DocumentJob(context)

  /**
   * Whether a player was erased (the keyring asked without making a key, as `Personals.of` asks it): a document naming
   * one is not kept. A keyring that cannot answer says no, and the erasure's own pass over kept documents takes it.
   */
  def erased(player: String): Boolean =
    val s: DataSubject = Personals.subject(player)
    Try(Keyring.current).flatMap(k => Try(k.key(k.project, s, create = false))).toOption.contains(KeyAnswer.Destroyed)

  val renderStep = step("render")(_.renderStep)
  val storeStep  = step("store")(_.storeStep)
  val expireStep = step("expire")(_.expireStep)
  val failedStep = step("failed")(_.failedStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
