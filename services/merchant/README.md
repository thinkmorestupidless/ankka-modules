# merchant

The shared `payments` project's service for the payments hierarchy, the cashier's commands and the
deposit workflow (spec 004): one workflow per payment from the gateway's initiate to the brand wallet's
credit. The design is `specs/004-merchant/plan.md`; the decisions and what established them are its `research.md`.

## Build and test

```bash
(cd ../.. && sbt contract/publishLocal)   # the contract this build depends on
sbt test                                  # Docker must be running: the integration kit starts Postgres
```

`just merchant-test` does both from the repository root; `just merchant-it` runs the integration and parity
suites alone. `MERCHANT_LEAK_COUNT=200 sbt "testOnly *LeakSuite"` runs SC-003's suite on fewer deposits.

## The components

| Kind | Name | What it holds |
|---|---|---|
| event sourced | `holding`, `company` | a holding, a company under it: name, status (FR-001) |
| event sourced | `sub-brand` | its parents, type, status, brand; its merchant keys by digest; its credentials' records, never their secrets (FR-004, FR-006) |
| event sourced | `terminal` | a gateway's scheme, name, regions, properties (secret ones by name only), status, directions; deleted is final (FR-007) |
| event sourced | `routing-tree` | `<level>/<entityId>`: the draft, the live version, every version deployed (FR-010) |
| key value | `session` | a cashier session by token, expiring with its deadline; the customer fields as `Personal`; the payment it made (FR-013, FR-014) |
| event sourced | `payment` | one deposit by payment id: what the workflow decided, nothing personal (FR-016) |
| key value | `payment-customer` | a payment's customer fields, for the provider, kept for the retention (FR-032) |
| workflow | `deposit` | one per payment: `initiate`, `await-outcome` (the deadline), `expire` (or wait again while the gateway holds the outcome undelivered), `record-capture`, `credit` with backoff, `record-failure` (FR-020, FR-021) |
| key value | `merchant-key-index` | a key's digest to its sub-brand, in one lookup (FR-004, SC-006) |
| event sourced, key value | `operator`, `invitation-token` | an operator of the backoffice and an invitation's token by digest, 72 hours, used once (FR-027) |
| view | `holdings`, `companies`, `sub-brands`, `terminals-by-sub-brand`, `payments`, `active-keys`, `operators`, `sessions-by-customer` | the listings; `payments` serves the backoffice page, the summary, the deposit count and the uncredited captures |
| consumer | `outcome-reporter` | every outcome but the capture, to the brand wallet's outcome route (FR-022) |
| consumer | `key-indexer`, `merchant-facts` | the key index; `merchant_key_issued`/`_deactivated` on `payments.merchants` (FR-005) |
| consumer | `*-audit` (seven) | `audit_record` on `platform.audit`, one consumer per entity (FR-034) |
| timed action | `seed` | the brands of `MERCHANT_BRANDS`, under the original's ids (FR-003) |
| timed action | `operator-sync` | recurring: the realm's enabled flags mirrored (FR-028) |

The pure rules are their own packages, each with the original's cases: `routing/Evaluator`,
`payments/Variance` and `Status`, `security/Hmac`, `operators/Scope` and `Privilege`,
`terminals/catalogue/Catalogue`.

## The routes

One endpoint per prefix (ankka 0.11 refuses two sharing one), each route's audience its ACL. The service
is exposed for its operator routes and `accept-invitation`; every other route refuses a token.

| Prefix | Audience |
|---|---|
| `/v1/holdings`, `/v1/companies`, `/v1/sub-brands`, `/v1/merchants`, `/v1/catalog` | a payments operator (`Routes.paymentsOperator`) |
| `/v1/payments` | operators (the backoffice page, summary, uncredited, retry, routing rules, terminals, metrics) and `psp-gateway` (`/{id}/capture`, `/{id}/fail`) |
| `/v1/operators` | operators; `accept-invitation` public, admitted by the invitation's token |
| `/v1/cashier` | `ingest` (spec 006) by identity |
| `/internal/v1` | `psp-gateway` by identity: a credential's and a terminal's secrets at the moment of use |
| `/erasure` | a brand's `players`, for its own players |
| `/status` | the project's services |

A credential is named by the query parameter `name` (`/v1/merchants/{id}/psp-credentials/{provider}?name=`):
the original's route took three path parameters, ankka takes two.

## Settings and secrets

`MERCHANT_BRANDS` (rendered by the brands tool from the repository's brand manifests), `PAYMENT_DEADLINE`
(30m), `UNCREDITED_BOUND` (15m), `CUSTOMER_RETENTION` (90d), `OPERATOR_SYNC_INTERVAL` (60s), `KEYCLOAK_URL`,
`OPERATORS_REALM` (`eitheror`), `ENGAGEMENT_REGION`, `OPERATOR_CONSOLE_URL`. The realm's admin credentials
come from the project secret the manifest names (`KEYCLOAK_ADMIN_*`), else from this service's store
(`keycloak/operators-admin`). The invitation provider's key is optional: `CUSTOMERIO_*` when an
installation's manifest adds them, else the store's `engagement/transactional`; with neither, an
operator is created and the invitation is logged as not sent. A credential's secrets are `credential/<id>` and a terminal's
`terminal/<id>` in the service's own secret store, read through a 30-second cache.

## The suites

| Level | Where | What |
|---|---|---|
| unit | `routing/`, `payments/`, `security/`, `operators/`, `terminals/`, `keys/`, `audit/`, `deposits/` | the pure rules with the original's cases; the entities and consumers in ankka's unit kits |
| integration | `it/` | the whole service over Postgres with `FakeGateway`, `FakeWallet`, `FakeKeycloak` and `RecordingInvitations`: the workflow, the views, the routes, the seed, the sync, erasure, durability across restarts, SC-003 (`LeakSuite`), SC-004 (`RepeatSuite`), SC-007 (`SecretLeakSuite`) |
| features | `features/` | `features/payments/{deposits,cashier,hierarchy,summary,operators}.feature`; the cashier scenarios that are `ingest`'s are named as run by spec 006 |
| parity | `port/` | one test per `tests/port/merchant/*.json`, ignored until the recording run; `ParityFixtureSuite` proves the replay on a hand-made recording |

Each suite that stops or restarts its service, or shortens its timers, runs in a JVM of its own (`build.sbt`).

## Recording the original

`tests/original/README.md`: `e2e-merchant.patch` for scenarios 12 and 13, and `merchant-driver/` for the
behaviours the original's suite never reaches.
