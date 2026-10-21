# Contract: what `merchant` sends the brand's wallet

The two routes spec 001 FR-016 grants `payments`/`merchant` on every brand, as spec 002 built them
(`specs/002-wallet/contracts/routes.md`, *Granted routes*; `Layout.BrandGrantsMade`). Called from
the shared project as `service:payments/merchant`; the wallet's ACL admits exactly that identity
(`Routes.granted("wallet")`). `{brand}` is the sub-brand's `brand`; `{player}` is the player part
of the customer id; `{currency}` the payment's.

## `POST /v1/casino/brands/{brand}/wallets/{player}/{currency}/deposits` — the credit (FR-021)

From the `DepositWorkflow`'s `credit` step. Body: `paymentId` (the key), `amount` (the credited
amount: the invoice, or an underpayment's valuation), `terminal`, `psp`, `scheme`, `paymentProvider`,
`paymentReference` (the provider's transaction id, spec 002 FR-022).

| The wallet answers | `merchant` does |
|---|---|
| `200` balances, `txnId`, `depositOrdinal`, `turnoverRequired`, `depositSums` | records `credited`; acknowledges the gateway |
| `503 OUTCOME_PENDING`, any other 5xx, a timeout, a failed connection | `credit-wait`, then again (1 s doubling to 30 s, without bound) |
| `404 WALLET_NOT_FOUND`, `400 CURRENCY_MISMATCH` | final: records the refusal; stops; the payment is listed as uncredited |
| `403 EXCLUDED`, `403 COOLING_OFF` (`endsAt`), `409 OVER_LIMIT` (`period`, `limit`, `used`, `remaining`) | final: records the refusal with its details (spec 010 FR-013); stops; shown to the player in their currency |
| `503 RG_UNAVAILABLE` | retry as a 5xx (the wallet's workflow answers the repeat) |

A repeat under the same `paymentId` is the same deposit at the wallet (spec 002 R17): a lost answer
is retried and the first outcome returned (US3 scenario 2).

## `POST /v1/casino/brands/{brand}/payments/{paymentId}/outcome` — the outcomes (FR-022)

From the `OutcomeReporter` consumer (research R7), for every outcome but the capture. Body:
`outcome` (`initiated` \| `failed` \| `expired` \| `abandoned`), `playerId`, `paymentProvider`,
`terminal?`, `amount` and `currency` (required for `initiated`), `code?`, `reason?`. Answers
`recorded`, `firstRecorded`; a repeat is `recorded: false` and nothing changes. The consumer retries
any non-2xx by its own at-least-once delivery. `refunded` and the dispute outcomes are spec 005's
to report when a provider reports them; the body carries the provider likewise.

A sub-brand that names no brand makes neither call.
