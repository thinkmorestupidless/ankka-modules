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

## The `tools` project (spec 020)

`shared/tools/manifest.json` deploys the game provider simulator to `dev-kind` or a pre-production
installation; the brands tool refuses it with `--real-money`. Its operator routes admit the `eitheror`
realm's `platform_admin`, the brand's `brand_<slug>` for that brand's runs, and `brand_tools` for the
scenario library and the status (spec 020 research R2). Create the realm role `brand_tools` in the
`eitheror` realm when someone other than a platform administrator runs the simulator; until then
`platform_admin` reaches every route.

## psp-stub on dev-kind (spec 005)

`overlays/dev-kind/psp-stub.yaml` runs the nine payment-provider stubs in the `casino-stubs` namespace, and
`settings.env` names each one as the gateway's base URL for that provider (`setting:<KEY>` in
`shared/payments/manifest.json`; the gke overlay leaves them unset, so a production gateway refuses every
provider by name until an operator sets each). The image is built from `tests/` and `kind load`ed; the stub's
webhooks go back through ankka's gateway by the public name and trust the local certificate authority from
a ConfigMap created out of band. Both commands are at the top of `psp-stub.yaml`. DreamzPay's relay page sends
the player's browser to the stub, which a host browser cannot reach at the cluster address: exercise the kind
installation with a server-reached provider.

Inside the local cluster a gateway hostname resolves to the pod itself. `overlays/dev-kind/gateway-dns.sh`
(run once after ankka's `just up`; idempotent) adds a CoreDNS rewrite so `*.127.0.0.1.sslip.io`
reaches the gateway, on 443, from any pod: what lets the simulator call a brand as an outside caller
(spec 020 research R3).
