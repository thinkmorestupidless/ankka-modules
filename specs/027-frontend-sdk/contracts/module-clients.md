# Contract: the module clients

Each row is one entry of a module's `routes.ts` and one function of its client. `{brand}` is filled
by the site server; `{player}` by the client from the session. Audience: `public` (no sign-in),
`player` (the sign-in added by the site server), `ticket` (the cashier ticket, sent by the page in
the header `cashierTicket`). Types are written from the services' own `contracts/routes.md`.

## core

| id | Service | Method and path | Audience | Answer the client gives |
|---|---|---|---|---|
| `register` | players | `POST /v1/casino/players/{brand}/register` | public | `{playerId, status}` |
| `verifyEmail` | players | `POST …/verify-email` | public | `{status}` |
| `resendVerification` | players | `POST …/resend-verification-email` | public | `{result}` |
| `requestPasswordReset` | players | `POST …/password-reset/request` | public | — |
| `signIn` | players | `POST …/login` | public, server only | `{playerId, brand, currency, country, pendingBonusCode}`; the tokens stay in the server |
| `refresh` | players | `POST …/refresh` | public, server only | — |
| `signOut` | players | `POST …/{player}/logout` | player, server only | — |
| `profile` | players | `GET …/{player}` | player | the profile |
| `changeProfile` | players | `PATCH …/{player}` | player | `{updated}` |
| `startDeposit` | players | `POST …/{player}/deposits` | player | `{cashierTicket, terminals, paymentId, requestId, redirectUrl}` |
| `unread` | players | `GET …/{player}/messages?status=unread&page=1&pageSize=1` | player | `{unreadCount}` |
| `gameSession` | players | `GET /v1/casino/sessions/{brand}/{sessionId}` | player | the session, `lastBetAt` |
| `endGameSession` | players | `PATCH /v1/casino/sessions/{brand}/{sessionId}/end` | player | `{status}` |
| `country` | players | `GET /v1/casino/geo/lookup` | public | `{country, dialCode}` |
| `balance` | wallet | `GET /v1/casino/players/{brand}/wallets/{currency}/balance` | player | `{real: Amount, restricted: Amount, available: Amount}` read from major text |
| `protection` | responsible-gambling | `GET /v1/casino/rg/{brand}/{player}/limits?currency=` | player | `{realityCheckIntervalMinutes, isCoolingOff, coolingOffUntil}` |
| `acknowledgeRealityCheck` | responsible-gambling | `POST /v1/casino/rg/{brand}/{player}/reality-check/acknowledge` | player | `{acknowledged}` |
| `submitCashier` | ingest (shared payments, public address) | `POST /v1/cashier/sessions/{ticket}/submit` | ticket | `{paymentId, status}` |
| `abandonCashier` | ingest | `POST /v1/cashier/sessions/{ticket}/abandon` | ticket | the payment |
| `payment` | ingest | `GET /v1/payments/{paymentId}` | ticket | `{status, redirectUrl?, paymentAddress?, cryptoAmount?, cryptoCurrency?, validUntil?, failureReason?}` |

`signIn`, `refresh` and `signOut` are called by the site server's own routes (site-server.md) and
are not in the page's allow-list.

## casino

| id | Service | Method and path | Audience | Answer |
|---|---|---|---|---|
| `launch` | game-provider | `POST /v1/casino/sessions/{brand}/launch` | player | `{gameUrl, sessionId}`; `launchToken` is dropped |
| `games` | game-provider | `GET /v1/casino/catalog/games?…` | public | `{games, total?, nextOffset?}` |
| `game` | game-provider | `GET /v1/casino/catalog/games/{gameCode}` | public | the game, its territories (V5) |
| `freeRounds` | rewards | `GET /v1/casino/brands/{brand}/rewards/{player}/free-rounds?currency=` | player | the games free rounds are held for (V4) |

`games` and `game` are public once spec 003 FR-027 is amended (amendments.md); `launch` and
`freeRounds` stay the player's.

## engagement

| id | Service | Method and path | Audience | Answer |
|---|---|---|---|---|
| `push` | engagement | `GET /v1/stream` | player, stream | events `ready`, `heartbeat`, `signal`, `closing` |

## support

| id | Service | Method and path | Audience | Answer |
|---|---|---|---|---|
| `turn` | support-agent | `POST /v1/casino/agent/{brand}/run/chat_with_player` | player, stream | events `delta`, `confirm_action`, `done`, `error` |
| `decide` | support-agent | `POST /v1/casino/agent/{brand}/resume/{session}` | player, stream | events `action_result`, `delta`, `done`, `error` |

## Every client function

- answers `{ok: true, value}` or `{ok: false, refusal}`; it does not throw for a refusal;
- parses the answer with its Zod shape; a misfit is the refusal `malformed_answer`;
- reads major-unit text into `Amount` (FR-037);
- has one test in `tests/contract/` against the running service (FR-036).
