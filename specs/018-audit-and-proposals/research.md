# Research: the shared audit

Every decision here was made against ankka 0.16.0 (tag `v0.16.0` of the ankka checkout, the version
`services/engagement/build.sbt` pins) and the services on `main` at `a0f13277`. Paths into ankka are
relative to its checkout; `docs/…` are the pages `read_doc` serves.

## R1. What the original has, and what is carried over

The original's audit is one projection over the twin journal (`substrate/substrate-server/.../V7__audit_projection.sql:1-26`):
`audit_records` (record id, occurred at, event type, twin id, actor id, action type, before and after
summaries, correlation, causation, visibility tier) and `pseudonym_registry` (a UUIDv4 per twin id,
`PseudonymId.scala:10`, issued by an upsert, `PseudonymRegistry.scala:47-86`). In practice every
record's actor is the twin itself and before, after, correlation and causation are null
(`AuditProjectionHandler.scala:47-55`). Its one reader is `POST /internal/audit/query` on the
unauthenticated internal port: filters twin, event type, since, until and a limit; answers 200 even on
error; pages only by `hasMore` (`InternalSubstrateRouter.scala:310-370`, `AuditStore.scala:65-178`).
`epipe audit query` fronts it (`cli/epipe/cmd/audit.go`). There is no export and no retention.

**Decision**: keep two things, the registry's shape (a random UUIDv4 per real id, issued once, held
for good) and the visibility filter's rule that a caller sees what its role admits and nothing else.
Everything else is new and listed under the spec's *Changes from the original*. The internal route and
the CLI are not served: no brand or operator calls them.

## R2. Where records come from: one consumer per source

`ChangeSource.fromTopic(project, name, decoder, StartFrom.Earliest)` reads one topic of one project
under its grant (`docs/build/topics.md:113-178`; spec 013 research R3 for the same pattern).
Components are registered at start, so a source is a component built for it.

| Component | Reads | Granted by |
|---|---|---|
| `records-<slug>`, one per brand | the brand's `platform.audit` | `core` (`modules/core/module.json:560-566`) |
| `modules-<slug>`, one per brand | the brand's `platform.modules` | `core` (`module.json:568-574`) |
| `records-payments` | `payments`' `platform.audit` | `payments`, added here (R15) |

The service reads `AUDIT_BRANDS` (slugs) at start. The modules tool renders it for `reasoner/audit`
from the brands that run `core`, as it renders `PROGRAMME_BRANDS` (`Render.scala:131`). A new brand
is a re-render and a rolling restart.

**Raw bytes, decoded in the handler.** A consumer whose decoder fails does not advance and retries the
message forever (`docs/build/consumers.md:71-74`, `topics.md:421-429`). So every consumer reads
`Serializer.bytes` (`modules/core/.../Serializer.scala:38-42`) and decodes with the contract's
`PlatformAudit` or `PlatformModules` serializer itself; a failure is quarantined (R11) and the consumer
moves on (FR-012).

**Alternatives rejected**: decoding in the source (one bad message stops a brand's audit); a webhook
per brand (the grant is the carrier spec 001 FR-016 names).

## R3. What is kept (FR-008a)

`Keep.decide(record)`, pure: a record is passed over when its category is `money`, its actor kind is
`service` or `system`, and its outcome is not `refused`; everything else is kept. On `main` the wallet
writes a record for every money event (`services/wallet/.../publish/Facts.scala:214-250`), about two
a spin, so the rule passes over nearly all of a brand's volume and keeps operator, player-account,
agent, KYC, responsible gambling, send and installation records and every refusal.

Not yet measured: the share kept is expected to be small because bets dominate, which would put a busy
brand's seven years in the tens of millions of records, inside what an unindexed view answers by
keyset paging (R4). The performance suite measures it against a recorded brand's mix (quickstart §4)
and SC-004 is checked at that size; if the share is far larger, the view-index finding becomes this
spec's blocker.

## R4. The store: a key value entity per record, a view over them

`Record` (key value), id `<source>/<digest of record id>` (R5 for the digest). One command, `keep`:
when empty, it stores the record; when it holds the same record, it answers `kept` and changes
nothing (FR-010); when it holds a different one, it refuses `collision` (reported, never overwritten).
`drop` deletes it, and only the retention sweep sends it (FR-011). No other command exists (SC-008).

`Records` (view over `Record` state): one row per record with the fields queries filter on as JSON
(`docs/build/views.md:861-931`). Queries are `ordered` with a keyset condition on
`(occurredAt, key)`, so a cursor is the last row's pair (`views.md:947-1150`); every filter of FR-013
is a `sql"…"` fragment. The entity's deletion deletes the row (`deleteRow`, `views.md:377-402`).

**Why a key value entity**: a record is written once, needs no history, and must be deletable by the
sweep alone; a view cannot be written or deleted from outside its source (`views.md:1131-1134`).

**At task time**: verify that a key value entity's deletion reaches a `stateOf` view as a deletion in
0.16. Fallback: `drop` writes a tombstone state and the view deletes on it.

**Alternatives rejected**: the service's own tables over the database credentials the platform hands
it (`docs/platform/databases.md:35-41`): undocumented, against the constitution's rule that ankka's
gaps are ankka specs. A view per month: needless at the volume R3 leaves. An event sourced entity per
record: a journal and a snapshot for something that never changes.

## R5. Pseudonyms and the ids that would leak

Record ids, correlation and causation ids are built from player ids on `main` (wallet:
`wallet/<player>/<currency>/<key>`, `Facts.scala:251`), so pseudonymising only actor and subject would
leave real ids in the store (SC-002).

- **People** (`player`, `operator`, `affiliate`, `agent` subjects and actors, every approver): a
  pseudonym from the registry.
- **Record, correlation and causation ids**: `d-` and the first 32 hex characters of
  HMAC-SHA256(registry key, `<brand>/<id>`). Stable, so a correlation still joins records; a query that
  filters by one digests what it is given.
- **Any other id** (a market, a reward, a service): kept as written (Clarification 2026-11-08), unless
  it contains the real id of a person the same record names, when it is digested too.

**The registry** is two key value entities. `Pseudonym`, id the HMAC hex of
`<brand>/<kind>/<real id>` (no real id in an entity id, so none in a log or a trace), state the
pseudonym, a random UUIDv4 issued by the first `issue` and returned by every later one: the entity
serialises two first sightings into one pseudonym. `Identity`, id the pseudonym, state (brand, kind,
real id), written after `issue` and idempotent. `Identity` is the one place a real id is held (FR-009)
and has no view; only the re-identification route reads it.

The registry key is generated at first start into the service's secret store
(`docs/build/secrets.md`) and backed up with the database (ankka 041). A lost key loses the ability to
find an existing pseudonym from a real id, not a record (spec Assumptions).

## R6. Visibility and scope (FR-020)

Derived at ingest, stored beside the record:

| Source | Visibility | Brand |
|---|---|---|
| a brand's `platform.audit` | `brand`, or `installation` for an erasure request's state | the record's, which must equal the source brand, else quarantined as mismatched |
| `payments`' `platform.audit` | `brand` | the record's: a brand slug, or `payments` when it names none (`merchant/.../AuditFacts.scala`: `a.brand.getOrElse("payments")`) |
| a brand's `platform.modules` | `installation` | the source brand |
| the audit's own records (queries, exports, re-identifications, grants, sweeps) | `installation` | the brand they concern, or `installation` |

A brand compliance officer sees every record whose brand is theirs; the installation's sees all.

## R7. Installation records (FR-019, Clarification 2026-11-09)

ankka 0.16 lets no service read the control plane: every route takes a person's OIDC token, the
project history keeps the last 100 lines with no cursor (`controlplane/.../domain/model.scala:398`),
grants and secrets are separate routes, and no topic of control-plane events exists
(`docs/reference/control-plane-api.md:17-33, :685-692`).

- **Deployments** from `platform.modules`: one installation record per `ModuleChange`
  (`contract/.../facts/PlatformModules.scala`): actor kind `platform`, actor the change's `actor`,
  subject the brand, service the module, action `module.<change>`, changes `version` and `channel`.
- **Erasure requests** from `players`: its erasure workflow writes a record per state (filed, held,
  applied, completed), amended here (R15); the audit keeps them as installation records.
- **Rollbacks, restores, switches, grants, secret changes**: an ankka spec (Findings). Until it
  lands, `Sources` reports them `not stated`, and the scenarios that need them are bound and ignored
  with that reason.

## R8. Roles and their grants (FR-016, Clarification 2026-11-09)

The realm's roles today: `brand_<slug>` per brand, added by the modules tool (`Realm.scala:144`), and
static permissions in `brands/_realm/eitheror-operators.json`.

| Role | Held as |
|---|---|
| a brand's compliance role | `audit_compliance_<slug>`, new, one per brand, added by `Realm.ensure` (amended at review: `brand_compliance` × `brand_<slug>` could not be held for one brand alone) |
| the installation's compliance role | `audit_compliance`, new, static |
| a brand's re-identification role | `reidentify_<slug>`, new, one per brand, added by `Realm.ensure` beside `brand_<slug>`; admitted only beside the brand's compliance role |
| an installation owner | `platform_admin` |

Grants go through `POST /v1/audit-roles/...`, which calls Keycloak's admin API through a
`KeycloakAdmin` seam copied from `services/affiliate-programme/.../accounts/KeycloakAdmin.scala`, and
writes a record of the grant or revocation. A daily timed action reads each role's holders and records
each holder with no grant in `RoleGrant` as granted outside the audit, once per (role, person).

## R9. Queries and their records (FR-013, FR-015)

`GET /v1/audit-records` with every filter of FR-013 as a query parameter, the brand optional for the
installation's role and required for a brand's. A page is 100 rows (at most 1000) with an opaque
cursor (base64 of the last `(occurredAt, key)`). Each query writes one record of its own (actor, the
filters, the range, the count). A query whose filters name only the audit's own service is not
recorded (one level, FR-015).

## R10. Exports (FR-014)

`Export` workflow, one per request, id the request's key. Step `write` streams the brand's records for
the period (`orderedStream`) in the format the brand's `ExportFormat` names, CSV or JSON lines, to a
temporary file, then puts it in the bucket `exports` (`docs/build/object-storage.md:34-80`) and ends
with its name and count; its step timeout is 10 minutes (SC-004), two retries. `GET` on the export
answers the status and, once done, a signed URL valid for 15 minutes. Recorded at request and at
completion. The descriptor declares `"buckets": [{"name": "exports", "exposure": "signed"}]`; the
kits use the in-memory store (`object-storage.md:629-649`).

CSV columns: the record's fields in FR-001's order, `changes` flattened as
`field:before>after;…` with a personal field written `field:`. JSON lines: one record per line as the
store holds it.

## R11. Quarantine (FR-012)

`Quarantined` (key value), id `<source>/<SHA-256 of the bytes>`: the bytes (base64), the source, the
arrival time and why (`undecodable`, `brand_mismatch`, `personal_value`, `collision`, and, found at review, `pseudonym_conflict`). A record whose
personal-field change carries a value is quarantined, never stored. The `Quarantine` view counts per
source for the status.

## R12. Status (FR-024)

`GET /v1/audit-status` per source: records stored and passed over, the last received, the lag (now
less the last kept record's occurrence), the quarantine count, the retention in force, the last export,
the sources of installation records and their state, and clock skew (records whose occurrence is more
than `AUDIT_SKEW_BOUND`, 5 minutes, after their arrival). A Scala consumer cannot see `ce-time` or the
broker's timestamp (`modules/sdk/.../ChangeSource.scala:200-230`), so the arrival time is the
service's clock at ingest.

**The grant's state**: a consumer cannot observe that a grant was revoked; its source simply stops.
The status shows the last record received and since when, which is the gap the scenario asks for;
ankka's own service status shows the consumer failing (Findings).

## R13. Retention and the sweep (FR-021)

`AUDIT_RETENTION_YEARS` (7) is the installation's; `Retention` (key value, per brand) holds a longer
one, raised only, never below the installation's. A recurring timed action (daily) starts a `Sweep`
workflow per brand: each step pages 500 records older than the cutoff by keyset and drops each
`Record`, keeping the cursor in its state, then writes one record of what it removed (counts per
month).

## R14. After a restore (FR-022)

A consumer's start position applies once per group; re-reading needs a raised component version
(`topics.md:343-398`), and a new group may start at an instant (`StartFrom.At`, `topics.md:113-178`).
The consumers' version is `AUDIT_READ_GENERATION` (default 1), and generation 1 starts from the
earliest. After a restore, an installation owner sets `AUDIT_READ_FROM` to the restore point, raises
the generation and redeploys; every consumer re-reads from that instant, not from the start of years of
a brand's topic, which is what keeps SC-006 within 10 minutes. Storing is idempotent (R4), so the
re-read restores what was lost and changes nothing else.
Every brand's `platform.audit` keeps everything (`module.json:513-516`), so every brand reaches; a
source whose topic declares less is named in the status with the time its earliest record has.

## R15. Neighbours amended

| Where | Change | Why |
|---|---|---|
| `services/wallet/.../publish/Facts.scala` | `AdminAdjusted` and `TurnoverOverrideSet` records carry `amount`, `currency` and `target` as changes | FR-008a, FR-013's amount filter, records.feature's adjustment |
| `services/players/.../erasure/` | the erasure workflow writes a record per state | FR-019 |
| `contract/.../access/Routes.scala` | `auditCompliance(brand)`, `auditInstallation`, `reidentifier(brand)`, `installationOwner` | R8 |
| `brands/_realm/eitheror-operators.json`; `tools/modules/.../Realm.scala` | `audit_compliance`; `reidentify_<slug>` per brand | R8 |
| `tools/modules/.../Render.scala` | `AUDIT_BRANDS` for `reasoner/audit` | R2 |
| `shared/payments/manifest.json` | grant `platform.audit` to `service:reasoner/audit` to consume | R2, Clarification 2026-11-08 |
| `shared/reasoner/manifest.json` | the image, env, the bucket | R10 |
| spec 001 FR-016 | names the `payments` grant | Clarification 2026-10-12 |

**`features/audit/records.feature` is bound where each record is written**, since it describes the
brand services' records on their own topic: the adjustment, the refused bet, the captured deposit and
the repeated key in `services/wallet/`'s feature suite; the deposit limit in
`services/responsible-gambling/`'s; the email change in `services/players/`'s; the support agent's
suspension is ignored with the reason "spec 017 is not built". At task time, each is run against the
service first: on `main` the wallet's refusal record has an empty causation and its money records no
balance before and after, so the wallet's mapping gains them where its events carry them. A scenario
the events cannot meet (a balance the event does not carry) is a question for the person, not a
guess.

## R16. Recordings and parity (constitution I)

The original has no surface this service ports: its query route is internal and unauthenticated, and
nothing in a brand calls it. What it shows is kept as parity tests in `tests/port/audit/`:

| Recording | Kept as |
|---|---|
| `TestScenario2_ActionProposeExecute`, last assertion: a mutation leaves a record carrying a visibility (`tests/e2e/scenario2_action_propose_execute_test.go:81-95`) | an operator's adjustment on a brand leaves one stored record with its visibility |
| `TestRunAuditQuery_empty`, `_events`, `_filterPipelineKind`, `TestAuditQuery_HTTP` (`cli/epipe`) | a query with no match answers no rows; a query by kind and since answers the matching rows, newest last |
| `PseudonymRegistrySpec` | one pseudonym per real id, stable across a restart |

They are recorded from a local run of the original's scenario 2 (`just test-e2e-scenario 2`, the
internal port at 19001) where the original's stack can be started; otherwise the expectation is
translated from the test's own fixture and marked so in `tests/port/audit/README.md`.

## R17. Language, runtime, storage

Scala 3.9 on ankka 0.16.0, as engagement. Its own Postgres (entities, views, timers, the secret
store), one bucket. No `Personal` field, so the descriptor declares no `personal` (`docs/.../personal-data.md:421-431`):
the registry's real ids are ids, not personal data (spec 001 FR-024), and the regulator's window
outlives an erasure (FR-023).

## Findings

- **ankka**: a project's events (rollbacks, restores and switches, grants' changes, secret changes) on
  a topic the project may grant, so a shared service can audit the platform's actions on a brand
  (FR-019). Today the history is person-only, 100 lines and cursorless.
- **ankka**: a view index a service declares, if the store ever needs to hold money events.
- **ankka**: a consumer cannot see a revoked grant, `ce-id`, `ce-time` or the broker's timestamp.
- **Spec 001**: FR-016 names the `payments` grant of `platform.audit` to `reasoner/audit`.
- **Spec 008**: the erasure workflow's records (R15).

## Design finished

2026-11-09: analyze reported nothing above LOW.

## Found during implementation

- **The quarantine's bytes are sealed** (asked 2026-11-09, T019). FR-012 keeps a malformed record's
  bytes and SC-002 finds no real id outside the registry; a brand's malformed record nearly always
  names a player. Decision: `Quarantined` holds the bytes sealed with AES-256-GCM under
  `quarantine-key`, 32 bytes in the secret store that the seed makes beside `registry-key`. The bytes
  are kept (FR-012) and no search of the store finds what they hold (SC-002); repairing one unseals it
  with the key. Rejected: the bytes in clear (SC-002 would hold for well-formed records only), and a
  digest alone (FR-012 amended, nothing to repair from).
- **A dropped record leaves the view** (T018, verified 2026-11-09 on ankka 0.16.0 over Postgres,
  `services/audit/.../it/StoreSuite.scala`). A key value entity's `deleteEntity` reaches the
  `stateOf` view `Records` as a deletion and its default handler removes the row, so `drop` deletes
  the record and no tombstone is written (R4's fallback is not needed).
- **What records.feature found in the wallet** (T027, run before T030). Three scenarios failed on
  `main`: a refusal's outcome was its code (`INSUFFICIENT_FUNDS`), so `Keep` (which keeps a refusal by
  `outcome == "refused"`) would have passed over every wallet refusal; its causation was empty; and
  no money record listed a change. T030: a refusal is `refused` with its code, lowercased, as the
  reason and the refused command's key as causation; an adjustment lists `amount`, `currency`,
  `target` and the balance it moved, before and after; a deposit names `cashier` as its actor and
  lists the same; the turnover override lists `currency` and the terms. "Outcome done" in the
  scenario is the action's own outcome, the wallet's `accepted` (FR-001).
- **What records.feature found in responsible gambling and players** (T028, T029). Both audited
  operators' actions only, so a player's limit and a player's email change left no record (FR-004).
  T028 and T031: a player's change on their own account is a record whose actor is the player, of
  category `account`: in responsible gambling, the limits, breaks and exclusions; in players,
  registration, profile edits (the fields named), each email change (named, no value), the session
  limit, self-exclusion, consent the player gave or withdrew, and closure. The system's and a third
  party's changes stay unrecorded.
- **FR-004 per brand service** (T033, read 2026-11-09 against FR-004's list). No service records a
  plain read; kyc's document view is the one read recorded, as FR-004 asks. Fixed here, with tests:
  wallet's account refusals now have outcome `refused` (as its wallet refusals since T030); players
  records an operator's profile edit under the operator (`ProfileEdited.operatorId`), where T031 had
  named the player. Each gap below is a finding for its service's spec, not fixed here:
  - **wallet** (spec 002): refusals made at the route before the entity (an operator adjustment's
    invalid request or missing key, the service money routes' validation); the from-account move
    refused for a missing wallet (`AccountMoveWorkflow`, kept in the workflow's state); the operator's
    `PUT /wallet-config/turnover` and the service's `bet-switches` (`BrandConfigEntity` has no audit
    publisher); the payment outcomes reported over the payments grant (`PaymentEvent` is not audited).
  - **game-provider** (spec 003): no audit mapping. A bet refused by responsible gambling or failed
    closed never reaches the wallet, so no record anywhere; the operator's catalogue configuration,
    provider configuration, catalogue refresh and unacknowledged replay and resolve leave none.
  - **payouts** (spec 007): a withdrawal request refused at the gate and a refused cancel; the
    entity's refusals of approve, reject, confirm and fail, which reply and persist nothing; the
    operator's read of a request showing the holder's names and IBAN's last four (a payment-details
    read).
  - **players** (spec 008): an erasure filed (US5, T049, records its states); a cookie decision
    (consent); `ExclusionLifted`; the operator's activity export, the brand, currency and allow-list
    configuration, and a module request; the operator's read of an assembled export carrying payout
    details.
  - **kyc** (spec 009): none.
  - **responsible-gambling** (spec 010): a staged limit raise or removal (`PendingStaged`), and the
    reality check, which carries no actor.
  - **rewards** (spec 011): a refused operator award or revoke (only `Activated` and `Revoked` are
    published).
  - **engagement** (spec 014): the operator's CRM seed and agent-stream ticket.
  - **markets** (spec 023): no operator command is recorded (drafts, terms, approve, open, suspend,
    resume, close, resolve, correct, void); only the driver's exchange refusals.
  - **exchange** (spec 023): order placements and cancels refused by the gates or the book, unless
    the wallet's reservation refused them (the placement workflow is ankka-predictions' and has no
    audit publisher here).

- **A brand's compliance role reads both visibilities** (T052, FR-020). The records route and the
  export first held a brand's officer to `brand` records, so installation records about the brand
  (deployments, erasures, the audit's own) were hidden from it, against FR-020 and the clarification
  that a brand sees who touched it. A brand's officer is now held to its brands alone; `visibility`
  stays a filter anyone may ask by. The audit's own records keep `installation` (R6).
- **An erasure's records name their state `erasure`, not `state`** (T049). `state` is on the
  contract's list of personal fields (an address's state), so a record naming it with a value is
  quarantined (FR-003). The players mapping test now checks no erasure record names a personal field.
- **Erasure states**: `filed` from `ErasureFiled`; `held`, `applied` and `completed` from a new event,
  `ErasureStageReached`, which the workflow asks the player entity for at each state and the entity
  persists once per state. Each is `erasure.<state>`, actor kind `system`, category `erasure`, under
  the erasure's correlation id.
- **The rows of an outline cannot be ignored one by one**: a suite's `ranOutside` names whole
  scenarios. The deployment outline's rollback and restore-switch rows have steps that skip with
  R7's reason (`assume`), so its deployment row still runs; the shared-part outline is ignored whole.
- **Roles** (R8): a grant is held under the person's installation pseudonym (`<role>/<pseudonym>`),
  never their realm id. *Superseded at review: one realm role per grant, `audit_compliance_<slug>`; below as built first.* `brand_compliance` kept the brands it was granted for, and its realm role
  goes once the last brand's grant is revoked. A revoke leaves `brand_<slug>`, which is an operator's
  role too. The daily realm read is a recurring timer, `realm-read`, first a minute after start.
- **The status's last export** is kept on `ExportFormat`, the brand's export settings, when an export
  finishes; no `Exports` view was needed.
- **Retention and the sweep** (T055): a brand's retention is `RetentionEntity` (raised only); the daily
  `sweep` timer, first ten minutes after start, starts `Sweep` `<brand>/<day>` for each brand read,
  `payments` and `installation`, so a timer firing twice starts nothing more. The sweep's record is the
  system's (`Own.record` takes an actor kind), with the cutoff, the total, and `removed.<yyyy-mm>` per
  month.
- **A re-read after a restore issues new pseudonyms only for people first seen after the restore
  point** (T053): the registry is in the restored database, so a person it already held keeps their
  pseudonym, but one first seen after the restore point is issued a new one on the re-read, and an
  export made between the two names the old one. The records are otherwise as stored (same key, same
  fields), and each pseudonym re-identifies to the same real id. Left as it is: a deterministic
  pseudonym is what FR-009 refuses. The status says what each source's topic keeps (`topicKeeps`:
  `everything` for every brand's and payments' `platform.audit`, R14), which is how a restore knows it
  reaches back.
- **SC-003 and SC-006 are missed in the integration kit** (T058, `services/audit/PERF.md`): 209
  messages a second handled against 1,200, and 43,934 of a day's 50,000 re-read in 10 minutes. The
  kit's broker is one partition, serial; the limit partitioning would not lift is `SourceState`, written
  once per message for one source. Reported, not tuned away: batching the counter changes the data
  model and the ingest contract, which is a decision for the spec. SC-004 is met (0.75 s, 3.2 s).
  Decided 2026-11-09: ship with the miss reported; a follow-up spec amends `contracts/ingest.md` and
  `SourceState` (count in the consumer, flush once a second; pseudonyms concurrently) and measures on
  Kafka on kind.
- **T063 skipped** (2026-11-09): the `ankka` kind cluster's control plane was not running (its API at
  127.0.0.1:49180 refused connections), so `shared/reasoner` was not deployed beside `goldvibe`.
- **T062** (2026-11-09): `just features` 0 findings; root `just test` green; audit 46 unit and 71
  integration, feature and parity (2 skipped, R7); wallet 764, responsible-gambling 157, players 424.
  Players' first full run failed `RealmSuite`'s p99 (147.9 ms of 100) and one `LeakSuite` case; both
  passed alone and on a second full run. No build here has the scalafmt plugin, so
  `scalafmtCheckAll` was not run.
- **Review, decided 2026-11-09** (amends R8 and contracts/routes.md):
  - A brand's compliance role is `audit_compliance_<slug>`, made by `Realm.ensure` beside
    `reidentify_<slug>`. `auditScope` reads only those, a grant assigns that one role, and a revoke
    removes exactly it. Re-identifying at a brand needs `audit_compliance_<slug>` and `reidentify_<slug>`.
    `brand_compliance` and `brand_<slug>` no longer bear on the audit. (Second round: first named
    `compliance_<slug>`, renamed because the realm's static `compliance_officer` read as brand `officer`.)
  - A `reasonCode` shaped like an id (a run of 8 hex digits once `-` is taken out) is refused: real ids
    are UUIDs, which the code's letters would otherwise admit.
  - A skewed record does not move `lastOccurred`, so a clock running ahead cannot hide the lag.
  - `GET /v1/audit-roles/{role}` answers each holder's installation pseudonym; `DELETE` takes a
    pseudonym or a realm id.
  - `topicKeeps` is in routes.md and answers `not stated` until a brand's topic retention reaches the
    service.
  - A `reasonCode` is a code, `[a-z0-9_.-]{1,64}`; anything else is `400`.
