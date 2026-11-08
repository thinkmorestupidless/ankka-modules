# Research: reporting

Evidence for the plan. Three surveys were made on 2026-11-08: the original's surface
(`casino-service`'s `ReportingRoutes`, `DashboardRoutes`, `ReportService` and the backoffice), the facts
on the brand's topics as built here, and what ankka offers. Paths: `O/` is
`eitheror-platform/`, `C/` is `O/substrate/casino-service/src/main/scala/io/eitheror/casino/`, `F/` is
`O/casino/frontend/src/`; repository paths are relative to the root.

## R1. What the original is

Six `/reports/*` routes (`C/ReportingRoutes.scala:135-545`), eight `/dashboard/*` routes
(`C/DashboardRoutes.scala:77-897`) and the dispute report (`C/DashboardRoutes.scala:1275-1488`,
`C/ReportService.scala`). Every figure is computed on the request from player twins and wallet calls.
contracts/routes.md lists every route, its query, its answer and what changes here.

Where the spec misdescribed the original, it is corrected on this branch (Spec corrections, below): the
dashboard's admission, the ledger's truncation at 100, the dashboard's FX conversion, the browser-built
CSV, the rankings' four ranges, the bonus summary's two field-name sets, the flag's 404, the UI's
thirty-day default, the dispute report's lack of an asynchronous path, the truncated conversion rate.

## R2. One service, Scala, on ankka 0.16.0

**Decision**: one service, `services/reporting/`, in the brand project, from `modules/reporting`. Scala
3.9 on ankka **0.16.0**, the contract evicted to it as `engagement` (spec 014, research *Found during
implementation*) and the predictions services do.

**Rationale**: the rows naming a player hold the email, the dispute report's network address and user
agent (FR-025). Constitution V wants them `Personal` under the player, sealed at rest and reading
`Erased` after erasure; ankka 0.13 has no such type (its `Personal` is the contract's marker), 0.16.0
(feature 042, `v0.16.0`, published in `~/.ivy2/local`) has `Personal.Present/Erased`, sealed rows,
`ErasureHandler` and `Bucket.erase`. The module's `runtime` moves to 0.16.0.

**Alternatives**: 0.13.0 with personal values kept out of every row and fetched from `players` at
export (constitution IV forbids a call at request time for a figure, and a row without the email cannot
be filtered by it); rejected.

## R3. How facts become figures: intake entities, one day entity, views

ankka gives a topic view one row per `ce-subject` (`docs/build/views.md`); it cannot aggregate or fan one
fact into several rows, and a keyed view cannot read a topic (ankka 031 FR-005). Declared queries return
rows, never sums.

**Decision**: three layers.

1. **Intake.** One consumer per topic (`ChangeSource.fromTopic`, `Lenient` as attribution, `StartFrom.Earliest`)
   hands each fact to an event sourced entity per subject that decides once what the fact is and records it
   as an event: `WalletBook` (a wallet, `<player>/<currency>`), `Payment` (a payment id), `Withdrawal` (a
   payout id), `Grant` (a bonus id), `PlayerLog` (a player), `Session` (a session id), `RoundCheck` (a
   round), `MarketBook` (a market). Each keeps a window of recent fact ids (attribution's `seen` pattern), so
   a redelivery is a no-op.
2. **The day.** One consumer over those entities' events, `ToDays`, folds each event into `BrandDay` (one
   event sourced entity per reporting day), deduplicating by the source entity and its event sequence (a map
   of the last sequence per source, bounded by the sources active that day). `BrandDay` holds, per currency,
   the day's figures, the 24 hourly buckets of GGR and NGR, the provider breakdowns, the per-game round
   figures and the predictions figures; it closes, fixes the day-end and records corrections (R7).
3. **Views.** Plain views over entity state (one row per entity: `Days`, `Wallets`, `Players`,
   `PaymentsView`, `WithdrawalsView`, `Grants`, `Sessions`, `Rounds`, `Markets`, `Documents`) and keyed
   views over entity events where a row is per fact or per player and day (`Transactions`, `PlayerDays`,
   `Activity`, `Traders`). A report is a declared query over one view and a fold in Scala, never across
   currencies.

**Rationale**: the revenue report for a month reads at most 31 `Days` rows (SC-001), the dashboard reads
today's and yesterday's (SC-002), and nothing scans every fact on a request. Every layer rebuilds: views
by their version, the entities by a new `generation` in their ids replayed from the topics (R8).

**Alternatives**: per-player day tallies summed at read (attribution's `DayTally`): 300,000 rows for a
month at 10,000 players, too slow for SC-001; one entity per fact family: the same day figure from seven
places.

## R4. Which facts count, and on which day

- **GGR** (FR-005): stakes of `bet_placed`, wins of `win_credited` and `provider_credited`, rollbacks of
  `bet_rolled_back` reduce the stake, `round_settled_as_loss` moves nothing. Each counts on **its own
  day** in the brand's zone (the original's buckets are by transaction time, `C/ReportingRoutes.scala:43-53`).
- **The deposit pool's GGR**: `RealMoney.counted` of attribution exactly
  (`services/attribution/.../money/RealMoney.scala:25-38`): the deposit parts of `bet_placed`,
  `win_credited`, `bet_rolled_back`; a provider credit and a loss count nothing; a leg counts on **its
  round's day**, the day of the round's first stake (attribution's `RoundIndex`). SC-005 holds only with the
  same rule; `WalletBook` keeps each open round's day as `RoundIndex` does. The spec's FR-005 says so.
- **Bonus cost** (FR-005, spec 011 FR-028): `bonus_granted.amount_cents` and `free_spins_settled.win_cents`
  from `casino.rewards`, on their own day.
- **Chargebacks**: the amount of `payment.dispute.closed` whose outcome is `lost` (R11).
- **Deposits**: `deposit_confirmed` (count, amount); **withdrawals**: `withdrawal_completed` on
  `casino.withdrawals` (the wallet's hold confirmation emits no fact, `services/wallet/.../publish/Facts.scala:181`).
  Net cash flow is their difference.
- **Excluded from every casino fold** (FR-005, FR-032): `reservation_placed`, `reservation_released`,
  `settlement_debit`, `settlement_credit`, `resolution_payment`, `account_debit`, `account_credit`,
  `account_adjusted` (spec 022). No casino family is ever written for a trade
  (`contract/.../facts/MoneyTransactions.scala:122-199`), so excluding these excludes every trade.
- **Test accounts**: counted, as the original; excluded from the dashboard and the revenue report only when
  the brand's setting says so (FR-024), by the flag on the player (R11). A day's figures are aggregates, so
  `BrandDay` keeps the test accounts' share of each money section beside the total (`ToDays` reads the
  player's flag from `PlayerLog` as it folds) and a read with the setting on subtracts it. A flag changed
  later does not restate closed days; open days fold from the change.

## R5. The ledger, the player report and the email search

- **Ledger** (FR-010): `Transactions`, a keyed view over `WalletBook` events, one row per movement:
  `transactionId`, timestamp, player id, type, amount, currency, reference. The six types map as the
  original's wallet types: `deposit` ← `deposit_confirmed`; `withdrawal` ← `hold_placed` (the wallet's
  withdrawal transaction, verify at task time against `O/substrate/wallet-service`'s type names, R20);
  `bet`, `win` (with `provider_credited`), `rollback`; `bonus` ← `restricted_funds_awarded`. Paged at 100
  with `OFFSET`; newest first by `occurredAt`; no per-player cap (SC-003).
- **Email search**: the email is `Personal`, and a declared query cannot compare it except by equality on a
  lookup field (ankka 042). The original matches part of an email case-insensitively. The service resolves
  the part to player ids by reading the `Players` rows (one per player, 10,000 at most a brand's size here)
  and decoding the email, then asks `Transactions` for `player_id = ANY(string_to_array(:ids, ','))`.
- **Player report** (FR-008): `Players` (a view over `PlayerLog` state: registration, country, currency,
  email, status, KYC status, last login, test flag) for the period, joined in Scala with `Wallets` rows (a
  view over `WalletBook` state: lifetime deposits, wagered, won, restricted balance). Status is the last
  `new_value` of the status families, `active` with none; KYC status the last of `player_kyc_*`, `unverified`
  with none. Rows are all those in the period, as the original (`totalCount`, no paging, sorted by the
  backoffice).

## R6. The dashboard

- KPIs, history, trend: `Days` rows. Today's hourly trend from `BrandDay`'s buckets; 7 and 30 days daily;
  90 days in ISO weeks of the zone (`YYYY-Www`), as the original's labels.
- Trend arrow: the original's rule, both zero `flat`, yesterday zero or today above 1.02× `up`, below 0.98×
  `down` (`C/DashboardRoutes.scala:239-243`).
- Players online, active games: counts of open `Sessions` rows by kind; `activeSessions` the alias of
  `activeGames` (`C/DashboardRoutes.scala:90-102`).
- Rankings (`today|7d|30d|90d`): games by game sessions started (`Sessions` rows in the range, grouped by
  game; the name and provider from `Rounds`' latest row for the game); players by GGR from `PlayerDays`
  rows in the range, top 10, with country and the truncated email (20 characters and `…`) from `Players`.
- Activity: `Activity`, a keyed view over `PlayerLog` and `Grant` events, today's registrations, awards,
  self-exclusions and suspensions, newest first, 50 (`C/DashboardRoutes.scala:712-790`), event ids as the
  original's (`reg-<pid>`, `bonus-<grant>`, `selfex-<pid>`, `susp-<pid>`).
- Bonus summary: open records and liability from `Wallets` (each wallet's open records with their balance,
  status and progress, R11); `activeBonusCount` counts records with status `ACTIVE`; liability sums every
  open record's balance; progress is the mean of `round(progress / requirement × 100)` over active records
  with a requirement (`C/DashboardRoutes.scala:812-831`); today's cost from `Days`. Answered under both
  field-name sets (FR-015).
- Payment health: the day's `PaymentsView` and `WithdrawalsView` figures per provider; a rate with no
  attempts `null`.
- Currency: the default is `ReportingSettings.defaultCurrency`, and with none set the currency most of the
  brand's wallets are in (spec 008's currency configuration is a key value entity in `players`, on no
  topic); any other currency the brand holds figures in is selectable with `currency=`. Nothing is
  converted (FR-003).

## R7. Day close, day-end and corrections

- **When**: a recurring timer every five minutes closes every open day whose end in the brand's zone plus
  the grace has passed: attribution's `DayCloser` and `Days.closeDue` (`services/attribution/.../money/Days.scala:20`),
  copied with the same grace (the core manifest gives attribution `CLOSE_GRACE` `15m` as a literal; the
  reporting manifest gives the same literal, and a manifest test holds the two equal, R14), so a day closes at the instant `ggr-daily` does (FR-006). ankka timers are intervals only (ankka
  032); a sweep has no DST trouble.
- **What**: `BrandDay.close` fixes the day-end: an event `DayFixed` holding, per currency, every section of
  FR-018 and FR-031 and the zone the day was closed in. A read renders from that event, so it is
  byte-identical (SC-008).
- **Differences**: a round's provider figures (from `round_settled`) and its wallet figures (stake, win
  and rollback legs from `WalletBook`) meet in `RoundCheck`; at close the day-end lists every `Rounds` row of
  the day whose figures differ or that one side lacks.
- **Late facts**: an event whose day is closed lands in `BrandDay` as `Corrected` (dated when it arrived,
  the figures it moves) and changes nothing fixed; the next day-end opens with the corrections that arrived
  during its day (FR-019).
- **Pending holds at close**: `hold_placed` less `hold_released`, `withdrawal_reversed` and
  `withdrawal_completed` joined by `withdrawal_request_id` = `payout_id`; `Withdrawal` holds both sides.
- **Zone change**: a closed day keeps its zone (in `DayFixed`); an open day is closed in the zone of the
  close; the day of the change says so.

## R8. Rebuild and "complete from"

- Views rebuild by `version`. The intake and day entities rebuild by a `generation` in their ids (a setting,
  `REPORTING_GENERATION`): a new generation reads the topics from the earliest offset into new ids, the old
  ones are left and later dropped. A report is served from the generation in the settings.
- ankka has no "complete from" (it reports a retention gap on a rebuild, ankka 043). The service records, per
  topic consumer, the earliest `occurredAt` it folded in the current generation (a key value entity
  `Coverage`), and the status answers, per view, the first whole reporting day after it. A report for an
  earlier period is refused `409 incomplete_period` naming that day (FR-004).

## R9. Documents, jobs and lifetimes

- **Record**: `Document` key value entity per reference (a UUID, the original's `referenceId`): kind,
  parameters, period, who asked, generated at, ready at, object name, status (`generating`, `ready`,
  `expired`), the player ids a document names (a sidecar object for a document naming many, below). No
  personal field (SC-006).
- **Generation**: under the synchronous limit, in the request: rows read with `askStream`, rendered, put in
  the bucket, answered as the attachment. Over it (500 rows by default; seven days for a dispute report;
  the brand's setting), a `DocumentJob` workflow: `202 {reference}`, a status route, the document served
  when ready, as players' `ExportWorkflow` (`services/players/.../erasure/ExportWorkflow.scala`). The HTTP
  gateway bounds a response at 15 s; the limit keeps a synchronous one inside it.
- **Lifetime**: the workflow pauses for the kind's lifetime (`thenPause`) and then deletes the object and
  marks the record `expired`; an hourly sweep timer deletes any ready object past its lifetime that a lost
  pause left (R9's belt and braces, as `game-provider-simulator`'s `SweepAction`).
- **Names**: a document naming one player (the dispute report, a traders row's export for one player) is
  stored under `ObjectRules.subjectPrefix(DataSubject("player/<id>"))`, so `bucket.erase(subject)` takes it;
  every other document under `reports/<kind>/<reference>.<ext>`, with the player ids it names in
  `reports/<kind>/<reference>.players` (ids are not personal, spec 001 FR-024).
- **Erasure** (FR-021): the `ErasureHandler` and the erasure route (R10) delete the subject's objects and
  every kept document whose `.players` names the player, mark each record `expired` with reason
  `erasure`, and leave the records.
- **PDF**: the dispute report as PDF (spec 117 FR-009, new): rendered from the same HTML with
  `openhtmltopdf` (Apache-2.0, pure JVM). Verify at task time that it renders the report's tables (R20); the
  fallback is a plain PDFBox layout of the same sections.

## R10. Personal data and erasure

- `Players` rows hold the email as `Personal[String]` under `player/<id>`; `Sessions` rows the network
  address and user agent likewise; converted from the contract's marker as a fact is read (engagement R15).
  Aggregates (`Days`, `BrandDay`, `PlayerDays`, `Markets`) hold none.
- The service declares `"personal": true`; it registers `withErasureHandler`, and serves
  `POST /v1/casino/brands/<slug>/reporting/erasure/players/{playerId}` (`Routes.playersOnly`) which does the
  same, answering `{service, subject, documents: n}`. The module's manifest adds the route row so the
  modules tool puts `reporting` in `erasure-targets` (`tools/modules/.../Render.scala:244-262`).
- A CSV decodes `Personal` when it is rendered and is kept only for its lifetime (FR-025); an erased value
  renders as `[erased]`.

## R11. Facts amended on this branch

Decided on 2026-11-08 (spec Clarifications, Session 2026-11-08 (plan)). Every field is optional
(`contract/.../facts/Compatibility.scala`); contracts/facts.md has the shapes.

| Producer (spec) | Change | Why |
|---|---|---|
| `players` (008) | `test_account` on `player_registered`; family `player_test_account_changed {test_account, actor_id}`; the operator's profile update takes `isTestAccount` as the original's (`C/PlayerProfileRoutes.scala:870`) | FR-008, FR-024: the flag no fact carried; 008's plan left it to 015 (`specs/008-players/plan.md:285`) |
| `game-provider` (003) | `game_name`, `game_type` on `round_settled`, from its catalogue (`catalogue/`, the category is the type); `session_id` stated to be the game session's id | FR-014, FR-016, FR-018 name and group by game; the original read them from the game-session twin |
| `wallet` (002) | `amount_cents`, `currency` on `payment.dispute.closed` and `outcome` enumerated `won` \| `lost` (the brand's); `records` on `bet_placed`, `win_credited`, `bet_rolled_back`, `restricted_funds_*`: each restricted funds record the movement changed, `{award_id, status, balance_cents, progress_cents, requirement_cents}` after it | FR-005 chargebacks; FR-015 progress, active count and liability per record exact (the wallet holds `releaseProgress` and `wageringRequirement`, `services/wallet/.../entity/Model.scala:135-189`) |
| `merchant` (004), `psp-gateway` (005) | a provider's dispute reported as an outcome on the merchant's outcome route: Unity Finance's deposit states `chargeback` (opened) and `chargebacked` (closed, `lost`) (`services/psp-gateway/.../unityfinance/UnityFinance.scala:182-197`, today ignored); for the other eight adapters, their documented dispute notifications where they have one, else nothing (R20) | NGR's chargebacks are figures; **new**: the original logs them "Phase 2 (not handled)" (`O/services/psp-gateway/internal/handler/webhook.go:401`) |
| contract only (023) | `fee_cents` on a `trade_made` leg | FR-028–FR-031 read the taker fee when spec 023 writes it; zero until then |

## R12. Prediction markets

- `Markets`: a plain topic view over `markets.events` (`ce-subject` = market id), one row per market:
  question, category, currency (from `market_drafted`/`market_changed`), status and its times, the
  resolution, the first resolved outcome and any correction, voided.
- `MarketBook` (event sourced, market id) from `exchange.trades`: dedups by trade id and position id;
  holds contracts traded, notional volume (Σ quantity × the traded outcome's price), trades, the traders set,
  positions per player and outcome (open interest: shares bought less sold less paid), fees (Σ legs'
  `fee_cents`), resolution payments (`position_paid`), remainder kept.
- `Traders`: a keyed view over `MarketBook` events, row `<player>/<market>`: contracts, notional, fees paid,
  positions paid, net result (Σ paid − Σ charge + Σ released over the player's legs and payments).
- `ToDays` folds `MarketBook` events into `BrandDay`'s predictions section; the fee account's credits come
  from `WalletBook`'s `account_*` movements with `account_kind = "fee"` (spec 022's when built), kept apart
  from every casino figure.
- `exchange.orders` is not read: no figure needs it (open interest is from fills and payments). The spec's
  FR-001 is corrected.
- Shown only when `CASINO_MODULES` names `predictions` (FR-032); the consumers of `casino.rounds`,
  `casino.rewards` register only when it names `casino`, as attribution (`AttributionService.scala:27, 59`).

## R13. Routes under ankka's limits

One endpoint, prefix `/v1/casino/brands/<slug>/reporting` (spec 008's `OperatorEndpoint` owns
`/v1/casino/backoffice/<slug>`; no two endpoints may share a prefix). Beneath it the original's paths:
`/reports/...`, `/dashboard/...`, `/players/{playerId}/dispute-report`; and the new: `/day-end/...`,
`/compliance/...`, `/predictions/...`, `/documents/{reference}`, `/settings`, `/status`, the erasure route.
At most one path parameter each. The backoffice's base URL for these becomes the prefix (spec 001 FR-033).
Admission per route by `withAcl`: `Routes.operatorHolding(slug, "brand_admin", "resource_admin",
"platform_admin")` for `/reports`; `Routes.operator(slug)` for `/dashboard` and the dispute report;
`operatorHolding` with `brand_compliance` added for `/compliance`, with `brand_finance` for `/day-end`;
`markets` added for the market report (FR-032).

## R14. Settings and the manifest

- `ReportingSettings`, a key value entity (`brand`): `reportsTab` (default `true`), `excludeTestAccounts`
  (`false`), `defaultCurrency` (none), `syncRows` (500), `syncDays` (7), `lifetimeHours` per kind (24; 1–2,160, refused `400
  invalid_setting` naming the range). `GET`/`PUT /settings`, `brand_admin` or `platform_admin`.
- The manifest (`modules/reporting/module.json`) gains: `runtime` 0.16.0; env `CASINO_REPORTING_ZONE`
  (`reporting-zone`), `CASINO_MODULES` (`casino-modules`), `CLOSE_GRACE` `15m` and
  `REPORTING_GENERATION` `1` as literals; the two configuration keys; the erasure route row;
  `"personal": true`; a `modules` test asserts that reporting's `CLOSE_GRACE` equals attribution's.

## R15. Audit

Every document generated and every export served is an `audit_record` on `platform.audit` (FR-026): action
`report.generated` / `report.served`, actor the operator, subject `document/<reference>`, the period and
kind in `changes`. Published from `Document` events by a consumer, the pattern of rewards' audit publisher.
No other fact is produced.

## R16. Money and rendering

`Long` minor units throughout; rendered at the boundary as the original's 2-dp strings by the currency's
places (`contract.money`). Rates: `depositSuccessRatePct` a number to one place or `null`;
`conversionRatePct` the truncated whole percent; `avgWithdrawalHours` to one place.

## R17. Testing

munit; the entity, workflow and consumer kits; `AnkkaTestKit` over Postgres with `InMemoryBroker` carrying
the brand's topics and `MovableClock`; a `FactSet` builder that publishes a known set of facts and computes
the expected figures independently (SC-001, SC-004, SC-009); `GherkinSuite` over the eight
`features/reporting/*.feature`; `DumpSuite` (`assertNoPersonalValue`) for SC-006; a `PerfSuite` with 10,000
players and a month (SC-001, SC-002, SC-003), on purpose only. The amended producers' own suites gain a
test per new field.

## R18. Recordings

No recording of reporting exists, and the Go suite never calls `/reports`, `/dashboard` or the dispute
report (`O/tests/e2e/`). A new driver, `tests/original/reporting-driver/`, in the players-driver's shape,
records the original on kind (contracts/recording.md): every route, the refusals, and the flag off. Its
figures are the twenty-player sample's, so the parity tests compare **shapes** (field names, types,
statuses, headers, refusal texts), and every figure is held to the fact set instead (spec *Recordings*).
Creating the operator users and fetching their tokens needs the person (the repository's practice).

## R19. Build order

The contract amendments first (published with a unique `CASINO_VERSION`), then the producers (players,
game-provider, wallet, merchant, psp-gateway), then the reporting service's packages in the order of
the user stories, then the manifest, the driver and the parity tests. quickstart.md proves each.

## R20. Verify at task time

1. The original wallet's transaction type names behind the ledger's `withdrawal` and `bonus` (R5): read
   `O/substrate/wallet-service`'s transaction types before mapping; fallback: the map in R5.
2. Which session `round_settled.session_id` is for ST8 (R11): read `services/game-provider/.../rounds/`
   and the ST8 adapter; fallback: the dispute report places a round under its game session by the
   wallet's time window.
3. The eight other PSP adapters' dispute notifications (R11): read each adapter's webhook states; one with
   none reports nothing.
4. A keyed view row read-and-update is idempotent on a redelivered event (R3's `PlayerDays`, `Traders`):
   keep the source's last applied sequence in the row and skip an older one; fallback: one row per event,
   summed at read.
5. `openhtmltopdf` renders the dispute report's tables on JDK 21 (R9).

## Spec corrections (applied on this branch)

From R1, R4 and R12, with the decisions of 2026-11-08:

1. Context: the sample covers the GGR report, the dashboard's money KPIs, history, trend and the players'
   rankings; the ledger truncates at 100 (the wallet clamps 200); the export is JSON made CSV by the
   browser; the dashboard converts currencies by FX.
2. Who may read: the dashboard and the dispute report admit every operator; `brand_compliance`,
   `brand_finance` are spec 135's additions; the flag also 404s the routes.
3. FR-005: the deposit pool's GGR counts as `ggr-daily` does; chargebacks are disputes lost.
4. FR-007: the backoffice sends thirty days. FR-009: offer names from grants; the rate truncated.
5. FR-011, FR-015: a rate with no attempts is null; the bonus summary answers both field-name sets.
6. FR-012: server CSV is a change. FR-014: four ranking ranges. FR-016: game name and type from rounds,
   status the outcome. FR-020: KYC decisions per document. FR-022: the dispute job is new. FR-023: admission.
7. FR-029: a corrected market shows its first resolution, the correction and the payments made.
8. FR-001: `exchange.orders` is not read (R12).
9. Changes, Assumptions, Dependencies: the amended producers, ankka 0.16.0.

## Findings for other specs

- **Spec 023 / ankka-predictions 007**: the taker fee and its `fee` account are not built; this branch adds
  only the optional `fee_cents` to the contract. A correction after finality pays nothing again; the market
  report shows what exists.
- **Spec 010**: `rg_case_closed.closure` is not enumerated; the compliance export prints it as published.
- **Spec 011**: no topic lists offers; an offer with no grant in a period has no bonus-report row.
- **Spec 008**: `pending_email_verification → active`, `blocked` and erasure states emit no fact; a player's
  report status reads `active` for them. The four statuses the report filters by are exact.
- **ankka**: no aggregating view, no calendar timer, no object lifecycle, no "complete from"; each is worked
  around here (R3, R7, R9, R8) and is a candidate feature.

## Declined remediations

(None.)

## Design finished

2026-11-08: analyze reported nothing above LOW (round 2; round 1's one high, two medium and two low findings applied).
