# Tasks: the shared audit

**Input**: Design documents from `specs/018-audit-and-proposals/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{routes,ingest,recording}.md, quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test (I)
and the living features the scenarios (VII); parity tests are a deliverable, written before the
component they hold to account and failing first (ankka-port §7). Every story opens with its tests.

**Organization**: By user story. US2 (the shared store holds every brand's actions and no real id) is
the MVP: without it nothing else can be observed. US1 (the brands' records) runs beside it in other
services; US3 is the rest of P1; US4–US6 are P2. The pure packages, the core entities, the registry,
the consumers' decoding and the kit are foundational.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US6 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `a/` stands for `services/audit/src/main/scala/io/eitheror/casino/audit/`
- `a-test/` stands for `services/audit/src/test/scala/io/eitheror/casino/audit/`
- `w/` for `services/wallet/src/main/scala/io/eitheror/casino/wallet/`, `pl/` for
  `services/players/src/main/scala/io/eitheror/casino/players/`, `pg/` for
  `services/affiliate-programme/src/main/scala/io/eitheror/casino/programme/` (patterns copied from
  `pg/`, never changed)
- `O/` is `~/Documents/projects/eitheror/eitheror-platform`
- The service's sbt commands run in `services/audit/`; everything else at the root; publish the
  contract as `CASINO_VERSION=0.2.0-018-SNAPSHOT`
- A recording run is made only against a local original, never a shared or production one; kubectl
  always with `--context kind-…`

---

## Phase 1: Setup

**Purpose**: the build, the contract, realm and tool additions, the manifests, the recipes and CI.

- [X] T001 Create `services/audit/` on `services/engagement/`'s template: `build.sbt` (Scala 3.9,
  `ankkaVersion = "0.16.0"`, the six ankka modules, `casino-contract` at `CASINO_VERSION`, the eviction
  rule engagement's `build.sbt` explains), `service.json` (its database, secret store and
  `"buckets": [{"name": "exports", "exposure": "signed"}]`, no `personal`), `README.md` (a stub naming
  plan.md's components), `a/AuditService.scala` registering nothing yet
- [X] T002 [P] Add `Routes.auditCompliance(slug)` (staff issuer, `brand_<slug>` and `brand_compliance`),
  `Routes.auditInstallation` (`audit_compliance`), `Routes.reidentifier(slug)` (brand compliance and
  `reidentify_<slug>`) and `Routes.installationOwner` (`platform_admin`), each refusing with a named
  code, to `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`, with cases in
  `contract/src/test/scala/io/eitheror/casino/contract/access/RoutesSuite.scala` (research R8)
- [X] T003 [P] Add the realm role `audit_compliance` to `brands/_realm/eitheror-operators.json`; make
  `tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala` `ensure` create
  `reidentify_<slug>` beside `brand_<slug>`, with a case in its suite
- [X] T004 [P] Render `AUDIT_BRANDS` (the brands that run `core`) for `reasoner/audit` in
  `tools/modules/src/main/scala/io/eitheror/casino/modules/Render.scala` beside `PROGRAMME_BRANDS`,
  with a case in `RenderSuite`
- [X] T005 [P] `shared/reasoner/manifest.json`: the env of data-model.md *Settings* (`AUDIT_BRANDS`
  rendered), the project secret `keycloak-operators-admin` as `shared/programme/manifest.json`
  declares it, the bucket; `shared/payments/manifest.json`: a grant of `platform.audit` to
  `service:reasoner/audit` to consume, in the shape the shared manifest's schema takes for a topic;
  `just modules validate --all` passes
- [X] T006 [P] Add to `justfile` the recipes `audit-test`, `audit-it`, `audit-parity`, `audit-perf`,
  `audit-run` (as the programme's), and to `.github/workflows/ci.yml` the path filter and job for
  `services/audit/**`
- [X] T007 [P] Amend spec 001 FR-016 to name `payments`' grant of `platform.audit` to
  `reasoner/audit`, in `specs/001-brands-and-projects/spec.md` (research *Findings*)

---

## Phase 2: Foundational

**Purpose**: what every story needs: the pure packages, the codecs and settings, the store and the
registry, the consumers' decoding, the kit.

**⚠️ CRITICAL**: no story starts before this phase ends.

- [X] T008 `a/Settings.scala`: data-model.md *Settings*, each parsed with a named error, a generation
  above 1 refused without `AUDIT_READ_FROM`; `SettingsSuite` in `a-test/SettingsSuite.scala`
- [X] T009 [P] `a/keep/Keep.scala` and `a-test/keep/KeepSuite.scala`: FR-008a over every category ×
  actor kind × outcome (research R3)
- [X] T010 [P] `a/ids/Ids.scala` and `a-test/ids/IdsSuite.scala`: `digest` (HMAC-SHA256 under the
  registry key, `d-` and 32 hex), `isPerson`, `contains` (research R5)
- [X] T011 `a/stored/Stored.scala` and `a-test/stored/StoredSuite.scala`: `Stored`, `from(record,
  source, pseudonyms, key)`, `fromModule(change, brand)`, visibility per research R6 and the amount
  fields; a property that no real id of the input record appears anywhere in the output
- [X] T012 [P] `a/query/Query.scala` and `a-test/query/QuerySuite.scala`: the filters of
  contracts/routes.md, the opaque cursor, the SQL fragments for `Records`
- [X] T013 [P] `a/retention/Retention.scala` and `a-test/retention/RetentionSuite.scala`: `cutoff`,
  `raise` refusing below the current or the installation's
- [X] T014 `a/AuditCodecs.scala`: serializers for every stored type, named manifests
- [X] T015 [P] `a/store/Record.scala`: the key value entity of data-model.md (`keep` → `kept` |
  `collision`, `drop`), with its kit suite `a-test/store/RecordSuite.scala`
- [X] T016 [P] `a/registry/Pseudonym.scala` and `a/registry/Identity.scala` with kit suites: the first
  `issue` draws a UUIDv4, every later one returns it; `bind` written once, `conflict` otherwise
- [ ] T017 `a/registry/RegistryKey.scala`: the `registry-key` secret, generated at first start when
  absent, read once; a kit case that two starts read the same key
- [ ] T018 `a/store/Records.scala`: the view over `Record` state with `page`, `count`, `olderThan`;
  verify that a `drop` deletes the row (research R4), else write a tombstone and delete on it, and
  record which in `research.md` under `## Found during implementation`
- [ ] T019 [P] `a/ingest/Quarantined.scala`, `a/ingest/Quarantine.scala`,
  `a/ingest/SourceState.scala` with kit suites
- [ ] T020 `a/ingest/Ingest.scala`: one message in contracts/ingest.md's order (decode from bytes,
  check against the brand and `PersonalFields.names`, keep or pass over, pseudonymise, store, count),
  as a function the consumers call, with `a-test/ingest/IngestUnitSuite.scala` on the kits
- [ ] T021 `a-test/it/Kit.scala`: `AnkkaTestKit.start` over Postgres, an `InMemoryBroker` with two
  brands' `platform.audit` and `platform.modules` and `payments`' `platform.audit`, `MovableClock`,
  `FakeKeycloak`, the in-memory object store; `just audit-it` runs one empty suite on it

**Checkpoint**: the pure suites, the kits and the empty integration suite are green.

---

## Phase 3: User Story 2 - The shared audit holds every brand's records and no real id (Priority: P1) 🎯 MVP

**Goal**: every brand's and payments' kept records in the store, once, in pseudonyms.

**Independent Test**: two brands and payments write records; the store holds each kept record once,
every person by a pseudonym, the same person the same at one brand and another at the other.

### Tests for User Story 2

- [ ] T022 [P] [US2] `a-test/it/IngestSuite.scala`: two brands and payments read; kept and passed
  over; each quarantine reason; a redelivery changes nothing; a revoked source stops at its last record
  and its `SourceState` holds since when (FR-007–FR-012; the status route is T045's)
- [ ] T023 [P] [US2] `a-test/it/LeakSuite.scala`: every real id used in the run is absent from every
  `Records` row, every answer, every export and the captured log; only `Identity` holds them (SC-002)
- [ ] T024 [P] [US2] `a-test/features/SharedFeatures.scala`: bind `features/audit/shared.feature` (9)

### Implementation for User Story 2

- [ ] T025 [US2] `a/ingest/Consumers.scala`: `records-<slug>` per `AUDIT_BRANDS`, `records-payments`,
  each over `Serializer.bytes` at version `AUDIT_READ_GENERATION`, from `Earliest` or `AUDIT_READ_FROM`
  (research R14), calling `Ingest`
- [ ] T026 [US2] Register the store, registry, ingest components and consumers in
  `a/AuditService.scala`; T022–T024 pass

**Checkpoint**: the MVP: the shared store fills from every source.

---

## Phase 4: User Story 1 - Every action in a brand leaves one record (Priority: P1)

**Goal**: the brand services' records meet `records.feature`, with amounts where a person moved money.

**Independent Test**: drive each action against its service; read `platform.audit` from its kit.

### Tests for User Story 1

- [ ] T027 [P] [US1] Bind `features/audit/records.feature`'s adjustment, refused bet, captured deposit
  and repeated key in `services/wallet/src/test/scala/.../features/AuditRecordsFeatures.scala`;
  run them first and record what fails
- [ ] T028 [P] [US1] Bind its deposit limit in
  `services/responsible-gambling/src/test/scala/.../features/AuditRecordsFeatures.scala`
- [ ] T029 [P] [US1] Bind its email change in
  `services/players/src/test/scala/.../features/AuditRecordsFeatures.scala`; the support agent's
  scenario ignored with "spec 017 is not built"

### Implementation for User Story 1

- [ ] T030 [US1] `w/publish/Facts.scala` `audit`: `AdminAdjusted` and `TurnoverOverrideSet` records
  carry `amount`, `currency` and `target`; a refusal's causation is the command's key; balances before
  and after where the event carries them. A scenario no event can meet is asked, not guessed
  (research R15)
- [ ] T031 [US1] Whatever T028 and T029 find failing, fixed in their services' audit mappings; their
  suites green
- [ ] T032 [US1] SC-001: in `services/wallet/` and `services/players/` parity suites, assert that every
  replayed money command, refusal and operator action leaves exactly one record on `platform.audit`
  and none twice
- [ ] T033 [US1] FR-004: read each brand service's audit mapping (wallet, game-provider, payouts,
  players, kyc, responsible-gambling, rewards, engagement, markets, exchange) against FR-004's list,
  write the result per service to `research.md` under `## Found during implementation`; fix a gap in
  wallet or players with a test, record any other as a finding for its spec

**Checkpoint**: `records.feature` met where it is bound.

---

## Phase 5: User Story 3 - Compliance asks what happened, per brand and across brands (Priority: P1)

**Goal**: queries and exports, each a record.

**Independent Test**: load two brands; query and export as each role; read the records of the queries.

### Tests for User Story 3

- [ ] T034 [P] [US3] `a-test/it/QueryItSuite.scala`: each role's scope, every filter, the cursor,
  `retainedFrom`, the query records and the no-recursion rule (FR-013, FR-015, SC-005)
- [ ] T035 [P] [US3] `a-test/it/ExportSuite.scala`: CSV and JSON lines, the signed URL, the same key
  twice, a record at request and at completion (FR-014, SC-005)
- [ ] T036 [P] [US3] `a-test/export/ExportLinesSuite.scala`: `Csv.line` and `JsonLines.line`
  (research R10)
- [ ] T037 [P] [US3] `a-test/features/QueriesFeatures.scala`: bind `features/audit/queries.feature`
  (6)
- [ ] T038 [US3] `a-test/port/ParitySuite.scala` rows 2–5 of contracts/recording.md, failing first

### Implementation for User Story 3

- [ ] T039 [P] [US3] `a/export/Lines.scala`: `Csv`, `JsonLines`
- [ ] T040 [US3] `a/api/RecordsEndpoint.scala` and `a/api/Wire.scala`: `GET /v1/audit-records`, its
  record of itself, its refusals
- [ ] T041 [US3] `a/exports/Export.scala`, `a/exports/ExportFormat.scala`, `a/exports/Exports.scala`
  and `a/api/ExportsEndpoint.scala` (contracts/routes.md); registered; T034–T038 pass

**Checkpoint**: compliance reads and exports.

---

## Phase 6: User Story 4 - A pseudonym is re-identified on demand, and that is recorded (Priority: P2)

**Goal**: both directions, under the brand's re-identification role, recorded.

**Independent Test**: re-identify as each role; read the records.

### Tests for User Story 4

- [ ] T042 [P] [US4] `a-test/it/ReidentifySuite.scala`: who, pseudonym (answered or issued), refused
  and recorded, visible to the brand (FR-017, FR-018, SC-005)
- [ ] T043 [P] [US4] `a-test/features/ReidentificationFeatures.scala`: bind
  `features/audit/re-identification.feature` (4); the activity-tab scenario against `players`' view
  as spec 008 serves it

### Implementation for User Story 4

- [ ] T044 [US4] `a/api/IdentitiesEndpoint.scala` (contracts/routes.md); registered; T042–T043 pass

---

## Phase 7: User Story 5 - Platform actions on a brand are in the brand's record (Priority: P2)

**Goal**: module changes and erasure states as installation records; role grants recorded.

**Independent Test**: change a module, move an erasure through its states, grant a role; read the
brand's records.

### Tests for User Story 5

- [ ] T045 [P] [US5] `a-test/it/InstallationSuite.scala`: each module change and each erasure state
  an installation record beside the brand's, in one order, within 2 minutes of the change (SC-007);
  the status route and its installation sources (FR-019, FR-020, FR-024)
- [ ] T046 [P] [US5] `a-test/it/RolesSuite.scala`: grant, revoke, each recorded; an outside holding
  found once (FR-016)
- [ ] T047 [P] [US5] `a-test/features/InstallationFeatures.scala`: bind
  `features/audit/installation.feature` (13 runs); rollback, restore switch and every grant row ignored
  with "ankka publishes no project events (research R7)"
- [ ] T048 [P] [US5] `services/players/src/test/scala/.../erasure/ErasureAuditSuite.scala`: a record per
  erasure state

### Implementation for User Story 5

- [ ] T049 [US5] `pl/erasure/`: the erasure workflow writes a record per state (filed, held, applied,
  completed) through the service's audit path; T048 passes
- [ ] T050 [US5] `a/ingest/Consumers.scala`: `modules-<slug>` per brand over `Ingest` with
  `Stored.fromModule`
- [ ] T051 [P] [US5] `a/roles/KeycloakAdmin.scala` (copied from `pg/accounts/KeycloakAdmin.scala`,
  narrowed to role grants and listings), `a/roles/RoleGrant.scala`, `a/roles/Grants.scala`,
  `a/roles/RealmRead.scala` (the daily timed action), `a/api/RolesEndpoint.scala`
- [ ] T052 [US5] `a/api/StatusEndpoint.scala` with the installation sources; registered; T045–T047
  pass

---

## Phase 8: User Story 6 - The record survives everything (Priority: P2)

**Goal**: immutable, retained per brand, swept, re-read after a restore.

**Independent Test**: try to change and delete; erase a player; raise retention; sweep; raise the
generation.

### Tests for User Story 6

- [ ] T053 [P] [US6] `a-test/it/DurabilitySuite.scala`: every route and role finds no change or
  deletion (SC-008); an erased player's records and registry rows unchanged (FR-023); ten years kept
  beside seven; lower refused; the sweep removes only what is past and records its counts; a raised
  generation re-reads with nothing changed (FR-021, FR-022)
- [ ] T054 [P] [US6] `a-test/features/DurabilityFeatures.scala`: bind `features/audit/durability.feature`
  (5)

### Implementation for User Story 6

- [ ] T055 [US6] `a/sweep/RetentionEntity.scala`, `a/sweep/Sweep.scala`, `a/sweep/SweepTimer.scala`
  (daily), `a/api/RetentionEndpoint.scala`; registered; T053–T054 pass

---

## Phase 9: Polish & cross-cutting

- [ ] T056 Record the original for contracts/recording.md row 1 (`just test-e2e-scenario 2` in `O/`,
  then the query on port 19001) into `tests/port/audit/`, or translate it from the test's assertion when
  the original's stack cannot start; write `tests/port/audit/README.md` saying which
- [ ] T057 `a-test/port/ParitySuite.scala` rows 1 and 6; `just audit-parity` green
- [ ] T058 `a-test/perf/IngestPerf.scala` and `just audit-perf`: quickstart §4 (SC-003, SC-004) and a
  re-read of 24 hours of records after a raised generation (SC-006); write `services/audit/PERF.md`
  with the share kept, the lag, the first page at a year, the export time and the re-read time; a
  criterion missed is reported, not tuned away
- [ ] T059 [P] `services/audit/README.md`: components, settings, suites, how to re-read after a restore
- [ ] T060 [P] `tests/port/README.md`: the audit's section
- [ ] T061 [P] Spec 008's facts row for the erasure records, in `specs/008-players/spec.md`
- [ ] T062 `just features`, `just test`, `just audit-test`, `just audit-it` and the three neighbours'
  suites green; `sbt scalafmtCheckAll` in each changed build
- [ ] T063 A kind deploy of `shared/reasoner` beside `goldvibe` (quickstart §5), `--context kind-…`
  only; optional, and skipped with a note when the kind installation is not up

---

## Dependencies & Execution Order

- **Setup (1)** → **Foundational (2)** → stories.
- **US2 (3)** first: the MVP; US3–US6 read its store.
- **US1 (4)** depends only on Setup: it changes other services and can run beside Phase 2–3.
- **US3 (5)** after US2. **US4 (6)**, **US5 (7)**, **US6 (8)** after US2, independent of each other,
  except that T049 (players) must be published before T045's erasure rows can pass.
- **Polish (9)** last; T056 may run at any time.

### Parallel opportunities

- T002–T007 together.
- T009, T010, T012, T013, then T015, T016, T019 together.
- Each story's tests together, before its implementation.
- US1's three services in parallel with Phase 2.

### Parallel example: User Story 2

```text
T022 IngestSuite      T023 LeakSuite      T024 SharedFeatures
        └──────────────── then T025 Consumers → T026 registration
```

## Implementation Strategy

### MVP first

Setup, Foundational, US2: the store fills from every source with no real id. Stop and check
`LeakSuite` before anything reads it.

### Incremental delivery

US1 (the brands' records) beside it, then US3 (queries and exports), then US4–US6 in any order, then
the recordings and the performance run.
