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

## Found during implementation

- **T005, `"personal": true` in the manifest** (R14): the vendored ankka-vendor descriptor schema
  (`tools/modules/src/main/resources/ankka-vendor-manifest.schema.json`, `$defs/descriptor`,
  `additionalProperties: false`) has no `personal` field, so a manifest that names it is refused. The manifest
  carries `runtime` 0.16.0 and leaves `personal` out, as spec 014's engagement does; `services/reporting/service.json`
  declares it for a local run. Until the vendor's format carries it, an installation gives the service no keyring
  notices, which is the same gap CLAUDE.md names for `erasure` ("not stated" until ankka spec 042). A finding for
  spec 021: the vendor schema needs `personal`.
- **T009, records on three more families** (R11; asked 2026-11-08, answer "add records to the 3"): the wallet
  also changes a restricted funds record's balance in `admin_adjusted` (restricted target), `provider_credited`
  (landing on restricted) and `hold_placed` (a cap draw that does not close the record). Without `records` on
  them the per-record liability and progress drift until the record's next listed movement, so the optional
  `records` field is added to those three families too (contracts/facts.md updated; topics keep v1) and the
  wallet writes it.
- **T007, no audit for the test-account flag** (asked 2026-11-08, answer "no audit, as original"): the original
  audits nothing for `isTestAccount`; `player_test_account_changed` carries the operator's id, and nothing more
  is written.
- **T009, a dispute closed with no amount and none opened**: published with no amount and no currency (the wallet
  never substitutes the payment's initiated amount, which is not the dispute's). Reporting counts such a
  chargeback by number only, with zero amount, and the day-end lists it as a difference (FR-018).
- **T016, the ledger's type names** (R5, R20 item 1; asked 2026-11-08, answer "the original's 14 names"): the
  original wallet writes 14 names, not 6 (`starter-packs/wallet/handlers-go/internal/apply/props.go`): `bet`,
  `win`, `rollback`, `loss`, `deposit`, `withdrawal`, `withdrawal_return`, `withdrawal_reversal`, `bonus_credit`,
  `bonus_conversion`, `bonus_cancel`, `bonus_cap_void`, `admin_adjustment`, `game_provider_credit`. Each
  movement shows the original's name (`ledger/Types.scala`); the filter takes FR-010's six values, five exact and
  `bonus` matching every `bonus_*` type (the original's exact filter made `bonus` match nothing).
- **T010, the merchant's dispute route** (R11): the merchant had no route for a gateway's dispute, so spec 004
  gains `POST /v1/payments/{paymentId}/dispute` (the gateway's identity, ACL only, as `/fail`). A dispute is
  recorded on the payment whatever its status; providers dispute captured deposits only, so nothing filters it.
- **T018–T025, events wider than data-model.md's**: every intake event carries its fact id, so a replay rebuilds
  the seen window (1,000 ids an entity) and not only the state. `WalletBook`'s `Moved` also carries the player,
  currency, transaction id and the balances and pools after it, which the movements and liability reads need.
  `PlayerLog` gains `KycSubmitted`, without which a document's decision cannot carry its submission's time.
  `BrandDay`'s `Folded` and `Corrected` carry their source and sequence, so the last sequence per source is
  rebuilt; `Fold` carries `arrivedAt`, the correction's date. `Session` and `RoundCheck` are key value and keep no
  seen window: each fact sets a field, so a repeat is a no-op (`RoundCheck` keeps its wallet legs' fact ids).
- **T022, KYC status from three families only**: `player_kyc_verified`, `player_kyc_rejected` and
  `player_kyc_pending` carry a new value; a submission and a document's review do not move the status.
- **T025, registrations per day, not per currency**: a registration's currency is optional, so `BrandDay` counts
  registrations (and the test accounts' share) for the day, beside the currencies.
- **T025, hours to completion as a sum**: a gateway's figures keep the seconds from request to completion summed
  over the completions whose request was seen (`timed`), and the average is the sum over `timed`; a completion
  with no recorded request counts as completed and is not timed.
- **T025, what the close is given**: the games section, the round differences, the liability and the pending
  holds at close cannot be folded from events (`RoundCheck` is key value; liability and holds span every
  wallet), so `Close` carries `CloseInputs`, gathered from views by the closer. Until the day-end's reads are
  wired (US6) the closer hands empty inputs (`ReportingRuntime.closeInputs`).
- **T026, one `to-days` consumer per source**: an ankka 0.16 consumer reads one source, so `to-days` is five
  consumers (`to-days-wallets`, `-payments`, `-withdrawals`, `-grants`, `-players`, each `-g<n>`). The test flag
  is the player's `PlayerLog` at the fold (a registration's own); a flag changed later does not restate a day.
- **T028, coverage's call cached in memory**: `Coverage` is one entity per topic, so every fact calling it would
  queue the topic's intake on it. Each consumer instance remembers the earliest time it has told and calls only
  for an earlier fact; after a restart one call per topic resets it.
- **T027, the close-days timer's wiring**: a timed action has no view client, so (as attribution's `Wiring`)
  `ReportingRuntime` holds the settings and the `open-before` read, set by `ReportingService.wire` and
  `started`. No `Main` calls them yet; whatever starts the service (T030 onwards) must call both.
- **T025, pending holds at close** (open question of the intake agent, settled 2026-11-08): `WalletBook` cannot see a hold
  end on completion (the wallet's hold confirmation emits no fact), so the day-end's pending holds are the
  `Withdrawal`s requested and not yet ended at the close, read through `CloseInputs` (US6). No contract change.
- **T011, disputes at the gateway** (R11, R20 item 3): only Unity Finance documents a dispute state; the eight other
  adapters report none (evidence in spec 005 FR-017a). A dispute is recorded only on a captured deposit, each kind once
  (a re-opening after a close is ignored), and delivered after its capture.
- **T035–T036, corrections in the revenue report**: a fact arriving for a closed day is a `Corrected` event on that
  day, not a change to its fixed section (R9). The revenue report adds a day's corrections to its own day's figures
  (leaving test accounts' out when the brand excludes them), so the figures are exact and a rebuild into a new
  generation answers the same whether a day closed before or during the replay (SC-004).
- **T036, the default currency before `Wallets`**: with no `defaultCurrency` set, the report takes the currency held on
  the most of the period's days (ties alphabetical), else the first the brand holds, else EUR. US2's `Wallets` view
  (T040) replaces this with the brand's wallets. The `currencies` list is every currency held, plus the configured
  default.
- **T031, `rebuilding` in `/status`**: ankka 0.16 gives a service no read of its own consumers' lag (it is on the
  management port and in the metrics), so `rebuilding` is always false; `completeFrom` is the latest of the view's
  topics' first complete days in the served generation, null until each has folded a fact. A finding for ankka.
- **T036, an unparseable date**: contracts/routes.md is silent and the original fails; a `from` or `to` that is not
  `YYYY-MM-DD` is `400 invalid_date` naming the field. To be confirmed in the contract.
- **T037, the export over the synchronous limit**: until `DocumentJob` (T053), a revenue CSV over `syncRows` or
  `syncDays` still renders synchronously; the file is `ggr-report-<ref>.csv`, its columns `date, bets, wins, ggr,
  depositPoolGgr, bonusCosts, chargebacks, ngr, deposits, withdrawals, netCashFlow, currency, ngrBasis`, the summary row
  first dated `<from>/<to>`.
- **T031–T037, three contract additions** (asked 2026-11-08, each answered as recommended): `400 invalid_date {field}`
  for an unreadable `from`/`to`; the CSV export's file name, quoting, header and the revenue CSV's columns and
  summary row; `PUT /settings` as a partial update. contracts/routes.md carries all three.
- **T040, the default currency from `Wallets`** (R6): with no `defaultCurrency` set, every route that takes a currency
  answers in the held currency with the most wallets (`Wallets.count`, ties alphabetical), else EUR
  (`api/Currencies.scala`). This replaces T036's most-days rule for the revenue report too.
- **T042, yesterday to the same hour**: `BrandDay` kept GGR and NGR per hour only, so a section now also keeps
  deposits and withdrawals per hour (`Flow`: amount and count) and the day its registrations per hour (the test
  accounts' share too). Yesterday is read to the end of the current hour of the zone, an hour's granularity, as
  the original's hourly buckets; the day's totals are unchanged and the new fields default empty, so stored days
  read as before.
- **T042, the session KPIs' yesterday**: `activeSessions` (the alias of `activeGames`), `activeGames` and
  `playersOnline` answer yesterday 0 and trend `flat`, as the original does; no history of open sessions is kept.
- **T042, `depositVolume` as a number**: payment health's `pspBreakdown[].depositVolume` is a JSON number in the
  currency's places (the original's shape, typed `number` in the backoffice's `dashboardTypes.ts`), not the 2-place
  string every other amount is. Asked as an open question in the hand-back.
- **T042, payment health from `Days`**: today's attempts, captures and value per provider and the withdrawal hours
  per gateway are read from the day's `BrandDay` sections, which already fold them, not from the `Payments` and
  `Withdrawals` views, which serve the PSP and dispute reports.
- **T040, view details ankka 0.16 imposes**: the `Players` query listing every row is `every`, since `all` is a
  reserved name; open sessions are counted with an ad hoc `count` over `Sessions` rather than a declared query, so a
  test-account exclusion can be added to it; `Sessions` rows leave the network address and user agent out (the
  dispute report reads them from the `Session` entity).
- **T044, a fact read again after an erasure**: a rebuild (R8) replays `casino.players` and `casino.sessions`, whose
  facts still carry the erased player's email, network address and user agent; ankka refuses the write of a personal
  field of an erased subject (`Conflict`, "nothing was written"), which stopped the topic's intake for good, every later
  fact waiting behind it. `Personals.of` now asks the keyring (without making a key) and keeps such a field as
  `Erased`, so the fact folds as before without it.
- **T046, the player report's currency**: every row's lifetimes are the wallet's in the one currency answered (asked,
  else the brand's default), as the original read every player in the brand's default; a player with no wallet in it
  reads zeros. The answer carries `currency` and `currencies` as every report does.
- **T046, the ledger's answer**: the page carries no `currency` or `currencies` (each row has its currency, and the
  original's had none): the ledger lists every currency's movements. The export answers page 0, `pageSize` the number
  of rows and `totalPages` 1 (0 with none), the nearest to the original's single page of 10,000. Sorting the player
  report by a column is the screen's, as in the original; the route answers in registration order.
- **T046, the email search**: reads every `Players` row and matches the decoded email case-insensitively, as the
  original filtered the brand's players; an erased email matches nothing. The search text is not recorded in an
  export's `Document` (only that one was given).
- **T049, the bonus and provider CSVs' layout** (asked in the hand-back): routes.md gives the layout of the revenue CSV
  only. These follow it: the summary first, under `all` (the filter's every-offer value) in the first column, then one
  row per offer or provider, the columns the JSON rows' names (`currency` added to the provider rows; a `null` rate an
  empty cell). The summary's `activeCount` has no column, as the offer rows have none.
- **T049, the bonus report's grants**: those granted in the period in the brand's zone, in the currency answered; a
  free-rounds grant (no currency until it wins, amount 0) counts in every currency. Offers are ordered by id (the
  original's order was a map's); a grant with no offer is under `unknown`, its name and type `UNKNOWN` as the original
  when none of its grants carries one. `expired` counts in the total only, as the original.
- **T049, the provider report from `Days`**: attempts, captures and value per payment provider and requests,
  completions and hours per gateway are the period's `BrandDay` sections, summed; a provider and a gateway of the same
  name are one row, ordered by name. Test accounts are counted (the setting excludes them from the dashboard and the
  revenue report only, spec Edge cases). `belowThreshold` is a rate under 95, false when the rate is `null`.
- **T042, `depositVolume` a string** (asked 2026-11-08, answer "make it a string"): payment health's
  `pspBreakdown[].depositVolume` follows the common rule, a 2-dp string, where the original answered a number; the
  backoffice's payment-health panel must parse it, which T074 (SC-007) records as a change beyond the base URL.
- **T049, the bonus and provider CSVs** (asked 2026-11-08, answer "summary row first"): the summary first under
  `all`, then one row per offer or provider; the bonus CSV gains `activeCount`, filled on the summary row.
- **T053, `Document` event sourced**: a consumer of a key value entity sees only its latest value (ankka
  consumers.md), so a serving followed quickly by another, or a readying followed by a serving, could make one
  audit record where FR-026 asks two. `Document` journals `Started`, `Progressed`, `Readied`, `Served`, `Expired`
  and `Failed`; the `Documents` view and the `document-audit` consumer read the events.
- **T053, views read from a workflow, a timed action and the erasure handler**: ankka 0.16 offers a view client to
  endpoints only, so `ReportingRuntime` holds the running service's (`started` sets it, as players' runtime does) and
  the clock (the kit's `MovableClock` in tests).
- **T053, a job's pages**: a step renders 1,000 rows of an export (the ledger read a page of the view at a time, the
  other reports computed and sliced) or a dispute report's login sessions up to 1,000 rounds (one at least), and
  keeps each page as an object `<object>.parts/<n>` with the player ids it names beside it (`.players`), never in the
  journal; a dispute report's running totals (amounts only) are carried in the job's state. `store` joins the pages,
  writes the document and its `.players`, deletes the pages, then pauses for the lifetime read at that moment.
- **T053, a pause's deadline is on the system clock**: ankka 0.16's workflow engine sets a pause's deadline from
  `System.currentTimeMillis()` while the timer sweeper fires it by the runtime's clock, so in a test whose clock has
  moved past the system's a pause wakes at once. The `expire` step expires only a document whose `expiresAt` has
  passed by the service's clock; an earlier wake ends the job and the hourly sweep expires the document on time.
- **T053, the sweep reads a view**: a sweep run finds only what `Documents` holds; a document readied a moment
  before a run is expired by the next, an hour later at most.
- **T053, an email search in a job**: the ledger's `playerEmail` is resolved to player ids when it is asked, and the
  job and the record carry the ids (`ids`) and `playerEmail: "given"`, never the email.
- **T055, the dispute report's range in the brand's zone**: `fromDate` and `toDate` are reporting days in the brand's
  zone (the original read them as UTC days); `periodFrom` is the first day's midnight and `periodTo` the last day's
  final second. A range over `syncDays` days (inclusive) or a report of more than `syncRows` rounds is a job.
- **T055, which session is not found**: a `sessionId` that is not a login session of that player (none, another
  player's, or a game session) is `404 session_not_found`; a player with no registration is `404 player_not_found`.
  The currency is the player's registration currency, else EUR.
- **T055, a round the provider's record lacks** (FR-017, R20 item 2): `WalletFigures` keeps its first leg's time,
  and such a round is listed under the game session of its login session that spans that time, else under a game
  `unknown` with no type; its stake is the wallet's stake less rollbacks, its win the wallet's, its status
  `unmatched`. A game's name and type come from its rounds; with none, its game id and no type.
- **T055, the dispute report's HTML escaped**: every value is HTML-escaped (the original interpolated them), so the
  same text renders as XHTML for the PDF.
- **T055, documents by kind's roles**: `GET /documents/{reference}` admits by the record's kind (an export the
  reports roles, a dispute report any operator, day-end adds `brand_finance`, compliance `brand_compliance`),
  `403 forbidden` otherwise; an export is `404` while the Reports tab is hidden. While generating it answers
  `202 {reference, status, progress}` (a whole percent); a failed job `500 {error: "document_failed"}`.
  `GET /documents` answers `{documents: [...]}`, the records of the kinds the caller is admitted to.
- **T054, the PDF**: openhtmltopdf-pdfbox 1.0.10 renders the report's HTML (as XHTML: the two named entities as
  numbers, the meta element closed) with its tables; verified by extracting its text with PDFBox, on JDK 27 here
  (no JDK 21 on this machine; the image runs Temurin 21).
- **T056, erasure**: the handler and the route erase the subject's prefix (a dispute report is kept under it) and
  end every kept document naming the player by subject, in its `.players`, or in a running job's pages. A document
  readied too recently for the view is missed by the route and the handler; a job storing one checks the keyring
  and drops it. The module manifest's route row for `erasure-targets` (R10) is outside this service and not made.
- **T057, every document audited**: `report.generated` on `Readied`, `report.served` on each `Served`, for every
  kind, synchronous or a job's; `record_id` `audit/document/<reference>/<sequence>`, the period `<from>/<to>`.
- **T055, the documents routes** (asked 2026-11-08, answer "as built"): `500 document_failed` for a failed job,
  `progress` a whole percent, and `GET /documents`' shape and default period; routes.md carries them.
- **T060, the close's inputs**: `close-days` hands `BrandDay.Close` what the views hold when it runs: the day's rounds
  (`Rounds` by day) for the games and the differences, the open restricted funds records' balances (`Wallets`) per
  currency for the liability, and the withdrawals requested before the day's end in its zone and not ended by it for
  the pending holds. A close that runs late reads the liability later than the day's end; the views keep no balance
  at an instant to read it then.
- **T060, a round only the wallet holds** is counted under the game `unknown/unknown` (the provider and the game
  `unknown`), its provider GGR 0; a cancelled round's provider figures count nothing; the wallet's stake is net of
  rollbacks. A difference names the provider, `""` for a round only the wallet holds.
- **T060, the day-end read**: rendered from the day's entity state (`DayFixed`), not the view, so a closed day reads
  the same bytes on every read (SC-008); every list is sorted. Its `reference` is a name-based UUID of the brand, the
  generation and the day, so it is stable. A day not closed, with a record or none, is `409 day_open`; a day that is
  not a date `400 invalid_date` naming `day`. Late facts add entries to `corrections` and nothing else changes.
- **T060, the day-end's JSON** fills what routes.md leaves to the build: a correction's `figures` (the currency and
  the day's figures it changed, money as decimal strings), the deposits per provider (`provider`, `count`, `amount`;
  a provider with attempts and no completed deposit is listed with 0), withdrawals per gateway, the games per
  provider and game with `providerGgr` and `walletGgr`, and the totals per currency.
- **T060, the day-end's export** is long-format CSV (`section, currency, item, field, value`), since its sections have
  different columns: a `day-end` section first with the export's own reference, the day-end's reference, the day, the
  zone and `closedAt`. It is `day-end-report-<reference>.csv`, a `day-end-export` document under that kind's
  lifetime; the export's reference is fixed before its rows are read, so the file prints it.
