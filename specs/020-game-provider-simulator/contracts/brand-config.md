# Contract: the brand table and the `tools` manifest (FR-024, FR-025)

## `SIMULATOR_BRANDS`

One JSON array in the service's environment, rendered by the brands tool from
`shared/tools/manifest.json`'s `env` (research R10). The simulator refuses to start on a malformed
entry, naming the field.

```json
[
  {
    "slug": "goldvibe",
    "baseAddress": "https://game-provider-goldvibe.127.0.0.1.sslip.io",
    "testBrand": true,
    "providerWaitMillis": 5000,
    "keyRefreshBoundSeconds": 60,
    "caPem": "env:SIMULATOR_LOCAL_CA"
  },
  {
    "slug": "spinvibe-original",
    "baseAddress": "http://host.docker.internal:8095",
    "testBrand": true,
    "providerWaitMillis": 5000,
    "registerKeyAt": "http://host.docker.internal:8095/st8/test/set-public-key"
  }
]
```

| Field | Required | Meaning |
|---|---|---|
| `slug` | yes | the brand as its routes name it; the `{brand}` of the operator routes |
| `baseAddress` | yes | where `/st8/*` is posted: the gateway hostname of the brand's `game-provider` (inside a kind cluster with no port: the gateway listens on 443 there, research R3), or `http://localhost:<port>` in local mode |
| `testBrand` | yes | a load profile is refused unless `true`; a brand whose manifest has `realMoney: true` must be `false` (the quickstart checks) |
| `providerWaitMillis` | no (5000) | the default `wait` when a scenario does not say |
| `keyRefreshBoundSeconds` | no (60) | the brand's bound for the rotated-key scenario (spec 003 FR-026) |
| `caPem` | no | `env:<VAR>` holding the certificate authority to trust for `baseAddress`, as PEM or base64 of the PEM (a local installation's CA, research R3) |
| `registerKeyAt` | no | an original adapter's `set-public-key` route; only for the original (research R8) |
| `site` | no (the slug) | the `site` a launch from this brand carries; how the simulator knows which brand launched (research R13) |

`SIMULATOR_EXTERNAL_URL` is the address written into `game_url` at launch (the stand-in's
`EXTERNAL_URL`): the simulator's own exposed hostname, or `http://localhost:<port>` locally.

## `shared/tools/manifest.json`

The shape of `specs/001-brands-and-projects/contracts/shared-manifest.schema.json`, whose enums
gain `tools` and `game-provider-simulator` (research R1):

```json
{
  "project": {"id": "tools", "name": "Tools"},
  "ankka": {"runtime": "0.11.0"},
  "keycloak": {"issuerBase": "https://auth-casino.127.0.0.1.sslip.io:8443"},
  "services": [
    {
      "name": "game-provider-simulator",
      "image": "europe-west1-docker.pkg.dev/eitheror/casino/game-provider-simulator:0.1.0",
      "expose": true,
      "env": {
        "SIMULATOR_BRANDS": "[...]",
        "SIMULATOR_EXTERNAL_URL": "https://game-provider-simulator-tools.127.0.0.1.sslip.io:8443",
        "SIMULATOR_RETENTION_DAYS": "30"
      }
    }
  ],
  "topics": [],
  "grants": {"made": []}
}
```

On `dev-kind` the CA is the project secret `tools-simulator/LOCAL_CA`, given to the brands tool as
base64 of `~/.ankka/local-ca.crt` in a `--secrets` file kept outside the repository, and a gateway
hostname reaches the gateway from inside the cluster through `installation/overlays/dev-kind/gateway-dns.sh`
(research R3).

No topics, no grants: the simulator is granted nothing under ankka 040 and publishes no facts
(FR-024). The brands tool's `validate` refuses the manifest on a real-money installation overlay
(`gke` with `--real-money`): the simulator is for local, test and pre-production installations
(spec: out of scope).

## In ankka's local mode (FR-026)

No manifest: `ANKKA_DB_NAME=simulator ANKKA_HTTP_PORT=9100 SIMULATOR_BRANDS='[{"slug":"goldvibe",
"baseAddress":"http://localhost:9000","testBrand":true}]' SIMULATOR_EXTERNAL_URL=http://localhost:9100
sbt run` beside a brand's `game-provider` on 9000. `Routes.operator` needs `ANKKA_AUTH_ISSUERS=staff`
and the `staff` issuer even locally: the casino's Keycloak from `installation/base`, as the
`dev-kind` overlay names it (`ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror`,
`ANKKA_AUTH_STAFF_JWKS_URL=<issuer>/protocol/openid-connect/certs`, `ANKKA_AUTH_STAFF_AUDIENCE` as
the realm's operator client names it). A laptop without Keycloak uses the test kit's issuer
(`SimulatorTestKit`), which is why the quickstart's by-hand sections run against `dev-kind`'s.
