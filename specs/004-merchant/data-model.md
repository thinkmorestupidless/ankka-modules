# Data model: merchant

Components of the one service `merchant` in the shared `payments` project. Money is `MinorUnits`
(contract.money), currencies `Currency`. The only personal fields are the customer fields of a
session and a payment and an operator's email, each `Personal` (research R12). Component kinds
follow research R2 and R7.

## Holding, Company (event sourced; id = the entity's id)

| Field | Type | Notes |
|---|---|---|
| id | String | a UUID; `UUID.nameUUIDFromBytes("seed.holding.<slug>")` when seeded (R15) |
| name | String | |
| status | `Active` \| `Suspended` \| `Archived` | |
| holdingId | String | company only: its parent |
| createdAt | Instant | |

Events: `Created(name, parent?, actor)`, `StatusChanged(status, actor)`. Commands: `create`
(a second create with the same content records nothing; different content is `Conflict`),
`changeStatus`, `read`. A parent lists no children (FR-001): `HierarchyView` does.

## SubBrand (event sourced; id = the sub-brand id)

| Field | Type | Notes |
|---|---|---|
| id, companyId, holdingId | String | the parent and grandparent |
| name | String | |
| type | `Payments` \| `Casino` \| `Combined` | |
| status | `Active` \| `Suspended` \| `Archived` | |
| brand | Option[String] | the brand project's slug; required for `Casino` and `Combined`, absent for `Payments` (Clarifications) |
| keys | Vector[MerchantKey] | `keyId`, `name`, `digest` (hex SHA-256 of the raw key), `active`, `createdAt`; never the raw key |
| credentials | Vector[CredentialRecord] | `id`, `provider`, `name`, `environment`, `scheme?`, `status` (`active` \| `disabled`), `createdAt`; secrets in the store at `credential/<id>` (R9) |
| createdAt | Instant | |

Events: `Created`, `StatusChanged`, `KeyGenerated(key)`, `KeyDisabled(keyId)`,
`CredentialRegistered(record)`, `CredentialSecretsChanged(id)` (the event names the id, never a
value), `CredentialStatusChanged(id, status)`, `CredentialDeleted(id)`; each with `actor`.

Rules: `generateKey` returns the raw key once in the reply and persists the digest (FR-004); a
credential existing for the same `(provider, name, environment)` is returned, not duplicated
(FR-006); registering a credential also creates a `Terminal` named after it that takes deposits and
not withdrawals (the command's effect sends the terminal its `create`; FR-006); the usable
credential for a provider is the first `active` one with secrets present (FR-008; a second is
named as unreachable in the status, Edge cases).

## MerchantKeyIndex (key value; id = the digest)

`subBrandId`, `keyId`, `active`. Written by the `KeyIndexer` consumer from `KeyGenerated` and
`KeyDisabled`. Read by nothing in a request path of this service (the sub-brand is resolved by
`ingest`); it is FR-004's direct lookup and the parity suite's way to resolve a recorded key.

## Terminal (event sourced; id = the terminal id)

The terminal id is the original's: `<subBrandId>_<gateway>_<scheme>_<name>`, whitespace replaced by
`_` (`sb_ad9a56ec_DREAMZPAY_DREAMZPAY_e2e-dreamzpay`).

| Field | Type | Notes |
|---|---|---|
| id, subBrandId | String | |
| gateway | String | the provider (`DREAMZPAY`, …) |
| scheme | String | from the catalogue |
| name | String | |
| regions | Vector[String] | |
| properties | Map[String, String] | non-secret properties; secret ones (the catalogue's `secret` keys) at `terminal/<id>` in the store; `supportedCurrencies`, comma-separated, is what a session offers (FR-014) |
| status | `ACTIVE` \| `INACTIVE` | created `INACTIVE` (FR-007); a credential's companion terminal is `ACTIVE` |
| supportsDeposit, supportsWithdrawal, requiresPayoutDestination | Boolean | default `true`, `false`, `false` (ADR 0038) |
| fromCredential | Option[String] | the credential id when it is a credential's companion |
| deleted | Boolean | a tombstone (`TerminalDeleteRoutesSpec`: cannot be resurrected) |

Events: `Created`, `Activated`, `Disabled`, `PropertiesChanged(keys)`, `CapabilityChanged`,
`Deleted`. Deleting a terminal the resolved routing tree names is refused `409`.

## RoutingTree (event sourced; id = `<level>/<entityId>`, level ∈ holding, company, subBrand)

| Field | Type | Notes |
|---|---|---|
| draft | Option[Tree] | `Tree(rootNodeId, nodes: Map[nodeId, Node(branches?, terminals?)])`, `Branch(conditions, nextNodeId)`, `Condition(type, operator, value)` as the original's `RoutingTreeState` |
| liveVersion | Option[Int] | |
| liveDeployedAt | Option[Instant] | |
| history | Vector[Version(version, deployedAt, tree)] | every deployed version |

Events: `DraftSaved(tree)`, `Deployed(version, at)`, `RolledBack(toVersion, at)`, `Retired(at)`;
each with `actor`. Deploy with no draft is `400 NO_DRAFT`; retire with nothing live is a no-op.
`simulate` is `routing.Evaluator.evaluate(tree, context)` → `Match(terminals)` | `NoMatch` | `NoTree`.

## Session (key value; id = the token; expireAfter = the deadline)

| Field | Type | Notes |
|---|---|---|
| token | String | a UUID |
| subBrandId, brand | String, Option[String] | from the sub-brand `ingest` resolved |
| transactionId | String | the merchant's reference |
| amount | MinorUnits | |
| currency | Currency | |
| customerId | String | `brand:player`; refused when its brand is not the sub-brand's (Edge cases) |
| redirectUrl, country | String | |
| customer | CustomerFields | twelve `Personal[String]` options (R12) |
| offered | Vector[OfferedTerminal] | `terminalEntityId`, `name`, `scheme`, `gateway?`, `supportedCurrencies?` |
| resolvedFrom | ResolvedFrom(level, entityId, name) | FR-011 |
| deadline | Instant | creation + `PAYMENT_DEADLINE` |
| submitted | Option[SubmitAnswer] | the first submit's answer (`paymentId`, `status`, `redirectUrl`, crypto fields); a second submit returns it (correction 2) |

## Payment (event sourced; id = the payment id)

| Field | Type | Notes |
|---|---|---|
| paymentId, subBrandId | String | |
| brand | Option[String] | from the sub-brand; `None` for a `Payments` sub-brand |
| customerId, playerId | String | `brand:player`, and the player part |
| amount | MinorUnits | the invoiced amount |
| originalAmount | Option[MinorUnits] | set once by an underpayment's first capture (FR-026) |
| creditedAmount | Option[MinorUnits] | what the wallet was asked to credit |
| currency | Currency | |
| provider (psp), scheme, terminalId | String | |
| pspTransactionId, pspRedirectUrl | String | |
| crypto | Option[Crypto(paymentAddress, cryptoAmount, cryptoCurrency, validUntil)] | |
| status | `AWAITING_REDIRECT` \| `AWAITING_CRYPTO` \| `captured` \| `failed` \| `expired` | exactly the original's words (FR-016) |
| createdAt, deadline | Instant | |
| capturedAt, failedAt, expiredAt | Option[Instant] | |
| failureCode, failureReason | Option[String] | default reason "Payment could not be processed" |
| variance | Option[Variance(kind, receivedCryptoAmount?, receivedConvertAmount?, receivedConvertCurrency?)] | |
| waitingReason | Option[String] | set when the deadline passed with the outcome undelivered (FR-020) |
| credit | Option[Credit(status: `credited` \| `refused`, attempts, lastAnswer, remaining?, at)] | FR-021, FR-023 |

Events: `Initiated`, `Captured(at, variance?, creditedAmount)`, `Credited(txnId, at)`,
`CreditRefused(code, message, remaining?, at)`, `CreditAttempted(answer)`, `Failed(code, reason,
crypto received?, at)`, `Expired(at)`, `Waiting(reason, at)`. Terminal: `captured`, `failed`,
`expired`; a command on a terminal payment records nothing and answers the state (FR-019). The
read (FR-017) answers `COMPLETED` / `FAILED` / `EXPIRED` / the stored status, and a non-terminal
payment past its deadline as `EXPIRED` with "Payment not completed" before any timer fired.

## PaymentCustomer (key value; id = the payment id; expireAfter = `CUSTOMER_RETENTION`)

`customerId`, `customer: CustomerFields` (the session's, overridden by the submit's). Deleted by the
erasure handler. Read only to build the gateway's initiate request.

## DepositWorkflow (workflow; id = the payment id)

State: `status` (`new` → `initiating` → `awaiting` → `expiring` | `waiting` → `crediting` →
`credit-waiting` → `credited` | `credit-refused` | `captured-no-brand` | `failed` | `expired`),
`submit: Option[SubmitInput]`, `capture: Option[CaptureInput]`, `attempts: Int`, `lastAnswer:
Option[String]`.

| From | Command or timeout | To |
|---|---|---|
| `new` | `start(submit)` | `initiate` |
| `initiate` done | — | `await-outcome`, paused until the deadline |
| `awaiting` | `capture(c)` | `credit` (or `captured-no-brand`, end, when the sub-brand names no brand) |
| `awaiting` | `fail(f)` / `abandon` | `failed`, end |
| `awaiting` | the deadline | `expire` |
| `expire`: session undelivered | — | `waiting`; paused another deadline → `expire` |
| `expire`: resolved or unknown | — | `expired`, end |
| `waiting` | `capture` / `fail` / `abandon` | as from `awaiting` |
| `credit`: wallet answered | — | `credited`, end |
| `credit`: wallet unreachable, 5xx, `OUTCOME_PENDING` | — | `credit-wait`, paused `min(30 s, 1 s · 2^attempts)` → `credit` |
| `credit`: a final refusal | — | `credit-refused`, end |
| `credit-refused` | `retry` (an operator) | `credit` |
| any terminal | `capture` / `fail` / `abandon` | unchanged; the command answers the state |

`start` on a started workflow is `Conflict`. Every step records its decision on the `Payment`
before transitioning. `initiate` is idempotent at the gateway by the payment id (005 FR-008).

## Operator (event sourced; id = the realm user id)

`email: Personal[String]`, `username`, `role`, `scopeLevel` (`platform` \| `holding` \| `company`
\| `sub_brand`), `scopeId`, `ancestors: Vector[String]`, `firstName?`, `lastName?` (`Personal`),
`pending`, `enabled`, `createdAt`, `createdBy`. Events: `Created`, `Invited(at)`, `Accepted(at)`,
`ScopeChanged`, `EnabledMirrored(enabled, at)`, `Removed`.

## InvitationToken (key value; id = SHA-256 of the token; expireAfter = 72 h)

`operatorId`, `email: Personal[String]`, `expiresAt`, `used`. `consume` succeeds once.

## Views

| View | Rows | Queries |
|---|---|---|
| `HierarchyView` | one per holding, company and sub-brand: id, kind, parent, name, status, type?, brand? | `companiesByHolding`, `subBrandsByCompany`, `subBrandByBrand`, `all` (platform scope) |
| `TerminalsBySubBrand` | one per terminal not deleted | `bySubBrand`, with the credential-named companions marked |
| `ActiveKeys` | one per active key: digest, keyId, subBrandId | `all` (ingest's refresh, FR-005) |
| `PaymentsView` | one per payment: every field of the `Payment` but the crypto detail, plus `credited: Boolean`, `uncreditedSince?` | `bySubBrand(status filter, search, page)` with status counts; `summary(subBrands)` (count, by status, captured volume per currency, daily, per provider, captured-without-amount); `depositCount(subBrandId, customerId)`; `uncredited(bound)`; `byCustomer(customerId)` |
| `OperatorsView` | one per operator not removed: id, email, role, scope, ancestors, pending, enabled | `inScope(scopeLevel, scopeId)` |

## Consumers

| Consumer | Source | Does |
|---|---|---|
| `KeyIndexer` | `SubBrand` events | writes `MerchantKeyIndex` for `KeyGenerated`, `KeyDisabled` |
| `MerchantFacts` | `SubBrand` events | publishes `merchant_key_issued`, `merchant_key_deactivated` on `payments.merchants` |
| `OutcomeReporter` | `Payment` events | calls the brand wallet's outcome route for `Initiated`, `Failed`, `Expired` and the abandon (a `Failed` with code `player_abandoned` reported as `abandoned`), keyed `<paymentId>/<outcome>`, with the provider; skipped for a payment with no brand (R7) |
| `AuditFacts` | the events of `Holding`, `Company`, `SubBrand`, `Terminal`, `RoutingTree`, `Operator`, `Payment` | publishes `audit_record` on `platform.audit` (R13) |

## Timed actions

| Action | Schedule | Does |
|---|---|---|
| `Seed` | once at startup, retried until done | creates each brand's holding, company and sub-brand from `MERCHANT_BRANDS` (R15) |
| `OperatorSync` | recurring, `OPERATOR_SYNC_INTERVAL` (60 s) | lists the realm's operators and mirrors `enabled` onto each `Operator` |

## Settings (R19)

`MERCHANT_BRANDS`, `PAYMENT_DEADLINE` (30 m), `UNCREDITED_BOUND` (15 m), `CUSTOMER_RETENTION`
(90 d), `OPERATOR_SYNC_INTERVAL` (60 s), `KEYCLOAK_URL`, `OPERATORS_REALM` (`eitheror`),
`ENGAGEMENT_REGION`, `OPERATOR_CONSOLE_URL`. Secret store entries: `credential/<id>`, `terminal/<id>`,
`keycloak/operators-admin`, `engagement/transactional`.
