# Data model: public-api

Field sources are in [contracts/fields.md](contracts/fields.md); the amended facts in
[contracts/facts.md](contracts/facts.md). Money is `Long` minor units everywhere below. Times are
`Instant`. `Personal[A]` is ankka 042's, under `player/<id>`.

## Keys

### `AccessKey` (event sourced, keyed by `keyId`)

| Field | Type | Rule |
|---|---|---|
| `keyId` | 16 lower-case base32 characters | the entity id; minted by `RawKeys` |
| `name` | String | 1–100 characters; not unique |
| `digest` | Option[String] | hex SHA-256 of the whole key; `None` once deleted |
| `enabled` | Boolean | `true` at creation |
| `deleted` | Boolean | final |
| `expiresAt` | Option[Instant] | in the future when set |
| `identifyingFields` | Set[IdentifyingField] | the contract's nine names; empty by default |
| `requestsPerMinute` | Int | 1–6,000; 600 by default |
| `createdBy`, `createdAt` | String, Instant | the operator's subject |

Events: `KeyCreated(all of the above but deleted)`, `KeyChanged(name?, expiresAt?, clearExpiry,
identifyingFields?, requestsPerMinute?, by, at)`, `KeyDisabled(by, at)`, `KeyEnabled(by, at)`,
`KeyDeleted(by, at)`. None carries the key; only `KeyCreated` carries the digest. A command on a
deleted key is refused `key_deleted`, except `delete` and `disable`, which are idempotent.

Query `admit(digest, now)`:

```text
empty or deleted or digest ≠ stored (constant time)  → Refused(key_invalid)
expiresAt ≤ now                                       → Refused(key_expired)
!enabled                                              → Refused(key_disabled)
otherwise                                             → Admitted(identifyingFields, requestsPerMinute)
```

The order matters: an expired key is reported as expired whether or not it is disabled, and a wrong
secret never learns either.

### `KeyWindow` (key value, keyed by `keyId`)

`{windowStart: Instant, count: Int, lastUsedAt: Option[Instant]}`. `take(now, limit)`: a new window
when `now ≥ windowStart + 60 s`; `count < limit` → `Allowed` (count + 1, lastUsedAt = now); else
`Over(max(1, ceil((windowStart + 60 s − now) / 1 s)))`.

### `KeyLog` (event sourced, keyed by `keyId`; snapshots every 500 events)

One event per request that named a key: `Served(requestId, operation, status, outcome, durationMs,
players: Vector[(playerId, Vector[fieldName])], at)`. `operation` is `GET <template> <name>=<id>,…`
(original `router.ts:154-155`). The state is only the count since the snapshot.

### Views

- `AccessKeys` over `AccessKey`: one row per key, the `Key` shape in contracts/routes.md, without
  `lastUsedAt`.
- `KeyUse` over `KeyWindow`: `{keyId, lastUsedAt}`.

## Records (key value entities, one keyed view each)

Each record holds `applied: Vector[FactId]` (the last 64) and ignores a fact already in it.

### `PlayerRecord` (by `playerId`) → `Players`

`id, registeredAt, status, kycStatus, country?, currency?, language?, testAccount, email?,
firstName?, lastName?, dateOfBirth?, phone?, addressLine1?, city?, stateProv?, postalCode?`.
The identifying fields are `Personal[String]`.

| Fact | Change |
|---|---|
| `player_registered` | creates; status `active`, kyc `unverified`; profile fields |
| `player_email_changed` | email |
| `player_profile_updated` | replaces the profile (the fact carries every field) |
| `player_suspended` / `_reinstated` / `_self_excluded` / `_closed` / `_reopened` | status = `newValue` |
| `player_kyc_verified` / `_rejected` / `_pending` | kycStatus = `newValue` |

A fact for an unknown player before its `player_registered` creates the record with what it carries
and no `registeredAt`. Such a record is not listed until registration arrives, because the contract
requires `registeredAt`.

### `SessionRecord` (by `sessionId`) → `Sessions`

`id, playerId, gameId?, startedAt, endedAt?, closeReason?` from `game_session_started` and
`game_session_ended`. Status is derived when served: OPEN without `endedAt`, CLOSED with it.

### `RoundRecord` (by `uuid3("round/" + roundId)`) → `Rounds`

`id, roundKey, playerId, sessionId?, gameId, currency, startedAt, settledAt?, stakeCents, winCents,
realStakeCents, realWinCents, freeRoundsGrant?, status (open | settled | cancelled), walletTxIds`.
`round_opened` creates it as `open`. `round_settled` settles it: outcome `cancelled` is `cancelled`,
otherwise `settled`; the last fact wins. `startedAt` is `round_opened.occurredAt`, else
`round_settled.openedAt`, else `round_settled.occurredAt`.

### `BetRecord` (by `transactionId` of `bet_placed`) → `Bets`

`id, playerId, roundId?, sessionId?, amountCents, currency, fundedFrom, status, placedAt`. `roundId`
is `uuid3("round/" + gameRoundId)`. Status: `open`; `settled` on a `win_credited` for the same
wallet round key; `voided` on `bet_rolled_back`; `loss` on `round_settled_as_loss` (wallet
`views/Views.scala:132, 195-199`). The bet's later facts find it through `BetsByRoundKey`, a key
value entity keyed by the wallet round key holding the bet ids.

### `TransactionRecord` (by id, [fields.md](contracts/fields.md) §6) → `Transactions`

`id, playerId, type, amountCents (signed for adjustments), currency, walletType?, status,
createdAt, roundId?, gameId?, paymentRef?, withdrawalRequestId?`, mapped per family by §2's table.
`withdrawal_completed` on `casino.withdrawals` completes the WITHDRAWAL row whose
`withdrawalRequestId` equals its `payoutId`, through `WithdrawalsByRequest`, a key value index.

### `BonusAwardRecord` (by `bonusId`) → `BonusAwards`

`id, playerId, offerId, name?, type?, valueCents, currency, wageringRequirementCents?,
wageredCents?, status, grantedAt, expiresAt?`. From `casino.rewards` grant and end families (status
per §2), and from `money.transactions`: `restricted_funds_awarded` gives the requirement, and
`records` (`RecordProgress`) on any movement gives the progress and the wallet's status.

### Paging

Each view's child query is `ordered(cond, (time, id), limit, offset)` and `count(cond)`:

| Collection | `cond` | Order |
|---|---|---|
| players | — | `registeredAt`, `id`; rows without `registeredAt` excluded |
| a player's sessions / rounds / bets / transactions / bonus awards | `playerId = $p` | `startedAt` / `startedAt` / `placedAt` / `createdAt` / `grantedAt`, then `id` |
| a session's rounds | `sessionId = $s` | `startedAt`, `id` |
| a round's bets | `roundId = $r` | `placedAt`, `id` |

The parent is read first. An absent parent is `404` before any child query (original
`routes/playerCollections.test.ts`).

## Statistics

### `StatsPointer` (key value, one: `"stats"`)

`{current: Option[Slot], a: SlotState, b: SlotState}`, `SlotState = {snapshotId?, computedAt?,
state: empty | building | ready}`.

### `PlayerFigures` (key value, by `<slot>/<playerId>`) → `PlayerFiguresView`

`{slot, snapshotId, playerId, status, lastActiveAt?, windows: {1d, 7d, 30d → PlayerWindow},
ranks: Map["<period>_<sort>[_<ccy>]" → Int]}`. A rank is present only where the player has the
figure (money ranks: only in a currency they played or deposited in). Ties are broken by `playerId`.

### `GameFigures` (key value, by `<slot>/<gameCode>`) → `GameFiguresView`

`{slot, snapshotId, gameId, windows: {1d, 7d, 30d → StatsWindow}, ranks}`.

### The run (`StatsRun`, a pure fold; `ComputeStats`, the timer's handler)

1. `t = now`; `slot` = the slot that is not current (`a` when none is); mark it `building` with a
   new snapshot id.
2. Stream `Rounds` with `startedAt > t − 30 d`, `Sessions` with `startedAt > t − 30 d`, and
   `Transactions` of type DEPOSIT (completed) and WITHDRAWAL (completed) with `createdAt > t − 30 d`.
3. For each window `w ∈ {1d, 7d, 30d}` and each row inside it:
   - a round with status `cancelled` counts nowhere;
   - any other round counts toward `rounds`, its player toward `uniquePlayers` and `games`, and
     — unless `freeRoundsGrant` is set — `realStakeCents` and `realWinCents` toward stake and win
     in its currency;
   - a session counts toward `sessions`;
   - a deposit or a withdrawal counts toward the player's count and amount in its currency.
   `ggr = stake − win`. `lastActiveAt` = the latest round `startedAt` or session `startedAt` in 30 d.
4. Rank: per window and sort, descending, ties by id; money sorts per currency.
5. Write every `PlayerFigures` and `GameFigures` row of the slot. Rows of the slot left from its
   previous run carry the old snapshot id, so the reads (which filter by snapshot id) never see them.
   They are deleted in the same run once the new rows are written.
6. Mark the slot `ready` with `computedAt = t`, then make it current.

A failure at any step leaves `current` as it was. The next hour starts again.

## Consumers

| Consumer | Source | Writes |
|---|---|---|
| `players-intake` | `casino.players` | `PlayerRecord` |
| `sessions-intake` | `casino.sessions` | `SessionRecord` |
| `rounds-intake` | `casino.rounds` | `RoundRecord` |
| `money-intake` | `money.transactions` | `TransactionRecord`, `BetRecord`, `BetsByRoundKey`, `BonusAwardRecord` (wagering) |
| `withdrawals-intake` | `casino.withdrawals` | `TransactionRecord` via `WithdrawalsByRequest` |
| `rewards-intake` | `casino.rewards` | `BonusAwardRecord` |
| `public-api-audit` | `AccessKey`, `KeyLog` events | `platform.audit` |

All read `StartFrom.Earliest` with `Lenient` serializers, and each applies a fact once by its
`FactId`.

## Timers

| Timer | When | Does |
|---|---|---|
| `compute-stats` | every hour at minute 5 | the run; then logs `public_api_stats_stale` at WARN if the current slot is older than three hours |
