# Research: responsible-gambling

Decisions for spec 010, each with what it rests on. The original is `~/Documents/projects/eitheror/eitheror-platform`
at the commit the recordings are made from; paths below are relative to it unless they start with
`services/` or `specs/` (this repository).

## R1. The recordings: the Go suite's scenarios 26 and 27, through `e2e-rg-kyc.patch`

**Decision**: the recordings are the 37 responsible-gambling tests `tests/original/e2e-rg-kyc.patch` adds to
the original's Go suite (scenario 26: limits, 13 tests; scenario 27: breaks, exclusion, play and deposits,
24 tests), run against a local original with `e2e-recording.patch`, one recording per test, under
`tests/port/responsible-gambling/`. `tests/original/e2e-rg-kyc.md` maps each test to its feature scenario
and says which parts skip as the original's defects. One parity test per recording.

**Rationale**: spec 001 FR-030 puts the RG scenarios in the Go suite before the service is recorded, and the
patch was written for that (it adds files only and reuses the suite's helpers). The spec's own *Recordings*
names the same routes (`RgRoutes`, `BackofficePlayerRoutes`, launch, login, `notify/deposit`). The suite's
original RG tests (`TestScenario7_CasinoGate3_RGEnforcement`, `TestScenario9_*`) all skip and record nothing.

**What it cannot record** (`e2e-rg-kyc.md`, *Not covered*): session-limit launch answers, bets refused as
locked, RG-unreachable answers, reality checks, facts, case notes and audits, the brand minimum change. These
scenarios are proved by the integration and feature suites against fakes, and named so in the plan's
*Divergences*. The defects the patch skips (a staged loosening never applies, a break never ends, a
reinstatement leaves the flag, a deposit is credited before the check) are this spec's *Changes*: the
recording is kept as the original's evidence and the parity test asserts this spec's behaviour, marked.

**The run needs the user**: the original's kind stack runs beside, not inside, the ankka installation, and
stopping ankka and the TinyGo pin are the user's (memory: *Recording the original on kind*). Until the run,
`ParitySuite` is `.ignore`d, as every service here started.

**Alternatives**: a Go driver of our own, as 008 wrote (R1 there): not needed, because the patch already
drives every route the original has for RG.

## R2. One event sourced profile per player, cases on it

**Decision**: `ProfileEntity` (event sourced, id = player id) holds the limits by type and currency, at most
one pending change per type, the break, the exclusion, the return request, the reality-check interval and
last acknowledgement, the cases (open and closed) with their notes, and the history. Every command is one
fold: a break and its case open in one event; a reinstatement clears the exclusion and the break and closes
the case in one.

**Rationale**: every rule of FR-002 to FR-024 reads the profile and the cases together (an exclusion takes
precedence over a break, FR-021; lifting a break closes its case, FR-016; a reinstatement clears both,
FR-020). One aggregate makes each transition atomic with no saga. The cases are few per player (one per
intervention, a human's action) and bounded by people, not traffic. One subject for erasure: the notes and
reasons are `Personal` under the player (FR-032), blanked by one `Erase`.

**Alternatives**: a `CaseEntity` per case, as the original's RGCase twins: opening a break would be two
writes that must agree, and the expiry function's mismatch (the case `pending`, the matcher `active`) is
exactly the bug that split gives. Rejected.

## R3. Limits: the more-restrictive rule as a pure function; pending changes apply by timer

**Decision**: `Limits` (pure) holds the original's `isMoreRestrictive` (a smaller value, or any value where
none applies, is tighter; a larger value or a removal is looser) and the staging: a player's tighter change
applies at once and cancels the type's pending change; a looser one replaces the pending change with
applies-at now + 24 h; an operator's applies at once and cancels the pending change (FR-002, FR-004). A
pending change schedules a timer named `rg-pending-<player>-<type>` at applies-at (ankka 032), so a further
change replaces it by name (FR-005). The timer calls `ProfileEntity.ApplyPending(type, appliesAt)`; a
`appliesAt` that no longer matches the state's is a stale fire and changes nothing.

**Rationale**: the original's rule and its 24 h are kept (*Kept unchanged*); the original stages and never
applies (`rg-pending-apply` is not registered), so the timer is the change. Naming the timer by player and
type gives replace-by-name for free; the stale-fire check covers a timer that fires in the instant a change
replaced it.

**SC-003** (within one minute of applies-at): the timer runtime's poll is seconds; `ApplyPending` is one
command.

## R4. Breaks, exclusions and the return: three timers, one entity

**Decision**:

- A break (FR-014): `SetBreak(until, actor)` checks one hour ≤ until − now ≤ the brand's maximum (default 42
  days), opens a cool-off case, and schedules `rg-break-<player>` at `until`. The player cannot end,
  shorten or replace an active break (FR-015: `break_active`, naming that only an operator may). An
  operator's `LiftBreak(reason)` closes the case `lifted` and cancels the timer (FR-016). The timer's
  `EndBreak(until)` closes the case `expired` (FR-017); a stale `until` changes nothing.
- An exclusion (FR-018, FR-019): permanent (player or operator) or a period of at least 183 whole days
  (operator). The entity records it and opens a self-exclusion case; the endpoint then tells `players`
  `PUT /v1/casino/brands/<slug>/players/{id}/exclusion` (spec 008 FR-032, built on 008's branch), which
  sets `self_excluded` and ends the sessions. `players` produces `player_self_excluded`.
- The return (FR-020): `RequestReturn(actor)` is refused for a permanent exclusion and before the period's
  end; otherwise it records the request and schedules `rg-return-<player>` at request + 24 h. The timer's
  `Reinstate` clears the exclusion and any break, closes the case, and the timed action tells `players`
  `DELETE …/{id}/exclusion`.

**Telling `players`**: the exclusion is recorded first and `players` told after; a failed call leaves the
profile excluded and `players` not yet told. Every check here answers from the profile, so the player is
already refused deposit, play, bet and log-in (`may-log-in` answers `excluded`); only the account status
lags. A `PlayersSync` timed action retries the call (named per player, every 30 s until answered), so the
status converges without an operator. The same for the reinstatement's `DELETE`.

**Alternatives**: a workflow per exclusion (record → tell `players`): a workflow keyed by player would carry
the same retry, and a second exclusion after a reinstatement would need a new workflow id. The timer keyed by
player, rescheduled until done, is smaller and has the same guarantee.

## R5. The checks: one entity read, the figures from the request, no call out

**Decision**: `may-deposit`, `may-bet`, `may-play` and `may-log-in` each read `ProfileEntity.Get` (one
entity read) and decide with the pure `Checks` from the profile and the figures in the request, in the
original's order: excluded, then cooling off (naming the end), then the limit (FR-007 to FR-010, FR-021).
`may-play` adds the minutes used today from `SessionMinutesView` (below). A missing profile is a player with
nothing set: allowed. FR-011's "unavailable" is the caller's: a timeout or a 5xx from here is a refusal
there, which every caller already does (wallet `RG_UNAVAILABLE`; game-provider `service_unavailable`;
players `rg_unavailable`).

**`may-play`'s minutes** are a view (`SessionMinutesView` over `casino.sessions`), seconds behind. The spec's
Clarifications accept a view for display only, but FR-008 names this view for the gate, and `players`
enforces the running session's limit by its own timer (spec 008 FR-019). The lag lets a launch through in
the seconds after a session closes past the limit; the running session is then ended by `players`. Recorded
as accepted, not hidden.

**SC-004** (p99 under 100 ms): one entity read and, for `may-play`, one view row; no call out.

## R6. Loss limits at bet: the sums are not yet carried

**Finding**: FR-010 has the wallet carry loss sums to `may-bet`. As built:

- `may-bet` is asked by `game-provider`'s `BetWorkflow`, not the wallet
  (`services/game-provider/.../clients/Neighbours.scala`: `POST /v1/casino/brands/<b>/rg/may-bet` with
  `{playerId, currency, amount}`), and it reads `allowed | locked | excluded | cooling_off`, nothing else;
- the wallet keeps `recentDeposits` for 30 days and answers deposit sums; it keeps no loss sums.

**Decision**: `may-bet` takes `lossLast24h`, `lossLast7d`, `lossLast30d` as optional fields. With no loss
limit set, they are not needed. With a loss limit set and the sums carried, a stake that takes a window past
its limit is refused `spending_limit` naming the period, limit and remaining (FR-010). With a loss limit set
and the sums not carried, the answer is `unavailable` (FR-011: a check that cannot be answered is a refusal).
Game-provider reads an unknown decision as `service_unavailable` and ends the bet `error`, the wallet never
called: fail closed.

**Consequence**: until spec 002 keeps loss sums (as `recentDeposits`, from settled losses) and spec 003's
bet workflow reads and carries them, a player with a loss limit cannot bet. That is the spec's rule (a limit
that never applies is a liability, Clarifications 2026-10-25), and the plan names the two changes under
*Elsewhere*. **Open for the user**: whether to ship 010 failing closed for loss-limited players until 002
and 003 carry the sums, or to make the two changes first.

## R7. Usage for display: a consumer of `money.transactions` into minute and hour buckets

**Decision**: `UsageConsumer` reads `money.transactions` and folds deposits and losses into `UsageEntity`
(key value, id = `player/currency`): exact entries for deposits (few; 30 days, as the wallet's
`recentDeposits`), and losses in minute buckets for the last 24 hours and hour buckets to 30 days. The
player's and operator's reads take the three windows from it (FR-012).

**Rationale**: the figures are for display and never decide (FR-007, FR-012). Deposits are kept exactly so
the shown deposit usage equals the wallet's sums to the minor unit (SC-007), the original's 100-row
truncation being the reason exactness matters. Losses can be thousands a day; buckets bound the state (1,440
+ 720 entries at most) at the cost of the weekly and monthly loss edges being exact to the hour, the daily to
the minute. Recorded as a divergence the display can carry.

**Alternatives**: one row per transaction in a view (unbounded); asking the wallet for the read (the wallet
answers deposit sums only, and constitution IV prefers the view).

## R8. Session minutes: a consumer of `casino.sessions` into an entity per player and UTC day

**Decision**: `SessionMinutesConsumer` reads `game_session_started` and `game_session_ended` from
`casino.sessions` and folds them into `SessionDayEntity` (key value, id = `player/yyyy-mm-dd`): closed
minutes and open sessions' start times. `may-play` reads it and counts an open session to now (FR-008). A
session that starts before midnight counts on its starting day only, so it adds nothing to the next day's
minutes, as the original (`CS/SessionRoutes.scala` `calculateSessionMinutesToday` lists sessions *started*
since 00:00 UTC, whole minutes). A failed read is the caller's refusal, as the original's `Int.MaxValue`.

## R9. Facts and audit: consumers of the profile's events

**Decision**: `RgPublisher` folds `ProfileEntity` events into `casino.players`: `rg_limit_applied` (with
`causedBy` set | lowered | applied | removed, and the currency), `rg_case_opened`, `rg_case_closed`
(FR-030). `AuditPublisher` writes a `platform.audit` record for every operator change (FR-028), naming the
operator, the player, the action and before and after; a note or a free-text reason is named, never its text
(spec 018 FR-003, FR-032 here). The CloudEvents id is the event's id, as every service here.

**Contract changes** (additive): `rg_limit_applied` gains `currency: Option[String]` (FR-030 names it; the
family has none); `rg_case_opened` gains `permanent: Option[Boolean]` for a self-exclusion (FR-030: whether
permanent or the period's end; `closesAt` alone cannot tell a permanent exclusion from a GAMSTOP case).

## R10. History: the profile's own list

**Decision**: the profile keeps `history`: every change and case event with type, old and new values, actor
and time, newest first on read (FR-027). It is in the entity because it is the profile's own record and
grows by people's actions only.

## R11. Reality checks

**Decision**: `SetRealityCheck(interval | none)` with interval in {15, 30, 45, 60} (FR-023);
`Acknowledge(sessionId)` refuses a session that is not the player's, read from `SessionDayEntity`'s sessions
of the last day (FR-024). The firing is the game page's.

## R12. GAMSTOP: by player id, not address

**Decision**: `POST …/rg/gamstop-refusal` takes `{playerId, result}` and records a self-exclusion case with
trigger third party, no end, no status change (FR-022). **Change to 008**: `players`' realm route resolves
the address the authenticator sends to the player and passes the id; this service holds no address
(constitution V: no personal field it does not need). An address with no player is dropped by `players`
with a log line.

## R13. Settings

**Decision**: `BrandSettingsEntity` (key value, id = brand): `minSessionMinutes` (default 60, FR-029),
`maxBreakDays` (default 42, FR-014). An operator changes them on the operator endpoint; a change binds the
next player change and alters no existing limit.

## R14. Routes under 0.13's limits

**Decision**: four endpoints, one prefix each, the slug a literal, at most two path parameters:

- `PlayerEndpoint` at `/v1/casino/rg/<slug>` (the original's prefix), `Routes.player`, the player's own id
  in the path checked against the token;
- `OperatorEndpoint` at `/v1/casino/backoffice/<slug>/rg` (as 008 moved the operator block), `Routes.operator`;
- `ServiceEndpoint` at `/v1/casino/brands/<slug>/rg` (the path the wallet, game-provider and players already
  call), `Routes.serviceOf` per route;
- none public: the realm reaches `players`, never this service (FR-009, FR-022).

`contracts/routes.md` lists them; `contracts/wire.md` translates the original's paths for the replay.

## R15. Erasure and export

**Decision**: `POST /erasure/players/{id}` and `GET /exports/players/{id}` on the service endpoint, admitted
for `players` (spec 008's `ErasureWorkflow` and `ExportWorkflow` call every brand service). Erasure blanks
the notes and reasons (`Personal`), keeps every limit, case type, time and status (FR-032) and answers a
certificate. **Change to 008**: `ErasureWorkflow.targets` gains `responsible-gambling`.

## R16. What the original's surface read showed (2026-10-27)

Read from the code (`contracts/surface.md` has every element and its disposition). Beyond the spec's
*Changes*, the read found:

- `isMoreRestrictive` treats an equal value as not tighter, so setting the active value again stages it;
  kept (the staged change applies to the same value, harmless).
- A player's `DELETE /limits/{type}` stages a removal whether or not a limit exists; kept.
- The operator's `parseDays` accepts an unreadable period as permanent; changed to `invalid_period`, since
  an operator's typo must not exclude a player for ever.
- ST8's `isPlayerLocked` reads a `status` property players are never written with, so it never fires in
  production; the lock is `may-bet` from the profile.
- The deposit and launch refusals name cooling-off differently (`account_restricted`/`cooling_off_active`
  and `cooling_off_active`); the callers keep their own bodies (wallet `COOLING_OFF`, launch
  `cooling_off_active`), so nothing here has to reproduce either.
- The original has no operator route to open or note a case, and nothing writes `RGCase.notes`; the two
  routes are new (FR-026).
- `export-rg-case` stamps a closed case's `occurred_at` with `closes_at`; here it is the close's time.

## Findings

*Written at task time.*

## For ankka (checked against 0.13.0)

- Timers by name with replace (032): in 0.13.
- `Personal` and erasure (042): not in 0.13; the marker and the redaction are this service's, as 008's R8.
