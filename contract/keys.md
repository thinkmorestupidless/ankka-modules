# Configuration keys

Every configuration key a module manifest (`modules/<module>/module.json`) may name, by module
(spec 021 FR-002, contracts/module-manifests.md). The modules tool refuses a manifest naming a key
that is not here, and a brand's values (`brands/<slug>/values.json`) naming one. Rows are read by the
tool: keep one row per key and the columns in this order.

- **Derived**: written by `values push` from the brand's values and the installation, never by a
  person; a values file that writes one is refused (contracts/values.md, E1).
- **Secret**: rendered as a `secretKeyRef` to the project secret `tenant-values`, set only by
  `ankka casino secrets <slug> --secrets <file>` and never through the vendor. *Read from* is the line
  of the secrets file (`<secret>/<KEY>=<value>`) it is filled from, `<slug>` being the brand's.

## The identity keys

Named by every brand service of every module, so each module's manifest lists them in its
`configuration[]` (the vendor's format has no templating; spec 001's tool computed these).

| Key | Module | Type | Secret | Read from | Derived from | Env |
|---|---|---|---|---|---|---|
| casino-brand | core | string | no | | `brand.slug` | `CASINO_BRAND` |
| casino-project | core | string | no | | the tenant project id (`Values.tenantProject`) | `CASINO_PROJECT` |
| casino-real-money | core | boolean | no | | `brand.realMoney` | `CASINO_REAL_MONEY` |
| player-site-base | core | url | no | | | `PLAYER_SITE_BASE` |
| keycloak-issuer-base | core | url | no | | `keycloak.issuerBase` | `KEYCLOAK_ISSUER_BASE` |
| keycloak-jwks-base | core | url | no | | the overlay's `KEYCLOAK_JWKS_BASE`, else `keycloak.issuerBase` | `KEYCLOAK_JWKS_BASE` |
| auth-realm | core | string | no | | `casino-<slug>` | `ANKKA_AUTH_REALM` |
| auth-player-issuer | core | url | no | | `<issuerBase>/realms/casino-<slug>` | `ANKKA_AUTH_PLAYER_ISSUER` |
| auth-player-jwks-url | core | url | no | | `<jwksBase>/realms/casino-<slug>/protocol/openid-connect/certs` | `ANKKA_AUTH_PLAYER_JWKS_URL` |
| auth-player-audience | core | string | no | | `casino-<slug>` | `ANKKA_AUTH_PLAYER_AUDIENCE` |
| auth-staff-issuer | core | url | no | | `<issuerBase>/realms/eitheror` | `ANKKA_AUTH_STAFF_ISSUER` |
| auth-staff-jwks-url | core | url | no | | `<jwksBase>/realms/eitheror/protocol/openid-connect/certs` | `ANKKA_AUTH_STAFF_JWKS_URL` |
| ingest-url | core | url | no | | the overlay's `INGEST_URL`, else empty | `INGEST_URL` |

`player-site-base`, `keycloak-issuer-base` and `keycloak-jwks-base` are rendered as
`PLAYER_SITE_BASE`, `KEYCLOAK_ISSUER_BASE` and `KEYCLOAK_JWKS_BASE` beside the six auth keys
(confirmed 2026-10-22): spec 001's tool rendered none of the three, so they are a difference from its
output that `RenderSuite` lists (spec 021 T018).

`ANKKA_AUTH_ISSUERS` (`player,staff`) and `ANKKA_AUTH_STAFF_AUDIENCE` (`eitheror-casino`) are the
same for every brand and are literals in the descriptors, not keys.

## The modules' own keys

| Key | Module | Type | Secret | Read from | Derived from | Env |
|---|---|---|---|---|---|---|
| kyc-provider-key | core | string | yes | `<slug>-kyc/API_KEY` | | `KYC_PROVIDER_KEY` |
| players-realm-admin | core | string | yes | `<slug>-players/REALM_ADMIN` | | `PLAYERS_REALM_ADMIN` |
| players-webhook-secret | core | string | yes | `<slug>-players/WEBHOOK_SECRET` | | `PLAYERS_WEBHOOK_SECRET` |
| players-realm-secret | core | string | yes | `<slug>-players/REALM_SECRET` | | `PLAYERS_REALM_SECRET` |
| players-email-token | core | string | yes | `<slug>-players/EMAIL_TOKEN` | | `PLAYERS_EMAIL_TOKEN` |
| players-lookup-key | core | string | yes | `<slug>-players/LOOKUP_KEY` | | `PLAYERS_LOOKUP_KEY` |
| players-merchant-key | core | string | yes | `<slug>-players/MERCHANT_KEY` | | `PLAYERS_MERCHANT_KEY` |
| game-provider-mode | casino | string | no | | | `GAME_PROVIDER_MODE` |
| st8-operator-id | casino | string | no | | | `ST8_OPERATOR_ID` |
| st8-api-key | casino | string | yes | `<slug>-st8/API_KEY` | | `ST8_API_KEY` |
| push-provider-key | engagement | string | yes | `<slug>-push/API_KEY` | | `PUSH_PROVIDER_KEY` |
| model-provider-key | support | string | yes | `<slug>-model/API_KEY` | | `MODEL_PROVIDER_KEY` |

## Not yet built

Services a manifest names whose specs have not built them yet: no `services/<name>/build.sbt`
exists. `ModuleManifestSuite` accepts a service in a manifest only when its build exists or it is
listed here; a service leaves this table in the commit that adds its build. A release fails before
any publish while a manifest it publishes names one of these (contracts/release.md).

| Service | Module | Spec |
|---|---|---|
| kyc | core | 009 |
| payouts | core | 007 |
| attribution | core | 012 |
| rewards | casino | 011 |
| engagement | engagement | 014 |
| reporting | reporting | 015 |
| support-agent | support | 017 |
