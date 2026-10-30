# wallet

A brand's ledger (specs 002 and 022): every player's money, one wallet per player and currency, and the facts
`money.transactions` and `payments.payments` that every other service reads instead of keeping a
balance of its own (constitution II). An ankka 0.13 service in Scala, one sbt build.

## What it is

| Component | Kind | What it holds or does |
|---|---|---|
| `WalletEntity` | event sourced, id `<player>/<currency>` | real balance as two pools (deposit and converted, spec 025), restricted-funds records with their rule snapshots and clawback baselines, open bets and the last 1,000 settled, holds, reservations by order, the last 30 days of deposits, the last 1,000 keys it has answered |
| `AccountEntity` | event sourced, id `<kind>/<name>/<currency>` | a non-player account (a market's `pool`, a pool's `clearing`): balance, owner, credit line, recent keys (spec 022) |
| `SettlementWorkflow` | workflow, id `<currency>/<tradeId>` | a trade across two wallets: checks the seller's wallet, debits the buyer's reservation, credits the seller; resumes after a crash at the step it reached |
| `AccountMoveWorkflow` | workflow, id `<accountId>/<key>` | money between a wallet and an account: splits, merges, resolution payments, clearing moves, each one keyed command |
| `wallet.funding` | pure Scala | the original's Go resolver ported line for line: funding, wins, rollbacks, losses, forfeits, cancels, holds, adjustments |
| `BrandConfigEntity` | key value, id `brand` | the brand's bet switches (from `rewards`) and turnover terms (from an operator) |
| `PaymentEntity` | event sourced, id = payment id | the outcomes `merchant` reports |
| `DepositWorkflow` | workflow, id `<player>/<currency>/<paymentId>` | a deposit's claim and lifetime record: asks `may-deposit`, then credits the wallet; the deposits route starts and polls it |
| `TransactionsView`, `SumsView`, `LifetimeTotalsView` | keyed, keyed, plain views | the reads that span transactions |
| `MoneyTransactionsPublisher`, `PaymentCapturedPublisher`, `PaymentsPublisher`, `AuditPublisher` | consumers | the contract's facts on `money.transactions`, `payments.payments`, `platform.audit` |
| `AccountTransactionsPublisher`, `AccountAuditPublisher` | consumers | an account's adjustments on `money.transactions` (keyed by the account id) and its audit records on `platform.audit` |
| `ServiceEndpoint`, `OperatorEndpoint`, `PlayerEndpoint` | HTTP | one per audience; [routes](../../specs/002-wallet/contracts/routes.md) |

Every money command carries its caller's key: a repeat within the wallet's most recent 1,000 keys
answers the first outcome and moves nothing, and another command under a recent key is refused as
`KEY_REUSED` (FR-002). The record of a key for the life of the ledger is the caller's workflow under
that key (`DepositWorkflow` here; `game-provider`'s bet workflow for bets), which answers older repeats
itself. Deposits ask `responsible-gambling` first, from the workflow's step, and fail closed; bets ask
nothing here (spec 003 asks `may-bet`).

## The two pools (spec 025)

Real balance is a deposit pool and a converted pool that always sum to it, so affiliate GGR counts
deposited money only: converted bonus money never counts as deposited money. Each event that moves
real balance records its parts (deposit, converted), and the pools after it are on its fact.

| Event | Parts |
|---|---|
| `DepositConfirmed` | all deposit |
| `BetPlaced` | the real leg, converted pool first (after any conversion the bet completes joins the converted pool); an allow-negative shortfall goes to converted |
| `WinCredited` | the bet's ratio, deposit part rounded down; a share whose record is no longer active, or a bet with no parts, all converted |
| `BetRolledBack` | the parts the bet recorded; a cancel's clawback converted first |
| `RestrictedFundsConverted`, a provider credit to real | all converted |
| `HoldPlaced` | the clawback and the cap void converted first, then the paid amount deposit first (`paidParts`, `voidedParts`) |
| `HoldReleased`, `WithdrawalReversed` | the hold's parts back where they came from |
| `AdminAdjusted` (real) | a credit all deposit; a debit deposit first |
| an award's cancel | its clawback converted first |
| `RoundSettledAsLoss`, restricted movements | none |
| `SettlementDebited`, `MovedToAccount` (spec 022) | converted first; a settlement's shortfall below both pools to converted |
| `SettlementCredited`, `MovedFromAccount` (spec 022) | all converted |
| `ReservationPlaced`, `ReservationReleased` (spec 022) | none |

Deposit turnover pending falls by the deposit part of a stake only (FR-003a). `open-migrated` takes
the original's pools; a wallet the original never stamped opens all converted.

The routes answer the pools: `depositPoolBalance` and `convertedPoolBalance` on the balance read
(every audience; major units on the operator's and the player's) and on every accepted money reply,
`fundedDepositAmount` on a bet, `wonDepositAmount` on a win, and `paidDepositAmount`,
`voidedDepositAmount`, `clawbackDepositAmount` on the hold preview. The transactions view's rows
carry `depositPart`, `convertedPart`, `depositPoolAfter` and `convertedPoolAfter`.

## Reservations, settlement and accounts (spec 022)

The exchange (ankka-predictions) holds money for an order with a reservation, settles a trade from
it, and moves money through a market's pool account; a clearing service moves money through a pool's
clearing account under a credit line. Bets, holds, withdrawals and clawbacks see only the available
amount, real balance less reserved. Its calls, in order
([routes](../../specs/022-wallet-primitives/contracts/routes.md)):

| Port call | Route | Key |
|---|---|---|
| reserve(order, amount) | `POST W/reservations` | order id |
| release(order, ordinal, amount) | `POST W/reservations/release` | order id + ordinal |
| settle(trade, buyer order, seller, cost, unused) | `POST /settlements` | trade id |
| split / merge(market, player, sets) | `POST /accounts/pool/{market}/splits`, `…/merges` | the exchange's key |
| pay(market, position, player, amount) | `POST /accounts/pool/{market}/payments` | market + position |

`W` is `/v1/casino/brands/<brand>/wallets/<player>/<currency>`. An account's owner is the service that
sent the first move naming it, even one refused after the claim (`service:<project>/<name>`, from `Routes.serviceNamed`); any other is
`NOT_OWNER`. An operator adjusts an account with a reason, a category and their token
(`POST /v1/casino/backoffice/<brand>/accounts/{kind}/{name}/adjustments`); the fact carries the
category and the actor, the journal the reason. `OUTCOME_PENDING` means call again under the same key.

`SettlementWorkflow.faults` and `AccountMoveWorkflow.faults` are test hooks: a function run at the
top of each step, empty in production, that a suite sets to fail or hold a step (the restart
scenario of `settlement.feature` holds the credit step and stops the service under it).

## Run it locally

```bash
sbt contract/publishLocal                  # from the repository root: the contract this build depends on
cd services/wallet
sbt schema && docker compose up -d         # Postgres with ankka's schema
CASINO_BRAND=goldvibe sbt run              # the service on :9000
```

Then, for example (locally every caller is admitted; in a cluster the caller is the platform's):

```bash
curl -s -X POST localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/deposits \
  -H 'Content-Type: application/json' -d '{"paymentId":"pay-1","amount":10000,"paymentProvider":"dreamzpay"}'
curl -s -X POST localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/bets \
  -H 'Content-Type: application/json' -d '{"key":"tx-1","roundId":"r1","gameId":"slot-a","amount":1000}'
curl -s localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/balance
```

`responsible-gambling` must answer on the route [routes.md](../../specs/002-wallet/contracts/routes.md)
names, or every deposit is `RG_UNAVAILABLE`: set `ankka.local-services.responsible-gambling` to a
running one. The deposit flow's step asks it through ankka's service client, on the step's own
thread, and waits `ankka.service-client.timeout` (500 ms, `application.conf`); a deposit refused as
`RG_UNAVAILABLE` is checked again when it arrives again, since that refusal is not an answer.

## Test it

| Command | What it runs |
|---|---|
| `sbt "testOnly io.eitheror.casino.wallet.funding.*"` | the original's Go table tests, with the pools' (`PoolsSuite`, `PoolsGuestsSuite`) |
| `sbt "testOnly io.eitheror.casino.wallet.entity.*"` | the TL fixtures (230 cases, 44 ignored: below), the rules, SC-006 for every command, invariants over random sequences, the pools' included |
| `sbt "testOnly io.eitheror.casino.wallet.features.*"` | every `features/wallet` scenario |
| `sbt "testOnly io.eitheror.casino.wallet.it.*"` | the service over HTTP against Postgres in Docker: facts, views, audiences, 200 concurrent bets, restarts |
| `sbt "testOnly io.eitheror.casino.wallet.it.ResponsibleGamblingItSuite"` | the deposit flow asking a scripted `responsible-gambling` through ankka's service client: the sums sent, the 500 ms bound, the re-check after `RG_UNAVAILABLE`, a limit refusal staying final |
| `sbt "testOnly io.eitheror.casino.wallet.port.*"` | the parity tests: each recording of the original under `tests/port/wallet/`, translated by `WireTranslation` |
| `WALLET_PERF=1 sbt "testOnly io.eitheror.casino.wallet.it.ScaleItSuite"` | a 10,000-transaction wallet: snapshot size, recovery, SC-003's latency (552 KiB, 241 ms, 2.0/16.4 ms; before the state was bounded, 4.3 MB, 143 ms, 4.4/147 ms), and spec 022's SC-002: reserve, release, settle and payment at 500 a second over 50 wallets (reserve and release under 20/100 ms, settle and payment, the two workflows, under 120/300 ms; measured at 501 a second with a market per wallet: reserve 8.4/41 ms, release 11.5/46 ms, settle 95/278 ms, payment 82/171 ms; on later runs a workflow p99 has reached 634 ms, so the suite can fail on a laptop, research R15) |
| `sbt "testOnly io.eitheror.casino.wallet.accounts.*"` | the account entity's commands and its invariants over random sequences (spec 022) |
| `sbt "testOnly io.eitheror.casino.wallet.features.SettlementFeaturesItSuite io.eitheror.casino.wallet.features.AccountsFeaturesItSuite io.eitheror.casino.wallet.it.ReservationsItSuite"` | spec 022 over HTTP against Postgres: settlement across a restart, pool and clearing accounts with the fold of their facts, reservations in every audience's read |
| `sbt "testOnly io.eitheror.casino.wallet.it.proto.*"` | the bet-workflow prototype spec 003 takes its shape from: latency against the direct command, duplicate starts, an RG refusal |

`just wallet-test` from the root runs all of it after publishing the contract.

The fixtures were converted again for spec 025, from the original as it is now. The cases the
original added for features it built after spec 002's conversion are ignored, each with its reason
(`FixtureRunner.Since002`), as the input to a later amendment of spec 002: two open records where
only the primary funds and is credited, a pending-cap record closing at zero real balance, withdrawal
plans, and deposit turnover as a ledger per deposit. The original's unstamped-wallet cases are
ignored too: every wallet here has its pools.

## Where it came from

The resolver and its tests are `starter-packs/wallet/handlers-go/internal/funding` in the original;
the fixtures are its `starter-packs/wallet/handlers/*.test.tl`, converted once by `tools/tl-fixtures`
into `src/test/resources/fixtures/`. The original's routes are recorded by
`tests/original/wallet-driver` (see `tests/original/README.md`); its `pools_test.go` drives
`features/wallet/pools.feature` scenario for scenario, the starting pools made by the original's own
rules. The original's transactions route does not serve a transaction's parts (only its twin has
them), so replay holds the pools and the bet's and win's deposit parts to the original, and the
fixtures and the Go tables hold the rest.

## The migration's opening command

`open-migrated` (FR-001, spec 019) opens a wallet with what the original holds: real balance, deposit
turnover pending, records with their rule snapshots and, where the original kept one, their clawback
baseline, open bets, holds, deposits so far, the pools (`depositPool`, `convertedPool`; none → all
converted; a pair that does not sum to real balance is refused as `POOLS_INVARIANT`), and any
restricted balance with no record (which refuses the bet path, FR-014). It is a money command under a key, accepted once; a second is
`WALLET_ALREADY_OPEN`.
