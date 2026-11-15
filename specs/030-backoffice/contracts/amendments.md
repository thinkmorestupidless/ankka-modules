# Amendments to the owning services

Every change this spec makes outside `services/backoffice*`, by service, with the spec it amends.
Each is made with its own tests in that service and one line under *Amended by 030* in that spec.
Three kinds recur and are named by letter:

- **M** the role matrix: each operator route requires one permission,
  `Routes.operatorMay(slug, permission)`, from [roles.md](roles.md); role names and role sets leave
  the service; the service reads the
  brand's roles through `RoleBags` and binds its `features/<area>/operator-roles.feature`.
- **L** lists: `page`, `pageSize` (max 200), `total`, the order FR-009 names.
- **F** freshness: `GET …/status` answering `{views: [{name, asOf, rebuilding}]}` (research R13).

## The contract (`contract/`, spec 001)

| # | Change |
|---|---|
| C1 | `access/OperatorPermissions.scala`: the permissions' names and the role matrix (the default bags); `access/RoleBags.scala`: the kept copy of the brand's roles, refreshed every 10 seconds through a fetch the service supplies, `Unavailable` until first read; `Routes.operatorMay(slug, permission)`; `Routes.operatorHolding` and bare `Routes.operator` leave the brand services |
| C2 | `WriteSchemas` writes `schemas/access/operator-permissions.v1.json` and `schemas/money/currencies.v1.json` |
| C3 | `Layout`: the module `backoffice` and its two services (`backoffice`, `backoffice-state`); the grant a brand makes for merchant's role-setting route (P13, H6) |
| C4 | `facts`: `adjustment` gains optional `cause {kind, ref}` |
| C5 | `brands/_realm/eitheror-operators.json`: role `brand_support`. Each brand gets its own confidential client `backoffice-<slug>` (audience `eitheror-casino`, standard flow, PKCE, the brand's backoffice address), made by `Realm.ensure` with the brand (D3) |

## players (spec 008)

| # | Change |
|---|---|
| P1 | M |
| P2 | L and F on `players`; the list becomes a view query (no 10,000 row cap); filters `country`, `currency`, `potVip`, `online`, `identity`, `birthdayWithinDays`; `facets=true` answers counts per filter value |
| P3 | `players/{p}/activity`: `from`, `to`, `after` cursor, `total` |
| P4 | `players/{p}/notes` GET (list) |
| P5 | `players/{p}/player-sessions` (a player's sessions with their game sessions and data gaps); `sessions` brand-wide list with `status`, `from`, `to`, L; `player-sessions/{sid}/force-close` |
| P6 | `players/{p}/sessions/{sid}/bets` answers the session's bets (501 today), from the rounds facts it already reads |
| P7 | `players/{p}/tags` GET, PUT (manager tags; `tier.change`); a tag is free text of at most 40 characters, not personal data about the player beyond the association |
| P8 | `players/{p}/devices` GET: each device seen at login (first and last seen, platform, browser family, country), `Personal` under `player/<id>`, erased with the player; recorded from the login the service already handles |
| P9 | `players/{p}/games` GET: games played with rounds, staked and won per game, from the rounds facts |
| P10 | `players/{p}/bets`: filters `roundId`, `transactionId`, `type`, `status`; `summary` gains `staked`, `won`, `net` in minor units |
| P11 | `fraud-cases`: L, oldest first for `status=open` |
| P12 | the brand's roles: a `Role` entity per role (name, description, permissions, default or the brand's own), seeded from the role matrix when the brand first starts; `roles` GET (every service of the project, and `roles.read`), `roles/{name}` PUT, DELETE and `roles/{name}/reset` POST (`roles.change`), with the rules of roles.md and an audit record per change |
| P13 | `role-assignments/{operator}` PUT (`roles.give`): refuses when the giver lacks a permission the roles carry, then asks merchant (H6) to set them; audit record |
| P14 | `GET /v1/casino/brands/<slug>/players/modules` on the service endpoint (the project's services, as `roles`): the brand's enabled modules, what `…/modules` answers an operator holding `modules.read`; the backoffice's server reads it for `/api/me` (research, *Found during implementation*, T037) |

## wallet (spec 002)

| # | Change |
|---|---|
| W1 | M (adjustments, turnover override, configuration) |
| W2 | adjustment body gains optional `cause {kind: round | provider_transaction | payment | incident, ref}`; `amountMinor` accepted beside `amount`; the answer gains `before` and `after`; the transaction row gains `cause` and `actor` |
| W3 | `wallet-config/rewards` PUT: `contributionRateByGameType`, `bonusWageringCounts`, `partialSplitsAllowed`, `maxConcurrentAwards` (`config.brand`) |
| W4 | F on transactions |

## payouts (spec 007)

| # | Change |
|---|---|
| Y1 | M, in the ACL |
| Y2 | queue: `sort=oldest`; `counts {pendingReview, needsReview}`. `playerSearch` stays an exact player id: the backoffice resolves a name or an email to player ids through players' search first, so payouts holds no name |
| Y3 | request detail: `outcomes[].operatorId` for a confirmed or failed payment; `balancesAtRequest` and `restrictedAtRequest` (recorded when the request was made) |
| Y4 | `players/{p}/withdrawal-history`: L |
| Y5 | F |

## kyc (spec 009)

| # | Change |
|---|---|
| K1 | M, in the ACL; `identity.read` admits a reader to `GET /{p}` without documents |
| K2 | `queue`: L (`counts` kept) |
| K3 | `{p}/decisions` GET: the player's reviews and decisions with actor and time |
| K4 | F |

## responsible-gambling (spec 010)

| # | Change |
|---|---|
| G1 | M |
| G2 | `cases?status=` brand-wide, L, oldest first |
| G3 | `{p}/history` for operators (the player's own route's data) |
| G4 | F |

## rewards (spec 011)

| # | Change |
|---|---|
| RW1 | M; `GET reward-grants/{rewardId}` admits `reward.read` |
| RW2 | the ineligible answer gains `allowedBy` (what would allow the grant) beside `reason` |
| RW3 | F |

## game-provider (spec 003)

| # | Change |
|---|---|
| GP1 | M |
| GP2 | `rounds`: `total`; `rounds/by-round/{roundId}` (any provider); `rounds/by-transaction/{txId}` |
| GP3 | `unacknowledged`: L |

## engagement (spec 014), support-agent (017), attribution (012), reporting (015), public-api (016)

| # | Change |
|---|---|
| E1 | M in each |
| E2 | attribution `attribution-webhooks/undelivered`: L |
| E3 | reporting: no route change; `Access` reads the matrix |

## merchant (spec 004)

| # | Change |
|---|---|
| H1 | an operator holding `brand_<slug>` reads `/v1/payments/cashier`, `/cashier/summary` and `/v1/payments/{id}` for the sub-brand that names that brand (glossary, *sub-brand*) and no other |
| H2 | `/v1/payments/cashier?reference=` (exact payment reference) |
| H3 | `/v1/sub-brands/{id}/deposit-bounds` GET, PUT: the deposit bounds per method and currency, in minor units; enforced by the deposit workflow before a provider is asked, refused as `DEPOSIT_BELOW_MINIMUM` or `DEPOSIT_ABOVE_MAXIMUM` |
| H4 | `/v1/sub-brands/{id}/psp-credentials/{provider}/test-payment` POST: one payment of the provider's smallest amount on the provider's test mode, through psp-gateway; answers the provider's outcome; never credits a wallet |
| H5 | `/v1/operators`: L |
| H6 | `/v1/operators/{id}/brand-roles/{brand}` PUT, admitted only to that brand's `players` service under the brand's grant: sets the operator's roles for the brand (adds `brand_<slug>`, creates a realm role that does not exist, removes the brand-given roles no longer asked for); refuses a payments role, `platform_admin` and another brand's `brand_<slug>`; changes no role another brand's request gave |

## psp-gateway (spec 005)

| # | Change |
|---|---|
| T1 | `outcomes/undelivered`, `/refused`: `total` |
| T2 | the test payment call H4 uses (the adapter's own smallest test charge; `501` from an adapter with no test mode, shown as such) |

## affiliate-programme (spec 013)

| # | Change |
|---|---|
| A1 | `accounts`: answers `{items, total}` |
| A2 | `accounts/{a}/portal/{view}` GET for managers: the data the affiliate's own portal routes answer, read-only, audited as `VIEWED_AS`; no token of the affiliate's is made |

## The modules tool, realm and delivery (specs 001, 021)

| # | Change |
|---|---|
| D1 | `Render` passes a module service's `hosting: "web"` through to the descriptor (the vendor's schema already allows it; the page's server listens on the default process port and needs no mount) |
| D2 | `modules/backoffice/module.json` (depends on `core`; services `backoffice`, exposed, and `backoffice-state`); the brand's values gain the backoffice's host and the addresses of the shared projects it reads |
| D3 | `Realm.ensure`: upserts `brand_support` and the brand's `backoffice-<slug>` client into an existing realm, its secret into the brand's secrets |
| D4 | `ci.yml`: a `backoffice` filter and a Node job (typecheck, unit, bound features, axe); `release.yml`: an image for a service with a `package.json` |

## The migration (spec 019)

| # | Change |
|---|---|
| X1 | the role mapping of [roles.md](roles.md), published as a report for review before it runs; ambiguous operators get `brand_support` and are listed (FR-008a) |
