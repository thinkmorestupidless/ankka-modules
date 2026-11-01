package io.eitheror.casino.programme.comms

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant
import java.util.UUID

/** An in-portal notification for an affiliate (FR-035), as the original's types (`APPLICATION_APPROVED`, …). */
final case class NotificationState(
    affiliate: String = "",
    brand: Option[String] = None,
    kind: String = "",
    body: String = "",
    createdAt: Option[Instant] = None,
    readAt: Option[Instant] = None
):
  def exists: Boolean = affiliate.nonEmpty

final case class WriteNotification(affiliate: String, brand: Option[String], kind: String, body: String, at: Instant)

object NotificationCodecs:
  given Serializer[NotificationState] = ProgrammeCodecs.serializer[NotificationState]("notification")
  given Serializer[WriteNotification] = ProgrammeCodecs.serializer[WriteNotification]("write-notification")
  given Serializer[Instant]           = ProgrammeCodecs.serializer[Instant]("instant")

import NotificationCodecs.given

final class Notification(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[NotificationState]:
  def emptyState: NotificationState = NotificationState()

  /** Written once; a repeat (a redelivered change) changes nothing. */
  def write(cmd: WriteNotification): Effect[NotificationState] =
    if currentState.exists then effects.reply(currentState)
    else effects.updateState(NotificationState(cmd.affiliate, cmd.brand, cmd.kind, cmd.body, Some(cmd.at))).thenReplyState

  def read(at: Instant): Effect[NotificationState] =
    if !currentState.exists then effects.error("notification_not_found", ErrorCode.NotFound)
    else if currentState.readAt.isDefined then effects.reply(currentState)
    else effects.updateState(currentState.copy(readAt = Some(at))).thenReplyState

  def get: ReadOnlyEffect[NotificationState] = effects.reply(currentState)

object Notification extends KeyValueEntity.Companion[Notification, NotificationState](
      componentId = ComponentId("notification"),
      stateSerializer = NotificationCodecs.given_Serializer_NotificationState
    ):
  def newId(): String = UUID.randomUUID().toString

  /** A notification a consumer writes for one change: its id from the change, so a redelivery writes the same one. */
  def idFor(change: String): String = UUID.nameUUIDFromBytes(change.getBytes("UTF-8")).toString

  def create(context: KeyValueEntityContext) = new Notification(context)

  val write = command("write")(_.write)
  val read  = command("read")(_.read)
  val get   = query("get")(_.get)
