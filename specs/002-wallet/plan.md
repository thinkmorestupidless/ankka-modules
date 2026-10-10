# Implementation Plan: Wallet

**Branch**: `002-wallet` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-wallet/spec.md`

## Summary

The wallet becomes one event sourced entity per player and currency whose command handlers are the
funding resolver, ported from Go to a pure Scala module, so a bet's check and its ledger change are
one step. Restricted funds records, open rounds, withdrawal requests, the last 30 days of deposits and
the most recent 1,000 command keys are the entity's state; the brand's bet switches and turnover terms
are one key value entity; payment outcomes are a small entity keyed by payment id; a deposit is a
workflow per payment id whose steps ask `responsible-gambling` and credit the wallet, and whose state
is the payment id's record for the life of the ledger. Three consumers publish
`money.transactions`, `payments.payments` and `platform.audit` in the contract's shapes; three views
answer transactions, lifetime totals and sums. The endpoints are thin, one per audience; the bet
path calls nothing (`may-bet` is spec 003's workflow step). Parity is held by the original's own
evidence: its TL fixtures and Go tables ported case for case, and recordings of its wallet routes
replayed through one documented wire translation. Decisions are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.11.0 (constitution VI: the money path). Go 1.22+ only for
the wallet driver that records the original (it lives in the original's test toolchain, as spec 001
R10 reasoned).

**Primary Dependencies**: `com.thinkmorestupidless` `ankka-sdk`, `ankka-http`, `ankka-auth-oidc`,
`ankka-testkit`; `io.eitheror:casino-contract` (published locally) for facts, money, route classes,
personal markers and the recording replayer. ankka 040 (granted routes), 042 (`Personal`, erasure)
and 043 (retention) against their `-impl` branches until released, as spec 001 did.

**Storage**: the service's own CNPG Postgres from ankka (journal, snapshots, key value store, view
tables). Nothing shared (constitution VI).

**Testing**: munit; `EventSourcedTestKit` and `KeyValueEntityTestKit` for the entities; the
integration testkit with Postgres for views, publishers, durability, concurrency and parity;
`GherkinSuite("features")` binding `features/wallet/*.feature`; `contract.port.Replay` for
recordings. Research R13 and R14.

**Target Platform**: a brand's ankka project on the self-hosted installation (kind locally, GKE).

**Project Type**: one ankka service (`services/wallet/`) plus a contract change and two tools.

**Performance Goals**: SC-003: a provider callback under 50 ms median, 250 ms p99 on a wallet with
10,000 transactions, with no call to any service. SC-002: 200 concurrent bets on one wallet, exact.

**Constraints**: minor units as `Long` end to end; every money command keyed and idempotent, with a
digest (`KEY_REUSED`); an entity handler calls nothing; views are never the input to a refusal; wire
bodies keep the original's field names; refusal codes and messages verbatim.

**Scale/Scope**: 22 commands and queries, 19 event types, 3 entities, 1 workflow, 3 views, 4
consumers, 3 endpoints; 173 TL fixture cases, 10 Go table files, the wallet recordings; the wallet's
state is bounded by recent activity, not history (R4, R17).

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings and the parity tests it leaves | Pass: R13 (wallet recordings via the driver, TL fixtures, Go tables); e2e recordings that enter through other services are listed as blocked by their specs, not dropped. Divergences: wire translation (R11), `KEY_REUSED`, repeated deposit returns the first outcome, RG fail-closed on deposits, all named in the spec's *Changes from the original*. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; one keyed, idempotent command per movement; no other balance anywhere | Pass: `contract.money` types throughout; R3, R4; every other service reads facts or calls |
| III. A brand is a project | Keys carry no brand; `{brand}` kept and refused when wrong; shared projects by grant only | Pass: id `player/currency`; `Routes.brandSegment`; the two granted routes are exactly `Layout.BrandGrantsMade`'s |
| IV. Commands over HTTP, facts over topics | Facts from consumers in stable shapes; no store read across services | Pass: R9; the `may-deposit` check is an HTTP call from a workflow step (R6); the lifetime record of a key is the caller's workflow, reached by nothing but its own commands (R17); views are the wallet's own |
| V. Personal data marked, erasure honoured | The adjustment reason is `Personal` under the player; erasure handler present | Pass: R12; the fact carries a category, not the text (`contracts/facts.md`) |
| VI. Scala by default | | Pass; the Go driver's reason is given |
| VII. Specs in the domain's words | `just features` passes; scenarios named from the spec | Pass: 0 findings, 1018 scenarios (this session) |
| Workflow: clarify before plan; `just features` before plan | | Pass (both this session) |
| Scope: ankka gaps are ankka specs | | Pass: 040/042/043 built against branches, reported as "platform lacks" by the brands tool; nothing rebuilt here |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-005–FR-027: every money rule on one wallet | `WalletEntity`, event sourced, id `player/currency`, records embedded | the only place a check and its change are atomic; history is the ledger | a record entity per award: a bet across records becomes a workflow, eventually consistent between legs (R1) |
| FR-002: idempotent by key, `KEY_REUSED` | the most recent 1,000 keys as `KeyedOutcomes` with digest in `WalletEntity` state; `CommandRefused` events for refusals under a key; the lifetime record is the caller's workflow under the key (spec 003's bet workflow, `DepositWorkflow` here) | the recent keys must change with the ledger, to answer a step that re-runs; the workflow's `start` is the claim and its state the record | a transaction entity per key filled by a consumer and acknowledged back to the wallet: the acknowledgement protocol and staleness bound a workflow's journal gives for free (R4, R17); every key in the wallet for its life: the state grows with history (4.3 MB at 10,000 transactions) |
| FR-004, FR-004a: brand switches and turnover terms | `BrandConfigEntity`, key value, id `brand` | latest value only; nobody reacts to how it changed | event sourced: no reader of its history; its audit is `platform.audit`'s (R7) |
| FR-022: `may-deposit`, then the credit | `DepositWorkflow`, id `player/currency/paymentId`: `start` the claim, steps `check-rg` (`services("responsible-gambling")`, 500 ms, fail closed) and `credit`; the route starts and polls it | the check and the credit are two writes; the workflow makes them one durable process a repeat cannot run twice; an entity handler never calls out | the endpoint calling RG then the wallet: a retry after a timeout cannot be told from a new request, and asks RG again for money already moved (R6, R17); a lock view in the wallet fed by RG facts: not fail-closed, lags |
| FR-007a (moved): `may-bet` | none here: a step of spec 003's bet workflow before its wallet call | the wallet is the ledger and nothing else on the bet path (FR-004, SC-003) | the bet endpoint asking RG: the same two-call problem, on the hot path |
| FR-022: deposit sums for `may-deposit` | `WalletEntity` state: last 30 days of deposits, pruned by event time | exact and current, as FR-030 and spec 010 FR-012 demand | a view over `deposit_confirmed`: lags with no bound (R5) |
| FR-022a: payment outcomes and `payments.payments` | `PaymentEntity`, event sourced, id = payment id; `payment.captured` from the wallet's `DepositConfirmed` | keyed by the payment as spec 001 FR-012 requires; a deposit and its fact are one write | outcomes as wallet events: wrong key, journal grows with facts the wallet does not own (R8) |
| FR-031: facts on three topics | `MoneyTransactionsPublisher`, `PaymentCapturedPublisher`, `PaymentsPublisher`, `AuditPublisher`, consumers | stable shapes from a consumer, never the journal; one topic per consumer, so `payment.captured` has its own | — |
| FR-028: balance and status | `WalletEntity` queries | read your own write after a bet | — |
| FR-029, FR-030 (reporting sums): transactions, totals, sums | `TransactionsView`, `SumsView` (keyed views), `LifetimeTotalsView` | questions over many transactions, eventually consistent; a row per transaction needs a keyed view | fan-out of entity queries: the caller holds no ids (R10) |
| FR-023a: hold preview | `WalletEntity` query `preview-hold` running the hold's computation | exactness of "what the hold would do" needs the entity's current state | computing it in `payouts`: duplicates the rules (spec 007 Q) |
| FR-032: audiences | three endpoints, one per prefix: service (in-project, with the two granted routes and erasure under `withAcl`), operator, player | two endpoints may not share a prefix; `withAcl` gives a route its own ACL | one endpoint per audience at separate prefixes: the granted routes are spec 001's paths, under the service prefix |
| FR-033: erasure | the 042 handler, confirming | — | — |
| Expiry of a record | none: `rewards`' timer calls forfeit (spec 011) | — | a timer per record here: would make the wallet decide a reward rule |

Pure logic with no row: `wallet.funding` (the resolver), `wallet.wire` (rendering), the clawback
formula, the digest.

## Project Structure

### Documentation (this feature)

```text
specs/002-wallet/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R15
├── data-model.md        # Phase 1: state, events, commands, views, facts, transitions
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── routes.md        # every route by audience, command, body
│   ├── wire.md          # the parity translation table and the refusal texts
│   ├── facts.md         # event → family mapping; the contract additions
│   └── surface.md       # the original's 56 routes and handlers, each with a disposition
└── tasks.md             # Phase 2 (/speckit-tasks), not created here
```

### Source Code (repository root)

```text
contract/                                   # additions only (contracts/facts.md): two families, two
│                                           # fields, KeyedOutcomes with digest; schemas regenerated
services/wallet/                            # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── src/main/scala/io/eitheror/casino/wallet/
│   ├── funding/                            # the resolver, pure: PlaceBet, CreditWin, Rollback, Loss,
│   │                                       # Withdraw, Adjust, Close, Rules, Legs, Clawback
│   ├── entity/                             # WalletEntity, WalletState, WalletEvent, Commands, Outcome
│   ├── config/                             # BrandConfigEntity, defaults
│   ├── payments/                           # PaymentEntity
│   ├── views/                              # TransactionsView, LifetimeTotalsView, SumsView
│   ├── publish/                            # MoneyTransactionsPublisher, PaymentsPublisher, AuditPublisher
│   ├── api/                                # ServiceEndpoint, OperatorEndpoint, PlayerEndpoint,
│   │                                       # ResponsibleGambling (client, may-deposit), Erasure
│   ├── deposits/                           # DepositWorkflow (claim, check-rg, credit)
│   ├── wire/                               # request/response bodies, decimal rendering, refusals
│   └── WalletService.scala                 # registration
└── src/test/scala/io/eitheror/casino/wallet/
    ├── funding/                            # ported Go tables, SC-004 overflow
    ├── entity/fixtures/                    # one suite per TL handler over resources/fixtures/*.json
    ├── entity/                             # RefusalsSuite, IdempotencySuite, InvariantsSuite
    ├── it/                                 # views, publishers, restartService, SC-002, fake RG
    ├── it/proto/                           # BetWorkflow: the measured prototype of spec 003's shape (R17)
    ├── port/                               # WireTranslation, one test per tests/port/wallet/*.json
    └── features/                           # GherkinSuite("features") step bindings for features/wallet
tools/tl-fixtures/                          # the one-off .test.tl → JSON converter (Scala)
tests/original/wallet-driver/               # Go: drives the original's wallet routes through the recorder
tests/port/wallet/                          # the recordings (committed after the recording run)
brands/goldvibe/manifest.json               # gains the wallet service entry
```

**Structure Decision**: the service is one directory as ankka's tooling expects, with the resolver
as a pure package so the original's tests run without a runtime; the contract changes stay in the
root build; the fixture converter and the driver are tools beside the service, not in it, because
they run once and against the original.

## Phase 0: research

Done: [research.md](research.md), R1–R15. No unknown remains. One correction to carry into the
contract: `KeyedOutcomes` gains a digest (R4), safe now because no journal exists.

## Phase 1: design

- [data-model.md](data-model.md): the three entities' state and events, the commands and their
  refusals in order, the views' rows, the invariants, the transitions.
- [contracts/](contracts/): routes by audience; the wire translation and refusal texts; the fact
  mapping and contract additions; the original's surface with dispositions.
- [quickstart.md](quickstart.md): resolver tests, fixtures, refusals, the local run, the integration
  suite, recording and replay, deploy.

### Constitution re-check after design

Unchanged. The design adds one service with its own database, no cross-service store read, one
in-project HTTP call (`may-deposit`, from a workflow step), and three topics the contract already names.
The contract additions are additive. The one place the design is stricter than the original
(`KEY_REUSED`) and the two places it differs (repeated deposit outcome, wire translation) are named
in the spec and here.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| Paths and amount encoding differ from the original's wallet API | spec 001 fixed the paths; FR-003 fixed minor units on service routes | every wallet recording, through `WireTranslation` |
| A seen key with a different payload is refused | FR-002 (clarification 2026-10-09) | `IdempotencySuite`; no recording (the original has no such check) |
| A repeated deposit returns the first outcome, not current balances | FR-002 over FR-022's old text | `DepositIdempotent` passes either way; a new scenario in `deposits.feature` |
| Deposits fail closed on `responsible-gambling`, from the deposit workflow; bets do the same in `game-provider` (spec 003) | FR-022; spec 003 FR-012 | the fake RG's silence in `wallet.it` (deposits); `it.proto.BetWorkflowItSuite` (the bet shape) |
| A deposit whose outcome is not known within 2 s is answered `503 OUTCOME_PENDING` | ankka 0.11 has no await on a workflow (R17) | `wallet.it`, the deposit route |
| Transactions and sums lag their source | views | parity tests read balance from the entity and retry the view |
| Turnover terms and the clawback are not inputs | FR-004a, FR-015a | a recording's inputs become the test's Givens (`wire.md`) |
| Another brand's path is answered `404` with the runtime's body, not `wrong_brand` | an ankka route takes two path parameters; the brand is in the prefix (R16) | `WalletItSuite`, "another brand's path is not this project's route" |
| A refusal carries a `message` beside the original's `error` | the rebuild says why; the original's body was the code alone | `WireTranslation` keeps the original's fields only |
| A round with several bets: a win or loss without a bet key settles every open bet of the round | the original settled one bet named by `bet_tx_id`; the rebuild takes `betKey` for that, and the recordings (BigRoundMultiple*) settle which default the provider relies on | credit-win fixtures 15 to 17, settle-loss 12 |

## What the next command needs to know

- Order: contract additions and `just schemas` first (everything compiles against them); then
  `wallet.funding` with the Go tables; then the entity with the fixture converter and suites; then
  config, payments, the deposit workflow, views, publishers; then endpoints and the fake RG; then the driver, recordings
  and the parity suite; then the manifest entry and a kind deploy.
- Parity tests are written before the components they test and must fail first (ankka-port §7).
- The e2e recordings that enter through `game-provider`, `rewards` and `payouts` are listed in
  `wallet.port` as blocked by specs 003, 007 and 011; SC-001 completes when those services exist.
- `contracts/wire.md`'s messages are the plan's reading of `WalletRoutes.scala`; the driver's
  recordings confirm or correct them, and the recording wins.
- The fact for an adjustment carries a reason category, never the operator's text
  (`contracts/facts.md`); if Sheldon wants the text on the fact, it becomes a `Personal` field in the
  contract first.
- 040/042/043: the granted routes name their grantees from `Layout` until 040 ships; `Personal` is a
  marker until 042; retention is the brands tool's until 043. None blocks the parity suite.
