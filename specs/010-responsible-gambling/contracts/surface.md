# The original's responsible-gambling surface, disposed of

Read 2026-10-27 from `eitheror-platform` (`CS` = `substrate/casino-service/src/main/scala/io/eitheror/casino/`).
Each element is **kept** (same path and shape, through `contracts/wire.md`), **moved** (another path or
owner), **changed** (a *Change from the original*), **dropped**, or **new**.

## Player routes: `CS/RgRoutes.scala`, prefix `/v1/casino/rg/{brand}/{player}`

| Element | Disposition |
|---|---|
| `GET /limits` → the status (`limits`, `pendingChanges`, `usedAmounts`, `coolingOffUntil`, `isCoolingOff`, `activeRestrictions`, `realityCheckIntervalMinutes`) | kept; `usedAmounts` from `UsageEntity` (R7); `isCoolingOff` true while a break stands (the original reads only the flag); a failed usage read still answers 200 with `usedAmounts: []`, as the original |
| `PUT /limits/{type}` `{"value": "<string>"}` | kept: the more-restrictive rule, 24 h staging, `invalid_value`, `below_minimum_session_minutes` with `minimumMinutes`; **changed**: a money limit takes `currency` (FR-006; default the player's one wallet currency, as the original's twin `currency`); `unknown limit type: X` becomes `400 unknown_limit_type` (wire.md) |
| `DELETE /limits/{type}` | kept: always stages a removal, 24 h; **changed**: the staged removal applies (FR-005) |
| `PUT /cooling-off` `{"until"}` | kept path and body; **changed**: bounds (1 h to the brand's maximum, FR-014), `break_active` while one stands (FR-015), the case opened `open`, the break ending by timer (FR-017) |
| `DELETE /cooling-off` → 405 | kept (no player route to end a break) |
| `PUT /permanent-exclusion` → `{"status": "self_excluded"}` | kept; **changed**: the account status set by `players` through its route (R4), sessions ended there; no `rg_cool_off_until` year 9999 (the exclusion is its own state, FR-021) |
| `GET /history` | kept path; **changed**: every limit change and case event with actors (FR-027); errors answer 503, not `[]` |
| `PUT /reality-check` `{"intervalMinutes"}`, `DELETE /reality-check` | kept, `invalid_interval` with `allowed` |
| `POST /reality-check/acknowledge` `{"sessionId"}` → `{"acknowledged": true}` | kept; **changed**: a session not the player's refused (FR-024) |
| — | **new**: `POST /return` (the player asks to return after a period exclusion, FR-020) |
| The `{brand}` segment unchecked against the token | **changed**: the slug is a literal in the prefix (001 FR-020) |

## Operator routes: `CS/BackofficePlayerRoutes.scala`, prefix `/v1/casino/{brand}/players/{player}`

| Element | Disposition |
|---|---|
| `GET /rg` (small status) | **moved** to `/v1/casino/backoffice/<slug>/rg/{player}` and **changed** to the full state (FR-025) |
| `PUT /rg/limits/{type}`, `DELETE /rg/limits/{type}` | moved; **changed**: a positive whole value checked, the pending change cancelled (FR-004), a fact and an audit record (FR-028) |
| `PUT /rg/cooling-off`, `DELETE /rg/cooling-off` | moved; **changed**: bounds; the lift takes a reason and closes the case `lifted` (FR-016) |
| `POST /self-exclude` `{"period"}` and `parseDays` | moved; `period_too_short` with `minimumDays`, `providedDays` kept; **changed**: an unreadable period is `400 invalid_period` (the original accepts it as permanent); a case opened |
| `PATCH /reinstate` | stays `players`' for a suspension (008); for an exclusion **changed** into the return request (FR-020), on this service: `POST …/rg/{player}/return` |
| `POST /v1/casino/{brand}/rg/cases/close` `{"caseId"}` | moved to `POST …/rg/{player}/cases/{caseId}/close` with a reason |
| — | **new**: open a case with a note, note a case (FR-026) |
| `GET/PUT /v1/casino/brands/{brand}/rg-config` `{"minSessionMinutes"}` | moved to `…/backoffice/<slug>/rg/settings`, with `maxBreakDays` (FR-029, FR-014) |

## Enforcement points

| Element | Disposition |
|---|---|
| `POST /internal/v1/casino/{brand}/notify/deposit` (after credit, 403 logged) | **changed** into `may-deposit`, asked by the wallet before credit (FR-007); its KYC checks are 009's; its stats and fire-and-forget calls are the wallet's and `players`' |
| Launch's cooling-off and `SESSION_MINUTES` checks (`CS/SessionRoutes.scala`) | **moved** into `may-play`, asked by game-provider, which keeps the original's launch bodies (`cooling_off_active`, `session_limit_reached`, `account_restricted`) |
| ST8 `isCoolingOff`, `isPlayerLocked` (reads `status`, never set) | **moved** into `may-bet`, asked by game-provider; **changed**: fails closed, reads the profile |
| `RgStatusAuthenticator` (reads the twin) | **moved**: the realm asks `players` (008 FR-013), which asks `may-log-in` here |
| `GamstopAuthenticator` | kept in Keycloak; **new**: its refusal reported to `players`, then here by player id (FR-022, R12) |

## TL, twins and events

| Element | Disposition |
|---|---|
| `rg-pending-apply`, `apply_pending_rg_limits` (never registered) | dropped; the pending timer (FR-005) |
| `rg-cool-off-expiry`, `check_cool_off_expiry` (disabled, mismatched) | dropped; the break timer (FR-017) |
| `rg-deposit-limit-monitor`, `rg-loss-limit-monitor`, `rg-deposit-limit-pending-apply`, `session-duration-limit-check` (empty or placeholder) | dropped |
| `export-rg-case` (`rg_case_opened`, `rg_case_closed`; `occurred_at` the close's `closes_at`) | **changed**: `RgPublisher`, the close's time is when it closed |
| `export_account_event` for `self_excluded`, `reinstated` | `players`' (008), on the status this service sets |
| `CrmService` `deposit_limit_set`, `loss_limit_set`, `session_limit_set`, `rg_limit_loosened`; TL `cooling_off_set`, `self_exclusion_activated` | moved to spec 014, derived from `rg_limit_applied` and `rg_case_opened` |
| The `rg_*` twin properties, `RGCase` twins, `rg-case.*` concepts, `HAS_RG_CASE` edges | dropped; the profile (R2) |
| `min_session_minutes` on the sub-brand twin | moved to `BrandSettingsEntity` |
