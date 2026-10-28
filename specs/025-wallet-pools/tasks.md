# Tasks: Wallet pools

**Input**: Design documents from `specs/025-wallet-pools/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{facts,routes,wire}.md, quickstart.md

**Tests**: Included, as spec 002's were. The constitution makes the original's recorded behaviour
the acceptance test (I) and the living features the scenarios (VII); the port method writes the
parity test before the component it holds to account (ankka-port §7). Every story opens with the
original's own evidence ported (its Go tables, its fixtures, its driver scenarios) and the feature
bindings, then the implementation that makes them pass.

**Organization**: By user story, in spec order. US1 (the bet, the win, the rollback, the loss) and
US2 (every other movement) are P1; US3 (facts, reads, the migration, attribution) is P2. The pool
step and the fields it needs are foundational: every story records parts the same way.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US3 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `contract/` — `io.eitheror:casino-contract`; the facts at
  `contract/src/main/scala/io/eitheror/casino/contract/facts/MoneyTransactions.scala`, the schemas
  at `contract/schemas/`
- `services/wallet/` — main sources under `services/wallet/src/main/scala/io/eitheror/casino/wallet/`
  (below, `W/`), tests under `services/wallet/src/test/scala/io/eitheror/casino/wallet/` (below,
  `WT/`), fixtures under `services/wallet/src/test/resources/fixtures/`
- `tools/tl-fixtures/` — the converter; `tests/original/wallet-driver/` — the Go driver
- The original is `~/Documents/projects/eitheror/eitheror-platform` (below, `ORIGINAL`); its pool
  code at `ORIGINAL/starter-packs/wallet/handlers-go/internal/pools/` and
  `ORIGINAL/starter-packs/wallet/handlers-go/internal/funding/poolstep.go` (below, `HG/…`); its
  fixtures at `ORIGINAL/starter-packs/wallet/handlers/*.test.tl`
- `services/attribution/` exists on PR #11's branch (`.claude/worktrees/012-affiliate-protocol/`),
  not on `main`; its tasks run there or after the merge

---

## Phase 1: Setup

**Purpose**: the contract fields everything compiles against, published under a version no other
worktree overwrites.

- [X] T001 Add to `contract/src/main/scala/io/eitheror/casino/contract/facts/MoneyTransactions.scala`
      exactly what `contracts/facts.md` lists: on the `MoneyTransactionsFact` trait
      `depositPoolAfter: SignedMinorUnits` and `convertedPoolAfter: SignedMinorUnits`; on
      `DepositConfirmed`, `BetPlaced`, `WinCredited`, `BetRolledBack`, `RestrictedFundsConverted`,
      `RestrictedFundsForfeited`, `AdminAdjusted`, `HoldPlaced`, `HoldReleased`,
      `WithdrawalReversed` and `ProviderCredited` the fields `depositPartCents: MinorUnits` and
      `convertedPartCents: MinorUnits`; on `HoldPlaced` also `voidedDepositCents` and
      `voidedConvertedCents`. Every new field defaults to 0 (wire names `deposit_pool_after`,
      `converted_pool_after`, `deposit_part_cents`, `converted_part_cents`, `voided_deposit_cents`,
      `voided_converted_cents`). Spec 022's families, if present on this branch, get the trait's two
      fields by inheritance and nothing else
- [X] T002 Extend `contract/src/test/scala/io/eitheror/casino/contract/facts/FactsSuite.scala`: a round
      trip of every changed family with non-zero parts and pools, and one test that decodes a
      `bet_placed` and a `hold_placed` message written without any of the new fields (the JSON of
      today) and reads them as 0
- [X] T003 Run `just schemas` and keep the regenerated `contract/schemas/money.transactions.v1.json`;
      `sbt contract/test` green including `SchemaDriftSuite`; then
      `CASINO_VERSION=0.2.0-pools-SNAPSHOT sbt contract/publishLocal` and use that
      `CASINO_VERSION` for every `just service wallet …` below (memory: snapshot collisions)

---

## Phase 2: Foundational

**Purpose**: the pool arithmetic, the fields every event and plan carries, the fixtures the
original grew, and the test scaffolding the three stories bind into.

**⚠️ CRITICAL**: no story's handler can record parts until this phase is complete

- [X] T004 [P] Port `HG/internal/pools/pools_test.go` case for case to
      `WT/funding/PoolsSuite.scala`, plus the cases of `HG/internal/funding/poolstep.go` the Go
      tables cover (`stepBetPools` with `allowNegative`, `stepPoolsSeq` in the hold's order), as
      munit tests against `funding.Pools` of data-model.md: `credit`, `debit(convFirst)` refusing an
      amount above the total, `bet` with the shortfall charged to converted, `winSplit` (orphaned
      share to converted; `floor(rest × bet.deposit ÷ bet.realLeg)`; real leg 0 → converted),
      `restore`, and `mulDiv` on values whose product overflows 64 bits (add those to
      `WT/funding/OverflowSuite.scala` if that is where the overflow cases live). Fails to compile
      until T006
- [X] T005 [P] Extend `WT/entity/InvariantsSuite.scala`: over its random command sequences, after
      every event `depositPool + convertedPool == realBalance` and `depositPool >= 0`, and a
      negative `convertedPool` appears only after an allow-negative adjustment took real balance
      negative. Fails until T008–T010
- [X] T006 Implement `W/funding/Pools.scala` as data-model.md declares it (`Pools`, `Parts`, the
      `Pools` object), a line-for-line port of `HG/internal/pools/apply.go` and
      `HG/internal/funding/poolstep.go`; `mulDiv` via `Math.multiplyHigh` or `BigInt`, truncating;
      the refusal `POOLS_INVARIANT` with the message
      `deposit+converted would not equal real_balance` added to `W/entity/Refusals.scala`. T004 green
- [X] T007 Change `W/funding/Types.scala`: `Wallet` gains `pools: Pools`; `Plan` gains `parts: Parts`
      and `newPools: Pools`; `WinPlan`, `RollbackPlan`, `CancelPlan`, `AdjustPlan`, `CreditPlan`
      gain `parts` and `newPools`; `WithdrawPlan` gains `clawbackParts`, `voidedParts`, `paidParts`
      and `newPools`; `LossPlan` unchanged. Update the existing table suites in `WT/funding/` only
      as far as they must to compile (the split assertions come in the stories)
- [X] T008 Change `W/entity/Model.scala`: `Bet.parts: Parts`, `WithdrawalRequest.paidParts: Parts`
      and `voidedParts: Parts`, `Outcome.depositPool`, `Outcome.convertedPool`,
      `Outcome.parts: Option[Parts]`, `Balance.depositPool`, `Balance.convertedPool`; add the
      codecs for `Pools` and `Parts` to `W/WalletCodecs.scala`
- [X] T009 Change `W/entity/WalletEvent.scala` per data-model.md's events table: `depositAfter` and
      `convertedAfter` (default 0) on every money event, `parts` where real balance moves,
      `HoldPlaced.clawbackParts/voidedParts/paidParts`, `WalletOpenedFromMigration.depositPool/
      convertedPool`; no event renamed, every addition with a default so the suites' journals and
      the `it` harness's still decode
- [X] T010 Change `W/entity/WalletState.scala`: `depositPool`, `convertedPool` (default 0);
      `fundingWallet` carries `Pools(depositPool, convertedPool)`; the fold sets both from each
      event's `after` values and `require`s the invariant of T005 after every event; `Bet` and
      `WithdrawalRequest` parts kept in the state's maps
- [X] T011 Change `W/entity/Commands.scala`: `OpenMigrated.depositPool: Long` and
      `convertedPool: Long`; in `W/entity/WalletEntity.scala` refuse `POOLS_INVARIANT` when they do
      not sum to `realBalance`, and emit them on `WalletOpenedFromMigration`; the other handlers
      copy each plan's `parts` and `newPools` onto their events (the entity never computes a split).
      `WT/entity/WalletBasicsSuite.scala` and `WT/entity/EntityRulesSuite.scala` gain the opening
      with pools, the refusal, and a repeat under the same key answering the same pools
- [X] T012 [P] Teach `tools/tl-fixtures/src/main/scala/io/eitheror/casino/tools/tlfixtures/Parser.scala`
      the twin properties `deposit_pool_balance`, `converted_pool_balance`, `pools_stamped_at` and
      the transaction properties `funded_deposit_amount`, `funded_converted_amount`,
      `won_deposit_amount`, `won_converted_amount`, `from_deposit_amount`, `from_converted_amount`,
      `to_deposit_amount`, `to_converted_amount`, `voided_from_deposit_amount`,
      `voided_from_converted_amount` (read the names from the pool cases of
      `ORIGINAL/starter-packs/wallet/handlers/*.test.tl`); extend
      `tools/tl-fixtures/src/test/scala/io/eitheror/casino/tools/tlfixtures/ParserSuite.scala` with
      one pool case
- [X] T013 Run `just tl-fixtures` and keep every regenerated
      `services/wallet/src/test/resources/fixtures/*.json` (the converter refuses a partial run);
      then, in `WT/entity/fixtures/BetFixturesSuites.scala`, `MoneyInFixturesSuites.scala`,
      `RestrictedFundsFixturesSuites.scala` and `WithdrawalFixturesSuites.scala`, add every new
      non-pool case to its suite's `ignored` map with its group's reason from research R6 (two open
      or queued records / `open_restricted_funds_refs`; spec 251's cap close at zero real;
      withdrawal plans with cap draws, `requested_amount` and legacy voids; the per-deposit-record
      turnover ledger, including `deposit turnover — stamped wallet: deposit pool empty closes
      even with converted cash left`), each as "original feature after spec 002's conversion; a later amendment
      of spec 002", and the three cases `unstamped wallet — deposit writes no pool properties`,
      `pools — unstamped wallet writes no pool properties` and `pools — bet with no recorded split
      settles by the pools' ratio` as "no unstamped wallet (spec 025 clarification 2026-10-28)".
      `FixtureSuite`'s first test green in every suite; the pool cases fail until their stories
- [X] T014 Change `WT/entity/fixtures/FixtureRunner.scala`: `opening` reads the fixture twin's
      `deposit_pool_balance` and `converted_pool_balance` into `OpenMigrated` (absent → `0 / real`),
      and the assertions compare the twin's pools after the command and the transaction's part
      properties of T012 against the event's `parts`
- [X] T015 [P] Add to `tests/original/wallet-driver/api.go` the response fields the original serves:
      `DepositPoolBalance`, `ConvertedPoolBalance` on the balance and every money response,
      `FundedDepositAmount` on the bet response, `WonDepositAmount` on the win response, and the
      transaction row's `funded_*`, `won_*`, `from_*`, `to_*` amounts (names per
      `contracts/wire.md`); create `tests/original/wallet-driver/pools_test.go` with the file's
      header and the helpers that make a converted pool (an award played to conversion, as
      `restricted_funds_test.go` does) and an allow-negative shortfall
- [X] T016 [P] Extend `WT/port/WireTranslation.scala` and `WT/port/WireTranslationSuite.scala` with
      the translations of `contracts/wire.md` (`depositPoolBalance`, `convertedPoolBalance`,
      `fundedDepositAmount`, `wonDepositAmount` major → minor; the row amounts identity onto
      `depositPart` / `convertedPart`)
- [X] T017 [P] Create `WT/features/PoolsFeatures.scala` binding `features/wallet/pools.feature` on
      the `GherkinSuite` pattern of `WT/features/BetsFeatures.scala`: the Background and the steps
      the file uses (`a deposit pool of … and a converted pool of …` opens the wallet with those
      pools through `OpenMigrated`; `the bet's parts are …`, `the win's parts are …`, `the hold's
      … parts are …` read the last event; `the deposit pool is … and the converted pool is …` reads
      the state). Every scenario fails until its story

**Checkpoint**: `just service wallet compile` and `sbt "testOnly …funding.PoolsSuite"` green;
`InvariantsSuite`, the pool fixtures and `PoolsFeatures` red for the right reasons

---

## Phase 3: User Story 1 — A stake and its win are split between the pools (Priority: P1) 🎯 MVP

**Goal**: the bet's real leg converted-first with its parts on the bet, the win in the bet's ratio,
the rollback restoring the recorded parts, the loss moving nothing, the correction debit's
shortfall on the converted pool, and deposit turnover reduced by the deposit part.

**Independent Test**: the pool cases of `place-bet.json`, `credit-win.json`, `rollback-bet.json`
and `settle-round-as-loss.json` green; `PlaceBetTablesSuite`, `CreditWinTablesSuite`,
`RollbackTablesSuite` with their split assertions green; the ten US1 scenarios of
`features/wallet/pools.feature` green; the driver's ten US1 scenarios present.

### Tests for User Story 1

- [X] T018 [P] [US1] Add the split assertions of `HG/internal/funding/placebet_test.go`,
      `creditwin_test.go`, `rollback_test.go` and `pools_guests_test.go` (the bet, win and rollback
      cases) to `WT/funding/PlaceBetTablesSuite.scala`, `WT/funding/CreditWinTablesSuite.scala`
      and `WT/funding/RollbackTablesSuite.scala`: each table row asserts the plan's `parts` and
      `newPools`; the allow-negative correction case asserts a negative converted pool and a
      deposit pool of 0; the case `deposit turnover — stamped wallet: only the deposit-pool share
      of the stake counts` asserts `turnoverReduced` = the deposit
      part
- [X] T019 [P] [US1] Write the US1 scenarios of `tests/original/wallet-driver/pools_test.go`: the ten
      scenarios of `features/wallet/pools.feature` lines 12–75, named exactly as the feature names
      them, each reading the balance after every step and the bet's `fundedDepositAmount` and the
      win's `wonDepositAmount` and transaction rows where the feature names parts; `go vet` and
      `go test ./original/wallet-driver/...` against the stub green

### Implementation for User Story 1

- [X] T020 [US1] Port the pool step of `HG/internal/funding/placebet.go` (`:125–180`), `creditwin.go`
      (`:96`), `rollback.go` (`:81`) and `adjust.go`'s allow-negative path into
      `W/funding/Resolver.scala`: `placeBet` adds the conversion to the converted pool before
      splitting the real leg converted-first (shortfall to converted when allowed) and sets
      `turnoverReduced = min(parts.deposit, pending)` (FR-003a); `creditWin` splits the real share
      (orphaned to converted, rest by `winSplit` on `Bet.parts`); `rollback` restores `Bet.parts`
      (a gone record's leg to converted); `settleLoss` leaves the pools. T018 green
- [X] T021 [US1] In `W/entity/WalletEntity.scala`, carry `Plan.parts` onto `BetPlaced`'s `Bet`,
      `WinPlan.parts` onto `WinCredited`, `RollbackPlan.parts` onto `BetRolledBack`, and the new
      pools onto every event's `after` fields; `Outcome.parts` is the bet's real-leg parts or the
      win's real-share parts. The pool cases of `place-bet.json`, `credit-win.json`, `rollback-bet.json` and
      `settle-round-as-loss.json` green; `WT/entity/EveryCommandIdempotentSuite.scala` still green
- [X] T022 [US1] Bind the US1 steps in `WT/features/PoolsFeatures.scala` so the ten scenarios of
      lines 12–75 pass; `just features` 0 findings
- [X] T023 [US1] Extend `WT/it/MoneyPathsItSuite.scala`: a bet, a win and a rollback over Postgres
      answer `depositPoolBalance`, `convertedPoolBalance`, `fundedDepositAmount` and
      `wonDepositAmount` on the service routes (`W/wire/Wire.scala`'s `MoneyReply` gains the fields
      per `contracts/routes.md`; `W/api/ServiceEndpoint.scala` fills them from `Outcome`)

**Checkpoint**: US1 green at all three levels; the affiliate's GGR for a converted-funded round
is computable from the bet's and win's deposit parts

---

## Phase 4: User Story 2 — Every other movement follows the money's origin (Priority: P1)

**Goal**: deposit to deposit; the hold's clawback, void and paid in order; release and reversal
restoring; operator credit and debit; provider credit; cancel's clawback; the invariant held.

**Independent Test**: the pool cases of `deposit.json`, `complete-withdrawal-request.json`,
`admin-adjust.json`, `game-provider-credit.json` and `cancel-bonus.json` green;
`WithdrawTablesSuite`, `AdjustTablesSuite`, `CloseTablesSuite` with their split assertions green;
the eleven US2 scenarios green; the driver's US2 scenarios present.

### Tests for User Story 2

- [X] T024 [P] [US2] Add the split assertions of `HG/internal/funding/withdraw_test.go`,
      `adjust_test.go`, `close_test.go`, `complete_test.go` and the remaining `pools_guests_test.go`
      cases to `WT/funding/WithdrawTablesSuite.scala`, `WT/funding/AdjustTablesSuite.scala` and
      `WT/funding/CloseTablesSuite.scala`: the hold's `clawbackParts`, `voidedParts`, `paidParts` in
      that order (clawback and void converted-first, paid deposit-first), the operator's debit
      deposit-first and credit to deposit, the cancel's clawback converted-first, the provider
      credit to converted
- [X] T025 [P] [US2] Write the US2 scenarios of `tests/original/wallet-driver/pools_test.go`: the
      scenarios of `features/wallet/pools.feature` lines 77–137 named as the feature names them
      (the reversal scenario on a hold with nothing voided, research R11), each reading the balance
      after every step and the withdrawal and adjustment rows' `from_*` / `to_*` amounts; the
      invariant scenario (line 139) asserted inside each as `deposit + converted == real` on every
      balance read

### Implementation for User Story 2

- [X] T026 [US2] Port the pool steps of `HG/internal/funding/withdraw.go` (`:296–322`,
      `stepPoolsSeq`), `close.go` (`:95`), `adjust.go` (`:119/164`) and `complete.go` (`:47/140`)
      into `W/funding/Resolver.scala` and `W/funding/Clawback.scala`: `withdraw` records
      `clawbackParts`, `voidedParts`, `paidParts`; `cancel` the clawback's parts converted-first;
      `adjust` on real credit → deposit, debit deposit-first (restricted: no parts);
      `providerCredit` on real → converted (on a record: no parts); `deposit` all deposit. T024
      green
- [X] T027 [US2] In `W/entity/WalletEntity.scala` and `W/deposits/DepositWorkflow.scala`'s confirm
      path: `DepositConfirmed.parts = (amount, 0)`; `HoldPlaced` carries the three parts and
      `WithdrawalRequest` keeps `paidParts` and `voidedParts`; `HoldReleased.parts =
      paidParts + voidedParts`; `WithdrawalReversed.parts = paidParts`;
      `RestrictedFundsCancelled`, `AdminAdjusted`, `ProviderCredited` carry their plan's parts.
      The pool cases of `deposit.json`, `complete-withdrawal-request.json`, `admin-adjust.json`,
      `game-provider-credit.json` and `cancel-bonus.json` green; T005's `InvariantsSuite` green
- [X] T028 [US2] Bind the US2 steps in `WT/features/PoolsFeatures.scala` so the eleven scenarios of
      lines 77–142 pass (the hold scenario reads the hold's three parts; the invariant scenario
      reads the state after each movement of its outline)
- [X] T029 [US2] Extend `WT/it/WalletItSuite.scala`: a deposit, a hold with a void, its release, a
      second hold and its reversal, an operator credit and debit, a provider credit and an award's
      cancel over Postgres each answer `depositPoolBalance` and `convertedPoolBalance` on
      `MoneyReply`, and the holds preview answers `clawbackDepositAmount`, `voidedDepositAmount`
      and `paidDepositAmount` from `WithdrawPlan` without moving anything (the preview reply in
      `W/wire/Wire.scala`, filled in `W/api/ServiceEndpoint.scala`)

**Checkpoint**: every money command records parts; the invariant holds over the `it` harness's
whole run

---

## Phase 5: User Story 3 — Every wallet carries its pools and every reader can count them (Priority: P2)

**Goal**: the facts carry parts and pools, every audience's balance read and the transactions
view carry them, the migration opens a wallet with the original's pools, and `attribution` counts
the deposit part.

**Independent Test**: `MappingSuite`'s assertions on every family and `WalletItSuite`'s on the published facts; the
operator's and player's balance routes in `WalletItSuite`; `open-migrated` with and without pools;
the four US3 scenarios green; `attribution`'s `RealMoneySuite` on facts with parts.

### Tests for User Story 3

- [X] T030 [P] [US3] Extend `WT/publish/MappingSuite.scala`: for every event of data-model.md's
      table, the fact carries `depositPoolAfter`, `convertedPoolAfter` and, where the event has
      parts, `depositPartCents`, `convertedPartCents` (and `hold_placed`'s voided pair) equal to
      the event's; where it has none, the parts are 0
- [X] T031 [P] [US3] Write the balance scenario of `tests/original/wallet-driver/pools_test.go`
      (`features/wallet/pools.feature` line 150) reading the pools on the service, operator and
      player balance routes of the original (the player's with the player's token, as the
      original's `s253Pools` does); add the original's five pool e2e tests
      (`TestScenario15_St8Wallet_SplitRoundEarnsRealGGROnly`,
      `…_ConvertedMoneyRoundEarnsNoAffiliateGGR`, `…_MixedPoolsLossEarnsDepositShareOnly`,
      `…_MixedPoolsWinEarnsDepositShareOnly`, `…_CancelledRoundRestoresPoolsAndEarnsNothing`) to
      `WT/port/Blocked.scala` under spec 003 and spec 012, with the reason of research R7

### Implementation for User Story 3

- [X] T032 [US3] Change `W/publish/Facts.scala` so every money fact carries the pools after and its
      parts per `contracts/facts.md` (the `restricted_funds_forfeited` parts are the clawback's;
      `admin_adjusted`'s of |amount|; `hold_placed`'s voided pair from `voidedParts`). T030 green
- [X] T033 [P] [US3] Change `W/views/Views.scala`: `TransactionRow` gains `depositPart`,
      `convertedPart`, `depositPoolAfter`, `convertedPoolAfter` (0 where the movement has no
      parts), filled by the view's handlers; `WT/it/WalletItSuite.scala`'s transactions read
      asserts them on a bet and a hold row
- [X] T034 [P] [US3] Change `W/api/AudienceEndpoints.scala`: `MajorBalance` gains
      `depositPoolBalance` and `convertedPoolBalance` rendered by `Major` as two-decimal strings on
      the operator's and the player's balance routes; `MajorTransaction` gains the row's four fields
      rendered the same way; `WT/features/RouteFeatures.scala` or `WT/it/WalletItSuite.scala`
      asserts both audiences' balance reads
- [X] T035 [US3] Extend `WT/it/WalletItSuite.scala` with `open-migrated` over the service route:
      with pools (opened with them, the first balance read shows them, a negative converted pool
      carried as is), without pools in the body (opened `0 / real`), and a pair that does not sum
      (`POOLS_INVARIANT`, nothing opened). Bind the US3 steps in `WT/features/PoolsFeatures.scala`
      so the four scenarios of lines 144–166 pass
- [X] T036 [US3] On PR #11's branch (`.claude/worktrees/012-affiliate-protocol/`), change
      `services/attribution/src/main/scala/io/eitheror/casino/attribution/money/RealMoney.scala`:
      `counted` takes `depositPartCents` as the leg for `bet_placed`, `win_credited` and
      `bet_rolled_back`, the settling leg for `round_settled_as_loss`, nothing for
      `provider_credited`; `WalletCursor.scala` keeps the dedupe and drops the delta's use as an
      amount; `RealMoneySuite.scala` one test per family on facts with parts and one on a fact
      without them (0, not the real delta); spec 012's GGR feature scenarios green; the README's
      kept divergence on converted money removed; the contract bumped to T003's version in
      `services/attribution/build.sbt`. If PR #11 has merged, do this on this branch instead

**Checkpoint**: a converted-funded round published by the wallet is counted as 0 GGR by
`attribution` from the facts alone

---

## Phase 6: Polish and cross-cutting

- [X] T037 [P] Update `services/wallet/README.md`: the two pools in the entity's description, the
      parts on each command's event, the new reply fields per route, the fixture groups ignored
      with their reasons, and the pool scenarios of the driver
- [X] T038 [P] Add to `specs/019-spinvibe-migration/spec.md` the pre-cutover step of research R8: run the original's `pools-backfill`, stamp,
      export `deposit_pool_balance` / `converted_pool_balance` / `pools_stamped_at`, and pass them
      to `open-migrated`; unstamped → `0 / real`
- [X] T039 [P] Add to `specs/002-wallet/spec.md`'s *Changes from the original* (or a notes section)
      the finding of research R6, the four areas where the original moved on since the fixtures'
      conversion, with the ignored case names, as the input to a later amendment; and R11's
      reversal-amount check as an item for the T074 recording run
- [X] T040 Re-create dev-kind's `goldvibe` project (its journals predate the pools; research R3),
      `just modules apply goldvibe --installation dev-kind` with the wallet's new image, then run
      quickstart.md sections 5 and 7 through the gateway with `kubectl --context kind-ankka` only:
      a deposit, a bet and a balance read show the pools, and the `money.transactions` facts on the
      broker carry the new fields
- [X] T041 `just service wallet test`, `just wallet-it`, `WALLET_PERF=1 just wallet-perf` (within
      spec 002 SC-003), `just features`, `just test` from the root, then quickstart.md sections 1–6
      in order; record the outcomes in `specs/025-wallet-pools/quickstart.md`'s expectations where
      they differ

---

## Dependencies and execution order

- **Setup (T001–T003)** first: everything compiles against the contract.
- **Foundational (T004–T017)**: T006 before T007 (plans use `Parts`); T007 before T008–T011; T012
  before T013 before T014; T004, T005, T012, T015, T016, T017 in parallel with each other. Blocks
  every story.
- **US1 (T018–T023)** and **US2 (T024–T029)** both touch `W/funding/Resolver.scala` and
  `W/entity/WalletEntity.scala`; run their tests in parallel (T018, T019, T024, T025) and their
  implementation in order, US1 first. US2's `deposit` parts (T027) are needed by every story's
  feature scenario that starts from a deposit, so if a scenario of US1 needs them, pull T027's
  `DepositConfirmed` line forward.
- **US3 (T030–T036)** after US1 and US2: the facts and views carry what the entity records. T036
  after PR #11 merges, or on its branch.
- **Polish (T037–T041)** last; T040 after T035; T041 last of all.

## Parallel example: Foundational

```bash
Task: "Port pools_test.go to WT/funding/PoolsSuite.scala"                       # T004
Task: "Extend WT/entity/InvariantsSuite.scala with the pool invariants"          # T005
Task: "Teach tools/tl-fixtures Parser.scala the pool properties"                 # T012
Task: "Add the pool fields to tests/original/wallet-driver/api.go"               # T015
Task: "Extend WT/port/WireTranslation.scala"                                     # T016
Task: "Create WT/features/PoolsFeatures.scala"                                   # T017
```

## Parallel example: User Story 1

```bash
Task: "Split assertions in PlaceBetTablesSuite, CreditWinTablesSuite, RollbackTablesSuite"  # T018
Task: "US1 scenarios of tests/original/wallet-driver/pools_test.go"                         # T019
```

## Implementation strategy

1. Setup and Foundational: the contract, `Pools`, the fields, the fixtures reconverted with the
   ignored maps. Stop: `PoolsSuite` green, the invariant suite red for the right reason.
2. US1: the bet path. Stop: the affiliate's GGR for one round is computable from a bet's and a
   win's deposit parts. This is the MVP.
3. US2: every other movement. Stop: the invariant holds over the whole `it` harness.
4. US3: facts, reads, migration, attribution. Stop: a converted-funded round is 0 GGR end to end.
5. Polish: docs, dev-kind, the findings recorded for spec 002 and spec 019.

## Notes

- Nothing the wallet refuses, pays or publishes today changes (FR-014): every existing suite stays
  green through every phase; a red existing test is a defect in the task, not a case to ignore.
- The recording run (spec 002's T074) is not a task here: it is one run of the driver against a
  local original that makes spec 002's and this spec's recordings together; until it is made,
  `PortSuite` stays ignored and the pool parity evidence is the fixtures and the Go tables.
- Commit after each phase, not each task; do not commit without being asked.
