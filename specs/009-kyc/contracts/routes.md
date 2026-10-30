# Routes: kyc

There are four endpoints, each with one prefix (research R14). Bodies and answers are camelCase JSON.

Every refusal is `{error: <code>, message}` with the status shown. A gate's refusal is an answer, and
its status is 200 (R5).

## `PlayerEndpoint`: `/v1/casino/kyc/<slug>`

ACL: `Routes.player(slug)`. A `{playerId}` other than the token's `Routes.playerId` is refused 403
`player_id_mismatch`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `GET /{playerId}/documents` | — | `{status, mode, categories: [{category, requiredByPolicy, requiredOfPlayer, document?}], other: [document], missingProfileFields}` | — |
| `POST /{playerId}/uploads` | `{category, contentType}` | `{documentId, url, objectName, contentType, expiresAt}` | 400 `invalid_category`, 400 `file_rejected`, 409 `document_locked`, 409 `account_closed`, 503 `storage_unavailable` |
| `POST /{playerId}/documents` | `{documentId}` | `{document, status}` | 404 `document_not_found`, 404 `upload_not_found`, 410 `upload_expired`, 400 `file_rejected` (names the rule; the object is deleted), 503 `storage_unavailable` |
| `GET /{playerId}/views/{documentId}` | — | `{url, expiresAt}` | 404 `document_not_found`, 410 `document_erased`, 503 `storage_unavailable` |

A `document` is `{documentId, category, status: pending|approved|rejected, uploadedAt, reviewedAt?,
reasonCode?, submittedBy: player|support}`. A rejection's `reasonCode` is shown only while that
document is the category's latest (FR-011).

## `PublicEndpoint`: `/v1/casino/kyc-requirements/<slug>`

ACL: `Routes.public`.

| Method and path | Answer |
|---|---|
| `GET /` | `{requiredDocTypes: [...]}`, nothing else (FR-029) |

## `OperatorEndpoint`: `/v1/casino/backoffice/<slug>/kyc`

ACL: `Routes.operator(slug)`. Each route also checks a role:

- **Player routes:** the compliance roles `brand_admin`, `brand_compliance`, `compliance_officer`,
  `resource_admin`, `platform_admin`.
- **Policy routes:** the same five.
- **Any other role** is refused 403 `forbidden`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `GET /policy` | — | the policy, every field, with `null` for none, and `isDefault` | — |
| `PUT /policy` | any policy fields | the policy, with the profile `CUSTOM` | 400 `invalid_policy` (naming the field), 400 `currency_required` |
| `POST /policy/profile` | `{profile}` | the policy | 400 `profile_required`, 400 `unknown_profile` with `knownProfiles` |
| `GET /queue?filter=&reason=&sort=` | — | `{rows: [...], counts: {awaiting, pending, verified, rejected, unverified, ready, threshold, sourceOfFunds, window, largeDeposit}}` | 400 `invalid_filter` |
| `GET /{playerId}` | — | the player read plus the operator's fields: reviewer per document, deadline, `sof`, `depositsByCurrency`, flags, `readyAt` | 404 `player_not_found` |
| `POST /{playerId}/uploads` | `{category, contentType, reason}` | as the player's | as the player's; 400 `reason_required` |
| `POST /{playerId}/documents` | `{documentId}` | as the player's | as the player's |
| `GET /{playerId}/views/{documentId}` | — | `{url, expiresAt}` | as the player's |
| `POST /{playerId}/reviews/{documentId}` | `{outcome: approved|rejected, reasonCode?}` | `{document, status}` | 400 `invalid_rejection_reason`, 404 `document_not_found`, 409 `document_already_reviewed` |
| `PUT /{playerId}/mode` | `{mode}` | `{mode, status, readyAt}` | 400 `invalid_mode` |
| `PUT /{playerId}/status` | `{status: pending|verified}` | `{status}` | 400 `invalid_status`, 409 `profile_incomplete` with `missingProfileFields` |
| `PUT /{playerId}/required` | `{categories}` | `{categories, mode, status}` | 400 `invalid_category` |
| `POST /{playerId}/override` | `{status: verified|rejected, reason?}` | `{status, by, at}` | 400 `invalid_status` |
| `POST /{playerId}/sof` | `{status: cleared|unverified}` | `{sof}` | 400 `invalid_status` |
| `POST /{playerId}/flags/{reason}/dismiss` | `{note}` | `{flags}` | 404 `flag_not_found`, 400 `note_required` |

## `ServiceEndpoint`: `/v1/casino/brands/<slug>/kyc`

ACL: `Routes.service()`, and per route `Routes.serviceOf(...)`.

| Method and path | Caller | Body | Answer |
|---|---|---|---|
| `POST /deposit-check` | `players` | `{playerId, amount, currency?}` | `{allowed: true}` or `{allowed: false, code, message, kycStatus?}` |
| `GET /{playerId}/play-check` | `game-provider` | — | `{allowed: true}` or `{allowed: false, error: "PLAY_BLOCKED_KYC", code: "PLAY_BLOCKED_KYC", kycStatus, message}` |
| `POST /erasure/players/{playerId}` | `players` | — | `{service: "kyc", playerId, objectsDeleted, recordsKept, erasedAt}` |
| `GET /exports/players/{playerId}` | `players` | — | `{documents: [...], status, mode, sof, flags, deadline}`, with no object |

A player with no record is answered as one with an empty record: unverified, no documents.

## Calls out

| To | Path | When | On failure |
|---|---|---|---|
| `players` | `POST /v1/casino/brands/<slug>/players/{playerId}/suspend` `{reason: "kyc_window_expired", deadline}` | a window lapses under a policy that suspends | the timer throws and is run again |
| the bucket | S3 `HEAD`, `DELETE`, `ListObjectVersions`; signed `PUT` and `GET` | upload, confirm, view, erasure | 503 `storage_unavailable` on the request path; the erasure answers no certificate |
