# Implementation Plan: affiliate programme

**Branch**: `013-affiliate-programme` | **Date**: 2026-11-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/013-affiliate-programme/spec.md`

## Summary

`affiliate-programme` is the one service of the shared `programme` project: eitheror's affiliate
network for every brand here, eitheror's brands elsewhere and hosted brands of other companies. Facts
arrive on each local brand's granted `affiliates.attribution` topic (one consumer per brand) or as spec
012's signed webhooks from anywhere else, are deduplicated once in an inbox, and become ADR 0042's
immutable commission facts per (affiliate, brand, currency). A period close claims them for a
deterministic payout. The portal and operator surface keep the original's routes, with the brand as a
filter; affiliates' identities are encrypted under a key of their own, so erasure is destroying it.

Five event sourced entities, sixteen key value entities, five workflows, twelve views, a consumer per
brand plus four, three timed actions. Decisions and evidence: [research.md](research.md) R1–R20. Eleven
decisions were made with the person on 2026-11-01 (spec Clarifications, two sessions).

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.13.0 (constitution VI). Go for the recording driver and the
import's exporter, in the original's toolchain.

**Primary Dependencies**:

- `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`, `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract` (published locally) with `Routes.affiliateManager` and `Routes.brandsOf`
  added on this branch (research R18);
- the JDK: `javax.crypto.Cipher("AES/GCM/NoPadding")`, `Mac("HmacSHA256")`, `Signature("Ed25519")`,
  `MessageDigest`, `java.net.http.HttpClient`. No cryptography library.

**Storage**: the service's own Postgres (journals, state, views, timers, the secret store). Secrets:
data keys per affiliate, the email digest key, the signing keys, webhook secrets and Customer.io keys
per brand (data-model.md *Secrets*). Project secret: the Keycloak admin credential.

**Testing**: munit; the entity, workflow and consumer kits with `InMemorySecretStore`;
`AnkkaTestKit.start` over Postgres with `InMemoryBroker` carrying two brands' `affiliates.attribution`,
`MovableClock`, `FakeCustomerIo`, `FakeKeycloak`, a `WebhookSender`, a `PostbackReceiver`; `GherkinSuite`
over the ten `features/programme/*.feature`; `contract.port.Recording` for `tests/port/programme/`.

**Target Platform**: the shared `programme` project on the self-hosted installation (kind locally,
GKE). Exposed: public routes (apply, password flows, join, documents, inbound webhooks), the portal, the
operator surface.

**Project Type**: one ankka service (`services/affiliate-programme/`), a Go driver and exporter
(`tests/original/programme-driver/`, `programme-export/`), contract, realm, manifest and tool changes.

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-005 | a fact in revenue within 10 s: one consumer hop, one entity command, one view update |
| SC-006 | a thousand closes with interleaved late facts, no double claim (`CloseSuite`) |
| SC-009 | a postback's last outcome visible within 1 minute of the final attempt |
| Close | a brand's close at 50 accounts a step, each step under the 30 s step timeout |

**Constraints**:

- money is `Long` minor units from the facts; percentages are basis points; RevShare rounds HALF_UP as
  the original; fees are not floored;
- no player id, and no readable name, email or payment details, anywhere the service stores;
- every fact the programme writes has a deterministic id; every command is idempotent on it;
- at most two path parameters per route, four endpoints with no shared prefix (research R12);
- entities call nothing; outbound HTTP only from consumers, workflow steps, timed actions and endpoints;
- a brand here is a consumer component, so adding one is a re-render and a restart (research R3).

**Scale/Scope**:

| Item | Count |
|---|---|
| Original routes ported | 91 of 110 (5 public, 35 portal, 51 operator); 19 not served (research R1) |
| New routes | 17 (manager revoke, close, erasure, export, unattributed, postbacks, 4 documents, legacy withdrawal, inbound, brands ×3, usage, import) |
| Event sourced entities | 5: `Affiliate`, `Membership`, `PlayerAttribution`, `CommissionAccount`, `Payout` |
| Key value entities | 16 (data-model.md) |
| Workflows | 5: `Close`, `Erasure`, `Postback`, `EmailSend`, `Import` |
| Views | 12 |
| Consumers | 1 per brand here, plus `ToLedger`, `Overrides`, `Communications`, `Provisioning` |
| Feature scenarios bound | 89 across 10 features (outlines once per row) |
| Recordings replayed | 8 from attribution's driver (ledger reads), 13 from the programme's driver |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings and parity tests named; divergences declared | Pass. contracts/recording.md names thirteen driver scenarios and the eight attribution recordings whose ledger the programme must reproduce, one parity test each. Sixteen changes listed in the spec, four of them decided on 2026-11-01 from this plan's survey (13–16). |
| II. Money is exact, the wallet is the ledger | `Long` minor units; keyed commands; no balance | Pass. The programme keeps no player's balance; its ledger is of commission, owned here (ADR 0042). Every amount is a `Long` from a fact; every write is keyed by a deterministic fact id. |
| III. A brand is a project | shared project reaches a brand only by its grant; no player id | Pass. The topic through `core`'s grant (R3); brands elsewhere by webhooks to a public route admitted by its HMAC; players as pseudonyms only (FR-038). |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. Facts in by topic or webhook; documents out as public reads; no call into a brand. |
| V. Personal data marked, erasure honoured | | Pass, and beyond the repository's practice: encrypted under a per-affiliate key, erasure destroys it (R9). |
| VI. Scala by default | | Pass. Go only for the driver and the exporter. |
| VII. Specs in the domain's words | `just features` passes; every requirement traced | Pass: 0 findings in 1,167 scenarios. |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (three sessions) and 2026-11-01 (two). |
| Scope: ankka gaps are ankka specs | | Pass. Erasure (042) is not worked around in ankka's place: the service encrypts its own fields, which 042 will later replace (R9). A runtime topic subscription is a finding for ankka, not built here (R3). |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-037: accounts | `Affiliate` (event sourced) with `Sealed` identity; `EmailIndex` | the account's history is the audit; the identity is ciphertext | a key value account (loses history); plaintext with overwrite (SC-007) (R9) |
| FR-002, FR-003, FR-016: memberships | `Membership` (event sourced, `<affiliate>/<brand>`) | transitions are the domain's rules; one entity per standing | status on the account (one brand per account, the original's limit) |
| FR-006, FR-007, FR-040: plans, settings, brands | `Plan`, `BrandProgramme` (key value); `Seed` | configuration edited by managers | the manifest (brands are added at runtime) |
| FR-008, FR-009: facts in | `attribution-<slug>` consumers; the inbound route; `Intake`; `FactInbox` | one claim per fact id on every channel | dedupe in each entity only (a fact can touch several) (R3–R5) |
| FR-010, FR-011, FR-013, FR-038: the player | `PlayerAttribution` (event sourced) | the binding, the guard and the totals are one player's decisions | the original's twins per concern (the race the original admits) (R6) |
| FR-012, FR-014, FR-015, FR-022: the ledger | `CommissionAccount` (event sourced); `ToLedger`, `Overrides` consumers | deltas need the emitted figures consistently; ids make writes idempotent | folding the view on write (lag, the original's `requestId` guard) (R6, R7) |
| FR-017: reads | `Ledger` and the other views | SQL sums over one row per fact are a fold | a cached revenue figure (the original's, FR-017 forbids) |
| FR-018–FR-021: settlement | `Close` workflow, `Payout`, `Unsettled` view, the export route | bounded steps, deterministic payout ids | one handler over every account (step timeout) (R8) |
| FR-023–FR-026: tags and documents | `SigningKeys`, `Link`, `BonusCode`, `Codes`, `Revocations`, `LegacyTags`; `ProgrammeEndpoint` | 012's shapes served from views | a per-tag lookup (012 forbids) (R10) |
| FR-027–FR-030: network | `InviteLink`, `Token`, `Accounts` view, the walk in `network` | | |
| FR-031: managers | `KeycloakAdmin`; `Provisioning` consumer | as `merchant` | |
| FR-032–FR-034: flags, notes, creatives, messages | key value entities, the `Activity` view | as the original | |
| FR-035, FR-036: communications | `Communications` consumer, `EmailSend` and `Postback` workflows, `PostbackOutcome` | a send never blocks; a retry survives restarts | a timer per attempt (R13) |
| FR-039: import | `Import` workflow, the import route, the Go exporter | deterministic ids make it repeatable | (R19) |
| FR-041, FR-042: hosted brands | `brandsOf` filtering; usage from `Ledger`, `Inbox`, `Memberships` | computed on read | a monthly timer (no calendar timers, none needed) (R15) |

## Project Structure

### Documentation (this feature)

```text
specs/013-affiliate-programme/
├── plan.md              # this file
├── research.md          # R1–R20, corrections, findings
├── data-model.md        # entities, workflows, views, consumers, timers, secrets, pure packages
├── quickstart.md        # proving it, in build order
├── contracts/
│   ├── routes.md        # every original route's fate; new routes; refusals; the export
│   ├── inbound.md       # the topic, the webhook, Intake, the documents
│   └── recording.md     # the driver's scenarios, the parity translation, marked divergences
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala   # + affiliateManager(), brandsOf
brands/_realm/eitheror-operators.json                                    # + realm role affiliate_manager
shared/programme/manifest.json                                           # env, secrets
tools/modules/src/main/scala/.../Render.scala                            # + PROGRAMME_BRANDS
services/affiliate-programme/
├── build.sbt, service.json, README.md
├── src/main/scala/io/eitheror/casino/programme/
│   ├── commission/        # pure: NGR, fees, RevShare, override, close
│   ├── periods/, sealing/, tags/, postbacks/, csv/, network/   # pure
│   ├── brands/            # BrandProgramme, Plan, Seed
│   ├── accounts/          # Affiliate, Membership, EmailIndex, Token, Erasure, KeycloakAdmin, Provisioning
│   ├── intake/            # Intake, FactInbox, the per-brand consumers
│   ├── players/           # PlayerAttribution, ClickRecord
│   ├── ledger/            # CommissionAccount, ToLedger, Overrides, Ledger, Unsettled
│   ├── settlement/        # Close, Payout, Payouts
│   ├── links/             # Link, BonusCode, InviteLink, SigningKeys, documents views
│   ├── comms/             # Notification, EmailSend, CustomerIo, Postback, PostbackOutcome, Communications
│   ├── activity/          # FraudFlag, Note, Creative, AuditEntry, Activity
│   ├── imports/           # Import
│   ├── api/               # OperatorEndpoint, PortalEndpoint, JoinEndpoint, ProgrammeEndpoint, Access, Wire
│   ├── ProgrammeCodecs.scala, Settings.scala, ProgrammeService.scala
└── src/test/scala/io/eitheror/casino/programme/
    ├── (pure and kit suites per package), it/ (Kit, fakes, the suites of quickstart §3), port/, features/
tests/original/programme-driver/, tests/original/programme-export/      # Go
tests/port/programme/                                                    # the recordings
.github/workflows/ci.yml, justfile                                        # programme-test, -it, -parity, -run
```

**Structure Decision**: one service directory with a package per capability, the shape of
`services/attribution/`. The outbound seams (`CustomerIo`, `KeycloakAdmin`, `PostbackHttp`) are traits
with an `HttpClient` implementation and kit fakes.

## Phase 0: research

Done: [research.md](research.md). No unknown remains. Two items are verified at task time, each with a
fallback (R20): a suspended membership's CPA in the original, and an ankka start with a brand's
component gone.

## Phase 1: design

- [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

### Constitution re-check after design

Unchanged. One service with its own database; it reads local brands' topics by their grant and a public
route admitted by HMAC; it serves public documents; it calls only Customer.io, Keycloak's admin API and
affiliates' postback addresses. Nothing reads another service's store, nothing holds a player's balance,
and nothing it stores names a player or, readably, an affiliate.

## Divergences (ankka-port, section 6)

Every change 1–16 of the spec, each demonstrated by a marked parity test or, where the original cannot
show it, by the suite named in quickstart §3: hosted brands and webhooks (`IntakeSuite`), encryption
(`LeakSuite`), retried postbacks (`CommunicationsSuite`), signed tags (`DocumentsSuite`), per-currency
ledgers and payouts (`CloseSuite`, recording `close and pay`).

## What the next command needs to know

**Build order:**

1. contract (`Routes.affiliateManager`, `brandsOf`), the realm role, the manifest, `Render`'s
   `PROGRAMME_BRANDS`; `just modules validate --all`;
2. the service skeleton: `ankka init`, `build.sbt`, `Settings`, `ProgrammeCodecs`, the `just` recipes,
   the CI filter and job;
3. the pure packages, `commission` first with the original's cases lifted;
4. the entities with kits, then the workflows;
5. the views, then `Intake`, the per-brand consumers, `ToLedger`, `Overrides`, `Communications`,
   `Provisioning`;
6. the integration kit and its fakes;
7. the endpoints, fail-first on the parity tests where a recording exists;
8. the driver and the recording run (the original's kind stack, affiliate service up);
9. `ParitySuite`, then the suites of quickstart §3;
10. the import's exporter and `ImportSuite` (P3);
11. a kind deploy of `shared/programme` beside `brands/goldvibe`.

**Constraints on that order:** parity tests are written before the components they test and fail first;
the recording run is against a local original only; the import is last (spec 019 sequences its use).

**Elsewhere:** findings for 001/the modules tool, 011, 012, 019 and ankka (research *Findings*).
