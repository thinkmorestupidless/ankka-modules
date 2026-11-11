package io.eitheror.casino.provider.edge.registry

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * The registered codes, in one key-value entity (id `index`), so the games list reads each game by
 * its code and needs no scan (data-model "Game"). Written after the game: a code listed whose game
 * is gone is skipped by the reader and removed on the next deregistration.
 */
final class GameIndex extends KeyValueEntity[Vector[String]]:

  def emptyState: Vector[String] = Vector.empty

  def add(code: String): Effect[Vector[String]] =
    if currentState.contains(code) then effects.reply(currentState)
    else
      val next = (currentState :+ code).sorted
      effects.updateState(next).thenReply(_ => next)

  def drop(code: String): Effect[Vector[String]] =
    if !currentState.contains(code) then effects.reply(currentState)
    else
      val next = currentState.filterNot(_ == code)
      effects.updateState(next).thenReply(_ => next)

  def codes: ReadOnlyEffect[Vector[String]] = effects.reply(currentState)

object GameIndex
    extends KeyValueEntity.Companion[GameIndex, Vector[String]](
      componentId = ComponentId("game-index"),
      stateSerializer = Codecs.serializer[Vector[String]]("game-index")
    ):
  val Id = "index"

  def create(context: KeyValueEntityContext) = new GameIndex

  val add   = command("add")(_.add)
  val drop  = command("drop")(_.drop)
  val codes = query("codes")(_.codes)
