# Research: payouts

The decisions the plan rests on, each with what was read to make it. The original is
`eitheror-platform` at `e7d8f2de`. Paths are shortened:

- `W` = `substrate/casino-service/src/main/scala/io/eitheror/casino/WithdrawalRoutes.scala`.
- `G` = `starter-packs/wallet/handlers-go/`.
- `S`, `E`, `L`, `PD` = `PayoutReconciliationSweeper`, `WithdrawalTerminalEligibility`,
  `WithdrawalLimitChecker` and `PayoutDestinationRoutes` beside `W`.

## R1. The original, read whole

**Decision**: the original's withdrawal surface is ported route by route, and listed in
`contracts/surface.md`. Where the code, its comments and the spec disagree, the code is the
specification (constitution I), except at the defects below, which the spec already removes.

What was read:

- `W` (3,284 lines) and the route mount (`CasinoServiceMain.scala:1123-1166`);
- the limit checker `L`, the sweeper `S`, eligibility `E` and its routes, destinations `PD`, `PayoutDestinationAccess` and `IbanValidator`;
- the wallet handlers:
  - only `approve-withdrawal-request` is still TL;
  - create, cancel, reject, complete, fail, reverse and mark-awaiting are Go guests under `G`;
  - their `.test.tl` files stay in `starter-packs/wallet/handlers/`;
- the wallet-service routes they sit behind;
- the reactive functions `export_withdrawal` and `notify_withdrawal_failed`.

**Defects of the original, and how the spec treats them:**

| The original | Where | The spec |
|---|---|---|
| Approve checks only the wallet's pending ref, so an approved request can be approved again, re-running the payout | `approve-withdrawal-request.tl:8-10` | FR-015: a second decision is `REQUEST_NOT_PENDING` |
| Reject of an approved or awaiting request passes the handler and refunds | `G/reject-withdrawal-request/main.go:47-53` | FR-015: reject only while pending review |
| An outcome `FAILED` on a request pending review refunds; on a cancelled one it answers 200 and writes a failure | `W:3134-3158`; `fail` has no guard | FR-019, FR-023: a settled request ignores later outcomes; an outcome is accepted only once approved or awaiting manual payout |
| Operator actions are not scoped to the brand | `requireAnyOperatorRole`, `W:1412` | FR-001: every route refuses another brand |
| Morphosis has no `COMPLETED` case and tracks it as in doubt | `W:1799-1847` | FR-018 reads the gateway's one answer, `completed`, for every provider |
| The approve path calls each provider route without a timeout | `W:1630-2142` | FR-018: an unreachable gateway is *in doubt* |
| The sweeper's scan stops at 12,500 twins; the queue and destination lists at one page of 500 | `S:81-93`, `W:1149`, `PD:65` | SC-004; the queue view pages |
| Outages read as `PLAYER_NOT_FOUND`, and bonus status reads as none | `STC:597-599`, `WSC:335-342` | the wallet's preview is called, and an unreachable wallet is `SERVICE_UNAVAILABLE` |
| Rate limits `withdraw` and `withdrawal` are configured and never checked | `CasinoServiceMain.scala:191-192` | dropped as a stub |

## R2. Recordings

**Decision**: a new driver, `tests/original/payouts-driver`, records the routes as the earlier drivers
did, against the original on kind, twice on fresh ids. The run is scenario 30, one test per scenario of
`features/payouts/`. The original's scenario 251 is recorded beside it, unchanged. A gate scenario the
driver cannot reach is said so in `tests/port/payouts/README.md`, with its reason.

**What exists today:**

- **The Go scenarios the spec names cannot be recorded:**
  - `TestScenario7_CasinoGate3_FullPlayerJourney` reaches the substrate's actions only, never `W`, and fails at `21da61f0` (`tests/port/README.md:49-52`).
  - `TestScenario10_PaymentsHTTPAPIGate3_PayoutLifecycle` is `t.Skip`ped and calls a route that does not exist.
  - The spec's Recordings section is corrected with this plan.
- **Scenario 251** (`scenario251_withdrawal_cap_lifecycle_e2e_test.go`, four tests) drives the player's create and cancel and the operator's approve and `confirm-payout` on a manual-payout terminal.
- **The gateway's side is recorded:** 8 withdrawal-payment scenarios in `tests/port/psp-gateway/`. They stay spec 005's.
- **The original's tests:**
  - 54 `.test.tl` cases on the wallet handlers. They are the wallet's (spec 002) and are not replayed here.
  - The casino-service specs: `WithdrawalRoutesSpec` (122 cases), `WithdrawalTerminalRoutesSpec` (18), `PayoutDestinationRoutesSpec` (15) and `PayoutReconciliationSweeperSpec` (9). They fake the substrate, wallet and gateway. Their cases are read as the oracle for `contracts/routes.md`, not replayed.

**What the run needs** (as 008 and 009's):

- the original on kind (`just dev-kind`, `kind-install-wallet`, `kind-install-casino`), with the Lightbend token and Akka licence copied into the recording worktree only, and TinyGo 0.42.0;
- psp-gateway repointed at psp-stub, whose Unity Finance, Cozeni, Gigadat and Morphosis payout routes exist;
- `TEST_OPERATOR_EMAIL` and `TEST_OPERATOR_PASSWORD`, which the user supplies;
- the outcome route signed with the original's `WALLET_SERVICE_INTERNAL_SECRET`. That credential stays in the recording worktree and is redacted by the merge.

The run needs the user (stopping the local ankka control plane, the passwords). It is the build's
task, as 009's T047 was.

**The parity test** replays each recording through `WireTranslation`. The translation maps:

- major units to minor units;
- the moved outcome route (R6);
- the 201 and 402 answers to this service's (`contracts/wire.md`).

## R3. Components: an entity per request, a workflow per request, an entity per player

**Decision**:

- **`WithdrawalRequest`**, an event sourced entity keyed by the request id. It holds the record of FR-008, and its events feed the facts, the queue and the audit.
- **`WithdrawalWorkflow`**, keyed by the same id. It makes every call out: the wallet's hold, release, confirm and reverse, and the gateway's initiation and status. It waits on review, on the outcome route and on its timers, and records each step on the request.
- **`PlayerPayouts`**, an event sourced entity keyed by the player id. It holds:
  - the destinations (FR-013);
  - the names from `casino.players` (FR-003);
  - the identity and account status;
  - the gateways and schemes of confirmed deposits;
  - the player's requests of the last 32 days, with amount, currency, creation time and status, for FR-005b.

**Rationale**:

- A request's gates read one player's facts together, and the limit check must see the request made a second ago. A view would answer seconds behind, and two requests in that gap could both pass a limit.
- The wallet allows one pending request per wallet, but an approved request is no longer pending, so two can be counted in one week.
- The workflow is ankka's durable process: its steps retry, its timers survive a restart, and each request reconciles itself (SC-004), with no scan.

**Alternatives**:

- The spec's views for eligibility and destinations: read-your-writes is lost on the limit, so not taken. The spec's Key Entities are amended to say entity.
- A workflow alone, with no request entity: a workflow's state is not a stream a consumer can read, so the facts would need a second source.

## R4. The request path: the original's order, the wallet's preview, nothing else called

**Decision**: a request is checked in the original's order (`W:180-873`), with the wallet's preview
standing for the route's own turnover, bonus and clawback reads.

| # | Check | Answer | Read from |
|---|---|---|---|
| 1 | token's player is the route's | 403 `player_id_mismatch` | the token |
| 2 | amount, currency, terminal present (W:213-217) | 400 naming the field | the body |
| 2a | amount a positive whole number (W:219-220); for a Gigadat terminal, `payoutMethod` with `mobile` (ETO) or `bankAccount`, `institutionCode` and `transitNumber` (ACH) | 422 `INVALID_AMOUNT` (the original's code and status); 400 naming the field | the body, and the listing for the terminal's gateway |
| 3 | player known | 404 `PLAYER_NOT_FOUND` | `PlayerPayouts` |
| 4 | identity verified | 402 `WITHDRAWAL_BLOCKED_KYC` with `kycStatus` and the original's message | `PlayerPayouts` |
| 5 | turnover, as the preview answers it (W:298-307) | 402 `WITHDRAWAL_BLOCKED_TURNOVER`, no amount | the preview |
| 6 | weekly, then monthly limit | 402 `WITHDRAWAL_BLOCKED_WEEKLY_LIMIT` / `_MONTHLY_LIMIT` | `PlayerPayouts`, `WithdrawalLimits` |
| 7 | forfeitable records: available below the amount (W:775-795; the preview's shortfall with `confirmationRequired`) | 402 `INSUFFICIENT_FUNDS` | the preview |
| 8 | forfeit would leave too little | 422 `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT` | the preview |
| 9 | forfeit not confirmed | 402 `WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION` | the preview |
| 10 | terminal eligible | 422 `TERMINAL_NOT_ELIGIBLE` | the terminal listing (R5) |
| 11 | destination required and named | 422 `DESTINATION_REQUIRED` | the listing |
| 12 | crypto address present | 422 `WALLET_ADDRESS_REQUIRED` | the listing |
| 13 | coin in the terminal's list | 422 `CRYPTO_CURRENCY_NOT_SUPPORTED` with the list | the listing |
| 14 | destination the player's and active | 422 `INVALID_PAYOUT_DESTINATION` | `PlayerPayouts` |
| 15 | the hold (W:622-652): the preview's other refusals here, then the hold's own | 409 `WITHDRAWAL_REQUEST_ALREADY_PENDING`; 402 `INSUFFICIENT_FUNDS`; 422 `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT` | the preview, then the wallet |

**What the preview and the hold say:**

- The preview answers its refusal in a 200 body (`services/wallet/.../WalletEntity.scala:531-542`).
- The hold answers 409 for every refusal (`Wire.scala:132-139`). This service maps each refusal to the original's status.
- The original answered the hold's turnover refusal as 500, an accident of its catch-all (`W:642-678`). Here it is the 402 of step 5, which the preview already gave.

**Gigadat's fields** (settled with the user, 2026-10-31): FR-004 refuses a missing one at the request, a divergence from the original, which accepted it and sent it to manual payout at approval. The player corrects it at once, and no operator settles by hand what a field would have paid. `Approval` keeps the original's manual fallback for a request that predates this check (migrated, spec 019).

**Rationale**: FR-005's order put the terminal checks before the funds and forfeit gates. The
original's order is the specification, and it decides which refusal a player sees when two apply.
FR-005, FR-005b and FR-006 are amended to this table. No scenario changes, since each scenario fails
one gate.

**The amount**:

- Minor units (spec 001 FR-026). Zero, a negative or a decimal is 422 `INVALID_AMOUNT`, the original's answer to an amount it could not take.
- The original truncated `amount * 100` for its limit check and rounded HALF_UP for the wallet. Neither is possible here.

## R5. Terminals: the gateway's listing, held here and refreshed

**Decision**:

- `TerminalCatalogue`, a key value entity keyed by the slug, holds the gateway's
  `GET /v1/payments/withdrawal-terminals` answer.
- A recurring timer refreshes it every 60 seconds.
- The request path and the listing read it. A request finding it empty (the first minutes after a deploy) reads the gateway once and stores the answer.
- The gateway's answer carries everything eligibility and the payout need: `id`, `subBrandId`, `name`, `scheme`, `gateway`, `active`, `supportsWithdrawal`, `requiresPayoutDestination`, `coins[]`, `hasStatus` and `reviewWindow` (`services/psp-gateway/.../payouts/Terminals.scala:29-38`).

**Rationale** (settled with the user, 2026-10-31):

- SC-005 and the 2026-10-08 clarification put the wallet alone in a request's path.
- The original reads its terminal twins on every request (`E:95-153`). A terminal an operator deactivates is offered here for up to a minute longer. The request still reaches the gateway, which refuses an inactive terminal (`Initiate.scala:43-66`) as `rejected`, and FR-018 returns the money.

**Eligibility** is the original's rule, unchanged (`E:79-134`):

- `active` (case-insensitive);
- `supportsWithdrawal`;
- a scheme containing `VISA` only for a player with a deposit whose scheme contains `VISA`;
- ranked by a gateway the player deposited with first, then name, then id.

The listing's `currency` filter is the player's wallet currency. The original's terminal route
defaulted it to GBP (`TR:68-71`); the request's currency is used here.

## R6. The routes: four endpoints, the outcome route where the gateway calls it

**Decision**: four endpoints, one prefix each, with the slug a literal, as kyc's R14:

| Endpoint | Prefix | Audience |
|---|---|---|
| `PlayerEndpoint` | `/v1/casino/brands/<slug>/players` | `Routes.player(slug)`. `withdrawal-history` is the operator's, by `withAcl` |
| `OperatorEndpoint` | `/v1/casino/brands/<slug>/withdrawal-requests` | `Routes.operator(slug)` with the finance-or-admin check in the handler |
| `ConfigEndpoint` | `/v1/casino/brands/<slug>/withdrawal-config` | `Routes.operator(slug)` with the admin check |
| `OutcomeEndpoint` | `/v1/casino/brands/<slug>/withdrawals` | `Routes.service(Some(GrantTarget.Route("payouts", "POST", "/v1/casino/brands/{brand}/withdrawals/{requestId}/outcome")))`: the grant `payments`/`psp-gateway` holds |

**The outcome route**: the gateway delivers to
`POST /v1/casino/brands/{brand}/withdrawals/{requestId}/outcome`
(`services/psp-gateway/.../delivery/Receivers.scala:30-31`), unsigned and admitted by the grant
(`modules/core/module.json:539-547`). The body is
`{outcome: completed|failed|reversed, providerReference, reason?}`.

- 2xx is delivered.
- 429, 5xx or no answer is retried.
- Any other 4xx is refused for good.

So a reference mismatch (FR-020) answers 409 and records it, and an unknown request answers 404. The
original's `/internal/v1/casino/withdrawal-requests/{id}/outcome` and its secret are gone.

**Roles** (FR-001, FR-015):

- `Routes.operator(slug)` admits `brand_<slug>` or `platform_admin`; the finer role is the handler's, as kyc's compliance check is.
- The original admits `brand_admin`, `resource_admin`, `platform_admin`, `brand_finance` and `resource_finance` to the queue and its decisions. It admits the admin three alone to `withdrawal-config`.
- The operator realm (`brands/_realm/eitheror-operators.json`) has every one of these but `brand_finance`, which exists only in the per-brand template. `brand_finance` is added to the operator realm with this spec, beside `brand_admin`.

## R7. The payout: one call, every answer read, nothing in doubt offered to a person

**Decision**: on approval, the workflow decides automated or manual as the original's
`resolveApprovalOutcome` (`W:1586-2282`). It is automated when:

- the terminal supports withdrawal and its gateway is one of the four;
- the request carries what that gateway needs:
  - Unity Finance: a destination (holder names and IBAN from `PlayerPayouts`) and the player's email;
  - Cozeni and Morphosis: a wallet address;
  - Gigadat: the method with a mobile number (ETO), or account, institution and transit numbers (ACH).

Otherwise the request is awaiting manual payout.

**The email** (settled with the user, 2026-10-31): Unity Finance's payout carries the player's email,
as the original's did. No fact carried it, so `casino.players` gains it, additively:

- `player_registered.email: Option[Personal[String]]`, the address the player registered with;
- a new family `player_email_changed(playerId, email: Personal[String], occurredAt)`, from `players`'
  `EmailPromoted` and `EmailChanged`, when the primary address changes.

It is a family of its own, not a field on `player_profile_updated`, because `kyc` judges a profile's
completeness from that fact alone (009 FR-016): an update carrying only the email would read as a
profile emptied. `PlayerPayouts` keeps the primary address `Personal` beside the names and blanks it on
erasure. `players`' publisher change (`facts/Facts.scala`) is made in this spec's build, since the
family is this spec's need.

**The call**:

- `POST /v1/payments/withdrawals` with `requestId` (the idempotency key), `brand`, `subBrandId`, `terminalId`, `amount` (minor units), `currency`, `method`, and the destination fields of `Initiate.scala:95-112`.
- The gateway claims the request id before it calls the provider; a repeat answers the first answer, or 409 `initiation_in_progress` until there is one (`Initiate.scala:36-41, 68-88, 130-131`).

**Each answer**:

| Answer | What the workflow does |
|---|---|
| `completed` | confirm the hold; complete |
| `initiated` with a reference | record it and wait |
| `already_initiated` | record it and wait |
| `rejected` | release as `failed` with `provider_rejected`; tell the player to try another terminal (FR-018) |
| `in_doubt` | record it and wait |
| 409 `initiation_in_progress` | wait |
| an unreachable gateway, a 5xx, a body it cannot read | record it and wait |
| 400, 403 or 502 `provider_not_configured` | release as `failed`: nothing reached a provider |

Waiting leads only to the outcome route, the status loop or the provider's window. Awaiting manual
payout is reached only from approval, when the terminal is not automated (FR-018).

## R8. Waiting: the outcome route, the status loop and the provider's window

**Decision**: the workflow waits with a timer:

- **A provider with a status to ask** (`hasStatus`, Unity Finance): it asks
  `GET /v1/payments/withdrawals/{requestId}` at 30 minutes, then every 5 minutes.
  - `completed`, `failed` and `reversed` settle the request.
  - `no_record` marks it needs review and stops.
  - `pending` and 502 `status_unavailable` wait 5 minutes more.
- **A provider with no status to ask:** it waits its `reviewWindow` from the listing (Cozeni PT24H,
  Gigadat and Morphosis P5D; `providers/Catalogue.scala:55-100`), then marks the request needs review.

The outcome route, whichever comes first, settles it. Once the request is settled, the outcome route
answers the same outcome with 200 and moves nothing. A different outcome answers 409, except
`reversed` on a completed request (FR-019, FR-020).

**FR-022's count** (requests older than 24 hours still waiting) is a query of the queue view by
payout status and initiation time.

**Rationale**: the original's sweeper timings (`S:75-78, 123-134`) are kept per request. The polling
of a Unity Finance payout that never settles is kept unbounded, as the original's.

## R9. Money: every step keyed by the request id, retried until the wallet answers

**Decision**: the hold, release, confirm and reverse each carry `key = <request id>/<step>` and
`requestId`:

| Step | Wallet command | Notes |
|---|---|---|
| hold | `POST …/holds` | `key` is the request id; the wallet takes it as the request id (`WalletEntity.scala:525`) |
| cancel, reject, failure | `POST …/holds/release` | `outcome` cancelled, rejected or failed |
| completion | `POST …/holds/confirm` | |
| reversal | `POST …/holds/reverse` | |

Each step is a workflow step that retries a transport failure without end (FR-024).

- A refusal is read as the wallet means it:
  - `REQUEST_NOT_PENDING` on a confirm already made is the first outcome;
  - a reverse of a reversed request answers `idempotent: true`.
- The wallet keeps 1,000 recent keys (`WalletState.scala:133`). The workflow's own state is the lifetime record, and a step done is never sent again.
- The hold's answer within the 2-second bound of SC-005 is the endpoint's. A slower hold leaves the workflow trying; the endpoint answers 503 `SERVICE_UNAVAILABLE`. If the hold lands later, the request exists pending review, as the player can see, and is theirs to cancel.

## R10. The brand's withdrawal limits

**Decision**: `WithdrawalLimits`, an event sourced entity keyed by the slug, holds a weekly and a
monthly limit per currency (FR-005a). `ConfigEndpoint` serves:

- `GET withdrawal-config` → `{limits: {<currency>: {weekly, monthly}}}`, each limit in minor units or null;
- `PUT` with the same shape. It refuses a negative or decimal amount naming the field, and is written to `platform.audit` with the operator.

Usage is the player's requests in `PlayerPayouts` (R3): the original's statuses, the original's two
windows and its `used + amount > limit` test (`L:32, 78-110`).

**What moves elsewhere**:

- The original's route also carried the brand's deposit turnover terms (`withdrawalTurnoverMultiplier`, `withdrawalTurnoverDefinition`). Those are the wallet's (002 FR-004a).
- The operator console's brand configuration page saves limits here and turnover terms to the wallet. It is a frontend change under spec 001's list.

## R11. Facts and the contract change

**Decision**: `casino.withdrawals` keeps its six families (`contract/.../facts/Others.scala:80-122`),
read today by `attribution` (requested, completed, failed, reversed) and `players` (requested,
completed, cancelled, failed).

**The record key** is the player id, as the contract has it (`def key = playerId`), not the request id
FR-025 named: both readers act per player (the erasure hold, the affiliate's ledger), and one key keeps a
player's facts in order. The request id is `payoutId` on every family. FR-025 is amended.

**The additive change** this spec makes, every field optional so both readers are unaffected:

- `gateway: Option[String]` on all six families;
- `providerReference: Option[String]` on completed, failed and reversed;
- `terminal: Option[String]` on the five that lack it;
- `reason: Option[String]` (a code, never text) on cancelled.

**What each family carries**:

- `withdrawal_cancelled.actor` is `player` or `operator`. A rejection is `withdrawal_cancelled` by an operator, as the original's `export_withdrawal` maps `REJECTED` (`export_withdrawal.tl:7-65`).
- `withdrawal_failed.failureReason` is a code: `provider_rejected`, `payout_failed`, `manual_payout_failed`, or the gateway's reason code.
- An operator's free-text reason is `Personal` on the request and never on a fact (FR-025, FR-027).

**Two changes from the original's facts**:

- The original's `export_withdrawal` emitted `withdrawal_requested` again on approval. Here approval is `withdrawal_approved` alone (FR-026).
- The original wrote `amount_cents` from the twin, which was the amount paid after a cap's void. Here the fact carries the amount paid, which the hold's answer reports as `paidAmount`.

## R12. Personal data and erasure

**Decision**: these are `Personal` under the player's subject:

- the destination's holder names and IBAN;
- the player's email (R7);
- a wallet address, a mobile number, a bank account, institution and transit numbers;
- the names from `casino.players`;
- every operator reason and review note.

The IBAN is held whole for the payout and shown as its last four (the original's `ibanLastFour`).

**Erasure**:

- `PlayerPayouts` and each `WithdrawalRequest` implement erasure by blanking those fields, as kyc's records do.
- `ServiceEndpoint` serves `POST /v1/casino/brands/<slug>/payouts/erasure/players/{player}` for `players`' `ErasureWorkflow`, until ankka 042's `erase(subject)`.
- `players` already holds an erasure while a withdrawal is open (008 FR-037). Its `ErasureWorkflow.targets` gains `payouts` (*Elsewhere*).

## R13. Destinations

**Decision**: the original's rules, unchanged (`PD:97-419`, `IbanValidator`):

- **IBAN:** normalised (whitespace stripped, upper-cased), checked by the structure pattern and mod-97.
- **Names:** trimmed, lower-cased, NFD with combining marks stripped, and both equal to the profile's. Both profile names blank is `PROFILE_INCOMPLETE`.
- **Default:** the first destination saved is the default. Setting another as default unsets it.
- **Delete:** the destination is kept deleted with its fields blanked, and no new default is promoted.

The scheme stays `eubanking` and the currency the wallet's.

## R14. Local run and CI

**Decision**:

- `services/payouts/` runs locally on 9004, with Postgres on 5439, both free (`Justfile`, the READMEs).
- The fakes `FakeWallet` and `FakeGateway` serve the integration kit. `FakeGateway` scripts the gateway's answers as spec 005's recordings prove them.
- `just payouts-test`, `payouts-it` and `payouts-run`; CI gains the service's filter and job.

## For ankka

Nothing new: 040 (the grants, as built), 042 (`Personal`, erasure) as for 008, 009 and 010, and 032
(timers).

## Elsewhere

- **Spec 008 (`players`)**: `ErasureWorkflow.targets` gains `payouts` (R12). The publisher's
  `player_registered.email` and `player_email_changed` (R7) are made in this spec's build.
- **Every reader of `casino.players`** (`kyc`, `attribution`, `game-provider`, `responsible-gambling`)
  is rebuilt on the new contract before `player_email_changed` is first published, as with every
  additive family; until then the family is one it does not know.
- **`brands/_realm/eitheror-operators.json`**: `brand_finance` is added (R6), settled with the user on 2026-10-31. This is done in this spec's build, since it is this repository's realm.
- **Spec 001 `contracts/grants.md:24`** lists one of the three payout routes `payments` grants; the manifest and `Layout` have three. It is corrected here.
- **The operator console**: brand configuration saves limits here and turnover terms to the wallet (R10). Amounts in minor units. Both are among spec 001's frontend changes.

## Found during implementation

- **2026-10-31, the turnover refusal names no amount** (settled with the user). The wallet's hold
  preview (`services/wallet/.../WalletEntity.scala:531-542`) reports a turnover refusal by its code and
  message only, and checks the available amount before turnover, as `Resolver.withdraw` does. payouts
  passes the preview's first refusal through as R4's steps 5 and 7 in the wallet's order: insufficient
  funds before turnover when both apply, and `WITHDRAWAL_BLOCKED_TURNOVER` with no `totalRemaining`. Both
  are changes from the original, which named the remaining amount and checked turnover first; the
  scenario and FR-006 are amended. The wallet was not changed.
- **2026-10-31, the hold does not refuse an unconfirmed forfeit.** The wallet's hold reads
  `playerConfirmed` nowhere; its preview says `confirmationRequired`. payouts refuses
  `WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION` itself (R4 step 9) when the preview requires
  confirmation and the request did not confirm.
- **2026-10-31, the erasure route** is `POST /v1/casino/brands/<slug>/payouts/erasure/players/{playerId}`,
  the path `players`' `ErasureWorkflow` calls on every target (settled with the user; contracts/routes.md).
- **2026-10-31, the listing carries the terminal's sub-brand** (settled with the user). The gateway's
  payout (R7) names `subBrandId`, which picks the credential, and the listing gave none. psp-gateway's
  listing row gains `subBrandId`, additively (005 contracts/payouts.md); payouts holds it on the
  catalogue and on the request's terminal and sends it, as the original read `sub_brand_id` off the
  terminal (`W:1607`).
- **2026-10-31, `deposit_confirmed` carries the deposit's gateway and scheme** (settled with the user).
  The fact had neither, though the wallet records both on its event; eligibility (R5) needs them.
  `deposit_confirmed` gains `psp: Option[String]` and `scheme: Option[String]`, additively, set by the
  wallet's publisher (002 contracts/facts.md, 001 contracts/topics.md). A deposit published before
  carries none and reads as not VISA, which fails closed, as the original's unrecorded schemes did.
- **2026-10-31, the name check stays exact** (settled with the user). terminals.feature saved "A. Example"
  against a profile of "Anna Example", which R13 and the original refuse. The scenario's profile is now
  "A. Example"; the rule is unchanged.
- **2026-10-31, the listing's currency when the query names none** is the one the player registered in,
  which their wallet is opened in: `player_registered.currency`, held on `PlayerPayouts`. The original read
  the player twin's `walletCurrency` and fell back to GBP; with none known this answers 400 naming
  `currency`.
- **2026-10-31, the outcome route admits the grantee alone** (settled with the user). The contract
  gave the route `Routes.service(Some(grant))`, but the service class also admits every service of the
  brand's project, so `players` or `wallet` could settle a payout. FR-001 says "that identity alone":
  the route now admits only the callers the grant names (`payments`/`psp-gateway`), and
  contracts/routes.md says so. The route's table is unchanged. `OutcomeSuite` holds it.
- **2026-10-31, payouts serves the player's export too** (settled with the user). Adding `payouts` to
  `players`' targets (T056) adds it to the export as well, because spec 008 FR-040 has every brand service
  serve both routes. `ServiceEndpoint` gains `GET /exports/players/{playerId}`, for `players` alone. It
  answers the player's destinations as their own listing shows them, and each request with its money,
  statuses and times. The whole IBAN and the operators' reasons are left out, as kyc's part leaves out
  its objects. contracts/routes.md says so; `ErasureSuite` holds it.
- **2026-10-31, R4's order restored where it had drifted** (settled with the user). Reading the original for
  the driver (T052) found three places where R4 departed from `W:180-873`:
  - presence was checked after the amount's value, where the original asks for amount, currency and
    terminal first (W:213-220);
  - a plain shortfall was refused straight after identity, where the original refuses it only at the hold,
    after the limits and the terminal and destination checks (W:622-652);
  - "already pending" was refused at the preview, where the original refuses it at the hold too (W:642).
  `Gates` now answers each where the original does. A shortfall with forfeitable records stays at row 7,
  as in the original (W:775-795): the wallet's refusal sets `confirmationRequired` exactly then. One case
  is left, already declared above: a player both short and turnover-blocked is told they are short,
  because the wallet's preview reports its first refusal only. `GatesSuite` (unit and integration) holds
  the order; a follow-up for spec 002 is for the preview to report turnover as well.
- **2026-10-31, the limit refusal in the original's names** (settled with the user). The limits came to the
  original in #733 (`15b4ce94`), after the build the kind stack ran (`a78decf1`); recording them meant
  rebuilding casino-service from main (T053). The original's refusal answers `weeklyLimit`, `weeklyUsed`,
  `weeklyRemaining` (and the `monthly` forms) and `currency`, and its player site reads those names
  (`WithdrawalPage.tsx`), where the design had `limit`, `used`, `remaining`. This service answers the
  original's names, in minor units (routes.md). Its config route's limits are `weeklyWithdrawalLimitEur` and
  `monthlyWithdrawalLimitEur`; wire.md had them as `weeklyLimitEur` and is corrected.

## Design finished

2026-10-31: analyze reported nothing above LOW.
