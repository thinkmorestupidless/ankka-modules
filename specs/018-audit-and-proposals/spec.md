# Feature Specification: Audit — One Record of What Every Brand Did, Held Outside the Brands

**Feature Branch**: `018-audit-and-proposals`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The shared `reasoner` project's `audit` service: the record every money command, refusal, operator action, agent tool call and platform action produces in a brand, read from every brand's `platform.audit` topic under a 040 grant, keyed by pseudonym, immutable, kept for the regulator's window, queryable per brand and across brands, exportable. And `proposals`: what the original's reasoner actually produces and whether the casino uses it."

## Context

Today the original has two things called audit, and neither is the one this spec describes.

The first is the substrate's audit projection (`substrate/substrate-audit`, decision 0009). It reads
the twin event log and writes one row per twin event into `audit_records`: a record id derived from
the persistence id and sequence number, the twin event's generic type (`twin.property.set.v1`,
`twin.edge.added.v1`, …), the twin's pseudonym and — because the actor is "pending action-primitive
OIDC thread-through" — the same pseudonym again as the actor, empty before and after summaries, no
correlation or causation id, and a visibility tier read from the deployment's configuration. Pseudonyms
come from `pseudonym_registry`, a table that maps a real id to a random UUID on first sight. The query
is `POST /internal/audit/query` (twin, event type, since, until, limit, caller tier) and the CLI's
`epipe audit query`; the one end-to-end scenario that reads it (`TestScenario2_ActionProposeExecute`)
asserts that a record exists with the right tier. It says *that* a twin changed, never what a person
did.

The second is the domain's own trail, written beside every action: `casino-account-events-<brand>`
twins (player, event type, change type, previous value, new value, actor, reason) that the backoffice
activity tab and the player's own activity download read, and a `HAS_AUDIT_EVENT` edge the wallet adds
from the player to each transaction. These carry the real ids and, for a profile change, the previous
and new values in the clear. They are the records an operator actually uses, and they live in the brand.

The plan put `audit` in the shared `reasoner` project with a `pseudonym-registry`, an `audit-records`
view, `audit-query`, and the dashboard and widget twins of decision 0010, and listed `proposals`
beside it. Spec 001 keeps audit shared (FR-016: each brand grants its `platform.audit` topic to
`reasoner`/`audit`; FR-018: `reasoner` holds the player id and no other identifying field) and says
spec 018 may defer proposals indefinitely. Every brand spec written since (002, 004, 006, 007, 008,
009, 010, 011) requires an audit record on `platform.audit` for its money commands, refusals and
operator actions, naming the actor and the reason, and none of them says what a record is. This spec
says what it is, who writes it, and what the shared service does with it.

What "proposals" means in the original, read from the code:

- The `proposals` service the plan describes is the **lab-grown-food proposal pipeline**
  (`starter-packs/lab-grown-food`, `tests/lgf-agent`, the `index_proposal_sections` capability):
  the New Form Foods PoC's research-proposal agent — clarify, decompose, research, synthesise, draft,
  review, finalise with human sign-off. Nothing in the casino or payments calls it, reads it, or
  shares a twin with it.
- The substrate's **action propose/execute** gate (`POST /internal/actions/propose` returning a
  `predicted_effect`, then `execute`): the Observe → Suggest → Act step at the level of one twin
  mutation. No casino, wallet, merchant or affiliate route calls `propose`; only the end-to-end
  scenario 2 does.
- The **mimir authoring loop**'s `MimirProposal` (decision 0016; `shared/proposal-schemas`): an
  atomic proposal of TL artefacts for human approval. It proposes TL, which spec 001 leaves open as a
  sold feature, and the casino's services are code.

None of the three is a casino capability in use. This spec is audit, and records proposals as not
carried over.

## Clarifications

### Session 2026-10-08

- Q: Is this spec audit and proposals, or audit alone? → A: Audit alone. The plan's `proposals` is
  the lab-grown-food research agent, another product's; the substrate's propose/execute gate has no
  casino caller; mimir's TL proposals have nothing to propose into a service that is code. All three
  are listed under *Changes from the original* as not carried over, and the `reasoner` project holds
  `audit` only. Where an agent's action needs a person's approval, ankka 029's approval is the
  mechanism (spec 017); where a plan of actions needs versioning, ankka 036 blueprints are the
  extension point. Neither is specified here.
- Q: What is an audit record? → A: One statement that an actor did, or was refused, one action on one
  subject in one brand, written by the service that owns the action at the moment it is done: actor
  (kind and id), subject (kind and id), brand, service, action, outcome (done, or refused with a
  reason code), the changes it made as field names with before and after values, the correlation id
  of the request or workflow that caused it, the id of the fact or command that caused it, when it
  occurred, and a visibility class. Every brand service writes this one shape to the brand's
  `platform.audit` topic; this spec owns the shape and the SDK helper that writes it.
- Q: Does a record carry personal values? → A: Never. A change to a `Personal` field (spec 001
  FR-023) records the field's name and that it changed; before and after are absent. Reasons and
  notes an operator writes are `Personal` in the brand's own store (specs 007, 008, 009) and reach
  the record as a reason *code* and a reference, not the text. The helper's type makes a `Personal`
  value unrepresentable in a record, so the rule is enforced where the record is built, not reviewed.
- Q: Who does the shared audit know a player as? → A: A pseudonym. The shared service pseudonymises
  every subject and actor id on ingest through its registry — one random pseudonym per (brand, kind,
  real id), issued on first sight and held for the record's life — and stores only pseudonyms in its
  records, so no query, export or view of the shared audit ever shows a real id. The registry is the
  one place in `reasoner` that holds a player id, which is what spec 001 FR-018 allows and all it
  allows; the glossary's "shared audit names a player by a pseudonym and never by a real id" is true
  of every record and every answer. Re-identification is a separate, role-restricted, audited action
  (User Story 4).
- Q: Why a registry rather than spec 012's keyed digest? → A: Because compliance has to go both ways.
  A keyed digest answers "which records are this player's" but not "who is this record about", and a
  regulator's question is usually the second. The original's registry already does both; it is kept,
  with its access narrowed.
- Q: Which actions are audited — every bet? → A: Yes. The glossary's `audit record` is "written by
  every part for every money command and refusal", and the brand specs already require it. A bet's
  record is small, and a gambling regulator's window is for the whole transaction log. The cost is
  stated in Assumptions; the ingest rate is a success criterion. Reads are not audited, with one
  exception: a read that shows a person's documents or payment details (spec 009's view links, spec
  013's payout export) is an action and is recorded as one.
- Q: Who reads the brand's own records, with real ids? → A: The brand. `platform.audit` is a topic of
  the brand's project and any service in the project may consume it; the operator's activity tab and
  the player's activity download are brand views with real ids (spec 015 `reporting`, from the records
  and the facts), not queries of the shared audit. The shared audit is for compliance across brands
  and for the installation's record outside any brand.
- Q: What does the three-tier visibility of decision 0009 become? → A: Two classes, because one
  organisation now runs both the platform and the brands. A record is `brand` (visible to the brand's
  compliance role and to the installation's) or `installation` (platform actions on the brand —
  deploys, grants, restores, erasures — visible to the brand's compliance role too, as 0009 chose:
  a brand sees who touched it). There is no record a brand's compliance role may not see about its
  own brand.
- Q: Where do platform actions come from? → A: From ankka's control plane, which records every deploy,
  rollback, grant, acceptance, revocation, restore, switch and erasure request on the project (ankka
  040, 041, 042). The shared audit reads each brand project's history from the control plane and
  writes it as `installation` records, so a brand's operator sees a deploy of its wallet beside the
  adjustments made after it.
- Q: How long, and where? → A: The shared audit's store is the keeper, immutable, with retention per
  installation (default seven years, decision 0009) and per brand where a licence requires longer.
  The topic is transport: a brand declares `platform.audit` at the installation's longest retention
  (spec 001), but a rebuild of the shared store from the topic is bounded by that retention and is
  not the recovery path; ankka 041's backups of the audit service's database are.
- Q: What about dashboards? → A: The plan's dashboard and widget twins (decision 0010) are not
  carried over: the generic dashboards-as-twins rendering is blocked in the original (scenario 6 is
  skipped, its pod crashes) and no operator uses it. The backoffice KPI routes in
  `DashboardRoutes.scala` (kpis, trend, history, geo, rankings, activity, bonus summary, payment
  health) are the brand's reporting and belong to spec 015.

### Session 2026-10-08 (reconciliation 3)

- Q: Who owns the brand's per-player activity views? → A: `players` (spec 008 FR-034, FR-040), as
  spec 015 says; this spec had named 015. FR-025 and Dependencies are corrected, and spec 001 names
  `players` and `reporting` as the in-project readers of `platform.audit`.
- Q: How does the glossary's `shared audit` admit the registry? → A: With one sentence added at the
  bdd pass, phrased in Dependencies; the term is otherwise true as written.

### Session 2026-10-12 (from spec 004)

- Q: Where do a shared project's audit records go? (raised by spec 004, open) → A: Not yet settled.
  `merchant` (spec 004 FR-034) writes `audit_record` on a `platform.audit` topic of the `payments`
  project, added to the contract's layout and the shared manifest. FR-016 of spec 001 names only the
  brands' grants of their `platform.audit` to `reasoner`/`audit`; how `audit` reads `payments`' records
  (a grant between two shared projects, or another path) is this spec's to settle when it is planned.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every action in a brand leaves one record (Priority: P1)

An operator adjusts a player's balance with a reason; the wallet refuses a bet that exceeds the real
balance; a player sets a deposit limit; the support agent calls a tool that suspends an account; the
merchant hands a captured deposit to the wallet. Each of these is one audit record, written by the
service that owns the action, in one shape, on the brand's `platform.audit` topic, as the action is
done — never later, never by another service.

**Why this priority**: Without the record there is nothing for the shared audit to hold; every brand
spec depends on this shape existing.

**Independent Test**: Drive one action of each kind against a brand and read `platform.audit`; one
record each, in the shape, with the actor, the outcome and the changes.

**Acceptance Scenarios**:

- added `features/audit/records.feature`: an operator's adjustment with a reason code is one record with the operator as actor, the player as subject, the wallet as the part and the balance's before and after
- added `features/audit/records.feature`: a bet refused for insufficient funds is one record with outcome refused, the reason code, the bet's key as causation and no change
- added `features/audit/records.feature`: a player setting a deposit limit is one record with the player as actor and subject and the limit's before and after
- added `features/audit/records.feature`: a suspension by the support agent's tool is one record with actor kind agent, the approver, and the suspension's before and after
- added `features/audit/records.feature`: a captured deposit handed to the wallet by the cashier is one record with actor kind service, the payment id as correlation, and the balance's before and after
- added `features/audit/records.feature`: an action repeated under the same key writes no second record
- added `features/audit/records.feature`: a change to the email address names the field and carries no before or after value

---

### User Story 2 - The shared audit holds every brand's records and no real id (Priority: P1)

The shared `audit` service consumes each brand's `platform.audit` under the brand's grant, replaces
every subject and actor id with a pseudonym from its registry, and appends the record to its store.
A query of the store, an export, a log line — nothing the shared audit emits carries a real id.

**Why this priority**: This is the cross-brand record the regulator and the group's compliance need,
and the one copy that outlives a brand.

**Independent Test**: Run two brands, drive actions in each, read the shared store: every record
present once, every id a pseudonym, the same player the same pseudonym across their records, a
different pseudonym at the other brand.

**Acceptance Scenarios**:

- added `features/audit/shared.feature`: a record written in a brand that allows the shared audit appears in the shared store with pseudonyms in place of its ids and every other field unchanged
- added `features/audit/shared.feature`: a player's records at one brand all name the same pseudonym
- added `features/audit/shared.feature`: the same person at two brands has differing pseudonyms and nothing in the store joins them
- added `features/audit/shared.feature`: a record already in the store arriving again leaves the store unchanged
- added `features/audit/shared.feature`: nothing the shared audit answers, writes or logs holds a real id, which only the registry holds
- added `features/audit/shared.feature`: a brand that revokes what it allowed the shared audit stops the store at the last record received and shows the gap on the brand's status

---

### User Story 3 - Compliance asks what happened, per brand and across brands (Priority: P1)

A brand's compliance officer asks for every action on one player in a period; the group's compliance
officer asks for every operator adjustment above an amount across every brand this quarter; a
regulator asks for the brand's record for a month as a file. Each answer comes from the shared store,
in pseudonyms, and is itself recorded.

**Why this priority**: The query is why the record exists.

**Independent Test**: Load records for two brands, run each query as each role, check the answers,
then read the audit of the queries.

**Acceptance Scenarios**:

- added `features/audit/queries.feature`: a query by a brand role for a pseudonym and a period lists every matching record of that brand in order of occurrence and none of another brand
- added `features/audit/queries.feature`: a query by the installation's role across brands lists records from every brand, each naming its brand
- added `features/audit/queries.feature`: a query by a brand role naming another brand is refused
- added `features/audit/queries.feature`: an export of a brand's records for a period is a file in the regulator's format, in pseudonyms, and is itself a record
- added `features/audit/queries.feature`: a query or an export is itself recorded with its actor, filters, range and count, and that record is not queried about recursively
- added `features/audit/queries.feature`: a query for a period older than the brand's retention answers what is retained and says from when

---

### User Story 4 - A pseudonym is re-identified on demand, and that is recorded (Priority: P2)

A regulator names a pseudonym from an export and asks who it is. A compliance officer with the
re-identification role asks the registry, states the reason, and is told the brand, the kind and the
real id. The question is itself a record visible to the brand.

**Why this priority**: Without it the pseudonymised record cannot answer a regulator's second
question; with it unrestricted, pseudonymisation is theatre.

**Independent Test**: Re-identify as each role; read the record of the attempt.

**Acceptance Scenarios**:

- added `features/audit/re-identification.feature`: a compliance officer with the re-identification role is told who a pseudonym is, and the re-identification is recorded
- added `features/audit/re-identification.feature`: a compliance officer without the re-identification role is refused, and the refusal is recorded
- added `features/audit/re-identification.feature`: a real id asked for its pseudonym at a brand by the re-identification role is answered or issued, and the lookup is recorded
- added `features/audit/re-identification.feature`: a brand operator reading the activity tab sees real ids with no re-identification needed inside the brand

---

### User Story 5 - Platform actions on a brand are in the brand's record (Priority: P2)

The installation deploys a new wallet version to a brand, grants the brand's topic to the programme,
restores the brand's database to a point in time, or applies an erasure. Each appears in the shared
audit as an `installation` record of that brand, visible to the brand's compliance role, so the
brand's story is one story.

**Why this priority**: Decision 0009 chose transparency: a brand sees who touched it. A restore that
rewinds balances must appear beside the adjustments that followed it.

**Independent Test**: Deploy, grant, restore and erase against a test brand; read the brand's records
in the shared store.

**Acceptance Scenarios**:

- added `features/audit/installation.feature`: a deployment, rollback or switch to a restored database is an installation record naming the actor, the part, the version or restore point, and when
- added `features/audit/installation.feature`: what a brand allows a shared part, made, accepted, declined, withdrawn or revoked, is a record naming the principal, the target and the change
- added `features/audit/installation.feature`: an erasure request filed, held, applied or completed is a record naming the subject's pseudonym, the state and when, and the record survives the erasure
- added `features/audit/installation.feature`: a brand compliance officer sees installation records beside brand records in one order of occurrence

---

### User Story 6 - The record survives everything (Priority: P2)

Records are immutable, kept for the installation's window or the brand's longer one, backed up,
unaffected by a player's erasure, and rebuildable only from backup, not from the topic.

**Why this priority**: An audit that can be edited or lost is not one.

**Independent Test**: Attempt to change and delete; erase a player; restore the audit service's
database; verify.

**Acceptance Scenarios**:

- added `features/audit/durability.feature`: no role can change or delete a record in the shared store, because no such action exists
- added `features/audit/durability.feature`: an erased player's records are present and unchanged under the pseudonym, and the registry's row for the id is unchanged
- added `features/audit/durability.feature`: a brand's retention raised to ten years keeps its records ten years while another brand's keep the installation's seven
- added `features/audit/durability.feature`: a brand's retention cannot be lowered below the installation's
- added `features/audit/durability.feature`: a restore of the shared audit's database re-reads the records after the restore point from each brand's facts where they still reach, and names any brand whose facts no longer do

---

### Edge Cases

- **A record arrives out of order.** Records are stored by occurrence time and record id; a query
  orders by occurrence, not arrival. Nothing depends on topic order across services.
- **A service's clock is wrong.** The record carries the service's occurrence time and the broker's
  receipt time; a difference above a bound is reported in the brand's status, and the occurrence time
  stands.
- **A record is malformed or carries a value where a change to a personal field should carry none.**
  It is refused at the writer: the helper cannot build it. A malformed record on the topic (a foreign
  producer) is stored in a quarantine with its raw bytes and reported; it is never dropped silently.
- **The registry is unavailable.** Ingest pauses; nothing is written without a pseudonym; the lag is
  reported. Records are not lost: they remain on the topic within its retention.
- **Two brands' topics carry the same record id.** Impossible by construction: the record id derives
  from the brand, service, entity and command key. A collision is reported as an error, never
  overwritten.
- **An actor is a service in a shared project** (`payments`/`merchant`, `reasoner`/`audit` itself).
  It is pseudonymised like any actor; the pseudonym is per brand, and a service's identity string is
  the "real id".
- **The regulator's export format changes.** The format is a setting per brand naming one of the
  formats the service offers; the records are unchanged.
- **A query would return millions of rows.** Paged with a cursor; an export runs as a job with a
  status and a file to fetch, recorded once at request and once at completion.

## Requirements *(mandatory)*

### Functional Requirements

**The record and who writes it**

- **FR-001**: An audit record MUST carry: a record id; the brand; the service; the action name; the
  actor as a kind (`player`, `operator`, `agent`, `service`, `platform`) and an id, with the
  approver's id where an agent's action was approved (ankka 029); the subject as a kind (`player`,
  `operator`, `affiliate`, `merchant`, `brand`, `service`) and an id; the outcome, `done` or
  `refused` with a reason code; the changes as a list of (field, before, after); the correlation id
  of the request or workflow; the causation id of the command or fact that led to it; the time it
  occurred; and the class, `brand` or `installation`.
- **FR-002**: The record id MUST derive from the brand, the service, the subject and the command's
  idempotency key, so that a repeated command writes no second record and a record is the same
  whichever instance wrote it.
- **FR-003**: A change to a `Personal` field (spec 001 FR-023) MUST record the field's name and no
  value; a reason or note an operator wrote MUST reach the record as a reason code and a reference
  to the brand's own store, never as text. The helper that builds a record MUST make a `Personal`
  value unrepresentable in it.
- **FR-004**: Every brand service MUST write a record for every money command and its refusal, every
  action an operator takes, every action a player takes on their own account (registration, limits,
  exclusion, consent, closure, withdrawal request and cancellation), every tool call the support
  agent makes, every action a shared service takes in the brand over a grant, and every read that
  shows a person's documents or payment details. Reads of anything else MUST NOT be recorded.
- **FR-005**: A record MUST be written by the service that owns the action, at the moment the action
  is done or refused, to the brand's `platform.audit` topic (spec 001 FR-011), keyed by the subject's
  id so a subject's records stay in order.
- **FR-006**: The record's shape MUST be one message family on `platform.audit`, versioned by addition
  only (spec 001 FR-013); a field this spec does not name MUST NOT be added by a service.

**The shared audit**

- **FR-007**: The shared `audit` service MUST consume every brand's `platform.audit` topic under the
  brand's grant (spec 001 FR-016; ankka 040) with a consumer of its own per brand, and MUST report per
  brand the time of the last record received and the lag.
- **FR-008**: On ingest the service MUST replace every subject id and actor id with a pseudonym from
  its registry and MUST store nothing else that identifies a person; the stored record MUST otherwise
  equal the record written.
- **FR-009**: The registry MUST issue one random pseudonym per (brand, kind, real id) on first sight
  and keep it for the record's life; the same real id at another brand MUST receive another pseudonym.
  The registry MUST be the only store in the `reasoner` project that holds a real id (spec 001
  FR-018), and MUST be readable only by the re-identification route (FR-017, FR-018).
- **FR-010**: Storing a record MUST be idempotent by record id; a second arrival MUST change nothing.
- **FR-011**: The store MUST be append-only: no route, command or role MUST change or delete a
  record; the retention sweep (FR-021) is the one removal, and it removes only whole records older
  than their brand's retention.
- **FR-012**: A malformed record MUST be kept in a quarantine with its bytes, its brand and its
  arrival time, reported in the brand's status, and never dropped; a record whose change to a personal
  field carries a value MUST be quarantined, not stored.

**Queries and exports**

- **FR-013**: A compliance officer MUST be able to query records by brand, subject pseudonym, actor
  pseudonym, service, action, outcome, reason code, class, amount range (where the action carries an
  amount), and period, paged by a cursor in order of occurrence; a role for one brand MUST be refused
  another brand, and the installation's role MUST see every brand, each record naming its brand.
- **FR-014**: A compliance officer MUST be able to export a brand's records for a period as a file in
  one of the formats the service offers, the format chosen per brand; the export MUST run as a job
  with a status and MUST contain every record of the period, in pseudonyms.
- **FR-015**: Every query, export and re-identification MUST itself be a record, naming the actor,
  the filters or range and the count, with class `installation`; a record of a query MUST NOT be
  written about reading records of queries (one level, no recursion).
- **FR-016**: Roles MUST be the realm's (spec 001; decision 0045): a brand compliance role per brand,
  an installation compliance role, and a re-identification role per brand, each granted by an
  installation owner and each grant a record.

**Re-identification**

- **FR-017**: A holder of a brand's re-identification role MUST be able to learn the kind and real id
  behind a pseudonym of that brand, and the pseudonym behind a real id, giving a reason code; both
  MUST be recorded with the actor, the pseudonym and the reason, and MUST be visible to the brand's
  compliance role.
- **FR-018**: Any other role MUST be refused re-identification, and the refusal recorded.

**Platform actions**

- **FR-019**: The service MUST read each brand project's history from ankka's control plane —
  deploys, rollbacks, restores and switches (041), grants and their acceptance, withdrawal and
  revocation (040), erasure requests and their states (042), and changes of secrets by name (038) —
  and MUST write each as an `installation` record of the brand, with the control plane's actor and
  time, so the brand's record holds what the platform did to it.
- **FR-020**: An `installation` record MUST be visible to the brand's compliance role as well as the
  installation's; there MUST be no record about a brand its compliance role may not see.

**Retention, durability and erasure**

- **FR-021**: Records MUST be kept for the installation's retention (default seven years) or the
  brand's longer retention where set; a brand's retention MUST be raisable and MUST NOT be set below
  the installation's. The sweep MUST remove only records older than the retention that applies and
  MUST record what it removed as counts per brand and period.
- **FR-022**: The service's database MUST be backed up under ankka 041; after a restore, the service
  MUST re-read each brand's topic from the restore point where the topic still holds it and MUST
  report any brand whose topic does not, naming the first time it can reach; the topic MUST NOT be
  relied on as the record's keeper (its retention is spec 001's declaration, not the audit's).
- **FR-023**: A player's erasure (ankka 042) MUST leave every record and the registry's rows unchanged:
  an id is not personal data (spec 001 FR-024), the records carry no personal value (FR-003), and the
  regulator's window outlives the player's account.
- **FR-024**: The service's status MUST show, per brand: records received, the lag, the quarantine
  count, the retention in force, the last export, and whether the grant is in effect.

**The brand's own records**

- **FR-025**: A brand's services MAY consume the brand's own `platform.audit` topic within the project
  without a grant — `players` and `reporting` do (spec 001 FR-011); the operator's per-player
  activity tab and the player's own activity download are the brand's views with real ids (spec 008
  FR-034, FR-040), and the brand's compliance exports are `reporting`'s (spec 015 FR-020), not
  queries of the shared audit.
- **FR-026**: The support agent (spec 017) MUST NOT have the shared audit in its tool surface
  (decision 0009); it reads a player's activity from the brand's views, and its own tool calls are
  recorded under FR-004.

### Key Entities

- **Audit record**: one action by one actor on one subject in one brand, with its outcome, changes,
  correlation, causation, time and class; written once by the owning service; immutable.
- **Actor**: the kind and id of who acted — a player, an operator, an agent (with its approver), a
  service (by its identity), or the platform.
- **Subject**: the kind and id of what was acted on — a player, operator, affiliate, merchant, brand
  or service.
- **Change**: a field name with before and after values, or the name alone for a personal field.
- **Pseudonym**: the random id the shared audit uses in place of a real id, one per (brand, kind, real
  id), issued by the registry.
- **Registry**: the shared audit's only store of real ids, mapping each (brand, kind, real id) to its
  pseudonym; readable only through re-identification.
- **Query**: a compliance officer's filtered, paged read of records, itself recorded.
- **Export**: a job producing a file of a brand's records for a period in the brand's agreed format,
  recorded at request and completion.
- **Re-identification**: a role-restricted, reasoned lookup from pseudonym to real id or back, recorded.
- **Retention**: the window a brand's records are kept, the installation's default or the brand's
  longer one.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a parity run of the original's recordings against a brand, every money command,
  refusal and operator action in the recording has exactly one record on `platform.audit`; zero
  actions without a record, zero duplicate records.
- **SC-002**: A search of the shared audit's store, logs and every answer it gives for any real id used
  in the test run finds none; the registry is the only hit.
- **SC-003**: The shared audit ingests a brand's records at 2,000 records per second per brand with
  a lag under 30 seconds at that rate, and a brand's bet-time traffic of 500 bets per second adds
  under 30 seconds of lag.
- **SC-004**: A compliance query over a brand's year of records answers its first page within 2
  seconds; an export of a month completes within 10 minutes.
- **SC-005**: Every query, export and re-identification in a test run has a record of itself; zero
  unrecorded reads of the audit.
- **SC-006**: After a restore of the audit service's database to a point 24 hours back, the store is
  complete to the present within 10 minutes from the brands' topics, with the status naming any gap.
- **SC-007**: Every deploy, grant change, restore and erasure state change made to a test brand
  during a run appears as an `installation` record within 2 minutes.
- **SC-008**: Attempting to change or delete a record through every route, command and role the
  service offers finds no way; the append-only property is a structural fact, not a policy.

## Changes from the original

- **Proposals are not carried over.** The lab-grown-food proposal pipeline is another product's; the
  substrate's propose/execute gate has no casino caller; mimir's TL proposals have no target in a
  service that is code. The `reasoner` project holds `audit` only. Ankka 029's approvals and 036's
  blueprints are the extension point if an agent's proposed actions ever need one.
- **Dashboards are not carried over.** The dashboard and widget twins of decision 0010 and
  `CreateDashboardPrimitive` are blocked in the original (scenario 6 skipped, rendering pod crashes)
  and unused; the backoffice KPI routes are spec 015's.
- **The substrate's generic twin-event projection becomes a domain record.** `twin.property.set.v1`
  with the twin as its own actor becomes "operator X adjusted player Y's real balance from A to B for
  reason R"; the actor, outcome, changes, correlation and causation the original left empty are
  required.
- **The domain's account-event twins and the wallet's audit edges become `platform.audit` records.**
  The operator's activity tab and the player's download read the brand's view of them (spec 015).
- **Previous and new values of a personal field are no longer stored in the clear**; the field's name
  is recorded and the value is not.
- **Three visibility tiers become two classes**, `brand` and `installation`, because one organisation
  runs both; the transparency of decision 0009 (a brand sees platform actions on it) is kept.
- **The registry is narrowed** from an internal lookup to a role-restricted, recorded
  re-identification; the original's query took no account of who asked beyond a tier.
- **Queries and exports are themselves recorded**; decision 0009 deferred this.
- **Platform actions enter the record** from ankka's control plane; the original had no deploy, grant
  or restore in its audit.
- **Retention is enforced** per installation and per brand; the original configured none.
- **`POST /internal/audit/query` and `epipe audit query`** are replaced by the shared audit's query
  route and the CLI that fronts it; the filters are a superset.

## Recordings

- Go: `TestScenario2_ActionProposeExecute` — only its last assertion (an audit record exists for the
  mutation, with the caller's visibility) is replayed; the propose/execute steps are the substrate's
  and are not carried over. `TestRunAuditQuery_empty`, `TestRunAuditQuery_events`,
  `TestRunAuditQuery_systemError`, `TestRunAuditQuery_filterPipelineKind` (`cli/epipe/cmd`) and
  `TestAuditQuery_HTTP` (`cli/epipe/substrate`) as the shape of a query and its answer.
- Playwright: `30-player-activity-tab.spec.ts` and `28-kyc-document-view.spec.ts` are the brand's
  views (specs 015 and 009); their expectations of what is recorded (every category, descending by
  time, a document view audited) are this spec's FR-004 and are replayed against the brand's records.
- Unit: `AuditProjectionIntegrationSpec`, `VisibilityFilterSpec`, `PseudonymRegistrySpec` for the
  registry's "one pseudonym per real id, stable across restarts" and the query's role filter.
- No Go end-to-end scenario drives the shared audit across two brands today; those scenarios are
  written new against the original (one brand) and the new service (two), per spec 001 FR-029.

## Assumptions

- Audit volume is the brand's money-command volume: a brand at 500 bets per second writes 500 records
  per second; at seven years that is a store in the low terabytes per busy brand. The regulator's
  window requires it; the installation sizes the audit service's database for it (ankka 041 backs it
  up).
- Ankka's control plane exposes each project's history (deploys, grants, restores, erasures, secret
  changes by name) in a form the audit service can read continuously; ankka 040 FR-019's listing, 041's
  restore status and 042's request states are that history. If no one feed exists, the plan adds the
  read per source.
- The export formats are the ones the brand's regulators accept; the first is a flat CSV per decision
  0009's `compliance_csv`; a second is added when a licence requires it.
- Pseudonyms are random, not derived, so the registry is load-bearing: it is backed up with the
  service's database (ankka 041) and a lost registry is a lost ability to re-identify, not a lost record.
- The original's substrate audit (`audit_records`, `pseudonym_registry`) is not migrated: its records
  say only that a twin changed, and the brand's account-event twins carry the operator's story; spec
  019 imports the account events as records with the migration's actor and time, and the substrate's
  rows are kept read-only with the old journal.
- The glossary's `shared audit` ("naming a player by a pseudonym and never by a real id") describes
  every record and answer; its registry's holding of ids under spec 001 FR-018 is the one exception,
  and the glossary should say so at the next pass.

## Dependencies

- Spec 001: `platform.audit` in the topic contract (FR-011), its grant to `reasoner`/`audit` (FR-016),
  what `reasoner` may hold (FR-018), personal fields (FR-023, FR-024), roles (decision 0045). Taken
  at reconciliation 3: FR-011's reader list for `platform.audit` names `players` and `reporting`
  in-project (FR-025 here); the project listing at `reasoner` is `audit` alone (001 FR-003).
- The glossary: its `shared audit` term says the shared audit names a player "by a pseudonym and
  never by a real id". That holds for every record and every answer; the registry (FR-009) is the
  one store in `reasoner` that holds a real id, under spec 001 FR-018. At the bdd pass the definition
  gains exactly this sentence: "Its registry alone maps a real id to its pseudonym, and is read only
  by a recorded re-identification."
- Specs 002, 004, 006, 007, 008, 009, 010, 011, 013: the actions each records (their "audit record"
  requirements) are instances of FR-004 and use the shape of FR-001; 013's programme audit is its
  own (spec 013 clarification) and not on a brand's topic.
- Spec 015: the brand's compliance exports and the KPI routes; spec 008: the per-player activity
  views over `platform.audit` and the facts.
- Spec 017: the support agent's tool calls as records; no audit in its tool surface.
- Spec 019: the import of the original's account events.
- Ankka 040 (grants and their history), 041 (backup of the audit database; restore status), 042
  (erasure request states; `Personal` type the helper refuses), 043 (the topic's retention is not the
  record's), 022 (service identities as actor ids).

## Out of scope

- Proposals of any kind: the lab-grown-food pipeline, the substrate's propose/execute gate, mimir's
  TL proposals (Changes from the original).
- Dashboards as twins; the KPI routes (spec 015).
- The brand's own activity views and the player's activity download (spec 015), and the DSAR response
  (spec 008).
- Operational telemetry, traces and logs (ankka 026); mimir's prompt logging (decision 0016).
- A cryptographic chain over records (decision 0009, phase 2+): the store is append-only by
  construction and backed up; a signed chain is added when a licence demands it.
- Cross-installation aggregation of audit.

## Open Questions

- [NEEDS CLARIFICATION: the installation's default retention. Decision 0009 chose seven years; some
  commissions require ten, PCI one. Is seven the installation default with ten set per brand where a
  licence needs it, or ten everywhere?]
- [NEEDS CLARIFICATION: who may hold a brand's re-identification role — the brand's compliance
  officer, the installation's only, or both — and whether a regulator's own account may hold it for a
  bounded time.]
- [NEEDS CLARIFICATION: the export format the current licence's regulator accepts, if any is
  prescribed; otherwise the flat file of decision 0009 is the first and only format.]
