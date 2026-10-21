# psp-stub

The nine payment-provider stubs in one programme and one image (spec 005 FR-025, research R15). Each
provider answers its own API shape on its own port, so the original's psp-gateway and the port can
both run a deposit or a payout with no request leaving the machine. Each provider's `README.md`
names its routes, signatures and settings.

| Provider | Port | Gateway setting | Webhook route on the gateway |
|---|---|---|---|
| [BounceMoney](../bouncemoney/README.md) | 18101 | `BOUNCEMONEY_BASE_URL` | `/v1/webhooks/bouncemoney` |
| [Cozeni](../cozeni/README.md) | 18102 | `COZENI_BASE_URL` | `/v1/webhooks/cozeni`, `…/withdrawal` |
| [DreamzPay](../dreamzpay/README.md) | 18103 | `DREAMZPAY_BASE_URL` (browser-reached) | `/v1/webhooks/dreamzpay` |
| [EuPaymentz](../eupaymentz/README.md) | 18104 | `EUPAYMENTZ_BASE_URL` | `/v1/webhooks/eupaymentz` |
| [Gigadat](../gigadat/README.md) | 18105 | `GIGADAT_BASE_URL` | `/v1/webhooks/gigadat` |
| [Morphosis](../morphosis/README.md) | 18106 | `MORPHOSIS_BASE_URL` | `/v1/webhooks/morphosis/deposit/{token}`, `…/withdrawal/{token}` |
| [T365](../t365/README.md) | 18107 | `T365_BASE_URL` | `/v1/webhooks/t365` |
| [Unity Finance](../unityfinance/README.md) | 18108 | `UNITYFINANCE_PAY_URL` (the host), `UNITYFINANCE_API_URL` (the host + `/v1`) | `/v1/webhooks/unityfinance`, `…/withdrawal` |
| [Digitarium](../digitarium/README.md) | 18109 | `DIGITARIUM_SANDBOX_URL`, `DIGITARIUM_PRODUCTION_URL` (the original: `DIGITARIUM_BASE_URL`) | `/v1/webhooks/digitarium` |

## Running it

```bash
cd tests && go build -o stubs/bin/ ./stubs/psp-stub && PSP_STUB_GATEWAY_URL=http://localhost:9001 stubs/bin/psp-stub
cd tests && docker build -f stubs/psp-stub/Dockerfile -t psp-stub .      # the image, from tests/ as the context
docker run --rm -p 18101-18109:18101-18109 -e PSP_STUB_GATEWAY_URL=http://host.docker.internal:9001 psp-stub
```

| Variable | Default | Purpose |
|---|---|---|
| `PSP_STUB_GATEWAY_URL` | unset | the gateway's base; each provider's webhook address defaults to it plus the route above |
| `<PROVIDER>_STUB_WEBHOOK_URL` | `PSP_STUB_GATEWAY_URL` + route | where that provider's webhooks go. Where the request names its own callback (Cozeni, Morphosis, Unity Finance), the setting replaces the address up to the route and keeps the rest (`/withdrawal`, `/deposit/{token}`, the query); elsewhere it replaces it outright. Unset with no gateway URL: the request's own callback address, as before |
| `<PROVIDER>_STUB_PORT` | the port above | listen port |
| `PSP_STUB_PROVIDERS` | all nine | a comma list of provider names to serve only those |

`<PROVIDER>` is `BOUNCEMONEY`, `COZENI`, `DREAMZPAY`, `EUPAYMENTZ`, `GIGADAT`, `MORPHOSIS`, `T365`,
`UNITYFINANCE` or `DIGITARIUM`. Each provider's own variables (keys, delays, public URLs) are in its README.

## Controls (every port, from `stubhttp`)

| Route | Body | Answer |
|---|---|---|
| `GET /healthz` | | `{"stub":"<provider>","status":"ok"}` |
| `POST /__control` | `{"outcome", "code"?, "receivedAmount"?, "convertAmount"?, "signatureEncoding"?, "envelope"?, "delayMs"?}` | `{"outcome"}`, plus `"settled": [{"reference", "status", "body"}]` when it settled pending sessions; `400` with `supported` for an outcome the provider lacks |
| `POST /__webhook` | `{"reference"}` plus any control field as an override | sends the provider's webhook for that reference now, once, and answers `{"status": <gateway status>, "body": <gateway body>}` (the body as JSON when it is JSON, else a string); `404` for an unknown reference, `400` for an outcome with no webhook, `502` when the gateway was not reached |
| `GET /__received` | | every request the stub received, oldest first |

The control is sticky: it holds for every request until the next `/__control`. `delayMs` replaces the
provider's delay before an automatic webhook. A `pending` session sends no terminal webhook (some
providers send their open status); the next control with another outcome settles every pending session
by sending its webhook with that control.

Outcomes, per provider (`·` = `400`):

| Outcome | BounceMoney | Cozeni | DreamzPay | EuPaymentz | Gigadat | Morphosis | T365 | Unity Finance | Digitarium |
|---|---|---|---|---|---|---|---|---|---|
| `success` | completed | `00` | `Y` | `00000` | `STATUS_SUCCESS` | `ac` | `SUCCESS` | `success` / state 8 | `COMPLETED` |
| `failure` (`code`) | failed, `expired` | `CANCELLED` code (99); payout rejected code (93) | `N` | code (`S00050001`) | rcode (231), or a `STATUS_*` | `de`, code as message | `FAIL`, or `REJECTED` | `canceled` + cancel code, `expired`; payout `canceled` 9, `limitExceeded` 20 | `DECLINED` errorCode (05), or `CANCELLED` |
| `pending` | no webhook | no callback | no notification | no callback | `STATUS_INITED` | `pe` | `PENDING` | no webhook; state 1 | `PENDING` |
| `duplicate` | · | `422` msgCode 13 | · | · | · | · | · | payout error "already exists (duplicate)" | · |
| `no_record` | · | · | · | · | · | · | · | payout not kept; status read `data: []` | · |
| `reversed` | · | · | · | · | · | · | · | `chargebacked` / state 11 | · |
| `underpayment` | · | `03`, `receivedAmount`, `convertAmount` (0 = unpriced) | · | · | · | `ac` short (`receivedAmount`, else 90%) | · | · | · |
| `overpayment` | · | `02`, `receivedAmount`, `convertAmount` | · | · | · | `ac` over (110%) | · | · | · |
| `unsigned` | completed, no `X-BounceMoney-Signature` | · | · | · | · | · | · | · | `COMPLETED`, no `Signature` |
| `unauthorised` | `401` | · | · | · | `401` | · | `401` | `401` on payout and status read | `401` |

Empty credentials are refused `401` by BounceMoney, Gigadat, T365, Unity Finance and Digitarium
whatever the control. Digitarium's `signatureEncoding` is `hex` (default), `base64` or `base64url`, and
its `envelope` is `flat` (default) or `result`.

## The image

`Dockerfile` here: `golang:1.22` builds a static binary, `gcr.io/distroless/static-debian12:nonroot`
runs it, ports 18101–18109 exposed. The context is `tests/` (the module root). The port's integration
kit starts it as a testcontainer (spec 005 T025); the original's recording runs it from
`tests/original/compose.override.yml`.
