# Data model: the player SDK

Nothing here is stored. These are the shapes the package gives a page.

## Amount

`{minor: bigint, currency: string}`. `currency` matches `[A-Z]{3}`; the exponent is the contract's
(`currencies.v1.json`), default 2.

## Refusal

`{status: number, code: RefusalCode, fields, service?: string, words?: string}`. `code` is one of
`codes.ts` or `unknown`; `fields` is typed per code there, under one name per thing whatever the
service called it (`until` for a break's end from `until`, `endsAt` and `coolingOffUntil`).
`words` only from the support agent and a payment provider's rejection (FR-022, FR-054).

## Step

`{kind: string, allowed: readonly ActionName[], …fields}`. A behaviour's steps are a closed union.

## Session

| Step | Fields | Allowed |
|---|---|---|
| `signed_out` | `reason?` | `signIn`, `register`, `verifyEmail`, `resendVerification`, `requestPasswordReset` |
| `signing_in` | | |
| `registering` | | |
| `awaiting_verification` | `email` | `resendVerification`, `verifyEmail`, `signIn` |
| `verifying` | | |
| `signed_in` | `player: {playerId, brand, currency, country}` | `signOut` |
| `idle_warning` | `player`, `signsOutAt` | `stay`, `signOut` |
| `signing_out` | | |
| `refused` | `refusal`, `during` | `back` |

## Deposit

| Step | Fields | Allowed |
|---|---|---|
| `amount` | `presets: Amount[]`, `currency` | `confirm(minor)`, `close` |
| `starting` | `amount`, `requestId` | |
| `refused` | `refusal`, `amount` | `back`, `close`; and `retry` when the start was not answered |
| `choosing_terminal` | `terminals: Terminal[]` | `choose(terminalId)`, `back`, `close` |
| `choosing_coin` | `terminal`, `coins` (the list in `schemes.ts`, the original's) | `chooseCoin(coin)`, `back`, `close` |
| `details` | `terminal`, `needs: FieldName[]`, `known` | `giveDetails(fields)`, `back`, `close` |
| `submitting` | `terminal` | |
| `waiting_at_provider` | `paymentId`, `address` | `close` |
| `waiting_on_crypto` | `paymentId`, `address`, `cryptoAmount` (text, as the cashier sent it), `coin`, `expiresAt` | `close` |
| `waiting` | `paymentId` | `close` |
| `succeeded` | `amount` | `close` |
| `failed` | `code`, `fields`, `words?` | `back`, `close` |

Held by the behaviour and never in a step's fields, in page storage or in an address: the cashier
ticket.

`Terminal`: `{id, name, scheme, gateway?, currencies?}`, from the cashier's `available_terminals`
(`terminalEntityId`, `supportedCurrencies` on the wire).

## Play

| Step | Fields | Allowed |
|---|---|---|
| `idle` | | `launch` |
| `needs_registration` / `needs_sign_in` / `needs_deposit` | `game` | `launch`, `dismiss` |
| `launching` | `game`, `funMode` | |
| `playing` | `game`, `address`, `gameSession`, `hidden: boolean` | `close` |
| `reality_check` | `game`, `gameSession`, `played` (seconds any game has been shown since the first of the open games was launched; hidden time is not counted) | `keepOn`, `stop` |
| `refused` | `refusal`, `game` | `dismiss` |
| `ended` | `game` | |

One play behaviour per open game (FR-059). `site.games` is a `Behaviour` over them with one step,
`{kind: "games", open, shown, allowed: ["pick", "show", "hideAll"]}`, told again whenever a game
opens, closes or is shown; it holds the reality check's one count (time any game is shown since
the last check). `site.subscribe(listener)` is told when `currentDeposit` changes.

| | |
|---|---|
| `open` | the play behaviours of the open games, in the order opened |
| `shown` | the one shown, or none |
| `pick(game, {funMode?})` | a game whose play stands at `launching`, `playing` or `reality_check` is shown and nothing is launched; one at a `needs_*` step has `launch` called again (FR-028's order run again) and is shown; one at `refused` is shown as it stands; else makes a play behaviour, launches, shows it and hides the rest |
| `show(game)`, `hideAll()` | which is shown |

Closing the shown game shows the most recently opened of those still open. A play at `ended` or dismissed leaves `open`.

## Inbox

| Step | Fields | Allowed |
|---|---|---|
| `closed` | | |
| `connecting` | `attempt`, `unread?` | |
| `connected` | `unread: number` | `refresh` |

## Chat

| Step | Fields | Allowed |
|---|---|---|
| `ready` | `turns: Turn[]`, `sessionId?` | `send(text)` |
| `answering` | `turns` (the last growing) | |
| `awaiting_approval` | `turns`, `request: {action, effect?}` (no effect when learnt from a refusal after a page load) | `approve`, `cancel` |
| `deciding` | `turns`, `decision` | |
| `refused` | `refusal`, `turns` | `dismiss` |
| `ended` | `turns`, `words` | `send` |
| `unavailable` | `words` | |

`Turn`: `{from: "player" | "agent", text, interrupted?, result?: {action, success, detail}}`.

## Funds

`{real: Amount | null, restricted: Amount | null, freeRoundsGames: string[] | null}`; `null` is
"not read yet" and counts as playable (FR-027).

## Site configuration

| Field | From | |
|---|---|---|
| `brand` | `CASINO_BRAND` | |
| `local` | `PLAYER_SITE_LOCAL` | optional; set only by `just player-site-run`; plain cookie names and no `Secure` |
| `modules` | `CASINO_MODULES` | the brand's own list; which behaviours are offered |
| `languages` | `PLAYER_SITE_LANGUAGES` | first is the site's first |
| `currency` | `PLAYER_SITE_CURRENCY` | a new registration's |
| `siteBase` | `PLAYER_SITE_BASE` | the origin the server accepts |
| `cashierUrl` | `INGEST_URL` | from the brand's keys, never written by a site builder |
| `presets`, `minimum?`, `maximum?` | the site's code | major units as text or whole numbers, per currency; the bounds trim the presets only |
| `idleMinutes`, `idleWarningSeconds` | the site's code | 30, 30 |

## Changed in services

- `players`' deposit start: the request gains `requestId?`; the answer gains `cashierTicket`,
  `terminals`, `requestId`; a refusal gains the figures. Nothing stored.
- `players`' game session: `lastBetAt: Instant?`, set from each round opened or settled in the session
  (stored in the game session's state and its view row).
- The wallet, every route writing major-unit text: by the currency's exponent. Nothing stored.
