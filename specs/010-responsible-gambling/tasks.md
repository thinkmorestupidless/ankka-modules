# Tasks: responsible-gambling

**Input**: [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md); the spec's seven stories and 59 scenarios.

**Tests**: required. The constitution makes the recordings the acceptance test (I) and the plan names the
suites; parity tests are written before the components they test and fail first (ankka-port §7).

Paths: `S` = `services/responsible-gambling/src/main/scala/io/eitheror/casino/rg/`,
`T` = `services/responsible-gambling/src/test/scala/io/eitheror/casino/rg/`.

## Phase 1: Setup

- [ ] T001 Add `currency: Option[String] = None` to `RgLimitApplied` and `permanent: Option[Boolean] = None` to
      `RgCaseOpened` in `contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala`
      (contracts/facts.md); `just schemas`; the schema diff shows two optional fields only; `sbt contract/test`
      green
- [ ] T002 `ankka init` the service at `services/responsible-gambling/` in the shape of `services/players/`:
      `build.sbt` on `casino-contract` and ankka 0.13.0, `service.json`, `docker-compose.yml` (Postgres),
      `src/main/resources/application.conf`
- [ ] T003 [P] `S/Settings.scala` (brand, project, issuers from the env the core module renders) and
      `S/RgCodecs.scala` (the stored-type serializer helper as `PlayersCodecs`)
- [ ] T004 [P] `justfile`: `rg-test`, `rg-it`, `rg-run`; `.github/workflows/ci.yml`: the service's filter,
      output and job

## Phase 2: Foundational

- [ ] T005 [P] `S/profile/Limits.scala` (the limit types, `isMoreRestrictive` as the original's
      `CS/RgRoutes.scala:35-48`, the staging decision) and `T/profile/LimitsSuite.scala`: the original's truth
      table (`RgRoutesSpec:376-405`) plus each staging row of data-model.md
- [ ] T006 [P] `S/profile/Model.scala`: state, `RgCase`, `HistoryEntry`, `Actor`, events, commands, `Done`
      with refusals (data-model.md), `Personal` notes and reasons
- [ ] T007 [P] `S/profile/Checks.scala` (pure; data-model.md *Checks*, the four orders) and
      `T/profile/ChecksSuite.scala`: every row, exactly-at-remainder allowed, a minor unit over refused, the
      first of daily/weekly/monthly named, excluded over break, loss limit with no sums `unavailable`
- [ ] T008 `S/profile/ProfileEntity.scala` and `T/profile/ProfileEntitySuite.scala` (`EventSourcedTestKit`):
      every command and refusal of data-model.md's table in its order; stale timer fires change nothing;
      `Erase` blanks every `Personal` and keeps values, types, times and statuses; the history entry each
      change appends
- [ ] T009 [P] `S/settings/BrandSettingsEntity.scala` (key value, defaults 60 and 42) with its kit
- [ ] T010 [P] `S/usage/Windows.scala`, `S/usage/UsageEntity.scala` (exact deposits for 30 days, loss minute
      and hour buckets, seen ids) and `T/usage/UsageSuite.scala`: the three windows; more than 100 deposits in
      a window summed exactly (SC-007); a repeat transaction a no-op; state bounded
- [ ] T011 [P] `S/usage/SessionDayEntity.scala` and its kit: minutes used with an open session counted to
      now; a session started yesterday not counted today; whole minutes
- [ ] T012 `S/usage/UsageConsumer.scala` (`money.transactions`) and `S/usage/SessionMinutesConsumer.scala`
      (`casino.sessions`) with `ConsumerTestKit` suites; an unreadable message ignored and logged without
      its payload
- [ ] T013 `S/facts/RgPublisher.scala` and `S/facts/AuditPublisher.scala` (contracts/facts.md) with
      `T/facts/PublishersSuite.scala`: every event to its family or none; `causedBy` set / lowered / applied
      / removed; `permanent` on a self-exclusion; an operator event to one audit record with the note named,
      never its text
- [ ] T014 `S/clients/Players.scala` (trait + HTTP: `PUT`/`DELETE /v1/casino/brands/<slug>/players/{id}/exclusion`)
      and `S/timers/` (`PendingTimer`, `BreakTimer`, `ReturnTimer`, `SyncTimer` as timed actions, the names
      of data-model.md *Timers*)
- [ ] T015 `S/RgService.scala` (registration), `S/api/Answers.scala` (refusal bodies), and the integration
      kit `T/it/Kit.scala` with `FakePlayers`, `InMemoryBroker` for the four topics, `TimerRuntime(200.millis)`,
      a settable clock, player and operator tokens, a service identity per caller
- [ ] T016 `T/port/WireTranslation.scala` (every row of contracts/wire.md) and `T/port/ParitySuite.scala`
      over `tests/port/responsible-gambling/`, `.ignore`d until T060; `T/port/WireTranslationSuite.scala`

**Checkpoint**: the pure packages, the entity and the plumbing pass their kits.

## Phase 3: US1, a player sets, lowers, raises and removes a limit (P1)

- [ ] T017 [US1] Fail first: bind `features/responsible-gambling/limits.feature` in
      `T/features/LimitsFeatures.scala` (10 scenarios)
- [ ] T018 [US1] `S/api/PlayerEndpoint.scala`: `GET /{player}/limits` (the status, contracts/routes.md),
      `PUT`/`DELETE /{player}/limits/{type}`; `player_id_mismatch`; the currency checked against the player's
      wallets (FR-006); the brand minimum from `BrandSettingsEntity`; the pending timer scheduled or replaced
      by name
- [ ] T019 [US1] `GET /{player}/history` (FR-027, newest first, the original's `eventType` names for cases
      and new ones for limit changes)
- [ ] T020 [US1] `T/it/LimitsSuite.scala`: a raise applies 24 h later within a minute of applies-at (SC-003),
      a removal too; a new raise replaces the pending change and its timer; lowering cancels it; the loss limit
      shown with the week's losses

## Phase 4: US2, a deposit is refused before it is credited (P1)

- [ ] T021 [US2] Fail first: bind `features/responsible-gambling/deposits.feature` in
      `T/features/DepositsFeatures.scala` (9 scenarios; "no balance changes" and "captured, not credited" are
      the wallet's, asserted on the `may-deposit` answer here and named as 002's)
- [ ] T022 [US2] `S/api/ServiceEndpoint.scala`: `POST /may-deposit` for `wallet` and `players`, the answer
      shape the wallet reads (`decision`, `endsAt`, `period`, `limit`, `used`, `remaining`)
- [ ] T023 [US2] Run the wallet's own suite against this route's shape: `ResponsibleGambling.read` over each
      answer this route gives (a test in `T/it/CallersSuite.scala` decoding with the wallet's codec copied
      from `services/wallet/.../api/ResponsibleGambling.scala`)

## Phase 5: US3, play and login refused while a break or an exclusion stands (P1)

- [ ] T024 [US3] Fail first: bind `features/responsible-gambling/play.feature` in
      `T/features/PlayFeatures.scala` (9 scenarios; sign-in through `may-log-in`, launch through `may-play`,
      the bet through `may-bet`)
- [ ] T025 [US3] `ServiceEndpoint`: `POST /may-log-in` (players' shape), `GET /{player}/may-play`
      (game-provider's: 200 or 403 with `allowed`, `reason`), `POST /may-bet` (game-provider's and the
      wallet's decisions; loss sums optional, R6)
- [ ] T026 [US3] `CallersSuite`: each answer decoded with players' `Rg.logIn`, game-provider's
      `ResponsibleGamblingClient` reading and the wallet's `read` (copied decoders), so a shape drift fails here
- [ ] T027 [US3] `T/it/ChecksLatencySuite.scala`: 1,000 asks of each check, p99 under 100 ms (SC-004), no
      outbound call (`FakePlayers` receives nothing)

## Phase 6: US4, breaks and exclusions (P1)

- [ ] T028 [US4] Fail first: bind `features/responsible-gambling/breaks.feature` in
      `T/features/BreaksFeatures.scala` (13 scenarios)
- [ ] T029 [US4] `PlayerEndpoint`: `PUT /{player}/cooling-off` (bounds, `break_active`), `PUT
      /{player}/permanent-exclusion`, `POST /{player}/return`; the break and return timers; the sync timer
      started on an exclusion and a reinstatement
- [ ] T030 [US4] `S/api/OperatorEndpoint.scala`: `PUT`/`DELETE /{player}/cooling-off` (a reason, the case
      `lifted`), `POST /{player}/self-exclude` (`parseDays` as the original, `invalid_period`,
      `period_too_short`), `POST /{player}/return`
- [ ] T031 [US4] `T/it/ExclusionSuite.scala`: a self-exclusion tells `FakePlayers` `PUT …/exclusion`; with
      `FakePlayers` silent the profile still refuses every check and the sync retries until it answers; a
      return after the period reinstates 24 h after the request and tells `players` `DELETE`; a break ending
      on a player `players` holds suspended changes nothing there
- [ ] T032 [US4] `T/it/TimersSuite.scala`: a break ends within a minute of its end and its case closes
      `expired` (SC-003); a lifted break's timer fires and changes nothing; a reinstated player is allowed by
      `may-log-in` at the next request (SC-005)

## Phase 7: US5, reality checks (P2)

- [ ] T033 [US5] Bind `features/responsible-gambling/reality-checks.feature` in
      `T/features/RealityChecksFeatures.scala` (5 scenarios)
- [ ] T034 [US5] `PlayerEndpoint`: `PUT`/`DELETE /{player}/reality-check`, `POST
      /{player}/reality-check/acknowledge` (the session from `SessionDayEntity`, `session_not_found`)

## Phase 8: US6, the operator (P2)

- [ ] T035 [US6] Bind `features/responsible-gambling/operators.feature` in
      `T/features/OperatorsFeatures.scala` (6 scenarios)
- [ ] T036 [US6] `OperatorEndpoint`: `GET /{player}` (the full state), `PUT`/`DELETE /{player}/limits/{type}`,
      `POST /{player}/cases`, `/cases/{caseId}/notes`, `/cases/{caseId}/close`, `GET`/`PUT /settings`
- [ ] T037 [US6] `T/it/OperatorSuite.scala`: every operator action in the history at once and in
      `platform.audit` within 5 s (SC-006) with the operator and before and after; another brand's operator
      `403`; a note never in a log or the audit

## Phase 9: US7, the facts (P2)

- [ ] T038 [US7] Bind `features/responsible-gambling/facts.feature` in `T/features/FactsFeatures.scala`
      (6 scenarios; "engagement sends nothing but the confirmation" is 014's, asserted on the fact here)
- [ ] T039 [US7] `ServiceEndpoint`: `POST /gamstop-refusal` by player id (R12); `POST
      /erasure/players/{player}` and `GET /exports/players/{player}` for `players` (R15)
- [ ] T040 [US7] `T/it/LeakSuite.scala`: after `Erase`, no note or reason in any state, fact, audit record or
      log line; values, dates, types and statuses unchanged

## Phase 10: The recordings and parity

- [ ] T041 The recording run (needs the user; research R1, quickstart §3): apply `e2e-recording.patch` and
      `e2e-rg-kyc.patch` to a local checkout of the original, run scenarios 26 and 27 against the local
      stack, copy the recordings to `tests/port/responsible-gambling/`; `tests/original/README.md` names the run
- [ ] T042 Un-ignore `ParitySuite`; every recording replays through `WireTranslation`; each marked divergence
      in `Parity.divergences` matches plan.md's *Divergences*; `CoverageSuite` names any unhit

## Phase 11: Polish and cross-cutting

- [ ] T043 [P] `services/responsible-gambling/README.md` (purpose, routes by endpoint with a pointer to
      contracts/routes.md, settings, the `just` recipes, what is verified where)
- [ ] T044 [P] On 008's branch (`008-players`): the GAMSTOP report resolves the address to the player and
      sends `playerId` (R12); `ErasureWorkflow.targets` gains `responsible-gambling` (R15); its suites green
- [ ] T045 Quickstart §6: the service run locally, the four checks answered by `curl`
- [ ] T046 Quickstart §7 on `kind-ankka` (never production): the image built and loaded, `just modules apply
      goldvibe --installation dev-kind --modules core`; 008's sign-in re-run ends in `player_logged_in`; a
      break set here refuses the next sign-in `cooling_off`
- [ ] T047 Close research: *Findings* with dates; R6's answer from the user recorded; the cross-spec
      follow-ups (002 loss sums, 003 carrying them, 014, 015) written; `just features` green; `just rg-test`
      and `just rg-it` green

## Dependencies & Execution Order

- Setup (T001–T004) → Foundational (T005–T016) → the stories.
- US1 → US2 and US3 (the checks read limits); US4 after US3 (the checks read breaks); US5, US6, US7 after
  US1 and US4, in any order.
- T041 needs the user and the original's kind stack; T042 after T041. Nothing else waits on it.
- T044 is on another branch and independent; T046 after T043–T045 and 008's merge order is the user's.

## Parallel examples

- Foundational: T005, T006, T007, T009, T010, T011 together (different files).
- After US1: US5's and US6's bindings together.

## Implementation strategy

MVP: Phases 1–6 (US1–US4), which is every gate the brand needs: with it, 008's sign-in on kind passes and
the wallet's deposits and game-provider's launches and bets get real answers. US5–US7 follow; the
recordings (T041) when the user can run the original.
