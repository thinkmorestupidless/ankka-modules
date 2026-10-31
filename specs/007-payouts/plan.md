# Implementation Plan: payouts

**Branch**: `007-payouts` | **Date**: 2026-10-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-payouts/spec.md`

## Summary

`payouts` is the brand service that takes a player's withdrawal from request to settlement:

1. it checks the request against the player's identity, the brand's withdrawal limits and the wallet's
   preview;
2. the wallet holds the money;
3. an operator reviews the request;
4. the gateway is asked to pay;
5. the request waits for the provider's outcome, asks for status, or marks itself for review, until it
   is completed, failed or reversed.

It also keeps the player's bank destinations and the brand's terminal listing.

The service has:

- three event sourced entities: `WithdrawalRequest` (per request), `PlayerPayouts` (per player) and
  `WithdrawalLimits` (per brand);
- one key value entity, `TerminalCatalogue` (per brand);
- one workflow per request (`WithdrawalWorkflow`), which makes every call out and owns every wait;
- one view (`WithdrawalQueue`) and one recurring timer (`terminals-<slug>`);
- three consumers in (`PlayersConsumer`, `DepositsConsumer`, `RequestsConsumer`) and three out
  (`WithdrawalPublisher`, `AuditPublisher`, `ConfigAuditPublisher`);
- five endpoints.

Eligibility, the limit check, the IBAN and name rules and the automated-or-manual choice are pure
ports of the original, held to its specs' cases. Decisions are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.13.0 (constitution VI; the versions every service here
pins).

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract`:
  - `Routes.player`, `Routes.operator`, `Routes.service`, `Routes.serviceOf`, `Routes.playerId`;
  - `contract.money`, `Personal`;
  - `CasinoPlayers` (the four KYC families, `player_registered`, `player_profile_updated`,
    `player_closed`, `player_email_changed`), `MoneyTransactions` (`deposit_confirmed`),
    `CasinoWithdrawals`, `PlatformAudit`;
  - `port.Recording`.
- **Contract changes** (additive, R7 and R11):
  - `player_registered.email: Option[Personal[String]]`;
  - a new family `player_email_changed`;
  - on `casino.withdrawals`: `gateway` on all six families, `providerReference` on three, `terminal`
    on five, and `reason` on `withdrawal_cancelled`.

**Storage**: the service's own Postgres from ankka (journal, durable state, view, workflow and timer
state). There is no bucket and no secret: both calls out are within the installation by identity.

**Testing**:

- munit;
- `EventSourcedTestKit` for the three event sourced entities, `KeyValueEntityTestKit` for
  `TerminalCatalogue`, `WorkflowTestKit` for `WithdrawalWorkflow`, `ConsumerTestKit` for the six
  consumers;
- the pure `Eligibility`, `Limits`, `Gates`, `Iban`, `Names` and `Approval`, with their own suites;
- `AnkkaTestKit.start` over Postgres, with these fakes and harnesses:
  - `FakeWallet` (preview, hold, release, confirm, reverse) and `FakeGateway` (listing, initiate,
    status, each answer scriptable) under `localServices`;
  - `InMemoryBroker` for `casino.players`, `money.transactions`, `casino.withdrawals` and
    `platform.audit`;
  - `TimerRuntime(200.millis)` and a settable clock;
- `GherkinSuite` binding the `features/payouts/*.feature` files;
- `contract.port.Replay` over `tests/port/payouts/` through `WireTranslation`.

**Target Platform**: every brand project on the self-hosted installation (kind locally, GKE), as the
core module's `payouts` entry declares.

**Project Type**: the work is:

- one ankka service (`services/payouts/`);
- the contract changes;
- `players`' publisher: `player_registered.email` and `player_email_changed` (R7);
- `brand_finance` in the operator realm (R6);
- the core manifest's `payouts` entry, unchanged but checked;
- `contract/keys.md` loses the `payouts` row;
- spec 001's `grants.md:24` corrected.

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-001 | every recording in `tests/port/payouts/` replays with the original's outcome, status word and money movement; each marked divergence named |
| SC-002 | `PayoutFailureSuite` breaks the gateway at each answer of R7 and at each wait of R8: no request reaches awaiting manual payout but by approval on a manual terminal |
| SC-003 | `DoublePaySuite`: initiated, a timeout, then completed on the outcome route and the status read at once leaves one completed request and one confirm |
| SC-004 | `ScaleSuite`: 10,000 requests, 200 in flight; each in-flight request's workflow alone wakes; no component reads the whole set |
| SC-005 | a request answered in under 2 s at p99, with one preview and one hold to the wallet and nothing else called (R4, R5) |
| SC-006 | `ErasureSuite`: after erasure, every `Personal` field reads blank on the request, the player's record and the queue; amounts, statuses and times unchanged; facts never carried them (`PublishersSuite`) |
| SC-007 | `EligibilitySuite`: 1,000 reads for one player, one order |

**Constraints**:

- five endpoints, one prefix each, at most two path parameters, the ACL per route, the slug a literal
  (R6);
- on a request's path, nothing called but the wallet's preview and hold (SC-005);
- the gateway is called only by the workflow, and by the catalogue's timer;
- every money step is keyed `<request id>/<step>` and retried until the wallet answers (R9);
- `Personal` covers every destination field, address, account number, name, email and reason. Nothing
  personal goes in a log, a span, a fact, an audit record's free text, a view row's unmarked column or
  a timer name;
- money is `Long` minor units with its currency (constitution II).

**Scale/Scope**:

| Item | Count |
|---|---|
| Player routes | 9 at `/v1/casino/brands/<slug>/players` (request create, list, one, cancel; terminals; destinations list, save, set default, delete) |
| Operator routes | 8 at `/v1/casino/brands/<slug>/withdrawal-requests` (list, one, approve, reject, confirm payout, mark failed, status counts) and `withdrawal-history` at `/players` |
| Config routes | 2 at `/v1/casino/brands/<slug>/withdrawal-config` |
| Outcome route | 1 at `/v1/casino/brands/<slug>/withdrawals` |
| Service routes | erasure at `/v1/casino/brands/<slug>/payouts` |
| Event sourced entities | 3 (`WithdrawalRequest`, `PlayerPayouts`, `WithdrawalLimits`) |
| Key value entities | 1 (`TerminalCatalogue`) |
| Workflows | 1 (`WithdrawalWorkflow`) |
| Views | 1 (`WithdrawalQueue`) |
| Timers | 1 recurring (`terminals-<slug>`); the workflow's own waits |
| Consumers | 6 (3 in, 3 out) |
| Feature scenarios bound | every scenario of `features/payouts/` |
| Recordings replayed | scenario 30 (the driver) and scenario 251 (R2) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings named, parity tests left, divergences declared | Pass. R1 read the original whole and lists its defects, and how the spec treats each. R2 corrects the spec's recordings, which could not be recorded, and names scenario 30 and scenario 251, one parity test per recording. R4 restores the original's order of checks. *Divergences* below lists every change, with what demonstrates it. `contracts/surface.md` disposes of the original's withdrawal surface. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; keyed money commands; no balance kept | Pass. The wallet holds, releases, confirms and reverses; `payouts` keeps no balance. Each step is keyed by the request id and step (R9), and the workflow's state is the lifetime record past the wallet's 1,000 recent keys. Limit usage is a sum of this service's own requests, not a balance. |
| III. A brand is a project | keys carry no brand | Pass. Ids are the request id, the player id and the slug, one per project. The slug is a literal in each prefix. |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. The wallet and the gateway are called over HTTP by identity. Identity, account status, names, email and deposits arrive as facts. Withdrawals leave as facts. |
| V. Personal data marked, erasure honoured | `Personal`; the erasure handler | Pass, with 008's 042 gap. R12 lists the fields. The erasure route blanks them until ankka 042's `erase(subject)`. `players`' erasure gains `payouts` (*Elsewhere*). |
| VI. Scala by default | | Pass. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1146 scenarios (2026-10-31). |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (three sessions), 2026-10-25 and 2026-10-31. Three planning decisions were settled with the user on 2026-10-31: the email (R7), the finance role (R6) and the terminal listing (R5). |
| Scope: ankka gaps are ankka specs | | Pass: nothing new (*For ankka*). |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-004 to FR-008 | `RequestEndpoint` runs pure `Gates` over `PlayerPayouts`, `WithdrawalLimits`, `TerminalCatalogue` and the wallet's preview, then starts `WithdrawalWorkflow`, whose `start` is the atomic claim and whose first step is the hold | the original's order (R4), one call to the wallet, the outcome polled from the workflow's state | the hold from the endpoint (no lifetime record of the key, R9) |
| FR-005a, FR-005b | `WithdrawalLimits` (event sourced, id = slug); pure `Limits` over `PlayerPayouts`' recent requests | the audit names each change; usage read-your-writes (R3, R10) | a view of usage (seconds behind: two requests could both pass) |
| FR-008, FR-014 to FR-016, FR-020, FR-023 | `WithdrawalRequest` (event sourced, id = request id) | the history from one request; the facts and the queue from its events | the workflow's state alone (not a stream a consumer reads, R3) |
| FR-009, FR-015 to FR-024 | `WithdrawalWorkflow` (id = request id): hold, review pause, approval, payout call, waits, settle | durable steps, retries and timers per request; no scan (SC-004) | the original's five-minute sweeper (capped, R1) |
| FR-010 to FR-012 | `TerminalCatalogue` (key value, id = slug), refreshed by `terminals-<slug>`; pure `Eligibility` | the wallet alone on a request's path (R5) | the gateway per request (settled against, 2026-10-31) |
| FR-003, FR-013 | `PlayerPayouts` (event sourced, id = player): destinations, names, email, identity, account, deposits, recent requests; pure `Iban`, `Names` | one record answers every gate for one player | the spec's views (lose read-your-writes, R3) |
| FR-003 | `PlayersConsumer` (`casino.players`), `DepositsConsumer` (`money.transactions`), `RequestsConsumer` (own request events → `PlayerPayouts`) | facts in, as every service here | — |
| FR-014, FR-022 | `WithdrawalQueue` view from `WithdrawalRequest` events | filters and counts in one place | — |
| FR-017, FR-018 | pure `Approval` (automated or manual, the gateway's fields); `Gateway` client | the original's `resolveApprovalOutcome`, every answer read (R7) | — |
| FR-019 to FR-021 | `OutcomeEndpoint` → the workflow's `outcome` command; the workflow's status timer | the outcome and the poll meet in one state | — (R8) |
| FR-025, FR-026, FR-029 | `WithdrawalPublisher` → `casino.withdrawals`; `AuditPublisher`, `ConfigAuditPublisher` → `platform.audit` | as every service here | (R11) |
| FR-027, SC-006 | `Personal` fields; `ServiceEndpoint` erasure | blank until 042 | (R12) |
| FR-001, FR-002 | `PlayerEndpoint`, `OperatorEndpoint` (role check), `ConfigEndpoint` (admin check), `OutcomeEndpoint` (grant), `ServiceEndpoint` | ankka 0.13's route limits | (R6) |

Pure logic with no component: `Gates`, `Limits`, `Eligibility`, `Iban`, `Names`, `Approval`,
`Refusals`, `WireTranslation`, `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/007-payouts/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R14, "For ankka", "Elsewhere"
├── data-model.md        # Phase 1: each entity's state, commands, events and refusals; the workflow; view, timers, consumers
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── surface.md       # the original's withdrawal routes and functions, each moved, dropped or new
│   ├── routes.md        # every route by endpoint; bodies, answers and refusals; the calls out
│   ├── facts.md         # events → families; audit records; what is consumed
│   └── wire.md          # the parity translation
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala  # + email, player_email_changed
contract/src/main/scala/io/eitheror/casino/contract/facts/Others.scala         # casino.withdrawals fields
contract/keys.md                                   # payouts leaves "Not yet built"
brands/_realm/eitheror-operators.json              # + brand_finance
services/players/src/main/scala/.../facts/Facts.scala  # email on player_registered; player_email_changed
specs/001-brands-projects/contracts/grants.md      # line 24: the three payout routes
services/payouts/                                  # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── docker-compose.yml                             # Postgres on 5439
├── README.md
├── src/main/scala/io/eitheror/casino/payouts/
│   ├── request/        # WithdrawalRequest, Model (state, events, commands), Gates, Refusals
│   ├── workflow/       # WithdrawalWorkflow, Approval
│   ├── player/         # PlayerPayouts, Model, Iban, Names
│   ├── limits/         # WithdrawalLimits, Limits
│   ├── terminals/      # TerminalCatalogue, CatalogueTimer, Eligibility
│   ├── queue/          # WithdrawalQueue
│   ├── consumers/      # PlayersConsumer, DepositsConsumer, RequestsConsumer
│   ├── facts/          # Facts (event to fact); Publishers: WithdrawalPublisher, AuditPublisher, ConfigAuditPublisher
│   ├── clients/        # Wallet, Gateway (trait + HTTP)
│   ├── api/            # PlayerEndpoint, OperatorEndpoint, ConfigEndpoint, OutcomeEndpoint, ServiceEndpoint; Answers
│   ├── PayoutsCodecs.scala
│   ├── Settings.scala
│   └── PayoutsService.scala
└── src/test/scala/io/eitheror/casino/payouts/
    ├── request/, workflow/, player/, limits/, terminals/, facts/   # kits and pure suites
    ├── it/                                        # Kit, FakeWallet, FakeGateway; the integration suites
    ├── port/                                      # WireTranslation, ParitySuite
    └── features/                                  # GherkinSuite bindings, one file per feature
tests/original/payouts-driver/                     # scenario 30, the driver (R2)
tests/port/payouts/                                # the recordings, after the run
.github/workflows/ci.yml                           # + the service's filter and job
justfile                                           # + payouts-test, payouts-it, payouts-run
```

**Structure Decision**: one service directory in the shape of `services/kyc/`, a package per
capability. The two outbound seams, `Wallet` and `Gateway`, are each a trait with an HTTP implementation
and a fake. The service runs locally on 9004, with Postgres on 5439.

## Phase 0: research

Done: [research.md](research.md), R1–R14. Three questions were settled with the user on 2026-10-31:

- the player's email reaches `payouts` on `casino.players` (R7);
- `brand_finance` is added to the operator realm (R6);
- the terminal listing is held and refreshed every 60 seconds (R5).

Verified while planning (2026-10-31):

- the wallet's holds routes as built: preview answers 200 with its refusal in the body, the hold
  answers 409 for every refusal, and 1,000 recent keys are kept (`services/wallet/`);
- `psp-gateway`'s three payout routes admit `payouts` only; the listing carries every field
  eligibility needs; the outcome is delivered to `…/withdrawals/{requestId}/outcome`, unsigned, and
  retried on 429 and 5xx (`services/psp-gateway/`);
- the core module declares `payouts` (image 0.0.0, exposed) and grants `payments`/`psp-gateway` the
  outcome route (`modules/core/module.json:317-381, 539-547`);
- `casino.withdrawals` exists, keyed by the player, read by `attribution` and `players`
  (`Others.scala:80-122`);
- the operator realm has no `brand_finance`; `players`' `ErasureWorkflow.targets` has no `payouts`;
- `TestScenario7` and `TestScenario10` cannot be recorded (R2).

## Phase 1: design

- [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).
- The spec is amended with this plan:
  - FR-005 and FR-005b follow R4's order;
  - FR-025 is keyed by the player;
  - the Key Entities say entity for the eligibility and name views (R3) and add the catalogue;
  - the Recordings are corrected (R2);
  - FR-003 adds the email.

### Constitution re-check after design

Unchanged:

- one brand service with its own database;
- it produces to `casino.withdrawals` and `platform.audit` from consumers;
- it reads `casino.players` and `money.transactions` through consumers;
- it calls the wallet on a request's path and the gateway only from the workflow and a timer;
- the contract changes are additive.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| A second approval, or a rejection after approval, is `REQUEST_NOT_PENDING` | FR-015, R1 | `WithdrawalRequestSuite`; `review.feature`; no recording (the defect is not driven) |
| An outcome is accepted only once approved or awaiting manual payout; a settled request answers the same outcome 200 and moves nothing | FR-019, FR-020, R8 | `OutcomeSuite`; `outcomes.feature` |
| A provider's rejection at initiation releases and tells the player; nothing reaches manual payout but an operator | FR-018 | `PayoutFailureSuite` (SC-002); scenario 30's rejection, marked |
| Operator actions refuse another brand | FR-001 | `OperatorSuite` |
| Limits per currency; usage from this service's requests | FR-005a, FR-005b | `LimitsSuite`; `requests.feature`; the driver's limit scenarios on EUR, marked for other currencies |
| The turnover refusal from the hold is the preview's 402, not 500 | R4 | `GatesSuite`; scenario 30, marked |
| Amounts in minor units throughout | FR-028 | `WireTranslation` |
| The outcome route moves to the gateway's path, admitted by the grant, with no secret | R6 | `WireTranslation` maps the internal route; `OutcomeSuite` |
| Morphosis answers `completed` like every provider | R1 | `ApprovalSuite`; `FakeGateway` |
| One payout per request, reconciled by its own workflow; no scan, no cap | SC-004 | `ScaleSuite` |
| A terminal deactivated in the last minute may be offered; the gateway refuses it at initiation | R5 | `EligibilitySuite`; `PayoutFailureSuite` |
| Approval emits `withdrawal_approved` alone; the fact's amount is the amount paid | R11 | `PublishersSuite` |
| The terminal listing's currency is the wallet's, not GBP by default | R5 | `EligibilitySuite`; scenario 30, marked |
| Reasons, notes and destination fields are `Personal` | FR-027 | `ErasureSuite`, `LeakSuite` |
| Unused rate limits dropped | R1 | — |

## What the next command needs to know

**Build order:**

1. the contract additions (R7, R11), `just schemas`, `sbt contract/publishLocal` with
   `CASINO_VERSION=0.2.0-007-SNAPSHOT`; `players`' publisher and its `FactsSuite` cases; `brand_finance`
   in the operator realm; `grants.md:24`; the CI filter and job;
2. the service skeleton: `ankka init`, `build.sbt`, `service.json`, `docker-compose.yml`, `Settings`,
   `PayoutsCodecs`, the `just` recipes; `contract/keys.md` loses the `payouts` row, which
   `ModuleManifestSuite` enforces once `services/payouts/build.sbt` exists;
3. the pure packages with their suites, written first and failing first:
   - `Gates` and `Refusals` (R4's table);
   - `Limits` (`WithdrawalLimitChecker`'s cases);
   - `Eligibility` (`WithdrawalTerminalEligibility`'s cases);
   - `Iban`, `Names` (`PayoutDestinationRoutesSpec`'s);
   - `Approval` (`WithdrawalRoutesSpec`'s approve cases);
   - `WireTranslation`;
4. `WithdrawalLimits`, `PlayerPayouts`, `WithdrawalRequest`, `TerminalCatalogue` with their kits, every
   row of data-model.md's command tables;
5. `Wallet` and `Gateway` (trait, HTTP, fake);
6. `WithdrawalWorkflow` with its kit: hold, review, approval, each gateway answer, each wait;
7. the consumers with kits; `WithdrawalQueue`; the publishers (`PublishersSuite`: every event to its
   family, never a `Personal` value);
8. the five endpoints: requests first (fail-first on `requests.feature`), then terminals, destinations,
   review, settlement, outcome, config, erasure;
9. the recording run (needs the user, R2); `ParitySuite` over every recording, each marked divergence
   named;
10. the integration suites:
    - `GatesSuite` (SC-005), `PayoutFailureSuite` (SC-002), `DoublePaySuite` (SC-003);
    - `ScaleSuite` (SC-004), `ErasureSuite` (SC-006), `EligibilitySuite` (SC-007);
    - `OperatorSuite`, `OutcomeSuite`, `LeakSuite`;
    - `GherkinSuite` over the features;
11. a kind deploy beside `wallet`, `players`, `kyc` and `psp-gateway`, with an outcome delivered by the
    gateway through its grant.

**Constraints on that order:**

- Parity tests are written before the components they test and fail first (ankka-port §7). Until the
  run, the suite is `.ignore`d.
- The recording run is against a local original only. The original's internal secret stays in the
  recording worktree.
- Step 1 touches `contract/`, `services/players/` and the realm, which other branches also touch. Keep
  it additive, and rebase before merging.

**Elsewhere:**

- **Spec 008 (`players`)**: `ErasureWorkflow.targets` gains `payouts` (R12).
- **Every reader of `casino.players`** is rebuilt on the new contract before `player_email_changed`
  is first published.
- **The operator console and the player site**: base URLs, minor units, and brand configuration saving
  limits here and turnover terms to the wallet (spec 001's frontend list, R10).
- **Spec 012 (`attribution`)** may read `gateway` and `providerReference`; nothing it reads changes.
- **Spec 013** decides the affiliate consequence of a failure after approval (spec Out of scope).
