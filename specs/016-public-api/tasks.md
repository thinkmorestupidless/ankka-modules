# Tasks: public-api

**Input**: Design documents from `specs/016-public-api/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md,
contracts/{amendments,routes,fields,facts,recording}.md, contracts/original/public-api-v1.openapi.yaml,
quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test (I)
and the living features the scenarios (VII). Every story opens with its tests (kit suites, feature
bindings over the `FactSet`, integration suites), each written first and failing first. The parity
tests come last, with the recordings.

**Organization**: By user story. US1 (keys) is the MVP: nothing is reachable without a key. US2–US4
are the rest of P1; US5–US8 are P2. The contract library and the four amended producers come first,
because the intake reads their new fields.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US8 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `p/` stands for `services/public-api/src/main/scala/io/eitheror/casino/publicapi/`
- `p-test/` stands for `services/public-api/src/test/scala/io/eitheror/casino/publicapi/`
- `f/` stands for `contract/src/main/scala/io/eitheror/casino/contract/facts/`
- `O/` is the original's `casino/public-api/` in `~/Documents/projects/eitheror/eitheror-platform`
- sbt runs one process per service directory; the contract is published with
  `CASINO_VERSION=0.2.0-016-SNAPSHOT`
- A recording is made only against a local original on kind; kubectl always with `--context kind-…`

---

## Phase 1: Setup

**Purpose**: the contract library's additions, the realm role, the module, the service's build, the
recipes.

- [X] T001 Add contracts/facts.md G1–G7 to the fact families: `language: Option[String] = None` and
  `stateProvince: Option[Personal[String]] = None` on `PlayerRegistered` and `PlayerProfileUpdated` in
  `f/CasinoPlayers.scala`; the family `RoundOpened` (`round_opened`, keyed `playerId/currency`), and
  `openedAt`, `realStakeCents`, `realWinCents` on `RoundSettled` in `f/Others.scala`; `gameRoundId`
  and `sessionId` on `BetPlaced`, `WinCredited`, `BetRolledBack` and `RoundSettledAsLoss` in
  `f/MoneyTransactions.scala`; `expiresAt` on `BonusGranted` and `FreeSpinsGranted`, and
  `amountCents`, `currency` on `FreeSpinsGranted` in `f/Others.scala`. Add cases in
  `contract/src/test/scala/io/eitheror/casino/contract/facts/` (round trip; a fact of the old shape
  decodes; `Compatibility` passes)
- [X] T002 Regenerate `contract/schemas/` with `just schemas`; `just test` green
- [X] T003 [P] In `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`, remove
  `partner` and `Refusal.NotPartner`, and add `AccessKeysRole = "api_keys"`; update
  `contract/src/test/scala/.../access/` suites (an operator of the brand holding `api_keys` is
  admitted by `operatorHolding(slug, AccessKeysRole)`; without it, `forbidden`; another brand's,
  `brand_not_in_roles`)
- [X] T004 [P] Add the realm role `api_keys` to `brands/_realm/eitheror-operators.json` beside
  `markets` (the file holds role names only, as `markets` has)
- [X] T005 [P] Rename spec 021's module `partner` to `public-api`: create `modules/public-api/module.json`
  (kind `product`, depends on `core` and `casino`, service `public-api`, runtime `0.16.0`,
  `"expose": true`, `"personal": true`, env `CASINO_PROJECT`, `CASINO_BRAND`, `ANKKA_AUTH_ISSUERS=staff`
  and the staff issuer keys as `modules/reporting/module.json` has them, the hostname key
  `public-api-host`, the erasure route row, the topics read in contracts/facts.md); update
  `modules/README.md` and `tools/modules/src/test/scala/.../RenderSuite.scala` (goldvibe entitled to
  `public-api` renders it; unentitled, not); `just modules validate --all` green
- [X] T006 [P] Amend spec 001 (`specs/001-brands-and-projects/spec.md`: FR-020's partner class becomes
  the public class admitted by an access key, the pilot exclusion of `partner-api` removed, the topic
  table's reader `partner-api` → `public-api`), spec 021 (`specs/021-core-and-modules/spec.md`: the
  module list), `CLAUDE.md`'s spec table (016 → `public-api`), and add a dated line to the brain's
  `../brain/projects/ankka-modules.md`
- [X] T007 Create `services/public-api/` from `services/reporting/`'s template with ankka **0.16.0**
  and its eviction lines (research R1): `build.sbt` (adding `jackson-dataformat-yaml` at ankka's
  Jackson version and `com.networknt:json-schema-validator`; `io.swagger.parser.v3:swagger-parser` in
  `Test`), `project/`, `service.json` (runtime `0.16.0`, `"personal": true`), `docker-compose.yml`
  (postgres:17-alpine on 5446), `README.md` stub, `p/PublicApiService.scala` registering nothing yet
- [X] T008 [P] Write `p/Settings.scala` (`CASINO_BRAND`, `CASINO_PROJECT`, `PUBLIC_API_HOST`, the
  request budget `5s`, the catalogue cache `5m`) with `p-test/SettingsSuite.scala`
- [X] T009 [P] Add the recipes `public-api-test`, `public-api-it`, `public-api-perf`,
  `public-api-run`, `public-api-record`, `public-api-parity` to `justfile`, and the service to
  `.github/workflows/ci.yml` as `services/reporting` is

**Checkpoint**: the contract publishes, the module renders, an empty service builds.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the producers' amendments, the contract served, the refusal shapes, the deadline, the
codecs, the test kit. Every story needs them.

### The producers (contracts/facts.md)

- [X] T010 [P] `services/players/`: send `language` and `stateProvince` (as `Personal` under
  `player/<id>`) on `player_registered` and `player_profile_updated` from the profile
  (`services/players/src/main/scala/.../facts/Facts.scala:47-50, 88-92`); one test per field in its
  facts suite
- [X] T011 [P] `services/game-provider/`: publish `round_opened` from the round entity's
  `RoundOpened` event (`facts/RoundFacts.scala:16-19`), and `openedAt`, `realStakeCents` (the wallet
  answers' `realLeg` for the round's debits, contracts/facts.md G7) and
  `realWinCents` (likewise for its credits) on `round_settled`; tests in its facts and rounds suites
- [X] T012 [P] `services/game-provider/`: pass the round's id and the game session's id with each
  money move (`rounds/MoneyMoves.scala:60`), so the wallet can put them on its facts; test in its
  rounds suite
- [X] T013 `services/wallet/`: carry `gameRoundId` and `sessionId` from the command onto
  `bet_placed`, `win_credited`, `bet_rolled_back` and `round_settled_as_loss` (the command already
  holds `sessionId`, `entity/Commands.scala:61`); `gameRoundId` is the round's `roundId` exactly
  (contracts/facts.md G4); tests in its facts suite (depends on T012 for the
  values; the fields are optional, so the order does not block)
- [X] T014 [P] `services/rewards/`: `expiresAt` on `bonus_granted` and `free_spins_granted`; on
  `free_spins_granted`, `amountCents` and `currency` as research R16.5 settles (spin value × rounds,
  else `0` in the reward's currency); tests in its facts suite
- [X] T015 [P] `services/game-provider/`: add `GET /v1/casino/brands/{brand}/game-provider/games` to
  `BrandsEndpoint` under `Routes.serviceOf("rewards", "public-api")`, answering
  `CatalogueReads.toJson` for `Enablement.listed` games with `removedFromFeed` and `releaseDate`
  (contracts/facts.md G8); tests: `public-api` admitted, `engagement` refused, a disabled game absent

### The service's foundation

- [X] T016 Copy `specs/016-public-api/contracts/original/public-api-v1.openapi.yaml` to
  `services/public-api/src/main/resources/public-api-v1.openapi.yaml` and apply exactly the changes of
  contracts/amendments.md (title, version `1.1.0`, the description's Authentication, Scope and Errors
  sections, `servers`, `bearerAuth`, the `Error` enum, the four response descriptions, the
  "partner's brand" and "grant entitles" wording); copy `O/src/contract/reference.html` to
  `services/public-api/src/main/resources/reference.html`
- [X] T017 Write `p/contract/Contract.scala`: load the YAML with `jackson-dataformat-yaml`, render
  the JSON once, read `components.parameters.limit.schema.maximum` and refuse to start unless it is
  100 (`O/src/contract/load.ts:7-32`); the operations list (method, template, operationId) read from
  `paths`; with `p-test/contract/ContractLoadSuite.scala`
- [X] T018 [P] Write `p/contract/Refusals.scala`: one constructor per code of the served `Error`
  enum with the original's status (contracts/amendments.md's table, contracts/routes.md), each a
  `Respond(Bytes("application/json", {"error", "message"?}), status, headers)`, `Retry-After` for
  `quota_exceeded`; with `p-test/contract/RefusalsSuite.scala`
- [X] T019 [P] Write `p/wire/Money.scala` (minor units → two-decimal string by the currency's
  exponent from `contract.money`; an exponent other than 2 is a projection error) and
  `p/wire/Times.scala` (ISO 8601 with `Z`); port `O/test/money.test.ts` as `p-test/wire/MoneySuite.scala`
- [X] T020 [P] Write `p/wire/Enums.scala` from contracts/fields.md §2 (player status, KYC,
  transaction type and wallet type per family, session status, round status, bonus status; an
  unmapped word → `None`, served as null); port `O/test/enums.test.ts` as `p-test/wire/EnumsSuite.scala`
  over the fact words here
- [X] T021 [P] Write `p/records/FactId.scala` (copied from `services/reporting/.../intake/FactId.scala`
  with a test that both give the same id) and `p/PublicApiCodecs.scala` (serializers for every stored
  type, as `services/wallet`'s `WalletCodecs`)
- [X] T022 Write `p/api/Deadline.scala`: run a handler under the settings' budget and answer
  `unavailable` on expiry or on a view read's failure (research R11); with
  `p-test/api/DeadlineSuite.scala` (a handler that sleeps past the budget is `503 unavailable`)
- [X] T023 Write `p/api/V1Endpoint.scala` skeleton: one endpoint on `/v1` with `Acl.AllowAll`, each
  of contracts/routes.md's paths declared for GET and answering `405 invalid_request` "Only GET is
  supported" for POST, PUT, PATCH and DELETE; `/v1/openapi.json` and `/v1/docs` served from
  `Contract`; every other path answering `501` until its story; and `p/api/HealthEndpoint.scala` on
  `/health`. Register both in `p/PublicApiService.scala`
- [ ] T024 Write the test kit: `p-test/it/Kit.scala` (`AnkkaTestKit` over Postgres,
  `InMemoryBroker`, `MovableClock`, the service with a fake `game-provider` catalogue route),
  `p-test/it/FactSet.scala` (two brands' players, sessions, rounds, bets, transactions, withdrawals,
  rewards with known figures; FR-039), and `p-test/it/Keys.scala` (keys of every kind: no fields,
  email only, all fields, disabled, expired, deleted)
- [X] T025 Write `p-test/features/GherkinSuite.scala` binding `features/public-api/` over the kit,
  every scenario pending until its story binds it

**Checkpoint**: `just public-api-test` runs; `/v1/openapi.json` answers; every scenario is pending.

---

## Phase 3: User Story 1 - An operator creates a key and a caller reads with it (P1) 🎯 MVP

**Goal**: keys created, shown once, listed, disabled, enabled, changed, deleted, expired and audited;
a request admitted or refused by its key.

**Independent test**: create a key through `/access-keys/goldvibe`, read a player with it, disable,
read, enable, delete, read; list the audit records.

### Tests for User Story 1

- [X] T026 [P] [US1] `p-test/keys/AccessKeySuite.scala` (entity kit): create, change, disable,
  enable, delete; a command on a deleted key is `key_deleted` except `delete` and `disable`; `admit`'s
  order of data-model.md (wrong digest `key_invalid` even when disabled or expired; expired before
  disabled); no event carries the key; only `KeyCreated` carries the digest
- [X] T027 [P] [US1] `p-test/keys/RawKeysSuite.scala`: the format `ak_<16 base32>_<43 base64url>`;
  parsing rejects every other shape without a lookup; 1,000 keys are distinct
- [X] T028 [P] [US1] `p-test/it/KeysSuite.scala`: every route of contracts/routes.md's
  `/access-keys` table, its validation (`400 invalid_request` with `field`) and admission (an operator
  without `api_keys`, or of another brand, refused); the secret only in the `201`
- [X] T029 [P] [US1] `p-test/it/AdmissionSuite.scala`: every refusal of contracts/routes.md's
  pipeline steps 2–3 with its status and code; `KeyChangeSuite` in the same file's package: a
  disable, a delete, a field change and a rate change each apply from the next request
- [X] T030 [US1] Bind `features/public-api/keys.feature` in `GherkinSuite`

### Implementation for User Story 1

- [X] T031 [P] [US1] Write `p/keys/RawKeys.scala` (mint with `SecureRandom`, parse, SHA-256 digest,
  constant-time compare), as `services/merchant/.../keys/Keys.scala`
- [X] T032 [US1] Write `p/keys/AccessKey.scala`: the event sourced entity of data-model.md with its
  commands (`create`, `change`, `disable`, `enable`, `delete`) and the query `admit(digest, now)`
- [X] T033 [P] [US1] Write `p/keys/AccessKeys.scala`: the view over `AccessKey` (the `Key` shape,
  deleted keys flagged)
- [X] T034 [US1] Write `p/api/KeysEndpoint.scala` on `/access-keys`:
  `Routes.operatorHolding(slug, AccessKeysRole)`, `Routes.brandSegment`, the seven routes of
  contracts/routes.md, the operator's subject as `by`
- [X] T035 [US1] Write `p/api/Admission.scala`: parse the header, call `AccessKey.admit`, answer the
  refusal or the key's fields and rate; wire it into `V1Endpoint` before every keyed route; serve
  `/v1/contract` (`{version: "v1", maxPageSize: 100}`) as the first keyed route

**Checkpoint**: US1's suites and scenarios pass. A key reads `/v1/contract`.

---

## Phase 4: User Story 2 - A caller reads only its own brand (P1)

**Goal**: isolation, the pipeline's order, no write, malformed ids refused.

**Independent test**: two brands in two service instances; walk every route with the other's ids;
send each untrusted key; send a write.

### Tests for User Story 2

- [ ] T036 [P] [US2] `p-test/it/IsolationSuite.scala` (port of `O/test/isolation.test.ts`): two
  instances on two databases, the same shapes of facts, every route walked with the other brand's ids
  answering `404 not_found` identical to an absent id
- [ ] T037 [P] [US2] `p-test/it/PipelineSuite.scala` (port of `O/test/requestPipeline.test.ts`):
  the order of refusals (public route, key, admission, rate, path id, parameters, handler); nothing
  from a handler leaks into a refusal; `/health` and `/v1/docs` need no key; an unknown path is 401
  without a key and 404 with one
- [ ] T038 [US2] Bind `features/public-api/access.feature`

### Implementation for User Story 2

- [ ] T039 [US2] Write `p/api/Pipeline.scala`: contracts/routes.md's steps 1–5 as one function the
  `V1Endpoint` routes run through, with the path id checks (`Uuid`, `GameCode`; lower-casing UUIDs,
  `O/src/rest/router.ts:148-151`) and `400 invalid_request` "`<name>` is not a valid identifier"
- [ ] T040 [US2] Write `p/wire/Paging.scala`: `limit` 1–100, default 100, `limit_exceeded` above;
  `offset ≥ 0`; the envelope `{data, page: {limit, offset, total, hasMore}}` with `hasMore = offset +
  limit < total` (`O/src/rest/paging.ts:31-46`); with `p-test/wire/PagingSuite.scala`

**Checkpoint**: US2's suites pass on the routes US3 serves (they are written against all 22; each
route's case turns green as its story lands).

---

## Phase 5: User Story 3 - A caller reads a player's activity page by page (P1)

**Goal**: players and their sessions, rounds, bets, transactions and bonus awards, by id and as
collections, from the brand's facts.

**Independent test**: a player with 250 transactions paged at 100 while more arrive; 101; past the
end; an empty collection.

### Tests for User Story 3

- [ ] T041 [P] [US3] `p-test/records/IntakeSuite.scala` (consumer and entity kits): each fact family
  of contracts/facts.md's *Read* table changes its record as data-model.md says; a replayed fact
  changes nothing; a bet's later facts find it by the wallet round key; a bet's `roundId` equals its round's id
  (G4); `withdrawal_completed`
  completes its WITHDRAWAL row
- [ ] T042 [P] [US3] `p-test/wire/MappersSuite.scala`: each record → the contract's shape per
  contracts/fields.md (ids by §6, money, enums, parents' ids, optional fields omitted, never null)
- [ ] T043 [P] [US3] `p-test/it/PlayersSuite.scala`, `CollectionsSuite.scala`, `ByIdSuite.scala`,
  `BoundariesSuite.scala`, `PagingSuite.scala` (ports of `O/test/routes/players.test.ts`,
  `playersClosed.test.ts`, `playerCollections.test.ts`, `byId.test.ts`, `O/test/boundaries.test.ts`,
  `O/test/paging.test.ts`), plus the tie-break by id and paging while facts arrive
- [ ] T044 [P] [US3] `p-test/it/ProjectionRefusalSuite.scala` (port of `droppedRecords.test.ts`): a
  fact whose currency exponent is not 2, or whose record lacks a required field, is refused at
  projection, logged once without personal data, and its record is 404 by id
- [ ] T045 [US3] Bind `features/public-api/activity.feature`

### Implementation for User Story 3

- [ ] T046 [P] [US3] Write `p/records/PlayerRecord.scala` and its view `Players` (data-model.md;
  identifying fields as `Personal`)
- [ ] T047 [P] [US3] Write `p/records/SessionRecord.scala` and `Sessions`
- [ ] T048 [P] [US3] Write `p/records/RoundRecord.scala` and `Rounds` (id `uuid3("round/" +
  roundId)`; `round_opened` then `round_settled`; `startedAt`'s fallbacks)
- [ ] T049 [P] [US3] Write `p/records/BetRecord.scala`, `BetsByRoundKey.scala` and `Bets`
- [ ] T050 [P] [US3] Write `p/records/TransactionRecord.scala`, `WithdrawalsByRequest.scala` and
  `Transactions` (§2's family table; secondary rows' ids by §6; the four unserved families skipped)
- [ ] T051 [P] [US3] Write `p/records/BonusAwardRecord.scala` and `BonusAwards` (grant and end
  families; requirement and progress from `money.transactions`)
- [ ] T052 [US3] Write the six intake consumers in `p/records/Intake.scala` (`StartFrom.Earliest`,
  `Lenient`, `FactId` dedup) and register them
- [ ] T053 [US3] Create the views' expression indexes at start in `p/records/Indexes.scala`
  (data-model.md's paging table); verify research R16.1 and, if the service cannot create them, use
  declared queries with `ORDER BY` and `OFFSET` instead, recording which in research.md
- [ ] T054 [US3] Write `p/wire/Records.scala` (the mappers of T042) and serve the 13 record routes in
  `V1Endpoint` through `Pipeline`, reading the parent first

**Checkpoint**: US2's and US3's suites and scenarios pass.

---

## Phase 6: User Story 4 - Only the identifying fields a key allows (P1)

**Goal**: identifying fields per key; absent otherwise; absent after erasure; never in diagnostics.

**Independent test**: one player read with three keys; a field withdrawn; the player erased.

### Tests for User Story 4

- [ ] T055 [P] [US4] `p-test/it/IdentifyingFieldsSuite.scala` (port of
  `O/test/entitlements.test.ts`, without the snake_case fallbacks): none, some, all; withdrawn from
  the next request; absent, not null
- [ ] T056 [P] [US4] `p-test/it/ErasureSuite.scala`: after the erasure route, every identifying field
  is absent with every key, within ankka 042's bound; id, status and amounts remain; a page across the
  player stays whole
- [ ] T057 [P] [US4] `p-test/it/DiagnosticsSuite.scala` (ports of `O/test/no-pii-leak.test.ts` and
  `logger.test.ts`): a full walk and a ranking walk with an all-fields key, then a scan of the
  captured logs and spans for every seeded identifying value, every key and every digest
- [ ] T058 [US4] Bind `features/public-api/identifying-fields.feature`

### Implementation for User Story 4

- [ ] T059 [US4] Write `p/wire/Fields.scala`: copy into the player's answer only the fields the
  admitted key allows whose value is `Personal.Present`; use it in `Records`
- [ ] T060 [US4] Write `p/api/Erasure.scala` and serve it from the `/v1` endpoint (`POST
  /casino/brands/{brand}/public-api/erasure/players/{player}` under its own
  `withAcl(Routes.playersOnly)`; research *Found during implementation*) with ankka 042's erasure
  handler for the `Players` view's subject, as `services/reporting` does

**Checkpoint**: US1–US4 pass: the P1 surface is complete.

---

## Phase 7: User Story 8 - Every read is recorded; each key's limits are kept (P2)

Built before US5–US7 because every later route is audited and rate-limited.

**Goal**: an audit record per request and per player served; key changes audited; the rate; the
budget.

**Independent test**: serve a player's email; list the audit by the player; drive one key past its
rate while another reads.

### Tests for User Story 8

- [ ] T061 [P] [US8] `p-test/keys/KeyWindowSuite.scala` (entity kit with `MovableClock`; port of
  `O/test/quota.test.ts`): windows, `Retry-After`, `lastUsedAt`
- [ ] T062 [P] [US8] `p-test/it/QuotaSuite.scala`: two service instances on one database share a
  key's count; a second key's p95 latency with the first at its full rate is within 10% of its p95
  without it (SC-006)
- [ ] T063 [P] [US8] `p-test/audit/AuditSuite.scala` and `KeyAuditSuite.scala` (port of
  `O/test/audit.test.ts`): contracts/facts.md's *Published* table record by record; no value, key or
  digest in any record; a refusal with a key named is recorded; one without is not; each request
  record carries status, outcome and duration, so a key's volume, error rate and latency are
  answered by spec 018's query by actor (FR-038)
- [ ] T064 [P] [US8] `p-test/it/BudgetSuite.scala`: a request whose view read is held past five
  seconds answers `503 unavailable`
- [ ] T065 [US8] Bind `features/public-api/audit.feature` (the subject access scenario reads the
  published records by `player/<id>`, as spec 018's query would)

### Implementation for User Story 8

- [ ] T066 [P] [US8] Write `p/keys/KeyWindow.scala` and the view `KeyUse`; add `lastUsedAt` to the
  key routes' answers
- [ ] T067 [P] [US8] Write `p/keys/KeyLog.scala` (event sourced, snapshots every 500 events)
- [ ] T068 [US8] Write `p/audit/PublicApiAudit.scala`: an `AuditConsumer` over `AccessKey` and
  `KeyLog` events publishing contracts/facts.md's records on `platform.audit`
- [ ] T069 [US8] Wire step 4 (`KeyWindow.take`) and step 6 (`KeyLog.served` after the answer, with
  the players and field names `Fields` served) into `Pipeline`

**Checkpoint**: every route so far is rate-limited and audited.

---

## Phase 8: User Story 5 - Games and game statistics (P2)

**Goal**: the catalogue within five minutes; game figures hourly; rankings; one game's figures.

**Independent test**: a catalogue with a disabled and a future game; rounds with known stakes; one
run; rankings and one game's figures against a hand count.

### Tests for User Story 5

- [ ] T070 [P] [US5] `p-test/games/CatalogueSuite.scala` and `CatalogueCacheSuite.scala` (ports of
  `O/test/games/catalogue.test.ts`, `ttlCache.test.ts`): availability with the release gate; one load
  at a time; a failed load not cached; stale while revalidating
- [ ] T071 [P] [US5] `p-test/stats/StatsRunSuite.scala` (pure): data-model.md's run over a fixed set
  of rows against a hand count: cancelled rounds nowhere, free spins without money, bonus-funded money
  excluded through `realStakeCents`, per currency, ranks with ties by id, zeros for a quiet game
- [ ] T072 [P] [US5] `p-test/it/GamesSuite.scala` and `GameStatsSuite.scala` (ports of
  `O/test/routes/games.test.ts`, `gameStats.test.ts`, `gameStatsEmpty.test.ts`): the routes, a money
  sort without a currency, before the first run (empty, no `computedAt`)
- [ ] T073 [US5] Bind `features/public-api/games.feature`

### Implementation for User Story 5

- [ ] T074 [P] [US5] Write `p/games/Catalogue.scala`: the client of T015's route, the release gate,
  the five-minute cache (research R9), the mapper to the contract's `Game` with the original's tag
  union (`O/src/mappers/game.ts:58-103`)
- [ ] T075 [P] [US5] Write `p/stats/StatsRun.scala`: the pure fold and ranking of data-model.md
- [ ] T076 [US5] Write `p/stats/StatsPointer.scala`, `GameFigures.scala` with `GameFiguresView`, and
  `p/stats/ComputeStats.scala` (the hourly timer: stream the views, fold, write the non-current slot,
  mark it ready, make it current; the stale warning) with game figures only
- [ ] T077 [US5] Serve `/v1/games`, `/v1/games/{gameId}`, `/v1/games/{gameId}/stats` and
  `/v1/game-stats` in `V1Endpoint`

**Checkpoint**: US5's suites and scenarios pass.

---

## Phase 9: User Story 6 - Player rankings through snapshots (P2)

**Goal**: player figures in the same run; rankings paged through one snapshot; retired snapshots
refused; status filters keeping ranks.

**Independent test**: 250 players with known GGR; the ranking paged across a new snapshot; a retired
snapshot.

### Tests for User Story 6

- [ ] T078 [P] [US6] `p-test/stats/PlayerFiguresSuite.scala` (port of
  `O/test/playerStatsMapper.test.ts`): per player per window; deposits and completed withdrawals; a
  reversed withdrawal not counted; `lastActiveAt`
- [ ] T079 [P] [US6] `p-test/it/PlayerRankingSuite.scala` and `PlayerStatsSuite.scala` (ports of
  `O/test/routes/playerRanking.test.ts`, `playerStats.test.ts`): snapshots, slot flips, 410, the
  status filter, validation, zeros, another brand's player 404, no identifying field
- [ ] T080 [P] [US6] `p-test/it/RankingCrawlSuite.scala` (port of `playerRankingCrawl.test.ts`;
  SC-007): 1,000 randomised crawls spanning runs, each complete or ended by `410`, never a duplicate
  or a gap
- [ ] T081 [US6] Bind `features/public-api/player-stats.feature`

### Implementation for User Story 6

- [ ] T082 [US6] Add player figures to `StatsRun` and write `p/stats/PlayerFigures.scala` with
  `PlayerFiguresView`; extend `ComputeStats` to write them, and to delete the slot's rows of an older
  snapshot once the new ones are written
- [ ] T083 [US6] Serve `/v1/player-stats` (snapshot resolution of research R8, `status` filter,
  paging) and `/v1/players/{playerId}/stats` in `V1Endpoint`
- [ ] T084 [P] [US6] Write `p/api/StatusEndpoint.scala` on `/public-api` (`GET
  /public-api/{brand}/status` under `Routes.operator`): `statsComputedAt`, `statsStale`, the snapshot,
  each view's last applied time; with a kit case for `stale` past three hours

**Checkpoint**: all 22 operations are served.

---

## Phase 10: User Story 7 - The published, stable contract (P2)

**Goal**: the drift check: the file served, amended only as allowed, every answer valid against it.

**Independent test**: fetch the contract; validate every route's answers; change a field and build.

- [ ] T085 [P] [US7] `p-test/contract/ContractSnapshotSuite.scala` (port of
  `O/test/contract/snapshot.test.ts`): `/v1/openapi.json` deep-equals the resource YAML; the
  endpoint's routes equal the contract's 22 operations; the limit's maximum equals the paging cap
- [ ] T086 [P] [US7] `p-test/contract/ContractAmendmentSuite.scala`: the served YAML and
  `specs/016-public-api/contracts/original/public-api-v1.openapi.yaml` differ only at
  contracts/amendments.md's paths (descriptions and summaries matched by key name); any other
  difference fails, naming the path
- [ ] T087 [P] [US7] `p-test/contract/ContractResponsesSuite.scala` (ports of
  `O/test/contract/responses.test.ts` and `validator.test.ts`): every route at every status the
  contract documents for it, each body validated with `json-schema-validator` against its schema;
  the validator itself rejects an extra property, a bad money string, a bad time and an unmapped enum
- [ ] T088 [P] [US7] `p-test/contract/ContractValiditySuite.scala`: the served document parses as
  OpenAPI 3.1 with `swagger-parser` with no unresolved `$ref` (research R2's stand-in for
  `clientGeneration.test.ts`)
- [ ] T089 [US7] Bind `features/public-api/contract.feature`; make the CI job fail when any of
  T085–T088 fails, and add a note to `services/public-api/README.md` that the contract changes only by
  a reviewed commit to the resource file and contracts/amendments.md

**Checkpoint**: every scenario in `features/public-api/` passes.

---

## Phase 11: Recordings, parity, deploy, polish

- [ ] T090 Write the Go driver `tests/original/public-api-driver/` (modelled on the original's
  `tests/e2e/scenario252_partner_api_e2e_test.go`): seed two brands, two partner clients and grants
  (all fields with an agreement; none), activity with known figures, a grant at 5 per minute; make
  contracts/recording.md's 11 recordings into `tests/port/public-api/` with `contract.port.Recording`.
  Check what the original writes as a free-spins claim's `face_value` and whether its `bonus-claims`
  list holds unactivated rewards, and record both in research.md (R16.5, fields.md §6)
- [ ] T091 Run the driver against a local original on kind (`just public-api-record`); commit the
  recordings
- [ ] T092 Write the parity suites of contracts/recording.md in `p-test/port/` with its comparison
  rules (ids by role, the authentication code classes, `computedAt` by shape), each skipping and
  naming its recording when absent; `just public-api-parity` green
- [ ] T093 [P] Write `p-test/perf/StatsPerfSuite.scala`: a run over one million rounds within five
  minutes (SC-008), and 95% of by-id reads within 500 ms and full pages within one second (SC-002) on
  the perf data set; and 95% of facts visible through the API within 30 seconds of being published
  (SC-003); if the run is too slow, fold one day at a time in parallel (research R16.3)
- [ ] T094 [P] Finish `services/public-api/README.md`: components, routes, suites, how to create a key
  locally, the recipes
- [ ] T095 Deploy to the dev-kind installation (`just modules apply goldvibe --installation dev-kind`
  with goldvibe entitled to `public-api`); create a key through the operator route; run `ankka-inspect`
  over the spec's scenarios against the deployed service; keep each met scenario as an integration
  test
- [ ] T096 Run `just features`, `just test`, `just public-api-test`, `just public-api-it` and every
  amended producer's suites; all green

---

## Dependencies & Execution Order

- **Setup (T001–T009)** first. T001 → T002; T003–T009 in parallel after T002.
- **Foundational (T010–T025)** after Setup. The producer tasks T010–T015 run in parallel (T013 takes
  T012's values, but the fields are optional); T016 → T017; T018–T021 in parallel; T022–T025 after
  T017.
- **US1 (T026–T035)** after Foundational: the MVP.
- **US2 (T036–T040)** after US1 (admission).
- **US3 (T041–T054)** after US2 (pipeline, paging).
- **US4 (T055–T060)** after US3 (players).
- **US8 (T061–T069)** after US1; it may run beside US3–US4, and must land before US5.
- **US5 (T070–T077)** after US3 (views) and US8.
- **US6 (T078–T084)** after US5 (the run).
- **US7 (T085–T089)** after US6 (all 22 operations).
- **Phase 11** last; T090–T092 need the original on kind.

## Parallel examples

- Phase 2: T010, T011, T012, T014 and T015 (four producers), with T018–T021 (the service's wire
  layer).
- US3: T046–T051 (six records) together; T041–T044 (tests) together.
- US7: T085–T088 together.

## Implementation strategy

1. Setup and Foundational: the contract published, four producers amended, the service serving its
   contract.
2. **MVP: US1**. Keys exist, and a key reads `/v1/contract`. Demonstrable to the backoffice team,
   who build the key screen against `/access-keys`.
3. US2–US4: the P1 records surface, which is what the BI system needs first.
4. US8, then US5, then US6: audit and limits, then games, then player rankings.
5. US7, then the recordings and parity.
