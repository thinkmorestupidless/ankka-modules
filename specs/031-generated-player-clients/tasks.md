# Tasks: generated player clients

**Input**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

**Tests**: required. The 22 scenarios of `features/brands/platform-version.feature`,
`features/brands/service-descriptions.feature` and `features/player-site/generated-clients.feature`
are bound; every existing suite stays green with no expectation changed; spec 027's 24 contract
tests are the acceptance.

**Order**: the plan's phases A to G. Story 1 (the move) is the first pull request and blocks
everything; stories 2 and 3 are built service by service together, because a route description is
written and held in the same sitting; story 4 last.

**Two pull requests**: phases 1 to 3 are raised as `031-ankka-0-18` (T030); phases 4 to 7 stay on
`031-generated-player-clients`, raised on top of it.

Paths: `Sv` = `services`, `K` = `sdk/player/src`, `KT` = `sdk/player/tests`, `MT` =
`tools/modules/src`, `A` = `~/Documents/projects/thinkmorestupidless/ankka` (read only, at the tag
`v0.18.0`). A service's suites "as CI runs them" are the commands of its job in
`.github/workflows/ci.yml`.

No kind installation is used by any task (FR-025). What runs locally runs under `ankka local up`.

## Phase 1: Setup

- [ ] T001 Upgrade the ankka command-line client to 0.18.0 (`brew upgrade thinkmorestupidless/tap/ankka`; `ankka version` prints 0.18.0) and record under "Found during implementation" in `specs/031-generated-player-clients/research.md` the version installed; if the tap has no 0.18.0, stop and ask
- [ ] T002 Baseline on `main`'s tree, before any change: run `sbt test` at the root and record the pass counts per suite in `specs/031-generated-player-clients/research.md` ("Baseline"), so SC-002 has a number to compare with; record for each of the 22 services the commands its CI job runs (from `.github/workflows/ci.yml`)
- [ ] T003 [P] Read the diff a service made when it moved from 0.13.0 to a 0.16 version (`git log --follow -p Sv/players/build.sbt` and the commit of PR #38) and list in `specs/031-generated-player-clients/research.md` ("The 0.13 to 0.16 changes, from players") every kind of source change it needed, as the checklist for T019 to T024

## Phase 2: User Story 1 — every service on ankka 0.18.0 (Priority: P1) 🎯 MVP

**Goal**: one ankka version in the repository; nothing a caller observes changes.

**Independent Test**: quickstart.md steps 1 to 3.

**Rules for every task T005 to T024** (`contracts/move.md`): set `val ankkaVersion = "0.18.0"` in the service's `build.sbt` and `"runtime": "0.18.0"` in its `service.json`; remove the `libraryDependencySchemes … VersionScheme.Always` block and its comment where present; change no expectation under `src/test`, no stored type, no serializer, no manifest name (a forced change is a finding: stop and ask); run the service's suites as CI runs them, green, before the task is done; commit per service or per group.

### Group 1: the root build

- [ ] T004 [US1] `build.sbt`: the default of `ankka.version` becomes `0.18.0`; fix what the contract, `provider-core` and the modules tool need to compile on it; `sbt test`, `sbt "modules/run validate --all"` and `sbt contract/schemas` (no diff) green. Fixtures and suites that still name an earlier runtime for a service not yet moved stay as they are until T025

### Group 2: the nine on 0.16.3

- [ ] T005 [P] [US1] Move `Sv/wallet` to 0.18.0 (`Sv/wallet/build.sbt`, `Sv/wallet/service.json`)
- [ ] T006 [P] [US1] Move `Sv/players` to 0.18.0 (`Sv/players/build.sbt`, `Sv/players/service.json`)
- [ ] T007 [P] [US1] Move `Sv/kyc` to 0.18.0 (`Sv/kyc/build.sbt`, `Sv/kyc/service.json`)
- [ ] T008 [P] [US1] Move `Sv/responsible-gambling` to 0.18.0 (`Sv/responsible-gambling/build.sbt`, `Sv/responsible-gambling/service.json`)
- [ ] T009 [P] [US1] Move `Sv/game-provider` to 0.18.0 (`Sv/game-provider/build.sbt`, `Sv/game-provider/service.json`; its suites need `providerCore/publishLocal`)
- [ ] T010 [P] [US1] Move `Sv/rewards` to 0.18.0 (`Sv/rewards/build.sbt`, `Sv/rewards/service.json`)
- [ ] T011 [P] [US1] Move `Sv/payouts` to 0.18.0 (`Sv/payouts/build.sbt`, `Sv/payouts/service.json`)
- [ ] T012 [P] [US1] Move `Sv/attribution` to 0.18.0 (`Sv/attribution/build.sbt`, `Sv/attribution/service.json`)
- [ ] T013 [P] [US1] Move `Sv/public-api` to 0.18.0 (`Sv/public-api/build.sbt`, `Sv/public-api/service.json`); re-read its Jackson YAML pin against the Jackson ankka 0.18.0 brings and correct the pin and its comment

### Group 3: the four on 0.16.0 and 0.16.2

- [ ] T014 [P] [US1] Move `Sv/engagement` to 0.18.0 (`Sv/engagement/build.sbt`, `Sv/engagement/service.json`)
- [ ] T015 [P] [US1] Move `Sv/reporting` to 0.18.0 (`Sv/reporting/build.sbt`, `Sv/reporting/service.json`)
- [ ] T016 [P] [US1] Move `Sv/audit` to 0.18.0 (`Sv/audit/build.sbt`, `Sv/audit/service.json`)
- [ ] T017 [P] [US1] Move `Sv/support-agent` to 0.18.0 (`Sv/support-agent/build.sbt`, `Sv/support-agent/service.json`; its block also lifts `ankka-agent`)

### Group 4: the two on 0.14.2

- [ ] T018 [US1] Move `Sv/exchange` and `Sv/markets` to 0.18.0 with ankka-predictions 0.3.0 (`PREDICTIONS_VERSION`'s default in both `build.sbt`s, and its comment); V7: read ankka-predictions' changes between `v0.1.1` and `v0.3.0` (`git -C ~/Documents/projects/thinkmorestupidless/ankka-predictions log v0.1.1..v0.3.0` after a fetch) and follow them; a change to what either service serves or stores is a finding: stop and ask. Apply T003's checklist

### Group 5: the six on 0.13.0

- [ ] T019 [P] [US1] Move `Sv/ingest` to 0.18.0 (`Sv/ingest/build.sbt`, `Sv/ingest/service.json`, sources by T003's checklist: no type arguments on route builders, annotated handler parameters, `Done.done`)
- [ ] T020 [P] [US1] Move `Sv/merchant` to 0.18.0 (`Sv/merchant/build.sbt`, `Sv/merchant/service.json`, sources by T003's checklist)
- [ ] T021 [P] [US1] Move `Sv/psp-gateway` to 0.18.0 (`Sv/psp-gateway/build.sbt`, `Sv/psp-gateway/service.json`, sources by T003's checklist)
- [ ] T022 [P] [US1] Move `Sv/provider` to 0.18.0 (`Sv/provider/build.sbt`, `Sv/provider/service.json`, sources by T003's checklist; with `provider-core` from T004)
- [ ] T023 [P] [US1] Move `Sv/game-provider-simulator` to 0.18.0 (`Sv/game-provider-simulator/build.sbt`, `Sv/game-provider-simulator/service.json`, sources by T003's checklist)
- [ ] T024 [P] [US1] Move `Sv/affiliate-programme` to 0.18.0 (`Sv/affiliate-programme/build.sbt`, `Sv/affiliate-programme/service.json`, sources by T003's checklist)

### Group 6: manifests, the tool's suites, the words

- [ ] T025 [US1] Every `"runtime"` in `modules/*/module.json` and `shared/*/manifest.json` becomes `0.18.0`; `MT/test/scala/io/eitheror/casino/modules/RenderSuite.scala` loses `NewRuntimes` and expects 0.18.0 for every service; `Spec001RenderSuite.scala`, `MT/test/resources/spec-001-goldvibe/plan.json`, `MT/test/resources/manifests/predictions.json`, `MT/it/scala/…/AccessSuite.scala` and `ApplyOnKindSuite.scala`'s default follow (the last untested, FR-025); `sbt test` and `just modules validate --all` green
- [ ] T026 [US1] Regenerate `local/guyanabet/services/*/service.json` with `just local-project` and commit the result; `just modules render guyanabet` shows 0.18.0 for every service and `brands/guyanabet/values.json` has no diff (story 1, third scenario)
- [ ] T027 [P] [US1] The words: `.github/workflows/ci.yml` comments naming a version (534, 558, 582, 605, 629); `Sv/support-agent/README.md`, `Sv/attribution/README.md`, `Sv/ingest/README.md`, `local/README.md`; `CLAUDE.md` *Working here* ("In ankka (0.13) a route takes at most two path parameters" goes; "no two endpoints may share a prefix" stays; the ankka 057 and 042 notes are left alone)
- [ ] T028 [US1] Bind `features/brands/platform-version.feature`'s first three scenarios in `MT/test/scala/io/eitheror/casino/modules/PlatformVersionFeatures.scala`, after `ModulesFeatures.scala`: every service's `build.sbt` and `service.json`, every module and shared manifest and the root `build.sbt` name 0.18.0 and no other (read from the working tree); a rendered `guyanabet` runs 0.18.0 everywhere with its values unchanged; the second scenario is bound to the count of parity suites present and is proved by T005 to T024's gates. SC-001's search is this suite

## Phase 3: User Story 1, continued — the runtime check, the local brand, the first pull request

- [ ] T029 [US1] `MT/main/scala/io/eitheror/casino/modules/Runtimes.scala` (`unsupported(platform, named)`, ankka's rule) with `MT/test/scala/io/eitheror/casino/modules/RuntimesSuite.scala` (the table of `contracts/runtime-check.md`); `Readiness.fromControlPlane` and `fromOverlay` read the platform's version (`platformVersion`; `PLATFORM_VERSION`, after V6: search `installation/overlays/*/settings.env` for a setting that already states it); every apply in `Main.scala` and `BrandCommands.scala`, brand and shared project, local mode and vendor push, calls it after validation and before anything is deployed, and refuses with the message of the contract; add `PLATFORM_VERSION=0.18.0` to each overlay; bind `platform-version.feature`'s fourth scenario in `PlatformVersionFeatures.scala`; a line in `modules/README.md`
- [ ] T030 [US1] The local brand on 0.18.0 (`contracts/move.md`, phase C): `just local-images`, `just local-project`, `ankka local up` in `local/guyanabet/`, `just local-onboard`, `just player-site-it`; expected 24 of 24 contract tests with the hand-written clients. Record the result, and anything `ankka local up` 0.18.0 demanded that 0.17.0 did not, in `research.md` ("Found during implementation"). Fewer than 24 is a regression of the move: find it before going on
- [ ] T031 [US1] `just features` green; then the first pull request: run `/speckit-review` over the move; create the branch `031-ankka-0-18` at this commit, push it, raise it as a draft against `main` (title `chore(031): every service on ankka 0.18.0`; body: the groups, each service's suites and counts against T002's baseline, the 24 of 24, the runtime check, that no expectation, recording or stored type changed); watch its checks; a fix is committed here and `031-ankka-0-18` fast-forwarded; mark it ready when green. Work continues on `031-generated-player-clients` without waiting for the merge

**Checkpoint**: story 1 is whole and reviewable on its own.

## Phase 4: Foundational for stories 2 to 4

- [ ] T032 V1, V3, V8 against `A` at `v0.18.0`, results in `research.md` ("Found during implementation"): what a `HeldToDocument` violation carries (`modules/testkit/…/HeldToDocument.scala`); whether `DocumentCheck` validates a JSON value against a document's schema from outside ankka; whether `ankka.document.strict` with `ankka.document.tags` refuses only tagged incomplete routes. Each failure takes the fallback research.md names
- [ ] T033 `contract/src/main/scala/io/eitheror/casino/contract/port/DescriptionCheck.scala` per `contracts/description-check.md` (`OriginalRoute`, `Difference`, `Result`, `run`), with `contract/src/test/scala/io/eitheror/casino/contract/port/DescriptionCheckSuite.scala` over fixtures under `contract/src/test/resources/description-check/`: a small description and recordings that are allowed, that show an unnamed status, that show a field outside the schema, that a `read` skips, and an operation no step matches
- [ ] T034 [P] A shared held kit: `contract/src/main/scala/io/eitheror/casino/contract/port/HeldToPlayerRoutes.scala` (or the contract's test-support artifact if `ankka-testkit` is not on its main class path): wraps a server with `HeldToDocument.server(server, violations)` and `assertHeld(description: Path)` fails listing each violation whose method and path are an operation of the kept description, `the-brand` matching the kit's brand; unit suite with a two-route endpoint, one tagged
- [ ] T035 [P] `Justfile`: `describe <service>`, `describe-check <service>`, `descriptions`, per `contracts/descriptions.md`; a line each in the root `README.md`
- [ ] T036 [P] `tests/port/unrecorded-player-routes.txt`, empty, with its format in a comment line the suites skip; `tests/port/README.md` gains a section on it and on the description check

## Phase 5: User Stories 2 and 3 — each service described, kept and held (Priority: P1)

**Goal**: nine kept service descriptions with no incomplete route, each agreeing with the recordings and with every test of its service.

**Independent Test**: quickstart.md step 4.

**Rules for every task T037 to T045** (`contracts/descriptions.md`, `contracts/description-check.md`), per service, in this order:
(a) `api/Described.scala`: the shapes its player routes take and answer, read from each handler's own JSON building and from the service's `specs/<nnn>/contracts/routes.md`; field names are the wire's;
(b) `described(…) { … }` round each player route, tagged `player`, with summary, body, answers, every refusal the handler makes, every query parameter it reads; the endpoint's `description` with `refusalShape` for the service's own refusal body; no handler is touched (FR-009);
(c) `describe.env`, and V2: `just describe <service>` writes `openapi.player.json` with no incomplete route; if `main` needs something before `start`, move it behind `start`;
(d) the kit held (`HeldToPlayerRoutes`), then the service's suites as CI runs them: each violation is fixed in the description when the handler is right, and reported as a finding and left when the handler is wrong;
(e) `port/DescriptionSuite.scala`: the original's route table, `read` from the service's `Parity` where it has one; differences empty; its lines of `tests/port/unrecorded-player-routes.txt` written from the first run and reviewed line by line;
(f) the service's `contracts/routes.md` corrected where the description shows it wrong (Amendments);
(g) a `describe-check` step in the service's job in `.github/workflows/ci.yml`.

- [ ] T037 [US2] [US3] Describe, keep and hold `Sv/wallet` first, as the pattern (3 routes in `api/AudienceEndpoints.scala` `PlayerEndpoint`; `MajorBalance`, `MajorTransactions`, `RestrictedFundsStatus` are its shapes already; kit `it/Harness.scala`; FR-016: all three unrecorded, and `port/DescriptionSuite.scala` says in its header what `PortSuite` replays); write what the pattern taught into `contracts/descriptions.md` before the other eight start
- [ ] T038 [P] [US2] [US3] Describe, keep and hold `Sv/responsible-gambling` (10 routes in `rg/api/Endpoints.scala` `PlayerEndpoint`; kit `rg/it/Kit.scala`; no parity package: `DescriptionSuite` reads `tests/port/engagement/` scenarios 26 and 27 and `tests/port/reporting/` as recorded, V4)
- [ ] T039 [P] [US2] [US3] Describe, keep and hold `Sv/ingest` (submit, abandon and the payment read in `api/Endpoints.scala`, tagged `player` and `ticket`; `POST /sessions` is not tagged; recordings in `tests/port/ingest/` and `tests/port/merchant/` under the host `merchant`)
- [ ] T040 [P] [US2] [US3] Describe, keep and hold `Sv/game-provider` (launch and the 4 catalogue routes in `api/Endpoints.scala`; the catalogue's 11 query parameters and `brand`; the launch is recorded in five directories)
- [ ] T041 [P] [US2] [US3] Describe, keep and hold `Sv/kyc` (4 routes in `api/PlayerEndpoint.scala`, 1 in `api/PublicEndpoint.scala`; the original's paths differ: `/v1/casino/{brand}/players/{id}/kyc/…`; `port/WireTranslation`)
- [ ] T042 [P] [US2] [US3] Describe, keep and hold `Sv/rewards` (10 routes in `api/RewardsEndpoint.scala`; `/free-rounds/eligible` is not tagged; the original's paths are `/v1/casino/bonus/{brand}/…`; `port/WireTranslation`)
- [ ] T043 [P] [US2] [US3] Describe, keep and hold `Sv/engagement` (the push connection in `api/StreamEndpoint.scala`: summary, the refusal before the stream opens, the tag; V5: its ACL gives `bearer` in the document, else the tag `signed-in`)
- [ ] T044 [P] [US2] [US3] Describe, keep and hold `Sv/support-agent` (the turn and the decision in `api/ChatEndpoint.scala`: bodies, the refusals thrown before the stream opens, the tag; recordings as recorded, status only)
- [ ] T045 [US2] [US3] Describe, keep and hold `Sv/players` (40 routes in `api/Endpoints.scala`: `PlayerEndpoint` 36, `SessionEndpoint` 3, `GeoEndpoint` 1; its calls are in every recordings directory; `port/WireTranslation`); the largest, so after the pattern has settled
- [ ] T046 [US2] [US3] Bind `features/brands/service-descriptions.feature`'s 11 scenarios in `MT/test/scala/io/eitheror/casino/modules/DescriptionFeatures.scala`, after `RecordingFeatures.scala`: the outline reads each kept `openapi.player.json` against that service's endpoint sources (every route tagged is held; none of an operator's prefix is); the refusal, incomplete, changed-route and stream scenarios run over T033's and T034's fixtures; "describing a route changes nothing" compares a fixture endpoint's answers with and without its description; the unrecorded-list scenario reads `tests/port/unrecorded-player-routes.txt`
- [ ] T047 [US3] Review `tests/port/unrecorded-player-routes.txt` whole: every line is a route no recording calls under any path (a miss in a service's original route table is fixed there); copy the list into `specs/031-generated-player-clients/contracts/description-check.md` as it stands, with the count out of 79 (FR-014, SC-004)

**Checkpoint**: `just describe-check` is current for all nine; nine `DescriptionSuite`s and every held suite green.

## Phase 6: User Story 4 — the module clients generated (Priority: P1)

**Goal**: route tables and wire shapes from the kept descriptions; the interface of spec 027 untouched.

**Independent Test**: quickstart.md steps 5 and 6.

- [ ] T048 [US4] `KT/unit/generate.test.ts`, first: the four hand-written tables of spec 027 copied in as the fixture (`KT/unit/fixtures/routes-027.ts`), and the failing expectations of `contracts/generation.md`: the generated tables equal the fixture row for row; each rule's failure message; the output is stable across two runs; a schema outside the subset fails naming it
- [ ] T049 [US4] `sdk/player/scripts/generate.ts` and `K/selection.ts` (the `Selection` type) per `contracts/generation.md` and research R10: reads `services/*/openapi.player.json` and `K/<module>/selection.ts`; writes `routes.ts` and `shapes.ts` with the generated header; `--check` mode; `package.json` scripts `generate` and `generate:check`; JSON Schema to Zod for the subset
- [ ] T050 [P] [US4] `K/core/selection.ts`: the 20 rows of core in today's order, each naming its operation, with `rename`, `fixedQuery` (`unread`), `serverOnly`, `visitor`, `takes`, `answers`
- [ ] T051 [P] [US4] `K/casino/selection.ts` (4 rows: `brandQuery`, the catalogue's `query`, `gameCode` renamed `gameId`), `K/engagement/selection.ts` (1 row), `K/support/selection.ts` (2 rows: `route` fixed to `chat_with_player`)
- [ ] T052 [US4] Generate: `K/{core,casino,engagement,support}/routes.ts` and `shapes.ts` committed as generated; T048's equality with the fixture green
- [ ] T053 [US4] `K/core/client.ts` and `K/casino/client.ts` become the mapping alone: every `z.object` goes, shapes come from `shapes.ts`, the interface and every function's result are unchanged; where a generated shape is stricter than the parser it replaces, the mapping does not add forgiveness back: a unit test in `KT/unit/coreClient.*.test.ts` or `casinoClient.test.ts` that relied on it is changed and listed in `research.md` ("Client tests changed, and why")
- [ ] T054 [P] [US4] `K/engagement/client.ts` and `K/support/client.ts` likewise; the events of the push connection and of the chat move to `K/engagement/events.ts` and `K/support/events.ts`, by hand
- [ ] T055 [P] [US4] `sdk/player/eslint.config.js`: under `src/{core,casino,engagement,support}/`, `zod` imported only by `shapes.ts` and `events.ts`, and a string literal beginning `/v1/` only in `routes.ts` and `selection.ts`; the generated files excluded from formatting rules that would rewrite them (SC-006)
- [ ] T056 [US4] Bind `features/player-site/generated-clients.feature`'s 7 scenarios in `KT/features/generated-clients.steps.ts` (`KT/features/coverage.test.ts` must count them): the outline over T048's fixture; the running-brand scenario bound to the contract test's result file or marked as proved by T060 in the way `coverage.test.ts` allows; the out-of-date scenario by editing a copy of a description in a temporary directory; the malformed answer through a double; the selection naming a missing route; a `kyc` route no selection names refused by the site server (`KT/server/`)
- [ ] T057 [P] [US4] `Justfile`: `player-sdk-generate`; `player-sdk-test` runs `generate:check` first; `.github/workflows/ci.yml`: the `player-sdk` job runs `generate:check` before typecheck, and its path filter gains `services/*/openapi.player.json`
- [ ] T058 [US4] `just player-sdk-test`, `just player-sdk-features`, `just player-site-features` green; `git diff origin/main --stat -- sdk/player/tests/features services/player-site sdk/player/src/react` shows no behaviour's, component's or site's test changed, other than the new steps file (FR-024)

## Phase 7: Acceptance and the words

- [ ] T059 [P] `sdk/player/README.md` (what a site builder now reads: a row comes from a selection, `npm run generate`), `specs/027-frontend-sdk/spec.md` FR-034 and FR-035 and `research.md` R5 amended as spec 031's *Amendments* say, `specs/027-frontend-sdk/contracts/module-clients.md` pointing at the selections; `specs/001-brands-and-projects/spec.md`'s pinned version
- [ ] T060 The acceptance: `just local-images`, `ankka local up` in `local/guyanabet/`, `just player-site-it`: 24 of 24 contract tests with the generated clients, compared case by case with T030's run; the result in `research.md` ("Acceptance")
- [ ] T061 [P] `CLAUDE.md`: the specs table's row for 031; *Working here*: how a route is described, `just describe`, `just describe-check`, the held kit, the description check and the unrecorded list, `npm run generate`; the brain's `brain/projects/ankka-modules.md` gains a dated line (in `~/Documents/projects/eitheror/brain/`, a separate commit there, not pushed)
- [ ] T062 Report to ankka, as an issue drafted in `research.md` ("For ankka") and not filed without the person's word: `ankka openapi <dir>` treats a directory under `services/` as a member of one root build (R4); a stream's events have no shape; a header a route reads is not described
- [ ] T063 `just features` reports 0 findings; `quickstart.md` run top to bottom, each step's result in `research.md` ("Quickstart run"); every SC of the spec ticked there with its evidence

## Dependencies

- T001 to T003 before anything. T004 before T005 to T024. Groups in order (2, 3, 4, 5); within a group, services are independent `[P]`.
- T025 after every service has moved; T026 after T025; T028 after T026. T029 after T025. T030 after T026 and T029. T031 last of the first pull request.
- T032 to T036 after T031 and before any of T037 to T045. T037 before T038 to T045 (the pattern). T046 and T047 after all nine.
- T048 before T049; T050 and T051 need the kept descriptions (T037 to T045); T052 after T049 to T051; T053 and T054 after T052; T056 and T058 after T053 and T054.
- T060 after T058. T063 last.

## Parallel examples

- Group 2 as nine agents, one service each, each in its own worktree with its own `CASINO_VERSION` for the published contract (the snapshot in `~/.ivy2` is shared) and its own git index.
- T038 to T044 as seven agents after T037, the same way; T045 alone.
- T050 and T051 together; T054, T055 and T057 together.

## Implementation strategy

The first pull request is the MVP and is worth merging alone: one ankka version, the runtime
check, the local brand proved. The second is built service by service; each service's description
is useful the moment it is held, before any client is generated. Generation comes last and is
proved against a fixture of what it replaces before the hand-written tables are deleted.

Stop and ask, never work round: a stored type the move forces to change; an expectation that must
change for a reason other than an ankka API; a finding from V7; a handler a description shows to
be wrong (report it, describe what it does, leave it).
