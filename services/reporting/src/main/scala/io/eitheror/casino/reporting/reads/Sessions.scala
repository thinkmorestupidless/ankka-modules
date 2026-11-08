package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewClient}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Generation, Session, SessionState}

import java.time.Instant

/**
 * A session as the dashboard and the dispute report find it (data-model.md `Sessions`): its kind (`player`, `game`),
 * player, start and end, close reason, a game session's game and login session. The network address and user agent
 * are not in the row: the dispute report reads them from the `Session` itself, so no query can reach them. `open`
 * is a column, so open sessions are counted without reading them.
 */
final case class SessionRow(
    generation: Int,
    sessionId: String = "",
    kind: String = "",
    player: String = "",
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    closeReason: Option[String] = None,
    gameId: Option[String] = None,
    playerSessionId: Option[String] = None,
    durationMinutes: Option[Int] = None,
    open: Boolean = false
)

object SessionRow:
  given Serializer[SessionRow] = ReportingCodecs.row[SessionRow]("session-row")

  def of(generation: Int, s: SessionState): SessionRow =
    SessionRow(generation, s.sessionId, s.kind, s.player, s.startedAt, s.endedAt, s.closeReason, s.gameId, s.playerSessionId,
      s.durationMinutes, s.open)

/** A key value entity's state is delivered at least once; the row is the state, so a repeat writes the same row. */
final class SessionRows extends View[SessionState, SessionRow]:
  def onChange(state: SessionState): Effect =
    Generation.of(updateContext.subject).fold(effects.ignore())(g => effects.updateRow(SessionRow.of(g, state)))

object Sessions extends View.Companion[SessionRows, SessionState, SessionRow](
      componentId = ComponentId("sessions"),
      source = ChangeSource.stateOf(Session),
      rowSerializer = SessionRow.given_Serializer_SessionRow
    ):
  /** A player's sessions started from `from` (inclusive) to `to` (exclusive). */
  val byPlayer = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'player' = :player AND (payload::jsonb->>'startedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'startedAt')::timestamptz < (:to)::timestamptz ORDER BY payload::jsonb->>'startedAt'")

  /** The game sessions of one login session. */
  val gameSessionsOf = query("game-sessions-of")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'kind' = 'game' AND payload::jsonb->>'playerSessionId' = :session ORDER BY payload::jsonb->>'startedAt'")

  /** The game sessions started from `from` (inclusive) to `to` (exclusive): the rankings' games (FR-014). */
  val gameStartsBetween = query("game-starts-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'kind' = 'game' AND (payload::jsonb->>'startedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'startedAt')::timestamptz < (:to)::timestamptz")

  def create(ctx: ViewComponentContext) = new SessionRows

  /**
   * How many sessions of `kind` are open (FR-013): players online for `player`, active games for `game`; a test account's
   * left out when `leaveOut` names it.
   */
  def openCount(views: ViewClient, generation: Int, kind: String, leaveOut: Set[String]): Long =
    val base = jsonText("generation") ++ sql" = ${generation.toString} AND " ++ jsonText("kind") ++ sql" = $kind AND " ++
      jsonText("open") ++ SqlFragment.raw(" = 'true'")
    val condition =
      if leaveOut.isEmpty then base
      else base ++ SqlFragment.raw(" AND NOT (") ++ jsonText("player") ++ sql" = ANY(string_to_array(${leaveOut.mkString(",")}, ',')))"
    views.forView(Sessions).count(condition)

  /** A player's sessions of every kind started from `from` (inclusive) to `to` (exclusive), by start. */
  def ofPlayer(views: ViewClient, generation: Int, player: String, from: Instant, to: Instant): Vector[SessionRow] =
    views.forView(Sessions).ask(byPlayer, 10_000_000, "generation" -> generation.toString, "player" -> player, "from" -> from.toString,
      "to" -> to.toString)

  /** A login session's game sessions, by start. */
  def gamesOf(views: ViewClient, generation: Int, session: String): Vector[SessionRow] =
    views.forView(Sessions).ask(gameSessionsOf, 10_000_000, "generation" -> generation.toString, "session" -> session)

  def gameStarts(views: ViewClient, generation: Int, from: Instant, to: Instant): Vector[SessionRow] =
    views.forView(Sessions).ask(gameStartsBetween, 10_000_000, "generation" -> generation.toString, "from" -> from.toString, "to" -> to.toString)
