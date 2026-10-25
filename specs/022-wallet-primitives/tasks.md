# Tasks: Wallet primitives — reservations, settlement, payouts and non-player accounts

**Input**: Design documents from `specs/022-wallet-primitives/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{routes,facts}.md, quickstart.md

**Tests**: Included. The constitution makes the living features the scenarios (VII) and spec 002's
suites the evidence that nothing the casino does changes (SC-001); there is no original to record
(research R11), so each story opens with the suites that hold it to the spec: the feature bindings,
the idempotency and invariants suites, the integration suite with the restart scenario. A suite is
written before the component it tests and fails first.

**Organization**: By user story, in spec order. US1, US2 and US3 are P1; US1 (reservations) is the
MVP, because every other move draws on the available amount it defines. US4 (reads) is P2 and
mostly falls out of the foundational phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US4 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `contract/` — `io.eitheror:casino-contract`, packages `io.eitheror.casino.contract.{facts,money,access}`
- `services/wallet/` — one sbt build; `M` = `services/wallet/src/main/scala/io/eitheror/casino/wallet`,
  `T` = `services/wallet/src/test/scala/io/eitheror/casino/wallet`
- `features/wallet/` — the living features; `GLOSSARY.md` — the domain's words
- Recipes: `just wallet-test` (publishes the contract, runs the unit suites), `just wallet-it`
  (Docker), `just wallet-perf`, `just schemas`, `just features`

---

## Phase 1: Setup

**Purpose**: the contract additions everything compiles against (contracts/facts.md).

- [X] T001 Add to `contract/src/main/scala/io/eitheror/casino/contract/facts/MoneyTransactions.scala`
      the seven wallet-keyed families extending `MoneyTransactionsFact` exactly as contracts/facts.md
      lists them (`ReservationPlaced`, `ReservationReleased`, `SettlementDebit`, `SettlementCredit`,
      `ResolutionPayment`, `AccountDebit`, `AccountCredit`, each with `reservedBalanceAfter:
      MinorUnits` beside the common fields) and the sealed trait `AccountFact extends Fact` with
      `AccountAdjusted` (`accountKind`, `accountName`, `currency`, `transactionId`, `amount:
      SignedMinorUnits`, `reason`, `actorId`, `balanceAfter: SignedMinorUnits`, `creditLine:
      MinorUnits`, `occurredAt`; `key = s"$accountKind/$accountName/${currency.code}"`); register all
      eight in `MoneyTransactions.families` with their family names
- [X] T002 Extend `contract/src/test/scala/io/eitheror/casino/contract/facts/FactsSuite.scala` with a
      sample per new family (the suite auto-tests every family's sample; add the eight samples), run
      `sbt contract/test`, then `just schemas` and commit the regenerated
      `contract/schemas/money.transactions.v1.json` so `SchemaDriftSuite` is green; confirm the diff
      adds definitions and removes none
- [X] T003 [P] Add `Routes.serviceNamed(grantedRoute: Option[GrantTarget.Route] = None): Acl` to
      `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`: `Routes.service`'s
      admission as `Acl.Authenticate`, yielding `Principal(s"service:$project/$name")` for an
      in-project `Caller.Service` and the grant's principal for a grantee of the named route,
      `Forbidden` otherwise; a test in `contract/src/test/scala/io/eitheror/casino/contract/access/`
      for the three cases (in-project, grantee, outsider)
- [X] T004 [P] Add the eleven rows of contracts/routes.md ("Rows for `contract/access/routes.md`") to
      `contract/access/routes.md`, in the wallet's block, columns as the table has them
- [X] T005 Run `sbt contract/publishLocal` and confirm `services/wallet` compiles against it (`just
      service wallet compile`)

---

## Phase 2: Foundational

**Purpose**: the available amount in the resolver and the wallet's state, and the shared shapes
every story writes to. Blocks all stories.

- [X] T006 Add `reserved: Long = 0L` to `Wallet` in `M/funding/Types.scala`; in
      `M/funding/Resolver.scala` make `placeBet` pass `w.realBalance - w.reserved` to `fundBet` and
      make `withdraw` compare `w.realBalance - w.reserved` and `realAfter - w.reserved` with
      `in.amount` (research R1); every absolute a plan returns stays in real terms
- [X] T007 Add resolver cases in `T/funding/` (beside the ported Go tables): a bet of 500 on real
      1,000 with 600 reserved is `InsufficientFunds`; a bet of 400 funds; a withdraw of 500 with 600
      reserved is `InsufficientFunds` in FR-023's order (after the pending-request check, before the
      turnover check); a withdraw of 400 pays with `newReal = 600`; run the whole `funding` package:
      every Go table still green with `reserved = 0`
- [X] T008 Add `Reservation(orderId, amount, placedAt)` and `reservations: Map[String, Reservation] =
      Map.empty` to `WalletState` in `M/entity/WalletState.scala`, with `def reserved`, `def
      available`, and `fundingWallet` passing `reserved` (data-model.md); `WalletCodecs.stored`
      already treats an empty map as transient, so the journal's existing states still decode
- [X] T009 [P] Add to `Outcome` in `M/entity/Model.scala` the optional fields `reserved`, `available`,
      `counterpartyTransactionId`, `counterpartyRealBalance`, `accountBalance`; add `reserved` and
      `available` to `Balance` and `available` to `HoldPreview`; make `WalletEntity.balance` and
      `previewHold` fill them (`M/entity/WalletEntity.scala`); make `accepted(...)` fill `reserved`
      and `available` from the state after
- [X] T010 [P] Add the refusal codes and messages to `M/entity/Refusals.scala`:
      `RESERVATION_NOT_FOUND`, `RELEASE_EXCEEDS_RESERVATION`, `RESERVATION_SHORT`,
      `ACCOUNT_NOT_FOUND`, `NOT_OWNER`, `CREDIT_LINE_REACHED`, `NOT_CLEARING`,
      `CREDIT_LINE_BELOW_BALANCE`, each with its message as data-model.md words it (both amounts
      named in the `details` map where the spec says so)
- [X] T011 [P] In `M/wire/Wire.scala` add `reserved: Option[Long]` and `available: Option[Long]` to
      `MoneyReply` (rendered from `Outcome`), and the statuses to `Wire.status`:
      `RESERVATION_NOT_FOUND` and `ACCOUNT_NOT_FOUND` 404, `NOT_OWNER` 403, `NOT_CLEARING` and
      `CREDIT_LINE_BELOW_BALANCE` 400 (contracts/routes.md)
- [X] T012 [P] In `M/views/Views.scala` add the seven `TransactionType` values (`reservation`,
      `reservation_release`, `settlement_debit`, `settlement_credit`, `resolution_payment`,
      `account_debit`, `account_credit`) and an optional `reference: String = ""` column to
      `TransactionRow` (rows already stored decode with the default through `WalletCodecs.stored`;
      the view's version does not change, and a test in `T/views/` reads a row written without the
      field); add `reference` to `MajorTransaction` in `M/api/AudienceEndpoints.scala`
- [X] T013 Add to `M/api/Flows.scala` the shared answer for a flow started under a key (research R4):
      `Flows.answer(start: => Unit, read: => (digest: String, outcome: Option[Outcome]),
      digest: String, commandType: String, amount: Long): Outcome` that claims, then on `Conflict`
      compares the flow's digest and answers the first outcome, `KEY_REUSED` naming the first
      command's type and amount, or `pending`; a unit test in `T/api/FlowsSuite.scala` with a fake
      flow for the three answers
- [X] T014 Extend `T/features/WalletFeatures.scala` with the step vocabulary the four features share:
      "the reserved amount is", "the available amount is", "a reservation of X is placed for order
      o1 [again]", "a release of X is placed for order o1", "a reservation transaction of X is
      written", and the `refusals` entries "release exceeds reservation" → `RELEASE_EXCEEDS_RESERVATION`,
      "reservation not found" → `RESERVATION_NOT_FOUND`, "credit line reached" →
      `CREDIT_LINE_REACHED`, "not owner" → `NOT_OWNER`; a `reserve(amount, order)` and
      `releaseReservation(order, sequence, amount)` helper; the suite compiles with the steps
      pending (unbound scenarios fail, which is the point)

**Checkpoint**: `just wallet-test` green except the four feature files' scenarios; every spec 002
scenario, Go table and fixture unchanged.

---

## Phase 3: User Story 1 - Money is reserved against an order and released (Priority: P1) 🎯 MVP

**Goal**: `reserve` and `release-reservation` on the wallet, keyed, with their facts and rows.

**Independent Test**: `features/wallet/reservations.feature`'s first five scenarios through
`WalletFeatures`; `EveryCommandIdempotentSuite` and `InvariantsSuite` with the two commands;
`POST W/reservations` and `POST W/reservations/release` through the integration kit.

### Tests for User Story 1

- [X] T015 [US1] Create `T/features/ReservationsFeatures.scala` (`extends
      WalletFeatures("reservations.feature")`, one subclass per feature file as the existing ones
      are) binding the five reservation scenarios of `features/wallet/reservations.feature` (set
      aside and reduce available; above available refused; release part and all; repeated returns
      the first outcome; restricted never reserved) to the unit kit; run them and see them fail on
      the missing commands
- [X] T016 [P] [US1] Add `Reserve` and `ReleaseReservation` cases to
      `T/entity/EveryCommandIdempotentSuite.scala` (accepted and refused first outcomes; a changed
      amount under the key is `KEY_REUSED`)
- [X] T017 [P] [US1] Add `ReserveS(amount, order)` and `ReleaseReservationS(order, amount)` steps to
      `T/entity/InvariantsSuite.scala` and the invariants `reserved == Σ reservations.amount`, no
      reservation at or below 0, `available ≥ 0` unless a correction debit or an `allowNegative` bet
      took it there, restricted balance untouched by the two commands
- [X] T018 [P] [US1] Add to `T/publish/MappingSuite.scala` the mapping of `ReservationPlaced` and
      `ReservationReleased` to `reservation_placed` and `reservation_released` with
      `reserved_balance_after` and `ce-id` the transaction id

### Implementation for User Story 1

- [X] T019 [US1] Add `Reserve(key, amount, at)` and `ReleaseReservation(key, orderId, amount, at)` to
      `M/entity/Commands.scala` as `MoneyCommand`s (digested; `orderId` is payload) and the events
      `ReservationPlaced` and `ReservationReleased` to `M/entity/WalletEvent.scala` with the fields
      data-model.md lists (`reservedAfter` included); register their serializers in
      `WalletEntity`'s companion through `Codecs.serializer`
- [X] T020 [US1] Implement `reserve` and `releaseReservation` in `M/entity/WalletEntity.scala` through
      `keyed`: `WALLET_NOT_FOUND`, `INSUFFICIENT_FUNDS` (`Messages.InsufficientRealBalance`) when
      `amount > available`; `RESERVATION_NOT_FOUND`, `RELEASE_EXCEEDS_RESERVATION` naming both
      amounts; the folds in `WalletState` (a release that empties a reservation removes it); the
      wire names `reserve` and `release-reservation` in the companion; `transactionId =
      Ids.transactionId(walletId, key)`
- [X] T021 [US1] Add the rows for the two events to `TransactionRows.of` in `M/views/Views.scala`
      (`type` `reservation` / `reservation_release`, `walletType = real`, before and after equal,
      `reference` the order id) and the sums under their names in `SumsView`
- [X] T022 [US1] Map the two events in `Facts.moneyTransactions` (`M/publish/Facts.scala`) per
      contracts/facts.md; `MappingSuite` green
- [X] T023 [US1] Add `POST W/reservations` (body `key`, `amount`) and `POST W/reservations/release`
      (body `orderId`, `sequence`, `amount`; key `<orderId>/<sequence>`) to
      `M/api/ServiceEndpoint.scala` through the `money(currency)` boundary; replies carry `reserved`
      and `available`
- [X] T024 [US1] Add to `T/it/` (an existing wallet integration suite or a new
      `ReservationsItSuite.scala`) a test through HTTP as `Caller.Service(project, "exchange")`:
      reserve 600 of 1,000, release 200, read balance (`reserved` 400, `available` 600), repeat the
      reserve (first outcome), reserve 600 again under a new key (`INSUFFICIENT_FUNDS`), and the
      two facts under the wallet's key; `just wallet-it`

**Checkpoint**: US1 scenarios green in the unit kit and over Postgres; spec 002's suites unchanged.

---

## Phase 4: User Story 2 - A trade settles between two players in one keyed command (Priority: P1)

**Goal**: `SettlementWorkflow` keyed by the trade across the buyer's and the seller's wallet,
completed on restart, one outcome for both.

**Independent Test**: `features/wallet/settlement.feature`'s four scenarios over the integration
kit, including the restart; `EveryCommandIdempotentSuite` with `SettleDebit` and `SettleCredit`.

### Tests for User Story 2

- [X] T025 [US2] Create `T/features/LedgerFeatures.scala` (an abstract `GherkinSuite(file)` over the
      `Harness`: `AnkkaTestKit`, Postgres, the shared step vocabulary) and
      `T/features/SettlementFeaturesItSuite.scala` (`extends LedgerFeatures("settlement.feature")`)
      binding `features/wallet/settlement.feature`: the
      Background (two wallets funded through `open-migrated` or deposits, 650 reserved on ada),
      "trade t1 of 600 is settled from order o1 of ada to bob [as the last trade of the order]
      [again]" (`POST /settlements`, `unused` 50 for the last trade), the balance and reserved reads
      per player, "a settlement transaction of 600 is written on each wallet" (the facts under both
      keys), "the service stops after the debit" (the step hook of T029 blocking `credit`) and "the
      service restarts" (`testKit.restartService()`, then the lifecycle query reports `Completed`);
      run it and see the four scenarios fail
- [X] T026 [P] [US2] Add `SettleDebit` and `SettleCredit` cases to
      `T/entity/EveryCommandIdempotentSuite.scala`
- [X] T027 [P] [US2] Add `SettleDebitS(order, amount, unused)` to `T/entity/InvariantsSuite.scala`
      (the single-wallet side: a settle-debit never takes available below 0 and never exceeds the
      reservation)
- [X] T028 [P] [US2] Add to `T/publish/MappingSuite.scala` the mapping of `SettlementDebited` and
      `SettlementCredited` to `settlement_debit` (with `released_cents` and the counterparty) and
      `settlement_credit`

### Implementation for User Story 2

- [X] T029 [US2] Add `SettleDebit(key, orderId, amount, unused, counterparty, at)` and
      `SettleCredit(key, amount, counterparty, at)` to `M/entity/Commands.scala`, the events
      `SettlementDebited` and `SettlementCredited` to `M/entity/WalletEvent.scala`, and the handlers
      `settleDebit` (`RESERVATION_NOT_FOUND`; `RESERVATION_SHORT` naming `amount + unused` and the
      reservation; real − amount; reservation − amount − unused, removed at 0) and `settleCredit`
      (`WALLET_NOT_FOUND`; real + amount) in `M/entity/WalletEntity.scala`, wire names
      `settle-debit` and `settle-credit`; rows in `TransactionRows.of` (`reference` the trade id)
- [X] T030 [US2] Create `M/settlement/SettlementWorkflow.scala` per data-model.md: `SettlementStart`,
      `SettlementFlow(status, request, digest, debit, outcome)`, component `settlement-flow`, id
      `<currency>/<tradeId>`, `start` (Conflict once started), steps `check` (seller's `balance`
      query → `WALLET_NOT_FOUND` ends refused), `debit` (buyer's `settle-debit`; a refusal ends the
      flow with it), `credit` (seller's `settle-credit`; the combined outcome, end); settings: step
      timeout 5 s, `maxRetries(3)` on `debit` and `credit`; query `status`; serializers through
      `WalletCodecs.stored`; a `private[wallet] val faults: AtomicReference[String => Unit]` test
      hook run at the top of each step (a no-op in production), as `DepositWorkflow.rg` is set
- [X] T031 [US2] Add `POST /settlements` to `M/api/ServiceEndpoint.scala`: boundary (`tradeId`,
      `currency`, `buyer`, `orderId`, `seller`, `amount`, `unused` default 0; buyer = seller is
      `INVALID_REQUEST`), `Flows.answer` over the flow's `start` and `status`, the reply body
      `{buyer{realBalance, reserved, available, txnId}, seller{realBalance, txnId}, currency}`
      (`M/wire/Wire.scala`, a `SettlementReply`)
- [X] T032 [US2] Register `SettlementWorkflow.descriptor` in `M/WalletService.scala`; map the two
      events in `M/publish/Facts.scala`; run `SettlementFeaturesItSuite`: the four settlement
      scenarios green, the restart one asserting `Completed` by the lifecycle query and bob at 600

**Checkpoint**: US1 and US2 green; a settlement repeated with a changed amount is `KEY_REUSED`.

---

## Phase 5: User Story 3 - Non-player accounts hold money that belongs to no player (Priority: P1)

**Goal**: `AccountEntity` with its floor and owner; `AccountMoveWorkflow` for splits, merges,
resolution payments and clearing moves; the operator's adjustment; the facts.

**Independent Test**: `features/wallet/accounts.feature`'s five scenarios over the integration kit;
`AccountEntitySuite` and `AccountInvariantsSuite` in the unit kit.

### Tests for User Story 3

- [X] T033 [P] [US3] Create `T/accounts/AccountEntitySuite.scala` (`EventSourcedTestKit`): a pool
      account is claimed by its first mover and refuses a second owner `NOT_OWNER`; a debit below 0
      is `INSUFFICIENT_FUNDS` naming amount, balance and floor; a clearing account with a line of 500
      takes a debit to −500 and refuses the next unit `CREDIT_LINE_REACHED`; `set-credit-line` on a
      pool is `NOT_CLEARING`, below the balance's shortfall `CREDIT_LINE_BELOW_BALANCE`, and on an
      absent account creates it under the setter (`AccountClaimed` then `CreditLineSet`); an
      adjustment without a reason is `REASON_REQUIRED`, past the floor `DEBIT_EXCEEDS_BALANCE`, of
      zero a no-op; every keyed command repeated answers the first outcome and `KEY_REUSED` on a
      changed one
- [X] T034 [P] [US3] Create `T/accounts/AccountInvariantsSuite.scala` (`ScalaCheckSuite`, 200
      sequences of claim, debit, credit, adjust, set-credit-line, repeat): `balance ≥ floor` after
      every event, `creditLine == 0` on a pool account, `owner` never changes, a key kept until 1,000
      later
- [X] T035 [US3] Create `T/features/AccountsFeaturesItSuite.scala` (`extends
      LedgerFeatures("accounts.feature")`) binding the five scenarios of
      `features/wallet/accounts.feature`: "the service exchange moves 100 from the wallet of ada into
      the pool account of market m1 in EUR" (`POST /accounts/pool/m1/splits` as
      `Caller.Service(project, "exchange")`), "the pool account ... exists, owned by the service
      exchange" and "holds" (`GET /accounts/pool/m1/balance?currency=EUR`), the Given "holds N"
      (seeded by splits from a funded wallet), "a resolution payment of 300 is paid to ada from the
      pool account of market m1 for position p1" (`POST .../payments`), the clearing Given "holds 0
      with a credit line of 500" (`PUT /accounts/clearing/north/credit-line` as
      `Caller.Service(project, "clearing")`, which creates the account under that caller: data-model.md
      `SetCreditLine`), "the service clearing moves 500 from the clearing account ... to the wallet of
      ada" (`POST .../moves`, `direction: to-wallet`), "an operator adjusts ... with the reason net
      settled and a signature" (the operator route with a staff token from `TestTokens`), "an
      adjustment transaction ... with the reason and the actor is written" (the `account_adjusted`
      fact under the account's key, category and actor); run and see them fail
- [X] T036 [P] [US3] Add `DebitToAccount` and `CreditFromAccount` cases to
      `T/entity/EveryCommandIdempotentSuite.scala`; add `MoveToAccountS(amount)` to
      `T/entity/InvariantsSuite.scala` (never below available)
- [X] T037 [P] [US3] Add to `T/publish/MappingSuite.scala` the mapping of `MovedToAccount` to
      `account_debit`, `MovedFromAccount` to `resolution_payment` (purpose `resolution_payment`:
      `market_id` = account name, `position_id` = reference) or `account_credit` (any other purpose),
      and a `T/publish/AccountMappingSuite.scala` for `AccountAdjusted` → `account_adjusted` under the
      account's key and the audit shapes of `AccountClaimed`, `CreditLineSet`, `CommandRefused`

### Implementation for User Story 3

- [X] T038 [US3] Create `M/accounts/AccountState.scala`, `AccountEvent.scala`, `AccountCommands.scala`
      per data-model.md: state (`accountId`, `created`, `owner`, `creditLine`, `balance`, `seen`,
      `keyOrder`; derived `kind`, `name`, `currency`, `floor`), the closed `enum AccountEvent`
      (`AccountClaimed`, `AccountDebited`, `AccountCredited`, `AccountAdjusted`, `CreditLineSet`,
      `CommandRefused`; manifests `wallet.account.<Event>`), the commands `Claim`, `Debit`, `Credit`,
      `Adjust`, `SetCreditLine` (the three money commands digested with `MoneyCommand.digest`'s
      rule), queries `AccountBalance`, `OutcomeOf`; codecs from `WalletCodecs.stored`
- [X] T039 [US3] Create `M/accounts/AccountEntity.scala` (component `account`, id
      `<kind>/<name>/<currency>`): the handlers with the refusal orders of data-model.md, `keyed`
      and `refuse` helpers as the wallet's, `Ids.transactionId(accountId, key)`, wire names `claim`,
      `debit`, `credit`, `adjust`, `set-credit-line`, `balance`, `outcome-of`; `AccountEntitySuite`
      and `AccountInvariantsSuite` green
- [X] T040 [US3] Add `DebitToAccount(key, accountKind, accountName, purpose, reference, amount, at)`
      and `CreditFromAccount(...)` to `M/entity/Commands.scala`, the events `MovedToAccount` and
      `MovedFromAccount` to `M/entity/WalletEvent.scala`, the handlers `debitToAccount`
      (`WALLET_NOT_FOUND`; `INSUFFICIENT_FUNDS` beyond available; real − amount) and
      `creditFromAccount` (`WALLET_NOT_FOUND`; real + amount) in `M/entity/WalletEntity.scala`, wire
      names `debit-to-account` and `credit-from-account`; rows in `TransactionRows.of` (`type`
      `account_debit` / `account_credit` / `resolution_payment` by purpose, `reference`)
- [X] T041 [US3] Create `M/accounts/AccountMoveWorkflow.scala` (component `account-move-flow`, id
      `<kind>/<name>/<currency>/<key>`): `AccountMoveStart` (account id, direction, purpose, player,
      amount, key, reference, caller principal, at), `AccountMoveFlow(status, request, digest, first,
      outcome)`, `start` (Conflict once started), steps for `to-account` (`claim` with the caller as
      owner → `NOT_OWNER` ends refused; `debit-wallet`; `credit-account`) and `from-account` (`check`
      the wallet's balance → `WALLET_NOT_FOUND`; `debit-account` with the owner and floor refusals;
      `credit-wallet`), settings as the settlement flow's, query `status`, the same test hook
- [X] T042 [US3] Add the account routes to `M/api/ServiceEndpoint.scala` under
      `withAcl(Routes.serviceNamed())` (the caller's principal read from the request as the owner):
      `POST /accounts/{kind}/{name}/splits`, `/merges`, `/payments` (key `<name>/<positionId>`), and
      under `withAcl(Routes.serviceNamed(Some(GrantTarget.Route("wallet", "POST", "/v1/casino/brands/
      {brand}/accounts/{kind}/{name}/moves"))))` the route `POST /accounts/{kind}/{name}/moves`
      (`direction` `to-wallet` | `from-wallet`) and likewise `PUT /accounts/{kind}/{name}/credit-line`;
      `GET /accounts/{kind}/{name}/balance?currency=`; a kind other than `pool` or `clearing`, and
      `splits`/`merges`/`payments` on a clearing account, are `INVALID_REQUEST`; each move through
      `Flows.answer`; the reply `{wallet{...}, account{balance, txnId}}` (`M/wire/Wire.scala`, an
      `AccountMoveReply`, `AccountBalanceReply`)
- [X] T043 [US3] Add to `OperatorEndpoint` in `M/api/AudienceEndpoints.scala` `POST
      /accounts/{kind}/{name}/adjustments` (body `key`, `currency`, `amount` signed major-unit string,
      `reason`, `category`; the actor `principal.subject`) and `GET
      /accounts/{kind}/{name}/balance?currency=` in major units
- [X] T044 [US3] Create `M/publish/AccountAuditPublisher.scala`: a consumer over `AccountEntity`
      publishing `AccountAdjusted` as `account_adjusted` on `money.transactions` (key the account's,
      `ce-id` the transaction id, `reason` the category) and `AccountClaimed`, `CreditLineSet`,
      `CommandRefused` on `platform.audit` in spec 001's audit shape (`ce-id` `<accountId>/<sequence>`);
      the pure mapping in `M/publish/Facts.scala` (`accountFacts`, `accountAudit`); map
      `MovedToAccount` and `MovedFromAccount` in `moneyTransactions`
- [X] T045 [US3] Register `AccountEntity`, `AccountMoveWorkflow` and `AccountAuditPublisher` in
      `M/WalletService.scala`; run `AccountsFeaturesItSuite`: the five account scenarios green, and
      the fold of contracts/facts.md over the published facts equal to each account's balance
      (assert it in the suite)

**Checkpoint**: US1–US3 green over Postgres; `just wallet-test` and `just wallet-it` green.

---

## Phase 6: User Story 4 - Holds, bets and reads know about reserved money (Priority: P2)

**Goal**: the hold, its preview and a bet refuse beyond available; balance and transactions report
the new amounts and types to every audience.

**Independent Test**: `reservations.feature`'s hold scenario and `reads.feature`'s un-ignored
balance scenario in the unit kit; the player's and operator's balance over HTTP.

### Tests for User Story 4

- [X] T046 [P] [US4] Bind in `T/features/ReservationsFeatures.scala` "a hold or a bet draws only on
      real balance that is not reserved" (`hold(500, "w1")` refused `INSUFFICIENT_FUNDS`; `hold(400,
      "w1")` leaves real 600, reserved 600; "the player places a bet of 500 in round r1" refused
      `INSUFFICIENT_FUNDS` through the existing `bet` helper, reserved still 600)
- [X] T047 [P] [US4] Remove `@ignore` and its comment from the scenario "a balance read reports the
      reserved and the available amount beside real balance" in `features/wallet/reads.feature`;
      bind it in the existing reads subclass of `WalletFeatures` (`T/features/`, the one constructed
      with `"reads.feature"`; major-unit strings "1000.00", "600.00", "400.00" through `Major`);
      `just features` still 0 findings
- [X] T048 [P] [US4] Add to the wallet's integration suite in `T/it/`: the player's `GET /balance`
      with a player token and the operator's `GET W/balance` with a staff token carry `reserved` and
      `available`; `GET W/transactions?type=reservation` lists the row with `reference` the order id;
      `GET W/holds/preview?amount=500` with 600 reserved of 1,000 answers the hold's
      `INSUFFICIENT_FUNDS` refusal and `available` 400; `GET W/deposit-sums` unchanged by a
      reservation; and in `WalletItSuite`'s erasure test, a wallet with a reservation is erased and
      its `reserved` amount is unchanged (spec Edge Cases; research R13)

### Implementation for User Story 4

- [X] T049 [US4] Add `reserved` and `available` (major-unit strings) to `MajorBalance` and its
      rendering in `M/api/AudienceEndpoints.scala` (both the operator's and the player's balance
      routes); `available` to the preview's wire body in `M/wire/Wire.scala`; `reference` to the
      operator's and player's transaction rows
- [X] T050 [US4] Accept the seven new types in the `type` filter of `TransactionsQuery.page`
      (`M/api/ServiceEndpoint.scala`) and the audiences' transaction routes; run T046–T048 green

**Checkpoint**: every scenario of the four feature files green; `just features` 0 findings.

---

## Phase 7: Polish & cross-cutting

- [ ] T051 Add the SC-002 test to `T/it/ScaleItSuite.scala` under `WALLET_PERF=1`: 500 reserve /
      release / settle / payment commands per second across 50 wallets for 20 s, median under 20 ms
      and p99 under 100 ms, printed and asserted; `just wallet-perf`
      *Written; not met (2026-10-25)*: at 501 a second with a market per wallet, reserve
      (8.4/41 ms) and release (11.5/46 ms) meet it; settle (95/278 ms) and payment (82/171 ms), each a
      workflow of three steps, do not. The suite asserts the target and fails until they do.
- [X] T052 [P] Update `services/wallet/README.md`: the components (the account entity, the two flows,
      the publisher), the routes, the suites, the test hook, and how the exchange calls the wallet
      (contracts/routes.md's port table)
- [X] T053 [P] Remove the `*Proposed.*` marker from the ten entries of `GLOSSARY.md` this feature
      builds (reservation, reserved amount, available amount, settlement, non-player account, pool
      account, clearing account, credit line, resolution payment, service principal), now that the
      terms are in use in the features and the code, leaving the definitions as they are; `just
      features` 0 findings
- [ ] T054 Run quickstart.md steps 1 to 8 end to end and fix what does not match; then step 9: build
      the wallet image, apply the brand on the kind installation (`--context kind-…`, never the
      default context), and run the eight `curl` calls of step 8 against it
      *Steps 1 to 8 run (2026-10-25)*; step 8 rewritten to what the service needs locally (a broker,
      an issuer, a `responsible-gambling`; port 9000). Step 9 not run: goldvibe has no project on
      kind-ankka, and applying creates one that is never reusable (as spec 002's T080).

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: starts now; T003 and T004 run beside T001–T002; T005 closes it.
- **Foundational (Phase 2)**: needs Phase 1; T006 → T007; T008 → T009; T010–T013 in parallel after
  T005; T014 after T009–T011. Blocks every story.
- **US1 (Phase 3)**: needs Phase 2. The MVP.
- **US2 (Phase 4)**: needs Phase 2 and T019–T020 (reservations to settle from).
- **US3 (Phase 5)**: needs Phase 2 and T019 (the `MoneyCommand` additions share `Commands.scala`);
  T033–T034 and T038–T039 (the account entity) can run beside US1.
- **US4 (Phase 6)**: needs T006–T009 only; its bindings need US1's commands (T046 binds a scenario
  in US1's `ReservationsFeatures`, so it runs after T015).
- **Polish (Phase 7)**: needs every story.

### Within each story

- Tests first (they fail on the missing component), then commands and events, then handlers, then
  flows, then routes, then facts, then registration.
- `Commands.scala`, `WalletEvent.scala`, `WalletEntity.scala`, `ServiceEndpoint.scala` and
  `Facts.scala` are touched by every story: sequence the tasks that edit them (T019/T020, T029,
  T040; T023, T031, T042; T022, T032, T044) and never run two of those in parallel.

### Parallel opportunities

- Phase 1: T003, T004 beside T001–T002.
- Phase 2: T009, T010, T011, T012, T013 after T008.
- US1: T016, T017, T018 beside T015.
- US2: T026, T027, T028 beside T025.
- US3: T033, T034, T036, T037 beside T035; T038–T039 beside US1's T019–T024.
- US4: T046, T047, T048 together.
- Polish: T052, T053 beside T051.

---

## Parallel Example: User Story 3

```bash
# The account entity's suites and the mapping suite, together, before the entity exists:
Task: "AccountEntitySuite in T/accounts/AccountEntitySuite.scala"
Task: "AccountInvariantsSuite in T/accounts/AccountInvariantsSuite.scala"
Task: "AccountMappingSuite in T/publish/AccountMappingSuite.scala"
Task: "DebitToAccount and CreditFromAccount cases in T/entity/EveryCommandIdempotentSuite.scala"

# Then the entity's three files, which share nothing with the wallet's:
Task: "AccountState, AccountEvent, AccountCommands in M/accounts/"
```

---

## Implementation Strategy

### MVP first (US1)

1. Phase 1 and Phase 2: the contract and the available amount; spec 002's suites still green.
2. Phase 3: reservations. Stop and validate: `just wallet-test`, `just wallet-it`, the two routes
   through the kind installation as `exchange`.

### Incremental delivery

1. US2 adds settlement: the first workflow and the restart scenario.
2. US3 adds the account entity and the move flow: the pool invariant has a home.
3. US4 closes the reads and the hold path.
4. Polish: SC-002, the README, the glossary, the kind run.

Each increment keeps SC-001: every spec 002 scenario, Go table and TL fixture passes unchanged at
every checkpoint, which is the parity this feature has.

---

## Notes

- Every money command added here must have a case in `EveryCommandIdempotentSuite`; the suite
  fails naming the one that lacks it.
- A flow's refusals come before its first write; if a step after the first write can refuse, the
  design is wrong, not the test.
- Assert a flow's end on the lifecycle query (`Completed`, `Failed`), never on its domain status.
- `account_debit` and `account_credit` name what happened to the wallet (plan.md, Divergences);
  the account's balance is the fold in contracts/facts.md and the suite checks the fold.
- The two clearing routes admit the project's services until the predictions module's manifest
  makes the grant; the suite calls them as `Caller.Service(project, "clearing")`.
- Commit after each phase or logical group; the branch is `022-wallet-primitives`.
