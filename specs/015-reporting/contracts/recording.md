# Recording: reporting

No recording of the original's reports exists (research R18). The driver
`tests/original/reporting-driver/` records them, in `players-driver`'s shape (`-casino-url -keycloak-url
-substrate-url -brand -operator-user`, passwords in the environment, `EITHEROR_RECORD_TO`), against the
original on kind only. The person creates the operator users and supplies their passwords.

The original's figures are the twenty-player sample's and its days UTC, so a parity test replays a
recording for its **shape**: status, headers, field names, field types and order, refusal texts. Figures are
held to the fact set (`FactSet`, research R17) by the feature suites. A field this branch adds is marked
`added` in the translation and is not compared.

| Recording | Driver call | Parity test | Marked divergences |
|---|---|---|---|
| `reports-ggr` | `GET …/reports/ggr?from&to` | `ReportsParity.ggr` | `depositPoolGgr`, `chargebacks`, `ngrBasis`, `currencies` added |
| `reports-ggr-default` | `GET …/reports/ggr` | `ReportsParity.ggrDefault` | the default period in the brand's zone |
| `reports-players` | `GET …/reports/players?from&to&country&status` | `ReportsParity.players` | — |
| `reports-bonus` | `GET …/reports/bonus?from&to&offerId=all` | `ReportsParity.bonus` | — |
| `reports-transactions` | `GET …/reports/transactions?…&page=0&pageSize=100` | `ReportsParity.transactions` | — |
| `reports-transactions-export` | `GET …/reports/transactions/export?…` | `ReportsParity.export` | — |
| `reports-psp` | `GET …/reports/psp?from&to` | `ReportsParity.psp` | one row per provider; `completedDeposits`, `withdrawalRequests`, `belowThreshold` added; rates `null` with no attempts |
| `reports-flag-off` | any `/reports/*` with the flag off | `ReportsParity.flagOff` | the flag is the brand's setting |
| `reports-refused-role` | `/reports/ggr` as a non-admin operator | `ReportsParity.refused` | — |
| `dashboard-kpis` | `GET …/dashboard/kpis` | `DashboardParity.kpis` | — |
| `dashboard-trend-*` | `?range=today`, `7d`, `30d`, `90d` | `DashboardParity.trend` | — |
| `dashboard-kpi-history` | `GET …/dashboard/kpi-history` | `DashboardParity.history` | — |
| `dashboard-geo` | `GET …/dashboard/geo` | `DashboardParity.geo` | — |
| `dashboard-rankings-*` | `?range=today`, `90d` | `DashboardParity.rankings` | — |
| `dashboard-activity` | `GET …/dashboard/activity` | `DashboardParity.activity` | — |
| `dashboard-bonus-summary` | `GET …/dashboard/bonus-summary` | `DashboardParity.bonus` | both field-name sets answered |
| `dashboard-payment-health` | `GET …/dashboard/payment-health` | `DashboardParity.health` | figures, not `97` |
| `dispute-session-html` | `GET …/players/{id}/dispute-report?sessionId&format=html` | `DisputeParity.sessionHtml` | the HTML's structure, not its times |
| `dispute-session-csv` | `…&format=csv` | `DisputeParity.sessionCsv` | — |
| `dispute-range-html` | `?fromDate&toDate` | `DisputeParity.range` | — |
| `dispute-refusals-*` | the five 400s and the two 404s | `DisputeParity.refusals` | — |

The driver seeds, before recording, a player with a login session, two game sessions and five rounds (the
dispute report's), a deposit, a bonus grant, and a self-exclusion, through the original's own routes, so no
answer is empty. The original is reached by its paths under `/v1/casino/backoffice/{brand}`; the port by
the same paths under `/v1/casino/brands/{brand}/reporting`, which the translation rewrites.
