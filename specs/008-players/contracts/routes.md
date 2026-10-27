# Contract: routes of `players`, by endpoint

Six endpoints (research R14), each one prefix with the brand's slug as a literal, so every route has
at most two path parameters and no two endpoints share a prefix. Classes are spec 001's
(`contract.access.Routes`); a class in *italics* is set per route with `withAcl`. Bodies keep the
original's field names (`contracts/wire.md`). Every refusal is `{"error", "message", ...}`; the
statuses are the original's where it had the route.

## `PlayerEndpoint` — `/v1/casino/players/<slug>` — `player(slug)`

| Route | Class | Does | Answers |
|---|---|---|---|
| `POST /register` | *public* | `RegistrationWorkflow`, awaited 5 s (R3); body `email`, `password`, `currency` required; `displayName`, `firstName`, `lastName`, `country`, `language`, `dateOfBirth`, `bonusCode`, `btag`, `clickId`, `deviceFingerprint`, `emailMarketingConsent`, `smsMarketingConsent` | 201 `{playerId, status}`; 400 `missing_fields` naming them; 409 `email_already_exists`; 422 `password_too_weak`; 404 `brand_not_found`; 503 `service_unavailable` |
| `POST /login` | *public* | the realm's direct grant, `SignIn`, `may-log-in`, the session (R4) | 200 `{token, refreshToken, brandId, playerId, currency, country, pendingBonusCode}`; 401 `invalid_credentials`; 401 `email_not_verified`; 403 `account_restricted` + `reason`; 403 `cooling_off` + `until`; 401 `missing_token` |
| `POST /refresh` | *public* | the realm's refresh | 200 `{accessToken, refreshToken, expiresIn}`; 400 `missing_refresh_token`; 401 `refresh_expired` |
| `POST /verify-email` | *public* | `VerifyEmail` from the token (R16) | 200; 400 `missing_token`; 400 `invalid_or_expired_token` |
| `POST /resend-verification-email` | *public* | `ResendVerification` | 200; 429 `rate_limited` + `retry_after_seconds` |
| `POST /password-reset/request`, `POST /password-reset/confirm` | *public* | the realm's reset | 200; 400 `invalid_or_expired_token` |
| `POST /password-change` | player | verify the current password with the realm, send the confirmation | 202; 400 `missing_fields`; 400 `invalid_password` |
| `POST /password-change/verify` | *public* | change on the realm | 200; 400 `invalid_token`; 400 `expired_token`; 409 `account_suspended` |
| `POST /{playerId}/logout` | player | `SessionEndWorkflow(logout)`, awaited; revoke the realm session | 200; 403 `player_id_mismatch` |
| `GET /{playerId}` | player | `PlayerEntity.get` | 200 the profile; 404 `player_not_found` |
| `PATCH /{playerId}` | player | `EditProfile`, the allow-list read first | 200 the profile; 400 `no_fields_provided`; 400 `invalid_country` |
| `POST /{playerId}/close` | player | body `password`; verify with the realm; `Close(player, player_request)` | 200; 400 `invalid_password`; 409 `already_closed` |
| `GET /{playerId}/consent`, `PATCH /{playerId}/consent` | player | consents | 200; 400 `no_fields_provided` |
| `PATCH /{playerId}/cookie-consent` | player | `RecordCookieDecision` | 200; 400 `invalid_cookie_consent_body`; 400 `missing_device_id` |
| `GET /{playerId}/favourites`, `POST`/`DELETE /{playerId}/favourites/{gameId}` | player | favourites | 200 |
| `GET /{playerId}/recently-played` | player | `RecentlyPlayedView` | 200 |
| `GET /{playerId}/activity-history?category&page` | player | `ActivityView` | 200 |
| `GET /{playerId}/messages?page` | player | `MessagesView`, unread count | 200 `{messages, unreadCount}` |
| `POST /messages/{messageId}/read`, `POST /messages/{messageId}/delete` | player | `MessageEntity` | 200; 404 `message_not_found` |
| `GET /{playerId}/emails`, `POST /{playerId}/emails` | player | the list; `AddEmail` and the verification send | 200; 202; 400 `invalid_address`; 409 `address_already_in_list`; 409 `address_already_registered` |
| `POST /emails/verify` | *public* | `EmailAddressVerified` from the token | 200; 400 `invalid_token`; 400 `expired_token` |
| `POST /emails/{address}/resend`, `POST /emails/{address}/promote`, `DELETE /emails/{address}` | player | `PlayerEntity` | 202; 200; 404 `address_not_found`; 400 `already_verified`; 400 `not_verified`; 400 `already_primary`; 409 `email_conflict`; 400 `cannot_remove_primary` |
| `POST /{playerId}/email-change` | player | body `newEmail`, `password` | 202; 400 `missing_fields`; 400 `invalid_password`; 409 `email_already_registered` |
| `POST /email-change/verify` | *public* | `ChangeEmail`, the realm's address | 200; 400 `invalid_token`; 400 `expired_token`; 409 `email_already_registered` |
| `POST /{playerId}/deposits` | player | FR-043 (R15); body `amount` (minor units), `currency`, `method` | 200 `{redirectUrl, paymentId}`; the refusal `kyc` or `responsible-gambling` answered, naming it; 503 naming the silent service |
| `POST /{playerId}/erasure` | player | `ErasureWorkflow` | 202 `{correlationId, holdUntil}`; 409 `not_closed` |
| `POST /{playerId}/exports`, `GET /{playerId}/exports/{exportId}` | player | `ExportWorkflow`; the read | 202 `{exportId}`; 200 the response with `missing`; 409 `export_not_ready`; 404 `export_not_found`; 410 `export_expired` |

*Amended at T052–T055 (2026-10-25)*, where the original's answer is not kept:

- `GET /{playerId}` answers a built profile (`playerId`, `status`, `email`, `emailVerified`, `currency`,
  `registeredAt`, `vipTier`, `profileComplete`, `missingProfileFields` and each field held), not the
  original's raw property map, which carried reset, change and verification tokens.
- `PATCH /{playerId}`, `PATCH /consent` and `PATCH /cookie-consent` answer the original's `{updated: true}`;
  a locked field sent is dropped and still `200`, as the original.
- `GET /activity-history` keeps the original's CSV default and `format=json`, and pages (`page`, 100 a
  page) with a `category` filter where the original capped at 500.
- `POST /password-reset/confirm` answers `400 invalid_or_expired_token`: the realm's own email links to
  the realm's own page, which confirms the reset.
- The operator's `GET /players/{id}/profile` has no `rg` or `lifetimeStats` (specs 010 and 015 answer
  those); `GET /players/{id}/crm` has no `lifecycleState` or `campaignSendHistory` (spec 014's).
- *Amended at T033 (2026-10-27)*, from the recordings: the operator's profile carries the original's
  `activeSessions` (the open game sessions, `{sessionId, gameName, provider, startedAt}`; `provider` is
  `""`, no session here carrying a game type), and its `player` answers each unset profile field and no
  tier as `""`. A message's `state` is the original's `sent` | `delivered` | `read`, not `unread`; the
  operator's list carries the `subjectTemplate` and `bodyTemplate` the operator wrote (`Personal`, erased
  with the message). An export's `202` carries the original's `statusUrl`.
- The links this service emails go through `engagement` with the triggers `EmailAddressVerification`
  (an added address), `EmailChangeVerification` and `PasswordChangeVerification`.

The address in a path (`/emails/{address}/…`) is the original's shape; it is URL-encoded and never
logged (001 FR-023).

## `SessionEndpoint` — `/v1/casino/sessions/<slug>` — `player(slug)`

| Route | Does | Answers |
|---|---|---|
| `GET /players/{playerId}` | `SessionsView` by player (the original's `GET ?playerId=`) | 200; 403 `player_id_mismatch` |
| `GET /{sessionId}` | the session | 200; 404 `session_not_found` (another player's too) |
| `PATCH /{sessionId}/end` | `GameSessionEntity.End(player)`, `PlayerEntity.GameSessionEnded`; idempotent | 200 `{status: completed, sessionId, durationMinutes}`; 403 `player_id_mismatch` (another player's, as the original); 404 |

`POST /launch` is spec 003's (`game-provider`) and is not served here.

## `OperatorEndpoint` — `/v1/casino/backoffice/<slug>` — `operator(slug)`

| Route | Does | Answers |
|---|---|---|
| `GET /players?status&tier&flagged&q&page` | `PlayersView` | 200 |
| `GET /players/{playerId}/profile`, `PATCH /players/{playerId}/profile` | `get`; `EditProfile` (the same locks) | 200; 400 `no_fields_provided` |
| `PATCH /players/{playerId}/date-of-birth` | body `dateOfBirth`, `reason` | 200; 400 `reason_required`, `date_of_birth_required`, `invalid_date_of_birth`, `underage`; 409 `kyc_locked` |
| `GET /players/{playerId}/wallet` | `WalletSummaryView` | 200 |
| `GET /players/{playerId}/activity`, `GET /players/{playerId}/bets?from&to`, `GET /players/{playerId}/sessions/{sessionId}/bets` | the views | 200; 400 `invalid_date_range` |
| `GET /players/{playerId}/sessions` | `SessionsView` | 200 |
| `GET`/`PATCH /players/{playerId}/crm` | consents, suppression, tier, notes summary | 200; 400 `no_fields_provided` |
| `PATCH /players/{playerId}/suspend` | body `reason`; `Suspend`; `SessionEndWorkflow(suspended)` per open session; revoke | 200 |
| `PATCH /players/{playerId}/reinstate` | `Reinstate` | 200; 409 `not_suspended` |
| `POST /players/{playerId}/close` | `Close(operator, reason)`; disable the realm user | 200; 409 `already_closed` |
| `POST /players/{playerId}/reopen` | body `reason`; `Reopen`; enable the realm user | 200; 409 `not_closed`; 409 `hold_lapsed` |
| `PUT /players/{playerId}/vip-tier`, `PUT /players/{playerId}/pot-vip` | `SetVipTier` (previous read first), `SetPotVip` (writes a note) | 200 `{vipTier}` |
| `POST /players/{playerId}/reset-password` | the realm's reset link | 200; 404 `player_not_found` |
| `POST /players/{playerId}/sessions/{sessionId}/force-end`, `POST /players/{playerId}/sessions/force-end-all` | `GameSessionEntity.End(forced)` with the operator as the actor (audited by `GameSessionAudit`); force-end-all ends every open game session, not the player sessions, as the original | 200 `{sessionId, status: ended}`; 404 `session_not_found`; 200 `{ended}` |
| `POST /players/{playerId}/notes`, `PATCH /players/{playerId}/notes/{noteId}/pin`, `/unpin` | `NoteEntity` | 201; 200 |
| `GET /players/{playerId}/messages`, `POST /players/{playerId}/messages` | `MessagesView`; `MessageEntity.Created` (origin `operator`), merge tags rendered | 200; 201; 422 `unknown_merge_tag` + `tag`; 422 `category_required`; 422 `marketing_suppressed`; 422 `account_closed`; 404 `player_not_found` |
| `GET /merge-tags`, `POST /messages/preview` | the merge tags; a render | 200; 422; 404 |
| `POST /players/{playerId}/clear-suspect-flag` | `ClearDuplicate` | 200 |
| `GET /fraud-cases?status`, `PATCH /fraud-cases/{caseId}/review`, `PATCH /fraud-cases/{caseId}/dismiss` `{notes}` | `FraudCasesView`; `FraudCaseEntity` | 200 `{status, reviewedBy}`, and `reviewNotes` on dismiss; 404 `case_not_found` |
| `POST /players/{playerId}/activity-export`, `GET /exports/{exportId}` | `ExportWorkflow` for the player | 202 `{exportId}`; 200; 409; 404; 410 |
| `POST /players/{playerId}/erasure` | `ErasureWorkflow` | 202; 409 `not_closed` |
| `GET /players/{playerId}/erasure` | the erasure's state: `correlationId`, `holdUntil`, `status` (`held` \| `applying` \| `erased`), each service's certificate | 200; 404 `erasure_not_found` |
| `GET /players/{playerId}/verification-link` | the current link, only when `CASINO_REAL_MONEY=false` (R16) | 200; 404 on a real-money brand |
| `GET`/`PUT /brand-config`, `GET`/`PUT /currency-config`, `GET`/`PUT /allow-list` | the three key value entities; bodies are data-model.md's fields in camelCase (`singleSession`, `multiAccountDetection`, `reportingZone`, `retentionDays`, `features`; `offered`; `countries`) | 200; 400 `invalid_config` naming the field |
| (none) | spec 021's two modules routes are served by `ModulesEndpoint`, below, not here | — |

*Amended at T059–T061 (2026-10-25)*, where the original's answer is not kept or the original has none:

- Suspend, reinstate, close, reopen, `vip-tier`, `pot-vip` and `clear-suspect-flag` keep the original's
  bodies and answers (`{status}`, `{vipTier}`, `{potVip}`, `{playerId, flagCleared, linkedCasesFound}`).
  The original's suspend and reinstate never refused; here reinstating a player who is not suspended is
  `409 not_suspended`, and closing a closed account `409 already_closed`. The original's suspend,
  reinstate, vip-tier, pot-vip and clear-suspect-flag sat under `/v1/casino/{brand}/players/…` and its
  close under `/backoffice/`; all are under `/backoffice/` here.
- Reopen and the player's own close are new (FR-032a); the original had neither. With no `retentionDays`
  in `brand-config`, the hold is 1825 days (5 years).
- A closure ends the player sessions with reason `forced`; suspension and exclusion use `suspended`.
- `GET /players` keeps `search`, `status`, `vipOnly`, `page` and `pageSize` and answers `{items, total}`
  with each item's `playerId` beside the original's `twinId`; the original's `kyc`, `birthday`,
  `country`, `currency`, `onlineOnly` filters and its `facetCounts` are not served (kyc is spec 009's,
  online is the sessions view's) until the backoffice asks for them.
- `GET /players/{id}/wallet` answers minor units (`realBalanceMinorUnits`, `bonusBalanceMinorUnits`,
  `currency`) from `money.transactions`, not decimal strings; bonus and wagering are spec 011's.
- `GET /players/{id}/bets` is the settled rounds in the activity, filtered by `fromDate`/`toDate`, with
  `summary.totalBets` only; the stake and win totals wait on the round fact carrying both into the row.
  `GET /players/{id}/sessions/{sessionId}/bets` stays `501`: an activity row carries no session.
- `GET /players/{id}/sessions` lists the game sessions (the original had no operator list).
- A note's author is the operator's subject from the token, not the body's `authorName`/`authorId`;
  empty content is `400 missing_fields`; pinning a note that is not the player's is `404 note_not_found`.
- The operator's `POST /players/{id}/messages` takes `subjectTemplate`/`bodyTemplate` (or `subject`/`body`)
  and an optional `notificationId` as its idempotency key; it has no `rewardId` (spec 011).
- The configuration routes: `brand-config` carries data-model.md's fields, not the original's `lobbyUrl`,
  `depositUrl`, `jurisdiction` (the manifest's); `currency-config` is `{offered, defaultCurrency}`;
  `allow-list` is `{countries}` at `/allow-list`, without the original's per-country `POST`/`DELETE`. A
  `PUT` sets the fields it names over what is held.
- `GET /fraud-cases` answers the original's `{cases}` with `playerIds` (every player the case links)
  where the original had `playerRef` and `linkedPlayerRef`, and each case's `reviewNotes`; review and
  dismiss answer `{caseId, status, reviewedBy, reviewedAt}`, dismiss with `reviewNotes` (the body's
  `notes`, `null` when none or after a linked player's erasure blanked them). Dismissing clears the flag on every linked player (FR-037); the original left
  the flags set. `clear-suspect-flag` dismisses the player's open cases, as the original, and so clears
  the flags of the players those cases link. A second decision on a dismissed case is `409 case_closed`.
- Erasure and the export (T070–T071), which the original had neither of: the player's `GET /{playerId}`
  carries `erasureHoldUntil` while an erasure is filed; the operator's `GET /players/{id}/erasure` answers
  `{correlationId, holdUntil, status, heldFor, failedAt, certificates}` with `status` `held` | `applying` |
  `erased` | `failed` and `heldFor` `retention` | `open_withdrawal`. An export's id is the workflow's, and
  the operator reads it at `GET /exports/{exportId}`; the player's read of another player's export is
  `404`. The export answers `{service, playerId, profile, consents, sessions, activity, messages, parts,
  missing, generatedAt, expiresAt}`.
- The deposit start (T073): a refusal is `403 {error, message, service}` with the refusing service's own
  code and message (responsible gambling's `reason` passed on when it gives one), and an `ingest` refusal
  keeps ingest's status; a player who is not active is `403 account_restricted` with `reason`, before
  anything is asked; `amount` that is not a whole number above zero is `400 invalid_amount`, a currency
  that is not three letters `400 invalid_currency`.
- `platform_admin` is admitted on every operator route (contract `Routes.operator`, spec 001; the original's
  `requireBrandAccess`), so T056's "a `platform_admin` without the brand role → `403`" does not hold.

### `ModulesEndpoint` — `/v1/casino/brands/<slug>/modules` — `operator(slug)`: the operator's modules page (spec 021)

These rows are spec 021's (`specs/021-core-and-modules/contracts/routes.md`), which `players` serves
because it is the core's operator-facing service and the vendor's agent does not trust the `eitheror`
realm. Their paths are 021's and are checked by `tools/modules`' `ReadOnlyRoutesSuite`, so they keep
the `/v1/casino/brands/{brand}` prefix and are a seventh endpoint of their own rather than rows of
`OperatorEndpoint`.

| Route | Class | Does | Answers |
|---|---|---|---|
| `GET /v1/casino/brands/{brand}/modules` | `operator(brand)` | calls the vendor's agent, inside the project by its certificate, at `GET /vendor/modules` and passes the answer through: modules with `status`, `version`, `follow` (channel or pin), each service's state, `lastChange`, `dataKeptUntil`; the open requests; the names of missing keys | 200; 503 `agent unreachable` |
| `POST /v1/casino/brands/{brand}/modules/requests` | `operator(brand)` | body `{"module", "note"?}`; forwards to the agent's `POST /vendor/requests` with the operator's subject as `by` | 204; 400 unknown module; 503 |

Nothing else is served under `/modules`: no role on the installation enables, disables, pins or
moves a module (spec 021 FR-013, SC-003). The `platform_admin` role is admitted as any operator is
and gains no enable. The page's copy says "ask eitheror for this module", never "enable".

## `ServiceEndpoint` — `/v1/casino/brands/<slug>/players` — `service`

In-project callers by certificate; no route here is granted to a shared project.

| Route | Caller | Does | Answers |
|---|---|---|---|
| `GET /{playerId}/status` | `game-provider` (003 FR-007), `support-agent` (017 `get_player_state`) | `PlayerEntity.status`, display name, verification status from the kyc facts | 200 `{status, displayName, verification}`; 404 |
| `POST /{playerId}/game-sessions` | `game-provider` (003 FR-009) | body `gameId`, `currency`, `demo`, `playerSessionId`; opens the game session, schedules the limit (R5) | 201 `{sessionId}`; 403 `account_restricted`; 403 `session_limit_reached` + `limitMinutes`, `usedMinutes`; 409 `session_expired` |
| `GET /{playerId}/game-sessions`, `POST /game-sessions/{sessionId}/end` | `support-agent` (017 `get_session_history`, `close_active_session`) | `SessionsView`; `End(forced)` with the approval id as the key | 200 |
| `POST /{playerId}/suspend` | `kyc` (009 FR-025) | body `reason` (`kyc_window_expired`), `deadline`; idempotent under player and deadline | 200 |
| `PUT /{playerId}/exclusion` | `responsible-gambling` (010 FR-018, FR-019) | body `at`; `SetSelfExcluded`; sessions end `suspended`; revoke | 200 |
| `DELETE /{playerId}/exclusion` | `responsible-gambling` (010 FR-020) | `LiftExclusion` | 200; 409 `not_excluded` |
| `POST /{playerId}/messages` | `engagement` (014) | body `deliveryId`, `category`, `subject`, `body`; `MessageEntity.Created` under the delivery id, origin `crm` | 201; 422 `marketing_suppressed`; 422 `account_closed`; 404 `player_not_found` |
| `GET /config/{name}` | any brand service | `brand-config` \| `currency-config` \| `allow-list` | 200 |
| `POST /erasure/players/{playerId}`, `GET /exports/players/{playerId}` | this service's own part, the same shape every brand service serves (below) | `Erase`; `exportPart` | 200 the certificate; 200 the part |

### The two routes every brand service serves to `players`

`POST /erasure/players/{playerId}` (served today by `wallet` and `merchant`) and
`GET /exports/players/{playerId}` (new; specs 002 and 004 gain it) under `Routes.service` admitting
`players` of the brand; `merchant`'s under the brand's erasure grant (`grants.md`). The erasure
answers `{service, playerId, appliedAt, certificate}`; the export answers `{service, playerId, part}`
with `Personal` values marked. `ErasureWorkflow` and `ExportWorkflow` call them (R10).

## `RealmEndpoint` — `/internal/realm/<slug>` — `public`, verified per route

| Route | Header | Does | Answers |
|---|---|---|---|
| `POST /webhook` | `X-Webhook-Secret` | body `type`, `realmId`, `sessionId`, `userId`, `timestamp`; ignores other realms and no session id; counts `LOGIN`; `SessionEndWorkflow(logout)` on `LOGOUT`, `(expired)` on `SESSION_EXPIRED` | always 200 `{ok: true}`; 401 `unauthorized`; 503 `webhook_secret_not_configured` |
| `POST /may-sign-in` | `X-Realm-Secret` | body `email`; the status and `may-log-in` (R4), under 100 ms p99 | 200 `{allowed: true}` or `{allowed: false, reason: <status> \| cooling_off, until?}`; 401 |
| `POST /gamstop-refusal` | `X-Realm-Secret` | body `email`, `result` (`excluded` \| `api_error` \| `timeout`); passes to RG (010 FR-022) | 200; 401 |

## `GeoEndpoint` — `/v1/casino/geo` — `public`

| Route | Answers |
|---|---|
| `GET /lookup` | 200 `{country, dialCode}`, both `null` when the lookup fails (FR-006) |
