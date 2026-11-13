package io.eitheror.casino.provider.edge.registry

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** A registered game (data-model "Game"): what the catalogue shows and where its page is. */
final case class Game(
    code: String,
    owner: String,
    name: String,
    category: String,
    developer: String,
    bonusTypes: Vector[String],
    features: Vector[String],
    themes: Vector[String],
    restrictedTerritories: Vector[String],
    prohibitedTerritories: Vector[String],
    deprecationDate: Option[String],
    demo: Boolean,
    pageAddress: String,
    registeredAt: Instant,
    updatedAt: Instant
)

object Game:
  /** Every house game's developer, as the catalogue names it. */
  val Developer = "eitheror"

/**
 * One game of the registry, id its code (spec 026 FR-007). The owner is the game service that
 * registered it: a second registration by the same owner updates the game and keeps when it was
 * first registered; one by another owner is refused naming the holder (`code_taken`). The owner
 * deregisters its own game; an operator removes any.
 */
final class GameEntity extends KeyValueEntity[Option[Game]]:

  def emptyState: Option[Game] = None

  def register(game: Game): Effect[Game] =
    currentState match
      case Some(held) if held.owner != game.owner =>
        effects.error(s"${GameEntity.CodeTaken}: ${held.owner} holds ${held.code}", ErrorCode.Conflict)
      case held =>
        val kept = game.copy(developer = Game.Developer, registeredAt = held.fold(game.registeredAt)(_.registeredAt))
        effects.updateState(Some(kept)).thenReply(_ => kept)

  def deregister(owner: String): Effect[Done] =
    currentState match
      case None                            => effects.reply(Done)
      case Some(g) if g.owner != owner     => effects.error(s"${GameEntity.Forbidden}: ${g.code} is ${g.owner}'s", ErrorCode.Forbidden)
      case Some(_)                         => effects.updateState(None).thenReply(_ => Done)

  /** An operator's removal (provider-routes `DELETE /registry/{code}`): whoever owns it. */
  def remove: Effect[Option[Game]] =
    val was = currentState
    if was.isEmpty then effects.error("game not found", ErrorCode.NotFound)
    else effects.updateState(None).thenReply(_ => was)

  def get: ReadOnlyEffect[Game] =
    currentState.fold(effects.error[Game]("game not found", ErrorCode.NotFound))(effects.reply(_))

object GameEntity
    extends KeyValueEntity.Companion[GameEntity, Option[Game]](
      componentId = ComponentId("game"),
      stateSerializer = Codecs.serializer[Option[Game]]("game-state")
    ):

  val CodeTaken = "code_taken"
  val Forbidden = "forbidden"

  given Serializer[Game] = Codecs.serializer[Game]("game")

  def create(context: KeyValueEntityContext) = new GameEntity

  val register   = command("register")(_.register)
  val deregister = command("deregister")(_.deregister)
  val remove     = command("remove")(_.remove)
  val get        = query("get")(_.get)
