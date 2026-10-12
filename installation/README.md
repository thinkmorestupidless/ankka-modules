# installation

eitheror's own ankka installations and what the casino adds to them. ankka itself is installed from
the ankka repository's overlays (the `ankka-platform` skill: `kind` locally, `kustomization/overlays/cloud`
for a cloud); this directory holds what sits beside it.

| Overlay | Where | Takes real money |
|---|---|---|
| `overlays/dev-kind` | the local kind cluster `ankka` | never |
| `overlays/gke` | eitheror's GKE, behind Cloud Armor | once every condition of spec 001 FR-040 holds |

- `base/keycloak` — the casino's own Keycloak (spec 001 FR-019): the `casino-<slug>` realm per brand and
  the `eitheror` realm, separate from ankka's identity provider, which authenticates ankka's operators only.
- `overlays/<name>/settings.env` — the installation's settings that decide whether a brand may take real
  money (FR-034 to FR-039). kustomize renders them into the `casino-installation` ConfigMap, and the brands
  tool reads the same file for its readiness check (`brands validate --installation <name> --real-money`).
  A setting is what the installation is configured to do; until ankka reports each on its own status
  (specs 041 to 043), this file is the source and the tool says so.

`overlays/dev-kind` routes the casino's Keycloak through ankka's gateway at `auth-casino.<base>` (the
gke overlay needs the same for its own hostname, and `KEYCLOAK_JWKS_BASE`; not yet written). Keycloak's issuer is the
installation's `KEYCLOAK_ISSUER_BASE`, port included; services read its keys at `KEYCLOAK_JWKS_BASE`, inside the
cluster, since a pod may not resolve the public name to Keycloak. The `eitheror` realm is expected to exist before a
brand is applied: the brands tool imports it from `brands/_realm/eitheror-operators.json` when it is
missing, with the staff audience `eitheror-casino` on every client, and adds `brand_<slug>` to it.
dev-kind keeps the development Keycloak's database on a volume, so the realms survive a restart.

The Keycloak administrator's credential is never in this repository: create
`casino-keycloak-admin` (`username`, `password`) in the `casino-identity` namespace out of band.
