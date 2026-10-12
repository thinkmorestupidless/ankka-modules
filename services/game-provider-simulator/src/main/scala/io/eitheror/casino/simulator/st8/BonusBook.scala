package io.eitheror.casino.simulator.st8

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** One player's allocation of a provisioned bonus: `active`, then `used` at zero, or `canceled`. */
final case class Instance(status: String, remaining: Int)

/** One `bonus/create` (FR-007): the stand-in's `storedBonus` (`main.go:94-113`). */
final case class Bonus(
    bonusId: String,
    players: Vector[String],
    count: Int,
    value: String,
    bonusType: String,
    currency: String,
    gameCodes: Vector[String],
    site: String,
    instances: Map[String, Instance]
)

/** What a free spin drew: the bonus, its currency, and what remains for the player. */
final case class Spent(bonusId: String, currency: String, remaining: Int)

/**
 * Every provisioned bonus, in one key-value entity (id `book`). The stand-in keeps one process-wide
 * map; a free spin searches it for the player's usable instance, which one entity answers without a
 * view. A second create under the same id replaces the first, as the stand-in's does. A free spin
 * takes the lowest bonus id with a usable instance, where the stand-in takes whichever its map
 * iteration finds first (research R7).
 */
final class BonusBook extends KeyValueEntity[Map[String, Bonus]]:

  def emptyState: Map[String, Bonus] = Map.empty

  def create(bonus: Bonus): Effect[Bonus] =
    effects.updateState(currentState.updated(bonus.bonusId, bonus)).thenReply(_ => bonus)

  def get(bonusId: String): ReadOnlyEffect[Bonus] =
    currentState.get(bonusId).fold(effects.error[Bonus]("bonus not found", ErrorCode.NotFound))(effects.reply(_))

  def cancel(request: BonusBook.Cancel): Effect[Bonus] =
    currentState.get(request.bonusId).filter(_.instances.contains(request.player)) match
      case None => effects.error("bonus or player not found", ErrorCode.NotFound)
      case Some(b) =>
        val updated = b.copy(instances = b.instances.updated(request.player, b.instances(request.player).copy(status = "canceled")))
        effects.updateState(currentState.updated(b.bonusId, updated)).thenReply(_ => updated)

  def spend(request: BonusBook.Spend): Effect[Spent] =
    currentState.values.toVector.sortBy(_.bonusId).find { b =>
      b.instances.get(request.player).exists(i => i.status == "active" && i.remaining > 0) && b.gameCodes.contains(request.gameCode)
    } match
      case None => effects.error("no_free_rounds", ErrorCode.Conflict)
      case Some(b) =>
        val before    = b.instances(request.player)
        val remaining = before.remaining - 1
        val after     = Instance(if remaining <= 0 then "used" else before.status, remaining)
        val updated   = b.copy(instances = b.instances.updated(request.player, after))
        effects.updateState(currentState.updated(b.bonusId, updated))
          .thenReply(_ => Spent(b.bonusId, if b.currency.isEmpty then request.sessionCurrency else b.currency, remaining))

object BonusBook
    extends KeyValueEntity.Companion[BonusBook, Map[String, Bonus]](
      componentId = ComponentId("bonus-book"),
      stateSerializer = Codecs.serializer[Map[String, Bonus]]("bonus-book")
    ):
  final case class Cancel(bonusId: String, player: String)
  final case class Spend(player: String, gameCode: String, sessionCurrency: String)

  given Serializer[Bonus]  = Codecs.serializer[Bonus]("bonus")
  given Serializer[Cancel] = Codecs.serializer[Cancel]("bonus-cancel")
  given Serializer[Spend]  = Codecs.serializer[Spend]("bonus-spend")
  given Serializer[Spent]  = Codecs.serializer[Spent]("bonus-spent")

  val Id = "book"

  def create(context: KeyValueEntityContext) = new BonusBook

  val createBonus = command("create")(_.create)
  val get         = query("get")(_.get)
  val cancel      = command("cancel")(_.cancel)
  val spend       = command("spend")(_.spend)
