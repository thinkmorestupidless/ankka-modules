# public-api

A brand's read-only REST API over its players' records (spec 016): players, sessions, rounds, bets,
transactions, bonus awards, games and statistics, under the original's contract
(`src/main/resources/public-api-v1.openapi.yaml`). Every call carries an access key that one of the
brand's operators created in the backoffice; identifying fields are served only to a key allowed them,
and every field served is audited. An ankka 0.16 service in Scala, one sbt build.

## Run and test

```bash
export CASINO_VERSION=0.2.0-016-SNAPSHOT   # this worktree's contract
just public-api-test                        # unit suites: pure packages and the contract checks
just public-api-it                          # integration, parity and feature suites (Docker)
just public-api-parity                      # the recordings of tests/port/public-api replayed (Docker)
just public-api-perf                        # the brand at its size, on purpose only (Docker; about an hour)
just public-api-record                      # records the original on a local kind cluster (tests/original/public-api-driver)
just public-api-run                         # on :9016 as goldvibe; `sbt schema && docker compose up -d` first
```

The perf suite's sizes are `PUBLIC_API_PERF_ROUNDS` (1,000,000) and `PUBLIC_API_PERF_PLAYERS`
(10,000); a smaller run checks it works before the full one.

## The components

| Component | Kind | Holds |
|---|---|---|
| `AccessKey` | event sourced entity | one key: its name, digest, expiry, identifying fields, rate, enabled and deleted |
| `AccessKeys` | view | the brand's keys, for the operator routes |
| `KeyWindow` | key value entity | a key's requests in the current minute (the rate) |
| `KeyLog`, `KeyUse` | event sourced entity, view | every read through a key, and each key's last use |
| `KeyAudit`, `ReadAudit` | consumers | `access_key.*` and `public_api.*` records to `platform.audit` |
| `PlayerRecord`, `SessionRecord`, `RoundRecord`, `BetRecord`, `TransactionRecord`, `BonusAwardRecord` (and two indexes) | key value entities | each record the contract serves, folded from its facts (`Intake`'s consumers) |
| `Players`, `Sessions`, `Rounds`, `Bets`, `Transactions`, `BonusAwards` | views | the records by id and by parent, in the contract's order |
| `StatsPointer` | key value entity | the two snapshot slots and which is current |
| `GameFigures`, `PlayerFigures` (and their views) | key value entities | a snapshot's figures per game and per player, with the players' ranks |
| `ComputeStats` | timed action | the hourly run, at minute 5 |

Identifying fields are held as `Personal` (sealed under the installation's keyring) and erased
through ankka's erasure (`Erasure`).

## The routes

| Prefix | Routes | Admission |
|---|---|---|
| `/v1` | the contract's 22 operations, `/v1/docs`, and the player's erasure | an access key (`Authorization: Bearer ak_…`); the erasure, `players` only |
| `/access-keys/{brand}` | list, create, read, change, disable, enable, delete | an operator of the brand with `api_keys`, or `platform_admin` |
| `/public-api/{brand}/status` | when the figures were computed, stale or not, each view's latest record | an operator of the brand |
| `/health` | `{"status":"ok"}` | none |

## Creating a key locally

With the service running (`just public-api-run`) and an operator token from the kind installation's
`eitheror` realm holding `brand_goldvibe` and `api_keys`:

```bash
curl -s -X POST localhost:9016/access-keys/goldvibe -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"bi","identifyingFields":[],"requestsPerMinute":600}'
# {"key":{"id":…,"prefix":"ak_…",…},"secret":"ak_…"}   the secret is shown once
curl -s localhost:9016/v1/players -H "Authorization: Bearer <secret>"
```

## The suites

- Unit (`sbt test`): `keys`, `wire`, `records`, `games`, `stats`, `audit`, `api` and `contract`,
  with the contract's four checks below.
- Integration (`it`, Docker): every route through the HTTP server over Postgres, on one kit per test
  JVM (`SharedKit`), a second brand's instance (`other`), a kit for peers (`peered`) and one for
  player rankings (`ranked`).
- Features (`features`): `features/public-api/*.feature`, bound against the kit.
- Parity (`port`): the original's recordings replayed; each suite skips, naming its recording, while
  `tests/port/public-api/` lacks it.
- Perf (`perf`): `StatsPerfSuite` (SC-002, SC-003, SC-008).

## The contract

`src/main/resources/public-api-v1.openapi.yaml` is the contract, served as it is at
`GET /v1/openapi.json` and embedded in `GET /v1/docs`. It changes only by a reviewed commit to that
file together with `specs/016-public-api/contracts/amendments.md`, which lists every JSON path where
it may differ from the original's copy (`contracts/original/`). Four suites fail the build on any
other change, and CI runs all four:

| Suite | Holds |
|---|---|
| `contract.ContractSnapshotSuite` | the served document is the file; `/v1` answers exactly its 22 operations; the page cap is the file's |
| `contract.ContractAmendmentSuite` | the file differs from the original only at the amendments' paths |
| `contract.ContractValiditySuite` | the document parses as OpenAPI 3.1 with every `$ref` resolved |
| `it.ContractResponsesSuite` | every route at every status it documents, each body valid against its schema (Docker) |

A breaking change is a new version at a new prefix, with `/v1` served beside it for at least six
months (FR-006).
