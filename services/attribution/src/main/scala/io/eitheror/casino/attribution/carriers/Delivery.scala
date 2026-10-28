package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** One delivery's state (data-model.md *Delivery*). The secret is read in the step and never held here. */
final case class DeliveryState(
    subscription: String = "",
    network: String = "",
    fact: Option[FeedFact] = None,
    firstAttemptAt: Option[Instant] = None,
    attempts: Int = 0,
    lastStatus: Option[Int] = None,
    lastError: Option[String] = None,
    status: String = DeliveryRules.New
)

final case class DeliveryStart(subscription: String, network: String, fact: FeedFact)

/**
 * What a delivery decides, pure (research R15): after an attempt, delivered on a 2xx; otherwise the next
 * delay of 1, 5, 15, 60 minutes then hourly, or undelivered once 24 hours have passed since the first
 * attempt. `Delivery`, the workflow, records what this decides.
 */
object DeliveryRules:
  val New         = "new"
  val Delivering  = "delivering"
  val Delivered   = "delivered"
  val Undelivered = "undelivered"
  val Discarded   = "discarded"

  sealed trait Next
  object Next:
    case object Done extends Next
    final case class Retry(after: FiniteDuration) extends Next
    case object GiveUp extends Next

  /** The state after an attempt at `now` that answered `result`, and what follows. */
  def attempted(state: DeliveryState, now: Instant, result: Either[String, Int]): (DeliveryState, Next) =
    val first   = state.firstAttemptAt.getOrElse(now)
    val counted = state.copy(firstAttemptAt = Some(first), attempts = state.attempts + 1,
      lastStatus = result.toOption.orElse(state.lastStatus), lastError = result.left.toOption)
    result match
      case Right(s) if s >= 200 && s < 300 => (counted.copy(status = Delivered, lastError = None), Next.Done)
      case _ if Backoff.exhausted(first, now)  => (counted.copy(status = Undelivered), Next.GiveUp)
      case _ =>
        val delay = Backoff.next(counted.attempts)
        (counted.copy(status = Delivering), Next.Retry(FiniteDuration(delay.toMillis, MILLISECONDS)))

  /** `resend` from undelivered: the attempts start again. */
  def resent(state: DeliveryState): DeliveryState =
    state.copy(status = Delivering, firstAttemptAt = None, attempts = 0, lastStatus = None, lastError = None)

/**
 * A fact's delivery to one subscription (data-model.md *Delivery*), id `<subscription>/<factId>`, after
 * `wallet`'s `DepositWorkflow`. A thrown error in the post is a failed attempt, not a failed workflow.
 */
final class Delivery(context: WorkflowContext) extends Workflow[DeliveryState]:
  def emptyState: DeliveryState = DeliveryState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(1.minute)
      .stepRecovery(Delivery.post, RecoverStrategy.maxRetries(3))
      .build

  /** A no-op on an existing delivery: the fanout may start the same one twice. */
  def start(s: DeliveryStart): Effect[DeliveryState] =
    if currentState.status != DeliveryRules.New then effects.reply(currentState)
    else
      effects
        .updateState(DeliveryState(s.subscription, s.network, Some(s.fact), status = DeliveryRules.Delivering))
        .transitionTo(Delivery.post)
        .thenReplyState

  def resend: Effect[DeliveryState] =
    if currentState.status != DeliveryRules.Undelivered then
      effects.error(s"only an undelivered fact is resent; this one is ${currentState.status}", ErrorCode.Conflict)
    else effects.updateState(DeliveryRules.resent(currentState)).transitionTo(Delivery.post).thenReplyState

  def discard: Effect[DeliveryState] =
    if currentState.status != DeliveryRules.Undelivered then
      effects.error(s"only an undelivered fact is discarded; this one is ${currentState.status}", ErrorCode.Conflict)
    else effects.updateState(currentState.copy(status = DeliveryRules.Discarded)).thenReplyState

  def postStep: StepEffect =
    val fact = currentState.fact.get
    val result =
      try
        context.secrets.get(WebhookSubscription.secretName(currentState.subscription)) match
          case None         => Left("the subscription's secret is missing")
          case Some(secret) =>
            val address = context.componentClient.forKeyValueEntity(EntityId(currentState.subscription))
              .call(WebhookSubscription.get).invoke().address
            Delivery.http.get().post(address, fact.json, WebhookHttp.headers(fact, secret))
      catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    val (next, decision) = DeliveryRules.attempted(currentState, Instant.now(Delivery.clock.get()), result)
    val recorded = stepEffects.updateState(next)
    decision match
      case DeliveryRules.Next.Done         => recorded.thenEnd
      case DeliveryRules.Next.GiveUp       => recorded.thenPause()
      case DeliveryRules.Next.Retry(after) =>
        Delivery.timers.get() match
          case Some(timers) =>
            timers.createSingleTimer(Delivery.retryTimer(context.workflowId), after, DeliveryRetry.retry.deferred(context.workflowId.toString))
            recorded.thenPause()
          case None => recorded.thenPause(after, Delivery.post.ref)

  /** The retry timer's call: the next attempt, when the delivery is still waiting for one. */
  def retry: Effect[DeliveryState] =
    if currentState.status != DeliveryRules.Delivering then effects.reply(currentState)
    else effects.transitionTo(Delivery.post).thenReplyState

  def status: ReadOnlyEffect[DeliveryState] = effects.reply(currentState)

object Delivery
    extends Workflow.Companion[Delivery, DeliveryState](
      componentId = ComponentId("delivery"),
      stateSerializer = AttributionCodecs.serializer[DeliveryState]("delivery")
    ):

  /** How a step posts and tells the time: set by `AttributionService` before registering; fakes in the suites. */
  private[attribution] val http: AtomicReference[WebhookHttp] = AtomicReference(WebhookHttp.client(java.time.Duration.ofSeconds(10)))
  private[attribution] val clock: AtomicReference[Clock]      = AtomicReference(Clock.systemUTC())

  /**
   * The service's timers, set at start: a retry waits on a named timer, so it runs by the service's clock,
   * the clock that also decides the 24 hours. Without one, the step pauses with a deadline instead.
   */
  private[attribution] val timers: AtomicReference[Option[TimerScheduler]] = AtomicReference(None)

  def retryTimer(id: EntityId): String = s"delivery-retry/$id"

  given Serializer[DeliveryStart] = AttributionCodecs.serializer[DeliveryStart]("delivery-start")

  def create(context: WorkflowContext) = new Delivery(context)

  def id(subscription: String, factId: String): EntityId = EntityId(s"$subscription/$factId")

  val post = step("post")(_.postStep)

  val start   = command("start")(_.start)
  val resend  = command("resend")(_.resend)
  val discard = command("discard")(_.discard)
  val retry   = command("retry")(_.retry)
  val status  = query("status")(_.status)

/** Calls a delivery's next attempt when its retry timer falls due. */
final class DeliveryRetry(context: TimedActionContext) extends TimedAction:
  def retry(id: String): Effect =
    context.componentClient.forWorkflow(EntityId(id)).call(Delivery.retry).invoke(): Unit
    effects.done()

object DeliveryRetry extends TimedAction.Companion[DeliveryRetry](ComponentId("delivery-retry")):
  def create(context: TimedActionContext) = new DeliveryRetry(context)
  val retry = handler[String]("retry")(a => id => a.retry(id))
