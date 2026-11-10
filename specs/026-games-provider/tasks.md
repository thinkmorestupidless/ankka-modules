# Tasks: games — eitheror's own game provider, beside ST8

**Input**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

**Tests**: required. The constitution holds every service to suites at three levels (unit kit,
bound features, the integration kit over Postgres), and every scenario in `features/games/` is
bound in the provider's `GherkinSuite`; the simulator's suites must stay green on the moved core
(SC-007); the amendments are bound in game-provider's and kyc's suites.

**Order**: the plan's build order. The library first and the simulator green on it before a line
of the provider is written; then US2 (the game contract) before US1, because US1's round is played
by `TestGame` through that contract; then the brand table, keys, free rounds, operators; the
amendments to the brand side and KYC last, because nothing in the provider depends on them and
their suites run in other builds.

Paths: `P` = `provider-core/src`, `E` = `services/provider/src`, `SIM` =
`services/game-provider-simulator/src`; packages `io.eitheror.casino.provider` (library) and
`io.eitheror.casino.provider.edge` (the service) beneath `main/scala` and `test/scala`.

## Phase 1: Setup

- [X] T001 Add the `providerCore` subproject to `build.sbt` per `contracts/provider-core.md` (`provider-core/`, name `casino-provider-core`, depends on `contract`, `commonTest`, ankka sdk/http/runtime, jackson, json-schema-validator, testkit and munit in Test, a `tests` classifier artifact for the kits); `sbt providerCore/compile` passes on an empty source tree
- [X] T002 [P] Scaffold `services/provider/` with `ankka init` (ankka 0.13.0): `build.sbt` depending on `casino-contract` and `casino-provider-core` (both `CASINO_VERSION`), `service.json` (`provider`, runtime 0.13.0), `docker-compose.yml`, `README.md` (what it is, how to test, run locally beside a brand), an empty `Main.scala`
- [X] T003 [P] `Justfile`: `just service` publishes `providerCore` beside `contract` before a service task; recipes `provider-test`, `provider-it`, `provider-perf`; note in `README.md` at the root
- [X] T004 [P] `.github/workflows/ci.yml`: path filters `provider-core/**` and `services/provider/**`; a `provider` job (publish contract and providerCore locally, `sbt test` in `services/provider`); the simulator's and game-provider's jobs publish `providerCore` too

## Phase 2: Foundational — the library, and the simulator green on it

- [X] T005 `git mv` the files `contracts/provider-core.md` names from `SIM/main/scala/io/eitheror/casino/simulator/{core,st8,brands}/` into `P/main/scala/io/eitheror/casino/provider/{core,st8,brands}/` with the package rename and nothing else changed; `sbt providerCore/compile`
- [X] T006 Re-point the simulator: `services/game-provider-simulator/build.sbt` depends on `casino-provider-core`; every import in `SIM/main` and `SIM/test` follows the rename; `Simulation.install` and `Main` unchanged in behaviour; `cd services/game-provider-simulator && sbt test` green (SC-007) with `features/simulator/*.feature` bound as before
- [X] T007 [P] Move `FakeBrand` and `SeamSuite` from `SIM/test` into `P/test/scala/io/eitheror/casino/provider/` (`SeamSuite` still holds `core` to naming no protocol word); the simulator's suites use them from the `tests` classifier
- [X] T008 [P] `README.md` of the simulator: the core is now `provider-core`; a dev installation's simulator data is dropped at the upgrade (`ankka services delete game-provider-simulator`, then apply), per `contracts/provider-core.md`
- [X] T009 `SessionEntity` gains `demo` and `language` on `Launched` (nothing of the launch's profile) (data-model: Session) in `P/main/.../core/Session.scala` and `SessionEntity.scala`, with a `pending` round state set by a stake not yet answered; the simulator's `Launch` command leaves them defaulted; entity suite in `P/test/.../core/EntitySuite.scala`
- [X] T010 [P] `CallbackEntity` gains `Abandoned(by, reason, at)` and the states `exhausted` and `abandoned` (data-model: Callback) in `P/main/.../core/Callback.scala` and `CallbackEntity.scala`; suite in `EntitySuite.scala`
- [X] T011 [P] `Signer.verify(body, signature, publicKey)` and `Signer.publicKeyFromPrivate` in `P/main/.../st8/Signer.scala` (research R5, R11); suite in `P/test/.../st8/SignerSuite.scala` with a signature from game-provider's `St8Signature` as the oracle
- [X] T012 `Deliver` workflow (research R6, data-model: Deliver) in `P/main/.../core/Deliver.scala`: id the callback id; `send` through `Play.send`; on acceptance or refusal `end`; on retryable, `pause` for `Schedule.nextInterval`; past the cap `exhaust`; an `abandon` command; every attempt re-sends the same signed body; unit suite `P/test/.../core/DeliverSuite.scala` with a scripted `Outbound` (late, error, never, then ok)
- [X] T013 `Schedule.fromJson` for `PROVIDER_RETRY_SCHEDULE` (the scenario schema's `schedule` shape; research R10) in `P/main/.../core/Schedule.scala`, default 30 s for 10 min then 10 min, cap 8 h; suite in `P/test/.../core/ScheduleSuite.scala`
- [X] T014 `TestGame` in `P/test/scala/io/eitheror/casino/provider/TestGame.scala` (`contracts/game-contract.md`): registers `test-slot` and `test-table` through the contract's routes, plays stake/settle/cancel on instruction, holds free-round instances in `BonusBook`, serves `/v1/game/bonus/*` and `/v1/game/health`; compiles here against the contract's bodies, and its first run is T026 (the routes it calls are T023 to T025)
- [X] T015 [P] `Layout.SharedProjects += "games" -> Vector("provider")` in `contract/src/main/scala/io/eitheror/casino/contract/Layout.scala`; `games` and `provider` in the enums of `specs/001-brands-and-projects/contracts/shared-manifest.schema.json` and `tools/modules/src/main/resources/shared-manifest.v1.json`; a modules-tool test that `shared/games/manifest.json` (T016) validates and is not refused with `--real-money` while `tools` still is, in `tools/modules/src/test/.../ManifestSuite.scala`
- [X] T016 [P] `shared/games/manifest.json` per `contracts/brand-entry.md`; `contract/keys.md` lists the provider's `PROVIDER_*` settings and `provider` in the "Not yet built" table until T022 lands (then move it)
- [X] T017 [P] `Routes.installationOperatorHolding(roles*)` (a staff token at the eitheror realm holding one of the roles, no brand role needed; `contracts/provider-routes.md`) in `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`; suite in `contract/src/test/.../access/RoutesSuite.scala`; the `games` realm role in `brands/_realm/` beside `markets`
- [X] T018 `Settings.fromEnvironment` in `E/main/.../edge/Settings.scala`: `PROVIDER_EXTERNAL_URL`, `PROVIDER_RETENTION_DAYS` (90), `PROVIDER_RETRY_SCHEDULE`, `PROVIDER_LOCAL_CA`, staff issuer settings; refusals naming the field; suite `E/test/.../edge/SettingsSuite.scala`
- [X] T019 `ProductSuite` in `E/test/.../edge/ProductSuite.scala`: no class under `io.eitheror.casino.simulator` on the classpath, no `scenarios/` resource, no endpoint at `/game` (FR-016); fails on any

## Phase 3: User Story 2 — a game service joins through one contract (P1)

**Goal**: a game registers, is launched, checks tickets, opens, settles and cancels rounds only
by asking the provider. **Independent test**: `features/games/contract.feature` bound against the
provider, `TestGame` and `FakeBrand`.

- [X] T020 [US2] `GameEntity` and `GameIndex` (data-model: Game) in `E/main/.../edge/registry/GameEntity.scala` and `GameIndex.scala`: register (refusing a code another owner holds, naming it), deregister, operator removal; entity suite `E/test/.../edge/registry/RegistrySuite.scala`
- [X] T021 [US2] `BrandEntryEntity`, `BrandIndex` and `BrandCache` (the core's `Brands` over entities, refreshed on write and at start; research R3) in `E/main/.../edge/brands/`; suite `E/test/.../edge/brands/BrandCacheSuite.scala` (US3 adds the routes; the test kit writes entries directly)
- [X] T022 [US2] `Main.scala` in `E/main/.../edge/Main.scala`: register the library's `SessionEntity`, `CallbackEntity`, `Deliver`, `KeyRotation`, `RunCallbacks`, `Sweep` and the edge's `GameEntity`, `GameIndex`, `BrandEntryEntity`, `BrandIndex`, `BonusIndex`; `Wiring.install(St8Protocol, Sender(cache, keys), keys, cache)`; `Keys.ensureCurrent()` at start; the sweep timer; the six endpoints; `contract/keys.md`'s table moved
- [X] T023 [US2] `GamesEndpoint` `/v1/games` registration routes (`PUT|DELETE /registry/{code}`; `contracts/game-contract.md`) in `E/main/.../edge/games/GamesEndpoint.scala`, admitted by `Routes.service()`, the owner taken from the caller's service principal
- [X] T024 [US2] `GamesEndpoint` session routes: `POST /sessions/{token}/check` (one signed `check` through `Play.send`, `SessionEntity.check` on `ok`, the session answered; refusal word on anything else; `403` for another game's session), `GET /sessions/{token}`
- [X] T025 [US2] `GamesEndpoint` round routes: `stake` (synchronous `Play.send` within the brand's wait; `pending` + `Deliver` past it or on a retryable answer; `demo` on a fun-mode session with nothing sent; `409` on a reused round id or an unchecked session), `settle` (always `Deliver`, `409` unless open), `cancel` (`Deliver`, new cancel id), `GET /callbacks/{id}`; the wire amounts rendered by the currency's exponent from `contract.money`
- [X] T026 [US2] Bind `features/games/contract.feature` in `E/test/.../edge/steps/ContractFeatures.scala` through `GherkinSuite` with `TestGame` and `FakeBrand` (late, error and silent answers scripted)
- [X] T027 [US2] Integration suite `E/test/.../edge/ProviderIntegrationSuite.scala` over Postgres (Docker): a brand silent for 15 minutes (clock advanced) then answering; every debit and credit acknowledged after, the balance moved once per callback (SC-004); a restart mid-`Deliver` resumes the schedule

## Phase 4: User Story 1 — a brand plays a house game exactly as an ST8 game (P1)

**Goal**: a brand with a second provider configuration lists, launches and plays a house game
and sees what it sees from ST8. **Independent test**: `OperatorApiParitySuite` and
`features/games/brand.feature`.

- [X] T028 [US1] `OperatorApi` `/api/operator/v1` in `E/main/.../edge/operator/OperatorApi.scala` (research R4, R5): find the brand by `site`, verify `x-st8-sign` with `Signer.verify` against the entry's key (`auth_failed`, nothing stored, on failure); `launch` stores the session with the registered game's code, `fun_mode`, the entry's language, nothing of `player_profile`, and answers `game_url = pageAddress?token=`; `unknown_game` for an unregistered code, a code outside the brand's games, or `fun_mode` on a game without demo; `{"status":"error","error":"unavailable"}` when the game's `/v1/game/health` fails within 2 s
- [X] T029 [US1] `OperatorApi` `games`: the listing in the provider's shape (`Catalogue` from the library over `GameEntity`), filtered to the brand's games, `fun_mode` per game, developers and categories in a fixed order as spec 020 R7 fixed them
- [X] T030 [US1] `KeyDocument` and `Health` endpoints wired from the library in `Main.scala`; `Health` ready once the key is held and the brand cache loaded
- [X] T031 [US1] `OperatorApiParitySuite` in `E/test/.../edge/port/OperatorApiParitySuite.scala`: replay `tests/port/simulator/OperatorApi_Launch.json`, `OperatorApi_Games.json`, `OperatorApi_BonusCreateUnknownGame.json`, `OperatorApi_BonusLifecycle.json` with `TestGame` registered and the recordings' key as a test brand's entry; the `game_url` host difference the one named exception (SC-001)
- [X] T032 [US1] Bind `features/games/brand.feature` in `E/test/.../edge/steps/BrandFeatures.scala`: `FakeBrand` as the brand (catalogue refresh, launch, wallet moves and the `round_settled` fact asserted on the fake's side), a launch signed with another key refused
- [X] T033 [US1] `just provider-perf`: 100 launches and checks against `FakeBrand` and `TestGame`, p99 under 2 s (SC-003), in `E/test/.../edge/PerfSuite.scala` behind a tag

## Phase 5: User Story 3 — one provider, many brands, each held apart (P1)

**Goal**: operators write the brand table; a launch is admitted only under its brand's key; nothing
crosses brands. **Independent test**: `features/games/brands.feature`.

- [X] T034 [US3] `ProviderEndpoint` `/v1/provider/brands` routes in `E/main/.../edge/admin/ProviderEndpoint.scala` (`contracts/provider-routes.md`, `contracts/brand-entry.md`): `GET /brands` (`installationOperatorHolding("games")`), `PUT /brands/{slug}` (`operatorHolding(slug, "games")`; malformed fields refused by name; `keyDocument` only with `testBrand`; a private key in the body refused), `GET /brands/{slug}` (`operator(slug)`); the cache refreshed on write
- [X] T035 [US3] Per-brand checks in `GamesEndpoint` and `OperatorApi`: a session answered only to its brand's game and callbacks; a launch for a brand the table does not hold refused; the games list filtered by the entry (FR-018)
- [X] T036 [US3] `SessionsByBrand` view and `GET /brands/{slug}/sessions`, `GET /brands/{slug}/rounds`, `GET /brands/{slug}/rounds/{token}` (`RunCallbacks`) in `E/main/.../edge/views/SessionsByBrand.scala` and `ProviderEndpoint.scala`, `operator(slug)`
- [X] T037 [US3] Bind `features/games/brands.feature` in `E/test/.../edge/steps/BrandsFeatures.scala`: two `FakeBrand`s, the `games` role versus a brand operator (SC-006 in the integration suite: 1,000 rounds each, no shared row)

## Phase 6: User Story 4 — keys and the three-act rotation (P2)

**Goal**: one key pair, a key document, rotation by an operator in three acts; a real-money brand
holds the key. **Independent test**: `features/games/keys.feature`.

- [X] T038 [US4] `ProviderEndpoint` `GET /keys`, `POST /keys/start|switch|withdraw` (`installationOperatorHolding("games")`) over the library's `KeyRotation` (`publishNext`, `promote`, `withdrawOld`); no timer anywhere switches a key (FR-020)
- [X] T039 [US4] The real-money rule in `OperatorApi.launch` and `BrandCache`: an entry with `realMoney` and `keyDocument` refuses every launch naming the entry; `PUT /brands/{slug}` with that pair is refused
- [X] T040 [US4] Bind `features/games/keys.feature` in `E/test/.../edge/steps/KeysFeatures.scala`: two `FakeBrand`s, one holding the key, one refreshing the document within its bound (SC-005)

## Phase 7: User Story 5 — free rounds reach a game as they reach ST8's (P2)

**Goal**: a brand's bonus is handed to the game, which holds the instances; a free round is a zero
`free_debit` then a credit. **Independent test**: `features/games/free-rounds.feature` and the
`OperatorApi_BonusLifecycle` recording.

- [X] T041 [US5] `BonusIndex` (data-model) in `E/main/.../edge/operator/BonusIndex.scala`; `OperatorApi` `bonus/create` (one game per bonus; `unknown_game` for an unregistered code; the brand verified; `POST /v1/game/bonus` on the game with `durationDays`; the game's envelope in the stand-in's shape), `bonus/get` and `bonus/cancel` routed by the index (research R9)
- [X] T042 [US5] `TestGame`'s bonus routes and its free round (a `free_debit` stake of amount 0 then a settle) through the contract; the claim deadline never earlier than `durationDays`; a round that settles after the deadline still sent (contract: claim deadline)
- [X] T043 [US5] Bind `features/games/free-rounds.feature` in `E/test/.../edge/steps/FreeRoundsFeatures.scala`

## Phase 8: User Story 6 — operators see what was sent and answered (P2)

**Goal**: per-brand reads, the unanswered list, abandon with a reason, the day export, audit
records, retention. **Independent test**: `features/games/operators.feature`.

- [X] T044 [US6] `UnansweredCalls` and `RoundsByDay` views in `E/main/.../edge/views/`; `GET /brands/{slug}/unanswered` (attempts, `nextRetryAt`), `GET /brands/{slug}/export/{day}` (JSON lines; the day in the installation's reporting zone) in `ProviderEndpoint.scala`
- [X] T045 [US6] `POST /brands/{slug}/abandon/{callbackId}` (`operatorHolding(slug, "games")`, body `{reason}`): `Deliver.abandon`, `CallbackEntity.Abandoned`, listed as abandoned with the operator and reason
- [X] T046 [US6] `Audit` publisher in `E/main/.../edge/admin/Audit.scala`: `platform.audit` records (data-model: Audit records) for brand entry writes, the three rotation acts, abandonments and game removals, the actor from the staff token, written by the endpoint after the command as `merchant` does; `DELETE /registry/{code}` and `GET /registry`
- [X] T047 [US6] `Sweep` on `PROVIDER_RETENTION_DAYS` wired in `Main.scala` (recurring timer; rounds, sessions and callbacks older than the retention gone, younger untouched); metrics `callbacks_sent{brand,kind}`, `callbacks_unanswered{brand}`, `launches{brand,game}` (FR-025) through the telemetry counters
- [X] T048 [US6] Bind `features/games/operators.feature` in `E/test/.../edge/steps/OperatorsFeatures.scala`, the audit records read from the test kit's topic

## Phase 9: User Story 7 — the Anjouan licence profile (P3)

**Goal**: an operator applies `anjouan` and gets Curaçao's values under that name. **Independent
test**: the new scenario in `features/kyc/policy.feature`.

- [X] T049 [US7] `Profile.Anjouan("ANJOUAN")` and `Profiles.Anjouan = Curacao.copy(profile = Profile.Anjouan)`, `byName += ANJOUAN` in `services/kyc/src/main/scala/io/eitheror/casino/kyc/policy/Policy.scala` and `Profiles.scala`; bind the scenario in kyc's policy features suite; the unknown-profile answer's known list updated in its suite; `just service kyc test`
- [X] T050 [P] [US7] Spec 009's text: FR for the profiles names Anjouan; *Changes from the original* gains the known-list divergence (`contracts/brand-amendments.md`)

## Phase 10: The brand side (spec 003's amendment) and the shared project

- [X] T051 `casino-providers` brand value (`st8:st8,eitheror:st8`) in `contract/keys.md`, `modules/casino/module.json` (env `CASINO_PROVIDERS`, key `casino-providers`) and `brands/goldvibe/values.json`; `just modules validate --all`
- [X] T052 `Settings`/`Context` in `services/game-provider/src/main/scala/io/eitheror/casino/gameprovider/`: `adapters = names.map(St8Adapter(_))` from `CASINO_PROVIDERS`, `ProviderPathEndpoint` per name, the root form for `st8` only; a configuration write for an unlisted name refused naming the value; the configuration read answers `operatorPublicKey` (`contracts/brand-amendments.md`); suites updated
- [X] T053 Bind the new scenario of `features/game-provider/launch.feature` ("a second game provider of the same protocol under its own name…") in game-provider's launch features suite; `just service game-provider test` green
- [X] T054 [P] Spec 003's text: FR-026 gains the `casino-providers` sentence; spec 001 FR-003 counts five shared projects; `specs/020-game-provider-simulator/spec.md` gains an amendment note that the core is the `provider-core` library (FR-005 here)
- [X] T055 [P] `installation/overlays/dev-kind/` gains the `games` project's hostnames and the provider's settings; `just modules apply shared/games/manifest.json --installation dev-kind` applies; quickstart step 4 run and its outcomes recorded in `quickstart.md` under a `## Run on dev-kind` heading

## Phase 11: Polish and cross-cutting

- [X] T056 `services/provider/README.md`: the components table, the six endpoints, how to test, how to run beside a brand locally, the brand entry, the rotation procedure; `CLAUDE.md`'s spec table gains 026 and the `games` project; `modules/README.md` names `games` as a shared project beside `tools`
- [X] T057 [P] The brain: `brain/projects/ankka-modules.md` gains the house provider and spec 026 under *Built*/*Decisions*; `brain/company/identity.md` *Stage* gains one line on the Anjouan licence and South American markets (`contracts/brand-amendments.md`)
- [X] T058 [P] `GLOSSARY.md`: the house provider section's terms re-read against the built routes; `just features` 0 findings
- [X] T059 [P] The client layout of `contracts/game-client.md` as repository plumbing with no client built: a `just service <game> client` recipe in `Justfile` (`npm ci && npm run build` in `services/<game>/client/` when the directory exists, a no-op otherwise), `services/*/src/main/resources/client/` in `.gitignore`, and the CI job snippet (`actions/setup-node@v4`, Node 22) documented in `.github/workflows/ci.yml` as a comment beside the provider job for 027 to copy
- [X] T060 Run the whole quickstart (steps 1 to 3, 5 and 6; step 4 as far as the brand side allows) and record the outcomes under `## Found during implementation` in `research.md`

## Dependencies

- Phase 1 → Phase 2 (T005 needs T001; T006 needs T005; T009 to T014 need T005; T018, T019 need T002).
- Phase 2 → every user story. T006 (the simulator green) gates everything in `services/provider`.
- US2 (Phase 3) → US1 (Phase 4): the parity suite and the brand features play rounds through the
  contract with `TestGame`. US1 → US3 (the brand table routes extend what US1's test kit wrote
  directly). US3 → US4, US6 (routes on `ProviderEndpoint`). US2 → US5 (`TestGame`'s bonus routes).
- US7 and Phase 10 depend on nothing in the provider; they can run any time after Phase 1 and are
  last only so the provider's suites are not waiting on other builds.
- Phase 11 last.

## Parallel examples

- Phase 2: T007, T008, T010, T011, T013, T015, T016, T017 in parallel after T005; T012 after T010.
- Phase 3: T020 and T021 in parallel; T023 to T025 after T022.
- Phase 10: T051 to T053 with US7's T049 in parallel in their own builds.

## Implementation strategy

MVP is Phases 1 to 4: the library, the simulator green on it, the contract and a brand playing a
`TestGame` round through the provider exactly as through the simulator (SC-001, SC-003, SC-004).
Phases 5 to 8 make it operable (brands, keys, free rounds, operators); Phases 9 and 10 make a real
brand able to configure it; Phase 11 writes it down. Spec 027 can start as soon as Phase 3 is
merged, against `TestGame`'s pattern.
