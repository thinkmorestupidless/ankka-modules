# Contract: a brand's values

`brands/<slug>/values.json`, schema `values.v1.json` in the modules tool's resources.

```json
{
  "brand": { "slug": "goldvibe", "name": "Goldvibe", "realMoney": false },
  "keycloak": { "issuerBase": "https://auth-casino.127.0.0.1.sslip.io:8443" },
  "hostnames": { "wallet": "wallet-goldvibe.127.0.0.1.sslip.io" },
  "secrets": [
    { "name": "goldvibe-st8", "keys": ["OPERATOR_ID", "API_KEY"] }
  ],
  "values": {
    "casino-brand": "goldvibe",
    "casino-real-money": "false",
    "game-provider-mode": "demo",
    "st8-operator-id": "op-1234"
  }
}
```

| Field | Rule | Refusal |
|---|---|---|
| `brand.slug` | `Layout.slugProblems` empty; equals the directory name; not reserved | names the problem |
| `brand.realMoney` | rendered into `casino-real-money`; `true` makes the core's requirements of the installation hold (research R6), evaluated by the agent, and by `validate --installation <name> --real-money` | names the unmet fact and its source |
| `keycloak.issuerBase` | a URL; rendered into `keycloak-issuer-base`; `keycloak-jwks-base` comes from the installation overlay's `KEYCLOAK_JWKS_BASE` | |
| `hostnames` | each key a service some module lists | `service '<x>' is not in the contract` |
| `secrets[]` | names and keys only; every secret configuration key an enabled module names must be covered by a secret entry's `<name>/<KEY>` mapping in `contract/keys.md` | `secret key '<k>' has no secret` |
| `values` | keys from `contract/keys.md`, non-secret; a value matching the vendor's secret patterns is refused | `'<k>' is a secret key: set it with secrets`, `looks like a secret` |
| `version`, `ankka`, `services`, `topics`, `grants` | absent | `'<field>' is the module's, not the brand's` |

`casino-brand`, `casino-project`, `casino-real-money`, `auth-realm`, `auth-player-issuer`,
`auth-player-jwks-url`, `auth-player-audience`, `auth-staff-issuer` and `auth-staff-jwks-url` are
derived (from `brand.slug`, the tenant project id, `brand.realMoney`, `keycloak.issuerBase` and the
overlay's `KEYCLOAK_JWKS_BASE`) and refused if written (E1).

## Where the values go

| Half | Command | Writes |
|---|---|---|
| non-secret `values` plus the derived keys | `ankka casino values push goldvibe` | through the vendor: `ankka vendor tenants values set <tenant> key=value…`; in local mode straight to the project secret `tenant-values` |
| `secrets[]` with the values from `--secrets <file>` (`<secret>/<KEY>=<value>` lines, outside the repository) | `ankka casino secrets goldvibe --secrets ~/casino-secrets.env` | the project secret `tenant-values` entries the module keys name, and the named project secrets, straight to the control plane; never through the vendor |
| `hostnames` | `ankka casino hostnames goldvibe` | the control plane's hostname routes |
| the realm | `ankka casino realm goldvibe` | the casino's Keycloak, as the brands tool did |

The tenant project id is `<organization>-vendor` where the organization is the brand's, as the
vendor provisions it; locally, the project is the slug as spec 001 had it. `Values.tenantProject`
holds the one derivation.
