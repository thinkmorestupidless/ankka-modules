package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

/**
 * Retention (FR-022): runs whose last answer is older than the installation's retention are
 * discarded, every callback and the session of each, and counted on the status. Fired by the
 * recurring timer `sweep-runs`, set at every start; it answers `done` with nothing to do, since an
 * error would only be retried (research R4).
 */
object Sweep:

  /** Set by `Main`: the retention and the view client a timed action is not handed. */
  @volatile var retention: FiniteDuration = 30.days
  @volatile var views: Option[ViewClient] = None

  /** Discards every run that ended before `cutoff`; answers how many runs. */
  def run(client: ComponentClient, views: ViewClient, cutoff: Long): Int =
    val rows = views.forView(RunCallbacks).ask(RunCallbacks.endedBefore, 1000, "cutoff" -> cutoff.toString)
    // A run is swept whole: only runs none of whose callbacks is newer than the cutoff.
    val runs = rows.map(_.runId).distinct.filter { run =>
      views.forView(RunCallbacks).ask(RunCallbacks.byRun, 1000, "run" -> run, "after_at" -> "-1", "after_id" -> "").forall(_.lastAnsweredAt < cutoff)
    }
    runs.foreach { run =>
      val callbacks = views.forView(RunCallbacks).ask(RunCallbacks.byRun, 1000, "run" -> run, "after_at" -> "-1", "after_id" -> "")
      callbacks.foreach(c => client.forEventSourcedEntity(EntityId(c.callbackId)).call(CallbackEntity.discard).invoke())
      callbacks.map(_.sessionId).distinct.foreach(s => client.forEventSourcedEntity(EntityId(s)).call(SessionEntity.discard).invoke())
    }
    if runs.nonEmpty then
      client.forKeyValueEntity(EntityId(SimulatorStatusEntity.Id)).call(SimulatorStatusEntity.swept).invoke(SimulatorStatusEntity.Swept(runs.size.toLong, Wiring.now())): Unit
    runs.size

final class SweepAction(context: TimedActionContext) extends TimedAction:
  def sweep: Effect =
    Sweep.views.foreach(v => Sweep.run(context.componentClient, v, Wiring.now() - Sweep.retention.toMillis))
    effects.done()

object SweepAction extends TimedAction.Companion[SweepAction](ComponentId("sweep")):
  def create(ctx: TimedActionContext) = new SweepAction(ctx)
  val sweep = handler("sweep")(_.sweep)
  val TimerName = "sweep-runs"
