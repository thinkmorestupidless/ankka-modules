package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.{ProgrammeCodecs, Wiring}

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** One affiliate's erasure (FR-037, research R9), id the affiliate id. */
final case class ErasureState(
    affiliate: String = "",
    filedBy: String = "",
    filedAt: Option[Instant] = None,
    holdUntil: Option[Instant] = None,
    status: String = ErasureRules.New,
    erasedAt: Option[Instant] = None
)

final case class FileErasure(affiliate: String, by: String, at: Instant, holdUntil: Instant)

object ErasureRules:
  val New    = "new"
  val Held   = "held"
  val Erased = "erased"

  /** The hold's end: the start of the day `days` after the UTC day the erasure is filed. */
  def holdUntil(filedAt: Instant, days: Int): Instant =
    LocalDate.ofInstant(filedAt, ZoneOffset.UTC).plusDays(days.toLong).atStartOfDay(ZoneOffset.UTC).toInstant

/**
 * Held until the retention ends, then the identity goes: the data key destroyed (every sealed value the
 * journal holds is unreadable after), the email index released, the realm user deleted, and the
 * account recorded erased. Each of the four is harmless done twice, so a step retried repeats none
 * wrongly. Facts, payouts and the audit are not touched: they name the affiliate by id only.
 */
final class Erasure(context: WorkflowContext) extends Workflow[ErasureState]:
  def emptyState: ErasureState = ErasureState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(1.minute).stepRecovery(Erasure.erase, RecoverStrategy.maxRetries(5)).build

  /** Filed once; filing again answers the erasure already held. */
  def file(c: FileErasure): Effect[ErasureState] =
    if currentState.status != ErasureRules.New then effects.reply(currentState)
    else
      effects.updateState(ErasureState(c.affiliate, c.by, Some(c.at), Some(c.holdUntil), ErasureRules.Held))
        .transitionTo(Erasure.hold.ref).thenReplyState

  def holdStep: StepEffect =
    val now   = Wiring.clock.get().instant()
    val until = currentState.holdUntil.getOrElse(now)
    if !now.isBefore(until) then stepEffects.updateState(currentState).thenTransitionTo(Erasure.erase.ref)
    else
      val wait = FiniteDuration(java.time.Duration.between(now, until).toMillis, MILLISECONDS)
      Erasure.timers.get() match
        case Some(timers) =>
          timers.createSingleTimer(Erasure.dueTimer(context.workflowId), wait, ErasureDue.due.deferred(context.workflowId.toString))
          stepEffects.thenPause()
        case None => stepEffects.thenPause(wait, Erasure.hold.ref)

  def eraseStep: StepEffect =
    val affiliate = currentState.affiliate
    val client    = context.componentClient
    val account   = client.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.get).invoke()
    Secrets(context.secrets).destroy(affiliate).left.foreach(e => throw IllegalStateException(s"the secret store: ${e.reason}"))
    Option(account.emailDigest).filter(_.nonEmpty)
      .foreach(d => client.forKeyValueEntity(EntityId(d)).call(EmailIndex.release).invoke(affiliate): Unit)
    account.keycloakUser.foreach { user =>
      Wiring.realm.get().deleteUser(user) match
        case Right(_) | Left(RealmError.NotFound(_)) | Left(RealmError.NotConfigured(_)) => ()
        case Left(e) => throw IllegalStateException(s"the realm: $e")
    }
    val at = Wiring.clock.get().instant()
    client.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.erased).invoke(at): Unit
    stepEffects.updateState(currentState.copy(status = ErasureRules.Erased, erasedAt = Some(at))).thenEnd

  /** The hold's timer: the hold looked at again, the clock that set it deciding. */
  def due: Effect[ErasureState] =
    if currentState.status != ErasureRules.Held then effects.reply(currentState)
    else effects.transitionTo(Erasure.hold.ref).thenReplyState

  def status: ReadOnlyEffect[ErasureState] = effects.reply(currentState)

object Erasure extends Workflow.Companion[Erasure, ErasureState](
      componentId = ComponentId("erasure"),
      stateSerializer = ProgrammeCodecs.serializer[ErasureState]("erasure")
    ):
  /** The service's timers, set at start: the hold ends on a named timer, on the service's clock. */
  val timers: AtomicReference[Option[TimerScheduler]] = AtomicReference(None)

  def dueTimer(id: EntityId): String = s"erasure-due/$id"

  given Serializer[FileErasure] = ProgrammeCodecs.serializer[FileErasure]("file-erasure")

  def create(context: WorkflowContext) = new Erasure(context)

  val hold  = step("hold")(_.holdStep)
  val erase = step("erase")(_.eraseStep)

  val file   = command("file")(_.file)
  val due    = command("due")(_.due)
  val status = query("status")(_.status)

/** Calls an erasure's hold again when its timer falls due. */
final class ErasureDue(context: TimedActionContext) extends TimedAction:
  def due(id: String): Effect =
    context.componentClient.forWorkflow(EntityId(id)).call(Erasure.due).invoke(): Unit
    effects.done()

object ErasureDue extends TimedAction.Companion[ErasureDue](ComponentId("erasure-due")):
  def create(context: TimedActionContext) = new ErasureDue(context)
  val due = handler[String]("due")(a => id => a.due(id))
