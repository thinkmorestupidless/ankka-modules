package io.eitheror.casino.publicapi.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.publicapi.contract.{Contract, Refusal, Refusals}
import io.eitheror.casino.publicapi.games.Catalogue
import io.eitheror.casino.publicapi.stats.*
import io.eitheror.casino.publicapi.wire.{Money, Paging, Times}

/**
 * The four game operations (US5, original `handlers/games.ts`, `gameStats.ts`): the catalogue a page at a time and by
 * code; a game's figures from the current snapshot, zeros with no `computedAt` before the first run; and the ranking,
 * of available games only, at most 100, by one figure in one window.
 */
final class GameRoutes(brand: String, contract: Contract, catalogue: Catalogue, components: => ComponentClient, views: => ViewClient):
  private type Handler = Served => Respond[Bytes]
  private val mapper  = new ObjectMapper()
  private val Sorts   = Vector("rounds", "sessions", "players", "stake", "ggr")
  private val Max     = 100
  private val Ccy     = "^[A-Z]{3}$".r

  private def ok(json: String): Respond[Bytes] = Refusals.json(200, json)

  private def current: Option[(String, SlotState)] =
    components.forKeyValueEntity(EntityId(StatsPointer.Id)).call(StatsPointer.get).invoke().currentSlot

  private def figures(snapshot: String): Vector[GameFiguresState] =
    Iterator.iterate(0)(_ + 500).map(o => views.forView(GameFiguresView).ask(GameFiguresView.ofSnapshot, 500, "snapshot" -> snapshot,
      "limit" -> "500", "offset" -> o.toString)).takeWhile(_.nonEmpty).flatten.toVector

  private def required(s: Served, name: String, allowed: Vector[String]): Either[Refusal, String] =
    s.call.param(name) match
      case None                         => Left(Refusals.invalidRequest(s"$name is required"))
      case Some(v) if allowed.contains(v) => Right(v)
      case Some(_)                      => Left(Refusals.invalidRequest(s"$name must be one of ${allowed.mkString(", ")}"))

  private def rankLimit(s: Served): Either[Refusal, Int] = s.call.param("limit") match
    case None => Right(Max)
    case Some(raw) if !raw.matches("^[0-9]+$") || raw.toLongOption.forall(_ < 1) => Left(Refusals.invalidRequest("limit must be a positive integer"))
    case Some(raw) if raw.toLongOption.exists(_ > Max) => Left(Refusals.limitExceeded(Max))
    case Some(raw) => Right(raw.toInt)

  val handlers: Map[String, Handler] = Map(
    "listGames" -> (s => Paging.parse(s.call, contract.maxPageSize).fold(_.respond, pg =>
      val all = catalogue.available()
      ok(Paging.envelope(all.slice(pg.offset, pg.offset + pg.limit).map(g => mapper.writeValueAsString(g.view)), pg, all.size.toLong)))),
    "getGame" -> (s => catalogue.byCode(s.id("gameId")).fold(Refusals.notFound.respond)(g => ok(mapper.writeValueAsString(g.view)))),
    "getGameStats" -> (s => catalogue.byCode(s.id("gameId")).fold(Refusals.notFound.respond) { g =>
      val now = current
      val row = now.flatMap((slot, st) =>
        components.forKeyValueEntity(EntityId(GameFigures.key(slot, g.code))).call(GameFigures.get).invoke()
          .filter(r => st.snapshotId.contains(r.snapshotId)))
      ok(GameRoutes.stats(g.code, brand, now.flatMap(_._2.computedAt), row.map(_.windows).getOrElse(Map.empty)))
    }),
    "rankGames" -> (s =>
      (for
        period   <- required(s, "period", StatsRun.Periods.map(_._1))
        sort     <- required(s, "sort", Sorts)
        limit    <- rankLimit(s)
        currency <- s.call.param("currency").fold[Either[Refusal, Option[String]]](Right(None))(c =>
          Either.cond(Ccy.matches(c), Some(c), Refusals.invalidRequest("currency must be an ISO 4217 code")))
        _        <- Either.cond(!(sort == "stake" || sort == "ggr") || currency.isDefined, (), Refusals.invalidRequest(s"currency is required when sort is $sort"))
      yield (period, sort, limit, currency)).fold(_.respond, { (period, sort, limit, currency) =>
        val available = catalogue.available().map(_.code).toSet
        val now       = current
        val rows      = now.flatMap(_._2.snapshotId).fold(Vector.empty[GameFiguresState])(figures)
          .filter(r => available(r.gameId)).map(r => r.gameId -> r.windows.getOrElse(period, GameWindow()))
        val ranked = StatsRun.rankGames(rows, sort, currency, limit).map((g, w) => GameRoutes.row(g, period, w))
        val n = mapper.createObjectNode()
        val data = n.putArray("data"); ranked.foreach(data.add)
        now.flatMap(_._2.computedAt).foreach(t => n.put("computedAt", Times.of(t)))
        ok(mapper.writeValueAsString(n))
      }))
  )

object GameRoutes:
  private val mapper = new ObjectMapper()

  private def money(c: Long, ccy: String): String = Money.of(c, ccy).getOrElse(Money.cents(c))

  def window(n: ObjectNode, w: GameWindow): ObjectNode =
    n.put("rounds", w.rounds).put("sessions", w.sessions).put("uniquePlayers", w.uniquePlayers)
    val a = n.putArray("amounts")
    w.amounts.foreach(x => a.addObject().put("currency", x.currency).put("stake", money(x.stakeCents, x.currency))
      .put("win", money(x.winCents, x.currency)).put("ggr", money(x.ggrCents, x.currency)))
    n

  /** The contract's `GameStats`: every window, zeros where the game had no play or no run has reached it. */
  def stats(gameId: String, brand: String, computedAt: Option[java.time.Instant], windows: Map[String, GameWindow]): String =
    val n = mapper.createObjectNode().put("gameId", gameId).put("brandId", brand)
    computedAt.foreach(t => n.put("computedAt", Times.of(t)))
    val ws = n.putObject("windows")
    StatsRun.Periods.foreach((p, _) => window(ws.putObject(p), windows.getOrElse(p, GameWindow())))
    mapper.writeValueAsString(n)

  def row(gameId: String, period: String, w: GameWindow): ObjectNode =
    window(mapper.createObjectNode().put("gameId", gameId).put("period", period), w)
