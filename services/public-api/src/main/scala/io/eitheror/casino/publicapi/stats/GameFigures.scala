package io.eitheror.casino.publicapi.stats

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.PublicApiCodecs
import io.eitheror.casino.publicapi.PublicApiCodecs.given_Serializer_Boolean

/** One game's figures in one slot (data-model.md `GameFigures`, keyed `<slot>/<gameCode>`). */
final case class GameFiguresState(slot: String = "", snapshotId: String = "", gameId: String = "", windows: Map[String, GameWindow] = Map.empty)

/** Empties a row of an older snapshot; a row already rewritten under `keep` is left as it is. */
final case class Gone(keep: String)

final class GameFigures(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[GameFiguresState]:
  def emptyState: GameFiguresState = GameFiguresState()
  def put(s: GameFiguresState): Effect[Boolean] = if s == currentState then effects.reply(false) else effects.updateState(s).thenReply(_ => true)
  /** Emptied rather than deleted, so the view sees the change and drops the row. */
  def get: ReadOnlyEffect[Option[GameFiguresState]] = effects.reply(Option(currentState).filter(_.gameId.nonEmpty))

  def remove(g: Gone): Effect[Boolean] = if currentState.gameId.isEmpty || currentState.snapshotId == g.keep then effects.reply(false) else effects.updateState(GameFiguresState()).thenReply(_ => true)

object GameFigures extends KeyValueEntity.Companion[GameFigures, GameFiguresState](
      componentId = ComponentId("game-figures"),
      stateSerializer = PublicApiCodecs.serializer[GameFiguresState]("game-figures")
    ):
  given Serializer[GameFiguresState] = PublicApiCodecs.serializer[GameFiguresState]("game-figures-put")
  given Serializer[Gone]             = PublicApiCodecs.serializer[Gone]("figures-gone")
  given Serializer[Option[GameFiguresState]] = PublicApiCodecs.serializer[Option[GameFiguresState]]("game-figures-reply")

  def key(slot: String, gameId: String): String = s"$slot/$gameId"

  def create(context: KeyValueEntityContext) = new GameFigures(context)

  val put    = command("put")(_.put)
  val remove = command("remove")(_.remove)
  val get    = query("get")(_.get)

final class GameFiguresUpdater extends View[GameFiguresState, GameFiguresState]:
  def onChange(s: GameFiguresState): Effect =
    if s.gameId.nonEmpty then effects.updateRow(s) else if rowState.isDefined then effects.deleteRow() else effects.ignore()

/** A snapshot's game figures (the reads filter by snapshot id, so a slot's older rows are never served). */
object GameFiguresView extends View.Companion[GameFiguresUpdater, GameFiguresState, GameFiguresState](
      componentId = ComponentId("game-figures-view"),
      source = ChangeSource.stateOf(GameFigures),
      rowSerializer = PublicApiCodecs.row[GameFiguresState]("game-figures-row")
    ):
  def create(ctx: ViewComponentContext) = new GameFiguresUpdater

  val ofSnapshot = query("of-snapshot")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'snapshotId' = :snapshot ORDER BY payload::jsonb->>'gameId' " +
      "LIMIT (:limit)::bigint OFFSET (:offset)::bigint")
  val staleInSlot = query("stale-in-slot")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'slot' = :slot AND payload::jsonb->>'snapshotId' <> :snapshot " +
      "ORDER BY payload::jsonb->>'gameId' LIMIT (:limit)::bigint OFFSET (:offset)::bigint")
