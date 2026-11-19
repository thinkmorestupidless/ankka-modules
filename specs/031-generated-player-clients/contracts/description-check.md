# Contract: the description check

## `io.eitheror.casino.contract.port.DescriptionCheck`

```scala
final case class OriginalRoute(method: String, path: Regex, host: Option[String], operationId: String)
final case class Difference(recording: String, step: String, operationId: String, what: String)
final case class Result(differences: Vector[Difference], unrecorded: Vector[String], matched: Int)

def run(
  description: Path,                         // services/<service>/openapi.player.json
  recordings: Vector[Path],                  // every recording under tests/port/
  routes: Vector[OriginalRoute],
  read: (Recording, Step) => Option[Step]    // the parity test's reading; None skips the step
): Result
```

- A step matches a route by method, path pattern and, where given, the original's host.
- A matched step's status must be one the operation names, or one of the platform's (400, 401,
  403, 413, 500, 503, 504). A JSON body must fit the schema of that status's answer. A stream's
  body is not read.
- `what` says which: `status 409 is not named`, or the field and the schema it failed.
- `unrecorded` is every operation no step matched, as `METHOD path`.
- The request is not checked: the original's requests are what the original accepted, and the
  rebuilt route's request differs where a spec says so.

## `port/DescriptionSuite.scala`, in each of the nine services

1. `run(...)` over every recording under `tests/port/`.
2. `differences` is empty, else the suite fails listing them.
3. `unrecorded` equals `tests/port/unrecorded-player-routes/<service>.txt` (lines beginning `#`
   skipped), else the suite fails listing what was added and what was removed.
4. `matched` is above zero for a service the table below gives recordings.

| Service | Its recordings lie in | Read through |
|---|---|---|
| players | every directory (registration, sign-in, verification are in most) | `port/WireTranslation` |
| kyc | `kyc/`, `engagement/` | `port/WireTranslation` |
| rewards | `rewards/`, `engagement/`, `game-provider/` | `port/WireTranslation` |
| engagement | `engagement/` | `port/WireTranslation`; the stream's steps for status only |
| ingest | `ingest/`, `merchant/` (host `merchant`) | as recorded |
| game-provider | `players/`, `rewards/`, `game-provider/`, `engagement/`, `kyc/` (the launch) | as recorded |
| support-agent | `support-agent/` | as recorded; status only |
| responsible-gambling | `engagement/` (23 recordings), `reporting/` (1) | as recorded (V4) |
| wallet | none | — : all three on the unrecorded list |

A step the service's parity test skips or reads through a named divergence is read the same way
here: `read` is built from the service's own `Parity` where it has one.

## The unrecorded list

`tests/port/unrecorded-player-routes/<service>.txt`, one file per service, so that services
described in parallel do not write one file. Written once from the first run, then reviewed: a route
on it that a recording does call under a path the table missed is a missing row in the table, not
an unrecorded route. Of the SDK's rows it is expected to hold: resendVerification,
requestPasswordReset, startDeposit, country, acknowledgeRealityCheck, abandonCashier, games, game,
freeRounds, balance.

A route leaves the list when a recording of it is added; the suite then fails until the line is
removed, which is the point.

## The lists as they stand (T047)

Of the 79 player routes the nine services describe, 36 are called by no recording under any
path and 43 are checked against the original's recorded answers (FR-014, SC-004). Each
line was read against every recording through its service's route table; a recorded close, export
and activity export are the operator's backoffice routes, not the player's.

| Service | Described | Unrecorded |
|---|---|---|
| players | 40 | 19 |
| wallet | 3 | 3 |
| ingest | 3 | 1 |
| game-provider | 5 | 4 |
| kyc | 5 | 0 |
| responsible-gambling | 10 | 4 |
| rewards | 10 | 5 |
| engagement | 1 | 0 |
| support-agent | 2 | 0 |
| all | 79 | 36 |

`players.txt`:

```text
DELETE /v1/casino/players/the-brand/emails/{address}
DELETE /v1/casino/players/the-brand/{playerId}/favourites/{gameId}
GET /v1/casino/geo/lookup
GET /v1/casino/players/the-brand/{playerId}/activity-history
GET /v1/casino/players/the-brand/{playerId}/exports/{exportId}
GET /v1/casino/players/the-brand/{playerId}/favourites
GET /v1/casino/players/the-brand/{playerId}/recently-played
POST /v1/casino/players/the-brand/email-change/verify
POST /v1/casino/players/the-brand/emails/{address}/resend
POST /v1/casino/players/the-brand/messages/{messageId}/delete
POST /v1/casino/players/the-brand/password-reset/confirm
POST /v1/casino/players/the-brand/password-reset/request
POST /v1/casino/players/the-brand/resend-verification-email
POST /v1/casino/players/the-brand/{playerId}/close
POST /v1/casino/players/the-brand/{playerId}/deposits
POST /v1/casino/players/the-brand/{playerId}/email-change
POST /v1/casino/players/the-brand/{playerId}/erasure
POST /v1/casino/players/the-brand/{playerId}/exports
POST /v1/casino/players/the-brand/{playerId}/favourites/{gameId}
```

`wallet.txt`:

```text
GET /v1/casino/players/the-brand/wallets/{currency}/balance
GET /v1/casino/players/the-brand/wallets/{currency}/restricted-funds
GET /v1/casino/players/the-brand/wallets/{currency}/transactions
```

`ingest.txt`:

```text
POST /v1/cashier/sessions/{ticket}/abandon
```

`game-provider.txt`:

```text
GET /v1/casino/catalog/developers
GET /v1/casino/catalog/games
GET /v1/casino/catalog/games/{gameCode}
GET /v1/casino/catalog/{brand}/games/popular
```

`responsible-gambling.txt`:

```text
DELETE /v1/casino/rg/the-brand/{player}/reality-check
POST /v1/casino/rg/the-brand/{player}/reality-check/acknowledge
POST /v1/casino/rg/the-brand/{player}/return
PUT /v1/casino/rg/the-brand/{player}/reality-check
```

`rewards.txt`:

```text
GET /v1/casino/brands/the-brand/rewards/{playerId}/free-rounds
GET /v1/casino/brands/the-brand/rewards/{playerId}/grants
GET /v1/casino/brands/the-brand/rewards/{playerId}/history
POST /v1/casino/brands/the-brand/rewards/{playerId}/deselect
POST /v1/casino/brands/the-brand/rewards/{playerId}/switch
```
