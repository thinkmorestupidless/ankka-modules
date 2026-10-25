package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.{PlayersCodecs, PlayersRuntime}
import io.eitheror.casino.players.player.{PlayerEntity, SessionId}

import scala.concurrent.duration.*

/** The game session a limit timer ends, and its player. */
final case class LimitDue(sessionId: String, playerId: String)

object LimitDue:
  given Serializer[LimitDue] = PlayersCodecs.serializer[LimitDue]("limit-due")

/**
 * The game-session limit (FR-016, research R5): one single timer per open game session, named
 * `game-session-limit/<sessionId>`, due when the day's minutes run out. Firing ends the game session
 * with reason `limit` and tells the player; a session already ended is no change, so a late or repeated
 * firing is harmless. A new limit reschedules every open session's timer under the same name.
 */
final class SessionLimits(context: TimedActionContext) extends TimedAction:
  def end(due: LimitDue): Effect =
    val at = PlayersRuntime.now()
    context.componentClient.forEventSourcedEntity(EntityId(due.sessionId)).call(GameSessionEntity.end)
      .invoke(EndGameSession("limit", at)): Unit
    context.componentClient.forEventSourcedEntity(EntityId(due.playerId)).call(PlayerEntity.gameSessionEnded)
      .invoke(SessionId(due.sessionId, at)): Unit
    effects.done()

object SessionLimits extends TimedAction.Companion[SessionLimits](ComponentId("session-limits")):
  def create(context: TimedActionContext) = new SessionLimits(context)

  val endSession = handler("end")(_.end)

  def name(sessionId: String): String = s"game-session-limit/$sessionId"

  /** Due in `minutesLeft` (at once when none are left); a later call under the same name replaces it. */
  def schedule(scheduler: TimerScheduler, sessionId: String, playerId: String, minutesLeft: Int): Unit =
    scheduler.createSingleTimer(name(sessionId), math.max(0, minutesLeft).minutes, endSession.deferred(LimitDue(sessionId, playerId)))

  def cancel(scheduler: TimerScheduler, sessionId: String): Unit = scheduler.delete(name(sessionId))
