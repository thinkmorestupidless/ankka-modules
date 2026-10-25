package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

/**
 * Both kinds of session, one row each (research R11): a player session (`kind` `player`, its realm
 * session as `parent`) and a game session (`kind` `game`, its player session as `parent`). Times in
 * epoch milliseconds. Nothing personal: the network address and agent stay on the entity.
 */
final case class SessionRow(
    sessionId: String,
    kind: String,
    playerId: String,
    parent: String = "",
    gameId: Option[String] = None,
    status: String = "",
    startedAt: Long = 0L,
    endedAt: Option[Long] = None,
    durationMinutes: Option[Long] = None,
    reason: Option[String] = None
):
  def open: Boolean = endedAt.isEmpty

object SessionRow:
  val Player = "player"
  val Game   = "game"

final class SessionsViewRows extends KeyedView[SessionRow]:

  def onPlayerSession(event: PlayerSessionEvent, change: Change): Effect =
    val key = SessionsView.key(SessionRow.Player, change.subject)
    event match
      case e: PlayerSessionEvent.Opened =>
        effects.updateRow(key, SessionRow(change.subject, SessionRow.Player, e.playerId, e.realmSessionId,
          status = SessionStatus.Active, startedAt = e.at.toEpochMilli))
      case e: PlayerSessionEvent.Ended =>
        change.rows.get(key).fold(effects.ignore())(r =>
          effects.updateRow(key, r.copy(status = e.reason, endedAt = Some(e.at.toEpochMilli), reason = Some(e.reason))))
      case _: PlayerSessionEvent.GameSessionAdded => effects.ignore()

  def onGameSession(event: GameSessionEvent, change: Change): Effect =
    val key = SessionsView.key(SessionRow.Game, change.subject)
    event match
      case e: GameSessionEvent.Opened =>
        effects.updateRow(key, SessionRow(change.subject, SessionRow.Game, e.playerId, e.playerSessionId, Some(e.gameId),
          GameSessionState.Active, e.at.toEpochMilli))
      case e: GameSessionEvent.Ended =>
        change.rows.get(key).fold(effects.ignore()) { r =>
          val minutes = math.max(0L, (e.at.toEpochMilli - r.startedAt) / 60000L)
          effects.updateRow(key, r.copy(status = GameSessionState.Ended, endedAt = Some(e.at.toEpochMilli),
            durationMinutes = Some(minutes), reason = Some(e.reason)))
        }

object SessionsView extends KeyedView.Companion[SessionsViewRows, SessionRow](
      ComponentId("sessions"),
      PlayersCodecs.serializer[SessionRow]("session-row")
    ):
  def create(ctx: ViewComponentContext) = new SessionsViewRows

  def key(kind: String, sessionId: String): String = s"$kind/$sessionId"

  val playerSessions = source(ChangeSource.eventsOf(PlayerSessionEntity))(_.onPlayerSession)
  val gameSessions   = source(ChangeSource.eventsOf(GameSessionEntity))(_.onGameSession)

  private val kind    = "payload::jsonb->>'kind'"
  private val player  = "payload::jsonb->>'playerId'"
  private val parent  = "COALESCE(payload::jsonb->>'parent', '')"
  private val isOpen  = "payload::jsonb->'endedAt' IS NULL"
  private val started = "COALESCE((payload::jsonb->>'startedAt')::bigint, 0)"

  /** A player's sessions of one kind, newest first. */
  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE $player = :player AND $kind = :kind ORDER BY $started DESC, row_key ASC OFFSET CAST(:offset AS int)")

  /** Every open player session: the reconciler's set (R4). */
  val openPlayerSessions: DeclaredQuery = query("open-player-sessions")(
    s"SELECT payload FROM $table WHERE $kind = 'player' AND $isOpen ORDER BY $started ASC, row_key ASC")

  /** A player's open game sessions: what a new limit reschedules (R5). */
  val openGameSessionsOf: DeclaredQuery = query("open-game-sessions-of")(
    s"SELECT payload FROM $table WHERE $kind = 'game' AND $player = :player AND $isOpen ORDER BY $started ASC, row_key ASC")

  /** The player session a realm session is mirrored by. */
  val byRealmSession: DeclaredQuery = query("by-realm-session")(
    s"SELECT payload FROM $table WHERE $kind = 'player' AND $parent = :realm ORDER BY $started DESC LIMIT 1")

  def ofPlayer(views: ViewClient, playerId: String, kind: String, offset: Int, limit: Int): Vector[SessionRow] =
    views.forView(SessionsView).ask(byPlayer, limit, "player" -> playerId, "kind" -> kind, "offset" -> offset.max(0).toString)

  def open(views: ViewClient, limit: Int = 1000): Vector[SessionRow] = views.forView(SessionsView).ask(openPlayerSessions, limit)

  def openGames(views: ViewClient, playerId: String): Vector[SessionRow] =
    views.forView(SessionsView).ask(openGameSessionsOf, 1000, "player" -> playerId)

  def ofRealm(views: ViewClient, realmSessionId: String): Option[SessionRow] =
    views.forView(SessionsView).ask(byRealmSession, 1, "realm" -> realmSessionId).headOption
