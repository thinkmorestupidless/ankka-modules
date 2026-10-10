# Contract: the wallet's routes (as built)

Paths follow spec 001 `contracts/routes.md`. Each audience is one endpoint whose prefix carries this
project's brand, read from `CASINO_BRAND`: a brand's project serves only its own brand
(constitution III), and an ankka route takes at most two path parameters, so `{brand}` is fixed in the
prefix rather than matched. A request naming another brand matches no route and is answered `404`
(spec 001 FR-005); the body is the runtime's, not a `wrong_brand` text (plan.md, divergences).

Bodies use the original's field names where it has the route (`realBalance`, `bonusBalance`, `txnId`,
`walletType`: camelCase, as its spray-json wrote them; [wire.md](wire.md)). Amounts on service and
granted routes are minor-unit integers; on player and operator routes major-unit strings with two
decimals. A route's ACL comes from `contract.access.Routes`; the granted routes and erasure replace
the endpoint's ACL for themselves (`withAcl`).

## Service routes (`Routes.service()`: the project's services, no token)

Prefix `/v1/casino/brands/<brand>`; `W` = `/wallets/{player}/{currency}`.

| Method, path | Caller | Command | Body in | Reply |
|---|---|---|---|---|
| `POST W/bets` | game-provider (its bet workflow, which asked `may-bet`) | `place-bet` | `key`, `roundId`, `gameId`, `sessionId`, `provider`, `gameType`, `category`, `amount`, `allowNegative` | `realBalance`, `bonusBalance`, `currency`, `txnId`, `walletType`, `realLeg`, `recordLegs`, `conversions`, `balanceBefore`, `balanceAfter` |
| `POST W/wins` | game-provider | `credit-win` | `key`, `roundId`, `amount`, `betKey?` (the bet a win settles in a round with several) | balances, `txnId`, `walletType` |
| `POST W/rollbacks` | game-provider | `rollback-bet` | `key`, `roundId`, `betKey?` | balances, `txnId` |
| `POST W/losses` | game-provider | `settle-loss` | `key`, `roundId`, `betKey?` | balances, `txnId`, `forfeited` |
| `POST W/provider-credits` | game-provider | `provider-credit` | `key`, `amount`, `providerRef`, `reason`, `actor` | balances, `txnId`, `landedOn` |
| `GET W/balance` | any | `balance` | — | `realBalance`, `restrictedBalance`, `currency`, `primary` |
| `GET W/restricted-funds` | rewards, support-agent | `restricted-funds-status` | — | `records[]` in order, `headline` |
| `POST W/awards` | rewards | `award` | `key` (= grant id), `bonusRef`, `amount`, `wageringMultiplier`, `maxWin`, `priority`, `expiresAt`, the rule snapshot | balances, `txnId`, `recordId`, `wageringRequirement` |
| `POST W/awards/forfeit` | rewards | `forfeit` | `key`, `grantRef?` | balances, `forfeited` |
| `POST W/awards/cancel` | rewards | `cancel` | `key`, `grantRef?` | balances, `clawbackAmount`, `forfeited` |
| `POST W/awards/close-pending-cap` | rewards | `close-pending-cap` | `key` | `closedRecordId` |
| `POST W/awards/switch` | rewards | `switch` | `key`, `grantRef` (in the body: two path parameters at most) | `priority`, `alreadyPrimary` |
| `PUT /wallet-config/bet-switches` | rewards | `set-bet-switches` | `contributionRateByGameType`, `bonusWageringCounts`, `partialSplitsAllowed`, `maxConcurrentAwards?` | the configuration |
| `POST W/holds` | payouts | `hold` | `key` (= request id), `amount`, `playerConfirmed` | balances, `txnId`, `confirmationRequired`, `forfeited`, `clawbackAmount`, `voidedAmount`, `paidAmount`, `cancelGrants` |
| `GET W/holds/preview?amount=` | payouts | `preview-hold` | — | `refusal?`, `confirmationRequired`, `bonusBalance`, `clawback`, `totalForfeit`, `mostWithdrawable`, `forfeits` |
| `POST W/holds/release` | payouts | `release` | `key`, `requestId`, `outcome` (cancelled, rejected, failed) | balances, `txnId` |
| `POST W/holds/confirm` | payouts | `confirm` | `key`, `requestId` | balances |
| `POST W/holds/reverse` | payouts | `reverse` | `key`, `requestId` | balances, `txnId` |
| `POST W/adjustments` | support-agent | `adjust` | `key`, `walletType` (real, bonus), `amount` (signed), `reason`, `category`, `actor` | balances, `txnId`, `forfeited` |
| `GET W/deposit-sums` | responsible-gambling (display) | `deposit-sums` | — | `last24h`, `last7d`, `last30d` |
| `GET W/sums?type=&from=&to=` | responsible-gambling, reporting | `SumsView` | — | `type`, `total`, `currency`, `count` (the original's `transaction-sum`) |
| `GET W/lifetime-totals` | reporting, support-agent | `LifetimeTotalsView` | — | `deposited`, `wagered`, `won` |
| `GET W/transactions?type=&walletType=&from=&to=&page=&pageSize=` | support-agent, reporting | `TransactionsView` | — | `{transactions, page, pageSize, total}` (the original's) |

## Granted routes (the brand's 040 grants to `payments`/`merchant`; `Layout.BrandGrantsMade`)

| Method, path | Command | Body in | Reply |
|---|---|---|---|
| `POST W/deposits` | starts `deposit-flow` `<player>/<currency>/<paymentId>` (a `Conflict` is "already started"), polls its `status` every 1 ms for up to 2 s, renders the outcome; the workflow's steps are `deposit-sums` + `may-deposit`, then `deposit`; past the bound `503 OUTCOME_PENDING` and the retry is answered from the workflow | `paymentId` (the key), `amount`, `terminal`, `psp`, `scheme`, `paymentProvider` | balances, `txnId`, `depositOrdinal`, `turnoverRequired`, `depositSums`; or `EXCLUDED`, `COOLING_OFF` (`endsAt`), `OVER_LIMIT` (`period`, `limit`, `used`, `remaining`), `RG_UNAVAILABLE` |
| `POST /payments/{paymentId}/outcome` | `PaymentEntity.record` | `outcome`, `playerId`, `paymentProvider`, `terminal?`, `amount?`, `currency?` (required for initiated, refunded, dispute opened), `code?`, `reason?`, `disputeOutcome?` | `recorded`, `firstRecorded` |

## Erasure (ankka 042; until it ships, a route `players` calls)

`POST /erasure/players/{playerId}`, admitted for `players` alone: answers `confirmed: true` with nothing
destroyed beyond the subject's key (FR-033).

## Operator routes (`Routes.operator(slug)`: a staff token whose realm roles name this brand)

Prefix `/v1/casino/backoffice/<brand>`.

| Method, path | Command | Notes |
|---|---|---|
| `POST W/adjustments` | `adjust` | `key`, `walletType`, `amount` as "10.00", `reason` (Personal), `category`; the actor is the token's subject |
| `PUT W/turnover-override` | `set-turnover-override` | `multiplier?`, `definition?`; no multiplier clears |
| `GET W/balance`, `W/restricted-funds`, `W/transactions`, `W/lifetime-totals` | reads | major-unit strings |
| `PUT /wallet-config/turnover` | `set-turnover-terms` | `multiplier`, `definition` (FR-004a) |
| `GET /wallet-config` | `get` | the whole configuration |

## Player routes (`Routes.player(slug)`: the brand realm's token; the player is its subject)

Prefix `/v1/casino/players/<brand>/wallets`: `GET /{currency}/balance`, `/{currency}/transactions`,
`/{currency}/restricted-funds`, in major units.

## Calls the wallet makes (`responsible-gambling`, within the project; spec 010 serves it)

| Call | Body | Answer |
|---|---|---|
| `POST /v1/casino/brands/<brand>/rg/may-deposit` | `playerId`, `currency`, `amount`, `last24h`, `last7d`, `last30d` | `decision`: allowed, excluded, cooling_off (`endsAt`), over_limit (`period`, `limit`, `used`, `remaining`) |

Made from the deposit workflow's `check-rg` step. 500 ms; a timeout, a failed connection, a non-2xx or
an answer the wallet cannot read is `RG_UNAVAILABLE` and the wallet is not called (spec 010 FR-011).
The refusal is the workflow's outcome, so a repeat of the payment id is answered from it and never
asks again. `may-bet` (`POST …/rg/may-bet`: `playerId`, `currency`, `amount` → allowed, locked,
excluded, cooling_off) is asked by `game-provider`'s bet workflow (spec 003 FR-012), not by the wallet.

## Refusals

The reply is `{"error": "<CODE>", "message": "<text>", "details": {…named amounts}}` with the status of
[wire.md](wire.md)'s table. The entity answers every refusal under a key as an outcome, so a repeat
within its recent 1,000 keys is refused the same way; a deposit's `responsible-gambling` refusal is
the deposit workflow's outcome, answered from it for the life of the ledger; the endpoint renders the
status from the code (one table, `Wire.status`).
