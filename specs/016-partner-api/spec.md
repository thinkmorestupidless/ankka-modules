# Feature Specification: partner-api — A Partner Reads One Brand Through a Published Contract

**Feature Branch**: `016-partner-api`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The brand project's `partner-api`: the read-only partner GraphQL kept
verbatim, over views the service builds from the brand's topics; partner tokens, grants, row-level
scoping, the cost ceiling, the quota and the audit of partner reads."

## Context

Today `casino/public-api` in the original is a TypeScript service (PostGraphile 5) serving one
hand-written GraphQL contract, `public-api-v1.graphql`, over a `public_api` schema of SQL views. The
views sit on `casino_read` tables that `substrate/casino-read-model`, a Scala projection in its own
process, fills from the twin event journal. A partner is a confidential Keycloak client in the
`eitheror` realm (`partner-<slug>`) whose token carries three hardcoded claims — `eitheror_actor_type:
partner`, `eitheror_scope_level`, `eitheror_scope_id` — and a five-minute lifetime. Isolation is row
level security in Postgres, stamped per transaction from those claims. A partner's grant — brand,
entitled identifying fields, the data processing agreement that permits them, the quota and the
cost ceiling — is a Helm value rendered into a file the service re-reads every minute. Every request
writes one audit line to Loki naming the partner, the operation and the opaque ids of the players
whose identifying fields were served, never a value. Opaque public ids are minted into a registry
that is never truncated, so a rebuild cannot break a partner's stored references. CI introspects the
running schema and fails on any drift from the committed contract. Decisions 0050 and 0051 in the
eitheror brain record why: the twin vocabulary is never published, views are the contract, and the
read model lives outside the substrate.

Two facts shape this spec more than any other. First, **no partner exists.** The original's spec
leaves the audience "deliberately unconstrained"; no client is seeded in any realm; the chart ships
`casinoPublicApi.enabled: false` and only the KinD overlay turns it on. The surface was built,
tested (forty of its own test suites) and exercised in pre-production, and never served a third
party. Second, **nothing in the Go or Playwright suites touches it**, so its recordings do not exist
yet (*Recordings*).

A partner and an affiliate are different things and this spec keeps them apart. A partner *reads* a
brand's data on request, under a grant the brand's operators made, about players by opaque id, with
identifying fields only under a recorded agreement. An affiliate network *receives* facts the brand
pushes (spec 012), about players by a pseudonym, never an identifying field, and never reads the
brand at all. A token carrying the affiliate role is refused here, as the original refuses the
`affiliate` scope level.

The rebuild keeps the contract byte for byte and the request pipeline's rules, and changes where
the data comes from (the brand's topics, spec 001 FR-011, instead of the journal), where the grant
lives (the service's own state, changed by an operator route, instead of a Helm value), and what
erasure means (a real one, ankka 042, instead of the marker the original kept for when the platform
could erase).

## Clarifications

### Session 2026-10-08

- Q: Are partner tokens ankka 040 machine tokens, or the realm's? → A: The `eitheror` realm's, as
  today: one confidential client per partner, client credentials, a five-minute token, verified
  through ankka 022 against the casino's realm (spec 001 FR-019, FR-021). A 040 machine is the
  platform's identity for a service elsewhere and is granted routes and topics by the platform's
  owners; a partner is the brand's customer, onboarded and cut off by the brand's operators, bound
  by a data processing agreement, and entitled field by field. That is a brand-level grant, not a
  platform one, and the realm model is the one the original proves. The service holds no realm
  administration credential (001 FR-021): the client is created in the realm by the operator, the
  secret delivered out of band, as today.
- Q: Does the contract change? → A: No. `public-api-v1.graphql` is served verbatim, drift fails the
  build, and the versioning rules the original documents (additive within a version; a breaking
  change is a new version served beside the old for six months) hold. Amounts stay decimal strings in
  major units with a currency, because that is the published contract; the views convert from the
  minor units every fact carries (001 FR-026 governs the inside, the contract governs the edge).
- Q: Where does the grant live? → A: In the service, one record per partner per brand, written by
  operator routes and read on every request. The original's Helm value was the first version of the
  same record; moving it makes a change take effect within one minute instead of a release, and
  audits who changed it. The realm client and the grant must both exist for a request to be served.
- Q: Do partners see identifying fields? → A: Only the fields their grant entitles, and only when
  the grant records a data processing agreement, its lawful basis and its date — the original's
  rule, default closed. Under ankka 042 those fields are `Personal` values in the service's own views
  under the player's subject; the service reads them with the brand's keyring, in its own project,
  so no 040 `decrypt` grant is involved. After erasure they read as erased and are absent from the
  response, which is the behaviour the original reserved a marker for.
- Q: Scope levels `company` and `holding`? → A: Dropped. A brand project holds its own brand's rows
  and refuses any other (001 FR-001, FR-005), so a token scoped above a brand would match nothing
  it is served from here. A partner that reads several brands holds one grant at each, under the
  same client id. No partner held either level in the original. Recorded under *Changes*.
- Q: Opaque ids or the brand's own ids? → A: Opaque, as today, for the reason decision 0051 gives:
  identifier hygiene, not data protection. A player id is not personal data (001 FR-024) but it is
  the key every brand service shares, and handing it out ties partners to it. The registry survives
  rebuilds, and the Spinvibe migration (spec 019) MUST carry it so an id a partner stored before the
  migration still resolves after.
- Q: Which quota fields are kept? → A: Requests per minute, which the original enforces. The grant's
  `maxConcurrentQueries` and `monthlyQueryBudget` are stored and never read by any code path, so they
  are not carried over (constitution I); a partner agreement that needs a monthly budget is a new
  requirement with a new spec entry. The per-request cost ceiling (depth, complexity, statement
  time) is enforced today and kept.
- Q: Who records the partner's reads? → A: This service, as one audit record per request on the
  brand's `platform.audit` topic (001 FR-011), carrying what the original's log line carries:
  partner, operation, the opaque ids whose identifying fields were served and the field *names*,
  timing and outcome — never a value. The shared audit's retention is the licence's (spec 018), and a
  subject access request is answered from it. The original's Loki line is replaced, not kept beside.
- Q: Sessions are kept 90 days on `casino.sessions` (001 FR-015) and the contract promises twelve
  months of history. → A: The service's session view keeps every row it ever projected; retention
  limits only what a *rebuild* can recover. A rebuild that starts after the topic's earliest retained
  message reports the gap in the view's status (ankka 043) and `readModelStatus` says degraded until
  an operator accepts it. Whether a brand with a partner raises the topic's retention is the brand
  manifest's decision; see the marker in *Assumptions*.
- Q: Language? → A: TypeScript, hosted beside the sidecar, with the reason: the contract tool
  derives cursor paging, filters, ordering and batching from the views and the CI drift check
  depends on introspecting it; a Scala rewrite would hand-roll all of that to produce the same
  bytes. Nothing in this service is atomic or moves money (constitution VI).

### Session 2026-10-08 (reconciliation 3)

- Q: When is this service built, and is it in the pilot? → A: Last, and no. No partner exists today;
  the pilot brand's manifest excludes `partner-api` (spec 001 FR-007), and the marker on whether any
  partner is contracted decides whether it is built at all. Spec 001 FR-020's partner class is
  corrected to say the token is the `eitheror` realm's and this service issues nothing (FR-015).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A partner authenticates and reads only its own brand (Priority: P1)

A partner holding a client id and secret for one brand exchanges them for a token and queries the
brand's players, transactions, bets, rounds, sessions and bonus awards. Every record returned
belongs to that brand. A record of any other brand is absent, not refused: a query by another
brand's identifier returns nothing and reveals nothing. A token that is missing, expired, signed
elsewhere, carrying the wrong actor type or the affiliate role, or belonging to a partner whose
grant is disabled, is refused and returns no data. The API offers no write of any kind.

**Why this priority**: Isolation is the precondition for the surface existing at all, and it is the
property the original tests hardest (`isolation`, `rls-default-deny`, `scope-levels`,
`scope-malformed`, `read-only`).

**Independent Test**: Seed two brands with players; with a token for one, query by the other's ids
and names; with each malformed token, call every root field; attempt a mutation.

**Acceptance Scenarios**:

- added `features/partner-api/access.feature`: a partner with a valid sign-in for a brand is returned only that brand's players
- added `features/partner-api/access.feature`: a partner asking for another brand's player by id is answered empty without learning whether the id exists
- added `features/partner-api/access.feature`: a request with a sign-in that cannot be trusted is refused and returns no data
- added `features/partner-api/access.feature`: a partner whose entitlement an operator disabled is refused within a minute, even with a sign-in issued before
- added `features/partner-api/access.feature`: any operation that is not a query is refused, because the partner contract has no write and no subscription
- added `features/partner-api/access.feature`: a partner with an entitlement but no realm client, or a realm client but no entitlement, is refused

---

### User Story 2 - A partner pages a collection while the brand keeps writing (Priority: P1)

A partner asks for sets — players registered in a range, a player's transactions, the bets in a
round — filters and orders them, and pages through more than fit in one answer while new records
keep arriving. Every matching record arrives exactly once. A page larger than the maximum is capped
and the maximum is discoverable. An empty match is an empty answer, not an error.

**Why this priority**: Collections are the reason a partner integrates; the original's `paging`,
`paging-concurrent`, `cursors`, `filters` and `collectionsQuery` suites are its parity tests.

**Independent Test**: Seed a brand with more records than one page; page to the end while a
writer adds records; check completeness and no duplicates; request a page over the maximum.

**Acceptance Scenarios**:

- added `features/partner-api/paging.feature`: paging to the end returns every matching record exactly once
- added `features/partner-api/paging.feature`: records written while a partner is part way through paging are neither served again nor skipped
- added `features/partner-api/paging.feature`: a query using a supported filter returns only matching records
- added `features/partner-api/paging.feature`: a page size above the maximum is capped at the maximum, which the partner contract states
- added `features/partner-api/paging.feature`: a page cursor that cannot be trusted is refused and nothing is leaked
- added `features/partner-api/paging.feature`: a query with no match is answered empty

---

### User Story 3 - A partner builds against a published, stable contract (Priority: P2)

A partner fetches the contract, generates a client, pins a version and builds against it. A release
that changes the published contract by accident fails before it reaches a partner; a deliberate
breaking change is a new version served beside the old for the stated period. An internal change
— a new field on a fact, a renamed column — leaves the contract byte-identical.

**Why this priority**: The drift check is what makes a derived schema safe to publish; it is the
original's `contract-snapshot` and `contract-stability` suites.

**Independent Test**: Fetch the contract; add an unselected column to a view's source and rebuild;
fetch again; compare.

**Acceptance Scenarios**:

- added `features/partner-api/contract.feature`: a partner with credentials fetches the partner contract complete enough to generate a working client
- added `features/partner-api/contract.feature`: a release that does not intend to change the partner contract serves an identical one or fails to build
- added `features/partner-api/contract.feature`: a breaking change ships as a new version with the prior one served for at least six months
- added `features/partner-api/contract.feature`: a new field on a fact the views read leaves the published partner contract unchanged

---

### User Story 4 - An operator onboards, entitles, limits and revokes a partner (Priority: P2)

An operator with the brand's role creates a partner's grant: brand, enabled, the identifying fields
the partner may read, the agreement that permits them, the requests per minute and the cost
ceiling. The operator sees the partner's volume, errors and latency, drives it past its quota and
sees it throttled with a retry signal while other partners are unaffected, withdraws a field and
sees it stop within a minute, and disables the grant and sees requests refused. Every change is
recorded with who made it.

**Why this priority**: Required before the surface carries more than one hand-held partner; it is
the original's User Story 4, with the Helm value replaced by a route.

**Independent Test**: Create a grant through the operator route; run a partner at full rate and
above it; withdraw a field; disable; check the audit.

**Acceptance Scenarios**:

- added `features/partner-api/entitlements.feature`: an operator with the brand's role creates a partner entitlement and the partner reads that brand only, recorded with the operator and the time
- added `features/partner-api/entitlements.feature`: an operator whose roles do not name the brand is refused a change to its partner entitlements
- added `features/partner-api/entitlements.feature`: a partner at its requests per minute has the next request refused with when to retry, and another partner is unaffected
- added `features/partner-api/entitlements.feature`: a query above the depth, complexity or time ceiling is refused or stopped before it burdens the store
- added `features/partner-api/entitlements.feature`: a partner entitled to a subset of identifying fields is served nothing outside it, with the partner contract unchanged
- added `features/partner-api/entitlements.feature`: a field withdrawn from an entitlement stops arriving within a minute with no release, credential or contract version
- added `features/partner-api/entitlements.feature`: an entitlement with identifying fields but no recorded agreement serves no identifying field
- added `features/partner-api/entitlements.feature`: an operator sees each partner's request volume, error rate and latency by partner

---

### User Story 5 - Every read is recorded, and an erased player stops appearing (Priority: P2)

Every request leaves one audit record naming the partner, the operation and, where identifying
fields were served, which players and which fields. When a player is erased, their identifying
fields vanish from every answer, including a page a partner is part way through, without breaking
paging; their amounts and ids remain. A subject access request can be answered: which partners read
this player's identifying data, and when.

**Why this priority**: The audit and the erasure are what a regulator asks for; the original's
`audit` and `no-pii-leak` suites cover the first and reserved a marker for the second.

**Independent Test**: Serve a player's email to a partner; erase the player; page again; list the
audit for the player.

**Acceptance Scenarios**:

- added `features/partner-api/audit.feature`: a request that served identifying fields leaves one audit record naming the partner, the operation, the players and the field names, and no value
- added `features/partner-api/audit.feature`: a request that served no identifying field still leaves an audit record saying so
- added `features/partner-api/audit.feature`: an erased player read by a partner has every identifying field absent, keeps their id, status and amounts, and a page in progress continues without gaps
- added `features/partner-api/audit.feature`: a subject access request lists every partner served an identifying field of the player, with the fields and the times
- added `features/partner-api/audit.feature`: no identifying value appears in a log, a trace or a metric of an error or a slow request

---

### User Story 6 - A partner can tell how fresh the data is (Priority: P3)

A partner reconciling money reads `readModelStatus` and sees how far the service's views lag the
brand's facts and whether they are degraded — rebuilding, catching up, or missing history a
rebuild could not recover. A degraded answer is provisional, and the contract says so.

**Why this priority**: The original's lag check failed in pre-production twice for measuring the
wrong thing; the rule must be written down.

**Independent Test**: Stop the view's consumer; query the status; restart; query again.

**Acceptance Scenarios**:

- added `features/partner-api/freshness.feature`: with the views caught up the lag is the time between the newest fact published and the newest applied, and degraded is false
- added `features/partner-api/freshness.feature`: a view rebuilding or with its consumer stopped reads as degraded with a growing lag
- added `features/partner-api/freshness.feature`: a quiet brand with no new facts reads a lag near zero, because idleness is not lag
- added `features/partner-api/freshness.feature`: a rebuild started after the earliest retained fact stays degraded until an operator records the gap as accepted

---

### Edge Cases

- **A player with no identifying field entitled** is still a player: id, status, verification
  status, country, currency and language are served.
- **A grant changed while a partner is mid-page** takes effect on the next page; cursors stay
  valid.
- **A brand retired while a partner holds a grant**: the grant is disabled with the brand and the
  partner is refused.
- **A replayed or corrected fact** (a re-published event after a restore, ankka 041) is applied by
  its id once; the view is not double-counted.
- **An amount in a currency with no minor-unit exponent known to the view** is refused at
  projection time and reported, never served rounded.
- **Two grants for one client id at one brand** cannot exist; the second write is refused.
- **A token for a realm other than `eitheror`** is refused as signed elsewhere.
- **An operator withdraws the agreement reference** from a grant: identifying fields stop within a
  minute, as for a withdrawn field.

## Requirements *(mandatory)*

### Functional Requirements

**The contract**

- **FR-001**: The service MUST serve `public-api-v1.graphql` from the original, byte for byte in
  every type, field, argument, nullability, enum member and default, at a route that returns the
  contract itself and at the query route, over encrypted transport only, at a hostname of its own
  (001 FR-006). *Established by the original's contract file and `contract-snapshot` suite.*
- **FR-002**: The contract MUST offer no mutation and no subscription. *Original FR-002,
  `read-only` suite.*
- **FR-003**: A release MUST fail when the schema it would serve differs from the published contract
  in anything but descriptions. *Original FR-024.*
- **FR-004**: A breaking change MUST be a new version served beside the prior one for at least six
  months; an additive change MUST NOT change the version. *Original FR-026 and the versioning
  document.*
- **FR-005**: Every amount MUST be served as a decimal string in major units with its currency,
  converted from the fact's minor units by the currency's exponent; every timestamp MUST carry an
  explicit offset. *Original FR-006, FR-007, `money` suite.*
- **FR-006**: A collection MUST page by cursor, MUST cap a page at the published maximum, MUST stay
  correct while records are written, and MUST return an empty answer for no match. *Original
  FR-004, FR-005, FR-008.*
- **FR-007**: A cursor MUST be refused when malformed, when it was issued to another partner, or
  when it is older than the installation's cursor lifetime, and the refusal MUST leak nothing.
  *Original edge case "stale paging cursor", `cursors` suite.*

**Views**

- **FR-008**: The service MUST build its own views from the brand's topics and read no other
  service's store: players from `casino.players`; sessions from `casino.sessions`; rounds from
  `casino.rounds`; transactions and bets from `money.transactions` and `casino.withdrawals`; bonus
  awards from `casino.rewards` (001 FR-011). A fact MUST be applied by its id once.
  *Constitution IV; decision 0051 ("views are the contract").*
- **FR-009**: Each view MUST map the facts' status words onto the contract's enums exactly as the
  original's views do, and MUST serve an undeclared word as null rather than coerce it: player status
  (active, suspended, closed, self-excluded), verification status (unverified, pending, verified,
  rejected), transaction type (deposit; withdrawal with its return and reversal; bet; win; the four
  bonus movements as bonus; rollback; loss; adjustment; provider credit), funding (main, bonus,
  split), session status (open; completed, expired and force-closed as closed, with the close reason
  kept), bonus status (the eight states of spec 011's grant). *Original `V8` views.*
- **FR-010**: The service MUST mint one opaque id per player, transaction, bet, round, session and
  bonus award on first sight, keep it in a registry that is never truncated and survives a rebuild,
  and serve only opaque ids: never a brand's own id, never a key. *Decision 0051; original
  `PublicIdMinter`.*
- **FR-011**: Every identifying field a view holds (email, given and family names, date of birth,
  telephone, address line, city, state or province, post code) MUST be a `Personal` value under the
  player's subject (001 FR-023, ankka 042), read with the brand's keyring in the service's own
  project. *Constitution V.*
- **FR-012**: A view's row for an erased player MUST remain, with its opaque id, status and
  amounts, and every identifying field MUST read as erased and be absent from any answer; a cursor
  spanning the row MUST stay valid. *Original FR-014's marker, closed by ankka 042.*
- **FR-013**: `readModelStatus` MUST report lag as the time between the newest fact the brand's
  topics hold and the newest the slowest view applied, never the age of the last write; degraded
  MUST be true while any view rebuilds, its consumer is stopped, or a rebuild began after the
  topic's earliest retained message and no operator has recorded acceptance (ankka 043's gap).
  *Original `V10` and the 2026-09-23 incident.*
- **FR-014**: A session view row, once projected, MUST be kept for as long as the service runs;
  `casino.sessions`' 90-day retention (001 FR-015) limits only what a rebuild recovers. *Decision
  here, Clarifications.*

**Partners and tokens**

- **FR-015**: A partner MUST be a confidential client of the `eitheror` realm whose token carries
  the partner actor type and the brand it is scoped to, with a lifetime of five minutes; the service
  MUST verify it through ankka 022 (001 FR-021) and MUST refuse a token that is absent, expired,
  signed by another issuer, of another actor type, or carrying the affiliate role. *Original
  `partner-grant.md` §1, `partnerToken` suite.*
- **FR-016**: The service MUST hold no realm administration credential; creating, rotating and
  disabling a partner's client is the operator's, in the realm, with the secret delivered out of
  band. *001 FR-021.*
- **FR-017**: A request MUST be served only when the token's partner has a grant at this brand that
  is enabled; a token with no grant, or a grant with no client, MUST be refused. *Original §3 step 6.*
- **FR-018**: Isolation MUST be enforced on the data: every query MUST run under the token's brand
  and partner, stamped per request before any read, so a query that omits a scope returns nothing
  rather than another brand's rows. *Original FR-017, `rls-default-deny` suite.*
- **FR-019**: A response MUST NOT disclose the existence of a record outside the partner's brand by
  error, status, count or timing. *Original FR-019.*

**Grants**

- **FR-020**: A grant MUST be one record per partner per brand holding: enabled; the identifying
  fields entitled; the data processing agreement reference, lawful basis and date; requests per
  minute; and the cost ceiling (maximum depth, maximum complexity, statement time). *Original §2,
  less the two unenforced quota fields (Clarifications).*
- **FR-021**: Identifying fields MUST be served only when the grant entitles the field *and*
  records all three agreement fields; otherwise the entitled set MUST be empty. Default closed.
  *Original FR-021g, `lawful-basis` suite.*
- **FR-022**: A field the grant does not entitle MUST be absent from the answer, never null or
  masked. *Original FR-021c, `identifyingFieldOmission` suite.*
- **FR-023**: A grant MUST be created, changed and disabled through operator routes admitted to an
  operator whose realm roles name the brand (001 FR-020), MUST take effect within one minute, and
  every change MUST be an audit record naming the operator, the partner and the fields changed.
  *Original FR-021d, SC-012, made a route (Clarifications).*
- **FR-024**: Disabling a grant MUST refuse the partner within one minute, whatever the token's
  remaining lifetime. *Original FR-020, SC-007, tightened from five minutes to one because the grant
  is read per request.*

**Protection**

- **FR-025**: A partner above its requests per minute MUST be refused with a retry signal that says
  when, distinguishable from every other refusal, and MUST NOT affect another partner's latency.
  *Original FR-028, FR-029, `quota` suite.*
- **FR-026**: A query above the grant's depth or complexity MUST be refused before it runs; one
  above the statement time MUST be stopped. *Original FR-027, `cost-limits` suite.*
- **FR-027**: The installation's gateway MUST hold a per-address floor in front of the service
  (001 FR-034), looser than any grant's rate, so partners sharing an egress do not throttle each
  other on it. *Original chart `rateLimit.publicApi`.*
- **FR-028**: Request volume, error rate and latency MUST be attributable per partner in the
  installation's telemetry (001 FR-034). *Original FR-030.*

**Audit**

- **FR-029**: Every request MUST produce one audit record on the brand's `platform.audit` topic
  (001 FR-011) carrying the partner, the brand, the operation, the opaque ids of players whose
  identifying fields were served with the field names, the duration, the time and the outcome —
  and never a field's value. *Original §4, `audit` suite; the Loki line replaced (Clarifications).*
- **FR-030**: A subject access request MUST be answerable from the shared audit: every partner
  served an identifying field of a given player, with fields and times. *Original SC-011.*
- **FR-031**: No identifying value MUST appear in the service's logs, traces, metrics or error
  messages. *Original FR-021f, `no-pii-leak` suite; ankka 042 FR-029 gives the mechanism.*

**Test doubles and recordings**

- **FR-032**: The service's test kit MUST seed two brands' worth of facts, a partner per brand and
  the malformed-token set, so every scenario above runs with no realm and no partner.
- **FR-033**: The original's forty test suites MUST be re-expressed as this service's own, one for
  one, before the port, and MUST pass against the rebuilt service; the ten listed under *Recordings*
  MUST be recorded against the original with a seeded partner first (001 FR-029, FR-030).

### Key Entities

- **Partner**: an organisation with a client in the `eitheror` realm; known here by its client id;
  holds one grant per brand it reads.
- **Grant**: what one partner may read at one brand: enabled, entitled identifying fields, the
  agreement that permits them, requests per minute, the cost ceiling; changed by operators,
  audited.
- **Opaque id**: the one identifier a partner sees for a player, transaction, bet, round, session or
  bonus award; minted once, never regenerated.
- **View**: the service's own projection of one topic, keyed by opaque id, with identifying fields
  as `Personal` values and a status per view.
- **Audit record**: one per request, naming the partner, the operation, the players and field
  names served, never a value.
- **Contract version**: the published description, immutable once published, served beside its
  successor for six months.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero cross-brand disclosure: every attempt to read outside the partner's brand returns
  nothing, verified on every release.
- **SC-002**: Across every collection at the maximum page size, 95% of requests answer within one
  second and 99% within three, on a brand holding twelve months of production-scale history.
- **SC-003**: A fact is visible through the contract within 30 seconds for 95% of facts, and the
  observed lag is visible to operators at all times.
- **SC-004**: No partner experiences an unannounced change to a contract version it pinned across a
  release cycle; the drift check runs on every build.
- **SC-005**: A partner at its full rate issuing its most expensive permitted query causes no
  measurable change to another partner's response times.
- **SC-006**: Disabling a grant, or withdrawing a field, stops the partner or the field within one
  minute with no release and no new credential.
- **SC-007**: Every request is attributable to a named partner in the shared audit for the period
  the licence requires, and a player's access history is producible within one working day.
- **SC-008**: Zero identifying values in diagnostics, verified by an automated check over logs,
  traces and metrics on every release.
- **SC-009**: An erased player's identifying fields are absent from every answer within ankka 042's
  bound, while every id and amount about them remains.
- **SC-010**: A rebuilt set of views, once caught up, answers every contract query identically to
  before the rebuild, and every opaque id resolves to the same record.
- **SC-011**: Onboarding a partner — client created, grant written, verified — takes under one hour
  of operator time and leaves a complete audit.

## Changes from the original

- **Scope levels `company` and `holding` are dropped.** A brand project serves its own brand only
  (constitution III); a partner spanning brands holds a grant at each. No partner held either level.
- **The grant moves from a Helm value to the service's state**, written by operator routes and
  audited; a change takes effect within one minute, not on a release. The chart's onboarding checklist
  becomes: create the client in the realm, deliver the secret, write the grant.
- **`maxConcurrentQueries` and `monthlyQueryBudget` are not carried over**: stored in the original's
  grant and enforced by nothing.
- **Revocation tightens from five minutes to one**, because the grant is read on every request; the
  token's five-minute lifetime no longer bounds it.
- **Erasure is real.** The original nulled identifying columns in place and kept a marker for a
  platform erasure that did not exist; here the fields are `Personal` values and erasure is ankka 042's.
- **The audit record goes to the shared audit**, not a Loki line; retention is the licence's, held in
  one place (spec 018).
- **The views read the brand's topics**, not the journal; the Scala projection process
  (`casino-read-model`), its placeholder-player migration and its journal-head lag function are
  gone. Freshness is the views' own status.
- **The per-address rate floor moves from the chart's nginx values to the installation's gateway.**
- **Nothing a partner sees changes**: the contract, the opaque ids, the enums, the money and time
  formats, the paging and the error shapes are the original's.

## Recordings

No Go end-to-end scenario and no Playwright test touches the original's public API; the chart ships
it disabled and only the KinD overlay enables it. Its forty `node:test` suites under
`casino/public-api/test/` are the oracle for every requirement above and are re-expressed one for one
(FR-033). Before the port, these MUST be written as Go scenarios against the original with a seeded
partner client and grant, and recorded under `tests/port/partner-api/` (001 FR-029, FR-030):

1. a partner reads its own brand's players, transactions, bets, rounds, sessions and bonus awards;
2. a partner asks for another brand's player by id and receives nothing;
3. each malformed token is refused;
4. a page larger than the maximum is capped;
5. a full collection is paged while a writer adds records;
6. a partner with an entitled field and an agreement sees the field; without the agreement, not;
7. a withdrawn field stops arriving;
8. a partner over its rate is refused with a retry signal;
9. a query over the cost ceiling is refused;
10. the contract route serves the published file.

The contract file itself, `specs/211-casino-public-api/contracts/public-api-v1.graphql`, is copied
into this repository unchanged and is the parity test for FR-001.

## Assumptions

- The first partner has not been named; the surface is built wide, as the original's spec decided,
  and narrowed per partner by grant. This service is built last of the brand's (its number in the
  order says so), is excluded from the pilot brand's manifest (spec 001 FR-007), and is built at all
  only when a partner is contracted — the first marker below is the decision. [NEEDS CLARIFICATION: is any partner contracted or in
  discussion for Spinvibe today? If none, this service is built last in the brand (it is 016 in the
  order) and the pilot brand ships without it enabled, as the original ships.]
- A partner reads one brand per grant. [NEEDS CLARIFICATION: does any planned partner need to read
  across several brands under one token, which the dropped `company`/`holding` scope levels would
  have served? If so, the answer is a grant per brand under one client id, and the partner queries
  each brand's hostname — confirm that is acceptable.]
- `casino.sessions` is kept 90 days (001 FR-015) while the contract promises twelve months of
  history; the view keeps its rows and a rebuild reports its gap. [NEEDS CLARIFICATION: should a
  brand with a partner raise `casino.sessions` retention to twelve months in its manifest, or accept
  that a rebuild recovers 90 days of sessions and `readModelStatus` says so?]
- Identifying fields are offered under a recorded agreement because the original offers them; the
  default is closed, so a brand that prefers pseudonymous partners entitles nothing.
- The contract's amounts are major-unit decimals at the edge; constitution II governs everything
  inside. A currency whose exponent the view does not know is a projection error, not a rounding.
- The opaque-id registry is imported by the Spinvibe migration (spec 019) so ids survive it; with
  no partner today this costs nothing and protects against a partner onboarded before the cutover.

## Dependencies

- Spec 001: topic contract (FR-011, FR-015), route classes and realms (FR-019–FR-021), personal
  data (FR-023–FR-025), the gateway (FR-034), the port method (FR-029–FR-033).
- Spec 008: `casino.players` families with the identifying fields and status words the player view
  maps; `casino.sessions` with the close reason.
- Spec 009: the verification status families the player view maps.
- Spec 002, 007, 011, 003: `money.transactions`, `casino.withdrawals`, `casino.rewards`,
  `casino.rounds` families and their status words.
- Spec 018: the shared audit's retention and the subject-access query over it.
- Spec 019: the opaque-id registry imported at migration.
- Spec 012: the affiliate/partner distinction; a token with the affiliate role is refused here.
- ankka 022 (token verification), 024/031 (views), 040 (none needed: in-project), 042 (`Personal`
  fields, erasure, the telemetry rule), 043 (retention and the rebuild gap in view status).

## Out of scope

- Any write path for partners.
- A second contract version; the mechanism to serve two is built with the first breaking change,
  as the original's versioning document decides.
- Partner self-service (sign-up, secret rotation by the partner); onboarding stays an operator's.
- Serving a partner across installations: a partner of two installations holds a client in each.
- The affiliate network's reads (spec 012/013) and the operator console's own read models (spec
  015).
