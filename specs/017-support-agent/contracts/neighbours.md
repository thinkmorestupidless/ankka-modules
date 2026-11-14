# Neighbours: what the tools call, and the amendments they need

Every call is made within the brand's project through `context.services`, as `support-agent`, by
identity (029 FR-010). It times out after 5 s, with one retry on a timeout, as the original's
substrate client did. The player id is the one bound to the conversation, never one the model
produced (FR-011). The currency is the one players' status answers (amendment P2).

## Reads

| Tool | Call | Mapped to the model | Today |
|---|---|---|---|
| `get_player_state` | players `GET /v1/casino/brands/<slug>/players/{p}/status`; with `personalFields`, also `GET …/{p}/agent-profile?fields=` (P3) | `status`, `verification`, `displayName`, `currency`, and each named field | status: admitted |
| `get_wallet_balance` | wallet `GET /v1/casino/brands/<slug>/wallets/{p}/{currency}/balance` | `real_balance`, `restricted_balance`, `currency` (minor units); 404 → zeros (as the original) | admitted |
| `get_active_bonuses` | rewards `GET /v1/casino/brands/<slug>/rewards/{p}/grants`, keeping `status = active` | `bonus_id`, `name`, `wagering_requirement`, `wagering_progress`, `expires_at`, `status` | admitted |
| `get_transaction_history` | wallet `GET …/wallets/{p}/{currency}/transactions?page=0&pageSize={limit}` (limit 1..50, default 10) | `transaction_id`, `type`, `amount`, `currency`, `status`, `created_at`, `reference` (= `paymentReference`); never `reason` | admitted |
| `get_session_history` | players `GET …/players/{p}/game-sessions` | `session_id`, `game_id`, `status`, `started_at`, `closed_at` | admitted |
| `get_rg_status` | responsible-gambling `GET /v1/casino/rg-service/<slug>/{p}/limits?currency=` (R1) | `deposit_limit_amount`, `deposit_limit_period`, `deposit_limit_pending`, `…_pending_amount`, `…_pending_effective`, `cool_off_until`, `self_excluded` | **amendment R1** |

`game_name` (original) is not served by players. The tool returns `game_id`, a divergence named in
plan.md.

## Writes (after approval; the key is the approval id)

| Tool | Call | `action_result.detail` (the original's) | Today |
|---|---|---|---|
| `request_withdrawal(amount, currency)` | payouts `POST /v1/casino/payouts-service/<slug>/players/{p}/withdrawal-requests` `{amount, currency, key}` (A1: payouts picks the terminal and destination) | `"{requestId} submitted"` | **amendment A1** |
| `close_active_session(game_session_id)` | players `POST …/players/game-sessions/{s}/end` `{approvalId}` | `"Session {id} closed"` | key: **amendment P1** |
| `set_deposit_limit(amount, period)` | RG `PUT /v1/casino/rg-service/<slug>/{p}/limits/DEPOSIT_{PERIOD}` `{value, currency, key}` (R2) | `"Limit set. Effective from: {appliesAt or now}"` | **amendment R2** |
| `apply_cool_off(cool_off_until)` | RG `PUT …/{p}/cooling-off` `{until, key}` (R3) | `"Cool-off in effect until {until}"` | **amendment R3** |
| `apply_self_exclusion()` | RG `PUT …/{p}/permanent-exclusion` `{key}` (R4) | `"Self-exclusion applied at {at}"` | **amendment R4** |

The summary before a withdrawal reads the wallet's hold preview
(`GET …/wallets/{p}/{currency}/holds/preview?amount=`, admitted) and names any forfeit.
The conversation keeps the forfeit the summary named (`ApprovalRequested.forfeit`). When the
approved withdrawal runs, the preview is read again. `confirmForfeit` is sent true only when the
summary named a forfeit and the preview now reads no larger. Otherwise it is sent false, so payouts
answers its confirm-forfeit refusal when one is due, and `action_result` carries that refusal
(settled with the user, 2026-11-11, at review).

A refusal from the service, such as RG's `break_active` or payouts' `KEY_REUSED`, is
`action_result` `{success: false, detail: <code and message>}`, and the model is told the refusal.

## Amendments (each a change to that service's spec, contract and suites, built in this branch)

| Id | Service (spec) | Change |
|---|---|---|
| **A1** | payouts (007) | Endpoint prefix `/v1/casino/payouts-service/<slug>`. ACL `Routes.serviceOf("support-agent")`. `POST /players/{playerId}/withdrawal-requests` runs the same `WithdrawalRequest` workflow as the player route, with the body's `key`, `KEY_REUSED` and `MISSING_KEY` as there. A body with no `terminalId` is given the player's default active destination and the first eligible terminal (`Eligibility`'s order) that pays to a saved destination and is neither crypto nor Gigadat; a player with neither is 409 `NO_WITHDRAWAL_ROUTE`, which the tool tells the model so it points the player to the cashier (settled with the user, 2026-11-09). 007's `contracts/routes.md` gains the row; `ServiceEndpoint` stays `serviceOf("players")`. |
| **R1** | responsible-gambling (010) | Endpoint prefix `/v1/casino/rg-service/<slug>`. ACL `Routes.serviceOf("support-agent")`. `GET /{player}/limits?currency=` answers what the player route answers (FR-025). |
| **R2** | responsible-gambling (010) | Same endpoint: `PUT /{player}/limits/{type}` `{value, currency?, key}`. The same command as the player's. A repeat key answers the first outcome; the same key with another body is 409 `key_reused`. |
| **R3** | responsible-gambling (010) | `PUT /{player}/cooling-off` `{until, key}`, keyed as R2. |
| **R4** | responsible-gambling (010) | `PUT /{player}/permanent-exclusion` `{key}`, keyed as R2. A repeat answers 200 `{status:"self_excluded"}`, not 409. |
| **P1** | players (008) | `POST /game-sessions/{sessionId}/end` keeps `{approvalId}` as the actor, and records it as the key. A repeat answers the first outcome; another session under the same key is 409 `key_reused`. |
| **P2** | players (008) | `GET /{playerId}/status` also answers `currency`, the player's registered currency. Additive. |
| **P3** | players (008) | `GET /{playerId}/agent-profile?fields=a,b`. ACL `serviceOf("support-agent")`. Answers only fields spec 001 FR-023 names personal and players holds (`email`, `firstName`, `lastName`, `country`, `language`, `dateOfBirth`); any other name is 400 `unknown_field`. |

RG's keys are kept the way the wallet keeps recent keys. `RgKeys` is a key value entity per player
holding the last 64 keys with their outcomes; a key older than those answers `key_expired`. The
spec 010 amendment says so.
