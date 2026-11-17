package io.eitheror.casino.wallet.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** Whether, and when, `players` asked the wallet to erase this player. */
final case class ErasedPlayer(erasedAt: Option[Instant] = None):
  def erased: Boolean = erasedAt.isDefined

/** `players`' erasure request, as of `at`. */
final case class RecordErasure(at: Instant)

/**
 * The players `players` has asked the wallet to erase (FR-033), one key value entity per player, id the
 * player. The wallet's one personal field, an adjustment's reason, is a contract marker until ankka 042
 * seals it under the player's key, so until then nothing makes it unreadable: the operator's transactions
 * read leaves it out for a player recorded here (review S1, 2026-11-17). Recording twice keeps the first time.
 */
final class ErasedPlayerEntity(context: KeyValueEntityContext) extends KeyValueEntity[ErasedPlayer]:

  def emptyState: ErasedPlayer = ErasedPlayer()

  def record(r: RecordErasure): Effect[ErasedPlayer] =
    if currentState.erased then effects.reply(currentState)
    else effects.updateState(ErasedPlayer(Some(r.at))).thenReplyState

  def get: ReadOnlyEffect[ErasedPlayer] = effects.reply(currentState)

object ErasedPlayerEntity
    extends KeyValueEntity.Companion[ErasedPlayerEntity, ErasedPlayer](
      componentId = ComponentId("erased-player"),
      stateSerializer = Codecs.serializer[ErasedPlayer]("wallet.erased-player")
    ):

  given Serializer[RecordErasure] = Codecs.serializer[RecordErasure]("wallet.record-erasure")

  def id(player: String): EntityId = EntityId(player)

  def create(context: KeyValueEntityContext) = new ErasedPlayerEntity(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)
