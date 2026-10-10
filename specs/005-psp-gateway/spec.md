# Feature Specification: psp-gateway — One Contract Over Eight Payment Providers

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

Today `services/psp-gateway` is a Go service in `eitheror-platform` with nine provider packages
(`bouncemoney`, `connecx`, `cozeni`, `dreamzpay`, `eupaymentz`, `gigadat`, `morphosis`, `t365`,
`unityfinance`), a stub provider with a simulator page for anything else, and one handler file per
concern. `connecx` is not carried over: it has never transacted, per the original's own comment, so
the port serves eight. It has no adapter interface in code: `HppInitiate` is a chain of `if req.PSP == "…"` blocks,
each calling that package's `Initiate` with its own argument list, and each provider has its own
webhook route, return route, status map and signature scheme. What the service *does* is uniform;
what it *is* was written one provider at a time, between May and October 2026, as each provider
signed.

The things this spec keeps are the behaviours a player, an operator or a provider can observe in
that code: which providers exist and which methods each offers; that an amount leaves the platform
as a two-decimal string of the minor units it was given; that every webhook is verified before it
moves money and acknowledged even when it does not; that a captured deposit reaches the merchant
exactly once however many times a provider says so; that an outcome the merchant could not take is
retried and then kept for a re-drive rather than lost; that a crypto deposit paid short is never
credited in full. The things it drops are the ways that was achieved on the twin substrate: sessions
kept in memory *and* on a substrate twin, a two-minute re-drive CronJob, an `X-Internal-Secret`
shared with `merchant-service` and another with `casino-service`, and credentials read from a
terminal twin's properties over an internal route.

The plan put `psp-gateway` in TypeScript because it mostly speaks outside HTTP. The code says the
same: eight HTTP clients, eight signature schemes, two outcome sinks, and one entity per session. This
spec keeps TypeScript; the reason is recorded in the clarifications.

## Clarifications

### Session 2026-10-08

- Q: Does the gateway stay TypeScript, as the plan said, or become Scala under constitution VI? →
  A: TypeScript. Every line that matters is an HTTP client to a provider or a signature over its
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

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player is sent to a provider's page and the deposit comes back captured (Priority: P1)

`merchant` opens a cashier session, the player picks a method, and `merchant` asks the gateway to
initiate a hosted-page deposit for a sub-brand, an amount, a currency and a method. The gateway
reads that sub-brand's credentials for the provider, calls the provider, records a session under a
reference it minted, and answers with where to send the player: a redirect, or a crypto address
with an amount and a deadline. The player
pays at the provider. The provider posts a webhook; the gateway verifies it, maps the provider's
status to captured or failed, and tells `merchant` once.

**Why this priority**: It is every deposit. Nothing else in payments happens until this does.

**Independent Test**: With `psp-stub` standing in for every provider, initiate a deposit for each of
the eight, drive the stub to capture, and watch `merchant` told exactly once per deposit; drive it to
fail and watch the failure carry the provider's code and a player-facing reason.

**Acceptance Scenarios**:

- added `features/payments/providers.feature`: a deposit initiated for a sub-brand with a credential answers the provider's reference and where to send the player, and records a provider session
- added `features/payments/providers.feature`: a deposit initiated for a sub-brand with no credential for the provider is refused naming both, and no provider is called
- added `features/payments/providers.feature`: a verified webhook reporting success tells the cashier the payment is captured, once, and the provider session records it delivered
- added `features/payments/providers.feature`: a webhook reporting a failure tells the cashier the payment failed with the provider's code and a reason a player can read
- added `features/payments/providers.feature`: a webhook whose signature does not verify is acknowledged, logged, and moves nothing
- added `features/payments/providers.feature`: a webhook for a reference the gateway does not know is acknowledged and moves nothing
- added `features/payments/providers.feature`: the same webhook arriving again for a captured provider session is acknowledged and the cashier is not told twice
- added `features/payments/providers.feature`: a webhook reporting a status that is not final leaves the provider session open and the cashier untold

---

### User Story 2 - A crypto deposit is quoted, paid and checked for short payment (Priority: P1)

A player chooses a crypto method. The gateway asks the provider for a quote (Cozeni converts the
fiat amount at the provider's rate; Morphosis quotes in coin), answers with a payment address, a
coin amount, a coin currency and a deadline, and the player sends coin. When the chain confirms,
the provider calls back with the coin amount received. The gateway captures only when the received
amount is not short of the quote; a short payment is reported to `merchant` as a failure carrying
the variance, because the merchant credits the full fiat amount and crediting an underpaid deposit
is the costly mistake.

**Why this priority**: Spinvibe takes crypto today through two providers, and the short-payment rule
is the one that loses money if it is wrong.

**Independent Test**: Initiate a Cozeni and a Morphosis deposit against the stub; drive each to
confirm at the quoted amount, under it, and over it; check which outcome `merchant` receives.

**Acceptance Scenarios**:

- added `features/payments/crypto.feature`: a crypto deposit is answered with a payment address, a coin amount, a coin currency and a deadline, and no redirect
- added `features/payments/crypto.feature`: a confirmation at or above the quote captures the payment, with an overpayment recorded as a variance
- added `features/payments/crypto.feature`: a confirmation below the quote fails the payment with an underpayment variance and credits nothing
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
- added `features/payments/withdrawal-payments.feature`: a withdrawal payment the provider refuses as a duplicate reference is reported to the brand as refused, with why
- added `features/payments/withdrawal-payments.feature`: a withdrawal payment refused for an empty credential is reported as a credential failure and never as no record

---

### User Story 4 - An outcome the receiver could not take is not lost (Priority: P1)

The provider has already been told 200; nothing upstream will redeliver. If `merchant` or a brand's
`payouts` cannot take an outcome — unreachable, answering a server error, or throttling — the
gateway keeps trying, and if it gives up the outcome stays on the session, visible, until it is
delivered or an operator acts.

**Why this priority**: The original lost captures this way once ("one log line and a dropped
capture was the whole failure mode") and built three mechanisms to stop it; the port must not need
any of them to be remembered.

**Independent Test**: Capture a deposit while `merchant` answers 503; watch the outcome delivered
after `merchant` recovers, with no operator action; stop `merchant` for longer than the retry
window; watch the session show the outcome undelivered and an operator's re-drive deliver it.

**Acceptance Scenarios**:

- added `features/payments/outcomes.feature`: an outcome delivered to a receiver answering a server error or not at all is retried with backoff until it answers
- added `features/payments/outcomes.feature`: an outcome a receiver refuses as a wrong request is not retried and the provider session shows the refusal
- added `features/payments/outcomes.feature`: an outcome whose retries are exhausted stays on the provider session as undelivered and is listable
- added `features/payments/outcomes.feature`: an undelivered outcome is delivered once when an operator re-drives it or the receiver recovers

---

### User Story 5 - An operator saves credentials and the next deposit uses them (Priority: P2)

An operator stores a provider's credentials for a sub-brand through `merchant`'s backoffice (004).
The gateway never sees them in a request; it reads them from the secret store when it needs them,
every time, so a rotation takes effect on the next call and every read is recorded.

**Why this priority**: Credential handling is what a regulator and a provider audit first.

**Independent Test**: Save credentials, initiate a deposit, rotate the secret, initiate another;
the second call carries the new values; the secret store's read record names the gateway for both.

**Acceptance Scenarios**:

- added `features/payments/providers.feature`: a deposit is initiated with the credential read from the secret store at that moment
- added `features/payments/providers.feature`: a rotated credential is used by the next deposit with no restart
- added `features/payments/providers.feature`: a provider's private signing key is read and parsed at request time and never cached across rotations
- added `features/payments/providers.feature`: no credential value and no personal data of a player appears in what is logged or traced

---

### User Story 6 - The player returns from the provider and sees the right page (Priority: P3)

After paying, the player's browser comes back to the gateway's return route for that provider. The
gateway shows the player where to go next (the cashier's return page); it never settles a payment
from a browser return, only from a webhook.

**Why this priority**: It is a page, not money; but every provider has one and the Playwright suite
drives it.

**Acceptance Scenarios**:

- added `features/payments/providers.feature`: a player's browser returning from the payment provider is sent on to the cashier's return page and settles nothing
- added `features/payments/providers.feature`: a browser return for an unknown reference is sent to the cashier's return page and nothing else happens

---

### Edge Cases

- A provider's webhook arrives before the session is readable (the provider was faster than the
  write): the handler waits for the session briefly and then acknowledges without settling; the
  provider's redelivery, or the status read, resolves it.
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
- The receiver's grant revoked while an outcome is undelivered: delivery fails as refused, the
  session shows it, and the re-drive delivers it once the grant is back.
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
- **FR-003**: The answer to a deposit initiation MUST carry the gateway's reference for the session
  and one of: a redirect, or a crypto destination (address, coin amount, coin currency, deadline).
- **FR-004**: A payout initiation, served to a brand's `payouts` under the grant of spec 001 FR-016
  and keyed by the withdrawal request id, MUST carry the withdrawal request id, the brand, the
  merchant, an amount in minor units, a currency, the method, and the method's details (IBAN and payee name;
  e-Transfer email and mobile; bank account, institution and transit; crypto symbol, address and
  optional memo or tag). The withdrawal request id MUST be the reference sent to the provider where
  the provider accepts one, so its webhook needs no second lookup.
- **FR-005**: Every outcome the gateway produces MUST be one of captured, failed (with the
  provider's code and a player-facing reason), completed, failed-payout, reversed, cancelled, or
  pending, plus a variance of underpayment or overpayment with the quoted and received amounts
  where the provider reported one. Every provider's native status MUST map onto this set, and the
  mapping MUST be the original's (listed per provider below).
- **FR-006**: Every amount sent to a provider MUST be the minor-unit amount rendered the way that
  provider expects (a two-decimal string, a two-decimal number, or a dollar float for Gigadat), and
  every amount received MUST be compared against the session's, never trusted alone.

**Sessions and idempotency**

- **FR-007**: Every initiation MUST record a session under the gateway's reference before the
  answer is returned: the provider, the sub-brand and merchant, the terminal, the callback target,
  the amount and currency, what the provider returned (quote, address, salt, checkout id), and the
  outcome once known with whether it was delivered. A deposit session is keyed by the transaction
  reference; a payout session by the payout reference.
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
  EuPaymentz SHA-256; Gigadat security token; BounceMoney HMAC-SHA-256 over a timestamped canonical
  string, with replay protection by timestamp; Morphosis the per-session token in the route. A
  message that does not verify MUST move nothing and MUST be logged with the reason.
- **FR-011**: A webhook MUST be acknowledged to the provider whether or not it verifies, is known,
  or is a repeat — except where the provider retries on non-2xx and redelivery could help
  (BounceMoney), where the answer MUST say whether redelivery could help.
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
  under one time budget; MUST NOT retry a client error; and MUST record on the session whether the
  outcome was delivered, refused, or abandoned, with the receiver's answer.
- **FR-016**: Abandoned outcomes MUST be listable by an operator and re-drivable by one, and MUST be
  delivered at most once in total across retries and re-drives.
- **FR-017**: A deposit outcome's receiver and a payout outcome's receiver MUST be derived from the
  session, never from the webhook's content.

**Credentials and identity**

- **FR-018**: A provider's credentials for a sub-brand MUST be read from the secret store (ankka
  038) under the `payments` project at the moment of use, by name, and MUST NOT be held in
  configuration, in a request, or in memory across requests. Each read is recorded by the store.
- **FR-019**: `merchant` (004) MUST own writing those secrets and their names; the gateway MUST only
  read.
- **FR-020**: Each provider's base URL MUST be the installation's setting for that provider, with
  no default that reaches a real provider; a missing setting MUST refuse initiation naming the
  provider. Where a provider has a sandbox selected by credential (Gigadat's `environment`), that
  credential MUST select it.
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

- **FR-025**: One `psp-stub` image MUST stand in for all eight providers, answering each provider's
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
| Cozeni | crypto address, amount from provider's exchange rate, deadline | crypto by symbol and address | fiat `float`, coin to 8 places | SHA-256 | `COMPLETED`→captured; `FAILED`/`CANCELLED`→failed; result codes `02` overpayment, `03` underpayment | credentials `accountId`, `apiKey`, `merchantId` |
| EuPaymentz | hosted flow via gateway relay | no | two-decimal string | SHA-256 | `00000`→captured; `PEND`→open; anything else→failed | |
| Gigadat (Interac) | redirect from a token call; CAD only | e-Transfer (email, mobile) or bank account (account, institution, transit) | dollar float | security token | `STATUS_SUCCESS`→captured; `ABORTED1`, `EXPIRED`, `REJECTED`, `REJECTED1`, `FAILED`→failed with reason | credentials `campaignId`, `accessToken`, `securityToken`, `siteUrl`, `environment`; player `userId` stable; mobile digits only; province required; 2FAR not implemented |
| BounceMoney | hosted page, fiat to crypto, optional pinned method; minimum amount | no | fiat decimal | Ed25519 request signing (key id, timestamp, `v1=` signature over `<ts>.` + body); webhook HMAC-SHA-256 `t=…,v1=…` with replay window | `checkout.session.completed`→captured; `.failed`/`.expired`→failed | `Idempotency-Key` on session creation; provider retries on non-2xx up to 7× over 24h; no sandbox; private key parsed per request |
| Morphosis (QVEI WPB) | crypto address, coin amount, deadline | crypto in USD by symbol and address, optional memo/tag | `%.2f` string | none from provider; per-session token in the callback route | `ac`→captured; `de`→failed; `pe`→open; payouts the same codes | short payment refused by exact comparison of coin amounts; method names are QVEI's full names with network suffixes |

### Key Entities

- **Deposit session**: one hosted-page or crypto deposit at a provider, keyed by the gateway's
  transaction reference: provider, sub-brand, merchant, terminal, callback target, amount, currency,
  what the provider returned, outcome, delivery state. Event sourced.
- **Payout session**: one payout at a provider, keyed by the withdrawal request id: provider, brand,
  merchant, amount, currency, method, provider reference, outcome, delivery state. Event sourced.
- **Outcome**: captured, failed, completed, failed-payout, reversed, cancelled or pending, with a
  provider code, a player-facing reason, and an optional variance.
- **Provider**: one of the eight, with its operations, its verification scheme, its status map, its
  base URL setting and its credential names.
- **Outcome deliverer**: the consumer of session events that calls the receiver and records the
  delivery state.
- **Undelivered outcome**: an outcome whose delivery was abandoned, listable and re-drivable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every provider the original serves is served: eight deposit paths, four payout paths,
  each recorded against `psp-stub` and replayed as a parity test.
- **SC-002**: A captured deposit reaches `merchant` exactly once under any number of webhook
  deliveries, concurrent or repeated, in a test that delivers each webhook five times.
- **SC-003**: No outcome is lost: with the receiver down for longer than the retry budget, every
  outcome is either delivered on recovery or listed as undelivered; none is absent.
- **SC-004**: No credential value appears in any log line, trace attribute, session record or
  response body in a test that greps all four for every credential the stub was configured with.
- **SC-005**: A crypto deposit confirmed one unit short of its quote is never captured.
- **SC-006**: A credential rotation takes effect on the next call with no restart, and the secret
  store's read record names the gateway for both reads.
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
- **Credentials move from terminal twin properties to the secret store**, and the backoffice can no
  longer read a value back (original spec 060 US1 scenario 3). Names and digests remain readable.
- **The two shared secrets (`X-Internal-Secret` to `merchant-service`, `CASINO_INTERNAL_SECRET` to
  `casino-service`) are dropped** for platform identity and grants.
- **The re-drive CronJob and the undelivered marker on a twin are dropped**; the outcome deliverer
  and the session's delivery state replace them.
- **Sessions are no longer held in memory as well as on a twin**; one entity, replayed, is the only
  copy. The "webhook before the session is readable" wait stays.
- **The Unity Finance payout's fire-and-forget outcome call becomes a delivered outcome** like every
  other, so a payout outcome that `payouts` could not take is retried and listed, not left to a
  sweeper.
- **Base URLs become installation settings with no production default**; `MORPHOSIS_BASE_URL`'s
  hard-coded `https://wpb-api.qvei.com/` default goes.
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
per-provider table and are re-expressed as parity tests against `psp-stub`. The Playwright suite's
`04-deposit.spec.ts` and `12-withdrawal-controls.spec.ts` drive the player's return pages.

Providers with no end-to-end recording today — Cozeni, EuPaymentz, Gigadat, BounceMoney, Morphosis,
T365, Unity Finance deposits — are recorded from new scenarios written against `psp-stub` before the
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
  records in comments. [NEEDS CLARIFICATION: Unity Finance's withdrawal webhook shape was "inferred
  from documentation, not captured from a real request" — is there a captured sample now, or does
  the stub carry the inferred shape with that caveat?]
- Webhook endpoints are reachable from the internet through the installation's gateway under the
  `payments` project's hostnames; providers are given those addresses at onboarding.
- BounceMoney's replay window and Unity Finance's 13 retries and BounceMoney's 7 are the providers'
  behaviour, not settings. [NEEDS CLARIFICATION: Morphosis callbacks carry no signature and are
  admitted by a token in the address alone; is that acceptable to the regulator for a real-money
  brand, or must the gateway also confirm the amount against the provider's API before capturing?]

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
- Any provider not in the eight; adding one is a new spec naming its deviations in the table above.
- Card authorisation server-to-server; no provider offers it.
