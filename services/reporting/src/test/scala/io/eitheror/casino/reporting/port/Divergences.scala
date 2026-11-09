package io.eitheror.casino.reporting.port

import io.eitheror.casino.reporting.port.Parity.Divergence

/**
 * Every way the service answers a recording differently on purpose, with where it is decided: contracts/routes.md, a
 * research.md decision, or spec.md's change from the original. A `pending` one is a difference no decision covers, where
 * the service follows the spec and the original answered otherwise; the person decides it.
 */
object Divergences:
  private val trends = Set("dashboard-trend-today", "dashboard-trend-7d", "dashboard-trend-30d", "dashboard-trend-90d")
  private val rankings = Set("dashboard-rankings-today", "dashboard-rankings-90d")
  private val withCurrency = trends ++ rankings ++ Set("dashboard-kpis", "dashboard-kpi-history", "dashboard-bonus-summary",
    "dashboard-payment-health", "reports-ggr", "reports-ggr-default", "reports-players", "reports-bonus", "reports-psp",
    "reports-refused-role")

  val all: Vector[Divergence] = Vector(
    Divergence(withCurrency, Seq("body.currency", "body.currencies"),
      "every answer names its currency and the currencies the brand holds figures in",
      "contracts/routes.md, Common: every answer carries currency and currencies (FR-003)"),
    Divergence(Set("reports-ggr", "reports-ggr-default"), Seq("body.summary.depositPoolGgr", "body.summary.chargebacks",
      "body.summary.ngrBasis", "body.dailyBreakdown[].depositPoolGgr"),
      "the revenue report adds the deposit pool's GGR, chargebacks and the NGR's basis",
      "contracts/routes.md, GET /reports/ggr: depositPoolGgr, chargebacks, ngrBasis added"),
    Divergence(Set("reports-psp"), Seq("body.pspBreakdown[].completedDeposits", "body.pspBreakdown[].withdrawalRequests",
      "body.pspBreakdown[].belowThreshold"),
      "a provider's row adds its completed deposits, its withdrawal requests and whether it is under 95%",
      "contracts/routes.md, GET /reports/psp; contracts/recording.md, reports-psp"),
    Divergence(Set("reports-psp", "dashboard-payment-health"), Seq("body.avgWithdrawalHours", "body.summary.avgWithdrawalHours",
      "body.pspBreakdown[].avgWithdrawalHours"),
      "with no completed withdrawal the average hours are null, where the original answered the constant 0",
      "contracts/routes.md, GET /reports/psp and /dashboard/payment-health (hours null with no completed withdrawal, decided 2026-11-09); spec.md FR-011"),
    Divergence(Set("dashboard-bonus-summary"), Seq("body.totalLiabilityMinorUnits", "body.bonusCostTodayMinorUnits"),
      "the bonus summary answers under both of the original's field-name sets",
      "contracts/routes.md, GET /dashboard/bonus-summary: both field-name sets; spec.md FR-015"),
    Divergence(Set("dashboard-payment-health"), Seq("values.depositSuccessRatePct", "values.pspBreakdown[].successRatePct"),
      "the success rates are the day's figures, where the original answered a hard-coded 97",
      "contracts/routes.md, GET /dashboard/payment-health: figures; contracts/recording.md: figures, not 97; spec.md FR-015"),
    Divergence(Set("dashboard-payment-health"), Seq("body.pspBreakdown[].depositVolume"),
      "a provider's deposit volume is a 2-dp string, where the original answered a number",
      "research.md, Found during implementation: T042, depositVolume a string (decided 2026-11-08); contracts/routes.md"),
    Divergence(rankings, Seq("body.games[].provider"),
      "a game's provider is its rounds', where the original answered it empty",
      "research.md R6, Rankings: the name and provider from Rounds' latest row for the game"),
    Divergence(Set("reports-refused-role"), Seq("headers.Content-Type", "body"),
      "a refused operator is answered the route class's JSON refusal, where Akka answered its plain-text 403",
      "contracts/routes.md, Common: refusals are {\"error\": …}; Admission: the route class's own code", steps = Set(0)),
    Divergence(Set("dispute-range-html"), Seq("status"),
      "a range of thirty days is over the synchronous limit (syncDays, 7), so it is a job whose document is then served",
      "contracts/routes.md, Common: over the synchronous limit 202, GET /documents/{reference} serves it; research.md T055"),
    Divergence(Set("dashboard-activity"), Seq("values.events[].eventType"),
      "an operator's award with no offer is an award in the feed (bonus_grant), where the original listed no event for it",
      "research.md R6, every award in the feed; confirmed 2026-11-09 (Found during implementation)"),
    Divergence(Set("reports-bonus"), Seq("body.offers"),
      "an operator's award with no offer is counted, under its manual offer, where the original counted no grant and listed no offer",
      "research.md T049, every grant of the period counted; confirmed 2026-11-09 (Found during implementation)"),
    Divergence(Set("reports-players"), Seq("body.players[].status"),
      "a player's status is answered, where the original answered it empty for every player",
      "spec.md FR-008; the original never filled it; confirmed 2026-11-09 (Found during implementation)"),
    Divergence(Set("reports-transactions", "reports-transactions-export"), Seq("body.transactions[].transactionId",
      "body.transactions[].reference"),
      "a movement answers its transaction id and reference, where the original answered both empty on every row",
      "spec.md FR-010; the original never filled them; confirmed 2026-11-09 (Found during implementation)"),
    Divergence(Set("dispute-session-html"), Seq("html"),
      "a login session's report lists its game sessions and their rounds, where the original's said no betting activity",
      "spec.md FR-016; the original matched no round to a login session; confirmed 2026-11-09 (Found during implementation)")
  )
