# Quickstart: proving the wallet

How to show the wallet does what the spec says, in the order the tasks built it. Contracts are in
[contracts/](contracts/), the model in [data-model.md](data-model.md). Figures marked *measured* are
from the run of 2026-10-09 on a developer laptop (Apple silicon, Docker Desktop).

## Prerequisites

- JDK 21+, sbt, `uv`, Go 1.22+, Docker, the `ankka` CLI and the `ankka@ankka` plugin.
- The contract published locally with this feature's additions: `sbt contract/publishLocal` from the
  repository root (version `0.2.0-SNAPSHOT`).
- For recordings only: the original checked out beside this repository and running locally as
  `tests/original/README.md` says.

## 1. The features still agree with the spec

```bash
just features          # 0 findings in 1018 scenarios and 20 specs (measured)
```

## 2. The resolver, with the original's own tests

```bash
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.funding.*"
```

204 cases, each named `<go file>/<case>`, all passing (measured); the SC-004 cases (`500000 on 50000`,
`5000000 on 500000`) credit the exact amount, and a product past 64 bits is refused, never wrapped.

## 3. The entity, with the TL fixtures and the rules

```bash
just tl-fixtures       # once: writes services/wallet/src/test/resources/fixtures/*.json (174 cases)
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.entity.*"
```

174 fixture cases: 171 pass, 3 ignored with their reason in their name (two exercise award-bonus's
record-first guard, which the rebuild's award does not need; one a cancel of an approved request,
which is `payouts`'). Then the rules: the deposit sums at their boundaries, the hold preview, the
personal reason, every event through its serializer, the original's refusal texts; SC-006 for every
money command type (a repeat answers the first outcome with no event, a changed command under the same
key is `KEY_REUSED`), and the data model's invariants over 200 random sequences of every command.

## 4. Every scenario of the living features

```bash
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.features.*"
```

75 tests: every scenario of `features/wallet/*.feature`, each outline row its own (measured).

## 5. The running service, locally

```bash
cd services/wallet
sbt schema && docker compose up -d
CASINO_BRAND=goldvibe sbt run               # :9000
```

```bash
curl -s -X POST localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/deposits \
  -H 'Content-Type: application/json' -d '{"paymentId":"pay-1","amount":10000,"paymentProvider":"dreamzpay"}'
curl -s -X POST localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/bets \
  -H 'Content-Type: application/json' -d '{"key":"tx-1","roundId":"r1","gameId":"slot-a","amount":1000}'
curl -s -X POST localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/wins \
  -H 'Content-Type: application/json' -d '{"key":"tx-2","roundId":"r1","amount":2500}'
curl -s localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/balance
```

`realBalance` 10000, 9000, 11500. Without a `responsible-gambling` to ask, the deposit is
`RG_UNAVAILABLE` (503) from its workflow's `check-rg` step, which is the point: set
`ankka.local-services.responsible-gambling`. The bet asks nothing (its `may-bet` is `game-provider`'s,
spec 003). The integration suite runs this sequence with a fake that allows (section 6).

## 6. The integration suites

```bash
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.it.*"     # or: just wallet-it
WALLET_PERF=1 sbt "testOnly io.eitheror.casino.wallet.it.ScaleItSuite"  # or: just wallet-perf
```

Over Postgres in Docker, through the routes:

- the deposit, bet and win of section 5, with `deposit_confirmed` (ordinal 1), `bet_placed` and
  `win_credited` on `money.transactions` and `payment.captured` on `payments.payments`;
- the deposit runs as `DepositWorkflow` (`player/currency/paymentId`): a silent `responsible-gambling`
  refuses it `RG_UNAVAILABLE` from the workflow and the wallet is never called; an over-limit deposit
  credits nothing and its repeat is answered from the workflow with the same refusal; two deposits
  under one payment id at once credit once;
- SC-002: 200 concurrent bets of 1000 on a wallet of 100,000: exactly 100 accepted, the rest
  `INSUFFICIENT_FUNDS`, final balance exact (measured);
- awards, conversions and the cancel's clawback (700, from the baseline) on the topic; holds,
  releases, confirmations and reversals; the operator's adjustment, whose fact carries the category and
  never the text; another brand's operator and a player refused the backoffice; a player's own balance
  and transactions in major units; the views answering after a restart; a payment outcome once;
- SC-005's local form, measured with `WALLET_PERF=1`: a 10,000-transaction wallet keeps its last
  1,000 keys and settled bets, state 552 KiB, first answer after a restart 241 ms (before the bound:
  4.3 MB and 143 ms, research R4);
- SC-003, measured: 1,000 bets on that wallet through the route, median 2.0 ms, 99th percentile
  16.4 ms (targets 50 ms and 250 ms; before the bound 4.4 ms and 147 ms).

### 6a. The caller's workflow, measured

```bash
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.it.proto.*"     # WALLET_PERF=1 for 10,000
```

`it.proto.BetWorkflowItSuite` is the shape spec 003's bet path takes (research R17): a workflow per
provider transaction, `start` the claim, steps `check-rg` and `debit`, the caller polling its state
at 1 ms. Measured 2026-10-10 through the component client, 1,000 bets each way: on a wallet of
1,000 transactions, direct `place-bet` median 0.4 ms and p99 8.7 ms, the workflow 7.0 ms and
38.1 ms; on a wallet of 10,000, direct 0.5 ms and 77.3 ms, the workflow 9.7 ms and 121.8 ms. Fifty
pairs of duplicate starts at once: 50 bets, RG asked 50 times, each pair answered identically; an RG
refusal never reaches the wallet and its repeat is the same refusal. On the bounded wallet of 10,000,
the workflow path is 7.1 ms and 49.1 ms against the direct command's 0.4 and 12.8 ms.

## 7. Parity against the original

Record (once per change of the original; `tests/original/README.md`, "Recording the wallet directly"):

```bash
cd tests && go test -tags e2e ./original/wallet-driver/... -count=1 \
  -args -wallet-url http://localhost:8081      # the original's wallet-service (just dev-wallet-service)
# twice, then merge into tests/port/wallet/ with tests/original/merge
```

Replay:

```bash
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.port.*"
```

Each `tests/port/wallet/*.json` is translated by `WireTranslation` (contracts/wire.md) and replayed
with `contract.port.Replay`; a failure names the recording and the first differing JSON path. The 50
end-to-end recordings that reach the wallet through `game-provider`, `rewards`, `payouts` or `players`
are listed as ignored with what blocks them. **Not yet run**: the recordings need the original running
locally (tasks.md T074); until then the suite reports one ignored test saying so.

## 8. Deploy to a brand

```bash
cd services/wallet && sbt Docker/publishLocal    # wallet:0.1.0-SNAPSHOT, 668 MB (measured)
just brands validate brands/goldvibe/manifest.json
just brands apply brands/goldvibe/manifest.json   # against ANKKA_URL: creates the brand's project
```

**Not yet run**: applying creates a project on an installation, and a project's id is never reusable;
it is done on purpose, not as part of this feature's build (tasks.md T080).
