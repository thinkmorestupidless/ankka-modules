# Wire translation: the original's recordings against rewards

`WireTranslation` maps a recorded step of the original to the port's request, and the port's answer
back, so `ParitySuite` compares like with like.

| Original | Port | Notes |
|---|---|---|
| `/v1/casino/bonus/{brand}/…` player routes | `/v1/casino/brands/<slug>/rewards/{playerId}/…` | the player id from the token's `eitheror_twin_id` |
| `DELETE /v1/casino/bonus/{brand}/cancel` | `POST …/rewards/{playerId}/cancel` | |
| `POST /v1/casino/bonus/promo-code/{brand}` `action: preview` | `POST …/rewards/{playerId}/codes/preview` | other actions: marked not carried |
| `POST /v1/casino/bonus/claim/{brand}` | — | marked not carried |
| operator `/v1/casino/bonus/offers/{brand}…` | `/offers…` | `reorder` → `order` |
| `/v1/casino/bonus/free-spins/spin-values/{brand}` | `/offers/free-rounds/spin-values` | |
| `/v1/casino/bonus/grant/{brand}/{playerId}` | `/reward-grants/{playerId}` | |
| `PATCH /v1/casino/{brand}/bonuses/{claimId}/revoke` | `POST /reward-grants/{claimId}/revoke` | |
| backoffice bonuses lists | `/reward-grants`, `/reward-grants?playerId=` | `playerEmail` dropped from rows (no personal data) |
| Customer.io webhook `grant_bonus`, `target_offer` | `/reward-campaigns/{playerId}/award`, `/target` | signature checks are `engagement`'s |
| amounts as major-unit strings (`"10.00"`) | minor units (`1000`) | both directions |
| `bonus-claims/<id>`, `free-spins-grants/<id>` refs | the reward id | the prefix stripped |
| twin reads `twins/bonus-claims/{id}`, `twins/free-spins-grants/{id}` | `GET /reward-grants/{rewardId}` | status words as the original's (`granted`, `used`, `expired` for the free-rounds part mapped from the reward's state) |
| wallet `…/bonus/status` reads | the wallet's `GET W/restricted-funds` | the wallet's own translation (spec 002) |
| `money.transactions` reason `cancel` on a revoke `forfeit` | `forfeit` | marked divergence (research R5) |
