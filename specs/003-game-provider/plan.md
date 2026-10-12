# Implementation Plan: Game Provider

**Branch**: `003-game-provider` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-game-provider/spec.md`

## Summary

`game-provider` is one Scala service of a brand's project that stands between the game provider
and the wallet. It serves ST8's signed seamless-wallet callbacks, keeps one event sourced round per
provider round with every transaction's outcome (so a repeat is answered without the wallet and a
dispute finds its round years later), places and settles each movement with the wallet by the
provider's own transaction id, and publishes `round_settled`. It also owns the launch (eligibility
asked of three neighbours, a one-use ticket, the signed launch call), the catalogue (fed nightly per
provider, curated per brand, queried from a view), the free-rounds calls `rewards` makes through it,
and an operator's replay or resolve of a callback the wallet never acknowledged. Everything ST8
sits behind one adapter seam so a second provider is a configuration and an adapter, as the brand
may run several at once. Decisions and what established them are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.11.0 (constitution VI: the callbacks are the money
path). Nothing else; the recordings come from the original's own Go suite (spec 001 R10).

**Primary Dependencies**: `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`,
`ankka-auth-oidc`, `ankka-telemetry-otlp`, `ankka-testkit`; `io.eitheror:casino-contract`
(published locally) for facts, money, route classes and the recording replayer; the JDK's
`SHA256withECDSA` for signatures. ankka 023 (secret store) as released; 032 (recurring timers), 024
(replayable topics) and 038 (secret backends) from their branches until released, each with the
fallback research R8 and R11 name.

**Storage**: the service's own CNPG Postgres from ankka (journal, key value store, view tables,
secret store, timers). Nothing shared (constitution VI).

**Testing**: munit; `KeyValueEntityTestKit` and `EventSourcedTestKit` for the entities,
`ConsumerTestKit` for the two consumers; `AnkkaTestKit.start` with Postgres for views, the timer,
the parity suite, durability (`restartService`) and the features; `GherkinSuite` binding
`features/game-provider/*.feature`; `contract.port.Recording` for `tests/port/game-provider/`
(research R15).

**Target Platform**: a brand's ankka project on the self-hosted installation (kind locally, GKE),
the service exposed as `game-provider-<slug>`.

**Project Type**: one ankka service (`services/game-provider/`) plus an additive contract change.

**Performance Goals**: SC-002, a money callback under 100 ms at the 95th percentile with the wallet in
the project: one secret-store-free configuration read, one key value read (ticket), one key value
write (index), one entity command (round), one wallet call, one entity command (record).

**Constraints**: minor units as `Long` from the boundary; every money callback idempotent under the
provider's id; the signature verified before anything is read; no personal field stored; no entity
reads the secret store; the frontend's and the provider's paths unchanged.

**Scale/Scope**: 8 callbacks at 3 prefixes, 1 launch route, 5 catalogue routes, 13 operator routes,
5 service routes; 9 entities, 7 views, 2 consumers, 1 timed action; 38 recordings; 10,000 games.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings and the parity tests it leaves | Pass: R15; 38 recordings named in the spec, one parity test each, plus the adapter's own suites ported as entity tests. Divergences are the spec's *Changes from the original* (settle every bet, catalogue by provider and code, popularity by rounds, replay and resolve, conflicting repeats, jurisdiction from configuration) and three more found in planning (R16, *Spec corrections*): buyin's RG refusals, the launch's `bet_limits`, and the brand-less callback forms. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; every movement keyed by the provider's id; no balance kept here | Pass: `MinorUnits` from the boundary (FR-005); keys are transaction and cancel ids (R6, R7); the round records outcomes, never a balance of its own |
| III. A brand is a project | `{brand}` kept and refused when wrong; no shared project reaches here | Pass: R2 names the one exception (ST8 strips the path) and how the brand is still the project's; no grant is made or received |
| IV. Commands over HTTP, facts over topics | Facts from a consumer in the contract's shape; no store read across services | Pass: `RoundFacts` → `casino.rounds` (R14); `PlayerStatus` reads a topic, not `players`' store (R8); eligibility is three HTTP calls (R9) |
| V. Personal data marked, erasure honoured | No personal field; the profile is read at call time | Pass: FR-028, R16; whether 042 wants an empty handler is a task |
| VI. Scala by default | | Pass; nothing else |
| VII. Specs in the domain's words | `just features` passes; scenarios named from the spec | Pass: 0 findings, 1,031 scenarios (this session) |
| Workflow: clarify before plan; `just features` before plan | | Pass (both this session) |
| Scope: ankka gaps are ankka specs | | Pass: 024, 032 and 038 are used as specified and each has a fallback until released (R8, R11); nothing is rebuilt here |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-008, FR-010: a one-use ticket that becomes the session ticket | `Ticket`, key value, id = token | one command consumes it, so no read-then-write race; nobody needs its history | event sourced: a journal row per spin's ticket for no reader (R5) |
| FR-011 to FR-016, FR-019, FR-020: every transaction of a round, its outcome, its settlement | `Round`, event sourced, id `<provider>/<roundId>` | the audit trail a dispute needs; the exactly-once source for views and the fact | key value: a view over it can skip states, and the fact needs every settlement (R6) |
| FR-011, FR-012: a transaction's claim, its `may-bet`, its wallet call and its outcome for the ledger's life | `BetWorkflow`, id `<provider>/<transaction id>` (2026-10-10; replaces `TransactionIndex` and the round's stored answers) | `start` is an atomic claim, the journal survives a crash, the finished state is the lifetime record the wallet's 1,000 recent keys no longer are | a transaction entity: "seen" and "debited" are two writes on two entities (spec 002 R17; R18) |
| FR-018, FR-025: a player known to a provider | `KnownPlayer`, key value | latest value only | — |
| FR-007: 20 launches a minute per player | `LaunchWindow`, key value with `expireAfter` | consistent across instances for one command | in-memory per pod, the original's: a player's limit multiplied by the instance count (R9) |
| FR-021, FR-022: the catalogue and the brand's curation | `Game`, key value per `<provider>/<code>`; `Catalogue` view; `BrandCatalogueConfiguration`, key value per brand | SQL over the view for the lobby's filters; the decision computed at read so a change applies at once | one entity holding 10,000 games: a 10 MB state rewritten nightly (R10) |
| FR-023: popular games | `RoundCounter` consumer → `GameRounds` key value → `GamePopularity` view | ordered by a number the view holds | counting rounds per game at read: a query per game (R10) |
| FR-021: nightly per provider | `CatalogueRefresh` timed action, recurring (032); `RefreshStatus` key value | durable, survives restarts, one instance runs it | a CronJob calling a route, the original's: a Kubernetes object outside the manifest (R11) |
| FR-012, FR-017: a player's account lock for a debit (cooling off and exclusion: the workflow's `may-bet`, R18) | `PlayerStatus` view over `casino.players` | no HTTP in the money path; the facts are the brand's | asking `players` per debit: a call the budget cannot afford and a failure mode on every spin (R8) |
| FR-019: `round_settled` | `RoundFacts` consumer over `Round` events | the contract's shape from a consumer, exactly once | — |
| FR-020: unacknowledged list, replay, resolve | `Unacknowledged` view; operator endpoint commands on `Round` | the entry lives on the round it belongs to | a separate ledger entity, the original's: two places for one fact (R13) |
| FR-001: rounds by player and by session | `RoundsByPlayer`, `RoundsBySession` views | questions over many rounds | — |
| FR-003, FR-026: provider configuration, rotated without a redeploy | the service's secret store, `provider/<name>`; `ProviderConfigurations` cache, 30 s | 023 is where a private key belongs; the cache keeps 038's read record off the spin path | `secretKeyRef` env: a redeploy per rotation (R4) |
| FR-002 to FR-006, FR-010 to FR-016: the callbacks | `CallbackEndpoint`, public, raw-body signature check, `St8Adapter` | one endpoint, three prefixes, the protocol in the adapter | one endpoint per prefix: three copies of the routes (R2) |
| FR-007 to FR-009: launch | `PlayerEndpoint` with `PlayersClient`, `KycClient`, `ResponsibleGamblingClient`, `St8Adapter.launch` | one audience, one ACL | — |
| FR-022 to FR-024, FR-026, FR-020: operator | `OperatorEndpoint` | one audience | — |
| FR-025: `rewards`' free rounds | `RewardsEndpoint`, service class | one audience; callers in the project | — |
| FR-029: metrics | `eitheror_casino_bets_total{brand}`, `eitheror_casino_unacknowledged_callbacks{brand}` via `ankka-telemetry-otlp` | the original's name kept | — (R16: verify the SDK's metrics API) |

Pure logic with no component: `money.Amounts` (decimal string ↔ minor units per currency exponent),
`St8Signature` (verify, sign), `St8Protocol` (fields, status words, answers), `Enablement` (the
catalogue decision), `BetLimits` (the band).

## Project Structure

### Documentation (this feature)

```text
specs/003-game-provider/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R16, spec corrections
├── data-model.md        # Phase 1: entities, views, the configuration document, the fact
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── callbacks.md     # the eight callbacks as the brand answers them, every status word
│   ├── routes.md        # every route by class; what is dropped from the original
│   ├── neighbours.md    # what this service asks of wallet, players, kyc, responsible-gambling
│   ├── provider-api.md  # the signed calls to ST8: launch, games, bonus, key document
│   └── round-settled.md # the fact and the three fields added to the contract
└── tasks.md             # Phase 2 (/speckit-tasks): 82 tasks by story
```

### Source Code (repository root)

```text
contract/                                   # additive: RoundSettled gains provider_transaction_ids,
│                                           # wallet_transaction_ids, free_rounds_grant; schemas regenerated
services/game-provider/                     # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json                            # rendered by the brands tool in deployment; local run
├── src/main/scala/io/eitheror/casino/gameprovider/
│   ├── money/                              # Amounts: decimal strings ↔ MinorUnits by currency exponent
│   ├── provider/                           # ProviderAdapter (trait); st8/: St8Adapter, St8Signature,
│   │                                       # St8Protocol, St8Client (launch, games, bonus, key document)
│   ├── config/                             # ProviderConfiguration (document), ProviderConfigurations (cache)
│   ├── tickets/                            # Ticket entity
│   ├── rounds/                             # Round entity (state, events, commands), BetWorkflow (R18),
│   │                                       # Settlement (which wallet calls a credit or cancel makes)
│   ├── players/                            # KnownPlayer, LaunchWindow, PlayerStatus view
│   ├── catalogue/                          # Game, Catalogue view, BrandCatalogueConfiguration, Enablement,
│   │                                       # GameRounds, GamePopularity view, RoundCounter consumer,
│   │                                       # CatalogueRefresh timed action, RefreshStatus
│   ├── facts/                              # RoundFacts consumer → casino.rounds
│   ├── views/                              # RoundsByPlayer, RoundsBySession, Unacknowledged
│   ├── clients/                            # WalletClient, PlayersClient, KycClient, ResponsibleGamblingClient
│   ├── api/                                # CallbackEndpoint, PlayerEndpoint, OperatorEndpoint, RewardsEndpoint
│   │   └── callbacks/                      # one handler per callback, Idempotent (the repeat check)
│   └── GameProviderService.scala           # registration, extensions, the recurring timer's schedule
└── src/test/scala/io/eitheror/casino/gameprovider/
    ├── money/, provider/                   # Amounts; St8Signature against the recordings' test key
    ├── rounds/                             # Round kit: multi-debit settle, repeats, conflicts, unacknowledged
    ├── tickets/, players/, catalogue/      # entity kits; Enablement table; BetLimits
    ├── facts/                              # RoundFacts and RoundCounter consumer kits
    ├── it/                                 # views, timer, restartService, the fake wallet and neighbours, the ST8 stub
    ├── port/                               # ParitySuite: one test per tests/port/game-provider/*.json
    └── features/                           # GherkinSuite step bindings for features/game-provider
tests/port/game-provider/                   # the 38 recordings (committed after the recording run)
tests/fixtures/specs/003/                   # st8-local.json, the recordings' test key pair, fake-st8's games.json
brands/goldvibe/manifest.json               # game-provider entry kept; the ST8_* env and game-provider-st8 secret removed (R4)
```

**Structure Decision**: one service directory as ankka's tooling expects; the provider's protocol
in its own package behind a trait so a second adapter is one package; the contract change in the
root build as spec 001 fixed; recordings and fixtures beside the other services'.

## Phase 0: research

Done: [research.md](research.md), R1–R16. No unknown remains. Three items are marked *verify at
task time* because the pinned SDK's surface decides them, each with a fallback: raw-bytes request
body (R3), a topic read from its start (R8), the metrics API (R16). Three spec corrections are
listed for the spec's next edit.

## Phase 1: design

- [data-model.md](data-model.md): the nine entities, seven views, the configuration document, the
  fact, and the round's transitions.
- [contracts/](contracts/): the callbacks with every status word; every route by class; what this
  service asks of its four neighbours, the wallet's now fixed by spec 002's plan; the signed calls to
  ST8; the `round_settled` additions.
- [quickstart.md](quickstart.md): recordings, unit suites, features, parity, a local run with the
  simulator, an installation.

### Constitution re-check after design

Unchanged. The design adds one service with its own database, one topic read (`casino.players`) and
one topic written (`casino.rounds`), three in-project HTTP calls at launch and one per movement to
the wallet, and an additive contract change. Nothing reads another service's store; nothing holds a
balance; nothing holds a personal field.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| A credit settles every open bet of the round | FR-014 (the 2026-08-19 leak) | `BigRoundMultipleDebits`, `BigRoundMultipleCredits`: the fake wallet holds no open bet after |
| A repeat with a different body is recorded | FR-011 | `IdempotentCredit` with a changed amount in a second step the parity kit adds as a marked extension |
| A refused debit answers the player's balance where the original answered `0.00` | FR-012; the original reads a balance its wallet's refusal does not carry | `NotEnoughMoney`: the refused debit's `balance` is a marked divergence in the parity suite |
| The bet-time `responsible-gambling` check fails closed, in this service's workflow | spec Changes; the original's, in the adapter, fails open | a feature scenario; the parity recordings carry no RG outage |
| Buyin answers `player_locked` / `spending_limit` where the original answered `ok` | FR-016 reads FR-012's mapping | `BasicBuyin` unchanged; a feature scenario |
| Jurisdiction and `bet_limits` from configuration and the deposit limit | FR-018; R16 | `TestScenarioFreeSpinsPlay_*`: `player_profile` compared with `jurisdiction` under `vary` |
| The test-control routes are not served | Clarifications | recordings whose steps hit `/st8/test/*` replay those steps as kit setup |
| Game session routes answered by `players` | spec 008 | `TestScenario7_CasinoGate3_SessionLifecycle`: the session steps are blocked by spec 008 and marked |
| Catalogue rows carry `provider`; a shared code is refused | Clarifications 2026-10-09 | the Playwright reads unchanged (SC-006); a feature scenario |

## What the next command needs to know

- *2026-10-10:* the bet workflow (R18) replaces the transaction index and the round's stored answers,
  and `may-bet` moves here from the wallet; tasks.md's Phase 12 reworks what was built before it.
- Order: the contract's `RoundSettled` additions and `just schemas`; `money.Amounts` and
  `St8Signature` with their unit tests; `Ticket`, `Round`, `TransactionIndex` with kits (the
  multi-debit settlement, repeats and conflicts first, fail-first); the fake wallet, players, kyc and
  RG stubs and the ST8 stub in `it/`; `CallbackEndpoint` and `WalletClient`; the parity suite over the
  recordings (which must exist first: the recording run is a task of its own and needs the original
  running); launch; catalogue, refresh and popularity; `RoundFacts` and `Unacknowledged`; operator
  and rewards endpoints; the features binding; the manifest edit and a kind deploy.
- Parity tests are written before the components they test and must fail first (ankka-port §7).
- The recording run follows `tests/original/README.md`; `fake-st8` is the original's and needs no
  stub of ours; the preflight must know it.
- Spec 002's plan now fixes the wallet's paths; `contracts/neighbours.md` names two bet fields
  (`game_category`, `provider`) 002's route lacks. Raise it on 002 before the `WalletClient` task.
- The paths for `players`, `kyc` and `responsible-gambling` are *expected*; the stubs carry the same
  strings as the clients so one edit moves both when those specs are planned.
- The three spec corrections (research, *Spec corrections*) are applied to spec.md: FR-019's
  key, FR-002's brand-less forms, and the launch's `bet_limits` under *Changes*.
- 032 recurring timers: until merged, the refresh schedules a single timer that reschedules itself
  from the handler. 024: until merged, the `PlayerStatus` view is seeded in tests and at migration.
