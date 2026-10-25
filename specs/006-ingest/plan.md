# Implementation Plan: ingest

**Branch**: `006-ingest` | **Date**: 2026-10-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/006-ingest/spec.md`

## Summary

`ingest` is the third Scala service of the shared `payments` project and its sub-brand-facing edge. It
serves the original's four public cashier routes verbatim: create a cashier session, submit it, abandon
it, read a payment. On each it verifies the cashier key against an index fed by `merchant`'s key facts,
rate-limits the sub-brand, checks the fields, makes the create idempotent under the sub-brand's request
id, forwards the command to `merchant` by service identity and returns the answer unchanged. Every
request is a record an operator reads; a key or rate refusal is an audit record; an answer that arrives
after the caller was told to try again is a lost answer for the operator.

It owns no payment and no session. Five key value entities, two views, two consumers, one start-time
refresh; no event sourced entity, no workflow, no secret. Decisions and their evidence are in
[research.md](research.md); the reading of the original that reshaped FR-020 is R1 and R10.

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.11.0 (constitution VI; the spec's 2026-10-08 clarification).
Go for the recording driver, in the original's toolchain (`tests/README.md`).

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract`, published locally: `Routes.public`, `Routes.paymentsOperator`,
  `PaymentsMerchants` and `PlatformAudit` with their fact types, `port.Recording`. No contract change.
- the JDK's `MessageDigest` (SHA-256, `isEqual`).

**Storage**: the service's own CNPG Postgres from ankka (durable state, view tables). No secret store:
the service holds key digests, never a key (spec *Dependencies*).

**Testing**:

- munit; `KeyValueEntityTestKit` for the five entities, `ConsumerTestKit` for `KeyFacts` and `AuditFacts`;
- `AnkkaTestKit.start` over Postgres with `FakeMerchant` under `localServices`, `InMemoryBroker` for
  the two topics and a settable clock;
- `GherkinSuite` binding `features/payments/cashier.feature` and `requests.feature`;
- `contract.port.Recording` for `tests/port/ingest/` (R15).

**Target Platform**: the shared `payments` project on the self-hosted installation (kind locally, GKE).
Exposed, since the sub-brands reach it from outside; the operator routes admit payments operators.

**Project Type**: one ankka service (`services/ingest/`), one Go driver (`tests/original/ingest-driver/`),
one manifest change, three spec corrections on this branch (research).

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-002 | one session from 10 concurrent creates under one request id |
| SC-003 | key verification under 5 ms at p99 with 10,000 keys; no call to `merchant` in the path |
| SC-004 | a deactivated key refused within 60 s (the consumer's and the view's lag, both seconds) |
| SC-005 | a flooding sub-brand's excess refused at its own entity; others answered within the bound |
| SC-007 | a record readable by an operator within 2 s (the view's lag) |

**Constraints**:

- minor units as `Long` from the boundary (`amountCents` a whole number `> 0`);
- the body bound before parsing, the key before the rate, the rate before the fields (R2);
- no customer field, address or key in any state, view row, log, span, audit record or operator answer
  (SC-006);
- the caller answered within 10 s, under the gateway's 15 s bound; `merchant` still heard for 30 s (R6);
- three endpoints with no shared prefix, at most two path parameters per route, ACL per route;
- nothing read from another service's store; `/keys` once at start.

**Scale/Scope**:

| Item | Count |
|---|---|
| Public routes | 4 at 2 prefixes |
| Operator routes | 5 at 1 prefix |
| Key value entities | 5 |
| Views, consumers | 2 each |
| Feature scenarios bound | 28 (`cashier.feature` 25, `requests.feature` 3; outlines once per row) |
| Recordings replayed | 3 copied from 004, 25 from the driver (contracts/recording.md's rows, one per field and per outline row) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings and the parity tests it leaves; divergences declared | Pass. R15 and contracts/recording.md name the three Go-suite recordings reused and the driver's scenarios; one parity test per recording. *Divergences* below lists every change with what demonstrates it. Four spec corrections (research) bring the spec back to the original where it had drifted (the browser never held the key). |
| II. Money is exact, the wallet is the ledger | `Long` minor units; every money command keyed; no balance kept | Pass. `amountCents` is a `Long` or refused. The create is keyed by the sub-brand's request id (R5); the submit by the ticket, which `merchant` honours. Nothing here holds money. |
| III. A brand is a project | A shared project reaches a brand only through a grant; public routes by their own credential | Pass. The public routes are admitted by the cashier key or the ticket, never by a platform identity. `ingest` calls `merchant` only, within its project (FR-012). It holds the player id inside the forwarded command and keeps it nowhere. |
| IV. Commands over HTTP, facts over topics | No store read across services | Pass. Every exchange with `merchant` is a call. The key index is built from `payments.merchants`; the audit records are produced from a consumer of the records (R9). |
| V. Personal data marked, erasure honoured | | Pass, vacuously: nothing personal is stored (FR-018; data-model.md). The customer fields are forwarded and dropped. |
| VI. Scala by default | | Pass: Scala. Go only for the driver. |
| VII. Specs in the domain's words | `just features` passes; every requirement traced | Pass: 0 findings, 1,051 scenarios, with the three scenarios correction 2 added. |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (twice), 2026-10-12 and 2026-10-21. |
| Scope: ankka gaps are ankka specs | | Pass. No `OPTIONS` verb and no CORS in 0.11 is met by a spec correction grounded in the original, not a workaround (R10); the missing per-call client timeout is met by a race in the handler, which is ordinary code. Both noted for ankka (finding 5). |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-002, FR-004: the key cache | `Key` (key value, id = key id); `KeyIndex` (key value, id = digest; replaced `KeysView.byDigest` at implementation, R3); `KeyFacts` consumer; `KeyRefresh` at start | the deactivation fact has no digest, so the index is by key id; the request path reads a view row by digest, spinning up no actor for unknown keys | one entity per digest (unknown keys make empty actors; deactivation cannot find its row); one entity for all keys (a hotspot); an in-memory map (per node) (R3) |
| FR-005, FR-003: refusals and the sub-brand | the handler, with `Digests` | the view row names the sub-brand; `MessageDigest.isEqual` on the digest | — |
| FR-015: the rate | `RateWindow` (key value, id = sub-brand), fixed minute window; `PUT …/rate` | cluster-wide count, one actor per sub-brand so one flood touches no other | in-memory bucket per node (per-node limits, forgets the raise); the gateway (has none) (R4) |
| FR-007, FR-008: fields and shaping | `Fields.check`, `Shape.toCommand`, pure | nothing to persist | — |
| FR-010, FR-013: idempotency and the fresh attempt | `Request` (key value, id = sub-brand/request id), attempt-numbered | the claim serialises concurrent sends; the attempt number is what keeps a late answer out | leaving it to `merchant` (creates a second session); `409` on a concurrent send (the feature wants the first session) (R5) |
| FR-011, FR-012: forwarding | `MerchantClient` over the service client; `Bound.race` | identity from the certificate; the race answers in 10 s and still hears the late answer | `ANKKA_SERVICE_CLIENT_TIMEOUT=10s` (drops the connection; FR-014 unmeetable) (R6) |
| FR-014: the lost answer | `RequestRecord.lostAnswer`; `RequestsView.lostAnswers`; the operator's `seen` | the record of the unanswered attempt is where the answer belongs | a lost-answer entity of its own (a second thing to list) (R8) |
| FR-009, FR-001 (submit and abandon by ticket) | `Ownership` (key value, id = `ticket/…` or `payment/…`) | one lookup names the sub-brand for a ticket or a payment; unknown tickets never reach `merchant` | forwarding and letting `merchant` answer `404` (a flood reaches it) (R7) |
| FR-016 to FR-018: the record | `RequestRecord` (key value, id = record id); `RequestsView` with three declared queries | one write per request; the view serves time, request id and lost answers | a view over `Request` (misses keyless refusals, submits, reads) (R8) |
| FR-019: audit | `AuditFacts` consumer of `stateOf(RequestRecord)` → `platform.audit` | constitution IV, as `merchant` does it | a produce from the handler (not how facts are made here) (R9) |
| FR-021: the body bound | the handler over the raw bytes, `BODY_BOUND` | the refusal is recorded; Pekko's 8 MiB stays as the outer guard | Pekko's limit alone (the runtime's refusal, recorded by nobody) (R11) |
| FR-017: the operator | `IngestEndpoint("/v1/ingest")`, `Routes.paymentsOperator()` | as 005's outcome routes | per-level admission (the hierarchy is `merchant`'s; finding 2) (R12) |
| FR-020 (corrected): no preflight | none | the browser never holds the key (R1); 0.11 has no `OPTIONS` (R10) | — |

Pure logic with no component: `Fields`, `Digests`, `Shape`, `Bound`, `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/006-ingest/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R16, spec corrections, findings, open for Sheldon
├── data-model.md        # Phase 1: the five entities, two views, two consumers, the refresh, the settings
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── routes.md        # every route by audience; bodies and refusals; what is not served
│   ├── merchant.md      # 004's commands.md adopted; what each side relies on
│   └── recording.md     # the three recordings reused, the driver's scenarios, the marked divergences
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
shared/payments/manifest.json               # ingest's env (research R13)
features/payments/cashier.feature           # + three scenarios (correction 2)
services/ingest/                            # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── README.md
├── src/main/scala/io/eitheror/casino/ingest/
│   ├── keys/                               # Key, KeyIndex, KeyFacts, KeyRefresh
│   ├── rate/                               # RateWindow
│   ├── requests/                           # Types (Outcome, Route, Answer), Request, Ownership, RequestRecord, RequestsView
│   ├── audit/                              # AuditFacts (over the contract's AuditConsumer, hoisted from merchant: tasks T003)
│   ├── merchant/                           # MerchantClient (the four commands and /keys), Bound
│   ├── shape/                              # Fields, Digests, Shape
│   ├── api/                                # CashierEndpoint, PaymentsEndpoint, IngestEndpoint; Context (the pipeline), Create, Ticket, Read, Rate, Operator, Answers
│   ├── IngestCodecs.scala
│   ├── Settings.scala
│   └── IngestService.scala                 # registration; the start refresh
└── src/test/scala/io/eitheror/casino/ingest/
    ├── shape/, merchant/                   # pure
    ├── keys/, rate/, requests/, audit/     # entity and consumer kits
    ├── it/                                 # Kit, FakeMerchant; CashierSuite, RepeatSuite, UnansweredSuite, RateSuite, KeysSuite, LeakSuite, OperatorSuite
    ├── port/                               # ParitySuite
    └── features/                           # GherkinSuite bindings
tests/original/ingest-driver/               # Go: one scenario per feature scenario the original can show
tests/port/ingest/                          # the recordings (three copied from tests/port/merchant, the driver's after its run)
justfile                                    # + ingest-test, ingest-it, ingest-run
```

**Structure Decision**: one service directory, as ankka's tooling expects, with a package per
capability, in the shape `services/merchant/` and `services/psp-gateway/` already have. The one
outbound seam, `merchant/MerchantClient`, is a trait with an HTTP implementation and a fake in `it/`.
The driver sits beside `merchant-driver/` and reuses its session calls.

## Phase 0: research

Done: [research.md](research.md), R1–R16. No unknown remains. Three items are *verify at task time*, each
with a fallback (R11):

- the gateway forwards `X-Forwarded-For` to the service (fallback: `clientIp` empty, as the original
  behind a proxy that set none);
- `request.header` matches a header name in any case (fallback: a lower-cased scan of all headers);
- a bearer the brand's proxy sends on a public route is not examined by the runtime (fallback: an
  ankka finding, since `Routes.public` must stay a caller-only ACL).

The research also records 4 spec corrections, 5 findings for other specs and 3 questions for Sheldon.

## Phase 1: design

- [data-model.md](data-model.md): the five entities with their commands and transitions, the two
  views' declared queries, the two consumers, the refresh, the settings, the pure packages.
- [contracts/](contracts/): every route with bodies and refusals in check order; `merchant`'s contract
  adopted; the recordings reused and driven, with each marked divergence.
- [quickstart.md](quickstart.md): features, recordings, unit suites, integration suites, a local run
  beside `merchant`, a kind installation.

### Constitution re-check after design

Unchanged. The design adds one service with its own database, producing to `platform.audit` from a
consumer and reading `payments.merchants` through one; one call target, `merchant`, within the
project; no contract change; no grant. Nothing reads another service's store, and nothing holds a
balance, a key or a personal field.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| A missing `merchantKey` answers `401 UNAUTHENTICATED` (the original: Pekko's `400` missing-header text) | FR-005; correction 4 | the driver's no-key recording, marked |
| A missing or malformed `amountCents`, `currency`, `customerId`, `country` or `redirectUrl` is `400 INVALID_REQUEST` naming the field, and nothing reaches `merchant` (the original defaults them, or answers `MISSING_ROUTING_CONTEXT`) | FR-007, *Changes* | the driver's field recordings, marked; `FakeMerchant` records no call |
| A `customerId` without its brand is refused | FR-007 | the driver's recording, marked |
| A repeat under one `transactionId` with the same body returns the first session; with another body `409 REQUEST_ID_REUSED` (the original: a second session) | FR-010, *Changes* | the driver's two repeat recordings, marked; `RepeatSuite` |
| A refused create leaves its request id free | R5, correction 3 | `RequestSuite`; no recording differs |
| A create `merchant` does not answer in 10 s is `503 TRY_AGAIN`; a late answer is a lost answer (the original: `500` with the exception's text, after Pekko's own timeout) | FR-013, FR-014 | `UnansweredSuite`; no recording differs (the original cannot be made to time out) |
| A payment is read only with its sub-brand's key or its session's ticket; another's is `404` (the original: anyone) | FR-009, *Changes* | the driver's other-sub-brand recording, marked; the brand's key path replays unmarked |
| A submit or abandon under a ticket `ingest` never saw is `404` without asking `merchant` | R7 | the driver's made-up-token recording: `FakeMerchant` records no call |
| `POST /v1/cashier/sessions/{ticket}/abandon` is public, by the ticket (the original: internal, by the player's id) | 004 FR-024 | `CashierSuite`; no recording (the original's route is internal) |
| A rate limit per sub-brand, `429` with `Retry-After` | FR-015, *Changes* | `RateSuite`; `cashier.feature`'s three rate scenarios |
| Request records, lost answers and the operator's routes | FR-014, FR-016, FR-017 | `OperatorSuite`; `requests.feature` |
| Key or rate refusals are audit records | FR-019 | `AuditFactsSuite`; the added scenario |
| Key verification by a fact-fed index, `/keys` once at start (the original: a scan of every sub-brand per request) | FR-002, FR-004 | `KeysSuite`: `FakeMerchant` asked nothing after start; a deactivation fact refuses the key |
| No `OPTIONS` answer and no CORS headers (the original: `200` and `Access-Control-*` from `CASHIER_ALLOWED_ORIGINS`) | R10, correction 1 | none needed: the browser never sends the key cross-origin (R1); the frontend's proxy is the evidence |
| Health is the runtime's readiness, not `/health` | ankka runtime | `Parity.divergences` on any recorded `/health` step |

## What the next command needs to know

**Build order:**

1. the three spec corrections are already applied on this branch (spec.md, `cashier.feature`); `just
   features` passes;
2. the service skeleton: `ankka init`, `build.sbt` on `casino-contract`, `Settings`, `IngestCodecs`,
   the `just` recipes;
3. the pure packages with their suites: `Fields` (every FR-007 row), `Digests`, `Shape`, `Bound`;
4. the entities with kits: `Key`, `RateWindow`, `Request` (every row of its command table),
   `Ownership`, `RequestRecord`;
5. `KeysView`, `RequestsView`; `KeyFacts` and `AuditFacts` with kits;
6. `FakeMerchant` and the integration kit (short `ANSWER_BOUND`, settable clock, `InMemoryBroker`);
7. `MerchantClient`, `KeyRefresh`, the create route end to end, fail-first on the parity test of
   scenario 14's session step;
8. submit, abandon, read, with `Ownership`;
9. the rate; the operator routes;
10. the driver and the recording run (needs the original's KinD stack); copy the three 004 recordings;
11. `ParitySuite` over every recording, each marked divergence named;
12. `LeakSuite`, `KeysSuite`, `RepeatSuite`, `UnansweredSuite`, `RateSuite`, `OperatorSuite`,
    `GherkinSuite`;
13. the manifest's `ingest` env and a kind deploy beside `merchant` and `psp-gateway`.

**Constraints on that order:**

- Parity tests are written before the components they test, and must fail first (ankka-port §7). The
  three copied recordings are available from step 1; the driver's wait on step 10.
- The recording run is against a local original only, never a shared or production one.

**Elsewhere:**

- **Spec corrections** 1 to 4 (research) are on this branch: spec.md's FR-010, FR-020, the preflight
  edge case, the key-holding assumption and *Changes*; three scenarios in `cashier.feature` named from
  US1.
- **For Sheldon** (research *Open*): a refused create frees its request id; the operator's record
  shows the cashier ticket; an outside sub-brand's page reads a payment with a `cashierTicket` header.
- **004 follow-ups**: none required (finding 1). Finding 2 (per-level operator admission) is a later
  spec if wanted.
- **Contract**: `AuditConsumer`/`AuditCompanion` are copied from `merchant` unless hoisted into
  `casino-contract` first (finding 4); either is a task.
- **Frontend (out of scope)**: the cashier base URL becomes `ingest`'s hostname; `CASINO_MERCHANT_KEY`
  stays on the frontend's server (finding 3).
- **For ankka (not blocking):** no `OPTIONS` verb or CORS in 0.11; no per-call service-client
  timeout; the three verify-at-task-time items (finding 5).
