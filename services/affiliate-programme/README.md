# affiliate-programme

The shared affiliate programme (spec 013), in the project `programme`: one account per affiliate,
a membership per brand, plans, the commission ledger, settlement, tags and documents, managers,
communications and postbacks, for every brand that reports to it. A brand here reports on its own
`affiliates.attribution` topic (spec 012); a brand elsewhere, or one hosted for another company,
posts the same facts to a signed webhook.

## Components

| Kind | Components |
|---|---|
| Event sourced | `Affiliate`, `Membership`, `PlayerAttribution`, `CommissionAccount`, `Payout` |
| Key value | `BrandProgramme`, `Plan`, `FactInbox`, `EmailIndex`, `Token`, `SigningKeys`, `RevocationsStamp`, `Link`, `InviteLink`, `BonusCode`, `ClickRecord`, `AuditEntry`, `Note`, `Creative`, `FraudFlag`, `Notification`, `PostbackOutcome`, `CloseRun` |
| Workflows | `Close` (a period's payouts), `Erasure` (held, then the identity destroyed), `Postback` (retried to 24 h), `EmailSend`, `Import` (a brand's bundle, one step per section) |
| Timed actions | `Seed` (at every start), `PostbackRetry`, `ErasureDue` |
| Consumers | `attribution-<slug>` per brand of `PROGRAMME_BRANDS`, `ToLedger`, `Overrides`, `Communications`, `PayoutNotices`, `FlagNotices`, `Provisioning` |
| Views | `Accounts`, `Memberships`, `Players`, `PlayerFacts`, `Plans`, `Brands`, `Ledger`, `Unsettled`, `Payouts`, `Inbox`, `Links`, `Clicks`, `Revocations`, `BonusCodes`, `InviteLinks`, `Activity`, `FraudFlags`, `Creatives`, `PostbackOutcomes` |
| Endpoints | `/v1/affiliates` (managers, per brand), `/v1/my` (an affiliate and the network below them), `/v1/join` (public), `/v1/programme` (documents, the inbound webhook, and for a platform administrator brands, usage and imports) |

The routes are `specs/013-affiliate-programme/contracts/routes.md`; the webhook is `contracts/inbound.md`.

## Settings and secrets

Settings come from the environment (`Settings.scala`): `PROGRAMME_BRANDS` (rendered by the modules
tool from every brand whose values enable the programme), `PUBLIC_URL`, `AFFILIATE_PORTAL_BASE_URL`,
`AFFILIATE_BACKOFFICE_BASE_URL`, `ATTRIBUTION_BASE_URL`, `CUSTOMERIO_REGION_DEFAULT`, `KEYCLOAK_URL`,
`KEYCLOAK_REALM`, `OUTBOUND_TIMEOUT`, `PROGRAMME_RETENTION_DAYS` (1825) and `PLAIN_HTTP` (local only).

The only secret in the manifest is `keycloak-operators-admin` (the realm administrator who
provisions affiliates and managers). Everything else is in the service's own store, made by the
service and never returned by a route:

| Name | What |
|---|---|
| `email-index-key` | the HMAC key emails are indexed under (made at the first start) |
| `affiliate/<id>/data-key` | each affiliate's key, under which their identity is sealed; destroyed by an erasure |
| `signing-key/<kid>` | the programme's tag signing keys; the public halves are `/v1/programme/documents/keys` |
| `brand/<slug>/webhook-secret` | a brand elsewhere or hosted: the secret its webhook posts are signed with |
| `brand/<slug>/customerio-key` | a brand's Customer.io key, put by a brand or platform administrator as `customerIoKey` on `PUT /v1/affiliates/<slug>/settings` |

## Adding a brand

**A brand here.** Enable the programme in the brand's values, then `just modules render <slug>`: the
programme's `PROGRAMME_BRANDS` gains the slug. Apply the shared project (`just modules apply …`, or
the vendor's agent); the service restarts, its `Seed` timer seeds the brand's `BrandProgramme` and
the consumer `attribution-<slug>` starts reading the brand's topic through `core`'s grant. A
platform administrator then sets its plans and settings through `/v1/affiliates/<slug>/…`.

**A hosted brand.** No restart: a platform administrator posts it.

```bash
curl -X POST $PROGRAMME/v1/programme/brands -H "authorization: Bearer $ADMIN" -d '{
  "brandId": "acme", "name": "Acme", "reportingZone": "Europe/Malta",
  "settings": {…}, "programme": {…}, "defaultPlan": {…}, "firstManager": {"email": "…"}
}'
```

The answer carries `webhookUrl` and `webhookSecret`, the secret shown this once. The brand posts its
facts there, signed `sha256=<HMAC of the body>`; `GET /v1/programme/brands/<slug>/usage?month=` is
what it is invoiced from. `PATCH /v1/programme/brands/<slug>` with `retired: true` stops it.

**A brand's existing affiliates.** `tests/original/programme-export` writes a brand's bundle from
the original; `POST /v1/programme/imports/<slug>` imports it (research.md, *The import's bundle*).

## Running and testing

```bash
just programme-test     # unit suites
just programme-it       # integration and feature suites (Docker)
just programme-parity   # parity: tests/port/programme and the import's export (Docker)
just programme-run      # locally on 9018 (quickstart §6)
just programme-walk     # against it: one CPA end to end, a local issuer and a hosted brand's webhook
```

Each integration suite shares one service per JVM (`SharedKit`), except those that move a clock or
start their own (build.sbt's `own`): `LeakSuite`, `LatencySuite`, `CloseSuite`, `DocumentsSuite`,
`DataSubjectSteps`, `ImportSuite`, `ImportSteps`, `ImportParitySuite`. The feature suites bind
`features/programme/` one file each; `port/CoverageSuite` holds every scenario to a suite and a
recording or the driver's `NotDriven`. `ParitySuite` and `ImportParitySuite` fail until the original
is recorded (`tests/port/programme/README.md`).
