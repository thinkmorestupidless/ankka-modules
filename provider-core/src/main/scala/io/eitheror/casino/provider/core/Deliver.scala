package io.eitheror.casino.provider.core

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

/** Where one callback's delivery stands. `attempts` counts every send, the first included. */
final case class DeliverState(
    callbackId: String,
    sessionId: String,
    firstSentAt: Long,
    attempts: Int,
    roundId: Option[String],
    state: String,
    abandonment: Option[Abandonment]
)

object DeliverState:
  val Idle      = "idle"
  val Sending   = "sending"
  val Waiting   = "waiting"
  val Done      = "done"
  val Abandoning = "abandoning"

/**
 * One callback, delivered until the brand answers (spec 026 R6, data-model "Deliver"): `send`
 * re-sends the body the callback recorded the first time, under its transaction id, so a brand that
 * already acted answers as it did; `wait` pauses for the schedule's next interval and comes back to
 * `send`. The brand's acceptance ends it `acknowledged`; a refusal that will not change (a malformed
 * body, a word the protocol does not retry) ends it `refused`; the schedule's cap ends it
 * `exhausted`, which the unanswered list shows. An operator's `abandon` ends it at once.
 *
 * A stake whose round waits for its answer (`roundId`) tells the session how it was answered, so
 * the round opens or goes away; an exhausted stake leaves its round pending, unanswered.
 *
 * The id is the callback id: one delivery per callback, and starting it twice starts it once.
 */
final class Deliver(context: WorkflowContext) extends Workflow[DeliverState]:

  def emptyState: DeliverState = DeliverState(context.workflowId, "", 0L, 0, None, DeliverState.Idle, None)

  // A send waits for the brand as long as the brand entry says (5 s by default); the timeout is room
  // above that. Running `send` again is harmless: the brand is idempotent under the transaction id.
  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(2.minutes).defaultRecovery(RecoverStrategy.maxRetries(3)).build

  private lazy val play = Play(context.componentClient)

  def start(request: Deliver.Start): Effect[Done] =
    if currentState.state != DeliverState.Idle then effects.reply(Done)
    else
      val s = DeliverState(request.callbackId, request.sessionId, request.firstSentAt, request.attemptsMade, request.roundId, DeliverState.Sending, None)
      val first = if request.attemptsMade == 0 then Deliver.send.ref else Deliver.waitForNext.ref
      effects.updateState(s).transitionTo(first).thenReply(Done)

  def abandon(request: Abandonment): Effect[Done] =
    currentState.state match
      case DeliverState.Idle => effects.error("delivery not found", ErrorCode.NotFound)
      case DeliverState.Done => effects.error("delivery_ended: the callback has ended", ErrorCode.Conflict)
      case DeliverState.Abandoning => effects.reply(Done)
      case _ =>
        effects.updateState(currentState.copy(state = DeliverState.Abandoning, abandonment = Some(request)))
          .transitionTo(Deliver.abandoned.ref).thenReply(Done)

  def get: ReadOnlyEffect[DeliverState] =
    if currentState.state == DeliverState.Idle then effects.error("delivery not found", ErrorCode.NotFound)
    else effects.reply(currentState)

  def sendStep: StepEffect =
    play.session(currentState.sessionId) match
      case None =>
        play.end(currentState.callbackId, Callback.Refused)
        stepEffects.updateState(currentState.copy(state = DeliverState.Done)).thenEnd
      case Some(session) =>
        val n    = currentState.attempts + 1
        val sent = play.resend(session, currentState.callbackId, "current", Wiring.brands.answerWait(session.brand), n)
        val s    = currentState.copy(attempts = n)
        sent.reading match
          case Some(r) if Wiring.protocol.accepted(r) => finish(s, Callback.Acknowledged, accepted = Some(true))
          case Some(r) if r.malformedBody || !r.retryable => finish(s, Callback.Refused, accepted = Some(false))
          case _ => stepEffects.updateState(s.copy(state = DeliverState.Waiting)).thenTransitionTo(Deliver.waitForNext.ref)

  def waitStep: StepEffect =
    val elapsed = (Wiring.now() - currentState.firstSentAt).max(0L).millis
    Deliver.schedule.nextInterval(elapsed) match
      case None => finish(currentState, Callback.Exhausted, accepted = None)
      case Some(interval) =>
        context.componentClient.forEventSourcedEntity(EntityId(currentState.callbackId)).call(CallbackEntity.retryAt)
          .invoke(Wiring.now() + interval.toMillis): Unit
        stepEffects.updateState(currentState.copy(state = DeliverState.Waiting)).thenPause(interval, Deliver.send.ref)

  def abandonedStep: StepEffect =
    currentState.abandonment.foreach { a =>
      try context.componentClient.forEventSourcedEntity(EntityId(currentState.callbackId)).call(CallbackEntity.abandon).invoke(a): Unit
      catch case _: CommandError => () // it ended meanwhile: the callback's own outcome stands
    }
    stepEffects.updateState(currentState.copy(state = DeliverState.Done)).thenEnd

  private def finish(s: DeliverState, outcome: String, accepted: Option[Boolean]): StepEffect =
    play.end(s.callbackId, outcome)
    for
      round  <- s.roundId
      answer <- accepted
    do
      context.componentClient.forEventSourcedEntity(EntityId(s.sessionId)).call(SessionEntity.answered)
        .invoke(SessionEvent.RoundAnswered(round, answer)): Unit
    stepEffects.updateState(s.copy(state = DeliverState.Done)).thenEnd

object Deliver
    extends Workflow.Companion[Deliver, DeliverState](
      componentId = ComponentId("deliver"),
      stateSerializer = Codecs.serializer[DeliverState]("deliver-state")
    ):

  /**
   * A delivery to start. `attemptsMade` is how many sends happened before it (the stake route sends
   * once and starts a delivery only when that was not answered); `firstSentAt` is when the first
   * was, from which the schedule's cap is measured.
   */
  final case class Start(callbackId: String, sessionId: String, firstSentAt: Long, attemptsMade: Int, roundId: Option[String])

  given Serializer[Start]        = Codecs.serializer[Start]("deliver-start")
  given Serializer[Abandonment]  = Codecs.serializer[Abandonment]("deliver-abandonment")
  given Serializer[DeliverState] = Codecs.serializer[DeliverState]("deliver-state")

  /** The retry schedule (spec 026 R10), set once at start from `PROVIDER_RETRY_SCHEDULE`. */
  @volatile var schedule: Schedule = Schedule.providerDefault(5.seconds, Set.empty)

  def create(context: WorkflowContext) = new Deliver(context)

  val start   = command("start")(_.start)
  val abandon = command("abandon")(_.abandon)
  val get     = query("get")(_.get)

  val send        = step("send")(_.sendStep)
  val waitForNext = step("wait")(_.waitStep)
  val abandoned   = step("abandoned")(_.abandonedStep)
