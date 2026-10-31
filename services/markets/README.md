# markets

A brand's prediction markets (spec 023): ankka-predictions' `markets-components` at a pinned version
(`PREDICTIONS_VERSION`), with this service supplying the resolution port, the markets permission, the
brand's categories and currencies, each market's drafted limits, the facts, the catalogue, and the
driver that opens and closes the market's book on `exchange`. The design is
`specs/023-predictions-module/plan.md`; the decisions and what established them are its `research.md`.

## Build and test

```bash
(cd ../.. && sbt contract/publishLocal)   # the contract this build depends on
sbt test                                  # Docker must be running: the integration kit starts Postgres
```

From the repository root: `just markets-test` and `just markets-it` (the integration suites only).

## The components

The library's own (the market entity, its view and its timers) are registered from
`MarketsComponents`, built from this service's resolution port and the timer runtime. Beside them:

| Kind | Name | What it holds |
|---|---|---|
| key value | `market-terms` | a market's drafted limits, or the brand's when the draft sets none (FR-014) |
| consumer | `market-facts` | every change to a market on the brand's `markets.events` topic, keyed by the market (FR-006) |
| consumer | `exchange-driver` | the market's events driven onto `exchange`'s service routes: open, suspend, resume, close, finalise; a `409 state` is taken as done |
| consumer | `market-mirror` | the market posted to the reasoning service; only when the brand names one (FR-007) |

## The routes

```text
operator   POST /drafts                               the markets permission; the brand's categories and currencies
           POST /{market}/{command}                   change, approve, open, suspend, resume, close, move-close, resolve, correct, void
           GET  /{market}                             the market with its record
player     GET  /catalogue[?category=]                open markets
           GET  /catalogue/{market}                   an open, suspended, closed or resolved market
```

## The suites

| Suite | What it holds the service to |
|---|---|
| `terms/TermsSuite` | the drafted limits and the brand's |
| `drive/ExchangeDriverSuite` | each event's call on `exchange`, and a refusal taken as done |
| `facts/MarketMirrorSuite` | the mirror's calls on the reasoning service |
| `features/*Features` | `features/predictions/lifecycle.feature` and `catalogue.feature`, bound against the kit |
| `it/LifecycleItSuite` | the integration kit over Postgres: the lifecycle through the routes |
| `NoRulesSuite` | SC-001: no source outside `ports/` and `api/` builds a domain command or applies the domain |
