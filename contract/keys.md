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
| kyc-max-upload-bytes | core | string | no | | | `KYC_MAX_UPLOAD_BYTES` |
| players-realm-admin | core | string | yes | `<slug>-players/REALM_ADMIN` | | `PLAYERS_REALM_ADMIN` |
| players-webhook-secret | core | string | yes | `<slug>-players/WEBHOOK_SECRET` | | `PLAYERS_WEBHOOK_SECRET` |
| players-realm-secret | core | string | yes | `<slug>-players/REALM_SECRET` | | `PLAYERS_REALM_SECRET` |
| players-email-token | core | string | yes | `<slug>-players/EMAIL_TOKEN` | | `PLAYERS_EMAIL_TOKEN` |
| players-lookup-key | core | string | yes | `<slug>-players/LOOKUP_KEY` | | `PLAYERS_LOOKUP_KEY` |
| players-merchant-key | core | string | yes | `<slug>-players/MERCHANT_KEY` | | `PLAYERS_MERCHANT_KEY` |
| reporting-zone | core | string | no | | | `CASINO_REPORTING_ZONE` |
| casino-modules | core | string | no | | | `CASINO_MODULES` |
| erasure-targets | core | string | no | | | `ERASURE_TARGETS` |
| game-provider-mode | casino | string | no | | | `GAME_PROVIDER_MODE` |
| st8-operator-id | casino | string | no | | | `ST8_OPERATOR_ID` |
| st8-api-key | casino | string | yes | `<slug>-st8/API_KEY` | | `ST8_API_KEY` |
| rewards-code-document-url | casino | string | no | | | `REWARDS_CODE_DOCUMENT_URL` |
| model-provider-key | support | string | yes | `<slug>-model/API_KEY` | | `MODEL_PROVIDER_KEY` |
| predictions-categories | predictions | string | no | | | `PREDICTIONS_CATEGORIES` |
| predictions-currencies | predictions | string | no | | | `PREDICTIONS_CURRENCIES` |
| predictions-max-order-cost | predictions | string | no | | | `PREDICTIONS_MAX_ORDER_COST` |
| predictions-max-position | predictions | string | no | | | `PREDICTIONS_MAX_POSITION` |
| predictions-taker-fee-bps | predictions | string | no | | | `PREDICTIONS_TAKER_FEE_BPS` |
| predictions-gates | predictions | string | no | | | `PREDICTIONS_GATES` |
| public-api-host | public-api | url | no | | | `PUBLIC_API_HOST` |

`reporting-zone`, `casino-modules` and `kyc-max-upload-bytes` have a value when a brand writes none (spec
012 research R7, R12; spec 009 R18; `Values.defaults`): the largest KYC upload is 5,242,880 bytes, the reporting zone is `UTC`, and the modules are those the brand runs, `core`
among them, so the value is never empty (the platform refuses an empty entry; found on kind,
2026-10-28). Locally the tool knows them from the modules it applies; through a vendor it does not, so
a brand that runs the casino module writes `"casino-modules": "core,casino"` until spec 021 has the
vendor render it.

`erasure-targets` (spec 023 R13) names the services of the brand's modules beyond core's own that
erase a player, as `service=prefix` pairs: each planned service with a `POST
…/erasure/players/{player}` row in `contract/access/routes.md`, the prefix the path before it with
the brand filled in. It is `none` when no module adds one. Like `casino-modules`, only the local plan
computes it; through a vendor a brand that runs `predictions` writes
`"erasure-targets": "exchange=/v1/casino/brands/<slug>/exchange"` until spec 021 has the vendor
render it.

The predictions module's keys (spec 023 R7): `predictions-categories` and `predictions-currencies`
are comma lists, and a brand that runs the module names at least one of each; the two limits are
minor units and shares. `predictions-taker-fee-bps` is `0` and `predictions-gates` is
`play-gate,may-bet` when a brand writes none; a fee above `0` stops `markets` at start until
ankka-predictions 0.2.0 charges one (R8). The reasoning mirror's key is added with the mirror,
when ankka-reasoning 0.2.0 is cut (R14).

`public-api-host` (spec 016) is the brand's public API address, without `/v1`, as callers reach it: the
served contract's `servers[0].url` is it with `/v1` appended. The platform gives a service no hostname
of its own, so a brand entitled to `public-api` writes it.

`rewards-code-document-url` (spec 011 R13) is where `rewards` fetches the programme's bonus code
document for the brand. It is `none` when a brand writes none, and `rewards` then matches brand codes
only, until spec 013 publishes a document.

## Not yet built

Services a manifest names whose specs have not built them yet: no `services/<name>/build.sbt`
exists. `ModuleManifestSuite` accepts a service in a manifest only when its build exists or it is
listed here; a service leaves this table in the commit that adds its build. A release fails before
any publish while a manifest it publishes names one of these (contracts/release.md).

| Service | Module | Spec |
|---|---|---|
| support-agent | support | 017 |
