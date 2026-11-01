# Feature Specification: The Predictions Module — Markets and an Exchange in a Brand

**Feature Branch**: `023-predictions-module`

**Created**: 2026-10-21

**Status**: Built

**Input**: User description: "The `predictions` module: two services, `markets` and `exchange`,
in a brand's project, composed from thinkmorestupidless's private libraries `markets` and
`exchange` (ankka-predictions spec 004), which carry every rule of a market's lifecycle and of a
limit order book. This module writes what the libraries leave to the consumer: the gates an order
passes, the ledger port on the brand's wallet (spec 022), who may approve, resolve and void, the
catalogue players read, the facts on the contract's topics, the price stream, the optional mirror
into a reasoning service, and erasure. A brand entitled to it trades; a brand bound to a pool
(spec 024) runs the module without its own books."

## Clarifications

### Session 2026-10-31

- Q: Does approving a market need a second operator, and may the drafter approve? → A: No second
  operator; the drafter may approve, for now.
- Q: Which of the play gate, `may-bet` and limits apply to an order under a DCM? → A: Not yet
  known, so it is configuration: the gates an order runs are a brand value, all three on by
  default; when the DCM's requirements are known the value changes, not the code.
- Q: Does a brand bound to a pool belong in this spec's first version? → A: No; FR-010's
  forwarding moves to spec 024, and a brand runs its own books here.
- Q: Who may change a market or move its close (ankka-predictions request item 10)? → A: The same
  `markets` permission as every other act on a market; the domain checks none.
- Q: What does one share pay, and in what are prices quoted? → A: One major unit of the market's
  currency (100 minor units); prices are whole minor units from 1 to 99, for every brand.
- Q: Where do a brand's categories and currencies come from? → A: The brand's values list the
  categories; a market's currency must be one the brand's wallet holds. `markets` refuses any
  other before the library sees the draft.
- Q: Who sets the exchange's limits? → A: The brand's values give the default maximum order cost
  and maximum position; an operator may set a market's own on its draft, and the book opens with
  whichever applies.
- Q: Do fees belong in the first version, and how? → A: Yes, as Kalshi and Polymarket charge them:
  a taker fee of rate × contracts × price × (1 − price), rounded up to the minor unit, makers
  free. The rate is a brand value, 0 by default (no fee).
- Q: Where does a taker fee land? → A: In the brand's fee account, a new account kind `fee` with
  a purpose `fee`, by an amendment to spec 022; charged from the taker's reservation as a split
  is (spec 022 FR-008a).
- Q: Do the 39 proposed predictions terms in the glossary stand? → A: All accepted as defined.
- Q: (found in planning) Where does the taker fee's rule live, given the library reserves only an
  order's cost and does not name a trade's taker? → A: In the exchange library: ankka-predictions
  spec 007 adds the fee to `Open`, the reservation and a trade's legs; this module supplies the
  rate and moves the fee.
- Q: (found in planning) Do players get explicit split and merge in this version? → A: No; complete
  sets arise only from pool matching, charged from reservations. The library's explicit split
  charges from available money after it is recorded; a request asks for one funded by a
  reservation first.
- Q: (found in planning) How do loss limits apply to orders, when no caller of `may-bet` knows a
  player's losses from trading? → A: `responsible-gambling` works them out itself from
  `money.transactions`, trades, fees and resolution payments included (an amendment to spec 010);
  `exchange` sends no sums.
- Q: (found in analysis) Is "the limits" a third gate a brand can switch off apart from
  `may-bet`? → A: No; `responsible-gambling` applies the limits inside `may-bet`, so a brand's
  gates are two switches, `play-gate` and `may-bet`, and turning `may-bet` off turns the limits
  off with it.
- Q: (found in implementation) Is the player id a personal field on the exchange topics? → A: No:
  spec 001's rule holds, `player_id` is a pseudonym and not personal, so the predictions facts
  carry no personal field.
- Q: (found in implementation) The contract's fields were flat; how are a trade's legs and its
  prices shaped? → A: As nested objects: the contract learns nested case classes in its schemas,
  its personal-field scan and its compatibility rules.

## Context

Decision 0055 makes eitheror the owner of the prediction market and this repository its home, as
a module beside the casino. The rules of the market are not written here. ankka-predictions holds
them as two private libraries: a pure domain each for a market and for a book, and components
with three ports: a ledger port (reserve, release, settle, pay), a gate port (may this player
place this order) and a resolution port (who may resolve and void). This spec is the consumer's
half: it supplies the ports from the brand's `wallet`, `responsible-gambling`, `kyc` and `players`
services and the brand's operator roles, registers the components, and writes the endpoints,
facts and reads around them. Where ankka-predictions specs 002 and 003 stated an orchestration
requirement, it is restated here; where they stated a domain rule, it is the library's and is
cited, not repeated.

Nothing casino-specific enters the libraries: the gate port answers allow or a refusal with a
reason, and this module turns the casino's refusals (excluded, cooling off, over limit, play gate)
into that reason.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An order passes the brand's gates and is funded before it reaches a book (Priority: P1)

A player places a buy order through the brand's player routes. Before the library's book sees it,
`exchange` checks that the player's account is active (`players`), that the KYC play gate allows
play (`kyc`), that the bet gate allows a stake of the order's cost (`responsible-gambling`
`may-bet`), and reserves quantity times price, and with a taker fee the most that fee can be, in
the player's wallet under the order id (spec 022). An order any check refuses never reaches the book and reserves nothing, and the refusal
names the gate and the casino's reason. A sell order needs the position; it reserves nothing.

**Why this priority**: The gates are the regulatory line between a market and a player; the
reservation is what makes a resting order trustworthy.

**Independent Test**: Place orders with each gate scripted to allow and refuse and with a short
wallet; only the allowed, funded order rests, and each refusal names its gate.

**Acceptance Scenarios**:

- added `features/predictions/orders.feature`: a buy order is checked against the account, the play gate and the bet gate, then funded, then rests
- added `features/predictions/orders.feature`: an order the play gate refuses rests nowhere and reserves nothing
- added `features/predictions/orders.feature`: an order the bet gate refuses rests nowhere and names the casino's reason
- added `features/predictions/orders.feature`: an order the wallet cannot fund is refused as insufficient funds and rests nowhere
- added `features/predictions/orders.feature`: a sell order needs the position and reserves nothing
- added `features/predictions/orders.feature`: with a taker fee a buy order reserves its cost and the most its fee can be

---

### User Story 2 - Trades and resolutions move money through the brand's wallet (Priority: P1)

When the book matches, `exchange` settles each trade through the wallet keyed by the trade id, at
least once; a complete set is split from and merged into the market's pool account; when a market
is final, each position is paid from the pool account keyed by the market and the position, one
unit per share of the resolved outcome, and a void pays each share its equal part rounded down.
A wallet that refuses a settlement after the book has traded is the one case the libraries leave
open; here it is recorded, retried, and raised to an operator.

**Why this priority**: Money moving wrongly is the failure a regulator cares about.

**Independent Test**: Match two orders, resolve, void; the wallet's facts carry the moves; kill
the service between trade and settlement and restart; settle once.

**Acceptance Scenarios**:

- added `features/predictions/settlement.feature`: a trade is settled through the wallet keyed by the trade, once
- added `features/predictions/settlement.feature`: a final market pays each position from the market's pool account
- added `features/predictions/settlement.feature`: a settlement the wallet refuses is recorded, retried and raised to an operator
- added `features/predictions/settlement.feature`: a taker's fee moves to the brand's fee account keyed by the trade, and the maker pays none

---

### User Story 3 - Operators run the market's lifecycle under their brand's roles (Priority: P1)

An operator with the brand's `markets` permission drafts a market, approves it, closes or voids
it, and resolves it with the authority's answer; a final market is one the review window has
passed on. The resolution port answers from the brand's roles: the `brand_<slug>` role with the
`markets` permission may draft, approve and resolve; whether approval needs a second person is
carried as a clarification. Every change is a fact on `markets.events`.

**Why this priority**: Without the lifecycle there are no markets to trade.

**Independent Test**: Run the lifecycle as an operator with and without the permission; every
refused act names the role.

**Acceptance Scenarios**:

- added `features/predictions/lifecycle.feature`: an operator with the markets permission drafts, approves, closes, resolves and voids a market
- added `features/predictions/lifecycle.feature`: an operator without the markets permission is refused naming the permission
- added `features/predictions/lifecycle.feature`: every change to a market is a fact on the brand's markets topic
- added `features/predictions/lifecycle.feature`: a draft in a category the brand does not offer is refused naming the category
- added `features/predictions/lifecycle.feature`: a draft in a currency the brand's wallet does not hold is refused naming the currency
- added `features/predictions/lifecycle.feature`: a market opens with the brand's limits unless its draft sets its own

---

### User Story 4 - Players read the catalogue, prices and their positions (Priority: P2)

A player sees open markets by category with each outcome's last price, best bid and ask and
depth, a price stream, their own orders and positions, and their trades; a draft or unopened
market is not shown. Reads are the brand's player routes under the player realm; nothing is
shown about another player.

**Why this priority**: The interface the brand builds (its player site) needs these and nothing
else from the module.

**Independent Test**: Read as a player; confirm only open markets, only own orders and
positions.

**Acceptance Scenarios**:

- added `features/predictions/catalogue.feature`: a player sees open markets and no draft or unopened market
- added `features/predictions/catalogue.feature`: a player sees each outcome's last price, best bid, best ask and depth of an open market
- added `features/predictions/catalogue.feature`: a player sees their own orders, positions and trades and no other player's

---

### User Story 5 - Facts, the price stream and the reasoning mirror (Priority: P2)

Every order, trade and price change is a fact on `exchange.orders`, `exchange.trades` and
`exchange.prices`, keyed by the market as the contract says, carrying the player id as a plain
id (spec 001 FR-024); `reporting` and the shared `audit` read them. When a brand's values name a
reasoning service, `markets` mirrors each approved market as a question with one hypothesis per
outcome, and `exchange` writes a price observation per outcome at most once a minute; with no
reasoning service named, nothing is mirrored and nothing fails.

**Why this priority**: Facts are how the brand reports and audits; the mirror is optional by
design.

**Independent Test**: Trade with and without a reasoning service configured; facts on every
topic either way; observations only with.

**Acceptance Scenarios**:

- added `features/predictions/facts.feature`: every order, trade and price change is a fact on the brand's exchange topics
- added `features/predictions/facts.feature`: a brand with a reasoning service named mirrors each approved market and writes price observations at most once a minute per outcome
- added `features/predictions/facts.feature`: a brand with no reasoning service named mirrors nothing and trades

---

### Edge Cases

- Erasure: the player id is a pseudonym, not a personal field (spec 001 FR-024, FR-006 here);
  erasure cancels the erased player's resting orders first, records the erasure, and leaves
  amounts, ids and times; an open position stands until the market is final.
- A market closes while orders rest: every order is cancelled and its reservation released, as
  the library's close says; the facts say why.
- A brand bound to a pool is spec 024's, not this spec's: here every brand runs its own books.
- An operator suspends an open market with a reason and resumes it (ankka-predictions spec 006
  FR-007a, the venue's emergency authority); the exchange suspends and resumes with it (spec 005
  FR-015a). `markets` drives `exchange` as it does for open and close, and both are facts.
- The gates under a Designated Contract Market: the first licence (Assumptions) is not a
  gambling licence, and which of `kyc`'s play gate and `responsible-gambling`'s `may-bet` and
  limits it asks of an order is not yet known. The brand's values say which run (FR-002), all
  three by default; `players` (account active) runs always.
- Approval: one operator with the `markets` permission approves a draft, and the operator who
  drafted it may (Trevor, 2026-10-31). Four-eyes approval, carried from ankka-predictions spec 002,
  is not required for now.
- The casino's `may-bet` with a cost rather than a bet: spec 010 is asked for a stake of the
  order's cost; whether a reservation counts while it rests is spec 022's clarification.

## Changes from the original

The original (`eitheror-platform`) has no prediction markets: everything in this module is new
(constitution I). No recording exists to replay, and none is made.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The module MUST be two services, `markets` and `exchange`, composed from
  ankka-predictions' libraries at a pinned version, registering the libraries' components
  explicitly and adding no lifecycle, matching, position or settlement rule of its own.
- **FR-002**: `exchange` MUST implement the gate port by asking, in order, `players` (account
  active, always), then those the brand's values enable of `kyc` (play gate) and
  `responsible-gambling` (`may-bet` with the order's cost, which applies the player's limits),
  both by default, and MUST turn each refusal into the port's reason naming the gate and the casino's
  reason. A gate the values disable is not asked.
- **FR-003**: `exchange` MUST implement the ledger port on the brand's wallet (spec 022): reserve
  under the order id before an order reaches a book (the cost, and with a taker fee the most it
  can be, FR-015), release on cancel, expiry or close, settle
  under the trade id at least once, split and merge against the market's pool account, pay under
  the market and position; a settlement the wallet refuses MUST be recorded, retried and raised
  on `platform.audit`.
- **FR-004**: `markets` MUST implement the resolution port from the brand's roles: the
  `brand_<slug>` role with the `markets` permission may draft, change, approve, open, suspend,
  resume, close, move the close of, resolve, correct and void a market; every other caller is
  refused naming the permission. The libraries' entity asks the port nothing for a change or a
  move of the close, so `markets` checks the permission before it calls them.
- **FR-005**: Player routes MUST be of the player class under the brand's player realm and MUST
  show open markets with last price, best bid and ask and depth, a price stream, and the player's
  own orders, positions and trades, never another player's; operator routes MUST be of the
  operator class. The price stream MUST number its messages per market, from the exchange's
  sequence, and the depth read MUST carry the sequence it is current to, so that a client that
  misses a message sees the gap and resyncs from the depth read; a stale read MUST say so.
- **FR-006**: The contract MUST gain the topics `markets.events`, `exchange.orders`,
  `exchange.trades` and `exchange.prices`, keyed by the market as ankka-predictions specs 002 and
  003 state, with the player id a plain id as on every topic (spec 001 FR-024: a pseudonym, not
  personal) and a trade's legs and prices as nested objects; `reporting` and the shared `audit` read them by the
  existing grant pattern.
- **FR-007**: When the brand's values name a reasoning service, `markets` MUST mirror each
  approved market as a question with one hypothesis per outcome and the market venue named for the
  brand, and `exchange` MUST write one price observation per outcome at most once a minute; with
  none named, nothing MUST be mirrored and nothing refused.
- **FR-008**: Erasure MUST cancel the player's resting orders first, record the erasure, and
  leave positions to pay or void as the market does (amended 2026-11-01: the player id is a
  pseudonym, FR-006, so there is no personal field on the exchange's facts to mark).
- **FR-009**: The module's manifest MUST depend on `core` at the version that carries spec 022
  FR-008a, declare its four topics and declare no grant within the brand; the grants to and from
  a pool instance are spec 024's.
- **FR-010**: Moved to spec 024 (Trevor, 2026-10-31): a brand bound to a pool forwarding to the
  pool's instance. Here every brand runs its own books.
- **FR-011**: `exchange` MUST reconcile, per market and at least daily, what its events say was
  reserved, released, settled, split, merged and paid against what the wallet's facts say moved
  for the market's pool account and its players (spec 022's families), and MUST raise any
  difference on `platform.audit` naming the market, the key and both figures; nothing MUST
  correct a difference by itself. A market is reconciled once more when it is final. An order's
  reservation is drawn down by `settlement_debit` and, for a trade through the pool, by
  `account_debit` with an `order_id` and its `released_cents` (spec 022 FR-008a), and by a taker
  fee's move into the brand's fee account (FR-015).
- **FR-012**: A share MUST pay one major unit of the market's currency (100 minor units), and a
  price MUST be a whole number of minor units from 1 to 99; `exchange` opens every book with that
  unit.
- **FR-013**: `markets` MUST refuse a draft or a change whose category is not one the brand's
  values list, naming the category, and whose currency the brand's wallet does not hold (the currencies its values
  list), naming the currency, before the library sees it.
- **FR-014**: `exchange` MUST open a market's book with the maximum order cost and maximum
  position its draft sets, else the brand's values' defaults; a draft MAY set either.
- **FR-015**: With a taker fee rate above 0 in the brand's values (0 by default, in basis
  points), each trade's taker MUST pay ⌈rate × contracts × price × (100 − price) / 1,000,000⌉
  minor units into the brand's fee account keyed by the trade; the maker pays nothing. A buy
  order MUST reserve, beside its cost, ⌈rate × contracts × 2,500 / 1,000,000⌉, the most its fee
  can be; what a fill does not use is released with the order. The rule is the exchange
  library's (ankka-predictions spec 007); `exchange` opens each book with the brand's rate and
  makes the fee's move. The fee account is spec 022's, amended with the kind `fee`.
- **FR-016**: Players MUST NOT be offered an explicit split or merge in this version; a complete
  set is split or merged only by a trade through the pool.

### Key Entities

- **Market, outcome, order, book, trade, position, complete set, resolution**: the libraries';
  their words are ankka-predictions' glossary, cited here.
- **Gate refusal**: the port's reason, naming the gate (`account`, `play gate`, `bet gate`) and
  the casino's reason.
- **Reasoning service**: a value in the brand's values naming where the mirror goes; absent by
  default.
- **Taker fee rate**: a value in the brand's values, in basis points, 0 by default.
- **Fee account**: the brand's wallet account of kind `fee` that taker fees move into.
- **Categories and limits**: values in the brand's values: the categories a brand offers, and
  the default maximum order cost and maximum position.
- **Gates**: a value in the brand's values naming which of the play gate and `may-bet` (which
  applies the limits) an order runs; both by default.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The module's code holds no matching, position, settlement or lifecycle rule: every
  such scenario runs in ankka-predictions and none here.
- **SC-002**: An order is checked, funded and resting within 50 ms at the median and 200 ms at
  p99 under 200 orders per second per brand in the testkit, gates and wallet included.
- **SC-003**: Every scenario of ankka-predictions `features/exchange/orders.feature` passes here
  with the casino's real gates and wallet standing in for the scripted ports.
- **SC-004**: A brand with `predictions` enabled and `casino` not serves no game route and
  trades.

## Assumptions

- The brand's player site is the brand's own, outside this repository, as the casino's is; the
  routes here are what it calls.
- The reasoning service, when named, is ankka-reasoning 0.2.0 run as a shared project the brand
  is granted to call; its image is private and pulled with the brand's credentials.
- Forecasts, the conversational interface, evidence and curation (ankka-predictions specs 004 to
  008 as first numbered) are later modules or later versions of this one.
- The first licence is in the United States, as a CFTC Designated Contract Market (Trevor,
  2026-10-31). What the licence asks of the venue lands here or in a successor: emergency
  authority is the market's suspend and resume (this spec); position limits are the exchange's
  maximum position per market, with the DCM's accountability levels as the brand's values;
  daily publication of trading information is `reporting`'s over `exchange.trades`;
  recordkeeping is the facts and the shared `audit`. Market surveillance (wash trading,
  spoofing, layering, self-matching across accounts beyond the library's own-owner rule) is a
  later spec over the same facts and is not in this one.

## Dependencies

- ankka-predictions spec 004 and its libraries; spec 021 (the module model), spec 022 (the
  wallet), with an amendment adding the account kind `fee` and the purpose `fee`;
  ankka-predictions spec 007 (the taker fee) and the release that carries it; spec 010 amended
  so `responsible-gambling` works out a player's losses itself; specs 008, 009, 010 (the gates), 015 (reporting), 018 (audit).
- ankka-predictions `notes/casino-requests.md` item 9: a trade matched through the pool charges
  each buyer's reservation into the market's pool account, as spec 022 FR-008a's split charged
  from a reservation; a trade through a merge is one merge per selling leg (spec 022 FR-008).
