package io.eitheror.casino.programme.postbacks

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.{ProgrammeCodecs, Wiring}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * One postback to an affiliate's own system (FR-036, research R13), id `<affiliate>/<brand>/<event>/<factId>`.
 * `url` is the affiliate's address with the macros filled when the fact was written: what is sent is
 * what the address said then. It carries the player's click id, so it is dropped once the postback is
 * sent or given up (FR-038): kept at most the 24 hours of retries.
 */
final case class PostbackState(
    affiliate: String = "",
    brand: String = "",
    event: String = "",
    url: String = "",
    firstAttemptAt: Option[Instant] = None,
    attempts: Int = 0,
    lastStatus: Option[Int] = None,
    lastError: Option[String] = None,
    status: String = PostbackRules.New
)

final case class SendPostback(affiliate: String, brand: String, event: String, url: String)

/**
 * What a postback decides, pure, after `attribution`'s `DeliveryRules`: sent on a 2xx; otherwise the next
 * attempt 1, 5, 15 and 60 minutes after, then hourly, until 24 hours from the first, when it is undelivered.
 */
object PostbackRules:
  val New         = "new"
  val Sending     = "sending"
  val Sent        = "sent"
  val Undelivered = "undelivered"

  val Life: Duration = Duration.ofHours(24)
  private val schedule = Vector(1L, 5L, 15L, 60L).map(Duration.ofMinutes)

  def next(attempts: Int): Duration = schedule.lift(attempts - 1).getOrElse(Duration.ofMinutes(60))

  sealed trait Next
  object Next:
    case object Done extends Next
    final case class Retry(after: FiniteDuration) extends Next

  def attempted(s: PostbackState, now: Instant, result: Either[String, Int]): (PostbackState, Next) =
    val first   = s.firstAttemptAt.getOrElse(now)
    val counted = s.copy(firstAttemptAt = Some(first), attempts = s.attempts + 1,
      lastStatus = result.toOption.orElse(s.lastStatus), lastError = result.left.toOption)
    result match
      case Right(code) if code / 100 == 2                 => (counted.copy(status = Sent, lastError = None, url = ""), Next.Done)
      case _ if !now.isBefore(first.plus(Life))            => (counted.copy(status = Undelivered, url = ""), Next.Done)
      case _ => (counted.copy(status = Sending), Next.Retry(FiniteDuration(next(counted.attempts).toMillis, MILLISECONDS)))

final class Postback(context: WorkflowContext) extends Workflow[PostbackState]:
  def emptyState: PostbackState = PostbackState()

  /** A no-op on one already started: a redelivered fact queues the same postback. */
  def start(c: SendPostback): Effect[PostbackState] =
    if currentState.status != PostbackRules.New then effects.reply(currentState)
    else
      effects.updateState(PostbackState(c.affiliate, c.brand, c.event, c.url, status = PostbackRules.Sending))
        .transitionTo(Postback.send.ref).thenReplyState

  def sendStep: StepEffect =
    val s      = currentState
    val now    = Wiring.clock.get().instant()
    val result =
      try Postback.http.get()(s.url)
      catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    val (next, decision) = PostbackRules.attempted(s, now, result)
    context.componentClient.forKeyValueEntity(PostbackOutcome.entityId(s.affiliate, s.brand, s.event)).call(PostbackOutcome.record)
      .invoke(RecordOutcome(s.affiliate, s.brand, s.event, context.workflowId.toString, next.status, next.attempts, next.lastStatus,
        next.lastError, now)): Unit
    val recorded = stepEffects.updateState(next)
    decision match
      case PostbackRules.Next.Done => recorded.thenEnd
      case PostbackRules.Next.Retry(after) =>
        Postback.timers.get() match
          case Some(timers) =>
            timers.createSingleTimer(Postback.retryTimer(context.workflowId), after, PostbackRetry.retry.deferred(context.workflowId.toString))
            recorded.thenPause()
          case None => recorded.thenPause(after, Postback.send.ref)

  /** The retry timer's call: the next attempt, when the postback still waits for one. */
  def retry: Effect[PostbackState] =
    if currentState.status != PostbackRules.Sending then effects.reply(currentState)
    else effects.transitionTo(Postback.send.ref).thenReplyState

  def status: ReadOnlyEffect[PostbackState] = effects.reply(currentState)

object Postback extends Workflow.Companion[Postback, PostbackState](
      componentId = ComponentId("postback"),
      stateSerializer = ProgrammeCodecs.serializer[PostbackState]("postback")
    ):
  /** How a step calls the address: a GET, as the original, its status or why there was none. Faked in the suites. */
  val http: AtomicReference[String => Either[String, Int]] = AtomicReference(get(java.time.Duration.ofSeconds(10)))

  /** The service's timers, set at start: a retry waits on a named timer, on the service's clock. */
  val timers: AtomicReference[Option[TimerScheduler]] = AtomicReference(None)

  def get(timeout: java.time.Duration): String => Either[String, Int] =
    val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    url =>
      val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build()
      Right(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())

  def retryTimer(id: EntityId): String = s"postback-retry/$id"

  def id(affiliate: String, brand: String, event: String, factId: String): EntityId = EntityId(s"$affiliate/$brand/$event/$factId")

  given Serializer[SendPostback] = ProgrammeCodecs.serializer[SendPostback]("send-postback")

  def create(context: WorkflowContext) = new Postback(context)

  val send = step("send")(_.sendStep)

  val start  = command("start")(_.start)
  val retry  = command("retry")(_.retry)
  val status = query("status")(_.status)

/** Calls a postback's next attempt when its retry timer falls due. */
final class PostbackRetry(context: TimedActionContext) extends TimedAction:
  def retry(id: String): Effect =
    context.componentClient.forWorkflow(EntityId(id)).call(Postback.retry).invoke(): Unit
    effects.done()

object PostbackRetry extends TimedAction.Companion[PostbackRetry](ComponentId("postback-retry")):
  def create(context: TimedActionContext) = new PostbackRetry(context)
  val retry = handler[String]("retry")(a => id => a.retry(id))

/** The last outcome of an affiliate's postbacks of one event at one brand (FR-036), id `<affiliate>/<brand>/<event>`. */
final case class OutcomeState(
    affiliate: String = "",
    brand: String = "",
    event: String = "",
    postback: String = "",
    status: String = "",
    attempts: Int = 0,
    lastStatus: Option[Int] = None,
    lastError: Option[String] = None,
    at: Option[Instant] = None
)

final case class RecordOutcome(affiliate: String, brand: String, event: String, postback: String, status: String, attempts: Int,
    lastStatus: Option[Int], lastError: Option[String], at: Instant)

object OutcomeCodecs:
  given Serializer[OutcomeState]  = ProgrammeCodecs.serializer[OutcomeState]("postback-outcome")
  given Serializer[RecordOutcome] = ProgrammeCodecs.serializer[RecordOutcome]("record-outcome")

import OutcomeCodecs.given

final class PostbackOutcome(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[OutcomeState]:
  def emptyState: OutcomeState = OutcomeState()

  /** The latest attempt wins; an older one arriving late changes nothing. */
  def record(c: RecordOutcome): Effect[OutcomeState] =
    if currentState.at.exists(_.isAfter(c.at)) then effects.reply(currentState)
    else
      effects.updateState(OutcomeState(c.affiliate, c.brand, c.event, c.postback, c.status, c.attempts, c.lastStatus, c.lastError, Some(c.at)))
        .thenReplyState

  def get: ReadOnlyEffect[OutcomeState] = effects.reply(currentState)

object PostbackOutcome extends KeyValueEntity.Companion[PostbackOutcome, OutcomeState](
      componentId = ComponentId("postback-outcome"),
      stateSerializer = OutcomeCodecs.given_Serializer_OutcomeState
    ):
  def entityId(affiliate: String, brand: String, event: String): EntityId = EntityId(s"$affiliate/$brand/$event")

  def create(context: KeyValueEntityContext) = new PostbackOutcome(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)

final class PostbackOutcomesView extends View[OutcomeState, OutcomeState]:
  def onChange(s: OutcomeState): Effect = effects.updateRow(s)

object PostbackOutcomes extends View.Companion[PostbackOutcomesView, OutcomeState, OutcomeState](
      componentId = ComponentId("postback-outcomes"),
      source = ChangeSource.stateOf(PostbackOutcome),
      rowSerializer = OutcomeCodecs.given_Serializer_OutcomeState
    ):
  val ofAffiliate = query("of-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate ORDER BY payload::jsonb->>'brand', payload::jsonb->>'event'")

  def create(ctx: ViewComponentContext) = new PostbackOutcomesView
