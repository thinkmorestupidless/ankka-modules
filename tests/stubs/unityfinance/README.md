# unityfinance stub

Answers the original psp-gateway's Unity Finance adapter
(`eitheror-platform/services/psp-gateway/internal/unityfinance/adapter.go`) in the shapes confirmed live,
and sends its signed webhooks. Port 18108 under [psp-stub](../psp-stub/README.md). It replaces the
original's `tests/uf-stub`, which speaks none of the live payout shapes (`status:"ok"` with
`data.withdraw.id`, the status read's array, the withdrawal webhook's `state` by name).

## Pointing the original at it

```
UNITYFINANCE_PAY_URL=http://psp-stub:18108
UNITYFINANCE_API_URL=http://psp-stub:18108/v1
```

Read in `internal/config/config.go:138-146` (defaults `https://old-pay.unityfinance.com` and
`https://old-api.unityfinance.com/v1`, production). The invoice is POSTed to the pay URL itself
(`adapter.go:165`); the payout and the status read go to `{API}/withdraw` (`adapter.go:365`, `:523`).

## What it answers

| Route | Answer |
|---|---|
| `POST /` (any path but `/v1/withdraw`), the invoice: `ik_*` form with `ik_int=json` | `ik_sign` checked with the signKey over every field but itself; `{"resultCode":0,"resultData":{"paymentForm":{"action":<stub>/checkout/<id>,"method":"get","parameters":{"ik_pm_no"}}}}` as `adapter.go:178-205` reads it. A bad signature: `{"resultCode":115,"resultMsg":"error 115"}`; a missing `ik_co_id`, `ik_pm_no`, `ik_am`, `ik_cur` or `ik_sign`: `resultCode 2`. |
| `GET /checkout/{id}` | the hosted page: 302 to `ik_suc_u` (`ik_fal_u` under `failure`) |
| `POST /v1/withdraw` (form, Basic `userId:withdrawalApiKey`, `Ik-Api-Account-Id`) | `200 {"status":"ok","code":0,"data":{"withdraw":{"id","paymentNo","state":1,"result":0,"psResultDesc"},…}}`. `401 {"status":"error","code":4,…}` on empty or wrong Basic auth or no account id. A `paymentNo` already seen: `200 {"status":"error","code":11,"message":"Withdraw with paymentNo … already exists (duplicate)"}` (`adapter.go:430-436`). |
| `GET /v1/withdraw?paymentNo=` (same auth) | `{"status":"ok","code":0,"data":[{"id","paymentNo","state":"8","result":"0","psResultDesc","stateName"}]}`, the state quoted; `data: []` for no record. The state is `1` until the payout's webhook is sent, then `8`, `9`, `20` or `11`. |

Both routes also answer without the `/v1` prefix.

## The webhooks

Form-encoded, `ik_sign` = base64(SHA-256(the other fields' values sorted by key, joined `:`, + `:` +
signKey)) (`signature.go:11-35`), verified by the gateway over every posted field
(`handler/webhook.go:339-349`) or over the body only (`withdrawal_webhook.go:108-119`).

- **Deposit**, to the invoice's `ik_ia_u` (`<gateway>/v1/webhooks/unityfinance`): `ik_co_id` (the
  invoice's), `ik_pm_no`, `ik_inv_id`, `ik_inv_st`, `ik_inv_crt`, `ik_am`, `ik_cur`, `ik_pw_via`, and on a
  cancel with a code `ik_inv_cancel_code`. All fields are `ik_`.
- **Payout**, to its `interactionUrl` (`<gateway>/v1/webhooks/unityfinance/withdrawal?merchantId=&brandId=&terminalId=`):
  `id`, `purseId`, `paymentNo`, `state` (by name), `processed`, `payerWriteoffAmount`,
  `payeeReceiveAmount`, `created`, `details_first_name`, `details_last_name`, `details_iban`, as the live
  callback of 2026-08-06 (`withdrawal_webhook.go:30-38`). The query is not signed.

## Outcomes (`POST /__control`)

| Outcome | Deposit | Payout | Status read |
|---|---|---|---|
| `success` | `ik_inv_st=success` | `state=success` | `8` |
| `failure` | `canceled` with `ik_inv_cancel_code` = `code`; `code` `expired` → `expired` | `canceled`; `code` `limitExceeded` or `20` → `limitExceeded` | `9`, or `20` |
| `pending` | no webhook until the next control (`/__webhook` sends `process`) | likewise | `1` |
| `reversed` | `chargebacked` (logged only by the original) | `chargebacked` | `11` |
| `duplicate` | — | the duplicate error, on a first `paymentNo` too | — |
| `no_record` | — | accepted but not kept, no webhook | `data: []` for every read |
| `unauthorised` | — | `401` | `401` |

`POST /__webhook {"reference": <ik_pm_no or paymentNo>}` sends that webhook now, once.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `UNITYFINANCE_STUB_SIGN_KEY` | `unityfinance-signing-secret` (the original's `handler/session_secrets_test.go:116`) | checks the invoice and signs both webhooks; must equal the terminal's `signKey` |
| `UNITYFINANCE_STUB_USER_ID`, `UNITYFINANCE_STUB_WITHDRAWAL_API_KEY` | unset | when set, the Basic credentials a payout must carry; unset, any non-empty pair |
| `UNITYFINANCE_STUB_PUBLIC_URL` | `http://localhost:18108` | the payment form's action, reached by a browser |
| `UNITYFINANCE_STUB_WEBHOOK_URL` | under psp-stub, `PSP_STUB_GATEWAY_URL` + `/v1/webhooks/unityfinance`; else the request's | replaces `ik_ia_u` and `interactionUrl` up to `/v1/webhooks/unityfinance`, keeping `/withdrawal` and the query |
| `UNITYFINANCE_STUB_WEBHOOK_DELAY_MS` | `500` | delay before a webhook |
| `UNITYFINANCE_STUB_PORT` | `18108` | listen port |

## Not reproduced

- The IP allowlist (the original only logs it) and UF's 13 redeliveries: each webhook is sent once.
- `ik_error_desc` is never sent, so the gateway's default reasons apply.
