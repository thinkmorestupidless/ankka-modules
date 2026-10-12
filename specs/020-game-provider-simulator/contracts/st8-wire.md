# Contract: the ST8 wire as the simulator speaks it

What the simulator sends to a brand and serves to a brand, byte-compatible with the original's
stand-in (`tests/fake-st8/main.go`) in the default scenario and with the Go harness
(`scenario15_st8_wallet_e2e_test.go`) in a scripted one. Spec 003 FR-002 to FR-006 is the other
side. Line references are to the original's files at the commit the recordings name.

## Conventions the stand-in has, kept

- A callback body is a JSON object of **string values only**, keys in **alphabetical order**, no
  trailing newline (Go `json.Marshal` of `map[string]string`). The body bytes are what is signed.
- An operator-API or instruction answer written as JSON ends with `\n` and HTML-escapes `<`, `>`
  and `&` (Go `json.Encoder`); keys are alphabetical. Error answers are `text/plain; charset=utf-8`
  with the message and a newline: `method not allowed` (405), `bad request` (400), `session not
  found` (404), `wallet token not set — game page not loaded yet` (409, with the em dash),
  `missing token` (400).
- Ids are UUID v4, lower-case hex, no prefix.
- `/health`: `200 {"status":"ready"}` (no newline) once the key is held and, where configured,
  registered; `503 {"status":"starting"}` before.

## Signing (FR-010)

`x-st8-sign: base64( ECDSA-P256-SignASN1( SHA-256(body bytes) ) )`, `Content-Type:
application/json`. The public key is PKIX (SubjectPublicKeyInfo) DER, base64 with padding. The
signature has a random nonce: a recording lists `x-st8-sign` under `vary`.

## Key document (FR-011)

`GET /.well-known/st8-keys` (public):

```json
{"current":{"kid":"k-2026-10-09a","spki":"MFkw..."},"next":{"kid":"k-2026-10-09b","spki":"MFkw..."},"cacheSeconds":60}
```

`next` is absent outside a rotation. A brand on a non-real-money installation names this address as
its provider public key (spec 003 FR-026) and refreshes within 60 s.

## Callbacks sent (FR-009), by shape

Routes under the brand's base address: `/st8/check`, `/st8/balance`, `/st8/player_profile`,
`/st8/debit`, `/st8/credit`, `/st8/cancel`, `/st8/buyin`, `/st8/payout` (spec 003 FR-002 serves
them with and without the prefix; the simulator uses the prefix).

### Shape `stub` (the default scenario; `main.go:767, 865-873, 420-428, 914-922, 962-970, 1011-1017`)

| Callback | Body keys (alphabetical) | Notes |
|---|---|---|
| check | `token` | the launch token; no `game_code` |
| debit | `amount`, `currency`, `game_code`, `provider_kind`, `round`, `token`, `transaction_id` | `provider_kind` `debit`; `token` is the wallet token |
| free-round debit | same keys | `amount` `"0.00"`, `provider_kind` `free_debit`; currency from the bonus, else the session |
| credit (win, loss, free-spin win) | same keys as debit | `provider_kind` `credit`; a loss is `amount` `"0.00"`; `game_code` always the session's; `round` as given, `""` when the instruction has none |
| cancel | `cancel_id`, `currency`, `round`, `token`, `transaction_id` | no amount, kind or game code; `transaction_id` passed through, may be `""` |

The stub never sends `balance`, `player_profile`, `buyin` or `payout`, nor `player`, `site`,
`developer_code`, `round_closed`, `bonus` or `provider`. `amount` is the instruction's string
unchanged (`"1.50"` stays `"1.50"`).

### Shape `harness` (scripted scenarios; `scenario15...go:259-268, 280-293, 319-335, 350-365, 372-412`)

| Callback | Body | Notes |
|---|---|---|
| balance | `player`, `token`, `currency`, `site` | `token` optional (`BalanceWithoutToken`); unknown extra fields allowed (`NewParams`) |
| debit / credit | `token`, `transaction_id`, `round`, `round_closed` (false / true), `currency`, `amount`, `provider_kind`, `player`, `site`, `developer_code`, `game_code`, `bonus: null`, `provider: {amount, currency, metadata: null, player, round, transaction_id}` | the `provider` object is the provider's own view of the round and is not read by the brand |
| cancel | `token`, `cancel_id`, `transaction_id`, `round`, `currency`, `amount`, `player`, `site`, `developer_code`, `game_code` | `LimitedCancel`: `player`, `cancel_id`, `transaction_id`, `currency` only |
| buyin | `player`, `transaction_id`, `currency`, `amount`, `provider_kind` (`correction_debit`), `round: null`, `round_closed: false`, `site`, `developer_code`, `bonus: null`, `provider` | no token |
| payout | as buyin with `provider_kind` `free_credit` and `bonus: {bonus_id, instance_id, status: "finished"}`; the harness's top-up is `payout` with `player`, `token`, `transaction_id`, `currency`, `amount` only | |

In this shape `null` and `false`/`true` are JSON values, not strings, and keys follow the harness's
order; a scenario step may add fields (`extra`) for `NewParams`.

## Answers read (FR-012)

HTTP 200 with `status` ∈ `ok`, `error`, `session_expired`, `player_locked`, `player_not_found`,
`not_enough_money`, `spending_limit`, `transaction_not_found`, `provider_unavailable`,
`auth_failed`; `balance` (two-place string) and `currency` where carried; `check` adds `token`,
`currency`, `game_code`, `country`, `player_id`, `site {id, lobby, deposit}`; `player_profile`
adds `id`, `jurisdiction`, `default_currency`, `reg_country`, `birth_date`, `reg_date`,
`bet_limits` (the last three personal fields are redacted on record, FR-023). HTTP 400 is recorded
as `malformed_body` and never retried. Any other HTTP status, a non-JSON body or a transport failure
is recorded as an error attempt and retried on the schedule.

## Operator API served (FR-007; `main.go:729-758, 593-634, 194-317`)

| Route | Request | Answer |
|---|---|---|
| `POST /api/operator/v1/launch` | `game_code`, `currency`, `token`, `player`, `fun_mode`, `site`, `player_profile` | `200 {"game_url":"<EXTERNAL_URL>/game?token=<token>"}\n`; bad JSON 400; other methods 405 |
| `GET /api/operator/v1/games` (any method, query and `x-st8-sign` ignored) | | `{"categories":[{id,name}],"developers":[{developer_id,name}],"games":[...]}\n`; games in seed order; developers and categories **sorted by id** (the original: map order) |
| `POST /api/operator/v1/bonus/create` | `bonus_id`, `players[]`, `count` (number or numeric string), `value`, `type`, `currency`, `game_codes[]`, `site`, `start_time`, `duration` | unknown game: `{"error":"unknown_game","status":"error"}`; else `{"bonus":{"bonus_id","instances":[{player,remaining,status}]},"status":"ok"}` in `players` order |
| `GET /api/operator/v1/bonus/get?bonus_id=&site=` | | unknown: `{"status":"error"}`; else the envelope |
| `POST /api/operator/v1/bonus/cancel` | `bonus_id`, `player`, `site` | unknown bonus or player: `{"status":"error"}`; else the envelope with that instance `canceled` |

A game object: `bonus_types`, `category`, `code`, `developer` (the slug), `enabled: true`,
`features`, `fun_mode: true`, `name`, `prohibited_territories: []`, `restricted_territories: []`,
`themes`. Categories: `slots` → `Slots`, `table` → `Table Games`.

## Game page (FR-008; `main.go:760-807`)

`GET /game?token=<launch token>`: missing token → `400 missing token`. Otherwise the simulator
sends `check` (`stub` shape) and, on `status` `ok`, stores the answer's `token` as the session's
wallet token and serves the game page (`text/html`, `data-wallet-token` then `data-launch-token`);
on any failure, transport or word, it serves the session-expired page with **HTTP 200**, as the
stand-in does. The two pages are the stand-in's templates (`main.go:689-713`), on the classpath.

## Instruction routes (FR-014; `main.go:809-1024, 361-465`)

Public; bodies as the stand-in reads them; the brand's answer is proxied **verbatim** (status code
and body, `Content-Type` forced to `application/json`); a transport failure is `502 adapter error:
<message>` as text.

| Route | Body | Behaviour |
|---|---|---|
| `GET /test/session/{launchToken}` | | `{"launchToken","walletToken","playerId","currency","gameCode"}\n`; 404 / 400 |
| `POST /test/bet` | `launchToken`, `amount`, `currency`?, `roundId`?, `transactionId`?, `gameCode`? | ids generated when absent; 404 unknown session, 409 before `check` |
| `POST /test/win` | `launchToken`, `roundId`?, `amount`, `currency`?, `transactionId`? | `roundId` not defaulted (`""`) |
| `POST /test/lose` | `launchToken`, `roundId`?, `currency`?, `transactionId`? | credit `"0.00"` |
| `POST /test/cancel` | `launchToken`, `roundId`?, `cancelId`?, `transactionId`?, `currency`? | `cancelId` generated when absent |
| `POST /test/free-spin` | `launchToken`, `winAmount`, `roundId`?, `transactionId`? | no instance: `409 {"error":"no_free_rounds"}`; else free debit then credit; answer `{"credit":<json>,"debit":<json>,"remaining":N,"roundId":..}\n`; adapter failure `502 adapter free_debit error: …` / `502 adapter credit error: …` |
| `GET /test/bonus/{bonusId}` | | `{"bonusId","players","count","value","type","currency","gameCodes","site","instances":{player:{status,remaining}}}\n`; 404 `bonus not found` |

In a scripted scenario an instruction still works: it is a `send` step issued by the tester, and
the session's scenario decides the shape and the schedule. `/test/seed-bonus-offer` and
`/test/reset-wallet-bonus` are not served (spec: dropped).

## Key registration with an original adapter (research R8)

When a brand entry names `registerKeyAt`: `POST <registerKeyAt>` with `{"publicKey":"<spki
base64>"}`, up to 60 attempts 2 s apart until HTTP 200, at start and after a rotation. Never
against a rebuilt brand.
