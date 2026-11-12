# Routes

Four endpoints, one prefix each (research R3; the erasure route is on `/v1`). ankka 0.16.0: at most two path parameters, no shared
prefix.

## `/v1`: the public contract (Acl.AllowAll; the access key checked in the handler)

The 22 operations of the served contract, unchanged from the original
(`original/public-api-v1.openapi.yaml`), plus the reference page.

| Method | Path | Operation | Key |
|---|---|---|---|
| GET | `/v1/openapi.json` | getOpenApi | none |
| GET | `/v1/docs` | (reference page) | none |
| GET | `/v1/contract` | getContractInfo | yes |
| GET | `/v1/players` | listPlayers | yes |
| GET | `/v1/players/{playerId}` | getPlayer | yes |
| GET | `/v1/players/{playerId}/sessions` | listPlayerSessions | yes |
| GET | `/v1/players/{playerId}/rounds` | listPlayerRounds | yes |
| GET | `/v1/players/{playerId}/bets` | listPlayerBets | yes |
| GET | `/v1/players/{playerId}/transactions` | listPlayerTransactions | yes |
| GET | `/v1/players/{playerId}/bonus-awards` | listPlayerBonusAwards | yes |
| GET | `/v1/players/{playerId}/stats` | getPlayerStats | yes |
| GET | `/v1/sessions/{sessionId}` | getSession | yes |
| GET | `/v1/sessions/{sessionId}/rounds` | listSessionRounds | yes |
| GET | `/v1/rounds/{roundId}` | getRound | yes |
| GET | `/v1/rounds/{roundId}/bets` | listRoundBets | yes |
| GET | `/v1/bets/{betId}` | getBet | yes |
| GET | `/v1/transactions/{transactionId}` | getTransaction | yes |
| GET | `/v1/bonus-awards/{bonusAwardId}` | getBonusAward | yes |
| GET | `/v1/games` | listGames | yes |
| GET | `/v1/games/{gameId}` | getGame | yes |
| GET | `/v1/games/{gameId}/stats` | getGameStats | yes |
| GET | `/v1/game-stats` | rankGames | yes |
| GET | `/v1/player-stats` | rankPlayers | yes |

`/v1/docs` is not an operation of the contract (as in the original). Any method but GET on these
paths is `405 invalid_request` "Only GET is supported". An unknown path under `/v1` is `401` without a
key and `404 not_found` with one (original `router.ts:41-64`, `server.ts:103-140`).

**The pipeline, in order** (original `server.ts:142-232`, with the key in place of the token):

1. public route? serve it;
2. key: `Authorization: Bearer ak_<keyId>_<secret>`; absent `key_missing`; malformed `key_invalid`;
3. `AccessKey(keyId).admit(digest, now)`: `key_invalid`, `key_expired` or `key_disabled`, or the
   key's fields and rate;
4. `KeyWindow(keyId).take(now, rate)`: `429 quota_exceeded` with `Retry-After`;
5. route: path ids checked (`Uuid`, `GameCode`; `400 invalid_request` "`<name>` is not a valid
   identifier"), then parameters (`limit` above 100 is `limit_exceeded`), then the handler, all under
   the five-second deadline (`503 unavailable`);
6. after the answer, from step 3 on: `KeyLog(keyId).served(...)` (research R6).

Every refusal is `content-type: application/json`, `{"error": "<code>"}` with `"message"` only where
the original sets one (inside the router).

## `/access-keys`: the brand's keys (operator holding `api_keys`, or `platform_admin`)

`Routes.operatorHolding(slug, AccessKeysRole)` and `Routes.brandSegment` (a wrong `{brand}` is 404).
These are new; the original had no key routes (its grant was a Helm value).

| Method | Path | Body | Answer |
|---|---|---|---|
| GET | `/access-keys/{brand}` | — | `200 {"keys":[Key]}`, deleted keys omitted unless `?deleted=true` |
| POST | `/access-keys/{brand}` | `{"name", "expiresAt"?, "identifyingFields"?, "requestsPerMinute"?}` | `201 {"key": Key, "secret": "ak_…"}`; the only answer carrying the secret |
| GET | `/access-keys/{brand}/{keyId}` | — | `200 Key`, `404` |
| PATCH | `/access-keys/{brand}/{keyId}` | any of `name`, `expiresAt` (or `null` to clear), `identifyingFields`, `requestsPerMinute` | `200 Key`; `409 key_deleted` on a deleted key |
| POST | `/access-keys/{brand}/{keyId}/disable` | — | `200 Key`; idempotent |
| POST | `/access-keys/{brand}/{keyId}/enable` | — | `200 Key`; `409 key_deleted` on a deleted key |
| DELETE | `/access-keys/{brand}/{keyId}` | — | `204`; idempotent |

`Key` is `{id, name, prefix: "ak_<id>", enabled, deleted, expiresAt?, identifyingFields:[…],
requestsPerMinute, createdBy, createdAt, lastUsedAt?}`. `identifyingFields` holds the contract's
names: `email`, `firstName`, `lastName`, `dateOfBirth`, `phone`, `addressLine1`, `city`, `stateProv`,
`postalCode`. Validation (`400 invalid_request` with `field`): a name of 1 to 100 characters;
`expiresAt` in the future; known field names; `requestsPerMinute` from 1 to 6,000.

## `/public-api`: status; the player's erasure

| Method | Path | Admission | Answer |
|---|---|---|---|
| GET | `/public-api/{brand}/status` | `Routes.operator(slug)` | `{statsComputedAt?, statsStale, snapshot?, views: {name: {lastAppliedAt?}}}` |
| POST | `/v1/casino/brands/{brand}/public-api/erasure/players/{player}` (on the `/v1` endpoint, its own `withAcl`) | `Routes.playersOnly` | `{service:"public-api", subject}`: ankka 042's erasure of the player's `Personal` fields, as every brand service (spec 015's route) |

## `/health`

`GET /health` → `{"status":"ok"}`, no key (original `server.ts:117-120`).
