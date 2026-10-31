# Surface: the original's withdrawals, and where each goes

The original is `eitheror-platform` at `e7d8f2de`. Its withdrawal surface, read whole (research R1),
with each element moved, changed, dropped or new.

- `W` = `substrate/casino-service/.../WithdrawalRoutes.scala`;
- `TR` = `WithdrawalTerminalRoutes.scala`;
- `PD` = `PayoutDestinationRoutes.scala`;
- `BC` = `BrandConfigRoutes.scala`;
- `S` = `PayoutReconciliationSweeper.scala`.

## Routes

| The original | Here | Disposition |
|---|---|---|
| `POST /v1/casino/brands/{b}/players/{p}/withdrawal-requests` (`W:178`) | `PlayerEndpoint` `POST /{p}/withdrawal-requests` | moved; R4's order; minor units |
| `GET …/players/{p}/withdrawal-requests` (`W:878`), the pending request only | `PlayerEndpoint` `GET /{p}/withdrawal-requests` | changed: every request, newest first (`reads.feature`) |
| `DELETE …/players/{p}/withdrawal-requests/{id}` (`W:970`) | `PlayerEndpoint` `DELETE /{p}/withdrawal-requests/{id}` | moved |
| `GET …/players/{p}/withdrawal-terminals` (`TR:41`) | `PlayerEndpoint` `GET /{p}/withdrawal-terminals` | moved; currency the wallet's, not GBP by default (R5) |
| `GET`, `POST …/players/{p}/payout-destinations` (`PD:97`) | `PlayerEndpoint` | moved |
| `PUT …/payout-destinations/{id}/set-default`, `DELETE …/payout-destinations/{id}` | `PlayerEndpoint` | moved |
| `GET /v1/casino/brands/{b}/withdrawal-requests` (`W:1106`), with its filters | `OperatorEndpoint` `GET /` | moved; pages past 500; `total` in the answer; `stale` filter for FR-022 |
| `GET …/withdrawal-requests/{id}` | `OperatorEndpoint` `GET /{id}` | moved |
| `POST …/{id}/approve` (`W:1412`) | `OperatorEndpoint` | changed: once only; the brand's roles only |
| `POST …/{id}/reject` (`W:2406`) | `OperatorEndpoint` | changed: pending review only |
| `POST …/{id}/confirm-payout` (`W:2515`), `POST …/{id}/mark-payout-failed` (`W:2613`) | `OperatorEndpoint` | moved |
| `GET …/players/{p}/withdrawal-history` (`W:2807`) | `PlayerEndpoint` with an operator ACL by `withAcl` | moved |
| `GET`, `PUT /v1/casino/brands/{b}/withdrawal-config` (`BC:67`) | `ConfigEndpoint` | changed: limits per currency; turnover terms go to the wallet (R10) |
| `POST /internal/v1/casino/withdrawal-requests/{id}/outcome` (`W:3042`), `X-Internal-Secret` | `OutcomeEndpoint` `POST /v1/casino/brands/<slug>/withdrawals/{id}/outcome`, by grant | moved (R6) |
| `…/players/{p}/deposit-records` (`W:2725`), `…/turnover-override` (`W:2871`) | — | dropped: spec 002's and 008's (spec *Changes*) |
| — | `ServiceEndpoint` `POST /v1/casino/brands/<slug>/payouts/erasure/{p}` | new (R12) |

## Functions and processes

| The original | Here | Disposition |
|---|---|---|
| `create-withdrawal-request` (Go) | the workflow's `hold` step → the wallet's `POST …/holds` | the wallet's (spec 002) |
| `cancel-`, `reject-`, `fail-withdrawal-request` (Go) | `release` with outcome cancelled, rejected or failed | the wallet's |
| `approve-withdrawal-request` (TL) | `WithdrawalRequest.Approve`; no money moves | changed: once only |
| `complete-withdrawal-request` (Go) | `confirm` | the wallet's |
| `reverse-withdrawal-request` (Go) | `reverse` | the wallet's |
| `mark-withdrawal-awaiting-payout` (Go) | `WithdrawalRequest.AwaitManual` | moved; reached only from approval (FR-018) |
| `WithdrawalLimitChecker` | pure `Limits` | moved; per currency |
| `WithdrawalTerminalEligibility` | pure `Eligibility` | moved, unchanged |
| `IbanValidator`, `PayoutDestinationAccess` | pure `Iban`, `Names`; `PlayerPayouts` | moved |
| `resolveApprovalOutcome` (`W:1586-2282`) | pure `Approval` + the workflow's `initiate` | changed: every gateway answer read (R7) |
| `PayoutReconciliationSweeper` (`S`), every 5 minutes, capped at 12,500 | the workflow's own waits | changed: per request, no scan (R8) |
| `export_withdrawal` (TL) | `WithdrawalPublisher` | changed: no second `withdrawal_requested` on approval (R11) |
| `notify_withdrawal_failed` (TL), the customer.io email | `withdrawal_failed` on `casino.withdrawals` | moved: spec 014 sends from the fact |
| Affiliate notifications at approval and failure | the facts | spec 013's (spec *Changes*) |
| Activity-log events on the twin | `platform.audit` | moved |
| Rate limits `withdraw`, `withdrawal` (`CasinoServiceMain.scala:191-192`) | — | dropped: configured, never checked |
