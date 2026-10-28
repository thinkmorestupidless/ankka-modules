# Contract: the modules tool, `ankka casino`

`tools/modules`, binary `ankka-casino` (so `ankka casino …` under ankka 056; `just modules …` runs
it from sbt). Reads `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_CA` as the CLI gives them, else
`~/.ankka/config.json`; `ANKKA_VENDOR_URL` for the vendor. Exit codes: 0 done or valid, 1 refused
or failed, 2 the command itself is wrong.

| Command | Does | Calls |
|---|---|---|
| `validate <file>` | a module manifest (`modules/*/module.json`) against the vendored vendor schema and the contract rules, or a values file against `values.v1.json`; `--installation <name> --real-money` checks a real-money brand's conditions as before | none |
| `validate --all` | every manifest and every values file; the CI's check | none |
| `render <slug> [--modules a,b] [--channel <c> \| --version core=1.2.0,casino=1.1.0] [--installation <name>]` | the plan: project, topics, each module's services in order with env rendered, grants made and received, and the keys missing; `--format json` | the vendor for a channel's versions (`ankka vendor modules get`), else none |
| `values push <slug>` | the non-secret values through the vendor; refuses a real-money brand on an unfit installation (research R6) | `ankka vendor tenants values set`; local mode: `PUT /projects/{p}/secrets/tenant-values` |
| `secrets <slug> --secrets <file>` | the secret keys and the named secrets straight to the project | `PUT /projects/{p}/secrets/*` |
| `hostnames <slug>` | the values' hostnames | the control plane's hostname routes |
| `realm <slug>` | the brand's realm and the operator role, as `brands apply` step 8 | the casino's Keycloak |
| `apply <slug> --installation <name> [--modules a,b] [--allow-unbuilt]` | local mode only: the agent's order on an installation whose overlay has no `VENDOR_URL`; refuses otherwise naming the vendor, and refuses a plan naming a service `contract/keys.md` lists as not yet built unless `--allow-unbuilt` | the control plane, as `brands apply` did |
| `apply shared/<project>/manifest.json --installation <name>` | unchanged from spec 001 | |
| `status <slug> [--installation <name>]` | the installation's fitness, then the module entity (through the agent's `GET /vendor/modules` with the person's token, which the agent admits for `platform-admin`), then each service, topic, grant and the realm | the agent; the control plane |
| `recordings check`, `specs established` | unchanged | none |
| `schema-sync` | refreshes `ankka-vendor-manifest.schema.json` from `ankka vendor modules schema` and records the vendor version in `tools/modules/README.md` | the plugin |
| `release render <version> --out <dir>` | every manifest with images at the version and schemas inlined, validated; what `release.yml` publishes | none |

Refusals are one line each on standard error, in the words `contracts/module-manifests.md` and
`contracts/values.md` give, every one found before the first call. `--help` on any level.

## Local mode

An installation is local when its overlay's `settings.env` has no `VENDOR_URL` (dev-kind) and
`ANKKA_VENDOR_URL` is unset. In local mode `apply` plays the agent: project, topics with their
schemas, secrets noted, `core`'s services in manifest order and waited for, then each module of
`--modules` after its dependencies, grants, hostnames, the realm; `status` reads the control
plane instead of a module entity. The test installation `ApplyOnKindSuite` uses is local.

Before any call, `apply` refuses a plan that names a service with no build (`contract/keys.md`'s
"not yet built" table), naming each with its module: the release's `CASINO_RELEASE_REQUIRE_BUILDS`
refusal, made by default. Such a service has no image from this tree; on kind it pulls nothing, or
runs one built on another branch whose topic schemas need not match this tree's (dev-kind,
2026-10-28). `--allow-unbuilt` applies them anyway, from whatever image the installation has.

## Tests

`ModuleManifestSuite`, `ValuesSuite`, `RenderSuite` (SC-001), `ReleaseRenderSuite`,
`ModulesFeatures`, `ReadOnlyRoutesSuite`; `ModulesOnKindSuite` under `just it` and the `kind`
job (SC-002); a staged-binary case proving `ankka-casino --help` runs from the path.
