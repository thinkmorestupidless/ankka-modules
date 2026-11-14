# Review: public-api (spec 016), origin/build-ankka-0.16...HEAD

**Verdict:** Request changes (before the fixes below)
**What it does:** Replaces the GraphQL partner API (old 016) with the original's REST public API. A new Scala service, `services/public-api`, serves the original's 22 OpenAPI operations from views it builds from the brand's topics. Callers are admitted by access keys that the brand's operators issue. Every read is audited to `platform.audit`. To supply the fields the contract needs, it changes the contract library and four producers (`players`, `game-provider`, `wallet`, `rewards`) additively, and it adds a new `round_opened` family on `casino.rounds`.
**Should it exist:** Yes. Constitution I makes the running original the specification, and the original replaced its GraphQL API with this REST API on 2026-10-22. Access keys in place of realm clients is a decision the spec records, with reasons.
**Read:** In full: spec.md and plan.md. Skimmed: tasks.md (all 96 tasks ticked), the research.md sections on R7, R16 and *Found during implementation*, and every main source file of `services/public-api` (api, keys, records/Views, Records, Intake, FactId, Lenient, audit, games, stats/ComputeStats, StatsPointer, PlayerFigures, contract, wire/Fields, wire/Paging, Settings, PublicApiService). Also read: `build.sbt`, `service.json`, `modules/public-api/module.json`, the contract `Routes.scala` and `Others.scala` diffs, the `game-provider` and `wallet` main diffs, the CI and Justfile diffs, and these tests: PipelineSuite, ContractAmendmentSuite, QuotaSuite, parts of RecordSuites, AuditTrailSuite, ActivityFeatures and StatsPerfSuite. Outside the diff I opened the `casino.rounds` consumers: `services/rewards/.../consumers/RoundsConsumer.scala`, `services/players/.../reads/ActivityView.scala` and `services/reporting/.../intake/RoundCheck.scala`, plus `modules/core/module.json` for `erasure-targets`. Skimmed for shape only: the recordings under `tests/port/public-api/*.json` (about 26k lines; I grepped them for tokens and secrets, and every credential is a `{name}` placeholder). Skimmed for secrets and kubectl use only: the Go recording driver `tests/original/public-api-driver/` (2.7k lines). It is test tooling against a local original, and I applied no Go checklist to it, so it needs another reader if it is to be kept as maintained code. Not reviewed line by line: `reference.html`, the verbatim and amended OpenAPI YAML (ContractAmendmentSuite checks their difference), and the features, glossary and doc prose (`just features` checks them).
**Size:** 224 files, +42,797 −1,058. About 26k of that is recorded fixtures and about 3.7k is docs. The hand-written code is about 8.6k lines of Scala and 2.7k of Go. This is well above the size where defect-finding falls off. The split I would accept is (1) the contract and the four producer amendments with their tests, then (2) the service, then (3) the driver, recordings and parity suites. Split (1) is where the blocking defect below hides: it changes every consumer of `casino.rounds`, and nothing in the service PR exercises those consumers.

## Blocking

- `contract/src/main/scala/io/eitheror/casino/contract/facts/Others.scala` (new `RoundOpened` family) with `services/game-provider/src/main/scala/io/eitheror/casino/gameprovider/facts/RoundFacts.scala:18` (now publishes it). Issue (blocking): three existing consumers of `casino.rounds` match only `RoundSettled` on the sealed `CasinoRoundsFact`. Once they are rebuilt against this contract, they throw `MatchError` on every `round_opened` fact, and game-provider publishes one for every round.
  - `services/rewards/src/main/scala/io/eitheror/casino/rewards/consumers/RoundsConsumer.scala:39`: `round(fact)` has `case f: RoundSettled` only.
  - `services/players/src/main/scala/io/eitheror/casino/players/reads/ActivityView.scala:69`: `ActivityLines.round` has `case r: RoundSettled` only.
  - `services/reporting/src/main/scala/io/eitheror/casino/reporting/intake/RoundCheck.scala:120`: `onMessage` has `None` and `Some(r: f.RoundSettled)` only.

  Each of these matches was exhaustive while the family had one member. Now it is a compiler warning, and these builds do not run `-Werror`. Lenient decoding does not help: the new contract decodes `round_opened` successfully, so the match is what throws. Free-spins settlement in rewards, the player activity view and reporting's round reconciliation all stop or skip at the first opened round, and no suite covers it. The producer suites and reporting's suites never feed `round_opened` to these consumers. Suggestion:

  ```scala
  // rewards RoundsConsumer.round and players ActivityLines.round
  case _: RoundOpened => None
  // reporting RoundsFactsConsumer.onMessage
  case Some(_) => effects.ignore()
  ```

  Add one test per consumer that feeds it a `RoundOpened`. Then grep the repository for every other `match` on a `*Fact` sealed trait that this change extends, and consider `-Wconf:msg=match may not be exhaustive:e` in these builds, so that a new family breaks the build rather than the consumer.

  Resolved: each of the three consumers names `RoundOpened` and ignores it, in an exhaustive match, with a test per consumer feeding it an opened round; `round_opened` is the only family this branch adds. 72dbf02b

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/Pipeline.scala:40`, `:54-55`. Issue (blocking): any internet caller can force writes. A request carrying any well-formed key, `ak_<16 base32>_<43 chars>`, is treated as having "named" a key even when no such key exists. After the refusal, `Usage.served` persists a `KeyLog(<that id>)` event, and the read-audit consumer turns that event into a `platform.audit` record. The rate check runs only after admission, so these refusals are never throttled.
  - Why it matters: one unauthenticated client minting random key ids creates an entity and a journal event per request, and an audit record on the shared topic, without bound. That is a storage and topic-volume amplification attack on the brand's audit, the thing a regulator reads.
  - The spec currently mandates this: FR-036 records every request that "named a key", and FR-018 puts unknown keys under `key_invalid`.
  - Suggestion: record a refusal only when the key id resolves to a key that exists (`AccessKey.admission` can say so). An unknown key has no owner to audit against, and a deleted one keeps its record. Amend FR-036 to "named a key of this brand". If unknown-key attempts must be visible, count them in a bounded metric rather than per-request events.

  Resolved: `AccessKey.admit` answers whether a key was ever created under the id, and a refusal is recorded only for such a key (wrong secret, expired, disabled, deleted); FR-036 amended by the person's decision (2026-11-14). AuditTrailSuite proves a made-up key id leaves no record, and fails with the old behaviour. 5663f7ea

## Should fix

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/Deadline.scala:24-40`. Issue (non-blocking): the deadline's pool is an unbounded `newCachedThreadPool`, and work that outlives the budget is never cancelled. Under a slow database, every request that times out leaves its thread running a view read, and new requests keep adding threads. The 503 protects the caller but not the service. Suggestion: use a bounded pool, with a queue limit, that answers `unavailable` when full. Also call `Future.cancel` (or interrupt) on timeout where the read can be interrupted.

  Resolved: each Deadline has 64 workers and 256 queued; a full pool is 503 at once, and work past its budget is cancelled with an interrupt; DeadlineSuite covers both. e75d3bab

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/Pipeline.scala:55`. Issue (non-blocking): on a timeout, `request.get.disclosed.toVector` reads a `mutable.ArrayBuffer` that the worker thread may still be appending to. There is no happens-before edge once `Await` has timed out.
  - The read can throw. It sits inside the `Try`, so the audit record is silently lost.
  - Or it can succeed, so a `503` answer is audited as having disclosed identifying fields it never sent. A subject access request would then report a disclosure that did not happen.
  - Suggestion: pass `Vector.empty` as the disclosed players when the answer is not 2xx, and make `disclosed` a value the handler returns rather than a mutable buffer on a case class.

  Resolved: only a 2xx answer, which the handler finished before `run` returned, reports disclosures (`Pipeline.disclosed`, DisclosedSuite). 5663f7ea

- `specs/016-public-api/spec.md` (SC-002) and `specs/016-public-api/tasks.md:474` (T093 `[X]`). Issue (non-blocking): research.md records that SC-002 is missed for full pages (2.66 s p95 against 1 s), because ankka 0.16 views take no index and plan R7's expression indexes were not built (R16.1). The person accepted that miss. However, the spec still states SC-002 as a success criterion, T093 is ticked, and commit `faea4109` says "every suite green" while `StatsPerfSuite` fails by design. Suggestion: mark SC-002 as deferred in the spec, with a pointer to the ankka finding (indexes declared on a view). Note the known failure beside T093 so the next reader does not take the tick at face value.

  Resolved: SC-002 marked deferred for full pages in the spec, beside T093, and in research.md's T096 note, which now says the perf suites were not in the green run. e674dc3c

- `services/public-api/src/test/scala/io/eitheror/casino/publicapi/it/QuotaSuite.scala:33`. Issue (non-blocking): the SC-006 latency comparison (p95 within 10% plus 2 ms) runs in CI's `publicapi.it.*` job on a shared runner. research.md already records it failing once under load from outside the service. A test that fails under contention from outside the service will turn CI red at random. Suggestion: move the latency half of this test to `perf` (run on purpose only), and keep the shared-count half in `it`.

  Resolved: the latency half is `perf.QuotaPerfSuite` (PUBLIC_API_PERF=1); the shared-count half stays in `it`. 52d3ebd4

- `services/public-api/src/test/scala/io/eitheror/casino/publicapi/it/RecordSuites.scala:73`, `:237`, `FieldsSuites.scala:76`, `AuditTrailSuite.scala:39`. Issue (non-blocking): each of these negative assertions ("not served", "404", "not recorded") comes after a `Thread.sleep(500–1000)`. If the consumer has not yet processed the fact, the assertion passes, so each test can pass while the behaviour it checks is broken. `CollectionsSuite` already shows the fix (around line 228): publish a sentinel fact on the same key after the one under test, wait for the sentinel to be served, then assert the absence.

  Resolved: each absence is asserted after a later fact on the same topic is served (RecordSuites 52d3ebd4, FieldsSuites 4331516d, AuditTrailSuite 5663f7ea).

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/audit/PublicApiAudit.scala:25`. Suggestion (if-minor): a key-change record's id is `access-key/<key>/<kind>/<epoch millis>`. Two changes of the same kind to one key in the same millisecond produce the same record id, and spec 018 stores a record id once, so the second change's audit record is dropped. FR-021 requires every change to be recorded. Derive the id from the event's sequence number or offset instead. That is just as deterministic under redelivery and cannot collide.

  Resolved: a key change's id is `access-key/<key>/<sequence number>`; KeyAuditSuite checks two changes in one millisecond are two records and a redelivery one. 41be8b43

## Nits

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/keys/AccessKeys.scala:42`. Nitpick (non-blocking): keys are ordered by `payload->>'createdAt'` as text. `Instant` is written with a varying number of fraction digits, so `…:00Z` sorts after `…:00.5Z`. Cast to `timestamptz` as the record views do. Related: `KeysEndpoint.scala:33` caps the list at 1000 rows with no sign that the list was cut.

  Resolved: ordered by `createdAt::timestamptz`, 1260bf44. Left: the 1000-row cap, nit; a brand with more than a thousand keys is not in sight, and a sign of the cut changes the operator routes' contract.

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/audit/PublicApiAudit.scala:46`. Nitpick (non-blocking): the request record lists every served player as `player:<id>`, including those served with no identifying field (`after = ""`). A no-fields key reading a page of 100 players therefore writes 100 empty changes. FR-036 asks only for players whose identifying fields were served. Filter on `fields.nonEmpty`, as the per-player records already do.

  Resolved: the request record names only players served an identifying field; AuditSuite checks it. 41be8b43

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/PublicApiService.scala:38-39`. Nitpick (non-blocking): `catalogueOf` defaults to `null` and is wrapped with `Option(...)`. Use `Option[...] = None` instead. Separately, the catalogue's blocking HTTP load runs on `ExecutionContext.global`. Give it the deadline's pool, or a named one.

  Resolved: `catalogueOf` is an `Option`, and the catalogue loads on two named daemon threads of its own. 1260bf44

## Questions

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/records/Views.scala:22`. Question: FR-014 says a record arriving mid-paging "is neither repeated nor skipped". Offset paging over `(time, id)` guarantees that only for records newer than the page being read. Two cases are not covered:
  - A late fact with an older time, for example a withdrawal completing on a separate topic, or a fact arriving after a consumer catches up.
  - A row that `deleteRow` removes because a later fact made it unservable.

  Either shifts every later offset by one. The feature scenario only publishes newer rounds. Does the spec mean "newer activity", or must late arrivals hold too? If the former, say so in FR-014. If the latter, offset paging cannot meet it.

  Answered: newer records only, the person's decision (2026-11-14); FR-014 now says so, and that a late record with an earlier time or one no longer served can shift a page, as the original's offset paging does. 5663f7ea

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/Erasure.scala:21`. Question: the ankka 042 handler always reports `ErasureReport(0)`, even when it erased a player record. Does the installation's erasure certificate read that count? If it does, report 1 when the record existed.

  Resolved: the handler reports 1 when the player's record held anything to erase, 0 on a rerun or another subject, per ankka's handler contract; ErasureSuite checks it. 4331516d

- Thought: a single admitted GET makes three writes: one to `KeyWindow`, one to the `KeyUse` view row (rewritten because `lastUsedAt` changes on every request) and one `KeyLog` event, which in turn produces at least one audit message. `durationMs` is measured before the `KeyLog` write, so the audit's latency figures do not include it. At a key's maximum rate of 6000 per minute that write load lands on one entity per key. This is worth measuring before a brand raises a key's rate.

  Answered: agreed; `durationMs` deliberately measures the answer the caller waited for, not the log write after it. The write load per key at 6000 a minute is for `perf` to measure before a brand raises a key's rate; no brand key is near it (the BI key is 600).

## Praise

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/keys/RawKeys.scala`. Praise: the key's design is sound. The key id sits inside the key, so admission is one consistent entity read rather than an eventually consistent digest index. The secret is 256 bits from `SecureRandom`. Only a SHA-256 digest is stored, it is compared with `MessageDigest.isEqual`, and the digest is dropped on delete.
- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/keys/AccessKey.scala` (`admission`). Praise: an unknown, deleted or wrong-secret key is `key_invalid` before expiry or disablement is checked. A guessed secret therefore never learns a key's state.
- `services/public-api/src/test/scala/io/eitheror/casino/publicapi/contract/ContractAmendmentSuite.scala`. Praise: the served contract is diffed against the verbatim original, with an explicit allowlist of the amended paths. A negative test proves that a change anywhere else fails and names its path. FR-002 and FR-005 are held mechanically.
- `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/Commands.scala` (`NotPayload`). Praise: the new labels are kept out of the idempotency digest, with the reason written down, so a retry from an older caller is still the same command. The new event fields are additive, with defaults, so the journal replays.

## Out of scope

- Note: `services/rewards`, `services/players` and `services/reporting` build without `-Werror`. Because of that, the non-exhaustive match warnings behind the first blocking finding were never fatal. It is worth an issue to make exhaustiveness warnings errors across the service builds.

  Left: out of scope, for an issue; this branch's consumers now match the rounds family exhaustively, so a new family warns.

## Checks applied

- **General checklist groups applied:** Correctness, Design and scope, Error handling, Concurrency and state, Security, Data and migrations (stored events and fact families), Performance, Tests, Observability, API and compatibility, Dependencies and build, Documentation, and Commit and PR hygiene. The commits follow the `type(016): …` convention with trailers.
- **Language skills:** `pr-review-scala` (bundled; the repository has none of its own), applied in this order: concurrency, serialization and wire evolution, types and totality, error handling, collections, tests, build. The Scala 3 idioms group was skimmed.
- **Go:** `pr-review-go` was not loaded. The Go driver was checked only for secrets and kubectl use.
- **Project rules read:** the root `CLAUDE.md` and the workspace `../CLAUDE.md`. There is no REVIEW.md and there are no `.claude/rules`.
- **Enforced by tooling and not reviewed:** `-Wunused:all` (unused code). No scalafmt configuration was found.
- **Tests:** not run. The service and producer suites need sbt with a published contract snapshot, and the integration suites need Docker. Each takes several minutes, beyond the one-minute budget for a run during review. The first blocking finding rests on reading the three consumers' matches against the new sealed family. A run of each consumer with a `RoundOpened` fact would confirm it.

## Second round

# Review: public-api (spec 016), the fixes, 19037014...HEAD

**Verdict:** Request changes
**What it does:** Applies the first round's findings: the three `casino.rounds` consumers pass over `round_opened`; a refusal is recorded only for a key that was ever created; disclosures are reported only for a `2xx`; the request budget runs on a bounded pool and interrupts late work; key-change audit ids come from the event's sequence number; the erasure handler reports a count; and several tests replace sleeps with later facts. It also records T095 and T096 as done and amends FR-014, FR-036 and SC-002.
**Should it exist:** Yes. Each commit answers a named finding, and the spec amendments record the person's decisions.
**Read:** In full: every hand-written hunk in the range (24 Scala files, about 340 lines), and the spec, tasks, research, quickstart, README and feature diffs. Outside the diff I opened `PublicApiService.endpoints` and `V1Endpoint` (how `Pipeline` is built), `Usage.scala`, `PlayerRecord.erase` and `PlayerState`, `AccessKey`'s `Admission` serializer, the it `Kit` (its broker is ankka's `InMemoryBroker`: one partition, in order), the contract's `AuditCompanion`, and in ankka 0.16: `ChangeContext.sequenceNumber` (per entity), `ProjectionRuntime` and `Consumer`/`View` `parallelism` (4 slice ranges by default), and `ErasureReport`. Not re-reviewed: the first round's files that no fix commit touched.
**Size:** 31 files, +415 −100, of which about 340 lines are Scala. The size is fine for a fix round.

### Resolutions of the first round

- **Blocking 1** (`round_opened` consumers), 72dbf02b: **holds.** All three matches are now exhaustive over `CasinoRoundsFact`. Each consumer has a test feeding it a `RoundOpened`: rewards through `round` and the consumer kit, players through `ActivityLines.round`, and reporting through the consumer kit with no calls recorded.
- **Blocking 2** (made-up key ids write), 5663f7ea: **holds for made-up ids.** `AccessKey.admission` answers `known = false` only when `!s.exists`, and `Pipeline` sets `admitted` only from `Rejected.key`. The `known = true` default is the safe direction for a reply from an older node during a rolling deploy. Two gaps remain: a refusal of a *known* key is still an unthrottled write (Should fix 1), and the integration test that proves the fix can pass while the fix is broken (Should fix 2).
- **Should fix 1** (bounded deadline pool), e75d3bab: **does not hold.** `Deadline` itself is right, and DeadlineSuite proves both properties on one instance. But `Pipeline` is now built once per request, so every request gets a pool of its own (Blocking 1). This leaves the service worse than before the fix.
- **Should fix 2** (disclosed buffer read after a timeout), 5663f7ea: **holds.** A `2xx` answer can only come from `task.get` returning, which makes the handler's appends visible to this thread. Every other answer reports nothing. DisclosedSuite covers both cases. The buffer is still a mutable field on `Served` rather than a returned value, but the race is gone.
- **Should fix 3** (SC-002 marked deferred), e674dc3c: **holds.** The spec, T093 and research.md's T096 note all say it.
- **Should fix 4** (SC-006 off shared runners), 52d3ebd4: **holds.** `perf.QuotaPerfSuite` is ignored unless `PUBLIC_API_PERF=1`, and QuotaSuite keeps the shared-count half. README and quickstart name the new suite.
- **Should fix 5** (absences after a sleep), 52d3ebd4, 4331516d, 5663f7ea: **holds in part.**
  - The later fact proves the topic intake read the earlier one. That holds because the kit's broker is one ordered partition.
  - It does not prove the `stateOf` view or the audit consumer processed it. Both are sliced by entity id, and the later fact is on another entity.
  - AuditTrailSuite is the one that matters (Should fix 2). RecordSuites and FieldsSuites are a nit (Nits 1).
- **Should fix 6** (key-change id collisions), 41be8b43: **holds.** `messageContext.sequenceNumber` is the source entity's own sequence number in ankka 0.16 (`ChangeContext`), so `access-key/<key>/<seq>` is unique per change and stable under redelivery. KeyAuditSuite checks both. Changing the id format is safe only because no key-change record has been published by a deployed brand. Please confirm that the dev-kind run in T095 does not matter here.
- **Nit 1** (`createdAt` sort), 1260bf44: **holds.** The query now casts to `timestamptz`. The 1000-row cap stays, with a reason given.
- **Nit 2** (empty `player:` changes), 41be8b43: **holds.** The request record filters on `fields.nonEmpty`, and AuditSuite asserts both directions.
- **Nit 3** (`null` default, global pool), 1260bf44: **holds.** The default is an `Option`, and the catalogue has a named pool of two daemon threads.
- **Question 1** (FR-014 and late records): **answered.** FR-014 now says what offset paging gives.
- **Question 2** (`ErasureReport(0)`), 4331516d: **answered, with an edge case.** A subject the service never held reports 1 (Should fix 3).
- **Thought** (write load per request): **answered.**
- **Out of scope** (`-Werror`): **left for an issue, as agreed.**

### Blocking

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/PublicApiService.scala:58`. Issue (blocking): `Pipeline(settings, contract, clients, now, handlers, usage.rate, usage.served(now))(_, _)` expands to `(op, call) => Pipeline(...)(op, call)`. The placeholder's scope is the whole expression, so the constructor runs on every request.
  - Since e75d3bab, `Deadline` owns its pool (`private val pool = ThreadPoolExecutor(...)` per instance, where it was `Deadline.pool` on the companion). So every request now builds a `ThreadPoolExecutor`, starts one thread in it, and leaves that thread idle for 60 s (`allowCoreThreadTimeOut`, keep-alive 60 s). The executor is never shut down.
  - The 64/256 bound therefore never applies. At 100 requests a second the service holds about 6,000 idle threads, where the old cached pool reused its threads. The finding the commit set out to fix is now worse, on the common path.
  - I confirmed the expansion with scalac 3.8.4: `E(P(1)(_, _))` called three times constructs `P` three times. DeadlineSuite cannot see this, because it builds one `Deadline` and calls it repeatedly.
  - The same expansion also rebuilds `Pipeline.builtIn(contract) ++ handlers` on every request. That was already the case before this range.
  - Suggestion:

    ```scala
    val pipeline = Pipeline(settings, contract, clients, now, handlers, usage.rate, usage.served(now))
    V1Endpoint(contract, pipeline.apply, ...)
    ```

    Add a test that holds the endpoint's pipeline fixed. For example, count `Deadline` or `Pipeline` constructions across two requests in PipelineSuite, or assert that `public-api-request` threads stay at or below `Deadline.Workers` after more requests than that.

  Resolved: the endpoint builds the pipeline once and passes `pipeline.apply`; the three suites that build their own do the same. RecordedSuite sends 100 requests and checks the budget's threads stop at its 64 workers; with the old construction it fails (163 threads after, from 63). 12e814b4

### Should fix

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/Pipeline.scala:46`. Issue (non-blocking): a refusal of a *known* key still writes a `KeyLog` event and a `platform.audit` record per request with no throttle, because `rate` runs only for an admitted key.
  - The key id travels inside the key. The likeliest holder of a real key id that is not admitted is whoever holds a leaked key the operator has just deleted or disabled. That holder can still make the brand write without bound, which is the amplification Blocking 2 closed for made-up ids.
  - FR-036, as amended, asks for every such refusal to be recorded, so this needs a decision as well as code.
  - Suggestion: take from the key's `KeyWindow` for refusals too, at the key's rate or a fixed refusal rate. Past it, answer as now but record nothing, or record one summary per window. Amend FR-036 to "recorded up to the key's rate".

  Resolved: the person chose (2026-11-14) a cap: each instance records at most 60 refusals of one key a minute, and names the count of the rest on the key's next recorded refusal (`refused_unrecorded`); FR-036 and contracts/facts.md say so. RefusalCapSuite and AuditSuite cover it. 01825e8f

- `services/public-api/src/test/scala/io/eitheror/casino/publicapi/it/AuditTrailSuite.scala:46`. Issue (non-blocking): this is the only test of the Pipeline-level half of Blocking 2, and it can pass while that half is broken.
  - `ReadAudit` is a consumer of `eventsOf(KeyLog)` with ankka's default `parallelism` of 4. Its slice ranges are split by entity id, so a record for `madeUp` and the second record for `d.id` are processed by independent projection instances.
  - Once `d.id`'s record is published, nothing guarantees that a `madeUp` record would already be published. The comment's reasoning ("written before it") holds for the journal, not for the topic.
  - AccessKeySuite proves `known = false`, but nothing proves `Pipeline` acts on it.
  - Suggestion: in the it `PipelineSuite`, build a `Pipeline` with a recording `served` and assert it is never invoked for a made-up key id. Then assert it is invoked once for a wrong secret on a real key. `served` runs inside `apply`, so the assertion is synchronous. Keep the AuditTrailSuite case as the end-to-end check.

  Resolved: RecordedSuite builds a pipeline with a recording `served` and reads what it is told directly: nothing for a made-up key id, and a known key's wrong secret up to the cap (60 of 62). 01825e8f

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/records/PlayerRecord.scala:98-100` (via `Erasure.erased`). Issue (non-blocking, if-minor): for a player the service never held, `currentState.copy(id = c.playerId).withErased` differs from the empty state, because `id` and `erased` change. So the first erasure replies `true`, and the installation's certificate is told 1 object was erased for a subject this service held nothing of.
  - Writing the erased marker is right: later facts must read as erased. Counting it as an erased object is not.
  - ErasureSuite's new test only erases a player it registered first.
  - Suggestion: reply with whether the record held anything before, for example `currentState.registeredAt.isDefined || currentState.email.isDefined || …`, or `currentState != emptyState && !currentState.erased`. Add the unknown-subject case to the test, expecting 0.

  Resolved: `erase` reports true only when the record held the player; the erased mark is still kept for one it never held. ErasureSuite checks a never-held player reports 0. c40ceb32

### Nits

- `services/public-api/src/test/scala/io/eitheror/casino/publicapi/it/RecordSuites.scala:73`, `:239`, `FieldsSuites.scala:76`. Nitpick (non-blocking): the later fact is on a different entity. It proves the topic intake read the earlier fact, but not that the `stateOf` view, which is sliced by entity id, wrote or deleted the earlier row. A broken build passes only within a slice range's lag, which is small. Where the subject allows it, put the later fact on the same entity:
  - in FieldsSuites, a status change for `pl`, then wait for that status to be served;
  - in RecordSuites, a later fact on the same round record.

  Left: nit. The topic's intake is checked to have read the earlier fact; the remaining window is one view slice's lag.

- `services/public-api/src/test/scala/io/eitheror/casino/publicapi/api/DeadlineSuite.scala:49`. Nitpick (non-blocking): `Thread.sleep(100)` waits for the second submission to be queued. On a slow runner the third call queues instead of being rejected, then fails the timing assertion after the 5 s budget. This is a flake that fails safe, not a false pass. A `Deadline` constructor parameter that exposes a queued count, or a latch counted in a wrapping task, removes the sleep.

  Left: nit. It fails safe (a flake, never a false pass), as the finding says.

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/Deadline.scala:51`. Nitpick (non-blocking): `public_api_busy` logs at warn once per rejected request. Under the overload it exists for, that is one line per request. Log the first in each interval, or count it as a metric.

  Left: nit. A brand at that overload is an incident whose logs say so; a metric waits on the service's metrics.

### Questions

- `specs/016-public-api/tasks.md` (T096 `[X]`), `research.md` (*The suites*). Question: the green run that T096 records was made before the fix commits (72dbf02b onward). None of their messages says `just public-api-it` or the rewards, players and reporting suites were run again. Were they? If they were, say so in the T096 note. If not, a run is what would settle it, and it would also be the first run of the new `DeadlineSuite`, `DisclosedSuite` and consumer tests.

  Answered: yes. After the first round's fixes: rewards' RoundsConsumerSuite, players' ViewsSuite and reporting's RoundCheckSuite, then reporting's whole unit suite (176) and public-api's units (108) and `just public-api-it` (169). After the second round's: public-api's units (111) and `just public-api-it` (171), all green; research.md's T096 note says so. On Should fix 6's note: no deployed brand published a key-change record; the dev-kind records of T095 are a test installation's.

### Praise

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/Pipeline.scala` (`Pipeline.disclosed`). Praise: the disclosure rule is a pure function, with the happens-before reasoning written beside it, and it has a test of its own. It is the right size of fix for a data race.
- `services/public-api/src/test/scala/io/eitheror/casino/publicapi/audit/KeyAuditSuite.scala` (new last test). Praise: it states both halves of the id's contract, distinct per change and equal under redelivery, in two lines.
- `services/rewards/.../consumers/RoundsConsumerSuite.scala`. Praise: the opened-round case names free rounds (`Some("fr-1")`), so it checks that an opened round is skipped for its kind, not merely for lacking a grant.

### Checks applied

- **General checklist groups applied:** Correctness, Design and scope, Error handling, Concurrency and state, Security, Data and migrations (audit record ids, the `Admission` reply's new field), Tests, Observability, API and compatibility, Documentation, and Commit and PR hygiene. The commits follow `type(016): …`, each names the findings it answers, and each has the trailer.
- **Skipped:** Dependencies and build (no build change) and Performance, apart from the blocking finding.
- **Language skills:** `pr-review-scala` (bundled) groups were applied from the first round's reading: concurrency (executors, interruption, visibility), types and totality (exhaustive matches), tests and fixtures. The repository has no `pr-review-scala` of its own. The summary listed `specs/016-public-api/REVIEW.md` as a review checklist. That is this file, `review.md`, on a case-insensitive filesystem, and it is not a checklist.
- **Project rules read:** `CLAUDE.md` and `../CLAUDE.md`. There are no `.claude/rules`.
- **Tests:** not run. The suites need sbt with a published contract snapshot, and the integration suites need Docker, beyond the review's budget. The one claim that rested on language semantics, the placeholder expansion behind Blocking 1, was checked by compiling and running a five-line reproduction with scalac 3.8.4. That gave three constructions for three calls.

## Third round

# Review: public-api (spec 016), the second round's fixes, 2ae8ff48...HEAD

**Verdict:** Approve with comments. The one should-fix below is expected before merge, as a code change or as a sentence in FR-036. The nits are optional.
**What it does:** Builds the `/v1` pipeline once per endpoint, so its request budget's pool is shared. Caps the recorded refusals of a key of the brand at 60 a minute per instance, and names the refusals left unrecorded on the key's next recorded refusal (`refused_unrecorded`). Makes an erasure of a player the service never held report nothing erased. Adds RecordedSuite, RefusalCapSuite and the AuditSuite and ErasureSuite cases, and amends FR-036, its clarification and contracts/facts.md.
**Should it exist:** Yes. Each commit answers a named second-round finding, and the cap is the person's recorded decision.
**Read:** In full: every hunk in the range (14 code and test files, about 150 hand-written lines), plus the spec, facts.md and research.md diffs and the second-round report. Outside the diff I opened all of `Pipeline.scala`, `Admission.scala` (`Rejected.key` is `Some` only for a known key), `KeyWindow` (a request over the rate writes no state), `KeyLog.outcome` (any `4xx` is `refused`), `PublicApiCodecs.stored` (jsoniter with `withTransientDefault`, unknown fields skipped), `PlayerRecord`'s fold (every fact sets `id`), the it `Kit` (a `MovableClock`, so the 62 requests cannot straddle a minute), `Deadline`'s pool and thread name, the build's `Test / fork` (one forked JVM, suites in sequence), and `features/public-api/audit.feature`. I also grepped for every `Pipeline(` construction.
**Size:** 16 files, +262 −15, of which about 150 lines are Scala. That is small, so the light procedure applied.

### Resolutions of the second round

- **Blocking 1** (pipeline built per request), 12e814b4: **holds.**
  - `PublicApiService.endpoints` binds `val pipeline` inside the endpoint factory, which ankka's `HttpServer` calls once at start. The `Deadline` pool, the `RefusalCap` counts and `builtIn ++ handlers` are therefore built once per service.
  - A grep finds no other `Pipeline(…)(_, _)`. The three suites that build their own pipeline pass `.apply` on one instance.
  - RecordedSuite's thread test can fail. A pool per request adds one thread per request (100 here), and those threads outlive the test by their 60 s keep-alive, well past the 64 the assertion allows. The implementer's run with the old construction (63 to 163) confirms it. Suites run in sequence in the one forked JVM, so no other suite's pools inflate the count.
- **Should fix 1** (refusals of a known key unthrottled), 01825e8f: **holds for refusals at admission, not for every refusal FR-036 now caps.**
  - `RefusalCap.refused` is atomic per key: `ConcurrentHashMap.compute` runs its function once, on the caller's thread, so the captured `var`s are safe.
  - The carried count resets once it is named. The warn line comes once per key per minute per instance. The map holds only known key ids.
  - `refused.set(true)` comes before `admitted.set`, so a reader that sees the key also sees that it was refused.
  - A refusal after admission is not capped: the quota's `429`, or a `400` path id. See Should fix 1.
- **Should fix 2** (AuditTrailSuite could pass while broken), 01825e8f: **holds.** RecordedSuite reads `served` where `Pipeline.apply` calls it, synchronously before the answer returns. It asserts that a made-up key id is told nothing, and that a known key's wrong secret is told exactly 60 times out of 62, each `(d.id, 401, 0)`. Both assertions fail if `Rejected.key` or the cap is broken. The clock is the kit's `MovableClock`, so the cap's minute cannot roll during the test.
- **Should fix 3** (`ErasureReport(1)` for a subject never held), c40ceb32: **holds.** Every fact's fold sets `id`, so `currentState.id.nonEmpty` is exactly "a fact has named this player". A never-held subject still gets the erased mark written, but its reply is `false`, and ErasureSuite asserts 0 for a fresh id. A rerun after a late fact still reports 0, because `withErased` on an erased record changes nothing.
- **Nits 1 to 3:** left, as recorded. That is acceptable.
- **Question** (suites run after the fixes): **answered.** The research.md T096 note says so.

`KeyLog.Served` gains `unrecorded: Int = 0`, a stored event type. That change is compatible in both directions:
- an old event decodes to 0;
- a new event with 0 omits the field (`withTransientDefault`);
- an older node reading a new event with a count skips the unknown field.

### Should fix

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/Pipeline.scala:64`. Issue (non-blocking): the cap applies only to refusals at admission (`refused` is set only in the `Left(rejected)` branch). A refusal of an admitted key is still recorded once per request: the quota's `429` at `:55`, or a `notAnIdentifier` `400`.
  - FR-036 as amended says "each instance records at most 60 refusals of one key a minute", and `KeyLog.outcome` calls a `429` `refused`, so the code does not do what the spec says.
  - It is also the same amplification the cap closes, in its other shape. A leaked key the operator has not yet disabled, or a misbehaving client, retried past its rate, costs the brand a `KeyLog` event and a `platform.audit` record per request. `KeyWindow.take` itself writes nothing once over the rate.
  - Suggestion: cap on the outcome, not the branch. In the `admitted.get.foreach` block, use `val unrecorded = if answer.status / 100 == 4 then cap.refused(k.keyId) else Some(0)` and drop the `refused` flag. Add a case to RecordedSuite: a key with `requestsPerMinute = 1` sends 62 requests, and `served` is told 61 times (1 done and 60 refused).
  - If the person wants every `429` recorded, amend FR-036 to "refusals at admission" instead.

  Resolved: any 4xx answer of a key goes through the cap, not only refusals at admission; RecordedSuite checks a one-a-minute key's 62 `429`s are recorded 60 times; FR-036 names the 4xx. 324fbb49

### Nits

- `features/public-api/audit.feature:2`. Nitpick (non-blocking): the feature still says "Every request that presents an access key of the brand leaves one audit record". That is no longer true past the cap. The constitution keeps acceptance in the living features, and FR-036's new sentence has no scenario. Amend the prose. If wanted, add a scenario such as "a key refused more than 60 times in a minute has the rest counted on its next record" (`/speckit-bdd-features`).

  Resolved: the feature's prose says a key's refusals past 60 a minute are counted on its next recorded refusal. Left: a scenario of its own, nit; RecordedSuite and RefusalCapSuite hold it. 324fbb49

- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/RefusalCap.scala:9`. Nitpick (non-blocking): "the audit still says how often it was tried" holds only if the key is refused again later. The count is in memory: a burst that stops after the cap, or an instance that stops, never writes the count anywhere, and the `public_api_refusals_capped` warn line carries no count.
  - Either say so in the comment, or log the count when a capped window rolls over.
  - In the same file's suite, the third test's name ("add up … in several minutes") describes a case that cannot happen, because any minute's first refusal is recorded. Its assertions are right.

  Resolved: the comment says the count is in memory and lost when the instance stops or the key is not refused again, and that the warning carries no count; the misnamed test is renamed. 324fbb49

### Praise

- `services/public-api/src/test/scala/io/eitheror/casino/publicapi/it/RecordedSuite.scala`. Praise: it moves the proof from the sliced audit consumer to the point where the pipeline decides. Both halves of FR-036's rule, and the thread bound, are asserted synchronously, and each assertion fails with the defect it guards against.
- `services/public-api/src/main/scala/io/eitheror/casino/publicapi/api/RefusalCap.scala`. Praise: a per-instance, in-memory cap that costs no write of its own. The bound is stated honestly as `perMinute` times the instances, and the warn is limited to one per key per minute.

### Checks applied

- **General checklist groups applied:** Correctness, Design and scope, Error handling, Concurrency and state (the `compute` closure, the order of the atomics, one pool per service), Security (the amplification path), Data and migrations (the `KeyLog.Served` field), Tests, Observability (the capped warn line), API and compatibility, Documentation, and Commit and PR hygiene. The commits are `type(016): …`, each names the finding it answers, and each has the trailer.
- **Skipped:** Dependencies and build (no build change) and Performance, apart from the pool construction.
- **Language skills:** `pr-review-scala` (bundled; the repository has none of its own), applied from its summary to concurrency, types and totality, codecs on stored types, and munit fixtures, as the light procedure allows.
- **Project rules read:** `CLAUDE.md`, `../CLAUDE.md` and the constitution's features rule. There are no `.claude/rules`.
- **Tests:** not run by the reviewer. The integration suites need Docker. I relied on the implementer's runs: 111/111 unit tests and 171/171 in `just public-api-it`. The RecordedSuite thread test failed with the old construction and passes with the fix.
