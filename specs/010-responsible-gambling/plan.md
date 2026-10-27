# Implementation Plan: responsible-gambling

**Branch**: `010-responsible-gambling` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/010-responsible-gambling/spec.md`

## Summary

`responsible-gambling` is the brand service that holds a player's limits, breaks, exclusions and reality
check, and answers the four questions the rest of the brand asks before money moves or play starts:
`may-deposit` (the wallet, before a credit), `may-bet` (game-provider, before a stake), `may-play`
(game-provider, at launch) and `may-log-in` (players, at sign-in and for the realm's authenticator). Every
answer comes from the service's own profile and the figures the caller carries; nothing is called on the
way, and every caller fails closed.

One event sourced entity per player (the profile, its cases and its history); key value entities for the
usage buckets, the session day and the brand's settings; four timers (a pending limit, a break's end, a
return, the sync to `players`); three consumers in (`money.transactions`, `casino.sessions`) and two out
(`casino.players`, `platform.audit`); three endpoints. Decisions are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.13.0 (constitution VI; the versions every service here pins).

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract`: `Routes.player`, `Routes.operator`, `Routes.serviceOf`, `Personal`,
  `CasinoPlayers` (the three RG families), `MoneyTransactions`, the `casino.sessions` families,
  `PlatformAudit`, `port.Recording`. **Contract changes** (additive, R9): `rg_limit_applied.currency`,
  `rg_case_opened.permanent`.

**Storage**: the service's own Postgres from ankka (journal, durable state, the timers). No secrets: every
caller is in the project, admitted by its certificate.

**Testing**:

- munit; `EventSourcedTestKit` for `ProfileEntity`, `KeyValueEntityTestKit` for the three key value
  entities, `ConsumerTestKit` for the five consumers;
- the pure `Limits` and `Checks` with their own suites (every row of data-model.md's tables);
- `AnkkaTestKit.start` over Postgres with `FakePlayers` (the exclusion route) under `localServices`,
  `InMemoryBroker` for the four topics, `TimerRuntime(200.millis)` and a settable clock;
- `GherkinSuite` binding the seven `features/responsible-gambling/*.feature` files (59 scenarios);
- `contract.port.Replay` over `tests/port/responsible-gambling/` through `WireTranslation`.

**Target Platform**: every brand project on the self-hosted installation (kind locally, GKE), as the
core module's `responsible-gambling` entry declares (`modules/core/module.json`).

**Project Type**: one ankka service (`services/responsible-gambling/`), one contract change, a manifest
check, two small changes to `players` on 008's branch (R12, R15).

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-001 | no deposit over the allowance credited: the wallet's sums are exact in the request, the check is one read |
| SC-002 | every attempt by an excluded or cooling-off player refused, at all four checks |
| SC-003 | a pending change applied, a break ended, within one minute: one timer, one command |
| SC-004 | each check under 100 ms at p99: one entity read (and one key value read for `may-play`), no call out |
| SC-005 | a reinstated player logs in at the next request: `may-log-in` reads the entity the timer just changed |
| SC-006 | every operator action in the history at once (the entity) and in the audit within 5 s (a consumer) |
| SC-007 | shown deposit usage equal to the wallet's sums to the minor unit (exact deposit entries, R7) |

**Constraints**:

- three endpoints, one prefix each, at most two path parameters, ACL per route, the slug a literal (R14);
- no outbound call on a check (FR-011); the only outbound calls are to `players`' exclusion route, from
  the sync timer, and none on a request path that gates money or play;
- notes and free-text reasons `Personal`; nothing personal in a log, a span, an audit record or a timer
  name (FR-032);
- every limit value a `Long` in minor units, `SESSION_MINUTES` in whole minutes (constitution II).

**Scale/Scope**:

| Item | Count |
|---|---|
| Player routes | about 10 at `/v1/casino/rg/<slug>` |
| Operator routes | about 10 at `/v1/casino/backoffice/<slug>/rg` |
| Service routes | 4 checks, 1 GAMSTOP report, erasure and export, at `/v1/casino/brands/<slug>/rg` |
| Event sourced entities | 1 (`ProfileEntity`) |
| Key value entities | 3 (`UsageEntity`, `SessionDayEntity`, `BrandSettingsEntity`) |
| Timers | 4 named kinds (`rg-pending-*`, `rg-break-*`, `rg-return-*`, `rg-sync-*`) |
| Consumers | 5 (2 in, 2 out, 1 audit) |
| Feature scenarios bound | 59 |
| Recordings replayed | 37 (scenarios 26 and 27, R1) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings named, parity tests left, divergences declared | Pass. R1: the 37 scenario 26–27 tests, one parity test each; *Divergences* below lists every change with what demonstrates it; `contracts/surface.md` disposes of the original's RG surface. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; no balance kept | Pass. Limits and figures are `Long` minor units. The service keeps no balance: the gate's figures come from the wallet in the request; `UsageEntity` is a display fold of the wallet's facts. |
| III. A brand is a project | keys carry no brand | Pass. Entity ids are the player id and `player/currency`; the slug is in each prefix. |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. The checks are HTTP; the exclusion is told to `players` over HTTP; usage and session minutes are views over `money.transactions` and `casino.sessions`; the facts come from consumers. |
| V. Personal data marked, erasure honoured | `Personal` notes; the erasure handler | Pass. Notes and reasons are `Personal`; the erasure route blanks them. GAMSTOP is taken by player id, so no address is held (R12). The journal gap until ankka 042 is 008's R8, the same here. |
| VI. Scala by default | | Pass. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1,108 scenarios (2026-10-27). |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (twice) and 2026-10-25. Two stale *Out of scope* lines contradicting the 2026-10-25 answers were corrected with this plan. |
| Scope: ankka gaps are ankka specs | | Pass: 042 only, as for 008. |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-021, FR-026, FR-027 | `ProfileEntity` (event sourced, id = player), cases and history on it | every rule reads limits, break, exclusion and cases together; each transition one event | a case entity per case (two writes per break; the original's mismatch) (R2) |
| FR-002 to FR-006 | pure `Limits`; `ProfileEntity.SetLimit`; `PendingTimer` (`rg-pending-<player>-<type>`) | the original's rule kept as a function; the timer is the change, replaced by name | a recurring scan (late, and the original's scan was never registered) (R3) |
| FR-007 to FR-011 | `ServiceEndpoint` checks; pure `Checks` over the profile and the request's figures | one read, no call out, the original's order | reading usage from a view in the gate (seconds behind; spec Clarifications) (R5) |
| FR-010 | `may-bet` with optional loss sums; `unavailable` when a loss limit is set and none carried | fail closed until 002 and 003 carry them | the view's losses in the gate (not exact) (R6) |
| FR-012 | `UsageConsumer` → `UsageEntity` (`player/currency`) | display only, bounded state, deposits exact | a view row per transaction (unbounded) (R7) |
| FR-008 | `SessionMinutesConsumer` → `SessionDayEntity` (`player/day`) | the UTC day's minutes in one read | asking `players` (a call on the gate) (R8) |
| FR-014 to FR-017 | `ProfileEntity` break commands; `BreakTimer` (`rg-break-<player>`) | the end is a timer; lifting cancels it | — (R4) |
| FR-018 to FR-020 | `ProfileEntity` exclusion and return; `ReturnTimer`; `SyncTimer` → `players`' exclusion route | the profile decides at once; `players`' status converges by retry | a workflow per exclusion (R4) |
| FR-022 | `ServiceEndpoint./gamstop-refusal` by player id | a third-party case, no status | taking the address (R12) |
| FR-023, FR-024 | `ProfileEntity` reality check; the session from `SessionDayEntity` | — | (R11) |
| FR-025 | `OperatorEndpoint` state read: the profile and `UsageEntity` | — | — |
| FR-028 | `AuditPublisher` over the profile's events | consumers make facts here | (R9) |
| FR-029 | `BrandSettingsEntity` | key value, one per brand | env (an operator cannot change it) (R13) |
| FR-030, FR-031 | `RgPublisher` → `casino.players` | as every service here | (R9) |
| FR-032 | `Personal` notes and reasons; the erasure route | — | (R15) |

Pure logic with no component: `Limits`, `Checks`, `Windows` (bucket sums), `WireTranslation`, `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/010-responsible-gambling/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R16, "For ankka"
├── data-model.md        # Phase 1: the profile's state, events, commands and refusals; the other entities, timers, consumers
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── surface.md       # the original's RG routes and functions, each kept, moved, dropped or new
│   ├── routes.md        # every route by endpoint; bodies, answers and refusals
│   ├── facts.md         # events → families; the contract additions
│   └── wire.md          # the parity translation
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala   # + currency, permanent
services/responsible-gambling/                 # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── README.md
├── src/main/scala/io/eitheror/casino/rg/
│   ├── profile/        # ProfileEntity, Model (state, events, commands), Limits, Checks
│   ├── timers/         # PendingTimer, BreakTimer, ReturnTimer, SyncTimer
│   ├── usage/          # UsageEntity, UsageConsumer, Windows, SessionDayEntity, SessionMinutesConsumer
│   ├── settings/       # BrandSettingsEntity
│   ├── facts/          # RgPublisher, AuditPublisher
│   ├── clients/        # Players (trait + HTTP)
│   ├── api/            # PlayerEndpoint, OperatorEndpoint, ServiceEndpoint; Answers
│   ├── RgCodecs.scala
│   ├── Settings.scala
│   └── RgService.scala
└── src/test/scala/io/eitheror/casino/rg/
    ├── profile/, usage/, settings/, facts/     # kits and pure suites
    ├── it/                                     # Kit, FakePlayers; the integration suites
    ├── port/                                   # WireTranslation, ParitySuite
    └── features/                               # GherkinSuite bindings, one file per feature
tests/port/responsible-gambling/                # the recordings, after the run
.github/workflows/ci.yml                        # + the service's filter and job
justfile                                        # + rg-test, rg-it, rg-run
```

**Structure Decision**: one service directory in the shape of `services/wallet/` and `services/players/`,
a package per capability; the one outbound seam (`Players`) a trait with an HTTP implementation and a fake.

## Phase 0: research

Done: [research.md](research.md), R1–R16. One open question for the user (R6: ship failing closed for
loss-limited players, or carry the loss sums in 002 and 003 first). Verified while planning (2026-10-27):

- the core module declares `responsible-gambling` (exposed, runtime 0.13.0, both issuers);
- `game_session_started` carries player, session and start; `game_session_ended` player, session and end
  (`contract/.../facts/Others.scala`), which is what `SessionDayEntity` needs.

## Phase 1: design

- [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

### Constitution re-check after design

Unchanged. One brand service with its own database; produces to `casino.players` and `platform.audit` from
consumers; reads `money.transactions` and `casino.sessions` through consumers; calls only `players`, off
every gating path. The contract change is additive.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| A deposit is refused before credit, by the wallet asking `may-deposit` | FR-007, FR-013 | the scenario 27 deposit recordings (`notify/deposit`'s answers) replay against `may-deposit`; "no balance changes" by the wallet's suite |
| Every check fails closed | FR-011 | the callers' suites (wallet, game-provider, players) with this service silent |
| A pending change applies by itself | FR-005 | `TimersSuite`; the scenario 26 raise and removal recordings, marked |
| A break ends by itself; nothing else changes | FR-017 | `TimersSuite`; `breaks.feature` |
| A reinstatement clears break and exclusion together; refused before the period; 24 h after the request | FR-020 | `ExclusionSuite`; the scenario 27 reinstatement recordings, marked |
| Self-exclusion ends open game sessions (by `players`) | FR-018 | `ExclusionSuite` with `FakePlayers`; the recording, marked |
| History lists limit changes and cases with actors | FR-027 | the scenario 26 history recording, marked |
| Case statuses open and closed with a reason | FR-026 | every case read in the recordings, marked |
| A break has bounds; the player cannot shorten it | FR-014, FR-015 | the scenario 27 shortening recording, marked |
| GAMSTOP is a third-party case, by player id | FR-022, R12 | `ChecksSuite`; no recording (the original keeps a Keycloak note) |
| Operator changes are audited and produce facts; an operator's limit cancels the pending change | FR-004, FR-028 | `OperatorSuite`; the scenario 26 operator recording, marked |
| Loss limits refuse a bet `spending_limit` (with the sums carried) | FR-010 | `ChecksSuite`; no recording (the original enforces nothing) |
| Weekly and monthly loss usage is exact to the hour, daily to the minute | R7 | `UsageSuite`; the scenario 26 loss read, marked if it differs |
| Operator routes move under `/v1/casino/backoffice/<slug>/rg` | R14 | `WireTranslation` |

## What the next command needs to know

**Build order:**

1. the contract additions, `just schemas`, `sbt contract/publishLocal`; the CI filter and job;
2. the service skeleton: `ankka init`, `build.sbt`, `Settings`, `RgCodecs`, the `just` recipes;
3. the pure packages with their suites: `Limits`, `Checks`, `Windows`, `WireTranslation`;
4. `ProfileEntity` with its kit, every row of data-model.md's command table;
5. the key value entities and the consumers with kits; the publishers (`PublishersSuite`: every event to
   its family, never a `Personal` value in a log);
6. the timers and the `Players` client with `FakePlayers`; the integration kit;
7. the three endpoints, the checks first (fail-first on the deposit and play features);
8. the recording run (needs the user, R1); `ParitySuite` over every recording, each marked divergence named;
9. `ChecksSuite` (SC-004's percentile), `TimersSuite` (SC-003), `ExclusionSuite`, `OperatorSuite`
   (SC-006), `UsageSuite` (SC-007), `LeakSuite`, `GherkinSuite` over the seven features;
10. the core module's entry checked; a kind deploy beside `wallet` and `players`; 008's sign-in on kind
    re-run (it waited on this service).

**Constraints on that order:**

- Parity tests are written before the components they test and fail first (ankka-port §7); until the run
  the suite is `.ignore`d.
- The recording run is against a local original only.
- Step 1 touches `contract/`, which 008, 012 and 022 also touch: keep it additive and rebase before merging.

**Elsewhere:**

- **Spec 008 (`players`, on its branch)**: the GAMSTOP report passes the player id (R12);
  `ErasureWorkflow.targets` gains this service (R15).
- **Spec 002 (wallet)** keeps loss sums as it keeps `recentDeposits`, and **spec 003 (game-provider)**'s
  bet workflow carries them to `may-bet` and reads `spending_limit` (R6). Until then a loss-limited player
  cannot bet (fail closed).
- **Spec 014** derives its events from the three families; **015** counts from them.
