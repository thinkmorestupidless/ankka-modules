package io.eitheror.casino.reporting.port

/** The dashboard's recordings (contracts/recording.md): the KPIs, the trends, the history, geo, rankings, feed and panels. */
class DashboardParity extends ParityTests(DashboardParity.recordings)

object DashboardParity:
  val recordings: Vector[String] = Vector("dashboard-kpis", "dashboard-trend-today", "dashboard-trend-7d", "dashboard-trend-30d",
    "dashboard-trend-90d", "dashboard-kpi-history", "dashboard-geo", "dashboard-rankings-today", "dashboard-rankings-90d",
    "dashboard-activity", "dashboard-bonus-summary", "dashboard-payment-health")
