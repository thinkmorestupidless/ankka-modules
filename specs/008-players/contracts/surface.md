# Contract: the original's surface and each route's disposition

Read on 2026-10-25 from `eitheror-platform` `substrate/casino-service/` (`PlayerRoutes.scala`,
`PlayerProfileRoutes.scala`, `BackofficePlayerRoutes.scala`, `SessionRoutes.scala`,
`PlayerEmailRoutes.scala`, `PlayerEmailChangeRoutes.scala`, `KeycloakWebhookRoutes.scala`). Auth
classes are the original's: **P** a player token whose `eitheror_twin_id` must match `{playerId}`,
**BO** an operator realm role with the brand in scope (**BO[…]** with the roles listed), **PUB** none,
**SECRET** the `X-Webhook-Secret` header. Dispositions: **kept** (served here, recorded by the
driver), **kept, inspected** (served; the original cannot show it to the driver), **moved** (another
spec's), **dropped** (a stub or an alias, named in the spec), **new** (no original).

## `PlayerRoutes.scala` — `/v1/casino/players/{brandId}`

| Route | Auth | Refusals | Disposition |
|---|---|---|---|
| `GET /v1/casino/geo/lookup` | PUB | none | kept (FR-006), `GeoEndpoint` |
| `POST /register` | PUB | 409 `email_already_exists`, 404 `brand_not_found`, 422 `password_too_weak`, 400 "email, password, and currency are required" | kept (FR-001, FR-002); the message becomes `missing_fields` naming the three |
| `POST /login` | PUB | 403 `account_restricted` + `reason`, 401 `invalid_credentials`, 401 `email_not_verified`, 400 | kept (FR-010, FR-011); `cooling_off` added |
| `POST /refresh` | PUB | 400 `missing_refresh_token`, 401 `refresh_expired`, 500 `refresh_failed`/`refresh_error` | kept (FR-014); the 500s become 503 |
| `POST /verify-email` | PUB | 400 `missing_token`, 400 `invalid_or_expired_token` | kept (FR-005) |
| `POST /resend-verification-email` | PUB | 400, 429 `rate_limited` + `retry_after_seconds: 300` | kept (FR-005, R16) |
| `POST /password-reset/request`, `/password-reset/confirm` | PUB | 400 `invalid_or_expired_token` | kept (FR-027) |
| `POST /password-change` | P | 400 `missing_fields`, 400 `invalid_password` | kept (FR-027) |
| `POST /password-change/verify` | PUB | 400 `missing_fields`, 409 `account_suspended`, 400 `invalid_token`, 400 `expired_token` | kept (FR-027) |
| `POST /{playerId}/logout` | P | 403 `player_id_mismatch` | kept (FR-014) |
| `GET /{playerId}/messages` | P | 403 `player_id_mismatch` | kept (FR-030) |
| `POST /messages/{messageId}/read`, `/delete` | P | 403 `player_id_missing` | kept (FR-030); the refusal is `missing_token` (001 FR-020) |
| `GET /{playerId}/kyc/documents/{docKey}/view-url` | P | 403 `forbidden`, 503 `storage_not_configured`, 404 `document_not_found` | moved to 009 |
| `GET /kyc/requirements` | PUB | none | moved to 009 |
| `GET /me/activity-history` | P | 401 `unauthorized` | kept (FR-029); `/me` is `{playerId}` here (two-parameter rule) |
| `GET /me/recently-played` | P | 401 | kept (FR-029) |
| `GET /me/favourites`, `POST`/`DELETE /me/favourites/{gameId}` | P | 401 | kept (FR-029) |
| `GET`/`PATCH /{playerId}/consent` | P | 403, 400 `no_fields_provided` | kept (FR-028) |
| `PATCH /{playerId}/cookie-consent` | P | 403, 400 `invalid_cookie_consent_body`, 400 `missing_device_id` | kept (FR-028) |
| `GET /{playerId}` | P | 403, 400 "invalid playerId format", 404 `player_not_found` | kept (FR-023) |
| `PATCH /{playerId}` | P | 403, 400 `no_fields_provided`, 400 `invalid_country` | kept (FR-024, FR-025) |
| — | P | — | new: `POST /{playerId}/close` (FR-032a), `POST /{playerId}/deposits` (FR-043, the original's `casino-service` cashier start), `POST /{playerId}/erasure`, `POST /{playerId}/exports`, `GET /{playerId}/exports/{exportId}` (FR-038, FR-040) |

## `PlayerEmailRoutes.scala`, `PlayerEmailChangeRoutes.scala` — `/v1/casino/players/{brandId}/emails`, `/email-change`

| Route | Auth | Refusals | Disposition |
|---|---|---|---|
| `GET /emails` | P | none | kept (FR-026) |
| `POST /emails` | P | 400 `invalid_address`, 409 `address_already_in_list`, 409 `address_already_registered`; 202 | kept |
| `POST /emails/verify` | PUB | 400 `invalid_token`, 400 `expired_token` | kept |
| `POST /emails/{address}/resend` | P | 404 `address_not_found`, 400 `already_verified`; 202 | kept |
| `POST /emails/{address}/promote` | P | 404, 400 `not_verified`, 400 `already_primary`, 409 `email_conflict` | kept |
| `DELETE /emails/{address}` | P | 404, 400 `cannot_remove_primary` | kept |
| `POST /email-change` | P | 400 `missing_fields`, 400 `invalid_password`, 409 `email_already_registered`; 202 | kept |
| `POST /email-change/verify` | PUB | 400 `missing_fields`, 400 `invalid_token`, 400 `expired_token`, 409 | kept |

## `SessionRoutes.scala` — `/v1/casino/sessions/{brandId}`

| Route | Auth | Refusals | Disposition |
|---|---|---|---|
| `GET ?playerId=` | P | 403 `player_id_mismatch` | kept (FR-021); `GET /players/{playerId}` here |
| `GET /{sessionId}` | P | 404 `session_not_found` | kept |
| `PATCH /{sessionId}/end` | P | 403, 404 `session_not_found` | kept (FR-021); idempotent |
| `POST /launch` | P | `account_restricted`, `PLAY_BLOCKED_KYC`, `cooling_off_active`, `session_limit_reached`, `RATE_LIMITED`, … | moved to 003 (its FR-007); `players` serves the open to `game-provider` on the service endpoint (FR-017) |

## `PlayerProfileRoutes.scala` — `/v1/casino/backoffice/{brandId}` (BO)

| Route | Refusals | Disposition |
|---|---|---|
| `GET`/`PATCH /players/{playerId}/profile` | 400 `no_fields_provided` | kept (FR-023, FR-034) |
| `GET /players/{playerId}/wallet` | none | kept (FR-034, `WalletSummaryView`) |
| `GET /players/{playerId}/activity` | none | kept (FR-034) |
| `GET /players/{playerId}/bets` | 400 `invalid_date_range` | kept (FR-034, from `casino.rounds`) |
| `GET`/`PATCH /players/{playerId}/crm` | 404, 400 | kept (FR-034) |
| `POST /players/{playerId}/close` | none | kept (FR-032); `POST /players/{playerId}/reopen` new (FR-032a) |
| `POST /players/{playerId}/notes`, `PATCH .../notes/{noteId}/pin`, `/unpin` | none | kept (FR-034) |
| `POST /players/{playerId}/messages` | 422 `unknown_merge_tag`, `category_required`, `marketing_suppressed`, `account_closed`, 404, 400 `invalid_reward_id` | kept (FR-030); merge tags are rendered here as today |
| `GET /players/{playerId}/sessions/{sessionId}/bets` | 500 | kept (FR-034) |
| `POST /players/{playerId}/sessions/{id}/force-end`, `/sessions/force-end-all` | 501 `not_implemented` | dropped here as a stub; the working pair is `BackofficePlayerRoutes`' below |
| `POST /players/{playerId}/activity-export`, `GET /exports/{exportId}`, `/exports/{exportId}/download` | 404 `export_not_found`, 410 `export_expired`, 409 `export_not_ready` | kept (FR-040), the body extended (R10) |

## `BackofficePlayerRoutes.scala` — `/v1/casino` (BO unless noted)

| Route | Refusals | Disposition |
|---|---|---|
| `POST /backoffice/{brandId}/{playerId}/kyc/approve` | none | moved to 009 |
| `GET /backoffice/{brandId}/players` | none | kept (FR-034, `PlayersView`) |
| `GET /backoffice/{brandId}/wallets` | 503 | moved to 002 (the wallet's operator list) |
| `GET /backoffice/{brandId}/players/{playerId}/messages` | none | kept (FR-030) |
| `GET /brands/{brandId}/merge-tags`, `POST /brands/{brandId}/messages/preview` | 422, 404 | kept (FR-030), under the operator prefix |
| `/{brandId}/players/{playerId}/kyc/…` (player and operator sides) | many | moved to 009 |
| `POST /{brandId}/players/{playerId}/clear-suspect-flag` | none | kept (FR-037); `PATCH /backoffice/{brandId}/fraud-cases/{caseId}/review`, `/dismiss` kept with it |
| `PATCH /{brandId}/players/{playerId}/date-of-birth` | 400 `reason_required`, `date_of_birth_required`, `invalid_date_of_birth`, `underage` | kept (FR-025) |
| `PATCH …/suspend`, `…/reinstate` | none | kept (FR-032) |
| `POST …/self-exclude` | 400 `period_too_short` | moved to 010 (its FR-019); `players` sets the status on 010's command |
| `PUT …/vip-tier`, `PUT …/pot-vip` | none | kept (FR-033) |
| `POST …/reset-password` | 404 | kept (FR-033) |
| `POST …/sessions/{sessionId}/force-end`, `…/sessions/force-end-all` | none | kept (FR-021) |
| `GET …/rg`, `PUT`/`DELETE …/rg/limits/{limitType}`, `…/rg/cooling-off`, `POST /{brandId}/rg/cases/close` | 400 | moved to 010 |
| `POST /{brandId}/wallets/{playerId}/adjust` | 400, 409 | moved to 002 |
| `GET`/`PUT /{brandId}/brand-config` | none | kept (FR-041) |

## `KeycloakWebhookRoutes.scala`

| Route | Auth | Refusals | Disposition |
|---|---|---|---|
| `POST /internal/keycloak-webhook` | SECRET | 503 `webhook_secret_not_configured`, 401 `unauthorized`; else 200 `{ok:true}` | kept (FR-008), at `/internal/realm/<slug>/webhook`; the realm's listener is pointed at it |
| — | SECRET | — | new: `POST /internal/realm/<slug>/may-sign-in`, `POST /internal/realm/<slug>/gamstop-refusal` (FR-013) |

## Not routes

| Thing | Disposition |
|---|---|
| `KeycloakSessionReconciler` (60 s) | kept as the `realm-reconcile` recurring timer (FR-009) |
| `close_child_sessions` (Go capability, TL trigger) | kept as `SessionEndWorkflow` (FR-020) |
| `session_duration_limit_check.tl` (hourly, empty rule) | replaced by a timer per game session (FR-019) |
| `player_login_failure_lockout.tl` (`{ }`) | dropped (Clarifications) |
| `MultiAccountDetector` (scan) | kept by lookup token (FR-036) |
| `EmailVerificationTokenStore` (stateless HMAC, 24 h) | kept (FR-005) |
| the affiliate `notifyRegistration` call and `affiliate_id`, `btag`, `affiliate_attributed_at` | dropped; `attribution` binds from the fact |
| `identity_hash`, `device_fingerprint` twin properties | replaced by the lookup tokens (R9); the fingerprint stays as a `Personal` field |
| the derived twin id `UUID.nameUUIDFromBytes("{brand}:{email}")` | dropped; the id is minted |
