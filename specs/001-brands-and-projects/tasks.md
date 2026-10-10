# Tasks: Brands and Projects

**Input**: Design documents from `specs/001-brands-and-projects/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The constitution makes the living features the acceptance tests (VII) and the
port method makes parity tests a deliverable (I); `GherkinSuite` bindings and the suites named in
quickstart.md are tasks here, written before the code they hold to account.

**Organization**: By user story. US1 is the MVP: a brand created from its manifest on kind.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US7 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `contract/` — the shared library (`io.eitheror:casino-contract`), packages
  `io.eitheror.casino.contract.{facts,money,access,personal,port}`
- `tools/brands/` — the manifest tool, package `io.eitheror.casino.brands`
- `brands/`, `installation/`, `tests/{port,stubs,original,fixtures}` — files, not code
- A later service lives in `services/<name>/`; none is created by this feature

---

## Phase 1: Setup

**Purpose**: the aggregate build, the Go module, the recipes everything after uses.

- [X] T001 Create the root `build.sbt` aggregating `contract` and `tools/brands` (Scala 3.9, the
      pinned ankka version as `ankkaVersion`, `com.thinkmorestupidless` artifacts `ankka-sdk`,
      `ankka-auth-oidc`, `ankka-testkit`, `ankka-controlplane-api`; `contract` publishes locally as
      `io.eitheror:casino-contract`), plus `project/build.properties` and `project/plugins.sbt`
- [X] T002 [P] Create `contract/` and `tools/brands/` skeletons: `src/main/scala` and
      `src/test/scala` package directories, each module's `README.md` naming its purpose in two lines
- [X] T003 [P] Create the Go module `tests/go.mod` (module `github.com/eitheror-io/ankka-casino/tests`,
      Go 1.22) covering `tests/stubs/` and `tests/original/`, with `tests/README.md` describing
      `port/`, `stubs/`, `original/` and `fixtures/` per `contracts/recording.md`
- [X] T004 [P] Add `just` recipes to `justfile`: `build` (`sbt compile`), `test` (`sbt test` and
      `go test ./tests/...`), `schemas` (`sbt contract/schemas`), `brands <cmd>` (`sbt "brands/run …"`),
      `stubs` (builds the seven stubs), keeping the existing `features` recipe
- [X] T005 [P] Add `.github/workflows/ci.yml` running `just features`, `sbt test`, `go test ./tests/...`
      and the schema drift check on every push

---

## Phase 2: Foundational

**Purpose**: what every story needs: the manifest model, money, the control-plane client, the
local installation, and the Gherkin binding.

**⚠️ CRITICAL**: no story starts before this phase is done.

- [X] T006 [P] Implement `contract/src/main/scala/io/eitheror/casino/contract/money/Money.scala`:
      `Money(minor: Long, currency: Currency)`, `Currency` as an ISO-4217 code, JSON codec that
      writes an integer and refuses a fraction with `AmountNotMinorUnits(amount)` naming the amount
      (FR-026); `Key.scala`: an opaque idempotency key, non-empty, with `MissingKey` (FR-027)
- [X] T007 [P] Copy `contracts/manifest.schema.json` to
      `tools/brands/src/main/resources/brand-manifest.v1.json` and implement
      `tools/brands/src/main/scala/io/eitheror/casino/brands/Manifest.scala`: the case classes of
      data-model.md (Brand, ServicePin, TopicDeclaration, GrantSpec, SecretRef), JSON decoding, and
      `Manifest.validate` running the schema then the rules the schema cannot express (every contract
      topic present, service names against FR-002, `latest` tag refused, `expose` refused on a
      service-only service, grants against `contracts/grants.md`), each refusal naming its rule
- [X] T008 [P] Implement `tools/brands/src/main/scala/io/eitheror/casino/brands/ControlPlane.scala`:
      a thin wrapper over `ankka-controlplane-api` for the calls the tool makes (project create/get,
      topic set/list, secret set, service apply/get/history/expose, grants make/list/received,
      erasure request), with a `Recording` implementation for tests that records calls and answers
      from fixtures, and a `calls` counter the validate command prints
- [X] T009 [P] Create `installation/base/` (the casino's Keycloak 24 deployment reading its admin
      secret from the installation, the `ankka-platform` ConfigMap patch for the OTLP endpoint) and
      `installation/overlays/dev-kind/kustomization.yaml` for a kind installation per the
      `ankka-platform` skill, with `installation/README.md` naming the two installations and the
      settings FR-034 to FR-039 need (set in T055)
- [X] T010 [P] Add the Gherkin binding `contract/src/test/scala/io/eitheror/casino/contract/FeaturesSuite.scala`
      (`GherkinSuite("../features")` from `ankka-testkit`, limited to `features/brands/` and
      `features/wallet/money.feature`) with step definitions pending, so every scenario is listed
      and fails as unbound
      *Implementation note:* Done as one GherkinSuite per feature file (ManifestFeatures, RecordingFeatures in the tool; AccessFeatures, FactFeatures, MoneyFeatures, PersonalFeatures in the contract): a scenario a service owns is reported ignored with the suite that runs it, never passed.
- [X] T011 Implement `tools/brands/src/main/scala/io/eitheror/casino/brands/Main.scala`: commands
      `validate <manifest> [--installation <name>] [--real-money]`, `apply <manifest> --installation
      <name>`, `status <slug>`, `recordings check`, exit codes 0/1/2, output as plain text with one
      line per finding (depends on T007, T008)

**Checkpoint**: `sbt compile` green; `sbt "brands/run validate brands/goldvibe/manifest.json"`
runs against a stub control plane; `FeaturesSuite` lists 36 pending scenarios.

---

## Phase 3: User Story 1 — A brand is created from one manifest and runs on its own (P1) 🎯 MVP

**Goal**: `apply` creates project, topics, services, exposure and realm from the manifest; a second
brand and a version change leave the first untouched.

**Independent Test**: quickstart sections 2, 3 and 4 on `dev-kind`.

### Tests for User Story 1

- [X] T012 [P] [US1] Unit tests `tools/brands/src/test/scala/io/eitheror/casino/brands/ManifestSuite.scala`
      from fixtures `tests/fixtures/manifests/{goldvibe,bad-slug,unknown-topic,unknown-service,
      latest-tag,expose-service-only}.json`: each refusal names its rule; the good one passes with
      `calls: 0`
- [X] T013 [P] [US1] Unit tests `tools/brands/src/test/scala/io/eitheror/casino/brands/RenderSuite.scala`:
      the rendered descriptor for each of the twelve services (image, runtime, `hosting: "process"`,
      `provisionObjectStorage` only for `kyc` and `reporting`, `secretKeyRef` entries, no
      `ANKKA_AUTH_*` until T040 fills them) and the ten topic declarations
- [X] T014 [P] [US1] Unit tests `tools/brands/src/test/scala/io/eitheror/casino/brands/ApplySuite.scala`
      against the recording control plane: call order (project, topics, secrets, services, expose,
      grants made, received grants checked, realm), idempotence (`changes: 0` on a second apply), diff on a changed tag touching one
      service, refusal before any call on an invalid manifest, a tombstoned slug refused with the
      platform's 409 passed through
- [X] T015 [P] [US1] Bind `features/brands/manifest.feature` scenarios 1–4 and
      `features/brands/access.feature` "a brand refuses to answer about any brand but its own" in
      `contract/src/test/scala/io/eitheror/casino/contract/steps/BrandSteps.scala` using the
      recording control plane and the `wrong_brand` refusal of T037

### Implementation for User Story 1

- [X] T016 [US1] Implement `tools/brands/src/main/scala/io/eitheror/casino/brands/Render.scala`:
      `Manifest → (ProjectSpec, Seq[TopicSpec], Seq[ServiceDescriptor], Seq[Expose], RealmSpec)`;
      topic specs carry partitions, the contract name `<topic>.v1` and the schema path under
      `contract/schemas/`, and the 043 fields when the installation reports them (else a
      `retention unset: platform lacks 043` note); reads the installation's retention bound first
      and declares the lesser of it and the contract's intent, noting `retention below intent` for
      the brand's status (spec edge case; 043 refuses an over-bound declaration rather than reducing it)
- [X] T017 [US1] Implement `tools/brands/src/main/scala/io/eitheror/casino/brands/Apply.scala`:
      ordered, idempotent apply through `ControlPlane` (project, topics, secrets, services, expose,
      grants made, received grants checked against `contracts/grants.md`, realm); reads current
      state first, computes the diff, applies only differences, prints one line per change and `changes: N`; stops at the
      first failure naming the step; never re-applies a service whose cluster generation is ahead
      of the manifest (prints `manifest behind cluster`, research R11)
- [X] T018 [P] [US1] Implement `tools/brands/src/main/scala/io/eitheror/casino/brands/Realm.scala`:
      render `casino-<slug>` from `brands/_realm/casino-template.json` (substituting `${brandId}`,
      setting `frontendBaseUrl`, keeping `eitheror-audit-listener` pointed at `players`' webhook
      route), import it through Keycloak's admin API with the installation's admin credential, and
      add the realm role `brand_<slug>` to the `eitheror` realm; idempotent
- [X] T019 [P] [US1] Copy the original's `substrate/substrate-iam/src/main/resources/keycloak-realms/casino-template.json`
      to `brands/_realm/casino-template.json` and write `brands/_realm/README.md` saying what is
      substituted and that the file is the original's (constitution I)
- [X] T020 [P] [US1] Write `brands/goldvibe/manifest.json`: slug `goldvibe`, `realMoney: false`,
      the eleven services FR-007 names for the pilot (no `partner-api`; `support-agent` present and
      off by its own configuration), the ten topics with partitions and retention per
      `contracts/topics.md`, the five made and two received grants, the secret names, the
      `dev-kind` Keycloak issuer base
- [X] T021 [P] [US1] Write `shared/payments/manifest.json`, `shared/programme/manifest.json` and
      `shared/reasoner/manifest.json` (FR-003): project id, the services FR-003 lists at their image
      tags, the topics each declares (`payments.merchants` in `payments`), the grants each makes to
      brands (the received side of `contracts/grants.md`), `staff` issuer only, no realm; plus
      `contracts/shared-manifest.schema.json` copied to `tools/brands/src/main/resources/`
- [X] T022 [US1] Extend `tools/brands/src/main/scala/io/eitheror/casino/brands/{Manifest,Render,Apply,Status}.scala`
      for a shared manifest: ids `payments`, `programme`, `reasoner` only, no brand topics, no
      player issuer, no realm, the same apply order; `validate` and `apply` take either kind, and
      `ManifestSuite`/`ApplySuite` gain the shared fixtures `tests/fixtures/manifests/shared-*.json`
- [X] T023 [US1] Implement `tools/brands/src/main/scala/io/eitheror/casino/brands/Status.scala`:
      `status <slug>` prints readiness (T053, `n/a` until then), each service's generation and
      image, each topic's phase and retention note, each grant's effect (T040), the realm's
      presence and the `brand_<slug>` role
- [X] T024 [US1] Integration suite `tools/brands/src/it/scala/io/eitheror/casino/brands/ApplyOnKindSuite.scala`
      (sbt config `IntegrationTest`, skipped unless `ANKKA_URL` is set): apply `goldvibe`, assert
      every service `Ready`, topics `provisioned`, exposure, realm; apply `testbrand`, change one
      tag, assert `goldvibe` generations unchanged; delete `testbrand`, assert re-create refused;
      record whether a dotted topic name was accepted (research R3) in the suite's output
      *Implementation note:* Written and compiled; not run: it creates projects on the installation, whose ids are never reusable. Run with `CASINO_IT=1 just it`.

**Checkpoint**: quickstart 2–4 pass on kind; SC-001's 15 minutes measured and written into
`specs/001-brands-and-projects/quickstart.md`'s section 3.

---

## Phase 4: User Story 2 — A fact crosses between services on the contract's topics (P1)

**Goal**: the contract's families exist as types, their schemas are generated, committed and
uploaded with each topic, and a rebuilt view sees every fact.

**Independent Test**: quickstart section 1, and section 3's topic schemas on kind.

### Tests for User Story 2

- [X] T025 [P] [US2] Round-trip tests `contract/src/test/scala/io/eitheror/casino/contract/facts/FactsSuite.scala`:
      every family of every topic encodes and decodes through its serializer; `ce-type` equals the
      family name; the key is the entity id `contracts/topics.md` names; `player_id` present where
      required and absent on `affiliates.attribution`; the kept field names of FR-014 present
- [X] T026 [P] [US2] Schema drift test `contract/src/test/scala/io/eitheror/casino/contract/facts/SchemaDriftSuite.scala`:
      the generated schemas equal the committed `contract/schemas/<topic>.v1.json` byte for byte,
      failing with the first differing topic
- [X] T027 [P] [US2] Bind `features/brands/facts.feature` in
      `contract/src/test/scala/io/eitheror/casino/contract/steps/FactSteps.scala` (publish, rebuild
      a view from `StartFrom.Earliest`, add a field, new version beside old, duplicate delivery
      credits once, the shared audit's pseudonym) using `ConsumerTestKit` and `AnkkaTestKit`

### Implementation for User Story 2

- [X] T028 [P] [US2] Implement `contract/src/main/scala/io/eitheror/casino/contract/facts/Fact.scala`:
      the `Fact` trait (`family`, `version`, `key`, `playerId: Option`, `id`), `FactSerializer`
      writing CloudEvents attributes (`ce-type` = family name with `.v<n>` when n > 1, `ce-subject` =
      key, `ce-id` = the fact id) and the JSON body, and `Topic` objects naming contract name,
      producer and families
- [X] T029 [P] [US2] Implement `contract/.../facts/MoneyTransactions.scala`: the eleven families
      with `real_balance_after` and `restricted_balance_after`, `deposit_confirmed` with
      `deposit_ordinal` and the kept fields `amount_cents`, `currency`, `transaction_id`
- [X] T030 [P] [US2] Implement `contract/.../facts/PaymentsPayments.scala`: the eight
      `payment.*` families, each with `payment_provider`
- [X] T031 [P] [US2] Implement `contract/.../facts/CasinoPlayers.scala`: the twenty-five families
      from `players`, `kyc` and `responsible-gambling`, with the kept fields of FR-014 and the
      profile fields `kyc` reads typed as personal (T043 supplies the type; until then a marker trait)
- [X] T032 [P] [US2] Implement `contract/.../facts/CasinoSessions.scala`, `CasinoRounds.scala`
      (`round_settled` with the provider's figures and wallet transaction ids), `CasinoWithdrawals.scala`,
      `CasinoRewards.scala` (grant families with offer id, name and type), `CasinoPush.scala`,
      `PlatformAudit.scala` (`audit_record` as spec 018 FR-001 shapes it), `PaymentsMerchants.scala`
- [X] T033 [P] [US2] Implement `contract/.../facts/AffiliatesAttribution.scala`: the fourteen kinds
      of spec 012 FR-011, keyed by pseudonym, no `player_id`
- [X] T034 [US2] Implement the `schemas` sbt task in `contract/build.sbt` (or `project/Schemas.scala`):
      generate `contract/schemas/<topic>.v1.json` as a `oneOf` over the topic's families
      discriminated by `ce-type`, from the serializers of T028–T033; commit the eleven schemas
- [X] T035 [US2] Extend `tools/brands/src/main/scala/io/eitheror/casino/brands/Render.scala` and `Apply.scala` (T016, T017) to upload each topic's contract name and
      schema with its declaration and to re-set the schema when it changed; extend `Status` to show
      the contract name per topic

**Checkpoint**: `sbt contract/test` green; `just schemas` produces no diff; quickstart 3 shows a
contract per topic.

---

## Phase 5: User Story 3 — The right person reaches the right route (P1)

**Goal**: six route classes as ACL recipes, two issuers per brand service, brand access checked on
realm roles only.

**Independent Test**: quickstart section 5.

### Tests for User Story 3

- [X] T036 [P] [US3] Unit tests `contract/src/test/scala/io/eitheror/casino/contract/access/RoutesSuite.scala`:
      each recipe's decision for a player token of this brand and of another, an operator with and
      without `brand_<slug>`, `platform_admin`, an affiliate, a partner client, `Caller.Service` in
      and out of project, `Callers.granted`, the internet; refusals carry the codes of `contracts/routes.md`
- [X] T037 [P] [US3] Integration suite `tools/brands/src/it/scala/io/eitheror/casino/brands/AccessSuite.scala`:
      deploys `tests/fixtures/sample-service/` (a one-endpoint ankka service using every recipe)
      into `goldvibe` on kind, mints tokens from both realms, and asserts the table in
      `contracts/routes.md`
      *Implementation note:* Written and compiled; not run, for the same reason, and it needs test users in the casino's realms (see the suite's header).
- [X] T038 [P] [US3] Bind `features/brands/access.feature` (the remaining eight scenarios) in
      `contract/src/test/scala/io/eitheror/casino/contract/steps/AccessSteps.scala`

### Implementation for User Story 3

- [X] T039 [US3] Implement `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`:
      `Routes.public`, `Routes.player(slug)`, `Routes.operator(slug)`, `Routes.affiliate`,
      `Routes.partner(slug)`, `Routes.service` as `Acl` values over `ankka-auth-oidc` and
      `Callers`, issuer names `player` and `staff`, refusals `wrong_brand` (naming the slug),
      `brand_not_in_roles`, `not_affiliate`, `not_granted`; `BrandSegment.check(slug)` answering
      404 `wrong_brand` for a `{brand}` segment that is not the project's (FR-005)
- [X] T040 [US3] Extend `tools/brands/src/main/scala/io/eitheror/casino/brands/Render.scala` (T016): `ANKKA_AUTH_ISSUERS=player,staff` with
      `ANKKA_AUTH_PLAYER_*` and `ANKKA_AUTH_STAFF_*` (issuer, JWKS URL, audience) from the
      manifest's `keycloak.issuerBase` on every brand service; refuse any `ANKKA_AUTH_*`,
      `ANKKA_DB_*` or `ANKKA_OTLP_*` in a manifest's `env`
- [X] T041 [P] [US3] Create `tests/fixtures/sample-service/` with `ankka init sample --language scala`,
      one endpoint per route class using T039's recipes, built to an image the kind cluster loads

**Checkpoint**: quickstart 5 passes; `features/brands/access.feature` green.

---

## Phase 6: User Story 4 — A player's personal data is marked and can be erased (P2)

**Goal**: the FR-023 fields are `Personal` types under `player/<id>`, every service registers an
erasure handler, and an erasure with a hold is applied when the hold lapses.

**Independent Test**: quickstart section 6. Needs ankka 042 (a local publish of the
`042-personal-data-erasure-impl` branch until released, research R5).

### Tests for User Story 4

- [X] T042 [P] [US4] Unit tests `contract/src/test/scala/io/eitheror/casino/contract/personal/PersonalSuite.scala`:
      a message with a `Personal` field shows the subject `player/<id>` in clear and no value;
      amounts, ids, times and country are not `Personal`; the pseudonym type is not `Personal`
- [X] T043 [P] [US4] Bind `features/brands/personal-data.feature` in
      `contract/src/test/scala/io/eitheror/casino/contract/steps/PersonalSteps.scala`, including the
      rewritten shared-payments scenario and the retention-hold scenario
- [ ] T044 [US4] Integration suite `tools/brands/src/it/scala/io/eitheror/casino/brands/ErasureSuite.scala`:
      on kind with 042, request an erasure for a player of the sample service with a hold, assert
      `held`, lapse it, assert `Erased` on the topic and in the store within the bound, amounts unchanged
      *Implementation note:* Blocked: ankka 042 is specified and has no code on any branch, so there is nothing to erase with.

### Implementation for User Story 4

- [X] T045 [US4] Implement `contract/src/main/scala/io/eitheror/casino/contract/personal/Personal.scala`:
      `PlayerSubject(playerId)` rendering `player/<id>` under 042's subject rules, type aliases for
      the FR-023 fields over `Personal[A]` (names, email, telephone, postal address, date of birth,
      nationality, document reference, network address, device identifier, free text), and
      `NotPersonal` evidence for amounts, currencies, ids, times, statuses, game identifiers,
      country, pseudonym (FR-024)
- [ ] T046 [US4] Implement `contract/.../personal/ErasureHandler.scala`: the registration helper a
      service calls at start to register 042's erasure callback under its subject prefix, plus
      `ErasureRequest.file(correlationId, hold)` for `players` to file in the brand and, under the
      `erasure` grant, in `payments` (FR-025)
      *Implementation note:* Blocked on ankka 042's erasure-handler API.
- [ ] T047 [US4] Replace the marker trait of T031 with T045's types in `CasinoPlayers.scala`,
      `PaymentsPayments.scala` (none expected) and `PlatformAudit.scala` (no personal value, FR-018),
      and regenerate the schemas (T034)
      *Implementation note:* Blocked on ankka 042's `Personal[A]`; contract.personal.Personal is the marker until then.

**Checkpoint**: quickstart 6 passes on kind with 042; SC-004 bound observed.

---

## Phase 7: User Story 5 — Money is exact and a repeated command does nothing twice (P2)

**Goal**: `Money` and `Key` are the only way an amount or a money command enters a casino service.

**Independent Test**: quickstart section 1's money assertions.

### Tests for User Story 5

- [X] T048 [P] [US5] Unit tests `contract/src/test/scala/io/eitheror/casino/contract/money/MoneySuite.scala`:
      `1050` accepted, `"10.50"`, `10.5` and `1050.0` refused naming the amount, negative allowed
      where typed `SignedMoney`, `Long.MaxValue` round-trips, a win above 2^31 minor units encodes
      without loss (research R10)
- [X] T049 [P] [US5] Bind `features/wallet/money.feature` in
      `contract/src/test/scala/io/eitheror/casino/contract/steps/MoneySteps.scala` with a
      `KeyedCommandTestKit` that records keys and answers a repeat with the first outcome

### Implementation for User Story 5

- [X] T050 [US5] Extend `Money.scala` (T006) with `SignedMoney` (GGR, adjustments), the
      `MoneyCommand` trait (`key: Key`) and `KeyedOutcomes`, a small helper an entity embeds to
      record seen keys and answer a repeat with the first outcome (FR-027), with its own tests in T048

**Checkpoint**: `features/wallet/money.feature` green; the helper is what spec 002 will use.

---

## Phase 8: User Story 6 — The original is recorded and the rebuild held to it (P2)

**Goal**: the original runs locally with every provider stubbed, its Go suite emits one recording
per scenario into `tests/port/`, and a service can replay a recording as a parity test.

**Independent Test**: quickstart section 7.

### Tests for User Story 6

- [X] T051 [P] [US6] Go tests for each stub in `tests/stubs/<psp>/main_test.go`: `/healthz`, one
      deposit initiation answering the adapter's expected shape, one webhook the stub posts on a
      `/__control` call, signature as the adapter verifies it
- [X] T052 [P] [US6] Unit tests `contract/src/test/scala/io/eitheror/casino/contract/port/RecordingSuite.scala`:
      a recording decodes; `vary` paths compared for presence only; captures substitute into later
      steps; a differing field fails naming the recording path and the JSON path; a second run on
      fresh ids is required (`runs: 2`)
- [X] T053 [P] [US6] Bind `features/brands/recordings.feature` in
      `contract/src/test/scala/io/eitheror/casino/contract/steps/RecordingSteps.scala` using a
      fixture recording set under `tests/fixtures/port/` and a stub that is absent on purpose; the
      scenario "the original's frontend suite passes against a rebuilt brand" is bound as pending
      with the reason "needs a rebuilt brand: spec 019's rehearsal" (FR-033)

### Implementation for User Story 6

- [X] T054 [P] [US6] Write the seven Go stubs `tests/stubs/{bouncemoney,cozeni,dreamzpay,eupaymentz,gigadat,morphosis,t365}/main.go`,
      each answering what the matching adapter in the original's `services/psp-gateway/internal/<psp>/`
      sends (read the adapter and its `signature_test.go`), with `/healthz`, `/__control` to force
      an outcome, and a recorded log of what was received; one shared `tests/stubs/internal/stubhttp`
      package for the common parts
- [X] T055 [P] [US6] Write `tests/original/compose.override.yml` adding the seven stubs beside
      `fake-st8` and `uf-stub` to the original's `dev/compose/full-stack.yml` and pointing each
      adapter's base URL at its stub, with `tests/original/README.md` giving the exact `just` and
      `docker compose` invocation in the original's checkout
- [X] T056 [US6] Write `tests/original/e2e-recording.patch`: an `http.RoundTripper` for the
      original's `tests/e2e/internal/setup` client that, when `-record-to <dir>` is set, writes one
      recording per test function (subtests as steps, captures from the test's own ids, outbound
      calls read back from the stubs' logs) in `contracts/recording.md`'s format, runs each test
      twice and fills `vary`, refuses to start while any stub fails `/healthz`, and writes
      `suite-result.json`; the patch applies cleanly to the original's `main`
- [X] T057 [P] [US6] Write `tests/original/e2e-rg-kyc.patch`: the responsible-gambling and KYC
      scenarios the original's Go suite lacks, written from `features/responsible-gambling/` and
      `features/kyc/` as `tests/e2e` test functions against the original's routes, so they run and
      record against the original before specs 009 and 010 are recorded (FR-030); scenarios the
      original cannot pass are marked skipped with the defect named
- [X] T058 [US6] Implement `contract/src/main/scala/io/eitheror/casino/contract/port/Recording.scala`
      (the model) and `contract/src/main/scala/io/eitheror/casino/contract/port/Replay.scala`
      (a munit helper: `replay(recording)(client)` substituting captures, comparing field by field,
      failing with path and JSON path), published with the contract so every service's parity tests
      use one replayer
- [X] T059 [US6] Implement `recordings check` in `Main.scala`/`Recordings.scala`: every file under
      `tests/port/` decodes, has two runs, names a requirement or is listed as unclaimed, and every
      requirement a service spec names under *Established by* exists (SC-006)
- [ ] T060 [US6] Run the recording once against the local original with every stub up and commit
      `tests/port/<service>/` with `suite-result.json`; record in `tests/port/README.md` the
      scenarios that skipped and why (the environment defects of research R10), so each service spec
      can name what it has
      *Implementation note:* Not run: it needs the original's full stack up locally; tests/original/README.md is the runbook.

**Checkpoint**: quickstart 7 passes; `recordings check` green; SC-006 holds for the recordings that exist.

---

## Phase 9: User Story 7 — The installation is fit for real money before a brand takes it (P3)

**Goal**: a real-money manifest is refused until FR-035 to FR-038 hold, and the `gke` overlay sets
what they need.

**Independent Test**: quickstart section 8.

### Tests for User Story 7

- [X] T061 [P] [US7] Unit tests `tools/brands/src/test/scala/io/eitheror/casino/brands/ReadinessSuite.scala`
      from fixtures `tests/fixtures/installations/{fit,no-offcluster-copy,erasure-off,one-keyring,
      retention-default,two-brokers,unknown-backup}/`: each refusal names the condition and its
      source; `unknown` refuses; `fit` passes; no control-plane write happens before the verdict
- [X] T062 [P] [US7] Bind `features/brands/manifest.feature` scenarios 5–7 (the outline and the two
      creations) in `BrandSteps.scala` (T015)

### Implementation for User Story 7

- [X] T063 [US7] Implement `tools/brands/src/main/scala/io/eitheror/casino/brands/Readiness.scala`:
      the four conditions of data-model.md read from the 041 project status, the installation
      overlay's settings (`installation/overlays/<name>/`), a 043 probe declaration and the broker
      replica count; verdict `met`/`failed`/`unknown` per condition; wired into `validate --real-money`
      and into `apply` before any call when `realMoney` is true; the conditions head `status`
- [X] T064 [P] [US7] Create `installation/overlays/gke/`: `ANKKA_CLOUD_PROVIDER=gcp`,
      `ANKKA_CLOUD_ACCOUNT`, `ANKKA_CLOUD_LOCATION`, `ANKKA_SECRET_BACKEND=secret-manager`, the 043
      bounds (longest retention unbounded, three copies default), three broker replicas with two in
      sync, the 041 off-cluster copy requirement, erasure enabled with the 042 keyring at two
      replicas (FR-036), the OTLP endpoint of the existing Grafana stack,
      and a Cloud Armor policy on the gateway; values that are secrets referenced, never written
- [X] T065 [P] [US7] Document in `brands/README.md` how a brand is promoted: one manifest per
      brand, and its `realMoney` flips to true in a release pull request only once
      `validate --installation gke --real-money` passes; no second manifest file

**Checkpoint**: quickstart 8 passes against the fixtures; SC-007 holds.

---

## Phase 10: Polish

- [X] T066 [P] Write `tools/brands/README.md`: the three commands, the apply order, what each
      "platform lacks" note means and which ankka spec removes it, how `status` is read
- [X] T067 [P] Write `brands/README.md`: what a manifest holds, how to add a brand (copy, slug,
      images, secrets file outside the repository, apply), how a release reaches one brand first
- [X] T068 [P] Update `CLAUDE.md` "Working here": the root build, `just` recipes, `services/<name>/`
      convention, the contract artifact every service depends on, and that `ankka@ankka` must be installed
- [ ] T069 Run quickstart.md end to end on `dev-kind` (sections 6 and the grant lines of 3 as far as
      the published ankka allows), fix what fails, and write the measured times into quickstart.md
      *Implementation note:* Not run on kind, for T024's reason; every section's unit and bound-feature part passes in `sbt test`.
- [X] T070 [P] Add the integration suites to `.github/workflows/ci.yml` behind a kind job, skipped
      on pull requests from forks
- [X] T071 Add to `specs/002-wallet/spec.md` under *Changes from the original* the idempotency-key
      divergence research R10 found (money commands carry a caller's key; the original minted a
      random UUID per dispatch), as a one-line clarification dated today

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)**: none.
- **Foundational (2)**: after Setup. Blocks every story.
- **US1 (3)**: after Foundational. The MVP.
- **US2 (4)**: after Foundational; T035 extends US1's Render/Apply, so finish T016/T017 first.
- **US3 (5)**: after Foundational; T040 extends Render (T016).
- **US4 (6)**: after US2 (T031's marker trait is replaced by T047); needs the 042 branch.
- **US5 (7)**: after Foundational only.
- **US6 (8)**: after Foundational only; T060 needs the original's checkout and Docker.
- **US7 (9)**: after US1 (Readiness wires into `validate` and `apply`).
- **Polish (10)**: after the stories wanted.

### Platform gates (research R5, R7, R8)

- Grants (T023's effects, the grant lines of T017) and personal data (US4) compile against the
  `040-` and `042-…-impl` branches until released; retention fields (T016) against `043-…-impl`.
  Everything else builds on released ankka. Order the work so the released-ankka tasks land first.

### Parallel Opportunities

- Phase 1: T002–T005 together after T001.
- Phase 2: T006–T010 together; T011 after T007 and T008.
- US1: T012–T015 and T018–T020 together; T016 → T017 → T023 → T024.
- US2: T025–T027 and T028–T033 together; T034 after T028–T033; T035 last.
- US3: T036–T038 and T041 together; T039 → T040.
- US6: T051–T055 together; T056 and T058 together after T055; T059 → T060.
- US7: T061, T062, T064, T065 together; T063 after T061.
- Stories US2, US3, US5, US6 can run in parallel once Phase 2 is done.

---

## Parallel Example: User Story 1

```bash
# tests and files first, together:
Task: "ManifestSuite in tools/brands/src/test/scala/io/eitheror/casino/brands/ManifestSuite.scala"
Task: "RenderSuite in tools/brands/src/test/scala/io/eitheror/casino/brands/RenderSuite.scala"
Task: "ApplySuite in tools/brands/src/test/scala/io/eitheror/casino/brands/ApplySuite.scala"
Task: "Realm.scala, the realm template copy, brands/goldvibe/manifest.json"
# then in order:
Task: "Render.scala" → "Apply.scala" → "Status.scala" → "ApplyOnKindSuite"
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phases 1 and 2.
2. Phase 3: a brand created from its manifest on kind, a second brand untouched by a change to the first.
3. Stop and run quickstart 2–4. That alone lets spec 002 (wallet) deploy somewhere real.

### Incremental delivery

1. US2 and US5 next: the contract artifact spec 002 compiles against.
2. US3: every service's ACLs come from one place before the first service is written.
3. US6: recordings before the first port (constitution I); start as soon as Docker and the original's checkout are to hand, independently of the above.
4. US4 and the grant parts when the ankka branches are published locally.
5. US7 before the pilot's manifest is marked for real money, not before.

---

## Notes

- Every suite named here is also the parity evidence the next specs cite; keep names stable.
- No task here creates a service under `services/`; the first is spec 002.
- The first apply on kind answers research R3 (dotted topic names); if refused, one clarification
  on this spec renames the topics to hyphens before US2's families are written.
