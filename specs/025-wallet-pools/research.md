# Research: Wallet pools

Decisions for spec 025, each with what was read. The original is
`~/Documents/projects/eitheror/eitheror-platform`; `HG` is its `starter-packs/wallet/handlers-go`.

## R1 — Where the pool step runs

**Decision**: in `wallet.funding`, inside the resolver's plans, exactly where the original's Go puts
it. `funding.Wallet` gains `pools: Pools` (`deposit`, `converted`); every plan that moves real
balance gains the `Parts` it decided (`Plan.parts`, `WinPlan.parts`, `RollbackPlan.parts`,
`WithdrawPlan.clawbackParts/voidedParts/paidParts`, `CancelPlan.parts`, `AdjustPlan.parts`,
`CreditPlan.parts`) and `newPools`. A new pure object `funding.Pools` is the port of
`HG/internal/pools/apply.go` (`debit` converted-first or deposit-first, `credit`, `winSplit`,
`mulDiv`) and of `poolstep.go` (`stepBetPools` with the allow-negative shortfall, `stepPoolsSeq`
for the hold's three movements). The entity copies parts and pools onto its events and never
computes a split itself.

**Rationale**: spec 002's resolver is "the original's Go ported line for line", and the original's
pool step is in that Go (`placebet.go:143`, `creditwin.go:96`, `rollback.go:81`,
`withdraw.go:304`, `close.go:95`, `adjust.go:119/164`, `complete.go:47/140`); the Go table tests
that already assert splits (`placebet_test.go`, `creditwin_test.go`, `rollback_test.go`,
`pools_guests_test.go`) port the same way. One implementation serves the fixtures, the features
and the entity.

**Alternatives**: a pool step in the entity after the resolver (one more place for money rules,
and the Go tables could not be ported as they are); a separate `pools` package called by the
entity (the same split of responsibility the original avoided when it moved the step into
`funding`).

## R2 — The rule table, handler by handler

Read from `apply.go` and the guests; the spec's FR-003 to FR-010. The arbiter where a case is
unclear is a recording (constitution I).

| Command | Movement | Pool rule | Recorded on |
|---|---|---|---|
| `place-bet` | real leg | converted pool += conversions first; then from converted first, remainder deposit; allow-negative shortfall to converted (converted may go negative, deposit never) | `Bet.parts`, `BetPlaced` |
| `credit-win` | real share | orphaned share → converted whole; rest in the bet's ratio, deposit part `floor(rest × bet.deposit ÷ bet.realLeg)`, converted the remainder; real leg 0 → converted | `WinCredited.parts` |
| `rollback-bet` | real restored | the bet's parts, whole; a leg whose record is gone → converted | `BetRolledBack.parts` |
| `settle-loss` | none | none | — |
| `deposit` | amount | deposit | `DepositConfirmed` (all deposit) |
| `hold` | clawback, void, paid, in that order | clawback converted-first; void converted-first; paid deposit-first | `HoldPlaced.clawbackParts/voidedParts/paidParts`; `WithdrawalRequest` keeps paid and voided parts |
| `release` | the held amount | paid + voided parts restored | `HoldReleased.parts` |
| `reverse` | what it credits | the paid parts restored (R11) | `WithdrawalReversed.parts` |
| `cancel` | clawback | converted-first | `RestrictedFundsCancelled.parts` |
| `adjust` on real | ±amount | credit → deposit; debit deposit-first | `AdminAdjusted.parts` |
| `adjust` on restricted, `award`, `forfeit`, `close-pending-cap`, `switch` | none on real | none | — |
| `provider-credit` on real | amount | converted | `ProviderCredited.parts` |
| `provider-credit` on a record | none on real | none | — |
| `open-migrated` | opening | the command's pools, or `0 / real` | `WalletOpenedFromMigration` |

Rounding: the win split is the one division, `mulDiv` without 64-bit overflow (`bits.Mul64`); in
Scala `Math.multiplyHigh`/`BigInt` for the product, truncating. Checked on every event: deposit +
converted = real (`WalletState` invariant), deposit ≥ 0.

## R3 — State, events and the journals that exist

**Decision**: `WalletState` gains `depositPool`, `convertedPool` (defaults 0); every money event
gains `depositAfter`, `convertedAfter` (defaults 0) and, where it moves real balance, its parts;
`Bet` gains `parts`; `WithdrawalRequest` gains `paidParts`, `voidedParts`;
`WalletOpenedFromMigration` gains `depositPool`, `convertedPool` (required; the command refuses a
pair that does not sum to the real balance). The fold sets the pools from the event's `after`
values, never recomputes them.

**Journals**: the ported wallet has no production journal. The only journals are kind's (goldvibe
on dev-kind, spec 012's run) and the suites'. A journal written before this change folds with
pools at 0 while real balance is not, which breaks the invariant for that wallet; the decision is
to re-create dev-kind's brand project when this deploys (its data is throwaway) rather than carry a
"wallet before pools" state. Spec 019's migration opens every real wallet with its pools (R8).

**Alternatives**: a per-wallet "stamped" flag as the original has (rejected by the spec:
clarification 2026-10-28); an upcast of old events to all-converted (a rule for data nobody keeps).

## R4 — The contract: fields, defaults, readers

**Decision**: `MoneyTransactionsFact` gains `depositPoolAfter: SignedMinorUnits` and
`convertedPoolAfter: SignedMinorUnits` (signed: the converted pool can be negative); each family
whose movement touches real balance gains `depositPartCents: MinorUnits` and
`convertedPartCents: MinorUnits`, the parts of its `amountCents` (for `admin_adjusted`, of
|`amount`|; for `restricted_funds_forfeited`, of `clawbackCents`); `hold_placed` also gains
`voidedDepositCents`, `voidedConvertedCents` so the paid parts are `amount − voided` part by part.
Every new field has a default of 0 in the case class, so a message written before this change
still decodes (spec 001 FR-013: additive). `just schemas` regenerates `money.transactions.v1.json`;
`SchemaDriftSuite` holds it. Published as `0.2.0-SNAPSHOT` with a unique `CASINO_VERSION` while
other worktrees build (memory: snapshot collisions).

**Readers today**: `attribution` (R5), `engagement`'s profile and `reporting`'s balance view (fold
`realBalanceAfter`, unchanged), `rewards` (deposit ordinal, unchanged). Spec 022's families, when
built, carry the same two `after` fields by the trait.

## R5 — `attribution` reads the parts

**Decision** (spec 012 FR-016, FR-030 as amended 2026-10-28): `RealMoney.counted` takes the fact's
`depositPartCents` as the leg: a `bet_placed` stakes its deposit part, a `win_credited` wins its
deposit part, a `bet_rolled_back` returns its deposit part, a `round_settled_as_loss` is the
settling leg, and `provider_credited` counts nothing. `WalletCursor` stays for what it also does,
the dedupe of a redelivered fact, and its delta is no longer the amount. One unit test per family
and the GGR feature scenarios on spec 012's branch.

**Where**: `services/attribution` is on PR #11's branch, not on `main`. The attribution tasks of
this spec run after that PR merges (or on its branch if it is still open when this is built), and
until then spec 012's service counts the real delta, as its PR says.

## R6 — The fixtures: reconvert, and what the original grew since 2026-10-09

**Finding**: `tools/tl-fixtures` ran on 2026-10-09. Since then the original's handler fixtures grew
from 174 to about 250 cases (`place-bet` 42 → 55, `credit-win` 21 → 27, `settle-round-as-loss`
16 → 21, `complete-withdrawal-request` 1 → 7, `fail`/`reject-withdrawal-request` 2 → 6 each, …).
The new cases fall in five groups, and only one is this spec's:

| Group | Cases | Disposition |
|---|---|---|
| **pools** (spec 253): converted-first, spill to deposit, allow-negative shortfall, win in the bet's ratio, rollback restores the split, deposit-first payout, deposit credits deposit, adjustment ± on the pools, provider credit to converted, deposit turnover by the deposit-pool share | 13 | ported here (FR-003 to FR-010, FR-003a) |
| pools, unstamped wallet (`writes no pool properties`, 3 cases) | 3 | ignored: no unstamped wallet here (spec Clarifications) |
| win on a bet with no recorded split (`settles by the pools' ratio`) | 1 | ignored: dropped with the unstamped state (*Changes from the original*) |
| two open records / queued records / `open_restricted_funds_refs` / cap closes at zero real (spec 251) / withdrawal plans with cap draws, `requested_amount`, legacy voids / per-deposit-record turnover ledger (869ev4rgz) | ~55 | ignored with the reason "original feature after spec 002's conversion; a later amendment of spec 002", named per case in the suites' `ignored` maps |

**Decision**: re-run the converter over every handler (it refuses a file whose case count differs
from its `test "` count, so a partial conversion is not an option), commit the JSON, port the 13
pool cases, and list every other new case as ignored with its group's reason. The converter must
learn the new twin properties (`deposit_pool_balance`, `converted_pool_balance`,
`pools_stamped_at`, `funded_deposit_amount`, `funded_converted_amount`, `won_*`, `from_*`, `to_*`,
`voided_from_*`), which `FixtureRunner` reads into the opening command's pools and asserts on the
transaction's parts. The ~55 other cases are a finding for Trevor: spec 002's port is behind the
original in four areas, and the pools are built on spec 002's model, not the original's current
one (spec *Assumptions*).

## R7 — Recordings

**Finding**: `tests/port/wallet/` does not exist; spec 002's T074 (the wallet driver's recording
run) has not been made, and `PortSuite` reports an ignored test until it is. The driver
(`tests/original/wallet-driver`) exists and runs against a stub.

**Decision**: the driver gains one scenario per scenario of `features/wallet/pools.feature` (its
convention: named exactly as the feature names them), reading the pools back from the original's
balance route (`depositPoolBalance`, `convertedPoolBalance`, major strings) and the parts from the
bet and win responses (`fundedDepositAmount`, `wonDepositAmount`); `WireTranslation` gains those
fields. (Found in implementation: the original's transactions route does not serve the rows'
`funded_*`, `won_*`, `from_*`, `to_*`; they are only on the transaction twins, so the driver
cannot record them for replay, and the decimal rule is the four fields' whole translation.) The recording run is spec 002's T074 and this spec's together: one run of the
driver against a local original makes both sets. Until it is made, the pool parity tests are the
fixtures and the Go tables, as spec 002's are.

The original's five pool e2e tests (`TestScenario15_St8Wallet_{SplitRoundEarnsRealGGROnly,
ConvertedMoneyRoundEarnsNoAffiliateGGR, MixedPoolsLossEarnsDepositShareOnly,
MixedPoolsWinEarnsDepositShareOnly, CancelledRoundRestoresPoolsAndEarnsNothing}`) enter through
the ST8 adapter and assert on the original's affiliate facts; they join `Blocked` as spec 003's
(the wallet-level assertions, `s253RequirePools`, are the balance route's) and spec 012's (the GGR
fact), and need the original's affiliate stack running to record (`skipIfSpec250StackMissing`).

## R8 — The migration's opening pools

**Decision**: `open-migrated` carries `depositPool` and `convertedPool`. Spec 019's export reads
the original's `deposit_pool_balance`, `converted_pool_balance` and `pools_stamped_at`; stamped →
the two values; unstamped → `0 / real_balance` (the original's 2026-10-15 decision). The command
refuses a pair whose sum is not the real balance (`POOLS_INVARIANT`, the original's own code).
Spec 019's plan (not yet written) gains the pre-cutover step "run the original's `pools-backfill`
and stamp" so that the all-converted fallback is the exception. `FixtureRunner.opening` reads the
fixture twin's pools the same way, which is how the fixtures start stamped.

## R9 — Deposit turnover by the deposit part (FR-003a)

**Read**: `HG/internal/funding/depositrecord.go` `AttributableStake` (the deposit-pool share of
the real leg on a stamped wallet) and `apply/records.go:413`, and the fixture "deposit turnover —
stamped wallet: only the deposit-pool share of the stake counts". The original applies it inside
its per-deposit-record ledger (869ev4rgz), which spec 002 does not have (one pending amount,
FR-010).

**Decision**: `Plan.turnoverReduced` (the entity's `reduced`) becomes `min(parts.deposit,
pending)`, nothing else of the ledger. The fixture's assertion on `deposit_turnover_pending`
(19000) holds against spec 002's model; its per-record mutations are not asserted (the runner
asserts balance-bearing values only).

## R10 — Wire names and audiences

**Decision**: the service routes' replies gain `depositPoolBalance`, `convertedPoolBalance` on
every money reply and the balance read; `fundedDepositAmount` on a bet's, `wonDepositAmount` on a
win's; minor units (the routes' convention), the original's names. Player and operator balance
routes render them as major strings, as they render real balance. `Outcome` carries them so a
repeat answers the same. The transactions view row gains `depositPart`, `convertedPart`,
`depositPoolAfter`, `convertedPoolAfter`.

The spec's first draft said the player's read is unchanged; the original serves the pools on the
player's route (`s253Pools` reads them with the player's token), so FR-013 was corrected before
this plan (constitution I).

## R11 — The hold's three movements, the release and the reversal

**Read**: `withdraw.go:304` applies `bonus_cancel` (clawback), `bonus_cap_void`, `withdrawal`
(paid) in that order with `stepPoolsSeq`; `cancel-withdrawal-request/main.go:58` restores
`ReturnParts()` = paid + voided parts; `reverse-withdrawal-request/main.go:54` restores
`PaidParts` only. Spec FR-008 says so.

**Finding to check at recording**: the rebuild's `reverse` credits `request.amount`, which is the
hold's whole amount (paid + voided); the original's `ReverseWithdrawal` credits `in.Amount`, which
its request twin keeps apart from `requested_amount`. If the recording shows the original
crediting the paid amount only, that is a spec 002 divergence to raise, not this spec's; this spec
restores the paid parts of whatever is credited and the test of the reversal scenario uses a hold
with nothing voided.

## R12 — Spec 022's movements

**Decision**: none here. The trait's two `after` fields reach 022's families by inheritance; the
parts of its movements and their tie-breaks are the spec's *Assumptions* for 022 to confirm (debits
converted-first, credits from another player or a non-player account to converted, a reservation
moving neither pool). 022 is a draft in another worktree; this plan adds nothing to its code.

## R13 — Tests

| Level | What | From |
|---|---|---|
| `wallet.funding` | `PoolsSuite`: `pools_test.go` ported case for case; the Go tables' split assertions added to the ported tables | `HG/internal/pools/pools_test.go`, `funding/*_test.go` |
| `wallet.entity.fixtures` | the 13 pool cases, asserting the wallet twin's pools and the transaction's parts | reconverted `fixtures/*.json` (R6) |
| `wallet.entity` | `InvariantsSuite` gains deposit + converted = real and deposit ≥ 0 over random sequences; `IdempotencySuite` unchanged (parts ride in `Outcome`) | — |
| `wallet.features` | `features/wallet/pools.feature` bound | the feature |
| `wallet.it` | facts carry parts and pools (`MappingSuite`, `WalletItSuite`), the views' rows, the balance routes per audience, `open-migrated` with and without pools | — |
| `contract` | `SchemaDriftSuite` after `just schemas`; a decode test of a message without the new fields | — |
| `wallet.port` | the pool scenarios of the driver's run, when T074 is made | R7 |
| `attribution` (PR #11's branch) | `RealMoney` per family; the GGR scenarios of spec 012 on facts with parts | R5 |
