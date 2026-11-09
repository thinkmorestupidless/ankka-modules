# reporting

A brand's reports (spec 015): the revenue report, the dashboard, the player report and the ledger, the
bonus and provider reports, the dispute report, the day-end with its reconciliation, the compliance
exports and the prediction markets' reports. Every figure is a fold of the brand's facts, read from
views, never a sample (constitution II). An ankka 0.16 service in Scala, one sbt build.

## What it is

The components are `ReportingService.descriptors`. Every intake and day id begins with the settings'
generation (`g<n>/…`, research R8), so a rebuild replays the topics into a new generation while the
old one serves.

| Component | Kind | What it holds or does |
|---|---|---|
| `money-facts` → `WalletBook` | consumer of `money.transactions`; event sourced, id `g<n>/<player>/<currency>` | each wallet movement with the balances and pools after it, the open restricted funds records, the last 1,000 fact ids |
| `payments-facts` → `Payment` | consumer of `payments.payments`; event sourced, id `g<n>/<payment>` | a deposit's attempt, capture and dispute (FR-011, FR-007) |
| `withdrawals-facts` → `Withdrawal` | consumer of `casino.withdrawals`; event sourced, id `g<n>/<payout>` | a withdrawal's request and end, its gateway and hours (FR-011) |
| `rewards-facts` → `Grant` | consumer of `casino.rewards` (with the casino module); event sourced, id `g<n>/<bonus>` | a bonus grant's value and outcome (FR-009) |
| `players-facts` → `PlayerLog` | consumer of `casino.players`; event sourced, id `g<n>/<player>` | registration, status, KYC, RG cases and limits, the test-account flag, the email as `Personal` |
| `sessions-facts` → `Session` | consumer of `casino.sessions`; key value | a login or game session, its address and agent as `Personal` |
| `rounds-facts`, `round-legs` → `RoundCheck` | consumers of `casino.rounds` and `WalletBook`; key value, id `g<n>/<round>` | a round as the provider and the wallet each recorded it (FR-017, FR-018) |
| `Coverage` | key value, id `g<n>/<topic>` | the first complete day of each topic in the generation (FR-004) |
| `to-days-wallets`, `-payments`, `-withdrawals`, `-grants`, `-players`, `-markets` | consumers (one per source) | each intake event folded into its days' `BrandDay`, under its source and sequence |
| `BrandDay` | event sourced, id `g<n>/<day>` | a reporting day in the brand's zone, per currency: money, providers, gateways, the hours, the markets; fixed once at its close (`DayFixed`), then corrections |
| `days`, `open-days` | views of `BrandDay` | the days the reports and the dashboard read; the open days the closer reads |
| `FoldProgress` | key value, id `g<n>/<consumer>` | per `to-days` consumer, the latest fact time it folded and when it last read an event |
| `close-days` | recurring timed action, every 5 minutes | closes every day whose end plus `CLOSE_GRACE` has passed, once every `to-days` consumer has folded a fact past that or read nothing for `CLOSE_IDLE` (research R7), with what the views hold then (`DayEndInputs`) |
| `wallets`, `players`, `sessions`, `payments`, `withdrawals`, `rounds`, `player-days`, `activity`, `transactions`, `grants`, `compliance` | views | the reads the reports, the dashboard, the ledger, the dispute report and the compliance exports answer from |
| `ReportingSettings` | key value, id `brand` | what an operator sets: the Reports tab, test accounts, the default currency, the synchronous limits, each kind's lifetime |
| `Document`, `documents` | event sourced, id = reference; view | a document's record from asked to served, expired or failed (FR-021, FR-026) |
| `document-job` | workflow, id = reference | an export or dispute report over the synchronous limit, a page at a time, then kept for its lifetime |
| `sweep-documents` | recurring timed action, hourly | expires the documents whose lifetime has ended |
| `document-audit` | consumer of `Document` | `report.generated` and `report.served` on `platform.audit` |
| `trades-facts` → `MarketBook`, `markets`, `market-figures`, `traders`, `fee-credits` → `TradeFee`, `trade-fees` | with the predictions module only | `exchange.trades` and `markets.events` folded per market, per player and day, and the fee account's credits beside each trade's fee (FR-028–FR-031) |
| `ReportingEndpoint` | HTTP, prefix `/v1/casino/brands/<slug>/reporting` | every route; [routes](../../specs/015-reporting/contracts/routes.md) |
| `Erasure.handler` | erasure handler | an erasure filed at the installation: the subject's objects and every kept document naming the player (R10) |

A rule's code lives in a pure package beside the components that use it: `figures` (GGR, NGR, rates,
the trend arrow), `ranges` (periods, buckets, labels), `days` (the zone's days and hours), `render`
(money, CSV, the dispute report's HTML, CSV and PDF), `ledger` (the original's 14 transaction types).

## The settings

What the service is told at start (`Settings.fromEnvironment`); each is refused at start, naming the
variable, when it cannot be read.

| Variable | Default | What it is |
|---|---|---|
| `CASINO_BRAND` | (required) | the brand's slug, in the route prefix |
| `CASINO_PROJECT` | the brand | the brand's project |
| `CASINO_REAL_MONEY` | `false` | whether the brand takes real money |
| `CASINO_REPORTING_ZONE` | `UTC` | the zone a reporting day is counted in, attribution's |
| `CASINO_MODULES` | none | the modules the brand runs, comma separated: `casino` reads `casino.rounds` and `casino.rewards`; `predictions` reads `markets.events` and `exchange.trades` and serves the prediction routes |
| `CLOSE_GRACE` | `15m` | how long a day waits after its end before it closes, attribution's, so both close at one instant |
| `CLOSE_IDLE` | `15m` | how long a `to-days` consumer must have read nothing (or, having read nothing, the service have run) before a due day stops waiting for it |
| `REPORTING_GENERATION` | `1` | the generation the intake and the days are built and served in |
| `ANKKA_AUTH_*` | | the player and staff issuers (`Oidc.authenticate`) |
| `ANKKA_KAFKA_BOOTSTRAP_SERVERS` | none | the broker; without one the facts are read in memory (a local run) |
| `ANKKA_KEYRING_ADDRESS` | none | the installation's keyring; without one personal fields are sealed under keys in the service's own database |

The module's manifest, [`modules/reporting/module.json`](../../modules/reporting/module.json), sets
them from a brand's values: `CASINO_PROJECT`, `CASINO_BRAND`, `CASINO_REAL_MONEY`,
`CASINO_REPORTING_ZONE` (`reporting-zone`), `CASINO_MODULES`, `CLOSE_GRACE` (`15m`),
`REPORTING_GENERATION` (`1`) and the `ANKKA_AUTH_*` issuers, with object storage provisioned and a
retention of 90 days. The vendor's descriptor format has no `personal` field yet, so `service.json`
declares it for a local run only (research, T005).

What an operator changes is `ReportingSettings`, through `GET`/`PUT /settings` (`brand_admin`,
`platform_admin`; a `PUT` changes only what it names):

| Field | Default | Range |
|---|---|---|
| `reportsTab` | `true` | off, every `/reports/*` route and its exports answer `404 not_found` (FR-024) |
| `excludeTestAccounts` | `false` | leaves test accounts out of the dashboard and the revenue report |
| `defaultCurrency` | none | a currency code; none, the currency most wallets are in |
| `syncRows` | 500 | 1 to 10,000 rows answered in the request; over it a `document-job` |
| `syncDays` | 7 | 1 to 365 days of a dispute report's range answered in the request; over it a `document-job` |
| `lifetimeHours` | 24 for every kind | 1 to 2,160 hours, per kind (`report-export`, `dispute-report`, `day-end-export`, `compliance-export`) |

## Run it locally

```bash
export CASINO_VERSION=0.2.0-015-SNAPSHOT     # a version of this branch's own (other worktrees publish 0.2.0-SNAPSHOT)
sbt contract/publishLocal                    # from the repository root: the contract this build depends on
cd services/reporting
CASINO_VERSION=$CASINO_VERSION sbt schema && docker compose up -d   # Postgres on 5445 with ankka's schema
cd - && just reporting-run goldvibe          # the service on :9015 as goldvibe, Africa/Johannesburg
just modules render goldvibe --modules core,casino,reporting   # this service as goldvibe's brand would run it
```

`just reporting-run` reads the facts in memory (no `ANKKA_KAFKA_BOOTSTRAP_SERVERS`) and verifies
tokens against the kind installation's casino Keycloak, as rewards' run does, so an operator's token
is needed for every route but `POST /erasure/players/{playerId}`:

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:9015/v1/casino/brands/goldvibe/reporting/dashboard/kpis
curl -s -H "Authorization: Bearer $TOKEN" localhost:9015/v1/casino/brands/goldvibe/reporting/reports/ggr
curl -s -H "Authorization: Bearer $TOKEN" localhost:9015/v1/casino/brands/goldvibe/reporting/status
```

## Test it

| Command | What it runs |
|---|---|
| `just reporting-test` | publishes the contract, then the unit suites: the pure packages, the entity, workflow and consumer kits (`day`, `days`, `figures`, `intake`, `ledger`, `predictions`, `ranges`, `reads`, `render`, `settings`), and `port.CoverageSuite`, which ties every recording of `tests/port/reporting/` to its parity test offline |
| `just reporting-it` | the integration kit, the parity tests and the bound features over Postgres in Docker, an in-memory broker and a clock the test moves: `RevenueSuite`, `DashboardSuite`, `LedgerSuite`, `DayEndSuite`, `DisputeSuite`, `DocumentsSuite`, `PredictionsSuite`, `AuditSuite`, `DumpSuite`, `AccessSuite`, `ReportsParity`, `DashboardParity`, `DisputeParity`, and every scenario of `features/reporting/*.feature` |
| `just reporting-perf` | on purpose: 10,000 players and a month; the revenue report under 2 s and every dashboard route under 1 s (SC-001, SC-002; `REPORTING_PERF_PLAYERS` overrides the 10,000) |
| `just reporting-parity` | the parity tests alone (T071): each recording of `tests/port/reporting/` replayed on the seed's world (`port/World.scala`) and compared by shape; the divergences, declared and pending, are `port/Divergences.scala` |

Each runs in this directory with `sbt`; `CASINO_VERSION` names the contract's version. One kit is
shared by every integration suite of a JVM (`SharedKit`), each suite on days of its own (`Kit.days`)
or a today of its own (`Kit.freshToday`). The integration suites need Docker.

## Where it came from

The original's reports are `C/ReportingRoutes.scala`, `C/DashboardRoutes.scala` and `ReportService` in
`eitheror-platform`; its routes keep their paths under this service's prefix, and what changed is in
[routes.md](../../specs/015-reporting/contracts/routes.md) (*Change*). The day-end, the compliance
exports and the prediction markets' reports are new. Decisions made while building it are in
[research.md](../../specs/015-reporting/research.md), *Found during implementation*.
