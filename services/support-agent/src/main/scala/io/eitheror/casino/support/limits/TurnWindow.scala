package io.eitheror.casino.support.limits

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.support.{Stored, SupportCodecs}

/**
 * A player's turns in the last minute (data-model.md `TurnWindow`, id = `<slug>/<playerId>`; FR-024, R10). An entity,
 * not instance memory, so the limit holds across nodes. A refused turn is not counted.
 */
final class TurnWindow(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[TurnWindowState]:
  def emptyState: TurnWindowState = TurnWindowState()

  def take(t: TakeTurn): Effect[Take] =
    val (next, taken) = Rate.take(currentState, t.at, t.limit)
    effects.updateState(next).thenReply(_ => taken)

object TurnWindow
    extends KeyValueEntity.Companion[TurnWindow, TurnWindowState](
      componentId = ComponentId("support-turn-window"),
      stateSerializer = Stored.turnWindow
    ):
  given Serializer[TakeTurn] = SupportCodecs.serializer[TakeTurn]("turn-window-take")
  given Serializer[Take]     = SupportCodecs.serializer[Take]("turn-window-taken")

  def id(slug: String, playerId: String): EntityId = EntityId(s"$slug/$playerId")

  def create(context: KeyValueEntityContext) = new TurnWindow(context)

  val take = command("take")(_.take)
