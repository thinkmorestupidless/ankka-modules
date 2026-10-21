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

Components, for the plan: no event sourced entity; one key value entity per merchant and merchant
reference holding the outcome of the first request under it; one key value entity per merchant
holding its rate; a view of inbound requests by merchant and time; a cache of merchant key digests
refreshed from `merchant`'s key facts. It calls `merchant` within the project by service identity
and nothing outside the project.

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

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A merchant creates a cashier session under its key (Priority: P1)

A brand's cashier page, or an external merchant's server, asks shared payments for a cashier
session: this amount, this currency, this player, this country, this reference, where to send the
player afterwards. It presents the merchant's key. Shared payments verifies the key, checks the
request, and answers with a session token and the terminals the merchant's routing rules allow for
this player. The player is shown those terminals.

**Why this priority**: this is how every deposit begins. Until a merchant key opens a cashier
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

---

### User Story 2 - A repeated request does nothing twice (Priority: P1)

A merchant's server retries a create because its first answer was lost in the network. Shared
payments recognises the merchant's reference and returns the session it already made; the player is
not shown two sessions and the merchant's books hold one.

**Why this priority**: constitution II and 001 FR-027. The original creates a second session on a
retry; a merchant that retries a timed-out create today double-books the deposit.

**Independent Test**: create a session under reference "t-1", create it again under "t-1" with the
same body, and compare the two answers; then create under "t-1" with a different amount.

**Acceptance Scenarios**:

- added `features/payments/cashier.feature`: a repeated create under the same request id with the same content returns the first session and asks the cashier nothing
- added `features/payments/cashier.feature`: a repeated create under the same request id with a different amount is refused naming the request id
- added `features/payments/cashier.feature`: a request id belongs to the sub-brand and not to the installation
- added `features/payments/cashier.feature`: a create with no request id is served under one the shared payments mints, and the same content again makes a second session
- added `features/payments/cashier.feature`: submitting a session already submitted returns the first submit's answer and makes no second payment

---

### User Story 3 - The session is submitted and the payment is read (Priority: P1)

The player picks a terminal on the cashier page; the page submits the session naming it. Shared
payments hands the submit to `merchant`, which initiates the payment with the provider, and the page
is given the payment id and where to send the player. Later the merchant, or the page, reads the
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

[NEEDS CLARIFICATION: the original serves a payment read with no key at all, to anyone holding the id.
This spec requires the sub-brand's cashier key or the session's cashier ticket (FR-009); confirm, since a
cashier page that today polls the payment without the key would need the ticket.]

---

### User Story 4 - A merchant that floods is slowed, and the rest are not (Priority: P2)

A merchant's integration misbehaves and sends thousands of creates a minute. Shared payments slows
that merchant down and tells it so; every other merchant's cashier is unaffected, and `merchant` is
never asked to do the flood's work.

**Why this priority**: the original has no rate limit; one bad integration can take the cashier down
for every brand. New behaviour, listed under *Changes from the original*.

**Independent Test**: send creates from two merchants, one above the limit, and count what each is
answered and what `merchant` receives.

**Acceptance Scenarios**:

- added `features/payments/cashier.feature`: a sub-brand above the rate limit has the excess refused as too many with the time to wait, and the cashier receives only what was served
- added `features/payments/cashier.feature`: one sub-brand refused as too many leaves another sub-brand served in the same minute
- added `features/payments/cashier.feature`: a sub-brand whose rate limit an operator raised is served in full

---

### User Story 5 - An operator sees what merchants sent (Priority: P2)

An operator of shared payments looks at a merchant's recent requests: what came in, when, under
which reference, and how each was answered — served, refused and why, or accepted by `merchant` but
unanswered. They find the one whose answer was lost and see the session it made.

**Why this priority**: the original records nothing about a merchant request beyond a log line; a
support question about a lost session has nowhere to look.

**Independent Test**: send a mix of served and refused requests for one merchant, then read the
merchant's recent requests as an operator.

**Acceptance Scenarios**:

- added `features/payments/requests.feature`: a sub-brand's recent requests are listed with their time, request id, amounts and outcome and nothing that names a player
- added `features/payments/requests.feature`: a request the cashier accepted whose answer could not be returned is read among the lost answers with what the cashier made
- added `features/payments/requests.feature`: an operator whose roles do not name the shared payments is refused a sub-brand's recent requests

---

### Edge Cases

- A key presented in a different header case (`MerchantKey`, `merchantkey`): accepted, as the
  original's header match ignores case.
- A merchant key deactivated between its verification and the forwarded command: the command is
  still forwarded; the next request under that key is refused. Deactivation is read within the
  refresh bound (FR-004).
- `merchant` answers after `ingest` has given up waiting: the request is dead-lettered with the
  reference (FR-014); a repeat under the reference returns the dead-lettered answer once it is known.
- A body that is not JSON, or larger than the installation's bound: refused before the key is
  verified, and the refusal is recorded under no merchant.
- A request whose `X-Forwarded-For` names several addresses: the first is the customer's address, as
  the original does; it is forwarded to `merchant` as personal data and not kept by `ingest`.
- A browser's preflight for the cashier routes: answered with the allowed origins the merchant's
  configuration names, never `*` for a merchant that named any.
- A merchant's reference longer than the bound or containing control characters: refused naming the
  field.

## Requirements *(mandatory)*

### Functional Requirements

**Merchant keys**

- **FR-001**: Every merchant-facing route MUST be a public route (001 FR-020) whose credential is a
  merchant key presented in the `merchantKey` header, except the submit, the abandon and the read
  of a payment, which MUST accept the session's token in place of the key.
  *Established by:* `CashierRoutes.scala` (header `merchantKey`; submit by token).
- **FR-002**: A merchant key MUST be verified by comparing its digest with the digests of active
  keys `merchant` has issued, in constant time, without the raw key leaving `ingest` and without a
  read of every merchant. *Established by:* `MerchantServiceClient.validateKey` (SHA-256 digest;
  today a scan of every merchant's keys) — the scan is replaced, the digest is kept.
- **FR-003**: A verified key MUST resolve to exactly one merchant (the sub-brand that holds it), and
  every forwarded command MUST name that merchant and never a merchant the request body names.
  *Established by:* `handleCreateSession` (`validated.subBrandId`).
- **FR-004**: `ingest` MUST learn of a key issued, rotated or deactivated by `merchant` within 60
  seconds, from the `merchant_key_issued` and `merchant_key_deactivated` facts on
  `payments.merchants` (001 FR-011, 004 FR-005), refreshing from `merchant`'s key index only at
  start, and MUST refuse a deactivated key from then on.
  *Established by:* `KeySummary.active`; the bound is this spec's.
- **FR-005**: A missing key MUST be refused as unauthenticated; an unknown or deactivated key MUST
  be refused as an invalid merchant key, and the two refusals MUST be told apart only in the request
  record, never in the answer. *Established by:* `CashierRoutes` (`INVALID_MERCHANT_KEY`); the
  distinction in the record is this spec's.

**Routes and shaping**

- **FR-006**: `ingest` MUST serve: create a cashier session; submit a cashier session; abandon a
  cashier session (by its token; spec 004 FR-024, which replaces the original's internal abandon
  route); read a payment. It MUST NOT serve any other route under a merchant key. *Established by:*
  `CashierRoutes.scala` routes; `scenario10_payments_http_api_test.go` (the routes that do not
  exist).
- **FR-007**: A create MUST require an amount in whole minor units greater than zero, a currency,
  a player id of the form `brand:player`, a country, and an address to return the player to, and
  MUST refuse a request missing or malforming any, naming the field, before anything reaches
  `merchant`. *Established by:* `handleCreateSession` (`amountCents`, `currency`, `transactionId`,
  `redirectUrl`, `country`, `customerId`); the refusal of a missing field is this spec's where the
  original defaulted it (`amountCents` to 0, `currency` to USD).
- **FR-008**: A create MAY carry the customer's email address, given and family names, telephone
  and mobile numbers, province, state, city, postal address and post code, and the customer's
  network address MUST be taken from the forwarded-for header's first address or the connection.
  Each MUST be forwarded to `merchant` as personal data under the player id and MUST NOT be kept by
  `ingest`. *Established by:* `CustomerFields` in `CashierRoutes`; 001 FR-023.
- **FR-009**: A submit MUST name the terminal the player chose and MUST be refused when the session
  did not offer it. A read of a payment MUST be served only to the merchant that created it, by its
  key or its session's token. *Established by:* `handleSubmit`; the read's admission is this spec's
  (the original serves it to anyone).
- **FR-010**: A create MUST carry the merchant's reference as its key (001 FR-027): a repeat with
  the same reference and the same body MUST return the first answer without asking `merchant`; a
  repeat with a different body MUST be refused naming the reference; a create with no reference
  MUST be served under a reference `ingest` mints and returns. A submit MUST be idempotent by the
  session's token. *Established by:* this spec (constitution II); the original's `transactionId`
  defaulting is kept for the no-reference case.
- **FR-011**: `ingest` MUST shape each request into the command `merchant` (spec 004) accepts and
  MUST return `merchant`'s answer unchanged in meaning: the session token and terminals; the payment
  id and next address; the payment's state; a rejection the player can act on with the provider's
  message. *Established by:* `CashierRoutes` answers (`session_token`, `available_terminals`,
  `paymentId`, `PAYMENT_REJECTED` with message).
- **FR-012**: `ingest` MUST call `merchant` within the `payments` project by service identity and
  MUST NOT call any service of any brand or any other project.

**When `merchant` cannot answer**

- **FR-013**: When `merchant` is unreachable or does not answer within the installation's bound,
  the merchant MUST be told to try again later, nothing MUST be queued, and the request MUST be
  recorded as unanswered. *Established by:* this spec (the routes are synchronous).
- **FR-014**: When `merchant` answers after the bound, the answer MUST be kept in a dead letter
  under the merchant and the reference, a repeat under the reference MUST return it, and an
  operator MUST be able to list and mark seen the dead letter. *Established by:* this spec.

**Rate**

- **FR-015**: Each merchant MUST be limited to the installation's rate of requests a minute,
  raisable per merchant by an operator; a request over the rate MUST be refused as too many, naming
  the time to wait, and MUST NOT reach `merchant`. One merchant's refusals MUST NOT slow another.
  *Established by:* this spec (the original has no limit).

**Records**

- **FR-016**: Every request MUST be recorded: the merchant (or none, for a request refused before a
  key was verified), the time, the route, the reference, the amount, the currency, the country, the
  outcome, and the session or payment id `merchant` returned. *Established by:* this spec.
- **FR-017**: An operator of shared payments MUST be able to read a merchant's requests by time and
  by reference, and the dead letter; an operator whose roles do not name shared payments MUST be
  refused (001 FR-020).
- **FR-018**: A request record MUST hold no personal field: no customer name, email address,
  telephone number, address or network address. The amount, currency, country, reference and ids
  are not personal data (001 FR-024).
- **FR-019**: Every refusal of a merchant key and every refusal for rate MUST be an audit record
  on `platform.audit` (001 FR-011) naming the merchant where known and never the key.

**Origins and bounds**

- **FR-020**: Browser preflights for the merchant routes MUST be answered with the origins the
  merchant's configuration names; a merchant that named none MUST be answered with any origin, as
  the original does by default. *Established by:* `CASHIER_ALLOWED_ORIGINS`.
- **FR-021**: A body larger than the installation's bound, or not JSON, MUST be refused before the
  key is verified.

### Key Entities

- **Merchant**: a sub-brand of shared payments that holds merchant keys; every eitheror brand is
  one, and so is each external merchant of the payments vertical.
- **Merchant key**: a credential `merchant` issues to one merchant, known to `ingest` by its digest
  and its active flag.
- **Cashier session**: what a merchant opens for one deposit attempt: amount, currency, player,
  country, reference, return address; it offers terminals and is submitted once.
- **Merchant reference**: the merchant's own id for a deposit attempt; the key of its create.
- **Terminal**: a payment provider account the merchant's routing rules offer for this player.
- **Request record**: one inbound request's merchant, time, route, reference, amount, currency,
  country, outcome and resulting ids; never a personal field.
- **Dead letter**: an answer `merchant` gave after the merchant had been told to try again, held
  under the merchant and the reference.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every recording this spec names replays against `ingest` with a stub `merchant` and
  answers as the original did, field for field, except where *Changes from the original* says
  otherwise.
- **SC-002**: A create repeated 10 times under one reference within a minute makes one session, in
  the parity suite and under concurrent sends.
- **SC-003**: A merchant key verification completes in under 5 ms at the 99th percentile with
  10,000 active keys across 1,000 merchants, with no read of `merchant` in the request path.
- **SC-004**: A deactivated key is refused within 60 seconds of its deactivation.
- **SC-005**: With one merchant sending 10 times the rate, every other merchant's creates are
  served within the installation's answer bound.
- **SC-006**: No request record, log line or audit record holds a customer field or a raw merchant
  key, checked by a test that sends every customer field and a known key and searches every store
  and log `ingest` writes.
- **SC-007**: An operator finds a given request by merchant and reference within 2 seconds of it
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
  and is never a merchant's to initiate.
- **Stricter: the merchant's reference is an idempotency key** (FR-010). The original creates a
  second session on a repeated `transactionId`.
- **Stricter: a missing amount or currency is refused** (FR-007). The original defaults them to 0
  and USD and lets `merchant` refuse later or not at all.
- **Stricter: a player id without its brand is refused** (FR-007). The original accepts it and the
  deposit never reaches a wallet.
- **Stricter: a payment is read only by its merchant** (FR-009; marked for confirmation).
- **New: a rate limit per merchant** (FR-015), **a request record** (FR-016), **a dead letter for
  late answers** (FR-014), **a key cache refreshed from facts** (FR-002, FR-004) replacing the scan
  of every merchant on every request.
- **Moved: key verification** from `merchant-service`'s routes to this edge; `merchant` (spec 004)
  issues keys and publishes their digests and no longer serves the merchant-facing routes.
- **Unchanged in observable behaviour:** the create and submit answers, the routing refusals, the
  player-actionable rejection with the provider's message, the customer fields forwarded, the
  forwarded-for address, the preflight origins.

## Recordings

Made as 001 FR-030 says, from the original's Go end-to-end suite:

- `TestScenario14_CasinoDeposit_PlayerToWallet` — the brand's cashier session under its key, with
  the terminals it offers (passing in CI).
- `TestScenario13_DreamzPay_FullChain` — session, submit and the payment's state through a
  provider's full chain.
- `TestScenario12_OnboardingGate3_FullChain`, steps 10a–12 — a new merchant's first session,
  submit and read (skipped in KinD today: "routing table draft returns 400"; to be unskipped, as
  001 says a lacking scenario is added to the suite, not written by hand).
- `TestScenario10_PaymentsHTTPAPIGate3_CashierSessionFlow` and
  `..._CashierSessionAlreadySubmitted` (skipped today: they probe `SubstrateURL`; to be pointed at
  the merchant routes and unskipped).
- Not recordings: `TestScenario10_PaymentsHTTPAPIGate2_PaymentsAPIRoutesLive`,
  `TestScenario10_PaymentsHTTPAPIGate3_PSPVerification`, `..._PaymentLifecycle`,
  `..._PayoutLifecycle`, `..._BalanceCheck`, `..._BalanceCheckNotFound` — they probe routes the
  original does not serve (the payments API spec 004 FR-037 drops); and `TestScenario1_L1L2Flow` —
  the Stripe stub.

## Assumptions

- `merchant` (spec 004 FR-005) publishes `merchant_key_issued` and `merchant_key_deactivated` on
  `payments.merchants`, carrying the key id, the merchant and the digest and never the raw key;
  `ingest` builds its cache from those facts and reads `merchant`'s key index only at start.
- `merchant` accepts the shaped commands this spec forwards (create session, submit session, read
  payment) by service identity within the project, and returns the same fields the original's
  routes return; spec 004 names those commands.
- The customer fields and the forwarded-for address are personal data under the player id as
  `payments`' own subject; `merchant` keeps them on the session and the payment as `Personal` values
  and nowhere else (004 FR-032, 001 FR-018, reconciled 2026-10-08). The original enriches them from
  the brand's player record by a cross-service read that 001 forbids; here the caller supplies them.
- A brand's cashier page holds the brand's merchant key, as today (the original's CORS admits the
  header from browsers). Whether that key should instead be presented by the brand's `players`
  service on the page's behalf is a frontend change and out of scope.
- The installation's defaults — 600 requests a minute per merchant, a 10 second answer bound, a
  64 KiB body bound, a 128-character reference bound — are starting values the plan may change.
  [NEEDS CLARIFICATION: is 600 a minute per merchant the right starting rate for a brand's
  cashier at peak, or should a brand's merchant be unlimited and only external merchants limited?]
- An external merchant's `GET` of a payment is the only read `ingest` serves; the merchant's
  transaction listing is the backoffice's (operator realm, spec 004).

## Dependencies

- 001 (brands and projects): route classes, the audit topic, minor units, idempotency, personal
  data.
- 004 (merchant): the commands behind each route (004 FR-013, FR-015, FR-017, FR-024), key issuance
  and the key facts (004 FR-005), what is kept of the customer fields (004 FR-032).
- 005 (psp-gateway): every payment provider's webhook, which this spec explicitly does not serve.
- ankka 022 (identity) for the service identity `merchant` admits; ankka 038 for nothing — `ingest`
  holds no secret, only key digests; ankka 042 for the personal fields it forwards.

## Out of scope

- Any payment provider's webhook (spec 005).
- The cashier session's, the payment's and the deposit workflow's behaviour (spec 004).
- Payout initiation (spec 007).
- The merchant backoffice: holdings, companies, sub-brands, routing rules, key issuance (spec 004).
- A Stripe adapter.
- A merchant-facing inbound webhook — none exists in the original; if one is specified later, FR-013
  and FR-014 are where at-least-once delivery and a dead letter would grow.
