package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.PlayersCodecs

import java.time.Instant

/** A signed-in session's status: open, or the reason it ended (data-model.md). */
object SessionStatus:
  val Active     = "active"
  val Completed  = "completed"
  val Expired    = "expired"
  val Forced     = "forced"
  val Suspended  = "suspended"
  val Superseded = "superseded"

  val endings: Set[String] = Set(Completed, Expired, Forced, Suspended, Superseded)

/** A player session (FR-013): the realm's session it mirrors, where it came from, and its game sessions. */
final case class PlayerSessionState(
    playerId: String = "",
    realmSessionId: String = "",
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    networkAddress: Personal[String] = Personal(""),
    userAgent: Personal[String] = Personal(""),
    deviceFingerprint: Personal[String] = Personal(""),
    status: String = "",
    gameSessions: Vector[String] = Vector.empty
):
  def exists: Boolean = status.nonEmpty
  def open: Boolean   = status == SessionStatus.Active

sealed trait PlayerSessionEvent
object PlayerSessionEvent:
  final case class Opened(playerId: String, realmSessionId: String, networkAddress: Personal[String], userAgent: Personal[String],
      deviceFingerprint: Personal[String], at: Instant) extends PlayerSessionEvent
  final case class GameSessionAdded(sessionId: String) extends PlayerSessionEvent
  final case class Ended(reason: String, at: Instant)  extends PlayerSessionEvent

final case class OpenPlayerSession(playerId: String, realmSessionId: String, networkAddress: String, userAgent: String,
    deviceFingerprint: String, at: Instant)
final case class AddGameSession(sessionId: String)
final case class EndSession(reason: String, at: Instant)

/** What an end answered: whether this call ended it, and the game sessions it held (to end in turn). */
final case class SessionEnd(ended: Boolean, gameSessions: Vector[String], refusal: Option[String] = None)

final class PlayerSessionEntity(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[PlayerSessionState, PlayerSessionEvent]:
  import PlayerSessionEvent.*

  def emptyState: PlayerSessionState = PlayerSessionState()

  def applyEvent(event: PlayerSessionEvent): PlayerSessionState = event match
    case e: Opened =>
      PlayerSessionState(e.playerId, e.realmSessionId, Some(e.at), None, e.networkAddress, e.userAgent, e.deviceFingerprint,
        SessionStatus.Active)
    case e: GameSessionAdded => currentState.copy(gameSessions = currentState.gameSessions :+ e.sessionId)
    case e: Ended            => currentState.copy(status = e.reason, endedAt = Some(e.at))

  /** Opens the session once; a repeat answers the state as it stands. */
  def open(c: OpenPlayerSession): Effect[PlayerSessionState] =
    if currentState.exists then effects.reply(currentState)
    else
      effects.persist(Opened(c.playerId, c.realmSessionId, Personal(c.networkAddress), Personal(c.userAgent),
        Personal(c.deviceFingerprint), c.at)).thenReply(identity)

  def addGameSession(c: AddGameSession): Effect[Boolean] =
    if !currentState.open then effects.reply(false)
    else if currentState.gameSessions.contains(c.sessionId) then effects.reply(true)
    else effects.persist(GameSessionAdded(c.sessionId)).thenReply(_ => true)

  /** Ends the session with the reason as its status; an ended or unknown session is no event. */
  def end(c: EndSession): Effect[SessionEnd] =
    val s = currentState
    if !SessionStatus.endings(c.reason) then effects.reply(SessionEnd(false, Vector.empty, Some("invalid_reason")))
    else if !s.open then effects.reply(SessionEnd(false, s.gameSessions))
    else effects.persist(Ended(c.reason, c.at)).thenReply(n => SessionEnd(true, n.gameSessions))

  def get: ReadOnlyEffect[PlayerSessionState] = effects.reply(currentState)

object PlayerSessionEntity
    extends EventSourcedEntity.Companion[PlayerSessionEntity, PlayerSessionState, PlayerSessionEvent](
      componentId = ComponentId("player-session"),
      stateSerializer = PlayersCodecs.serializer[PlayerSessionState]("player-session-state"),
      eventSerializer = PlayersCodecs.serializer[PlayerSessionEvent]("player-session-event")
    ):
  import com.thinkmorestupidless.ankka.core.Serializers.given

  given Serializer[PlayerSessionState] = PlayersCodecs.serializer[PlayerSessionState]("player-session-reply")
  given Serializer[OpenPlayerSession]  = PlayersCodecs.serializer[OpenPlayerSession]("open-player-session")
  given Serializer[AddGameSession]     = PlayersCodecs.serializer[AddGameSession]("add-game-session")
  given Serializer[EndSession]         = PlayersCodecs.serializer[EndSession]("end-session")
  given Serializer[SessionEnd]         = PlayersCodecs.serializer[SessionEnd]("session-end")

  def create(context: EventSourcedEntityContext) = new PlayerSessionEntity(context)

  val open           = command("open")(_.open)
  val addGameSession = command("add-game-session")(_.addGameSession)
  val end            = command("end")(_.end)
  val get            = query("get")(_.get)
