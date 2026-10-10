# Tasks: Wallet

**Input**: Design documents from `specs/002-wallet/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{routes,wire,facts,surface}.md, quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test
(I) and the living features the scenarios (VII); the port method makes parity tests a deliverable
that is written before the component it holds to account and fails first (ankka-port §7). Every
story therefore opens with its tests: the original's Go tables and TL fixtures ported, the wallet
driver's scenarios, the feature bindings, the refusal and idempotency suites.

**Organization**: By user story, in spec order. US1, US2 and US3 are all P1; US1 (the bet path) is
the MVP. A minimal `deposit` lives in Phase 2 because a wallet is created by its first deposit and
every test needs money in; US3 completes it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US6 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `contract/` — `io.eitheror:casino-contract`, packages `io.eitheror.casino.contract.{facts,money,access,personal,port}`
- `services/wallet/` — one sbt build; main sources under
  `services/wallet/src/main/scala/io/eitheror/casino/wallet/{funding,entity,config,payments,views,publish,api,wire}/`,
  tests under `services/wallet/src/test/scala/io/eitheror/casino/wallet/…`, fixtures under
  `services/wallet/src/test/resources/fixtures/`
- `tools/tl-fixtures/` — the one-off converter; `tests/original/wallet-driver/` — the Go driver;
  `tests/port/wallet/` — the recordings
- The original is `~/Documents/projects/eitheror/eitheror-platform` (below, `ORIGINAL`): the resolver
  at `starter-packs/wallet/handlers-go/internal/funding/`, the fixtures at
  `starter-packs/wallet/handlers/*.test.tl`, the routes at
  `substrate/wallet-service/src/main/scala/io/eitheror/wallet/WalletRoutes.scala`

---

## Phase 1: Setup

**Purpose**: the contract additions everything compiles against, the service build, the two tools'
skeletons, the recipes.

- [X] T001 Change `contract/src/main/scala/io/eitheror/casino/contract/money/Key.scala` per research
      R4: `KeyedOutcomes[A](seen: Map[String, Seen[A]])`, `Seen(digest: String, outcome: A)`,
      `answer(key, digest)(decide)` returning `Answer.First(outcome)`, `Answer.Repeated(first)` or
      `Answer.Reused(first)`, `Digest.sha256(canonicalJson)`; keep `outcomeOf`; update
      `contract/src/test/scala/io/eitheror/casino/contract/money/MoneySuite.scala` (first, repeated,
      reused, digest stable across field order) and
      `contract/src/test/scala/io/eitheror/casino/contract/steps/MoneyFeatures.scala` so the new
      scenario "a seen key carried by a different money command is refused naming the first" passes
- [X] T002 [P] Add to `contract/src/main/scala/io/eitheror/casino/contract/facts/MoneyTransactions.scala`
      the families `WithdrawalReversed` and `ProviderCredited`, the fields
      `RestrictedFundsForfeited.clawbackCents: MinorUnits` and `cause: String`, and
      `HoldPlaced.voidedCents: MinorUnits`, exactly as `contracts/facts.md` lists them, registered as
      `FamilyDef`s `withdrawal_reversed` and `provider_credited`; extend
      `contract/src/test/scala/io/eitheror/casino/contract/facts/FactsSuite.scala` with round trips
      for both and for the new fields; run `just schemas` and commit the regenerated
      `contract/schemas/money.transactions.v1.json` so `SchemaDriftSuite` is green
- [X] T003 Bump the contract version in `build.sbt` (`contract` module, `0.2.0`), run `sbt contract/test`
      and `sbt contract/publishLocal`, and record the version in `services/wallet/README.md`'s
      prerequisites (depends on T001, T002)
- [X] T004 [P] Create the service with `ankka init services/wallet` (Scala): `services/wallet/build.sbt`
      (Scala 3.9, ankka 0.11.0, `ankka-sdk`, `ankka-http`, `ankka-auth-oidc`, `ankka-testkit`,
      `io.eitheror:casino-contract:0.2.0`, munit, munit-scalacheck), `services/wallet/project/`,
      `services/wallet/src/main/resources/application.conf` (service name `wallet`, port 9000,
      `CASINO_BRAND` from the environment), the eight package directories of plan.md under
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/`, and `services/wallet/README.md`
      naming the service's purpose and its two topics in three lines
      *Implementation note:* Done with `ankka init` (CLI 0.10.0, bumped to runtime 0.11.0); the template's copied skills and CI were removed (the plugin and the root CI hold them).
- [X] T005 [P] Create `tools/tl-fixtures/` as an sbt project `tlFixtures` in the root `build.sbt`
      (not aggregated into `contract`'s publish): `tools/tl-fixtures/src/main/scala/io/eitheror/casino/tools/tlfixtures/Main.scala`
      stub taking `<handlers-dir> <out-dir>`, and `tools/tl-fixtures/README.md` saying it runs once
      and what it skips
- [X] T006 [P] Create `tests/original/wallet-driver/doc.go` (package `walletdriver`, build tag `e2e`)
      in the `tests/go.mod` module, documenting the flags `-wallet-url`, `-brand` and the
      `EITHEROR_RECORD_TO` variable the recorder reads; add a "Recording the wallet directly" section
      to `tests/original/README.md` with the two-run command of quickstart section 7
- [X] T007 [P] Add `Justfile` recipes `wallet-test` (`cd services/wallet && sbt test`), `wallet-it`
      (`cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.it.* io.eitheror.casino.wallet.port.*"`),
      `wallet-run` (`cd services/wallet && sbt run`), `tl-fixtures` (`sbt "tlFixtures/run …"`), and a
      `wallet` job to `.github/workflows/ci.yml` that publishes the contract locally, then runs
      `wallet-test` and `wallet-it` (the latter behind the Docker-capable runner)

---

## Phase 2: Foundational

**Purpose**: the pure types, the events and state, the entity skeleton that answers from its keys,
the brand configuration, the RG client and its fake, the publishers, the fixture converter, the
parity harness, the driver harness and the feature bindings. Every story adds handlers to this.

**⚠️ CRITICAL**: no story starts before this phase is done.

- [X] T008 [P] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/wire/Bodies.scala`
      (every request and response body of `contracts/routes.md` with the original's field names),
      `wire/Amounts.scala` (minor-unit integer codec via `contract.money.MinorUnits`; major-unit
      two-decimal rendering for player and operator routes) and `wire/Refusals.scala` (every code of
      `contracts/wire.md` with its status and message template, rendered as
      `{"error","message",…named amounts}`); unit test
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/wire/RefusalsSuite.scala` asserting
      each rendered message equals the table's text
      *Implementation note:* The refusal texts are asserted in `entity/EntityRulesSuite` and the status table is `Wire.status`; replies are camelCase, the original's names (contracts/wire.md).
- [X] T009 [P] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/funding/Types.scala`
      (port of `ORIGINAL/…/funding/types.go`: `RuleSnapshot` with the names of data-model.md,
      `Record`, `Bet`, `Leg`, `Contribution`, `BrandSwitches`, the enumerations for funding order,
      win allocation, enforcement, violation mode, release scope, forfeit policy; no ankka type) and
      `funding/Clawback.scala` (FR-015a: deposits since award, clawback, clamp; `None` baseline → 0);
      unit test `services/wallet/src/test/scala/io/eitheror/casino/wallet/funding/ClawbackSuite.scala`
      with the cases of `features/rewards/lifecycle.feature` (clawback 500) and
      `features/wallet/restricted-funds.feature` cancel scenarios
- [X] T010 [P] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/WalletEvent.scala`
      (the 19 events of data-model.md as one closed `enum`, `Codecs.serializer` manifests
      `wallet.<Event>`), `entity/WalletState.scala` (the state table; derived `restrictedBalance`;
      `openRecords` ordered by `(priority, grantedAt)`; `recentDeposits` pruned to 30 days by the
      event's time; `settledRounds` bounded at 10,000; `seen: KeyedOutcomes[Outcome]`),
      `entity/Outcome.scala`; unit tests
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/EventsRoundTripSuite.scala`
      (every event through its serializer) and `entity/InvariantsSuite.scala` (scalacheck over
      random event sequences: the five invariants of data-model.md)
      *Implementation note:* Round trips and invariants are in `EntityRulesSuite` and `InvariantsSuite` (scalacheck, 200 sequences, plus a planted-corruption test that the checks can fail).
- [X] T011 Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/Commands.scala`
      (`MoneyCommand` with `key` and `now`; one case class per command of data-model.md),
      `entity/Digest.scala` (canonical JSON of the command without its key → SHA-256 hex),
      `entity/WalletEntity.scala`: id `<playerId>/<currency>`, `emptyState`, the pure `applyEvent`
      fold for all 19 events, `snapshotEvery = 100`, the prelude every money handler runs
      (`seen.answer(key, digest)`: `Repeated` → the first outcome with no event; `Reused` → reply
      `KEY_REUSED` naming the first command's type and amount and persist `CommandRefused` under
      `<key>#reused#<digest>`), the `refused(code, message)` helper that persists `CommandRefused`
      and replies the refusal, the queries `balance` and `deposit-sums`, `open-migrated`
      (`WalletOpenedFromMigration`; `WALLET_ALREADY_OPEN` when created), and a **minimal** `deposit`
      (`WALLET` created, real credited, `DepositRecord` with the multiplier the command carries,
      `DepositConfirmed`, idempotent; US3 adds RG, terms resolution and sums); every other money
      handler returns `effects.error("not built", Conflict)` until its story; and
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/WalletService.scala` registering it
      (depends on T008–T010)
- [X] T012 [P] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/config/BrandConfigEntity.scala`:
      key value, id `brand`, the state and defaults of research R7 (rate 100 per game type, bonus
      wagering counts, partial splits on, no award limit, turnover 1× `gross_wagered`), commands
      `set-bet-switches` and `set-turnover-terms` each replacing only its part, query `get`;
      `KeyValueEntityTestKit` test
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/config/BrandConfigSuite.scala`
      (defaults when never written; each write leaves the other part unchanged)
- [X] T013 [P] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ResponsibleGambling.scala`:
      `mayBet(player, currency, amount)` and `mayDeposit(player, currency, amount, sums)` over
      `services("responsible-gambling")` with a 500 ms timeout, mapping the answers of spec 010
      FR-007 to `PLAYER_LOCKED`, `EXCLUDED`, `COOLING_OFF(endsAt)`, `OVER_LIMIT(limit, used, remaining)`
      and timeout, connection failure or 5xx to `RG_UNAVAILABLE`; and the scripted fake
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/FakeResponsibleGambling.scala`
      (an HTTP server on a free port: allow by default, `refuse(code, body)`, `silent()`), wired as
      the testkit's service stub for `responsible-gambling`
      *Implementation note:* The fake is in-process (`it/Harness.FakeResponsibleGambling`), handed to the endpoints in place of the HTTP client: `ServiceClients` needs no server to stand in for. The client's paths are fixed in contracts/routes.md for spec 010.
- [X] T014 [P] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/publish/MoneyTransactionsPublisher.scala`
      (consumer over `WalletEntity`: the event → family table of `contracts/facts.md`, one
      `restricted_funds_converted`/`restricted_funds_forfeited` per record with `ce-id`
      `<transactionId>/<recordId>`, `clawback_cents` on the first record only, balances after on
      every family, `admin_adjusted.reason` = the category never the text, `DepositConfirmed` also →
      `PaymentCaptured` on `payments.payments`; audit-only events publish nothing) and
      `publish/AuditPublisher.scala` (every money event and `CommandRefused` → one `platform.audit`
      record per spec 018 FR-001); `ConsumerTestKit` test
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/publish/MappingSuite.scala`: one
      event of each kind → the family named in `facts.md`, the key is the wallet id, `ce-id` as stated
- [X] T015 [P] Implement `tools/tl-fixtures/src/main/scala/io/eitheror/casino/tools/tlfixtures/{Parser,Main}.scala`:
      parse `test "name" { given twin {…} … when <Handler> {…} then { mutation n: …; result … } }`
      into `{"handler","name","given":[twins],"when":{…},"then":{"mutations":[…],"result":…}}`,
      skip `approve-withdrawal-request`, `mark-withdrawal-awaiting-payout`, `check-open-round`,
      `read-contribution-rate` and the three payout-destination files, write one
      `services/wallet/src/test/resources/fixtures/<handler>.json` per handler and print the count
      per handler; unit test `tools/tl-fixtures/src/test/scala/io/eitheror/casino/tools/tlfixtures/ParserSuite.scala`
      on the first case of `credit-win.test.tl`; then run it against
      `ORIGINAL/starter-packs/wallet/handlers` and commit the JSON (173 cases expected across the
      kept handlers; write the actual count into `tools/tl-fixtures/README.md`)
- [X] T016 [P] Implement the fixture runner
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/fixtures/FixtureRunner.scala`:
      loads a handler's JSON, and for each case builds the `given` through real commands on an
      `EventSourcedTestKit` (a wallet twin's `real_balance` → a `deposit` with multiplier 0; a
      `RestrictedFunds` twin → an `award` with the record's fields; an open bet twin → a `place-bet`;
      `restricted_balance` with no record → the unmigrated state via `open-migrated`, FR-001), runs the `when`
      as the command, and asserts the `then`'s `result` and every balance-bearing `set_properties`
      on the wallet and records (twin, edge and concept mutations are not asserted, research R13);
      one munit test per case named `<handler>/<case>`
      *Implementation note:* One `open-migrated` builds each case's starting state; `OpenMigrated` gained open bets, holds and the pending request for it, which the migration needs too. 171 pass, 3 ignored with reasons.
- [X] T017 [P] Implement the parity harness
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/port/WireTranslation.scala` (the
      path, field and amount table of `contracts/wire.md`, applied to a `contract.port.Recording`
      before replay; `turnover_multiplier`/`turnover_definition` inputs become a `set-turnover-terms`
      Given), `port/PortSuite.scala` (one test per `tests/port/wallet/*.json`, translated then
      `Replay.run` against `AnkkaTestKit.start` with the fake RG, failing with the recording's name
      and the first differing JSON path; zero recordings fails the suite) and `port/Blocked.scala`
      (the e2e recordings of the spec's *Recordings* that enter through `game-provider`, `rewards`
      and `payouts`, each reported ignored with "blocked by spec 003/007/011"); unit test
      `port/WireTranslationSuite.scala` on a hand-written three-step recording
      *Implementation note:* `PortSuite` reports an ignored test while `tests/port/wallet/` is empty (T074), and the 50 blocked recordings by name.
- [X] T018 [P] Implement `tests/original/wallet-driver/driver.go`: an HTTP client over the
      `tests/original/recorder` transport to `-wallet-url`, fresh player ids per run, and
      `scenario(t, name, requirement, steps…)` writing the shape of
      `specs/001-brands-and-projects/contracts/recording.md` with `capture` and the `vary` the
      merge tool derives; `tests/original/wallet-driver/driver_test.go` checking the file shape
      against a stub server
- [X] T019 [P] Create `services/wallet/src/test/scala/io/eitheror/casino/wallet/features/WalletFeatures.scala`:
      one `GherkinSuite` per file under `features/wallet/` (`bets`, `restricted-funds`, `deposits`,
      `withdrawals`, `adjustments`, `reads`, `money`), sharing
      `features/Steps.scala` (a scenario-scoped wallet on `EventSourcedTestKit` for entity steps; the
      integration kit for route and topic steps), with every step undefined so the suite lists all
      scenarios and fails them as unbound; set `Test / baseDirectory` so `GherkinSuite("../../features/wallet")`
      resolves from the service's directory
      *Implementation note:* 75 tests across seven suites; the route-level scenarios run through `it/Harness`.

**Checkpoint**: `sbt compile` and `sbt test` run; the fixture, parity and feature suites fail for
the right reason (nothing built, no recordings, steps unbound); `balance` and `deposit` answer on
the test kit.

---

## Phase 3: User Story 1 — A player's bet is funded and settled in one step (P1) 🎯 MVP

**Goal**: bet, win, rollback and loss on real balance through the `game-provider` routes, keyed and
idempotent, `may-bet` first, facts on the topic.

**Independent Test**: quickstart sections 2 (the US1 Go tables), 3 (the four bet fixtures) and 5;
the `bets.feature` and `money.feature` scenarios; `IdempotencySuite`.

### Tests for User Story 1

- [X] T020 [P] [US1] Port `ORIGINAL/…/funding/legs_test.go`, `creditwin_test.go`, `rollback_test.go`
      and `loss_test.go` case for case to
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/funding/{Legs,CreditWin,Rollback,Loss}TablesSuite.scala`,
      each test named `<file>/<case>`; add `funding/OverflowSuite.scala` with SC-004's two cases
      (500,000 on 50,000; 5,000,000 on 500,000) and a `Long` product beyond `Int`
- [X] T021 [P] [US1] Add the fixture suites
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/fixtures/{PlaceBet,CreditWin,RollbackBet,SettleRoundAsLoss}FixturesSuite.scala`
      over `fixtures/{place-bet,credit-win,rollback-bet,settle-round-as-loss}.json` via T016 (cases
      with records pass when US2 lands; the suite reports each by name)
- [X] T022 [P] [US1] Write `tests/original/wallet-driver/bets_test.go`: one scenario per
      `features/wallet/bets.feature` scenario plus the St8 shapes the spec records (BasicDebit,
      BasicCredit, BasicCancel, ZeroDebit, NotEnoughMoney, LargeWinOnLargeStake, IdempotentDebit,
      IdempotentCredit, IdempotentCancel, CorrectionDebitNegativeBalance, BigRoundMultipleDebits,
      BigRoundMultipleCredits, BigRoundMultipleCancels1/2, Resettlement, CancelEverythingWithinRound)
      against the original's `/v1/wallet/{b}/{p}/{c}/{deposit,bet,win,rollback,settle-loss,balance}`
- [X] T023 [P] [US1] Write `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/IdempotencySuite.scala`
      (bet, win, rollback, loss repeated under their key → the first outcome and no new event; the
      same key with a different amount or type → `KEY_REUSED`, message
      `Key <key> was used by a <type> of <amount>`, one `CommandRefused` under the synthetic key;
      a refused bet repeated → the same refusal, no event) and the US1 part of
      `entity/RefusalsSuite.scala` (`WALLET_NOT_FOUND`, `ROUND_NOT_FOUND`, `INSUFFICIENT_FUNDS` bet
      text, `RESTRICTED_FUNDS_UNMIGRATED`, each with `contracts/wire.md`'s message and `ErrorCode`)
- [X] T024 [P] [US1] Bind `features/wallet/bets.feature` and `features/wallet/money.feature` in
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/features/{BetSteps,MoneySteps}.scala`
      (money.feature's boundary steps through the service endpoint of T027)

### Implementation for User Story 1

- [X] T025 [US1] Port `ORIGINAL/…/funding/{legs.go,helpers.go,creditwin.go,rollback.go,loss.go}` to
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/funding/{Legs,CreditWin,Rollback,Loss}.scala`:
      the four funding orders over records with partial splits (FR-005), win allocation
      `follow_source` with the last record leg absorbing the remainder and `all_restricted`, shares
      of a no-longer-active record to real (FR-011), leg restoration and contribution reversal
      floored at zero (FR-012), loss forfeits at zero balance and zero open rounds (FR-013); until
      T020 is green
- [X] T026 [US1] Port `ORIGINAL/…/funding/placebet.go` to
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/funding/PlaceBet.scala`: refusal
      order scope → max stake → funding (FR-006, FR-007), `allow_negative` funded wholly from real
      with no contribution, contributions by FR-008 through a `Rules` interface that T035 fills
      (defaults: rate 100, no scope, no max stake), conversion of records whose progress meets the
      requirement (FR-009), wallet type and balance before/after (FR-010), turnover pending reduced by
      `min(realLeg, pending)`; a zero stake opens its round with no legs
- [X] T027 [US1] Implement the `place-bet`, `credit-win`, `rollback-bet` and `settle-loss` handlers in
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/WalletEntity.scala`: the
      prelude, then `WALLET_NOT_FOUND` on an uncreated wallet, `RESTRICTED_FUNDS_UNMIGRATED` (FR-014),
      the resolver, `BetPlaced`/`WinCredited`/`BetRolledBack`/`RoundSettledAsLoss` carrying everything
      the fold and the fact need, the reply of `contracts/routes.md`; a win or rollback finds the bet
      in `openBets` then `settledRounds` (late rollback restores the stake only)
- [X] T028 [US1] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ServiceEndpoint.scala`
      (`Routes.service()`): `POST …/bets|wins|rollbacks|losses`, `GET …/balance`; `{brand}` checked
      against `CASINO_BRAND` → `404 wrong_brand`; `INVALID_REQUEST` and a missing key refused at the
      boundary with nothing read; the bet route calls `ResponsibleGambling.mayBet` first and answers
      `PLAYER_LOCKED` 403 / `RG_UNAVAILABLE` 503 without reaching the entity, reads
      `BrandConfigEntity.get` once and passes the switches in the command; `CommandError` →
      status with no mapping of the endpoint's own; register the endpoint in `WalletService.scala`
      *Implementation note:* The brand is in the endpoint's prefix (an ankka route takes two path parameters; research R16): another brand's path is a 404 with the runtime's body. The switch's grant and a hold's request ride in the body for the same reason.
- [X] T029 [US1] Integration suite `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/BetsItSuite.scala`
      (`AnkkaTestKit.start` with the entities, the publishers, `ProjectionRuntime()`, the endpoint on
      a free port, the fake RG): quickstart 5's sequence over HTTP asserting bodies and balances;
      `bet_placed`, `win_credited`, `bet_rolled_back`, `round_settled_as_loss` on `money.transactions`
      with `real_balance_after` (retrying on the value); the fake RG silent → `RG_UNAVAILABLE` and no
      round opened; SC-002: 200 concurrent bets on one wallet, final real = start − Σ accepted and no
      accepted bet the balance at its moment could not fund
      *Implementation note:* In `it/WalletItSuite` with the deposits, durability and erasure cases.

**Checkpoint**: quickstart 2 (US1 tables), 3 (real-only fixture cases), 5 pass; `bets.feature` and
`money.feature` green; the driver's `bets_test.go` compiles.

---

## Phase 4: User Story 2 — Restricted funds are granted, wagered, converted and forfeited (P1)

**Goal**: awards as records with rule snapshots; bets across records in priority order with
contributions, conversion, scope and stake rules; forfeit, cancel with the wallet's clawback, close
pending cap, switch; the brand's bet switches route.

**Independent Test**: quickstart 2 (the US2 tables) and 3 (the award, cancel, forfeit and close
fixtures, and the record cases of the US1 fixtures); `restricted-funds.feature`.

### Tests for User Story 2

- [X] T030 [P] [US2] Port `ORIGINAL/…/funding/placebet_test.go`, `multi_test.go`, `rules_test.go` and
      `close_test.go` to
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/funding/{PlaceBet,Multi,Rules,Close}TablesSuite.scala`,
      named `<file>/<case>`
- [X] T031 [P] [US2] Add the fixture suites
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/fixtures/{AwardBonus,CancelBonus,ForfeitBonus,ClosePendingCapBonus}FixturesSuite.scala`
      over `fixtures/{award-bonus,cancel-bonus,forfeit-bonus,close-pending-cap-bonus}.json`
- [X] T032 [P] [US2] Write `tests/original/wallet-driver/restricted_funds_test.go`: one scenario per
      `features/wallet/restricted-funds.feature` scenario against the original's
      `bonus/award`, `bonus/forfeit`, `cancel-bonus`, `bonus/close-pending-cap`, `bonus/switch`,
      `bonus/status` and `bet`/`win`, including the `TestScenario22_FundingResolver` shapes
      (two_records_priority_order, bonus_active_conversion, rules_by_seeding, unmigrated_guard)
- [X] T033 [P] [US2] Extend `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/RefusalsSuite.scala`
      with `SCOPE_BLOCKED`, `MAX_STAKE_EXCEEDED`, `BONUS_ALREADY_ACTIVE`, `MISSING_BONUS_REF`,
      `NO_ACTIVE_BONUS`, `GRANT_NOT_OPEN`, `GRANT_NOT_FOUND`, `GRANT_NOT_ACTIVE`, `NOT_PENDING_CAP`
      and the "already primary" 200, and `IdempotencySuite.scala` with award, forfeit, cancel,
      close-pending-cap and switch
- [X] T034 [P] [US2] Bind `features/wallet/restricted-funds.feature` in
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/features/RestrictedFundsSteps.scala`
      (the brand's concurrent-award limit through `BrandConfigEntity`)

### Implementation for User Story 2

- [X] T035 [US2] Port `ORIGINAL/…/funding/rules.go` to
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/funding/Rules.scala`: scope
      exclusion by game, provider or category with `block`/`ignore` (FR-007), the smallest active
      max stake with `block`/`cap`, the contribution rate lookup game → provider → category →
      default → brand rate by game type, the base by release scope and funding order (FR-008); make
      `PlaceBet.scala` (T026) use it and complete the multi-record cases of T030
- [X] T036 [US2] Port `ORIGINAL/…/funding/close.go` and the award/forfeit/cancel handlers' logic to
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/funding/Records.scala`: new record
      from an award with requirement = amount × multiplier and the baseline `realAtAward`,
      `totalDepositsAtAward` (FR-015, FR-015a); forfeit of the primary or named active record
      (FR-016); cancel's clawback via `Clawback.scala` clamped, with or without an open record, and
      the `bonus_cancel` balance-before (FR-017); close the first `CONVERTED_PENDING_CAP` (FR-018);
      switch to `min(open priorities) − 1` (FR-019)
- [X] T037 [US2] Implement the `award`, `forfeit`, `cancel`, `close-pending-cap`, `switch` handlers and
      the `restricted-funds-status` query in
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/WalletEntity.scala`
      (refusal order per data-model.md; `BONUS_ALREADY_ACTIVE` against the `maxConcurrentAwards` the
      command carries; `RestrictedFundsAwarded`, `RestrictedFundsForfeited(cause)`,
      `RestrictedFundsCancelled`, `PendingCapClosed`, `PrimarySwitched`; `created = true` on an
      award), and the `bet`'s conversions reported in its reply
- [X] T038 [US2] Add to `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ServiceEndpoint.scala`
      `POST …/awards`, `…/awards/forfeit`, `…/awards/cancel`, `…/awards/close-pending-cap`,
      `…/awards/{grantRef}/switch`, `GET …/restricted-funds`, and
      `PUT /v1/casino/brands/{brand}/wallet-config/bet-switches` → `BrandConfigEntity.set-bet-switches`;
      the award route reads `BrandConfigEntity.get` for the concurrent-award limit
- [X] T039 [US2] Integration suite `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/RestrictedFundsItSuite.scala`:
      award → split bet → conversion over HTTP; `restricted_funds_awarded`, one
      `restricted_funds_converted` per record and `restricted_funds_forfeited` with `clawback_cents`
      on `money.transactions`; a cancel after a deposit during the bonus shows the FR-015a figure
      *Implementation note:* In `it/MoneyPathsItSuite`.

**Checkpoint**: every US1 and US2 Go table and fixture case passes; `restricted-funds.feature` green.

---

## Phase 5: User Story 3 — A deposit is confirmed and turnover is accounted (P1)

**Goal**: the granted deposit route with `may-deposit` first, exact deposit sums, turnover terms
resolved in the wallet, payment outcomes and the brand's `payments.payments` facts.

**Independent Test**: quickstart 3 (`deposit.test.tl`) and 5's deposit; `deposits.feature`;
`PaymentsItSuite`.

### Tests for User Story 3

- [X] T040 [P] [US3] Add `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/fixtures/DepositFixturesSuite.scala`
      over `fixtures/deposit.json`, the fixture's `turnover_multiplier`/`turnover_definition` becoming
      a `set-turnover-terms` on the kit's brand configuration first
- [X] T041 [P] [US3] Write `tests/original/wallet-driver/deposits_test.go`: one scenario per
      `features/wallet/deposits.feature` scenario and the shapes of `DepositUpdatesBalance`,
      `DepositIdempotent` and `TurnoverDisplayFollowsWalletPending` against the original's `deposit`,
      `bet`, `rollback`, `balance`
- [X] T042 [P] [US3] Add `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/DepositSumsSuite.scala`:
      sums over 1, 7 and 30 days exact at the boundaries, pruning by event time, a repeat deposit
      counted once, an adjustment never counted (FR-026); and
      `payments/PaymentEntitySuite.scala`: each outcome once, a repeated (payment id, outcome) answers
      the first with no event, `captured` refused as not this entity's
- [X] T043 [P] [US3] Bind `features/wallet/deposits.feature` in
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/features/DepositSteps.scala`

### Implementation for User Story 3

- [X] T044 [US3] Complete the `deposit` handler and add `refuse-deposit` and `set-turnover-override` in
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/WalletEntity.scala`: terms
      from the override else the brand terms the command carries else 1× `gross_wagered` (FR-004a),
      `DepositRecord` `CLEARED` at multiplier 0 else `PENDING`, turnover pending += required,
      `depositsSinceConversion` += amount on every cap record, the ordinal, `recentDeposits` pruned,
      deposit sums after in the reply; `refuse-deposit` persists `CommandRefused` under the payment id
      with the RG code; `TurnoverOverrideSet`
- [X] T045 [P] [US3] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/payments/PaymentEntity.scala`
      (event sourced, id = payment id, `PaymentOutcomeRecorded` per research R8, repeat answered with
      the first) and `publish/PaymentsPublisher.scala` (consumer → the seven `payments.payments`
      families of `contracts/facts.md`, `payment_provider` on every one); register both
- [X] T046 [US3] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/GrantedEndpoint.scala`
      (`Routes.service(Some(route))` for the two `Layout.BrandGrantsMade` paths): `POST …/deposits`
      reads `deposit-sums`, calls `mayDeposit`, then `deposit` or `refuse-deposit`, answering
      `EXCLUDED`/`COOLING_OFF {ends_at}`/`OVER_LIMIT {limit, used, remaining}`/`RG_UNAVAILABLE` as
      `contracts/routes.md` shows; `POST /v1/casino/brands/{brand}/payments/{paymentId}/outcome` →
      `PaymentEntity`; grantee named from `Layout` until ankka 040 ships (research R12's pattern)
- [X] T047 [US3] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/OperatorEndpoint.scala`
      (`Routes.operator(slug)`, role `brand_<slug>`): `PUT …/wallets/{player}/{currency}/turnover-override`,
      `PUT /v1/casino/backoffice/{brand}/wallet-config/turnover`, `GET …/wallet-config`; amounts as
      major-unit strings; actor from the token's `sub`
- [X] T048 [US3] Integration suite `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/DepositsItSuite.scala`:
      a deposit over the granted route → `deposit_confirmed` (ordinal 1, `real_balance_after`) and
      `payment.captured` on their topics; a repeat → the first body, no new fact; the fake RG
      refusing `OVER_LIMIT` → no credit, the refusal returned again on the repeat; an outcome
      `failed` → `payment.failed`; the deposit sums passed to the fake equal a direct sum (SC-007's
      local form)
      *Implementation note:* Split between `WalletItSuite` and `MoneyPathsItSuite`.

**Checkpoint**: quickstart 5 runs end to end from an empty wallet; `deposits.feature` green.

---

## Phase 6: User Story 4 — A withdrawal is held, released, confirmed or reversed (P2)

**Goal**: hold with its refusal order, forfeiture policies, single clawback, cap draw-down and
voids; preview; release, confirm, reverse.

**Independent Test**: quickstart 2 (`withdraw_test.go`) and 3 (the withdrawal fixtures);
`withdrawals.feature`.

### Tests for User Story 4

- [X] T049 [P] [US4] Port `ORIGINAL/…/funding/withdraw_test.go` to
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/funding/WithdrawTablesSuite.scala`
- [X] T050 [P] [US4] Add `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/fixtures/{CreateWithdrawalRequest,CancelWithdrawalRequest,RejectWithdrawalRequest,FailWithdrawalRequest,CompleteWithdrawalRequest,ReverseWithdrawalRequest}FixturesSuite.scala`
      over their JSON files (the three release outcomes map to `release` with `outcome`)
- [X] T051 [P] [US4] Write `tests/original/wallet-driver/withdrawals_test.go`: one scenario per
      `features/wallet/withdrawals.feature` scenario (each Examples row its own) against the
      original's `create-withdrawal-request`, `cancel-/reject-/fail-/complete-/reverse-withdrawal-request`
- [X] T052 [P] [US4] Extend `RefusalsSuite.scala` with `WITHDRAWAL_REQUEST_ALREADY_PENDING`,
      `INSUFFICIENT_FUNDS` (hold text), `WITHDRAWAL_BLOCKED_TURNOVER`,
      `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT` (naming both amounts), `REQUEST_NOT_PENDING`,
      `WITHDRAWAL_NOT_IN_COMPLETED_STATE`, and `IdempotencySuite.scala` with hold, release, confirm,
      reverse; add `entity/PreviewSuite.scala`: for every hold case the preview equals what the hold
      then does and persists nothing
- [X] T053 [P] [US4] Bind `features/wallet/withdrawals.feature` in
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/features/WithdrawalSteps.scala`

### Implementation for User Story 4

- [X] T054 [US4] Port `ORIGINAL/…/funding/withdraw.go` to
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/funding/Withdraw.scala`: the FR-023
      sequence (three refusals in order; forfeits by policy with `cancel_promotion` reporting the
      award; one clawback from the forfeited records' baselines only for `funds_and_winnings` or
      `cancel_promotion`; `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT`; cap draw-down deposits since
      conversion then allowance; `bonus_cap_void`; closing exhausted and all-cap-at-zero records;
      confirmation required) returning a `HoldPlan` the preview and the hold share
- [X] T055 [US4] Implement `hold`, `preview-hold` (query), `release`, `confirm`, `reverse` in
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/WalletEntity.scala`
      (`HoldPlaced` with forfeited, clawback, capDrawn, voided, closedRecords; `HoldReleased(outcome)`;
      `HoldConfirmed`; `WithdrawalReversed`; a confirmed request kept for FR-025)
- [X] T056 [US4] Add to `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ServiceEndpoint.scala`
      `POST …/holds`, `GET …/holds/preview?amount=`, `POST …/holds/{requestId}/release|confirm|reverse`
      with the bodies of `contracts/routes.md`
- [X] T057 [US4] Integration suite `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/WithdrawalsItSuite.scala`:
      `hold_placed` with `voided_cents`, `restricted_funds_forfeited` with the clawback,
      `hold_released`, `withdrawal_reversed` on the topic; a hold on a negative real balance refused;
      a release credited against a negative balance (Edge Cases)
      *Implementation note:* In `it/MoneyPathsItSuite`.

**Checkpoint**: `withdraw_test.go` and the six withdrawal fixture files pass; `withdrawals.feature` green.

---

## Phase 7: User Story 5 — An operator corrects a balance or a provider credits one (P2)

**Goal**: adjustments with a `Personal` reason and the actor; provider credits; the erasure handler.

**Independent Test**: quickstart 2 (`adjust_test.go`) and 3 (`admin-adjust`, `game-provider-credit`);
`adjustments.feature`.

### Tests for User Story 5

- [X] T058 [P] [US5] Port `ORIGINAL/…/funding/adjust_test.go` to
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/funding/AdjustTablesSuite.scala`
- [X] T059 [P] [US5] Add `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/fixtures/{AdminAdjust,GameProviderCredit}FixturesSuite.scala`
- [X] T060 [P] [US5] Write `tests/original/wallet-driver/adjustments_test.go`: one scenario per
      `features/wallet/adjustments.feature` scenario and the shapes of
      `ZeroedBonusClosesRecordAndGrant`, `PartialBonusAdjustLeavesRecordActive`, `StandaloneCredit`
      against the original's `admin-adjust` and `award`
- [X] T061 [P] [US5] Extend `RefusalsSuite.scala` with `REASON_REQUIRED`, `DEBIT_EXCEEDS_BALANCE`
      (naming both amounts), `NO_OPEN_RESTRICTED_FUNDS`; `IdempotencySuite.scala` with adjust and
      provider-credit; add `entity/PersonalSuite.scala`: the reason is `Personal[String]` under
      `PlayerSubject(playerId)` in the event, absent from the fact (T014's mapping) and the audit
      record
- [X] T062 [P] [US5] Bind `features/wallet/adjustments.feature` in
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/features/AdjustmentSteps.scala`

### Implementation for User Story 5

- [X] T063 [US5] Port `ORIGINAL/…/funding/adjust.go` and the `game-provider-credit` guest's logic to
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/funding/Adjust.scala`: real or the
      primary active record, debit refused above the chosen balance, zero as a no-op, a record
      zeroed with no open round forfeited (FR-026); provider credit to the primary active record else
      real (FR-027)
- [X] T064 [US5] Implement `adjust` and `provider-credit` in
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/WalletEntity.scala`
      (`AdminAdjusted` with `reason: Personal[String]`, `actorId`, `forfeited`; `ProviderCredited`),
      with `contract.personal.Personal` against the 042 marker until it ships
- [X] T065 [US5] Add `POST …/provider-credits` and `POST …/adjustments` (`support-agent`) to
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ServiceEndpoint.scala`, and
      `POST …/wallets/{player}/{currency}/adjustments` (reason `Personal`, amounts as strings, actor
      from the token) to `api/OperatorEndpoint.scala`
- [X] T066 [US5] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/Erasure.scala`:
      the 042 erasure handler for subject prefix `player/`, confirming with nothing destroyed beyond
      the subject's key (FR-033); as a confirming route until 042 ships; register it; integration
      test in `it/AdjustmentsItSuite.scala`: `admin_adjusted` carries the category and never the
      text; `provider_credited` carries `landed_on`; an erasure request is confirmed
      *Implementation note:* Erasure is `POST …/erasure/players/{playerId}`, admitted for `players` alone; the integration case is in `WalletItSuite`.

**Checkpoint**: `adjust_test.go`, both fixture files and `adjustments.feature` pass.

---

## Phase 8: User Story 6 — Reads: balance, status, transactions, sums (P2)

**Goal**: the three views, the player routes, the operator reads, and the sums `reporting` and
`responsible-gambling` display.

**Independent Test**: `reads.feature`; `ViewsItSuite` across a restart.

### Tests for User Story 6

- [X] T067 [P] [US6] Write `tests/original/wallet-driver/reads_test.go`: one scenario per
      `features/wallet/reads.feature` scenario against the original's `balance`, `bonus/status`,
      `transactions`, `transaction-sum`, `lifetime-stats` (the `FullPlayerJourney` transactions
      assertions as one scenario)
- [X] T068 [P] [US6] Bind `features/wallet/reads.feature` in
      `services/wallet/src/test/scala/io/eitheror/casino/wallet/features/ReadSteps.scala` (views
      asserted by retrying on the value that changes)

### Implementation for User Story 6

- [X] T069 [P] [US6] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/views/TransactionsView.scala`:
      one row per transaction with the columns of data-model.md (the original's `type` values, a
      hold as `withdrawal`, a reversal as `withdrawal_reversal`, a cap void as `bonus_cap_void`,
      `reason: Personal[String]`), query by wallet with optional type, wallet type, date range, paged
      by `createdAt desc, transactionId`
      *Implementation note:* A keyed view (`KeyedView`): a plain view keeps one row per wallet. Forfeit rows take the original's types (a voided `bonus_credit`; a hold's forfeit a `bonus_cancel`).
- [X] T070 [P] [US6] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/views/LifetimeTotalsView.scala`
      (one row per wallet: deposited, wagered, won) and `views/SumsView.scala` (one row per
      wallet, type and UTC day: total, count; a rollback does not reduce `bet`'s day)
- [X] T071 [US6] Add the reads to `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ServiceEndpoint.scala`
      (`GET …/sums?type=&from=&to=` summing `SumsView` rows, `GET …/deposit-sums`,
      `GET …/lifetime-totals`, `GET …/transactions?…` with `next_page`) and to `api/OperatorEndpoint.scala`
      (`GET …/balance|restricted-funds|transactions|lifetime-totals` as major-unit strings); register
      the views with `ProjectionRuntime()` in `WalletService.scala`
- [X] T072 [US6] Implement `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/PlayerEndpoint.scala`
      (`Routes.player(slug)`, player id from the token's `sub`, the brand realm only): `GET
      /v1/casino/players/{brand}/wallets/{currency}/balance|transactions|restricted-funds`, rendered as
      the original's player balance; another brand's realm → `wrong_brand`
- [X] T073 [US6] Integration suite `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/ViewsItSuite.scala`:
      a journey of deposit, bets, win, award, hold → every row type present with its fields; lifetime
      totals and sums equal a direct computation from the facts on the topic (SC-007); the player
      token of another player gets nothing; `restartService()` then the same reads
      *Implementation note:* In `it/MoneyPathsItSuite`.

**Checkpoint**: all 72 `features/wallet` scenarios and outlines bound and green; every view answers after a restart.

---

## Phase 9: Polish & cross-cutting

**Purpose**: the recordings and the parity run, the proofs that span every command, deployment,
documentation.

- [ ] T074 Run the wallet driver twice against the local original per `tests/original/README.md`
      (`cd tests && go test -tags e2e ./original/wallet-driver/... -count=1 -args -wallet-url …`),
      merge with `tests/original/merge` into `tests/port/wallet/`, run `just brands recordings check`,
      and commit the recordings with `EITHEROR_ORIGINAL_COMMIT` in each
      *Implementation note:* Not run: it needs the original running locally (docker compose of its full stack and its native services), driven with writes against its own data; ankka-port asks for that to be agreed first. The driver compiles, vets and passes its offline checks.
- [ ] T075 Correct `specs/002-wallet/contracts/wire.md`'s messages and
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/wire/Refusals.scala` to what the
      recordings show (the recording wins); mark in `specs/002-wallet/contracts/surface.md` any
      route the driver found that the surface lacks (depends on T074)
      *Implementation note:* Partly: wire.md and the refusal texts are corrected from the driver's reading of `WalletRoutes.scala`, the Go guests and the fixtures (DEBIT_EXCEEDS_BALANCE is 400; WITHDRAWAL_REQUEST_NOT_CANCELLABLE added). The recordings will confirm or correct them.
- [ ] T076 Run `cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.port.*"` until every
      recording in `tests/port/wallet/` passes or is marked with the divergence of plan.md it
      demonstrates; write the count passing/recorded into `specs/002-wallet/quickstart.md` section 7
      (depends on T075)
      *Implementation note:* Waits on T074.
- [X] T077 [P] Write `services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/EveryCommandIdempotentSuite.scala`
      (SC-006): for every `MoneyCommand` type, accepted and refused, a repeat returns the first outcome
      with no event and the same key with a changed field is `KEY_REUSED` with one `CommandRefused`;
      the suite enumerates the command enum so a new command cannot be left out
      *Implementation note:* `entity/EveryCommandIdempotentSuite`: 21 tests; the command list is checked against `MoneyCommand`'s subtypes.
- [X] T078 [P] Write `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/DurabilitySuite.scala`
      (SC-005's local form): replay a 10,000-event wallet built from the fixtures' commands, snapshot
      at 100, `restartService()`, balances and `seen` unchanged; measure and record the activation
      time and the snapshot size (research R4's figure) in `specs/002-wallet/research.md` R4
      *Implementation note:* `it/ScaleItSuite`, 10,000 transactions with `WALLET_PERF=1`: state 4.3 MB (443 bytes a key, not R4's 150), first answer after a restart 143 ms. It found that jsoniter refused to read back a state of more than 1,024 keys: fixed with `WalletCodecs.stored`. research.md R4 has the figures. **Superseded by T090**: the state is now bounded (Phase 10) and the figures are re-measured there.
- [X] T079 [P] Write `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/LatencySuite.scala`
      (SC-003): on a wallet with 10,000 transactions, 1,000 bets through the endpoint with the fake RG
      answering in 5 ms; assert median < 50 ms and p99 < 250 ms, print both; skipped unless
      `WALLET_PERF=1`
      *Implementation note:* Measured: median 4.4 ms, p99 147 ms over 1,000 bets on the 10,000-transaction wallet, `may-bet` included (the fake's). **Superseded by T090**: the bet path no longer asks `may-bet` and the state is bounded; re-measured there.
- [ ] T080 [P] Add the `wallet` service's deployment: confirm `brands/goldvibe/manifest.json`'s entry
      (image `europe-west1-docker.pkg.dev/eitheror/casino/wallet:0.1.0`, `expose: true`, the two
      granted routes already present), add `services/wallet/Dockerfile` from `ankka init`'s output,
      run quickstart section 8 on `dev-kind` and write the observed status lines into
      `specs/002-wallet/quickstart.md`
      *Implementation note:* Partly: `sbt Docker/publishLocal` builds `wallet:0.1.0-SNAPSHOT` (668 MB); the manifest's wallet entry is unchanged. Not applied: applying creates a project on an installation, whose id is never reusable.
- [X] T081 [P] Write `services/wallet/README.md` in full: the entity and its id, the commands by
      caller, the routes by audience, the topics, how to run locally (quickstart 5), how the parity
      suite is fed; and update `CLAUDE.md`'s "Working here" with the first service's conventions
      (`services/<name>/`, `just wallet-*`, the fixture converter runs once)
- [X] T082 [P] Update `specs/003-game-provider/spec.md`, `specs/007-payouts/spec.md`,
      `specs/011-rewards/spec.md` *Dependencies* with the wallet's wire names and paths as built
      (`contracts/routes.md`), dated today, so their recordings can be replayed against it
- [ ] T083 Run `just features` and `just test` from the root, then quickstart sections 1–7 in order,
      fixing what fails; document `open-migrated`'s body for spec 019 in `services/wallet/README.md`
      *Implementation note:* Sections 1 to 6 run and pass (543 tests, 55 ignored with reasons); 7 waits on T074 and 8's apply on T080.

---

## Phase 10: Bounded state and workflows (2026-10-10)

**Purpose**: the key store and the settled bets stop growing with the wallet's history; the lifetime
record of a key and the `responsible-gambling` preflight move to a workflow per keyed command at the
caller (spec clarifications 2026-10-10; research R4, R6, R17).

- [X] T084 Bound the state in `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/WalletState.scala`:
      `RecentKeys = 1000` with `keyOrder: Vector[String]` beside `seen`, the oldest evicted in
      `remember`; `SettledBound = 1000`; the state doc comment corrected (`BoundedFanOut` is not a
      retention depth). `InvariantsSuite`: a key stays until 1,000 later keys; `seen.keySet == keyOrder.toSet`
- [X] T085 Remove the bet path's `responsible-gambling` preflight and the `outcome-of` prelude from
      `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ServiceEndpoint.scala`; `WalletItSuite`'s
      silent-RG bet case becomes the deposit's; FR-007a is spec 003's
- [X] T086 Write `services/wallet/src/main/scala/io/eitheror/casino/wallet/deposits/DepositWorkflow.scala`
      (`deposit-flow`, id `player/currency/paymentId`; `start`, `status`; steps `check-rg`, `credit` with
      `maxRetries(2)`) and make the granted deposits route start it and poll `status` (1 ms, 2 s;
      `503 OUTCOME_PENDING` past the bound); register it in `WalletService.components`; the fake RG is
      handed to its factory in `it/Harness`
- [X] T087 Remove `refuse-deposit` and `RefuseDeposit` from `entity/Commands.scala`, `entity/WalletEntity.scala`
      and `entity/EveryCommandIdempotentSuite.scala` (the RG refusal is the workflow's record)
- [X] T088 [P] Keep the prototype `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/proto/BetWorkflow.scala`
      and `BetWorkflowItSuite.scala` (the shape spec 003 takes; its measurements are R17's) and the
      `Harness.components` hook a suite adds components through
- [X] T089 [P] Documents: `spec.md` (session 2026-10-10; FR-002, FR-004, FR-007a pointer, FR-022, SC-003,
      SC-006, Key Entities, Changes, Dependencies), `research.md` (R1, R4, R6, R17), `data-model.md`,
      `plan.md`, `contracts/routes.md`, `contracts/wire.md`, `quickstart.md`, `services/wallet/README.md`,
      `CLAUDE.md`; specs 003 (bet workflow, FR-001, FR-011, FR-012), 007 and 011 (Dependencies)
- [X] T090 Re-measure `services/wallet/src/test/scala/io/eitheror/casino/wallet/it/ScaleItSuite.scala` with
      `WALLET_PERF=1` after T084–T087: state size at 10,000 transactions, first answer after a restart,
      SC-003's median and p99 through the route; write them where `<!-- measure -->` stands in
      `research.md` R4, `quickstart.md` 6 and `services/wallet/README.md`.
      *Implementation note:* Measured 2026-10-10: state 552 KiB at 10,000 transactions (was 4.3 MB), first answer after a restart 241 ms, route median 2.0 ms and p99 16.4 ms (were 4.4 and 147 ms); the prototype workflow path on the same wallet 7.1 ms and 49.1 ms.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)**: none. T003 after T001 and T002; everything in Phase 2 after T003 and T004.
- **Foundational (2)**: after Setup. Blocks every story. T011 after T008–T010; T016 after T011
  and T015; the rest in parallel.
- **US1 (3)**: after Foundational. The MVP. T026 after T025; T027 after T026; T028 after T027 and
  T013; T029 last.
- **US2 (4)**: after US1 (T035 and T036 extend `PlaceBet.scala` and the entity's handlers). T037
  after T035, T036; T038 after T037; T039 last.
- **US3 (5)**: after Foundational only (T044 completes T011's `deposit`); T046 after T044, T045.
  Independent of US1 and US2.
- **US4 (6)**: after US2 (holds forfeit records and compute the clawback). T055 after T054; T056
  after T055; T057 last.
- **US5 (7)**: after US2 (adjustments touch records). T064 after T063; T065, T066 after T064.
- **US6 (8)**: after US3 and US4 (the row types cover deposits and holds); T071 after T069, T070.
- **Polish (9)**: T074 needs every driver file (T022, T032, T041, T051, T060, T067) and the original
  running locally; T075 → T076. T077–T082 after the stories they span. T083 last.

### Platform gates

- The granted routes (T046) name their grantee from `Layout` and `Personal` (T064, T069) is the
  contract's marker until ankka 040 and 042 ship; the erasure handler (T066) is a confirming route
  until 042. Nothing here blocks the parity suite (plan.md).
- The e2e recordings that enter through other services stay in `port/Blocked.scala` (T017) until
  specs 003, 007 and 011 exist; SC-001 completes then.

### Parallel Opportunities

- Phase 1: T001 and T002 together; then T003; T004–T007 together from the start.
- Phase 2: T008, T009, T010, T012, T013, T014, T015, T017, T018, T019 together; T011 after
  T008–T010; T016 after T011 and T015.
- US1: T020–T024 together; then T025 → T026 → T027 → T028 → T029.
- US2: T030–T034 together; T035 and T036 together; T037 → T038 → T039.
- US3: T040–T043 together with T045; T044 → T046 → T047 → T048. US3 can run beside US1.
- US4: T049–T053 together; T054 → T055 → T056 → T057.
- US5: T058–T062 together; T063 → T064 → T065 and T066.
- US6: T067, T068, T069, T070 together; T071 → T072 → T073.
- Polish: T077–T082 together after T076.

---

## Parallel Example: User Story 1

```bash
# the tests first, together (they fail: nothing is built):
Task: "LegsTablesSuite, CreditWinTablesSuite, RollbackTablesSuite, LossTablesSuite, OverflowSuite in services/wallet/src/test/scala/io/eitheror/casino/wallet/funding/"
Task: "PlaceBet/CreditWin/RollbackBet/SettleRoundAsLoss FixturesSuite in services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/fixtures/"
Task: "bets_test.go in tests/original/wallet-driver/"
Task: "IdempotencySuite and RefusalsSuite in services/wallet/src/test/scala/io/eitheror/casino/wallet/entity/"
Task: "BetSteps and MoneySteps in services/wallet/src/test/scala/io/eitheror/casino/wallet/features/"
# then in order:
Task: "funding/Legs, CreditWin, Rollback, Loss" → "funding/PlaceBet" → "WalletEntity bet handlers" → "api/ServiceEndpoint" → "it/BetsItSuite"
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phases 1 and 2: the contract published, the service compiling, every suite failing for the right reason.
2. Phase 3: a bet funded, settled, rolled back and lost on real balance through the provider routes,
   `may-bet` first, facts on the topic, 200 concurrent bets exact.
3. Stop and run quickstart 2, 3 and 5. That alone lets spec 003 (game-provider) start.

### Incremental delivery

1. US3 beside US1: deposits are the first money in and `payments`' granted hand-off.
2. US2: the restricted-funds rules, the densest evidence the original has (its Go tables and fixtures).
3. US4 and US5: money out and corrections; both need records.
4. US6: the views, once every row type exists.
5. Polish: record the original with the whole driver in one session, hold parity, deploy to goldvibe on kind.

### Recording early

The driver (T018) and its per-story files need only the original running locally. Record after each
story's driver file if the original is to hand; T074's merge takes several runs' output as long as each
scenario ran twice. Recording once at the end is the fallback, not the plan.

---

## Notes

- Every suite named here is parity evidence the next specs cite (`features/wallet`, `tests/port/wallet`,
  the fixture JSON); keep the names stable.
- Expected values come from the Go tables, the TL fixtures, the features and the recordings, never
  from the handler's output (ankka testing: "a test must be able to fail").
- A task that needs a component the plan's table does not have changes `plan.md`'s table first
  (ankka-design), then the code.
- `open-migrated` (T011, T016) exists for the fixtures' unmigrated cases and for spec 019's
  migration; it is a money command under a key like any other, named in the spec (FR-001) and in
  `surface.md` as new.
