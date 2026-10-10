# Feature Specification: rewards — Offers, Claims, Awards and Free Rounds

**Feature Branch**: `011-rewards`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The brand's `rewards` service: offers and campaigns, codes, claims and
awards of restricted funds, free rounds through the game provider, expiry, the bonus cost affiliates
are told, and the `casino.rewards` facts. The wallet does the money; this service decides when and on
what terms."

## Context

Today a reward lives in three places. The offer is a twin an operator edits through 3,516 lines of
`BonusRoutes`; the grant is a `bonus-claims/<id>` twin in ten states kept by `RewardGrantStore`
(feature 216) with an immutable `RewardEvent` log beside it; and the money is a restricted-funds
record the wallet holds. Five TL functions and four Go capabilities orchestrate the rest: a daily
cadence scan expires grants, settles free-rounds rounds and finalises expired free-rounds grants;
two more fan each reward event out to Customer.io and Kafka. One evaluator (feature 218) decides
eligibility for every path — a player's selection, a code, a deposit that confirms, a Customer.io
campaign, an operator's manual award — with twelve decline reasons the frontend names.

The service this spec describes keeps every rule the original enforces and moves the orchestration
inside one service: the offer and the grant are its entities, the evaluator is its code, expiry is a
timer per grant rather than a daily scan, free-rounds provisioning is a workflow against the game
provider (spec 003), and every outward effect is a fact on `casino.rewards`. The wallet (spec 002)
holds the restricted funds, enforces the wagering rules and does every money movement; this service
tells it to award, forfeit, cancel with a clawback, switch or close, each under the grant's id as
the key, and learns what happened from `money.transactions`.

Cashback, loyalty points and a VIP programme are named in the build plan and exist nowhere in the
original: no twin, route, function or test. Under constitution I they are not carried over. The
only VIP behaviour today is an eligibility floor on a player's tier, and it stays.

## Clarifications

### Session 2026-10-08

- Q: Where is the line between this service and the wallet? → A: The wallet owns the restricted
  funds record and every rule a bet, win, rollback, withdrawal or conversion applies to it (spec 002
  FR-005 to FR-021). This service owns the offer, the eligibility decision, the grant's lifecycle, the
  terms it hands the wallet at award (amount, wagering multiplier, cap, priority, expiry, the rule
  snapshot), the clocks, and every command to the wallet about a grant. It never computes a balance
  and never reads the wallet's store: it calls the wallet's commands and reads its facts.
- Q: Daily scan or timer? → A: A timer per clock, set when the clock is set and replaced by name
  when the clock changes: claim expiry, wagering window, cap closure, free-rounds expiry, pending
  selection expiry. The original's once-a-day cadence is a workaround for TL having no clock; a
  grant expires at its deadline, not up to a day later. Timers are the service's (ankka 032); TL is
  not carried over (spec 001 Assumptions).
- Q: Cashback, loyalty, VIP programme? → A: Not in the original; not carried over. A VIP tier floor
  on an offer is kept as an eligibility rule read from the player's tier on `casino.players`.
- Q: Who talks to the game provider for free rounds? → A: `game-provider` (spec 003 FR-025): this
  service asks it to price spin values, to create, poll and cancel a provider bonus, each under the
  grant id. Settlement arrives as `round_settled` facts on `casino.rounds`; the provider never
  touches the wallet for a free round (spec 003).
- Q: Whose are the bonus codes? → A: Two kinds, one entry point. A *brand code* is a redemption code
  on a brand's offer, created by an operator here. An *affiliate code* is the programme's (spec 013
  FR-025): it names a brand offer and carries the programme's signed tag. A player enters text; this
  service matches brand codes first, then the programme's code document for the brand, and refuses
  an operator a brand code whose text the document already holds. Binding attribution is
  `attribution`'s, from the `player_registered` or `bonus_granted` fact carrying the code (spec 001
  FR-011, spec 012 FR-029).
- Q: What is the bonus cost affiliates are told? → A: The amount of restricted funds awarded, in the
  wallet's currency, recognised when the grant activates — exactly what the original sends
  `notify/bonus-cost` today. A free-rounds grant's cost is the sum its rounds win, recognised when
  the grant is finalised. A forfeit, cancel or revoke emits no negative cost: the original does not
  and the programme's ledger (spec 013) treats bonus cost as a deduction already made.
- Q: How does Customer.io reach this service? → A: Through `engagement` (spec 014), which owns the
  Customer.io webhook and calls this service's award and target routes as a service; the
  `customerio_webhook` source is kept on the grant. Nothing in this service verifies a Customer.io
  signature.
- Q: Which expiry writes what? → A: The claim deadline forfeits an `ACTIVE` grant's record (the
  wallet's forfeit, grant-scoped) and marks it `EXPIRED`; the wagering window does the same; the
  cap deadline closes a `CONVERTED_PENDING_CAP` record (the wallet's close pending cap) and marks
  the grant `CLOSED`. These are the original's three arms, each one wallet command.
- Q: Cancel and revoke clawback? → A: The clawback is real balance now, less the real balance at
  activation, less deposits confirmed since activation, floored at zero; without an activation
  baseline it is zero (feature 244: player-favourable). A player's cancel and an operator's
  `reclaim` revoke apply it; an operator's `forfeit` revoke moves no real money. The wallet applies
  the figure this service computes (spec 002 FR-017) — this service holds the baselines.

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: Where does the deposit number come from? → A: The wallet's deposit ordinal on
  `deposit_confirmed` (spec 002 FR-031), per player and currency (FR-012).
- Q: Does the programme publish the code document? → A: Yes, spec 013 FR-023a; FR-021 names it.
- Q: Which `casino.players` fields does the evaluator read? → A: Registration time, duplicate flag
  and tier from spec 008's families; segment is this service's own view.

### Session 2026-10-08 (reconciliation 3)

- Q: What does `engagement`'s webhook need of this service? → A: The award and target routes (FR-022),
  with the answers the original's `dispatchAction` gave, and the `bonus_code_entered` family (FR-027)
  for `promo_code_entered`.
- Q: What does `reporting` need? → A: The offer's id, name and type on every grant family (FR-027),
  so the bonus report groups by offer from facts.
- Q: What does `support-agent` read? → A: A player's grants through FR-022a, by identity.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An operator creates an offer and a player claims it (Priority: P1)

An operator creates a deposit-match offer on a brand: name, percentage, maximum value, wagering
multiplier, minimum deposit, deposit number, claim window, wagering window, codes, validity. A player
sees it among the offers they are eligible for, selects it, deposits, and the award lands as
restricted funds with the wagering requirement the offer states.

**Why this priority**: the deposit match is the live product and the path every recording exercises.

**Independent Test**: create an offer, select it as a player, confirm a deposit through the wallet's
granted route, and read the player's wallet: restricted funds equal to the computed amount with the
release requirement at amount × multiplier.

**Acceptance Scenarios**:

- added `features/rewards/offers.feature`: an operator creates a deposit match offer and it is listed active with every field as given
- added `features/rewards/offers.feature`: a deposit match offer without a maximum value is refused naming it as required
- added `features/rewards/offers.feature`: a pure free rounds offer is created without a maximum value
- added `features/rewards/offers.feature`: a free rounds offer paying to restricted funds without a maximum convertible amount is refused naming it as required
- added `features/rewards/offers.feature`: selecting an active offer with no deposit required activates the reward at once with the offer's fixed amount
- added `features/rewards/offers.feature`: selecting an active offer requiring a deposit leaves the reward pending
- added `features/rewards/offers.feature`: the next confirmed deposit activates a pending reward with the matched amount and clears the selection
- added `features/rewards/offers.feature`: a deposit below the offer's minimum leaves the reward pending and is credited as real balance
- added `features/rewards/offers.feature`: a deposit after the deposit window expires the pending selection and awards nothing
- added `features/rewards/offers.feature`: the same deposit reported again under the same payment id awards nothing twice

---

### User Story 2 - Eligibility is decided once, with the same reasons on every path (Priority: P1)

Every path to a grant — selection, a code, a confirming deposit, a campaign, an operator's award —
asks the same evaluator. A player declined is told the one reason the original names.

**Why this priority**: the twelve reasons are frontend contract and the eligibility matrix is the
largest recording.

**Independent Test**: `TestScenario21_*` against each path.

**Acceptance Scenarios**:

- added `features/rewards/eligibility.feature`: a player flagged as a suspected duplicate is refused on any path
- added `features/rewards/eligibility.feature`: the duplicate rule is skipped on a brand that turned duplicate detection off
- added `features/rewards/eligibility.feature`: a player with an active reward claiming another offer is refused
- added `features/rewards/eligibility.feature`: an offer that conflicts with one the player holds open is refused
- added `features/rewards/eligibility.feature`: an offer claimed the maximum times per player is refused on the next claim
- added `features/rewards/eligibility.feature`: an offer excluded by one the player already claimed is refused
- added `features/rewards/eligibility.feature`: a claim that fails one rule is refused with that rule's reason
- added `features/rewards/eligibility.feature`: an operator's award proceeds past every rule but the duplicate flag and the active reward conflict
- added `features/rewards/eligibility.feature`: an offer code verified when the offer was targeted is not demanded again when the deposit confirms

---

### User Story 3 - A code unlocks an offer: the brand's or an affiliate's (Priority: P1)

A player enters a code. A brand code makes its offer visible and selects it; an affiliate code does
the same for the offer it names and the fact it produces lets `attribution` bind the player.

**Why this priority**: codes are the affiliate channel's second instrument (spec 012 User Story 2).

**Independent Test**: enter a brand code and an affiliate code from the programme's document; both
yield a pending grant; the grant facts carry the code.

**Acceptance Scenarios**:

- added `features/rewards/codes.feature`: a brand's offer code entered in any case makes the offer visible and selects it
- added `features/rewards/codes.feature`: a code no live offer carries and no bonus code document holds is answered invalid
- added `features/rewards/codes.feature`: an affiliate's bonus code from the programme's document selects the offer it names and the reward records the affiliate source
- added `features/rewards/codes.feature`: an operator creating a brand code already in the programme's document is refused
- added `features/rewards/codes.feature`: a code entered as a preview tells the player the offer's terms and selects nothing

---

### User Story 4 - An active grant converts, completes, is switched, cancelled, revoked or expires (Priority: P1)

The wallet reports what wagering does to the restricted funds; this service keeps the grant's
state in step, sets the clocks, and tells the player and the operators.

**Why this priority**: the lifecycle is where money is kept or taken.

**Independent Test**: `TestScenario19_*`, `TestScenario16_RewardGrant_*`, `TestScenario23_*`,
`TestScenario244_*`, `TestScenario245_*`.

**Acceptance Scenarios**:

- added `features/rewards/lifecycle.feature`: a conversion with a cap puts the reward in converted pending cap and sets a cap timer for the cap window
- added `features/rewards/lifecycle.feature`: a conversion with no cap closes the reward as completed
- added `features/rewards/lifecycle.feature`: a cap deadline passing closes the record on the wallet and the reward
- added `features/rewards/lifecycle.feature`: a reward past its claim deadline or wagering window is forfeited on the wallet and expired
- added `features/rewards/lifecycle.feature`: a player switching a second open reward to primary reorders the records on the wallet and records the switch
- added `features/rewards/lifecycle.feature`: a player cancelling a reward with an activation baseline forfeits the record and claws back winnings since activation less deposits since
- added `features/rewards/lifecycle.feature`: a player cancelling a reward with no activation baseline claws back nothing
- added `features/rewards/lifecycle.feature`: a cancel naming a reward the player does not hold is answered reward not found
- added `features/rewards/lifecycle.feature`: a cancel naming no active reward is refused as a conflict
- added `features/rewards/lifecycle.feature`: an operator revoking a reward as forfeit forfeits the record and leaves real balance untouched
- added `features/rewards/lifecycle.feature`: an operator revoking a reward as reclaim forfeits the record and applies the clawback
- added `features/rewards/lifecycle.feature`: an operator revoking a reward with no mode is refused
- added `features/rewards/lifecycle.feature`: a record an operator adjusted to zero closes the reward
- added `features/rewards/lifecycle.feature`: a record an operator adjusted but not to zero leaves the reward active

---

### User Story 5 - Free rounds are granted at the provider and settled from play (Priority: P2)

An operator or a campaign grants free rounds on a game. The provider is asked to create them, the
player plays them, each settled round accumulates, and on completion or expiry the winnings land
where the offer says.

**Why this priority**: a live product with its own incident history (features 233 to 238).

**Independent Test**: `TestScenarioFreeSpinsPlay_*`, `TestScenario16_*` against the provider's
bonus routes, `scenario_free_spins_customerio`, `scenario_free_spins_stacking`.

**Acceptance Scenarios**:

- added `features/rewards/free-rounds.feature`: free rounds given on an eligible game at an offered spin value are created at the game provider and live with the offer's expiry
- added `features/rewards/free-rounds.feature`: a spin value the game provider does not offer is refused naming the values it does
- added `features/rewards/free-rounds.feature`: a game that is not eligible is refused
- added `features/rewards/free-rounds.feature`: free rounds the game provider does not confirm within the time allowed are voided and the player is told nothing was given
- added `features/rewards/free-rounds.feature`: a round settled with a win uses one round and accumulates the win
- added `features/rewards/free-rounds.feature`: the same round settling again changes nothing
- added `features/rewards/free-rounds.feature`: the last round settling under an offer paying to real balance credits the accumulated amount to real balance
- added `features/rewards/free-rounds.feature`: the last round settling under an offer paying to restricted funds awards the accumulated amount with the offer's multiplier and cap
- added `features/rewards/free-rounds.feature`: winnings awarded as restricted funds while a reward is already active stack beside it
- added `features/rewards/free-rounds.feature`: free rounds past their expiry with rounds unused are expired and whatever accumulated is paid in full
- added `features/rewards/free-rounds.feature`: a round taken before expiry that settles late is still honoured
- added `features/rewards/free-rounds.feature`: an operator asking a game's spin values in a currency is answered the game provider's values sorted
- added `features/rewards/free-rounds.feature`: an operator asking spin values on a brand with no free rounds set up is answered not configured

---

### User Story 6 - Offers are targeted, listed and ordered (Priority: P2)

A campaign targets an offer at a player; the player's eligible list shows it; an operator orders the
brand's offers and sees every grant.

**Why this priority**: the lists are the player's and the operator's whole view of rewards.

**Independent Test**: target an offer through the service route, list as the player, reorder as the
operator, read the backoffice lists.

**Acceptance Scenarios**:

- added `features/rewards/offers.feature`: an offer the brand's engagement targeted at a player appears in their eligible list with the reason it is visible
- added `features/rewards/offers.feature`: targeting an offer at a player again changes nothing
- added `features/rewards/offers.feature`: a player with rewards available or pending sees only those offers as selectable
- added `features/rewards/offers.feature`: a player with no reward sees the offers visible to them by code or targeting
- added `features/rewards/offers.feature`: offers reordered by an operator are seen by players in that order
- added `features/rewards/offers.feature`: an operator reading a player's rewards sees every reward with its state, face value, source and clocks
- added `features/rewards/offers.feature`: an operator reading the brand's rewards sees them across players, filterable by state
- added `features/rewards/offers.feature`: a player reading their reward state sees the active reward's balance, requirement and progress and their history

---

### User Story 7 - Every change is a fact (Priority: P2)

Each grant transition is a fact on `casino.rewards`, so `attribution`, `engagement`, `partner-api`
and `reporting` learn of it without a call.

**Independent Test**: drive one grant through its life and read the topic.

**Acceptance Scenarios**:

- added `features/rewards/facts.feature`: a reward activating is published with the reward, the offer, the amount, the currency, the source and the code
- added `features/rewards/facts.feature`: a reward transition is published as the matching fact
- added `features/rewards/facts.feature`: the same transition applied twice publishes one fact

---

### Edge Cases

- **Two selections at once.** The second is refused as "ACTIVE_BONUS_CONFLICT" or as a pending
  selection already held; the grant entity decides, not the evaluator.
- **A deposit confirming while the offer is deactivated.** The pending grant is evaluated against
  the offer as it was snapshotted; an offer no longer live expires the selection.
- **The wallet refuses an award** (`BONUS_ALREADY_ACTIVE` with the maximum concurrent grants set).
  The grant stays pending, the player is told, and the deposit is not withheld.
- **The provider is down at grant time.** The grant is created pending provisioning; the workflow
  retries, then voids on timeout; the player is told nothing was granted.
- **A round settles for a grant this service does not know.** The fact is ignored and recorded as
  unmatched for an operator.
- **A code typed with surrounding spaces or mixed case.** Trimmed and matched case-insensitively.
- **A player erased.** The grant's facts stay; no field in a grant is personal (FR-033).
- **The programme's code document unreachable.** The last fetched document serves; an entry older
  than its refresh bound is reported in status.

## Requirements *(mandatory)*

### Functional Requirements

**Offers**

- **FR-001**: An offer MUST be one event sourced entity keyed by offer id, belonging to one brand,
  with: name, description, type (`DEPOSIT_MATCH`, `FREE_SPINS`, `DEPOSIT_MATCH_WITH_FREE_SPINS`,
  `NO_DEPOSIT`), payload type (`bonus_funds`, `real_cash`, `free_spins`,
  `deposit_match_with_free_spins`; derived from type when absent), validity window, percentage,
  maximum value, minimum deposit, deposit number target (a number or `ANY`), claim window days,
  wagering multiplier, wagering window days, maximum convertible amount, maximum claims per player,
  excluded offer ids, conflicting offer ids, maximum concurrent grants, redemption codes, segment,
  tier floor, role, no-deposit flag, currency, priority order, notification template and field map,
  and the free-rounds fields (game, provider, count, spin value, winnings destination, expiry days),
  each named as the original names it.
- **FR-002**: Creation and edit MUST apply the original's validation and name the first failure:
  `bonus_amount_required` (a maximum value above zero unless pure free rounds),
  `wagering_multiplier_required` (unless pure free rounds paying real balance),
  `invalid_payload_type`, `max_concurrent_grants_invalid` (above one), `currency_required` (free
  rounds), `game_id_required`, `spin_count_required`, `spin_value_required`,
  `max_convertible_amount_required` (destination restricted funds), `code_already_in_use`.
- **FR-003**: A redemption code MUST be unique across the brand's offers and the programme's code
  document for the brand, regardless of case; an operator MUST be able to list, create, edit,
  deactivate, delete and reorder offers, each change recorded in the audit (spec 001 FR-036).
- **FR-004**: The player's eligible list MUST show the offers the player holds a grant for when any
  is available or pending, and otherwise the offers made visible to them by a code or a campaign;
  each with its terms and the reason it is visible, in the brand's priority order.

**Eligibility**

- **FR-005**: One evaluator MUST decide every path, applying in order: suspected duplicate (skipped
  when the brand turns detection off), active grant conflict, conflicting grant, maximum claims,
  excluded by prior offer, redemption code (skipped when verified at targeting), minimum deposit,
  deposit number, claim window (from registration when the offer sets days, else from selection
  with a default of 3 days), segment, tier floor; and MUST decline with exactly the reasons
  `SUSPECTED_DUPLICATE`, `ACTIVE_BONUS_CONFLICT`, `CONFLICTING_GRANT`, `MAX_CLAIMS_REACHED`,
  `EXCLUDED_BY_PRIOR_OFFER`, `INVALID_CODE`, `BELOW_MIN_DEPOSIT`, `DEPOSIT_NUMBER_MISMATCH`,
  `CLAIM_WINDOW_EXPIRED`, `SEGMENT_MISMATCH`, `VIP_TIER_TOO_LOW`, `ALREADY_CLAIMED`.
- **FR-006**: An operator override MUST apply only the first two rules.
- **FR-007**: The amount MUST be the deposit × percentage capped at the maximum value for a deposit
  match, the fixed maximum value for a no-deposit offer, and the deposit's currency, or the offer's
  when it carries one, or the player's wallet currency; all in minor units.
- **FR-008**: The evaluator MUST read the player's flags, tier, segment and registration time from
  the service's own view over `casino.players`, and the player's open grants and wallet open count
  from its own grants and the wallet's balance read; it MUST NOT query another service's store.

**Grants**

- **FR-009**: A grant MUST be one event sourced entity keyed by grant id in exactly the original's
  ten states — `AVAILABLE`, `PENDING`, `ACTIVE`, `PAUSED`, `CONVERTED_PENDING_CAP`, `CLOSED`,
  `EXPIRED`, `CANCELLED`, `FORFEITED`, `REVOKED` — with exactly its transitions (feature 216
  data model §5), refusing any other as `INVALID_TRANSITION` naming both, and treating a repeat of
  the current state as a no-op.
- **FR-010**: A grant MUST record player, offer, source (`customerio_webhook`, `player_code`,
  `operator_manual`, `affiliate_code`, `system`), payload type, face value, currency, priority,
  the clocks (granted, expires, wagering expires, cap expires, terminated), the rule snapshot copied
  from the offer at creation, the activation baselines (real balance and total deposits at
  activation), the code used, and the programme's tag when an affiliate code was used.
- **FR-011**: Selecting an offer MUST create or move the grant to pending; a no-deposit offer MUST
  activate at once; deselecting MUST return it to available; a pending selection MUST expire by
  timer at its claim window.
- **FR-012**: A confirmed deposit (a `deposit_confirmed` fact carrying the wallet's deposit ordinal
  as the deposit number, spec 002 FR-031 — counted per player and currency) MUST
  re-evaluate the player's pending grant with that deposit and activate it on eligibility; a
  `CLAIM_WINDOW_EXPIRED` decline MUST expire it; any other decline MUST leave it pending and record
  the reason; the deposit itself is never withheld.
- **FR-013**: Activation MUST ask the wallet to award restricted funds under the grant id as the
  key with amount, wagering multiplier, maximum convertible amount, priority, expiry and the rule
  snapshot; MUST record the activation baselines from the wallet's balance and the player's deposit
  total; and MUST mark the grant active only after the wallet confirms. A wallet refusal is recorded
  on the grant with its reason.
- **FR-014**: Each grant MUST be told of its record's conversion, forfeit, cancel and zeroing from
  `money.transactions` and move state accordingly, setting the cap timer from the wagering window
  days at conversion.
- **FR-015**: Timers MUST fire the claim deadline and the wagering window (wallet forfeit of the
  grant's record, state `EXPIRED`), the cap deadline (wallet close pending cap, state `CLOSED`) and
  the pending-selection window; each MUST be a no-op on a grant already terminal or a wallet answer
  of `NO_ACTIVE_BONUS` or `NOT_PENDING_CAP`.
- **FR-016**: A player MUST be able to switch an open grant to primary (the wallet's switch, then
  the record), cancel their active grant or a named grant they hold (the clawback of the
  Clarifications, then the wallet's cancel), and read their state and history; an explicit empty
  grant reference MUST be refused as `grant_not_found`, no active grant as `NO_ACTIVE_BONUS`.
- **FR-017**: An operator MUST be able to award an offer manually with an optional deposit amount
  (operator override), and revoke an active grant as `forfeit` or `reclaim`, with `INVALID_MODE`,
  `CLAIM_NOT_FOUND` (including a grant of another brand) and `NO_ACTIVE_BONUS` as the original
  answers; both recorded with the actor in the audit and on the grant.
- **FR-018**: Every command to the wallet MUST carry the grant id as its key, and a repeat MUST not
  award, forfeit or claw back twice.

**Codes and targeting**

- **FR-019**: Entering a code MUST trim and lower-case it, match a live brand offer's codes first,
  then the programme's code document for the brand, make the offer visible, select it, record the
  source and, for an affiliate code, the programme's tag; no match is `invalid_code`.
- **FR-020**: A preview of a code MUST answer the offer's terms and select nothing.
- **FR-021**: The service MUST fetch the programme's code document for the brand (spec 013 FR-023a)
  as a public client (spec 001 FR-016) on a refresh bound and serve from the last fetched copy when the
  programme is unreachable, reporting staleness in status.
- **FR-022**: Two service routes MUST let `engagement` (spec 014 FR-018) target an offer at a player
  (the *target route*, recording the source as a campaign and refusing a free-spins offer as
  `free_spins_offer_not_targetable`) and award an offer to a player on a campaign's behalf (the
  *award route*, source `customerio_webhook`, re-resolving a stale offer by its role once, answering
  `alreadyProcessed` when the player holds a claim of the offer, and `offer_not_found`,
  `ineligible: <reason>`, `spin_value_not_offered` or `game_not_eligible` as the evaluator decides);
  both MUST be idempotent per player and offer and MUST size a deposit match from the wallet's
  facts, never from the caller.
- **FR-022a**: A read route MUST answer, for a player, their grants with state, offer name,
  wagering requirement, progress and expiry, served to the player and to `support-agent` by identity
  within the project (spec 017 FR-009 `get_active_bonuses`).

**Free rounds**

- **FR-023**: Granting free rounds MUST check the game is eligible and the spin value is one the
  provider offers in the currency (through `game-provider`), refusing `game_not_eligible` or
  `spin_value_not_offered` with the allowed values; and MUST run a workflow that creates the
  provider bonus under the grant id, polls until confirmed or a timeout, and on timeout or
  rejection voids the grant and tells the player.
- **FR-024**: Each `round_settled` fact naming a grant MUST count one round and add the win to the
  accumulated winnings exactly once per round; on reaching the count, or on expiry with rounds
  unused, the full accumulated amount MUST be credited as real money (destination real balance) or
  awarded as restricted funds with the offer's multiplier and maximum convertible amount
  (destination restricted funds), uncapped at this point, and the grant finalised; a round that
  settles after expiry MUST still be honoured.
- **FR-025**: Free-rounds expiry MUST be a timer at the offer's days (default 30) from grant.
- **FR-026**: An operator MUST be able to list a game's spin values in a currency as the provider
  offers them, sorted, or `not_configured`.

**Facts and cost**

- **FR-027**: A consumer over the grant's events MUST produce `casino.rewards` facts of the
  families spec 001 FR-011 lists — `bonus_granted`, `bonus_cancelled`, `bonus_forfeited`,
  `bonus_completed`, `grant_expired`, `free_spins_granted`, `free_spins_settled` — keyed by the
  player id, carrying grant, offer id, offer name, offer type, amount, currency, source, code and
  tag, once per transition, so `reporting` (spec 015 FR-009) reports per offer without a call; and
  `bonus_code_entered` (code, outcome: matched, invalid, ineligible) once per code a player enters,
  which `engagement` (spec 014 FR-006) turns into `promo_code_entered`.
- **FR-028**: Bonus cost MUST be carried on `bonus_granted` (the awarded amount) and
  `free_spins_settled` (the amount credited or awarded) so `attribution` emits `bonus-cost` (spec
  012 FR-011) from facts alone; no call to any affiliate system remains.
- **FR-029**: Notifications (`bonus_granted`, `bonus_cancelled`, `bonus_completed` triggers) MUST
  reach Customer.io through `engagement` consuming `casino.rewards`, carrying the grant id so a
  campaign can name it; this service sends nothing itself.

**Reads, identity and data**

- **FR-030**: The player's state, free rounds, history and eligible list, and the operator's
  per-player and per-brand lists, MUST be views over this service's entities and the wallet's
  balance read; face values MUST be displayed in major units with the currency.
- **FR-031**: Player routes MUST require the brand's player realm; operator routes the brand's
  operator roles (`brand_admin`, `resource_admin`, `platform_admin`); service routes the calling
  service's identity (spec 001 FR-020), admitting `engagement`, `game-provider` and `payouts`.
- **FR-032**: Every amount MUST be a whole number of minor units; a decimal MUST be refused naming
  the amount (spec 001 FR-026).
- **FR-033**: The service holds no personal data: a grant carries the player id (spec 001 FR-024),
  an offer carries no player field, and an operator's reason on a revoke is held as personal data
  under the operator (spec 002 FR-033's rule).

### Key Entities

- **Offer**: a brand's promotion with its terms, rules, codes, validity and order.
- **Grant**: one player's instance of an offer in one of ten states, with its clocks, snapshot,
  baselines, source and code; its events are the fact source.
- **Free-rounds grant**: a grant whose payload is rounds at the provider: count, spin value, game,
  rounds used, accumulated winnings, provisioning state, expiry.
- **Code**: a brand code on an offer, or an entry in the programme's code document naming an offer.
- **Visibility**: which offers a player may select and why (targeted, code, held).
- **Evaluator decision**: eligible with an amount, or declined with one reason and the rule.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every recording under *Recordings* replays unchanged against the service with the
  wallet of spec 002, with the same refusal codes and reasons.
- **SC-002**: A grant expires within one minute of its deadline, never up to a day after.
- **SC-003**: No restricted funds are awarded, forfeited or clawed back twice for one grant under
  any repeat of any command, fact or timer.
- **SC-004**: Every grant transition appears exactly once on `casino.rewards`, and `attribution`
  can compute bonus cost from the topic alone.
- **SC-005**: A free-rounds grant whose provider never confirms is voided within the timeout and
  leaves no record at the wallet.
- **SC-006**: The service makes no call to an affiliate system, Customer.io or Kafka directly.

## Changes from the original

- The five TL functions, four Go capabilities, the daily cadence scan and the Customer.io and Kafka
  fan-outs are gone: timers per clock, a consumer per topic, and one fact stream.
- The grant and reward-event twins become one entity whose events are the log; the `bonus_claim_refs`
  index, the pre-flight retire route, the legacy status synonyms and the major-unit face-value
  strings are not carried over (opening events are written in the new shape by spec 019).
- `notify/bonus-cost` to the affiliate service is replaced by the amount on the fact.
- Customer.io's webhook moves to `engagement`; the `grant_bonus` and `target_offer` actions become
  service routes here.
- The free-rounds provisioning becomes a workflow; the provider calls move to `game-provider`.
- The programme's bonus codes are matched from its published document, not from an
  `affiliate-bonus-codes-<brand>` namespace this service writes.
- Cashback, loyalty points and a VIP programme are not built: they do not exist in the original.
- Dropped as stubs or migration tooling: the offer-concurrency backfill job, the retire-legacy-refs
  pre-flight, `max_concurrent_grants` values above one (the routes refuse them today; the wallet
  supports more — see Assumptions).

## Recordings

The Go end-to-end suite, run against the original and recorded by ankka-port:
`TestScenario21_*` (OfferValidation, CleanGrantEveryPath, CodeMinDepositDepositNumberWindow,
Concurrency_ActiveBonusConflict, ConflictsWith, MaxClaimsAndExclusion,
SuspectedDuplicate_RefusedOnEveryPath); `TestScenario16_RewardGrant_*`
(TargetSelectActivateForfeit, LegacyStatusStillTransitions) and `scenario16_bonus_triggers`;
`TestScenario19_BonusWageringCompletesToRealMoney`; `TestScenario23_RewardGrantExpiry`;
`TestScenario244_*` (CancelWithBaselineClawsBackOnlyWinnings, CancelWithNoBonusIsConflict,
CancelWithoutBaselineSucceedsWithZeroClawback, ConversionRecordsActivationBaseline,
DepositMatchCancelStillWorks); `TestScenario245_*` (PartialBonusAdjustLeavesRecordActive,
ZeroedBonusClosesRecordAndGrant); `TestScenario18_MultiAccountDetection_BonusBlock`;
`TestScenarioFreeSpinsPlay_*` (BonusBalanceDestination, RealBalanceDestination,
StacksOnActiveBonus, CustomerIOGrant), `scenario_free_spins_customerio`,
`scenario_free_spins_stacking`; `TestScenario9_CasinoRF_BonusExpiryCheck` and
`_BonusWageringProgress`; the bonus parts of `TestScenario7_CasinoGate3_FullPlayerJourney` and
`TestScenario11_*`.

Playwright, the frontend oracle: `34-bonus-wagering-config` (brand contribution rates),
`39-bonus-lifecycle` (claim, wagering to real, cancel with clawback), `43-bonus-revoke-and-decline-reasons`
(a blocked grant names its reason; cancelling the revoke dialog moves no money).

Unit fixtures replayed against the entity and evaluator: `EligibilityEvaluator`'s suites,
`RewardGrantStoreSpec`, `RewardStatusSpec`, `RewardGrantSpec`, and the capability tables under
`award_bonus`, `expire_reward_grant`, `settle_free_spins_round`, `finalize_free_spins_grant`.

## Assumptions

- The wallet (spec 002) keeps the restricted funds record, applies the rule snapshot at bet time and
  reports conversion, forfeit, cancel and zeroing on `money.transactions`; this service reads those
  facts and never the wallet's store.
- `players` (spec 008 FR-035) publishes registration time (`player_registered`), the duplicate
  flag (`player_fraud_flagged`, `player_fraud_cleared`) and the tier (`vip_tier_changed`) on
  `casino.players`; `wallet` publishes `deposit_confirmed` with its deposit ordinal (spec 002
  FR-031), which is the deposit number (settled at reconciliation 2); a player's segment is this
  service's own view over the facts it reads.
- `game-provider` (spec 003 FR-025) serves the provider bonus routes under the grant id and tags
  free rounds on `round_settled`.
- `engagement` (spec 014) owns the Customer.io webhook and calls FR-022's routes.
- The programme (spec 013 FR-023a) publishes a code document per brand beside its key document:
  code text, offer id, signed tag (settled at reconciliation 2).
- The original forces maximum concurrent grants to one at the routes while the wallet enforces any
  number. [NEEDS CLARIFICATION: keep the limit at one, or let an offer set it now that the wallet
  supports it?]
- Free-rounds winnings are awarded or credited uncapped at finalisation (amendment 2026-08-23),
  with the maximum convertible amount applying only at withdrawal. [NEEDS CLARIFICATION: confirm
  this is the intended product rule rather than an incident-time fix to keep.]
- The claim window for a pending selection defaults to 3 days from selection; from registration
  only when the offer sets days. [NEEDS CLARIFICATION: is 3 days the product default, and should a
  brand be able to change it?]
- TL is not carried over; the timers here are ankka timers (spec 001 Assumptions).

## Dependencies

- Spec 002 wallet: award, forfeit, cancel with clawback, close pending cap, switch, balance read,
  `money.transactions`.
- Spec 003 game-provider: FR-025 bonus routes; `round_settled` carrying the grant.
- Spec 008 players: `casino.players` fields the evaluator reads.
- Spec 012 attribution: `bonus-cost` from `bonus_granted` and `free_spins_settled`; the code and tag
  on the facts.
- Spec 013 programme: the per-brand code document (amendment).
- Spec 014 engagement: the Customer.io webhook, the notification triggers.
- Spec 001: topic contract (`casino.rewards` families), identity classes, minor units, audit.

## Out of scope

- Cashback, loyalty points, a VIP programme, tournaments and leaderboards: not in the original.
- The provider's own bonus mechanics beyond create, poll, cancel and spin values.
- The programme's commission on bonus cost (spec 013).
- The frontend's bonus pages (they change only base URLs).
