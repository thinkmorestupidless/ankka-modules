# Tasks: payouts

**Input**: [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md); the spec's six stories and the five
`features/payouts/*.feature` files.

**Tests**: required. The constitution makes the recordings the acceptance test (I), the success
criteria name suites, and the plan names them. Parity tests and feature bindings are written before the
components they test and fail first (ankka-port §7).

Paths:

- `S` = `services/payouts/src/main/scala/io/eitheror/casino/payouts/`;
- `T` = `services/payouts/src/test/scala/io/eitheror/casino/payouts/`.

Build with `CASINO_VERSION=0.2.0-007-SNAPSHOT`.

## Phase 1: Setup

- [X] T001 The contract additions (contracts/facts.md; research R7, R11), every field `Option` with a
      default of `None`:
      - in `contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala`:
        `PlayerRegistered.email: Option[Personal[String]]`, and a new family
        `PlayerEmailChanged(playerId, email: Personal[String], occurredAt)` named `player_email_changed`,
        registered in the topic's families;
      - in `contract/src/main/scala/io/eitheror/casino/contract/facts/Others.scala`: `gateway` on all six
        `casino.withdrawals` families, `terminal` on the five that lack it, `providerReference` on
        completed, failed and reversed, and `reason` on cancelled;
      - then `just schemas` (the diff shows optional fields and one family only), `sbt contract/test` green,
        and `sbt contract/publishLocal`.
- [X] T002 `players`' publisher in `services/players/src/main/scala/io/eitheror/casino/players/facts/Facts.scala`:
      `player_registered.email` from `Registered.address`, and `player_email_changed` from
      `EmailPromoted` and `EmailChanged`, each `Personal`. Add cases to its `FactsSuite`, and the new
      family to `specs/001-brands-and-projects/contracts/topics.md`'s `casino.players` row. Then
      `just service players test` green.
- [X] T003 [P] Add `brand_finance` to `brands/_realm/eitheror-operators.json` beside `brand_admin`
      (research R6). Correct `specs/001-brands-and-projects/contracts/grants.md:24` to the three payout
      routes `payments` grants (initiate, status, the terminal listing). Then `just test` green.
- [X] T004 Run `ankka init` for the service at `services/payouts/`, in the shape of `services/kyc/`:
      - `build.sbt` on `casino-contract` and ankka 0.13.0;
      - `service.json`;
      - `docker-compose.yml` (Postgres on 5439);
      - `src/main/resources/application.conf`.
      Move the `payouts` row of `contract/keys.md` out of "Not yet built" (`ModuleManifestSuite` enforces
      it once `services/payouts/build.sbt` exists). Check that the core module's `payouts` entry
      (`modules/core/module.json:317-381`) needs no change.
- [X] T005 [P] `S/Settings.scala` (brand, project and issuers from the env the core module renders; the
      wallet's and the gateway's addresses) and `S/PayoutsCodecs.scala` (the stored-type serializer helper,
      as `KycCodecs`).
- [X] T006 [P] In the `justfile`: `payouts-test`, `payouts-it` and `payouts-run` (port 9004). In
      `.github/workflows/ci.yml`: the service's filter (`services/payouts/**`, `features/payouts/**`,
      `tests/port/payouts/**`), its output and its job.

## Phase 2: Foundational

- [X] T007 [P] `S/request/Gates.scala` and `S/request/Refusals.scala` (pure: research R4's table in its
      order, each refusal's code, status and fields as in contracts/routes.md, and the KYC messages
      verbatim), with `T/request/GatesSuite.scala`: every row; when two apply, the earlier wins; zero, a
      negative or a decimal amount is 422 `INVALID_AMOUNT`. Write the suite first, and see it fail first.
- [X] T008 [P] `S/limits/Limits.scala` (pure: `used + amount > limit`, the rolling seven days, the UTC
      calendar month, the counting statuses; FR-005b), with `T/limits/LimitsSuite.scala`:
      `WithdrawalLimitChecker`'s cases per currency, each window at its edges, and zero or none as no
      limit.
- [X] T009 [P] `S/terminals/Eligibility.scala` (pure: research R5's rule and ranking), with
      `T/terminals/EligibilitySuite.scala`: `WithdrawalTerminalEligibility`'s cases, an unrecorded scheme
      counted as not VISA, and 1,000 reads in one order (SC-007).
- [X] T010 [P] `S/player/Iban.scala` and `S/player/Names.scala` (pure: research R13), with their suites:
      `IbanValidator`'s and `PayoutDestinationRoutesSpec`'s cases; diacritics, case and spacing normalised.
- [X] T011 [P] `S/workflow/Approval.scala` (pure: automated or manual, and the gateway's fields per
      provider, from `resolveApprovalOutcome`; research R7), with `T/workflow/ApprovalSuite.scala`: each
      gateway, and each missing field leading to manual payout.
- [X] T012 `S/request/Model.scala`, `S/player/Model.scala`, `S/limits/Model.scala`: states, events,
      commands and refusals as data-model.md has them, `Personal` where it marks.
- [X] T013 `S/player/PlayerPayouts.scala`, with `T/player/PlayerPayoutsSuite.scala`
      (`EventSourcedTestKit`):
      - every row of data-model.md's table;
      - deposits deduplicated by ordinal;
      - requests older than 32 days dropped;
      - `Erase` blanks every `Personal` value and keeps the rest.
- [X] T014 `S/limits/WithdrawalLimits.scala`, with its kit: `Set`, its refusals, and `LimitsSet` carrying
      before and after.
- [X] T015 `S/request/WithdrawalRequest.scala`, with `T/request/WithdrawalRequestSuite.scala`
      (`EventSourcedTestKit`):
      - every row of data-model.md's table;
      - a second `Approve` or `Reject`, or a `Reject` after approval, is `REQUEST_NOT_PENDING` (R1);
      - a repeated command writes nothing;
      - `Erase` blanks the payee, the reasons and the notes.
- [X] T016 `S/terminals/TerminalCatalogue.scala` (key value) and `S/terminals/CatalogueTimer.scala`
      (`terminals-<slug>`, every 60 s, one currency at a time; a failed refresh keeps the last list), with
      their kits.
- [X] T017 [P] `S/clients/Wallet.scala` (trait + HTTP: preview, hold, release, confirm, reverse; keys
      `<id>/<step>`; refusals read as research R9 says) and `S/clients/Gateway.scala` (trait + HTTP:
      listing, initiate, status; every answer of research R7 and R8 decoded, an unreadable body as
      `Unreadable`), with `T/it/FakeWallet.scala` and `T/it/FakeGateway.scala` (each answer scriptable per
      request id, as spec 005's recordings prove the real one answers).
- [X] T018 `S/consumers/PlayersConsumer.scala`, `S/consumers/DepositsConsumer.scala` and
      `S/consumers/RequestsConsumer.scala`, with their `ConsumerTestKit` suites. A redelivery changes
      nothing, and a fact for a player with no record creates one.
- [X] T019 `S/facts/Facts.scala` and `S/facts/Publishers.scala` (`WithdrawalPublisher`, `AuditPublisher`,
      `ConfigAuditPublisher`; contracts/facts.md), with `T/facts/PublishersSuite.scala`:
      - each event to its family or to none;
      - a rejection as `withdrawal_cancelled` by an operator;
      - approval emitting `withdrawal_approved` alone;
      - `amountCents` as the paid amount;
      - never a `Personal` value or free text on a fact or an audit record.
- [X] T020 `S/PayoutsService.scala` (registration), `S/api/Answers.scala` (refusal bodies), and the
      integration kit `T/it/Kit.scala`:
      - Postgres, with `FakeWallet` and `FakeGateway` under `localServices`;
      - `InMemoryBroker` for `casino.players`, `money.transactions`, `casino.withdrawals` and
        `platform.audit`;
      - `TimerRuntime(200.millis)` and a settable clock;
      - player tokens, and operator tokens with roles;
      - the `payments`/`psp-gateway` identity.
- [X] T021 `T/port/WireTranslation.scala` (every row of contracts/wire.md, with minor units and the ids
      captured), `T/port/WireTranslationSuite.scala`, and `T/port/ParitySuite.scala` over
      `tests/port/payouts/`, which stays `.ignore`d until T054.

**Checkpoint**: the pure packages, the four entities, the clients and the plumbing pass their kits.

## Phase 3: US1, a verified player requests a withdrawal and the money is held (P1)

- [X] T022 [US1] Fail first (a saved destination is seeded on `PlayerPayouts` directly; saving one is US3's): bind the US1 scenarios of `features/payouts/requests.feature` (the request
      and its hold, already pending, the amount, destination required, crypto address and coin, a
      destination not the player's, insufficient funds) in `T/features/RequestsFeatures.scala`.
- [X] T023 [US1] `S/workflow/WithdrawalWorkflow.scala`, the request half: `start` as the atomic claim
      (`KEY_REUSED` on a different body), the `hold` step keyed by the request id, `open`, a pause for
      review, and `status`. A wallet refusal is recorded and ends the workflow. Transport failures are
      retried (R9). Test it with `T/workflow/WithdrawalWorkflowSuite.scala` (`WorkflowTestKit`).
- [X] T024 [US1] `S/api/PlayerEndpoint.scala` at `/v1/casino/brands/<slug>/players`:
      `POST /{playerId}/withdrawal-requests` (contracts/routes.md):
      - `player_id_mismatch`; `MISSING_KEY`;
      - the request id as a name-based UUID of `<player>/<key>`;
      - `Gates` over `PlayerPayouts`, `WithdrawalLimits`, `TerminalCatalogue` and the wallet's preview;
      - then `start`, polling `status` to a 2 s bound, then `503 SERVICE_UNAVAILABLE`;
      - on a hold, `PlayerPayouts.Requested` before the answer;
      - 201 with the forfeit fields.
- [X] T025 [US1] `PlayerEndpoint`: `GET /{playerId}/withdrawal-requests` (newest first, from
      `WithdrawalQueue.byPlayer`) and `DELETE /{playerId}/withdrawal-requests/{requestId}` through the
      workflow's `cancel` → `release` cancelled, refusing `WITHDRAWAL_REQUEST_NOT_CANCELLABLE`.
- [X] T026 [US1] `S/queue/WithdrawalQueue.scala` (the view of data-model.md, with no `Personal` column),
      with its suite: each query and filter, pages of 1–200, and `total`.
- [X] T027 [US1] `T/it/RequestSuite.scala`:
      - a request holds, opens and is listed in the account and the queue;
      - `withdrawal_requested` is published;
      - a repeat with the same key answers the first;
      - a crash between the hold and `Requested` is repaired by `RequestsConsumer`;
      - 1,000 requests, p99 under 2 s, with the preview and the hold the only calls out (SC-005).

## Phase 4: US2, a player is blocked by identity, turnover, a limit or a bonus (P1)

- [X] T028 [US2] Fail first: bind the US2 scenarios of `features/payouts/requests.feature` (identity, the
      weekly and monthly limits, what does not count, turnover, the forfeit and its confirmation, funds
      before forfeit) in `T/features/RequestsFeatures.scala`.
- [X] T029 [US2] `S/api/ConfigEndpoint.scala` at `/v1/casino/brands/<slug>/withdrawal-config`: `GET /` and
      `PUT /`, with the admin role check (`brand_admin`, `resource_admin` or `platform_admin`; any other
      role is `forbidden`). Its refusals are 400s naming the field.
- [X] T030 [US2] `T/it/GatesSuite.scala` drives the endpoint through each gate in R4's order:
      - the preview's 200 refusals and the hold's 409s, mapped to the original's statuses;
      - the hold's turnover refusal as 402;
      - a limit seen by the request made a second after the one before it (read-your-writes, R3);
      - each `LimitsSet` in `platform.audit` with the operator.

## Phase 5: US3, the player is offered the terminals they may withdraw to (P2)

- [X] T031 [US3] Fail first: bind `features/payouts/terminals.feature` in
      `T/features/TerminalsFeatures.scala`.
- [X] T032 [US3] `PlayerEndpoint`: `GET /{playerId}/withdrawal-terminals?currency=`. Eligibility is
      ranked from the catalogue, and the currency is the wallet's when absent. One rule serves the
      listing and the request (FR-010).
- [X] T033 [US3] `PlayerEndpoint`: `GET`, `POST /{playerId}/payout-destinations`,
      `PUT /{playerId}/payout-destinations/{id}` (`setAsDefault: true`) and `DELETE …/{id}`, with the refusals
      of contracts/routes.md. The IBAN is shown as its last four.
- [X] T034 [US3] `T/it/EligibilitySuite.scala`:
      - a cold catalogue reads the gateway once;
      - a refresh replaces one currency;
      - a terminal deactivated after the last refresh is offered, and refused by the gateway at
        initiation, which returns the money (R5);
      - the order is the same on 1,000 reads (SC-007).

## Phase 6: US4, an operator reviews the queue and approves or rejects (P1)

- [X] T035 [US4] Fail first: bind `features/payouts/review.feature` in `T/features/ReviewFeatures.scala`.
- [X] T036 [US4] `S/api/OperatorEndpoint.scala` at `/v1/casino/brands/<slug>/withdrawal-requests`, with the
      finance-or-admin check (R6):
      - `GET /` with every filter and `stale`, and `GET /{requestId}`;
      - `POST /{requestId}/approve` and `/reject` through the workflow's `approve` and `reject`;
      - `REQUEST_NOT_PENDING`;
      - rejection → `release` rejected.
- [X] T037 [US4] `PlayerEndpoint`: `GET /{playerId}/withdrawal-history`, with the operator ACL set by
      `withAcl`.
- [X] T038 [US4] `T/it/OperatorSuite.scala`:
      - each of the five roles is admitted, and any other refused;
      - another brand's operator is refused;
      - a second decision is refused;
      - each decision is in `platform.audit` with the operator, and the reason never in clear text.

## Phase 7: US5, the payout is executed, reconciled and settled by hand (P1)

- [X] T039 [US5] Fail first: bind `features/payouts/settlement.feature` in
      `T/features/SettlementFeatures.scala`.
- [X] T040 [US5] `WithdrawalWorkflow`, the payout half:
      - `pay` (`Approval`), `initiate` with every answer of R7's table, and `AwaitManual`;
      - `wait` and `poll` (30 minutes, then every 5; `no_record` → `needs-review`; the `reviewWindow` for a
        provider with no status to ask; R8);
      - `confirm` and `reverse`, each keyed `<id>/<step>`;
      - `pendingSettle` across the wallet's answer;
      - a completed workflow paused for a reversal.
      Test each step in `WithdrawalWorkflowSuite`.
- [X] T041 [US5] `S/api/OutcomeEndpoint.scala` at `/v1/casino/brands/<slug>/withdrawals`, with
      `POST /{requestId}/outcome` admitted by the grant (R6) and passed to the workflow's `outcome`:
      - `REQUEST_NOT_FOUND`;
      - `REFERENCE_MISMATCH`, recorded;
      - `ALREADY_RESOLVED`;
      - `WITHDRAWAL_NOT_IN_COMPLETED_STATE`;
      - a repeat is 200 and moves nothing.
- [X] T042 [US5] `OperatorEndpoint`: `POST /{requestId}/confirm-payout` and `/mark-payout-failed` (the
      default reason; `WITHDRAWAL_NOT_AWAITING_PAYOUT`; clears needs review).
- [X] T043 [US5] `T/it/PayoutFailureSuite.scala` (SC-002): `FakeGateway` broken at each answer (an
      unreachable gateway, 5xx, an unreadable body, `in_doubt`, `initiation_in_progress`) and at each
      wait. No request reaches awaiting manual payout but by approval on a manual terminal; `rejected`
      returns the money with `provider_rejected`.
- [X] T044 [US5] `T/it/DoublePaySuite.scala` (SC-003): `initiated`, then a timeout, then `completed` on
      the outcome route and the status read at once. The result is one completed request, one confirm at
      `FakeWallet`, and one `withdrawal_completed`.
- [X] T045 [US5] `T/it/OutcomeSuite.scala`: the gateway's identity is admitted, and any other caller and
      any player or operator token refused; an outcome on a request awaiting manual payout wins; a
      reversal of a completed request credits back once.
- [X] T046 [US5] `T/it/ScaleSuite.scala` (SC-004): 10,000 requests with 200 in flight; only the 200
      workflows wake on the clock's advance, and no component reads the whole set; FR-022's `stale`
      count.

## Phase 8: US6, reads (P2)

- [X] T047 [US6] Bind `features/payouts/reads.feature` in `T/features/ReadsFeatures.scala`.
- [ ] T048 [US6] `T/it/QueueSuite.scala`: each filter of FR-014 lists its requests with the right total,
      and the default is pending review.

## Phase 9: Personal data and erasure

- [ ] T049 `S/api/ServiceEndpoint.scala` at `/v1/casino/brands/<slug>/payouts`:
      `POST /erasure/players/{playerId}`, for `players` alone, erases `PlayerPayouts` and each of the player's
      requests. It is idempotent.
- [ ] T050 `T/it/ErasureSuite.scala` (SC-006): after erasure, every destination, address, account number,
      name, email and reason is unreadable on the request, the player's record and the queue, while every
      amount, status and time stays readable; the facts never carried them.
- [ ] T051 `T/it/LeakSuite.scala`: no `Personal` value in any log line, span, fact, audit record, view row
      or timer name at any time.

## Phase 10: The recordings and parity

- [ ] T052 Write `tests/original/payouts-driver` (scenario 30): one test per scenario of
      `features/payouts/` that the original can reach, in the shape of `tests/original/players-driver`,
      with the outcome route signed by the original's internal secret read from the recording worktree's
      environment and never written to a file here. Add scenario 251 to the run list.
- [ ] T053 The recording run (it needs the user; research R2, quickstart §3): the original on kind,
      psp-gateway on psp-stub, the operator credentials from the user; twice on fresh ids. The merge
      redacts the secret. Copy the recordings to `tests/port/payouts/`. Its `README.md` names the gates
      the driver could not reach, and `tests/original/README.md` names the run.
- [ ] T054 Un-ignore `ParitySuite`. Every recording replays through `WireTranslation`. Each marked
      divergence in `Parity.divergences` matches plan.md's *Divergences*. `CoverageSuite` names any route
      left unhit.

## Phase 11: Polish and cross-cutting

- [ ] T055 [P] `services/payouts/README.md`: purpose, routes by endpoint (with a pointer to
      contracts/routes.md), settings, the two calls out, the `just` recipes, and what is verified where.
- [ ] T056 [P] On `players`: add `payouts` to `ErasureWorkflow.targets`
      (`services/players/src/main/scala/io/eitheror/casino/players/erasure/ErasureWorkflow.scala`), and
      add a fake payouts erasure route to its suites (research R12). Then `just service players test`
      green.
- [ ] T057 Quickstart §6: the service run locally on 9004 beside `wallet-run` and `psp-gateway`'s local
      run; a request, a cancel and an approval driven by `curl` with real tokens from kind.
- [ ] T058 Quickstart §7 on `kind-ankka` (never production; always `--context kind-…`):
      - build and load the image, then run
        `just modules apply goldvibe --installation dev-kind --modules core --allow-unbuilt`;
      - a request held and approved;
      - the stub's outcome delivered through the core module's grant;
      - `withdrawal_completed` read by `attribution`.
- [ ] T059 Close research: *Findings* with dates; the cross-spec follow-ups (the `casino.players`
      readers' rebuild, the frontends, 012, 013) written; `just features` green; `just payouts-test` and
      `just payouts-it` green.

## Dependencies & Execution Order

- Setup (T001–T006) comes first. T001 comes before T002 and T004; T003 is independent.
- Foundational (T007–T021) comes before the stories.
- US1 → US2 (the gates' endpoint is US1's) → US4 (a review needs a request) → US5 (a payout needs an
  approval).
- US3 needs only the foundation. US6 comes after US4 and US5.
- Phase 9 comes after US5. T052 and T053 need the user and the original's kind stack; T054 comes after
  them. Nothing else waits on the recording run.
- T056 is independent of the service. T058 comes after T055–T057.

## Parallel examples

- Foundational: T007–T011 together (different files, all pure); T017 beside T012–T016.
- After the foundation: US1 (T022–T027) and US3 (T031–T034) together.
- In US5: T043–T046 together once T040–T042 are in.

## Implementation strategy

The MVP is Phases 1–7 (US1, US2, US4 and US5): a player requests, an operator decides, the gateway
pays, and the request settles itself. With it, a brand can take withdrawals end to end on kind. US3's
listing and destinations, US6's reads, erasure and the recordings follow, the last when the user can run
the original.
