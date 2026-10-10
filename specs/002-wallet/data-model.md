# Data model: Wallet

**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md)

Amounts are `MinorUnits` (`Long`, never negative) or `SignedMinorUnits` where a balance may go below
zero (real balance after a correction debit). Rates are whole percents (`Int`). Times are `Instant`,
carried in events, never read in a fold. Field names are the JSON (`ankka-entities` rule 8) and are
protocol once a journal holds them.

## WalletEntity (event sourced, id `<playerId>/<currency>`)

### State

| Field | Type | Notes |
|---|---|---|
| `playerId`, `currency` | String, Currency | from the id; `currency` is ISO 4217 |
| `created` | Boolean | false until the first deposit or award; a bet on an uncreated wallet is `WALLET_NOT_FOUND` |
| `realBalance` | SignedMinorUnits | FR-003; negative only after a correction debit |
| `depositTurnoverPending` | MinorUnits | FR-010, FR-022, FR-023 |
| `turnoverOverride` | Option[TurnoverTerms] | the player's override, FR-004a |
| `records` | Vector[RestrictedFundsRecord] | every record ever awarded; open = `ACTIVE` or `CONVERTED_PENDING_CAP`; ordered by `(priority, grantedAt)` when read (FR-020) |
| `openBets` | Map[roundKey, Bet] | FR-010 |
| `settledBets` | bounded Vector[Bet] | the last 1,000 settled bets, for a late rollback (R1, `assumed`) |
| `withdrawals` | Map[requestId, WithdrawalRequest] | at most one `PENDING` (FR-023, FR-024, FR-025) |
| `recentDeposits` | Vector[DepositRecord] | the last 30 days by `confirmedAt`, pruned by event time (R5) |
| `depositOrdinal` | Int | count of confirmed deposits (FR-031) |
| `seen` | KeyedOutcomes[Outcome] | the most recent 1,000 keys → (digest, outcome) (FR-002, R4, R17); the lifetime record is the caller's workflow |
| `keyOrder` | Vector[String] | the keys of `seen` in insertion order; the oldest is evicted past 1,000 |

`restrictedBalance` is derived: the sum of open records' balances (FR-021). Never stored.

### RestrictedFundsRecord (in state)

| Field | Type | Notes |
|---|---|---|
| `id` | String | the award's transaction id |
| `grantRef`, `bonusRef` | String | `rewards`' grant id (the award key) and the bonus reference (`MISSING_BONUS_REF` when absent) |
| `status` | `ACTIVE` \| `CONVERTED_PENDING_CAP` \| `FORFEITED` \| `CLOSED` | |
| `priority` | Int | lower sorts first; `switch` sets `min(open priorities) − 1` (FR-019) |
| `grantedAt`, `expiresAt` | Instant, Option[Instant] | expiry is informational; `rewards` forfeits (Assumptions) |
| `balance` | MinorUnits | |
| `wageringRequirement`, `releaseProgress` | MinorUnits | requirement = amount × multiplier (FR-015) |
| `maxConvertible`, `remainingAllowance` | MinorUnits | allowance set at conversion (FR-009) |
| `depositsSinceConversion` | MinorUnits | FR-022, FR-023 |
| `realAtAward`, `totalDepositsAtAward` | Option[SignedMinorUnits], Option[MinorUnits] | the clawback baseline (FR-015a); `None` for a record migrated without one → clawback 0 (FR-017) |
| `openRoundCount` | Int | FR-013 |
| `rules` | RuleSnapshot | below |

### RuleSnapshot

`maxStake: Option[MinorUnits]`, `maxStakeEnforcement: block | cap`, `contributionDefault: Int`,
`contributionByGame/ByProvider/ByCategory: Map[String, Int]`, `scopeInclude`, `scopeExclude:
Set[String]` (game, provider or category keys), `violationMode: block | ignore`, `fundingOrder:
real_first | restricted_first | proportional | combined`, `winAllocation: follow_source |
all_restricted`, `releaseProgressScope: own | all_open`, `forfeitOnWithdrawal: none | funds |
funds_and_winnings | cancel_promotion`. The names are the resolver's (`rules.go`).

### Bet (in state while its round is open; a transaction after)

`roundKey`, `transactionId`, `amount`, `realLeg: MinorUnits`, `recordLegs: Vector[(recordId,
MinorUnits)]`, `contributions: Vector[(recordId, MinorUnits)]`, `walletType: real | bonus | split |
""`, `status: OPEN | SETTLED | VOIDED | LOSS`, `sessionId`, `gameId`, `placedAt`.

### WithdrawalRequest (in state)

`requestId`, `amount`, `status: PENDING | RELEASED | CONFIRMED | REVERSED`, `heldAt`, `closedAt`,
`forfeited: Vector[recordId]`, `clawbackApplied`, `voided`, `confirmationRequired: Boolean`.

### DepositRecord (in state for 30 days; a transaction forever)

`paymentReference`, `amount`, `multiplier: Int`, `definition: gross_wagered | …` (the original's
`turnover_definition` values), `requiredTurnover`, `status: PENDING | CLEARED`, `terminal`, `psp`,
`scheme`, `confirmedAt`.

### Outcome (what a key remembers)

`code: Option[String]` (None when accepted), `message`, `transactionId: Option[String]`,
`realBalanceAfter`, `restrictedBalanceAfter`, plus the command-specific reply fields the parity
recordings compare (legs, conversions, clawback, confirmation required). Stored as the reply the
endpoint rendered minus the wire framing.

### Events (past tense; a closed `enum`; manifest `wallet.<Event>`)

| Event | Carries | Fold |
|---|---|---|
| `WalletOpenedFromMigration` | key, realBalance, depositTurnoverPending, records (whole, baselines `None` unless the original kept one), totalDeposits, depositOrdinal, restrictedWithoutRecords, openBets, withdrawals, pendingRequestId, at | `created = true`; every field set; the unmigrated state when records are empty and the restricted balance is positive (FR-014) |
| `DepositConfirmed` | key, transactionId, amount, paymentReference, terminal, psp, scheme, terms, requiredTurnover, status, ordinal, sums after, balances after, at | real += amount; turnover pending += required; `recentDeposits` += (pruned); `depositsSinceConversion` += amount on cap records; ordinal |
| `BetPlaced` | key, transactionId, roundKey, gameId, sessionId, amount, realLeg, recordLegs, contributions, walletType, balanceBefore, balanceAfter, conversions, turnoverReduced, at | real −= realLeg; each record: balance −= leg, progress += contribution, openRoundCount += 1; converted records → `CONVERTED_PENDING_CAP` with allowance; turnover pending −= reduced; `openBets` += |
| `WinCredited` | key, transactionId, roundKey, amount, realShare, recordShares, balances after, at | real += realShare; records += shares; openRoundCount −= 1 per funding record; bet → `SETTLED`, moved to `settledRounds` |
| `BetRolledBack` | key, transactionId, roundKey, realRestored, recordRestored, contributionsReversed, balances after, at | restore legs; progress −= reversal (floored); counts; bet → `VOIDED` |
| `RoundSettledAsLoss` | key, transactionId, roundKey, forfeited: Vector[recordId], balances after, at | counts −= 1; named records → `FORFEITED`; bet → `LOSS` |
| `RestrictedFundsAwarded` | key, transactionId, record (whole), balances after, at | `records` += ; `created = true` |
| `RestrictedFundsForfeited` | key, transactionId, forfeits (record, amount), cause: forfeit \| loss \| adjustment, record updates, balances after, at | records → `FORFEITED`, balance 0 (a cancel is `RestrictedFundsCancelled`; a hold's forfeits ride on `HoldPlaced`) |
| `RestrictedFundsCancelled` | key, transactionId, recordId: Option, clawback, balanceBefore (sum of active), balances after, at | as forfeited with cause cancel; `bonus_cancel` row |
| `PendingCapClosed` | key, transactionId, recordId, at | record → `CLOSED` |
| `PrimarySwitched` | key, recordId, newPriority, at | priority |
| `HoldPlaced` | key, transactionId, requestId, amount, forfeited, clawback, capDrawn: Vector[(recordId, fromDeposits, fromAllowance)], voided, closedRecords, confirmationRequired, balances after, at | real −= amount; request `PENDING`; cap records drawn/closed |
| `HoldReleased` | key, transactionId, requestId, amount, outcome: cancelled \| rejected \| failed, balances after, at | real += amount; request `RELEASED` |
| `HoldConfirmed` | key, requestId, at | request `CONFIRMED` |
| `WithdrawalReversed` | key, transactionId, requestId, amount, balances after, at | real += amount; request `REVERSED` |
| `AdminAdjusted` | key, transactionId, target: real \| restricted, amount (signed), reason: Personal[String], actorId, recordId: Option, forfeited: Option[recordId], balances after, at | real or record ±; record → `FORFEITED` when zeroed with no open round |
| `ProviderCredited` | key, transactionId, amount, providerRef, reason, actorId, recordId: Option, balances after, at | record or real += |
| `TurnoverOverrideSet` | terms: Option, actorId, at | `turnoverOverride` |
| `CommandRefused` | key, digest, code, message, commandType, amount: Option, at | `seen` += (R3) |

Every money event also folds `seen += key → outcome`, evicting the oldest key past 1,000 (R4).
`balances after` on every event is what the publisher copies onto the fact (FR-031).

### Commands and refusals

| Command (wire name) | Caller | Refusals, in order |
|---|---|---|
| `open-migrated` | the migration (spec 019) | `WALLET_ALREADY_OPEN` when `created`; repeat → first outcome |
| `deposit` | `merchant`, via the endpoint's `DepositWorkflow` (its `credit` step) | `KEY_REUSED`; repeat → first outcome. An RG refusal is the workflow's outcome and never reaches the wallet (R6) |
| `place-bet` | `game-provider`'s bet workflow (spec 003) | `WALLET_NOT_FOUND`, `RESTRICTED_FUNDS_UNMIGRATED`, `SCOPE_BLOCKED`, `MAX_STAKE_EXCEEDED`, `INSUFFICIENT_FUNDS` (FR-006, FR-007, FR-014) |
| `credit-win` | `game-provider` | `ROUND_NOT_FOUND`; an optional `betKey` settles that bet alone, as the original's `bet_tx_id` (credit-win fixtures 15 to 17) |
| `rollback-bet` | `game-provider` | `ROUND_NOT_FOUND` |
| `settle-loss` | `game-provider` | `ROUND_NOT_FOUND`; optional `betKey` as for a win |
| `provider-credit` | `game-provider` | `WALLET_NOT_FOUND` |
| `award` | `rewards` | `MISSING_BONUS_REF`, `BONUS_ALREADY_ACTIVE` |
| `forfeit` | `rewards` | `NO_ACTIVE_BONUS` |
| `cancel` | `rewards` | `GRANT_NOT_OPEN` (named grant not active) |
| `close-pending-cap` | `rewards` | `NOT_PENDING_CAP` |
| `switch` | `rewards` | `GRANT_NOT_FOUND`, `GRANT_NOT_ACTIVE`, "already primary" (200, no change) |
| `hold` | `payouts` | `WITHDRAWAL_REQUEST_ALREADY_PENDING`, `INSUFFICIENT_FUNDS`, `WITHDRAWAL_BLOCKED_TURNOVER`, then forfeits, then `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT` (FR-023) |
| `preview-hold` (query) | `payouts` | none; returns what `hold` would answer |
| `release`, `confirm` | `payouts` | `REQUEST_NOT_PENDING` |
| `reverse` | `payouts` | `WITHDRAWAL_NOT_IN_COMPLETED_STATE` |
| `adjust` | operator, `support-agent` | `REASON_REQUIRED`, `DEBIT_EXCEEDS_BALANCE`, `NO_OPEN_RESTRICTED_FUNDS` |
| `set-turnover-override` | operator | none |
| `balance`, `restricted-funds-status`, `deposit-sums` (queries) | all readers | none |

Every command but the queries and `set-turnover-override` is a `MoneyCommand` carrying a `Key` and
`now`; the handler computes the digest from the command (R4) and answers from `seen` first. A key
older than the wallet's recent 1,000 is unknown to it: the caller's workflow answers that repeat (R17).

### Invariants (tested on every fold)

- `restrictedBalance == Σ open records' balance`; a record's balance is never negative.
- `openRoundCount == |open bets with a leg in the record|` for every record.
- At most one `PENDING` withdrawal request.
- `depositTurnoverPending ≥ 0`; never increased by a rollback.
- A key stays in `seen` until 1,000 later keys have been seen; `seen.keySet == keyOrder.toSet`.

## BrandConfigEntity (key value, id `brand`)

`contributionRateByGameType: Map[String, Int]` (default 100), `bonusWageringCounts: Boolean` (true),
`partialSplitsAllowed: Boolean` (true), `maxConcurrentAwards: Option[Int]` (None), `turnover:
TurnoverTerms` (multiplier 1, `gross_wagered`), `updatedBy`, `updatedAt`. Two commands,
`set-bet-switches` (`rewards`) and `set-turnover-terms` (operator), each replacing its part; one query.

## DepositWorkflow (workflow, id `<playerId>/<currency>/<paymentId>`; component `deposit-flow`)

The claim and the lifetime record of a deposit's payment id (R6, R17). State: `status: new | checking
| refused | done`, `request: Option[DepositStart]` (player, currency, the `deposit` command), `outcome:
Option[Outcome]`.

| Handler | Kind | Does |
|---|---|---|
| `start` | command | refuses `Conflict` unless `status == new`; sets `checking`, transitions to `check-rg` |
| `status` | query | the state; the endpoint polls it (1 ms, up to 2 s) until `outcome` is present |
| `check-rg` | step | reads the wallet's `deposit-sums` as of now, asks `may-deposit` (500 ms, fail closed); a refusal (`EXCLUDED`, `COOLING_OFF`, `OVER_LIMIT`, `RG_UNAVAILABLE`) sets `refused` with the refusal as `outcome` and ends; else transitions to `credit` |
| `credit` | step | calls the wallet's `deposit` under the payment id; sets `done` with the wallet's outcome and ends. `maxRetries(2)`: the wallet answers a repeated recent key with the first outcome |

Past the poll bound the route answers `503 OUTCOME_PENDING`; the workflow finishes on its own and the
retry is answered from its state. An ended workflow is never deleted.

## PaymentEntity (event sourced, id = payment id)

State: `playerId`, `paymentProvider`, `terminal`, `amount`, `currency`, `outcomes: Vector[Outcome]`.
Events: `PaymentOutcomeRecorded(outcome: initiated | failed | expired | abandoned | refunded |
dispute_opened | dispute_closed, fields the family needs, at)`; a repeated (payment id, outcome) is
answered with the first and persists nothing (an outcome's repeat needs no digest: its payload is the
family's and `merchant` is the only writer). `captured` is never recorded here (R8).

## Views (over WalletEntity events; exactly once)

`TransactionsView` and `SumsView` are keyed views (ankka `KeyedView`): one row per transaction, and
one per wallet, type and day, each written under a key the handler names, one change at a time. A
win, rollback or loss rewrites its bets' rows' status in the same change. `LifetimeTotalsView` is a
plain view, one row per wallet.

| View | Row | Query |
|---|---|---|
| `TransactionsView` | one per transaction: `transactionId`, `walletId`, `playerId`, `currency`, `type` (the original's: deposit, bet, win, rollback, loss, bonus_credit, bonus_cancel, bonus_cap_void, withdrawal, withdrawal_return, withdrawal_reversal, admin_adjust, game_provider_credit; a forfeit is a voided `bonus_credit` and a hold's forfeit a `bonus_cancel`, as the original's fixtures write them), `walletType`, `amount`, `balanceBefore`, `balanceAfter`, `status`, `paymentReference`, `roundKey`, `awardRef`, `reason: Personal[String]`, `terminal`, `paymentProvider`, `actorId`, `createdAt` | by wallet, optional type, wallet type, date range; paged by `createdAt desc, transactionId` |
| `LifetimeTotalsView` | one per wallet: `deposited`, `wagered`, `won` | by wallet |
| `SumsView` | one per (wallet, type, UTC day): `total`, `count` | by wallet, type, day range; the endpoint sums the rows |

A hold is a `withdrawal` row; a reversal a `withdrawal_reversal` row (new type: the original wrote
none, see spec *Changes from the original*); a cap void a `bonus_cap_void` row.

## Facts (consumers; at least once)

`MoneyTransactionsPublisher`: `WalletEntity` event → `MoneyTransactionsFact` (contract) →
`money.transactions`; `DepositConfirmed` → also `PaymentCaptured` → `payments.payments`.
`PaymentsPublisher`: `PaymentEntity` event → `PaymentsPaymentsFact`. `AuditPublisher`: every money
event and `CommandRefused` → `platform.audit`. Mapping in [contracts/facts.md](contracts/facts.md).

## State transitions

```text
RestrictedFundsRecord:  ACTIVE ──convert──▶ CONVERTED_PENDING_CAP ──close / allowance exhausted──▶ CLOSED
                        ACTIVE ──forfeit | cancel | hold policy | adjust to 0 | loss at 0──▶ FORFEITED
Bet:                    OPEN ──win──▶ SETTLED      OPEN ──rollback──▶ VOIDED      OPEN ──loss──▶ LOSS
                        SETTLED ──late rollback──▶ VOIDED (stake restored; win reversal is the provider's debit)
WithdrawalRequest:      PENDING ──release──▶ RELEASED      PENDING ──confirm──▶ CONFIRMED ──reverse──▶ REVERSED
DepositRecord:          PENDING ──turnover met──▶ CLEARED   (CLEARED at once when multiplier 0)
```
