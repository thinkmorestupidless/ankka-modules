# The parity translation

`WireTranslation` maps each recorded request of the original to this service (or to its caller, for the
enforcement points) and compares the answer field by field. A row marked **divergence** is asserted as this
spec says and named in `Parity.divergences`.

## Paths

| Original | Here |
|---|---|
| `/v1/casino/rg/{brand}/{player}/…` | unchanged (the slug literal) |
| `/v1/casino/{brand}/players/{player}/rg` | `/v1/casino/backoffice/<slug>/rg/{player}` |
| `/v1/casino/{brand}/players/{player}/rg/limits/{type}`, `/rg/cooling-off` | `/v1/casino/backoffice/<slug>/rg/{player}/limits/{type}`, `/cooling-off` |
| `/v1/casino/{brand}/players/{player}/self-exclude` | `/v1/casino/backoffice/<slug>/rg/{player}/self-exclude` |
| `/v1/casino/{brand}/rg/cases/close` `{caseId: "<ns>/<id>"}` | `/v1/casino/backoffice/<slug>/rg/{player}/cases/{id}/close` (the id after the `/`) |
| `/v1/casino/brands/{brand}/rg-config` | `/v1/casino/backoffice/<slug>/rg/settings` |
| `/internal/v1/casino/{brand}/notify/deposit` | `POST /v1/casino/brands/<slug>/rg/may-deposit` with the sums the wallet would carry (the replay's own deposits) |
| `/v1/casino/sessions/{brand}/launch` (the RG refusals) | game-provider's launch over `may-play` (the launch bodies are game-provider's and unchanged) |
| `/v1/casino/players/{brand}/login` (the RG refusals) | `players`' sign-in over `may-log-in` |

## Answers

| Original | Here |
|---|---|
| `notify/deposit` 403 `{error: account_restricted, reason: self_excluded}` | `{decision: excluded}` |
| `notify/deposit` 403 `{error: account_restricted, reason: cooling_off_active}` | `{decision: cooling_off, endsAt}` (**divergence**: the end is named) |
| `notify/deposit` 403 `{error: deposit_limit_exceeded, limitType, limitValue, usedAmount, remainingAmount, currency}` | `{decision: over_limit, period, limit, used, remaining}`; `limitType` `DEPOSIT_DAILY` ↔ `period` `daily` |
| `notify/deposit` 200 `{ok: true}` | `{decision: allowed}` |
| `400 {"error": "unknown limit type: X"}` | `400 unknown_limit_type` (**divergence**: a code, not a sentence) |
| `400 {"error": "value is required"}`, `"until is required"`, `"intervalMinutes required"` | `value_required`, `until_required`, `interval_required` (**divergence**) |
| `GET /history` without limit changes, `[]` on error | every change (**divergence**, FR-027); 503 on error |
| A case `status` `pending` / `active` | `open` (**divergence**, FR-026) |
| Operator `PUT /rg/limits` leaving the player's pending change | cancelled (**divergence**, FR-004) |
| Operator `GET /rg` small status | the full state (**divergence**, FR-025) |
| `usedAmount` as a decimal with 2 places | unchanged on the player's status read (minor units ÷ 100 for a 2-place currency, as the original renders); `Long` everywhere else |

Times compare as instants; minted ids (case ids) compare for presence.
