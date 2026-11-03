# Implementation Plan: engagement

**Branch**: `014-engagement` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/014-engagement/spec.md`

## Summary

`engagement` is the brand service that owns every message a brand sends a player and the live channel
the in-app signal arrives on. It:

1. consumes the brand's facts on six topics and keeps a `Profile` per player from them, never reading
   another service's store;
2. turns each fact into the original's Customer.io events, exactly once, through a durable `Send`
   per event whose state is the send record: identify, track, transactional, with the original's
   attempts and a dead letter the operator retries or discards;
3. enforces marketing suppression outbound, for sends and events alike, at every attempt;
4. serves the send route `players` already calls, the Customer.io webhook, the player stream, the
   agent stream with its ticket, the configuration with masked secrets, the seed, the sends, the
   dead letter, presence and status;
5. holds a player's connections across the cluster in one `Channel` and produces `push_delivered`
   and `presence_changed` on `casino.push`;
6. honours erasure: deletes the person at the CRM and redacts what it holds.

The service has:

- two key value entities, `Profile` (per player) and `Configuration` (per brand), and `AgentTicket`
  (per ticket);
- two event sourced entities, `Channel` (per player) and `AgentChannel` (per brand);
- one workflow, `Send` (per send key);
- two views, `SendsView` and `ProfilesView`;
- one recurring timer per brand (`deposit-day-<slug>`) and one per online player (`sweep-<player>`);
- six consumers in and two out;
- five endpoints.

It also amends three neighbours (research R6): `responsible-gambling` with one fact, `players` with
consent facts at registration and a terminal `refused`, and the `engagement` module manifest, which
loses its placeholder secret.

The event table, the attribute rendering, the deposit day, the signature check and the frames are
pure ports of the original, held to its own suites. Decisions are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.13.0 (constitution VI; spec clarification 2026-10-08).

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract`:
  - `Routes.player`, `Routes.operator`, `Routes.operatorHolding`, `Routes.service`,
    `Routes.playersOnly`, `Routes.public`, `Routes.playerId`;
  - `Personal`, `PlayerSubject`;
  - `CasinoPlayers`, `CasinoSessions`, `MoneyTransactions`, `PaymentsPayments`, `CasinoWithdrawals`,
    `CasinoRewards`, `CasinoPush`, `PlatformAudit`; `AuditConsumer`;
  - `port.Recording`, `port.Replay`.
- **Contract change** (additive, research R6): `casino.players` gains `rg_limit_loosening_requested`.

**Storage**: the service's own Postgres from ankka (journal, durable state, views, workflow and timer
state) and its secret store for the three Customer.io secrets (038 as shipped in 0.13). No bucket.

**Testing**:

- munit;
- `KeyValueEntityTestKit` for `Profile`, `Configuration`, `AgentTicket`; `EventSourcedTestKit` for
  `Channel`, `AgentChannel`; `ConsumerTestKit` for the eight consumers;
- the pure `Events`, `Attributes`, `DepositDay`, `Classes`, `Suppression`, `Signature`, `Masking`,
  `Seed`, `Frames`, with the original's suites ported case by case (research R13);
- `AnkkaTestKit.start` over Postgres with (the workflow runs here; 0.13 has no workflow test kit):
  - `FakeCrm`, an HTTP server playing both Customer.io hosts, recording every call, scriptable to
    answer 429, 5xx or hang for the next *n* calls;
  - `FakePlayers` (the message route) and `FakeRewards` (target, award) under `localServices`;
  - `InMemoryBroker` for the six topics in, `casino.push` and `platform.audit` out;
  - `TimerRuntime(200.millis)` with a `MovableClock`; `testKit.secrets`;
  - an SSE reader over `java.net.http` for the stream routes;
- `GherkinSuite` binding `features/engagement/*.feature`;
- `contract.port.Replay` over `tests/port/engagement/` and the webhook steps of `tests/port/rewards/`
  through `WireTranslation`.

**Target Platform**: every brand project whose entitlement names the `engagement` module, on the
self-hosted installation (kind locally, GKE), as `modules/engagement/module.json` declares.

**Project Type**: the work is:

- one ankka service (`services/engagement/`);
- the contract addition and the access rows;
- one fact in `services/responsible-gambling/` (R6 P1);
- two amendments in `services/players/` (R6 P2, P3);
- the `engagement` manifest without `push-provider-key`, `contract/keys.md` and the modules tool's
  suites (R6 P4, P5);
- `tests/stubs/crm-stub/` and `tests/original/engagement-driver/` (R2).

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-001 | `ParitySuite`: every recording's CRM calls replay with the original's event names, attribute names and types and data keys; each marked divergence named |
| SC-002 | `ReplaySuite`: a year of a player's facts, each delivered twice, into a service restarted with an empty `Profile` journal: every `Send` found or `skipped_stale`, no CRM call |
| SC-003 | `SuppressionSuite`: over FR-011's whole trigger list, a suppressed player's marketing class refused and untracked, service class sent, at the first attempt and at a retry |
| SC-004 | `ChannelSuite`: a signal reaches every open connection within 2 s on one instance and across `restartService()`; three instances on kind (quickstart §6) |
| SC-005 | `LatencySuite`: a transactional accepted by `FakeCrm` within 60 s of its fact with the stub healthy, and after one 503 |
| SC-006 | `DumpSuite`: `pg_dump` of the service's database holds no readable address, name, date of birth or message text |
| SC-007 | `ErasureSuite`: after erasure, `FakeCrm` has a `delete` for the person and a replayed fact makes no call |
| SC-008 | `RecordsSuite`: every refusal, parking and discard is a `Send` state and an audit record; `FakeCrm`'s accepted count equals the `accepted` rows over a day of facts |
| SC-009 | the sixteen Playwright suites plus `32-player-email-management`, base URLs only (quickstart §6) |

**Constraints**:

- five endpoints, one prefix each, at most two path parameters, the ACL per route (research R7);
- nothing read from another service's store; the profile is built from facts alone (FR-005);
- every CRM call inside a `Send` step; no call from a consumer or an endpoint (the seed excepted, which
  calls the CRM from its route and records nothing, as the original);
- suppression and the account state read from `Profile` at every attempt (FR-011);
- the connection set lives in `Channel`, never in instance memory alone (R10);
- every personal field `Personal` under `player/<id>`; nothing personal in a fact, a log, a span, a
  timer name or an audit record's values (FR-015, FR-029);
- money in minor units on the way in; major units with two decimals, `HALF_UP`, in event data only
  (FR-006).

**Scale/Scope**:

| Item | Count |
|---|---|
| Service routes | 2 at `/v1/casino/brands/<slug>/engagement` (sends, erasure) |
| Player routes | 1 at `/v1/stream` (the stream) |
| Public routes | 1 at `/v1/casino/customerio` (`/webhook/{brand}`) |
| Operator routes | 8 at `/v1/casino/backoffice/<slug>` (agent ticket, agent stream, a player's sends, parked sends, retry, discard, presence, status); 3 at `/v1/casino/crm-config` (get, put, seed) |
| Key value entities | 3 (`Profile`, `Configuration`, `AgentTicket`) |
| Event sourced entities | 2 (`Channel`, `AgentChannel`) |
| Workflows | 1 (`Send`) |
| Views | 2 (`SendsView`, `ProfilesView`) |
| Timers | `deposit-day-<slug>` (daily, 03:05 UTC); `sweep-<player>` (30 s while online) |
| Consumers | 8 (6 in: players, sessions, money, payments, withdrawals, rewards; 2 out: push, audit) |
| Feature scenarios bound | every scenario of `features/engagement/`, plus the steps added to `features/responsible-gambling/limits.feature` and `features/players/registration.feature` |
| Recordings replayed | the spec's *Recordings* (research R2) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings named, parity tests left, divergences declared | Pass. R1 read both dispatch paths, the webhook, the gateway and the agent stream whole; seven planning questions were settled with the user (spec, *Session 2026-11-03 (planning)*); the spec was corrected where it contradicted the original (FR-002, FR-006, FR-008 to FR-010, FR-013, FR-014, FR-017 to FR-024, FR-026, the edge cases, *Recordings*, *Assumptions*), and *Divergences* below lists every change with what demonstrates it. `contracts/surface.md` disposes of every route, function and topic. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; no balance kept | Pass. No money moves here. The balances on the profile are the wallet's own figures after each movement, carried by its facts (002 FR-031), rendered to major units only inside an event's data. |
| III. A brand is a project | keys carry no brand | Pass. Ids are the player id, the send key, the slug, a connection id. `/v1/stream` and the webhook refuse another brand's token or path. Nothing leaves the project but to Customer.io, the brand's named processor (FR-015). |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. `players` and `rewards` are called over HTTP by identity, only when the webhook or erasure asks. Everything else arrives on the six topics; `casino.push` and `platform.audit` leave through consumers. |
| V. Personal data marked, erasure honoured | `Personal`; the erasure handler | Pass. R15: the fields named, the route served to `players`, the CRM's person deleted, the records redacted, a later fact silent. |
| VI. Scala by default | | Pass (clarification 2026-10-08). |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1,184 scenarios (2026-11-03, after clarify). |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (three sessions), 2026-10-12, 2026-11-03; seven planning questions settled 2026-11-03. |
| Scope: ankka gaps are ankka specs | | Pass: nothing new (research, *For ankka*). |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-005, FR-008, FR-012 | `Profile` (key value, id = player): the attributes, consents, cases, status, deposit day, balances, recent payments | the latest value of each attribute is what an identify sends; one writer per player | an event sourced profile (the facts are already the journal; nothing reads its history) |
| FR-007, FR-002, FR-011, FR-013, FR-014, FR-025 | `Send` (workflow, id = the send key): claim, identify, track, transactional, park, retry, discard; its state is the send record | the atomic claim, durable attempts as pauses, the lifetime record; 007's and 011's shape | a `SendRecord` entity plus a stateless step (two writers for one record, research R3) |
| FR-025, FR-027 | `SendsView` (`stateOf(Send)`): a player's sends, the parked sends, the hour's counts | one source, one row per send | — |
| FR-009 | `ProfilesView` (`stateOf(Profile)`) and the timed action `DepositDayTimer`, recurring `deposit-day-<slug>` | the reset selects from its own view; a daily cadence set at every start | the original's Keycloak enumeration (another store) |
| FR-020 to FR-023 | `Channel` (event sourced, id = player): connections with leases, the signal ring, acks; `Opened`, `Closed`, `Signalled`, `Delivered`, `Expired` events | one writer per player across the cluster; its events are the fact source (research R10) | connections in instance memory with per-instance consumer groups (ankka runs a consumer once) |
| FR-024 | `AgentChannel` (event sourced, id = slug) and `AgentTicket` (key value, id = ticket) | the same channel for agents; a ticket is single-use across instances only as an entity | an in-memory ticket store (fails across instances) |
| FR-004, FR-026 | `Configuration` (key value, id = slug) with the three secrets in `clients.secrets` | the non-secret part is state; the secrets reach no journal | the manifest's secret (settled 2026-11-03, R12) |
| FR-005, FR-006, FR-010, FR-012 | `PlayersConsumer`, `SessionsConsumer`, `MoneyConsumer`, `PaymentsConsumer`, `WithdrawalsConsumer`, `RewardsConsumer`: update `Profile`, run `Events`, start a `Send` per event, signal `Channel` | at least once in, exactly once out through the claim | — |
| FR-022, FR-023, FR-029 | `PushPublisher` (`Channel` events → `casino.push`), `AuditPublisher` (`Send` states and `Configuration` changes → `platform.audit`) | as every service here | — |
| FR-028 | `SendEndpoint`, `StreamEndpoint`, `WebhookEndpoint`, `BackofficeEndpoint`, `ConfigEndpoint` | the original's paths where a frontend or Customer.io reads them (research R7) | — |

Pure logic with no component: `Events`, `Attributes`, `DepositDay`, `Classes`, `Suppression`,
`Signature`, `Masking`, `Seed`, `Frames`, `Refusals`, `WireTranslation`, `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/014-engagement/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R16, "For ankka", "Elsewhere"
├── data-model.md        # Phase 1: each entity's state, commands and events; the workflow; views, timers, consumers
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── surface.md       # the original's routes, functions and topics, each moved, dropped or new
│   ├── routes.md        # every route by endpoint; bodies, answers and refusals; the calls out; the frames
│   ├── facts.md         # facts → events (the table as built); facts produced; audit records; the contract addition
│   └── wire.md          # the parity translation
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala   # rg_limit_loosening_requested
contract/access/routes.md                          # the send, erasure and message rows
contract/keys.md                                   # push-provider-key gone; engagement leaves "Not yet built"
modules/engagement/module.json                     # PUSH_PROVIDER_KEY gone
tools/modules/src/test/…/RenderSuite.scala, ValuesSuite.scala   # follow
services/responsible-gambling/                     # R6 P1: the request fact, with its suite
services/players/                                  # R6 P2, P3: consent facts at registration; a terminal `refused`
services/engagement/                               # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── docker-compose.yml                             # Postgres on 5442
├── README.md
├── src/main/scala/io/eitheror/casino/engagement/
│   ├── profile/        # Profile, Model, Attributes, Suppression, DepositDay
│   ├── events/         # Events (fact → event + data), Classes, Amounts
│   ├── send/           # Send (workflow), Model, Attempts
│   ├── channel/        # Channel, AgentChannel, AgentTicket, Frames, Leases
│   ├── config/         # Configuration, Masking, Seed, Secrets (the 30 s cache)
│   ├── reads/          # SendsView, ProfilesView
│   ├── timers/         # DepositDayTimer, SweepTimer
│   ├── consumers/      # the six in
│   ├── facts/          # PushPublisher, AuditPublisher
│   ├── clients/        # Crm (trait, CustomerIo, fake), Players, Rewards
│   ├── api/            # SendEndpoint, StreamEndpoint, WebhookEndpoint, BackofficeEndpoint, ConfigEndpoint; Signature; Answers
│   ├── EngagementCodecs.scala
│   ├── Settings.scala
│   └── EngagementService.scala
└── src/test/scala/io/eitheror/casino/engagement/
    ├── profile/, events/, send/, channel/, config/, api/   # kits and pure suites
    ├── it/                                        # Kit, FakeCrm, FakePlayers, FakeRewards, SseReader; the integration suites
    ├── port/                                      # WireTranslation, ParitySuite
    └── features/                                  # GherkinSuite bindings, one file per feature
tests/stubs/crm-stub/                              # Customer.io's two hosts, recording (R2)
tests/original/engagement-driver/                  # what the suite does not reach (R2)
tests/port/engagement/                             # the recordings, after the run
.github/workflows/ci.yml                           # + the service's filter and job; crm-stub in the stubs job
Justfile                                           # + engagement-test, engagement-it, engagement-run
```

**Structure Decision**: one service directory in the shape of `services/rewards/`, a package per
capability. The three outbound seams, `Crm`, `Players` and `Rewards`, are each a trait with an HTTP
implementation and a fake. The service runs locally on 9013, with Postgres on 5442.

## Phase 0: research

Done: [research.md](research.md), R1–R16. Seven questions were settled with the user on 2026-11-03
(spec, *Session 2026-11-03 (planning)*):

- the spec corrected to the original wherever it contradicted it; the Scala path's form where the
  original's two paths disagree (R1);
- `rg_limit_loosened` from a new 010 fact at the request (R6);
- the original's close reasons, jitters and silent overflow (R10);
- cluster-wide presence and one delivered fact, named as divergences (R10, R11);
- consent facts at registration and a terminal `refused` in `players` (R6);
- `push-provider-key` removed (R12);
- `player_name` and `language` from what facts carry (R9).

Verified while planning (2026-11-03): research R6's list.

## Phase 1: design

- [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).
- The spec is amended with this plan (spec, *Session 2026-11-03 (planning)*):
  - FR-002, FR-006, FR-008 to FR-010, FR-013, FR-014, FR-017 to FR-024 and FR-026 follow the original;
  - *Changes from the original*, *Recordings*, *Assumptions* and *Dependencies* are corrected;
  - the features: `push.feature` and `campaigns.feature` follow the original; `profiles.feature`'s
    potential-VIP branch is removed (no fact carries it);
  - spec 010 FR-030 and `limits.feature`, spec 008 FR-030 and `registration.feature` gain R6's facts.

### Constitution re-check after design

Unchanged:

- one brand service with its own database and secret store;
- it produces to `casino.push` and `platform.audit` from consumers;
- it reads the six topics through consumers;
- it calls Customer.io only from `Send` steps and the seed, `players` only from the webhook's
  `send_message`, `rewards` only from the webhook's `grant_bonus` and `target_offer`;
- the contract change is additive.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| A durable send record per event; no freshness gate, no in-process dedup; a fact older than 24 h at first sight is `skipped_stale` | clarification 2026-10-08 | `ReplaySuite`; `tracks.feature`; `handler_test.go`'s freshness cases, marked |
| The Scala path's sends are attempted four times and parked; the original's were fire-and-forget | clarification 2026-10-08 | `AttemptsSuite`; `tracks.feature` |
| A per-call timeout of 10 s | R5 | `AttemptsSuite` |
| Marketing suppression enforced outbound, for sends and events; re-checked at every attempt; the operator's flag covers every channel; `marketing_suppressed` and `suppression_reason` are new attributes | clarify 2026-11-03 | `SuppressionSuite`; `suppression.feature`; the driver's RG recordings, marked |
| `withdrawal_failed` once per failure, not once per player per 25 h | R4 (the original's defect) | scenario 30's recording, marked |
| `player_name` without a display name; no `language`; no `affiliate_id`, `last_device_os`; `pot_vip` always `false` | settled 2026-11-03 (R9) | `AttributesSuite`; scenario 40's identify, marked |
| `payment_failed` without the crypto and `expires_at` keys | R8 (no fact carries them) | `EventsSuite`; the driver's payment-failed recording, marked |
| `affiliate_attributed` on registration with a tag only, data `btag` | R8; spec 012 | scenario 40's recording, marked |
| `rg_limit_loosened` from `rg_limit_loosening_requested` | settled 2026-11-03 | `EventsSuite`; the RG recordings |
| `bonus_name` is the offer id, as the original's `offer_ref` | R8 | `EventsSuite`; scenario 16's recording |
| The webhook answers 400 for a body that is not a JSON object; the original threw | R7 | `WebhookSuite` |
| The webhook's refusals are counted on status | FR-017 (new) | `WebhookSuite` |
| `send_message`'s `campaign_id` and `reward_id` are read and dropped; `players` takes neither | R6 | `WebhookSuite`; the free-spins recording's `send_message`, marked |
| `ready`'s `subjectId` is the player id, not `casino.player/<id>` | constitution III | `StreamSuite`; the driver's stream recording, marked |
| Presence cluster-wide with `since`; one `push_delivered` per `player_message_created` with the cluster's count; nothing on read or delete | settled 2026-11-03 | `ChannelSuite`; `push.feature` |
| A dead instance's connections expire within two minutes (`sweep-<player>`) | R10 | `ChannelSuite` across `restartService()` |
| The deposit-day reset is on by default, at 03:05 UTC | R1 (the chart's default was off) | `DepositDaySuite`; `profiles.feature` |
| The person is deleted at the CRM on erasure | FR-016 (new) | `ErasureSuite` |
| Paths move: the send route, the backoffice routes under `/v1/casino/backoffice/<slug>`, `/v1/casino/crm-config/<slug>`; `/v1/stream` and the webhook keep the original's | R7 | `WireTranslation` |

## What the next command needs to know

**Build order:**

1. the contract addition (R6 P4), `just schemas`, `sbt contract/publishLocal` with
   `CASINO_VERSION=0.2.0-014-SNAPSHOT`; the access rows; `keys.md`; the manifest and the modules
   tool's suites (R6 P5);
2. `responsible-gambling`'s request fact with its suite and `limits.feature` bound (R6 P1);
   `players`' consent facts at registration and the terminal `refused` with their suites and
   `registration.feature` bound (R6 P2, P3); `specs/010` and `specs/008` amended;
3. `crm-stub` (R2) beside `psp-stub`, in the stubs job;
4. the service skeleton: `ankka init`, `build.sbt`, `service.json`, `docker-compose.yml`, `Settings`,
   `EngagementCodecs`, the `just` recipes, the CI filter and job; `ModuleManifestSuite`'s built set
   gains `engagement`;
5. the pure packages with their suites, written first and failing first: `Events`
   (`handler_test.go`, `payment_failed_test.go`), `Attributes` (`handler_test.go`'s encoding cases),
   `DepositDay` (`daily_deposit_count_test.go`), `Classes`, `Suppression`, `Signature`
   (`CustomerIOWebhookRoutesSpec`'s verification cases), `Masking`, `Seed`, `Frames`, `WireTranslation`;
6. `Profile`, `Configuration`, `AgentTicket`, `Channel`, `AgentChannel` with their kits, every row of
   data-model.md's command tables;
7. `Crm`, `Players`, `Rewards` (trait, HTTP, fake);
8. `Send` on the shared kit: every CRM answer, the attempts, parking, retry, discard, suppression at
   each attempt, a restart mid-attempt;
9. the consumers with kits; the timers; the views; the publishers;
10. the five endpoints: the send route first (fail-first on `transactional.feature`), then the
    webhook (`campaigns.feature`), the stream and agent stream (`push.feature`), the backoffice and
    configuration routes (`operators.feature`);
11. the recording run, the driver and the stub wiring (needs the user, R2); `ParitySuite` over every
    recording, each marked divergence named;
12. the integration suites of the SC table; `GherkinSuite` over the features;
13. a kind deploy beside `core` with three instances, the Playwright suites, the stream from two
    devices (quickstart §6).

**Constraints the tasks must keep:**

- `Events`' table exactly as R8 and `handler.go`'s candidate order; `Attributes` exactly as
  `buildIdentifyAttributes`;
- no CRM call outside a `Send` step but the seed; no `players` or `rewards` call outside the webhook
  and erasure;
- every fact keyed by the player id; nothing personal on a fact, a log, a span, a timer name or an
  audit record's values.

**Elsewhere** (research, *Elsewhere*): spec 008's four unsent triggers, display name, language and
potential-VIP mark, and the online filter; spec 015's online count; spec 019's profile seeding; spec
017's reads; spec 012's affiliate codes.
