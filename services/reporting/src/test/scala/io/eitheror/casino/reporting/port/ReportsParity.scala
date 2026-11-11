package io.eitheror.casino.reporting.port

/** The Reports tab's recordings (contracts/recording.md): the six reports, the hidden tab and the refused role. */
class ReportsParity extends ParityTests(ReportsParity.recordings)

object ReportsParity:
  val recordings: Vector[String] = Vector("reports-ggr", "reports-ggr-default", "reports-players", "reports-bonus",
    "reports-transactions", "reports-transactions-export", "reports-psp", "reports-flag-off", "reports-refused-role")
