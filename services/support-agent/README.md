# support-agent

Motoko, a brand's player support agent (spec 017), on ankka 0.16's agent model. It answers a
signed-in player about their account from the parts that own the answer, and asks the owning part to
act once the player approves.

## Run it locally

```bash
cd services/support-agent
CASINO_VERSION=0.2.0-SNAPSHOT sbt schema   # ankka's schema into target/ddl
docker compose up -d                       # Postgres on 5444
cd ../.. && just support-agent-run         # HTTP on 9020, as brand goldvibe
```

## Test it

```bash
just support-agent-test   # the unit suites
just support-agent-it     # the integration, parity and feature suites (Docker)
```
