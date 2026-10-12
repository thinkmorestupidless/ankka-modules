# Implementation Plan: Game provider simulator

**Branch**: `020-game-provider-simulator` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/020-game-provider-simulator/spec.md`

## Summary

A stand-in for ST8 that a rebuilt brand cannot tell from `fake-st8` in its default scenario and
that, in every other scenario, does what a real provider does when things go wrong. One ankka
service in a new shared project `tools`, reaching a brand from outside through the gateway with
nothing but a signature. A provider core (sessions, rounds, callbacks, a scheduler, scenarios as
data, a seeded generator, the run record) under one ST8 adapter (the operator API, the game page,
the instruction routes, the signed callbacks in the stand-in's and the harness's body shapes).
Every callback is one workflow that retries on the scenario's schedule and records every attempt
on an event-sourced entity; a view makes the run readable and exportable as a recording the brand's
test kit replays. Decisions and what established them are in [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9.0 on the ankka SDK 0.11.0 (constitution VI: the default; the ST8
signing already exists in Scala in the original's adapter tests). sbt 1.12.9, JDK 21 image.

**Primary Dependencies**: `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`,
`ankka-auth-oidc`, `ankka-telemetry-otlp`, `ankka-testkit` (test); `io.eitheror:casino-contract`
(`Routes`, `Recording`, `Layout`); `java.net.http.HttpClient` for the outside path to a brand (R3);
`java.security` ECDSA P-256 (no extra library); Jackson (already in the contract) for recordings;
munit 1.3.6.

**Storage**: the service's own ankka-provisioned Postgres (event journals of `Session` and
`Callback`, the key-value `Scenario`, `Bonus` and `SimulatorStatus`, the `RunCallbacks` view);
the signing key pair in the secret store (ankka 038 changes nothing in the code). No topics.

**Testing**: munit through `ankka-testkit`: `EventSourcedTestKit` / `KeyValueEntityTestKit` for
entities; `AnkkaTestKit.start` with a `ScriptedService` as the brand for workflows, view, timers
and endpoints; `GherkinSuite("features/simulator")` binding the five feature files; parity tests
in `tests/port/simulator/` for the operator-API shapes the original's recordings hold; the
original's Go suite driven against the simulator by hand (quickstart §9).

**Target Platform**: ankka's local mode on a laptop beside a brand (FR-026); the `dev-kind`
installation; pre-production. Never a real-money installation (out of scope; the brands tool
refuses the manifest there).

**Project Type**: one ankka service (web service with an HTML game page), plus a one-line change
to the contract's `Layout` and the shared-manifest schema, plus `shared/tools/manifest.json`.

**Performance Goals**: SC-004: 100 sessions at 10 rounds a minute for 5 minutes (about 17 callbacks
a second at peak, two per round) with the brand answering within spec 002 SC-003's bound at p99;
the simulator's own overhead must not be what the profile measures, so a send is one workflow step
on a virtual thread and the view is read only for reports.

**Constraints**: no platform identity toward a brand (FR-024: JDK client, never `ServiceClient`);
no personal field held (FR-023: player id only; profile answers redacted on record); amounts on the
wire as the provider's decimal strings (constitution II binds the wallet, not a foreign format);
every ACL through `contract.access.Routes` (two classes, FR-027); scenarios, brands and the
catalogue as data because a descriptor mounts no files (R10); every step may run twice, so attempt
recording is idempotent on `(callbackId, attempt)` (R5).

**Scale/Scope**: 1 service, 1 new project, about 20 components (2 event-sourced entities, 4
key-value entities, 4 workflows, 1 view, 1 timed action, 6 endpoints at distinct prefixes), 11
shipped scenarios, 3
games, 29 harness tests as the source of scripted expectations, 1 brand at first (Goldvibe) and
the original (Spinvibe) for FR-026.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings it replays and the parity tests it leaves | Pass: the recordings under *Recordings* replay against a rebuilt brand with the simulator as provider (SC-001, spec 003's kit); the simulator's own parity tests replay the recordings' outbound calls to `fake-st8` (launch, games, bonus) from `tests/port/simulator/` (R11). Divergences named under *Changes*: deterministic ordering where the original is map-ordered, the key document in place of the control route (the control route kept only for the original, R8), no substrate seeding (R8). Three spec corrections made from the source (R7). |
| II. Money is exact, the wallet is the ledger | No balance of its own; minor units where money is ours | Pass: the simulator holds the brand's answered balances as strings for verdicts only; it is a provider speaking the provider's decimal wire, which spec 003 converts (spec clarification 2026-10-08). |
| III. A brand is a project | `tools` is a project; nothing shared by convention; a shared project reaches a brand by grant or, for a public route, as the internet does | Pass under constitution 1.2.0 (amended 2026-10-09 for this spec): `tools` is the fourth shared project with a manifest (R1); it holds the player id a brand passed at launch and nothing else of a player, and reaches only a brand's public provider routes under signature, never a grant and never a platform identity (FR-024). |
| IV. Commands over HTTP, facts over topics | | Pass: no topics; every command is HTTP; no store read across services (the view is the simulator's own). |
| V. Personal data marked, erasure honoured | | Pass: FR-023 holds no personal field; `player_profile` answers are redacted before recording, so there is nothing to mark or erase. The player id alone is what constitution III allows a shared project. |
| VI. Scala by default | | Pass: Scala; no other language. |
| VII. Specs in the domain's words | Scenarios live in `features/`; `just features` passes | Pass: 0 findings, 1016 scenarios, 20 specs (this session, after the clarify pass). |
| Workflow: `just features` before plan | | Pass. |
| Scope: ankka gaps are ankka specs, not workarounds | | Pass with one note: an HTTP client to an arbitrary address is not an ankka gap but a deliberate non-feature (R3), so the JDK client is the right tool, not a workaround; in-cluster reachability of a gateway hostname (R3, R12) is a question for ankka's networking docs if the first deploy fails. |

No violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/020-game-provider-simulator/
├── plan.md              # this file
├── research.md          # Phase 0: decisions R1–R12 and what established them
├── data-model.md        # Phase 1: Game, Session, Round, Callback, Schedule, Scenario, Run, view, LoadProfile, Bonus, keys, status
├── quickstart.md        # Phase 1: how to prove it, locally, against the original, and on dev-kind
├── contracts/
│   ├── st8-wire.md            # bodies, headers, answers, operator API, game page, instruction routes, byte for byte
│   ├── routes.md              # every route and its class (public / operator per brand / operator tools)
│   ├── scenario.schema.json   # the scenario file format (FR-019)
│   ├── run-export.md          # a run as a recording (FR-021)
│   └── brand-config.md        # SIMULATOR_BRANDS and shared/tools/manifest.json
└── tasks.md             # Phase 2 (/speckit-tasks), not created here
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/Layout.scala   # SharedProjects += tools; ReservedSlugs follows
specs/001-brands-and-projects/contracts/shared-manifest.schema.json # enums += tools, game-provider-simulator
specs/001-brands-and-projects/spec.md                               # FR-003: four shared projects (one sentence)
shared/tools/manifest.json                                          # the project, one service, env, expose

services/game-provider-simulator/                                   # ankka init, one sbt build
├── build.sbt  service.json  docker-compose.yml  project/
├── src/main/resources/
│   ├── application.conf                   # simulator.* keys with ${?SIMULATOR_*} substitutions
│   ├── games.json                         # the three games (FR-001)
│   ├── scenarios/<name>.json              # the eleven shipped scenarios (FR-017)
│   └── pages/game.html  session-expired.html
├── src/main/scala/io/eitheror/casino/simulator/
│   ├── Main.scala                         # register components; key at start; sweep timer; registerKeyAt pushes
│   ├── core/
│   │   ├── Session.scala  SessionEntity.scala      # rounds, scenario, cursor, verdicts (R5)
│   │   ├── Callback.scala  CallbackEntity.scala    # attempts, outcome (R5)
│   │   ├── CallbackWorkflow.scala                  # the scheduler: send, decide, pause, end (R4)
│   │   ├── ScenarioPlay.scala                      # walks a scripted scenario's steps for one session
│   │   ├── LoadProfile.scala                       # launch, tick, settle (R4, R9)
│   │   ├── Scenario.scala  ScenarioEntity.scala    # the library as data; schema validation (R10)
│   │   ├── Seeded.scala                            # SplittableRandom per run (R6)
│   │   ├── RunCallbacks.scala                      # the view; by-run query with keyset paging
│   │   ├── Sweep.scala                             # TimedAction: retention (FR-022)
│   │   └── SimulatorStatus.scala
│   ├── st8/
│   │   ├── St8Bodies.scala                         # stub and harness shapes, alphabetical string maps (R7)
│   │   ├── Signer.scala  Keys.scala  KeyRotation.scala  # SHA-256 + ECDSA P-256 ASN.1; secret store; rotation workflow
│   │   ├── OperatorApi.scala                       # prefix /api/operator/v1: launch, games, bonus create/get/cancel (public)
│   │   ├── GamePage.scala                          # prefix /game: the page and check (public)
│   │   ├── Instructions.scala                      # prefix /test: the instruction routes (public), verbatim proxy
│   │   ├── KeyDocument.scala                       # prefix /.well-known: st8-keys (public)
│   │   ├── Health.scala                            # prefix /health: the stand-in's health (public)
│   │   └── Bonus.scala  BonusEntity.scala          # free-round instances
│   ├── brands/
│   │   ├── BrandTable.scala                        # SIMULATOR_BRANDS (contracts/brand-config.md)
│   │   └── Sender.scala                            # JDK HttpClient: sign, post, time, read (R3)
│   └── api/
│       ├── SimulatorEndpoint.scala                 # one endpoint, prefix /v1/simulator: brands/{brand}/... under
│       │                                           #   Routes.operator(brand), scenarios and status under
│       │                                           #   Routes.operator("tools"), each group in its own withAcl
│       ├── Access.scala                            # the three ACL recipes, nothing else declares one
│       └── Export.scala                            # a run as a Recording (contracts/run-export.md)
└── src/test/scala/io/eitheror/casino/simulator/
    ├── core/*Suite.scala  st8/*Suite.scala          # entity and body unit tests; SeamSuite enforces FR-006
    ├── SimulatorTestKit.scala                      # AnkkaTestKit + ScriptedService brand + staff issuer
    ├── steps/*Features.scala                       # GherkinSuite bindings for features/simulator/*.feature
    └── port/OperatorApiParitySuite.scala           # replays tests/port/simulator/*.json

tests/port/simulator/                               # recordings of fake-st8's operator-API answers (from the recordings' outbound lists)
```

**Structure Decision**: one service under `services/` as ankka's tooling expects and spec 001's
plan laid down, depending on the published contract; the core and the ST8 adapter are two packages
in one build, because the seam is a package boundary enforced by the rule in FR-006 (the core names
no route, field or status word), not a second deployable. The three touches outside the service are
the minimum to make `tools` a project the brands tool knows (R1).

## Component table

| Concern | Component | Id | Close alternative, rejected because |
|---|---|---|---|
| A session's rounds, scenario, verdicts | `SessionEntity` (event-sourced) | launch token | key-value: a KV view may skip intermediates; the run is the history |
| One callback's attempts and answers | `CallbackEntity` (event-sourced) | callback id | callbacks inside Session: one view row per source id, no per-callback ordering |
| Walking a scripted scenario, its sends and their retries | `ScenarioPlay` workflow (built in place of a per-callback `CallbackWorkflow`, research R13) | launch token | a TimedAction rescheduling itself: 1 KiB payload, runtime retry schedule on error, no lifecycle |
| A load profile | `LoadProfile` workflow, stopped through `LoadStop` (research R13) | profile id | recurring timer: no stop-then-settle sequence |
| The scenario library | `ScenarioEntity` (key-value) | scenario name | files: no mount in a descriptor; SC-005 needs no release |
| Free-round bonuses | `BonusBook` (key-value, research R13) | `book` | in Session: a bonus spans players and sessions |
| The run, readable | `RunCallbacks` view over `CallbackEntity` | | reading the journal: no ordering or paging |
| Retention | `Sweep` TimedAction on a recurring timer | `sweep-runs` | `expireAfter`: the view row stays; no count |
| Counters | `SimulatorStatus` (key-value) | `status` | |
| Keys | secret store + `Keys` + `KeyRotation` workflow | `keys/*` | a key in config: FR-010 forbids |
| A brand's default scenario | `BrandDefaults` (key-value) | brand slug | in the brand table: an operator sets it at runtime |
| The seam (FR-006) | a package boundary, enforced by `SeamSuite` | | a second sbt module: heavier than one test until a second adapter exists |
| Endpoints | six, at distinct prefixes (`/api/operator/v1`, `/game`, `/test`, `/.well-known`, `/health`, `/v1/simulator`) | | one root endpoint: ankka refuses endpoints sharing a prefix, and `/` is a prefix of everything |

## Implementation notes

What building it changed is research R13: one scenario workflow per session, stops recorded beside
the load profile, callback ids from ticket and step, a seed on the selection, the brand found by the
launch's site, one bonus book.

## Phase 0: research

Done: [research.md](research.md). All Technical Context unknowns resolved (R1–R11); one item to
verify at the first `dev-kind` deploy (in-cluster reachability of a gateway hostname, R3, R12);
three spec corrections made from the original's source (R7).

## Phase 1: design

- [data-model.md](data-model.md): the entities, their fields and rules, the states a session, a
  round, a callback and a load profile move through, the view's rows and queries.
- [contracts/](contracts/): the wire byte for byte, the routes and their classes, the scenario
  schema, the export mapping, the brand table and the `tools` manifest.
- [quickstart.md](quickstart.md): ten proofs, from the unit suites to the original's suite and a
  `dev-kind` deploy, in the order the tasks build them.

### Constitution re-check after design

Unchanged. The design adds one shared project with a manifest, no topic, no grant, no personal
field and no cross-service read. The outside path to a brand is the JDK client by decision, not a
missing platform feature. Complexity Tracking stays empty.

## What the next command needs to know

- **Order**: contract `Layout` + schema + `shared/tools/manifest.json` + spec 001 FR-003 sentence
  first (everything else depends on `publishLocal`); then `ankka init` and the core entities with
  their unit suites; then `St8Bodies` and `Signer` with byte-level tests against
  `contracts/st8-wire.md`; then `Sender`, `OperatorApi`, `GamePage`, `Instructions` and the default
  scenario (US1, the parity floor); then `CallbackWorkflow`, `ScenarioPlay`, the eleven scenario
  files and the verdict (US2); `Bonus` (US3); `RunCallbacks`, `Export`, `Sweep` (US4);
  `LoadProfile` (US5); finally the `dev-kind` deploy and the original's suite (quickstart §9, §10).
- **Each scenario file cites its source** (FR-018): the recording path under `tests/port/` or the
  harness's test function and line from R7; a scenario with neither is not shipped.
- **Byte-level fidelity is a test, not a hope**: one munit suite asserts the exact bytes of every
  `stub`-shape body and every operator-API answer (trailing newline, escaping, sorted keys) against
  literals copied from `contracts/st8-wire.md`.
- **The features' wording to keep in view**: "100 sessions are launched" is the brand's test kit
  launching them while the profile starts (R9); if `/speckit-bdd-features` is rerun, the load
  feature's `Given` may say so.
- **A load profile drives its sessions itself**: the tick issues the instructions (a bet, then a win
  or a loss drawn from the seed) through `Play.instruct`; a scenario with an `on: now` send is
  refused for a profile (`400 scenario_not_instruction_driven`), so no per-session `ScenarioPlay`
  runs under a profile.
- **ACLs**: `Routes.operator(brand)` from the path segment, `Routes.operator("tools")` for the
  library and status; the `eitheror` realm gains role `brand_tools` in `installation/base`
  (`platform_admin` works meanwhile).
- **Spec 003's kit must accept a provider address and a key-document address** for quickstart §3
  and §6; raise it there if it does not yet.
- **The retention sweep's test** moves `TimerRuntime`'s clock; a workflow pause may not follow that
  clock (R6), so scenario delays in tests are milliseconds, from the scenario data.
