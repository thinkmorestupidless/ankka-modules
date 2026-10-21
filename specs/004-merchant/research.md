# Research: merchant

Phase 0 of the plan. Each decision names what established it: the original's code (`eitheror-platform`
on `main` as checked out on 2026-10-12, `substrate/merchant-service/`, `services/psp-gateway/`,
`tests/e2e/`), the ankka 0.11 documentation (`build/workflows.md`, `build/timers.md`,
`build/http-endpoints.md`), the ankka specs this repository depends on, and specs 001, 002, 003, 005
and 006 here. Nothing below is new behaviour unless the spec already lists it under *Changes from the
original*; where planning found a gap or a contradiction in the spec it is named under *Spec
corrections* at the end, and what it found against other specs under *Findings for other specs*.

## R1. One Scala service of the shared `payments` project, exposed for its operators

**Decision**: `services/merchant/` is one Scala service (`ankka init merchant --package
io.eitheror.casino.merchant`), the first of the shared `payments` project
(`shared/payments/manifest.json`). It serves no merchant-facing or provider-facing route: the
cashier's public routes are `ingest`'s (spec 006) and the providers' webhooks are `psp-gateway`'s
(spec 005). It is exposed, because the payments backoffice frontend reads its operator routes and an
invited operator posts `accept-invitation` from a browser (spec Assumptions; analysis 2026-10-12).
Every route is admitted by a staff token (`Routes.paymentsOperator`), by the invitation token in its
body (`accept-invitation`, a public route admitted by its own key, constitution III), by a named
service of the project (`ingest`, `psp-gateway`), or by a brand's `players` under grant; the
identity routes are not reachable with a token. Spec 001's `Layout.ServiceOnly` named `merchant`
and the brands tool refused `expose: true` for it (`Manifest.scala:130`); both change (T002, T072).

**Rationale**: constitution VI (the deposit is the money path); spec 004 Clarifications (wave 1);
Layout.scala:27-34.

**Established by**: `MerchantServiceMain.scala` (one process serving every route today); spec 001
FR-003, FR-020.

## R2. Components

The component table is in plan.md. The reasoning that is not obvious from the spec:

- **Holding, Company, Sub-brand, Terminal, Operator are event sourced** (FR-001): the backoffice
  tree and the audit (FR-034) need the history, and the audit consumer reads their events.
- **The sub-brand holds its merchant keys and credential records as events; secrets never**
  (FR-004, FR-006): a key is `(id, name, digest, active, created)`; a credential record is `(id,
  provider, name, environment, scheme, status)`. Secrets go to the service's secret store (R9). The
  original kept both on `eitheror-merchant` twins with the raw credentials in twin properties
  (`MerchantServiceClient.scala:355-410, 576-712`).
- **Terminals are their own entities**, listed by a view, because a sub-brand must not list its
  children (FR-001) and a terminal's properties change independently (`MerchantRoutes.scala:1027-1333`).
- **`MerchantKeyIndex` is a key value entity keyed by the digest** (FR-004, SC-006), maintained by
  the `KeyIndexer` consumer over the sub-brand's key events. The original scanned every merchant's
  key list (`MerchantServiceClient.scala:1169-1183`). `ingest` verifies keys from the facts (FR-005);
  this index is the direct lookup FR-004 requires here and backs the active-key read `ingest`
  refreshes from.
- **A routing tree is an event sourced entity per `<level>/<entityId>`** holding the draft, the live
  version and every deployed version (FR-010); `simulate` is a pure function over a tree the request
  carries (`RoutingTreeRoutes.scala:279-300`).
- **A session is a key value entity keyed by its token, expiring at the deadline** (FR-014), holding
  everything the submit needs, the customer fields (R12), and, once submitted, the first submit's
  answer (R7, spec correction 2).
- **A payment is an event sourced entity keyed by the payment id and holds no personal field**;
  the customer fields are a key value entity `PaymentCustomer` beside it (R12).
- **One `PaymentsView` serves every question over payments**: the backoffice list and its counts,
  the summary (FR-029), the deposit count a routing context needs (FR-012: captured payments of the
  customer at the sub-brand), the captured-not-credited list (FR-023) and a customer's payments for
  erasure. One row per payment, written from the payment's events, so a count is exactly once by
  construction. The original kept a per-customer counter twin incremented on capture
  (`MerchantServiceClient.scala:948-969`); a counter entity updated by an at-least-once consumer
  would need to remember which payments it counted. *Verify at task time*: the view's SQL supports
  the sums and groupings the summary needs (`jsonNumber`, `GROUP BY` on a JSON field); the fallback
  is the summary computed in the endpoint over the sub-brand's rows, which is what the original does
  (`CashierAdminRoutes.scala:185-230`).
- **No `DepositCounter`, no risk entities, no legacy merchant identity** (R20).

**Established by**: `Models.scala`; `MerchantServiceClient.scala`; ankka `build/key-value-entities.md`,
`build/event-sourced-entities.md`, `build/views-consumers`.

## R3. Standing in for `psp-gateway` (spec 005), which is not built

**Decision**: everything this service asks of the gateway sits behind one trait, `GatewayClient`:
`initiate(HostedPage) → Either[GatewayRefusal, Initiated]` and `session(reference) → SessionStatus`.
`GatewayClient.http` calls `psp-gateway` within the project (`services("psp-gateway")`); the
integration kit's `FakeGateway` (`it/FakeGateway.scala`, a `FakeServer` as game-provider's) serves
the same two routes and can be told to refuse an initiate with a player-safe message, to answer a
crypto destination, to answer a session as resolved, unknown or undelivered, and to record what it was
asked. The gateway's own calls to this service — capture and fail — are not faked: the suite posts
them to this service's routes as the gateway's identity (`testKit.asCaller(Caller.Service("payments",
"psp-gateway"))`), signed with the credential's `webhookSecret` when it has one, exactly as spec
005 FR-014 says the gateway will.

The contract both sides are held to is [contracts/gateway.md](contracts/gateway.md): the initiate
request and answer are the original's `HppInitiateReq` and `HppInitiateResp`
(`CashierRoutes.scala:379-427`; `hpp.go:29-84`) with the amount in minor units; the player-safe
refusal is the original's `{"error":"player_error","message":…}` (`CashierRoutes.scala:446-451`;
`CashierPlayerErrorForwardingSpec`); the session status read is new (spec 005 FR-009 promises it;
the original marked `outcome_undelivered` on a twin the sweeper read, `substrate/client.go:193-217`,
`CashierRoutes.scala:1320-1350`). Spec 005's plan adopts this file.

**Rationale**: the spec's independent tests name "the gateway's simulator provider", which in spec
005 is the `psp-stub` image (005 Clarifications: the in-process simulator is dropped). Neither exists
yet, and a fake of two routes is a day's work where a stub of eight providers is spec 005. The seam
is the same one `psp-gateway` will be called through, so swapping the fake for the service changes
no code here.

**Alternatives**: build spec 005's simulator provider first — it is spec 005, out of order; call the
original's Go gateway in tests — it needs the substrate and the internal secret the rebuild drops.

## R4. Standing in for `ingest` (spec 006): nothing to fake

**Decision**: `ingest` is a caller. The routes this service serves it (create a session, submit,
abandon, read a payment, read the active key index) are a service-class endpoint admitted to
`Callers.service("payments", "ingest")` alone; the suites call them as that caller. The parity suite
replays the original's public cashier steps — `POST /v1/cashier/sessions` with a `merchantKey`
header, `POST …/submit`, `GET /v1/payments/{id}` — as those commands, resolving the sub-brand from
the key the recording generated (the raw key is captured from the key-generation step; its digest
is the index's key), which is exactly what `ingest` does (006 FR-002, FR-011). The command shapes
are [contracts/commands.md](contracts/commands.md); spec 006's plan adopts them.

**Established by**: spec 006 FR-006, FR-011, FR-012; `CashierRoutes.scala:607-665`.

## R5. Standing in for the brand's wallet (spec 002, built)

**Decision**: the two granted routes the deposit workflow calls (`POST /v1/casino/brands/{brand}/wallets/{player}/{currency}/deposits`,
`POST /v1/casino/brands/{brand}/payments/{paymentId}/outcome`; spec 002 `contracts/routes.md`,
`Layout.BrandGrantsMade`) are served in the integration kit by `FakeWallet` (`it/FakeWallet.scala`),
addressed as the brand's `wallet` service in another project. The fake keeps every key it has
answered so a repeat returns the first outcome (as the real wallet's workflow does), and can be
stopped and started (US3 scenario 1), told to answer and then forget the answer was delivered
(scenario 2), to refuse finally (`WALLET_NOT_FOUND`, a currency mismatch, `EXCLUDED`, `COOLING_OFF`,
`OVER_LIMIT`; scenario 3) or to answer `503 OUTCOME_PENDING`. The real wallet is not started in this
build's tests: it is another sbt build; the cross-service check is spec 019's and the installation
smoke in quickstart §8.

*Verify at task time*: the SDK's form for calling a service of another project
(`clients.services(project, name)` per `build/http-endpoints.md`, "one in another project by project
and name") and the `localServices` key the test kit takes for it.

**Established by**: spec 002 FR-022, FR-022a, its `contracts/routes.md`; spec 003's `FakeWallet`.

## R6. Recording the original's payments path

**Decision**: `tests/port/merchant/` is made in two runs, as `tests/original/README.md` says, from:

1. **The Go scenarios the spec names**: `TestScenario12_OnboardingGate2_ServicesHealthy`,
   `TestScenario12_OnboardingGate3_FullChain`, `TestScenario13_DreamzPay_FullChain`,
   `TestScenario14_CasinoDeposit_PlayerToWallet`. Three facts about them decide how:
   - Gate3 is `t.Skip`ped in the original ("routing table draft returns 400 in KinD",
     `scenario12_payments_onboarding_e2e_test.go:470`), and the skip is right about the symptom and
     wrong about the cause: the helper posts the draft as `{"subBrandId", "tree"}` and deploys with
     `?merchantId=`, while the route takes `{"level", "entityId", "tree"}` and `?level=&entityId=`
     (`RoutingTreeRoutes.scala:230-233, 376-385`). Scenario 13's helper has the same shape. On the
     original they are refused before any behaviour is exercised. A new patch,
     `tests/original/e2e-merchant.patch` (beside `e2e-rg-kyc.patch`), removes the skip and corrects
     the two calls to the route as served. This is a stale test call, not the original's behaviour;
     spec 001 R10's rule — fix the environment, never the test — is read as "never change what the
     test asserts", and the assertions are untouched. The finding goes to the original's `GAPS.md`.
   - Scenarios 13 and 14 skip without `DREAMZPAY_MEMBER_ID`, `DREAMZPAY_TOTYPE`,
     `DREAMZPAY_SECURE_KEY`; the dreamzpay stub's README gives the values, and `DREAMZPAY_BASE_URL`
     must be the host's `http://localhost:18103` because the player's browser, not the gateway, posts
     to it. The suite posts the webhook itself, so the stub is never called in a recording.
   - Scenario 14 needs the casino stack: the original's KinD cluster with `casino-service` and
     `wallet-service` (as spec 003's recordings, `tests/port/README.md`), with `merchant-service` and
     `psp-gateway` reached by port-forward at the suite's defaults (`setup.go:17-20`). Its steps on
     other services (register and sign in a casino player, the substrate's twin reads, the wallet's
     balance) are recorded and replayed as the kit's setup or as recorded, as 003 did.
   - Gate3's capture steps carry `X-Internal-Secret` (`scenario12OutcomeHTTP`); the recorder redacts
     it and the parity suite replays them as the gateway's identity (R3).
2. **A driver for the cashier behaviours the Go suite never reaches**: `tests/original/merchant-driver/`
   (Go, after `wallet-driver/`), one scenario per scenario of `features/payments/deposits.feature`
   and the cashier scenarios of `cashier.feature` this spec owns, driven against the original's
   `merchant-service` with the in-process simulator as the provider (an empty `psp` name, the
   path the gateway keeps for callers that mean it, `hpp.go:98-100`) and the internal secret for
   the capture and fail routes. It records: the failure with code and reason and the generic
   default; the repeated capture; a failure arriving after a capture; the lazy expiry on read; the
   expiry sweep (`POST /internal/v1/cashier/payments/expire-stale` with `olderThanMinutes`, a
   dropped route replayed in the kit as "the deadline fires" under the kit's clock); the undelivered
   skip; the abandon through the original's internal route (dropped; replayed as the new
   token-admitted command); the HMAC refusals and the unsigned simulator path; and the four variance
   cases. Each scenario is named exactly as its feature scenario, so one parity test binds each.
   Without the driver, US2, US3 and US7 would rest on the original's unit suites alone, which mock
   the substrate and record nothing.
3. **The original's unit suites** (`CashierRoutesSpec`, `CashierFailureOutcomeSpec`,
   `CashierCaptureDurabilitySpec`, `CashierSettlementVarianceSpec`, `CashierCaptureAuthSpec`,
   `CashierPlayerErrorForwardingSpec`, `TenantIsolationSpec`, `RoutingTreeRoutesSpec`,
   `MerchantKeyValidationRouteSpec`, `PspCredentialLifecycleSpec`, `TerminalCredentialStateRoutesSpec`,
   `TerminalDeleteRoutesSpec`, `CashierAdminRoutesSpec`, `OperatorSurfaceActorTypeSpec`,
   `OperatorInviteEmailServiceSpec`, `OperatorInvitationTokenStoreSpec`; 189 cases in all) are not
   recordings: their cases are ported as the domain package's and the entity kits' tests, each named
   after the original's case, as spec 003 ported the adapter's suites. Cases about the substrate
   (concept assignment, twin writes, the sweepers' internals) are dropped with what they test.

The run follows `tests/original/README.md` (compose stack plus the seven stubs for 12 and 13; the
KinD stack for 14), twice on fresh ids, merged with `merge`, and `just brands recordings check` must
accept every file. A scenario that fails on the original is recorded as it fails and is a finding,
not a requirement.

**Established by**: spec 001 FR-029 to FR-032, R10; `tests/original/README.md`; `tests/port/README.md`;
`scenario12_*.go`, `scenario13_*.go`, `scenario14_*.go`, `setup.go`.

## R7. The deposit workflow

**Decision**: one `DepositWorkflow` per payment, id = the payment id, started by the submit. Its
steps, and the commands that move it:

| Step | Does | Then |
|---|---|---|
| `initiate` | asks the gateway to initiate the hosted page (R3) with the payment id as the gateway's key (005 FR-008); records `initiated` on the payment (the status `AWAITING_REDIRECT`, or `AWAITING_CRYPTO` when a destination came back) | `await-outcome` |
| `await-outcome` | nothing | `thenPause(deadline − now, expire)` |
| `expire` | asks the gateway for the session (R3). Undelivered: records `waiting` on the payment with the reason, pauses another deadline, back to `expire`. Resolved or unknown: records `expired`, reason "Payment not completed" | end |
| `credit` | calls the brand wallet's deposit route under the grant, key = payment id, amount = the credited amount (R8's variance), `paymentReference` = the provider's transaction id, with terminal, provider and scheme (002 FR-022). Answered: records `credited` (or the final refusal) on the payment | end, or `credit-wait` |
| `credit-wait` | nothing; attempts in the state | `thenPause(backoff, credit)`: 1 s doubling to 30 s, without bound (SC-002's minute holds with a call's latency; analysis 2026-10-12) |

Commands: `start(submit)` (the atomic claim; a second start is `Conflict`); `capture(details)`,
`fail(details)`, `abandon` — each refused when the payment is terminal, each transitioning
`await-outcome` (or an `expire` pause) to `credit`, or recording the failure and ending; `retry`
(an operator's, FR-023) transitioning a final refusal back to `credit`; `status`.

**The capture is acknowledged only after the wallet answers** (FR-021, *Changes*): the capture
route sends `capture` and polls `status` every millisecond for up to 2 s (the wallet's `Flows`
pattern, spec 002 R17); `credited`, `captured` with no brand, or a final refusal is `200`; anything
else is `503 OUTCOME_PENDING`, which is the gateway's cue to keep the outcome undelivered and
redeliver (005 FR-015). A redelivered capture finds the workflow past `await-outcome` and is
answered from its state without a wallet call (US1 scenario 4, SC-004).

**A wallet that cannot be reached, answers 5xx, or answers `OUTCOME_PENDING`** is `credit-wait`; a
refusal the wallet states as final (`WALLET_NOT_FOUND`, a currency mismatch, `EXCLUDED`,
`COOLING_OFF`, `OVER_LIMIT`; spec 002 `contracts/wire.md`) ends the retries, is recorded with the
reason and the remaining allowance, and leaves the payment `captured` and not credited, listed by
the view (FR-021, FR-023). The money's fate is spec 010's open question and stays open (R17).

**Outcomes other than the capture reach the brand from a consumer, not a step**: `OutcomeReporter`
consumes the payment's events and calls the wallet's outcome route for `initiated`, `failed`,
`expired` and `abandoned`, keyed `<paymentId>/<outcome>` (002 FR-022a), retried by the consumer's
own at-least-once delivery; the wallet answers a repeat as recorded. The spec's FR-020 names a
`report` step; a step cannot retry a report while the same instance must also pause for its
deadline (a workflow runs one step at a time, `build/workflows.md`), and `initiated` must reach the
brand before the outcome does. The observable behaviour FR-022 requires is unchanged; the step is a
consumer (spec correction 3). A sub-brand naming no brand skips both `credit` and the reporter
(FR-022).

**Races**: a capture and a failure for one payment are commands on one single-writer instance; the
first recorded wins and the second is acknowledged and ignored (Edge cases). A capture for a
payment already `expired` is acknowledged and changes nothing (FR-019, the original's rule); the
`expire` step's undelivered check is what keeps that from losing a provider's capture (SC-003).

**The payment entity records what the workflow decides** (`initiated`, `captured` with the variance,
`credited`, `credit-refused`, `failed`, `expired`, `waiting`), and the read computes the lazy expiry
(FR-017) from the deadline when no timer has fired yet, as the original's read did
(`CashierRoutes.scala:1948-1990`).

**Established by**: `CashierRoutes.scala` (capture 2100-2290, fail 1860-1935, expire-stale
1280-1430, abandon 1190-1215, `PaymentStatus.scala`); spec 002 R17 and `DepositWorkflow.scala`;
ankka `build/workflows.md` (pause with timeout, single writer, queries during a step).

## R8. Verifying the gateway's capture, and the settlement variance

**Decision**: the capture and fail routes take the body as `String` and the endpoint reads the
payment first: an unknown payment id is `200` and ignored (FR-019; `CashierFailureOutcomeSpec`
"return 200 for an unknown payment"); an entity or secret store that cannot be read is `503`
(`CashierCaptureAuthSpec` "503, not 401"). The payment names its sub-brand and provider; the
sub-brand's first active credential for that provider names its secret (R9); `webhookSecret`
absent means the callback is accepted unsigned (the simulator's path). Present, both
`X-PSP-Signature` and `X-PSP-Timestamp` are required, the timestamp within 300 s, and the hex
HMAC-SHA256 of the body's UTF-8 bytes under the secret compared in constant time
(`MessageDigest.isEqual`). One header without the other, or a mismatch, is refused `401`/`403` as
the original (`CashierRoutes.scala:666-795, 2069-2100`). As spec 003 R3 found, ankka 0.11 has no
raw-bytes body; JSON is UTF-8 and the round trip is exact.

The variance (FR-026) is the original's `SettlementVariance` (`CashierRoutes.scala:264-350`): kind
`UNDERPAYMENT` or `OVERPAYMENT` with `receivedCryptoAmount`, `receivedConvertAmount`,
`receivedConvertCurrency` as the provider's strings; an underpayment credits the valuation as minor
units of the payment's currency and keeps the invoiced amount as `originalAmount` on the first
capture only; it is refused — `400`, the capture not acknowledged — when the valuation is not a
positive decimal, exceeds the invoice, has no currency or another currency; equal is not over. An
overpayment credits the invoice and records the surplus.

**Established by**: `CashierCaptureAuthSpec` (16 cases), `CashierSettlementVarianceSpec` (8 cases).

## R9. Secrets: the service's store, by credential and terminal

**Decision**: a credential's secrets are one entry `credential/<id>` (the secrets map as JSON) in
this service's secret store (ankka 023 `SecretStore`, as spec 003 R4 uses it; 038's backends from
their branch until released, with the same fallback); a terminal's secret properties are
`terminal/<id>`. Which of a terminal's properties are secrets is the gateway catalogue's to say:
`RequiredProperty` gains `secret: Boolean` (FR-009; the original's catalogue marks `required` and
`multiline` only, `Models.scala:380-396`; the keys it lists as secrets today — `secureKey`,
`passphrase`, `webhookSecret`, `accessToken`, `securityToken`, `apiKey`, `password`,
`privateKey` — are marked). A listing shows no secret and a masked preview at most, the original's
proportional reveal (`TerminalCredentialStateRoutesSpec`). Reads on the money path (the HMAC
secret) go through a 30-second cache as spec 003 R4, so 038's read record stays off every capture.

`psp-gateway` reads credentials and terminal properties from this service (FR-008), not from a
store of its own: the store is per service. Spec 005 FR-018 says the gateway reads them "from the
secret store under the `payments` project"; see *Findings for other specs*.

**Established by**: FR-006 to FR-009; `MerchantServiceClient.scala:812-832`;
`MerchantRoutes.scala:1467-1516`; ankka 023; spec 003 R4.

## R10. Operators: tokens, scope, Keycloak, the invitation

**Decision**:
- **Tokens** are verified through ankka 022 (`Oidc.authenticate()`, the staff issuer) as the
  contract's `Routes.operator` does for a brand; shared payments needs a new ACL recipe,
  `Routes.paymentsOperator` (additive contract change): the staff issuer, a role in the privilege
  table below, and the scope read from the claims `eitheror_scope_level` and `eitheror_scope_id`
  (decision 0050; never `customer_id`). Scope is checked through the hierarchy by `Scope.permits`
  (ported from `HierarchicalAccess`: platform sees all; a holding its companies and sub-brands; a
  company its sub-brands; a sub-brand itself), answered `403 scope_violation` with the original's
  message (`CashierAdminRoutes.scala:243-245`).
- **Privilege** is the original's table (`PrivilegeCheck.scala:9-18`): `platform_admin` 100,
  `compliance_officer` and `fraud_analyst` 60, `resource_owner` 50, `resource_admin` 40,
  `support_agent` and `resource_operator` 30, `resource_finance` 25, `resource_developer` 20,
  `resource_viewer` 10; an assigner must outrank the role granted, and may not broaden the scope
  level or leave their own subtree (`canAssignPrivilege`, `canAssignScope`).
- **Provisioning** is the Keycloak admin REST API against the `eitheror` realm, the calls the
  original makes (`createUser`, `assignRole`, `setOperatorScope` attributes, `resetPassword`,
  `setEmailVerified`, `listUsers`, `deleteUser`, the `enabled` flag), ported as
  `operators/KeycloakAdmin.scala` with the admin client's credential in the secret store
  (`keycloak/operators-admin`). `tools/brands` has a `RealmAdmin` with overlapping calls and spec
  008 will need one too; a shared module is a reuse-map item once both exist, not an abstraction
  made now. The `Operator` entity records what Keycloak was told (scope, ancestors, pending) and
  what the sync read back.
- **The invitation** is a token whose digest is a key value entity expiring after 72 hours
  (`OperatorInvitationTokenStore.scala:48-60`), sent through the `Invitations` seam:
  `CustomerIoInvitations` (the app key and the two transactional ids read from the secret store
  entry `engagement/transactional`; the region from `ENGAGEMENT_REGION` and the accept page's address
  from `OPERATOR_CONSOLE_URL`, R19) and the kit's recording fake. FR-027's "engagement provider the
  installation configures" is this service's configuration; spec 001's dependency note says the
  invitation is sent "through" `engagement` (spec 014), which is a brand service a shared project
  holds no grant into — see *Findings*.
- **The enabled flag** is mirrored by `OperatorSync`, a recurring timer every 60 s (032 from its
  branch; until merged a single timer that reschedules itself from its handler, spec 003 R11),
  replacing `OperatorStatusSyncer`.

**Established by**: `OperatorRoutes.scala`, `PrivilegeCheck.scala`, `OperatorInviteEmailService.scala`,
`OperatorStatusSyncer.scala`; decision 0050; spec 001 FR-021.

## R11. Money on the wire

**Decision**: every amount is `MinorUnits` (contract.money) from the boundary. Routes `ingest`
calls take minor units; the backoffice and the frontend read `amount` as a two-decimal string and
`amountCents` as a number, both produced on the wire (FR-031). The decimal rendering by currency
exponent, `Amounts`, exists in game-provider (`money/Amounts.scala`); it is hoisted into the
contract as `contract.money.Amounts` (additive) so `merchant` and later `payouts` share it;
game-provider switches to it when next touched. `amountCents` was a 32-bit integer on the original's
cashier (`RoutingContext.amountCents: Int`); it is a `Long` here and a value that does not fit is
refused naming the field.

**Established by**: spec 001 FR-026, R (#662 was an int32 overflow); `CashierRoutes.scala:95-100`.

## R12. Personal data: where the customer fields live, and erasure

**Decision**: the customer fields a provider requires (email, first and last name, phone, mobile,
province, network address, country, city, address, post code, state;
`CashierRoutes.scala:74-87`) are `Personal` values (the contract's marker until 042 ships, spec 001
R5, 002 R12) under the subject `PlayerSubject(customerId)` where the customer id is the original's
`brand:player`. They live in two key value entities and nowhere else: `Session` (expiring at the
deadline) and `PaymentCustomer` (keyed by the payment id, kept for the provider's needs until the
brand's erasure or the installation's retention bound, default 90 days, as `expireAfter`). The
payment's events hold the player id and no personal field, so a journal is never the thing to erase.
The erasure handler is a route `POST /erasure/players/{customerId}`, admitted to a brand's `players`
under the shared manifest's `erasure` grant and refused when the customer id's brand is not the
caller's project; it deletes the customer's `PaymentCustomer` entries (found by the view) and any
live `Session`, and confirms. An operator's email is `Personal` under `operator/<id>` and is
removed with the operator.

**Established by**: FR-032; spec 001 FR-018, FR-025, R5; spec 002 R12.

## R13. Facts and the audit

**Decision**: `MerchantFacts` consumes the sub-brand's key events and publishes
`merchant_key_issued` and `merchant_key_deactivated` on `payments.merchants` (contract
`PaymentsMerchants`, already defined; FR-005). `AuditFacts` consumes the events of every entity
FR-034 names and publishes `audit_record` (contract `AuditRecord`) with `service = merchant`,
`brand` = the sub-brand's brand or `payments` for the hierarchy and operators, and the actor the
command recorded. The shared `payments` manifest lists no `platform.audit` topic; the plan adds one
to `shared/payments/manifest.json` (the brain says audit is per brand and the shared `reasoner`
reads by grant; spec 018 is unplanned) — *verify at task time* against specs 001 and 018 before
the manifest changes.

**Established by**: FR-005, FR-034; `Others.scala:198-247`; `shared/payments/manifest.json`.

## R14. Routes under ankka 0.11's two rules

**Decision** (ankka `build/http-endpoints.md`: a handler takes at most two path parameters; two
endpoints may not share a prefix; literal segments outrank parameters):

- The original's credential routes carry three parameters
  (`/v1/merchants/{id}/psp-credentials/{provider}/{name}[/activate|/disable]`,
  `MerchantRoutes.scala:607-726, 901-1026`). They become `/v1/merchants/{id}/psp-credentials/{provider}`
  with the credential's name as the query parameter `name` (`?name=`), for read, update, delete,
  activate and disable; the same under `/v1/sub-brands/{id}`. The wallet set the precedent
  (`awards/switch` moved `grantRef` to the body, spec 002 `contracts/routes.md`). The payments
  backoffice frontend changes these paths; spec 019 carries the frontend change. The parity suite
  marks the recorded `activate` step as this divergence and replays it with the query form.
- `/v1/payments` is one endpoint with two audiences by `withAcl`: operators (`/cashier`,
  `/cashier/summary`, `/routing-rules/*`, `/terminals`, `/{id}` read), `psp-gateway`
  (`/{id}/capture`, `/{id}/fail`). The literal segments outrank `/{id}`.
- `/v1/holdings`, `/v1/companies`, `/v1/sub-brands`, `/v1/merchants`, `/v1/catalog`,
  `/v1/operators` are operator endpoints; `/v1/cashier` is `ingest`'s; `/internal/v1` holds the two
  gateway reads at the original's paths; `/erasure` the brands' `players`; `/status` the project's.

The full table is [contracts/routes.md](contracts/routes.md).

## R15. Seeding a brand's hierarchy (FR-003)

**Decision**: the brands tool reaches only the control plane and holds no operator token, so it
cannot call `merchant`'s routes (exposed or not, R1). Instead, when the tool applies `shared/payments`, it renders into `merchant`'s
descriptor an environment variable `MERCHANT_BRANDS` — a JSON list of `{slug, name}` from every
`brands/<slug>/manifest.json` in the repository — and `merchant` seeds on start: a `Seed` timed
action scheduled at startup (at least once, retried) sends `create` to the holding, company and
sub-brand entities with the ids decision 0050 derives
(`UUID.nameUUIDFromBytes("seed.<holding|company|sub-brand>.<slug>")`), type `Casino`, the brand
slug as its `brand`; an entity that already exists with the same content records nothing. Applying
a manifest again changes nothing. `DevSeedRoutes` is dropped (spec *Changes*).

*Verify at task time*: the tool's `Render` emits per-service env from the pin (`Render.scala:101-118`);
the shared manifest needs the list computed, not pinned.

**Established by**: FR-003; decision 0050; `DevSeedRoutes.scala`; `tools/brands/.../Render.scala`.

## R16. Suites at three levels, and parity

**Decision**, as the wallet and game-provider:
- **Unit**: the pure packages with the original's cases ported — `routing/Evaluator` (the
  `RoutingTreeEvaluator` and `RoutingTreeRoutesSpec`'s resolution cases), `money` (amounts,
  the variance rules of `CashierSettlementVarianceSpec`), `security/Hmac`
  (`CashierCaptureAuthSpec`'s header and window cases), `operators/Scope` and `Privilege`
  (`TenantIsolationSpec`, `OperatorSurfaceActorTypeSpec`), `payments/Status` (`PaymentStatus`'s
  mapping), the catalogue's masked preview (`TerminalCredentialStateRoutesSpec`).
- **Entity kits**: every entity with `EventSourcedTestKit` or `KeyValueEntityTestKit`;
  `PspCredentialLifecycleSpec`, `TerminalDeleteRoutesSpec`, `OperatorInvitationTokenStoreSpec`'s
  cases land here.
- **Integration** (`it/Kit.scala`: `AnkkaTestKit.start` over Postgres with `FakeGateway`,
  `FakeWallet`, `FakeKeycloak`, the recording `Invitations`, an `InMemoryBroker`, `TimerRuntime` and
  a settable clock): the deposit workflow's suite (unreachable wallet, lost answer, final refusal,
  the deadline and the undelivered wait, races, 1,000 captures with one in ten undelivered for
  SC-003, a capture delivered ten times for SC-004), the views, the seed, the sync, `restartService`
  mid-workflow, the features binding (`GherkinSuite` over `features/payments/deposits.feature`,
  `hierarchy.feature`, `operators.feature`, `summary.feature` and the scenarios of `cashier.feature`
  this spec names; the rest of `cashier.feature` is spec 006's), and `ParitySuite`: one test per
  file in `tests/port/merchant/`, fail-first, divergences and extensions named in the test as
  spec 003's.

## R17. What stays open

- A deposit the wallet refuses for a responsible-gambling reason is left captured and not credited,
  with the reason and the allowance recorded and shown; whether it is refunded or held is spec 010's
  question (FR-021). Nothing here decides it.
- Whether a provider's capture arriving after the payment expired should re-open the payment (the
  original acknowledges and ignores it, FR-019). The undelivered check (FR-020) is the guard; a
  provider that captures after thirty minutes without the gateway holding the outcome undelivered is
  a case the original loses too. Raised for Sheldon as a product question, not changed here.

## R18. Performance

SC-002: a capture with the wallet down ten minutes credits within one minute of its return — the
backoff ceiling is 30 s, so the minute holds with the call's latency. The capture path with the wallet up: one entity read (the payment), one
cached secret read, one workflow command, one wallet call, one entity write, polled at 1 ms: well
inside the 2 s bound. A session: one index-free sub-brand read (ingest resolved the key), up to
three routing-tree reads, one view query (the deposit count), one key value write.

## R19. Settings

`MERCHANT_BRANDS` (R15); `PAYMENT_DEADLINE` (default 30 minutes, the original's `paymentTtl`);
`UNCREDITED_BOUND` (default 15 minutes, FR-023); `CUSTOMER_RETENTION` (default 90 days, R12);
`OPERATOR_SYNC_INTERVAL` (default 60 s); the Keycloak base URL and realm (`eitheror`); the
engagement provider's region; `OPERATOR_CONSOLE_URL`, the backoffice address an invitation's accept
link points at (the original's `OperatorInviteEmailService` base URL). Secrets by name in the store (R9, R10). All rendered by the brands
tool from the shared manifest's pin.

## R20. What is not carried over

Dropped under constitution I with the spec's FR-036 and FR-037: the risk blocklist and config, the
payments API and its fraud call. Dropped as replaced: `DevSeedRoutes` (R15), the legacy
`eitheror-merchant` identity and `reconcileLegacyMerchant`, the internal validate-key route
(`ingest` verifies from facts; this service serves the index read), the internal player-attempts and
abandon routes, the three sweepers and the reactive function, the CORS preflights (spec 006 FR-020),
the ST8 credential routes (spec 003), the holding CRM config (spec 014), the sub-brand turnover
fields (spec 002), the `kyc-policy` seed (spec 009).

## Spec corrections found in planning

Applied to spec.md on this branch unless marked as a question:

1. **FR-030 contradicts FR-036.** FR-030 keeps the risk routes "as the original serves them"; FR-036,
   written in a later reconciliation, drops them as a stub. FR-036 stands; FR-030 is struck.
2. **FR-015 contradicts its own acceptance scenario.** FR-015 says a session submitted twice "MUST be
   refused"; the scenario US2 names (`cashier.feature`: "submitting a session already submitted
   returns the first submit's answer and makes no second payment") and spec 006 FR-010 say the first
   answer is returned. The scenario stands (constitution II: a repeat returns the first outcome):
   the session keeps the first submit's answer and returns it.
3. **FR-020's `report` step is a consumer** (R7). The step list loses `report`; FR-022 says the
   outcomes are reported by a consumer of the payment's events, keyed and retried. Behaviour as
   observed by the brand is unchanged.
4. **FR-009: the gateway catalogue marks which properties are secrets** (R9), listed under *Changes*.
5. **Three-parameter credential routes take the name as a query parameter** (R14), listed under
   *Changes*, with a line for spec 019 (the backoffice frontend's paths).
6. **Recordings: scenario 12's full chain is skipped and stale in the original** (R6). The
   *Recordings* section says so and names `e2e-merchant.patch` and the `merchant-driver` scenarios.
7. **FR-027: the invitation provider is this service's configuration** (R10); spec 001's note that
   it is sent through `engagement` is a finding below.
8. *Question for Sheldon* (R17): a provider's capture after expiry.

Found by `/speckit-analyze` (2026-10-12), applied:

9. **`merchant` is exposed** (R1): the backoffice and the invitation's accept page reach it from a
   browser; spec 001's `Layout.ServiceOnly` and the shared manifest change.
10. **FR-018 has living-feature scenarios**: five signature scenarios appended to
    `deposits.feature`, which the driver's HMAC recordings are named after.
11. **FR-014's supported currencies** come from the terminal's `supportedCurrencies` property, as
    the original's; no new field.
12. **The inactive-key scenario** (`cashier.feature`) is `ingest`'s and is no longer named by this
    spec; FR-004 says the index is this service's and the validation spec 006's.
13. **The credit backoff ceiling is 30 s**, so SC-002's minute holds with a call's latency.
14. **`OPERATOR_CONSOLE_URL`** is a setting (R19); the engagement region is a setting, not part of
    the secret.

## Findings for other specs

- **Spec 005 FR-018** has the gateway read credentials "from the secret store under the `payments`
  project"; the store is per service, and spec 004 FR-008 has this service serve them. 005's plan
  should read them from `merchant` at the moment of use ([contracts/gateway.md](contracts/gateway.md)),
  and adopt the session status read and the capture acknowledgement rule (`503 OUTCOME_PENDING`
  keeps the outcome undelivered).
- **Spec 006** adopts [contracts/commands.md](contracts/commands.md) as the commands it shapes
  requests into.
- **Spec 001's dependency note on spec 014** ("operator invitations from `merchant` are sent
  through it"): a shared project holds no grant into a brand's `engagement`, and a payments
  operator belongs to no brand. The invitation goes through the installation's provider configured
  on `merchant` (FR-027). 001 and 014 to be corrected.
- **Spec 001 / 018**: a shared project's audit records need a topic; the plan adds `platform.audit`
  to `shared/payments/manifest.json` (R13), to be confirmed when 018 is planned.
- **The original (`tests/e2e/GAPS.md`)**: scenario 12's and 13's routing-rules helpers call a shape
  the route does not serve (R6); the skip's stated cause is wrong.

## As built (implementation, 2026-10-12)

What the *verify at task time* items found, and where the build departs from the decisions above. Each
is named in the code where it applies.

- **R2, the view's SQL.** ankka 0.11's view client offers `where`, `ordered`, `count` and no aggregate
  SQL. The deposit count is a `count`; the backoffice summary is folded in the route over the sub-brand's
  rows, as the original folds it (`CashierAdminRoutes.buildCashierSummary`).
- **R2, the companion terminal.** An entity cannot call another in ankka, so a credential's "terminal of
  the same name" is not a second entity made by an effect: the terminals listing and the session's offer
  derive it from the credential, as the original lists credential-named terminals beside integrations.
- **R5, the cross-project call.** Locally `clients.services(project, name)` resolves by name through
  `ankka.local-services`, so the kit's key for a brand's wallet is `wallet`. Locally, too, a request with
  no caller header is `Caller.Local` and every `allowCallers` admits it: the suites present an identity on
  every request, an operator's as the ingress (`Caller.Gateway`).
- **R6, the original's ports and names.** `merchant-service` listens on 8085 and reads
  `MERCHANT_SERVICE_INTERNAL_SECRET` (`changeme` in dev). The driver seeds through the original's
  `DevSeedRoutes` (`seed/hierarchy`, `seed/merchants`: a key, a tree to `SIM`, the `SIMULATOR`
  credential), so it needs no Keycloak.
- **R6a, two departures found in porting.** The original's routing `IN` compared case-sensitively;
  FR-012 says ignoring case, and the spec stands. The original's deploy numbered a version
  `liveVersion + 1`, so a deploy after a retirement was a second version 1; a version here is one above the
  highest deployed. Its rollback copies the version into the draft (204), which is kept.
- **R7, the expiry check fails closed.** A gateway that cannot say whether it holds an outcome is not
  read as "none": the payment waits another deadline, as the original's `outcomeStillOutstanding` does.
  A payment's deadline runs from the submit, not from the session's creation.
- **R7, a capture that loses a race.** A failure or an expiry recorded first answers the capture 200 with
  that status; without this the capture polled for a credit that never came (found by the race test).
- **R8, the original's answers.** The signature refusals are the original's: 401 `missing_token`, 403
  `invalid_token`, 503 `webhook_secret_not_configured` for a signed callback whose provider has no secret,
  503 `auth_requirement_unavailable` when the store cannot be read. A variance that cannot be trusted is
  answered 503 `settlement_variance_rejected`, as the original answers it, so the gateway keeps
  redelivering and holds it undelivered; contracts/gateway.md is corrected.
- **R10, credentials.** The realm's admin credentials and the invitation provider's key are read from the
  project secrets the manifest names, rendered as env (`KEYCLOAK_ADMIN_*`, `CUSTOMERIO_*`), and from the
  service's own store only when those are absent: the brands tool writes project secrets, nothing writes a
  service's store at install. The shared manifest names only the realm's (decided 2026-10-18): a pod naming a
  missing project secret does not start and an entry may not be empty, so a required email secret stopped
  `merchant` on any installation without Customer.io. Without a key an invitation is logged as not sent. Creating an operator follows FR-027 and the feature (the caller's level and
  privilege decide), not the original's platform-only gate.
- **R10, the sync.** ankka 0.11.0 has recurring timers (spec 032 shipped); `OperatorSync` is one.
- **R13, the audit topic.** Neither spec 001 nor spec 018 names a shared project's audit topic: spec 018
  reads each brand's `platform.audit` under the brand's grant. `platform.audit` is added to `payments` in
  the contract's layout and the shared manifest, the contract's topic registry now allowing a shared
  project to reuse a brand topic's shape. How `reasoner`/`audit` reads `payments`' records is spec 018's to
  settle (*Findings*).
- **R14, methods.** The original's operator writes are PATCH (status, activate, disable, capability,
  properties); the routes take PATCH and the PUT or POST the contract named.
- **R15, the seed.** The brands tool renders `MERCHANT_BRANDS` for `payments`/`merchant` from every brand
  manifest in the repository, by slug. The seeded entities are named by the slug, as the original's are.
- **R18, the gauge.** ankka 0.11.0 offers no meter: `GET /v1/payments/metrics` serves
  `eitheror_payments_uncredited_captures` as Prometheus text.
- **No settable clock reaches a workflow's pause.** The suites shorten the deadline through the runtime's
  settings; the lazy read takes the kit's clock.
