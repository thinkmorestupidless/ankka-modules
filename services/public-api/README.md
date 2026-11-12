# public-api

A brand's read-only REST API over its players' records (spec 016): players, sessions, rounds, bets,
transactions, bonus awards, games and statistics, under the original's contract
(`src/main/resources/public-api-v1.openapi.yaml`). Every call carries an access key that one of the
brand's operators created in the backoffice; identifying fields are served only to a key allowed them,
and every field served is audited. An ankka 0.16 service in Scala, one sbt build.

## Run and test

```bash
export CASINO_VERSION=0.2.0-016-SNAPSHOT   # this worktree's contract
just public-api-test                        # unit suites
just public-api-it                          # integration, parity and feature suites (Docker)
just public-api-run                         # on :9000 against docker-compose.yml's Postgres (5446)
```

The components and the suites are listed here as they are built (spec 016 tasks).

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
