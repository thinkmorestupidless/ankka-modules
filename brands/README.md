# brands

One directory per brand, each holding the brand's values, `values.json` (spec 021 FR-003,
`specs/021-core-and-modules/contracts/values.md`): what the brand is, never what it runs. The
schema is `tools/modules/src/main/resources/values.v1.json`; the tool that reads it is
`tools/modules` (`just modules …`, or `ankka casino …`). `_realm/` holds the realm template every
brand's realm is made from.

What a brand runs is the modules its entitlement at ankka-vendor names (`modules/`), at the
versions its channel or pin gives; locally, the modules `--modules` names.

## What the values hold

- `brand`: the slug (the project id locally: lowercase, at most 42 characters, not a shared
  project's name), the name, and whether it takes real money.
- `keycloak.issuerBase`: where the casino's realms are.
- `hostnames`: optional, a hostname per exposed service.
- `secrets`: the names and entries of the secrets the brand's secret keys are read from
  (`contract/keys.md` says which: `st8-api-key` from `<slug>-st8/API_KEY`), never a value.
- `values`: a value for every non-secret configuration key the brand's modules name.

Never a version, a service, a topic or a grant: those are the modules'. Never a derived key
(`casino-brand`, `casino-real-money`, the `auth-*` keys …): `values push` writes those from the
fields above. Never a secret value: those go from a file outside the repository straight to the
brand's project with `ankka casino secrets <slug> --secrets <file>`.

## Adding a brand

1. Copy `goldvibe/` to `<slug>/` and change the slug, the name and the secrets' names.
2. `just modules validate brands/<slug>/values.json`.
3. With a vendor: eitheror staff provision the tenant and entitle it; then `ankka casino values
   push <slug>` and `ankka casino secrets <slug> --secrets <file>`.
4. Locally: `just modules apply <slug> --installation dev-kind [--modules casino]`.

## Taking real money

A brand's `realMoney` flips to `true` only when the installation is fit: the core's manifest
requires it (spec 001 FR-035 to FR-038), the vendor's agent refuses the core before applying
anything otherwise, and the tool checks the same facts first:

```bash
just modules validate brands/<slug>/values.json --installation gke --real-money
```
