# Research: game-provider

Phase 0 of the plan. Each decision names what established it: the original's code (commit on the
`main` of `eitheror-platform` as checked out on 2026-10-09), the ankka documentation for 0.11, the
ankka specs this repository depends on, and specs 001 and 002 here. Nothing below is new behaviour
unless the spec already lists it under *Changes from the original*; where planning found a gap in
the spec it is named under *Spec corrections* at the end.

## R1. One service, one adapter seam, Scala

**Decision**: `services/game-provider/` is one Scala service (`ankka init game-provider --package
io.eitheror.casino.gameprovider`). The money rules (FR-010 to FR-020) live in a provider-agnostic
core; everything that is ST8's (the callback names and fields, the status words, the signature
header, the launch, games and bonus API) lives behind one `ProviderAdapter` trait with one
implementation, `St8Adapter`. A second provider is a second implementation and a second
configuration, nothing else (Clarifications 2026-10-09).

**Rationale**: constitution VI (the money path is Scala); spec 020 keeps the same seam on the
simulator side so the two stay symmetric; the original's adapter mixed protocol and money in one
2,200-line route file (`St8Routes.scala`), which is what made the multi-debit leak hard to see.

**Alternatives**: TypeScript for the adapter because it "mostly speaks to an external HTTP API". Rejected: the
callbacks are the money path, and the outbound calls are three small signed requests.

**Established by**: `casino-st8-adapter/.../St8Routes.scala`, spec 020 FR-007 to FR-015.

## R2. Where the callbacks are served

**Decision**: the eight callbacks are served at three prefixes on the exposed `game-provider-<slug>`
host: `/v1/casino/brands/{brand}/st8/{callback}` (the form the launch request names as the wallet
URL, `{brand}` kept, FR-005), `/st8/{callback}` and `/{callback}`. The last two exist because ST8
strips the path from the wallet URL (the original's `pathPrefix("st8")(inner) ~ inner`,
`St8Routes.scala:2131`) and because the original's recordings use them; on ankka one host serves one
brand, so a brand-less form resolves the brand as the project's own. A second provider gets
`/v1/casino/brands/{brand}/<provider>/…` and `/<provider>/…` only, never the root (FR-002).

**Rationale**: parity (SC-001 replays the recorded paths) and the provider's behaviour, which is not
ours to change.

**Established by**: `St8Routes.scala:254, 2131`; the Go suite's `scenario15_st8_wallet_test.go`
calls `{adapter}/st8/<callback>`; spec 001 contracts/routes.md.

## R3. Signature verification on the raw body

**Decision**: the callback endpoint takes the body as `String` (ankka's raw-text body) and verifies
`SHA256withECDSA` over its UTF-8 bytes against the brand's provider public key (base64 DER
SubjectPublicKeyInfo, `KeyFactory("EC")`), signature base64 in `x-st8-sign`, before parsing JSON.
A missing header, bad base64 or failed verify is HTTP 200 `{"status":"auth_failed"}`; a body that
then fails to parse as a JSON object is HTTP 400 `{"status":"session_expired"}`
(`player_not_found` for `player_profile`). The endpoint's ACL is `Routes.public`.

**Rationale**: the original verifies the strict entity bytes (`St8Routes.scala:551-553`,
`EcdsaVerifier.scala:15-31`); JSON is UTF-8, so text and bytes agree. **Verify at task time**: ankka
0.11 offers no raw-bytes request body; if a provider body ever carries bytes a UTF-8 round trip
changes, verification fails closed as `auth_failed`, which is the safe side.

**Established by**: `EcdsaVerifier.scala`, `St8Routes.scala:70-123`; ankka `build/http-endpoints.md`
(bodies, `Respond`, `Acl.AllowAll`).

**Verified at task time (T007)**: ankka 0.11.0's endpoint takes a `String` body as the raw request
text, and the callback endpoint verifies over its UTF-8 bytes before parsing. `St8SignatureSuite`
covers a body that is not ASCII; no raw-bytes body is needed.

## R4. The provider configuration is a service secret, cached with a 30-second bound

**Decision**: a brand's configuration for one provider is one entry in the service's own secret
store (ankka 023), named `provider/<name>` (slashes are allowed), holding a JSON document: provider
API address, the brand's site at it, the operator private key (base64 PKCS#8 EC), the provider
public key or, on a non-real-money installation, the address of the simulator's key document, the
jurisdiction, the lobby and deposit links. It is written through an operator route and read by the
endpoints through a `ProviderConfigurations` holder that refreshes every entry every 30 seconds and
at once on the instance that took the write; a key document is fetched on the same cadence, within
FR-026's 60-second bound. No entity reads the store (023 forbids it).

**Rationale**: FR-003 and FR-026 want a change picked up without a redeploy; 038 FR-013 writes a
read record on every `get`, so a read per callback would write an audit row per spin and cost the
100 ms budget (SC-002). The original cached its credentials for 60 seconds (`St8CredentialCache.scala:28`).
The manifest's `game-provider-st8` project secret and the two `ST8_*` variables the goldvibe
manifest renders (spec 001) are not read by this design; a task removes them from the manifest.

**Alternatives**: `secretKeyRef` environment variables (a redeploy per rotation, refused by
FR-026); a key value entity (a private key in the journal, refused by 023's reason for existing).

**Established by**: ankka 023 FR-001, FR-003 and its clarifications; 038 FR-013, FR-016, FR-022;
`St8CredentialCache.scala`; spec 020 FR-011.

**Verified at task time (T009)**: 0.11.0 ships the secret store as `SecretStore` (`put`, `get`,
`delete`), reached by an endpoint as `clients.secrets` and by a timed action or consumer as
`context.secrets`. It cannot list its entries, so the providers configured are kept in one more entry,
`provider/_index`. The manifest's `game-provider-st8` secret and `ST8_*` variables are removed (T005).

## R5. Tickets: one key value entity, two states, no expiry

**Decision**: a launch ticket and the session ticket it becomes are one `Ticket` key value entity
keyed by the token, with a status `issued` → `checked`. `check` is one command, so the
read-then-write race of the original (`St8TokenStore.scala:29-31`) is gone: the entity refuses a
second check with `session_expired`. Nothing expires (Clarifications 2026-10-09). The ticket
holds player id, provider, game code, game category, session id, currency and the attached grant,
never a personal field.

**Established by**: `St8TokenStore.scala:236-399`; ankka `build/key-value-entities.md`.

## R6. Rounds: one event sourced entity per provider round, a transaction index beside it

*Revised 2026-10-10 (spec session 2026-10-10, R18):* the idempotency and the transaction's outcome
move to a bet workflow per transaction and the transaction index is dropped. The round stays as the
round's history, its open bets, its conflicting repeats and unacknowledged entries, and the source of
the views and `casino.rounds`. What follows is the decision as first made.

**Decision**: `Round` is an event sourced entity keyed `<provider>/<round id>`, the round id being
the provider's `round` (or `round_id`) and the transaction id when the callback names none, as the
original (`St8Routes.scala:895-901, 1349`). A buyin, a payout and a standalone credit are rounds of
their own id, so every money callback has a round and one rule. The entity holds every provider
transaction of the round with its outcome in a `KeyedOutcomes` (contract.money), which is the
idempotency of FR-011: a repeat is answered from it and a differing repeat is recorded as a
`RepeatConflicted` event. A `TransactionIndex` key value entity maps a transaction id or cancel id to
its round, written before the round is commanded, because a credit, a cancel and a repeat name a
transaction id and may name no round.

**Rationale**: the original kept five twin namespaces for this (txns, round-index, callback
failures, bet records, rounds) and reconstructed the round from `txn_ids.head`; the event sourced
round is the audit trail a dispute years later needs (spec 001 FR-015) and the exactly-once source
the views and the `casino.rounds` consumer need (ankka `build/views.md`: exactly once over an event
sourced entity, at least once over a key value entity or topic).

**Established by**: `St8TokenStore.scala:403-746`, `BetRecordClient.scala`; spec 002 FR-002;
ankka `build/event-sourced-entities.md`, `build/consumers.md`.

## R7. The wallet is called over HTTP within the project, by the key the provider gave

*Revised 2026-10-10 (R18):* the calls are made from the bet workflow's `wallet` step. The wallet asks
no `may-bet` and answers no `PLAYER_LOCKED` or `RG_UNAVAILABLE`: those come from the workflow's own
`may-bet` step. It keeps only its recent 1,000 keys and refuses `KEY_REUSED` for another command
under a seen key (spec 002 R4, R17); both still map to `error` here. Its bodies are camelCase as built (contracts/neighbours.md; tasks T088).

**Decision**: a `WalletClient` over `clients.services("wallet")` with `request(...)`, so a refusal's
status and body are read rather than thrown. Calls: bet (key = transaction id; game, category,
provider, allow-negative for `correction_debit` and for a cancel of a credit), win (round key = the
first open bet's key), settle-as-loss (round key), rollback (round key = the debit's own
transaction id), provider credit (key = transaction id or cancel id), balance. The wallet's
refusals map as FR-012: `INSUFFICIENT_FUNDS` → `not_enough_money`; `PLAYER_LOCKED` →
`player_locked`; `SCOPE_BLOCKED` and every other responsible-gambling refusal → `spending_limit`;
`ROUND_NOT_FOUND` on a win or rollback → the provider-credit fallback of FR-014/FR-015;
`RG_UNAVAILABLE`, `KEY_REUSED`, any 5xx, a timeout or `ServiceUnresolvable` → `error` and an
unacknowledged record. The paths are spec 002's to fix at its plan; contracts/neighbours.md names
the shape this service expects and the parity kit stubs it with a fake wallet until 002 exists.

**Rationale**: constitution II and IV; the original's client (`St8WalletGatewayClient.scala`) and
its `translateWalletError` (`:302-307`), with `RG_UNAVAILABLE` new since 002 FR-007a fails closed.
Settling every open bet (the change FR-014 records) is one win plus N-1 settle-as-loss calls on the
round's open bets, in the order they were placed.

**Established by**: `St8WalletGatewayClient.scala`; spec 002 FR-002, FR-006, FR-007, FR-007a,
FR-011 to FR-013, FR-027; ankka `build/http-endpoints.md` (*Call another service*).

## R8. A player's lock, for a debit, from a view of `casino.players`

**Decision**: a `PlayerStatus` view over the `casino.players` topic, one row per player with the
account status folded from `player_suspended`, `player_reinstated`, `player_self_excluded`,
`player_closed`; a debit reads the row (FR-012) and answers `player_locked` for suspended, closed
or blocked. Cooling off and exclusion come from the bet workflow's `may-bet` step (R18; the
wallet's `PLAYER_LOCKED` until 2026-10-10). The view must read the
topic from its beginning, which ankka 024 (replayable topics) provides and 0.11's default does not
(`build/topics.md`: "sees only what is published after it starts"). **Verify at task time** that the
pinned ankka exposes 024's from-earliest option; until it does, the kit seeds the view in tests and
the migration (spec 019) replays the brand's players at cutover.

**Established by**: spec 003 Clarifications (reconciliation 2); spec 001 contracts/topics.md;
ankka 024.

**Verified at task time (T025)**: 0.11.0 has `StartFrom.Earliest` for a topic source, and a view that
declares none starts there, so `PlayerStatus` reads `casino.players` from its start and no seed is needed
(`PlayerStatusSuite` publishes before the service starts). `blocked` has no family on `casino.players`
yet (spec 008 FR-035); the view folds it once spec 008 publishes `player_blocked` (spec Assumptions).

## R9. Launch: three neighbours, one rate-limit entity, one provider call

**Decision**: `POST /v1/casino/sessions/{brand}/launch` (the original's path, kept for the frontend)
is a player route. In order: body and player id checks; the rate limit as a `LaunchWindow` key value
entity per player (count and window start, `expireAfter(2 minutes)`), refusing the 21st launch in 60
seconds with 429 `RATE_LIMITED` and `retryAfterSeconds`; then `players` (account status), `kyc`
(play check) and `responsible-gambling` (`may-play`) over HTTP within the project, each refusal
mapped to the original's code and each failure to 503 `service_unavailable` (fail closed); the
provider's configuration (`st8_not_configured` when absent); the grant to attach; the ticket issued;
the provider's launch called with the signed body; `players` asked to open the game session
(best effort); the recently-played write (best effort); answer `{launchToken, gameUrl, sessionId,
freeSpinsGrantRef?}`.

**Rationale**: the original's `SessionRoutes.scala:257-565` in the same order; the rate limit was
per pod in memory, an entity makes it per player across instances for the same cost (one command).
`fun_mode`, `site{id, lobby_url, deposit_url, wallet_url}` and `player_profile{…}` are the
provider's launch body (`:741-771`); `bet_limits` is computed from the daily deposit limit as the
profile callback does (the original sent a constant `medium` at launch, a defect not kept).

**Established by**: `SessionRoutes.scala`, `PlayerRateLimiter.scala`, `CasinoServiceMain.scala:168`;
specs 008 FR-017/FR-018, 009 FR-020, 010 FR-008.

## R10. Catalogue: an entity per game, a view for the lobby, the decision at read time

**Decision**: `Game` is a key value entity keyed `<provider>/<code>` holding the provider's fields
(US7 scenario 1); `Catalogue` is a view over its state, queried with SQL over the row's JSON
(`jsonText`, `jsonContains`) for category, themes, features, bonus types, tag, developer (id or
slug), name search and country, paged by code. `BrandCatalogueConfiguration` is one key value entity
(id = the brand slug). The enablement decision (removed → category → developer → override → the
provider's flag, `SubstrateTwinClient.scala:154-158`) is computed in the endpoint on each read from
the row and the configuration, so a configuration change takes effect on the next read (FR-022).
The frontend's paths and JSON field names are kept exactly (`GameRoutes.scala:427-450`) so the
Playwright suite passes unchanged (SC-006); a `brand` query parameter naming another brand is
refused as FR-005 refuses a segment.

**Rationale**: 10,000 games is well inside a view table; the original cached the whole list for five
minutes in memory and filtered in Scala, which the view's SQL replaces. Popularity is a
`GamePopularity` view over a `GameRounds` key value entity per `<provider>/<code>` that a consumer
over `Round` events increments on `RoundOpened` (rounds played, Clarifications 2026-10-09), ordered
by `jsonNumber("rounds") DESC` and filtered by enablement at read time; the consumer's at-least-once
redelivery may count a round twice after a crash, acceptable for a ranking and noted in the entity.

**Established by**: `GameRoutes.scala`, `St8GameSeeder.scala:157-218`, `BrandConfigRoutes.scala:327-399`;
ankka `build/views.md`.

**Changed at task time (T068, T070)**: the player's filters are not SQL over the view. The original's
category, themes, features and bonus types each match a union of the game's category and tags
(`categoryTagsOf`, ORed within a dimension, ANDed across), which a JSON containment query cannot express.
The view is read whole into a snapshot kept ten seconds and filtered in Scala, as the original filtered
its cached list; the brand's configuration is read on every call, so FR-022's "next read" holds.
`CatalogueViewSuite` holds the filters to the original's semantics over 300 games.

## R11. The nightly refresh is a recurring timer per provider

**Decision**: a `CatalogueRefresh` timed action, scheduled as a recurring timer (ankka 032) named
`catalogue-refresh/<provider>` with a 24-hour period, first due at the next 03:00 UTC, scheduled
idempotently at service start and whenever a provider configuration is written; an operator route
runs one now and reads its status (a `RefreshStatus` key value entity per provider). The handler
fetches `GET /api/operator/v1/games?site=…` signed over the query string, upserts every game under
`<provider>/<code>`, marks the provider's games absent from a non-empty list removed, leaves
everything alone on an empty list, retries three times with growing delays (10 s, 30 s, 60 s, the
original's), and returns `done` on failure too, with the failure on status, so the timer is not
retried forever (`build/timers.md`: "nothing to do is success").

**Rationale**: the original ran at startup and from a Kubernetes CronJob calling an internal route
(`St8GameSeeder.scala:19`, `CasinoServiceMain.scala:1005-1043`); the time of night is not in the
repository, so 03:00 UTC is this plan's choice. 032 is on its branch until merged; until then the
task schedules a single timer that reschedules itself from the handler, the pattern 032 replaces.

**Established by**: `St8GameSeeder.scala`; ankka 032 contracts/protocol.md, `build/timers.md`.

**Verified at task time (T069)**: 0.11.0 has no recurring timer. The nightly refresh is a single timer
per provider that reschedules itself for the next 03:00 UTC from its handler, scheduled at start for
every configured provider and when a configuration is written. The operator's refresh-now is a timer due
at once, so it is durable too. The timed action reads the scheduler, the adapters and the configuration
cache through `RefreshRuntime`, wired at start.

## R12. Free rounds for `rewards` are service routes over one signed client

**Decision**: a `RewardsEndpoint` of class `service` (`Routes.service()`, callers in the project) with
create, poll, cancel, spin values and make-known, each naming the provider; `St8Adapter` signs the
body (POST) or the query string (GET) with the brand's operator key and maps the provider's answer
to `active | processing | rejected | failed | cancelled | not_configured` by the original's groups
(`St8BonusClient.scala:31-36, 180-195`). Spin values are cached per provider, games and currency
for the day (FR-025, the feature's "cached for the day"); make-known writes the `KnownPlayer`
entity the profile callback reads. `rewards` owns the polling loop and the grant (spec 011 FR-023).

**Established by**: `St8BonusClient.scala`, `FreeSpinsProvisioner.scala:136-160`,
`St8PlayerRegistrar.scala`; spec 011 FR-023, FR-026.

## R13. Unacknowledged callbacks: a view, a replay, a resolve

**Decision**: a wallet call that fails for any reason other than a refusal is recorded on the round
as `CallbackUnacknowledged(transaction id, kind, amount, error)`, answered `error`; the provider's
retry is served afresh (the stored outcome of an unacknowledged call is not an answer). The
`Unacknowledged` view over `Round` events keeps one row per round with its open entries, listed by
an operator route; replay re-drives the stored callback against the wallet under the same key and
records `UnacknowledgedCleared(replay, operator)`; resolve records `UnacknowledgedCleared(resolved,
reason, operator)` and moves nothing. The count of open entries is a metric (FR-029).

**Established by**: `St8TokenStore.scala:125-228` (the original's failure ledger, record and resolve
only); Clarifications 2026-10-09.

## R14. `casino.rounds`: the contract gains three fields, additively

**Decision**: `RoundSettled` in `contract.facts` gains `provider_transaction_ids` (the provider's
ids), `wallet_transaction_ids` (the wallet's, for `reporting`'s matching, FR-019) and an optional
`free_rounds_grant`; `transaction_ids` stays as the wallet ids for readers already written against
it. `outcome` is one of `win`, `loss`, `cancelled`, `credit`. `just schemas` regenerates
`casino.rounds.v1.json`. The record key stays the wallet (`player/currency`), as spec 001's topic
contract fixes it; the fact carries `player_id`.

**Established by**: `contract/.../facts/Others.scala:44-60`, `contract/schemas/casino.rounds.v1.json`;
spec 001 contracts/topics.md; spec 015 FR-017, FR-018.

## R15. Recording and parity

**Decision**: the recordings the spec names under *Recordings* are made by the spec 001 method
(research R10, `tests/original/README.md`) against the original with `fake-st8`, filed under
`tests/port/game-provider/`. The parity suite is one munit test per recording over
`AnkkaTestKit.start` with the service's descriptors, `HttpServer.at("127.0.0.1", 0)`, a fake wallet
answering spec 002's commands in process (a `services` stub, until spec 002's service exists and the
two can be started together), `fake-st8` answered by spec 020's simulator or an in-process stub of
its three routes, and the recordings' test key registered as the brand's provider public key through
the operator route. Scenarios that drive the original's test-control surface (`/st8/test/*`) set the
same state through entities in the kit; the recording's steps that hit `/st8/test/*` are replayed as
setup, not compared. `GherkinSuite("features/game-provider")` binds the nine features against the
same kit.

**Established by**: spec 001 contracts/recording.md, research R10; ankka `build/testing.md`.

## R16. What the service does not do

- No personal field is stored: the ticket and the known-player record hold the player id; the
  profile and the check's country are read from `players` at the time of the call (FR-028). No
  erasure handler is needed beyond the player id's deletion, which the brand's `players` erasure
  (spec 001 FR-025) drives; a task confirms whether ankka 042 requires a handler that deletes
  nothing.
- No `platform.audit` producer of its own in this feature: operator replay and resolve are recorded
  on the round (FR-020), and spec 018 decides what else reaches `platform.audit`.
- Metrics: `eitheror_casino_bets_total{brand}` and `eitheror_casino_unacknowledged_callbacks{brand}`
  through `ankka-telemetry-otlp`. **Verify at task time** which counter and gauge API the 0.11 SDK
  exposes; if none, they are served on the runtime's `/metrics` endpoint by a registry the service
  owns. **Verified at task time (T017)**: 0.11.0 exports only the runtime's own metrics and offers a
  service no meter. The counter is kept by the service and both metrics are rendered in Prometheus' text
  format on the operator route `GET /v1/casino/backoffice/{brand}/game-provider/metrics`; the gauge is
  read from the `Unacknowledged` view when rendered, so it survives a restart. A scrape path joins the
  runtime's `/metrics` when ankka offers a service meter.
- Erasure (T079): ankka 042 FR-010 says a service without an erasure handler "has nothing of its own to
  do", and the platform's own duties do not depend on one. This service holds no personal field, so it
  registers none.

## R18. A bet workflow per provider transaction

**Decision** (2026-10-10, spec session 2026-10-10, spec 002 R17): every money callback starts a
`BetWorkflow` whose id is `<provider>/<transaction id>` (the cancel id for a cancel; provider-scoped,
since two providers' ids may collide). Its `start` is the atomic claim; a second start is refused
`Conflict`, which the endpoint reads as "already started" and answers from the workflow's `status`,
recording a conflicting repeat on the round when the body differs (FR-011). Its steps, by kind:

| Kind | Steps |
|---|---|
| debit, correction debit, buyin | `may-bet` → `wallet` (the bet) → `record-round` |
| zero or free debit (FR-013) | `record-round` (no bet, nothing asked) |
| credit, zero credit, standalone credit | `wallet` (one win and N-1 losses on the round's open bets, or a provider credit) → `record-round` |
| cancel | `wallet` (rollback or provider credit, from the referenced transaction's workflow) → `record-round` |
| payout | `wallet` (provider credit) → `record-round` |

- **`record-round` is on the reply path.** The outcome is written to the workflow's state only once
  the round has recorded the transaction, so the endpoint never answers before the round knows.
  The session put it off the reply path; that races: a credit settles the open bets the round holds,
  and ST8 sends the credit as soon as it has the debit's answer, so a debit answered before its
  `record-round` ran would leave its bet out of the credit's settlement, which is the leak FR-014
  closes. It costs one entity command per callback (R17's measured median, 9.7 ms, already includes
  two calls of that kind).
- **`may-bet`** asks `responsible-gambling` (spec 010 FR-010), 500 ms. `locked`, `excluded`,
  `cooling_off` end the workflow `player_locked` with the balance read from the wallet; no answer,
  a non-2xx or an unreadable one ends it `error` (`RG_UNAVAILABLE`), failing closed, and the wallet is
  never called. Either refusal is the outcome, so a repeat is answered the same and never asks again.
- **The wallet step** maps refusals as R7. When the wallet cannot be reached (5xx, timeout,
  `KEY_REUSED`, unresolvable) the step is retried (`maxRetries(2)`; the wallet answers a key it has
  seen with the first outcome, so a retry is safe), then the workflow records the callback
  unacknowledged on the round and pauses in `unacknowledged` with no outcome. A provider retry (its
  `start` refused `Conflict`, the status `unacknowledged`) resumes the wallet step; an operator replay
  does the same; a resolve ends it `resolved` (FR-020). An outcome is never `error` for a wallet
  failure, so a retry is never answered from a failure.
- **The endpoint** starts the workflow, then polls its `status` every 1 ms up to 2 s (the wallet's
  deposit bound, R17), answering ST8 from the state as soon as the outcome is there and `error` past
  the bound; the provider's retry is answered from the workflow. The poll is one helper, to become
  ankka's await on a workflow when it exists (a platform request, spec 002 R17).
- **A cancel** finds the referenced transaction by its workflow's `status` (its round, kind, wallet
  key and outcome), which replaces the index: no workflow is `transaction_not_found` (FR-015).

**As built (T083 to T091, 2026-10-10)**:
- `may-bet`'s 500 ms is the step's own timeout, failing over to a step that ends the workflow `error`.
  A step may call another service only from its own thread (ankka refuses a call from another), so
  the bound cannot be a timer around the call.
- The wallet step tries three times, 20 ms apart, within the step; `maxRetries(2)` failing over to
  `unacknowledged` covers a step that throws or times out. The failover reads the last error from the
  state, which a step that throws cannot write, which is why the step retries itself first.
- An account locked in the players view (a step cannot query a view) is read by the endpoint and
  carried in the job; `start` then ends the workflow `player_locked` and records nothing.
- A restart mid-step: the transition is journalled and the instance resumes when it is next addressed
  (the provider's retry, a cancel naming it, an operator's replay), not by itself. `DurabilitySuite`
  shows a debit held in `record-round` across a restart answered `ok` on its retry with one bet. A
  provider always retries a call it got no answer to, so nothing is stranded in practice; that ankka
  resume an interrupted workflow without being addressed is a platform request beside the await.
- Measured: SC-002 p50 6 ms, p95 11 to 12 ms over 3,250 money callbacks in the kit (57 ms before);
  50 pairs of simultaneous duplicate debits place 50 bets and ask `may-bet` 50 times; 1,000 repeats
  call neither the wallet nor `may-bet`; LeakSuite and the 33 parity tests unchanged and green.
- Breaking the order (the outcome written in the wallet step, before `record-round`) fails
  `BetWorkflowSuite`'s ordering test, which slows `record-round` in the kit to make the order
  observable; without that delay the race is too narrow for any suite to see reliably.

**Rationale**: spec 002 R17. A transaction entity alone cannot make "seen" and "debited" one write;
the workflow's journal can, and its finished state is the lifetime record the wallet's bounded key
store no longer is. The round is kept because a view and a consumer cannot source a workflow.

**Alternatives**: `record-round` after the answer (rejected above); asking the wallet for a round's
open bets at credit time (spec 002 offers no such read, and the round is the history anyway).

**Established by**: spec 003 session 2026-10-10; spec 002 R4, R17 and its prototype
`services/wallet/.../it/proto/BetWorkflow.scala`; ankka `build/workflows.md`.

## Unknowns resolved

| Technical Context item | Resolution |
|---|---|
| Where configuration lives and how rotation is picked up | R4 |
| Idempotency and the round's identity | R6, R18 |
| How the wallet is reached before spec 002 is built | R7, R15 |
| Where a debit learns a player is locked | R8, R18 |
| Rate limiting across instances | R9 |
| Catalogue query shape at 10k games | R10 |
| Nightly schedule without a CronJob | R11 |
| The `casino.rounds` fields `reporting` needs | R14 |

## Spec corrections found in planning

*Applied to spec.md on 2026-10-09.*

- FR-019 says `round_settled` is "keyed by the player id"; spec 001's topic contract keys
  `casino.rounds` by the wallet (`player/currency`) and the contract's `RoundSettled.key` does the
  same. The fact carries `player_id`; the key is the wallet. FR-019 should say so.
- FR-002 says every callback route keeps the `{brand}` segment; the brand-less forms R2 serves
  cannot. FR-002 should say the segment is kept on the form the launch names, and that the
  brand-less forms resolve the project's own brand.
- The launch sent `bet_limits: "medium"` as a constant (`SessionRoutes.scala`) while the profile
  callback computes it; this plan computes it at launch too and the spec's *Changes from the
  original* should record it beside the jurisdiction change.
