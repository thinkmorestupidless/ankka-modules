# Implementation Plan: kyc

**Branch**: `009-kyc` | **Date**: 2026-10-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/009-kyc/spec.md`

## Summary

`kyc` is the brand service that holds a player's identity documents and their reviews, derives the
account's verification status, and answers the two questions the rest of the brand asks: the
**deposit check** (`players`, before a deposit starts) and the **play check** (`game-provider`, at
launch). It owns the brand's KYC policy, the verification window, lifetime deposits per currency, and the
flags that put a player in the compliance queue. The documents live in the service's own bucket, reached
by signed links.

The service has:

- one event sourced entity per player (`KycRecord`: documents, offers, status, mode, required categories,
  source of funds, deadline, flags, profile completeness, deposits);
- one per brand (`PolicyEntity`);
- a view (`KycQueue`) and one timer kind (`kyc-window-<player>`);
- four consumers in and four out (`DepositsConsumer`, `ProfileConsumer`, `RegistrationConsumer` and
  `ClosureConsumer`; `RecordTimers`, `PolicyFlagger`, `KycPublisher` and `AuditPublisher`);
- four endpoints.

The status rules are a pure port of the original's `rollup.go`, held to its 68 tests. Decisions are in
[research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.13.0 (constitution VI; the versions every service here
pins).

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `software.amazon.awssdk:s3` (v2), configured as `platform/object-storage.md` says: path style,
  `ANKKA_S3_REGION`, checksums when required, `GenerationAsVersionId` (R7);
- `io.eitheror:casino-contract`: `Routes.player`, `Routes.operator`, `Routes.serviceOf`,
  `Routes.public`, `Routes.playerId`, `Personal`, `CasinoPlayers` (the seven KYC families,
  `player_registered`, `player_profile_updated`, `player_closed`), `MoneyTransactions`
  (`deposit_confirmed`), `PlatformAudit`, `port.Recording`.
- **Contract change** (additive, R13): `player_kyc_pending.reason: Option[String]`.

**Storage**:

- the service's own Postgres from ankka (journal, durable state, view, timers);
- the service's own bucket (`provisionObjectStorage`, `exposeObjectStorage`; already in
  `modules/core/module.json`).

There is no secret beyond the bucket credential the platform writes (R16).

**Testing**:

- munit;
- `EventSourcedTestKit` for `KycRecord` and `PolicyEntity`, `ConsumerTestKit` for the eight consumers;
- the pure `Rollup`, `Gates`, `Profiles` and `Completeness` with their own suites;
- `AnkkaTestKit.start` over Postgres, with these fakes and harnesses:
  - an S3-compatible container for the bucket;
  - `FakePlayers` (the suspend route) under `localServices`;
  - `InMemoryBroker` for `casino.players`, `money.transactions` and `platform.audit`;
  - `TimerRuntime(200.millis)` and a settable clock;
- `GherkinSuite` binding the seven `features/kyc/*.feature` files (68 scenarios);
- `contract.port.Replay` over `tests/port/kyc/` through `WireTranslation`.

**Target Platform**: every brand project on the self-hosted installation (kind locally, GKE), as the
core module's `kyc` entry declares.

**Project Type**: the work is:

- one ankka service (`services/kyc/`);
- one contract change;
- the removal of `kyc-provider-key` from the core manifest, `contract/keys.md`, goldvibe's values and
  the modules tool's tests (R16);
- requests to `players` on its own spec (*Elsewhere*).

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-001 | the 61 rollup and handler cases and the route specs' KYC cases pass: `RollupSuite`, `KycRecordSuite` |
| SC-002 | the Playwright KYC suites pass with base URLs changed: the routes keep the original's shapes behind the BFF (`contracts/wire.md`) |
| SC-003 | a gate decision under 50 ms at p99: one policy read and one record command or read, no call out |
| SC-004 | after erasure, no version under `subjects/<player>/`; the records' non-personal fields unchanged |
| SC-005 | every view link an event and an audit record: `ViewIssued` is the only path to a signature |
| SC-006 | a policy change seen by the next gate decision: the endpoint reads `PolicyEntity` on each check |

**Constraints**:

- four endpoints, one prefix each, at most two path parameters, the ACL per route, the slug a literal
  (R14);
- no outbound call on a gate (SC-003); the only call out is `players`' suspend, from the window timer;
- operator reasons and notes and the age date are `Personal`; nothing personal in a log, a span, a fact,
  an audit record, a view row, a timer name or an object name (the object name carries the player id
  and category only);
- money is `Long` minor units with its currency (constitution II);
- a gate refusal is a 200 answer, since `players` reads any non-2xx as an outage (R5).

**Scale/Scope**:

| Item | Count |
|---|---|
| Player routes | 4 at `/v1/casino/kyc/<slug>` |
| Public routes | 1 at `/v1/casino/kyc-requirements/<slug>` |
| Operator routes | 14 at `/v1/casino/backoffice/<slug>/kyc` |
| Service routes | 2 gates, erasure and export at `/v1/casino/brands/<slug>/kyc` |
| Event sourced entities | 2 (`KycRecord`, `PolicyEntity`) |
| Views | 1 (`KycQueue`) |
| Timers | 1 kind (`kyc-window-<player>`) |
| Consumers | 8 (4 in, 4 on its own events) |
| Feature scenarios bound | 68 |
| Recordings replayed | 37 (scenarios 28 and 29, R2) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings named, parity tests left, divergences declared | Pass. R1 read the original whole and lists where its code, its oracle suites and the spec differ; R2 names the 37 recordings, one parity test each; *Divergences* below lists every change with what demonstrates it; `contracts/surface.md` disposes of the original's KYC surface. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; no balance kept | Pass. Thresholds and lifetime deposits are `Long` minor units per currency. Lifetime deposits are a count of the wallet's facts, deduplicated by its ordinal (R6), not a balance. |
| III. A brand is a project | keys carry no brand | Pass. The record's id is the player id; the policy's is the slug, one per project; the slug is in each prefix. |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. The gates and the suspend are HTTP; deposits, registration, profile and closure arrive as facts; the profile is kept as presence and an age date only (R11). |
| V. Personal data marked, erasure honoured | `Personal`; the erasure handler | Pass, with 008's R8 gap. Objects sit under `subjects/<player>/`; reasons, notes and the age date are `Personal`, blanked by `Erased`; the erasure route deletes every version itself until ankka 042's `erase(subject)` exists (R15). |
| VI. Scala by default | | Pass. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings (2026-10-30). |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (twice), 2026-10-25 and 2026-10-30. Two stale references to an answered marker were corrected with this plan. |
| Scope: ankka gaps are ankka specs | | Pass: 042 (`Personal`, `erase(subject)`) as for 008 and 010, and `objectStorageOrigins` in the vendor's manifest schema (R7), recorded under *For ankka*. |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-004 to FR-008, FR-010, FR-011 | `KycRecord` (event sourced, id = player); `Store` (trait over S3) signing at the endpoint | every rule reads the account's documents together; each upload, confirm, review and view one event | an entity per document (the derivation would read many, R3) |
| FR-009 | the installation's store | Google Cloud Storage encrypts every bucket | encrypting in the service (one more key to lose, R17) |
| FR-012 to FR-018 | pure `Rollup` (a port of `rollup.go`); `KycRecord` runs it after every input change | the original's rules kept as a function with its tests | the original's trigger with a freshness window (R4) |
| FR-016 | `ProfileConsumer` → `ProfileChanged`; pure `Completeness` | presence and an age date, no values | asking `players` (a call on every derivation) (R11) |
| FR-019, FR-020 | `ServiceEndpoint` gates; pure `Gates`; the deposit check a command on `KycRecord`, the play check a read | one policy read, one record call, flags raised once under concurrency | a view of totals (seconds behind) (R5) |
| FR-021, FR-038 | `DepositsConsumer` → `CountDeposit` | per currency, exact by the wallet's ordinal; the large-deposit flag on the same event | a set of transaction ids (unbounded) (R6) |
| FR-022 | `PolicyFlagger` over `PolicyChanged`, finding rows through `KycQueue.overThreshold` | one flag per player, from the policy's own event | a scan on a timer (late) (R9) |
| FR-023, FR-024 | `KycRecord` `ClearSof`, `DismissFlag`; flags closed on verification | — | (R8) |
| FR-025 | `RegistrationConsumer` → `SetDeadline`; `RecordTimers` sets `kyc-window-<player>`; `WindowTimer` → `players`' suspend | the deadline is a timer, replaced by name; the record keeps `SuspensionRequested` | a sweeper over a view (the original's, never built) (R10) |
| FR-026 to FR-029 | `PolicyEntity` (event sourced, id = slug); pure `Profiles`; `PublicEndpoint` | the audit names each field; consumers see every change | a key value entity (a consumer may skip values) (R9) |
| FR-030, FR-037 | `KycPublisher` → `casino.players`; `AuditPublisher` → `platform.audit` | as every service here | (R13) |
| FR-031 | `ClosureConsumer` → `Close`; `RecordTimers` cancels the timer | — | — |
| FR-032 to FR-034 | `Personal` fields; `ServiceEndpoint` erasure and export | deletes every version under the prefix itself until ankka 042 | waiting for 042 (R15) |
| FR-035, FR-036 | `KycQueue` view; the record's own reads | counts per filter in one place | — (R12) |
| FR-001 to FR-003 | `PlayerEndpoint`, `OperatorEndpoint` (role check), `ServiceEndpoint`, `PublicEndpoint` | ankka 0.13's route limits | (R14) |

Pure logic with no component: `Rollup`, `Completeness`, `Gates`, `Profiles`, `ObjectNames`,
`WireTranslation`, `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/009-kyc/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R18, "For ankka"
├── data-model.md        # Phase 1: the record's state, commands, events and refusals; the policy, view, timers, consumers
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── surface.md       # the original's KYC routes and functions, each moved, dropped or new
│   ├── routes.md        # every route by endpoint; bodies, answers and refusals; the calls out
│   ├── facts.md         # events → families; audit records; what is consumed
│   └── wire.md          # the parity translation
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala   # + player_kyc_pending.reason
contract/keys.md                                # kyc leaves "Not yet built"; kyc-provider-key goes
modules/core/module.json                        # kyc: KYC_PROVIDER_KEY goes
brands/goldvibe/values.json                     # goldvibe-kyc/API_KEY goes
tools/modules/src/test/…                        # fixtures and LocalApplySuite without kyc-provider-key
services/kyc/                                   # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── docker-compose.yml                          # Postgres on 5438, an S3-compatible store
├── README.md
├── src/main/scala/io/eitheror/casino/kyc/
│   ├── record/         # KycRecord, Model (state, events, commands), Rollup, Completeness, Gates
│   ├── policy/         # PolicyEntity, Policy, Profiles
│   ├── documents/      # Store (trait + S3), ObjectNames
│   ├── queue/          # KycQueue
│   ├── timers/         # RecordTimers, WindowTimer
│   ├── consumers/      # DepositsConsumer, ProfileConsumer, RegistrationConsumer, ClosureConsumer, PolicyFlagger
│   ├── facts/          # KycPublisher, AuditPublisher
│   ├── clients/        # Players (trait + HTTP)
│   ├── api/            # PlayerEndpoint, OperatorEndpoint, ServiceEndpoint, PublicEndpoint; Answers
│   ├── KycCodecs.scala
│   ├── Settings.scala
│   └── KycService.scala
└── src/test/scala/io/eitheror/casino/kyc/
    ├── record/, policy/, queue/, facts/        # kits and pure suites
    ├── it/                                     # Kit, FakePlayers, the store container; the integration suites
    ├── port/                                   # WireTranslation, ParitySuite
    └── features/                               # GherkinSuite bindings, one file per feature
tests/port/kyc/                                 # the recordings, after the run
.github/workflows/ci.yml                        # + the service's filter and job
justfile                                        # + kyc-test, kyc-it, kyc-run
```

**Structure Decision**: one service directory in the shape of `services/responsible-gambling/`, a
package per capability. The two outbound seams, `Players` and `Store`, are each a trait with an HTTP or
S3 implementation and a fake. The service runs locally on 9012, where `players`' local run already
looks for `kyc`.

## Phase 0: research

Done: [research.md](research.md), R1–R18. Six questions were settled with the user on 2026-10-30
(spec Clarifications):

- the large-deposit flag;
- one KYC currency;
- the Curaçao default;
- an incomplete profile is pending;
- the compliance roles;
- the per-payment proposal dropped.

Verified while planning (2026-10-30):

- the core module declares `kyc` (exposed, runtime 0.13.0, `provisionObjectStorage`) with a
  `KYC_PROVIDER_KEY` nothing will read (R16);
- every KYC family exists in the contract (`CasinoPlayers.scala:87-113`); `player_kyc_pending` lacks
  `reason`;
- `players` asks `POST …/kyc/deposit-check {playerId, amount}` with no currency and reads a non-2xx
  as an outage (`services/players/.../clients/Neighbours.scala:10-33`); `game-provider` asks
  `GET …/kyc/{playerId}/play-check` and reads 2xx or 403;
- `players`' suspend reads `reason` only and ignores the deadline (`OperatorStatusRoutes.scala:62-67`);
  `ErasureWorkflow.targets` lacks `kyc` (`erasure/ErasureWorkflow.scala:187-191`);
- ankka's object storage page: signed URLs against `ANKKA_S3_PUBLIC_ENDPOINT`; Garage keeps one
  version; Google Cloud Storage keeps versions with a soft-delete window and encrypts.

## Phase 1: design

- [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

### Constitution re-check after design

Unchanged:

- one brand service with its own database and bucket;
- it produces to `casino.players` and `platform.audit` from consumers;
- it reads `money.transactions` and `casino.players` through consumers;
- it calls only `players`, from a timer, off every gating path;
- the contract change is additive.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| The deposit check runs before the deposit, asked by `players`; a refusal is a 200 answer | FR-019, R5 | scenario 29's deposit recordings (`notify/deposit`'s 403s) replay against the check, translated; `players`' own suite for "no deposit started" |
| Lifetime deposits are real, per currency, deduplicated | FR-021, R6 | `KycRecordSuite`; `gates.feature`; scenario 29's threshold recordings, marked where the one-deposit comparison differed |
| A deposit check in another currency is refused while a threshold is set | FR-019 | `GatesSuite`; `gates.feature`; no recording |
| A large deposit flags, suspends no one | FR-038 | `KycRecordSuite`; `gates.feature`; no recording (never built in the original) |
| Only the compliance roles reach the per-player routes | FR-002 | `OperatorSuite`; no recording (the recordings use an admin) |
| A derivation that changes nothing writes nothing | FR-014 | `RollupSuite` (`StaleTimestampIsNoOp` restated) |
| Confirm only an upload this service offered and found in the store; size and type checked | FR-007, R7 | `DocumentsSuite`; scenario 28's confirm recordings, with the replay's PUT |
| The reviewer is recorded; an unknown document is 404; the approval's answer carries the status after | FR-010 | `KycRecordSuite`; scenario 28's review recordings, marked |
| A policy save reads `CUSTOM` and is validated; unset fields are `null` | FR-027 | `PolicySuite`; scenario 29's policy recordings, marked |
| Switching to automatic recomputes at once | FR-015 | `KycRecordSuite`; scenario 28's mode recording, marked if it differs |
| `PUT status pending` is `player_kyc_pending` (reason `agent`); required categories publish `player_kyc_pending` | FR-014, FR-017 | `PublishersSuite`; the recordings' facts are not replayed |
| One player view route; every issue an event and an audit record | FR-037 | `DocumentsSuite`; `WireTranslation` maps both original routes |
| Any change of a required profile field counts, not only the player's own | R1, R11 | `KycRecordSuite`; no recording |
| The window suspends (it never did); a migrated past deadline only flags | FR-025 | `WindowSuite`; no recording |
| No `kyc_review` proposal per captured payment | Clarifications 2026-10-30 | nothing published: `PublishersSuite` |
| Objects under `subjects/<player>/kyc/` | FR-032 | `DocumentsSuite`; `WireTranslation` (the key is opaque to the replay) |
| Routes move under four prefixes | R14 | `WireTranslation` |
| Scenario 28's incomplete-profile test waits for `pending`, not `unverified` | FR-012 | the patch corrected before the run |

## What the next command needs to know

**Build order:**

1. the contract addition, `just schemas`, `sbt contract/publishLocal` with a unique `CASINO_VERSION`;
   the removal of `kyc-provider-key` (R16) with the modules tool's tests; the CI filter and job;
2. the service skeleton: `ankka init`, `build.sbt`, `service.json`, `docker-compose.yml`, `Settings`,
   `KycCodecs`, the `just` recipes; `contract/keys.md` loses the `kyc` row ("Not yet built"), which
   `ModuleManifestSuite` enforces once `services/kyc/build.sbt` exists;
3. the pure packages with their suites:
   - `Rollup` with the 61 cases, written first and failing first;
   - `Completeness`, `Gates`, `Profiles` with the seven `substrate_test.go` cases;
   - `ObjectNames`, `WireTranslation`;
4. `PolicyEntity`, then `KycRecord` with their kits, every row of data-model.md's command table;
5. `Store` (trait, S3 implementation, fake) and the integration kit's store container;
6. the consumers with kits; `KycQueue`; the publishers (`PublishersSuite`: every event to its family,
   never a `Personal` value);
7. `RecordTimers`, `WindowTimer` and the `Players` client with `FakePlayers`;
8. the four endpoints, the gates first (fail-first on `gates.feature`), then documents, review, policy,
   reads, erasure and export;
9. the recording run (needs the user, R2); `ParitySuite` over every recording, each marked divergence
   named;
10. the integration suites:
    - `GatesSuite` (SC-003, SC-006) and `DocumentsSuite` (SC-005);
    - `WindowSuite`, `ErasureSuite` (SC-004), `OperatorSuite`, `LeakSuite`;
    - `GherkinSuite` over the seven features;
11. the core module's entry checked; a kind deploy beside `players`, `wallet` and `game-provider`; the
    bucket seen in `ankka services get`.

**Constraints on that order:**

- Parity tests are written before the components they test and fail first (ankka-port §7); until the run
  the suite is `.ignore`d.
- The recording run is against a local original only.
- Step 1 touches `contract/` and `modules/core/module.json`, which other branches also touch: keep it
  additive and rebase before merging.

**Elsewhere:**

- **Spec 008 (`players`)**:
  - the deposit check carries `currency` (until then every check is answered as another currency,
    R5);
  - the suspend honours the deadline, idempotent under player and deadline (R10);
  - `ErasureWorkflow.targets` and the export gain `kyc` (R15);
  - `ActivityView` reads `player_kyc_pending` if the activity list should show it.
- **`tests/original/e2e-rg-kyc.patch`**: scenario 28's `IncompleteProfileStaysUnverified` waits for
  `pending` (R1).
- **The vendor's manifest schema** carries `objectStorageOrigins` before a page uploads straight to the
  bucket (R7); until then the player site proxies.
- **Spec 007 (payouts)** reads the status families for its identity gate; **spec 014** sends the review
  and status messages from the families; **spec 015** counts from them; **spec 019** writes the opening
  event and copies the objects.
