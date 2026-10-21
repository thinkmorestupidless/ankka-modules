# digitarium stub

Answers the original psp-gateway's Digitarium (G2Pay) adapter
(`eitheror-platform/services/psp-gateway/internal/digitarium/adapter.go`) and sends its signed webhook.
Port 18109 under [psp-stub](../psp-stub/README.md).

## Pointing the original at it

```
DIGITARIUM_BASE_URL=http://psp-stub:18109
```

The original reads one setting, `DIGITARIUM_BASE_URL` (`config/config.go:188`); unset, the terminal's
`environment` picks `https://engine-sandbox.g2pay.io` or `https://engine.g2pay.io` (`adapter.go:19-35`).
The port reads two, `DIGITARIUM_SANDBOX_URL` and `DIGITARIUM_PRODUCTION_URL` (research R10); point both here.

## What it answers

| Route | Answer |
|---|---|
| `POST /api/v1/payments` (JSON, `Authorization: Bearer <apiKey>`) | `200 {"timestamp","status":200,"result":{"id","referenceId","paymentType","state":"CHECKOUT","amount","currency","redirectUrl"}}`; `401` on an empty or (when `DIGITARIUM_STUB_API_KEY` is set) wrong key; `400` with `referenceId`, `paymentType`, a positive `amount` or `currency` missing |
| `GET /checkout/{id}` | the hosted page: 302 to the request's `returnUrl` |

## The webhook

`POST` to the request's `webhookUrl` (`<gateway>/v1/webhooks/digitarium`), JSON, the payment object
`{"id","referenceId","paymentType","state","amount","currency"[,"errorCode","errorMessage"]}`, bare
(`flat`, the default) or as `{"timestamp","status":200,"result":{…}}` (`result`); the gateway reads both
(`adapter.go:84-98`). Header `Signature` = HMAC-SHA256 of the raw body under the signingKey, hex by
default, `base64` or `base64url` when controlled; the gateway accepts hex and every base64
(`signature.go:21-52`). The status field is `state`, which is what the original reads
(`PaymentResult.State`, `handler/digitarium.go:69`).

## Outcomes (`POST /__control`)

| Outcome | `state` |
|---|---|
| `success` | `COMPLETED` |
| `failure` | `DECLINED`, `errorCode` the `code` (default `05`), `errorMessage` "Do not honour"; `code` `CANCELLED` → `CANCELLED` |
| `pending` | `PENDING` now; the next control's outcome settles it |
| `unsigned` | `COMPLETED` with no `Signature` (the gateway answers 400) |
| `unauthorised` | the payment call answers `401` |

`signatureEncoding` (`hex`, `base64`, `base64url`) and `envelope` (`flat`, `result`) shape the
webhook. `POST /__webhook {"reference": <referenceId or G2Pay id>}` sends it now, once.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `DIGITARIUM_STUB_API_KEY` | unset | the Bearer token a request must carry; unset, any non-empty token |
| `DIGITARIUM_STUB_SIGNING_KEY` | `test-signing-key-unit-only` (the original's `handler/webhook_digitarium_test.go:46`) | signs the webhook; must equal the terminal's `signingKey` |
| `DIGITARIUM_STUB_PUBLIC_URL` | `http://localhost:18109` | `redirectUrl`'s host, reached by a browser |
| `DIGITARIUM_STUB_WEBHOOK_URL` | under psp-stub, `PSP_STUB_GATEWAY_URL` + `/v1/webhooks/digitarium`; else the request's `webhookUrl` | where the webhook goes |
| `DIGITARIUM_STUB_WEBHOOK_BODY` | `flat` | the default `envelope` |
| `DIGITARIUM_STUB_WEBHOOK_DELAY_MS` | `500` | delay before the webhook |
| `DIGITARIUM_STUB_PORT` | `18109` | listen port |
