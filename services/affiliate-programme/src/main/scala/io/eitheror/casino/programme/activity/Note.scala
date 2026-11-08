package io.eitheror.casino.programme.activity

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant
import java.util.UUID

/** A manager's note on an affiliate at a brand (O23, O24), id a UUID, as the original's. */
final case class NoteState(affiliate: String = "", brand: String = "", text: String = "", author: String = "", createdAt: Option[Instant] = None):
  def exists: Boolean = affiliate.nonEmpty

final case class WriteNote(affiliate: String, brand: String, text: String, author: String, at: Instant)

object NoteCodecs:
  given Serializer[NoteState] = ProgrammeCodecs.serializer[NoteState]("note")
  given Serializer[WriteNote] = ProgrammeCodecs.serializer[WriteNote]("write-note")

import NoteCodecs.given

final class Note(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[NoteState]:
  def emptyState: NoteState = NoteState()

  def write(c: WriteNote): Effect[NoteState] =
    if currentState.exists then effects.reply(currentState)
    else effects.updateState(NoteState(c.affiliate, c.brand, c.text, c.author, Some(c.at))).thenReplyState

  def get: ReadOnlyEffect[NoteState] = effects.reply(currentState)

object Note extends KeyValueEntity.Companion[Note, NoteState](
      componentId = ComponentId("note"),
      stateSerializer = NoteCodecs.given_Serializer_NoteState
    ):
  def newId(): String = UUID.randomUUID().toString

  def create(context: KeyValueEntityContext) = new Note(context)

  val write = command("write")(_.write)
  val get   = query("get")(_.get)
