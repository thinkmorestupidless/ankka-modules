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
  /** The player's record erased; true when it held anything to erase, so a second erasure finds nothing. */
  def erasePlayer(components: ComponentClient, player: String): Boolean =
    components.forKeyValueEntity(EntityId(player)).call(PlayerRecord.erase).invoke(ErasePlayer(player))

  def erase(components: ComponentClient, player: String): String =
    erasePlayer(components, player): Unit
    s"""{"service":"public-api","subject":"player/$player"}"""

  /** The objects an erasure of `subject` erased here: the player's record, once; nothing for any other subject. */
  def erased(components: ComponentClient, subject: String): Long =
    val player = subject.stripPrefix("player/")
    if subject.startsWith("player/") && player.nonEmpty && erasePlayer(components, player) then 1L else 0L

  val handler: ErasureHandler = ErasureHandler(ctx => subject => ErasureReport(erased(ctx.client, subject.value)))
