package io.eitheror.casino.ingest.requests

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.ingest.{IngestCodecs, IngestRuntime}

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/** An answer `merchant` gave after the caller was told to try again (FR-014): what it made. */
final case class LostAnswer(answeredAt: Instant, ticket: Option[String] = None, paymentId: Option[String] = None)

/**
 * One inbound request (FR-016 to FR-018), under the record id minted when it arrived: when, which route,
 * under which request id, the amounts, how it ended, and the ids `merchant` made. Never a customer field, a
 * network address or a key (SC-006): there is no field here to hold one.
 */
final case class RecordState(
    recordId: String = "",
    subBrand: Option[String] = None,
    route: String = "",
    receivedAt: Option[Instant] = None,
    requestId: Option[String] = None,
    amount: Option[Long] = None,
    currency: Option[String] = None,
    country: Option[String] = None,
    outcome: String = "",
    refusal: Option[String] = None,
    ticket: Option[String] = None,
    paymentId: Option[String] = None,
    attempt: Option[Int] = None,
    lostAnswer: Option[LostAnswer] = None,
    seen: Boolean = false
):
  def exists: Boolean = outcome.nonEmpty

object RecordState:
  /** Every field a record holds, as data-model.md lists it: what `LeakSuite` and the operator's answers hold to. */
  val Fields: Vector[String] = Vector("recordId", "subBrand", "route", "receivedAt", "requestId", "amount", "currency", "country",
    "outcome", "refusal", "ticket", "paymentId", "attempt", "lostAnswer", "seen")

object RecordCodecs:
  given Serializer[RecordState]         = IngestCodecs.serializer[RecordState]("record-state")
  given Serializer[Option[RecordState]] = IngestCodecs.serializer[Option[RecordState]]("record-read")
  given Serializer[LostAnswer]          = IngestCodecs.serializer[LostAnswer]("record-lost-answer")

import RecordCodecs.given

final class RequestRecord(context: KeyValueEntityContext, retention: Duration) extends KeyValueEntity[RecordState]:

  def emptyState: RecordState = RecordState(context.entityId)

  private def keep(s: RecordState) = effects.updateState(s).expireAfter(retention.toMillis.millis)

  /** Once, when the request is answered. */
  def write(cmd: RecordState): Effect[RecordState] =
    if currentState.exists then effects.error("the record is written", ErrorCode.Conflict)
    else keep(cmd.copy(recordId = context.entityId)).thenReplyState

  /** `merchant` answered the unanswered attempt after all: what it made, for the operator (FR-014). */
  def lostAnswer(cmd: LostAnswer): Effect[RecordState] =
    if currentState.outcome != Outcome.Unanswered then effects.error(s"a ${currentState.outcome} record has no lost answer", ErrorCode.Conflict)
    else keep(currentState.copy(outcome = Outcome.LostAnswer, lostAnswer = Some(cmd), seen = false)).thenReplyState

  def markSeen: Effect[Boolean] =
    if !currentState.exists then effects.error("no such record", ErrorCode.NotFound)
    else if currentState.outcome != Outcome.LostAnswer then effects.error("not a lost answer", ErrorCode.Conflict)
    else if currentState.seen then effects.reply(true)
    else keep(currentState.copy(seen = true)).thenReply(_ => true)

  def read: ReadOnlyEffect[Option[RecordState]] = effects.reply(Some(currentState).filter(_.exists))

object RequestRecord extends KeyValueEntity.Companion[RequestRecord, RecordState](
      componentId = ComponentId("request-record"),
      stateSerializer = RecordCodecs.given_Serializer_RecordState
    ):
  def create(context: KeyValueEntityContext) = new RequestRecord(context, IngestRuntime.settings.recordRetention)

  val write      = command("write")(_.write)
  val lostAnswer = command("lost-answer")(_.lostAnswer)
  val markSeen   = command("mark-seen")(_.markSeen)
  val read       = query("read")(_.read)
