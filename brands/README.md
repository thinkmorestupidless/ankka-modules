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
4. Locally: `just modules secrets <slug> --installation dev-kind --secrets <file>` once the project
   exists (the first apply makes it, holding each module whose secret key is missing), then
   `just modules apply <slug> --installation dev-kind [--modules casino]`. `just modules render <slug>`
   shows what either would run, and which keys are missing, before anything does.

## Onboarding a brand on one machine

`guyanabet` runs as one project with `ankka local up` (`local/README.md`). Each step of spec 027's
*Onboarding* (`specs/027-frontend-sdk/contracts/amendments.md`), as a command in `local/guyanabet/`
with the project up; `just local-onboard` runs them in order:

| Step | Command |
|---|---|
| the realm `casino-guyanabet` with its `casino-frontend` client, and the operators' realm | `./realm.sh` (the modules tool's realm command at the local identity provider) |
| the secrets | local-only values in `local.json` (`local/generate.py`); none is a secret anywhere else |
| an operator, and a token for the steps below | `./operator-token.sh` (`platform_admin`, `games`, `payments-operator`, scope level `platform`) |
| the sub-brand at the shared payments, its cashier key, and an `EUR` routing tree to the payment provider stub | merchant seeds the sub-brand (`MERCHANT_BRANDS`); `python3 onboard-payments.py` adds the DreamzPay credential and terminal, deploys the tree and issues the key, which `just local-project` writes into `local.json` |
| the house provider's brand entry and the brand's provider configurations, with the site's deposit and lobby addresses | `python3 onboard-games.py` (`eitheror`, and `st8` at the simulator) |
| an email verified in a timed run | `GET /v1/casino/backoffice/guyanabet/players/{id}/verification-link` on `players`, which answers on a brand that takes no real money; `just player-site-it` reads it |

On an installation the same steps run with the installation's addresses; the scripts name theirs
at the top.

## The brands

| Brand | Real money | Runs, locally | Notes |
|---|---|---|---|
| `goldvibe` | no | `core`, and the modules `--modules` names | the development brand of specs 001 to 026 |
| `guyanabet` | no | `--modules casino,engagement,support,player-site` | the reference player site's brand (spec 027): French first, then English, in euros; its domain is not yet owned, so it runs on `dev-kind` under its slug |

## Taking real money

A brand's `realMoney` flips to `true` only when the installation is fit: the core's manifest
requires it (spec 001 FR-035 to FR-038), the vendor's agent refuses the core before applying
anything otherwise, and the tool checks the same facts first:

```bash
just modules validate brands/<slug>/values.json --installation gke --real-money
```
