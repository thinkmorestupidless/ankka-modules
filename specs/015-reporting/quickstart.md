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
just reporting-test                           # pure packages, entity, workflow and consumer kits, GherkinSuite
```

Expected: every scenario of `features/reporting/*.feature` bound and green; `FactSet`'s figures equal the
reports' to the minor unit (SC-001, SC-004, SC-009).

## 3. Integration (Docker)

```bash
just reporting-it
```

Over Postgres on 5445 with `InMemoryBroker` and `MovableClock`, the suites:

| Suite | Proves |
|---|---|
| `RevenueSuite` | per currency, the brand's zone, the deposit pool's GGR equal to attribution's fold (SC-005), rebuild identical (SC-004) |
| `DashboardSuite` | more than twenty players summed; sessions counted; payment health from facts; `null` rates |
| `LedgerSuite` | a player with 5,000 transactions, every one paged and exported (SC-003); the email search |
| `DayEndSuite` | the close at the same instant as `ggr-daily`; a fixed day-end byte-identical; a late fact a correction (SC-008) |
| `DisputeSuite` | session and range modes, the refusals, HTML, CSV and PDF, the job over the limit, unmatched rounds |
| `DocumentsSuite` | lifetimes per kind, 1–2,160 hours, the sweep, erasure deleting kept documents, records kept (SC-006) |
| `PredictionsSuite` | the four reports from trades and markets; fees from `fee_cents`; no casino figure moves (SC-009) |
| `DumpSuite` | `assertNoPersonalValue` over the journal, state, views and document records |
| `AccessSuite` | each route's admission; the flag's 404; another brand refused |

## 4. Performance (on purpose)

```bash
just reporting-perf                           # 10,000 players, a month of facts
```

Expected: the revenue report under 2 s, the dashboard under 1 s (SC-001, SC-002).

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
just reporting-run goldvibe                   # HTTP 9015
just modules render goldvibe                  # the reporting service with its zone, modules and runtime 0.16.0
```

Then, with an operator's token: `GET /v1/casino/brands/goldvibe/reporting/dashboard/kpis` answers the
original's shape; `GET …/reports/ggr` the month in `Africa/Johannesburg`.
