# Research: engagement

Phase 0 of [plan.md](plan.md). The original is `~/Documents/projects/eitheror/eitheror-platform` on
`main`, read whole on 2026-11-03. Paths under it are written `O/…`; `G` is
`O/tl/runtime/capabilities/customerio_dispatch/`, `S` is
`O/substrate/casino-service/src/main/scala/io/eitheror/casino/`, `PG` is
`O/substrate/push-gateway/src/main/scala/io/eitheror/substrate/push/`, `CW` is
`S/CustomerIOWebhookRoutes.scala`. Paths in this repository are written from its root.

## R1. The original, read whole

**Decision**: port the behaviour of the two dispatch paths (`G/handler.go`, `G/cio_client.go`,
`G/payment_failed.go` and the five `notify-*` TL functions; `S/CrmService.scala`,
`S/CustomerIOClient.scala`, every `crmService.dispatch`, `send*` and `syncAttributes` call site),
`S/CustomerIOConfigRoutes.scala`, `S/DailyDepositCountReset.scala` and `S/DepositDay.scala`, `CW`,
`S/NotificationService.scala`, `S/PushEventConsumer.scala`, `S/PresenceView.scala`,
`S/AgentStreamConsumer.scala`, `S/AgentStreamRegistry.scala`, `S/BackofficeAgentStreamRoutes.scala`,
`S/AgentStreamTicketStore.scala` and `PG` in full. Correct the spec wherever it said something the
original does not do; the user settled this on 2026-11-03 (spec, *Session 2026-11-03 (planning)*).

The original's two paths disagree with each other in places. Where they do, the Scala route path's
form is taken (settled 2026-11-03): it is the path the backoffice and the player routes drive, and the
one `CustomerIOWebhookRoutesSpec` and the Playwright suites exercise.

**What the spec had wrong, and what it now says** (each with its source):

| Spec said | The original | Now |
|---|---|---|
| retried four times | `maxAttempts = 4`: four attempts, three retries, 500 ms doubling, ±25 % jitter, on transport errors, 429 and ≥ 500; Go only (`G/cio_client.go:17-21,39-62,90-101`). The Scala path never retries and swallows every failure (`S/CustomerIOClient.scala:184-202`) | FR-002: four attempts; a timeout per call is new |
| 22 fact-derived triggers + 8 request triggers | `CrmEvent` has 30 values: 23 dispatched (identify, track, optional transactional), of which `ConsentUpdated` is one, from the consent `PATCH` (`S/PlayerRoutes.scala:1789`); 7 send-only (`S/CrmService.scala:13-35,532-675`) | FR-006 (23 + `affiliate_attributed`), FR-014 (7) |
| `reward_id`, `clawback_amount`, `amount_wagered`, `wagering_required` on the reward events | `bonus_granted`: `bonus_name` (the offer id), `amount` (major), `currency`, `reward_id`; `bonus_cancelled` and `wagering_requirement_met`: `bonus_name`, `amount`, `currency` only (`O/starter-packs/casino/functions/notify-bonus/notify_bonus.tl:35-68`) | FR-006 |
| `kyc_rejected` carries `rejection_reason`; `self_exclusion_activated` carries `exclusion_period` | neither carries data (`G/handler.go:239-244,258-263`) | FR-006 |
| `rg_limit_applied` with cause `applied` or `removed` → `rg_limit_loosened` | tracked at the *request*: a loosening is tracked when staged, 24 h before it applies, with `limit_type` and `limit_value` (`S/RgRoutes.scala:429-439,356-367`); a delete tracks it at once with `limit_type` only (`:468-478`) | FR-006 derives it from a new 010 fact, `rg_limit_loosening_requested`, and from cause `removed` (R6) |
| `payment.failed` → `payment_failed` | `PaymentExpired` twins also produce `payment_failed` (`O/starter-packs/casino/functions/notify-payment-expired/`); the data adds `payment_method`, `crypto_coin`, `crypto_network`, `reason` (normalised), `provider`, `provider_code`, `expires_at`, `received_crypto_amount`, `received_convert_amount`, `received_convert_currency`, each only when non-empty (`G/payment_failed.go:243-255`); one event per transaction id (`G/handler.go` dedup `payment_failed:<transaction_id>`) | FR-006; the keys the facts carry (R8) |
| `affiliate_attributed` names the network and affiliate | `affiliate_id` and `btag` (`G/handler.go:264-273`); also fires when a promo code resolves to an affiliate (`S/BonusRoutes.scala:2752-2760`); never has a transactional (`G/types.go:117-137`) | FR-006: `btag` only (no brand service knows an affiliate id, spec 012); the code path is a divergence |
| FR-013: transactional data = event data + `player_name`, `player_email`, `brand_id`, `brand_name`; `language` passed as the message's language | dispatch sends the whole track data (every identify attribute plus the extras) as `message_data`, all strings, no `player_email`, no `brand_name`; the send-only triggers send their own keys plus `brand_name`, which is the brand id at every call site; the Scala path lifts `language` to the top-level field, the Go path leaves it in the data (`S/CrmService.scala:446-530`; `S/CustomerIOClient.scala:138-146`; `G/cio_client.go:232-239`) | FR-013 |
| FR-008: every attribute omitted when unseen | `id`, `brand_id`, `has_rg_limits`, `daily_deposit_count`, `email_subscribed`, `sms_subscribed` and `pot_vip` are always sent (`S/CrmService.scala:305-397`); `affiliate_id` is sent; the Go path alone sends `last_device_os` | FR-008; `affiliate_id` and `last_device_os` omitted (R9) |
| `rg_limit_types`, `bonus_money` | Scala: `DEPOSIT_DAILY,…` and the wallet's restricted balance; Go: `rg_limit_DEPOSIT_DAILY,…` and `bonus_balance` | the Scala forms |
| FR-009: 03:00 UTC | the deposit day begins at 03:00 UTC (`S/DepositDay.scala:23-30`); the reset CronJob runs at `5 3 * * *` (03:05) and is `enabled: false` by default, on in KinD (`O/starter-packs/casino/chart/values.yaml:810-813`; `O/deploy/kind/casino-values.yaml:238-239`); a player is reset only when the count is non-empty, not `0`, and stamped with an earlier day | FR-009: 03:05 |
| FR-010: `player_fraud_flagged` sync | no such sync; identify-only syncs come from the backoffice `vip-tier` and `pot-vip` PUTs and the reset (`S/BackofficePlayerRoutes.scala:2369,2421`); the Go path identifies on every player-twin write | FR-010 |
| `OperatorInvitation`, `OperatorStatusChanged` | `OperatorInvite`, `OperatorPasswordReset` in merchant-service (`O/substrate/merchant-service/.../OperatorInviteEmailService.scala:126-135`); not this service's (spec 004) | Clarifications |
| the holding configuration as a fallback | the holding twin is read first and is authoritative; the casino twin is the fallback (`S/CrmService.scala:192-221`) | Clarifications; neither is carried |
| the seed is idempotent | 30 identify+track pairs, one per `CrmEvent` including the send-only ones, excluding `affiliate_attributed`; 422 `credentials_not_configured` on an empty site id; `{seeded: 30, person}`; nothing deduplicated (`S/CustomerIOConfigRoutes.scala:254-309`) | FR-026 |
| a player with no address: identify and track still made | the Scala path skips everything when the email is empty (`S/CrmService.scala:483-491`); `notify_player` requires an email | Edge case: nothing sent, `no_address` recorded |
| a refusal "unauthenticated" | four 401 codes in order: `brand_not_configured`, `webhook_not_configured`, `timestamp_invalid` (missing or non-numeric too), `invalid_signature` (missing too); timestamp before signature; `abs(now − ts) < 300` s; 400 `could_not_read_body` (`CW:895-971`) | FR-017 |
| FR-018's answers | the route's own codes: `grant_bonus` 200 `{ok, bonusAmount}` or `{ok, grantId, providerStatus}` or `{ok, alreadyProcessed}`; 422 `{error}` with `offer_not_found`, `offer_not_active`, a decline reason, `grant_failed`, `currency_not_configured`, `player_currency_unresolved`, `spin_value_not_offered`, `game_not_eligible`; 503 `ELIGIBILITY_CHECK_UNAVAILABLE`; 502 `provider_rejected`; missing fields 422 with the literal `"player_id and offer_id required"`, `"player_id required"`, `"player_id and role required"`; `send_message` also `unknown_merge_tag`, `category_required`, `player_not_found`, `message_failed`; `target_offer` refuses `DEPOSIT_MATCH_WITH_FREE_SPINS` too, `role_not_found`, `target_failed` (`CW:174-274,337-357,676-805,807-892`) | FR-018: the answers `rewards` and `players` give, which 011 and 008 built to these codes |
| FR-019: never from the webhook body | the amount never; the deposit currency and the load currency fall back to the webhook's `currency` (`CW:131,171`) | FR-019; `rewards`' award route takes `currency?` |
| `token_invalid`, `max_age`, `slow_consumer` | `revalidation_failed` every 30 s × (1.0–1.2, once per connection), also when the token resolves to another player; `connection_recycled` at 5 min × (1 + (|id.hashCode| % 20)/100), swept every 30 s; `shutting_down`; an overflowing 64-frame queue drops the connection with no frame (`PG/Revalidator.scala:42-72`; `PG/ConnectionSweeper.scala:61`; `PG/ConnectionRegistry.scala:110,250-285`) | FR-021 (settled 2026-11-03) |
| `ready` names the player | `event: ready` data `{subjectId, serverTime}`; `event: signal`, `id: <correlationId>`, data `{kind, correlationId, hint}`; `event: closing` `{reason}`; `: heartbeat <instant>` every 15 s, the first after 15 s (`PG/ConnectionRegistry.scala:24-45`; `PG/StreamRoutes.scala:91-94`) | FR-020 |
| `subject_unresolved` for any token without a player id | 401 `unauthenticated` for a missing or bad token; 403 `subject_unresolved` for a non-player token; 503 `capacity` at `MAX_CONNECTIONS` (10,000) per pod (`PG/StreamRoutes.scala:55-74`) | FR-020 |
| one `push_delivered` with the cluster's count, on `player_message_created` only | one `delivery.outcome` per pod holding connections, with that pod's count, for created, read and delete alike, so deleting an unread message marks it delivered; counted before the offers resolve (`PG/EnvelopeConsumer.scala:118-133`; `S/PushEventConsumer.scala:141-223`) | FR-022 kept as a divergence (settled 2026-11-03, R11) |
| presence counted across the cluster, with the time online | per pod, 0→1 and 1→0, plus a `presence.snapshot` every 30 s; the reader holds `lastSeenAt` per pod with a 60 s grace; no test-account rule in presence (`PG/PresenceTracker.scala`; `S/PresenceView.scala:32-137`) | FR-023 kept as a divergence (settled 2026-11-03, R11) |
| FR-024's agent stream on `player_session_started`, bearer-authenticated | `notify_agents_player_online` fires when a `PlayerSession` becomes active, publishing `player_logged_in`; the stream is opened with a single-use ticket: `POST …/agent-stream/ticket` (brand access) answers `{ticket, expires_in: 30}`, `GET …/agent-stream?ticket=` takes no bearer, 403 `invalid_ticket`; frame `id: <session_id>`, `event: player_online`, data `{player_id, player_email_prefix, session_id, occurred_at}`; `: ping` every 15 s; a 16-frame queue, dropped on overflow; a failed player lookup broadcasts an empty prefix (`S/BackofficeAgentStreamRoutes.scala:39-134`; `S/AgentStreamTicketStore.scala:33-62`; `S/AgentStreamConsumer.scala:41-185`) | FR-024 |
| Go scenario 16 is a webhook recording; no other Go scenario touches Customer.io | scenario 16 never calls the webhook; `TestScenarioFreeSpinsPlay_CustomerIOGrant`, `TestScenario21_SuspectedDuplicate_RefusedOnEveryPath` (`grant_bonus`) and `scenario_free_spins_stacking` do (`O/tests/e2e/scenario21_eligibility_matrix_e2e_test.go:176-200,292`; `scenario_free_spins_stacking_e2e_test.go:66,82`) | Recordings (R2) |
| every notification suite drives `/api/player/stream` | the player app's `EventSource("/api/player/stream")` proxies to `${PUSH_GATEWAY_URL}/v1/stream`; suite 47 calls casino-service's `agent-stream/ticket` and `agent-stream?ticket=` directly (`O/tools/casino-player-frontend-next/src/app/api/player/stream/route.ts:10,17`; `O/casino/frontend/src/hooks/useAgentPresenceStream.ts:51,59`) | Assumptions; R7 keeps both paths |

**Not in the original, kept as this spec's own** (each listed under *Changes from the original*): the
send record and its claim, retries on the Scala path, the dead letter with operator retry and discard,
outbound suppression with its attributes, the deletion of a person at erasure, a per-call timeout, the
refusal counter on status, the 2 s and 5 s bounds, cluster-wide presence and one delivered fact.

## R2. Recordings

**Decision**: three oracles, as the spec's *Recordings* says, corrected:

1. **The Go suite through `crm-stub`.** A `crm-stub` (Go, beside `tests/stubs/psp-stub`, the same
   `stubhttp` recorder) answers Customer.io's two hosts: `POST /api/v2/entity` and
   `POST /v1/send/email`, 200 to everything, `GET /__received` listing every call with its body,
   `POST /__control` to answer 429, 5xx or a hang for the next *n* calls. The original's compose
   override gains it, with `CUSTOMERIO_TRACK_URL`/`CUSTOMERIO_APP_URL` (the clients' base URLs,
   `S/CustomerIOClient.scala:72-76`, `G/cio_client.go:23-35`) pointed at it, and `preflight` refuses
   to start without it (FR-029). The CRM configuration of the recording brand is seeded through
   `PUT /v1/casino/crm-config/{brand}` with the stub's keys and a transactional id for every trigger,
   so every dispatch also sends. The scenarios that produce CRM traffic are then recorded as any
   other, and each recording's `captures` gain the stub's `__received` entries in order: scenarios
   40 (registration), 16 and 21 (deposit, codes, rewards), 30 (withdrawal), the RG and KYC
   scenarios of `e2e-rg-kyc.patch`, and the three webhook scenarios (`FreeSpinsPlay_CustomerIOGrant`
   with operator credentials, 21, `free_spins_stacking`). Scenarios 21 and stacking are already under
   `tests/port/rewards/`; their webhook steps are replayed from there (`WireTranslation`, R7).
2. **An `engagement-driver`** (`tests/original/engagement-driver/`, the shape of `rewards-driver`)
   for what the suite never reaches: the config `GET`/`PUT`/seed with masking, the webhook's four
   refusals and `send_message`/`target_offer` paths, the daily reset route
   (`POST /internal/v1/casino/{brand}/daily-deposit-count/reset`), the agent-stream ticket and stream,
   the player stream's frames (`ready`, a `signal` after an operator message, `closing` on
   recycle), and `GET /v1/presence`.
3. **The unit suites as expected answers**: `G/handler_test.go` (1,070 lines: candidate events,
   dedup ids, freshness, attribute encoding), `G/payment_failed_test.go`,
   `G/daily_deposit_count_test.go`, `CustomerIOWebhookRoutesSpec` (lines 749-1847, the webhook's real
   oracle), `PresenceViewSpec`, `AgentStreamTicketStoreSpec`, `NotificationDeliveryIntegrationSpec`,
   `PushEventConsumerSpec`, `AgentStreamConsumerSpec` and the ten `push-gateway` specs. Their cases
   are ported into this service's pure suites (R13).

The Playwright suites stay the frontends' acceptance (SC-009); `32-player-email-management` is
added to the list.

The recording run needs the user: the original's KinD stack, operator credentials for the free-spins
scenario, and a brand configured against the stub (memory: *Recording the original on kind*).

## R3. Components: a profile per player, a send per event, a channel per player

**Decision**:

- **`Profile`** (key value entity, id = player id): everything FR-008 reads, the consents, the open RG
  cases, the account status, the deposit-day count and stamp, the balances after the last movement,
  the lifetime deposit count and the 20 most recent payment initiations (R8). Written only by the
  consumers; read by every send. A key value entity rather than event sourced: the attributes are
  the latest value of each, the facts are the journal, and `ProfilesView` (`stateOf`) serves the
  deposit-day timer and the operator's reads.
- **`Send`** (workflow, id = the send key, R4): the send record *is* the workflow's state, as 007's
  `WithdrawalWorkflow` and 011's `WalletStep`. `start` is the atomic claim: a repeat is a no-op that
  answers the first outcome. Its steps make the calls with FR-002's attempts as durable pauses; it
  parks itself; `retry` and `discard` are its commands. `SendsView` (`stateOf(Send)`) is the player's
  send history, the dead letter and the hourly counts.
- **`Channel`** (event sourced, id = player id): the player's live connections across the cluster and
  a ring of the last 32 signals (R10). Its events are where `presence_changed` and `push_delivered`
  come from. **`AgentChannel`** (event sourced, id = slug) is the same for the backoffice agents, with
  **`AgentTicket`** (key value, id = ticket) for the single-use ticket.
- **`Configuration`** (key value, id = slug): FR-004's non-secret part and the names of the three
  secrets in the secret store.

**Alternatives**: an event sourced `SendRecord` entity beside a stateless step (two writers for one
record; the workflow's state already outlives the wallet's recent keys in 007 and 011); connections in
instance memory with a per-instance consumer group as the original (ankka runs a consumer once per
cluster, not per instance, and the platform keeps no record of who holds a socket).

## R4. The send key and the claim

**Decision**: a `Send`'s id is `<event>/<dedup>` where `<dedup>` is the fact id, except:

- `payment_failed/<payment id>`: the original's dedup id was `payment_failed:<transaction_id>`, so an
  expiry after a failure of the same payment sends nothing (R1);
- `<trigger>/<key>` for the send route (FR-014), the caller's key;
- `deposit-day/<yyyy-mm-dd>/<player>` for the reset's identify (FR-009);
- `seed/<slug>/<n>` is not a send: the seed is answered from the calls themselves (FR-026).

The original's `withdrawal_failed:<playerId>` id, which sent one failed withdrawal per player per 25
hours, is a defect and is not carried (a divergence). A replayed fact finds its `Send` and makes no
call (FR-007). The staleness bound is judged at the claim, against the fact's `occurredAt` and the
brand's setting (24 h shipped), and a stale fact's `Send` ends at once as `skipped_stale`.

## R5. The calls out, in order, and the attempts

**Decision**: a `Send` runs `identify` → `track` → `transactional`, the original's order
(`S/CrmService.scala:446-530`), each a step:

- the identify is the full profile (FR-008), the track carries the identify attributes plus the
  event's data (`G/handler.go:287-303`), the transactional carries the track data as strings (R1);
- an attempt that answers a retryable failure (connection failure, timeout, 429, ≥ 500) pauses the
  workflow for 500 ms, 1 s, 2 s (±25 %) and runs again; the fourth failure parks the send with the
  last answer; any other failure ends the step as `refused` with the status (FR-002);
- a track that fails after an accepted identify retries the track alone (edge case);
- each step reads `Profile` first: a marketing-class trigger for a suppressed player, or any trigger
  for a closed player but the closure confirmation, ends as `marketing_suppressed` or
  `account_closed` with an audit record, at the first attempt and at every retry, automatic or an
  operator's (FR-011; settled 2026-11-03);
- the identify of a suppressed player is still sent (FR-012): a `Send` whose event is withheld makes
  its identify step and skips the rest.

The credentials are read from the secret store at each attempt (a rotation mid-flight is picked up,
edge case), cached 30 s per instance as `game-provider` does, because every read is recorded.

A per-call timeout of 10 s is new (the original had none, R1).

## R6. What the neighbours must change

Settled 2026-11-03 (spec, *Session 2026-11-03 (planning)*):

- **spec 010 / `services/responsible-gambling`** (`P1`): a new family on `casino.players`,
  `rg_limit_loosening_requested` (`playerId, limitType, period, value, currency, appliesAt,
  occurredAt`), produced when a loosening is staged; FR-030 amended; the raise scenario of
  `limits.feature` gains the step. `engagement` tracks `rg_limit_loosened` from it, and from `rg_limit_applied` with cause
  `removed`.
- **spec 008 / `services/players`** (`P2`): `player_consent_changed` for `email` and `sms` at
  registration, source `registration`, beside `player_registered` (today the consents are stored and
  nothing is published, `services/players/.../api/RegistrationRoutes.scala:157`); FR-028 amended;
  the consents scenario of `registration.feature` gains the step.
- **`services/players`** (`P3`): the `Engagement` client treats `refused` with its reason as a
  terminal answer, like `suppressed` (`services/players/.../clients/Neighbours.scala:60-66`), so a
  verification send refused as `account_closed` is not retried for a minute.
- **the contract** (`P4`): the family above in `CasinoPlayers`; `contract/access/routes.md` gains the
  send route, the erasure route and the message route's reverse row; `contract/keys.md` loses the
  `push-provider-key` row and the `engagement` row of *Not yet built*.
- **`modules/engagement/module.json`** (`P5`): `push-provider-key` and `PUSH_PROVIDER_KEY` removed
  (settled 2026-11-03: the three Customer.io secrets enter through the configuration route, R12);
  `tools/modules`' `RenderSuite` and `ValuesSuite` follow.

Verified while planning:

- `players` already calls `POST /v1/casino/brands/<brand>/engagement/sends` with
  `{key, playerId, trigger, to, link}` and reads `status` as `accepted`, `not_configured` or
  `suppressed` + `reason`, bound 2 s, retried for a minute (`services/players/.../clients/Neighbours.scala:46-66`;
  `RegistrationWorkflow.scala:322-363`); it sends `EmailAddressVerification`, `EmailChangeVerification`
  and `PasswordChangeVerification` only; a password reset goes through the realm. The route serves the
  seven triggers of FR-014 regardless (*Elsewhere*).
- `players` serves `POST /v1/casino/brands/<slug>/players/{playerId}/messages` to `engagement` with
  `{deliveryId, category, subject, body}`, entity id = the delivery id, origin `crm`, 201 with the
  message, 400 `missing_fields`, 422 `category_required` | `account_closed` | `marketing_suppressed`,
  404 `player_not_found` (`services/players/.../api/OperatorProfileRoutes.scala:118-136`). It takes no
  `campaign_id` and no `reward_id` (011 dropped it): both are read and dropped by the webhook.
- `players`' `PushDeliveredConsumer` marks the message whose id *is* the fact's `correlationId`
  delivered (`services/players/.../facts/Consumers.scala:92-105`): the fact carries the bare message
  id; the frame carries the original's `<brand>:<message id>` (R10).
- the operator's message-suppression flag is published as `player_consent_changed` with channel
  `messages`, `consented = !suppressed`, source `operator` (`services/players/.../player/PlayerEntity.scala:272-275`).
- `rewards` serves `POST …/reward-campaigns/{playerId}/target` `{role, context?}` and
  `…/award` `{offerId, role?, currency?}` to `engagement`, answering the webhook's own shapes and
  codes (`services/rewards/.../api/CampaignEndpoint.scala:24-130`; `Answers.scala:116-119`).
- no fact carries a display name, a language or a potential-VIP mark; `player_registered` carries
  the given and family names, the email, the date of birth, the country and the currency, and
  `player_profile_updated`, `player_email_changed`, `vip_tier_changed`, `player_duplicate_check_ended`,
  `player_closed` | `suspended` | `reinstated` | `reopened`, the KYC and RG families carry the rest.
- `payment.failed` carries `code` and `reason` and no amount; `payment.initiated` carries the amount,
  currency, provider and terminal (R8).
- RG case types are `self_exclusion` and `cool_off`; the field is `causedBy`.
- the `engagement` module is its own manifest (`modules/engagement/module.json`), depending on
  `core`, with the topic `casino.push`; `ModuleManifestSuite` pins the built set.
- `services/exchange` serves an `sse` route built on `Source.tick` and `mapAsync` (`api/PlayerEndpoint.scala:66-74`).
- HTTP 9013 is reserved for engagement by players' local run; Postgres 5442 is the next free.

## R7. The routes: five endpoints

**Decision** (ankka 0.13: one prefix per endpoint, two path parameters, the brand a literal):

| Endpoint | Prefix | Class | Why this path |
|---|---|---|---|
| `SendEndpoint` | `/v1/casino/brands/<slug>/engagement` | service (`Routes.service()`); the erasure route `Routes.playersOnly` | what `players` already calls |
| `StreamEndpoint` | `/v1/stream` | player (`Routes.player(slug)`) | the player app's proxy appends `/v1/stream` to its base URL (R1); the brand is the project and the token's realm |
| `WebhookEndpoint` | `/v1/casino/customerio` | public (`Routes.public`), served only under the signature | Customer.io is configured with `…/webhook/<brand>`; a `{brand}` other than the slug is 404 `wrong_brand` |
| `BackofficeEndpoint` | `/v1/casino/backoffice/<slug>` | operator (`Routes.operator(slug)`) | the backoffice calls `…/agent-stream/ticket` and `…/agent-stream?ticket=` at this prefix; the sends, parked sends, presence and status routes sit beside them (routes.md) |
| `ConfigEndpoint` | `/v1/casino/crm-config` | operator, roles `brand_admin`, `resource_admin`, `platform_admin` (`Routes.operatorHolding`) | the console calls `…/crm-config/{brand}` |

`/v1/stream` and `/v1/casino/customerio` carry no `{brand}` segment because the original's did not
(constitution III keeps a segment, it does not add one); each refuses a token or a path of another
brand. If the SDK refuses `sse("/")` under `/v1/stream`, the route is `sse("/{slug}")` and the
frontend's base URL carries the slug; the build records which.

The webhook's `grant_bonus` and `target_offer` are relayed to `rewards` and `send_message` to
`players`, each answer passed through as is (R1's table); the webhook adds only its own verification
refusals, `unknown_action`, `delivery_id_required` and the missing-field 422s, and a 400 for a body
that is not a JSON object (the original threw; a divergence).

## R8. Facts to events: the table as the facts allow

**Decision**: FR-006's table is derived from the facts as built, with these readings:

- `bonus_granted` from `bonus_granted` and `free_spins_granted` (the original fired on any
  activation); `bonus_cancelled` from `bonus_cancelled` and `bonus_forfeited` (cancelled, forfeited,
  revoked); `wagering_requirement_met` from `bonus_completed` (completed, converted), not from the
  wallet's `restricted_funds_converted`; `bonus_name` is the offer id, as the original's `offer_ref`.
- `payment_failed` from `payment.failed` and `payment.expired`, with `amount` and `currency` from the
  payment's `payment.initiated` held on the profile (the 20 most recent), `transaction_id` the payment
  id, `failure_reason` the fact's `reason` (or `expired`), `provider` the provider, `payment_method`
  the terminal, `reason` normalised from `code` as `G/payment_failed.go` does; the crypto and
  `expires_at` keys are absent (no fact carries them; a divergence named).
- `deposit_limit_set` | `loss_limit_set` | `session_limit_set` from `rg_limit_applied` with cause
  `set` or `lowered`, by `limitType`; `limit_type` in the original's form (`DEPOSIT_DAILY`, the type
  and period upper-cased), `limit_value` in major units for money limits, minutes for a session
  limit; `rg_limit_loosened` from `rg_limit_loosening_requested` (both keys) and `removed`
  (`limit_type` only).
- `cooling_off_set` from `rg_case_opened` with type `cool_off`, `cooling_off_period` = `closesAt`;
  `self_exclusion_activated` from `player_self_excluded`; `kyc_approved` | `kyc_rejected` from
  `player_kyc_verified` | `player_kyc_rejected`; the document events from
  `player_kyc_document_reviewed` by `decision`, `doc_type` = `category`, `reason` = `reasonCode`.
- `promo_code_entered` from every `bonus_code_entered`; `consent_updated` from
  `player_consent_changed` for `email` and `sms` (the `messages` channel changes the suppression
  attributes and identifies only).
- `affiliate_attributed` from `player_registered` with a `btag`, data `btag`.
- `player_logged_in` from `player_logged_in` unless within two minutes of `registeredAt` (a negative
  difference counts as auto-login, as the original).
- `first_deposit_confirmed` beside `deposit_confirmed` when the profile's lifetime count becomes 1
  (the original's `newCount == 1`, not the wallet's per-currency ordinal).
- Amounts in major units with two decimals, `HALF_UP`, as `S/CustomerIOClient.scala` rounds.

## R9. The profile and the identify

**Decision**: `Attributes.of(profile)` renders FR-008 exactly as `buildIdentifyAttributes` does:
`player_name` = given name, else the part of the address before `@` (no display name exists, settled
2026-11-03); the seven always-sent attributes; the rest only when seen; the numeric nine as numbers
with the string fallback (FR-003); `language`, `affiliate_id` and `last_device_os` never (no fact
carries them; divergences named); `pot_vip` always `"false"` (no fact; a finding for 008);
`suspected_duplicate` from `player_duplicate_check_ended` (`strong` | `weak`); `kyc_status` and
`account_status` from the KYC and status families; `rg_limit_types` as the Scala form;
`real_money`, `bonus_money` (the restricted balance), `total_balance` from the last
`money.transactions` fact's balances, omitted until one is seen; `marketing_suppressed` and
`suppression_reason` from `Suppression.of(profile)` (R14).

An identify with no event (FR-010) is a `Send` of trigger `identify` keyed by the fact id, made for
`vip_tier_changed` and for every fact that changes an attribute no event carries: `player_email_changed`,
`player_profile_updated`, `player_duplicate_check_ended`, the status families, `rg_case_closed`,
`player_consent_changed` on `messages`, the balance movements that carry no event. It is cheap and the
original identified on every twin write.

## R10. The push channel: a sharded entity, polled by the stream

**Decision**: ankka keeps no record of who holds a socket, a consumer runs once per cluster, and a
stream's `Source` lives on the instance that served the request. So:

- the stream handler, on open, calls `Channel.open(connection, instance, now)`, then ticks every
  second (`Source.tick`, `mapAsync(1)`, as `exchange`), each tick a `Channel.poll(connection, cursor)`
  *query* (no write) answering the signals after the cursor; frames are written from the answer, then
  `Channel.acked(connection, seq)` (a write only when something was written); every 30 s the tick
  also sends `heartbeat(connection)` (the lease) and re-verifies the token (R1's reasons); on
  recycle, revalidation failure or shutdown it writes `closing` and calls `close(connection, reason)`;
- a consumer of `player_message_created` | `read` | `deleted` calls `Channel.signal(kind,
  correlationId)`, which appends to the ring when at least one connection is open and records which
  connections were open; `Delivered(correlationId, n)` is the event when every one of those has acked
  the signal or closed, once, for `created` only (settled 2026-11-03);
- `Opened` on 0→1 and `Closed` on 1→0 are `presence_changed` (`online` with `since` = the first
  open's time); a `sweep-<player>` recurring timer (30 s) while the count is above zero expires any
  connection whose lease is older than 90 s, so an instance that died takes its players offline
  within two minutes (90 s + 30 s at worst); the timer is cancelled at zero;
- the ring holds 32 signals; a connection whose cursor has fallen out re-reads (the page refetches
  anyway, push carries no content); the queue of 64 frames per connection overflows into a drop, as
  the original (settled 2026-11-03);
- the frame's `correlationId` is `<brand>:<message id>` (what the original's frontend sees); the
  fact's is the bare message id (what `players` as built reads).

**Alternatives**: a Pekko distributed pub-sub inside the service (a dependency on the runtime's actor
system that no service here takes); per-instance consumer groups (ankka has none). A poll a second
per open connection is a query on a sharded entity in memory: at 10,000 connections it is 10,000
reads a second across the cluster, under the wallet's bet rate.

SC-004's three-instance proof is the kind run (quickstart §6): the test kit runs one instance, and the
integration suite proves the design by holding nothing in instance memory and passing across
`restartService()`.

## R11. Facts and the contract

**Decision**: `casino.push` as the contract has it: `push_delivered(playerId, correlationId,
connections, occurredAt)` once per `player_message_created` with the cluster's count when above zero;
`presence_changed(playerId, online, since, occurredAt)` when the count crosses zero. Both from
`Channel`'s events through `PushPublisher`. `platform.audit` from `AuditPublisher` over `Send`'s
states (a refusal of FR-011, a retry, a discard) and `Configuration`'s changes (a secret named,
never a value). The one contract addition is 010's `rg_limit_loosening_requested` (R6).

## R12. Configuration, secrets and the seed

**Decision**: `Configuration` holds `siteId`, `region`, `fromEmail`, the trigger table (the thirty-two
`CrmEvent` keys, each a transactional id or absent) and the three secret names
`crm/<slug>/track-key`, `crm/<slug>/app-key`, `crm/<slug>/signing-key`; the values go to
`clients.secrets` from the `PUT` and nowhere else. The `GET` answers every key with `""` when absent,
and each secret masked as its first three characters plus `len − 3` asterisks; a submitted secret
that is non-empty and all `*` after the third character preserves the stored one (`S/CustomerIOConfigRoutes.scala:46-52`).
The seed sends thirty-two identify+track pairs with the original's `seedAttributes` and `seedData` to
`_seed_<slug>`, 422 `credentials_not_configured` on an empty site id, and answers
`{seeded: 30, person}`; a repeat re-tracks (the original did; the identify upserts).

`EmailAddressVerification` falls back to the transactional id `email_address_verification` when the
trigger has none, still needing a configuration and an app key (`S/CrmService.scala:229-262`).

## R13. Pure logic, ported with the original's suites

`Events` (fact → events and data: `handler_test.go`'s candidate and single-mode cases,
`payment_failed_test.go`), `Attributes` (`handler_test.go`'s encoding cases; `numericAttributes`),
`DepositDay` (`daily_deposit_count_test.go`), `Classes` (FR-011), `Suppression` (R14), `Signature`
(`CustomerIOWebhookRoutesSpec`'s verification cases), `Masking`, `Seed`, `Frames` (the SSE frame
texts), `WireTranslation`. No component in any of them.

## R14. Suppression

**Decision**: `Suppression.of(profile)` answers, per channel: suppressed while the account is closed
(`account_closed`), an RG case of type `self_exclusion` or `cool_off` is open (`self_excluded`,
`cooling_off`), the `messages` consent is withdrawn (`operator`), or the channel's consent is not
granted (`consent_withdrawn`; a player with no consent fact is not consented, fail-closed, until R6's
P2 ships). The reason is the first in that order. A marketing-class trigger for a suppressed player
sends nothing and tracks nothing; the identify carries `email_subscribed`, `sms_subscribed`,
`marketing_suppressed` and `suppression_reason`; a service-class trigger is unaffected. A closed
player gets only `account_closed` (the closure confirmation is `players`' in-app message, not a
trigger here). `rg_case_closed` lifts the case; `player_reopened` lifts the closure.

## R15. Personal data and erasure

**Decision**: the profile's email, given and family names, date of birth, the send record's recipient
address and data, the parked send's data, and the agent frame's email prefix are `Personal` under
`player/<id>`. The erasure route (`POST …/engagement/erasure/players/{player}`, `Routes.playersOnly`,
answering `{service, subject, crm: deleted | failed, sends: n}`) asks the CRM to delete the person
(`/api/v2/entity` with `action: delete`, the only deletion the Track API offers; the original had
none), ends every open `Send` of the subject as `discarded`, redacts the profile and the records, and
marks the profile erased so a later fact produces no call (FR-016). A `Send` of trigger `erasure`
holds the deletion's attempts, so a CRM outage parks it for the operator like any send.

## R16. Local run and CI

HTTP 9013 (players' local run already points there), Postgres 5442; `just engagement-test`,
`engagement-it`, `engagement-run <slug>`; the CI filter `engagement:` over `services/engagement/**`,
`features/engagement/**`, `tests/port/engagement/**`, `tests/stubs/crm-stub/**`; the `crm-stub`
built in the stubs job beside `psp-stub`.

## For ankka

The SSE frame fields: ankka 0.13's `SseEvent` writes a name and JSON data only, so an `id:` line and a comment
line cannot be sent (found during implementation, below). A stream that keeps the original's frames needs both.

A per-instance consumer group, or a way to reach every instance's sockets, would make
R10 a broadcast rather than a poll; the poll is cheap enough that no spec is raised.

## Declined remediations

(none)

## Elsewhere

- **spec 008**: `players` sends only three of the seven send-only triggers; the original also sent
  `EmailChangeNotification` (with the new address unmasked despite its key), `EmailPrimaryChanged`,
  `PasswordChangeNotification` and `PasswordResetRequest`; `engagement` serves all seven. No fact
  carries a display name, a language or a potential-VIP mark, so `player_name` falls back to the
  given name, `language` is not sent and `pot_vip` is always `false`. No fact carries a
  test-account flag, so FR-023's exclusion from the agent alert waits on one. The players list's
  online filter (`?online=true`) reads `presence_changed` from `casino.push`.
- **spec 015**: `reporting` reads `casino.push` for the online count.
- **spec 019**: the migration seeds each player's `Profile` from the player twin (attributes,
  consents, counts) so the first identify after cutover is complete, and sets no `Send` for history.
- **spec 017**: `support-agent` reads a player's sends through the backoffice route.
- **spec 012**: `affiliate_attributed` on an affiliate's code is not derived here; `attribution`
  knows which codes are affiliates'.

## Found during implementation

- **SSE frames** (2026-11-03, asked and settled): ankka 0.13's `sse` writes named events with JSON data
  only, never an `id:` or a comment line. So the player stream's heartbeat is `event: heartbeat`
  `{"at": <instant>}` every 15 s, the agent stream's ping `event: ping` `{}` every 15 s, and the ids
  (`correlationId` on `signal`, `session_id` on `player_online`) are carried in the data alone, which
  already holds them. The frontends listen by event name and never received the comments; a
  reconnect's `Last-Event-ID` is lost, and the page refetches anyway. A divergence (plan.md), and a
  note under *For ankka*.

- **ankka 0.16.0 and its `Personal`** (2026-11-06, asked and settled): SC-006 needs the personal values
  sealed at rest, and ankka 0.13 had no personal-data encryption. ankka 0.16.0 (feature 042) does:
  `com.thinkmorestupidless.ankka.core.Personal[A]` (`Present(subject, value)` or `Erased(subject)`)
  is sealed under a per-subject key by every `Serializer`, so the journal, the key value state, the
  workflow state and the view rows hold only ciphertext. So engagement builds on 0.16.0 (the contract,
  built on 0.13.0, is evicted to it as the predictions services do; its 250 tests pass on 0.16.0),
  and R15's fields are ankka's `Personal` under `DataSubject("player/<id>")`, converted from the
  contract's marker as a fact is read. A `Send` whose subject reads `Erased` makes no call. The kit's
  `assertNoPersonalValue` is DumpSuite's check. The erasure route stays as R15 says (players calls it);
  an `ErasureHandler` runs the same CRM deletion and discards, so an erasure filed at the installation
  reaches the CRM too, and the keyring's shredding makes what is stored unreadable either way. A local
  run takes `withLocalKeyring()` when `ANKKA_KEYRING_ADDRESS` is unset. The module's runtime is
  0.16.0. A divergence from the plan's 0.13.0 (plan.md).

- **Thirty-two triggers** (2026-11-07, asked and settled): the original's `CrmEvent` gained
  `KycAwaitingDeposit` (`kyc_awaiting_deposit`, seed data `doc_type: payment_method`; 2026-11-04) and
  `BankAccountAdded` (`bank_account_added`, `account_name`, `masked_iban`; 2026-11-06) after the spec.
  Both are configuration keys and seeded, so the configuration and the seed are the original's; no fact
  here produces either, so nothing tracks them until the facts exist (kyc's awaiting-deposit state, a
  bank account added at payouts). `seeded` is 32 (contracts/routes.md).

- **The fact id** (2026-11-07, decided while building): R4 keys a send `<event>/<fact id>`, but the
  contract's facts carry no id and ankka 0.16's consumer context names none (no `ce-id`; only the
  subject and a sequence). A fact's id here is the first 16 bytes, in hex, of the SHA-256 of the
  topic's name and the fact as the contract writes it (`consumers/Sends.factId`). A redelivery decodes
  to the same value and so the same id; two facts differ at least in their `occurredAt`. A recording's
  ids are its own, so parity compares by event name, not key (T052). Under *For ankka*: a consumer
  that could read the message's `ce-id` would use the producer's id.
- **The original's Customer.io hosts are hard-coded** (2026-11-08, T049): R2 says
  `CUSTOMERIO_TRACK_URL`/`CUSTOMERIO_APP_URL` are the clients' base URLs, but neither client reads
  any variable. `CustomerIOClient.scala:72-76` and `cio_client.go:23-35` choose between the
  regions' hosts. `tests/original/e2e-crm.patch` adds the two variables to both, with the hard-coded
  hosts as the default, and applies only to a recording checkout. It is the first patch here to change
  the original's service code rather than its suite, so the recording rebuilds casino-service and the
  TL runner (not the guests; no TinyGo). The stub's calls are made behind the original's consumers,
  where the suite's transport cannot see them, so `crm-captures` attaches them to each recording
  after the run, by the person each call names. They go in the recording's `outbound`, which spec
  001's `contracts/recording.md` already defines and the recorder now carries (`recorder.Outbound`,
  stub `crm-track` or `crm-app`: the method, the path and the body, never a header).

## Design finished

2026-11-03: analyze reported nothing above LOW.
