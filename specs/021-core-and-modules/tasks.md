# Tasks: Core and Modules — A Brand Runs What Its Entitlement Names

**Input**: Design documents from `specs/021-core-and-modules/`

**Prerequisites**: plan.md, spec.md, research.md (R1 to R10), data-model.md, contracts/
(module-manifests, values, modules-tool, routes, release), quickstart.md,
`features/brands/modules.feature`.

**Tests**: the living features are the acceptance tests; every scenario of `modules.feature` ends
up bound in `ModulesFeatures`, and the unit suites of research R10 beside it. A scenario is ticked
only when its step runs green.

**Organization**: by user story. US2 (the manifests and the tool's validation) comes first in
execution because US1 and US3 render from the manifests; the phases below are in execution order
and say which story each serves.

## Format: `[ID] [P?] [Story] Description`

`[P]` can run in parallel. Paths: `tools/modules/src/main/scala/io/eitheror/casino/modules/` is
written `main/…`, `tools/modules/src/test/scala/io/eitheror/casino/modules/` is `test/…`.

---

## Phase 1: Setup

- [X] T001 `git mv tools/brands tools/modules`; rename package `io.eitheror.casino.brands` → `io.eitheror.casino.modules` in every file under `tools/modules/src`; `build.sbt`: project `modules` (was `brands`, `name := "casino-modules"`, `Compile / mainClass := Some("io.eitheror.casino.modules.Main")`, `executableScriptName := "ankka-casino"`) and `modulesIt`; `Justfile`: `modules *args`, `it` → `modulesIt/test`; `sbt test` green on the rename alone
- [X] T002 [P] `tools/modules/src/main/resources/ankka-vendor-manifest.schema.json`: the vendor's schema, written from ankka-vendor 004 `contracts/manifest.md` until `ankka vendor modules schema` exists (then `schema-sync`, T027); `tools/modules/README.md` records the vendor version
- [X] T003 [P] `contract/keys.md`: every configuration key a manifest may name, by module, with type and whether secret, and for a secret key the `<secret>/<KEY>` it is read from (`st8-api-key` ← `goldvibe-st8/API_KEY`); the five identity keys of the core (data-model.md)
- [X] T004 [P] `CLAUDE.md`: the module model in "Working here" (manifests, values, `just modules`, local mode, the tag), spec 021 in the table; `README.md` the same in two lines

---

## Phase 2: Foundational — the manifests, the values, the contract

- [X] T005 `main/ModuleManifest.scala`: the vendor's shape as Scala (`name`, `kind`, `dependencies`, `services[].descriptor` as `ankka-controlplane-api`'s descriptor body plus `env[].key`, `topics`, `grants`, `configuration`, `retention`, `ending`), read with jsoniter refusing unknown fields; `validate(file)` against the vendored schema then the contract rules of contracts/module-manifests.md, including `a grant within the brand project is not a grant` for a `made` grant whose principal is `{module, service}` of a module the same project runs (FR-007), each refusal naming the entry's path
- [X] T006 [P] `main/Values.scala` and `tools/modules/src/main/resources/values.v1.json`: the values file of contracts/values.md, its refusals, the derived keys, `tenantProject(slug, organization)`
- [X] T007 [P] `modules/core/module.json`, `modules/casino/module.json`, `modules/engagement/module.json`, `modules/reporting/module.json`, `modules/support/module.json` to contracts/module-manifests.md, images at `0.0.0`; `modules/README.md`
- [X] T008 [P] `brands/goldvibe/values.json` from `brands/goldvibe/manifest.json` (then `git rm` the manifest); `brands/README.md` rewritten
- [X] T009 `contract/src/main/scala/io/eitheror/casino/contract/Layout.scala`: `Services`, `BrandTopics`, `BrandGrantsMade`, `brandGrantsReceived` read from `modules/*/module.json` packaged as resources (`build.sbt`: `contract / Compile / unmanagedResourceDirectories += baseDirectory.value.getParentFile / "modules"`); `OptionalBrandServices` and `BrandServices` removed; `LayoutSuite` asserts the derived lists equal spec 001's values for the core and the casino together
- [X] T010 [P] `contract/src/main/scala/io/eitheror/casino/contract/facts/PlatformModules.scala`: the family of data-model.md; `Topics.brand` gains it; `just schemas` writes `contract/schemas/platform.modules.v1.json`; `FactsSuite` case
- [X] T011 `test/ModuleManifestSuite.scala` and `tools/modules/src/test/resources/manifests/` (`unknown-service.json`, `unknown-topic.json`, `unknown-grant.json`, `value-for-key.json`, `secret-literal.json`, `product-on-product.json`, `service-in-two-modules.json`): the five manifests validate; each variant is refused naming the entry; every service a manifest names has `services/<name>/build.sbt` or is listed in `contract/keys.md`'s "not yet built" table
- [X] T012 [P] `test/ValuesSuite.scala`: goldvibe validates; a values file with `version`, `services`, `grants`, a secret key in `values`, a secret-looking value, an unknown key, a slug not matching its directory: each refused naming the entry

**Checkpoint**: `just build && just test` green; `just modules validate --all` passes.

---

## Phase 3: User Story 2 — A module is described by one manifest and released by one tag (Priority: P1)

**Independent Test**: `modules.feature` scenarios 5 to 7; `just release-dry`.

- [ ] T013 [US2] `main/ReleaseRender.scala` and `Main` command `release render <version> --out <dir>`: images `0.0.0` → the version, `topics[].schema` inlined from the contract artifact's `schemas/<topic>.v1.json`, every manifest validated after rendering; `test/ReleaseRenderSuite.scala`
- [ ] T014 [US2] `tests/fakes/ankka-vendor` (bash): records each invocation's arguments to `$ANKKA_VENDOR_FAKE_LOG`, answers `modules publish` with `published <name> <v> <digest>`, exits 1 on any `channels` call; `Justfile` recipe `release-dry version`: render to a temp dir and run the publish loop of `.github/workflows/release.yml`'s script `scripts/release-publish.sh` with the fake first on the path; `ReleaseRenderSuite` asserts one publish per manifest and no channel call (SC-004)
- [ ] T015 [US2] `.github/workflows/release.yml`: `images` matrix over `services/*/` (`CASINO_VERSION` from the tag, `Docker/publish`), `manifests` running `scripts/release-publish.sh` with the vendor secrets, `summary`; `.github/workflows/ci.yml`: `just modules validate --all` and `release-dry` in `check`; the `kind` job runs `modulesIt/test` (was `brandsIt`)
- [ ] T016 [US2] Steps for scenarios 5 to 7 of `modules.feature` in `test/ModulesFeatures.scala` (validation refusals through `ModuleManifest.validate`; the tag scenario through `ReleaseRender` and the fake's log)

---

## Phase 4: User Story 3 — A brand's values are the only thing a brand supplies (Priority: P2)

**Independent Test**: `modules.feature` scenarios 8 to 10; `RenderSuite` equals the spec 001 fixture.

- [ ] T017 [US3] `main/Render.scala`: `plan(values, manifests, versions, installation)` → `Plan` of data-model.md: descriptors with `env` joined from values (`value` or `secretKeyRef`), topics with retention from `Layout`, grants resolved to the project, `missing` keys; `Main` command `render <slug> [--modules] [--channel | --version] [--installation] [--format json]` (a channel's versions through `main/Vendor.scala` running `ankka vendor modules get`)
- [ ] T018 [US3] `tools/modules/src/test/resources/spec-001-goldvibe/`: the descriptors, topics and grants spec 001's `Render` produced for goldvibe at `4755028`'s manifest (generated once by the old tool at the parent commit, committed as the fixture); `test/RenderSuite.scala`: rendering goldvibe's values against `core` and `casino` at the fixture's versions equals the fixture field by field (SC-001), with the listed differences (E1, E2): `SUPPORT_AGENT_ENABLED` gone (`support` is a module); the three `psp-gateway` routes and the erasure grant absent from the core's manifest, made by `shared/payments/manifest.json` in local mode
- [ ] T019 [US3] `main/Readiness.scala` rewritten over the vendor's installation facts: evaluates the core manifest's `requires[]` against `GET /installation` when reachable, else against the overlay's `settings.env` mapped to the same fact names (research R6), for `validate <values> --installation --real-money` and for `LocalApply`; `Main` commands `values push <slug>` (through `Vendor` or, in local mode, the project secret), `secrets <slug> --secrets <file>`, `hostnames <slug>`, `realm <slug>` (from `Realm.scala`); `test/ValuesPushSuite.scala` against `InMemoryControlPlane` and a fake `Vendor`
- [ ] T020 [US3] Steps for scenarios 8 to 10 of `modules.feature` in `ModulesFeatures` (the refusal through `Values.validate`; the missing key through `Render.plan`'s `missing` and `AgentModel`; real money through `AgentModel` evaluating the core's requirements against modelled installation facts and applying nothing, naming the fact)

---

## Phase 5: User Story 1 — A brand runs its core and the modules it is entitled to (Priority: P1)

**Independent Test**: `modules.feature` scenarios 1 to 4; `ModulesOnKindSuite`.

- [ ] T021 [US1] `test/AgentModel.scala`: the vendor's apply order (ankka-vendor 004 `contracts/agent.md`: resolve, values, requires against modelled installation facts, topics, services in order, grants; remove in reverse with `dataKeptUntil`; refuse disabling a dependency naming the dependent) over `Plan`s against `InMemoryControlPlane`, with a module entity record and a `platform.modules` fact per change; `AgentModelSuite` proving order, the missing key, the dependent refusal
- [ ] T022 [US1] `main/LocalApply.scala` (from `Apply.scala`): the same order as `AgentModel`, for real, in local mode: project, topics with schemas, secrets noted, the core's services waited for, `--modules` after their dependencies, grants, hostnames, realm; refuses an installation with `VENDOR_URL`; `Main` commands `apply <slug> --installation <name> [--modules]`, `apply shared/<p>/manifest.json`, `status <slug>` (fitness, then the module entity through the agent or, locally, the control plane); `test/LocalApplySuite.scala` (from `ApplySuite`) and `ManifestFeatures.scala` rebound to local mode
- [ ] T023 [US1] Steps for scenarios 1 to 4 of `modules.feature` in `ModulesFeatures` over `AgentModel`: "a player can register, deposit and withdraw" and "no game route" asserted on the plan's services and the contract's route list per service (`contract/access/routes.md`), "nothing to any other brand" on a second project's state, "what the module allows … is in force" on the grants made
- [ ] T024 [US1] `tools/modules/src/it/scala/io/eitheror/casino/modules/ModulesOnKindSuite.scala` (the `kind` job): `apply goldvibe --installation dev-kind` with the services that exist (`wallet`, `game-provider`, the simulator in `tools`): the wallet's scenarios answer, the game route is not served; then `--modules casino`: `features/game-provider/` scenarios the simulator drives answer and the wallet's data is unchanged (SC-002 as far as the built services go; the suite names the services it could not prove)

---

## Phase 6: User Story 4 — Operators see their modules; eitheror enables them (Priority: P2)

**Independent Test**: `modules.feature` scenarios 11 to 13; `ReadOnlyRoutesSuite`.

- [X] T025 [US4] `contract/access/routes.md` and `specs/008-players/contracts/routes.md`: the two routes of contracts/routes.md under `players`; `test/ReadOnlyRoutesSuite.scala`: every route under `/modules` in the contract is `GET` or `POST …/requests` (SC-003); `contract/access/Routes.scala` unchanged
- [ ] T026 [US4] Steps for scenarios 11 to 13 in `ModulesFeatures`: the operator's read through the contract's route list and the model's entity; the enable attempt finds no route; the vendor-console scenario bound to `AgentModel`'s entitlement delivery with the assertion that the delivered record and the entity hold no player, wallet or value (field names checked against a deny list); the fact scenario on the model's `platform.modules` output validated against the family's schema. The step file says in a comment that the console itself is proven in ankka-vendor (vendor quickstart step 5)

---

## Phase 7: Polish and cross-cutting

- [ ] T027 [P] `Main` command `schema-sync` running `ankka vendor modules schema` into the resource and the README line (skips with a message when the plugin is absent); `test/SchemaSyncSuite.scala` with the fake plugin
- [ ] T028 [P] `tools/modules/README.md` rewritten to contracts/modules-tool.md; `modules/README.md` with "moving a channel" (contracts/release.md); `brands/README.md`; `installation/README.md` notes `VENDOR_URL` in a vendor's overlay
- [ ] T029 [P] `specs/019-spinvibe-migration/spec.md` and `specs/020-game-provider-simulator/spec.md`: one paragraph each under Assumptions saying values replace the manifest (019) and the simulator is applied by the modules tool in local mode (020), per research R9; `just features` green
- [ ] T030 Run quickstart steps 1 and 2; run step 3 against ankka-vendor's compose stack once the vendor's 004 is merged (record the commit and vendor version in `research.md`, or the reason it could not run); `just features`, `just test`, `just it` green; `sbt scalafmtAll`

---

## Dependencies

- Phase 1 → Phase 2 → Phases 3 and 4 (either order, both need T005 to T012) → Phase 5 (needs
  T017's `Render`, T021 before T022, T023) → Phase 6 (needs T021) → Phase 7.
- T009 after T007 (the resources it reads). T018's fixture is generated before T001's rename
  moves the old tool, or from the parent commit in a scratch checkout.
- T024 needs a kind installation and the wallet's image; it runs in the `kind` job and `just it`.

## Parallel opportunities

- Phase 1: T002, T003, T004. Phase 2: T006, T007, T008, T010, T012 together after T005.
- Phases 3 and 4 by two workers (release and rendering touch different files; both add steps
  to `ModulesFeatures`, so the second to finish rebases).
- Phase 7: T027 to T029.

## Implementation strategy

MVP is Phases 1 to 5 with `ModulesOnKindSuite` green: manifests, values, a tool that renders and
applies locally, and the bound features proving the apply order. Phase 3's release workflow can
ship before Phase 5 since it needs no running service. Phase 6 is contract and tests only until
spec 008 builds `players`.

**Task count**: 30. US1: 4 (T021 to T024). US2: 4 (T013 to T016). US3: 4 (T017 to T020). US4: 2
(T025, T026). Setup 4, foundational 8, polish 4.
