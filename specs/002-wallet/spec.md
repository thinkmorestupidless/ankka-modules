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

- Q: Does the wallet enforce responsible-gambling deposit limits? → A: *Corrected at reconciliation
  2.* The original's check runs after the wallet has credited the deposit and its refusal is logged
  and ignored (spec 010 Context), so nothing enforces a limit today. The wallet asks
  `responsible-gambling`'s `may-deposit` before crediting (FR-022), failing closed; the decision
  stays `responsible-gambling`'s (spec 010), and the wallet's part is to carry its own exact sums
  (FR-030) in the call. *Revised 2026-10-10:* `may-bet` is asked by `game-provider`'s bet workflow
  (spec 003 FR-012), not by the wallet.
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

### Session 2026-10-08 (reconciliation, wave 1)

- Q: Who computes the clawback a hold or cancel applies? → A: The wallet (settled with spec 007). It
  records the real balance and total deposits at award (FR-015a) and computes the clawback by the
  original's formula at cancel (FR-017) and at hold (FR-023); it serves a hold preview (FR-023a) so
  `payouts` can tell the player what a hold would forfeit before placing it. No caller supplies a
  clawback.
- Q: Where do deposit turnover terms resolve? → A: In the wallet (FR-004a). The brand's terms move
  here from `casino-service`'s brand configuration (spec 004 lists them as moved); the player's
  override is held on the player's wallet; the deposit resolves override, then brand terms, then
  the default, and calls nothing.
- Q: Does `payments.payments` carry an `initiated` outcome? → A: Yes (spec 001 FR-011): `merchant`
  reports `initiated` on the outcome route at the start of every attempt (spec 004 FR-022) and the
  wallet produces `payment.initiated` from it, moving nothing, so the brand lists a player's attempts
  from its own facts.

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: Does the wallet ask `responsible-gambling` before moving money? → A: Yes, on both paths
  (FR-007a, FR-022), failing closed. The earlier answer that "casino-service checks before the
  wallet, as today" was wrong: nothing checks today (spec 010 Context). *Revised 2026-10-10:* the
  deposit path only; a bet's `may-bet` is the caller's step (session 2026-10-10).
- Q: Does a deposit fact carry the deposit number? → A: Yes, the wallet's deposit ordinal on
  `deposit_confirmed` (FR-031), per player and currency; a player with two wallets has two counts,
  stated so `rewards` and the migration know.

### Session 2026-10-08 (reconciliation 3)

- Q: What do the wallet's facts carry for the third wave? → A: The real and restricted balance after
  every movement on `money.transactions` (FR-031), so `engagement`'s profile and `reporting`'s views
  are folds of facts; and the payment provider on every `payments.payments` family (FR-022a), which
  `merchant` names on the capture and on each outcome it reports.

### Session 2026-10-09

- Q: FR-004 and SC-003 say a bet calls no other service; FR-007a says every bet asks `may-bet`. Which
  holds? → A: The `may-bet` call stays (chosen at reconciliation 2; specs 003 and 010 depend on it).
  FR-004 and SC-003 now say "no call other than `may-bet`", and the latency targets include that
  call. `responsible-gambling` answers from its own state and calls nothing (spec 010 FR-011).
  *Superseded 2026-10-10:* the bet path calls nothing; `may-bet` moved to spec 003.
- Q: Who computes the clawback a cancel applies — spec 011 says `rewards` computes it and the wallet
  applies it, FR-015a says the wallet? → A: The wallet, for cancel and hold alike, from the baseline
  it records at award (FR-015a now states the original's formula). `rewards` sends no clawback and
  keeps no baseline; spec 011's clarification and data model are corrected to say so. Award and
  activation are the same moment (spec 011 Context), so the baseline is the same figure.
- Q: A reversal, a provider credit, a cancel's clawback and a hold's cap void have no
  `money.transactions` family; how do readers see them? → A: Two new families, `withdrawal_reversed`
  and `provider_credited`, since both are movements totals must count (`reporting` counts a provider
  credit as a win, spec 015 FR-005); the clawback rides as an amount on `restricted_funds_forfeited`
  and the void as an amount on `hold_placed` (FR-031). The contract and spec 001's topic table gain
  them.
- Q: A seen key arrives with a different command (another amount, round or type)? → A: Refused as
  `KEY_REUSED`, naming the key and the first command's type and amount, with a `platform.audit`
  record; the key store keeps a digest of the command beside its outcome (FR-002). Stricter than the
  original, named under *Changes from the original*. An identical repeat still returns the first
  outcome.
- Q: A repeated deposit returns current balances (FR-022, the original) or the first outcome
  (FR-002)? → A: The first outcome, like every other money command; FR-022 and the deposits feature
  are reworded, and the difference from the original's `hasDepositTransaction` check is noted.

### Session 2026-10-09 (analysis)

- FR-030 promised sums "within one second" of a view, which ankka cannot bound; it now says the sums
  `responsible-gambling` decides on are the wallet's own state (research R5) and the views' sums are
  eventually consistent.
- FR-001 names the migration's opening command `open-migrated` and *Changes from the original* names
  it and the `withdrawal_reversal` transaction type as new (constitution I).
- FR-007a says a bet's `responsible-gambling` refusal is not recorded under its key; FR-004 says
  service route, not operator route.

### Session 2026-10-10

- Q: The key store kept every key for the wallet's life, with its outcome; `ScaleItSuite` measured a
  10,000-transaction wallet's state at 4.3 MB, growing without bound. Where does the lifetime answer
  to "has this transaction been seen" live? → A: With the caller. Every keyed command is sent from a
  workflow whose id is the key (spec 003's bet workflow per provider transaction; a workflow per
  grant in `rewards` and per request in `payouts`; the wallet service's own `DepositWorkflow` for
  deposits). Its `start` is the atomic claim (a second start is refused `Conflict`), its journalled
  steps survive a crash, and its state holds the outcome for the life of the ledger. The wallet keeps
  only its most recent 1,000 keys with their digest and outcome: enough to answer a step that re-runs
  after a crash (a step runs at least once) and two concurrent duplicates that get past a caller
  without a workflow. A key older than that is unknown to the wallet; the caller's record answers it
  and never resends it. FR-002, SC-006 and research R4/R17.
- Q: Why does the wallet no longer ask `responsible-gambling` before a bet? → A: The check and the
  debit were two calls from the endpoint, so a provider's retry after a lost reply asked RG again and
  could be refused for a bet whose stake was already taken. In the bet workflow the check is a step
  before the wallet call and a repeat never reaches it. The wallet is the ledger and nothing else on
  the bet path (FR-004, SC-003: no call to any service). FR-007a moved to spec 003 FR-012.
- Q: Why are deposits a workflow, and what does the route answer while it runs? → A: The same shape:
  `may-deposit` then the credit are two writes, and a retry after a timeout cannot be told from a new
  request without a claim. `DepositWorkflow` (id `player/currency/paymentId`) asks RG in one step and
  credits the wallet in the next; its state is the outcome, so an RG refusal is recorded there and
  the wallet is never called for it (`refuse-deposit` is removed). ankka 0.11 has no await on a
  workflow, so the route starts it and polls its status every 1 ms for up to 2 s; past that it
  answers `503 OUTCOME_PENDING` and the retry is answered from the workflow's state.
- Q: How many settled bets does the wallet keep for a late rollback? → A: 1,000, `assumed`. The
  earlier 10,000 was justified as the original's `BoundedFanOut` depth, which is wrong:
  `BoundedFanOut` is a concurrency helper in `wallet-service`, and the original's adapter store is
  unbounded. ST8's cancel horizon is undocumented; 1,000 covers about 2.4 hours of the heaviest
  autoplay pace (one bet and one win per spin, about 10,000 keys a day). A rollback of a bet the
  wallet no longer holds is `ROUND_NOT_FOUND`, which spec 003 handles.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player's bet is funded and settled in one step (Priority: P1)

A game provider debits a stake for a round, then credits a win, cancels the bet, or settles the
round as a loss. The wallet funds the stake from real balance and open restricted funds by the
primary record's funding order, records which record funded which part, advances each active
record's wagering, converts a record whose requirement is met, and returns the balances. A later
settlement of that round reads the bet's own funding legs back, so a win follows its source, a
rollback restores each leg to where it came from, and a loss only closes the round.

**Why this priority**: this is the money path every provider callback runs through; nothing else in
the casino works without it, and it is the behaviour the parity recordings cover most densely.

**Independent Test**: replay `TestScenario15_St8Wallet_*` and `TestScenario22_FundingResolver`
against the entity through the `game-provider` routes, and the pack's `place-bet.test.tl`,
`credit-win.test.tl`, `rollback-bet.test.tl` and `settle-round-as-loss.test.tl` fixtures against the
entity's handlers directly.

**Acceptance Scenarios**:

- added `features/wallet/bets.feature`: a bet funded from real balance alone opens its round with one real leg
- added `features/wallet/bets.feature`: a win settles the bet and closes the round
- added `features/wallet/bets.feature`: a rollback restores the stake, voids the bet and closes the round
- added `features/wallet/bets.feature`: settling a round as a loss moves no money and closes the round
- added `features/wallet/bets.feature`: a bet the wallet cannot fund is refused and opens no round
- added `features/wallet/bets.feature`: a correction debit that allows a negative balance is funded from real balance with no contribution
- added `features/wallet/bets.feature`: a large win on a large stake is credited exactly
- added `features/wallet/bets.feature`: a win credited twice under the same key returns the first balances and writes nothing
- added `features/wallet/bets.feature`: a win for a round with no open bet is refused
- added `features/wallet/bets.feature`: a bet of zero is accepted, funds nothing and opens its round
- added `features/wallet/money.feature`: an amount is a whole number of minor units
- added `features/wallet/money.feature`: a money command sent again under its key returns the first answer and moves nothing
- added `features/wallet/money.feature`: an amount that is not a whole number of minor units is refused naming the amount
- added `features/wallet/money.feature`: a money command without a key is refused
- added `features/wallet/money.feature`: a seen key carried by a different money command is refused naming the first

---

### User Story 2 - Restricted funds are granted, wagered, converted and forfeited (Priority: P1)

`rewards` awards a bonus as a restricted-funds record with a snapshot of its rules: priority,
wagering requirement, maximum convertible amount, maximum stake and its enforcement, contribution
rate and overrides by game, provider or category, scope include and exclude and what a violation
does, funding order, win allocation, release-progress scope, and what a withdrawal does to it.
Bets draw on the records in priority order, each bet's contribution advances release progress,
and a record whose progress meets its requirement converts: its balance becomes real and it stays
open as `CONVERTED_PENDING_CAP` so a later withdrawal can cap what the player takes out. Rewards
can forfeit, cancel (with a clawback), close a pending cap, or make another award primary.

**Why this priority**: the restricted-funds rules are where the original's money bugs have lived
(stale projection reads, the `bonus_balance` columns, the int32 overflow); recording them is the
point of porting the wallet first.

**Independent Test**: replay `TestScenario19_BonusWageringCompletesToRealMoney`,
`TestScenario244_*`, `TestScenario245_*`, `TestScenarioFreeSpinsPlay_*` and
`TestScenario16_RewardGrant_*`; replay `award-bonus.test.tl`, `cancel-bonus.test.tl`,
`forfeit-bonus.test.tl`, `close-pending-cap-bonus.test.tl` and the resolver's Go table tests
(`placebet_test.go`, `multi_test.go`, `creditwin_test.go`, `rules_test.go`).

**Acceptance Scenarios**:

- added `features/wallet/restricted-funds.feature`: an award creates an active restricted funds record with its wagering requirement
- added `features/wallet/restricted-funds.feature`: under real first funding with partial splits on, a bet real balance cannot cover alone is split
- added `features/wallet/restricted-funds.feature`: under real first funding with partial splits off, a bet real balance cannot cover alone is funded from the record in full
- added `features/wallet/restricted-funds.feature`: records fund a bet in priority order, the first drawn to zero before the next
- added `features/wallet/restricted-funds.feature`: a record whose release progress meets its wagering requirement converts on the bet that completes it
- added `features/wallet/restricted-funds.feature`: a win follows its source, with the last restricted funds leg absorbing the rounding remainder
- added `features/wallet/restricted-funds.feature`: a win's share for a record that has since converted is paid to real balance
- added `features/wallet/restricted-funds.feature`: a bet on a game a record's scope excludes with violation mode block is refused
- added `features/wallet/restricted-funds.feature`: a bet above a maximum stake enforced as cap is funded in full with its contribution base capped
- added `features/wallet/restricted-funds.feature`: a bet above a maximum stake enforced as block is refused
- added `features/wallet/restricted-funds.feature`: a record at zero balance is forfeited when its last open round settles as a loss
- added `features/wallet/restricted-funds.feature`: forfeiting an active record zeroes it and closes it as forfeited
- added `features/wallet/restricted-funds.feature`: forfeiting when no record is active is refused
- added `features/wallet/restricted-funds.feature`: cancelling an award forfeits its record and applies the clawback clamped to real balance
- added `features/wallet/restricted-funds.feature`: cancelling with no active record still applies the clawback to real balance
- added `features/wallet/restricted-funds.feature`: closing the pending cap of a converted record closes it with no balance change
- added `features/wallet/restricted-funds.feature`: closing a pending cap when no record awaits one is refused
- added `features/wallet/restricted-funds.feature`: making another active award primary makes its record sort first on the next bet
- added `features/wallet/restricted-funds.feature`: making an award that is not active primary is refused
- added `features/wallet/restricted-funds.feature`: making an unknown award primary is refused
- added `features/wallet/restricted-funds.feature`: a second award is refused when the brand allows one concurrent award
- added `features/wallet/restricted-funds.feature`: a second award is accepted when the brand sets no limit on concurrent awards
- added `features/wallet/restricted-funds.feature`: a wallet with a restricted balance and no open record refuses every bet-path command

---

### User Story 3 - A deposit is confirmed and turnover is accounted (Priority: P1)

A captured payment reaches the wallet once, credits real balance, and records the deposit's
withdrawal-turnover terms. Bets funded from real reduce the turnover still pending; a withdrawal is
blocked while any is pending. A deposit while a converted record awaits its cap advances that
record's deposits since conversion.

**Why this priority**: deposits are the first money in; the hand-off from `payments`/`merchant` is a
call across a 040 grant keyed by the payment id, and double credit is the failure a regulator asks about.

**Independent Test**: replay `TestScenario14_CasinoDeposit_PlayerToWallet`,
`TestScenario11_CasinoHTTPAPIGate3_DepositUpdatesBalance`, `..._DepositIdempotent`,
`TestScenario245_TurnoverDisplayFollowsWalletPending` and `deposit.test.tl`.

**Acceptance Scenarios**:

- added `features/wallet/deposits.feature`: a confirmed deposit credits real balance and records its required turnover
- added `features/wallet/deposits.feature`: a deposit confirmed again under the same payment reference moves nothing
- added `features/wallet/deposits.feature`: a deposit with a turnover multiplier of zero is cleared at once
- added `features/wallet/deposits.feature`: a bet's real leg reduces deposit turnover pending
- added `features/wallet/deposits.feature`: a bet funded only from restricted funds leaves deposit turnover pending unchanged
- added `features/wallet/deposits.feature`: a rollback does not restore deposit turnover pending
- added `features/wallet/deposits.feature`: a deposit while a converted record awaits its cap advances that record's deposits since conversion
- added `features/wallet/deposits.feature`: a first deposit in a currency creates the wallet for that currency

---

### User Story 4 - A withdrawal is held, released, confirmed or reversed (Priority: P2)

`payouts` asks the wallet to hold a withdrawal's amount. The wallet refuses while a request is
already pending, while real balance is short, or while deposit turnover is pending; otherwise it
forfeits every active record by its own policy, applies a clawback once if any policy calls for it,
draws the amount down against any converted record's cap and voids the excess, and debits real. A
release returns the money; a confirmation closes the hold; a reversal after confirmation returns
the money again.

**Why this priority**: it is the money path out, and the forfeiture and cap rules are the most
intricate the original has.

**Independent Test**: replay `create-withdrawal-request.test.tl`, `withdraw_test.go`, the withdrawal
request flows in `TestScenario7_CasinoGate3_FullPlayerJourney`, and the withdrawal cancel, reject,
fail and reverse handlers' `.test.tl` fixtures.

**Acceptance Scenarios**:

- added `features/wallet/withdrawals.feature`: a hold takes the amount out of real balance and records the pending request
- added `features/wallet/withdrawals.feature`: a second hold while a request is pending is refused
- added `features/wallet/withdrawals.feature`: a hold above real balance is refused
- added `features/wallet/withdrawals.feature`: a hold while deposit turnover is pending is refused
- added `features/wallet/withdrawals.feature`: a hold forfeits an award under a funds and winnings policy, applies its clawback once, and is refused when real balance is then short
- added `features/wallet/withdrawals.feature`: a hold forfeits a record under a funds policy without clawback, leaves a record under no policy untouched, and requires the player's confirmation
- added `features/wallet/withdrawals.feature`: a hold forfeits a record under a cancel promotion policy and reports its award for cancellation
- added `features/wallet/withdrawals.feature`: a hold draws down a converted record's cap, voids the excess and closes the record when its allowance is exhausted
- added `features/wallet/withdrawals.feature`: a release returns the held amount and clears the pending request
- added `features/wallet/withdrawals.feature`: a confirmation clears the pending request with no money movement
- added `features/wallet/withdrawals.feature`: a reversal after confirmation returns the amount
- added `features/wallet/withdrawals.feature`: a second reversal returns the first outcome
- added `features/wallet/withdrawals.feature`: a release or confirmation naming a request that is not the pending one is refused

---

### User Story 5 - An operator corrects a balance or a provider credits one (Priority: P2)

An operator credits or debits real or restricted balance with a reason; a game provider credits a
standalone amount (a free-spin win, a promotional payout) that lands on the primary active grant
if there is one, else on real.

**Independent Test**: replay `admin-adjust.test.tl`, `game-provider-credit.test.tl`,
`TestScenario245_ZeroedBonusClosesRecordAndGrant`, `TestScenario245_PartialBonusAdjustLeavesRecordActive`
and `TestScenario15_St8Wallet_StandaloneCredit`.

**Acceptance Scenarios**:

- added `features/wallet/adjustments.feature`: an operator's credit to real balance carries its reason and actor
- added `features/wallet/adjustments.feature`: an operator's debit above real balance is refused naming both amounts
- added `features/wallet/adjustments.feature`: an adjustment without a reason is refused
- added `features/wallet/adjustments.feature`: an operator's debit that leaves a record at zero with no open round forfeits it
- added `features/wallet/adjustments.feature`: an operator's debit that leaves a record above zero leaves it active
- added `features/wallet/adjustments.feature`: an adjustment to restricted funds with no active record is refused
- added `features/wallet/adjustments.feature`: a provider credit lands on the primary active record
- added `features/wallet/adjustments.feature`: a provider credit with no active record lands on real balance
- added `features/wallet/adjustments.feature`: an adjustment is never counted as a deposit

---

### User Story 6 - Reads: balance, status, transactions, sums (Priority: P2)

The player site, the backoffice, `responsible-gambling`, `reporting` and `partner-api` read the
wallet.

**Independent Test**: replay `TestScenario15_St8Wallet_BasicBalance`, the transactions assertions
in `TestScenario7_CasinoGate3_FullPlayerJourney`, and the reporting routes' sums.

**Acceptance Scenarios**:

- added `features/wallet/reads.feature`: a balance read returns real balance, restricted balance, currency and the primary record's progress
- added `features/wallet/reads.feature`: a restricted funds status read lists every open record in order with the primary record's view as the headline
- added `features/wallet/reads.feature`: transactions are listed by type, wallet type, date range and page with every field of the row
- added `features/wallet/reads.feature`: a sum by type over a date range returns the total and the count, and lifetime totals report deposited, wagered and won

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
- A converted record with a wagering requirement of 0 converts on its first bet, as today.
- Five hundred open records on one wallet: the original refuses the traversal; the entity has no
  such limit and the spec sets none.

## Requirements *(mandatory)*

### Functional Requirements

**Identity and money**

- **FR-001**: A wallet MUST be one event sourced entity keyed by player id and ISO 4217 currency
  within the brand's project (spec 001 FR-001: one project per brand, named by its slug, the wallet a
  service in it); the brand is the project, never part of the key. A wallet is
  created by its first deposit or award, or by the migration's opening command (`open-migrated`,
  spec 019: the opening balances, records, deposit turnover pending and lifetime deposits of a
  wallet the original holds, accepted once and refused as `WALLET_ALREADY_OPEN` after), and never
  deleted.
- **FR-002**: Every command that moves money MUST carry a key the caller supplies (the provider's
  transaction id, the payment reference, the request id, or the caller's request id) and MUST be
  idempotent under it: a repeat returns the outcome of the first call, including a refusal, and
  writes nothing. A seen key carried by a command that differs from the first (in type, amount,
  round or any other field) MUST be refused as `KEY_REUSED`, naming the key and the first command's
  type and amount, writing nothing but a `platform.audit` record; the wallet keeps a digest of each
  command beside its outcome to tell the two apart. The wallet keeps its most recent 1,000 keys
  (synthetic `KEY_REUSED` entries included) and forgets older ones; the record of a key for the life
  of the ledger is the caller's workflow under that key, which answers a repeat older than the
  wallet's recent keys itself and never resends it (clarification 2026-10-10; research R17).
- **FR-003**: Every amount MUST be a whole number of minor units carried as a 64-bit integer;
  every rate a whole percent; every product of two amounts computed in 64 bits with truncating
  integer division, as the resolver does. Replies to the player site and backoffice MUST render
  major-unit strings with two decimals, as today.
- **FR-004**: A brand's bet switches — contribution rate per game type, whether bonus wagering
  counts toward release, whether partial splits are allowed — MUST be held in one key value entity
  per brand inside the wallet service, written by a service route admitted to `rewards`, and read
  by the bet handler; a bet MUST NOT call another service.
- **FR-004a**: A brand's deposit turnover terms — the multiplier and the definition the original
  keeps as the sub-brand's `withdrawal_turnover_*` in `casino-service`'s brand configuration — MUST
  be held in the same key value entity, written by a route for the brand's operator realm; a
  player's override (`turnover_*_override`) MUST be held on the player's wallet, written by an
  operator route; and a deposit MUST resolve its terms from the override when set, else the brand's
  terms, defaulting to 1× gross wagered, without calling another service.

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
- **FR-007a**: *Moved to spec 003 FR-012, 2026-10-10.* The bet-time `may-bet` check is a step of
  `game-provider`'s bet workflow, before its wallet call; the wallet's bet path asks nothing and a
  bet it is sent has passed the check. The refusals `PLAYER_LOCKED` and `RG_UNAVAILABLE` are that
  workflow's outcomes, never the wallet's.
- **FR-008**: Each active record's contribution MUST be its base × its rate ÷ 100, where the rate
  is the record's override for the game, then provider, then category, then its default, then the
  brand's rate for the game type; the base is the record's own leg (when bonus wagering counts)
  plus the real leg for the primary, or the whole bet's real-and-record legs under `all_open`
  scope, or the whole capped stake for the primary under `combined`; zero when the record's scope
  excludes the game; never above the capped stake.
- **FR-009**: After legs and contributions are applied, every active record whose release progress
  meets its requirement MUST convert: its balance is credited to real, it becomes
  `CONVERTED_PENDING_CAP`, its remaining allowance is set to its maximum convertible amount, and its
  balance, progress, requirement and deposits since conversion are zeroed. The bet reports the
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
  maximum concurrent awards is set and that many records are active (absent = no limit), and as
  `MISSING_BONUS_REF` without a bonus reference.
- **FR-015a**: An award MUST record on the record the real balance and the total deposits at the
  moment of award — the baseline from which the wallet computes the clawback a cancel or a hold
  applies (FR-017, FR-023), as `casino-service`'s withdrawal and cancel-bonus routes compute it today:
  deposits since award = max(0, total deposits now − total deposits at award); clawback = max(0,
  real balance now − real balance at award − deposits since award); then clamped to real balance when
  applied. A caller MUST NOT supply a clawback, and no other service keeps the baseline.
- **FR-016**: Forfeit MUST zero and close as `FORFEITED` the primary active record, or the active
  record a grant reference names; none open is `NO_ACTIVE_BONUS`; a named award that is not active
  is `NO_ACTIVE_BONUS`.
- **FR-017**: Cancel MUST compute the clawback from the record's baseline (FR-015a) and apply it to
  real, clamped to real balance, whether or not a record is open — zero for a record that has no
  baseline (one the migration, spec 019, carried over without the original's snapshot; feature 244,
  player-favourable); MUST forfeit the primary active record or the named award's; a named award
  not active is `GRANT_NOT_OPEN`; MUST write a `bonus_cancel` transaction whose balance before is
  the sum of active balances before the cancel.
- **FR-018**: Close pending cap MUST close as `CLOSED` the first `CONVERTED_PENDING_CAP` record with
  no balance change; none is `NOT_PENDING_CAP`.
- **FR-019**: Switch MUST make a named active record primary by giving it a priority below every
  open record's, in one write; `GRANT_NOT_FOUND` for an unknown award, `GRANT_NOT_ACTIVE` for one
  not active, and "already primary" when it already sorts first.
- **FR-020**: Records MUST be ordered by priority ascending then granted time ascending wherever
  the spec says "primary", "first" or "in order"; open means `ACTIVE` or `CONVERTED_PENDING_CAP`.
- **FR-021**: Restricted balance MUST always equal the sum of open records' balances; it is derived,
  never commanded.

**Deposits and holds**

- **FR-022**: A deposit MUST arrive on the deposit route the brand grants `payments`/`merchant` under
  ankka 040 (spec 001 FR-016), keyed by the payment id (spec 001 FR-017); before any credit it MUST
  ask `responsible-gambling`'s `may-deposit` within the project with the player, currency, amount and
  the wallet's deposit sums of the last 1, 7 and 30 days (FR-030; spec 010 FR-007), and
  MUST refuse the deposit — crediting nothing, the wallet never called — as
  `EXCLUDED`, `COOLING_OFF` (naming the end) or `OVER_LIMIT` (naming the limit, the amount used and
  the remaining allowance) as answered, or `RG_UNAVAILABLE` when there is no answer; the refusal is
  final to `merchant` (spec 004 FR-021). The check and the credit are the two steps of a
  `DepositWorkflow` per payment id, started by the route as the atomic claim; the refusal or the
  wallet's outcome is the workflow's state, which answers every repeat of the payment id for the life
  of the ledger (clarification 2026-10-10). When allowed, it MUST credit real balance, write a `deposit` transaction with payment
  reference, terminal, PSP and scheme, resolve the turnover multiplier and definition (FR-004a),
  create a deposit record with required turnover = amount × multiplier (`CLEARED` at multiplier 0,
  else `PENDING`), add the requirement to deposit turnover
  pending, and add the amount to deposits since conversion on every `CONVERTED_PENDING_CAP` record.
  A repeated payment reference returns the first call's outcome and writes nothing (FR-002); the
  original returned the balances of the moment.
- **FR-022a**: The wallet MUST serve a second route granted to `payments`/`merchant`, on which
  `merchant` reports a payment's start and later outcomes (initiated, failed, expired, abandoned,
  refunded, dispute opened, dispute closed), keyed by the payment id and the outcome; an outcome MUST move no money in the
  wallet (the original's wallet moves money on capture only). From the captured deposit and from each
  outcome the wallet MUST produce the brand's `payments.payments` messages — `payment.initiated`,
  `payment.captured`,
  `payment.failed`, `payment.expired`, `payment.abandoned`, `payment.refunded`,
  `payment.dispute.opened`, `payment.dispute.closed` — keyed by the payment id, carrying the player
  id as subject and the payment provider on every family (as `merchant` names it on the capture and
  on each outcome, spec 004 FR-022), so the brand's readers never depend on the shared project's
  topics and `reporting` groups payments by provider (spec 001 FR-011, spec 015 FR-011).
- **FR-023**: A hold MUST be refused, in this order, as `WITHDRAWAL_REQUEST_ALREADY_PENDING` while a
  request is pending, `INSUFFICIENT_FUNDS` ("Insufficient real balance for withdrawal") when real
  is below the amount, and `WITHDRAWAL_BLOCKED_TURNOVER` ("Deposit turnover requirements not yet
  met") while turnover is pending. It MUST then forfeit every active record by its policy (`none`
  untouched; `funds`, `funds_and_winnings`, `cancel_promotion` forfeited; the last also reporting
  the award for cancellation with reason "withdrawal"), compute the clawback from the forfeited
  records' baselines (FR-015a) and apply it once to real only when a forfeited policy is
  `funds_and_winnings` or `cancel_promotion`, refuse as
  `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT` when real after clawback is below the amount, draw
  the amount down across `CONVERTED_PENDING_CAP` records in order (deposits since conversion first,
  then remaining allowance), void the excess as `bonus_cap_void`, close a record whose allowance is
  exhausted and every cap record when real after payout is at or below zero, debit real by the
  full amount, record the request as pending, and report whether confirmation was required (any
  active record under a policy other than `none`).
- **FR-023a**: The wallet MUST serve a hold preview: for an amount, the refusal the hold would give,
  whether confirmation would be required, the bonus balance, the clawback and the total forfeit,
  computed exactly as the hold computes them and moving nothing; `payouts` (spec 007 FR-006) reads
  it before placing the hold.
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
- **FR-030**: The deposit sums `responsible-gambling` decides deposit limits on (the last 1, 7
  and 30 days) MUST be the wallet's own figures, exact to the minor unit and current at the moment
  of the deposit they are asked for (FR-022): the wallet keeps its last 30 days of deposits in its
  state and never takes them from a view. The sums by type the views serve (FR-029) are for display
  and reporting and are eventually consistent.
- **FR-031**: A consumer over the wallet's events MUST produce `money.transactions` messages of the
  types the topic contract names (`deposit_confirmed`, `bet_placed`, `win_credited`,
  `bet_rolled_back`, `round_settled_as_loss`, `restricted_funds_awarded`,
  `restricted_funds_converted`, `restricted_funds_forfeited`, `admin_adjusted`, `hold_placed`,
  `hold_released`, `withdrawal_reversed` for FR-025, `provider_credited` for FR-027), keyed by the
  wallet and carrying the player id as subject (spec 001 FR-012), each a stable `.v1` shape that
  leaks no event class; `restricted_funds_forfeited` MUST carry the clawback applied (0 when none,
  FR-017, FR-023) and `hold_placed` the amount voided as `bonus_cap_void` (0 when none, FR-023), so
  every movement the wallet makes is on the topic; every family MUST carry the real and restricted
  balance after the movement, so `engagement` (spec 014) and `reporting` (spec 015) keep a balance
  view from facts and never read the wallet; `deposit_confirmed` MUST carry the wallet's deposit
  ordinal (1 for the wallet's first confirmed deposit, counted per player and currency), which
  `rewards` reads as the deposit number (spec 011 FR-012);
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
  rounds (bet keys), restricted-funds records (embedded; see below), withdrawal requests by request
  id with amount and status (pending, released, confirmed, reversed — at most one pending; a
  confirmed one is kept so a reversal, FR-025, can find it), its most recent 1,000 command keys
  with a digest of each command and its outcome (FR-002), the last 1,000 settled bets for a late
  rollback, and the brand's bet switches read at bet time.
- **Restricted-funds record** (part of the wallet's state, one per award): id, grant reference,
  bonus reference, status (`ACTIVE`, `CONVERTED_PENDING_CAP`, `FORFEITED`, `CLOSED`), priority,
  granted and expiry times, balance, wagering requirement, release progress, maximum convertible
  amount, remaining allowance, deposits since conversion, real balance and total deposits at award
  (the clawback baseline, FR-015a), open round count, and the rule snapshot:
  maximum stake and enforcement, contribution default and overrides, scope include and exclude,
  violation mode, funding order, win allocation, release-progress scope, forfeit on withdrawal.
- **Bet** (part of the wallet's state while open; a transaction after): round key, amount, real leg,
  record legs, contributions, wallet type, status (open, settled, voided, loss), session, game.
- **Deposit record**: payment reference, amount, turnover multiplier and definition, required
  turnover, status (`PENDING`, `CLEARED`).
- **Transaction** (an event, projected to a view row): type (`deposit`, `bet`, `win`, `rollback`,
  `loss`, `bonus_credit` (a forfeit is a voided `bonus_credit`), `bonus_cancel` (also a hold's
  forfeit), `bonus_cap_void`, `withdrawal`, `withdrawal_return`, `withdrawal_reversal`, `admin_adjust`,
  `game_provider_credit`), wallet type,
  amount, balance before and after, currency, status, references, reason, actor, created time.
- **Brand bet switches** (key value, one per brand): contribution rate by game type, bonus wagering
  counts, partial splits allowed, maximum concurrent awards.
- **Deposit flow** (workflow, `player/currency/paymentId`): the deposit request, its status
  (`new`, `checking`, `refused`, `done`) and its outcome; the record of the payment id for the life
  of the ledger (FR-022).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every recording under *Recordings* replays green against the wallet through the
  `game-provider`, `rewards` and `payouts` routes, with balances equal to the minor unit.
- **SC-002**: A bet's check and ledger change are one command: under 200 concurrent bets on one
  wallet, the final real balance equals the starting balance minus the sum of accepted stakes,
  and no bet is accepted that the balance at its moment could not fund.
- **SC-003**: A provider callback (bet, win, rollback, loss) completes in under 50 ms at the
  wallet's median and under 250 ms at its 99th percentile on a wallet with 10,000 transactions,
  with no call to any service.
- **SC-004**: The #662 case (a 500,000 win on a 50,000 stake, and a 5,000,000 win on a 500,000
  stake) credits the exact amount.
- **SC-005**: Replaying every production wallet's history from the migration's opening event
  through the recorded commands reproduces today's balances exactly, wallet by wallet.
- **SC-006**: Any money command repeated under its key within the wallet's recent keys (1,000)
  returns the first outcome and adds no event, and for the life of the ledger is answered by the
  caller's workflow under that key; and the same key carried by a different command is refused as
  `KEY_REUSED` with no event, proven by a test that does both for every command type.
- **SC-007**: The 1-, 7- and 30-day deposit sums match a direct sum of the view's rows for every
  wallet in the parity run.

## Changes from the original

- A deposit is refused before it is credited when `responsible-gambling` says so, failing closed,
  and the refusal is recorded by the deposit workflow, not the wallet (FR-022); today the deposit
  check runs after the credit and is ignored. The bet-time check, which fails open in the ST8
  adapter today, is `game-provider`'s (spec 003 FR-012; spec 001's defect list).
- `deposit_confirmed` carries the wallet's deposit ordinal (FR-031); the original has no such field
  and `rewards`' evaluator counted deposits itself.
- The brand's deposit turnover terms and a player's override move into the wallet from
  `casino-service`'s brand configuration (FR-004a); the clawback is computed by the wallet from a
  baseline recorded at award (FR-015a), not supplied by the caller; a hold preview is served
  (FR-023a).

- The twin, the HTTP dispatch to guests, the graph projection of records, the
  `open_restricted_funds_refs` pipe set and the `HAS_TRANSACTION` scan fallback are gone: records
  are part of the wallet's state and a round is found by key.
- Idempotency by key moves into the wallet for every money command (today only deposits are
  idempotent in the wallet, by `hasDepositTransaction` on the payment reference; providers' retries
  are absorbed by the ST8 adapter's store), and a seen key carried by a different command is refused
  as `KEY_REUSED` (FR-002); the original has no such check. The wallet keeps only its recent 1,000
  keys; the caller's workflow is the lifetime record (R17). The original's `WalletActionDispatcher` mints a random
  UUID as the idempotency key of every dispatch, so a retried bet, win or adjustment that passes the
  adapter moves money twice (spec 001, known defects; research R10 of spec 001).
- `create-withdrawal-request` and the request lifecycle become hold, release, confirm and reverse;
  the withdrawal request and payout destination twins move to `payouts` (spec 007). Money movement
  is unchanged. A reversal writes a `withdrawal_reversal` transaction; the original wrote no row for
  a reversal, only the balance change.
- `open-migrated` is new (FR-001): the one command that opens a wallet with the balances the
  original holds, for the migration (spec 019) and for the fixtures whose starting state no ordinary
  command produces (a restricted balance with no record, FR-014). The original has no equivalent;
  its wallets were created by the twin substrate.
- The brand's bet switches are a key value entity in the wallet, not a read of the brand twin.
- Dropped as stubs or migration tooling: the Phase 2 backfill and rollback routes, the three
  pre-flight routes, the crypto-wallet routes (to `payouts`), `check-open-round`, the audit edge
  write per transaction (replaced by `platform.audit`), the metrics' active-round gauge (a view).
- The 500-record traversal limit and the 2,000-twin scan cap have no counterpart.
- The brand's `payments.payments` facts are produced by the wallet from what `merchant` hands it over
  the granted routes; no brand service reads the shared `payments` project's topics (spec 001 FR-011).
- `money.transactions` gains `withdrawal_reversed` and `provider_credited`, and a clawback amount on
  `restricted_funds_forfeited` and a voided amount on `hold_placed` (FR-031); the contract built for
  spec 001 lacked them, so this feature adds them to `casino-contract` and its schema.

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

- The clawback a hold or cancel applies is the wallet's to compute from the baseline it records at
  award, by the formula FR-015a states; no caller supplies a clawback (settled with spec 007, and
  with spec 011 on 2026-10-09).
- A deposit's turnover multiplier and definition are the wallet's to resolve (FR-004a): the brand's
  terms live beside its bet switches and a player's override on the player's wallet, both written by
  operator routes; nothing arrives on the deposit command (settled at reconciliation with spec 004,
  which lists the fields as moved here).
- Free-spin rounds that never debit the wallet (an award-funded round) are the game provider's to
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
- Spec 010 (responsible-gambling): `may-deposit`, asked within the project by the deposit workflow
  and failing closed (FR-022). `may-bet` is spec 003's (its FR-012).
