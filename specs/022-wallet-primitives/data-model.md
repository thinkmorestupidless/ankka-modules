# Data model: Wallet primitives

**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md) | **Amends**: spec 002's
[data-model.md](../002-wallet/data-model.md)

Everything of spec 002 stands. This file lists what is added. Amounts are minor units as `Long`;
times are the command's `at`, set by the route (a handler never reads a clock); every stored type
uses `WalletCodecs.stored`; never rename a field, add with defaults.

## WalletEntity (event sourced, id `<playerId>/<currency>`) — additions

### State

| Field | Type | Notes |
|---|---|---|
| `reservations` | Map[String, Reservation] | by order id; present while any amount remains reserved (R1) |

Derived, never stored: `reserved = Σ reservations.amount`; `available = realBalance − reserved`.
`fundingWallet` becomes `Wallet(realBalance, depositTurnoverPending, restrictedBalance, reserved)`.

### Reservation (in state)

| Field | Type | Notes |
|---|---|---|
| `orderId` | String | the reservation's key |
| `amount` | Long | what remains reserved; falls on release and settlement; the entry goes at 0 |
| `placedAt` | Instant | the reserve's `at` |

### funding.Wallet (pure) — addition

| Field | Type | Notes |
|---|---|---|
| `reserved` | Long = 0 | `fundBet` draws real from `realBalance − reserved`; `withdraw` compares `realBalance − reserved` and `realAfter − reserved` with the amount; every absolute in a plan stays in real terms |

### Commands (all `MoneyCommand`: `key`, `at`; digested; refusals persisted)

| Command (wire) | Fields | Refusals, in order | Effect |
|---|---|---|---|
| `Reserve` (`reserve`) | `key` = order id, `amount` | `WALLET_NOT_FOUND`; `INSUFFICIENT_FUNDS` (`Messages.InsufficientRealBalance`) when `amount > available` | `ReservationPlaced`; real unchanged; reserved + amount |
| `ReleaseReservation` (`release-reservation`) | `key` = `<orderId>/<sequence>`, `orderId`, `amount` | `RESERVATION_NOT_FOUND`; `RELEASE_EXCEEDS_RESERVATION` naming `amount` and the remaining reservation | `ReservationReleased`; reserved − amount; entry removed at 0 |
| `SettleDebit` (`settle-debit`) | `key` = trade id, `orderId`, `amount`, `unused`, `counterparty` (the seller) | `RESERVATION_NOT_FOUND`; `RESERVATION_SHORT` naming `amount + unused` and the reservation | `SettlementDebited`; real − amount; reservation − amount − unused |
| `SettleCredit` (`settle-credit`) | `key` = trade id, `amount`, `counterparty` (the buyer) | `WALLET_NOT_FOUND` (the flow checked first; kept for the entity's own sake) | `SettlementCredited`; real + amount |
| `DebitToAccount` (`debit-to-account`) | `key`, `accountKind`, `accountName`, `purpose`, `reference`, `amount` | `WALLET_NOT_FOUND`; `INSUFFICIENT_FUNDS` when `amount > available` | `MovedToAccount`; real − amount |
| `CreditFromAccount` (`credit-from-account`) | `key`, `accountKind`, `accountName`, `purpose`, `reference`, `amount` | `WALLET_NOT_FOUND` | `MovedFromAccount`; real + amount |

`purpose` is one of `split`, `merge`, `resolution_payment`, `clearing`; `reference` is the position
id for a resolution payment and the caller's key otherwise. A zero amount is `INVALID_REQUEST` at the
boundary (spec 001 FR-026). Restricted funds are untouched by all six.

### Events (added to the `WalletEvent` enum; manifest `wallet.<Event>`)

Every event carries `key`, `digest`, `transactionId`, `at`, `outcome`, `realAfter`,
`restrictedAfter` and `reservedAfter`.

| Event | Fields beyond the common ones |
|---|---|
| `ReservationPlaced` | `orderId`, `amount` |
| `ReservationReleased` | `orderId`, `amount` |
| `SettlementDebited` | `tradeId`, `orderId`, `amount`, `unused`, `counterparty` |
| `SettlementCredited` | `tradeId`, `amount`, `counterparty` |
| `MovedToAccount` | `accountKind`, `accountName`, `purpose`, `reference`, `amount` |
| `MovedFromAccount` | `accountKind`, `accountName`, `purpose`, `reference`, `amount` |

`CommandRefused` is unchanged and records the new commands' refusals under their keys. Existing
events gain nothing (`reservedAfter` is on the new events only; a reader of an old event's reserved
amount folds the new ones).

### Outcome — additions

`reserved: Option[Long]`, `available: Option[Long]`, `counterpartyTransactionId: Option[String]`,
`counterpartyRealBalance: Option[Long]`, `accountBalance: Option[Long]`. `Balance` gains `reserved`
and `available`; `HoldPreview` gains `available`.

### Invariants (added to those tested on every fold)

- `reserved == Σ reservations.amount`; no reservation's amount is negative or zero (an entry at 0 is
  removed).
- `available ≥ 0` unless a correction debit (`adjust` on real) or an `allowNegative` bet took it there;
  a reserve, a settle-debit or a move to an account never does.
- Restricted balance is unchanged by every new command.

## AccountEntity (event sourced, id `<kind>/<name>/<currency>`; component `account`)

### State

| Field | Type | Notes |
|---|---|---|
| `accountId` | String | `pool/<marketId>/<currency>` or `clearing/<poolName>/<currency>` |
| `created` | Boolean | set by `AccountClaimed` |
| `owner` | String | the service principal of the first move, `service:<project>/<name>` |
| `creditLine` | Long = 0 | clearing accounts only; the floor is `−creditLine` (R7) |
| `balance` | Long | signed; ≥ floor |
| `seen`, `keyOrder` | KeyedOutcomes[Outcome], Vector[String] | the last 1,000 keys, as the wallet's |

Derived: `kind`, `name`, `currency` from the id; `floor = if kind == pool then 0 else −creditLine`.

### Commands

| Command (wire) | Caller | Fields | Refusals, in order | Effect |
|---|---|---|---|---|
| `Claim` (`claim`) | the move flow | `owner`, `at` | `NOT_OWNER` naming the owner when created under another | `AccountClaimed` when new; nothing when owned by `owner` |
| `Debit` (`debit`) | the move flow | `key`, `owner`, `amount`, `purpose`, `counterpartyPlayerId`, `reference`, `at` | `ACCOUNT_NOT_FOUND`; `NOT_OWNER`; `INSUFFICIENT_FUNDS` (pool) or `CREDIT_LINE_REACHED` (clearing) naming the amount, the balance and the floor | `AccountDebited`; balance − amount |
| `Credit` (`credit`) | the move flow | as `Debit` | `ACCOUNT_NOT_FOUND`; `NOT_OWNER` | `AccountCredited`; balance + amount |
| `Adjust` (`adjust`) | operator | `key`, `amount` (signed), `reason`, `category`, `actorId`, `at` | `ACCOUNT_NOT_FOUND`; `REASON_REQUIRED`; `DEBIT_EXCEEDS_BALANCE` naming both amounts when the floor would be passed; a zero amount answers balances and writes nothing | `AccountAdjusted`; balance ± amount |
| `SetCreditLine` (`set-credit-line`) | owner | `creditLine`, `owner`, `at` | `NOT_OWNER`; `NOT_CLEARING` on a pool account; `CREDIT_LINE_BELOW_BALANCE` when `balance < −creditLine` | `AccountClaimed` first when the account does not exist (the setter becomes the owner, as a first move would), then `CreditLineSet` |
| `balance` (query) | any | — | `ACCOUNT_NOT_FOUND` | balance, owner, credit line, floor, currency |
| `outcome-of` (query) | the route | `key` | — | the stored outcome |

`Debit`, `Credit` and `Adjust` are keyed and digested exactly as the wallet's (`KEY_REUSED`,
`CommandRefused`); `Claim` and `SetCreditLine` are not money commands and carry no key. The adjustment's
free-text `reason` is held in the event and never published (the fact carries the category, as spec
002's `admin_adjusted`); it is about an account, not a person, and is not marked `Personal`: the
operator route's documentation says the reason names no player, and the audit record carries the
category and the actor only.

Account transitions: *none* → (`claim` by the first mover, or `set-credit-line` by its first
setter) → *owned, balance 0*.

### Events (a closed `enum AccountEvent`; manifest `wallet.account.<Event>`)

| Event | Fields |
|---|---|
| `AccountClaimed` | `owner`, `at` |
| `AccountDebited` | `key`, `digest`, `transactionId`, `amount`, `purpose`, `counterpartyPlayerId`, `reference`, `balanceAfter`, `at`, `outcome` |
| `AccountCredited` | the same |
| `AccountAdjusted` | `key`, `digest`, `transactionId`, `amount` (signed), `reason`, `category`, `actorId`, `balanceAfter`, `creditLine`, `at`, `outcome` |
| `CreditLineSet` | `creditLine`, `actor`, `at` |
| `CommandRefused` | `key`, `digest`, `commandType`, `code`, `message`, `details`, `at`, `outcome` |

Transaction ids: `Ids.transactionId(accountId, key)`.

### Invariants (tested on every fold)

- `balance ≥ floor` after every event (an adjustment past the floor is refused
  `DEBIT_EXCEEDS_BALANCE`, so no event takes it there).
- `creditLine ≥ 0`; `creditLine == 0` on a pool account.
- `owner` never changes after `AccountClaimed`.
- A key stays in `seen` until 1,000 later keys have been seen.

## SettlementWorkflow (workflow, id `<currency>/<tradeId>`; component `settlement-flow`)

### State (`SettlementFlow`)

| Field | Type | Notes |
|---|---|---|
| `status` | `new` \| `checking` \| `debiting` \| `crediting` \| `done` \| `refused` | the domain's view; the engine's is the lifecycle query |
| `request` | Option[SettlementStart] | trade id, currency, buyer, order id, seller, amount, unused, at |
| `digest` | String | `MoneyCommand.digest("settle", request)`: what `KEY_REUSED` compares |
| `debit` | Option[Outcome] | the buyer's outcome, recorded in the same transition as the step's result |
| `outcome` | Option[Outcome] | present once there is an answer: both balances after, both transaction ids |

### Steps

| Step | Does | Then |
|---|---|---|
| `check` | the seller's `balance` query | absent → `refused` (`WALLET_NOT_FOUND`), end; else `debit` |
| `debit` | buyer's `settle-debit` (key = trade id) | refused → `refused` with that outcome, end; else record `debit`, `credit` |
| `credit` | seller's `settle-credit` (key = trade id) | `done` with the combined outcome, end |

Settings: default step timeout 5 s; `stepRecovery(debit, maxRetries(3))` and `stepRecovery(credit,
maxRetries(3))` (both keyed, so a re-run is answered, not repeated). A step that exhausts its retries
fails the flow; the route answers `OUTCOME_PENDING` and the exchange retries later (spec 023 FR-003
records and raises a refused or failed settlement). `start` is `Conflict` once started; the route
answers from `status` (R4). Lifecycle: `Running` → `Completed`, or `Failed` with the step named.

## AccountMoveWorkflow (workflow, id `<kind>/<name>/<currency>/<key>`; component `account-move-flow`)

### State (`AccountMoveFlow`)

| Field | Type | Notes |
|---|---|---|
| `status` | `new` \| `claiming` \| `checking` \| `debiting` \| `crediting` \| `done` \| `refused` | |
| `request` | Option[AccountMoveStart] | account id, direction (`to-account` \| `from-account`), purpose, player, amount, key, reference, caller principal, at |
| `digest`, `first`, `outcome` | as the settlement flow's | `first` is the debit side's outcome |

### Steps

| Direction | Step | Does | Then |
|---|---|---|---|
| to-account | `claim` | account's `claim(owner = caller)` | `NOT_OWNER` → refused, end; else `debit-wallet` |
| to-account | `debit-wallet` | wallet's `debit-to-account` | refused → refused, end; else `credit-account` |
| to-account | `credit-account` | account's `credit` | `done`, end |
| from-account | `check` | wallet's `balance` query | absent → refused (`WALLET_NOT_FOUND`), end; else `debit-account` |
| from-account | `debit-account` | account's `debit(owner = caller)` | refused (`ACCOUNT_NOT_FOUND`, `NOT_OWNER`, `INSUFFICIENT_FUNDS`, `CREDIT_LINE_REACHED`) → refused, end; else `credit-wallet` |
| from-account | `credit-wallet` | wallet's `credit-from-account` | `done`, end |

Settings as the settlement flow's. The purposes map to routes in [contracts/routes.md](contracts/routes.md):
split and a clearing move from a wallet are `to-account`; merge, resolution payment and a clearing move
to a wallet are `from-account`.

## Views — additions

`TransactionType` gains `reservation`, `reservation_release`, `settlement_debit`, `settlement_credit`,
`resolution_payment`, `account_debit`, `account_credit`. `TransactionRows.of` writes one row per new
wallet event: `walletType = real`, `amount` the event's amount, `balanceBefore`/`balanceAfter` the
real balance before and after (equal for a reservation and a release), `status = completed`,
`roundId` empty, `awardRef` empty; the order id, trade id, account or position ride in `reference`
(a new optional column, default empty). `SumsView` sums the new types under their names;
`LifetimeTotalsView` is unchanged. No view over `AccountEntity`.

## Facts

See [contracts/facts.md](contracts/facts.md): seven wallet-keyed families from the six wallet
events (`MovedFromAccount` yields `resolution_payment` or `account_credit` by purpose), one
account-keyed family from `AccountAdjusted`; the account's refusals and `CreditLineSet` to
`platform.audit`.

## State transitions

Reservation: *none* → (`reserve`) → *reserved(amount)* → (`release-reservation` of part) →
*reserved(less)* → (`release-reservation` of the rest, or `settle-debit` whose `amount + unused`
empties it) → *none*. A `settle-debit` of part leaves *reserved(less)*.

Account: *none* → (`claim` by the first mover, or `set-credit-line` by its first setter) → *owned,
balance 0* → (`debit`, `credit`, `adjust`, `set-credit-line`) → *owned*. No end state: never deleted.

Settlement flow: *new* → *checking* → *debiting* → *crediting* → *done*; or any of the first three
→ *refused*. Account move flow: *new* → *claiming* | *checking* → *debiting* → *crediting* → *done*,
or → *refused* before the first write.
