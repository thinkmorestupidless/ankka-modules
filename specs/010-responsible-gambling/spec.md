# Feature Specification: responsible-gambling — Limits, Breaks and Exclusions a Player Can Rely On

**Feature Branch**: `010-responsible-gambling`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The brand's `responsible-gambling` service: deposit, loss and session
limits with the 24-hour rule, cooling-off and self-exclusion, reality checks, the checks the wallet,
`players` and `game-provider` ask before money moves or play starts, the timers that apply pending
changes and end breaks, the facts engagement and reporting read, and the operator's view and
overrides — rebuilt from the original's `RgRoutes`, `BackofficePlayerRoutes`, the deposit notify
route, the session launch preflight, the Keycloak authenticators and the RG TL functions."

## Context

The original keeps a player's responsible-gambling state as properties on the player twin
(`rg_limit_<TYPE>`, `rg_pending_<TYPE>`, `rg_pending_<TYPE>_at`, `rg_is_cooling_off`,
`rg_cool_off_until`, `rg_cool_off_set_at`, `self_excluded_at`, `reality_check_interval_minutes`) and
as RGCase twins, and enforces it from five places that do not agree with each other:

- **`RgRoutes`** (player routes under `/v1/casino/rg/{brand}/{player}/…`): seven limit types
  (`DEPOSIT_DAILY|WEEKLY|MONTHLY`, `LOSS_DAILY|WEEKLY|MONTHLY`, `SESSION_MINUTES`); a smaller value
  applies at once and clears any pending loosening, a larger value or a removal is staged with
  `applies_at` 24 hours out; `SESSION_MINUTES` must be a positive integer at or above the brand's
  minimum (60 unless the brand set one); used amounts are six wallet sums over rolling windows of
  the last 1, 7 and 30 days, not calendar periods; cooling-off is set with an `until` and is not
  removable by the player; permanent exclusion sets `account_status = self_excluded` and a cool-off
  to the year 9999; reality-check interval is one of 15, 30, 45, 60 minutes, and a dismissal is
  recorded with the session id; history lists RGCase twins only, never limit changes.
- **`BackofficePlayerRoutes`** (operator): sets or removes any limit at once with no pending stage,
  no case and no notification; sets and lifts cooling-off; self-excludes for a period of at least
  183 days or permanently, writing `rg_is_cooling_off = true` beside the status; reinstates a
  suspended player without clearing the cooling-off flag; closes a case.
- **Login**: a Keycloak authenticator asks the substrate for the twin and refuses a self-excluded or
  cooling-off player, failing closed; `players`' session issue refuses `self_excluded`, `suspended`
  and `blocked` but not cooling-off. A second authenticator asks GAMSTOP by email, failing closed.
- **Launch** (`SessionRoutes`): refuses a restricted status, an unmet KYC play gate, cooling-off
  (flag or a future `until`), and a `SESSION_MINUTES` limit reached by sessions started this UTC
  day, open ones counted to now; fails closed.
- **Bet** (`St8Routes`): answers `player_locked` for a suspended, self-excluded or cooling-off
  player, reading the flag over HTTP and failing *open* on any transport error.
- **Deposit**: the limit, cooling-off and self-exclusion checks live on the internal
  `notify/deposit` route, which `deposit-on-payment-capture-fn` calls *after* the wallet has
  credited the deposit; a refusal is answered `403`, which the caller logs as a warning and
  ignores. In production, no deposit is ever refused for a limit, a break or an exclusion.

Three TL functions exist for the rest and none runs: `rg-pending-apply` is not registered by the
chart, so a staged loosening never applies by itself; `rg-cool-off-expiry` is registered but
disabled (`rgCoolOffExpiry.enabled: false`), matches only cases in status `active` while both
routes create them `pending`, and its capability reinstates the account without clearing
`rg_is_cooling_off`; the deposit- and loss-limit monitors and the session-duration check have empty
bodies. Loss limits are set, shown and never enforced (Playwright `41-rg-session-limit` records
this as a documented gap). Every Go end-to-end test of these rules is skipped.

This spec is the service that makes these rules one thing. The rules are the service's own code,
on event sourced entities and ankka's timers; the observable behaviour of the player and operator
routes is the original's; the enforcement is what the original's specs 071, 104 and 108 say it
should be and the code does not do.

## Clarifications

### Session 2026-10-08

- Q: Are the rules code with timers, or TL function packs? → A: The service's own command handlers
  and ankka timers (032). Whether TL survives as a sold feature is spec 001's open question; if it
  does, the same rules can be expressed as a pack over this service's facts, and the Assumptions say
  what that would need. Nothing here waits on that answer.
- Q: Where is a deposit checked, and by whom? → A: In the wallet's deposit command, before the
  credit, by a call to this service's `may-deposit` route within the project. The wallet carries the
  player's deposit sums over the last 1, 7 and 30 days in the call (spec 002 FR-030 keeps them
  exact), so this service decides from its own profile and never calls the wallet back on the
  deposit path. A refusal is final to `merchant` (spec 004): the payment stays captured and not
  credited, and is refunded (FR-013).
- Q: Does this service compute usage from facts or ask for it? → A: Both, for different purposes.
  A decision that gates money or play is made from an exact figure carried in the request (deposit
  sums from the wallet) or from this service's own state (session minutes from its view of
  `casino.sessions`, limits and breaks from the profile). The figures shown to a player and an
  operator, and the reports, come from this service's views of `money.transactions` and
  `casino.sessions`. A view that is seconds behind is acceptable for a display and not for a gate.
- Q: Who owns self-exclusion, the profile or `players`? → A: This service decides and records it; the
  player's account status is `players`' (spec 008). This service tells `players` over HTTP within
  the project to set `self_excluded`, and `players` refuses login on the status as today. Cooling-off
  is this service's alone and changes no account status, as the original's decision R5 says.
- Q: Who answers "may this player log in"? → A: This service, on `may-log-in`, for `players`, which
  asks at session issue and on behalf of the casino's Keycloak RG authenticator (*as reconciled at
  reconciliation 2: the realm talks to `players` only, spec 001 FR-022, and registers no machine*).
  Both fail closed. GAMSTOP stays in Keycloak as the original has it (spec 001 FR-019), and a GAMSTOP
  refusal reaches this service through `players` as a third-party exclusion for the record.
- Q: Rolling windows or calendar periods? → A: Rolling, as the original: the last 24 hours, 7 days
  and 30 days from the moment of the check. Session minutes are the UTC calendar day, as the
  original. Changing either is a regulatory decision, not a port's.
- Q: Does a cooling-off end by itself? → A: Yes, by a timer at `until`, which clears the break and
  closes its case. The original never does (the function is disabled, mis-matched and incomplete);
  the original's spec 104 US4 scenario 3 says it must, and it is listed as a change.
- Q: Does a pending loosening apply by itself? → A: Yes, by a timer per player and limit type at
  `applies_at`, replaced by name on a further change. The original stages it and never applies it.
- Q: What ends a self-exclusion with a period? → A: Nothing automatic. After the period an
  operator may reinstate; before it the service refuses. A permanent exclusion is never reinstated
  by this service. Settled 2026-10-25: the player asks to return and is reinstated 24 hours later;
  an operator may record the request, the day still runs (FR-020).
- Q: Are loss limits enforced? → A: The original sets and shows them and enforces nothing. This spec
  enforces them at bet time (settled 2026-10-25): the wallet asks `may-bet` with the loss sums and a
  bet that would cross a limit is refused `spending_limit` (FR-010).
- Q: What happens to a deposit the limit refuses, now that it is refused before credit? → A: The
  money is with the provider; `merchant` sees a final refusal and refunds (spec 004), and the player
  is told (FR-013). Settled 2026-10-25: automatic, not held for an operator; a refund fee is the
  operator's cost of a limit it let the provider reach.
- Q: Fail open or closed? → A: Closed, everywhere. The original fails closed at login and launch,
  open at bet and deposit. A check this service cannot answer is a refusal, and the refusal says
  so.

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: Does the realm reach this service? → A: No. It asks `players` (spec 001 FR-022, spec 008
  FR-013), which asks `may-log-in` here within the project and fails closed; no machine registration
  under ankka 040 (FR-009, FR-022).
- Q: Who produces `player_self_excluded`? → A: `players`, on the status this service commands
  (FR-018, FR-019, FR-030); this service produces the case facts. An operator excludes a player on this
  service's route only.
- Q: Does `players` ask here for the session timer? → A: No: it keeps the limit from
  `rg_limit_applied` and counts its own sessions (spec 008 FR-019); `may-play` carries the limit and
  minutes used for `game-provider` (FR-008).
- Q: Did every caller take its check? → A: Yes: the wallet asks `may-deposit` before crediting and
  `may-bet` before funding (spec 002 FR-022, FR-007a), `game-provider` asks `may-play` (spec 003
  FR-007), `players` asks `may-log-in` (spec 008 FR-011) and may pre-check `may-deposit` at initiation
  (spec 008 FR-043), and `merchant` treats a refusal as final (spec 004 FR-021).

### Session 2026-10-25

The markers left open for product and regulatory decisions, settled with Trevor before 008 is planned.

- Q: After a period exclusion ends, how does the player return? → A: They ask, and are reinstated
  24 hours later by a timer; an operator may record the request for them and the day still runs
  (FR-020).
- Q: Are loss limits enforced? → A: Yes, at bet time: the wallet's `may-bet` carries the loss sums
  and a bet that would cross a limit is refused `spending_limit`; a limit a player can set but that
  never applies is a liability (FR-010; spec 003 FR-012's step carries the sums spec 002 FR-030 gives it).
- Q: A deposit refused at the limit, money already with the provider? → A: `merchant` refunds
  automatically and the player is told; nothing waits on an operator (FR-013).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player sets, lowers, raises and removes a limit (Priority: P1)

A player opens their limits and sets a daily deposit limit. Lowering it applies at once. Raising
or removing it is staged and applies 24 hours later unless they tighten it again first. They see
what applies, what is pending and when, and what they have used.

**Why this priority**: The 24-hour rule is the regulatory core of limits; the original stages the
change and never applies it, so a player who raised a limit today is held to the old one forever
or until an operator notices.

**Independent Test**: Set, lower, raise and remove each limit type on one player and read the
profile after each step and after the timer fires.

**Acceptance Scenarios**:

- added `features/responsible-gambling/limits.feature`: a first daily deposit limit applies at once and shows with the amount used in the last day
- added `features/responsible-gambling/limits.feature`: lowering a deposit limit applies at once and cancels any pending change for it
- added `features/responsible-gambling/limits.feature`: raising a deposit limit keeps the old one and applies the new one a day later
- added `features/responsible-gambling/limits.feature`: removing a deposit limit keeps it for a day and then no limit applies
- added `features/responsible-gambling/limits.feature`: a new raise while one is pending replaces the pending change and restarts its day
- added `features/responsible-gambling/limits.feature`: a session limit below the brand's minimum is refused naming the minimum
- added `features/responsible-gambling/limits.feature`: a session limit at the brand's minimum is accepted
- added `features/responsible-gambling/limits.feature`: a session limit that is not a positive whole number is refused
- added `features/responsible-gambling/limits.feature`: a weekly loss limit shows with the losses of the last week and refuses the bet that would cross it
- added `features/responsible-gambling/limits.feature`: a limit change by the player is recorded in the history and published as a limit applied fact

---

### User Story 2 - A deposit is refused before it is credited (Priority: P1)

A player with a daily limit of 100 who has deposited 80 today tries to deposit 30. The wallet asks
this service before crediting and the deposit is refused, naming the remaining 20. A player on a
break or excluded is refused whatever the amount.

**Why this priority**: Today the deposit is credited and the refusal is a log line. This is the
one place the port changes what money does, and it is the change the original's own spec 104
required.

**Independent Test**: Drive deposits through the wallet's granted route with the sums the wallet
carries, and assert the credit never happens on a refusal.

**Acceptance Scenarios**:

- added `features/responsible-gambling/deposits.feature`: a deposit over the daily deposit limit is refused naming the limit, what was used and what remains, and no balance changes
- added `features/responsible-gambling/deposits.feature`: a deposit exactly at what remains of the daily deposit limit is accepted
- added `features/responsible-gambling/deposits.feature`: a deposit a minor unit over what remains of the daily deposit limit is refused
- added `features/responsible-gambling/deposits.feature`: a deposit that would pass any of the daily, weekly or monthly deposit limits is refused naming the first it would pass
- added `features/responsible-gambling/deposits.feature`: a deposit for a player on a break is refused as cooling off naming the end of the break
- added `features/responsible-gambling/deposits.feature`: a deposit for a self-excluded player is refused as excluded
- added `features/responsible-gambling/deposits.feature`: a deposit for a player whose break ended is not refused for the break
- added `features/responsible-gambling/deposits.feature`: a deposit while responsible gambling cannot be reached is refused as unavailable and credits nothing
- added `features/responsible-gambling/deposits.feature`: a deposit refused for a limit is recorded captured and not credited, and the player is told the remaining allowance

---

### User Story 3 - Play and login are refused while a break or an exclusion stands (Priority: P1)

A player on a break cannot log in, launch a game or place a bet until the break ends; an excluded
player never can. A player at their session limit for the day cannot launch.

**Why this priority**: A break that can be played through is no break. The original enforces
these at login and launch and fails open at bet.

**Independent Test**: Put a player on a break and drive login, launch and a bet; end the break by
timer and drive them again.

**Acceptance Scenarios**:

- added `features/responsible-gambling/play.feature`: a sign-in by a player on a break is refused with the date the break ends, by the brand and by the realm alike
- added `features/responsible-gambling/play.feature`: a sign-in by a self-excluded player is refused as permanently excluded, directing them to support
- added `features/responsible-gambling/play.feature`: a launch for a player on a break is answered cooling off with the end of the break and refused
- added `features/responsible-gambling/play.feature`: a player whose sessions today total their session limit is answered session limit reached
- added `features/responsible-gambling/play.feature`: a player under their session limit is answered may play
- added `features/responsible-gambling/play.feature`: a session started yesterday does not count against today's session limit
- added `features/responsible-gambling/play.feature`: a bet for a player on a break or self-excluded is refused as locked with the balance
- added `features/responsible-gambling/play.feature`: a caller asking responsible gambling while it cannot be reached is answered unavailable and refuses
- added `features/responsible-gambling/play.feature`: a player whose break ended is refused nothing for the break

---

### User Story 4 - A player takes a break or excludes themselves; an operator does the same for them (Priority: P1)

A player sets a break until a date and cannot shorten it. A player excludes themselves permanently
in one confirmed action. An operator sets a break, lifts one, excludes for at least six months or
permanently, and reinstates after the period.

**Why this priority**: Self-exclusion is the player's strongest right; the original records it but
leaves a reinstated player blocked by a flag and never ends a break.

**Independent Test**: Walk each transition and read the profile, the case, the history and the
facts after each.

**Acceptance Scenarios**:

- added `features/responsible-gambling/breaks.feature`: a break set by the player applies at once, opens a case with its end and publishes one case opened fact
- added `features/responsible-gambling/breaks.feature`: a player asking to end or shorten their break is refused
- added `features/responsible-gambling/breaks.feature`: a break ends by itself when its end passes and its case closes as expired
- added `features/responsible-gambling/breaks.feature`: an operator lifting a break with a reason ends it and closes the case naming them
- added `features/responsible-gambling/breaks.feature`: a player excluding themselves permanently is excluded at once with every game session ended
- added `features/responsible-gambling/breaks.feature`: an operator's exclusion shorter than 183 days is refused naming the minimum
- added `features/responsible-gambling/breaks.feature`: an operator's exclusion of 183 days or permanent is accepted
- added `features/responsible-gambling/breaks.feature`: a request to return before an exclusion's period ends is refused naming the end
- added `features/responsible-gambling/breaks.feature`: a request to return after an exclusion's period ends reinstates the player 24 hours later
- added `features/responsible-gambling/breaks.feature`: an operator recording a player's request to return starts the same day's wait
- added `features/responsible-gambling/breaks.feature`: a request to return from a permanently self-excluded player is refused
- added `features/responsible-gambling/breaks.feature`: a player both excluded and on a break is answered with the exclusion
- added `features/responsible-gambling/breaks.feature`: a break ending on a suspended player leaves them suspended

---

### User Story 5 - A reality check is set, fires and is acknowledged (Priority: P2)

A player chooses a reality-check interval; the game page pauses play at that interval until they
acknowledge, and the acknowledgement is recorded.

**Why this priority**: Required to be offered and player-set; cheap; the original already stores
it server-side.

**Independent Test**: Set each interval, read the profile, record an acknowledgement, remove the
preference.

**Acceptance Scenarios**:

- added `features/responsible-gambling/reality-checks.feature`: a reality check interval of an allowed value shows on the profile
- added `features/responsible-gambling/reality-checks.feature`: a reality check interval of a value not allowed is refused naming the allowed values
- added `features/responsible-gambling/reality-checks.feature`: an acknowledgement naming the player's game session is recorded with the time and the session
- added `features/responsible-gambling/reality-checks.feature`: removing the reality check leaves the profile showing none and no check fires
- added `features/responsible-gambling/reality-checks.feature`: a game run with no reality check set fires no check

---

### User Story 6 - An operator sees and changes a player's protections (Priority: P2)

An operator opens a player's responsible-gambling tab: limits with usage, pending changes, the
break, the exclusion, the cases and the history. Operator changes apply at once and are recorded
against the operator.

**Why this priority**: Operators act on regulator and support requests; every action must be
attributable.

**Independent Test**: Drive each operator action and read the player's history and the audit.

**Acceptance Scenarios**:

- added `features/responsible-gambling/operators.feature`: an operator setting a limit with a pending raise applies it at once, cancels the pending change and is named in the history
- added `features/responsible-gambling/operators.feature`: an operator's session limit below the brand's minimum is accepted, because the minimum binds players and not operators
- added `features/responsible-gambling/operators.feature`: an operator removing a limit leaves no limit of that type at once
- added `features/responsible-gambling/operators.feature`: a case an operator opened with a note and closed with a reason shows both, the note as personal data
- added `features/responsible-gambling/operators.feature`: an operator's action on a player's protections is audited with the before and after
- added `features/responsible-gambling/operators.feature`: a change to the brand's minimum session limit binds the next player change and alters no existing limit

---

### User Story 7 - The rest of the brand learns what changed (Priority: P2)

`engagement` suppresses marketing to an excluded or cooling-off player, `reporting` counts
exclusions and limits, `support-agent` sees the state, and the regulator's export has every case.

**Why this priority**: The facts are the only way other services may know; the original pushes
CRM events and exports RGCase twins.

**Independent Test**: Make each change and read the facts the brand's topics carry.

**Acceptance Scenarios**:

- added `features/responsible-gambling/facts.feature`: a limit applied, lowered, raised by timer or removed is published as a limit applied fact
- added `features/responsible-gambling/facts.feature`: a break set or an exclusion applied is published as a case opened fact with its type, trigger and end
- added `features/responsible-gambling/facts.feature`: a case that ends is published as a case closed fact with its reason
- added `features/responsible-gambling/facts.feature`: a self-exclusion is published for the player and the brand's engagement sends them nothing but the confirmation
- added `features/responsible-gambling/facts.feature`: a national register refusal at sign-in is recorded as a self-exclusion case with a third party trigger and exported with the rest
- added `features/responsible-gambling/facts.feature`: an erased player's protection facts keep their amounts, dates, types and statuses and lose any note

---

### Edge Cases

- A limit set in a currency other than the player's wallet currency: refused; a limit is in the
  wallet's currency, and a player with two wallets sets a limit per currency.
- A deposit sum the wallet carries that is older than one second: the wallet's sums are exact at the
  moment it builds the call (spec 002 FR-030); this service does not re-check them.
- Two deposits racing against the same headroom: each is decided inside the wallet's command for
  the wallet (one entity per player and currency), so the second sees the first.
- A pending change whose timer fires while the player is on a break: it applies; a break does not
  stop a limit from changing.
- A break set to a time in the past: refused. A break longer than the brand's maximum (installation
  setting, default six weeks): refused naming the maximum.
- A self-exclusion with a period that is not a whole number of days, or shorter than 183: refused.
- A reality-check acknowledgement for a session that is not the player's: refused.
- Session minutes when the sessions view is behind: the service counts what it has; a session it
  has not yet seen does not count, which can let one more launch through within the view's lag;
  the status shows the lag.
- The original's `blocked` account status: `players`' (spec 008); this service asks nothing about
  it.
- An erasure request for an excluded player: the hold the brand's retention rules require applies
  (spec 001 FR-025); the exclusion itself is a status and a case, not personal data, and survives.

## Requirements *(mandatory)*

### Functional Requirements

**The profile**

- **FR-001**: The service MUST keep one event sourced profile per player holding: the active limits
  by type and currency (`DEPOSIT_DAILY`, `DEPOSIT_WEEKLY`, `DEPOSIT_MONTHLY`, `LOSS_DAILY`,
  `LOSS_WEEKLY`, `LOSS_MONTHLY` in minor units of a currency; `SESSION_MINUTES` in whole minutes),
  at most one pending change per type with its value or removal and its applies-at, the break (end,
  set-at, case id) if any, the exclusion (permanent or a period's end, since, trigger, case id) if
  any, the reality-check interval if any and the last acknowledgement, and who made each change.
  Established from `RgRoutes` and the twin properties.
- **FR-002**: A player's change to a limit MUST apply at once when it is more restrictive than the
  active limit (a smaller value, or any value where none applies) and MUST cancel a pending change
  of that type; it MUST be staged as pending with applies-at 24 hours from now when it is less
  restrictive (a larger value, or a removal); a further change to a pending type MUST replace the
  pending change and its time. Established from `RgRoutes.isMoreRestrictive`.
- **FR-003**: A `SESSION_MINUTES` value from a player MUST be a positive whole number and MUST be at
  or above the brand's minimum (a brand setting, default 60), refused naming the minimum otherwise;
  an operator's value MUST only be a positive whole number. Established from `RgRoutes` and
  `getMinSessionMinutes`.
- **FR-004**: An operator's change to a limit MUST apply at once with no pending stage and MUST
  cancel any pending change of that type. Established from `BackofficePlayerRoutes`.
- **FR-005**: A pending change MUST apply by a timer (ankka 032) named by the player and limit type
  at its applies-at, replaced by name on a further change, and MUST be recorded as applied by the
  system. **Change from the original**, which stages and never applies.
- **FR-006**: A limit MUST be in the currency of one of the player's wallets, and MUST apply to that
  wallet; `SESSION_MINUTES` carries no currency.

**Usage and the checks**

- **FR-007**: The service MUST serve `may-deposit` within the project for the wallet: given the
  player, the currency, the amount, and the deposit sums of the last 24 hours, 7 days and 30 days
  the wallet carries, it MUST answer refused as excluded, else refused as cooling off naming the
  end, else refused as over the limit naming the first of daily, weekly, monthly the amount would
  pass with the limit, the amount used and the remaining allowance, else allowed. A refusal MUST
  name no other reason. The wallet's call at credit (spec 002 FR-022) is the authoritative one;
  `players` MAY ask the same route at deposit initiation with sums read from the wallet (spec 008
  FR-043) so the player is told before paying, and that answer binds nothing. Established from the
  `notify/deposit` route's order of checks and spec 104.
- **FR-008**: The service MUST serve `may-play` within the project for `game-provider`: refused as
  excluded, else cooling off naming the end, else session limit reached naming the limit and the
  minutes used, else allowed, an allowed answer carrying the session limit (or none) and the minutes
  used; minutes used MUST be the sum of the player's game sessions started this UTC calendar day, an
  open session counted to now, from the service's view of `casino.sessions`. `players` sets the game
  session's timer from the limit it reads on `rg_limit_applied` and its own sessions (spec 008
  FR-019), asking nothing here. Established from `SessionRoutes`.
- **FR-009**: The service MUST serve `may-log-in` for `players` within the project — which asks at
  session issue and when the casino realm's RG authenticator asks `players` (spec 008 FR-011, FR-013;
  the realm reaches no other service, spec 001 FR-022): refused as excluded, else refused as cooling
  off naming the end, else allowed. Established from `RgStatusAuthenticator` and `PlayerRoutes`.
- **FR-010**: The service MUST serve `may-bet` within the project for the wallet: refused as locked
  for an excluded or cooling-off player; else refused `spending_limit`, naming the limit and the
  remaining allowance, when the stake would take the player's losses over 24 hours, 7 days or 30
  days past a loss limit, from the loss sums the wallet carries in the request as FR-007's deposit
  sums are carried; else allowed. Established from `St8Routes.isCoolingOff` and `isPlayerLocked`;
  the loss-limit refusal is a **change from the original**, which sets and shows loss limits and
  enforces nothing (Clarifications 2026-10-25). The `may-bet` step is spec 003 FR-012's; it carries
  the sums spec 002 FR-030 gives it, so FR-011 holds and the gate calls nothing.
- **FR-011**: Every check MUST answer from the service's own state and the figures the request
  carries, and MUST NOT call another service on the way; a check the service cannot answer MUST
  be answered unavailable, and every caller MUST treat unavailable as a refusal. **Change from the
  original** at bet and deposit, which fail open.
- **FR-012**: The service MUST keep a view of `money.transactions` giving, per player and currency,
  the deposits and the losses of the last 24 hours, 7 days and 30 days for display and reports, and
  MUST show them with the limits on the player's and the operator's routes; the figures MUST be
  exact to the minor unit and MUST never be the input to a refusal (FR-007). Established from
  `fetchUsedAmounts`; the 100-row truncation incident is the reason for exactness.
- **FR-013**: A deposit refused under FR-007 MUST be refused by the wallet before any credit, MUST
  be reported to `merchant` as final with the reason and the remaining allowance, and MUST be
  shown to the player in their currency; `merchant` MUST treat the refusal as final and refund
  through the provider (spec 004), and the player MUST be told the deposit was refused and refunded,
  with the reason and the remaining allowance: nothing waits on an operator and nothing is credited
  past a limit. **Change from the original**, which credits and logs.

**Breaks and exclusions**

- **FR-014**: A player MUST be able to set a break until a moment at least one hour and at most the
  brand's maximum ahead (installation setting, default 42 days); the break MUST apply at once, open
  a case of type cool-off with trigger player and that end, and schedule a timer at the end.
  Established from `RgRoutes` cooling-off; the bounds are new.
- **FR-015**: A player MUST NOT be able to end, shorten or replace an active break; a request to do
  so MUST be refused naming that only an operator may. Established from spec 104 FR-013 and the
  absence of a player route.
- **FR-016**: An operator MUST be able to set a break with the same bounds, trigger operator, and to
  lift one with a reason; lifting MUST end the break, close the case as lifted naming the operator,
  cancel the timer, and change no account status. Established from `BackofficePlayerRoutes`.
- **FR-017**: A break MUST end by its timer: the break is cleared, the case closed as expired, and
  nothing else about the player changes; a player whose status is suspended stays suspended.
  **Change from the original**, whose expiry function is disabled, matches no case and clears no
  flag.
- **FR-018**: A player MUST be able to exclude themselves permanently in one confirmed action; the
  service MUST record the exclusion, open a case of type self-exclusion with trigger player, and tell
  `players` within the project to set the account status `self_excluded` (spec 008 FR-032), which
  ends every session of the player and produces `player_self_excluded`; this service produces
  `rg_case_opened`. Established from `RgRoutes` permanent-exclusion and spec 104 FR-001 to FR-005;
  ending sessions is new.
- **FR-019**: An operator MUST be able to exclude a player for a period of at least 183 whole days
  or permanently, trigger operator, on this service's route — `players` has no exclusion action of
  its own (spec 008 FR-032) — with the same command to `players` as FR-018; a shorter period MUST be
  refused naming the minimum. Established from `BackofficePlayerRoutes.parseDays`.
- **FR-020**: A period exclusion MUST NOT end by itself. After the period the player MUST be able
  to ask to return, from their own routes or through an operator recording the request for them,
  and the service MUST reinstate 24 hours after the request by a timer (UKGC's positive action plus
  a day) — never sooner, whoever asks; a request before the period's end MUST be refused naming it,
  and always for a permanent exclusion. A reinstatement MUST clear the exclusion and the break
  together, close the case, and tell `players` to set the status active. **Change from the
  original**, whose reinstate is an operator's at once, leaves `rg_is_cooling_off` set and whose
  period is written and never read.
- **FR-021**: An exclusion MUST take precedence over a break in every answer; both MUST remain on
  the profile.
- **FR-022**: The casino realm's GAMSTOP authenticator MUST stay as the original has it, failing
  closed; a refusal it makes MUST be reported by the realm to `players`, which passes it to this
  service within the project (spec 008 FR-013); this service MUST record a case of type
  self-exclusion with trigger third party and no end, and MUST NOT change the account status for it.
  Established from `GamstopAuthenticator`; the report is new.

**Reality checks**

- **FR-023**: A player MUST be able to set a reality-check interval of 15, 30, 45 or 60 minutes or
  remove it; any other value MUST be refused naming the allowed values. Established from
  `RgRoutes`.
- **FR-024**: A player MUST be able to record an acknowledgement naming one of their game sessions;
  the service MUST keep the latest acknowledgement's time and session on the profile and MUST
  refuse a session that is not the player's. The firing of the check is the game page's (the
  frontend reads the interval at launch); the service fires nothing. Established from `RgRoutes`
  and `GameManagerContext`.

**Operators, cases and history**

- **FR-025**: The service MUST serve the operator the player's full state: limits with usage,
  pending changes, break, exclusion, reality-check interval, open and closed cases, and history.
- **FR-026**: A case MUST have a type (self-exclusion, cool-off, deposit-limit, loss-limit,
  session-limit, reality-check, account-block), a trigger (player, operator, third party, system), a
  status (open, closed), opened-at, closes-at where it has an end, closed-at and a close reason
  (expired, lifted, closed) where closed, optional limit value and period, and notes. An operator
  MUST be able to open a case with a note and close one with a reason. Established from the
  RGCase vocabulary; `pending_review` and `expired` statuses collapse into open and a close reason.
- **FR-027**: History MUST list every change to the profile and every case event in reverse time
  order with the type, the old and new value where there is one, the actor (player, operator by
  id, system, third party) and the time. **Change from the original**, whose history lists cases
  only (spec 104 US6).
- **FR-028**: Every change an operator makes MUST write an audit record (`platform.audit`) naming
  the operator, the player, the action and the before and after.
- **FR-029**: The brand's minimum session limit MUST be a brand setting an operator can change;
  the change MUST bind the next player change and alter no existing limit.

**Facts**

- **FR-030**: The service MUST produce on `casino.players` (spec 001 FR-011), keyed by the player:
  `rg_limit_applied` (type, currency, value or none, actor, cause: set, lowered, applied, removed),
  `rg_case_opened` (case id, type, trigger, closes-at, and for a self-exclusion whether permanent or
  the period's end), `rg_case_closed` (case id, reason); `player_self_excluded` is `players`', produced
  when this service has it set the status (FR-018, spec 008 FR-032); and MUST tell `engagement`'s
  consumers nothing else: `deposit_limit_set`, `loss_limit_set`, `session_limit_set`,
  `rg_limit_loosened`, `cooling_off_set` and `self_exclusion_activated` are derived by
  `engagement` (spec 014) from these facts, once each. Established from `CrmService` and
  `customerio_dispatch`.
- **FR-031**: `engagement` MUST send an excluded or cooling-off player no marketing while the
  case is open (spec 014 enforces; this service's facts make it knowable); `reporting` (spec 015)
  MUST count exclusions and limits from the same facts; the regulator's case export MUST be built
  from `rg_case_opened` and `rg_case_closed` with no other source.

**Personal data**

- **FR-032**: A case's notes and a lift's or close's free-text reason MUST be personal data under
  the player (spec 001 FR-023); limit values, currencies, dates, types, statuses, triggers and
  actor ids MUST NOT be.

### Changes from the original

- A deposit is refused before it is credited, by the wallet asking this service; today it is
  credited and the refusal is a warning in a log.
- Every check fails closed; today bet and deposit fail open.
- A staged loosening applies by itself after 24 hours; today it never applies.
- A break ends by itself at its end; today it never ends, and the disabled function that should
  end it would leave the flag set.
- Reinstating a player clears the break and the exclusion; today it leaves `rg_is_cooling_off`
  set and the player is refused at login.
- A period exclusion is refused reinstatement before its end; today the period is written and
  never read.
- Self-exclusion ends open game sessions; today it records the status only.
- History lists limit changes and case events with actors; today it lists cases only.
- Case statuses `pending_review` and `expired` become open and closed with a reason; both routes
  today open cases as `pending`, which the expiry function never matches.
- A break has bounds (one hour to the brand's maximum); today any `until` is accepted, including
  the past.
- The GAMSTOP refusal is recorded as a third-party case; today it is a Keycloak note.
- Operator limit changes are audited and produce facts; today they write properties and nothing
  else.
- Dropped: the three empty TL monitors (`rg-deposit-limit-monitor`, `rg-loss-limit-monitor`,
  `session-duration-limit-check`) and their events (`rg.deposit_limit_approaching.v1`,
  `rg.loss_limit_approaching.v1`, `session.duration_limit_reached.v1`); the `rg-pending-apply`
  and `rg-cool-off-expiry` functions and the `apply_pending_rg_limits` and
  `check_cool_off_expiry` capabilities; the `rg_*` twin properties and the RGCase twins; the
  `rg-case.pending` and `rg-case.resolved` concepts; the `last_blocked_login_at` property.
- Kept unchanged in observable behaviour: the seven limit types and their names, the
  more-restrictive rule, the 24-hour delay, the rolling 1/7/30-day windows, the UTC-day session
  minutes, the brand minimum session limit with default 60, the 183-day minimum, the four
  reality-check intervals, the order of refusals, the operator's immediate changes, and the
  player's inability to lift a break.

### Recordings

The original's Go end-to-end tests of these rules are all skipped (`TestScenario7_CasinoGate3_RGEnforcement`,
`TestScenario9_CasinoRF_CoolOffExpiry`, `_DepositLimitPendingApply`, `_SessionDurationLimitCheck`,
`_DepositLimitMonitor`, `_LossLimitMonitor`), so the Go suite records nothing here. The recordings
this spec names are written against the original before the port (spec 001 FR-029), one per
scenario above that the original can perform, driving `RgRoutes`, `BackofficePlayerRoutes`,
`SessionRoutes` launch, `PlayerRoutes` login and the `notify/deposit` route; where the original's
behaviour differs from this spec (the Changes above), the recording is kept as evidence of the
original and the parity test asserts this spec's behaviour, named in the test. The Playwright tests
that run today are the frontend oracle: `05-rg-api` (status fields, history shape, reality-check
set, invalid interval, removal, cooling-off not removable by the player), `41-rg-session-limit`
(minimum enforcement, pending stage, launch refusal), and the unit tests `RgRoutesSpec` (status
shape, the full-aggregate usage) are reproduced as parity tests.

### Key Entities

- **Profile**: one per player; limits, pending changes, break, exclusion, reality check, and the
  actor of each change.
- **Case**: one per intervention; type, trigger, status, times, reason, limit value and period,
  notes.
- **Pending change**: a limit type, a value or removal, and the time it applies.
- **Break**: a cooling-off with an end and a case.
- **Exclusion**: permanent or a period, with a trigger and a case.
- **Usage view**: deposits and losses per player and currency over the three windows, from
  `money.transactions`; session minutes today, from `casino.sessions`.
- **Brand settings**: minimum session limit (default 60), maximum break (default 42 days).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: No deposit that would pass a player's remaining allowance is credited, by any
  sequence of deposits, in every parity test and under two deposits racing.
- **SC-002**: No excluded or cooling-off player logs in, launches or bets while the exclusion or
  break stands; 100% of attempts refused in the parity tests.
- **SC-003**: A staged loosening applies within one minute of its applies-at; a break ends within
  one minute of its end.
- **SC-004**: A check answers within 100 milliseconds at the 99th percentile from the service's
  own state, with no call to another service.
- **SC-005**: A reinstated player logs in within one request of the reinstatement.
- **SC-006**: Every operator action appears in the player's history and in the audit within five
  seconds.
- **SC-007**: The usage figures shown equal the wallet's sums to the minor unit in every parity
  test, including a player with more than 100 transactions in a window.
- **SC-008**: Every scenario above has one parity test, and every change listed is asserted by a
  test the original would fail.

## Assumptions

- The TL alternative: if TL survives as a sold feature (spec 001), these rules can be written as a
  function pack reading this service's facts (`rg_limit_applied`, `rg_case_*`,
  `money.transactions`) and proposing the same commands; the service's command routes are the
  pack's effects. Nothing in this spec assumes it.
- The wallet's deposit sums (spec 002 FR-030) are exact at the moment of the call; the one-second
  freshness bound in 002 is for display, since here the sum is built inside the command.
- Spec 003 asked `players` for cooling-off; cooling-off is this service's, and 003's launch asks
  `may-play` here for break, exclusion and session minutes, and `players` for status and KYC.
  Spec 002's deposit route (FR-022) asks `may-deposit` here before crediting, and its bet command
  asks `may-bet`; 002's clarification that "the wallet does not enforce RG limits, as today" is
  corrected: today nothing does, and the wallet asks. Spec 004 treats a refusal here as final.
  Spec 008's login refuses on account status and asks `may-log-in` for breaks. Each was taken at
  reconciliation 2 (specs 002 FR-007a and FR-022, 003 FR-007 and FR-012, 004 FR-021, 008 FR-011,
  FR-013, FR-018, FR-019, FR-032).
- The casino's Keycloak authenticators (GAMSTOP, RG status) stay and ask `players` only (spec 008
  FR-013), which asks `may-log-in` here; no machine is registered under ankka 040. The realm's
  report of a GAMSTOP refusal reaches this service through `players`.
- The brand's maximum break of 42 days and the one-hour minimum are new; UKGC's cooling-off is
  24 hours to six weeks, and the original accepts anything.
- `loss` in the usage view is the wallet's `loss` transaction type as spec 002 produces it; its
  meaning (stakes settled as losses net of nothing) is 002's.
- Operators are identified by the `eitheror` realm roles for the brand (spec 001 FR-020); which
  role may exclude, lift a break or reinstate is spec 008's role table.

## Dependencies

- Spec 001 (project, `casino.players` families, `casino.sessions`, identity classes, port rules),
  spec 002 (the wallet's deposit and bet commands asking here; the deposit sums; the `loss`
  type), spec 003 (`may-play` at launch), spec 004 (a final refusal of a captured deposit), spec
  008 (`players`: account status, session issue asking `may-log-in`, ending sessions, the realm's
  reports), spec 014 (`engagement` derives its events and suppresses marketing), spec 015
  (`reporting` and the case export).
- ankka 032 (timers by name), 022 (player and operator tokens), 042 (`Personal` notes and reasons),
  024/043 (the facts).
- The casino's Keycloak with the two authenticators, outside the installation.

## Out of scope

- Stake limits, affordability checks, proactive monitoring and "approaching limit" notices: none
  exists in the original. (Loss limits are enforced at bet, FR-010; settled 2026-10-25.)
- Jurisdiction-specific exclusion registers other than GAMSTOP; a second is a second authenticator.
- The game page's reality-check timer and modal (frontend; it reads the interval and records the
  acknowledgement as today).
- Account closure by the player: `players`' (spec 008).
- KYC and source-of-funds gates at deposit: `kyc`'s (spec 009), asked by the wallet beside this
  service's check.
