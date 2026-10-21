# Implementation Plan: psp-gateway

**Branch**: `005-psp-gateway` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-psp-gateway/spec.md`

## Summary

`psp-gateway` is the second Scala service of the shared `payments` project. It serves nine payment
providers behind one contract:

- **Deposits:** `merchant` initiates a hosted-page or crypto deposit keyed by its payment id, and reads
  the session's status.
- **Payouts:** a brand's `payouts` lists its withdrawal terminals, initiates a payout keyed by the
  withdrawal request id, and reads its status.
- **Webhooks:** each provider's webhook is verified over the raw body by that provider's scheme,
  cross-checked against the session, and acknowledged as the provider expects.

Each initiation is a session, an event-sourced entity claimed before the provider is called, so a repeat
calls no provider and a webhook always finds its session. Outcomes reach `merchant`, or the brand's
`payouts` under grant, from a consumer of the sessions' events. The consumer retries with the original's
budget, then records the outcome undelivered. A two-minute timer re-drives it until it is delivered or
refused, and an operator can re-drive one at once.

Credentials are read from `merchant` at every use and kept nowhere. One Go `psp-stub` stands in for all
nine providers. Decisions and their evidence are in [research.md](research.md). The language changed
from the spec's TypeScript to Scala (R1, decided 2026-10-18).

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.11.0 (research R1; constitution VI's default). Go for
`psp-stub` and the recording driver, which run in the original's toolchain (spec 001 R10;
`tests/README.md`).

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract`, published locally. It gains two grants in `Layout.brandGrantsReceived`
  (R12);
- the JDK's `java.net.http.HttpClient`, `MessageDigest`, `Mac` (HmacSHA256) and `Signature` (Ed25519,
  for BounceMoney).

ankka 032 (recurring timers) is in 0.11.0, as `merchant`'s `OperatorSync` uses it. 040 grants are as
`contract.access.Routes` applies them until 040 ships (the named-caller list).

**Storage**: the service's own CNPG Postgres from ankka (journal, key value store, view tables, timers).
No secret store: nothing here is a secret of this service's (R7).

**Testing**:

- munit;
- `EventSourcedTestKit` for the two sessions, `KeyValueEntityTestKit` for the index, `ConsumerTestKit`
  for the deliverer;
- `AnkkaTestKit.start` over Postgres with `FakeMerchant`, `FakePayouts`, `psp-stub` in a testcontainers
  container, `TimerRuntime` and a settable clock;
- `GherkinSuite` binding `features/payments/{providers,crypto,withdrawal-payments,outcomes}.feature`;
- `contract.port.Recording` for `tests/port/psp-gateway/` (R14, R16).

**Target Platform**: the shared `payments` project on the self-hosted installation (kind locally, GKE).
It is exposed for the webhooks and the browser routes. Everything else is admitted by identity: `merchant`,
the brands' `payouts`, payments operators (contracts/routes.md).

**Project Type**: one ankka service (`services/psp-gateway/`). Alongside it:

- one Go programme with two new stubs (`tests/stubs/psp-stub/`, `unityfinance/`, `digitarium/`);
- one Go driver (`tests/original/gateway-driver/`);
- one additive contract change;
- one manifest change;
- two 004 follow-ups (research *Findings* 1, 2).

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-002 | one capture under five deliveries |
| SC-003 | delivered within one 2-minute re-drive of the receiver's recovery |
| Webhooks | answered within the providers' timeouts: under 1 s with `merchant` up, bounded by the 15 s delivery budget, which runs in the consumer and not the webhook |

**Constraints**:

- minor units as `Long` from the boundary; a decimal amount refused;
- verification before anything else, over the raw body;
- no credential or personal field in any event, view, log, span or response (SC-004);
- every outbound call bounded;
- no base URL default;
- one endpoint per prefix, nested prefixes for the public routes;
- at most two path parameters per route.

**Scale/Scope**:

| Item | Count |
|---|---|
| Routes | 32 at 7 prefixes |
| Entities | 2 event sourced, 1 key value |
| Views, consumers, timed actions | 1 each |
| Provider adapters | 9 |
| Original Go test cases ported | about 170 |
| Driver recordings | about 110 |
| Stubs | 2 new, 7 extended |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings and the parity tests it leaves; divergences declared | Pass. R16 names the driver's scenarios and the two Go-suite recordings replayed; one parity test per recording. *Divergences* below lists every change, each with what demonstrates it. The 12 spec corrections (research) bring the spec back to the original where it had drifted. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; every money command keyed; no balance kept | Pass. Amounts are rendered per provider from minor units (`money.Render` over `contract.money.Amounts`). A deposit is keyed by the payment id and a payout by the request id (R3). The gateway records outcomes, never a balance. |
| III. A brand is a project | A shared project reaches a brand only through a grant; no player data beyond the id | Pass. `payouts` is called on the route the brand grants (`Layout.BrandGrantsMade`). The three routes `payments` grants are listed in `Layout.brandGrantsReceived` (R12). The sessions hold the player's id and no personal field (FR-022); destinations and customer fields go to the provider and nowhere else. |
| IV. Commands over HTTP, facts over topics | No store read across services | Pass. Every exchange with `merchant` and `payouts` is a call. The gateway produces no topic. |
| V. Personal data marked, erasure honoured | | Pass, vacuously: nothing personal is stored, so there is nothing to erase (data-model.md). |
| VI. Scala by default | | Pass: Scala. Go only for the stubs and the driver, in the original's toolchain. |
| VII. Specs in the domain's words | `just features` passes; scenarios named from the spec | Pass: 0 findings, 1,044 scenarios, with the corrections applied on this branch. |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08, 2026-10-12 and 2026-10-18. |
| Scope: ankka gaps are ankka specs | | Pass. The TypeScript SDK's response gap is avoided by the language choice, not worked around. A note for ankka is in *What the next command needs to know*. |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-007, FR-008, FR-024: a deposit's session | `DepositSession`, event sourced, id = payment id; claimed before the call | the claim is the atomic idempotency; the history is the dispute trail | key value: no history; record-after-call: needs the wait the original lacks (R3) |
| FR-004, FR-009: a payout's session | `PayoutSession`, event sourced, id = request id | the same, and the brand on the session (the original kept Gigadat's in memory) | — |
| FR-013 (lookup): references the provider changes | `ReferenceIndex`, key value, id = `<provider>/<ref>` | T365 dashless, Morphosis tokens: one lookup | parsing a provider's string into a key |
| FR-014, FR-015, FR-017: delivery | `OutcomeDeliverer` consumer of both sessions; `delivery.Backoff` | at least once from the journal; bounded attempts; the result is an event | throwing for redelivery: stalls a slice behind one receiver (R5) |
| FR-016: undelivered, listed and re-driven | `SessionsView` (a `KeyedView` over both sessions) queries `undelivered`, `refused`; `Redrive` timed action, recurring 2 min; the operator route, which re-drives undelivered and refused | a sweep over only what is outstanding | the original's list of every session ever made |
| FR-001 to FR-006, FR-010 to FR-013: the providers | `providers.<name>` pure; `providers.Catalogue`; `ProviderHttp` | each provider's deviations in one place with its original's tests | an adapter trait with a uniform signature: the providers differ in what they need before and after the call |
| FR-018, FR-019: credentials | `merchant.MerchantClient` over the two internal routes, per use | no copy anywhere | the request's `terminalProperties`: forbidden by FR-018 (R7) |
| FR-009a: the payout terminal listing | `/v1/payments/withdrawal-terminals` over `merchant`'s brand listing (004 follow-up) plus `Catalogue` | `payouts` keeps no copy | a view of `merchant`'s terminals here: another service's facts, which `merchant` does not publish |
| FR-020, FR-023: base URLs, timeouts, health | `Settings`; `ProviderHttp` with a per-provider window; `GET /status` and the gauges | per node is enough to see a provider failing | an entity per provider: a write per payment |
| FR-021: identity | ACLs per prefix (contracts/routes.md); `Caller.Service(_, "payouts")` with the brand as the caller's project | no shared secret | — |
| FR-025, FR-026: the test double | `tests/stubs/psp-stub` (Go) | the seven stubs exist and are tested | Scala fakes per provider: re-deriving checked shapes |
| US6: browser routes | `/v1/psp/checkout` endpoint, `Html` answers | the original's pages | — |

Pure logic with no component: the nine `providers.*`, `Catalogue`, `money.Render`, `crypto.Underpaid`,
`delivery.Backoff`, `delivery.Signature`, `http.Form`, `http.Raw`.

## Project Structure

### Documentation (this feature)

```text
specs/005-psp-gateway/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R16, spec corrections, findings for other specs and in the original
├── data-model.md        # Phase 1: the sessions, the index, the view, the consumer, the timer, the settings
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── routes.md        # every route by audience and prefix; what is dropped
│   ├── merchant.md      # 004's gateway.md adopted, with the four points that refine it
│   ├── payouts.md       # the three granted routes and the outcome call (007 adopts it)
│   └── recording.md     # how the driver records the original and how it is replayed
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/                                   # Layout.brandGrantsReceived: + GET withdrawals/{requestId}, GET withdrawal-terminals (R12)
shared/payments/manifest.json               # psp-gateway's env: PUBLIC_URL and the base URLs; the kind overlay points them at psp-stub
services/psp-gateway/                       # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── README.md
├── src/main/scala/io/eitheror/casino/pspgateway/
│   ├── sessions/                           # DepositSession, PayoutSession, ReferenceIndex, SessionsView, Outcome, Delivery
│   ├── providers/                          # Catalogue; dreamzpay/, unityfinance/, t365/, cozeni/, eupaymentz/, gigadat/, bouncemoney/, digitarium/, morphosis/
│   ├── deposits/                           # Initiate (claim → provider → record), Webhooks (resolve, verify, cross-check, settle, answer)
│   ├── payouts/                            # Initiate, Status, Terminals
│   ├── delivery/                           # OutcomeDeliverer, Backoff, Signature, Receivers (merchant, payouts), Redrive
│   ├── merchant/                           # MerchantClient (credentials, properties, brand terminals)
│   ├── http/                               # ProviderHttp (bounded, health window), Form, Raw, Pages (relay, processing)
│   ├── money/                              # Render
│   ├── crypto/                             # Underpaid
│   ├── api/                                # PspEndpoint, CheckoutEndpoint, WebhooksEndpoint, WithdrawalsEndpoint, WithdrawalTerminalsEndpoint, OutcomesEndpoint, StatusEndpoint
│   ├── GatewayCodecs.scala
│   ├── Settings.scala
│   └── PspGatewayService.scala             # registration; the Redrive schedule
└── src/test/scala/io/eitheror/casino/pspgateway/
    ├── providers/, money/, crypto/, delivery/   # pure, with the original's cases
    ├── sessions/                           # entity kits
    ├── delivery/                           # the consumer kit
    ├── it/                                 # Kit, FakeServer, FakeMerchant, FakePayouts, PspStub (container); RepeatSuite, RedriveSuite, LeakSuite, RotationSuite, ShortSuite, DurabilitySuite, SmokeSuite
    ├── port/                               # ParitySuite
    └── features/                           # GherkinSuite bindings
tests/stubs/
├── internal/stubhttp/                      # the control set grows to FR-025's; /__webhook
├── unityfinance/, digitarium/              # new
└── psp-stub/                               # one main over the nine, one port each; Dockerfile
tests/original/
├── compose.override.yml                    # one psp-stub service in place of seven
└── gateway-driver/                         # Go: one scenario per feature scenario, per provider
tests/port/psp-gateway/                     # the recordings (committed after the recording run)
```

**Structure Decision**: one service directory, as ankka's tooling expects, with a package per
capability. The providers are pure, beside the components that call them. The two outbound seams
(`merchant/`, `delivery/Receivers`) are traits with an HTTP implementation and a fake in `it/`. The
contract and manifest changes are in the root build, as spec 001 fixed. The stubs and the driver sit
beside the existing ones.

## Phase 0: research

Done: [research.md](research.md), R1–R16. No unknown remains. Three items are *verify at task time*, each
with a fallback:

- the terminal property that lists a payout terminal's coins (R12; fallback: an empty list, and 007's
  rule reads none);
- `merchant`'s credential route's case handling (R7; fallback: send what `merchant` stored, as the
  catalogue name);
- a nested public prefix under `/v1/psp` beside `merchant`'s endpoint (contracts/routes.md; ankka's
  longest-match rule, `HttpServer.scala:318-321`; fallback: `/v1/psp` as one endpoint with per-route
  `withAcl`);
- how long a consumer handler may run: the deliverer's budget is 15 s inside `OutcomeDeliverer`
  (`merchant`'s `OutcomeReporter` calls the wallet inside a handler, so a synchronous call is fine;
  the bound is the question). Fallback: the handler makes one bounded attempt and records
  `Undelivered` early, and a short timer re-drives on the original's schedule until the budget is spent.

The research also records 12 spec corrections, 6 findings for other specs and 9 findings in the original.

## Phase 1: design

- [data-model.md](data-model.md): the sessions' states and events, the index, the view, the deliverer,
  the timer, the pure packages, the settings.
- [contracts/](contracts/): every route; `merchant`'s contract adopted with four refinements; the payout
  routes and the outcome call; the recording and replay method.
- [quickstart.md](quickstart.md): stubs, recordings, unit suites, kits, integration, features, parity, a
  local run beside `merchant`, an installation.

### Constitution re-check after design

Unchanged. The design adds:

- one service with its own database, producing no topic;
- two cross-project calls on grants the contract lists;
- one additive contract change.

Nothing reads another service's store, and nothing holds a balance, a credential or a personal field.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| Scala, not TypeScript | R1 | — (the spec's clarification amended) |
| The initiate is admitted for `merchant` only; payouts for a brand's `payouts` only; the redrive route is gone | FR-021, *Changes* | `SmokeSuite`: each route refused `403` to another caller. The driver's unauthenticated initiate is replayed as `merchant` |
| A repeat initiate under one payment id returns the first answer and calls no provider | FR-008, correction 6 | the driver's repeat recording: the original's second provider call is absent in replay (marked) |
| The payment id is the provider's reference, not a fresh uuid | R3 | `vary` on the reference; the replay's provider call names the payment id |
| `terminalProperties` is ignored; credentials come from `merchant`'s routes | FR-018, R7 | `RotationSuite`; replay serves the recorded credentials from `FakeMerchant` |
| Amount, currency and settled-session cross-check for every provider | FR-013, correction 7 | the driver's tampered-amount recording: the original captured, the port answers `400` as it does a bad signature and settles nothing (marked; spec.md Clarifications 2026-10-20) |
| DreamzPay's return POST settles nothing | correction 8 | the driver's return-POST recording: the original's capture call is absent in replay (marked) |
| No wait for a session; claimed before the call | correction 5 | `DepositSessionSuite`; no recording differs |
| Outcomes from a consumer; undelivered as an event; re-driven by a timer every 2 minutes and by an operator | FR-014 to FR-016 | `RedriveSuite`; the driver's re-drive step replayed as the clock |
| A payout outcome is delivered with retries and listed when undelivered | *Changes* | the driver's payout-webhook recordings with `FakePayouts` down |
| A refused outcome is kept, listed and re-drivable by an operator (the original dropped it) | FR-016, *Edge Cases* | `outcomes.feature`'s refused scenario; the driver's `400` recording shows no marker (marked) |
| Refusals of an initiate carry the port's codes (`404 no_credentials`, `502 provider_not_configured`, `400 unsupported_*`) where the original answered `500`/`502` with free text | contracts/merchant.md | `Parity.divergences` on the driver's refusal recordings; `merchant` reads all of them as a generic failure, as it read the original's |
| The outcome to `merchant` is signed with the credential's `webhookSecret` when it has one | R13 | `CaptureSigningSuite` against 004's `Hmac.verify`; recordings have no secret and replay unmarked |
| The payout routes are one family under `/v1/payments/withdrawals`, in minor units; answers in lower case | FR-004, *Changes* | the driver's payout recordings replayed through the new route (path and amount marked) |
| Gigadat's payout brand is on the session, not in memory | R9 | `DurabilitySuite`: a payout webhook after a restart reaches the brand |
| Base URLs have no default; a missing one refuses by name | FR-020 | `SettingsSuite`; no recording differs |
| Provider calls are bounded; health per provider on `/status` | FR-023 | `ProviderHttpSuite` against a silent `psp-stub` |
| Session status read `GET /v1/psp/sessions/{reference}` | 004 contract | no original recording; `features` |
| Webhook comparisons are constant time (DreamzPay, EuPaymentz) | R9 | the original's cases pass unchanged |
| Health is the runtime's readiness, not `/health` | ankka runtime | `Parity.divergences` on any recorded `/health` step |

## What the next command needs to know

**Build order:**

1. the contract's two grants, with tests and `just brands validate`;
2. `psp-stub`: the two new stubs, the control set, the single main and its image;
3. the pure providers with the original's cases, one provider at a time, DreamzPay first;
4. the sessions and the index, with kits;
5. `FakeMerchant`, `FakePayouts`, the `psp-stub` container and the kit;
6. the deposit initiate and the session read;
7. the webhooks, provider by provider;
8. the deliverer, fail-first on a down receiver, a refusal and an exhausted budget;
9. the view, the re-drive timer and the operator routes;
10. the payout routes, provider by provider;
11. the browser routes and pages;
12. `/status` and the health window;
13. the parity suite over the recordings;
14. the manifest and a kind deploy, which closes 004 T081's last clause.

**Constraints on that order:**

- The recordings need the original running with `psp-stub`. The driver and the recording run are tasks
  of their own, and the parity suite waits on them.
- Parity tests are written before the components they test, and must fail first (ankka-port §7).

**Elsewhere:**

- **The spec corrections** are applied to spec.md, the features, `GLOSSARY.md` and `CLAUDE.md` on this
  branch. Corrections 3 (Gigadat) and Morphosis's open decision go to Sheldon.
- **004 follow-ups** (research *Findings* 1, 2, 4, 7): `merchant` sends `terminalProperties: {}`; adds
  `GET /internal/v1/brands/{brand}/terminals`; its `gateway.md` wording is corrected; and its deadline
  reads `refused: true` as it reads `undelivered: true` (wait, not expire), since a capture `merchant`
  itself refused is one an operator will re-drive. These are tasks in 005's list, made in
  `services/merchant/`.
- **A recorded refusal is final for its payment id.** `merchant`'s workflow ends a refused payment
  (`DepositWorkflow.refused`, `thenEnd`) and never retries under the same id, so a provider timeout
  fails that payment and the player starts another. That is the original's outcome too (its session
  never existed), and no retry reaches a session marked `InitiationFailed`.
- **007** should read `already_initiated` as *initiated* (finding 5).
- **For ankka (not blocking):** the 0.11 TypeScript SDK cannot set a response's status on success, its
  headers or its content type (R1). Spec 016's `partner-api`, also TypeScript, should check that before
  its plan.
