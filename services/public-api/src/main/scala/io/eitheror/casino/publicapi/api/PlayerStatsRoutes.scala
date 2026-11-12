package io.eitheror.casino.publicapi.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.publicapi.contract.{Contract, Refusal, Refusals}
import io.eitheror.casino.publicapi.records.Players
import io.eitheror.casino.publicapi.stats.*
import io.eitheror.casino.publicapi.wire.{Money, Paging, Times}

/**
 * The two player figure operations (US6, original `handlers/playerStats.ts`): a ranking paged through one snapshot,
 * the current one or the one named, `410 snapshot_expired` for a snapshot in neither ready slot (research R8); and one
 * player's figures from the current snapshot, zeros where the player had no activity. No identifying field, whatever the
 * key allows.
 */
final class PlayerStatsRoutes(contract: Contract, components: => ComponentClient, views: => ViewClient):
  private type Handler = Served => Respond[Bytes]
  private val mapper   = new ObjectMapper()
  private val Ccy      = "^[A-Z]{3}$".r
  private val Uuid     = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r
  private val Statuses = Vector("ACTIVE", "SUSPENDED", "CLOSED", "SELF_EXCLUDED")

  private def ok(n: ObjectNode): Respond[Bytes] = Refusals.json(200, mapper.writeValueAsString(n))
  private def pointer = components.forKeyValueEntity(EntityId(StatsPointer.Id)).call(StatsPointer.get).invoke()

  private def required(s: Served, name: String, allowed: Vector[String]): Either[Refusal, String] = s.call.param(name) match
    case None                           => Left(Refusals.invalidRequest(s"$name is required"))
    case Some(v) if allowed.contains(v) => Right(v)
    case Some(_)                        => Left(Refusals.invalidRequest(s"$name must be one of ${allowed.mkString(", ")}"))

  /** Comma-separated statuses, each the contract's, in first-seen order without repeats. */
  private def statuses(s: Served): Either[Refusal, Vector[String]] = s.call.param("status").fold(Right(Vector.empty)) { raw =>
    val xs = raw.split(",", -1).toVector
    Either.cond(xs.forall(Statuses.contains), xs.distinct,
      Refusals.invalidRequest(s"status must be a comma-separated list of ${Statuses.mkString(", ")}"))
  }

  private def snapshotOf(slot: SlotState, into: ObjectNode): Unit =
    into.putObject("snapshot").put("id", slot.snapshotId.getOrElse("")).put("computedAt", slot.computedAt.fold("")(Times.of))

  val handlers: Map[String, Handler] = Map(
    "rankPlayers" -> (s =>
      (for
        period <- required(s, "period", StatsRun.Periods.map(_._1))
        sort   <- required(s, "sort", StatsRun.PlayerSorts)
        raw    <- s.call.param("currency").fold[Either[Refusal, Option[String]]](Right(None))(c =>
          Either.cond(Ccy.matches(c), Some(c), Refusals.invalidRequest("currency must be an ISO 4217 code")))
        _      <- Either.cond(!StatsRun.MoneySorts(sort) || raw.isDefined, (), Refusals.invalidRequest(s"currency is required when sort is $sort"))
        sts    <- statuses(s)
        named  <- s.call.param("snapshot").fold[Either[Refusal, Option[String]]](Right(None))(n =>
          Either.cond(Uuid.matches(n), Some(n.toLowerCase), Refusals.invalidRequest("snapshot must be a snapshot id")))
        page   <- Paging.parse(s.call, contract.maxPageSize)
        p       = pointer
        slot   <- named.fold[Either[Refusal, Option[(String, SlotState)]]](Right(p.currentSlot))(n => p.ready(n).map(Some(_)).toRight(Refusals.snapshotExpired))
      yield (period, sort, raw.filter(_ => StatsRun.MoneySorts(sort)), sts, page, slot)).fold(_.respond, { (period, sort, currency, sts, page, slot) =>
        val rank = StatsRun.rankKey(period, sort, currency)
        val (rows, total) = slot.flatMap(_._2.snapshotId).fold((Vector.empty[PlayerFiguresState], 0L)) { snap =>
          val q = views.forView(PlayerFiguresView)
          (q.ask(PlayerFiguresView.ranking, page.limit, "snapshot" -> snap, "rank" -> rank, "statuses" -> sts.mkString(","),
            "limit" -> page.limit.toString, "offset" -> page.offset.toString), q.count(PlayerFiguresView.total(snap, rank, sts)))
        }
        val body = mapper.readTree(Paging.envelope(rows.map(r => mapper.writeValueAsString(PlayerStatsRoutes.entry(r, period, rank))), page, total))
          .asInstanceOf[ObjectNode]
        slot.foreach((_, st) => snapshotOf(st, body))
        val r = body.putObject("ranking").put("period", period).put("sort", sort)
        currency.foreach(r.put("currency", _))
        if sts.nonEmpty then { val a = r.putArray("status"); sts.foreach(a.add) }
        ok(body)
      })),
    "getPlayerStats" -> (s =>
      views.forView(Players).get(s.id("playerId")).fold(Refusals.notFound.respond) { pl =>
        val current = pointer.currentSlot
        val row = current.flatMap((slot, st) =>
          components.forKeyValueEntity(EntityId(PlayerFigures.key(slot, pl.id))).call(PlayerFigures.get).invoke()
            .filter(r => st.snapshotId.contains(r.snapshotId)))
        val n = mapper.createObjectNode().put("playerId", pl.id)
        current.foreach((_, st) => snapshotOf(st, n))
        row.flatMap(_.lastActiveAt).fold(n.putNull("lastActiveAt"))(t => n.put("lastActiveAt", Times.of(t)))
        val ws = n.putObject("windows")
        StatsRun.Periods.foreach((p, _) => PlayerStatsRoutes.window(ws.putObject(p), row.flatMap(_.windows.get(p)).getOrElse(PlayerWindow())))
        ok(n)
      })
  )

object PlayerStatsRoutes:
  private val mapper = new ObjectMapper()

  private def money(c: Long, ccy: String): String = Money.of(c, ccy).getOrElse(Money.cents(c))

  def window(n: ObjectNode, w: PlayerWindow): ObjectNode =
    n.put("rounds", w.rounds).put("sessions", w.sessions).put("games", w.games)
    val a = n.putArray("amounts")
    w.amounts.foreach { x =>
      val e = a.addObject().put("currency", x.currency).put("stake", money(x.stakeCents, x.currency)).put("win", money(x.winCents, x.currency))
        .put("ggr", money(x.ggrCents, x.currency))
      e.putObject("deposits").put("count", x.deposits).put("amount", money(x.depositCents, x.currency))
      e.putObject("withdrawals").put("count", x.withdrawals).put("amount", money(x.withdrawalCents, x.currency))
    }
    n

  def entry(r: PlayerFiguresState, period: String, rank: String): ObjectNode =
    window(mapper.createObjectNode().put("rank", r.ranks.getOrElse(rank, 0)).put("playerId", r.playerId).put("status", r.status),
      r.windows.getOrElse(period, PlayerWindow()))
