package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.player.PlayerEntity
import io.eitheror.casino.players.rounds.*
import io.eitheror.casino.players.sessions.*

import java.time.{Instant, LocalDate}
import scala.concurrent.duration.*
import scala.util.Try

import Answers.*

/**
 * An operator's reads of a player's sessions and rounds (spec 030 P5, P6, P10; FR-023): a player's sessions
 * with their game sessions and any gap in the data, the brand's player sessions, a player session ended by
 * force, a game session's bets, and the player's bets found by round, transaction, type and status with what
 * was staked, won and netted. The rounds are `casino.rounds`' facts ([[RoundsView]]).
 */
final class OperatorSessionRoutes(ctx: Ctx):
  import OperatorSessionRoutes.*

  private def components = ctx.clients.componentClient
  private def views      = ctx.clients.viewClient

  private def known(playerId: String)(f: => Reply): Reply =
    if !components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke().exists then
      refusal(404, "player_not_found", "No such player")
    else f

  // ── sessions ───────────────────────────────────────────────────────────────────────────────────

  /**
   * `GET players/{p}/player-sessions`: the player's sessions, newest first, each with its game sessions (newest
   * first), each of those with its rounds' count, stake, win and net, what the wallet took in bets during it, and
   * `gap` when the two differ (bets with no round recorded, or a round with no bet: the original's data-gap
   * warning), else `null`. `{sessions, total, page, pageSize}`.
   */
  def playerSessions(playerId: String, page: Option[String], pageSize: Option[String]): Reply =
    known(playerId) {
      val (n, size) = paging(page, pageSize)
      val wanted    = SessionsView.Wanted(playerId = Some(playerId))
      val rows      = SessionsView.playerSessions(views, wanted, (n - 1) * size, size)
      ok(Json.obj("sessions" -> Json.arr(rows.map(r => withGames(playerSession(r), r.sessionId))*),
        "total" -> SessionsView.playerSessionsTotal(views, wanted), "page" -> n, "pageSize" -> size))
    }

  private def withGames(o: ObjectNode, playerSessionId: String): ObjectNode =
    Json.set(o, "gameSessions", Json.arr(SessionsView.gamesOf(views, playerSessionId).map(gameSession)*))
    o

  private def gameSession(r: SessionRow): ObjectNode =
    val o       = GameSessionRoutes.wire(r)
    val rounds  = RoundsView.all(views, RoundsView.Wanted(sessionId = Some(r.sessionId)))
    val wagers  = SessionWagers.of(components, r.sessionId)
    val totals  = RoundTotals.of(rounds)
    val currency = totals.headOption.map(_.currency).orElse(Option(wagers.currency).filter(_.nonEmpty))
    val staked  = rounds.map(_.stake).sum
    o.put("rounds", rounds.size)
    o.put("stakedMinorUnits", staked)
    o.put("wonMinorUnits", rounds.map(_.win).sum)
    o.put("netMinorUnits", rounds.map(_.net).sum)
    o.put("wageredMinorUnits", wagers.wagered)
    Json.set(o, "currency", currency.orNull)
    Json.set(o, "gap",
      if wagers.wagered == staked then null
      else Json.obj("wageredMinorUnits" -> wagers.wagered, "roundStakeMinorUnits" -> staked, "currency" -> currency.orNull))
    o

  /**
   * `GET sessions`: the brand's player sessions, newest first: `status` (`active`, `ended`, or an end's status:
   * `completed`, `expired`, `forced`, `suspended`, `superseded`; anything else is no filter), `from` and `to` on
   * the start (as the activity's), and L. `{sessions, total, page, pageSize}`, each session without its games.
   */
  def brandSessions(status: Option[String], from: Option[String], to: Option[String], page: Option[String], pageSize: Option[String]): Reply =
    val f = from.flatMap(OperatorStatusRoutes.bound(_, end = false))
    val t = to.flatMap(OperatorStatusRoutes.bound(_, end = true))
    if f.zip(t).exists((a, b) => a >= b) then refusal(400, "invalid_date_range", "from is not before to")
    else
      val (n, size) = paging(page, pageSize)
      val st        = status.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(SessionFilters)
      val wanted    = SessionsView.Wanted(status = st, from = f, to = t)
      val rows      = SessionsView.playerSessions(views, wanted, (n - 1) * size, size)
      ok(Json.obj("sessions" -> Json.arr(rows.map(playerSession)*), "total" -> SessionsView.playerSessionsTotal(views, wanted),
        "page" -> n, "pageSize" -> size))

  /**
   * `POST players/{p}/player-sessions/{sid}/force-close` (`session.end`): every game session under it ended
   * `forced` naming the operator, then the session, awaited under `SESSION_END_BOUND`, then the realm's session
   * revoked. A session already ended is answered as it stands, ending nothing. `{sessionId, status, ended}`.
   */
  def forceClose(p: Principal, playerId: String, sessionId: String): Reply =
    val ref = components.forEventSourcedEntity(EntityId(sessionId))
    val s   = ref.call(PlayerSessionEntity.get).invoke()
    if !s.exists || s.playerId != playerId then refusal(404, "session_not_found", "No such session")
    else if !s.open then ok(Json.obj("sessionId" -> sessionId, "status" -> s.status, "ended" -> false))
    else
      SessionEndWorkflow.end(components, sessionId, EndReason.Forced, Some(ctx.settings.sessionEndBound.toMillis.millis), Some(p.subject)): Unit
      if s.realmSessionId.nonEmpty then
        ctx.neighbours.realm.revokeSession(s.realmSessionId) match
          case Left(r)  => log.warn("force-close of {}: the realm session was not revoked: {}", sessionId, r.code)
          case Right(_) => ()
      val after = ref.call(PlayerSessionEntity.get).invoke()
      ok(Json.obj("sessionId" -> sessionId, "status" -> after.status, "ended" -> !after.open))

  private def playerSession(r: SessionRow): ObjectNode =
    Json.obj("sessionId" -> r.sessionId, "playerId" -> r.playerId, "status" -> r.status,
      "startedAt" -> Instant.ofEpochMilli(r.startedAt).toString, "endedAt" -> r.endedAt.map(Instant.ofEpochMilli(_).toString).orNull,
      "reason" -> r.reason.orNull)

  // ── rounds ─────────────────────────────────────────────────────────────────────────────────────

  /**
   * `GET players/{p}/sessions/{sid}/bets` (spec 030 P6, was `501`): the game session's rounds, newest first,
   * every status, each with its bet and win; `{sessionId, bets, total, page, pageSize, summary}`. A game session
   * that is not the player's is `404 session_not_found`.
   */
  def sessionBets(playerId: String, sessionId: String, page: Option[String], pageSize: Option[String]): Reply =
    val g = components.forEventSourcedEntity(EntityId(sessionId)).call(GameSessionEntity.get).invoke()
    if !g.exists || g.playerId != playerId then refusal(404, "session_not_found", "No such session")
    else
      val (n, size) = paging(page, pageSize)
      val wanted    = RoundsView.Wanted(playerId = Some(playerId), sessionId = Some(sessionId))
      val all       = RoundsView.all(views, wanted)
      ok(Json.obj("sessionId" -> sessionId, "bets" -> Json.arr(RoundsView.page(views, wanted, (n - 1) * size, size).map(bet)*),
        "total" -> all.size, "page" -> n, "pageSize" -> size, "summary" -> totals(all)))

  /**
   * `GET players/{p}/bets`: the player's rounds as before (`fromDate`, `toDate` as ISO dates, `limit` 50,
   * `offset`; `400 invalid_date_range`), with spec 030 P10's `roundId` (the round's id or its provider's),
   * `transactionId` (any of its ids), `type` (`bet`, a stake; `win`, a win) and `status` (`open`, `settled`,
   * `cancelled`, `all`; absent, every settled round, cancelled ones too, as before). `summary` gains `staked`,
   * `won`, `net` and `currency` when the rounds are in one currency, and `totals` per currency.
   */
  def bets(playerId: String, q: BetsQuery): Reply =
    val f = q.fromDate.flatMap(d => Try(LocalDate.parse(d.take(10))).toOption)
    val t = q.toDate.flatMap(d => Try(LocalDate.parse(d.take(10))).toOption)
    if f.zip(t).exists((a, b) => a.isAfter(b)) then refusal(400, "invalid_date_range", "fromDate is after toDate")
    else
      known(playerId) {
        val lim  = q.limit.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(50)
        val off  = q.offset.flatMap(_.toIntOption).filter(_ >= 0).getOrElse(0)
        def day(d: LocalDate) = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant.toEpochMilli
        val statuses = q.status.map(_.trim.toLowerCase(java.util.Locale.ROOT)) match
          case None | Some("")                        => Set(RoundStatus.Settled, RoundStatus.Cancelled)
          case Some("all")                            => Set.empty[String]
          case Some(s) if RoundStatus.all.contains(s) => Set(s)
          case Some(_)                                => Set(RoundStatus.Settled, RoundStatus.Cancelled)
        val wanted = RoundsView.Wanted(playerId = Some(playerId), statuses = statuses,
          roundId = q.roundId.map(_.trim).filter(_.nonEmpty), transactionId = q.transactionId.map(_.trim).filter(_.nonEmpty),
          kind = q.kind.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(Set("bet", "win")),
          from = f.map(day), to = t.map(d => day(d.plusDays(1))))
        val all  = RoundsView.all(views, wanted)
        val page = RoundsView.page(views, wanted, off, lim)
        val summary = totals(all)
        summary.put("totalBets", all.size)
        Json.set(summary, "fromDate", q.fromDate.orNull)
        Json.set(summary, "toDate", q.toDate.orNull)
        ok(Json.obj("bets" -> Json.arr(page.map(bet)*), "summary" -> summary, "total" -> all.size, "limit" -> lim, "offset" -> off))
      }

  /** `staked`, `won`, `net` and `currency` for one currency (`null`s for several, never summed across), and `totals` per currency. */
  private def totals(rows: Vector[RoundRow]): ObjectNode =
    val per = RoundTotals.of(rows)
    val one = Option.when(per.size <= 1)(per.headOption.getOrElse(RoundTotals("", 0, 0L, 0L, 0L)))
    Json.obj("staked" -> one.map(t => Long.box(t.staked)).orNull, "won" -> one.map(t => Long.box(t.won)).orNull,
      "net" -> one.map(t => Long.box(t.net)).orNull, "currency" -> one.map(_.currency).filter(_.nonEmpty).orNull,
      "totals" -> Json.arr(per.map(t => Json.obj("currency" -> t.currency, "rounds" -> t.rounds, "staked" -> t.staked, "won" -> t.won,
        "net" -> t.net))*))

  /** A round as a bet: the original's fields first (`amountMinorUnits` the net, as before), then the round's own. */
  private def bet(r: RoundRow): ObjectNode =
    def at(ms: Option[Long]) = ms.map(Instant.ofEpochMilli(_).toString).orNull
    Json.obj("betId" -> r.roundId, "amountMinorUnits" -> Long.box(r.net), "currency" -> r.currency,
      "description" -> r.outcome.fold("Round open")(o => s"Round settled: $o"), "createdAt" -> Instant.ofEpochMilli(r.at).toString,
      "roundId" -> r.roundId, "providerRoundId" -> r.providerRoundId, "provider" -> r.provider, "gameId" -> r.gameId,
      "sessionId" -> r.sessionId, "status" -> r.status, "outcome" -> r.outcome.orNull, "stakeMinorUnits" -> r.stake,
      "winMinorUnits" -> r.win, "netMinorUnits" -> r.net, "freeRounds" -> r.freeRoundsGrant.nonEmpty,
      "transactionIds" -> Json.arr(r.transactionIds*), "providerTransactionIds" -> Json.arr(r.providerTransactionIds*),
      "openedAt" -> at(r.openedAt), "settledAt" -> at(r.settledAt))

  private def paging(page: Option[String], pageSize: Option[String]): (Int, Int) =
    (page.flatMap(_.trim.toIntOption).filter(n => n >= 1 && n <= 1_000_000).getOrElse(1),
      pageSize.flatMap(_.trim.toIntOption).filter(s => s >= 1 && s <= 200).getOrElse(25))

object OperatorSessionRoutes:
  /** The `status` values the brand's sessions list filters by. */
  val SessionFilters: Set[String] = SessionStatus.endings + SessionStatus.Active + "ended"

  final case class BetsQuery(fromDate: Option[String] = None, toDate: Option[String] = None, limit: Option[String] = None,
      offset: Option[String] = None, roundId: Option[String] = None, transactionId: Option[String] = None,
      kind: Option[String] = None, status: Option[String] = None)

  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.players.api.OperatorSessionRoutes")
