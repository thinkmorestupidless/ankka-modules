# Data model: affiliate programme

Phase 1 of [plan.md](plan.md). Five event sourced entities, sixteen key value entities, five
workflows, twelve views, a consumer per brand plus four internal, three timed actions. Every id is a
`String`; every time an `Instant`; every amount a `Long` of minor units; every period `yyyy-MM` in the
brand's reporting zone (research R14). No player id is stored anywhere: a player is a pseudonym at a
brand. No readable personal value is stored anywhere: an affiliate's identity is `Sealed` (research R9).

## Shared types

- `Sealed(ciphertext, nonce, keyVersion)`: AES-256-GCM of the JSON of `Identity`, associated data the
  affiliate id. `Identity(name, displayName, email, companyName, website, trafficSource, paymentMethod)`.
  Erased: `Sealed.erased`, which every reader shows as `erased`.
- `FactType`: NGR inputs `ggr`, `bonus-cost`, `chargeback`, `admin-fee`, `payment-fee`, `provider-fee`;
  payable `cpa`, `revshare`, `override`, `adjustment` (ADR 0042, `S/CommissionEngine.scala:56-69`).
- `LedgerFact(id, type, amount: Long (signed), occurredAt, recordedAt, period, sourceAffiliate: Option, settledBy: Option[payoutId])`.
- `Status` (membership): `pending`, `active`, `suspended`, `rejected`. `PayoutStatus`: `pending`,
  `approved`, `rejected`, `paid`.
- `Window`: `lifetime` (negative carryover) or `period`.
- Fact ids written by the programme are deterministic: `cpa/<brand>/<pseudonym>`,
  `ggr/<factId>`, `admin-fee/<factId>`, `provider-fee/<factId>`, `payment-fee/<factId>`,
  `bonus-cost/<factId>`, `chargeback/<factId>`, `revshare/<factId>`, `override/<source>/<period>/<seq>`,
  `adjustment/<uuid>`, `override-figure/<payoutId>`.

## Event sourced entities

### `Affiliate`: id the affiliate id (`aff-<base32 of 10 random bytes>`; imported ids kept)

| Field | Meaning |
|---|---|
| `identity` | `Sealed` |
| `emailDigest` | HMAC of the lowercased email (the `EmailIndex` id) |
| `parent`, `overridePercent` | the network (FR-028), percent as basis points `0..10000` |
| `minimumPayout` | `Map[currency, Long]`, default none (FR-018) |
| `postbackUrl` | `Option[String]`, not personal |
| `keycloakUser` | `Option[String]` |
| `termsVersion`, `termsAcceptedAt` | the account's |
| `firstLoginAt`, `lastLoginAt` | FR-034 |
| `managerId`, `group`, `tags` | operator fields, as the original |
| `closed`, `erased` | `Option[Instant]` |

Commands: `create(identity, digest, parent, by, at)`, `updateIdentity(sealed, digest, by)`,
`update(fields, by)`, `setParent(parent, by)` (refused `parent_cycle` when the consumer's check finds a
cycle; the endpoint checks through the `Network` view first), `setOverride(bp, by)`, `login(first|last, at)`,
`close(by, at)`, `erased(at)`, `get`. Events: one per command, each with the actor.

### `Membership`: id `<affiliate>/<brand>`

| Field | Meaning |
|---|---|
| `status` | `Status` |
| `plan` | plan id at the brand |
| `revShareOverride` | `Option[basis points]` (FR-002) |
| `application` | `{appliedAt, termsVersion, source: self | invite-email | invite-link | operator | import}` |
| `suspendedBy` | `Option[operator | ancestor]` (an operator's suspension stands, FR-029) |

Commands and the transitions they allow (FR-002): `apply` (none → pending; rejected → pending),
`approve(plan, by)` (pending → active), `reject(reason, by)` (pending → rejected), `suspend(by, kind)`
(active → suspended), `reactivate(by, kind)` (suspended → active; refused for an ancestor when
`suspendedBy` is `operator`), `activateInvited(plan, by)` (none → active, the email invitation),
`setPlan(plan, by)`, `setRevShareOverride(bp, by)`. Any other transition is refused naming the status.

### `PlayerAttribution`: id `<brand>/<pseudonym>`

| Field | Meaning |
|---|---|
| `binding` | `Option[(affiliate, link, campaign, subIds, clickId, boundAt)]` (FR-010) |
| `country`, `clickId` | until `player-closed` (FR-038) |
| `firstDepositGuard` | `Option[(factId, at)]`, lifetime (FR-013) |
| `totals` | deposits per currency (count, sum, 2nd and 3rd), withdrawals per currency (never below 0) |
| `completedWithdrawals` | `Set[reference]` not yet reversed |
| `fraud` | bonus-cost count, chargeback count |
| `beforeBinding` | `Vector[(reportingDay, currency, ggr)]` recorded, never folded |
| `seen` | the last 1,000 fact ids (a second guard behind `FactInbox`) |
| `disagreements` | facts whose affiliate or link differs from the binding: fact id, what it named (edge case; commission follows the binding) |
| `closed` | `Option[(category, at)]` |

Command `apply(fact)` answers what it recorded: `Bound`, `Duplicate`, `Unattributed(reason)`,
`FirstDeposit(qualifies: Boolean)`, `Deposit`, `Ngr(kind, amount)`, `BeforeBinding`, `WithdrawalTotal`,
`Ignored(reason)`, `Closed`. Rules by kind:

| Kind | Recorded |
|---|---|
| `registration` | the binding once; a second is `Duplicate` (FR-010) |
| `first-deposit` | when unguarded and at or above the plan's minimum (passed in by the caller), the guard and `FirstDeposit(true)`; below it, the guard stays unset. No totals: the brand also emits a `deposit` for the same deposit (spec 012), which counts it |
| `deposit` | totals and the payment fee; with the guard unset and at or above the minimum, also the guard and `FirstDeposit(true)` |
| `ggr-daily`, `ggr-adjustment`, `ggr-round` | `Ngr` when the kind is the brand's GGR source (passed in), else `Ignored(other_source)` recorded for reconciliation; `beforeBinding` true: `BeforeBinding` |
| `bonus-cost`, `chargeback` | `Ngr`; the fraud counters |
| `withdrawal-completed` | total up, reference kept |
| `withdrawal-reversed` | total down, never below 0, only for a kept reference, else `Ignored` |
| `withdrawal-requested`, `withdrawal-failed` | nothing changes (commission.feature) |
| `player-closed` | country and click forgotten; amounts kept |

Every kind but `registration` and `click` on an unbound player is `Unattributed(no_attribution)`. A
fact naming another affiliate or link than the binding is applied to the binding and recorded as a
disagreement. `Intake` refuses before the player is touched, recording `Unattributed(revoked_link)`
when the binding's link is revoked and `Unattributed(not_a_member)` when the bound affiliate holds no
membership at the brand (FR-011).

### `CommissionAccount`: id `<affiliate>/<brand>/<currency>`

State: per period `Sums(ngrInputs by type, payable by type)`, `revShareEmitted` per period and the
lifetime total, `overrideEmitted` per `(source, period)`, `unsettled: Vector[LedgerFact]` (payable,
unclaimed or claimed by a payout not yet paid), `claims: Map[payoutId, Set[factId]]`, `paid: Set[payoutId]`,
the last 10,000 fact ids written.

Commands (each idempotent on its fact id):

- `recordNgr(id, type, amount, occurredAt, plan)`: the NGR input, the fees per GGR fact at the brand's
  percentages (unfloored), and, unless `plan.payable` is false (membership not active), the RevShare
  delta over the plan's window (FR-014); emits `EarningsChanged(period, earnings)` when payable moved.
- `recordCpa(id, amount, occurredAt)` (in the plan's currency; the account is the plan currency's).
- `recordPaymentFee(id, amount, occurredAt)`.
- `recordAdjustment(id, amount, reason, by, claimedBy: Option)`.
- `recomputeOverride(source, period, sourceEarnings, percentBp)`: writes the delta (FR-015).
- `close(payoutId, periodEnd, threshold)`: claims every unsettled payable fact with `occurredAt` ≤
  `periodEnd`, plus the payout's earlier claims; answers `Claimed(total, figures)` or `BelowThreshold`;
  refused `paid` when the payout is paid.
- `markPaid(payoutId)`: the claimed facts leave `unsettled`.
- `get`, `sums(period)`.

Events: `FactWritten(LedgerFact)`, `FactsClaimed(payoutId, ids)`, `PayoutPaid(payoutId)`,
`EarningsChanged(period, earnings)`. Snapshot every 500 events.

### `Payout`: id `<affiliate>/<brand>/<currency>/<period>`

`status`, figures (`cpa`, `revshare`, `override`, `adjustments`, `total`), `approvedBy/At`,
`rejectedReason`, `paidAt`, `supersededApprovals`. Commands: `open(figures)` (writes or, for an approved
payout whose total changed, reverts to pending and records the superseded approval, FR-019),
`approve(by, total)`, `reject(by, reason)`, `paid(by, at)`. Any other transition is refused naming the
status (settlement.feature).

## Key value entities

| Entity | Id | Holds | Commands |
|---|---|---|---|
| `FactInbox` | `<brand>/<factId>` | its kind, currency, channel, received at and outcome; not its body (FR-038) | `claim(fact, channel)` → `new` or `duplicate`; `outcome(o)` |
| `BrandProgramme` | brand slug | origin (`here`, `elsewhere`, `hosted`), fees (bp), fraud thresholds, payout cycle, reporting zone, GGR source and its change day, default plan, programme page, sender configuration (region, transactional ids), retired (a retired brand refuses applications and status changes; closes, payouts and reads continue), counts of duplicates and mismatches | `seed`, `add`, `update`, `setGgrSource(source, fromDay)`, `retire`, `countDuplicate`, `countMismatch`, `get` |
| `Plan` | `<brand>/<planId>` | name, type, CPA amount and currency, RevShare bp, minimum deposit, window, status | `create` (refused `plan_already_exists` by name), `update`, `get` |
| `EmailIndex` | HMAC of the email | affiliate id | `claim(affiliate)` (refused when another holds it), `release`, `get` |
| `Link` | link id | affiliate, brand, campaign, landing path, utm, sub-ids, signed tag, legacy btag, revoked | `create`, `revoke`, `get` |
| `BonusCode` | `<brand>/<UPPER code>` | affiliate, link, offer, tag, status | `claim(affiliate, link, offer, tag)` (refused when live: one wins, FR-025), `revoke` (frees the text) |
| `InviteLink` | token | affiliate, brand(s), plan, status, applications this hour | `create`, `use(at)` (refused past 50 an hour), `revoke` |
| `Token` | SHA-256 of the token | user, purpose (`invite`, `reset`), expires, used | `issue`, `claim(at)` once |
| `ClickRecord` | `<brand>/<factId>` | link, click id, device, country, at | `record` |
| `SigningKeys` | `programme` | kids with public keys, made, retired; `legacyWithdrawnAt` | `add`, `retire` (never the last), `withdrawLegacy(by, at)` once |
| `FraudFlag` | `<brand>/<pseudonym>/<reason>` | affiliate, reason, raised, dismissed by and why | `raise` once, `dismiss` |
| `Notification` | uuid | affiliate, type, body, read | `write`, `read` |
| `Note`, `Creative`, `AuditEntry` | uuid | as the original (`S/AffiliateTwins.scala`) | `write` (creative also `delete`) |
| `PostbackOutcome` | `<affiliate>/<event>` | last attempt, status code, attempts, finished | `attempted` |

## Workflows

- `Close`, id `<brand>/<period>`: step `page(cursor)` reads 50 accounts from `Unsettled`, for each calls
  `Payout.get` (skips a paid one), `CommissionAccount.close`, `Payout.open`; transitions to itself until
  the page is empty; answers the payouts written (`{period, created, payouts}` as the original).
- `Erasure`, id the affiliate id: `hold` pauses until the retention end (filing day plus
  `PROGRAMME_RETENTION_DAYS`, default 1825 as `players`, spec 008), `destroy` deletes the data key,
  `release` the email index, `realm` deletes the realm user, `record` writes `Affiliate.erased`.
- `Postback`, id `<factId>/<affiliate>`: `send`, then a timer-driven retry schedule to 24 hours (R13).
- `EmailSend`, id uuid: up to three attempts over five minutes.
- `Import`, id `<brand>/<bundle digest>`: one step per section of the bundle, every write deterministic.

## Views

| View | Source | Rows | Declared queries |
|---|---|---|---|
| `Ledger` | `CommissionAccount` | one per `FactWritten`, with settlement from `FactsClaimed`/`PayoutPaid` | sums by type for affiliate(s), brand(s), currency, period range; breakdown; deductions |
| `Unsettled` | `CommissionAccount` | one per account with unsettled facts and the earliest | accounts at a brand with unsettled facts before an instant, paged |
| `Accounts` | `Affiliate` | sealed identity, parent, override, status fields | by id; children of; all (for the network walk) |
| `Memberships` | `Membership` | one per membership | by brand and status; by affiliate |
| `Players` | `PlayerAttribution` | pseudonym, affiliate, country, totals, first deposit | by affiliate and brand; by link |
| `Payouts` | `Payout` | one per payout | by brand and period; by affiliate; approved by brand and period |
| `LinkStats` | `ClickRecord`, `PlayerAttribution` | clicks and registrations per link | by affiliate; by link |
| `Codes` | `BonusCode` | live codes per brand | the code document |
| `Revocations`, `LegacyTags` | `Link`, `Affiliate` | revoked links, and affiliates whose account is closed or erased; imported legacy tags, until withdrawn | the two documents |
| `Inbox` | `FactInbox` | received, duplicates, unattributed, mismatched per brand and day | the usage report; operator counts |
| `Activity` | `AuditEntry`, `Notification`, `Note`, `FraudFlag`, `PostbackOutcome` | one row per entry, typed | by affiliate and brand |

`platform_admin` and managers read through the endpoints only; every list is filtered to the
caller's brands (research R11).

## Consumers

- `attribution-<slug>` per slug in `PROGRAMME_BRANDS`: `affiliates.attribution` of project `<slug>` →
  `Intake` (below).
- `Intake` (a function shared with the webhook endpoint): checks the fact's brand is the channel's
  (else `countMismatch`), `FactInbox.claim`, then by kind: `click` → `ClickRecord.record`; otherwise
  `PlayerAttribution.apply` with the brand's GGR source and, for a first deposit, the plan's minimum.
- `ToLedger` (over `PlayerAttribution`): reads `Membership` and `Plan`; `FirstDeposit(true)` →
  `recordCpa` when active (FR-016); `Ngr` → `recordNgr` with `payable = active`; `Deposit` and first
  deposits → `recordPaymentFee`; queues postbacks; raises fraud flags when a counter crosses the brand's
  threshold (FR-032); `Unattributed` → `FactInbox.outcome`.
- `Overrides` (over `CommissionAccount` `EarningsChanged`): the ancestor walk (research R7).
- `Communications` (over `Membership`, `Payout`, `FraudFlag`): notifications and `EmailSend`s.
- `Provisioning` (over `Membership`): realm roles `affiliate` and `brand_<slug>` on activation, the
  brand role removed when no membership at it is active or suspended.

## Timed actions

- `Seed` at start: `BrandProgramme.seed` per slug in `PROGRAMME_BRANDS`, the first signing key when none.
- `Revocations` every 300 s: bumps the revocation list's `asOf`.
- `PostbackRetry`: the `Postback` workflow's timer.

## Secrets (the service's store)

`affiliate/<id>/data-key`, `email-index-key`, `signing-key/<kid>`, `brand/<slug>/webhook-secret`,
`brand/<slug>/customerio-key`. Project secrets (manifest): `keycloak-operators-admin/*`.

## The pure packages

`commission` (NGR, fees, RevShare delta, override delta, close: the original's formulas and their
cases), `periods` (zone, period, ranges as `S/ReportingRange.scala`), `sealing` (AES-GCM, HMAC
digests), `tags` (Ed25519 JWS as 012's `Tag`), `postbacks` (macros), `csv` (the export), `network`
(walk, cycle).
