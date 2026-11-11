package io.eitheror.casino.reporting.documents

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingRuntime

import java.time.Instant
import scala.concurrent.duration.*

/**
 * One run of the sweep (research R9's belt and braces, as `game-provider-simulator`'s): every ready document whose
 * lifetime is over has its object deleted and its record marked expired, whatever became of its job's pause. Deleting
 * an object already gone and expiring a record already expired change nothing, so a run repeated is harmless.
 */
final class Sweeping(components: ComponentClient, bucket: => Bucket, due: Instant => Vector[DocumentState], now: () => Instant):
  def run(): Vector[String] =
    val at = now()
    val past = due(at).filter(d => d.status == Document.Ready && d.expiresAt.exists(!_.isAfter(at)))
    past.foreach(d => Keeping.expire(components, bucket, d, Document.Lifetime, at): Unit)
    past.map(_.reference)

/** The recurring timer `sweep-documents`, every hour: always done, so a failed run is retried by the next. */
final class Sweep(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    Sweeping(context.componentClient, context.storage.default, at => Documents.expiredBy(ReportingRuntime.views, at),
      () => ReportingRuntime.now()).run(): Unit
    effects.done()

object Sweep extends TimedAction.Companion[Sweep](ComponentId("sweep-documents")):
  val TimerName = "sweep-documents"
  val Every: FiniteDuration = 1.hour

  /** At every start: setting the timer again under its name replaces it. */
  def schedule(timers: TimerScheduler): Unit =
    timers.createRecurringTimer(TimerName, Every, Every, sweep.deferred)

  def create(context: TimedActionContext) = new Sweep(context)
  val sweep = handler("sweep-documents")(_.run)
