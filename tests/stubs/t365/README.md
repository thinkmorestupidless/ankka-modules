# t365 stub

Answers the original's T365 adapter
(`eitheror-platform/services/psp-gateway/internal/t365/adapter.go`) as T365 would. Default port
18107 (`T365_STUB_PORT` overrides).

## Point the original at it

`T365_BASE_URL=http://localhost:18107` (no trailing slash: the adapter appends
`/api/v1/external/payment/hosted`; `services/psp-gateway/internal/config/config.go:141-144`,
default `https://api.t365.io`). The Helm chart does not template `T365_BASE_URL`
(`starter-packs/payments/chart/templates/gateways/psp-gateway/deployment.yaml`), so in kind set it
on the deployment directly.

The terminal's `secretKey` property (merchant-service terminal properties `apiKey`, `secretKey`,
`midId`) must equal `T365_STUB_SECRET`: T365 is never sent the secret, so the stub has to be told it
to sign. The original has no local or test default for this credential (it is per-terminal, set in
the backoffice), so the stub's default `t365-stub-secret` is its own; configure the test terminal
with it. Postbacks go to the `callbackUrl` the adapter sends, built from `PSP_GATEWAY_BASE_URL`
(config.go:121-124).

## What it answers

| Request | Answer |
|---|---|
| `POST /api/v1/external/payment/hosted` (JSON, `apikey` header) | 200 `{"id","orderId","status":"PENDING","message","paymentUrl"}` |
| same, no `apikey` / missing field / bad orderId | 401 or 400 `{"status":"error","message":…}`, no `paymentUrl` (the adapter errors) |
| `GET /hosted/{id}` (the `paymentUrl`) | 302 to the `returnUrl` the adapter sent |

`paymentUrl` uses `T365_STUB_PUBLIC_URL` if set, else the request's Host.

After `T365_STUB_CALLBACK_DELAY_MS` (default 1000) from initiation it POSTs JSON
`{"id","orderId","status","amount","currency","hash"}` to `callbackUrl` with the `apikey` header it
received, retrying up to 5 times until 200. `status` is `SUCCESS`, `FAIL` (or the `code`, `REJECTED`) or
`PENDING` (settled by the next control) by the forced outcome; `unauthorised` answers `401`.
`POST /__webhook {"reference": <orderId>}` sends a postback now, once ([psp-stub's README](../psp-stub/README.md)
has the whole contract). `T365_STUB_WEBHOOK_URL` (under psp-stub, `PSP_STUB_GATEWAY_URL` +
`/v1/webhooks/t365`) replaces `callbackUrl`. `hash` is the adapter's algorithm (`internal/t365/signature.go`): SHA-256 of
`secretKey={secret padded with '0' to 16}&amount=…&currency=…&id={orderId}&orderId={orderId}&status=…`,
uppercase hex.

## Not reproduced

- The postback is sent on initiation, not when the player finishes on the hosted page.
- The hash is computed as the adapter verifies it (both `id` and `orderId` slots carry the merchant
  orderId); whether real T365 does the same is the adapter's assumption, not checked here.
- No payout and no status query: the adapter has neither.
