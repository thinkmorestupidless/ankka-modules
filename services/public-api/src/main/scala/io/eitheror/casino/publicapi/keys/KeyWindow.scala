package io.eitheror.casino.publicapi.keys

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.PublicApiCodecs

import java.time.Instant

/** A key's requests in its current minute and its last use (data-model.md `KeyWindow`). */
final case class WindowState(keyId: String = "", windowStart: Option[Instant] = None, count: Int = 0, lastUsedAt: Option[Instant] = None)

final case class Take(now: Instant, limit: Int)

/** `take`'s answer: allowed, or over the rate with the seconds until the window turns. */
final case class Taken(allowed: Boolean, retryAfter: Int = 0)

object Window:
  val Length: Long = 60L

  /**
   * A new window when `now ≥ windowStart + 60 s`; `count < limit` is allowed (count + 1, lastUsedAt = now); else over,
   * `Retry-After` the seconds to the window's end, at least 1 (original `quota.ts`).
   */
  def take(s: WindowState, keyId: String, t: Take): (WindowState, Taken) =
    val fresh = s.windowStart.forall(start => !t.now.isBefore(start.plusSeconds(Length)))
    val w     = if fresh then s.copy(keyId = keyId, windowStart = Some(t.now), count = 0) else s.copy(keyId = keyId)
    if w.count < t.limit then (w.copy(count = w.count + 1, lastUsedAt = Some(t.now)), Taken(true))
    else
      val end  = w.windowStart.get.plusSeconds(Length)
      val left = java.time.Duration.between(t.now, end).toMillis
      (w, Taken(false, math.max(1, math.ceil(left / 1000.0).toInt)))

final class KeyWindow(context: KeyValueEntityContext) extends KeyValueEntity[WindowState]:
  def emptyState: WindowState = WindowState()

  def take(t: Take): Effect[Taken] =
    val (next, taken) = Window.take(currentState, context.entityId, t)
    if next == currentState then effects.reply(taken) else effects.updateState(next).thenReply(_ => taken)

object KeyWindow extends KeyValueEntity.Companion[KeyWindow, WindowState](
      componentId = ComponentId("key-window"),
      stateSerializer = PublicApiCodecs.serializer[WindowState]("key-window")
    ):
  given Serializer[Take]  = PublicApiCodecs.serializer[Take]("key-window-take")
  given Serializer[Taken] = PublicApiCodecs.serializer[Taken]("key-window-taken")

  def create(context: KeyValueEntityContext) = new KeyWindow(context)

  val take = command("take")(_.take)

/** Each key's last use, for the key routes' `lastUsedAt` (data-model.md `KeyUse`). */
final case class KeyUseRow(keyId: String = "", lastUsedAt: Option[Instant] = None)

final class KeyUseView extends View[WindowState, KeyUseRow]:
  def onChange(s: WindowState): Effect =
    val row = KeyUseRow(updateContext.subject, s.lastUsedAt)
    if rowState.contains(row) then effects.ignore() else effects.updateRow(row)

object KeyUse extends View.Companion[KeyUseView, WindowState, KeyUseRow](
      componentId = ComponentId("key-use"),
      source = ChangeSource.stateOf(KeyWindow),
      rowSerializer = PublicApiCodecs.row[KeyUseRow]("key-use-row")
    ):
  def create(ctx: ViewComponentContext) = new KeyUseView
