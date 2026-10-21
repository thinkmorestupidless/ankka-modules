# Contract: `merchant` and `psp-gateway` (spec 005 adopts this)

Both services are in the `payments` project; every call is by service identity. The shapes are the
original's (`CashierRoutes.scala:379-427`, `hpp.go:29-84`) with amounts in minor units; the session
status read is new and is what spec 005 FR-009 promises.

## What `merchant` asks the gateway

### `POST /v1/psp/hpp/initiate` — initiate a hosted-page deposit (FR-015, FR-020 `initiate`)

In: `amount` (minor units), `currency`, `method` (the scheme), `callbackUrl`
(`/v1/payments/{paymentId}/capture` on this service, as the original built it), `psp`,
`subBrandId`, `customerId`, `redirectUrl`, `paymentId` (the gateway's idempotency key, 005 FR-008),
`terminalId`, `terminalProperties{}` (always empty: the gateway reads the credential from `merchant`; spec 005 finding 1), and the customer fields
`customerEmail`, `customerFirstName`, `customerLastName`, `customerPhone`, `customerMobile`,
`customerProvince`, `customerIp`, `customerCountry`, `customerCity`, `customerAddress`,
`customerPostCode`, `customerState` (optional).

Out `200`: `redirectUrl`, `pspTransactionId`, and for crypto `paymentAddress`, `cryptoAmount`,
`cryptoCurrency`, `validUntil`.

Refused `400 {"error": "player_error", "message": "<player-safe text>"}` for a reason the player can
act on (below the provider's minimum, an unsupported currency); any other non-2xx is a generic
failure to `merchant` (`CashierPlayerErrorForwardingSpec`). A repeat under the same `paymentId`
returns the first answer and calls no provider.

### `GET /v1/psp/sessions/{reference}` — a session's status (FR-020 `expire`; 005 FR-009)

`reference` is the payment id. Out `200`: `state` (`pending` \| `resolved`), `outcome?`
(`captured` \| `failed`), `delivered: Boolean`, `undelivered: Boolean` (an outcome whose delivery
was abandoned and is listed for re-drive), `refused: Boolean` (an outcome `merchant` answered with a
client error, which only an operator's re-drive delivers; spec 005 finding 7), `updatedAt`. `404`
when the gateway holds no session. `merchant` reads `undelivered: true` or `refused: true` as "wait
another deadline" and anything else as "expire".

## How the gateway calls `merchant`

### `POST /v1/payments/{paymentId}/capture` (FR-018 to FR-021, FR-026)

Headers: `X-PSP-Signature` (hex HMAC-SHA256 of the raw body under the credential's
`webhookSecret`) and `X-PSP-Timestamp` (Unix seconds, within 300 s) when the provider's credential
has a `webhookSecret`; neither otherwise. The gateway signs the body it sends with that secret (spec 005
research R13; the original's gateway never signed, so a credential with a secret refused every capture).

Body: `outcome` (`success`), `psp_transaction_id`, and the variance when the provider reported one:
`variance` (`UNDERPAYMENT` \| `OVERPAYMENT`), `receivedCryptoAmount`, `receivedConvertAmount`,
`receivedConvertCurrency` as the provider's strings.

| Answer | Meaning for the gateway |
|---|---|
| `200 {"ok": true}` | delivered; stop. The original's one acknowledgement (`completeOutcome`), whatever it acknowledges; the credit is read from the payment |
| `200` for an unknown payment, or a terminal one | delivered; stop (FR-019) |
| `503 OUTCOME_PENDING` | the wallet has not answered within the bound: keep the outcome undelivered and redeliver with backoff (005 FR-015) |
| `503` (secret store or entity unreadable) | redeliver |
| `401 missing_token` (no signature where one is required, one header without the other) / `403 invalid_token` (bad signature, stamped outside 300 s) | a client error: do not retry; record as refused |
| `503 {"error": <the reason>}` for a variance it cannot trust | the original's answer: not acknowledged, so the outcome stays undelivered for an operator (US7) |
| `503 webhook_secret_not_configured` / `503 auth_requirement_unavailable` | redeliver |

### `POST /v1/payments/{paymentId}/fail`

Headers as the capture. Body: `failureCode?`, `failureReason?` (default "Payment could not be
processed"), `receivedCryptoAmount?`, `receivedConvertAmount?`, `receivedConvertCurrency?`.
Answers `200 {"ok": true}`, an unknown or terminal payment too; `503` as above. A caller other than
`psp-gateway` is refused `403` by the route's ACL, where the original answered `401 UNAUTHORIZED` for
a missing internal secret (plan.md Divergences).

### What the gateway reads from `merchant`

`GET /internal/v1/sub-brands/{subBrandId}/psp-credentials/{provider}` and
`GET /internal/v1/terminals/{terminalId}/properties` ([routes.md](routes.md)), at the moment of use,
never cached across requests (spec 005 FR-018's intent; research *Findings*).
