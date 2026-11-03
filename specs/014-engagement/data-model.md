# Data model: engagement

Phase 1 of [plan.md](plan.md). Money arrives as `Long` minor units with its currency and is rendered
to major units only inside an event's data. Times are `Instant`. Wire names are camelCase; stored
types go through `EngagementCodecs` (manifests `engagement.<name>`). Every field marked *P* is
`Personal` under `player/<id>`.

## `Profile` (key value, id = player id)

**State** (FR-008, FR-012; research R9):

| Field | Type | Source fact |
|---|---|---|
| `registeredAt` | Option[Instant] | `player_registered` |
| `email` *P*, `givenName` *P*, `familyName` *P*, `dateOfBirth` *P* | Option[Personal[String]] | `player_registered`, `player_profile_updated`, `player_email_changed` |
| `country`, `currency`, `btag` | Option[String] | `player_registered`, `player_profile_updated` |
| `vipTier` | Option[String] | `vip_tier_changed` |
| `lastLoginAt`, `lastDepositAt` | Option[Instant] | `player_logged_in`, `deposit_confirmed` |
| `depositCount`, `totalDepositCents` | Long | `deposit_confirmed` (lifetime) |
| `dailyDepositCount`, `depositDay` | Int, Option[LocalDate] | `deposit_confirmed`; the reset (FR-009) |
| `realCents`, `restrictedCents` | Option[Long] | the last `money.transactions` fact's `realBalanceAfter`, `restrictedBalanceAfter` |
| `limits` | Map[String, Limit(period, value, currency)] by type | `rg_limit_applied` (`set`, `lowered` add; `removed` removes) |
| `cases` | Map[caseId, Case(type, closesAt)] | `rg_case_opened` / `rg_case_closed` |
| `status` | `active` \| `self_excluded` \| `closed` \| `suspended` | `player_self_excluded`, `player_closed`, `player_suspended`, `player_reinstated`, `player_reopened` |
| `kycStatus` | Option[String] | `player_kyc_*` |
| `suspectedDuplicate` | Boolean | `player_duplicate_check_ended` (`strong`, `weak`) |
| `consents` | Map[channel, Consent(granted, at, source)] for `email`, `sms`, `messages` | `player_consent_changed` |
| `recentPayments` | Vector[(paymentId, amountCents, currency, provider, terminal)], the last 20 | `payment.initiated` |
| `erased` | Boolean | the erasure route |

**Commands**: one `apply(fact)` per family the consumers deliver (idempotent: a fact already reflected
changes nothing, judged by `occurredAt` per field), `resetDepositDay(day)` (sets 0 and the stamp;
answers whether anything changed), `erase()` (redacts every *P* field, sets `erased`). **Query**:
`get` → the state; `attributes` → `Attributes.of(state)` and `Suppression.of(state)`.

`Suppression.of` (R14) answers `(suppressed: Boolean, reason: Option[String])` per channel in the
order `account_closed`, `self_excluded`, `cooling_off`, `operator`, `consent_withdrawn`; a channel
with no consent entry is not consented.

## `Send` (workflow, id = the send key, research R4)

**State** (the send record, FR-007):

| Field | Type |
|---|---|
| `key`, `playerId`, `trigger`, `event` (Option), `sendClass` (`marketing` \| `service` \| `identify`), `channel` (`crm` \| `email`) | String |
| `factId`, `occurredAt` | the fact's, or the request's |
| `recipient` *P*, `data` *P* | Option[Personal[String]], Map[String, Personal[String]] |
| `calls` | Vector[Call(kind: identify \| track \| transactional, attempts: Int, outcome, status: Option[Int], lastAnswer: Option[String], at)] |
| `outcome` | `pending` \| `accepted` \| `refused` \| `skipped_stale` \| `no_configuration` \| `no_address` \| `marketing_suppressed` \| `account_closed` \| `parked` \| `discarded` |
| `reason` | Option[String] |
| `parkedAt`, `discardedBy`, `discardReason` *P* | Option |

**Commands**:

| Command | From | Effect |
|---|---|---|
| `start(request)` | a consumer, the send route, the timer, erasure | the claim: a state already present answers its outcome and transitions nowhere; else judges staleness (`skipped_stale`, ends), configuration (`no_configuration`, ends), the address for a transactional (`no_address`), then transitions to `identify` |
| `retry(operator)` | the backoffice | only from `parked`; transitions to the first unaccepted call; audited |
| `discard(operator, reason)` | the backoffice | only from `parked`; ends `discarded`; audited |
| `outcome` (query) | the send route, the backoffice | the state |

**Steps**: `identify` → `track` → `transactional` → end. Each step first reads `Profile.attributes`:
a marketing-class event for a suppressed player, or any trigger but the closure confirmation for a
closed player, ends the send as `marketing_suppressed` | `account_closed` with the reason (the identify
of a withheld event is still made first, FR-012); then makes its call with the credentials read
through `Secrets`; a retryable failure (connection, timeout, 429, ≥ 500) records the attempt and
pauses 500 ms, 1 s, 2 s (±25 %) before the same step; the fourth failure transitions to `parked`; any
other failure records `refused` with the status and moves on (a refused identify ends the send, as
the original's abort). A step with nothing to do (no event, no transactional id and no fallback)
moves on. `parked` is a pause with no timeout.

## `SendsView` (view, `stateOf(Send)`)

One row per send: `key, playerId, trigger, event, sendClass, channel, outcome, reason, factId,
occurredAt, updatedAt, parked: Boolean`. Queries: `byPlayer(playerId)` newest first, paged;
`parked(brand)` newest first, paged, with `search` on trigger and reason; `counts(since)` by outcome;
`oldestParked`.

## `ProfilesView` (view, `stateOf(Profile)`)

One row per player: `playerId, dailyDepositCount, depositDay, status, erased`. Query:
`dailyCountAbove(day)`: `dailyDepositCount > 0 and depositDay < day`, paged by 500.

## `Channel` (event sourced, id = player id, research R10)

**State**: `connections: Map[connectionId, Connection(instance, openedAt, leaseAt, cursor)]`,
`ring: Vector[Signal(seq, kind, correlationId, at, openAt: Set[connectionId], acked: Set[connectionId])]`
(the last 32), `seq: Long`, `onlineSince: Option[Instant]`.

| Command | Event | Rule |
|---|---|---|
| `open(connectionId, instance, now)` | `Opened` | 0→1 sets `onlineSince` |
| `heartbeat(connectionId, now)` | `Leased` | a lease refresh; unknown connection answers `closed` so the stream ends |
| `poll(connectionId, cursor)` (query) | — | the signals with `seq > cursor` |
| `acked(connectionId, seq)` | `Acked` | marks; a signal whose `openAt ⊆ acked ∪ closed` becomes `Delivered(correlationId, openAt.size)` once, for kind `inbox.changed` from `created` only |
| `signal(kind, correlationId, origin, now)` | `Signalled` | appended only when a connection is open; `openAt` = the open set |
| `close(connectionId, reason, now)` | `Closed` | 1→0 clears `onlineSince`; pending signals re-judged |
| `sweep(now)` | `Expired` per lease older than 90 s | from the `sweep-<player>` timer |

Snapshots every 100 events. `PushPublisher` maps `Opened` on 0→1 and `Closed` | `Expired` on 1→0 to
`presence_changed`, `Delivered` to `push_delivered`.

## `AgentChannel` (event sourced, id = slug)

The same shape for agents: connections, a ring of `PlayerOnline(seq, playerId, emailPrefix *P*,
sessionId, at)`; `signal` from `player_logged_in` with the profile's email prefix (empty when the
profile has none); a 16-frame queue per connection, dropped on overflow; no delivered fact, no
presence fact. No fact carries a test-account flag, so none is excluded until 008 adds one
(research, *Elsewhere*).

## `AgentTicket` (key value, id = ticket)

`brand, issuedAt, usedAt: Option[Instant]`. `issue(brand, now)`, `redeem(brand, now)` → refused when
unknown, another brand, used, or older than 30 s; `delete` after redemption.

## `Configuration` (key value, id = slug, FR-004)

`siteId, region (us | eu), fromEmail, triggers: Map[String, String]` (the thirty `CrmEvent` keys →
transactional id; absent is disabled), `secretNames: Map[track | app | signing, String]`,
`updatedAt`. Commands `put(config)` (answers the changed keys for the audit record), `get`. The
secrets are `clients.secrets` under `crm/<slug>/track-key`, `crm/<slug>/app-key`,
`crm/<slug>/signing-key`; `Secrets` caches each 30 s per instance.

## Timers

| Name | Kind | Handler |
|---|---|---|
| `deposit-day-<slug>` | recurring, first due at the next 03:05 UTC, period 1 day; set at every start | `DepositDayTimer.reset(day)`: pages `ProfilesView.dailyCountAbove(day)`, for each `Profile.resetDepositDay(day)` then `Send.start(deposit-day/<day>/<player>, identify)` |
| `sweep-<player>` | recurring 60 s while `Channel` has connections; cancelled at zero | `SweepTimer.sweep(player)` → `Channel.sweep(now)` |

## Consumers

| Consumer | Topic | Does |
|---|---|---|
| `PlayersConsumer` | `casino.players` | `Profile.apply`; `Events`; `Send.start` per event or identify; `Channel.signal` for the message families; `AgentChannel.signal` for `player_logged_in` |
| `SessionsConsumer` | `casino.sessions` | `game_session_started` \| `ended` events |
| `MoneyConsumer` | `money.transactions` | balances; `deposit_confirmed` (+ `first_deposit_confirmed`); an identify for a movement with no event |
| `PaymentsConsumer` | `payments.payments` | `payment.initiated` remembered; `payment.failed` \| `expired` → `payment_failed` |
| `WithdrawalsConsumer` | `casino.withdrawals` | `withdrawal_failed` |
| `RewardsConsumer` | `casino.rewards` | `bonus_granted`, `bonus_cancelled`, `wagering_requirement_met`, `promo_code_entered` |
| `PushPublisher` | `Channel` events → `casino.push` | research R11 |
| `AuditPublisher` | `Send` states, `Configuration` changes → `platform.audit` | a refusal of FR-011, a retry, a discard, a configuration change (keys named, no value) |

Each consumer in is at least once; the `Send` claim and `Profile.apply`'s per-field time make a
repeat harmless.
