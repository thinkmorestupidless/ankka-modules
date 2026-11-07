package io.eitheror.casino.provider.core

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

  /** How often the timer `wake-deliveries` fires: a retry falls due at most this late. */
  @volatile var wakeEvery: FiniteDuration = 30.seconds

  /** Told how many runs a sweep discarded, when any: the simulator counts them on its status. */
  @volatile var onSwept: (ComponentClient, Int) => Unit = (_, _) => ()

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
    if runs.nonEmpty then onSwept(client, runs.size)
    runs.size

  /**
   * Wakes every `Deliver` whose retry falls due before `before`, by asking it its state. A pause's
   * deadline is a timer in the workflow's memory (ankka 0.13): a workflow passivated while it
   * waits, or one a restart stopped, keeps its deadline in its journal and re-arms it only when it
   * is next brought back, which nothing else does. Brought back past its deadline it sends at
   * once. Answers how many were woken.
   */
  def wake(client: ComponentClient, views: ViewClient, before: Long): Int =
    val due = views.forView(RunCallbacks).ask(RunCallbacks.retryDue, 1000, "before" -> before.toString)
    due.foreach(c => client.forWorkflow(EntityId(c.callbackId)).call(Deliver.get).invoke(): Unit)
    due.size

final class SweepAction(context: TimedActionContext) extends TimedAction:
  def sweep: Effect =
    Sweep.views.foreach(v => Sweep.run(context.componentClient, v, Wiring.now() - Sweep.retention.toMillis))
    effects.done()

  def wake: Effect =
    Sweep.views.foreach(v => Sweep.wake(context.componentClient, v, Wiring.now() + Sweep.wakeEvery.toMillis))
    effects.done()

object SweepAction extends TimedAction.Companion[SweepAction](ComponentId("sweep")):
  def create(ctx: TimedActionContext) = new SweepAction(ctx)
  val sweep = handler("sweep")(_.sweep)
  val wake  = handler("wake")(_.wake)
  val TimerName     = "sweep-runs"
  val WakeTimerName = "wake-deliveries"
