package io.eitheror.casino.rg.usage

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rg.RgCodecs

import java.time.{Duration, Instant, ZoneOffset}

/**
 * A player's game sessions started on one UTC day (R8): the minutes of those closed and the start of those
 * open. A session counts on the day it started only, in whole minutes, as the original's
 * `calculateSessionMinutesToday`. `sessions` names every session of the day (FR-024).
 */
final case class SessionDay(closedMinutes: Long = 0, open: Map[String, Instant] = Map.empty, sessions: Set[String] = Set.empty):
  def minutesUsed(now: Instant): Long =
    closedMinutes + open.values.map(s => SessionDay.wholeMinutes(s, now)).sum

final case class SessionStarted(sessionId: String, at: Instant)
final case class SessionEnded(sessionId: String, at: Instant)
final case class DayAt(now: Instant)
final case class DayAnswer(minutesUsed: Long, sessions: Set[String])

object SessionDay:
  def wholeMinutes(from: Instant, to: Instant): Long = math.max(0L, Duration.between(from, to).toMinutes)

/** Keyed `player/yyyy-mm-dd`. */
final class SessionDayEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[SessionDay]:
  def emptyState: SessionDay = SessionDay()

  def started(c: SessionStarted): Effect[Boolean] =
    if currentState.sessions(c.sessionId) then effects.reply(false)
    else effects.updateState(currentState.copy(open = currentState.open + (c.sessionId -> c.at), sessions = currentState.sessions + c.sessionId))
      .thenReply(_ => true)

  /** An end of a session this day did not see start (it started on another day) is no change. */
  def ended(c: SessionEnded): Effect[Boolean] =
    currentState.open.get(c.sessionId) match
      case None => effects.reply(false)
      case Some(start) =>
        effects.updateState(currentState.copy(closedMinutes = currentState.closedMinutes + SessionDay.wholeMinutes(start, c.at),
          open = currentState.open - c.sessionId)).thenReply(_ => true)

  def get(c: DayAt): ReadOnlyEffect[DayAnswer] = effects.reply(DayAnswer(currentState.minutesUsed(c.now), currentState.sessions))

object SessionDayEntity
    extends KeyValueEntity.Companion[SessionDayEntity, SessionDay](
      componentId = ComponentId("rg-session-day"),
      stateSerializer = RgCodecs.serializer[SessionDay]("session-day")
    ):
  given Serializer[SessionStarted] = RgCodecs.serializer[SessionStarted]("session-started")
  given Serializer[SessionEnded]   = RgCodecs.serializer[SessionEnded]("session-ended")
  given Serializer[DayAt]          = RgCodecs.serializer[DayAt]("day-at")
  given Serializer[DayAnswer]      = RgCodecs.serializer[DayAnswer]("day-answer")
  given Serializer[Boolean]        = RgCodecs.serializer[Boolean]("day-changed")

  def create(context: KeyValueEntityContext) = new SessionDayEntity(context)

  def id(playerId: String, at: Instant): String = s"$playerId/${at.atOffset(ZoneOffset.UTC).toLocalDate}"

  val started = command("started")(_.started)
  val ended   = command("ended")(_.ended)
  val get     = query("get")(_.get)
