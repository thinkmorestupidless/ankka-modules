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
