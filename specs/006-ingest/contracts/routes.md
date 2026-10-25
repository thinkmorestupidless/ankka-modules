# Routes: ingest

Every route by audience and prefix (research R12). Paths, field names and answer shapes of the public
routes are the original's `CashierRoutes.scala`, verbatim: the frontends change only base URLs. Every
request takes a `RequestRecord`; every public one of a known sub-brand takes a `RateWindow.take`.

## `/v1/cashier`: public (`Routes.public`): the sub-brand's server or the brand's frontend proxy

### `POST /sessions` — create a cashier session (US1, US2)

Header `merchantKey` (any case). Body, the original's names:

| Field | Required | Check (FR-007) |
|---|---|---|
| `amountCents` | yes | a whole number `> 0` |
| `currency` | yes | non-empty |
| `customerId` | yes | `brand:player`, both parts non-empty |
| `country` | yes | non-empty |
| `redirectUrl` | yes | non-empty |
| `transactionId` | no | at most `REQUEST_ID_BOUND` characters, no control characters; minted when absent |
| `customerEmail`, `customerFirstName`, `customerLastName`, `customerPhone`, `customerMobile`, `customerProvince`, `customerCountry`, `customerCity`, `customerAddress`, `customerPostCode`, `customerState` | no | forwarded, never kept (FR-008) |

Answer `200`: `merchant`'s create answer unchanged — `session_token`, `transactionId`, `redirectUrl`,
`available_terminals[]`, `resolvedFrom` ([merchant.md](merchant.md)). A repeat under the same
`transactionId` with the same body: the same answer, `merchant` not asked.

Refusals, in the order checked:

| Status | `error` | When |
|---|---|---|
| `413` | `body_too_large` | more than `BODY_BOUND` bytes (FR-021) |
| `400` | `not_json` | the body is not a JSON object (FR-021) |
| `401` | `UNAUTHENTICATED` | no `merchantKey` (FR-005) |
| `401` | `INVALID_MERCHANT_KEY` | the key is no active key of any sub-brand (FR-005) |
| `429` | `TOO_MANY_REQUESTS`, `retryAfterSeconds`; header `Retry-After` | over the sub-brand's rate (FR-015) |
| `400` | `INVALID_REQUEST`, `field` | a required field missing or malformed (FR-007) |
| `409` | `REQUEST_ID_REUSED`, `transactionId` | the id was used with a different body (FR-010) |
| `503` | `TRY_AGAIN`, `retryAfterSeconds` (5); header `Retry-After: 5` | `merchant` unreachable or unanswered within `ANSWER_BOUND` (FR-013) |
| as `merchant` | `merchant`'s code | `merchant` refused: `NO_ROUTING_TREE`, `NO_ROUTING_MATCH`, `WRONG_BRAND`, `INVALID_AMOUNT`, `SUB_BRAND_NOT_FOUND`, `SUB_BRAND_INACTIVE` (FR-011) |

### `POST /sessions/{ticket}/submit` — submit (US3)

No key: the ticket is the credential (FR-001). Body `selectedTerminalEntityId`, `cryptoCurrency?`,
customer fields. Answer `200`: `merchant`'s — `paymentId`, `status`, crypto fields. A second submit:
the first submit's answer, from `merchant`.

Refusals: `413`/`400` as above; `404 SESSION_NOT_FOUND` when no ownership names the ticket (then
`merchant` is not asked) or when `merchant` says so; `429`; `503 TRY_AGAIN`; `merchant`'s
`400 terminal_not_found`, `400 missing_crypto_currency`, `400 PAYMENT_REJECTED` with `message`,
`502 psp_error`, `503 OUTCOME_PENDING`.

### `POST /sessions/{ticket}/abandon` — the player abandons (004 FR-024)

No body. Answer `200`: the payment read. Refusals as submit's first group.

## `/v1/payments`: public (`Routes.public`)

### `GET /{paymentId}` — read a payment (US3, FR-009)

Header `merchantKey` (the creating sub-brand's), or header `cashierTicket` (the session's). Answer
`200`: `merchant`'s read, unchanged ([merchant.md](merchant.md)).

| Status | `error` | When |
|---|---|---|
| `401` | `UNAUTHENTICATED` | neither header |
| `401` | `INVALID_MERCHANT_KEY` | the key is no active key |
| `404` | `PAYMENT_NOT_FOUND` | the key's sub-brand does not own the payment; the ticket's session has no such payment; or `merchant` says so |
| `429`, `503` | as above | |

## `/v1/ingest`: payments operators (`Routes.paymentsOperator()`, installation level)

| Route | Answer | FR |
|---|---|---|
| `GET /sub-brands/{subBrand}/requests?from&to&requestId&limit` | `requests[]` of `RequestRecord` fields, newest first; `from` and `to` default to the last 24 hours, `limit` to 100 and at most 1,000; with `requestId`, every attempt under it, oldest first | FR-017 |
| `GET /lost-answers?subBrand` | `lostAnswers[]`: records with `outcome = lost_answer`, not seen | FR-014 |
| `POST /lost-answers/{recordId}/seen` | `204`; `404` no such record; `409` not a lost answer | FR-014 |
| `GET /sub-brands/{subBrand}/rate` | `{perMinute, default}`; `perMinute` is the default when never raised | FR-015 |
| `PUT /sub-brands/{subBrand}/rate` body `{perMinute}` | `204`; `400` unless a positive integer | FR-015 |

An operator whose token is not from the staff issuer or whose roles do not name shared payments is
refused `403` by the contract's ACL (FR-017).

## Not served

`OPTIONS` anything (research R10); `POST /v1/payments`, `/v1/payouts`, `/v1/payments/verify`;
`/v1/payments/{id}/capture` and `/fail` (they are `merchant`'s, from `psp-gateway`); `/health` (the
runtime's readiness); any provider's webhook (`psp-gateway`).
