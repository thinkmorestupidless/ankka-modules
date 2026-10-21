# Routes: psp-gateway

Every route the service serves, grouped as ankka requires: one endpoint per prefix, with nested prefixes
as their own endpoints (the longest match wins). The service is exposed, because its webhooks and
browser routes are reached from the internet.

## `/v1/psp`: `merchant` only (`Callers.service("payments", "merchant")`)

| Method, path | Does |
|---|---|
| `POST /hpp/initiate` | the deposit initiation ([merchant.md](merchant.md)) |
| `GET /sessions/{reference}` | a deposit session's status ([merchant.md](merchant.md)) |

## `/v1/psp/checkout`: public (`Routes.public`): the player's browser

All of these settle nothing (US6). Every return route answers the original's neutral processing page
(`hpp.go:919-927`), `text/html`. A reference the gateway does not know gets the same page.

| Method, path | Does |
|---|---|
| `GET /dreamzpay/{reference}` | DreamzPay's relay: a page that auto-POSTs the session's form to `{DREAMZPAY_BASE_URL}/transaction/Checkout` |
| `GET /dreamzpay/{reference}/return`, `POST /dreamzpay/{reference}/return` | the processing page; the POST's form is logged and ignored (spec correction 8) |
| `GET /eupaymentz/{reference}` | EuPaymentz's relay for a POST-method redirect |
| `GET /{reference}/return`, `GET /unityfinance/{reference}/return` | Unity Finance's return, brand-neutral and legacy (`main.go:170-172`) |
| `GET /t365/{reference}/return`, `/eupaymentz/{reference}/return`, `/gigadat/{reference}/return`, `/bouncemoney/{reference}/return`, `/digitarium/{reference}/return` | the processing page |

## `/v1/webhooks`: public (`Routes.public`): the providers

The body is read raw (`postBody`), and is verified in the handler (FR-010, FR-012). The answers are each
provider's, from research R9.

| Method, path | Provider |
|---|---|
| `POST /dreamzpay` | DreamzPay |
| `POST /unityfinance`, `POST /unityfinance/withdrawal` | Unity Finance deposit, payout |
| `POST /t365` | T365 |
| `POST /cozeni`, `POST /cozeni/withdrawal` | Cozeni deposit, payout |
| `POST /eupaymentz` | EuPaymentz |
| `POST /gigadat` | Gigadat, deposits (`CPI`) and payouts (`ETO`, `ACH`), with `?transactionId=&status=` |
| `POST /bouncemoney` | BounceMoney |
| `POST /digitarium` | Digitarium |
| `POST /morphosis/deposit/{token}`, `POST /morphosis/withdrawal/{token}` | Morphosis |

## `/v1/payments/withdrawals` and `/v1/payments/withdrawal-terminals`: a brand's `payouts`

ACL: `Acl.AllowIf` admitting `Caller.Service(_, "payouts")` and `Caller.Local`. The brand is the
caller's project. These routes are what `Layout.brandGrantsReceived` grants
([payouts.md](payouts.md)).

| Method, path | Does |
|---|---|
| `POST /v1/payments/withdrawals` | initiate a payout, keyed by `requestId` (FR-004) |
| `GET /v1/payments/withdrawals/{requestId}` | the payout's status (FR-009) |
| `GET /v1/payments/withdrawal-terminals` | the brand's withdrawal-capable terminals (FR-009a) |

## `/v1/psp/outcomes`: payments operators (`Routes.paymentsOperator`, platform scope)

| Method, path | Does |
|---|---|
| `GET /undelivered?limit=` | the undelivered outcomes: reference, kind, receiver, outcome, attempts, last answer and since (FR-016) |
| `GET /refused?limit=` | the refused outcomes, with the receiver's answer |
| `POST /{reference}/redrive` | re-drive one at once, undelivered or refused. `202` when re-driven; `409 not_redrivable` with the delivery state otherwise (delivered, or not yet settled) |

## `/status`: any service of the project, and payments operators

`GET /status` answers `{"providers": {"<PROVIDER>": {"state": "up|degraded|down", "calls": n, "failed":
n, "p95Seconds": x, "configured": bool}}, "undelivered": n, "refused": n}` (FR-023, FR-016).

## Dropped from the original's surface

| Original | Why |
|---|---|
| `POST /v1/psp/{authorize,capture,void,refund}` | the card stub (*Changes*) |
| `POST /v1/psp/hpp/callback`, `GET /v1/psp/terminals/{id}` | stub-only, no callers |
| `GET /psp-simulator/redirect/{id}`, `POST /psp-simulator/complete/{id}` | the simulator (*Changes*) |
| `POST /v1/psp/payout/{unityfinance,cozeni,morphosis,gigadat}`, `GET /v1/psp/payout/unityfinance/{paymentNo}/status` | `/v1/payments/withdrawals` |
| `POST /internal/v1/psp/redrive-undelivered` | the `Redrive` timer and the operator route |
| ConnecX's three routes | ConnecX is dropped |
| `GET /health` | the runtime's readiness |
