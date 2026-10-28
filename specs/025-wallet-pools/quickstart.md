# Quickstart: proving the wallet pools

In build order. Everything runs from the repository root unless said otherwise; the wallet's own
commands are in `services/wallet/README.md`.

## 1. The contract

```bash
export CASINO_VERSION=0.2.0-pools-SNAPSHOT     # unique while other worktrees publish (memory)
just schemas && sbt contract/test               # SchemaDriftSuite green after regeneration
sbt contract/publishLocal
```

Expected: `money.transactions.v1.json` gains `deposit_pool_after`, `converted_pool_after` on every
family and the parts on the families `contracts/facts.md` lists; a message without the new fields
still decodes (the decode test).

## 2. The pure rules

```bash
cd services/wallet
sbt "testOnly io.eitheror.casino.wallet.funding.*"
```

Expected: `PoolsSuite` (the original's `pools_test.go`, case for case) and the Go tables with their
split assertions, all green; `CorrectionDebit` with allow-negative shows the shortfall on the
converted pool.

## 3. The fixtures

```bash
just tl-fixtures ../eitheror-platform            # reconverts every handler (R6)
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.entity.*"
```

Expected: the 13 pool cases green; every other new case reported `[ignored: …]` with its group's
reason; `FixtureSuite`'s first test (every ignored name is a case of the file) green;
`InvariantsSuite` holds deposit + converted = real and deposit ≥ 0 over random sequences.

## 4. The features

```bash
just features                                    # 0 findings
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.features.*"
```

Expected: every scenario of `features/wallet/pools.feature` bound and green, the rest of
`features/wallet` unchanged.

## 5. The service

```bash
cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.it.*"
```

Expected: `MappingSuite` and `WalletItSuite` show parts and pools on the published facts; the transactions view rows
carry them; the balance routes of all three audiences carry the pools (minor on service, major on
player and operator); `open-migrated` with pools opens with them and without a matching sum is
`POOLS_INVARIANT`; `ScaleItSuite` (`WALLET_PERF=1`) within spec 002 SC-003.

Run it and look:

```bash
sbt schema && docker compose up -d && CASINO_BRAND=goldvibe sbt run
curl -s -X POST localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/deposits \
  -H 'Content-Type: application/json' -d '{"paymentId":"pay-1","amount":5000,"paymentProvider":"dreamzpay"}'
curl -s -X POST localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/bets \
  -H 'Content-Type: application/json' -d '{"key":"tx-1","roundId":"r1","gameId":"slot-a","amount":1000}'
curl -s localhost:9000/v1/casino/brands/goldvibe/wallets/p1/EUR/balance
```

Expected: the bet answers `fundedDepositAmount: 1000` (no converted money yet) and the balance
`depositPoolBalance: 4000, convertedPoolBalance: 0`.

## 6. The driver (against a local original; constitution I)

```bash
cd tests && go test ./original/wallet-driver/...          # the driver against its stub, every scenario present
```

The recording run is spec 002's T074 (`tests/original/README.md`, *Recording the wallet
directly*), made once for both specs; the pool scenarios read the pools from the original's balance
route and the parts from its bet and win responses (`contracts/wire.md`). After the
merge, `sbt "testOnly io.eitheror.casino.wallet.port.*"` replays them.

Found when run (2026-10-28): the original's transactions route does not serve a row's parts, so
no recording holds the rows' parts to the original; the fixtures and the Go tables do. The five
pool recordings are in `Blocked` until T074 records them. The cap-void hold scenario is skipped in
the driver: its state is not reachable through the original's routes.

## 7. On kind

```bash
just modules apply goldvibe --installation dev-kind   # after re-creating the brand project (R3)
```

Expected: a deposit, a bet and a balance read through the gateway as in step 5; `money.transactions`
facts carry the new fields (the contract check of the modules tool); `attribution`, once PR #11 is
merged and its `RealMoney` reads the deposit part, reports a converted-funded round as 0 GGR.

Run 2026-10-28 on kind-ankka (platform 0.14.2, rebuilt; goldvibe re-created):

- The calls were made to the wallet's service routes from inside its own pod with the pod's
  service certificate (`kubectl exec … curl --cert /var/run/secrets/ankka/service/tls.crt …`), not
  through the gateway: the service routes take a service identity, not a token. Deposit 5000:
  `depositPoolBalance: 5000, convertedPoolBalance: 0`; bet 1000: `fundedDepositAmount: 1000`,
  `depositPoolBalance: 4000`; balance: `depositPoolBalance: 4000, convertedPoolBalance: 0`.
- The facts: ankka started the wallet's `money-transactions-publisher` against the project's
  declared `money.transactions.v1`, which carries `deposit_part_cents`, `converted_part_cents` and
  the hold's `voided_*_cents`; it refused `players`, whose image was built on the contract before
  this spec (a different digest). Every service publishing or reading `money.transactions` must be
  rebuilt on this contract before it runs beside the wallet.
- A deposit needs `responsible-gambling` running (spec 010: the wallet fails closed,
  `RG_UNAVAILABLE`). The wallet started before it had an image kept answering `RG_UNAVAILABLE` after
  it was Ready, until the wallet was restarted.
- The modules tool's apply stopped at 300 s on a lapsed control plane token (`401` on
  `GET /services/goldvibe`), after the core's services were applied and before `game-provider`.
- Not run: `attribution` (PR #11 is not merged; section 8).

## 8. `attribution` (PR #11's branch)

```bash
just service attribution test
```

Expected: `RealMoney` per family reads `depositPartCents`; spec 012's GGR scenarios green on facts
that carry parts; the one kept divergence of its parity run is gone from its README.

Run 2026-10-28 in PR #11's worktree, uncommitted: PR #11's contract has `Routes.machine` and this
spec's does not, so `attribution` was built on a combined contract published locally as
`0.2.0-pools012-SNAPSHOT` (PR #11's `contract/` diff applied on this branch's). 327 of 328 green;
`OperatorSuite`'s one failure passes alone (order-dependent). Both converted-money parity
recordings now agree with the original: the original's GGR fact of 0 for a converted-only day is
expected as no fact (`ExpectNone`). Merge order: this spec first, then PR #11 rebased on it; PR #11
does not compile in CI before then.
