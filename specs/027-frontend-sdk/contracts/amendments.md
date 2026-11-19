# Contract: amendments

## `players`: `POST /v1/casino/players/<slug>/{playerId}/deposits` (spec 008 FR-043)

Request gains `requestId?: string` (1 to 64 of `[A-Za-z0-9_-]`; otherwise `400 invalid_request_id`).

| | Today | Amended |
|---|---|---|
| sent to the cashier | no `transactionId` (the cashier mints one) | `transactionId: requestId` when given |
| `200` | `{redirectUrl, paymentId}` | `{redirectUrl, paymentId, cashierTicket, terminals, requestId}` |
| a repeat, same request id and content | a second session | the cashier's first answer, so the same `cashierTicket` |
| a repeat, same request id, another amount | a second session | `409 REQUEST_ID_REUSED`, `service: "ingest"`, as the cashier answers |
| a refusal by the deposit gate | `{error, message, service}` | gains `kycStatus` |
| a refusal by responsible gambling | `{error, message, service, reason?}` | gains `endsAt` (`cooling_off`), and `period`, `limit`, `used`, `remaining` in minor units (`over_limit`) |

`cashierTicket` is the cashier's `session_token`; `terminals` its `available_terminals`,
unchanged; `requestId` its `transactionId`. `paymentId` is kept for callers that read it and is
the cashier's `transactionId`, so it equals `requestId`; it is not a payment's id, which the
submit answers. `Kyc.read`, `KycDecision.Refused` and the responsible-gambling branch of `checked`
carry the added fields. Checks and their order are unchanged (the deposit
gate, the wallet's sums, responsible gambling, then the cashier). The answer is not logged.

The session's `country` and `customerCountry` are the profile's country, else the country
registration found from the caller's address (`networkCountry`): a player registered at the
reference site with only an email address and a password could not deposit, because ingest refuses
a session with no country (found during implementation; the original's page sent the country it
found itself). The reference site's registration asks for a country only when the address names
none (`session.visitorCountry()`, contracts/behaviours.md).

`specs/008-players/contracts/routes.md` and spec 008 FR-043 are edited to say so, with a line
"Amended by spec 027".

## `players`: the game session read answers the last bet (spec 008)

`GET /v1/casino/sessions/<slug>/{sessionId}` answers `lastBetAt` (an instant, or `null` with no
bet), where it answers `null` always today. `GameSessionEntity` gains a command recording a bet's time (a new event; the state's `lastBetAt`),
sent by `RoundsActivity` (`reads/ActivityView.scala`, which consumes `casino.rounds`) for each
round opened or settled under its `sessionId`, with the fact's `occurredAt`; an older time changes
nothing. `SessionRow` gains the field for the list. Both `wire` functions of `GameSessionRoutes`
answer it. `specs/008-players/spec.md` and `contracts/routes.md` gain the line.

## The wallet: major units by the currency's exponent (spec 002)

`Wire.major(minor, currency)` renders through `Amounts.render` in place of two fixed decimals;
each of its calls in `AudienceEndpoints.scala`, the player's and the operator's, passes the
route's currency. For a currency of two
decimals every answer is byte for byte as today; for one of none or three it changes (`100` JPY is
`"100"`, not `"1.00"`). The service routes' integer minor units are untouched. `specs/002-wallet`
gains the line, and the wallet's own suites a row per exponent.

## The contract: a published currency table

A task `contract/schemas` writes `currencies.v1.json` (`{code: exponent}` for every ISO 4217 code
`java.util.Currency` knows, each exponent through `Amounts.exponent`, so a code the JDK gives no
digits is 2 as the wallet treats it) beside the topic schemas; `just schemas` writes it and copies it to
`sdk/player/src/`, and CI fails when the copy differs.

## `game-provider`: the catalogue's reads are public (spec 003 FR-027)

`CatalogEndpoint`'s four reads move from `Routes.player` to `Routes.public`. `?brand=` naming
another brand still answers 404. Answers are unchanged. Spec 003 FR-027 and its
`contracts/routes.md` (lines 29 to 30, which foresaw this) are edited, with "Amended by spec 027".

## The module `player-site` (spec 021)

```json
{ "name": "player-site", "kind": "product",
  "description": "The brand's player site: the reference site built on the player SDK (spec 027)",
  "dependencies": [{ "module": "core", "range": ">=0.4.0 <1.0.0" }],
  "services": [{ "name": "player-site", "descriptor": {
    "image": "europe-west1-docker.pkg.dev/eitheror/casino/player-site:0.0.0",
    "hosting": "web", "expose": true, "resources": { "instanceType": "small" },
    "env": [
            { "name": "CASINO_BRAND", "key": "casino-brand" },
      { "name": "CASINO_MODULES", "key": "casino-modules" },
      { "name": "PLAYER_SITE_BASE", "key": "player-site-base" },
      { "name": "INGEST_URL", "key": "ingest-url" },
      { "name": "PLAYER_SITE_LANGUAGES", "key": "player-site-languages" },
      { "name": "PLAYER_SITE_CURRENCY", "key": "player-site-currency" } ] } }] }
```

New configuration keys: `player-site-languages`, `player-site-currency`. No grant, no topic, no
database, no retention.

## The brand `guyanabet` (spec 001)

```json
{"brand": {"slug": "guyanabet", "name": "Guyanabet", "realMoney": false},
 "keycloak": {"issuerBase": "https://auth-casino.127.0.0.1.sslip.io:8443"},
 "secrets": [{"name": "guyanabet-st8", "keys": ["API_KEY"]},
             {"name": "guyanabet-players", "keys": ["REALM_ADMIN", "WEBHOOK_SECRET", "REALM_SECRET", "EMAIL_TOKEN", "LOOKUP_KEY", "MERCHANT_KEY"]}],
 "values": {"player-site-base": "https://player-site-guyanabet.127.0.0.1.sslip.io:8443",
            "player-site-languages": "fr,en", "player-site-currency": "EUR",
            
            "game-provider-mode": "demo", "casino-providers": "eitheror:st8", "st8-operator-id": "op-1234",
            "reporting-zone": "America/Cayenne"}}
```

Plus whatever key `just modules validate` names as missing for `engagement` and `support`. The
values file names no module: what the brand runs is its entitlement's, locally
`--modules casino,engagement,support,player-site`. `casino-modules` is not written: locally the
modules tool derives it from the modules applied; through a vendor the brand writes it by hand
until spec 021 has the vendor render it (`contract/keys.md`). A fixture under `tests/fixtures/manifests/` and
a case in the modules tool's render suite.

### Onboarding it on `dev-kind`

As `goldvibe` was: the realm `casino-guyanabet` and its `casino-frontend` client
(the modules tool's realm command); the secrets (its secrets command); a sub-brand `guyanabet` at the shared
payments with a cashier key (stored as `MERCHANT_KEY`) and a routing tree offering the payment
provider stub's terminal for `EUR`; the house provider's brand entry for `guyanabet` and the
brand's provider configuration, with its deposit address `…/?deposit=true` and its lobby address
the site's; an email verified in a timed run through the development verification link
`players` answers on a brand that takes no real money.

## The constitution

*Scope*, second bullet, becomes: "The player site is in scope as spec 027: the player SDK and a
reference site, a module of each brand's own project. A site built on the SDK holds no rule of a
service and reaches a service only as the signed-in player. The affiliate portal and the operator
console are out of scope: they change only base URLs." Version 1.3.0, with the amendment note. If
spec 030's amendment has merged, the bullet keeps 030's sentence on the operator console and the
version is 1.4.0.
