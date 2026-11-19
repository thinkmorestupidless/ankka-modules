# Implementation Plan: generated player clients

**Branch**: `031-generated-player-clients` | **Date**: 2026-11-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/031-generated-player-clients/spec.md`

## Summary

Two pull requests.

**The first moves everything to ankka 0.18.0** (story 1): the root build, 22 services, every
module and shared manifest, `local/`, and the tool's suites, in six groups with each group's suites
green before the next; `exchange` and `markets` take ankka-predictions 0.3.0; the modules tool
gains a check that refuses a runtime an installation's platform cannot run. Nothing a caller
observes changes, and no stored type changes. It is raised as `031-ankka-0-18` against `main`; the
second is raised on top of it and merges after it.

**The second describes, checks and generates** (stories 2 to 4). The 79 player routes of nine
services each get a route description; because every one of those routes reads its body as text
and answers bytes it builds itself, every shape is stated in the description, in case classes kept
for that purpose, and held to the handler's real answer by the test kit. Each service keeps
`openapi.player.json`, written by its own build in ankka's document mode and compared byte for byte
in CI. A description check in the contract reads all 590 recordings for calls of player routes and
fails on a status or a field a description does not allow, and on a change to the kept list of
routes no recording calls. A generator in the SDK writes each module's `routes.ts` and `shapes.ts`
from the kept descriptions and a hand-written selection; `client.ts` keeps the mapping and the
interface of spec 027. Spec 027's 24 contract tests against the local brand are the acceptance.

Decisions and evidence are in [research.md](research.md), R1 to R12, with eight things to verify
at task time (V1 to V8).

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.18.0 for the contract, the modules tool and 21
services; TypeScript 5.9 on Node 24 for the SDK and its generator (constitution VI).

**Primary Dependencies**: `ankka-sdk`, `ankka-http` (route descriptions, `ShapeOf`), `ankka-testkit`
(`HeldToDocument`), all 0.18.0; ankka-predictions 0.3.0 for `exchange` and `markets`; Zod in the
SDK. No new dependency in the SDK; `json-schema-validator` in the contract only if V3 fails.

**Storage**: none added. No stored type, serializer or manifest name changes (FR-003).

**Testing**: munit per service (`sbt test`, and `-Dit=true` where CI runs it), with every kit of
the nine held to its description; `port/DescriptionSuite` per service over `tests/port/`; vitest
for the generator and the SDK; `just player-site-it` against the local brand under `ankka local up`
for the 24 contract tests. No kind (FR-025).

**Target Platform**: an installation at platform 0.18.0; the developer's machine with Docker and
the ankka command-line client 0.18.0.

**Project Type**: a platform move across a multi-build repository, test and build machinery in
nine services, a generator in a TypeScript library, and one check in a command-line tool.

**Performance Goals**: none new. `just describe-check` adds one `sbt run` to a service's CI job.

**Constraints**:

- a described route answers byte for byte as before (FR-009): handlers are not touched;
- the repository builds at every commit of the move (FR-005);
- generation reads kept files only: no service, database or broker (FR-023);
- the generated files are committed and compared byte for byte (FR-021);
- each module client's interface is spec 027's, function for function (FR-019).

**Scale/Scope**: 22 service builds; 79 player routes in 9 services; 590 recordings; 27 client rows
in 4 modules.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Holds | How |
|---|---|---|
| I. The running original is the specification | yes | No route's behaviour changes. The descriptions are checked against the original's recordings (R8); the unrecorded routes are named. The service description and the runtime check are new and are under *Changes from the original*. |
| II. Money is exact and the wallet is the ledger | yes | No money path changes. The wallet's player balance stays major-unit text on the wire and an exact amount in the SDK's mapping, which is kept by hand. |
| III. A brand is a project | yes | A description is written for the placeholder brand `the-brand`, and the generated path carries `{brand}`; no brand's name is in a kept file. |
| IV. Commands over HTTP, facts over topics | yes | Nothing moves between the two. |
| V. Personal data is marked | yes | `Described.scala`'s case classes are shapes for a document and store nothing; no `Personal` field changes. The kept descriptions hold field names, never values. |
| VI. Scala by default | yes | The generator is TypeScript because it writes TypeScript for the browser's SDK. |
| VII. Specs in the domain's words | yes | 22 scenarios in three features; `just features` is green with six new terms. |
| Workflow: the plan names the recordings it replays and the parity tests it leaves | yes | Below. |

**Recordings replayed**: every existing parity suite, unchanged, on 0.18.0 (story 1). **Left
behind**: `port/DescriptionSuite.scala` in each of the nine services, reading every recording under
`tests/port/` for calls of that service's player routes; `tests/port/unrecorded-player-routes.txt`.

Post-design re-check: no violation; nothing in *Complexity Tracking*.

## Project Structure

### Documentation (this feature)

```text
specs/031-generated-player-clients/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── move.md              # the six groups, what each touches, its gate
│   ├── descriptions.md      # the nine services' player routes; how one is described and kept
│   ├── description-check.md # DescriptionCheck, the per-service suite, the unrecorded list
│   ├── generation.md        # the selection, the generated files, the rules
│   └── runtime-check.md     # the modules tool's refusal
└── tasks.md
```

### Source Code (repository root)

```text
build.sbt                                   # ankka.version default 0.18.0
contract/src/main/scala/io/eitheror/casino/contract/port/
└── DescriptionCheck.scala                  # new: recordings against a kept description
tools/modules/src/main/scala/io/eitheror/casino/modules/
└── Runtimes.scala                          # new: the runtime check; called from every apply
tools/modules/src/test/…                    # RenderSuite, Spec001RenderSuite, fixtures: 0.18.0
installation/overlays/<name>/settings.env   # PLATFORM_VERSION (V6)

services/<each of 22>/build.sbt             # ankkaVersion 0.18.0; the eviction block removed
services/<each of 22>/service.json          # runtime 0.18.0
modules/*/module.json, shared/*/manifest.json, local/**/service.json   # 0.18.0

services/<each of 9>/
├── describe.env                            # the placeholder environment of document mode
├── openapi.player.json                     # the kept service description
├── src/main/scala/…/api/Described.scala    # the shapes its route descriptions state
├── src/main/scala/…/api/<Endpoints>.scala  # described(…) { … } round each player route
├── src/test/scala/…/it/Kit.scala           # held to the description (wallet: it/Harness.scala)
└── src/test/scala/…/port/DescriptionSuite.scala

tests/port/unrecorded-player-routes.txt     # the kept list

sdk/player/
├── scripts/generate.ts                     # new
├── src/<module>/selection.ts               # new, by hand: the rows and what a description cannot say
├── src/<module>/routes.ts                  # generated
├── src/<module>/shapes.ts                  # generated
├── src/<module>/client.ts                  # by hand: the mapping only
├── src/{engagement,support}/events.ts      # by hand: a stream's events
├── eslint.config.js                        # no zod and no path literal outside generated files
└── tests/unit/generate.test.ts             # the generator's own tests

Justfile                                    # describe, describe-check, descriptions, player-sdk-generate
.github/workflows/ci.yml                    # describe-check in nine jobs; generate:check in player-sdk
CLAUDE.md                                   # the specs table; Working here
```

**Structure Decision**: nothing is restructured. Each service gains four files beside what it has;
the contract gains one file in the package that already holds the recording replayer; the SDK's
module directories keep their file names, so nothing that imports `routes.ts` or `client.ts`
changes its import.

## Phases and gates

| Phase | Pull request | Holds | Gate |
|---|---|---|---|
| A | first | the move, groups 1 to 6 (contracts/move.md) | each group's suites as CI runs them; `just modules validate --all`; `just features` |
| B | first | the runtime check | its unit suite; `RenderSuite` |
| C | first | the local brand on 0.18.0 | `just player-site-it`: 24 of 24 with the hand-written clients, before anything else changes |
| D | second | descriptions, kept files, held kits, one service at a time | that service's suites held; `just describe-check <service>` |
| E | second | the description check and the unrecorded list | nine `DescriptionSuite`s |
| F | second | selections, generator, generated files, the mapping | SDK typecheck, lint, unit, features; `generate:check` |
| G | second | acceptance | `just player-site-it`: 24 of 24 with the generated clients; no behaviour, component or site test changed |

Phase C is the measurement the second pull request is compared with: the same 24 tests, the same
local brand, before and after generation.

## Complexity Tracking

No violation to justify.
