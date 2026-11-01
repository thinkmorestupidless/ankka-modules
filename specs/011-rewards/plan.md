# Implementation Plan: rewards

**Branch**: `011-rewards` | **Date**: 2026-11-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/011-rewards/spec.md`

## Summary

`rewards` is the brand service that decides when a player gets a reward, and on what terms. The
wallet holds the money. The service:

1. holds the brand's offers, their codes and their order;
2. decides eligibility with the original's evaluator, one rule order, fourteen reasons, on every path;
3. takes a reward from available or pending to active by asking the wallet to award restricted funds
   under the reward's id;
4. follows the wallet's record on `money.transactions` through conversion, forfeit, cancel and close;
5. fires each reward's own clocks: claim deadline, wagering window, cap deadline, pending selection,
   free-rounds expiry;
6. gives free rounds through `game-provider` and settles them from `round_settled`;
7. publishes every transition on `casino.rewards`.

The service has:

- three event sourced entities: `OfferBook` (per brand), `Reward` (per reward) and `PlayerRewards`
  (per player);
- one key value entity, `CodeDocument` (per brand);
- two workflows, `WalletStep` (per wallet command) and `FreeRounds` (per free-rounds reward);
- one view, `RewardsView`;
- five timers per reward at most, and one recurring timer per brand;
- four consumers in and three out;
- four endpoints.

It also amends four neighbours:

- the wallet (spec 002) on five points (research R6);
- `game-provider` (spec 003) with one service read;
- `players` (spec 008) with FR-034 to FR-036;
- the contract's `casino.rewards`, `casino.players` and `money.transactions` families.

The evaluator, the amount, the offer validation and the free-rounds settlement are pure ports of the
original, held to its own suites. Decisions are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.13.0 (constitution VI; the versions every service here
pins).

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract`:
  - `Routes.player`, `Routes.operator`, `Routes.operatorHolding`, `Routes.service`,
    `Routes.serviceOf`, `Routes.playerId`;
  - `contract.money`, `Personal`;
  - `CasinoPlayers`, `MoneyTransactions`, `CasinoRounds`, `CasinoRewards`, `PlatformAudit`;
  - `port.Recording`, `port.Replay`.
- **Contract changes** (additive, research R11):
  - `casino.rewards`: `source`, `code`, `tag` on every reward family; `reason` on
    `bonus_cancelled`; `amountCents` and `currency` on `bonus_forfeited`; `outcome` on
    `bonus_code_entered`;
  - `casino.players`: four families, `player_duplicate_check_started`,
    `player_duplicate_check_ended`, `player_no_deposit_blocked`, `player_no_deposit_block_cleared`;
  - `money.transactions`: one family, `restricted_funds_closed`.

**Storage**: the service's own Postgres from ankka (journal, durable state, view, workflow and timer
state). No bucket and no secret: every call out is within the brand project by identity, and the
programme's code document is read as a public client (spec 001 FR-016).

**Testing**:

- munit;
- `EventSourcedTestKit` for the three event sourced entities, `KeyValueEntityTestKit` for
  `CodeDocument`, `ConsumerTestKit` for the seven consumers;
- the pure `Evaluator`, `Amounts`, `OfferRules`, `Transitions`, `Settlement` and `Codes`, with the
  original's suites ported case by case;
- `AnkkaTestKit.start` over Postgres, with these fakes and harnesses (the workflows run here, since
  0.13 has no workflow test kit):
  - `FakeWallet` (award, forfeit, cancel, close pending cap, switch, provider credit, restricted
    funds read; each answer scriptable) and `FakeGameProvider` (known, create, get, cancel, spin
    values, game; each answer scriptable) under `localServices`;
  - `FakeProgramme`, an HTTP server answering the code document;
  - `InMemoryBroker` for `casino.players`, `money.transactions`, `casino.rounds`,
    `casino.rewards` and `platform.audit`;
  - `TimerRuntime(200.millis)` and a settable clock;
- `GherkinSuite` binding the `features/rewards/*.feature` files;
- `contract.port.Replay` over `tests/port/rewards/` through `WireTranslation`.

**Target Platform**: every brand project running the casino module, on the self-hosted installation
(kind locally, GKE), as `modules/casino/module.json`'s `rewards` entry declares.

**Project Type**: the work is:

- one ankka service (`services/rewards/`);
- the contract additions;
- five wallet amendments (research R6) in `services/wallet/`;
- one `game-provider` service read (research R6) in `services/game-provider/`;
- FR-034 to FR-036 in `services/players/` (research R9);
- the casino manifest's `rewards` entry, checked;
- `contract/keys.md` loses the `rewards` row.

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-001 | every recording in `tests/port/rewards/` replays with the original's answer, refusal code and money movement; each marked divergence named |
| SC-002 | `ClockSuite`: each of the five timers fires within one minute of its deadline under a settable clock; no scan |
| SC-003 | `RepeatSuite`: every wallet command, fact and timer delivered twice, and a command repeated after the wallet's 1,000 recent keys: one award, one forfeit, one clawback per reward |
| SC-004 | `PublishersSuite`: every `Reward` event maps to at most one fact, each transition once; `attribution`'s `RealMoney` computes bonus cost from the topic in its own suite, unchanged |
| SC-005 | `FreeRoundsSuite`: a provider that never confirms leaves the reward cancelled after 50 polls (30 s each, settable), with no wallet command |
| SC-006 | `OutboundSuite`: the only hosts the service calls are `wallet`, `game-provider` and the programme's document |

**Constraints**:

- four endpoints, one prefix each, at most two path parameters, the ACL per route, the slug a literal
  (research R7);
- the evaluator reads only `PlayerRewards`' state and the wallet's restricted funds read, never
  another service's store (FR-008);
- every wallet command goes through a `WalletStep` workflow, keyed as FR-018 says, retried until the
  wallet answers; the workflow's state is the lifetime record past the wallet's recent keys
  (research R5);
- nothing personal is held: the reward carries the player id; an operator's revoke reason is
  `Personal` under the operator (FR-033);
- money is `Long` minor units with its currency (constitution II).

**Scale/Scope**:

| Item | Count |
|---|---|
| Player routes | 11 at `/v1/casino/brands/<slug>/rewards` (state, free rounds, select, deselect, enter code, preview code, cancel, switch, history, grants read, eligible free rounds) |
| Operator routes | 7 at `/offers` (list, one, create, edit, delete, reorder, spin values); 4 at `/reward-grants` (award, revoke, the list of the brand or of one player, one) |
| Service routes | 2 at `/reward-campaigns` (target, award); 2 at `/rewards` by `withAcl` (grants read for `support-agent`, eligible free rounds for `game-provider`) |
| Event sourced entities | 3 (`OfferBook`, `Reward`, `PlayerRewards`) |
| Key value entities | 1 (`CodeDocument`) |
| Workflows | 2 (`WalletStep`, `FreeRounds`) |
| Views | 1 (`RewardsView`) |
| Timers | per reward: `claim`, `wagering`, `cap`, `pending`, `rounds`; per brand: `codes-<slug>` |
| Consumers | 7 (4 in, 3 out) |
| Feature scenarios bound | every scenario of `features/rewards/` and the six FR-034 to FR-036 scenarios of `features/players/duplicates.feature` |
| Recordings replayed | the spec's *Recordings* (research R2) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings named, parity tests left, divergences declared | Pass. R1 read the original whole: 3,936 lines of `BonusRoutes`, the evaluator, the grant store, six TL functions and their capabilities, the wallet's side. Four planning questions were settled with the user (spec, *Session 2026-11-01 (planning)*). The spec was corrected where it contradicted the original (FR-002, FR-003, FR-005, FR-007, FR-014 to FR-018, FR-022 to FR-026), and *Divergences* below lists every change, with what demonstrates it. `contracts/surface.md` disposes of every route and function. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; keyed money commands; no balance kept | Pass. The wallet awards, forfeits, cancels, closes, switches and credits; `rewards` keeps no balance. Accumulated free-rounds winnings are a counter on the reward, paid once through the wallet. Every command is keyed (FR-018, R5), and `WalletStep`'s state is the lifetime record. The clawback is the wallet's (spec 002 FR-017). |
| III. A brand is a project | keys carry no brand | Pass. Ids are the reward id, the offer id, the player id and the slug, one per project. |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. The wallet and `game-provider` are called over HTTP by identity. Registration, flags and tier arrive on `casino.players`, deposits and the record's fate on `money.transactions`, rounds on `casino.rounds`. Rewards leave on `casino.rewards`. |
| V. Personal data marked, erasure honoured | `Personal`; nothing held | Pass. No personal field is held (FR-033). The revoke reason is `Personal`. `players`' new tokens are lookup tokens, never the email or address (FR-035). |
| VI. Scala by default | | Pass. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1,183 scenarios (2026-11-01). |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (three sessions) and 2026-11-01; four planning questions settled 2026-11-01. |
| Scope: ankka gaps are ankka specs | | Pass: nothing new (research, *For ankka*). |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001 to FR-003, FR-019 | `OfferBook` (event sourced, id = slug): every offer, the code index, the roles, the order | uniqueness of codes and roles, and the order, are rules across offers; dozens of offers per brand | an entity per offer (uniqueness then needs a second writer, research R3) |
| FR-004 to FR-008, FR-011, FR-012 | `PlayerRewards` (event sourced, id = player): registration, duplicate flags, tier, the player's rewards and claims, visibility; runs pure `Evaluator` | one record answers every rule, and two selections at once are serialised by it (edge case) | a view of the player's rewards (seconds behind: two claims could pass) |
| FR-009, FR-010, FR-013 to FR-017, FR-024 | `Reward` (event sourced, id = reward id): the ten states, clocks, snapshot, free-rounds counters | its events are the log and the fact source | the original's grant and event twins (two writes, R1) |
| FR-013, FR-015 to FR-018, FR-024 | `WalletStep` (workflow, id = `<reward>/<step>`) | the atomic claim of a command, its retries, its lifetime record past the wallet's recent keys | the call from the endpoint (no lifetime record, R5) |
| FR-023 | `FreeRounds` (workflow, id = reward id): known, create, poll, cancel | durable 30 s polls, up to 50 | the original's in-memory scheduler (lost on restart, R8) |
| FR-011, FR-015, FR-025 | timers `claim-`, `wagering-`, `cap-`, `pending-`, `rounds-<reward>` | a deadline per reward, within a minute (SC-002) | a daily scan (the original's, up to a day late) |
| FR-021 | `CodeDocument` (key value, id = slug), refreshed by `codes-<slug>` | the last fetched copy serves when the programme cannot be reached | a fetch per code entry |
| FR-012, FR-014 | `MoneyConsumer` (`money.transactions`) | the deposit and the record's fate as facts | — |
| FR-008, FR-034 | `PlayersConsumer` (`casino.players`) | — | — |
| FR-024 | `RoundsConsumer` (`casino.rounds`) | — | — |
| FR-004 | `IndexConsumer` (own `Reward` events → `PlayerRewards`) | the player's index follows each reward | — |
| FR-016, FR-022a, FR-022b, FR-030 | `RewardsView` from `Reward` events | lists, search, pages, the oldest free rounds per game | — |
| FR-027 to FR-029 | `RewardsPublisher`, `CodesPublisher` → `casino.rewards`; `AuditPublisher` → `platform.audit` | as every service here | (R11) |
| FR-031 | `RewardsEndpoint` (player; two service routes by `withAcl`), `OffersEndpoint`, `RewardAdminEndpoint` (operator), `CampaignEndpoint` (`engagement`) | ankka 0.13's route limits | (R7) |

Pure logic with no component: `Evaluator`, `Amounts`, `OfferRules`, `Transitions`, `Settlement`,
`Codes`, `Refusals`, `WireTranslation`, `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/011-rewards/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R14, "For ankka", "Elsewhere"
├── data-model.md        # Phase 1: each entity's state, commands, events and refusals; the workflows; view, timers, consumers
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── surface.md       # the original's reward routes and functions, each moved, dropped or new
│   ├── routes.md        # every route by endpoint; bodies, answers and refusals; the calls out
│   ├── facts.md         # events → families; audit records; what is consumed; the contract additions
│   └── wire.md          # the parity translation
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/facts/Others.scala          # casino.rewards fields
contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala   # four duplicate-check families
contract/src/main/scala/io/eitheror/casino/contract/facts/MoneyTransactions.scala  # restricted_funds_closed
contract/keys.md                                   # rewards leaves "Not yet built"
services/wallet/                                   # R6: W1 to W5, with their suites
services/game-provider/                            # R6: G1, the game's bonus types by service identity
services/players/                                  # R9: FR-034 to FR-036
services/rewards/                                  # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── docker-compose.yml                             # Postgres on 5441
├── README.md
├── src/main/scala/io/eitheror/casino/rewards/
│   ├── offers/         # OfferBook, Model, OfferRules, Codes
│   ├── eligibility/    # Evaluator, Amounts, Decline
│   ├── player/         # PlayerRewards, Model
│   ├── reward/         # Reward, Model, Transitions, Settlement, Clocks
│   ├── steps/          # WalletStep, FreeRounds
│   ├── codes/          # CodeDocument, CodesTimer
│   ├── reads/          # RewardsView
│   ├── consumers/      # PlayersConsumer, MoneyConsumer, RoundsConsumer, IndexConsumer
│   ├── facts/          # Facts (event to fact); RewardsPublisher, CodesPublisher, AuditPublisher
│   ├── clients/        # Wallet, GameProvider, Programme (trait + HTTP)
│   ├── api/            # RewardsEndpoint, OffersEndpoint, RewardAdminEndpoint, CampaignEndpoint; Answers
│   ├── RewardsCodecs.scala
│   ├── Settings.scala
│   └── RewardsService.scala
└── src/test/scala/io/eitheror/casino/rewards/
    ├── offers/, eligibility/, player/, reward/, facts/   # kits and pure suites
    ├── it/                                        # Kit, FakeWallet, FakeGameProvider, FakeProgramme; the integration suites
    ├── port/                                      # WireTranslation, ParitySuite
    └── features/                                  # GherkinSuite bindings, one file per feature
tests/original/rewards-driver/                     # the #730 gates and the routes the suite does not reach (R2)
tests/port/rewards/                                # the recordings, after the run
.github/workflows/ci.yml                           # + the service's filter and job
justfile                                           # + rewards-test, rewards-it, rewards-run
```

**Structure Decision**: one service directory in the shape of `services/payouts/`, a package per
capability. The three outbound seams, `Wallet`, `GameProvider` and `Programme`, are each a trait with
an HTTP implementation and a fake. The service runs locally on 9008, with Postgres on 5441.

## Phase 0: research

Done: [research.md](research.md), R1–R14. Four questions were settled with the user on 2026-11-01
(spec, *Session 2026-11-01 (planning)*):

- codes trimmed and matched in any case (R4);
- free-rounds winnings to real balance through the wallet's provider credit, as the original (R8);
- of the routes no requirement named, the operator's two awards with no offer are carried; `/claim`,
  `/offers/eligible` and the `/promo-code` store and read are dropped (R7);
- the spec corrected to the original wherever it contradicted it (R1).

Verified while planning (2026-11-01):

- the wallet's award routes as built: the award's key is the record's `grantRef`; its concurrency
  limit is the brand's `maxConcurrentAwards`, counting active records only; close pending cap names
  no grant; the cancel computes the clawback from the named record's baseline; `PendingCapClosed`
  and `PrimarySwitched` publish nothing (`services/wallet/`);
- `game-provider` already calls `GET …/rewards/{playerId}/free-rounds/eligible` and tags free rounds
  on `round_settled`; it has no service read of a game's bonus types (`services/game-provider/`);
- `players` keeps `pendingBonusCode` until `bonus_code_entered`, has identity and device tokens only,
  and no duplicate check in progress (`services/players/`);
- the original returns the registration's code at login for the frontend to enter, so rewards needs
  no consumer of `player_registered.bonusCode` (R4);
- `modules/casino/module.json:93-158` declares `rewards` (exposed, identity keys, no grants);
  `contract/keys.md:98-100` lists it as not yet built;
- nothing for rewards is recorded under `tests/port/` (R2).

## Phase 1: design

- [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).
- The spec is amended with this plan (spec, *Session 2026-11-01 (planning)*):
  - FR-001 to FR-003, FR-005, FR-007, FR-014 to FR-018 and FR-022 to FR-027 follow the original;
  - FR-022b is new (the game provider's read);
  - FR-031 admits `support-agent` and any operator on the brand's list;
  - *Changes from the original*, *Recordings* and *Dependencies* are corrected;
  - the features: `lifecycle.feature`, `free-rounds.feature` and `offers.feature` follow the
    original, and the glossary's *cap window* says how it is counted.

### Constitution re-check after design

Unchanged:

- one brand service with its own database;
- it produces to `casino.rewards` and `platform.audit` from consumers;
- it reads `casino.players`, `money.transactions` and `casino.rounds` through consumers;
- it calls the wallet only from `WalletStep`, `game-provider` from `FreeRounds` and the operator's
  spin-value and game reads, and the programme's document from a timer;
- the contract changes are additive.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| Codes are trimmed and matched in any case; uniqueness in any case; an edit may change codes, with creation's checks | settled 2026-11-01 (planning), R4 | `CodesSuite`; `codes.feature`; scenario 21's code steps, marked where the case differs |
| An affiliate's code selects the offer the programme's document names | reconciliation 2, FR-019 | `CodesSuite` against `FakeProgramme`; no recording (new) |
| `/claim`, `/offers/eligible`, the `/promo-code` store and read are not served | settled 2026-11-01 (planning) | scenario 21's `/claim` steps, marked; `surface.md` |
| The deposit number is the wallet's ordinal per currency | reconciliation 2 | `EvaluatorSuite`; scenario 21's deposit-number steps (one currency, unchanged) |
| A confirming deposit expires a selection whose offer is not live | spec edge case | `DepositSuite`; `offers.feature` |
| A decline at a confirming deposit, and a wallet refusal, are recorded on the reward | FR-012, FR-013 | `RewardSuite` |
| The target and award routes are idempotent per player and offer | FR-022 | `CampaignSuite`; `offers.feature` |
| The award route sizes a deposit match from `PlayerRewards`' last deposit, which arrives as a fact | FR-022, constitution IV | `CampaignSuite` |
| Expiry within a minute of its deadline, per reward; no daily scan | SC-002 | `ClockSuite`; scenario 23, marked for the cadence |
| Free-rounds polling is a durable workflow; the original's poll was lost on restart | R8 | `FreeRoundsSuite` |
| A late round after finalisation is paid under its own key | FR-024 | `SettlementSuite`; `free-rounds.feature` |
| Bonus cost on every `bonus_granted` and `free_spins_settled`, and no `notify/bonus-cost` | FR-028 | `PublishersSuite`; attribution's suite |
| Facts replace Customer.io and Kafka fan-outs; `bonus_code_entered`, `free_spins_*`, `grant_expired` are new | FR-027, FR-029 | `PublishersSuite` |
| The revoke `forfeit` uses the wallet's forfeit, so its `money.transactions` fact says `forfeit` where the original's cancel transaction said `cancel` | R5 | `RevokeSuite`; scenario 16's revoke, marked |
| The original's legacy status synonyms, twin shapes and major-unit strings are not read | spec *Changes* | `WireTranslation`; spec 019 writes the opening events |
| Amounts in minor units throughout | FR-032 | `WireTranslation` |
| The player's eligible list is in the brand's order | FR-004 | `offers.feature` |
| Paths move under `/v1/casino/brands/<slug>/…` | spec 001 | `WireTranslation` |

## What the next command needs to know

**Build order:**

1. the contract additions (R11), `just schemas`, `sbt contract/publishLocal` with
   `CASINO_VERSION=0.2.0-011-SNAPSHOT`;
2. the wallet's five amendments with their suites (R6: W1 to W5) and `specs/002-wallet/contracts/`
   updated; `game-provider`'s G1 with its suite and `specs/003-game-provider/contracts/` updated;
3. `players`' FR-034 to FR-036 (R9) with `features/players/duplicates.feature` bound, its facts in
   `FactsSuite`, and `specs/008-players/` amended;
4. the service skeleton: `ankka init`, `build.sbt`, `service.json`, `docker-compose.yml`, `Settings`,
   `RewardsCodecs`, the `just` recipes, the CI filter and job; `contract/keys.md` loses the `rewards`
   row, which `ModuleManifestSuite` enforces once `services/rewards/build.sbt` exists;
5. the pure packages with their suites, written first and failing first:
   - `OfferRules` (`BonusRoutesSpec`'s create and edit cases);
   - `Evaluator` and `Amounts` (`EligibilityEvaluatorSpec`'s cases);
   - `Transitions` (`RewardStatusSpec`, `RewardGrantStoreSpec`);
   - `Settlement` (the `settle-free-spins-round` and `finalize-expired-free-spins-grant` tables);
   - `Codes`; `WireTranslation`;
6. `OfferBook`, `PlayerRewards`, `Reward`, `CodeDocument` with their kits, every row of
   data-model.md's command tables;
7. `Wallet`, `GameProvider`, `Programme` (trait, HTTP, fake);
8. `WalletStep` and `FreeRounds` on the shared kit: every wallet answer, every provider answer, the
   poll limit, a restart mid-poll;
9. the consumers with kits; the timers; `RewardsView`; the publishers (`PublishersSuite`);
10. the four endpoints: offers first (fail-first on `offers.feature`), then select and deposit, codes,
    lifecycle, free rounds, campaigns, the operator's lists;
11. the recording run and the driver (needs the user, R2); `ParitySuite` over every recording, each
    marked divergence named;
12. the integration suites: `ClockSuite` (SC-002), `RepeatSuite` (SC-003), `PublishersSuite`
    (SC-004), `FreeRoundsSuite` (SC-005), `OutboundSuite` (SC-006), `GherkinSuite` over the
    features;
13. a kind deploy beside `wallet`, `players` and `game-provider` with the simulator, a deposit match
    and free rounds driven end to end (quickstart).

**Constraints the tasks must keep:**

- the evaluator's rule order and reasons exactly as `EligibilityEvaluator` (R4's table);
- `Reward` refuses any transition outside the original's table and treats a repeat as a no-op;
- no wallet call outside `WalletStep`; no `game-provider` call on a player's path but launch's read
  and the grant itself;
- every fact keyed by the player id; nothing personal on a fact, a log, a span or a timer name.

**Elsewhere** (research, *Elsewhere*): spec 012's bonus cost counting every award; spec 013's code
document; spec 014's webhook translation; spec 017's tool; spec 019's opening events.
