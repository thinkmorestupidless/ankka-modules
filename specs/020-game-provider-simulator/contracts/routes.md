# Contract: the simulator's routes and their classes (FR-027)

Every route is one of two of spec 001's classes, declared through `contract.access.Routes` and
nothing else. The service is exposed (`https://game-provider-simulator-tools.<base>`); in local
mode it is `http://localhost:<port>`. Health is ankka's `/_ankka/health` and the stand-in's
`/health` (contracts/st8-wire.md).

## Public (`Routes.public`)

What a brand, a game client or the original's suite calls; unchanged from the stand-in.

| Route | Purpose |
|---|---|
| `POST /api/operator/v1/launch`, `GET /api/operator/v1/games`, `POST /api/operator/v1/bonus/create`, `GET /api/operator/v1/bonus/get`, `POST /api/operator/v1/bonus/cancel` | the provider's operator API (FR-007) |
| `GET /game` | the game page and `check` (FR-008) |
| `GET /.well-known/st8-keys` | the key document (FR-011) |
| `GET /test/session/{token}`, `POST /test/bet`, `POST /test/win`, `POST /test/lose`, `POST /test/cancel`, `POST /test/free-spin`, `GET /test/bonus/{id}` | the instruction routes (FR-014) |
| `GET /health` | the stand-in's health |

## Operator, per brand (`Routes.operator(brand)`, the `{brand}` segment)

Admits an operator with realm role `brand_<brand>` or `platform_admin` (spec 001 routes contract).
A `{brand}` not in the brand table is `404 {"error":"unknown_brand"}`.

| Route | Purpose |
|---|---|
| `PUT /v1/simulator/brands/{brand}/sessions/{token}/scenario` `{name, seed?}` | select a scenario for a session before launch (FR-016), optionally fixing its run's seed (SC-003); `409` after `check` or when one stands |
| `PUT /v1/simulator/brands/{brand}/scenario` `{name}` | the brand's default scenario (FR-016) |
| `GET /v1/simulator/brands/{brand}/runs/{runId}?after=` | the run: callbacks, attempts, answers, timing, steps (FR-020); keyset paging |
| `GET /v1/simulator/brands/{brand}/runs/{runId}/verdict` | expected against observed per step, passed or failed with the first failing step |
| `GET /v1/simulator/brands/{brand}/runs/{runId}/export` | the run as a recording (FR-021, contracts/run-export.md) |
| `POST /v1/simulator/brands/{brand}/load-profiles` `{sessions[], roundsPerMinute, duration, scenario?, seed?}` | start a load profile (research R9); `403 {"error":"not_a_test_brand"}` when the brand is not a test brand (FR-025); `409` when one is running |
| `POST /v1/simulator/brands/{brand}/load-profiles/{id}/stop` | stop: recorded at once; the next tick settles open rounds as losses and ends the profile (research R13) |
| `GET /v1/simulator/brands/{brand}/load-profiles/{id}` | state and report |
| `POST /v1/simulator/brands/{brand}/keys/rotate` `{boundSeconds?}` | rotate the key against this brand's bound (the scenario does the same) |

## Operator, installation-wide (`Routes.operator("tools")`)

Admits realm role `brand_tools` or `platform_admin` (research R2).

| Route | Purpose |
|---|---|
| `GET /v1/simulator/scenarios`, `GET /v1/simulator/scenarios/{name}` | the scenario library |
| `PUT /v1/simulator/scenarios/{name}` | add or replace a scenario as data (FR-019); validated against `scenario.schema.json`, `400` naming the first problem; `default` may not be replaced |
| `GET /v1/simulator/status` | keys, brands, swept runs, last sweep (FR-022) |

## Refusals

A missing or wrong identity on an operator route is refused by the ACL before any handler runs:
`401 unauthenticated`, `403 brand_not_in_roles` or `403 wrong_issuer`, as the routes contract names
them; nothing is recorded in any run (spec edge case). The simulator has no credential scheme of its
own.

## Environment

`ANKKA_AUTH_ISSUERS=staff` with `ANKKA_AUTH_STAFF_ISSUER=<issuerBase>/realms/eitheror` and its JWKS
and audience, rendered by the brands tool from `shared/tools/manifest.json` as for any shared
service; `SIMULATOR_BRANDS`, `SIMULATOR_EXTERNAL_URL` (the address written into `game_url`),
`SIMULATOR_RETENTION_DAYS` (30), `SIMULATOR_GAMES_EXTRA` (optional), `ANKKA_SECRET_KEY` (set by the
platform; locally by the developer).
