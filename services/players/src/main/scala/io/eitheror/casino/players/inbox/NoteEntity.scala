package io.eitheror.casino.players.inbox

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.PlayersCodecs

import java.time.Instant

/** An operator's note on a player (FR-033): operators only; its text may name the player, so it is `Personal`. */
final case class NoteState(
    playerId: String = "",
    text: Personal[String] = Personal(""),
    author: String = "",
    createdAt: Option[Instant] = None,
    pinned: Boolean = false,
    updatedAt: Option[Instant] = None
):
  def exists: Boolean = playerId.nonEmpty

final case class WriteNote(playerId: String, text: String, author: String, at: Instant)
final case class PinNote(pinned: Boolean, at: Instant)

final class NoteEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[NoteState]:
  def emptyState: NoteState = NoteState()

  /** Writes the note once under its id; a repeat answers the first. */
  def write(c: WriteNote): Effect[NoteState] =
    if currentState.exists then effects.reply(currentState)
    else effects.updateState(NoteState(c.playerId, Personal(c.text), c.author, Some(c.at), updatedAt = Some(c.at))).thenReplyState

  def pin(c: PinNote): Effect[NoteState] =
    if !currentState.exists || currentState.pinned == c.pinned then effects.reply(currentState)
    else effects.updateState(currentState.copy(pinned = c.pinned, updatedAt = Some(c.at))).thenReplyState

  /** Erasure blanks the text and keeps who wrote it and when. */
  def erase: Effect[NoteState] =
    if !currentState.exists then effects.reply(currentState)
    else effects.updateState(currentState.copy(text = Personal(""))).thenReplyState

  def get: ReadOnlyEffect[NoteState] = effects.reply(currentState)

object NoteEntity
    extends KeyValueEntity.Companion[NoteEntity, NoteState](
      componentId = ComponentId("note"),
      stateSerializer = PlayersCodecs.serializer[NoteState]("note-state")
    ):

  given Serializer[NoteState] = PlayersCodecs.serializer[NoteState]("note-reply")
  given Serializer[WriteNote] = PlayersCodecs.serializer[WriteNote]("write-note")
  given Serializer[PinNote]   = PlayersCodecs.serializer[PinNote]("pin-note")

  def create(context: KeyValueEntityContext) = new NoteEntity(context)

  val write = command("write")(_.write)
  val pin   = command("pin")(_.pin)
  val erase = command("erase")(_.erase)
  val get   = query("get")(_.get)
