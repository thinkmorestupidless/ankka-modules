# Data model: Wallet pools

Additions to spec 002's [data-model.md](../002-wallet/data-model.md). Amounts are minor units
(`Long`); a field named `*After` is the absolute value the fold sets, as spec 002's `realAfter` is.

## `wallet.funding` (pure)

```scala
final case class Pools(deposit: Long, converted: Long):
  def total: Long = deposit + converted

/** The deposit-origin and converted-origin amounts of one movement; they sum to the amount moved. */
final case class Parts(deposit: Long, converted: Long):
  def total: Long = deposit + converted

object Pools:
  def credit(p: Pools, s: Parts): Pools
  /** amount from converted first (convFirst) or deposit first; refused when amount > total. */
  def debit(p: Pools, amount: Long, convFirst: Boolean): Either[Deny, (Pools, Parts)]
  /** The bet: converted += convertedIn, then the real leg converted-first; with allowNegative the
    * shortfall beyond both pools is charged to converted (which may go negative), deposit never. */
  def bet(p: Pools, realLeg: Long, convertedIn: Long, allowNegative: Boolean): Either[Deny, (Pools, Parts)]
  /** The win's real share: orphaned → converted; rest in the bet's ratio, deposit = floor(rest × bet.deposit ÷ bet.realLeg). */
  def winSplit(p: Pools, realShare: Long, orphaned: Long, bet: Option[Parts]): Parts
  /** Restore recorded parts (rollback, release, reversal); parts unknown → all converted (rollback) / all deposit (return). */
  def restore(p: Pools, s: Parts): Pools
  /** floor(a × b ÷ c) without 64-bit overflow. */
  def mulDiv(a: Long, b: Long, c: Long): Long
```

`Wallet` gains `pools: Pools`. Plans gain:

| Plan | Fields |
|---|---|
| `Plan` (bet) | `parts: Parts`, `newPools: Pools`; `turnoverReduced` is `min(parts.deposit, pending)` (FR-003a) |
| `WinPlan` | `parts`, `newPools` |
| `RollbackPlan` | `parts` (the bet's, orphaned legs to converted), `newPools` |
| `LossPlan` | none (`newPools` = the wallet's) |
| `WithdrawPlan` | `clawbackParts`, `voidedParts`, `paidParts`, `newPools` (applied in that order) |
| `CancelPlan` | `parts` (the clawback's), `newPools` |
| `AdjustPlan` (real) | `parts`, `newPools`; on restricted none |
| `CreditPlan` (real) | `parts` (all converted), `newPools`; on a record none |

The `Deny` for a pool step that cannot keep the invariant is `POOLS_INVARIANT` with the original's
message `deposit+converted would not equal real_balance`; it is unreachable from a consistent
state and exists so a corrupt state refuses rather than drifts.

## `WalletState`

```text
depositPool: Long = 0, convertedPool: Long = 0
```

`fundingWallet` carries `Pools(depositPool, convertedPool)`. Invariant, checked on every fold:
`depositPool + convertedPool == realBalance` and `depositPool >= 0`.

## Model

- `Bet` gains `parts: Parts` (of `realLeg`).
- `WithdrawalRequest` gains `paidParts: Parts`, `voidedParts: Parts`; a release restores
  `paidParts + voidedParts`, a reversal `paidParts` (R11).
- `Outcome` gains `depositPool: Long`, `convertedPool: Long` (after) and `parts: Option[Parts]`
  (a bet's real-leg parts; a win's real-share parts; nothing on the other commands), so a repeat
  under a key answers the same `fundedDepositAmount` or `wonDepositAmount`. The hold preview's
  three deposit parts come from `WithdrawPlan` directly (the preview moves nothing and records no
  outcome).
- The conversion fact (`restricted_funds_converted`) has no event of its own: `Facts` derives it
  from `BetPlaced`'s record updates, and its parts are `(0, converted amount)`.
- `Balance` gains `depositPoolBalance`, `convertedPoolBalance` (the reply's names; found in
  implementation: they carry no default, because the reply's codec leaves a field at its default
  out and a reader must see a pool of 0).

## Commands

- `OpenMigrated` gains `depositPool: Long`, `convertedPool: Long`. Refused as `POOLS_INVARIANT`
  when their sum is not `realBalance`. The migration passes the original's stamped values, else
  `0 / realBalance`.

## Events

Every money event gains `depositAfter: Long = 0`, `convertedAfter: Long = 0`. Where real balance
moves, the event carries the parts:

| Event | Parts fields |
|---|---|
| `WalletOpenedFromMigration` | `depositPool`, `convertedPool` (the opening values; also the `after`) |
| `DepositConfirmed` | `parts` = `(amount, 0)` |
| `BetPlaced` | `bet.parts` (in `Bet`) |
| `WinCredited` | `parts` (of `realShare`) |
| `BetRolledBack` | `parts` (of `realRestored`) |
| `RoundSettledAsLoss` | none |
| `RestrictedFundsAwarded`, `RestrictedFundsForfeited`, `PendingCapClosed`, `PrimarySwitched` | none |
| `RestrictedFundsCancelled` | `parts` (of `clawback`) |
| `HoldPlaced` | `clawbackParts`, `voidedParts`, `paidParts` |
| `HoldReleased` | `parts` (= paid + voided) |
| `WithdrawalReversed` | `parts` (= paid) |
| `AdminAdjusted` (target real) | `parts` (of |amount|); target restricted: `Parts(0, 0)` |
| `ProviderCredited` (landed on real) | `parts` = `(0, amount)`; on a record `Parts(0, 0)` |
| `TurnoverOverrideSet`, `HoldConfirmed`, `CommandRefused` | none |

Field names are the journal's JSON: added with defaults, none renamed (R3).

## Views

`TransactionRow` gains `depositPart: Long`, `convertedPart: Long`, `depositPoolAfter: Long`,
`convertedPoolAfter: Long` (0 where the movement has no parts). `SumRow` and `LifetimeTotals`
unchanged. Found in implementation: the row's codec leaves a field at its default out, so a row
whose part or pool is 0 has no such field on the service route; a reader takes an absent one as 0.

## Facts (`contracts/facts.md`)

`MoneyTransactionsFact` gains `depositPoolAfter`, `convertedPoolAfter` (signed). The families that
move real balance gain `depositPartCents`, `convertedPartCents`; `hold_placed` also
`voidedDepositCents`, `voidedConvertedCents`. Found in implementation: each is an `Option`
defaulting to `None`, not a number defaulting to 0, so a message written before this change is told
from one whose part is 0 (the contract's compatibility rule); the wallet always writes them.

## State transitions

Unchanged from spec 002; the pools change on exactly the events the table above lists, by the
`after` values. A negative converted pool is a state, not a transition: it arises from an
allow-negative bet's shortfall and is refilled by conversions and converted-origin credits.
