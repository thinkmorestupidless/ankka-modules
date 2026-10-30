# Feature Specification: kyc — Who a Player Is, Shown and Reviewed

**Feature Branch**: `009-kyc`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The brand's `kyc` service: the brand's verification policy from a licence
profile, the documents a player submits and an operator reviews, the account's verification status and
how it is derived, the deposit, play and withdrawal gates other services ask it about, the thresholds
and the time window that call for review, the documents' storage, retention and erasure, and the facts
every other service reads."

## Context

Today KYC is spread across `casino-service` (`BackofficeKycPolicyRoutes.scala`, the KYC arms of
`PlayerProfileRoutes.scala` and `BackofficePlayerRoutes.scala`, the deposit route in
`CasinoServiceMain.scala`, the play gate in `SessionRoutes.scala`, `ProfileCompleteness.scala`,
`MinioClient.scala`), a Go capability (`tl/runtime/capabilities/kyc_status_rollup`) that derives the
account's status from the documents, five TL functions in the casino starter pack (`kyc-status-rollup`,
`player-kyc-gate-on-large-deposit`, `player-deposit-threshold-check`, `player-kyc-window-set`,
`player-kyc-window-expiry-check`), a `kyc-policy` twin per brand seeded by `merchant-service`, and a
MinIO bucket `casino-kyc` the player site proxies uploads and views through. The original's own specs
137 (policy per brand), 118 (retrieval and encryption at rest) and 197 (manual verification, four fixed
categories, per-player required categories) describe what it does; the code and the Playwright suites
28–33 and 50 are what it does.

There is no automated identity provider. Spec 137 put Onfido, Sumsub and Jumio out of scope and no
adapter was ever added: every document is reviewed by a person, and the account's status is derived from
those reviews. There are no verification tiers: an account is unverified, pending, verified or rejected,
with a separate source-of-funds status, and the player-facing "Additional verification required" is
`pending`.

This spec gathers all of it into one service, `kyc`, in the brand project (spec 001 FR-002). The plan
named `kyc` as a new service the 2026-10-03 split had not placed; this is its placement. What leaves
`casino-service` with it: the policy routes, every `/kyc/...` route under a player and under the
backoffice, the status derivation, the gates' decisions, the review queue, the document store. What
stays elsewhere: the player's profile fields (spec 008, which emits them as facts), the deposit and
game-launch routes that ask this service whether to proceed (specs 008 and 003), the withdrawal gate
that reads this service's facts (spec 007), and the emails and pushes a review triggers (spec 014).

## Clarifications

### Session 2026-10-08

- Q: Is there automated verification to carry over? → A: No. Spec 137 put electronic identity
  providers out of scope and none was added; the only path is a player's upload and an operator's
  review. This spec keeps that and names an adapter seam for a provider as out of scope, not designed.
- Q: Are there verification levels or tiers? → A: No. One verification status (`unverified`,
  `pending`, `verified`, `rejected`) and one source-of-funds status (`unverified`, `cleared`). The
  licence profile decides which actions each status gates; there is no ladder of levels.
- Q: Where does the deposit gate decide? → A: In `kyc`, asked by the service that starts the deposit.
  The decision needs the amount being deposited (the threshold rule is "would the lifetime total exceed
  the threshold"), the brand's policy and the player's two statuses, all of which are this service's.
  A fact cannot carry a decision about an amount not yet known, so the gate is a query within the
  project, admitted by the caller's identity (spec 001 FR-020, service class), exactly as the wallet is
  asked. The play gate is the same query without an amount. The withdrawal gate stays a fact: spec 007
  reads verification status from `casino.players`, which this service produces.
- Q: Who produces the KYC families on `casino.players`? → A: `kyc`. Spec 001 FR-011 already lists two
  producers for that topic (`players`, `responsible-gambling`); `kyc` is the third, producing the
  families FR-030 lists. Spec 001 must be amended to say so and to add the families beyond
  `player_kyc_verified` and `player_kyc_rejected` (spec 007 asked for `player_kyc_submitted`).
- Q: How does `kyc` know whether the profile is complete? → A: From facts. `players` (spec 008) emits
  the eight required profile fields as `Personal` values on a `player_profile_updated` fact whenever
  one changes; `kyc` applies the completeness rule (every field present, date of birth a date and 18
  years or more before today) itself, as the Go rollup does today. Spec 001's contract and spec 008
  must add that family.
- Q: Where do "review required" signals go? → A: To this service's own review queue, a view operators
  filter, and to a fact. Today they are action proposals (`kyc_review`, `sof_review`,
  `suspend_player`) published to the reasoner's proposal topic and surfaced nowhere a compliance
  operator works. The queue filters the backoffice already has ("Docs awaiting review", "KYC pending",
  "Ready for final KYC review") gain "Review required: threshold", "Review required: window", "Source
  of funds review". A suspension the policy calls for is a command to `players` (spec 008), not a
  proposal.
- Q: Is the policy inherited up the payments hierarchy? → A: No. The original reads a brand's policy
  "with ancestors" (`getKycPolicyForBrandWithAncestors`), walking the merchant hierarchy for a
  sub-brand with no policy of its own. A brand is one project and has one policy (spec 001); a brand
  with no policy saved has the Curaçao default, as today.
- Q: Does the 15-minute freshness window of the Go rollup survive? → A: No. It exists because the TL
  trigger re-fires on every write to a player twin, so the capability had to decide whether a given
  firing was "about" a document change. A consumer of this service's own events recomputes exactly
  when a document, a required category, the mode or the profile changes, and never otherwise.
- Q: Who may read a document? → A: The player, for their own; an operator holding `brand_admin` or
  `brand_compliance` (or wider) for the brand. Every issue of a view link is an audit record. The
  original writes the issue to one twin property, `kyc_view_log`, which the next issue overwrites, so
  it holds only the latest view; this spec records every one (FR-044).
- Q: Can the source-of-funds gate be cleared? → A: Not today. The deposit route refuses a deposit over
  the SoF threshold until `sof_status` is `cleared`, and nothing in the original sets it: a player who
  crosses the threshold is refused every deposit from then on. The gate is kept (Playwright 31 proves
  it) and an operator action to clear it is added (FR-023), with the SoF document category the review
  uses being `other`, as 137 assumed.

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: Who asks the gates? → A: `players` asks the deposit check from its deposit-initiation route
  (spec 008 FR-043); `game-provider` asks the play check at launch (spec 003 FR-007); `players` does
  not ask when it opens a game session (FR-003).
- Q: Who serves the suspend command the window's expiry needs? → A: `players`, as a service route
  idempotent under the player and deadline (spec 008 FR-032).
- Q: Did 008 add `player_profile_updated`? → A: Yes (spec 008 FR-025), carrying the eight
  completeness fields as `Personal` values; spec 001 FR-011 lists it and `kyc` as a producer.

### Session 2026-10-25

The markers left open for product and regulatory decisions, settled with Trevor before 008 is planned.

- Q: Enforce the verification window the original never acted on? → A: Yes, for deadlines set from
  go-live; a migrated deadline already passed flags the player for review and suspends no one
  (FR-025).
- Q: How long are documents kept after closure, per licence profile? → A: Five years for UKGC, MGA
  and Curaçao; Curaçao's is a default the compliance officer confirms before a Curaçao brand takes
  real money (FR-034).
- Q: May an operator upload for a player? → A: Yes, with a reason, audited under the operator, and
  shown in the player's list as submitted by support (FR-005, Assumptions).

### Session 2026-10-30

Settled before 009 is planned.

- Q: The original's `player-kyc-gate-on-large-deposit` (casino starter pack, approved) flags an
  unverified or pending player when one confirmed deposit exceeds the deposit threshold, and proposes
  suspending a rejected one; the proposal reached no one. What does `kyc` do? → A: Flag, suspend no
  one. A confirmed deposit whose own amount exceeds the policy's deposit threshold flags an unverified,
  pending or rejected player for review (`large_deposit`) and publishes the fact; nobody is suspended,
  which is what the original did in effect. The deployed trigger's extra floor of 1,000,000 minor
  units, a leftover of its test fixture, is not carried over (FR-038).
- Q: A player may hold wallets in several currencies (spec 002) and the thresholds are bare minor
  units; the original summed every deposit into one number. How are deposits compared with the
  thresholds? → A: One KYC currency per brand. The policy names it (the UKGC profile `GBP`, MGA `EUR`,
  Curaçao none, having no threshold); only deposits in it are summed; while the policy has a deposit
  or source of funds threshold, a deposit check in another currency is refused
  `DEPOSIT_BLOCKED_KYC_CURRENCY`, failing closed as spec 010 does, so no threshold is avoided by
  splitting currencies (FR-019, FR-021, FR-026).

Settled at planning, once the original's code had been read whole (research R1).

- Q: The Curaçao default, which every brand with no saved policy runs (every Spinvibe brand today),
  has a 30-day window with suspension on expiry. With the window enforced from go-live (2026-10-25),
  what is the default? → A: As the original: 30 days, suspend on expiry. An unverified player on a
  default brand is suspended 30 days after registering, from go-live; a brand that wants otherwise
  saves a policy.
- Q: Every required document approved but the profile incomplete: `pending` (the original's code and
  its own test, `IncompleteProfileKeepsAccountPending`) or `unverified` (this spec, until now)? → A:
  `pending`, as the original. The player is told which fields are missing (FR-012).
- Q: Which operator roles act on a player's KYC? The original's fallback route admits any operator
  role with the brand in scope. → A: The compliance roles: `brand_admin`, `brand_compliance`,
  `compliance_officer`, `resource_admin`, `platform_admin` (FR-002). Support agents, fraud analysts and
  viewers lose access they have today by accident.
- Q: The original's `EventIngestConsumer` proposes `kyc_review` on every captured payment by a
  pending player; nobody acts on it. Carry it into the queue? → A: No. A pending player is already in
  the queue; the proposal is not carried over.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player submits documents and sees where they stand (Priority: P1)

A player opens their account's documents page and sees the six categories — identity front, identity
back, proof of address, payment method, bank account details, other — each with its own status and its
own upload action. They upload a file for a category, it shows as pending review, and once an operator
has decided they see approved, or rejected with the reason in their own language and a way to upload a
replacement. They always see the account's overall status: unverified, additional verification
required, verified, or rejected.

**Why this priority**: Spec 197's stated outcome was fewer "why is my account not verified" contacts;
the page is the player's only window on the process, and the withdrawal gate (spec 007) sends every
unverified player here.

**Independent Test**: Register a player, open the documents page, upload one file per category,
observe pending; approve and reject from the operator side; observe the outcomes and the replacement
path.

**Acceptance Scenarios**:

- added `features/kyc/documents.feature`: a player with no documents sees all six categories with upload actions and the required ones marked
- added `features/kyc/documents.feature`: asking to upload a file for a category gives an upload address valid for an hour and a document id, and the upload lands under the player's own prefix
- added `features/kyc/documents.feature`: confirming a completed upload shows the category pending review and locks the document
- added `features/kyc/documents.feature`: a category whose latest document is pending or approved refuses another upload as locked
- added `features/kyc/documents.feature`: the category other is never locked and every upload to it is a document of its own
- added `features/kyc/documents.feature`: a replacement for a rejected document shows the category pending review and hides the rejection reason
- added `features/kyc/documents.feature`: a rejected document's reason is shown as one of the reason codes in the player's language and never as free text
- added `features/kyc/documents.feature`: a player viewing one of their documents is given a view address valid for fifteen minutes to it alone, and the view is recorded
- added `features/kyc/documents.feature`: a player asking to view another player's document is refused
- added `features/kyc/documents.feature`: a file of a type or size the brand does not take is refused naming the rule
- added `features/kyc/documents.feature`: an operator uploads a document for a player with a reason and it shows as submitted by support

---

### User Story 2 - An operator reviews documents and the account's status follows the rules (Priority: P1)

A compliance operator opens a player's KYC tab, views each document, approves or rejects it with a
reason code. In automatic mode the account becomes verified by itself once every category the brand's
policy requires has an approved latest document and the profile is complete; in manual mode nothing
changes the account's status but the operator's own decision. The operator can mark categories as
required for this one player, set the account to "additional verification required" or "verified"
directly, switch the mode, and find accounts waiting on their final decision.

**Why this priority**: This is the compliance team's daily work and the rule set the original fought
hardest to get right (ClickUp 869eftg4h, 869ejmpvy, 869epq4cf, 869f028j8, 869eu7dpn). Every rule here
is held to the Go rollup's tests.

**Independent Test**: Replay the 61 rollup cases (`rollup_test.go`, `handler_test.go`) and
`BackofficePlayerRoutesSpec`'s KYC cases against the service's test kit.

**Acceptance Scenarios**:

- added `features/kyc/review.feature`: approving a pending document records the operator and time, and a second review of it is refused
- added `features/kyc/review.feature`: rejecting a pending document with a known reason code records the code
- added `features/kyc/review.feature`: rejecting a document with an unknown reason code is refused
- added `features/kyc/review.feature`: in automatic mode an account whose required categories are approved with a complete profile becomes verified with the system as actor
- added `features/kyc/review.feature`: in automatic mode an account with only the identity front approved stays pending
- added `features/kyc/review.feature`: an approved legacy identity document meets the identity requirement when no front exists
- added `features/kyc/review.feature`: an uploaded identity front stops the legacy identity document counting for anything
- added `features/kyc/review.feature`: a rejected latest document in any category makes the account rejected, required or not
- added `features/kyc/review.feature`: a rejected document in the category other does not reject the account
- added `features/kyc/review.feature`: any pending document makes the account pending even when every required category is approved
- added `features/kyc/review.feature`: an account with every required category approved but an incomplete profile is pending and the player is told
- added `features/kyc/review.feature`: a change to a required profile field returns a verified account to unverified until a document is reviewed after it
- added `features/kyc/review.feature`: in manual mode approving every required document changes nothing
- added `features/kyc/review.feature`: in manual mode the operator setting the account verified verifies it at once
- added `features/kyc/review.feature`: setting an account verified while the profile is incomplete is refused naming the missing fields
- added `features/kyc/review.feature`: marking a category required for one player puts the account in manual mode and pending without affecting any other player
- added `features/kyc/review.feature`: an account ready for final review is listed with the time it became ready
- added `features/kyc/review.feature`: an account leaves the ready for final review list when the operator decides or changes its mode
- added `features/kyc/review.feature`: an operator with a compliance or admin role for the brand views a document by a fifteen-minute address and the view is recorded
- added `features/kyc/review.feature`: an operator of another brand is refused a document
- added `features/kyc/review.feature`: an operator verifying or rejecting an account directly changes the status regardless of the documents, audited

---

### User Story 3 - The brand's policy comes from its licence (Priority: P1)

An operator applies a licence profile — UKGC, MGA or Curaçao — to the brand and the policy's fields
take that profile's defaults: the deposit gate, the play gate, the KYC currency, the deposit threshold,
the source of funds threshold, the verification window, the required categories, and whether the window's expiry
suspends. The operator may then override any field, which marks the profile custom.

**Why this priority**: Every gate and every derivation reads the policy; without it the service has
no rules. The defaults are the regulator's, written once.

**Independent Test**: Apply each profile and read the policy; override one field and read it; replay
Playwright 30.

**Acceptance Scenarios**:

- added `features/kyc/policy.feature`: a brand with no KYC policy reads the Curaçao default
- added `features/kyc/policy.feature`: applying the UKGC profile sets its gates, window, threshold and categories
- added `features/kyc/policy.feature`: applying the MGA profile sets its thresholds and categories with no window and no suspension
- added `features/kyc/policy.feature`: changing one field of a profile's policy changes only that field and marks the profile custom
- added `features/kyc/policy.feature`: applying an unknown licence profile is refused naming the known profiles
- added `features/kyc/policy.feature`: a saved KYC policy change is audited with the fields and values and governs the next gate decision
- added `features/kyc/policy.feature`: an unauthenticated client asking the brand's requirements is told the required categories and nothing else

---

### User Story 4 - A deposit or a game launch is allowed or refused by the policy (Priority: P1)

The service that starts a deposit asks `kyc` whether this player may deposit this amount; the service
that opens a game session asks whether this player may play. `kyc` answers from the policy's gates,
the player's statuses, and the player's lifetime deposits.

**Why this priority**: A UKGC brand that lets an unverified player deposit is in breach. The decision
must be one place's.

**Independent Test**: Replay Playwright 31 and 32 against the gate queries.

**Acceptance Scenarios**:

- added `features/kyc/gates.feature`: a brand whose deposit gate is verified refuses a deposit by an unverified, pending or rejected player with the status's own words
- added `features/kyc/gates.feature`: a deposit that would take an unverified player over the deposit threshold is refused and flags them for review
- added `features/kyc/gates.feature`: a deposit that keeps an unverified player under the deposit threshold is allowed
- added `features/kyc/gates.feature`: a deposit that would take a player whose source of funds is not cleared over the source of funds threshold is refused and flags them
- added `features/kyc/gates.feature`: a deposit over the source of funds threshold by a player whose source of funds is cleared is allowed
- added `features/kyc/gates.feature`: a brand whose play gate is verified refuses play by an unverified player with the status's words
- added `features/kyc/gates.feature`: play is allowed for a verified player or on a brand whose play gate is none
- added `features/kyc/gates.feature`: a deposit confirmed by the wallet grows the player's lifetime deposits for the next check
- added `features/kyc/gates.feature`: a tightened policy flags each unverified player over the new threshold for review once
- added `features/kyc/gates.feature`: a deposit check in a currency other than the KYC currency is refused while the brand has a threshold
- added `features/kyc/gates.feature`: a deposit check in any currency is answered by the gates alone when the brand has no threshold
- added `features/kyc/gates.feature`: only deposits in the KYC currency count toward the player's lifetime deposits
- added `features/kyc/gates.feature`: a confirmed deposit that alone exceeds the deposit threshold flags a player not verified for review and suspends no one
- added `features/kyc/gates.feature`: a confirmed deposit that alone exceeds the deposit threshold does not flag a verified player

---

### User Story 5 - The verification window runs out (Priority: P2)

On a brand whose policy sets a window, a player who registers has that many days to become verified.
When the window lapses unverified, the account is suspended if the policy says so, or flagged for
review if not.

**Why this priority**: Spec 137 FR-010 and FR-011; the UKGC profile's three days. Lower than the gates
because the original sets the deadline and never acts on it (Clarifications 2026-10-25; FR-025).

**Independent Test**: Register on a brand with a one-day window and suspend on expiry; advance the
clock; observe suspension; register on a brand with no suspension; observe the review flag.

**Acceptance Scenarios**:

- added `features/kyc/window.feature`: a registration on a brand with a three-day window sets the player's deadline three days out
- added `features/kyc/window.feature`: a registration on a brand with no window sets no deadline
- added `features/kyc/window.feature`: a deadline passing on an unverified player under a policy that suspends on expiry suspends them once
- added `features/kyc/window.feature`: a deadline passing on an unverified player under a policy that does not suspend flags them for review
- added `features/kyc/window.feature`: a deadline passing on a player verified before it does nothing
- added `features/kyc/window.feature`: a changed window applies to players registered after it and not before
- added `features/kyc/window.feature`: a migrated deadline already passed flags the player for review and suspends no one

---

### User Story 6 - Documents are kept as long as the regulator needs and then erased (Priority: P2)

A player's documents live in the brand's object store under the player's prefix, encrypted at rest,
readable only through links this service issues. When the player is erased under a hold that has
lapsed, every version of every document goes, with the metadata's personal fields.

**Why this priority**: Identity documents are the most sensitive thing the brand holds. Spec 118 added
encryption at rest for exactly this reason; ankka 034/039/042 make retention and erasure the
platform's.

**Independent Test**: Upload, close the account, file an erasure with a hold, advance past the hold,
read the store and the metadata.

**Acceptance Scenarios**:

- added `features/kyc/retention.feature`: a document is kept under the player's prefix in the brand's document store, encrypted at rest
- added `features/kyc/retention.feature`: an erasure applied after its hold lapses deletes every version of every document and keeps the ids, times, categories, statuses and reason codes readable
- added `features/kyc/retention.feature`: an erased player's documents are listed with their statuses and no address can be issued
- added `features/kyc/retention.feature`: a document written for an erased player after the erasure is removed when the erasure handler runs again

---

### User Story 7 - Reads (Priority: P2)

Operators list players by KYC state, see counts, and open a player's documents with their history;
the player sees their own; other services read facts.

**Acceptance Scenarios**:

- added `features/kyc/reads.feature`: the queue read with a filter lists the matching players with a live count
- added `features/kyc/reads.feature`: the queue read with the ready for final review filter lists each player with the time since ready
- added `features/kyc/reads.feature`: an operator reading a player's KYC tab sees every document and the account's KYC fields
- added `features/kyc/reads.feature`: a player's activity history shows KYC status changes as account events

---

### Edge Cases

- A document confirmed with an id the upload never used: refused as not found in the store.
- Two uploads for one category confirmed in the same second: the one confirmed last is the latest.
- A category marked required that the brand's policy does not require: it counts for the manual
  ready-for-final-review signal and never for automatic verification.
- An operator clears every player-required category: the mode stays manual; the status does not change.
- A player switched from manual to automatic while every required document is approved: the account is
  recomputed at the switch and becomes verified if the rules say so.
- The policy is read while the brand has none: the Curaçao default, and the read says it is a default.
- A deposit check in a currency other than the policy's KYC currency: refused
  `DEPOSIT_BLOCKED_KYC_CURRENCY` while the policy has a threshold, answered by the gates alone while it
  has none (FR-019). A currency the player holds no wallet in is the wallet's to refuse, not this
  service's.
- A deposit confirmed for a player with no record in this service: the record is created with that
  deposit as its lifetime total.
- The store refuses an upload link (the bucket is unreachable): the player is told storage is
  unavailable, nothing is recorded.
- A view link issued and used after fifteen minutes: refused by the store; nothing this service can do
  or needs to.

## Requirements *(mandatory)*

### Functional Requirements

**Placement and identity**

- **FR-001**: `kyc` MUST be a service in the brand project, Scala, with its own database and its own
  bucket (ankka 034), holding the brand's policy, each player's documents' metadata, statuses, mode,
  required categories, deadline and lifetime deposits. Every route keeps its `{brand}` segment and
  refuses any other brand (spec 001 FR-005).
- **FR-002**: Player routes MUST admit a `casino-<slug>` token for the player named in the route and
  no other player. Operator routes MUST admit an `eitheror` token whose realm roles name the brand and
  include `brand_admin`, `brand_compliance`, `compliance_officer`, `resource_admin` or `platform_admin` (decision 0045; spec
  001 FR-020). The brand's requirements route MUST be public.
- **FR-003**: The gate queries MUST be served within the project, admitted by identity with no token:
  the deposit check (FR-019) to `players`' deposit-initiation route (spec 008 FR-043), the play check
  (FR-020) to `game-provider`'s launch (spec 003 FR-007).

**Documents**

- **FR-004**: The document categories MUST be `identity_front`, `identity_back`, `proof_of_address`,
  `payment_method`, `bank_account_details` and `other`; a document of the legacy category `identity`
  MUST be accepted from the migration and treated as FR-013 says. Every category but `other` MUST hold
  one current document, the latest uploaded; `other` MUST hold every document uploaded to it.
- **FR-005**: An upload MUST begin with the player (or an operator for the player, with a reason) asking for an upload
  link for a category and a content type among `image/jpeg`, `image/png`, `application/pdf`. The
  service MUST answer a link to its own bucket valid for one hour, the object name
  `subjects/<player id>/kyc/<category>-<time>.<ext>`, and a document id; the link MUST allow one
  object of at most the brand's size limit (default 5 MiB) and the named content type.
- **FR-006**: A category other than `other` whose latest document is pending or approved MUST refuse a
  new upload link as locked; a category whose latest is rejected, or none, MUST allow one.
- **FR-007**: The player (or operator) MUST confirm the upload; the service MUST verify the object
  exists before recording the document as pending with its upload time, and MUST publish
  `player_kyc_submitted`. A document once recorded MUST NOT be edited, replaced or removed by a player.
- **FR-008**: A view of a document MUST be a link valid for fifteen minutes to that object only,
  issued to the player for their own documents and to an admitted operator for the brand's. The link
  MUST carry no credential of the requester. A request for a document the player does not hold MUST be
  refused as not found.
- **FR-009**: The bucket MUST be encrypted at rest by the store (ankka 034/039) and MUST refuse an
  object written outside a signed link.

**Review**

- **FR-010**: An operator MUST be able to approve a pending document, or reject it with exactly one of
  the reason codes `document_unclear`, `address_mismatch`, `document_expired`, `invalid_document`,
  `other`. The review MUST record the operator and the time, MUST publish
  `player_kyc_document_reviewed`, and a review of a document already reviewed MUST be refused.
- **FR-011**: A rejection's reason MUST be shown to the player as the code's text in the player's
  language, and MUST stop being shown for the category once a replacement is recorded.
- **FR-012**: The account's verification status MUST be derived, in automatic mode, whenever a
  document is recorded or reviewed, a required category changes, the mode changes, or the profile
  changes, by these rules in this order: if any category's latest document (other than `other`) is
  rejected, `rejected`; else if any category's latest document or any `other` document is pending,
  `pending`; else if the policy's required categories are non-empty and each is satisfied by an
  approved latest document, `verified`; else, with no document at all, `unverified`; else `pending`.
  A derived `verified` on an account not already verified MUST be withheld as `pending` while the
  profile is incomplete (FR-016), naming the missing fields to the player, and as `unverified` while
  the profile changed after the last verification and no document has been reviewed since (the
  original's `rollup.go` and `handler.go`; Clarifications 2026-10-30).
- **FR-013**: A required category `identity` or `identity_front` MUST be satisfied by an approved
  `identity_front`, or by an approved legacy `identity` when no front exists; once a front exists the
  legacy document MUST count for nothing. `identity_back` MUST never be required by the policy.
- **FR-014**: A change of status MUST record an audit record (spec 001, `platform.audit`) with the
  actor — system, operator or player — the previous and new status and a reason, and MUST publish
  `player_kyc_verified`, `player_kyc_rejected` or `player_kyc_pending` as FR-030 says. A derivation
  that yields the current status MUST write nothing.
- **FR-015**: An operator MUST be able to set a player's mode to automatic or manual. In manual mode
  the service MUST NOT change the status by derivation; the operator MUST be able to set the status to
  pending or verified directly, and setting verified MUST be refused while the profile is incomplete,
  naming the missing fields. A switch to automatic MUST derive the status at once and clear the
  ready-for-final-review time.
- **FR-016**: The profile MUST be complete for verification: given name, family name, date of birth,
  telephone, address line, city, postal code and country present, and the date of birth a date at
  least eighteen years before today. The fields MUST be read from `players`' `player_profile_updated`
  facts, never asked for. A verified account whose required field changes MUST return to `unverified`
  with reason `profile_changed`, in either mode.
- **FR-017**: An operator MUST be able to set the categories required of one player. Setting a
  non-empty list MUST put the player in manual mode and the status to pending unless it is pending or
  rejected, and MUST NOT affect any other player or the brand's policy. The player's documents page
  MUST show those categories as required.
- **FR-018**: In manual mode the service MUST maintain the ready-for-final-review time: set when every
  player-required category has an approved latest document (vacuously when none), the profile is
  complete and the status is not verified; cleared when any of those stops holding. The time MUST
  change only on a transition, never on a recompute that changes nothing.

**Gates and thresholds**

- **FR-019**: The deposit check MUST take a player, a currency and an amount in minor units and answer,
  in this order: `DEPOSIT_BLOCKED_KYC` with the status-specific message when the policy's deposit gate is
  `KYC_APPROVED` and the status is not verified; `DEPOSIT_BLOCKED_KYC_THRESHOLD` when the policy has a
  deposit threshold, the status is not verified, and lifetime deposits plus the amount exceed it,
  flagging the player for review (threshold) once; `DEPOSIT_BLOCKED_SOF` when the policy has a source
  of funds threshold, the source of funds status is not cleared, and lifetime deposits plus the amount
  exceed it, flagging the player for source of funds review once; else allowed. Before the two
  threshold rules, a check in a currency other than the policy's KYC currency MUST be refused
  `DEPOSIT_BLOCKED_KYC_CURRENCY` while the policy has either threshold; with neither, the currency is
  not compared. The messages MUST be the original's three for unverified, pending and rejected.
- **FR-020**: The play check MUST answer `PLAY_BLOCKED_KYC` with the status-specific message when the
  policy's play gate is `KYC_APPROVED` and the status is not verified, else allowed.
- **FR-021**: Lifetime deposits MUST be kept per player and currency from `deposit_confirmed` on
  `money.transactions`, read in the policy's KYC currency, summed in minor units for the player's life at
  the brand, never reset; a deposit in another currency MUST NOT count. With no KYC currency named,
  nothing is summed and no threshold applies.
- **FR-022**: A policy save that lowers a threshold MUST flag for review, once, every unverified
  player whose lifetime deposits already exceed the new deposit threshold, and every player with
  source of funds not cleared whose deposits exceed the new source of funds threshold.
- **FR-023**: An operator MUST be able to clear a player's source of funds status, recording the
  operator and publishing `player_sof_cleared`; a cleared status MUST stay cleared until an operator
  sets it back to unverified.
- **FR-024**: A player flagged for review MUST appear in the queue under the reason until the account
  is verified, the source of funds is cleared, or an operator dismisses the flag with a note.
- **FR-038**: A `deposit_confirmed` whose own amount, in the KYC currency, exceeds the policy's
  deposit threshold MUST flag an unverified, pending or rejected player for review (large deposit) and
  publish `player_kyc_review_required`; it MUST NOT suspend anyone, and MUST NOT flag a verified
  player. With no deposit threshold it does nothing. **Change from the original**: the function also
  proposed suspending a rejected player, a proposal nothing acted on, and its deployed trigger fired only
  above 1,000,000 minor units whatever the policy said (Clarifications 2026-10-30).

**The window**

- **FR-025**: When a player registers on a brand whose policy has a window, the service MUST set the
  player's deadline to the registration time plus the window and a timer for it; a policy change MUST
  NOT move existing deadlines. When the timer fires on a player still unverified, the service MUST
  tell `players` to suspend the player with reason `kyc_window_expired` if the policy suspends on
  expiry, else flag the player for review (window), and in both cases publish
  `player_kyc_review_required`; the command MUST be idempotent under the player id and deadline.
  The window is enforced for every deadline set from go-live; a deadline the migration (spec 019)
  carries that has already passed MUST flag the player for review (window) and publish the fact, and
  MUST NOT suspend, so no one is suspended in one sweep at cutover. **Change from the original**:
  it sets the deadline at registration and nothing acts on it (the expiry function's body is empty
  and no sweeper reads `kyc_check_deadline_at`), so the window has never suspended anyone on
  Spinvibe; spec 137 intended it and the UKGC profile is built on it (Clarifications 2026-10-25).

**The policy**

- **FR-026**: The brand's policy MUST hold: deposit gate (`NONE` | `KYC_APPROVED`), play gate (the
  same), KYC currency (ISO 4217, or none; required while either threshold is set), deposit threshold (minor units or none), source of funds threshold (minor units or none),
  window (days or none), required categories, suspend on expiry, and the licence profile (`UKGC` |
  `MGA` | `CURACAO` | `CUSTOM`). Absent, it MUST read as the Curaçao default of User Story 3.
- **FR-027**: Applying a profile MUST set every field to the profile's defaults of User Story 3;
  overriding any field afterwards MUST set the profile to `CUSTOM`. An unknown profile MUST be refused
  naming the known ones.
- **FR-028**: Every policy change MUST be an audit record with the operator and the fields changed, and
  MUST take effect on the next decision.
- **FR-029**: The brand's requirements — the required categories — MUST be readable publicly.

**Facts**

- **FR-030**: `kyc` MUST produce on `casino.players`, keyed by the player id with the player as
  subject, these families at version 1: `player_kyc_submitted` (category, document id),
  `player_kyc_document_reviewed` (category, document id, outcome, reason code, reviewer id),
  `player_kyc_verified` (actor), `player_kyc_rejected` (actor, reason), `player_kyc_pending` (reason:
  `agent` | `required_category` | `profile_changed` | `document`), `player_kyc_review_required`
  (reason: `threshold` | `source_of_funds` | `window` | `large_deposit`), `player_sof_cleared`
  (operator id). No family MUST carry a personal field beyond ids; a reviewer id is an operator's, not
  a player's.
- **FR-031**: `kyc` MUST consume `money.transactions` (`deposit_confirmed`) for FR-021, and
  `casino.players` (`player_registered` for FR-025, `player_profile_updated` for FR-016,
  `player_closed` to stop timers and flags) from `players`.

**Personal data, retention and erasure**

- **FR-032**: Every document object, and any field extracted from one, MUST be personal data under
  the player (ankka 042). Document ids, categories, times, statuses, reason codes, operator ids and
  the lifetime deposit total MUST NOT be.
- **FR-033**: The service MUST implement the erasure handler: it MUST call `erase(subject)` on its
  bucket for the player's prefix (ankka 042 FR-026) and MUST run again on every later application, so
  a late upload is removed. Erasure MUST NOT remove the document records' non-personal fields.
- **FR-034**: Documents MUST be kept until the player's erasure applies; this service MUST NOT delete
  a document for any other reason. The hold a brand's licence requires on erasure is `players`'
  (spec 001 FR-025) to file. The profile defaults MUST be five years after the account closes for
  UKGC (MLR 2017 regulation 40), MGA and Curaçao alike; Curaçao's figure is not written down anywhere
  in the original, so it is a default the licence holder's compliance officer confirms, and records
  in the brand's values, before a Curaçao brand takes real money (Clarifications 2026-10-25).

**Reads and audit**

- **FR-035**: The queue view MUST list the brand's players by: docs awaiting review (count of
  documents with no decision), KYC pending, verified, rejected, ready for final review (with time
  since), review required by reason, source of funds review; each with a live count.
- **FR-036**: The player's documents read MUST return every category with its current document (or
  every `other` document), status, upload and review times, reason code, whether required by the
  policy and whether required of this player, the account's status and mode; the operator's read MUST
  add the reviewer, the deadline, the source of funds status and the lifetime deposits.
- **FR-037**: Every status change, every policy change, every review, every clearance and every view
  link issued MUST be an audit record on `platform.audit` naming the actor; the view record MUST name
  the requester, the document id and the time, and every issue MUST be kept, not the latest only.

### Key Entities

- **Policy**: one per brand; the gates, KYC currency, thresholds, window, required categories, suspend-on-expiry
  and profile; read as the Curaçao default when absent.
- **Player KYC record**: one event sourced entity per player: documents, status, mode, required
  categories, source of funds status, deadline, flags, ready-for-final-review time, and lifetime
  deposits per currency from the wallet's facts.
- **Document**: category, id, object name, content type, upload time, status (pending | approved |
  rejected), reason code, reviewer, review time; the object itself is personal data.
- **Status**: `unverified` | `pending` | `verified` | `rejected`; the player sees `pending` as
  "additional verification required".
- **Mode**: `automatic` | `manual`, per player.
- **Flag**: a reason a player is in the review queue: threshold, source of funds, window, large
  deposit, with the time and whether dismissed.
- **Gate decision**: allowed, or refused with a code and a message.
- **View link**: a fifteen-minute signed link to one object; never stored.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every case of the original's `rollup_test.go` and `handler_test.go` (61) and the KYC
  cases of `BackofficePlayerRoutesSpec` and `PlayerRoutesSpec` pass against the service's test kit
  with the same inputs and outcomes.
- **SC-002**: Playwright suites 28, 29, 30, 31, 32, 33 and 50 pass against a rebuilt brand with only
  base URLs changed (spec 001 FR-033).
- **SC-003**: A gate decision answers within 50 ms at the 99th percentile with the policy and the
  player's record in memory; no gate decision reads another service.
- **SC-004**: After an erasure applies and the store's soft-delete window ends, no object exists under
  the player's prefix in any version, and every document record's non-personal fields read as before.
- **SC-005**: Every view link issued in a test run has an audit record; zero unrecorded views.
- **SC-006**: A policy change is reflected in the next gate decision for any player with no restart
  and no delay beyond the write.

## Changes from the original

- **One service.** The policy routes, the player and backoffice KYC routes, the gates' decisions, the
  rollup, the threshold and window functions and the document store leave `casino-service`, the Go
  capability and the TL packs for `kyc`. The player's profile stays with `players`; the deposit and
  game-launch routes stay where they are and ask `kyc`.
- **Gates are a query, not inline code.** Today the deposit route and the session route each read the
  policy and the player's twin and decide; now they ask `kyc` (FR-019, FR-020). The codes and messages
  are unchanged.
- **The rollup's freshness window is gone.** The 15-minute `freshnessWindow` of the Go capability
  existed because the TL trigger fired on every twin write; a consumer of this service's own events
  needs no such gate. The rules of the derivation are unchanged (FR-012).
- **Review proposals become a queue.** `kyc_review`, `sof_review` and `suspend_player` action
  proposals on the reasoner's topic become flags in this service's queue and a command to `players`;
  nothing reaches the reasoner. The `kyc_review` proposal `EventIngestConsumer` makes for every captured
  payment by a pending player is not carried over (Clarifications 2026-10-30).
- **Source of funds can be cleared.** The original has no action that sets `sof_status` to `cleared`,
  so a player over the threshold was refused every deposit for good (FR-023).
- **One KYC currency.** The original summed every deposit into one number whatever its currency; the
  policy now names the currency its thresholds are in, and a deposit check in another is refused
  while a threshold is set (FR-019, FR-021).
- **A large deposit flags and suspends no one.** The original's function proposed suspending a rejected
  player and fired only above a fixed 1,000,000 minor units; the flag now follows the policy's deposit
  threshold alone (FR-038).
- **The deposit gate blocks the deposit.** The original's `notify/deposit` runs after the wallet has
  credited the money and its refusal is only logged; the check now runs before the deposit starts
  (FR-019, Clarifications 2026-10-08).
- **Lifetime deposits are real.** The original's counter was written only by a function never
  deployed, so its thresholds compared the one deposit alone; the total is now every confirmed
  deposit in the KYC currency (FR-021).
- **The compliance roles only.** The original admits any operator role through a fallback route; the
  per-player KYC routes now admit the compliance roles (FR-002, Clarifications 2026-10-30).
- **A changed policy reads custom; uploads are checked.** The original's policy save never set the
  profile to `CUSTOM` (FR-027), its confirmation took any object key without looking for it (FR-007),
  and its approval recorded no reviewer (FR-010).
- **Every view is recorded.** `kyc_view_log` was one property overwritten on each issue (FR-037).
- **Policy inheritance up the merchant hierarchy is dropped.** A brand is one project with one policy;
  the "ancestors" read goes.
- **The window's expiry is enforced** (FR-025, Clarifications 2026-10-25); today it is a deadline no
  one reads.
- **The upload proxy is the frontend's business.** The player site proxies the signed PUT today so
  the browser never reaches MinIO; with the bucket's CORS origins on the descriptor (ankka 039) it may
  upload directly or keep proxying, and this spec does not care which. The 5 MiB limit was the player
  site's ingress; it becomes the brand's setting.
- **Object names carry the subject prefix** (`subjects/<player id>/kyc/...`) instead of
  `<brand>/<player>/...`, so ankka 042's `erase(subject)` finds them.
- **Dropped**: the duplicate camelCase and
  snake_case profile keys (the fact carries one shape).

## Recordings

The original's own Go suite had no unskipped KYC scenario. `tests/original/e2e-rg-kyc.patch` adds
37 KYC tests to it (scenarios 28 and 29: documents, review, policy and gates), each mapped to a
scenario in `features/kyc/` in `tests/original/e2e-rg-kyc.md`; those are the recordings this service
replays (plan, research R2). What they do not reach (the queue and tab reads, the window, retention,
the profile-change downgrade, the lowered-threshold flag, the legacy-identity rows) is held to:

- Unit: `tl/runtime/capabilities/kyc_status_rollup/rollup_test.go` and `handler_test.go` (61 cases)
  and `substrate_test.go` (7); `BackofficePlayerRoutesSpec`'s KYC groups (approve, reject, reason
  codes, verification mode, status, required categories, the one-file lock, view links, the queue's
  order and facets); `PlayerRoutesSpec`'s view link.
- Playwright `28-kyc-document-view`, `29-kyc-document-review`, `30-kyc-policy-settings`,
  `31-kyc-deposit-gate`, `32-kyc-play-gate`, `33-kyc-auto-approval` and `50-kyc-manual-verification`,
  read as the original's intent where an assertion disagrees with its code (research R1 lists each).

Every requirement above names its source: the routes and Go capability cited in Context, specs 137,
118 and 197 of the original, or a decision in Clarifications.

## Assumptions

- The document categories and reason codes are the original's as of commit `ab82ea93` (2026-10-05).
- `players` (spec 008) emits `player_profile_updated` with the eight profile fields as `Personal`
  values and `player_registered` with the registration time; spec 001's contract is amended to list
  that family and `kyc` as a producer of `casino.players` with FR-030's families.
- `players`' deposit-initiation route (spec 008 FR-043) calls FR-019 and `game-provider`'s launch
  (spec 003 FR-007) calls FR-020 before anything else; `players` opens a game session without asking
  here (spec 008 FR-017); spec 007 reads this service's facts and calls nothing here.
- Amounts in the deposit check are minor units; the original multiplies a decimal by 100 at that
  route, which spec 001 FR-026 forbids at a boundary.
- The source of funds review uses the `other` category for the player's evidence, as spec 137
  assumed; no `source_of_funds` category is added.
- Customer.io messages for `kyc_approved`, `kyc_rejected`, `kyc_document_accepted` and
  `kyc_document_declined` are `engagement`'s (spec 014), consuming FR-030's families.
- The migration (spec 019) copies each player's `kyc_documents` array, statuses, mode, required
  categories, deadline and cumulative deposits into one opening event per player, and copies the
  objects from MinIO `casino-kyc` into the service's bucket under the subject prefix.
- An operator may upload on a player's behalf and confirm it, as the backoffice `upload-url` route
  allows today (FR-005), for the documents support receives by email: the upload takes a reason, is
  audited under the operator's identity, and the document shows in the player's own list as
  submitted by support, with the time, so nothing is filed under a player's name unseen. No
  Playwright test exercises the original's route; the port's parity test is the first.

## Dependencies

- Spec 001 (brands and projects): FR-002 placement, FR-011 contract (amended per Assumptions), FR-020
  route classes, FR-023 personal fields.
- Spec 002 (wallet): `deposit_confirmed` on `money.transactions` for FR-021.
- Spec 007 (payouts): reads `player_kyc_verified`, `player_kyc_rejected`, `player_kyc_submitted` and
  `player_kyc_pending` for its identity gate.
- Spec 008 (players): `player_registered`, `player_profile_updated`, `player_closed`; the suspend
  command; the deposit and game-session routes that call the gates.
- Spec 003 (game-provider): launch asks the play gate directly (003 FR-007).
- Spec 014 (engagement): consumes the review and status families.
- Spec 019 (migration): the opening event and the object copy.
- ankka 034 (bucket per service), 039 (CORS origins on the descriptor, versioning and soft delete),
  042 (`Personal`, the erasure handler, `erase(subject)`), 038 (no secret of its own beyond the
  bucket credential), 032 (the window timer).

## Out of scope

- Automated identity verification (Onfido, Sumsub, Jumio and the like): no adapter, no seam designed;
  a later spec may add one behind the review.
- Verification levels or tiers; per-player policy overrides beyond required categories and mode.
- Sanctions and politically-exposed-person screening: nothing in the original.
- The player site's and backoffice's pages: they change base URLs only (spec 001 FR-033).
- The emails and pushes a review triggers (spec 014); the audit store (spec 018); the player's
  profile fields (spec 008).
