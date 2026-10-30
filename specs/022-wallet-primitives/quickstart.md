# Quickstart: proving the wallet primitives

Each step names what it proves and the command that proves it. Spec 002's
[quickstart.md](../002-wallet/quickstart.md) is the base: its steps must still pass at every point
below (SC-001).

## Prerequisites

- JDK 21, sbt, Docker (the integration kits run Postgres), `uv` (the features check).
- `sbt contract/publishLocal` after any contract change; `just wallet-test` does it first.

## 1. The features still agree with the spec

```sh
just features
```

Expected: 0 findings; `features/wallet/reads.feature`'s reserved-and-available scenario no longer
carries `@ignore` once the balance read reports both.

## 2. The contract: eight families, additive

```sh
sbt contract/test && just schemas && git diff --stat contract/schemas
```

Expected: `FactsSuite` green for `reservation_placed`, `reservation_released`, `settlement_debit`,
`settlement_credit`, `resolution_payment`, `account_debit`, `account_credit`, `account_adjusted`;
`SchemaDriftSuite` green after `just schemas`; the schema diff adds definitions and removes none.

## 3. The resolver, unchanged where it was, aware of `reserved`

```sh
just service wallet "testOnly io.eitheror.casino.wallet.funding.*"
```

Expected: every Go table green with `reserved = 0`; the new cases: a bet of 500 on real 1,000 with
600 reserved is `INSUFFICIENT_FUNDS`; a hold of 400 is placed and of 500 refused, in FR-023's order.

## 4. The wallet's reservations, with the unit kit

```sh
just service wallet "testOnly io.eitheror.casino.wallet.features.WalletFeatures io.eitheror.casino.wallet.entity.*"
```

Expected: `reservations.feature`'s six scenarios and the balance read green; `EveryCommandIdempotentSuite`
has a case for each of `Reserve`, `ReleaseReservation`, `SettleDebit`, `SettleCredit`,
`DebitToAccount`, `CreditFromAccount` (it fails naming the missing one otherwise); `InvariantsSuite`
holds `reserved == Σ reservations` and `available ≥ 0` over 200 random sequences.

## 5. The account entity

```sh
just service wallet "testOnly io.eitheror.casino.wallet.accounts.*"
```

Expected: a pool account refuses a debit below zero as `INSUFFICIENT_FUNDS`; a clearing account with
a line of 500 takes a debit to −500 and refuses the next as `CREDIT_LINE_REACHED`; the second mover
is `NOT_OWNER`; an adjustment without a reason is `REASON_REQUIRED`; `AccountInvariantsSuite` holds
`balance ≥ floor`.

## 6. Settlement and accounts across entities (Docker)

```sh
just wallet-it
```

Expected (`SettlementFeaturesItSuite` and `AccountsFeaturesItSuite`, both `LedgerFeatures` over the
`Harness`, and `ReservationsItSuite`):

- `settlement.feature`: 650 reserved on ada; trade t1 of 600 → ada real 400, reserved 50, bob 600; with
  `unused = 50` → ada reserved 0, available 400; a repeat answers the first outcome; **the interrupted
  settlement**: the suite holds `credit` through `SettlementWorkflow`'s test hook, sees the debit on
  ada and no credit on bob, stops the service under the held step (`testKit.stopService()`), starts it
  again, and sees the flow `Completed` by the lifecycle query and bob at 600.
- `accounts.feature`: `exchange` splits 100 into `pool/m1/EUR` → the account exists, owned by
  `service:<project>/exchange`, balance 100, ada 900; a payment of 300 for position p1 → ada 1,300, the
  pool 700, a `resolution_payment` fact; a payment the pool cannot cover is `INSUFFICIENT_FUNDS` and
  the pool unchanged; `clearing` moves 500 from `clearing/north/EUR` with a line of 500 → −500, the
  next unit `CREDIT_LINE_REACHED`; the operator's adjustment of +500 with reason "net settled" → 0,
  an `account_adjusted` fact carrying the category and the actor.
- the facts: each wallet-keyed family under the wallet's key, `account_adjusted` under the account's,
  `ce-id` the transaction id, and the fold of `contracts/facts.md` equal to the account's balance.

## 7. SC-002 (Docker, opt in)

```sh
just wallet-perf
```

Expected, beside spec 002's figures: 500 reserve/release/settle/payment commands per second across
50 wallets for 20 s; reserve and release under 20 ms median and 100 ms p99, settle and payment under
120 ms median and 300 ms p99, printed and asserted per kind.

## 8. The running service, locally, as the exchange

The service needs Postgres, a broker for its publishers, an issuer for the operator and player
routes, and a `responsible-gambling` that answers `may-deposit`, since a wallet opens with its first
deposit. Locally every caller is admitted as `local`.

```sh
cd services/wallet && sbt schema && docker compose up -d
docker run -d --name kafka -p 9092:9092 apache/kafka:3.9.0
# any server on :9300 answering POST /v1/casino/brands/goldvibe/rg/may-deposit with {"decision":"allowed"}
ANKKA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ANKKA_AUTH_ISSUERS=staff \
ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
ANKKA_AUTH_STAFF_AUDIENCE=operator-console CASINO_BRAND=goldvibe \
sbt 'set run / javaOptions += "-Dankka.local-services.responsible-gambling=http://127.0.0.1:9300"' run
```

Then (the service listens on 9000; 8080 is the kind installation's ingress):

```sh
B=http://localhost:9000/v1/casino/brands/goldvibe; J='Content-Type: application/json'
curl -X POST $B/wallets/ada/EUR/deposits -H "$J" -d '{"paymentId":"pay-a1","amount":1000,"paymentProvider":"dreamzpay"}'
curl -X POST $B/wallets/bob/EUR/deposits -H "$J" -d '{"paymentId":"pay-b1","amount":1,"paymentProvider":"dreamzpay"}'
curl -X POST $B/wallets/ada/EUR/reservations -H "$J" -d '{"key":"o1","amount":600}'
curl -X POST $B/wallets/ada/EUR/reservations/release -H "$J" -d '{"orderId":"o1","sequence":1,"amount":200}'
curl -X POST $B/settlements -H "$J" -d '{"tradeId":"t1","currency":"EUR","buyer":"ada","orderId":"o1","seller":"bob","amount":300,"unused":100}'
curl -X POST $B/accounts/pool/m1/splits -H "$J" -d '{"key":"s1","currency":"EUR","player":"ada","amount":100}'
curl -X POST $B/accounts/pool/m1/payments -H "$J" -d '{"positionId":"p1","currency":"EUR","player":"ada","amount":100}'
curl "$B/accounts/pool/m1/balance?currency=EUR"
curl $B/wallets/ada/EUR/balance
```

Expected, as run on 2026-10-25: reserved 600 and available 400; after the release 400 and 600;
the settlement leaves ada at real 700 with nothing reserved and bob at 301; the split leaves ada 600
and the pool 100; the payment returns ada to 700 and the pool to 0, owned by `local`; the balance
reads report `reserved` and `available` even at 0. A second `POST` of any of them with the same body
answers the first outcome; with a changed amount, `409 KEY_REUSED`. An account no move has named is
`404 ACCOUNT_NOT_FOUND`.

## 9. Deploy to a brand

As spec 002's step 8: the wallet's image is rebuilt and the brand's manifest applied; the module
manifest is unchanged. The clearing routes admit the project's services until the predictions
module's manifest (spec 023, spec 024) makes the grant to the pool's `clearing`.
