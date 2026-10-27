# ankka-casino, the modules tool

> Vendor manifest schema: `src/main/resources/ankka-vendor-manifest.schema.json`, written by hand from
> ankka-vendor spec 004 `contracts/manifest.md` (branch `004-modules`, the contract as of ca846e8, which adds the broker facts `defaultCopies` and `minInSync`; no published schema yet).
> `ankka casino schema-sync` replaces it with `ankka vendor modules schema` and rewrites this line.

The tool that turns a brand's values and the modules' manifests into what runs in the brand's
project (spec 021, `specs/021-core-and-modules/contracts/modules-tool.md`). Its binary is
`ankka-casino`, so under ankka 056 it is `ankka casino …`; `just modules …` runs it from sbt.
A module's manifest is `modules/<module>/module.json`; a brand's values are
`brands/<slug>/values.json`. With a vendor, ankka-vendor's agent applies the modules a brand is
entitled to and this tool supplies the values; without one (local mode) the tool plays the agent.

```bash
just modules validate --all                                        # every manifest and values file, no call
just modules validate modules/casino/module.json                   # one manifest: the vendor's schema, then the contract
just modules validate brands/goldvibe/values.json --installation gke --real-money
just modules render goldvibe --modules casino --version core=0.3.0,casino=0.3.0
just modules values push goldvibe --organization <tenant>          # through the vendor; locally --installation dev-kind
just modules secrets goldvibe --installation dev-kind --secrets ~/casino-secrets.env
just modules hostnames goldvibe --installation dev-kind
just modules realm goldvibe --installation dev-kind
just modules apply goldvibe --installation dev-kind [--modules casino] # local mode only; refuses a service with no build
just modules apply shared/payments/manifest.json --installation dev-kind
just modules status goldvibe --installation dev-kind
just modules release render v0.3.0 --out target/release            # what release.yml publishes
just modules schema-sync                                           # the vendor's manifest schema, through the plugin
just modules recordings check                                      # tests/port is well formed (spec 001 SC-006)
just modules specs established                                     # every requirement names its source (spec 001 FR-031)
```

Exit codes: 0 done or valid; 1 refused or failed; 2 the command itself is wrong. Every refusal is
found before the first call, one line each.

## Where it connects

- **ankka**: as the `ankka` CLI does: `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_CA`, else `~/.ankka/config.json`
  (so `ankka login` serves both). The login's token is read again before each call and renewed with
  `ankka whoami` within 30 s of expiring or on a `401`, so a long `apply` outlives it; a token given in
  `ANKKA_TOKEN` is used as it is. `--organization` (or `ANKKA_ORGANIZATION`) names the organisation a
  new project is created in locally, and the vendor's tenant otherwise.
- **ankka-vendor**: through its plugin, `ankka vendor …` (or the command `CASINO_VENDOR_COMMAND` names):
  `modules get` for a channel's versions, `tenants values set` for `values push`, `modules schema` for
  `schema-sync`, and in the release, `modules publish`. Never a secret.
- **The casino's Keycloak**: the values' `keycloak.issuerBase`, as the master realm's administrator from
  `CASINO_KEYCLOAK_ADMIN_USERNAME` and `CASINO_KEYCLOAK_ADMIN_PASSWORD`; trusted with `CASINO_KEYCLOAK_CA`,
  else `ANKKA_CA`. Without them the realm is not touched and the tool says so. `CASINO_KEYCLOAK_URL`
  names another Keycloak, such as the one a service runs beside it locally.
- **Secrets**: `--secrets <file>` holds `<secret>/<KEY>=<value>` lines, kept outside the repository. A
  secret key of `contract/keys.md` is filled into the project secret `tenant-values` from the line its
  row names; each secret the values declare is set as it is. Straight to the brand's project.

## Local mode

An installation is local when its overlay's `settings.env` has no `VENDOR_URL` and `ANKKA_VENDOR_URL`
is unset. `apply <slug>` then plays the vendor's agent, in its order:

1. **Real money**: a brand with `realMoney: true` is refused before any write unless the core's
   `requires[]` holds against the installation's facts: `GET /installation` when the control plane
   answers it, else the overlay's settings mapped to the vendor's fact names. A fact not reported is unmet.
2. **Project**: created if absent. A deleted project's id is never reusable.
3. **Values**: the brand's values and the derived keys into `tenant-values` (entries merge, so the
   secret keys `secrets` set stay).
4. **Each module**, `core` first and each after its dependencies: every key it names present in
   `tenant-values`, else the module is held naming the key, and so is every module depending on it;
   its topics with their contracts; its services in manifest order, each waited for until Ready (a
   paused one is noted and not waited out, a failed one fails the module at once, and one applied only
   by `--allow-unbuilt` is not waited for at all); their exposure; the grants it makes (locally the tool
   is the project's owner and makes them).
5. **Hostnames** the values name, where the platform has custom hostnames.
6. **Realm**: `eitheror` from `brands/_realm/eitheror-operators.json` when the installation lacks it,
   `casino-<slug>`, and `brand_<slug>` in `eitheror`.

`changes: 0` on a second apply means the installation already holds it. An installation with a vendor
is refused, naming it: there the agent applies, from the entitlements eitheror staff make in the
vendor's console.

The shared projects (`shared/*/manifest.json`) are applied as spec 001 applied them; a brand manifest
is refused, since a brand is applied from its values.

## What "platform lacks" means

The status and the apply say so where ankka 0.11 cannot yet do what a manifest asks. Each line goes
when the ankka spec that removes it ships; nothing here works around one.

| Note | Removed by |
|---|---|
| `retention unset: platform lacks 043` | ankka 043: a topic declared with its retention and copies |
| `grants: platform lacks 040` | ankka 040: grants made and read, `Callers.granted` |
| `hostname … waits: the platform has no custom hostnames` | ankka's custom hostnames on the installation |
| a fact `GET /installation` does not report is unmet | ankka reporting erasure, broker and topic facts (ankka-vendor's request of ankka) |

## Reading `status`

`status <slug>` says whether the installation is fit for real money and each condition with its
source, then each module (`--modules`, else every module with a service in the project) with its
services (lifecycle, generation, image), topics and grants, then the realm. Through a vendor, the
module entity is the agent's (`GET /vendor/modules`, which players' operator page passes through).
