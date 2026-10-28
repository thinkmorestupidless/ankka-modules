# Feature Specification: Wallet pools — deposit-origin and converted money

**Feature Branch**: `025-wallet-pools`

**Created**: 2026-10-28

**Status**: Draft

**Input**: User description: "wallet pools: deposit-origin and converted-origin money kept as two
pools inside a player's real balance, so affiliate GGR counts deposit-origin money only. An
amendment to spec 002 (wallet) with the original's spec 253
(`~/Documents/projects/eitheror/eitheror-platform/specs/253-affiliate-real-money-pools`) as the
source; attribution (spec 012) reads the deposit pool's movement instead of the real balance's.
Sheldon's decision 2026-10-28: converted bonus funds never count as real/deposited money for
affiliates."

## Context

A wallet's real balance (spec 002) is one number. Money reaches it from the player's deposits,
from restricted funds whose wagering completed and converted, from wins on either, and from
operator and provider credits. The brand's affiliate protocol (spec 012) reports GGR to a network
per attributed player, and its parity run of 2026-10-28 kept one divergence from the original on
purpose: it counted every real-money stake and win, converted money included, because the ported
wallet could not say which part of a stake was deposited money. The original can. Its spec 253
(2026-10-14, decided by Trevor; 41 of 47 tasks done, the live rule in the wallet guests and the
affiliate chain, the production rollout not yet run) keeps two pools inside real balance, a
*deposit pool* and a *converted pool*, splits every stake and win between them, and counts only
the deposit pool's part towards affiliate GGR. Sheldon confirmed the rule for ankka on 2026-10-28:
converted bonus funds are never real or deposited money for affiliates.

This spec carries the pools into the wallet of spec 002. It amends spec 002's requirements where
a movement touches real balance, adds the pools and the parts of each movement to the wallet's
facts and reads, and gives the migration (spec 019) the opening pools. `attribution` (spec 012,
FR-016 and FR-030 as amended on 2026-10-28) then counts the deposit pool's movement and nothing
else; that change is spec 012's and is not restated here beyond the facts it reads.

The number is 025, not 024, because specs 022 and 023 already reserve 024 for the pool module of
predictions. The two "pools" are unrelated: spec 022's pool account is a market's non-player
ledger; this spec's pools are two parts of one player's real balance.

The rules are the original's, read from `handlers-go/internal/pools` (`apply.go`, the one
implementation of its research Q2 table) and the guests that call it (`placebet.go`,
`creditwin.go`, `rollback.go`, `withdraw.go`, `close.go`, `adjust.go`, `complete.go`); the
recordings under *Recordings* hold the port to them. Where a rule is a tie-break, it is the one
that gives the affiliate least (Trevor, 2026-10-14): a stake takes converted money first, a
withdrawal takes deposited money first.

## Clarifications

### Session 2026-10-28

- Q: Does money converted from restricted funds count in affiliate GGR once it is real? → A: No,
  never (Sheldon, 2026-10-28; spec 012 Clarifications of the same date). The wallet keeps the two
  pools and `attribution` reads the deposit pool's part of each stake and win (FR-012).
- Q: Which number does this spec take? → A: 025. 024 is the predictions pool module (specs 022
  and 023).
- Q: Must the wallet keep the original's "unstamped" state, in which a wallet has no pools and its
  movements carry no parts? → A: No. Every wallet in ankka has its pools from its first event:
  a deposit or an award opens it at a known composition, and the migration's opening command
  carries the original's pools or, for a wallet the original never stamped, all converted
  (FR-011, the original's decision of 2026-10-15). The fallback for a win on a bet that carries no
  parts is dropped with it (*Changes from the original*).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A stake and its win are split between the pools (Priority: P1)

A player holds deposited money and converted money in one real balance. When they bet, the real
leg of the stake is taken from the converted pool first and from the deposit pool only for the
remainder; both parts are recorded on the bet. A win on that round is split between the pools in
the stake's ratio, the deposit part rounded down; a rollback puts back exactly the parts the bet
recorded; a loss moves nothing. The affiliate's GGR for the round is the deposit part of the stake
less the deposit part of the win, which is zero for a player who never deposited.

**Why this priority**: this is the rule itself, and the one movement the affiliate protocol
counts. Without it every other story is bookkeeping.

**Independent Test**: replay the original's `TestScenario15_St8Wallet_SplitRoundEarnsRealGGROnly`,
`..._ConvertedMoneyRoundEarnsNoAffiliateGGR`, `..._MixedPoolsLossEarnsDepositShareOnly`,
`..._MixedPoolsWinEarnsDepositShareOnly` and `..._CancelledRoundRestoresPoolsAndEarnsNothing`
through the `game-provider` routes, and the pool cases of `place-bet.test.tl`,
`credit-win.test.tl` and `rollback-bet.test.tl` against the entity's handlers.

**Acceptance Scenarios**:

- added `features/wallet/pools.feature`: the real leg of a stake is taken from the converted pool first
- added `features/wallet/pools.feature`: a stake larger than the converted pool takes the remainder from the deposit pool
- added `features/wallet/pools.feature`: a stake funded only from restricted funds moves neither pool
- added `features/wallet/pools.feature`: a conversion joins the converted pool before the stake's real leg is split
- added `features/wallet/pools.feature`: a win is split in the stake's ratio with the deposit part rounded down
- added `features/wallet/pools.feature`: a win on a stake with no real leg moves neither pool while its record is active
- added `features/wallet/pools.feature`: a win's share for a record no longer active goes to the converted pool
- added `features/wallet/pools.feature`: a rollback restores the stake's recorded parts after later movements changed the pools
- added `features/wallet/pools.feature`: settling a round as a loss moves neither pool
- added `features/wallet/pools.feature`: a correction debit that takes real balance negative charges the shortfall to the converted pool

---

### User Story 2 - Every other movement follows the money's origin (Priority: P1)

A deposit joins the deposit pool. A conversion joins the converted pool. A withdrawal's paid amount
leaves the deposit pool first; the cap void and the clawback a hold applies leave the converted
pool first; a release or a reversal returns the money to the pools it was taken from. An operator's
credit joins the deposit pool and an operator's debit leaves it first; a provider credit to real
balance and the shares of a win whose record is gone are bonus-side money and join the converted
pool; a cancelled award's clawback leaves the converted pool first. Neither pool goes below zero,
except the converted pool when a correction debit is allowed to take real balance negative.

**Why this priority**: the pools are only right if every movement keeps them right; a wallet
whose pools stop summing to its real balance would misattribute every later round.

**Independent Test**: replay the pool cases of `deposit.test.tl`,
`create-withdrawal-request.test.tl`, the withdrawal lifecycle fixtures, `admin-adjust.test.tl`,
`game-provider-credit.test.tl` and `cancel-bonus.test.tl`, and the original's `pools_test.go`
table against the wallet's pool step.

**Acceptance Scenarios**:

- added `features/wallet/pools.feature`: a deposit joins the deposit pool
- added `features/wallet/pools.feature`: only the deposit part of a stake reduces deposit turnover pending
- added `features/wallet/pools.feature`: a hold takes the paid amount from the deposit pool first
- added `features/wallet/pools.feature`: a hold's cap void and clawback come from the converted pool first, before the paid amount
- added `features/wallet/pools.feature`: a release returns the hold's parts to the pools they were taken from
- added `features/wallet/pools.feature`: a reversal returns the hold's parts to the pools they were taken from
- added `features/wallet/pools.feature`: an operator's credit joins the deposit pool
- added `features/wallet/pools.feature`: an operator's debit comes from the deposit pool first
- added `features/wallet/pools.feature`: a provider credit to real balance joins the converted pool
- added `features/wallet/pools.feature`: cancelling an award takes its clawback from the converted pool first
- added `features/wallet/pools.feature`: the two pools always sum to the real balance

---

### User Story 3 - Every wallet carries its pools and every reader can count them (Priority: P2)

Finance, the backoffice and `attribution` can see, for any wallet, how much of the real balance is
deposited money and how much converted, and for any movement how much of it was each. Every fact
on `money.transactions` whose movement touches real balance carries the deposit-origin and
converted-origin parts of that movement and both pools after it, so `attribution` folds the
deposit parts of stakes and wins into GGR from facts alone and `reporting` (spec 015) can show the
pools. A wallet the migration opens carries the pools the original holds for it.

**Why this priority**: the live rule in Stories 1 and 2 is worth nothing to the affiliate protocol
until the facts carry it, and worth nothing to an existing player until the migration carries it.

**Independent Test**: the leak and contract suites of the wallet (every family's schema), the
`open-migrated` recordings of spec 019 with the original's pools present and absent, and spec 012's
GGR scenarios on a feed built from facts that carry the parts.

**Acceptance Scenarios**:

- added `features/wallet/pools.feature`: every fact whose movement touches real balance carries its parts and both pools after it
- added `features/wallet/pools.feature`: the balance read reports both pools beside the real balance
- added `features/wallet/pools.feature`: a wallet opened by the migration carries the original's pools
- added `features/wallet/pools.feature`: a wallet opened by the migration from a wallet the original never stamped is all converted

---

### Edge Cases

- A conversion inside the bet that completes wagering: the converted amount joins the converted
  pool before the real leg is split, so the leg is taken from the just-converted money first
  (FR-003). The real leg itself was drawn from real balance before the conversion (spec 002
  FR-009, FR-010); only its attribution sees the new composition.
- A win's rounding: the only division is the win split; the deposit part is the floor of real
  share × the stake's deposit part ÷ the stake's real leg, and the converted part the remainder,
  so the parts always sum to the real share and rounding never favours the affiliate.
- A win on a stake whose real leg is zero (funded from restricted funds alone): its real share is
  zero unless a share is orphaned to real, and an orphaned share joins the converted pool.
- A rollback after later movements changed the composition: it restores exactly the parts the bet
  recorded, not a share recomputed from the pools of the moment; a leg whose record is gone is
  restored to the converted pool.
- A correction debit allowed to take real balance negative (spec 002 FR-006): the leg takes the
  converted pool to zero, then the deposit pool to zero, and the shortfall beyond both is charged
  to the converted pool, which goes negative by that amount; the deposit pool never does. The pools
  still sum to the (negative) real balance; a later deposit joins the deposit pool and leaves the
  converted pool negative until conversions and converted-origin wins refill it.
- An operator's debit larger than the deposit pool: the deposit pool is drained and the remainder
  leaves the converted pool; both pools together short is `DEBIT_EXCEEDS_BALANCE` as today.
- A hold whose cap void and clawback exhaust the converted pool: the remainder of each leaves the
  deposit pool; then the paid amount leaves the deposit pool first.
- A release of a hold that voided something: the paid and voided parts are returned together,
  since the release credits the full held amount (spec 002 FR-024).
- A wallet opened by the migration with the original's pools and a negative converted pool: carried
  as is; the invariant is the sum, not the sign of the converted pool.
- A wallet the original never stamped (its backfill did not reconcile it, or spec 253 had not
  rolled out when the export was taken): opens with a deposit pool of zero and a converted pool
  equal to its real balance, as the original's decision of 2026-10-15 stamps it. Affiliates were
  not live when that money was played, so none of it earns commission.
- Two bets in flight on one wallet: the split is decided in the command that funds the stake, so
  the pools and the real balance can never disagree (spec 002 SC-002 covers both).

## Requirements *(mandatory)*

### Functional Requirements

Each requirement names the spec 002 requirement it amends and what established it: *recorded*
(a recording under *Recordings* replays it), *read* (from the original's code, the file named),
*decided* (a decision, dated), or *assumed* (*Assumptions* says why).

**The pools**

- **FR-001**: Every wallet MUST carry two amounts inside its real balance, a deposit pool and a
  converted pool, whose sum equals the real balance after every event of the wallet, from its first
  event; a wallet opened by a deposit opens with the amount in its deposit pool, one opened by an
  award with both pools at zero. *Amends spec 002 FR-001 and FR-021 (the second invariant beside
  restricted balance). Read: the original's `data-model.md` §1 invariant and `poolstep.go`, which
  refuses any step whose pools would not sum to the new real balance; decided 2026-10-28 that no
  wallet is unstamped (Clarifications).*
- **FR-002**: Every amount of a pool and every part of a movement MUST be a whole number of minor
  units in 64 bits, with the one division (FR-004) a truncating integer division computed without
  overflow. *Amends spec 002 FR-003. Read: `apply.go` `mulDiv`.*

**Bets and rounds**

- **FR-003**: A bet MUST first add to the converted pool the balance of every record that converts
  on it (spec 002 FR-009), then take its real leg from the converted pool first and from the deposit
  pool for the remainder, and MUST record the two parts on the bet beside its legs. A bet the
  caller allows to take real balance negative (spec 002 FR-006) MUST charge the shortfall beyond
  both pools to the converted pool; the deposit pool never goes below zero. *Amends spec 002 FR-006,
  FR-009 and FR-010. Recorded: `SplitRoundEarnsRealGGROnly`, `MixedPools*`,
  `CorrectionDebitNegativeBalance` with the pools asserted; read: `placebet.go` and
  `poolstep.go` `stepBetPools`.*
- **FR-003a**: A bet MUST reduce deposit turnover pending (spec 002 FR-010) by the smaller of its
  deposit part and the pending amount, not by its real leg: converted money staked does not clear a
  deposit's turnover. *Amends spec 002 FR-010. Read: the original's `depositrecord.go`
  `AttributableStake` (the deposit-pool share on a stamped wallet) and the fixture "deposit turnover
  — stamped wallet: only the deposit-pool share of the stake counts" in `place-bet.test.tl`; the
  original's per-deposit-record turnover ledger of the same change is not this spec's
  (*Changes from the original*).*
- **FR-003b**: A settlement or an adjustment that leaves the deposit pool at zero with no round
  open MUST close deposit turnover: nothing pending, every pending deposit closed; converted money
  left in real balance does not keep it open. An adjustment of restricted funds does not close it.
  *Amends spec 002 FR-010. Read: the original's `PlanCloseTurnover` with `AttributableCash` (the
  deposit-pool share on a stamped wallet); found in implementation, where spec 002's rule (real
  balance at zero) and this spec's pools disagreed.*
- **FR-004**: A win MUST split the real share it pays (the real leg's share plus any share whose
  record is no longer active, spec 002 FR-011) as follows: an orphaned share joins the converted
  pool whole; the rest is split in the ratio of the bet's recorded parts, the deposit part being
  floor(rest × the bet's deposit part ÷ the bet's real leg) and the converted part the remainder; a
  bet whose real leg is zero gives it all to the converted pool. Both parts MUST be recorded on the
  win. *Amends spec 002 FR-011. Recorded: `MixedPoolsWinEarnsDepositShareOnly`; read:
  `creditwin.go` and `apply.go` `winSplit`.*
- **FR-005**: A rollback MUST return to each pool exactly the part the bet recorded for it, a leg
  whose record is gone (spec 002 FR-012) going to the converted pool, and MUST record the parts
  restored. *Amends spec 002 FR-012. Recorded: `CancelledRoundRestoresPoolsAndEarnsNothing`; read:
  `rollback.go`.*
- **FR-006**: Settling a round as a loss MUST move neither pool. *Amends nothing in spec 002
  FR-013 beyond stating it. Recorded: `MixedPoolsLossEarnsDepositShareOnly`.*

**Deposits, conversions and withdrawals**

- **FR-007**: A deposit MUST credit the deposit pool by its amount. *Amends spec 002 FR-022. Read:
  `apply.go` `TypeDeposit`; the pool case of `deposit.test.tl`.*
- **FR-008**: A hold MUST take, in this order and recording each: the clawback it applies (spec 002
  FR-023) from the converted pool first, the amount it voids as `bonus_cap_void` from the converted
  pool first, and the amount it pays from the deposit pool first, the remainder of each from the
  other pool; the hold preview (spec 002 FR-023a) MUST report the same parts without moving
  anything, as the deposit part of each of the three (`clawbackDepositAmount`,
  `voidedDepositAmount`, `paidDepositAmount`; assumed: the original's preview reports none, these
  names follow its `fundedDepositAmount`); the hold's own reply carries the pools after, as every
  money reply does, and its parts are read from the transactions view. A release (spec 002 FR-024) MUST return the paid and voided parts together to the pools
  they were taken from (the release credits the whole held amount), and a reversal (spec 002
  FR-025) MUST return the paid parts (the original's reversal restores `PaidParts`; what was voided
  was never paid out and is not reversed). *Amends spec 002 FR-023, FR-023a, FR-024 and FR-025. Read: `withdraw.go`
  (the three movements applied in that order) and `complete.go` `restoreMovement`; recorded: the
  withdrawal lifecycle fixtures with the pools asserted.*
- **FR-009**: Cancelling an award MUST take the clawback it applies (spec 002 FR-017) from the
  converted pool first and the remainder from the deposit pool, and record the parts on the
  cancellation; forfeiting, awarding, switching and closing a pending cap move neither pool.
  *Amends spec 002 FR-017. Read: `close.go`; the pool case of `cancel-bonus.test.tl`.*
- **FR-010**: An operator's credit to real balance MUST join the deposit pool; an operator's debit
  of real balance MUST leave the deposit pool first and the converted pool for the remainder, the
  parts recorded; an adjustment of restricted funds moves neither pool. A provider credit that
  lands on real balance (spec 002 FR-027) MUST join the converted pool; one that lands on a record
  moves neither pool. *Amends spec 002 FR-026 and FR-027. Read: `adjust.go`; the pool cases of
  `admin-adjust.test.tl` and `game-provider-credit.test.tl`; the original's spec 253 FR-004 as
  amended by its research Q2.*

**Opening, facts and reads**

- **FR-011**: The migration's opening command (`open-migrated`, spec 002 FR-001; spec 019) MUST
  carry the two pools: the original's `deposit_pool_balance` and `converted_pool_balance` when the
  wallet is stamped, and otherwise a deposit pool of zero and a converted pool equal to the real
  balance; the sum MUST equal the opening real balance or the command is refused. The migration
  (spec 019) MUST run the original's pools backfill before its final export so that every wallet
  it can reconcile is stamped. *Amends spec 002 FR-001 and spec 019's wallet row. Decided: the
  original's 2026-10-15 decision for unreconciled wallets, carried over.*
- **FR-012**: Every `money.transactions` family whose movement touches real balance
  (`deposit_confirmed`, `bet_placed`, `win_credited`, `bet_rolled_back`,
  `restricted_funds_converted`, `restricted_funds_forfeited` when it carries a clawback,
  `admin_adjusted` on real, `hold_placed`, `hold_released`, `withdrawal_reversed`,
  `provider_credited` on real, and spec 022's families when built) MUST carry the deposit-origin
  and converted-origin parts of that movement, and every family MUST carry both pools after it
  beside the real and restricted balances (spec 002 FR-031); the fields are added and nothing
  existing changes (spec 001 FR-013). `attribution` (spec 012 FR-016, FR-030) reads the deposit
  part of `bet_placed`, `win_credited` and `bet_rolled_back`, and nothing of `provider_credited`,
  into GGR. *Amends spec 002 FR-031. Decided 2026-10-28 (Sheldon); the original's data-model §2
  and §5 read for the fields.*
- **FR-013**: The balance read (spec 002 FR-028) MUST report both pools beside real balance on
  every audience's route, the player's included, as the original's balance response does; a bet's
  reply MUST carry the deposit part of its real leg and a win's reply the deposit part of its real
  share, as the original's do; the transactions view (spec 002 FR-029) MUST carry each row's parts
  where its movement has them. *Amends spec 002 FR-028 and FR-029. Read: the original's
  `BalanceResponse.depositPoolBalance` and `convertedPoolBalance` (served to the player's token
  too: its e2e tests read them so), `BetResponse.fundedDepositAmount`,
  `WinResponse.wonDepositAmount`; its spec 253 FR-013.*
- **FR-014**: The pools and the parts MUST NOT change any refusal, any balance, any restricted
  funds rule, any contribution or any fact the wallet gives today; the pool step runs after the
  resolver has decided the movement and only attributes it. *Amends nothing; holds spec 002's
  recordings. Recorded: every recording of spec 002 replays unchanged with the pools present.*

### Key Entities

- **Deposit pool**: the part of a wallet's real balance that is the player's deposited money,
  operator credits, and the winnings earned by staking that money; never below zero.
- **Converted pool**: the part of a wallet's real balance that came from restricted funds that
  converted, from bonus-side credits (a provider credit to real, a win's share whose record is
  gone), and from the winnings earned by staking that money; below zero only by a correction
  debit's shortfall.
- **Parts of a movement**: on every movement that touches real balance, its deposit-origin and
  converted-origin amounts, summing to the real amount moved; recorded on bets, wins, rollbacks,
  holds, releases, reversals, clawbacks and adjustments, and carried on their facts.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After every event of every wallet in the parity run and the integration suites, the
  deposit pool plus the converted pool equals the real balance to the minor unit, checked by the
  entity on every event.
- **SC-002**: A player who never deposited, converts an award and plays it through produces facts
  whose deposit parts are all zero, so spec 012's GGR for the player is zero for the period.
- **SC-003**: Every fixture and Go table under *Recordings* passes, every recording of spec 002
  replays unchanged, and the driver's pool scenarios replay green once the wallet recording run
  is made (the blocked e2e tests follow spec 003).
- **SC-004**: A bet's or win's two parts sum to its real amount in every fact of the parity run and
  the property test, for amounts up to the 64-bit range of spec 002 SC-004.
- **SC-005**: The provider callback latencies of spec 002 SC-003 hold with the pools present.
- **SC-006**: An operator can read a player's deposit pool and converted pool from the balance
  read, and a migrated wallet shows the original's pools or all converted, wallet by wallet.

## Changes from the original

- There is no unstamped wallet. The original keeps `pools_stamped_at` and leaves an unstamped
  wallet's pools untouched and its movements without parts until the backfill stamps it; here
  every wallet has its pools from its first event and the migration's opening command carries them
  (FR-001, FR-011). The original's fallback for a win on a bet that recorded no parts (the ratio of
  the two pools at settlement, logged) is dropped with it: every bet in this wallet carries its
  parts, and a round open at the cutover is drained before it (spec 019).
- The backfill, its reconciliation report and the stamp route are the original's and run before
  the cutover (FR-011); they are not ported. A wallet that does not reconcile there opens here all
  converted.
- The parts and the pools ride on the wallet's existing fact families (FR-012); the original adds
  `deposit_stake` and `deposit_win` to its round index and round twin and `excludedGgrCents` to its
  GGR fact. Here `attribution` reads the parts from `money.transactions` directly (spec 012 FR-030
  as amended), and the converted-origin GGR excluded from commission is not emitted as a figure
  (the original's spec 253 FR-014, its affiliate portal's informational line; *Assumptions*).
- The repair tool's `converted-excluded` policy (the original's FR-012) has no counterpart: the
  affiliate programme (spec 013) computes commission from the facts it receives.
- The original's bet, win and balance replies carry the deposit parts and the pools in major-unit
  strings (`fundedDepositAmount`, `wonDepositAmount`, `depositPoolBalance`, `convertedPoolBalance`);
  the wallet's service routes carry them in minor units under the same names, and the parity
  translation (spec 002 `contracts/wire.md`) converts, as it does every amount.
- Deposit turnover is reduced by the stake's deposit part (FR-003a) against spec 002's one pending
  amount; the original's per-deposit-record turnover ledger, released with its pools, is not carried
  here (*Assumptions*).

Found in implementation (2026-10-28):

- A converted pool already negative stays negative on later movements: a bet or debit draws
  nothing from it, a credit to it raises it. The original's `checkedPools` refuses any later step
  that would leave converted negative; here only the deposit pool is held at or above zero
  (FR-001), so a wallet left short by an allow-negative bet is not stuck.
- An operator's credit records its parts, `(amount, 0)`; the original records none on a credit.
- A win on a bet with no parts, which only a bet recorded before the pools could be, pays all
  converted; a reversal or release whose recorded parts do not match the amount returns the
  difference to deposit, as the original does for a legacy request.
- The pool step refuses a pair that would not sum as `POOLS_INVARIANT` instead of throwing; the
  entity's fold never throws.
- A bet funded proportionally while real balance is negative (after a correction debit) has a
  negative real leg (spec 002's `fundProportional`, as the original's), and the pools refuse it,
  `POOLS_INVARIANT` "negative debit", as the original's `debit` does. Found by the invariants
  property on 2026-10-28 and kept as a fixed test; before the pools such a bet raised real balance.
- The fixtures whose wallet twin carries no pools (the original's unstamped cases) open all
  deposit, so their turnover and payout outcomes read as the original's on a stamped wallet; the
  migration's rule for a wallet without pools stays all converted (FR-011).
- The original's pool tests that its guest tables carry (`pools_guests_test.go`) are ported as one
  suite, `PoolsGuestsSuite`, not spread over the bet, win and rollback table suites.
- `open-migrated` has no route until spec 019 names one; its pools are tested through the entity.
- The original's transactions route does not serve a transaction's parts, so no recording can
  hold the rows' parts to the original (`contracts/wire.md`); the fixtures and the Go tables do.

## Recordings

Taken against the original with the pools live (a wallet the original creates is stamped at
`0/0` from creation, its data-model §1), by ankka-port, kept under `tests/port/wallet/`:

- The wallet driver's pool scenarios (`tests/original/wallet-driver/pools_test.go`, one per
  scenario of `features/wallet/pools.feature` that the original's wallet answers), with the
  wallet's pools and each transaction's parts read back after every step. They are recorded in
  the same run as spec 002's wallet recordings (its T074), which has not yet been made; until it
  is, the pool parity evidence is the fixtures and the Go tables below.
- The original's `TestScenario15_St8Wallet_{SplitRoundEarnsRealGGROnly,
  ConvertedMoneyRoundEarnsNoAffiliateGGR, MixedPoolsLossEarnsDepositShareOnly,
  MixedPoolsWinEarnsDepositShareOnly, CancelledRoundRestoresPoolsAndEarnsNothing}` and
  `CorrectionDebitNegativeBalance` enter through the ST8 adapter and assert the affiliate's facts;
  they are blocked on spec 003 (game-provider) and spec 012, listed as such in the wallet's
  `Blocked`, and replay when those services exist. Their wallet-level assertions are the driver
  scenarios above.
- The pool cases of the handler fixtures: `place-bet.test.tl` (converted first, conversion inside
  the bet, the shortfall), `credit-win.test.tl` (the ratio and its rounding, an orphaned share),
  `rollback-bet.test.tl`, `deposit.test.tl`, `create-withdrawal-request.test.tl` and the withdrawal
  lifecycle fixtures (void and clawback first, then paid; the return), `admin-adjust.test.tl`,
  `game-provider-credit.test.tl`, `cancel-bonus.test.tl`.
- The original's `pools_test.go` table, replayed against the wallet's pool step as a unit kit.

## Assumptions

- The order of the three debits of a hold (clawback, void, paid) and the paid-first-from-deposit
  rule are the original's as of 2026-10-28 (`withdraw.go`); a change there before the recordings
  are taken is caught by the recordings, which are the arbiter.
- Spec 022's movements (a reservation, a settlement, a resolution payment, splitting and merging
  complete sets, an adjustment of a non-player account) split the pools by the same tie-break: a
  reservation moves neither pool (nothing leaves real balance); a debit of real balance
  (a settlement's buyer side, a split) leaves the converted pool first; a credit of real balance
  from another player or from a non-player account (a settlement's seller side, a merge, a
  resolution payment) joins the converted pool. This is the GGR-minimising default for money whose
  origin the wallet cannot trace. Spec 022 is a draft in another worktree and is asked to confirm
  or change it; nothing in spec 012 counts these movements today.
- The converted-origin GGR excluded from commission (the original's spec 253 FR-014) is not carried
  as a figure in spec 012's facts. If Sheldon wants the affiliate portal (spec 013) to show it, spec
  012 gains a field on `ggr-daily` and this wallet already carries what it needs.
- The player's balance read carries the pools because the original's does (FR-013); whether the
  player site shows them is the frontend's choice (constitution: frontends out of scope).
- The original's deposit turnover changed with the same release as its pools: a per-deposit-record
  ledger (oldest record first, a record closing when the deposit-origin cash is gone) beside the
  deposit-pool-share rule. Only the share rule is a pool rule and only it is here (FR-003a); the
  ledger is an amendment of spec 002's turnover model for a later spec, and its fixture cases are
  listed as ignored with that reason until then (plan, research R6).
- The original's production rollout of spec 253 may or may not have run by the cutover. FR-011
  covers both: the migration runs the backfill, and whatever is still unstamped opens all converted.

## Dependencies

- Spec 002 (wallet), amended as each requirement states; its recordings must replay unchanged.
- Spec 012 (affiliate protocol) FR-016 and FR-030 as amended 2026-10-28 on its branch (PR #11):
  the reader of the parts this spec puts on the facts.
- Spec 019 (migration): the wallet row of its opening-event table gains the two pools (FR-011) and
  its pre-cutover steps gain the original's pools backfill.
- Spec 001 FR-013 (fields added to a fact family, nothing existing changed) for the contract change
  of FR-012; `casino-contract` and its schema gain the fields.
- Spec 022 (wallet primitives), for the movements the *Assumptions* ask it to confirm.
