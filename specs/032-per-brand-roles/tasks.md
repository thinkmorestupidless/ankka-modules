# Tasks: Per-brand realm roles

**Input**: `specs/032-per-brand-roles/` (spec.md, plan.md, research.md, data-model.md, contracts/,
quickstart.md)

**Tests**: required. Every scenario the spec names is bound in the suite the plan's table names
(plan, *Where each new scenario is bound*); a scenario with no steps fails its suite.

**Conventions**: publish the contract under `CASINO_VERSION=0.2.0-032-SNAPSHOT` (other worktrees
overwrite the shared snapshot); commit through a private `GIT_INDEX_FILE` when agents run in
parallel; never touch `services/backoffice/src/web/areas/affiliates/account.tsx` or its `.swp`.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 Prove research R1: add `RealmRoleNameIt` in `services/backoffice-state/src/test/scala/io/eitheror/casino/backoffice/it/RealmRoleNameIt.scala` on `StateIt`'s Keycloak 26.0.7 container: create realm role `brand_finance@goldvibe` through the admin API, assign it to a user, sign the user in, and assert the access token's `realm_access.roles` holds it unchanged. If Keycloak refuses `@`, stop and record the separator `:` in research R1 before any other task
- [ ] T002 Set `CASINO_VERSION=0.2.0-032-SNAPSHOT` for every `just service` run of this branch and note it in `specs/032-per-brand-roles/quickstart.md` (already there; confirm it is honoured by `just service` in `justfile`)

## Phase 2: Foundational (the rule, once)

- [ ] T003 Write the shared case table `contract/src/test/resources/brand-roles.cases.json` (rows per `contracts/brand-roles.md`: another brand's role, an unbranded role, `brand_admin` without a brand, `platform_admin`, a deleted bag, slug `admin`)
- [ ] T004 Add `contract/src/main/scala/io/eitheror/casino/contract/access/BrandRoles.scala` (`Separator`, `nameIn`, `heldIn`, `brandOf`, split at the last `@`) and `contract/src/test/scala/io/eitheror/casino/contract/access/BrandRolesSuite.scala` running every row of T003's table
- [ ] T005 Make `OperatorPermissions.permissionsOf` take the slug and open only `BrandRoles.heldIn(slug, roles)`; `validRoleName` and the reserved checks refuse any name with `@`, in `contract/src/main/scala/io/eitheror/casino/contract/access/OperatorPermissions.scala`
- [ ] T006 Make `RoleBags` take the brand's slug when made and open only that brand's roles, in `contract/src/main/scala/io/eitheror/casino/contract/access/RoleBags.scala`; update every `RoleBags(` construction under `services/*/src/main/scala` to pass the service's brand
- [ ] T007 In `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`: `operatorHolding(slug, roles*)` and `accessKeys(slug)` check `nameIn(slug, role)` (`platform_admin` bare); `affiliateManager` admits `affiliate_manager@<b>` or `brand_admin@<b>` for the path's brand; `brandsOf` reads only `brand_<slug>` with no `@` in the rest and not a default role's rest (research R3)
- [ ] T008 Update the contract's access suites (`contract/src/test/scala/io/eitheror/casino/contract/access/`, `steps/AccessFeatures.scala`) for the named roles; `just test` green
- [ ] T009 Pass the slug to the direct callers: `services/kyc/src/main/scala/io/eitheror/casino/kyc/api/OperatorEndpoint.scala` (`holds`), `services/reporting/src/main/scala/io/eitheror/casino/reporting/api/Documents.scala` (`held`), `services/players/src/main/scala/io/eitheror/casino/players/api/RolesRoutes.scala` (lines 119, 146)
- [ ] T010 Change every test kit's operator tokens to named roles (`staff|<id>|<roles>` and the `asStaff` helpers in `services/*/src/test/scala/**/it/Kit.scala`) through one helper per kit that applies `BrandRoles.nameIn(kit.brand, _)`; run each service's suite (`just service <name> test`) until green, the `OperatorRolesFeatures` of game-provider, reporting, kyc, players, rewards, support-agent, engagement and attribution included

**Checkpoint**: the contract holds the rule; every brand service opens only its own brand's roles.

## Phase 3: User Story 1 - A role one brand gives holds in that brand only (P1) 🎯 MVP

**Goal**: no route of a brand admits a role named for another brand, or one with no brand.

**Independent test**: an operator of goldvibe and spinvibe given finance in goldvibe is refused every spinvibe route only finance would allow.

- [ ] T011 [P] [US1] In `services/backoffice/src/server/roles.ts`, `permissionsOf(brand, roles, bags)` and a `heldIn` twin of the contract's; `services/backoffice/src/server/roles.test.ts` runs every row of `contract/src/test/resources/brand-roles.cases.json`
- [ ] T012 [US1] Pass the brand at every `permissionsOf` call in `services/backoffice/src/server/main.ts` (lines 149, 194, 200, 269, 345)
- [ ] T013 [US1] Bind the 3 US1 scenarios of `features/backoffice/access.feature` in `services/backoffice/tests/features/steps/access.steps.ts`, the doubles' tokens carrying named roles (`services/backoffice/tests/features/world.ts`); `just backoffice-features`
- [ ] T014 [P] [US1] Bind "the markets permission given in one brand opens no other brand's markets" in `services/markets/src/test/scala/io/eitheror/casino/markets/features/LifecycleFeatures.scala`; the kit's markets operator holds `markets@<brand>`; check `services/exchange` the same way (`just service exchange test`)
- [ ] T015 [P] [US1] Public API: `KeysFeatures.scala:27`'s operator holds `api_keys@spinvibe` and `brand_goldvibe` (`services/public-api/src/test/scala/io/eitheror/casino/publicapi/features/KeysFeatures.scala`); `asKeyManager` holds `api_keys@<brand>` in `services/public-api/src/test/scala/io/eitheror/casino/publicapi/it/Kit.scala`
- [ ] T016 [US1] Programme managers (research R8): `services/affiliate-programme/src/main/scala/io/eitheror/casino/programme/api/Managers.scala` gives, lists and revokes `affiliate_manager@<slug>`; `Access.ManagerAdminRoles` checks `brand_admin@<path brand>` in `api/Access.scala`
- [ ] T017 [US1] Bind "the manager role given for one brand manages no other brand the operator's roles name" in `services/affiliate-programme/src/test/scala/io/eitheror/casino/programme/features/ManagerSteps.scala`; the programme's suites green

**Checkpoint**: 030's blocking finding is closed.

## Phase 4: User Story 2 - A brand's giving ends as asked (P1)

**Goal**: a giving is recorded first and applied by a consumer; unreachable retried, refused ends failed; brands cannot undo each other.

**Independent test**: interrupt a giving after its first realm change; two brands give at once; both end as asked.

- [ ] T018 [US2] Split `RealmError.Failed` into `Unreachable` and `Refused(status, reason)` and URL-encode the role name on every path, in `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/KeycloakAdmin.scala`; `KeycloakAdminSuite` covers both (research R5)
- [ ] T019 [US2] Add `BrandRolesAsked`, `BrandRolesApplied`, `BrandRolesFailed`, the `givings` state and the replay of `SetBrandRoles` (data-model.md) to `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/Operator.scala`, with manifests in `MerchantCodecs.scala`; unit suite for the transitions, a stale `Applied`/`Failed` changes nothing
- [ ] T020 [US2] H6 records the giving and answers `202`; H6r answers the giving's state, in `services/merchant/src/main/scala/io/eitheror/casino/merchant/api/OperatorsEndpoint.scala` (contracts/amendments.md H6, H6r); the `others` read and the in-request realm calls go
- [ ] T021 [US2] Add the consumer `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/BrandRolesApplier.scala` (research R4), registered in `MerchantService.scala`; audit records for applied and failed (FR-014)
- [ ] T022 [US2] `FakeKeycloak` (`services/merchant/src/test/scala/io/eitheror/casino/merchant/it/FakeKeycloak.scala`) gains switches to stop answering after N calls and to refuse a user; `BrandRolesApplierSuite` covers resume after a part-way failure, 100 concurrent givings by two brands ending as asked (SC-003), a later giving replacing an earlier one, and a refusal recorded once
- [ ] T023 [US2] Bind the 4 added scenarios of `features/payments/operators.feature` in `services/merchant/src/test/scala/io/eitheror/casino/merchant/features/OperatorsFeatures.scala`; update its existing H6 steps (and `it/OperatorsSuite.scala`) to wait for the giving to be in force
- [ ] T024 [US2] Players' P13 and P13r (contracts/amendments.md): record `RoleAssignment` after H6's `202`, answer the giving's state, and read H6r into the GET, in `services/players/src/main/scala/io/eitheror/casino/players/api/RolesRoutes.scala`; `RoleRules.nameRefusal` gives `api_keys` and `markets` (research R9) in `services/players/src/main/scala/io/eitheror/casino/players/roles/RoleRules.scala`; players' suites green
- [ ] T025 [US2] The backoffice's operators area shows each giving's state and a failed giving's reason (`services/backoffice/src/web/areas/team/`), and lets `api_keys` and `markets` be given; the players double in `services/backoffice/tests/` answers the giving's state
- [ ] T026 [US2] Bind the 3 added scenarios of `features/backoffice/roles.feature` in `services/backoffice/tests/features/steps/roles.steps.ts`; `just backoffice-features`, `just backoffice-a11y`
- [ ] T027 [US2] The modules tool's `Realm.ensureBrandRole` makes the named roles and carries over (research R7) in `tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala`, its admin client gaining `usersWithRole`, `assign`, `remove` and the in-memory admin the same; each change in the plan output
- [ ] T028 [US2] Bind the carry-over scenario of `features/brands/modules.feature` in `tools/modules/src/test/scala/io/eitheror/casino/modules/ModulesFeatures.scala`, including a second apply that changes nothing; `just test`

**Checkpoint**: 030's declined findings are closed.

## Phase 5: User Story 3 - A role given at the cutover can be taken away (P2)

**Goal**: the cutover gives named roles through H6.

**Independent test**: the cutover's mapping for one operator, then a smaller giving; the left-out roles are gone.

- [ ] T029 [US3] Amend spec 019's role step to give `<mapped>@<slug>` through H6 per brand (research R10): edit `specs/019-spinvibe-migration/` where it states the cutover's role mapping, and spec 030's `contracts/roles.md` cutover mapping
- [ ] T030 [US3] Cover a cutover-given role taken away by a later giving in `BrandRolesApplierSuite` (a role `<x>@<slug>` held, not asked, removed); `features/migration/operator-roles.feature` stays unbound (plan)

## Phase 6: User Story 4 - An operator sees their roles in this brand (P3)

**Goal**: `/api/me` lists only this brand's roles, by their names without the brand.

**Independent test**: as an operator of two brands, sign in to one brand's backoffice; only its roles are shown.

- [ ] T031 [US4] `/api/me` returns `heldIn(brand, roles)` plus `platform_admin` when held, in `services/backoffice/src/server/main.ts`; vitest for it
- [ ] T032 [US4] Bind "an operator is shown only the roles they hold in the brand of the backoffice" in `services/backoffice/tests/features/steps/access.steps.ts`

## Phase 7: Polish

- [ ] T033 [P] Amend spec 030's `specs/030-backoffice/contracts/roles.md` ("How a service opens a bag") and `contracts/amendments.md` (H6, P13) to point at this spec's contracts
- [ ] T034 [P] Update `CLAUDE.md`'s spec table with 032, and `GLOSSARY.md`'s `operator role` if the build changed its meaning
- [ ] T035 Run `specs/032-per-brand-roles/quickstart.md` end to end; `just features` reports `0 findings`

## Dependencies

- T001 before everything (it may change the separator).
- Phase 2 (T003–T010) before every story.
- US1 (T011–T017) and US2 (T018–T028) are independent after Phase 2; T025–T026 need T024; T024 needs T020.
- US3 needs T021. US4 needs T011–T012.
- Polish last.

## Parallel examples

- After Phase 2: T011, T014, T015, T018 and T027 touch different builds.
- Within US2: T018–T023 (merchant) beside T027–T028 (the modules tool).

## Implementation strategy

MVP is Phase 1, Phase 2 and US1: the security hole closed. US2 closes 030's declined findings and
is needed before 030 merges; US3 and US4 follow.
