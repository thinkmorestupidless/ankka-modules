# Implementation Plan: support-agent

**Branch**: `017-support-agent` | **Date**: 2026-11-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/017-support-agent/spec.md`

## Summary

`support-agent` is Motoko on ankka: a brand service, run for a brand entitled to the `support`
module, that answers a signed-in player about their account and, with the player's confirmation,
asks the owning service to act. It:

1. serves the original's two player routes as server-sent-event streams. The chat's stream ends at
   a confirmation, and the resume's stream carries the action's result (research R3);
2. runs one ankka agent per brand, with each session's memory tagged with the player as its data
   subject (ankka 042), compacted, and forgotten after the brand's retention;
3. offers the original's six read tools and five write tools. Each tool calls its owning service
   as `support-agent`, and each write is approved by the player and keyed by the approval id;
4. guards input and output: the length limit, another player's data, attempts to override the
   prompt, softening, and safeguarding (on for a brand that takes real money). It also limits a
   player's turns per minute and the brand's tokens per day;
5. keeps a versioned, audited configuration per brand and writes an `audit_record` for every tool
   call, request, decision, rejection and hand-off.

To do this it has:

- one event sourced entity, `Conversation`;
- four key value entities: `AgentConfiguration`, `ConfigVersion`, `TurnWindow` and `Budget`;
- one agent, `Motoko`;
- one view, `ConversationsByPlayer`;
- two audit consumers;
- one timer, `forget-<session>`;
- three endpoints.

Three neighbours are amended so the tools can reach them by identity ([contracts/neighbours.md](contracts/neighbours.md)):

- `payouts`: A1;
- `responsible-gambling`: R1–R4;
- `players`: P1–P3.

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.16.2: 0.16 for sessions with a data subject (042, research
R2), 0.16.2 for compacting them (064; research, *Found during implementation*). The contract, built on 0.13.0, is evicted to it as engagement's is (`VersionScheme.Always`).
The amended neighbours stay on their own versions.

**Primary Dependencies**:

- `com.thinkmorestupidless`: `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-agent`,
  `ankka-auth-oidc`, `ankka-telemetry-otlp`, `ankka-testkit`.
- `io.eitheror:casino-contract`:
  - routes: `Routes.player`, `Routes.playerId`, `Routes.operatorHolding`, `Routes.service`,
    `Routes.serviceOf`;
  - `contract.money`, for major units and the currency's exponent;
  - `PlatformAudit`, `AuditConsumer`, `AuditCompanion`;
  - `port.Recording`, `port.Replay`.
- **No contract change.** No new fact, and the audit record is the existing one.

**Storage**: the service's own Postgres from ankka (journal, durable state, views, timers, session
memory). The model provider's key is the module's `model-provider-key` secret (ankka 038).

**Testing**:

- munit and munit-scalacheck over the pure packages, with the original's agent tests ported case by
  case;
- the integration kit: `AnkkaTestKit` over Postgres with `TestModelProvider`,
  `TestJudgmentProvider`, `TimerRuntime` on a `MovableClock`, the in-memory broker, and fakes for
  wallet, payouts, players, rewards and responsible-gambling under `localServices`;
- `features/support-agent/` bound by a features suite;
- `ParitySuite` replaying `tests/port/support-agent/`;
- the neighbours' own suites for each amendment.

**Target Platform**: an ankka project per brand (spec 001), as the `support` module's service.

**Project Type**: one sbt build under `services/support-agent/`, plus changes to three service
builds and the module manifest.

**Performance Goals**:

- SC-002: the first `delta` within 5 s at p95 on the scripted provider;
- a tool call within 5 s per attempt, with one retry on a timeout (R15, FR-012);
- SC-003: User Stories 1–3 pass with the original's tool set unchanged, and T043 and T044 pass
  unskipped against the rebuild (the widget change from R3 included, in spec 019);
- SC-006: an audit record within one minute of each tool call and decision.

**Constraints**:

- The gateway ends a response at 15 s, so the chat's stream does not wait for a decision (R3).
- No model text, `feedback` or personal value in any audit record.
- Five write tools, each approved once and run at most once.
- A route path carries at most two parameters.

**Scale/Scope**: one brand, tens of concurrent conversations in the pilot. A session queues 32
messages at most (ankka), and a busy session's stream is refused.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | recordings named, parity tests left, divergences declared | **Pass.** R1 read the agent, its tests, the proxy and the Playwright tests whole. The spec was corrected where it contradicted them (`feedback`, the 404, the streams). Two planning questions were settled with the user (spec, *Session 2026-11-09*, "Q (plan)"). R13 names the recordings, and *Divergences* below lists every change with what demonstrates it. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; idempotent under the caller's key | **Pass.** Amounts are `Long` minor units to the last moment, and `Summaries` renders major units with `contract.money`. No balance is kept. Every write carries the approval id as its key: A1 already has one, and R2–R4 and P1 gain one. |
| III. A brand is a project | keys carry no brand | **Pass.** Ids are the session, the player, the slug, `<slug>/<version>` and `<slug>/<day>`. Every call stays in the project. |
| IV. Commands over HTTP, facts over topics | no store read across services | **Pass.** Every tool is an HTTP call by identity. Only `platform.audit` leaves, through consumers. |
| V. Personal data marked, erasure honoured | `Personal`; erasure | **Pass.** The transcript is ankka's session memory under the player's subject, so it reads as erased after erasure (042). `Conversation` holds no personal value (data-model.md). The personal fields a brand names are read per turn and never stored. |
| VI. Scala by default | | **Pass.** |
| VII. Specs in the domain's words | `just features` passes | **Pass:** 0 findings after clarify (2026-11-09). Re-run after this plan. |
| Workflow: clarify before plan | | **Pass:** clarified 2026-10-08 (two sessions) and 2026-11-09; two planning questions settled 2026-11-09. |
| Scope: ankka gaps are ankka specs | | **Pass:** nothing blocks. Four gaps are noted for ankka (research, *For ankka*), each worked around inside this service's own components, not by reimplementing a platform feature. |

No violations, so Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-005–FR-008, FR-009, FR-010, FR-019 | `Motoko` (agent, sharded by session; `chat` stream; tools built in the handler over `context.services`; `requiresApproval(approvalMinutes)`; subject = the player) | ankka's agent owns memory, approvals, compaction and at-most-once tools | a workflow driving the model (it would re-implement 029) |
| FR-004, FR-017, FR-028, FR-029 (version pinning) | `Conversation` (event sourced, id = session) | one writer per session; its events are the audit trail and the resume's `action_result` source | a key value record (the audit would diff states, R6) |
| FR-029, FR-001, FR-023 | `AgentConfiguration` (key value, id = slug) + `ConfigVersion` (key value, id = `<slug>/<version>`, written once) | engagement's audited configuration; a conversation reads its own version | one entity holding every version (unbounded state) |
| FR-024 | `TurnWindow` (key value, id = `<slug>/<player>`), `Budget` (key value, id = `<slug>/<day>`) | they must hold across nodes; ankka has neither (R10) | instance memory (wrong with two nodes) |
| FR-027 | timer `forget-<session>` | set again on each turn and checked when it fires | a recurring sweep over a view (scans everything daily) |
| FR-028 | `AuditPublisher` (`eventsOf(Conversation)`), `ConfigAudit` (`stateOf(AgentConfiguration)`) → `platform.audit` | the shape every service here uses | — |
| FR-029 (status), operator list | `ConversationsByPlayer` (view of `Conversation`) | operators read conversations without the transcript | — |
| FR-002, FR-003 | `ChatEndpoint` (player; `sseBody` × 2) | the original's paths | — |
| FR-029, status | `ConfigEndpoint` (operator) | | |
| health | `ServiceEndpoint` | | |
| FR-021–FR-023 | guardrails: `maxInputLength`, `forbidding("other-player")`, `judged("override")`, `judged("safeguarding")`, output `other-player`, `softening` | ankka's guardrails, fail closed | prompt rules alone (the original; FR-021 asks for code) |

Pure logic with no component: `Summaries`, `Redaction`, `Mapping` (the services' answers to the
model's fields), `Rate`, `Budgeting`, `Handoff` (the marker, held back while streaming), `Softening`,
`OtherPlayer`, `ConfigRules`, `Frames` (SSE events), `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/017-support-agent/
├── spec.md
├── plan.md            # this file
├── research.md        # Phase 0, R1–R14
├── data-model.md      # Phase 1
├── quickstart.md      # Phase 1
├── contracts/
│   ├── routes.md      # the service's routes
│   ├── neighbours.md  # every call, and amendments A1, R1–R4, P1–P3
│   ├── tools.md       # prompt, tools, summaries, softening, judge questions
│   └── audit.md       # audit_record mapping
└── tasks.md           # /speckit-tasks
```

### Source Code (repository root)

```text
services/support-agent/
├── build.sbt  service.json  docker-compose.yml  README.md
└── src/
    ├── main/scala/io/eitheror/casino/support/
    │   ├── SupportService.scala  SupportRuntime.scala  Settings.scala  SupportCodecs.scala  Json.scala
    │   ├── agent/        Motoko.scala  Tools.scala  Guardrails.scala  Context.scala
    │   ├── domain/       Summaries  Redaction  Mapping  Rate  Budgeting  Handoff  Softening  OtherPlayer  ConfigRules
    │   ├── conversation/ Conversation.scala  Model.scala
    │   ├── config/       AgentConfiguration.scala  ConfigVersion.scala  Model.scala
    │   ├── limits/       TurnWindow.scala  Budget.scala
    │   ├── clients/      Neighbour.scala  Wallet  Payouts  Players  Rewards  Rg   (trait, HTTP, fake)
    │   ├── timers/       Forget.scala
    │   ├── reads/        ConversationsByPlayer.scala
    │   ├── facts/        AuditPublisher.scala  ConfigAudit.scala
    │   └── api/          ChatEndpoint  ConfigEndpoint  ServiceEndpoint  Frames  Ctx  Answers
    └── test/scala/io/eitheror/casino/support/
        ├── domain/  agent/  conversation/  config/  limits/        (unit)
        ├── it/      Kit  FakeNeighbours  ChatSuite  ResumeSuite  GuardrailSuite  ForgetSuite  ConfigSuite  AuditSuite  ErasureSuite
        ├── features/ SupportFeatures (binds features/support-agent/)
        └── port/    ParitySuite (replays tests/port/support-agent/)

services/payouts/      api/SupportEndpoint.scala + suite                 (A1)
services/responsible-gambling/ api/SupportEndpoint.scala, profile/RgKeys + suites (R1–R4)
services/players/      game-session end keyed, status currency, agent-profile + suites (P1–P3)
modules/support/module.json     runtime 0.16.2; image; settings env (R11)
contract/keys.md                support-agent out of "Not yet built"
Justfile                        support-agent-test / -it / -run (9020)
.github/workflows/              the service's job and filter
tests/port/support-agent/       recordings (R13)
```

## Phase 0: research

[research.md](research.md), R1–R14. Two decisions were settled with the user (R3 and R4). There are
no `NEEDS CLARIFICATION` left.

## Phase 1: design

[data-model.md](data-model.md), the contracts listed above, and [quickstart.md](quickstart.md).

### Constitution re-check after design

Unchanged. The service:

- keeps its own database;
- calls neighbours only by HTTP, by identity;
- writes money commands only with the approval id as the key;
- produces only `platform.audit`, from consumers;
- keeps the transcript only in ankka's subject-tagged memory.

The neighbours' amendments add routes and keys; they change no existing route's answer. P2 adds a
field.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| The chat's stream ends at `confirm_action` + `done`; the resume answers a stream with `action_result`, `delta`, `done`, where the original answered `{ok:true}` and kept the first stream open | R3 (settled 2026-11-09) | `ResumeSuite`; T044's recording, marked |
| `delta` streams text as produced; the original sent the whole reply as one delta | FR-003; ankka streaming | `ChatSuite`; T043's recording, marked |
| `action_result.success` may be `false`, carrying the service's refusal | FR-012 | `ResumeSuite` |
| `get_player_state` returns `status`, `verification`, `displayName`, `currency` and the brand's named fields; the original's `email`, `full_name`, `kyc_status`, `created_at` are withheld by default | FR-018 (clarified 2026-11-09) | `MappingSuite`; `reads.feature` |
| `get_session_history` returns `game_id`, not `game_name` | players serves no game name | `MappingSuite`; the original's session-history test, marked |
| `get_transaction_history` never returns `reason` | FR-018 (`Personal`) | `MappingSuite` |
| Withdrawal summary names a forfeit when the hold preview shows one | FR-013 | `SummariesSuite`; `actions.feature` |
| `set_deposit_limit`'s period and every tool's currency are validated before an approval is requested | FR-011 | `ToolsSuite` |
| 403 `session_not_yours`, 404 `no_pending_action`, 409 `already_decided`, 409 `conversation_ended`, 429 `too_many_turns`, 503 `agent_unavailable`; the original had the 409 "Session already active", 404, 409, 422 and 403 only | FR-004, FR-017, FR-024, FR-001 | `ChatSuite`, `ResumeSuite` |
| The approval expires after the brand's time (default 15 min); the original waited forever | FR-016 | `ResumeSuite` with the moved clock |
| The player id from the realm token; no hand-off token, no `/healthz`, no `/mcp/manifest` | spec, *Changes*; ankka's health | `ChatSuite` |
| Guardrails, rate, budget, retention, audit, hand-off marker | FR-021–FR-028 | their suites; `guardrails.feature`, `protections.feature`, `conversations.feature`, `operators.feature` |
| The prompt gains two lines (the hand-off marker; context given) | R12, FR-007 | `PromptSuite` (the original's text otherwise byte for byte) |

## What the next command needs to know

**Build order:**

1. **Neighbours, each with its suites, its spec and contract rows amended, and its features bound
   where a scenario names it:**
   - payouts A1, as `just service payouts test` / `it`;
   - responsible-gambling R1–R4, including `RgKeys`;
   - players P1–P3.

   Use `CASINO_VERSION=0.2.0-017-SNAPSHOT` throughout.
2. **The service skeleton:**
   - `ankka init`, `build.sbt` (0.16.2, `VersionScheme.Always` as engagement's), `service.json`,
     `docker-compose.yml` (Postgres 5444), `Settings`, `SupportCodecs`;
   - the `just` recipes (port 9020), the CI job and filter;
   - `modules/support/module.json` on runtime 0.16.2 with the settings env (`AGENT_COMPACTION_*`,
     `AGENT_MODEL_PROVIDER`), and `ModuleManifestSuite`'s built set gains `support-agent`;
   - `contract/keys.md`'s row moved.
3. **The pure packages, test first:** `Summaries` (the original's write and RG summary tests),
   `Mapping` (the read-tool tests), `Redaction`, `Rate`, `Budgeting`, `Handoff`, `Softening`,
   `OtherPlayer`, `ConfigRules`, `Frames`.
4. **The entities, with their kits:** `Conversation`, `AgentConfiguration` with `ConfigVersion`,
   `TurnWindow`, `Budget`. Cover every command in data-model.md.
5. **The clients** (trait, HTTP and fake) for the five neighbours, with a 5 s timeout and one retry.
6. **`Motoko`:** the tools, the guardrails and the context load, on `TestModelProvider` and
   `TestJudgmentProvider`.
7. **`ChatEndpoint`:** both streams, the checks in contracts/routes.md's order, the hand-off marker,
   and the safeguarding reply.
8. **The rest:** the forget timer, `ConversationsByPlayer`, the audit consumers, `ConfigEndpoint`
   and status, and the erasure suite (memory reads empty after the subject's erasure).
9. **Features and parity:** the features suite binding all six files, then `ParitySuite` once the
   recordings exist (R13 needs the user: the original on kind with a scripted model).
10. **Docs:** the README, `keys.md`, and the spec 019 runbook note covering the token forwarding and
    the widget reading the resume's stream.

**Known gaps:**

- The module cannot deploy beside a `core` on 0.13 until `core` reaches 0.15 (as engagement).
- A real-money brand stays unapplicable through the vendor until ankka reports `erasure` (042 in
  the installation).
- SC-002 is measured on the scripted provider; the real model's latency is the provider's.
- SC-003's T043 and T044 run against the rebuild only once spec 019 changes the widget to read the
  resume's stream (R3); until then `ParitySuite` holds the frames.
