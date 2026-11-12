package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, ErasureHandler, ErasureReport}
import io.eitheror.casino.publicapi.records.{ErasePlayer, PlayerRecord}

/**
 * A player's erasure in public-api (FR-025): `players` calls the route as one of its erasure targets, and an erasure
 * filed at the installation (ankka 042) runs the handler; both erase the player's identifying fields in the player's
 * record, so every key reads them absent and a fact read again does not bring them back. The installation's erasure
 * also destroys the subject's key, which every stored copy then reads as erased. Ids, statuses and amounts stay.
 */
object Erasure:
  def erase(components: ComponentClient, player: String): String =
    components.forKeyValueEntity(EntityId(player)).call(PlayerRecord.erase).invoke(ErasePlayer(player)): Unit
    s"""{"service":"public-api","subject":"player/$player"}"""

  val handler: ErasureHandler = ErasureHandler { ctx => subject =>
    val name = subject.value
    if name.startsWith("player/") && name.length > "player/".length then erase(ctx.client, name.stripPrefix("player/")): Unit
    ErasureReport(0)
  }
