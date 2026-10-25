# ingest

The shared payments project's merchant-facing edge (spec 006): a sub-brand's server, or a brand's
frontend proxy, opens a cashier session under its cashier key, submits it by the ticket and reads the
payment. `ingest` verifies the key, checks the request, answers a repeat once, slows a sub-brand that
floods, forwards to `merchant` as the key's sub-brand, and keeps one record per request for operators.
It holds no customer field, address or key (SC-006).

## Routes

One endpoint per prefix; `specs/006-ingest/contracts/routes.md` has every answer.

| Prefix | Audience | Routes |
|---|---|---|
| `/v1/cashier` | public: the key admits the create, the ticket the rest | `POST /sessions`, `POST /sessions/{ticket}/submit`, `POST /sessions/{ticket}/abandon` |
| `/v1/payments` | public: the creating sub-brand's key or the session's `cashierTicket` | `GET /{paymentId}` |
| `/v1/ingest` | payments operators (`Routes.paymentsOperator`) | `GET /sub-brands/{s}/requests`, `GET` and `PUT /sub-brands/{s}/rate`, `GET /lost-answers`, `POST /lost-answers/{recordId}/seen` |

## Components

| Component | Kind | Id | What |
|---|---|---|---|
| `Key` | key value | key id | a key as merchant told of it; never revived once deactivated |
| `KeyIndex` | key value | the key's digest | the request path's point read (SC-003) |
| `RateWindow` | key value | sub-brand | the one-minute window and an operator's raise (FR-015) |
| `Request` | key value | sub-brand/request id | the request id's claim and answer, for 24 h (FR-010) |
| `Ownership` | key value | `ticket/…`, `payment/…` | who a ticket and a payment belong to (FR-009) |
| `RequestRecord` | key value | record id | one per request, written once; a lost answer (FR-014, FR-016) |
| `RequestsView` | view | | the operator's reads (FR-017) |
| `KeyFacts` | consumer | | `payments.merchants` → `Key`, `KeyIndex` |
| `RequestAudit` | consumer | | a key or rate refusal → `platform.audit` (FR-019) |

At start `KeyRefresh` reads `merchant`'s `GET /v1/cashier/keys` once; the facts keep the index after.

## Settings

| Variable | Default | |
|---|---|---|
| `MERCHANT_SERVICE` | `merchant` | the service the commands go to |
| `ANSWER_BOUND` | `10s` | how long a caller waits for `merchant` |
| `RATE_LIMIT` | `600` | requests a minute per sub-brand |
| `BODY_BOUND` | `65536` | bytes |
| `REQUEST_ID_BOUND` | `128` | characters |
| `REQUEST_ID_RETENTION` | `24h` | |
| `RECORD_RETENTION` | `90d` | records and ownerships |

`application.conf` keeps the service client's timeout at 30 s (the lost-answer window) and sets the
views' poll to 1 s (SC-007).

## Running and testing

- `just ingest-test`: every suite. Unit and entity suites need nothing; `it.*`, `port.*` and
  `features.*` need Docker (the integration kit's Postgres). `just ingest-it` runs those three alone.
- `just ingest-run`: the service on 9002 against Postgres on 5434 (`sbt schema && docker compose up -d`
  here first) and `merchant` on 9000. With no `merchant` running, `sbt "Test/runMain
  io.eitheror.casino.ingest.it.fakes"` serves `FakeMerchant` on 9000 with the key `mk_local_goldvibe` for
  sub-brand `goldvibe-local`. A local run has no broker: the key index is what the start refresh read, so
  start `merchant` (or the fakes) first. Run on 2026-10-22 against the fakes: quickstart §5's four
  requests answered `200`, `200` (the same body), `409 REQUEST_ID_REUSED`, `401 UNAUTHENTICATED`.
  Run on 2026-10-25 against the real `merchant` (004 quickstart §7, dev-kind's Keycloak forwarded,
  ankka 0.13.0): the key made through `merchant`'s operator routes, the start refresh read `1 keys`, the
  same four answers, and `GET /v1/ingest/sub-brands/{id}/requests` listed the three records (served,
  repeated, `request_id_reused`) with no customer field.
- On kind (quickstart §6), 2026-10-25: the three payments services Ready on 0.13.0, the same four
  answers through the gateway, the key in neither service's log, and the player's address reaching the
  provider from `X-Forwarded-For` (research R11).

## What is verified where

| What | Where |
|---|---|
| the original's answers | `port.ParitySuite` over `tests/port/ingest`: three recordings from spec 004's run and 25 by `tests/original/ingest-driver` (2026-10-24, `659c2d6b`); `port.CoverageSuite` |
| the features | `features.CashierFeatures`, `features.RequestsFeatures` |
| SC-003, SC-004 (the key step, a deactivation) | `it.KeysSuite`, which prints its measurements |
| SC-005 (rate) | `it.RateSuite` |
| SC-006 (no personal data) | `it.LeakSuite` |
| SC-007 (an operator finds a request) | `it.OperatorSuite`, `it.SmokeSuite` |
| a restart | `it.DurabilitySuite` |
