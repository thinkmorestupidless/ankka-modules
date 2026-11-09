# Feature Specification: reporting — The Brand's Figures, Dashboard and Regulator Documents

**Feature Branch**: `015-reporting`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The brand's `reporting` service: the backoffice dashboard, the
finance, player, bonus, transaction and payment-provider reports with their exports, the dispute
report, the compliance exports, and the day-end figures finance reconciles against the providers,
every one a view over the brand's own facts."

## Context

Today the casino backoffice's Reports tab and Dashboard are served by `casino-service`
(`ReportingRoutes`, `DashboardRoutes`, `ReportService`), and every figure they show is computed
on the request by listing the brand's player twins and asking `wallet-service` for sums per player.
That shape is the reason the figures are wrong in production and the reason this service exists:

- The GGR report, the dashboard's GGR, NGR, deposits and withdrawals, the seven-day history, the trend and the
  players' rankings sample **the twenty most recently registered players** and present the sum as the brand's. For a
  brand with more than twenty players, which Spinvibe is, every money figure on the dashboard and
  the revenue report is a sample presented as a total. The code says so (`samplePids … take(20)`,
  "MVP ceiling", "TODO spec-078: replace with wallet transaction event-log query").
- The transaction ledger asks for one page of two hundred transactions per player, which the
  wallet clamps to one hundred, and filters in memory, so a player with more than one hundred
  transactions is truncated silently; the export is the same read capped at ten thousand rows,
  answered as JSON and turned into CSV by the browser.
- The payment report answers a deposit success rate of **97** and withdrawal counts of **0** as
  constants, in one row named "All PSPs"; the dashboard's payment-health card answers the same
  constants even when the brand's currency cannot be read.
- Every day boundary is UTC, whatever the brand's reporting zone; every amount is a double formatted
  to two places; bonus figures are parsed from strings; the reports' sums are in the brand's default
  currency, a player whose wallet is in another currency counted at nothing, while the dashboard
  converts every other currency to the default at a rate read on the request.
- Nothing is a view. Nothing is rebuildable. Nothing is exact.

The original's reporting *spec* (078) is nonetheless clear about what finance, compliance, marketing
and support want, the Playwright suite drives the screens, and the dispute report (117) and the
player activity trail (128) are real and used. This service keeps every report the backoffice has,
keeps 078's shapes so the backoffice changes only its base URL (spec 001 FR-033), and replaces the
request-time sampling with views over the brand's facts, exact to the minor unit, per currency,
in the brand's reporting zone, rebuildable from the topics whose retention spec 001 FR-015 fixes for
this reason.

What this service is not: it is not the partner read model (`casino-read-model` and its
`casino_read` schema serve the partner GraphQL API and are spec 016's), not the player's own
activity history and export (spec 008 FR-029, FR-040), not the operator's per-player views (spec
008 FR-034: list, search, profile, wallet summary, activity, bets, CRM), not the programme's revenue
(spec 013), not the shared audit record and its dashboards (spec 018), and not the substrate's
`Dashboard` twins and widgets (plan: dropped with the substrate; their one Go scenario is skipped).

## Clarifications

### Session 2026-10-08

- Q: Request-time sums over sampled players, or views? → A: Views over the brand's own topics
  (constitution IV): `money.transactions`, `payments.payments`, `casino.players`,
  `casino.sessions`, `casino.rounds`, `casino.withdrawals`, `casino.rewards`. Every figure a report
  shows is the fold of facts this service has read, exact to the minor unit, and every view can be
  rebuilt from its topics. The twenty-player sample is a defect, not a behaviour, and is not kept.
- Q: Whose day is a reporting day? → A: The brand's. A brand names its reporting zone (an IANA
  zone, set with the brand's configuration, spec 008 FR-041, default UTC), and every daily figure,
  "today", "yesterday", trend bucket and default date range is a calendar day in that zone. Spec
  012 FR-016 closes the affiliate's `ggr-daily` fact on the same day, so the brand's own GGR for a
  day and what its affiliates are told agree. The original's UTC is the default, not a rule.
- Q: One currency per brand, or per currency? → A: Per currency, never summed across currencies
  (spec 013's rule for revenue, kept here for the same reason: a sum across currencies is a number
  with no meaning until a rate is chosen). A report answers one currency at a time, defaulting to
  the brand's default currency, and lists the currencies it holds figures in. The original's
  "brand default currency and nothing else" is a defect for a brand with two wallets' worth of
  players.
- Q: What is GGR, NGR and bonus cost here? → A: The glossary's: GGR is total stake minus total win
  (over `bet_placed`, `win_credited`, `bet_rolled_back`, `round_settled_as_loss`); bonus cost is
  what `casino.rewards` says was awarded, forfeited or converted (spec 011 FR-028, carried on
  `bonus_granted` and `free_spins_settled`); NGR in a brand's report is GGR minus bonus cost minus
  chargebacks, before the programme's fee percentages, which are the programme's (spec 013 FR-007,
  FR-012) and not a brand's figure. A report MUST say which it shows. The original's NGR was GGR
  minus the wallet's `bonus` transaction sum; the inputs change, the formula does not.
- Q: Who reconciles against the game provider? → A: This service, by the provider's own round
  record. `game-provider` keeps the provider's per-round records (spec 003) and this service reads
  `casino.rounds`, which carries the provider's round id, stake and win per settled round; the
  day-end report lists, per provider and game, the brand's GGR from `casino.rounds` beside the
  wallet's GGR from `money.transactions`, and names any round in one and not the other. The
  provider's monthly statement is reconciled by finance against that day-end, outside the
  platform.
- Q: Which exports are this service's? → A: The brand-level ones: every report's CSV, the dispute
  report (HTML and CSV today, PDF as spec 117 FR-009 asked and the original never built), the
  compliance exports (RG cases, spec 010; KYC decisions, spec 009), and the day-end. A player's own
  activity history and the operator's export of one player's trail are spec 008's (FR-029,
  FR-040); a partner's feed is spec 016's; the analytics and lifecycle event exports of the
  original's specs 146 and 149 are spec 014's, if anything's.
- Q: Who may read a report? → A: As the original admits, corrected at planning (Session 2026-11-08,
  plan): the Reports tab's reports to an operator of the brand with `brand_admin`, `resource_admin`
  or `platform_admin` (spec 001 FR-020, decision 0045); the dashboard and the dispute report to
  every operator of the brand, as the original admits its whole operator role set; the compliance
  exports also to `brand_compliance`, and the day-end also to `brand_finance`, spec 135's roles,
  which the original's backend never admitted. The original's `backoffice-reporting-tab` feature
  flag is kept as a brand setting: hidden, the tab is not shown and every report route answers not
  found, as the flag does; it never hides the dashboard.
- Q: Regulatory report definitions? → A: None exist in the original: the licence profiles (UKGC,
  MGA, Curaçao) of spec 137 configure KYC policy and nothing produces a report per profile. This
  spec adds no regulator-shaped report. It adds the minimum a regulator's request can be answered
  from — the compliance exports and the dispute report, each with a reference number, period and
  generation time — and names the per-profile returns as a later spec, under *Changes from the
  original*.
- Q: Personal data in reports? → A: Aggregates hold none. A row that names a player (the player
  report, the ledger, the rankings, the activity feed, the dispute report) carries the player id and
  the fields its purpose needs as `Personal` under the player (spec 001 FR-023): the player report
  and the ledger show the email the original shows, the dispute report the email, network address
  and user agent, each decoding `Erased` after erasure so the aggregate is unchanged and the row
  stays. A report's CSV MUST decode `Personal` at the moment of export and MUST NOT be kept with
  values in it beyond the export's lifetime.
- Q: Snapshots? → A: A generated document (a dispute report, a compliance export, a day-end) is
  kept as an object under the brand's bucket for 24 hours (spec 117 FR-012) and then deleted; its
  reference number, who asked, the period and the parameters are kept as a record with no personal
  field, so a document can be regenerated from the facts and the record says what was given to
  whom. Nothing else is snapshotted: a view is the snapshot.

### Session 2026-10-08 (reconciliation 3)

- Q: Were the fields this spec assumed of 003, 004, 008 and 011 added? → A: Yes, at reconciliation 3
  (003 FR-019, 004 FR-022 with 002 FR-022a, 008 FR-041, 011 FR-027); Assumptions says so. The
  per-player activity, bets, transaction search and export stay spec 008's, as the Context says;
  spec 018 is corrected to agree.

### Session 2026-11-08

- Q: How long is a generated document kept? → A: A brand setting per document kind (report
  export, dispute report, day-end export, compliance export): 24 hours by default, at most 90
  days. A player's erasure deletes every kept document that names them before its lifetime ends;
  the document's record, which holds no personal field, remains (FR-021, FR-024).
- Q: Which GGR does the brand's report show, now that an affiliate's `ggr-daily` counts the deposit
  pool only (spec 025, spec 012 FR-016)? → A: The brand's GGR stays total stake minus total win
  (FR-005). Every report and day-end that shows GGR also shows the deposit pool's GGR, folded from
  the deposit parts spec 025 carries on `money.transactions`, and SC-005 compares `ggr-daily` with
  that figure.
- Q: Does reporting cover a brand's prediction markets (spec 023)? → A: Yes, in full: a summary per
  period and currency, a report per market, a report per trader, and the day's prediction figures
  in the day-end, each shown only to a brand that runs the predictions module (User Story 8,
  FR-028–FR-032). The casino reports' folds exclude the predictions module's movements on
  `money.transactions`, so a casino figure never holds a trade.
- Q: (left open in the draft) NGR before or after the programme's fees, and when the day-end
  closes? → A: Already decided: NGR is before the programme's fees (Session 2026-10-08, FR-005);
  the day-end closes at the instant the reporting day ends in the brand's zone, the instant spec
  012 FR-016 closes `ggr-daily` on, a later settlement being a correction (FR-019).

### Session 2026-11-08 (plan)

- Q: The test-account flag, a game's name and type, and a chargeback's amount and outcome are on no
  fact. How are they had? → A: By amending their producers on this branch: spec 008 (`players`)
  carries the flag on `player_registered` and a family for its change; spec 003 (`game-provider`)
  carries the game's name and type on `round_settled`; spec 002 carries the amount, the currency and
  an enumerated outcome on `payment.dispute.closed`; and spec 005 (`psp-gateway`) with spec 004
  (`merchant`) reports a provider's dispute and its outcome to the brand, which nothing does today
  (the original logs a provider's chargeback as not handled).
- Q: The dashboard's average wagering progress has no exact input. What does it show? → A: The exact
  figure: spec 002 is amended so the wallet's facts carry, for each restricted funds record a bet
  moves, its progress and its requirement after the bet.
- Q: Build User Story 8 before taker fees and corrections after finality exist? → A: Yes. A taker
  fee is read from `trade_made` when spec 023 carries it, and the fee account's credits from
  `money.transactions` when spec 022 has the account; until then both are zero. A corrected market
  shows the outcome it was first resolved to, the corrected outcome and the payments made.
- Q: What does a rate with no attempts answer? → A: Null under its name: distinct from 0%, and the
  field stays in the shape the backoffice reads (FR-011, FR-015).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Finance runs the revenue report for a period (Priority: P1)

A finance manager opens the Reports tab, picks last month, and runs the revenue report. They see
the brand's total stakes, wins, GGR, bonus cost, NGR, deposits, withdrawals and net cash flow for
the period in one currency, one row per reporting day, and export it. The figures are every
player's, exact to the minor unit, and the same figures the day after.

**Why this priority**: It is the report the original gets wrong for every brand with more than
twenty players, and the one finance and the affiliate ledger must agree on.

**Independent Test**: Publish a known set of `money.transactions` and `casino.rewards` facts for
three players across two days in the brand's zone; run the report for the two days; the summary
and the daily rows equal the hand-computed totals; rebuild the view from the topics; the figures
are identical.

**Acceptance Scenarios**:

- added `features/reporting/revenue.feature`: the revenue report sums every player exactly in the currency asked for and names the currencies it holds
- added `features/reporting/revenue.feature`: the revenue report has one row per reporting day in the brand's zone, with a quiet day at zero and a late bet on its own day
- added `features/reporting/revenue.feature`: an exported revenue report holds the summary row and every daily row with every column, equal to the screen
- added `features/reporting/revenue.feature`: the programme's daily GGR facts for a closed day sum to that day's deposit pool GGR for the attributed players
- added `features/reporting/revenue.feature`: the revenue report shows the deposit pool's GGR beside GGR, a stake from restricted funds or the converted pool counting in GGR only
- added `features/reporting/revenue.feature`: a revenue report asked for a currency the brand holds no wallet in is refused naming the currencies it holds
- added `features/reporting/revenue.feature`: a revenue report run again after the view is rebuilt gives identical figures

---

### User Story 2 - An operator reads the dashboard (Priority: P1)

An operator opens the backoffice and sees the brand now: players online, active games, today's
registrations, deposits, withdrawals, GGR and NGR against yesterday, the seven-day history, the
trend over a chosen range, players by country, the top games and players, today's notable events,
the open restricted-funds liability, and the payment providers' health. Every figure is the
brand's, not a sample.

**Why this priority**: The dashboard is the operator's first screen and today shows sampled money
figures and constant payment health.

**Independent Test**: Publish facts for more than twenty players; the dashboard's GGR today equals
the sum over all of them; the payment-health card equals the fold of `payments.payments` for the
day.

**Acceptance Scenarios**:

- added `features/reporting/dashboard.feature`: today's money figures are the sums over every player, and yesterday's and the trend arrow are computed the same way
- added `features/reporting/dashboard.feature`: players online counts open player sessions and active games counts open game sessions
- added `features/reporting/dashboard.feature`: payment health shows the day's deposit success rate per provider and the average time to a withdrawal's completion, with no rate for a day with no attempts
- added `features/reporting/dashboard.feature`: the trend over a range holds the GGR and NGR of each span in the brand's zone
- added `features/reporting/dashboard.feature`: the rankings for a range order games by game sessions started and players by GGR, with country and total wagered
- added `features/reporting/dashboard.feature`: today's registrations, awards, self-exclusions and suspensions appear in the activity list newest first
- added `features/reporting/dashboard.feature`: the bonus summary shows the open records, their restricted balance as the liability, the average progress and today's bonus cost
- added `features/reporting/dashboard.feature`: the geography counts every player of the brand by country

---

### User Story 3 - Compliance reviews players and a support agent finds a transaction (Priority: P2)

A compliance officer runs the player report for a registration period, filters by country and
status, sorts by lifetime GGR, and opens a player. A support agent runs the transaction ledger for
a period and a type, searches by a player's email, and exports every matching row.

**Why this priority**: These are 078's second and fourth stories; the ledger is truncated today.

**Independent Test**: Register players across countries and statuses with known lifetime figures;
the report lists every player registered in the period with the exact figures and the filters
narrow it; a player with three hundred transactions appears with all of them in the ledger.

**Acceptance Scenarios**:

- added `features/reporting/players.feature`: the player report lists one row per player registered in the period with exact lifetime figures
- added `features/reporting/players.feature`: the player report filtered or sorted narrows or orders the rows, and its export holds every matching row regardless of the page
- added `features/reporting/players.feature`: the transaction report shows every transaction of a player with many, newest first, paged at one hundred rows
- added `features/reporting/players.feature`: the transaction report filtered by type or searched by part of an email address shows only matching rows, and its export holds all of them
- added `features/reporting/players.feature`: an erased player's rows in the player report or the transaction report read the email address as erased with every figure unchanged

---

### User Story 4 - Marketing reads bonus performance and finance the provider report (Priority: P2)

A marketing manager runs the bonus report for a period and sees, per offer, grants, granted value,
conversions, forfeits and cancellations with the conversion rate. An operations manager runs the
payment-provider report and sees attempts, completions, value and success rate per provider, and
withdrawal requests, completions and time to completion.

**Why this priority**: 078's third and fifth stories; the provider report is constants today.

**Independent Test**: Publish `casino.rewards` facts for two offers with known outcomes and
`payments.payments` and `casino.withdrawals` facts for two providers; both reports show the exact
counts and rates.

**Acceptance Scenarios**:

- added `features/reporting/bonus-and-providers.feature`: the bonus report shows the summary and one row per offer with the same figures
- added `features/reporting/bonus-and-providers.feature`: the bonus report filtered by one offer shows only that offer, and its export holds the per-offer table
- added `features/reporting/bonus-and-providers.feature`: the provider report shows deposits and withdrawals per payment provider and marks a provider below ninety-five percent success
- added `features/reporting/bonus-and-providers.feature`: a provider report for a period with no attempts shows no rate, not a constant

---

### User Story 5 - A support agent generates a dispute report (Priority: P2)

A player disputes a win. A support agent, from the player's session detail, generates a dispute
report for that login session, or for a date range, and receives a document with a reference
number to quote: the login sessions with their start, end, close reason, device and address, each
game session with its rounds, stakes, wins, net result and status, and the period's totals.

**Why this priority**: Spec 117's report is real, used and the one document the brand hands a
player or a regulator about play.

**Independent Test**: Seed a login session with two game sessions and five rounds; generate the
report for the session and for the day; both hold the rounds with the recorded figures and a
reference number; the document is gone after 24 hours and the record of it remains.

**Acceptance Scenarios**:

- added `features/reporting/disputes.feature`: a dispute report for a player session holds the sessions, every game session and round, the totals and a reference number
- added `features/reporting/disputes.feature`: a dispute report for a date range of up to a year includes every player session in the range
- added `features/reporting/disputes.feature`: a dispute report asked for a range the rules do not allow is refused naming the rule
- added `features/reporting/disputes.feature`: a dispute report is served as an attachment under a file name carrying the reference number
- added `features/reporting/disputes.feature`: a dispute report whose rounds exceed the synchronous limit becomes a job whose progress the agent reads and whose document is served once ready
- added `features/reporting/disputes.feature`: a generated document is no longer served after a day and the record of the report remains
- added `features/reporting/disputes.feature`: a round the wallet settled but the game provider's record lacks is included from the wallet's facts and marked unmatched

---

### User Story 6 - Finance closes the day and reconciles with the providers (Priority: P3)

When a reporting day closes in the brand's zone, the day-end figures are fixed: per currency, the
day's deposits and withdrawals per payment provider, the day's GGR per game provider and game from
the provider's rounds beside the wallet's, bonus cost, chargebacks, and the open liabilities. Finance
reads the day-end the next morning and reconciles it against the providers' statements.

**Why this priority**: Nothing does this today; it is what a real-money brand's finance team
actually needs from the report, and the affiliate ledger's `ggr-daily` is computed on the same
close.

**Independent Test**: Publish a day's facts; let the day close; the day-end equals the revenue
report for that day and lists each provider's figures; a late fact for the closed day appears as a
correction, not a changed day-end.

**Acceptance Scenarios**:

- added `features/reporting/day-end.feature`: the day-end holds, per currency, the providers' deposits and withdrawals, GGR by game provider and by the wallet, bonus cost, chargebacks and the liabilities at close
- added `features/reporting/day-end.feature`: a round whose figures differ between the game provider's record and the wallet's, or present in one only, is listed under differences with both figures
- added `features/reporting/day-end.feature`: a fact for a closed day arriving after the close leaves the day-end unchanged and appears as a correction carried into the next day-end
- added `features/reporting/day-end.feature`: an exported day-end holds every section with the reference number and the close time

---

### User Story 7 - Compliance exports cases and decisions for a regulator (Priority: P3)

A compliance officer, asked by the regulator for the brand's responsible-gambling cases or KYC
decisions over a period, generates the export and hands it over with its reference number.

**Why this priority**: Spec 010 and spec 009 name this service as the holder of the case export;
nothing produces it today.

**Independent Test**: Publish RG case facts and KYC decision facts over a period; the export holds
each with its times, outcome and reason, and the record of the export remains after the document is
deleted.

**Acceptance Scenarios**:

- added `features/reporting/compliance-exports.feature`: an export of cases holds one row per case with the player, type, opened, closed, outcome and reason
- added `features/reporting/compliance-exports.feature`: an export of KYC decisions holds one row per decision with the player, submitted, decided, outcome and reason
- added `features/reporting/compliance-exports.feature`: a compliance export carries a reference number, is served as an attachment, and is deleted after a day with its record kept
- added `features/reporting/compliance-exports.feature`: a document is kept for the lifetime the brand set for its kind, 24 hours when it set none
- added `features/reporting/compliance-exports.feature`: a document lifetime longer than 90 days is refused
- added `features/reporting/compliance-exports.feature`: a kept document that names an erased player is deleted before its lifetime ends, its record kept

---

### User Story 8 - Finance and the trading desk read the brand's prediction markets (Priority: P3)

A brand that runs the predictions module (spec 023) asks what its markets did: how much traded,
what the taker fees earned, what was paid on resolution, which markets were voided or corrected,
and who traded. Finance reads the period's summary and the day-end's prediction section beside the
casino's; the trading desk reads the per-market report; compliance reads the traders report.

**Why this priority**: The module trades real money through the brand's wallet (spec 022) and
nothing reports it; its figures are the brand's revenue from fees and its exposure until markets
are final. It follows the casino's reports, which the original has.

**Independent Test**: For a brand running the predictions module, open, trade, resolve and void
markets with known prices and a taker fee; every figure of the summary, the market report, the
traders report and the day-end equals the fold of those facts, the fees equal the fee account's
credits, and the casino's revenue report is unchanged.

**Acceptance Scenarios**:

- added `features/reporting/predictions.feature`: the predictions summary holds, per currency, contracts traded, notional volume, trades, traders, taker fees, resolution payments and remainders kept, and the markets opened, resolved and voided
- added `features/reporting/predictions.feature`: the market report holds one row per market with its status and times, volume, trades, traders, open interest, fees and what was paid on resolution
- added `features/reporting/predictions.feature`: a voided or corrected market shows what its resolution paid and what its correction changed
- added `features/reporting/predictions.feature`: the traders report holds one row per player who traded with volume, fees paid, positions paid and net result
- added `features/reporting/predictions.feature`: the day-end of a brand running the predictions module holds the day's taker fees, resolution payments and remainders per market, reconciled with the fee account
- added `features/reporting/predictions.feature`: the dashboard shows today's predictions summary against yesterday's to the same hour
- added `features/reporting/predictions.feature`: a market trading changes no figure of the revenue report or the dashboard
- added `features/reporting/predictions.feature`: a brand that does not run the predictions module is shown no predictions summary

---

### Edge Cases

- A brand with wallets in two currencies: every report answers one currency at a time and names
  both; nothing is summed across them; the dashboard shows the default currency with the other
  selectable.
- A reporting day of 23 or 25 hours at a zone's clock change: the day is the calendar day in the
  zone, and the trend's hourly buckets for today hold the hour that happened.
- A brand changes its reporting zone: days already closed keep their boundaries; days from the
  change close in the new zone; the day of the change is reported in the new zone and the day-end
  for it says so.
- A fact arrives out of order or twice: the views fold by fact id and entity key (spec 001 FR-012),
  so a repeat changes nothing and a late fact for an open day lands in that day.
- A view rebuild from topics whose retention has lost the earliest facts: the rebuild proceeds
  (ankka 024) and this service's status says from which day its figures are complete; a report for
  an earlier period is refused naming that day. Spec 001 FR-015 is what keeps this from happening.
- The export of a report with more rows than the synchronous limit: the export becomes a job with
  a reference, like a long dispute report.
- A test account (spec 008): counted in every figure as the original counts it, shown with its
  flag in the player report; a brand setting excludes test accounts from the dashboard and the
  revenue report when set.
- A player erased: rows stay, `Personal` fields read as erased, aggregates are unchanged, a
  document generated before erasure that names the player is deleted at the erasure, and its
  record holds no personal field.
- The dashboard asked for while a view is rebuilding: served from the view as it stands, with the
  status saying rebuilding and the day it is complete from.

## Requirements *(mandatory)*

### Functional Requirements

**Views and figures**

- **FR-001**: Every figure this service shows MUST be the fold of facts it has read from the brand's
  topics — `money.transactions`, `payments.payments`, `casino.players`, `casino.sessions`,
  `casino.rounds`, `casino.withdrawals`, `casino.rewards` (spec 001 FR-011), and for a brand that
  runs the predictions module `markets.events` and `exchange.trades` — into views this
  service keeps, and MUST NOT be computed by calling another service at request time.
  **Change from the original**, which lists players and asks the wallet per player per request.
  (Source: `ReportingRoutes`, `DashboardRoutes`; constitution IV.)
- **FR-002**: Every amount MUST be exact to the minor unit, folded as whole minor units and
  rendered at the boundary in the currency's places; a figure MUST NOT be computed in floating
  point. (Source: spec 001 FR-026; the original's `BigDecimal`-of-`Double` formatting.)
- **FR-003**: Every figure MUST be per currency and MUST NOT be summed across currencies; a report
  MUST answer one currency, defaulting to the brand's default currency, and MUST list the
  currencies the brand holds figures in; a currency the brand holds no figures in MUST be refused
  naming the ones it does. (Source: spec 013's rule; the original's `getBrandDefaultCurrency`.)
- **FR-004**: Every view MUST be rebuildable from its topics (ankka 024), MUST fold by fact id and
  entity key so a repeat or a late fact changes nothing twice, and the service's status MUST say,
  per view, the earliest reporting day its figures are complete from; a report for an earlier
  period MUST be refused naming that day.
- **FR-005**: GGR MUST be total stake minus total win over `bet_placed`, `win_credited`,
  `bet_rolled_back`, `round_settled_as_loss` and `provider_credited` (a free-spin or promotional
  win paid outside a round, counted as a win; added with spec 002 on 2026-10-09); bonus cost MUST be the fold of `casino.rewards`
  per spec 011 FR-028; chargebacks MUST be the amounts of `payment.dispute.closed` whose outcome is lost by the
  brand; NGR in this
  service MUST be GGR minus bonus cost minus chargebacks, and every report that shows NGR MUST say
  it is before the programme's fees (spec 013 FR-012). Every report and day-end that shows GGR MUST
  also show the deposit pool's GGR: the deposit parts (spec 025's `deposit_part_cents`) of `bet_placed`, `win_credited` and
  `bet_rolled_back`, counted as spec 012 FR-016 counts an affiliate's `ggr-daily` (a provider credit
  and a loss settlement add nothing, and a leg counts on its round's day), so the two agree. The
  predictions module's movements MUST NOT enter any casino figure. Net cash flow MUST be deposits confirmed
  minus withdrawals completed.
- **FR-006**: Every daily figure, "today", "yesterday", bucket and default date range MUST be a
  calendar day in the brand's reporting zone (spec 008 FR-041), default UTC; a day MUST close when
  it ends in that zone, and the close MUST be the same instant spec 012 FR-016 uses for
  `ggr-daily`. (Source: the original's `ZoneOffset.UTC` throughout; spec 012.)

**Reports**

- **FR-007**: The revenue report MUST show, for a period and currency, a summary of total stakes,
  total wins, GGR, the deposit pool's GGR, bonus cost, NGR, total deposits, total withdrawals and
  net cash flow, and one row per reporting day with stakes, wins, GGR, the deposit pool's GGR,
  bonus cost and NGR, a day without activity showing
  zero; the period MUST default to the first of the current month to today in the brand's zone (the
  backoffice sends the last thirty days).
  (Source: 078 FR-004, FR-008–FR-011; `ReportingRoutes` `ggr`.)
- **FR-008**: The player report MUST show one row per player registered in the period with player
  id, email, country, registration date, status, KYC status, lifetime deposits, lifetime wagered,
  lifetime GGR, last login and whether the account is a test account (spec 008's flag, amended here); MUST filter by country and
  by status (`active`, `suspended`, `self_excluded`, `closed`); MUST sort by any column; and MUST
  link a row to the player. Lifetime figures MUST be exact over every transaction. (Source: 078
  FR-012–FR-016; `ReportingRoutes` `players`; the `lifetimeGgr` fix, #422.)
- **FR-009**: The bonus report MUST show, for a period, a summary of total grants, granted value,
  converted count and rate, forfeited, cancelled and active counts, and one row per offer with
  name, type and the same figures, from `casino.rewards`, an offer's name and type taken from its grants (spec 011; no topic lists
  offers, so an offer with no grant in the period has no row); the conversion rate MUST be the
  whole percent, truncated, as the original's; converted MUST count a grant converted whether its cap is pending or closed, as the
  original counts. MUST filter by one offer. (Source: 078 FR-017–FR-020; `ReportingRoutes`
  `bonus`; spec 196's amendment on converted statuses.)
- **FR-010**: The transaction ledger MUST show every transaction of the brand in the period, newest
  first, paged at one hundred, each with timestamp, player id, truncated email, type, amount,
  currency and reference; MUST filter by type (`deposit`, `withdrawal`, `bet`, `win`, `rollback`,
  `bonus`, which matches every `bonus_*` type) and by part of an email, each transaction showing the original wallet's own type name (research, Found during implementation, T016); and MUST NOT truncate a player's transactions. **Change from
  the original**, which reads one page per player that the wallet clamps to one hundred. (Source: 078 FR-021–FR-025.)
- **FR-011**: The payment-provider report MUST show, for a period, deposit attempts, completed
  deposits, deposit value and success rate, withdrawal requests, completed withdrawals and average
  hours to completion, in a summary and one row per provider, from `payments.payments` and
  `casino.withdrawals`; a provider below 95% success MUST be marked; a rate with no attempts MUST
  be null under its name. **Change from the original**, which answers 97 and 0 as constants. (Source: 078
  FR-026–FR-028; `ReportingRoutes` `psp`.)
- **FR-012**: Every report MUST export as CSV with every column and every matching row regardless
  of the page shown, and the export's figures MUST equal the screen's; an export beyond the synchronous limit (FR-022) MUST become a job. **Change from the
  original**, whose CSV the browser builds from the JSON, the revenue CSV without the summary row
  078 FR-011 asked for; the JSON routes stay. (Source: 078 FR-003, FR-005, FR-011, FR-016,
  FR-020, FR-025, FR-028.)

**Dashboard**

- **FR-013**: The dashboard MUST show players online (open player sessions), active games (open game
  sessions), registrations today, deposits today (amount and count), withdrawals today (amount and
  count), GGR today and NGR today, each with yesterday's value and a trend of up, down or flat, in
  the brand's zone and default currency; the response MUST keep the original's field names and the
  "active sessions" alias. (Source: `DashboardRoutes` `kpis`; Playwright `15-dashboard-kpis`.)
- **FR-014**: The dashboard MUST show a seven-day history (per day: registrations, deposits amount
  and count, withdrawals amount and count, GGR, NGR), a trend for today (hourly), seven and thirty
  days (daily) and ninety days (weekly) of GGR and NGR, players by country, rankings of games by
  sessions started and of players by GGR with country and total wagered for today, seven, thirty or
  ninety days,
  and an activity feed of today's registrations, awards, self-exclusions and suspensions, newest
  first. (Source: `DashboardRoutes` `kpi-history`, `trend`, `geo`, `rankings`, `activity`.)
- **FR-015**: The dashboard MUST show the bonus summary — open restricted-funds records, their total
  balance as the brand's liability, the average wagering progress and today's bonus cost — and
  payment health — the day's deposit success rate and average hours to a completed withdrawal, per
  provider — from the facts; today's bonus cost and the liability MUST be figures, not the original's zeros, each answered
  under both names the original has (`totalLiability` and `bonusCostToday` as amounts,
  `totalLiabilityMinorUnits` and `bonusCostTodayMinorUnits`, which the backoffice reads, as minor
  units); the average wagering progress MUST be folded from the wallet's progress on its
  restricted funds records (spec 002, amended here); a rate with no attempts MUST be null. (Source: `DashboardRoutes` `bonus-summary`, `payment-health`.)

**Dispute report**

- **FR-016**: An operator MUST be able to generate a dispute report for a player covering one login
  session or a date range of at most a year, as HTML, CSV or PDF, holding the brand, the player's
  email, the period, each login session's start, end, close reason, network address and user
  agent, each game session's game, type, start and end, each round's number, time, stake, win, net
  result and status, the period totals, the generation time and a unique reference number; a
  session and a range together, a range over a year, or an end before a start MUST be refused
  naming the rule. A game's name and type MUST come from its rounds (spec 003's `round_settled`,
  amended here), and a round's status MUST be its settlement's outcome (`win`, `loss`,
  `cancelled`, `credit`). (Source: spec 117 FR-008–FR-012; `DashboardRoutes` `dispute-report`;
  `ReportService`; Playwright `27-dispute-report`.)
- **FR-017**: The dispute report's rounds MUST come from `casino.rounds` and its sessions from
  `casino.sessions`; a round the wallet settled (`money.transactions`) that the provider's record
  lacks MUST be included from the wallet's facts and marked unmatched. (Source: spec 117 FR-004's
  consistency indicator; spec 003's open-rounds finding.)

**Day-end and compliance exports**

- **FR-018**: When a reporting day closes, this service MUST fix the day-end for that day per
  currency: deposits and withdrawals per payment provider (counts and values), GGR per game
  provider and per game from `casino.rounds`, the wallet's GGR from `money.transactions`, bonus
  cost, chargebacks, the restricted-funds liability and the pending holds at close; MUST list every
  round whose provider figures and wallet figures differ or that appears in one and not the other;
  and MUST NOT change a day-end once fixed. **New** (Changes from the original).
- **FR-019**: A fact for a closed day arriving after its close MUST be recorded as a correction
  dated when it arrived, shown with the day-end, and carried into the next day-end's opening
  figures.
- **FR-020**: A compliance officer MUST be able to export, for a period, the brand's RG cases (one
  row per case: player, type, opened, closed, outcome, reason) from `casino.players`'
  `rg_case_opened`, `rg_case_closed`, `rg_limit_applied` and `player_self_excluded`, and the
  brand's KYC decisions (one row per document decided: player, category, submitted, decided,
  outcome, reason) from `player_kyc_submitted` joined by document to
  `player_kyc_document_reviewed`, with each status decision (`player_kyc_verified`,
  `player_kyc_rejected`) as a row of its own. **New** (spec 010 and
  spec 009 name this service for them).
- **FR-021**: Every generated document — a report export, a dispute report, a day-end export, a
  compliance export — MUST carry a unique reference number, the period, the parameters and the
  generation time; MUST be kept as an object under the brand's bucket (spec 001; ankka 034) and
  served as an attachment under a file name carrying the reference; MUST be deleted when its
  lifetime after it is ready ends (the brand's setting for its kind, FR-024: 24 hours by default,
  at most 90 days), and before then when a player it names is erased; and MUST leave a record of reference, who asked, what and when, holding no personal
  field. (Source: spec 117 FR-011, FR-012; spec 128 US3.)
- **FR-022**: A document whose rows exceed the synchronous limit (a brand setting, default 500 rows
  or a range over seven days for the dispute report) MUST be generated as a job with a reference,
  a readable status and a download once ready; one under it MUST be returned in the request. The dispute report's job is **new**: the
  original's dispute report has no asynchronous path, and the seven-day and 500-row rule is its
  activity export's (spec 128).
  (Source: spec 128 US3 scenarios 1–2; spec 117 FR-010.)

**Identity, settings and data**

- **FR-023**: The Reports tab's reports MUST be served to an operator of the brand
  holding `brand_admin`, `resource_admin` or `platform_admin` (spec 001 FR-020); the dashboard and
  the dispute report to every operator of the brand, as the original; the compliance exports also
  to `brand_compliance`; the day-end also to `brand_finance`; every route MUST keep its `{brand}` segment and refuse another
  brand's (spec 001 FR-005). (Source: `AuthDirectives.requireBrandAccessWithRoles`; spec 135.)
- **FR-024**: The brand's settings MUST carry: whether the Reports tab is shown (the original's
  `backoffice-reporting-tab` flag: hidden, every report route answers not found; the dashboard is
  never hidden; shown by default), whether test accounts are
  excluded from the dashboard and the revenue report (default: included, as today), the
  synchronous limit of FR-022, and a document lifetime per document kind (FR-021: hours, 24 by
  default, from 1 to 2,160); the reporting zone is spec 008 FR-041's. (Source: `Flags`;
  `is_test_account`.)
- **FR-025**: An aggregate MUST hold no personal field. A row that names a player MUST carry the
  player id and, as `Personal` under the player (spec 001 FR-023), only the fields its purpose
  needs: the player report and the ledger the email; the rankings and the activity feed the
  truncated email; the dispute report the email, network address and user agent; an erased
  player's row MUST stay with those fields reading as erased and every figure unchanged. A CSV
  MUST decode `Personal` at export and MUST NOT be kept beyond FR-021's lifetime.
- **FR-026**: This service MUST write an audit record (spec 001 FR-011 `platform.audit`) for every
  document generated and every export served, naming the operator, the reference and the period,
  and MUST produce no other fact.
- **FR-027**: Every figure the original's routes answer MUST keep its field name and shape, so the
  casino backoffice changes only its base URL (spec 001 FR-033); fields the original answered as
  constants or zeros (FR-011, FR-015) MUST be answered as figures under the same names.

**Prediction markets** (a brand that runs the predictions module, spec 023)

- **FR-028**: The predictions summary MUST show, for a period and currency: contracts traded,
  notional volume (the sum over trades of contracts times price), trades, distinct traders, taker fees (from `trade_made` when spec 023 carries the fee, zero until
  then), resolution payments and remainders kept, and the markets opened, resolved, voided and
  corrected in the period; the dashboard MUST show today's of these beside the casino's. Folded
  from `exchange.trades` (`trade_made`, `position_paid`, `remainder_kept`) and `markets.events`.
  **New**.
- **FR-029**: The market report MUST show one row per market, filterable by status, category and
  period: question, category, currency, status, opened, closed and resolved times, the resolution
  (outcome, voided or corrected), contracts traded, notional volume, trades, traders, open
  interest (the shares held at the report's time, from fills and payments), taker fees,
  resolution payments and the remainder kept; a corrected market MUST show the outcome it was first resolved to, the corrected
  outcome and the payments made (spec 023 pays once, at finality, on the outcome then standing). **New**.
- **FR-030**: The traders report MUST show, for a period and currency, one row per player who
  traded: contracts traded, notional volume, taker fees paid, positions paid and net result (what
  the trades and payments moved to and from the player's real balance), the email as `Personal`
  under the player (FR-025), filterable and exportable as every report (FR-012). **New**.
- **FR-031**: The day-end of a brand running the predictions module MUST hold, per currency and
  market, the day's taker fees, resolution payments and remainders kept, and the fee account's credits from `money.transactions` (spec 022's `fee` account, zero until it
  exists) beside the fees, listing any trade whose fee and credit differ
  or appear in one only (FR-018, FR-019 apply). **New**.
- **FR-032**: A prediction report MUST be served only to a brand whose entitlement runs the
  predictions module (spec 021), to the roles of FR-023, the trading desk's reports (FR-028,
  FR-029) also to the module's `markets` permission holders; the casino's folds MUST exclude the
  module's movements (the pool, reservation and fee accounts of spec 022), so no casino figure
  holds a trade. **New**.

### Key Entities

- **View**: a fold of one or more topics kept by this service: the money view (per player,
  currency and day: stakes, wins, GGR, deposits, withdrawals, holds, lifetime totals), the rewards
  view (grants by offer and status; cost by day; open liability), the payments view (attempts and
  outcomes by provider and day), the withdrawals view (requests, completions, hours to completion
  by provider and day), the sessions view (open and closed player and game sessions by day;
  rounds per game session), the players view (registration, status, KYC status, country, last
  login, test flag), the events view (registrations, awards, self-exclusions, suspensions, RG
  cases, KYC decisions by day). Each knows the earliest day it is complete from.
- **Reporting day**: a calendar day in the brand's reporting zone; open until it closes, then
  fixed in a day-end.
- **Day-end**: the fixed figures of a closed reporting day per currency, with its differences and
  later corrections.
- **Document**: a generated report (export, dispute report, day-end, compliance export): reference
  number, kind, parameters, period, who asked, generated at, ready at, object until deleted,
  status (generating, ready, expired).
- **Job**: a document generated out of the request: reference, progress, result.
- **Brand reporting settings**: Reports tab shown, test accounts excluded, synchronous limit, a
  document lifetime per document kind.
- **Prediction views**: folds of `markets.events` and `exchange.trades`, for a
  brand that runs the predictions module: per market (status and its times, contracts traded,
  notional volume, trades, traders, open interest, taker fees, resolution payments, the remainder
  kept), per trader and market, and per currency and day.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a brand with 10,000 players and a month of facts, the revenue report's totals
  equal the sums computed independently over the topics, to the minor unit, and the report answers
  within two seconds.
- **SC-002**: The dashboard answers within one second for a brand with 10,000 players, and every
  money figure on it equals the fold of that day's facts over every player.
- **SC-003**: The transaction ledger for a player with 5,000 transactions in the period lists all
  5,000 across its pages, and the export holds all 5,000 rows.
- **SC-004**: Rebuilding every view from the topics and re-running every report for the same
  periods yields identical figures.
- **SC-005**: The sum of a closed day's `ggr-daily` attribution facts over attributed players
  equals that day's deposit pool GGR for those players in the revenue report, in every recording
  that has both.
- **SC-006**: A generated document is unreachable once its lifetime after it is ready has passed
  (24 hours by default, the brand's setting otherwise) or a player it names is erased, and its
  record is still listed; no document record holds a personal field (checked by dump).
- **SC-007**: Every route the original's backoffice calls under `/dashboard` and `/reports`
  answers the same field names, and the Playwright oracles below pass against the rebuilt brand
  with only base URLs changed.
- **SC-008**: A day-end, once fixed, is byte-identical on every later read; a late fact appears as a
  correction and in the next day-end's opening.
- **SC-009**: For a brand running the predictions module, every prediction figure equals the fold
  of its markets' and trades' facts computed independently, to the minor unit; the period's taker
  fees equal the fee account's credits on `money.transactions`; and no casino figure changes when
  a market trades.

## Changes from the original

- **Views replace request-time sampling.** The twenty-player sample, the per-player wallet calls,
  the one-page-per-player ledger and the in-memory filters are gone; every figure is every
  player's. The field names and shapes are kept.
- **Payment figures are figures.** The 97% success rate, the zero withdrawal figures and the single
  "All PSPs" row are replaced by the fold of `payments.payments` and `casino.withdrawals` per
  provider; the bonus summary's zero cost and zero liability likewise.
- **Reporting days are the brand's zone**, not UTC, and agree with the affiliate's `ggr-daily`.
- **Per currency, never summed**: a brand with two currencies gets two sets of figures, not one in
  the default currency with the other's players at zero.
- **Exact minor units**, not doubles formatted to two places.
- **The deposit pool's GGR beside the brand's** (spec 025): the figure the brand's affiliates are
  told, so the two can be checked.
- **Prediction-market reports** (User Story 8): the original had no prediction markets.
- **A document's lifetime is the brand's** per kind, 24 hours by default, at most 90 days; spec 117
  fixed 24 hours.
- **Admission as the original, with spec 135's roles**: the dashboard and the dispute report admit
  every operator of the brand, as the original; `brand_compliance` and `brand_finance` are admitted
  where FR-023 says, which the original's backend never did.
- **CSV from the server**: every report exports from this service, the revenue CSV with its summary
  row (078 FR-011); the original's browser-built CSV had none.
- **Chargebacks are reported**: a provider's dispute reaches `payments.payments` with its amount and
  an enumerated outcome (specs 002, 004, 005, amended here), so NGR's chargebacks are figures; the
  original logged a provider's chargeback as not handled.
- **Facts amended for reporting**: the test-account flag (spec 008), a game's name and type on its
  rounds (spec 003), and a restricted funds record's wagering progress (spec 002).
- **A rate with no attempts is null**, not the original's 0 (or 97 on payment health); the bonus
  summary answers both of the original's field names.
- **Dropped**: the substrate's `Dashboard` twins, widgets and rendering service (one Go scenario,
  skipped); `casino-read-model` is not this service's (spec 016); the per-player activity feed,
  bets tab, transaction search and player export are spec 008's; the `/reports` feature flag
  becomes a brand setting.
- **New**: the day-end with provider reconciliation and corrections (FR-018, FR-019); the RG case
  and KYC decision exports (FR-020); the dispute report as PDF (spec 117 FR-009, asked for and never built) and as a job (117 FR-010,
  never built); the document record and the brand's lifetime applied to every export, not only the
  dispute report; the earliest-complete-day status; the test-account exclusion setting. No regulator-shaped
  return per licence profile is added: none exists in the original, and a per-profile return (UKGC,
  MGA, Curaçao) is a later spec once the regulator's format is in hand.
- **Not carried over**: Neo4j's `sumTransactionAmounts`, `queryTransactions`, `queryAuditEvents`,
  `queryActiveSessions` and `querySessionBets` (the graph client's read side behind the dashboard
  and the session views) and `substrate-graphql`'s resolvers: their readers are this service's and
  spec 008's views.

## Recordings

The Go end-to-end suite has no scenario for the Reports tab or the dashboard (`TestScenario6_
DashboardRendering` is the substrate's dashboard twin and is skipped). Recordings for this service
are made from new Go scenarios written against the original before the port (spec 001 FR-029,
FR-030): one per report with a known fact set, one per dashboard route, one dispute report per
mode. The oracle meanwhile is the Playwright suite (spec 001 FR-033) and the original's unit specs:

- Playwright: `15-dashboard-kpis` (Players online and Active games cards; the Active Sessions card
  no longer appears), `27-dispute-report` (Export report visible on the session detail page; the
  HTML report downloads as an attachment; the body carries a reference; the CSV downloads under a
  `.csv` name), `09-casino-backoffice` (smoke), `35-casino-backoffice-role-guard` (admission).
- Playwright spec 008 owns and this service must not break: `30-player-activity-tab`,
  `19-player-activity-bets`, `24-backoffice-bets-tab`, `31-player-activity-history-download`,
  `33-player-activity-export`, `48-transaction-search`.
- Unit specs: `ReportService`'s HTML and CSV shapes; `DashboardRoutes` and `ReportingRoutes`
  responses as field-name oracles only — their figures are the sample's, not the brand's, and a
  parity test on a figure MUST be written against the fact set, not the original's answer.

## Assumptions

- Spec 008 FR-041 (brand configuration) carries the reporting zone, as taken at reconciliation 3;
  spec 012 reads it too.
- `casino.rounds` carries, per `round_settled`, the provider's round id, game id, provider, stake,
  win and the wallet transaction ids (spec 003 FR-019, taken), so FR-017 and FR-018 match a round to
  its wallet facts without a call.
- `casino.rewards` carries the offer id, name and type on every grant family (spec 011 FR-027,
  taken), so FR-009 groups by offer without a call.
- `payments.payments` carries the provider on every family (spec 002 FR-022a, spec 004 FR-022,
  taken), so the provider report and payment health group by provider.
- The brand's own `platform.audit` is readable in-project (spec 001 FR-011, spec 018 FR-025); this
  service reads it for the record of its own exports and for compliance exports that cite an
  operator's action, never for a figure.
- The original counts test accounts in every figure; the exclusion setting defaults to the
  original's behaviour.
- The original's admission for reports (`brand_admin`, `resource_admin`, `platform_admin`) stands;
  the finer roles of spec 135 are admitted where this spec says, and a brand that has not created
  them loses nothing.
- Topic retention for the fact topics is the installation's longest (spec 001 FR-015); a brand on
  a laptop with seven days' retention gets figures from the day the views were built, and the
  status says so.
- NGR is shown before the programme's fee percentages (FR-005); the affiliate ledger's NGR is the
  programme's.
- The day-end closes at the instant the reporting day ends in the brand's zone, as spec 012 FR-016
  closes `ggr-daily`; a later settlement is a correction (FR-019).
- A generated document's lifetime is the brand's setting for its kind, 24 hours by default and at
  most 90 days (FR-021).
- Spec 008 carries the test-account flag, spec 003 a game's name and type on `round_settled`, spec
  002 a restricted funds record's progress and a dispute's amount and outcome, and spec 005 a
  provider's dispute: each amended on this branch (Session 2026-11-08, plan).
- Spec 023 will carry a taker fee on `trade_made` and spec 022 a `fee` account; until they do, every
  fee figure is zero and the day-end's fee reconciliation compares zero with zero.

## Dependencies

- Spec 001 (topic contract and retention, identity classes, `Personal` fields, the port rules),
  spec 002 (`money.transactions` families and the lifetime totals' meaning), spec 003
  (`casino.rounds` with the provider's figures), spec 004 (`payments.payments` with the provider; a dispute's outcome forwarded, amended here),
  spec 005 (a provider's dispute reported, amended here),
  spec 007 (`casino.withdrawals` with completion times), spec 008 (the reporting zone and brand
  settings; the player-level views this service does not own), spec 009 (`player_kyc_*`
  families), spec 010 (RG case families), spec 011 (`casino.rewards` and bonus cost), spec 012
  (`ggr-daily` closing on the same day), spec 013 (NGR's definition and the fee rule), spec 021
  (whether a brand runs the predictions module), spec 022 (the fee, pool and reservation accounts),
  spec 023 (`markets.events`, `exchange.trades`), spec 025 (the deposit parts
  on `money.transactions`).
- ankka 024 (views and rebuild), 032 (the day-close timer per brand), 034/039 (documents in the
  brand's bucket), 042 (`Personal` in rows and exports, and the erasure handler: ankka 0.16.0, as
  spec 014), 043 (retention the views need), 022
  (operator tokens).
- The casino backoffice (`casino/frontend`) unchanged but for base URLs.

## Out of scope

- The partner read model and GraphQL API (`casino-read-model`, `casino-public-api`): spec 016.
- A player's own activity history and export, the operator's per-player activity, bets, sessions,
  transaction search and profile views: spec 008.
- The programme's revenue, breakdowns and payouts: spec 013.
- The shared audit record and its dashboards: spec 018.
- Analytics and lifecycle event exports to outside systems (the original's specs 146, 149): spec
  014 or spec 016, if carried.
- Regulator-shaped returns per licence profile: a later spec.
- Reconciling the provider's monthly statement itself: finance's, outside the platform; this service
  gives the day-end it is reconciled against.
