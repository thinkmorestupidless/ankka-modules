# Wire translation for parity: payouts

`WireTranslation` turns a request recorded against the original into this service's request, and this
service's answer into the shape recorded (research R2). Each row is one test in `WireTranslationSuite`.

Abbreviations:

- `<b>` is the brand and `<p>` the player;
- `<r>` is the request id the original returned, and `<id>` the one this service returned for the same
  step (captured by the replay);
- `<d>` is a destination id, captured likewise.

## Amounts

The original's player routes take and answer decimals of major units. They become minor units with
the currency's exponent: × 100 for EUR, GBP and CAD. The answer's `amount`, `forfeitedAmount`,
`clawbackAmount`, `maxWithdrawable`, `totalForfeit`, `bonusBalance`, `refundedAmount`, `voidedReturnedAmount` and the limit refusal's `weeklyLimit`,
`weeklyUsed`, `weeklyRemaining` (and their `monthly` forms) are translated back. A recorded decimal with more places than the exponent is a test failure, not a
rounding. The cancel's `amount` is the exception: the original answered it in minor units beside a decimal
`refundedAmount`, so it is compared untranslated.

## Routes

| Original request | Here | Answer translated |
|---|---|---|
| `POST /v1/casino/brands/<b>/players/<p>/withdrawal-requests {amount, currency, terminalId, …, confirmForfeit}` | the same path and field names, `amount × 100`, the currency the wallet's when absent, and `key: <a fresh key>` | 201 body with `requestId` → `<r>` |
| `GET …/players/<p>/withdrawal-requests` | the same | `{requests}`: only `PENDING_REVIEW` rows compared, as the original listed only those |
| `DELETE …/withdrawal-requests/<r>` | `DELETE …/withdrawal-requests/<id>` | `amount` as it is; `requestId` with the original's `withdrawal-request/` prefix |
| `GET …/players/<p>/withdrawal-terminals?currency=` | the same; a missing currency becomes the wallet's | `{terminals}` compared by id and order |
| `GET`, `POST …/payout-destinations` | the same | `iban` compared by its last four |
| `PUT …/payout-destinations/<d>/set-default` | `PUT …/payout-destinations/<d> {setAsDefault: true}` | the same |
| `DELETE …/payout-destinations/<d>` | the same | the same: 204, no body |
| `GET /v1/casino/brands/<b>/withdrawal-requests?…` | the same | rows compared by id, status and amount, untranslated: the original's queue answered minor units |
| `POST …/withdrawal-requests/<r>/approve`, `/reject`, `/confirm-payout`, `/mark-payout-failed` | `…/<id>/…` | `status` compared; `payoutStatus` compared after the workflow's first step |
| `GET …/players/<p>/withdrawal-history` | the same | the same |
| `GET`, `PUT /v1/casino/brands/<b>/withdrawal-config {…, weeklyWithdrawalLimitEur, monthlyWithdrawalLimitEur}` | `{limits: {EUR: {weekly × 100, monthly × 100}}}`; the turnover fields dropped | the limits back in the original's names |
| `POST /internal/v1/casino/withdrawal-requests/<r>/outcome` with `X-Internal-Secret` | `POST /v1/casino/brands/<b>/withdrawals/<id>/outcome` as `payments`/`psp-gateway`, the secret dropped | the same |

## Statuses and errors

- Statuses keep the original's upper-case words on the wire (`PENDING_REVIEW`, `AWAITING_MANUAL_PAYOUT`, …).
- The original's 500 for the hold's turnover refusal is compared as R4's 402 `WITHDRAWAL_BLOCKED_TURNOVER` (a marked divergence).
- The original's `PLAYER_NOT_FOUND` during an outage is not compared: outages are not recorded.
