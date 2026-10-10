# Data Model: Brands and Projects

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

This feature's data is files in this repository and state the control plane holds. Nothing here
is a service entity. Field names are the manifest's (see `contracts/manifest.schema.json`).

## Brand

One commercial casino; one ankka project. Identified by its slug.

| Field | Type | Rule |
|---|---|---|
| `slug` | string | `^[a-z][a-z0-9-]{0,41}$`; not `payments`, `programme`, `reasoner`, `platform`, `local`, `machine` (FR-001, R2); never reused after deletion |
| `name` | string | shown in the console and the status |
| `realMoney` | boolean | gates on Readiness (FR-040) |
| `realm` | Realm | `casino-<slug>`, derived, not written |

Relationships: has one Manifest; has exactly the Services FR-002 lists (`partner-api` and
`support-agent` may be absent from the pilot's manifest, FR-007); declares every Topic in the
contract; makes and receives Grants; has one Realm.

States of the project: `absent` → `created` → `applied` (every service `Ready`) → `deleted`
(tombstoned; the slug is dead). A brand comes back only by a 041 restore.

## Manifest

The one description a brand is created from. `brands/<slug>/manifest.json`.

| Field | Type | Rule |
|---|---|---|
| `brand` | Brand (slug, name, realMoney) | as above |
| `ankka.runtime` | version | the ankka runtime the services declare; major equals the platform's, minor equal or one below |
| `services[]` | ServicePin | each name in FR-002; no other name (FR-010) |
| `topics[]` | TopicDeclaration | each name in the contract; no other (FR-010); every contract topic present |
| `grants.made[]` | GrantSpec | exactly FR-016's list, no more |
| `grants.received[]` | GrantSpec | what the brand expects to hold from `payments` |
| `secrets[]` | SecretRef | names only: `{name, keys[]}`; values never in the file |
| `keycloak` | `{issuerBase}` | the casino Keycloak's base URL for issuer rendering |

Validation (FR-010) happens entirely before the first control-plane call: schema, slug, service
and topic names against the contract, grants against FR-016, and Readiness when `realMoney`.

## Shared project manifest

`shared/<project>/manifest.json`, applied by the same tool (FR-003). Differs from a brand's: `id`
is one of `payments`, `programme`, `reasoner`; services are FR-003's for that project; topics are
the project's own (`payments.merchants` in `payments`); grants are those made to brands (the
received side of `contracts/grants.md`); issuer `staff` only; no realm and no `realMoney`.

## ServicePin

| Field | Type | Rule |
|---|---|---|
| `name` | string | one of the twelve |
| `image` | string | registry image with an explicit tag; `latest` refused |
| `expose` | boolean | true for the player-facing and operator-facing services; service-only routes are never exposed |
| `objectStorage` | boolean | true only for `kyc` and `reporting` (R12) |
| `env` | map | extra variables; `ANKKA_AUTH_*`, `ANKKA_DB_*`, `ANKKA_OTLP_*` refused (rendered or reserved) |
| `resources` | descriptor resources | passed through |

Rendered into one ankka service descriptor: `name`, `service.image`, `service.runtime`,
`service.hosting` (`embedded` for a Scala service, `process` for the TypeScript `psp-gateway` and `partner-api`), `service.env` (the two issuers for a brand service, `staff` only for
a shared one), `provisionObjectStorage`, `secretKeyRef` entries from `secrets`.

## TopicDeclaration

| Field | Type | Rule |
|---|---|---|
| `name` | string | a contract topic (`contracts/topics.md`) |
| `partitions` | int 1–1000 | can only grow between applies |
| `retention` | `fact` or `{days}` | `fact` = keep everything, or the installation's longest; `casino.sessions` 90, `casino.push` 1 |
| `copies` | 3 / 2 in sync | fixed at declaration (043 FR-008); the tool refuses a change |
| `contract` | `<name>.v1` + schema path | the committed schema under `contract/schemas/` |

States as ankka reports: `waiting for broker` → `provisioned` | `recovered` | `failed`. The tool
adds its own flag: `retention unset` while 043 is absent.

## Family

A kind of fact on a topic. Defined in `contract.facts`, not in the manifest.

| Field | Rule |
|---|---|
| `name` | snake_case as FR-011 spells it; `.v<n>` suffix when n > 1 (FR-013) |
| `topic` | exactly one |
| `producer` | exactly one service in the brand (FR-011) |
| `key` | the entity id the message is about (FR-012) |
| `playerId` | present where a player is involved; absent on `affiliates.attribution` |
| `fields` | the original's names where the family exists there (FR-014); personal ones typed `Personal[A]` |

A family changes only by adding fields. Removing, re-meaning or re-keying is a new family version
beside the old, produced until no manifest lists a reader of the old.

## Grant

| Field | Type | Rule |
|---|---|---|
| `grantee` | `service:<project>/<name>` | the principals FR-016 names |
| `target` | route `{service, method, path}` or topic `{name, right}` or `erasure` | one per grant; no `decrypt` (R7) |
| `direction` | `made` or `received` | |
| `effect` | as 040 reports | `in effect`, `pending`, `declined`, `withdrawn`, `revoked`, `relinquished`, `lapsed`, `route not seen`, `route not grantable`, `rollout needed`, `broker not exposed`; plus the tool's `platform lacks 040` |

Lifecycle (040): `pending` → `accepted` (same organisation: immediate) → `in effect` within two
minutes; ends `withdrawn` (by the granter), `revoked`, `relinquished` (by the grantee) or `lapsed`.

## Realm

| Field | Rule |
|---|---|
| `name` | `casino-<slug>` for players; `eitheror` for operators, affiliates and partners (FR-019) |
| `source` | `brands/_realm/casino-template.json`, the original's template with `${brandId}` substituted |
| `listeners` | `eitheror-audit-listener` posting to `players`' webhook route (FR-022) |
| `roles` | player realm: `player`, `vip_player`, `brand_admin`, `brand_compliance`, `brand_finance`, `brand_support`; the `eitheror` realm gains `brand_<slug>` on apply |

## Route class

Six classes (FR-020), one ACL recipe each in `contract.access` (`contracts/routes.md`). A route
without a class fails the service's spec (SC-003), so the recipes are the only way to declare an
endpoint's ACL in a casino service.

## Readiness (FR-040)

| Condition | Source | Verdict |
|---|---|---|
| backed up, off-cluster copy required and present | 041 project status; installation setting | `met` / `failed` / `unknown` |
| erasure on, keyring ≥ 2 instances | overlay; 042 status when exposed | |
| retention bound ≥ contract's need | overlay's 043 variables; a probe declaration | |
| broker nodes = 3 | overlay's broker replicas; `KafkaTopic` phase | |

`unknown` refuses like `failed`. The verdict and each condition's source head the brand's status.

## Recording and parity test

| Field | Rule |
|---|---|
| `scenario` | the Go test function's name, subtests as steps |
| `requirement` | the FR it establishes, set when a service spec claims it |
| `steps[]` | `{request:{method,path,headers?,body}, response:{status,body}, capture:{name: jsonpath}}` |
| `vary` | `{jsonpath: reason}`; present-only comparison; never added to make a rebuild pass |
| `outbound[]` | what the original sent each stub, with the stub's answer |
| location | `tests/port/<service>/<scenario>.json`; the suite's result file beside them |

A parity test is one munit test per recording, named after the scenario, in the service's own
test kit; it replays the steps with captures substituted, compares each response field by field,
and fails naming the recording and the first differing field. It never calls the original.
