# dreamzpay stub

Stands in for DreamzPay's hosted checkout for the original psp-gateway's DreamzPay adapter
(`eitheror-platform/services/psp-gateway/internal/dreamzpay/`). Port 18103.

## Pointing the original at it

```
DREAMZPAY_BASE_URL=http://localhost:18103
```

Read in `internal/config/config.go:25-26,116-119` (default `https://secure.dreamzpay.com`). The gateway
never calls DreamzPay itself: its relay page makes the **player's browser** POST the form to
`{DREAMZPAY_BASE_URL}/transaction/Checkout` (`handler/hpp.go:1035`, `dreamzpay/relay.html`). So the
value must be reachable from the browser (the host), not a container name.

## What it answers

`POST /transaction/Checkout` (form: `memberId, totype, amount, currency, merchantTransactionId,
merchantRedirectUrl, notificationUrl, checksum[, terminalId]`). It checks
`checksum = MD5(memberId|totype|amount|merchantTransactionId|merchantRedirectUrl|secureKey)`
(`dreamzpay/signature.go:9-14`; 400 on a mismatch), then answers a page that auto-POSTs the browser
to `merchantRedirectUrl` with `paymentId, merchantTransactionId, amount, currency, status (Y|N),
checksum` (read by `handler/hpp.go:902-998`), and after a delay POSTs the notification.

Outcome (`POST /__control`, [psp-stub's README](../psp-stub/README.md) has the whole contract):
`success` → Y; `failure` → N; `pending` → a "pending" page, no return POST and no notification until
the next control settles it. DreamzPay has none of the other outcomes. `POST /__webhook
{"reference": <merchantTransactionId>}` sends a notification now.

## The notification

Form-encoded POST to the form's `notificationUrl`, `<PSP_GATEWAY_BASE_URL>/v1/webhooks/dreamzpay`
(`dreamzpay/adapter.go:61`, `cmd/psp-gateway/main.go:163`), with `paymentId, merchantTransactionId,
amount, currency, status (capturesuccess|capturefailed), transactionStatus (Y|N), checksum`, where
`checksum = MD5(paymentId|merchantTransactionId|amount|transactionStatus|secureKey)`
(`dreamzpay/signature.go:16-26`, checked at `handler/webhook.go:215`).

The original's e2e scenarios 13 and 14 never reach this stub: they post their own signed webhook to
the gateway (`tests/e2e/scenario13_dreamzpay_e2e_test.go:145-184`) and skip unless
`DREAMZPAY_MEMBER_ID`, `DREAMZPAY_TOTYPE` and `DREAMZPAY_SECURE_KEY` are set.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `DREAMZPAY_STUB_PORT` | `18103` | listen port |
| `DREAMZPAY_STUB_SECURE_KEY` | `dreamzpay-test-key` (the original's `tests/k6/lib/payments.js:153`) | the terminal's `secureKey`; set the e2e suite's `DREAMZPAY_SECURE_KEY` to the same value |
| `DREAMZPAY_STUB_NOTIFICATION_URL` or `DREAMZPAY_STUB_WEBHOOK_URL` | under psp-stub, `PSP_STUB_GATEWAY_URL` + `/v1/webhooks/dreamzpay`; else the form's `notificationUrl` | where the notification goes |
| `DREAMZPAY_STUB_WEBHOOK_DELAY_MS` | `500` | delay before the notification |
