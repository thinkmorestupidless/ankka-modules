# Tasks: rewards

**Input**: [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md); the spec's seven stories, the six
`features/rewards/*.feature` files and FR-034 to FR-036's scenarios in
`features/players/duplicates.feature`.

**Tests**: required. The constitution makes the recordings the acceptance test (I), the success
criteria name suites, and the plan names them. Pure suites, feature bindings and parity tests are
written before the components they test and fail first (ankka-port §7).

Paths:

- `S` = `services/rewards/src/main/scala/io/eitheror/casino/rewards/`;
- `T` = `services/rewards/src/test/scala/io/eitheror/casino/rewards/`.

Build with `CASINO_VERSION=0.2.0-011-SNAPSHOT` (memory: contract snapshot collisions).

## Phase 1: Setup

- [X] T001 The contract additions (contracts/facts.md; research R11), every new field `Option` with a
      default of `None`:
      - `contract/src/main/scala/io/eitheror/casino/contract/facts/Others.scala`: `source`, `code`,
        `tag` on every `casino.rewards` reward family; `reason` on `BonusCancelled`; `amountCents`,
        `currency` on `BonusForfeited`; `outcome` on `BonusCodeEntered`;
      - `contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala`: the families
        `player_duplicate_check_started`, `player_duplicate_check_ended`, `player_no_deposit_blocked`,
        `player_no_deposit_block_cleared`, registered in the topic;
      - `contract/src/main/scala/io/eitheror/casino/contract/facts/MoneyTransactions.scala`:
        `restricted_funds_closed(playerId, currency, transactionId, awardId, reason, realBalanceAfter,
        restrictedBalanceAfter, occurredAt)`;
      - `specs/001-brands-and-projects/contracts/topics.md`: the new families on their topics;
      - then `just schemas` (the diff shows optional fields and new families only), `just test` green,
        `sbt contract/publishLocal`.
- [X] T002 Add `REWARDS_CODE_DOCUMENT_URL` (research R13) to `contract/keys.md`, to
      `modules/casino/module.json`'s `configuration[]` (empty default) and to the `rewards` service's
      `env[]`; `just modules render goldvibe` shows it. `just test` green.
- [X] T003 Run `ankka init` for the service at `services/rewards/`, in the shape of `services/payouts/`:
      `build.sbt` on `casino-contract` and ankka 0.13.0, Scala 3.9.0, munit, munit-scalacheck,
      postgresql, test `-Dcasino.root` and `CASINO_BRAND=goldvibe`, fork, `dockerExposedPorts := Seq(9000)`,
      the `schema` task; `service.json` (`rewards`, runtime 0.13.0); `docker-compose.yml` (Postgres
      on 5441); `src/main/resources/application.conf` (`ankka.service.name = rewards`,
      `ankka.service-client.timeout = 2s`); `README.md` (components, suites, local run).
- [X] T004 Remove the `rewards` row from `contract/keys.md`'s "Not yet built" table in the same
      commit as `services/rewards/build.sbt`; `ModuleManifestSuite` green (`just test`).
- [X] T005 [P] `justfile`: `rewards-test`, `rewards-it`, `rewards-run brand="goldvibe"` (HTTP 9008,
      Postgres 5441, both issuers, `-Dankka.local-services.wallet=http://127.0.0.1:9000
      -Dankka.local-services.game-provider=http://127.0.0.1:9009
      -Dankka.local-services.players=http://127.0.0.1:9003`), as `payouts-run`.
- [X] T006 [P] `.github/workflows/ci.yml`: a `rewards` paths filter (`*build`, `services/rewards/**`,
      `features/rewards/**`, `tests/port/rewards/**`) and a job as payouts', with a `-Dit=true` step.

## Phase 2: Foundational

**Neighbours** (research R6, R9). Each with its suite written first.

- [ ] T007 Wallet W1 in `services/wallet/`: `AwardRequest.maxConcurrentGrants: Option[Int]` in
      `wire/Wire.scala`; `api/ServiceEndpoint.scala` passes it to `Award.maxConcurrentAwards` when present,
      else the brand config's; suite cases in `EntitySuite` (limit from the body; body absent → the
      brand's; neither → none).
- [ ] T008 Wallet W2: `ClosePendingCap.grantRef: Option[String]` (route body `GrantRequest`);
      `funding/Resolver.closePendingCap` closes the named record, `NOT_PENDING_CAP` when it is not
      pending cap; the first otherwise, as now. Suite cases with two pending-cap records.
- [ ] T009 Wallet W3: in `entity/WalletEntity.cancelAward`, a named record that is not primary and has
      no release progress claws back 0 (`BR:2919-2925`). Suite case.
- [ ] T010 Wallet W4: `CancelAward.requireBaseline: Boolean = false`; refused `MISSING_BASELINE`
      (409, `entity/Refusals.scala`, `wire/Wire.scala` status table) when the named record has no
      baseline. Suite cases.
- [ ] T011 Wallet W5: `publish/Facts.scala` publishes `restricted_funds_closed` for `PendingCapClosed`
      (reason `cap_expired`) and for each cap draw that closes a record (reason `cap_exhausted`);
      `FactsSuite` cases. Update `specs/002-wallet/contracts/routes.md` and `facts.md` for W1 to W5,
      and `just wallet-test` green.
- [ ] T012 [P] `game-provider` G1: `GET /v1/casino/brands/<slug>/game-provider/{provider}/games/{gameCode}`
      in `services/game-provider/src/main/scala/io/eitheror/casino/gameprovider/api/Endpoints.scala`,
      `withAcl(Routes.serviceOf("rewards"))`, answering `{gameCode, bonusTypes}` from the catalogue or
      404 `game_not_found`; suite case; `specs/003-game-provider/contracts/routes.md` updated;
      `just service game-provider test` green.
- [ ] T013 `players` FR-035 tokens: `registration/LookupTokens.scala` gains `emailVariant` and
      `address` (HMAC under `players-lookup-key`); the email-variant normalisation as
      `MultiAccountDetector.scala`'s, with its spec's cases in a new `EmailVariantSuite`;
      `LookupIndexEntity` keeps per address token the registrations of the last 24 hours.
- [ ] T014 `players` FR-034, FR-035: `RegistrationWorkflow` records `DuplicateCheckStarted` before
      `matchStep`; the step's outcome (strong, weak, clear, skipped) and `DuplicateCheckEnded` are one
      event on the player; a failed match after its retries ends `weak`; a weak outcome sets
      `noDepositBlocked`. The strong and weak rules of research R9. Suite cases per rule.
- [ ] T015 `players` FR-036: `POST /v1/casino/backoffice/<slug>/players/{playerId}/clear-no-deposit-block`
      (`Routes.operator` plus the six roles in the handler; `forbidden` otherwise), answering
      `{playerId, blockCleared: true}`, recording the operator and time; the operator profile shows
      `noDepositOfferBlocked`; a fraud case's dismissal leaves the block. Suite cases.
- [ ] T016 `players` facts: `facts/Facts.scala` publishes the four new families; `FactsSuite` cases;
      bind the six FR-034 to FR-036 scenarios of `features/players/duplicates.feature` in the players
      feature suite; amend `specs/008-players/spec.md` (FR-036 references FR-034 to FR-036 of spec
      011) and its `contracts/facts.md`; `just service players test` green.

**The service's foundation.**

- [X] T017 `S/Settings.scala` (brand, wallet, game-provider, players, programme URL, poll interval 30 s,
      poll limit 50, code refresh 5 min, staleness 15 min, request bound 2 s; `fromEnvironment`
      reads `CASINO_BRAND`, `REWARDS_CODE_DOCUMENT_URL`) and `S/RewardsCodecs.scala` (`stored`,
      `serializer`, `row`, `wire`, and a `RewardsRuntime` holder for settings, clock, client factories
      and timers, swapped by suites), as `PayoutsCodecs`.
- [ ] T018 `S/RewardsService.scala`: `descriptors`, `endpoints(authenticate)`, `started(timers)`,
      `@main run` with Kafka when `ANKKA_KAFKA_BOOTSTRAP_SERVERS` is set, else `InMemoryBroker`;
      `TimerRuntime`; `HttpServer.of(… Oidc.authenticate())`.
- [ ] T019 [P] Pure `S/reward/Transitions.scala`: the ten states and the original's table; refusal
      `INVALID_TRANSITION(from, to)`; a repeat a no-op. `T/reward/TransitionsSuite.scala` first, with
      `RewardStatusSpec`'s cases.
- [ ] T020 [P] Pure `S/offers/OfferRules.scala` and `S/offers/Codes.scala`: create and edit checks
      in data-model.md's order with their exact codes; normalisation (`trim.toLowerCase`); payload
      type derived from type. `T/offers/OfferRulesSuite.scala` first, with `BonusRoutesSpec`'s create
      cases (2036-2260) and the edit list of research R1.
- [ ] T021 [P] Pure `S/eligibility/Evaluator.scala`, `Decline.scala`, `Amounts.scala`: research R4's
      thirteen rules in order, the two modes, the anchors, `computeBonusAmount` in minor units, half
      up. `T/eligibility/EvaluatorSuite.scala` and `AmountsSuite.scala` first, with
      `EligibilityEvaluatorSpec`'s cases.
- [ ] T022 [P] Pure `S/reward/Settlement.scala`: counting a round once, completion at
      `max(used, seen) >= count`, the destination, zero pays nothing, `CLOSED` or `EXPIRED` at expiry,
      the late round. `T/reward/SettlementSuite.scala` first, with the `settle_free_spins_round` and
      `finalize_free_spins_grant` handler tables.
- [ ] T023 [P] `S/clients/Neighbour.scala`, `S/clients/Wallet.scala` (trait + `Http`: the six award
      routes, `restricted-funds`, `balance`), `S/clients/GameProvider.scala` (known, create, get,
      cancel, spin values, game), `S/clients/Players.scala` (brand config), `S/clients/Programme.scala`
      (the document), each returning `Reply`; contracts/routes.md's *Calls out*.
- [ ] T024 [P] `T/it/FakeServer.scala`, `T/it/FakeWallet.scala`, `T/it/FakeGameProvider.scala`,
      `T/it/FakePlayers.scala`, `T/it/FakeProgramme.scala`: scriptable answers, `down`, `delayMillis`,
      a record of every call.
- [ ] T025 `T/it/Kit.scala` and `SharedKit`: `AnkkaTestKit.start` over Postgres with the fakes under
      `localServices`, `InMemoryBroker`, `TimerRuntime(200.millis)`, a settable clock, callers
      `asPlayer`, `asOperator(roles)`, `asService(name)`, `facts(topic)`, `publish`, `timerExists`,
      `fireTimer`, `eventually`, as payouts'.
- [ ] T026 `S/reward/Model.scala` and `S/reward/Reward.scala`: data-model.md's state, commands and
      events through `Transitions`; the timers set and cancelled per research R10 (`S/reward/Clocks.scala`).
      `T/reward/RewardSuite.scala` (`EventSourcedTestKit`) first: every row of the command table.
- [ ] T027 `S/steps/WalletStep.scala`: the workflow of research R5 (claim on `start`, retry until the
      wallet answers, deliver to `Reward`); `NO_ACTIVE_BONUS` and `NOT_PENDING_CAP` on a timer's step
      move the reward without money. `T/it/WalletStepSuite.scala` first: every wallet answer, a
      transport failure, a repeat start.
- [ ] T028 `S/reads/RewardsView.scala`: data-model.md's row and queries. `T/it/RewardsViewSuite.scala`.
- [ ] T029 `S/facts/Facts.scala` (pure event → fact, research R11) and `S/facts/Publishers.scala`
      (`RewardsPublisher`, `CodesPublisher`, `AuditPublisher`). `T/facts/FactsSuite.scala` first:
      every event to at most one family; the close after conversion publishes nothing; no `Personal`
      value on a fact.
- [ ] T030 `S/api/Answers.scala`: refusals as `{error, …}` with the status table of contracts/routes.md;
      the original's shapes (`{status: "ineligible", reason}`, `{valid: false, error}`); amounts
      refused when not whole (FR-032).

**Checkpoint**: neighbours amended and green; the pure packages, `Reward`, `WalletStep`, the view and
the publishers green under their suites.

## Phase 3: US1, an operator creates an offer and a player claims it (P1)

**Goal**: a deposit match from creation to restricted funds at the wallet.
**Independent test**: `offers.feature`'s US1 scenarios through the kit with `FakeWallet`.

- [ ] T031 [US1] Bind `features/rewards/offers.feature` in `T/features/OffersFeatures.scala`
      (`GherkinSuite`); it fails first.
- [ ] T032 [US1] `S/offers/Model.scala` and `S/offers/OfferBook.scala`: data-model.md's commands and
      queries. `T/offers/OfferBookSuite.scala` first.
- [ ] T033 [US1] `S/player/Model.scala` and `S/player/PlayerRewards.scala`: `Target`, `Select`,
      `Deselect`, `DepositConfirmed`, `Claim`, `RewardChanged`, each evaluating with `Evaluator` and
      reserving the reward id in one event. `T/player/PlayerRewardsSuite.scala` first, including two
      selections at once.
- [ ] T034 [US1] `S/consumers/MoneyConsumer.scala` (`deposit_confirmed` → `PlayerRewards`; the reward
      families → `Reward` by `awardId`) and `S/consumers/IndexConsumer.scala`; `ConsumerTestKit`
      suites.
- [ ] T035 [US1] `S/api/OffersEndpoint.scala` (`/offers`, `Routes.operatorHolding` with the three
      roles): list, one, create (the provider's spin-value and game checks first, then `OfferBook`),
      edit, delete, `PUT /order`; audit records.
- [ ] T036 [US1] `S/api/RewardsEndpoint.scala` (`/rewards`): `state`, `select`, `deselect`; the
      award path: `Claimed` → `WalletStep(<reward>/award)` → the endpoint polls the step's state, as
      payouts' request route; the pending timer.
- [ ] T037 [US1] `T/it/DepositSuite.scala`: a confirming deposit activates the pending reward with the
      wallet's ordinal; below minimum stays pending with the reason; a deposit after the window
      expires it; an offer no longer live expires it; the same deposit twice awards once.

**Checkpoint**: US1's scenarios green.

## Phase 4: US2, eligibility is decided once, with the same reasons on every path (P1)

**Independent test**: `eligibility.feature` through every path.

- [ ] T038 [US2] Bind `features/rewards/eligibility.feature` in `T/features/EligibilityFeatures.scala`.
- [ ] T039 [US2] `S/consumers/PlayersConsumer.scala`: research R9's families into `PlayerRewards`;
      the detection switch from `Players` at start and on each check fact. `ConsumerTestKit` suite.
- [ ] T040 [US2] The open count: `PlayerRewards`' open rewards and the wallet's active records from
      `GET W/restricted-funds` at evaluation (0 on failure); `T/it/ConcurrencySuite.scala`: one, two
      and no maximum; the wallet refusing `BONUS_ALREADY_ACTIVE` records the refusal and leaves the
      reward as it was.
- [ ] T041 [US2] `T/it/PathsSuite.scala`: each decline reason answered on select, code, deposit,
      the award route and the operator's award, in the shape each path answers; the operator's
      override applies rules 1–4 only.

**Checkpoint**: US2's scenarios green.

## Phase 5: US3, a code unlocks an offer (P1)

**Independent test**: `codes.feature` with `FakeProgramme`.

- [ ] T042 [US3] Bind `features/rewards/codes.feature` in `T/features/CodesFeatures.scala`.
- [ ] T043 [US3] `S/codes/CodeDocument.scala` (key value) and `S/codes/CodesTimer.scala`
      (`codes-<slug>`, every 5 minutes, inert with no URL; a failure keeps the last copy; staleness
      reported after 15 minutes) and `OffersEndpoint`'s `GET /code-document` (`{fetchedAt, entries,
      stale}`). Suite with `FakeProgramme`.
- [ ] T044 [US3] `RewardsEndpoint`: `POST /{playerId}/codes` (brand codes first, then the document;
      `invalid_code`; source `player_code` or `affiliate_code` with the tag) and
      `POST /{playerId}/codes/preview`; each entry publishes `bonus_code_entered` with its outcome.
      `OfferBook`'s create and edit refuse a code already in the document.
- [ ] T045 [US3] `T/it/CodesSuite.scala`: any case and spaces; a code in the document but not live;
      the document unreachable serving the last copy.

## Phase 6: US4, an active reward converts, completes, is switched, cancelled, revoked or expires (P1)

**Independent test**: `lifecycle.feature` with `FakeWallet` facts and the settable clock.

- [ ] T046 [US4] Bind `features/rewards/lifecycle.feature` in `T/features/LifecycleFeatures.scala`.
- [ ] T047 [US4] `MoneyConsumer`: `restricted_funds_converted` → `Converted` (cap timer);
      `restricted_funds_forfeited` reason `loss` → `Forfeited bonus_exhausted`, `adjustment` →
      `Forfeited admin_adjust_emptied`; `restricted_funds_closed` → `Closed` with its reason. Suite
      cases.
- [ ] T048 [US4] The timers `claim-`, `wagering-`, `cap-`, `pending-<reward>` (research R10) wired to
      `WalletStep` `forfeit` and `close-cap`; `T/it/ClockSuite.scala` (SC-002): each fires within a
      minute and is a no-op on a terminal reward.
- [ ] T049 [US4] `RewardsEndpoint`: `POST /{playerId}/cancel` (`grant_not_found`, `no_active_bonus`,
      the wallet's cancel through `WalletStep`, `{status: "canceled", clawbackAmount, newRealBalance}`),
      `POST /{playerId}/switch` (`grantRef_required`, `switch_failed`), `GET /{playerId}/history`.
- [ ] T050 [US4] `S/api/RewardAdminEndpoint.scala` (`/reward-grants`): `POST /{rewardId}/revoke`
      (`INVALID_MODE`, `CLAIM_NOT_FOUND` including another brand's, `NO_ACTIVE_BONUS`; `forfeit` →
      `WalletStep forfeit`, `reclaim` → `WalletStep cancel` with `requireBaseline`, `MISSING_BASELINE`);
      the reason `Personal` in the audit. `T/it/RevokeSuite.scala`.
- [ ] T051 [US4] `T/it/RepeatSuite.scala` (SC-003): every wallet command, fact and timer delivered
      twice, and a command repeated after 1,000 other keys at `FakeWallet`: one award, forfeit and
      clawback per reward.

## Phase 7: US5, free rounds are granted at the provider and settled from play (P2)

**Independent test**: `free-rounds.feature` with `FakeGameProvider` and `casino.rounds` facts.

- [ ] T052 [US5] Bind `features/rewards/free-rounds.feature` in `T/features/FreeRoundsFeatures.scala`.
- [ ] T053 [US5] `S/steps/FreeRounds.scala`: research R8's steps (known, create, poll 30 s × 50,
      cancel); a create never resent after `active` or `processing`. `T/it/FreeRoundsSuite.scala`
      (SC-005): confirmed; processing then active; rejected; 50 polls then `CANCELLED provider_failed`
      with no wallet command; a restart mid-poll resumes.
- [ ] T054 [US5] The grant path: the spin value for the player's currency (`currency_not_configured`,
      `player_currency_unresolved`), G1's game check (`game_not_eligible`), the spin-value check
      (refused only on a known mismatch), then `FreeRounds`; the route answers after `create`.
- [ ] T055 [US5] `S/consumers/RoundsConsumer.scala`: `round_settled` with `freeRoundsGrant` →
      `Reward.RoundSettled`; an unknown reward → `PlayerRewards.RoundUnmatched`, shown in
      `GET /reward-grants?playerId=` as `unmatchedRounds`. Suite.
- [ ] T056 [US5] Finalisation: on the last round and at `rounds-<reward>`, `Settlement` decides;
      `WalletStep credit` (provider credit) or `winnings` (award at `min(open) − 1`, 30-day expiry);
      nothing won calls nothing; a late round paid under `late-<round>`. `T/it/SettlementItSuite.scala`
      covers both destinations with and without an active reward.
- [ ] T057 [US5] `RewardsEndpoint`: `GET /{playerId}/free-rounds` and
      `GET /{playerId}/free-rounds/eligible` (`withAcl(Routes.serviceOf("game-provider"))`,
      `no_eligible_grant`); `OffersEndpoint`: `GET /free-rounds/spin-values` (`not_configured` as an
      empty list, 502 `provider_unavailable`).
- [ ] T058 [US5] Revoke of free rounds in `RewardAdminEndpoint`: `FreeRounds.cancel` first,
      `FREE_SPINS_FINISHED`, `PROVIDER_UNAVAILABLE`; accumulated winnings forfeited. Suite cases in
      `RevokeSuite`.

## Phase 8: US6, offers are targeted, listed and ordered (P2)

**Independent test**: the US6 scenarios of `offers.feature` and the campaign routes.

- [ ] T059 [US6] `S/api/CampaignEndpoint.scala` (`/reward-campaigns`, `Routes.serviceOf("engagement")`):
      `POST /{playerId}/target` (by role; `free_spins_offer_not_targetable` for both free-rounds types,
      `role_not_found`; idempotent) and `POST /{playerId}/award` (role re-resolution once,
      `alreadyProcessed`, `offer_not_found`, `offer_not_active`, `{error: <reason>}`, 503 for
      `ELIGIBILITY_CHECK_UNAVAILABLE`, the deposit from `PlayerRewards`' last deposit, the free-rounds
      answers). `T/it/CampaignSuite.scala`.
- [ ] T060 [US6] `RewardAdminEndpoint`: `GET /` (any operator of the brand; `search`, `status`,
      `offerId`, `playerId`, `page`, `pageSize`), `GET /{rewardId}`, and `POST /{playerId}` in its
      three shapes (offer with optional deposit, free bonus with no offer, free rounds with no offer).
      `T/it/OperatorSuite.scala`.
- [ ] T061 [US6] `RewardsEndpoint`: the `available` list in the brand's order, with the reason each
      offer is visible; `GET /{playerId}/grants` (`withAcl` adding `Routes.serviceOf("support-agent")`).

## Phase 9: US7, every change is a fact (P2)

- [ ] T062 [US7] Bind `features/rewards/facts.feature` in `T/features/FactsFeatures.scala`.
- [ ] T063 [US7] `T/it/PublishersSuite.scala` (SC-004): one grant driven through its life; each
      transition once on `casino.rewards` with source, code and tag; a transition applied twice
      publishes one fact; `attribution`'s `RealMoney` over the same `money.transactions` facts
      computes the bonus cost (a case in `services/attribution`'s suite).
- [ ] T064 [US7] `T/it/LeakSuite.scala`: no email, address or revoke reason on a fact, log line,
      span or timer name; `T/it/OutboundSuite.scala` (SC-006): only `wallet`, `game-provider`,
      `players` and the programme's URL are called.

## Phase 10: The recordings and parity

- [ ] T065 `tests/original/rewards-driver/`: one scenario per feature scenario the suite does not reach
      (research R2: the #730 gates, the operator's awards with no offer, revoke of free rounds, spin
      values), on the original's routes, in the shape of `tests/original/payouts-driver/`.
- [ ] T066 Record (needs the user: the original's kind stack and credentials): the spec's
      *Recordings* twice on fresh ids with `e2e-recording.patch`, scenario 23 through
      `just test-e2e-cadence`, and the driver; merge into `tests/port/rewards/`, moving recordings filed
      under other services; `just modules recordings check` green.
- [ ] T067 `T/port/WireTranslation.scala` (contracts/wire.md) and `T/port/ParitySuite.scala`: one test
      per recording; each marked divergence named with plan.md's *Divergences* row.

## Phase 11: Polish and cross-cutting

- [ ] T068 [P] `services/rewards/README.md`: components, suites, local run; `modules/casino/module.json`'s
      `rewards` entry checked against the service (`just modules render goldvibe`).
- [ ] T069 [P] `specs/001-brands-and-projects/contracts/routes.md` and `contract/access/routes.md`:
      the rewards rows (four prefixes and their audiences).
- [ ] T070 `just features` 0 findings; `just test`, `just rewards-test`, `just rewards-it`,
      `just wallet-test`, `just service players test`, `just service game-provider test` green.
- [ ] T071 quickstart.md §4 run locally and §6 on kind (`--context kind-…` only): one deposit match
      and one free-rounds grant against the simulator end to end; `casino.rewards` read.

## Dependencies & Execution Order

- Phase 1 → Phase 2 → stories. T001 before every other task that reads the contract; T007–T011 before
  T027; T012 before T054; T013–T016 before T039.
- US1 (Phase 3) before US2–US7: they use `OfferBook`, `PlayerRewards` and the award path.
- US2, US3, US4 in any order after US1; US5 after US4 (revoke, timers); US6 after US2 (the award
  route evaluates); US7 after US4 and US5 (every transition exists).
- Phase 10's recording (T066) needs the user and may run beside Phases 3–9; T067 after them.

## Parallel examples

- Phase 2: T012 beside T007–T011 (different services); T019, T020, T021, T022, T023, T024 together.
- Phase 3 onward: each story's feature binding beside its first component.

## Implementation strategy

MVP is Phases 1–3: a deposit match from creation to restricted funds, with the neighbours amended.
Then US2 and US4 (where money is kept or taken), US3, US5, US6, US7, and parity last, once the
recordings exist.
