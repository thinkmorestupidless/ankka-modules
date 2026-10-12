# Contract: every route of game-provider and its class

Classes and recipes are spec 001's (`contracts/routes.md`, `contract.access.Routes`). Every route
with a `{brand}` segment refuses another brand's (FR-005); the catalogue routes the frontend reads
carry the brand as a query parameter and refuse another brand's the same way. Paths the frontend or
the provider calls are the original's, unchanged (SC-006, SC-001).

## Public (signature-verified; contracts/callbacks.md)

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/casino/brands/{brand}/st8/{callback}` | the wallet URL the launch names |
| POST | `/st8/{callback}` | ST8 strips the path (research R2) |
| POST | `/{callback}` | likewise |

`{callback}` ∈ check, balance, player_profile, debit, credit, cancel, buyin, payout. A second
provider: `/v1/casino/brands/{brand}/{provider}/{callback}` and `/{provider}/{callback}` only.

## Player (`Routes.player(slug)`)

| Method | Path | Body / query | Answer |
|---|---|---|---|
| POST | `/v1/casino/sessions/{brand}/launch` | `{playerId, gameId, provider?, currency?, demo?, freeSpinsGrantRef?}`; `provider` defaults to the one provider configured, refused `provider_required` when the brand runs several and the game code is shared | 200 `{launchToken, gameUrl, sessionId, freeSpinsGrantRef?}`; 400 `playerId required` / `gameId required` / `free_spins_grant_invalid` / `provider_required`; 403 `player_id_mismatch`, `account_restricted` (+`reason`), `PLAY_BLOCKED_KYC` (+`kycStatus`, `message`), `cooling_off_active` (+`coolingOffUntil`), `session_limit_reached` (+`limitMinutes`, `usedMinutes`); 429 `RATE_LIMITED` (+`action`, `retryAfterSeconds`); 503 `service_unavailable`, `st8_not_configured`; 500 `launch_failed` |
| GET | `/v1/casino/catalog/games` | `brand`, `category`, `themes`, `features`, `bonusTypes`, `tag`, `developer`, `search`, `country`, `limit` (1–3000), `offset`, `ids` (≤ 50, ignores the rest) | `{games}` or, with `limit`, `{games, total, nextOffset?}`; each game as `gameToJson` (research R10) plus `provider` |
| GET | `/v1/casino/catalog/games/{gameCode}` | `brand`, `provider?` | one game; 404 `game_not_found`; 400 `ambiguous_game_code` naming both providers when two carry the code and none is named |
| GET | `/v1/casino/catalog/developers` | `brand`, `country` | `{developers:[{developerId, developerName}]}` |
| GET | `/v1/casino/catalog/{brand}/games/popular` | `limit` (1–100, default 20) | `{gameIds}` ranked by rounds played, disabled games excluded at read time |

Whether the lobby reads the catalogue before sign-in is checked against the Playwright suite in
Phase 1; if it does, the catalogue reads become `Routes.public` and FR-027 is corrected.

## Operator (`Routes.operator(slug)`)

| Method | Path | Body | Answer |
|---|---|---|---|
| GET | `/v1/casino/brands/{brand}/game-catalog-config` | | `{brandId, disabledDevelopers, disabledCategories, gameOverrides}` |
| PUT | `/v1/casino/brands/{brand}/game-catalog-config` | the same three fields | the configuration; 400 `game_removed` naming a game an override would enable that is marked removed |
| GET | `/v1/casino/brands/{brand}/game-catalog` | `provider?` | every game with `code, provider, name, category, developer, thumbnailUrl, st8Enabled, removedFromFeed, individualOverride, categoryDisabled, developerDisabled, effectiveEnabled` |
| GET | `/v1/casino/backoffice/{brand}/game-provider/providers` | | the providers configured, each without its private key |
| GET | `/v1/casino/backoffice/{brand}/game-provider/providers/{provider}` | | the configuration without the private key; 404 `provider_not_configured` |
| PUT | `/v1/casino/backoffice/{brand}/game-provider/providers/{provider}` | `{apiUrl, site, operatorPrivateKey, providerPublicKey?, providerKeyDocumentUrl?, jurisdiction, lobbyUrl, depositUrl, walletUrl?}`; keys as PEM or base64 DER; exactly one of the two key fields; `providerKeyDocumentUrl` refused 400 `real_money_brand` on a real-money brand (`CASINO_REAL_MONEY`, rendered by the brands tool); `walletUrl` defaults to `CASINO_PUBLIC_URL` + the brand-segmented callback prefix; 404 `provider_unknown` for a provider with no adapter | 204; written to the secret store (research R4); the nightly refresh scheduled |
| POST | `/v1/casino/backoffice/{brand}/game-provider/providers/{provider}/catalogue/refresh` | | 202 `{status:"running"}` |
| GET | `/v1/casino/backoffice/{brand}/game-provider/providers/{provider}/catalogue/refresh` | | the RefreshStatus |
| GET | `/v1/casino/backoffice/{brand}/game-provider/unacknowledged` | | `{entries:[{transactionId, kind, amount, currency, provider, roundId, playerId, error, recordedAt}]}` |
| POST | `/v1/casino/backoffice/{brand}/game-provider/unacknowledged/{transactionId}/replay` | | 200 `{status, balance, currency}` as the callback would have answered; 409 `still_unacknowledged` with the new error when the wallet refuses again; 404 when not open |
| POST | `/v1/casino/backoffice/{brand}/game-provider/unacknowledged/{transactionId}/resolve` | `{reason}` (required) | 204 |
| GET | `/v1/casino/backoffice/{brand}/game-provider/rounds` | `playerId` or `sessionId`, `limit`, `offset` | rows of RoundsByPlayer / RoundsBySession |
| GET | `/v1/casino/backoffice/{brand}/game-provider/rounds/{provider}/{roundId}` | | the round's state with its transactions; `{provider}` is a literal per adapter, since a route takes at most two path parameters in ankka 0.11 |
| GET | `/v1/casino/backoffice/{brand}/game-provider/metrics` | | `text/plain` in Prometheus' format: `eitheror_casino_bets_total` and `eitheror_casino_unacknowledged_callbacks` (FR-029, research R16) |

## Service (`Routes.service()`, callers in the project; the caller is `rewards`)

| Method | Path | Body | Answer |
|---|---|---|---|
| POST | `/v1/casino/brands/{brand}/game-provider/{provider}/players/{playerId}/known` | `{currency}` | 204; the player is known to the provider |
| POST | `/v1/casino/brands/{brand}/game-provider/{provider}/bonuses` | `{grantId, playerId, gameCodes, count, spinValue, currency, durationSeconds}` | 200 `{status}` ∈ active, processing, rejected, failed, cancelled, not_configured; `providerBonusId?` |
| GET | `/v1/casino/brands/{brand}/game-provider/{provider}/bonuses/{grantId}` | | `{status}` |
| POST | `/v1/casino/brands/{brand}/game-provider/{provider}/bonuses/{grantId}/cancel` | `{playerId}` | `{status}` |
| GET | `/v1/casino/brands/{brand}/game-provider/{provider}/spin-values` | `gameCodes` (repeated or comma-separated), `currency` | `{values:[…]}` sorted, cached for the day; `{status:"not_configured"}`; 503 `provider_unavailable` |

## Not served (dropped from the original)

`/st8/test/*`, `/internal/register-token/*`, `/internal/register-player/*`, `/st8/launch-stub`,
`/v1/casino/provider/*` (the generic API), `/internal/v1/casino/catalog/refresh*`,
`GET /test/transaction-amount/*`. Game session routes (`GET /v1/casino/sessions/…`, `PATCH …/end`)
are `players`' (spec 008).
