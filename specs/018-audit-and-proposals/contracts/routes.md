# Routes of the shared audit

All under the `reasoner` project's `audit` service, signed in at the `eitheror` realm (staff issuer).
One endpoint per prefix (ankka 0.13+: no shared prefixes, at most two path parameters). Errors are the
repository's problem shape: `{"code": "...", "message": "..."}` with a 4xx status.

| Role in the table | Admitted by |
|---|---|
| brand compliance (`b`) | `Routes.auditCompliance(brand)`: `brand_<slug>` and `brand_compliance` |
| installation compliance (`i`) | `Routes.auditInstallation`: `audit_compliance` |
| re-identifier (`r`) | `Routes.reidentifier(brand)`: brand compliance and `reidentify_<slug>` |
| installation owner (`o`) | `Routes.installationOwner`: `platform_admin` |

## `/v1/audit-records`

`GET /v1/audit-records` — `b` (with `brand`), `i` (with or without it). FR-013, FR-015.

Query: `brand`, `subject` (pseudonym), `actor` (pseudonym), `service`, `action`, `outcome`,
`reasonCode`, `category`, `visibility`, `amountMin`, `amountMax` (minor units), `currency`,
`correlation` (a raw id; digested before the query), `from`, `to` (instants, `from` inclusive), `limit`
(default 100, at most `AUDIT_PAGE_LIMIT`), `after` (a cursor).

`200`: `{"records": [Stored…], "next": "<cursor>" | null, "retainedFrom": "<instant>"}` in order of
occurrence, oldest first. `retainedFrom` is set when `from` is older than the brand's retention.

Refused: `403 brand_not_in_roles` (a brand role naming another brand or none), `400 bad_filter`,
`400 bad_cursor`.

Recorded: one installation record per call, action `audit.query`, changes `filters`, `range`, `count`;
not when `service=audit` is the only service asked for.

## `/v1/audit-exports`

- `POST /v1/audit-exports` — `b`, `i`. Body `{"key": "...", "brand": "...", "from": "...", "to": "..."}`.
  `202 {"id": key, "status": "running"}`; the same key again answers the same export. Recorded.
- `GET /v1/audit-exports/{id}` — the caller who may see that brand. `200 {"id", "brand", "format",
  "status": "running" | "done" | "failed", "count"?, "url"?}`; `url` signed for 15 minutes.
- `PUT /v1/audit-exports/format/{brand}` — `o`. Body `{"format": "csv" | "jsonl"}`. Recorded.

## `/v1/audit-identities`

- `POST /v1/audit-identities/who` — `r` for the pseudonym's brand. Body `{"pseudonym", "reasonCode"}`.
  `200 {"brand", "kind", "id"}`; `404 unknown_pseudonym`. Recorded, visible to the brand.
- `POST /v1/audit-identities/pseudonym` — `r` for the brand. Body `{"brand", "kind", "id",
  "reasonCode"}`. `200 {"pseudonym", "issued": bool}`. Recorded.
- Anyone else: `403 not_reidentifier`, and the refusal is recorded (FR-018).

## `/v1/audit-roles`

- `PUT /v1/audit-roles/{role}/{person}` — `o`. Grants the realm role through Keycloak's admin API.
  `role` is `audit_compliance`, `brand_compliance` (with `{"brand"}`) or `reidentify_<slug>`. Recorded.
- `DELETE /v1/audit-roles/{role}/{person}` — `o`. Revokes. Recorded.
- `GET /v1/audit-roles/{role}` — `o`. The holders and how each came to hold it (`granted` or
  `outside`).

## `/v1/audit-retention`

- `GET /v1/audit-retention/{brand}` — `b`, `i`, `o`. `{"years", "installation": 7}`.
- `PUT /v1/audit-retention/{brand}` — `o`. Body `{"years"}`. `409 below_floor` when lower than the
  installation's or the current. Recorded.

## `/v1/audit-status`

`GET /v1/audit-status` — `i`, `o`; `GET /v1/audit-status?brand=` — `b`. Per source: `stored`,
`passedOver`, `lastReceived`, `lagSeconds`, `quarantined`, `skewed`, `retentionYears`, `lastExport`,
`installationSources: {"modules": "read", "erasures": "read", "rollbacks": "not stated",
"restores": "not stated", "grants": "not stated", "secrets": "not stated"}`.

## What is not served

The original's `POST /internal/audit/query` and `epipe audit query` (research R1). No route changes
or deletes a record (SC-008).
