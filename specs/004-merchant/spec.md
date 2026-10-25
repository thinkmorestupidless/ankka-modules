# Feature Specification: Merchant — The Shared Payments Project's Hierarchy, Cashier and Deposit Workflow

**Feature Branch**: `004-merchant`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The `merchant` service of the shared `payments` project: the holding → company → sub-brand → terminal hierarchy and its operators, merchant keys, payment-provider credentials and terminals, versioned routing rules, and the deposit as one workflow per payment that initiates through the gateway, awaits the provider's outcome or a stale timeout, hands the captured deposit to the brand's wallet on the granted route keyed by the payment id, and reports every later outcome the same way."

## Context

`merchant-service` is the original (`substrate/merchant-service/`, 9,760 lines of Scala over the
substrate's twin API). It does five things: it holds the payments hierarchy (`Models.scala`,
`HoldingRoutes`, `CompanyRoutes`, `MerchantRoutes`); it holds each sub-brand's merchant keys,
payment-provider credentials and terminals (`MerchantRoutes`, `MerchantServiceClient`); it holds a
versioned routing tree per holding, company or sub-brand and resolves the one that governs a deposit
(`RoutingTreeRoutes`, `RoutingTreeEvaluator`, `resolveRoutingTree`); it runs the cashier
(`CashierRoutes`, 2,307 lines: a session, a submit, the gateway's capture and fail callbacks, the
player's status read); and it provisions operators into Keycloak's `eitheror` realm with an invitation
email (`OperatorRoutes`, `OperatorInviteEmailService`, `OperatorStatusSyncer`).

The deposit today is not one thing. The cashier writes a `cashier-payment` twin, the gateway's
capture callback marks it captured and assigns the concept `payment.captured`, that assignment fires
the reactive function `deposit-on-payment-capture` (`substrate/deposit-on-payment-capture-fn/`),
which posts to `wallet-service`'s deposit route keyed by the provider's transaction id and then to
`casino-service`'s `notify/deposit`. Three sweepers close the gaps that chain leaves: the gateway's
`redrive-undelivered-outcomes` CronJob every two minutes re-posts outcomes it could not deliver;
`expire-stale-payments` every five minutes marks attempts past their 30-minute deadline expired;
`reconcile-captured-payments` hourly scans every captured payment, asks the graph whether the wallet
holds a matching deposit, and re-fires the trigger up to three times by assigning a numbered marker
concept. Each sweeper exists because the step before it could lose money silently; each is explained
in the code by the incident that produced it (ClickUp 869eby6ba, ADR 0043).

This spec keeps every route, every status and every outcome the original has and replaces the chain
with one workflow per payment that cannot end until the wallet has acknowledged the deposit. The
hand-off is the one spec 001 FR-016 names: a call on the route the brand grants `payments`/`merchant`,
keyed by the payment id (spec 001 FR-017, spec 002 FR-022), with every later outcome reported on the
second granted route (spec 002 FR-022a). The brand's wallet produces `payments.payments` from both; the
shared project's own topic is read by nothing in a brand.

Three things the original keeps in this service belong elsewhere under spec 001's split and are
named here as moved, not dropped: the ST8 credentials routes (`CredentialRoutes`,
`/v1/credentials/st8/{brand}`) are the game provider's (spec 003); the holding-level Customer.io
configuration (`BrandCrmConfigRoutes`) is engagement's (spec 014); the `kyc-policy` twin
`seedHierarchy` writes is KYC's (spec 009). A sub-brand's withdrawal turnover multiplier and definition
are read and written by `casino-service` (`BrandConfigRoutes`) and belong to the brand's wallet
(spec 002 FR-004a); this service does not hold them. The merchant-facing routes the original's
`CashierRoutes` serves — create a session, submit it, read a payment, and the abandon this spec adds
— are served in the rebuild by `ingest` (spec 006), the merchant-facing edge of shared payments; this
service serves the commands behind them to `ingest` by its platform identity and keeps the session,
the payment and the workflow.

## Clarifications

### Session 2026-10-08

- Q: What is a merchant? → A: A sub-brand. The original's `merchantId` is the sub-brand id everywhere
  (`Models.scala` "Legacy Merchant alias"); `eitheror-merchant` twins are a payments-only legacy
  identity that `reconcileLegacyMerchant` folds into the sub-brand. This spec has holdings, companies,
  sub-brands and terminals, and the service keeps its name because the plan and the routes carry it.
- Q: Which brand project does a deposit reach? → A: The one the sub-brand names. A sub-brand of type
  `Casino` or `Combined` records the brand project it belongs to (new field `brand`, the project slug,
  spec 001 FR-001); a `Payments` sub-brand names none and its deposits are captured without a wallet
  credit, as the original's "no wallet context" path does today. The original derives the brand from
  the `customer_ref` the frontend sends (`brandId:playerId`); the sub-brand's own field is the
  authority now and a `customer_ref` naming another brand is refused.
- Q: How does the deposit reach the wallet? → A: The deposit workflow calls the brand wallet's deposit
  route under the 040 grant with the payment id as the idempotency key, carrying the provider's
  transaction id as the payment reference and the terminal, provider and scheme the transaction
  records (spec 002 FR-022). The reactive function, the concept assignment that fired it, the
  `casino-service` notify call and the hourly reconcile sweep are replaced by the workflow's own
  retry: it does not complete until the wallet answers, and a repeat is the same key.
- Q: What replaces the sweepers? → A: Timers inside each payment's workflow. The 30-minute deadline is
  the workflow's `await-outcome` timeout; the gateway's undelivered-outcome marker becomes a status
  poll the workflow makes before expiring; the captured-without-credit case cannot arise because the
  workflow holds the capture until the wallet acknowledges it. A view of payments captured and not yet
  acknowledged after a bound replaces the sweep's `gaps` count for alerting.
- Q: Who reads the cashier? → A: The brand's player frontend, through its own server-side proxy that
  adds the sub-brand's merchant key (`tools/casino-player-frontend-next/src/app/api/cashier/`). The
  routes it calls are `ingest`'s (spec 006): public routes admitted by the merchant key, verified
  there against the digests this service publishes (FR-005), as spec 001 FR-020 admits a provider's
  webhook by its signature. `ingest` forwards each as a command this service serves by `ingest`'s
  identity (FR-013, FR-015, FR-017, FR-024). The frontend changes only its base URL.
- Q: How does the brand know a player's payment attempts? → A: From the outcomes `merchant` reports on
  the wallet's outcome route, which the wallet turns into the brand's `payments.payments` facts. The
  original's internal route that `casino-service` calls for a player's attempts
  (`/internal/v1/cashier/{brand}/{player}/payments`) is dropped; the brand builds its own view, as
  spec 001 FR-011 and constitution IV require. That needs an `initiated` outcome the two specs do not
  yet list (Dependencies).
- Q: How does a player abandon a deposit? → A: On the cashier, with the session token, on a new route
  `POST /v1/cashier/sessions/{token}/abandon` admitted by the merchant key; the session names the player
  and the payment, so ownership is proven by the token, which only that player's browser holds. The
  original's internal abandon route, which `casino-service` called with the player id from the token
  because the public `fail` route could not tell players apart, is dropped with it.
- Q: Do the gateway's callbacks keep their internal secret? → A: No. `psp-gateway` is admitted to the
  capture and fail routes by its platform identity (spec 001 FR-020 service class). The provider's HMAC
  on the payload (`X-PSP-Signature`, `X-PSP-Timestamp`, 300-second replay window) is kept exactly, since
  it authenticates the provider's message rather than the caller.
- Q: Is the risk blocklist enforced? → A: It is not, in the original: `RiskRoutes` stores a blocklist
  and a config (amount ceiling, velocity limit and window) at platform scope and nothing in
  `merchant-service`, `psp-gateway` or a function reads them when a deposit is made. The routes are kept
  so the backoffice page works; enforcement is a product question (FR-036).
- Q: Where is fraud assessed? → A: Not on the cashier. The original's fraud call is in the payments
  API's authorization function (`starter-packs/payments/functions/authorization.tl`,
  `FRAUD_SERVICE_URL`), on the `Payment` twin path that `pipelines/payments-api` and the Go scenarios
  8, 9 and 10 exercise. That path is the merchant API's, which spec 006 (`ingest`) carries or drops;
  this spec adds no fraud step the cashier never had.
- Q: Do operators stay here? → A: Yes. The original provisions operators of the payments backoffice
  into Keycloak's `eitheror` realm from this service, with role, scope level and scope id, an
  invitation sent through Customer.io, and a syncer that mirrors Keycloak's enabled flag. That needs
  the realm's administration, which spec 001 FR-021 allows `players` alone; 001 must extend the
  exception to `merchant` for the `eitheror` realm (Dependencies).
- Q: Minor units? → A: The original takes `amountCents` as a 32-bit integer on the cashier and stores
  the amount as a two-decimal string beside `amount_cents` as a string. Every amount here is a `Long`
  of minor units (spec 001 FR-026); the decimal string is produced on the wire where the frontend and
  the backoffice read it, never stored.

### Session 2026-10-08 (reconciliation, wave 1)

- Q: Who serves the cashier's routes? → A: `ingest` (spec 006). It verifies the merchant key from the
  digests this service publishes on `payments.merchants` (FR-005), shapes and rate-limits the request,
  makes it idempotent by the merchant's reference, and forwards a command this service serves by
  `ingest`'s identity (FR-013, FR-015, FR-017, FR-024). This service serves no public route.
- Q: Does `payments` hold personal data? → A: Yes, as FR-032 says: the customer fields a provider
  requires, as `Personal` values under the player id as `payments`' own subject, on the session and
  the payment only. Spec 001 FR-018 now says the same; the brand files the player's erasure here
  under its erasure right (001 FR-025).
- Q: The risk blocklist and the payments API? → A: Both dropped under constitution I (FR-036,
  FR-037): the first is stored and never read, the second is a stub no live route serves.
- Q: Where did the moved things land? → A: ST8 credentials in spec 003 FR-026; the deposit turnover
  terms in spec 002 FR-004a; the KYC policy and the CRM configuration are placed on specs 009 and 014
  by spec 001's Dependencies.

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: What does `merchant` do with a deposit the wallet refuses for a limit, a break or an exclusion?
  → A: Treats it as final (FR-021): no retry, the reason and allowance recorded and shown to the
  player. The money is refunded through the provider, automatically (spec 010 FR-013, settled
  2026-10-25).

### Session 2026-10-08 (reconciliation 3)

- Q: Does `merchant` name the provider on every call to the wallet? → A: Yes (FR-022): on the capture
  and on each outcome, so the brand's `payments.payments` carries the provider on every family and
  `reporting` groups deposit attempts and outcomes by provider without reading `payments`.

### Session 2026-10-12 (analysis)

- Q: The backoffice frontend reads this service's operator routes and an invited operator posts
  `accept-invitation` from a browser, yet spec 001 and the shared manifest had `merchant` never
  exposed. Which stands? → A: `merchant` is exposed. Its operator routes are admitted by a staff
  token and `accept-invitation` by the invitation token in its body (a public route admitted by its
  own key, constitution III); the `ingest`, `psp-gateway`, erasure and status routes stay service-class
  by ACL and are not reachable with a token. Spec 001's `Layout.ServiceOnly` and
  `shared/payments/manifest.json` change (tasks T002, T072).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player deposits through the cashier and the brand's wallet is credited (Priority: P1)

A player of a brand opens the deposit dialog, chooses an amount, and is shown the terminals the
sub-brand's routing rules allow for that amount, currency, country and deposit count. They pick one,
are sent to the payment provider's page, pay, and come back to a balance that has gone up. The
provider's outcome reaches the cashier through the gateway; the cashier hands the deposit to the
brand's wallet once and reports the capture so the brand's readers learn of it.

**Why this priority**: it is the money in. Nothing else in the casino earns until this works, and it
is the path the original's three sweepers exist to protect.

**Independent Test**: with a brand project, the shared payments project, the gateway's simulator
provider and a sub-brand carrying one credential and one deployed routing tree, create a session,
submit, drive the simulator's capture callback, and read the brand wallet's balance and the brand's
`payments.payments` facts.

**Acceptance Scenarios**:

- added `features/payments/cashier.feature`: a cashier session created under an active key answers a cashier ticket and the terminals the routing tree allows
- added `features/payments/cashier.feature`: submitting a session naming an offered terminal initiates the payment and answers the payment id and the status
- added `features/payments/deposits.feature`: a capture reported by the gateway captures the payment, confirms the deposit to the wallet once and is acknowledged only after the wallet answers
- added `features/payments/deposits.feature`: the same capture reported again leaves the payment captured, calls the wallet nothing and is acknowledged
- added `features/payments/cashier.feature`: reading a payment tells its status in the frontend's words with its amounts, currency, provider, scheme, terminal and redirect address
- added `features/payments/cashier.feature`: a cashier session that cannot be routed is refused naming why
- added `features/payments/deposits.feature`: a capture for a sub-brand of type payments that names no brand is captured with no wallet called
- added `features/payments/deposits.feature`: a capture signed under the payment provider's webhook secret and stamped within 300 seconds is accepted
- added `features/payments/deposits.feature`: a capture from a payment provider with no webhook secret is accepted unsigned
- added `features/payments/deposits.feature`: a capture carrying one of the signature and the stamped time without the other is refused as missing its signature
- added `features/payments/deposits.feature`: a capture stamped with a time older than 300 seconds or whose signature does not match is refused and moves nothing
- added `features/payments/deposits.feature`: a signed capture arriving while the secret cannot be read is answered as unavailable for now, never as unauthenticated

---

### User Story 2 - A deposit that fails, expires or is abandoned resolves for the player (Priority: P1)

A player who closes the dialog, walks away from the provider's page, or whose payment the provider
declines, sees a resolved status with a reason rather than a spinner, and the brand learns of the
outcome through the same route the capture used.

**Why this priority**: an unresolved attempt is the failure mode the original's expiry sweep was
written for; a deposit the provider took but reported as abandoned is money lost silently.

**Independent Test**: create and submit a session with the simulator, then drive each of the fail
callback, the deadline, and the player's abandon, and read the payment and the brand's facts.

**Acceptance Scenarios**:

- added `features/payments/deposits.feature`: a failure reported by the gateway fails the payment with the provider's code and reason and reaches the brand
- added `features/payments/deposits.feature`: a payment past its deadline whose provider session is resolved or unknown expires with its reason and reaches the brand
- added `features/payments/deposits.feature`: a payment past its deadline whose outcome the gateway still holds undelivered is not expired and waits again
- added `features/payments/deposits.feature`: a player abandoning the session with its cashier ticket fails the payment as abandoned and reaches the brand
- added `features/payments/deposits.feature`: a failure, an expiry or an abandon arriving for a captured payment changes nothing and is acknowledged
- added `features/payments/deposits.feature`: a payment past its deadline that nothing has yet touched reads as expired with its reason
- added `features/payments/cashier.feature`: submitting a session already submitted returns the first submit's answer and makes no second payment

---

### User Story 3 - A captured deposit reaches the wallet exactly once, however the wallet answers (Priority: P1)

The brand's wallet may be unreachable, slow, or may refuse the deposit (an unknown wallet, a currency
the player has no wallet in). The cashier keeps trying until the wallet answers, never credits twice,
and makes a deposit that cannot be credited visible rather than silent.

**Why this priority**: this is the original's `reconcile-captured-payments` sweep and its
three-attempt marker concepts, folded into the workflow. The money has already moved at the provider;
only the credit is at stake.

**Independent Test**: capture a payment with the brand's wallet stopped, start the wallet, and read
the balance once; capture a payment for a currency the player has no wallet in and read the payments
view.

**Acceptance Scenarios**:

- added `features/payments/deposits.feature`: a payment captured while the wallet is unreachable is credited once when the wallet comes back, and acknowledged only then
- added `features/payments/deposits.feature`: a capture whose wallet answer was lost in flight is confirmed again under the same payment id and credited once
- added `features/payments/deposits.feature`: a capture the wallet refuses for a wallet the player does not hold stays captured, stops retrying and is visible for an operator to retry
- added `features/payments/deposits.feature`: a payment captured and unacknowledged for longer than the installation's bound is counted on the shared payments' status

---

### User Story 4 - An operator sets a sub-brand up to take deposits (Priority: P2)

An operator with the right scope creates a holding, a company under it and a sub-brand under that,
registers a payment-provider credential or a terminal, generates a merchant key for the brand's
frontend, writes a routing tree and deploys it, simulates a deposit against it, and can roll the tree
back or retire it. A sub-brand with no tree of its own takes its company's, then its holding's.

**Why this priority**: onboarding is scenario 12 of the original's end-to-end suite and every pilot
brand goes through it once.

**Independent Test**: run the onboarding chain with an operator token and then create a session.

**Acceptance Scenarios**:

- added `features/payments/hierarchy.feature`: a holding, a company and a sub-brand are created each recording its parent
- added `features/payments/hierarchy.feature`: a credential registered for a payment provider is listed without its secrets and brings a terminal of the same name that takes deposits only
- added `features/payments/hierarchy.feature`: a terminal created for a payment provider exists inactive, takes deposits, and can be changed or deleted
- added `features/payments/hierarchy.feature`: a generated cashier key is shown raw once and listed by id and name thereafter
- added `features/payments/hierarchy.feature`: deploying a draft routing tree makes it live version one, and a later deploy is version two with a rollback to one possible
- added `features/payments/hierarchy.feature`: a sub-brand with no routing tree of its own is governed by its holding's, and the answer says which level it came from
- added `features/payments/hierarchy.feature`: simulating a deposit against a routing tree names the terminals it reaches or that nothing matched
- added `features/payments/hierarchy.feature`: an operator of one company is refused a sub-brand of another company as outside their level
- added `features/payments/hierarchy.feature`: an operator of one company is served a sub-brand of their own company

---

### User Story 5 - An operator sees the transactions page and its summary (Priority: P2)

The backoffice lists a sub-brand's payments newest first with search, a status filter and pages, and
shows totals: counts by status, captured volume per currency, a daily series and a per-provider
series. An operator sees only the sub-brands their scope covers.

**Why this priority**: it is how finance reconciles with the providers today.

**Independent Test**: capture, fail and expire payments on two sub-brands and read the page with two
operators of different scope.

**Acceptance Scenarios**:

- added `features/payments/summary.feature`: payments filtered to captured are listed newest first with their fields and the counts by status
- added `features/payments/summary.feature`: the summary sums captured volume per currency, counts the daily and per-provider series, and counts a captured row with no amount separately
- added `features/payments/summary.feature`: an operator of one company is refused the payments of a sub-brand of another company
- added `features/payments/summary.feature`: a sub-brand with no parent is served to the installation level only

---

### User Story 6 - Operators are provisioned, invited and scoped (Priority: P3)

A platform or holding operator creates another operator with a role and a scope, who receives an
invitation, accepts it, sets a password and is thereafter an operator of exactly that scope. An
operator can be re-invited, sent a password reset, have their scope changed by someone whose scope
contains the new one, or be removed.

**Why this priority**: it is kept as the original does it; nothing in the pilot changes it.

**Independent Test**: `features/payments/operators.feature` against a fake realm, then the replay of
scenario 12's full chain, whose steps 1 to 3 touch the realm directly and are stood in for.

**Acceptance Scenarios**:

- added `features/payments/operators.feature`: an operator created with a role and a level exists pending with its ancestors and is sent an operator invitation
- added `features/payments/operators.feature`: a pending operator who accepts the operator invitation and sets a password is active at their level
- added `features/payments/operators.feature`: an operator cannot create an operator above their own level or with more privilege than their own
- added `features/payments/operators.feature`: an operator disabled at the sign-in reads as disabled in the shared payments

---

### User Story 7 - A crypto deposit that settles for a different amount is credited for what arrived (Priority: P3)

A player paying in crypto may send less or more than invoiced. The provider completes either way and
reports what arrived and its valuation. An underpayment credits the valuation and keeps the invoiced
amount on record; an overpayment credits the invoice and records the surplus for reconciliation.

**Why this priority**: it is the one place the credited amount is not the requested one, and the
original refuses a capture it cannot trust rather than credit it.

**Independent Test**: drive the simulator's capture with a variance body.

**Acceptance Scenarios**:

- added `features/payments/deposits.feature`: a crypto underpayment credits the valuation and keeps the invoiced amount on record
- added `features/payments/deposits.feature`: a crypto capture whose valuation cannot be trusted is refused and left undelivered at the gateway
- added `features/payments/deposits.feature`: a crypto overpayment credits the invoiced amount and records the surplus
- added `features/payments/deposits.feature`: a captured underpayment delivered again keeps the invoiced amount on record

---

### Edge Cases

- A capture arrives for a payment id that does not exist: acknowledged and ignored, as the original
  does, since a provider that is told 4xx may stop retrying a real one.
- A capture and a failure race for the same payment: the first to be recorded wins; the second is
  acknowledged and ignored. The workflow serialises them; the original could not.
- A provider's callback carries a signature header without a timestamp, or a timestamp outside 300
  seconds: refused as missing or invalid token; a provider with no webhook secret configured is
  accepted unsigned, as the simulator is today.
- The credential store cannot be read when a signed callback arrives: answered as temporarily
  unavailable, never as unauthorised, so the provider retries.
- Two usable credentials exist for one provider on a sub-brand: the first is used and the status
  names the rest as unreachable, as the original warns.
- The gateway rejects the initiate with a player-safe message ("amount below the provider's
  minimum"): the submit is refused as `PAYMENT_REJECTED` with that message; any other gateway error
  is a generic failure.
- A `CRYPTO`-scheme terminal is chosen with no crypto currency: refused as `missing_crypto_currency`.
- A routing tree with a cycle: the walk stops at the first repeated node and reports no match.
- The sub-brand names brand "acme" and the session's customer id names "bravo": refused.
- A merchant key is disabled: sessions with it are refused; payments already in flight complete.

## Requirements *(mandatory)*

### Functional Requirements

**Hierarchy**

- **FR-001**: The service MUST keep holdings, companies, sub-brands, terminals and operators as event
  sourced entities keyed by their ids; a child MUST record its parent's id and a parent MUST NOT list
  its children. A holding and a company MUST have a name and a status of `Active`, `Suspended` or
  `Archived`; a sub-brand MUST have a name, a type of `Payments`, `Casino` or `Combined`, a status,
  and, when its type is `Casino` or `Combined`, the slug of the brand project it belongs to.
- **FR-002**: The service MUST serve `/v1/holdings`, `/v1/companies`, `/v1/holdings/{id}/companies`,
  `/v1/sub-brands` and `/v1/merchants/{id}` with the original's create, list, read and status-change
  shapes, under operator tokens, and MUST refuse a read or change outside the operator's scope as
  `scope_violation`. A view MUST serve children by parent for the backoffice tree.
- **FR-003**: A brand's manifest (spec 001 FR-007) MUST create its holding, company and sub-brand with
  the ids the original derives (`seed.holding.<brand>`, `seed.company.<brand>`,
  `seed.sub-brand.<brand>` as name-based UUIDs, decision 0050), so operators' scope ids and partner
  tokens issued today keep working; applying the manifest again MUST change nothing.

**Merchant keys, credentials and terminals**

- **FR-004**: A sub-brand MUST hold merchant keys, each with an id, a name, a digest of the raw key,
  an active flag and a creation time. Generating a key MUST return the raw value exactly once; the
  raw value MUST be kept nowhere, and the digest MUST resolve to the sub-brand in one lookup through an
  index this service keeps and serves (FR-005), never a scan of every sub-brand's keys; the validation
  itself is `ingest`'s (FR-013, FR-033).
- **FR-005**: The service MUST publish on `payments.merchants` (spec 001 FR-011) a
  `merchant_key_issued` fact for every key generated and a `merchant_key_deactivated` fact for every
  key disabled, each carrying the key id, the sub-brand and the digest and never a raw key, so that
  `ingest` (spec 006) verifies keys from its own cache; and MUST serve `ingest`, by its platform
  identity, a read of the active key index for its refresh, used in no request path.
- **FR-006**: A sub-brand MUST hold payment-provider credentials, each with an id, a provider, a name,
  an environment label, an optional scheme, a status of `active` or `disabled`, and its secrets.
  Secrets MUST be kept in the service's secret store (ankka 038) under the credential's id and never
  in an event or a view; a listing MUST show no secret and a masked preview at most. Creating a
  credential MUST also create a terminal of the credential's name that takes deposits and not
  withdrawals, and a credential already existing for the same provider, name and environment MUST be
  returned rather than duplicated.
- **FR-007**: A sub-brand MUST hold terminals, each with an id, a gateway, a scheme, a name, regions,
  properties, a status of `ACTIVE` or `INACTIVE`, and whether it takes deposits, takes withdrawals and
  requires a payout destination; a terminal created without a direction MUST take deposits and not
  withdrawals. Properties that are secrets MUST be kept as FR-006 keeps a credential's.
- **FR-008**: The service MUST serve `psp-gateway` (spec 005), by its platform identity, the usable
  credentials for a sub-brand and provider — the first active credential with secrets — and a
  terminal's properties, so the gateway holds no credential of its own.
- **FR-009**: The service MUST serve the gateway catalogue the original serves
  (`/v1/catalog/gateway/{gateway}`: schemes and their required properties, including which are
  multiline and, new, which are secrets, so FR-007 knows which properties go to the secret store), and `/v1/payments/terminals?merchantId=` listing a sub-brand's credential-named terminals
  and its terminals together, each saying which directions it takes.

**Routing rules**

- **FR-010**: A routing tree MUST be an event sourced entity per holding, company or sub-brand holding a
  draft, a live version, and every version deployed with its deployment time. Deploy, simulate,
  rollback to a version and retire MUST be commands, each an event, under operator tokens scoped to
  the level's entity.
- **FR-011**: A session MUST be governed by the sub-brand's live tree, else its company's, else its
  holding's, and the session's answer MUST name the level and entity the tree was resolved from.
- **FR-012**: A tree MUST be evaluated from its root, taking the first branch whose conditions all
  hold, until a node with terminals is reached; a condition MUST be one of `AMOUNT` or `DEPOSIT_COUNT`
  compared by `LT`, `LTE`, `GT`, `GTE`, `EQ` or `NEQ` to a whole number, or `CURRENCY`, `COUNTRY` or
  `CUSTOMER_ID` compared by `EQ`, `NEQ` or `IN` to a value ignoring case. A node visited twice MUST end
  the walk as no match. The deposit count MUST be the player's captured deposits at that sub-brand.

**The cashier**

- **FR-013**: The create-session command MUST be served to `ingest` (spec 006) by its platform
  identity, naming the sub-brand `ingest` resolved from the merchant key and never one the body
  names. It MUST take an amount in minor units, a currency, the merchant's reference (the
  transaction id `ingest` verified or minted), a redirect address, a country, a customer id, and
  the customer fields the original takes (email, first and last name, phone, mobile, province, the
  network address `ingest` read, country, city, address, post code, state). It MUST refuse a missing
  country or customer id as `MISSING_ROUTING_CONTEXT`, no tree as `NO_ROUTING_TREE`, and no terminal
  reached or none active as `NO_ROUTING_MATCH`; an invalid merchant key is refused by `ingest` and
  never reaches this service.
- **FR-014**: A session MUST answer with a token, the transaction id, the redirect address and the
  available terminals, each with its id, name and scheme, and for a terminal its gateway and its
  supported currencies when its `supportedCurrencies` property names them (comma-separated, as the
  original's `CashierRoutes.scala:1551-1565`). A session MUST be kept as a key value entity under its
  token with everything the submit needs, and MUST expire with the payment's deadline.
- **FR-015**: The submit command, served to `ingest` by its identity with the session's token, MUST
  take the chosen terminal, an optional crypto currency, and the customer fields a player may enter at payment time (which MUST override
  the session's). It MUST resolve the terminal as a credential name first, else as an active terminal
  (`terminal_not_found` otherwise), substitute the crypto currency for a `CRYPTO` scheme
  (`missing_crypto_currency` when absent), mint a payment id, and ask the gateway to initiate a
  hosted-page payment with the amount, currency, scheme, the capture callback for that payment id,
  the provider, the sub-brand, the customer id, the redirect address, the customer fields, the
  terminal's properties and the terminal's id. A session submitted twice MUST be answered with the
  first submit's answer and MUST make no second payment (the scenario US2 names; spec 006 FR-010;
  corrected in planning from "refused", 2026-10-12).
- **FR-016**: A payment MUST be an event sourced entity keyed by the payment id, holding the sub-brand,
  the customer id, the brand (from the sub-brand), the player id, the amount in minor units, the
  currency, the provider, the provider's transaction id, the provider's redirect address, the
  terminal, the scheme, the status, the creation time, the deadline, and the crypto fields when the
  provider returned a wallet address. Its statuses MUST be exactly the original's: `AWAITING_REDIRECT`,
  `AWAITING_CRYPTO`, `captured`, `failed`, `expired`, the last three terminal.
- **FR-017**: The payment read, served to `ingest` by its identity (and to the operator realm from the
  backoffice), MUST answer the status in the frontend's words (`COMPLETED`, `FAILED`, `EXPIRED`, else the stored status), the amount as a decimal string and
  in minor units, and every field the original answers; a non-terminal payment past its deadline MUST
  read as `EXPIRED` with reason "Payment not completed" before any timer has fired.
- **FR-018**: `POST /v1/payments/{id}/capture` and `/fail` MUST be served to `psp-gateway` by its
  platform identity. When the payment's provider has a webhook secret, the capture MUST carry
  `X-PSP-Signature` and `X-PSP-Timestamp`, the timestamp MUST be within 300 seconds, and the HMAC-SHA256
  of the raw body under the secret MUST match in constant time; one header without the other, or a
  mismatch, MUST be refused. When the secret cannot be read the answer MUST be temporarily
  unavailable, never unauthorised. A provider with no secret MUST be accepted unsigned.
- **FR-019**: A capture or failure for a payment that does not exist MUST be acknowledged and ignored;
  one for a terminal payment MUST be acknowledged and change nothing.

**The deposit workflow**

- **FR-020**: Every payment MUST be driven by one workflow keyed by the payment id, started at submit,
  with these steps: `initiate` (the gateway call, idempotent by the payment id); `await-outcome`
  (paused until a capture, a failure or an abandon arrives, with a timer at the deadline); `expire`
  (on the timer: ask the gateway for the provider session's status; when it reports the outcome
  undelivered, wait another deadline and say so in the status; otherwise record `expired` with reason
  "Payment not completed"); `credit` (on capture: call the brand wallet's deposit route, retrying
  until it answers); end. Every outcome but the capture reaches the brand through FR-022's consumer,
  not a step (planning 2026-10-12: a workflow runs one step at a time, and a report's retries must
  not hold the deadline's pause).
- **FR-021**: The `credit` step MUST call the brand wallet's deposit route under the grant of spec 001
  FR-016 with the payment id as the idempotency key, carrying the amount to credit in minor units,
  the currency, the provider's transaction id as the payment reference, the terminal, the provider
  and the scheme. It MUST retry with backoff until the wallet answers, MUST treat a repeated call as
  the same deposit, and MUST NOT acknowledge the capture to the gateway until the wallet has answered.
  A refusal the wallet states as final (no such wallet, a currency mismatch, or a
  responsible-gambling refusal — excluded, cooling off, over a limit, spec 002 FR-022) MUST end the
  retries, record the reason and, for a limit, the remaining allowance, leave the payment captured and
  not credited, show the player the reason in their currency, and refund it through the provider
  without an operator (spec 010 FR-013, settled 2026-10-25).
- **FR-022**: A consumer of the payment's events MUST call the brand wallet's outcome route (spec 002
  FR-022a) for `initiated`, `failed`, `expired` and `abandoned`, keyed by the payment id and the
  outcome, carrying the payment provider and the failure code and reason where there is one, retried
  until acknowledged; the capture hand-off (`credit`) MUST carry
  the provider likewise, so every `payments.payments` family the wallet produces names it (spec 002
  FR-022a, spec 015 FR-011); the `refunded` and dispute outcomes are spec 005's to report, with the
  provider, when a provider reports them. A sub-brand that names no brand MUST skip `credit` and the
  outcome consumer.
- **FR-023**: A view MUST list captured payments not yet credited, each with the time captured, the
  attempts made and the wallet's last answer; an operator MUST be able to retry one from it; and the
  project's status MUST count those older than the installation's bound (default 15 minutes).
- **FR-024**: The abandon command, served to `ingest` by its identity with the session's token (spec
  006 FR-006), MUST fail the session's payment with code `player_abandoned` and reason "Payment not
  completed", and MUST change nothing for a terminal payment.
- **FR-025**: A failure MUST record the provider's code and reason (default "Payment could not be
  processed"), and the crypto received amounts when reported; an expiry MUST record the time and the
  reason; a capture MUST record the time.
- **FR-026**: A settlement variance on a capture MUST be `UNDERPAYMENT` or `OVERPAYMENT` with the
  received crypto amount, the valuation and its currency, recorded on the payment. An underpayment
  MUST credit the valuation, keep the invoiced amount as the original amount on the first capture
  only, and MUST be refused — the capture not acknowledged — when the valuation is not a positive
  decimal, exceeds the invoiced amount, carries no currency, or carries a currency other than the
  payment's. An overpayment MUST credit the invoiced amount.

**Operators**

- **FR-027**: The service MUST provision operators of the payments backoffice into Keycloak's `eitheror`
  realm with an email, a role, a scope level (`platform`, `holding`, `company`, `sub_brand`) and a scope
  id, recording the scope's ancestors; MUST send an invitation through the engagement provider
  configured on this service for the installation (a shared project holds no grant into a brand's
  `engagement`; planning 2026-10-12); MUST serve accept-invitation, resend-invitation, reset-password, scope change
  and removal; and MUST refuse creating or changing an operator to a role of privilege not below the
  caller's or to a scope the caller's does not contain, by the original's privilege levels.
- **FR-028**: The service MUST mirror an operator's enabled flag from the realm on a recurring timer
  (ankka 032), replacing the original's in-process syncer, and MUST list operators by the caller's
  scope.

**Backoffice reads**

- **FR-029**: `GET /v1/payments/cashier` MUST page a sub-brand's payments newest first with a status
  filter (`all`, `pending`, `captured`, `failed`, `expired`; every non-terminal status counts as
  pending), a search over payment id, customer id and the provider's transaction id, and counts by
  status; `GET /v1/payments/cashier/summary` MUST answer the count, counts by status, captured volume
  per currency in minor units, a daily series and a per-provider series, counting captured rows with
  no amount separately. Both MUST serve only sub-brands the operator's scope covers and MUST serve a
  sub-brand with no parent to platform scope only.
- **FR-030**: *Struck in planning (2026-10-12): it contradicted FR-036, written in a later
  reconciliation, which drops the risk routes as a stub. FR-036 stands.*

**Money, identity and data**

- **FR-031**: Every amount MUST be a `Long` of minor units (spec 001 FR-026); the two-decimal string the
  frontend and backoffice read MUST be produced on the wire from it and never stored. An amount that
  does not fit, or a decimal on a route that takes minor units, MUST be refused naming the field.
- **FR-032**: The service MUST hold, about a player, the player id and the customer fields a deposit
  carries to the provider; every customer field MUST be a `Personal` value (ankka 042) under the
  player's subject, held on the session and the payment for the provider's needs and the deadline, and
  nothing else about the player MUST be held (spec 001 FR-018). The erasure handler MUST cover sessions
  and payments.
- **FR-033**: Operator tokens MUST be verified through ankka 022 against the `eitheror` realm and scope
  checked through the hierarchy (decision 0050: `eitheror_scope_level` and `eitheror_scope_id`, never
  `customer_id`); the capture, fail, credential and key-index routes MUST be served by caller
  identity; the cashier commands MUST be served to `ingest` by its identity, and the merchant key is
  `ingest`'s to verify (spec 006), never this service's.
- **FR-034**: The service MUST write an audit record (spec 001, `platform.audit`) for every hierarchy
  change, key generation and disablement, credential and terminal change, routing deploy, rollback and
  retirement, operator change, and every payment outcome.

**Recordings and parity**

- **FR-035**: Every requirement above MUST be established by the original's code, the named Go
  scenario, or the named unit suite (spec 001 FR-031); the rebuilt service MUST carry one parity test
  per recording in *Recordings* (spec 001 FR-032).

**Dropped under constitution I**

- **FR-036**: The risk blocklist and config (`RiskRoutes`) MUST NOT be carried over: the original
  stores them and nothing reads them when a deposit is made, which makes them a stub; the backoffice
  page that edits them is out of scope. Enforcement, if ever wanted, is a new feature with its own
  spec.
- **FR-037**: The payments API the original serves beside the cashier — `authorize`, `capture`,
  `process_payment`, `refund`, `mark_disputed`, the `Payment` and `Refund` twins, the fraud call in
  the authorization function — MUST NOT be carried over: spec 005 found its card routes served by
  the in-process stub only, spec 006 found no live route under it, and nothing live uses it. Go
  scenarios 8 and 9 and the probes of scenario 10 cover that stub and are not replayed (Recordings).

### Key Entities

- **Holding**, **Company**, **Sub-brand**: the hierarchy; a sub-brand names its company, its holding,
  its type, and the brand project it belongs to when it is a casino's.
- **Merchant key**: a sub-brand's credential for its frontend; id, name, digest, active, created;
  verified by `ingest` from this service's key facts.
- **Credential**: a provider account a sub-brand holds; secrets in the secret store.
- **Terminal**: a way deposits or withdrawals go through a gateway; properties, directions, status.
- **Routing tree**: versioned rules at one level of the hierarchy; draft, live, history.
- **Session**: a cashier session; the player, the amount, the currency, the terminals offered.
- **Payment**: one deposit attempt; the statuses, the outcome, the variance, the provider's ids.
- **Deposit workflow**: one per payment; its step is the payment's progress toward the wallet.
- **Operator**: a backoffice user in the `eitheror` realm; role, scope level, scope id, ancestors.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The original's scenarios 12, 13 and 14, recorded and replayed through ankka-port, pass
  against the rebuilt service with only base URLs changed.
- **SC-002**: A deposit captured while the brand's wallet is down for ten minutes is credited exactly
  once within one minute of the wallet returning, with no operator action and no sweep.
- **SC-003**: No deposit the provider captured is ever recorded as expired or abandoned: a thousand
  captures with the gateway marking one in ten outcomes undelivered leave zero payments expired that
  the provider captured.
- **SC-004**: A capture delivered ten times credits once and raises the deposit count once.
- **SC-005**: The three CronJobs (`expire-stale-payments`, `reconcile-captured-payments`,
  `redrive-undelivered-outcomes`) and the reactive function have no counterpart in the rebuilt
  deployment, and the shared project's own Kafka topic is read by no brand.
- **SC-006**: A merchant key validates in one lookup regardless of how many sub-brands exist.
- **SC-007**: No secret appears in any event, view row, log line or span of the service.

## Changes from the original

- The concept assignment, the reactive function `deposit-on-payment-capture`, the `casino-service`
  `notify/deposit` call, the hourly reconcile sweep with its numbered marker concepts, the five-minute
  expiry sweep and the gateway's two-minute redrive are replaced by one workflow per payment with
  timers; the wallet is called directly under a grant and keyed by the payment id rather than the
  provider's transaction id.
- The capture is acknowledged to the gateway only after the wallet has answered; the original
  acknowledged after the twin write and the concept assignment.
- The internal routes `casino-service` called — a player's payment attempts, and abandon — are dropped;
  the brand builds its view from `payments.payments`, which gains `payment.initiated`, and the player
  abandons on the cashier with the session token.
- The gateway's internal secret header is replaced by caller identity; the provider HMAC is kept.
- Merchant keys resolve by digest lookup rather than a scan of every sub-brand's key list, and the
  verification itself moves to `ingest` (spec 006), fed by this service's key facts (FR-005).
- The merchant-facing routes (create and submit a session, read a payment, abandon) are `ingest`'s;
  this service serves the commands behind them by `ingest`'s identity.
- Dropped as stubs: the risk blocklist and config (FR-036) and the payments API with its fraud
  assessment (FR-037).
- Credentials' and terminals' secrets move from twin properties to the secret store (ankka 038).
- Amounts are `Long` minor units; the 32-bit `amountCents` and the stored decimal string are gone.
- A session submitted twice is answered with the first submit's answer (the original has no guard on
  this cashier and would initiate a second payment).
- The gateway catalogue marks which required properties are secrets (FR-009), and a terminal's
  secret properties live in the secret store.
- The credential routes that carried three path parameters
  (`/v1/merchants/{id}/psp-credentials/{provider}/{name}[/activate|/disable]`) take the name as the
  query parameter `name`, since an ankka route takes at most two; the payments backoffice frontend
  changes these paths (spec 019 carries it).
- The outcomes other than the capture reach the brand from a consumer of the payment's events rather
  than a workflow step (FR-020, FR-022); what the brand observes is unchanged.
- The in-process operator status syncer becomes a recurring timer; the invitation email goes through
  the installation's engagement provider rather than a Customer.io key held by this service.
- Moved, not dropped: ST8 credentials (spec 003 FR-026), holding-level CRM config (spec 014), the
  seeded `kyc-policy` (spec 009), the sub-brand's withdrawal turnover fields (spec 002 FR-004a).
- Dropped: `DevSeedRoutes` (the manifest creates the hierarchy, FR-003); the `payment.pending` concept
  and the `platform.event.ingest` publish (nothing in a brand reads them once the wallet produces the
  brand's facts); the legacy `eitheror-merchant` identity and its reconciliation.

## Recordings

Go end-to-end scenarios (`tests/e2e/`), each a recording under spec 001 FR-030:
`TestScenario12_OnboardingGate2_ServicesHealthy`, `TestScenario12_OnboardingGate3_FullChain` (US4, US6,
US1), `TestScenario13_DreamzPay_FullChain` (US1 with a real provider adapter), and
`TestScenario14_CasinoDeposit_PlayerToWallet` (US1 scenario 3, the credit). Planning (2026-10-12)
found scenario 12's full chain skipped in the original and its routing-rules calls, shared with
scenario 13, stale against the route (`{"subBrandId"}` and `?merchantId=` where the route takes
`level` and `entityId`); `tests/original/e2e-merchant.patch` unskips it and corrects the calls, and
scenarios 13 and 14 run with the dreamzpay stub's `DREAMZPAY_*` values. The cashier behaviours the
suite never reaches (US2, US3, US7, FR-018) are recorded by `tests/original/merchant-driver/`, one
scenario per feature scenario, against the original's cashier with its simulator provider
(`contracts/recording.md`). Scenarios 8 and 9 and
the probes of scenario 10 cover the dropped payments API (FR-037) and are not replayed; scenario 10's
cashier tests, skipped today, are spec 006's recordings once unskipped.

Recorded 2026-10-18 from the original at `659c2d6b` on its KinD stack (T019; `tests/port/README.md`):
the four Go scenarios and the driver's 18, each twice on fresh ids, every one passing on both runs.
Findings, none of them requirements:

- A DreamzPay terminal registered through `psp-credentials` cannot take a deposit: the submit
  answers `500 psp_error_502`, because the cashier sends a credential's terminal with no properties
  and the gateway has refused that since `2add2353` (2026-08-03). Terminals made as gateway
  integrations carry their credentials and work; scenarios 12 to 14 were changed to make them so.
  This bears on FR-006 and FR-007 (whether a `psp-credential` is carried over as a deposit path at
  all) and on spec 005, and is a question for the plan, not settled here.
- Scenarios 13 and 14 asserted `MemberID` on the relay page (renamed `memberId` in `8361fa09`) and a
  major-unit wallet balance (the wallet twin holds minor units); scenario 14 logged in a player
  Keycloak still held unverified. Each was corrected in `e2e-merchant.patch`.

Unit suites of the original whose cases become parity tests: `CashierRoutesSpec`,
`CashierCaptureDurabilitySpec` (US3), `CashierFailureOutcomeSpec` (US2),
`CashierSettlementVarianceSpec` (US7), `CashierCaptureAuthSpec` (FR-018),
`CashierPlayerErrorForwardingSpec` (the edge case on player-safe messages), `TenantIsolationSpec`
(US4 scenario 8, US5 scenario 3), `RoutingTreeRoutesSpec` (US4), `MerchantKeyValidationRouteSpec`
(FR-005), `PspCredentialLifecycleSpec`, `TerminalCredentialStateRoutesSpec`,
`TerminalDeleteRoutesSpec` (FR-006, FR-007), `CashierAdminRoutesSpec` (US5),
`OperatorSurfaceActorTypeSpec`, `OperatorInviteEmailServiceSpec`,
`OperatorInvitationTokenStoreSpec` (US6).

## Assumptions

- `psp-gateway` (spec 005) keeps the hosted-page initiate contract the original's `HppInitiateReq` and
  `HppInitiateResp` define, including the player-safe error marking, and keeps calling the capture
  and fail routes with the provider's HMAC headers passed through; its undelivered-outcome marker
  becomes a status the workflow can ask for.
- The brand wallet's deposit route (spec 002 FR-022) takes the provider's transaction id as the
  payment reference and the payment id as the key; the original's wallet deduplicated on the
  provider's transaction id, so a payment whose provider reports a new transaction id on retry is a
  new deposit there and the same deposit here, which is the correct reading.
- The engagement provider for operator invitations is the installation's; the original used a
  Customer.io app key held by this service with two transactional templates.
- The payments backoffice frontend reads the same routes it reads today with only its base URL changed
  (constitution, scope).

## Dependencies

- Spec 001 FR-011 and spec 002 FR-022a carry the `payment.initiated` outcome and family; spec 001
  FR-021 extends the realm-administration exception to `merchant` for the `eitheror` realm; spec 001
  FR-020 names the merchant key as the admission of the cashier's public routes on `ingest`; spec
  001 FR-011 lists `payments.merchants` (reconciled 2026-10-08).
- Spec 002 (the deposit and outcome routes), spec 005 (the gateway), spec 006 (`ingest`: the
  merchant-facing routes and the key cache), spec 003 (ST8 credentials), spec 009 (the KYC policy),
  spec 014 (CRM config).
- ankka 040 (the grants to the brand's wallet), 038 (secrets), 032 (recurring timers), 042 (`Personal`
  customer fields), 022 (operator tokens).

## Out of scope

- Withdrawals and payouts (spec 007 and spec 005's payout sessions).
- The payments API and its fraud assessment, and the risk blocklist: dropped (FR-036, FR-037).
- The merchant-facing routes and the merchant key's verification (spec 006).
- The backoffice frontend and the player frontend's cashier proxy, which change only base URLs.
