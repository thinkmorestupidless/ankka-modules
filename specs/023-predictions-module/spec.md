# Feature Specification: The Predictions Module — Markets and an Exchange in a Brand

**Feature Branch**: `023-predictions-module`

**Created**: 2026-10-21

**Status**: Draft

**Input**: User description: "The `predictions` module: two services, `markets` and `exchange`,
in a brand's project, composed from thinkmorestupidless's private libraries `markets` and
`exchange` (ankka-predictions spec 004), which carry every rule of a market's lifecycle and of a
limit order book. This module writes what the libraries leave to the consumer: the gates an order
passes, the ledger port on the brand's wallet (spec 022), who may approve, resolve and void, the
catalogue players read, the facts on the contract's topics, the price stream, the optional mirror
into a reasoning service, and erasure. A brand entitled to it trades; a brand bound to a pool
(spec 024) runs the module without its own books."

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
`may-bet`), and reserves quantity times price in the player's wallet under the order id (spec
022). An order any check refuses never reaches the book and reserves nothing, and the refusal
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

- added `features/predictions/catalogue.feature`: a player sees open markets with prices and depth and no draft or unopened market
- added `features/predictions/catalogue.feature`: a player sees their own orders, positions and trades and no other player's

---

### User Story 5 - Facts, the price stream and the reasoning mirror (Priority: P2)

Every order, trade and price change is a fact on `exchange.orders`, `exchange.trades` and
`exchange.prices`, keyed by player or market as the contract says, carrying the player id as a
personal field; `reporting` and the shared `audit` read them. When a brand's values name a
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

- Erasure: orders, trades and positions carry the player id as a personal field (spec 001
  FR-023); erasure marks it and leaves amounts, ids and times; a resting order of an erased
  player is cancelled first, and an open position stands until the market is final.
- A market closes while orders rest: every order is cancelled and its reservation released, as
  the library's close says; the facts say why.
- A brand bound to a pool (spec 024): `markets` and `exchange` serve the player and operator
  routes and forward to the pool's instance; no book runs in the brand. This spec's gates run in
  the brand either way.
- An operator suspends an open market with a reason and resumes it (ankka-predictions spec 006
  FR-007a, the venue's emergency authority); the exchange suspends and resumes with it (spec 005
  FR-015a). `markets` drives `exchange` as it does for open and close, and both are facts.
- The gates under a Designated Contract Market: the first licence (Assumptions) is not a
  gambling licence. [NEEDS CLARIFICATION: which of `kyc`'s play gate and `responsible-gambling`'s
  `may-bet` and limits apply to an order under a DCM, where a prediction market is a derivatives
  contract and not a bet; `players` (account active) and KYC itself apply either way]
- Four-eyes approval: carried from ankka-predictions spec 002. [NEEDS CLARIFICATION: whether
  approving a market needs a second operator, and whether the drafter may approve]
- The casino's `may-bet` with a cost rather than a bet: spec 010 is asked for a stake of the
  order's cost; whether a reservation counts while it rests is spec 022's clarification.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The module MUST be two services, `markets` and `exchange`, composed from
  ankka-predictions' libraries at a pinned version, registering the libraries' components
  explicitly and adding no lifecycle, matching, position or settlement rule of its own.
- **FR-002**: `exchange` MUST implement the gate port by asking, in order, `players` (account
  active), `kyc` (play gate) and `responsible-gambling` (`may-bet` with the order's cost), and
  MUST turn each refusal into the port's reason naming the gate and the casino's reason.
- **FR-003**: `exchange` MUST implement the ledger port on the brand's wallet (spec 022): reserve
  under the order id before an order reaches a book, release on cancel, expiry or close, settle
  under the trade id at least once, split and merge against the market's pool account, pay under
  the market and position; a settlement the wallet refuses MUST be recorded, retried and raised
  on `platform.audit`.
- **FR-004**: `markets` MUST implement the resolution port from the brand's roles: the
  `brand_<slug>` role with the `markets` permission may draft, approve, open, suspend, resume,
  close, resolve and void; every other caller is refused naming the permission.
- **FR-005**: Player routes MUST be of the player class under the brand's player realm and MUST
  show open markets with last price, best bid and ask and depth, a price stream, and the player's
  own orders, positions and trades, never another player's; operator routes MUST be of the
  operator class. The price stream MUST number its messages per market, from the exchange's
  sequence, and the depth read MUST carry the sequence it is current to, so that a client that
  misses a message sees the gap and resyncs from the depth read; a stale read MUST say so.
- **FR-006**: The contract MUST gain the topics `markets.events`, `exchange.orders`,
  `exchange.trades` and `exchange.prices`, keyed as ankka-predictions specs 002 and 003 state,
  with the player id a personal field; `reporting` and the shared `audit` read them by the
  existing grant pattern.
- **FR-007**: When the brand's values name a reasoning service, `markets` MUST mirror each
  approved market as a question with one hypothesis per outcome and the market venue named for the
  brand, and `exchange` MUST write one price observation per outcome at most once a minute; with
  none named, nothing MUST be mirrored and nothing refused.
- **FR-008**: Erasure MUST mark the player id on orders, trades and positions, cancel the
  player's resting orders first, and leave positions to pay or void as the market does.
- **FR-009**: The module's manifest MUST depend on `core` at the version that carries spec 022,
  declare its four topics, declare no grant within the brand, and declare the grants it makes to
  and receives from a pool instance for spec 024 to use.
- **FR-010**: A brand bound to a pool MUST run `markets` and `exchange` as routes that forward to
  the pool's instance with the brand and the player, with this module's gates run in the brand;
  the forwarding is spec 024's.
- **FR-011**: `exchange` MUST reconcile, per market and at least daily, what its events say was
  reserved, released, settled, split, merged and paid against what the wallet's facts say moved
  for the market's pool account and its players (spec 022's families), and MUST raise any
  difference on `platform.audit` naming the market, the key and both figures; nothing MUST
  correct a difference by itself. A market is reconciled once more when it is final.

### Key Entities

- **Market, outcome, order, book, trade, position, complete set, resolution**: the libraries';
  their words are ankka-predictions' glossary, cited here.
- **Gate refusal**: the port's reason, naming the gate (`account`, `play gate`, `bet gate`) and
  the casino's reason.
- **Reasoning service**: a value in the brand's values naming where the mirror goes; absent by
  default.

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
  wallet), spec 024 (the pool); specs 008, 009, 010 (the gates), 015 (reporting), 018 (audit).
- ankka-predictions `notes/casino-requests.md` item 9: a trade matched through the pool charges
  each buyer's reservation into the market's pool account, which spec 022's split and settlement
  do not yet do; FR-003's split against the pool needs it.
