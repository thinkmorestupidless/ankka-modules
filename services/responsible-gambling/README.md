# responsible-gambling

A brand's player protections (spec 010): deposit, loss and session limits with the 24-hour wait on a
loosening, breaks, self-exclusion and the return from it, reality checks, an operator's cases, and the
four checks the brand's other services ask before they let a player deposit, sign in, launch or bet. An
ankka 0.13 service in Scala, one sbt build.

## What it is

| Component | Kind | What it holds or does |
|---|---|---|
| `ProfileEntity` | event sourced, id = player | limits, pending changes, the break, the exclusion and the return, the reality check, the cases, the history |
| `profile.Limits`, `profile.Checks` | pure Scala | whether a change tightens or loosens; the four checks over the profile and the caller's figures |
| `BrandSettingsEntity` | event sourced, id = brand slug | the least session limit a player may set and the longest break; each change carries its operator |
| `UsageEntity` | key value, id `<player>/<currency>` | deposits and losses in minute and hour buckets, for display only |
| `SessionDayEntity` | key value, id `<player>/<yyyy-mm-dd>` | the day's game sessions and their minutes, for `may-play` |
| `UsageConsumer`, `SessionMinutesConsumer` | consumers of `money.transactions`, `casino.sessions` | fill the two above |
| `ProfileTimers` | consumer of the profile's events | sets or deletes each timer, whichever route made the change |
| `PendingTimer`, `BreakTimer`, `ReturnTimer`, `SyncTimer` | timed actions | apply a loosening, end a break, reinstate, tell `players` of an exclusion until it answers |
| `RgPublisher` | consumer | `rg_limit_applied`, `rg_case_opened`, `rg_case_closed` on `casino.players` |
| `AuditPublisher`, `SettingsAuditPublisher` | consumers | every operator change on `platform.audit`; a note or reason named, never written |
| `PlayerEndpoint`, `OperatorEndpoint`, `ServiceEndpoint` | HTTP | one per audience; [routes](../../specs/010-responsible-gambling/contracts/routes.md) |

A check is one profile read and the caller's figures: it calls nothing out, so it answers in a few
milliseconds and does not fail because a neighbour is down. A request it cannot read is `400`, which
every caller reads as a refusal.

The wallet holds the deposit sums and sends them with `may-deposit`. Loss sums are not carried yet
(research R6): a player with a loss limit is refused every bet with `unavailable` until the wallet (002)
keeps loss sums and game-provider (003) carries them. That is deliberate (fail closed, decided
2026-10-27).

## Run it locally

```bash
sbt contract/publishLocal                    # from the repository root
cd services/responsible-gambling
sbt schema && docker compose up -d           # Postgres on 5436 with ankka's schema
cd ../.. && just rg-run                      # the service on :9006, players expected on :9005
```

Then, as a caller would (locally every caller is admitted; in a cluster the caller is the platform's):

```bash
curl -s -X POST localhost:9006/v1/casino/brands/goldvibe/rg/may-log-in \
  -H 'Content-Type: application/json' -d '{"playerId":"p1"}'
curl -s -X POST localhost:9006/v1/casino/brands/goldvibe/rg/may-deposit -H 'Content-Type: application/json' \
  -d '{"playerId":"p1","currency":"EUR","amount":1000,"last24h":0,"last7d":0,"last30d":0}'
curl -s localhost:9006/v1/casino/brands/goldvibe/rg/p1/may-play
```

`CASINO_BRAND` is required. `RG_LOOSEN_DELAY`, `RG_RETURN_DELAY` (both `24h`) and `RG_SYNC_RETRY`
(`30s`) change the waits. The minimum session limit and the longest break are not environment settings:
an operator changes them at `PUT /v1/casino/backoffice/<slug>/rg/settings`.

## Test it

| Command | What it runs |
|---|---|
| `sbt "testOnly io.eitheror.casino.rg.profile.* io.eitheror.casino.rg.usage.* io.eitheror.casino.rg.facts.*"` | the limit rule, the checks' truth table, every command of the profile, the usage windows, the facts and audit records |
| `sbt "testOnly io.eitheror.casino.rg.features.*"` | every `features/responsible-gambling` scenario against the whole service |
| `sbt "testOnly io.eitheror.casino.rg.it.*"` | over HTTP against Postgres in Docker: the stories end to end, each caller's decoder over every answer (`CallersSuite`), 1,000 asks of each check (`ChecksLatencySuite`), nothing personal left after erasure (`LeakSuite`) |

`just rg-test` from the root runs all of it after publishing the contract; `just rg-it` the integration
suites. The scenarios about a caller failing closed are held through that caller's own decoder, copied
into `it/Callers.scala` from the wallet, game-provider and `players` (008).

## What is not done

- **Parity.** The original has not been recorded for this service (it needs the original's kind stack);
  there is no `tests/port/responsible-gambling/` and no parity suite yet.
- **FR-006.** A limit's currency is not checked against the player's wallets: this service does not
  hold them. It takes any ISO currency, or the one the player's limits already use.
- **Erasure in the journal.** A note or reason is gone from the state, every fact, the audit and every
  answer after erasure, but stays in the event journal until ankka 042's `Personal` encrypts it under
  the player (`LeakSuite` names the journal as the one place allowed).
