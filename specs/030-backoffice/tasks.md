# Tasks: the backoffice

**Input**: Design documents from `specs/030-backoffice/`

**Prerequisites**: plan.md, spec.md, parity.md, research.md (R1–R20, V1–V6), data-model.md,
contracts/{roles,sources,amendments,routes,design}.md, quickstart.md

**Tests**: Included. The living features are the scenarios (constitution VII) and every amendment to
an owning service is made with its own tests (contracts/amendments.md). Each story opens with its
tests: step bindings over `features/backoffice/` against the doubles, and the owning services'
suites, each written first and failing first.

**Organization**: By user story, in the six phases of FR-045, all in this one list (clarified
2026-11-15). US1 to US3 are phase 1 and the first usable backoffice. Amendments to an owning
service sit in the story that first needs them; the role matrix (M) sits in the foundation, because
every story's access depends on it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US11 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `s/` is `services/backoffice/src/server/`; `sh/` is `services/backoffice/src/shared/`;
  `w/` is `services/backoffice/src/web/`; `t/` is `services/backoffice/tests/`
- `st/` is `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/`; `st-test/` its
  `src/test/scala/…` twin
- `c/` is `contract/src/main/scala/io/eitheror/casino/contract/`
- `svc/<name>/` is `services/<name>/`; a task there names the amendment's number in
  contracts/amendments.md (P2, W2, RW1, GP2, …; R1–R20 and V1–V6 are research.md's) and edits that service's endpoint, its tests, its
  `features/<area>/` scenarios, and adds one line under *Amended by 030* in its spec
- sbt runs one process per service directory; the contract is published with a unique
  `CASINO_VERSION`; every `kubectl` takes `--context kind-…`
- an amended service keeps its own ankka pin (plan.md, *Technical Context*)

---

## Phase 1: Setup

**Purpose**: prove the assumption, and make the two service directories buildable.

- [X] T001 Prove V1 before anything else: write `tests/spikes/backoffice-v1/` (a web-hosted probe from `ankka init --language web`, its `service.json`, a `README.md`) and the recipe `backoffice-spike` in `Justfile`; on `dev-kind`, deploy the probe in the brand's project and call (a) `players`' operator route at `ANKKA_SERVICES_URL` and (b) `merchant.payments`' with an operator's token and with none; record the four answers in `specs/030-backoffice/research.md` under V1. If (a)'s tokened call is refused by the runtime, stop the build and write the ankka request there; if only (b)'s is, write the request and mark T071, T108, T112, T113, T117 to T119 as waiting on it
- [X] T002 Create `services/backoffice/` with `ankka init backoffice --language web`; set `package.json` (Node 24, scripts `dev`, `build`, `typecheck`, `test`, `features`, `a11y`), `tsconfig.json`, `vite.config.ts` (content-named assets), `Dockerfile`, and split `src/` into `server/`, `shared/`, `web/` as plan.md lays out
- [X] T003 [P] Create `services/backoffice-state/` with `ankka init` (Scala): `build.sbt` on ankka 0.16.3 with `ankka-auth-oidc` and the contract, `service.json`, `README.md`, `st/BackofficeStateService.scala`, `st/Settings.scala`
- [X] T004 [P] Add the dependencies of research R7 to `services/backoffice/package.json` and lock them: hono, @tanstack/react-router, @tanstack/react-query, tailwindcss 4, radix-ui, recharts, d3-geo, intl-messageformat, zod; dev: vitest, @testing-library/react, playwright-bdd, @axe-core/playwright
- [X] T005 [P] Add recipes to `Justfile`: `backoffice-test`, `backoffice-features`, `backoffice-a11y`, `backoffice-run` (`ankka local web`), `backoffice-contract` (copy the published files), `backoffice-state-it`, `backoffice-seed`, `backoffice-it`, `backoffice-record-original`
- [X] T006 [P] Add to `.github/workflows/ci.yml` a `backoffice` paths filter and a Node 24 job (typecheck, unit, features, a11y, the contract-copy diff), and a `backoffice-state` sbt job like the other services'; make `.github/workflows/release.yml` build an image for a service directory with a `package.json` (amendment D4)

---

## Phase 2: Foundation (blocks every story)

**Purpose**: permissions, the brand's roles, the realm, the module, the session, the pass-through,
the frame and the test harness. No story can be built before this phase is done.

### The contract: permissions and the role matrix

- [X] T007 Write `contract/src/test/scala/io/eitheror/casino/contract/access/OperatorPermissionsSuite.scala` and `RoleBagsSuite.scala` first: every permission of contracts/roles.md is named once; the role matrix gives each default role its default bag; `platform_admin` carries every permission whatever the bags say; no payments role is in the matrix; `RoleBags` decides unavailable until its first read, refreshes every 10 seconds, keeps its last copy when the fetch fails, and `operatorMay(slug, permission)` refuses with `forbidden` naming the permission
- [X] T008 Implement `c/access/OperatorPermissions.scala` (the permissions' names and the role matrix as data), `c/access/RoleBags.scala` (the kept copy, refreshed through a fetch the service supplies, so the contract stays on its own ankka pin; verify V6 and answer `503` from the endpoint if a refinement cannot decide `Unavailable`) and `Routes.operatorMay(slug, permission)` in `c/access/Routes.scala` (C1)
- [ ] T009 Make `WriteSchemas` in `build.sbt`'s contract project write `contract/schemas/access/operator-permissions.v1.json` (the permissions' names and the role matrix as `defaults`) and `contract/schemas/money/currencies.v1.json` (every ISO currency's minor-unit count from `Amounts.exponent`); regenerate with `just schemas`; extend the CI schemas diff to them (C2, R5, R6)
- [ ] T010 [P] Add to `c/Layout.scala` the module `backoffice` with its services `backoffice` and `backoffice-state`, and the grant a brand makes to its `players` for merchant's `PUT /v1/operators/{id}/brand-roles/{brand}`, with their tests; add the optional `cause` on the adjustment fact in `c/facts/` with its schema regenerated (C3, C4)
- [ ] T011 [P] Add the role `brand_support` to `brands/_realm/eitheror-operators.json` and say in `brands/_realm/README.md` that each brand's `backoffice-<slug>` client is made with the brand (C5, D3)

### The brand's roles (players, merchant)

- [ ] T012 Bind `features/players/roles.feature` (12 scenarios) in `svc/players/`'s `GherkinSuite` and write `RoleSuite` in its test tree, failing first: the seed, the rules of contracts/roles.md, the audit record
- [ ] T013 Implement in `services/players/src/main/scala/io/eitheror/casino/players/roles/` `Role.scala` (event sourced, data-model.md), `Roles.scala` (the view), the seed of the role matrix when no role exists, and the rules: known permissions only, the last role carrying `roles.change` keeps it, a default role resets and is not deleted (P12, FR-008b, FR-008d)
- [ ] T014 Implement `roles/RolesEndpoint.scala` in `svc/players/`: `GET roles` admitted to every service of the project and to `roles.read`; `PUT roles/{name}`, `DELETE roles/{name}`, `POST roles/{name}/reset` requiring `roles.change`; an audit record per change through the service's audit consumer; `players` opens its own bags from `Roles` directly (P12)
- [ ] T015 [P] `svc/merchant/` H6: `PUT /v1/operators/{id}/brand-roles/{brand}` admitted only to that brand's `players` under the brand's grant; adds `brand_<slug>`, creates a realm role that does not exist, removes brand-given roles no longer asked for, refuses a payments role, `platform_admin` and another brand's role; binding the three new scenarios of `features/payments/operators.feature`; amends spec 004
- [ ] T016 Implement `roles/Assignments.scala` in `svc/players/` (P13): `PUT role-assignments/{operator}` requiring `roles.give`; refuses, naming the permission, when the giver's permissions do not include every permission the roles carry; then calls merchant's `brand-roles`; an audit record naming both operators and the roles (FR-008c)

### Permissions in every service (M)

Each task: replace `Routes.operator`/`operatorHolding` and handler role sets with
`Routes.operatorMay(slug, permission)` per route (FR-008), so that no role name is left in the service (`withAcl` where an endpoint mixes actions); start `RoleBags` with the service's own client of `players`' `roles` route and show in the service's status when the roles were last read; bind the service's
`features/<area>/operator-roles.feature` in its `GherkinSuite`, failing first; one line in its spec.

- [ ] T017 [P] M in `svc/wallet/` (W1): `adjustment.make`, `turnover.override`, `wallet.read`, `config.brand` in `api/AudienceEndpoints.scala`; amends spec 002
- [ ] T018 [P] M in `svc/payouts/` (Y1): `withdrawal.read`, `withdrawal.review`, `config.brand` in the ACLs of `api/OperatorEndpoint.scala`, `PlayerEndpoint.scala`, `ConfigEndpoint.scala`; remove `FinanceRoles` from `api/Answers.scala`; amends spec 007
- [ ] T019 [P] M in `svc/players/` (P1): the players rows of contracts/roles.md per route in `api/Endpoints.scala` and the `Operator*Routes`; remove `BlockRoles`; amends spec 008
- [ ] T020 [P] M in `svc/kyc/` (K1): `identity.read` on `GET /{p}` without documents, `identity.review` elsewhere, `config.brand` on policy, in `api/OperatorEndpoint.scala`; remove `ComplianceRoles`; amends spec 009
- [ ] T021 [P] M in `svc/responsible-gambling/` (G1): `protection.read`, `protection.change`, `config.brand` in `api/Endpoints.scala`; amends spec 010
- [ ] T022 [P] M in `svc/rewards/` (RW1): `reward.read` (list and one reward), `reward.grant`, `reward.revoke`, `offer.manage` in `api/RewardAdminEndpoint.scala`, `OffersEndpoint.scala`; amends spec 011
- [ ] T023 [P] M in `svc/game-provider/` (GP1): `player.read` on rounds, `config.games`, `callback.resolve` in `api/Endpoints.scala`; amends spec 003
- [ ] T024 [P] M in `svc/engagement/` and `svc/support-agent/` (E1): `presence.read`, `send.release`, `config.crm`, `config.agent`; amends specs 014, 017
- [ ] T025 [P] M in `svc/reporting/`, `svc/attribution/`, `svc/public-api/` (E1, E3): `api/Access.scala` reads the matrix (`dashboard.read`, `report.*`, `export.player`, `config.brand`); `config.attribution`, `fact.redeliver`; the status route under `config.brand`; amends specs 015, 012, 016
- [ ] T026 Amend `specs/001-brands-and-projects/` (the realm role `brand_support`, the brand's `backoffice-<slug>` client, the module `backoffice`, `contracts/routes.md`'s operator class now by permission) and `specs/008-players/` (the brand's roles), each with one line under *Amended by 030*

### Deployment

- [ ] T027 Make `tools/modules/src/main/scala/io/eitheror/casino/modules/Render.scala` pass a module service's `hosting: "web"` through to the descriptor (D1), with a test in `tools/modules/src/test/`
- [ ] T028 Teach `tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala` to upsert `brand_support` and the brand's confidential client `backoffice-<slug>` (standard flow, PKCE, audience `eitheror-casino`, the brand's backoffice address) into an existing realm, its secret into the brand's secrets (D3), with its test; verify V4 (the token's audience and roles) and add a mapper if it fails
- [ ] T029 Write `modules/backoffice/module.json` (depends on `core`; `backoffice` with `hosting: "web"`, exposed at the brand's backoffice host; `backoffice-state`, not exposed), add the backoffice host and the shared projects' addresses to the brand's values schema and `brands/goldvibe/values.json`, and its section in `modules/README.md` (D2, R8, R17)

### `backoffice-state`

- [ ] T030 Write `st-test/session/SessionSuite.scala` and `st-test/preferences/PreferencesSuite.scala` first, from data-model.md: one refresh for two concurrent `token` calls near expiry; a refused refresh ends the session; tokens cleared on `Ended`; a preference naming a player or an unknown key is refused; at most 20 jobs
- [ ] T031 Implement `st/session/Session.scala` (event sourced), `st/session/RealmClient.scala` (code exchange with PKCE, refresh, revoke), `st/session/SessionsBySubject.scala` and the expiry timer
- [ ] T032 Implement `st/preferences/Preferences.scala` (key value; FR-038) and `st/Erasure.scala` (the `operator/<subject>` handler)
- [ ] T033 Implement `st/session/SessionEndpoint.scala` and `st/preferences/PreferencesEndpoint.scala` as contracts/routes.md, each admitting only `Callers.service("backoffice")`; no token in any log or trace
- [ ] T034 Write `st-test/it/StateIt.scala` over Postgres and a Keycloak container (quickstart §4) and make it pass

### The page's server

- [ ] T035 Write `t/unit/session.test.ts`, `t/unit/proxy.test.ts`, `t/unit/routes.test.ts` first: no cookie is `401 signed_out`; a non-GET without `X-Backoffice` or with a foreign `Origin` is `403`; a row not in the table is `404`; an owner's answer is returned as given; a proxy's own answer becomes `503 source_unavailable`; no response or log line holds a token
- [ ] T036 Implement `s/main.ts` (Hono on `PORT`, graceful stop, `/healthz`, static assets immutable and `index.html` `no-cache`: FR-042) and `s/session.ts` (the `__Host-bo` cookie, the token cache to expiry, the client of `backoffice-state`)
- [ ] T037 Implement `s/auth.ts`: `/auth/sign-in` (PKCE, `return` restricted to this site), `/auth/callback`, `/auth/sign-out`; `s/roles.ts` (the brand's roles from `players`' `roles`, read again at most every 10 seconds); `/api/permissions` (those roles and what each carries); `/api/me` (the brand from `CASINO_BRAND`, `403 brand_not_in_roles` for an operator whose roles do not name it, modules from players' `modules`, the operator's permissions opened from their roles with the brand's bags)
- [ ] T038 Implement `s/routes.ts` (the table: method, path template, service, where it is (the brand's project or a shared project), permission; FR-007: a route not in an owner is an amendment, never a workaround) for players, wallet, payouts, kyc, responsible-gambling, game-provider, rewards' reads and merchant's deposits, and `s/proxy.ts` (`/api/s/<service>/<rest>`, the brand's slug put where a template has `{brand}`, a shared project reached at its address from the brand's values, 15 s bound, pass-through without parsing); generate the typed clients in `s/owners/` from the table
- [ ] T039 [P] Implement `sh/generated/` (copied by `just backoffice-contract`), `sh/access.ts` (does this operator hold this permission in this brand, from `/api/me`; and, for a disabled action, a role whose bag holds it, from `GET /api/permissions`; no bag is written in the page), `sh/money.ts` (minor units to text by the contract's table; no float), `sh/dates.ts` (the brand's zone, named; a filter's end is its day's last instant), each with unit tests in `t/unit/` covering JPY, EUR and KWD (FR-013, FR-015, SC-007)
- [ ] T040 [P] Implement `sh/messages/en.ts` (typed keys), `sh/messages/index.ts` (`intl-messageformat`), `sh/refusals.ts` (owner code to message and next step; an unknown code shown as given) and a lint rule in `services/backoffice/eslint.config.js` refusing a string literal in JSX outside the catalogue (FR-006, FR-040a)

### The frame and shared components

- [ ] T041 Implement `w/tokens.css` from contracts/design.md (dark default, light; FR-039: no eitheror brand colour, mark or type) and `t/unit/contrast.test.ts` that computes every named pair's contrast and fails under AA
- [ ] T042 Implement `w/frame/` (the icon rail, the top bar with brand, search box, freshness, jobs, notices and operator; the router; the sign-in redirect; the below-1280 read-only mode: FR-041; an area whose module the brand does not run absent from the rail, with the modules page saying why; a link to the content system's own editor where the brand has one, and no content screen: FR-043)
- [ ] T043 [P] Implement `w/components/list/` : a table at 36 px rows that keeps filters, sort, page and scroll in the URL and restores them on return (FR-010), shows total and whether complete (FR-009), keeps rows while refetching and marks changed rows (FR-012), and shows freshness and *provisional* (FR-011)
- [ ] T044 [P] Implement `w/components/action/`: the action dialog that states the effect before confirmation, takes a reason where the owner records one, shows a disabled action with the role it needs, and shows a refusal with its reason and next step (FR-005, FR-006, FR-025), and `w/actions/index.ts` (one description per action: effect message, reason rule, the states it is offered in)
- [ ] T045 [P] Implement `w/components/money.tsx`, `date.tsx`, `status.tsx` (word and icon, never colour alone: FR-040), `links.tsx` (a record links to the records it names: FR-024), `date-input.tsx` (a birth date typed: FR-015)

### The test harness

- [ ] T046 Implement `t/doubles/`: a scripted HTTP double per owner at the calling address (`<service>.<project>` paths; verify V3 and listen under the dotted name if `ankka local web` cannot map it), answering from fixtures in `t/doubles/fixtures/`, able to be told to fail, to be slow, and to assert the `Authorization` each call carried; and a double realm
- [ ] T047 Implement `t/features/` : the `playwright-bdd` config over `features/backoffice/`, the world (sign in as a role, a seeded brand in the doubles), and `t/a11y/axe.spec.ts` that visits every route in dark and light and fails on a serious finding (SC-008)

**Checkpoint**: an operator can sign in and reach an empty frame; every service refuses by action.

---

## Phase 3: User Story 1: sign in to the brand's backoffice, see only what you may do (P1) 🎯 MVP

**Goal**: FR-001 to FR-008d, FR-011. **Independent test**: sign in as an operator of the brand in
each role, as `platform_admin`, and as an operator of another brand only; who is let in, and each
action's availability, match what the owner admits; a role made and given in the backoffice admits
exactly its permissions.

- [ ] T048 [US1] Bind `features/backoffice/access.feature` (7 scenarios), `roles.feature` (2) and `freshness.feature` in `t/features/steps/access.steps.ts`, failing first; what each service admits is held by its own `operator-roles.feature` (T017–T025)
- [ ] T049 [US1] Implement `w/areas/access/`: the brand shown from `/api/me`, the page that names the role needed for an operator whose roles do not name the brand, and signing an operator out of the brand, saying why, when they lose it (edge case 1)
- [ ] T050 [US1] Implement `s/events.ts`: `/api/events` as server-sent events with `permissions_changed` (the server compares the operator's permissions at each read of the roles and of the token) and `signed_out`; the page re-reads `/api/me` on it, so an action appears or goes without signing in again (FR-008b); long polling if V5 fails
- [ ] T051 [P] [US1] Add `GET …/status` (F) to `svc/players/`, `svc/wallet/`, `svc/payouts/`, `svc/kyc/`, `svc/responsible-gambling/`, `svc/rewards/` (P2, W4, Y5, K4, G4, RW3): verify V2 in one and use the view's own consumer if a view's last applied time cannot be read; each with its test
- [ ] T052 [US1] Implement `s/composite/freshness.ts` and `w/components/freshness.tsx`; wire it into `w/components/list/` and the top bar
- [ ] T053 [US1] Implement `w/areas/preferences/` (theme, worklist kinds, online notices) over `/api/me` and `PUT` preferences through the server
- [ ] T054 [US1] Implement `w/areas/team/roles.tsx`: the brand's roles with what each carries; create a role (name, description, permissions grouped by area with their catalogue descriptions); change, reset and delete, each stating its effect (who gains or loses what) before it is confirmed; the owner's refusals shown with their reasons (FR-008b, FR-008d)
- [ ] T055 [US1] Implement `w/areas/team/assign.tsx`: an operator's roles for the brand, given and taken away through players' `role-assignments`; a role carrying a permission the giver lacks shown disabled, naming the permission (FR-008c)

**Checkpoint**: US1's scenarios pass; `t/it/role-sweep` (T125) can already run against the doubles.

---

## Phase 4: User Story 2: the worklist (P1)

**Goal**: FR-016 to FR-018. **Independent test**: one item of each kind; each role's worklist holds
exactly its kinds, oldest first; acting on one removes it and keeps the place.

- [ ] T056 [US2] Bind `features/backoffice/worklist.feature` (4 scenarios) in `t/features/steps/worklist.steps.ts`, failing first
- [ ] T057 [P] [US2] `svc/payouts/` Y2: `sort=oldest`, `counts {pendingReview, needsReview}` on the queue, with tests
- [ ] T058 [P] [US2] `svc/kyc/` K2: paging on `queue` keeping `counts`, with tests
- [ ] T059 [P] [US2] `svc/responsible-gambling/` G2: `cases?status=` brand-wide, paged, oldest first, with tests
- [ ] T060 [P] [US2] `svc/players/` P11: paging and total on `fraud-cases`, oldest first for open, with tests
- [ ] T061 [P] [US2] `svc/game-provider/` GP3 and `svc/attribution/` E2: paging and total on `unacknowledged` and on `attribution-webhooks/undelivered`, with tests
- [ ] T062 [P] [US2] `svc/psp-gateway/` T1: `total` on `outcomes/undelivered` and `/refused`, with tests
- [ ] T063 [US2] Implement `s/composite/worklist.ts` from contracts/sources.md: only the kinds the roles admit, in parallel, 3 s each, `unavailable` with its reason, totals from the sources; unit tests in `t/unit/worklist.test.ts`
- [ ] T064 [US2] Implement `w/areas/worklist/` as the first page: kinds with counts, one row per item oldest first, opening an item's investigation, returning to the same place after acting (FR-018), an item acted on by someone else showing who and what with no action

**Checkpoint**: an operator's day starts from the worklist.

---

## Phase 5: User Story 3: investigate a player (P1)

**Goal**: FR-019 to FR-024. **Independent test**: find a seeded player by each kind of reference;
read the timeline end to end; stop one source and see it named.

- [ ] T065 [US3] Bind `features/backoffice/investigation.feature` (5 scenarios) in `t/features/steps/investigation.steps.ts`, failing first
- [ ] T066 [P] [US3] `svc/players/` P2: the list as a view query with paging, total, the filters and `facets=true`, binding `features/players/lists.feature`, with a suite at 10,000 rows
- [ ] T067 [P] [US3] `svc/players/` P3, P4: `from`, `to`, `after`, `total` on `activity`; `GET notes`, with tests
- [ ] T068 [P] [US3] `svc/players/` P5, P6, P10: a player's sessions with their game sessions and gaps, the brand-wide sessions list, force-close; a session's bets (no more `501`); `bets` filters and `staked`, `won`, `net`, with tests
- [ ] T069 [P] [US3] `svc/game-provider/` GP2: `total` on `rounds`; `rounds/by-round/{roundId}`; `rounds/by-transaction/{txId}`, with tests
- [ ] T070 [P] [US3] `svc/kyc/` K3 and `svc/responsible-gambling/` G3: `{p}/decisions` and the operator's `{p}/history`, with tests
- [ ] T071 [P] [US3] `svc/merchant/` H1, H2: an operator holding `brand_<slug>` reads the sub-brand that names that brand, and no other, on `/v1/payments/cashier`, `/cashier/summary`, `/v1/payments/{id}`; `?reference=`, with tests; amends spec 004
- [ ] T072 [US3] Implement `s/composite/search.ts` and its classifier from contracts/sources.md, with `t/unit/search.test.ts` (free text is never sent as an id; every match listed by kind; a value of two shapes asked of both)
- [ ] T073 [US3] Implement `s/composite/standing.ts` (the parts in parallel, each with its own state) and `s/composite/timeline.ts` (the k-way merge by cursor, gaps named, actors' names from merchant's operators, lines as message keys), with `t/unit/timeline.test.ts` covering order, a late source and a failed source
- [ ] T074 [US3] Implement `w/areas/search/` (the one search in the top bar and its results by kind) and `w/areas/players/list.tsx` (the list with facets)
- [ ] T075 [US3] Implement `w/areas/players/page.tsx`: the standing fitting 1440 by 900 (FR-020) with an erased player shown under the pseudonym only (edge case 5), and the tabs' frame
- [ ] T076 [US3] Implement `w/areas/players/timeline.tsx` (filter by kind and period, gaps named, export as the owner's job: FR-021, FR-022) and `w/areas/players/rounds.tsx`, `sessions.tsx`, `wallets.tsx` (a round's bets and wins with staked, won, net and *unsettled*; a game session's rounds; a player session's game sessions and gaps: FR-023)
- [ ] T077 [US3] Implement `w/areas/players/profile.tsx`, `notes.tsx`, and the standing actions (suspend, reinstate, close, reopen, reset password, verification link, clear flags, erasure request, date of birth, consents) through `w/actions/`

**Checkpoint**: phase 1 of FR-045 is usable: access, worklist, investigation. Run quickstart §5 and
the moderated test for SC-002.

---

## Phase 6: User Story 4: a withdrawal from request to payment (P1)

**Goal**: FR-014, FR-026. **Independent test**: one request through each path; each state, amount
and actor shown; only that state's actions offered.

- [ ] T078 [US4] Bind `features/backoffice/withdrawals.feature` (4 scenarios) in `t/features/steps/withdrawals.steps.ts`, failing first
- [ ] T079 [P] [US4] `svc/payouts/` Y3: `operatorId` on a confirmed or failed outcome; `balancesAtRequest` and `restrictedAtRequest` recorded when a request is made (a new field on the request's first event, absent on older ones), with tests
- [ ] T080 [P] [US4] `svc/payouts/` Y4: paging on `players/{p}/withdrawal-history`, with tests; in `s/composite/`, resolve a name or an email typed in the queue's search to player ids through players' search before asking payouts (Y2)
- [ ] T081 [US4] Implement `w/areas/withdrawals/queue.tsx` (by state, the two counts, filters) and `w/areas/players/withdrawals.tsx` (every request in every state)
- [ ] T082 [US4] Implement `w/areas/withdrawals/request.tsx`: requested, held, paid and voided apart and why; balances then and now; identity status and recent deposits; destination; every state with who moved it; the payment's attempts; and exactly the state's actions, each stating its effect (SC-003)

---

## Phase 7: User Story 5: correct money and say why (P2)

**Goal**: FR-027, FR-028. **Independent test**: adjust one player with a cause; adjust five, one
refused; repeat and nothing is made twice.

- [ ] T083 [US5] Bind `features/backoffice/corrections.feature` (3 scenarios) in `t/features/steps/corrections.steps.ts`, failing first
- [ ] T084 [US5] `svc/wallet/` W2: `cause`, `amountMinor`, `before` and `after` in the answer, `cause` and `actor` on the transaction row and the fact, with tests (a repeat under the same key answers the first outcome)
- [ ] T085 [US5] Implement `s/composite/batch.ts` (the batch id, the key `batch/<id>/<n>`, eight at a time, at most 500 rows, each row's outcome streamed) with `t/unit/batch.test.ts` (a repeat makes none twice; a player twice is two rows)
- [ ] T086 [US5] Implement `w/areas/corrections/adjust.tsx` (credit or debit, real or a restricted funds record, the brand's reasons with free text, the cause picker, before and after, the refusal of an over-large debit) and `w/areas/corrections/accounts.tsx` (non-player accounts)
- [ ] T087 [US5] Implement `w/areas/corrections/batch.tsx` (paste or upload rows, validate, run, each row made, refused with its reason, or already made)

**Checkpoint**: phase 2 of FR-045. Run the moderated test for SC-003.

---

## Phase 8: User Story 6: a player's rewards (P2)

**Goal**: FR-026 for rewards. **Independent test**: a record in each state shows its actions and
explanations; a grant to a suspected duplicate shows why; a forfeit closes the record.

- [ ] T088 [US6] Bind `features/backoffice/rewards.feature` (3 scenarios) in `t/features/steps/rewards.steps.ts`, failing first
- [ ] T089 [US6] `svc/rewards/` RW2: `allowedBy` beside `reason` on an ineligible grant, with tests
- [ ] T090 [US6] Extend `s/routes.ts` with rewards' and offers' rows; implement `w/areas/rewards/list.tsx` (brand-wide, search and state) and `w/areas/players/rewards.tsx` (state in the glossary's words, wagering progress, open rounds, free rounds used of given as the player sees them, the state's actions with their effects)
- [ ] T091 [US6] Implement `w/areas/rewards/grant.tsx` (from an offer or by hand; the refusal with its reason and what would allow it)
- [ ] T092 [US6] Implement `w/areas/rewards/offers.tsx` (create, edit, order, deactivate, free rounds values, the games picker, dependants named before a change)

---

## Phase 9: User Story 7: identity and responsible gambling (P2)

**Goal**: story 7. **Independent test**: a player from documents awaiting review to verified; a
limit with its pending change; a break started and lifted; a fraud case dismissed and its flags
cleared.

- [ ] T093 [US7] Bind `features/backoffice/compliance.feature` (4 scenarios) in `t/features/steps/compliance.steps.ts`, failing first
- [ ] T094 [US7] Implement `s/composite/document.ts` (kyc's `views/{d}` streamed for this operator; the document id the only input: FR-031) with a unit test that no address in a request is ever fetched
- [ ] T095 [US7] Implement `w/areas/identity/queue.tsx` (first-in first-out, place kept) and `w/areas/players/identity.tsx` (documents viewed, approved or rejected with a reason, mode, required documents, override, source of funds, flags, the final decision)
- [ ] T096 [US7] Implement `w/areas/players/protection.tsx` and `w/areas/protection/cases.tsx` (limits set and removed with the pending change and when it takes effect; breaks; self-exclusion; return; cases with notes and closing), each action stating what it changes for the player
- [ ] T097 [US7] Implement `w/areas/fraud/cases.tsx` (list, review, dismiss with the flags it clears) and `w/areas/configuration/identity-policy.tsx`, `protection-settings.tsx`

**Checkpoint**: phase 3 of FR-045.

---

## Phase 10: User Story 8: the dashboard and the reports (P2)

**Goal**: FR-032, FR-033. **Independent test**: two currencies and five countries give two sets of
figures and five countries on the map; each report exports with its reference.

- [ ] T098 [US8] Bind `features/backoffice/dashboard.feature` (3 scenarios) in `t/features/steps/dashboard.steps.ts`, failing first
- [ ] T099 [US8] Extend `s/routes.ts` with reporting's rows; implement `s/composite/jobs.ts` and `w/areas/jobs/` (start, poll `documents/{reference}`, notify wherever the operator is through `/api/events`, download: FR-029)
- [ ] T100 [P] [US8] Implement `w/components/charts/` (line and candle, donut, dot grid, the world map from `d3-geo` with a bundled map and a table beside it) in the tokens of contracts/design.md
- [ ] T101 [US8] Implement `w/areas/dashboard/` (figures per currency never summed, trend, the map, top games and players, rewards, payment health, each with freshness)
- [ ] T102 [US8] Implement `w/areas/reports/` (revenue, players, transactions, rewards, payment providers, day-end, compliance exports, the dispute report; a period; `incomplete_period` shown with the first complete day; export by the owner with its reference number)

**Checkpoint**: phase 4 of FR-045.

---

## Phase 11: User Story 9: configuration, payments and the team (P3)

**Goal**: FR-034, FR-035, parity's phase 5. **Independent test**: one value changed in each area
shows as differing; a routing rule simulated and deployed; an invitation above the inviter's role
refused.

- [ ] T103 [US9] Bind `features/backoffice/configuration.feature` (3 scenarios) in `t/features/steps/configuration.steps.ts`, failing first
- [ ] T104 [P] [US9] `svc/wallet/` W3: `PUT wallet-config/rewards`, with tests
- [ ] T105 [P] [US9] `svc/players/` P7, P8, P9: manager tags; seen devices recorded at log-in as `Personal` under the player's subject and erased with the player; what a player plays; binding `features/players/manager-tags.feature`, `devices.feature`, `games-played.feature`
- [ ] T106 [P] [US9] `svc/merchant/` H3: deposit bounds per method and currency, enforced by the deposit workflow, binding `features/payments/deposit-bounds.feature`
- [ ] T107 [P] [US9] `svc/merchant/` H4, H5 and `svc/psp-gateway/` T2: the test payment through the gateway, never crediting a wallet; paging on `/v1/operators`, with tests; amends specs 004, 005
- [ ] T108 [US9] Extend `s/routes.ts` with the configuration, merchant, psp-gateway, ingest, attribution, support-agent and engagement rows; implement `w/components/config-form.tsx` that marks each value differing from the brand's default or its licence profile (FR-034)
- [ ] T109 [P] [US9] Implement `w/areas/configuration/brand.tsx` (sites, countries, currencies and zone, withdrawal, wagering and reward settings, reporting settings, the modules page with why a module is off)
- [ ] T110 [P] [US9] Implement `w/areas/configuration/games.tsx` (providers, provider configuration and catalogue refresh, developers, categories, per-game overrides) and `callbacks.tsx` (unacknowledged calls: replay, resolve)
- [ ] T111 [P] [US9] Implement `w/areas/configuration/crm.tsx`, `agent.tsx`, `attribution.tsx` (networks, webhooks, signing keys, undelivered facts: resend, discard), `sends.tsx` (parked sends: retry, discard)
- [ ] T112 [US9] Implement `w/areas/payments/hierarchy.tsx`, `providers.tsx` (configurations: create, edit, activate, disable, delete, test payment), `terminals.tsx`, `deposit-bounds.tsx`, `merchant.tsx`, with the level picker and banner kept here only (FR-004)
- [ ] T113 [US9] Implement `w/areas/payments/routing.tsx` (the tree as the original draws it; draft, simulate, deploy, retire, roll back: FR-035), `outcomes.tsx` (undelivered and refused outcomes, redrive), `lost-answers.tsx`, `deposits.tsx` (list, detail, analytics, uncredited and retry)
- [ ] T114 [US9] Implement `w/areas/team/operators.tsx` (list, invite with a payments role at or below one's own and a level, resend, reset, revoke, rescope; an operator's roles for the brand through T055's screen) and `w/areas/players/devices.tsx`, `games.tsx`, the tags and VIP tier controls

**Checkpoint**: phase 5 of FR-045.

---

## Phase 12: User Story 10: the affiliate programme (P3)

**Goal**: FR-037, parity's phase 6. **Independent test**: approve an account, attach a plan, close a
payout period, dismiss a fraud flag with a reason.

- [ ] T115 [US10] Bind `features/backoffice/affiliates.feature` (2 scenarios) in `t/features/steps/affiliates.steps.ts`, failing first
- [ ] T116 [P] [US10] `svc/affiliate-programme/` A1, A2: `{items, total}` on `accounts`; `accounts/{a}/portal/{view}` for managers, read-only and audited as `VIEWED_AS`, binding the existing scenario "a manager's read-only view of an affiliate shows what the affiliate sees and changes nothing" in `features/programme/managers.feature`; amends spec 013
- [ ] T117 [US10] Extend `s/routes.ts` with the programme's rows (the project's address is a value of the brand, so spec 028 can move it); a manager sees the programme only, for this backoffice's brand
- [ ] T118 [P] [US10] Implement `w/areas/affiliates/dashboard.tsx`, `plans.tsx`, `links.tsx`, `creatives.tsx`, `settings.tsx`, `messages.tsx`, `managers.tsx`
- [ ] T119 [US10] Implement `w/areas/affiliates/accounts.tsx` and `account.tsx` (create, approve, reject, status, edit, notes, audit, password, email, network, reparent, adjustments, view as the affiliate), `payouts.tsx` (by period: close, status, export), `fraud.tsx` (dismiss with a reason)

---

## Phase 13: User Story 11: reach players who are online (P3)

**Goal**: FR-036. **Independent test**: turn the notice on, bring a player online, open them from
the notice, send a previewed message.

- [ ] T120 [US11] Bind `features/backoffice/outreach.feature` (2 scenarios) in `t/features/steps/outreach.steps.ts`, failing first
- [ ] T121 [US11] Extend `s/events.ts`: for an operator who asked, take engagement's ticket, read its `agent-stream`, and relay `player_online` for the current brand; reconnect and re-read `presence` after an overflow
- [ ] T122 [US11] Implement `w/areas/outreach/` (the notice that opens the player; who is online) and `w/areas/players/messages.tsx` (merge tags, the preview with tags filled before sending, the player's sends)

**Checkpoint**: phase 6 of FR-045; every row of parity.md not dropped or moved is reachable (SC-001).

---

## Phase 14: Polish and cross-cutting

- [ ] T123 Write `t/it/seed.ts` and the recipe `backoffice-seed`: 10,000 players, 1,000 rewards, 5,000 withdrawal requests and amounts in JPY, EUR and KWD in a brand on `dev-kind`, through the services' own routes
- [ ] T124 Write `t/it/lists.it.ts` (SC-005: every list's total equals its source's count and its last page is reachable) and `t/it/money.it.ts` (SC-007)
- [ ] T125 Write `t/it/role-sweep.it.ts` (SC-006): for each of the five default roles, a role made in the backoffice and one payments role, take every action the page offers and expect admission, and call every action it does not offer and expect the owner's refusal
- [ ] T126 Write `t/it/investigations.it.ts` (SC-004: the three questions answered from the pages) and `t/it/degraded.it.ts` (a paused service named in the worklist and timeline; a rollout with a page open: FR-017, FR-022, FR-042)
- [ ] T127 Write `t/it/browser.it.ts`: every request of a full session goes to the backoffice's address only, and no script-readable storage or response holds a token (FR-002, FR-003)
- [ ] T128 Write `t/it/roles.it.ts` on `dev-kind`: a permission added to a role is offered and admitted within 15 seconds without signing in again, and removed likewise; nothing is deployed or restarted; giving a role with a permission the giver lacks is refused; the last role carrying `roles.change` cannot lose it; a service started with `players` paused answers unavailable, and one already running keeps its last copy (FR-008b, FR-008c, FR-008d)
- [ ] T129 [P] Write `t/unit/parity.test.ts`: every row of `specs/030-backoffice/parity.md` not dropped or moved names a route in `s/routes.ts` and a page in `w/areas/` (FR-030, SC-001), failing on a row with neither; and check FR-044: no other spec's route or manifest names `backoffice`, and nothing under the original is changed
- [ ] T130 [P] Amend `specs/019-spinvibe-migration/` (X1): the role mapping of contracts/roles.md, the report published for review before it runs, ambiguous operators given `brand_support` and listed (FR-008a), binding `features/migration/operator-roles.feature`
- [ ] T131 [P] Write `tests/original/backoffice-screens/` (the Playwright script, the index, `README.md`) and record the original on kind, one capture per screen per role (research R19); compare with the built screens and note each intended difference in `specs/030-backoffice/checklists/design-review.md`
- [ ] T132 [P] Write `specs/030-backoffice/checklists/moderated.md` (the script and result sheet for SC-002 and SC-003) and leave the results to the product owner
- [ ] T133 [P] Measure research R20's targets with `t/it/perf.it.ts` on the seeded brand and record them in `specs/030-backoffice/research.md`
- [ ] T134 Run the a11y suite on every route in dark and light and fix every serious finding (SC-008); check keyboard reach of every action
- [ ] T135 [P] Update `CLAUDE.md` (spec 030's row, the two services, the recipes), `services/backoffice/README.md`, `services/backoffice-state/README.md`, `shared/README.md`, `brands/_realm/README.md`, and `brain/projects/ankka-modules.md`'s pointer in the eitheror brain
- [ ] T136 Apply the brand with the `backoffice` module in its entitlement to `dev-kind`, run quickstart §6 end to end, and run `just features`, `just test`, `just modules validate --all` and every amended service's suite once more

---

## Dependencies

- Phase 1 → Phase 2 → every story. T001 gates everything: if V1 fails, nothing after it is built.
- In Phase 2: T007 → T008 → T009; T012 → T013 → T014 → T017–T025 (M: every service reads the roles from `players`) and T039; T015 → T016; T027, T028 → T029; T030 → T031–T033 → T034; T035 → T036–T038; T041 → T042–T045; T046 → T047.
- US1 → US2 and US3 (both need the sign-in and the permissions). T054 and T055 need T014 and T016. US3 → US4 (the player page holds the withdrawals tab) → US5 uses US3's player page only.
- US6, US7, US8 each need US3 and nothing of each other. US9 needs US1 only; its player tabs (T114) need US3. US10 needs US1. US11 needs US3 and T050.
- An owning service's amendments in a story come before that story's composite routes and pages.
- Polish needs every story; T123 → T124–T127.

## Parallel examples

- Foundation: T017 to T025 are nine services, one sbt process each: run them together after T014; T015 (merchant) runs beside T012 to T014 (players).
- US2: T057 to T062 are six services; T063 can be written against the doubles meanwhile.
- US3: T066 to T071 are five services; T072 and T073 against the doubles.
- US9: T104 to T107 are four services; T109 to T111 are separate areas.

## Implementation strategy

1. Phases 1 and 2, then US1: the smallest thing that proves the design (sign-in on the server,
   permissions in every service, roles changed at run time, one pass-through). Stop and check V1 to
   V6 here.
2. US2 and US3 complete FR-045's phase 1: demonstrable, and the moderated test for SC-002.
3. US4 and US5 (phase 2), then US6 to US8 (phases 3 and 4): everything an operator does daily.
4. US9 to US11 (phases 5 and 6), then polish. Each checkpoint leaves a backoffice usable on its own.

Commit after each task or small group; a task is done when its tests pass and `just features` is
green.
