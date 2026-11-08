package io.eitheror.casino.reporting

import com.thinkmorestupidless.ankka.runtime.ViewClient
import io.eitheror.casino.reporting.day.{CloseInputs, OpenDayRow}

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * What a timed action, a workflow and the erasure handler are constructed without, as attribution's `Wiring`: the
 * settings, the running service's view client and the reads that need it, and the clock. The service sets them at
 * start; a suite sets its own.
 */
object ReportingRuntime:
  val settings: AtomicReference[Settings] = AtomicReference(Settings(sys.env.getOrElse("CASINO_BRAND", "")))

  /** This generation's open days before a day, from `OpenDays.openBefore`. */
  val openBefore: AtomicReference[String => Vector[OpenDayRow]] = AtomicReference(_ => Vector.empty)

  /**
   * What a day's close is given from views over every wallet and round (the games, the differences, the liability,
   * the pending holds, the corrections that arrived during it): `DayEndInputs.gather`, set at start; none before.
   */
  val closeInputs: AtomicReference[String => CloseInputs] = AtomicReference(_ => CloseInputs())

  /** The running service's view client: a workflow step and the erasure handler have none of their own. */
  val viewClient: AtomicReference[Option[ViewClient]] = AtomicReference(None)

  def views: ViewClient = viewClient.get().getOrElse(throw IllegalStateException("reporting has not started: no view client"))

  /** Now, as the endpoint reads it: a document job's and the sweep's times agree with the route's. */
  val clock: AtomicReference[() => Instant] = AtomicReference(() => Instant.now())

  def now(): Instant = clock.get()()
