# payouts

A brand's withdrawals (spec 007):
- the player's request, held at the wallet behind the original's gates in the original's order;
- the terminals a player may withdraw to, and their saved bank destinations;
- the operator's review, then the payout through the payments project's gateway;
- the provider's outcome, settled exactly once, by its delivered answer or a status read;
- the brand's queue and limits, and erasure.

An ankka 0.13 service in Scala, one sbt build.

## What it is

| Component | Kind | What it holds or does |
|---|---|---|
| `PlayerPayouts` | event sourced, id = player | what payouts knows of a player: KYC status, names and email from `casino.players`, deposits by provider and scheme, saved destinations, the requests the limits count, the registration currency |
| `WithdrawalRequest` | event sourced, id = request | the request: amount, terminal, payee, forfeit, status and payout status, each decision and outcome, the provider's reference |
| `WithdrawalWorkflow` | workflow, id = request | the request's life: hold, open, review, then approve, initiate, wait and poll, confirm, release or reverse; one keyed call at a time to each neighbour |
| `WithdrawalLimits` | event sourced, id = brand slug | the brand's weekly and monthly limits per currency |
| `TerminalCatalogue`, `CatalogueTimer` | key value, id = brand slug; timed action | the gateway's listing per currency, refreshed every `refreshEvery` |
| `request.Gates`, `terminals.Eligibility`, `workflow.Approval`, `player.Iban`, `player.Names` | pure Scala | the original's gate order, the terminal rule (ADR 0038), automated or manual payout, the IBAN check, the name match |
| `WithdrawalQueue` | view of the requests | one row per request, no personal column but the IBAN's last four; the queue's filters, the player's list, the stale count |
| `PlayersConsumer`, `DepositsConsumer`, `RequestsConsumer` | consumers of `casino.players`, `money.transactions`, the requests | the player's record, the deposits, the limits' counts |
| `WithdrawalPublisher`, `AuditPublisher`, `PlayerAuditPublisher`, `ConfigAuditPublisher` | consumers | the `withdrawal_*` facts on `casino.withdrawals`; each decision, destination change and limit change on `platform.audit`, a reason named, never written |
| `PlayerEndpoint`, `OperatorEndpoint`, `ConfigEndpoint`, `OutcomeEndpoint`, `ServiceEndpoint` | HTTP | one per prefix and audience; [routes](../../specs/007-payouts/contracts/routes.md) |

| Prefix | Audience |
|---|---|
| `/v1/casino/brands/<slug>/players` | the player, on their own id: requests, cancel, terminals, destinations; and an operator's `withdrawal-history` |
| `/v1/casino/brands/<slug>/withdrawal-requests` | an operator with a finance or admin role for the brand: queue, detail, approve, reject, confirm-payout, mark-payout-failed |
| `/v1/casino/brands/<slug>/withdrawal-config` | a brand, resource or platform admin: the limits |
| `/v1/casino/brands/<slug>/withdrawals` | `payments`/`psp-gateway` alone, by its grant: the provider's outcome |
| `/v1/casino/brands/<slug>/payouts` | `players` alone: erasure |

## The two calls out

- **The wallet**, in the brand's project, by identity: the hold's preview, hold, release, confirm and
  reverse. Each is keyed `<request>/<step>`, so a repeat is the first answer.
- **The gateway**, in `payments`, over its grant: the listing, the payout keyed by the request id, and the
  status read.

An answer that may have reached a provider is never offered for manual payout. The request waits
for the outcome or reads the status: a first read after `firstPoll`, then one every `pollEvery`. A
provider with no status to ask is marked needs review when its review window passes. Each request
wakes on its own pause, so the cost grows with requests in flight, not with requests ever made.

## Run it locally

```bash
sbt contract/publishLocal                    # from the repository root
cd services/payouts
sbt schema && docker compose up -d           # Postgres on 5439
cd ../.. && just payouts-run                 # the service on :9004; the wallet on :9000, psp-gateway on :9001
```

Tokens are verified against the kind installation's casino Keycloak (`just payouts-run` names it).

| Setting | Default | |
|---|---|---|
| `CASINO_BRAND` | — | required |
| `wallet`, `paymentsProject`, `gateway` | `wallet`, `payments`, `psp-gateway` | the neighbours, by service name |
| `firstPoll`, `pollEvery` | 30 min, 5 min | the status reads (R8) |
| `refreshEvery` | 60 s | the catalogue's refresh (R5) |
| `requestBound` | 2 s | how long an answer waits for the step it started |

Only `CASINO_BRAND` is read from the environment; the rest are `Settings`' defaults, which the suites
shorten. There is no secret: both calls are by identity.

## Test it

| Command | What it runs |
|---|---|
| `sbt "testOnly io.eitheror.casino.payouts.request.* io.eitheror.casino.payouts.player.* io.eitheror.casino.payouts.limits.* io.eitheror.casino.payouts.terminals.* io.eitheror.casino.payouts.queue.* io.eitheror.casino.payouts.facts.* io.eitheror.casino.payouts.consumers.* io.eitheror.casino.payouts.clients.*"` | the gates and their original tests, the IBAN and name checks, limits, eligibility, every entity command, the queue's row, the facts and audit records, each consumer's mapping, the gateway's decoder |
| `sbt "testOnly io.eitheror.casino.payouts.workflow.*"` | the workflow step by step: every answer of R7's table, the waits, the confirm and reversal keys, settlement by hand |
| `sbt "testOnly io.eitheror.casino.payouts.features.*"` | every `features/payouts` scenario against the whole service |
| `sbt "testOnly io.eitheror.casino.payouts.it.*"` | over HTTP against Postgres in Docker, with a fake wallet and gateway: the gates' order (`GatesSuite`), the catalogue (`EligibilitySuite`), the operator's routes (`OperatorSuite`, `QueueSuite`), the gateway broken at every answer (`PayoutFailureSuite`), one payment for two outcomes (`DoublePaySuite`), the outcome route (`OutcomeSuite`), 1,000 requests with 200 in flight (`ScaleSuite`), erasure (`ErasureSuite`), nothing personal left (`LeakSuite`) |
| `sbt "testOnly io.eitheror.casino.payouts.port.*"` | the original's paths and shapes onto this service's (`WireTranslationSuite`); the recordings' replay (`ParitySuite`) |

From the root: `just payouts-test` runs everything after publishing the contract; `just payouts-it`
runs the integration, parity and feature suites; `just payouts-scale` runs `ScaleSuite` at 10,000.

## What is not done

- **Parity.** `ParitySuite` stays ignored until the original's withdrawal scenarios are recorded into
  `tests/port/payouts/` (`tests/original/payouts-driver`, run against a local original).
- **Erasure in the journal.** After erasure, every personal value is gone from the state, every fact, the
  audit, every view row and every answer: the holder's names, the IBAN, the email, and every reason and
  note. They stay in the event journal and the workflows' stored state until ankka 042's `Personal`
  encrypts them under the player. `LeakSuite` names those tables as the one place allowed.
