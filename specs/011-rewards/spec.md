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
record the wallet holds. Six TL functions and their Go capabilities orchestrate the rest: a daily
cadence scan expires grants, settles free-rounds rounds and finalises expired free-rounds grants;
one sets the cap clock at conversion; two more fan each reward event out to Customer.io and Kafka.
One evaluator (feature 218) decides eligibility for every path — a player's selection, a code, a
deposit that confirms, a Customer.io campaign, an operator's manual award — with fourteen decline
reasons the frontend names.

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
  `reclaim` revoke apply it; an operator's `forfeit` revoke moves no real money. *Corrected
  2026-10-09 with spec 002:* the wallet computes the figure from the baseline it records at award
  (spec 002 FR-015a, FR-017) — award and activation are one moment — and this service supplies no
  clawback and keeps no baseline; a `reclaim` revoke or a cancel is the wallet's cancel, a `forfeit`
  revoke the wallet's forfeit.

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

### Session 2026-11-01

Answered with the original as it runs on 2026-11-01, which changed since this spec was written.

- Q: Maximum concurrent grants: one, or set by the offer? → A: Set by the offer. The original
  accepts any `max_concurrent_grants` of one or more since its feature 220 and reads an absent value
  as no limit since 236 (a welcome bonus was blocking free rounds a brand's marketing had sent);
  `ACTIVE_BONUS_CONFLICT` is declined once the player's open grants, or the wallet's open records if
  more, reach the offer's number. Only a value below one is `max_concurrent_grants_invalid`.
- Q: Are free-rounds winnings awarded or credited uncapped at finalisation? → A: Yes, as the original
  (amendment 2026-08-23): the maximum convertible amount applies when the restricted funds convert,
  not when the rounds finish.
- Q: Is the pending selection's claim window 3 days? → A: Yes, 3 days from selection, fixed; from
  registration only when the offer sets its days. No brand setting.
- Q: Affiliate codes and the campaign routes, with the programme (013) and engagement (014) not built?
  → A: Built now, inert until used: the code document client and the target and award routes are
  built against the contract and tested with fakes; an affiliate code matches nothing until the
  programme publishes a document, and the routes wait for `engagement`.
- Q: The original's no-deposit gates of 2026-10-29 (#730, ClickUp 869fj3w46)? → A: Carried over in
  this spec, with the signals they read: `ELIGIBILITY_CHECK_UNAVAILABLE` while the player's duplicate
  check is in progress, `NO_DEPOSIT_OFFER_BLOCKED` on a weak duplicate signal, both for no-deposit
  offers only; `players` gains the email-variant signal and the registration address's rate,
  publishes the check and the block, and lets an operator clear the block (FR-034 to FR-036,
  amending spec 008). #730's device tracking at login is not a reward
  rule and is left to a follow-up on spec 008.
- Q: The glossary's 23 proposed terms from specs 021, 023 and 025? → A: Accepted as written.

### Session 2026-11-01 (planning)

Asked while planning, after the original was read whole (research R1).

- Q: Codes are exact and case-sensitive in the original; keep the spec's trimmed, any-case match?
  → A: Keep it, named under *Changes from the original*. Brand and programme codes then agree (the
  original's affiliate side already matched in any case), and uniqueness is in any case too.
- Q: Free-rounds winnings paying to real balance: the original's credit lands on the primary open
  reward's restricted funds when one is open. As the original, or always real? → A: As the original:
  the wallet's provider credit under the reward's id (FR-024).
- Q: Which of the original's routes that no requirement named are carried? → A: The operator's two
  awards with no offer (a free bonus, and free rounds), FR-017. Dropped and named under *Changes*:
  the player's `/claim` (a bonus sized from a deposit amount the player states), the unused
  `/offers/eligible` list, and the `/promo-code` store and read (written, never read back); the
  preview stays (FR-020).
- Q: Correct the spec to the original where it contradicts it? → A: Yes, all: every conversion
  waits in converted pending cap; a loss or an operator's adjustment to zero forfeits; free rounds
  the game provider fails are cancelled and no longer listed; the target route refuses a deposit
  match with free rounds too; spin values not configured are an empty list; deleting an offer
  deactivates it; the award route's declines are the reason alone; an unknown spin value goes ahead;
  the game provider's free-rounds read is served (FR-014, FR-017, FR-022, FR-022b, FR-023 to FR-026).
  Also read in the original and applied: the claim window from a selection is the offer's days when
  it sets them, else 3 (the 3 days are fixed in that no brand sets them); a maximum of claims of 0 is
  no limit; the player's cancel answers in lower case.

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
- added `features/rewards/eligibility.feature`: an offer allowing two open rewards is claimed beside one the player holds
- added `features/rewards/eligibility.feature`: an offer setting no maximum of open rewards is claimed beside any the player holds
- added `features/rewards/eligibility.feature`: a no-deposit offer claimed while the player's duplicate check is in progress is refused for now
- added `features/rewards/eligibility.feature`: a duplicate check still in progress ten minutes after registration no longer refuses
- added `features/rewards/eligibility.feature`: a no-deposit offer claimed by a player with a weak duplicate signal is refused
- added `features/rewards/eligibility.feature`: a deposit offer is not refused for a duplicate check in progress or a weak duplicate signal
- added `features/rewards/eligibility.feature`: an award by the brand's engagement during the duplicate check is answered to try again later
- added `features/players/duplicates.feature`: a registration is marked as under a duplicate check until the detector has run
- added `features/players/duplicates.feature`: a registration whose email is a variant of a player's from the same address flags both as suspected duplicates
- added `features/players/duplicates.feature`: a registration whose email is a variant of a player's, with nothing else, blocks only its no-deposit offers
- added `features/players/duplicates.feature`: a registration whose comparison fails blocks only its no-deposit offers
- added `features/players/duplicates.feature`: registrations from one address at the rate the detector counts, with nothing else, change nothing
- added `features/players/duplicates.feature`: an operator clearing a player's no-deposit block lifts it and records who and when
- added `features/rewards/eligibility.feature`: an offer that conflicts with one the player holds open is refused
- added `features/rewards/eligibility.feature`: an offer claimed the maximum times per player is refused on the next claim
- added `features/rewards/eligibility.feature`: an offer excluded by one the player already claimed is refused
- added `features/rewards/eligibility.feature`: a claim that fails one rule is refused with that rule's reason
- added `features/rewards/eligibility.feature`: an operator's award proceeds past every rule but the duplicate checks and the open reward maximum
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
- added `features/rewards/lifecycle.feature`: a conversion with no maximum convertible amount still waits in converted pending cap
- added `features/rewards/lifecycle.feature`: a reward whose restricted funds are lost in play is forfeited
- added `features/rewards/lifecycle.feature`: a cap deadline passing closes the record on the wallet and the reward
- added `features/rewards/lifecycle.feature`: a reward past its claim deadline or wagering window is forfeited on the wallet and expired
- added `features/rewards/lifecycle.feature`: a player switching a second open reward to primary reorders the records on the wallet and records the switch
- added `features/rewards/lifecycle.feature`: a player cancelling a reward with an activation baseline forfeits the record and claws back winnings since activation less deposits since
- added `features/rewards/lifecycle.feature`: a player cancelling a reward with no activation baseline claws back nothing
- added `features/rewards/lifecycle.feature`: a cancel naming a reward the player does not hold is answered reward not found
- added `features/rewards/lifecycle.feature`: a cancel naming no active reward is refused as a conflict
- added `features/rewards/lifecycle.feature`: an operator revoking a reward as forfeit forfeits the record and leaves real balance untouched
- added `features/rewards/lifecycle.feature`: an operator revoking a reward as reclaim forfeits the record and applies the clawback
- added `features/rewards/lifecycle.feature`: an operator revoking as reclaim a reward whose record has no baseline is refused
- added `features/rewards/lifecycle.feature`: an operator revoking free rounds not yet played cancels them at the game provider
- added `features/rewards/lifecycle.feature`: an operator revoking free rounds already played out is refused
- added `features/rewards/lifecycle.feature`: an operator revoking a reward with no mode is refused
- added `features/rewards/lifecycle.feature`: a record an operator adjusted to zero forfeits the reward
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
- added `features/rewards/free-rounds.feature`: free rounds the game provider does not confirm within the time allowed are cancelled and no longer listed to the player
- added `features/rewards/free-rounds.feature`: free rounds given while the game provider has not yet confirmed them are answered as processing
- added `features/rewards/free-rounds.feature`: a round settled with a win uses one round and accumulates the win
- added `features/rewards/free-rounds.feature`: the same round settling again changes nothing
- added `features/rewards/free-rounds.feature`: the last round settling under an offer paying to real balance credits the accumulated amount to real balance
- added `features/rewards/free-rounds.feature`: winnings paying to real balance while a reward is active land on the active reward's restricted funds
- added `features/rewards/free-rounds.feature`: the last round settling with nothing won pays nothing and completes the reward
- added `features/rewards/free-rounds.feature`: the last round settling under an offer paying to restricted funds awards the accumulated amount with the offer's multiplier and cap
- added `features/rewards/free-rounds.feature`: winnings awarded as restricted funds while a reward is already active stack beside it
- added `features/rewards/free-rounds.feature`: free rounds past their expiry with something won are paid in full and completed
- added `features/rewards/free-rounds.feature`: free rounds past their expiry with nothing won are expired
- added `features/rewards/free-rounds.feature`: a round taken before expiry that settles late is still honoured
- added `features/rewards/free-rounds.feature`: an operator asking a game's spin values in a currency is answered the game provider's values sorted
- added `features/rewards/free-rounds.feature`: an operator asking spin values on a brand with no free rounds set up is answered with none, as not configured
- added `features/rewards/free-rounds.feature`: free rounds given when the game provider cannot say which spin values it offers go ahead
- added `features/rewards/free-rounds.feature`: a launch naming no free rounds is given the player's oldest free rounds for that game

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
- added `features/rewards/offers.feature`: targeting an offer of free rounds, or of a deposit match with free rounds, is refused
- added `features/rewards/offers.feature`: an operator deleting an offer deactivates it and keeps the rewards already given
- added `features/rewards/offers.feature`: an operator giving a free bonus with no offer awards restricted funds with the amount and wagering they set
- added `features/rewards/offers.feature`: an operator giving free rounds with no offer creates them at the game provider

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
  The grant stays pending with the reason recorded, and the deposit is not withheld.
- **The provider is down at grant time.** The grant is active with its provisioning processing; the
  workflow asks again every 30 seconds, up to 50 times, then cancels it as `provider_failed`; the
  player's free rounds no longer list it and it does not count as a claim.
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
  the free-rounds fields (game, game name, provider, count, spin value or a spin value per currency,
  winnings destination, expiry days), and the bet-time rules the wallet applies (maximum stake and
  its enforcement, contributions, scope, funding order, win allocation, forfeit on withdrawal, and
  forfeit on cancel), each named as the original names it.
- **FR-002**: Creation and edit MUST apply the original's validation and name the first failure:
  `bonus_amount_required` (a maximum value above zero unless pure free rounds),
  `wagering_multiplier_required` (unless pure free rounds paying real balance),
  `invalid_payload_type`, `max_concurrent_grants_invalid` (below one; absent is no limit), `currency_required` (free
  rounds), `game_id_required`, `spin_count_required`, `spin_value_required`,
  `max_convertible_amount_required` (destination restricted funds), `spin_configurations_invalid`,
  `spin_value_not_offered` with the allowed values, `game_not_eligible`, `duplicate_code_in_request`,
  `code_already_in_use`; an edit applies `invalid_payload_type`, `max_concurrent_grants_invalid`,
  `spin_configurations_invalid`, the spin-value check when the game, currency or value changes,
  `role_already_assigned`, and the code checks when the codes change.
- **FR-003**: A redemption code MUST be unique across the brand's offers and the programme's code
  document for the brand, regardless of case; an operator MUST be able to list, create, edit,
  delete (which deactivates: nothing is removed) and reorder offers, each change recorded in the
  audit (spec 001 FR-036).
- **FR-004**: The player's eligible list MUST show the offers the player holds a grant for when any
  is available or pending, and otherwise the offers made visible to them by a code or a campaign;
  each with its terms and the reason it is visible, in the brand's priority order.

**Eligibility**

- **FR-005**: One evaluator MUST decide every path, applying in order: duplicate check in progress
  (a no-deposit offer, while the player's check is in progress and no more than 10 minutes since
  registration), weak duplicate signal (a no-deposit offer), suspected duplicate (these three
  skipped when the brand turns detection off), open grant maximum (the player's open grants, or the
  wallet's open records if more, at or above the offer's maximum concurrent grants; no limit when
  the offer sets none), conflicting grant, maximum claims (0 is no limit; a terminal grant counts as
  a claim, one cancelled as `provider_failed` does not), excluded by prior offer, redemption code
  (skipped when verified at targeting), minimum deposit, deposit number, claim window (at a
  confirming deposit, from the selection: the offer's days, else 3; on every other path, from
  registration and only when the offer sets days), segment, tier floor; and MUST
  decline with exactly the reasons `ELIGIBILITY_CHECK_UNAVAILABLE`, `NO_DEPOSIT_OFFER_BLOCKED`,
  `SUSPECTED_DUPLICATE`, `ACTIVE_BONUS_CONFLICT`, `CONFLICTING_GRANT`, `MAX_CLAIMS_REACHED`,
  `EXCLUDED_BY_PRIOR_OFFER`, `INVALID_CODE`, `BELOW_MIN_DEPOSIT`, `DEPOSIT_NUMBER_MISMATCH`,
  `CLAIM_WINDOW_EXPIRED`, `SEGMENT_MISMATCH`, `VIP_TIER_TOO_LOW`, `ALREADY_CLAIMED`.
- **FR-006**: An operator override MUST apply only the first four rules.
- **FR-007**: The amount MUST be the deposit × percentage capped at the maximum value when the offer
  has a percentage and there is a deposit, else the fixed maximum value; in the offer's currency when
  it carries one, else the deposit's, else the player's wallet currency; all in minor units, a
  fraction of a minor unit rounded half up.
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
  from the offer at creation, the code used, and the programme's tag when an affiliate code was
  used. The clawback baseline is the wallet's (spec 002 FR-015a), not held here.
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
  snapshot; and MUST mark the grant active only after the wallet confirms. A wallet refusal is
  recorded on the grant with its reason. The wallet records the clawback baseline on its record at
  that award (spec 002 FR-015a); this service holds none.
- **FR-014**: Each grant MUST be told of its record's conversion, forfeit, cancel, zeroing and close
  from `money.transactions` and move state as the original does: every conversion to
  `CONVERTED_PENDING_CAP`, with the cap timer at the conversion plus the wagering window days (30
  when the offer sets none); a record taken to zero by a loss or a zero win to `FORFEITED`
  (`bonus_exhausted`), by an operator's adjustment to `FORFEITED` (`admin_adjust_emptied`); a pending
  cap closed by real balance reaching zero, by a payout exhausting the cap, or at the cap deadline to
  `CLOSED` (`real_balance_zero`, `cap_exhausted`, `cap_expired`). `PAUSED` is declared and never
  written, as in the original.
- **FR-015**: Timers MUST fire the claim deadline (the award plus the wagering window days, 30 when
  the offer sets none) and the wagering window (wallet forfeit of the grant's record, state
  `EXPIRED`, reason `claim_expired` or `wagering_window_expired`), the cap deadline (wallet close
  pending cap of the grant's record, state `CLOSED`, reason `cap_expired`) and the pending-selection
  window (state `EXPIRED`, no wallet command); each MUST be a no-op on a grant already terminal, and
  a wallet answer of `NO_ACTIVE_BONUS` or `NOT_PENDING_CAP` moves the grant as the timer would and
  moves no money.
- **FR-016**: A player MUST be able to switch an open grant to primary (the wallet's switch, then
  the record), cancel their active grant or a named grant they hold (the wallet's cancel, which
  computes and applies the clawback, spec 002 FR-017; nothing is clawed back for a named grant that
  is not primary and has no release progress), and read their state and history (the newest 20
  closed grants); an explicit empty grant reference, or a grant they do not hold open, MUST be
  refused as `grant_not_found`, no active grant as `no_active_bonus`, a switch without a grant as
  `grantRef_required`, a switch the wallet refuses as `switch_failed`.
- **FR-017**: An operator MUST be able to award an offer manually with an optional deposit amount
  (operator override); award with no offer a free bonus (amount, wagering multiplier, optional
  maximum convertible amount, currency; a 30-day wagering window; offer id `manual:<uuid>`, type
  `MANUAL`) or free rounds (game, provider, count, spin value, maximum convertible amount, wagering
  multiplier, winnings destination, expiry and wagering window days), refusing `bonusAmount and
  wager are required` and FR-002's free-rounds codes; and revoke an active grant as `forfeit` (the
  wallet's forfeit, nothing clawed back) or `reclaim` (the wallet's cancel with its clawback,
  refused as `MISSING_BASELINE` when the record has none), state `REVOKED`, with `INVALID_MODE`,
  `CLAIM_NOT_FOUND` (including a grant of another brand) and `NO_ACTIVE_BONUS` as the original
  answers. Revoking free rounds MUST cancel them at the game provider first, refusing
  `FREE_SPINS_FINISHED` once played out and `PROVIDER_UNAVAILABLE` when it cannot be reached, and
  forfeit what they accumulated. Each is recorded with the actor in the audit and on the grant.
- **FR-018**: The award MUST carry the grant id as its key (the wallet's record is named by it) and
  the offer id as its bonus reference; every later command to the wallet MUST carry the grant id as
  the grant it names and `<grant id>/<step>` as its key; a repeat MUST not award, forfeit or claw
  back twice.

**Codes and targeting**

- **FR-019**: Entering a code MUST trim and lower-case it, match a live brand offer's codes first,
  then the programme's code document for the brand, make the offer visible, select it, record the
  source and, for an affiliate code, the programme's tag; no match is `invalid_code`.
- **FR-020**: A preview of a code MUST answer the offer's terms and select nothing.
- **FR-021**: The service MUST fetch the programme's code document for the brand (spec 013 FR-023a)
  as a public client (spec 001 FR-016) on a refresh bound and serve from the last fetched copy when the
  programme is unreachable, reporting staleness in status.
- **FR-022**: Two service routes MUST let `engagement` (spec 014 FR-018) target an offer at a player
  (the *target route*, by the offer's role, recording the source as a campaign, refusing an offer of
  free rounds or of a deposit match with free rounds as `free_spins_offer_not_targetable` and an
  unknown role as `role_not_found`) and award an offer to a player on a campaign's behalf (the
  *award route*, source `customerio_webhook`, re-resolving a stale offer by its role once, answering
  `alreadyProcessed` when the player holds a claim of the offer, `offer_not_found` or
  `offer_not_active`, the decline's reason alone as the error, `spin_value_not_offered` or
  `game_not_eligible` as the evaluator decides, and a retryable unavailable answer for
  `ELIGIBILITY_CHECK_UNAVAILABLE`, as the original's webhook answers 503 for the campaign to retry);
  both MUST be idempotent per player and offer and MUST size a deposit match from the wallet's
  facts, never from the caller.
- **FR-022a**: A read route MUST answer, for a player, their grants with state, offer name,
  wagering requirement, progress and expiry, served to the player and to `support-agent` by identity
  within the project (spec 017 FR-009 `get_active_bonuses`).
- **FR-022b**: A service route MUST answer `game-provider` (spec 003 FR-025), for a player and a game,
  the player's oldest live free rounds on that game, or `no_eligible_grant`.

**Free rounds**

- **FR-023**: Granting free rounds MUST resolve the spin value for the player's currency (an offer
  with a spin value per currency refuses `currency_not_configured`, an unreadable currency
  `player_currency_unresolved`), check the game is eligible and the spin value is one the provider
  offers in the currency (through `game-provider`), refusing `game_not_eligible`, or
  `spin_value_not_offered` with the allowed values only when the provider names its values (not
  configured, or unreachable, goes ahead); and MUST run a workflow that makes the player known to
  the provider, creates the provider bonus under the grant id with the expiry as its duration, and
  answers once the creation answers (`active`, `processing` or `not_configured`; a rejection is
  `provider_rejected`); a processing bonus is polled every 30 seconds up to 50 times, and a rejection
  or the last poll cancels the grant as `provider_failed`, after which the player's free rounds no
  longer list it.
- **FR-024**: Each `round_settled` fact naming a grant MUST count one round and add the win to the
  accumulated winnings exactly once per round; on reaching the count, or on expiry, the full
  accumulated amount MUST be credited through the wallet's provider credit under the grant id
  (destination real balance: it lands on the primary open grant's restricted funds when one is
  open, else on real balance, as the original's credit does) or awarded as restricted funds with
  the offer's multiplier and maximum convertible amount, ahead of every open record and with a
  30-day claim deadline (destination restricted funds), uncapped at this point; nothing won calls
  no wallet. The grant is then `CLOSED`, or `EXPIRED` when it expired with nothing won. A round that
  settles after finalisation MUST still be paid, to the same destination under its own key.
- **FR-025**: Free-rounds expiry MUST be a timer at the offer's days (default 30) from grant.
- **FR-026**: An operator MUST be able to list a game's spin values in a currency as the provider
  offers them, distinct and sorted, with the source; a brand with no free rounds set up is an empty
  list with the source `not_configured`, and a provider that cannot be reached is
  `provider_unavailable`.

**Facts and cost**

- **FR-027**: A consumer over the grant's events MUST produce `casino.rewards` facts of the
  families spec 001 FR-011 lists — `bonus_granted`, `bonus_cancelled`, `bonus_forfeited`,
  `bonus_completed`, `grant_expired`, `free_spins_granted`, `free_spins_settled` — keyed by the
  player id, carrying grant, offer id, offer name, offer type, amount, currency, source, code and
  tag, once per transition (`bonus_completed` once per grant: at its conversion, or at its close
  when it never converted; `bonus_cancelled` for a cancel and a revoke, with the reason), so
  `reporting` (spec 015 FR-009) reports per offer without a call; and
  `bonus_code_entered` (code, outcome: matched, invalid, ineligible) once per code a player enters,
  which `engagement` (spec 014 FR-006) turns into `promo_code_entered`.
- **FR-028**: Bonus cost MUST be carried on `bonus_granted` (the awarded amount) and
  `free_spins_settled` (the amount credited or awarded) so `attribution` emits `bonus-cost` (spec
  012 FR-011) from facts alone; no call to any affiliate system remains.
- **FR-029**: Notifications (`bonus_granted`, `bonus_cancelled`, `bonus_completed` triggers) MUST
  reach Customer.io through `engagement` consuming `casino.rewards`, carrying the grant id so a
  campaign can name it; this service sends nothing itself.

**Duplicate signals (amends spec 008)**

- **FR-034**: `players` MUST mark a newly registered player's duplicate check in progress until its
  detector has run, or was skipped by the brand's kill switch, and MUST publish the start and the end
  on `casino.players`; a check never ended is not honoured after 10 minutes from registration.
- **FR-035**: The detector (spec 008 FR-036) MUST add an email-variant signal (the email's local part
  lower-cased, with a `+` tag, Gmail's dots and its digits removed, matching another player's) and
  MUST read the registration's address. A pair is strong when it shares the identity or device
  signal, or an email variant together with either a registration from the same address within 24
  hours or three or more registrations from the new player's address within 24 hours; a strong pair
  MUST flag both and open a fraud case as spec 008 FR-036 does. An email variant alone, or a lookup that
  fails, is a weak signal and MUST only block the new player's no-deposit offers, published on
  `casino.players`; an address's rate alone MUST do nothing. The outcome and the end of the check
  MUST be one change, so the check never ends before the outcome is recorded. The email and the
  address MUST be matched as lookup tokens, as spec 008's other signals are, never kept in the clear.
- **FR-036**: An operator with `brand_admin`, `resource_admin`, `brand_compliance`,
  `compliance_officer`, `fraud_analyst` or `platform_admin` MUST be able to clear a player's
  no-deposit block, recorded with the operator and the time and published on `casino.players`; the
  player's profile read MUST show the block as it shows the suspected-duplicate flag. A fraud case's
  dismissal does not clear it, as in the original.

**Reads, identity and data**

- **FR-030**: The player's state, free rounds, history and eligible list, and the operator's
  per-player and per-brand lists, MUST be views over this service's entities and the wallet's
  balance read; face values MUST be displayed in major units with the currency.
- **FR-031**: Player routes MUST require the brand's player realm; operator routes the brand's
  operator roles (`brand_admin`, `resource_admin`, `platform_admin`); service routes the calling
  service's identity (spec 001 FR-020), admitting `engagement`, `game-provider`, `payouts` and
  `support-agent`; the brand's list of grants admits any operator of the brand, as the original's
  does.
- **FR-032**: Every amount MUST be a whole number of minor units; a decimal MUST be refused naming
  the amount (spec 001 FR-026).
- **FR-033**: The service holds no personal data: a grant carries the player id (spec 001 FR-024),
  an offer carries no player field, and an operator's reason on a revoke is held as personal data
  under the operator (spec 002 FR-033's rule).

### Key Entities

- **Offer**: a brand's promotion with its terms, rules, codes, validity and order.
- **Grant**: one player's instance of an offer in one of ten states, with its clocks, snapshot,
  source and code; its events are the fact source.
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
- Dropped as stubs or migration tooling: the offer-concurrency backfill job and the
  retire-legacy-refs pre-flight.
- Dropped as unused or unsafe (settled while planning): the player's `/claim` (it sized a bonus
  from a deposit amount the player stated), the `/offers/eligible` list no frontend reads, and the
  `/promo-code` store and read (written, never read back by any grant path).
- Codes are trimmed and matched in any case, and unique in any case; the original matched them
  exactly on the casino side. An edit may change an offer's codes, with the same checks as
  creation; the original's edit could not.
- An affiliate's code from the programme's document selects the offer it names, with the source
  `affiliate_code` and the programme's tag; the original only recorded attribution from it.
- The deposit number is the wallet's deposit ordinal per player and currency (reconciliation 2);
  the original counted deposits across currencies.
- A confirming deposit expires a selection whose offer is no longer live; the original awarded it.
- A decline at a confirming deposit, and a wallet refusal, are recorded on the grant; the original
  only logged them.
- The target and award routes are idempotent per player and offer; the original's target made a
  second available grant.
- Bonus cost is on every `bonus_granted` and `free_spins_settled`; the original sent it only from a
  confirming deposit, and the affiliate service refused it (no `transactionId`).
- `bonus_code_entered` with its outcome, `free_spins_granted`, `free_spins_settled` and
  `grant_expired` are new facts: the original sent Customer.io and Kafka only the granted, cancelled
  and completed events.
- A round that settles after the free rounds were finalised is paid; the original skipped it.
- The player's eligible list is in the brand's priority order; the original's was unordered.

## Recordings

None is recorded yet (`tests/port/` has no `rewards/`); research R2 says how each is made. The Go
end-to-end suite, run against the original on its kind stack and recorded by ankka-port:
`TestScenario21_*` (OfferValidation, CleanGrantEveryPath, CodeMinDepositDepositNumberWindow,
Concurrency_ActiveBonusConflict, ConflictsWith, MaxClaimsAndExclusion,
SuspectedDuplicate_RefusedOnEveryPath; their `/claim` steps are marked as not carried);
`TestScenario16_RewardGrant_TargetSelectActivateForfeit` (`_LegacyStatusStillTransitions` exercises
legacy shapes that are not carried, and is kept only for its revoke);
`TestScenario19_BonusWageringCompletesToRealMoney`; `TestScenario23_RewardGrantExpiry` (its own
cadence recipe); `TestScenario244_*` (CancelWithBaselineClawsBackOnlyWinnings,
CancelWithNoBonusIsConflict, CancelWithoutBaselineSucceedsWithZeroClawback,
ConversionRecordsActivationBaseline, DepositMatchCancelStillWorks); `TestScenario245_*`
(PartialBonusAdjustLeavesRecordActive, ZeroedBonusClosesRecordAndGrant);
`TestScenario_BonusBust_LosingRoundForfeitsGrant`; `TestScenario254_*` (LossSettlementPromotesNext,
ZeroWinPromotesNext); `TestScenario18_MultiAccountDetection_BonusBlock`;
`TestScenarioFreeSpinsPlay_*` (BonusBalanceDestination, RealBalanceDestination, StacksOnActiveBonus,
CustomerIOGrant, MultiCurrencyGrant), re-recorded rather than taken from spec 003's of 2026-10-10.
The #730 gates (FR-034 to FR-036) have no end-to-end test in the original: a driver,
`tests/original/rewards-driver/`, records them, one scenario per feature scenario.

Playwright, the frontend oracle: `39-bonus-lifecycle` (claim, wagering to real, cancel with
clawback) and `43-bonus-revoke-and-decline-reasons` (a blocked grant names its reason; cancelling
the revoke dialog moves no money). `34-bonus-wagering-config` stubs its route in the page and is the
wallet's (spec 002), not a recording here.

Unit fixtures replayed against the entity and evaluator: `EligibilityEvaluatorSpec`,
`BonusGrantServiceSpec`, `RewardLifecycleSpec`, `RewardGrantStoreSpec`, `RewardStatusSpec`,
`RewardGrantSpec`, and the capability tables under `reward-grant-expiry`, `settle-free-spins-round`
and `finalize-expired-free-spins-grant`.

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
- An offer sets its maximum concurrent grants, one or more, or none for no limit; the wallet
  enforces the same number at award, sent on the award as the original's wallet takes it (settled
  2026-11-01; the wallet's award gains the field, research R6).
- Free-rounds winnings are awarded or credited uncapped at finalisation (amendment 2026-08-23),
  with the maximum convertible amount applying when the restricted funds convert (settled
  2026-11-01).
- The claim window for a pending selection is 3 days from selection, fixed; from registration
  only when the offer sets days (settled 2026-11-01).
- The programme (spec 013) and `engagement` (spec 014) are not built: the code document client and
  the target and award routes are built and tested against fakes, and are inert until those land
  (settled 2026-11-01).
- TL is not carried over; the timers here are ankka timers (spec 001 Assumptions).

## Dependencies

- Spec 002 wallet: award, forfeit, cancel with clawback, close pending cap, switch, balance read,
  `money.transactions`. As built on 2026-10-09: `POST `/v1/casino/brands/<brand>/wallets/{player}/{currency}`/awards` (the grant id as `key`, the rule
  snapshot in the body), `/awards/forfeit`, `/awards/cancel`, `/awards/close-pending-cap`,
  `/awards/switch` (the grant in the body), `GET …/restricted-funds`, and
  `PUT /v1/casino/brands/<brand>/wallet-config/bet-switches` (specs/002-wallet/contracts/routes.md).
  Since 2026-10-10 the wallet keeps only its most recent 1,000 keys: the lifetime record of a grant's
  wallet commands is this service's own (a workflow per grant, started as the atomic claim, is the
  shape spec 003 uses), and a repeat older than the wallet's recent keys is answered from that record,
  never resent to the wallet.
- Spec 003 game-provider: FR-025 bonus routes; `round_settled` carrying the grant; amended here by a
  service read of a game's bonus types, for FR-023's game check (research R6).
- Spec 002 wallet, amended here (research R6): the award's maximum concurrent grants; the grant
  named on close pending cap; no clawback on a cancel naming an unused secondary record; a cancel
  that requires a baseline (`MISSING_BASELINE`); and a `restricted_funds_closed` fact for every
  pending cap closed.
- Spec 008 players: `casino.players` fields the evaluator reads; amended here by FR-034 to FR-036
  with the duplicate check and the weak signal (the original's #730).
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
