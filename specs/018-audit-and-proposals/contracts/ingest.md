# Ingest: what the audit reads and what it makes of it

## Sources

| Source | Topic | Project | Grant |
|---|---|---|---|
| `brand:<slug>` | `platform.audit` | the brand | `core` grants `service:reasoner/audit` consume |
| `modules:<slug>` | `platform.modules` | the brand | `core` grants the same |
| `payments` | `platform.audit` | `payments` | `shared/payments/manifest.json`, added here |

Each is read as bytes at `AUDIT_READ_GENERATION`: from the earliest at generation 1, from
`AUDIT_READ_FROM` (the restore point) at a later one.

## One message, in order

1. **Decode** with the contract's serializer. Failure → `Quarantined(undecodable)`; next message.
2. **Check**: a brand source's record must name that brand → else `Quarantined(brand_mismatch)`. A
   change whose field is in the contract's `PersonalFields.names`
   (`contract/.../personal/Personal.scala:38`) and carries a before or after value →
   `Quarantined(personal_value)`.
3. **Keep or pass over** (`Keep.decide`, FR-008a). Passed over → `SourceState.received(kept = false)`.
4. **Pseudonymise**: for each person's id (actor, subject, approver): `Pseudonym.issue` then
   `Identity.bind`. A pseudonym already bound to someone else → `Quarantined(pseudonym_conflict)`. Digest the record, correlation and causation ids, and any other id that contains a
   person's real id (research R5).
5. **Store**: `Record.keep(stored)`. `collision` → `Quarantined(collision)` and reported.
6. **Count**: `SourceState.received(at, kept = true, skewed)`.

A step that throws is redelivered; each step is idempotent, so a redelivery changes nothing.

## Visibility

Brand topic → `brand` (an erasure state → `installation`); `payments` → `brand`; modules →
`installation`; the audit's own → `installation` (research R6).

## A module change as a record

| `Stored` | From `ModuleChange` |
|---|---|
| `service` | `module` |
| `action` | `module.<change>` |
| `actorKind`, `actor` | `platform`, `actor` as written (a vendor identity, not a person) |
| `subjectKind`, `subject` | `brand`, the slug |
| `changes` | `version`, `channel` (after only) |
| `category`, `outcome` | `system`, `done` |
| `recordDigest` | digest of `<module>/<change>/<at>` |
