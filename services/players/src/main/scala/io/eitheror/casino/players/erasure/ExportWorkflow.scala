package io.eitheror.casino.players.erasure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.{Json, Neighbours, PlayersCodecs, PlayersRuntime}
import io.eitheror.casino.players.api.{GameSessionRoutes, ProfileRoutes}
import io.eitheror.casino.players.clients.{Neighbour, Raced}
import io.eitheror.casino.players.inbox.{MessageEntity, MessagesView}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState}
import io.eitheror.casino.players.reads.ActivityView
import io.eitheror.casino.players.sessions.{SessionRow, SessionsView}

import java.time.Instant
import scala.concurrent.duration.*

/**
 * An export (FR-040; research R10), keyed by its id: whose, when it was made, until when it is kept, and
 * the services that did not answer. The export itself is personal and is held in [[ExportBody]], which
 * the `expire` step deletes, so the workflow's journal holds none of it.
 */
final case class ExportFlow(
    exportId: String = "",
    playerId: String = "",
    requestedBy: String = "",
    step: String = ExportFlow.New,
    parts: Vector[String] = Vector.empty,
    missing: Vector[String] = Vector.empty,
    generatedAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None
):
  def started: Boolean = step != ExportFlow.New
  def ready: Boolean   = generatedAt.isDefined

object ExportFlow:
  val New    = "new"
  val Own    = "own"
  val Others = "others"
  val Done   = "done"

final case class StartExport(playerId: String, requestedBy: String, at: Instant)

/** The assembled export, `Personal`: put once, read by the download route, deleted when it expires. */
final case class ExportBodyState(body: Personal[String] = Personal(""))

final class ExportBody(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[ExportBodyState]:
  def emptyState: ExportBodyState = ExportBodyState()
  def put(s: ExportBodyState): Effect[ExportBodyState] = effects.updateState(s).thenReplyState
  def drop: Effect[ExportBodyState] = effects.deleteEntity().thenReply(_ => ExportBodyState())
  def get: ReadOnlyEffect[ExportBodyState] = effects.reply(currentState)

object ExportBody
    extends KeyValueEntity.Companion[ExportBody, ExportBodyState](
      componentId = ComponentId("export-body"),
      stateSerializer = PlayersCodecs.serializer[ExportBodyState]("export-body-state")
    ):
  given Serializer[ExportBodyState] = PlayersCodecs.serializer[ExportBodyState]("export-body-reply")
  def create(context: KeyValueEntityContext) = new ExportBody(context)
  val put  = command("put")(_.put)
  val drop = command("drop")(_.drop)
  val get  = query("get")(_.get)

/**
 * `own` assembles this service's part (the profile, consents, sessions, activity and inbox; never an
 * operator's note); `others` asks each brand service for its part within `EXPORT_PART_BOUND` and names
 * one that does not answer as missing; the export is kept `EXPORT_RETENTION`, then `expire` deletes it.
 * `own` is retried: it reads the player and puts the body under the workflow's id, so running it again
 * is harmless, and without retries a stop in the middle of it (a redeploy) failed the export for good.
 */
final class ExportWorkflow(context: WorkflowContext) extends Workflow[ExportFlow]:
  import ExportFlow.*

  private val components = context.componentClient
  private def now()      = PlayersRuntime.now()
  private def live       = PlayersRuntime.settings
  private def neighbours = Neighbours(context.services, context.secrets)
  private def body       = components.forKeyValueEntity(EntityId(currentState.exportId))

  def emptyState: ExportFlow = ExportFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      .stepRecovery(ExportWorkflow.ownStep, RecoverStrategy.maxRetries(3))
      .stepTimeout(ExportWorkflow.othersStep, live.exportPartBound.toMillis.millis + 1.second)
      .stepRecovery(ExportWorkflow.othersStep, RecoverStrategy.maxRetries(0).failoverTo(ExportWorkflow.missingStep))
      .build

  def start(c: StartExport): Effect[ExportFlow] =
    if currentState.started then effects.reply(currentState)
    else
      val next = ExportFlow(context.workflowId.toString, c.playerId, c.requestedBy, Own)
      effects.updateState(next).transitionTo(ExportWorkflow.ownStep).thenReply(next)

  def status: ReadOnlyEffect[ExportFlow] = effects.reply(currentState)

  def ownStep: StepEffect =
    val s = currentState
    val p = components.forEventSourcedEntity(PlayerEntity.id(s.playerId)).call(PlayerEntity.get).invoke()
    body.call(ExportBody.put).invoke(ExportBodyState(Personal(Json.render(ExportWorkflow.own(components, p))))): Unit
    stepEffects.updateState(s.copy(step = Others)).thenTransitionTo(ExportWorkflow.othersStep)

  /** The next service's part, bounded by its own step timeout; one that does not answer fails over to `missing`. */
  def othersStep: StepEffect =
    val s = currentState
    next(s) match
      case None => finish(s)
      case Some(t) =>
        Neighbour.inline(neighbours.`export`.part(t.target, t.subject(s.playerId), live.exportPartBound)) match
          case Raced.Answered(part) =>
            val doc   = Json.parseObject(body.call(ExportBody.get).invoke().body.reveal).getOrElse(Json.obj())
            val parts = Option(doc.get("parts")).collect { case o: ObjectNode => o }.getOrElse(Json.obj())
            parts.set[JsonNode](t.name, part)
            doc.set[JsonNode]("parts", parts)
            body.call(ExportBody.put).invoke(ExportBodyState(Personal(Json.render(doc)))): Unit
            stepEffects.updateState(s.copy(parts = s.parts :+ t.name)).thenTransitionTo(ExportWorkflow.othersStep)
          case _ => stepEffects.updateState(s.copy(missing = s.missing :+ t.name)).thenTransitionTo(ExportWorkflow.othersStep)

  /** The service whose part did not come within its bound, named as missing (FR-040). */
  def missingStep: StepEffect =
    val s = currentState
    next(s).fold(stepEffects.thenTransitionTo(ExportWorkflow.othersStep))(t =>
      stepEffects.updateState(s.copy(missing = s.missing :+ t.name)).thenTransitionTo(ExportWorkflow.othersStep))

  private def next(s: ExportFlow): Option[ErasureTarget] =
    ExportWorkflow.targets(live.brand).find(t => !s.parts.contains(t.name) && !s.missing.contains(t.name))

  private def finish(s: ExportFlow): StepEffect =
    val doc = Json.parseObject(body.call(ExportBody.get).invoke().body.reveal).getOrElse(Json.obj())
    val at  = now()
    if !doc.has("parts") then doc.set[JsonNode]("parts", Json.obj()): Unit
    doc.set[JsonNode]("missing", Json.arr(s.missing*))
    doc.put("generatedAt", at.toString)
    doc.put("expiresAt", at.plus(live.exportRetention).toString)
    body.call(ExportBody.put).invoke(ExportBodyState(Personal(Json.render(doc)))): Unit
    stepEffects.updateState(s.copy(step = Done, generatedAt = Some(at), expiresAt = Some(at.plus(live.exportRetention))))
      .thenPause(live.exportRetention.toMillis.millis, ExportWorkflow.expireStep)

  def expireStep: StepEffect =
    body.call(ExportBody.drop).invoke(): Unit
    stepEffects.thenEnd

object ExportWorkflow
    extends Workflow.Companion[ExportWorkflow, ExportFlow](
      componentId = ComponentId("export"),
      stateSerializer = PlayersCodecs.serializer[ExportFlow]("export-flow")
    ):
  given Serializer[StartExport] = PlayersCodecs.serializer[StartExport]("export-start")
  given Serializer[ExportFlow]  = PlayersCodecs.serializer[ExportFlow]("export-reply")

  def create(context: WorkflowContext) = new ExportWorkflow(context)

  /** The services asked for their part: the erasure's, as each serves both routes (contracts/routes.md). */
  def targets(brand: String): Vector[ErasureTarget] = ErasureWorkflow.targets(brand)

  /** This service's part (FR-040): never an operator's note. */
  def own(components: ComponentClient, p: PlayerState): ObjectNode =
    val views    = PlayersRuntime.views()
    val sessions = Vector(SessionRow.Player, SessionRow.Game).flatMap(k => SessionsView.ofPlayer(views, p.playerId, k, 0, 100_000))
    val activity = ActivityView.ofPlayer(views, p.playerId, None, 0, 100_000)
    val messages = MessagesView.ofPlayer(views, p.playerId, 0, 100_000, includeDeleted = true).map(r =>
      ProfileRoutes.message(r, components.forEventSourcedEntity(EntityId(r.messageId)).call(MessageEntity.get).invoke()))
    Json.obj("service" -> "players", "playerId" -> p.playerId, "profile" -> ProfileRoutes.profileOf(p),
      "consents" -> ProfileRoutes.consentsOf(p.consents), "sessions" -> Json.arr(sessions.map(r => GameSessionRoutes.wire(r).put("kind", r.kind))*),
      "activity" -> Json.arr(activity.map(ProfileRoutes.event)*), "messages" -> Json.arr(messages*))

  val ownStep    = step("own")(_.ownStep)
  val othersStep = step("others")(_.othersStep)
  val missingStep = step("missing")(_.missingStep)
  val expireStep = step("expire")(_.expireStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
