# Data model: rewards

Phase 1 of [plan.md](plan.md). Money is `Long` minor units with its currency. Times are `Instant`.
Wire names are camelCase; stored types go through `RewardsCodecs` (manifests `rewards.<name>`).

## `OfferBook` (event sourced, id = slug)

**State**: `offers: Map[OfferId, Offer]`, `order: Vector[OfferId]`, `codes: Map[NormalisedCode, OfferId]`
(live offers only), `roles: Map[String, OfferId]` (active offers only).

**`Offer`** (FR-001; the original's names in brackets where they differ):

| Field | Type | Notes |
|---|---|---|
| `id`, `name`, `description` | String | |
| `type` | `DEPOSIT_MATCH` \| `FREE_SPINS` \| `DEPOSIT_MATCH_WITH_FREE_SPINS` \| `NO_DEPOSIT` | |
| `payloadType` | `bonus_funds` \| `real_cash` \| `free_spins` \| `deposit_match_with_free_spins` | derived from type when absent |
| `active` (`is_active`) | Boolean | delete sets false |
| `validFrom`, `validUntil` | Option[Instant] | live = active and inside both |
| `bonusPercentage` | Long (basis points) | |
| `maxBonusValue`, `minDepositAmount`, `maxConvertibleAmount` | Option[Long] | minor units |
| `depositNumberTarget` | `ANY` \| a number | default `ANY` |
| `claimWindowDays`, `wageringWindowDays`, `freeSpinsExpiryDays` | Option[Int] | |
| `wageringMultiplier` | Long | |
| `maxClaimsPerPlayer` | Option[Int] | 0 is no limit |
| `maxConcurrentGrants` | Option[Int] | ≥ 1; absent is no limit |
| `excludedOfferIds`, `conflictsWith` | Set[OfferId] | |
| `redemptionCodes` | Vector[Code] | as entered; indexed normalised |
| `segmentPredicate`, `vipTierFloor` | Option | dormant (research R4) |
| `role`, `noDepositRequired`, `currency`, `priority` | | `priority` is the wallet's |
| `notificationTemplate`, `fieldMap` | Option | carried, unused here |
| `gameId`, `gameName`, `provider`, `spinCount`, `spinValue`, `spinConfigurations`, `winningsDestination` | free rounds | `spinConfigurations: Map[Currency, Long]` clears the flat pair |
| bet-time rules | `maxStake`, `maxStakeEnforcement`, `contributionDefault`, `contributionOverrides`, `scopeInclude`, `scopeExclude`, `scopeViolationMode`, `fundingOrder`, `winAllocation`, `forfeitOnWithdrawal`, `forfeitOnCancel` | settable by edit only, as the original |
| `createdAt`, `updatedAt` | Instant | |

**Commands**:

| Command | Checks (first failure answered, `OfferRules`) | Event |
|---|---|---|
| `Create(offer, programmeCodes)` | `bonus_amount_required`, `wagering_multiplier_required`, `invalid_payload_type`, `max_concurrent_grants_invalid`, `spin_configurations_invalid`, `currency_required`, `game_id_required`, `spin_count_required`, `spin_value_required`, `max_convertible_amount_required`, `duplicate_code_in_request`, `code_already_in_use` (the endpoint has already run the provider's spin-value and game checks) | `OfferCreated` |
| `Edit(id, patch, programmeCodes)` | `offer_not_found`, `invalid_payload_type`, `max_concurrent_grants_invalid`, `spin_configurations_invalid`, `role_already_assigned`, the code checks when codes change | `OfferEdited` |
| `Deactivate(id)` | `offer_not_found` | `OfferDeactivated` (a repeat writes nothing) |
| `Reorder(ids)` | ids not of the brand are ignored | `OffersReordered` |

**Queries**: `offer(id)`, `list` (by order), `byCode(normalised)`, `byRole(role)`, `liveCodes`.

## `PlayerRewards` (event sourced, id = player)

**State**: `registeredAt`, `checkInProgress: Option[Instant]`, `noDepositBlocked: Boolean`,
`suspectedDuplicate: Boolean`, `tier: Option[String]`, `detectionOn: Boolean`,
`rewards: Map[RewardId, Held(offerId, status, providerFailed)]`, `visible: Map[OfferId, Visibility(source: targeted | code, campaign, at)]`,
`lastDeposit: Option[Deposit(amount, currency, ordinal, paymentRef)]`, `codesEntered: Vector[(code, outcome, at)]`,
`unmatchedRounds: Vector[(roundId, grantRef, winCents, at)]`.

**Commands** (each runs `Evaluator` over the state, then reserves or declines in one event):

| Command | From | Outcome |
|---|---|---|
| `Target(offer, source, campaign)` | target route, code | `OfferMadeVisible` + a reward id `AVAILABLE`; a repeat for the same offer writes nothing |
| `Select(offer)` | player | deposit offer: `RewardSelected` (the `AVAILABLE` reward → `PENDING`); no-deposit: `Claimed(rewardId, decision)` then an award; refusals `offer_not_visible`, a decline reason |
| `Deselect` | player | every `PENDING` → `AVAILABLE` |
| `EnterCode(code, preview)` | player | not preview: `CodeEntered(code, outcome)`, and matched → `Target` with source `player_code` or `affiliate_code` and the tag; a preview answers the terms and records nothing (FR-020) |
| `DepositConfirmed(deposit)` | `MoneyConsumer` | `DepositRecorded`; with a `PENDING` reward: evaluate at `Selection` → `Claimed` / `PendingExpired` / `DeclineRecorded(reason)` |
| `Claim(offer, mode, deposit?, source)` | award route, operator award | `Claimed(rewardId, decision)` or the decline |
| `PlayerFact(fact)` | `PlayersConsumer` | `RegistrationRecorded`, `CheckStarted`, `CheckEnded`, `NoDepositBlocked`, `BlockCleared`, `FlagChanged`, `TierChanged` |
| `RewardChanged(rewardId, status, providerFailed)` | `IndexConsumer` | `RewardIndexed` |
| `RoundUnmatched(round)` | `RoundsConsumer` | `RoundUnmatched` (once per round id) |

`Claimed` carries the `Decision`: amount, wagering requirement, maximum convertible, currency, the
rule snapshot, the source, code and tag. The command's caller then starts `WalletStep(<reward>/award)`
or `FreeRounds(<reward>)`.

## `Reward` (event sourced, id = reward id)

**State**: `playerId`, `offerId`, `offerName`, `offerType`, `status`, `payloadType`, `faceValue`,
`currency`, `priority`, `source`, `code`, `tag`, `snapshot`, clocks (`grantedAt`, `expiresAt`,
`wageringExpiresAt`, `capExpiresAt`, `selectedAt`, `terminatedAt`), `lastDecline`, `revoke(by, mode)`,
and for free rounds `FreeRoundsState(gameId, provider, spinCount, spinValue, destination,
providerStatus, roundsSeen: Set[RoundId], roundsUsed, accumulated, paid)`.

**Transitions** (`Transitions`, the original's table; anything else `INVALID_TRANSITION` naming both;
the same state a no-op):

| from | to |
|---|---|
| `AVAILABLE` | `PENDING`, `EXPIRED`, `ACTIVE` |
| `PENDING` | `ACTIVE`, `AVAILABLE`, `EXPIRED`, `CANCELLED` |
| `ACTIVE` | `PAUSED`, `CONVERTED_PENDING_CAP`, `CLOSED`, `EXPIRED`, `CANCELLED`, `FORFEITED`, `REVOKED` |
| `PAUSED` | `ACTIVE`, `CANCELLED`, `REVOKED` (never reached) |
| `CONVERTED_PENDING_CAP` | `CLOSED`, `FORFEITED`, `REVOKED` |

**Commands → events**:

| Command | Event | Reason / effect |
|---|---|---|
| `Open(initial, decision)` | `Targeted` / `Selected` / `Activated` | initial `AVAILABLE`, `PENDING` or `ACTIVE` |
| `Select`, `Deselect` | `Selected`, `Deselected` | sets `pending-` timer |
| `Activated(walletAnswer)` | `Activated` | sets `claim-` (and `wagering-`) timers |
| `AwardRefused(code)` | `AwardRefused` | stays as it was |
| `Converted` | `Converted` | sets `cap-` |
| `Forfeited(reason)` | `Forfeited` | `bonus_exhausted`, `admin_adjust_emptied` |
| `Closed(reason)` | `Closed` | `cap_expired`, `cap_exhausted`, `real_balance_zero`, `free_spins_finalised` |
| `Expired(reason)` | `Expired` | `claim_expired`, `wagering_window_expired`, `pending_expired`, `free_spins_expired_unused` |
| `Cancelled(reason, clawback)` | `Cancelled` | `player_cancel`, `provider_failed` |
| `Revoked(mode, by, clawback)` | `Revoked` | `forfeit`, `reclaim`, `free_spins_revoked`; reason `Personal` |
| `Switched` | `Switched` | `bonus_active_switched` |
| `ProviderStatus(status)` | `ProviderStatusRecorded` | |
| `RoundSettled(roundId, win)` | `RoundCounted` | once per round id; none when terminal and paid, but `LatePaid` after finalisation |
| `Finalise(at)` | `FreeRoundsFinalised(amount)` | then `Closed` or `Expired` |

## `CodeDocument` (key value, id = slug)

`entries: Map[NormalisedCode, (offerId, tag)]`, `fetchedAt: Option[Instant]`. Set by `codes-<slug>`.

## `WalletStep` (workflow, id = `<reward>/<step>`)

State: `step`, `request`, `attempts`, `answer: Option[WalletAnswer]`. Steps: `call` (retry with
backoff on transport failure or 5xx, at most 10 minutes between tries), `deliver` (to `Reward`).
`start` twice answers the first state. Steps and keys in research R5.

## `FreeRounds` (workflow, id = reward id)

State: `providerStatus`, `polls`, `cancelRequested`. Steps: `known`, `create`, `poll` (pause 30 s,
at most 50), `cancel`. Research R8.

## `RewardsView`

Row per reward from `Reward` events: `rewardId`, `playerId`, `offerId`, `offerName`, `type`,
`status`, `faceValue`, `currency`, `source`, `freeRounds(spinsRemaining, gameId, providerStatus)`,
`grantedAt`, `expiresAt`, `closedAt`. Queries: by player (newest first; closed
only, top 20, for history); by brand with `search` (player id, reward id, offer id, offer name),
`status` (`all` is none), `offerId`, `page`, `pageSize` (1–200, default 25); the oldest `ACTIVE`
free rounds for a player and game with `providerStatus` not failed.

## Consumers

| Consumer | Source | Does |
|---|---|---|
| `PlayersConsumer` | `casino.players` | `player_registered`, the four check families, `player_fraud_flagged` / `_cleared`, `vip_tier_changed` → `PlayerRewards.PlayerFact` |
| `MoneyConsumer` | `money.transactions` | `deposit_confirmed` → `PlayerRewards.DepositConfirmed`; `restricted_funds_converted`, `_forfeited` (reason `loss`, `adjustment`), `_closed` → `Reward` by `awardId`; reasons `forfeit`, `cancel`, `hold` are this service's own commands or payouts', recorded from the step's answer or as `Forfeited` |
| `RoundsConsumer` | `casino.rounds` | `round_settled` with `freeRoundsGrant` → `Reward.RoundSettled`; a reward not known → `PlayerRewards.RoundUnmatched` |
| `IndexConsumer` | `Reward` events | → `PlayerRewards.RewardChanged` |
| `RewardsPublisher` | `Reward` events | → `casino.rewards` (research R11) |
| `CodesPublisher` | `PlayerRewards` events | `CodeEntered` → `bonus_code_entered` |
| `AuditPublisher` | `OfferBook`, `Reward` events | operator actions → `platform.audit` |
