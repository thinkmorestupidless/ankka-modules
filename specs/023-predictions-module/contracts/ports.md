# Ports onto the brand's services

## The gate port (`exchange`)

| Order | Call | Allowed | Refused as |
|---|---|---|---|
| 1, always | `players` `GET /players/{player}/status` | `active` | `account: <status>` |
| 2, if `play-gate` ∈ `predictions-gates` | `kyc` `GET /kyc/{player}/play-check` | `allowed: true` | `play gate: <code>` |
| 3, if `may-bet` ∈ `predictions-gates`, buys only | `responsible-gambling` `POST /rg/may-bet` `{playerId, currency, amount: quantity × price}` | `allowed` | `bet gate: <decision>` |

Unreachable, a 5xx, or no answer within the client's timeout: `<gate>: unavailable`.

## The ledger port (`exchange`)

As research R4's table. Answers: 2xx `Done`; `INSUFFICIENT_FUNDS` `Insufficient`;
`OUTCOME_PENDING`, 5xx or unreachable: throw; any other code `Refused(code)`, and for `settle`,
`payout` and the fee a `SettlementRetry` is started first (R5). `charge` and `pay` answer
`Refused("not offered")`.

## The resolution port (`markets`)

`Allow` for every question: the operator route has checked the `markets` role (R6).
