package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.players.player.PlayerEntity
import io.eitheror.casino.players.sessions.{EndReason, PlayerSessionEntity, SessionEndWorkflow}

import scala.concurrent.duration.*

/**
 * What a suspension, an exclusion or a closure does to a player's sessions (FR-031, SC-003): every open
 * player session ended with the reason through `SessionEndWorkflow`, its game sessions first, awaited
 * under `SESSION_END_BOUND`, then the realm's session revoked. The open set is the entity's, not a view's,
 * so a session opened a moment ago is not missed.
 */
object Cascade:
  def endAll(ctx: Ctx, playerId: String, reason: String): Int =
    val components = ctx.clients.componentClient
    val open  = components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke().openPlayerSessions.toVector.sorted
    val bound = ctx.settings.sessionEndBound.toMillis.millis
    open.foreach { sessionId =>
      val realmSession = components.forEventSourcedEntity(EntityId(sessionId)).call(PlayerSessionEntity.get).invoke().realmSessionId
      SessionEndWorkflow.end(components, sessionId, reason, Some(bound)): Unit
      if realmSession.nonEmpty then
        ctx.neighbours.realm.revokeSession(realmSession) match
          case Left(r)  => log.warn("{} of {}: the realm session was not revoked: {}", reason, playerId, r.code)
          case Right(_) => ()
    }
    open.size

  val Suspended: String = EndReason.Suspended
  val Forced: String    = EndReason.Forced

  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.players.api.Cascade")
