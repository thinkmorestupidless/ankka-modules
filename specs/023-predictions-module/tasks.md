# Tasks: The predictions module — markets and an exchange in a brand

**Input**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

**Tests**: required. The constitution holds every service to suites at three levels (unit kit,
bound features, the integration kit over Postgres), and every scenario in `features/predictions/`
is bound in a service's `GherkinSuite`.

**Order**: the plan's phases. US3 (the lifecycle) comes before US1 because a book is opened only
by `markets` driving `exchange` (R2); US1's own tests open a book through the service route
directly, so it is testable without US3.

Paths: `S/m` = `services/markets/src`, `S/x` = `services/exchange/src`, packages
`io.eitheror.casino.markets` and `io.eitheror.casino.exchange` beneath `main/scala` and
`test/scala`.

## Phase 1: Setup

- [X] T001 Prove the contract on ankka 0.14.2 (R1): run the contract's suites with `ankkaVersion` overridden to 0.14.2 in `build.sbt` (a `sys.props` override, default 0.13.0); if they fail, stop and raise the repository bump as its own chore before going on
- [X] T002 [P] Scaffold `services/markets/` with `ankka init` (ankka 0.14.2): `build.sbt` depending on `casino-contract` (`CASINO_VERSION`) and `com.thinkmorestupidless %% ankka-predictions-markets-components` 0.1.0, `service.json`, `docker-compose.yml`, `README.md`, an empty `Main.scala` registering nothing yet
- [X] T003 [P] Scaffold `services/exchange/` likewise, depending on `ankka-predictions-exchange-components` 0.1.0
- [X] T004 [P] Add `just` recipes `markets-test`, `markets-it`, `exchange-test`, `exchange-it`, `exchange-perf` in `Justfile`, each through `just service <name> …`

## Phase 2: Foundational

- [X] T005 Add the four topics and their families (contracts/facts.md) in `contract/src/main/scala/io/eitheror/casino/contract/facts/Markets.scala` and `Exchange.scala`, the player id as `Personal[String]`; list them in `Topics.brand`; regenerate `contract/schemas/` with `just schemas`; a contract suite asserts every family's personal fields are marked
- [X] T006 [P] Add the seven configuration keys of R7 to `contract/keys.md`, and `markets` and `exchange` to its "Not yet built" table
- [ ] T007 [P] Add the realm role `markets` to `brands/_realm/eitheror-operators.json` and a `Routes` helper `operatorWith(slug, roles*)` in `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala` (the brand role check plus one of the roles, else `forbidden` naming the first), with its suite in `contract/src/test/…/access/RoutesSuite.scala`
- [ ] T008 [P] Admit `exchange` on `players`' status route (`services/players/…/api/Endpoints.scala`), kyc's play-check (`services/kyc/…/api/ServiceEndpoint.scala`) and responsible-gambling's may-bet (`services/responsible-gambling/…/api/Endpoints.scala`); one ACL test each in the services' existing suites; run each service's `test`
- [ ] T009 [P] `Settings.fromEnvironment` for each service: `S/m/main/…/markets/Settings.scala` (brand, categories, currencies, limits, fee rate, reasoning service) and `S/x/main/…/exchange/Settings.scala` (brand, gates, reasoning service), with suites for defaults and refusals of malformed values

## Phase 3: User Story 3 — the lifecycle under the brand's roles (P1)

**Goal**: an operator with the `markets` role drafts, approves, changes, moves the close, closes,
suspends, resumes, resolves, corrects and voids a market; every change is a fact on
`markets.events`. **Independent test**: `lifecycle.feature` bound in `markets`.

- [ ] T010 [US3] `MarketTerms` key-value entity (data-model) in `S/m/main/…/markets/terms/MarketTerms.scala`, with its unit suite
- [ ] T011 [US3] The resolution port answering `Allow` (R6, contracts/ports.md) in `S/m/main/…/markets/Resolution.scala`; register `MarketsComponents` and `MarketTerms` in `Main.scala` with `ProjectionRuntime` and `TimerRuntime`
- [ ] T012 [US3] `OperatorEndpoint` (contracts/routes.md, markets) in `S/m/main/…/markets/api/OperatorEndpoint.scala`: `operatorWith(slug, "markets", "platform_admin")`, the category and currency checks before the library (FR-013), drafted limits written to `MarketTerms` first, the library's refusals mapped to 400/409 with their names
- [ ] T013 [US3] `MarketFactsPublisher` over the market entity onto `markets.events` in `S/m/main/…/markets/facts/MarketFactsPublisher.scala`
- [ ] T014 [US3] Bind `features/predictions/lifecycle.feature` in `S/m/test/…/markets/features/LifecycleFeatures.scala` through `GherkinSuite`
- [ ] T015 [US3] Integration suite over Postgres in `S/m/test/…/markets/it/LifecycleItSuite.scala`: the timers open and close a market on time after a restart; the facts appear once each

## Phase 4: User Story 1 — an order passes the gates and is funded (P1)

**Goal**: a buy is checked against the account, the play gate and the bet gate, reserved, and
rests; any refusal rests nothing and reserves nothing. **Independent test**: `orders.feature`
bound in `exchange`, the book opened through the service route.

- [ ] T016 [P] [US1] Copy `Http`/`Reply` and the players, kyc and RG clients from game-provider into `S/x/main/…/exchange/ports/` (`Http.scala`, `Neighbours.scala`), and the wallet's routes into `WalletClient.scala`; note in each file's header where it was copied from
- [ ] T017 [US1] Prove `ServiceClients` can be had by a port outside a component's context (R3) in a spike suite `S/x/test/…/exchange/ports/ServiceClientsSuite.scala`; if not, pass the clients in from `Main` and record the answer under `## Found during implementation` in research.md
- [ ] T018 [US1] The gate port in `S/x/main/…/exchange/ports/Gates.scala` (contracts/ports.md): order, failing closed, sells skip may-bet, gates by `predictions-gates`; a unit suite `GatesSuite.scala` covering every row with scripted replies
- [ ] T019 [US1] `MarketTerms` (currency, outcomes) in `S/x/main/…/exchange/terms/MarketTerms.scala`
- [ ] T020 [US1] The ledger port's `reserve` and `release` (R4) in `S/x/main/…/exchange/ports/Ledger.scala`, `charge` and `pay` answering `Refused("not offered")` (FR-016); `LedgerSuite.scala` maps every wallet code to its answer
- [ ] T021 [US1] Register `ExchangeComponents` in `S/x/main/…/exchange/Main.scala` with the two ports and `ledgerPatience` 5 s
- [ ] T022 [US1] `ServiceEndpoint` in `S/x/main/…/exchange/api/ServiceEndpoint.scala`: `/exchange/markets/{market}/{command}` admitted to `markets` only; open writes `MarketTerms` then sends `Open` with unit 100 and the limits
- [ ] T023 [US1] `PlayerEndpoint` order and cancel routes in `S/x/main/…/exchange/api/PlayerEndpoint.scala`: place starts `exchange-placement` and polls its outcome as `Flows.answer` does; another player's order is `forbidden`
- [ ] T024 [US1] `ExchangeDriver` consumer in `S/m/main/…/markets/drive/ExchangeDriver.scala` (R2): each market event to its call, limits from `MarketTerms` else the values; a `state` refusal is done, anything else is audited on `platform.audit`; its unit suite with a scripted exchange
- [ ] T025 [US1] Bind `features/predictions/orders.feature` in `S/x/test/…/exchange/features/OrdersFeatures.scala`, the neighbours scripted at their route contracts; the fee scenario ignored naming ankka-predictions spec 007
- [ ] T026 [US1] Integration suite `S/x/test/…/exchange/it/PlacementItSuite.scala`: a wallet that answers `OUTCOME_PENDING` then `Done` funds once; an unreachable kyc refuses `play gate: unavailable`

## Phase 5: User Story 2 — money through the brand's wallet (P1)

**Goal**: each trade and each payout is one keyed wallet call; a refusal is recorded, retried and
raised; the module reconciles. **Independent test**: `settlement.feature` bound in `exchange`.

- [ ] T027 [US2] The ledger port's `settle` (direct, split, merge) and `payout` (R4) in `Ledger.scala`, one call per leg as the table says; extend `LedgerSuite.scala`
- [ ] T028 [US2] `SettlementRetry` workflow (R5, data-model) in `S/x/main/…/exchange/retry/SettlementRetry.scala`: `start` as the claim, the backoff 1, 5, 15 min then hourly, an audit record at the first refusal and at the end; the port starts it before answering a refusal; its unit suite
- [ ] T029 [US2] `OperatorEndpoint` in `S/x/main/…/exchange/api/OperatorEndpoint.scala`: `POST /exchange/retries/{key}/abandon` under `operatorWith(slug, "markets", "platform_admin")`
- [ ] T030 [US2] The reconciliation (R12) in `S/x/main/…/exchange/reconcile/`: the `reconciliation` view over `money.transactions` and the exchange's events, a daily timed action and one at `Final`, one audit record per difference; `money.transactions` is the brand project's own topic, read with no grant; a unit suite feeding a deliberate difference
- [ ] T031 [US2] Bind `features/predictions/settlement.feature` in `S/x/test/…/exchange/features/SettlementFeatures.scala`; the fee scenario ignored naming ankka-predictions spec 007
- [ ] T032 [US2] Integration suite `S/x/test/…/exchange/it/SettlementItSuite.scala`: a restart between a trade and its settlement settles once; a refused settlement retries and succeeds under the same key; a final market pays every position from the pool

## Phase 6: User Story 4 — the catalogue, prices and positions (P2)

**Goal**: a player reads open markets, prices and depth, and only their own orders, positions and
trades. **Independent test**: `catalogue.feature` bound in both services.

- [ ] T033 [P] [US4] The catalogue routes in `S/m/main/…/markets/api/PlayerEndpoint.scala` over the library's `market-catalogue` view, a draft or approved market 404
- [ ] T034 [P] [US4] Views `orders-by-owner` and `trades-by-owner` in `S/x/main/…/exchange/reads/` with unit suites
- [ ] T035 [US4] The read routes in `PlayerEndpoint.scala` (prices, depth, the player's orders, positions with shares under sells added, trades), each carrying its sequence; the SSE stream per market numbered by the sequence (R10); a view read behind the entity's sequence answers with `stale: true` (FR-005)
- [ ] T036 [US4] Bind `features/predictions/catalogue.feature` in `S/m/test/…/markets/features/CatalogueFeatures.scala` and `S/x/test/…/exchange/features/CatalogueFeatures.scala`

## Phase 7: User Story 5 — facts and the reasoning mirror (P2)

**Goal**: every order, trade and price change is a fact; the mirror runs only when a reasoning
service is named. **Independent test**: `facts.feature` bound in `exchange`.

- [ ] T037 [P] [US5] `OrderFactsPublisher`, `TradeFactsPublisher`, `PriceFactsPublisher` in `S/x/main/…/exchange/facts/` (R9), with unit suites over sample events
- [ ] T038 [US5] `MarketMirror` and `PriceObservations` behind `reasoning-service` (R14) in `S/m/main/…/markets/facts/` and `S/x/main/…/exchange/facts/`, registered only when the value is present; at most one observation per outcome a minute, with a unit test of the throttle
- [ ] T039 [US5] Bind `features/predictions/facts.feature` in `S/x/test/…/exchange/features/FactsFeatures.scala`; the mirroring scenario ignored naming ankka-reasoning 0.2.0

## Phase 8: Polish and cross-cutting

- [ ] T040 `modules/predictions/module.json` (R15): both services at runtime 0.14.2 with their env entries, the four topics, the configuration, the grants made to `service:reasoner/audit` to consume the four topics (FR-006), the core dependency; `just modules validate --all` and `just modules render` for a brand entitled to `predictions`; a modules-tool test that a brand entitled to `predictions` and not `casino` renders `markets` and `exchange` and no game-provider (SC-004); move the services out of keys.md's "Not yet built"
- [ ] T041 Erasure (R13): `POST /erasure/players/{player}` in `S/x/main/…/exchange/api/ServiceEndpoint.scala` cancelling resting orders, then marking the player's `Personal` fields on the exchange's facts and views; `exchange` added to `services/players/…/erasure/ErasureWorkflow.scala` targets; a test each side
- [ ] T042 [P] `PlacementPerfSuite` in `S/x/test/…/exchange/perf/` (SC-002): 200 orders/s, neighbours at a fixed latency, median ≤ 50 ms, p99 ≤ 200 ms printed and asserted
- [ ] T043 [P] SC-003 in the kind integration suite (`just it`, on purpose only): ankka-predictions' `features/exchange/orders.feature` against the real gates and wallet
- [ ] T044 [P] An SC-001 check in `S/x/test/…/exchange/NoRulesSuite.scala` and its twin in markets: no source file outside the ports names a price, quantity or phase rule (the suite fails when a main source outside `ports/` and `api/` constructs the library's domain commands or reads a book's or market's state other than through the components' entity and view clients)
- [ ] T045 [P] READMEs for both services (components, suites, how to run); CLAUDE.md's spec table and the brain's project file name 023 as built; spec status Built
- [ ] T046 Run quickstart.md end to end and record the results in the pull request

## Dependencies

- Phase 1 → Phase 2 → US3 → US1 → US2 → (US4 ∥ US5) → Polish.
- US1 needs US3 only for T024 (the driver); its feature binding opens the book itself.
- T001 gates everything: a failure stops the build for a repository bump.
- The fee (FR-015) waits on ankka-predictions v0.2.0 and the spec 022 fee amendment; loss limits
  on the spec 010 amendment (R17). Neither blocks any task here; their scenarios are ignored with
  the reason named.

## Parallel examples

- Phase 1: T002, T003, T004 together after T001.
- Phase 2: T006, T007, T008, T009 together after T005.
- US1: T016 beside T019; US4: T033 beside T034; Polish: T042–T045.

## Implementation strategy

MVP: Phases 1–4 (a market drafted, opened by the driver, an order gated and funded). Then US2,
which makes the module safe to take money; US4 and US5 after. Each phase ends with its feature
binding green.
