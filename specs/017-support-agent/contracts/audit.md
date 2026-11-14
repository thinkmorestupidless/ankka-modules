# Audit records: support-agent → `platform.audit`

Each record is a `contract.facts.AuditRecord` (`audit_record`), published by `AuditPublisher`, an
`AuditConsumer` over `ChangeSource.eventsOf(Conversation)`, and by `ConfigAudit` over
`ChangeSource.stateOf(AgentConfiguration)`, as engagement's. The key is `subjectKind/subjectId`, and
`ce-id` is `recordId`. The topic is core's; spec 018 reads it. The module grants nothing new, since
core already grants produce to its project's services.

| Event | `action` | actorKind / actorId | approverId | subject | outcome | reasonCode | changes (field names and redacted values) |
|---|---|---|---|---|---|---|---|
| `ToolCalled` | `support.tool.<tool>` | `service` / `support-agent` | the approval id, for a write | `player` / playerId | `ok \| refused \| unavailable \| timed_out` | the service's refusal code | the arguments (amounts, currency, period, until, game session id) |
| `ApprovalRequested` | `support.approval.requested` | `service` / `support-agent` | — | `player` / playerId | `awaiting` | — | `tool`, `expiresAt` |
| `Decided` | `support.approval.decided` | `player` / playerId, or `service` / `ankka` when it expired | — | `player` / playerId | `approved \| refused \| expired` | — | `tool`, `approvalId` |
| `GuardrailRejected` | `support.guardrail.rejected` | `service` / `support-agent` | — | `player` / playerId | `refused` | the guardrail's name | `side`, `retried` |
| `HandedOff` | `support.handoff` | `service` / `support-agent` | — | `player` / playerId | `ok` | — | — |
| configuration version | `support.config.changed` | `operator` / the operator's id | — | `brand` / slug | `ok` | — | the changed field names, with values only for `enabled`, `model`, the limits and `personalFields` (never the prompt or message text) |

The remaining fields:

- `recordId`: `support/<session>/<event seq>`, or `support-config/<slug>/<version>`;
- `correlationId`: the session id;
- `causationId`: the approval id where there is one, else the record id;
- `recordClass`: `system` for the agent's records, `operator` for a configuration version, and
  `security` for a guardrail rejection and for a `personalFields` change.

A record carries no message text, no model output, no `feedback`, and no personal value (FR-028,
SC-005).
