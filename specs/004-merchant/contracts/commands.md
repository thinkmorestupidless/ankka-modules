# Contract: the cashier commands `ingest` sends (spec 006 adopts this)

Prefix `/v1/cashier`, served to `Callers.service("payments", "ingest")` alone. `ingest` has verified
the merchant key and resolved the sub-brand, made the create idempotent by the merchant's reference,
rate-limited and recorded the request (spec 006 FR-002 to FR-016); this service trusts the
`subBrandId` the body names because only `ingest` can call. Amounts are minor units; the frontend's
decimal string is produced where the original answered one.

## `POST /sessions` — create a cashier session (FR-013, FR-014)

In: `subBrandId`, `transactionId` (the merchant's reference), `amount` (minor units, `> 0`),
`currency`, `country`, `customerId` (`brand:player`), `redirectUrl`, `clientIp`, and the customer
fields as the original's names: `customerEmail`, `customerFirstName`, `customerLastName`,
`customerPhone`, `customerMobile`, `customerProvince`, `customerCountry`, `customerCity`,
`customerAddress`, `customerPostCode`, `customerState` (all optional).

Out `200`: `session_token`, `transactionId`, `redirectUrl`, `available_terminals[{terminalEntityId,
name, scheme, gateway?, supportedCurrencies[]?}]`, `resolvedFrom{level, entityId, name}` (FR-011;
a declared divergence, plan.md). No deadline: the original's session answer has none
(`CashierRoutes.scala:1643`).

Refused `400`: `MISSING_ROUTING_CONTEXT` (no country or customer id), `NO_ROUTING_TREE`,
`NO_ROUTING_MATCH` (no terminal reached, or none active); `WRONG_BRAND` when the customer id's brand
is not the sub-brand's (new; Clarifications); `INVALID_AMOUNT` naming the field (R11). `404
SUB_BRAND_NOT_FOUND`; `409 SUB_BRAND_INACTIVE`.

The routing context is `(currency, amount, country, customerId, depositCount)` where the deposit
count is the customer's captured payments at the sub-brand (`PaymentsView.depositCount`); the tree
is the sub-brand's live one, else the company's, else the holding's (FR-011); a credential-named
terminal is offered when the credential is active, a terminal when it is `ACTIVE`
(`CashierRoutes.scala:1540-1570`).

## `POST /sessions/{token}/submit` — submit (FR-015)

In: `selectedTerminalEntityId`, `cryptoCurrency?`, and any customer fields the player entered
(override the session's).

Out `200`: `paymentId`, `status` (`AWAITING_REDIRECT` or `AWAITING_CRYPTO`), and for crypto
`paymentAddress`, `cryptoAmount`, `cryptoCurrency`, `validUntil` (`CashierRoutes.scala:1745`). The
provider's redirect address is not in it; the frontend takes it from the read. A second submit returns the same answer and makes no second payment
(correction 2).

Refused: `404 SESSION_NOT_FOUND` (unknown or expired token); `400 terminal_not_found`;
`400 missing_crypto_currency`; `400 PAYMENT_REJECTED` with `message` when the gateway refused with
a player-safe message; `502 psp_error` for any other gateway failure (the original's generic
failure, `CashierPlayerErrorForwardingSpec`); `503` when the gateway cannot be reached.

The submit resolves the terminal as a credential name first, else as an active terminal; mints the
payment id; starts the `DepositWorkflow` (its `initiate` step asks the gateway,
[gateway.md](gateway.md)); polls its status up to 2 s; answers from the payment. Past the bound:
`503 OUTCOME_PENDING`, and the retry under the same token is answered from the session once the
workflow has recorded the initiation.

## `POST /sessions/{token}/abandon` — the player abandons (FR-024)

In: nothing. Out `200`: the payment read. Fails the payment with code `player_abandoned`, reason
"Payment not completed"; a terminal payment is unchanged and answered.

## `GET /payments/{paymentId}` — read (FR-017)

Out `200`: `merchantId`, `customerRef`, `status` (`COMPLETED` \| `FAILED` \| `EXPIRED`
\| the stored awaiting status), `amount` (decimal string), `amountCents`, `currency`, `psp`,
`scheme`, `terminalId`, `pspTransactionId`, `redirectUrl`, `createdAt`, `expiresAt`, `capturedAt?`,
`failedAt?`, `expiredAt?`, `failureCode?`, `failureReason?`, `paymentAddress?`, `cryptoAmount?`,
`cryptoCurrency?`, `validUntil?`, `variance?`, `receivedCryptoAmount?`, `receivedConvertAmount?`,
`receivedConvertCurrency?`, `originalAmountCents?`. No payment id, no merchant's transaction id
and no credit (`CashierRoutes.scala:2005-2060`); the credit is the deposit workflow's.
A non-terminal payment past its deadline reads `EXPIRED`, `failureReason` "Payment not completed".
`404 PAYMENT_NOT_FOUND`.

## `GET /keys` — the active key index (FR-005)

Out `200`: `keys[{digest, keyId, subBrandId}]`, every active key. For `ingest`'s refresh only; never
in a request path.
