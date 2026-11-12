# Quickstart: proving public-api

In build order. Each step names what it proves and the suites that prove it. Contracts:
[routes](contracts/routes.md), [fields](contracts/fields.md), [facts](contracts/facts.md),
[amendments](contracts/amendments.md), [recording](contracts/recording.md).

## 1. Prerequisites

- JDK 21, sbt 1.12.9, Docker (the integration kit's Postgres), `uv` (`just features`).
- A unique contract version for this worktree: `export CASINO_VERSION=0.2.0-016-SNAPSHOT`, so another
  worktree's snapshot is never used.
- For the recordings only: the original's kind installation, run on purpose.

## 2. The contract and the producers

```bash
just build && just test        # contract library, schemas, compatibility
just schemas                   # regenerated; the diff is the new fields only
just service players test
just service game-provider test
just service wallet test
just service rewards test
```

Expected: each producer's suites pass with one new test per amended field; `round_opened` is
published on a round's first bet; the catalogue route answers `public-api` and refuses `engagement`.

## 3. The service

```bash
just public-api-test           # unit and kit suites, GherkinSuite over features/public-api/
just public-api-it             # the integration kit over Postgres
just public-api-perf           # StatsPerfSuite, SC-008
```

| Proves | Suites |
|---|---|
| the contract is the file, amended only as allowed, and every answer matches it (US7) | `ContractSnapshotSuite`, `ContractAmendmentSuite`, `ContractResponsesSuite`, `ContractValiditySuite` |
| keys: created, shown once, listed, disabled, enabled, changed, deleted, expired, audited (US1) | `KeysSuite`, `AdmissionSuite`, `KeyChangeSuite`, `KeyAuditSuite` |
| isolation and the pipeline's order (US2) | `IsolationSuite`, `PipelineSuite` |
| records, paging, money, enums (US3) | `PlayersSuite`, `CollectionsSuite`, `ByIdSuite`, `PagingSuite`, `BoundariesSuite`, `MoneySuite`, `EnumsSuite`, `ProjectionRefusalSuite` |
| identifying fields and erasure (US4) | `IdentifyingFieldsSuite`, `ErasureSuite`, `DiagnosticsSuite` |
| games and game statistics (US5) | `CatalogueSuite`, `CatalogueCacheSuite`, `GamesSuite`, `GameStatsSuite`, `StatsRunSuite` |
| player statistics and snapshots (US6) | `PlayerFiguresSuite`, `PlayerStatsSuite`, `PlayerRankingSuite`, `RankingCrawlSuite` |
| audit, rate and the budget (US8) | `AuditSuite`, `QuotaSuite` (two instances, one database), `BudgetSuite` |

## 4. Run it

```bash
just public-api-run            # the service on :9000 with the kit's seeded facts, two keys printed
curl -s localhost:9000/v1/openapi.json | jq '.info.version'                  # "1.1.0"
curl -s -H "Authorization: Bearer $KEY_NONE" localhost:9000/v1/players | jq '.page'
curl -s -H "Authorization: Bearer $KEY_NONE" localhost:9000/v1/players/$PLAYER | jq 'has("email")'   # false
curl -s -H "Authorization: Bearer $KEY_ALL"  localhost:9000/v1/players/$PLAYER | jq 'has("email")'   # true
curl -s -X POST -H "Authorization: Bearer $OPERATOR" localhost:9000/access-keys/goldvibe/$KEY_ID/disable
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $KEY_NONE" localhost:9000/v1/players   # 403
```

Then check it with `ankka-inspect` against the spec's scenarios: each scenario through the real
endpoints, the state through the views, the traces read.

## 5. Recordings and parity

```bash
just public-api-record         # against the original on kind; writes tests/port/public-api/
just public-api-parity         # replays the 11 recordings
```

Expected: every parity suite passes under contracts/recording.md's comparison rules. Without
recordings, each parity suite skips and names the recording it needs.

## 6. Deploy

```bash
just modules validate --all
just modules render goldvibe   # public-api appears only when the brand is entitled to the module
just modules apply goldvibe --installation dev-kind
```

Then create a key from the operator route on the installation and read a player with it.
