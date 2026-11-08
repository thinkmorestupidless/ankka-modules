# Tasks: reporting

**Input**: Design documents from `specs/015-reporting/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{routes,facts,recording}.md, quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test (I) and
the living features the scenarios (VII). Every story opens with its tests: kits, feature bindings over the
`FactSet`, integration suites, parity tests. Each is written first and fails first.

**Organization**: By user story. US1 (revenue) is the MVP: the intake, `BrandDay` and `Days` it needs are
foundational, so every later story reads a day that is already exact. US2 is the rest of P1; US3–US5 are P2;
US6–US8 are P3. The contract and the five amended producers come first, because the intake entities read
their new fields.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US8 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `r/` stands for `services/reporting/src/main/scala/io/eitheror/casino/reporting/`
- `r-test/` stands for `services/reporting/src/test/scala/io/eitheror/casino/reporting/`
- `f/` stands for `contract/src/main/scala/io/eitheror/casino/contract/facts/`
- `a/` stands for `services/attribution/src/main/scala/io/eitheror/casino/attribution/` (patterns copied, never changed)
- `C/` is the original's `substrate/casino-service/src/main/scala/io/eitheror/casino/` in
  `~/Documents/projects/eitheror/eitheror-platform`
- sbt runs one process per service directory; the contract is published with `CASINO_VERSION=0.2.0-015-SNAPSHOT`
- A recording is made only against a local original on kind; kubectl always with `--context kind-…`

---

## Phase 1: Setup

**Purpose**: the contract's additions, the service's build, the manifest, the recipes and CI.

- [X] T001 Add the optional fields and the family of contracts/facts.md to `f/CasinoPlayers.scala`
  (`test_account` on `PlayerRegistered`; `PlayerTestAccountChanged` registered as
  `player_test_account_changed`), `f/Others.scala` (`game_name`, `game_type` on `RoundSettled`, and its
  comment that `session_id` is the game session's), `f/PaymentsPayments.scala` (`amount_cents`, `currency` on
  `PaymentDisputeClosed`; `DisputeOutcome.values = Set("won", "lost")`), `f/MoneyTransactions.scala`
  (`RecordProgress` and `records` on the seven families), `f/Exchange.scala` (`fee_cents` on `TradeLeg`), with
  cases in `contract/src/test/scala/io/eitheror/casino/contract/facts/` (round trip; an old fact decodes;
  `Compatibility` passes)
- [X] T002 Regenerate `contract/schemas/` with `just schemas`; `just test` green
- [X] T003 [P] Create `services/reporting/` on `services/rewards/`'s template with ankka **0.16.0** and the
  eviction lines of `services/engagement/build.sbt` (research R2): `build.sbt`, `project/`,
  `service.json` (runtime `0.16.0`, `provisionObjectStorage`, `"personal": true`), `docker-compose.yml`
  (postgres:17-alpine on 5445), `README.md` stub, `r/ReportingService.scala` registering nothing yet; add
  `com.openhtmltopdf:openhtmltopdf-pdfbox`
- [X] T004 [P] Write `r/Settings.scala`: `CASINO_BRAND`, `CASINO_PROJECT`, `CASINO_REAL_MONEY`,
  `CASINO_REPORTING_ZONE` (attribution's parse, `a/Settings.scala:47-48`), `CASINO_MODULES`, `CLOSE_GRACE`,
  `REPORTING_GENERATION`; with `r-test/SettingsSuite.scala`
- [X] T005 [P] Update `modules/reporting/module.json` per research R14: runtime `0.16.0`, the env (keys
  `reporting-zone`, `casino-modules`; literals `CLOSE_GRACE=15m`, `REPORTING_GENERATION=1`), the two
  configuration keys, `"personal": true`, the erasure route row; add a case to
  `tools/modules/src/test/scala/.../RenderSuite.scala` that `goldvibe` renders them and `erasure-targets`
  names `reporting`, and one that reporting's `CLOSE_GRACE` equals attribution's in `modules/core/module.json`
- [X] T006 [P] Add to `justfile` the recipes `reporting-test`, `reporting-it`, `reporting-perf`,
  `reporting-record`, `reporting-parity`, `reporting-run` (HTTP 9015, DB 5445, the auth env of
  `rewards-run`), and to `.github/workflows/ci.yml` the filter `reporting:` over `services/reporting/**`,
  `contract/**`, `features/reporting/**` with its job, as `rewards`' (push over SSH: the token has no
  workflow scope)

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the amended producers, the pure packages, the intake, the day, the kit, the endpoint and
access. Nothing story-specific starts before this phase ends.

### The amended producers (research R11)

- [X] T007 [P] players: the operator's profile update accepts `isTestAccount` (the original's
  `C/PlayerProfileRoutes.scala:870`), the player entity records it, `player_registered` carries
  `test_account`, a change publishes `player_test_account_changed`; tests in the players suites; amend
  `specs/008-players/spec.md` (an FR line naming spec 015) and `contracts/routes.md`
- [X] T008 [P] game-provider: `round_settled` carries `game_name`, `game_type` from the catalogue
  (`services/game-provider/.../catalogue/`), absent when the game is not in it; confirm which session
  `session_id` is (research R20 item 2) and state it; tests in `RoundFactsSuite`; amend
  `specs/003-game-provider/spec.md` FR-019
- [X] T009 [P] wallet: `payment.dispute.closed` carries the amount and currency of the payment's dispute
  and refuses an outcome outside `won`, `lost` (`400 invalid_outcome`); movements that change restricted
  funds records carry `records` from the record model (`services/wallet/.../entity/Model.scala`); tests in
  the wallet's facts and endpoint suites; amend `specs/002-wallet/spec.md` (FR-022a, the facts' FR)
- [X] T010 [P] merchant: forward `dispute_opened` and `dispute_closed` outcomes from psp-gateway to the brand's
  wallet as the other outcomes; tests; amend `specs/004-merchant/spec.md` FR-022
- [X] T011 [P] psp-gateway: Unity Finance's deposit states `chargeback` and `chargebacked` become dispute
  outcomes (`services/psp-gateway/.../unityfinance/UnityFinance.scala:182-197`); read the eight other
  adapters for a documented dispute notification and map those (research R20 item 3); tests per adapter
  touched; amend `specs/005-psp-gateway/spec.md` with the dispute requirement

### Pure packages

- [X] T012 [P] `r/days/Days.scala` copied from `a/money/Days.scala` (`dayOf`, `endOf`, `closeDue`,
  `nextClose`), with `r-test/days/DaysSuite.scala` including the 23- and 25-hour days and a case that
  attribution's and this give the same close
- [X] T013 [P] `r/figures/` : GGR legs, the deposit pool's legs (attribution's `RealMoney.counted`
  rule), NGR, the trend arrow (`C/DashboardRoutes.scala:239-243`), rates (`null` with no attempts, one
  place), the truncated conversion rate; `r-test/figures/FiguresSuite.scala`
- [X] T014 [P] `r/ranges/` : periods, the default month, `today|7d|30d|90d` (other → `7d`), ISO weeks
  `YYYY-Www` in the zone, hourly labels; `r-test/ranges/RangesSuite.scala`
- [X] T015 [P] `r/render/Money.scala` (2-dp strings by the currency's places) and `r/render/Csv.scala`
  (the original's quoting, `C/ReportService.scala:183-186`); suites
- [X] T016 [P] `r/ledger/Types.scala`: the family → ledger type map after checking the original wallet's
  type names (research R20 item 1); suite

### Intake and the day

- [X] T017 `r/ReportingCodecs.scala`: codecs for every state, event and row; `r/intake/FactId.scala`
  (engagement's `factId`); `r/intake/Lenient.scala` (attribution's); `r/intake/Generation.scala`: the
  generation of research R8 as the first segment of every intake and day entity id and in every consumer's
  component id, so a new generation replays the topics from the earliest offset into new ids
- [X] T018 [P] `r/intake/WalletBook.scala` and its consumer `money-facts`: the event `Moved` per
  data-model.md, `casino` false for spec 022's families, the day and the round's day, open records, holds,
  `seen`; `r-test/intake/WalletBookSuite.scala` (kit: every family, a repeat, a converted stake, a trade)
- [X] T019 [P] `r/intake/Payment.scala` and `payments-facts`; suite
- [X] T020 [P] `r/intake/Withdrawal.scala` and `withdrawals-facts`; suite
- [X] T021 [P] `r/intake/Grant.scala` and `rewards-facts` (registered only when `CASINO_MODULES` names
  `casino`); suite
- [X] T022 [P] `r/intake/PlayerLog.scala` and `players-facts`: the status and KYC folds, the email as
  `Personal` under `player/<id>`, the test flag, RG and KYC document events; suite
- [X] T023 [P] `r/intake/Session.scala` (key value) and `sessions-facts`; the network address and user agent
  as `Personal`; suite
- [X] T024 [P] `r/intake/RoundCheck.scala` (key value), `rounds-facts` (only with `casino`) and `round-legs`
  from `WalletBook`; statuses `matched`, `differs`, `provider_only`, `wallet_only`; suite
- [X] T025 `r/day/BrandDay.scala`: state and events of data-model.md, `Fold` dedup by source and sequence,
  `Corrected` on a closed day, `Close` writing `DayFixed`, the test accounts' share kept beside each money
  section; `r-test/day/BrandDaySuite.scala`
- [X] T026 `r/day/ToDays.scala`: the consumer over the intake entities' events folding each into its day's
  delta, marked as a test account's when the player's `PlayerLog` says so at the fold (research R4); suite
- [X] T027 `r/day/CloseDays.scala`: the `close-days` timer every five minutes (`a/money/DayCloser.scala`'s
  shape) and the `OpenDays` query it needs; suite with `MovableClock`
- [X] T028 [P] `r/intake/Coverage.scala`: the earliest `occurredAt` per topic in the generation; suite
- [X] T029 [P] `r/settings/ReportingSettings.scala`: the key value entity, defaults and ranges of
  data-model.md, `400 invalid_setting`; suite

### The kit, the endpoint and access

- [X] T030 `r-test/it/Kit.scala`: `AnkkaTestKit` over Postgres (5445) with `InMemoryBroker` carrying the
  brand's topics and `MovableClock`; token helpers for each role; `r-test/it/FactSet.scala` publishing a
  known set of facts and computing every figure independently (research R17)
- [X] T031 `r/api/ReportingEndpoint.scala` with `r/api/Access.scala` (the admission table of
  contracts/routes.md through `withAcl`), `r/api/Wire.scala` (the common parameters, `unknown_currency`,
  `incomplete_period`), `GET /status`, `GET`/`PUT /settings`; `r-test/it/AccessSuite.scala` (each route's
  roles; another brand refused; the flag's 404)
- [X] T032 `r-test/features/ReportingSteps.scala` and `GherkinSuite` wiring over `features/reporting/`,
  with every scenario pending until its story binds it

**Checkpoint**: the producers publish the new fields, facts land once in the intake, days fold and close.

---

## Phase 3: User Story 1 — Finance runs the revenue report (P1) 🎯 MVP

**Goal**: the revenue report for a period, per currency, every player, exact, exported.

**Independent test**: quickstart §3 `RevenueSuite`; the seven scenarios of `features/reporting/revenue.feature`.

- [ ] T033 [P] [US1] Bind the seven scenarios of `features/reporting/revenue.feature` in
  `r-test/features/RevenueSteps.scala` over the `FactSet`
- [X] T034 [P] [US1] `r-test/it/RevenueSuite.scala`: three players, two days in the zone, two currencies, a
  23:30 bet, a converted and a restricted stake, the deposit pool's GGR equal to attribution's
  `RealMoney` fold per day (SC-005), a rebuild into a new generation identical (SC-004), a test account
  excluded when the setting is on
- [X] T035 [US1] `r/day/DaysView.scala` (`Days`, `between(:from, :to)`)
- [X] T036 [US1] `r/api/Reports.scala` `GET /reports/ggr`: the shape of contracts/routes.md, the default
  month, `currencies`, the test-account exclusion setting; `format=csv` with the summary row
- [X] T037 [US1] `r/render/RevenueCsv.scala` and the synchronous document path: render, put under
  `reports/report-export/<ref>.csv`, a `Document` record (T053's entity, created here in its first shape)

**Checkpoint**: US1 is shippable alone.

---

## Phase 4: User Story 2 — An operator reads the dashboard (P1)

**Goal**: the eight dashboard routes in the original's shapes, from views.

**Independent test**: `DashboardSuite`; the eight scenarios of `features/reporting/dashboard.feature`.

- [ ] T038 [P] [US2] Bind the eight scenarios of `dashboard.feature` in `r-test/features/DashboardSteps.scala`
- [ ] T039 [P] [US2] `r-test/it/DashboardSuite.scala`: more than twenty players, open sessions, payment
  health per provider with a provider without attempts (`null`), the bonus summary's both names
- [ ] T040 [P] [US2] `r/reads/Wallets.scala` (`byPlayers`, `withOpenRecords`), `r/reads/Players.scala`,
  `r/reads/Sessions.scala`, `r/reads/PaymentsView.scala`, `r/reads/WithdrawalsView.scala`,
  `r/reads/Rounds.scala` with their declared queries (data-model.md)
- [ ] T041 [P] [US2] `r/reads/PlayerDays.scala` (keyed; the row keeps the source's last sequence, research
  R20 item 4) and `r/reads/Activity.scala` (keyed over `PlayerLog` and `Grant` events)
- [ ] T042 [US2] `r/api/Dashboard.scala`: `kpis`, `trend`, `kpi-history`, `geo`, `rankings`, `activity`,
  `bonus-summary`, `payment-health`, each as contracts/routes.md

---

## Phase 5: User Story 3 — Compliance reviews players; support finds a transaction (P2)

**Goal**: the player report and the ledger, every row, searchable by part of an email.

**Independent test**: `LedgerSuite`; the five scenarios of `players.feature`.

- [ ] T043 [P] [US3] Bind the five scenarios of `players.feature` in `r-test/features/PlayersSteps.scala`
- [ ] T044 [P] [US3] `r-test/it/LedgerSuite.scala`: a player with 5,000 transactions paged at 100 and exported
  whole (SC-003); the email search; an erased player's rows read `[erased]` with figures unchanged
- [ ] T045 [US3] `r/reads/Transactions.scala` (keyed over `WalletBook` events; `between` with type, ids and
  offset)
- [ ] T046 [US3] `r/api/Reports.scala` `GET /reports/players`, `/reports/transactions`,
  `/reports/transactions/export` (JSON as the original, `format=csv`, a job over the limit)

---

## Phase 6: User Story 4 — Bonus performance and the provider report (P2)

**Independent test**: the four scenarios of `bonus-and-providers.feature`.

- [ ] T047 [P] [US4] Bind the four scenarios in `r-test/features/BonusAndProvidersSteps.scala`
- [ ] T048 [US4] `r/reads/Grants.scala` (`grantedBetween`, `byOffer`)
- [ ] T049 [US4] `r/api/Reports.scala` `GET /reports/bonus` (the original's converted statuses, truncated
  rate) and `/reports/psp` (one row per provider, `belowThreshold`, `null` rates), each with `format=csv`

---

## Phase 7: User Story 5 — A dispute report (P2)

**Goal**: the dispute report by session or range, HTML, CSV and PDF, as a job over the limit, with documents'
lifetimes and erasure.

**Independent test**: `DisputeSuite`, `DocumentsSuite`; the seven scenarios of `disputes.feature`.

- [ ] T050 [P] [US5] Bind the seven scenarios of `disputes.feature` in `r-test/features/DisputesSteps.scala`
- [ ] T051 [P] [US5] `r-test/it/DisputeSuite.scala`: session and range modes, the five 400s and two 404s
  with the original's texts, attachments named `dispute-report-{ref}.{ext}`, an unmatched round, a job past
  seven days
- [ ] T052 [P] [US5] `r-test/it/DocumentsSuite.scala`: lifetimes per kind, the sweep after a lost pause,
  erasure deleting kept documents with records kept (SC-006); `r-test/it/DumpSuite.scala`
  (`assertNoPersonalValue`)
- [ ] T053 [US5] `r/documents/Document.scala`, `r/documents/Documents.scala` (view), `r/documents/DocumentJob.scala`
  (render paged, store, pause for the lifetime, expire), `r/documents/Sweep.scala` (`sweep-documents`
  hourly), the `.players` sidecar and subject-prefixed names (research R9)
- [ ] T054 [US5] `r/render/DisputeReport.scala`: HTML and CSV in `C/ReportService.scala`'s layout (its
  `ReportServiceSpec` cases ported as `r-test/render/DisputeReportSuite.scala`), PDF from the HTML (research
  R20 item 5)
- [ ] T055 [US5] `r/api/Disputes.scala` `GET /players/{playerId}/dispute-report` and `r/api/Documents.scala`
  `GET /documents`, `GET /documents/{reference}` (`410 document_expired`)
- [ ] T056 [US5] `r/documents/Erasure.scala`: the `ErasureHandler` and `POST /erasure/players/{playerId}`
  (`Routes.playersOnly`), deleting the subject's objects and every kept document whose sidecar names the
  player (research R10)
- [ ] T057 [US5] `r/documents/Audit.scala`: the `audit` consumer publishing `report.generated` and
  `report.served` records (contracts/facts.md); suite

---

## Phase 8: User Story 6 — The day-end and reconciliation (P3)

**Independent test**: `DayEndSuite`; the four scenarios of `day-end.feature`.

- [ ] T058 [P] [US6] Bind the four scenarios in `r-test/features/DayEndSteps.scala`
- [ ] T059 [P] [US6] `r-test/it/DayEndSuite.scala`: the close at attribution's instant; providers' and games'
  sections; differences; a late fact a correction and in the next opening; byte-identical reads (SC-008)
- [ ] T060 [US6] `r/api/DayEnd.scala` `GET /day-end/{day}` from `DayFixed`, `GET /day-end/{day}/export` (a
  `day-end-export` document)

---

## Phase 9: User Story 7 — Compliance exports (P3)

**Independent test**: the six scenarios of `compliance-exports.feature`.

- [ ] T061 [P] [US7] Bind the six scenarios in `r-test/features/ComplianceSteps.scala`
- [ ] T062 [US7] `r/reads/Compliance.scala` (keyed over `PlayerLog` events: cases and document decisions)
- [ ] T063 [US7] `r/api/Compliance.scala` `GET /compliance/rg-cases`, `/compliance/kyc-decisions` with
  `format=csv` as `compliance-export` documents

---

## Phase 10: User Story 8 — Prediction markets (P3)

**Independent test**: `PredictionsSuite`; the seven scenarios of `predictions.feature`.

- [ ] T064 [P] [US8] Bind the seven scenarios in `r-test/features/PredictionsSteps.scala`, publishing
  `trade_made` with `fee_cents` and fee account movements directly
- [ ] T065 [P] [US8] `r-test/it/PredictionsSuite.scala`: the four reports from facts, fees equal to the fee
  account's credits, a market's trading moves no casino figure (SC-009), no route for a brand without the
  module
- [ ] T066 [US8] `r/predictions/Markets.scala` (topic view over `markets.events`), `r/predictions/MarketBook.scala`
  and `trades-facts` (only with `predictions`), `r/predictions/MarketFigures.scala`, `r/predictions/Traders.scala`
- [ ] T067 [US8] `BrandDay`'s predictions section folded by `ToDays` and the day-end's predictions part with the
  fee reconciliation
- [ ] T068 [US8] `r/api/Predictions.scala` `GET /predictions/summary`, `/predictions/markets`,
  `/predictions/traders`, each of the last two with `format=csv`, and the dashboard's predictions summary
  when the module runs

---

## Phase 11: Polish and cross-cutting

- [ ] T069 `r-test/perf/PerfSuite.scala`: 10,000 players and a month; revenue < 2 s, dashboard < 1 s (SC-001,
  SC-002); run with `just reporting-perf`
- [ ] T070 [P] `tests/original/reporting-driver/` (Go, `players-driver`'s shape): the seed and the 31
  recordings of contracts/recording.md; the person creates the operator users; record on kind with `just
  reporting-record` into `tests/port/reporting/`
- [ ] T071 `r-test/port/ReportsParity.scala`, `DashboardParity.scala`, `DisputeParity.scala`: a parity test per
  recording comparing shapes, with the marked divergences
- [ ] T072 [P] `services/reporting/README.md`: the components, the suites, how to run it locally
- [ ] T073 Run quickstart §1–§3 and §6 end to end; `just features` green; record the results in the pull
  request body
- [ ] T074 SC-007: on kind, point the backoffice's reporting base URL at
  `/v1/casino/brands/<slug>/reporting` (a local environment override, no code change) and run the original's
  Playwright `15-dashboard-kpis`, `27-dispute-report` and `09-casino-backoffice`; record the results in the
  pull request body, and a change the backoffice would need beyond its base URL as a finding in research.md

---

## Dependencies

- Phase 1 → Phase 2 → stories. T001–T002 before T007–T011 and every intake task.
- Within Phase 2: T017 before T018–T024; T018–T024 before T025–T026; T030 before every suite on the kit;
  T031 before every route.
- US1 needs only Phase 2. US2 needs T035. US3 and US4 need T040. US5 needs T040 (`Sessions`, `Rounds`) and
  brings `Document` to its full shape (T037's first shape before it). US6 needs T025's `DayFixed` and T053.
  US7 needs T053. US8 needs Phase 2 only, and T067 needs US6's day-end route.
- Polish after the stories it measures: T069 after US1 and US2; T071 after T070 and every route; T074
  after US2 and US5.

## Parallel examples

- Phase 2's producers: T007, T008, T009, T010 and T011 at once (different services; one sbt each).
- Phase 2's intake: T018–T024 at once after T017.
- US2: T038, T039, T040 and T041 at once; then T042.

## Implementation strategy

MVP is Phases 1–3: the producers' fields, the intake, the day, and the revenue report exact for every
player. Then US2 (the dashboard), then the P2 stories in order, then the P3. Each checkpoint is a working
service; the parity tests and the driver close the branch.
