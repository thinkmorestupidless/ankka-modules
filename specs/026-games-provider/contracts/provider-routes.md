# Contract: the provider's routes and their classes (FR-024, research R8)

Six endpoints at six prefixes; no two share a prefix; at most two path parameters.

## `/api/operator/v1` — public, brand-signed (research R5)

The stand-in's operator API, byte for byte as `specs/020-game-provider-simulator/contracts/st8-wire.md`
describes it, with one addition: the `x-st8-sign` header is verified against the brand entry found
by `site`, and a failure is `200 {"status":"error","error":"auth_failed"}` with nothing stored.

| Route | What |
|---|---|
| `POST /launch` | body `game_code, currency, token, player, fun_mode, site, player_profile`; answers `{game_url}`; `unknown_game` for a code not registered or not in the brand's games; `{"status":"error","error":"unavailable"}` when the game's health fails; `unknown_game` for `fun_mode` on a game without demo |
| `GET\|POST /games` | the games list in the provider's shape, filtered to the brand's games, each with `fun_mode` per game |
| `POST /bonus/create` | research R9 |
| `GET /bonus/get` | by `bonus_id`, routed by the index |
| `POST /bonus/cancel` | routed by the index |

## `/.well-known` — public

`GET /st8-keys`: the key document (020 FR-011).

## `/health` — public

`GET /`: ready once the key is held and the brand table loaded.

## `/v1/games` — service class, in-project (contracts/game-contract.md)

## `/v1/provider` — operator

| Route | ACL | What |
|---|---|---|
| `GET /brands` | `operatorHolding("games")` on any slug the token names, else `platform_admin` | the brand table |
| `PUT /brands/{slug}` | `operatorHolding(slug, "games")` | write an entry (contracts/brand-entry.md); audited |
| `GET /brands/{slug}` | `operator(slug)` | read an entry |
| `GET /brands/{slug}/sessions` | `operator(slug)` | sessions by brand, paged |
| `GET /brands/{slug}/rounds` | `operator(slug)` | rounds by brand, paged; `?day=YYYY-MM-DD` filters |
| `GET /brands/{slug}/rounds/{token}` | `operator(slug)` | one session's rounds with their callbacks (`RunCallbacks`) |
| `GET /brands/{slug}/unanswered` | `operator(slug)` | unanswered calls with attempts and `nextRetryAt` |
| `POST /brands/{slug}/abandon/{callbackId}` | `operatorHolding(slug, "games")` | body `{reason}`; ends `Deliver`; audited |
| `GET /brands/{slug}/export/{day}` | `operator(slug)` | JSON lines of settled rounds for the day (research R14) |
| `GET /registry` | `operatorHolding("games")` | every registered game with its owner |
| `DELETE /registry/{code}` | `operatorHolding("games")` | an operator removes a game; audited |
| `GET /keys` | `operatorHolding("games")` | current, next, withdrawn ids |
| `POST /keys/start` \| `/keys/switch` \| `/keys/withdraw` | `operatorHolding("games")` | the three acts (FR-020); audited |

`operatorHolding("games")` with no slug: the token holds the `games` role at the eitheror realm;
the brand roles are not required. `Routes` gains `installationOperatorHolding(roles*)` for it.

## Route classes, by spec 001 FR-020

| Class | Routes |
|---|---|
| public | `/api/operator/v1/*` (admitted by the brand's signature), `/.well-known/*`, `/health` |
| service | `/v1/games/*` |
| operator | `/v1/provider/*` |
| player | none: the provider has no player routes; the game page is the game service's |
