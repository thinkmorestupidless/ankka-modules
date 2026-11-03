# Research: rewards

Phase 0 of [plan.md](plan.md). The original is `~/Documents/projects/eitheror/eitheror-platform` on
`main`, read on 2026-11-01. Paths under it are written `O/…`; `BR` is
`O/substrate/casino-service/src/main/scala/io/eitheror/casino/BonusRoutes.scala`, `EE` its
`EligibilityEvaluator.scala`, `BGS` its `BonusGrantService.scala`, `CW` its
`CustomerIOWebhookRoutes.scala`, `WR` `O/substrate/wallet-service/.../WalletRoutes.scala`.

## R1. The original, read whole

**Decision**: port the behaviour of `BonusRoutes` (3,936 lines), `EligibilityEvaluator`,
`BonusGrantService`, `BonusTargetingService`, `FreeSpinsProvisioner`, `CustomerIOWebhookRoutes`'
two reward actions, `RewardGrantStore` and `RewardStatus` (feature 216), and the six TL functions with
their capabilities (`reward-grant-expiry`, `set-withdrawal-cap-expiry`, `settle-free-spins-round`,
`finalize-expired-free-spins-grant`, `notify-bonus`, `export-bonus`; `award_bonus`,
`credit_real_balance`, `expire_reward_grant`, `customerio_dispatch`, `kafka_publish`). Correct the
spec wherever it said something the original does not do; the user settled this on 2026-11-01.

**What the spec had wrong, and what it now says** (each with its source):

| Spec said | The original | Now |
|---|---|---|
| a conversion with no cap closes the reward | every conversion is `CONVERTED_PENDING_CAP` (`O/starter-packs/wallet/handlers-go/internal/funding/placebet.go:95-108`; WR:1482-1486); the record closes later at the cap deadline, at real zero, or when a payout exhausts the allowance | FR-014 |
| the cap timer from "the cap window" | `cap_expires_at = conversion + wagering_window_days`, 30 when absent (`set_withdrawal_cap_expiry/handler.go:13,85-88`) | FR-014; glossary *cap window* |
| FR-014's four moves | also `bonus_exhausted` (loss or zero win, WR:1912-1935, 2272-2300), `admin_adjust_emptied` (WR:5111-5145), `real_balance_zero` and `cap_exhausted` (WR:1433-1451, 4630-4645) | FR-014 |
| an adjustment to zero closes | it forfeits (`admin_adjust_emptied`) | `lifecycle.feature` |
| a failed provision voids and tells the player | `CANCELLED`, reason `provider_failed`; the player's list excludes it (`BR:1385-1396`); it is not a claim (`EE:141-150`) | FR-023, `free-rounds.feature` |
| spin value refused when not offered | refused only on a known mismatch; not configured or unreachable goes ahead (`FreeSpinsProvisioner.scala:118-143`) | FR-023 |
| `not_configured` a refusal | 200, `values: []`, `source: "not_configured"`; 502 `provider_unavailable` (`BR:3125-3176`) | FR-026 |
| target refuses free spins | also `DEPOSIT_MATCH_WITH_FREE_SPINS`; `role_not_found` (`CW:759-771`) | FR-022 |
| award declines `ineligible: <reason>` | `{"error": "<REASON>"}`, 503 for `ELIGIBILITY_CHECK_UNAVAILABLE`, else 422; `offer_not_active` (`CW:200-215, 287-340`) | FR-022 |
| delete an offer | deactivate only (`BR:2417-2429`) | FR-003 |
| FR-002's list | also `spin_configurations_invalid`, `duplicate_code_in_request`; edit has its own list and `role_already_assigned` (`BR:2204-2415`) | FR-002 |
| maximum claims | 0 is no limit (`EE:199-204`) | FR-005 |
| the claim window | anchored by path: a confirming deposit from the selection (offer's days, else 3); elsewhere from registration and only when the offer sets days (`EE:263-281`) | FR-005 |
| the amount and currency | fixed when no percentage or no deposit; offer's currency first (`EE:430-456`, `CSM:681-683`) | FR-007 |
| player cancel `NO_ACTIVE_BONUS` | lower case `no_active_bonus`, `grant_not_found` (`BR:2838-2998`) | FR-016 |
| revoke | `MISSING_BASELINE` on reclaim; free-spins revoke `FREE_SPINS_FINISHED`, `PROVIDER_UNAVAILABLE`, provider cancel first (`BR:769-1130`) | FR-017 |
| the game provider's read | `game-provider` calls `…/rewards/{playerId}/free-rounds/eligible` (`services/game-provider/.../clients/Neighbours.scala:21-22,133-139`) | FR-022b |
| six reasons of FR-005 called twelve | fourteen (`EE:102-120`) | Context |
| five TL functions | six (`O/starter-packs/casino/chart/values.yaml:190,216,304,331`) | Context |

**Defects in the original, not carried**: an edit stores `redemptionCodes=""` and cannot change codes
(`BR:200-202`); code uniqueness is checked on create only (`BR:2098`); `/claim` matches a bare code
against suffixed entries and never matches a coded offer (`BR:2485-2489`); the webhook answers the
maximum convertible amount as `bonusAmount` (`CW:240-247`); the affiliate service refuses every
`notify/bonus-cost` for want of `transactionId` (`InternalRoutes.scala:1752-1770`); a stuck
`expired` free-spins grant is never re-touched (`finalize…/handler.go:62-73`); `already_processed`
is handled and produced nowhere (`WalletServiceClient.scala:836-845`).

**Alternatives considered**: port the spec as written. Rejected: constitution I, and the user's
answer.

## R2. Recordings

**Decision**: record the spec's *Recordings* on the original's kind stack (casino-service 8091,
Keycloak 8180, substrate 9000, fake-st8 30096), twice on fresh ids, with `e2e-recording.patch`
(`tests/original/README.md`), and move each into `tests/port/rewards/`. Write
`tests/original/rewards-driver/` for what the suite does not reach: the #730 gates (no end-to-end
test exists; the unit specs `MultiAccountDetectorSpec.scala:250-334`,
`CustomerIOWebhookRoutesSpec.scala:843-860` and `BackofficePlayerRoutesSpec.scala:1670` are its
oracle), the operator's two awards with no offer, revoke of free spins, and the spin-values read.

**What is runnable** (research of 2026-11-01):

- scenario 21 (seven tests), 16 (`TargetSelectActivateForfeit`, `LegacyStatusStillTransitions`), 19,
  244 (five), 245 (two), `BonusBust`, 254 (two), 18: stack plus Keycloak, several with operator
  credentials;
- scenario 23 only through `just test-e2e-cadence` (re-registers the cadence at 60 s);
- the free-spins tests need fake-st8, the adapter and `settle-free-spins-round-fn`;
  `O/tests/e2e/GAPS.md:18` says they are "written, not yet run in CI". The four recorded under
  `tests/port/game-provider/` (2026-10-10, `21da61f0`) predate features 236 to 254 and are re-recorded;
- not runnable, removed from the spec: scenario 9's two (hard skipped, pre-216 Kafka path),
  scenario 7 (no reward content), scenario 11 (hard skipped);
- Playwright `34-bonus-wagering-config` stubs its route in the page: the wallet's, not a recording.

**Needs the user**: the kind stack and the credentials (memory: recording the original on kind).

**Alternatives considered**: reuse spec 003's free-spins recordings. Rejected: they predate the
behaviour now specified (stacking, multi-currency, primary only).

## R3. Components: an offer book per brand, a record per player, an entity per reward

**Decision**:

- `OfferBook` (event sourced, id = slug) holds every offer. Code uniqueness (across the brand's
  offers and the programme's document), role uniqueness and the order are rules over all offers; one
  writer keeps them. A brand has dozens of offers; the original lists them all on every read
  (`BR:2173-2184`).
- `PlayerRewards` (event sourced, id = player) holds what the evaluator reads: registration time,
  the duplicate flags, the tier, the player's rewards (id, offer, state) and claims, the offers
  visible to them, the last deposit (for the award route). A claim, a selection, a code and a
  confirming deposit are each one command to it: it evaluates and reserves the reward id in one
  event, so two at once are serialised (the edge case "two selections at once").
- `Reward` (event sourced, id = reward id) holds the ten states with the original's table
  (`RewardStatus.scala:60-66`), the clocks, the rule snapshot (`OfferRuleSnapshot.scala:27-53`),
  source, code, tag, and the free-rounds counters. Its events are the fact source.
- `IndexConsumer` carries each reward's state back to `PlayerRewards`, as the original's
  `HAS_REWARD_GRANT` edge did. The evaluator's open count is `max(PlayerRewards' open rewards, the
  wallet's active records)` (`EE:164-165`); the wallet's count is read with `GET …/restricted-funds`
  at evaluation, failure counting 0 as the original did (`EE:501-512`).

**Alternatives considered**: an entity per offer with a uniqueness index entity (two writes per
create, and a reorder across many entities); the evaluator over a view (seconds behind).

## R4. Eligibility and codes

**Decision**: `Evaluator` is `EligibilityEvaluator` ported rule by rule, with `OperatorOverride`
running rules 1–4 (`EE:367`):

| # | Rule | Reason | Notes |
|---|---|---|---|
| 1 | duplicate check in progress | `ELIGIBILITY_CHECK_UNAVAILABLE` | no-deposit offer; check in progress and now < registration + 10 min (`EE:325-342`) |
| 2 | no-deposit blocked | `NO_DEPOSIT_OFFER_BLOCKED` | no-deposit offer (`EE:345-350`) |
| 3 | suspected duplicate | `SUSPECTED_DUPLICATE` | any offer; rules 1–3 skipped when the brand turned detection off |
| 4 | open maximum | `ACTIVE_BONUS_CONFLICT` | open = `ACTIVE`, `CONVERTED_PENDING_CAP`, `PAUSED` |
| 5 | conflicts | `CONFLICTING_GRANT` | |
| 6 | claims | `MAX_CLAIMS_REACHED` | 0 is no limit; a claim is any reward not available or pending, not cancelled `provider_failed` |
| 7 | exclusions | `EXCLUDED_BY_PRIOR_OFFER` | |
| 8 | code | `INVALID_CODE` | skipped when verified at targeting; normalised as below |
| 9 | minimum deposit | `BELOW_MIN_DEPOSIT` | with a deposit; also from the amount when none (`EE:380-388`) |
| 10 | deposit number | `DEPOSIT_NUMBER_MISMATCH` | `ANY` or exact ordinal |
| 11 | claim window | `CLAIM_WINDOW_EXPIRED` | by anchor (R1) |
| 12 | segment | `SEGMENT_MISMATCH` | dormant: no writer of segments, as in the original |
| 13 | tier floor | `VIP_TIER_TOO_LOW` | dormant: the original compares an integer rank nothing writes; `vip_tier_changed` carries a name, so the floor passes until a rank exists |

`ALREADY_CLAIMED` is not an evaluator reason: it is the answer to an award of an offer the player
already holds a claim of (the award route answers `alreadyProcessed` instead, `CW:174-183`).

**Codes** (settled 2026-11-01): stored as entered, matched on `trim.toLowerCase` against live
offers first, then the programme's document; uniqueness in the same form across both
(`duplicate_code_in_request`, `code_already_in_use` naming the code). The registration's code is
not a second path: the original returns it at login as `pendingBonusCode` for the frontend to enter
(`PlayerRoutes.scala:760`), so it arrives through FR-019 and `players` clears it on
`bonus_code_entered`.

**The deposit-confirm path** (`CSM:660-777`): the player's first pending reward, `codeVerifiedAtTargeting`,
source `player_code`, the wallet's ordinal as the number. `CLAIM_WINDOW_EXPIRED`, or an offer no
longer live, expires it; any other decline is recorded and leaves it pending.

**Alternatives considered**: evaluate in the endpoint over the view (stale; R3).

## R5. The wallet's commands: keyed, through a workflow, a lifetime record

**Decision**: every wallet command is a `WalletStep` workflow, id `<reward>/<step>`:

| Step | Wallet route | Key | Body | On answer |
|---|---|---|---|---|
| `award` | `POST W/awards` | reward id (the wallet names the record by it) | `bonusRef` = offer id (`manual:<uuid>` with no offer), amount, multiplier, `maxWin` = maximum convertible (else the amount, `EE:453-455`), priority, `expiresAt` = now + wagering window days (30), rule snapshot flattened, `maxConcurrentGrants` (W1) | `Reward.activated` with the wallet's requirement; a refusal `Reward.awardRefused(code)` |
| `forfeit` | `POST W/awards/forfeit` | `<reward>/forfeit` | `grantRef` | `EXPIRED` (timer) or `REVOKED` (`forfeit` mode) |
| `cancel` | `POST W/awards/cancel` | `<reward>/cancel` | `grantRef`, `requireBaseline` on reclaim (W4) | `CANCELLED` (player) or `REVOKED` (`reclaim`), with the clawback applied |
| `close-cap` | `POST W/awards/close-pending-cap` | `<reward>/close-cap` | `grantRef` (W2) | `CLOSED` `cap_expired` |
| `switch-<n>` | `POST W/awards/switch` | `<reward>/switch-<n>` | `grantRef` | records the switch |
| `credit` | `POST W/provider-credits` | `<reward>/credit` | amount, `providerRef` = reward id | `CLOSED` |
| `winnings` | `POST W/awards` | `<reward>/winnings` | as `award`, priority `min(open) − 1`, 30-day expiry (`award_bonus/handler.go:140-191`) | `CLOSED` |
| `late-<round>` | either of the two above | `<reward>/late/<round>` | the round's win | recorded |

`start` is the atomic claim (a second start of the same id answers the first's state); the step
retries a transport failure or 5xx with backoff until the wallet answers, then hands the answer to
`Reward`. Past the wallet's 1,000 recent keys a repeat is answered from the workflow's state, never
resent (spec Dependencies). `NO_ACTIVE_BONUS` and `NOT_PENDING_CAP` on a timer's step move the reward
as the timer would (the record is not open) and move no money (FR-015).

The **revoke `forfeit`** uses the wallet's forfeit route. The original sent a zero-clawback cancel
(`BR:1001-1005`); the port's cancel always computes the wallet's clawback, and its forfeit moves
the same money (the record to zero, nothing else). The difference is the family's `reason` on
`money.transactions` (`forfeit`, not `cancel`), marked in the parity test.

**Alternatives considered**: call the wallet from the endpoint or the entity (no lifetime record;
the 1,000-key window); a workflow per reward for every command (serialises a switch behind a pending
timer step).
- **Amended during implementation (2026-11-02):** an operator's revoke has steps of its own,
  `<reward>/revoke-forfeit` and `<reward>/revoke-reclaim`, not `forfeit` and `cancel`. A workflow
  started twice answers its first state, so a refused revoke under the shared ids would block the
  claim timer's forfeit and the player's cancel. Wallet keys stay `<reward>/<step>` (FR-018).


## R6. What the neighbours must change

**Decision**: amend the wallet (spec 002) and `game-provider` (spec 003), each with its suites and
contracts, in this branch.

| | Change | Why |
|---|---|---|
| W1 | `AwardRequest.maxConcurrentGrants: Option[Int]`; when present it is the limit, else the brand's `maxConcurrentAwards`; counts active records | the original's wallet takes the number on the award (`WR:69-72, 3859-3861`); the offer sets it (FR-005) |
| W2 | close pending cap takes `grantRef` and closes that record, `NOT_PENDING_CAP` when it is not pending cap | the original scoped by grant (`WR:315-336`); the port closes the first, which is another reward's when two wait |
| W3 | a cancel naming a record that is not primary and has no release progress claws back nothing | the original's "unused secondary" (`BR:2919-2925`) |
| W4 | `CancelAward.requireBaseline`: refuse `MISSING_BASELINE` (409) when the named record has none | the operator's reclaim (`BR:1012-1023`); records from spec 019's migration may have none |
| W5 | a `restricted_funds_closed` fact (`awardId`, `reason`: `cap_expired`, `cap_exhausted`, `real_balance_zero`) for every pending cap the wallet closes | `PendingCapClosed` publishes nothing (`publish/Facts.scala:169-171`), so a payout exhausting the cap leaves the reward waiting |
| G1 | `GET /v1/casino/brands/<slug>/game-provider/{provider}/games/{gameCode}` for `Routes.serviceOf("rewards")`: `{gameCode, bonusTypes}`, 404 `game_not_found` | the game check (`BR:546-553`, `SubstrateTwinClient.scala:2179-2180`: eligible when bonus types hold `free_bets` or `free_spins`); the catalogue reads are player- and operator-only |

The port's wallet does not close a pending cap when real balance reaches zero (the original's
`CloseCapsAtZeroReal`, `complete.go:164-178`): a spec 002 parity gap, recorded under *Elsewhere*;
W5's reason is ready for it.

**Alternatives considered**: enforce the offer's maximum in rewards only (the wallet's brand-wide
limit then contradicts it); read the restricted funds to learn of a close (a poll per reward).

## R7. The routes: four endpoints

**Decision**: four prefixes under `/v1/casino/brands/<slug>`:

- `/rewards` — `RewardsEndpoint`, `Routes.player(slug)`, the path's player the token's. Two routes by
  `withAcl`: `GET /{playerId}/grants` for `Routes.serviceOf("support-agent")` and the player;
  `GET /{playerId}/free-rounds/eligible` for `Routes.serviceOf("game-provider")`, the path
  `game-provider` already calls.
- `/offers` — `OffersEndpoint`, `Routes.operatorHolding(slug, brand_admin, resource_admin,
  platform_admin)`.
- `/reward-grants` — `RewardAdminEndpoint`: the brand's list `Routes.operator(slug)` (any operator,
  as `BR:3888`); the rest the three admin roles.
- `/reward-campaigns` — `CampaignEndpoint`, `Routes.serviceOf("engagement")`.

`payouts` calls nothing here: the wallet's hold answers what it forfeits, so FR-031 no longer
admits it (amended at analysis).

Carried with no requirement before (settled 2026-11-01): the operator's free bonus and free rounds
with no offer (`BR:3363-3565`). Dropped: `/claim`, `/offers/eligible`, `/promo-code` store and read.

**Alternatives considered**: one endpoint per audience with a shared prefix (refused by 0.13).

## R8. Free rounds

**Decision**: `FreeRounds` (workflow, id = reward id):

1. `known`: `POST …/game-provider/st8/players/{playerId}/known`; a failure is logged and the create
   goes ahead (`FreeSpinsProvisioner.scala:159-170`);
2. `create`: `POST …/bonuses` with `grantId` = reward id, count, spin value, currency, game codes,
   `durationSeconds` = expiry days × 86,400; the route answers after this step (`active`,
   `processing`, `not_configured` playable; `rejected`, `failed`, `error` → `provider_rejected` and
   `CANCELLED provider_failed`);
3. `poll`: every 30 s, at most 50 times, `GET …/bonuses/{grantId}`; `active` ends it; a terminal
   failure or the 50th poll cancels the reward `provider_failed`; a reward no longer active ends it
   silently (`:267-313`);
4. `cancel` (on revoke): `POST …/bonuses/{grantId}/cancel`; transport failure → `PROVIDER_UNAVAILABLE`;
   any other answer revokes locally (`BR:828-958`).

A create is never resent after `active` or `processing`: the simulator replaces a bonus under the
same id (`BonusBook.scala:29,37-38`).

**Settlement** (`settle_free_spins_round/handler.go:86-275`, `finalize_free_spins_grant/handler.go:83-162`):
`round_settled` with `freeRoundsGrant` counts once per `roundId` and adds `winCents`; complete at
`max(rounds used, rounds seen) >= count`; on completion or at the `rounds-<reward>` timer, a
positive total goes to the destination (R5's `credit` or `winnings`), zero calls no wallet; then
`CLOSED`, or `EXPIRED` at expiry with nothing won. A round after finalisation pays its own win under
`late-<round>` (FR-024, a change). A round for a reward this service does not know is recorded as
unmatched on `PlayerRewards` (the fact names the player) and shown in the operator's list of the
player's rewards (edge case).

**Real balance** (settled 2026-11-01): the wallet's provider credit, which lands on the primary open
record when one is open, else on real balance (`services/wallet/.../funding/Resolver.scala:480-487`),
as the original's `game_provider_credit` (`WR:4063-4096`). Attribution already counts a credit landing
on restricted as bonus cost (`services/attribution/.../money/RealMoney.scala:39-43`).

**Alternatives considered**: the original's in-memory scheduler (lost on restart); the wallet's real
adjustment (always real, but an operator action with a reason: the user chose the original).

## R9. Duplicate signals in `players` (FR-034 to FR-036)

**Decision**, in `services/players/`:

- `RegistrationWorkflow` records `DuplicateCheckStarted` on the player before its `match` step and
  publishes `player_duplicate_check_started`; the `match` step's outcome and `DuplicateCheckEnded`
  are one event (`strong`, `weak`, `clear`, or `skipped` by the kill switch), published as
  `player_duplicate_check_ended`. A weak outcome adds `NoDepositBlocked`, published as
  `player_no_deposit_blocked`.
- `LookupTokens` gains `emailVariant` (local part lower-cased, `+tag`, Gmail dots and digits removed,
  with the domain; `MultiAccountDetector.scala`'s normalisation) and `address` (the registration's
  network address), both HMAC under `players-lookup-key`. `LookupIndexEntity` keeps, per address
  token, the registrations of the last 24 hours.
- Strong: identity or device, or an email variant plus the same address within 24 h, or three or
  more registrations from the address in 24 h. Weak: an email variant alone, or a lookup that fails
  after its retries (the workflow's failure path now ends `weak`, not skipped). The address rate alone
  does nothing.
- `POST /v1/casino/backoffice/<slug>/players/{playerId}/clear-no-deposit-block`, roles
  `brand_admin`, `resource_admin`, `brand_compliance`, `compliance_officer`, `fraud_analyst`,
  `platform_admin` (`Routes.operator` plus the handler's set, as kyc's `ComplianceRoles`); answers
  `{playerId, blockCleared: true}` (`BPR:2037-2074`); publishes `player_no_deposit_block_cleared` with
  the actor. The operator profile shows `noDepositOfferBlocked`. Dismissing a fraud case does not
  clear it.

`PlayersConsumer` in rewards: started → in progress (with the registration time), ended → not; the
block and its clearing; `player_fraud_flagged` / `_cleared` → suspected duplicate; `vip_tier_changed`
→ tier; the brand's kill switch is read from players' `GET …/players/config/brand-config` (service
route, `Routes.service()`) at start and on each check fact.

**Alternatives considered**: infer the in-progress state from the absence of a flag (cannot end it).

## R10. The clocks

**Decision**: timers, named by the reward, each a no-op on a terminal reward:

| Timer | Set | Fires |
|---|---|---|
| `pending-<reward>` | at selection: selection + offer's claim window days, else 3 | `EXPIRED`, no wallet command |
| `claim-<reward>` | at activation: award + wagering window days, else 30 | `forfeit` step, `EXPIRED` `claim_expired` |
| `wagering-<reward>` | when the reward has a wagering deadline apart from the claim deadline (free-rounds winnings) | `forfeit` step, `EXPIRED` `wagering_window_expired` |
| `cap-<reward>` | at conversion: conversion + wagering window days, else 30 | `close-cap` step, `CLOSED` `cap_expired` |
| `rounds-<reward>` | at a free-rounds grant: grant + expiry days, else 30 | finalise (R8) |

The claim deadline fires before the wagering window when both are due (`expire_reward_grant/handler.go:111-121`).
A timer is cancelled when the reward leaves the state it watches.

## R11. Facts and the contract

**Decision**: `RewardsPublisher` maps `Reward` events to `casino.rewards`, keyed by the player id,
`ce-id` `<reward>/<seq>`:

| Event | Family |
|---|---|
| activated (funds) | `bonus_granted` (amount = the award) |
| free rounds granted | `free_spins_granted` |
| converted | `bonus_completed` |
| closed, never converted | `bonus_completed` |
| closed after conversion | — (the original's Customer.io dedup sent one completion, `customerio_dispatch/handler.go:372-379`) |
| forfeited | `bonus_forfeited` (amount, currency, reason) |
| cancelled, revoked | `bonus_cancelled` (reason: `player_cancel`, `forfeit`, `reclaim`, `provider_failed`, `free_spins_revoked`) |
| expired | `grant_expired` |
| free rounds finalised | `free_spins_settled` (winCents = the amount paid) |

`CodesPublisher` maps `PlayerRewards`' code entries to `bonus_code_entered` (code, outcome `matched`,
`invalid`, `ineligible`). Contract additions in plan.md's *Technical Context*; all optional fields or
new families, so the `Lenient` readers and `Compatibility` accept them.

## R12. Personal data

**Decision**: nothing personal is held or published. The reward and the view carry the player id;
offers carry no player field; the operator's revoke reason is `Personal` under the operator in the
audit record. `players`' new tokens are HMACs (R9). No email or address on a fact, log, span or timer
name; `LeakSuite` checks the published facts and the logs.

## R13. The programme's code document

**Decision**: `CodeDocument` (key value, id = slug) holds the last fetched document and its time;
`codes-<slug>` refreshes it every 5 minutes from `REWARDS_CODE_DOCUMENT_URL`, a key added to
`contract/keys.md` and `modules/casino/module.json`'s `configuration[]`, empty until spec 013
publishes one (inert, settled 2026-11-01). A fetch failure keeps the last copy, but a 404 is a document with no codes (decided 2026-11-02, so
a brand can create offers before spec 013 publishes one); the operator's
`GET /offers/code-document` answers its age and `stale` past 15 minutes.

## R14. Local run and CI

**Decision**: HTTP 9008, Postgres 5441 (free, research of 2026-11-01). `just rewards-test`,
`rewards-it`, `rewards-run` (with `-Dankka.local-services.wallet=…:9000`,
`game-provider=…:9009`). CI: a `rewards` filter (`*build`, `services/rewards/**`,
`features/rewards/**`, `tests/port/rewards/**`) and job as payouts'. Use
`CASINO_VERSION=0.2.0-011-SNAPSHOT` (memory: contract snapshot collisions).

## For ankka

Nothing new. A workflow test kit would spare `WalletStep` and `FreeRounds` the shared kit; 0.13 has
none, as payouts found.

## Declined remediations

None.

## Elsewhere

- **Spec 002 wallet**: closing a pending cap when real balance reaches zero is not built (R6).
- **Spec 012 attribution**: bonus cost counts every `restricted_funds_awarded`; the original sent it
  only from a confirming deposit (and it was refused). Spec 012 decides whether every award is cost.
- **Spec 015 reporting**: its features name bonus cost; free rounds cost their `free_spins_settled`
  amount, as attribution now counts it.
- **Spec 013 programme**: the code document (`code`, `offerId`, `tag`) per brand and its URL.
- **Spec 014 engagement**: translates the webhook's `grant_bonus` and `target_offer` to
  `/reward-campaigns`, passing the answers through.
- **Spec 017 support-agent**: `get_active_bonuses` reads `GET /rewards/{playerId}/grants`.
- **Spec 019 migration**: opening events for `Reward` from the original's grants, and records without
  a baseline (W4).
- **Spec 003 game-provider**: its `contracts/routes.md` names `serviceOf("rewards")` for the bonus
  routes; as built they admit `Routes.service()`.

## Design finished

2026-11-01: analyze reported nothing above LOW.

## Found during implementation

Readings taken where the spec, the research and the original left a choice, each the original's
behaviour unless it says otherwise.

- **Status wire names** (T019): the original's upper-case names (`ACTIVE`, `CONVERTED_PENDING_CAP`, …);
  `casino.rewards` carries no status field, so nothing outside the service reads them.
- **Free rounds settled with nothing won** (T022): completion closes the reward (`free_spins_finalised`);
  expiry with nothing accumulated expires it (`free_spins_expired_unused`), even when rounds were played.
- **The late round** (T022, FR-024): a new round after payment is paid once under `late-<round>` and
  changes neither the finalised amount nor `free_spins_settled`. A revoked, cancelled or forfeited
  reward counts no round.
- **Code uniqueness** (T020): checked against the brand's other active offers; matching uses live
  offers. A code of an inactive offer may be reused.
- **Role on create** (T020): `role_already_assigned` is checked on edit only, as FR-002.
- **Edit checks** (T020): the original's order, including `spin_value_required`; an edit skips the
  create's amount and multiplier checks; only codes new to the offer are checked for being in use.
- **W3 and a reclaim** (T009): a reclaim (`requireBaseline`) claws back from the baseline and does not
  take the unused-secondary rule, which was the player's cancel's alone (`BR:2919-2925`, `BR:1024-1041`).
- **`MISSING_BASELINE` order** (T010): after the open-grant check, so a grant that is not open is
  `GRANT_NOT_OPEN`.
- **W1's limit** (T007): used as sent; only the brand config refuses a limit below 1.
- **The programme's document** (T023): fetched with `java.net.http.HttpClient` (ankka's service clients
  reach services by name only), bounded by the request bound and 1 MiB, no redirects.
- **G1's catalogue** (T012): the provider's enabled games in the feed, as the player's single-game read;
  the brand's overrides do not apply.
- **The duplicate check's rules** (T014, FR-035): an email variant is strong when the matched player
  registered from the same address within 24 hours, or the address has 3 or more registrations in 24
  hours; a variant alone is weak; the address rate alone is clear. A strong outcome does not block
  no-deposit offers, as the original. Normalisation as `MultiAccountDetector`: digits stripped on every
  domain, no variant under 6 characters. A registration with no tokens ends clear; the kill switch
  ends skipped.
- **Clearing a block not held** (T015): answers 200, as the original, and records nothing.
- **The wallet step's retries** (T027): every 4xx with a code is an answer, delivered once; only no
  answer, a 5xx or a 4xx without a code is retried, from 1 s doubling to 10 minutes, without limit. A
  repeat start answers the first state, whatever its body. Delivery is at least once, so `Reward` takes
  one answer per step id.
- **The reward's one deadline** (T026): `expiresAt` is the current state's deadline (pending, claim,
  rounds, then cap); `wagering-` is set only when it differs from the claim deadline. Rounds count only
  while `ACTIVE`. Finalisation writes `FreeRoundsFinalised` and the ending together; the payment
  follows, keyed, so a repeat answers the same payment.
- **The revoke reason** (T026, T029): kept in the `Revoked` event alone; the audit names the field with
  no value, as payouts'. `bonus_cancelled.amountCents` is the clawback.
- **Whole amounts** (T030, FR-032): refused 400 `amount_not_minor_units` naming the field, game-provider's code.
- **The award path** (T036): a no-deposit select starts `<reward>/award` and polls the step up to the
  request bound: awarded `{ok: true}`, the wallet's refusal 409 `{error: code}`, no answer 503. An
  award answered after the reward ended (cancelled or expired while in flight) starts
  `<reward>/cancel`, so the wallet's record does not stay open; the reward keeps its ending.
- **Two selections at once** (spec edge case): a select while another selection is pending is refused
  409 `ACTIVE_BONUS_CONFLICT`, in `PlayerRewards`, not the evaluator. The original allowed several; the
  spec and the `state` route's single `selected` do not.
- **Activation sizes the award** (T036): a deposit match is sized at the deposit; `Activated` carries
  the amount, multiplier, maximum convertible and currency the wallet awarded.
- **Offer create order** (T035): the offer's own fields, then spin values, then the game's
  eligibility, then codes. A provider that is unconfigured or unreachable lets a spin value through;
  a game that cannot be looked up is 503 `service_unavailable`. A missing `type` is `DEPOSIT_MATCH`.
- **A deposit** (T033) is evaluated at the fact's `occurredAt`, in the offer's currency else the
  deposit's; source, code and tag are fixed at targeting.
- **Revoke is recorded by its route** (T050): the reason is `Personal` and may live only in the
  `Revoked` event, so the delivery leaves a revoke's answer alone and the route records it; no answer
  within the bound is 503, and asking again answers from the finished step. The order: admin role,
  mode, reward, then free rounds or funds; a pending cap is `NO_ACTIVE_BONUS`.
- **Free rounds revoked from the route** (T050): the provider's cancel, then the revoke, until T053's
  `FreeRounds` takes the cancel; only no answer or the provider's `error` is 503
  `PROVIDER_UNAVAILABLE`.
- **The player's cancel** (T049): the open rewards are the wallet's open records whose reward is
  `ACTIVE`; an empty `grantRef` is `grant_not_found`, never the primary; no grant named cancels the
  primary.
- **A preview records nothing** (decided 2026-11-02, FR-020): no `CodeEntered`, no
  `bonus_code_entered`, as the original, so a preview never clears the registration's pending code.
- **A claim after the wallet refused** (T040): the award key is the reward id and the wallet keeps its
  refusal under it, so a claim of a reward whose award was refused opens a fresh reward id in the
  same state; the old one expires `superseded`.
- **One selection at a time** (T040): a select, or a no-deposit select, while any other reward is
  pending is refused 409 `ACTIVE_BONUS_CONFLICT`.
- **The detection switch** (T039): read on the first player fact after start and on each duplicate
  check fact; a failed read keeps the last value. Every `player_fraud_flagged` sets the
  suspected-duplicate flag.
- **A matched code selects** (T044): as codes.feature ("visible and selected"); the original only
  revealed it. A no-deposit offer is awarded at once; a decline is 409 `{status: "ineligible", reason}`.
  The preview's text names the offer's currency (the original wrote £).
- **Codes against the document** (T043): an offer create fetches the document when no copy exists
  (503 when that fails); an edit checks the copy it has.
- **The provider's create** (T053): no answer or a 4xx is `failed` and the reward is cancelled
  `provider_failed`; the create is never resent. A poll with no answer counts towards the 50 and
  polling goes on (the original's poll stopped silently on an error); a reward no longer `ACTIVE`
  ends the polling with no cancel. A revoke's cancel redirects the workflow from any phase.
- **The grant order** (T054): currency, evaluation (reserving nothing), the game, the spin value, then
  the claim, which evaluates again; the reward opens `ACTIVE` at face value 0 before the create, as the
  original. A create not answered within the bound is 503 and the workflow carries on.
- **Spin values in minor units** (T054, T057, FR-032): `allowedValues` and the spin-values route's
  `values`, where the original sent decimal strings; a configured but empty list refuses
  `spin_value_not_offered`.
- **A round for a reward not held** (T055): recorded once per round id on the player, the newest 200,
  matched by grant id only.
- **A restart mid-poll** (T053): ankka 0.13's test kit keeps no pause timer to stop and resume, so the
  suite proves the poll count is journalled and a repeated start neither restarts polling nor creates
  again; the restart itself is not exercised (For ankka).
- **Face values in minor units** (decided 2026-11-02): FR-030's lists carry face values in minor units
  with their currency, as FR-032 and the contract; the original sent major-unit strings, a divergence
  parity names.
- **The campaign's award** (T059): currency the offer's, else the body's, else the player's last seen,
  else 422 `player_currency_unresolved` (the original defaulted to EUR); the deposit the last confirmed
  one; a wallet refusal or no answer within the bound is 422 `grant_failed`, and the next retry answers
  `alreadyProcessed`. A grant the service cannot finish (the game or the create unanswered) is 503
  `service_unavailable`. Codes are not asked (`codeVerified`), for funds and free rounds alike.
- **The operator's award takes a fresh reward** (T060): it never claims the player's available reward,
  so the source is the operator's and the award is audited; it has no repeat check, as the original.
  An inactive offer can still be awarded (`BR:3198`).
- **The operator's lists** (T060): `pageSize` outside 1–200 is 400 `{error: "pageSize"}`; with
  `playerId` every reward up to 200; free rounds valued at spin value × count, or what they paid once
  closed.
- **The player's grants** (T061): every reward the player holds, whatever its state, as the original's
  `get_active_bonuses`.
- **Free-rounds cost from `free_spins_settled`** (decided 2026-11-02): attribution reads
  `casino.rewards` as well, and a free-rounds grant's bonus cost is its `free_spins_settled` amount,
  keyed by the reward, whatever the destination; it no longer counts a restricted provider credit or a
  winnings award of a free-rounds reward, so nothing is counted twice (SC-004, "from the topic alone").
- **Attribution names `restricted_funds_closed`** (T063): its money consumer had no case for the new
  family and would have failed on the first one.
- **What the leak and outbound suites read** (T064): spans are not scanned (the kit exposes none; a
  span carries only declared names, a subject and a client key); "only these are called" counts the
  service's own calls, not the runtime's key fetch, telemetry, broker or database.
- **Free-rounds money recognised in attribution** (2026-11-02): the restricted destination's awards by
  their keys (`<reward>/winnings`, `<reward>/late/<round>`); the real destination's credit by the reward
  ids attribution learns from `free_spins_granted` and `free_spins_settled`. A credit read before its
  reward, for a player already attributed, is still counted: possible only on a replay where money runs
  far ahead of `casino.rewards`.
- **The kyc recordings listed as unclaimed** (2026-11-02, the user, "List kyc as unclaimed"): `recordings
  check` refused the 35 kyc recordings spec 009 committed with no requirement. They are listed in
  `tests/port/unclaimed.txt` until spec 009 names one in each, rather than given a mapping here.
- **Recordings the original could not give** (2026-11-02): `TestScenario23_RewardGrantExpiry` fails on the
  original at `7630feb0` (its setup's losing bet forfeits the grant, the bonus bust); and
  `TestScenario21_CodeMinDepositDepositNumberWindow` passed twice in nine runs. Neither is kept;
  `tests/port/rewards/README.md` says why. FR-015 and FR-025 rest on the expiry capabilities' tables.
- **Free rounds on kind, run locally instead** (2026-11-03, the user, "Run it locally"): on `dev-kind`
  game-provider cannot reach the simulator. Through the gateway its ST8 client trusts no local CA (PKIX);
  the simulator's in-cluster port answers no plain HTTP. T071's free-rounds leg runs as quickstart §4
  does, on the laptop; giving game-provider an optional local CA is a follow-up for specs 003 and 020.
- **What T071 found on `dev-kind`** (2026-11-03), none of it in rewards:
  - `modules apply` installs core before casino and waits for core's services, but core's `players`
    reads `casino.rewards`, whose schema the casino module declares. A change to that schema leaves
    `players` refusing its projections and the apply stuck; pausing `players` let the casino module
    declare it.
  - A service whose image tag (`0.0.0`) did not change is not rolled, so it keeps running the old
    image: the wallet published `money.transactions` on the old schema, and rewards skipped the deposit
    as unreadable.
  - players' `INGEST_URL` names the gateway's host port (8443), which a pod cannot reach, and players
    trusts no local CA either, so a deposit cannot start from players. The run opened the cashier
    session at ingest from the host.
  - The duplicate check (FR-035) declined the scripted players, who shared a name and a birth date:
    the rule working, the script's data at fault.
- **What T071's local run found** (2026-11-03): the free-rounds leg passed on the laptop. Three rounds
  (1.50, 0, 2.00) closed the reward `free_spins_finalised` with 3.50 won, rewards' `credit` step paid
  it, and the real balance went from 1.01 to 4.51. Getting there:
  - game-provider reads a provider's key document as `{keys:[{kid, publicKey}]}` (spec 003
    `contracts/provider-api.md`); the simulator publishes `{current:{kid, spki}, next, cacheSeconds}`
    (spec 020 `contracts/st8-wire.md`). No key verifies, so the game page's `check` is `auth_failed` and
    no round is played. The run configured the provider with the simulator's key itself
    (`providerPublicKey`). A cross-spec defect for 003 and 020, not rewards'.
  - A launch made before the new grant reaches the free-rounds list opens a session without
    `freeSpinsGrantRef`; the simulator still spends the grant's rounds there, game-provider settles them
    as ordinary rounds, and the reward never counts them. The simulator also spends whichever of the
    player's bonuses it holds for the game, not the session's. The index lag the parity suite waits for
    (T067) shows up here as lost rounds.
  - The wallet opens on a deposit, a bet, an award or a migration, not on an adjustment; a player known
    only on kind has no local wallet, so the first finalisation's payment was refused
    `WALLET_NOT_FOUND`. The run opened it with an award, forfeited at once.
  - Running locally needs a Kafka broker (`ANKKA_KAFKA_BOOTSTRAP_SERVERS`), a fixed `ANKKA_SECRET_KEY`
    per service (a fresh one cannot read the secrets stored under the last), and players, kyc and
    responsible-gambling answering game-provider's launch and bet checks (the run used a stub).
  - `just rewards-run`'s `-Dankka.local-services.*` never reached the forked run; `build.sbt` now
    passes `ankka.*` properties on.
- **Winnings, a reward of their own** (2026-11-03, the user, "Own reward", after the review's blocking
  finding): free-rounds winnings paid to the bonus balance were a bare wallet record that nothing
  expired, cap-closed, cancelled or switched. They are now a reward of their own, under the wallet
  key they already had (`<reward>/winnings`, `<reward>/late/<round>`), opened from the free-rounds
  reward and awarded through the `winnings` step, which activates it as an award does. The original
  did the same (`award_bonus/handler.go:210-238`, a grant `ACTIVE` with a 30-day expiry). Its
  activation publishes `bonus_granted`, as any reward's. `Clock.Wagering` stays unused: no reward has
  a wagering deadline apart from its claim deadline.
- **One pull request** (2026-11-03, the user, "One PR"): the neighbours' amendments stay on this
  branch, the review's split declined. The pull request body lists them by service.
