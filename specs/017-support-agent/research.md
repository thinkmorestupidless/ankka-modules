# Research: support-agent

Planned 2026-11-09. `EP` is `~/Documents/projects/eitheror/eitheror-platform`; `ankka` is
`~/Documents/projects/thinkmorestupidless/ankka`. Each decision says what was chosen, why, and what
else was weighed.

## R1. The original, read whole

The agent is `EP/starter-packs/casino/agent/` (PydanticAI, FastAPI), `claude-sonnet-4-6`
(`agents/motoko.py:61`).

- **Routes** (`main.py:52-73`): `POST /run/chat_with_player` `{message, session_id}`, Bearer hand-off
  token; `POST /resume/{session_id}` `{action: approve|reject, feedback?}`; `GET /healthz`;
  `GET /mcp/manifest`.
- **Answers**: run is 409 "Session already active" while a confirmation awaits; resume is 200
  `{ok: true}`, 404 when nothing awaits, 409 when already decided, 422 for another action, 403 when
  the token's `session_id` is not the path's (`routes/resume.py:18-51`, `auth.py:45-53`).
- **Frames** (`sse.py:19-33`): `event: <name>` and `data: <json>`.
  - `delta` `{token}`. The whole output is sent as one delta (`run.py:58-61`).
  - `confirm_action` `{action_name, summary, session_id}`.
  - `action_result` `{action_name, success: true, detail}`; `success: false` is never sent.
  - `done` `{}`.
  - `error` `{message}`.
- **The open stream**: a write tool blocks on an in-process future, so the run's stream stays open
  through the confirmation and later carries `action_result` and `done`. The widget reads them there
  and ignores the resume's answer (`MotokoChat.tsx:209-222`).
- **The session id** comes from `crypto.randomUUID()` in the widget, kept for the component's life
  (`MotokoChat.tsx:138-139`). Only the pause uses it. No history is kept.
- **The system prompt** is quoted in [contracts/tools.md](contracts/tools.md), with every tool, its
  parameters, what it returns and its summary and detail texts (`motoko.py:30-47, 67-144`,
  `tools/casino/read.py`, `tools/casino/write.py`).
- **Tests** (`tests/`): read-tool mappings; each write tool's confirm payload on approve and reject;
  the cool-off and exclusion statements, suspended exactly once; 401s on the run route; resume's 200,
  403, 404 and 422; live-key integration tests, skipped. Two are stale (a wrong arity, missing
  fields).
- **Playwright** `16-motoko-chat.spec.ts`: skipped unconditionally (line 10).
  - T043 opens the chat, asks "What is my balance?", and expects a non-empty reply within 15 s.
  - T044 sends "I want to withdraw 10", expects "Confirm action" with Confirm and Cancel, confirms,
    expects a reply, and expects the card to go within 5 s.
- **The proxy** (`casino-player-frontend-next/src/app/api/motoko/…`) mints an HS256 token
  `{session_id, tool, player_id, brand_id, currency, iat, exp: +900 s}` from cookies. It forwards
  the body as is and streams the answer back. The widget is gated by the Unleash flag
  `player-motoko-chat` and hidden while a game plays.
- **Absent in the original**: rate limits, timeouts on a pending confirmation, retries beyond the
  substrate client's one.

Corrections to the spec, made with this plan:

- the resume body's note is `feedback`, not `note`;
- 404 when nothing awaits;
- one `delta` in the original (here, text streams as produced: a divergence the widget absorbs, since
  it appends);
- `action_result.success` may be `false` here, when the service refuses.

## R2. ankka: what ships, and the version

| Feature | First release |
|---|---|
| Streaming, compaction, `TestModelProvider`, judged guardrails | ≤ 0.13.0 |
| 029 approvals and tool→service calls | 0.13.0 |
| 032 timers | 0.13.0 |
| 036 blueprints | 0.13.0 |
| 038 secret stores | 0.13.0 |
| 042 a session with a data subject | **0.16.0** |

`ankka/specs/*/tasks.md`: 029's open T082 is a manual quickstart check, and 038's open T065 is GKE
access logs; neither touches this service. Everything is merged on ankka's main.

**Decision: ankka 0.16.0**, as `engagement` and `audit`. FR-019 needs `forAgent(id, subject = Some(DataSubject(...)))`
(`AgentRuntime.scala:1281`). The `support` module's descriptor moves to runtime 0.16.0. The
consequence is the one engagement carries: a platform runs its minor and the one below, so the
module cannot deploy beside a `core` on 0.13 until `core` reaches 0.15 (spec 014's known gap).

**Rejected: 0.13.0 with a hand-rolled subject key.** It would duplicate 042, against constitution
*Scope*: an ankka gap is an ankka spec, not a workaround.

## R3. The stream, the gateway and the decision (settled with the user, 2026-11-09)

ankka's gateway ends every response at 15 s, SSE included (`reference/limitations.md`,
*Networking*). A decision is not pushed to a stream still open (029). `AgentPart` has only `Text`
and `AwaitingApproval`.

**Decision: the run stream ends at `confirm_action`, and the resume route answers as a stream.**

- The run stream carries `delta` for each `AgentPart.Text`, then one of:
  - `confirm_action` and `done` on `AgentPart.AwaitingApproval`;
  - `done` when the turn answers.
- The resume route calls `decide`, then streams `action_result` (from the tool's recorded outcome),
  a `delta` with the reply, and `done`.
- The widget reads the resume's answer instead of waiting on the first stream. That is a frontend
  change beyond the base URL, carried by spec 019's runbook beside the token forwarding.

**Rejected: the original's open stream.** It cannot outlive the gateway's 15 s, and a human
decision takes longer. Lifting that would be an ankka spec, and 017 would wait on it.

**Turn length.** A turn with several tool calls can pass 15 s. The turn completes in the session
anyway (029: a turn is the session's, not the request's). A cut stream's next request reads the
reply from memory (the spec's edge case). SC-002 (first `delta` within 5 s) is unaffected.

## R4. Tools call by identity; three neighbours gain service routes (settled with the user, 2026-11-09)

Read from the code, 2026-11-09:

| Tool | Route | Admits `support-agent` today |
|---|---|---|
| `get_player_state` | players `GET /players/{playerId}/status` | yes, `serviceOf("game-provider","support-agent","exchange")` (`players/api/Endpoints.scala:150`) |
| `get_wallet_balance` | wallet `GET /wallets/{player}/{currency}/balance` | yes, `Routes.service()` |
| `get_transaction_history` | wallet `GET /wallets/{player}/{currency}/transactions` | yes |
| hold preview (`request_withdrawal`'s summary) | wallet `GET /wallets/{player}/{currency}/holds/preview?amount=` | yes |
| `get_active_bonuses` | rewards `GET /rewards/{playerId}/grants` | yes, by name (`rewards/api/PlayerGrants.scala:24-39`) |
| `get_session_history` | players `GET /players/{playerId}/game-sessions` | yes, `serviceOf("support-agent")` |
| `close_active_session` | players `POST /players/game-sessions/{sessionId}/end` `{approvalId}` | yes, but `approvalId` is only the actor |
| `request_withdrawal` | payouts `POST /players/{playerId}/withdrawal-requests` | **no**: `Routes.player` and `mine` |
| `get_rg_status`, `set_deposit_limit`, `apply_cool_off`, `apply_self_exclusion` | responsible-gambling `GET …/limits`, `PUT …/limits/{type}`, `PUT …/cooling-off`, `PUT …/permanent-exclusion` | **no**: player routes only, and no key |

**Decision: amend the neighbours** ([contracts/neighbours.md](contracts/neighbours.md)).

- **payouts**: a service route for the withdrawal request, admitting `support-agent` only, with the
  player id in the path and the body's `key` as today.
- **responsible-gambling**: service routes for the status, a deposit limit, a break and an
  exclusion, admitting `support-agent` only, each command taking a `key`. A repeat answers the first
  outcome (constitution II's rule applied to RG's commands).
- **players**:
  - the session end takes `approvalId` as its key (a repeat answers the first outcome);
  - the status answers the player's `currency`;
  - a read of the personal fields a brand names (R9), admitting `support-agent` only.

Each route runs the same command the player route runs. Only the admission differs, and the player
id comes from the path, never from the model (FR-011).

**Rejected: forwarding the player's realm token.** It breaks call-by-identity (029 FR-010). An
approved tool resumed after a restart, or after the token's five minutes, would have no valid token.

## R5. The switch: the entitlement runs the service, the configuration turns the agent on

Spec 021 made the entitlement to the `support` module the switch: "No `SUPPORT_AGENT_ENABLED`
switch" (`specs/021-core-and-modules/contracts/module-manifests.md:78`). The spec's scenarios still
need the agent off in a running service:

- a brand whose agent is off answers "not available";
- a brand that takes real money cannot turn it on without a safeguarding reply.

**Decision: both.**

- A brand not entitled to `support` does not run the service, so the routes do not exist. The
  player site's Unleash flag already hides the widget.
- A brand entitled runs it, with `AgentConfiguration.enabled` false until an operator turns it on.
- FR-001 is amended to say so.

## R6. Conversations: an event sourced entity beside ankka's session memory

ankka's session memory holds the transcript, encrypted under the subject (042). Four things it does
not hold:

- the session's player binding (FR-004);
- the configuration version the conversation started on (FR-029);
- whether it has ended (FR-017);
- the last activity (FR-027).

The audit also needs a durable trail of tool calls, requests, decisions, rejections and hand-offs
(FR-028).

**Decision: `Conversation`, event sourced, id = the session id.**

- Its events are the trail, and `AuditPublisher` reads them.
- They carry no message text and no personal value, only names, redacted arguments and outcomes,
  so erasure has nothing to do here. The transcript in session memory is what 042 erases.
- A tool appends `ToolCalled` with its outcome. That is how the resume route knows `action_result`'s
  success and detail.

**Rejected: a key value entity per conversation.** The trail would be overwritten, and the audit
would have to diff states.

## R7. The approval's time limit

FR-010 asks for "the player session's remaining life". The access token's `exp` is minutes, and
the realm session's life is not in the token. **Decision: a configured limit, default 15 minutes**,
the original's hand-off token life (900 s). A decision after the player's token expires is refused
anyway as unauthenticated. FR-010, FR-016 and the edge case are amended. `requiresApproval(within)`
needs `TimerRuntime` registered, which R10 registers anyway.

## R8. Guardrails, and what the runtime does not do

- **Input, in FR-021's order**:
  - `Guardrail.maxInputLength(configured)`;
  - `Guardrail.forbidding("other-player", …)`: a UUID, an email address or an internal hostname
    (`*.svc`, `*.cluster.local`, `*.internal`);
  - `Guardrail.judged("override").onInput(Refuse.ifYes(…, atLeast = 0.7))`.
- **Safeguarding**: `Guardrail.judged("safeguarding")`, on a brand that takes real money or opted in
  (FR-023). It refuses, and the endpoint answers the configured safeguarding reply instead of the
  refusal, then records `GuardrailRejected(safeguarding)`.
- **Output**:
  - `other-player`: a UUID that is not the player's own, or an internal address;
  - `softening`: a reply about a break or an exclusion carrying a phrase from the list in
    [contracts/tools.md](contracts/tools.md) (*Softening*).
- **Summaries**: the summary of a break or an exclusion is composed by `Summaries` from the tool's
  arguments, never by the model, so it carries its statement by construction. `softening` guards
  the model's own words around it.
- **A judge that is unreachable** is `Unavailable`, answered with the unavailable reply and never as
  a refusal (029, `build/judgments.md`).
- **No retry in the runtime** ("the agent loop does not retry", `concepts/designing-agents.md`). A
  rejected turn writes nothing. The chat endpoint asks again, at most twice (FR-022). On a stream,
  the rejection follows text already sent, as the scenario says.

## R9. Personal fields a brand names (FR-018)

The status route answers `status`, `displayName` and `verification`.

**Decision**: a brand's configuration lists the personal fields its model may see. `get_player_state`
reads them through players' new route `GET /players/{playerId}/agent-profile?fields=`. That route
admits `support-agent` only and answers only fields named personal by spec 001 FR-023, refusing any
other name (`unknown_field`). With no field named, nothing is read. Every change to the list is a
configuration version and an audit record naming the fields. Wallet rows' `reason` (a `Personal`)
is never returned (`TransactionHistory` projects it out).

## R10. Rate, budget, retention

ankka has no rate limit, budget or session expiry (`reference/limitations.md`;
`concepts/designing-agents.md`, *Failure and cost*).

- **Turns per minute**: `TurnWindow`, a key value entity per player holding the minute's turn
  times. It must hold across nodes, so it cannot live in instance memory. Past the limit the answer
  is 429 `too_many_turns` with `retryAfter`.
- **Daily budget**: `Budget`, a key value entity per day (`<yyyy-mm-dd>`). After each turn the
  endpoint reads the session's `usage` from `forSessionMemory(id).history` and adds the increase
  since `Conversation`'s last count. Past the brand's tokens, every turn answers the fixed
  unavailable reply, and the status says `budgetSpent`.
- **Retention**: a timer `forget-<session>` at the last activity plus the brand's retention, set
  again on every turn. When it fires it checks the last activity: if the conversation was touched
  since, it sets itself again; otherwise it calls `forSessionMemory(id).clear` and records
  `Forgotten`. The audit records remain.
- **Payload**: the timer carries the session id alone, within the 1,024 bytes allowed.

## R11. Configuration

`AgentConfiguration` is a key value entity, id = slug, in engagement's shape: a version, the last 16
change notes, and the brand's role to change it (`Routes.operatorHolding`).

- **Versions**: each version's content is an immutable `ConfigVersion` (id `<slug>/<version>`), so a
  conversation reads the version it started on (FR-029).
- **What a version holds**:
  - the prompt;
  - the hand-off, unavailable, closing and safeguarding messages, and the helpline;
  - the support chat's hours;
  - the personal fields;
  - the model name;
  - the limits: input length, turns per minute, daily tokens, approval time;
  - the retention;
  - `safeguardingOptIn`;
  - `enabled`.
- **Turning it on**: a `PUT` that sets `enabled` on a brand that takes real money
  (`CASINO_REAL_MONEY`) with no safeguarding message or helpline is refused, 422
  `safeguarding_not_configured`.
- **The model provider's key** is the module's existing `model-provider-key` secret, read from the
  secret store as the manifest declares.
- **Compaction is a service setting, not a version field.** ankka sets it on the runtime at start
  (`AgentRuntime.withCompaction`). Its two numbers become settings (`AGENT_COMPACTION_BYTES`,
  `AGENT_COMPACTION_RECENT`), defaults 100 000 and 10, applied on restart. FR-005 and FR-029 are
  amended.
- **Rejected: rebuilding the runtime on a change.** The runtime is registered once.

## R12. The hand-off

FR-025 says the reply ends with the hand-off message "when the model answers that it cannot help".
The tool set is fixed (spec: "Not carried over: nothing"), so the signal is not a tool.

**Decision**: the prompt tells the model to end such a reply with the marker `⟦handoff⟧`. The
endpoint holds back a trailing partial marker while streaming, replaces the marker with the
configured hand-off message, and records `HandedOff`.

**Rejected: a `hand_off` tool.** It would add a tool the original does not have.

## R13. Recordings and the oracle

No Go end-to-end exists for the agent. The constitution's oracle is the agent's own suite and
T043/T044.

- **The recording run** needs the user: the original on kind with `casinoAgent.enabled: true` and
  PydanticAI's `FunctionModel` scripted in place of Anthropic. ankka-port records:
  - T043's and T044's HTTP exchanges against the agent;
  - each write tool's confirm and result on approve and reject.
- **The recordings** go under `tests/port/support-agent/`.
- **`ParitySuite`** replays them, with frame names, fields and the summary and detail texts held
  exact. Every divergence is named in plan.md, *Divergences*.
- **The original's own tests** are ported case by case into the unit suites, as engagement did.

## R14. Testing

- `TestModelProvider` scripts each scenario's tool calls and text. It fails when its script runs
  out (FR-008).
- `TestJudgmentProvider` answers the judged guardrails, with `failNext` for an unreachable judge.
- `AnkkaTestKit.start` over Postgres:
  - `AgentRuntime.descriptors` and `ProjectionRuntime()`;
  - `TimerRuntime` with a `MovableClock`;
  - `InMemoryBroker` for `platform.audit`.
- The fakes: `FakeWallet`, `FakePayouts`, `FakePlayers`, `FakeRewards` and `FakeRg`, under
  `localServices`, each recording the caller and the key.
- SSE is read over `java.net.http`, as in engagement.

## R15. Tool timeouts (settled with the user, 2026-11-09, at analyze)

Each neighbour call times out at 5 s and is retried once on a timeout, as the original's substrate
client (`tools/platform/client.py:40-71`); a tool answers at most 10 s after it is called. FR-012 is
amended. A turn with several slow tools may pass the gateway's 15 s; it completes in the session
(R3). **Rejected:** 5 s with no retry, which keeps turns shorter but drops the original's tolerance
of a blip.

## For ankka

Nothing blocks this service, and each item below is noted for an ankka spec.

- No budget or rate primitive: R10 builds both from entities.
- No retry of a rejected output.
- No push of a decision to an open stream.
- The gateway's 15 s cut.

## Design finished

2026-11-09: analyze reported nothing above LOW.
