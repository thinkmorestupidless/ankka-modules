# Contract: the calls this service makes to ST8

The provider's operator API as the original called it and as `fake-st8` and spec 020's simulator
serve it. Every request carries `x-st8-sign`: base64 of `SHA256withECDSA` under the brand's
operator private key (base64 PKCS#8 EC) over the exact body bytes for a `POST` and over the query
string (`site=…`, `bonus_id=…&site=…`) for a `GET`. The API address and the site are the brand's
configuration for the provider (research R4). HTTP/1.1; the original forced it because ST8 closes
with a reset.

| Call | Request | Answer read |
|---|---|---|
| launch (FR-008) | `POST {api}/api/operator/v1/launch` body `{game_code, currency, token, player, fun_mode, site:{id, lobby_url, deposit_url, wallet_url}, player_profile:{id, default_currency, reg_country, jurisdiction, birth_date, reg_date, bet_limits}}` | 200 with `game_url`; anything else, or no `game_url`, is `launch_failed` and the ticket is discarded |
| games (FR-021) | `GET {api}/api/operator/v1/games?site={site}` | `{games:[{code, name, category, developer, enabled, fun_mode, bonus_types, features, themes, restricted_territories, prohibited_territories, rtp, volatility, hit_ratio, max_payout_coeff, release_date, deprecation_date, thumbnail_url}], developers:[{developer_id, name}]}`; a game with no code is skipped; a missing thumbnail becomes `https://luckmedia.link/{code}/thumb.webp` as the original did |
| create bonus (FR-025) | `POST {api}/api/operator/v1/bonus/create` body `{bonus_id, players:[player], count, value, type:"free_bets", currency, game_codes, site, start_time, duration}` | `status` word; `bonus.instances[0].status` → active (`active`, `accepted`), processing, rejected/failed/`error`, `canceled`; an absent status is processing |
| poll bonus | `GET {api}/api/operator/v1/bonus/get?bonus_id={grantId}&site={site}` | as create |
| cancel bonus | `POST {api}/api/operator/v1/bonus/cancel` body `{bonus_id, player, site}` | as create |
| spin values (FR-025) | `GET {api}/api/operator/v1/bonus/offers?game_codes={codes}&currency={cur}&type=free_bets&site={site}` | `{status:"ok", offers:[{value}]}`; anything else is `provider_unavailable` |
| key document (FR-026, non-real-money only) | `GET {providerKeyDocumentUrl}` | `{keys:[{kid, publicKey}]}` as spec 020 FR-011 publishes it; every listed key verifies until withdrawn; refreshed every 30 seconds |

`wallet_url` is `https://game-provider-{slug}.{base}/v1/casino/brands/{slug}/st8`; because ST8
strips the path, the callbacks are also served at that host's root (research R2).

In a local run and in the parity kit the API address names spec 020's simulator or an in-process
stub of these routes; the recordings' `outbound` lists what the original sent to `fake-st8` and the
parity test compares the launch body field by field, with `token` under `vary`.
