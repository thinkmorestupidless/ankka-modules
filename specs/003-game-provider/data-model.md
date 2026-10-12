# Data model: game-provider

Entities are ankka components of the one service `game-provider` in a brand's project. Money is
`MinorUnits` (contract.money), keys are `Key`, currencies `Currency`. No field is personal (FR-028,
research R16). Component kinds follow research R5, R6, R10, R13.

## Ticket (key value entity, id = the token)

A launch ticket and the session ticket it becomes (glossary: *launch ticket*, *session ticket*).

| Field | Type | Notes |
|---|---|---|
| token | String | the entity id; a UUID the launch mints |
| provider | String | `st8` today |
| playerId | String | not personal (spec 001 FR-024) |
| gameCode | String | |
| gameCategory | Option[String] | from the catalogue at launch, for the wallet's bet |
| sessionId | String | the game session `players` opened, or the id minted when it could not |
| currency | Currency | the player's at launch |
| freeRoundsGrant | Option[String] | the grant attached (`free-spins-grants/<uuid>` normalised) |
| funMode | Boolean | |
| status | `issued` \| `checked` | |
| issuedAt, checkedAt | Instant, Option[Instant] | |

Rules: `check(gameCode)` succeeds once, when `status = issued` and the game code matches (an
absent game code matches); otherwise it refuses `session_expired`. No expiry in either state. Every
later callback resolves the ticket by token and requires `status = checked` (`session_expired`
otherwise).

## Round (event sourced entity, id = `<provider>/<roundId>`)

One provider round (glossary: *round*, here the provider's). `roundId` is the callback's `round`
(or `round_id`), else its `transaction_id`; a buyin, a payout and a standalone credit are rounds of
their own transaction id.

State:

| Field | Type | Notes |
|---|---|---|
| provider, roundId | String | from the id |
| playerId, currency | String, Currency | from the first callback |
| gameCode, sessionId | Option[String] | from the ticket when there was one |
| transactions | Vector[ProviderTransaction] | in arrival order |
| conflictingRepeats | Vector[ConflictingRepeat] | transaction id, what differed, when |
| openBets | Vector[String] | wallet keys of bets placed and not yet settled, in order |
| totalStake, win | MinorUnits | |
| freeRoundsGrant | Option[String] | set by a free debit (FR-013) |
| unacknowledged | Vector[Unacknowledged] | open entries |
| status | `open` \| `settled` \| `cancelled` | |
| openedAt, settledAt | Instant, Option[Instant] | |

`ProviderTransaction`: transactionId, kind (`debit`, `free_debit`, `correction_debit`, `credit`,
`free_credit`, `cancel`, `buyin`, `payout`), amount (MinorUnits), walletKey (Option), walletAction
(`bet`, `win`, `loss`, `rollback`, `provider_credit`, `none`), walletTransactionId (Option),
status word answered, balanceAnswered (MinorUnits, signed), receivedAt. `Answer`: status, balance,
currency. `Unacknowledged`: transactionId, kind, amount, error, recordedAt, cleared
(Option[Cleared]); `Cleared`: how (`provider_retry` \| `operator_replay` \| `operator_resolve`),
reason (Option), operator (Option), at.

Events: `RoundOpened`, `DebitRecorded`, `FreeRoundsTagged`, `CreditRecorded`, `CancelRecorded`,
`BuyinRecorded`, `PayoutRecorded`, `RepeatConflicted`, `CallbackUnacknowledged`,
`UnacknowledgedCleared`, `RoundSettled(outcome)`, `RoundCancelled`.

Commands and rules (the money rules themselves are FR-012 to FR-016; the entity records what the
bet workflow did with the wallet; since 2026-10-10 it no longer answers repeats, the workflow does):

- `record(transaction, answer, movement)`: from the workflow's `record-round` step, before the
  workflow holds its outcome (research R18): appends the transaction, updates open bets, stake and win, and settles or cancels the round when the movement closes
  it: a credit (win or zero) settles every open bet (FR-014); a cancel closes the round as
  cancelled when no open bet remains (FR-015).
- `recordConflict(key, difference)`: a repeat whose body differed (FR-011).
- `recordUnacknowledged(transaction, error)`: answered `error`; the key is not an answer.
- `clear(key, how, reason, operator)`: by a successful retry, a replay or a resolve (FR-020).

State transitions: `open` → `settled` (credit, zero credit, standalone credit, payout) or
`cancelled` (last open bet rolled back or a cancel on a free-round debit); a cancel of a credit on a
settled or cancelled round records the movement and leaves the status (Edge Cases).

## BetWorkflow (workflow, id = `<provider>/<transaction id>`, the cancel id for a cancel)

Replaces the transaction index and the round's stored answers (spec session 2026-10-10, research
R18). Kept for the ledger's life (spec 001 FR-015): its finished state is the transaction's record.

State:

| Field | Type | Notes |
|---|---|---|
| status | `new` \| `asking` \| `moving` \| `recording` \| `done` \| `refused` \| `unacknowledged` \| `resolved` | |
| callback | Callback | kind, transaction id, round id, player, currency, amount, game, the referenced transaction for a cancel, and the body's digest |
| roundEntityId | String | `<provider>/<roundId>` |
| movement | Option[Movement] | what the wallet did: action, wallet key, wallet transaction id, settled bets |
| outcome | Option[Answer] | status, balance, currency: present only once the round has recorded the transaction |
| error | Option[String] | the last wallet failure while `unacknowledged` |

Commands: `start(job)` (the claim; `Conflict` when started; an account locked in the players view
ends it at once `player_locked`, recording nothing), `status` (query), `retry` (a provider retry or an
operator replay of an `unacknowledged` workflow: resumes the wallet step), `resolve(cleared)` (ends it
`resolved`). Steps: `may-bet` (500 ms step timeout, failing over to `rg-unavailable`, which ends it
`error`), `wallet` (three attempts, then `unacknowledged`, which records the callback on the round and
pauses), `record-round` (the outcome is written after it), `resolved`. Which steps run for which kind
is R18's table. In code: `rounds/BetWorkflow.scala` (with `BetJob`, `BetFlow`) and `rounds/MoneyMoves.scala`.

## KnownPlayer (key value entity, id = player id)

| Field | Type | Notes |
|---|---|---|
| providers | Map[String, Instant] | when the player became known to each provider |
| currency | Currency | the player's at the time |

Written at `check` and by `rewards`' make-known route; read by `player_profile` (`player_not_found`
when absent) and by token-less balance, cancel, buyin and payout to confirm the player is the
brand's. The profile's fields are read from `players` at the time of the call, never stored.

## LaunchWindow (key value entity, id = player id)

| Field | Type |
|---|---|
| windowStart | Instant |
| count | Int |

`launch()` admits while `count < 20` within 60 seconds of `windowStart`, else refuses with the
seconds left; the state expires two minutes after its last update (`expireAfter`).

## Game (key value entity, id = `<provider>/<code>`)

The provider's fields (US7 scenario 1, `St8GameSeeder.scala:157-218`): provider, code, name,
developerId, developerName, category, tags (derived: bonus types ∪ features ∪ themes), themes,
features, bonusTypes, restrictedTerritories, prohibitedTerritories, deprecationDate (Option),
releaseDate (Option), rtp (Option[String]), volatility (Option[Int]), hitRatio, maxPayoutCoeff,
thumbnailUrl, funMode, enabled (the provider's flag), removedFromFeed, lastSeenAt.

Rules: `upsert(fields)` replaces every provider field and clears `removedFromFeed`;
`markRemoved()` sets it and leaves `enabled` untouched (FR-021). The brand's own enablement is not
on the game: it is in BrandCatalogueConfiguration and decided at read time (FR-022).

## Catalogue (view over Game state)

One row per game, the Game's fields plus `codeLower` and `developerSlug` for the filters. Queries
(FR-023): by category, themes, features, bonus types, tag, developer id or slug, name substring,
country not in restricted or prohibited territories, deprecation date after today; ordered by code,
paged by limit (1 to 3,000) and offset; a batch of up to 50 codes; one code; the developers list.
Enablement is applied by the endpoint after the query, from BrandCatalogueConfiguration, so a page
is filled after filtering as the original did (`GameRoutes.scala:293-341`).

## BrandCatalogueConfiguration (key value entity, id = the brand slug)

| Field | Type |
|---|---|
| disabledDevelopers | Set[String] |
| disabledCategories | Set[String] |
| gameOverrides | Map[String (`<provider>/<code>`), Boolean] |

Rule: an override on a game marked removed is refused (feature: "a game marked removed cannot be
enabled by the brand"). Decision for a game: not removed, then category not disabled, then
developer not disabled, then the override else the provider's flag.

## GameRounds (key value entity, id = `<provider>/<code>`) and GamePopularity (view over its state)

`rounds: Long`, incremented by the `RoundCounter` consumer on `RoundOpened`. Popularity is the
view ordered by `rounds` descending, enablement applied at read time, limit 1 to 100 (default 20).
At-least-once redelivery may count a round twice after a crash (research R10).

## RefreshStatus (key value entity, id = provider)

`status` (`idle` \| `running` \| `succeeded` \| `failed`), startedAt, finishedAt, count, error,
attempt. Written by the CatalogueRefresh timed action; read by the operator status route.

## PlayerStatus (view over the `casino.players` topic)

One row per player: `accountStatus` (`active` \| `suspended` \| `self_excluded` \| `closed` \|
`blocked`), `since`. Folded from `player_suspended`, `player_reinstated`, `player_self_excluded`,
`player_closed`, and `blocked` from a `player_blocked` family spec 008 is asked to add (spec
Assumptions; until it exists the view never answers `blocked`). Read by debit and balance for
`player_locked` (FR-012, FR-017). Research R8 on reading from the topic's start.

## RoundsByPlayer, RoundsBySession (views over Round events)

One row per round: provider, roundId, playerId, sessionId, gameCode, currency, totalStake, win,
outcome, status, openedAt, settledAt. Queried by player and by session for the operator and for
spec 015's inspection; readers derive session totals from `casino.rounds`, not from these.

## Unacknowledged (view over Round events)

One row per round with open entries: playerId, provider, roundId, entries (transactionId, kind,
amount, error, recordedAt), openCount. Listed where `openCount > 0`; the metric is the sum.

## Provider configuration (service secret `provider/<name>`, research R4)

JSON document: `apiUrl`, `site`, `operatorPrivateKey` (base64 PKCS#8 EC, never answered on a
read), `providerPublicKey` (base64 DER) or `providerKeyDocumentUrl` (non-real-money installations
only, FR-026), `jurisdiction`, `lobbyUrl`, `depositUrl`, `walletUrl` (the brand-segmented callback
prefix this service serves, research R2), `updatedAt`. One per provider the brand runs.

## The fact: round_settled (`casino.rounds`, contract.facts, research R14)

As `contract/schemas/casino.rounds.v1.json` plus the additive fields `provider_transaction_ids`,
`wallet_transaction_ids` and optional `free_rounds_grant`; `outcome` ∈ {win, loss, cancelled,
credit}; keyed by the wallet (`player/currency`); `ce-id` = `<round entity id>/<settlement event
sequence>` so a redelivered settlement is deduplicated by the reader.
