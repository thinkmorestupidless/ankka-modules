# cozeni stub

Answers the original psp-gateway's Cozeni adapter
(`eitheror-platform/services/psp-gateway/internal/cozeni/`) as Cozeni would. Port 18102.

## Pointing the original at it

```
COZENI_BASE_URL=http://cozeni-stub:18102
```

Read in `internal/config/config.go:49-52,162-166` (default the sandbox, `https://test.cozeni.io`). The
adapter uses it for the rate (`adapter.go:68`), pay-in (`adapter.go:176`) and payout (`adapter.go:351`).

## What it answers

| Route | Answer |
|---|---|
| `GET /pub/rate/{crypto}/{fiat}` | `{"rate":n}` — fiat value of 1 crypto: BTC 65000, ETH 3000, LTC 80, TRX 0.12, USDT/USDC 1, anything else 1 |
| `POST /mxapi/{merchantId}/payintxn` | `200 {"txnId","status":"PENDING","resultCode":"01","paymentRef","requestAmount","paymentAddress","validUntil",…}`; `422 {"msgCode","msgText"}` on a missing or wrong `X-cozeni-txnauthz` or missing fields |
| `POST /mxapi/{merchantId}/payouttxn` | `200 {"txnId","resultCode":"01","resultMsg":"Pending"}`; with `failure`, `resultCode "93"` (the gateway reports REJECTED, `handler/payout.go:174`) |
| `GET /mxapi/{merchantId}/payintxn/{txnId}` | the transaction's current state (the gateway never polls; here for completeness) |

Outcome (`POST /__control`): pay-in `success` → callback `status=COMPLETED, resultCode=00`;
`failure` → callback `status=CANCELLED`; `pending` → no callback. Payout `success` → callback
`COMPLETED` with a `txnHash`; `failure` → rejected in the answer, no callback; `pending` → no callback.

## The callback

Form-encoded POST to the `callbackUrl` in the request body — `<PSP_GATEWAY_BASE_URL>/v1/webhooks/cozeni`
for a pay-in (`adapter.go:157`), `.../v1/webhooks/cozeni/withdrawal` for a payout
(`handler/payout.go:136`) — so `PSP_GATEWAY_BASE_URL` must be reachable from the stub. The token is the
`txnauthz` form field, `SHA-256(merchantId + requestSalt + paymentRef + apiKey)` hex
(`cozeni/signature.go:9-31`, checked at `handler/webhook.go:873` and `:1371`). That is the same value
as the request's `X-cozeni-txnauthz` header, so the stub echoes it and needs no API key.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `18102` | listen port |
| `COZENI_STUB_API_KEY` | unset | when set, the request token is checked against it; must equal the terminal's `apiKey` |
| `COZENI_STUB_WEBHOOK_DELAY_MS` | `500` | delay before the callback |
