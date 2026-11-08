# Routes: rewards

Four endpoints, each with one prefix and the slug a literal (research R7). Bodies and answers are
camelCase JSON, and every amount is a whole number of minor units: a decimal is 400 naming the field
(FR-032). Refusals are `{error: <code>, …}` with the status shown; the original's own shapes are kept
where its frontend reads them. A path's brand other than the slug is `404 wrong_brand` (spec 001).

## `RewardsEndpoint`: `/v1/casino/brands/<slug>/rewards`

ACL `Routes.player(slug)`; a `{playerId}` other than the token's is 403 `player_id_mismatch`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `GET /{playerId}/state` | — | one of `{state: "active", activeBonuses: [{recordId, grantRef, bonusBalance, currency, wageringRequirement, wageredAmount, maxWin, offer?}], bonusBalance, currency, wageringRequirement, wageredAmount, maxWin, offer?}`; `{state: "pending", selected: {offer summary, selectedAt, expiresAt}, competingOffers}`; `{state: "available", visibleOffers: [offer summary]}` (in the brand's order) | never refuses; a wallet failure degrades to `available` |
| `GET /{playerId}/free-rounds?currency=` | — | `[{id, gameId, gameName, spinsRemaining, expiresAt, providerStatus}]`, active and not failed | — |
| `POST /{playerId}/select` | `{offerId}` | no-deposit: `{ok: true}` after the award; deposit offer: `{ok: true, expiresAt}` | 400 `offerId required`; 404 `offer_not_visible`; 409 `{error: <reason>}` (a decline, or the wallet's code, `ALREADY_CLAIMED` on a repeat) |
| `POST /{playerId}/deselect` | — | `{ok: true}` | — |
| `POST /{playerId}/codes` | `{code}` | the offer summary `{offerId, role, source: "code", name, description, claimWindowDays, type, bonusPercentage, maxBonusValue, minDepositAmount, wageringMultiplier, wageringWindowDays, noDepositRequired}` | 400 `code is required`; 422 `invalid_code`; 409 `{status: "ineligible", reason}` |
| `POST /{playerId}/codes/preview` | `{code}` | `{valid: true, offerName, offerType, preview, firesImmediately, minDepositAmount, isAffiliate}` | 400 `code is required`; 200 `{valid: false, error: "code_not_found"}` |
| `POST /{playerId}/cancel` | `{grantRef?}` | `{status: "canceled", clawbackAmount, newRealBalance}` | 404 `grant_not_found`; 409 `no_active_bonus`; 503 `cancel_failed` |
| `POST /{playerId}/switch` | `{grantRef}` | `{ok: true}` | 400 `grantRef_required`; 409 `switch_failed`; 503 `service_unavailable` (no answer from the wallet) |
| `GET /{playerId}/history` | — | `[{grantRef, faceValue, currency, status, grantedAt, closedAt}]`, closed, newest 20 | — |
| `GET /{playerId}/grants` | — | `[{rewardId, offerName, status, wageringRequirement, releaseProgress, expiresAt, currency}]` | — (also `Routes.serviceOf("support-agent")`, by `withAcl`) |
| `GET /{playerId}/free-rounds/eligible?provider=&gameCode=` | — | `{grantId}`, the oldest live free rounds for the game | 404 `no_eligible_grant` (`Routes.serviceOf("game-provider")` only, by `withAcl`) |

The cancel is `POST` where the original's was `DELETE` with a body (`WireTranslation` maps it).

## `OffersEndpoint`: `/v1/casino/brands/<slug>/offers`

ACL `Routes.operatorHolding(slug, "brand_admin", "resource_admin", "platform_admin")`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `GET /` | — | `[offer]` in the brand's order | — |
| `GET /{offerId}` | — | `offer` | 404 `offer_not_found` |
| `POST /` | the offer's fields (data-model.md), `redemptionCodes: [{code, isAffiliate}]`, `spinConfigurations: [{currency, spinValue}]` | 201 `offer` | data-model.md's create order; 400 `spin_value_not_offered` + `allowedValues`; 400 `game_not_eligible`; 503 `service_unavailable` when the code document cannot be read and none was fetched |
| `PATCH /{offerId}` | any of the fields | `offer` | data-model.md's edit order; 409 `role_already_assigned` |
| `DELETE /{offerId}` | — | `{status: "deactivated"}` | 404 `offer_not_found` |
| `PUT /order` | `{orderedOfferIds}` | `{updated: n}` | — |
| `GET /code-document` | — | `{fetchedAt, entries, stale}` (FR-021; `stale` past 15 minutes) | — |
| `GET /free-rounds/spin-values?gameId=&currency=` | — | `{gameId, currency, values, source: "st8" \| "not_configured"}` | 502 `provider_unavailable` |

## `RewardAdminEndpoint`: `/v1/casino/brands/<slug>/reward-grants`

ACL `Routes.operator(slug)`; every route but `GET /` checks the three admin roles in the handler
(`forbidden`).

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `GET /?search=&status=&offerId=&playerId=&page=&pageSize=` | — | `{items: [{rewardId, playerId, offerId, offerName, type, status, faceValue, freeSpins, currency, wageringRequirement, wageredAmount, grantedAt, expiresAt, source}], total, page, pageSize}`; with `playerId`, every reward of the player, newest first, with `isPrimary`, and the player's `unmatchedRounds` | 400 naming `pageSize` (1–200) |
| `GET /{rewardId}` | — | the reward with its free-rounds part | 404 `CLAIM_NOT_FOUND` |
| `POST /{playerId}` | `{offerId, depositAmount?}`; or `{type: "FREE_SPINS", gameId, provider, spinCount, spinValue, maxConvertibleAmount, wageringMultiplier, winningsDestination, freeSpinsExpiryDays, wageringWindowDays}`; or `{bonusAmount, wager, maxConvertibleAmount?, currency}` | funds: `{status: "granted", bonusAmount, wageringRequired}`; free rounds: `{status: "granted", grantId, type: "FREE_SPINS", providerStatus}` | 404 `offer_not_found`; 422 `currency_not_configured` + `playerCurrency`, `player_currency_unresolved`, `game_not_eligible`, `spin_value_not_offered` + `allowedValues`; 409 `{status: "ineligible", reason}`; 502 `provider_rejected` + `grantId`; 400 `bonusAmount and wager are required` and FR-002's free-rounds codes |
| `POST /{rewardId}/revoke` | `{mode: "forfeit" \| "reclaim", reason?}` | `{status: "revoked", mode, clawbackApplied, newRealBalance}`; free rounds `{status: "revoked", mode, clawbackApplied: 0, freeSpins: true}` | 400 `INVALID_MODE`; 404 `CLAIM_NOT_FOUND`; 409 `NO_ACTIVE_BONUS`, `MISSING_BASELINE`, `FREE_SPINS_FINISHED` + `grantStatus`; 503 `PROVIDER_UNAVAILABLE`, `service_unavailable` |

## `CampaignEndpoint`: `/v1/casino/brands/<slug>/reward-campaigns`

ACL `Routes.serviceOf("engagement")`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `POST /{playerId}/target` | `{role, context?}` | `{ok: true}`; a repeat for the same offer the same | 422 `free_spins_offer_not_targetable`, `role_not_found` |
| `POST /{playerId}/award` | `{offerId, role?, currency?}` | `{ok: true, bonusAmount}`; free rounds `{ok: true, grantId, providerStatus}`; `{ok: true, alreadyProcessed: true}` | 422 `offer_not_found`, `offer_not_active`, `{error: <reason>}`, `currency_not_configured`, `player_currency_unresolved`, `game_not_eligible`, `spin_value_not_offered`, `grant_failed`; 503 `{error: "ELIGIBILITY_CHECK_UNAVAILABLE"}`; 502 `provider_rejected` |

The award's `bonusAmount` is the awarded amount (the original answered the maximum convertible
amount, a defect).

## Calls out

| To | Route | From |
|---|---|---|
| `wallet` | `POST W/awards`, `/awards/forfeit`, `/awards/cancel`, `/awards/close-pending-cap`, `/awards/switch`, `/provider-credits` | `WalletStep` (research R5) |
| `wallet` | `GET W/restricted-funds`, `GET W/balance` | the evaluator's open count; `state`, `cancel` answers |
| `game-provider` | `POST …/game-provider/st8/players/{playerId}/known`, `POST …/bonuses`, `GET …/bonuses/{grantId}`, `POST …/bonuses/{grantId}/cancel` | `FreeRounds` |
| `game-provider` | `GET …/spin-values`, `GET …/games/{gameCode}` (G1) | offer create and edit, free-rounds grants, the spin-values read |
| `players` | `GET …/players/config/brand-config` | the detection switch (research R9) |
| the programme | the code document URL, public | `codes-<slug>` |
