# Data Model: Core and Modules

Nothing here is a service's state: the entities are files this repository keeps, the plans the
tool derives from them, and the two records the vendor keeps that this repository reads.

## Module manifest (`modules/<module>/module.json`, the vendor's shape)

| Field | This repository's rule on top of the vendor's |
|---|---|
| `name` | one of `core`, `casino`, `engagement`, `reporting`, `support`, `partner`, `predictions`, `pool`; the directory's name |
| `kind` | `core` for `core`; `shared` for `pool`; `product` otherwise |
| `dependencies[]` | a product module depends on `core` and on no other product module (FR-002), except `partner` on `casino`; `pool` on `core` |
| `services[]` | each `name` in `Layout.Services` (the union of the modules' services, a service in one module only); `descriptor.image` is `europe-west1-docker.pkg.dev/eitheror/casino/<name>:0.0.0` in the tree; `env[]` names keys from `contract/keys.md` or literals that are no secret; `expose` as spec 001 had it; `objectStorage` for `kyc`, `reporting` |
| `topics[]` | names from `Topics.all`, partitions and retention as `Layout` gives them; a topic in one module only; `schema` absent in the tree, inlined at release |
| `grants.made[]` | literal principals `service:payments/merchant`, `service:payments/psp-gateway`, `service:programme/affiliate-programme`, `service:reasoner/audit` (the shared projects) with targets that are the module's routes or topics; `{module, service}` principals only for a module's grant to the core or the core's to a module, which within one project the agent skips (FR-007), so none is written today |
| `grants.received[]` | the core receives `psp-gateway`'s withdrawals route for `payouts` (`from` literal `service:payments/psp-gateway`) |
| `configuration[]` | keys from `contract/keys.md`; `secret: true` for every credential; every module names the twelve identity keys (contracts/module-manifests.md) |
| `retention` | `casino` 30d, `engagement` 30d, `reporting` 90d, `support` 7d; `core` 30d (the core is never ended alone; the longest promise the product makes) |
| `ending` | none today; `support` may later declare `delays` on open conversations |
| `requires[]` | the core's one entry, conditional on `casino-real-money` (contracts/module-manifests.md); no other module declares any |

`ModuleManifest` (Scala) reads it with the vendor's field names and refuses an unknown field.

## Values (`brands/<slug>/values.json`, `values.v1.json`)

| Field | Meaning |
|---|---|
| `brand` | `slug` (Layout's slug rules), `name`, `realMoney` |
| `keycloak.issuerBase` | the casino Keycloak's base; rendered into the keys `keycloak-issuer-base` and `keycloak-jwks-base` with the overlay's `KEYCLOAK_JWKS_BASE` |
| `hostnames` | optional map service → hostname, applied by the tool's `hostnames` step |
| `secrets[]` | `{name, keys[]}`: the names and keys the brand's services read, as spec 001; every secret key an enabled module names must appear here |
| `values` | key → value for non-secret configuration keys; a key no module names is refused |

Refused naming the entry: `version`, `services`, `grants`, `topics`, `ankka` (spec 001's manifest
fields); a `values` entry whose key a module marks secret; a value matching the vendor's secret
patterns.

## Plan (what `render` produces and `LocalApply` and `AgentModel` consume)

```
Plan(project, tenant, modules: Vector[ModulePlan], values: Set[key], missing: Set[key])
ModulePlan(name, version, dependencies, topics: Vector[TopicPlan], services: Vector[ServiceDescriptor in order], grantsMade, grantsReceived, retention)
```

Rendering a service: the descriptor as the manifest gives it, `env[].key` → `value` from the
values file (or `secretKeyRef {tenant-values, key}` for a secret key), plus nothing else: the
`CASINO_*` and `ANKKA_AUTH_*` variables spec 001's tool computed are now keys the core's manifest
names explicitly, so the rendering is a join, not a computation. A missing key leaves the module
out of the plan and names the key in `missing` (FR-009).

## The agent's module entity (the vendor's, read here)

As ankka-vendor 004 `data-model.md` gives it: `modules: Map[module, {status, version, follow,
services: Map[name, state], lastChange, dataKeptUntil}]`, `entitlements`, `values` (key names),
`requests`. `players` passes `GET /vendor/modules` through; `status <slug>` prints it.

## The entitlement (the vendor's, never written here)

`{tenant, module, follow: Channel|Pin, terms, start, end, binding, state}`; this repository's
only act on it is a request through the agent.

## `platform.modules` facts (contract family, v1)

| Field | Type | Values |
|---|---|---|
| `module` | string | a module name |
| `change` | string | `enabled`, `disabled`, `upgraded`, `failed`, `requested`, `values-changed` |
| `version` | string | the release, absent for `requested` and `values-changed` |
| `channel` | string | absent for a pinned entitlement |
| `actor` | string | the vendor's principal or `agent` |
| `at` | timestamp | |

Keyed by `module`; a fact topic (`retention: everything`); declared by the core's manifest with the
grant to `service:reasoner/audit` to consume.

## Relationships

A values file names keys that module manifests declare; a plan joins one values file with the
manifests an entitlement (or `--modules`) names at the versions a channel (or `--version`) gives;
the contract's `Layout` is derived from the manifests; the operator's page is the module entity
through `players`.
