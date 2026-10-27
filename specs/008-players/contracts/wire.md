# Contract: the parity translation and the refusal texts

The driver records the original's paths and bodies (research R1); the parity suite replays them
through one documented translation, as the wallet's `WireTranslation` does (spec 002 `wire.md`).
Everything not in this table is replayed as recorded. The recording wins over this file: a message
here is the plan's reading of the Scala; the driver's recordings confirm or correct it.

## Paths

| Recorded (the original) | Replayed (this service) |
|---|---|
| `/v1/casino/players/{brand}/me/activity-history`, `/me/recently-played`, `/me/favourites…` | `/v1/casino/players/{brand}/{player}/activity-history`, `…/recently-played`, `…/favourites…` |
| `/v1/casino/players/{brand}/emails…` | `/v1/casino/players/{brand}/{player}/emails` for the list and add; `/v1/casino/players/{brand}/emails/{address}/…` and `/emails/verify` unchanged |
| `/v1/casino/players/{brand}/email-change` | `/v1/casino/players/{brand}/{player}/email-change`; `/email-change/verify` unchanged |
| `/v1/casino/sessions/{brand}?playerId={player}` | `/v1/casino/sessions/{brand}/players/{player}` |
| `/v1/casino/{brand}/players/{player}/suspend` (and `reinstate`, `vip-tier`, `pot-vip`, `reset-password`, `date-of-birth`, `clear-suspect-flag`, `sessions/…/force-end`, `sessions/force-end-all`) | `/v1/casino/backoffice/{brand}/players/{player}/…` |
| `/v1/casino/{brand}/brand-config` | `/v1/casino/backoffice/{brand}/brand-config` |
| `/v1/casino/backoffice/{brand}/players/{player}/activity-export` | unchanged; the response body gains `missing` and the other services' parts (FR-040; the recorded fields are compared, the added ones ignored) |
| `/v1/casino/brands/{brand}/merge-tags`, `/messages/preview` | `/v1/casino/backoffice/{brand}/merge-tags`, `…/messages/preview` |
| `/internal/keycloak-webhook` | `/internal/realm/{brand}/webhook` |
| `PATCH /v1/casino/backoffice/{brand}/fraud-cases/{case}/review` | unchanged |

## Bodies

| Recorded | Replayed |
|---|---|
| `register`: `trackingContext.btag` | `btag` (both accepted on input) |
| `login` answer: `token`, `refreshToken`, `brandId`, `playerId`, `currency`, `country`, `pendingBonusCode` | unchanged; the token values are `vary` |
| a refusal `{"error": "x"}` | `{"error": "x", "message": "…"}`; the comparison is on the recorded fields |
| a refusal `{"error": "account_restricted", "reason": "suspended"}` | unchanged |
| `400 {"error": "email, password, and currency are required"}` (recorded at T029; the plan read it as plain text) | `400 {"error": "missing_fields", "fields": ["email", "password", "currency"]}` — a listed divergence |
| Akka's rejection page for a missing or invalid token | `401 {"error": "unauthenticated"}` — a listed divergence |
| `500 refresh_failed`, `500 refresh_error` | `503 service_unavailable` — a listed divergence |
| `403 player_id_missing` on the message routes | `401 missing_token` (001 FR-020) — a listed divergence |
| `POST /register` answer `201 {playerId, status}` | unchanged; `playerId` is `vary` (minted, not derived) |
| a fraud case's `status` `OPEN` \| `REVIEWED` \| `DISMISSED` | unchanged on the wire; stored lower-case (data-model.md) |

## Placeholders the driver writes

`{brand}`, `{player}` (captured from the registration's `playerId`), `{email}` (the address the
scenario minted, redacted to a placeholder by `merge`), `{session}` (from the login's realm session,
read back from the sessions route), `{game}`, `{message}`, `{case}`, `{export}`, `{note}`. Passwords,
tokens and refresh tokens are redacted by `merge` and compared for presence only.

*Amended at T033 (2026-10-27)*, from the recordings themselves: `merge` wrote the driver's own
placeholders (`{email}`, `{otherEmail}`, `{secondEmail}`, `{link}`, `{otherLink}`, `{secondLink}`,
`{changeToken}`, `{device}`, `{surname}`, `{youngDate}`, `{caseId}`) and the tokens' capture names in
`Authorization` (`{token}`, `{otherToken}`, `{operatorToken}`, `{outsiderToken}`), and redacted every
`password` and `refreshToken`. The replay (`Parity.Replaying`) fills them with its own:

| Recorded | Replayed |
|---|---|
| an address, `{device}`, `{surname}` | minted once per replay (the entities outlive a test); the answer carries the placeholder back |
| `{link}`, `{otherLink}`, `{secondLink}`, `{changeToken}` | the token of the last link `engagement` was asked to send to the matching address |
| `{youngDate}` | today less seventeen years |
| `{caseId}`, or a case id of the original's own suite | the case of `FraudCasesView` that links one of the replay's players |
| `password: <redacted>` | the driver's: `Players-Driver-1!`; `a` in a step named for a weak password; `Players-Driver-2!` in one named for the new password |
| `refreshToken: <redacted>` | the first sign-in's refresh token |
| `Bearer {token}` and the other players' | the token this service answered under that capture name |
| `Bearer {operatorToken}` | an operator of the brand, as the realm user the recordings' operator was (`reviewedBy` names it) |
| `Bearer {outsiderToken}` | an operator with no role at the brand |
| no `Authorization` on a backoffice path of the original's own suite (`TestScenario…`) | the operator: that suite was recorded without its headers |
| an answer's `<redacted>` field, the replay's slug | `<redacted>` where present; the recorded brand |

A read (`GET`) that differs is asked again for up to five seconds: the reads here are views, a moment
behind the write, where the original read its twin at once. Where a divergence holds, the read is asked
again until the difference shows, since that is what it settles to.

## Steps of other services

A recorded step on another service's route that changes a player here is made through the route that
service calls on this one, and answered as recorded except where this service's part shows:

| Recorded | Replayed |
|---|---|
| `POST /v1/casino/sessions/{brand}/launch` (`game-provider`, spec 003) | `POST /v1/casino/brands/{brand}/players/{player}/game-sessions` as `game-provider`, with the player session of the step's token; answered `200 {sessionId}` with the recorded `gameUrl` and `launchToken` (`game-provider`'s), or this service's refusal |
| `POST /v1/casino/{brand}/players/{player}/self-exclude` (`responsible-gambling`, spec 010) | `PUT /v1/casino/brands/{brand}/players/{player}/exclusion` as `responsible-gambling`; answered as recorded |
| `setup:` `POST /twins/casino-{brand}/{player}` `{"account_status": "self_excluded"}` | the same exclusion |
| `setup:` the twin write of `blocked` | nothing: only the migration sets `blocked` (FR-031), a listed divergence |
| `/v1/casino/bonus/…` (`rewards`, spec 011), the realm's and the twins' own routes | answered as recorded |

## Refusal messages (the plan's reading; the recordings confirm)

| Code | Message |
|---|---|
| `email_already_exists` | An account with this email address already exists |
| `password_too_weak` | The password does not meet the brand's policy |
| `account_restricted` | This account is {reason} |
| `cooling_off` | A break is in place until {until} |
| `invalid_credentials` | The email address or password is wrong |
| `email_not_verified` | Verify your email address before signing in |
| `player_id_mismatch` | The token is not this player's |
| `missing_token` | The token carries no player id |
| `invalid_country` | The brand does not serve {country} |
| `no_fields_provided` | Nothing to change |
| `rate_limited` | Wait {retry_after_seconds} seconds before asking again |
| `session_not_found` | No such session |
| `session_limit_reached` | The session limit of {limitMinutes} minutes is reached ({usedMinutes} used) |
| `underage` | The player must be 18 or over |
| `not_closed` | Only a closed account can be erased or reopened |
| `hold_lapsed` | The retention hold has passed; the account cannot be reopened |
| `marketing_suppressed` | This player receives no marketing messages |
| `account_closed` | This account is closed |
| `export_not_ready` | The export is being assembled |
| `export_expired` | The export is no longer available |
