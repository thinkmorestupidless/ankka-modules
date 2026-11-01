# Tasks: affiliate programme

**Input**: Design documents from `specs/013-affiliate-programme/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{routes,inbound,recording}.md, quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test (I)
and the living features the scenarios (VII); the port method makes parity tests a deliverable, written
before the component they hold to account and failing first (ankka-port §7). Every story opens with its
tests: kits, integration suites on the kit, feature bindings, SC suites, parity tests.

**Organization**: By user story. US2 (facts become commission) is built first and is the MVP: without the
ledger no other story can be observed. US1, US3 and US4 are the rest of P1; US5–US8 are P2; US10 and
US9 are P3. The pure packages, the core entities, the views the ledger needs, `Intake`, `ToLedger`,
the outbound seams and the kit are foundational.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US10 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `p/` stands for `services/affiliate-programme/src/main/scala/io/eitheror/casino/programme/`
- `p-test/` stands for `services/affiliate-programme/src/test/scala/io/eitheror/casino/programme/`
- `a/` stands for `services/attribution/src/main/scala/io/eitheror/casino/attribution/`, `m/` for
  `services/merchant/src/main/scala/io/eitheror/casino/merchant/` and `w/` for
  `services/wallet/src/main/scala/io/eitheror/casino/wallet/` (patterns copied, never changed)
- `S/` is the original's `substrate/affiliate-service/src/main/scala/io/eitheror/affiliate/` in
  `~/Documents/projects/eitheror/eitheror-platform`
- The service's sbt commands run in `services/affiliate-programme/`; Go commands in `tests/`; everything
  else at the root
- A recording run is made only against a local original on its kind stack with the affiliate service
  installed, never a shared or production one; kubectl always with `--context kind-…`

---

## Phase 1: Setup

**Purpose**: the build, the contract and realm additions, the manifest and the tool, the recipes and CI,
the recording driver, and the original's commission cases as failing tests.

- [X] T001 Create `services/affiliate-programme/` on `services/attribution/`'s template: `build.sbt`
  (Scala 3.9.0, `ankkaVersion = "0.13.0"`, the six ankka modules, `casino-contract` at
  `CASINO_VERSION`), `service.json` (runtime `0.13.0`, its database and secret store), `README.md` (a
  stub naming the components of plan.md), `p/ProgrammeService.scala` registering nothing yet
- [X] T002 [P] Add `Routes.affiliateManager()` (issuer `staff`; any of `affiliate_manager`,
  `brand_admin`, `platform_admin`) and `Routes.brandsOf(principal): BrandSet` (`brand_<slug>` roles;
  `platform_admin` is every brand) to `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`,
  with cases in `contract/src/test/scala/io/eitheror/casino/contract/access/RoutesSuite.scala`
  (research R11)
- [X] T003 [P] Add the realm role `affiliate_manager` to `brands/_realm/eitheror-operators.json`
- [X] T004 [P] Render `PROGRAMME_BRANDS` (comma-separated slugs of the brands that run `core`) for
  `programme/affiliate-programme` in `tools/modules/src/main/scala/.../Render.scala` beside
  `MERCHANT_BRANDS`, with a case in its `RenderSuite`; add to `shared/programme/manifest.json` the env
  (`PROGRAMME_BRANDS` rendered, `AFFILIATE_PORTAL_BASE_URL`, `AFFILIATE_BACKOFFICE_BASE_URL`,
  `CUSTOMERIO_REGION_DEFAULT`, `KEYCLOAK_REALM=eitheror`) and the project secret
  `keycloak-operators-admin` as `shared/payments/manifest.json` declares it (research R18)
- [X] T005 [P] Add to `justfile` the recipes `programme-test`, `programme-it`, `programme-parity`,
  `programme-run` (as `attribution-test`, `attribution-it`); add the `affiliate-programme` filter and job
  to `.github/workflows/ci.yml` (push over SSH: the token has no workflow scope)
- [X] T006 [P] Write `tests/original/programme-driver/` (Go, as `tests/original/attribution-driver/`,
  its recorder reused): the thirteen scenarios of contracts/recording.md, each driving the original's
  routes with a staff token for `brand_spinvibe` and an affiliate token, and recording requests and
  answers; `NotDriven` names the feature scenarios the original cannot show (hosted brands, webhooks,
  per-currency payouts, encryption); `tests/port/programme/README.md` names what the directory will hold;
  fix `tests/port/README.md`'s stale "not recorded yet" for attribution
- [X] T007 [P] Lift the original's `CommissionEngineSpec` and `CommissionFormulasSpec` cases
  (`S/../test/scala/io/eitheror/affiliate/`) into `p-test/commission/OriginalCasesSuite.scala`, amounts in
  minor units, the per-period guard cases marked as change 2; they fail until T010

---

## Phase 2: Foundational

**Purpose**: everything every story needs: the pure packages, the core entities, the ledger's views,
facts in from a brand's topic, the ledger written, the outbound seams, the kit.

### Pure packages (tests beside each)

- [X] T008 [P] Implement `p/periods/Periods.scala` (research R14: `periodOf(instant, zone)`,
  `periodEnd(period, zone)`, `Range.parse` with the original's grammar of `S/ReportingRange.scala`: a
  month, a month range, a day range, all time) with `p-test/periods/PeriodsSuite.scala` (zone edges,
  a bet at 23:30 local)
- [X] T009 [P] Implement `p/sealing/Sealing.scala` (AES-256-GCM `seal(identity, key, affiliate)` and
  `open`, a fresh 96-bit nonce per seal, the affiliate id as associated data; `Sealed.erased`) and
  `p/sealing/Digest.scala` (HMAC-SHA256 of the lowercased, trimmed email) with
  `p-test/sealing/SealingSuite.scala` (round trip; a tampered byte, another affiliate's id and another
  key all fail; two seals of one identity differ)
- [X] T010 [P] Implement `p/commission/` (`Ngr`, `Fees.on(amount, bp)` unfloored, `RevShare.delta(window,
  sums, emitted, bp)` HALF_UP on NGR floored at zero, `Override.delta(earnings, bp, emitted)`,
  `Claim.select(unsettled, periodEnd, priorClaims, threshold)`) until T007 passes, with
  `p-test/commission/CommissionSuite.scala` for what the original's cases do not cover (currency
  separation, zero omitted, a negative delta)
- [X] T011 [P] Implement `p/tags/TagSigner.scala` (spec 012 contracts/protocol.md *The tag*: JWS compact,
  `EdDSA`, `typ eo-tag`, `net programme`, at most 1,024 characters) with `p-test/tags/TagSignerSuite.scala`
  verifying each signed tag with a copy of `a/tag/Tag.scala`'s verification against the key document
  the programme would serve
- [X] T012 [P] Implement `p/network/Network.scala` (`ancestors(start, parentOf)` visiting each once,
  ending on a cycle; `wouldCycle(child, newParent, parentOf)`) with `p-test/network/NetworkSuite.scala`
- [X] T013 [P] Implement `p/postbacks/Macros.scala` (`{event}`, `{clickid}`, `{payout}` default `0`,
  `{status}` default `ok`, percent-encoded, as `S/Postback.scala:15-25`) and `p/csv/Export.scala`
  (contracts/routes.md *The export*), with suites
- [X] T014 [P] Implement `p/Settings.scala` (the env of T004, parsed and validated; `PROGRAMME_BRANDS`
  slugs `[a-z0-9-]`) and `p/ProgrammeCodecs.scala` (every stored type, as `w/WalletCodecs.scala`)

### The entities (tests first, then the entity)

- [X] T015 [P] `p-test/brands/BrandProgrammeSuite.scala`, `PlanSuite.scala` (key value kits): every
  command of data-model.md; `setGgrSource` takes effect from a named day; then `p/brands/BrandProgramme.scala`,
  `p/brands/Plan.scala`
- [X] T016 [P] `p-test/accounts/AffiliateSuite.scala` (event sourced kit): every command, the identity
  stored only as `Sealed`, `close` and `erased`; then `p/accounts/Affiliate.scala`
- [X] T017 [P] `p-test/accounts/MembershipSuite.scala`: every allowed transition and every refused one
  naming the status (FR-002), an operator's suspension standing over an ancestor's reactivation
  (FR-029), `activateInvited`, `revShareOverride`; then `p/accounts/Membership.scala`
- [X] T018 [P] `p-test/intake/FactInboxSuite.scala`: `claim` once, `duplicate` after; then
  `p/intake/FactInbox.scala`
- [X] T019 [P] `p-test/players/PlayerAttributionSuite.scala`: every row of data-model.md's kind table,
  including the lifetime guard, a first deposit below the minimum leaving it unset, the other GGR source
  recorded as `Ignored(other_source)`, `beforeBinding` recorded, withdrawals never below zero, a
  reversal with no completed withdrawal ignored, `player-closed` forgetting country and click; then
  `p/players/PlayerAttribution.scala`
- [X] T020 [P] `p-test/ledger/CommissionAccountSuite.scala`: each record command idempotent on its fact
  id; RevShare over both windows; fees per GGR fact; no payable fact when `payable` is false and the
  catch-up on the next NGR fact once it is true (FR-016); `recomputeOverride` deltas; `close` claiming
  up to the end plus earlier claims, below threshold claiming nothing, refused for a paid payout;
  `markPaid`; a snapshot at 500 events; then `p/ledger/CommissionAccount.scala`
- [X] T021 [P] `p-test/settlement/PayoutSuite.scala`: `open`, the approved-to-pending revert recording
  the superseded approval, every refused transition naming the status, paid never changing; then
  `p/settlement/Payout.scala`
- [X] T022 [P] `p-test/accounts/EmailIndexSuite.scala`, `TokenSuite.scala` (claim once, expiry); then
  `p/accounts/EmailIndex.scala`, `p/accounts/Token.scala`
- [X] T023 [P] `p-test/links/SigningKeysSuite.scala` (never retire the last; `withdrawLegacy` once) and
  `p-test/links/LinkSuite.scala`; then `p/links/SigningKeys.scala` and `p/links/Link.scala` (signed by
  `TagSigner`; the routes are US5's); `p/activity/AuditEntry.scala` and `p/comms/Notification.scala`
  (key value, with suites): every story's actions write an `AuditEntry` (FR-005) from here on

### The ledger's views, the secrets, the pipeline

- [X] T024 Implement `p/ledger/Ledger.scala` and `p/ledger/Unsettled.scala` (views over
  `CommissionAccount`, the declared queries of data-model.md) with `p-test/ledger/LedgerViewSuite.scala`
  (sums by type per period range equal the facts' sum; settled facts carry their payout)
- [X] T025 [P] Implement the views `p/accounts/Accounts.scala`, `p/accounts/Memberships.scala`,
  `p/players/Players.scala`, `p/settlement/Payouts.scala`, `p/intake/Inbox.scala` with a view suite each
- [X] T026 [P] Implement `p/accounts/Secrets.scala` (`dataKey(affiliate)` created once and cached 60 s,
  `destroy(affiliate)`, `emailIndexKey`, `brandSecret(slug, name)`; as `m/security/Secrets.scala`) with
  `p-test/accounts/SecretsSuite.scala` over `InMemorySecretStore`
- [X] T027 Implement `p/intake/Intake.scala` (contracts/inbound.md *Intake*), `p/intake/BrandConsumers.scala`
  (one `attribution-<slug>` consumer per slug of `PROGRAMME_BRANDS`, `ChangeSource.fromTopic(slug,
  "affiliates.attribution", …)` from `Earliest`) and `p/brands/Seed.scala` (timed action at start:
  `BrandProgramme.seed` per slug, the first signing key when none), with
  `p-test/intake/IntakeKitSuite.scala` (consumer kit: a duplicate, a mismatched brand, a click, a
  first deposit)
- [X] T028 Implement `p/ledger/ToLedger.scala` (consumer over `PlayerAttribution`, data-model.md
  *Consumers*; fraud flags and postbacks are added by US6 and US7) with
  `p-test/ledger/ToLedgerSuite.scala`; `Intake`'s refusals `revoked_link` and `not_a_member` and
  `PlayerAttribution`'s disagreements (FR-011, data-model.md) with cases in `IntakeKitSuite`
- [X] T029 [P] Implement the outbound seams: `p/comms/CustomerIo.scala` (trait and `HttpClient` client,
  `POST /v1/send/email` as `S/AffiliateInviteEmailService.scala:311-391`), `p/comms/EmailSend.scala`
  (workflow, three attempts over five minutes, failure recorded), `p/comms/Communications.scala`
  (consumer over `Membership`: application received, approved, rejected, a sub pending to its parent;
  each a `Notification` and an `EmailSend`; US7 adds the rest), `p/accounts/KeycloakAdmin.scala` (after
  `m/operators/KeycloakAdmin.scala`: create user with the attributes of `S/NetworkTree.scala:225-328`,
  assign and remove realm roles, find by email, reset password, update email, delete user)
- [X] T030 Implement `p/api/Access.scala` (the path's `{brand}` against `brandsOf`, after
  `services/game-provider-simulator/.../api/Access.scala:22-28`; the portal's self-or-descendant check
  through the `Accounts` view, `404 account_not_found` otherwise) and the four endpoint shells
  `p/api/OperatorEndpoint.scala` (`/v1/affiliates`), `PortalEndpoint.scala` (`/v1/my`),
  `JoinEndpoint.scala` (`/v1/join`), `ProgrammeEndpoint.scala` (`/v1/programme`) with their ACLs
  (research R12); register everything so far in `p/ProgrammeService.scala`
- [X] T031 Write `p-test/it/Kit.scala` (after `services/attribution/src/test/.../it/Kit.scala`: Postgres,
  `InMemoryBroker` carrying `affiliates.attribution` of `goldvibe` and `silvervibe`, `MovableClock`,
  staff and affiliate token stubs with roles), `FakeCustomerIo.scala`, `FakeKeycloak.scala`,
  `WebhookSender.scala` (signs as a brand), `PostbackReceiver.scala`, and `Facts.scala` (builders for
  the fourteen kinds)
- [X] T032 [P] Write `p-test/features/GherkinSuite.scala` binding `features/programme/*.feature` on the
  kit, every step pending until its story binds it
- [X] T033 [P] Write `p-test/port/Translate.scala` (contracts/recording.md: money, ids, player ids to
  pseudonyms, the brand as a filter, the reshaped routes) and `p-test/port/ParitySuite.scala`, one test
  per file in `tests/port/programme/` and per ledger read in `tests/port/attribution/`
- [ ] T034 Run the driver (T006) on the original's kind stack with the affiliate service installed;
  commit the recordings to `tests/port/programme/`

**Checkpoint**: a fact on a brand's topic is claimed once and reaches its player and the ledger.

---

## Phase 3: User Story 2 — A brand's facts become commission (P1) 🎯 MVP

**Goal**: every fact kind does what FR-010 to FR-017 say. **Independent test**: spec.md US2.

### Tests

- [X] T035 [P] [US2] `p-test/it/CommissionSuite.scala`: a registration, a first deposit, three GGR days,
  a bonus cost and a chargeback, in and out of order and with one duplicate, equal the original's fold;
  CPA once in the plan's currency; the GGR source switch from the next day; before-binding days fold
  nothing; both carryover windows
- [ ] T036 [P] [US2] Bind `features/programme/commission.feature` in `p-test/features/CommissionSteps.scala`
- [X] T037 [P] [US2] `p-test/it/IntakeSuite.scala` (topic cases): duplicates on one channel write once;
  a mismatched brand writes nothing and is counted; an unattributed fact is recorded with its reason

### Implementation

- [X] T038 [US2] Wire the brand's GGR source and its change day through `Intake` into
  `PlayerAttribution.apply`, and fees per folded GGR fact in `ToLedger`
- [X] T039 [US2] CPA in the plan's currency, the payment fee per deposit, and FR-016 for a suspended
  membership in `ToLedger`; settle research R20's first item against `S/InternalRoutes.scala:761-1171`
  and record the answer in research.md *Changed at implementation*
- [ ] T040 [US2] Parity: the ledger reads of the eight `tests/port/attribution/` driver recordings and the
  programme driver's `cpa once` and `revshare and carryover` in `ParitySuite`, each divergence named by
  change number

**Checkpoint**: commission.feature green; the MVP's ledger matches the original's.

---

## Phase 4: User Story 1 — An affiliate works several brands from one account (P1)

**Goal**: one account, a membership per brand, every read per brand or across brands.

### Tests

- [ ] T041 [P] [US1] `p-test/it/MembershipsSuite.scala`: apply to A then B; approve on the default plan;
  a manager of A only cannot see B's application; a suspended membership's facts recorded without payable
  facts; reapply after rejection; a duplicate email refused; the raw status route answers `404`
- [ ] T042 [P] [US1] Bind `features/programme/memberships.feature` in `p-test/features/MembershipSteps.scala`

### Implementation

- [ ] T043 [US1] `POST /v1/affiliates/{brand}/apply` (public): `EmailIndex.claim` of the digest, a data
  key and a `Sealed` identity for a new email, a membership for an existing account, `409` for a
  pending or active membership at the brand (FR-003)
- [ ] T044 [US1] Operator routes O14, O15, O20, O21 (approve with plan, reject with reason, read and patch
  the account, `revShareOverride` on the membership) in `p/api/OperatorEndpoint.scala`
- [ ] T045 [US1] `p/accounts/Provisioning.scala` (consumer over `Membership`): the realm user and the
  roles `affiliate` and `brand_<slug>` on activation (research R11), the brand role removed when no
  membership there is active or suspended; a default link on activation (as `S/NetworkTree.scala:178-212`,
  T023's `Link` created with `campaign default`)
- [ ] T046 [US1] Revenue reads with the brand as a filter, per brand and currency, never summed across
  currencies: portal M1–M5 and operator O22, O37–O39 in `p/api/PortalEndpoint.scala` and
  `OperatorEndpoint.scala`, over `Ledger`; portal M6–M10 (`account`, `plan`, `available-plans`,
  `programme`)
- [ ] T047 [US1] Parity: `apply, approve, reject, reapply`

**Checkpoint**: memberships.feature green; SC-003.

---

## Phase 5: User Story 3 — A sub-affiliate network earns override (P1)

### Tests

- [ ] T048 [P] [US3] `p-test/it/NetworkSuite.scala`: three tiers, each ancestor's delta checked against
  the original's `emitOverrideDelta`; no override where the sub has no membership; a cycle ends the walk;
  an email invitation active at once; an invite link pending; a request outside the subtree refused
- [ ] T049 [P] [US3] Bind `features/programme/network.feature` in `p-test/features/NetworkSteps.scala`

### Implementation

- [ ] T050 [US3] `p/ledger/Overrides.scala` (consumer over `EarningsChanged`, research R7) and its kit suite
- [ ] T051 [US3] `p/links/InviteLink.scala` (key value, 50 an hour) with its suite; `p/api/JoinEndpoint.scala`
  P3 and P4; portal M31–M33
- [ ] T052 [US3] Portal M13–M21 (network health, network, a sub, its approve, suspend and reactivate at
  `?brand=`, `sub-affiliates/invite` with `brands`, `sub-affiliates`); the email invitation through
  `Membership.activateInvited`, `Token.issue` and an `EmailSend`; operator O9–O13 (O11–O13 reshaped,
  routes.md) and O27; `setParent` refused `parent_cycle` through `Network.wouldCycle`
- [ ] T053 [US3] Parity: `network override`, `invite and join`

---

## Phase 6: User Story 4 — A period closes and affiliates are paid (P1)

### Tests

- [ ] T054 [P] [US4] `p-test/it/CloseSuite.scala`: a close claims and writes pending; below threshold
  nothing; a late fact reverts an approved payout; a paid payout unchanged and its late fact in the next
  close; two currencies two payouts; two concurrent closes one result; then a thousand closes with
  interleaved late facts, no fact claimed twice and none older than the last close unclaimed (SC-006)
- [ ] T055 [P] [US4] Bind `features/programme/settlement.feature` in `p-test/features/SettlementSteps.scala`

### Implementation

- [ ] T056 [US4] `p/settlement/Close.scala` (workflow, research R8: 50 accounts a step) and its kit suite;
  O40 starting or rejoining it, O41
- [ ] T057 [US4] O42: approve, reject, paid; an override figure written as the adjustment
  `override-figure/<payoutId>` claimed by the payout before approval (FR-019); `markPaid` on the account
- [ ] T058 [US4] O28 adjustments (FR-022); the export route and its `payment_details_read` audit
  (FR-021, `p/csv/Export.scala`); portal M5
- [ ] T059 [US4] Parity: `close and pay`

---

## Phase 7: User Story 5 — Links, bonus codes and invite links carry the programme's tag (P2)

### Tests

- [ ] T060 [P] [US5] `p-test/it/DocumentsSuite.scala`: a link's tag verifies against the served key
  document with 012's `Tag`; a revoked link in the revocation list within 300 s; the legacy tag
  document maps imported tags and answers `404` after the transition; the code document's version bumps
  on each change; a key rotated keeps the old verifying until its expiry
- [ ] T061 [P] [US5] Bind `features/programme/links.feature` in `p-test/features/LinkSteps.scala`

### Implementation

- [ ] T062 [US5] `p/links/BonusCode.scala` (one wins a concurrent claim; revoke frees the text) with its
  suite; portal M11, M12, M28–M30; operator O35, O36 over T023's `Link`
- [ ] T063 [US5] `p/players/ClickRecord.scala` and `p/links/LinkStats.scala` (click counts from click facts
  only, FR-024)
- [ ] T064 [US5] The views `p/links/Codes.scala`, `Revocations.scala` (closed and erased affiliates
  included), `LegacyTags.scala`, the `Revocations` timed action, the four document routes of
  `ProgrammeEndpoint` (contracts/inbound.md *The documents*) and the legacy withdrawal route
  (contracts/routes.md)
- [ ] T065 [US5] Parity: `links and codes`

---

## Phase 8: User Story 6 — Managers run the programme per brand and across brands (P2)

### Tests

- [ ] T066 [P] [US6] `p-test/it/ManagersSuite.scala`: a manager of A lists only A; a request naming B is
  refused `403 brand_not_held`; a manager of A and B reads per brand and currency; plans apply from the
  next fact; adjustments; a fraud flag once per player, brand and reason; dismissal tells the affiliate;
  the read-only view changes nothing; add, reset and revoke a manager
- [ ] T067 [P] [US6] Bind `features/programme/managers.feature` in `p-test/features/ManagerSteps.scala`

### Implementation

- [ ] T068 [US6] O1–O3 and `DELETE /{brand}/managers/{user}` (change 16) through `KeycloakAdmin`, admitted
  to `brand_admin` and `platform_admin` only; O4–O6 plans with `cpaCurrency`; O43–O46 settings and
  programme page (GGR source, reporting zone, sender configuration, default plan, terms)
- [ ] T069 [US6] O7, O8: the account list decrypting the brand's page and filtering by search, status,
  manager, group and tags in the endpoint (research R9); creating an account active with a membership
- [ ] T070 [US6] `p/activity/FraudFlag.scala` raised by `ToLedger` when a player's counters cross the
  brand's thresholds; O48, O49
- [ ] T071 [US6] `p/activity/Note.scala`, `Creative.scala`, the `Activity` view; O23–O25, O47, O50–O52;
  `GET /{brand}/unattributed` (contracts/routes.md); a retired brand refusing applications and status
  changes (data-model.md `BrandProgramme`)
- [ ] T072 [US6] O29–O34, the read-only view (`view/{page}`, `commission` with `?period=`)
- [ ] T073 [US6] Parity: `plans`, `managers`, `fraud flags`, `notes, messages, creatives, settings,
  programme page`, `read-only view`

---

## Phase 9: User Story 7 — The programme talks to affiliates (P2)

### Tests

- [ ] T074 [P] [US7] `p-test/it/CommunicationsSuite.scala`: each happening of communications.feature writes
  a notification and one Customer.io send with the brand's sender configuration; a failing send blocks
  nothing; a postback to a failing receiver retried to 24 h on the `MovableClock`, its outcome visible
  within a minute (SC-009); a parent told of a pending sub once
- [ ] T075 [P] [US7] Bind `features/programme/communications.feature` in `p-test/features/CommunicationSteps.scala`

### Implementation

- [ ] T076 [US7] Extend `p/comms/Communications.scala` over `Payout` and `FraudFlag` and the invitation
  and password happenings; portal M24, M25
- [ ] T077 [US7] `p/comms/Postback.scala` (workflow after `a/carriers/Delivery.scala`, the retry by timer),
  `PostbackOutcome`, queued by `ToLedger` for registration, first deposit, deposit and RevShare; the
  portal's `postbacks` route
- [ ] T078 [US7] P5, P6 (forgot password, accept invitation, `mode=reset`), O16, O17; M34, M35; M26, M27
- [ ] T079 [US7] Parity: `notifications`

---

## Phase 10: User Story 8 — An affiliate is a data subject (P2)

### Tests

- [ ] T080 [P] [US8] `p-test/it/LeakSuite.scala`: `pg_dump` of the service's database, journals and
  views included, holds no test name, email or IBAN; after an erasure, opening the sealed identity with
  the secret store fails (SC-007)
- [ ] T081 [P] [US8] Bind `features/programme/data-subjects.feature` in `p-test/features/DataSubjectSteps.scala`

### Implementation

- [ ] T082 [US8] `p/accounts/Erasure.scala` (workflow: hold to the filing day plus
  `PROGRAMME_RETENTION_DAYS`, added to `p/Settings.scala` and the manifest, default 1825; destroy the key, release the email index,
  delete the realm user, record) with its kit suite; the close and erasure routes; O18 update-email
  re-sealing and moving the index
- [ ] T083 [US8] O26 and M23 show pseudonyms, countries and figures only; a `player-closed` fact's effect
  visible in both

---

## Phase 11: User Story 10 — Another company's brand runs on the programme (P3)

### Tests

- [ ] T084 [P] [US10] `p-test/it/HostingSuite.scala`: add a hosted brand; its webhook fact becomes
  commission; a bad signature, a wrong protocol and a fact id mismatch refused with nothing recorded; the
  same fact on the topic and by webhook writes once (SC-004); a hosted brand's manager and an eitheror
  manager sharing an affiliate each see only theirs (SC-010); the usage report per currency, refused to a
  manager
- [ ] T085 [P] [US10] Bind `features/programme/hosting.feature` in `p-test/features/HostingSteps.scala`

### Implementation

- [ ] T086 [US10] `POST /v1/programme/inbound/{brand}` (contracts/inbound.md, constant-time HMAC)
- [ ] T087 [US10] `GET`, `POST /v1/programme/brands`, `PATCH /v1/programme/brands/{brand}` (the webhook
  secret generated and shown once) and `GET /v1/programme/brands/{brand}/usage?month=`, `platform_admin`

---

## Phase 12: User Story 9 — Spinvibe's affiliates move in (P3)

### Tests

- [ ] T088 [P] [US9] `p-test/it/ImportSuite.scala`: a bundle exported from the recorded original imports,
  folds to the original's revenue and payouts for every affiliate and period, keeps legacy tags and gains
  signed ones, attributes a later fact for an imported player, and imports again writing nothing (SC-008)
- [ ] T089 [P] [US9] Bind `features/programme/import.feature` in `p-test/features/ImportSteps.scala`

### Implementation

- [ ] T090 [US9] `tests/original/programme-export/` (Go): reads the original's twins per namespace of
  research R1 into a bundle; player ids become pseudonyms through the brand's `attribution` (research R19)
- [ ] T091 [US9] `p/imports/Import.scala` (workflow, one step per section, deterministic ids) and
  `POST /v1/programme/imports/{brand}`

---

## Phase 13: Polish & Cross-Cutting Concerns

- [ ] T092 [P] `p-test/it/LatencySuite.scala`: a fact in revenue within 10 s (SC-005)
- [ ] T093 [P] `p-test/port/CoverageSuite.scala`: every scenario of `features/programme/` bound, every
  recording replayed or named in `NotDriven`
- [ ] T094 [P] `services/affiliate-programme/README.md`: components, secrets, how to add a brand here
  (re-render, restart) and a hosted brand, the suites
- [ ] T095 Quickstart §6: `just programme-run`, the walk-through it describes
- [ ] T096 Quickstart §7 on kind: `shared/programme` beside `brands/goldvibe`, the grant consumed, a hosted
  brand's webhook landing; settle research R20's second item (a removed brand's component)
- [ ] T097 Record what changed at implementation in research.md *Changed at implementation*

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (1)**: T001 first; T002–T007 together after it. T002 publishes the contract every service task
  needs.
- **Foundational (2)**: after Setup; blocks every story. T008–T014 together (T010 makes T007 pass);
  T015–T023 together after T014; T024, T025 after T020, T021, T016, T017, T019; T026 after T014; T027
  after T015, T018, T019, T026; T028 after T020, T027; T029 after T014, T017, T023; T030 after T025, T026; T031
  after T027–T030; T032, T033 after T031. T034 needs T006, the original's kind stack and Docker, and
  gates every parity task.
- **US2 (3)**: after Foundational. The MVP.
- **US1 (4)**: after US2 (revenue reads need the ledger).
- **US3 (5)**: after US1 (memberships and the portal's access).
- **US4 (6)**: after US2; can run beside US1 and US3.
- **US5 (7)**: after US1; beside US3 and US4.
- **US6 (8)**: after US1; T070 after US2's `ToLedger`.
- **US7 (9)**: after US1 and US4 (payout happenings).
- **US8 (10)**: after US1.
- **US10 (11)**: after US2 and US6 (managers and access).
- **US9 (12)**: after US2, US4, US5.
- **Polish (13)**: after every story; T096 last but T097.

### Parallel opportunities

- Phase 1: T002–T007 together.
- Phase 2: T008–T014; then T015–T023; then T024–T026, T029.
- After US1: US3, US4, US5, US6 and US8 can run in parallel; each story's tests together.

---

## Parallel Example: User Story 2

```bash
# tests first, together:
Task: "CommissionSuite in p-test/it/CommissionSuite.scala"
Task: "Bind commission.feature in p-test/features/CommissionSteps.scala"
Task: "IntakeSuite topic cases in p-test/it/IntakeSuite.scala"
# then:
Task: "GGR source and fees through Intake and ToLedger"
Task: "CPA, payment fee, suspension in ToLedger"
# then:
Task: "Parity: attribution's eight ledger reads, cpa once, revshare and carryover"
```

---

## Implementation Strategy

### MVP first (User Story 2)

1. Phases 1 and 2, with T034 started the moment the driver builds and the original's stack is up.
2. Phase 3: facts on a brand's topic become the ledger the original folds. commission.feature green.
3. Stop and run quickstart §2 and §3's `IntakeSuite` and `CommissionSuite`.

### Incremental delivery

1. US1: accounts, memberships, the portal's reads. After it an affiliate can apply and see revenue.
2. US3 and US4: networks and payouts. After them the programme pays.
3. US5–US8: links and documents, managers, communications, data subjects.
4. US10: hosted brands. US9: the import, ahead of spec 019's cutover.
5. Polish, then quickstart §6 and §7 on kind before anything real.

## Notes

- Parity tests fail first: T040, T047, T053, T059, T065, T073 and T079 are written to fail until their
  story's components exist, and are run again at the story's end.
- Commit after each task or logical group; `just features` stays green throughout.
