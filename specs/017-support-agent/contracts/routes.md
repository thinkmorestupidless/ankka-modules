# Routes: support-agent

These are served under the brand's project. `<slug>` is `CASINO_BRAND`, and a path `{brand}` that
is not the slug answers 404 `brand_not_found`. Route classes come from `contract.access.Routes`. In
ankka 0.16 no two endpoints share a prefix, so each prefix below is one endpoint.

## Player: prefix `/v1/casino/agent/<slug>` (`ChatEndpoint`)

ACL `Routes.player(slug)`. The player id is `Routes.playerId(principal)`, never a path or a body
value.

### `POST /run/chat_with_player`: `sseBody`

Body `{message: string, session_id: string}`.

**Before the stream opens, in this order:**

| Check | Answer |
|---|---|
| a missing or empty field | 400 `missing_fields` |
| the agent is off (`enabled` false) | 503 `agent_unavailable` `{message}` with the unavailable message (FR-001) |
| the session is another player's | 403 `session_not_yours` (FR-004) |
| the session awaits approval | 409 `Session already active` (the original's text) |
| the conversation has ended | 409 `conversation_ended` `{message}` with the closing message (FR-017) |
| the turn rate is exceeded | 429 `too_many_turns` `{retryAfter}` (FR-024) |
| the budget is spent | 200, and the stream carries the unavailable message as one `delta`, then `done` |

**The stream** (`text/event-stream`; each `data` is JSON). Frame names and fields are the
original's:

| Event | Data | When |
|---|---|---|
| `delta` | `{token}` | each text part, with the hand-off marker replaced (R12) |
| `confirm_action` | `{action_name, summary, session_id}` | `AgentPart.AwaitingApproval`; `summary` from `Summaries` |
| `done` | `{}` | the turn answered, or awaits approval |
| `error` | `{message}` | a guardrail refused (`message` the refusal reply), the judge or model was unavailable (the unavailable message), or the turn failed |

A safeguarding refusal is `delta` with the safeguarding message and helpline, then `done`, not
`error` (FR-023).

### `POST /resume/{session}`: `sseBody`

Body `{action: "approve" | "reject", feedback?: string}`.

**Before the stream opens:**

| Check | Answer |
|---|---|
| another action | 422 `invalid_action` |
| the session is another player's | 403 `session_not_yours` |
| nothing awaits | 404 `no_pending_action` |
| already decided (ankka `Conflict`) | 409 `already_decided` |

**The stream** (research R3):

| Event | Data | When |
|---|---|---|
| `action_result` | `{action_name, success, detail}` | approved: from `ToolCalled`'s outcome; `success` false carries the service's refusal as `detail`. Rejected: no `action_result`. |
| `delta` | `{token}` | the model's reply |
| `done` | `{}` | |
| `error` | `{message}` | as above |

`decide` is called with `Decision.approved(id, by = "player:<id>")` or
`Decision.refused(id, by, note = feedback)`. The player's `feedback` is passed to ankka, which
records it in the encrypted memory, and is never put in an audit record.

## Operator: prefix `/v1/casino/agent-config/<slug>` (`ConfigEndpoint`)

ACL `Routes.operatorHolding(slug, "brand_admin", "resource_admin", "platform_admin")`. Changing the prompt or the
fields is the brand admin's.

| Route | Answer |
|---|---|
| `GET /` | 200 `{version, config: ConfigBody, recentChanges}` |
| `PUT /` | Body `ConfigBody` (all fields; missing fields keep their values). 200 `{version}`. Refusals: 400 `invalid_config` naming the field, 400 `unknown_field` naming it, 422 `safeguarding_not_configured`. |
| `GET /versions/{version}` | 200 `ConfigBody`, or 404 |
| `GET /status` | 200 `{enabled, realMoney, safeguarding: on\|off, judged: on\|off, budget: {used, limit, spent}, conversations: {open, awaiting, lastDay}}` (FR-024, the judge-off assumption) |
| `GET /conversations?player=&offset=` | 200 `{conversations: [ConversationsByPlayer row]}`, 50 per page |

## Service: prefix `/v1/casino/agent-service/<slug>` (`ServiceEndpoint`)

| Route | ACL | Answer |
|---|---|---|
| `GET /health` | `Routes.service()` | 200 `{status: "ok"}` |

The original's `/healthz` and `/mcp/manifest` are not carried over. ankka serves health, and the
manifest belonged to mimir's hand-off, which is gone (spec, *Changes*).

## Calls this service makes

[neighbours.md](neighbours.md) lists every call, its route and its key.
