# Implementation Plan: public-api

**Branch**: `016-public-api` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/016-public-api/spec.md`

## Summary

`public-api` is a brand's service from the `public-api` module. It serves the original's REST API
(its specs 255 and 256) at the original's 22 operations, held to the original's OpenAPI document. The
only amendments are to the credential: the authentication guide, the security scheme and the
authentication codes.

A caller presents an access key that an operator of the brand created from the backoffice. The key
names its own entity, so each request checks it, counts it and records it consistently. A disabled,
deleted or expired key is refused from the next request.

Records come from the brand's topics, through one consumer per topic into a key value entity per
record, with a keyed view over each. Statistics are computed hourly from those views into two-slot
snapshots, as the original's job does. Every request and every key change is an audit record, and so
is each player whose identifying fields a request served, so the shared audit answers a subject
access request by the player.

Decisions and evidence are in [research.md](research.md), R1–R16.

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka **0.16.0** (R1), the contract evicted to it as specs 014,
015 and 018 do. Go for the recording driver, in the original's toolchain.

**Primary Dependencies**:

- ankka: `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`, `ankka-telemetry-otlp`,
  `ankka-testkit`, all 0.16.0;
- `io.eitheror:casino-contract`;
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`, at ankka's Jackson version (R2);
- `com.networknt:json-schema-validator`, as `services/game-provider-simulator` (R2);
- `io.swagger.parser.v3:swagger-parser`, tests only (R2).

No GraphQL library: the original's API is no longer GraphQL (R2).

**Storage**: the service's own Postgres (journals, state, views, timers). No object storage.

**Testing**: munit; the entity, consumer and timer kits; `AnkkaTestKit` over Postgres with
`InMemoryBroker` and `MovableClock`; `FactSet`; `GherkinSuite` over `features/public-api/`;
`contract.port.Recording` for `tests/port/public-api/`; `StatsPerfSuite`.

**Target Platform**: a brand project on the self-hosted installation (kind locally, GKE), from
`modules/public-api`, at the brand's own public API hostname.

**Project Type**: one ankka service (`services/public-api/`), a Go driver, and amendments to the
contract library, spec 001, spec 021, the realm and the modules.

**Performance Goals**:

| Criterion | Target | How |
|---|---|---|
| SC-002 | by id < 500 ms p95; full page < 1 s p95 | one `get`; one `ordered` and one `count` on an index (R7) |
| SC-003 | fact visible < 30 s p95 | consumer → entity → view, as reporting |
| SC-005 | refused on the next request | `AccessKey` read on every request (R4) |
| SC-006 | no cross-key slowdown | one `KeyWindow` and one `KeyLog` per key (R5, R6) |
| SC-008 | hourly run < 5 min at 1 M rounds | one stream per view, in-memory fold (R8) |

**Constraints**:

- the served contract differs from the original's only at the paths in contracts/amendments.md;
- `Long` minor units inside; a decimal string with two decimals only at the edge (R12);
- no read of another service's store; the catalogue is `game-provider`'s route (R9);
- at most two path parameters per route; one endpoint per prefix (R1, R3);
- no key, digest or identifying value in any log, trace, metric, error or audit record;
- every request's deadline is five seconds (R11).

**Scale/Scope**:

| Item | Count |
|---|---|
| Contract operations served | 22, plus `/v1/docs` and `/health` |
| Operator routes | 7 key routes, 1 status route, 1 erasure route |
| Event sourced entities | 2: `AccessKey`, `KeyLog` |
| Key value entities | 12: six records, `BetsByRoundKey`, `WithdrawalsByRequest`, `KeyWindow`, `StatsPointer`, `PlayerFigures`, `GameFigures` |
| Views | 10: six records, `AccessKeys`, `KeyUse`, `PlayerFiguresView`, `GameFiguresView` |
| Consumers | 6 intake, 1 audit |
| Timers | 1: `compute-stats` |
| Feature files | 8 under `features/public-api/`, 51 scenarios |
| Recordings | 11 (contracts/recording.md) |
| Producers amended | 4: `players`, `game-provider`, `wallet`, `rewards` (contracts/facts.md) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings and parity tests named; divergences declared | Pass. The spec was rewritten against the running original's REST API (its 255 and 256), not the GraphQL API it retired. contracts/recording.md names 11 recordings, their parity tests and the original's suites one for one. Every divergence is under the spec's *Changes from the original*. |
| II. Money is exact, the wallet is the ledger | `Long`; no balance kept | Pass. Nothing here moves or holds money. Figures are folds of facts, converted to the contract's decimal string only when they are served. |
| III. A brand is a project | in-project topics; `{brand}` in routes | Pass, with the public-route case. Operator routes keep `{brand}`. The contract's `/v1` paths carry none (FR-001), and they are admitted by a key that exists only in this brand's project: the "admitted by the route's own key" clause. |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. Records and figures come from the brand's topics into this service's views. The catalogue is read by calling `game-provider`'s route, not its store. |
| V. Personal data marked, erasure honoured | `Personal`; subject prefix; handler | Pass. Identifying fields are `Personal` under `player/<id>`, with the erasure route and handler. Figures and audit records carry none. |
| VI. Scala by default | | Pass. Scala; the TypeScript of the 2026-10-08 draft was dropped for the reason spec 005 R1 gives. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1,233 scenarios. |
| Workflow: clarify before plan | | Pass: clarified 2026-11-11 and 2026-11-12. |
| Scope: ankka gaps are ankka specs | | Pass. No publish from an endpoint, no alerting and no statement time per query: each is built from existing primitives here and listed under *Findings for other specs* (research). |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001–FR-006: the contract | `ContractEndpoint` (`/v1/openapi.json`, `/v1/docs`, `/v1/contract`), `Contract` (loaded at start) | the committed file is the contract | a document derived from code (R2) |
| FR-015–FR-021: keys | `AccessKey` (ES), `AccessKeys` view, `KeysEndpoint` | events give the audit and the list; the key id in the key gives one consistent read | a digest-keyed index (eventual; R4) |
| FR-018, FR-019: admission | `Admission` in the `/v1` handler | refusals shaped as the contract says | an ACL (shaped by ankka; R3) |
| FR-034: rate, last use | `KeyWindow` (KV), `KeyUse` view | counts across instances | in-process counters |
| FR-036, FR-037, FR-021: audit | `KeyLog` (ES), `PublicApiAudit` consumer (`AuditConsumer`) | only a consumer publishes | logs (R6) |
| FR-007–FR-012: records | six intake consumers → six record entities (KV) → six keyed views | a row per record, applied once by fact id | topic views (one row per subject; R7) |
| FR-013, FR-014: paging | `ordered` + `count` on `(parent, time, id)` | stable under appends; ties broken | keyset cursors (the contract is offset) |
| FR-022: isolation | the project; `Admission` | the brand is the project's | — |
| FR-023–FR-026: identifying fields | `Personal` in `PlayerRecord`; `Fields.serve(key, player)` | fields absent unless allowed and present | masking (FR-024 says absent) |
| FR-027: games | `Catalogue` cache over `game-provider`'s route | the owner's answer within five minutes | a catalogue topic (R9) |
| FR-028–FR-033: statistics | `compute-stats` timer, `StatsRun`, `StatsPointer`, `PlayerFigures`, `GameFigures` and their views | two slots, as the original | a workflow (nothing to recover; R8) |
| FR-035: budget | a deadline around each `/v1` handler | — | — |

## Project Structure

### Documentation (this feature)

```text
specs/016-public-api/
├── plan.md              # this file
├── research.md          # R1–R16, corrections, findings
├── data-model.md        # entities, views, consumers, the run
├── quickstart.md        # proving it, in build order
├── contracts/
│   ├── original/public-api-v1.openapi.yaml   # the original's contract, verbatim
│   ├── facts.md         # facts read, the four producers' amendments, the audit records published
│   ├── amendments.md    # the JSON paths the served contract may change, and why /v1
│   ├── routes.md        # every route, admission, pipeline
│   ├── fields.md        # contract field → fact, enum maps, ids, gaps, the catalogue route
│   └── recording.md     # recordings, parity tests, the original's suites one for one
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala   # partner class removed; AccessKeysRole
contract/src/main/scala/io/eitheror/casino/contract/facts/                # facts.md G1–G7 (round_opened and optional fields)
contract/schemas/                                                         # regenerated
services/players/        # G1, G2: language, stateProvince on registration and profile facts
services/game-provider/  # G3 round_opened and openedAt; G7 real stake and win; G8 the catalogue route
services/wallet/         # G4 gameRoundId and sessionId on the round's movements
services/rewards/        # G5 expiresAt; G6 free-spins value and currency
brands/_realm/eitheror-operators.json                                     # role api_keys
modules/public-api/module.json                                            # replaces spec 021's `partner`
services/public-api/
├── build.sbt, service.json, docker-compose.yml, README.md
├── src/main/resources/public-api-v1.openapi.yaml, reference.html
├── src/main/scala/io/eitheror/casino/publicapi/
│   ├── contract/      # Contract (load, serve), the refusal shapes
│   ├── keys/          # AccessKey, KeyWindow, KeyLog, AccessKeys, KeyUse, RawKeys
│   ├── records/       # the six record entities, views, intake consumers, FactId
│   ├── wire/          # Money, Times, Enums, the record mappers, Fields
│   ├── games/         # Catalogue, the cache
│   ├── stats/         # StatsRun (pure fold), StatsPointer, PlayerFigures, GameFigures, ComputeStats
│   ├── audit/         # PublicApiAudit
│   ├── api/           # V1Endpoint, Admission, Pipeline, KeysEndpoint, StatusEndpoint, Erasure, HealthEndpoint
│   └── PublicApiCodecs.scala, Settings.scala, PublicApiService.scala
└── src/test/scala/io/eitheror/casino/publicapi/
    ├── (pure and kit suites per package), it/ (Kit, FactSet, contracts/recording.md's suites), perf/, port/, features/
tests/original/public-api-driver/                                        # Go
tests/port/public-api/                                                   # the recordings
.github/workflows/ci.yml, justfile                                       # public-api-test, -it, -perf, -record, -parity, -run
```

**Structure Decision**: one service directory with a package per layer, the shape of
`services/reporting/`.

## Phase 0: research

Done: [research.md](research.md). No unknown remains. Five items are verified at task time, each with
a fallback (R16).

## Phase 1: design

[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

### Constitution re-check after design

Unchanged. One service with its own database. It reads the brand's topics, calls one owner for the
catalogue, and writes nothing but its own records, figures, keys and audit facts.

## Divergences (ankka-port, section 6)

Every entry under the spec's *Changes from the original* is shown by a marked parity test or, where
the original cannot show it, by a quickstart §3 suite:

- keys for tokens: `AdmissionSuite`, `KeysSuite`, the class rule in contracts/recording.md;
- no agreement condition: `IdentifyingFieldsSuite`;
- records from topics, statistics computed here: every parity suite (same bodies from facts);
- rate across instances: `QuotaSuite`;
- tie-break by id: `PagingSuite`;
- immediate revocation: `KeyChangeSuite`;
- the shared audit: `AuditSuite`, `KeyAuditSuite`.

## What the next command needs to know

**Build order**:

1. the contract library: `Routes` (partner out, `AccessKeysRole` in), contracts/facts.md G1–G7,
   schemas regenerated; the realm role; spec 001 and 021 amendments;
2. the four producers: G1–G8, each with its own tests;
3. the service's foundation: build, settings, the contract loaded and served, `/health`, the refusal
   shapes, the deadline;
4. User Story 1: keys (the entity, the routes, admission);
5. User Story 2: isolation, the pipeline's order;
6. User Story 3: records (intake, entities, views, mappers, paging);
7. User Story 4: identifying fields and erasure;
8. User Story 8: rate, `KeyLog`, the audit consumer;
9. User Story 5: catalogue and game statistics;
10. User Story 6: player statistics and snapshots;
11. User Story 7: the contract suites;
12. the module, the driver, the recordings, the parity tests and CI.

**Do not**:

- run two sbt processes in one service directory;
- use another worktree's contract snapshot (use a unique `CASINO_VERSION`);
- record against anything but a local original on kind;
- edit `contracts/original/public-api-v1.openapi.yaml`.
