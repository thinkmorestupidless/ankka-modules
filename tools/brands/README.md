# casino-brands

The tool that creates a brand, or a shared project, from its manifest on an ankka installation
(spec 001 FR-007 to FR-010, FR-040). ankka has no project manifest of its own, so this composes its
control plane: one project, its topics, one descriptor per service, exposure, grants and the brand's
realm, applied in that order and only where something differs.

```bash
just brands validate brands/goldvibe/manifest.json                      # schema and rules, no call
just brands validate brands/goldvibe/manifest.json --installation gke --real-money
just brands apply brands/goldvibe/manifest.json --installation dev-kind [--secrets ~/casino-secrets.env]
just brands apply shared/payments/manifest.json --installation dev-kind
just brands status goldvibe --installation dev-kind
just brands recordings check                                            # tests/port is well formed (SC-006)
just brands specs established                                           # every requirement names its source (FR-031)
```

Exit codes: 0 done or valid; 1 refused or failed; 2 the command itself is wrong.

## Where it connects

- **ankka**: as the `ankka` CLI does: `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_CA`, else `~/.ankka/config.json`
  (so `ankka login` serves both). `--organization` (or `ANKKA_ORGANIZATION`) names the organisation a
  new project is created in.
- **The casino's Keycloak**: the manifest's `keycloak.issuerBase`, as the master realm's administrator from
  `CASINO_KEYCLOAK_ADMIN_USERNAME` and `CASINO_KEYCLOAK_ADMIN_PASSWORD`. Without them the realm is not
  touched and the apply says so.
- **Secrets**: `--secrets <file>` holds `<secret>/<KEY>=<value>` lines, kept outside the repository. A
  manifest names a secret and its keys, never a value; a service's env refers to one as `secret:<name>/<KEY>`.

## What an apply does, in order

1. **Real money**: a manifest with `realMoney: true` is refused before any call unless the
   installation's `settings.env` meets FR-035 to FR-038; a condition it cannot read refuses too.
2. **Project**: created if absent. A deleted project's id is never reusable (ankka refuses it with 409).
3. **Topics**: each declared with its partitions and its contract `<topic>.v1`, whose schema comes from
   the contract artifact; re-declared only when the partitions or the schema's fingerprint differ.
4. **Secrets**: keys the file supplies and the project lacks are set; missing ones are noted.
5. **Services**: one descriptor each, at the manifest's image and `ankka.runtime`, with the issuers of
   the casino's realms and `CASINO_BRAND`. A service an operator rolled back by hand is not redeployed
   over: the apply notes `manifest behind cluster` until the manifest is changed to match.
6. **Exposure**: the services the manifest marks `expose`.
7. **Grants**: those FR-016 names, made by the brand; those it should receive, checked.
8. **Realm**: `eitheror` from `brands/_realm/eitheror-operators.json` when the installation lacks it,
   `casino-<slug>` from the original's template, and `brand_<slug>` in `eitheror`. Keycloak is reached
   with `CASINO_KEYCLOAK_CA`, else `ANKKA_CA`, as its authority.

`changes: 0` on a second apply means the installation already holds the manifest.

## What "platform lacks" means

The status and the apply say so where ankka 0.11 cannot yet do what the manifest asks. Each line goes
when the ankka spec that removes it ships; nothing here works around one.

| Note | Removed by |
|---|---|
| `retention unset: platform lacks 043` | ankka 043: a topic declared with its retention and copies |
| `grants: platform lacks 040` | ankka 040: grants made and read, `Callers.granted` |
| a personal field is a marker, not encrypted | ankka 042: `Personal[A]`, the keyring, erasure |
| readiness read from `settings.env` | ankka 041–043 reporting backups, keyring and bounds on the project's status |

## Reading `status`

The first lines say whether the installation is fit for real money and each condition with its source.
Then each service (lifecycle, generation, image, and the manifest's pin when they differ), each topic
(phase, partitions, contract and its notes), each grant's effect, and the realm.
