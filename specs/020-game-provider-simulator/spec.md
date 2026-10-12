# Feature Specification: Game provider simulator

**Feature Branch**: `020-game-provider-simulator`

**Created**: 2026-10-08

**Status**: Implemented (2026-10-10); deployed to `dev-kind` (2026-10-11); the run against the original (quickstart §9) is pending

**Input**: User description: "A mock ST8 service that recreates ST8's APIs and callbacks so we can test properly before switching to them in pre-production and production, written as an ankka project: a provider core with protocol adapters over it, scenario scripts for every failure mode, and a recording of every run."

## Context

Today the original has two stand-ins for the game provider, and neither can misbehave on purpose.
`tests/fake-st8` is a Go process of 1,329 lines: it generates a P-256 key at start, registers the
public key with the adapter through a test-only control route, seeds three games into the catalogue,
answers the provider's operator API (launch, games, free-round provisioning), serves a game page that
performs the `check` callback, and exposes `/test/*` hooks that send one signed `debit`, `credit`,
zero-amount `credit` or `cancel` and proxy the adapter's answer back verbatim. The Go end-to-end
suite's `TestScenario15_St8Wallet_*` tests do not use it at all: the test harness is its own
provider, signing with a key it registers once through the same control route. Neither stand-in
retries, times out, sends a callback out of order, rotates a key, or records what it sent.

What that leaves untested is exactly what the incident of 2026-08-19 showed: the provider re-sent
one €0.03 win 86 times over eight hours while the adapter answered `error`, and 2,894 retry
attempts went into the logs before anyone looked. The provider's behaviour under failure, not its
happy path, is what real money depends on, and specs 002 and 003 carry requirements for duplicate,
late and conflicting callbacks that nothing can exercise today.

The simulator is a provider core under protocol adapters, deployed as an ankka service in a `tools`
project, reaching a brand's public provider routes as the real provider does: from outside, with no
platform identity, under the brand's signature verification. Its default behaviour reproduces
`fake-st8` so the original's recordings replay unchanged; its scenarios add what `fake-st8` cannot do.

## Clarifications

### Session 2026-10-08

- Q: Is this a game provider or a test double? → A: A simulator. The hard part of a real provider is
  the games: a certified random number generator, return-to-player rates, per-jurisdiction
  certification, a game client. None of that is in scope, and the spec says so. The one seam it keeps
  is between the provider core and a protocol adapter, so that a second real provider's protocol is
  a second adapter and no change to the core.
- Q: Where does it live? → A: In this repository, as a service in a `tools` project any installation
  can run, not a repository of its own. A monorepo of specs stays consistent; extract it if it ever
  becomes a product.
- Q: How does it reach a brand? → A: As the real provider does: through the gateway, as an outside
  caller with no ankka identity, signing every callback so that the brand's own verification admits
  it. It is the first outside caller the rebuild has, and so a test of the gateway path too.
- Q: What is its default behaviour? → A: `fake-st8`'s, exactly. ankka-port records the original
  talking to `fake-st8`, so a run of the simulator in its default scenario against a rebuilt brand
  must produce the same recording, or the parity tests mean nothing.
- Q: Which protocol? → A: ST8's only, as the adapter implements it: `check`, `balance`,
  `player_profile`, `debit`, `credit`, `cancel`, `buyin`, `payout`, each a signed POST answered with
  HTTP 200 and a status word. The generic HMAC protocol (`bet`, `win`, `rollback`) was dropped by
  spec 003 as a stub of the original, so no second adapter is specified.
- Q: Does the simulator keep ST8's decimal amounts? → A: Yes. Constitution II binds the wallet, not a
  foreign wire format. The simulator sends what ST8 sends (`"amount": "1.50"`); spec 003's adapter
  converts to minor units, as the original's does.
- Q: What about `fake-st8`'s hooks that reach past the provider: `/test/seed-bonus-offer` and
  `/test/reset-wallet-bonus`? → A: Not provider behaviour. They poke the original's substrate and
  wallet internals and belong to the rewards and wallet test kits (specs 011, 002), not here.
- Q: Who registers the simulator's public key with a brand? → A: Configuration, not a test route. The
  original's `/st8/test/set-public-key` is a test-only control; the rebuild's brand holds its
  provider's public key as provider configuration (spec 003), and the simulator publishes its current
  and next keys at a key document, as the programme does for tags (spec 012).

### Session 2026-10-08 (reconciliation, wave 1)

- Q: Is there a second adapter? → A: No. Spec 003 dropped the generic provider API as a stub, so the
  generic adapter and its user story are gone; the seam stays (FR-015) for a real second provider.
- Q: Do this spec and 003 agree on the wire? → A: Yes, after reconciliation: `x-st8-sign`, the fields
  of 003 FR-004, 003's status words including `spending_limit`, HTTP 400 for a malformed body, and
  the key document of 003 FR-026 as the way a test brand registers the simulator's key.

### Session 2026-10-09

- Q: Does ST8 document its callback retry timing and cap, and can it be copied into the default
  schedule? → A: Not to hand. The stated approximation stays the shipped default, and the schedule
  (intervals and cap) is a per-scenario setting in the scenario data, replaced without a release
  when the provider's figures arrive.
- Q: What does ST8's sandbox answer on the wire once its testing pot cap is reached? → A: Not
  known; the incident of 2026-08-13 saw bets fail silently and nobody captured the answer. No
  "testing pot cap" scenario in this spec; it is out of scope until the sandbox's answer is captured.
- Q: Who may call the simulator's own routes? → A: The instruction routes and the game page are
  public, unchanged from `fake-st8`, so the original's suite runs against them as it is; scenario
  selection, load-profile start, the run view and the export are operator routes (spec 001's route
  classes) under the installation's gateway, callable only with an operator identity.
- Q: Are "duplicate debit after a timeout" and "slow operator" one scenario or two? → A: One,
  "slow operator": the brand answers a debit slower than the provider waits; the simulator abandons
  the request, retries under the same transaction id on the schedule, records every attempt, and
  passes only if the retry's answer equals the first and the balance moved once. "Duplicate debit"
  is dropped as a separate scenario.
- Q: Is the signing material per brand or the simulator's? → A: The simulator's: one P-256 key pair
  (FR-010), one key document (FR-011), shared by every brand it serves. FR-025's "or shared
  secret" and "the protocol" were leftovers of the dropped generic adapter; a brand's configuration
  is its base address and its test-brand flag.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A brand plays a round against the simulator exactly as against `fake-st8` (Priority: P1)

A tester launches a game at a brand, the simulator issues the game page, performs `check`, and on
the tester's instruction sends a bet, a win, a loss or a cancel to the brand's provider routes, signed,
and answers with what the brand answered. Nothing the brand sees differs from a run against
`fake-st8`.

**Why this priority**: it is the parity floor. Every recording of the original that involves the
provider replays through this path; without it no other scenario is trusted.

**Independent Test**: run the original's suite with `fake-st8` and record; run it with the
simulator in its default scenario and record; the two recordings are the same.

**Acceptance Scenarios**:

- added `features/simulator/rounds.feature`: a launch against the simulator stores the session, returns a game page address, and checks the launch ticket on the page's load
- added `features/simulator/rounds.feature`: a bet instructed by the tester is sent as one signed debit with a new transaction id and the brand's answer is returned unchanged
- added `features/simulator/rounds.feature`: a win instructed by the tester is sent as one signed credit for the round and the brand's answer is returned
- added `features/simulator/rounds.feature`: a loss instructed by the tester is sent as a credit of zero, which the brand settles as a loss
- added `features/simulator/rounds.feature`: a cancel instructed by the tester is sent as one signed cancel naming the bet's transaction id and a new cancel id
- added `features/simulator/rounds.feature`: the original's recordings replay against the rebuilt brand with the simulator in its default simulator scenario, with the same calls in the same order and the same answers

---

### User Story 2 - A scenario makes the provider misbehave on purpose (Priority: P1)

A tester selects a scenario for a session or a brand, and the simulator does what a real provider
does when things go wrong: retries a debit the brand answered too slowly, cancels after a credit, sends a round's
callbacks out of order, storms the brand with retries, signs with a rotated key, names a token the
brand never issued. The scenario states what the brand must answer and the simulator records whether
it did.

**Why this priority**: these are the cases real money depends on and nothing can exercise today;
specs 002 and 003 carry the requirements and this is their test.

**Independent Test**: run each scenario against a rebuilt brand and read the run's verdict: for
each step, the brand's answer against the scenario's expectation.

**Acceptance Scenarios**:

- added `features/simulator/scenarios.feature`: a cancel sent after a credit passes only if the brand answers as the original does for a cancel of a settled round
- added `features/simulator/scenarios.feature`: a credit sent before its debit passes only if the brand answers as for an unknown round and later accepts the debit
- added `features/simulator/scenarios.feature`: a retry storm resends on the provider's schedule until the brand stops answering error or the cap is reached, and reports the count and time
- added `features/simulator/scenarios.feature`: a rotated key passes only if the brand admits calls under the next key within its bound and refuses calls under a key no longer listed
- added `features/simulator/scenarios.feature`: a debit naming a session ticket the brand never issued passes only if the brand answers session expired and no balance moves
- added `features/simulator/scenarios.feature`: a win far larger than any bet passes only if the brand answers exactly as the original does, with the balances before and after recorded
- added `features/simulator/scenarios.feature`: an orphaned bet is settled by a credit of zero after the scenario's window, and the simulator run passes only if the brand settles the round as a loss
- added `features/simulator/scenarios.feature`: a brand answering a debit slower than the provider waits has the request abandoned and retried under the same transaction id, every attempt recorded, and the simulator run passes only if the retry's answer equals the first and the balance moved once
- added `features/simulator/scenarios.feature`: a simulator scenario selected for one session leaves another session on the same brand on the default scenario

---

### User Story 3 - The operator API and free rounds behave as the provider's (Priority: P2)

A brand's game catalogue is seeded from the simulator's games listing; an operator provisions free
rounds for players through the simulator's bonus API; a tester spends a free round and the brand
sees a zero-stake debit followed by a credit.

**Why this priority**: the catalogue seed, launch and free-round flows are the provider's operator
side; four of the original's recordings run through them.

**Independent Test**: seed a brand's catalogue from the simulator, provision a free round for a
player on one game, spend it, and read the brand's answers.

**Acceptance Scenarios**:

- added `features/simulator/catalogue-and-free-rounds.feature`: the simulator's list of games is answered in the provider's shape with the three games the original's stub advertises
- added `features/simulator/catalogue-and-free-rounds.feature`: free rounds provisioned on a game the simulator has are stored as one instance per player and answered ok with the instances
- added `features/simulator/catalogue-and-free-rounds.feature`: free rounds provisioned on a game the simulator does not have are answered error with unknown game
- added `features/simulator/catalogue-and-free-rounds.feature`: a free round spent by a player with an active instance sends a free debit of zero and then a credit of the win, and decrements the instance
- added `features/simulator/catalogue-and-free-rounds.feature`: a free round spent by a player with no active instance is refused and nothing is sent
- added `features/simulator/catalogue-and-free-rounds.feature`: an operator cancelling one player's free rounds instance marks it canceled and later spends are refused

---

### User Story 4 - Every run is observable and can become a parity test (Priority: P2)

A tester opens the run of a session and sees every callback sent, the answer, the timing and the
scenario step that caused it; a run exports as a recording in the shape ankka-port keeps, so a
scenario run can become a parity test of the brand.

**Why this priority**: the simulator's value is the verdict, and a verdict nobody can read or keep
is a log line.

**Independent Test**: run a scenario, read the run through the view and the route, export it, and
replay the export as a parity test against the same brand.

**Acceptance Scenarios**:

- added `features/simulator/runs.feature`: a simulator run lists each provider call with what was sent, the key it was signed with, the times, the answer, the attempt and the scenario step
- added `features/simulator/runs.feature`: an exported simulator run is a recording in the shape the recordings are kept in, and the brand's parity tests replay it
- added `features/simulator/runs.feature`: a simulator run of a simulator scenario shows each step's expected against observed and passed or failed with the first failing step
- added `features/simulator/runs.feature`: simulator runs older than the installation's retention for the simulator are removed by the sweep and counted on the simulator's status

---

### User Story 5 - A load profile plays many sessions at a rate (Priority: P3)

A tester starts a load profile of N sessions playing M rounds a minute for a duration against a
brand, and reads throughput, the answer-time distribution and every non-"ok" answer.

**Why this priority**: the wallet's one-entity-per-wallet design and spec 002's latency criterion
need a load that looks like a provider's, not a benchmark's.

**Independent Test**: run a profile of 100 sessions at 10 rounds a minute for 5 minutes against a
local brand and read the report.

**Acceptance Scenarios**:

- added `features/simulator/load.feature`: a load profile launches its sessions, plays rounds at the rate, and reports rounds sent, answers by word and the percentile answer times
- added `features/simulator/load.feature`: a load profile stopped by the tester settles its open rounds as losses before it ends

---

### Edge Cases

- A callback answered with HTTP 400 and "session_expired" (the brand could not parse the body): the
  simulator records it as a malformed-body answer and does not retry; the scenario "malformed body"
  sends one on purpose.
- An answer with status "auth_failed": recorded, never retried; the scenario "wrong key" expects it.
- The brand is unreachable: the simulator retries as the provider would, records each attempt, and
  the run fails with "brand unreachable" after the cap.
- A session's launch ticket is used twice: the second `check` is answered "session_expired" by the
  brand and the simulator serves the session-expired page, as `fake-st8` does.
- Two scenarios selected for one session: refused; the last selection before launch stands.
- The brand's public key document for the simulator is missing: the brand refuses every callback
  with "auth_failed"; the run says so in its first step.
- A load profile against a brand that takes real money: refused unless the brand is marked as a
  test brand in the simulator's configuration.
- A scenario selection, a load-profile start or a run export without an operator identity: refused
  by the gateway before it reaches the simulator; nothing is recorded in any run.

## Requirements *(mandatory)*

### Functional Requirements

**The core**

- **FR-001**: The simulator MUST hold a catalogue of fake games, each with a code, a name, a category,
  a developer, bonus types, features and themes, seeded with the three games `fake-st8` advertises
  (`book-of-ra`, `fake-slot-002`, `fake-table-001`) and extendable by configuration.
- **FR-002**: The simulator MUST keep one session per launch ticket (the wire's `token`): the brand,
  player, currency, game code, the session ticket the brand returned at `check`, the selected
  scenario and the run.
- **FR-003**: The simulator MUST keep one round per round id within a session with the state open,
  won, lost or cancelled, and the transaction ids it sent for the round's debit, credits and cancels.
- **FR-004**: Every outcome a scenario needs at random (a win amount, a delay within a range, which
  session acts next) MUST come from a generator seeded per run, so that a run with the same seed sends
  the same callbacks in the same order.
- **FR-005**: The simulator MUST schedule every callback through one scheduler that applies the
  protocol's retry rules: a status of "error" or no answer within the timeout is retried on the
  scenario's retry schedule (its intervals and cap, part of the scenario data of FR-019, shipped
  with the default of *Assumptions*); any other status word ends the attempt.
- **FR-006**: The core MUST expose to adapters only sessions, rounds, scheduled callbacks and their
  answers; no adapter keeps state of its own, and no part of the core names a protocol's route,
  field or status word.

**The ST8 adapter**

- **FR-007**: The ST8 adapter MUST answer the provider's operator API as `fake-st8` does: launch
  (store the session, return a game page address carrying the launch ticket), games (the catalogue in
  the provider's games, developers and categories shape), and free-round provisioning (create, get,
  cancel) answering HTTP 200 with a status word, "unknown_game" for a game the catalogue lacks.
- **FR-008**: The ST8 adapter MUST serve the game page, which performs `check` with the launch ticket
  and, on "ok", keeps the session ticket; on any other answer it serves the session-expired page.
- **FR-009**: The ST8 adapter MUST send the callbacks `check`, `balance`, `player_profile`, `debit`,
  `credit`, `cancel`, `buyin` and `payout` with the fields the original's adapter reads (`token`,
  `transaction_id`, `round`, `currency`, `amount` as a decimal string of two places, `provider_kind`,
  `game_code`; `cancel_id` and the referenced `transaction_id` for a cancel) and the provider's
  nested `provider` object as the provider's testing tool sends it.
- **FR-010**: Every callback MUST be signed: the SHA-256 of the body, signed with the simulator's
  current key, ASN.1 encoded, base64 in the `x-st8-sign` header. The key MUST be a P-256 key
  generated at start or supplied from the secret store (ankka 038), never written to configuration.
- **FR-011**: The simulator MUST publish a key document naming its current key and, during a
  rotation, its next key, each with a key id; the scenario "rotated key" MUST publish the next key,
  wait the brand's bound, then sign with it, then withdraw the old key. A brand on an installation
  that takes no real money registers the simulator by naming that document's address as its provider
  public key (spec 003 FR-026), which the brand refreshes within 60 seconds; in ankka's local mode
  the brand's provider configuration names the simulator's local address.
- **FR-012**: The ST8 adapter MUST read the brand's answer as the protocol defines it: HTTP 200 with
  a `status` of "ok", "error", "session_expired", "player_locked", "player_not_found",
  "not_enough_money", "spending_limit", "transaction_not_found", "provider_unavailable" or
  "auth_failed" (the words spec 003 answers), with `balance`
  and `currency` where the status carries them; HTTP 400 as a malformed body.
- **FR-013**: A loss MUST be sent as a `credit` of "0.00" on the round, as `fake-st8` sends it; a
  free round MUST be a `debit` of "0.00" with `provider_kind` "free_debit" followed by a `credit`.
- **FR-014**: The ST8 adapter MUST expose the instruction routes `fake-st8` exposes for a session
  (bet, win, lose, cancel, free-spin, and the session's state), with the same request bodies, so the
  original's tests drive the simulator unchanged.

**A second adapter**

- **FR-015**: No second adapter is specified: the generic provider API was dropped by spec 003 as a
  stub of the original. FR-006 is the seam a second real provider's adapter would be added behind.

**Scenarios**

- **FR-016**: A scenario MUST be selectable per session before launch or per brand as a default, and
  the default scenario MUST reproduce `fake-st8`: one callback per instruction, no retries, the
  brand's answer returned verbatim.
- **FR-017**: The simulator MUST ship the scenarios: slow operator (a debit answered slower than
  the provider waits is abandoned and retried under the same transaction id; passes only if the
  retry's answer equals the first and the balance moved once); cancel after credit; out of order;
  retry storm; rotated key; wrong key; unknown token; win larger than any bet; orphaned bet settled
  as a loss; malformed body; and a load profile, which plays any instruction-driven scenario
  across many sessions. Each scenario MUST state,
  per step, the callback sent and the answer expected, and the run MUST record the verdict per step.
- **FR-018**: A scenario's expectations MUST be stated as the original's answers, taken from the
  recordings spec 003 names, and a scenario whose expectation has no recording MUST say so and cite
  the original's source.
- **FR-019**: A scenario MUST be written as data the simulator reads (steps, callbacks, expected
  answers, delays, caps), not as code, so that a tester adds one without a release.

**Runs and observability**

- **FR-020**: Every callback sent MUST be recorded with the route, the body, the key id, the time
  sent, the attempt number, the scenario step, the answer's status and balance and the time taken, and
  MUST be readable through the simulator's operator routes (FR-027), which a test kit reaches with a
  staff token.
- **FR-021**: A run MUST be exportable as a recording in the shape ankka-port keeps, so that it can be
  replayed as a parity test by the brand's test kit.
- **FR-022**: Runs MUST be kept for the installation's retention for the simulator (shipping default
  30 days) and swept after it.
- **FR-023**: The simulator MUST hold no personal data: a session names the player by the id the
  brand passed at launch, and the `player_profile` callback's answer is recorded with its personal
  fields redacted.

**Deployment**

- **FR-024**: The simulator MUST be an ankka service in a project named `tools`, reaching a brand's
  provider routes through the installation's gateway as an outside caller with no platform identity;
  it MUST NOT be granted anything under ankka 040, and the brand MUST admit it only by signature.
- **FR-025**: One simulator MUST serve many brands, each configured with the brand's base address
  and whether the brand is a test brand; every brand is signed for with the simulator's one key
  pair (FR-010) and names the same key document (FR-011). A load profile MUST be refused against a
  brand not marked as a test brand.
- **FR-026**: The simulator MUST run beside a brand on a laptop in ankka's local mode, and the
  original's end-to-end suite MUST run against it in place of `fake-st8` by changing one address
  and running the original's own catalogue sync first, since the simulator seeds nothing into the
  original (research R8).
- **FR-027**: The simulator's routes MUST fall in two of spec 001's route classes and no other: the
  instruction routes (FR-014), the game page (FR-008), the key document (FR-011) and the operator
  API (FR-007) are public, as `fake-st8`'s are; scenario selection (FR-016), starting and stopping
  a load profile, the run view and the run export (FR-020, FR-021) are operator routes, admitted
  only with an operator identity through the installation's gateway. The simulator MUST NOT
  introduce a credential scheme of its own.

### Key Entities

- **Game**: a fake game in the catalogue: code, name, category, developer, bonus types, features,
  themes.
- **Session**: one launch: brand, player id, currency, game code, launch ticket, session ticket,
  scenario, run.
- **Round**: one round in a session: round id, state, the transaction ids sent.
- **Callback**: one request sent to the brand: route, body, key id, attempt, time sent, answer, time
  taken, scenario step.
- **Scenario**: a named script of steps with expected answers, delays and caps; selectable per session
  or per brand.
- **Run**: the ordered callbacks of a session or a load profile with its verdict, exportable as a
  recording.
- **Free-round instance**: a provisioned bonus's allocation to one player: status and count remaining.
- **Brand configuration**: a brand's base address and test-brand flag.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The original's provider recordings (the tests under *Recordings*) replay against a
  rebuilt brand with the simulator in its default scenario with zero differences in callbacks or
  answers.
- **SC-002**: Every shipped scenario runs against a rebuilt brand and reports a verdict per step;
  the scenarios slow operator, cancel after credit, unknown token and orphaned bet pass against
  specs 002 and 003 as written.
- **SC-003**: A run with a given seed sends the same callbacks in the same order on two machines.
- **SC-004**: A load profile of 100 sessions at 10 rounds a minute for 5 minutes against a local
  brand completes with the brand answering within spec 002's SC-003 bound at the 99th percentile.
- **SC-005**: A tester adds a scenario as data and runs it without a release of the simulator.
- **SC-006**: A run's export replays as a parity test in the brand's test kit without edits.

## Changes from `fake-st8`

- Added: scenarios, retries on the provider's schedule, timeouts, out-of-order and duplicate
  callbacks, key rotation and a key document, a verdict per step, the run record and its export, a
  load profile, many brands from one simulator, and the seeded generator.
- Changed: the public key reaches a brand as provider configuration (spec 003), not through a test
  control route; the catalogue is served to the brand, which seeds itself, rather than written into
  the original's substrate by the stand-in.
- Dropped: `/test/seed-bonus-offer` and `/test/reset-wallet-bonus`, which reach past the provider
  into the original's rewards and wallet; they become fixtures of the rewards and wallet test kits.
- Tightened (planning, research R7): the games listing's developers and categories, and the bonus
  a free spin draws from, are in a fixed order (by id) where the stand-in's is Go map order; each
  is one of the orders the stand-in produces. The key is pushed to a test control route only for the
  original's own adapter (FR-026, `registerKeyAt`), never to a rebuilt brand.
- Unchanged: the operator API, the game page and `check`, the instruction routes and their bodies,
  the three games, the free-round provisioning, the signing scheme.

## Recordings

Recordings of the original that drive the provider through `fake-st8`, which the simulator in its
default scenario must replay unchanged: `TestScenarioFreeSpinsPlay_*` (CustomerIOGrant,
StacksOnActiveBonus, BonusBalanceDestination, RealBalanceDestination),
`TestScenario19_BonusWageringCompletesToRealMoney`, `TestScenario244_*` (5), the end-to-end
coverage item 38 "game roundtrip: bet, win, loss, cancel", and the Playwright launch flow (the
original's spec 094).

Recordings in which the test harness is the provider, which the simulator must be able to send as
scripted scenarios: `TestScenario15_St8Wallet_*` (29, corrected from 30 at planning: BasicBalance, BasicDebit, BasicCredit,
LargeWinOnLargeStake, BasicCancel, BasicBuyin, BasicPayout, ZeroDebit, NotEnoughMoney,
ZeroBalanceFreshSpinDeclined, ZeroCredit, StandaloneCredit, BigRoundMultipleDebits,
BigRoundMultipleCredits, BigRoundMultipleCancels1, BigRoundMultipleCancels2, BigRoundFailedDebit,
IdempotentDebit, IdempotentCredit, IdempotentCancel, BalanceWithoutToken, LimitedCancel,
WrongSignature, NewParams, TableSwitch, CorrectionDebitNegativeBalance, Resettlement,
StandaloneCancel, CancelEverythingWithinRound); these are the source of the scenarios' expected
answers (FR-018).

Recordings the simulator's own parity suite replays, made against `fake-st8` run from source:
`tests/port/simulator/OperatorApi_Launch.json`, `tests/port/simulator/OperatorApi_Games.json`,
`tests/port/simulator/OperatorApi_BonusCreateUnknownGame.json`,
`tests/port/simulator/OperatorApi_BonusLifecycle.json`.

## Assumptions

- The provider's retry schedule is not documented in the original; the incident of 2026-08-19 shows
  retries continuing for at least eight hours at a rate of tens an hour. The provider's figures
  are not to hand. The shipped default schedule is a stated approximation (every 30 seconds for the
  first ten minutes, then every ten minutes, capped at eight hours); each scenario carries its own
  schedule as data (FR-005, FR-019), so the default is replaced without a release when the
  provider's documentation arrives.
- The provider's sandbox enforced a testing pot cap (€100 of gross gaming revenue; incident of
  2026-08-13) that silently failed bets once reached. What the cap looks like on the wire was not
  captured, so no scenario reproduces it (see *Out of scope*).
- `buyin` and `payout` are driven as the harness drives them in `TestScenario15` (`BasicBuyin`,
  `BasicPayout`, `CorrectionDebitNegativeBalance`: no token, a player id, `correction_debit` and
  `free_credit` kinds); the simulator's model of them is no richer than those tests need.
  `TableSwitch` is a credit under a different game code within a round and `NewParams` a balance
  read carrying two unknown fields; neither is a table-game flow (corrected at planning, research R7).
- `fake-st8` itself sends only `check`, `debit`, `credit` and `cancel`, without the nested `provider`
  object; `balance`, `player_profile`, `buyin` and `payout` and the fuller body are the harness's
  (research R7). The default scenario sends the stand-in's bodies; a scripted scenario sends the
  harness's.
- The simulator is Scala: it is a protocol speaker with a scheduler, and the ST8 signing is already
  written in Scala in the original's adapter tests.
- Amounts on the wire are the provider's decimal strings; the wallet's minor units are spec 003's
  conversion.

## Dependencies

- Spec 003 (game-provider): the routes, status words, signing and key configuration the simulator
  speaks to. Reconciled on 2026-10-08: the header is `x-st8-sign` (003 FR-003), the fields are 003
  FR-004's, the status words 003's, the key document 003 FR-026's.
- Spec 002 (wallet): the answers the scenarios expect for duplicate, late and conflicting commands.
- Spec 001: the port method, `tests/port/`, the recording shape, the public route class.
- ankka 038 for the signing key; the gateway for the outside path; ankka's local mode.

## Out of scope

- Games: random number generation that is certified, return-to-player rates, game clients,
  certification, jurisdiction rules. This is a simulator.
- Any provider protocol but ST8's; the generic provider API is dropped (spec 003).
- Standing in for ST8 in production: the simulator is for local, test and pre-production
  installations; a real-money brand points at the real provider.
- The original's `/test/seed-bonus-offer` and `/test/reset-wallet-bonus` (specs 011, 002).
- A "testing pot cap" scenario: the sandbox's answer once its cap is reached was never captured
  (incident of 2026-08-13). It is added as scenario data (FR-019) once a captured answer exists.
