# Data model: support-agent

Every stored type has a codec in `SupportCodecs` with a manifest named in the type's row, as
`WalletCodecs` does. Ids are strings, times are `Instant`, and money is `Long` minor units with its
currency. `Personal` marks a field spec 001 FR-023 names personal.

## Session memory (ankka's, not ours)

`forAgent(SessionId(session), subject = Some(DataSubject("player/<playerId>")))`.

- **What it holds**: the transcript, the approval requests and their decisions, and token usage.
- **Encryption**: under the player's key. After erasure it reads empty, and new turns record
  nothing (ankka 042).
- **Writes**: the service never writes it directly. It reads it with `forSessionMemory(id).history`
  for usage (R10), and clears it with `forSessionMemory(id).clear` when the forget timer fires
  (FR-027).

## Conversation — event sourced, id = session id (`support-conversation`)

The trail ankka's memory does not keep (research R6). Its events hold no message text and no
personal value.

| State field | Type | Rule |
|---|---|---|
| `playerId` | String | bound by the first `Started`; a later command for another player is refused `session_not_yours` (FR-004) |
| `configVersion` | Int | the version current at `Started`; every turn reads that version (FR-029) |
| `status` | `Open \| Awaiting(approvalId, tool) \| Ended(reason) \| Forgotten` | `Ended` after an approved exclusion, or after an approved break until it ends (FR-017) |
| `breakUntil` | Option[Instant] | set by an approved `apply_cool_off`; `Ended` lifts when it passes |
| `lastActivity` | Instant | every turn, decision and result |
| `tokensCounted` | Long | the session's usage already added to `Budget` (R10) |
| `turns` | Int | |

| Event (manifest) | Fields | Recorded when |
|---|---|---|
| `Started` (`support.conversation.started`) | playerId, configVersion, at | first turn |
| `TurnTaken` (`support.conversation.turn`) | at, tokensBefore, tokensAfter | a turn answered or awaiting |
| `ToolCalled` (`support.conversation.tool`) | callId, tool, arguments (redacted, see below), outcome `Ok \| Refused(code) \| Unavailable \| TimedOut`, detail?, at | after each tool runs |
| `ApprovalRequested` (`support.conversation.requested`) | approvalId, tool, arguments (redacted), summary, expiresAt, at | the turn ends awaiting |
| `Decided` (`support.conversation.decided`) | approvalId, approved, by `player \| ankka`, expired, at | resume, or expiry seen on the next call |
| `GuardrailRejected` (`support.conversation.rejected`) | guardrail, side `Input \| Output`, retried, at | a guardrail refused |
| `HandedOff` (`support.conversation.handed-off`) | at | the reply carried the hand-off marker (R12) |
| `Ended` (`support.conversation.ended`) | reason `SelfExcluded \| OnBreak(until)`, at | FR-017 |
| `Forgotten` (`support.conversation.forgotten`) | at | the forget timer cleared memory |

**Redacted arguments.** Tool arguments are amounts, currencies, periods, dates and session ids. The
player id is never one (FR-011). A free-text argument, of which there is none in the five tools, is
recorded by its length only. The `feedback` note is not recorded, because a player wrote it.

**Commands.**

| Command | Answer |
|---|---|
| `start` | idempotent for the same player |
| `turned` | — |
| `toolCalled` | — |
| `requested` | — |
| `decided` | refuses a second decision with `already_decided` (FR-015 at our level; ankka refuses first) |
| `rejected` | — |
| `handedOff` | — |
| `end` | — |
| `forget` | — |
| `get` | the state, for the endpoint and the forget timer |

## AgentConfiguration — key value entity, id = slug (`support-configuration`)

As engagement's `Configuration`: `version`, `current: ConfigBody`, `recentChanges` (last 16
`ConfigNote(version, by, fields, at)`). A `put` with nothing changed only replies.

## ConfigVersion — key value entity, id = `<slug>/<version>` (`support-config-version`)

Written once, before `AgentConfiguration` moves to that version, and never changed. A conversation
reads its `configVersion` here.

`ConfigBody`:

| Field | Type | Default | Rule |
|---|---|---|---|
| `enabled` | Boolean | false | refused 422 `safeguarding_not_configured` when true on a real-money brand without `safeguardingMessage` and `helpline` (FR-023) |
| `prompt` | String | the original's, [contracts/tools.md](contracts/tools.md) | non-empty, ≤ 20 000 chars |
| `handOffMessage` | String | "I can't help with that here. Our support team can, on live chat {hours}." | must name `{hours}` or carry the hours |
| `supportHours` | String | "every day, 08:00–24:00 UK time" | |
| `unavailableMessage` | String | "Support chat is busy right now. Please try live chat." | |
| `closingMessage` | String | "Your account is now excluded. This conversation is closed." | |
| `safeguardingMessage` | Option[String] | none | |
| `helpline` | Option[String] | none | |
| `safeguardingOptIn` | Boolean | false | only read on a brand that takes no real money |
| `personalFields` | Set[String] | empty | each a name players' `agent-profile` serves (spec 001 FR-023), else 400 `unknown_field` |
| `model` | String | `claude-sonnet-4-6` | |
| `maxInputChars` | Int | 2 000 | 1..20 000 |
| `turnsPerMinute` | Int | 10 | 1..120 |
| `dailyTokens` | Long | 2 000 000 | > 0 |
| `approvalMinutes` | Int | 15 | 1..1 440 |
| `retentionDays` | Int | 90 | 1..3 650 |

## TurnWindow — key value entity, id = `<slug>/<playerId>` (`support-turn-window`)

`times: Vector[Instant]`, only the last minute's. `take(at, limit)` answers `Allowed` and appends,
or answers `Refused(retryAfter)`. Pure rule `Rate.take` (FR-024).

## Budget — key value entity, id = `<slug>/<yyyy-mm-dd>` UTC (`support-budget`)

`tokens: Long`, `counted: Map[session, Long]`. `add(session, upTo)` is idempotent: it adds only
`upTo - counted(session)`. `spent(limit)` answers whether `tokens >= limit` (FR-024).

## Timers

| Name | Payload | When it fires |
|---|---|---|
| `forget-<session>` | the session id | Reads `Conversation`. Untouched for the retention: `clear` the session memory, then `forget`. Otherwise it sets itself again at `lastActivity + retention`. |

The approval's expiry is ankka's own timer (`requiresApproval(within)`).

## Views

| View | From | Rows | Serves |
|---|---|---|---|
| `ConversationsByPlayer` | `Conversation` events | `{session, playerId, status, startedAt, lastActivity, turns, handedOff, rejections}` | the operator's list (FR-029 status), and finding a player's conversations on erasure (unused: memory erases itself under 042) |

## Audit records (`platform.audit`, `audit_record`)

[contracts/audit.md](contracts/audit.md) has the field mapping. One record per `ToolCalled`,
`ApprovalRequested`, `Decided`, `GuardrailRejected` and `HandedOff`, and one per configuration
version.
