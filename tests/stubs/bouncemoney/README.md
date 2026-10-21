# bouncemoney stub

Answers the original psp-gateway's BounceMoney adapter
(`eitheror-platform/services/psp-gateway/internal/bouncemoney/`) as BounceMoney would. Port 18101.

## Pointing the original at it

```
BOUNCEMONEY_BASE_URL=http://psp-stub:18101
```

Read in `internal/config/config.go:60-64,177`; used in `bouncemoney/adapter.go:112-115`, which posts to
`{BOUNCEMONEY_BASE_URL}/checkout/sessions` (`adapter.go:148`). With the variable unset the adapter
falls back to **production**, `https://api.bouncecredits.com/api/v1` (`adapter.go:26-30`) — BounceMoney
has no sandbox — so the variable must be set for every recording run.

## What it answers

| Route | Answer |
|---|---|
| `POST /checkout/sessions` (also under `/api/v1`) | `201 {"statusCode":201,"data":{"id","url","status":"open","referenceId",…}}` — the adapter requires 201 and `data.url`. 401 when the `X-BounceMoney-*` headers are missing or (with a public key set) the Ed25519 signature is wrong; 400 below 15.00 or with fields missing. |
| `GET /checkout/{id}?redirect=…` | The hosted page: 302 back to the adapter's `redirectUrl`. |

Outcome (`POST /__control`, [psp-stub's README](../psp-stub/README.md) has the whole contract):
`success` → webhook `checkout.session.completed`; `failure` → session created, then webhook
`checkout.session.failed` (`code` `expired` → `checkout.session.expired`); `pending` → session created,
no webhook until the next control settles it; `unsigned` → the completed event with no
`X-BounceMoney-Signature` (the gateway answers 400); `unauthorised` → `401`. `POST /__webhook
{"reference": <referenceId>}` sends a session's webhook now.

## The webhook

`POST {gateway}/v1/webhooks/bouncemoney` (`cmd/psp-gateway/main.go:213`), JSON
`{"id","type","created","data":{"id","referenceId","status",…}}`, header
`X-BounceMoney-Signature: t=<unix>,v1=<base64url(HMAC-SHA256(secret, "<t>." + body))>` — the check in
`bouncemoney/signature.go:43-63`. BounceMoney's request carries no callback URL, so the stub takes the
scheme and host of the `redirectUrl` the adapter sends (`<PSP_GATEWAY_BASE_URL>/v1/psp/checkout/bouncemoney/<txnRef>/return`,
`adapter.go:118`): `PSP_GATEWAY_BASE_URL` must therefore be reachable from the stub's container, or set
`BOUNCEMONEY_STUB_WEBHOOK_URL`.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `BOUNCEMONEY_STUB_PORT` | `18101` | listen port |
| `BOUNCEMONEY_STUB_SIGNING_SECRET` | `test-signing-secret-unit-only` (the original's `handler/webhook_bouncemoney_test.go:36`) | signs the webhook; must equal the terminal's `signingSecret` property, which the gateway re-reads per webhook (`handler/webhook.go:1501-1513`). The original has no dev default. |
| `BOUNCEMONEY_STUB_WEBHOOK_URL` | under psp-stub, `PSP_STUB_GATEWAY_URL` + `/v1/webhooks/bouncemoney`; else derived from `redirectUrl` | where the webhook goes |
| `BOUNCEMONEY_STUB_PUBLIC_KEY` | unset | PKIX PEM Ed25519 public key; when set, request signatures are verified (`signature.go:17-33`) |
| `BOUNCEMONEY_STUB_PUBLIC_URL` | `http://localhost:18101` | host a browser reaches for `data.url` |
| `BOUNCEMONEY_STUB_WEBHOOK_DELAY_MS` | `500` | delay before the webhook |
