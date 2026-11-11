# Tasks: support-agent

**Input**: [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md). The work covers the spec's six stories,
the six files of `features/support-agent/`, and the neighbours' amendments in
[contracts/neighbours.md](contracts/neighbours.md).

**Tests**: required. The constitution makes the recordings the acceptance test (I), the success
criteria name suites, and the plan names them.

- Pure suites and feature bindings are written before the components they test, and must fail
  first.
- The original's agent tests are ported case by case (research R1, R13).

**Paths**:

- `S` = `services/support-agent/src/main/scala/io/eitheror/casino/support/`
- `T` = `services/support-agent/src/test/scala/io/eitheror/casino/support/`
- `O` = `~/Documents/projects/eitheror/eitheror-platform/starter-packs/casino/agent/` (the original)

**Rules for this build**:

- Build with `CASINO_VERSION=0.2.0-017-SNAPSHOT` (contract snapshots collide across worktrees).
- kubectl only with `--context kind-…`.
- Do not touch the other worktrees (`013-…`, `015-…`, `026-…`, `agent-*`).
- No test needs a model key: every agent suite runs on `TestModelProvider`, which fails when its
  script runs out (FR-008).

## Phase 1: Setup

- [X] T001 Publish the contract unchanged: `CASINO_VERSION=0.2.0-017-SNAPSHOT sbt -batch
      contract/publishLocal`; `just test` green.
- [X] T002 Run `ankka init` for `services/support-agent/`, shaped like `services/engagement/`.
  - `build.sbt`:
    - ankka 0.16.0 with `ankka-agent` added and `VersionScheme.Always` for ankka's artifacts;
    - Scala 3.9.0; munit, munit-scalacheck, postgresql (test);
    - `needsDocker` for `.it.`, `.port.` and `.features.`;
    - `Test / envVars CASINO_BRAND=goldvibe`; the `schema` task.
  - `service.json` (`support-agent`, runtime 0.16.0).
  - `docker-compose.yml` (Postgres on 5444).
  - `src/main/resources/application.conf` (`ankka.service.name = support-agent`,
    `ankka.service-client.timeout = 5s`).
  - `README.md` (components, routes, env, run, suites).
- [X] T003 Update `modules/support/module.json`:
  - runtime `0.16.0`;
  - env `AGENT_COMPACTION_BYTES` and `AGENT_COMPACTION_RECENT` (values 100000 and 10), and
    `AGENT_JUDGE` (key `judgment-provider`, optional);
  - the configuration keys to match.

  Then `ModuleManifestSuite`'s built set gains `support-agent`, and the `support-agent` row leaves
  `contract/keys.md`'s "Not yet built" table, in the same commit as `build.sbt`. `just test` green;
  `just modules render goldvibe --modules core,support` shows the service.
- [X] T004 [P] `Justfile`: add `support-agent-test`, `support-agent-it`, and
      `support-agent-run brand="goldvibe"` (HTTP 9020, Postgres 5444, both issuers,
      `-Dankka.local-services.{wallet,payouts,players,rewards,responsible-gambling}=…` at their
      `*-run` ports), in the shape of `engagement-run`. The port comment at `Justfile:262` gains
      `support-agent 9020`.
- [X] T005 [P] `.github/workflows/ci.yml`: add a `support-agent` paths filter (`*build`,
      `services/support-agent/**`, `features/support-agent/**`, `tests/port/support-agent/**`) and a
      job like engagement's, with a `-Dit=true` step.
- [X] T006 [P] `S/Settings.scala`: read every setting from env and fail at start when one is
      missing.
  - `CASINO_BRAND`, `CASINO_PROJECT`, `CASINO_REAL_MONEY` (as `players/Settings.scala:44`'s flag);
  - the auth issuers;
  - `AGENT_COMPACTION_BYTES`, `AGENT_COMPACTION_RECENT`;
  - `AGENT_MODEL_PROVIDER` (`anthropic` | `scripted:<file>`), `MODEL_PROVIDER_KEY` from the secret
    store;
  - `AGENT_JUDGE` (optional; absent means the judged guardrails are off, and status says so).

  Test it in `T/SettingsSuite.scala`.

## Phase 2: Foundational (blocks every story)

### The neighbours' amendments (contracts/neighbours.md)

- [X] T007 Amendment P2 (players): `GET /{playerId}/status` answers `currency`.
  - Code: `services/players/src/main/scala/io/eitheror/casino/players/api/GameSessionRoutes.scala`.
  - Test: the players suite asserting the field.
  - Docs: the row in `specs/008-players/contracts/routes.md`.
- [X] T008 Amendment P1 (players): `POST /game-sessions/{sessionId}/end` records `approvalId` as its
      key.
  - A repeat answers the first outcome, and another session under the same key is 409 `key_reused`.
  - Suites first, then the code; then `specs/008-players` spec (FR-017) and contract rows.
- [X] T009 Amendment P3 (players): `GET /{playerId}/agent-profile?fields=`, admitting
      `serviceOf("support-agent")` only.
  - It serves `email`, `firstName`, `lastName`, `country`, `language` and `dateOfBirth`; any other
    name is 400 `unknown_field`.
  - It sits beside the status route in `Endpoints.scala`, with its suite (admission, each field,
    unknown, unknown player).
  - Then spec 008 and the contract row.
- [X] T010 Amendment A1 (payouts): `services/payouts/src/main/scala/io/eitheror/casino/payouts/api/SupportEndpoint.scala`.
  - Prefix `/v1/casino/payouts-service/<slug>`, ACL `Routes.serviceOf("support-agent")`.
  - `POST /players/{playerId}/withdrawal-requests` runs `PlayerEndpoint`'s workflow (extract the
    shared body into a function both call), with `MISSING_KEY` and `KEY_REUSED` as there.
  - Suite: support-agent admitted, a player token refused, and the same key answering the same
    request.
  - Then spec 007 (FR-004) and `specs/007-payouts/contracts/routes.md`; register the endpoint in
    the service.
- [X] T011 Amendment R2–R4 keys (responsible-gambling): `services/responsible-gambling/src/main/scala/io/eitheror/casino/rg/profile/RgKeys.scala`.
  - A key value entity per player holding the last 64 keys with each command's outcome and a body
    hash.
  - A repeat answers the outcome; another body is `key_reused`; a key older than those is
    `key_expired`.
  - Codec registered; unit and kit suites.
- [X] T012 Amendments R1–R4 (responsible-gambling): `rg/api/SupportEndpoint.scala`.
  - Prefix `/v1/casino/rg-service/<slug>`, ACL `Routes.serviceOf("support-agent")`.
  - `GET /{player}/limits?currency=`, `PUT /{player}/limits/{type}` `{value, currency?, key}`,
    `PUT /{player}/cooling-off` `{until, key}`, `PUT /{player}/permanent-exclusion` `{key}`.
  - Each calls the same command as `ProfileRoutes`, through `RgKeys`. A repeated exclusion is 200
    `{status:"self_excluded"}`.
  - Suites per route: admission, a player refused, a repeat, `key_reused`, each refusal code.
  - Then spec 010 (FR-002, FR-014, FR-018, FR-025) and its contract rows; register the endpoint.
- [X] T013 Run `just service players test`, `just service payouts test` and
      `just service responsible-gambling test` (each `it` with `-Dit=true`), all green. Then
      `just features` at 0 findings.

### The service's foundations

- [X] T014 [P] `S/SupportCodecs.scala` and `S/Json.scala`: a codec for every stored type in
      data-model.md, with the manifests named there; round-trip tests in `T/CodecsSuite.scala`.
- [X] T015 [P] Pure, test first: `T/domain/SummariesSuite.scala`, then `S/domain/Summaries.scala`.
  - It produces contracts/tools.md's five summaries, with major units via `contract.money`
    (0, 2 and 3 exponents) and the withdrawal forfeit suffix.
  - Port `O/tests/test_write_tools.py`'s and `test_rg_tools.py`'s summary assertions ("Withdraw
    50.00 GBP from your account", "regulatory" or "delay", "cannot be shortened", "permanent" and
    "cannot be reversed").
  - Add a property test: every `apply_cool_off` summary names its end and "cannot be shortened",
    and every `apply_self_exclusion` summary says "permanent" and "cannot be reversed", for any
    argument (FR-022's required statement, by construction; research R8).
- [X] T016 [P] Pure, test first: `T/domain/MappingSuite.scala`, then `S/domain/Mapping.scala`.
  - It maps each neighbour's answer to the model's fields (contracts/neighbours.md *Reads*).
  - Rules: the wallet's 404 becomes zeros; only active grants; `reason` never; `game_id` for
    `game_name`.
  - Port `O/tests/test_read_tools.py`'s cases.
- [X] T017 [P] Pure, test first: `Redaction` (FR-028's redacted arguments; data-model.md),
      `Frames` (the SSE events of contracts/routes.md, the original's names and fields), and
      `ConfigRules` (data-model.md's field rules, `safeguarding_not_configured`, `unknown_field`),
      each with its suite under `T/domain/`.
- [X] T018 [P] `S/clients/`: `Neighbour` (a call through `context.services` with a 5 s timeout and
      one retry on a timeout), then `Wallet`, `Payouts`, `Players`, `Rewards` and `Rg`.
  - Each is a trait, an HTTP implementation and a fake that records the caller, the path and the
    key.
  - `T/clients/NeighbourSuite.scala` covers a 5 s timeout per attempt, one retry on a timeout
    (not on a refusal), and a refusal carried as a value (FR-012, R15).
- [X] T019 `S/conversation/Conversation.scala` and `Model.scala`: data-model.md's state, its events
      and every command row. Add `T/conversation/ConversationSuite.scala` (unit kit) covering
      binding, the other player, decided twice, ended, and forgotten.
- [X] T020 `S/config/AgentConfiguration.scala`, `ConfigVersion.scala` and `Model.scala`.
  - `put` writes `ConfigVersion` first, then moves the version.
  - A put with nothing changed only replies, and the last 16 notes are kept.
  - `T/config/ConfigurationSuite.scala`.
- [X] T021 `T/it/Kit.scala` and `T/it/FakeNeighbours.scala`.
  - `AnkkaTestKit` over Postgres with `AgentRuntime` descriptors, `ProjectionRuntime()`,
    `TimerRuntime` on a `MovableClock`, and the in-memory broker.
  - `TestModelProvider` and `TestJudgmentProvider` per test, and the five fakes under
    `localServices`.
  - Helpers: a player token, an operator token, an SSE reader over `java.net.http` (as engagement's
    `AgentStreamSuite`), and `restartService()`.
- [X] T022 `S/agent/Motoko.scala`, `Tools.scala` and `Context.scala`. These are the agent's
      skeleton; the stories fill it in.
  - The model: the provider kind and key come from `Settings`; the model name comes from the
    conversation's `ConfigVersion.model`, set per turn with `effects.model(...)` (FR-008).
  - Nothing here or in `Neighbour` logs, or puts on a span, a message's text, a reply, or a tool's
    arguments (FR-020).
  - The agent: `Agent.Companion(ComponentId("motoko"))`, a `chat` stream, and the system message
    from the conversation's `ConfigVersion`.
  - Session memory: `forAgent(SessionId(s), subject = Some(DataSubject("player/<id>")))`.
  - Runtime: `AgentRuntime.withCompaction(CompactionSettings(bytes, recent), summariser)`, with the
    model from `Settings`.
  - Tools are built in the handler over `context.services`.
  - On the first turn the player's state and balance are loaded into `withContext` (FR-007).
- [X] T023 `S/api/ChatEndpoint.scala`, `Ctx.scala` and `Answers.scala`: the two `sseBody` routes
      under `Routes.player(slug)`, in contracts/routes.md's check order. The stories fill in the
      bodies. `S/SupportService.scala` and `SupportRuntime.scala` register every component.
- [X] T024 `T/features/SupportFeatures.scala`: bind the six `features/support-agent/` files on the
      kit, each step failing until its story's tasks land.

**Checkpoint:** the neighbours are amended and green, the service starts, and the feature suite
runs (red).

## Phase 3: User Story 1 — reads (P1) 🎯 MVP

**Goal**: a player asks about their account and is answered from the owning services as
`support-agent`.

**Independent test**: `reads.feature` green on the kit. T043's recording replays in Phase 10 (T056).

- [X] T025 [P] [US1] `T/agent/ReadToolsSuite.scala`, written first and failing first: each of the
      six tools on the scripted provider calls its fake as `support-agent` for the bound player and
      answers the mapped fields.
  - A timeout is answered to the model as "cannot be read right now", and the turn does not fail
    (FR-012).
  - A currency argument is never accepted (FR-011).
- [X] T026 [US1] Implement the six read tools in `S/agent/Tools.scala` over the clients and
      `Mapping`. `get_transaction_history` takes a limit of 1..50, default 10.
- [X] T027 [US1] Personal fields (FR-018) in `S/agent/Context.scala`: `get_player_state` calls
      `agent-profile` only for `personalFields`. `T/it/PersonalSuite.scala` uses a provider that
      records requests and asserts that no other personal value reaches the model (SC-005).
- [X] T028 [US1] Stream the chat in `ChatEndpoint`: `delta` per `AgentPart.Text`, then `done`.
      `T/it/ChatSuite.scala` covers the first `delta` within 5 s at p95 on the scripted provider
      (SC-002), 400 `missing_fields`, and 403 `session_not_yours`.
- [X] T029 [US1] Bind `reads.feature`'s eight scenarios in `SupportFeatures`; all green.

**Checkpoint:** a player can ask and be answered.

## Phase 4: User Story 2 — actions with approval (P1)

**Goal**: an approved write is applied once by the owning service, keyed by the approval id.

**Independent test**: `actions.feature` green. T044's recording replays in Phase 10 (T056).

- [X] T030 [P] [US2] `T/it/ResumeSuite.scala`, written first, covering these cases:
  - a confirmation followed by `done` on the chat's stream;
  - approve → `action_result` `{success:true}`, a `delta`, `done`, and one fake call keyed by the
    approval id;
  - reject → no call, and a reply;
  - a new message while awaiting → 409 `Session already active`;
  - a second decision → 409 `already_decided`;
  - 404 `no_pending_action` and 422 `invalid_action`;
  - a refusal from the service → `action_result` `{success:false}` naming it;
  - `restartService()` between decide and run → the turn ends without running the tool;
  - expiry after `approvalMinutes` on the moved clock → decided by `ankka`, and the next message is
    a new turn (FR-016).
- [X] T031 [US2] Add `request_withdrawal` and `close_active_session` to `Tools.scala`.
  - Both are `requiresApproval(approvalMinutes)`, with the key being the approval id.
  - The summary comes from `Summaries`, with the hold preview read first and `confirmForfeit` set
    (contracts/neighbours.md).
  - Currency and amount are validated before a request (FR-011): another currency, or an amount
    of zero or less, is answered to the model as a refusal, with no approval requested and no
    service called.
- [X] T032 [US2] `set_deposit_limit`: the period is validated, and the detail names RG's `appliesAt`
      (actions.feature's last scenario).
- [X] T033 [US2] The resume route in `ChatEndpoint`.
  - `decide` with `Decision.approved` or `Decision.refused(note = feedback)`.
  - Then stream `action_result` from the `ToolCalled` the tool recorded, and the reply's `delta`s
    and `done` (research R3).
  - Record `ApprovalRequested` and `Decided` on `Conversation`.
- [X] T034 [US2] Bind `actions.feature`'s eight scenarios; all green. The FR-015 and SC-001 suite
      covers approve, reject, repeat and restart, and shows no tool running twice or unapproved.

## Phase 5: User Story 3 — break and self-exclusion (P1)

**Goal**: a break or an exclusion is stated plainly, confirmed once, applied by
responsible-gambling, and ends the conversation.

**Independent test**: `protections.feature` green.

- [X] T035 [P] [US3] `T/domain/SofteningSuite.scala`, then `S/domain/Softening.scala`: contracts/tools.md's
      phrase list, with whole words and any case, applied only to a turn about a break or an
      exclusion.
- [X] T036 [US3] `apply_cool_off` and `apply_self_exclusion` in `Tools.scala`, each with one
      approval. After approval, `Conversation.end`: `SelfExcluded` with the closing message, or
      `OnBreak(until)` (FR-017). Port `O/tests/test_rg_tools.py`'s "suspends exactly once" and
      reject cases.
- [X] T037 [US3] The ended conversation: the chat route answers 409 `conversation_ended` with the
      closing message, and the break's end lifts it. Covered in `ChatSuite`.
- [X] T038 [US3] Bind `protections.feature`'s five scenarios (the softening one needs T047's
      guardrail wiring; bind it there). The rest are green.

## Phase 6: User Story 4 — durable, bounded, forgotten (P2)

**Independent test**: `conversations.feature` green.

- [X] T039 [P] [US4] `T/it/DurabilitySuite.scala`: a restart keeps the history and an awaiting
      request; the same session id from another page continues; compaction past 100 000 bytes
      keeps the last 10 messages, using a separate scripted summariser (FR-026, FR-005).
      It also covers a stream cut mid-turn: the turn completes in the session, and the next turn's
      model is given that reply in its history, with the turn not run again (edge case).
- [X] T040 [US4] `S/timers/Forget.scala`: `forget-<session>`, set again on every turn at
      `lastActivity + retentionDays`.
  - When it fires and the conversation is untouched, it calls `forSessionMemory(id).clear` and
    `Conversation.forget`; otherwise it sets itself again.
  - The payload is the session id.
  - `T/it/ForgetSuite.scala` uses the moved clock.
- [X] T041 [US4] `T/it/ErasureSuite.scala`: after the subject's erasure (ankka 042's kit hook), the
      memory reads empty, a new turn starts with no history and records nothing, and `Conversation`
      holds nothing to erase (SC-004 at the kit's level).
- [X] T042 [US4] Bind `conversations.feature`'s five scenarios; all green.

## Phase 7: User Story 5 — guardrails, limits, hand-off (P2)

**Independent test**: `guardrails.feature` green, plus the softening scenario.

- [X] T043 [P] [US5] Pure, test first: `OtherPlayer` (a UUID other than the player's, an email
      address, `*.svc`, `*.cluster.local`, `*.internal`), `Rate`, `Budgeting` and `Handoff` (the
      marker replaced, and a trailing partial marker held back across `delta`s). Each has its
      suite under `T/domain/`.
- [X] T044 [US5] `S/agent/Guardrails.scala`, in FR-021's order:
  - input: `maxInputLength`, `forbidding("other-player")`, `judged("override")`, and
    `judged("safeguarding")` when real money or opted in;
  - output: `other-player` and `softening`;
  - the judged guardrails are on only with `AGENT_JUDGE`.
- [X] T045 [US5] `S/limits/TurnWindow.scala` and `Budget.scala`, with kits.
  - The chat route takes a turn first: 429 `too_many_turns` with `retryAfter`.
  - After each turn, the session's usage increase is added to `Budget`; past the limit, the
    unavailable message is sent as one `delta` (FR-024).
- [X] T046 [US5] In the chat route:
  - a safeguarding refusal becomes the safeguarding message and helpline as `delta` then `done`,
    with no tool run, recording `GuardrailRejected`;
  - an unreachable judge becomes `error` with the unavailable message;
  - the hand-off marker is replaced by the configured message, recording `HandedOff`.
- [X] T047 [US5] Retry an output rejection at most twice, then answer the refusal (FR-022; research
      R8). The text is held until checked, so a rejected reply is never sent (research).
      `T/it/GuardrailSuite.scala` covers each guardrail and the retry.
- [X] T048 [US5] Bind `guardrails.feature`'s nine scenarios and the softening scenario of
      `protections.feature`; all green.

## Phase 8: User Story 6 — operators (P3)

**Independent test**: `operators.feature` green.

- [X] T049 [P] [US6] `S/api/ConfigEndpoint.scala`.
  - Routes: `GET`/`PUT /`, `GET /versions/{version}`, `GET /status`, `GET /conversations`.
  - ACL: `Routes.operatorHolding(slug, "brand_admin", "resource_admin", "platform_admin")`.
  - Refusals as contracts/routes.md.
- [X] T050 [US6] `S/reads/ConversationsByPlayer.scala` (a view of `Conversation`), serving the list
      and status.
- [X] T051 [US6] The agent off: the chat route answers 503 `agent_unavailable` with the unavailable
      message. A real-money brand cannot set `enabled` without safeguarding, and gets 422.
      `T/it/ConfigSuite.scala` also checks that a new version governs only conversations started
      after it.
- [X] T052 [US6] Bind `operators.feature`'s three scenarios; all green.

## Phase 9: Audit (every story; FR-028)

- [X] T053 [P] `T/facts/AuditMappingSuite.scala`, written first: each event → contracts/audit.md's
      `AuditRecord`, with no message text, `feedback` or personal value in any field.
- [X] T054 `S/facts/AuditPublisher.scala` (`eventsOf(Conversation)`) and `ConfigAudit.scala`
      (`stateOf(AgentConfiguration)`, every note in `recentChanges`), as engagement's.
      `T/it/AuditSuite.scala` checks a record within one minute of each tool call, decision and guardrail rejection on
      the in-memory broker (SC-006).

## Phase 10: Parity and polish

- [ ] T055 Record the original (needs the user; research R13, quickstart).
  - Run the original on kind with `casinoAgent.enabled: true` and `FunctionModel`.
  - Record T043, T044, and each write tool's approve and reject into `tests/port/support-agent/`
    with ankka-port (its `README.md` naming the run).
  - Ask the user before starting kind or the original.
- [ ] T056 `T/port/ParitySuite.scala`: replay `tests/port/support-agent/`, holding frame names,
      fields, summaries and details exact, with plan.md's *Divergences* marked per recording.
- [ ] T060 [P] `T/it/TelemetrySuite.scala` (FR-020): runs a conversation with a tool call, an
      approval and a guardrail rejection, then reads the kit's captured logs and spans. Asserts that
      none carries the message text, the reply, the `feedback`, or a tool's argument values.
- [X] T057 [P] `T/agent/PromptSuite.scala`: the default prompt is the original's
      (`O/agents/motoko.py:30-47`) byte for byte, plus the two lines of contracts/tools.md.
- [ ] T058 [P] `services/support-agent/README.md` (components, routes, env, run, suites).
  - It states that what a session sent to the model provider is beyond the installation's erasure
    (FR-019).
  - It carries the spec 019 note: the proxy forwards the realm token, and the widget reads the
    resume's stream.
- [ ] T059 Final gate: `just support-agent-test`, `just support-agent-it`, the three neighbours'
      suites, `just test` and `just features`, all green; quickstart steps 1–6 walked on
      `just support-agent-run`.

## Dependencies

- Setup (T001–T006) → Foundational (T007–T024).
- The neighbours' amendments (T007–T013) block US1's `get_rg_status` and `get_player_state`, and
  all of US2 and US3.
- Stories:
  - US1 → US2, which adds the resume route;
  - US2 → US3, which uses approvals;
  - US4, US5 and US6 each need only US1, and can run in parallel after it;
  - T038's softening scenario waits for T047.
- Audit (T053–T054) after US2; parity (T055–T056) last.

## Parallel examples

- After T006: T007–T009 (players), T010 (payouts) and T011–T012 (RG) touch three different builds.
- T014–T018 are separate files.
- After US1: T039–T041 (US4), T043 (US5's pure parts) and T049–T050 (US6).

## Implementation strategy

The MVP is Setup + Foundational + US1: a player is answered from the services. US2 and US3 add the
actions; with them, every P1 story passes (SC-003, short of T043 and T044 against the widget, which
wait for spec 019). Then US4–US6, audit and parity.
