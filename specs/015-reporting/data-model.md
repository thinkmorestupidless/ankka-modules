# Data model: reporting

Every amount is a `Long` of minor units with its currency. Every id below carries the generation `g`
(research R8) as its first segment, left out here for reading. A day is `YYYY-MM-DD` in the brand's
reporting zone. Nothing here names a person except the `Personal` fields marked **P**, each under
`player/<id>`.

## Intake entities (event sourced; research R3)

Each keeps the ids of its recent facts (`seen`, the last 1,000) and ignores a repeat. A fact id is the
first 16 bytes, in hex, of the SHA-256 of the topic's name and the fact as the contract writes it
(engagement's `factId`; ankka 0.16 gives a consumer no `ce-id`).

| Entity | Id | Fed by | State | Events |
|---|---|---|---|---|
| `WalletBook` | `<player>/<currency>` | `money.transactions` (player families) | lifetime deposits, wagered, won; real and restricted balance; pools; open restricted funds records `{awardId, status, balance, progress, requirement}`; open rounds' days (attribution's `RoundIndex`, the last 500 open rounds); pending holds by request; `seen` | `Moved {factId, family, kind, amount, depositPart, roundId?, reference, day, roundDay?, at, casino: Boolean, accountKind?}`; `RecordsChanged {records}` |
| `Payment` | `<payment id>` | `payments.payments` | player, provider, currency, amount, initiated at, outcome (`captured`, `failed`, `expired`, `abandoned`), refund, dispute `{amount, outcome}` | `Initiated`, `Captured`, `Ended {outcome}`, `Refunded`, `DisputeOpened`, `DisputeClosed {outcome, amount}` |
| `Withdrawal` | `<payout id>` | `casino.withdrawals` | player, gateway, terminal, amount, currency, requested at, approved at, ended at, outcome | `Requested`, `Approved`, `Completed`, `Cancelled`, `Failed`, `Reversed` |
| `Grant` | `<bonus id>` | `casino.rewards` | player, offer id, name, type, currency, amount, granted at, status (`active`, `converted`, `forfeited`, `cancelled`, `expired`), free-rounds win | `Granted`, `Converted`, `Forfeited`, `Cancelled`, `Expired`, `FreeSpinsSettled {win}` |
| `PlayerLog` | `<player>` | `casino.players` | registered at, country, currency, email **P**, status, KYC status, last login, test account, open KYC submissions by document | `Registered`, `StatusChanged`, `KycChanged`, `KycDocumentDecided {document, category, submitted, decided, decision, reason}`, `LoggedIn`, `TestAccountChanged`, `EmailChanged`, `RgCaseOpened`, `RgCaseClosed`, `RgLimitApplied` |
| `Session` (key value) | `<session id>` | `casino.sessions` | kind (`player`, `game`), player, started, ended, close reason, game id, player session id, network address **P**, user agent **P** | — |
| `RoundCheck` (key value) | `<player>/<currency>/<round id>` | `casino.rounds`; `WalletBook`'s `Moved` with a round | provider figures `{provider, providerRoundId, game, gameName, gameType, session, stake, win, outcome, at}`; wallet figures `{stake, win, rollback}`; day | — |
| `MarketBook` | `<market id>` | `exchange.trades` | contracts, notional, trades, traders, positions per `(player, outcome)`, fees, payments, remainder; `seen` by trade and position id | `Traded {tradeId, quantity, notional, legs, fees}`, `PositionPaid`, `RemainderKept` |

`Moved.casino` is false for spec 022's families (research R4); `kind` is the ledger type (`deposit`,
`withdrawal`, `bet`, `win`, `rollback`, `bonus`) or empty.

## The day (event sourced)

`BrandDay`, id `<day>`.

- State: `open | closed`, the zone; per currency:
  - `money`: stakes, wins, GGR, deposit pool GGR, bonus cost, chargebacks, NGR, deposits (count, amount),
    withdrawals (count, amount), registrations;
  - `hours[24]`: GGR and NGR per hour;
  - `providers`: per payment provider, deposit attempts, completed, value; per gateway, withdrawal
    requests, completed, hours-to-completion sum;
  - `games`: per `(game provider, game)`, the provider's stake and win and the wallet's;
  - `predictions`: per market, contracts, notional, trades, fees, resolution payments, remainder, fee
    account credits;
  - liability at close, pending holds at close;
  - `testShare`: the same money sections over test accounts only, subtracted at read when the brand
    excludes test accounts (FR-024);
  - `lastSeq` per source entity (dedup).
- Commands: `Fold {source, seq, delta}`, `Close {at, zone}`, `Read`.
- Events: `Folded {delta}` (open day), `Corrected {arrivedAt, delta}` (closed day), `DayFixed {zone,
  closedAt, sections, differences, openingCorrections}`.
- Transitions: open → closed at `Close`, once; nothing reopens.

## Views

| View | Kind, source | Row | Queries (declared) |
|---|---|---|---|
| `Days` | over `BrandDay` state | the day's state but `lastSeq` | `between(:from, :to)` |
| `Wallets` | over `WalletBook` state | player, currency, lifetimes, restricted balance, open records | `byPlayers(:ids)`, `withOpenRecords` |
| `Players` | over `PlayerLog` state | player, registered at, country, currency, email **P**, status, KYC status, last login, test account | `registeredBetween(:from, :to)`, `all` (paged) |
| `PaymentsView` | over `Payment` state | the payment | `initiatedBetween(:from, :to)`, `disputesClosedBetween` |
| `WithdrawalsView` | over `Withdrawal` state | the withdrawal | `requestedBetween`, `completedBetween` |
| `Grants` | over `Grant` state | the grant | `grantedBetween(:from, :to)`, `byOffer(:offer, :from, :to)` |
| `Sessions` | over `Session` state | the session but the **P** fields for the dispute report | `open(:kind)`, `byPlayer(:player, :from, :to)`, `gameSessionsOf(:session)`, `gameStartsBetween` |
| `Rounds` | over `RoundCheck` state | the round, its status `matched`, `differs`, `provider_only`, `wallet_only` | `byGameSession(:session)`, `byDay(:day)`, `unmatchedOn(:day)`, `latestForGame(:game)` |
| `Transactions` | keyed, over `WalletBook` events, key `<wallet>/<factId>` | timestamp, transaction id, player, type, amount, currency, reference | `between(:from, :to, :type, :ids, :offset)` |
| `PlayerDays` | keyed, over `WalletBook` events, key `<player>/<currency>/<day>` | stakes, wins, GGR, deposit pool GGR, wagered, `lastSeq` | `between(:currency, :from, :to)` |
| `Activity` | keyed, over `PlayerLog` and `Grant` events, key the original's event id | event type, player, at | `since(:at)` |
| `Compliance` | keyed, over `PlayerLog` events, key `rg/<case>` or `kyc/<document>` or `kyc/<player>/<at>` | player, kind, type or category, opened or submitted, closed or decided, outcome, reason | `between(:kind, :from, :to)` |
| `Markets` | over the `markets.events` topic (row per market) | question, category, currency, status and its times, resolution, first resolution, corrected, voided | `filter(:status, :category, :from, :to)` |
| `MarketFigures` | over `MarketBook` state | the market's figures | `byMarkets(:ids)` |
| `Traders` | keyed, over `MarketBook` events, key `<player>/<market>` | contracts, notional, fees, paid, net, `lastSeq` | `between(:currency, :from, :to)` |
| `Documents` | over `Document` state | the record | `kept`, `byKind(:kind, :from, :to)` |

## Documents and settings

- `Document` (key value, `<reference>`): kind (`report-export`, `dispute-report`, `day-end-export`,
  `compliance-export`), format, parameters, period, asked by (operator id), generated at, ready at,
  object name, status (`generating`, `ready`, `expired`), ended by (`lifetime`, `erasure`), rows. Events
  for audit come from its state changes (research R15).
- `DocumentJob` (workflow, `<reference>`): `render` (paged, 1,000 rows a step) → `store` →
  `pause(lifetime)` → `expire`; `RecoverStrategy` retries a step three times then ends `failed`.
- `ReportingSettings` (key value, `brand`): `reportsTab`, `excludeTestAccounts`, `defaultCurrency`,
  `syncRows` (1–10,000), `syncDays` (1–365), `lifetimeHours` per kind (1–2,160, default 24).
- `Coverage` (key value, `<topic>`): the earliest `occurredAt` folded in this generation.
- `TestHolds` (key value, `<player>`): whether the player's registration has been folded by the day consumers,
  its test flag, and the folds made before it, which a test account's registration folds again into the test
  sections alone (research R4).
- `FoldProgress` (key value, `<consumer>`, one per `to-days` consumer): the latest fact time it has folded in
  this generation and the instant it last read an event, which `close-days` waits on (research R7).

## Timers

| Timer | Period | Does |
|---|---|---|
| `close-days` | 5 minutes | closes every open `BrandDay` whose end plus the grace has passed, once every `to-days` consumer has folded a fact past that or read nothing for `CLOSE_IDLE` (research R7) |
| `sweep-documents` | 1 hour | deletes any ready object past its lifetime and marks its record (research R9) |

## Consumers

One per intake topic (`money-facts`, `payments-facts`, `withdrawals-facts`, `rewards-facts` when the
brand runs `casino`, `players-facts`, `sessions-facts`, `rounds-facts` when `casino`, `trades-facts`
when `predictions`); `to-days` over the intake entities' events; `round-legs` from `WalletBook` to
`RoundCheck`; `audit` from `Document` to `platform.audit`.

## Pure packages

`figures` (GGR, NGR, the deposit pool, rates, the trend arrow), `days` (attribution's `Days`), `render`
(money strings, CSV with the original's quoting, the dispute report's HTML and CSV, PDF), `ledger` (the
family → type map), `ranges` (periods, the four ranges, ISO weeks).
