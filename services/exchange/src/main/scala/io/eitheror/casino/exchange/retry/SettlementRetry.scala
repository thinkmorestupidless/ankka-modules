package io.eitheror.casino.exchange.retry

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.predictions.exchange.components.LedgerAnswer
import io.eitheror.casino.exchange.ExchangeRuntime
import io.eitheror.casino.exchange.ports.{WalletCall, WalletClient, WalletPending}

import java.time.{Duration, Instant}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** What an audit record is written for (R5): the first refusal, and the end, succeeded or abandoned. */
final case class Note(seq: Int, kind: String, by: Option[String], reason: Option[String], at: Instant)

/**
 * A wallet call the wallet refused after the book had moved (research R5, data-model.md), under the
 * call's own key: the call, its attempts, the last refusal and where it stands. `note` is set only
 * on the change that makes it, so the audit consumer writes one record each.
 */
final case class RetryState(
    call: Option[WalletCall] = None,
    attempts: Int = 0,
    lastRefusal: Option[String] = None,
    status: String = SettlementRetry.New,
    notes: Int = 0,
    note: Option[Note] = None
):
  def noted(kind: String, by: Option[String], reason: Option[String], at: Instant): RetryState =
    copy(notes = notes + 1, note = Some(Note(notes + 1, kind, by, reason, at)))

final case class StartRetry(call: WalletCall, refusal: String)
final case class Abandon(operator: String, reason: String)

/**
 * The retry of one refused wallet call: `start` is the claim (a second start is the same retry), then
 * the same keyed call again after 1, 5 and 15 minutes and hourly after, until the wallet moves it or
 * an operator abandons it. Each attempt is the call as first sent, so a success is still one move.
 */
final class SettlementRetry(@scala.annotation.unused context: WorkflowContext) extends Workflow[RetryState]:
  def emptyState: RetryState = RetryState()

  def start(s: StartRetry): Effect[RetryState] =
    if currentState.status != SettlementRetry.New then effects.reply(currentState)
    else
      effects
        .updateState(RetryState(Some(s.call), 1, Some(s.refusal), SettlementRetry.Retrying)
          .noted("refused", None, Some(s.refusal), ExchangeRuntime.now()))
        .transitionTo(SettlementRetry.waiting.ref)
        .thenReplyState

  def abandon(a: Abandon): Effect[RetryState] =
    if currentState.status != SettlementRetry.Retrying then
      effects.error(s"only a retrying call is abandoned; this one is ${currentState.status}", ErrorCode.Conflict)
    else
      effects
        .updateState(currentState.copy(status = SettlementRetry.Abandoned).noted("abandoned", Some(a.operator), Some(a.reason), ExchangeRuntime.now()))
        .transitionTo(SettlementRetry.finish.ref)
        .thenReplyState

  def get: ReadOnlyEffect[RetryState] =
    if currentState.status == SettlementRetry.New then effects.error("no such retry", ErrorCode.NotFound) else effects.reply(currentState)

  /** Until the next attempt is due: the backoff for the attempts so far. */
  def waitStep: StepEffect =
    stepEffects.thenPause(SettlementRetry.after(currentState.attempts), SettlementRetry.attempt.ref)

  def attemptStep: StepEffect =
    if currentState.status != SettlementRetry.Retrying then stepEffects.thenEnd
    else
      val call = currentState.call.get
      val answer =
        try Right(WalletClient(ExchangeRuntime.services, ExchangeRuntime.settings.brand).send(call))
        catch
          case p: WalletPending => Left(Option(p.getMessage).getOrElse("pending"))
          case NonFatal(e)      => Left(e.getClass.getSimpleName)
      answer match
        case Right(LedgerAnswer.Done) =>
          stepEffects
            .updateState(currentState.copy(attempts = currentState.attempts + 1, status = SettlementRetry.Succeeded)
              .noted("succeeded", None, None, ExchangeRuntime.now()))
            .thenEnd
        case other =>
          val refusal = other match
            case Right(LedgerAnswer.Refused(code)) => code
            case Right(LedgerAnswer.Insufficient)  => "INSUFFICIENT_FUNDS"
            case Left(pending)                     => s"pending: ${pending.take(120)}"
            case Right(LedgerAnswer.Done)          => "done"
          stepEffects
            .updateState(currentState.copy(attempts = currentState.attempts + 1, lastRefusal = Some(refusal), note = None))
            .thenTransitionTo(SettlementRetry.waiting.ref)

  def finishStep: StepEffect = stepEffects.thenEnd

object SettlementRetry
    extends Workflow.Companion[SettlementRetry, RetryState](
      componentId = ComponentId("settlement-retry"),
      stateSerializer = Codecs.serializer[RetryState]("settlement-retry")
    ):
  val New       = "new"
  val Retrying  = "retrying"
  val Succeeded = "succeeded"
  val Abandoned = "abandoned"

  given Serializer[StartRetry] = Codecs.serializer[StartRetry]("settlement-retry-start")
  given Serializer[Abandon]    = Codecs.serializer[Abandon]("settlement-retry-abandon")

  /** The backoff after `attempts` attempts: 1, 5, 15 minutes, then hourly (R5); the test kit sets a shorter one. */
  @volatile var backoff: Int => Duration = {
    case 1 => Duration.ofMinutes(1)
    case 2 => Duration.ofMinutes(5)
    case 3 => Duration.ofMinutes(15)
    case _ => Duration.ofHours(1)
  }

  def after(attempts: Int): FiniteDuration = FiniteDuration(backoff(attempts).toMillis, "ms")

  /** A retry's id: the call's key with its slashes as `~`, so it is one path segment of the operator's route. */
  def id(call: WalletCall): EntityId = EntityId(call.key.replace('/', '~'))

  def create(context: WorkflowContext) = new SettlementRetry(context)

  val waiting = step("wait")(_.waitStep)
  val attempt = step("attempt")(_.attemptStep)
  val finish  = step("finish")(_.finishStep)

  val start   = command("start")(_.start)
  val abandon = command("abandon")(_.abandon)
  val get     = query("get")(_.get)
