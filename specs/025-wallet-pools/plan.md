# Implementation Plan: Wallet pools

**Branch**: `025-wallet-pools` | **Date**: 2026-10-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/025-wallet-pools/spec.md`

## Summary

The wallet's real balance becomes two pools, deposit and converted, kept by the resolver exactly
where the original's Go keeps them: `funding.Wallet` gains the pools, every plan that moves real
balance gains the parts it decided, and a new pure `funding.Pools` is the port of the original's
`pools/apply.go` and `poolstep.go`. The entity copies parts and pools onto its events and state;
the publisher puts them on every `money.transactions` family (two pools after on all, the parts on
those that move real balance); the views and the replies carry them under the original's names;
`open-migrated` carries the migration's pools. `attribution` then counts a fact's deposit part
instead of its real delta (spec 012, on PR #11's branch). Parity is the original's own evidence:
13 new pool fixtures, its `pools_test.go` and the split assertions of its Go tables, plus pool
scenarios for the wallet driver, recorded with spec 002's pending run. Decisions are in
[research.md](research.md); the original moved on in four other areas since spec 002's fixtures
were converted, and those cases are listed as ignored, not ported (R6).

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.13.0 (the wallet's build); Go for the driver's new
scenarios (the original's toolchain, spec 002 R13).

**Primary Dependencies**: as spec 002: `ankka-sdk`, `ankka-http`, `ankka-testkit`;
`io.eitheror:casino-contract` 0.2.0-SNAPSHOT (published locally under a unique `CASINO_VERSION`).

**Storage**: the wallet's own Postgres; the events gain fields with defaults; the state gains two
`Long`s. No new table.

**Testing**: munit; `EventSourcedTestKit` for the fixtures and invariants; the integration kit for
facts, views and routes; `GherkinSuite` binding `features/wallet/pools.feature`; `SchemaDriftSuite`
in the contract; the driver's Go tests against the stub.

**Target Platform**: a brand's ankka project (kind locally).

**Project Type**: a change to one service (`services/wallet/`), one contract change, the fixture
converter re-run, the driver extended, and one change to `services/attribution` on PR #11's branch.

**Performance Goals**: spec 002 SC-003 unchanged (SC-005 here): the pool step is a few integer
operations per command; `ScaleItSuite` re-run.

**Constraints**: `Long` minor units; the converted pool may be negative, the deposit pool never;
deposit + converted = real after every event (checked in the fold); nothing the wallet refuses,
pays or publishes today changes (FR-014); new contract fields default to 0.

**Scale/Scope**: 1 new pure object (~120 lines of Go to port), 2 state fields, ~14 event fields,
2 model fields, 2 trait fields and ~24 family fields in the contract, 13 fixtures, 25 feature
scenarios, ~20 driver scenarios, 1 change in `attribution`'s `RealMoney`.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings and parity tests named; divergences named | Pass: R6 (13 fixtures), R7 (driver scenarios; the five e2e tests blocked by spec 003), the Go tables; divergences in the spec's *Changes from the original* (no unstamped wallet, no ratio fallback, the backfill not ported, minor-unit wire) and R11's finding flagged for recording |
| II. Money exact, the wallet the ledger | `Long`; parts sum to the amount; no balance elsewhere | Pass: `Pools` in integer minor units, one division without overflow (R2); `attribution` reads parts from facts, keeps no balance (R5) |
| III. A brand is a project | no brand in keys; routes keep `{brand}` | Pass: nothing changes |
| IV. Commands over HTTP, facts over topics | stable, additive fact shapes | Pass: R4, fields with defaults; spec 001 FR-013 |
| V. Personal data marked | | Pass: amounts and parts are not personal (spec 001 FR-024) |
| VI. Scala by default | | Pass; Go only in the driver, as spec 002 reasoned |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1,133 scenarios (2026-10-28) |
| Workflow: `just features` before plan; clarify | | Pass: the spec's Clarifications session 2026-10-28; `/speckit-clarify` not run (no open marker; the bdd check runs with it if wanted) |
| Scope: ankka gaps are ankka specs | | Pass: none needed |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-002: the pools and their arithmetic | `funding.Pools` (pure): `Pools`, `Parts`, `credit`, `debit` (converted-first or deposit-first), `bet` (with the shortfall), `winSplit`, `restore`, `mulDiv` | the original's one implementation of its rule table, ported as the resolver was | a step in the entity: a second place for a money rule, and the Go tables could not port (R1) |
| FR-003 to FR-010: every movement's parts | `funding.Resolver` plans carry `parts` and `newPools`; `Wallet` carries `pools` | the plan is the decision; the entity records it | — |
| FR-003a: turnover by the deposit part | `Resolver.placeBet` reduces turnover by `min(parts.deposit, pending)` | the original's `AttributableStake` (R9) | — |
| FR-001, FR-011: state and opening | `WalletState.depositPool/convertedPool`; `OpenMigrated.depositPool/convertedPool`; `WalletOpenedFromMigration` | the fold sets, never computes (R3) | — |
| FR-012: facts | `Facts.moneyTransactions` copies parts and pools; contract fields (R4) | — | — |
| FR-013: reads and replies | `Outcome`, `MoneyReply`, `Balance`, `TransactionRow` gain the fields; player/operator render major strings | the original's names (R10) | — |
| FR-012 (attribution's side) | `attribution.money.RealMoney.counted` reads `depositPartCents` | spec 012 FR-030 as amended (R5) | the cursor's delta of `depositPoolAfter`: the same number with a cursor it no longer needs for the amount |
| FR-014: nothing else changes | `FixtureSuite`s, `PortSuite`, the Go tables unchanged and green | — | — |

## Project Structure

### Documentation (this feature)

```text
specs/025-wallet-pools/
├── plan.md              # this file
├── research.md          # R1–R13
├── data-model.md        # pools, parts, the fields on state, events, outcome, rows, facts
├── quickstart.md        # proving it, in build order
├── contracts/
│   ├── facts.md         # the contract additions, family by family
│   ├── routes.md        # the replies' new fields by route
│   └── wire.md          # the driver's observations and the parity translation of the new fields
└── tasks.md             # /speckit-tasks, not created here
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/facts/MoneyTransactions.scala   # trait + families (facts.md)
contract/schemas/money.transactions.v1.json                                         # regenerated
contract/src/test/.../facts/                                                         # decode-without-fields test
services/wallet/src/main/scala/io/eitheror/casino/wallet/
├── funding/Pools.scala                  # new: the port of apply.go + poolstep.go
├── funding/Types.scala                  # Wallet.pools; Parts; plans' parts/newPools
├── funding/Resolver.scala               # the step in each plan; turnover by the deposit part
├── entity/Model.scala                   # Bet.parts; WithdrawalRequest.paidParts/voidedParts; Outcome, Balance fields
├── entity/WalletState.scala             # depositPool, convertedPool; fundingWallet carries them; invariant
├── entity/WalletEvent.scala             # after-pools on every money event; parts where real moves
├── entity/Commands.scala                # OpenMigrated.depositPool/convertedPool
├── entity/WalletEntity.scala            # copies plan parts/pools onto events; opening check
├── publish/Facts.scala                  # parts and pools onto the facts
├── views/Views.scala                    # TransactionRow fields
├── wire/Wire.scala                      # MoneyReply fields; major rendering on player/operator routes
└── api/*Endpoint.scala                  # balance replies
services/wallet/src/test/scala/io/eitheror/casino/wallet/
├── funding/PoolsSuite.scala             # pools_test.go ported; tables' split assertions
├── entity/fixtures/*                    # the 13 pool cases; ignored maps for the other new cases (R6)
├── entity/InvariantsSuite.scala         # the pool invariants
├── features/                            # pools.feature bindings
├── it/                                  # facts, views, routes, open-migrated with/without pools
└── port/WireTranslation.scala           # the new fields (wire.md)
services/wallet/src/test/resources/fixtures/*.json   # reconverted (tools/tl-fixtures)
tools/tl-fixtures/                        # the new twin and transaction properties
tests/original/wallet-driver/pools_test.go           # one scenario per pools.feature scenario
features/wallet/pools.feature             # written by /speckit-bdd-features
services/attribution/.../money/RealMoney.scala       # on PR #11's branch: counted reads the deposit part
```

**Structure Decision**: nothing new beyond one file in `wallet.funding`; the change follows the
original's shape through the service, so each layer gains fields rather than a component.

## Phase 0: research

Done: [research.md](research.md), R1–R13. Two findings for Trevor beyond this spec: the original's
fixtures grew by about 55 non-pool cases since spec 002's conversion (R6: a later amendment of
spec 002) and spec 002's T074 recording run has not been made (R7: this spec's driver scenarios
join it).

## Phase 1: design

- [data-model.md](data-model.md): `Pools` and `Parts`; the fields on state, bet, request, events,
  outcome, rows and facts; the invariants; the opening.
- [contracts/](contracts/): the contract additions by family; the replies by route; the driver's
  observations and the wire translation.
- [quickstart.md](quickstart.md): contract, resolver, fixtures, features, integration, the driver
  and the kind deploy, in order.

### Constitution re-check after design

Unchanged. The design adds no component, no call, no topic; the contract change is additive with
defaults; the pool step is the original's code in the resolver's plans; what differs from the
original is named in the spec and R6, R7, R11 name what the recordings must settle.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| No unstamped wallet; no pool-ratio fallback for a win on a bet without parts | spec Clarifications 2026-10-28; every wallet opens with its pools | the 4 "unstamped" and "no recorded split" fixtures listed as ignored with that reason |
| The backfill, its report and the stamp route are not ported | the migration opens each wallet with the original's stamped pools or all converted (FR-011) | `open-migrated` tests with and without pools |
| Parts and pools in minor units on the service routes | spec 002's wire convention | `WireTranslation` converts the driver's major strings |
| Turnover reduced by the deposit part against one pending amount, not the original's per-record ledger | FR-003a; the ledger is a later amendment (R9) | the stamped-wallet turnover fixture asserts the pending amount only |
| A reversal restores the paid parts of what it credits; whether the original credits the paid or the requested amount is R11's open check | spec 002's reversal | the driver's reversal scenario, when recorded |

## What the next command needs to know

- Order: contract fields and `just schemas` first; `funding.Pools` with `PoolsSuite`; `Wallet`,
  plans and `Resolver`; state, events, commands, entity; the converter and the 13 fixtures (with
  the ignored maps for the other new cases); facts, views, wire, endpoints; the features binding;
  the integration suites; the driver's scenarios; the kind deploy; then `attribution` on PR #11's
  branch.
- Reconverting the fixtures re-writes every `fixtures/*.json`; the suites' existing cases must
  stay green and every new non-pool case must be in an `ignored` map with its group's reason (R6),
  or `FixtureSuite`'s first test fails.
- `CASINO_VERSION` must be unique while other worktrees publish the contract (memory).
- dev-kind's goldvibe project is re-created when this deploys (R3); its journals predate the pools.
- The driver's pool scenarios are recorded in spec 002's T074 run; until then `PortSuite` stays
  ignored and the parity evidence is the fixtures and the Go tables.
