# Contract: wire translation for parity, and the refusal texts

The original's `wallet-service` (`WalletRoutes.scala`) answers at `/v1/wallet/{b}/{p}/{c}/<verb>` behind
a player token, and at `/internal/v1/wallet/{b}/{p}/{c}/<verb>` for the original's own services, signed
with `X-Internal-Brand-Id` and `X-Internal-Secret` (an HMAC of the brand). Its bodies are spray-json,
camelCase, with decimal amounts ("98.50") except the withdrawal lifecycle's, which are cents. The
rebuild answers at spec 001's paths ([routes.md](routes.md)) with the original's field names and
minor-unit integers on service routes.

A recording under `tests/port/wallet/` is the original's; the parity suite translates each step once,
in one place (`wallet.port.WireTranslation`, tested by `WireTranslationSuite`), and reads the
rebuild's answer back in the recording's terms: its fields only, its decimals. The rebuild may answer
more than the original (a `message` beside the `error`), never less. This is the documented divergence
of research R11; nothing goes under `vary` for it.

Established from the wallet driver's reading of `WalletRoutes.scala` (tests/original/wallet-driver),
the Go guests and the converted TL fixtures; the recordings (tasks.md T074) confirm or correct each
row, and the recording wins.

## Paths

| Original | Rebuild |
|---|---|
| `POST …/deposit` (public and internal) | `POST …/deposits` |
| `POST …/bet`, `…/win`, `…/rollback`, `…/settle-loss` | `POST …/bets`, `…/wins`, `…/rollbacks`, `…/losses` |
| `POST /internal/…/award` (provider credit) | `POST …/provider-credits` |
| `GET …/balance` | `GET …/balance` |
| `GET …/transactions` (public and internal) | `GET …/transactions` |
| `GET /internal/…/lifetime-stats` | `GET …/lifetime-totals` |
| `GET /internal/…/transaction-sum` | `GET …/sums` |
| `POST /internal/…/bonus/award` | `POST …/awards` |
| `POST …/bonus/forfeit` | `POST …/awards/forfeit` |
| `POST /internal/…/cancel-bonus` | `POST …/awards/cancel` |
| `POST /internal/…/bonus/close-pending-cap` | `POST …/awards/close-pending-cap` |
| `POST /internal/…/bonus/switch` | `POST …/awards/switch` |
| `GET …/bonus`, `…/bonus/status` | `GET …/restricted-funds` |
| `POST /internal/…/create-withdrawal-request` | `POST …/holds` |
| `POST /internal/…/cancel-`, `reject-`, `fail-withdrawal-request` | `POST …/holds/release` with `outcome` |
| `POST /internal/…/complete-withdrawal-request` | `POST …/holds/confirm` |
| `POST /internal/…/reverse-withdrawal-request` | `POST …/holds/reverse` |
| `POST /internal/…/admin-adjust` | `POST …/adjustments` |

Routes of the original with no row are dropped (spec *Changes from the original*, [surface.md](surface.md)).

## Fields

| Original | Rebuild | Rule |
|---|---|---|
| `paymentRef` | `paymentId` | the deposit's key (FR-002) |
| none (the original minted a UUID per dispatch) | `key` | the caller's key; a recording's step gets `<scenario>#<step>`, so two identical requests stay two commands, as they were |
| `amount: "100.00"` | `amount: 10000` | decimal to minor units on service and granted routes |
| `realBalance`, `bonusBalance`, `walletType`, `txnId`, `currency` | same | minor units; rendered back as the recording's decimals |
| `roundId`, `gameId`, `sessionId`, `provider`, `gameType`, `allowNegative` | same | |
| `terminalId`, `pspId` | `terminal`, `psp` | |
| `bonusRef`, `wageringMultiplier`, `maxWin`, `priority`, `maxStake` | same | `grantRef` becomes `key` on the award |
| `grant_ref` (forfeit, close), `grantRef` (cancel, switch) | `grantRef` | |
| `clawbackAmount` (cancel), `clawback_amount` (hold) | none | dropped: the wallet computes it (FR-015a); a recording's value becomes the test's baseline |
| `request_id`, `requestId` | `requestId` | the hold's key is the request id |
| admin-adjust `type` (credit, debit), `amount` | `amount` signed | a debit is negative |
| admin-adjust `wallet_type` (real, bonus), `reason`, `actor` | `walletType`, `reason`, `actor` | |
| `totalDeposited`, `totalWagered`, `totalWon` | `deposited`, `wagered`, `won` | renamed back by the translation |
| `turnover_multiplier`, `turnover_definition` (deposit input) | none | resolved from configuration (FR-004a); a recording's value becomes the test's brand terms |
| `created_at`, `transaction_id`, `txnId` | same | `vary` (clock, generated) |

## Refusals

The original's bodies are mostly `{"error": "<CODE>"}` with no message; the rebuild adds the message
the Go guest or the route carries. Status and code are the original's.

| Code | Status | Message | Evidence |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | `the amount '<amount>' is not a whole number of minor units` | spec 001 FR-026 (new boundary) |
| `MISSING_KEY` | 400 | `a money command must carry the key its caller chose` | spec 001 FR-027 (new) |
| `WALLET_NOT_FOUND` | 404 | `Wallet not found` | route |
| `WALLET_ALREADY_OPEN` | 409 | `Wallet already open` | new: `open-migrated` on a created wallet (FR-001) |
| `ROUND_NOT_FOUND` | 404 | `No open bet found for round <round>` | route (code only) |
| `INSUFFICIENT_FUNDS` (bet) | 409 | `Insufficient funds in real and bonus wallets` | Go `MsgInsufficientFunds` |
| `INSUFFICIENT_FUNDS` (hold) | 409 | `Insufficient real balance for withdrawal` | Go withdraw, fixtures |
| `SCOPE_BLOCKED` | 409 | `stake is out of scope for a blocking restricted-funds record` | Go placebet |
| `MAX_STAKE_EXCEEDED` | 409 | `stake exceeds the binding max_stake` | Go placebet |
| `RESTRICTED_FUNDS_UNMIGRATED` | 409 | `restricted balance present with no open restricted-funds record` | Go placebet (the TL fixtures carry an older wording) |
| `PLAYER_LOCKED` | 403 | `Player is locked` | spec 003's bet workflow answers it, not the wallet (FR-007a moved); kept in the table for the status |
| `RG_UNAVAILABLE` | 503 | `Responsible gambling check unavailable` | new: fails closed; on the deposit route it is the deposit workflow's outcome |
| `EXCLUDED`, `COOLING_OFF`, `OVER_LIMIT` | 403, 403, 409 | as `responsible-gambling` answers (details carry `endsAt`, `period`, `limit`, `used`, `remaining`); the deposit workflow's outcome | spec 010 FR-007 |
| `OUTCOME_PENDING` | 503 | `the deposit is still being decided; retry` | new: the deposit route's poll bound (2 s) passed; the retry is answered from the workflow (R17) |
| `BONUS_ALREADY_ACTIVE` | 409 | `A bonus is already active` | route (code only) |
| `MISSING_BONUS_REF` | 400 | `bonus_ref is required` | route |
| `NO_ACTIVE_BONUS` | 409 | `No active bonus to forfeit` | Go close.go, forfeit fixtures |
| `GRANT_NOT_OPEN` | 409 | `Grant <grant> is not open` | Go close.go (code) |
| `GRANT_NOT_FOUND` | 404 | `Grant <grant> not found` | route (code only) |
| `GRANT_NOT_ACTIVE` | 409 | `Grant <grant> is not active` | route |
| `NOT_PENDING_CAP` | 409 | `No CONVERTED_PENDING_CAP bonus to close` | Go close.go |
| `WITHDRAWAL_REQUEST_ALREADY_PENDING` | 409 | `A withdrawal request is already pending review` | Go withdraw |
| `WITHDRAWAL_BLOCKED_TURNOVER` | 409 | `Deposit turnover requirements not yet met` | Go withdraw |
| `WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT` | 409 | `insufficient real balance after bonus forfeiture` | Go withdraw (lowercase, verbatim) |
| `WITHDRAWAL_REQUEST_NOT_CANCELLABLE` | 409 | the original's | cancel and reject fixtures: a release as cancelled of a request that is not pending |
| `REQUEST_NOT_PENDING` | 409 | `Request <request> is not the pending request` | route; release as rejected or failed, confirm |
| `WITHDRAWAL_NOT_IN_COMPLETED_STATE` | 409 | `Withdrawal <request> is not completed` | route, reverse fixtures |
| `REASON_REQUIRED` | 400 | `A reason must be supplied for a manual balance adjustment` | route |
| `DEBIT_EXCEEDS_BALANCE` | **400** | `Requested debit of <amount> exceeds current <real\|bonus> balance of <balance>` (details `requestedAmount`, `currentBalance`) | route |
| `NO_OPEN_RESTRICTED_FUNDS` | 409 | `no open restricted-funds record to adjust` | Go adjust |
| `KEY_REUSED` | 409 | `Key <key> was used by a <command> of <amount>` | new (FR-002) |

Dropped with the routes or guards they belonged to: `RESTRICTED_FUNDS_UNAVAILABLE` (award-bonus's
record-first guard: the rebuild's award creates its record), `grantRef_required` (the rebuild answers
`INVALID_REQUEST` naming it), `INVALID_GRANT_STATUS`, `INVALID_ACTOR`, `SUBSTRATE_UNAVAILABLE` and
`scope_violation` (the twin substrate and its internal signing).
