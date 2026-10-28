package io.eitheror.casino.players.inbox

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

/** A message as the inbox lists one (FR-030): no subject or body, which the route reads from the entity. */
final case class MessageRow(
    messageId: String,
    playerId: String,
    category: String,
    origin: String,
    createdAt: Long,
    readAt: Option[Long] = None,
    deleted: Boolean = false,
    delivered: Boolean = false
):
  def unread: Boolean = readAt.isEmpty && !deleted

final class MessagesViewRows extends View[MessageEvent, MessageRow]:
  def onChange(event: MessageEvent): Effect = (rowState, event) match
    case (None, e: MessageEvent.Created) =>
      effects.updateRow(MessageRow(updateContext.subject, e.playerId, e.category, e.origin, e.at.toEpochMilli))
    case (Some(r), e: MessageEvent.Read)    => effects.updateRow(r.copy(readAt = Some(e.at.toEpochMilli)))
    case (Some(r), _: MessageEvent.Deleted) => effects.updateRow(r.copy(deleted = true))
    case (Some(r), _: MessageEvent.Delivered) => effects.updateRow(r.copy(delivered = true))
    case _                                  => effects.ignore()

object MessagesView extends View.Companion[MessagesViewRows, MessageEvent, MessageRow](
      componentId = ComponentId("messages"),
      source = ChangeSource.eventsOf(MessageEntity),
      rowSerializer = PlayersCodecs.serializer[MessageRow]("message-row")
    ):
  def create(ctx: ViewComponentContext) = new MessagesViewRows

  private val created = "COALESCE((payload::jsonb->>'createdAt')::bigint, 0)"
  private val deleted = "COALESCE((payload::jsonb->>'deleted')::boolean, false)"

  /** A player's messages, newest first; one the player deleted is left out unless `:all` is `true` (the operator's). */
  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player AND (CAST(:all AS boolean) OR NOT $deleted) " +
      s"ORDER BY $created DESC, row_key ASC OFFSET CAST(:offset AS int)")

  def ofPlayer(views: ViewClient, playerId: String, offset: Int, limit: Int, includeDeleted: Boolean = false): Vector[MessageRow] =
    views.forView(MessagesView).ask(byPlayer, limit, "player" -> playerId, "all" -> includeDeleted.toString,
      "offset" -> offset.max(0).toString)

  /** The unread count the route answers, over every row the player sees. */
  def unreadCount(rows: Vector[MessageRow]): Int = rows.count(_.unread)

/** A note as the operator lists one (FR-033): the text stays on the entity. */
final case class NoteRow(noteId: String, playerId: String, pinned: Boolean, createdAt: Long, author: String)

final class NotesViewRows extends View[NoteState, NoteRow]:
  def onChange(state: NoteState): Effect =
    if !state.exists then effects.ignore()
    else effects.updateRow(NoteRow(updateContext.subject, state.playerId, state.pinned, state.createdAt.fold(0L)(_.toEpochMilli), state.author))

object NotesView extends View.Companion[NotesViewRows, NoteState, NoteRow](
      componentId = ComponentId("notes"),
      source = ChangeSource.stateOf(NoteEntity),
      rowSerializer = PlayersCodecs.serializer[NoteRow]("note-row")
    ):
  def create(ctx: ViewComponentContext) = new NotesViewRows

  /** A player's notes, pinned first, then newest first. */
  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player " +
      "ORDER BY COALESCE((payload::jsonb->>'pinned')::boolean, false) DESC, COALESCE((payload::jsonb->>'createdAt')::bigint, 0) DESC, row_key ASC")

  def ofPlayer(views: ViewClient, playerId: String, limit: Int = 1000): Vector[NoteRow] =
    views.forView(NotesView).ask(byPlayer, limit, "player" -> playerId)
