# Data model: psp-gateway

Phase 1 of [plan.md](plan.md). Every stored type has a codec from `GatewayCodecs`, as `merchant`'s use
`MerchantCodecs`. Amounts are `Long` minor units with a `contract.money.Currency`. No stored type holds a
credential value or a player's personal field (FR-022). The player fields `merchant` sends at initiation
go to the provider and nowhere else.

## Shared types

```text
Provider     DREAMZPAY | UNITYFINANCE | T365 | COZENI | EUPAYMENTZ | GIGADAT | BOUNCEMONEY | DIGITARIUM | MORPHOSIS
Outcome      Captured(variance?) | Failed(code?, reason) | Completed | PayoutFailed(reason) | Reversed
             (pending is "no outcome yet", never stored as one; a provider's cancellation is a failure; FR-005)
Variance     kind UNDERPAYMENT | OVERPAYMENT, quoted, receivedCryptoAmount?, receivedConvertAmount?, receivedConvertCurrency?
             (the provider's strings, passed to merchant as the original passes them)
Receiver     Merchant(paymentId)               → payments/merchant  POST /v1/payments/{paymentId}/capture | /fail
             Payouts(brand, requestId)         → {brand}/payouts    POST /v1/casino/brands/{brand}/withdrawals/{requestId}/outcome
Delivery     NotYet | Delivered(at, status) | Refused(at, status, answer) | Undelivered(at, attempts, lastAnswer)
Destination  Redirect(url) | Crypto(address, coinAmount, coinCurrency, validUntil?)
```

`Receiver` is derived from the session at claim time and never from a webhook (FR-017).

## `DepositSession`: event sourced, id = `merchant`'s payment id

| State field | From |
|---|---|
| `provider`, `method`, `subBrandId`, `terminalId?`, `customerId` (the player's id, not personal) | `Claimed` |
| `amount: Long`, `currency` | `Claimed` |
| `generated`: what the gateway made before the call (Cozeni `requestSalt`, Morphosis `token`, the return and webhook addresses) | `Claimed` |
| `providerReference?`, `destination?`, `providerData` (Unity Finance `checkoutId`, Cozeni `cozeniTxnId`, Morphosis `wpbId` and `expectedCoinAmount`, Digitarium `g2payId`, EuPaymentz relay `form`) | `Initiated` |
| `refusal?` (player-facing or generic, with the provider's status) | `InitiationFailed` |
| `outcome?`, `providerStatus?`, `settledAt?` | `Settled` |
| `delivery`, `attempts`, `redrives` | `DeliveryRecorded`, `Redriven` |

| Event | Persisted by | Rule |
|---|---|---|
| `Claimed(...)` | `claim` | Only on an empty session. A second `claim` replies with the current state and persists nothing (FR-008) |
| `Initiated(reference, destination, providerData, at)` | `initiated` | Only from claimed |
| `InitiationFailed(refusal, at)` | `initiationFailed` | Only from claimed. Final: the session settles nothing (FR-024) |
| `Settled(outcome, providerStatus, at)` | `settle` | Only from claimed or initiated, never twice. A second `settle` replies `AlreadySettled(outcome)`. Two concurrent webhooks are serialised here (SC-002) |
| `DeliveryRecorded(delivery, attempts, at)` | `recordDelivery` | Only after `Settled`. Ignored once `Delivered` or `Refused` |
| `Redriven(by: timer \| operator id, at)` | `redrive` | By the timer only while `Undelivered`; by an operator while `Undelivered` or `Refused` (FR-016). Otherwise it replies with the delivery state and persists nothing |

States: `Empty → Claimed → Initiated → Settled → (Delivered | Refused | Undelivered ⇄ Redriven)`, with
`Claimed → InitiationFailed` final. A webhook on `Claimed` is allowed when the provider's verification
needs nothing `Initiated` brings. Morphosis's `wpbId` check does need it: Morphosis on `Claimed` is
acknowledged and moves nothing (research R3).

Queries: `get` (the whole state, for the routes and the deliverer), `status` (the 004 contract's
`state`, `outcome`, `delivered`, `undelivered` and `updatedAt`).

## `PayoutSession`: event sourced, id = the withdrawal request id

| State field | From |
|---|---|
| `brand`, `provider`, `terminalId`, `method`, `amount: Long`, `currency` | `Claimed` |
| `generated` (Cozeni `requestSalt`, Morphosis `token`, the webhook address) | `Claimed` |
| `answer`: `Initiated(providerReference)` \| `Completed` \| `Rejected(reason)` \| `InDoubt(reason)` \| `AlreadyInitiated` | `Answered` |
| `outcome?`, `providerReference?` | `Settled` |
| `delivery`, `attempts`, `redrives` | as a deposit's |

The destination (IBAN, names, e-Transfer email and mobile, bank account, wallet address, memo) goes to the
provider and is not kept (FR-022). A `claim` naming another brand than the session's is refused `409`,
since a request id is unique within a brand and the gateway is shared.

Events: `Claimed`, `Answered`, `Settled`, `DeliveryRecorded`, `Redriven`. These follow a deposit's rules.
An answer of `Completed` settles the payout in the same command (`Answered` then `Settled(Completed)`),
so `payouts` is told by the deliverer as for a webhook. A status read that finds a final state settles
the same way. `Settled` admits `Reversed` after `Completed` (the one later outcome 007 FR-019 accepts),
and nothing else after a final outcome.

## `ReferenceIndex`: key value, id = `<provider>/<reference>`

`{kind: deposit | payout, key}`. Written by the initiate before the provider is called, for T365's
dashless `orderId`, Morphosis's deposit and payout tokens, and nothing else (research R4). Never updated.
It expires with the session's lifetime: `expireAfter` 400 days, past any provider's redelivery and every
dispute window the original kept twins for.

## `SessionsView`: a `KeyedView`, one row per session of either kind

A `KeyedView` with two sources (`eventsOf(DepositSession)`, `eventsOf(PayoutSession)`; ankka 0.11
`views.md`, "Several entities into one table"). Each row has `kind`, `key`, `provider`, `subBrandId` or `brand`, `state`,
`outcome?`, `delivery`, `updatedAt` and `receiver`, with no amounts beyond `amount` and `currency`.

| Query | For |
|---|---|
| `undelivered` (`delivery = 'Undelivered'`, oldest first, limit) | the `Redrive` timer and the operator listing (FR-016) |
| `refused` | the operator listing |
| `byProvider(provider, since)` | the status page's counts |

## `OutcomeDeliverer`: consumer of `DepositSession` and `PayoutSession` events

The consumer reacts to `Settled` and `Redriven` (research R5):

1. Read the session.
2. If its delivery is `Delivered`, do nothing. If it is `Refused`, do nothing unless this is an
   operator's `Redriven`.
3. Otherwise build the receiver's call from the session:
   - a deposit: capture or fail with the 004 `gateway.md` body, signed when the credential has a
     `webhookSecret` (research R13);
   - a payout: 007's outcome body.
4. Call it as this service, with up to 4 attempts, 250 ms doubling, a 15 s budget and 10 s per call.
5. Persist `DeliveryRecorded`.

Every other event is ignored. Nothing is thrown.

## `Redrive`: timed action, recurring every `REDRIVE_INTERVAL` (2 minutes)

The timer reads `SessionsView.undelivered` (limit 500, oldest first) and calls `redrive` on each session
(`by = timer`). A failed run is retried by the timer runtime. Missed periods are skipped.

## Pure packages (no component)

| Package | Holds |
|---|---|
| `providers.<name>` (nine) | request building, response parsing, signing, verification and status maps, each provider's own, with the original's cases ported |
| `providers.Catalogue` | per provider: operations offered, methods per currency, minimums, payout review window, `hasStatus`, credential names, base URL setting names |
| `money.Render` | minor units to each provider's form (`%.2f` string, `%d.%02d`, a double, Gigadat dollars, Cozeni's 8-place coin), over `contract.money.Amounts` |
| `crypto.Underpaid` | the exact rational comparison of coin amounts (Morphosis; unreadable is short) |
| `delivery.Backoff` | the attempt schedule and the budget |
| `delivery.Signature` | `X-PSP-Signature` and `X-PSP-Timestamp` for `merchant` (the inverse of 004's `Hmac.verify`) |
| `http.Form`, `http.Raw` | form encoding and decoding over the raw body; content-type sniffing for DreamzPay and Gigadat |

## Settings (`Settings.scala`, from the environment)

| Name | Default | Meaning |
|---|---|---|
| `PUBLIC_URL` | none (required) | the base of every address given to a provider and a browser |
| `DREAMZPAY_BASE_URL`, `UNITYFINANCE_PAY_URL`, `UNITYFINANCE_API_URL`, `T365_BASE_URL`, `COZENI_BASE_URL`, `EUPAYMENTZ_BASE_URL`, `GIGADAT_BASE_URL`, `BOUNCEMONEY_BASE_URL`, `MORPHOSIS_BASE_URL`, `DIGITARIUM_SANDBOX_URL`, `DIGITARIUM_PRODUCTION_URL` | none | a provider without one refuses initiation by name (FR-020) |
| `PROVIDER_TIMEOUT` | `20s` | per outbound provider call (FR-023) |
| `REDRIVE_INTERVAL` | `2m` | the re-drive cadence (FR-016) |
| `MERCHANT_SERVICE` | `merchant` | the name of the receiver and credential source, within the project |

No secret is a setting: every credential is read from `merchant` (research R7).
