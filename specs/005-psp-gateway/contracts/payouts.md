# Contract with a brand's `payouts` (spec 007)

Across projects: `{brand}/payouts` calls the gateway under the grants `payments` makes it
(`Layout.brandGrantsReceived`, three routes after this spec). The gateway calls
`{brand}/payouts` under the grant the brand makes (`Layout.BrandGrantsMade`). Until ankka 040, each side
admits the other by name: `Routes.service(Some(route))` on the brand side, and `Caller.Service(_,
"payouts")` with the project as the brand here.

## `GET /v1/payments/withdrawal-terminals` (FR-009a)

`?currency=` is optional. The answer is `terminals[]`:

- from `merchant`'s brand listing: `id`, `name`, `scheme`, `gateway`, `active`, `supportsWithdrawal`,
  `requiresPayoutDestination`;
- from the terminal's `supportedCurrencies` property (comma-joined; trimmed, upper-cased, without repeats): `coins[]`;
- from the catalogue: `hasStatus`, and `reviewWindow` (ISO-8601 duration, or null).

Only terminals with `supportsWithdrawal` are listed. `payouts` applies the eligibility rule itself.

## `POST /v1/payments/withdrawals` (FR-004)

- **In:**
  - `requestId`, `brand` (must be the caller's project), `subBrandId`, `terminalId`, `amount` (minor
    units, a whole number), `currency` and `method`;
  - the method's details, one of:
    - `iban`, `firstName`, `lastName`, `email?`;
    - `email` and `mobile` (Gigadat ETO);
    - `account`, `institution`, `transit` (Gigadat ACH);
    - `coin`, `address`, `memo?` (Cozeni, Morphosis).
- **Out `200`:** `{"requestId", "status", "providerReference?", "reason?"}`. `status` is one of:

  | `status` | Meaning (the original's words) | 007 FR-018 reads it as |
  |---|---|---|
  | `initiated` | the provider took it; the outcome follows | initiated |
  | `completed` | the provider paid synchronously (Cozeni `00`). The outcome is also delivered to the outcome route | completed |
  | `already_initiated` | the provider says it already holds this reference | initiated (finding 5) |
  | `rejected` | refused, with `reason`: `missing_credentials`, `unauthorised` (provider `401`/`403` to the request that makes the payout, every provider), the provider's message, `unsupported_method`, `unsupported_currency` | rejected |
  | `in_doubt` | unreachable, timed out or unreadable. The provider may hold it | in doubt |

- **Repeats:** a repeat under the same `requestId` answers the recorded answer and calls no provider.
  A repeat naming another brand is `409`. A credential that is empty is `rejected` / `missing_credentials`,
  never "no record" (US3).

## `GET /v1/payments/withdrawals/{requestId}` (FR-009)

`{"requestId", "status": "completed|failed|reversed|pending|no_record", "providerReference?",
"reason?"}`.

- For a provider with a status to ask (Unity Finance), the provider is asked.
- For one without, the session's recorded outcome is answered: `pending` until a webhook settles it.
- `404` when the gateway holds no session for the request.
- `502 {"error":"status_unavailable"}` when the provider cannot be read, or the credential is empty.
  The original answers the same, and it is never `no_record`.

## The outcome the gateway delivers: `POST /v1/casino/brands/{brand}/withdrawals/{requestId}/outcome`

- **Body:** `{"outcome": "completed|failed|reversed", "providerReference", "reason?"}`.
- **Idempotency:** the request id and the outcome. 007 FR-020 accepts a repeat and moves nothing.
- **Answers:** read as `merchant`'s are ([merchant.md](merchant.md) §3).
- **Cadence:** a payout outcome gets the same retries, undelivered listing and re-drive as a deposit's
  (*Changes*: the original's call was fire-and-forget).
