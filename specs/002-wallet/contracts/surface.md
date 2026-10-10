# Surface: the original's `wallet-service`, with dispositions

From `substrate/wallet-service/src/main/scala/io/eitheror/wallet/WalletRoutes.scala` (56 routes under
five prefixes) and the two handler packs. Every row has a disposition (ankka-port, section 3):
*covered* by the named requirements, or *dropped* with the reason. The disposition list is the
spec's *Changes from the original*, made per route.

## `/v1/wallet/{brand}/{player}/{currency}` (the service API casino-service and the ST8 adapter call)

| Route | Disposition |
|---|---|
| `POST deposit` | covered: FR-022, FR-004a (terms no longer on the command) |
| `POST bet`, `win`, `rollback`, `settle-loss` | covered: FR-005 to FR-013 |
| `POST award` (provider credit) | covered: FR-027 |
| `GET balance` | covered: FR-028 |
| `GET transactions`, `lifetime-stats`, `transaction-sum` | covered: FR-029, FR-030 |
| `POST bonus/award`, `bonus/forfeit`, `cancel-bonus`, `bonus/close-pending-cap`, `bonus/switch`, `GET bonus/status` | covered: FR-015 to FR-021, FR-028 |
| `POST create-withdrawal-request` | covered: FR-023 (hold), FR-023a (preview) |
| `POST cancel-/reject-/fail-withdrawal-request` | covered: FR-024 (release) |
| `POST complete-withdrawal-request` | covered: FR-024 (confirm) |
| `POST reverse-withdrawal-request` | covered: FR-025 |
| `POST approve-withdrawal-request`, `mark-withdrawal-awaiting-payout` | dropped: request lifecycle without money movement is `payouts`' (spec 007) |
| `POST create-/set-default-/delete-payout-destination` | dropped: payout destinations are `payouts`' (spec 007) |
| `POST admin-adjust` | covered: FR-026 |
| `POST check-open-round` | dropped: a round is found by key; stub |

## Second prefix (line 2202–2266: `bet`, `balance`, `win`, `bonus/forfeit`, `rollback`, `settle-loss`, `bonus/status`)

Same handlers reached under an alternate prefix for the ST8 adapter; covered by the rows above. One
path each in the rebuild.

## `/internal/v1/wallet/...`

| Route | Disposition |
|---|---|
| `preflight/retire-bonus-columns`, `{b}/preflight/retire-legacy-funding-inputs`, `{b}/preflight/withdrawal-request-concepts` | dropped: migration tooling of the twin substrate |
| `{b}/backfill/withdrawal-request-concepts`, `{b}/backfill/phase2`, `{b}/rollback/phase2` | dropped: Phase 2 backfill and rollback |
| `{b}/restricted-funds/open` | dropped: the graph projection of records; records are state |
| `{b}/{p}/{c}/deposit` (internal deposit) | covered by the granted deposit route (FR-022) |

## `/internal/v1/crypto-wallets/{b}/{p}` (`create`, `set-default`, `delete`)

Dropped: crypto payout destinations move to `payouts` (spec 007).

## Not routes

| Element | Disposition |
|---|---|
| `WalletActionDispatcher` (HTTP dispatch to TinyGo guests and TL handlers) | dropped: the resolver is in the entity (R2) |
| `BoundedFanOut` (the `HAS_TRANSACTION` scan) | dropped: a round is found by key; its depth (10,000) survives as the settled-rounds bound (R1) |
| `GraphQLClient`, `WalletSubstrateTwinHttp` | dropped: no twin, no graph |
| Metrics: active-round gauge | dropped: a view |
| The audit edge write per transaction | covered by `platform.audit` (FR-031) |
| Idempotency: `hasDepositTransaction` on the payment reference; a random UUID per dispatch otherwise | covered and widened: FR-002 |
| TinyGo guests: `place-bet`, `credit-win`, `rollback-bet`, `settle-round-as-loss`, `admin-adjust`, `award-bonus`, `cancel-bonus`, `forfeit-bonus`, `close-pending-cap-bonus`, `game-provider-credit`, `create-withdrawal-request`, `mark-withdrawal-awaiting-payout` | covered (`wallet.funding`), except the last (dropped with its route) |
| TL handlers: `deposit.tl`, the withdrawal lifecycle `.tl`, `read-contribution-rate.tl`, `check-open-round.tl`, payout destinations | `deposit` and the lifecycle covered; `read-contribution-rate` is the bet's own read of `BrandConfigEntity`; the rest dropped as above |
| Brand configuration read on every bet (`casino-service` brand twin) | covered: `BrandConfigEntity` (FR-004, R7) |
| Wallet creation by the twin substrate (no route) | replaced: created by the first deposit or award, or by `open-migrated` (new, FR-001) for the migration and the fixtures' unmigrated state |
