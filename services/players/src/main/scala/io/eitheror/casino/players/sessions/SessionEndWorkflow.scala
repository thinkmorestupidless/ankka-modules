package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.{PlayersCodecs, PlayersRuntime}
import io.eitheror.casino.players.player.{PlayerEntity, SessionId}

import java.time.Instant
import scala.concurrent.duration.*

/** Why a player session ends (FR-020; R6), and the status the session takes for it. */
object EndReason:
  val Logout     = "logout"
  val Expired    = "expired"
  val Forced     = "forced"
  val Suspended  = "suspended"
  val Superseded = "superseded"

  val all: Set[String] = Set(Logout, Expired, Forced, Suspended, Superseded)

  /** The session's status: a sign-out completes it, every other reason is its own status. */
  def status(reason: String): String = if reason == Logout then SessionStatus.Completed else reason

  /** The close reason the facts carry for a status: `completed` was a sign-out. */
  def closeReason(status: String): String = if status == SessionStatus.Completed then Logout else status

final case class StartEnd(reason: String, at: Instant)

/** The workflow's state: the reason and time, the step, the player, and the game sessions it ended. */
final case class SessionEndFlow(
    reason: Option[String] = None,
    at: Option[Instant] = None,
    step: String = "",
    playerId: String = "",
    gamesEnded: Vector[String] = Vector.empty,
    done: Boolean = false
):
  def started: Boolean = reason.isDefined

/**
 * Ending one player session (research R6), id = the session's id: `games` ends every game session the
 * player session holds, with the same reason, each end idempotent and told to the player; `session`
 * ends the player session, whose event is the source of `player_session_ended` and `player_logged_out`;
 * `player` tells the player. A second start under the same id, whatever its reason, joins the first.
 */
final class SessionEndWorkflow(context: WorkflowContext) extends Workflow[SessionEndFlow]:
  private val components = context.componentClient
  private def sessionId  = context.workflowId.toString
  private def session    = components.forEventSourcedEntity(EntityId(sessionId))
  private def reason     = currentState.reason.get
  private def at         = currentState.at.get

  def emptyState: SessionEndFlow = SessionEndFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(10.seconds).defaultRecovery(RecoverStrategy.maxRetries(5)).build

  def start(c: StartEnd): Effect[SessionEndFlow] =
    if currentState.started then effects.reply(currentState)
    else if !EndReason.all(c.reason) then effects.error(s"no such reason: ${c.reason}", com.thinkmorestupidless.ankka.core.ErrorCode.BadRequest)
    else
      val next = SessionEndFlow(Some(c.reason), Some(c.at), "games")
      effects.updateState(next).transitionTo(SessionEndWorkflow.games).thenReply(next)

  def status: ReadOnlyEffect[SessionEndFlow] = effects.reply(currentState)

  def gamesStep: StepEffect =
    val s     = session.call(PlayerSessionEntity.get).invoke()
    val games = s.gameSessions.distinct.sorted
    val ended = games.filter { g =>
      val end = components.forEventSourcedEntity(EntityId(g)).call(GameSessionEntity.end).invoke(EndGameSession(reason, at))
      if s.playerId.nonEmpty then
        components.forEventSourcedEntity(PlayerEntity.id(s.playerId)).call(PlayerEntity.gameSessionEnded).invoke(SessionId(g, at)): Unit
      end.ended
    }
    stepEffects.updateState(currentState.copy(step = "session", playerId = s.playerId, gamesEnded = ended))
      .thenTransitionTo(SessionEndWorkflow.sessionStep)

  def sessionStep: StepEffect =
    session.call(PlayerSessionEntity.end).invoke(EndSession(EndReason.status(reason), at)): Unit
    stepEffects.updateState(currentState.copy(step = "player")).thenTransitionTo(SessionEndWorkflow.playerStep)

  def playerStep: StepEffect =
    if currentState.playerId.nonEmpty then
      components.forEventSourcedEntity(PlayerEntity.id(currentState.playerId)).call(PlayerEntity.sessionEnded)
        .invoke(SessionId(sessionId, at)): Unit
    stepEffects.updateState(currentState.copy(step = "done", done = true)).thenEnd

object SessionEndWorkflow
    extends Workflow.Companion[SessionEndWorkflow, SessionEndFlow](
      componentId = ComponentId("session-end"),
      stateSerializer = PlayersCodecs.serializer[SessionEndFlow]("session-end-flow")
    ):
  given Serializer[StartEnd]       = PlayersCodecs.serializer[StartEnd]("session-end-start")
  given Serializer[SessionEndFlow] = PlayersCodecs.serializer[SessionEndFlow]("session-end-reply")

  def create(context: WorkflowContext) = new SessionEndWorkflow(context)

  /** Starts the end, and waits for it when `bound` is given; answers whether it ended within it. */
  def end(components: ComponentClient, sessionId: String, reason: String, bound: Option[FiniteDuration]): Boolean =
    val call = components.forWorkflow(EntityId(sessionId)).call(start)
    val at   = PlayersRuntime.now()
    bound match
      case None => call.invoke(StartEnd(reason, at)).started
      case Some(b) =>
        scala.util.Try(call.thenAwaitEnd[SessionEndFlow](b).invoke(StartEnd(reason, at))).toOption.exists(_.done)

  val games       = step("games")(_.gamesStep)
  val sessionStep = step("session")(_.sessionStep)
  val playerStep  = step("player")(_.playerStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
