package io.eitheror.casino.players.inbox

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.PlayersCodecs

import java.time.Instant

/**
 * A message in a player's inbox (FR-030): written by an operator or the CRM, its subject and body
 * rendered with the merge tags at write and `Personal` from then on. A player's delete hides it from
 * the player; the body is kept for the operator's record until erasure.
 */
final case class MessageState(
    playerId: String = "",
    category: String = "",
    origin: String = "",
    subject: Personal[String] = Personal(""),
    body: Personal[String] = Personal(""),
    createdAt: Option[Instant] = None,
    createdBy: Option[String] = None,
    readAt: Option[Instant] = None,
    deletedByPlayerAt: Option[Instant] = None,
    delivered: Boolean = false
):
  def exists: Boolean = playerId.nonEmpty

object MessageOrigin:
  val Operator = "operator"
  val Crm      = "crm"

sealed trait MessageEvent
object MessageEvent:
  final case class Created(playerId: String, category: String, origin: String, subject: Personal[String], body: Personal[String],
      createdBy: Option[String], at: Instant) extends MessageEvent
  final case class Read(at: Instant)    extends MessageEvent
  final case class Deleted(at: Instant) extends MessageEvent
  final case class Delivered(at: Instant) extends MessageEvent
  final case class Erased(at: Instant)  extends MessageEvent

final case class CreateMessage(playerId: String, category: String, origin: String, subject: String, body: String,
    createdBy: Option[String], at: Instant)
final case class MessageAt(at: Instant)

/** Whether the call changed the message, and the message as it then stands. */
final case class MessageAnswer(changed: Boolean, state: MessageState)

final class MessageEntity(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[MessageState, MessageEvent]:
  import MessageEvent.*

  def emptyState: MessageState = MessageState()

  def applyEvent(event: MessageEvent): MessageState = event match
    case e: Created   => MessageState(e.playerId, e.category, e.origin, e.subject, e.body, Some(e.at), e.createdBy)
    case e: Read      => currentState.copy(readAt = Some(e.at))
    case e: Deleted   => currentState.copy(deletedByPlayerAt = Some(e.at))
    case _: Delivered => currentState.copy(delivered = true)
    case _: Erased    => currentState.copy(subject = Personal(""), body = Personal(""))

  private def unchanged = effects.reply(MessageAnswer(false, currentState))

  /** Writes the message once under its id; a repeat answers the first. */
  def create(c: CreateMessage): Effect[MessageAnswer] =
    if currentState.exists then unchanged
    else
      effects.persist(Created(c.playerId, c.category, c.origin, Personal(c.subject), Personal(c.body), c.createdBy, c.at))
        .thenReply(MessageAnswer(true, _))

  def read(c: MessageAt): Effect[MessageAnswer] =
    if !currentState.exists || currentState.readAt.nonEmpty then unchanged
    else effects.persist(Read(c.at)).thenReply(MessageAnswer(true, _))

  def delete(c: MessageAt): Effect[MessageAnswer] =
    if !currentState.exists || currentState.deletedByPlayerAt.nonEmpty then unchanged
    else effects.persist(Deleted(c.at)).thenReply(MessageAnswer(true, _))

  def delivered(c: MessageAt): Effect[MessageAnswer] =
    if !currentState.exists || currentState.delivered then unchanged
    else effects.persist(Delivered(c.at)).thenReply(MessageAnswer(true, _))

  def erase(c: MessageAt): Effect[MessageAnswer] =
    if !currentState.exists || currentState.body.reveal.isEmpty && currentState.subject.reveal.isEmpty then unchanged
    else effects.persist(Erased(c.at)).thenReply(MessageAnswer(true, _))

  def get: ReadOnlyEffect[MessageState] = effects.reply(currentState)

object MessageEntity
    extends EventSourcedEntity.Companion[MessageEntity, MessageState, MessageEvent](
      componentId = ComponentId("message"),
      stateSerializer = PlayersCodecs.serializer[MessageState]("message-state"),
      eventSerializer = PlayersCodecs.serializer[MessageEvent]("message-event")
    ):

  given Serializer[MessageState]  = PlayersCodecs.serializer[MessageState]("message-reply")
  given Serializer[CreateMessage] = PlayersCodecs.serializer[CreateMessage]("create-message")
  given Serializer[MessageAt]     = PlayersCodecs.serializer[MessageAt]("message-at")
  given Serializer[MessageAnswer] = PlayersCodecs.serializer[MessageAnswer]("message-answer")

  def create(context: EventSourcedEntityContext) = new MessageEntity(context)

  val write     = command("create")(_.create)
  val read      = command("read")(_.read)
  val delete    = command("delete")(_.delete)
  val delivered = command("delivered")(_.delivered)
  val erase     = command("erase")(_.erase)
  val get       = query("get")(_.get)
