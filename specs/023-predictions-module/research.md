# Research: the predictions module

Decisions for [plan.md](plan.md). The libraries' surface is ankka-predictions v0.1.0 (4bff8f3):
`modules/{markets,exchange}-components`, read in full for this plan; the brand services' routes
are read from their builds on `main` (1b01f5c).

## R1. Two services on ankka 0.14.2, the contract evicted up to it

**Decision**: `services/markets/` and `services/exchange/`, one sbt build each (`ankka init`), as
every service here. Both depend on ankka-predictions' components at a pinned version and so run
ankka 0.14.2, the libraries' own pin, where every other service runs 0.13.0. The contract
(`io.eitheror:casino-contract`) is built on 0.13.0 and is evicted to 0.14.2 in these two builds;
the first task proves it: the contract's suites compile and pass with `ankkaVersion` overridden to
0.14.2. If they do not, the repository moves to 0.14.2 first, as a chore of its own, and this plan
waits on it. The manifest gives each service its own `runtime`, so 0.13.0 and 0.14.2 run side by
side in one brand.

**Rationale**: the libraries cannot be used below the version they were built on; one service per
build is the repository's rule; the runtime is per service descriptor (`modules/core/module.json`).

**Alternatives**: one service holding both libraries (declined: `markets` and `exchange` are the
spec's two services, and a book's load is not a market's); moving the repository to 0.14.2 now
(not needed unless the eviction fails).

## R2. `markets` drives `exchange` over HTTP

**Decision**: the markets library never names the exchange (ankka-predictions spec 006: "driving
one from the other is the consumer's"). A consumer in `markets`, `ExchangeDriver`, over the market
entity's events, calls `exchange`'s service routes:

| Market event | Call | With |
|---|---|---|
| `Opened` | `POST /exchange/markets/{market}/open` | outcomes, currency, unit 100, the limits (R7), the fee rate (R8) |
| `Suspended` / `Resumed` | `…/suspend`, `…/resume` | |
| `Closed` | `…/close` | |
| `Final` | `…/finalise` | the resolved outcome, or void |

The consumer is at least once; a repeat the exchange refuses as `state` (already open, already
closed) is taken as done, any other refusal is raised on `platform.audit` and the event is not
skipped silently. Nothing schedules the exchange's own close timer (`ExchangeTimers.scheduleClose`
is never called by the library): the market's close drives it.

**Rationale**: constitution IV (a command across services is HTTP); one source of the lifecycle;
the close on time comes from `market-timers` once.

**Alternatives**: the exchange consuming `markets.events` (declined: the fact's shape is the
contract's, not the command's, and the limits and fee rate are not in it); one service (R1).

## R3. The gate port

**Decision**: `GatePort.mayPlace(owner, market, place)` asks, in order and failing closed:
`players` `GET …/players/{player}/status` (`active` or refused `account: <status>`), then if the
brand's gates (R7) include it `kyc` `GET …/kyc/{player}/play-check` (refused `play gate:
<code>`), then if included `responsible-gambling` `POST …/rg/may-bet` with the order's stake
(refused `bet gate: <decision>`). A sell is a stake of nothing: only the account and the play gate
are asked. An unreachable gate or a 5xx is a refusal naming the gate and `unavailable`. The
clients are game-provider's (`clients/Http.scala`, `Neighbours.scala`), copied: the contract does
not hold clients today, and a third copy is the time to move them.

The port is called on the placement workflow's step thread, which is where a service call may be
made (wallet README); the port takes its `ServiceClients` from the runtime at start. The first
task proves the client can be had outside a component's context; if it cannot, the port is given
the clients through a `ServiceClients` the endpoint's start-up passes in.

The three routes admit `exchange` (`Routes.serviceOf(…, "exchange")`): a one-word change in
`players`, `kyc` and `responsible-gambling` each, with each service's own suites run.

**Rationale**: FR-002, request items 5 and 6; the library's gate sees the whole `Place`, so the
stake is quantity × price there.

## R4. The ledger port, call by call

**Decision**: the wallet's routes (spec 022), the market's currency from R10's market terms:

| Port call | Wallet route | Key |
|---|---|---|
| `reserve(m, order, money)` | `POST /wallets/{owner}/{currency}/reservations` `{key: m/order, amount}` | the market and the order |
| `release(m, order, ordinal, money)` | `POST …/reservations/release` `{orderId: m/order, sequence: ordinal, amount}` | `m/order/ordinal` |
| `settle(m, trade, Direct, legs)` | `POST /settlements` `{tradeId: m/trade, buyer, orderId: m/order, seller, amount: charge, unused: released}` | the market and the trade |
| `settle(m, trade, Split, legs)` | per buying leg `POST /accounts/pool/{m}/splits` `{key: m/trade/owner, player, amount: charge, orderId: m/order, unused: released}` (FR-008a) | market, trade and owner |
| `settle(m, trade, Merge, legs)` | per selling leg `POST /accounts/pool/{m}/merges` `{key: m/trade/owner, player, amount: paid}` | market, trade and owner |
| `payout(m, position, money)` | `POST /accounts/pool/{m}/payments` `{positionId: owner/outcome, …}` | market and position |
| `charge`, `pay` (explicit split and merge) | none: answered `Refused("not offered")` (FR-016) | |
| the taker fee (R8) | spec 022's amended fee move into `/accounts/fee/fees/…` | trade and taker |

Order and trade ids are a book's own (every market has a `t-1`), and a wallet keys a player's calls
across every market, so every id the port sends is under its market (amended 2026-10-31, found on
kind; "Found during implementation").

Answers: a 2xx is `Done`; `INSUFFICIENT_FUNDS` is `Insufficient`; `OUTCOME_PENDING`, a 5xx or
an unreachable wallet throws (the library retries: placement with backoff within
`ledgerPatience`, 5 s; the settlement consumer by the runtime); any other refusal is
`Refused(<code>)`. Every call is keyed, so a retry is answered as the first was (constitution II).

**Rationale**: the port order in `contract/access/routes.md`; FR-003.

## R5. A settlement the wallet refuses

**Decision**: the library's settlement consumer logs any answer but `Done` and skips the event
(`Settlement.scala:43-51`), so FR-003's "recorded, retried and raised" is the port's. On a
`Refused` or `Insufficient` from `settle`, `payout` or the fee, the port starts a
`SettlementRetry` workflow keyed by the call's own key, before it answers. Its `start` is the
claim (a second start is the same retry); it writes a `platform.audit` record on the first
refusal, retries the same keyed call at 1 min, 5 min, 15 min, then hourly, and writes a second
record when it succeeds or when an operator abandons it (`POST /exchange/retries/{key}/abandon`,
operator class with the markets permission). The port then answers as the wallet did.

**Rationale**: the refusal is the one case the libraries leave open (spec User Story 2); the
retry's key is the wallet's, so a success after a retry is still one move.

**Alternatives**: throwing on a refusal so the runtime retries (declined: an endless redelivery
holds the consumer's partition behind one trade).

## R6. The markets permission

**Decision**: the realm gains a role `markets` (`brands/_realm/eitheror-operators.json`). Every
operator route of `markets` is of the operator class for the brand (`Routes.operator(slug)`), and
checks `markets` or `platform_admin` among the principal's roles before calling the library, as
kyc checks its compliance roles; a caller without it is refused `forbidden` naming the
permission. The resolution port therefore answers `Allow`: it is given only an `OperatorId`, and
the route has already decided. Change and move close are covered by the same check (request item
10); the operator recorded is `principal.subject`.

**Rationale**: FR-004; there is no named-permission concept in the contract today, and a role is
how the realm carries one.

## R7. A brand's values for the module

**Decision**: configuration keys, each with its row in `contract/keys.md`:

| Key | Type | Default | Read by |
|---|---|---|---|
| `predictions-categories` | comma list | none: a brand names at least one | `markets` |
| `predictions-currencies` | comma list | none | `markets` (a draft's currency must be one; no wallet key lists a brand's currencies today) |
| `predictions-max-order-cost` | minor units | none | `markets`, sent to the exchange at open |
| `predictions-max-position` | shares | none | as above |
| `predictions-taker-fee-bps` | basis points | 0 | as above |
| `predictions-gates` | comma list of `play-gate`, `may-bet` | both | `exchange` |
| `reasoning-service` | project/service | absent | `markets`, `exchange` |

A draft may carry its own maximum order cost and maximum position (FR-014). The market's
definition has no place for them, so `markets` keeps a `MarketTerms` key-value entity per market
(limits as drafted), written by the draft and change routes before the library is called, read by
`ExchangeDriver` at `Opened`. A category or currency outside the lists is refused before the
library is called (FR-013).

## R8. The taker fee waits on ankka-predictions spec 007

**Decision**: the fee rule (the reservation's fee bound, which leg is the taker, the fee on each
trade) is written in ankka-predictions spec 007, released as v0.2.0, with `Open` taking a fee rate
and a leg carrying its fee. This module sends the rate at open and makes the fee's move in the
ledger port, into the brand's fee account (`fee/fees/<currency>`), which spec 022 is amended to
allow (kind `fee`, purpose `fee`; charged from the taker's reservation for a buy, from available
for a sell just credited). Until v0.2.0, the module builds on v0.1.0 with the rate 0, and the two
fee scenarios are ignored naming spec 007.

**Rationale**: SC-001 (no settlement rule here); the library reserves only an amount and does not
name the taker, so the rule cannot be had outside it without duplicating the matcher's knowledge.

## R9. Facts

**Decision**: four topics in the contract, each `Topic[…]` with its families, in `Topics.brand`,
schemas by `just schemas`:

| Topic | Key | Families | Producer |
|---|---|---|---|
| `markets.events` | market | `market_drafted` … `market_final`, one per market event | `MarketFactsPublisher` over `market` |
| `exchange.orders` | market | `order_placed`, `order_cancelled`, `order_expired`, `order_filled` | `OrderFactsPublisher` over `exchange` |
| `exchange.trades` | market | `trade_made` (legs with each player as `Personal`), `position_paid`, `remainder_kept` | `TradeFactsPublisher` over `exchange` |
| `exchange.prices` | market | `prices_changed` (last price per outcome, the sequence) | `PriceFactsPublisher` over `exchange` |

Every topic is keyed by the market (FR-006). The player id is a plain id, as on every topic
(spec 001 FR-024); a trade's legs and its prices are nested objects, which the contract's field
types, personal-field scan and compatibility rules learn (decided in implementation). `reporting` and the shared `audit` read
them by the grant pattern `core` uses.

## R10. Reads and the price stream

**Decision**: player routes under the player class:

- the catalogue: the library's `market-catalogue` view (`by-state` open, `by-category`);
- a market's prices and depth: the exchange entity's `prices` and `depth` queries, each carrying
  the entity's sequence (consistent: never stale);
- the player's positions: the library's `exchange-positions` view (`by-owner`), which omits shares
  resting under sells; the route adds them from the player's orders;
- the player's orders and trades: views `exchange` adds, `orders-by-owner` and `trades-by-owner`,
  over the exchange's events (the library has no view of either). A view read carries the
  sequence it has seen, so a stale read says so (FR-005);
- the price stream: server-sent events per market; the handler reads `prices` and `depth` and
  emits when the sequence moves, each message numbered by it, so a client sees a gap and resyncs
  from the depth read.

`exchange` also keeps `MarketTerms` of its own (currency and outcomes, written by the open route),
which the ledger port reads for the currency.

## R11. Loss limits wait on spec 010's amendment

**Decision**: `responsible-gambling` is amended (spec 010) to fold a player's losses from
`money.transactions` itself, settlement debits and credits, account debits, resolution payments
and fees included, so `may-bet` needs no sums from any caller. `exchange` sends only the stake.
Until the amendment, a player with a loss limit is answered `unavailable` and refused naming the
bet gate, which is the safe reading.

## R12. Reconciliation

**Decision**: FR-011 as a view and a timer in `exchange`. A consumer of `money.transactions`
(a topic of the same project, so no grant) sums per market and family: reservations and releases
by order (the order's market from `orders-by-owner`), settlement debits and credits, splits and
merges (`account_debit` and `account_credit` on `pool/<market>`), payments, fees. A timed action
daily, and at `Final`, compares them with the exchange's own sums (from its events) and writes one
`platform.audit` record per difference naming the market, the family, the key and both figures.
Nothing corrects a difference.

## R13. Erasure

**Decision**: `exchange` serves `POST /erasure/players/{player}` (admitted to `players`): it
cancels the player's resting orders (from `orders-by-owner`), which releases their reservations,
and marks the player's `Personal` fields; positions stand until the market is final. `players`'
`ErasureWorkflow.targets` gains `exchange`. `markets` holds no player data and is not a target.

## R14. The reasoning mirror

**Decision**: FR-007 waits on ankka-reasoning 0.2.0, which is not cut. Its consumers are the last
phase; with `reasoning-service` absent (the default) nothing is mirrored, which is the scenario
that runs; the mirroring scenario is ignored naming ankka-reasoning 0.2.0 until it is.

## R15. The manifest

**Decision**: `modules/predictions/module.json`, kind `product`: services `markets` and
`exchange` (runtime 0.14.2), the four topics, the configuration keys of R7, no grant within the
brand (a service reads its own project's topics without one), the grants made to
`service:reasoner/audit` to consume the four topics (FR-006), as `core` makes them for
`platform.audit`, and a dependency on `core` at the version carrying spec 022 FR-008a and the fee amendment.
`contract/keys.md` gains the keys and the two services in its "Not yet built" table until their
builds exist; `just modules validate --all` passes. The test fixture
`tools/modules/src/test/resources/manifests/predictions.json` is left as the tool's own fixture.

## R16. Tests

**Decision**:

- unit: the gate's and the ledger port's mappings against scripted replies (every row of R3, R4);
- bound features: `features/predictions/*.feature` through `GherkinSuite`, in each service, with
  the neighbours scripted at their recorded route contracts (players, kyc, responsible-gambling,
  wallet) over the testkit's HTTP;
- integration (Docker): `AnkkaTestKit` over Postgres for the placement-to-settlement path, the
  restart between a trade and its settlement (settled once), the retry workflow, the facts;
- SC-002 in a perf suite with the neighbours scripted at a fixed latency;
- SC-003, every scenario of ankka-predictions `features/exchange/orders.feature` against the real
  wallet, players, kyc and responsible-gambling, in the kind integration suite (`just it`), run on
  purpose only.

No recordings: the original has no prediction markets (constitution I; the spec's *Changes from
the original*).

## R17. Prerequisites, and what builds without them

| Prerequisite | Needed by | Without it |
|---|---|---|
| the contract on ankka 0.14.2 (R1) | everything | a repository bump first |
| ankka-predictions spec 007, v0.2.0 | the taker fee | build on v0.1.0 with rate 0; two scenarios ignored |
| spec 022 amended: account kind and purpose `fee` | the fee's move | as above |
| spec 010 amended: losses folded in `responsible-gambling` | loss limits on orders | a limited player is refused `unavailable` |
| ankka-reasoning 0.2.0 | the mirror | nothing mirrored; one scenario ignored |
| `players`, `kyc`, `responsible-gambling` admitting `exchange` | the gate | in this branch, three one-line changes |

## Design finished

2026-10-31: analyze reported nothing above LOW.

## Found during implementation

- **T002–T003, the eviction.** sbt's early-semver check refuses the contract's ankka 0.13.0 evicted to
  0.14.2. The two builds lift it for ankka's artifacts alone (`libraryDependencySchemes`, `Always`),
  as R1 planned; T001 passed the contract's suites on 0.14.2, and these services' suites run its
  classes there. ankka-predictions v0.1.0 is not on Maven Central (the release's signing failed), so
  the builds resolve it from `~/.ivy2/local` (`sbt publishLocal` at the tag) until it is.
- **T005, the facts.** The design keyed `exchange.orders` by player and marked the player id
  personal; FR-006 keys every predictions topic by the market (ankka-predictions 003), and spec 001
  FR-024 holds the player id a pseudonym. Trevor chose spec 001's rule, and nested objects for a
  trade's legs and prices: `FieldType.nested`, `FamilyDef.allFields`, and `Compatibility` reading
  within a nested object. `modules/predictions/module.json` is written here, ahead of T040, because
  the contract's brand topics are read from the manifests; spec 001's fixed lists leave it out.
  goldvibe's values gain the module's four required keys, so the tool's predictions scenario runs.
- **T006.** The two services' builds exist from T002, so they never enter keys.md's "Not yet built".
- **The routes' prefixes.** contracts/routes.md put every route under `/v1/casino/brands/<slug>`;
  Trevor chose the repository's convention, one endpoint per audience under its own prefix
  (operators `/v1/casino/backoffice/<slug>/<service>`, players `/v1/casino/<service>/<slug>`,
  services `/v1/casino/brands/<slug>/<service>`), and routes.md is rewritten to it.
- **The review window** is 24 hours, ankka-predictions spec 002 FR-007's default, carried on every
  resolve, correct and void; it becomes a brand value when a brand asks for another.
- **T017, the clients outside a component.** The running service's `services` (`AnkkaService.services`)
  answer a call made outside any component's context, so the ports reach them through
  `ExchangeRuntime.started`, which `Main` and the test kit call once the service is up
  (`it/ServiceClientsSuite`). The ports are built before the start and read the clients per call.
- **T020 and T027 together.** The ledger port's `settle` and `payout` were written with `reserve` and
  `release`: the orders feature's sell scenario needs a held position, which a split trade makes, and
  the library's settlement consumer settles it at once. `LedgerSuite` covers every row of R4.
- **T036, the catalogue scenario split.** catalogue.feature's first scenario joined `markets`'
  catalogue with `exchange`'s prices and depth, which no one service's suite can run. Trevor chose to
  split it: the catalogue scenario is bound in `markets`, the prices and depth scenario in
  `exchange`; the joined read is the kind suite's (T043).
- **T035, shares under sells.** The library's positions view keeps a holding's full `quantity`,
  the shares resting under a sell among them (its `Position` counts `restingUnderSells` apart and
  leaves `quantity` whole). R10's "the route adds them" is a field, not a sum: a position answers
  `quantity` and `restingUnderSells`, the second taken from the player's resting sells. The
  positions view keeps no sequence; the orders view's, over the same journal, stands for it in the
  `stale` answer.
- **T039, the orders topic's key.** facts.feature and R9's table said `exchange.orders` was keyed
  by the player; FR-006, contracts/facts.md and the contract key every exchange topic by the
  market. Trevor settled it as the scenario's error: the step now says "keyed by the market".
- **T040, core's range.** No release tag exists yet and spec 022 is on main, unreleased. Trevor
  set predictions' dependency to `core` `>=0.4.0 <1.0.0`, taking the next tag after 022 to be
  v0.4.0 or later, so no vendor pairs predictions with a core whose wallet lacks reservations,
  splits and pool payments. `markets` and `exchange` were never in keys.md's "Not yet built"
  table: their builds landed with T002 and T003. The services read `REASONING_SERVICE`; the
  manifest names no such key until ankka-reasoning 0.2.0 (keys.md, R14), so nothing is mirrored.
- **T041, how players learns of the exchange.** players' erasure calls each target and retries
  until it answers, so an `exchange` target on a brand without predictions would stop every
  erasure. Trevor chose that the module wire it. A manifest's configuration may carry no value
  and the vendor's format no new field, so the wiring is the module's route row: a planned
  service with a `POST …/erasure/players/{player}` row in `contract/access/routes.md` is an
  erasure target, and the modules tool derives `erasure-targets` (`service=prefix` pairs, `none`
  when there are none) as it derives `casino-modules`; core's manifest gives it to players as
  `ERASURE_TARGETS`. players erases them after core's own targets; the export does not ask them
  (exchange holds nothing to export). Through a vendor the brand writes the value until spec 021
  renders it (keys.md). The player id is a plain id on every fact and view (FR-006), so the
  exchange's "mark" is a record of the erasure, which also makes a repeat answer the first.
- **T043, found on kind (SC-003 against goldvibe).** The kind suite found three faults the
  service suites could not, each settled by Trevor:
  - *Ids across markets.* The ledger port sent order and trade ids bare, but a book numbers its
    own, so a player's second market reused the first's keys: its reservation was answered as a
    repeat, its split found no reservation (`RESERVATION_NOT_FOUND`), and the library passed the
    event, granting the position with no money moved (8 of 8 markets on kind). Trevor chose to
    qualify by market: R4's table is amended, the tally keys and indexes the same ids, a retry's
    key names the market, and `LedgerSuite` holds two markets reusing an order and a trade id.
  - *An order priced or sized below one* reached the wallet as a reservation of nothing or less,
    refused `INVALID_REQUEST` before the library could name the price or the quantity. Trevor chose
    the library: ankka-predictions' placement sends such an order to the book alone (no gate, no
    reservation), on the 0.1.1 branch.
  - *A book with no terms.* `markets` shows a market open before its driver has opened the book on
    `exchange`, and an order in that window failed 424 when the gate read the missing terms. The
    place route now refuses it `state: empty` (409), as the library refuses a book that is not open.
  - *Funding.* A staff adjustment credits a wallet no deposit has opened, and the wallet refuses a
    reservation on one (`WALLET_NOT_FOUND`); the suite funds each player by a deposit, as
    `payments/merchant` lands one, from merchant's pod with its own service identity.
  - *Repeats.* The place route stamps each call with its clock, and the library's placement compared
    the whole request, so a repeat sent later was refused as another order on other terms; the
    library now compares it without its time (0.1.1). Another order under a placed order's id met
    the workflow's conflict error, not the id refusal; the route now refuses it naming the id.
  - *Instance size.* The exchange was OOM-killed on the small default (512 MiB) under the suite;
    the predictions manifest gives `markets` and `exchange` the medium instance, as core's services.
    core's `responsible-gambling` stays on the small instance and was OOM-killed once under the
    suite (every order asks it the bet gate); that is core's manifest, left for its owner.
  - *The result*, 2026-10-31 on goldvibe (kind, platform 0.14.2, ankka-predictions
    0.1.1-pr023-SNAPSHOT): 18 of 18 scenarios pass and one runs outside (the maximum position, which
    orders cannot set up: 100 shares held against a maximum of 50). The run before it failed only on
    sign-ins answered `rg_unavailable` while responsible-gambling was restarting.
    `PredictionsOnKindSuite` (`just it`, `CASINO_IT_PREDICTIONS=<slug>`) is the check.
