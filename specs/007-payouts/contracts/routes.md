# Routes: payouts

There are five endpoints, each with one prefix and the slug a literal (research R6). Bodies and answers
are camelCase JSON, and every amount is a whole number of minor units. Every refusal is
`{error: <code>, message, …}` with the status shown. A path's `{brand}` other than the slug is `404
wrong_brand` (spec 001).

## `PlayerEndpoint`: `/v1/casino/brands/<slug>/players`

ACL: `Routes.player(slug)`. A `{playerId}` other than the token's `Routes.playerId` is 403
`player_id_mismatch`. `withdrawal-history` alone is `Routes.operator(slug)` with the finance-or-admin
check, set by `withAcl`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `POST /{playerId}/withdrawal-requests` | `{amount, currency, terminalId, destinationId?, walletAddress?, cryptoCurrency?, payoutMethod?, mobile?, bankAccount?, institutionCode?, transitNumber?, confirmForfeit?: false, key}` (the original's names) | 201 `{requestId, status: "PENDING_REVIEW", amount, currency, terminalId, createdAt, bonusForfeited, forfeitedAmount, clawbackAmount}` | research R4's table, in order; 503 `SERVICE_UNAVAILABLE` (the wallet slower than 2 s, or unreachable) |
| `GET /{playerId}/withdrawal-requests` | — | `{requests: [{requestId, amount, currency, terminalName, createdAt, status, payoutStatus}]}`, newest first | — |
| `DELETE /{playerId}/withdrawal-requests/{requestId}` | — | `{requestId, status: "CANCELLED"}` | 404 `REQUEST_NOT_FOUND`; 409 `WITHDRAWAL_REQUEST_NOT_CANCELLABLE` |
| `GET /{playerId}/withdrawal-terminals?currency=` | — | `{terminals: [{id, name, scheme, gateway, requiresPayoutDestination, coins}]}`, ranked (FR-011) | 400 naming `currency` |
| `GET /{playerId}/payout-destinations` | — | `{destinations: [{id, label, firstName, lastName, ibanLastFour, isDefault, createdAt}]}`, active only | — |
| `POST /{playerId}/payout-destinations` | `{firstName, lastName, iban, label?, setAsDefault?}` (the original's names) | 201 the destination | 400 naming the field; 422 `INVALID_IBAN`; 422 `PROFILE_INCOMPLETE`; 422 `PAYOUT_DESTINATION_AML_BLOCKED` (the original's code for a name that does not match) |
| `PUT /{playerId}/payout-destinations/{id}` | `{setAsDefault: true}` (the original's `set-default`) | the destination; an already default one answers 200 `{destinationId, isDefault: true}` and writes nothing (the original's `ALREADY_DEFAULT`) | 404 `DESTINATION_NOT_FOUND`; 409 `DESTINATION_DELETED` |
| `DELETE /{playerId}/payout-destinations/{id}` | — | 204 | 404 `DESTINATION_NOT_FOUND`; 409 `DESTINATION_DELETED` |
| `GET /{playerId}/withdrawal-history` (operator) | — | `{requests: [the queue row]}` | 403 `forbidden` |

`key` is the request's idempotency key. The request id is a name-based UUID of `<player>/<key>`, so it
is safe in a path and the same for a repeat. A repeat with the same body answers the first answer; a
different body is 409 `KEY_REUSED`. A missing `key` is 400 `MISSING_KEY` (constitution II).
`PUT …/{id}` stands for the original's `PUT …/{id}/set-default`, since ankka 0.13 allows two path
parameters (`WireTranslation` maps it).

Each R4 refusal carries its own fields:

- `WITHDRAWAL_BLOCKED_KYC`: `kycStatus`;
- `WITHDRAWAL_BLOCKED_TURNOVER`: `totalRemaining`, `currency`;
- `_WEEKLY_LIMIT` and `_MONTHLY_LIMIT`: `limit`, `used`, `remaining`, `currency`;
- `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT`: `maxWithdrawable`, `totalForfeit`;
- `WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION`: `bonusBalance`, `clawbackAmount`, `totalForfeit`;
- `CRYPTO_CURRENCY_NOT_SUPPORTED`: `supported`.

## `OperatorEndpoint`: `/v1/casino/brands/<slug>/withdrawal-requests`

ACL: `Routes.operator(slug)`. The handler also checks a role: `brand_admin`, `brand_finance`,
`resource_admin`, `resource_finance` or `platform_admin` (R6). Any other role is 403 `forbidden`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `GET /` | query `status` (default `PENDING_REVIEW`; `ALL`), `payoutStatus`, `kyc`, `method`, `amountRange` (`lt500`, `500to5000`, `gt5000`), `playerSearch`, `stale` (initiated over 24 h ago), `page`, `pageSize` (1–200, default 50) | `{requests: [row], total, page, pageSize}` | 400 naming the parameter |
| `GET /{requestId}` | — | the request: every field of `WithdrawalRequest` but `Personal` ones beyond the IBAN's last four and the holder's names | 404 `REQUEST_NOT_FOUND` |
| `POST /{requestId}/approve` | — | `{requestId, status, payoutStatus}` after the approval's first step | 404; 409 `REQUEST_NOT_PENDING` |
| `POST /{requestId}/reject` | `{reason?}` | `{requestId, status: "REJECTED"}` | 404; 409 `REQUEST_NOT_PENDING` |
| `POST /{requestId}/confirm-payout` | `{note?}` | `{requestId, status: "COMPLETED"}` | 404; 409 `WITHDRAWAL_NOT_AWAITING_PAYOUT` |
| `POST /{requestId}/mark-payout-failed` | `{reason?}` (default "Manual payout not completed") | `{requestId, status: "FAILED"}` | 404; 409 `WITHDRAWAL_NOT_AWAITING_PAYOUT` |

A row is `{requestId, playerId, kycStatus, amount, currency, terminalName, method, destinationLastFour,
createdAt, status, payoutStatus}`.

## `ConfigEndpoint`: `/v1/casino/brands/<slug>/withdrawal-config`

ACL: `Routes.operator(slug)`, with the admin check: `brand_admin`, `resource_admin` or `platform_admin`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `GET /` | — | `{limits: {<currency>: {weekly, monthly}}}`; an unset limit is `null` | — |
| `PUT /` | the same shape | the stored limits | 400 naming a negative, decimal or unknown-currency field |

## `OutcomeEndpoint`: `/v1/casino/brands/<slug>/withdrawals`

ACL: `Routes.service(Some(GrantTarget.Route("payouts", "POST",
"/v1/casino/brands/{brand}/withdrawals/{requestId}/outcome")))`, which admits `payments`/`psp-gateway`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `POST /{requestId}/outcome` | `{outcome: completed \| failed \| reversed, providerReference, reason?}` | 200 `{requestId, status}` | 400 `UNRECOGNISED_OUTCOME` (the original's); 404 `REQUEST_NOT_FOUND`; 409 `REFERENCE_MISMATCH` (recorded, new); 409 `ALREADY_RESOLVED` (a different outcome on a settled request, or any outcome before approval); 409 `WITHDRAWAL_NOT_IN_COMPLETED_STATE` (a reversal of a request not completed) |

The gateway retries on 429, 5xx and no answer; a 4xx is final (R6). A repeat of the outcome that
settled the request is 200 and moves nothing.

## `ServiceEndpoint`: `/v1/casino/brands/<slug>/payouts`

ACL: `Routes.serviceOf("players")`.

| Method and path | Answer |
|---|---|
| `POST /erasure/{playerId}` | 200 `{erased: [requestIds]}`, idempotent |

## Calls out

| To | Route | When | Read as |
|---|---|---|---|
| wallet | `GET /v1/casino/brands/<slug>/wallets/{p}/{c}/holds/preview?amount=` | a request, before the hold | 200 with the refusal in the body; 404 `WALLET_NOT_FOUND` |
| wallet | `POST …/holds` `{key: <id>, amount, playerConfirmed}` | the workflow's `hold` | ok, or 409 with `WITHDRAWAL_REQUEST_ALREADY_PENDING`, `INSUFFICIENT_FUNDS`, `WITHDRAWAL_BLOCKED_TURNOVER`, `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT`, `KEY_REUSED`; transport → retry |
| wallet | `POST …/holds/release` `{key: <id>/release, requestId, outcome}` | cancel, reject, failure | ok; `REQUEST_NOT_PENDING` on a repeat is done |
| wallet | `POST …/holds/confirm` `{key: <id>/confirm, requestId}` | completion | ok; `REQUEST_NOT_PENDING` on a repeat is done |
| wallet | `POST …/holds/reverse` `{key: <id>/reverse, requestId}` | reversal | ok or `idempotent: true` |
| psp-gateway | `GET /v1/payments/withdrawal-terminals?currency=` | the catalogue's timer, or a cold read | the listing |
| psp-gateway | `POST /v1/payments/withdrawals` | the workflow's `initiate` | research R7's table |
| psp-gateway | `GET /v1/payments/withdrawals/{requestId}` | the workflow's `poll` | research R8 |
