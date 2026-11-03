# Surface: the original's rewards, moved, dropped or new

Every route and function of the original that touches a reward, and where it goes. `BR` =
`BonusRoutes.scala`, `CW` = `CustomerIOWebhookRoutes.scala`, `BPR` = `BackofficePlayerRoutes.scala`.
New paths are under `/v1/casino/brands/<slug>`; [routes.md](routes.md) has their bodies.

## Player routes (`/v1/casino/bonus`)

| Original | Disposition |
|---|---|
| `GET /{brand}/state` (`BR:1196-1360`) | moved: `GET /rewards/{playerId}/state` |
| `GET /{brand}/{playerId}/free-spins` (`BR:1368-1423`) | moved: `GET /rewards/{playerId}/free-rounds` |
| `POST /{brand}/select` (`BR:1426-1658`) | moved: `POST /rewards/{playerId}/select`; the legacy pointer fallback dropped (spec 019) |
| `POST /{brand}/deselect` (`BR:1661-1707`) | moved: `POST /rewards/{playerId}/deselect` |
| `POST /{brand}/enter-code` (`BR:1710-1791`) | moved: `POST /rewards/{playerId}/codes` |
| `GET /offers/{brand}/eligible` (`BR:1794-1868`) | dropped (settled 2026-11-01) |
| `POST /claim/{brand}` (`BR:2466-2623`) | dropped (settled 2026-11-01) |
| `POST /promo-code/{brand}` preview (`BR:2626-2736`) | moved: `POST /rewards/{playerId}/codes/preview` |
| `POST /promo-code/{brand}` store, `GET /promo-code/{brand}` (`BR:2738-2815`) | dropped (settled 2026-11-01); the affiliate-attribution branch is spec 012's |
| `DELETE /{brand}/cancel` (`BR:2823-3005`) | moved: `POST /rewards/{playerId}/cancel` |
| `POST /{brand}/switch` (`BR:3008-3068`) | moved: `POST /rewards/{playerId}/switch` |
| `GET /{brand}/history` (`BR:3071-3123`) | moved: `GET /rewards/{playerId}/history` |

## Operator routes

| Original | Disposition |
|---|---|
| `POST /v1/casino/bonus/offers/{brand}` (`BR:1879-2171`) | moved: `POST /offers` |
| `GET /v1/casino/bonus/offers/{brand}` and `/{offerId}` | moved: `GET /offers`, `GET /offers/{offerId}` |
| `PATCH /v1/casino/bonus/offers/{brand}/{offerId}` (`BR:2204-2415`) | moved: `PATCH /offers/{offerId}`; codes editable (a change) |
| `DELETE /v1/casino/bonus/offers/{brand}/{offerId}` | moved: `DELETE /offers/{offerId}` (deactivates) |
| `PUT /v1/casino/bonus/offers/{brand}/reorder` | moved: `PUT /offers/order` |
| `GET /v1/casino/bonus/free-spins/spin-values/{brand}` | moved: `GET /offers/free-rounds/spin-values?gameId=&currency=` |
| `POST /v1/casino/bonus/grant/{brand}/{playerId}` (three shapes) | moved: `POST /reward-grants/{playerId}` |
| `PATCH /v1/casino/{brand}/bonuses/{claimId}/revoke` (`BR:769-1130`) | moved: `POST /reward-grants/{rewardId}/revoke` |
| `GET /v1/casino/backoffice/{brand}/players/{playerId}/bonuses` | moved: `GET /reward-grants?playerId=` |
| `GET /v1/casino/backoffice/{brand}/bonuses` | moved: `GET /reward-grants` |
| `POST …/players/{playerId}/clear-no-deposit-block` (`BPR:2037-2074`) | `players`' (spec 008, FR-036) |
| `GET/PUT /v1/casino/brands/{brand}/bonus-config` | the wallet's `bet-switches` (spec 002) |

## Webhook and internal

| Original | Disposition |
|---|---|
| Customer.io `grant_bonus` (`CW:147-358`) | `engagement` translates to `POST /reward-campaigns/{playerId}/award` |
| Customer.io `target_offer` (`CW:747-805`) | `engagement` translates to `POST /reward-campaigns/{playerId}/target` |
| `POST /internal/v1/casino/{brand}/wallet/deposit-notify` (reward part) | `MoneyConsumer` on `deposit_confirmed` |
| `…/preflight/retire-legacy-bonus-refs`, `…/rollback/legacy-claims`, `…/backfill/legacy-claims` | dropped (migration tooling) |
| `notify/bonus-cost` to the affiliate service | dropped: the amount on `bonus_granted` (FR-028) |

## Functions and capabilities

| Original | Disposition |
|---|---|
| `reward-grant-expiry` / `expire_reward_grant` | timers `claim-`, `wagering-`, `cap-<reward>` |
| `set-withdrawal-cap-expiry` | `Reward.Converted` sets `cap-<reward>` |
| `settle-free-spins-round` | `RoundsConsumer` → `Reward.RoundSettled` |
| `finalize-expired-free-spins-grant` | timer `rounds-<reward>` |
| `award_bonus`, `credit_real_balance` | `WalletStep` `winnings` and `credit` |
| `notify-bonus` / `customerio_dispatch`, `export-bonus` / `kafka_publish` | `RewardsPublisher` → `casino.rewards`; `engagement` and `reporting` consume |
| `FreeSpinsProvisioner` (in-memory poll) | `FreeRounds` workflow |

## New

- `GET /rewards/{playerId}/grants` (FR-022a), for the player and `support-agent`.
- `GET /rewards/{playerId}/free-rounds/eligible` (FR-022b), for `game-provider`; already called.
- `bonus_code_entered`, `free_spins_granted`, `free_spins_settled`, `grant_expired` facts.
