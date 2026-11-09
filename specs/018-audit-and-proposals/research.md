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
| a brand's compliance role | `brand_<slug>` and `brand_compliance` (both exist) |
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
arrival time and why (`undecodable`, `brand_mismatch`, `personal_value`, `collision`). A record whose
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
(`topics.md:343-398`). The consumers' version is `AUDIT_READ_GENERATION` (default 1): after a
restore, an installation owner raises it and redeploys, and every consumer re-reads from the
earliest. Storing is idempotent (R4), so the re-read restores what was lost and changes nothing else.
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
