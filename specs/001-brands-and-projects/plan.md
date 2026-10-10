# Implementation Plan: Brands and Projects

**Branch**: `001-brands-and-projects` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-brands-and-projects/spec.md`

## Summary

A brand is one ankka project created from one manifest. ankka has no project manifest, so this
feature builds the one thing every later spec deploys into: a manifest format and an apply tool on
the control-plane API that creates the project, declares the contract's topics, renders and applies
one descriptor per service at its pinned version, makes and checks the grants, creates the brand's
realm, and refuses a real-money manifest until the installation is fit. Beside the tool, a shared
`contract` library makes the topic contract, money, route classes and personal-data markers a
compile-time agreement; `installation/` holds eitheror's own ankka installations; and
`tests/port/` plus the seven PSP stubs make the original recordable so every service spec after
this one can be held to it. Decisions and what established them are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9 on the ankka SDK (`contract`, `tools/brands`); Go 1.22+ for the
seven PSP stubs and the recording transport, because they run in the original's toolchain
(research R10, constitution VI reason given). TypeScript services (005, 016) are other specs'.

**Primary Dependencies**: `com.thinkmorestupidless` `ankka-sdk`, `ankka-auth-oidc`,
`ankka-testkit`, `ankka-controlplane-api`; ankka at the version this repository pins, with 040,
042 and 043 built against their `-impl` branches until released (R5, R7). Keycloak 24 for the
casino's realms. The `ankka` CLI and the `ankka@ankka` plugin for the local loop.

**Storage**: none of this feature's own. Each service gets its CNPG database from ankka; `kyc` and
`reporting` get a bucket (R12). The manifest, schemas and recordings are files in this repository.

**Testing**: munit through `ankka-testkit` for the contract and the tool (unit, plus an
integration suite against a kind installation); `GherkinSuite("features")` binding
`features/brands/*.feature` and `features/wallet/money.feature`; Go tests for the stubs and the
recording transport; the original's Go suite as the recorder.

**Target Platform**: a self-hosted ankka installation on GKE behind Cloud Armor (`gke` overlay);
kind for development (`dev-kind` overlay).

**Project Type**: platform foundation: one library, one CLI tool, installation configuration,
test tooling. No user-facing surface of its own.

**Performance Goals**: SC-001: a brand created from its manifest in under 15 minutes on a local
installation, every service ready; a second brand in the same time with the first untouched.

**Constraints**: FR-040 refuses real money before anything is created; a slug is at most 42
characters (R2); a brand's services never share a database; no grant carries `decrypt` (R7).

**Scale/Scope**: 12 brand services, 10 brand topics plus `payments.merchants`, 3 shared projects,
2 brands at first (Goldvibe pilot, then Spinvibe by migration), 1 installation.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names how the original is recorded and what the parity tests are | Pass: R10; the recording transport, stub set and `tests/port/` layout are this feature's deliverables. 001 has no recording of its own to replay: it is the shape, not a ported service. The parity tests it leaves are the contract's schema conformance suite and the `features/brands` and `features/wallet/money` scenarios bound in `GherkinSuite`. Two findings against the original are recorded for spec 002 (no idempotency keys) and corrected in this spec (#662 was an int32 overflow). |
| II. Money is exact, the wallet is the ledger | Amounts are `Long` minor units; every money command carries a key | Pass: `contract.money` (R4); FR-026/027 |
| III. A brand is a project | One project per brand; shared projects reach a brand only by grant | Pass: R1, R2, R7; reserved slugs include ankka's |
| IV. Commands over HTTP, facts over topics | The topic contract is declared and versioned; no store is read across services | Pass: R3, R4; `ce-type` carries the family, one contract per topic |
| V. Personal data marked, erasure honoured | `Personal` fields under the player's subject; erasure handler per service | Pass with dependency: R5; built against 042's branch, no interim |
| VI. Scala by default, reason for anything else | Go stubs and transport | Pass: reason given in R10 |
| VII. Specs in the domain's words | Scenarios live in `features/`; `just features` passes | Pass: 0 findings, 1017 scenarios |
| Workflow: `just features` before plan | | Pass (this session) |
| Scope: ankka gaps are ankka specs, not workarounds | No platform feature rebuilt here | Pass: R5, R8 compute readiness from status rather than building a gate; R1's tool composes the control-plane API rather than replacing it |

No violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-brands-and-projects/
├── plan.md              # this file
├── research.md          # Phase 0: decisions R1–R13 and what established them
├── data-model.md        # Phase 1: Brand, Manifest, Topic, Family, Grant, Realm, Route class, Recording
├── quickstart.md        # Phase 1: how to prove it end to end on kind
├── contracts/
│   ├── manifest.schema.json   # the manifest's JSON schema
│   ├── topics.md              # the eleven topics, families, keys, retention, field names kept
│   ├── routes.md              # the six route classes, the address scheme, the ACL recipes
│   ├── grants.md              # the grants a brand makes and receives
│   └── recording.md           # the recording file, the port layout, parity test naming
└── tasks.md             # Phase 2 (/speckit-tasks), not created here
```

### Source Code (repository root)

```text
build.sbt                      # aggregates contract and tools/brands only
project/
contract/                      # io.eitheror:casino-contract, published locally for services
├── src/main/scala/io/eitheror/casino/contract/
│   ├── facts/                 # one object per topic: families as case classes + serializers
│   ├── money/                 # Money, Currency, Key
│   ├── access/                # Routes.public|player|operator|affiliate|partner|service
│   └── personal/              # PlayerSubject, the FR-023 field types over Personal[A]
├── schemas/                   # generated JSON schemas, committed: <topic>.v1.json
└── src/test/scala/            # schema conformance, Money decoding, ACL decisions, GherkinSuite
tools/brands/                  # the manifest tool (Scala CLI on ankka-controlplane-api)
├── src/main/scala/io/eitheror/casino/brands/
│   ├── Manifest.scala         # model + JSON schema validation
│   ├── Readiness.scala        # FR-040: conditions, sources, verdict
│   ├── Render.scala           # manifest -> service descriptors, topic declarations, grants
│   ├── Apply.scala            # ordered, idempotent apply with diff
│   ├── Status.scala           # brand status: readiness, services, topics, grants, realm
│   └── Realm.scala            # casino-<slug> from the template; brand_<slug> role
└── src/test/scala/            # unit tests per file; integration suite against kind
brands/
├── _realm/casino-template.json
└── goldvibe/manifest.json
shared/                        # the three shared projects, applied by the same tool (FR-003)
├── payments/manifest.json
├── programme/manifest.json
└── reasoner/manifest.json
installation/
├── base/                      # the casino's Keycloak, platform settings, Cloud Armor policy
└── overlays/{dev-kind,gke}/
tests/
├── port/<service>/            # recordings (R10), one JSON per scenario, suite result beside
├── stubs/<psp>/               # Go stubs: bouncemoney cozeni dreamzpay eupaymentz gigadat morphosis t365
└── original/                  # compose override + the recording RoundTripper patch for tests/e2e
services/<name>/               # one sbt build per service, created by later specs with ankka init
```

**Structure Decision**: a small aggregate build at the root for the two things every service
depends on (`contract`) or is deployed by (`tools/brands`); services stay one-per-directory under
`services/` as ankka's tooling expects, depending on the published contract artifact (R4). The
original is never vendored; `tests/original/` holds only what runs beside it.

## Phase 0: research

Done: [research.md](research.md). All Technical Context unknowns resolved (R1–R13), one item left
to verify at the first apply (dotted topic names, R3), one spec correction made (#662, R10).

## Phase 1: design

- [data-model.md](data-model.md): entities, fields, rules and the states a manifest apply, a grant
  and a recording move through.
- [contracts/](contracts/): the manifest schema, the topic contract with families and field
  names, the route classes and ACL recipes, the grant list, the recording format.
- [quickstart.md](quickstart.md): the end-to-end proof on kind, in the order the tasks will build it.

### Constitution re-check after design

Unchanged: the design adds no store, no service, no cross-service read, no platform feature. The
one new dependency is on three unreleased ankka specs (040, 042, 043), which the tool reports as
"platform lacks" rather than works around, and which FR-040 already gates real money on.

## What the next command needs to know

- `/speckit-tasks` should order work so that nothing waits on 040/042/043 unnecessarily: manifest
  model and validation, readiness, render, apply for project/topics/services/expose, the contract's
  `facts`, `money` and `access`, the installation overlays, the stubs and the recording transport
  all build against released ankka; grants, personal fields and retention settings come last and
  compile against the `-impl` branches.
- Install the `ankka@ankka` plugin before Phase 1 recording (R13).
- Spec 002 must add the idempotency-key divergence under *Changes from the original* (R10); spec
  001 now lists it among the original's known defects.
- The shared projects (`payments`, `programme`, `reasoner`) are created by the same tool from
  `shared/<project>/manifest.json` (FR-003); their services' images come from specs 004–006, 013
  and 018, so those manifests start with the project, topics and grants only.
- FR-033 (the Playwright suite against a rebuilt brand) cannot pass until every service exists; its
  scenario is bound as pending until spec 019's rehearsal.
