# Tasks: Game Provider

**Input**: Design documents from `specs/003-game-provider/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The constitution makes the living features the acceptance tests (VII) and the
port method makes parity tests a deliverable (I): the `GherkinSuite` bindings, the entity kits and
the parity suite are tasks here, written before the code they hold to account and failing first
(ankka-port §7).

**Organization**: By user story, in spec.md's order. US1 to US5 are P1 and together are the money
path every recording exercises; US1 alone is the MVP (a game launches and the provider's `check`
is answered).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US8 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `gp/` stands for `services/game-provider/src/main/scala/io/eitheror/casino/gameprovider/`
- `gp-test/` stands for `services/game-provider/src/test/scala/io/eitheror/casino/gameprovider/`
- `contract/` is the shared library `io.eitheror:casino-contract`; `tests/port/game-provider/`
  the recordings; `tests/fixtures/specs/003/` this feature's fixtures
- The service's sbt commands run in `services/game-provider/`; everything else at the root

---

## Phase 1: Setup

**Purpose**: the service's build, the contract change it depends on, the fixtures and the manifest.

- [X] T001 Create `services/game-provider/` with `ankka init game-provider --language scala
      --package io.eitheror.casino.gameprovider`: `build.sbt` on Scala 3.9 with ankka 0.11.0
      (`ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`, `ankka-telemetry-otlp`,
      `ankka-testkit`), `io.eitheror:casino-contract` from the local publish, munit; the package
      directories plan.md lists under `gp/` and `gp-test/`; `services/game-provider/README.md`
      naming the service's purpose in two lines
      *Implementation note:* Written by hand on the template `ankka init` produces (tests/fixtures/sample-service), without running the CLI.
- [X] T002 [P] Extend `contract/src/main/scala/io/eitheror/casino/contract/facts/Others.scala`:
      `RoundSettled` gains `providerTransactionIds: Vector[String]`,
      `walletTransactionIds: Vector[String]` and `freeRoundsGrant: Option[String]`, `outcome`
      constrained to win, loss, cancelled, credit, the key unchanged (`player/currency`); extend
      `contract/src/test/scala/io/eitheror/casino/contract/facts/FactsSuite.scala`; run `just
      schemas` and commit the regenerated `contract/schemas/casino.rounds.v1.json`; `sbt
      contract/publishLocal`
- [X] T003 [P] Create `tests/fixtures/specs/003/`: `st8-local.json` (the provider configuration
      document of data-model.md pointing `apiUrl` at `http://localhost:8099` with a
      `providerKeyDocumentUrl`), `test-key.pem` and `test-key.pub` (the test key pair the original's
      `fake-st8` signs with, copied from the original's checkout and its path recorded), and a
      `README.md` saying which recordings are signed with it (FR-030)
- [X] T004 [P] Add a `service <name> +args` recipe to `justfile` running `sbt` in `services/<name>/`,
      and a step to `.github/workflows/ci.yml`'s `check` job running `sbt contract/publishLocal`
      then `sbt test` in `services/game-provider/`
      *Implementation note:* `just service <name> <task>`; CI runs `sbt test` in services/game-provider after publishing the contract.
- [X] T005 [P] Edit `brands/goldvibe/manifest.json`: remove the `ST8_OPERATOR_CODE` and
      `ST8_SIGNING_KEY` env entries from `game-provider` and the `game-provider-st8` secret
      (research R4: the configuration is written through the operator route into the service's
      secret store); adjust `tools/brands/src/test/scala/io/eitheror/casino/brands/RenderSuite.scala`
      where it expects them
      *Implementation note:* tests/fixtures/manifests/goldvibe.json mirrors the manifest; RenderSuite's secret test now names its own secret. The brands tool also renders `CASINO_REAL_MONEY` from the manifest's `realMoney`, which FR-026's refusal reads.

---

## Phase 2: Foundational

**Purpose**: what every callback and every story needs: amounts, the signature, the protocol, the
adapter seam, the configuration, the four clients and their fakes, the test kits, the recordings,
the endpoint shells and the metrics.

**⚠️ CRITICAL**: no story starts before this phase is done.

- [X] T006 [P] Implement `gp/money/Amounts.scala`: `parse(value, currency)` reading a decimal string
      or a JSON number into `MinorUnits` by the currency's exponent, refusing a non-whole amount
      with `AmountNotWhole(amount)` naming it (FR-005, spec 001 FR-026); `render(minor, currency)`
      to the exponent, negative allowed. Tests `gp-test/money/AmountsSuite.scala`: `"10.50"` EUR →
      1050, `10.5` as a number → 1050, `"10.505"` refused naming it, JPY exponent 0, `-250` renders
      `-2.50`
- [X] T007 [P] Implement `gp/provider/st8/St8Signature.scala`: `verify(rawBody: Array[Byte],
      header: Option[String], publicKey)` as ECDSA P-256 SHA-256 over the bytes with the header
      base64-decoded, `sign(bytes, privateKey)`, loading of base64 DER and PKCS#8 keys (FR-003).
      Tests `gp-test/provider/St8SignatureSuite.scala` with T003's key: round trip; different bytes
      refused; missing and non-base64 header refused. **Verify at task time** (research R3) how
      `ankka-http` hands an endpoint the raw request bytes; if a parsed body is all it gives, use
      the raw-body form it offers and never re-serialise, and record the finding in research R3
      *Implementation note:* Verified: a `String` body is the raw request text in ankka 0.11.0; verified over its UTF-8 bytes (research R3).
- [X] T008 [P] Implement `gp/provider/st8/St8Protocol.scala`: the eight callback names, the fields
      read (FR-004, string or number accepted where the original accepts both), the transaction
      kinds, the status words, `Answer(status, balance, currency)` encoding with the balance as
      T006 renders it, and the HTTP 400 word per callback for a body that is not a JSON object
      (`session_expired`; `player_not_found` for the profile). Tests
      `gp-test/provider/St8ProtocolSuite.scala` as a table over `contracts/callbacks.md`'s cases
- [X] T009 [P] Implement `gp/provider/ProviderAdapter.scala` (the trait: name, callback prefix,
      verify, parse, launch, games, bonus create/get/cancel, offers, key document),
      `gp/config/ProviderConfiguration.scala` (the document of data-model.md; its codec never
      writes `operatorPrivateKey` on a read) and `gp/config/ProviderConfigurations.scala` (reads
      `provider/<name>` from the service's secret store, caches 30 s, invalidates on `put`, lists
      the providers configured, fetches and refreshes a `providerKeyDocumentUrl` within 60 s, FR-026,
      research R4). Tests `gp-test/config/ProviderConfigurationsSuite.scala` over a fake secret
      store: cache, invalidation, key document refresh, the private key absent from a read
      *Implementation note:* The secret store is `SecretStore` (`clients.secrets`, `context.secrets`) in 0.11.0; it cannot list, so `provider/_index` lists the providers (research R4).
- [ ] T010 Reconcile the bet body with spec 002 before the wallet client is written: FR-012 sends
      `game_category` and `provider` with a bet, 002's place-bet carries `game_id` only
      (`contracts/neighbours.md`). Raise it on the `002-wallet` branch (its
      `specs/002-wallet/contracts/routes.md` and FR-004), and record the outcome in
      `specs/003-game-provider/contracts/neighbours.md`: either the two fields are accepted or
      `WalletClient` drops them. Raise on spec 008 at the same time the `player_blocked` family
      `PlayerStatus` folds (data-model.md, spec Assumptions) and record the answer in T025
      *Implementation note:* Open: spec 002's route still carries `game_id` only and 002 has no service. `WalletClient.betBody` sends all three; the `player_blocked` family is likewise still to be raised on spec 008. Both need the people on those branches.
      *2026-10-10:* the bet half is settled on `002-wallet` (844a5b7): place-bet takes `provider`, `gameType` and `category`, in camelCase; aligning `WalletClient` is T088. Open for the `player_blocked` family on spec 008 only.
- [X] T011 [P] Implement `gp/clients/WalletClient.scala` over `clients.services("wallet")`: the
      six commands of `contracts/neighbours.md` with 002's bodies and paths, a 2xx read into
      `WalletTransaction(transactionId)`, refusals decoded into a `WalletRefusal` ADT and the
      research R7 mapping to status words (`INSUFFICIENT_FUNDS` → `not_enough_money`,
      `PLAYER_LOCKED` → `player_locked`, `SCOPE_BLOCKED` and the RG family → `spending_limit`,
      `ROUND_NOT_FOUND` as its own case, `RG_UNAVAILABLE`, `KEY_REUSED`, a 5xx and no answer →
      `Unacknowledged(error)`); and `gp/clients/{PlayersClient,KycClient,ResponsibleGamblingClient}.scala`
      with the *expected* paths of `contracts/neighbours.md` as constants, each answering
      `ServiceUnavailable(name)` on no answer or a non-refusal failure (FR-007). Tests
      `gp-test/clients/WalletClientSuite.scala` over a scripted HTTP stub: every mapping row
- [X] T012 [P] Write the fakes in `gp-test/it/`: `FakeWallet.scala` (an in-process HTTP server
      answering 002's six routes over a per-wallet ledger of real and restricted balance and open
      bets, counting calls per route, scripting a refusal or an outage per key, listing open bets
      for SC-003), `FakeNeighbours.scala` (players, kyc and responsible-gambling at the same path
      constants as T011, with scripted account status, play check, may-play, profile and session
      opening), `St8Stub.scala` (launch answering a game URL, the games list from a fixture, bonus
      create/get/cancel and offers, a key document for T003's key and a rotated one, a record of
      every request received and its signature) and `Kit.scala` (`AnkkaTestKit.start` with the
      service's descriptors and `HttpServer.at("127.0.0.1", 0)`, `clients.services` wired to the
      fakes, a helper that writes T003's key as provider `st8`'s configuration through the operator
      route, and `signed(callback, body)` posting a callback signed with T003's private key)
      *Implementation note:* The kit is `it/Kit.scala`; one shared service serves every integration suite (`features/SharedKit`), ids namespaced per test. A second provider is ST8's protocol under the name `acme`, a third (`nova`) never configured.
- [X] T013 [P] Add `gp-test/features/GameProviderFeatures.scala`: `GherkinSuite("../../features/game-provider")`
      over T012's kit with every step pending, failing when the directory yields zero scenarios, so
      all 73 scenarios of the nine features are listed and fail as unbound
      *Implementation note:* Bound one suite per feature file, as spec 001 did (`features/LaunchFeatures` … `FreeRoundsFeatures`), over the shared kit: 73 scenarios, 82 tests with outline rows, all green.
- [X] T014 [P] Add `gp-test/port/ParitySuite.scala`: one munit test per
      `tests/port/game-provider/*.json` through `contract.port.Replay` against T012's kit, failing
      when the directory is empty; `gp-test/port/TestControl.scala` mapping each `/st8/test/*` step a
      recording carries to the kit setup it stands for (register token → `Ticket` issued and
      checked, register player → `KnownPlayer`, set balance → `FakeWallet`), replayed as setup and
      not compared (research R15); game-session steps marked blocked by spec 008
      *Implementation note:* `ParitySuite` reports itself ignored, never passed, until T015 records; `ParityFixtureSuite` proves the replay on a hand-made recording in tests/fixtures/port/game-provider (control steps, re-signing, a refused unsigned call, a reported difference).
- [X] T015 Record the original: with the original's full stack and `fake-st8` up per
      `tests/original/README.md`, run the Go suite with `-record-to tests/port/game-provider/` for
      `TestScenario15_St8Wallet_*`, `TestScenario7_CasinoGate3_*`, `TestScenarioFreeSpinsPlay_*`
      and the Playwright launch and catalogue reads (spec 001 FR-033); commit the recordings and
      `suite-result.json`, each naming its requirement `003/FR-0xx`; `just brands recordings check`
      green; list what skipped and why in `tests/port/README.md`. Needs the original's checkout and
      Docker; the parity suite has nothing to run until this is done
      *Implementation note:* Recorded 2026-10-10 on the original's own KinD stack at `21da61f0`, twice: 29 `TestScenario15_St8Wallet_*` and 4 `TestScenarioFreeSpinsPlay_*`, each naming its `003/FR-0xx`; `just brands recordings check` 33, 0 problems. Not recorded, with why, in `tests/port/README.md`: `TestScenario7_CasinoGate3_*` fail on the original itself; the Playwright reads are not the Go recorder's. The merge now collapses polls and redacts credentials (`tests/original/merge`).
- [X] T016 Implement `gp/GameProviderService.scala` (registration of every component, the
      extensions, the timer schedule hook T067 fills) and the endpoint shells: `gp/api/CallbackEndpoint.scala`
      as `Routes.public` on `/v1/casino/brands/{brand}/{provider}/{callback}`,
      `/{provider}/{callback}` and, for `st8` only, `/{callback}` (FR-002, research R2), resolving
      the brand (`BrandSegment.check` on the segmented form, the project's own on the others),
      selecting the adapter by prefix, running T007's gate on the raw bytes before any read and
      answering HTTP 200 `auth_failed` on failure, answering T008's 400 word for a malformed body,
      then dispatching to per-callback handlers US1–US6 fill (each `NotImplemented` → `error`
      until then); `gp/api/PlayerEndpoint.scala` (`Routes.player(slug)`),
      `gp/api/OperatorEndpoint.scala` (`Routes.operator(slug)`) and `gp/api/RewardsEndpoint.scala`
      (`Routes.service()`) with their routes declared and `501` bodies; `services/game-provider/service.json`
      for the local run (quickstart 5)
      *Implementation note:* Endpoints are grouped by prefix, not by audience: ankka 0.11 refuses two endpoints sharing a prefix and a route with more than two path parameters. `api/Endpoints.scala` holds Brands (callbacks, operator catalogue, rewards), Sessions, Catalog, Backoffice, the `/st8` and root callback endpoints; each route's audience is its `withAcl`. The provider segment is a literal per adapter. The handlers are real, not shells.
- [X] T017 Implement `gp/Metrics.scala`: `eitheror_casino_bets_total{brand}` (counter) and
      `eitheror_casino_unacknowledged_callbacks{brand}` (gauge) (FR-029). **Verify at task time**
      (research R16) which counter and gauge API `ankka-telemetry-otlp` 0.11 exposes; if none, a
      registry the service owns served on the runtime's `/metrics`; record the finding in research R16
      *Implementation note:* Verified: 0.11.0 offers a service no meter (research R16). `Metrics.scala` keeps the counter; both are served as Prometheus text on `GET …/game-provider/metrics` (operator).

**Checkpoint**: `sbt compile test` green for T006–T011; `GameProviderFeatures` lists 73 pending
scenarios; `ParitySuite` lists 38 failing tests; a callback signed with another key is `auth_failed`
and an unsigned malformed body is 400; nothing else is served yet.

---

## Phase 3: User Story 1 — A player launches a game and the provider opens the session (P1) 🎯 MVP

**Goal**: an eligible player's launch answers the game URL, a launch ticket and a session id; the
provider's `check` consumes the ticket once and the ticket becomes the session ticket.

**Independent Test**: `TestScenario7_CasinoGate3_SessionLifecycle` and the launch and check steps
of `_FullPlayerJourney` and `_RGEnforcement` replay; `features/game-provider/launch.feature` green.

### Tests for User Story 1

- [X] T018 [P] [US1] `gp-test/tickets/TicketSuite.scala` (`KeyValueEntityTestKit`): issue; `check`
      for the ticket's game answers the ticket's fields and marks it checked; a second `check` is
      `session_expired`; `check` for another game is `session_expired`; an absent game code
      matches; a ticket issued long ago is still checked (no expiry, FR-010)
- [X] T019 [P] [US1] `gp-test/players/LaunchWindowSuite.scala`: 20 launches admitted within 60 s,
      the 21st refused with the seconds left; a new window after 60 s; `expireAfter` two minutes
      (FR-007, research R9)
      *Implementation note:* In `players/PlayersSuite.scala` with T020; LaunchWindow, KnownPlayer and BetLimits are in `players/Players.scala`.
- [X] T020 [P] [US1] `gp-test/players/BetLimitsSuite.scala`: at most 50 → low, at most 200 →
      medium, else high, none → medium (FR-018; used by the launch too, *Changes from the original*)
      *Implementation note:* In `players/PlayersSuite.scala`.
- [X] T021 [P] [US1] `gp-test/it/PlayerStatusSuite.scala`: `player_suspended`, `player_reinstated`,
      `player_self_excluded`, `player_closed` published on `casino.players` fold into one row per
      player; a fact published before the view started is seen (research R8; the fallback seed when
      024 is not in the pinned SDK)
      *Implementation note:* In `it/OwnKitSuites.scala`: a fact published before the service starts is seen; 0.11.0 has `StartFrom.Earliest`, so no seed (research R8).
- [X] T022 [P] [US1] Bind `features/game-provider/launch.feature` (14 scenarios) in
      `gp-test/features/steps/LaunchSteps.scala` over the kit: eligibility answers scripted on
      `FakeNeighbours`, the provider's launch and `check` on `St8Stub`, the twenty-launches outline
      *Implementation note:* `features/LaunchFeatures.scala`, 17 tests.

### Implementation for User Story 1

- [X] T023 [P] [US1] Implement `gp/tickets/Ticket.scala`: the key value entity of data-model.md
      (fields, `issue`, `check(gameCode)`, `get`), id the token
- [X] T024 [P] [US1] Implement `gp/players/LaunchWindow.scala` (key value, `launch()` admits or
      refuses with seconds, `expireAfter(2 minutes)`), `gp/players/KnownPlayer.scala` (key value,
      `makeKnown(provider, currency)`, `get`) and `gp/players/BetLimits.scala` (the band)
      *Implementation note:* In `players/Players.scala`.
- [X] T025 [P] [US1] Implement `gp/players/PlayerStatus.scala`: the view over the `casino.players`
      topic of data-model.md with `byPlayer(playerId)`; read from the topic's start where the SDK
      allows (ankka 024), else a `seed` command the migration and the kit use (research R8); record
      which in research R8; `blocked` folds from `player_blocked` once spec 008 publishes it (T010),
      and the view never answers `blocked` until then
- [X] T026 [US1] Implement `gp/provider/st8/St8Client.scala` and `St8Adapter.launch` in
      `gp/provider/st8/St8Adapter.scala`: the signed launch of `contracts/provider-api.md` with the
      brand's site, lobby and deposit links, the wallet URL (`walletUrl` of the configuration),
      the player's profile fields, `bet_limits` from T024's band, fun mode and the attached grant;
      an answer without a game URL is `LaunchFailed`; when no provider is configured locally a stub
      game URL (FR-008)
      *Implementation note:* `St8Adapter` is the client too; it takes its provider name, so a second provider's tests run ST8's protocol under another name.
- [X] T027 [US1] Implement `gp/clients/RewardsClient.scala` with the *expected* path of
      `contracts/neighbours.md`'s `rewards` section (the player's oldest eligible free-rounds grant
      for a game, FR-008) as a constant, plus its scripted fake in `FakeNeighbours.scala`; no grant
      when `rewards` answers none or fails (the launch does not fail)
- [X] T028 [US1] Implement the launch in `gp/api/PlayerEndpoint.scala`: the player token must name
      `playerId` (`player_id_mismatch`); provider resolution from the body, else the one provider
      configured, else `provider_required` when the game code is shared (FR-023); `st8_not_configured`
      when the game's provider has no configuration; `players` account status, `kyc` play check and
      `responsible-gambling` may-play in that order, each refusal and each failure answered as
      `contracts/routes.md` lists (FR-007); `LaunchWindow` → 429 `RATE_LIMITED` with
      `retryAfterSeconds`; grant resolution (the caller's, else T027's, none in demo); `Ticket.issue`;
      `St8Adapter.launch`; `players` game session opened and recently played recorded, best effort
      (FR-009); 200 `{launchToken, gameUrl, sessionId, freeSpinsGrantRef?}`
      *Implementation note:* In `api/Launch.scala`, served by `SessionsEndpoint`. A failed launch discards the ticket; the game session is opened after the provider answers and attached to the ticket (Ticket gained `attach-session` and `discard`).
- [X] T029 [US1] Implement the `check` handler `gp/api/callbacks/Check.scala`: `Ticket.check(gameCode)`
      (`session_expired` on refusal), `player_locked` from `PlayerStatus`, `KnownPlayer.makeKnown`,
      the answer with token, currency, game code, country from `players`' profile (MT when unknown),
      player id and the site's id, lobby and deposit links (FR-010, `contracts/callbacks.md`)
      *Implementation note:* Every handler is in `api/callbacks/Callbacks.scala`, one class, so the shared rules (ticket, currency, lock, idempotency, unacknowledged) are written once.
- [X] T030 [US1] Make the US1 parity tests green: the launch and `check` steps of the three
      `TestScenario7_CasinoGate3_*` recordings and the Playwright launch read, the game-session
      steps marked blocked by spec 008 with the reason in the test's name
      *Implementation note:* The Gate3 recordings do not exist (they fail on the original). The launch and `check` are replayed in the four free-spins recordings instead: the launch step against the service, `fake-st8`'s `GET /game` as the `check` it makes. The Playwright launch read is T072's gap.

**Checkpoint**: quickstart step 3 for `launch.feature`; a launch followed by the stub's `check` works
end to end on the kit; nothing moves money yet.

---

## Phase 4: User Story 2 — The provider debits a stake, credits a win, or closes a round as a loss (P1)

**Goal**: a debit places a wallet bet keyed by the transaction id; a credit settles every open bet
of the round; `round_settled` is published; a free debit is tagged and never reaches the wallet.

**Independent Test**: the 15 `TestScenario15_St8Wallet_*` recordings US2 names replay;
`features/game-provider/rounds.feature` green; SC-003 holds over 1,000 rounds.

### Tests for User Story 2

- [X] T031 [P] [US2] `gp-test/rounds/RoundSuite.scala` (`EventSourcedTestKit`): a debit opens the
      round with an open bet; a credit to a two-debit round settles the first with the win and the
      second as a loss and emits `RoundSettled(win)`; a zero credit settles every open bet as a loss
      (`loss`); a credit with no open bet settles as `credit`; `answerFor` returns the stored answer
      for a key and none for an open unacknowledged one; `recordConflict` appends; a free debit tags
      the grant; stake and win totals
- [X] T032 [P] [US2] `gp-test/rounds/TransactionIndexSuite.scala`: a transaction id and a cancel id
      resolve to their round entity id and kind
- [X] T033 [P] [US2] `gp-test/rounds/SettlementSuite.scala`: the pure decision of which wallet calls
      a credit makes from the round's open bets and the amount: a win on the first open bet and a
      loss on each other, keys `<credit transaction id>/<bet key>`; a provider credit when no bet is
      open; nothing when the round is tagged with a grant; a zero amount is losses only (FR-014)
- [X] T034 [P] [US2] `gp-test/facts/RoundFactsSuite.scala` (`ConsumerTestKit`): `RoundSettled` and
      `RoundCancelled` each produce one `round_settled` with every field of
      `contracts/round-settled.md`, the key `player/currency`, `ce-id` `<round entity id>/<sequence>`
      *Implementation note:* With `facts/RoundCounterSuite.scala` beside it.
- [X] T035 [P] [US2] Bind `features/game-provider/rounds.feature` (16 scenarios) in
      `gp-test/features/steps/RoundSteps.scala`, asserting on `FakeWallet`'s open bets and call
      counts and on the `casino.rounds` messages the kit's broker holds
      *Implementation note:* `features/RoundFeatures.scala`, 18 tests.

### Implementation for User Story 2

- [X] T036 [P] [US2] Implement `gp/rounds/Round.scala`: the event sourced entity of data-model.md
      (state, the twelve events, `answerFor`, `record`, `recordConflict`, `recordUnacknowledged`,
      `clear`, `get`), id `<provider>/<roundId>`, `KeyedOutcomes` from `contract.money`
- [X] T037 [P] [US2] Implement `gp/rounds/TransactionIndex.scala`: the key value entity of
      data-model.md, written before the round is commanded
- [X] T038 [US2] Implement `gp/rounds/Settlement.scala`: T033's decision as a pure function over the
      round's state, returning the ordered wallet calls and the movement the round records
- [X] T039 [US2] Implement `gp/api/callbacks/Idempotent.scala`, used by debit, credit, cancel,
      buyin and payout before any wallet call: `TransactionIndex` → `Round.answerFor(key)` → the
      stored answer without the wallet; the repeat's amount, kind and round compared with the
      stored transaction and a difference → `recordConflict` (still the first answer); an open
      unacknowledged entry → the callback is served afresh and `clear(provider_retry)` on success
      (FR-011, FR-020)
      *Implementation note:* In `api/callbacks/Callbacks.scala` (`locate`, `repeat`, `clearing`).
- [X] T040 [US2] Implement the debit handler `gp/api/callbacks/Debit.scala`: the ticket is required
      and checked (`session_expired`); currency by FR-006; `player_locked` from `PlayerStatus`;
      T006 on the amount; `TransactionIndex` written first; `Round.answerFor` (a repeat, US4);
      a zero or `free_debit` amount tags the round with the ticket's grant before answering and
      records a zero-stake bet (FR-013, `error` when the tag cannot be written); otherwise
      `WalletClient.placeBet` keyed by the transaction id with game, category and provider,
      `allow_negative` for `correction_debit`, each refusal mapped with the current balance, an
      unreachable wallet → `error` and `recordUnacknowledged`; `record`; `Metrics` bets counted
      (FR-012)
      *Implementation note:* In `api/callbacks/Callbacks.scala`.
- [X] T041 [US2] Implement the credit handler `gp/api/callbacks/Credit.scala`: the round by round
      id, else by `TransactionIndex`, else a new round of the transaction id; `Settlement`; a
      `ROUND_NOT_FOUND` on the win → provider credit keyed by the transaction id; a tagged round
      answers the balance and credits nothing; any other refusal or failure → `error` and
      `recordUnacknowledged`; `record` (FR-014)
      *Implementation note:* In `api/callbacks/Callbacks.scala`; a win on a bet the wallet no longer holds is paid as a provider credit under the credit's id.
- [X] T042 [US2] Implement `gp/facts/RoundFacts.scala`: the consumer over `Round` events producing
      `round_settled` on `casino.rounds` with `produceTo`, per `contracts/round-settled.md` (FR-019)
- [X] T043 [US2] Implement `gp/views/RoundsByPlayer.scala` and `gp/views/RoundsBySession.scala`
      over `Round` events and the operator routes `GET …/game-provider/rounds` and
      `GET …/game-provider/rounds/{provider}/{roundId}` in `OperatorEndpoint.scala`
      *Implementation note:* In `views/RoundViews.scala` with the Unacknowledged view; the routes are in `BackofficeEndpoint`.
- [X] T044 [US2] Make the 15 US2 parity tests green; `BigRoundMultipleDebits` and
      `BigRoundMultipleCredits` additionally assert `FakeWallet` holds no open bet after the credit
      (the divergence of plan.md); add `gp-test/it/LeakSuite.scala` driving 1,000 rounds including
      multi-debit ones and asserting zero open bets (SC-003), and writing the kit's timing report
      for SC-002 (`p95` over its money callbacks against `FakeWallet`)
      *Implementation note:* All 15 green. `BigRoundMultipleDebits` records no credit, so its extension credits the round and finds both bets settled; `BigRoundMultipleCredits` finds no open bet. Breaking the settlement to the original's rule fails the extension.

**Checkpoint**: every spin's money path works on the kit; `casino.rounds` carries one fact per
settlement; the 2026-08-19 leak cannot recur.

---

## Phase 5: User Story 3 — The provider cancels a debit or a credit (P1)

**Goal**: a cancelled debit is rolled back on its own key; a cancelled credit is debited back
below zero; a cancel of the unknown is `transaction_not_found` with the balance.

**Independent Test**: the 6 `TestScenario15_St8Wallet_*` recordings US3 names replay;
`features/game-provider/cancels.feature` green.

### Tests for User Story 3

- [X] T045 [P] [US3] Extend `gp-test/rounds/RoundSuite.scala`: a cancel of the last open bet
      closes the round as `cancelled` and emits `RoundCancelled`; a cancel of a credit on a settled
      or cancelled round records the movement and leaves the status; a cancel of a free-round debit
      cancels the round with no movement (Edge Cases)
- [X] T046 [P] [US3] Bind `features/game-provider/cancels.feature` (7 scenarios) in
      `gp-test/features/steps/CancelSteps.scala`
      *Implementation note:* `features/CancelFeatures.scala`.

### Implementation for User Story 3

- [X] T047 [US3] Implement the cancel handler `gp/api/callbacks/Cancel.scala`: the player by token
      or by id (`KnownPlayer`); the referenced transaction through `TransactionIndex`, unknown →
      `transaction_not_found` with the balance; kinds `credit` and `free_credit` → a bet keyed by
      the cancel id for the credited amount with `allow_negative`; a debit → `rollbackBet` on its
      own wallet key, `ROUND_NOT_FOUND` → a provider credit of the debit's amount keyed by the
      cancel id; a free-round debit → no movement; `record` and the round closed as cancelled when
      no open bet remains (FR-015)
      *Implementation note:* In `api/callbacks/Callbacks.scala`; a transaction already cancelled is not refunded twice.
- [X] T048 [US3] Make the 6 US3 parity tests green (`BasicCancel`, `BigRoundMultipleCancels1`,
      `BigRoundMultipleCancels2`, `LimitedCancel`, `StandaloneCancel`,
      `CancelEverythingWithinRound`)
      *Implementation note:* All 6 green.

**Checkpoint**: the provider's correction path is whole; `cancels.feature` green.

---

## Phase 6: User Story 4 — A repeated callback is answered as the first was (P1)

**Goal**: a repeat never reaches the wallet; a conflicting repeat is recorded; an unacknowledged
callback is listed, replayed or resolved by an operator and cleared by a successful retry.

**Independent Test**: the 3 `Idempotent*` recordings replay; `repeats.feature` and
`unacknowledged.feature` green; SC-004 holds over 1,000 repeats.

### Tests for User Story 4

- [X] T049 [P] [US4] Bind `features/game-provider/repeats.feature` (4 scenarios) in
      `gp-test/features/steps/RepeatSteps.scala` and `features/game-provider/unacknowledged.feature`
      (4 scenarios) in `gp-test/features/steps/UnacknowledgedSteps.scala`, the wallet outage
      scripted on `FakeWallet` and the operator acting through the kit with a staff token
      *Implementation note:* `features/RepeatFeatures.scala` and `features/UnacknowledgedFeatures.scala`.
- [X] T050 [P] [US4] `gp-test/it/UnacknowledgedViewSuite.scala`: a `CallbackUnacknowledged` event
      makes a row with the entry; `UnacknowledgedCleared` removes it; a round with two open entries
      is one row with `openCount` 2; the metric gauge equals the sum
      *Implementation note:* In `it/SharedKitSuites.scala`, with the bets counter.

### Implementation for User Story 4

- [X] T051 [US4] Implement `gp/views/Unacknowledged.scala` over `Round` events (one row per round
      with open entries, `openCount`) and wire `Metrics`' gauge to its sum
- [X] T052 [US4] Implement the operator routes in `OperatorEndpoint.scala`: `GET …/unacknowledged`
      from the view; `POST …/unacknowledged/{transactionId}/replay` re-driving the stored callback
      through the same handler under the same key, 200 with the answer and `clear(operator_replay,
      operator)`, 409 `still_unacknowledged` with the new error on another refusal, 404 when not
      open; `POST …/resolve` with a required `reason` → `clear(operator_resolve, reason, operator)`,
      204; the operator from the staff token, the time recorded on the round (FR-020)
      *Implementation note:* In `BackofficeEndpoint`; a replay re-runs the stored body through the same handler with the operator recorded.
- [X] T053 [US4] Make the 3 US4 parity tests green; add to `IdempotentCredit` a marked extension
      step repeating the credit with a changed amount and asserting the first answer and a recorded
      conflict (plan.md divergence); add `gp-test/it/RepeatSuite.scala` driving 1,000 repeats and
      asserting zero wallet calls (SC-004)
      *Implementation note:* All 3 green; `IdempotentCredit`'s marked extension repeats the credit for 99.00 and finds the first answer and a conflicting repeat.

**Checkpoint**: a timeout can never become a double debit; an operator can finish what the wallet
did not acknowledge.

---

## Phase 7: User Story 5 — A callback is verified, and an unverifiable one is refused (P1)

**Goal**: the gate T016 built is proven: nothing is read or written on `auth_failed`, a rotated key
verifies without a redeploy, and a real-money brand cannot point at a key document.

**Independent Test**: `WrongSignature` replays; `signatures.feature` green; SC-005 100 of 100.

### Tests for User Story 5

- [X] T054 [P] [US5] Bind `features/game-provider/signatures.feature` (4 scenarios) in
      `gp-test/features/steps/SignatureSteps.scala`, asserting zero entity commands and zero wallet
      calls on a refused signature (the kit counts both)
      *Implementation note:* `features/SignatureFeatures.scala`, including the rotated key through the stub's key document.
- [X] T055 [P] [US5] `gp-test/it/KeyRotationSuite.scala`: `St8Stub` publishes a rotated key
      document; a callback signed with the new key verifies within 60 s with no restart; a brand
      holding a literal key ignores the document; `PUT …/providers/st8` with
      `providerKeyDocumentUrl` on a real-money installation is 400 `real_money_brand` (FR-026)
      *Implementation note:* In `it/OwnKitSuites.scala` on a real-money kit; the rotation itself is in signatures.feature.

### Implementation for User Story 5

- [X] T056 [US5] Harden `gp/api/CallbackEndpoint.scala`: the public key chosen by the prefix's
      provider from `ProviderConfigurations` (the document's current and next keys both accepted
      during a rotation), the gate proven to run before any entity or client call, the malformed
      400 word per callback from T008, a brand the service does not serve refused (spec 001 FR-005)
      *Implementation note:* In `CallbackRoute.serve`: the signature is checked before anything is read; a wrong brand is 404 `wrong_brand` naming the brand.
- [X] T057 [US5] Make `WrongSignature` green; add `gp-test/it/SignatureSuite.scala`: 100 callbacks
      signed with 100 other keys all `auth_failed` (SC-005)
      *Implementation note:* Green: the recorder keeps no signature header, so a callback the original answered `auth_failed` is replayed under another key.

**Checkpoint**: the P1 stories are done; every one of the 30 `TestScenario15_St8Wallet_*`
recordings but the 4 reads of US6 is green (SC-001 nearly whole).

---

## Phase 8: User Story 6 — The provider reads a balance or a player profile; a buyin or payout outside a round (P2)

**Goal**: balance with or without a token, the profile from `players` at call time with the
brand's jurisdiction, buyin and payout as rounds of their own transaction id.

**Independent Test**: `BasicBalance`, `BalanceWithoutToken`, `BasicBuyin`, `BasicPayout` replay;
`reads.feature` green.

### Tests for User Story 6

- [X] T058 [P] [US6] Bind `features/game-provider/reads.feature` (7 scenarios) in
      `gp-test/features/steps/ReadSteps.scala`, the profile fields scripted on `FakeNeighbours`
      *Implementation note:* `features/ReadFeatures.scala`.

### Implementation for User Story 6

- [X] T059 [US6] Implement `gp/api/callbacks/Balance.scala` (a token of ten or more characters
      resolves the ticket, else the player id through `KnownPlayer`; `player_locked` from
      `PlayerStatus`; `session_expired` when neither resolves; `player_not_found` with no player;
      currency by FR-006; real plus restricted from `WalletClient.balance`, FR-017) and
      `gp/api/callbacks/PlayerProfile.scala` (`player_not_found` unless `KnownPlayer` holds the
      player; the profile from `PlayersClient` at call time, never stored; jurisdiction from the
      provider's configuration; country upper-cased; registration date as a calendar day;
      `BetLimits`, FR-018)
      *Implementation note:* In `api/callbacks/Callbacks.scala`. A profile is served to a player known to the provider or registered at the brand (`casino.players`), which reads.feature's never-launched player needs; FR-018 and contracts/callbacks.md now say so.
- [X] T060 [US6] Implement `gp/api/callbacks/Buyin.scala` (a player id required →
      `player_not_found`; the ticket when present; a round of the transaction id; a bet keyed by it
      with `allow_negative` for `correction_debit`; refusals mapped as a debit's, the divergence
      plan.md records) and `gp/api/callbacks/Payout.scala` (a provider credit keyed by the
      transaction id; the round settles as `credit`) (FR-016)
      *Implementation note:* In `api/callbacks/Callbacks.scala`.
- [X] T061 [US6] Make the 4 US6 parity tests green; SC-001 now holds for all 30
      `TestScenario15_St8Wallet_*` recordings
      *Implementation note:* All 4 green; SC-001 holds for the 29 ST8 recordings. `NotEnoughMoney` diverges on purpose (the refused debit's balance; spec Changes, plan Divergences) and the test demonstrates it.

**Checkpoint**: every callback of `contracts/callbacks.md` is served; SC-001 complete.

---

## Phase 9: User Story 7 — The catalogue is fed by the provider and curated by the brand (P2)

**Goal**: a nightly refresh per provider, the brand's curation applied at read time, the player's
filters and popular games by rounds, the operator's full view.

**Independent Test**: a catalogue seeded from `St8Stub`'s game list and the Playwright catalogue
reads (SC-006) replay; `catalogue.feature` green.

### Tests for User Story 7

- [X] T062 [P] [US7] `gp-test/catalogue/GameSuite.scala` (`upsert` replaces the provider's fields
      and clears `removedFromFeed`; `markRemoved` sets it and leaves `enabled`),
      `gp-test/catalogue/BrandCatalogueConfigurationSuite.scala` (an override on a removed game is
      refused `game_removed`) and `gp-test/catalogue/EnablementSuite.scala` (the decision table of
      data-model.md: removed, category, developer, override, provider's flag)
      *Implementation note:* One suite, `catalogue/CatalogueEntitiesSuite.scala`, for the game, the configuration and the decision.
- [X] T063 [P] [US7] `gp-test/facts/RoundCounterSuite.scala` (`ConsumerTestKit`): `RoundOpened`
      increments `GameRounds` for `<provider>/<code>`; a redelivery is noted as the at-least-once
      limit research R10 accepts
- [X] T064 [P] [US7] `gp-test/it/CatalogueViewSuite.scala`: 300 games seeded; filters by category,
      themes, features, bonus types, tag, developer id and slug, name search, country against
      restricted and prohibited territories, deprecation date; ordering by code; `limit` 1–3,000 and
      `offset` with `total` and `nextOffset`; a batch of 50 codes ignoring other filters; one code;
      developers; `GamePopularity` ordered by rounds with enablement applied at read (FR-023)
      *Implementation note:* In `it/SharedKitSuites.scala`. The filters are the original's: the view is read whole and filtered in Scala (research R10, changed at task time).
- [X] T065 [P] [US7] `gp-test/it/CatalogueRefreshSuite.scala`: a refresh from `St8Stub`'s list
      upserts every game under `st8/<code>`; a game absent from a non-empty list is marked removed
      with `enabled` untouched; an empty list changes nothing; two providers configured refresh
      independently and only each one's absent games are marked; three retries at 10 s, 30 s, 60 s
      (clock advanced by the kit) and the outcome on `RefreshStatus` per provider; the on-demand
      operator route answers 202 (FR-021)
      *Implementation note:* In `it/SharedKitSuites.scala`: retries at 10, 30 and 60 s, recovery on the second attempt, refresh-now through the durable timer, 03:00 UTC.
- [X] T066 [P] [US7] Bind `features/game-provider/catalogue.feature` (12 scenarios) in
      `gp-test/features/steps/CatalogueSteps.scala`
      *Implementation note:* `features/CatalogueFeatures.scala`, 13 tests.

### Implementation for User Story 7

- [X] T067 [P] [US7] Implement `gp/catalogue/Game.scala` (key value per `<provider>/<code>`, the
      fields of data-model.md, `upsert`, `markRemoved`), `gp/catalogue/BrandCatalogueConfiguration.scala`
      (per brand slug, `set` refusing an override that would enable a removed game),
      `gp/catalogue/Enablement.scala` (the decision and the per-layer explanation the operator
      route lists), `gp/catalogue/GameRounds.scala` (key value, `increment`) and
      `gp/catalogue/RefreshStatus.scala` (key value per provider)
      *Implementation note:* All in `catalogue/Catalogue.scala`.
- [X] T068 [P] [US7] Implement `gp/catalogue/Catalogue.scala` (the view over `Game` state with
      `codeLower` and `developerSlug`, the queries of data-model.md over `jsonText`, `jsonNumber`,
      `jsonContains`), `gp/catalogue/GamePopularity.scala` (the view over `GameRounds` ordered by
      `rounds` descending) and `gp/catalogue/RoundCounter.scala` (the consumer over `Round` events
      incrementing `GameRounds` on `RoundOpened`)
      *Implementation note:* In `catalogue/Catalogue.scala`; the reads are `catalogue/CatalogueReads.scala`.
- [X] T069 [US7] Implement `St8Adapter.games` in `gp/provider/st8/St8Adapter.scala` (the signed
      `GET /api/operator/v1/games?site=…` of `contracts/provider-api.md`, the field mapping of
      `St8GameSeeder.scala:157-218`) and `gp/catalogue/CatalogueRefresh.scala`: the timed action
      refreshing one provider with three retries and `RefreshStatus`, returning done on failure
      too; scheduled from `GameProviderService.scala` as a recurring timer every 24 h first at 03:00
      UTC (ankka 032), **verify at task time**: where 032 is not in the pinned SDK, a single timer
      that reschedules itself from the handler; scheduled idempotently at start and when a
      provider configuration is written; record which in research R11
      *Implementation note:* 0.11.0 has no recurring timer: the nightly timer reschedules itself (research R11). `RefreshRuntime` carries the scheduler, adapters and configuration cache to the timed action.
- [X] T070 [US7] Implement the catalogue routes in `gp/api/PlayerEndpoint.scala` per
      `contracts/routes.md`: `GET /v1/casino/catalog/games` (the original's `gameToJson` shape plus
      `provider`, enablement applied after the query and the page filled as
      `GameRoutes.scala:293-341` did), `GET …/games/{gameCode}` (`provider?`, 404 `game_not_found`,
      400 `ambiguous_game_code` naming both providers), `GET …/developers`,
      `GET /v1/casino/catalog/{brand}/games/popular` (`limit` 1–100, default 20, disabled excluded
      at read). Check the Playwright recordings of T015 for a catalogue read before sign-in: if one
      exists, make the catalogue reads `Routes.public` and correct FR-027 in spec.md
      *Implementation note:* In `CatalogEndpoint`. Whether the lobby reads the catalogue before sign-in awaits T015's Playwright recordings; the reads are player routes until then.
- [X] T071 [US7] Implement the operator catalogue and provider routes in `gp/api/OperatorEndpoint.scala`:
      `GET`/`PUT …/game-catalog-config` (400 `game_removed`), `GET …/game-catalog` (`provider?`,
      every layer of the decision, FR-024), `GET …/providers`, `GET`/`PUT …/providers/{provider}`
      (exactly one key field, `real_money_brand`, the private key never answered, a write
      invalidating `ProviderConfigurations` and scheduling T069's refresh),
      `POST`/`GET …/providers/{provider}/catalogue/refresh`
      *Implementation note:* In `BrandsEndpoint` (catalogue configuration and operator catalogue) and `BackofficeEndpoint` (providers, refresh). Overrides keyed by a bare code, as the original's backoffice wrote them, are honoured.
- [ ] T072 [US7] Make the Playwright catalogue read recordings of T015 green as parity tests
      (SC-006), the catalogue seeded from the same `fake-st8` game list the recording saw
      (`tests/fixtures/specs/003/games.json`, copied from the original's `fake-st8`)
      *Implementation note:* Open: the Playwright reads were not recorded (the recorder is the Go suite's transport). Spec 001 FR-033's run of the Playwright suite against a rebuilt brand is where SC-006 is shown.

**Checkpoint**: quickstart step 5's refresh and catalogue calls work against the simulator or the
stub; the lobby's reads are unchanged.

---

## Phase 10: User Story 8 — `rewards` grants free rounds through the provider (P2)

**Goal**: `rewards` creates, polls and cancels a provider bonus and prices spin values through this
service's signed client, per provider.

**Independent Test**: the 4 `TestScenarioFreeSpinsPlay_*` recordings replay against `St8Stub`'s
bonus routes; `free-rounds.feature` green.

### Tests for User Story 8

- [X] T073 [P] [US8] Bind `features/game-provider/free-rounds.feature` (5 scenarios) in
      `gp-test/features/steps/FreeRoundsSteps.scala`, the second provider as a second configuration
      and a second `St8Stub` instance under another prefix
      *Implementation note:* `features/FreeRoundsFeatures.scala`.
- [X] T074 [P] [US8] `gp-test/it/RewardsEndpointSuite.scala`: create answers the provider's status
      mapped to active, processing, rejected, failed, cancelled; a player not known is made known
      first (the stub sees `player_profile` answered); poll and cancel; `not_configured` for a
      provider without configuration; 503 `provider_unavailable`; spin values sorted and cached for
      the day (a second call makes no provider request); each request signed with the operator key
      of the named provider
      *Implementation note:* In `it/SharedKitSuites.scala`.

### Implementation for User Story 8

- [X] T075 [US8] Implement `St8Adapter.bonusCreate`, `bonusGet`, `bonusCancel` and `offers` in
      `gp/provider/st8/St8Adapter.scala` per `contracts/provider-api.md`: the body signed on a
      POST, the query string on a GET; the status mapping by the original's groups
      (`St8BonusClient.scala:31-36, 180-195`)
- [X] T076 [US8] Implement the five routes of `gp/api/RewardsEndpoint.scala` per
      `contracts/routes.md` (`…/players/{playerId}/known`, `…/bonuses`, `…/bonuses/{grantId}`,
      `…/bonuses/{grantId}/cancel`, `…/spin-values`) and `gp/provider/SpinValuesCache.scala` (per
      provider, games and currency, until the end of the UTC day) (FR-025)
      *Implementation note:* In `BrandsEndpoint`; the day's cache is `SpinValues` in the same file.
- [X] T077 [US8] Make the 4 `TestScenarioFreeSpinsPlay_*` parity tests green, `player_profile`'s
      `jurisdiction` compared under `vary` (the divergence plan.md records)
      *Implementation note:* All 4 green. `fake-st8`'s steps are stood in for with the callbacks it makes (its `check`, and `/test/free-spin`'s zero `free_debit` and `credit`), other services' steps answered as recorded, the recorded grant made the fake `rewards`' eligible grant. No `player_profile` is in these recordings, so nothing is compared under `vary` for jurisdiction.

**Checkpoint**: every route of `contracts/routes.md` is served; all 38 recordings are green or
marked blocked by spec 008.

---

## Phase 11: Polish

- [X] T078 [P] `gp-test/it/DurabilitySuite.scala`: `restartService` between a debit and its credit,
      after a ticket is issued, and with an open unacknowledged entry; each state is intact after
      *Implementation note:* In `it/OwnKitSuites.scala`.
- [X] T079 [P] Confirm against ankka 042's current branch whether a service with no personal field
      must register an erasure handler (research R16); if so, implement `gp/Erasure.scala`
      registering one that deletes nothing under `player/<id>` and says so in a log line; record
      the answer in research R16
      *Implementation note:* No handler: ankka 042 FR-010 says a service without one has nothing of its own to do (research R16).
- [X] T080 [P] Write `services/game-provider/README.md` in full (the components, the three callback
      prefixes, the provider configuration and how a key is rotated, how a second provider is
      added, the suites and what each proves) and update `CLAUDE.md` "Working here" for the first
      service under `services/` and the `just service` recipe
- [X] T081 [P] Write the measured SC-002 p95 from T044's report into `quickstart.md` step 6 and the
      scenario counts into step 3
      *Implementation note:* Written into quickstart steps 3 and 6, as measured in the kit; the installation figures await T082.
- [ ] T082 Run quickstart steps 5 and 6: build the service image, point `brands/goldvibe/manifest.json`
      at it, `just brands apply`, `ankka services get game-provider --project goldvibe` Ready, the
      provider configuration written naming the simulator's key document, one launch and one round
      visible on the rounds route and on `casino.rounds`, `ankka-inspect` confirming the entities
      and views of data-model.md; fix what fails and write the outcome into `quickstart.md`
      *Implementation note:* Not run: it applies the goldvibe manifest, which creates a project whose id is never reusable (spec 001's T024 note), and kubectl's current context is a GKE cluster. Run it deliberately against the kind installation.
      *2026-10-10:* run on the local kind installation (goldvibe's id is taken there): Ready at `medium`, step 6's routes, signatures, configuration and an unacknowledged payout shown through the public hostname; two service faults (plaintext Kafka, unnamed topic contracts) and four installation gaps fixed; quickstart step 6 has the rest. Open for what needs the neighbours: a launch, a round that moves money, the refresh against a simulator, SC-002 measured there.
      *2026-10-10, later:* the real wallet deployed beside it; the two talk, and money waits on two things quickstart step 6 names: a payout to a player with no wallet is refused (only a deposit creates one, where the original's payout did), and a deposit needs `responsible-gambling`.

## Phase 12: The bet workflow (spec session 2026-10-10)

**Purpose**: spec 002 bounded the wallet's keys to its recent 1,000 and moved `may-bet` out of the
wallet (`002-wallet` 844a5b7). A provider transaction's idempotency and outcome become a bet workflow
per transaction here (research R18); the transaction index and the round's stored answers go. What
Phases 2 to 8 built on them is reworked; the features, the parity recordings and the SC suites are the
oracle and stay green.

**Independent test**: the whole suite green, with the new scenarios of `rounds.feature` and
`repeats.feature`; LeakSuite, RepeatSuite and the 33 parity tests unchanged in what they assert.

- [X] T083 [P] `gp-test/it/BetWorkflowSuite.scala` on the shared kit, written first and failing: a
      start claims and a second start is `Conflict`; a debit runs `may-bet`, the bet and `record-round`,
      and its outcome is absent until the round holds the bet; `may-bet` locked, excluded or cooling off
      ends it `player_locked` with no wallet call; `may-bet` silent past 500 ms ends it `error` with no
      wallet call; a wallet failure retries twice, records the callback unacknowledged and pauses with no
      outcome, `retry` resumes it and `resolve` ends it; a zero or free debit asks nothing
      *Implementation note:* `it/BetWorkflowSuite.scala`, 8 tests, red before T084. The ordering test slows `record-round` (`BetRuntime.recordDelay`) so the order is observable; writing the outcome before `record-round` fails it.
- [X] T084 Implement `gp/rounds/BetWorkflow.scala` (id `<provider>/<transaction id>`, the cancel id for a
      cancel; state, `start`, `status`, `retry`, `resolve`; steps `may-bet`, `wallet`, `record-round` and
      the failover `unacknowledged`, by kind as research R18's table; `wallet` step `maxRetries(2)`
      failing over to `unacknowledged`) and register it in `GameProviderService.scala`
      *Implementation note:* `rounds/BetWorkflow.scala` with `BetJob` and `BetFlow`; workflow id `bet`. `may-bet` has a 500 ms step timeout failing over to `rg-unavailable` (a step may call a service only from its own thread); the wallet step tries three times itself, then `unacknowledged`; an account locked in the players view ends `start` at once. The money rules moved, unchanged, to `rounds/MoneyMoves.scala`.
- [X] T085 [P] `ResponsibleGamblingClient.mayBet` in `gp/clients/Neighbours.scala` (500 ms, failing
      closed) and its path in `NeighbourPaths`; `FakeNeighbours` serves `may-bet` (allowed, locked,
      excluded, cooling off, silent) and counts the calls
      *Implementation note:* `ResponsibleGamblingClient.mayBet` and `NeighbourPaths.mayBet`; `FakeNeighbours.betRefused`, `betSilent` (answers after 1.5 s), `mayBetAsked`.
- [X] T086 Rework `gp/api/callbacks/Callbacks.scala`: every money handler starts the workflow and polls
      its `status` with one helper (`gp/api/Await.scala`: 1 ms apart up to 2 s, `error` past it); a
      `Conflict` answers from the outcome and records a conflicting repeat when the body's digest
      differs; a cancel reads the referenced transaction from that workflow's `status`
      (`transaction_not_found` when none); `BackofficeEndpoint`'s replay and resolve drive the
      workflow's `retry` and `resolve`
      *Implementation note:* The poll is `Callbacks.await` (no separate `Await.scala`). A cancel finds the referenced transaction by its workflow's `status`. The operator's replay and resolve call the workflow's `retry` and `resolve`.
- [X] T087 Remove `gp/rounds/TransactionIndex.scala`, `TransactionIndexSuite`, and the round's
      `outcomes` and `answerFor` (`Round.scala`, `RoundSuite`); `Round.record` is called only from
      `record-round`
      *Implementation note:* Removed; `Round.record` and `recordUnacknowledged` key their idempotency on the round's recorded transactions. The round no longer depends on the contract's `KeyedOutcomes`, which spec 002 has since changed.
- [X] T088 [P] Align `gp/clients/WalletClient.scala` with spec 002 as built (`contracts/neighbours.md`):
      camelCase bodies (`roundId`, `gameId`, `sessionId`, `provider`, `gameType`, `category`,
      `allowNegative`, `providerRef`, `actor`), `realBalance` and `bonusBalance` from a movement,
      `realBalance` and `restrictedBalance` from the balance, `txnId`; `FakeWallet` speaks the same;
      `WalletClientSuite` checks the refusal mapping without `PLAYER_LOCKED` and `RG_UNAVAILABLE`
      *Implementation note:* `gameType` and `category` both carry the game's category, as the original sent `gameType`; `actor` is `game-provider`.
- [X] T089 Bind the new scenarios in `gp-test/features/RoundFeatures.scala` and `RepeatFeatures.scala`
      (a debit when responsible gambling does not answer; a credit sent once its debit is answered; a
      repeated debit refused as player locked); the cooling-off and self-excluded outline now sets
      `FakeNeighbours`' `may-bet`, not the wallet's refusal
      *Implementation note:* Bound; `RepeatFeatures` gained its own `it is answered {string}`. Self-exclusion still comes from the players view; cooling off from `may-bet`.
- [X] T090 Rerun and re-measure: `LeakSuite` (1,000 rounds, no open bet; SC-002's p95 through the
      workflow into `quickstart.md`), `RepeatSuite` (1,000 repeats: no wallet call and no `may-bet`),
      `SignatureSuite`, `DurabilitySuite` (a restart between a start and its outcome resumes it), the
      parity suite (33 green); and a concurrency check: 50 pairs of simultaneous duplicate debits place
      50 bets, ask `may-bet` 50 times and answer each pair alike (spec 002 R17's prototype)
      *Implementation note:* All 251 tests green. SC-002 p95 11 to 12 ms (57 before). `DurabilitySuite` gained a restart mid-workflow: the instance resumes when next addressed, which research R18 records with the platform request it implies. `RepeatSuite` also asserts no `may-bet`.
- [X] T091 [P] Update `services/game-provider/README.md` (the components: the bet workflow for the
      transaction index) and research R18 with T090's numbers
      *Implementation note:* README components and suites, research R18 *As built*, data-model's commands and steps, quickstart's SC-002.

**Checkpoint**: no component of this service answers a repeat but the workflow; the wallet's bounded
key store is never the only record of a transaction.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)**: none. T002 publishes the contract T001's build depends on.
- **Foundational (2)**: after Setup. Blocks every story. T010 before T011; T011 and T012 share
  the neighbour path constants; T016 after T007–T009 and T011; T015 needs the original's checkout
  and Docker and gates every parity task (T030, T044, T048, T053, T057, T061, T072, T077).
- **US1 (3)**: after Foundational. The MVP. T028 after T023–T027; T029 after T023–T025.
- **US2 (4)**: after US1 (the debit needs the ticket and `PlayerStatus`). T038 after T036; T039
  after T036 and T037; T040 and T041 after T036–T039; T042 after T036.
- **US3 (5)**: after US2 (extends `Round`, uses `Settlement`'s keys).
- **US4 (6)**: after US2 (the repeat check T039 is already in every handler).
- **US5 (7)**: after Foundational for T054–T056; T057 after T015.
- **US6 (8)**: after US2 (buyin and payout reuse the debit's and credit's wallet path).
- **US7 (9)**: after Foundational only; T069 extends `St8Adapter` (T026) and
  `GameProviderService.scala` (T016). The launch's catalogue lookup (T028's provider resolution)
  reads `Catalogue` once T068 exists; until then the body's `provider` is required.
- **US8 (10)**: after US1 (`KnownPlayer`, `St8Adapter`); T075 extends `St8Adapter`.
- **Polish (11)**: after the stories wanted; T082 needs a kind installation and the simulator or
  the stub reachable from it.
- **The bet workflow (12)**: after everything above. T083 first and failing; T084 after T085; T086
  after T084; T087 after T086; T088 independent; T089 after T086 and T088; T090 after all of them;
  T082 is best run after Phase 12.

### Platform gates (research R3, R8, R11, R16)

- Raw request bytes (T007), a topic read from its start (T025), the recurring timer (T069) and the
  metrics API (T017) are each verified against the pinned SDK when the task is taken, and each has
  its fallback in the task. Nothing waits on an unreleased ankka spec.
- The paths of `players`, `kyc`, `responsible-gambling` and `rewards` are *expected*; the clients
  and the fakes share one constant per path so one edit moves both when those specs are planned.

### Parallel Opportunities

- Phase 1: T002–T005 together, after T001.
- Phase 2: T006–T009 together; T010 alone; then T011–T014 together; T015 any time the original is
  to hand; T016 after T007–T009 and T011; T017 beside it.
- US1: T018–T022 together; T023–T025 together; T026 and T027 together; T028 → T029 → T030.
- US2: T031–T035 together; T036 and T037 together; T038 → T039; T040–T043 together; T044.
- US7: T062–T066 together; T067 and T068 together; T069 → T070 and T071 together → T072.
- US7 can run beside US2–US6 once Phase 2 is done; US8 beside US2–US6 once US1 is done.

---

## Parallel Example: User Story 2

```bash
# tests first, together:
Task: "RoundSuite in gp-test/rounds/RoundSuite.scala"
Task: "TransactionIndexSuite in gp-test/rounds/TransactionIndexSuite.scala"
Task: "SettlementSuite in gp-test/rounds/SettlementSuite.scala"
Task: "RoundFactsSuite in gp-test/facts/RoundFactsSuite.scala"
Task: "RoundSteps binding features/game-provider/rounds.feature"
# then the entities together, then in order:
Task: "Round.scala" + "TransactionIndex.scala" → "Settlement.scala"
Task: "Debit.scala" + "Credit.scala" + "RoundFacts.scala" + "RoundsByPlayer/BySession" → parity
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phases 1 and 2, with T015 started the moment the original's stack is up.
2. Phase 3: a launch and the provider's `check` on the kit, `launch.feature` green.
3. Stop and run quickstart steps 2 and 3 for the launch feature.

### Incremental delivery

1. US2, then US3 and US4: the money path and its two guarantees (no leaked bet, no double
   debit). After US4, 24 of the 30 wallet recordings are green.
2. US5, then US6: the gate proven and SC-001 complete.
3. US7 in parallel with 1 and 2 by a second pair of hands; US8 after US1.
4. Polish, then quickstart 6 on kind before the manifest is applied anywhere real.

---

## Notes

- Every parity task replays recordings that must exist (T015); do not write a recording by hand.
- Every money handler calls `Idempotent` (T039) before the wallet; it is written before the first
  handler.
- `WalletClient` has one place to drop `game_category` and `provider` if T010 decides so.
- The features and the parity suite run on the same kit; a scenario a neighbour owns (the game
  session's ending) is marked, never passed.
- Commit after each task or logical group; stop at any checkpoint to validate the story alone.
