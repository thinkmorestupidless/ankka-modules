package io.eitheror.casino.players.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.player.Done

import java.time.Instant

/** The last time a player played a game (FR-029), one per player and game, from `casino.rounds`. */
final case class RecentlyPlayedRow(playerId: String, gameId: String, lastPlayedAt: Long):
  def exists: Boolean = playerId.nonEmpty

/** One player's last play of one game, under `<player>/<game>`; an older round changes nothing. */
final class RecentlyPlayed(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[RecentlyPlayedRow]:
  def emptyState: RecentlyPlayedRow = RecentlyPlayedRow("", "", 0L)

  def touch(row: RecentlyPlayedRow): Effect[Done] =
    if currentState.lastPlayedAt >= row.lastPlayedAt then effects.reply(Done.unchanged) else effects.updateState(row).thenReply(_ => Done(changed = true))

  def erase: Effect[Done] = if currentState.exists then effects.deleteEntity().thenReply(_ => Done(changed = true)) else effects.reply(Done.unchanged)

object RecentlyPlayed
    extends KeyValueEntity.Companion[RecentlyPlayed, RecentlyPlayedRow](
      componentId = ComponentId("recently-played-game"),
      stateSerializer = PlayersCodecs.serializer[RecentlyPlayedRow]("recently-played-row")
    ):
  given Serializer[RecentlyPlayedRow] = PlayersCodecs.serializer[RecentlyPlayedRow]("recently-played-touch")
  given Serializer[Done]              = PlayersCodecs.serializer[Done]("done")

  def create(context: KeyValueEntityContext) = new RecentlyPlayed(context)

  val touchGame = command("touch")(_.touch)
  val erase     = command("erase")(_.erase)

  def key(playerId: String, gameId: String): String = s"$playerId/$gameId"

  def touch(client: ComponentClient, playerId: String, gameId: String, at: Instant): Unit =
    client.forKeyValueEntity(EntityId(key(playerId, gameId))).call(touchGame).invoke(RecentlyPlayedRow(playerId, gameId, at.toEpochMilli)): Unit

final class RecentlyPlayedViewRows extends View[RecentlyPlayedRow, RecentlyPlayedRow]:
  def onChange(state: RecentlyPlayedRow): Effect = if state.exists then effects.updateRow(state) else effects.ignore()
  override def onDelete: Effect = effects.deleteRow()

object RecentlyPlayedView extends View.Companion[RecentlyPlayedViewRows, RecentlyPlayedRow, RecentlyPlayedRow](
      componentId = ComponentId("recently-played"),
      source = ChangeSource.stateOf(RecentlyPlayed),
      rowSerializer = PlayersCodecs.serializer[RecentlyPlayedRow]("recently-played-row")
    ):
  def create(ctx: ViewComponentContext) = new RecentlyPlayedViewRows

  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player " +
      "ORDER BY (payload::jsonb->>'lastPlayedAt')::bigint DESC, row_key ASC")

  def ofPlayer(views: ViewClient, playerId: String, limit: Int): Vector[RecentlyPlayedRow] =
    views.forView(RecentlyPlayedView).ask(byPlayer, limit, "player" -> playerId)
