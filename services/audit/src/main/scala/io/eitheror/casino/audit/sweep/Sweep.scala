package io.eitheror.casino.audit.sweep

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.{AuditCodecs, Wiring}
import io.eitheror.casino.audit.ingest.IngestCalls
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.query.Cursor
import io.eitheror.casino.audit.store.{Record, Records}

import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration.*

/**
 * One day's sweep of a brand (research R13), id `<brand>/<day>`: its records older than the cutoff, a
 * page at a time by keyset, each dropped, the cursor and the counts per month kept in the state; then
 * one record of what was removed. The same day's sweep again answers it as it stands.
 */
final case class SweepState(
    brand: String = "",
    cutoff: Option[Instant] = None,
    after: Option[String] = None,
    removed: Map[String, Long] = Map.empty,
    finished: Boolean = false
):
  def started: Boolean = brand.nonEmpty
  def total: Long      = removed.values.sum

final case class StartSweep(brand: String, cutoff: Instant)

final class Sweep(context: WorkflowContext) extends Workflow[SweepState]:
  def emptyState: SweepState = SweepState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(1.minute).stepRecovery(Sweep.page, RecoverStrategy.maxRetries(5)).build

  def start(c: StartSweep): Effect[SweepState] =
    if currentState.started then effects.reply(currentState)
    else effects.updateState(SweepState(c.brand, Some(c.cutoff))).transitionTo(Sweep.page.ref).thenReplyState

  def pageStep: StepEffect =
    val s    = currentState
    val rows = Records.olderThan(Wiring.views().forView(Records), s.brand, s.cutoff.get, s.after.flatMap(Cursor.decode))
    rows.foreach(r => context.componentClient.forKeyValueEntity(Record.entityId(r.key)).call(Record.drop).invoke(context.workflowId): Unit)
    val removed = rows.foldLeft(s.removed)((m, r) => m.updatedWith(Sweep.month(r.occurredAt))(n => Some(n.getOrElse(0L) + 1)))
    val next    = s.copy(after = rows.lastOption.map(r => Cursor(r.occurredAt, r.key).encode).orElse(s.after), removed = removed)
    if rows.size < Records.SweepPage then stepEffects.updateState(next).thenTransitionTo(Sweep.recorded.ref)
    else stepEffects.updateState(next).thenTransitionTo(Sweep.page.ref)

  /** One record of the sweep: the cutoff, and how many records of each month it removed. */
  def recordedStep: StepEffect =
    val s = currentState
    val changes = Vector(Own.change("cutoff", s.cutoff.get.toString), Own.change("removed", s.total.toString)) ++
      s.removed.toVector.sorted.map((month, n) => Own.change(s"removed.$month", n.toString))
    Own.record(IngestCalls.of(context.componentClient), s.brand, Sweep.Action, "sweep", "brand", s.brand, "done", None, changes,
      Wiring.clock.get().instant(), actorKind = "system", id = Some(s"sweep/${context.workflowId}")): Unit
    stepEffects.updateState(s.copy(finished = true)).thenEnd

  def get: ReadOnlyEffect[SweepState] = effects.reply(currentState)

object Sweep extends Workflow.Companion[Sweep, SweepState](
      componentId = ComponentId("sweep"),
      stateSerializer = AuditCodecs.serializer[SweepState]("sweep")
    ):
  val Action = "audit.sweep"

  def month(at: Instant): String = at.atOffset(ZoneOffset.UTC).toString.take(7)

  def entityId(brand: String, day: Instant): EntityId = EntityId(s"$brand/${day.toString.take(10)}")

  given Serializer[StartSweep] = AuditCodecs.serializer[StartSweep]("start-sweep")

  def create(context: WorkflowContext) = new Sweep(context)

  val page     = step("page")(_.pageStep)
  val recorded = step("record")(_.recordedStep)

  val start = command("start")(_.start)
  val get   = query("get")(_.get)
