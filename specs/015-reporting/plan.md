# Implementation Plan: reporting

**Branch**: `015-reporting` | **Date**: 2026-11-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/015-reporting/spec.md`

## Summary

`reporting` is a brand's service from the `reporting` module. It serves the backoffice's Reports tab and
dashboard at the original's paths and field names, the dispute report, the day-end with its provider
reconciliation, the compliance exports and the prediction-market reports. Every figure is a fold of the
brand's facts, exact to the minor unit, per currency and in the brand's zone. The original sampled
twenty players on every request.

Facts enter through one consumer per topic into intake entities that deduplicate them. One consumer folds
those entities' events into a `BrandDay` per reporting day. Views over both answer the reports
(research R3). Documents are objects in the brand's bucket, each with a record, a lifetime and an
erasure path. Producers are amended on this branch where a figure had no fact: the test-account flag,
game names, chargebacks and wagering progress (research R11). Decisions and evidence are in
[research.md](research.md), R1–R20.

## Technical Context

**Language/Version**: Scala 3.9 on ankka **0.16.0** (research R2), the contract evicted to it as spec
014 does. Go for the recording driver, in the original's toolchain.

**Primary Dependencies**:

- ankka: `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`, `ankka-telemetry-otlp`,
  `ankka-testkit`, all 0.16.0;
- `io.eitheror:casino-contract`, with this branch's fields (contracts/facts.md);
- `com.openhtmltopdf:openhtmltopdf-pdfbox` for the dispute report's PDF (research R9).

**Storage**: the service's own Postgres (journals, state, views, timers); the brand's bucket
(`provisionObjectStorage`) for documents.

**Testing**: munit; the entity, workflow and consumer kits; `AnkkaTestKit` over Postgres with
`InMemoryBroker` and `MovableClock`; `FactSet`; `GherkinSuite` over `features/reporting/`; `DumpSuite`;
`PerfSuite` (research R17); `contract.port.Recording` for `tests/port/reporting/`.

**Target Platform**: a brand project on the self-hosted installation (kind locally, GKE), from
`modules/reporting`.

**Project Type**: one ankka service (`services/reporting/`), a Go driver, amendments to the contract and
five services.

**Performance Goals**:

| Criterion | Target | How |
|---|---|---|
| SC-001 | revenue report < 2 s, 10,000 players, a month | ≤ 31 `Days` rows |
| SC-002 | dashboard < 1 s | today's and yesterday's `Days` rows, `Sessions` counts |
| SC-003 | 5,000 transactions of one player, all paged and exported | `Transactions` rows, `OFFSET` paging, `askStream` export |
| Day close | within five minutes of the end plus the grace | `close-days` timer |

**Constraints**:

- `Long` minor units; never summed across currencies; no FX;
- no figure from a call at request time (FR-001); no read of another service's store;
- at most one path parameter per route, one endpoint (research R13);
- every fact folded once by its fact id, every day fold once by source and sequence;
- no personal field in an aggregate or a document record; `Personal` under `player/<id>` elsewhere;
- a figure from spec 022's families never enters a casino fold.

**Scale/Scope**:

| Item | Count |
|---|---|
| Original routes ported | 15: 6 reports, 8 dashboard, the dispute report |
| New routes | 14 (contracts/routes.md) |
| Event sourced entities | 7: `WalletBook`, `Payment`, `Withdrawal`, `Grant`, `PlayerLog`, `MarketBook`, `BrandDay` |
| Key value entities | 5: `Session`, `RoundCheck`, `Document`, `ReportingSettings`, `Coverage` |
| Workflows | 1: `DocumentJob` |
| Views | 16 (data-model.md) |
| Consumers | 8 intake, `to-days`, `round-legs`, `audit` |
| Timers | 2: `close-days`, `sweep-documents` |
| Producers amended | `players`, `game-provider`, `wallet`, `merchant`, `psp-gateway` |
| Feature scenarios | 8 features under `features/reporting/` |
| Recordings | 31 from a new driver (contracts/recording.md) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings and parity tests named; divergences declared | Pass. contracts/recording.md names the driver's 31 recordings and a parity test for each. Every divergence is listed under the spec's *Changes from the original*, corrected on this branch where the spec had misdescribed the original (research, *Spec corrections*). |
| II. Money is exact, the wallet is the ledger | `Long`; no balance kept | Pass. Reporting keeps no balance of its own. Its liability and pending holds are folds of the wallet's facts, and nothing it does moves money. |
| III. A brand is a project | in-project topics; `{brand}` in routes | Pass. It runs in the brand's project, reads the brand's own topics (spec 001 FR-011's reader column) and keeps the slug in every route. |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. Every figure comes from facts. The amended producers publish the facts reporting needed instead of being queried for them. |
| V. Personal data marked, erasure honoured | `Personal`; subject prefix; handler | Pass. Rows use 0.16.0's `Personal`. Single-player documents sit under the subject's prefix, and the erasure handler and route delete every kept document that names the player (R9, R10). |
| VI. Scala by default | | Pass. Scala throughout; Go only for the driver. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1,209 scenarios. |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 and 2026-11-08 (twice). |
| Scope: ankka gaps are ankka specs | | Pass. Aggregating views, calendar timers, object lifecycles and "complete from" are missing in ankka. Each is built from existing primitives here and listed under *Findings for other specs*. |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-004: facts in, once | intake consumers → `WalletBook`, `Payment`, `Withdrawal`, `Grant`, `PlayerLog`, `Session`, `RoundCheck`, `MarketBook` | the entity decides once what a fact is and dedups it | a topic view (one row per subject, cannot fan out or sum; research R3) |
| FR-005–FR-007, FR-013, FR-014, FR-018, FR-019: days | `to-days` → `BrandDay`; `Days` view; `close-days` | a month is 31 rows; the close fixes an event | per-player day rows summed at read (too slow for SC-001) |
| FR-008: players | `Players` + `Wallets` views | one row per player and per wallet | a join in SQL (no cross-view queries) |
| FR-009: bonus | `Grants` view | grouped by offer in Scala, as the original | an offers topic (none exists) |
| FR-010: ledger | `Transactions` keyed view | a row per movement, no per-player cap | the original's per-player page |
| FR-011, FR-015: providers, health, bonus summary | `PaymentsView`, `WithdrawalsView`, `Wallets`, `Days` | figures per provider from facts | the original's constants |
| FR-014: rankings, activity, geo | `Sessions`, `PlayerDays`, `Activity`, `Players` | ranges of rows, top 10 in Scala | — |
| FR-016, FR-017: dispute report | `Sessions`, `Rounds`; `render`; `DocumentJob` | rounds by game session; unmatched from `RoundCheck` | calls to game-provider (FR-001) |
| FR-020: compliance | `Compliance`, a keyed view over `PlayerLog` events | one row per case and per document decided | — |
| FR-021, FR-022, FR-026: documents | `Document`, `DocumentJob`, `Documents`, `sweep-documents`, `audit` | record, lifetime, job and audit in one place | a signed URL as the reference (≤ 7 days) |
| FR-023, FR-024, FR-027: access, settings, shapes | `ReportingEndpoint`, `ReportingSettings` | one endpoint per prefix, `withAcl` per route | — |
| FR-025: personal data | 0.16.0 `Personal`, `ErasureHandler`, erasure route | sealed rows, erased on erasure | values kept out and fetched at export (a call per export) |
| FR-028–FR-032: predictions | `Markets` topic view, `MarketBook`, `MarketFigures`, `Traders`, `BrandDay.predictions` | a market's figures from its trades | reading the exchange's views (constitution IV) |

## Project Structure

### Documentation (this feature)

```text
specs/015-reporting/
├── plan.md              # this file
├── research.md          # R1–R20, corrections, findings
├── data-model.md        # entities, the day, views, documents, timers, consumers
├── quickstart.md        # proving it, in build order
├── contracts/
│   ├── routes.md        # every route, admission, answers, changes
│   ├── facts.md         # what is read; the fields and families added; disputes end to end
│   └── recording.md     # the driver's recordings and their parity tests
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/facts/   # CasinoPlayers, Others (rounds), PaymentsPayments,
                                                              # MoneyTransactions (RecordProgress), Exchange (fee_cents)
contract/schemas/                                             # regenerated
services/players/      # test_account, player_test_account_changed, the profile's isTestAccount
services/game-provider/ # game_name, game_type on round_settled
services/wallet/       # the dispute's amount, currency, outcome; records' progress on movements
services/merchant/     # dispute outcomes forwarded
services/psp-gateway/  # Unity Finance (and documented others) disputes
modules/reporting/module.json  # runtime 0.16.0, env, keys, personal, the erasure route row
services/reporting/
├── build.sbt, service.json, docker-compose.yml, README.md
├── src/main/scala/io/eitheror/casino/reporting/
│   ├── figures/, days/, render/, ledger/, ranges/      # pure
│   ├── intake/        # the eight intake consumers and entities
│   ├── day/           # BrandDay, ToDays, CloseDays, Days view
│   ├── reads/         # Wallets, Players, PaymentsView, WithdrawalsView, Grants, Sessions, Rounds,
│   │                  # Transactions, PlayerDays, Activity, Compliance
│   ├── predictions/   # Markets, MarketFigures, Traders
│   ├── documents/     # Document, DocumentJob, Documents, Sweep, Audit, Erasure
│   ├── api/           # ReportingEndpoint, Reports, Dashboard, Disputes, DayEnd, Compliance,
│   │                  # Predictions, Documents, Settings, Wire, Access
│   ├── ReportingCodecs.scala, Settings.scala, ReportingService.scala
└── src/test/scala/io/eitheror/casino/reporting/
    ├── (pure and kit suites per package), it/ (Kit, FactSet, the suites of quickstart §3), perf/, port/, features/
tests/original/reporting-driver/                              # Go
tests/port/reporting/                                         # the recordings
.github/workflows/ci.yml, justfile                            # reporting-test, -it, -perf, -record, -parity, -run
```

**Structure Decision**: one service directory with a package per layer, the shape of `services/attribution/`.
Its `Days` is copied into `days/` with a test that the two give the same close.

## Phase 0: research

Done: [research.md](research.md). No unknown remains. Five items are verified at task time, each with a
fallback (R20).

## Phase 1: design

[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

### Constitution re-check after design

Unchanged. One service with its own database and the brand's bucket. It reads the brand's topics, calls
nothing for a figure and writes only audit records. The producers it needed facts from were amended to
publish them; none of them reads reporting.

## Divergences (ankka-port, section 6)

Every entry under the spec's *Changes from the original* is demonstrated by a marked parity test
(contracts/recording.md) or, where the original cannot show it, by a quickstart §3 suite:

- every player, not a sample, per currency, in the zone: `RevenueSuite`, `DashboardSuite`;
- figures for payments, bonuses and chargebacks: `DashboardSuite`, `DayEndSuite`;
- the day-end and its corrections: `DayEndSuite`;
- lifetimes, jobs and PDF: `DocumentsSuite`, `DisputeSuite`;
- predictions: `PredictionsSuite`.

## What the next command needs to know

**Build order**:

1. the contract;
2. the five producers;
3. the reporting service's foundation (build, settings, intake, `BrandDay`, endpoint skeleton, access);
4. User Story 1 (revenue);
5. User Story 2 (dashboard);
6. User Stories 3 and 4 (players, ledger, bonus, providers);
7. User Story 5 (disputes, documents);
8. User Story 6 (day-end);
9. User Story 7 (compliance);
10. User Story 8 (predictions);
11. the manifest, the driver, the parity tests and CI.

**Do not**:

- run two sbt processes in one service directory;
- use another worktree's contract snapshot;
- record against anything but a local original on kind.
