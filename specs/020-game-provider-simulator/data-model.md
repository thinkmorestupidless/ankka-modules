# Data model: Game provider simulator

**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md) | **Date**: 2026-10-09

Amounts on the wire are the provider's decimal strings (spec 003 converts); the simulator holds
them as the strings it sent and the strings the brand answered, and compares them as decimals only
for a verdict. Nothing here is a ledger (constitution II: the wallet is). Times are epoch
milliseconds written by the caller into the event (research R6).

## Game

A fake game in the catalogue (FR-001). Classpath `games.json`, read at start; not an entity.

| Field | Type | Rule |
|---|---|---|
| `code` | string | unique; the three shipped: `book-of-ra`, `fake-slot-002`, `fake-table-001` |
| `name` | string | `Book of Ra`, `Fake Slots II`, `Fake Blackjack` |
| `category` | string | `slots` or `table`; listed as `Slots` / `Table Games` |
| `developer` | string | display name; its id is the slug (lower case, spaces to `-`) |
| `bonusTypes`, `features`, `themes` | string[] | may be empty |

## Session (event-sourced entity, id = launch token)

One launch (FR-002). Created by the operator API's `launch`.

| Field | Type | Rule |
|---|---|---|
| `brand` | string | a key of the brand table |
| `playerId` | string | as the brand passed it; the only player field held (FR-023) |
| `currency` | string | from launch |
| `gameCode` | string | from launch |
| `walletToken` | string? | set by `check` on the game page's load; absent until then (409 on an instruction) |
| `scenario` | string | a Scenario name; `default` unless selected before launch |
| `runId` | string | = the launch token for a session run; the profile id when played by a load profile |
| `seed` | long | the run's generator seed (FR-004); the profile's for a profile session |
| `rounds` | map roundId → Round | one per round id (FR-003) |
| `cursor` | int | the next scenario step |
| `verdicts` | Verdict[] | one per scenario step with an `expect` |
| `state` | enum | `launched` → `checked` → `closed` (by the sweep's discard) |

Events: `Launched`, `ScenarioSelected(name, seed?)`, `Checked(walletToken)`, `CallbackPlanned
(callbackId, kind, roundId?, transactionId, cancelId?, role, free, step?)` (which moves the round:
a stake opens it, a win or loss settles it, a cancel cancels it), `BalanceSeen`, `StepJudged
(verdict)`, `Discarded`.

Rules: `ScenarioSelected` is refused with `Conflict` after `Checked`, and when a selection already
stands, so the first selection before launch is the one used; a selection's seed, when given, is the
run's (SC-003). Nothing is planned on a session never launched. A cancel naming a transaction the
session never sent is sent, as the stand-in sends it (research R13).

## Round

| Field | Type | Rule |
|---|---|---|
| `roundId` | string | generated (UUID v4 lower case) when the instruction has none, on bet and free spin only |
| `state` | enum | `open` → `won` / `lost` / `cancelled` |
| `debits`, `credits`, `cancels` | string[] | the transaction ids and cancel ids sent, in order |
| `free` | boolean | opened by a free-round debit |

## Callback (event-sourced entity, id = callback id)

One request to the brand (FR-020). Created by `Sender` for a default-scenario send or by a
`Callback` workflow for a scheduled one.

| Field | Type | Rule |
|---|---|---|
| `runId`, `sessionId`, `brand` | string | |
| `kind` | enum | `check`, `balance`, `player_profile`, `debit`, `credit`, `cancel`, `buyin`, `payout` |
| `route` | string | the path posted (`/st8/<kind>` under the brand's base) |
| `body` | string | exactly the bytes signed |
| `step` | int? | the scenario step, absent in the default scenario |
| `schedule` | Schedule | the scenario's intervals and cap |
| `attempts` | Attempt[] | one per send; idempotent on `attempt` |
| `outcome` | enum | `pending` → `answered(status)` / `gave_up` / `malformed_body` (HTTP 400) / `unreachable` |

Attempt: `attempt` (1-based), `keyId`, `sentAt`, `answeredAt?`, `httpStatus?`, `status?` (the word),
`balance?`, `currency?`, `tookMillis`, `error?` (transport). `player_profile` answers are stored with
`birth_date`, `reg_country` and `reg_date` replaced by `"<redacted>"` (FR-023).

Events: `Scheduled`, `AttemptSent`, `Answered`, `GaveUp`, `Discarded`.

## Schedule

Part of a Scenario and copied onto each Callback (FR-005).

| Field | Type | Rule |
|---|---|---|
| `wait` | duration | how long one attempt waits for an answer |
| `intervals` | `{ every, for }[]` | e.g. `[{every: 30s, for: 10m}, {every: 10m}]`; the last has no `for` |
| `cap` | duration | elapsed since the first attempt after which the callback gives up |
| `retryOn` | string[] | status words retried; shipped default `["error"]`; a transport failure or timeout always retries |

The shipped default is the spec's approximation; the default scenario has `intervals: []` (no retry).

## Scenario (key-value entity, id = name)

A script (FR-016 to FR-019); the full shape is `contracts/scenario.schema.json`.

| Field | Type | Rule |
|---|---|---|
| `name` | string | `default`, `slow-operator`, `cancel-after-credit`, `out-of-order`, `retry-storm`, `rotated-key`, `wrong-key`, `unknown-token`, `win-larger-than-any-bet`, `orphaned-bet`, `malformed-body`, plus any added |
| `version` | int | a shipped file re-seeds only when higher than the stored |
| `schedule` | Schedule | |
| `shape` | enum | `stub` or `harness`: which body the callbacks carry (research R7) |
| `steps` | Step[] | in order |
| `source` | string | where the expectations come from: a recording's path or the original's source (FR-018) |

Step kinds: `send` (kind, amount or a draw range, round reference `new`/`same`/`none`,
transaction reference `new`/`same-as <step>`/`unknown`, key `current`/`next`/`wrong`,
`malformed: true` for a non-JSON body), `expect` (status word; balance `moved_once` / `unchanged` /
`exact`; `httpStatus`), `wait` (a duration or a range drawn from the seed), `settle-open-rounds`,
`rotate-key` (publish next, wait the brand's bound, promote, withdraw), `pass-through` (the
default scenario's one step: send on instruction, no expectation).

## Run

Not an entity of its own: a run is the Callbacks sharing a `runId`, read through the `RunCallbacks`
view, and its verdict is the Session's (or the LoadProfile's report). `runId` is the launch token
for a session run and the profile id for a load run. A run's export is a Recording
(`contracts/run-export.md`). Runs older than the retention are discarded by the sweep: every
Callback and Session with that `runId` receives `Discarded` and is deleted (FR-022).

## RunCallbacks (view over Callback events)

One row per Callback: `runId`, `sessionId`, `brand`, `kind`, `route`, `step`, `firstSentAt`,
`lastAnsweredAt`, `attempts` (count), `outcome`, `status`, `balance`, `attemptsDetail` (the
Attempt list). Queries: by `runId` ordered by `firstSentAt` with keyset paging on `after`; by
`brand` and `lastAnsweredAt < cutoff` for the sweep; counts by `status` for a load report.

## Verdict

| Field | Type | Rule |
|---|---|---|
| `step` | int | |
| `expected` | Expectation | from the scenario |
| `observed` | `{status, balanceBefore, balanceAfter, httpStatus}` | from the Callback's last attempt |
| `passed` | boolean | |

A run passes when every verdict passed; the report names the first failing step.

## LoadProfile (workflow, id = profile id)

| Field | Type | Rule |
|---|---|---|
| `brand` | string | must be a test brand (FR-025) else refused `Forbidden` |
| `sessions` | string[] | launch tokens the brand issued (research R9) |
| `roundsPerMinute` | int | per session |
| `duration` | duration | |
| `scenario` | string | the scenario each session plays; `default` unless given; must be instruction-driven (every `send` has `on: instruction`), else the start is refused `400 scenario_not_instruction_driven`: the profile's tick issues the instructions itself, and no `ScenarioPlay` runs under a profile |
| `seed` | long | |
| `state` | enum | `launching` → `playing` → `settling` → `done` / `stopped` |
| `report` | Report | rounds sent, answers by word, p50/p95/p99 answer times, every non-`ok` answer; kept in the workflow's state as the sends answer |

A stop is a `LoadStop` key-value entity (id = profile id) the tick reads at its start (research R13).

## FreeRoundsInstance (part of a Bonus, in the `bonus-book` key-value entity, id `book`)

A provisioned bonus (FR-007) holding one instance per player.

| Field | Type | Rule |
|---|---|---|
| `bonusId` | string | a second create with the same id replaces the first, as the original does |
| `players` | string[] | order kept for the answer |
| `count` | int | per player |
| `value`, `type`, `currency`, `site` | string | stored, not validated |
| `gameCodes` | string[] | every code must be in the catalogue, else `unknown_game` |
| `instances` | map player → `{status, remaining}` | status `active` → `used` (at zero) / `canceled` (the original's spelling) |

A free spin takes, for the session's player and game, the active instance with `remaining > 0`
from the bonus with the lowest id (research R7: the original's choice is map-ordered).

## KeyPair and KeyDocument

Served at `GET /.well-known/st8-keys` by the `KeyDocument` endpoint (public). Secret store names `keys/<kid>` (PKCS#8 private, SPKI public, base64), `keys/current` and
`keys/next` (a kid each). The key document is derived: `{ current: {kid, spki}, next?: {kid, spki},
cacheSeconds }`. Rotation (`rotate-key` step or the operator route): put `next`, pause the brand's
bound, `current := next`, delete the old pair, push to every `registerKeyAt` (research R8).

## SimulatorStatus (key-value entity, id = `status`)

`sweptRuns` (total), `lastSweepAt`, `keys: {currentKid, nextKid?}`, `brands: [{slug, testBrand,
reachable?}]`. Read by `GET /v1/simulator/status`.

## Brand configuration

Not an entity: `SIMULATOR_BRANDS` (research R10), one entry per brand. Fields in
`contracts/brand-config.md`: `slug`, `baseAddress`, `testBrand`, `providerWaitMillis`,
`caPem?`, `registerKeyAt?`, `site?`.
