# Feature Specification: Wallet

**Feature Branch**: `002-wallet`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The `wallet` service of a brand's project: real and restricted balances per player and currency, wagering requirements and the conversion or forfeit of restricted funds, open rounds (bet, win, rollback, settle as loss), deposit confirmation, holds for withdrawals, admin adjustments, deposit-turnover accounting, and the `money.transactions` and `payments.payments` facts it produces. Every money command is idempotent by a caller-supplied key and returns the resulting balance; amounts are minor units; one event sourced entity per player and currency."

## Context

The wallet is the one ledger (constitution II). Today it is `wallet-service`, a 4,900-line route
file over a wallet twin, whose money rules run as TinyGo guests (`starter-packs/wallet/handlers-go`)
and TL handlers (`starter-packs/wallet/handlers`), each invoked by HTTP with the twin's state, and
whose restricted funds are `restricted-funds/<id>` twins read back through a lagging graph
projection. A bet is therefore a read, a dispatch, and a write of mutations; the check and the
ledger change are not one step. This feature makes them one step: an event sourced entity keyed
`player/currency` whose command handlers are the resolver (`internal/funding`, read as the
specification of every rule below) and whose events are the transactions.

Everything the original does on a wallet, it does in minor units (`Long`), since fix #662: a
€5,000 win on a €500 real-funded stake overflowed a 32-bit product and was credited as −€153.96. The
resolver's arithmetic is truncating integer division throughout, and the spec keeps every quirk of
it that a parity recording can observe.

Who calls the wallet (plan, *Projects and services*): `game-provider` (bet, win, rollback, settle
as loss, provider credit, balance), `payments`/`merchant` (the captured deposit and each later payment outcome, over the two routes the
brand grants it under ankka 040), `payouts` (hold, release, confirm, reverse), `rewards` (award, forfeit, cancel, close
pending cap, switch, status), `responsible-gambling` and `reporting` (sums and lifetime totals),
`support-agent` and the backoffice (balance, transactions, admin adjust). The player site reads
balance, transactions and restricted-funds status with the player's own token.

## Clarifications

### Session 2026-10-08

- Q: Does the wallet enforce responsible-gambling deposit limits? → A: No, as today. The original's
  casino-service checks a deposit against the player's limits before it ever reaches the wallet,
  by summing the wallet's deposit transactions over the last 1, 7 and 30 days. The wallet's part is
  to answer those sums exactly (FR-030); the decision is `responsible-gambling`'s (spec 010).
- Q: Where do a bet's brand-level switches come from (contribution rate per game type, whether
  bonus wagering counts, partial splits)? → A: Today `wallet-service` reads the brand's bonus
  configuration on every bet. Here the wallet keeps that configuration itself, as one key value
  entity per brand written by an operator route, so a bet has no cross-service read in its path
  (FR-004). `rewards` (spec 011) is its writer.
- Q: What is a "hold"? → A: Today a withdrawal request debits real balance at once and a
  cancellation, rejection or failure credits it back as `withdrawal_return`; completion moves
  nothing. The hold commands keep exactly that money movement (FR-022 to FR-025): a hold is the
  record that a pending request's amount has already left the balance. Observable balances are
  unchanged; only the names are the plan's.
- Q: Rollback of a bet already rolled back? → A: Today the wallet restores the stake again; the
  ST8 adapter's own transaction store is what stops a provider's retry reaching it twice. Here
  every money command is idempotent by key inside the wallet (FR-002), so the second rollback
  returns the first outcome. This is the one place the wallet is stricter than the original, and
  the recordings that prove it (`IdempotentDebit`, `IdempotentCredit`, `IdempotentCancel`) pass
  either way.

### Session 2026-10-08 (reconciliation)

- Q: Which project is the wallet in? → A: The brand's one project, named by its slug (spec 001
  FR-001); there is no `money` project. Topic names are the contract's and unchanged:
  `money.transactions` is the wallet's, `casino.rounds` is `game-provider`'s, not the wallet's.
- Q: How does a deposit arrive? → A: As a call from `payments`/`merchant` on a route the brand grants
  under ankka 040, keyed by the payment id (spec 001 FR-016, FR-017), not as a message consumed from
  the shared project's topic. A second granted route carries each payment's later outcomes. The
  wallet produces the brand's `payments.payments` from both (FR-022, FR-022a).
- Q: Who calls the wallet without a grant? → A: Every service of the brand project, over HTTP within
  the project by its platform identity (spec 001 FR-020): `game-provider`, `rewards`, `payouts`,
  `responsible-gambling`, `support-agent`, `reporting`. Only `merchant` is admitted from outside.
- Q: Does the wallet hold personal data? → A: The player id as subject and the adjustment reason an
  operator writes, which spec 001 FR-023 names as personal (free text about the player); nothing
  else (FR-033). Amounts, keys and the actor's id are not personal (spec 001 FR-024).
- Q: Do the money rules match spec 001's? → A: Yes: minor units as 64-bit integers with a decimal
  refused at the boundary (FR-003, spec 001 FR-026), one idempotency key per money command with
  the first outcome on a repeat (FR-002, spec 001 FR-027), one currency per wallet (FR-001, spec
  001 FR-028).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player's bet is funded and settled in one step (Priority: P1)

A game provider debits a stake for a round, then credits a win, cancels the bet, or settles the
round as a loss. The wallet funds the stake from real balance and open restricted funds by the
primary grant's funding order, records which record funded which part, advances each active
grant's wagering, converts a grant whose requirement is met, and returns the balances. A later
settlement of that round reads the bet's own funding legs back, so a win follows its source, a
rollback restores each leg to where it came from, and a loss only closes the round.

**Why this priority**: this is the money path every provider callback runs through; nothing else in
the casino works without it, and it is the behaviour the parity recordings cover most densely.

**Independent Test**: replay `TestScenario15_St8Wallet_*` and `TestScenario22_FundingResolver`
against the entity through the `game-provider` routes, and the pack's `place-bet.test.tl`,
`credit-win.test.tl`, `rollback-bet.test.tl` and `settle-round-as-loss.test.tl` fixtures against the
entity's handlers directly.

**Acceptance Scenarios**:

1. **Given** a wallet with real balance 10,000 and no restricted funds, **When** a bet of 1,000 is
   placed for round "r1", **Then** real balance is 9,000, the bet transaction records a real leg of
   1,000 and no record legs, its wallet type is "real", and round "r1" is open.
2. **Given** round "r1" open with a bet of 1,000, **When** a win of 2,500 is credited for "r1",
   **Then** real balance rises by 2,500, the bet is settled, "r1" is no longer open, and the win's
   wallet type mirrors the bet's.
3. **Given** round "r1" open with a bet of 1,000, **When** the bet is rolled back, **Then** real
   balance rises by 1,000, the bet is voided, "r1" is no longer open, and deposit turnover pending
   is not restored.
4. **Given** round "r1" open, **When** the round is settled as a loss, **Then** no balance changes,
   the bet is marked loss, and "r1" is no longer open.
5. **Given** a wallet with real balance 500, **When** a bet of 1,000 is placed, **Then** it is refused
   as `INSUFFICIENT_FUNDS` with the message "Insufficient funds in real and bonus wallets", no
   transaction is written, and no round opens.
6. **Given** a wallet with real balance 0, **When** a correction debit of 1,000 is placed with
   allow-negative, **Then** real balance is −1,000, the bet is funded entirely from real, and no
   wagering contribution is recorded.
7. **Given** a wallet with real balance 50,000, **When** a bet of 50,000 is placed and a win of
   500,000 is credited, **Then** real balance is 500,000 (the #662 case: no overflow, no negative
   credit).
8. **Given** round "r1" open with a bet of 1,000, **When** a win is credited twice for "r1" under the
   same key, **Then** the second call returns the first call's balances and writes nothing.
9. **Given** no open bet for round "r9", **When** a win is credited for "r9", **Then** it is refused
   as `ROUND_NOT_FOUND`.
10. **Given** a bet of 0 for round "r0", **When** it is placed, **Then** it is accepted, funds
    nothing, and opens the round.

---

### User Story 2 - Restricted funds are granted, wagered, converted and forfeited (Priority: P1)

`rewards` awards a bonus as a restricted-funds record with a snapshot of its rules: priority,
release requirement, maximum convertible amount, maximum stake and its enforcement, contribution
rate and overrides by game, provider or category, scope include and exclude and what a violation
does, funding order, win allocation, release-progress scope, and what a withdrawal does to it.
Bets draw on the records in priority order, each bet's contribution advances released progress,
and a record whose progress meets its requirement converts: its balance becomes real and it stays
open as `CONVERTED_PENDING_CAP` so a later withdrawal can cap what the player takes out. Rewards
can forfeit, cancel (with a clawback), close a pending cap, or make another grant primary.

**Why this priority**: the restricted-funds rules are where the original's money bugs have lived
(stale projection reads, the `bonus_balance` columns, the int32 overflow); recording them is the
point of porting the wallet first.

**Independent Test**: replay `TestScenario19_BonusWageringCompletesToRealMoney`,
`TestScenario244_*`, `TestScenario245_*`, `TestScenarioFreeSpinsPlay_*` and
`TestScenario16_RewardGrant_*`; replay `award-bonus.test.tl`, `cancel-bonus.test.tl`,
`forfeit-bonus.test.tl`, `close-pending-cap-bonus.test.tl` and the resolver's Go table tests
(`placebet_test.go`, `multi_test.go`, `creditwin_test.go`, `rules_test.go`).

**Acceptance Scenarios**:

1. **Given** a wallet with real balance 0, **When** a bonus of 2,000 with wagering multiplier 10 is
   awarded, **Then** restricted balance is 2,000, one record is open and active with release
   requirement 20,000 and released progress 0, and a `bonus_credit` transaction is written.
2. **Given** an active record of 2,000 and real balance 1,000 under funding order `real_first`
   with partial splits on, **When** a bet of 1,500 is placed, **Then** the real leg is 1,000, the
   record leg is 500, the bet's wallet type is "split", and the record's open round count is 1.
3. **Given** the same wallet with partial splits off, **When** a bet of 1,500 is placed, **Then**
   the record funds all 1,500 (real cannot cover it alone, the record can), and the wallet type is
   "bonus".
4. **Given** two active records A (priority 0) and B (priority 1), **When** a bet larger than A's
   balance is funded from records, **Then** A is drawn to zero first and B funds the remainder.
5. **Given** an active record with release requirement 20,000 and released progress 19,500 at
   contribution rate 100, **When** a real-funded bet of 1,000 is placed, **Then** the record converts:
   its balance is credited to real, it becomes `CONVERTED_PENDING_CAP` with remaining allowance set
   to its maximum convertible amount, and the bet reports the conversion.
6. **Given** a bet funded 50/50 from real and a record under win allocation `follow_source`,
   **When** a win of 1,001 is credited, **Then** real receives 500 and the record 501 (the last
   record leg absorbs the rounding remainder), and open round count falls by one.
7. **Given** a bet funded partly from a record that has since converted, **When** its win is
   credited, **Then** the record's share is paid to real, not to the converted record.
8. **Given** an active record whose scope excludes the bet's game with violation mode `block`,
   **When** a bet is placed on that game, **Then** it is refused as `SCOPE_BLOCKED`.
9. **Given** an active record with maximum stake 500 enforced as `cap`, **When** a bet of 800 is
   placed, **Then** the bet is funded in full and its contribution base is 500.
10. **Given** an active record with maximum stake 500 enforced as `block`, **When** a bet of 800 is
    placed, **Then** it is refused as `MAX_STAKE_EXCEEDED`.
11. **Given** an active record of balance 0 with one open round, **When** that round settles as a
    loss, **Then** the record is forfeited automatically and restricted balance is reported as 0.
12. **Given** an active record, **When** rewards forfeits it, **Then** its balance is 0, it is
    `FORFEITED`, and a second forfeit is refused as `NO_ACTIVE_BONUS`.
13. **Given** an active record and real balance 300, **When** rewards cancels it with a clawback of
    500, **Then** the record is forfeited, the clawback applied is 300 (clamped to real), and real
    balance is 0.
14. **Given** no active record, **When** rewards cancels with a clawback, **Then** no record changes,
    the clawback clamp still applies to real, and a `bonus_cancel` transaction is written.
15. **Given** a `CONVERTED_PENDING_CAP` record, **When** rewards closes the pending cap, **Then** it
    is `CLOSED` and no balance changes; with none pending the call is refused as `NOT_PENDING_CAP`.
16. **Given** active records A (primary) and B, **When** rewards switches to B's grant, **Then** B
    sorts first on the next bet; switching to a grant that is not active is refused as
    `GRANT_NOT_ACTIVE`, and to an unknown grant as `GRANT_NOT_FOUND`.
17. **Given** a brand limit of one concurrent grant and one active record, **When** a second award
    arrives, **Then** it is refused as `BONUS_ALREADY_ACTIVE`; with no limit set it is accepted.
18. **Given** a restricted balance with no open record (an unmigrated wallet), **When** any bet-path
    command runs, **Then** it is refused as `RESTRICTED_FUNDS_UNMIGRATED` rather than funded from
    real alone.

---

### User Story 3 - A deposit is confirmed and turnover is accounted (Priority: P1)

A captured payment reaches the wallet once, credits real balance, and records the deposit's
withdrawal-turnover terms. Bets funded from real reduce the turnover still pending; a withdrawal is
blocked while any is pending. A deposit while a converted grant awaits its cap advances that
grant's deposits-since-release.

**Why this priority**: deposits are the first money in; the hand-off from `payments`/`merchant` is a
call across a 040 grant keyed by the payment id, and double credit is the failure a regulator asks about.

**Independent Test**: replay `TestScenario14_CasinoDeposit_PlayerToWallet`,
`TestScenario11_CasinoHTTPAPIGate3_DepositUpdatesBalance`, `..._DepositIdempotent`,
`TestScenario245_TurnoverDisplayFollowsWalletPending` and `deposit.test.tl`.

**Acceptance Scenarios**:

1. **Given** a wallet with real balance 0, **When** a deposit of 5,000 under payment reference "p1"
   with turnover multiplier 1 is confirmed, **Then** real balance is 5,000, a deposit record holds
   required turnover 5,000 and status `PENDING`, and deposit turnover pending is 5,000.
2. **Given** "p1" confirmed, **When** a deposit under "p1" arrives again, **Then** the balances are
   returned unchanged and no transaction is written.
3. **Given** turnover multiplier 0, **When** a deposit is confirmed, **Then** its record is `CLEARED`
   and turnover pending does not change.
4. **Given** turnover pending 5,000, **When** a bet with a real leg of 2,000 is placed, **Then**
   turnover pending is 3,000; a bet funded only from records leaves it unchanged; a rollback does
   not restore it.
5. **Given** a `CONVERTED_PENDING_CAP` record with deposits since release 0, **When** a deposit of
   1,000 is confirmed, **Then** that record's deposits since release is 1,000.
6. **Given** a wallet that does not exist for the currency, **When** a deposit arrives, **Then** the
   wallet is created with that deposit (a wallet is `player/currency`; a first touch creates it).

---

### User Story 4 - A withdrawal is held, released, confirmed or reversed (Priority: P2)

`payouts` asks the wallet to hold a withdrawal's amount. The wallet refuses while a request is
already pending, while real balance is short, or while deposit turnover is pending; otherwise it
forfeits every active grant by its own policy, applies a clawback once if any policy calls for it,
draws the amount down against any converted grant's cap and voids the excess, and debits real. A
release returns the money; a confirmation closes the hold; a reversal after confirmation returns
the money again.

**Why this priority**: it is the money path out, and the forfeiture and cap rules are the most
intricate the original has.

**Independent Test**: replay `create-withdrawal-request.test.tl`, `withdraw_test.go`, the withdrawal
request flows in `TestScenario7_CasinoGate3_FullPlayerJourney`, and the withdrawal cancel, reject,
fail and reverse handlers' `.test.tl` fixtures.

**Acceptance Scenarios**:

1. **Given** real balance 10,000, no grants and no turnover pending, **When** a hold of 4,000 is
   placed for request "w1", **Then** real balance is 6,000, "w1" is the pending request, and a
   withdrawal transaction of 4,000 is written.
2. **Given** "w1" pending, **When** a second hold is placed, **Then** it is refused as
   `WITHDRAWAL_REQUEST_ALREADY_PENDING`.
3. **Given** real balance 3,000, **When** a hold of 4,000 is placed, **Then** it is refused as
   `INSUFFICIENT_FUNDS` with the message "Insufficient real balance for withdrawal".
4. **Given** deposit turnover pending 1, **When** a hold is placed, **Then** it is refused as
   `WITHDRAWAL_BLOCKED_TURNOVER`.
5. **Given** an active record of 2,000 under policy `funds_and_winnings` and a clawback of 1,500
   computed for it, **When** a hold of 4,000 is placed on real balance 5,000, **Then** the record is
   forfeited, the clawback of 1,500 is applied once, and it is refused as
   `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT` because 3,500 is less than 4,000.
6. **Given** two active records under policies `funds` and `none`, **When** a hold is placed,
   **Then** the first is forfeited with no clawback, the second is untouched, and the hold
   requires the player's confirmation (a record under any policy but `none` was open).
7. **Given** a record under `cancel_promotion`, **When** a hold is placed, **Then** the record is
   forfeited and its grant is reported for cancellation with reason "withdrawal".
8. **Given** a `CONVERTED_PENDING_CAP` record with deposits since release 1,000 and remaining
   allowance 2,000, **When** a hold of 4,000 is placed, **Then** 1,000 is drawn from deposits, 2,000
   from the allowance, 1,000 is voided as `bonus_cap_void`, the paid amount is 3,000, real is
   debited by the full 4,000, and the record closes because its allowance is exhausted.
9. **Given** "w1" held for 4,000, **When** it is released as cancelled, rejected or failed, **Then**
   real balance rises by 4,000 as a `withdrawal_return`, and no request is pending.
10. **Given** "w1" held, **When** it is confirmed, **Then** no balance changes and no request is
    pending; **When** it is then reversed, **Then** real balance rises by 4,000; a second reversal
    returns the first outcome.
11. **Given** "w1" not the pending request, **When** a release or confirmation names it, **Then** it
    is refused as `REQUEST_NOT_PENDING`.

---

### User Story 5 - An operator corrects a balance or a provider credits one (Priority: P2)

An operator credits or debits real or restricted balance with a reason; a game provider credits a
standalone amount (a free-spin win, a promotional payout) that lands on the primary active grant
if there is one, else on real.

**Independent Test**: replay `admin-adjust.test.tl`, `game-provider-credit.test.tl`,
`TestScenario245_ZeroedBonusClosesRecordAndGrant`, `TestScenario245_PartialBonusAdjustLeavesRecordActive`
and `TestScenario15_St8Wallet_StandaloneCredit`.

**Acceptance Scenarios**:

1. **Given** real balance 1,000, **When** an operator credits 500 with reason "goodwill", **Then**
   real balance is 1,500 and an `admin_adjust` transaction carries the reason and the actor.
2. **Given** real balance 1,000, **When** an operator debits 1,500, **Then** it is refused as
   `DEBIT_EXCEEDS_BALANCE` naming both amounts.
3. **Given** no reason, **When** an adjustment is requested, **Then** it is refused as
   `REASON_REQUIRED`.
4. **Given** an active record of 800, **When** an operator debits 800 from restricted balance and
   the record has no open round, **Then** the record is forfeited and restricted balance is 0; a
   debit of 300 leaves it active at 500.
5. **Given** no active record, **When** an operator adjusts restricted balance, **Then** it is
   refused as `NO_OPEN_RESTRICTED_FUNDS`.
6. **Given** an active record, **When** a provider credit of 700 arrives, **Then** the record's
   balance rises by 700; with no active record, real rises by 700.
7. **Given** any adjustment, **When** deposit totals, GGR or deposit-limit usage are computed,
   **Then** `admin_adjust` transactions are not counted as deposits.

---

### User Story 6 - Reads: balance, status, transactions, sums (Priority: P2)

The player site, the backoffice, `responsible-gambling`, `reporting` and `partner-api` read the
wallet.

**Independent Test**: replay `TestScenario15_St8Wallet_BasicBalance`, the transactions assertions
in `TestScenario7_CasinoGate3_FullPlayerJourney`, and the reporting routes' sums.

**Acceptance Scenarios**:

1. **Given** a wallet, **When** its balance is read, **Then** real balance, restricted balance
   (the sum of open records' balances), currency and, when a record is active, its status,
   wagering requirement, wagered amount and progress are returned, as major-unit strings.
2. **Given** open records, **When** restricted-funds status is read, **Then** every open record is
   listed in resolver order with its balance, requirement, progress, cap fields, priority and
   withdrawal policy, and the primary record's view is the headline.
3. **Given** transactions, **When** they are listed by type, wallet type, date range and page,
   **Then** each row carries type, wallet type, amount, balance before and after, currency,
   status, payment reference, round, bonus reference, reason, terminal, PSP and created time.
4. **Given** transactions, **When** a sum by type over a date range is asked, **Then** the total and
   count are returned; lifetime totals report deposited, wagered and won.

---

### Edge Cases

- A win for a round whose bet was placed in a different currency: there is no such round in this
  wallet (a wallet is `player/currency`), so it is `ROUND_NOT_FOUND`.
- A rollback after the round settled: the stake is restored (the bet is found by its key, open or
  not), the win's reversal is the provider's separate correction debit, and the ST8 adapter's
  "cancel everything within a round" recording proves the pair restores the starting balance.
- A hold larger than real balance but smaller than real plus restricted: refused; restricted funds
  never fund a withdrawal.
- A bet in a currency the player has no wallet for: `WALLET_NOT_FOUND`; only a deposit creates one.
- An amount that is not a whole number of minor units, or negative where a positive is required:
  refused as `INVALID_REQUEST`, naming the amount, before any state is read (spec 001 FR-026).
- Real balance negative after a correction debit: a normal bet is refused, a deposit is credited
  against the negative, and a release of a hold is credited against it.
- A record closed between a bet and its settlement: the settlement's share for that record goes to
  real; its open round count is still decremented.
- A converted record with a release requirement of 0 converts on its first bet, as today.
- Five hundred open records on one wallet: the original refuses the traversal; the entity has no
  such limit and the spec sets none.

## Requirements *(mandatory)*

### Functional Requirements

**Identity and money**

- **FR-001**: A wallet MUST be one event sourced entity keyed by player id and ISO 4217 currency
  within the brand's project (spec 001 FR-001: one project per brand, named by its slug, the wallet a
  service in it); the brand is the project, never part of the key. A wallet is
  created by its first deposit or award and never deleted.
- **FR-002**: Every command that moves money MUST carry a key the caller supplies (the provider's
  transaction id, the payment reference, the request id, or the caller's request id) and MUST be
  idempotent under it: a repeat returns the outcome of the first call, including a refusal, and
  writes nothing. Keys are kept for the wallet's life.
- **FR-003**: Every amount MUST be a whole number of minor units carried as a 64-bit integer;
  every rate a whole percent; every product of two amounts computed in 64 bits with truncating
  integer division, as the resolver does. Replies to the player site and backoffice MUST render
  major-unit strings with two decimals, as today.
- **FR-004**: A brand's bet switches — contribution rate per game type, whether bonus wagering
  counts toward release, whether partial splits are allowed — MUST be held in one key value entity
  per brand inside the wallet service, written by an operator route admitted to `rewards`, and read
  by the bet handler; a bet MUST NOT call another service.

**Bets and rounds**

- **FR-005**: A bet MUST be funded by the primary (first active) record's funding order:
  `real_first` (default) draws real, then records in priority order when partial splits are on,
  else records in full; `restricted_first` the reverse; `proportional` splits the stake in
  proportion to real and the records' total; `combined` funds as `real_first`. Only active records
  with a positive balance fund a bet.
- **FR-006**: A bet that cannot be funded MUST be refused as `INSUFFICIENT_FUNDS` with the message
  "Insufficient funds in real and bonus wallets", unless the caller allows a negative balance, in
  which case it is funded entirely from real with no contribution.
- **FR-007**: Before funding, a bet MUST be refused as `SCOPE_BLOCKED` when any active record whose
  violation mode is `block` excludes the game (by game, provider or category key), and as
  `MAX_STAKE_EXCEEDED` when the smallest active maximum stake is enforced as `block` and the stake
  exceeds it; a `cap` enforcement caps the contribution base instead.
- **FR-008**: Each active record's contribution MUST be its base × its rate ÷ 100, where the rate
  is the record's override for the game, then provider, then category, then its default, then the
  brand's rate for the game type; the base is the record's own leg (when bonus wagering counts)
  plus the real leg for the primary, or the whole bet's real-and-record legs under `all_open`
  scope, or the whole capped stake for the primary under `combined`; zero when the record's scope
  excludes the game; never above the capped stake.
- **FR-009**: After legs and contributions are applied, every active record whose released progress
  meets its requirement MUST convert: its balance is credited to real, it becomes
  `CONVERTED_PENDING_CAP`, its remaining allowance is set to its maximum convertible amount, and its
  balance, progress, requirement and deposits since release are zeroed. The bet reports the
  converting records.
- **FR-010**: A bet MUST open its round: the bet transaction records its real leg and record legs,
  its contributions, its wallet type (`real`, `bonus`, `split`, or empty for a zero stake), and
  balance before and after (real for real or split funding; the primary record's balance for pure
  record funding, captured after legs and before conversion); each record that drew a leg has its
  open round count incremented; deposit turnover pending is reduced by the smaller of the real
  leg and the pending amount.
- **FR-011**: A win MUST find the open bet by round key or be refused as `ROUND_NOT_FOUND`; MUST
  allocate the amount across the bet's legs by the primary funding record's win allocation —
  `follow_source` pro rata to the legs with the last record leg absorbing the rounding remainder,
  `all_restricted` entirely to the highest-priority funding record — and MUST pay any share whose
  record is no longer active to real. It decrements each funding record's open round count,
  marks the bet settled, and closes the round.
- **FR-012**: A rollback MUST find the bet by round key or be refused as `ROUND_NOT_FOUND`; MUST
  restore each leg to its source, a share whose record is no longer active to real; MUST reverse
  each recorded contribution on records still active, floored at zero; decrements open round
  counts; marks the bet voided; closes the round; and MUST NOT restore deposit turnover pending.
- **FR-013**: Settling a round as a loss MUST move no money, decrement each funding record's open
  round count, forfeit any active funding record whose balance is zero and open round count is
  now zero, mark the bet loss, and close the round.
- **FR-014**: A bet-path command on a wallet whose restricted balance is positive with no open
  record MUST be refused as `RESTRICTED_FUNDS_UNMIGRATED`.

**Restricted funds**

- **FR-015**: An award MUST create an active record with the amount as its balance, release
  requirement = amount × wagering multiplier (0 when the multiplier is 0), the grant reference,
  priority, granted time, expiry, and the rule snapshot named in User Story 2; MUST write a
  `bonus_credit` transaction; and MUST be refused as `BONUS_ALREADY_ACTIVE` when the brand's
  maximum concurrent grants is set and that many records are active (absent = no limit), and as
  `MISSING_BONUS_REF` without a bonus reference.
- **FR-016**: Forfeit MUST zero and close as `FORFEITED` the primary active record, or the active
  record a grant reference names; none open is `NO_ACTIVE_BONUS`; a named grant that is not active
  is `NO_ACTIVE_BONUS`.
- **FR-017**: Cancel MUST apply the requested clawback to real, clamped to real balance, whether or
  not a record is open; MUST forfeit the primary active record or the named grant's; a named grant
  not active is `GRANT_NOT_OPEN`; MUST write a `bonus_cancel` transaction whose balance before is
  the sum of active balances before the cancel.
- **FR-018**: Close pending cap MUST close as `CLOSED` the first `CONVERTED_PENDING_CAP` record with
  no balance change; none is `NOT_PENDING_CAP`.
- **FR-019**: Switch MUST make a named active record primary by giving it a priority below every
  open record's, in one write; `GRANT_NOT_FOUND` for an unknown grant, `GRANT_NOT_ACTIVE` for one
  not active, and "already primary" when it already sorts first.
- **FR-020**: Records MUST be ordered by priority ascending then granted time ascending wherever
  the spec says "primary", "first" or "in order"; open means `ACTIVE` or `CONVERTED_PENDING_CAP`.
- **FR-021**: Restricted balance MUST always equal the sum of open records' balances; it is derived,
  never commanded.

**Deposits and holds**

- **FR-022**: A deposit MUST arrive on the deposit route the brand grants `payments`/`merchant` under
  ankka 040 (spec 001 FR-016), keyed by the payment id (spec 001 FR-017); it MUST credit real balance, write a `deposit` transaction with payment
  reference, terminal, PSP and scheme, create a deposit record with required turnover = amount ×
  multiplier (`CLEARED` at multiplier 0, else `PENDING`), add the requirement to deposit turnover
  pending, and add the amount to deposits since release on every `CONVERTED_PENDING_CAP` record.
  A repeated payment reference returns current balances and writes nothing.
- **FR-022a**: The wallet MUST serve a second route granted to `payments`/`merchant`, on which
  `merchant` reports a payment's later outcomes (failed, expired, abandoned, refunded, dispute opened,
  dispute closed), keyed by the payment id and the outcome; an outcome MUST move no money in the
  wallet (the original's wallet moves money on capture only). From the captured deposit and from each
  outcome the wallet MUST produce the brand's `payments.payments` messages — `payment.captured`,
  `payment.failed`, `payment.expired`, `payment.abandoned`, `payment.refunded`,
  `payment.dispute.opened`, `payment.dispute.closed` — keyed by the payment id and carrying the player
  id as subject, so the brand's readers never depend on the shared project's topics (spec 001 FR-011).
- **FR-023**: A hold MUST be refused, in this order, as `WITHDRAWAL_REQUEST_ALREADY_PENDING` while a
  request is pending, `INSUFFICIENT_FUNDS` ("Insufficient real balance for withdrawal") when real
  is below the amount, and `WITHDRAWAL_BLOCKED_TURNOVER` ("Deposit turnover requirements not yet
  met") while turnover is pending. It MUST then forfeit every active record by its policy (`none`
  untouched; `funds`, `funds_and_winnings`, `cancel_promotion` forfeited; the last also reporting
  the grant for cancellation with reason "withdrawal"), apply the supplied clawback once to real
  only when a forfeited policy is `funds_and_winnings` or `cancel_promotion`, refuse as
  `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT` when real after clawback is below the amount, draw
  the amount down across `CONVERTED_PENDING_CAP` records in order (deposits since release first,
  then remaining allowance), void the excess as `bonus_cap_void`, close a record whose allowance is
  exhausted and every cap record when real after payout is at or below zero, debit real by the
  full amount, record the request as pending, and report whether confirmation was required (any
  active record under a policy other than `none`).
- **FR-024**: A release (cancelled, rejected or failed) MUST credit the held amount back as a
  `withdrawal_return` and clear the pending request; a confirmation MUST clear it with no money
  movement; either naming a request that is not the pending one is `REQUEST_NOT_PENDING`.
- **FR-025**: A reversal of a confirmed withdrawal MUST credit the amount back; a reversal of a
  request not confirmed is `WITHDRAWAL_NOT_IN_COMPLETED_STATE`; a repeat is idempotent.

**Adjustments and credits**

- **FR-026**: An operator adjustment MUST require a reason (`REASON_REQUIRED`), carry the actor,
  refuse a debit above the chosen balance as `DEBIT_EXCEEDS_BALANCE` naming both amounts, treat a
  zero as a no-op returning balances, and write an `admin_adjust` transaction, never a deposit.
  On real it credits or debits real; on restricted it adjusts the primary active record
  (`NO_OPEN_RESTRICTED_FUNDS` when none), closing it as `FORFEITED` when a debit leaves it at zero
  with no open round.
- **FR-027**: A provider credit MUST land on the primary active record when one exists, else on
  real, and write a transaction carrying the provider's reference, reason and actor.

**Reads and facts**

- **FR-028**: Balance MUST report real, restricted, currency and the primary record's status,
  requirement, wagered amount and progress; restricted-funds status MUST list every open record in
  order with the fields of User Story 6 scenario 2.
- **FR-029**: Transactions MUST be a view over the wallet's events, filterable by type, wallet type
  and date range, paged, with the row shape of User Story 6 scenario 3; lifetime totals (deposited,
  wagered, won) and sums by type with counts over a date range MUST be views over the same events.
- **FR-030**: Sums by type over the last 1, 7 and 30 days MUST be exact to the minor unit and
  available within one second of the transaction that changes them, since `responsible-gambling`
  decides deposit limits on them.
- **FR-031**: A consumer over the wallet's events MUST produce `money.transactions` messages of the
  types the topic contract names (`deposit_confirmed`, `bet_placed`, `win_credited`,
  `bet_rolled_back`, `round_settled_as_loss`, `restricted_funds_awarded`,
  `restricted_funds_converted`, `restricted_funds_forfeited`, `admin_adjusted`, `hold_placed`,
  `hold_released`), keyed by the wallet and carrying the player id as subject (spec 001 FR-012), each
  a stable `.v1` shape that leaks no event class;
  and `platform.audit` records for every money command and refusal.
- **FR-032**: Every money command MUST be admitted by caller identity (spec 001 FR-020, the service
  class): the callers inside the brand project — `game-provider`, `rewards`, `payouts`,
  `responsible-gambling`, `support-agent`, `reporting` — over HTTP within the project with no grant,
  and `payments`/`merchant` on the deposit and payment-outcome routes only, by the 040 grant the brand
  made. No other caller from outside the project is admitted. The player routes (balance,
  transactions, restricted-funds status) MUST be served to the brand realm's player token for that
  player only, and every route keeps its `{brand}` segment and refuses another brand's (spec 001
  FR-005).
- **FR-033**: Personal data: the wallet holds, under ankka 042 and spec 001 FR-023, the player id as
  its subject (the accepted residual) and one personal field, the free-text reason an operator writes
  on an adjustment, held as `Personal` under that player; amounts, currencies, keys, references,
  times, statuses and the actor's id are not personal (spec 001 FR-024). The wallet's erasure handler
  has nothing to delete beyond what destroying the subject's key erases, and confirms.

### Key Entities

- **Wallet** (event sourced, `player/currency`): real balance, deposit turnover pending, open
  rounds (bet keys), restricted-funds records (embedded; see below), pending withdrawal request and
  its held amount, the set of seen command keys with their outcomes, and the brand's bet switches
  read at bet time.
- **Restricted-funds record** (part of the wallet's state, one per grant): id, grant reference,
  bonus reference, status (`ACTIVE`, `CONVERTED_PENDING_CAP`, `FORFEITED`, `CLOSED`), priority,
  granted and expiry times, balance, release requirement, released progress, maximum convertible
  amount, remaining allowance, deposits since release, open round count, and the rule snapshot:
  maximum stake and enforcement, contribution default and overrides, scope include and exclude,
  violation mode, funding order, win allocation, release-progress scope, forfeit on withdrawal.
- **Bet** (part of the wallet's state while open; a transaction after): round key, amount, real leg,
  record legs, contributions, wallet type, status (open, settled, voided, loss), session, game.
- **Deposit record**: payment reference, amount, turnover multiplier and definition, required
  turnover, status (`PENDING`, `CLEARED`).
- **Transaction** (an event, projected to a view row): type (`deposit`, `bet`, `win`, `rollback`,
  `loss`, `bonus_credit`, `bonus_cancel`, `bonus_forfeit`, `bonus_cap_void`, `withdrawal`,
  `withdrawal_return`, `admin_adjust`, `game_provider_credit`), wallet type, amount, balance before
  and after, currency, status, references, reason, actor, created time.
- **Brand bet switches** (key value, one per brand): contribution rate by game type, bonus wagering
  counts, partial splits allowed, maximum concurrent grants.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every recording under *Recordings* replays green against the wallet through the
  `game-provider`, `rewards` and `payouts` routes, with balances equal to the minor unit.
- **SC-002**: A bet's check and ledger change are one command: under 200 concurrent bets on one
  wallet, the final real balance equals the starting balance minus the sum of accepted stakes,
  and no bet is accepted that the balance at its moment could not fund.
- **SC-003**: A provider callback (bet, win, rollback, loss) completes in under 50 ms at the
  wallet's median and under 250 ms at its 99th percentile on a wallet with 10,000 transactions,
  with no read of any other service.
- **SC-004**: The #662 case (a 500,000 win on a 50,000 stake, and a 5,000,000 win on a 500,000
  stake) credits the exact amount.
- **SC-005**: Replaying every production wallet's history from the migration's opening event
  through the recorded commands reproduces today's balances exactly, wallet by wallet.
- **SC-006**: Any money command repeated under its key within the wallet's life returns the first
  outcome and adds no event, proven by a test that repeats every command type.
- **SC-007**: The 1-, 7- and 30-day deposit sums match a direct sum of the view's rows for every
  wallet in the parity run.

## Changes from the original

- The twin, the HTTP dispatch to guests, the graph projection of records, the
  `open_restricted_funds_refs` pipe set and the `HAS_TRANSACTION` scan fallback are gone: records
  are part of the wallet's state and a round is found by key.
- Idempotency by key moves into the wallet for every money command (today only deposits are
  idempotent in the wallet; providers' retries are absorbed by the ST8 adapter's store).
- `create-withdrawal-request` and the request lifecycle become hold, release, confirm and reverse;
  the withdrawal request and payout destination twins move to `payouts` (spec 007). Money movement
  is unchanged.
- The brand's bet switches are a key value entity in the wallet, not a read of the brand twin.
- Dropped as stubs or migration tooling: the Phase 2 backfill and rollback routes, the three
  pre-flight routes, the crypto-wallet routes (to `payouts`), `check-open-round`, the audit edge
  write per transaction (replaced by `platform.audit`), the metrics' active-round gauge (a view).
- The 500-record traversal limit and the 2,000-twin scan cap have no counterpart.
- The brand's `payments.payments` facts are produced by the wallet from what `merchant` hands it over
  the granted routes; no brand service reads the shared `payments` project's topics (spec 001 FR-011).

## Recordings

The Go end-to-end suite, run against the original and recorded by ankka-port:
`TestScenario15_St8Wallet_*` (30 tests: BasicBalance, BasicDebit, BasicCredit,
LargeWinOnLargeStake, BasicCancel, BasicBuyin, BasicPayout, ZeroDebit, NotEnoughMoney,
ZeroBalanceFreshSpinDeclined, ZeroCredit, StandaloneCredit, BigRoundMultipleDebits,
BigRoundMultipleCredits, BigRoundMultipleCancels1, BigRoundMultipleCancels2, BigRoundFailedDebit,
IdempotentDebit, IdempotentCredit, IdempotentCancel, BalanceWithoutToken, LimitedCancel,
WrongSignature, NewParams, TableSwitch, CorrectionDebitNegativeBalance, Resettlement,
StandaloneCancel, CancelEverythingWithinRound); `TestScenario22_FundingResolver`
(real_only_legs_and_concept, bonus_active_conversion, rules_by_seeding, unmigrated_guard,
cutover_settlement, two_records_priority_order); `TestScenario19_BonusWageringCompletesToRealMoney`;
`TestScenario14_CasinoDeposit_PlayerToWallet`; `TestScenario11_CasinoHTTPAPIGate3_*`
(DepositUpdatesBalance, DepositIdempotent, ProviderBetDeductsBalance, ProviderBetInsufficientFunds,
ProviderWinCreditsBalance, ProviderRollbackSettledRound); `TestScenario7_CasinoGate3_*`
(FullPlayerJourney, InsufficientFunds); `TestScenario244_*` (4); `TestScenario245_*` (3);
`TestScenarioFreeSpinsPlay_*` (4); `TestScenario16_RewardGrant_*` (2); `TestScenario23_RewardGrantExpiry`.

Handler fixtures, replayed against the entity's handlers: every `starter-packs/wallet/handlers/*.test.tl`
(place-bet 1,469 lines, credit-win, rollback-bet, settle-round-as-loss, deposit, award-bonus,
cancel-bonus, forfeit-bonus, close-pending-cap-bonus, admin-adjust, game-provider-credit,
create-withdrawal-request and the withdrawal lifecycle), and the resolver's Go tables
(`placebet_test.go`, `multi_test.go`, `creditwin_test.go`, `rollback_test.go`, `loss_test.go`,
`close_test.go`, `withdraw_test.go`, `adjust_test.go`, `rules_test.go`, `legs_test.go`).

## Assumptions

- The clawback a hold or cancel applies is computed by the caller from the grant's activation
  baseline, as today (`casino-service` computes it; `rewards` will). [NEEDS CLARIFICATION: should
  the wallet instead record real balance at award time on the record and compute the clawback
  itself, so `rewards` never has to read the wallet first? It holds every input.]
- A deposit's turnover multiplier and definition are resolved by the caller (today: the sub-brand's
  `withdrawal_turnover_*`, overridden by the player's `turnover_*_override`, defaulting to 1× gross
  wagered) and arrive on the deposit command. [NEEDS CLARIFICATION: in the new split the brand
  terms belong to `players` or to the brand's configuration and the player override to `players`;
  the deposit route must resolve one of them before confirming — which, or does the wallet keep the
  brand terms beside its bet switches (FR-004)?]
- Free-spin rounds that never debit the wallet (a grant-funded round) are the game provider's to
  settle; the wallet sees only a provider credit for the win.
- Expiry of a record is `rewards`' timer: it calls forfeit; the wallet keeps the expiry time only
  for status.
- The topic message shapes start from `shared/json-schemas/` as the plan says.

## Dependencies

- Spec 001 (brands and projects) for the project layout, realms and the topic contract.
- ankka 040 for the deposit and payment-outcome routes `payments`/`merchant` calls across projects;
  042 for `Personal` (the adjustment reason) and the erasure handler; 043 for the fact-topic
  retention spec 001 FR-015 requires of `money.transactions` and `payments.payments`.
- Spec 003 (game-provider), 007 (payouts), 011 (rewards) as callers; their specs name the routes
  they call here by wire name.
