# Quickstart: proving engagement

In build order. Every command from the repository root unless said. Use
`CASINO_VERSION=0.2.0-014-SNAPSHOT` (memory: contract snapshot collisions). kubectl only with
`--context kind-…`.

## 1. Contract, manifest, tool

```bash
just schemas && just test
CASINO_VERSION=0.2.0-014-SNAPSHOT sbt -batch contract/publishLocal
just modules render goldvibe
```

Expect `FactsSuite` green with `rg_limit_loosening_requested` ([facts.md](contracts/facts.md)),
`ModuleManifestSuite` with `engagement` built, `RenderSuite` with no `PUSH_PROVIDER_KEY`, and the
render showing `engagement` with no secret.

## 2. Neighbours

```bash
CASINO_VERSION=0.2.0-014-SNAPSHOT just service responsible-gambling test   # the request fact
CASINO_VERSION=0.2.0-014-SNAPSHOT just service players test                # consent facts at registration; `refused`
just features
```

Expect the steps added to `features/responsible-gambling/limits.feature` (the raise scenario's
request fact) and to `features/players/registration.feature` (the consent facts) bound and green.

## 3. The stub

```bash
cd tests/stubs/crm-stub && go test ./... && go run . &    # 18201 track, 18202 app
curl -s localhost:18201/__received
```

## 4. The service

```bash
just engagement-test      # pure, entity, consumer kits; GherkinSuite over features/engagement
just engagement-it        # Postgres (Docker): the SC suites below
```

| Suite | Proves |
|---|---|
| `EventsSuite` | every row of facts.md; `handler_test.go`'s candidate order; `payment_failed_test.go`'s normalisation |
| `AttributesSuite` | `buildIdentifyAttributes` by name and type; the seven always sent; the string fallback |
| `DepositDaySuite` | the day boundary at 03:00; the reset at 03:05 selects count > 0 with an earlier stamp |
| `SignatureSuite` | the four refusals in order; `< 300` s; `CustomerIOWebhookRoutesSpec`'s cases |
| `AttemptsSuite` | four attempts at 500 ms, 1 s, 2 s ± 25 %; 429, 5xx, timeout retry; 4xx does not; parked with the last answer |
| `ReplaySuite` (SC-002) | a year of facts twice into an empty journal: no call, every `Send` found or stale |
| `SuppressionSuite` (SC-003) | FR-011's list, suppressed and not, at the first attempt and at a retry |
| `ChannelSuite` (SC-004) | two connections, one signal each within 2 s, one `push_delivered` of 2; across `restartService()`; leases expire at 90 s |
| `LatencySuite` (SC-005) | accepted within 60 s with the stub healthy and after one 503 |
| `DumpSuite` (SC-006) | `pg_dump` holds no readable address, name, date of birth, message text |
| `ErasureSuite` (SC-007) | a `delete` at the stub; a replayed fact silent |
| `RecordsSuite` (SC-008) | accepted calls = `accepted` rows; every refusal, parking, discard audited |
| `WebhookSuite` | each action relayed, each answer passed through, `FakeRewards` and `FakePlayers` scripted |
| `StreamSuite` | `ready`, heartbeat, `signal`, `closing`; `revalidation_failed`, `connection_recycled`, `shutting_down`; `capacity` |
| `AgentStreamSuite` | the ticket once, 30 s, another brand; `player_online` frames; test accounts |
| `ConfigSuite` | masking, the masked round trip, the secret store holding the values, the seed's 30 pairs |

## 5. Run it

```bash
just players-run goldvibe                    # 9003
just engagement-run goldvibe                 # 9013, Postgres 5442; CRM_TRACK_URL/CRM_APP_URL at the stub
```

As an operator, `PUT /v1/casino/crm-config/goldvibe` with the stub's keys and a transactional id for
`DepositConfirmed`; `POST …/seed` and read 30 pairs at `__received`. Register a player, deposit
through the wallet, and read the identify, the `deposit_confirmed` and `first_deposit_confirmed`
tracks and the send at the stub; read the player's sends at `GET /v1/casino/backoffice/goldvibe/players/<id>/sends`.
Open `GET /v1/stream` with the player's token in two terminals, send the player an operator message,
and see one `signal` on each; read `casino.push` for one `push_delivered` of 2 and the
`presence_changed`.

A run on the laptop also needs a Kafka broker (`ANKKA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092`), a
fixed `ANKKA_SECRET_KEY` per service, and wallet, players, kyc and responsible-gambling (or
stand-ins).

## 6. Parity and kind

Record (needs the user: the original's kind stack with the stub in its compose override, operator
credentials, the brand configured against the stub; research R2), move the recordings to
`tests/port/engagement/`, then:

```bash
just modules recordings check
just engagement-test     # ParitySuite over tests/port/engagement/ and the webhook steps of tests/port/rewards/
```

On kind: `just modules apply goldvibe --installation dev-kind` with `engagement` entitled and
`minInstances: 3`; open the stream from two devices, send a message, see both signals (SC-004);
run the seventeen Playwright suites with the player app's `PUSH_GATEWAY_URL` and the backoffice's
base URL pointed at the brand (SC-009).
