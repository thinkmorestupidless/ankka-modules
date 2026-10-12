# Tasks: Game provider simulator

**Input**: Design documents from `specs/020-game-provider-simulator/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The constitution makes the living features the acceptance tests (VII) and the
port method makes parity tests a deliverable (I); the `GherkinSuite` bindings, the byte-fidelity
suite and the parity suite named in quickstart.md are tasks here, written before the code they hold
to account.

**Organization**: By user story. US1 is the MVP: a brand plays a round against the simulator
exactly as against `fake-st8`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US5 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `services/game-provider-simulator/` — the service, one sbt build; `S` below is
  `services/game-provider-simulator/src/main/scala/io/eitheror/casino/simulator` and `T` is
  `services/game-provider-simulator/src/test/scala/io/eitheror/casino/simulator`; resources under
  `services/game-provider-simulator/src/main/resources`
- `contract/` — the shared library, touched once (`Layout`)
- `shared/tools/` — the project's manifest; `tests/port/simulator/` — recordings
- The wire is `contracts/st8-wire.md`; a task that says "as the wire contract says" means byte for
  byte against that file

---

## Phase 1: Setup

**Purpose**: make `tools` a project the repository knows, scaffold the service, publish the contract.

- [X] T001 Add `"tools" -> Vector("game-provider-simulator")` to `SharedProjects` in
      `contract/src/main/scala/io/eitheror/casino/contract/Layout.scala` (so `ReservedSlugs` gains
      `tools`), extend the `RoutesSuite`/`Layout` tests if any enumerate shared projects, and run
      `sbt contract/test`
- [X] T002 [P] Add `tools` to `project.id` and `game-provider-simulator` to `services[].name` in
      `specs/001-brands-and-projects/contracts/shared-manifest.schema.json`, and wherever
      `tools/brands` loads that schema from (check `tools/brands/src/main/resources/` and the
      tool's `Manifest.scala` validation tests); run `sbt brands/test`
- [X] T003 [P] Amend spec 001 FR-003 in `specs/001-brands-and-projects/spec.md` to four shared
      projects, adding `tools` (`game-provider-simulator`, spec 020) in one sentence; add a line
      under its *Clarifications* dated 2026-10-09 naming research R1 of spec 020
- [X] T004 [P] Create `shared/tools/manifest.json` as `contracts/brand-config.md` shows (project
      `tools`, one service, `expose: true`, the `SIMULATOR_*` env, no topics, no grants) and check it
      with `just brands validate shared/tools/manifest.json --installation dev-kind`
- [X] T005 Scaffold the service with `ankka init game-provider-simulator --package
      io.eitheror.casino.simulator --dir services` and then: add `io.eitheror %% casino-contract`
      and `ankka-auth-oidc` to `services/game-provider-simulator/build.sbt` (as
      `tests/fixtures/sample-service/build.sbt` does), set `name` in `service.json` and
      `ankka.service.name` in `application.conf` to `game-provider-simulator`, remove the template's
      `Item*` sources and suites, and `sbt contract/publishLocal && cd services/game-provider-simulator
      && sbt compile`
- [X] T006 [P] Add `services/game-provider-simulator/README.md` (what it is, how to run it beside a
      brand in local mode per `contracts/brand-config.md` including the `ANKKA_AUTH_STAFF_*` settings
      for the casino's local Keycloak, where the scenarios live) and a `just`
      recipe `simulator *args` in `Justfile` running `sbt` in the service directory
- [X] T007 [P] Extend `.github/workflows/ci.yml` to run `sbt test` in `services/game-provider-simulator`
      after `sbt contract/publishLocal`

---

## Phase 2: Foundational

**Purpose**: what every story needs: configuration, keys and signing, the sender, the two body
shapes, the session and callback entities, the scenario library, the test kit and the feature
bindings' skeleton.

**⚠️ CRITICAL**: no story starts before this phase is done.

- [X] T008 [P] Implement `S/brands/BrandTable.scala`: parse `SIMULATOR_BRANDS` (fields and
      defaults of `contracts/brand-config.md`), `SIMULATOR_EXTERNAL_URL`, `SIMULATOR_RETENTION_DAYS`
      and `SIMULATOR_GAMES_EXTRA` from `application.conf` keys with `${?SIMULATOR_*}` substitutions;
      refuse to start on a malformed entry naming the field; unit suite `T/brands/BrandTableSuite.scala`
- [X] T009 [P] Implement `S/core/Seeded.scala`: a `SplittableRandom` per run from a seed, with
      `amount(min, max)` to two decimal places, `duration(min, max)` and `pick(n)`, each taking the
      step index so a draw is reproducible from `(seed, step)`; unit suite proving two generators with
      one seed agree (`T/core/SeededSuite.scala`)
- [X] T010 [P] Implement `S/st8/Signer.scala`: `sign(body: Array[Byte], privateKey)` = base64 of the
      ASN.1 ECDSA P-256 signature over SHA-256 of the bytes, `verify` for tests, PKIX/SPKI and PKCS#8
      base64 encoding and decoding; unit suite `T/st8/SignerSuite.scala` round-tripping a signature
      and verifying one against a key encoded as `fake-st8` encodes it
- [X] T011 Implement `S/st8/Keys.scala`: the secret-store names `keys/<kid>`, `keys/current`,
      `keys/next` (data-model *KeyPair*), `ensureCurrent()` generating a P-256 pair when
      `keys/current` is absent, `document()` building the key document of the wire contract,
      `publishNext()`, `promote()`, `withdrawOld()`; read only from an endpoint, workflow step or
      `Main`, never an entity (research R10); tested in T023 through the test kit
- [X] T012 Implement `S/st8/KeyDocument.scala`: a public endpoint at prefix `/.well-known` serving
      `GET /st8-keys` from `Keys.document()` as the wire contract shows (`current`, `next` during a
      rotation, `cacheSeconds: 60`), and `S/st8/Health.scala`: a public endpoint at prefix `/health`
      answering `503 {"status":"starting"}` until `Main` marks the service ready, then
      `200 {"status":"ready"}`, raw with no newline
- [X] T013 [P] Implement `S/st8/St8Bodies.scala`: the `stub` shape (alphabetical string-only maps,
      no newline) for check, debit, free debit, credit, cancel, and the `harness` shape for balance,
      debit/credit (with `round_closed`, `bonus: null`, the `provider` object), cancel, buyin, payout,
      with `extra` fields and a `malformed` body, exactly as `contracts/st8-wire.md` lists them
- [X] T014 [P] Write `T/st8/St8BodiesSuite.scala`: one assertion per body in `contracts/st8-wire.md`
      comparing the exact bytes to a literal copied from the contract (sorted keys, `"0.00"`,
      `free_debit`, no trailing newline), and one per operator-API answer shape (`\n`, HTML escaping,
      sorted keys) that T033 and T049 will satisfy
- [X] T015 Implement `S/brands/Sender.scala`: a `java.net.http.HttpClient` per brand entry
      (`caPem` into an `SSLContext`, `connectTo` with `Host`/SNI kept, research R3), `send(brand,
      route, body, key)` posting with `Content-Type: application/json` and `x-st8-sign`, a per-request
      timeout of the schedule's `wait`, returning `Attempt` data (HTTP status, parsed `status`,
      `balance`, `currency`, raw body, `tookMillis`, or the transport error) and redacting
      `player_profile`'s `birth_date`, `reg_country`, `reg_date` (FR-023); unit suite
      `T/brands/SenderSuite.scala` against a `ScriptedService`
- [X] T016 [P] Implement `S/core/Session.scala` and `S/core/SessionEntity.scala` (event-sourced,
      id = launch token): state, events and rules of data-model *Session* and *Round* (`Launched`,
      `ScenarioSelected` refused with `Conflict` after `Checked` or when one stands, `Checked`,
      `RoundOpened`, `CallbackPlanned`, `RoundSettled`, `StepJudged`, `Discarded`); generated ids and
      times passed in by the command, never drawn in `applyEvent`; queries `state`, `verdict`
- [X] T017 [P] Implement `S/core/Callback.scala` and `S/core/CallbackEntity.scala` (event-sourced,
      id = callback id): `Scheduled`, `AttemptSent` idempotent on `attempt`, `Answered`, `GaveUp`,
      `Discarded` (persist then `deleteEntity`), outcomes of data-model *Callback*
- [X] T018 [P] Write `T/core/SessionEntitySuite.scala` and `T/core/CallbackEntitySuite.scala` with
      `EventSourcedTestKit`: a second scenario selection is a `Conflict`; a selection after `Checked`
      is a `Conflict`; a cancel of a transaction the session never sent is a `BadRequest`; a repeated
      `AttemptSent(attempt = 2)` persists once; `RoundSettled` on an unknown round is refused
- [X] T019 [P] Implement `S/core/Scenario.scala` (the model of `contracts/scenario.schema.json`,
      durations parsed from `30s`/`10m`/`8h`/`250ms`), `S/core/ScenarioEntity.scala` (key-value, id =
      name, `put` refusing `default`'s replacement, `get`) and a validator over the schema (the
      `json-schema-validator` the brands tool already uses) returning the first problem's path
- [X] T020 [P] Write `src/main/resources/scenarios/default.json` (`pass-through`, `intervals: []`)
      and `src/main/resources/games.json` (the three games of data-model *Game*), and
      `T/core/ScenarioSuite.scala` validating every file under `scenarios/` against
      `specs/020-game-provider-simulator/contracts/scenario.schema.json` and asserting each has a
      non-empty `source`
- [X] T021 Implement `S/Main.scala`: register `SessionEntity`, `CallbackEntity`, `ScenarioEntity`
      (and the later components as their tasks add them), `ProjectionRuntime()`, `TimerRuntime()`
      before `HttpServer`, `start(name = "game-provider-simulator")`; after start: `Keys.ensureCurrent()`,
      seed `scenarios/*.json` into `ScenarioEntity` when the file's `version` is higher than the
      stored (research R10), load `BrandTable`
- [X] T022 [P] Implement `S/api/Access.scala`: `operatorFor(brand)` = `Routes.operator(brand)` after
      checking the brand is in the table (`404 unknown_brand` otherwise), `tools` =
      `Routes.operator("tools")`, `public` = `Routes.public`; nothing else declares an ACL (FR-027);
      and the skeleton of `S/api/SimulatorEndpoint.scala`, the one endpoint at prefix `/v1/simulator`
      whose route groups each sit in their own `withAcl` (ankka refuses two endpoints sharing a prefix)
- [X] T023 Write `T/SimulatorTestKit.scala`: `AnkkaTestKit.start` with every descriptor, a
      `ScriptedService` playing the brand (answers, `delay`, `failNext`, recorded requests), `settings`
      pointing `SIMULATOR_BRANDS` at it with `testBrand: true` and a second entry with `false`, a
      staff issuer the testkit can mint tokens for (`ANKKA_AUTH_*` via `settings`), helpers
      `staffToken(roles*)`, `lastRequestTo(route)`, `verifySignature(request)` using the key
      document; plus a first suite proving `Keys` creates `keys/current` once and the document serves it
- [X] T024 [P] Write `T/steps/SimulatorFeatures.scala`: `GherkinSuite("features/simulator")` with the
      shared `Background` step ("a brand configured with the simulator as its game provider") bound to
      the test kit and every other step pending, so `sbt test` names what is unbound from now on
      — Done differently: an unbound step fails its scenario rather than pending, so each story binds its whole feature file (RoundsFeatures, ScenariosFeatures, CatalogueFeatures, RunsFeatures, LoadFeatures); research R13.
- [X] T025 [P] Write `T/core/SeamSuite.scala` enforcing FR-006 and FR-015: scans
      `src/main/scala/io/eitheror/casino/simulator/core/` and fails on any import of
      `io.eitheror.casino.simulator.st8`, any `/st8/` or `/api/operator` route literal, and any of the
      ten status words as a string literal, naming the file and line

**Checkpoint**: `sbt test` in the service passes with the byte suite red for the operator API only
and the features listed as pending; `just features` is green.

---

## Phase 3: User Story 1 — A brand plays a round against the simulator exactly as against `fake-st8` (P1) 🎯 MVP

**Goal**: launch, game page and `check`, the instruction routes bet/win/lose/cancel/session in the
default scenario, the brand's answer proxied verbatim, every callback recorded.

**Independent Test**: `features/simulator/rounds.feature` passes against the scripted brand; the
recordings under *Recordings* replay in spec 003's kit with the simulator as provider (quickstart §2, §3).

### Tests for User Story 1

- [X] T026 [P] [US1] Bind the six scenarios of `features/simulator/rounds.feature` in
      `T/steps/RoundsFeatures.scala`: launch stores the session and answers `game_url`; the page load
      sends `{"token":…}` to `/st8/check` and keeps the answer's token; a bet is one signed debit with
      a fresh `transaction_id` whose `x-st8-sign` verifies and whose answer is returned unchanged; win,
      loss (`"0.00"`), cancel naming the bet's id with a fresh `cancel_id`; the replay scenario bound
      as pending until spec 003's kit exists, naming it; plus the edge case of a launch ticket used
      twice (the scripted brand answers `session_expired` to the second `check` and the simulator
      serves the session-expired page with HTTP 200)
- [X] T027 [P] [US1] Write `T/port/OperatorApiParitySuite.scala`: one `test` per recording in
      `tests/port/simulator/`, replaying its steps against the running test kit with
      `contract.port.Replay.run` and failing with the recording's path and first differing JSON path;
      red until T029 extracts the recordings

### Implementation for User Story 1

- [X] T028 [US1] Implement `S/st8/OperatorApi.scala` `launch` (public): `POST /api/operator/v1/launch`
      reading `game_code`, `currency`, `token`, `player`, `fun_mode`, `site`, `player_profile`,
      creating the `Session` (`Launched`), answering `{"game_url":"<SIMULATOR_EXTERNAL_URL>/game?token=<token>"}\n`,
      400 on bad JSON, 405 on other methods, as the wire contract says
- [X] T029 [US1] Extract `tests/port/simulator/*.json` from the `outbound` lists of the recordings
      spec 003 names (`tests/port/game-provider/`): one recording per operator-API request shape
      (`launch`, later `games`, `bonus/create`, `bonus/get`, `bonus/cancel`), `vary` on the token in
      `game_url`; where a recording has no `outbound` yet (`tests/original/README.md`, "Outbound
      calls"), record `fake-st8`'s answer to the same request by hand, **twice on fresh ids** and
      merged with `tests/original/merge` so the file has `runs: 2` and `just brands recordings check`
      accepts it; say so in `requirement`
- [X] T030 [US1] Implement `S/st8/GamePage.scala` (public): `GET /game?token=`; `400 missing token`;
      sends `check` through `Sender` in `stub` shape, records a `Callback`, on `ok` persists
      `Checked(walletToken)` and serves `pages/game.html` with the two data attributes; on any failure
      serves `pages/session-expired.html` with HTTP 200; both templates copied from `main.go:689-713`
      into `src/main/resources/pages/`
- [X] T031 [US1] Implement `S/st8/Instructions.scala` (public): `GET /test/session/{token}`,
      `POST /test/bet`, `/test/win`, `/test/lose`, `/test/cancel` with the bodies, defaults, generated
      ids, 404/409 and the verbatim proxy (status and body, `Content-Type` forced, `502 adapter error:`
      on transport failure) of the wire contract; each sends once through `Sender` with no retry in
      the default scenario, records a `Callback` and plans it on the `Session`
- [X] T032 [US1] Implement the default scenario's path in `S/core/Play.scala`: `instruct(session,
      kind, instruction)` resolving the session's scenario; for `pass-through` it builds the `stub`
      body, sends synchronously and returns the answer; for a scripted scenario it hands the
      instruction to `ScenarioPlay` (T039) as an `on: instruction` step; opens and settles rounds on
      the `Session` (`RoundOpened`, `RoundSettled` on a loss, win or cancel)
- [X] T033 [US1] Implement `S/st8/Answers.scala`: the JSON writer that matches Go's encoder (`\n`,
      `<`/`>`/`&` escaped, sorted keys) and the plain-text error writer, used by every operator-API
      and instruction answer; turns `T/st8/St8BodiesSuite.scala`'s answer assertions green
- [X] T034 [US1] Implement readiness in `S/Main.scala`: `Health` (T012) answers `starting` until
      `Keys.ensureCurrent()` has run, the scenarios are seeded and every `registerKeyAt` push succeeded
      (T068), then `ready`; a test in `T/st8/HealthSuite.scala` reads both answers

**Checkpoint**: quickstart §1 and §2 pass; §3 runs by hand once spec 003's kit takes a provider
address.

---

## Phase 4: User Story 2 — A scenario makes the provider misbehave on purpose (P1)

**Goal**: scheduled, retried and out-of-order callbacks driven by scenario data; a verdict per step;
the eleven shipped scenarios; scenario selection per session and per brand; the library as data.

**Independent Test**: `features/simulator/scenarios.feature` passes against the scripted brand
(quickstart §4); each scenario file validates and cites its source.

### Tests for User Story 2

- [X] T035 [P] [US2] Bind the nine scenarios of `features/simulator/scenarios.feature` in
      `T/steps/ScenariosFeatures.scala`: the scripted brand delays past the wait for `slow-operator`
      (two attempts, one `transaction_id`, equal answers, balance moved once), answers `ok` then is
      sent a cancel for `cancel-after-credit`, receives a credit before its debit for `out-of-order`,
      answers `error` N times for `retry-storm` (count and time reported), sees the next key published
      then used for `rotated-key` and refuses the withdrawn key, `session_expired` for
      `unknown-token`, `"1000000.00"` after `"0.10"` for `win-larger-than-any-bet`, the window then a
      `"0.00"` credit for `orphaned-bet`, and two sessions with different scenarios on one brand; plus
      the edge case of a brand answering `auth_failed` to everything (no key document configured),
      whose verdict names the first step
- [X] T036 [P] [US2] Write `T/core/CallbackWorkflowSuite.scala` through the test kit: a brand
      answering `error` is retried on a millisecond schedule from the scenario and ends at the cap
      with `GaveUp`; a `session_expired` ends after one attempt; HTTP 400 is `malformed_body` and
      never retried; an unreachable brand records each attempt; the step rerun records no duplicate
      attempt

### Implementation for User Story 2

- [X] T037 [US2] Implement `S/core/CallbackWorkflow.scala` (id = callback id): command `start(plan)`
      (`Scheduled` on the entity), step `send`: read the key named by the plan from `Keys`, send
      through `Sender`, `AttemptSent` then `Answered` on the entity, then decide: a word in `retryOn`
      or no answer → `thenPause(nextInterval, send.ref)` if elapsed < cap else `GaveUp` and end; any
      other word or HTTP 400 → end; `stepTimeout(send) = wait + 5s`, `defaultRecovery(fail)`, no
      workflow `timeout`; lifecycle readable (research R4)
      — Done as `S/core/ScenarioPlay.scala`: one workflow per session sends, retries on the schedule and judges; no per-callback workflow (research R13).
- [X] T038 [US2] Implement `S/core/Schedule.scala`: `nextInterval(elapsed)` from the scenario's
      intervals (`every`/`for` runs, the last to the cap), the spec's default schedule as the shipped
      constant used when a scenario omits `schedule`; unit suite `T/core/ScheduleSuite.scala`
      (20 attempts in the first ten minutes, then every ten minutes, 67 in eight hours)
- [X] T039 [US2] Implement `S/core/ScenarioPlay.scala` (workflow, id = launch token): walks the
      scenario's steps from the session's cursor; `send` with `on: now` starts a `CallbackWorkflow`
      and waits for its end (poll lifecycle with a short pause); `on: instruction` pauses until
      `Play.instruct` resumes it; `expect` reads the judged callback's last attempt and the balances
      before and after, persists `StepJudged` on the session; `wait` pauses for the duration, range
      (drawn through `Seeded`) or `until` (brand bound or window); `settle-open-rounds` sends `"0.00"`
      credits; `rotate-key` calls `Keys.publishNext`, pauses the brand's bound, `promote`, `withdrawOld`
- [X] T040 [US2] Implement transaction and token references in `S/core/Play.scala` /
      `ScenarioPlay`: `transaction: new | same-as <step> | unknown`, `round: new | same | none`,
      `token: session | unknown | none`, `key: current | next | wrong | withdrawn` (a wrong key is a
      fresh pair never published), `malformed: true`, `extra`, `gameCode` override; every generated
      id drawn or minted in the command/step and persisted (research R6)
- [X] T041 [P] [US2] Write the shipped scenario files under `src/main/resources/scenarios/`, each
      with `source` (a `tests/port/` recording path or the harness function and line from research R7)
      and `shape: harness` unless noted: `slow-operator.json`, `cancel-after-credit.json`,
      `out-of-order.json`, `retry-storm.json` (a per-step `schedule` of short intervals and a cap),
      `rotated-key.json`, `wrong-key.json`, `unknown-token.json`, `win-larger-than-any-bet.json`,
      `orphaned-bet.json`, `malformed-body.json`; `T/core/ScenarioSuite.scala` (T020) covers them
- [X] T042 [P] [US2] Write scenario files for the harness's cases that are scripted sends rather
      than failure modes, so FR-018's source list is covered: `harness-basic.json` (BasicBalance,
      BasicDebit, BasicCredit, BasicCancel, BasicBuyin, BasicPayout, ZeroDebit, ZeroCredit,
      StandaloneCredit), `harness-rounds.json` (BigRound*, CancelEverythingWithinRound, Resettlement,
      TableSwitch, BigRoundFailedDebit), `harness-idempotent.json` (IdempotentDebit, IdempotentCredit,
      IdempotentCancel), `harness-edges.json` (NotEnoughMoney, ZeroBalanceFreshSpinDeclined,
      BalanceWithoutToken, LimitedCancel, NewParams, StandaloneCancel with two accepted words,
      CorrectionDebitNegativeBalance, WrongSignature with `key: wrong`), each `expect` stating the
      harness's assertion
- [X] T043 [US2] Implement scenario selection in `S/api/SimulatorEndpoint.scala` (`Access.operatorFor
      (brand)`): `PUT /v1/simulator/brands/{brand}/sessions/{token}/scenario` (`ScenarioSelected`, 409
      as the entity refuses, 404 unknown scenario) and `PUT /v1/simulator/brands/{brand}/scenario`
      (the brand's default, held on a `BrandDefaults` key-value entity in `S/core/BrandDefaults.scala`,
      read by `launch` when a session has no selection)
- [X] T044 [US2] Implement the scenario library routes in `S/api/SimulatorEndpoint.scala`
      (`Access.tools`): `GET /v1/simulator/scenarios`, `GET /v1/simulator/scenarios/{name}`,
      `PUT /v1/simulator/scenarios/{name}` validating against the schema (400 naming the first
      problem's path, 409 for `default`), so a tester adds a scenario without a release (SC-005)
- [X] T045 [US2] Implement `GET /v1/simulator/brands/{brand}/runs/{runId}/verdict` in
      `S/api/SimulatorEndpoint.scala` from the `Session` entity's `StepJudged` events: expected against
      observed per step, `passed`, the first failing step (data-model *Verdict*)
- [X] T046 [US2] Implement `POST /v1/simulator/brands/{brand}/keys/rotate` in
      `S/api/SimulatorEndpoint.scala` starting a `KeyRotation` workflow (`S/st8/KeyRotation.scala`:
      publish next, pause the brand's bound or the body's `boundSeconds`, promote, withdraw, push to
      every `registerKeyAt`), shared with the `rotate-key` step

**Checkpoint**: quickstart §4 passes against the scripted brand; SC-003 (§8) holds for one scenario.

---

## Phase 5: User Story 3 — The operator API and free rounds behave as the provider's (P2)

**Goal**: the games listing, bonus create/get/cancel, the free-spin instruction.

**Independent Test**: `features/simulator/catalogue-and-free-rounds.feature` passes; the `games` and
`bonus/*` parity recordings replay (quickstart §5).

### Tests for User Story 3

- [X] T047 [P] [US3] Bind the six scenarios of `features/simulator/catalogue-and-free-rounds.feature`
      in `T/steps/CatalogueFeatures.scala`: the listing in the provider's shape with the three games;
      create answers `ok` with one `active` instance per player; an unknown game answers
      `{"error":"unknown_game","status":"error"}`; a free spin sends `free_debit` `"0.00"` then the
      credit on one round and decrements (`used` at zero); no instance is `409 {"error":"no_free_rounds"}`
      and nothing is sent; cancel marks `canceled` and later spends are refused
- [X] T048 [P] [US3] Write `T/st8/BonusEntitySuite.scala` (`KeyValueEntityTestKit`): a second create
      with the same id replaces; decrement to zero sets `used`; cancel sets `canceled`; instance
      selection takes the lowest bonus id with an `active` instance for the game (research R7)

### Implementation for User Story 3

- [X] T049 [US3] Implement `GET /api/operator/v1/games` in `S/st8/OperatorApi.scala` (any method,
      query and signature ignored): `games` from `games.json` plus `SIMULATOR_GAMES_EXTRA` in seed
      order with the game object's fields, `developers` and `categories` sorted by id, category names
      `Slots`/`Table Games`, written by `Answers` so the byte suite's listing assertion is green
- [X] T050 [P] [US3] Implement `S/st8/Bonus.scala` and `S/st8/BonusEntity.scala` (key-value, id =
      bonus id) per data-model *FreeRoundsInstance*: `create` (replace), `get`, `cancel(player)`,
      `spend(player, gameCode)` returning the chosen bonus and remaining or `NoFreeRounds`
      — Done as `S/st8/BonusBook.scala`: one key-value entity holding every bonus (research R13).
- [X] T051 [US3] Implement the bonus routes in `S/st8/OperatorApi.scala`: `POST
      /api/operator/v1/bonus/create` (`count` as number or numeric string, unknown game →
      `unknown_game`, envelope in `players` order), `GET /api/operator/v1/bonus/get` (`{"status":"error"}`
      for unknown), `POST /api/operator/v1/bonus/cancel`, and `GET /test/bonus/{id}` in
      `S/st8/Instructions.scala`, each as the wire contract says
- [X] T052 [US3] Implement `POST /test/free-spin` in `S/st8/Instructions.scala`: 404/409 as bet;
      `Bonus.spend`; `409 {"error":"no_free_rounds"}`; a `free_debit` `"0.00"` (currency from the bonus
      else the session) then a credit of `winAmount` on one round, both through `Play.instruct` so a
      scripted scenario still governs them; answer `{"credit":…,"debit":…,"remaining":N,"roundId":…}\n`;
      `502 adapter free_debit error:` / `502 adapter credit error:` on transport failure
- [X] T053 [US3] Add the `games`, `bonus/create`, `bonus/get` and `bonus/cancel` recordings to
      `tests/port/simulator/` (T029's method) so `T/port/OperatorApiParitySuite.scala` covers every
      operator-API shape the original's recordings hold

**Checkpoint**: quickstart §5 passes.

---

## Phase 6: User Story 4 — Every run is observable and can become a parity test (P2)

**Goal**: the run view and route, the export as a recording, retention and the status.

**Independent Test**: `features/simulator/runs.feature` passes; an export loads with
`Recording.load` and replays (quickstart §6).

### Tests for User Story 4

- [X] T054 [P] [US4] Bind the four scenarios of `features/simulator/runs.feature` in
      `T/steps/RunsFeatures.scala`: the run lists each callback with body, key id, times, answer word
      and balance, time taken, attempt and step (polling the view); the export parses with
      `contract.port.Recording.load` and its steps replay against the scripted brand through
      `Replay.run`; the verdict shows expected against observed and the first failing step; runs past
      the retention are swept when the `TimerRuntime` clock is moved and counted on the status; plus
      the edge case of a run route, a scenario selection and a load start without a staff token
      (401 from the ACL) after which the run shows nothing new
- [X] T055 [P] [US4] Write `T/api/ExportSuite.scala`: a run with a `check`, two attempts of one debit
      and a malformed-body send exports as `contracts/run-export.md` maps it (`capture` on check,
      `vary` on `x-st8-sign` and generated ids, no step for an unanswered attempt, `outbound` empty)

### Implementation for User Story 4

- [X] T056 [US4] Implement `S/core/RunCallbacks.scala`: a `View` over `CallbackEntity`'s events with
      the row of data-model *RunCallbacks*; declared queries `by-run` (ordered by `firstSentAt`, keyset
      `:after`, limit), `by-brand-before` (for the sweep) and `count-by-status` (for load reports);
      register it in `Main`
- [X] T057 [US4] Implement `GET /v1/simulator/brands/{brand}/runs/{runId}?after=` in
      `S/api/SimulatorEndpoint.scala` from the view, pages of at most 500 rows with the next `after`
- [X] T058 [US4] Implement `S/api/Export.scala` and `GET /v1/simulator/brands/{brand}/runs/{runId}/export`
      per `contracts/run-export.md`, producing JSON that `contract.port.Recording.parse` accepts
- [X] T059 [P] [US4] Implement `S/core/SimulatorStatus.scala` (key-value, id `status`: `sweptRuns`,
      `lastSweepAt`, keys, brands) and `GET /v1/simulator/status` in `S/api/SimulatorEndpoint.scala`
- [X] T060 [US4] Implement `S/core/Sweep.scala`: a `TimedAction` `sweep` listing runs with
      `lastAnsweredAt` before now minus `SIMULATOR_RETENTION_DAYS` through the view, sending
      `discard` to every `Callback` and `Session` of each run (persist then delete), adding the count
      to `SimulatorStatus`, returning `done` always; in `Main`, `createRecurringTimer("sweep-runs",
      Duration.Zero, 1.hour, Sweep.sweep.deferred)` at every start

**Checkpoint**: quickstart §6 passes; SC-006 replays in the scripted kit.

---

## Phase 7: User Story 5 — A load profile plays many sessions at a rate (P3)

**Goal**: N sessions, M rounds a minute, a duration, a report; stop settles open rounds; refused for
a non-test brand.

**Independent Test**: `features/simulator/load.feature` passes with a small profile against the
scripted brand; quickstart §7 by hand against a rebuilt brand.

### Tests for User Story 5

- [X] T061 [P] [US5] Bind the two scenarios of `features/simulator/load.feature` in
      `T/steps/LoadFeatures.scala` with a profile of 5 sessions at 60 rounds a minute for 10 seconds
      against the scripted brand: sessions checked, rounds at the rate, the report's rounds sent,
      answers by word and p50/p95/p99; a stop settles open rounds as `"0.00"` credits before `stopped`;
      plus a refusal `403 {"error":"not_a_test_brand"}` against the table's non-test brand
      — Done: the stop scenario is bound; the 100-session five-minute scenario is `ranElsewhere` (quickstart §7, SC-004) and `LoadProfileSuite` proves the mechanics with a small profile.

### Implementation for User Story 5

- [X] T062 [US5] Implement `S/core/LoadProfile.scala` (workflow, id = profile id) per data-model
      *LoadProfile*: `start` (refuse `Conflict` when running), steps `launch` (check every launch
      token through `Sender`, `invokeAsync` fan-out), `tick` (for each session due this interval,
      issue a bet then a win or a loss drawn from the profile's seed through `Play.instruct`, so the
      session's instruction-driven scenario governs each send; then `thenPause(interval, tick.ref)`
      until the deadline; no `ScenarioPlay` runs under a profile), `settle` (`"0.00"` credits for open rounds; `stop` transitions to
      it), `report` (from the view's `count-by-status` and the attempts' `tookMillis` percentiles)
- [X] T063 [US5] Implement the load routes in `S/api/SimulatorEndpoint.scala`: `POST
      /v1/simulator/brands/{brand}/load-profiles` (403 `not_a_test_brand` unless the table says
      `testBrand: true`, 409 when one runs, 400 `scenario_not_instruction_driven` when the named
      scenario has a `send` with `on: now`), `POST …/{id}/stop`, `GET …/{id}` with state and report

**Checkpoint**: quickstart §7 by hand; SC-004 recorded in the PR.

---

## Phase 8: Polish and cross-cutting

**Purpose**: the original's suite, the deploy, the realm role, documentation, the final checks.

- [X] T064 [P] Write `T/core/DeterminismSuite.scala`: `win-larger-than-any-bet` with `seed: 42`
      against the scripted brand twice on two test kits produces exports equal outside `vary` (SC-003)
- [X] T065 [P] Add `tests/port/simulator/README.md` naming how each recording was made (outbound
      list or hand-recorded) and the parity suite that replays it
- [X] T066 [P] Add the realm role `brand_tools` to the `eitheror` realm in `installation/base`
      (Keycloak realm JSON or its template) and note in `installation/README.md` that `platform_admin`
      reaches the simulator's tools routes without it (research R2)
      — Done as documentation in `installation/README.md`: this repository holds no `eitheror` realm file to add the role to; `platform_admin` reaches every route meanwhile.
- [ ] T067 [P] Update `brain/projects/ankka-casino.md` (the eitheror brain, parent repository) with
      spec 020's status: planned, tasks written, the `tools` project added; and `GLOSSARY.md` if any
      term changed (none expected: run `just features`)
      — Not done: the brain is the parent `eitheror` repository, outside this worktree; update it when the branch merges.
- [X] T068 Implement `registerKeyAt` in `S/st8/Keys.scala` / `Main`: for every brand entry naming
      it, `POST {"publicKey": spki}` up to 60 attempts 2 s apart until HTTP 200 at start and after a
      rotation; `/health` stays `starting` until every push succeeded (research R8)
- [ ] T069 Run quickstart §9 by hand against the original: start the original's stack without
      `fake-st8`, the simulator with the `spinvibe` entry, trigger `run-catalog-sync`, run the
      `FreeSpinsPlay`, `Scenario19` and `Scenario244` tests with the stand-in's address pointed at the
      simulator; record the outcome and any test blocked by the catalogue change in
      `specs/020-game-provider-simulator/quickstart.md` under §9
      — Not done: needs the original's whole stack running (compose plus its native services); left for a by-hand run.
- [X] T070 Run quickstart §10 on `dev-kind`: `sbt Docker/publishLocal`, `kind load`, `just brands
      apply shared/tools/manifest.json`, `curl` the key document through the gateway, and a callback
      to a brand's gateway hostname from inside the cluster; if it cannot connect, set `connectTo` and
      `caPem` in the manifest and record the finding in research R12
      — Done 2026-10-11 on a fresh cluster: `tools` applied, the service Ready and exposed, its public routes answered through the gateway. The in-cluster check found `connectTo` unworkable (SNI follows the URI's host); it was removed for a CoreDNS rewrite (`installation/overlays/dev-kind/gateway-dns.sh`) and proved from the deployed simulator's pod (research R3).
- [X] T071 Run `just features`, `sbt test` at the root and in the service, and `sbt brands/test`;
      fix anything red; confirm `specs/020-game-provider-simulator/checklists/requirements.md` still
      passes and update plan.md's *Constitution re-check* if anything changed
      — Done 2026-10-10: `just features` 0 findings; root 156 + 54 passed; service 112 passed (2 run elsewhere); recordings 4, problems 0; zero compiler warnings.
- [X] T072 Update `specs/020-game-provider-simulator/spec.md` *Status* from Draft to Implemented
      and list under *Recordings* the `tests/port/simulator/` files that now exist

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 before T005 (the contract must publish with `tools`); T002–T004,
  T006, T007 in parallel with each other.
- **Foundational (Phase 2)**: depends on Phase 1. T008–T010, T013, T014, T016–T020, T022, T024 in
  parallel; T011 after T010; T012 after T011; T015 after T008 and T010; T021 after T011, T016,
  T017, T019; T023
  after T021 and T022. Blocks every story.
- **US1 (Phase 3)**: after Phase 2. T026, T027 first (red). T028 → T030 → T031; T032 and T033 beside
  them; T029 any time after T028; T034 last.
- **US2 (Phase 4)**: after US1 (uses `Play`, `Instructions`, the sender path). T035, T036 first.
  T038 → T037 → T039 → T040; T041, T042 in parallel with the workflows; T043–T046 after T039.
- **US3 (Phase 5)**: after Phase 2 and T031/T032 (free-spin goes through `Play`); otherwise
  independent of US2. T047, T048 first; T049, T050 in parallel; T051, T052 after T050; T053 after T051.
- **US4 (Phase 6)**: after US1 (callbacks exist); the verdict route (T045) is US2's. T054, T055
  first; T056 → T057, T058; T059 in parallel; T060 after T056 and T059.
- **US5 (Phase 7)**: after US1 and T056 (the report reads the view). T061 first; T062 → T063.
- **Polish (Phase 8)**: T064–T067 any time after US2/US4; T068 after T011; T069 after T068 and
  US3 (the original's tests use free spins); T070 after T004 and US1; T071, T072 last.

### Platform and sibling gates

- Spec 003's kit must take a provider address and a key-document address for quickstart §3 and §6
  (plan.md, *What the next command needs to know*); US1's replay scenario stays pending until then.
- The first `dev-kind` deploy (T070) verifies research R3; nothing before it depends on the answer.
- The `brand_tools` role (T066) is optional while `platform_admin` is used.

### Parallel Opportunities

- Phase 2: eleven of sixteen tasks are `[P]`; two people (or agents) take "wire" (T010, T013, T014,
  T015) and "core" (T009, T016–T020) and meet at T021.
- US2's scenario files (T041, T042) are written while the workflows are built.
- US3 and US4 can proceed in parallel once US1 is done.

## Parallel Example: User Story 1

```text
# red first, in parallel:
T026 T/steps/RoundsFeatures.scala
T027 T/port/OperatorApiParitySuite.scala
# then the operator API's launch, and beside it the answer writer:
T028 S/st8/OperatorApi.scala (launch)      ||  T033 S/st8/Answers.scala
# then, in order:
T030 S/st8/GamePage.scala → T031 S/st8/Instructions.scala → T032 S/core/Play.scala
# any time after T028:
T029 tests/port/simulator/*.json
# last:
T034 readiness in S/Main.scala
```

## Implementation Strategy

### MVP first (User Story 1)

Phases 1–3: a simulator a rebuilt brand cannot tell from `fake-st8`. It launches, serves the game
page, checks, bets, wins, loses and cancels, proxies the brand's answer verbatim, records every
callback, and replays the operator-API recordings. Stop here and the port method has its provider.

### Incremental delivery

1. US2 next: the scenarios are why the simulator exists (both P1 stories before anything P2).
2. US3 and US4 in parallel: the catalogue and free rounds unblock four of the original's recordings;
   the run view and export make every scenario run a parity test.
3. US5 when spec 002's latency criterion needs measuring.
4. Polish: the original's suite (§9) and the deploy (§10) close FR-024 and FR-026.

## Notes

- Every `[P]` task touches files no other unfinished task touches.
- A scenario file without a `source` is not shipped (FR-018); T020's suite enforces it.
- Byte fidelity is asserted against literals copied from `contracts/st8-wire.md`, not computed.
- Nothing here calls the original except T069, by hand, locally.
- Entities never draw randomness or read a clock: callers pass ids and times in (research R6).
