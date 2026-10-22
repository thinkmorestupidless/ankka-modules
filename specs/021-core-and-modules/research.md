# Research: Core and Modules

Decisions behind the plan. Each names what was chosen, why, and what else was considered. The
vendor's side is ankka-vendor spec 004 (`specs/004-modules/` there: `contracts/manifest.md`,
`contracts/agent.md`, `contracts/cli.md`); this repository consumes it and asks for nothing beyond
what is listed under "Asked of the vendor".

## R1. The manifest format is the vendor's, and the schema comes from the vendor

**Decision**: `modules/<module>/module.json` is written in ankka-vendor 004's format exactly
(`contracts/manifest.md` there): `name`, `kind`, `dependencies[]` with ranges, `services[]` each
with a full ankka service descriptor and `env[].key` references, `topics[]`, `grants.made[]` and
`grants.received[]`, `configuration[]`, `retention`, `ending`. The modules tool validates it against
the vendor's published JSON schema, `ankka-vendor-manifest.schema.json`, vendored into
`tools/modules/src/main/resources/` with the vendor version it came from in a comment line of
`tools/modules/README.md`, refreshed by `just modules schema-sync` (which runs
`ankka vendor modules schema > …`). On top of the schema the tool applies this repository's
contract: every service name is one `Layout` lists, every topic is `Topics.all`, every grant
target names a route class of `contract.access` or a contract topic, every configuration key is
named in `contract/keys.md`.

**Rationale**: one format, owned by the product that reads it; a drift is caught where it is
cheapest, at validate. A Scala dependency on the vendor's `manifest` artifact was rejected: the
vendor repository is private and publishes no library, and a schema is what every consumer in any
language gets.

**Alternatives**: keeping spec 001's brand manifest schema and translating at publish (two formats
to keep in step); depending on the vendor's Scala module (private, JVM-only).

## R2. What a module manifest carries that the brand manifest carried

| Brand manifest (spec 001) | Module manifest (vendor 004) |
|---|---|
| `services[].image`, `expose`, `objectStorage`, `resources`, `env` | `services[].descriptor`: the same fields as the control plane's descriptor; `env[]` entries name a key (`{name, key}`) or a literal that is no secret; the image's tag is `0.0.0` in the tree and the tag's version in the published manifest (R7) |
| `topics[]` with partitions, retention, the contract schema | `topics[]` as the control plane takes them: `partitions`, `retention` (`everything` for a fact topic, a duration otherwise), and `schema` inlined by the release job from `contract/schemas/<topic>.v1.json` so the agent declares the topic with its contract as the brands tool did |
| `grants.made[]` with literal `service:payments/merchant` principals | `grants.made[]` with the same literal principals today (the shared projects are applied by the modules tool); `{shared: "payments"}` once `payments` is a vendor shared instance (out of scope here) |
| `grants.received[]` including `{erasure: true}` | `grants.received[]` for routes; the erasure grant is not a vendor grant: it is the platform's erasure handler registration (ankka 042), declared on the descriptor, so the module manifest carries no erasure entry |
| `brand.realMoney`, `keycloak.issuerBase`, `secrets[]`, `CASINO_BRAND` | the twelve identity keys every module names (contracts/module-manifests.md), the derived ones written by `values push`; secret keys per service (`st8-api-key` …), all `secret: true`, filled by the brand's own tool, never the vendor |

The core's `grants.made[]` are spec 001 FR-016's five, moved from `Layout.BrandGrantsMade` into
`modules/core/module.json`; `Layout` now reads them from the manifests, which the contract artifact
packages as resources (`Compile / unmanagedResourceDirectories += modules/`), so `Routes.service`
keeps admitting exactly the grantees until ankka 040's `Callers.granted` replaces the list.

## R3. Values: one file per brand, pushed in two halves

**Decision**: `brands/<slug>/values.json` holds `brand` (`slug`, `name`, `realMoney`),
`keycloak.issuerBase`, `hostnames` (per exposed service, optional), `secrets[]` (names and keys,
as before) and `values` (key → value for every non-secret configuration key the enabled modules
name). `just modules values push <slug>` writes the non-secret entries to the brand's tenant
project through the vendor (`ankka vendor tenants values set <tenant> key=value…`), or straight to
the project secret `tenant-values` in local mode; `just modules secrets <slug> --secrets <file>`
writes the secret entries straight to the project (`PUT /projects/{p}/secrets/tenant-values`), as
`brands apply --secrets` did, never through the vendor. `render <slug>` joins values, manifests and
a channel's versions into the descriptors the agent would apply, for a person to read and for
`RenderSuite` to compare with spec 001's output (SC-001).

**Rationale**: the vendor may hold names and non-secret values (vendor 004 FR-016), a brand's
secrets reach its project only from the brand's side. Hostnames stay with the brand because they
are the brand's, applied by the tool's `hostnames` step through the control plane's hostname
routes, not by the agent.

## R4. The tool: `tools/brands` becomes `tools/modules`, reachable as `ankka casino`

**Decision**: the sbt project `brands` is renamed `modules` (`tools/modules/`, package
`io.eitheror.casino.modules`, main class `Main`, binary `ankka-casino` by `JavaAppPackaging` so
`ankka casino …` finds it on the path under ankka 056). Commands: `validate <file>` (a module
manifest or a values file; `--installation <name> --real-money` as before for the values file),
`render <slug> [--channel stable | --version <module>=<v>…]`, `values push <slug>`, `secrets
<slug> --secrets <file>`, `hostnames <slug>`, `realm <slug>`, `apply <slug> --installation
dev-kind` (local mode only: refuses an installation whose `settings.env` names a vendor), `apply
shared/<project>/manifest.json` (unchanged), `status <slug>`, `recordings check`, `specs
established`. Exit codes as before.

Local `apply` composes exactly what the agent would do, from the same `Render`: project, topics,
secrets noted, the core's services in order, then each module the `--modules` flag names, grants,
hostnames, realm. It is how parity, the simulator and a developer's kind cluster run without a
vendor, and `ApplyOnKindSuite` keeps proving it.

**Rationale**: one rendering shared by validate, render, local apply and the suites, so what a
person reads is what runs. The brands tool's `Apply`, `Realm`, `Readiness`, `Status`,
`Recordings`, `Established` and `InMemoryControlPlane` carry over with the manifest type changed.

## R5. The backoffice reads modules through `players`

**Decision**: the operator's modules page reads `GET /v1/casino/brands/{brand}/modules` and posts
`POST /v1/casino/brands/{brand}/modules/requests` on the `players` service, under the `operator`
route class (`brand_<slug>` or `platform_admin` at the `eitheror` realm). `players` answers by
calling the agent's `GET /vendor/modules` and `POST /vendor/requests` inside the project, by its
certificate, and passes the answer through unchanged with the operator's subject as `by` on a
request. No write that enables exists on any installation route (SC-003): `players` serves these
two routes and nothing else under `/modules`.

**Rationale**: an operator signs in at the casino's `eitheror` realm, which the vendor's agent does
not trust (it admits the hub's realm only); a core service that already serves operators is the
bridge, and `players` is the operator-facing core service. Asked of the vendor: the agent admits a
caller in its own project, by certificate, to its `GET` routes and to `POST /vendor/requests`
(ankka-vendor 004 `contracts/agent.md`, amended 2026-10-22).

`services/players` is spec 008's and is not built yet. This spec adds the two routes to spec 008's
route contract and to `contract/access/Routes.md`, and binds US4's scenarios against the contract's
in-memory model; the live proof arrives with spec 008's implementation.

**Alternatives**: the agent trusting a second issuer (the vendor would need the brand's realm: no);
a seventh core service for the page (a service for two routes); the modules tool's `status` (for
engineers, not operators).

## R6. The real-money gate is a requirement of the core's manifest (decided 2026-10-22)

**Decision**: FR-010 is kept as written and met through ankka-vendor 004 FR-019: the core's
manifest carries `requires[]` with `when: casino-real-money = "true"` and the four conditions of
spec 001 FR-035 to FR-038 as installation facts (`backups.target: object-store`,
`backups.copyRequired: true`, `backups.sharesFailureDomain: false`, `erasure.enabled: true`,
`erasure.keyringInstances: 2`, `broker.nodes: 3`, `topics.longestRetention: unbounded`). The
vendor's agent reads them from `GET /installation` on the tenant's control plane after the values
and before applying anything, records the first unmet one on the module entity as `Unfit`, and
evaluates again on each retry; a fact the control plane does not report is unmet. The modules
tool's `validate <values> --installation <name> --real-money` evaluates the same requirements, from
`GET /installation` when the installation is reachable and from the overlay's `settings.env`
otherwise (local mode, CI), with `Readiness` rewritten over the vendor's fact names so the two
checks cannot drift.

**Rationale**: Trevor chose this over a consumer-side gate at the values push (2026-10-22): the
platform enforces it every time, whatever path the values took, and the vendor's console names the
unmet fact. The cost is a dependency: ankka reports backups today and must gain erasure, broker and
retention facts (ankka-vendor's request 4 of ankka, in T5's spec), so FR-010 holds end to end only
once that release and the vendor's `requires` exist; until then the agent, finding the facts
unreported, applies nothing of the core on a real-money brand, which is the safe side of the rule.
The modules tool stays as strict as the agent (decided 2026-10-22, round 2): ankka 057 (in
flight) reports `broker` and `retention`; `erasure` stays "not stated" until ankka spec 042, so a
real-money brand cannot be applied through the vendor until then.

**Alternatives**: the gate at `values push` (bypassable by a direct values write; a file, not the
installation); a fitness route in the `tools` project (a service to read a file).

## R7. Release: one tag, every image, every manifest, no channel moved

**Decision**: `.github/workflows/release.yml` on `v*`: for each `services/<name>/`, that service's
own sbt build publishes its image at the tag (`Docker/publish`, `CASINO_VERSION` from the tag);
then `tools/modules` renders each `modules/<m>/module.json` at the version (image tags `0.0.0` →
the version, topic schemas inlined from `contract/schemas/`), validates it, and publishes it with
`ankka vendor modules publish <rendered> --version <v>` under the release client's credentials
(`ANKKA_VENDOR_CLIENT_ID`, `ANKKA_VENDOR_CLIENT_SECRET`, `ANKKA_VENDOR_URL` from repository
secrets). The job never calls `channels set`. A module whose services are not all built yet
(`players`, `kyc` … until their specs land) fails the job on a missing image, so a tag cannot
publish a core that cannot run. The manifests list every service of the contract
(contracts/release.md, amended 2026-10-22): `CASINO_RELEASE_REQUIRE_BUILDS=1` refuses a release until
every listed service has a build, and until then only dry runs pass.

**Rationale**: FR-004; the vendor's image check refuses an unresolvable image anyway, so the job
failing early is the same rule applied first.

## R8. `platform.modules` is a contract topic with a family

**Decision**: `contract/facts/PlatformModules.scala`: family `platform.modules` v1 with `module`,
`change` (`enabled`, `disabled`, `upgraded`, `failed`, `requested`, `values-changed`), `version`,
`channel`, `actor`, `at`, flat, with no body `type` field (the vendor's `ModuleFacts` shape is the
family; the one exception `Topics` and `FactsSuite` make, E3); the schema written by `just schemas`; listed in `Layout.BrandTopics`
and declared by the core's manifest with the grant to `reasoner/audit` to consume it. The vendor's
agent publishes to it as CloudEvents (vendor 004 FR-006) in exactly this shape, which the vendor's
`ModuleFacts` contract names; `reporting` and `audit` read it as they read `platform.audit`.

## R9. Migration and the shared projects are unchanged in substance

Spec 019's migration writes `brands/spinvibe/values.json` instead of a manifest and asks the
vendor for entitlements `core`, `engagement`, `reporting`, `casino` from the first apply; the
shared projects keep their manifests under `shared/` and the local tool; the simulator stays in
`tools` (spec 020).

## R10. Tests

- `ModuleManifestSuite`: the manifests in `modules/` validate; the broken variants under
  `tools/modules/src/test/resources/manifests/` are refused naming the entry (US2).
- `ValuesSuite`: goldvibe's values validate; a values file with a version, a service, a grant or a
  secret value is refused naming the entry (US3).
- `RenderSuite`: rendering goldvibe's values against `core` and `casino` at a fixed version set
  equals the descriptors, env and grants spec 001's tool produced, kept as the fixture
  `tools/modules/src/test/resources/spec-001-goldvibe/` (SC-001).
- `ModulesFeatures`: `features/brands/modules.feature` bound against `InMemoryControlPlane` and an
  `AgentModel` that implements the vendor's apply order (vendor 004 `contracts/agent.md`) over the
  rendered plans, so every scenario runs in milliseconds; the scenarios that name eitheror staff and
  the vendor's console are bound to the model's entitlement delivery, and the plan says so: the
  end-to-end proof is ankka-vendor's SC-001 run with these manifests (vendor quickstart step 5).
- `ReadOnlyRoutesSuite`: every route the contract lists under `/modules` is `GET` or a request
  `POST`; no enable route exists (SC-003).
- `ModulesOnKindSuite` (in `tools/modules/src/it`, the `kind` job): local apply of the core alone
  with the services that exist, the player and wallet scenarios answer, no game route; then
  `--modules casino`; `features/game-provider/` answers (SC-002, as far as the built services go).
- `ReleaseRenderSuite`: the release rendering rewrites every image to the tag and inlines every
  schema, and the job's dry run (`just release-dry v9.9.9`) calls `publish` once per module and
  `channels set` never (SC-004), against a fake `ankka-vendor` on the path.

## Asked of the vendor (ankka-vendor 004, both amended 2026-10-22)

1. The agent admits a caller in its own project, by certificate, to its `GET` routes and to
   `POST /vendor/requests` (R5).
2. `ankka vendor modules schema` prints the manifest's JSON schema, and the release publishes it as
   `ankka-vendor-manifest.schema.json` (R1).

## Verify at implementation

- The vendor's topic entry accepts an inline `schema` as the control plane's `PUT /projects/{p}/topics/{name}` does (ankka docs: topics); else the agent declares the topic without its contract and the modules tool's `status` says so.
- The control plane's hostname routes (`add_hostname`) for the `hostnames` step.
- The descriptor field that registers the erasure handler (ankka 042) for `players` and `kyc`.

## The quickstart, as run (T030, 2026-10-22)

- Step 1 ran green on branch `021-core-and-modules`: `just features` (0 findings in 1090 scenarios),
  `just build`, `just test` (contract 186 passed; modules 152 passed; the Go stubs), `just modules
  validate --all`, the `render` example, `just release-dry v9.9.9` (five publishes, core first, no
  channel call).
- Step 2 did not run: the kind cluster `eitheror-dev` holds no ankka installation (its namespaces are
  the original platform's), and installing one is a deployment, which this run may not make.
  `just it` passes with zero tests, since its suites run only with `CASINO_IT=1`; with it,
  `ModulesOnKindSuite` fails at once: `could not reach https://api.127.0.0.1.sslip.io:8443`.
- Step 3 did not run: it waits on ankka-vendor's 004 being merged and its compose stack running.
- Verified at implementation: the vendor's topic entry carries its contract as `contract: {name,
  schema}`, which the release renders; the hostname routes (`PUT /services/{p}/{name}/hostnames/{hostname}`)
  are on ankka's `main` and not in 0.11.0, whose limitations list no custom hostnames, so `hostnames`
  says each one waits until the pin moves; `GET /installation` reports `backups` today and
  not `erasure`, `broker` or `topics`, so a real-money brand is refused on any installation until it
  does (the overlay is read only where the control plane does not answer).
