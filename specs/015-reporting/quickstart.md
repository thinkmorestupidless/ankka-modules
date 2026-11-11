# Quickstart: proving reporting

In build order (research R19). Every command runs from the repository root. Set a unique contract
version first (other worktrees overwrite `0.2.0-SNAPSHOT` in `~/.ivy2`): `export CASINO_VERSION=0.2.0-015-SNAPSHOT`.

## 1. The contract and the amended producers

```bash
just schemas && just test                     # the contract: the new fields, compatibility, schemas
just service players test                     # test_account, player_test_account_changed, the profile's isTestAccount
just service game-provider test               # game_name, game_type on round_settled
just service wallet test                      # dispute amount, currency, outcome; records' progress
just service merchant test                    # dispute outcomes forwarded
just service psp-gateway test                 # Unity Finance chargeback and chargebacked as disputes
```

Expected: green; each suite holds a test per addition (contracts/facts.md). The wallet's existing suites
pass unchanged (the additions are optional).

## 2. The service, unit and features

```bash
just reporting-test                           # the contract published, then the pure packages, entity, workflow and consumer kits
```

Expected: green. The bound features need the integration kit, so they run in §3, not here.

## 3. Integration (Docker)

```bash
just reporting-it
```

Over a throwaway Postgres in Docker with `InMemoryBroker` and `MovableClock`, the suites below and
every scenario of `features/reporting/*.feature` bound (`features.*`), `FactSet`'s figures equal to the
reports' to the minor unit (SC-004, SC-009):

| Suite | Proves |
|---|---|
| `RevenueSuite` | per currency, the brand's zone, the deposit pool's GGR equal to attribution's fold (SC-005), rebuild identical (SC-004) |
| `DashboardSuite` | more than twenty players summed; sessions counted; payment health from facts; `null` rates |
| `LedgerSuite` | a player with 5,000 transactions, every one paged and exported (SC-003); the email search |
| `DayEndSuite` | the close at the same instant as `ggr-daily`; a fixed day-end byte-identical; a late fact a correction (SC-008) |
| `DisputeSuite` | session and range modes, the refusals, HTML, CSV and PDF, the job over the limit, unmatched rounds |
| `DocumentsSuite` | lifetimes per kind, 1–2,160 hours, the sweep, erasure deleting kept documents, records kept (SC-006) |
| `PredictionsSuite` | the four reports from trades and markets; the dashboard's predictions summary against yesterday to the same hour; fees from `fee_cents`; no casino figure moves (SC-009); no route for a brand without the module |
| `DumpSuite` | `assertNoPersonalValue` over the journal, state, views and document records |
| `AccessSuite` | each route's admission; the flag's 404; another brand refused |

## 4. Performance (on purpose)

```bash
just reporting-perf                           # 10,000 players, a month of facts
```

Expected: the revenue report under 2 s, every dashboard route under 1 s (SC-001, SC-002); the slowest of
five reads of each is printed (`perf:` lines). `REPORTING_PERF_PLAYERS` overrides the 10,000. The ledger's
5,000 transactions (SC-003) are `LedgerSuite`'s, in §3.

## 5. Parity with the original

Needs the original on kind (`kubectl --context kind-…` only) and operator users the person creates.

```bash
just reporting-record                         # tests/original/reporting-driver → tests/port/reporting/
just reporting-parity                         # the parity tests of contracts/recording.md
```

Expected: every recording's shape matches; the marked divergences only.

## 6. Locally, end to end

```bash
cd services/reporting && CASINO_VERSION=$CASINO_VERSION sbt schema && docker compose up -d && cd -
just reporting-run goldvibe                   # HTTP 9015, CASINO_MODULES=core,casino,reporting, facts in memory
just modules render goldvibe --modules core,casino,reporting   # the reporting service with its zone, modules, grace and generation
```

`render goldvibe` alone renders goldvibe's default modules, `core` only, with no reporting service. The
render prints no runtime: `0.16.0` is the manifest's (`modules/reporting/module.json`). Without a token every
route but the erasure answers `401`; the tokens are verified against the kind installation's casino Keycloak,
so an operator's token needs kind and an operator user. Then: `GET /v1/casino/brands/goldvibe/reporting/dashboard/kpis`
answers the original's shape; `GET …/reports/ggr` the month in `Africa/Johannesburg`; `GET …/dashboard/predictions`
is `404` here, since the run's modules do not name `predictions`.
