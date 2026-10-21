# Feature Specification: psp-gateway — One Contract Over Nine Payment Providers

**Feature Branch**: `005-psp-gateway`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The shared `payments` project's `psp-gateway`: eight payment provider
adapters behind one contract — initiate a deposit, read its status, initiate a payout, read its
status, verify and normalise a webhook — with each provider's deviations stated as the original's
code has them; credentials per merchant from the secret store; outcomes delivered to `merchant`
(004) and to a brand's `payouts` (007) under grant; stubs for every provider so the original can be
recorded."

## Context

Today `services/psp-gateway` is a Go service in `eitheror-platform` with ten provider packages
(`bouncemoney`, `connecx`, `cozeni`, `digitarium`, `dreamzpay`, `eupaymentz`, `gigadat`, `morphosis`,
`t365`, `unityfinance`), a stub provider with a simulator page for anything else, and one handler file
per concern. `connecx` is not carried over: it has never transacted, per the original's own comment, so
the port serves nine. It has no adapter interface in code: `HppInitiate` is a chain of `if req.PSP == "…"` blocks,
each calling that package's `Initiate` with its own argument list, and each provider has its own
webhook route, return route, status map and signature scheme. What the service *does* is uniform;
what it *is* was written one provider at a time, between May and October 2026, as each provider
signed.

The things this spec keeps are the behaviours a player, an operator or a provider can observe in
that code: which providers exist and which methods each offers; that an amount leaves the platform
as a two-decimal string of the minor units it was given; that every webhook is verified before it
moves money and refused as a bad request when it does not; that a captured deposit reaches the merchant
exactly once however many times a provider says so; that an outcome the merchant could not take is
retried and then kept for a re-drive rather than lost; that a crypto deposit paid short is never
credited in full. The things it drops are the ways that was achieved on the twin substrate: sessions
kept in memory *and* on a substrate twin, a two-minute re-drive CronJob, an `X-Internal-Secret`
shared with `merchant-service` and another with `casino-service`, and credentials read from a
terminal twin's properties over an internal route.

The plan put `psp-gateway` in TypeScript because it mostly speaks outside HTTP. Planning found that
ankka 0.11's TypeScript SDK cannot set a response's status, headers or content type, which the
gateway's pages, refusals and provider answers need, so it is Scala (clarification 2026-10-18).

## Clarifications

### Session 2026-10-08

- Q: Does the gateway stay TypeScript, as the plan said, or become Scala under constitution VI? →
  A: TypeScript. *(Superseded 2026-10-18 (plan): Scala.)* Every line that matters is an HTTP client to a provider or a signature over its
  body; nothing in it is atomic across two records, and the money movement it triggers is the
  merchant's and the wallet's. The one entity it keeps (a session) is a record of what was sent and
  what came back. That is the case constitution VI allows, and the reason it gives.
- Q: Where do a provider's credentials live? → A: In the secret store (ankka 038), under the
  `payments` project, one secret per sub-brand and provider, written by `merchant` (004) when an
  operator saves credentials and read by the gateway at the moment of use. Today they are properties
  on a terminal twin fetched over `GET /internal/v1/terminals/{id}/properties` or
  `GET /internal/v1/sub-brands/{id}/psp-credentials/{provider}`, in clear, listable by the backoffice
  (the original's spec 060 says "retrievable via the backoffice API"). That stops: the gateway reads
  a secret the store records a read of, and `merchant` holds only the secret's name, as the plan's
  `psp-credential` entity says.
- Q: How does an outcome reach `merchant` and a brand's `payouts`? → A: By a call the gateway makes
  as itself (ankka 022 identity), to `merchant`'s capture and fail routes within `payments`, and to
  the brand's `payouts` outcome route under the grant 001 FR-016 names, with the session's reference
  as the idempotency key. No `X-Internal-Secret`. The call is made by a consumer of the session's
  events, so a delivery that fails is retried by redelivery of the change, and a delivery that is
  abandoned stays visible on the session — the plan's `outcome-deliverer` — which replaces the
  in-handler retry loop, the undelivered marker on a twin, and the re-drive CronJob.
- Q: Who serves a provider's webhook? → A: This service, as today, one route per provider, open to
  everyone and verified in the handler against the provider's scheme. Spec 006 (`ingest`) is the
  Stripe and merchant-API webhook path the Cloudflow pipelines served; it is not the payment
  providers' path and never was.
- Q: What is the test double? → A: One `psp-stub` image answering every provider's API shape with
  per-provider behaviour, replacing `tests/uf-stub` (Unity Finance only) and the in-process
  `StubPSP` + simulator. The original's recordings need all eight; a stub a provider does not have
  is a recording that cannot be made (001 FR-034).
- Q: Does ConnecX stay? → A: No. It never transacted; it is dropped, and a terminal that needs it
  later is a new adapter under the contract.
- Q: Does the gateway keep the card authorise/capture/void/refund routes? → A: No. They are served
  by the in-process `StubPSP` only — no real provider in the code authorises a card server-to-server
  — and the only caller is the payments Go suite's Gate 3 journey. They are recorded, listed under
  *Changes from the original*, and dropped; the merchant's hosted-page deposit is the deposit.
- Q: Does a provider's `environment` (sandbox or production) belong to a terminal? → A: Only where
  the provider has a sandbox the credential selects (Gigadat). BounceMoney has none, and the
  original removed a terminal-level switch whose "Sandbox" still reached production (its spec 206,
  FR-013); a base URL is the installation's, per provider, never a terminal's.

### Session 2026-10-08 (reconciliation, wave 1)

- Q: What does a brand's `payouts` call here? → A: Three routes under the one grant `payments` makes
  it (spec 001 FR-016): list the brand's withdrawal-capable terminals with the fields spec 007 needs
  (FR-009a), initiate a payout keyed by the withdrawal request id (FR-004), read a payout's status
  (FR-009). Terminals are `merchant`'s entities; the gateway reads them within the project.
- Q: Are the scenario 10 tests recordings? → A: Not as they stand: every one is skipped in the
  original. The three cashier tests become recordings once unskipped (spec 006 says the same); the
  rest probe the payments API spec 004 drops (FR-037) and are evidence only.

### Session 2026-10-12 (from spec 004)

- Q: Where does the gateway read a provider's credentials (FR-018)? → A: From `merchant`, at the moment of
  use, through `GET /internal/v1/sub-brands/{subBrandId}/psp-credentials/{provider}` and
  `GET /internal/v1/terminals/{terminalId}/properties`, by its identity. A secret store is per service
  (ankka 023), so "the secret store under the `payments` project" is `merchant`'s, which only `merchant`
  reads (spec 004 FR-008, research R9). The plan adopts `specs/004-merchant/contracts/gateway.md`: the
  initiate keyed by the payment id, the session status read `GET /v1/psp/sessions/{reference}`, and the
  capture's answers (`503 OUTCOME_PENDING` keeps an outcome undelivered and redelivered).

### Session 2026-10-18

- Q: Digitarium (G2Pay) card deposits joined the original on 2026-10-13 (`71a5298b`, in the recorded
  `659c2d6b`), after this spec was written: is it in scope? → A: Yes, as the ninth adapter: deposits
  only, its webhook check kept as the original has it (HMAC-SHA256 of the body under the signing key,
  any hex or base64 encoding of the digest accepted until a live delivery pins one), a `psp-stub` shape
  and a parity recording like the others.
- Q: The spec said the gateway reads credentials from "the secret store under `payments`" and keys a
  deposit session by a reference it mints; the 2026-10-12 clarification and the adopted
  `specs/004-merchant/contracts/gateway.md` say otherwise → A: The adopted contract stands: credentials
  are read from `merchant`'s internal routes by identity at the moment of use, and a deposit session is
  keyed by `merchant`'s payment id. FR-007, FR-018, FR-019, US1, US5, SC-006 and *Changes* now say so.
- Q: Is there a captured sample of Unity Finance's withdrawal webhook? → A: Yes: the original confirmed
  it live on 2026-08-06 (`unityfinance/adapter.go`, `MapWithdrawalStateName`): a field `state` carrying
  the state's name (`canceled`), not the numeric `ik_inv_st`. The stub carries that shape.
- Q: What happens to an outcome still undelivered after its retries? → A: It stays listed as
  undelivered and is re-driven automatically on a slow cadence (the original's two minutes) until it is
  delivered or a client error refuses it; an operator's re-drive delivers it at once. The original
  re-drives every undelivered outcome every two minutes, indefinitely (`redrive.go`, its CronJob), and
  `merchant` reads `undelivered: true` as "wait another deadline", so an outcome nobody re-drove would
  hold a payment open forever.
- Q: Morphosis signs nothing: is the original's check enough for a real-money brand, or must the
  gateway confirm with QVEI's API before capturing? → A: The original's check is kept as it is: the
  per-session token in the callback's address and the exact comparison of the coin amount with the
  quote, no call to QVEI. Whether that satisfies the regulator is an open product decision for Sheldon,
  recorded under *Assumptions*; it does not block the build.

### Session 2026-10-18 (plan)

- Q: TypeScript, when ankka 0.11's TypeScript SDK answers every success `200` with no headers, every
  refusal as plain text, and serves no HTML (no relay pages, no JSON refusals to `merchant`, no status
  a provider reads)? → A: Scala, constitution VI's default (plan research R1).
- Planning against the original (research, *Spec corrections*) corrected: the Cozeni underpayment
  (US2, SC-005); Gigadat's webhook, which the original does not authenticate (FR-010, *Assumptions*);
  which webhooks are not acknowledged (FR-011); the wait for a session (*Edge Cases*); the return
  pages (US6); Digitarium's environment (FR-020); and the new behaviours under *Changes*.

### Session 2026-10-19 (implementation)

- Q: A duplicate withdrawal reference: `refused`, as `withdrawal-payments.feature` said, or the
  original's `already_initiated`? → A: `already_initiated`: it says why, and the provider holds the
  request. The scenario now says so.
- Q: A webhook whose signature does not verify: acknowledged, as `providers.feature` said, or the
  original's `400` for DreamzPay? → A: a bad request is answered as one: `400` for every provider that
  signs (FR-011). New for Unity Finance, T365, Cozeni and EuPaymentz, which the original answered `200`.
- Q: Gigadat's unsigned webhook, and Morphosis's authenticated by its token address? → A: enough for
  now, as built (research R8.4).
- Q: A provider's `401`/`403` to a payout? → A: standardised: `rejected` / `unauthorised` for every
  provider unless the provider specifies otherwise (FR-004). The original did so for Unity Finance only.

### Session 2026-10-20 (parity)

- Q: A signed webhook whose amount, currency or reference does not match its session: acknowledged
  (T365, EuPaymentz, Unity Finance as built) or `400` (DreamzPay, Digitarium, BounceMoney as built)? →
  A: `400` for all six. FR-013 treats a mismatch as not verifying, and a webhook that does not verify
  is answered `400` (FR-011). Nothing settles either way; the original captured it.
- Q: A signed payout webhook naming another provider reference than the payout recorded (Unity
  Finance; Cozeni's reference is the lookup key and is signed, so it cannot mismatch): acknowledged
  as built, or `400`? → A: `400`, the same rule. Nothing settles either way.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player is sent to a provider's page and the deposit comes back captured (Priority: P1)

`merchant` opens a cashier session, the player picks a method, and `merchant` asks the gateway to
initiate a hosted-page deposit for a sub-brand, an amount, a currency and a method. The gateway
reads that sub-brand's credentials for the provider from `merchant`, calls the provider, records a
session under the payment id `merchant` gave it, and answers with where to send the player: a redirect, or a crypto address
with an amount and a deadline. The player
pays at the provider. The provider posts a webhook; the gateway verifies it, maps the provider's
status to captured or failed, and tells `merchant` once.

**Why this priority**: It is every deposit. Nothing else in payments happens until this does.

**Independent Test**: With `psp-stub` standing in for every provider, initiate a deposit for each of
the nine, drive the stub to capture, and watch `merchant` told exactly once per deposit; drive it to
fail and watch the failure carry the provider's code and a player-facing reason.

**Acceptance Scenarios**:

- added `features/payments/providers.feature`: a deposit initiated for a sub-brand with a credential answers the provider's reference and where to send the player, and records a provider session
- added `features/payments/providers.feature`: a deposit initiated for a sub-brand with no credential for the provider is refused naming both, and no provider is called
- added `features/payments/providers.feature`: a verified webhook reporting success tells the cashier the payment is captured, once, and the provider session records it delivered
- added `features/payments/providers.feature`: a webhook reporting a failure tells the cashier the payment failed with the provider's code and a reason a player can read
- added `features/payments/providers.feature`: a webhook whose signature does not verify is refused as a bad request, logged, and moves nothing
- added `features/payments/providers.feature`: a webhook for a reference the gateway does not know is acknowledged and moves nothing
- added `features/payments/providers.feature`: the same webhook arriving again for a captured provider session is acknowledged and the cashier is not told twice
- added `features/payments/providers.feature`: a webhook reporting a status that is not final leaves the provider session open and the cashier untold

---

### User Story 2 - A crypto deposit is quoted, paid and checked for short payment (Priority: P1)

A player chooses a crypto method. The gateway asks the provider for a quote (Cozeni converts the
fiat amount at the provider's rate; Morphosis quotes in coin), answers with a payment address, a
coin amount, a coin currency and a deadline, and the player sends coin. When the chain confirms,
the provider calls back with the coin amount received. The gateway captures in full only when the
received amount is not short of the quote. A short payment the provider has valued in the deposit's
currency (Cozeni's received convert amount) is captured with an underpayment variance, and `merchant`
credits that value, never the invoice (spec 004 FR-026); a short payment with no valuation (every
Morphosis one) is reported as a failure carrying the variance. Crediting an underpaid deposit in full
is the costly mistake.

**Why this priority**: Spinvibe takes crypto today through two providers, and the short-payment rule
is the one that loses money if it is wrong.

**Independent Test**: Initiate a Cozeni and a Morphosis deposit against the stub; drive each to
confirm at the quoted amount, under it, and over it; check which outcome `merchant` receives.

**Acceptance Scenarios**:

- added `features/payments/crypto.feature`: a crypto deposit is answered with a payment address, a coin amount, a coin currency and a deadline, and no redirect
- added `features/payments/crypto.feature`: a confirmation at or above the quote captures the payment, with an overpayment recorded as a variance
- added `features/payments/crypto.feature`: a confirmation below the quote that the provider has not valued fails the payment with an underpayment variance and credits nothing
- added `features/payments/crypto.feature`: a confirmation below the quote that the provider has valued captures the payment at that value, with an underpayment variance
- added `features/payments/crypto.feature`: a confirmation whose received amount is missing or unreadable is treated as short
- added `features/payments/crypto.feature`: a webhook from a crypto provider that does not sign is admitted only on the secret in its address

---

### User Story 3 - A brand's payout is initiated and its outcome delivered (Priority: P1)

A brand's `payouts` service (007) approves a withdrawal and asks the gateway to pay it out through a
provider: Unity Finance by IBAN, Gigadat by e-Transfer or bank account, Cozeni or Morphosis to a
crypto address. The gateway initiates it under the withdrawal request's id, records a payout session,
and answers with the provider's reference. The outcome arrives by webhook or is fetched by status;
either way the gateway tells the brand's `payouts` once, under grant, and `payouts` resumes the
withdrawal.

**Why this priority**: Withdrawals are the regulated half of money movement and the half players
complain about.

**Independent Test**: For each payout-capable provider, initiate a payout against the stub, drive it
to completed, failed and reversed, and watch `payouts` receive each outcome exactly once; drive the
stub to "no record" and watch the status read say so.

**Acceptance Scenarios**:

- added `features/payments/withdrawal-payments.feature`: a withdrawal payment initiated under a request id calls the provider once and answers the provider's reference
- added `features/payments/withdrawal-payments.feature`: a withdrawal payment initiated again under the same request id calls no provider and returns the first answer
- added `features/payments/withdrawal-payments.feature`: a provider's webhook reporting the withdrawal's outcome tells the brand once with the provider's reference
- added `features/payments/withdrawal-payments.feature`: a status read for a withdrawal payment with no webhook asks the provider and answers its state or that the provider has no record
- added `features/payments/withdrawal-payments.feature`: a withdrawal payment the provider refuses as a duplicate reference is reported to the brand as already initiated
- added `features/payments/withdrawal-payments.feature`: a withdrawal payment refused for an empty credential is reported as a credential failure and never as no record

---

### User Story 4 - An outcome the receiver could not take is not lost (Priority: P1)

The provider has already been told 200; nothing upstream will redeliver. If `merchant` or a brand's
`payouts` cannot take an outcome — unreachable, answering a server error, or throttling — the
gateway keeps trying; past its retry budget the outcome stays on the session, visible as
undelivered, and is re-driven on a slow cadence until it is delivered, or at once when an operator
re-drives it.

**Why this priority**: The original lost captures this way once ("one log line and a dropped
capture was the whole failure mode") and built three mechanisms to stop it; the port must not need
any of them to be remembered.

**Independent Test**: Capture a deposit while `merchant` answers 503; watch the outcome delivered
after `merchant` recovers, with no operator action; stop `merchant` for longer than the retry
budget; watch the session show the outcome undelivered, the next slow re-drive deliver it once
`merchant` is back, and an operator's re-drive deliver another at once.

**Acceptance Scenarios**:

- added `features/payments/outcomes.feature`: an outcome delivered to a receiver answering a server error or not at all is retried with backoff until it answers
- added `features/payments/outcomes.feature`: an outcome a receiver refuses as a wrong request is not retried and the provider session shows the refusal
- added `features/payments/outcomes.feature`: an outcome whose retries are exhausted stays on the provider session as undelivered and is listable
- added `features/payments/outcomes.feature`: an undelivered outcome is delivered once when an operator re-drives it or the receiver recovers
- added `features/payments/outcomes.feature`: a refused outcome is left by the automatic re-drive and delivered when an operator re-drives it

---

### User Story 5 - An operator saves credentials and the next deposit uses them (Priority: P2)

An operator stores a provider's credentials for a sub-brand through `merchant`'s backoffice (004).
The gateway never sees them in a request; it reads them from `merchant` when it needs them, every
time, by its identity, so a rotation takes effect on the next call and `merchant` sees every read.

**Why this priority**: Credential handling is what a regulator and a provider audit first.

**Independent Test**: Save credentials, initiate a deposit, rotate the secret, initiate another;
the second call carries the new values; `merchant` was asked twice, by the gateway.

**Acceptance Scenarios**:

- added `features/payments/providers.feature`: a deposit is initiated with the credential read from the secret store at that moment
- added `features/payments/providers.feature`: a rotated credential is used by the next deposit with no restart
- added `features/payments/providers.feature`: a provider's private signing key is read and parsed at request time and never cached across rotations
- added `features/payments/providers.feature`: no credential value and no personal data of a player appears in what is logged or traced

---

### User Story 6 - The player returns from the provider and sees the right page (Priority: P3)

After paying, the player's browser comes back to the gateway's return route for that provider. The
gateway shows the player the original's neutral page saying the payment is being processed; it never
settles a payment from a browser return, only from a webhook (the original's DreamzPay return POST
did, and that is dropped under *Changes*).

**Why this priority**: It is a page, not money; but every provider has one and the Playwright suite
drives it.

**Acceptance Scenarios**:

- added `features/payments/providers.feature`: a player's browser returning from the payment provider is shown that the payment is being processed and settles nothing
- added `features/payments/providers.feature`: a browser return for an unknown reference is shown that the payment is being processed and nothing else happens

---

### Edge Cases

- A provider's webhook arrives before the provider's answer to the initiation is recorded: the session
  is claimed before the provider is called, so the webhook finds it and settles it where verification
  needs nothing the answer carries; where it does (Morphosis's id), it is acknowledged without settling
  and the provider's redelivery resolves it. The original has no wait and the port adds none.
- Two webhooks for one reference at once: one capture, because the session entity serialises them
  and the second finds the session captured.
- A webhook whose amount or currency differs from the session's: refused as not verifying, logged
  with both, acknowledged.
- A deposit initiated below a provider's minimum (BounceMoney has one): refused before the provider
  is called, with a player-facing message naming the minimum.
- A provider whose base URL the installation has not set: initiation refused naming the provider;
  nothing is attempted against a default production URL by accident.
- A method a provider does not offer in the given currency (Interac outside CAD): refused by the
  gateway naming the method and currency; `merchant` should not have offered it, and the gateway
  does not trust that it did not.
- The receiver's grant revoked while an outcome is undelivered: delivery is refused, the session shows
  the refusal with the receiver's answer, the automatic re-drive leaves it, and an operator re-drives it
  once the grant is back.
- A payout status read for a reference the provider never created: answered as "no record", which
  `payouts` treats as failed only by its own rules; the gateway does not decide.

## Requirements *(mandatory)*

### Functional Requirements

**The contract**

- **FR-001**: The gateway MUST offer one contract over every provider: initiate a deposit, read a
  deposit's status, list the terminals a brand's players may withdraw to, initiate a payout, read a
  payout's status, and verify and normalise a webhook.
  A provider that lacks an operation (most lack payouts; crypto providers lack a redirect) MUST
  answer that it is not offered, named, rather than fail.
- **FR-002**: A deposit initiation MUST carry the sub-brand, the player's id, an amount in minor
  units as a whole number, a currency, a method, the callback target, the terminal, and the player
  fields a provider may require (name, email, phone, address, country, city, postal code, state or
  province, mobile, IP). A decimal amount MUST be refused.
- **FR-003**: The answer to a deposit initiation MUST carry the session's reference (the payment id,
  as the provider was given it) and one of: a redirect, or a crypto destination (address, coin amount, coin currency, deadline).
- **FR-004**: A payout initiation, served to a brand's `payouts` under the grant of spec 001 FR-016
  and keyed by the withdrawal request id, MUST carry the withdrawal request id, the brand, the
  merchant, an amount in minor units, a currency, the method, and the method's details (IBAN and payee name;
  e-Transfer email and mobile; bank account, institution and transit; crypto symbol, address and
  optional memo or tag). The withdrawal request id MUST be the reference sent to the provider where
  the provider accepts one, so its webhook needs no second lookup. A `401` or `403` to the request
  that makes the payout MUST be `rejected` / `unauthorised` for every provider, unless the provider
  specifies otherwise; a duplicate reference MUST be `already_initiated`.
- **FR-005**: Every outcome the gateway produces MUST be one of captured, failed (with the
  provider's code and a player-facing reason), completed, failed-payout, reversed, or pending (a
  provider's cancellation is a failure, as the original maps it), plus a variance of underpayment or overpayment with the quoted and received amounts
  where the provider reported one. Every provider's native status MUST map onto this set, and the
  mapping MUST be the original's (listed per provider below).
- **FR-006**: Every amount sent to a provider MUST be the minor-unit amount rendered the way that
  provider expects (a two-decimal string, a two-decimal number, or a dollar float for Gigadat), and
  every amount received MUST be compared against the session's, never trusted alone.

**Sessions and idempotency**

- **FR-007**: Every initiation MUST record a session under its reference before the provider is
  called and before the answer is returned: the provider, the sub-brand and merchant, the terminal, the callback target,
  the amount and currency, what the provider returned (quote, address, salt, checkout id), and the
  outcome once known with whether it was delivered. A deposit session is keyed by `merchant`'s payment
  id; a payout session by the withdrawal request id.
- **FR-008**: A second initiation under the same reference (a payout's withdrawal request id, or a
  deposit's payment id where `merchant` supplies one) MUST return the first answer and MUST NOT call
  the provider again.
- **FR-009**: A session MUST be readable by `merchant` and by the brand's `payouts` under grant: its
  state, its outcome, and whether and when the outcome was delivered. A payout's status read MUST
  answer completed, failed-payout, reversed, pending, or no record, with the provider's reference.
- **FR-009a**: The gateway MUST serve a brand's `payouts`, under the same grant, the list of the
  brand's terminals, read from `merchant` within the project: for each, its id, name, scheme, the
  provider (gateway), whether it is active, whether it supports withdrawal, whether a payout
  destination is required, the coins it supports, the provider's review window and whether the
  provider has a status to ask (spec 007 FR-002, FR-010); `payouts` applies the eligibility rule
  itself and keeps no copy.

**Webhooks**

- **FR-010**: Each provider's webhook route MUST be open to everyone and MUST verify the message in
  the handler by that provider's scheme before anything else: DreamzPay MD5 checksum; Unity Finance
  SHA-256 over sorted `ik_` values with the sign key; T365 SHA-256; Cozeni SHA-256;
  EuPaymentz SHA-256; Gigadat none (the original authenticates nothing, its OQ-8: it is admitted on the
  reference and FR-013's cross-check); BounceMoney HMAC-SHA-256 over a timestamped canonical
  string, with replay protection by timestamp; Digitarium HMAC-SHA-256 of the body under the signing
  key, its digest accepted in any hex or base64 encoding; Morphosis the per-session token in the route. A
  message that does not verify MUST move nothing and MUST be logged with the reason.
- **FR-011**: A webhook whose signature does not verify (or that cannot be verified, its key absent)
  MUST be answered `400`, for every provider that signs. Any other webhook MUST be acknowledged
  whether or not it is known or a repeat, except BounceMoney's and Digitarium's answers, which say
  whether redelivery could help (`400` malformed, `500` no signing secret, `502` a dependency
  unreadable). Gigadat's webhook is unsigned and Morphosis's is authenticated by its token address;
  both are acknowledged (Clarifications 2026-10-19).
- **FR-012**: The raw body of a webhook MUST be read before it is parsed, so verification is over
  the bytes the provider signed, and so an empty form is told apart from a body in another content
  type.
- **FR-013**: A verified webhook MUST be cross-checked against the session: the checkout or terminal
  it names, the amount and currency, and that the session is not already settled. A mismatch MUST be
  treated as not verifying.

**Outcome delivery**

- **FR-014**: An outcome MUST be delivered to its receiver — `merchant`'s capture or fail route
  within `payments`, or the brand's `payouts` outcome route under the grant of 001 FR-016 — as the
  gateway's own identity, with the session's reference as the idempotency key, and by a consumer of
  the session's events so that a failed delivery is redelivered.
- **FR-015**: Delivery MUST retry a server error, a throttle, and a transport failure with backoff
  under one time budget (the original's: 4 attempts from 250 ms doubling, within 15 s, each call
  bounded at 10 s); MUST NOT retry a client error; and MUST record on the session whether the outcome
  was delivered, refused, or undelivered, with the receiver's answer.
- **FR-016**: An outcome undelivered past its budget MUST be listable by an operator, MUST be re-driven
  automatically every two minutes until it is delivered or refused by a client error, and MUST be
  re-drivable by an operator at once. An outcome a receiver refused MUST be listable and re-drivable by
  an operator, and never re-driven automatically. The gateway MUST send a delivered or refused outcome
  no further time; a redelivery after a crash between the call and its record is a repeat under the
  session's key, which the receiver absorbs (constitution II).
- **FR-017**: A deposit outcome's receiver and a payout outcome's receiver MUST be derived from the
  session, never from the webhook's content.

**Credentials and identity**

- **FR-018**: A provider's credentials for a sub-brand MUST be read from `merchant` at the moment of
  use, by the gateway's identity, through `GET /internal/v1/sub-brands/{subBrandId}/psp-credentials/{provider}`
  and `GET /internal/v1/terminals/{terminalId}/properties`, and MUST NOT be held in configuration, in a
  request, or in memory across requests. Each read is a call `merchant` sees (and, once ankka 038
  ships, one its secret store records).
- **FR-019**: `merchant` (004) MUST own the credentials, in its own secret store (a store is per
  service, ankka 023); the gateway MUST only read them through those routes.
- **FR-020**: Each provider's base URL MUST be the installation's setting for that provider, with
  no default that reaches a real provider; a missing setting MUST refuse initiation naming the
  provider. Where a provider has a sandbox selected by credential (Gigadat's `environment`, and
  Digitarium's, which chooses between two installation settings as the original chooses its host),
  that credential MUST select it.
- **FR-021**: Every call the gateway makes into the platform MUST carry the gateway's identity
  (ankka 022) and no shared secret; every call a platform service makes to the gateway MUST be
  admitted by the caller's identity, within `payments` or by grant.
- **FR-022**: No log line, trace or stored session MUST carry a credential value or a player's
  personal field; player fields passed to a provider at initiation are not kept on the session.

**Resilience**

- **FR-023**: Every outbound provider call MUST have a timeout, and a provider answering slowly or
  failing repeatedly MUST be reported in the service's status per provider, so an operator sees a
  provider down before players do.
- **FR-024**: An initiation that fails at the provider MUST refuse with a player-facing message where
  the provider gave one (BounceMoney) and a generic one otherwise, and MUST NOT leave a
  session that a webhook could later settle.

**Test doubles and recordings**

- **FR-025**: One `psp-stub` image MUST stand in for all nine providers, answering each provider's
  real API shape (paths, bodies, envelopes, signatures) with control endpoints that drive any
  outcome: capture, fail with a code, pending then settle, duplicate reference, no record, reversed,
  underpayment, overpayment, unsigned callback, and a 401 on empty credentials. It replaces
  `tests/uf-stub` and the in-process stub and simulator.
- **FR-026**: Every acceptance scenario above MUST be runnable against `psp-stub`, and the stub MUST
  be the provider in ankka-port recordings of the original (001 FR-034).

### Per-provider deviations, as read from the original

| Provider | Deposit | Payout | Amount sent | Verification | Status map | Notes |
|---|---|---|---|---|---|---|
| DreamzPay | redirect | no | `%.2f` string | MD5 checksum of member id, type, amount, reference, return URL, secure key | status + transactionStatus → captured/failed | credentials `memberId`, `totype`, `secureKey`; `terminalId.{METHOD}` per method |
| Unity Finance | redirect (server-side invoice, JSON) | IBAN, Basic auth + account id | `%.2f` string | SHA-256 over sorted `ik_` values + sign key, base64; `ik_sign` excluded; arrays sorted | `success`→captured, `canceled`→failed with cancel code; payouts `1` transient, `8` completed, `9`/`20` failed, `11` reversed; withdrawal webhook uses state *names* | one `signKey` per sub-brand, `checkoutId.{METHOD}`; methods Apple Pay, Google Pay, Visa, Mastercard, Open Banking, MB WAY, Skrill, Neteller, paysafecard, Rapid; cards carry full identity; provider retries 13× until 200; recover by status read; refuses empty Basic auth |
| T365 | redirect | no | `%.2f` string | SHA-256 | provider code → captured/failed | 16 method codes (bank transfer, wallets, QR, virtual account, UPI, FPX, DuitNow, TrueMoney, MomoPay, mobile money, cash, PIX, open banking, BEP20/ERC20/TRC20) |
| Cozeni | crypto address, amount from provider's exchange rate, deadline | crypto by symbol and address | fiat `float`, coin to 8 places | SHA-256 | `COMPLETED`→captured; `FAILED`/`CANCELLED`→failed; result codes `02` overpayment (captured), `03` underpayment (captured at the received convert amount when there is one, else failed) | credentials `accountId`, `apiKey`, `merchantId` |
| EuPaymentz | hosted flow via gateway relay | no | two-decimal string | SHA-256 | `00000`→captured; `PEND`→open; anything else→failed | |
| Gigadat (Interac) | redirect from a token call; CAD only | e-Transfer (email, mobile) or bank account (account, institution, transit) | dollar float | none (OQ-8); the reference and the cross-check | `STATUS_SUCCESS`→captured; `ABORTED1`, `EXPIRED`, `REJECTED`, `REJECTED1`, `FAILED`→failed with reason | credentials `campaignId`, `accessToken`, `securityToken`, `siteUrl`, `environment`; player `userId` stable; mobile digits only; province required; 2FAR not implemented |
| BounceMoney | hosted page, fiat to crypto, optional pinned method; minimum amount | no | fiat decimal | Ed25519 request signing (key id, timestamp, `v1=` signature over `<ts>.` + body); webhook HMAC-SHA-256 `t=…,v1=…` with replay window | `checkout.session.completed`→captured; `.failed`/`.expired`→failed | `Idempotency-Key` on session creation; provider retries on non-2xx up to 7× over 24h; no sandbox; private key parsed per request |
| Digitarium (G2Pay) | hosted card checkout (redirect); two-decimal currencies only | no | decimal number (`float`) | HMAC-SHA-256 of the JSON body under `signingKey`; hex or base64 digest, padded or not, compared in constant time | `COMPLETED`→captured; `DECLINED`/`CANCELLED`→failed; `CHECKOUT`, `PENDING`, `AUTHORIZED` open | credentials `apiKey` (Bearer), `signingKey`; our reference sent as `referenceId` and echoed in the webhook; a currency whose minor unit is not 1/100 refused before the call; 401 on a bad key |
| Morphosis (QVEI WPB) | crypto address, coin amount, deadline | crypto in USD by symbol and address, optional memo/tag | `%.2f` string | none from provider; per-session token in the callback route | `ac`→captured; `de`→failed; `pe`→open; payouts the same codes | short payment refused by exact comparison of coin amounts; method names are QVEI's full names with network suffixes |

### Key Entities

- **Deposit session**: one hosted-page or crypto deposit at a provider, keyed by `merchant`'s payment
  id: provider, sub-brand, merchant, terminal, callback target, amount, currency,
  what the provider returned, outcome, delivery state. Event sourced.
- **Payout session**: one payout at a provider, keyed by the withdrawal request id: provider, brand,
  merchant, amount, currency, method, provider reference, outcome, delivery state. Event sourced.
- **Outcome**: captured, failed, completed, failed-payout, reversed, cancelled or pending, with a
  provider code, a player-facing reason, and an optional variance.
- **Provider**: one of the nine, with its operations, its verification scheme, its status map, its
  base URL setting and its credential names.
- **Outcome deliverer**: the consumer of session events that calls the receiver and records the
  delivery state.
- **Undelivered outcome**: an outcome past its retry budget and not yet delivered or refused: listed,
  re-driven every two minutes, and re-drivable by an operator. A refused outcome is listed and
  re-drivable by an operator only.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every provider the original serves is served: nine deposit paths, four payout paths,
  each recorded against `psp-stub` and replayed as a parity test.
- **SC-002**: A captured deposit reaches `merchant` exactly once under any number of webhook
  deliveries, concurrent or repeated, in a test that delivers each webhook five times.
- **SC-003**: No outcome is lost: with the receiver down for longer than the retry budget, every
  outcome is listed as undelivered while it is down and delivered within one re-drive cadence (two
  minutes) of its recovery, with no operator action; none is absent and none delivered twice.
- **SC-004**: No credential value appears in any log line, trace attribute, session record or
  response body in a test that greps all four for every credential the stub was configured with.
- **SC-005**: A crypto deposit confirmed one unit short of its quote is never credited in full: with no
  valuation from the provider it fails, and with one it is captured with an underpayment variance.
- **SC-006**: A credential rotation takes effect on the next call with no restart, and `merchant` is
  asked for the credential on each of the two calls (its secret store records each read once ankka
  038 ships).
- **SC-007**: No shared secret exists between the gateway and any platform service; every call is
  admitted by identity or grant.

## Changes from the original

- **ConnecX is removed.** It has never transacted, per the original's own comment; its package,
  its `X-paymesh-authz` scheme, and its status map are not ported. A terminal that needs it later is
  a new adapter under the contract, with its deviations added to the table above.
- **The gateway-hosted instructions page for bank-transfer channels is dropped with it.** ConnecX
  was the only provider that returned one; a deposit answer is now a redirect or a crypto
  destination.
- **Card authorise, capture, void and refund routes are dropped.** They were served by the
  in-process stub only; no real provider in the code authorises a card. Recorded by
  `TestScenario8_PaymentsGate3_AuthorizeCaptureJourney` and `TestScenario10_PaymentsHTTPAPIGate3_PSPVerification`
  as evidence of what was, not as parity targets.
- **The simulator and the stub provider are dropped** in favour of `psp-stub`; the `PAYONE` stub
  entry the original's spec 199 already declared dead goes with them.
- **Credentials move from terminal twin properties to `merchant`'s secret store**, read by the gateway
  through `merchant`'s internal routes by identity, and the backoffice can no longer read a value back
  (original spec 060 US1 scenario 3). Names and digests remain readable.
- **The two shared secrets (`X-Internal-Secret` to `merchant-service`, `CASINO_INTERNAL_SECRET` to
  `casino-service`) are dropped** for platform identity and grants.
- **The re-drive CronJob and the undelivered marker on a twin are dropped**; the outcome deliverer,
  the session's delivery state and a timer that re-drives every two minutes replace them, at the
  original's cadence.
- **Sessions are no longer held in memory as well as on a twin**; one entity, replayed, is the only
  copy. The "webhook before the session is readable" wait stays.
- **The Unity Finance payout's fire-and-forget outcome call becomes a delivered outcome** like every
  other, so a payout outcome that `payouts` could not take is retried and listed, not left to a
  sweeper.
- **Base URLs become installation settings with no production default**; `MORPHOSIS_BASE_URL`'s
  hard-coded `https://wpb-api.qvei.com/` default goes.
- **New: a repeat initiation calls no provider.** The original mints a reference per call, so a
  retried initiate opened a second provider session; the session is now keyed by `merchant`'s payment
  id, claimed before the call, and that id is the reference sent to the provider.
- **New: every webhook is cross-checked against its session** (FR-013): amount, currency, the
  checkout or terminal, and that it is not settled. The original checked amounts only for Cozeni and
  Morphosis and the checkout only for Unity Finance.
- **The DreamzPay return POST no longer settles a payment.** The original verified the browser's form
  and called the capture itself, to an address the browser supplied, carrying the internal secret.
- **New: every outbound provider call is bounded, and each provider's health is on the status**
  (FR-023). The original's calls had no timeout and it had no metrics.
- **Payout amounts arrive in minor units**; the original's decimal conversions (truncating for
  Cozeni, rounding for Morphosis) are gone, and its four `/v1/psp/payout/{provider}` routes are one
  family under `/v1/payments/withdrawals`.
- **New: the outcome to `merchant` is signed with the credential's `webhookSecret` where it has one**,
  the scheme `merchant` checks; the original never signed it, so such a credential refused every capture.
- Kept verbatim: every provider's status map, every signature scheme, the player-facing failure
  reasons, the underpayment rule, the always-200 acknowledgement where providers retry until 200,
  and the refusal of initiation on missing credentials.

## Recordings

The payments Go end-to-end suite, run against the original with `psp-stub` as every provider,
recorded by ankka-port: `TestScenario13_DreamzPay_FullChain`;
`TestScenario14_CasinoDeposit_PlayerToWallet`; `TestScenario12_PaymentsOnboarding*` for credential
saving; and, once unskipped and pointed at the routes the original serves,
`TestScenario10_PaymentsHTTPAPIGate3_CashierSessionFlow`, `…_CashierSessionAlreadySubmitted` and
`…_HPPCallbackFailure` (every scenario 10 test is `t.Skip` today). Not recordings:
`…_PaymentLifecycle`, `…_PayoutLifecycle`, `…_PSPVerification`, `…_BalanceCheck*` and
`TestScenario8_PaymentsGate3_*`, which probe the dropped payments API and card routes (spec 004
FR-037) and are kept as evidence of what was. The gateway's own 37
Go test files (signature, status-map, retry and envelope tests per provider) are the source of the
per-provider table and are ported as unit cases; the parity tests are the driver's recordings against
`psp-stub` (plan research R14, R16). The Playwright suite's
`04-deposit.spec.ts` and `12-withdrawal-controls.spec.ts` drive the player's return pages.

Providers with no end-to-end recording today — Cozeni, Digitarium, EuPaymentz, Gigadat, BounceMoney,
Morphosis, T365, Unity Finance deposits — are recorded from new scenarios written against `psp-stub` before the
port, so that each has a parity test. ConnecX, which also has none, is dropped rather than recorded
(see *Clarifications*).

## Assumptions

- `merchant` (004) opens the cashier session, chooses the terminal and provider from the routing
  rules, supplies the player fields, and owns the deposit workflow; the gateway never decides which
  provider to use.
- `payouts` (007) decides what a "no record" status means for a withdrawal and when to ask for
  status; the gateway only answers.
- The `psp-stub` image is built from the providers' real API shapes as the original's adapters and
  `uf-stub` encode them; where a provider's documentation and its live behaviour differed (Unity
  Finance's envelopes and withdrawal webhook), the stub follows the live behaviour the original
  records in comments; Unity Finance's withdrawal webhook is the shape confirmed live on 2026-08-06.
- Webhook endpoints are reachable from the internet through the installation's gateway under the
  `payments` project's hostnames; providers are given those addresses at onboarding.
- BounceMoney's replay window and Unity Finance's 13 retries and BounceMoney's 7 are the providers'
  behaviour, not settings.
- A Morphosis callback carries no signature; it is admitted, as the original admits it, by the
  per-session token in its address and an exact comparison of the coin amount with the quote, with no
  confirmation from QVEI's API. **Open product decision (Sheldon):** whether that satisfies the
  regulator for a real-money brand. If it does not, confirming with QVEI before capturing is a declared
  divergence added then.
- A Gigadat webhook carries no signature either, and the original checks nothing (its OQ-8); it is
  admitted on the reference in its address and FR-013's cross-check. **Open product decision
  (Sheldon):** the same question as Morphosis's.

## Dependencies

- Spec 001: the `payments` project, identity, the grants of FR-016, the port method.
- Spec 004 (`merchant`): capture and fail routes, credential writing, the cashier session.
- Spec 007 (`payouts`): the outcome route it serves and the three calls it makes under grant —
  the terminal listing (FR-009a), the payout initiation (FR-004) and the status read (FR-009) —
  reconciled 2026-10-08.
- Spec 002 (`wallet`): none directly; the gateway never touches a wallet.
- ankka 022 (identity), 038 (secret store, read record), 040 (grants to and from a brand), 029 is
  not used.

## Out of scope

- Routing, risk rules, merchant hierarchy, cashier sessions and the deposit workflow (004).
- The withdrawal workflow, review, and what an outcome means for a player (007).
- Stripe and merchant-API webhooks (006).
- Any provider not in the nine; adding one is a new spec naming its deviations in the table above.
- Card authorisation server-to-server; no provider offers it.
