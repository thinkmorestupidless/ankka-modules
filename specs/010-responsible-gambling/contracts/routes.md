# Routes: responsible-gambling

Three endpoints, one prefix each, the brand's slug a literal (R14). Refusals are
`{"error": "<code>", "message": "…", …named fields}` unless a caller's contract says otherwise (the checks).
Amounts are `Long` minor units; on the player's status read they are shown as the original shows them
(`contracts/wire.md`).

## PlayerEndpoint: `/v1/casino/rg/<slug>`, `Routes.player`

The `{player}` segment must be the token's player: `403 player_id_mismatch` otherwise.

| Route | Body | Answer | Refusals |
|---|---|---|---|
| `GET /{player}/limits` | — | 200 the status (below) | 503 |
| `PUT /{player}/limits/{type}` | `value` (string), `currency?` | 200 the status | 400 `unknown_limit_type`, `value_required`, `invalid_value`, `below_minimum_session_minutes` (`minimumMinutes`), `invalid_currency` |
| `DELETE /{player}/limits/{type}` | `currency?` (query) | 200 the status (removal staged) | 400 `unknown_limit_type` |
| `PUT /{player}/cooling-off` | `until` | 200 the status | 400 `until_required`, `invalid_until`, `break_too_short`, `break_too_long` (`maximumDays`); 409 `break_active` |
| `PUT /{player}/permanent-exclusion` | — | 200 `{"status": "self_excluded"}` | 409 `already_excluded` |
| `POST /{player}/return` | — | 202 `{"reinstatesAt"}` | 409 `not_excluded`, `exclusion_permanent`, `exclusion_not_ended` (`endsAt`), `return_requested` |
| `GET /{player}/history` | — | 200 `[{eventType, limitType, previousValue, newValue, actor, occurredAt, description}]`, newest first | 503 |
| `PUT /{player}/reality-check` | `intervalMinutes` (number) | 200 the status | 400 `interval_required`, `invalid_interval` (`allowed: [15,30,45,60]`) |
| `DELETE /{player}/reality-check` | — | 200 the status | — |
| `POST /{player}/reality-check/acknowledge` | `sessionId` | 200 `{"acknowledged": true}` | 404 `session_not_found` |

0.13 allows two path parameters: `/{player}/limits/{type}` has two. The cooling-off `DELETE` is not
routed (405 as the original).

**The status**: `{limits: [{limitType, value, currency?}], pendingChanges: [{limitType, pendingValue|null,
appliesAt, currency?}], usedAmounts: [{limitType, usedAmount}], coolingOffUntil|null, isCoolingOff,
activeRestrictions: ["COOLING_OFF"|"SELF_EXCLUDED"], realityCheckIntervalMinutes|null}`.

## OperatorEndpoint: `/v1/casino/backoffice/<slug>/rg`, `Routes.operator`

| Route | Body | Answer | Refusals |
|---|---|---|---|
| `GET /{player}` | — | 200 the full state: the status, the exclusion, the return request, cases, history | — |
| `PUT /{player}/limits/{type}` | `value`, `currency?` | 200 the status | 400 as the player's, no minimum |
| `DELETE /{player}/limits/{type}` | — | 200 the status | 404 `no_limit` |
| `PUT /{player}/cooling-off` | `until` | 200 the status | as the player's, no `break_active` (an operator may replace) |
| `DELETE /{player}/cooling-off` | `reason` | 200 the status | 400 `reason_required`; 404 `no_break` |
| `POST /{player}/self-exclude` | `period` (`permanent`, `P183D`, `183d`) | 200 `{"status": "self_excluded"}` | 400 `invalid_period`, `period_too_short` (`minimumDays: 183`, `providedDays`); 409 `already_excluded` |
| `POST /{player}/return` | — | 202 `{"reinstatesAt"}` (the request recorded for the player) | as the player's |
| `POST /{player}/cases` | `caseType`, `note` | 201 the case | 400 `invalid_case_type` |
| `POST /{player}/cases/{caseId}/notes` | `note` | 200 the case | 404 `case_not_found` |
| `POST /{player}/cases/{caseId}/close` | `reason` | 200 the case | 404 `case_not_found`; 409 `case_closed` |
| `GET /settings`, `PUT /settings` | `minSessionMinutes?`, `maxBreakDays?` | 200 the settings | 400 `invalid_setting` |

`/settings` and `/{player}` share no prefix beyond the endpoint's (0.13 forbids two endpoints sharing a
prefix, not two routes under one).

## ServiceEndpoint: `/v1/casino/brands/<slug>/rg`, `Routes.serviceOf(...)`

The callers' contracts, as already built (`services/wallet/.../api/ResponsibleGambling.scala`,
`services/game-provider/.../clients/Neighbours.scala`, `players/clients/Rg.scala` on 008's branch). Every
answer is 200 with a decision; a 5xx or no answer is the caller's refusal.

| Route | Admitted | Body | Answer |
|---|---|---|---|
| `POST /may-deposit` | `wallet`, `players` | `playerId, currency, amount, last24h, last7d, last30d` | `{decision: allowed}` \| `{decision: excluded}` \| `{decision: cooling_off, endsAt}` \| `{decision: over_limit, period, limit, used, remaining}` |
| `POST /may-bet` | `game-provider`, `wallet` | `playerId, currency, amount, lossLast24h?, lossLast7d?, lossLast30d?` | `allowed` \| `excluded` \| `cooling_off` (`endsAt`) \| `spending_limit` (`period, limit, remaining`) \| `unavailable` (a loss limit set, no sums; R6) |
| `GET /{player}/may-play` | `game-provider` | — | 200 `{allowed: true, sessionLimitMinutes?, minutesUsed}` \| 403 `{allowed: false, reason: self_excluded \| cooling_off \| session_limit_reached, coolingOffUntil?, limitMinutes?, usedMinutes?}` |
| `POST /may-log-in` | `players` | `playerId` | `{decision: allowed}` \| `{decision: excluded}` \| `{decision: cooling_off, endsAt}` |
| `POST /gamstop-refusal` | `players` | `playerId, result` (`excluded` \| `api_error` \| `timeout`) | 204; a third-party case for `excluded` only |
| `POST /erasure/players/{player}` | `players` | — | 200 the certificate |
| `GET /exports/players/{player}` | `players` | — | 200 `{service, playerId, records}` |

`may-play` is a `GET` with the player in the path because game-provider calls it so; the other checks are
`POST`s with the player in the body, as the wallet and players call them.
