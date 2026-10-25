# Feature Specification: Ingest — The Merchant-Facing Edge of Shared Payments

**Feature Branch**: `006-ingest`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The `ingest` service of the shared `payments` project: every request that
reaches shared payments from outside the installation under a merchant key — a brand's cashier or an
external merchant creating a cashier session, submitting it, reading a payment — is verified, shaped,
made idempotent, rate-limited and recorded here, then handed to `merchant` as a command. The Stripe
webhook and the Cloudflow streamlets the plan named are examined against the original and dropped
where they are stubs."

## Context

The plan gave `ingest` two endpoints to replace the Cloudflow pipelines: a Stripe webhook verifying
`Stripe-Signature` and applying a JSONata shape, and a merchant API webhook validating the merchant
key against `merchant` and checking required fields. Reading the original (constitution I) changes
that picture:

- **The Stripe webhook is a stub.** `pipelines/payments-stripe-ingest` exists as a blueprint, a
  JSONata file and two unit tests, but the deployed `pipeline-runtime` pods serve nginx
  (`tests/e2e/GAPS.md`: "no ingest routes exist"), the Stripe ingest handler "was deleted as
  dead/unwired code" in the NATS-to-Kafka migration (#179, recorded in
  `scenario1_l1_l2_flow_test.go`), the one end-to-end scenario that posts a Stripe webhook is
  `t.Skip`, and no payment provider adapter for Stripe exists (`services/psp-gateway` has none;
  `payments/frontend`'s registry holds only a documentation link). Nothing in production receives a
  Stripe event. It is dropped, with the JSONata shape, under *Changes from the original*.
- **The merchant API is real, and it is not a webhook.** It is the set of routes a merchant calls
  with a merchant key in the `merchantKey` header: create a cashier session, submit it, read a payment.
  Today they are served by `merchant-service`'s `CashierRoutes` directly; the Cloudflow `payments-api`
  pipeline that would have fronted them (`http-in → merchant-key-verify → json-parse → validate →
  substrate-sink`) is not in the casino chart (`starter-packs/casino/chart/values.yaml` configures
  `casinoApi` only, with no image) and carries no traffic. The end-to-end suite calls the merchant
  routes at `MerchantURL`, never at `IngestURL` (`tests/e2e/internal/setup/setup.go`).
- **Who the merchants are.** The holding → company → sub-brand hierarchy of shared payments
  (plan, *Shared project: payments*). Every eitheror brand is a sub-brand with a merchant key of its
  own: `scenario14_casino_deposit_e2e_test.go` creates the player's cashier session with the brand's
  raw key, and `CashierRoutes` answers browsers directly (its CORS allows the `merchantKey` header,
  `CASHIER_ALLOWED_ORIGINS`). An external merchant of the payments vertical uses the same routes with
  its own key. So the merchant API is a **public** route class (001 FR-020) whose credential is the
  merchant key, and it is how a brand starts a deposit: 001 FR-016 grants nothing from a brand into
  `payments`, by design — a brand is a merchant.
- **Windowing, joining and enrichment are not carried over.** `Windower`, `StreamJoiner` and
  `ExternalEnricher` are used by one blueprint, `_enricher-windower-kafka`, which `pipelines/README.md`
  lists under "Test pipelines … not for production use". The three production blueprints
  (`payments-api`, `casino-api`, `payments-stripe-ingest`) use none of them.

So `ingest` is the **merchant-facing edge of shared payments**: the one place a merchant key is
verified, a request is shaped into `merchant`'s command, a merchant's own reference makes the
command idempotent, a merchant is rate-limited, and every inbound request is recorded for an
operator to see. It owns no payment: `merchant` (spec 004) owns the cashier session, the payment and
the deposit workflow; `psp-gateway` (spec 005) owns every payment provider's webhook, which never
reaches `ingest`. It is Scala (constitution VI): it sits on the deposit path, its reason for being
Python — the JSONata shape — is gone with Stripe, and a cached key lookup and a forwarded command
need no adapter library.

Components, for the plan: no event sourced entity; one key value entity per sub-brand and request
id holding the outcome of the first request under it; one per sub-brand holding its rate; one per
inbound request (the request record) with a view of them by sub-brand and time; one per cashier
ticket and payment naming the sub-brand that owns it; a cache of cashier key digests fed from
`merchant`'s key facts and read by digest (plan.md's component table, 2026-10-21). It calls
`merchant` within the project by service identity and nothing outside the project.

## Clarifications

### Session 2026-10-08

- Q: Does `ingest` carry the Stripe webhook the plan named? → A: No. It is a stub in the original
  (Context): no deployed handler, no provider adapter, the only scenario skipped. Constitution I
  drops it, and this spec says so. A Stripe adapter, if one is ever wanted, is a payment provider
  in spec 005 and its webhook lands there like every other provider's.
- Q: What is "the merchant API webhook" of the plan? → A: Not a webhook. The merchant API is the
  cashier session and payment routes a merchant calls under its key; the plan named it as the
  `payments-api` pipeline's role (merchant key verified, fields checked, command sunk). Here those
  routes are `ingest`'s, verified at the edge, and `merchant`'s commands behind them.
- Q: Which routes does the original serve under a merchant key? → A: Create a cashier session
  (`merchantKey` header required), submit a cashier session (by session token, no key), read a
  payment by id (no key at all). `POST /v1/payments`, `POST /v1/payouts` and
  `POST /v1/payments/verify` do not exist on `merchant-service`
  (`scenario10_payments_http_api_test.go` skips its probes for that reason) and are not carried over.
- Q: Where do the payment providers' webhooks land? → A: At `psp-gateway` (spec 005), as today
  (`services/psp-gateway/internal/handler/webhook.go`: one route per provider, each verifying that
  provider's signature or checksum). `ingest` serves no provider. The capture and fail outcomes
  `psp-gateway` reports to `merchant` are internal to the project and are not `ingest`'s either.
- Q: Is a request from a merchant delivered to `merchant` at least once with a retry and a dead
  letter? → A: No; that shape fits an asynchronous inbound, and the original has none that is live.
  Every merchant route is synchronous: the caller — a player's browser on the cashier page, or a
  merchant's server — waits for the session token, the terminals, or the payment. `ingest` forwards
  the command and returns `merchant`'s answer; when `merchant` cannot answer, the merchant is told to
  try again and nothing is queued (FR-013). A dead letter exists for one thing only: a request
  `merchant` accepted whose answer `ingest` could not return (FR-014).
- Q: Is a merchant's own reference an idempotency key? → A: Yes, by this spec (001 FR-027 applies:
  the request starts a deposit). Today a repeated create with the same `transactionId` creates a
  second session, and a create without one is given a random id. Here a repeat under a merchant's
  reference returns the first answer (FR-010); a create without a reference is served, given a
  reference, and is not repeatable. This is stricter than the original and is listed under *Changes
  from the original*.
- Q: Is `ingest` Scala or Python? → A: Scala. The plan's reason for Python was the JSONata
  transformation, which is dropped with Stripe; what remains is on the money path (constitution VI).
- Q: Are the customer fields a merchant sends personal data of the player? → A: Yes, under the
  player id the merchant names (`customerId` is `brand:player` in the original). `ingest` forwards
  them to `merchant` for the payment provider's use and keeps none of them: its request record holds
  the merchant, the reference, the amount, the currency, the country and the outcome (FR-018). What
  `merchant` keeps is spec 004's to say against 001 FR-018.

### Session 2026-10-08 (reconciliation, wave 1)

- Q: What does `merchant` keep of the customer fields? → A: Exactly the fields a provider requires,
  as `Personal` values under the player id as `payments`' own subject, on the session and the
  payment (004 FR-032); 001 FR-018 now says so, and the brand files the player's erasure in
  `payments` under its erasure right (001 FR-025).
- Q: Where does the key cache come from? → A: `payments.merchants`, the shared project's one
  contract topic (001 FR-011), produced by `merchant` (004 FR-005); the key index is read only at
  start.
- Q: Is abandon a merchant-facing route? → A: Yes. Spec 004 FR-024 replaces the original's internal
  abandon route with one the player's browser reaches by the session token; it is served here like
  submit (FR-001, FR-006).

### Session 2026-10-12 (from spec 004)

- Q: What does `ingest` send `merchant`? → A: The commands of `specs/004-merchant/contracts/commands.md`,
  by its identity, the sub-brand resolved from the merchant key: `POST /v1/cashier/sessions` (amounts in
  minor units), `/sessions/{token}/submit`, `/sessions/{token}/abandon`, `GET /v1/cashier/payments/{id}`,
  and `GET /v1/cashier/keys` for the key cache's refresh. `merchant` answers a second submit with the
  first submit's payment (spec 004 correction 2).

### Session 2026-10-21

- Q: Which fields must a create carry for `ingest` to forward it? → A: Amount, currency, player id,
  country and return address, each refused by name before the cashier is asked (FR-007). A create
  naming no country or no player id is this refusal, never a routing refusal: the routing outline in
  `features/payments/cashier.feature` loses that row and the field outline gains no country, no
  player id and no return address.
- Q: Who may read a payment by its id? → A: The sub-brand that created it, by its cashier key or the
  session's cashier ticket (FR-009). The original serves anyone holding the id; the cashier page
  presents the ticket it was given at create. Confirmed under *Changes from the original*.
- Q: Does the rate limit apply to a brand's own cashier? → A: Yes. Every sub-brand is limited by one
  rule, 600 requests a minute by default, raised per sub-brand by an operator (FR-015). A brand's
  cashier is a sub-brand like any other.
- Q: A create the cashier did not answer in time is repeated under the same request id before any
  late answer arrived; what does `ingest` do? → A: Forward it again as a fresh create (FR-013). An
  unanswered request id holds no answer, so FR-010 does not apply to it; a session the first create
  may have made is never submitted and expires, and a late answer is kept as a lost answer for the
  operator and never becomes the request id's answer (FR-014).

### Session 2026-10-21 (plan corrections, `plan.md` research)

- Q: Does `ingest` answer browser preflights (FR-020)? → A: No. The brand's frontend reaches the
  cashier routes through its own server, which adds the key (`route.ts`; research R1), so no browser
  sends the key cross-origin, and an outside sub-brand calls from its server. ankka 0.11 has no
  `OPTIONS` route (R10). FR-020, the edge case and the assumption are corrected;
  `CASHIER_ALLOWED_ORIGINS` is dropped under *Changes from the original*.
- Q: Is a create `merchant` refused "the first answer" a repeat under its request id returns
  (FR-010)? → A: No. The request id is the key of a session made, and a refusal made none: a repeat
  after a refusal is forwarded again (R5). The feature already says "returns the first session".
- Q: Which scenarios show FR-004, FR-019 and FR-021 hold? → A: Three added to
  `features/payments/cashier.feature` (correction 2), named from US1.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A sub-brand creates a cashier session under its key (Priority: P1)

A brand's cashier page, or an outside sub-brand's server, asks shared payments for a cashier
session: this amount, this currency, this player, this country, this request id, where to send the
player afterwards. It presents the sub-brand's key. Shared payments verifies the key, checks the
request, and answers with a cashier ticket and the terminals the sub-brand's routing rules allow for
this player. The player is shown those terminals.

**Why this priority**: this is how every deposit begins. Until a cashier key opens a cashier
session, no money comes in through shared payments.

**Independent Test**: replay `TestScenario14_CasinoDeposit_PlayerToWallet` (steps up to the session)
and `TestScenario12_OnboardingGate3_FullChain` (step 10a) against `ingest` with a stub `merchant`
that answers the forwarded command as the recording shows, and compare the answer.

**Acceptance Scenarios**:

- added `features/payments/cashier.feature`: a cashier session created under an active key answers a cashier ticket and the terminals the routing tree allows
- added `features/payments/cashier.feature`: a request with no cashier key is refused as unauthenticated and no session exists
- added `features/payments/cashier.feature`: a request under a key no sub-brand holds active is refused without saying why
- added `features/payments/cashier.feature`: a routing refusal is recorded against the sub-brand
- added `features/payments/cashier.feature`: a request missing or malforming a field is refused naming the field before the cashier is asked
- added `features/payments/cashier.feature`: a cashier key the sub-brand disabled is refused within a minute
- added `features/payments/cashier.feature`: a refusal of a cashier key or for rate is an audit record naming the sub-brand where known and never the key
- added `features/payments/cashier.feature`: a body larger than the installation's bound or not readable is refused before the key is checked and recorded under no sub-brand

---

### User Story 2 - A repeated request does nothing twice (Priority: P1)

A sub-brand's server retries a create because its first answer was lost in the network. Shared
payments recognises the sub-brand's request id and returns the session it already made; the player is
not shown two sessions and the sub-brand's books hold one.

**Why this priority**: constitution II and 001 FR-027. The original creates a second session on a
retry; a sub-brand that retries a timed-out create today double-books the deposit.

**Independent Test**: create a session under request id "t-1", create it again under "t-1" with the
same body, and compare the two answers; then create under "t-1" with a different amount.

**Acceptance Scenarios**:

- added `features/payments/cashier.feature`: a repeated create under the same request id with the same content returns the first session and asks the cashier nothing
- added `features/payments/cashier.feature`: a repeated create under the same request id with a different amount is refused naming the request id
- added `features/payments/cashier.feature`: a request id belongs to the sub-brand and not to the installation
- added `features/payments/cashier.feature`: a create with no request id is served under one the shared payments mints, and the same content again makes a second session
- added `features/payments/cashier.feature`: submitting a session already submitted returns the first submit's answer and makes no second payment
- added `features/payments/cashier.feature`: a create the cashier did not answer in time is told to try again, and a repeat under its request id is forwarded again

---

### User Story 3 - The session is submitted and the payment is read (Priority: P1)

The player picks a terminal on the cashier page; the page submits the session naming it. Shared
payments hands the submit to `merchant`, which initiates the payment with the provider, and the page
is given the payment id and where to send the player. Later the sub-brand, or the page, reads the
payment to show its state.

**Why this priority**: the second and third steps of every deposit; without them the session is
inert.

**Independent Test**: replay `TestScenario12_OnboardingGate3_FullChain` steps 10b and 12 and
`TestScenario13_DreamzPay_FullChain` against `ingest` with a stub `merchant`.

**Acceptance Scenarios**:

- added `features/payments/cashier.feature`: submitting a session naming an offered terminal initiates the payment and answers the payment id and the status
- added `features/payments/cashier.feature`: submitting a session naming a terminal it did not offer is refused naming the terminal
- added `features/payments/cashier.feature`: a submit the payment provider refused for a reason the player can act on is answered rejected with the provider's words
- added `features/payments/cashier.feature`: a submit under a cashier ticket no session has is refused as not found
- added `features/payments/cashier.feature`: a payment read by the sub-brand that created it returns its state
- added `features/payments/cashier.feature`: a payment read by another sub-brand is refused as not found

---

### User Story 4 - A sub-brand that floods is slowed, and the rest are not (Priority: P2)

A sub-brand's integration misbehaves and sends thousands of creates a minute. Shared payments slows
that sub-brand down and tells it so; every other sub-brand's cashier is unaffected, and `merchant` is
never asked to do the flood's work.

**Why this priority**: the original has no rate limit; one bad integration can take the cashier down
for every brand. New behaviour, listed under *Changes from the original*.

**Independent Test**: send creates from two sub-brands, one above the limit, and count what each is
answered and what `merchant` receives.

**Acceptance Scenarios**:

- added `features/payments/cashier.feature`: a sub-brand above the rate limit has the excess refused as too many with the time to wait, and the cashier receives only what was served
- added `features/payments/cashier.feature`: one sub-brand refused as too many leaves another sub-brand served in the same minute
- added `features/payments/cashier.feature`: a sub-brand whose rate limit an operator raised is served in full

---

### User Story 5 - An operator sees what sub-brands sent (Priority: P2)

An operator of shared payments looks at a sub-brand's recent requests: what came in, when, under
which request id, and how each was answered — served, refused and why, or accepted by `merchant` but
unanswered. They find the one whose answer was lost and see the session it made.

**Why this priority**: the original records nothing about a sub-brand request beyond a log line; a
support question about a lost session has nowhere to look.

**Independent Test**: send a mix of served and refused requests for one sub-brand, then read the
sub-brand's recent requests as an operator.

**Acceptance Scenarios**:

- added `features/payments/requests.feature`: a sub-brand's recent requests are listed with their time, request id, amounts and outcome and nothing that names a player
- added `features/payments/requests.feature`: a request the cashier accepted whose answer could not be returned is read among the lost answers with what the cashier made
- added `features/payments/requests.feature`: an operator whose roles do not name the shared payments is refused a sub-brand's recent requests

---

### Edge Cases

- A key presented in a different header case (`MerchantKey`, `merchantkey`): accepted, as the
  original's header match ignores case.
- A cashier key deactivated between its verification and the forwarded command: the command is
  still forwarded; the next request under that key is refused. Deactivation is read within the
  refresh bound (FR-004).
- `merchant` answers after `ingest` has given up waiting: the answer is a lost answer under the
  request id (FR-014) for the operator; a repeat under the request id is a fresh create (FR-013), so
  the late answer is never returned to the sub-brand.
- A body that is not JSON, or larger than the installation's bound: refused before the key is
  verified, and the refusal is recorded under no sub-brand.
- A request whose `X-Forwarded-For` names several addresses: the first is the customer's address, as
  the original does; it is forwarded to `merchant` as personal data and not kept by `ingest`.
- A browser's preflight for the cashier routes: none arrives. The brand's frontend calls from its
  server, which holds the key; an outside sub-brand calls from its server. `ingest` serves no
  `OPTIONS` (FR-020, corrected 2026-10-21).
- A sub-brand's request id longer than the bound or containing control characters: refused naming the
  field.

## Requirements *(mandatory)*

### Functional Requirements

**Cashier keys**

- **FR-001**: Every merchant-facing route MUST be a public route (001 FR-020) whose credential is a
  cashier key presented in the `merchantKey` header, except the submit, the abandon and the read
  of a payment, which MUST accept the session's cashier ticket in place of the key.
  *Established by:* `CashierRoutes.scala` (header `merchantKey`; submit by token).
- **FR-002**: A cashier key MUST be verified by comparing its digest with the digests of active
  keys `merchant` has issued, in constant time, without the raw key leaving `ingest` and without a
  read of every sub-brand. *Established by:* `MerchantServiceClient.validateKey` (SHA-256 digest;
  today a scan of every sub-brand's keys) — the scan is replaced, the digest is kept.
- **FR-003**: A verified key MUST resolve to exactly one sub-brand (the sub-brand that holds it), and
  every forwarded command MUST name that sub-brand and never a sub-brand the request body names.
  *Established by:* `handleCreateSession` (`validated.subBrandId`).
- **FR-004**: `ingest` MUST learn of a key issued, rotated or deactivated by `merchant` within 60
  seconds, from the `merchant_key_issued` and `merchant_key_deactivated` facts on
  `payments.merchants` (001 FR-011, 004 FR-005), refreshing from `merchant`'s key index only at
  start, and MUST refuse a deactivated key from then on.
  *Established by:* `KeySummary.active`; the bound is this spec's.
- **FR-005**: A missing key MUST be refused as unauthenticated; an unknown or deactivated key MUST
  be refused as an invalid cashier key, and the two refusals MUST be told apart only in the request
  record, never in the answer. *Established by:* `CashierRoutes` (`INVALID_MERCHANT_KEY`); the
  distinction in the record is this spec's.

**Routes and shaping**

- **FR-006**: `ingest` MUST serve: create a cashier session; submit a cashier session; abandon a
  cashier session (by its token; spec 004 FR-024, which replaces the original's internal abandon
  route); read a payment. It MUST NOT serve any other route under a cashier key. *Established by:*
  `CashierRoutes.scala` routes; `scenario10_payments_http_api_test.go` (the routes that do not
  exist).
- **FR-007**: A create MUST require an amount in whole minor units greater than zero, a currency,
  a player id of the form `brand:player`, a country, and an address to return the player to, and
  MUST refuse a request missing or malforming any, naming the field, before anything reaches
  `merchant`; a create naming no country or no player id is this refusal, never a routing refusal
  (clarification 2026-10-21). *Established by:* `handleCreateSession` (`amountCents`, `currency`, `transactionId`,
  `redirectUrl`, `country`, `customerId`); the refusal of a missing field is this spec's where the
  original defaulted it (`amountCents` to 0, `currency` to USD).
- **FR-008**: A create MAY carry the customer's email address, given and family names, telephone
  and mobile numbers, province, state, city, postal address and post code, and the customer's
  network address MUST be taken from the forwarded-for header's first address or the connection.
  Each MUST be forwarded to `merchant` as personal data under the player id and MUST NOT be kept by
  `ingest`. *Established by:* `CustomerFields` in `CashierRoutes`; 001 FR-023.
- **FR-009**: A submit MUST name the terminal the player chose and MUST be refused when the session
  did not offer it. A read of a payment MUST be served only to the sub-brand that created it, by its
  key or its session's cashier ticket. *Established by:* `handleSubmit`; the read's admission is this spec's
  (the original serves it to anyone; confirmed 2026-10-21).
- **FR-010**: A create MUST carry the sub-brand's request id as its key (001 FR-027): a repeat with
  the same request id and the same body MUST return the first session's answer without asking
  `merchant`; a repeat with a different body MUST be refused naming the request id; a create with no
  request id MUST be served under a request id `ingest` mints and returns. A create `merchant`
  refused leaves its request id free: a repeat after a refusal is forwarded again (corrected
  2026-10-21; plan research R5). A submit MUST be idempotent by the
  session's cashier ticket. *Established by:* this spec (constitution II); the original's `transactionId`
  defaulting is kept for the no-request-id case.
- **FR-011**: `ingest` MUST shape each request into the command `merchant` (spec 004) accepts and
  MUST return `merchant`'s answer unchanged in meaning: the cashier ticket and terminals; the payment
  id and next address; the payment's state; a rejection the player can act on with the provider's
  message. *Established by:* `CashierRoutes` answers (`session_token`, `available_terminals`,
  `paymentId`, `PAYMENT_REJECTED` with message).
- **FR-012**: `ingest` MUST call `merchant` within the `payments` project by service identity and
  MUST NOT call any service of any brand or any other project.

**When `merchant` cannot answer**

- **FR-013**: When `merchant` is unreachable or does not answer within the installation's bound,
  the sub-brand MUST be told to try again later, nothing MUST be queued, and the request MUST be
  recorded as unanswered. A repeat under an unanswered request id MUST be forwarded again as a
  fresh create: an unanswered request id holds no answer for FR-010 to return (clarification
  2026-10-21). *Established by:* this spec (the routes are synchronous).
- **FR-014**: When `merchant` answers after the bound, the answer MUST be kept as a lost answer
  under the sub-brand and the request id, with the session or payment the cashier made; it MUST NOT
  become the request id's answer; and an operator MUST be able to list and mark seen the lost
  answers. *Established by:* this spec (clarification 2026-10-21).

**Rate**

- **FR-015**: Each sub-brand, a brand's own cashier included, MUST be limited to the installation's
  rate of requests a minute (600 by default), raisable per sub-brand by an operator (clarification
  2026-10-21); a request over the rate MUST be refused as too many, naming
  the time to wait, and MUST NOT reach `merchant`. One sub-brand's refusals MUST NOT slow another.
  *Established by:* this spec (the original has no limit).

**Records**

- **FR-016**: Every request MUST be recorded: the sub-brand (or none, for a request refused before a
  key was verified), the time, the route, the request id, the amount, the currency, the country, the
  outcome, and the session or payment id `merchant` returned. *Established by:* this spec.
- **FR-017**: An operator of shared payments MUST be able to read a sub-brand's requests by time and
  by request id, and the lost answer; an operator whose roles do not name shared payments MUST be
  refused (001 FR-020).
- **FR-018**: A request record MUST hold no personal field: no customer name, email address,
  telephone number, address or network address. The amount, currency, country, request id and ids
  are not personal data (001 FR-024).
- **FR-019**: Every refusal of a cashier key and every refusal for rate MUST be an audit record
  on `platform.audit` (001 FR-011) naming the sub-brand where known and never the key.

**Origins and bounds**

- **FR-020**: `ingest` serves no browser preflight and sends no cross-origin headers: the key is
  presented by a server, the brand's frontend proxy or an outside sub-brand's, never by a browser.
  *Established by:* `route.ts` in `casino-player-frontend-next` (the proxy adds `merchantKey` from
  the server's environment); corrected 2026-10-21 from the original's `CASHIER_ALLOWED_ORIGINS`
  answer, which no browser of a brand ever needed (plan research R1, R10).
- **FR-021**: A body larger than the installation's bound, or not JSON, MUST be refused before the
  key is verified.

### Key Entities

- **Sub-brand**: one customer of shared payments, holding cashier keys; every eitheror brand is
  one, and so is each outside sub-brand of the payments vertical.
- **Cashier key**: a credential `merchant` issues to one sub-brand, known to `ingest` by its digest
  and its active flag.
- **Cashier session**: what a sub-brand opens for one deposit attempt: amount, currency, player,
  country, request id, return address; it offers terminals and is submitted once.
- **Request id**: the sub-brand's own id for a deposit attempt; the key of its create.
- **Terminal**: a payment provider account the sub-brand's routing rules offer for this player.
- **Request record**: one inbound request's sub-brand, time, route, request id, amount, currency,
  country, outcome and resulting ids; never a personal field.
- **Lost answer**: an answer `merchant` gave after the sub-brand had been told to try again, held
  under the sub-brand and the request id with what the cashier made, for an operator to see and
  mark seen; never the request id's answer.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every recording this spec names replays against `ingest` with a stub `merchant` and
  answers as the original did, field for field, except where *Changes from the original* says
  otherwise.
- **SC-002**: A create repeated 10 times under one request id within a minute makes one session, in
  the parity suite and under concurrent sends.
- **SC-003**: A cashier key verification completes in under 5 ms at the 99th percentile with
  10,000 active keys across 1,000 sub-brands, with no read of `merchant` in the request path.
- **SC-004**: A deactivated key is refused within 60 seconds of its deactivation.
- **SC-005**: With one sub-brand sending 10 times the rate, every other sub-brand's creates are
  served within the installation's answer bound.
- **SC-006**: No request record, log line or audit record holds a customer field or a raw cashier
  key, checked by a test that sends every customer field and a known key and searches every store
  and log `ingest` writes.
- **SC-007**: An operator finds a given request by sub-brand and request id within 2 seconds of it
  being served.

## Changes from the original

- **Dropped: the Stripe webhook and its JSONata shape.** A stub (Context). No scenario in the
  suite exercises it unskipped; no provider adapter exists. If Stripe is ever wanted it is a payment
  provider in spec 005.
- **Dropped: the Cloudflow streamlets** — `Windower`, `StreamJoiner`, `ExternalEnricher`,
  `HttpEgress`, `KafkaEgress`, `FileExport`, `RoleGateStreamlet`, `JwtVerifier`, `ApiKeyVerifier`
  — and the `payments-api`, `casino-api` and `_*` blueprints. The production blueprints carry no
  traffic (Context); `casino-api`'s JWT routes are the brand's services' own (specs 002, 008, 010,
  011); the JWT, role-gate and API-key verifiers are what ankka 022 and 001 FR-020 do.
- **Not carried: `POST /v1/payments`, `POST /v1/payouts`, `POST /v1/payments/verify`.** They do
  not exist on `merchant-service`; the suite's probes for them are skipped. A payout is spec 007's
  and is never a sub-brand's to initiate.
- **Stricter: the sub-brand's request id is an idempotency key** (FR-010). The original creates a
  second session on a repeated `transactionId`.
- **Stricter: a missing amount, currency, country or return address is refused by name** (FR-007;
  clarified 2026-10-21). The original defaults the amount to 0 and the currency to USD and lets
  `merchant` refuse later or not at all, and answers a missing country as a routing refusal.
- **Stricter: a player id without its brand is refused** (FR-007). The original accepts it and the
  deposit never reaches a wallet.
- **Stricter: a payment is read only by its sub-brand** (FR-009; confirmed 2026-10-21).
- **New: a rate limit per sub-brand** (FR-015), **a request record** (FR-016), **a lost answer for
  late answers** (FR-014), **a key cache refreshed from facts** (FR-002, FR-004) replacing the scan
  of every sub-brand on every request.
- **Moved: key verification** from `merchant-service`'s routes to this edge; `merchant` (spec 004)
  issues keys and publishes their digests and no longer serves the merchant-facing routes.
- **Dropped: the preflight answer and `CASHIER_ALLOWED_ORIGINS`** (FR-020; corrected 2026-10-21). The
  brand's frontend proxies the cashier routes from its server with the key, so no browser preflights
  them; ankka 0.11 serves no `OPTIONS`.
- **Unchanged in observable behaviour:** the create and submit answers, the routing refusals, the
  player-actionable rejection with the provider's message, the customer fields forwarded, the
  forwarded-for address.

## Recordings

Made as 001 FR-030 says, from the original's Go end-to-end suite:

- `TestScenario14_CasinoDeposit_PlayerToWallet` — the brand's cashier session under its key, with
  the terminals it offers (passing in CI).
- `TestScenario13_DreamzPay_FullChain` — session, submit and the payment's state through a
  provider's full chain.
- `TestScenario12_OnboardingGate3_FullChain`, steps 10a–12 — a new sub-brand's first session,
  submit and read (recorded by spec 004's run at `659c2d6b`; the three Go-suite recordings above
  are reused from `tests/port/merchant/` as they stand, since the original served the edge and the
  cashier in one process: plan research R15).
- What the Go suite never reaches — the refusals for a missing, unknown or disabled key, each
  missing or malformed field, the repeats under one request id, two sub-brands under one, a read by
  another sub-brand, a made-up ticket — is recorded by a driver run against the original
  (`tests/original/ingest-driver/`, one scenario per feature scenario, as 004 and 005 recorded
  theirs: 001 says a lacking scenario is added to the suite, not written by hand).
  `TestScenario10_PaymentsHTTPAPIGate3_CashierSessionFlow` and `..._CashierSessionAlreadySubmitted`
  stay skipped in the original (they probe `SubstrateURL`); the driver holds their behaviours
  (corrected 2026-10-22, plan analysis).
- Not recordings: `TestScenario10_PaymentsHTTPAPIGate2_PaymentsAPIRoutesLive`,
  `TestScenario10_PaymentsHTTPAPIGate3_PSPVerification`, `..._PaymentLifecycle`,
  `..._PayoutLifecycle`, `..._BalanceCheck`, `..._BalanceCheckNotFound` — they probe routes the
  original does not serve (the payments API spec 004 FR-037 drops); and `TestScenario1_L1L2Flow` —
  the Stripe stub.

## Assumptions

- `merchant` (spec 004 FR-005) publishes `merchant_key_issued` and `merchant_key_deactivated` on
  `payments.merchants`, carrying the key id, the sub-brand and the digest and never the raw key;
  `ingest` builds its cache from those facts and reads `merchant`'s key index only at start.
- `merchant` accepts the shaped commands this spec forwards (create session, submit session, read
  payment) by service identity within the project, and returns the same fields the original's
  routes return; spec 004 names those commands.
- The customer fields and the forwarded-for address are personal data under the player id as
  `payments`' own subject; `merchant` keeps them on the session and the payment as `Personal` values
  and nowhere else (004 FR-032, 001 FR-018, reconciled 2026-10-08). The original enriches them from
  the brand's player record by a cross-service read that 001 forbids; here the caller supplies them.
- A brand's frontend server holds the brand's cashier key and presents it on the page's behalf, as
  today (`route.ts` adds `merchantKey` from `CASINO_MERCHANT_KEY` and forwards the player's address);
  the browser never holds it (corrected 2026-10-21). The frontend changes only its cashier base URL,
  to `ingest`'s hostname.
- The installation's defaults — 600 requests a minute per sub-brand, a brand's own included
  (clarification 2026-10-21), a 10 second answer bound, a 64 KiB body bound, a 128-character
  request id bound — are starting values the plan may change.
- An outside sub-brand's `GET` of a payment is the only read `ingest` serves; the sub-brand's
  transaction listing is the backoffice's (operator realm, spec 004).

## Dependencies

- 001 (brands and projects): route classes, the audit topic, minor units, idempotency, personal
  data.
- 004 (sub-brand): the commands behind each route (004 FR-013, FR-015, FR-017, FR-024), key issuance
  and the key facts (004 FR-005), what is kept of the customer fields (004 FR-032).
- 005 (psp-gateway): every payment provider's webhook, which this spec explicitly does not serve.
- ankka 022 (identity) for the service identity `merchant` admits; ankka 038 for nothing — `ingest`
  holds no secret, only key digests; ankka 042 for the personal fields it forwards.

## Out of scope

- Any payment provider's webhook (spec 005).
- The cashier session's, the payment's and the deposit workflow's behaviour (spec 004).
- Payout initiation (spec 007).
- The payments backoffice: holdings, companies, sub-brands, routing rules, key issuance (spec 004).
- A Stripe adapter.
- A merchant-facing inbound webhook — none exists in the original; if one is specified later, FR-013
  and FR-014 are where at-least-once delivery and a lost answer would grow.
