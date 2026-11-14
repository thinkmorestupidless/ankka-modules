package io.eitheror.casino.support.domain

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.http.SseEvent

/** The chat stream's events (contracts/routes.md): the original's names and fields, each a named event with JSON data. */
object Frames:

  private final case class Delta(token: String)
  private final case class ConfirmAction(action_name: String, summary: String, session_id: String)
  private final case class ActionResult(action_name: String, success: Boolean, detail: String)
  private final case class Message(message: String)
  private final case class Empty()

  private inline def everyField = CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false).withTransientNone(false)

  private given JsonValueCodec[Delta]         = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[ConfirmAction] = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[ActionResult]  = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[Message]       = JsonCodecMaker.make(everyField)
  private given JsonValueCodec[Empty]         = JsonCodecMaker.make(everyField)

  def delta(token: String): SseEvent = SseEvent.json("delta", Delta(token))
  def confirmAction(tool: String, summary: String, session: String): SseEvent =
    SseEvent.json("confirm_action", ConfirmAction(tool, summary, session))
  def actionResult(tool: String, success: Boolean, detail: String): SseEvent =
    SseEvent.json("action_result", ActionResult(tool, success, detail))
  def done: SseEvent                    = SseEvent.json("done", Empty())
  def error(message: String): SseEvent  = SseEvent.json("error", Message(message))

  /** The marker the prompt tells the model to end a reply it cannot help with (R12). */
  val HandOffMarker = "⟦handoff⟧"

/**
 * The hand-off marker replaced in a reply streamed in parts (R12): a part that may begin the marker is held back
 * until the next shows whether it does. `feed` answers what may be sent now; `finish` what is left, and whether
 * the reply carried the marker. Nothing after the marker is sent: the prompt says nothing follows it.
 */
final class HandOff(message: String):
  private val marker   = Frames.HandOffMarker
  private var held     = ""
  private var found    = false

  def handedOff: Boolean = found

  def feed(part: String): String =
    if found then ""
    else
      val text = held + part
      text.indexOf(marker) match
        case i if i >= 0 =>
          found = true
          held = ""
          text.take(i) + message
        case _ =>
          // Hold back the longest tail of the text that is a beginning of the marker.
          val keep = (math.min(marker.length - 1, text.length) to 1 by -1).find(n => marker.startsWith(text.takeRight(n))).getOrElse(0)
          held = text.takeRight(keep)
          text.dropRight(keep)

  def finish(): String =
    val rest = held
    held = ""
    rest
