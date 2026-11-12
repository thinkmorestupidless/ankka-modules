package io.eitheror.casino.publicapi.stats

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.PublicApiCodecs
import io.eitheror.casino.publicapi.PublicApiCodecs.given_Serializer_Boolean

import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql

import java.time.Instant

/**
 * One player's figures in one slot (data-model.md `PlayerFigures`, keyed `<slot>/<playerId>`): the status at the run,
 * as the contract names it, the last activity, the three windows and every rank the player has. No identifying field.
 */
final case class PlayerFiguresState(slot: String = "", snapshotId: String = "", playerId: String = "", status: String = "",
    lastActiveAt: Option[Instant] = None, windows: Map[String, PlayerWindow] = Map.empty, ranks: Map[String, Int] = Map.empty)

final class PlayerFigures(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[PlayerFiguresState]:
  def emptyState: PlayerFiguresState = PlayerFiguresState()
  def put(s: PlayerFiguresState): Effect[Boolean] = if s == currentState then effects.reply(false) else effects.updateState(s).thenReply(_ => true)
  def get: ReadOnlyEffect[Option[PlayerFiguresState]] = effects.reply(Option(currentState).filter(_.playerId.nonEmpty))
  /** Emptied rather than deleted, so the view drops the row; a row rewritten under `keep` is left. */
  def remove(g: Gone): Effect[Boolean] =
    if currentState.playerId.isEmpty || currentState.snapshotId == g.keep then effects.reply(false)
    else effects.updateState(PlayerFiguresState()).thenReply(_ => true)

object PlayerFigures extends KeyValueEntity.Companion[PlayerFigures, PlayerFiguresState](
      componentId = ComponentId("player-figures"),
      stateSerializer = PublicApiCodecs.serializer[PlayerFiguresState]("player-figures")
    ):
  given Serializer[PlayerFiguresState]         = PublicApiCodecs.serializer[PlayerFiguresState]("player-figures-put")
  given Serializer[Gone]                       = PublicApiCodecs.serializer[Gone]("player-figures-gone")
  given Serializer[Option[PlayerFiguresState]] = PublicApiCodecs.serializer[Option[PlayerFiguresState]]("player-figures-reply")

  def key(slot: String, playerId: String): String = s"$slot/$playerId"

  def create(context: KeyValueEntityContext) = new PlayerFigures(context)

  val put    = command("put")(_.put)
  val remove = command("remove")(_.remove)
  val get    = query("get")(_.get)

final class PlayerFiguresUpdater extends View[PlayerFiguresState, PlayerFiguresState]:
  def onChange(s: PlayerFiguresState): Effect =
    if s.playerId.nonEmpty then effects.updateRow(s) else if rowState.isDefined then effects.deleteRow() else effects.ignore()

/**
 * A snapshot's player figures. A ranking is the rows holding the rank, in rank order, optionally of some statuses
 * only, so each keeps its rank in the whole ranking (FR-030). A rank is a JSON number, so it orders numerically.
 */
object PlayerFiguresView extends View.Companion[PlayerFiguresUpdater, PlayerFiguresState, PlayerFiguresState](
      componentId = ComponentId("player-figures-view"),
      source = ChangeSource.stateOf(PlayerFigures),
      rowSerializer = PublicApiCodecs.row[PlayerFiguresState]("player-figures-row")
    ):
  def create(ctx: ViewComponentContext) = new PlayerFiguresUpdater

  private val ranked =
    "payload::jsonb->>'snapshotId' = :snapshot AND jsonb_extract_path_text(payload::jsonb->'ranks', :rank) IS NOT NULL " +
      "AND ((:statuses) = '' OR payload::jsonb->>'status' = ANY(string_to_array(:statuses, ',')))"

  val ranking = query("ranking")(
    s"SELECT payload FROM $table WHERE $ranked ORDER BY jsonb_extract_path_text(payload::jsonb->'ranks', :rank)::int LIMIT (:limit)::bigint OFFSET (:offset)::bigint")
  val ofSnapshot = query("of-snapshot")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'snapshotId' = :snapshot ORDER BY row_key LIMIT (:limit)::bigint OFFSET (:offset)::bigint")
  val staleInSlot = query("stale-in-slot")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'slot' = :slot AND payload::jsonb->>'snapshotId' <> :snapshot " +
      "ORDER BY row_key LIMIT (:limit)::bigint OFFSET (:offset)::bigint")

  /** The ranking's total, under the same condition as `ranking`. */
  def total(snapshot: String, rank: String, statuses: Vector[String]): SqlFragment =
    val base = sql"payload::jsonb->>'snapshotId' = $snapshot AND (payload::jsonb->'ranks'->>$rank) IS NOT NULL"
    if statuses.isEmpty then base else base ++ sql" AND payload::jsonb->>'status' = ANY(string_to_array(${statuses.mkString(",")}, ','))"

