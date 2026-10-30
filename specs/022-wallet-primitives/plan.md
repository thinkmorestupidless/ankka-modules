# Implementation Plan: Wallet primitives — reservations, settlement, payouts and non-player accounts

**Branch**: `022-wallet-primitives` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/022-wallet-primitives/spec.md`; amends spec 002.

## Summary

The wallet of spec 002 gains four things an exchange needs. A reservation is state of the
`WalletEntity` under the order id, and the pure resolver learns `reserved`, so a bet and a hold draw
on real balance less reserved with the refusal order they have today. A settlement is a workflow
keyed by the trade id whose two steps are keyed commands on the buyer's and the seller's wallet, so a
crash between them resumes and a repeat is answered from the flow. A non-player account is an event
sourced entity of its own, `<kind>/<name>/<currency>`, owned by the service principal that first moved
it, with a floor of zero (pool) or the negative of a credit line its owner sets (clearing). Every move
between a wallet and an account is a workflow whose only refusable write comes first, so nothing
compensates. Eight fact families are added to `money.transactions`, seven keyed by the wallet and one
by the account; balance and transactions gain fields and types; nothing existing changes. Decisions
are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.13.0 (constitution VI).

**Primary Dependencies**: `ankka-sdk`, `ankka-http`, `ankka-auth-oidc`, `ankka-testkit` 0.13.0;
`io.eitheror:casino-contract` 0.2.0-SNAPSHOT (published locally), which gains the eight families,
`AccountFact` and `Routes.serviceNamed`. No new library.

**Storage**: the wallet service's own Postgres from ankka: the journal gains one entity type and two
workflow types; no new view table.

**Testing**: munit, munit-scalacheck; `EventSourcedTestKit` for the two entities; `AnkkaTestKit`
with Postgres (Docker) for the workflows, the restart scenario, the facts and SC-002;
`GherkinSuite` binding `features/wallet/{reservations,settlement,accounts,reads}.feature`
(research R11). `just features` passes before planning: 0 findings in 1,108 scenarios.

**Target Platform**: a brand's ankka project (kind locally, GKE), the wallet being core
(`modules/core/module.json`, unchanged).

**Project Type**: one existing ankka service (`services/wallet/`) plus a contract change.

**Performance Goals**: SC-002 at 500 such commands per second across wallets: reserve or release
in 20 ms median, 100 ms p99; settle or payment in 120 ms median, 300 ms p99. A workflow-backed move
is three journalled steps and a poll at 1 ms (`Flows`); measured, each step costs the runtime about
30 ms, so the workflows have a budget of their own (R15).

**Constraints**: minor units as `Long`; every money command keyed and digested (`KEY_REUSED`); an
entity handler calls nothing; a flow's refusals precede its first write; restricted funds never
reserved, settled or paid; every existing wire body, event, fact and scenario unchanged (SC-001).

**Scale/Scope**: 6 new wallet commands, 6 new wallet events; 1 new entity with 5 commands and 2
queries, 6 events; 2 workflows; 11 new routes and 3 changed read bodies; 8 fact families; 4 feature
files (17 scenarios, 1 un-ignored); 4 suites extended, 3 added. No new recordings (R11).

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | the plan names the recordings it replays and the parity tests it leaves; new behaviour is named under *Changes from the original* | Pass: the spec's *Changes from the original* says everything here is new (added 2026-10-25 after `/speckit-analyze`). SC-001 is held by spec 002's Go tables, TL fixtures and scenarios running unchanged; no wallet recordings exist to replay and none are changed in meaning (`Wallet.reserved` defaults to 0). No parity test is added (R11). |
| II. Money is exact, the wallet is the ledger | `Long` minor units; one keyed command per movement; no other balance anywhere | Pass: `contract.money` at the boundary; R2, R3, R5, R6; an account's balance is in the wallet service, read by nobody else's store |
| III. A brand is a project | keys carry no brand; `{brand}` in the prefix; shared projects by grant only | Pass: ids `player/currency` and `kind/name/currency`; the pool's `clearing` reaches two routes by a grant the predictions module's manifest makes (R9) |
| IV. Commands over HTTP, facts over topics | facts from consumers in stable shapes; no store read across services | Pass: R8; the flows call entities of this service only; `exchange` is told nothing but its own outcomes |
| V. Personal data marked, erasure honoured | reasons under a player are `Personal`; erasure handler present | Pass: an account's adjustment reason is about no person and is not marked; the wallet's handler is unchanged and leaves reservations (R13) |
| VI. Scala by default | | Pass |
| VII. Specs in the domain's words | `just features` passes; scenarios named from the spec | Pass: 0 findings, 1,108 scenarios; the glossary already holds the ten terms (reservation, reserved amount, available amount, settlement, non-player account, pool account, clearing account, credit line, resolution payment, service principal) |
| Workflow: clarify before plan; `just features` before plan | | Pass: clarification 2026-10-25 (#7); features this session |
| Scope: ankka gaps are ankka specs | | Pass: nothing of ankka is rebuilt; the lack of an await on a workflow is carried as spec 002 R17 carried it |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-002: reserve, release | `WalletEntity` commands `reserve`, `release-reservation`; `reservations` in `WalletState` | the check against available and the change are one write on the wallet that holds the money | a reservation entity per order: a bet's check would span two entities (R1) |
| FR-003: available for bets, holds, previews | `funding.Wallet.reserved`; `fundBet` and `withdraw` read `realBalance − reserved` | the refusal order of spec 002 FR-023 lives there and is not restated | a guard in the entity (R1) |
| FR-004: settlement across two wallets | `SettlementWorkflow` (`settlement-flow`, id `<currency>/<tradeId>`): `check`, `debit`, `credit`; wallet commands `settle-debit`, `settle-credit` | two writes made one durable process; refusals before the first write; a crash resumes at `credit` | the route chaining two calls: dies with the request (R3) |
| FR-010 for two-entity commands | the flow's `start` as the claim, its digest and outcome as the record; the entities' recent keys for re-runs | spec 002 R17, unchanged | — (R4) |
| FR-006, FR-007, FR-009: non-player accounts | `AccountEntity` (`account`, id `<kind>/<name>/<currency>`): `claim`, `debit`, `credit`, `adjust`, `set-credit-line`; queries `balance`, `outcome-of` | a ledger with a floor is a check and its change; its own id is its natural key; the owner is the first mover | a wallet with a synthetic player (R5) |
| FR-005, FR-008, the clearing moves | `AccountMoveWorkflow` (`account-move-flow`, id `<account>/<key>`): towards an account `claim`, `debit-wallet`, `credit-account`; from one `check`, `debit-account`, `credit-wallet`; wallet commands `debit-to-account`, `credit-from-account` | the one refusable write first; the ledger port's done / insufficient / refusal is the outcome | the account calling the wallet: handlers call nothing (R6) |
| FR-007: the credit line | `AccountEntity.set-credit-line`, owner only, audited | the owner is the one caller spec 024 will entitle; the value is told to the wallet | the line on every move (R7) |
| FR-011: facts | `Facts.moneyTransactions` extended for the seven wallet families; `AccountAuditPublisher` (consumer over `AccountEntity`) for `account_adjusted` on `money.transactions` and the account's refusals and line changes on `platform.audit` | stable shapes from consumers; one consumer per source | account-keyed families per move: no names in the spec (R8) |
| FR-012: reads | `Balance`, `MajorBalance`, `HoldPreview` gain fields; `TransactionType` and `TransactionRows.of` gain the seven types; `AccountEntity.balance` | additive; read your own write for balances | a view per account (R10) |
| FR-013: routes and audiences | `ServiceEndpoint` gains the routes, the two clearing routes under `withAcl(Routes.serviceNamed(granted))`, the account routes under `Routes.serviceNamed()`; `OperatorEndpoint` gains an account's balance and adjustment | two path parameters at most; no shared prefix between endpoints; the handler needs the caller's name for the owner | a separate accounts endpoint: would share the prefix (R9) |
| SC-002, SC-003 | `ScaleItSuite` (perf tag); `InvariantsSuite`, `AccountInvariantsSuite`; `EveryCommandIdempotentSuite` | the suites that hold spec 002's figures hold these | — (R11) |

Pure logic with no row: the reservation arithmetic in `WalletState`, the floor check in
`AccountState`, the digest of a flow's start.

## Project Structure

### Documentation (this feature)

```text
specs/022-wallet-primitives/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R13
├── data-model.md        # Phase 1: state, events, commands, flows, invariants, transitions
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── routes.md        # the new routes by audience, bodies, refusals, grants
│   └── facts.md         # the eight families, the event → family mapping, the fold
└── tasks.md             # Phase 2 (/speckit-tasks), not created here
```

### Source Code (repository root)

```text
contract/                                   # additions only (contracts/facts.md, contracts/routes.md):
│                                           # eight families, AccountFact, Routes.serviceNamed; schemas
├── access/routes.md                        # the new rows
└── schemas/money.transactions.v1.json      # regenerated by `just schemas`
services/wallet/
├── src/main/scala/io/eitheror/casino/wallet/
│   ├── funding/Types.scala                 # Wallet.reserved; Resolver: fundBet, withdraw read available
│   ├── entity/                             # WalletState.reservations; Reserve, ReleaseReservation,
│   │                                       # SettleDebit, SettleCredit, DebitToAccount, CreditFromAccount;
│   │                                       # six events; Balance/HoldPreview fields; Refusals
│   ├── accounts/                           # AccountEntity, AccountState, AccountEvent, AccountCommands,
│   │                                       # AccountMoveWorkflow
│   ├── settlement/                         # SettlementWorkflow
│   ├── api/                                # ServiceEndpoint: reservations, settlements, accounts;
│   │                                       # OperatorEndpoint: account balance, adjustment; Wire bodies
│   ├── publish/                            # Facts: seven families; AccountAuditPublisher
│   ├── views/Views.scala                   # TransactionType, TransactionRows.of
│   └── WalletService.scala                 # registers AccountEntity, the two flows, the publisher
└── src/test/scala/io/eitheror/casino/wallet/
    ├── entity/                             # EveryCommandIdempotentSuite, InvariantsSuite extended
    ├── accounts/                           # AccountEntitySuite, AccountInvariantsSuite
    ├── features/                           # WalletFeatures: reservations, the balance read;
    │                                       # LedgerFeaturesItSuite (integration): settlement, accounts
    ├── it/                                 # ScaleItSuite SC-002; facts of the eight families
    └── publish/MappingSuite.scala          # the seven wallet families
features/wallet/reads.feature               # the @ignore comes off one scenario
```

**Structure Decision**: everything lands in the existing service, in two new packages for the two
new kinds of thing (accounts, settlement), because the spec amends spec 002 and the wallet service is
the ledger (constitution II). The contract change stays in the root build.

## Phase 0: research

Done: [research.md](research.md), R1–R13. No unknown remains. Two things are read from specs that do
not exist yet and are named as such: the pool's `clearing` service and the grant its manifest makes
(spec 024, spec 023 FR-009), and the entitlement that decides a credit line (spec 024, R7).

## Phase 1: design

- [data-model.md](data-model.md): the wallet's new state, commands, events and refusals; the
  account entity; the two workflows' state and steps; invariants; transitions.
- [contracts/routes.md](contracts/routes.md): the new routes by audience with bodies, replies and
  refusals; the rows for `contract/access/routes.md`; the grant; the ledger port's calls in order.
- [contracts/facts.md](contracts/facts.md): the eight families, the mapping from events, the fold
  that reconstructs an account, the contract additions.
- [quickstart.md](quickstart.md): the features, the unit kits, the integration suites with the
  restart scenario, the perf figures, the local run as the exchange.

### Constitution re-check after design

Unchanged. The design adds one entity, two workflows and one consumer to the wallet service, no
cross-service call from any step, no new topic, no new database, and additive contract changes. The
two places the plan reads beyond the spec are named: the account's ledger reconstructed by a fold
rather than by account-keyed facts per move (R8), and the signature as the operator's token (R12).

## Divergences

There is no original to diverge from. Readings of the spec the next command should know:

| Reading | Where | Why |
|---|---|---|
| `account_debit` and `account_credit` are keyed by the wallet and name what happened to it; the account's balance is a fold | R8, contracts/facts.md; now in FR-011 and SC-004 | the spec lists eight families and every move but the adjustment has a wallet side |
| `unused` on a settlement is the exchange's figure, not computed from the order's price | R3; now in FR-004 | the wallet holds no order prices; the predictions' FR-012 arithmetic is the caller's |
| The credit line is set by `set-credit-line`, 0 until set, creating the account when absent | R7; now in the spec's Assumptions | spec 024 is unwritten |
| A settlement naming one wallet for both sides, or an account kind that is not `pool` or `clearing`, is `INVALID_REQUEST` at the boundary | R3, R9 | spec 001 FR-026's boundary refusal |
| `release-reservation`'s key is `<orderId>/<sequence>` | R2 | one key store, two releases of one order |
| The signature on an operator's adjustment is the staff token | R12; now in FR-009 | spec 002 FR-026 names the actor and nothing more |

## What the next command needs to know

- Order: the contract first (families, `AccountFact`, `Routes.serviceNamed`, `just schemas`, the
  `routes.md` rows); then `Wallet.reserved` and the resolver's two reads with the Go tables still
  green; then the wallet's reservation commands and events with `WalletFeatures` binding
  `reservations.feature` and the un-ignored read; then `AccountEntity` with its kit suites; then the
  two workflows, the wallet's four two-entity commands, the routes, and the integration features
  suite with the restart scenario; then facts, `MappingSuite`, the publisher; then SC-002 and the
  invariants; then a kind deploy of the brand and the exchange's calls from `quickstart.md`.
- `EveryCommandIdempotentSuite` fails the moment a `MoneyCommand` subtype has no case: add the six
  cases with the commands.
- The restart scenario needs the step hook on `SettlementWorkflow` (R11) and a step timeout above
  the test's wait; assert on the lifecycle query, not the domain state.
- The two clearing routes name their grantee from `Layout.BrandGrantsMade`; until the predictions
  module's manifest exists the grantee list is empty and the routes admit the project's services only,
  which is what the integration suite uses (`Caller.Service(project, "clearing")`).
- The eight families are additive; `SchemaDriftSuite` and `FactsSuite` are the check that they are.
- `tests/port/wallet/` does not exist; nothing here waits for it, and nothing here changes what a
  recording of a spec 002 route would replay.
