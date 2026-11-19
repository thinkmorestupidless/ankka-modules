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
    endReason: Option[String] = None,
    /** The latest round opened or settled under the session (spec 027 FR-057); none before the first bet. */
    lastBetAt: Option[Instant] = None
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
  /** Carries the player, the game and the whole minutes, so a reader of the event alone has the fact. */
  final case class Ended(reason: String, at: Instant, playerId: String = "", gameId: String = "", durationMinutes: Long = 0L,
      actorId: Option[String] = None)
      extends GameSessionEvent
  /** A round opened or settled under the session at `at`, later than any before it (spec 027 FR-057). */
  final case class BetRecorded(at: Instant) extends GameSessionEvent

final case class OpenGameSession(playerId: String, playerSessionId: String, gameId: String, currency: String, demo: Boolean, at: Instant)
/** `actorId`: the operator or approval that forced it, for the audit record (FR-021). */
final case class EndGameSession(reason: String, at: Instant, actorId: Option[String] = None)
/** A bet's time, from a round's fact on `casino.rounds`. */
final case class RecordBet(at: Instant)

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
    case e: BetRecorded => currentState.copy(lastBetAt = Some(e.at))

  def open(c: OpenGameSession): Effect[GameSessionState] =
    if currentState.exists then effects.reply(currentState)
    else effects.persist(Opened(c.playerId, c.playerSessionId, c.gameId, c.currency, c.demo, c.at)).thenReply(identity)

  /** Ends the game session; an ended or unknown one is no event (`session_not_found` is the route's). */
  def end(c: EndGameSession): Effect[GameSessionEnd] =
    if !GameEndReason.all(c.reason) then effects.reply(GameSessionEnd(false, currentState, Some("invalid_reason")))
    else if !currentState.open then effects.reply(GameSessionEnd(false, currentState))
    else
      val s = currentState
      val minutes = s.startedAt.fold(0L)(start => math.max(0L, Duration.between(start, c.at).toMinutes))
      effects.persist(Ended(c.reason, c.at, s.playerId, s.gameId, minutes, c.actorId)).thenReply(n => GameSessionEnd(true, n))

  /**
   * Keeps the latest bet's time to within 30 seconds (FR-057): an unknown session, a time no later than
   * the one held (a fact delivered again, or out of order), or one less than 30 seconds after it, is
   * no event, so a session's journal grows by at most two events a minute of play, not one per round. An ended
   * session still records a round settled after it.
   */
  def recordBet(c: RecordBet): Effect[GameSessionState] =
    if !currentState.exists || currentState.lastBetAt.exists(held => c.at.isBefore(held.plus(GameSessionEntity.BetPrecision))) then
      effects.reply(currentState)
    else effects.persist(BetRecorded(c.at)).thenReply(identity)

  def get: ReadOnlyEffect[GameSessionState] = effects.reply(currentState)

object GameSessionEntity
    extends EventSourcedEntity.Companion[GameSessionEntity, GameSessionState, GameSessionEvent](
      componentId = ComponentId("game-session"),
      stateSerializer = PlayersCodecs.serializer[GameSessionState]("game-session-state"),
      eventSerializer = PlayersCodecs.serializer[GameSessionEvent]("game-session-event")
    ):

  /**
   * How close the last bet's time is kept. The SDK asks every 5 minutes whether a bet fell within the
   * last 6, a minute's margin for consumer lag and clocks; half of it is left (second review round).
   */
  val BetPrecision: Duration = Duration.ofSeconds(30)

  given Serializer[GameSessionState] = PlayersCodecs.serializer[GameSessionState]("game-session-reply")
  given Serializer[OpenGameSession]  = PlayersCodecs.serializer[OpenGameSession]("open-game-session")
  given Serializer[EndGameSession]   = PlayersCodecs.serializer[EndGameSession]("end-game-session")
  given Serializer[GameSessionEnd]   = PlayersCodecs.serializer[GameSessionEnd]("game-session-end")
  given Serializer[RecordBet]        = PlayersCodecs.serializer[RecordBet]("record-bet")

  def create(context: EventSourcedEntityContext) = new GameSessionEntity(context)

  val open = command("open")(_.open)
  val end  = command("end")(_.end)
  val recordBet = command("record-bet")(_.recordBet)
  val get  = query("get")(_.get)
