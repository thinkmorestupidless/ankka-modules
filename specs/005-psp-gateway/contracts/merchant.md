# Contract with `merchant`

The gateway adopts [`specs/004-merchant/contracts/gateway.md`](../../004-merchant/contracts/gateway.md)
as written, with the four points below. Both services are in `payments`, and every call is by identity.

## 1. `POST /v1/psp/hpp/initiate`

- **In:** as 004's contract. `paymentId` is the session's key and the reference sent to the provider
  (research R3). `terminalProperties` is accepted and **ignored**: credentials are read from `merchant`
  (research R7). `merchant` is to send `{}` (004 follow-up).
- **Out `200`:** `redirectUrl` (a relay page's address for DreamzPay and EuPaymentz POST flows; empty for
  crypto), `pspTransactionId` (the payment id; dashless for T365), and for crypto `paymentAddress`,
  `cryptoAmount`, `cryptoCurrency`, `validUntil?`.
- **Refusals:**

  | Answer | When |
  |---|---|
  | `400 {"error":"player_error","message"}` | below the provider's minimum (BounceMoney), and a player-facing refusal from the provider (BounceMoney's `userMessage`): the original's only player errors |
  | `400 {"error":"unsupported_method" \| "unsupported_currency","message"}` | a method the provider does not offer in the currency (Gigadat outside CAD, Digitarium's non-two-decimal currencies). The original answered `500`; `merchant` shows the player a generic failure either way |
  | `400 {"error":"bad_request","message"}` | a decimal or missing amount, an unknown provider |
  | `404 {"error":"no_credentials","message":"<provider> for <subBrandId>"}` | `merchant` holds no usable credential. No provider is called |
  | `409 {"error":"initiation_in_progress"}` | a repeat while the first call is still out |
  | `502 {"error":"provider_not_configured","provider"}` | the installation has no base URL for the provider |
  | `502 {"error":"provider_unavailable","provider"}` | the provider failed or timed out. The session is `InitiationFailed` |

  A repeat under the same `paymentId` answers the first answer, refusal included, and calls no provider.
  A refusal is final for that payment id; `merchant`'s workflow ends the payment and the player starts
  another, as with the original, whose session then never existed.

## 2. `GET /v1/psp/sessions/{reference}`

As 004's contract: `state` (`pending` \| `resolved`), `outcome?` (`captured` \| `failed`),
`delivered`, `undelivered`, `updatedAt`. Also `refused: Boolean`, which is additive: the receiver
answered a client error, and only an operator's re-drive will deliver it. `merchant` today reads
anything but `undelivered: true` as "expire"; it must read `refused: true` as it reads `undelivered`
(wait another deadline), or a capture `merchant` itself refused through a misconfigured
`webhookSecret` would expire a payment the provider took the money for (004 follow-up, research
finding 7).

## 3. Capture and fail

- **Bodies:** as 004's contract.
  - The capture: `outcome: "success"`, `psp_transaction_id`, and the variance fields where the provider
    reported them.
  - The fail: `failureCode?`, `failureReason`, and the received fields.
- **Signing:** when the credential read for the session has a `webhookSecret`, the gateway sends
  `X-PSP-Signature` (hex HMAC-SHA256 of the body under it) and `X-PSP-Timestamp` (Unix seconds).
  Otherwise it sends neither (research R13). This replaces 004's "passes the provider's signature
  through".
- **Answers, as the deliverer reads them:**

  | Answer | Read as |
  |---|---|
  | `2xx` | delivered |
  | `429`, `5xx` (`503 OUTCOME_PENDING` included), no answer | retried, then undelivered |
  | any other `4xx` (`401 missing_token`, `403 invalid_token`, `403` by ACL) | refused, recorded with the answer |

## 4. What the gateway reads

- `GET /internal/v1/sub-brands/{subBrandId}/psp-credentials/{provider}`: `provider` in upper case.
- `GET /internal/v1/terminals/{terminalId}/properties`.
- **New, 004 follow-up:** `GET /internal/v1/brands/{brand}/terminals` → `terminals[]`: `id`, `subBrandId`,
  `gateway`, `scheme`, `name`, `status`, `supportsDeposit`, `supportsWithdrawal`,
  `requiresPayoutDestination` and `properties{}` (non-secret values only), for the payout listing.

Every read is made at the moment of use and is never cached. `404` means none, and the initiate or the
webhook says so.
