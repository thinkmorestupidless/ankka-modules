package io.eitheror.casino.reporting.port

/** The dispute report's recordings (contracts/recording.md): by session as HTML and CSV, by range, and the seven refusals. */
class DisputeParity extends ParityTests(DisputeParity.recordings)

object DisputeParity:
  val recordings: Vector[String] = Vector("dispute-session-html", "dispute-session-csv", "dispute-range-html",
    "dispute-refusals-session-and-range", "dispute-refusals-neither", "dispute-refusals-range-over-a-year",
    "dispute-refusals-end-before-start", "dispute-refusals-not-a-date", "dispute-refusals-unknown-player",
    "dispute-refusals-unknown-session")
