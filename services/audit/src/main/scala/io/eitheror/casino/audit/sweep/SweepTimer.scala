package io.eitheror.casino.audit.sweep

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.Wiring
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.retention.Retention

/**
 * The daily sweep (research R13): a `Sweep` for each brand read, `payments` and the installation, at
 * the cutoff of its retention. One a day per brand: the day names the workflow, so a timer that fires
 * twice starts nothing more.
 */
final class SweepTimer(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    SweepTimer.run(context.componentClient)
    effects.done()

object SweepTimer extends TimedAction.Companion[SweepTimer](ComponentId("sweep-timer")):
  val TimerName = "sweep"

  def brands: Vector[String] = Wiring.settings.get().brands :+ "payments" :+ Own.Installation

  /** Starts each brand's sweep for today; answers the sweeps as they stand. */
  def run(client: ComponentClient): Vector[SweepState] =
    val now   = Wiring.clock.get().instant()
    val floor = Wiring.settings.get().retentionYears
    brands.map { brand =>
      val cutoff = Retention.cutoff(now, RetentionEntity.yearsOf(client, brand, floor))
      client.forWorkflow(Sweep.entityId(brand, now)).call(Sweep.start).invoke(StartSweep(brand, cutoff))
    }

  def create(context: TimedActionContext) = new SweepTimer(context)
  val sweep = handler("sweep")(_.run)
