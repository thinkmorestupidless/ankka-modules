# Implementation Plan: the shared audit

**Branch**: `018-audit-and-proposals` | **Date**: 2026-11-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/018-audit-and-proposals/spec.md`

## Summary

`audit` is the one service of the shared `reasoner` project. It reads every brand's `platform.audit`
and `platform.modules`, and `payments`' `platform.audit`, under their grants. It keeps the records of
actions and passes over money events no person caused, which stay in the brand's ledger. It replaces
every person's id with a pseudonym from its registry, digests the ids that would carry one, and stores
each record once, immutably, for its brand's retention. Compliance queries it by brand and across
brands, exports a brand's period as CSV or JSON lines, and re-identifies a pseudonym under a role that
only a brand's compliance officer may hold. Every query, export, re-identification and role grant is a
record of its own.

Eight key value entities, two workflows, four views, a consumer per source, two timed actions.
Decisions and evidence: [research.md](research.md) R1–R17. Eight decisions were made with the person
(spec Clarifications, 2026-11-08 and 2026-11-09).

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.16.0 (constitution VI), as `services/engagement/`.

**Primary Dependencies**:

- `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`, `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract` (published locally) with `Routes.auditCompliance`,
  `auditInstallation`, `reidentifier` and `installationOwner` added on this branch (research R8);
- the JDK: `Mac("HmacSHA256")`, `MessageDigest`, `java.util.UUID`, `java.net.http.HttpClient`.

**Storage**: the service's own Postgres (state, views, timers, the secret store), one bucket
`exports` with signed exposure. Secret: `registry-key`. Project secret: the Keycloak admin credential.

**Testing**: munit; entity and workflow kits; `AnkkaTestKit.start` over Postgres with an
`InMemoryBroker` carrying two brands' and `payments`' topics, `MovableClock`, `FakeKeycloak`, the
in-memory object store; `GherkinSuite` over five `features/audit/*.feature` (all but `records.feature`, bound in the services that write the records);
`contract.port.Recording` for `tests/port/audit/`.

**Target Platform**: the shared `reasoner` project on the self-hosted installation (kind locally,
GKE). Exposed: the compliance routes, behind the staff realm.

**Project Type**: one ankka service (`services/audit/`), plus amendments to the contract, the realm,
the modules tool, the payments manifest, `wallet` and `players`.

**Performance Goals**:

| Criterion | Target | How |
|---|---|---|
| SC-003 | 200 kept records a second per brand, 1,000 passed over, lag under 30 s | one consumer per source; a passed-over record costs a decode and a `SourceState` update |
| SC-004 | first page of a brand's year under 2 s; a month's export under 10 min | keyset paging over `Records`; the export streams in one step |
| SC-006 | after a restore, complete within 10 min | a raised `AUDIT_READ_GENERATION`; storing is idempotent |
| SC-007 | an installation record within 2 min | the modules and players topics are read as any other |

**Constraints**:

- no real id of a person anywhere the service stores, logs or answers but `Identity` (SC-002), so the
  registry's entity ids are keyed digests, and record, correlation and causation ids are digested
  (research R5);
- no route, command or role changes or deletes a record; the sweep is the only removal (SC-008);
- at most two path parameters per route, one endpoint per prefix;
- entities call nothing; outbound HTTP (Keycloak) only from endpoints and timed actions;
- a brand is a consumer component, so adding one is a re-render and a restart (research R2);
- the control plane is not called: no sanctioned way exists in 0.16 (research R7).

**Scale/Scope**:

| Item | Count |
|---|---|
| Original routes ported | 0: the internal query and the CLI are not served (research R1) |
| New routes | 13 across 6 endpoints (contracts/routes.md) |
| Key value entities | 8: `Record`, `Pseudonym`, `Identity`, `Quarantined`, `Retention`, `ExportFormat`, `RoleGrant`, `SourceState` |
| Workflows | 2: `Export`, `Sweep` |
| Views | 4: `Records`, `Quarantine`, `Grants`, `Exports` |
| Consumers | 2 per brand, plus `records-payments` |
| Timed actions | 2: `sweep`, `realm-read` |
| Feature scenarios bound | 37 across 5 features (outlines once per row); `records.feature`'s 7 in the services that write them |
| Recordings replayed | 6 (contracts/recording.md) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings and parity tests named; divergences declared | Pass. contracts/recording.md names six, one parity test each; the original's audit is internal and thin (research R1), and the spec lists every change. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; no balance | Pass. The audit keeps no balance; an amount is a `Long` from the record; money events stay in the wallet's journal (FR-008a). |
| III. A brand is a project | a shared project reaches a brand only by its grant | Pass. `core`'s grants of both topics; `payments`' grant added; `reasoner` holds the player id and nothing else that identifies a player (spec 001 FR-018), in `Identity` alone. |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. Facts in by topic only; no call into a brand. |
| V. Personal data marked, erasure honoured | | Pass. No `Personal` field is held: a record carries none (FR-003) and a carried value is quarantined; ids are not personal data (spec 001 FR-024); FR-023 keeps the record through an erasure. |
| VI. Scala by default | | Pass. |
| VII. Specs in the domain's words | `just features` passes; every requirement traced | Pass: 0 findings in 1,200 scenarios. |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (three sessions), 2026-10-12, 2026-11-08, 2026-11-09. |
| Scope: ankka gaps are ankka specs | | Pass. The control plane's events and view indexes are findings for ankka (research *Findings*), not worked round: FR-019's missing sources are reported "not stated", and the store holds only actions. |

No violations; Complexity Tracking is empty.

FR-025 (the brand's own readers) and FR-026 (no audit in the support agent's tools) need nothing built
here: `players` and `reporting` read the brand's topic (specs 008, 015), and spec 017 builds the agent.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-007, FR-012: reading | `records-<source>`, `modules-<slug>` consumers over bytes | a bad message is quarantined, not stuck | decoding in the source (a bad message stops a brand) (R2) |
| FR-008a: what is kept | pure `Keep` | a rule over three fields | a filter per brand service (every service changed) (R3) |
| FR-008, FR-009: pseudonyms | `Pseudonym`, `Identity` | the entity serialises first sightings; no real id in an entity id | a digest alone (cannot answer "who is this") (spec Clarification 2026-10-08) |
| FR-010, FR-011: the store | `Record` (key value), `Records` view | written once, deleted only by the sweep; a view cannot be written from outside | own tables (undocumented) (R4) |
| FR-013, FR-015: queries | `RecordsEndpoint` over `Records` | keyset paging; each query a `Record` of its own | |
| FR-014: exports | `Export` workflow, the bucket | one streamed step, a signed URL | a page per step (a file in parts) (R10) |
| FR-016: roles | `RolesEndpoint`, `KeycloakAdmin`, `RoleGrant`, `realm-read` | a grant is a record because it is made here | Keycloak's admin events (retention, a realm setting) (R8) |
| FR-017, FR-018: re-identification | `IdentitiesEndpoint` over `Identity` and `Pseudonym` | | |
| FR-019, FR-020: installation records | `modules-<slug>`; players' erasure records; `Stored` visibility | what ankka 0.16 lets a service read | the control plane (person-only) (R7) |
| FR-021: retention | `Retention`, `sweep`, `Sweep` | bounded steps with a cursor | entity expiry (cannot be raised after the fact) |
| FR-022: restore | `AUDIT_READ_GENERATION` | ankka re-reads only under a new version | (R14) |
| FR-024: status | `SourceState`, `Quarantine`, `Exports`, `StatusEndpoint` | | |

## Project Structure

### Documentation (this feature)

```text
specs/018-audit-and-proposals/
├── plan.md              # this file
├── research.md          # R1–R17, findings
├── data-model.md        # entities, workflows, views, consumers, timers, secrets, settings, pure packages
├── quickstart.md        # proving it, in build order
├── contracts/
│   ├── routes.md        # every route, its role, its refusals and its record
│   ├── ingest.md        # the sources, one message in order, visibility, module changes
│   └── recording.md     # the six recordings, the parity tests, marked divergences
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala   # + auditCompliance, auditInstallation, reidentifier, installationOwner
brands/_realm/eitheror-operators.json                                    # + audit_compliance
tools/modules/src/main/scala/.../Realm.scala, Render.scala               # + reidentify_<slug>; AUDIT_BRANDS
shared/payments/manifest.json, shared/reasoner/manifest.json             # the grant; env, bucket, secret
services/wallet/src/main/scala/.../publish/Facts.scala                   # amounts, causation, balances on records
services/players/src/main/scala/.../erasure/                              # a record per erasure state
services/audit/
├── build.sbt, service.json, README.md
├── src/main/scala/io/eitheror/casino/audit/
│   ├── keep/, ids/, stored/, query/, export/, retention/   # pure
│   ├── store/         # Record, Records
│   ├── registry/      # Pseudonym, Identity
│   ├── ingest/        # the consumers, Quarantined, Quarantine, SourceState
│   ├── exports/       # Export, ExportFormat, Exports
│   ├── sweep/         # Retention, Sweep, the timed action
│   ├── roles/         # RoleGrant, Grants, KeycloakAdmin, the realm read
│   ├── api/           # RecordsEndpoint, ExportsEndpoint, IdentitiesEndpoint, RolesEndpoint, RetentionEndpoint, StatusEndpoint, Wire
│   ├── AuditCodecs.scala, Settings.scala, AuditService.scala
└── src/test/scala/io/eitheror/casino/audit/
    ├── (pure and kit suites per package), it/ (Kit, FakeKeycloak, the suites of quickstart §3), port/, features/, perf/
tests/port/audit/                                                        # the recordings
.github/workflows/ci.yml, justfile                                        # audit-test, -it, -perf, -run
```

**Structure Decision**: one service directory with a package per capability, the shape of
`services/affiliate-programme/`. The outbound seam (`KeycloakAdmin`) is a trait with an `HttpClient`
implementation and a kit fake.

## Phase 0: research

Done: [research.md](research.md). No unknown remains. Two items are verified at task time, each with a
fallback: a key value entity's deletion reaching its view (R4), and what the wallet's events carry
for `records.feature`'s balances (R15).

## Phase 1: design

- [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

### Constitution re-check after design

Unchanged. One service with its own database; it reads three kinds of topic by their grants; it calls
only Keycloak's admin API. Nothing reads another service's store, nothing holds a balance, and
nothing it stores names a person by a real id but `Identity`.

## Divergences (ankka-port, section 6)

Every change of the spec's *Changes from the original*, each shown by a marked parity test or the
suite quickstart §3 names: order and paging (`ParitySuite` rows 2–5), who the actor is (row 1),
pseudonymised ids beyond the actor (`LeakSuite`), queries recorded (`QuerySuite`), platform actions
(`InstallationSuite`), retention (`DurabilitySuite`).

## What the next command needs to know

**Build order:**

1. contract (`Routes`), the realm role, the modules tool (`AUDIT_BRANDS`, `reidentify_<slug>`), the
   manifests; `just test`, `just modules validate --all`;
2. the neighbours: the wallet's and players' records, and `records.feature` bound in wallet,
   responsible-gambling and players;
3. the service skeleton: `ankka init`, `build.sbt`, `Settings`, `AuditCodecs`, the `just` recipes, the
   CI filter and job;
4. the pure packages, `stored` with its no-real-id property first;
5. the entities with kits, then the views, then the consumers;
6. the integration kit and its fakes; `IngestSuite` and `LeakSuite`;
7. the endpoints, fail-first on the parity tests;
8. the workflows and timed actions; `ExportSuite`, `DurabilitySuite`, `RolesSuite`;
9. the recordings (a local original where it can start), `ParitySuite`;
10. `GherkinSuite` over the five features; the performance run and `PERF.md`;
11. a kind deploy of `shared/reasoner` beside `goldvibe`.

**Constraints on that order:** parity tests are written before the components they test and fail
first; the recording run is against a local original only; kubectl only with `--context kind-…`.

**Elsewhere:** findings for ankka, 001 and 008 (research *Findings*).
