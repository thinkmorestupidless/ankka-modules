# The original's KYC surface, and where each piece goes

Read from `eitheror-platform` at `e7d8f2de` (research R1). "Moved" means served by this service at the
route `contracts/routes.md` names.

## Routes

| Original | Where | Here |
|---|---|---|
| `GET /v1/casino/backoffice/{brand}/kyc-policy[?ancestors]` | `BackofficeKycPolicyRoutes.scala:103-116` | moved: `GET …/backoffice/<slug>/kyc/policy`; the ancestors read dropped (Clarifications 2026-10-08); none-valued fields are `null`, not omitted |
| `PUT …/kyc-policy` | `:117-189` | moved: `PUT …/policy`, validated, the profile `CUSTOM` |
| `POST …/kyc-policy/profile` | `:194-249` | moved: `POST …/policy/profile` |
| `GET /v1/casino/players/{brand}/kyc/requirements` | `PlayerRoutes.scala:1503-1516` | moved: `GET /v1/casino/kyc-requirements/<slug>` |
| `POST /v1/casino/{brand}/players/{player}/kyc/upload-url` (player and operator) | `BackofficePlayerRoutes.scala:180-209, 672-702` | moved: `POST …/uploads`; the type checked; the operator gives a reason |
| `GET …/kyc/documents` (player and operator) | `:213-245, 911-936` | moved: `GET /{playerId}/documents` (player), `GET /{playerId}` (operator) |
| `POST …/kyc/documents` (confirm; player and operator) | `:247-311, 938-996` | moved: `POST /{playerId}/documents` by document id, found in the store; the operator's is locked as the player's |
| `GET …/kyc/documents/{key}/view-url` (player, operator) and `GET /v1/casino/players/{brand}/{player}/kyc/documents/{key}/view-url` | `:315-383, 705-774`; `PlayerRoutes.scala:1436-1501` | moved: `GET /{playerId}/views/{documentId}`; one player route, not two |
| `PATCH …/kyc/documents/{key}/approve`, `…/reject` | `:781-905` | moved: `POST /{playerId}/reviews/{documentId}`; the reviewer recorded; an unknown document is 404 |
| `PATCH …/kyc/verify`, `PATCH …/kyc/reject` | `:392-464` | moved: `POST /{playerId}/override` |
| `POST /v1/casino/backoffice/{brand}/{player}/kyc/approve` | `:1497-1550` | moved: `POST /{playerId}/override` with `verified` and a reason |
| `PUT …/kyc/verification-mode` | `:471-514` | moved: `PUT /{playerId}/mode`; automatic recomputes at once |
| `PUT …/kyc/status` | `:522-577` | moved: `PUT /{playerId}/status`; pending is `player_kyc_pending` |
| `PUT …/kyc/required-doc-types` | `:586-669` | moved: `PUT /{playerId}/required` |
| `GET /v1/casino/backoffice/{brand}/players?kyc=…` (filters, facets) | `:1559-1640` | KYC part moved: `GET …/kyc/queue`; the rest of the list stays `players`' |
| `GET …/players/{player}/profile` (`profileComplete`, `missingProfileFields`, `kycStatus`, mode, required) | `PlayerProfileRoutes.scala:290-314` | profile fields stay `players`'; the KYC fields are this service's `GET /{playerId}` |
| `POST /internal/v1/casino/{brand}/notify/deposit` (the KYC part) | `CasinoServiceMain.scala:413-830` | moved: `POST /v1/casino/brands/<slug>/kyc/deposit-check`, asked by `players` before the deposit (research R5) |
| the launch's KYC check | `SessionRoutes.scala:285-300` | moved: `GET …/kyc/{playerId}/play-check`, asked by `game-provider` |
| the withdrawal's KYC check | `WithdrawalRoutes.scala:249-264` | spec 007's, from this service's facts |

## Functions and capabilities

| Original | State | Here |
|---|---|---|
| `kyc_status_rollup` (Go) and the `kyc-status-rollup` function | deployed | `Rollup` and `KycRecord` (R4), with its 61 tests and `substrate_test.go`'s 7 |
| `player-kyc-gate-on-large-deposit` | approved, never built | the large-deposit flag (FR-038) |
| `player-deposit-threshold-check` | not deployed | lifetime deposits (R6); its proposals become flags |
| `player-kyc-window-set` | empty body; the deadline is written by `SubstrateTwinClient.createPlayerTwin` | `SetDeadline` (R10) |
| `player-kyc-window-expiry-check` | empty body | `WindowTimer` (R10) |
| `EventIngestConsumer`'s `kyc_review` proposal | proposes to nobody | not carried over |
| `kyc-policy` twin, seeded by `merchant-service` (dev only) | | `PolicyEntity` (R9) |
| MinIO `casino-kyc`, keys `{brand}/{player}/{type}-{millis}.{ext}` | | the service's bucket, `subjects/<player>/kyc/…` (R7) |
| `kyc_view_log` (latest only) | | `ViewIssued` and an audit record per issue |
| CRM `kyc_document_accepted` / `kyc_document_declined`; `kyc_approved` / `kyc_rejected` via `notify_player` | | `engagement`'s (spec 014), from `player_kyc_document_reviewed` and the status families |
| `kyc_status` reads elsewhere (GraphQL, reporting, withdrawal list filter, read model, CRM attribute) | | each reader's own view of the facts (specs 015, 007, 014) |
