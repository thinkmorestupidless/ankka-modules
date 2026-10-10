# Research: Wallet

**Spec**: [spec.md](spec.md) | **Date**: 2026-10-09

What was read: the spec after its 2026-10-09 clarifications; the constitution; spec 001's plan,
research and contracts (`routes.md`, `topics.md`, `recording.md`); the contract library as built
(`contract.money.KeyedOutcomes`, `contract.facts.MoneyTransactions`, `contract.access.Routes`,
`contract.port.Replay`, `Layout.BrandGrantsMade`); the ankka 0.11 skills (`ankka-design`,
`ankka-entities`, `ankka-port`); the original's `wallet-service` (`WalletRoutes.scala`, 4,900 lines,
56 routes), its resolver (`starter-packs/wallet/handlers-go/internal/funding`, 5,856 lines of Go with
its table tests), the TL fixtures (`starter-packs/wallet/handlers/*.test.tl`, 173 cases) and
`casino-service`'s `WithdrawalRoutes.scala` for the clawback. Each decision names what established it.

## R1. One event sourced entity per wallet, records embedded

**Decision**: `WalletEntity`, event sourced, id `<playerId>/<currency>`. Its state holds real balance,
deposit turnover pending, the open bets by round key, every restricted funds record (open and closed),
the withdrawal requests by id, the deposit records of the last 30 days, the clawback baselines, and the
seen keys. Every rule the spec states (FR-005 to FR-027) is a check followed by the change it guards on
one of these, so one entity is the only component that can enforce them atomically (`ankka-design`
step 1). Records are state, not entities of their own: a bet reads several records in priority order
and writes legs to each, which would be a cross-entity transaction if records had ids of their own.

**Rationale**: the original's bug class is exactly the split the spec's Context names: records as
twins read back through a lagging projection. Constitution II says the wallet is the one ledger.

**Alternatives**: a `RestrictedFundsRecord` entity per award (rejected: a bet spanning records becomes
a workflow with reserving steps, and the resolver's single pass over all records becomes eventually
consistent); a key value wallet (rejected: history is the ledger; views and facts fold the events).

**Bounded state**: closed records stay (their status is read by `switch` and `cancel`), but a wallet
has tens of records, not thousands. Deposit records are kept 30 days in state (R5); older ones live in
the transactions view only. A bet leaves the open map when its round closes; a late rollback (Edge
Cases) finds it among the last 1,000 settled bets (`WalletState.SettledBound`, `assumed`, 2026-10-10:
about 2.4 hours of the heaviest autoplay pace of one bet and one win per spin, some 10,000 keys a day;
ST8's cancel horizon is undocumented in the original). The first draft said 10,000 and called it the
original's `BoundedFanOut` depth, which was wrong: `BoundedFanOut` is a concurrency helper in
`wallet-service`, not a retention depth, and the original's adapter store is unbounded. A rollback of a
bet the wallet no longer holds is `ROUND_NOT_FOUND`, which spec 003 handles. Seen keys are the last
1,000 too (R4, R17). Snapshot every 100 events.

## R2. The resolver ports from Go to Scala as a pure module

**Decision**: `internal/funding` becomes `wallet.funding`, a package of pure functions over immutable
state (`placeBet`, `creditWin`, `rollback`, `settleLoss`, `withdraw`, `adjust`, `close`, `rules`,
`legs`), with no ankka type in it. The entity's command handlers call it and persist what it returns.
Its Go table tests (`placebet_test.go`, `multi_test.go`, `creditwin_test.go`, `rollback_test.go`,
`loss_test.go`, `close_test.go`, `withdraw_test.go`, `adjust_test.go`, `rules_test.go`,
`legs_test.go`) port case for case to munit tables, with the Go file and case name in each test's name.

**Rationale**: constitution VI puts the money path in Scala. `ankka-port` warns that most parity
failures come from logic rewritten in another language; the mitigation is to port the original's own
tests before the logic, and to keep the module pure so every case runs with no runtime. Truncating
integer division and `Long` arithmetic are the same in both languages; the one trap, `%` and `/` on
negatives, is covered by the correction-debit cases.

**Alternative**: compile the Go to WebAssembly (rejected: ankka's WebAssembly SDK is Rust, and the
resolver would stay outside the entity, repeating the dispatch the spec removes).

## R3. Refusals that must be idempotent are events

**Decision**: a money command whose refusal the spec records under its key (FR-002: "a repeat returns
the outcome of the first call, including a refusal"; FR-022: "writing nothing but the refusal under the
key") persists one `CommandRefused(key, digest, code, message, at)` event and replies with the
refusal. The fold adds the key to the seen set and changes nothing else. Boundary refusals
(`INVALID_REQUEST`, a missing key, a wrong brand, an unknown caller) are the endpoint's and persist
nothing: they happen before the entity is reached.

**Rationale**: `ankka-entities` rule 3 says a refusal persists nothing, which is right for a refusal
the caller may fix and resend. A money refusal under a key is a decision that must be repeatable, and
the only durable thing an entity has is its journal. This also gives `platform.audit` its record of
every refusal (FR-031) from the same consumer that publishes the facts.

## R4. The key store keeps a digest and is bounded; the contract's `KeyedOutcomes` gains the digest

**Decision**: `contract.money.KeyedOutcomes[A]` becomes `Map[String, Seen[A]]` with
`Seen(digest: String, outcome: A)`; `answer` takes the command's digest and returns a fourth case,
*reused*, when the digest differs. The digest is SHA-256 over the command's canonical JSON with the
key removed. The wallet replies `KEY_REUSED` naming the key and the first command's type and amount
(FR-002) and persists a `CommandRefused` with code `KEY_REUSED` under a synthetic key
(`<key>#reused#<digest>`), so the audit consumer sees it and the original key's outcome is unchanged.

**Bound** (2026-10-10): the wallet keeps its most recent 1,000 keys (`WalletState.RecentKeys`), the
synthetic entries counted, evicting the oldest in insertion order (`keyOrder: Vector[String]` beside
`seen`). A key older than that is unknown to the wallet. The lifetime record of a key is the caller's
workflow under that key (R17), so `Outcome` no longer accumulates in the state. The `outcome-of` query
stays and answers among the recent keys.

**Why the bound was needed**: the spec first said every key for the wallet's life. Estimated at 150
bytes a key, it was **measured** by `ScaleItSuite` (`just wallet-perf`) at 443 bytes a key of state:
a 10,000-transaction wallet's state, its snapshot, was 4.3 MB, with its keys' outcomes and the last
10,000 settled bets the two largest parts, growing for the wallet's life, and a 100,000-transaction
wallet would have carried about 43 MB. That measurement is what showed the design was wrong: the
state must depend on recent activity, not history. The same run measured decoding at 19 ms, the first
command after a restart at 143 ms, and bets at 4.4 ms median and 147 ms at the 99th percentile; the
p99 was the 4.3 MB snapshot being written every 100 events.

**Measured after the bound** (2026-10-10, same laptop): a 10,000-transaction wallet's state is
552 KiB (1,000 keys and 1,000 settled bets held; 565 bytes a key of state, most of it the settled
bets), down from 4.3 MB, and the same whatever the history; the first answer after a restart 241 ms;
1,000 bets through the route on that wallet median 2.0 ms, p99 16.4 ms (SC-003: 50 and 250 ms), down
from 4.4 and 147 ms. The bet-workflow prototype on the bounded wallet: median 7.1 ms, p99 49.1 ms,
against the direct command's 0.4 and 12.8 ms.

**Two findings of the first measurement, kept**: jsoniter refuses by default to read a map or set of
more than 1,024 entries, so a wallet with more keys wrote a snapshot it could not read back; and
ankka's codec writes every absent field as `null`. The wallet's stored types use `WalletCodecs.stored`:
no cap, absent fields left out. Both were safe to decide before any journal existed. (The bound now
keeps the map under 1,024 entries in any case; the setting stays, so a bound raised later cannot
reintroduce the fault.)

**Established by**: the contract as built has no journal yet (no service is deployed), so changing
the stored shape of `KeyedOutcomes` is safe today and not after spec 002 ships.

## R5. Deposit sums for `may-deposit` come from entity state, not a view

**Decision**: the wallet's state keeps the deposits of the last 30 days (amount, confirmed at) and
the fold drops entries older than 30 days relative to the event's own time, so the fold stays pure.
The deposit endpoint asks the entity for the 1-, 7- and 30-day sums as of `now`, passes them to
`responsible-gambling`'s `may-deposit`, then sends the deposit command; the reply carries the sums
after the deposit, so the parity test can check them.

**Rationale**: FR-030 wants the sums exact and current within a second, and spec 010 FR-012 forbids a
view's figures as the input to a refusal after the 100-row truncation incident. A view lags with no
bound (`ankka-design`, consistency). Thirty days of one wallet's deposits is a bounded list.

**Alternative**: a view over `deposit_confirmed` queried by the endpoint (rejected: lag, and the
decision that must be exact would rest on view data).

## R6. `may-deposit` is a step of the deposit workflow; `may-bet` is spec 003's

**Decision** (revised 2026-10-10): the wallet asks `responsible-gambling` for deposits only, and from
a workflow step, not an endpoint. `DepositWorkflow` (component `deposit-flow`, id
`<player>/<currency>/<paymentId>`): its `start` command is the atomic claim (a second start is
`Conflict`); the `check-rg` step reads the wallet's `deposit-sums` as of now and calls
`services("responsible-gambling")`'s `may-deposit` with a 500 ms timeout, failing closed; a refusal
(`EXCLUDED`, `COOLING_OFF`, `OVER_LIMIT`, `RG_UNAVAILABLE`) becomes the workflow's outcome and the
workflow ends with the wallet never called; the `credit` step calls the wallet's `deposit` under the
payment id, with `maxRetries(2)` because the wallet answers a repeated recent key with the first
outcome. The `refuse-deposit` command is removed: the refusal is the workflow's record, and a repeat
of the payment id is answered from it. The granted deposit route starts the workflow (a `Conflict` is
"already started"), polls its `status` every 1 ms for up to 2 s, and renders the outcome; past the
bound it answers `503 OUTCOME_PENDING` and the retry is answered from the workflow's state. An entity
handler still never calls out (`ankka-entities` rule 1).

`may-bet` is no longer the wallet's: it is a step of `game-provider`'s bet workflow (spec 003 FR-012),
before that workflow's wallet call. The wallet's bet path calls nothing (FR-004, SC-003). The reason
is R17's: with the check and the debit as two calls from an endpoint, a provider's retry after a lost
reply asked RG again and could be refused for a stake already taken.

**Rationale**: the check is a precondition on another service's state, not a rule the wallet's state
can enforce; the workflow makes the claim, the check and the credit one durable process under the
payment id, where the endpoint could only make them two calls.

**Local loop**: the integration suite hands an in-process `FakeResponsibleGambling` (allowed by
default; scripted refusals and silence per test) to the workflow's factory in place of the HTTP
client; no real `responsible-gambling` exists until spec 010.

## R7. Brand configuration is one key value entity with a fixed id

**Decision**: `BrandConfigEntity`, key value, id `brand` (the project is the brand, constitution
III). State: contribution rate by game type, bonus wagering counts, partial splits allowed, maximum
concurrent awards, the deposit turnover multiplier and definition. Two endpoints write it: a `service`
route for `rewards` (bet switches, FR-004) and an `operator` route (turnover terms, FR-004a), each with
its own ACL. The bet endpoint reads it with one `invoke` and passes the switches into the bet command,
so the command handler has them without a call (rule 1). Absent configuration means the original's
defaults: contribution rate 100 for every game type, bonus wagering counts, partial splits on, no
award limit, turnover 1× gross wagered (`rules.go` defaults, `deposit.tl` defaults). The player's
turnover override is wallet state (FR-004a), written by an operator route on the wallet.

**Alternatives**: read the configuration inside the entity (impossible by rule 1); event source it
(rejected: nobody reacts to how a rate changed, and the audit record of the operator's write is
`platform.audit`'s).

## R8. Payment outcomes are a small entity of their own

**Decision**: `PaymentEntity`, event sourced, id = payment id. The outcome route (FR-022a) records
`initiated`, `failed`, `expired`, `abandoned`, `refunded`, `dispute.opened`, `dispute.closed`, each
idempotent by (payment id, outcome). The captured deposit is not recorded here: `payment.captured` is
produced from the wallet's `DepositConfirmed` event, so a deposit and its fact are one write. A
consumer over `PaymentEntity` publishes the other seven families.

**Rationale**: an outcome moves no money and is keyed by the payment, not the wallet; writing it into
the wallet's journal would key `payments.payments` wrongly (spec 001 FR-012: keyed by the payment id)
and grow the wallet with facts about nothing it owns. Two writes (wallet, then payment) are avoided by
sourcing the capture from the wallet.

## R9. Facts are published by consumers, in the contract's shapes

**Decision**: `MoneyTransactionsPublisher`, a consumer over `WalletEntity` events, maps each event to
one `contract.facts.MoneyTransactionsFact` and publishes it to `money.transactions`, and maps
`DepositConfirmed` also to `PaymentCaptured` on `payments.payments`. `PaymentsPublisher` does the same
over `PaymentEntity`. `AuditPublisher` maps every money event and `CommandRefused` to a
`platform.audit` record. The contract gains `WithdrawalReversed` and `ProviderCredited`, a
`clawbackCents` field on `RestrictedFundsForfeited` and a `voidedCents` field on `HoldPlaced`
(clarification 2026-10-09); `just schemas` regenerates `money.transactions.v1.json`.

**Rationale**: `ankka-design` step 10: publish a stable message type from a consumer, never the
entity's events. Delivery is at least once; `ce-id` is the transaction id, so readers dedupe.

## R10. Reads are views, except the two the entity answers

**Decision**: `balance` and `restricted-funds status` are entity queries (read your own write; the
player site shows the balance after the bet that just happened). `transactions` (filter by type,
wallet type, date range; paged), `lifetime totals` and `sum by type over a range` are views over
`WalletEntity` events: `TransactionsView` (one row per transaction, the row of User Story 6 scenario
3), `LifetimeTotalsView` (one row per wallet), `SumsView` (one row per wallet, type and UTC day, summed
by the query). The adjustment reason is a `Personal[String]` in the row (R12).

**As built**: a plain ankka view keeps one row per source entity, so the transactions and the sums
are keyed views (`KeyedView`), which write rows under keys of their own. A keyed view is one writer
across the service; acceptable at the pilot's volume, and the first thing to split (a view per
read) if it lags.

**Rationale**: step 4 of `ankka-design`: every question that is not about one id is a view. The sums
`responsible-gambling` decides on are not these (R5).

## R11. Wire format: the original's bodies, spec 001's paths

**Decision**: the wallet's routes are the paths spec 001's `Layout` and `routes.md` set
(`/v1/casino/brands/{brand}/wallets/{player}/{currency}/...`, `/v1/casino/players/{brand}/...`,
`/v1/casino/backoffice/{brand}/...`), with the original's field names in request and response bodies.
Amounts: service routes carry minor units as integers (FR-003); player and backoffice routes render
major-unit strings with two decimals as the original does. Refusal codes and messages are the
original's, verbatim, as the spec lists them.

**Parity replay**: the original's wallet routes are `/v1/wallet/{brand}/{player}/{currency}/<verb>`
and answer decimals. `contracts/wire.md` is the one translation table (path to path, decimal to minor
units on service routes) the parity suite applies to a recording before `Replay.run`; it is a
documented divergence, not a `vary` entry.

## R12. Personal data and erasure

**Decision**: the adjustment reason is `Personal[String]` under `PlayerSubject(playerId)` in the
event, the view row and nothing else; the wallet implements the 042 erasure handler, which destroys
nothing of its own and confirms (FR-033). Built against 042's `-impl` branch as spec 001 did (its R5);
until it ships the contract's `Personal` is a marker type and the handler is a route that confirms.

## R13. What the parity tests replay, and from where

**Decision**: three sources, in this order of weight.

1. **Wallet recordings** (`tests/port/wallet/*.json`): the original's `wallet-service` driven directly
   at `/v1/wallet/...` by a Go driver added to `tests/original/` (`wallet-driver`, tagged `e2e`), one
   scenario per user-story scenario of the spec, recorded twice through the existing recorder and
   merged. The e2e suite itself reaches the wallet only through `casino-service` and the ST8 adapter
   (`EITHEROR_CASINO_URL`, `EITHEROR_SUBSTRATE_URL`), so its recordings are `game-provider`'s,
   `payouts`' and `rewards`' to replay (specs 003, 007, 011), and SC-001's "through the game-provider,
   rewards and payouts routes" is met when those exist. Until then those recordings are listed in the
   wallet's parity suite as *blocked by spec 003/007/011*, not deleted.
2. **TL fixtures** (173 cases, `given twin / when / then`): translated once by a small converter
   (`tools/tl-fixtures`, Scala, reads the fixture grammar, writes a JSON case file per handler under
   `services/wallet/src/test/resources/fixtures/`) and replayed by one munit suite per handler against
   `EventSourcedTestKit`: the `given` becomes events that build the state, the `when` the command, the
   `then`'s `result` and the balance-bearing mutations the assertions. Mutations about twins, edges and
   concepts are not asserted; they are the substrate's, not the wallet's.
3. **Go tables** (R2), ported by hand.

**Rationale**: `ankka-port` wants recordings of the original's answers; the wallet's answers are on
its own routes, which the e2e suite never calls directly. The TL fixtures are the original's own
tests of the money rules, 1,469 lines for `place-bet` alone, and are the densest evidence there is.

## R14. Testing the service

**Decision**: `EventSourcedTestKit` for the entity (fixtures, Go tables, one test per refusal code);
`KeyValueEntityTestKit` for the brand configuration; the integration testkit (`AnkkaTestKit.start`
with the service's Postgres) for the parity recordings, the views, the publishers (asserting the
facts on the topics), `restartService()` for durability (SC-005's local form), SC-002's 200
concurrent bets and SC-006's repeat-every-command test; `GherkinSuite("features")` binds
`features/wallet/*.feature`. The FR-003 overflow case (SC-004) is a unit test on the resolver.

## R15. Service layout and build

**Decision**: `services/wallet/`, one sbt build from `ankka init`, Scala 3.9, ankka 0.11.0, depending
on `io.eitheror:casino-contract` (published locally with `sbt contract/publishLocal` until a registry
exists). Packages: `wallet.funding` (R2), `wallet.entity`, `wallet.config`, `wallet.payments`,
`wallet.views`, `wallet.publish`, `wallet.api` (one endpoint per audience), `wallet.wire` (bodies and
rendering). The brand manifest (`brands/goldvibe/manifest.json`) gains the `wallet` service entry at
its pinned version; the brands tool applies it unchanged.

## R16. The brand in the prefix, the request in the body

**Decision**: an ankka route takes at most two path parameters, and the wallet's paths carry three
(brand, player, currency). Each endpoint's prefix carries this project's brand, read from
`CASINO_BRAND`; `/{player}/{currency}` are the two parameters. A request naming another brand matches
no route and is answered `404` by the runtime (spec 001 FR-005's status, without the `wrong_brand`
text: a divergence in plan.md). For the same reason the switch names its grant, and a hold's release,
confirmation and reversal name their request, in the body.

**Alternative**: one endpoint at `/v1/casino/brands` reading the path by hand (rejected: it gives up
typed parameters and the runtime's route listing for a message text).

## R17. A workflow per keyed command at the caller; the wallet keeps recent keys

**Decision** (2026-10-10): every keyed money command is sent from a workflow whose id is the key, in
the calling service: spec 003's bet workflow per provider transaction, a workflow per grant in
`rewards` (spec 011), per request in `payouts` (spec 007), and this service's `DepositWorkflow` (R6).
The workflow's `start` is the atomic claim, its steps are the precondition checks and the one wallet
call, and its state holds the outcome for the life of the ledger. The wallet keeps its most recent
1,000 keys (R4) and nothing older.

**Why not the wallet alone**: keeping every key for the wallet's life made the state grow with
history (R4's 4.3 MB at 10,000 transactions). Keeping a bounded window alone leaves "has this
transaction been seen" uncertain past the window.

**Why not a transaction entity alone**: "does the entity for this key exist" and "debit the wallet"
are two writes on two entities, and ankka makes a check and the change it guards atomic only inside
one entity. Two copies of one request can both read "not seen" and both debit; a crash after the
debit and before the entity is written leaves a retry reading "not seen". Writing the entity first as a
claim does not settle a crash between the claim and the debit: the entity cannot know whether the money
moved. A certain version needs a consumer over the wallet's events to backfill outcomes and an
acknowledgement that lets the wallet forget a key, with a staleness bound on commands: the protocol a
workflow's journal gives for free.

**What the workflow gives**: `start` on an id the runtime hosts as a single writer is the claim (a
second start is `Conflict`); every step transition is journalled before the next step runs, so a
crash resumes rather than restarts, replacing the backfill and the re-claim rule; the finished
instance stays queryable by id, so it is the lifetime record; and the precondition (`may-bet`,
`may-deposit`) is asked once, in a step a repeat never reaches.

**Why the wallet still keeps recent keys**: a step runs at least once. A crash after the wallet
persisted and before the step's transition journalled re-runs the step and calls the wallet again,
which must answer with the first outcome. 1,000 is ample for a crash-and-resume, and also absorbs two
concurrent duplicates that get past a caller without a workflow. This is what lets the wallet step
carry retries.

**Waiting for the outcome**: ankka 0.11 has no call that awaits a workflow's terminal state (only
agents stream; a view cannot source a workflow), and a handler answers only its own caller. The
endpoint starts the workflow and polls its `status` on its virtual thread, 1 ms apart, to a bound (2 s
for deposits); the lifecycle query (`isTerminal`) tells a failed instance from a slow one. Past the
bound the route answers an error and the caller's retry is answered from the workflow's state. The
poll is one helper, to become one call when ankka gains the await: **a platform request**, recorded in
"ankka core features for eitheror".

**Measured** (prototype in the test tree, `it/proto/BetWorkflow.scala` and `BetWorkflowItSuite.scala`:
a workflow per provider transaction with steps `check-rg` and `debit`, polled at 1 ms, through the
component client, 1,000 bets each way, 2026-10-10, same laptop as R4): on a wallet of 1,000
transactions, direct `place-bet` median 0.4 ms and p99 8.7 ms against the workflow's 7.0 ms and
38.1 ms; on a wallet of 10,000 transactions, direct 0.5 ms and 77.3 ms against 9.7 ms and 121.8 ms
(SC-003's targets are 50 and 250 ms through the route, which measured 4.4 and 147 ms before this
change). The p99 on both paths was the 4.3 MB snapshot, which the bound removes. Fifty pairs of
duplicate starts, concurrent: 50 bets on the wallet, RG asked exactly 50 times, each pair's two
callers answered identically. An RG refusal ends the workflow `PLAYER_LOCKED`, the wallet never sees
the key, and a repeat after RG allows again answers the same refusal.

**Alternatives**: a transaction entity per key with a backfill consumer and an acknowledgement
(rejected above); the endpoint doing the calls with the workflow as claim and record only, a paused
step recovering after a crash (rejected: two code paths for one job, the cold one exercised only
after a crash); a count-bounded or time-bounded wallet store with no caller record (rejected: a
repeat past the bound is uncertain).

## Resolved unknowns

| Unknown | Resolved by |
|---|---|
| Entity grain | R1 |
| Where the resolver lives and in what language | R2 |
| How a refusal is idempotent | R3 |
| Key store bound and the digest | R4 |
| Exact deposit sums without a view | R5 |
| Where `may-deposit` is called, its timeout; `may-bet` moved to spec 003 | R6 |
| Brand configuration's shape and defaults | R7 |
| Where payment outcomes live and who publishes `payment.captured` | R8 |
| Fact publishing and the contract additions | R9 |
| Which reads are views | R10 |
| Wire format and the parity translation | R11 |
| Personal data and erasure before 042 ships | R12 |
| What the parity tests replay | R13 |
| Test kits and suites | R14 |
| Service layout | R15 |
| Where the lifetime record of a key lives, and how a caller waits for an outcome | R17 |

No `NEEDS CLARIFICATION` remains.
