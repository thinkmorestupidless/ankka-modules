# Tasks: engagement

**Input**: [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md); the spec's seven stories, the seven
`features/engagement/*.feature` files, the step added to the raise scenario of
`features/responsible-gambling/limits.feature` and the step added to the consents scenario of
`features/players/registration.feature`.

**Tests**: required. The constitution makes the recordings the acceptance test (I), the success
criteria name suites, and the plan names them. Pure suites, feature bindings and parity tests are
written before the components they test and fail first (ankka-port §7); the original's unit suites
are ported case by case (research R13).

Paths:

- `S` = `services/engagement/src/main/scala/io/eitheror/casino/engagement/`;
- `T` = `services/engagement/src/test/scala/io/eitheror/casino/engagement/`;
- `O` = `~/Documents/projects/eitheror/eitheror-platform` (the original), `G` =
  `O/tl/runtime/capabilities/customerio_dispatch/`, `CW` = the original's
  `CustomerIOWebhookRoutes.scala`.

Build with `CASINO_VERSION=0.2.0-014-SNAPSHOT` (memory: contract snapshot collisions). kubectl only
with `--context kind-…`. The affiliates work in `.claude/worktrees/013-affiliate-programme` is
another session's: do not touch it.

## Phase 1: Setup

- [X] T001 The contract addition (contracts/facts.md; research R6 P4): in
      `contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala` the family
      `rg_limit_loosening_requested(playerId, limitType, period: Option, value: Option[Long],
      currency: Option, appliesAt, occurredAt)`, registered in the topic; the family added to
      `specs/001-brands-and-projects/contracts/topics.md`; then `just schemas` (the diff shows one new
      family), `just test` green, `CASINO_VERSION=0.2.0-014-SNAPSHOT sbt -batch contract/publishLocal`.
- [X] T002 Remove `push-provider-key` (research R6 P5): the `PUSH_PROVIDER_KEY` env entry and the
      `push-provider-key` key from `modules/engagement/module.json`; the row from `contract/keys.md`;
      `tools/modules/src/test/scala/io/eitheror/casino/modules/RenderSuite.scala` (`"engagement" ->
      Set()` at line 49; the `missing` set at line 167 loses the engagement pair) and
      `ValuesSuite.scala:79` (use `model-provider-key` as the uncovered secret). `just test` green;
      `just modules render goldvibe` shows `engagement` with no secret.
- [X] T003 Run `ankka init` for the service at `services/engagement/`, in the shape of
      `services/rewards/`: `build.sbt` on `casino-contract` and ankka 0.16.0 (research, *Found during implementation*), Scala 3.9.0, munit,
      munit-scalacheck, postgresql, test `-Dcasino.root` and `CASINO_BRAND=goldvibe`, fork,
      `dockerExposedPorts := Seq(9000)`, the `schema` task; `service.json` (`engagement`, runtime
      0.16.0); `docker-compose.yml` (Postgres on 5442); `src/main/resources/application.conf`
      (`ankka.service.name = engagement`, `ankka.service-client.timeout = 2s`); `README.md`
      (components, suites, local run).
- [X] T004 Remove the `engagement` row from `contract/keys.md`'s "Not yet built" table in the same
      commit as `services/engagement/build.sbt`; `ModuleManifestSuite`'s built set gains `engagement`;
      `just test` green.
- [X] T005 [P] `Justfile`: `engagement-test`, `engagement-it`, `engagement-run brand="goldvibe"` (HTTP
      9013, Postgres 5442, both issuers, `CRM_TRACK_URL`/`CRM_APP_URL` at the stub,
      `-Dankka.local-services.players=http://127.0.0.1:9003
      -Dankka.local-services.rewards=http://127.0.0.1:9008`), as `rewards-run`.
- [X] T006 [P] `.github/workflows/ci.yml`: an `engagement` paths filter (`*build`,
      `services/engagement/**`, `features/engagement/**`, `tests/port/engagement/**`,
      `tests/stubs/crm-stub/**`) and a job as rewards', with a `-Dit=true` step; `crm-stub` built in
      the stubs job beside `psp-stub`.
- [X] T007 [P] `tests/stubs/crm-stub/` (Go, the `stubhttp` recorder of `tests/stubs/psp-stub`; research
      R2): two listeners (18201 Track, 18202 App) answering `POST /api/v2/entity` and `POST
      /v1/send/email` with 200, checking basic `siteId:trackKey` and bearer `appKey` against its
      flags; `GET /__received` listing every call with headers and body in order; `POST /__control`
      scripting 429, a 5xx or a hang for the next *n* calls; `go test ./...` green; a `README.md`.

## Phase 2: Foundational

**Neighbours** (research R6). Each with its suite written first.

- [X] T008 `services/responsible-gambling/` (R6 P1): produce `rg_limit_loosening_requested` when a
      raise or a removal is staged (the pending change), from the limits publisher, keyed by the
      player; a suite case in the publisher's suite; the step added to the raise scenario of
      `features/responsible-gambling/limits.feature` bound in the service's `GherkinSuite`;
      `just service responsible-gambling test` green.
- [X] T009 `services/players/` (R6 P2): `RegistrationRoutes.scala` (line ~157) produces
      `player_consent_changed` for `email` and `sms` at registration, source `registration`, beside
      `player_registered`; suite cases; the step added to the consents scenario of
      `features/players/registration.feature` bound; `just service players test` green.
- [X] T010 `services/players/` (R6 P3): `clients/Neighbours.scala` (lines 46-66) treats `refused` with
      its reason as a terminal answer like `suppressed`; `RegistrationWorkflow.scala` (322-363)
      records it and does not retry; suite cases.
- [X] T011 [P] `contract/access/routes.md`: the rows for `POST
      /v1/casino/brands/{brand}/engagement/sends` (service, players → engagement), `POST
      …/engagement/erasure/players/{player}` (players → engagement, so the modules tool fills
      `ERASURE_TARGETS`) and the reverse row for `POST …/players/{player}/messages` (engagement →
      players); `just modules render goldvibe` shows the grants.

**The service** (plan.md's structure; data-model.md).

- [X] T012 `S/Settings.scala` (`fromEnvironment: Either`: `CRM_TRACK_URL`, `CRM_APP_URL` overrides,
      `CRM_TIMEOUT` 10 s, `STALENESS_HOURS` 24, `MAX_CONNECTIONS` 10000, the instance id, the two
      issuers), `S/EngagementCodecs.scala` (manifests `engagement.<name>` for every stored type),
      `S/EngagementService.scala` (descriptors, endpoints, `started` registering the timers, `@main`
      with `TimerRuntime`, `ProjectionRuntime.fromEnv` else `InMemoryBroker`, `HttpServer.of(…
      Oidc.authenticate())`), as `RewardsService`. `T/SettingsSuite.scala`.
- [X] T013 [P] `S/events/Events.scala` (fact → events and data, contracts/facts.md's table in
      `handler.go`'s candidate order; the auto-login rule; `payment_failed`'s normalisation from
      `G/payment_failed.go`; `bonus_name` the offer id), `S/events/Classes.scala` (FR-011's two
      classes), `S/events/Amounts.scala` (minor → major, two decimals, `HALF_UP`).
      `T/events/EventsSuite.scala` first: every row of facts.md; `G/handler_test.go`'s candidate and
      single-mode cases and `G/payment_failed_test.go` ported case by case, the freshness cases marked
      as replaced by the claim; `T/events/ClassesSuite.scala`: every trigger of FR-006 and FR-014 in
      exactly one class.
- [X] T014 [P] `S/profile/Attributes.scala` (`Attributes.of(profile)`: FR-008 exactly as
      `buildIdentifyAttributes`, the seven always sent, the numeric nine with the string fallback,
      `player_name`'s fallback, `rg_limit_types`'s form), `S/profile/Suppression.scala` (research
      R14: per channel, the reason order), `S/profile/DepositDay.scala` (the day beginning at 03:00
      UTC; the next 03:05). `T/profile/AttributesSuite.scala` (`handler_test.go`'s encoding cases),
      `T/profile/SuppressionSuite.scala`, `T/profile/DepositDaySuite.scala`
      (`G/daily_deposit_count_test.go`) first.
- [X] T015 [P] `S/api/Signature.scala` (FR-017's verification in order; `CW:895-971`),
      `S/config/Masking.scala` (first three characters and asterisks; a masked value preserves),
      `S/config/Seed.scala` (the thirty-two `CrmEvent` keys with the original's `seedAttributes` and
      `seedData`), `S/channel/Frames.scala` (the SSE frame texts of contracts/routes.md).
      `T/api/SignatureSuite.scala` (`CustomerIOWebhookRoutesSpec`'s verification cases),
      `T/config/MaskingSuite.scala`, `T/config/SeedSuite.scala`, `T/channel/FramesSuite.scala` first.
- [X] T016 [P] `S/profile/Model.scala` and `S/profile/Profile.scala` (key value entity, id = player):
      data-model.md's state, `apply(fact)` per family with the per-field time, `resetDepositDay`,
      `erase`, `get`, `attributes`; every *P* field `Personal`. `T/profile/ProfileSuite.scala`
      (`KeyValueEntityTestKit`) first: every family, a repeat fact changes nothing, erase.
- [X] T017 [P] `S/config/Model.scala`, `S/config/Configuration.scala` (key value, id = slug: `put`
      answering the changed keys, `get`), `S/config/Secrets.scala` (the three names under
      `crm/<slug>/…`, read through `clients.secrets`, cached 30 s per instance).
      `T/config/ConfigurationSuite.scala` first.
- [ ] T018 [P] `S/clients/Crm.scala` (trait: `identify`, `track`, `send`, `delete`, answering
      `Accepted | Retryable(status) | Refused(status, body)`; `CustomerIo` over the two hosts by
      region with the overrides, basic and bearer, the timeout), `S/clients/Players.scala` (the
      message route), `S/clients/Rewards.scala` (target, award), each passing the neighbour's answer
      through as contracts/routes.md's *Calls out*.
- [ ] T019 [P] `T/it/FakeCrm.scala` (both hosts; every call recorded; scriptable 429, 5xx, hang, and a
      per-call refusal), `T/it/FakePlayers.scala`, `T/it/FakeRewards.scala` (scriptable answers,
      `down`, a record of every call), `T/it/SseReader.scala` (`java.net.http` over a stream, frames
      parsed, a close observed).
- [ ] T020 `T/it/Kit.scala` and `SharedKit`: `AnkkaTestKit.start` over Postgres with `FakePlayers` and
      `FakeRewards` under `localServices`, `FakeCrm`'s URLs in `Settings`, `InMemoryBroker` for the
      six topics in and the two out, `TimerRuntime(200.millis)` with a `MovableClock`,
      `testKit.secrets`, callers `asPlayer`, `asOperator(roles)`, `asService(name)`, `publish(topic,
      key, fact)`, `facts(topic)`, `timerExists`, `fireTimer`, `restartService`, `eventually`, as
      rewards'.
- [ ] T021 `S/send/Model.scala`, `S/send/Attempts.scala` (500 ms doubling, ±25 %, four attempts) and
      `S/send/Send.scala` (workflow, id = the send key; research R4, R5): `start` as the claim with
      staleness, configuration and address judged, the steps `identify` → `track` → `transactional`
      each reading `Profile.attributes` first and the credentials through `Secrets`, the pauses, `parked`,
      `retry`, `discard`, `outcome`. `T/it/SendSuite.scala` first: every `FakeCrm` answer, four
      attempts then parked with the last answer, a 4xx refused without retry, a repeat `start`
      answering the first outcome, retry and discard from `parked` only, suppression and closure at
      the first attempt and at a retry, the identify of a withheld event still made, a key rotation
      between attempts, `restartService()` mid-attempt resuming.
- [ ] T022 `S/reads/SendsView.scala` (`stateOf(Send)`: `byPlayer`, `parked` with `search`, `counts(since)`,
      `oldestParked`) and `S/reads/ProfilesView.scala` (`stateOf(Profile)`: `dailyCountAbove(day)` paged
      by 500). `T/it/ViewsSuite.scala`.
- [ ] T023 `S/facts/AuditPublisher.scala` (`Send` states → `engagement.send.refused | retried |
      discarded`; `Configuration` changes → `engagement.config.changed`, keys named, no value) through
      `AuditCompanion`. `T/facts/AuditSuite.scala` first: no `Personal` value on a record.
- [ ] T024 `S/api/Answers.scala`: refusals as `{error, …}` with contracts/routes.md's statuses; the
      original's literal missing-field messages; a neighbour's answer passed through unchanged.

**Checkpoint**: neighbours amended and green; the pure packages, `Profile`, `Configuration`, `Send`,
the views and the audit publisher green under their suites.

## Phase 3: US1, a fact becomes one Customer.io event, exactly once (P1)

**Goal**: every fact family to its identify, track and transactional, once, with the dead letter.
**Independent test**: `tracks.feature` through the kit with `FakeCrm`.

- [ ] T025 [US1] Bind `features/engagement/tracks.feature` in `T/features/TracksFeatures.scala`
      (`GherkinSuite`); it fails first.
- [ ] T026 [US1] `S/consumers/MoneyConsumer.scala` (`money.transactions`: balances onto `Profile`,
      `deposit_confirmed` with `first_deposit_confirmed` when the lifetime count becomes 1, an
      identify-only `Send` for a movement with no event); `ConsumerTestKit` suite.
- [ ] T027 [US1] `S/consumers/PlayersConsumer.scala` (`Profile.apply`, `Events`, `Send.start` per
      event, the identify-only families of contracts/facts.md), `S/consumers/SessionsConsumer.scala`,
      `S/consumers/PaymentsConsumer.scala` (`payment.initiated` remembered; `payment.failed` |
      `expired` → `payment_failed/<paymentId>`), `S/consumers/WithdrawalsConsumer.scala`,
      `S/consumers/RewardsConsumer.scala`; a `ConsumerTestKit` suite each, the key of every `Send`
      asserted.
- [ ] T028 [US1] `S/api/BackofficeEndpoint.scala` (`/v1/casino/backoffice/<slug>`, `Routes.operator`),
      the `GET /engagement/status` route first (contracts/routes.md: configured, parked, lastHour,
      webhookRefusals, connections); the other routes come in US7.
- [ ] T029 [US1] `T/it/ReplaySuite.scala` (SC-002: a year of facts delivered twice into an empty
      journal; no call; every `Send` found or `skipped_stale`), `T/it/AttemptsSuite.scala` (the
      backoff under the test clock; 429, 5xx, a hang retried; 4xx not; parked with the last answer),
      `T/it/LatencySuite.scala` (SC-005: accepted within 60 s healthy and after one 503),
      `T/it/RecordsSuite.scala` (SC-008: `FakeCrm`'s accepted count equals the `accepted` rows; every
      refusal, parking and discard a record and an audit record).

**Checkpoint**: US1's scenarios green.

## Phase 4: US2, the profile Customer.io holds reflects the brand's facts (P1)

**Independent test**: `profiles.feature`; the identify calls read from `FakeCrm` by name and type.

- [ ] T030 [US2] Bind `features/engagement/profiles.feature` in `T/features/ProfilesFeatures.scala`;
      it fails first.
- [ ] T031 [US2] `S/timers/DepositDayTimer.scala` (timed action `reset(day)`: pages
      `ProfilesView.dailyCountAbove`, `Profile.resetDepositDay`, `Send.start(deposit-day/<day>/<player>,
      identify)`) and its registration in `EngagementService.started` as the recurring
      `deposit-day-<slug>`, first due at the next 03:05 UTC, idempotent at every start.
      `T/it/DepositDaySuite.scala`: the clock moved past 03:05 identifies the players above zero with
      an earlier stamp and no other; a second firing identifies no one.
- [ ] T032 [US2] `T/it/IdentifySuite.scala`: the identify before any track over a registration, a
      login, a deposit, a bet, an RG limit and a consent withdrawal, every attribute by name and type
      against FR-008; a tier change identifies and tracks nothing; the balance attributes absent
      before any movement.

**Checkpoint**: US2's scenarios green.

## Phase 5: US3, a suppressed player gets no marketing and still gets service messages (P1)

**Independent test**: `suppression.feature`; `SuppressionSuite` over FR-011's list.

- [ ] T033 [US3] Bind `features/engagement/suppression.feature` in
      `T/features/SuppressionFeatures.scala`; it fails first.
- [ ] T034 [US3] `T/it/SuppressionSuite.scala` (SC-003): for each trigger of FR-011's two lists and
      each suppression cause (an open case of either type, consent withdrawn, no consent fact, the
      operator's flag, a closed account), the marketing class refused with its reason, untracked, the
      identify carrying the four attributes, the audit record written; the service class sent; at the
      first attempt and at an operator's retry of a parked send; `rg_case_closed` and
      `player_reopened` lifting it.

**Checkpoint**: US3's scenarios green.

## Phase 6: US4, `players` sends a verification email through this service (P1)

**Independent test**: `transactional.feature` through the send route as `players`.

- [ ] T035 [US4] Bind `features/engagement/transactional.feature` in
      `T/features/TransactionalFeatures.scala`; it fails first.
- [ ] T036 [US4] `S/api/SendEndpoint.scala` (`/v1/casino/brands/<slug>/engagement`, `Routes.service()`):
      `POST /sends` (contracts/routes.md: the seven triggers, the `EmailAddressVerification` fallback
      id, `Send.start(<trigger>/<key>)`, the answer within 2 s from the first attempt's outcome,
      `missing_fields`, `unknown_trigger`); the links never logged above debug.
      `T/it/SendRouteSuite.scala`: one send per key, the fallback, `not_configured`, `suppressed`,
      `refused`, a caller that is not the brand's refused.
- [ ] T037 [US4] The erasure route `POST /erasure/players/{player}` (`Routes.playersOnly`; research
      R15): a `Send` of trigger `erasure` making the CRM's `delete`, every open `Send` of the subject
      discarded, `Profile.erase`, the answer's certificate. `T/it/ErasureSuite.scala` (SC-007: the
      `delete` at `FakeCrm`; a replayed fact silent) and `T/it/DumpSuite.scala` (SC-006: `pg_dump`
      holds no readable address, name, date of birth or message text).

**Checkpoint**: US4's scenarios green; `players`' registration against this service's route end to
end in `just players-run` + `just engagement-run`.

## Phase 7: US5, a Customer.io campaign acts on a player through the webhook (P2)

**Independent test**: `campaigns.feature` with `FakeRewards` and `FakePlayers`.

- [ ] T038 [US5] Bind `features/engagement/campaigns.feature` in `T/features/CampaignsFeatures.scala`;
      it fails first.
- [ ] T039 [US5] `S/api/WebhookEndpoint.scala` (`/v1/casino/customerio`, `Routes.public`): `POST
      /webhook/{brand}` with `Signature`'s refusals in order and the body read within 5 s, `wrong_brand`,
      `invalid_body`, `unknown_action`, the three actions relayed through `clients.Rewards` and
      `clients.Players` with each answer passed through (contracts/routes.md's action table), the
      missing-field 422s, `service_unavailable`, every refusal counted for status.
      `T/it/WebhookSuite.scala`: each refusal; each action's answers scripted at the fakes, including
      `alreadyProcessed`, `marketing_suppressed`, `account_closed`, `player_not_found`; `campaign_id`
      and `reward_id` dropped; the 21 and stacking recordings' webhook steps replayed.

**Checkpoint**: US5's scenarios green.

## Phase 8: US6, a player's open page learns the inbox changed within seconds (P2)

**Independent test**: `push.feature`; `ChannelSuite` across `restartService()`.

- [ ] T040 [US6] Bind `features/engagement/push.feature` in `T/features/PushFeatures.scala`; it fails
      first.
- [ ] T041 [US6] `S/channel/Model.scala`, `S/channel/Leases.scala` and `S/channel/Channel.scala` (event
      sourced, id = player; research R10): data-model.md's command table, the ring of 32, `Delivered`
      once per `created` signal when every connection open at the signal has acked or closed, snapshots
      every 100 events. `T/channel/ChannelSuite.scala` (`EventSourcedTestKit`) first: every row; 0→1
      and 1→0; a sweep expiring a lease older than 90 s; a cursor fallen out of the ring.
- [ ] T042 [US6] `S/api/StreamEndpoint.scala` (`/v1/stream`, `Routes.player(slug)`; `sse`): `open`, the
      1 s tick with `poll` and `acked`, the heartbeat and lease every 15 s and 30 s, the
      revalidation (`revalidation_failed`), the recycle (`connection_recycled`, judged at the 30 s
      sweep), `shutting_down` on stop, the 64-frame overflow drop, `unauthenticated`,
      `subject_unresolved`, `capacity`; `Frames` for every frame. If `sse("/")` is refused under
      `/v1/stream`, `sse("/{slug}")` and research R7's note in README.
- [ ] T043 [US6] `PlayersConsumer`: `player_message_created | read | deleted` → `Channel.signal`;
      `S/facts/PushPublisher.scala` (`Opened` on 0→1, `Closed | Expired` on 1→0 → `presence_changed`;
      `Delivered` → `push_delivered` with the bare message id); `S/timers/SweepTimer.scala`
      (`sweep-<player>` recurring 30 s while above zero, cancelled at zero). Suites:
      `T/facts/PushFactsSuite.scala` (no fact for `Leased`, `Acked`, `Signalled`, read or delete).
- [ ] T044 [US6] `T/it/StreamSuite.scala` (`ready`, the heartbeat, a `signal` after
      `player_message_created`, `closing` for each reason, the refusals, two devices converging on a
      read) and `T/it/ChannelSuite.scala` (SC-004: two connections, one signal each within 2 s, one
      `push_delivered` of 2, nothing for a player with no connection, across `restartService()`;
      leases expiring take the player offline with `presence_changed`).

**Checkpoint**: US6's scenarios green.

## Phase 9: US7, an operator sees what was sent, what failed, and who is online (P2)

**Independent test**: `operators.feature` as an operator with the brand's role.

- [ ] T045 [US7] Bind `features/engagement/operators.feature` in `T/features/OperatorsFeatures.scala`;
      it fails first.
- [ ] T046 [US7] `S/channel/AgentTicket.scala` (key value, id = ticket: issue, redeem once within
      30 s for the brand), `S/channel/AgentChannel.scala` (event sourced, id = slug: connections, the
      `PlayerOnline` ring, a 16-frame queue dropped on overflow); `BackofficeEndpoint`'s `POST
      /agent-stream/ticket` and `GET /agent-stream?ticket=` (`sse`, no bearer, `invalid_ticket`;
      `event: ping` every 15 s); `PlayersConsumer`'s `player_logged_in` → `AgentChannel.signal` with the
      profile's email prefix. `T/channel/AgentTicketSuite.scala`, `T/channel/AgentChannelSuite.scala`
      first; `T/it/AgentStreamSuite.scala` (`AgentStreamTicketStoreSpec`'s and
      `AgentStreamConsumerSpec`'s cases; `player_online` within 5 s; a ticket used twice).
- [ ] T047 [US7] `BackofficeEndpoint`: `GET /players/{player}/sends`, `GET /parked-sends`, `POST
      /parked-sends/{sendId}/retry`, `POST /parked-sends/{sendId}/discard`, `GET /presence`
      (contracts/routes.md), each through `SendsView`, `Send` and `Channel`; the audit records.
      `T/it/OperatorsSuite.scala`: the sends newest first and paged, a retry accepted leaving the
      parked list, a retry refused for suppression (US3's case at the route), a discard with its
      reason, `not_parked`, `send_not_found`, presence with `since`.
- [ ] T048 [US7] `S/api/ConfigEndpoint.scala` (`/v1/casino/crm-config`, `Routes.operatorHolding` with
      `brand_admin`, `resource_admin`, `platform_admin`): `GET /{brand}` masked, `PUT /{brand}` writing
      the secrets to `clients.secrets` and the rest to `Configuration`, a masked value preserving,
      `invalid_config`, the audit record; `POST /{brand}/seed` (thirty pairs at `_seed_<slug>`,
      `credentials_not_configured`, `crm_unavailable`, `{seeded, person}`).
      `T/it/ConfigSuite.scala`: the round trip, `testKit.secrets` holding the values and the entity
      none, the seed's thirty-two pairs at `FakeCrm`, a second seed at the same person.

**Checkpoint**: US7's scenarios green; every scenario of `features/engagement/` bound.

## Phase 10: The recordings and parity

- [ ] T049 The recording wiring (research R2): `tests/original/` gains the `crm-stub` in the compose
      override with `CUSTOMERIO_TRACK_URL`/`CUSTOMERIO_APP_URL` pointed at it, a step in
      `e2e-recording.patch` that seeds the recording brand's `crm-config` with the stub's keys and a
      transactional id for every trigger, `preflight` refusing to start without the stub (FR-029),
      and each recording's `captures` gaining the stub's `__received` entries; `tests/original/README.md`
      updated.
- [ ] T050 [P] `tests/original/engagement-driver/`: one scenario per feature scenario the suite does
      not reach (research R2: the configuration routes with masking and the seed, the webhook's four
      refusals and its `send_message` and `target_offer` paths, the daily reset route, the agent-stream
      ticket and stream, the player stream's frames, `GET /v1/presence`), on the original's routes,
      in the shape of `tests/original/rewards-driver/`.
- [ ] T051 Record (needs the user: the original's kind stack with the stub, operator credentials for
      the free-spins scenario, the brand configured against the stub): the spec's *Recordings* twice on
      fresh ids with `e2e-recording.patch` and the driver; merge into `tests/port/engagement/`; the
      webhook steps of scenarios 21 and stacking stay under `tests/port/rewards/`; `just modules
      recordings check` green.
- [ ] T052 `T/port/WireTranslation.scala` (contracts/wire.md) and `T/port/ParitySuite.scala`: one test
      per recording, the CRM calls compared by event name, attribute names and types and data keys in
      order per player; each marked divergence named with plan.md's *Divergences* row.

## Phase 11: Polish and cross-cutting

- [ ] T053 [P] `services/engagement/README.md`: components, suites, local run, the `/v1/stream` note;
      `modules/engagement/module.json` checked against the service (`just modules render goldvibe`).
- [ ] T054 [P] `specs/001-brands-and-projects/contracts/routes.md`: the engagement rows (five prefixes
      and their audiences); `contract/access/routes.md` re-checked against T011.
- [ ] T055 `just features` 0 findings; `just test`, `just engagement-test`, `just engagement-it`, `just
      service players test`, `just service responsible-gambling test` green.
- [ ] T056 quickstart.md §5 run locally and §6 on kind (`--context kind-…` only; three instances):
      one deposit end to end at the stub, the stream from two devices with one signal each and one
      `push_delivered` of 2, the seventeen Playwright suites with the base URLs pointed at the brand
      (SC-004, SC-009).

## Dependencies & Execution Order

- Phase 1 → Phase 2 → stories. T001 before every task that reads the contract (T008 onward); T002 and
  T004 before T053; T007 before T049.
- T012 before T016–T024; T013–T015 before T021; T016–T020 before T021; T021 before T022–T024 and
  every story.
- US1 (Phase 3) before US2–US7: they use the consumers and `Send`. US2, US3 and US4 in any order
  after US1; US5 after US4 (`Answers`, the clients' shapes); US6 after US1; US7 after US3 and US6
  (retry under suppression; presence).
- Phase 10's recording (T051) needs the user and may run beside Phases 3–9 once T007, T049 and T050
  exist; T052 after every story.

## Parallel examples

- Phase 1: T005, T006, T007 together after T003.
- Phase 2: T008, T009 and T010 beside T011 (different services); T013, T014, T015, T016, T017, T018
  and T019 together after T012.
- Phase 3 onward: each story's feature binding beside its first component; US2, US3 and US4 in
  three parallel runs after US1.

## Implementation strategy

MVP is Phases 1–3: every fact family to its Customer.io calls, once, with the dead letter and the
status route, the neighbours amended. Then US2 and US3 (the profile and suppression, which
campaigns depend on), US4 (so `players`' registration completes against this service), US5, US6,
US7, and parity last, once the recordings exist.
