package io.eitheror.casino.provider.core

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.provider.core.CallbackEvent.*

/**
 * A callback's record. Recording an attempt is idempotent on its number, so a workflow step that
 * runs twice records once (research R5). Discarding persists before deleting, so the run view is
 * told and drops the row (FR-022).
 */
final class CallbackEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[Callback, CallbackEvent]:

  def emptyState: Callback = Callback.empty(context.entityId)

  def applyEvent(event: CallbackEvent): Callback = event match
    case Scheduled(run, session, brand, kind, route, body, step, at) =>
      currentState.copy(runId = run, sessionId = session, brand = brand, kind = kind, route = route, body = body, step = step, scheduledAt = at)
    case AttemptRecorded(a) => currentState.copy(attempts = currentState.attempts.filterNot(_.attempt == a.attempt) :+ a)
    case Ended(outcome)     => currentState.copy(outcome = outcome, nextRetryAt = None)
    case RetryPlanned(at)   => currentState.copy(nextRetryAt = Some(at))
    case AbandonedBy(a)     => currentState.copy(outcome = Callback.Abandoned, abandoned = Some(a), nextRetryAt = None)
    case Discarded          => currentState

  def schedule(s: Scheduled): Effect[Done] =
    if currentState.scheduled then effects.reply(Done)
    else effects.persist(s).thenReply(_ => Done)

  def record(attempt: Attempt): Effect[Callback] =
    if !currentState.scheduled then effects.error("callback not scheduled", ErrorCode.NotFound)
    else if currentState.attempts.exists(_.attempt == attempt.attempt) then effects.reply(currentState)
    else effects.persist(AttemptRecorded(attempt)).thenReply(identity)

  def end(outcome: String): Effect[Done] =
    if currentState.outcome == outcome || currentState.outcome == Callback.Abandoned then effects.reply(Done)
    else effects.persist(Ended(outcome)).thenReply(_ => Done)

  def retryAt(at: Long): Effect[Done] =
    if !currentState.scheduled then effects.error("callback not scheduled", ErrorCode.NotFound)
    else if Callback.Final.contains(currentState.outcome) || currentState.nextRetryAt.contains(at) then effects.reply(Done)
    else effects.persist(RetryPlanned(at)).thenReply(_ => Done)

  /**
   * An operator gives up on a callback that has not ended (spec 026 FR-021). Abandoning one that
   * ended is refused naming its outcome; abandoning it twice answers the first abandonment.
   */
  def abandon(a: Abandonment): Effect[Callback] =
    if !currentState.scheduled then effects.error("callback not found", ErrorCode.NotFound)
    else if currentState.abandoned.isDefined then effects.reply(currentState)
    // An exhausted call is closed by an operator's abandonment (FR-021; review): it is the one ended
    // call left unacknowledged, and the sweep keeps it until someone says why it may go.
    else if Callback.Final.contains(currentState.outcome) && currentState.outcome != Callback.Exhausted then
      effects.error(s"callback_ended: the callback ended ${currentState.outcome}", ErrorCode.Conflict)
    else effects.persist(AbandonedBy(a)).thenReply(identity)

  def discard: Effect[Done] =
    if !currentState.scheduled then effects.reply(Done)
    else effects.persist(Discarded).deleteEntity().thenReply(_ => Done)

  def get: ReadOnlyEffect[Callback] =
    if !currentState.scheduled then effects.error("callback not found", ErrorCode.NotFound)
    else effects.reply(currentState)

object CallbackEntity
    extends EventSourcedEntity.Companion[CallbackEntity, Callback, CallbackEvent](
      componentId = ComponentId("callback"),
      stateSerializer = Codecs.serializer[Callback]("callback"),
      eventSerializer = Codecs.serializer[CallbackEvent]("callback-event")
    ):

  given Serializer[Scheduled] = Codecs.serializer[Scheduled]("callback-scheduled")
  given Serializer[Attempt]   = Codecs.serializer[Attempt]("callback-attempt")
  given Serializer[Callback]    = Codecs.serializer[Callback]("callback")
  given Serializer[Abandonment] = Codecs.serializer[Abandonment]("callback-abandonment")

  def create(context: EventSourcedEntityContext) = new CallbackEntity(context)

  val schedule = command("schedule")(_.schedule)
  val record   = command("record")(_.record)
  val end      = command("end")(_.end)
  val retryAt  = command("retry-at")(_.retryAt)
  val abandon  = command("abandon")(_.abandon)
  val discard  = command("discard")(_.discard)
  val get      = query("get")(_.get)
