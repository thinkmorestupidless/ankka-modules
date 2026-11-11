package io.eitheror.casino.provider.edge.operator

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** Which brand created a bonus, on which game: all the provider keeps of free rounds (research R9). */
final case class BonusRoute(bonusId: String, brand: String, code: String, owner: String, createdAt: Long)

/**
 * The bonus index, one entry per bonus id. The game service holds the instances, their counts and
 * the claim deadline; the provider only routes a brand's `bonus/get` and `bonus/cancel` to it, and
 * answers a bonus another brand created as one that does not exist.
 */
final class BonusIndex(context: KeyValueEntityContext) extends KeyValueEntity[Option[BonusRoute]]:

  def emptyState: Option[BonusRoute] = None

  def put(route: BonusRoute): Effect[BonusRoute] =
    currentState match
      case Some(held) if held.brand != route.brand || held.code != route.code =>
        effects.error(s"bonus_taken: ${context.entityId} is a bonus of ${held.brand}", ErrorCode.Conflict)
      case Some(held) => effects.reply(held)
      case None       => effects.updateState(Some(route)).thenReply(_ => route)

  def get: ReadOnlyEffect[BonusRoute] =
    currentState.fold(effects.error[BonusRoute]("bonus not found", ErrorCode.NotFound))(effects.reply(_))

object BonusIndex
    extends KeyValueEntity.Companion[BonusIndex, Option[BonusRoute]](
      componentId = ComponentId("bonus-index"),
      stateSerializer = Codecs.serializer[Option[BonusRoute]]("bonus-index-state")
    ):
  given Serializer[BonusRoute] = Codecs.serializer[BonusRoute]("bonus-route")
  def create(context: KeyValueEntityContext) = new BonusIndex(context)
  val put = command("put")(_.put)
  val get = query("get")(_.get)
