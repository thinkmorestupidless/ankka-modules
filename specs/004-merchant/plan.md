# Implementation Plan: Merchant

**Branch**: `004-merchant` | **Date**: 2026-10-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-merchant/spec.md`

## Summary

`merchant` is the first Scala service of the shared `payments` project. It keeps the payments
hierarchy (holding → company → sub-brand → terminal) and its operators, each sub-brand's merchant
keys, provider credentials and terminals, and the versioned routing trees; it runs the cashier's
commands for `ingest` (a session, a submit, an abandon, a payment read) and the gateway's capture
and fail; and it drives every deposit as one workflow per payment that initiates through the
gateway, waits for the outcome or the deadline, and hands a captured deposit to the brand's wallet
once, under the brand's grant, keyed by the payment id, acknowledging the gateway only after the
wallet has answered. The three sweepers and the reactive function of the original are the
workflow's timers and retries. Decisions and what established them are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.11.0 (constitution VI: the deposit is the money path).
Go for the recording driver and the e2e patch, which run in the original's toolchain (spec 001 R10).

**Primary Dependencies**: `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`,
`ankka-auth-oidc`, `ankka-telemetry-otlp`, `ankka-testkit`; `io.eitheror:casino-contract`
(published locally; gains `Routes.paymentsOperator` and `money.Amounts`, R10, R11); the JDK's
`HmacSHA256` and `MessageDigest.isEqual`. ankka 023 (secret store) as released; 032 (recurring
timers) and 038 (secret backends) from their branches until released, each with the fallback
research R9 and R10 name; 040 grants and 042 `Personal` as specs 001 and 002 built against them
(the contract's grant list and marker type until they ship).

**Storage**: the service's own CNPG Postgres from ankka (journal, key value store, view tables,
secret store, timers). Nothing shared (constitution VI).

**Testing**: munit; `EventSourcedTestKit` and `KeyValueEntityTestKit` for the entities,
`ConsumerTestKit` for the four consumers; `AnkkaTestKit.start` over Postgres with `FakeGateway`,
`FakeWallet`, `FakeKeycloak`, a recording `Invitations`, `InMemoryBroker`, `TimerRuntime` and a
settable clock for the workflow, the views, the seed, the sync, durability and the features;
`GherkinSuite` binding `features/payments/*.feature`; `contract.port.Recording` for
`tests/port/merchant/` (R16).

**Target Platform**: the shared `payments` project on the self-hosted installation (kind locally,
GKE), the service exposed for its token-admitted operator routes and `accept-invitation`; the `ingest`,
`psp-gateway`, erasure and status routes are service-class by ACL (research R1).

**Project Type**: one ankka service (`services/merchant/`), two additive contract changes, one
brands-tool change (R15), one manifest change (R13), a recording patch and driver in `tests/original/`.

**Performance Goals**: SC-002 (credited within one minute of the wallet's return), SC-003 (a thousand
captures with one in ten undelivered, zero expired that the provider captured), SC-004 (ten
deliveries, one credit), SC-006 (a key in one lookup). A capture acknowledged inside the 2 s poll
bound with the wallet up (R18).

**Constraints**: minor units as `Long` from the boundary; every money call keyed by the payment id;
the HMAC verified before the workflow is touched; no personal field in any event; no secret in any
event, view, log or span (SC-007); the frontend's and the gateway's paths unchanged except the three-
parameter credential routes (R14); at most two path parameters per route; one endpoint per prefix.

**Scale/Scope**: ~60 routes at 11 prefixes; 11 entities, 1 workflow, 5 views, 4 consumers, 2 timed
actions; 4 Go-scenario recordings plus ~20 driver recordings; 189 unit cases of the original ported.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings and the parity tests it leaves | Pass: R6 names the four Go scenarios, the driver's scenarios (one per feature scenario) and how each is made; one parity test per recording (R16). Divergences are the spec's *Changes from the original* and eight corrections found in planning (research, *Spec corrections*), each listed under *Divergences* below with what demonstrates it. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; every money call keyed; no balance kept here | Pass: `MinorUnits` from the boundary (R11); the wallet is called under the payment id (R7); this service records a payment's status, never a balance. The submit is idempotent by the session (correction 2). |
| III. A brand is a project | A shared project reaches a brand only through a grant; holds only the player id and the provider's customer fields, as `Personal` | Pass: the wallet is reached on the two granted routes (`Layout.BrandGrantsMade`); the customer fields are `Personal` in two key value entities under the player's subject (R12); the sub-brand's `brand` field is the authority (Clarifications). |
| IV. Commands over HTTP, facts over topics | Facts from consumers in the contract's shape; no store read across services | Pass: `payments.merchants` and `platform.audit` from consumers (R13); the outcomes reach the brand as HTTP calls under grant, and the brand's wallet makes the facts (spec 002 FR-022a). `psp-gateway` and `ingest` read nothing of this service's store: they call routes (R3, R4). |
| V. Personal data marked, erasure honoured | `Personal` fields; an erasure handler over sessions and payments | Pass: R12; the handler is a route the brand's `players` calls under the manifest's grant, until 042 ships (spec 002 R12's pattern). |
| VI. Scala by default | | Pass: Scala; Go only for the recording driver, in the original's toolchain (spec 001 R10's reason). |
| VII. Specs in the domain's words | `just features` passes; scenarios named from the spec | Pass: 0 findings, 1,034 scenarios (this session). |
| Workflow: clarify before plan; `just features` before plan | | Pass: clarified 2026-10-08 (three sessions); features green this session. |
| Scope: ankka gaps are ankka specs | | Pass: 032 and 038 used as specified with fallbacks (R9, R10); 040 and 042 as 001 and 002 built against them. Nothing is rebuilt here. |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001 to FR-003: the hierarchy | `Holding`, `Company`, `SubBrand`, event sourced, id = the entity's id (name-based UUIDs when seeded) | history for the tree and the audit; a child records its parent | key value: no history for FR-034 |
| FR-004, FR-005: merchant keys | key events on `SubBrand`; `MerchantKeyIndex`, key value, id = the digest, from the `KeyIndexer` consumer; `MerchantFacts` consumer → `payments.merchants`; `ActiveKeys` view for `ingest`'s refresh | one lookup by digest (SC-006); the fact is the contract's shape | a key entity per key: the sub-brand must still list its keys for the backoffice (R2) |
| FR-006, FR-008, FR-009: credentials and the catalogue | credential records as `SubBrand` events; secrets at `credential/<id>` in the secret store; `Catalogue` pure | a listing shows no secret; the gateway reads through a route | secrets in events: permanent in the journal (the original's defect, `HppInitiateReq` comment) |
| FR-007: terminals | `Terminal`, event sourced, id = terminal id; `TerminalsBySubBrand` view; secret properties at `terminal/<id>` | its own lifecycle; the parent lists no children | a list on the sub-brand (the original's `saveGatewayIntegrations`): one write for every terminal's change |
| FR-010 to FR-012: routing trees | `RoutingTree`, event sourced, id = `<level>/<entityId>`; `routing.Evaluator` pure; resolution sub-brand → company → holding in the session command | every deploy, rollback and retirement is an event; the walk is the original's, ported with its tests | — |
| FR-013, FR-014: a session | `Session`, key value, id = the token, `expireAfter` = the deadline | one command consumes it; nobody needs its history; it holds `Personal` fields that must be deletable (R12) | event sourced: a journal of personal fields |
| FR-015 to FR-017, FR-019, FR-025, FR-026: a payment | `Payment`, event sourced, id = payment id; `PaymentCustomer`, key value, id = payment id (the `Personal` fields) | the outcome trail a dispute and the audit need; the personal fields beside it, erasable | one entity holding both: personal fields in a journal |
| FR-020 to FR-022: the deposit | `DepositWorkflow`, id = payment id (R7); `OutcomeReporter` consumer → the wallet's outcome route | `start` is the atomic claim; the deadline is the pause's timeout; the credit retries in its own pause loop; the journal survives a crash; the state is the lifetime record | the original's chain of concept, function, three sweepers: each exists because the step before it could lose money |
| FR-018: the gateway's capture and fail | `/v1/payments` endpoint, `withAcl(psp-gateway)`, `security.Hmac` over the `String` body | one endpoint for the prefix; the provider's HMAC kept, the internal secret replaced by identity | — |
| FR-023: captured and not credited | `PaymentsView` query `uncredited`; `retry` command on the workflow; gauge `eitheror_payments_uncredited_captures`; `GET /status` | the entry lives on the payment it belongs to | the original's hourly scan of every captured payment |
| FR-024: abandon | `Session` read → `DepositWorkflow.abandon` | ownership proven by the token | — |
| FR-027, FR-028: operators | `Operator`, event sourced, id = the realm user id; `InvitationToken`, key value, id = the token's digest, 72 h; `operators/KeycloakAdmin`; `Invitations` seam; `OperatorSync` timed action, recurring | what Keycloak was told is recorded; the sync is durable | the in-process syncer: lost on restart |
| FR-029: the backoffice list and summary | `PaymentsView` queries `bySubBrand`, `summary` | SQL over one row per payment | the original's scan of every twin in the namespace |
| FR-032: erasure | `/erasure` endpoint, `withAcl(the brands' players)`; `PaymentsView` query `byCustomer` | finds and deletes the customer's two key value kinds | — |
| FR-033: operator scope | contract `Routes.paymentsOperator`; `operators/Scope`, `Privilege` pure | the claims decision 0050 fixed | — |
| FR-034: audit | `AuditFacts` consumer → `platform.audit` | the contract's `AuditRecord` from events, exactly once | — |
| FR-003: seeding | `Seed` timed action at startup from `MERCHANT_BRANDS` (R15) | the tool cannot reach an unexposed service; a timer is at least once and idempotent | `DevSeedRoutes` (dropped) |

Pure logic with no component: `routing.Evaluator`, `money.Amounts` (in the contract), `money.Variance`,
`security.Hmac`, `operators.Scope`, `operators.Privilege`, `payments.Status` (the stored and
frontend words), `catalogue.Catalogue` (schemes, required and secret properties, the masked preview).

## Project Structure

### Documentation (this feature)

```text
specs/004-merchant/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R20, spec corrections, findings for other specs
├── data-model.md        # Phase 1: entities, the workflow, views, consumers, timers, settings
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── routes.md        # every route by audience and prefix; what is dropped; the divergences
│   ├── commands.md      # what ingest sends and is answered (spec 006 adopts it)
│   ├── gateway.md       # what this service asks of psp-gateway and how the gateway calls back (spec 005 adopts it)
│   ├── wallet.md        # the two granted calls to the brand's wallet, as spec 002 built them
│   ├── facts.md         # payments.merchants, platform.audit, the contract additions
│   └── recording.md     # how scenarios 12–14 and the driver's scenarios are recorded and replayed
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/                                   # additive: access.Routes.paymentsOperator (R10); money.Amounts (R11); Layout.ServiceOnly loses merchant (R1)
shared/payments/manifest.json               # merchant exposed (R1); adds platform.audit (R13, verify); merchant's pin gains the settings (R19)
tools/brands/                               # Render: MERCHANT_BRANDS from brands/*/manifest.json when applying shared/payments (R15)
services/merchant/                          # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── README.md
├── src/main/scala/io/eitheror/casino/merchant/
│   ├── hierarchy/                          # Holding, Company, SubBrand entities (keys, credential records), HierarchyView, Seed
│   ├── terminals/                          # Terminal entity, TerminalsBySubBrand view, catalogue/Catalogue (schemes, secrets, preview)
│   ├── keys/                               # MerchantKeyIndex, KeyIndexer consumer, ActiveKeys view, MerchantFacts consumer
│   ├── routing/                            # Evaluator (pure), RoutingTree entity, Resolution (sub-brand → company → holding)
│   ├── cashier/                            # Session entity, the create/submit/abandon/read commands
│   ├── payments/                           # Payment entity, PaymentCustomer entity, PaymentsView, Variance and Status (pure)
│   ├── deposits/                           # DepositWorkflow, OutcomeReporter consumer, Backoff
│   ├── gateway/                            # GatewayClient (trait, http), HostedPage, Initiated, SessionStatus
│   ├── wallet/                             # WalletClient (the two granted routes), Refusals (final vs retry)
│   ├── security/                           # Hmac (verify over the raw body, the window), Secrets (store names, the 30 s cache)
│   ├── operators/                          # Operator entity, InvitationToken, KeycloakAdmin, Invitations (seam, CustomerIo), Scope, Privilege, OperatorSync
│   ├── audit/                              # AuditFacts consumer
│   ├── api/                                # one endpoint per prefix: Holdings, Companies, SubBrands, Merchants, Payments, Catalog, Operators, Cashier, Internal, Erasure, Status
│   ├── MerchantCodecs.scala                # codecs for stored types
│   ├── Settings.scala                      # R19
│   └── MerchantService.scala               # registration, extensions, the timers' schedule
└── src/test/scala/io/eitheror/casino/merchant/
    ├── routing/, money/, security/, operators/, payments/   # the pure packages with the original's cases ported
    ├── hierarchy/, terminals/, cashier/, payments/, operators/  # entity kits
    ├── keys/, deposits/, audit/            # consumer kits
    ├── it/                                 # Kit, FakeGateway, FakeWallet, FakeKeycloak, RecordingInvitations, FakeServer; DepositWorkflowSuite, ViewsSuite, SeedSuite, SyncSuite, DurabilitySuite, LeakSuite (SC-003), RepeatSuite (SC-004)
    ├── port/                               # ParitySuite: one test per tests/port/merchant/*.json
    └── features/                           # GherkinSuite bindings for features/payments (this spec's scenarios)
tests/original/
├── e2e-merchant.patch                      # unskips scenario 12's chain and corrects the routing-rules calls (R6)
└── merchant-driver/                        # Go: one scenario per deposits.feature scenario against the original's cashier (R6)
tests/port/merchant/                        # the recordings (committed after the recording run)
```

**Structure Decision**: one service directory as ankka's tooling expects; a package per capability
of the spec (hierarchy, terminals, keys, routing, cashier, payments, deposits, operators) with the
pure logic beside the components that use it; the two external seams (`gateway/`, `wallet/`) as
traits with an HTTP implementation and a fake in `it/`; the contract and tool changes in the root
build as spec 001 fixed; recordings and the driver beside the other services'.

## Phase 0: research

Done: [research.md](research.md), R1–R20. No unknown remains. Items marked *verify at task time*,
each with a fallback: the view's aggregate SQL (R2), the cross-project service call's form (R5),
the audit topic for a shared project (R13), the tool's rendering of a computed env (R15). Eight
spec corrections are listed, seven applied to spec.md on this branch and one a question for
Sheldon; four findings for other specs.

## Phase 1: design

- [data-model.md](data-model.md): the eleven entities, the workflow and its transitions, the five
  views, the four consumers, the two timed actions, the settings.
- [contracts/](contracts/): every route by audience; the commands `ingest` sends; the gateway
  contract both 004 and 005 are held to; the wallet calls as spec 002 built them; the facts and the
  contract additions; the recording and replay method.
- [quickstart.md](quickstart.md): recordings, unit suites, features, parity, a local run with the
  fakes, an installation.

### Constitution re-check after design

Unchanged. The design adds one service with its own database, one topic written for the contract
(`payments.merchants`), one for the audit (`platform.audit`, verify), two outbound HTTP seams
(`psp-gateway` within the project; a brand's wallet under grant), and two additive contract changes.
Nothing reads another service's store; nothing holds a balance; the personal fields are marked and
deletable; no secret leaves the store except to the gateway's route, by identity.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| The capture is acknowledged only after the wallet answers; `503 OUTCOME_PENDING` otherwise | FR-021, *Changes* | `deposits.feature` US1 scenario 3, US3 scenarios 1 and 2; `DepositWorkflowSuite`; the driver's capture recording is replayed with the fake wallet up |
| The wallet is called under the payment id, not the provider's transaction id | spec 001 FR-017, spec 002 FR-022 | `FakeWallet` records the key; scenario 14's wallet-balance step replayed as the fake's balance |
| The internal secret header is replaced by the gateway's identity | *Changes* | recorded capture steps carry no secret; replayed as `Caller.Service("payments","psp-gateway")`; a step the original refused `401` for a missing secret is replayed as another caller, refused `403` (marked) |
| The cashier's public routes are `ingest`'s; this service serves commands by identity | *Changes*, spec 006 | recorded `merchantKey` steps replayed as the commands with the sub-brand resolved from the key's digest (R4) |
| A session submitted twice returns the first answer | correction 2 | `cashier.feature` scenario; the original has no guard |
| The outcomes other than the capture reach the brand from a consumer | correction 3 (R7) | `OutcomeReporter` kit; `deposits.feature` "reaches the brand" steps |
| Credential routes take the name as `?name=` | R14 | `HierarchyFeatures` (`/psp-credentials/DREAMZPAY?name=main`). No recording reaches a credential route: `e2e-merchant.patch` onboards DreamzPay as an integration activated by its id (`PATCH`, as the original), which replays unmarked |
| The three sweepers, the reactive function, the internal attempts and abandon routes are gone | *Changes*, SC-005 | the driver's `expire-stale` step replayed as the kit's clock; the internal abandon step as the token-admitted command |
| Risk routes dropped | FR-036 (correction 1) | no recording touches them |
| Amounts are `Long` minor units; `amountCents` a number beyond int32 | R11 | the parity suite's amounts compare as numbers; a `LeakSuite` capture above 2^31 minor units |
| The gateway catalogue marks secret properties | R9 (correction 4) | `Catalogue` unit test; a listing shows a masked preview only |
| Operator invitations through the installation's provider configured here | R10 (correction 7) | `RecordingInvitations` in the kit; `operators.feature` |
| A session's answer names the level its tree was resolved from (`resolvedFrom`) | FR-011 | `Parity.divergences`: marked in every recording with a session |
| Withdrawal turnover is not on the sub-brand's answer | spec 002 FR-004a | `Parity.divergences`: scenarios 12, 13 and 14's sub-brand steps |
| A deposit's brand is the sub-brand's; scenario 14's `Payments` sub-brand is created as `Casino` with the brand its customer reference names | spec.md Clarifications | `Parity.divergences`: scenario 14's sub-brand step (`type`, `brand`) |
| The abandon is admitted by the session's token; a player without it is refused `404 SESSION_NOT_FOUND`, where the original answered `PAYMENT_NOT_FOUND` for another player | *Changes*, spec 006 | `Parity.divergences`: the abandon recording's other-player step, sent under a token of no session |
| A payment waiting for an undelivered outcome says why in its read (`waitingReason`) | FR-020 | `Parity.divergences`: the undelivered recording's read after the sweep |
| The deadline is one clock: the original's sweep decided expiry before `expires_at`, so its read was not yet past it; ours reads `EXPIRED` (FR-017) while the payment waits | FR-017, FR-020 | `Parity.divergences`: the same read; `expiredAt` stays compared, so the payment is shown not expired |
| Health is the runtime's readiness (`GET /ready` on the probe port), not a route | ankka runtime endpoints | `Parity.divergences`: `TestScenario12_OnboardingGate2_ServicesHealthy` |
| `merchant` is exposed, for its operator routes and `accept-invitation` | R1 (analysis correction 9); spec 001 had it service-only | `SmokeSuite`: an identity route called with a token is `403`; `just brands validate` accepts `expose: true` |

## What the next command needs to know

- Order: the contract's `Routes.paymentsOperator` and `money.Amounts` with tests and `just schemas`;
  the pure packages with the original's cases (`routing.Evaluator`, `money.Variance`,
  `security.Hmac`, `operators.Scope`/`Privilege`, `payments.Status`, `Catalogue`); the entities with
  kits (hierarchy, terminal, routing tree, session, payment, operator); `FakeGateway`, `FakeWallet`,
  `FakeKeycloak` and the kit; `DepositWorkflow` and its suite (fail-first on the unreachable wallet,
  the lost answer, the final refusal, the deadline, the undelivered wait); the `Payments` endpoint
  (capture, fail, read); the `Cashier` endpoint and the session; the parity suite over the recordings
  (which must exist first: the recording run and the driver are tasks of their own and need the
  original running); the views and the backoffice reads; keys, facts, the index and `ingest`'s read;
  operators, Keycloak, the invitation and the sync; the audit; the seed and the tool's env; the
  manifest edit and a kind deploy of the `payments` project.
- Parity tests are written before the components they test and must fail first (ankka-port §7).
- The recording run follows `tests/original/README.md` with `e2e-merchant.patch` applied and the
  dreamzpay stub's values in `DREAMZPAY_*`; scenario 14 needs the KinD casino stack as spec 003's
  recordings did (`tests/port/README.md`).
- Spec 005's and 006's plans adopt `contracts/gateway.md` and `contracts/commands.md`; raise the
  005 FR-018 finding on 005 before its plan.
- The seven spec corrections are applied to spec.md on this branch; the eighth (R17) is a question
  for Sheldon, left in the spec's Clarifications as open.
- 032 recurring timers: until merged, `OperatorSync` schedules a single timer that reschedules itself
  from the handler. 038: until merged, the store's default backend; nothing here reads the record.
