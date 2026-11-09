package io.eitheror.casino.gameprovider.rounds

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.gameprovider.catalogue.Game

import scala.util.Try

/** The catalogue's part of a round's opening, read only for a round that is new (spec 015). */
object Openings:
  /** Called with the provider and the game code on every catalogue read; a test observes the reads through it. */
  @volatile var onLookup: (String, String) => Unit = (_, _) => ()

  /**
   * The opening with the catalogue's name and category for its game, none when the catalogue holds no such
   * game or cannot be read, which fails no bet. An opening that wants no lookup is returned as it is.
   */
  def catalogued(components: ComponentClient, o: RoundOpening): RoundOpening =
    if !o.gameLookup then o
    else
      val game = o.gameCode.flatMap { code =>
        onLookup(o.provider, code)
        Try(components.forKeyValueEntity(EntityId(Game.entityId(o.provider, code))).call(Game.get).invoke()).toOption.flatMap(_.fields)
      }
      o.copy(gameName = game.map(_.name).filter(_.nonEmpty), gameType = game.map(_.category).filter(_.nonEmpty), gameLookup = false)
