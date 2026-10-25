package io.eitheror.casino.ingest.rate

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.ingest.IngestCodecs

import java.time.Instant

/**
 * One sub-brand's rate (FR-015, research R4): what it was served in the current minute, and the limit an
 * operator raised it to, if any. A fixed one-minute window, so the time to wait is exact. One entity per
 * sub-brand: a flood queues on its own and touches no other's (SC-005).
 */
final case class RateState(minute: Long = -1, count: Int = 0, limit: Option[Int] = None)

final case class Take(now: Instant, default: Int)

/** Served, or refused with the seconds to the next minute. */
final case class Taken(served: Boolean, count: Int, waitSeconds: Long = 0)

object RateCodecs:
  given Serializer[RateState] = IngestCodecs.serializer[RateState]("rate-state")
  given Serializer[Take]      = IngestCodecs.serializer[Take]("rate-take")
  given Serializer[Taken]     = IngestCodecs.serializer[Taken]("rate-taken")

import RateCodecs.given

final class RateWindow(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[RateState]:

  def emptyState: RateState = RateState()

  /** One request: served while the minute's count is under the limit; a refusal does not count. */
  def take(cmd: Take): Effect[Taken] =
    val minute = cmd.now.getEpochSecond / 60
    val count  = if currentState.minute == minute then currentState.count else 0
    val limit  = currentState.limit.getOrElse(cmd.default)
    if count >= limit then effects.reply(Taken(served = false, count, 60 - cmd.now.getEpochSecond % 60))
    else effects.updateState(currentState.copy(minute = minute, count = count + 1)).thenReply(_ => Taken(served = true, count + 1))

  /** An operator's raise (or cut): it holds until changed, across minutes. */
  def setLimit(perMinute: Int): Effect[RateState] =
    effects.updateState(currentState.copy(limit = Some(perMinute))).thenReplyState

  def read: ReadOnlyEffect[RateState] = effects.reply(currentState)

object RateWindow extends KeyValueEntity.Companion[RateWindow, RateState](
      componentId = ComponentId("rate-window"),
      stateSerializer = RateCodecs.given_Serializer_RateState
    ):
  def create(context: KeyValueEntityContext) = new RateWindow(context)

  val take     = command("take")(_.take)
  val setLimit = command("set-limit")(_.setLimit)
  val read     = query("read")(_.read)
