# Implementation Plan: The predictions module — markets and an exchange in a brand

**Branch**: `023-predictions-module` | **Date**: 2026-10-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/023-predictions-module/spec.md`.

## Summary

Two new services in a brand's project, `markets` and `exchange`, each composing one of
ankka-predictions' components libraries and supplying what they leave to the consumer. `markets`
supplies the resolution port, checks the markets permission, the brand's categories and currencies
before the library sees a draft, keeps each market's drafted limits, and drives the exchange from
the market's events over HTTP. `exchange` supplies the gate port (players, kyc, responsible
gambling, failing closed) and the ledger port (the wallet's spec 022 routes, one keyed call per
movement), retries a settlement the wallet refused and raises it, adds the reads the library lacks
(orders and trades by player, the price stream), publishes the facts, reconciles daily against the
wallet's facts, and honours erasure. A new module manifest declares both. The taker fee's rule is
the exchange library's (ankka-predictions spec 007); until it is released the module runs with a
rate of 0. Decisions are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3 on ankka 0.14.2 for the two services (the libraries' pin, R1); the
rest of the repository stays on 0.13.0 (constitution VI).

**Primary Dependencies**: `com.thinkmorestupidless %% ankka-predictions-markets-components` and
`…-exchange-components` at v0.1.1 (found in implementation: owners' commands at the book's time and the market's own clock), then v0.2.0 (spec 007); `ankka-sdk`, `ankka-http`,
`ankka-auth-oidc`, `ankka-testkit` 0.14.2; `io.eitheror:casino-contract`, which gains the four
topics, their families and the keys (R9, R15).

**Storage**: each service's own Postgres from ankka. `markets`: the library's entity, view, timer
and consumer, plus `MarketTerms` and `ExchangeDriver`. `exchange`: the library's entity, workflow,
view, consumer and timer, plus `MarketTerms`, `SettlementRetry`, two views, four publishers, the
reconciliation's view and timer.

**Testing**: munit; `GherkinSuite` binding `features/predictions/*.feature` in each service with
the neighbours scripted; `AnkkaTestKit` over Postgres (Docker) for the integration suites; a perf
suite (SC-002); the kind integration suite for SC-003, on purpose only. `just features` passed
before planning: 0 findings in 1,150 scenarios.

**Target Platform**: a brand's ankka project (kind locally, GKE), the module `predictions`.

**Project Type**: two new ankka services, a new module manifest, a contract change, and three
one-line ACL changes and one erasure target in `players`, `kyc` and `responsible-gambling`.

**Performance Goals**: SC-002: an order checked, funded and resting within 50 ms median and 200 ms
p99 at 200 orders per second per brand, gates and wallet included. Three gate calls, a reservation
and the placement workflow's three journalled steps: the gate calls are the budget (R3).

**Constraints**: minor units as `Long`; a share pays 100 minor units, prices 1 to 99 (FR-012);
every money call keyed (constitution II); no matching, position, settlement or lifecycle rule in
the module (SC-001); a gate fails closed; no explicit split or merge offered (FR-016).

**Scale/Scope**: 2 services; on `markets` 1 entity, 1 consumer and about 14 operator routes and
3 player routes; on `exchange` 1 entity, 1 workflow, 3 views, 6 consumers, 1 timer, about 10
player and service routes and 2 operator routes; 4 topics; 7 configuration keys; 22 scenarios in 5
feature files.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings replayed; new behaviour named under *Changes from the original* | Pass: the original has no prediction markets; the spec's *Changes from the original* (added in this plan) says the module is new. No recording exists or is made (R16). |
| II. Money is exact, the wallet is the ledger | `Long` minor units; one keyed call per movement; no balance kept elsewhere | Pass: R4; the exchange's pool is the library's figure, reconciled against the wallet's facts, never a second ledger (R12) |
| III. A brand is a project | keys without brand; shared projects by grant | Pass: ids are the market, the order and the player; no shared project in this version (FR-010 moved to spec 024) |
| IV. Commands over HTTP, facts over topics | | Pass: `markets` drives `exchange` over HTTP (R2); gates and wallet over HTTP; four topics by consumers (R9) |
| V. Personal data marked, erasure honoured | | Pass: the facts carry no personal field (the player id is a pseudonym, spec 001 FR-024, FR-006); `exchange`'s erasure handler cancels resting orders and records the erasure (R13) |
| VI. Scala by default | | Pass; the two services on ankka 0.14.2 with a reason (R1) |
| VII. Specs in the domain's words | | Pass: 0 findings; 39 terms accepted at clarify |
| Workflow: `just features` before plan | | Pass |
| Scope: ankka gaps are ankka specs | | Pass: nothing of ankka is rebuilt; the libraries' gaps are ankka-predictions requests (spec 007, the split funded by a reservation) |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001 | `MarketsComponents`, `ExchangeComponents` registered in each service's `Main`, with `ProjectionRuntime` and `TimerRuntime` | the libraries register nothing; their README's composition | a library-provided `Main` (none exists) |
| FR-002 | `exchange`'s `Gates` (the gate port): `Http` clients to `players`, `kyc`, `responsible-gambling` | the library asks it with the whole order before reserving | a gate workflow step of our own: the library owns the placement workflow (R3) |
| FR-003 | `exchange`'s `Ledger` (the ledger port) on the wallet's routes; `SettlementRetry` workflow, key = the wallet call's key | the library's settlement consumer skips a refusal; the retry keeps the wallet's key | throwing to redeliver: blocks the partition (R5) |
| FR-004 | `markets`' `OperatorEndpoint` checking the `markets` role; `Resolution` (the resolution port) answering `Allow` | the port sees only an operator id; the route sees the principal | a role lookup in the port: it has no principal (R6) |
| FR-005 | `exchange`'s `PlayerEndpoint`: catalogue (from `markets` by HTTP), prices, depth, positions, orders, trades, the SSE stream; views `orders-by-owner`, `trades-by-owner` | the library has no view of a player's orders or trades and no stream | reading the entity's whole book per request (R10) |
| FR-006 | the contract's four topics; `MarketFactsPublisher`, `OrderFactsPublisher`, `TradeFactsPublisher`, `PriceFactsPublisher` | one consumer per topic, stable shapes | the library's events on a topic: leaks the journal (constitution IV) |
| FR-007 | `MarketMirror`, `PriceObservations` consumers, behind `reasoning-service` | optional by value | — (R14) |
| FR-008 | `exchange`'s erasure route; `players`' erasure targets | the existing pattern | — (R13) |
| FR-009, R15 | `modules/predictions/module.json`, `contract/keys.md` | the module model | — |
| FR-011 | `Reconciliation` view over `money.transactions` and a daily timed action | sums by market and family against the exchange's | a query of the wallet's store: constitution IV (R12) |
| FR-012, FR-014, FR-015 | `markets`' `MarketTerms` entity; `ExchangeDriver` consumer calling `exchange`'s open with unit 100, the limits and the rate | the definition has no limits; the open needs them | adding limits to the library's definition: a library change for a consumer's concern (R7) |
| FR-013 | `markets`' draft and change routes checking the values | before the library sees it | — (R7) |
| FR-016 | the ledger port answering `charge` and `pay` with a refusal; no route | nothing a player does opens the unpaid-sets gap | a pre-checked route (declined at planning) |
| SC-002 | `PlacementPerfSuite` | | |
| SC-003 | the kind integration suite | real gates and wallet | |

## Project Structure

### Documentation (this feature)

```text
specs/023-predictions-module/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── routes.md      # the two services' routes
│   ├── ports.md       # the ports onto the brand's services, call by call
│   └── facts.md       # the four topics
└── tasks.md           # /speckit-tasks
```

### Source Code (repository root)

```text
services/markets/            # ankka init; ankka 0.14.2
├── build.sbt, service.json, README.md
└── src/{main,test}/scala/io/eitheror/casino/markets/
    ├── Main.scala, Settings.scala
    ├── terms/               # MarketTerms entity
    ├── drive/               # ExchangeDriver consumer, the exchange client
    ├── api/                 # OperatorEndpoint, PlayerEndpoint (catalogue), ServiceEndpoint
    ├── facts/               # MarketFactsPublisher, MarketMirror
    └── test: features/, it/
services/exchange/           # ankka init; ankka 0.14.2
└── src/{main,test}/scala/io/eitheror/casino/exchange/
    ├── Main.scala, Settings.scala
    ├── ports/               # Gates, Ledger, Http and the neighbours' clients
    ├── retry/               # SettlementRetry workflow
    ├── terms/               # MarketTerms entity
    ├── reads/               # orders-by-owner, trades-by-owner, the stream
    ├── facts/               # the three publishers, PriceObservations
    ├── reconcile/           # the view and the timed action
    ├── api/                 # PlayerEndpoint, ServiceEndpoint, OperatorEndpoint
    └── test: features/, it/, perf/
modules/predictions/module.json
contract/src/main/scala/io/eitheror/casino/contract/facts/{Markets,Exchange}.scala
contract/keys.md
services/{players,kyc,responsible-gambling}/   # the ACL changes and the erasure target
brands/_realm/eitheror-operators.json          # the markets role
```

**Structure Decision**: two services, as the spec's FR-001 says and the repository's one service
per build requires.

## Phases

1. **Foundation**: the contract on ankka 0.14.2 (R1); the topics, families and keys; the realm
   role; the three ACL changes.
2. **US3, the lifecycle** (`markets`): the service, the permission, the values checks, the
   terms, the facts on `markets.events`.
3. **US1, orders** (`exchange`): the service, the gate port, the ledger port's reserve and
   release, the open route the driver calls; `ExchangeDriver` in `markets`.
4. **US2, money**: the ledger port's settle, split, merge and payout; `SettlementRetry`; the
   reconciliation.
5. **US4, reads**: the views, the player routes, the stream.
6. **US5, facts and the mirror**: the three exchange publishers; the mirror behind its value.
7. **The module**: the manifest, erasure, SC-002, SC-003, the README.
8. **The fee**, once ankka-predictions v0.2.0 is released: the rate at open and the fee's move.

## Complexity Tracking

Empty.

## Re-check after Phase 1

Unchanged: the design adds no store read across services, no unkeyed money call, and no rule of
the libraries'. The two prerequisites outside this repository (ankka-predictions spec 007 and the
contract on 0.14.2) are named in R17 with what builds without them.
