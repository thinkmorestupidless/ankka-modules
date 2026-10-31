# Feature Specification: Payouts

**Feature Branch**: `007-payouts`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The `payouts` service of a brand's project: a player's withdrawal from request to money leaving, as one workflow per request — the eligibility gates, the hold on the wallet, the operator's review queue, the payout through the shared payment gateway, waiting for the provider's outcome with a timeout and a status loop, completion or failure, reversal after a paid payout, the player's cancellation, saved payout destinations, and the `casino.withdrawals` facts the brand's readers and the affiliate protocol consume."

## Context

The original's withdrawal is a 3,088-line route file in `casino-service` (`WithdrawalRoutes.scala`),
seven TL handlers on the wallet twin (`create-`, `approve-`, `reject-`, `cancel-`, `complete-`,
`fail-`, `reverse-withdrawal-request.tl` and `mark-withdrawal-awaiting-payout.tl`), a five-minute
reconciliation sweeper that pages every withdrawal-request twin in the brand to find the in-flight
ones (`PayoutReconciliationSweeper.scala`), a terminal eligibility rule shared by the picker and the
request (`WithdrawalTerminalEligibility.scala`, decisions 2, 3, 5 and 6 of ADR 0038), and a payout
destination store with an AML name match (`PayoutDestinationRoutes.scala`). Money moves in the wallet
only: a request debits real balance at once, a cancellation, rejection or failure credits it back as
`withdrawal_return`, completion moves nothing, and a reversal of a completed withdrawal credits it
back (spec 002, User Story 4). This spec keeps every one of those rules and replaces the route file
and the sweeper with one workflow per request.

Payout execution is as ADR 0038 decided: manual payout is the permanent default, automated payout is
opt-in per terminal, and anything ambiguous after a provider was asked to pay is *in doubt* and
reconciled — never parked for a human to pay a second time. Four providers pay out today: Unity
Finance (bank, polled by status), Cozeni and Morphosis (crypto, webhook only), Gigadat (Canadian
ETO/ACH, webhook only); the gateway's adapters are spec 005's, this spec sees one payout route.

## Clarifications

### Session 2026-10-08

- Q: Where do the eligibility gates read from — a call to `players`, `kyc` and `wallet`, or facts? →
  A: Both, by constitution IV. What the wallet must *decide* (turnover, forfeiture, the clawback,
  funds) is a call in the request path, because the answer changes the request's outcome and the
  wallet is the ledger. What is a fact about the player (identity verified or not, account closed,
  the schemes and gateways they deposited through) is a view `payouts` builds from `casino.players`
  and `money.transactions`. No call to `players` or `kyc` is made, so a withdrawal is refused or
  accepted with the wallet as the only service in its path.
- Q: Who computes the clawback a hold applies? (spec 002's open question) → A: The wallet: it holds
  every restricted-funds record and records at award the real balance and total deposits the
  clawback formula needs (spec 002 FR-015a). `payouts` asks the wallet for a *hold preview* (002
  FR-023a) to tell the player what a hold would forfeit, then places the hold; the wallet computes
  and applies the clawback itself (002 FR-023). Settled in the wallet's favour at reconciliation.
- Q: Terminals belong to the shared `payments` project; how does a brand's `payouts` know which
  terminals a player may withdraw to? → A: Over the one grant `payments` makes to the brand (spec 001
  FR-016): `psp-gateway`'s payout routes, read here as a family of three — list the brand's
  withdrawal-capable terminals, initiate a payout, read a payout's status. Spec 005 serves them.
  `payouts` keeps no copy of the catalogue; it applies the eligibility rule to what the gateway lists.
- Q: A provider refuses the payout outright at initiation. Release the hold (the plan's
  "fail-and-release") or park the request for manual payout (the original)? → A: The original: it
  parks as awaiting manual payout, because a refusal at initiation means nothing reached the
  provider and a human can pay another way, while the player's money has already been taken. The
  release happens only when an operator marks the manual payout failed. Confirmed below as an open
  product question, since the plan said otherwise. *(Superseded 2026-10-25: a rejection releases the
  hold; FR-018.)*
- Q: The affiliate notifications the original sends (a withdrawal at approval, a reversal on failure
  or chargeback)? → A: Gone. `payouts` emits `casino.withdrawals` facts and the brand's `attribution`
  service derives spec 012's four withdrawal kinds from them; which of them commission counts is spec
  013's rule, not this service's.
- Q: The sweeper, the `payout_status` stamps (`INITIATED`, `NEEDS_REVIEW`, `RESOLVED`) and the 30-minute
  cut-off? → A: The workflow's own timers. A payout in doubt waits for the outcome route; after the
  provider's first poll delay it asks the gateway for status and loops on the provider's interval;
  a webhook-only provider has no status to ask, so after its review window the request is marked
  *needs review* for an operator to settle by hand. The windows are the original's: 30 minutes then
  every 5 minutes for a polled provider, 24 hours for Cozeni, 5 days for Gigadat and Morphosis.
- Q: The outcome route the gateway calls (`/internal/v1/casino/withdrawal-requests/{id}/outcome`,
  admitted by a shared secret)? → A: A route granted to `payments`/`psp-gateway` under ankka 040
  (spec 001 FR-016), admitted by the caller's identity; no secret.
- Q: Amounts on the player's route — the original takes major units as a decimal? → A: Minor units,
  as spec 001 FR-026 decided for every boundary; a decimal is refused naming the amount. The
  withdrawal page is one of the frontend changes spec 001 lists, beside base URLs.
- Q: Is a self-excluded, suspended or closed player refused a withdrawal? → A: The original has no
  such gate: it checks the player exists and is verified, nothing about exclusion, and a player who
  excludes themselves is normally owed their balance. Kept as the original; an open product question
  below asks whether a closed account should be refused.

### Session 2026-10-08 (reconciliation, wave 1)

- Q: Did the wallet take the clawback? → A: Yes (spec 002 FR-015a, FR-023, FR-023a); FR-007 no longer
  passes one.
- Q: Did 005 and 001 take what this spec asked of them? → A: Yes: 005 FR-009a lists terminals with
  the fields FR-010 needs, 005 FR-004 and FR-009 are the initiation and status read under the grant;
  001 FR-011 adds `player_kyc_submitted` and lists `payouts` as a reader of `casino.players` and
  `money.transactions`.
- Q: Which `casino.withdrawals` families reach the affiliate protocol? → A: Four of the six, one to
  one by name (spec 012 FR-030); `withdrawal_approved` and `withdrawal_cancelled` produce no
  attribution fact.

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: Which KYC families does the identity gate read? → A: Four of spec 009 FR-030's: verified,
  rejected, submitted and pending (FR-003); "under review" is answered for a document pending or an
  account set pending.

### Note from spec 005 (2026-10-18), read into FR-018 on 2026-10-31

- The gateway's payout answers are spec 005's `contracts/payouts.md`: `initiated`, `completed`,
  `rejected`, `in_doubt` and `already_initiated`, the original's words. FR-018 reads all but
  `already_initiated`, which should be read as *initiated*: the provider already holds the request
  (spec 005 research, *Findings for other specs* 5; kept by the decision of 2026-10-19, spec 005 *Clarifications*). A provider's `401`/`403` is `rejected` / `unauthorised` for every provider.
- A payout terminal's coins are its `supportedCurrencies` property, as the original's casino-service
  reads it; the gateway's `GET /v1/payments/withdrawal-terminals` answers them as `coins[]`.

### Session 2026-10-25

The markers left open for product and regulatory decisions, settled with Trevor before 008 is planned.

- Q: A provider rejects the payout at initiation: park it for a manual payout, or release the hold?
  → A: Release the hold and tell the player to try another terminal; manual payout is only ever an
  operator's act (FR-018).
- Q: May a suspended, self-excluded or closed account withdraw? → A: Yes, as the original, gated by
  identity and funds: the money is the player's (FR-005, Assumptions).

### Session 2026-10-31

- Q: The original refuses a request that would pass the brand's weekly (rolling seven days) or monthly
  (UTC calendar month) withdrawal limit (`WithdrawalLimitChecker`, `withdrawal-config`); this spec had
  none. Port them? → A: Ported, per currency: `payouts` serves `withdrawal-config`'s two limits (the
  turnover terms there stay the wallet's, spec 002 FR-004a) and counts usage from its own requests, so
  there is no outside read to fail open on; the original's EUR only becomes a limit per currency
  (FR-005a, FR-005b).
- Q: Saving a destination matches the holder's names to the profile (FR-013), and `payouts` calls only
  the wallet and the gateway. Where do the profile's names come from? → A: A view of `payouts`' own,
  from `player_registered` and `player_profile_updated` on `casino.players` (spec 009's addition), the
  names `Personal` and erased with the player (FR-003).
- Q (analyze): The original accepts a Gigadat request missing its method fields and sends it to manual
  payout at approval; FR-004 refuses it. → A: refused at the request naming the field (FR-004), a change
  from the original (research R4).
- Q (plan): Unity Finance's payout carries the player's email, and no fact carries it. → A:
  `casino.players` gains it: `player_registered.email` and a new family `player_email_changed`, both
  `Personal`, published by `players` (research R7).
- Q (plan): the operator realm has no `brand_finance`, which the original admits to the queue. → A: it
  is added to the operator realm beside `brand_admin` (research R6).
- Q (plan): eligibility needs the gateway's terminal listing, and SC-005 puts the wallet alone in a
  request's path. → A: `payouts` holds the listing per brand and refreshes it every 60 seconds; a terminal
  deactivated within that minute is refused by the gateway at initiation and the money returned
  (research R5).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A verified player requests a withdrawal and the money is held (Priority: P1)

A player with verified identity, no deposit turnover outstanding and no restricted funds in play
chooses an amount and an eligible terminal, supplies what that terminal needs (a saved bank
destination, a crypto wallet address, a Gigadat method and account), and submits. The wallet holds
the amount at once, the request appears in the player's account as pending review and in the brand's
queue, and a `withdrawal_requested` fact is emitted. A second request while one is pending is refused.

**Why this priority**: every other story is a gate before this one or a resolution after it.

**Independent Test**: replay the withdrawal section of `TestScenario7_CasinoGate3_FullPlayerJourney`
and `create-withdrawal-request.test.tl`; the Playwright `withdrawal-us1-new-destination` and
`withdrawal-us2-saved-destination` journeys drive the frontend end to end.

**Acceptance Scenarios**:

- added `features/payouts/requests.feature`: a request by a verified player with nothing pending holds the amount and is listed pending review
- added `features/payouts/requests.feature`: a second request while one is pending review is refused and nothing moves
- added `features/payouts/requests.feature`: an amount that is zero, negative or a decimal is refused naming the amount
- added `features/payouts/requests.feature`: a request to a terminal that requires a destination is refused when none is named
- added `features/payouts/requests.feature`: a request to a crypto terminal is refused when no address is supplied
- added `features/payouts/requests.feature`: a request to a crypto terminal naming a coin it does not support is refused naming the coins it supports
- added `features/payouts/requests.feature`: a destination that is not the player's own is refused as invalid
- added `features/payouts/requests.feature`: a request above real balance is refused as insufficient funds and nothing moves

---

### User Story 2 - A player is blocked by identity, turnover, a withdrawal limit or a bonus in play (Priority: P1)

The gates before the hold, each telling the player exactly why and what to do next. A brand may set
a weekly and a monthly withdrawal limit per currency; a request that would pass one is refused.

An admin of the brand reads and sets the limits.

**Why this priority**: regulatory (identity), commercial (turnover) and the one gate that takes money
from the player (forfeiting a bonus), so the wording and the order matter.

**Independent Test**: replay the KYC, turnover and bonus-forfeit cases of `WithdrawalRoutesSpec`
and the `WITHDRAWAL_*` branches of `create-withdrawal-request.test.tl`.

**Acceptance Scenarios**:

- added `features/payouts/requests.feature`: a player whose identity is not verified is refused telling them what to do
- added `features/payouts/requests.feature`: a verified player passes the identity gate
- added `features/payouts/requests.feature`: a request while deposit turnover is pending is refused as blocked by turnover
- added `features/payouts/requests.feature`: a request whose clawback under a funds and winnings policy would leave real balance short is refused naming the most they could withdraw
- added `features/payouts/requests.feature`: a request with a record that forfeits on withdrawal is refused until the player confirms the forfeit
- added `features/payouts/requests.feature`: a request confirming the forfeit places the hold, forfeits the record and applies the clawback once
- added `features/payouts/requests.feature`: a request with a record under a funds policy requires confirmation naming a clawback of zero
- added `features/payouts/requests.feature`: a request above real balance alone with restricted funds in play is refused as insufficient funds, not as insufficient after forfeit
- added `features/payouts/requests.feature`: a request that would pass the brand's weekly withdrawal limit is refused naming the limit, what was used and what remains
- added `features/payouts/requests.feature`: a request that would pass the brand's monthly withdrawal limit is refused naming the limit, what was used and what remains
- added `features/payouts/requests.feature`: a withdrawal request cancelled, rejected or failed does not count towards a withdrawal limit

---

### User Story 3 - The player is offered the terminals they may withdraw to (Priority: P2)

Before requesting, the player's account lists the terminals they may use, ranked: every active,
withdrawal-capable terminal of the brand; a VISA terminal only if they have deposited by VISA;
terminals on a gateway they have deposited with first, then by name. What is offered and what is
accepted cannot differ. A player may save bank destinations in their own name.

**Why this priority**: ADR 0038's picker rules are a licence condition (VISA) and the frontend
preselects the first entry, so the order is part of the contract.

**Independent Test**: replay `WithdrawalTerminalRoutesSpec`, the eligibility cases of
`WithdrawalRoutesSpec` and `PayoutDestinationRoutesSpec`.

**Acceptance Scenarios**:

- added `features/payouts/terminals.feature`: a VISA terminal is absent for a player who never deposited by VISA
- added `features/payouts/terminals.feature`: a VISA terminal is present for a player who deposited by VISA
- added `features/payouts/terminals.feature`: a terminal of a payment provider the player deposited with is listed first, in the same order on every read
- added `features/payouts/terminals.feature`: a terminal that is inactive or does not pay withdrawals is absent and a request naming it is refused
- added `features/payouts/terminals.feature`: a destination is saved only in a name that matches the player's
- added `features/payouts/terminals.feature`: saving a destination with no name on the profile is refused as profile incomplete
- added `features/payouts/terminals.feature`: setting a second destination as default makes the first no longer default
- added `features/payouts/terminals.feature`: deleting the default destination leaves it unoffered and no destination default

---

### User Story 4 - An operator reviews the queue and approves or rejects (Priority: P1)

A finance or admin operator of the brand sees pending requests with the player's identity status,
amount, terminal, destination and age, filtered by status, payout status, player, identity status,
method and amount; opens one; approves or rejects it with a reason. Approval starts the payout.
Rejection returns the money and records the reason.

**Why this priority**: the approval is what moves money to a person.

**Independent Test**: replay the queue, approve and reject cases of `WithdrawalRoutesSpec`,
`approve-withdrawal-request.test.tl` and `reject-withdrawal-request.test.tl`.

**Acceptance Scenarios**:

- added `features/payouts/review.feature`: an operator with a finance or admin role approves a pending request and the withdrawal payment is pursued
- added `features/payouts/review.feature`: an operator rejects a pending request with a reason, the held amount returns and the reason is recorded
- added `features/payouts/review.feature`: approving or rejecting a request already approved is refused as not pending
- added `features/payouts/review.feature`: an operator whose roles name another brand is refused this brand's queue
- added `features/payouts/review.feature`: a player cancels a request pending review and the amount returns
- added `features/payouts/review.feature`: a player cancelling an approved request is refused as not cancellable

---

### User Story 5 - The payout is executed, reconciled, and settled by hand when it must be (Priority: P1)

On approval, a terminal with automated payout and the request's destination or address makes one
payout call to the gateway with the request id as its idempotency key. The gateway answers
completed, initiated, in doubt, or rejected. Completed confirms the hold. Initiated and in doubt
wait: the provider's outcome arrives on the granted outcome route, or the workflow polls status
after 30 minutes and every 5 minutes until the provider's answer is final, or, for a provider with
no status to ask, the request is marked needs review after the provider's window. Rejected at
initiation releases the hold and tells the player to try another terminal. A terminal without
automated payout parks the request awaiting manual payout, where an operator confirms the payout or
marks it failed. Nothing in doubt is ever offered for manual payment.

**Why this priority**: the duplicate payout is the costliest mistake in the system; the original's
rule that "ambiguous means in doubt, never manual" is the heart of this story.

**Independent Test**: replay `TestScenario10_PaymentsHTTPAPIGate3_PayoutLifecycle`,
`PayoutReconciliationSweeperSpec`, `complete-`, `fail-`, `reverse-` and
`mark-withdrawal-awaiting-payout.test.tl`, and the approve-path cases of `WithdrawalRoutesSpec`.

**Acceptance Scenarios**:

- added `features/payouts/settlement.feature`: a withdrawal payment the gateway answers completed confirms the hold and completes the request
- added `features/payouts/settlement.feature`: an initiated withdrawal payment whose outcome arrives as completed completes the request
- added `features/payouts/settlement.feature`: an initiated withdrawal payment whose outcome arrives as failed returns the held amount and fails the request
- added `features/payouts/settlement.feature`: a withdrawal payment in doubt to a polled provider is asked for its status after thirty minutes
- added `features/payouts/settlement.feature`: a status that is not final is asked again five minutes later
- added `features/payouts/settlement.feature`: a final status settles the request accordingly
- added `features/payouts/settlement.feature`: a provider saying the withdrawal payment was never created marks the request needs review and stops the polling
- added `features/payouts/settlement.feature`: a withdrawal payment in doubt to a provider that only sends webhooks is marked needs review after the provider's window
- added `features/payouts/settlement.feature`: a withdrawal payment the gateway cannot answer is in doubt and reconciled, never awaiting manual payment
- added `features/payouts/settlement.feature`: a request rejected at initiation returns the money and tells the player to try another terminal
- added `features/payouts/settlement.feature`: a request to a terminal without automated withdrawal payment awaits manual payment with the money held
- added `features/payouts/settlement.feature`: an operator confirms a manual payment and the hold is confirmed
- added `features/payouts/settlement.feature`: an operator marks a manual payment failed and the amount returns
- added `features/payouts/settlement.feature`: confirming or failing a manual payment for a request in any other state is refused as not awaiting payment
- added `features/payouts/settlement.feature`: a completed request the provider reports charged back is reversed and the amount returns
- added `features/payouts/settlement.feature`: the same reversal arriving again moves nothing and returns the first outcome
- added `features/payouts/settlement.feature`: a reversal for a request not completed is refused as not completed
- added `features/payouts/settlement.feature`: a provider's outcome reporting a request awaiting manual payment completed wins over the manual path

---

### User Story 6 - Reads (Priority: P2)

The player reads their requests with amount, terminal, submission time and status; the operator
reads the queue, one request with its player's identity status and destination, and the payout's
provider reference and status; the queue's needs-review set is visible at a glance.

**Independent Test**: the list and detail cases of `WithdrawalRoutesSpec`.

**Acceptance Scenarios**:

- added `features/payouts/reads.feature`: a player lists their requests newest first with amount, terminal, time and status
- added `features/payouts/reads.feature`: a queue filtered by status pending review shows only pending requests
- added `features/payouts/reads.feature`: a queue filtered by payment status needs review shows only those requests

---

### Edge Cases

- The wallet refuses the hold after every gate passed (a race with a bet): the request is never
  created, the player is told insufficient funds, and nothing is held.
- The gateway's payout call returns completed but the hold confirmation fails: the request is
  recorded completed with the confirmation retried by the workflow until it succeeds; money is never
  returned for a payout the provider made.
- An outcome arrives for a request the brand never made: refused as not found; nothing is created.
- An outcome for a provider reference that does not match the request's: refused; recorded on the
  request for review.
- The provider says never created after the gateway said initiated: needs review, not failed — an
  operator decides, because the two answers contradict.
- A player is erased (ankka 042) with a request pending: the request is completed or failed on its
  own course; its destination fields and reasons are personal and go dark; amounts, times and
  statuses stay.
- The brand's queue grows past what one page holds: the view pages; no request falls out of
  reconciliation, since each request's workflow reconciles itself (the original's 12,500-twin scan
  cap is gone).
- Two operators approve at once: the second is refused as not pending.

## Requirements *(mandatory)*

### Functional Requirements

**Placement and identity**

- **FR-001**: `payouts` MUST be a service of the brand's project (spec 001 FR-002). It MUST serve
  player routes under `/v1/casino/brands/{brand}/players/{player}/withdrawal-requests`,
  `/withdrawal-terminals` and `/payout-destinations` for the brand's player realm, refusing a token
  whose player is not the route's; brand routes under `/v1/casino/brands/{brand}/withdrawal-requests`
  for the operator realm with a finance or admin role naming the brand, and
  `/v1/casino/brands/{brand}/withdrawal-config` for an admin role naming the brand (the original admits
  `brand_admin`, `resource_admin` and `platform_admin`, not finance); and one route for a payout's
  outcome, granted to `payments`/`psp-gateway` under ankka 040 (spec 001 FR-016) and admitted by that
  identity alone. Every route keeps its `{brand}` segment and refuses any other brand (spec 001
  FR-005).
- **FR-002**: `payouts` MUST call, within the project and by identity, the wallet's hold preview,
  hold, release, confirm and reverse commands (spec 002 FR-023 to FR-025); and, over the grant
  `payments` makes to it, `psp-gateway`'s three payout routes: list the brand's withdrawal-capable
  terminals, initiate a payout, read a payout's status (spec 005 FR-009a, FR-004, FR-009). It MUST
  call no other service.
- **FR-003**: `payouts` MUST build its own views from `casino.players` (identity verified, rejected,
  submitted and pending — spec 009 FR-030's `player_kyc_verified`, `player_kyc_rejected`,
  `player_kyc_submitted`, `player_kyc_pending`; account closed; the given and family names
  `player_registered` and `player_profile_updated` carry, kept `Personal` for FR-013's match; the email
  `player_registered` and `player_email_changed` carry, `Personal`, for FR-017's Unity Finance payout) and
  `money.transactions` (the gateway and scheme of every confirmed deposit), and MUST NOT query another
  service's store (constitution IV).

**The request**

- **FR-004**: A request MUST name an amount as a whole number of minor units above zero, a currency,
  a terminal, and what the terminal needs: a saved destination where the terminal requires one; a
  wallet address, and optionally a coin, for a crypto terminal; a payout method (ETO or ACH) with
  mobile number or bank account, institution and transit numbers for Gigadat. A missing field, a
  decimal, zero or a negative amount MUST be refused naming the field.
- **FR-005**: Before the hold, in the original's order (research R4), a request MUST be refused when:
  the player is unknown; the player's identity is not verified, with the original's three messages for
  unverified, under review and rejected; then by the hold preview's turnover answer (FR-006), the
  withdrawal limits (FR-005b) and the preview's funds and forfeit answers (FR-006); then when the
  terminal is not eligible (FR-012); the terminal requires a destination and none is named; a crypto
  terminal has no wallet address; the coin named is not in the terminal's list, naming the list; the
  destination is not the player's own and active.
- **FR-005a**: A brand MAY set, per currency, a weekly and a monthly withdrawal limit in minor units;
  none set, or zero, is no limit. The admin route of FR-001 MUST read and set them (`GET` and `PUT
  withdrawal-config`), refusing a negative or decimal amount naming the field, and MUST record each
  change to `platform.audit` with the operator. The brand's deposit turnover terms the original keeps
  on the same route are the wallet's (spec 002 FR-004a), not this service's.
- **FR-005b**: After the hold preview finds no turnover pending (FR-006), and before its other answers,
  a request MUST be refused when the player's requests in the request's currency created in the last
  seven days (rolling), plus the amount, exceed the weekly limit, as the original's
  `WITHDRAWAL_BLOCKED_WEEKLY_LIMIT` naming the limit, the amount used and what remains; else the same
  for the current UTC calendar month and the monthly limit (`WITHDRAWAL_BLOCKED_MONTHLY_LIMIT`). A
  request counts while pending review, approved, awaiting manual payout or completed; cancelled,
  rejected, failed and reversed requests do not. The usage MUST be read from `payouts`' own requests,
  so the check calls nothing and cannot fail open.
- **FR-006**: The wallet's hold preview MUST then decide, with the wallet's own answers passed
  through: blocked by turnover (the wallet's preview names no amount remaining: 2026-10-31, research
  *Found during implementation*); insufficient funds when the available
  amount (real balance less reserved, spec 022 FR-003) is below the amount; insufficient after bonus forfeit, naming the most withdrawable, when the
  clawback would leave real below the amount; requires bonus-forfeit confirmation, naming bonus
  balance, clawback and total forfeit, when any active record's policy forfeits on withdrawal and the
  player has not confirmed. A funds-only policy MUST require confirmation with a clawback of zero; a
  `none` policy MUST require nothing.
- **FR-007**: The hold MUST be placed with the request id as its key and the player's confirmation;
  the wallet computes and applies the clawback (spec 002 FR-023). The request MUST be created
  pending review only when the hold succeeded;
  the reply MUST carry the request id, status, amount, currency, terminal, creation time, whether a
  bonus was forfeited, the amount forfeited and the clawback applied. A hold refused as already
  pending MUST be reported as such; a refusal for funds MUST be reported as insufficient funds.
- **FR-008**: A request MUST record its terminal, its gateway, its destination reference, wallet
  address and coin, its Gigadat fields, and later the provider's reference, the payout status and
  every outcome, so an operator reads the whole history from one request.
- **FR-009**: A player MUST be able to cancel their own request only while it is pending review; a
  cancellation MUST release the hold and record the request cancelled; any other state MUST be
  refused as not cancellable.

**Eligibility and destinations**

- **FR-010**: The brand's withdrawal-capable terminals MUST be read from the gateway's listing
  (FR-002), and a terminal MUST be eligible for a player when it is active, can pay out, and, if its
  scheme is VISA, the player has at least one confirmed deposit whose scheme is VISA (a deposit whose
  scheme was never recorded counts as not VISA). Eligibility MUST be decided by one rule used by both
  the listing and the request, so what is offered and what is accepted cannot differ.
- **FR-011**: The listing MUST be ranked: terminals on a gateway the player has deposited with first,
  then by name, then by id, and the order MUST be the same on every read.
- **FR-012**: A request naming a terminal that is not eligible, or a blank terminal, MUST be refused
  as not eligible.
- **FR-013**: A player MUST be able to save a bank destination (holder's first and last names, IBAN,
  a label, whether default), set one as default, and delete one; a destination MUST be refused when
  the player's profile holds no name, or when the supplied names, normalised, do not match the
  profile's; a deleted destination MUST stay on record as deleted and never be offered or accepted.
  The first destination saved MUST become default; setting a default MUST unset the previous one;
  deleting the default MUST leave none.

**Review**

- **FR-014**: The brand's queue MUST list requests with the player, identity status, amount,
  currency, terminal and method, destination, submission time, status and payout status, and filter
  by status (pending review by default, or all, or one), payout status, player, identity status,
  method and amount range.
- **FR-015**: An operator with a finance or admin role for the brand MUST be able to approve or
  reject (with an optional reason) a request pending review, and only one; a second decision MUST be
  refused as not pending. Rejection MUST release the hold and record the operator and reason.
- **FR-016**: An operator MUST be able to confirm the payout or mark it failed (with a reason,
  "Manual payout not completed" by default) only when the request is awaiting manual payout or is
  approved and marked needs review; any other state MUST be refused as not awaiting payout.
  Confirmation MUST confirm the hold and complete the request; marking failed MUST release the hold
  and fail the request. Either MUST clear needs review.

**Payout execution**

- **FR-017**: On approval, the workflow MUST pursue the payout: when the terminal has automated
  payout and the request carries the destination or address it needs, it MUST make one payout call to
  the gateway with the request id as the idempotency key, the amount, currency, terminal, destination
  or address and coin, or Gigadat fields; otherwise the request MUST be awaiting manual payout.
- **FR-018**: The gateway's answer MUST be read as: *completed* — confirm the hold and complete;
  *initiated* with a provider reference, or *already initiated* (the provider already holds the
  request; spec 005 *Clarifications*, 2026-10-19) — record it and wait; *rejected*, including a
  provider's refusal of the gateway's credentials (`unauthorised`) — release the hold as a
  withdrawal return with reason `provider_rejected` (FR-023) and tell the player to try another
  terminal (**change from the original**, which parks the request for a manual payout);
  *in doubt*, an unreachable gateway, an unparseable answer or an unrecognised status — record what
  is known and wait. A request waiting MUST NOT become awaiting manual payout by any path but an
  operator's.
- **FR-019**: While waiting, the request MUST settle from the outcome route (FR-020) or from the
  status loop (FR-021), whichever first; a settled request MUST ignore later outcomes except a
  reversal of a completed one.
- **FR-020**: The outcome route MUST accept completed, failed with a reason, and reversed for a
  request the brand holds, carrying the provider's reference; it MUST complete, fail or reverse the
  request as FR-023 says; it MUST refuse an unknown request and record and refuse a reference that
  does not match; it MUST accept the same outcome twice and move nothing the second time.
- **FR-021**: For a provider with a status to ask (Unity Finance today), the workflow MUST ask the
  gateway for status 30 minutes after initiation and every 5 minutes after, until the status is
  final (completed, failed, reversed) or the provider says never created, which MUST mark the request
  needs review and stop. For a provider with no status to ask, the workflow MUST mark the request
  needs review when its window passes with no outcome: 24 hours for Cozeni, 5 days for Gigadat and
  Morphosis, read from the gateway's terminal listing rather than named here.
- **FR-022**: A request MUST be settled within 24 hours of initiation whenever the provider answers,
  and the brand's status MUST count requests older than that still waiting.
- **FR-023**: Completion MUST confirm the hold (no money moves); failure MUST release the hold as a
  withdrawal return with the reason; reversal of a completed request MUST credit the amount back and
  record the request reversed; a reversal of a request not completed MUST be refused; every one of
  these MUST be idempotent under the request id and the outcome, so a repeat moves nothing and
  returns the first result.
- **FR-024**: Every money step MUST retry until the wallet answers; a payout the provider made MUST
  never be undone because a later step failed.

**Facts**

- **FR-025**: `payouts` MUST produce `casino.withdrawals` (spec 001 FR-011) with the families
  `withdrawal_requested`, `withdrawal_approved`, `withdrawal_completed`, `withdrawal_cancelled`
  (carrying whether the player or an operator cancelled, and the reason), `withdrawal_failed`
  (carrying the reason) and `withdrawal_reversed`; each keyed by the player id, as the contract keys
  the topic so a player's facts stay in order (research R11), and carrying the request id as
  `payoutId` and the player id as subject (spec 001 FR-012), the amount in minor units, the currency, the terminal, the
  gateway, the provider's reference when there is one, and when it occurred. A fact MUST be emitted
  exactly once per transition and MUST NOT carry a destination, address, name or reason text in the
  clear.
- **FR-026**: A `withdrawal_requested` fact MUST be emitted on the hold, not on approval; the brand's
  `attribution` service (spec 012 FR-011) derives `withdrawal-requested`, `-completed`, `-failed`
  and `-reversed` from these families and nothing else.

**Personal data, money and audit**

- **FR-027**: A destination's holder names and IBAN, a wallet address, a mobile number, a bank
  account, institution and transit numbers, and every free-text reason or review note MUST be
  `Personal` under the player's id (ankka 042, spec 001 FR-023); amounts, currencies, statuses,
  terminal and gateway ids, provider references and times MUST NOT be.
- **FR-028**: Every amount MUST be a whole number of minor units (spec 001 FR-026); the request's
  currency MUST be the wallet's; the hold, release, confirm and reverse MUST carry the request id as
  their key (spec 001 FR-017, constitution II).
- **FR-029**: Every request, decision, payout call, outcome, poll result and manual settlement MUST
  be written to `platform.audit` naming the actor (player, operator by id, gateway, or the workflow).

### Key Entities

- **Withdrawal request**: one per request id; amount, currency, terminal, gateway, destination
  reference or address and coin, Gigadat fields, status (`pending review`, `approved`, `awaiting
  manual payout`, `completed`, `rejected`, `cancelled`, `failed`, `reversed`), payout status (`none`,
  `initiated`, `needs review`, `resolved`), provider reference, decisions with operator and reason,
  outcomes, times. An event sourced entity keyed by the request id.
- **Withdrawal workflow**: one per request; the lifecycle of FR-017 to FR-023 with its waits and
  timers; keyed by the request id.
- **Player's payout record**: one event sourced entity per player (research R3) holding the saved bank
  destinations (holder names, IBAN, label, default flag, status `active` or `deleted`); the gateways
  and schemes of confirmed deposits, from `money.transactions`; identity status, account status, the
  names and the email, from `casino.players`; and the player's requests of the last 32 days for
  FR-005b. An entity, not views, so the limit check reads the request made a second before.
- **Terminal catalogue**: per brand, the gateway's withdrawal-capable terminals, refreshed every 60
  seconds (research R5).
- **Withdrawal limits**: per brand, a weekly and a monthly limit per currency (FR-005a); one entity
  per brand.
- **Queue view**: the brand's requests by status, payout status, player, identity status, method,
  amount and age, from request events.
- **Withdrawal notifier**: the consumer that shapes request events into `casino.withdrawals`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every recording named below replays against `payouts` with the original's outcome,
  status word and money movement, under the original's error names.
- **SC-002**: A withdrawal that the provider answers is settled within 24 hours of initiation in
  100% of cases, and a payout in doubt is never offered for manual payment, proven by a suite that
  breaks the gateway at every step and counts manual-payout states (must be zero).
- **SC-003**: No request is paid twice: a suite that answers initiated, then times out, then answers
  completed on both routes at once leaves one completed request and one confirmed hold.
- **SC-004**: The brand's queue lists 10,000 requests with 200 in flight, and all 200 reconcile
  without a scan: the reconciliation cost grows with requests in flight, not with requests ever made.
- **SC-005**: A player's request is accepted or refused within 2 seconds at the 99th percentile with
  the wallet as the only service in its path.
- **SC-006**: Erasing a player leaves every destination, address, account number and reason
  unreadable and every amount, status and time readable, on the request, the queue and the facts.
- **SC-007**: The order of the terminal listing is identical across 1,000 reads for one player.

## Changes from the original

- The route file, the seven TL handlers' orchestration and the five-minute sweeper become one
  workflow per request with its own timers; the wallet's money movement is unchanged (spec 002).
- The reconciliation scan over every withdrawal-request twin (capped at 12,500) is gone; each
  request reconciles itself (SC-004).
- The internal outcome route's shared secret becomes a 040 grant admitting `psp-gateway` by
  identity; the `payout_status` stamps and `payout_resolved_at` become the request's own state.
- Terminal eligibility reads the gateway's listing over the payments grant instead of terminal twins;
  the rule (ADR 0038, decisions 2, 3, 5, 6) is unchanged. ADR 0038's decision 1, a separate
  withdrawal routing tree, was never built and is not carried over.
- Identity status and deposit history are views from facts, not reads of the player and wallet
  twins; the deposit-turnover and restricted-funds gates are the wallet's preview, not a sum in the
  route.
- Affiliate notifications at approval and on failure are replaced by the facts of FR-025; the
  original's "count at approval, reverse on failure" rule becomes spec 013's to keep or change.
- Amounts on the player's route are minor units, not a decimal of major units (spec 001 FR-026); the
  `bonusForfeited`, `forfeitedAmount` and `clawbackAmount` fields of the reply are minor units too.
- Reasons and review notes are `Personal`; the original stored them in the clear on the twin.
- A Gigadat request missing its method fields is refused naming the field (FR-004); the original
  accepted it and parked it for a manual payout at approval.
- Withdrawal limits are per currency, not EUR only with every other currency let through; their usage
  is counted from `payouts`' own requests, not a fan-out over the wallet twin's edges, so nothing fails
  open (FR-005b). The turnover terms the original's `withdrawal-config` also carried are the wallet's.
- The withdrawal activity-log events written to a twin become `platform.audit` records.
- Dropped as stubs: `turnover-override` and `deposit-records` routes under the withdrawal file belong
  to spec 002's reads and spec 008, not here.

## Recordings

*Corrected 2026-10-31 (research R2):* `TestScenario7_CasinoGate3_FullPlayerJourney` never reaches the
withdrawal routes and fails on the original, and `TestScenario10_PaymentsHTTPAPIGate3_PayoutLifecycle`
is skipped; neither can be recorded. The recordings are:

- Go: a new driver, `tests/original/payouts-driver` (scenario 30, one test per scenario of
  `features/payouts/`), and the original's
  `scenario251_withdrawal_cap_lifecycle_e2e_test.go`, recorded against the local original.
- TL: the wallet handlers' `.test.tl` cases are spec 002's (the money movement); read here as the
  oracle for the workflow's money steps, not replayed.
- Scala (read as the oracle for `contracts/routes.md`, not replayed): `WithdrawalRoutesSpec`, `WithdrawalTerminalRoutesSpec`, `PayoutDestinationRoutesSpec`,
  `PayoutReconciliationSweeperSpec`.
- Playwright: `withdrawal-us1-new-destination.spec.ts`, `withdrawal-us2-saved-destination.spec.ts`,
  `58-brand-config-withdrawal-limits.spec.ts`.
- Scala: `WithdrawalLimitChecker`'s spec and `BrandConfigRoutesSpec`'s `withdrawal-config` cases.
- The gateway's own payout handler and adapter tests (`TestPayoutHandler*`, `TestMorphosisPayout_*`,
  `TestGigadatPayout*`, `TestMorphosisWithdrawalWebhook_*`) are spec 005's recordings; this spec's
  parity tests drive `payouts` against a scripted gateway that answers as those tests prove the real
  one does.

## Assumptions

- Spec 005 serves the three payout routes of FR-002 under one grant (005 FR-009a, FR-004, FR-009),
  and its terminal listing carries whether a terminal supports withdrawal, its scheme, its gateway,
  whether a payout destination is required, the supported coins, the provider's review window and
  whether it has a status to ask. Spec 001 FR-016's "payout route" reads as this family (reconciled
  2026-10-08).
- `casino.players` carries `player_kyc_submitted` and `player_kyc_pending` (spec 001 FR-011,
  produced by spec 009), so the "under review" message of FR-005 stands for a player with a document
  pending or an account set pending; spec 001 lists `payouts` as a reader of `casino.players` and
  `money.transactions`.
- Spec 002 serves the hold preview (002 FR-023a) and computes the clawback itself (002 FR-015a,
  FR-023); `payouts` supplies none.
- The roles the original admits (`brand_admin`, `resource_admin`, `platform_admin`, `brand_finance`,
  `resource_finance`) are the operator realm's roles, checked by the handler for the brand (spec 001
  FR-020); `brand_finance` is added to the realm with this spec (research R6).
- A provider's explicit rejection at initiation releases the hold and tells the player to try another
  terminal (FR-018), as the plan had it; the original parks the request for a manual payout, which
  waits on a person and makes a support ticket of every rejection. Awaiting manual payout is reached
  only by an operator's act (settled 2026-10-25).
- A suspended, self-excluded or closed account may request a withdrawal, gated by identity and funds
  as today (FR-005): the balance is the player's, and regulators expect it returned on exclusion and
  closure; refusing would trap the money. Kept as the original (settled 2026-10-25).

## Dependencies

- Spec 001 (project, routes, realms, grants, topic contract, money and personal-data rules).
- Spec 002 (hold preview, hold, release, confirm, reverse; `money.transactions` deposit families).
  As built on 2026-10-09: `GET `/v1/casino/brands/<brand>/wallets/{player}/{currency}`/holds/preview?amount=`, `POST …/holds` with the request id as `key`,
  and `POST …/holds/release` (`outcome` cancelled, rejected or failed), `…/holds/confirm`,
  `…/holds/reverse`, each naming `requestId` in the body; a release as cancelled of a request that is
  not pending answers the original's `WITHDRAWAL_REQUEST_NOT_CANCELLABLE`. Since 2026-10-10 the wallet
  keeps only its most recent 1,000 keys: the lifetime record of a request's wallet commands is this
  service's own (the workflow per request, started as the atomic claim, is the shape spec 003 uses),
  and a repeat older than the wallet's recent keys is answered from that record, never resent to the
  wallet
  (specs/002-wallet/contracts/routes.md).
- Spec 005 (the gateway's payout routes and outcome calls); spec 004 (terminals' capabilities).
- Spec 008 and 009 (`casino.players` identity and account families); spec 012 (the withdrawal fact
  kinds derived from `casino.withdrawals`).
- ankka 040 (the two grants), 042 (`Personal`, erasure), 032 (the workflow's recurring timers).

## Out of scope

- The gateway's provider adapters, their credentials and their webhooks (spec 005).
- Deposits, deposit turnover's definition and overrides (specs 002, 004).
- A player's own responsible-gambling limits on withdrawals, which the original does not have (spec
  010 may add); the brand's withdrawal limits are in scope (FR-005a).
- The affiliate commission consequence of a withdrawal (spec 013).
- The operator console and the player frontend, which change base URLs and the amount's units.
