# wallet

A brand's ledger (spec 002): every player's money, one wallet per player and currency, and the facts
`money.transactions` and `payments.payments` that every other service reads instead of keeping a
balance of its own (constitution II). An ankka 0.13 service in Scala, one sbt build.

## What it is

| Component | Kind | What it holds or does |
|---|---|---|
| `WalletEntity` | event sourced, id `<player>/<currency>` | real balance, restricted-funds records with their rule snapshots and clawback baselines, open bets and the last 1,000 settled, holds, the last 30 days of deposits, the last 1,000 keys it has answered |
| `wallet.funding` | pure Scala | the original's Go resolver ported line for line: funding, wins, rollbacks, losses, forfeits, cancels, holds, adjustments |
| `BrandConfigEntity` | key value, id `brand` | the brand's bet switches (from `rewards`) and turnover terms (from an operator) |
| `PaymentEntity` | event sourced, id = payment id | the outcomes `merchant` reports |
| `DepositWorkflow` | workflow, id `<player>/<currency>/<paymentId>` | a deposit's claim and lifetime record: asks `may-deposit`, then credits the wallet; the deposits route starts and polls it |
| `TransactionsView`, `SumsView`, `LifetimeTotalsView` | keyed, keyed, plain views | the reads that span transactions |
| `MoneyTransactionsPublisher`, `PaymentCapturedPublisher`, `PaymentsPublisher`, `AuditPublisher` | consumers | the contract's facts on `money.transactions`, `payments.payments`, `platform.audit` |
| `ServiceEndpoint`, `OperatorEndpoint`, `PlayerEndpoint` | HTTP | one per audience; [routes](../../specs/002-wallet/contracts/routes.md) |

Every money command carries its caller's key: a repeat within the wallet's most recent 1,000 keys
answers the first outcome and moves nothing, and another command under a recent key is refused as
`KEY_REUSED` (FR-002). The record of a key for the life of the ledger is the caller's workflow under
that key (`DepositWorkflow` here; `game-provider`'s bet workflow for bets), which answers older repeats
itself. Deposits ask `responsible-gambling` first, from the workflow's step, and fail closed; bets ask
nothing here (spec 003 asks `may-bet`).

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
| `sbt "testOnly io.eitheror.casino.wallet.funding.*"` | the original's Go table tests, 204 cases |
| `sbt "testOnly io.eitheror.casino.wallet.entity.*"` | the 174 TL fixtures, the rules, SC-006 for every command, invariants over random sequences |
| `sbt "testOnly io.eitheror.casino.wallet.features.*"` | every `features/wallet` scenario |
| `sbt "testOnly io.eitheror.casino.wallet.it.*"` | the service over HTTP against Postgres in Docker: facts, views, audiences, 200 concurrent bets, restarts |
| `sbt "testOnly io.eitheror.casino.wallet.it.ResponsibleGamblingItSuite"` | the deposit flow asking a scripted `responsible-gambling` through ankka's service client: the sums sent, the 500 ms bound, the re-check after `RG_UNAVAILABLE`, a limit refusal staying final |
| `sbt "testOnly io.eitheror.casino.wallet.port.*"` | the parity tests: each recording of the original under `tests/port/wallet/`, translated by `WireTranslation` |
| `WALLET_PERF=1 sbt "testOnly io.eitheror.casino.wallet.it.ScaleItSuite"` | a 10,000-transaction wallet: snapshot size, recovery, SC-003's latency (552 KiB, 241 ms, 2.0/16.4 ms; before the state was bounded, 4.3 MB, 143 ms, 4.4/147 ms) |
| `sbt "testOnly io.eitheror.casino.wallet.it.proto.*"` | the bet-workflow prototype spec 003 takes its shape from: latency against the direct command, duplicate starts, an RG refusal |

`just wallet-test` from the root runs all of it after publishing the contract.

## Where it came from

The resolver and its tests are `starter-packs/wallet/handlers-go/internal/funding` in the original;
the fixtures are its `starter-packs/wallet/handlers/*.test.tl`, converted once by `tools/tl-fixtures`
into `src/test/resources/fixtures/`. The original's routes are recorded by
`tests/original/wallet-driver` (see `tests/original/README.md`).

## The migration's opening command

`open-migrated` (FR-001, spec 019) opens a wallet with what the original holds: real balance, deposit
turnover pending, records with their rule snapshots and, where the original kept one, their clawback
baseline, open bets, holds, deposits so far, and any restricted balance with no record (which refuses
the bet path, FR-014). It is a money command under a key, accepted once; a second is
`WALLET_ALREADY_OPEN`.
