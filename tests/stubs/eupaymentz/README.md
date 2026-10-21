# eupaymentz stub

Answers as EuPaymentz (Secure1Gateway) answers the original's adapter,
`services/psp-gateway/internal/eupaymentz/adapter.go`. Default port 18104 (`EUPAYMENTZ_STUB_PORT` overrides).

## Pointing the original at it

`EUPAYMENTZ_BASE_URL=http://psp-stub:18104` on psp-gateway — read at
`services/psp-gateway/internal/config/config.go:168-171` (default `https://ts.secure1gateway.com`,
which is production: EuPaymentz has no sandbox). The adapter posts to `<EUPAYMENTZ_BASE_URL>/api/v2/processTx`
(`adapter.go:173`).

The webhook goes to the `merchant_url_callback` the adapter sends, `<PSP_GATEWAY_BASE_URL>/v1/webhooks/eupaymentz`
(`adapter.go:141`; `PSP_GATEWAY_BASE_URL` read at `config.go:121-124`, default `http://localhost:8087`), so
that setting must be an address the stub can reach.

The EUPAYMENTZ terminal the deposit routes to must carry `passphrase` equal to `EUPAYMENTZ_STUB_PASSPHRASE`
(plus any `accountId` and `password`): the adapter signs with it and the gateway verifies the webhook with it,
re-read from the terminal (`handler/webhook.go:1217`).

## Routes

| Route | Answer |
|---|---|
| `POST /api/v2/processTx` | Checks `account_sha` = SHA256(passphrase + transac_amount + account_id + cust_email + customer_ip). Answers `{"success":true,"data":{"resp_trans_status":"PEND","UrlToRedirect":…,"UrlToRedirectMethod":"GET","resp_trans_merchant_id":<merchant_payment_id>,…}}`; a bad signature or missing field answers `success:false`. |
| `GET /api/v2/ProcessTxGetHosted?transkey=` | The hosted page the player is sent to; links back to `merchant_url_return`. |
| `GET /healthz`, `POST /__control`, `GET /__received` | From `stubhttp`. |

After processTx the stub posts the flat JSON callback to `merchant_url_callback`, signed
`resp_sha` = SHA256(passphrase + resp_trans_id + resp_trans_amount + resp_trans_status)
(`eupaymentz/signature.go:23-25`). Outcome forced by `/__control` ([psp-stub's README](../psp-stub/README.md)
has the whole contract): `success` sends `00000` (captured), `failure` sends the `code` (default
`S00050001`, refused), `pending` sends no callback until the next control settles it. Hosted mode always
answers `PEND` at initiation, so the outcome only shows in the callback. `POST /__webhook
{"reference": <merchant_payment_id>}` sends a callback now.

## Environment

| Variable | Default | |
|---|---|---|
| `EUPAYMENTZ_STUB_PASSPHRASE` | `sk-test` | the value the original's webhook test uses (`handler/webhook_eupaymentz_test.go:84`) |
| `EUPAYMENTZ_STUB_WEBHOOK_URL` | under psp-stub, `PSP_STUB_GATEWAY_URL` + `/v1/webhooks/eupaymentz`; else `merchant_url_callback` | where the callback goes |
| `EUPAYMENTZ_STUB_WEBHOOK_DELAY_MS` | `1500` | delay before the callback |
| `EUPAYMENTZ_STUB_PORT` | `18104` | |
