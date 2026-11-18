# Contract: amendments

## `players`: `POST /v1/casino/players/<slug>/{playerId}/deposits` (spec 008 FR-043)

Request gains `requestId?: string` (1 to 64 of `[A-Za-z0-9_-]`; otherwise `400 invalid_request_id`).

| | Today | Amended |
|---|---|---|
| sent to the cashier | no `transactionId` (the cashier mints one) | `transactionId: requestId` when given |
| `200` | `{redirectUrl, paymentId}` | `{redirectUrl, paymentId, cashierTicket, terminals, requestId}` |
| a repeat, same request id and content | a second session | the cashier's first answer, so the same `cashierTicket` |
| a repeat, same request id, another amount | a second session | `409 REQUEST_ID_REUSED`, `service: "ingest"`, as the cashier answers |

`cashierTicket` is the cashier's `session_token`; `terminals` its `available_terminals`,
unchanged; `requestId` its `transactionId`. Checks and their order are unchanged (the deposit
gate, the wallet's sums, responsible gambling, then the cashier). The answer is not logged.

`specs/008-players/contracts/routes.md` and spec 008 FR-043 are edited to say so, with a line
"Amended by spec 027".

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
      { "name": "PLAYER_SITE_BASE", "key": "player-site-base" },
      { "name": "INGEST_URL", "key": "ingest-url" },
      { "name": "PLAYER_SITE_LANGUAGES", "key": "player-site-languages" },
      { "name": "PLAYER_SITE_CURRENCY", "key": "player-site-currency" } ] } }] }
```

New configuration keys: `player-site-languages`, `player-site-currency`. No grant, no topic, no
database, no retention.

## The brand `guyanabet` (spec 001)

`brands/guyanabet/values.json`, after `goldvibe`'s: slug `guyanabet`, name `Guyanabet`,
`realMoney: false`; secrets `guyanabet-st8`, `guyanabet-players`; `player-site-languages: "fr,en"`,
`player-site-currency: "EUR"`, `casino-providers: "eitheror:st8"`, `game-provider-mode: "demo"`,
`reporting-zone: "America/Cayenne"`. A fixture under `tests/fixtures/manifests/` and a row in the
modules tool's render suite.

## The constitution

*Scope*, second bullet, becomes: "The player site is in scope as spec 027: the player SDK and a
reference site, a module of each brand's own project. A site built on the SDK holds no rule of a
service and reaches a service only as the signed-in player. The affiliate portal and the operator
console are out of scope: they change only base URLs." Version 1.3.0, with the amendment note. If
spec 030's amendment has merged, the bullet keeps 030's sentence on the operator console and the
version is 1.4.0.
