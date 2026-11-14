# The contract's amendments (FR-001, FR-002)

`original/public-api-v1.openapi.yaml` is the original's contract, copied verbatim from
`eitheror-platform/specs/255-casino-public-rest-api/contracts/` on 2026-11-12 (807 lines; last
changed there by `9a1f13cf9`, 2026-10-30). The served contract,
`services/public-api/src/main/resources/public-api-v1.openapi.yaml`, is that file with the changes
below and no others. `ContractAmendmentSuite` parses both and fails on a difference at any JSON path
not listed here (research R2).

## Paths that may differ

| JSON path | Original | Served |
|---|---|---|
| `info.title` | `eitheror casino partner API` | `eitheror casino public API` |
| `info.version` | `1.0.0` | `1.1.0` |
| `info.description` | §Authentication: client-credentials token from the `eitheror` realm, five-minute lifetime; §Scope: "where your agreement covers them"; §Errors: the `jwt_*`, `actor_type_invalid`, `scope_*` and `grant_*` rows | §Authentication: an access key a brand's operator creates in the backoffice, sent as `Authorization: Bearer <key>` on every call, no exchange, no lifetime unless the operator set an expiry; §Scope: "where the key allows them"; §Errors: the rows below |
| `servers[*].url` | `https://partners.pre.eitheror.io/v1` | the brand's public API hostname, from the brand's values (spec 021 FR-003) |
| `components.securitySchemes.bearerAuth` | `{type: http, scheme: bearer, bearerFormat: JWT, description: access token from the client-credentials grant}` | `{type: http, scheme: bearer, bearerFormat: access key, description: an access key from the brand's backoffice}` |
| `components.schemas.Error.properties.error.enum` | `invalid_request, limit_exceeded, jwt_missing, jwt_invalid, jwt_expired, jwt_issuer_mismatch, jwt_verification_failed, actor_type_invalid, scope_missing, scope_invalid, scope_mismatch, grant_absent, grant_disabled, not_found, snapshot_expired, quota_exceeded, unavailable` | `invalid_request, limit_exceeded, key_missing, key_invalid, key_expired, key_disabled, not_found, snapshot_expired, quota_exceeded, unavailable` |
| `components.responses.Unauthorized.description`, `.Forbidden.description`, `.TooManyRequests.description`, `.NotFound.description` | name tokens, grants, scopes and "the partner's brand" | name the access key and "the key's brand" |
| any `summary` or `description` of an operation or a schema property | "the partner's brand", "where the grant entitles it" | "the brand", "where the key allows it" |

Every path, operation id, parameter, schema, `required` list, pattern, enum of a resource and status
code is unchanged.

## The error table

| Status | Codes | Meaning |
|---|---|---|
| 400 | `invalid_request`, `limit_exceeded` | A parameter or identifier is malformed or out of range |
| 401 | `key_missing`, `key_invalid`, `key_expired` | No usable access key: ask the brand for a new one |
| 403 | `key_disabled` | The key exists and is disabled: ask the brand to enable it |
| 404 | `not_found` | No such record in the key's brand |
| 410 | `snapshot_expired` | Start the ranking again from page 1 |
| 429 | `quota_exceeded` | Wait `Retry-After` seconds |
| 503 | `unavailable` | Try again shortly |

The original's `400 scope_invalid` (a scope id that is not a UUID) has no counterpart: a key carries
no scope. Its `401 actor_type_invalid`, `scope_missing`, `scope_invalid` and `403 grant_absent`,
`scope_mismatch` describe a misconfigured realm client, which cannot exist here; a key that is not
this brand's is `key_invalid`.

## Why `/v1` and not `/v2`

Removing error codes and changing the credential breaks a caller written against the original's
`/v1`. The versioning rule (FR-006) would make that a `/v2`. It stays `/v1`, because the only caller
is eitheror's BI system, which must take a new credential at the Spinvibe cutover anyway (spec 019).
No outside party is on the original's `/v1` (its spec 255 *Assumptions*). From this service's first
release, FR-006 holds as written.
