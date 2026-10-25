package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

import java.time.{Duration, Instant}

/** Why a game session ended (data-model.md). */
object GameEndReason:
  val all: Set[String] = Set("player", "limit", "forced", "logout", "expired", "suspended", "superseded")

/** A game session (FR-016): the game launched in a player session, and its whole minutes once ended. */
final case class GameSessionState(
    playerId: String = "",
    playerSessionId: String = "",
    gameId: String = "",
    currency: String = "",
    demo: Boolean = false,
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    durationMinutes: Option[Long] = None,
    status: String = "",
    endReason: Option[String] = None
):
  def exists: Boolean = status.nonEmpty
  def open: Boolean   = status == GameSessionState.Active

object GameSessionState:
  val Active = "active"
  val Ended  = "ended"

sealed trait GameSessionEvent
object GameSessionEvent:
  final case class Opened(playerId: String, playerSessionId: String, gameId: String, currency: String, demo: Boolean, at: Instant)
      extends GameSessionEvent
  final case class Ended(reason: String, at: Instant) extends GameSessionEvent

final case class OpenGameSession(playerId: String, playerSessionId: String, gameId: String, currency: String, demo: Boolean, at: Instant)
final case class EndGameSession(reason: String, at: Instant)

/** What an end answered: whether this call ended it, and the state after. */
final case class GameSessionEnd(ended: Boolean, state: GameSessionState, refusal: Option[String] = None)

final class GameSessionEntity(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[GameSessionState, GameSessionEvent]:
  import GameSessionEvent.*

  def emptyState: GameSessionState = GameSessionState()

  def applyEvent(event: GameSessionEvent): GameSessionState = event match
    case e: Opened =>
      GameSessionState(e.playerId, e.playerSessionId, e.gameId, e.currency, e.demo, Some(e.at), status = GameSessionState.Active)
    case e: Ended =>
      val s = currentState
      s.copy(status = GameSessionState.Ended, endedAt = Some(e.at), endReason = Some(e.reason),
        durationMinutes = s.startedAt.map(start => math.max(0L, Duration.between(start, e.at).toMinutes)))

  def open(c: OpenGameSession): Effect[GameSessionState] =
    if currentState.exists then effects.reply(currentState)
    else effects.persist(Opened(c.playerId, c.playerSessionId, c.gameId, c.currency, c.demo, c.at)).thenReply(identity)

  /** Ends the game session; an ended or unknown one is no event (`session_not_found` is the route's). */
  def end(c: EndGameSession): Effect[GameSessionEnd] =
    if !GameEndReason.all(c.reason) then effects.reply(GameSessionEnd(false, currentState, Some("invalid_reason")))
    else if !currentState.open then effects.reply(GameSessionEnd(false, currentState))
    else effects.persist(Ended(c.reason, c.at)).thenReply(n => GameSessionEnd(true, n))

  def get: ReadOnlyEffect[GameSessionState] = effects.reply(currentState)

object GameSessionEntity
    extends EventSourcedEntity.Companion[GameSessionEntity, GameSessionState, GameSessionEvent](
      componentId = ComponentId("game-session"),
      stateSerializer = PlayersCodecs.serializer[GameSessionState]("game-session-state"),
      eventSerializer = PlayersCodecs.serializer[GameSessionEvent]("game-session-event")
    ):

  given Serializer[GameSessionState] = PlayersCodecs.serializer[GameSessionState]("game-session-reply")
  given Serializer[OpenGameSession]  = PlayersCodecs.serializer[OpenGameSession]("open-game-session")
  given Serializer[EndGameSession]   = PlayersCodecs.serializer[EndGameSession]("end-game-session")
  given Serializer[GameSessionEnd]   = PlayersCodecs.serializer[GameSessionEnd]("game-session-end")

  def create(context: EventSourcedEntityContext) = new GameSessionEntity(context)

  val open = command("open")(_.open)
  val end  = command("end")(_.end)
  val get  = query("get")(_.get)
