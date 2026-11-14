package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import io.eitheror.casino.publicapi.contract.{Contract, Refusals}
import io.eitheror.casino.publicapi.records.*
import io.eitheror.casino.publicapi.wire.{Page, Paging, Records as Wire}

/**
 * The 13 record operations (US3): by id from each view, and each collection as one page of its view in data-model.md's
 * order. Parameters are checked before any read; a collection's parent is read first, and an absent parent is
 * `404 not_found` before any child query (original `playerCollections.test.ts`).
 */
final class RecordRoutes(brand: String, contract: Contract, views: => ViewClient):
  private type Handler = Served => Respond[Bytes]

  private def ok(json: String): Respond[Bytes] = Refusals.json(200, json)
  private def paged(s: Served)(f: Page => Respond[Bytes]): Respond[Bytes] = Paging.parse(s.call, contract.maxPageSize).fold(_.respond, f)

  private def player(s: Served, p: PlayerState): String =
    val (json, fields) = Wire.player(p, brand, s.key.identifyingFields)
    s.disclosed += (p.id -> fields)
    json

  private def parent[R](row: Option[R])(f: R => Respond[Bytes]): Respond[Bytes] = row.fold(Refusals.notFound.respond)(f)

  private def page[R](rows: Vector[R], page: Page, total: Long)(render: R => String): Respond[Bytes] =
    ok(Paging.envelope(rows.map(render), page, total))

  val handlers: Map[String, Handler] = Map(
    "listPlayers" -> (s => paged(s) { pg =>
      val q = views.forView(Players)
      page(Views.page(q, Players.listed, pg), pg, q.count())(player(s, _))
    }),
    "getPlayer" -> (s => parent(views.forView(Players).get(s.id("playerId")))(p => ok(player(s, p)))),
    "listPlayerSessions" -> child(Sessions, Sessions.ofPlayer, "player")(Wire.session(_, brand)),
    "listPlayerRounds" -> child(Rounds, Rounds.ofPlayer, "player")(Wire.round(_, brand)),
    "listPlayerBets" -> child(Bets, Bets.ofPlayer, "player")(Wire.bet(_, brand)),
    "listPlayerTransactions" -> child(Transactions, Transactions.ofPlayer, "player")(Wire.transaction(_, brand)),
    "listPlayerBonusAwards" -> child(BonusAwards, BonusAwards.ofPlayer, "player")(Wire.bonusAward(_, brand)),
    "getSession" -> (s => parent(views.forView(Sessions).get(s.id("sessionId")))(r => ok(Wire.session(r, brand)))),
    "listSessionRounds" -> (s => paged(s) { pg =>
      parent(views.forView(Sessions).get(s.id("sessionId"))) { session =>
        val q = views.forView(Rounds)
        page(Views.page(q, Rounds.ofSession, pg, "session" -> session.id), pg, q.count(Views.by("sessionId", session.id)))(Wire.round(_, brand))
      }
    }),
    "getRound" -> (s => parent(views.forView(Rounds).get(s.id("roundId")))(r => ok(Wire.round(r, brand)))),
    "listRoundBets" -> (s => paged(s) { pg =>
      parent(views.forView(Rounds).get(s.id("roundId"))) { round =>
        val q = views.forView(Bets)
        page(Views.page(q, Bets.ofRound, pg, "round" -> round.id), pg, q.count(Views.by("roundId", round.id)))(Wire.bet(_, brand))
      }
    }),
    "getBet" -> (s => parent(views.forView(Bets).get(s.id("betId")))(r => ok(Wire.bet(r, brand)))),
    "getTransaction" -> (s => parent(views.forView(Transactions).get(s.id("transactionId")))(r => ok(Wire.transaction(r, brand)))),
    "getBonusAward" -> (s => parent(views.forView(BonusAwards).get(s.id("bonusAwardId")))(r => ok(Wire.bonusAward(r, brand))))
  )

  private def child[V <: com.thinkmorestupidless.ankka.sdk.View[S, R], S, R](
      view: com.thinkmorestupidless.ankka.sdk.View.Companion[V, S, R], query: com.thinkmorestupidless.ankka.sdk.DeclaredQuery, param: String
  )(render: R => String): Handler = s =>
    paged(s) { pg =>
      parent(views.forView(Players).get(s.id("playerId"))) { p =>
        val q = views.forView(view)
        page(Views.page(q, query, pg, param -> p.id), pg, q.count(Views.by("playerId", p.id)))(render)
      }
    }
