# audit

The installation's shared audit, the one service of the `reasoner` project (spec 018):
- every brand's `platform.audit` and `platform.modules`, and `payments`' `platform.audit`, read under
  their grants;
- the records of actions kept, and money events no person caused passed over (they stay in the
  brand's ledger);
- every person's id replaced by a pseudonym from the registry, and the ids that would carry one
  digested;
- queries by brand and across brands, exports as CSV or JSON lines, re-identification under a brand's
  role, and role grants, each a record of its own;
- retention per brand, a daily sweep, and a re-read after a restore.

An ankka 0.16 service in Scala, one sbt build. It holds no `Personal` value: the registry's `Identity`
is the one place a real id is kept.

## Components

| Package | Component | Kind | Does |
|---|---|---|---|
| `ingest` | `RecordsConsumer` (each brand's and `payments`' `platform.audit`), `ModulesConsumer` (each brand's `platform.modules`) | consumers | each source's topic under its grant, at the read generation (below); `Ingest.record` decodes, checks, keeps or passes over, pseudonymises and stores |
| `ingest` | `SourceState` | key value entity, per source | stored, passed over, skewed, last received: the status's figures |
| `ingest` | `Quarantined`, `Quarantine` | key value entity, view | what could not be stored, its bytes sealed under the quarantine key |
| `registry` | `Pseudonym`, `Identity` | key value entities | registry id (a keyed digest) → pseudonym, and pseudonym → brand, kind, real id: the one place a real id is kept |
| `store` | `Record`, `Records` | key value entity, view | one record, created once and never changed; `drop` only from the sweep |
| `exports` | `Export`, `ExportFormat` | workflow, key value entity | a brand's records for a range as CSV or JSON lines in the `exports` bucket; the brand's format and last export |
| `roles` | `RoleGrant`, `RealmRead` | key value entity, timed action (daily) | the audit's roles granted and revoked in the realm, and holders found there that it did not grant |
| `sweep` | `RetentionEntity`, `Sweep`, `SweepTimer` | key value entity, workflow, timed action (daily) | a brand's retention, raised only; each day a sweep per brand of what is past it, with a record of its counts per month |
| `api` | `/v1/audit-records`, `/v1/audit-exports`, `/v1/audit-identities`, `/v1/audit-roles`, `/v1/audit-retention`, `/v1/audit-status` | endpoints | `specs/018-audit-and-proposals/contracts/routes.md`; every read and every change is a record of the audit's own (`own/Own.scala`) |

The domain is pure and has its own suites: `keep` (what is kept), `ids` (digests), `query` (filters
and cursors), `retention` (cutoffs and raises), `stored` (the stored shape), `exports/Lines`.

## Settings

| Variable | Default | |
|---|---|---|
| `AUDIT_BRANDS` | none | the brands read, comma-separated; rendered by the modules tool from the brands that run `core` |
| `CASINO_PROJECT` | `reasoner` | the project the service runs in |
| `AUDIT_RETENTION_YEARS` | 7 | the installation's retention; a brand's may be raised above it, never below |
| `AUDIT_READ_GENERATION` | 1 | the consumers' version; generation 1 reads from the earliest |
| `AUDIT_READ_FROM` | none | the restore point; required above generation 1 |
| `AUDIT_SKEW_BOUND` | `5m` | a record dated further ahead than this is stored and counted as skewed |
| `AUDIT_PAGE_LIMIT` | 1000 | the most records a page answers |
| `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD` | none, `eitheror` | the operators' realm the roles routes grant in; secrets in the manifest |

The registry key and the quarantine key are made once by the seed timer, in the secret store; they
are never in a setting.

## Suites

| Command | Runs | Needs |
|---|---|---|
| `just audit-test` | the unit suites | nothing |
| `just audit-it` | `it.*` (the integration kit over Postgres: ingest, store, queries, exports, re-identification, roles, the installation, durability, leaks) and `features.*` (the five audit features bound) | Docker |
| `just audit-parity` | `port.ParitySuite`: the six rows of `contracts/recording.md` (`tests/port/audit/README.md`) | Docker |
| `just audit-perf` | `perf.IngestPerf`: SC-003, SC-004 and SC-006 at scale; figures in `PERF.md` | Docker, time |

Run with `CASINO_VERSION=<version>` when another worktree publishes the contract under the default
snapshot.

## Running locally

`sbt schema && docker compose up -d`, then `just audit-run`: the service on 9019 as `reasoner`,
reading `goldvibe`, its topics in memory, staff tokens verified against dev-kind's realm through a
port-forward on 18180.

## Re-reading after a restore

The store is rebuilt from backup, not from the brands' topics, because a brand's topic is not the
record (FR-022). After restoring the database to a point in time, the records after that point are
re-read from every brand's `platform.audit`, which keeps everything:

1. Set `AUDIT_READ_FROM` to the restore point (an instant, UTC) and raise `AUDIT_READ_GENERATION` by
   one, in `shared/reasoner/manifest.json`.
2. Deploy. Each consumer is a new group at the new version and starts at the restore point, not at
   the start of the topic (research R14).
3. Storing is idempotent: a record already restored is the same record again and changes nothing.
   A person first seen after the restore point is issued a new pseudonym, which re-identifies to the
   same id; an export made between the backup and the restore names the old one.
4. `GET /v1/audit-status` shows each source's counts and lag. Its `topicKeeps` answers `not stated`
   until a brand's topic retention reaches the service, so whether a brand's topic still reaches the
   restore point is checked on the brand's manifest (every `platform.audit` keeps everything); one
   that does not is a gap to report.

Leave `AUDIT_READ_FROM` set: the next restore raises the generation again.
