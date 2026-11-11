# Review: 017 support-agent — Motoko on ankka's agent model, with the neighbours' amendments

**Verdict:** Request changes. There are four blocking findings:
- the output and input `other-player` guardrails refuse the player's own game session, transaction and reward ids;
- a withdrawal always confirms a bonus forfeit, including one the player was never shown;
- a real-money brand can turn the agent on with no safeguarding guardrail running;
- the stream's own threads block on entity calls and a sleep loop.

The should-fix items are expected before merge, or an answer with a reason.

**What it does:** Adds the `support-agent` brand service: Motoko as one ankka agent sharded by session. It has:
- the original's two SSE routes (chat, and resume carrying the action's result);
- six read tools and five approval-gated write tools that call the owning services as `support-agent`, keyed by the approval id;
- guardrails, a per-player turn rate and a per-brand daily token budget;
- a versioned, audited per-brand configuration, a forget timer, an operator view, and audit consumers onto `platform.audit`.

It also amends three neighbours so the tools can reach them by identity:
- payouts A1: a withdrawal request with a terminal chosen for the player;
- responsible-gambling R1–R4: limits, a keyed limit, cooling-off and exclusion through `RgKeys`;
- players P1–P3: a keyed session end, the currency on the status, and `agent-profile`.

**Should it exist:** yes. Constitution I keeps the original's agent. The spec, plan and tasks call for exactly this service, and ankka 029/042/064 supply the approvals, the subject-sealed memory and the compaction that the original lacked.

**Read:**
- Read in full:
  - everything under `services/support-agent/src/main` (api, agent, domain, conversation, config, limits, clients, timers, reads, facts, Settings, SupportRuntime, SupportService, SupportCodecs);
  - the players, payouts and responsible-gambling diffs;
  - `modules/support/module.json`, the `Values.scala` default, `ci.yml`, `Justfile` and `contract/keys.md`;
  - spec.md, plan.md, tasks.md, `contracts/routes.md`, and research.md's R8, R10 and *Found during implementation*.
- Read in part: `ResumeSuite`, `TelemetrySuite` and `OtherPlayerSuite` in full; the other suites for what they assert.
- Opened outside the diff:
  - ankka's `HttpEndpoint.scala` (`sseEventsBody`) and `docs/build/http-endpoints.md` (which thread a stream's elements run on);
  - wallet's `ServiceEndpoint.scala` (the routes the tools call, and the hold preview's amount);
  - payouts' `Gates.scala` (`confirmForfeit`) and `PlayerEndpoint.scala`;
  - responsible-gambling's `ProfileRoutes.setBreak`;
  - players' `GameSessionRoutes.launch`, where game session ids are `UUID.randomUUID()`;
  - one recording, `tests/port/support-agent/a write tool waits … [close_active_session, approve].json`.
- Skimmed: `tests/original/support-agent-recorder.py` (the recorder, not shipped), the recordings (data), data-model.md, tools.md and neighbours.md.

**Size:** 156 files, +11,195 −75.
- About 3.0k lines are main Scala and about 3.6k test Scala.
- The rest is recordings (about 2.6k), specs and docs (about 1.9k), and config.
- Main Scala alone is far past the size where a single review finds defects reliably. For the next service of this size, we would accept this split:
  1. the neighbours' amendments;
  2. the service with its pure packages and kit;
  3. the recordings and parity.

## Blocking

- `services/support-agent/src/main/scala/io/eitheror/casino/support/domain/OtherPlayer.scala:20-31` (used by `agent/Guardrails.scala:33-38`) — issue (blocking): any UUID other than the player's own id counts as "another player's id", on input and on output. The ids the player's own tools return are UUIDs too:
  - game session ids (`GameSessionRoutes.scala:54`, `UUID.randomUUID()`);
  - transaction ids (`transaction/<uuid>`);
  - reward ids.

  **Consequences:**
  - A reply that lists the player's sessions or transactions with their ids is refused, retried twice, and ends in `error … refused by the other-player check`.
  - After an approved `close_active_session`, the tool's own detail ("Session <uuid> closed") is what the model naturally repeats. On the resume path a refused reply is not retried. The whole frame batch fails, so the player sees `error` for a session that was closed (see the should-fix on `action_result` below).
  - A player who pastes their own session id into the chat is refused before the model is called.
  - Each case is audited as a `security` guardrail rejection, which misleads the audit.

  The suites never see this: the kit's player ids are `p-1`, and no scripted reply names an id the tools returned.

  **Suggestion:**
  - Build the guardrail per turn with an allow-set: the player's id, plus every id the turn's tools returned. `Tools` fills the set as it maps answers (`Mapping.sessions` / `transactions` / `activeBonuses`). Output guardrails run after the tools, so the set is complete by then.
  - On input, allow the ids of the player's own sessions, read once when a UUID is present.
  - Add an `it` case: a reply quoting a `get_session_history` session id passes, and a foreign UUID is still refused.

- `services/support-agent/src/main/scala/io/eitheror/casino/support/clients/Clients.scala:68` with `agent/Actions.scala:57` — issue (blocking): `confirmForfeit` is always `true`, but the summary names a forfeit only when the hold preview answered. `Actions.summary` drops the forfeit whenever the preview call times out, is unavailable or answers non-2xx (`.filter(_.ok)`). The player then approves "Withdraw 50.00 GBP from your account". The request still carries `confirmForfeit: true`, so payouts' gate (`Gates.scala:105`) passes, and the hold forfeits bonus funds the player was never shown. The preview is also read up to `approvalMinutes` before the request runs, so the forfeit can grow in between.

  This breaks `contracts/neighbours.md:34`, which justifies `true` with "since the player confirmed a summary naming it", and FR-013. It is the player's money.

  **Suggestion:**
  - Record the forfeit the summary named on `ApprovalRequested`/`Requested`, or carry it in the conversation's approved action.
  - When the write runs, send `confirmForfeit = namedForfeit.isDefined`. With `false`, payouts answers its confirm-forfeit refusal if one is now required, and `action_result` explains it.
  - Alternatively, refuse to put the withdrawal to the player when the preview cannot be read ("the withdrawal cannot be prepared right now").
  - Add a `ResumeSuite` case: the preview is unavailable, the player approves, and the request carries `confirmForfeit: false`.

- `services/support-agent/src/main/scala/io/eitheror/casino/support/domain/ConfigRules.scala:89-93`, `agent/Guardrails.scala:53-56`, `tools/modules/src/main/scala/io/eitheror/casino/modules/Values.scala:95` — issue (blocking): FR-023 says a brand that takes real money MUST run the judged safeguarding guardrail. The code runs it only when `AGENT_JUDGE` is set, and the module's default for `judgment-provider` is `none`. `safeguarded` checks only the message and the helpline. So a real-money brand rendered with the defaults can set `enabled: true` and serve players, and a self-harm message goes straight to the model. The status then shows `safeguarding: off`, and only someone reading it would notice. The spec's Assumption ("a brand without one gets the pattern guardrails only") contradicts FR-023 and the 2026-11-09 clarification. The code took the less safe reading for the case that matters, a regulated real-money brand.

  **Suggestion:** make `ConfigRules.safeguarded` take the settings, and refuse `enabled` for a real-money brand with no judge:
  ```scala
  if b.enabled && realMoney && (b.safeguardingMessage.isEmpty || b.helpline.isEmpty || !judged) then Left(…"safeguarding_not_configured"…)
  ```
  Use the same 422 code or a sibling `safeguarding_unavailable`. Add the case to `operators.feature`/`ConfigSuite`. If we decide the agent may run on a real-money brand without a judge, amend FR-023 to say so explicitly, so the decision is recorded and not inferred from an Assumption.

- `services/support-agent/src/main/scala/io/eitheror/casino/support/api/ChatEndpoint.scala:202-231` (also `:166-172`, `:178-195`) — issue (blocking): the stream's own threads block, which this file says must not happen (line 50: "they run here, never on the stream's own threads").
  - `ending` is a `Source.lazySingle`, so its body runs on the materializer's thread when the stream pulls it. The body makes four or five blocking `invoke()` calls (`handedOff`, `memory` in a loop, `get`, `turned`, `Budget.add`) plus `Forget.schedule`. It also runs `written`'s `Thread.sleep(20)` poll for up to 3 s.
  - `rejected` and `failed` inside `recoverWithRetries` likewise persist on the stream's thread.
  - ankka's docs (`build/http-endpoints.md:852-855`) say only the handler runs on a virtual thread, and a streaming route's elements "are pulled later, on another thread".
  - With tens of concurrent conversations, each turn's end can hold a Pekko dispatcher thread for up to 3 s. The HTTP server and everything else on that dispatcher share those threads.

  **Suggestion:** build each of these on the virtual-thread `ExecutionContext` that `turn` and `resume` already use:
  ```scala
  Source.lazyFuture(() => Future { …ending's body… })
  ```
  Do the same for `rejected`/`failed` inside the recover. Better, replace the sleep poll with an ankka hook when one exists; research's *For ankka* already notes the gap. Confirming the starvation needs a load run. The pattern itself is confirmed by reading.

## Should fix

- `services/support-agent/src/main/scala/io/eitheror/casino/support/api/ChatEndpoint.scala:117-128` — issue (non-blocking): the decision is recorded on `Conversation` before ankka is told, and nothing reconciles the two if the second step does not happen. That happens on a restart between the two calls, or when `decide` fails (for example, the approval expired between `settled` and `decide`). The conversation then says `Open`, with `lastDecided = id`, while ankka's session is still suspended. After that:
  - every `resume` answers 409 `already_decided`;
  - every chat turn reaches a suspended session, and ankka refuses it, so the player sees `error`;
  - this lasts until ankka expires the approval (default 15 minutes).

  The audit says the player approved a tool that never ran. FR-015's restart scenario covers a restart after `decide`, not between the two calls.

  **Suggestion:**
  - In `resume`, when `Decide` answers `already_decided` but `agent.approvals()` still holds that id, send ankka the decision again instead of 409. In `settled`, treat "conversation Open, ankka pending on `lastDecided`" the same way.
  - Add a kit case that records `Decided` and restarts before `decide`.

- `services/support-agent/src/main/scala/io/eitheror/casino/support/api/ChatEndpoint.scala:126-138` — issue (non-blocking): on resume, `action_result` is built inside the same `Future` as `decide`, after it. When the model's reply after an applied tool fails (an output guardrail, the model unavailable), the stream becomes `error` alone, and the player is never told that the withdrawal was requested or the break applied. The trail already holds the result.

  **Suggestion:** in the `recoverWithRetries`, prepend `lastAction.filter(_.approvalId == id)` as `action_result` before the failure frames, so an applied action is always reported (FR-003).

- `services/support-agent/src/main/scala/io/eitheror/casino/support/config/AgentConfiguration.scala:44-58` — issue (non-blocking): a `put` that wrote `ConfigVersion n+1` and then failed to `move` leaves an orphan body in that slot. Failures include a crash, or an ask timeout on `move`. Every later `put` with a different body computes `n+1` again, gets the orphan back from `write`, goes stale, retries, goes stale again, and throws. Configuration changes are then refused (500) until someone happens to resend the orphaned body.

  **Suggestion:** when `write` answers a body other than ours and `now.version` has not moved, the slot is an abandoned put. Move to it first, with a note marking it recovered, then retry at `n+2`. Alternatively, key the slot by a put id and let `move` name it.

- `services/support-agent/src/main/scala/io/eitheror/casino/support/api/ConfigEndpoint.scala:59-70` with `AgentConfiguration.scala:46-57` — issue (non-blocking) (if-minor): a lost update. `ConfigRules.read` merges the sent fields over `now.current` once. On a stale retry, `put` writes that same merged body over the newer version. So two operators who each change one field at the same time lose one change, and the brand-admin check ran against the old base.

  **Suggestion:** pass the sent JSON (or the sent field set) into `put`, and re-run `ConfigRules.read` and the admin-field check over the fresh `current` on each attempt.

- `services/players/src/main/scala/io/eitheror/casino/players/api/GameSessionRoutes.scala:107` with `services/support-agent/src/main/scala/io/eitheror/casino/support/agent/Tools.scala:138` — issue (non-blocking): `close_active_session` passes a game session id the model produced, and `endByAgent` ends any session in the brand, whoever's it is. That breaks the spec's boundary: "a tool that needs an identifier to act uses the player id from the token, never a value the model produced" (Context, FR-011). Today the input guardrail and the player's confirmation are the only things in the way.

  **Suggestion:**
  - Scope the route to the player: `POST /{playerId}/game-sessions/{sessionId}/end`, which is within the two-parameter limit. Refuse 404 `session_not_found` when the session is not the player's.
  - Alternatively, have the tool check the id against `n.players.sessions(playerId)` before the approval is put.

- `services/support-agent/src/main/scala/io/eitheror/casino/support/api/ChatEndpoint.scala:107` — issue (non-blocking) (if-minor): `resume` does not check `enabled`, so a brand that turned its agent off still runs approved tools and model turns through `/resume`. FR-001 says that while the agent is off, "its routes MUST answer that the agent is not available". If letting a pending decision finish is intended, say so in `contracts/routes.md`. Otherwise add the same 503 check that `run` makes.

- `specs/017-support-agent/spec.md:333` and `specs/017-support-agent/plan.md:214` — todo: FR-003 still says "`delta` with text as it is produced", and the plan's Divergences row says "`delta` streams text as produced … demonstrated by `ChatSuite`". The code holds the whole turn until the output guardrails pass (research, 2026-11-11, settled with the user), and `contracts/routes.md` says so. Amend FR-003 and the Divergences row to match. The plan's *Project Structure* also names files that do not exist (`domain/Rate`, `Budgeting`, `Handoff`, `config/ConfigVersion.scala`, per-neighbour client files, `api/Frames`); point it at where they live.

## Nits

- `services/support-agent/src/main/scala/io/eitheror/casino/support/SupportCodecs.scala:57-78` — nitpick (non-blocking): the events' case names are mapped to wire names, but the enums nested in stored state and events (`Outcome`, `Side`, `Status`, `EndReason`) are encoded by their Scala case names. The same file's `wire` helper exists for exactly that. Renaming `TimedOut` would break the journal. Give them wire names now, while the journal is empty.
- `services/support-agent/src/main/scala/io/eitheror/casino/support/agent/Tools.scala:56` — nitpick (non-blocking): `get_player_state` is described as returning "KYC, email, and profile information", which by default it does not (FR-018). If the parity description is to stay byte for byte, that is a reason to keep it, but the model will then claim to see an email it was not given. Consider the original's text plus "only the fields the brand allows".
- `.github/workflows/ci.yml:519` — nitpick (non-blocking): the comment says "on ankka 0.16.0", and the build is 0.16.2. T002 and T003 in tasks.md say the same.
- `services/support-agent/src/test/scala/io/eitheror/casino/support/it/ResumeSuite.scala:118` (also `features/ActionsFeatures.scala:133`, `it/ForgetSuite.scala:33`, `it/DurabilitySuite.scala:86`) — nitpick (non-blocking): wall-clock `Thread.sleep` waits to show that something did not happen. They are slow and can pass early on a slow runner. Where a positive signal exists (the turn ended, the timer fired), wait on it with `kit.eventually`.
- `services/support-agent/src/main/scala/io/eitheror/casino/support/reads/ConversationsByPlayer.scala:79` — nitpick (non-blocking): a page reads `offset + 50` rows and drops `offset`. Use the view's offset if `ViewQueries` has one.
- `.specify/feature.json` — nitpick (non-blocking): the active-feature pointer moves from 018 to 017. It will flip whichever branch merges last; consider leaving it out of feature branches.

## Questions

- `services/support-agent/src/main/scala/io/eitheror/casino/support/api/ChatEndpoint.scala:254-255` — question: `requests.head` assumes ankka never answers `AwaitingApproval(Vector.empty)`. Is that guaranteed by its type or its docs? Then, deciding `requests.tail` as refused before the head is put to the player: does ankka resume the turn (call the model) as soon as some of a response's approvals are decided, or only when all are? If the former, the head's request may be overtaken. A doc reference, or a kit case with two writes in one response, would settle it.
- `services/support-agent/src/main/scala/io/eitheror/casino/support/api/ChatEndpoint.scala:80` — thought: the budget is checked before the turn and added after it, so concurrent turns can overshoot `dailyTokens` by up to one turn each. That seems acceptable for a soft budget. `resume` neither checks nor bypasses it, and its tokens are counted. Say so in R10 if intended.

## Praise

- `services/support-agent/src/main/scala/io/eitheror/casino/support/agent/Tools.scala:23-34` — praise: the tools are built per turn over the bound player and expose no player, brand or currency parameter. Every call is recorded with `Redaction`'s arguments. FR-011's boundary holds by construction rather than by the prompt.
- `services/support-agent/src/test/scala/io/eitheror/casino/support/it/TelemetrySuite.scala:50` — praise: the suite asserts that something was captured before asserting that the markers are absent, so it cannot pass vacuously. That is the usual failure of "no log carries X" tests.
- `services/responsible-gambling/src/main/scala/io/eitheror/casino/rg/profile/RgKeys.scala:33-52` — praise: keeping evicted keys as 64-bit digests turns a stale retry into `key_expired` instead of a second change. The trade-off is written down in research.
- `specs/017-support-agent/research.md` *Found during implementation* — praise: each surprise (concat pulling early, memory written after the stream ends, compaction ignoring sealed events) is recorded with what was rejected and why. That makes the code's odder shapes reviewable.

## Out of scope

- note: research's *For ankka* list should gain one more item: a `FunctionTool` handler is not given the approval id it runs under. The pre-recorded `Decided` that the should-fix above works around exists only because of that. File it beside "a streamed turn's source completes before session memory holds the turn".

## Checks applied

- **General checklist groups applied:** Correctness; Design and scope; Error handling; Concurrency and state; Security (entry points, ACLs, personal data to the model, the CI job); Data and migrations (stored formats only, no SQL schema); Performance; Tests; Observability; API and compatibility (neighbours' new routes, players' `approvalId` now required on the agent's end route); Dependencies and build (ankka 0.16.2, `VersionScheme.Always` scoped to ankka); Documentation; Commit and PR hygiene (Conventional Commits, `type(017): …`, all parse).
- **General checklist groups skipped:** none entirely.
- **Language skill:** `pr-review-scala`. Concurrency and actors, serialization, types and totality, error handling, collections, tests and build were applied. The idioms group was skimmed: `-Wunused:all` is on, and there is no scalafmt check in this build. Python (`tests/original/support-agent-recorder.py`) was skimmed only: it is a local recorder, not shipped, and `pr-review-python` was not loaded.
- **Project rules read:** `CLAUDE.md` (repository and workspace), `.specify/memory/constitution.md` as referenced by the plan's gate, and `specs/007-payouts/REVIEW.md` (it applies to `specs/007-payouts/`, which the diff touches only in its spec and contract rows). There are no `.claude/rules`.
- **Tests:** not run. The integration, parity and feature suites need Docker, and the instructions are not to run them. The unit suites need the contract published under a unique `CASINO_VERSION` and several minutes of sbt. Every finding above rests on reading the code and the ankka source and docs it calls. The dispatcher finding needs a load run to show its effect, not its presence.
