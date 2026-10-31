# Data model: the predictions module

The market, the book, the order, the trade and the position are the libraries' (ankka-predictions
specs 005 and 006) and are not restated. What this module adds:

## `markets`

| Entity | Id | Fields | Written by | Read by |
|---|---|---|---|---|
| `MarketTerms` (key-value) | market | `maxOrderCost: Option[Long]`, `maxPosition: Option[Long]` as drafted | the draft and change routes, before the library | `ExchangeDriver` at `Opened` |

`ExchangeDriver` (consumer over `market`): at `Opened` calls the exchange's open with the market's
outcomes and currency, unit 100, the limits (`MarketTerms`, else the values), the fee rate; at
`Suspended`, `Resumed`, `Closed`, `Final` the matching call. A `state` refusal is done; anything
else is audited.

Validation before the library (FR-013): category ∈ `predictions-categories`; currency ∈
`predictions-currencies`; a drafted limit, when given, is above zero.

## `exchange`

| Entity | Id | Fields | Written by | Read by |
|---|---|---|---|---|
| `MarketTerms` (key-value) | market | `currency`, `outcomes` | the open route, before the library's `Open` | the ledger port, the reads |
| `SettlementRetry` (workflow) | the wallet call's key | the call (route, body), attempts, the last refusal, state `retrying` / `succeeded` / `abandoned` | the ledger port, on a refusal | the operator's abandon route |

Views:

| View | Row id | Columns | From |
|---|---|---|---|
| `orders-by-owner` | order | market, owner, outcome, side, price, quantity, filled, state (`resting`, `filled`, `cancelled`, `expired`), sequence | the exchange's events |
| `trades-by-owner` | trade/owner | market, owner, outcome, side, price, quantity, through, at | `TradeMade` legs |
| `reconciliation` | market/family | the wallet's sum, the exchange's sum, the last fact seen | `money.transactions` and the exchange's events |

The gate's refusal reasons (FR-002): `account: <status>`, `play gate: <code>`, `bet gate:
<decision>`, each `<gate>: unavailable` when the gate cannot answer.
