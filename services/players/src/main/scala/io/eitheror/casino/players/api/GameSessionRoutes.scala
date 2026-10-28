package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, SessionId}
import io.eitheror.casino.players.sessions.*

import java.util.UUID
import scala.util.Try

import Answers.*

/**
 * Game sessions (FR-016 to FR-022; research R5): `game-provider` opens one for a launch, the player reads
 * and ends their own, an operator or the support agent ends one by force. Every end goes through
 * `GameSessionEntity.End`, which is no change on an ended session, then tells the player.
 */
final class GameSessionRoutes(ctx: Ctx):
  private def components             = ctx.clients.componentClient
  private def player(id: String)     = components.forEventSourcedEntity(PlayerEntity.id(id))
  private def game(id: String)       = components.forEventSourcedEntity(EntityId(id))
  private def playerSession(id: String) = components.forEventSourcedEntity(EntityId(id))

  private def stateOf(playerId: String): PlayerState = player(playerId).call(PlayerEntity.get).invoke()
  private def gameOf(sessionId: String): GameSessionState = game(sessionId).call(GameSessionEntity.get).invoke()

  // ── ServiceEndpoint ────────────────────────────────────────────────────────────────────────────

  /**
   * `POST /{playerId}/game-sessions` for `game-provider` (FR-017 to FR-019): the player's status and an
   * open player session, then the day's limit; the game session under the player session; the timer
   * when a limit is set. No outbound call (SC-007).
   */
  def open(playerId: String, text: String): Reply =
    val body = Json.parse(text).filter(_.isObject).getOrElse(Json.obj())
    body.text("gameId") match
      case None => refusal(400, "missing_fields", "gameId is required", "fields" -> Json.arr("gameId"))
      case Some(gameId) =>
        val p = stateOf(playerId)
        if !p.exists then refusal(404, "player_not_found", "No such player")
        else
          val parent = body.text("playerSessionId").orElse(p.openPlayerSessions.toVector.sorted.lastOption)
          val live   = parent.filter { id =>
            val s = playerSession(id).call(PlayerSessionEntity.get).invoke()
            s.open && s.playerId == playerId
          }
          live match
            case None if p.status == io.eitheror.casino.players.player.Status.Active =>
              refusal(409, "session_expired", "The player session has ended")
            case _ =>
              val sessionId = UUID.randomUUID().toString
              val at        = PlayersRuntime.now()
              val answer    = player(playerId).call(PlayerEntity.gameSessionOpened).invoke(SessionId(sessionId, at))
              answer.refusal match
                case Some(r) if r.code == "session_expired" => refusal(409, r.code, "The player session has ended")
                case Some(r) if r.code == "session_limit_reached" =>
                  refusal(403, r.code, "The session limit for today has been reached",
                    "limitMinutes" -> r.details.get("limitMinutes").flatMap(_.toIntOption).getOrElse(0),
                    "usedMinutes" -> r.details.get("usedMinutes").flatMap(_.toIntOption).getOrElse(0))
                case Some(r) => refusal(403, r.code, "The account is restricted", r.details.toSeq*)
                case None =>
                  val under    = live.get
                  val currency = body.text("currency").getOrElse(p.currency)
                  game(sessionId).call(GameSessionEntity.open)
                    .invoke(OpenGameSession(playerId, under, gameId, currency, body.bool("demo").getOrElse(false), at)): Unit
                  playerSession(under).call(PlayerSessionEntity.addGameSession).invoke(AddGameSession(sessionId)): Unit
                  answer.minutesLeft.foreach(left => SessionLimits.schedule(PlayersRuntime.timers(), sessionId, playerId, left))
                  json(201, Json.obj("sessionId" -> sessionId))

  /** `GET /{playerId}/status` for `game-provider` and `support-agent`. */
  def status(playerId: String): Reply =
    val p = stateOf(playerId)
    if !p.exists then refusal(404, "player_not_found", "No such player")
    else ok(Json.obj("status" -> p.status, "displayName" -> p.profile.get("displayName").orNull,
      "verification" -> (if p.kycVerified then "verified" else "unverified")))

  /** `GET /{playerId}/game-sessions` for `support-agent`: the history, newest first. */
  def history(playerId: String, offset: Option[String]): Reply =
    ok(Json.obj("sessions" -> sessions(playerId, offset.flatMap(_.toIntOption).getOrElse(0))))

  /** `POST /game-sessions/{sessionId}/end` for `support-agent`: forced, the approval named as the actor. */
  def endByAgent(sessionId: String, text: String): Reply =
    val approval = Json.parse(text).flatMap(_.text("approvalId")).getOrElse("support-agent")
    val s        = gameOf(sessionId)
    if !s.exists then refusal(404, "session_not_found", "No such session")
    else
      end(sessionId, s, "forced", Some(approval))
      ok(Json.obj("sessionId" -> sessionId, "status" -> GameSessionState.Ended))

  // ── SessionEndpoint ────────────────────────────────────────────────────────────────────────────

  /** `GET /players/{playerId}` (the original's `GET ?playerId=`): the player's own game sessions. */
  def mine(p: Principal, playerId: String): Reply =
    PlayerCaller.matching(p, playerId)(_ => ok(Json.obj("sessions" -> sessions(playerId, 0))))

  /** `GET /{sessionId}`: the session, when it is the caller's; another's, or none, is `404` alike. */
  def one(p: Principal, sessionId: String): Reply =
    val s = gameOf(sessionId)
    if s.exists && PlayerCaller.of(p).playerId.contains(s.playerId) then ok(GameSessionRoutes.wire(sessionId, s))
    else refusal(404, "session_not_found", "No such session")

  /** `PATCH /{sessionId}/end`: the player ends their own; idempotent, as the original's `completed`. */
  def endMine(p: Principal, sessionId: String): Reply =
    val s = gameOf(sessionId)
    if !s.exists then refusal(404, "session_not_found", "No such session")
    else if !PlayerCaller.of(p).playerId.contains(s.playerId) then
      refusal(403, "player_id_mismatch", "Authenticated player does not match requested player ID")
    else
      val after = end(sessionId, s, "player", None).state
      ok(Json.obj("status" -> "completed", "sessionId" -> sessionId, "durationMinutes" -> after.durationMinutes.getOrElse(0L)))

  // ── OperatorEndpoint ───────────────────────────────────────────────────────────────────────────

  /** `POST /players/{playerId}/sessions/{sessionId}/force-end`: the operator named on the end and its audit record. */
  def forceEnd(p: Principal, playerId: String, sessionId: String): Reply =
    val s = gameOf(sessionId)
    if !s.exists || s.playerId != playerId then refusal(404, "session_not_found", "No such session")
    else
      end(sessionId, s, "forced", Some(p.subject))
      ok(Json.obj("sessionId" -> sessionId, "status" -> GameSessionState.Ended))

  /** `POST /players/{playerId}/sessions/force-end-all`: every open game session of the player; how many ended. */
  def forceEndAll(p: Principal, playerId: String): Reply =
    val open  = stateOf(playerId).todaysGameSessions.filter(_.endedAt.isEmpty).map(_.sessionId)
    val ended = open.count(id => end(id, gameOf(id), "forced", Some(p.subject)).ended)
    ok(Json.obj("ended" -> ended))

  // ──────────────────────────────────────────────────────────────────────────────────────────────

  /** Ends the game session (no change when it has ended), tells the player, and drops its timer. */
  private def end(sessionId: String, s: GameSessionState, reason: String, actor: Option[String]): GameSessionEnd =
    val at     = PlayersRuntime.now()
    val answer = game(sessionId).call(GameSessionEntity.end).invoke(EndGameSession(reason, at, actor))
    if s.playerId.nonEmpty then player(s.playerId).call(PlayerEntity.gameSessionEnded).invoke(SessionId(sessionId, at)): Unit
    Try(SessionLimits.cancel(PlayersRuntime.timers(), sessionId))
    answer

  private def sessions(playerId: String, offset: Int) =
    val rows = SessionsView.ofPlayer(PlayersRuntime.views(), playerId, SessionRow.Game, offset, 100)
    Json.arr(rows.map(GameSessionRoutes.wire)*)

object GameSessionRoutes:
  /** The original's session body: the status `completed` for a player's own end, `ended` for any other. */
  def status(state: String, reason: Option[String]): String =
    if state == GameSessionState.Ended && reason.contains("player") then "completed" else state

  def wire(sessionId: String, s: GameSessionState): ObjectNode =
    Json.obj("sessionId" -> sessionId, "gameId" -> s.gameId, "status" -> status(s.status, s.endReason),
      "launchedAt" -> s.startedAt.map(_.toString).getOrElse(""), "endedAt" -> s.endedAt.map(_.toString).orNull,
      "durationMinutes" -> s.durationMinutes.map(Long.box).orNull, "reason" -> s.endReason.orNull, "lastBetAt" -> null)

  def wire(r: SessionRow): ObjectNode =
    Json.obj("sessionId" -> r.sessionId, "gameId" -> r.gameId.getOrElse(""), "status" -> status(r.status, r.reason),
      "launchedAt" -> java.time.Instant.ofEpochMilli(r.startedAt).toString,
      "endedAt" -> r.endedAt.map(java.time.Instant.ofEpochMilli(_).toString).orNull,
      "durationMinutes" -> r.durationMinutes.map(Long.box).orNull, "reason" -> r.reason.orNull, "lastBetAt" -> null)
