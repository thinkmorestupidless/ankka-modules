# Review: 026 games — eitheror's own game provider, beside ST8

**Verdict:** Request changes
**What it does:** Moves the simulator's core and ST8 adapter into a root-build library,
`provider-core`, and points the simulator at it. On that library it builds `services/provider`, the
house provider's edge in a new shared project `games`. The edge verifies brand-signed operator API
calls, keeps a brand table, runs a game registry with an in-project contract (check, stake, settle,
cancel, free rounds), delivers each callback with a retry workflow (`Deliver`), and adds three-act
key rotation, operator reads, abandon, a day export, audit and retention. Beside it: spec 003's
brand side runs an ST8 adapter under any configured name (`casino-providers`), spec 009 gains the
Anjouan profile, and spec 001 gains `games` in the shared-manifest schema.
**Should it exist:** yes. The spec's case holds: a provider that speaks ST8's protocol costs a brand
one configuration, and keeping the scenarios out of the product's classpath is the right split
(FR-005, checked by `ProductSuite`).
**Read:** In full: `GamesEndpoint`, `OperatorApi`, `ProviderEndpoint`, `Main`, `Settings`,
`BrandCache`, `BrandEntryEntity`, `GameEntity`, `GameIndex`, `BonusIndex`, `Audit`, `Metrics`, the
two views, `HouseOutbound`; in the library `Deliver`, `Schedule`, `Play`, `Session`,
`SessionEntity`, `Callback`, `CallbackEntity`, `Sweep`, `RunCallbacks`, `Keys`, `Signer`; the
contract's `Routes` and `Layout` diffs and `RoutesSuite`; game-provider's `Context`,
`ProviderConfiguration`, `St8Signature` and `Endpoints` diffs; kyc's `Policy`/`Profiles` diffs;
`shared/games/manifest.json`, `module.json`, `values.json`, both schema copies, `ci.yml`, `Justfile`;
`ProductSuite`, `ProviderIntegrationSuite`, `PerfSuite` (assertions). Spec, plan, tasks,
`contracts/game-contract.md`, data-model (Round), and research "Found during implementation".
Outside the diff: `St8Protocol.roleOf`/`read`, `Routes.serviceNamed`, `GameProviderService` (the
per-name `ProviderPathEndpoint`), the other shared manifests (issuer base), and the simulator's
test diffs, read beyond their imports. Skimmed: the feature step bindings under `steps/` (by
scenario and assertion), `ContractFeatures`, the remaining spec documents, the GLOSSARY, and the
READMEs. Not reviewed: `TestGame`/`FakeBrand` line by line, the moved files' unchanged bodies, and
the brain edits outside this repository (I only checked that they exist).
**Size:** 153 files, +7886 −280 hand-written lines, of which about 1650 are the library move
(renames). Defect-finding falls off at this size. The commits already follow the split I would
accept, so no split is asked for: (1) the library move and the simulator re-pointed, (2) the
provider, (3) the brand-side and KYC amendments with the manifest and CI. Review them in that order.

## Blocking

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/games/GamesEndpoint.scala:73-77`
  — issue (blocking): a session belongs to its game's *current* registration, so deregistering or
  removing a game strands every open round of it. `owned` admits a call only when
  `game(s.gameCode).exists(_.owner == owner)`. `GameEntity.deregister` and `remove` set the state to
  `None`, so after either one, every `check`, `stake`, `settle`, `cancel` and `GET /callbacks/{id}`
  on an open session of that code is answered `403`. A round already debited at the brand can then
  never be settled or cancelled through the contract. The player's stake stays taken, and only an
  operator by hand can fix it. The contract says the opposite: `DELETE /registry/{code}` → "open
  sessions of the code keep being served", spec *Edge cases* says "open sessions and their rounds
  are served until settled", and `ProviderEndpoint.scala:262` says the same. There is a second
  effect. Once a code is free, another game service can register it (`GameEntity.register` accepts
  any owner on an empty state) and is then admitted to the first owner's open sessions. No scenario
  covers deregistration with an open session; `contract.feature`'s last-but-one scenario checks
  only the listing.
  Suggestion: record the owner on the session at launch (the code's owner when the launch was
  admitted; `Launched` already gains fields with defaults) and check `s.owner == owner` in `owned`.
  Alternatively, keep a deregistered game as a tombstone (`Some(g.copy(listed = false))`) that the
  listing and launch skip but `owned` still reads, and refuse re-registration of a tombstoned code
  by another owner. Add a scenario: a round opened, the game deregistered, the round settled and
  acknowledged.
  Resolved: a session records the game service that owned its code at launch (`Session.owner`), and `owned` admits by it, falling back to the game's current owner for a session launched before; a contract scenario settles a round after its game is deregistered, a06baf16, 525f541c.

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/games/GamesEndpoint.scala:141-189`
  with `provider-core/src/main/scala/io/eitheror/casino/provider/core/SessionEntity.scala:45-47` —
  issue (blocking): FR-011's "a second settlement of a settled round MUST be refused" and the
  contract's "a round id already used: 409" are check-then-act in the endpoint. The entity does not
  enforce them. `settle` reads the session through `owned` (a query), checks `Round.Open`, then
  calls `play.plan`, and `SessionEntity.plan` persists any `CallbackPlanned` on a launched session.
  So two concurrent settles of one round both see `open`, and both plan and deliver a credit under
  *different* transaction ids. The brand's idempotency cannot catch that, and the player is paid
  twice. `stake` has the same shape: two concurrent stakes on one round id both pass
  `s.round(roundId).isDefined` and send two debits. The callers are first-party game services, but a
  game client that retries on its own HTTP timeout while the first call is still running is exactly
  this case. The money path is where constitution II applies. A sequential retry is refused
  correctly, because the first plan has already moved the round. Only the race is open.
  Suggestion: put the rule in the entity, where ankka's design puts it. Add a command for the house
  provider (say `planGuarded`, leaving `plan` as the simulator's scenarios need it) that refuses,
  under the entity's own serialisation: a stake on a round id that exists, a credit on a round not
  `open`, and a cancel on a round with no debit or one already cancelled. The endpoint then answers
  `409` from the entity's error, and the callback entity is scheduled only after that command
  succeeds (today `CallbackEntity.schedule` runs first, in `Play.plan`, and would leave a scheduled
  orphan). An entity-suite row with two plans of a credit on one round proves it, with no
  concurrency needed in the test.
  Resolved: the round's rules are the session's (`SessionEntity.planGuarded`, `Session.refusal`): a stake on a used round id, a second credit, a credit on a pending round and a second cancel are refused by the entity, answered 409 with the word; the simulator keeps the unguarded `plan`, a06baf16, 525f541c.

## Should fix

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/games/GamesEndpoint.scala:191-199`
  — issue (non-blocking): a cancel of a `pending` round (allowed: `Open || Pending` with a debit)
  plans the cancel and starts its own `Deliver`, but leaves the stake's `Deliver` running. The debit
  is still re-sent on the schedule after the cancel, and the two deliveries race. If the cancel
  reaches the brand before the debit ever did, the brand answers it `transaction_not_found`, and the
  next debit retry can then be applied. The round reads `cancelled` at the provider, and
  `onAnswered` ignores the late acceptance because the state is no longer `pending`. The player has
  been debited with nothing to refund it. The provider-side half is certain from the code. Whether
  the brand applies a debit after a cancel of it (a tombstone, or not) is spec 003's to say, and I
  did not confirm it. Suggestion: when a cancel is planned on a pending round, abandon the stake's
  `Deliver` first (`Deliver.abandon` with the system as actor), then send the cancel. Or refuse
  cancel on `pending` until the stake is answered or exhausted, which is what the contract's
  "409 when the round has no open debit" reads as. Add a feature row: a silent brand, a pending
  stake, a cancel, the brand recovering; one debit and one cancel applied, balance unchanged.
  Resolved: a cancel of a pending round abandons the stake's `Deliver` (as the provider, with a reason) before planning the cancel, so the debit's retries stop; a scenario and its steps cover it, 1846eb73.

- `provider-core/src/main/scala/io/eitheror/casino/provider/core/Session.scala:72-73` — issue
  (non-blocking): a credit moves its round to `won`/`lost` (with `settledAt`) when it is *planned*,
  not when the brand acknowledges it. Spec *Edge cases* says a credit refused until the schedule is
  exhausted leaves "the round … open at the provider". The operator's round reads and the day
  export (`ProviderEndpoint.exportJson`, `RoundsByDay.settled`) then list as won, with its win, a
  round the brand never paid. The export is what finance compares with the brand's books, and it
  carries no acknowledgement. Suggestion: either keep the state as is and say so in the spec, while
  putting the credit's callback outcome (`acknowledged`/`refused`/`exhausted`/`abandoned`) in the
  round read and the export line; or add a `settling` state that becomes `won`/`lost` on
  acknowledgement. The first is smaller.
  Resolved: the first suggestion. The round's state stays the game's settlement, and each export line carries `settlement`, the outcome of the call that settled it (`acknowledged` once the brand paid); the round read already lists each callback with its outcome; the routes contract names the field, d1c2a09c.

- `provider-core/src/main/scala/io/eitheror/casino/provider/core/Sweep.scala:28-38` with
  `RunCallbacks.scala:95` — issue (non-blocking): two problems with retention.
  (1) The sweep discards a run once its callbacks' last answer is older than the cutoff, whatever
  their outcome. An `exhausted` or `abandoned` credit, which is winnings the brand never paid and the
  one record the brand's books do not hold, is deleted after 90 days along with its session.
  (2) Retention is driven only by callbacks. A demo session, or a launch never checked, sends none,
  so no `RunCallbacks` row names it and it is never swept. FR-023 asks for every record to be swept
  after the retention.
  Suggestion: exclude runs holding a callback in `exhausted`, or in `abandoned` without an
  operator's close, from `endedBefore` (or keep such a callback's row and session until an operator
  resolves it). Sweep sessions with no callbacks from `SessionsByBrand` by `launchedAt`.
  Resolved: the sweep keeps a run holding an exhausted credit (`Sweep.keepExhausted`) until an operator abandons it, and sweeps sessions that sent nothing by `launchedAt` (`SessionsByBrand.silentBefore`); an operators scenario covers both, be6e4732.

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/brands/BrandCache.scala:22-41`
  — question → should fix: the brand table is a process-local copy, refreshed at start and by the
  replica that served the write. The provider is one service with no stated instance count (neither
  `service.json` nor the manifest sets one). With a second replica, a brand added, a key changed or
  `realMoney` set at one replica is not seen by the other until it restarts. A launch there is then
  refused, or admitted under the old operator key, and FR-017's "a change MUST apply without a
  redeploy" holds only by luck. If the provider is pinned to one instance, say so in the README and
  the manifest. Otherwise, refresh on a recurring timer (the `wake` timer already runs every 30 s),
  or read through to the entity on a cache miss and on a signature failure.
  Resolved: every replica re-reads the brand table on the 30 s wake (`Sweep.onWake = cache.refresh`), so a change applies everywhere within 30 s without a redeploy; the README and the brand-entry contract say so, c3a69376.

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/games/GamesEndpoint.scala:204-206`
  — issue (non-blocking): `GET /callbacks/{id}` for an id no callback holds throws: `CallbackEntity.get`
  answers `NotFound` as an error, so the call throws `CommandError` uncaught, and the game gets a 500,
  not the 404 the code means. The `!c.scheduled` branch can never run. Suggestion:
  `Try(...).toOption.filter(_.scheduled)` as `ProviderEndpoint.scala:199` already does.
  Resolved: an unknown callback id answers 404 (`Try(...).toOption.filter(_.scheduled)`), 525f541c.

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/operator/OperatorApi.scala:59-85`
  — issue (non-blocking): two launch inputs are taken unchecked, and both fail later.
  (1) `currency` is stored as sent, and every stake and settle then calls `Currency.unsafe(s.currency)`
  (`GamesEndpoint.scala:123`), which throws on a code `contract.money` does not know. The session
  exists and was checked, then the first stake answers 500. Refuse the launch instead (`Currency.from`)
  in the operator API's error shape.
  (2) A second launch of a ticket the *same* brand already launched re-persists `Launched`
  (`SessionEntity.launch` is unconditional). It overwrites the game code, currency and `demo` of a
  session that may be checked and hold open rounds. A brand's retried launch is harmless only while
  the fields are identical. Answer it the existing `game_url` when they match and refuse it when they
  differ, in the entity.
  Resolved: (1) a launch in a currency ISO 4217 does not list is refused `invalid_currency` before any session exists (`Currency.from` checks only the shape, so the JDK's table is asked too); (2) `SessionEntity.launchOnce` answers an identical repeat unchanged and refuses the ticket with other terms `ticket_used`; `LaunchSuite` and an entity row cover both, d980d23a.

- `provider-core/src/main/scala/io/eitheror/casino/provider/st8/Signer.scala:57-75` and
  `services/game-provider/.../provider/st8/St8Signature.scala:58-85` — suggestion: the P-256
  public-key derivation (ECDH with the generator, the square root, a probe signature) is written
  twice. The library copy is used only by `SignerSuite`; no main code calls it. Delete the library's
  copy (and its tests), or move one implementation to `contract`, where both builds can reach it. Two
  hand-rolled copies of curve arithmetic will drift.
  Resolved: the library's copy and its tests are deleted; game-provider's `St8Signature.publicKeyOf` is the one, 4bf8c1c1.

- `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala:101-107` with
  `services/provider/.../admin/ProviderEndpoint.scala:39-46` — suggestion: T017's
  `Routes.installationOperatorHolding` is added and tested but has no caller. `ProviderEndpoint`
  hand-rolls `asInstallationOperator`, which also admits `platform_admin` (the routes contract names
  only `games`). The result is inconsistent: `platform_admin` may rotate keys, remove a game and read
  every brand entry, but may not write a brand entry or abandon a callback (`asOperator(slug, "games")`
  requires `games`). Decide whether `platform_admin` is an installation operator of the provider,
  express it once (one helper, the same role set everywhere), and drop the unused ACL or use it.
  Resolved: decided with Trevor (research, *Found during implementation*): `games` is the provider's installation operator, `platform_admin` reads the brand table only, as the routes contract says; the unused `Routes.installationOperatorHolding` and its test are dropped, 4bf8c1c1.

- Description (spec FR-003, FR-014; *Edge cases*) — issue (non-blocking): three claims have no hunk
  behind them. (a) "spin values" in the operator API and "spin values MUST be the game's": no route,
  in the provider or in the simulator it replays. (b) "The brand answers a retried debit differently
  from the first attempt: … the difference is recorded on the round, and the operator sees it":
  nothing records a difference. With `Deliver` retrying only when no definitive answer came, this may
  be moot, and if so the spec should say so. (c) The contract's "A game service that does not serve
  these is refused registration of any game with `demo` or free rounds support": registration does
  not probe the game. Implement each or strike it from the spec, so 027 does not build against them.
  Resolved: decided with Trevor. (a) spin values are struck from FR-003 and FR-014 and moved to *Out of scope* for spec 027; (b) the edge case now says why there is never a second answer to compare (`Deliver` ends at the first definitive one); (c) the registration probe is struck from the game contract, a game lacking the route being found at use (`failed`), e56cbfcd.

- `provider-core/src/main/scala/io/eitheror/casino/provider/st8/Keys.scala:64-78` — suggestion
  (if-minor): on the real-money product, `withdrawOld` keeps the withdrawn private key in the secret
  store, and `signingKey` still offers `withdrawn` and `wrong`. Those exist for the simulator's
  scripted scenarios (FR-005's misbehaviour), now on the provider's classpath. Nothing in the
  provider calls them, so this is hygiene, not a hole. Still, a withdrawn private key that stays
  readable is one more secret to lose. Give the provider a withdrawal that deletes
  `keys/<kid>/private` (the simulator keeps its own), or move the scenario choices into the
  simulator's `KeyRing`.
  Resolved: the provider's withdrawal is `Keys.withdrawForGood`, which deletes the withdrawn key's private half and keeps its id and public half; the simulator keeps `withdrawOld`; `KeysFeatures` asserts the private half is gone, 4bf8c1c1.

## Nits

- `provider-core/.../core/Deliver.scala:129`, `Sweep.scala:18-25` — nitpick (non-blocking):
  process-wide `@volatile var`s set by `Main` (`Deliver.schedule`, `Sweep.retention`, `views`,
  `wakeEvery`, `onSwept`) carry configuration into components. This follows `Wiring`'s existing
  pattern, but each one is a value two suites in one JVM can overwrite for each other. One holder
  installed by `Wiring.install` would keep them together.
  Left: nit. One holder installed by `Wiring.install` is the better shape, and it touches the simulator's wiring too; for a change of its own.
- `services/provider/.../operator/OperatorApi.scala` — nitpick (non-blocking): fully-qualified names
  inline (`com.fasterxml.jackson.databind.JsonNode`, `com.thinkmorestupidless.ankka.sdk.ServiceClient`,
  `io.eitheror.casino.provider.edge.admin.Metrics`) where an import reads better; the same in
  `Main.scala:31-32` and `HouseOutbound.scala:15`.
  Left: nit.
- `provider-core/.../st8/Signer.scala:58`, `Keys.scala:94` — nitpick (non-blocking):
  `key.asInstanceOf[ECPrivateKey]` and `entry(current).get` are partial. A non-EC key, or a current
  kid whose public half is missing, throws an unhelpful exception. A `match` with a named refusal
  would say what is wrong.
  Left: nit.
- `services/provider/.../operator/OperatorApi.scala:163` — nitpick (non-blocking): a bonus id
  another brand already holds is answered `unknown_game`. The stand-in's word for "no such bonus" is
  `{"status":"error"}` (as `bonus/get` answers). `unknown_game` sends the brand's operator looking at
  the wrong thing.
  Resolved: a bonus id another brand holds is answered `{"status":"error"}`, as the stand-in answers a bonus it cannot take; `LaunchSuite` covers it, d980d23a.

## Questions

- `provider-core/.../core/Sweep.scala:41-53` (`wake`) — question: research T027 records that an ankka
  0.13 workflow pause is an in-memory timer, and adds a 30-second wake as the remedy. The
  constitution's scope rule says ankka gaps are ankka specs, not workarounds. Is an ankka spec (or an
  issue) filed for durable pauses, so the wake has an owner and an end? An answer naming the ankka
  spec settles it.
  Answered: ankka fixed it upstream: "A paused workflow resumes after passivation and restart" (ankka #160) is in v0.14.0. The wake stays while this repository is on 0.13 and goes with the move to 0.14.
- `provider-core/.../core/CallbackEntity.scala` (`abandon`) and `ProviderEndpoint.scala:196-216` —
  question: an `exhausted` callback is listed as unanswered, but abandoning it is refused `409`
  (`Final` includes `Exhausted`, and `Deliver` is `Done`). FR-021 reads as "list the unacknowledged
  and abandon one". Is the exhausted list meant to be closed some other way? If not, allow an
  operator to abandon an exhausted call, which records who closed it and why.
  Answered: no, that was a defect. `operators.feature` already says an exhausted credit is kept until an operator abandons it. `CallbackEntity.abandon` now closes an exhausted call with who and why, and the route closes the record directly; the operators step abandons one, a6eebf2c.
- `services/provider/.../admin/ProviderEndpoint.scala:277-283` — question: `/v1/provider/metrics` is
  behind a staff token holding `games` or `platform_admin`. How does the installation's Prometheus
  scrape it? It also computes the unanswered gauge by one view query per brand with a 10,000-row
  limit on each scrape. If scraping is not set up yet, say so in the README.
  Answered: scraping is not set up. The README says so, and that it waits on a scrape identity the installation can issue; the per-brand query is acceptable at a handful of brands, 8363a6c9.
- `services/provider/.../games/GamesEndpoint.scala:28,33` — question: `Routes.serviceNamed` admits
  `Caller.Local` as owner `local`. Can a request arriving at a deployed provider ever be classified
  `Local` (a sidecar, a port-forward)? If it can, it owns whatever it registers. An answer that
  ankka never classifies a deployed caller as `Local` settles it.
  Answered: no. In ankka 0.13 a request is `Caller.Local` only when the server runs without TLS, outside a cluster (`HttpServer` logs it, `Admitter.local`). A deployed provider serves mTLS, so a sidecar or a port-forward reaches the TLS listener and is never `Local`.
- thought: `SessionsByBrand.byBrand` and `RoundsByDay.settledBetween` filter and order on `jsonb`
  expressions (and `jsonb_array_elements`) with no index. At "a handful of brands" this is fine. At
  90 days of rounds, each export day scans the whole view table. Note it for when the view runtime
  allows an index.
  Answered: noted. The view runtime in 0.13 declares no index; the full scans are acceptable at a handful of brands and are to be revisited when views take an index.

## Praise

- `services/provider/src/test/.../ProductSuite.scala` — praise: it reads the real forked runtime
  classpath and first asserts that `Deliver.class` is present, so the test cannot pass by reading
  an empty classpath. FR-005's product rule is held by something that fails.
- `services/provider/src/test/.../ProviderIntegrationSuite.scala:34-73` — praise: the silent-brand
  test makes the brand *process* the slow debit while the provider never hears. It then asserts that
  every retry carried a byte-identical body and that the balance moved exactly once per callback.
  That is SC-004 checked by its mechanism, not by its outcome alone.
- `provider-core/.../core/Schedule.scala:78-103` — praise: `fromJson` refuses each malformed part by
  its path (`schedule.intervals[1].every`), and only the last interval may run to the cap, so a bad
  `PROVIDER_RETRY_SCHEDULE` stops the start saying what to fix.
- `specs/026-games-provider/research.md` "Found during implementation" — praise: every divergence
  from the plan (five endpoints not six, the key document's `keys`, the developer exception, the
  wake) is written down with its reason and who decided it.

## Out of scope

- note: `SessionEntity.launch` is unconditional in the simulator too (pre-existing). The should-fix
  above asks for the provider's guard only. Whether the simulator wants it is spec 020's call.

## Checks applied

General checklist groups applied: Correctness, Design and scope, Error handling, Concurrency and
state, Security (entry points and authorisation, signature verification, key handling, CI and
Justfile as code), Data and migrations (event and state evolution: new fields carry defaults,
manifests are explicit strings, and the journal drop at the package move is documented), Performance,
Tests, Observability, API and compatibility (the `casino-providers` default adds `eitheror` to an
unrendered brand, which only exposes a refusing `/eitheror` prefix: accepted), Dependencies and
build (no new third-party dependency; `% Test` scoping correct), Documentation, Commit and PR
hygiene (conventional `type(026):` headers, attribution trailers present). No group skipped.
Language skill: `pr-review-scala` (bundled; no repository override). Applied: concurrency and actors
(entity fold purity holds, `Session.applied` is pure; check-then-act outside the entity, reported),
serialization and versioning, types and totality, error handling, collections, tests, build, API
design, idioms. Skipped what `-Wunused:all -deprecation -feature` owns.
Project rules read: `CLAUDE.md` (root and the eitheror brain), the constitution's principles as the
plan cites them. `specs/009-kyc/REVIEW.md` is an earlier review report, not a checklist; it has no
`## pr-review` section, so nothing was skipped or loaded from it. No `.claude/rules/`.
Tests: not run, at the caller's instruction (sbt suites take minutes and need Docker). The author
records the runs in research T060. Every finding above was confirmed by reading the code, except
the brand-side half of the cancel-while-pending finding and the multi-replica finding, which need
spec 003's cancel semantics and the deployment's instance count to settle.

## Second round

# Review: 026 games — the fixes for the first round (7e28f60d...HEAD)

**Verdict:** Approve with comments. The two blocking findings are fixed in the entity, where they
belong. Three of the should-fix findings below are expected before merge: the brand-table refresh
that runs on one instance only, the window between `planGuarded` and `schedule`, and the spec's
edge case that still contradicts the export decision. The rest are optional.
**What it does:** It answers the first round. The session now holds a round's rules
(`SessionEntity.planGuarded`) and its owner at launch. A pending debit's delivery is abandoned
before the debit is cancelled. The day export carries `settlement`. The sweep keeps exhausted
calls and sweeps sessions that never made a money call. The brand cache is re-read on the wake.
A launch refuses an unknown currency and a reused ticket. The withdrawn key's private half is
deleted. The P-256 derivation copy and the unused ACL are gone. Spin values and the registration
probe are struck from the spec.
**Should it exist:** yes. Each commit answers one finding and names it.
**Read:** In full, the range's diff: `Play`, `Session`, `SessionEntity`, `CallbackEntity`, `Sweep`
(whole file), `Keys`, `Signer`, `GamesEndpoint`, `OperatorApi`, `ProviderEndpoint` (the diff and
lines 30–75 and 196–228), `SessionsByBrand`, `Main`, the `Routes` and `RoutesSuite` deletions, every
test diff (`EntitySuite`, `LaunchSuite`, `BrandCacheSuite`, `ContractFeatures`, `OperatorsFeatures`,
`KeysFeatures`, `FreeRoundsFeatures`, `ProviderTestKit`), both feature files, the spec, plan,
contracts, README and research diffs. Outside the diff I opened `Session.onPlanned`/`onAnswered`,
`CallbackEntity` whole (`schedule` is idempotent and `AbandonedBy` sets the outcome),
`Callback.Final`, `RunCallbacks.endedBefore`, `Amounts.exponent`, `St8Protocol.check`, the check
route (`GamesEndpoint:106-125`), `ProviderTestKit.schedule`, `St8Signature.publicKeyOf`, and
ankka's timer documentation (`docs/build/timers.md`, `docs/reference/glossary.md` in the local
ankka checkout, which is at 0.16).
**Size:** 33 files, +854 −166, of which 289 lines are the first-round report. That leaves about 560
lines of code and spec. Each commit is one finding, so no split is asked for.

## Blocking

None.

## Should fix

- `provider-core/src/main/scala/io/eitheror/casino/provider/core/Sweep.scala:78-81` with
  `services/provider/README.md:91-92` and `contracts/brand-entry.md:34` — issue (non-blocking): the
  brand-table refresh runs on one instance, not on every replica. `wake-deliveries` is a named,
  database-backed recurring timer. ankka's timer page says "One instance of the service runs a
  sweeper, as a cluster singleton", and the glossary adds "singletons such as the timer sweeper run
  on exactly one". So `Sweep.onWake = cache.refresh` refreshes only the instance that fires the
  timer. The other replicas keep their stale copy until they restart, which is the problem first
  raised in round one. The README and the brand-entry contract now promise "within 30 s at every
  other". `BrandCacheSuite`'s new test calls `onWake` by hand in one JVM, so it cannot catch this.
  I read the current ankka docs (0.16); that the 0.13 runtime the provider is on works the same is
  my inference, though the sweeper has always run as a singleton.
  Suggestion: refresh in-process on every instance, outside the timer. A scheduled executor in
  `Provider` started with the cache (and stopped on shutdown) is enough. Or read through to
  `BrandEntryEntity` on a cache miss and on a signature failure. Or pin the provider to one
  instance and say so in the manifest and README. Then correct the two documents.
  Resolved: each process refreshes its own copy every 30 s on a daemon thread (`BrandCache.refreshEvery`, started by `Provider.prepare`); `Sweep.onWake` is gone from the library; the test runs two copies with their own refresh and sees another replica's write in both; the README and the brand-entry contract name the mechanism, 6487b4de.

- `provider-core/src/main/scala/io/eitheror/casino/provider/core/Play.scala:57-64` — issue
  (non-blocking): the guarded order leaves a credit planned with no record of the call when
  `schedule` fails after `planGuarded`. `planGuarded` persists `CallbackPlanned`, so a settle has
  already moved the round to `won`/`lost`. If `CallbackEntity.schedule` then throws (a timeout, a
  restart mid-request), the game gets a 500. No `Callback` exists, no `Deliver` starts, and
  `RunCallbacks` lists nothing for an operator to see. A retried settle is refused `round_not_open`.
  The winnings are never sent, and nothing in the provider marks them as unsent. A stake fails the
  same way, less harmfully: its round is stuck `pending` with no debit sent. The first-round
  suggestion asked for this order, and its cost was mine to point out. `CallbackEntity.schedule` is
  already idempotent (`if currentState.scheduled then reply(Done)`), so the safer order is
  available. Suggestion: schedule first, then `planGuarded`. On the entity's refusal, end the
  scheduled callback (`CallbackEntity.end(Callback.Refused)`, or discard it) before answering 409. A
  failure between the two calls then leaves a scheduled, visible, abandonable call instead of an
  invisible planned credit. Add an entity or kit row that refuses a guarded plan and asserts that
  the callback did not stay pending.
  Resolved: the suggested order. `Play.plan` schedules the callback first, then `planGuarded`; on the session's refusal the callback is discarded before the 409, so a failure between the two leaves a visible, abandonable call; the pending-debit step asserts a refused settle leaves no credit on the run, b6aadc5c.

- `specs/026-games-provider/spec.md:268-271` — issue (non-blocking): the export resolution chose to
  "keep the state as is and say so in the spec", but the spec still says the opposite. The *Edge
  cases* line for a credit refused until the schedule is exhausted still reads "the round stays
  open at the provider". `Session.onPlanned` moves the round to `won`/`lost` when the credit is
  planned, and the export now relies on that. Suggestion: rewrite the line. The round reads
  `won`/`lost` at the provider once the credit is planned. Whether the brand paid is the credit's
  outcome (`exhausted`), shown in the round read and in the export's `settlement`, and the call is
  kept past the retention until an operator abandons it.
  Resolved: the edge case now says the round reads `won` or `lost` once the credit is planned, whether the brand paid is the credit's outcome (`exhausted`) in the round read and the export's `settlement`, and the call is kept past the retention until an operator abandons it, 0a394005.

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/admin/ProviderEndpoint.scala:41-55`
  — todo: nothing tests the `platform_admin` decision. No test or feature in `services/provider`
  mentions `platform_admin`. A later edit that adds `"platform_admin"` to an `asInstallationOperator()`
  call, or removes it from `GET /brands`, would pass every suite. Suggestion: one test with a
  `platform_admin` staff token. `GET /brands` answers 200. `POST /keys/start`,
  `DELETE /registry/{code}` and `PUT /brands/{slug}` answer 403.
  Resolved: `RolesSuite`: a `platform_admin` token reads `GET /brands` (200) and is refused the key rotation, the keys, the registry, a game's removal and a brand write (403), 0f0d33ba.

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/admin/ProviderEndpoint.scala:234-237`
  — suggestion (if-minor): `settlement` makes one `CallbackEntity.get` per exported round. The day
  export is a single request, so a brand with tens of thousands of rounds a day makes as many
  entity calls in it. `RunCallbacks` already holds each callback's `outcome` by run. Suggestion: one
  `RunCallbacks.byRun` read per session in `settledBetween` (a session is one run), with outcomes
  looked up by callback id from that.
  Resolved: `settlement` reads `RunCallbacks.byRun` once per exported session and looks each round's call up by id, 0a394005.

- `services/provider/src/test/scala/io/eitheror/casino/provider/edge/steps/OperatorsFeatures.scala:204`
  — suggestion (if-minor): the export's `settlement` is asserted as `acknowledged` or `pending`, and
  no test exports a round whose credit was exhausted or refused. That second case is why the field
  exists. A `settlement` stuck at `pending` for every round would pass. Suggestion: in the
  exhausted-credit sweep scenario, or a row of its own, export the day and assert
  `"settlement":"exhausted"` for that round, and `acknowledged` for a round whose credit the brand
  answered.
  Resolved: the exhausted-credit scenario exports the round's day and asserts `settlement` `exhausted` for it, 0a394005.

## Nits

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/admin/ProviderEndpoint.scala:232-234,334-344`
  — nitpick (non-blocking): two stacked scaladoc blocks. `settlement` was inserted under
  `settledBetween`'s doc ("Every session of `slug`…"), so that doc now heads the wrong method, and
  `exportJson` carries its old doc and the new one. Move the first block back above
  `settledBetween` and merge the two above `exportJson`.
  Resolved: the two docs are merged above `exportJson`, and `settledBetween`'s is back above it, 0a394005.
- `services/provider/src/test/scala/io/eitheror/casino/provider/edge/steps/ContractFeatures.scala:288`
  — nitpick (non-blocking): `Thread.sleep(3000)` to show that no debit follows the cancel. With the
  kit's 300 ms schedule and 1 s answer wait, three seconds covers two retries, so the assertion can
  fail as it should. Still, it adds three seconds to every run. A wait expressed in schedule
  periods, read from `ProviderTestKit.schedule`, would say why it is three seconds.
  Left: nit.
- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/views/SessionsByBrand.scala:39-56`
  — nitpick (non-blocking): the doc says no callback names a session with an empty `sent`. A
  checked session that never staked does have one: its `check` goes through `play.send`, and since a
  check has no transaction id `sent` stays empty. `alsoSweep` can then discard such a session
  launched just before the cutoff while its check callback, answered after the cutoff, stays. The
  next sweep removes the callback, and `SessionEntity.discard` on the deleted session answers
  `Done`. So this is harmless, but "a run is swept whole" is briefly untrue. Correct the comment,
  or add `AND NOT EXISTS` a `RunCallbacks` row for the run.
  Resolved: the comment now says a check callback can outlive its session by one sweep, which removes it, 0f0d33ba.
- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/operator/OperatorApi.scala:208`
  — nitpick (non-blocking): `java.util.Currency.getInstance` also lists `XAU`, `XXX`, `XTS` and the
  other funds and metals, whose fraction digits are −1. `Amounts.exponent` falls back to 2 for those,
  so nothing breaks, but a brand can launch in gold. `getDefaultFractionDigits >= 0` in
  `knownCurrency` would refuse them.
  Resolved: `knownCurrency` requires a fraction-digit count of zero or more, so the metals and funds codes are refused, 0f0d33ba.

## Questions

- `services/provider/src/main/scala/io/eitheror/casino/provider/edge/games/GamesEndpoint.scala:189-193,227`
  — question: abandoning the stake's `Deliver` stops further attempts, but an attempt already in
  flight when the cancel is planned can still reach the brand after the cancel. The new scenario
  asserts the provider's side (no debit sent after the cancel, the stake abandoned). It does not
  assert the brand's balance after the brand recovers, which was the round-one row's point. Does
  spec 003's brand refuse a debit whose transaction a cancel already named (a tombstone)? An answer
  citing the 003 requirement, or a brand-balance assertion in the scenario, settles it.
  Answered: no. Spec 003 keeps no tombstone: a cancel naming a transaction the brand never recorded is answered `transaction_not_found` (spec 003 User Story 3, `features/game-provider/cancels.feature`), and a debit landing after it is applied. So an attempt already in flight when the cancel goes can still charge the player for a round the provider cancelled. ST8 has the same race against this brand. Closing it is the brand's (a cancel recorded as a tombstone that a later debit of the same transaction is refused against), so it is an amendment to spec 003, raised with the pull request, not a change on this branch.

## Praise

- `provider-core/src/main/scala/io/eitheror/casino/provider/core/Session.scala:61-71` — praise:
  `Session.refusal` is a pure function of the state and the role. The entity applies it, and
  `EntitySuite` proves the race sequentially (two credits, two stakes, two cancels) with no
  concurrency in the test, which is the cheapest proof that holds.
- `services/provider/src/test/scala/io/eitheror/casino/provider/edge/steps/ContractFeatures.scala:333-346`
  — praise: the deregistration scenario puts the code back with its first owner before asserting.
  A failure cannot cascade into the later scenarios, and the comment says so.
- `provider-core/src/main/scala/io/eitheror/casino/provider/core/SessionEntity.scala:25-35` —
  praise: `launchOnce` keeps `launch` for the simulator and adds the guard beside it. The "out of
  scope" note from round one stays a decision for spec 020, as it should.

## First-round resolutions

- Blocking 1 (session held to its game's current owner): **holds**. `Session.owner` is recorded at
  launch and `ownedBy` admits by it, falling back for older sessions. The new scenario settles after
  deregistration and refuses the new owner with 403.
- Blocking 2 (check-then-act on round rules): **holds**. `planGuarded` refuses under the entity's
  serialisation, `refusedByRound` answers 409 with the word, and the simulator keeps `plan`. The
  ordering it introduced has a failure window, raised above under Should fix.
- Should fix, cancel of a pending debit: **holds** for the provider's half. `stopDelivery` abandons
  the stake's `Deliver` before the cancel is planned, and the scenario shows no debit after it. The
  brand's half is still the open question above.
- Should fix, credit settled at plan, not at acknowledgement: **does not hold in full**. The
  export's `settlement` is implemented and documented in the routes contract, but the spec's edge
  case still says the round stays open (Should fix above).
- Should fix, retention of unpaid winnings and silent sessions: **holds**. `keepExhausted` keeps
  any run with an exhausted call. `AbandonedBy` moves the outcome to `abandoned`, so an operator's
  close releases it, and `alsoSweep` sweeps sessions with no money call (one comment
  inaccuracy, Nits).
- Should fix, process-local brand cache: **does not hold**. The refresh rides a timer that fires on
  one instance (Should fix above).
- Should fix, `GET /callbacks/{id}` 500: **holds**.
- Should fix, unchecked launch inputs: **holds**. `invalid_currency` comes before any session, and
  `launchOnce` answers an identical repeat or `ticket_used`. `LaunchSuite` covers both. The metals
  are a nit.
- Should fix, duplicated P-256 derivation: **holds**. The library copy and its tests are deleted,
  and `St8Signature.publicKeyOf` is the only one left.
- Should fix, `platform_admin` and the unused ACL: **holds**. The code matches the decision
  (`games` everywhere, `platform_admin` on `GET /brands` only), and `installationOperatorHolding`
  and its test are gone. No test pins it (Should fix above).
- Should fix, claims with no hunk (spin values, the retried-debit difference, the registration
  probe): **holds**. All three are struck or reworded as the decisions record. FR-003 and FR-014 and
  *Out of scope* agree.
- Should fix, withdrawn private key kept: **holds**. `withdrawForGood` deletes `keys/<old>/private`,
  and `KeysFeatures` asserts the private half is gone and the public half stays.
- Nit, bonus id held by another brand: **holds**. It answers `{"status":"error"}`, covered by
  `LaunchSuite`.
- Nits left (the `@volatile` holders, the fully-qualified names, the partial accessors): left as
  stated. That is acceptable for nits.
- Question, durable pauses: **answered**. ankka #160 in 0.14 is a named owner and end for the
  wake.
- Question, abandoning an exhausted callback: **holds**. `CallbackEntity.abandon` admits
  `Exhausted`, the route closes the record directly, and an entity row and the operators step cover
  it.
- Question, metrics scraping: **holds**. The README says scraping is not set up and why.
- Question, `Caller.Local` and thought on view indexes: **answered**. Nothing to verify in this
  range.

## Checks applied

General checklist groups applied: Correctness, Design and scope, Error handling, Concurrency and
state (the guarded plan, the cancel and delivery race, the per-process cache), Security (the ACL
narrowing, the key deletion), Data and migrations (new fields `owner` on `Launched`, `Launch` and
`Session` default to `""`; new command wire names `launch-once` and `plan-guarded` are additive),
Performance, Tests, Observability, API and compatibility (the export gains a field; `Routes` loses a
public method that had no caller outside this branch), Documentation, Commit and PR hygiene
(conventional `type(026):` headers, each naming the finding it resolves, trailers present). Skipped:
Dependencies and build (no dependency or build change). Language skill: `pr-review-scala` (bundled;
no repository override). Applied: concurrency and actors (entity rules under serialisation, fold
purity of `onPlanned`), serialization and versioning, types and totality (`RoundRole.valueOf` is
fed `role.toString` only), error handling (`NonFatal` in the wake, `CommandError` narrowed by
prefix), collections, tests, API design, idioms. Skipped: what `-Wunused:all -deprecation -feature`
owns. Project rules: `CLAUDE.md` (repository and brain) and the 2026-11-10 decisions in research
*Found during implementation*, judged for conformance only. `specs/026-games-provider/REVIEW.md` is
not a checklist file; the summary picked up this report. Tests: not run, at the caller's
instruction (another process holds the build). The single-instance timer finding rests on ankka's
documentation, not on a run.
