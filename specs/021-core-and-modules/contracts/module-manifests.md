# Contract: the module manifests

Five manifests in the vendor's format (ankka-vendor 004 `contracts/manifest.md`), one directory
each under `modules/`. Every entry below is what the tree holds; images carry `0.0.0` until the
release renders the tag's version.

## `core` (kind `core`)

| Service | From the brand manifest | Keys named in `env` |
|---|---|---|
| `wallet` | expose, `medium` | `casino-brand`, `casino-project`, `casino-real-money`, `keycloak-issuer-base`, `keycloak-jwks-base` |
| `players` | expose; erasure handler | the same, plus `player-site-base` |
| `kyc` | expose, object storage; erasure handler | the same, plus `kyc-provider-key` (secret) |
| `responsible-gambling` | expose | the same |
| `payouts` | expose | the same |
| `attribution` | expose | the same |

Topics: `money.transactions` 12, `payments.payments` 6, `casino.players` 6, `casino.sessions` 12
(90d), `casino.withdrawals` 6, `affiliates.attribution` 6, `platform.audit` 12,
`platform.modules` 6. Grants made: spec 001 FR-016's five (`merchant` → wallet deposits and
payment outcome; `psp-gateway` → payouts outcome; `affiliate-programme` consumes
`affiliates.attribution`; `audit` consumes `platform.audit`) plus `audit` consumes
`platform.modules`. Received: `psp-gateway`'s `POST /v1/payments/withdrawals` for `payouts`.
Configuration: the six keys above, `kyc-provider-key` secret. Requires: one entry, `when`
`casino-real-money` equals `"true"`, with `backups.target: object-store`, `backups.copyRequired:
true`, `backups.sharesFailureDomain: false`, `erasure.enabled: true`, `erasure.keyringInstances: 2`,
`broker.nodes: 3`, `topics.longestRetention: unbounded` (spec 001 FR-035 to FR-038, research R6).
Retention: none. Ending: none. Dependencies: none.

## `casino` (kind `product`, depends on `core >=0.3.0 <1.0.0`)

| Service | Notes | Keys |
|---|---|---|
| `game-provider` | expose, `medium` | the core's five identity keys, `st8-operator-id`, `st8-api-key` (secret), `game-provider-mode` |
| `rewards` | expose | the identity keys |

Topics: `casino.rounds` 12, `casino.rewards` 6. Grants: none made (game-provider to wallet is
within the project, FR-007). Retention: 30d.

`partner-api` is not the casino's: the vendor's format has no optional service, so it is the one
service of the module `partner`, which depends on `casino` and whose manifest spec 016 writes when a
partner is contracted.

## `engagement` (kind `product`, depends on `core`)

`engagement`: expose; keys the identity five plus `push-provider-key` (secret). Topics
`casino.push` 6 (1d). Retention 30d.

## `reporting` (kind `product`, depends on `core`)

`reporting`: expose, object storage; keys the identity five. Topics: none of its own (it reads the
core's). Retention 90d.

## `support` (kind `product`, depends on `core`)

`support-agent`: expose; keys the identity five plus `model-provider-key` (secret). Retention 7d.
No `SUPPORT_AGENT_ENABLED` switch: being entitled is the switch (spec 017 FR-001).

## `predictions`, `pool`

Spec 023's and 024's; their manifests are written there to this contract. `predictions` depends
on `core` at the range carrying spec 022.

## Rules the modules tool adds to the vendor's schema

- `refused: service '<name>' is not in the contract` for a service `Layout.Services` lacks;
  `topic`, `grant target`, `configuration key` likewise, naming the entry's path (`services[2].name`).
- `refused: service '<name>' is in modules <a> and <b>`: one service, one module.
- `refused: a product module depends on product module '<b>'` (FR-002); `partner` → `casino` is
  the one exception the contract lists.
- `refused: a value where a key is expected` for `configuration[].value` or a `secretKeyRef`.
- `refused: '<literal>' looks like a secret` with the vendor's patterns.
