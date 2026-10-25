# Contract with `merchant`

`specs/004-merchant/contracts/commands.md` is adopted whole: the prefix `/v1/cashier`, served to
`Callers.service("payments", "ingest")` alone, with the create, submit, abandon, read and key index
commands, their fields and their refusals. This file says only what `ingest` does on each side of it.

## 1. `POST /v1/cashier/sessions`

Sent with: `subBrandId` (the key's sub-brand, never the body's: FR-003), `transactionId` (given or
minted), `amount` (the public `amountCents`), `currency`, `country`, `customerId`, `redirectUrl`,
`clientIp` (the first `X-Forwarded-For` entry, else empty: research R11), and every `customer*` field
the caller sent, unchanged.

Received `200`: returned to the caller as is, and the `session_token` is written as the ticket's
ownership. Received `4xx`: returned as is; the request id is left free (R5). Not received within
`ANSWER_BOUND`: the caller gets `503 TRY_AGAIN`; the answer, if it comes before
`ANKKA_SERVICE_CLIENT_TIMEOUT`, is a lost answer (R6).

## 2. `POST /v1/cashier/sessions/{token}/submit`, `POST /v1/cashier/sessions/{token}/abandon`

Sent only when an ownership names the ticket, with the body as received. Returned as is; a `200`
submit's `paymentId` is written as the payment's ownership.

## 3. `GET /v1/cashier/payments/{paymentId}`

Sent only when the key's sub-brand, or the ticket, owns the payment. Returned as is.

## 4. `GET /v1/cashier/keys`

Once, when the service starts: every `{digest, keyId, subBrandId}` becomes `Key(keyId).issue`. Never
in a request path (SC-003).

## What `merchant` may rely on

- The caller is `ingest` by certificate; `subBrandId` is a verified key's sub-brand.
- A create is forwarded at most once per attempt; a repeat the sub-brand sent with the same body never
  arrives. A repeat after an unanswered attempt does arrive, as a fresh create (FR-013): `merchant`
  may then hold two sessions under one `transactionId`, of which only the second is ever submitted.
- A submit or abandon arrives only for a ticket `ingest` saw answered.
- The rate limit has been applied: no sub-brand sends more than its limit a minute.

## What `ingest` relies on

- A second submit under one token answers the first submit's payment (004 correction 2).
- `merchant`'s answers hold the original's field names, so they pass through unchanged (FR-011).
- `merchant_key_issued` and `merchant_key_deactivated` on `payments.merchants` carry the key id and the
  sub-brand, and the issued fact the digest (004 FR-005).
