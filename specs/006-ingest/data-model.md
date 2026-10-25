# Data model: ingest

Phase 1 of [plan.md](plan.md). Five key value entities, two views, two consumers, one start-time
refresh, no event sourced entity and no workflow. Nothing here is a `Personal` value: the customer
fields pass through to `merchant` inside the forwarded command and are never written (FR-008, FR-018).
Every id is a `String`; every time an `Instant`; every amount a `Long` of minor units.

## Shared types

- `Outcome`: `served`, `repeated`, `refused`, `unanswered`, `lost_answer`, `key_missing`, `key_unknown`,
  `key_disabled`, `rate`, `body_too_large`, `not_json`, `invalid_field`, `request_id_reused`,
  `not_found`. The record's word for how a request ended.
- `Route`: `create`, `submit`, `abandon`, `read`.
- `Answer(status: Int, body: JsonNode)`: what `merchant` said, returned unchanged in meaning.
- `Digest`: lower-case SHA-256 hex, of a raw key (R3) or of a body with its keys sorted (R5).

## `Key`: key value, id = `keyId`

The cache of cashier keys (FR-002, FR-004).

| Field | Meaning |
|---|---|
| `digest` | SHA-256 hex of the raw key, from the fact or `/keys` |
| `subBrand` | the sub-brand that holds it |
| `active` | false once `merchant_key_deactivated` is read |
| `since` | the fact's `occurredAt`, or the refresh's time |

Commands: `issue(digest, subBrand, at)` (sets active; a repeat is a no-op; ignored once deactivated),
`deactivate(at)` (on an empty entity too, recording `active = false` with no digest), `read`. No
expiry: a deactivated key stays, inactive, so a late `issue` from the start refresh cannot revive it
(the refresh sends only active keys, but the order of a refresh and a fact is not fixed).

## `KeyIndex`: key value, id = the key's digest, the request path's read

`Option[{keyId, digest, subBrand, active, deactivated}]`, written by `KeyFacts` and `KeyRefresh` from
the state `Key` replied with (a key with no digest yet writes nothing). `set` never makes a deactivated
row active again, so a refresh's late write cannot undo a deactivation that landed first. One point read
per request; the handler compares the row's digest with the computed one in constant time and admits
only `active`. Replaced `KeysView` at implementation (research R3, *Changed at implementation*).

## `RateWindow`: key value, id = `subBrand`

The rate limit (FR-015).

| Field | Meaning |
|---|---|
| `minute` | the epoch minute the count belongs to |
| `count` | requests served in that minute |
| `limit` | the operator's raise, if any; otherwise `RATE_LIMIT` applies |

Commands: `take(now, default)` → `Served` or `Refused(waitSeconds)`; `setLimit(perMinute)`; `read`.
Every public route takes one; a request refused before its sub-brand is known takes none.

## `Request`: key value, id = `<subBrand>/<requestId>`

The sub-brand's request id as an idempotency key (FR-010, FR-013, FR-014). `expireAfter(REQUEST_ID_RETENTION)`.

| Field | Meaning |
|---|---|
| `digest` | of the first create's body under this id |
| `attempt` | how many times a create under this id has been forwarded |
| `state` | `free`, `inFlight`, `answered` |
| `answer` | the accepted create's `Answer`, once answered |
| `recordIds` | the request records of each attempt |

| Command | From | To |
|---|---|---|
| `begin(digest, recordId)` | free | inFlight, `attempt + 1`, reply `Forward(attempt)` |
| | answered, same digest | unchanged, reply `Answered(answer)` |
| | inFlight, same digest | unchanged, reply `InFlight` (the handler polls `read`) |
| | any, other digest | unchanged, reply `Mismatch` |
| `answer(attempt, answer)` | inFlight, same attempt | answered |
| | otherwise | unchanged, reply `Late` (the caller records a lost answer) |
| `unanswered(attempt)` | inFlight, same attempt | free |
| `refused(attempt)` | inFlight, same attempt | free (R5: a refusal is not the id's answer) |

Invariant: at most one attempt is in flight; an `answer` is accepted only from it.

## `Ownership`: key value, id = `ticket/<ticket>` or `payment/<paymentId>`

Which sub-brand a ticket and a payment belong to (FR-009, and the sub-brand of a submit or abandon).
`expireAfter(RECORD_RETENTION)`.

| Field | Meaning |
|---|---|
| `subBrand` | the creating sub-brand |
| `requestId` | the create's |
| `recordId` | the create's record |
| `ticket` | the cashier ticket |
| `paymentId` | set when the submit is answered |

Commands: `set(...)` (written for the ticket at create, for the payment at submit; the ticket's row
gains `paymentId` then), `read`.

## `RequestRecord`: key value, id = `recordId` (a UUID minted when the request arrives)

One inbound request (FR-016 to FR-018). `expireAfter(RECORD_RETENTION)`.

| Field | Meaning |
|---|---|
| `subBrand` | none when no sub-brand is known: a body refused before the key, no key, an unknown key, an unknown ticket. A disabled key's row names its sub-brand, so a `key_disabled` record is under it (FR-019's "where known") |
| `route` | `Route` |
| `receivedAt` | |
| `requestId` | the create's `transactionId`, given or minted; none for other routes |
| `amount`, `currency`, `country` | from the create's body, when present |
| `outcome` | `Outcome` |
| `refusal` | `merchant`'s code, or the field refused, when `refused` or `invalid_field` |
| `ticket` | the cashier ticket `merchant` returned, or the one submitted |
| `paymentId` | the payment `merchant` returned, or the one read |
| `attempt` | the `Request` attempt this record belongs to |
| `lostAnswer` | `{answeredAt, ticket?, paymentId?}` when `merchant` answered after the bound |
| `seen` | the operator's mark on a lost answer |

Commands: `write(...)` (once, when the request is answered), `lostAnswer(answeredAt, ticket, paymentId)`
(outcome `unanswered` → `lost_answer`), `markSeen`, `read`. Never: a customer field, a network address,
a key.

## `RequestsView`: view over `stateOf(RequestRecord)`, the operator's reads

Declared queries:

- `bySubBrand(subBrand, from, to, limit)` → records newest first (FR-017).
- `byRequestId(subBrand, requestId)` → every attempt under the id, oldest first (FR-017, SC-007).
- `lostAnswers(subBrand?)` → `outcome = lost_answer and not seen` (FR-014).

## `KeyFacts`: consumer of `payments.merchants` from `StartFrom.Earliest`

`merchant_key_issued(keyId, subBrand, digest, occurredAt)` → `Key(keyId).issue`;
`merchant_key_deactivated(keyId, subBrand, occurredAt)` → `Key(keyId).deactivate`. At least once;
both commands are idempotent.

## `KeyRefresh`: at start

`GET /v1/cashier/keys` on `merchant`; `Key(keyId).issue(digest, subBrandId, now)` per key. A failure is
logged and the service starts: the consumer rebuilds the index from the topic (FR-004's "only at start").

## `AuditFacts`: consumer of `stateOf(RequestRecord)`, producing to `platform.audit`

A record whose outcome is `key_missing`, `key_unknown`, `key_disabled` or `rate`, on its first change,
→ one `AuditRecord` with `ce-id = recordId`, the actor the record's sub-brand where it has one
(`key_disabled`, `rate`) and `unknown` otherwise (FR-019; R9). Every other change: ignored.

## Settings (`Settings.scala`, from the environment; R13)

`MERCHANT_SERVICE`, `ANSWER_BOUND`, `RATE_LIMIT`, `BODY_BOUND`, `REQUEST_ID_BOUND`,
`REQUEST_ID_RETENTION`, `RECORD_RETENTION`; each with its default, each refused by name when it cannot
be read, as `psp-gateway`'s `Settings.fromEnvironment` does.

## Pure logic (no component)

- `Fields.check(body)`: FR-007's validation, naming the first field that fails.
- `Digests`: SHA-256 hex of a key; of a body with keys sorted.
- `Shape.toCommand(subBrand, requestId, body, clientIp)`: the public create body into `merchant`'s
  command (004 `contracts/commands.md`).
- `Bound.race(future, bound)`: the answer bound of R6.
