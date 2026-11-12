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
