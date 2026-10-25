package io.eitheror.casino.ingest.requests

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.ingest.{IngestCodecs, IngestRuntime}

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/**
 * A sub-brand's request id as an idempotency key (FR-010, FR-013, FR-014; research R5), under
 * `<subBrand>/<requestId>`: the digest of the body first forwarded under it, how many times a create under
 * it was forwarded, whether one is in flight, and the accepted create's answer. The attempt number is what
 * keeps a late answer from becoming the id's answer: an answer is accepted only from the attempt in flight.
 */
final case class RequestState(
    digest: String = "",
    attempt: Int = 0,
    state: String = RequestState.Free,
    answer: Option[Answer] = None,
    recordIds: Vector[String] = Vector.empty,
    since: Option[Instant] = None
)

object RequestState:
  val Free     = "free"
  val InFlight = "inFlight"
  val Answered = "answered"

final case class Begin(digest: String, recordId: String, at: Instant)
final case class Settle(attempt: Int, at: Instant, answer: Option[Answer] = None)

/** What a `begin` found: forward (as this attempt), the id's answer, one in flight, or another body. */
final case class Begun(kind: String, attempt: Int, answer: Option[Answer] = None)

object Begun:
  val Forward  = "forward"
  val Answered = "answered"
  val InFlight = "inFlight"
  val Mismatch = "mismatch"

/** Whether an attempt's answer was taken as the id's, or came too late (the caller records a lost answer). */
final case class Settled(accepted: Boolean)

object RequestCodecs:
  given Serializer[RequestState] = IngestCodecs.serializer[RequestState]("request-state")
  given Serializer[Begin]        = IngestCodecs.serializer[Begin]("request-begin")
  given Serializer[Settle]       = IngestCodecs.serializer[Settle]("request-settle")
  given Serializer[Begun]        = IngestCodecs.serializer[Begun]("request-begun")
  given Serializer[Settled]      = IngestCodecs.serializer[Settled]("request-settled")

import RequestCodecs.given

final class Request(@scala.annotation.unused context: KeyValueEntityContext, retention: Duration) extends KeyValueEntity[RequestState]:
  import RequestState.*

  def emptyState: RequestState = RequestState()

  private def keep(s: RequestState) = effects.updateState(s).expireAfter(retention.toMillis.millis)

  /**
   * data-model.md's table. A free id forwards whatever the body (a refused create is retried corrected,
   * R5); an id held by another body is a mismatch; an answered id past its retention, on the clock the
   * command carries, is a fresh one.
   */
  def begin(cmd: Begin): Effect[Begun] =
    val s       = currentState
    val expired = s.state == Answered && s.since.exists(t => !t.plus(retention).isAfter(cmd.at))
    if s.state == Free || expired then
      val attempt = if expired then 1 else s.attempt + 1
      val ids     = if expired then Vector(cmd.recordId) else s.recordIds :+ cmd.recordId
      keep(RequestState(cmd.digest, attempt, InFlight, None, ids, Some(cmd.at))).thenReply(_ => Begun(Begun.Forward, attempt))
    else if s.digest != cmd.digest then effects.reply(Begun(Begun.Mismatch, s.attempt))
    else if s.state == Answered then effects.reply(Begun(Begun.Answered, s.attempt, s.answer))
    else effects.reply(Begun(Begun.InFlight, s.attempt))

  /** The attempt in flight answered: the answer is the id's. Any other attempt's is late. */
  def answer(cmd: Settle): Effect[Settled] =
    val s = currentState
    if s.state == InFlight && s.attempt == cmd.attempt then
      keep(s.copy(state = Answered, answer = cmd.answer, since = Some(cmd.at))).thenReply(_ => Settled(true))
    else effects.reply(Settled(false))

  /** The attempt in flight was not answered within the bound, or was refused: the id is free again. */
  def release(cmd: Settle): Effect[Settled] =
    val s = currentState
    if s.state == InFlight && s.attempt == cmd.attempt then keep(s.copy(state = Free)).thenReply(_ => Settled(true))
    else effects.reply(Settled(false))

  def read: ReadOnlyEffect[RequestState] = effects.reply(currentState)

object Request extends KeyValueEntity.Companion[Request, RequestState](
      componentId = ComponentId("request"),
      stateSerializer = RequestCodecs.given_Serializer_RequestState
    ):
  def create(context: KeyValueEntityContext) = new Request(context, IngestRuntime.settings.requestIdRetention)

  def id(subBrand: String, requestId: String): String = s"$subBrand/$requestId"

  val begin      = command("begin")(_.begin)
  val answer     = command("answer")(_.answer)
  /** `unanswered` and `refused` of data-model.md: both free the attempt in flight. */
  val unanswered = command("unanswered")(_.release)
  val refused    = command("refused")(_.release)
  val read       = query("read")(_.read)
