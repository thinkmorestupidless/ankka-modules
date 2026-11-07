# Contract: the brand entry and `shared/games/manifest.json` (FR-001, FR-017)

## A brand entry (`PUT /v1/provider/brands/{slug}`)

```json
{
  "baseAddress": "https://game-provider-goldvibe.127.0.0.1.sslip.io",
  "site": "goldvibe",
  "operatorPublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE…",
  "language": "pt",
  "testBrand": true,
  "games": [],
  "keyDocument": true,
  "providerWaitMillis": 5000,
  "keyRefreshBoundSeconds": 60,
  "caPem": "env:PROVIDER_LOCAL_CA",
  "realMoney": false
}
```

| Field | Required | Rule |
|---|---|---|
| `baseAddress` | yes | where `/eitheror/<callback>` is posted (spec 003 FR-002: a second provider's own prefix, never the root) |
| `site` | yes | unique across entries; what the brand's launches carry |
| `operatorPublicKey` | yes | base64 DER of the brand's operator public key for the `eitheror` provider, as `GET /v1/game-provider/{brand}/providers/eitheror` answers it after the spec 003 amendment |
| `language` | yes | `es` or `pt` |
| `testBrand` | yes | |
| `games` | no (every game) | codes the brand may run |
| `keyDocument` | no (false) | refused `true` unless `testBrand` |
| `providerWaitMillis`, `keyRefreshBoundSeconds` | no | as the simulator's |
| `caPem` | no | `env:<VAR>` or PEM |
| `realMoney` | yes | what the brand's manifest says; with `keyDocument: true` every launch is refused naming the entry (FR-020) |

The write is refused naming the field on a malformed value; the private key is never accepted
(`operatorPrivateKey` in the body is a refusal).

## `shared/games/manifest.json`

```json
{
  "project": {"id": "games", "name": "Games"},
  "ankka": {"runtime": "0.13.0"},
  "keycloak": {"issuerBase": "https://auth-casino.127.0.0.1.sslip.io:8443"},
  "services": [
    {
      "name": "provider",
      "image": "europe-west1-docker.pkg.dev/eitheror/casino/provider:0.1.0",
      "expose": true,
      "env": {
        "PROVIDER_EXTERNAL_URL": "https://provider-games.127.0.0.1.sslip.io:8443",
        "PROVIDER_RETENTION_DAYS": "90",
        "PROVIDER_RETRY_SCHEDULE": "{\"intervals\":[{\"every\":\"30s\",\"for\":\"10m\"},{\"every\":\"10m\"}],\"cap\":\"8h\"}",
        "PROVIDER_LOCAL_CA": "secret:games-provider/LOCAL_CA",
        "ANKKA_AUTH_ISSUERS": "staff",
        "ANKKA_AUTH_STAFF_ISSUER": "setting:STAFF_ISSUER",
        "ANKKA_AUTH_STAFF_JWKS_URL": "setting:STAFF_JWKS_URL",
        "ANKKA_AUTH_STAFF_AUDIENCE": "operator-console"
      }
    }
  ],
  "secrets": [{"name": "games-provider", "keys": ["LOCAL_CA"]}],
  "topics": [{"name": "platform.audit", "partitions": 6, "retention": "fact"}],
  "grants": {"made": []}
}
```

The schema's enums gain `games` (project) and `provider` (service); 027 adds `pools`.
`Layout.SharedProjects("games") = Vector("provider")`. The modules tool applies it on a real-money
installation (`tools` alone is refused there).

The game services' entries (027 onward) follow the same shape with `expose: true`, since the game
page is reached by the player's browser.
