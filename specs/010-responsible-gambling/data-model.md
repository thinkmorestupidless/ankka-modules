# Data model: responsible-gambling

Every amount is a `Long` of minor units; `SESSION_MINUTES` is whole minutes. Times are `Instant`, UTC.
Fields marked *Personal* are `Personal[String]` under the player (FR-032).

## ProfileEntity (event sourced, id = player id)

### State

| Field | Type | From |
|---|---|---|
| `playerId` | String | the first command |
| `limits` | Map[LimitKey, Limit] | FR-001; `LimitKey` = type and currency (none for `SESSION_MINUTES`) |
| `pending` | Map[LimitKey, Pending] | at most one per key; `Pending(value: Option[Long], appliesAt, actor)`; `None` is a removal |
| `break` | Option[Break] | `Break(until, setAt, caseId, actor)` |
| `exclusion` | Option[Exclusion] | `Exclusion(permanent, until: Option[Instant], since, trigger, caseId)` |
| `returnRequest` | Option[ReturnRequest] | `ReturnRequest(at, reinstatesAt, actor)` (FR-020) |
| `playersTold` | Option[String] | the status `players` has not yet acknowledged: `self_excluded` or `active` (R4) |
| `realityCheck` | Option[Int] | 15, 30, 45 or 60 |
| `acknowledged` | Option[Ack] | `Ack(sessionId, at)` |
| `cases` | Vector[RgCase] | below |
| `history` | Vector[HistoryEntry] | FR-027; newest last in state, newest first on read |

`Limit(value: Long, setAt: Instant, actor: Actor)`. `Actor` = `player` | `operator(id)` | `system` |
`third_party`.

### RgCase

| Field | Type |
|---|---|
| `caseId` | String (minted) |
| `caseType` | `self_exclusion` \| `cool_off` \| `deposit_limit` \| `loss_limit` \| `session_limit` \| `reality_check` \| `account_block` |
| `trigger` | `player` \| `operator` \| `third_party` \| `system` |
| `status` | `open` \| `closed` |
| `openedAt`, `closesAt?`, `closedAt?` | Instant |
| `closeReason?` | `expired` \| `lifted` \| `closed` |
| `closeNote?` | *Personal* |
| `limitValue?`, `period?` | Long, String |
| `notes` | Vector[(at, operator, text: *Personal*)] |

### Events

`LimitSet(key, value, actor, at)`, `LimitRemoved(key, actor, at)`, `PendingStaged(key, value?, appliesAt,
actor, at)`, `PendingCancelled(key, at)`, `PendingApplied(key, value?, at)`, `BreakSet(until, caseId,
actor, at)`, `BreakLifted(operatorId, reason: Personal, at)`, `BreakEnded(at)`, `Excluded(permanent,
until?, trigger, caseId, actor, at)`, `ReturnRequested(reinstatesAt, actor, at)`, `Reinstated(at)`,
`PlayersAcknowledged(status, at)`, `RealityCheckSet(interval?, at)`, `Acknowledged(sessionId, at)`,
`CaseOpened(case, actor, at)`, `CaseNoted(caseId, operatorId, text: Personal, at)`, `CaseClosed(caseId,
reason, note: Personal?, actor, at)`, `ThirdPartyExcluded(caseId, result, at)`, `Erased(at)`.

### Commands and their refusals, in order

| Command | Refusals (code) | Effect |
|---|---|---|
| `SetLimit(type, currency?, value, actor=player)` | `unknown_limit_type`; `value_required`; `invalid_value` (not a positive whole number); `below_minimum_session_minutes` naming `minimumMinutes` (session) | tighter (the original's `isMoreRestrictive`: none active, or smaller): `LimitSet` + `PendingCancelled` if any; otherwise (larger or equal): `PendingStaged(appliesAt = now + 24 h)` replacing any |
| `SetLimit(…, actor=operator)` | the same, no minimum | `LimitSet` + `PendingCancelled` if any |
| `RemoveLimit(type, currency?, actor)` | player: none (the original stages a removal whether or not a limit exists); operator: `no_limit` | player: `PendingStaged(None, now + 24 h)`; operator: `LimitRemoved` + `PendingCancelled` |
| `ApplyPending(key, appliesAt)` (timer) | none: a pending whose `appliesAt` differs is a stale fire, no event | `PendingApplied` |
| `SetBreak(until, actor)` | `break_active` (player: only an operator may change it); `break_too_short` (< 1 h); `break_too_long` naming the maximum | `BreakSet` + `CaseOpened(cool_off)` |
| `LiftBreak(operatorId, reason)` | `no_break`; `reason_required` | `BreakLifted` + `CaseClosed(lifted)` |
| `EndBreak(until)` (timer) | none: stale `until` is no event | `BreakEnded` + `CaseClosed(expired)` |
| `Exclude(permanent, days?, actor)` | `already_excluded`; `period_too_short` naming `minimumDays: 183` and `providedDays` (operator period); `invalid_period` (unreadable; the original took it as permanent) | `Excluded` + `CaseOpened(self_exclusion)`; `playersTold = self_excluded` |
| `RequestReturn(actor)` | `not_excluded`; `exclusion_permanent`; `exclusion_not_ended` naming the end; `return_requested` (one pending) | `ReturnRequested(reinstatesAt = now + 24 h)` |
| `Reinstate(reinstatesAt)` (timer) | stale: no event | `Reinstated` (exclusion, break cleared) + `CaseClosed` for each open exclusion and break case; `playersTold = active` |
| `PlayersAck(status)` (sync timer) | — | `PlayersAcknowledged`; `playersTold = None` |
| `SetRealityCheck(interval?)` | `invalid_interval` with `allowed: [15, 30, 45, 60]` | `RealityCheckSet` |
| `Acknowledge(sessionId, own: Boolean)` | `session_not_found` (not the player's) | `Acknowledged` |
| `OpenCase(type, note, operatorId)` | `invalid_case_type` | `CaseOpened` (trigger operator) |
| `NoteCase(caseId, text, operatorId)` | `case_not_found` | `CaseNoted` |
| `CloseCase(caseId, reason, operatorId)` | `case_not_found`; `case_closed` | `CaseClosed(closed)` |
| `RecordThirdParty(result)` | none | `ThirdPartyExcluded` + `CaseOpened(self_exclusion, third_party, no end)`; no status change |
| `Erase` | none | `Erased`: every *Personal* blanked; values, types, times and statuses kept |
| `Get` | — | the state (a missing profile answers the empty state: nothing set) |

The endpoint checks the currency against the player's wallets (FR-006) from `UsageEntity`'s keys for the
player and the brand's currencies; an unknown currency is `invalid_currency`.

## Checks (pure)

Each answers from the profile, the brand's settings and the request's figures, in this order (FR-021):

| Check | Order |
|---|---|
| `may-log-in` | excluded → cooling off (`endsAt`) → allowed |
| `may-deposit` | excluded → cooling off (`endsAt`) → over limit: the first of daily, weekly, monthly that `sum + amount` passes, naming `period`, `limit`, `used`, `remaining` → allowed |
| `may-bet` | excluded or cooling off → `locked` (game-provider and the wallet read `locked`, `excluded`, `cooling_off`) → loss limit set and sums absent → `unavailable` → `spending_limit` (period, limit, remaining) → allowed |
| `may-play` | excluded (`reason: self_excluded`) → cooling off (`reason: cooling_off`, `coolingOffUntil`) → session limit reached when `used ≥ limit` (`limitMinutes`, `usedMinutes`) → allowed (`sessionLimitMinutes`, `minutesUsed`) |

A limit is "passed" when `amount > limit − used` (the original's `notify/deposit`); exactly at the
remainder is allowed (scenario 27, `ExactlyAtRemainingAccepted`). Session minutes are whole minutes from
start to end (or now), sessions started before midnight UTC not counted, as the original's
`calculateSessionMinutesToday`.

## UsageEntity (key value, id = `player/currency`)

`deposits: Vector[(at, amount)]` for 30 days (exact); `lossMinutes: Map[minute, Long]` for 24 h;
`lossHours: Map[hour, Long]` for 30 days; `seen: bounded set of transaction ids` (a repeat is a no-op).
Folded by `UsageConsumer` from `money.transactions` (`DepositConfirmed` → deposit; the wallet's loss type →
loss). Read for display only (R7).

## SessionDayEntity (key value, id = `player/yyyy-mm-dd`)

`closedMinutes: Long`, `open: Map[sessionId, startedAt]`, `sessions: Set[sessionId]` (for FR-024). Folded
by `SessionMinutesConsumer`: `game_session_started` adds to `open`; `game_session_ended` moves it to
`closedMinutes` (end − start in whole minutes, as the original). `minutesUsed(now)` =
`closedMinutes` + Σ open (now − start).

## BrandSettingsEntity (key value, id = brand)

`minSessionMinutes: Int = 60`, `maxBreakDays: Int = 42`.

## Timers

| Name | When | Calls |
|---|---|---|
| `rg-pending-<player>-<type>[-<currency>]` | applies-at | `ApplyPending(key, appliesAt)` |
| `rg-break-<player>` | the break's end | `EndBreak(until)` |
| `rg-return-<player>` | request + 24 h | `Reinstate(reinstatesAt)` |
| `rg-sync-<player>` | at once, then every 30 s until acknowledged | `players` `PUT`/`DELETE …/{id}/exclusion`, then `PlayersAck(status)` |

No personal value in a name.

## Consumers

| Consumer | From | To |
|---|---|---|
| `UsageConsumer` | `money.transactions` | `UsageEntity` |
| `SessionMinutesConsumer` | `casino.sessions` | `SessionDayEntity` |
| `RgPublisher` | `ProfileEntity` events | `casino.players`: `rg_limit_applied`, `rg_case_opened`, `rg_case_closed` |
| `AuditPublisher` | `ProfileEntity` events by an operator | `platform.audit` |

## Invariants

- At most one pending change per key; an active break and an exclusion may coexist (FR-021).
- A break's `until` is in the future when set; a timer exists for every break and pending change.
- `playersTold` is set by every exclusion and reinstatement and cleared only by `players`' acknowledgement.
- No refusal or check reads `UsageEntity`.

## Transitions

```text
no break  --SetBreak-->  on break  --LiftBreak | EndBreak-->  no break
not excluded --Exclude--> excluded --RequestReturn (period ended)--> return requested --Reinstate (24 h)--> not excluded
pending(key) --ApplyPending | a tighter SetLimit | an operator SetLimit--> none
```
