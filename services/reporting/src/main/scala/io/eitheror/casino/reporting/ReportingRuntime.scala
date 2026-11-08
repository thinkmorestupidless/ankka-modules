package io.eitheror.casino.reporting

import io.eitheror.casino.reporting.day.{CloseInputs, OpenDayRow}

import java.util.concurrent.atomic.AtomicReference

/**
 * What a timed action is constructed without, as attribution's `Wiring`: the settings, and the reads that need the
 * running service's view client. The service sets them at start; a suite sets its own.
 */
object ReportingRuntime:
  val settings: AtomicReference[Settings] = AtomicReference(Settings(sys.env.getOrElse("CASINO_BRAND", "")))

  /** This generation's open days before a day, from `OpenDays.openBefore`. */
  val openBefore: AtomicReference[String => Vector[OpenDayRow]] = AtomicReference(_ => Vector.empty)

  /**
   * What a day's close is given from views over every wallet and round (the games, the differences, the liability,
   * the pending holds, the corrections that arrived during it). None until the day-end's reads are wired (US6).
   */
  val closeInputs: AtomicReference[String => CloseInputs] = AtomicReference(_ => CloseInputs())
