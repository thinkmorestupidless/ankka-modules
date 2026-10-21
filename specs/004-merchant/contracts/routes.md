# Contract: the routes of `merchant`

Every route by audience, grouped by prefix (one endpoint per prefix; a route takes at most two
path parameters; research R14). Bodies keep the original's field names (`Models.scala`,
`CashierRoutes.scala`), camelCase as spray-json wrote them; amounts on operator and ingest routes are
minor-unit integers, and where the original answered a decimal string (`amount`) it is answered
beside `amountCents` (R11). A refusal is `{"error": "<CODE>", "message"?: "<text>"}` with the
original's code words.

The service is exposed (research R1): the operator routes and `accept-invitation` are reached from the
backoffice frontend; every other route is admitted by a service identity or a grant and refuses a token.

## Operator routes (`Routes.paymentsOperator`: a staff token; scope from `eitheror_scope_level`, `eitheror_scope_id`)

A read or change outside the token's scope is `403 scope_violation`, message "Your operator scope
does not include this resource". `platform` scope sees everything, including a sub-brand with no
parent (FR-029).

### `/v1/holdings`

| Method, path | Does | Body in → out |
|---|---|---|
| `POST /` | create a holding (platform scope) | `name` → `holdingId`, `name`, `status`, `createdAt` |
| `GET /` | list holdings in scope | → `holdings[]` |
| `GET /{id}` | read | → the holding |
| `PUT /{id}/status` | change status | `status` → `holdingId`, `status` |
| `GET /{id}/companies` | the holding's companies | → `companies[]` |

### `/v1/companies`

| Method, path | Does |
|---|---|
| `POST /` | create under `holdingId` → `companyId`, `holdingId`, `name`, `status`, `createdAt` |
| `GET /`, `GET /{id}` | list in scope; read |
| `PUT /{id}/status` | change status |

### `/v1/sub-brands`

| Method, path | Does |
|---|---|
| `POST /` | create under `companyId` with `name`, `type`, and `brand` (required for `Casino`, `Combined`) → the sub-brand; `withdrawalTurnover*` fields are refused as not this service's (spec 002 FR-004a) |
| `GET /`, `GET /{id}` | list in scope (the company's, by query `companyId`); read |
| `PUT /{id}/status` | change status |
| `GET /{id}/psp-credentials`, `POST /{id}/psp-credentials` | as under `/v1/merchants/{id}` below |
| `GET\|PUT\|DELETE /{id}/psp-credentials/{provider}?name=` | as below |
| `POST /{id}/psp-credentials/{provider}/activate?name=`, `/disable?name=` | as below |
| `GET /{id}/gateway-config` | `methodGateways` |

### `/v1/merchants` (`{id}` is the sub-brand id; the original's "merchant")

| Method, path | Does | Body in → out |
|---|---|---|
| `GET /{id}` | read the sub-brand | |
| `POST /{id}/keys` | generate a merchant key | `name` → `keyId`, `name`, `rawKey` (once), `createdAt` |
| `GET /{id}/keys` | list | → `keys[{keyId, name, active, createdAt}]` |
| `POST /{id}/keys/{keyId}/disable` | disable (new route: the original disabled nothing) | |
| `GET /{id}/psp-configurations` | the legacy listing | → `configurations[]` without credentials |
| `POST /{id}/psp-credentials` | register a credential, creating its terminal | `provider`, `name`, `environment`, `scheme?`, `credentials{}` → `configId`, `provider`, `name`, `environment`, `status`, `scheme?` |
| `GET /{id}/psp-credentials` | list | → `credentials[]`, no secret |
| `GET /{id}/psp-credentials/{provider}?name=` | read one | → the summary with `credentialKeys` (names) and masked previews |
| `PUT /{id}/psp-credentials/{provider}?name=` | replace secrets | `credentials{}` |
| `DELETE /{id}/psp-credentials/{provider}?name=` | delete, marking the companion terminal deleted | |
| `POST /{id}/psp-credentials/{provider}/activate?name=`, `…/disable?name=` | status | |
| `POST /{id}/gateways/{gateway}/integrations` | create a terminal | `scheme`, `name`, `regions[]`, `properties{}`, `supportsDeposit?`, `supportsWithdrawal?` → `integrationId` |
| `GET /{id}/gateways/integrations` | list terminals | → `integrations[]`: properties as key names set, with masked previews, never values; `missingRequired[]` |
| `GET /{id}/gateways/integrations/{integrationId}` | read | |
| `PUT /{id}/gateways/integrations/{integrationId}/properties` | replace properties | `properties{}` |
| `PUT /{id}/gateways/integrations/{integrationId}/capability` | directions | `supportsDeposit`, `supportsWithdrawal`, `requiresPayoutDestination?` |
| `POST /{id}/gateways/integrations/{integrationId}/activate`, `…/disable` | status | `204`, as the original |
| `DELETE /{id}/gateways/integrations/{integrationId}` | delete; `409` when the resolved routing tree names it | |
| `GET /{id}/gateway-config` | `methodGateways` | |

Divergence (R14): the original's `/{id}/psp-credentials/{provider}/{name}[/activate|/disable]`
carried three path parameters; the name is the query parameter `name`.

### `/v1/payments` (operators; the gateway's two routes below)

| Method, path | Does | Body in → out |
|---|---|---|
| `GET /cashier?merchantId=&status=&search=&page=&pageSize=` | the backoffice list (FR-029) | → `payments[]` (`paymentId`, `merchantId`, `customerRef`, `amount`, `amountCents`, `currency`, `status`, `psp`, `pspTransactionId`, `terminalId`, `createdAt`, …), `statusCounts{}`, `page`, `pageSize`, `total`; `status` ∈ `all`, `pending`, `captured`, `failed`, `expired`, else `400` |
| `GET /cashier/summary?merchantId=` | the summary | → `count`, `statusCounts{}`, `capturedVolume[{currency, amountCents}]`, `daily[{date, total, captured}]`, `psps[{psp, total, captured}]`, `capturedWithoutAmount` |
| `GET /cashier/uncredited` | captured and not credited (FR-023) | → `payments[{paymentId, capturedAt, attempts, lastAnswer}]` |
| `POST /cashier/{paymentId}/retry-credit` | an operator's retry (FR-023) | → the payment |
| `GET /routing-rules/state?level=&entityId=` | draft, live, history, `resolvedFrom?` | → `VersionedState` |
| `POST /routing-rules` | save a draft | `level`, `entityId`, `tree` → `202` |
| `POST /routing-rules/deploy?level=&entityId=` | deploy | → `version`, `deployedAt`; `400 NO_DRAFT` |
| `POST /routing-rules/simulate?level=&entityId=` | simulate | `tree`, `currency`, `amountCents`, `country`, `customerId?`, `depositCount?` → `outcome`, `terminals[]` |
| `POST /routing-rules/rollback/{version}?level=&entityId=` | roll back | → `version`, `deployedAt` |
| `POST /routing-rules/retire?level=&entityId=` | retire | |
| `GET /terminals?merchantId=` | credential-named and own terminals together (FR-009) | → `terminals[{terminalEntityId, gateway, scheme, name, status, integrationType, supportsDeposit, supportsWithdrawal, requiresPayoutDestination}]` |
| `GET /{paymentId}` | the payment read, for the backoffice | → as the cashier read in [commands.md](commands.md) |

Dropped: `/v1/payments/risk/*` (FR-036).

### `/v1/catalog`

| Method, path | Does |
|---|---|
| `GET /gateway/{gateway}` | `gateway`, `schemes[{pspId, category, requiredProperties[{key, label, required, multiline, secret}]}]` (`secret` is new, R9) |

### `/v1/operators`

| Method, path | Does | Body in → out |
|---|---|---|
| `POST /` | create and invite | `email`, `role`, `scopeId`, `scopeLevel`, `firstName?`, `lastName?` → `operatorId`, `email`, `role`, `scopeId`, `scopeLevel`; `403` when the role's privilege is not below the caller's or the scope not within the caller's |
| `GET /` | list in scope | → `operators[{operatorId, email, username, enabled, roles[], scopeId, scopeLevel, scopeAncestors[], pending, firstName?, lastName?}]` |
| `POST /accept-invitation` | public within the realm's flow: no token | `token`, `password` → `204` |
| `POST /{id}/resend-invitation`, `POST /{id}/reset-password` | | |
| `PUT /{id}/scope` | | `scopeId`, `scopeLevel` → `operatorId`, `scopeId`, `scopeLevel` |
| `DELETE /{id}` | remove | |

## Service routes for `ingest` (`Callers.service("payments", "ingest")`)

Prefix `/v1/cashier`; the commands are [commands.md](commands.md): `POST /sessions`,
`POST /sessions/{token}/submit`, `POST /sessions/{token}/abandon`, `GET /payments/{paymentId}`,
`GET /keys` (the active key index for the refresh, FR-005).

## Service routes for `psp-gateway` (`Callers.service("payments", "psp-gateway")`)

| Method, path | Does |
|---|---|
| `POST /v1/payments/{paymentId}/capture` | [gateway.md](gateway.md) |
| `POST /v1/payments/{paymentId}/fail` | [gateway.md](gateway.md) |
| `GET /internal/v1/sub-brands/{subBrandId}/psp-credentials/{provider}` | the usable credential's secrets: `provider`, `credentials{}`; `404 NOT_FOUND` when none active with secrets (FR-008; `PspCredentialLifecycleSpec`) |
| `GET /internal/v1/terminals/{terminalId}/properties` | `properties{}`, secrets included (FR-008) |
| `GET /internal/v1/brands/{brand}/terminals` | the terminals of every sub-brand naming the brand, deleted ones left out: `terminals[]` of `id`, `subBrandId`, `gateway`, `scheme`, `name`, `status`, `supportsDeposit`, `supportsWithdrawal`, `requiresPayoutDestination` and `properties{}` without the catalogue's secret keys; the secret store is not read. An unknown brand is an empty list. For the gateway's payout listing (spec 005, research R12) |

## Granted route for a brand's `players` (`Layout`: `service:{brand}/players`, erasure)

`POST /erasure/players/{customerId}`: refused `403` when the customer id's brand is not the caller's
project; deletes the customer's `PaymentCustomer` entries and live `Session`s; answers
`{"confirmed": true, "payments": n}` (R12).

## Project status (`Callers.anyInProject`)

`GET /status`: `{"uncreditedCaptures": n, "bound": "15m"}` (FR-023); the same number is the gauge
`eitheror_payments_uncredited_captures`.

## Dropped from the original's surface

| Original | Why |
|---|---|
| `OPTIONS` preflights, `merchantKey` header handling | `ingest`'s (spec 006 FR-020, FR-002) |
| `POST /v1/cashier/sessions`, `…/submit`, `GET /v1/payments/{id}` as public routes | `ingest`'s; the commands behind them are above |
| `POST /internal/v1/merchants/validate-key` | `ingest` verifies from the facts; `GET /v1/cashier/keys` replaces it |
| `GET /internal/v1/cashier/{brand}/{player}/payments`, `POST /internal/v1/cashier/payments/{id}/abandon` | the brand's view and the token-admitted abandon (*Changes*) |
| `POST /internal/v1/cashier/payments/expire-stale`, `…/reconcile-captured` | the workflow's timers (SC-005) |
| `/v1/credentials/st8/{brand}` | spec 003 |
| `/v1/holdings/{id}/crm-config` | spec 014 |
| `/v1/payments/risk/*` | FR-036 |
| `/dev/seed*` | the seed from `MERCHANT_BRANDS` (R15) |
| `PUT /v1/merchants/{id}/turnover` | spec 002 FR-004a |
