# exchange

A brand's order books (spec 023): ankka-predictions' `exchange-components` at a pinned version
(`PREDICTIONS_VERSION`), with this service supplying what the library leaves to its consumer: the gate
port onto `players`, `kyc` and `responsible-gambling`, the ledger port onto the wallet's spec 022
routes, each book's terms, the reads, the facts, reconciliation and the erasure. The design is
`specs/023-predictions-module/plan.md`; the decisions and what established them are its `research.md`.

## Build and test

```bash
(cd ../.. && sbt contract/publishLocal)   # the contract this build depends on
sbt test                                  # Docker must be running: the integration kit starts Postgres
```

From the repository root: `just exchange-test`, `just exchange-it` (the integration suites only) and
`just exchange-perf` (SC-002 at 200 orders a second, the bounds asserted).

## The components

The library's own (the book entity, the placement workflow, the positions view) are registered from
`ExchangeComponents`, built from this service's ports. Beside them:

| Kind | Name | What it holds |
|---|---|---|
| key value | `exchange-market-terms` | a book's currency and limits, written when `markets` opens it (FR-003) |
| workflow | `settlement-retry` | a wallet call the wallet refused or did not answer, retried until done or abandoned by an operator (R5) |
| consumer | `settlement-retry-audit` | an `exchange.settlement_retry` audit record for each retry's start and end |
| key value | `exchange-tally`, `exchange-market-of` | per market, what the books, the placements and the wallet say moved; which market a key belongs to (R11) |
| consumer | `exchange-tally-exchange`, `exchange-tally-placements`, `exchange-tally-wallet` | the three sides of the tally, and the daily and final checks' timers |
| timed action, consumer | `exchange-reconcile`, `exchange-reconcile-audit` | compares the three sides; a difference is an audit record (`exchange.reconciliation_difference`) |
| keyed view | `orders-by-owner`, `trades-by-owner` | a player's orders and trades, each with the sequence it has seen (FR-005) |
| key value | `exchange-erased` | a player's erasure: when, the orders it cancelled, the positions it left (FR-008) |
| consumer | `exchange-order-facts`, `exchange-trade-facts`, `exchange-price-facts` | the brand's `exchange.orders`, `exchange.trades` and `exchange.prices` topics, keyed by the market (FR-006) |
| key value, consumer | `exchange-observed`, `exchange-price-observations` | a minute's throttle per outcome, and the prices posted to the reasoning service; only when the brand names one (FR-007) |

## The routes

One endpoint per audience, none sharing a prefix (`contracts/routes.md`):

```text
player     POST /orders/{market}                      place; answered when the placement ends
           POST /orders/{market}/{order}              {"cancel": true}
           GET  /markets/{market}[?outcome=]          prices, or an outcome's depth
           GET  /stream/{market}                      server-sent events, by sequence
           GET  /players/{player}?read=orders|positions|trades
service    POST /markets/{market}/{command}           markets only: open, suspend, resume, close, finalise
           POST /erasure/players/{player}             players only
operator   POST /retries/{key}                        {"abandon": true, "reason": …}
```

## The suites

| Suite | What it holds the service to |
|---|---|
| `ports/GatesSuite`, `ports/LedgerSuite` | the ports against scripted neighbours: each gate's refusal, the ledger's answers and its patience |
| `features/*Features` | `features/predictions/*.feature`, bound against the kit |
| `it/*ItSuite` | the integration kit over Postgres, with fake gates and wallet: placement, retries, settlement over a restart, reconciliation, the read routes, erasure |
| `reads/ReadRowsSuite`, `reconcile/TallySuite`, `facts/*Suite` | the rows, the tally and the facts, unit by unit |
| `NoRulesSuite` | SC-001: no source outside `ports/` and `api/` builds a domain command or applies the domain |
| `perf/PlacementPerfSuite` | SC-002: three seconds at 200 orders a second by default, figures printed; `EXCHANGE_PERF=1` runs ten and asserts the median and p99 |
