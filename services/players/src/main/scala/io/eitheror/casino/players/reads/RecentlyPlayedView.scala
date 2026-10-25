package io.eitheror.casino.players.reads

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{CasinoRounds, CasinoRoundsFact, RoundSettled}
import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.facts.Lenient

/** The last time a player played a game (FR-029), one row per player and game, from `casino.rounds`. */
final case class RecentlyPlayedRow(playerId: String, gameId: String, lastPlayedAt: Long)

final class RecentlyPlayedViewRows extends KeyedView[RecentlyPlayedRow]:
  def onRound(fact: Option[CasinoRoundsFact], change: Change): Effect = fact match
    case Some(r: RoundSettled) =>
      val key = RecentlyPlayedView.key(r.playerId, r.gameId)
      val at  = r.occurredAt.toEpochMilli
      if change.rows.get(key).exists(_.lastPlayedAt >= at) then effects.ignore()
      else effects.updateRow(key, RecentlyPlayedRow(r.playerId, r.gameId, at))
    case _ => effects.ignore()

object RecentlyPlayedView extends KeyedView.Companion[RecentlyPlayedViewRows, RecentlyPlayedRow](
      ComponentId("recently-played"),
      PlayersCodecs.serializer[RecentlyPlayedRow]("recently-played-row")
    ):
  def create(ctx: ViewComponentContext) = new RecentlyPlayedViewRows

  def key(playerId: String, gameId: String): String = s"$playerId/$gameId"

  val rounds = source(ChangeSource.fromTopic(CasinoRounds.name, Lenient.of(CasinoRounds), StartFrom.Earliest,
    TopicOptions(Some(CasinoRounds.contract), None, false)))(_.onRound)

  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player " +
      "ORDER BY (payload::jsonb->>'lastPlayedAt')::bigint DESC, row_key ASC")

  def ofPlayer(views: ViewClient, playerId: String, limit: Int): Vector[RecentlyPlayedRow] =
    views.forView(RecentlyPlayedView).ask(byPlayer, limit, "player" -> playerId)
