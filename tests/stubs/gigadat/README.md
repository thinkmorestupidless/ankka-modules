# gigadat stub

Answers as Gigadat Express Connect (Interac) answers the original's adapter,
`services/psp-gateway/internal/gigadat/adapter.go`. Default port 18105 (`GIGADAT_STUB_PORT` overrides).

## Pointing the original at it

`GIGADAT_BASE_URL=http://psp-stub:18105` on psp-gateway — read at
`services/psp-gateway/internal/config/config.go:173-176` (default `https://interac.express-connect.com`,
production; sandbox is a body flag, not a host).

Gigadat's webhook address is not in any request (it is set per campaign in Gigadat's portal), so the stub
is told it: `GIGADAT_STUB_WEBHOOK_URL`, default `http://localhost:8087/v1/webhooks/gigadat` — the gateway's
default port (`config.go:73`) and route (`cmd/psp-gateway/main.go:210`).

The GIGADAT terminal needs `campaignId`, `accessToken`, `securityToken`, `siteUrl` and `environment`
(any values; the stub only requires the tokens be present). Currency must be CAD.

## Routes

| Route | Answer |
|---|---|
| `POST /api/payment-token/{campaignId}` | Requires HTTP Basic credentials (the adapter sends access/security token that way, `adapter.go:90`). Answers 200 `{"token":<JWT-shaped>,"data":{"transactionId":<the merchant's transactionId>,…}}`; 401/400 on missing credentials or a bad body. |
| `GET /webflow?transaction=&token=` | 200: the player's page for CPI, the "initiate" call for ETO/ACH payouts. |
| `GET /webflow/deposit?transaction=&token=` | 200: the payout "finalize" call; starts the payout's webhooks. |
| `GET /healthz`, `POST /__control`, `GET /__received` | From `stubhttp`. |

Webhooks: `POST <GIGADAT_STUB_WEBHOOK_URL>?transactionId=<txn>&status=<STATUS_*>`, body a JSON array of
`{key,value}` (`transactionId`, `type`, `amount`, `cookie`, `rcode` at a final status). A CPI deposit's webhook
goes after the delay from the token call; an ETO/ACH payout's go after finalize, `STATUS_INITED` first.
`success` → `STATUS_SUCCESS` rcode 100; `failure` → `STATUS_REJECTED` rcode 231 (a `code` is the rcode, or
the status when it is a `STATUS_*` such as `STATUS_EXPIRED`); `pending` → `STATUS_INITED` only, settled by
the next control; `unauthorised` → `401` on the token call. `POST /__webhook {"reference": <transactionId>}`
sends a status now ([psp-stub's README](../psp-stub/README.md) has the whole contract).
Gigadat signs nothing and the gateway verifies nothing (it logs `cookie`, `handler/gigadat.go:84-87`).

## Environment

| Variable | Default | |
|---|---|---|
| `GIGADAT_STUB_WEBHOOK_URL` | under psp-stub, `PSP_STUB_GATEWAY_URL` + `/v1/webhooks/gigadat`; else `http://localhost:8087/v1/webhooks/gigadat` | where webhooks go |
| `GIGADAT_STUB_WEBHOOK_BODY` | `array` | `array` (bare JSON array, per `handler/gigadat.go:39-41`) or `form` (`data=<array>` URL-encoded, per the original's spec 199); the gateway parses both |
| `GIGADAT_STUB_WEBHOOK_DELAY_MS` | `1500` | delay before webhooks |
| `GIGADAT_STUB_PORT` | `18105` | |
