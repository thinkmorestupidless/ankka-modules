# Contract: grants (ankka 040)

Exactly these, per brand (FR-016). The manifest lists them; the tool makes the `made` ones after
the services exist and checks the `received` ones. No grant carries `decrypt` (R7). Between two
brands: none.

## Made by the brand `<slug>`

| Grantee | Target | Why |
|---|---|---|
| `service:payments/merchant` | route `wallet` `POST /v1/casino/brands/{brand}/wallets/{player}/{currency}/deposits` | a captured deposit, keyed by payment id (FR-017) |
| `service:payments/merchant` | route `wallet` `POST /v1/casino/brands/{brand}/payments/{paymentId}/outcome` | later outcomes of a payment, the same way |
| `service:payments/psp-gateway` | route `payouts` `POST /v1/casino/brands/{brand}/withdrawals/{requestId}/outcome` | the withdrawal payment's outcome |
| `service:programme/affiliate-programme` | topic `affiliates.attribution` `consume` | the programme reads attribution facts |
| `service:reasoner/audit` | topic `platform.audit` `consume` | the shared audit reads the brand's records |

The exact route paths are each owning service's (specs 002, 007); this file fixes the five grants,
their grantees and their kinds. A service spec that changes a path changes it here too.

## Received by the brand from `payments`

| Granter | Grantee | Target |
|---|---|---|
| `payments` | `service:<slug>/payouts` | routes `psp-gateway` `GET /v1/payments/withdrawal-terminals` (the brand's withdrawal-capable terminals), `POST /v1/payments/withdrawals` (initiate a withdrawal payment) and `GET /v1/payments/withdrawals/{requestId}` (its status) |
| `payments` | `service:<slug>/players` | `erasure` (the brand files a player's erasure in `payments`, FR-025) |

## Not grants

- `attribution` and `rewards` fetch the programme's key document, revocation list, legacy tag
  document and bonus code document as public clients (spec 012 FR-023, spec 013 FR-023a).
- A brand starts a deposit as a merchant of `payments` under its merchant key on `ingest`'s public
  routes (spec 006).

## Status

Each grant's `effect` as 040 reports it, plus `platform lacks 040` until it ships. `route not seen`
after apply means the owning service does not declare the route: the service's spec is wrong, not
the manifest (spec edge case).
