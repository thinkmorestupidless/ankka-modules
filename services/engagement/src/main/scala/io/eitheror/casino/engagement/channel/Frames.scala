package io.eitheror.casino.engagement.channel

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.http.SseEvent

import java.time.Instant

/**
 * The SSE frames of contracts/routes.md: the player stream's (`ready`, `heartbeat`, `signal`,
 * `closing`, as the push gateway's `Frame.render`) and the agent stream's (`player_online`, `ping`).
 * Each is a named event with JSON data and nothing else: the original's `id:` lines and its comment
 * heartbeat and ping are not written (research, *Found during implementation*). Times are written as
 * `Instant.toString`, as the original.
 */
object Frames:

  private final case class Ready(subjectId: String, serverTime: String)
  private final case class Heartbeat(at: String)
  private final case class Empty()
  private final case class Signal(kind: String, correlationId: String, hint: Empty)
  private final case class Closing(reason: String)
  private final case class PlayerOnline(playerId: String, playerEmailPrefix: String, sessionId: String, occurredAt: String)

  // Every field written, empty strings too.
  private inline def everyField = CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false).withTransientNone(false)

  private given JsonValueCodec[Ready]     = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[Heartbeat] = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[Empty]     = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[Signal]    = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[Closing]   = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[PlayerOnline] =
    JsonCodecMaker.make(everyField.withFieldNameMapper(JsonCodecMaker.enforce_snake_case))

  /** The kind of the only signal this service sends (FR-022). */
  val InboxChanged = "inbox.changed"

  def ready(subjectId: String, serverTime: Instant): SseEvent =
    SseEvent.json("ready", Ready(subjectId, serverTime.toString))

  def heartbeat(at: Instant): SseEvent = SseEvent.json("heartbeat", Heartbeat(at.toString))

  def signal(slug: String, messageId: String): SseEvent =
    SseEvent.json("signal", Signal(InboxChanged, s"$slug:$messageId", Empty()))

  def closing(reason: String): SseEvent = SseEvent.json("closing", Closing(reason))

  def playerOnline(playerId: String, emailPrefix: String, sessionId: String, occurredAt: Instant): SseEvent =
    SseEvent.json("player_online", PlayerOnline(playerId, emailPrefix, sessionId, occurredAt.toString))

  def ping(): SseEvent = SseEvent.json("ping", Empty())
