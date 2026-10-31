# Contract: what game-provider asks of its neighbours

Every call is over HTTP within the project through `clients.services(<name>)`, as this service's
identity, with no token (spec 001 FR-020, service class). Each neighbour's spec fixes its own path
at its plan; what is written here is the shape this service depends on and stubs in its parity kit.
A path marked *expected* is this plan's reading of the neighbour's spec, to be reconciled when that
spec is planned; the parity kit's stubs carry the same expected paths so one edit moves both.

## wallet (spec 002)

Fixed by spec 002 as built (`specs/002-wallet/contracts/routes.md` on `002-wallet`, 2026-10-10).
Base `/v1/casino/brands/{brand}/wallets/{player}/{currency}`; the key is the body's `key` (002
FR-002); bodies are camelCase; amounts are minor-unit integers; a movement answers `realBalance`,
`bonusBalance`, `currency`, `txnId`, and `GET …/balance` answers `realBalance`, `restrictedBalance`,
`currency`, which this service sums and renders to the currency's exponent. Every call is made from
the bet workflow's `wallet` step (research R18). The wallet keeps only its recent 1,000 keys and asks
no `may-bet`; another command under a seen key is `KEY_REUSED`.

*2026-10-10*: the table below is 002's as built; `WalletClient` still sends the snake_case names of
this contract's first version (`round_id`, `game_id`, `allow_negative`, `game_category`) and reads
`real_balance`, so it must be aligned before the two services meet (tasks.md T088).

| Command | Path | Body | Used for |
|---|---|---|---|
| place-bet | `POST …/bets` | `key`, `roundId`, `gameId`, `sessionId`, `provider`, `gameType`, `category`, `amount`, `allowNegative` | debit, buyin, cancel of a credit (`allow_negative: true`) |
| credit-win | `POST …/wins` | `key`, `roundId`, `amount`, `betKey?` | credit on the first open bet |
| settle-loss | `POST …/losses` | `key`, `roundId`, `betKey?` | every other open bet on a credit; every open bet on a zero credit |
| rollback-bet | `POST …/rollbacks` | `key`, `roundId`, `betKey?` | cancel of a debit |
| provider-credit | `POST …/provider-credits` | `key`, `amount`, `providerRef`, `reason`, `actor` | standalone credit, payout, cancel of a debit the wallet no longer holds |
| balance | `GET …/balance` | | balance callbacks and every answered balance |

`roundId` is the wallet's round: this service passes the debit's transaction id, so one provider
round with several debits is several wallet rounds (FR-012). The win and the losses of a credit name
each open bet's own `roundId`; their `key` is `<credit transaction id>/<bet key>` so each movement
is idempotent on its own and a repeated credit that reaches the wallet moves nothing. A refusal is
`{"error": "<CODE>", "message": …, "details": …}`: `INSUFFICIENT_FUNDS` (409), `SCOPE_BLOCKED`,
`MAX_STAKE_EXCEEDED` and the rest of the responsible-gambling family (409), `ROUND_NOT_FOUND` (404),
`KEY_REUSED` (409), `INVALID_REQUEST` (400); mapping in research R7. (`PLAYER_LOCKED` and
`RG_UNAVAILABLE` are no longer the wallet's to answer a bet with: the bet workflow's `may-bet` step
decides them.) A 2xx carries `txnId` (the wallet's, kept on the round for `round_settled`).

*Settled 2026-10-10*: 002's bet now takes `provider`, `gameType` and `category` (above).

**To reconcile with spec 002** (as first written): FR-012 here places the bet "with the game, the game category and the
provider" (the wallet's contribution overrides are by game, provider or category, 002 FR-004); the
wallet's route carries `game_id` only. Either the wallet's bet gains `game_category` and `provider`,
or the wallet derives them from `game_id` through a catalogue it does not have. This plan sends
all three and asks 002 to accept them; `WalletClient` has one place to drop them.

*At implementation (2026-10-09)*: still open. Spec 002's route carries `game_id` only and 002 has no
service yet; `WalletClient.betBody` sends `game_category` and `provider` beside it, which a reader that
ignores unknown fields accepts. Task T010 stays open until 002 decides.

## players (spec 008)

| Need | Expected path | Answer |
|---|---|---|
| account status at launch (008 FR-018) | `GET /v1/casino/brands/{brand}/players/{playerId}/status` (008 routes.md) | `{status, displayName, verification}`, of which `status` is read; `suspended`, `self_excluded`, `blocked`, `closed` refuse the launch as `account_restricted` |
| open a game session (008 FR-017) | `POST /v1/casino/brands/{brand}/players/{playerId}/game-sessions` with `{gameId, gameName?, gameCategory?, currency, demo, playerSessionId?}` | `{sessionId}`; a refusal or failure does not fail the launch (FR-009) |
| recently played (FR-009) | `POST /v1/casino/brands/{brand}/players/{playerId}/recently-played` with `{gameId}` | 204; best effort |
| profile fields for `player_profile`, `check` and the launch body | `GET /v1/casino/brands/{brand}/players/{playerId}/profile` | `{defaultCurrency, country, dateOfBirth, registeredAt, dailyDepositLimit?}`; 404 → `player_not_found` |

## kyc (spec 009)

| Need | Expected path | Answer |
|---|---|---|
| the play gate (009 FR-020) | `GET /v1/casino/brands/{brand}/kyc/{playerId}/play-check` | `{allowed: true}` or `{allowed: false, error: "PLAY_BLOCKED_KYC", kycStatus, message}` |

## responsible-gambling (spec 010)

| Need | Expected path | Answer |
|---|---|---|
| `may-play` (010 FR-008) | `GET /v1/casino/brands/{brand}/responsible-gambling/{playerId}/may-play` | `{allowed: true, sessionLimitMinutes?, minutesUsed}` or `{allowed: false, reason: "excluded" \| "cooling_off" \| "session_limit_reached", coolingOffUntil?, limitMinutes?, usedMinutes?}` |
| `may-bet` (010 FR-010; asked by the wallet until 2026-10-10) | `POST /v1/casino/brands/{brand}/rg/may-bet` with `playerId`, `currency`, `amount` (the path spec 002 used); *open (010 R6):* `lossLast24h`, `lossLast7d`, `lossLast30d` from the wallet | `decision`: allowed, locked, excluded, cooling_off (`endsAt`); also, as built in 010, `spending_limit` (`period`, `limit`, `remaining`) and `unavailable` |

*Open, from spec 010 R6 (decided 2026-10-27):* the bet workflow does not yet carry the loss sums, nor
read `spending_limit` as its own refusal. Both are read as unavailable today, which ends the bet `error`.
So a player with a loss limit set cannot bet until spec 002 keeps the sums and this workflow carries them.

Each of the three fails closed: no answer or a non-2xx that is not a refusal is 503
`service_unavailable` naming the service (FR-007). `may-bet` is asked from the bet workflow's step
with a 500 ms bound; no answer, a non-2xx or an unreadable one ends the workflow `error` without a
wallet call (FR-012, research R18).

## rewards (spec 011)

| Need | Expected path | Answer |
|---|---|---|
| the player's oldest eligible free-rounds grant for a game, when the launch names none (FR-008) | `GET /v1/casino/brands/{brand}/rewards/{playerId}/free-rounds/eligible?provider=…&gameCode=…` | `{grantId}` or 404 `no_eligible_grant`; no answer or a failure attaches no grant and does not fail the launch |

The reverse direction (`rewards` calling this service for a bonus) is in `contracts/routes.md`,
service routes.

## players (spec 008), the fact this service needs

`PlayerStatus` folds `blocked` from a `player_blocked` family 008 FR-035 does not list yet (spec
Assumptions). Raised with T010.

## Topics read

`casino.players` (view PlayerStatus, research R8). Topics written: `casino.rounds`
(contracts/round-settled.md).
