# Data model: the shared audit

One service, `audit`, in the shared `reasoner` project. Its components, what each holds, and the rules
each enforces. Decisions: [research.md](research.md) R2–R14.

## The record as it arrives

The contract's `audit_record` (`contract/.../facts/Others.scala:232-236`), unchanged:

| Field | Type | Notes |
|---|---|---|
| `recordId` | string | unique within its source project (R5) |
| `brand` | string | a brand slug; `payments` when a payments record names none |
| `service`, `action` | string | |
| `actorKind`, `actorId`, `approverId?` | string | open kinds (FR-001) |
| `subjectKind`, `subjectId` | string | open kinds |
| `outcome`, `reasonCode?` | string | |
| `changes` | `[{field, before?, after?}]` | a personal field carries neither |
| `correlationId`, `causationId` | string | |
| `recordClass` | string | the category: `money`, `operator`, `security`, `system`, … |
| `occurredAt` | instant | |

And `module_change` from `platform.modules` (`PlatformModules.scala`): `module`, `change`, `version?`,
`channel?`, `actor`, `at`.

## Pure packages

| Package | Holds | Tested by |
|---|---|---|
| `keep` | `Keep.decide(record): Keep | PassOver` (FR-008a) | `KeepSuite`: every category × actor kind × outcome |
| `ids` | `Ids.digest(key, brand, id)`, `Ids.isPerson(kind)`, `Ids.contains(id, realIds)` | `IdsSuite` |
| `stored` | `Stored.from(record, source, pseudonyms, key): Stored`; `Stored.fromModule(change, brand)`; visibility (R6) | `StoredSuite`, a property: no real id of the input appears in the output |
| `query` | the filters, the keyset cursor, the SQL fragments | `QuerySuite` |
| `export` | `Csv.line(stored)`, `JsonLines.line(stored)` | `ExportSuite`, with the original's CSV header order |
| `retention` | `Retention.cutoff(now, years)`, `Retention.raise(current, asked, floor)` | `RetentionSuite` |

## The stored record

`Stored`: what `Record` holds and `Records` indexes.

| Field | From |
|---|---|
| `key` | `<source>/<digest of recordId>` |
| `source` | `brand:<slug>`, `payments`, `modules:<slug>`, `audit` |
| `brand` | R6 |
| `visibility` | `brand` or `installation` (R6) |
| `service`, `action`, `outcome`, `reasonCode`, `category` (= `recordClass`), `occurredAt` | as written |
| `actorKind`, `subjectKind` | as written |
| `actor`, `subject`, `approver?` | a pseudonym for a person's kind; for any other, the id as written or its digest (R5) |
| `changes` | as written; `amount` and `currency`, where present, also as `amount: Long?`, `currency: String?` for the amount filter |
| `correlation`, `causation`, `recordDigest` | digests (R5) |
| `arrivedAt` | the service's clock at ingest |

Nothing in it is a real id of a person (SC-002).

## Key value entities

| Entity | Id | State | Commands | Rules |
|---|---|---|---|---|
| `Record` | `Stored.key` | `Stored` | `keep(stored) → kept | collision`; `drop(sweepId)` | create-only; the same record again changes nothing (FR-010); a different one is refused; only `Sweep` sends `drop` (FR-011) |
| `Pseudonym` | HMAC hex of `<brand>/<kind>/<realId>` | `pseudonym` | `issue → pseudonym` | the first `issue` draws a UUIDv4; every later one returns it (FR-009) |
| `Identity` | the pseudonym | `brand`, `kind`, `realId` | `bind(brand, kind, realId)`; `get` | written once; a second `bind` with other values is refused `conflict`; no view; read only by the re-identification route |
| `Quarantined` | `<source>/<SHA-256 of bytes>` | bytes sealed under `quarantine-key` (AES-256-GCM), source, arrival, why | `hold` | idempotent (FR-012); sealed so no real id is searchable (SC-002) |
| `Retention` | the brand | years | `raise(years, by)` | refused below the current or the installation's (FR-021) |
| `ExportFormat` | the brand | `csv` or `jsonl` | `set(format, by)` | default `csv` |
| `RoleGrant` | `<role>/<person>` | granted by, at; or revoked | `grant`, `revoke`, `found` | `found` records an outside holding once (R8) |
| `SourceState` | the source | last received, counts stored and passed over, skewed | `received(at, kept, skewed)` | updated by the consumers, read by the status |

## Workflows

| Workflow | Id | Steps | Bounds |
|---|---|---|---|
| `Export` | the request key | `write` (stream, put the object), `done` | step 10 min, 2 retries; recorded at start and end |
| `Sweep` | `<brand>/<day>` | `page` (500 records, self-transition with the cursor), `record` | step 1 min; ends with one record of its counts |

## Views

| View | Over | Queries |
|---|---|---|
| `Records` | `Record` state | `page(filters, after, limit)` keyset on `(occurredAt, key)`; `count(filters)`; `olderThan(brand, cutoff, after, 500)` |
| `Quarantine` | `Quarantined` state | `countBySource` |
| `Grants` | `RoleGrant` state | `byRole(role)` |
| `Exports` | `Export` state | `latestByBrand(brand)` |

## Consumers

| Consumer | Source | Does |
|---|---|---|
| `records-<slug>` per brand, `records-payments` | `platform.audit` as bytes | decode, quarantine or `Keep.decide`; for a kept record: `issue` and `bind` each person's pseudonym, build `Stored`, `keep`; `received` on `SourceState` |
| `modules-<slug>` per brand | `platform.modules` as bytes | decode, build `Stored.fromModule`, `keep` |

Each is versioned by `AUDIT_READ_GENERATION` and starts from `Earliest` at generation 1, from
`AUDIT_READ_FROM` at a later one (R14).

## Timed actions

| Action | When | Does |
|---|---|---|
| `sweep` | daily, recurring | starts `Sweep` for each brand |
| `realm-read` | daily, recurring | lists the holders of each audit role; `found` for any without a grant |

## Secrets

| Name | Holds | Made |
|---|---|---|
| `registry-key` | 32 random bytes for the digests and the registry ids | at first start, if absent, by the `seed` timer |
| `quarantine-key` | 32 random bytes the quarantine seals a message's bytes under | the same |
| (project) `keycloak-operators-admin` | the realm admin credential | the manifest, as `programme` |

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `AUDIT_BRANDS` | (rendered) | the brands whose sources are read |
| `AUDIT_RETENTION_YEARS` | `7` | the installation's retention |
| `AUDIT_READ_GENERATION` | `1` | the consumers' version; raised to re-read after a restore |
| `AUDIT_READ_FROM` | (none) | the instant a raised generation re-reads from: the restore point; required when the generation is above 1 |
| `AUDIT_SKEW_BOUND` | `5m` | occurrence after arrival beyond which a record counts as skewed |
| `AUDIT_PAGE_LIMIT` | `1000` | the largest page a query may ask for |
| `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD` | | as `programme` |
