# kyc

A brand's identity verification (spec 009): the player's documents, uploaded straight to the brand's
document store through signed links; the operator's review of each; the account's KYC status, derived
from the documents and the profile or set by an operator; the brand's KYC policy; the deposit and play
gates the brand's other services ask; the verification window and its suspension; the queue the
compliance team works from; and erasure. An ankka 0.13 service in Scala, one sbt build.

## What it is

| Component | Kind | What it holds or does |
|---|---|---|
| `KycRecord` | event sourced, id = player | documents and offers, status, mode, required categories, flags, source of funds, lifetime deposits per currency, the profile's presence, the deadline |
| `record.Rollup`, `record.Completeness`, `record.Gates` | pure Scala | the original's derivation, its profile rule, the two gates |
| `PolicyEntity` | event sourced, id = brand slug | the policy: gates, KYC currency, thresholds, window, required categories, suspension; set from a licence profile (Curaçao, MGA, UKGC) or field by field |
| `KycQueue` | view of the record | one row per player, no personal column; the queue's filters and counts, and `PolicyFlagger`'s searches |
| `ProfileConsumer`, `DepositsConsumer`, `RegistrationConsumer`, `ClosureConsumer` | consumers of `casino.players`, `money.transactions` | the profile's presence, deposits counted once per ordinal, the deadline, closure |
| `PolicyFlagger` | consumer of the policy's events | a tightened threshold flags each player over it, once |
| `RecordTimers`, `WindowTimer` | consumer, timed action | `kyc-window-<player>` at the deadline; flags and, when the policy says, suspends through `players` |
| `KycPublisher`, `AuditPublisher`, `PolicyAuditPublisher` | consumers | the `player_kyc_*` facts on `casino.players`; every operator decision on `platform.audit`, a note named, never written |
| `PlayerEndpoint`, `PublicEndpoint`, `OperatorEndpoint`, `ServiceEndpoint` | HTTP | one per audience; [routes](../../specs/009-kyc/contracts/routes.md), and [wire](../../specs/009-kyc/contracts/wire.md) for the original's names |

The gates read the record and the policy and call nothing out: they answer in a few milliseconds and do
not fail because a neighbour is down. A refusal is `200` with `allowed: false`, never an outage.

## The document store

Documents never pass through the service. An upload is offered as a signed `PUT` to
`subjects/<player>/kyc/<category>-<millis>.<ext>` in the brand's bucket; the confirm checks the object's size and
type there and records it; a view is a signed `GET` for 15 minutes. The bucket and its credential come
from the platform as `ANKKA_S3_*`, all six or none; with none, every upload and view answers
`503 storage_unavailable`.

Encryption at rest is the store's (research R17): Google Cloud Storage encrypts every bucket, with the
installation's key when it names one, which a real-money installation's store is. Garage, the kind
installation's store, holds objects on its volume unencrypted; on kind, encryption at rest is the
volume's. The service holds no key.

## Run it locally

```bash
sbt contract/publishLocal                    # from the repository root
cd services/kyc
sbt schema && docker compose up -d           # Postgres on 5438, Garage on 3900
cd ../.. && just kyc-run                     # the service on :9012, players expected on :9005
```

Tokens are verified against the kind installation's casino Keycloak (`just kyc-run` names it). The gates
need no token locally:

```bash
curl -s -X POST localhost:9012/v1/casino/brands/goldvibe/kyc/deposit-check \
  -H 'Content-Type: application/json' -d '{"playerId":"p1","amount":5000,"currency":"EUR"}'
curl -s localhost:9012/v1/casino/brands/goldvibe/kyc/p1/play-check
curl -s localhost:9012/v1/casino/kyc-requirements/goldvibe
```

| Setting | Default | |
|---|---|---|
| `CASINO_BRAND` | — | required |
| `KYC_MAX_UPLOAD_BYTES` | `5242880` | the largest document the brand takes |
| `KYC_SUSPEND_RETRY` | `30s` | the wait before the window's suspend is asked again |
| `ANKKA_S3_ENDPOINT`, `_PUBLIC_ENDPOINT`, `_REGION`, `_BUCKET`, `_ACCESS_KEY`, `_SECRET_KEY` | none | the bucket, written by the platform |

The policy is not an environment setting: an operator sets it at `/v1/casino/backoffice/<slug>/kyc/policy`.

## Test it

| Command | What it runs |
|---|---|
| `sbt "testOnly io.eitheror.casino.kyc.record.* io.eitheror.casino.kyc.policy.* io.eitheror.casino.kyc.facts.* io.eitheror.casino.kyc.queue.* io.eitheror.casino.kyc.consumers.*"` | the rollup and its original tests, the gates, every command of the record and the policy, the facts and audit records, the queue's row, each consumer's mapping |
| `sbt "testOnly io.eitheror.casino.kyc.features.*"` | every `features/kyc` scenario against the whole service |
| `sbt "testOnly io.eitheror.casino.kyc.it.*"` | over HTTP against Postgres and Garage in Docker: uploads to the store, the operator's routes, each caller's decoder (`CallersSuite`), 1,000 asks of each gate (`GatesLatencySuite`), the window by the sweeper (`WindowSuite`), erasure (`ErasureSuite`), the queue (`QueueSuite`), nothing personal left (`LeakSuite`) |
| `sbt "testOnly io.eitheror.casino.kyc.port.*"` | the original's paths and shapes onto this service's (`WireTranslationSuite`); the recordings' replay (`ParitySuite`) |

`just kyc-test` from the root runs all of it after publishing the contract; `just kyc-it` the
integration, parity and feature suites.

## What is not done

- **Parity.** The original's scenarios 28 and 29 are not recorded yet (`tests/original/e2e-rg-kyc.patch`,
  run against a local original); `ParitySuite` is ignored until they are in `tests/port/kyc/`.
- **Erasure in the journal.** Every personal value (an operator's upload reason, an override or dismissal
  note, the age date) is gone from the state, every fact, the audit, every view row and every answer after
  erasure, but stays in the event journal until ankka 042's `Personal` encrypts it under the player
  (`LeakSuite` names the journal as the one place allowed).
