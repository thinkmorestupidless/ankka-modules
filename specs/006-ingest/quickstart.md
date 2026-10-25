# Quickstart: proving ingest

In build order. Every command runs from the repository root unless it says otherwise; the ones that
need Docker say so. Expected outcomes are what [plan.md](plan.md)'s *Divergences* and the contracts
say; nothing here repeats them.

## 1. The features and the glossary

```bash
just features
```

Expected: 0 findings. The three scenarios research correction 2 added to
`features/payments/cashier.feature` are counted; the spec's acceptance lists name them.

## 2. The recordings (needs the original's KinD stack; [contracts/recording.md](contracts/recording.md))

```bash
cp tests/port/merchant/TestScenario1{2_OnboardingGate3_FullChain,3_DreamzPay_FullChain,4_CasinoDeposit_PlayerToWallet}.json tests/port/ingest/
cd tests/original/ingest-driver && go test -tags e2e -merchant-url http://localhost:8086 -substrate-url http://localhost:9000 ./...
just modules recordings check
```

Expected: the three copied recordings unchanged; one new recording per driver scenario, every one
`pass` on the original; the check reports every file well formed. The recording run is a task of its
own and the parity suite waits on it.

## 3. Unit suites

```bash
just ingest-test   # sbt "ingest/testOnly io.eitheror.casino.ingest.*Suite" without the it and port packages
```

Expected: `FieldsSuite` (every FR-007 row), `DigestsSuite`, `ShapeSuite` (the public body into 004's
command, `clientIp` from the first forwarded-for entry), `SettingsSuite` (each variable refused by
name), `BoundSuite`; the entity kits for `Key`, `RateWindow` (the minute rolls, the raise holds),
`Request` (every row of the command table in data-model.md), `Ownership`, `RequestRecord`; the
consumer kits for `KeyFacts` and `AuditFacts` (one record per key or rate refusal, none otherwise,
never a key in it).

## 4. Integration, features and parity (Docker)

```bash
just ingest-it     # AnkkaTestKit over Postgres with FakeMerchant, InMemoryBroker and a settable clock
```

Expected, by suite:

- `CashierSuite`: the four public routes end to end against `FakeMerchant`'s script.
- `RepeatSuite` (SC-002): 10 concurrent creates under one request id make one `FakeMerchant` call and
  return one ticket.
- `UnansweredSuite` (FR-013, FR-014): `FakeMerchant` delayed past the kit's `ANSWER_BOUND` (500 ms);
  the caller gets `503 TRY_AGAIN`; a repeat is forwarded again; the late answer is listed under
  `GET /v1/ingest/lost-answers` and marked seen.
- `RateSuite` (FR-015, SC-005): 700 creates from one sub-brand in one clock minute, 600 served, 100
  `429` with `Retry-After`; another sub-brand's create served in the same minute; a raise to 6000
  serves 700.
- `KeysSuite` (FR-004, SC-003, SC-004): a deactivation fact refuses the key on the next request;
  10,000 keys over 1,000 sub-brands, the key step under 5 ms at p99, `FakeMerchant` asked nothing after
  start.
- `LeakSuite` (SC-006): every customer field, a known key and a known address sent; every table and
  the captured log hold none of them.
- `OperatorSuite` (FR-017): a staff token with the payments role lists a sub-brand's requests by time
  and by request id; one without the role is `403`.
- `GherkinSuite` over `cashier.feature` and `requests.feature`: every scenario bound and green.
- `ParitySuite` over `tests/port/ingest/`: every recording replayed, the marked divergences named.

## 5. Locally, beside `merchant` (spec 004's §7 run)

```bash
just ingest-run    # ANKKA_HTTP_PORT=9002, -Dankka.local-services.merchant=http://127.0.0.1:9000
```

`sbt schema && docker compose up -d` in `services/ingest` first. A local run has no broker, so the key
index is what the start refresh read from `merchant`: make the key first, then start `ingest`. With a
sub-brand and a key made through `merchant`'s backoffice routes (004 quickstart §7, which needs dev-kind's
Keycloak) and the raw key in `KEY`; or, without Keycloak, `sbt "Test/runMain io.eitheror.casino.ingest.it.fakes"`
in `services/ingest` in place of `merchant`, and `KEY=mk_local_goldvibe`:

```bash
curl -s -X POST localhost:9002/v1/cashier/sessions -H "merchantKey: $KEY" -H 'Content-Type: application/json' \
  -d '{"amountCents":5000,"currency":"EUR","customerId":"goldvibe:p1","country":"GB","redirectUrl":"https://example.test/return","transactionId":"t-1"}'
```

Expected: `200` with `session_token` and `available_terminals`; the same command again: the same body;
with `amountCents` 6000: `409 REQUEST_ID_REUSED`; without the header: `401 UNAUTHENTICATED`. Inspect
with `ankka local console`: `RequestRecord` rows for each, `Request` `payments-sub-brand/t-1` answered,
`RateWindow` count 3.

## 6. An installation (kind)

```bash
just modules validate shared/payments/manifest.json --installation dev-kind
CASINO_KEYCLOAK_CA=~/.ankka/local-ca.crt just modules apply shared/payments/manifest.json \
  --installation dev-kind --secrets <a file outside the repository with the Keycloak admin entries>
ankka services list --project payments
```

The current kubeconfig context must be `kind-ankka` (never production). The key comes from `merchant`'s
operator routes (`POST /v1/merchants/{subBrand}/keys`) under a token from the casino Keycloak's
`eitheror` realm, after a credential and a deployed tree for the sub-brand. The realm is imported on a
brand's apply; with only `payments` applied, import `brands/_realm/eitheror-operators.json` by hand.

Expected: `ingest` running beside `merchant` and `psp-gateway`, exposed at
`ingest-payments.<base domain>`, its env from the manifest's `ingest` entry (research R13), the staff
issuer rendered by the modules tool. A create through the gateway with a key `merchant` issued answers
as §5 did; `ankka services logs ingest` shows the start refresh's key count and no key value. The
frontend's cashier base URL is then this hostname (out of scope here; research finding 3).
