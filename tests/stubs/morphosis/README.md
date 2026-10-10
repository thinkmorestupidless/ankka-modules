# morphosis stub (QVEI WPB API)

Answers the original's Morphosis adapter
(`eitheror-platform/services/psp-gateway/internal/morphosis/adapter.go`) as QVEI would. Default port
18106 (`$PORT` overrides).

## Point the original at it

`MORPHOSIS_BASE_URL=http://localhost:18106/`
(`services/psp-gateway/internal/config/config.go:179`, read by `resolveBaseUrl` in
`internal/morphosis/adapter.go:35-40`; unset, the adapter uses the hard-coded production default
`https://wpb-api.qvei.com/`). In the Helm chart it is `pspGateway.morphosisBaseUrl`
(`starter-packs/payments/chart/templates/gateways/psp-gateway/deployment.yaml:60-61`).

The callbacks go to the `notification_url` the adapter sends, built from `PSP_GATEWAY_BASE_URL`
(config.go:121-124, default `http://localhost:8087`), so that must be reachable from the stub.

## What it answers

| Request | Answer |
|---|---|
| `POST /?action=insert_deposit` (form) | `{"result":"ok","status":"pe","id":N,"address":"…","coin_amount":X}`, a quote at a fixed stub rate (bitcoin 60000 USD, ethereum 3000, stablecoins 1; table in `provider.go`) |
| `POST /?action=insert_payout` (form) | `{"result":"ok","status":"pe","id":N,"coin_amount":X,"processed":"0"}` |
| missing field / unknown action | `{"result":"error","detail":"…"}` (the adapter's API error / `RejectedError`) |

After `MORPHOSIS_STUB_CALLBACK_DELAY_MS` (default 1000) it POSTs JSON
`{"id":N,"status":…,"tx_hash":…,"coin_amount":X}` to the `notification_url`, retrying up to 5 times
until the gateway answers 200 `OK`. By the forced outcome (`POST /__control`):

| Outcome | Deposit callback | Payout callback |
|---|---|---|
| success | `ac`, the quoted coin amount | `ac`, with a `tx_hash` |
| failure | `ac`, 90% of the quoted coin amount: the gateway fails it as underpaid | `de` |
| pending | `pe` (non-terminal; nothing further is sent) | `pe` |

QVEI does not sign callbacks, so neither does the stub: the gateway authenticates them by the
notify token in the `notification_url` path and the `id` matching the one insert_deposit returned
(`internal/morphosis/sessions.go`, `internal/handler/morphosis.go`). No secret is needed;
`user` and `pass` must be non-empty but are not checked.

## Not reproduced

- The quote has no deadline: the adapter reads none (`hpp.go` leaves `validUntil` empty for Morphosis).
- Rates are fixed, not live; there is no status query (the adapter does not poll).
- QVEI's real retry schedule (every 60 s, 20 times) is shortened to 5 tries a second apart.
