# Contract: a game service and the house provider (FR-007 to FR-014)

In-project HTTP within `games`, service class (research R7). Amounts are minor units as integers;
the provider renders the wire's decimal strings by the currency's exponent (spec 001 FR-026).
Every route under `/v1/games` is admitted by `Routes.service()`; a route that names a code or a
session is refused `forbidden` when the caller's service name is not the code's registered owner.

## What a game service calls on the provider

| Route | Body | Answer |
|---|---|---|
| `PUT /v1/games/registry/{code}` | `{name, category, bonusTypes[], features[], themes[], restrictedTerritories[], prohibitedTerritories[], deprecationDate?, demo, pageAddress}` | `200 {code, owner}`; `409 {error:"code_taken", owner}` when another service holds it |
| `DELETE /v1/games/registry/{code}` | | `200`; open sessions of the code keep being served |
| `POST /v1/games/sessions/{token}/check` | | `200 {brand, playerId, currency, demo, language, sessionTicket}` on the brand's `ok`; `200 {refused: "<word>"}` on any other answer (`session_expired`, `player_locked`); `404` for a token no session holds; `403` for another game's session |
| `GET /v1/games/sessions/{token}` | | the session as above plus its rounds |
| `POST /v1/games/sessions/{token}/rounds/{roundId}/stake` | `{amount, kind: "debit"\|"free_debit"}` | within the wait: `200 {status:"ok", balance}` or `200 {status:"<refusal word>", balance?}`; past it: `202 {status:"pending", callbackId}`; a demo session: `200 {status:"demo"}` and nothing sent; a round id already used: `409` |
| `POST /v1/games/sessions/{token}/rounds/{roundId}/settle` | `{amount}` (0 for a loss) | `202 {status:"accepted", callbackId}`; `409` when the round is not open or already settled |
| `POST /v1/games/sessions/{token}/rounds/{roundId}/cancel` | | `202 {status:"accepted", callbackId, cancelId}`; `409` when the round has no open debit |
| `GET /v1/games/callbacks/{id}` | | `{state, attempts, answer?: {status, balance}, nextRetryAt?}`; states as data-model Callback |

Rules:

- A stake's `callbackId` is the transaction id the provider assigned; the game keeps it with its
  round. A stake on a session not yet checked is `409 not_checked`.
- `pending` means the brand has not answered; the game treats the round as not open and polls the
  callback (or subscribes to the round, below) before it shows the player an accepted entry.
- A settle on a `pending` round is refused `409 pending` until the stake is answered.
- A `free_debit` stake is sent with amount `0.00`; the game names the amount it would have charged
  in `amount` for its own records, and the provider ignores it on the wire.
- The provider never calls the brand for a demo session; a demo session's rounds are the game's
  alone.

## What the provider calls on a game service

Admitted by `Routes.serviceOf("provider")`. A game service that does not serve these is refused
registration of any game with `demo` or free rounds support.

| Route | Body | Answer |
|---|---|---|
| `POST /v1/game/bonus` | `{bonusId, brand, code, players[], count, value, currency, durationDays?}` | `200 {status:"active"\|"rejected"\|"failed", instances:[{player, remaining, status}], claimDeadline}`; the deadline is never earlier than `durationDays` from now when given |
| `GET /v1/game/bonus/{bonusId}` | | `200 {status, instances[]}`; `404` |
| `POST /v1/game/bonus/{bonusId}/cancel` | `{player}` | `200 {status:"cancelled", instances[]}` |
| `GET /v1/game/health` | | `200` when the game can take a launch; the provider answers `unavailable` to a launch when this fails within 2 s |

## The launch, as the game sees it

The provider answers the brand `game_url = <pageAddress>?token=<launch ticket>`. The game page
loads at the game service, which calls `check` above with the token, then renders. The page may
carry `&lang=es|pt`, which overrides the session's language in the page only.

## Statuses the game must handle

From a stake: `ok`, `not_enough_money`, `spending_limit`, `player_locked`, `session_expired`,
`pending`, `demo`. From a settle or cancel, through the callback: `acknowledged`, `refused`
(`transaction_not_found` for a cancel of an unknown debit), `exhausted`, `abandoned`.

## Test game

`provider-core`'s test kit ships `TestGame`: a game service that registers `test-slot` and
`test-table`, plays stake/settle/cancel on instruction (the simulator's instruction bodies), holds
free-round instances in `BonusBook`, and serves no page. It is a test fixture (`src/test`), never an
image (spec FR-005: nothing scripted is deployable).
