# psp-gateway

The shared `payments` project's payment providers behind one contract (spec 005): `merchant`'s deposits
and a brand's `payouts`' withdrawals through nine providers, their webhooks verified, settled once and
delivered. The design is `specs/005-psp-gateway/plan.md`; the decisions and what established them are
its `research.md`.

## Build and test

```bash
(cd ../.. && sbt contract/publishLocal)   # the contract this build depends on
sbt test                                  # Docker must be running: Postgres and psp-stub's image
```

`just psp-gateway-test` does both from the repository root; `just psp-gateway-it` runs the integration
and parity suites alone. `just psp-stub` then `just psp-gateway-run` runs the service on 9001 with every
provider at the stub.

## The components

| Kind | Name | What it holds |
|---|---|---|
| event sourced | `deposit-session` | one deposit by payment id: the claim (provider, method, sub-brand, amount, where its outcome goes), the initiation, the settlement, the delivery (FR-001 to FR-015) |
| event sourced | `payout-session` | one withdrawal by request id: the claim, the provider's answer, the settlement, the delivery (FR-017) |
| key value | `reference-index` | a provider's own reference (Morphosis's token) to the session it names (FR-011) |
| view | `deposit-rows`, `payout-rows` | a row per session: kind, provider, receiver, outcome, delivery, attempts, last answer, since; nothing personal, nothing secret (FR-016, FR-022) |
| consumer | `deposit-deliverer`, `payout-deliverer` | on a settlement or a re-drive: the outcome to `merchant` (signed when the credential has a `webhookSecret`) or to the brand's `payouts`, up to 4 attempts in 15 s, then recorded delivered, refused or undelivered (FR-014, FR-015) |
| timed action | `redrive` | every `REDRIVE_INTERVAL`: the 500 oldest undelivered outcomes re-driven; a refused one is left for an operator (FR-016) |

The providers are pure packages under `providers/<name>/`, each with the original's cases: what to send,
how to read the answer, how to verify and read a webhook. `providers/Catalogue` says what each offers.
`crypto/`, `money/` and `deposits/Variance` are the rest of the rules.

| Provider | Deposits | Payouts | Status read |
|---|---|---|---|
| DreamzPay | cards, by a relay page | · | · |
| Unity Finance | cards, wallets (`checkoutId.<METHOD>`) | bank transfer | yes |
| T365 | PIX | · | · |
| Cozeni | crypto, quoted | crypto | · |
| EuPaymentz | cards, a relay page for a POST redirect | · | · |
| Gigadat | Interac | e-Transfer | · |
| BounceMoney | open banking, Ed25519-signed | · | · |
| Digitarium | cards | · | · |
| Morphosis | crypto, by its token | crypto | · |

## The routes

One endpoint per prefix (ankka refuses two sharing one), each route's audience its ACL. The service
is exposed for the providers' webhooks, the player's browser and the payments operators.

| Prefix | Audience |
|---|---|
| `/v1/psp` | `merchant` by identity: `hpp/initiate`, `sessions/{reference}` |
| `/v1/psp/checkout` | public: the relay pages and every return page, which settle nothing |
| `/v1/webhooks` | public: each provider's webhook, verified in the handler |
| `/v1/payments/withdrawals`, `/v1/payments/withdrawal-terminals` | a brand's `payouts`, under the grants the manifest makes |
| `/v1/psp/outcomes` | a payments operator: the undelivered and refused outcomes, and a re-drive |
| `/status` | the project's services, and a payments operator through the ingress: each provider's health and whether it is configured, and the two counts |

## Settings

`PUBLIC_URL` (required: the base of every address a provider or a browser is given), `PROVIDER_TIMEOUT`
(20s), `REDRIVE_INTERVAL` (2m), `MERCHANT_SERVICE` (`merchant`), and each provider's base URL:
`DREAMZPAY_BASE_URL`, `UNITYFINANCE_PAY_URL`, `UNITYFINANCE_API_URL`, `T365_BASE_URL`, `COZENI_BASE_URL`,
`EUPAYMENTZ_BASE_URL`, `GIGADAT_BASE_URL`, `BOUNCEMONEY_BASE_URL`, `MORPHOSIS_BASE_URL`,
`DIGITARIUM_SANDBOX_URL`, `DIGITARIUM_PRODUCTION_URL`. A provider with no base URL refuses by name
(`502 provider_not_configured`) and reads `configured: false` on `/status`. No secret is a setting: every
credential is read from `merchant` at the moment of the call and held by nothing (FR-018, FR-019).

## The suites

| Level | Where | What |
|---|---|---|
| unit | `providers/`, `crypto/`, `money/`, `deposits/`, `delivery/`, `sessions/`, `merchant/`, `payouts/` | the pure rules with the original's cases; the entities and deliverers in ankka's unit kits |
| integration | `it/` | the whole service over Postgres with `FakeMerchant`, `FakePayouts` and psp-stub in Docker: initiate, webhooks, repeats, crypto, payouts, pages, re-drive (SC-003), rotation (SC-006), leaks (SC-004), status, durability across restarts |
| features | `features/` | `features/payments/{providers,crypto-payments,withdrawal-payments,outcomes}.feature` |
| parity | `port/` | `ParitySuite`: one test per `tests/port/psp-gateway/**/*.json` replayed against the service over psp-stub, each difference from the original a named entry of `Parity.divergences`; `ParityFixtureSuite` proves the replay reports; `CoverageSuite` (SC-001) wants a recording per deposit and payout path (`tests/port/README.md`) |

Each suite that stops or restarts its service, shortens its timer or rotates every credential runs in a
JVM of its own (`build.sbt`). `FakesMain` runs `FakeMerchant` and `FakePayouts` alone, for a local run.

## psp-stub

`tests/stubs/psp-stub/README.md`: the nine providers on 18101–18109, steered by `POST /__control`
(sticky), made to send a webhook by `POST /__webhook`, and read back by `GET /__received`.

## Recording the original

`tests/original/README.md`: `gateway-driver/` records the original's gateway against psp-stub; the
recordings go to `tests/port/psp-gateway/`.
