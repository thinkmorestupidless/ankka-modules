# Fields: the contract's fields, the original's source, and the facts here

Written 2026-11-12 from a reading of the original's mappers and handlers and this repository's fact
families; every line cites its source. **Decided on this branch (Trevor, 2026-11-12):** every gap in
§1 is closed by an additive amendment to its producer ([facts.md](facts.md)). Rows marked GAP below
therefore read "after the amendment". Smaller decisions taken in planning are in §6.

Path shorthand:

- **C** = `eitheror-platform/specs/255-casino-public-rest-api/contracts/public-api-v1.openapi.yaml`
- **O** = `eitheror-platform/casino/public-api/src` (mappers, handlers)
- **S** = `eitheror-platform/substrate/casino-service/src/main/scala/io/eitheror/casino`
- **F** = `ankka-modules/contract/src/main/scala/io/eitheror/casino/contract/facts`
- **svc** = `ankka-modules/services`
- Topic table: `ankka-modules/specs/001-brands-and-projects/spec.md:467-482` (FR-011); `partner-api` (now `public-api`) is a listed reader of `money.transactions`, `casino.players`, `casino.sessions`, `casino.rounds`, `casino.withdrawals`, `casino.rewards` (lines 473-479).

Money: every fact amount is minor units (`MinorUnits`/`SignedMinorUnits`, `Long`); the contract's `Money` is a major-unit string with two decimals (C:519-522). The original converts with `centsToMoney` / `majorToMoney` (O/mappers/money.ts:14-43). Here it is always minor → `d.dd`, so one function replaces the original's unit guessing.

Optional contract fields are omitted, never null (`compact`, O/mappers/props.ts:42). A record missing a required field is dropped and logged (O/mappers/props.ts:55-60).

## Player (C:558-579)

Original source: twin in `casino-<brand>` (O/substrate/scopedQuery.ts:65), mapper O/mappers/player.ts:74-103.

| Field | Type / nullability | Original fills it | Fact here | Notes |
|---|---|---|---|---|
| id | uuid, required | twin id, player.ts:76 | `PlayerRegistered.playerId` F/CasinoPlayers.scala:28 | UUID, see Ids |
| brandId | string, required | `scope.brandId` player.ts:92 | none needed: `CASINO_BRAND` (Routes.scala:35-36) | project = brand |
| status | PlayerStatus, required, nullable | `status=='closed'` → CLOSED, else map of `account_status` player.ts:59-61 | `PlayerSuspended` / `PlayerReinstated` / `PlayerSelfExcluded` / `PlayerClosed` / `PlayerReopened` `.newValue` F/CasinoPlayers.scala:56-76 | ACTIVE on registration (original writes `account_status: active` at creation); then last `newValue` wins. See Enums for words with no mapping |
| kycStatus | KycStatus, required, nullable | `kyc_status` player.ts:94 | `PlayerKycVerified` / `PlayerKycRejected` / `PlayerKycPending` `.newValue` F/CasinoPlayers.scala:137-148 | Read `newValue`, not the family: `player_kyc_rejected` is also sent for verified → unverified (svc/kyc/.../facts/Facts.scala:38-39). No fact before the first change: default UNVERIFIED |
| registeredAt | date-time, required | `registered_at` player.ts:77 | `PlayerRegistered.registeredAt` :28 | |
| country | string, optional | `country` player.ts:96 | `PlayerRegistered.country` :29; later `PlayerProfileUpdated.country` :87 | |
| currency | Currency, optional | `currency` player.ts:97 | `PlayerRegistered.currency` :29 | |
| language | string, optional | `language` player.ts:98 | **GAP** | no fact carries it |
| email | string, identifying | `email` player.ts:35 | `PlayerRegistered.email` :33 (`Personal`), then `PlayerEmailChanged.email` :47 | entitlement-gated (player.ts:82-87) |
| firstName | identifying | `firstName`/`first_name` player.ts:36 | `givenName` on `PlayerRegistered` :30 / `PlayerProfileUpdated` :84 | filled from profile `firstName` (svc/players/.../facts/Facts.scala:48, 90) |
| lastName | identifying | player.ts:37 | `familyName` :30 / :85 | |
| dateOfBirth | Date, identifying | player.ts:38, first 10 chars if date-shaped :52 | `dateOfBirth` :31 / :85 | same date check needed |
| phone | identifying | player.ts:39 | `telephone` :31 / :85 | |
| addressLine1 | identifying | player.ts:40 | `addressLine` :32 / :86 | from profile `addressLine1` (Facts.scala:49, 91) |
| city | identifying | player.ts:41 | `city` :32 / :86 | |
| stateProv | identifying | player.ts:42 | **GAP** | neither family has the field; `players` holds it in its profile but does not send it |
| postalCode | identifying | player.ts:43 | `postCode` :32 / :86 | |

`PlayerProfileUpdated` sends every field the player holds, not only the changed ones (Facts.scala:83-92), so the latest fact replaces the profile.

## Session (C:580-592)

Original: `casino-sessions-<brand>` filtered `_event_type = game_session_started` (scopedQuery.ts:67, 86). The contract's Session is the **game session**, not the player (login) session. Mapper O/mappers/session.ts:6-23.

| Field | Type | Original | Fact here | Notes |
|---|---|---|---|---|
| id | uuid, required | twin id session.ts:8 | `GameSessionStarted.sessionId` F/Others.scala:17 | UUID |
| brandId | required | scope | environment | |
| playerId | uuid, required | `player_ref` :9 | `GameSessionStarted.playerId` :17 | |
| gameId | GameCode, optional | `game_id` :17 | `GameSessionStarted.gameId` :17 | must pass `^[A-Za-z0-9._-]{1,64}$` (props.ts:21) |
| startedAt | required | `started_at` :10 | `GameSessionStarted.occurredAt` :18 | |
| endedAt | optional | `ended_at` :19 | `GameSessionEnded.occurredAt` :23 | |
| status | SessionStatus, required, nullable | `status` via SESSION_STATUS :20 | derived: OPEN until a `game_session_ended` for the id, then CLOSED | the facts carry no status word; none is needed |
| closeReason | string, optional | `close_reason` :21 | `GameSessionEnded.closeReason` :23 | words here: `player`, `limit`, `forced`, `logout`, `expired`, `suspended`, `superseded` (Others.scala:20). Free string in the contract |

`casino.sessions` is ephemeral, 90 days (Others.scala:10): the service must keep its own view; a replay older than 90 days loses sessions.

## Round (C:593-608)

Original: `casino-rounds-<brand>` twin, mapper O/mappers/round.ts:8-34.

| Field | Type | Original | Fact here | Notes |
|---|---|---|---|---|
| id | uuid, required | twin id :10 | `RoundSettled.roundId` F/Others.scala:59 | **not a UUID**: `<provider>/<providerRoundId>` (svc/game-provider/.../facts/RoundFacts.scala:54, rounds/Round.scala:87). See Ids |
| brandId | required | scope | environment | |
| playerId | uuid, required | `player_ref` :11 | `RoundSettled.playerId` :59 | |
| sessionId | uuid, optional | `session_ref` :25 | `RoundSettled.sessionId` :60 | empty string when no ticket named the round; a random id matching no session when `players` did not answer the launch (RoundFacts.scala:41-46). Omit when empty |
| gameId | GameCode, required | `game_id` :12 | `RoundSettled.gameId` :60 | `""` when the game code is unknown (RoundFacts.scala:57) → record would be dropped as the original drops it |
| startedAt | date-time, required | `created_at` :13 | **GAP** | `round_settled.occurredAt` is the settlement time (RoundFacts.scala:67). The entity has `openedAt` (rounds/RoundModel.scala:91) and a `RoundOpened` event (:137) that RoundFacts ignores (RoundFacts.scala:16-19) |
| settledAt | optional | `settled_at` :28 | `RoundSettled.occurredAt` :63 | for outcome `cancelled` the original has no settled_at; omit it then |
| stakeTotal | Money, required | `total_stake`, absent → `0.00` :16 | `RoundSettled.stakeCents` :60 | total stake, all funding |
| winTotal | Money, required | `win_amount`, absent → `0.00` :17 | `RoundSettled.winCents` :60 | |
| currency | required | `currency` :14 | `RoundSettled.currency` :59 | |
| status | `open`/`settled`/`cancelled`, optional | `status` if one of the three :6, :32 | `RoundSettled.outcome` :61 | `win`/`loss`/`credit` → `settled`; `cancelled` → `cancelled`. **`open` is a GAP**: a round has no fact until it settles or cancels |

A round can produce more than one `round_settled` (one per settlement or cancellation, RoundFacts.scala:10-12; a late credit or payout re-settles): fold by `roundId`, last wins.

## Bet (C:609-623)

Original: `casino-bet-records-<brand>` twin, mapper O/mappers/bet.ts:7-28.

| Field | Type | Original | Fact here | Notes |
|---|---|---|---|---|
| id | uuid, required | twin id :9 | `BetPlaced.transactionId` F/MoneyTransactions.scala:54 | UUID (name-based). Not the original's bet-record id |
| brandId | required | scope | environment | |
| playerId | uuid, required | `player_ref` :10 | `BetPlaced.playerId` :54 | |
| roundId | uuid, optional | `round_ref` :20 | **GAP (partial)** | `BetPlaced.roundId` is the wallet's round key, which is the provider's transaction key, not the round (svc/game-provider/.../rounds/MoneyMoves.scala:60 passes `job.key` twice). The round is only reachable by join: `RoundSettled.walletTransactionIds` (Others.scala:62) contains the bet's `transactionId`, and only once the round settles |
| sessionId | uuid, optional | `session_ref` :21 | **GAP (partial)** | same join, via the round's `sessionId`. The wallet's `PlaceBet` already holds `sessionId` (svc/wallet/.../entity/Commands.scala:61) but the fact does not carry it |
| amount | Money, required | `amount` (major) :11 | `BetPlaced.amountCents` :55 | |
| currency | required | `currency` :12 | `BetPlaced.currency` :54 | |
| fundedFrom | WalletType, required, nullable | `funded_from` via WALLET_TYPE :24 | `BetPlaced.walletType` :55 | words `real`/`bonus`/`split`/`""` (svc/wallet/.../funding/Resolver.scala:47-52). `""` → null |
| status | string, optional | `status` :25 | derived | original words: `open`, then `settled` / `voided` / `loss`. Here: `open` until `WinCredited` → `settled`, `BetRolledBack` → `voided`, `RoundSettledAsLoss` → `loss` for the same wallet `roundId` (svc/wallet/.../views/Views.scala:132, 195-199) |
| placedAt | required | `created_at` :13 | `BetPlaced.occurredAt` :56 | |

## Transaction (C:624-640)

Original: `transaction` twin filtered by `brand_ref`, mapper O/mappers/transaction.ts:6-31. One contract record per wallet movement; here one per `money.transactions` fact, with the exceptions listed under Enums.

| Field | Type | Original | Fact here | Notes |
|---|---|---|---|---|
| id | uuid, required | twin id :8 | `transactionId` on every family (F/MoneyTransactions.scala:37) | UUID, but **not unique per fact**: see Ids |
| brandId | required | `brand_ref` :9 | environment | |
| playerId | uuid, required | `player_ref` :10 | `playerId` :35 | `account_adjusted` has no player (:206-219): never a Transaction |
| type | TransactionType, required, nullable | `type` via TRANSACTION_TYPE :20 | the family | table under Enums |
| amount | Money, required | `amount_str`, else integer cents, else decimal (money.ts:38-43) | `amountCents`; `AdminAdjusted.amount` is signed (:102) | `RoundSettledAsLoss` has no amount (:72-73): `0.00`, as the wallet's own view writes it (Views.scala:141) |
| currency | required | `currency` :12 | `currency` :36 | |
| walletType | WalletType, optional, nullable | `wallet_type`; absent → MAIN :24 | `BetPlaced.walletType` :55, `WinCredited.walletType` :61; `AdminAdjusted.target` :103; `ProviderCredited.landedOn` :131 | other families: fixed per family, see Enums |
| status | string, optional | `status` :25 | derived per family | no status word on any fact; wallet view words in Views.scala:129-191 |
| createdAt | required | `created_at` :13 | `occurredAt` :40 | |
| roundId | uuid, optional | `round_id` :27 | join, as for Bet | `BetPlaced`/`WinCredited`/`BetRolledBack`/`RoundSettledAsLoss.roundId` is the wallet round key, not the round; not a UUID either |
| gameId | GameCode, optional | `game_id` :28 | `BetPlaced.gameId` :54, `WinCredited.gameId` :60 | absent on rollback and loss; fill from the round by join |
| paymentRef | string, optional, deposits only | `payment_ref` :29 | `DepositConfirmed.paymentReference` :49 | |

## BonusAward (C:641-658)

Original: `bonus-claims` twin filtered by `brand_id` (scopedQuery.ts:75, 88), mapper O/mappers/bonusAward.ts:6-32. It is the reward (claim), which here is `rewards`' reward; the wallet's restricted-funds record shares its id.

| Field | Type | Original | Fact here | Notes |
|---|---|---|---|---|
| id | uuid, required | twin id :8 | `BonusGranted.bonusId` / `FreeSpinsGranted.bonusId` F/Others.scala:146, 173 | UUID; equals the wallet's `awardId` (svc/rewards/.../steps/WalletStep.scala:61-64; svc/wallet/.../entity/WalletEntity.scala:420) |
| brandId | required | `brand_id` :9 | environment | |
| playerId | uuid, required | `player_ref` :10 | `playerId` | |
| offerId | string, required | `offer_id` :11 | `offerId` on every grant family :146 | manual free rounds: `manual:<uuid>` (svc/rewards/.../api/FreeRoundsGrants.scala:61) |
| name | optional | `offer_name` :22 | `bonusName` :146 | |
| type | optional | `type` :23 | `offerType` :146 | |
| value | Money, required | `face_value` :12 | `BonusGranted.amountCents` :147 | **GAP for free spins**: `FreeSpinsGranted` (:173-176) has `rounds` and `gameIds`, no amount and no currency. Winnings arrive later as `FreeSpinsSettled.winCents` :180 |
| currency | required | `currency` :13 | `BonusGranted.currency` :147 | **GAP for free spins** until `FreeSpinsSettled.currency` |
| wageringRequirement | Money, optional | `wagering_requirement` :26 | `RestrictedFundsAwarded.wageringRequirement` F/MoneyTransactions.scala:77, joined on `awardId` = `bonusId`; or `RecordProgress.requirementCents` :28 | cross-topic join |
| wageredAmount | Money, optional | `wagered_amount` :27 | `RecordProgress.progressCents` :27 in `records` on `bet_placed`, `win_credited`, `bet_rolled_back`, `restricted_funds_*`, `admin_adjusted`, `hold_placed`, `provider_credited` | last `records` entry for the `awardId` wins |
| status | BonusStatus, required, nullable | `status` via BONUS_STATUS :28 | folded from `casino.rewards` families and `RecordProgress.status` | table under Enums |
| grantedAt | required | `granted_at` :14 | `BonusGranted.occurredAt` :148 / `FreeSpinsGranted.occurredAt` :175 | the fact is published at activation (svc/rewards/.../facts/Facts.scala:47-54), so this is the activation time; a reward still AVAILABLE or PENDING has no fact and is invisible |
| expiresAt | optional | `expires_at` :30 | **GAP** | no fact carries the expiry; `rewards` passes it to the wallet (svc/rewards/.../clients/Wallet.scala:39) |

## Game (C:659-682)

Original: `casino-catalog` twins plus the brand's config twin (O/games/catalogue.ts:47-54, 68-72), mapper O/mappers/game.ts:58-103. **No topic carries any of it** (see Games catalogue). Source here is `game-provider`'s catalogue (`GameFields`, svc/game-provider/.../catalogue/Catalogue.scala:12-), as `CatalogueReads.toJson` writes it (CatalogueReads.scala:128-139).

| Field | Type | Original (game.ts) | Catalogue JSON here | Notes |
|---|---|---|---|---|
| id | GameCode, required | `game_code` :60, :82 | `gameId` | a code may exist at two providers here (`Ambiguous`, CatalogueReads.scala:82-85) |
| name | required | `name` :63 | `name` | |
| category | required | `category` :64 | `category` | |
| developerId | required | `developer` :85 | `developerId` | |
| developerName | required | `developer_name` ?? `developer` :86 | `developerName` | |
| funMode | boolean, required | `fun_mode == 'true'` :87 | `funMode` | |
| bonusTypes / features / themes | string[], required | JSON-string lists :69-71 | same names | |
| tags | string[], required | lower-cased, de-duplicated, sorted union :72 | `tags` (the catalogue's own) | recompute as the original does rather than trust `tags` |
| restrictedTerritories / prohibitedTerritories | string[], required | :92-93 | same names | |
| thumbnailUrl | uri, optional | :94 | `thumbnailUrl` (null when empty) | null → omit |
| rtp, hitRatio, maxPayoutCoeff | string, optional | :95, :97, :98 | same names, null when absent | |
| volatility | integer, optional | :96 | `volatility` | |
| releaseDate / deprecationDate | Date, optional | strict ISO date :99-100 | same names | |

Availability in the original: not removed from feed, category and developer not disabled, override else provider's flag, and released by today (catalogue.ts:47-54, 71; game.ts:45-48). Here `Enablement.listed` is the same decision (Catalogue.scala:130-140) but the release-date gate is not in `browse`, which gates on deprecation instead (CatalogueReads.scala:66). The public API must apply the release gate itself.

## Statistics (C:683-789)

Original: precomputed twins written by `S/GameStatsRecompute.scala` and `S/PlayerStatsRecompute.scala`; mappers only parse them (O/mappers/gameStats.ts:67-95, O/mappers/playerStats.ts:80-130). Here the service computes them from facts.

| Schema.field | Type | Original computes | Facts here | Notes |
|---|---|---|---|---|
| GameStats.gameId | GameCode | `game_code` gameStats.ts:69 | `RoundSettled.gameId`, `GameSessionStarted.gameId` | zeros for a listed game with no activity (gameStats.ts:33-35) |
| GameStats.brandId | string | scope :91 | environment | |
| GameStats.computedAt | optional | `computed_at` :92 | the service's own run time | |
| StatsWindow.rounds | integer | rounds with `status != cancelled`, by `created_at` (S/GameStatsRecompute.scala:209) | `RoundSettled` with `outcome != cancelled` | **bucketed by settlement time here** unless round start is supplied (GAP startedAt); open rounds are not counted (GAP) |
| StatsWindow.sessions | integer | `game_session_started` by `started_at` (:222-226) | `GameSessionStarted.occurredAt` | exact |
| StatsWindow.uniquePlayers | integer | distinct players among counted rounds | `RoundSettled.playerId` | |
| CurrencyAmounts.stake / win / ggr | Money | `Sum(real_stake)`, `Sum(real_win)` over non-cancelled rounds with empty `free_spins_grant_ref` (:216-219); ggr = stake − win | see Statistics section | never summed across currencies |
| PlayerStats.playerId | uuid | path | `playerId` | 404 unless the player exists (O/rest/handlers/playerStats.ts:127-128) |
| PlayerStats.snapshot | optional | pointer twin (playerStats.ts:130-132) | the service's own snapshot id (random UUID) and time | |
| PlayerStats.lastActiveAt | date-time, nullable | max of last round `created_at` and last session `started_at` in 30 days (S/PlayerStatsRecompute.scala:288, 308) | max of `GameSessionStarted.occurredAt` and round time | round time is settlement here (GAP startedAt) |
| PlayerWindow.rounds / sessions | integer | as StatsWindow, per player (:280-308) | same facts keyed by player | |
| PlayerWindow.games | integer | distinct `game_id` among counted rounds | `RoundSettled.gameId` | |
| PlayerAmounts.stake / win / ggr | Money | as CurrencyAmounts, per player (:296-298) | see Statistics section | |
| PlayerAmounts.deposits | CountAmount | `transaction` twins `type=deposit`, `status=settled`, by `created_at` (:312-318) | `DepositConfirmed` count and `amountCents` by `occurredAt` | |
| PlayerAmounts.withdrawals | CountAmount | `withdrawal-request` twins `status=COMPLETED`, by `resolved_at` (:322-327) | `WithdrawalCompleted` count and `amountCents` by `occurredAt` F/Others.scala:101-104 | |
| PlayerRankingEntry.rank | integer ≥ 1 | precomputed `rk_*` property (playerStats.ts:125-127) | computed by the service; ties by player id (C:783) | |
| PlayerRankingEntry.status | PlayerStatus | `account_status` copied at snapshot time (S/PlayerStatsRecompute.scala:183-190, 461, 500) | player status fold at snapshot time | default `active` when not in a status list (:461) |
| PlayerRankingEntry.rounds / sessions / games / amounts | | the window of the requested period (playerStats.ts:129) | as PlayerWindow | |

## 1. Gaps and the smallest additive amendment

| # | Contract field | Why no fact supplies it | Smallest additive amendment | Producer |
|---|---|---|---|---|
| G1 | Player.language | not on `player_registered` or `player_profile_updated` | `language: Option[String] = None` on `PlayerRegistered` and `PlayerProfileUpdated` | players |
| G2 | Player.stateProv | no field; players holds `stateProv` in the profile | `stateProvince: Option[Personal[String]] = None` on both families (send it in `Facts.scala:47-50, 88-92`) | players |
| G3 | Round.startedAt, Round.status `open`, and every stats bucket "by creation time" | only `round_settled` exists | a `round_opened` family on `casino.rounds` (playerId, currency, roundId, provider, providerRoundId, gameId, sessionId, freeRoundsGrant, occurredAt) from the entity's existing `RoundOpened` event; or, smaller but without open rounds, `openedAt: Option[Instant] = None` on `RoundSettled` (state already has it, RoundModel.scala:91) | game-provider |
| G4 | Bet.roundId, Bet.sessionId, Transaction.roundId before settlement | `bet_placed.roundId` is the provider's transaction key | `sessionId: Option[String] = None` on `BetPlaced` (the wallet already receives it); and for the round either pass the round's id to the wallet as a new optional `gameRoundId` on `BetPlaced`/`WinCredited`/`BetRolledBack`/`RoundSettledAsLoss`, or list the wallet transaction ids on `round_opened` and each later debit | wallet (field), game-provider (value) |
| G5 | BonusAward.expiresAt | not on any reward or wallet fact | `expiresAt: Option[Instant] = None` on `BonusGranted` and `FreeSpinsGranted` | rewards |
| G6 | BonusAward.value and currency for free spins | `FreeSpinsGranted` has neither | `amountCents: Option[MinorUnits] = None`, `currency: Option[Currency] = None` on `FreeSpinsGranted` (spin value × rounds, as the original's `face_value`); check what the original writes for a free-spins claim before choosing | rewards |
| G7 | Real-money stake and win per round (stats FR-020b) | `round_settled` has totals only | not strictly a gap (join, section 5); to avoid the join, `realStakeCents`, `realWinCents: Option[MinorUnits] = None` on `RoundSettled` | game-provider (values from the wallet's answers) |
| G8 | Game (whole schema) | no catalogue topic and no service-class catalogue route | a service-class read on `game-provider` (section 4); no fact needed | game-provider |
| G9 | BonusAward before activation | AVAILABLE and PENDING rewards publish nothing | none proposed: the original's `bonus-claims` twin may list them; confirm against a recording | rewards |

Not gaps, but derived rather than carried: Session.status, Bet.status, Transaction.status, Transaction.type, Round.status `settled`/`cancelled`, BonusAward.status.

## 2. Enum mappings

### PlayerStatus (O/mappers/enums.ts:11-16, player.ts:59-61)

| Original word | Contract | Word the facts carry (`newValue`) |
|---|---|---|
| `active` | ACTIVE | `active` on `player_reinstated`, `player_reopened` (svc/players/.../facts/Facts.scala:56, 61, 65) |
| `suspended` | SUSPENDED | `suspended` on `player_suspended` (:54) |
| `self_excluded` | SELF_EXCLUDED | `self_excluded` on `player_self_excluded` (:58); lifted by `player_reinstated` with reason `exclusion_lifted` (:61) |
| `closed` (the twin's `status`) | CLOSED | `closed` on `player_closed` (:63) |

**No mapping** (svc/players/.../player/Model.scala:8-18): `pending_email_verification`, `blocked`, `erasure_pending`, `erased`. None is the `newValue` of any fact, but `previousValue` can be one of them, and no fact says an email was verified, a player blocked, or erased (Facts.scala:43-81 publishes nothing for those events). So the public API reads a pending or blocked player as ACTIVE. The original writes `account_status: active` at creation, so pending reads the same there; `blocked` and the erasure states need a decision (erasure should remove the record, by ankka's erasure rather than a fact).

### KycStatus (enums.ts:18-23)

`unverified` → UNVERIFIED, `pending` → PENDING, `verified` → VERIFIED, `rejected` → REJECTED. The facts carry exactly these four as `newValue` (svc/kyc/.../record/Model.scala:33-37; facts/Facts.scala:32-40). A change to `unverified` from anything but `verified` publishes nothing (:40).

### SessionStatus (enums.ts:49-54)

Original `active` → OPEN; `completed`, `expired`, `force_closed` → CLOSED. Facts: no status word; `game_session_ended` present → CLOSED.

### Round.status

Original: `open`, `settled`, `cancelled` passed through (round.ts:6). Facts: `RoundSettled.Outcome` `win`, `loss`, `credit` → `settled`; `cancelled` → `cancelled` (F/Others.scala:69-74; decided in svc/game-provider/.../rounds/Round.scala:107-119). `credit` is a standalone credit or payout. `open`: GAP G3.

### TransactionType (enums.ts:25-40) and WalletType (enums.ts:43-47)

Original twin words, written by `starter-packs/wallet/handlers-go/internal/apply/props.go` (e.g. `admin_adjustment` :911, `game_provider_credit` :1027, `bonus_cap_void` :1191, `withdrawal_return` :1371, `bonus_conversion` :338).

| Fact family (F/MoneyTransactions.scala) | Original type word | Contract type | walletType | Status word (wallet view, Views.scala) |
|---|---|---|---|---|
| `deposit_confirmed` :47 | `deposit` | DEPOSIT | MAIN | `completed` (:129); the original's stats filter on `settled` |
| `bet_placed` :53 | `bet` | BET | `walletType` word | `open`, then settled/voided/loss |
| `win_credited` :59 | `win` | WIN | `walletType` word | `settled` |
| `bet_rolled_back` :65 | `rollback` | ROLLBACK | MAIN (:138) | `completed` |
| `round_settled_as_loss` :71 | `loss` | LOSS | view writes `""` (:141) → null; original default for an absent `wallet_type` is MAIN (transaction.ts:24) | `settled` |
| `restricted_funds_awarded` :75 | `bonus_credit` | BONUS | BONUS | `completed` |
| `restricted_funds_converted` :81 | `bonus_conversion` | BONUS | MAIN (original writes `wallet_type: real`, place-bet.test.tl:232) | `settled` |
| `restricted_funds_forfeited` :87 | `bonus_credit` voided (reason `loss`, `adjustment`, forfeit) or `bonus_cancel` (reason `cancel`, `hold`) (Views.scala:145-159; Facts.scala:101-123) | BONUS | BONUS | `voided` / `completed` |
| `restricted_funds_closed` :94 | none: no row in the wallet's view | **no mapping**; no amount on the fact. Recommend: not a Transaction | | |
| `admin_adjusted` :101 | `admin_adjustment` | ADMIN_ADJUSTMENT | `target` `real` → MAIN, `restricted` → BONUS (:166-167) | `completed` |
| `hold_placed` :107 | `withdrawal`; plus `bonus_cap_void` for `voidedCents > 0` | WITHDRAWAL; BONUS for the void row | MAIN (both, :154-155) | `pending` / `completed` |
| `hold_released` :113 | `withdrawal_return` | WITHDRAWAL | MAIN | released-as word |
| `withdrawal_reversed` :123 | `withdrawal_reversal` | WITHDRAWAL | MAIN | `completed` |
| `provider_credited` :129 | `game_provider_credit` | GAME_PROVIDER_CREDIT | `landedOn` `real` → MAIN, `restricted` → BONUS (:170-171) | `completed` |
| `reservation_placed`, `reservation_released`, `settlement_debit`, `settlement_credit`, `resolution_payment`, `account_debit`, `account_credit` :143-200 | none (spec 022, predictions) | **no mapping** → `type: null` if listed (FR-009 "never coerced", enums.ts:1-3) or excluded | MAIN | `completed` |
| `account_adjusted` :216 | none | not a player's transaction; exclude | | |

Flags:

- The wallet's own view names the admin type `admin_adjust` (Views.scala:65) where the original's twin and the contract map use `admin_adjustment` (props.go:911, enums.ts:38). Map from the fact family, not from that word.
- There is no fact for a confirmed withdrawal on `money.transactions` (`HoldConfirmed` publishes nothing, Facts.scala:191-193); the WITHDRAWAL transaction stays `pending` unless `casino.withdrawals` `withdrawal_completed` is joined on `withdrawalRequestId` = `payoutId`.

**Funding (WalletType)**: `real` → MAIN, `bonus` → BONUS, `split` → SPLIT. The word is decided by the wallet from the legs of the movement: real leg and record legs → `split`; record legs only → `bonus`; real leg only → `real`; neither → `""` (svc/wallet/.../funding/Resolver.scala:47-52). It is on `BetPlaced.walletType` and `WinCredited.walletType` only. `""` has no mapping → null.

### BonusStatus (enums.ts:56-68)

Original words: `active`/`ACTIVE`, `wagering`/`WAGERING`, `completed`, `expired`, `CANCELLED`, `forfeited`/`FORFEITED`, `CONVERTED_PENDING_CAP`, `CLOSED`.

| Fact | Contract status |
|---|---|
| `bonus_granted`, `free_spins_granted` | ACTIVE |
| `RecordProgress.status` `ACTIVE` (svc/wallet/.../funding/Types.scala:13-17) | ACTIVE (WAGERING once `progressCents > 0` only if the original's twin did so; no fact carries the word `wagering`) |
| `RecordProgress.status` `CONVERTED_PENDING_CAP` | CONVERTED_PENDING_CAP |
| `bonus_completed` (conversion, or close after it; Facts.scala:55-56), `RecordProgress.status` `CLOSED`, `restricted_funds_closed` | COMPLETED vs CLOSED is ambiguous: one family covers both. Use `RecordProgress.status` `CLOSED` → CLOSED, else COMPLETED |
| `bonus_forfeited`, `RecordProgress.status` `FORFEITED` | FORFEITED |
| `bonus_cancelled` (cancel and revoke, reason on the fact; Facts.scala:60-65) | CANCELLED |
| `grant_expired` | EXPIRED |

**No mapping**: `rewards`' own statuses `AVAILABLE`, `PENDING`, `PAUSED`, `REVOKED` (svc/rewards/.../reward/Transitions.scala:8-18). The first three never reach a fact; a revoke arrives as `bonus_cancelled` → CANCELLED. `free_spins_settled` is not a status change.

## 3. Ids

| Record | Id here | Minted | UUID? |
|---|---|---|---|
| Player | `playerId` | `UUID.randomUUID()` at registration (svc/players/.../api/RegistrationRoutes.scala:45; `Begin.playerId`, registration/RegistrationWorkflow.scala:92) | yes |
| Session | `GameSessionStarted.sessionId` | `UUID.randomUUID()` (svc/players/.../api/GameSessionRoutes.scala:54) | yes |
| (player session, not the contract's) | `PlayerSessionStarted.sessionId` | `UUID.randomUUID()` (api/SessionRoutes.scala:107) | yes; carried as `GameSessionStarted.playerSessionId` |
| Round | `RoundSettled.roundId` = `<provider>/<providerRoundId>` | `Round.entityId` (svc/game-provider/.../rounds/Round.scala:87) | **no**. Publish `UUID.nameUUIDFromBytes(roundId)` and key the view by it; `providerRoundId` alone may or may not be a UUID (ST8's) |
| Bet | `BetPlaced.transactionId` | `UUID.nameUUIDFromBytes("<walletId>/<key>")` (svc/wallet/.../Ids.scala:8-9) | yes (version 3; `format: uuid` accepts it) |
| Transaction | `transactionId` | same | yes, but shared: `restricted_funds_converted` reuses the bet's id (Facts.scala:83), `restricted_funds_forfeited`/`_closed` reuse the causing movement's id, and the wallet's own view disambiguates with `<txId>/<recordId>` and `<txId>/void` (Views.scala:147, 155, 158), which are not UUIDs. Derive a name-based UUID from `<transactionId>/<family>/<awardId>` for the secondary rows |
| BonusAward | `bonusId` | `UUID.randomUUID()` (svc/rewards/.../player/PlayerRewards.scala:230); same value as the wallet's `awardId` | yes |
| Game | game code | provider's | `GameCode` pattern, not a UUID (C:518) |
| Snapshot | the service's | mint `UUID.randomUUID()` per computation | yes |

A Bet and its BET Transaction share one id here; in the original they are two twins with different ids. Ids of migrated Spinvibe records are a spec 019 question.

## 4. Games catalogue

- Lives in `game-provider`: the `Catalogue` view and `BrandCatalogueConfiguration` entity (svc/game-provider/.../catalogue/Catalogue.scala, CatalogueReads.scala:38-53). Spec 003 FR-022 (listing rule), FR-023 (player catalogue), FR-024 (operator catalogue): `ankka-modules/specs/003-game-provider/spec.md:591-605`.
- **No topic carries catalogue facts.** The only catalogue data on a topic is `RoundSettled.gameName` and `gameType` (F/Others.scala:63).
- Routes that return games with details (svc/game-provider/.../api/Endpoints.scala):
  - `GET /v1/casino/catalog/games` and `GET /v1/casino/catalog/games/{gameCode}` under `withAcl(Routes.player(ctx.brand, ctx.settings.authenticate()))` (:201-223). Full details (`CatalogueReads.toJson`), listed games only, but **player class**: a sibling service has no player token.
  - `GET /v1/casino/brands/{brand}/game-catalog` under `withAcl(Routes.operator(brand, ctx.settings.authenticate()))` (:62, :90-95). Every game with enablement layers, few details (`operatorJson`, CatalogueReads.scala:141-149); operator class.
  - `GET /v1/casino/brands/{brand}/game-provider/<provider>/games/{gameCode}` under `withAcl(Routes.serviceOf("rewards"))` (:156-165). The only service-class game read: returns `gameCode` and `bonusTypes` only, one game, and admits `rewards` alone (`Routes.serviceOf`, contract/.../access/Routes.scala:269-270).
- So **no in-project route serves the available games with their details to a sibling service**. Needed: a service-class list on the `/v1/casino/brands` prefix, e.g. `GET /{brand}/game-provider/games` under `Routes.service()` (Routes.scala:256-263, `Callers.anyInProject`) or `Routes.serviceOf("public-api")`, returning `CatalogueReads.toJson` for `Enablement.listed` games plus `removedFromFeed`/release date so the caller applies the original's release gate. ankka 0.13 limits a route to two path parameters and one endpoint per prefix (repo CLAUDE.md), so it belongs in `BrandsEndpoint`.
- The original caches the catalogue 5 minutes and the config 1 minute (catalogue.ts:9-10); FR-019 of 255 allows five minutes.

## 5. Statistics inputs

| Need | Fact | Notes |
|---|---|---|
| Real-money stake | `BetPlaced.depositPartCents + convertedPartCents` (F/MoneyTransactions.scala:56): the deposit-origin and converted-origin parts of the real leg (:12-15) | equals the real leg; a `bonus` bet has both zero, a `split` bet only its real part. Optional in the schema, always written since spec 025 (:15). FR-020b of 255 counts real funds whatever their origin, so sum the two |
| Real-money win | `WinCredited.depositPartCents + convertedPartCents` (:62) | same rule |
| Bonus-funded play (excluded) | `BetPlaced.walletType == "bonus"`, or the remainder of `amountCents` on `split` | |
| Attribution to a game and window | bets carry `gameId`; wins carry `gameId`; the round is by join on `RoundSettled.walletTransactionIds` | the original sums the round twin's `real_stake`/`real_win` (S/GameStatsRecompute.scala:219), so a rolled-back or cancelled round's money is excluded by excluding the round. Summing bets directly would count them: do the join, or take amendment G7 |
| Free-spins rounds | `RoundSettled.freeRoundsGrant.isDefined` (F/Others.scala:62) | count in rounds, sessions, players; contribute no money (S/GameStatsRecompute.scala:33-35, :216). Their wallet transaction ids are empty (Others.scala:52) |
| Cancelled rounds | `RoundSettled.outcome == "cancelled"` (F/Others.scala:72) | excluded from every figure (255 FR-020a). A round settled and later cancelled yields two facts: last wins |
| Rounds by creation time | GAP G3 | settlement time is the only time on the fact |
| Game sessions | `GameSessionStarted` by `occurredAt` (F/Others.scala:16-18) | free-spins sessions included |
| Settled deposits | `DepositConfirmed` (F/MoneyTransactions.scala:47-51): count and `amountCents`, by `occurredAt`, per `currency` | |
| Completed withdrawals | `WithdrawalCompleted` on `casino.withdrawals` (F/Others.scala:101-104): count and `amountCents`, by `occurredAt` | the original buckets by `resolved_at` (S/PlayerStatsRecompute.scala:325-326). A later `withdrawal_reversed` (:116) does not un-complete it in the original's query (status would no longer be COMPLETED there): decide whether a reversed payout leaves the count |
| Player status at snapshot | the status fold of section 2 | |
| Test accounts | `PlayerRegistered.testAccount`, `PlayerTestAccountChanged` (F/CasinoPlayers.scala:33, 37-39) | the original's statistics do not exclude them; `reporting` does (spec 015 R11) |

## 6. Decisions taken in planning

- **Round id**: the view keys a round by `UUID.nameUUIDFromBytes("round/" + roundId)`, a version 3
  UUID that `format: uuid` accepts. The provider's id stays internal. A bet's and a transaction's
  `roundId` is the same derivation from G4's `gameRoundId`.
- **Secondary transaction rows** (a forfeit's void, a converted record, a hold's bonus-cap void)
  are keyed `UUID.nameUUIDFromBytes("<transactionId>/<family>/<awardId>")`. The primary row keeps the
  wallet's `transactionId`.
- **A bet and its BET transaction share one id** here. In the original they were two twins. Both
  are served, at `/v1/bets/{id}` and `/v1/transactions/{id}`. This is listed under the spec's
  *Changes*.
- **Families with no contract type** (`restricted_funds_closed`, spec 022's seven, `account_adjusted`)
  are not transactions in the public API. They are excluded rather than served with a null type,
  because they are not the casino's movements (`account_*`, predictions) or carry no amount.
- **Player statuses with no fact** (`pending_email_verification`, `blocked`): the player reads as
  their last announced status, ACTIVE from registration, as in the original. An erased player is
  erased by ankka 042, not by a status.
- **A reversed withdrawal** leaves the completed-withdrawals count, as the original's query on
  `status = COMPLETED` does.
- **Test accounts** are counted in statistics, as the original counts them.
- **WAGERING**: no fact carries it. A bonus award reads ACTIVE until another status, as the facts
  allow; recorded under *Changes*.
- **A WITHDRAWAL transaction's status** is completed by `withdrawal_completed` on `casino.withdrawals`,
  joined on `withdrawalRequestId` = `payoutId`.
- **Real-money stake and win** come from G7's `realStakeCents` and `realWinCents` on `round_settled`,
  so a rolled-back or cancelled round's money is excluded with the round, as the original's sum over
  round twins does.
- **Bonus awards before activation** (AVAILABLE, PENDING): not served (G9). The original's
  `bonus-claims` twin is checked by recording 1; if it lists them, a follow-up amends `rewards`.
