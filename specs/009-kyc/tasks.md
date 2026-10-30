# Tasks: kyc

**Input**: [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md); the spec's seven stories and 68 scenarios.

**Tests**: required. The constitution makes the recordings the acceptance test (I), SC-001 names the
original's 61 rollup and handler cases, and the plan names the suites. Parity tests and feature bindings
are written before the components they test and fail first (ankka-port §7).

Paths: `S` = `services/kyc/src/main/scala/io/eitheror/casino/kyc/`,
`T` = `services/kyc/src/test/scala/io/eitheror/casino/kyc/`.

## Phase 1: Setup

- [X] T001 Add `reason: Option[String] = None` to `PlayerKycPending` and `large_deposit` to the documented
      reasons of `PlayerKycReviewRequired` in
      `contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala` (contracts/facts.md,
      R13); `just schemas`; the schema diff shows one optional field only; `sbt contract/test` green
- [X] T002 Remove `kyc-provider-key` (R16): `KYC_PROVIDER_KEY` from the `kyc` entry of
      `modules/core/module.json`, its row in `contract/keys.md`, `goldvibe-kyc/API_KEY` from
      `brands/goldvibe/values.json`, and its uses in `tools/modules/src/test/` (fixtures,
      `LocalApplySuite`'s "core held: missing kyc-provider-key"). In the same entry add `"exposeObjectStorage": true`
      (R7: the signed links need `ANKKA_S3_PUBLIC_ENDPOINT`) and `KYC_MAX_UPLOAD_BYTES` from an optional key
      `kyc-max-upload-bytes` (a row in `contract/keys.md`, not required; the service's default when unset);
      `just test` green
- [X] T003 `ankka init` the service at `services/kyc/` in the shape of `services/responsible-gambling/`:
      `build.sbt` on `casino-contract`, ankka 0.13.0 and `software.amazon.awssdk:s3`; `service.json` with
      `provisionObjectStorage` and `exposeObjectStorage`; `docker-compose.yml` (Postgres on 5438 and an
      S3-compatible store); `src/main/resources/application.conf`. Move the `kyc` row of `contract/keys.md`
      out of "Not yet built" (`ModuleManifestSuite` enforces it once `services/kyc/build.sbt` exists)
- [X] T004 [P] `S/Settings.scala` (brand, project, issuers from the env the core module renders;
      `KYC_MAX_UPLOAD_BYTES`, default 5,242,880; the `ANKKA_S3_*` variables) and `S/KycCodecs.scala` (the
      stored-type serializer helper as `RgCodecs`)
- [X] T005 [P] `justfile`: `kyc-test`, `kyc-it`, `kyc-run` (port 9012, R18); `.github/workflows/ci.yml`:
      the service's filter, output and job

## Phase 2: Foundational

- [X] T006 [P] `S/record/Rollup.scala` (pure port of `rollup.go` and `handler.go`, R4; the order of FR-012;
      FR-013's legacy identity) and `T/record/RollupSuite.scala`: the 61 cases of `rollup_test.go` and
      `handler_test.go` as rows with the same inputs and outcomes (SC-001), written first and failing
      first; `StaleTimestampIsNoOp` restated as "a command that changes no input derives nothing"
- [X] T007 [P] `S/record/Completeness.scala` (pure: the eight fields, the age date checked against today,
      the missing fields named, R11) with `T/record/CompletenessSuite.scala`: a player who turns eighteen
      completes with no new fact
- [X] T008 [P] `S/policy/Policy.scala` and `S/policy/Profiles.scala` (pure: research R9's table, the
      validation of `Change`, `currency_required`) and `T/policy/PolicySuite.scala`: every profile row,
      every refusal, the seven `substrate_test.go` cases on the required categories' fallback
- [X] T009 [P] `S/record/Gates.scala` (pure: data-model.md's deposit check order and the play check; the
      messages of research R5 verbatim; a missing currency treated as another currency) and
      `T/record/GatesSuite.scala`: every row, exactly at the threshold allowed, a minor unit over refused,
      `DEPOSIT_BLOCKED_KYC` before the currency rule
- [X] T010 [P] `S/documents/ObjectNames.scala` (`subjects/<player>/kyc/<category>-<millis>.<ext>`, the three
      content types of R18) with its suite: nothing but the player id and category in a name
- [X] T011 `S/record/Model.scala`: state, `Document`, `Offer`, `ProfileFacts`, `Flag`, `Category`,
      `ReasonCode`, events, commands and refusals (data-model.md), `Personal` reasons, notes and age date
- [X] T012 `S/policy/PolicyEntity.scala` with its `EventSourcedTestKit` suite: the Curaçao default with
      `isDefault` when no event; `ApplyProfile`, `Change` (profile `CUSTOM`), every refusal;
      `PolicyChanged` carries before and after
- [X] T013 `S/record/KycRecord.scala` and `T/record/KycRecordSuite.scala` (`EventSourcedTestKit`): every
      command, event and refusal of data-model.md's table in its order; the derivation after each input
      change and only a changed status written (FR-014); offers dropped an hour after they are offered;
      `Erase` blanks every `Personal` value and keeps ids, categories, times, statuses and reason codes
- [X] T014 [P] `S/documents/Store.scala` (trait: sign PUT with content type, sign GET, HEAD, DELETE, list
      and delete every version under a prefix) with `S3Store` configured as `platform/object-storage.md`
      (path style, `ANKKA_S3_REGION`, checksums when required, `GenerationAsVersionId`; sign against
      `ANKKA_S3_PUBLIC_ENDPOINT`, read and write through `ANKKA_S3_ENDPOINT`) and `T/documents/FakeStore.scala`
- [X] T015 `S/facts/KycPublisher.scala` and `S/facts/AuditPublisher.scala` (contracts/facts.md) with
      `T/facts/PublishersSuite.scala`: every event to its family or none; `player_kyc_pending` with its
      reason; `verified → unverified` as `player_kyc_rejected` with `profile_changed`; a free-text reason
      never on a fact; one audit record per status change, review, view, source of funds change, flag
      dismissal, operator upload and policy change, naming that a note was given and never its text
- [X] T016 `S/KycService.scala` (registration), `S/api/Answers.scala` (refusal bodies
      `{error, message}`), and the integration kit `T/it/Kit.scala`: Postgres, the S3-compatible container,
      `FakePlayers` under `localServices`, `InMemoryBroker` for `casino.players`, `money.transactions` and
      `platform.audit`, `TimerRuntime(200.millis)`, a settable clock, player and operator tokens (operator
      with a role), a service identity per caller
- [X] T017 `T/port/WireTranslation.scala` (every row of contracts/wire.md, `<d>` captured beside `<k>`),
      `T/port/WireTranslationSuite.scala`, and `T/port/ParitySuite.scala` over `tests/port/kyc/`, `.ignore`d
      until T048

**Checkpoint**: the pure packages, both entities and the plumbing pass their kits.

## Phase 3: US1, a player submits documents and sees where they stand (P1)

- [X] T018 [US1] Fail first: bind `features/kyc/documents.feature` in `T/features/DocumentsFeatures.scala`
      (11 scenarios; "in the player's language" asserted as the code the page renders, frontends out of
      scope)
- [X] T019 [US1] `S/api/PlayerEndpoint.scala` at `/v1/casino/kyc/<slug>`: `GET /{playerId}/documents`,
      `POST /{playerId}/uploads`, `POST /{playerId}/documents`, `GET /{playerId}/views/{documentId}`
      (contracts/routes.md); `player_id_mismatch`; the signed PUT for an hour with the content type signed;
      the confirm's HEAD, an oversized or mistyped object deleted and refused `file_rejected` naming the
      rule; `storage_unavailable` on a store failure
- [X] T020 [US1] `S/api/OperatorEndpoint.scala` at `/v1/casino/backoffice/<slug>/kyc` with the compliance
      role check (FR-002, R14: `brand_admin`, `brand_compliance`, `compliance_officer`, `resource_admin`,
      `platform_admin`; any other `forbidden`): `POST /{playerId}/uploads` (with `reason`,
      `reason_required`), `POST /{playerId}/documents`, `GET /{playerId}/views/{documentId}`
- [X] T021 [US1] `T/it/DocumentsSuite.scala` against the store container: an upload through the signed
      PUT, confirmed; a category locked; `other` never locked; a replacement for a rejected document; a
      view link for fifteen minutes, its `ViewIssued` and its audit record (SC-005); another player's
      document refused; an id never offered `document_not_found`; an expired offer `upload_expired`

## Phase 4: US2, an operator reviews and the status follows the rules (P1)

- [X] T022 [US2] Fail first: bind `features/kyc/review.feature` in `T/features/ReviewFeatures.scala`
      (21 scenarios)
- [X] T023 [US2] `OperatorEndpoint`: `GET /{playerId}` (the operator's read), `POST
      /{playerId}/reviews/{documentId}` (the reason code checked first, `document_already_reviewed`, 404
      on an unknown document), `PUT /{playerId}/mode` (automatic recomputes), `PUT /{playerId}/status`
      (`profile_incomplete` with the missing fields), `PUT /{playerId}/required`, `POST /{playerId}/override`
- [X] T024 [US2] `S/consumers/ProfileConsumer.scala` (`player_profile_updated` → `ProfileChanged`:
      presence, age date, whether a required field changed) with its `ConsumerTestKit` suite; no field's
      value kept
- [X] T025 [US2] `T/it/OperatorSuite.scala`: each role admitted or refused; another brand's operator
      refused; a review, override, mode and required change each in `platform.audit` with the operator;
      the ready time set and cleared in manual mode

## Phase 5: US3, the brand's policy comes from its licence (P1)

- [X] T026 [US3] Bind `features/kyc/policy.feature` in `T/features/PolicyFeatures.scala` (7 scenarios)
- [X] T027 [US3] `OperatorEndpoint`: `GET /policy` (every field, `null` for none, `isDefault`), `PUT
      /policy` (`invalid_policy` naming the field, `currency_required`), `POST /policy/profile`
      (`profile_required`, `unknown_profile` with `knownProfiles`)
- [X] T028 [US3] `S/api/PublicEndpoint.scala` at `/v1/casino/kyc-requirements/<slug>`: `GET /` answers
      `{requiredDocTypes}` and nothing else (FR-029)

## Phase 6: US4, a deposit or a launch is allowed or refused (P1)

- [X] T029 [US4] Fail first: bind `features/kyc/gates.feature` in `T/features/GatesFeatures.scala`
      (14 scenarios)
- [X] T030 [US4] `S/api/ServiceEndpoint.scala` at `/v1/casino/brands/<slug>/kyc`: `POST /deposit-check`
      for `players` (the policy read from `PolicyEntity`, then `DepositCheck` on the record; answered 200,
      `{allowed}` or `{allowed:false, code, message, kycStatus?}`) and `GET /{playerId}/play-check` for
      `game-provider` (a read; `error` and `code` both `PLAY_BLOCKED_KYC`); a player with no record
      answered as unverified with no documents
- [X] T031 [US4] `S/consumers/DepositsConsumer.scala` (`deposit_confirmed` → `CountDeposit` with the
      policy; per currency, deduplicated by `depositOrdinal`; FR-038's large-deposit flag) with its
      `ConsumerTestKit` suite: a repeat changes nothing; a deposit for a player with no record creates it
- [X] T032 [US4] `S/queue/KycQueue.scala` (the view of data-model.md, no `Personal` column; the
      `overThreshold` query) and `S/consumers/PolicyFlagger.scala` (`PolicyChanged` lowering a threshold →
      `FlagForPolicy` for each row) with their suites: each player flagged once (FR-022)
- [X] T033 [US4] `T/it/CallersSuite.scala`: each gate answer decoded with `players`' `Kyc.read` and
      `game-provider`'s reading of the play check (decoders copied from
      `services/players/.../clients/Neighbours.scala` and `services/game-provider/.../clients/`), so a shape
      drift fails here; a refusal is never read as an outage
- [X] T034 [US4] `T/it/GatesLatencySuite.scala`: 1,000 asks of each gate, p99 under 50 ms (SC-003), no
      outbound call (`FakePlayers` and the store receive nothing); a policy change seen by the next ask
      (SC-006)

## Phase 7: US5, the verification window runs out (P2)

- [X] T035 [US5] Fail first: bind `features/kyc/window.feature` in `T/features/WindowFeatures.scala`
      (7 scenarios)
- [X] T036 [US5] `S/consumers/RegistrationConsumer.scala` (`player_registered` → `SetDeadline` with the
      policy) and `S/consumers/ClosureConsumer.scala` (`player_closed` → `Close`) with their kits
- [X] T037 [US5] `S/clients/Players.scala` (trait + HTTP: `POST
      /v1/casino/brands/<slug>/players/{playerId}/suspend {reason: "kyc_window_expired", deadline}`),
      `S/timers/RecordTimers.scala` (sets `kyc-window-<player>` on `DeadlineSet`, replaced by name; cancels
      on `Closed`) and `S/timers/WindowTimer.scala` (`WindowLapsed`, the suspend when told to, then
      `SuspensionRequested`; a failure throws)
- [X] T038 [US5] `T/it/WindowSuite.scala`: a deadline lapses within a minute and flags; under a policy that
      suspends `FakePlayers` is called once, retried while it fails, not again after it answered; a
      verified or closed player changes nothing; a migrated past deadline flags and never suspends

## Phase 8: US6, documents kept and then erased (P2)

- [X] T039 [US6] Bind `features/kyc/retention.feature` in `T/features/RetentionFeatures.scala`
      (4 scenarios; "encrypted at rest" asserted as the installation's store, R17, and named for kind)
- [X] T040 [US6] `ServiceEndpoint`: `POST /erasure/players/{playerId}` (every version under
      `subjects/<player>/` deleted, then `Erase`; a certificate; runs again on every call) and `GET
      /exports/players/{playerId}` (the records, no object) for `players`; an erased player's view refused
      `document_erased`
- [X] T041 [US6] `T/it/ErasureSuite.scala`: after erasure no object under the prefix in any version the
      store keeps (SC-004); the records' non-personal fields unchanged; a late upload removed by the next
      call; a store failure answers no certificate; no other path deletes a recorded document's object
      (FR-034: only `file_rejected` on an unconfirmed upload, and erasure)
- [X] T042 [US6] `T/it/LeakSuite.scala`: after `Erase`, no reason, note or age date in any state, fact,
      audit record, view row or log line; no `Personal` value in any log line or span at any time

## Phase 9: US7, reads (P2)

- [X] T043 [US7] Bind `features/kyc/reads.feature` in `T/features/ReadsFeatures.scala` (4 scenarios;
      "the player's activity history" is `players`' `ActivityView`, asserted on the facts here and named as
      008's)
- [X] T044 [US7] `OperatorEndpoint`: `GET /queue?filter=&reason=&sort=` (`invalid_filter`; every count of
      contracts/routes.md; oldest first unless `sort=newest`), `POST /{playerId}/sof` and `POST
      /{playerId}/flags/{reason}/dismiss` (`flag_not_found`, `note_required`)
- [ ] T045 [US7] `T/it/QueueSuite.scala`: each filter lists its players with the right count; a flag leaves
      the queue on verification or dismissal, and a source of funds flag on clearance (FR-024)

## Phase 10: The recordings and parity

- [ ] T046 Correct `tests/original/e2e-rg-kyc.patch`: scenario 28's `IncompleteProfileStaysUnverified`
      waits for `pending` (R1; FR-012); update `tests/original/e2e-rg-kyc.md` to match
- [ ] T047 The recording run (needs the user; research R2, quickstart §3): apply `e2e-recording.patch` and
      `e2e-rg-kyc.patch` to a local checkout of the original, run scenarios 28 and 29 against the local
      stack, copy the recordings to `tests/port/kyc/`; `tests/original/README.md` names the run
- [ ] T048 Un-ignore `ParitySuite`; every recording replays through `WireTranslation`; each marked divergence
      in `Parity.divergences` matches plan.md's *Divergences*; `CoverageSuite` names any unhit

## Phase 11: Polish and cross-cutting

- [ ] T049 [P] `services/kyc/README.md` (purpose, routes by endpoint with a pointer to contracts/routes.md,
      settings, the bucket, encryption by store (R17), the `just` recipes, what is verified where)
- [ ] T050 [P] On 008's branch (`008-players`): the deposit check sends `currency` (R5); the suspend
      honours the deadline, idempotent under player and deadline (R10); `ErasureWorkflow.targets` and the
      export gain `kyc` (R15); `FakeKyc` answers the shapes of contracts/routes.md; its suites green
- [ ] T051 Quickstart §6: the service run locally on 9012, the two gates answered by `curl`, an upload
      offered, put and confirmed with a real player token from kind
- [ ] T052 Quickstart §7 on `kind-ankka` (never production; `--context kind-…`): the image built and
      loaded, `just modules apply goldvibe --installation dev-kind --modules core --allow-unbuilt`; the bucket
      in `ankka services get kyc`; under UKGC a deposit refused through `players` and a launch through
      `game-provider`
- [ ] T053 Close research: *Findings* with dates; the cross-spec follow-ups (008, 007, 014, 015, 019, the
      vendor's `objectStorageOrigins`) written; `just features` green; `just kyc-test` and `just kyc-it`
      green
- [ ] T054 SC-002: the Playwright KYC suites (28–33, 50) against the kind brand, once the frontends'
      base-URL move is made (008's T077 waits on the same move); until then named in research *Findings*

## Dependencies & Execution Order

- Setup (T001–T005) → Foundational (T006–T017) → the stories.
- US1 → US2 (a review needs a document); US3 independent of US1 and US2; US4 after US3 (the gates read the
  policy); US5 after US3; US6 after US1; US7 after US2 and US4 (the queue counts reviews and flags).
- T047 needs the user and the original's kind stack; T046 before it; T048 after it. Nothing else waits on
  it.
- T050 is on another branch and independent; T052 after T049–T051; T054 after T052 and the frontends' move.

## Parallel examples

- Foundational: T006, T007, T008, T009, T010 together (different files, pure); T014 beside T011–T013.
- After the foundation: US1 (T018–T021) and US3 (T026–T028) together.
- After US3: US4's bindings and US5's together.

## Implementation strategy

MVP: Phases 1–6 (US1–US4): the documents, the review and its rules, the policy and both gates. With it,
`players`' deposits and `game-provider`'s launches get real answers and a UKGC brand can refuse an
unverified player. US5–US7 follow; the recordings (T047) when the user can run the original.
