# Feature Specification: Wallet Primitives — Reservations, Settlement, Payouts and Non-Player Accounts

**Feature Branch**: `022-wallet-primitives`

**Created**: 2026-10-21

**Status**: Draft

**Input**: User description: "Amends spec 002: the wallet gains what an exchange needs and a
casino round does not. A reservation sets money aside from real balance against a caller's key
and is released in whole or part. A settlement moves money from one player's reservation to
another player's real balance in one keyed command. A payout credits a player's real balance from
a non-player account. Non-player accounts are ledgers in the wallet service owned by a service
principal: a market's pool account, a pool's clearing account with a credit line. Every move is
keyed and idempotent; holds and reads learn about reserved money; every change is a fact on
`money.transactions`; nothing the casino does today changes."

## Context

The wallet of spec 002 is a round's wallet: a bet funds and opens a round in one step, a win or a
rollback closes it, a hold takes a withdrawal out. A prediction market (spec 023) needs something
the round model cannot say. An order rests in a book for hours with its cost set aside; a trade
moves money from one player to another; a position pays at resolution from money that belongs to
no player. ankka-predictions spec 001 wrote those as a wallet of its own; decision 0055 keeps one
wallet per player per currency and adds the primitives here, so that the same real balance funds
a spin and a share.

Three rules carry over unchanged from spec 002 and bind everything below: money is exact minor
units in 64 bits (FR-003); every move carries a caller's key and a repeat returns the first
outcome (FR-002, with the 1,000-key window and the caller's workflow as the lifetime record); the
wallet keeps restricted funds apart from real balance (FR-021). Reservations and settlements touch
real balance only: restricted funds are never reserved, never settled, never paid. A bonus on a
market is a product decision this spec does not take.

## Clarifications

### Session 2026-10-25

- Q: Does reserved money count towards a player's limits while the order rests? → A: As a stake,
  yes, from reservation, returned on cancellation; as a loss, only at settlement, when it is real
  (Assumptions).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Money is reserved against an order and released (Priority: P1)

The exchange asks the wallet to reserve 600 for order `o-1`. Real balance stays 1,000 but 600 of
it is reserved: available for a bet or a hold is 400. The exchange later releases 200 of it when
part of the order is cancelled, and the rest when the order expires. A reservation above
available is refused as insufficient funds. A reservation keyed by an order already reserved
returns the first outcome.

**Why this priority**: Every order starts here; without it a book cannot trust that a resting
order is funded.

**Independent Test**: Reserve, release in part, release the rest, reserve beyond available, repeat
a key: balances and refusals as stated, and the transactions written.

**Acceptance Scenarios**:

- added `features/wallet/reservations.feature`: a reservation sets money aside from real balance and reduces what is available
- added `features/wallet/reservations.feature`: a reservation above the available amount is refused as insufficient funds
- added `features/wallet/reservations.feature`: a release returns part or all of a reservation to the available amount
- added `features/wallet/reservations.feature`: a reservation repeated under its key returns the first outcome and moves nothing
- added `features/wallet/reservations.feature`: restricted funds are never reserved

---

### User Story 2 - A trade settles between two players in one keyed command (Priority: P1)

Ada's buy order at 60 for 10 shares matches Bob's sell. The exchange asks the wallet to settle
trade `t-1`: 600 from Ada's reservation on `o-1` to Bob's real balance. If Ada's order was at 65
and the trade at 60, the 50 the trade did not use is released back to Ada's available amount.
The settlement is one command keyed by the trade id across two wallets; a repeat returns the
first outcome; a crash between the two wallets is completed, never left half done.

**Why this priority**: The move between two players is the one the round model has no word for.

**Independent Test**: Settle a trade, repeat it, settle at a better price than the order, kill the
service between the debit and the credit and restart: balances as stated every time.

**Acceptance Scenarios**:

- added `features/wallet/settlement.feature`: a settlement moves the trade's cost from the buyer's reservation to the seller's real balance
- added `features/wallet/settlement.feature`: a settlement at a better price than the order releases the difference to the buyer
- added `features/wallet/settlement.feature`: a settlement repeated under the trade's key returns the first outcome and moves nothing
- added `features/wallet/settlement.feature`: a settlement interrupted between its two wallets is completed on restart

---

### User Story 3 - Non-player accounts hold money that belongs to no player (Priority: P1)

A market's complete sets are backed by money in the market's pool account, a ledger in the wallet
service owned by the `exchange` principal and keyed by the market id and the currency. Splitting
a set moves one unit from a player's real balance into it; merging moves one unit out; a payout at
resolution moves one unit per winning share out to each player. A pool account never goes below
zero. A clearing account, owned by a pool's principal (spec 024), is the same ledger with a
credit line: it may go below zero up to the line and no further. An operator adjusts a non-player
account with a reason and a signature as they adjust a wallet.

**Why this priority**: Without a ledger for money that is nobody's, the pool invariant has no
place to hold.

**Independent Test**: Split, merge, pay out; try to take a pool account below zero; take a
clearing account to its line and one past it.

**Acceptance Scenarios**:

- added `features/wallet/accounts.feature`: a non-player account is a ledger owned by a service principal and keyed by what it is for
- added `features/wallet/accounts.feature`: a resolution payment credits a player's real balance from a non-player account keyed by the market and the position
- added `features/wallet/accounts.feature`: a pool account is refused a move that would take it below zero
- added `features/wallet/accounts.feature`: a clearing account may go below zero to its credit line and no further
- added `features/wallet/accounts.feature`: an operator adjusts a non-player account with a reason and a signature

---

### User Story 4 - Holds, bets and reads know about reserved money (Priority: P2)

A hold (spec 002 FR-023) and a bet (FR-005) draw on real balance that is not reserved; the hold
preview says so. Balance reports real, reserved and available beside restricted; transactions
list reservations, releases, settlements and payouts by type; the deposit sums `responsible-
gambling` reads are unchanged. A player's statement shows a resting order's money as reserved,
not gone.

**Why this priority**: A hold that ignored reservations would let a player withdraw money a
resting order has promised.

**Independent Test**: With 1,000 real and 600 reserved, preview and place a hold of 500: refused
as insufficient; of 400: placed. Bet 500: refused. Read balance and transactions.

**Acceptance Scenarios**:

- added `features/wallet/reservations.feature`: a hold or a bet draws only on real balance that is not reserved
- added `features/wallet/reads.feature`: a balance read reports the reserved and the available amount beside real balance

---

### Edge Cases

- A release larger than the reservation: refused naming both amounts; nothing moves.
- A settlement naming a reservation that is not the buyer's order: refused; the exchange's book
  and the wallet's reservations are reconciled by the order id, never by amount.
- Erasure of a player with a reservation: the reservation is money, not personal data; it stands
  until the order is cancelled or settled, and the erasure marks the personal fields as spec 002
  FR-033 says.
- A non-player account's owner principal is removed (a module disabled): the account keeps its
  balance; only its owner may move it; an operator adjustment with a reason is how the money
  leaves.
- A reservation is a bet to `may-bet` (spec 010): the exchange asks the gate with the order's cost
  before reserving, so an excluded or cooling-off player rests no order, and the cost counts as
  the player's stake from the moment it is reserved; a cancelled order returns its stake. A loss
  counts only at settlement, when it is real: a resting order's cost is not in the loss sums the
  gate is given, and a cancelled order was never a loss. The wallet asks nothing (Clarifications
  2026-10-25).

## Requirements *(mandatory)*

### Functional Requirements

**Reservations**

- **FR-001**: A reservation MUST set aside an amount of real balance against a caller's key (the
  order id), reduce the available amount by it, and be refused as `INSUFFICIENT_FUNDS` when the
  amount exceeds real balance less what is already reserved; restricted funds MUST never be
  reserved.
- **FR-002**: A release MUST return part or all of a reservation to the available amount, keyed
  by the order id and a sequence the caller supplies, and MUST be refused naming both amounts when
  it exceeds what remains reserved.
- **FR-003**: The available amount MUST be real balance less reserved; a hold (spec 002 FR-023),
  its preview (FR-023a) and a bet (FR-005) MUST draw on the available amount and refuse beyond it
  as they refuse beyond real balance today.

**Settlement and payouts**

- **FR-004**: A settlement MUST move quantity times price from the buyer's reservation to the
  seller's real balance in one command keyed by the trade id, across the two wallets, releasing to
  the buyer any part of the reservation the trade did not use; the exchange supplies that unused
  amount with the settlement (the order's price less the trade's over the filled quantity, plus the
  remainder on the order's last trade), because the wallet holds no order prices; a repeat MUST
  return the first outcome; an interrupted settlement MUST be completed on restart with the same
  key, never left with one side done.
- **FR-005**: A resolution payment (the word `payout` being the withdrawal's, spec 007) MUST credit a player's real balance from a non-player account, keyed by the
  market id and the position, and MUST be refused when the account cannot cover it under FR-007.

**Non-player accounts**

- **FR-006**: A non-player account MUST be a ledger in the wallet service, one currency, owned by
  a service principal, keyed by its kind and what it is for (a pool account by market id; a
  clearing account by the pool's name), created by its owner's first move, never deleted, and moved
  only by its owner or an operator adjustment.
- **FR-007**: A pool account MUST refuse any move that would take it below zero; a clearing
  account MUST carry a credit line set by its owner's entitlement (spec 024) and refuse any move
  that would take it below the negative of the line.
- **FR-008**: Splitting a complete set MUST move one unit per set from the player's available
  amount to the market's pool account; merging MUST move it back; both keyed by the caller.
- **FR-009**: An operator adjustment of a non-player account MUST require a reason and carry the
  actor, taken from the operator's authenticated staff token, as spec 002 FR-026 requires of a
  wallet's; the token is the signature, and no separate signed payload is carried.

**Keys, facts, reads**

- **FR-010**: Every command above MUST carry a caller's key and be idempotent under it exactly as
  spec 002 FR-002 states, within the same window; a key reused with a different command MUST be
  `KEY_REUSED`.
- **FR-011**: Every change above MUST be a message on `money.transactions` of a new family in the
  contract: `reservation_placed`, `reservation_released`, `settlement_debit`,
  `settlement_credit`, `resolution_payment`, `account_debit`, `account_credit`, `account_adjusted`, each
  carrying the balances after it; the families are added, nothing existing changes (spec 001
  FR-013). The first seven are keyed by the wallet and name what happened to it (`account_debit`
  is the wallet debited into an account, `account_credit` the wallet credited from one), carrying
  the wallet's real, restricted and reserved balance after; `account_adjusted` is keyed by the
  account and carries the account's balance after. A move's account side is not a message of its
  own: the wallet-keyed message names the account.
- **FR-012**: Balance MUST report real, restricted, reserved and available; transactions MUST be
  filterable by the new types; the deposit sums MUST be unchanged.
- **FR-013**: The routes MUST be of the service class, admitted in-project to `exchange` and, when
  a brand is bound to a pool, granted to the pool's `clearing` service (spec 024); a player's and
  an operator's reads are under the existing prefixes.

### Key Entities

- **Reservation**: an amount of a wallet's real balance set aside under an order id; released in
  part or whole; drawn down by settlements.
- **Settlement**: one keyed move of a trade's cost from a reservation in one wallet to real
  balance in another.
- **Non-player account**: a ledger owned by a service principal: a pool account (floor zero) or a
  clearing account (floor the negative of its credit line).
- **Available amount**: real balance less reserved; what a bet or a hold may take.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every spec 002 scenario passes unchanged with this feature present; the casino's
  recordings replay with no difference.
- **SC-002**: A reserve, release, settle or payout is answered within 20 ms at the median and
  100 ms at p99 under 500 such commands per second across wallets in the brand's testkit.
- **SC-003**: No sequence of commands leaves available below zero, reserved unmatched by
  reservations, or a pool account below zero; a property suite over random sequences proves it.
- **SC-004**: A wallet's balances are reconstructable from its `money.transactions` facts alone,
  and an account's balance from the `money.transactions` facts that name it: the sum of
  `account_debit` less `account_credit` and `resolution_payment` naming the account, plus its
  `account_adjusted` amounts, in any order, equals the account's balance.

## Changes from the original

Everything here is new. The original (`eitheror-platform`) has no reservation, no settlement
between two players, no non-player account and no resolution payment: its wallet is a round's
wallet, and the exchange that needs these primitives (spec 023) does not exist there.
ankka-predictions spec 001 wrote a wallet of its own with the same moves; decision 0055 brings them
into the brand's wallet instead. No route, command, event, fact or scenario of spec 002 changes
(SC-001); there is no recording to replay for any of this, and no parity test is left.

## Assumptions

- The pool account and the clearing account are the only two kinds of non-player account; a
  third is a change to this spec.
- Until spec 024 exists, a clearing account's credit line is a value its owner sets on the account
  (0 until set, never below what the balance already owes); spec 024 decides where the owner gets
  the value. Setting the line on an account that does not exist yet creates it under the setter,
  as the owner's first move would.
- The exchange reserves before an order reaches a book and settles after a trade, at least once
  (ankka-predictions spec 003 FR-012 and the libraries' ledger port); the wallet is told, never
  asked, which trades happened.
- Money in a non-player account is the brand's liability to its players, reported by `reporting`
  as such; how it is reported is spec 015's to amend.

## Dependencies

- Spec 002 (everything not restated), spec 001 FR-011 to FR-015 (the contract's additive
  families), spec 023 (the caller), spec 024 (the clearing account's owner and line).
- ankka-predictions spec 004 (the ledger port this serves).
