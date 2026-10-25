# Research: ingest

Phase 0 of [plan.md](plan.md). The original is `eitheror-platform` at `659c2d6b` (the commit spec 004's
recordings were made at); `CashierRoutes.scala` and `MerchantServiceClient.scala` are
`substrate/merchant-service/src/main/scala/io/eitheror/merchant/`, and `route.ts` is
`casino-player-frontend-next/src/app/api/cashier/[...path]/route.ts`. ankka facts are from the `v0.11.0`
sources this repository pins (`services/*/build.sbt`); where the 0.12 docs the ankka MCP serves differ,
the sources won.

## R1. What the original serves, and who really calls it

`CashierRoutes.route` (lines 600-668) serves, with CORS headers on everything:

| Route | Credential | Original's refusals |
|---|---|---|
| `OPTIONS /v1/cashier/**`, `OPTIONS /v1/payments/{id}[/capture\|/fail]` | none | `200`, `Access-Control-Allow-Origin: *` unless `CASHIER_ALLOWED_ORIGINS` is set, `Allow-Headers: Content-Type, merchantKey` (539-558) |
| `POST /v1/cashier/sessions` | `merchantKey` header, required | missing header: Pekko's `400` text; `not_found`: `401 INVALID_MERCHANT_KEY`; `MISSING_ROUTING_CONTEXT`, `NO_ROUTING_TREE`, `NO_ROUTING_MATCH`: `400`; anything else `500` with the message |
| `POST /v1/cashier/sessions/{token}/submit` | the token | `404 SESSION_NOT_FOUND`; `400 PAYMENT_REJECTED` + `message`; else `500` |
| `GET /v1/payments/{id}` | none | `404 PAYMENT_NOT_FOUND`; else `500` |

`handleCreateSession` (1492-1540) defaults what the caller left out: `amountCents` to `0`, `currency` to
`USD`, `transactionId` to a fresh UUID, `redirectUrl` to `""`; a missing `country` or `customerId` is
`MISSING_ROUTING_CONTEXT`. The customer's address is the first `X-Forwarded-For` entry, else the
connection's (609-614). `validateKey` (`MerchantServiceClient.scala:1169-1186`) hashes the key with
SHA-256 and scans every sub-brand's key list for an active match.

**Who holds the key.** Not the browser. The brand's frontend reaches these routes through its own
server: `route.ts` is a Next.js route that adds `merchantKey` from the server's `CASINO_MERCHANT_KEY`,
`Authorization: Bearer <eo_session>` and `X-Forwarded-For` to every call, and maps `payments*` to
`/v1/payments/...` and everything else to `/v1/cashier/...`. The browser's requests are same-origin to
the frontend, so no browser ever preflights the cashier routes with the key. An outside sub-brand calls
from its server. This decides R10 and spec correction 1.

**Decision**: the four public routes keep the original's paths, field names and answer shapes
verbatim, since frontends change only base URLs (constitution *Scope*). The only public body fields
are the original's: `amountCents`, `currency`, `customerId`, `country`, `redirectUrl`, `transactionId`,
`customer*`. [contracts/routes.md](contracts/routes.md) lists them.

## R2. Where each check happens, in order

A create passes these in order; the first that fails answers, and every outcome is a request record
(FR-016):

1. **Body bound** (FR-021): more than `BODY_BOUND` bytes refused `413 body_too_large`; not a JSON
   object `400 not_json`. No sub-brand yet: recorded under none.
2. **Key** (FR-002, FR-005): header `merchantKey`, any case. Missing: `401 UNAUTHENTICATED`. Digest
   not an active key: `401 INVALID_MERCHANT_KEY`. The record says `key_missing`, `key_unknown` or
   `key_disabled`; the answer does not. A disabled key's row names its sub-brand, so that record and
   its audit record are under the sub-brand; the other two are under none. Audit record (FR-019).
3. **Rate** (FR-015): `RateWindow(subBrand).take` → served, or `429 TOO_MANY_REQUESTS` with
   `Retry-After` and `retryAfterSeconds`. Audit record.
4. **Fields** (FR-007): `400 INVALID_REQUEST` naming `field`. Checked here and not earlier because a
   malformed body under a valid key is still a request the sub-brand sent and is rated as one.
5. **Request id** (FR-010): `Request(subBrand/requestId).begin(digest)`; see R5.
6. **Forward** (FR-011 to FR-014): see R6.

Submit, abandon and read have steps 1, then ownership instead of key (R7), then 3, then 6.

**Alternative rejected**: fields before rate. A sub-brand flooding malformed creates would then never
be slowed.

## R3. The key cache: an index keyed by key id, read by digest (a view at planning, an entity at implementation)

**Decision**:

- `Key`, a key value entity keyed by `keyId`: `digest`, `subBrand`, `active`, `since`.
- `KeyFacts`, a consumer of `payments.merchants` from `StartFrom.Earliest`: `merchant_key_issued` →
  `Key.issue(digest, subBrand)`; `merchant_key_deactivated` → `Key.deactivate`.
- `KeyRefresh` at start: `GET /v1/cashier/keys` on `merchant` (004 `contracts/commands.md`), one
  `issue` per key; a failure logs and does not stop the service, since the consumer rebuilds the index
  from the topic.
- `KeysView`, a view over `stateOf(Key)` with the declared query `byDigest(digest)`, is what the
  request path reads. The row found, its digest is compared with `MessageDigest.isEqual` (FR-002's
  constant time, cheap and literal).

**Rationale**: `MerchantKeyDeactivated(keyId, subBrand, occurredAt)` (`contract/.../facts/Others.scala:239`)
carries no digest, so the index must be reachable by key id; the request path needs it by digest. One
entity keyed by key id plus one view query gives both, and the request path never spins up an entity
actor for a key nobody holds: an unknown digest is a view read that finds no row, not an empty entity
kept in memory for two minutes. `/keys` answers `keys[{digest, keyId, subBrandId}]`, the same three
fields the fact carries.

**Changed at implementation (2026-10-22, T028)**: the view did not meet SC-003. ankka 0.11 declares no
index on a view's JSON payload, so `byDigest` scanned every row: with a handful of keys the key step was
p50 1.7 ms, p99 8.2 ms; at 10,000 keys p50 5.2 ms, p99 9–13 ms, growing with the keys. `KeysView` was
replaced by `KeyIndex`, a key value entity keyed by the digest, written by `KeyFacts` and `KeyRefresh`
from the state `Key` replied with. Measured on the integration kit at 10,000 keys over 1,000 sub-brands:
p50 0.05 ms, p99 0.52 ms; a deactivation refused 5 ms after the fact was published (SC-004). The cost the
view avoided is now paid: an unknown digest activates an empty `KeyIndex` actor until it passivates, so a
caller spraying made-up keys holds memory for as long as it sprays (each is still one record and one audit
record, FR-019). `Key` stays keyed by key id, since the deactivation fact carries no digest. An index
declarable on a view's column would let the view return; raised as ankka finding 6.

The view lags the entity by well under FR-004's 60 s; SC-003's 5 ms is one SQL read of a table of
10,000 rows. The service has a database (every component here needs one); `ANKKA_DATABASE` is not
`none`.

**Alternatives rejected**: one entity keyed by digest (deactivation has no digest to key by, and every
unknown key makes an empty actor); one entity holding every key (a hotspot on one node, a megabyte
rewritten per issuance); an in-memory map per node (the consumer runs on one node's slice, so other
nodes would not learn of a deactivation).

## R4. Rate: a key value entity per sub-brand, a fixed one-minute window

**Decision**: `RateWindow(subBrand)`: `minute`, `count`, `limit: Option[Int]`. `take(now, default)`
resets the count when the minute changes, serves while `count < limit.getOrElse(default)`, otherwise
refuses with the seconds to the next minute. `setLimit(n)` is the operator's raise
(`PUT /v1/ingest/sub-brands/{subBrand}/rate`). The default is `RATE_LIMIT` (600).

**Rationale**: the count must be cluster-wide, and one entity per sub-brand is the natural serialisation:
a flooding sub-brand queues on its own actor and touches no other's (FR-015's last sentence, SC-005). A
fixed window answers "the time to wait" exactly. The cost is one durable-state write per served request
on top of the request record; at the default rate that is 10 writes a second per flooding sub-brand.

**Alternatives rejected**: a token bucket in memory per node (the limit would be per node, and a
restart forgets the raise); a view counting request records (lags, and the refusal must be decided
before the record is written); the gateway (it applies no rate limit, `platform/networking.md`).

## R5. Idempotency: a key value entity per sub-brand and request id

**Decision**: `Request(<subBrand>/<requestId>)`: `digest` (SHA-256 of the body with keys sorted),
`attempt`, `state` ∈ {free, in flight, answered}, `answer` (status and body), `recordIds`.
`expireAfter(REQUEST_ID_RETENTION, 24h)`.

| `begin(digest, recordId)` finds | Does |
|---|---|
| free | in flight, `attempt + 1`; the create is forwarded |
| answered, same digest | returns the answer; `merchant` is not asked; the record says `repeated` |
| answered or in flight, other digest | `409 REQUEST_ID_REUSED` naming `transactionId` |
| in flight, same digest | the handler polls `read` every 200 ms until answered or unanswered, within its own bound; then as answered, or as R6's try-again |

- `answer(attempt, status, body)`: accepted only for the current attempt in flight → answered.
- `unanswered(attempt)`: in flight → free, and the next `begin` is a fresh attempt (FR-013).
- A late `answer` for an attempt that is no longer the one in flight is refused by the entity, and the
  caller records it as a lost answer (R6). The attempt number is what keeps a late answer from becoming
  the request id's answer (FR-014).
- **A refusal from `merchant` leaves the request id free.** A `NO_ROUTING_TREE` under request id `t-1`
  followed by the operator deploying a tree and the sub-brand retrying `t-1` is served. Idempotency
  protects against doing a thing twice; a refusal did nothing. The feature says "returns the first
  session", not "the first refusal". Spec FR-010 is read that way (correction 3) and the decision is
  listed for Sheldon.

A create with no `transactionId` is given a UUID (the original's default) and runs the same path; the
id is returned, so the sub-brand can repeat it if it wants to.

**Alternatives rejected**: `409 in_progress` on a concurrent same-body send (the feature says the first
session is returned, and SC-002 counts concurrent sends); leaving idempotency to `merchant` (004's
contract says `ingest` made the create idempotent, and `merchant` creates a second session as the
original does).

## R6. Forwarding, the answer bound and the lost answer

**Decision**: the call is `services(MERCHANT_SERVICE).request(...)` as `psp-gateway`'s `MerchantClient`
makes it, raced against `ANSWER_BOUND` (10 s):

- answered within the bound: `Request.answer`, the ownership written (R7), the record `served`, the
  answer returned with `merchant`'s status and body.
- `merchant` refused (4xx): the refusal returned unchanged, the request id left free (R5), the record
  `refused` with the code.
- not answered within the bound, or `ServiceUnresolvable`/`ServiceCallFailed` at once: `503 TRY_AGAIN`
  with `Retry-After`, `Request.unanswered`, the record `unanswered`. Nothing is queued.
- the call's future is kept after the caller was answered. A success that arrives later, before the
  client's own timeout, is `RequestRecord.lostAnswer(ticket or paymentId, answeredAt)` on the unanswered
  attempt's record: outcome `lost_answer`, `seen = false`. The ownership is not written for it: nobody
  legitimate holds that ticket.

**Rationale**: the service client has no per-call timeout and no retries (`build/calling-services.md`);
its one timeout, `ANKKA_SERVICE_CLIENT_TIMEOUT`, stays at 30 s and is the window in which a late answer
can still be caught. The caller's bound is the handler's own race, so the handler answers at 10 s,
inside the gateway's 15 s bound on responses, and still hears `merchant`. `merchant`'s submit bounds
itself at 2 s and answers `503 OUTCOME_PENDING` past it (004 `commands.md`), so a lost submit answer is
rare; a lost create answer is the common case.

**Alternatives rejected**: `ANKKA_SERVICE_CLIENT_TIMEOUT=10s` (the connection is dropped at the bound
and the late answer is never seen, so FR-014 cannot be met); a retry of the forwarded create (the spec
forbids a queue, and `merchant`'s create is not idempotent).

## R7. Who owns a ticket and a payment

**Decision**: `Ownership`, a key value entity with two id shapes, `ticket/<ticket>` and
`payment/<paymentId>`: `subBrand`, `requestId`, `recordId`, `ticket`, `paymentId`. Written when a
create is answered (the ticket) and when a submit is answered (the payment, and the ticket's record
gains the payment id). `expireAfter(RECORD_RETENTION, 90d)`.

- Submit and abandon by `{ticket}`: `Ownership(ticket/…)` names the sub-brand for the rate and the
  record; none → `404 SESSION_NOT_FOUND` at the edge, recorded under no sub-brand, and `merchant` is not
  asked.
- Read of `{paymentId}` (FR-009): with `merchantKey`, the key's sub-brand must be the payment's
  owner; with a `cashierTicket` header, `Ownership(ticket/…).paymentId` must be the id. Otherwise
  `404 PAYMENT_NOT_FOUND`, the same answer as for a payment that does not exist. Neither header:
  `401 UNAUTHENTICATED`.
- A second submit is forwarded; `merchant` answers the first submit's payment (004 correction 2), so
  `ingest` keeps no submit answer. FR-010's "a submit MUST be idempotent by the ticket" is met by
  `merchant`, and the feature scenario asks for no more.

**Rationale**: the brand's page reads payments with the brand's key through its proxy (R1), so the key
path serves every brand today; the ticket header is for an outside sub-brand's page that holds no key.
The ticket is "what the player's browser alone holds" (glossary), which is why it, and not the player
id, proves ownership.

**Alternative rejected**: forwarding submits under unknown tickets to `merchant` (it answers `404` too,
but a flood of made-up tickets would then reach it).

## R8. The request record and the operator's view

**Decision**: `RequestRecord(recordId)`, a key value entity, one per inbound request, written once when
the request is answered and once more if a lost answer arrives: `subBrand: Option`, `route`,
`receivedAt`, `requestId: Option`, `amount: Option[Long]`, `currency`, `country`, `outcome`,
`refusal: Option`, `ticket: Option`, `paymentId: Option`, `attempt`, `lostAnswer: Option[{answeredAt,
ticket, paymentId}]`, `seen`. No customer field, no address, no key (FR-018; SC-006).
`expireAfter(RECORD_RETENTION, 90d)`.

`RequestsView` over `stateOf(RequestRecord)` with the declared queries `bySubBrand(subBrand, from, to,
limit)` newest first, `byRequestId(subBrand, requestId)`, `lostAnswers(subBrand?)` where
`outcome = lost_answer and not seen`. SC-007's 2 s is the view's lag. At implementation the views'
poll (`pekko.persistence.r2dbc.refresh-interval`, 3 s by default) missed it, and `ingest` sets 1 s in its
`application.conf`; a request is then found by request id 558 ms after it was served (`OperatorSuite`).

The record holds the cashier ticket `merchant` returned, because the original's session has no id but
its token. An operator who reads a lost answer sees a ticket a player's browser could use for 30 minutes.
Listed for Sheldon.

**Alternative rejected**: a view sourced from `Request` (misses refusals before a key, and submits and
reads, which have no request id).

## R9. Audit records from a consumer of the records

**Decision**: `AuditFacts`, a consumer over `stateOf(RequestRecord)` producing to `platform.audit`: a
record whose outcome is `key_missing`, `key_unknown`, `key_disabled` or `rate` yields one `AuditRecord`
(`service = ingest`, `action = <route>`, `actorKind = sub-brand` or `unknown`, `subjectKind = request`,
`subjectId = recordId`, `outcome = refused`, `reasonCode = <outcome>`, `ce-id = recordId`); every other
change is ignored. The key never appears: the record never held it. The shape and the companion are
`merchant`'s `audit/AuditFacts.scala` (`AuditConsumer`, `AuditCompanion`); hoisting that base class into
`casino-contract` is a task-time option (finding 4).

**Rationale**: constitution IV, as every service here does it: a fact is produced from a component's
change, never written to a topic from a handler.

## R10. No preflight (spec correction 1)

ankka 0.11's `HttpEndpoint` declares `get`, `post`, `put`, `patch`, `delete` and their `*Body`
variants, and nothing for `OPTIONS` (`http/HttpEndpoint.scala:429-575`); the gateway "applies no …
header policy of its own" (`platform/networking.md`). FR-020 cannot be met as written. It also need
not be: the brand's browser never sends the key cross-origin (R1), and an outside sub-brand's server
sends no preflight. FR-020 becomes "serves no preflight", the edge case and the assumption that said
the page holds the key are corrected, and `CASHIER_ALLOWED_ORIGINS` is dropped under *Changes*. A
page that wants to call shared payments from a browser goes through its own server, as the brand's
does. If that ever changes, CORS is an ankka spec (OPTIONS at the endpoint or a header policy at the
gateway), not a workaround here.

## R11. Body bound, forwarded-for, header case

- **Body bound**: the handler reads the raw bytes (as 005's `http.Raw` does) and refuses past
  `BODY_BOUND` (64 KiB) before parsing. Pekko's own `max-content-length` (8 MiB) stays as the outer
  guard; it is the runtime's refusal, recorded by nobody, which is why the bound is checked in the
  handler.
- **Forwarded-for**: Envoy appends `X-Forwarded-For` on the way in; `ingest` reads
  `request.header("X-Forwarded-For")`, takes the first entry, and sends it to `merchant` as `clientIp`
  in the command body (004 `commands.md`). It cannot go as a header: the service client strips
  `Forwarded` and `X-Forwarded-*` (`sdk/ServiceClient.scala:18`). Without the header, the connection's
  address is the gateway's, so `clientIp` is empty rather than wrong. *Verify at task time* that the
  gateway forwards the header to the service; fallback: empty, as the original behind a proxy that did
  not set it. The handler's side is tested (`CashierSuite`: the first entry reaches `merchant` as
  `clientIp`). *Verified 2026-10-25* (T052, kind on ankka 0.13.0): a create through the gateway with no
  header of the caller's reached T365 at psp-stub as `"ip":"192.168.97.1"`, the caller as Envoy saw it,
  so the gateway sets the header and the service reads it. The run found that `merchant` dropped it:
  the original takes the body's `customerIp`, else the forwarded address (`CashierRoutes.scala:1573`),
  and 004's port read only `customerIp`. Fixed in `merchant`'s `CustomerFields.from`, held by
  `DepositWorkflowSuite`. **Open (a decision)**: Envoy appends, so the first entry is whatever the
  caller sent. A create sending `X-Forwarded-For: 203.0.113.7` reached T365 as `203.0.113.7`. The
  original did the same, and the brand's proxy sits in front, so the first entry is the proxy's claim
  about the player. Taking the entry the installation's gateway appended (the last) would trust only
  the gateway, at the cost of the player's address behind the brand's proxy. Recommended: keep the first
  entry, since the brand's proxy is the only caller the edge serves, and have the proxy set the header
  rather than append to the player's.
- **Header case**: `request.header(name)` is expected to match HTTP's case-insensitivity, as Pekko
  does; *verify at task time*, fallback: look the header up by lower-cased name over all headers.
  *Verified 2026-10-22*: ankka 0.11's `RequestContext.header` compares names case-insensitively, and
  `CashierSuite` serves `MerchantKey`, `merchantkey` and `MERCHANTKEY` alike; still green on 0.13.0
  (2026-10-25).
- **A bearer on a public route**: the brand's proxy sends the player's `eo_session` as a bearer. A
  public route (`Routes.public`, `allowCallers(Gateway)`) does not read it; only `Acl.Authenticate`
  does. *Verify at task time* that an invalid bearer on a public route is not refused by the runtime.
  *Verified 2026-10-22*: `SmokeSuite` sends `Authorization: Bearer not-a-token` with a valid key and is
  served `200`.

## R12. Routes, prefixes and the operator's audience

Three endpoints, no shared prefixes, at most two path parameters, each route's ACL by `withAcl`:

| Endpoint | Audience | Routes |
|---|---|---|
| `/v1/cashier` | `Routes.public` | `POST /sessions`, `POST /sessions/{ticket}/submit`, `POST /sessions/{ticket}/abandon` |
| `/v1/payments` | `Routes.public` | `GET /{paymentId}` |
| `/v1/ingest` | `Routes.paymentsOperator()`, platform scope | `GET /sub-brands/{subBrand}/requests`, `GET /lost-answers`, `POST /lost-answers/{recordId}/seen`, `GET`/`PUT /sub-brands/{subBrand}/rate` |

`/v1/payments` on `ingest` is the original's public read path, which the frontend's proxy maps
`payments*` to; it does not collide with `merchant`'s operator `/v1/payments`, since each service has
its own hostname (`ingest-payments.<base domain>`). The operator routes admit payments operators at
the installation's level, as 005's outcome routes do: the hierarchy that would place a sub-brand under
an operator's level is `merchant`'s, and `ingest` does not read another service's store (finding 2).

## R13. Settings and the manifest

| Variable | Default | Meaning |
|---|---|---|
| `MERCHANT_SERVICE` | `merchant` | the service the commands go to |
| `ANSWER_BOUND` | `10s` | how long a caller waits for `merchant` (R6) |
| `RATE_LIMIT` | `600` | requests a minute per sub-brand (R4) |
| `BODY_BOUND` | `65536` | bytes (R11) |
| `REQUEST_ID_BOUND` | `128` | characters; control characters refused |
| `REQUEST_ID_RETENTION` | `24h` | how long a request id is remembered (R5) |
| `RECORD_RETENTION` | `90d` | request records and ownerships (R7, R8) |
| `ANKKA_SERVICE_CLIENT_TIMEOUT` | `30s` | the runtime's; the lost-answer window (R6) |

`shared/payments/manifest.json`'s `ingest` entry gains these as `env`; it already has `expose: true`.
The brands tool renders `ANKKA_AUTH_ISSUERS=staff` and the issuer's variables for every payments
service, which the operator routes need. The kind overlay's `settings.env` needs nothing new.

## R14. Testing

- `KeyValueEntityTestKit` for `Key`, `RateWindow`, `Request`, `Ownership`, `RequestRecord`;
  `ConsumerTestKit` for `KeyFacts` (both facts) and `AuditFacts` (one record per refusal, none for a
  served request or a lost answer).
- `AnkkaTestKit.start` over Postgres with `InMemoryBroker` for the two topics, a `FakeMerchant` HTTP
  server registered under `localServices` (`services/psp-gateway`'s `FakeServer` pattern) that answers
  the commands from a script and can delay past the kit's short `ANSWER_BOUND`, and a settable clock
  for the rate window.
- `GherkinSuite` binding `features/payments/cashier.feature` and `requests.feature`.
- `LeakSuite` (SC-006): every customer field, a known key and a known forwarded address sent; every
  table of the service's database and the captured log searched for each value.
- `KeysSuite` (SC-003): 10,000 keys over 1,000 sub-brands issued through the consumer; `byDigest`
  timed; no call reaches `FakeMerchant`.
- `ParitySuite` over `tests/port/ingest/` (R15), fail-first.

## R15. Recordings: the Go suite's cashier steps, and a driver for the rest

The three Go-suite recordings spec 004 already made (`tests/port/merchant/TestScenario12/13/14_*.json`)
contain every public cashier step the original serves: `POST /v1/cashier/sessions`,
`POST /v1/cashier/sessions/{c}/submit`, `GET /v1/payments/{c}`. The original's `merchant-service`
served both roles, so each recorded answer is at once what `ingest` must return and what `FakeMerchant`
answers the forwarded command with. The recorder keeps only `Content-Type`, `Accept` and `X-Brand-Id`
(`tests/original/recorder/recorder.go`, `kept()`), so no recording holds a key; the replay reconstructs
`merchantKey` from the raw key in the key-generation step's recorded answer (`POST /v1/merchants/{c}/keys`),
as spec 004's replay resolves the sub-brand from it.

What the suite never reaches is driven: `tests/original/ingest-driver/` (Go, after `merchant-driver/`,
whose `Session.Open/Submit/Read` it reuses), one scenario per feature scenario the original can show,
each creating its own sub-brand and key as `setup:` steps. What the original cannot show (the rate
limit, the lost answer, the audit record, the deactivation bound) is held by the features alone.
[contracts/recording.md](contracts/recording.md) lists the scenarios and the divergences each replay
marks.

## R16. Dropped, and why nothing replaces it

Unchanged from the spec's *Changes from the original*: the Stripe webhook and its JSONata, the Cloudflow
streamlets and blueprints, `POST /v1/payments`, `/v1/payouts`, `/v1/payments/verify`. Nothing in this
plan stands in for any of them. `CASHIER_ALLOWED_ORIGINS` joins them (R10).

## Spec corrections (applied to spec.md and the features on this branch)

1. **FR-020, the preflight edge case, the key-holding assumption, *Changes*** (R10): `ingest` serves
   no preflight; the browser never holds the key; `CASHIER_ALLOWED_ORIGINS` dropped.
2. **Three scenarios added** to `features/payments/cashier.feature` so FR-004, FR-019 and FR-021 are
   traced (constitution VII): a disabled key refused within a minute; a key or rate refusal is an
   audit record naming no key; an oversized body refused before the key is checked.
3. **FR-010's "first answer"** read as the first *session*: a refused create leaves the request id
   free (R5). The feature already says "returns the first session"; the spec's wording is aligned.
4. **A missing key answers `401`** where the original's missing-header rejection is Pekko's `400`
   text; FR-005 already says unauthenticated, and the divergence is listed in plan.md.

## Findings for other specs

1. **004 / `merchant`**: `GET /v1/cashier/keys` is read once at start (R3); `MerchantKeyDeactivated`
   carrying no digest is fine because the index is keyed by key id. No change asked.
2. **004 / `merchant`**: an operator's level over a sub-brand is `merchant`'s knowledge. If a
   sub-brand-level operator must read their own sub-brand's requests, `merchant` would have to answer
   "is this operator above this sub-brand" on a route, or publish the hierarchy as facts. Not needed
   for 006.
3. **Frontend (out of scope, base URLs only)**: `CASINO_MERCHANT_KEY` stays on the frontend's server;
   its cashier base URL becomes `ingest`'s hostname, and its `payments*` mapping lands on
   `GET /v1/payments/{id}` here. No code change.
4. **Contract**: `AuditConsumer`/`AuditCompanion` are written in `merchant` and would be copied here
   (R9); they belong in `casino-contract`. *Closed by T003*: hoisted into `contract.audit`; `merchant`
   and `ingest` both extend them.
5. **ankka (not blocking)**: no `OPTIONS` verb and no CORS at endpoint or gateway (R10); no per-call
   service-client timeout (R6 works around it with a race); that the gateway sets `X-Forwarded-For`
   and that `request.header` is case-insensitive are true (R11, verified 2026-10-22 and 2026-10-25)
   but unstated in the docs.
7. **004 / `merchant` (raised at T052, fixed here)**: the session's address fell back to nothing
   where the original falls back to the forwarded address (R11). `CustomerFields.from` now takes
   `clientIp` after `customerIp`; 004's `commands.md` already names `clientIp` in the command.
6. **ankka (not blocking, raised at implementation)**: a view cannot declare an index on a column of
   its payload, so a declared query by a field is a scan. `KeysView.byDigest` missed SC-003 for it and
   was replaced by an entity keyed by the digest (R3); a declarable index would let a view serve a
   point lookup, and spare the empty actor an unknown key now starts.

## Open for Sheldon

- A refused create leaves its request id free for a corrected retry (R5). The alternative is the
  letter of FR-010: the refusal is the id's answer for 24 hours.
- The request record an operator reads holds the cashier ticket, which a browser could use while the
  session lives (R8). The alternative is to show only that a session was made.
- An outside sub-brand's page without the key reads a payment with a `cashierTicket` header (R7); the
  brand's page needs nothing new.

### The message for Sheldon (drafted 2026-10-22)

> Three calls on the payments edge (`ingest`), each built one way with the other ready if you prefer it:
>
> 1. **A refused create frees its request id.** If merchant refuses a create (no routing tree, no match),
>    the sub-brand can fix it and retry under the same `transactionId`. The other way: the refusal is that
>    id's answer for 24 hours, and a fix needs a new id.
> 2. **Operators see the cashier ticket.** The request list shows each session's ticket, which a browser
>    could use while the session is open. The other way: show only that a session was made.
> 3. **An outside sub-brand's page reads a payment with the ticket.** A page without the merchant key sends
>    `cashierTicket`; a brand's own page changes nothing. The other way: reads by key only, so an outside
>    page goes through its own server.

## For ankka (finding 5 and 6, drafted 2026-10-22, checked against 0.13.0 on 2026-10-25)

All still open in 0.13.0:

- No `OPTIONS` verb and no CORS at endpoint or gateway (R10).
- No per-call service-client timeout: `ingest` races the call against its own bound (R6). 0.13's
  `calling-services.md` still says "there is no per-call timeout".
- The gateway appends `X-Forwarded-For` (verified on kind, R11) and `request.header` is
  case-insensitive (R11), but neither is documented.
- No index declarable on a view's payload: a declared query by a field scans every row (finding 6, R3).
  0.13's `views.md` advises a Postgres expression index made by hand, which a deployed service cannot.
- The local console's entity read (`/observability/query/{component}/{id}/{method}`, and the MCP
  `query_local_entity`) takes no id containing `/`, so `Request` and `RateWindow` cannot be read
  locally (T051).
