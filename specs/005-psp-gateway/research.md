# Research: psp-gateway

Phase 0 of [plan.md](plan.md). The original is `eitheror-platform/services/psp-gateway` at `659c2d6b`
(the commit spec 004's recordings were made at); paths below are relative to its `internal/` unless they
start with `cmd/`, `tests/` or `starter-packs/`. ankka facts are from the `v0.11.0` tag, which this
repository pins (`services/*/build.sbt`); the docs the ankka MCP serves are 0.12's and were not relied on.

## R1. Language: Scala, not TypeScript

**Decision**: Scala 3.9 on ankka 0.11.0, like every other service here. Spec 005's 2026-10-08
clarification (TypeScript) is amended (spec correction 1).

**Rationale**: the 0.11 TypeScript SDK cannot shape a response. A success is always `200` with the
reply codec's content type (or `204`), with no headers; any other status is `HttpProblem`, always
`text/plain` (`sdks/typescript/src/server/http.ts`, `encodeResult` and `errorReply`); the content
types are JSON, text and octet-stream (`src/codec.ts:11`). The gateway needs all three things that
rules out:

- **HTML pages.** DreamzPay's and EuPaymentz's relays are auto-submitting forms (`hpp.go:846`, `:1046`;
  `dreamzpay/relay.html`), and every return route serves a page (`hpp.go:919-927`).
- **JSON refusals.** `merchant` reads `400 {"error":"player_error","message"}` from the initiate
  (`specs/004-merchant/contracts/gateway.md`; `GatewayClient.scala`, `HttpGateway.initiate`).
- **Status codes as answers to providers.** A `400` on a bad DreamzPay checksum (`webhook.go:218`),
  and BounceMoney's and Digitarium's `400`/`500`/`502` answers, which decide whether the provider
  redelivers. Payouts also answer `409` and `502` (`payout.go:296-302`).

TypeScript also cannot use the Scala contract (`contract.access.Routes`, `contract.money.Amounts`,
`Layout`'s grants), so it would carry a hand copy of each. Its integration kit has no settable clock and
no broker.

The reason recorded for TypeScript was that the service "mostly speaks outside HTTP". That holds just
as well with the JDK's `HttpClient`, and constitution VI's default is Scala. The user chose Scala on
2026-10-18 when these gaps were put to them.

**Alternatives**: TypeScript with the contract bent (refusals as `200`, a hand-cast `text/html` codec,
which is unconfirmed); TypeScript after an ankka spec for response control, which would block this build.

## R2. The original's surface, and what the port serves

`cmd/psp-gateway/main.go:146-220` registers 40 routes (three are ConnecX's). The port keeps every
provider-facing and browser-facing path verbatim, because providers and their dashboards are configured
with them. It replaces the platform-facing ones:

| Original | Port | Why |
|---|---|---|
| `POST /v1/psp/hpp/initiate`, unauthenticated | the same path, `merchant`'s identity only | spec 004 `contracts/gateway.md`; FR-021 |
| none | `GET /v1/psp/sessions/{reference}` | 004 FR-020 `expire`; FR-009 |
| `POST /v1/psp/payout/{unityfinance,cozeni,morphosis,gigadat}`, unauthenticated, called by casino-service | `POST /v1/payments/withdrawals`, a brand's `payouts` | FR-004; the grant `Layout.brandGrantsReceived` names |
| `GET /v1/psp/payout/unityfinance/{paymentNo}/status` | `GET /v1/payments/withdrawals/{requestId}` | FR-009 |
| none (casino-service read terminal twins) | `GET /v1/payments/withdrawal-terminals` | FR-009a |
| `POST /internal/v1/psp/redrive-undelivered` (X-Internal-Secret, CronJob) | the `Redrive` timer; `POST /v1/psp/outcomes/{reference}/redrive` for an operator | FR-016 |
| none | `GET /v1/psp/outcomes/undelivered`, `GET /status` | FR-016, FR-023 |
| `/v1/psp/{authorize,capture,void,refund}`, `/v1/psp/hpp/callback`, `/v1/psp/terminals/{id}`, `/psp-simulator/*`, `/health` | dropped | *Changes*; health is the runtime's readiness |

[contracts/routes.md](contracts/routes.md) lists every route with its ACL.

**As built (implementation)**:

- `GET /status` admits the project's services by identity and a payments operator through the ingress:
  `Routes.projectOrPaymentsOperator(project)`. The project is named (`Settings.project`, `payments`)
  because an authenticating ACL is not told the service's own; the integration kit runs as `local`.
- A relay page for an unknown session, or for another provider's, answers the processing page. The
  original answers `404 session not found`; a player's browser is shown the neutral page, as every
  return route shows it.
- A return route logs the names of the query and form fields it was sent, not their values: a return
  can carry the player's details (FR-022), and the outcome is the webhook's.

## R3. Sessions: two event-sourced entities, claimed before the provider is called

**Decision**:

- **`DepositSession`** is keyed by `merchant`'s payment id (a UUID, `cashier/Commands.scala:108`).
- **`PayoutSession`** is keyed by the withdrawal request id.
- Each session is **claimed** (`Claimed` persisted) *before* the provider is called. It records what the
  gateway itself generates: Cozeni's `requestSalt`, Morphosis's notification token, the return and
  webhook addresses. The provider's answer is then recorded as `Initiated` or `InitiationFailed`.
- A repeat initiation under the same key finds the claim. It answers the recorded `Initiated`, the
  recorded refusal, or `409 initiation_in_progress` while the first call is still out, and calls no
  provider (FR-008).
- The payment id is the reference sent to the provider wherever the original sent its fresh uuid:
  - DreamzPay `merchantTransactionId`, Unity Finance `ik_pm_no`, Cozeni `paymentRef`, EuPaymentz
    `merchant_payment_id`, Gigadat `transactionId`, BounceMoney `referenceId` and `Idempotency-Key`,
    Digitarium `referenceId`, Morphosis `trace_id`;
  - T365 `orderId`, dashes stripped as the original does (`t365/adapter.go:73-74`);
  - a payout's request id wherever the provider takes one: Unity Finance `paymentNo`, Cozeni
    `paymentRef`, Gigadat `transactionId`.

**Rationale**:

- **No idempotency in the original.** Every initiate mints a uuid (`hpp.go`), so BounceMoney's
  `Idempotency-Key` and Cozeni's `dupCheck` carry a new value on a retry, and a retried initiate opens
  a second provider session. Keying by the payment id makes both provider-side guards work as intended.
- **No wait-for-session.** Claiming first means a webhook can never arrive before the session exists.
  The spec's edge case asked for a wait the original does not have (spec correction 5).
- **FR-024: no session a webhook could settle.** An `InitiationFailed` session refuses a settlement
  (acknowledged, logged, nothing moves).

**Alternatives**:

- **A key value session:** every webhook, delivery and re-drive is an audit question ("when did the
  provider say so, and when did the cashier hear"), and the original's twin kept that as properties.
- **Recording after the provider answers (the original's order):** this is what needs a wait, or loses
  the webhook.

## R4. Finding a session from a webhook

Most providers echo our reference, which is the session's key. Three don't, so a key value
**`ReferenceIndex`** (id `<provider>/<ref>`, holding the session's kind and key) is written at claim
time for them:

- **T365's dashless `orderId`:** the payment id is recoverable by re-inserting the dashes, but the index
  avoids parsing a provider's string into a key.
- **Morphosis's notification token:** the webhook route carries only the token (`morphosis.go:76`).
- **Morphosis payouts:** they carry no request id at all (`morphosis/adapter.go:305-315`).

Unity Finance's withdrawal webhook carries `paymentNo` (the request id). The original also put
`merchantId`, `brandId` and `terminalId` in the query string (`payout.go:267`); the port reads none of
them, because the session has them (FR-017).

## R5. Outcome delivery: a consumer, the original's budget, then undelivered

**Decision**:

- The **`OutcomeDeliverer`** consumer of both session kinds' events reacts to `Settled` and `Redriven`.
- It calls the session's receiver as this service:
  - a deposit: `merchant`'s `POST /v1/payments/{paymentId}/capture` or `/fail` within `payments`;
  - a payout: the brand's `POST /v1/casino/brands/{brand}/withdrawals/{requestId}/outcome`, through
    `services(brand, "payouts")`, which the brand's ACL admits by the contract's grant list
    (`Routes.service`, `Layout.BrandGrantsMade`, until ankka 040).
- Retries happen inside the handler, with the original's figures (`webhook.go:564-582`):
  - 4 attempts, 250 ms doubling, within 15 s, each call bounded at 10 s;
  - retried: no answer, `429`, `5xx` (including `merchant`'s `503 OUTCOME_PENDING`);
  - not retried: any other `4xx`.
- The handler then records the result on the session:
  - `Delivered`, for a `2xx`;
  - `Refused` with the answer, for a `4xx`;
  - `Undelivered` with the last answer, when the budget is spent.
- It returns `done` in every case. It does not throw.
- A `Refused` outcome is never re-driven automatically, since the answer will not change. An operator
  may re-drive it (a revoked grant restored, a `webhookSecret` corrected), which the original could not:
  a `4xx` there set no marker and the outcome was gone.

**Rationale**:

- **Throwing would stall the consumer.** A consumer that throws does not advance and blocks its slice
  until the code or data changes (ankka 0.11 `docs/build/consumers.md:60-62`), and a receiver that is
  down for an hour would block every other session's delivery in that slice behind it.
- **Bounded attempts keep a handler short.** Recording the outcome as `Undelivered` is the original's
  marker (`webhook.go:654-675`), now an event.
- **A crash cannot lose an outcome.** A crash between the call and the record redelivers the event, and
  calls the receiver again. Both receivers take a repeat as a repeat:
  - `merchant`'s capture acknowledges a terminal payment (004 `gateway.md`);
  - `payouts` accepts the same outcome twice (007 FR-020).
- **At most once (FR-016).** The gateway never sends after `Delivered` or `Refused`, and a repeat after
  a crash is absorbed by the receiver's own idempotency.

**Alternatives**:

- **Throwing for redelivery (spec 004's `OutcomeReporter` does this):** acceptable there, where the
  wallet is in the same request path and is rarely down. Here a provider's capture must not wait behind
  another receiver.
- **A workflow per session:** a workflow's retries and pauses fit, but each webhook would then be a
  command on a workflow whose state duplicates the entity's, for one call.

**As built (implementation)**:

- **Verified (T027)**: ankka 0.11 bounds no consumer handler (`reference/configuration.md` lists no
  such setting; `build/consumers.md`: Scala consumers run on virtual threads, a blocking call costs only
  the wait), so the 15 s budget runs inside one handler, as decided. What it costs: a receiver that
  stays silent holds one of the consumer's slices (`parallelism`, 4 by default) for up to the budget
  plus a call's 10 s, and the sessions behind it in that slice wait that long.
- Two consumers, `deposit-deliverer` and `payout-deliverer`, one per session kind, since a consumer
  reads one entity's events. Both share `Deliver` (the schedule) and record through their own entity.
- A credential that cannot be read to sign the outcome records `Undelivered` with 0 attempts and
  `credential unreadable`, so the timer re-drives it; nothing is sent unsigned in its place.
- The listings are two plain views (`deposit-rows`, `payout-rows`) merged by `SessionsView`, not one
  keyed view over both (T056): each row is its own session's, and ankka 0.11 `views.md` says to use a
  plain view wherever one will do. `byProvider` was not needed: `/status` reads each provider's health
  from `ProviderHttp`'s window.
- A timed action's context carries no view client, so `PspGatewayService.started` hands the service's
  to `Redrive`; after a restart it is handed again.
- `RedriveSuite` runs the real timer every 3 s rather than moving a clock: the timer runtime runs on
  real time. The run over 500 undelivered (oldest first) is not exercised; `SessionsView` orders by
  `since` and the batch is `Redrive.Batch`.

## R6. Re-drive: a recurring timer over a view

**Decision**:

- **`UndeliveredView`** holds one row per session whose last delivery is `Undelivered`.
- The **`Redrive`** timed action is scheduled recurring every 2 minutes (ankka 032, released in 0.11,
  as `merchant`'s `OperatorSync` uses it). It reads up to 500 rows and calls `redrive` on each session.
  `redrive` persists `Redriven` only while the session is still undelivered; the consumer then delivers.
- An operator's `POST /v1/psp/outcomes/{reference}/redrive` does the same for one session at once.
- `REDRIVE_INTERVAL` (default `2m`) is a setting.

**Rationale**:

- **Bounded, unlike the original's sweep.** The original lists every checkout-session twin ever made,
  every two minutes (`substrate/client.go:318-367`; *Findings* 6). The view holds only what is
  outstanding.
- **Indefinite, as decided.** The re-drive continues until the outcome is delivered or refused (spec
  clarification 2026-10-18). A timer re-runs a failed run (3 s doubling to 30 s, indefinitely), and a
  skipped period is not caught up (ankka 0.11 `docs/build/timers.md:24-26`, `:228-246`), which suits a
  sweep.

**Alternatives**: a timer per undelivered session. That is many timers when a receiver is down; one
sweep is the original's shape.

## R7. Credentials: read from `merchant` on every use, never from the request

**Decision**:

- Before every provider call and every webhook verification, `MerchantClient` reads one of `merchant`'s
  internal routes (004 `contracts/routes.md`), as this service:
  - `GET /internal/v1/terminals/{terminalId}/properties` when the session has a terminal;
  - otherwise `GET /internal/v1/sub-brands/{subBrandId}/psp-credentials/{provider}`.
- The values live in the request's scope and are dropped with it: no cache, no field, no event.
- The provider's name is sent in the catalogue's upper-case form. The original sent `"unityfinance"`
  from its payout paths and `"UNITYFINANCE"` from its webhooks (*Findings* 9). `merchant`'s match is
  checked at task time.
- **`terminalProperties` in `merchant`'s initiate body is ignored.** Today `merchant` copies a
  terminal's secrets into that field (`DepositWorkflow.scala:167-173`, from the original's
  `hpp.go:102-108`), which FR-018 forbids. The gateway neither reads, logs nor keeps it. Spec 004 gets a
  follow-up to send `{}` (finding 1).

**Rationale**: FR-018 and SC-006 (a rotation applies to the next call, and each call is a read
`merchant`'s store records). BounceMoney's private key is parsed from the read value per request
(`bouncemoney/signature.go:82-137`), so it is never cached either.

**As built (implementation)**:

- `merchant` matches the provider case-insensitively (`SubBrand.usableCredential`), so the upper-case
  name is read wherever it is sent from.
- `RotationSuite` shows each provider's next call carrying a rotated value, a read per call, a webhook
  verified under the key read when it arrives, and BounceMoney's key parsed on each call (a malformed
  key refuses that initiate only, `502 provider_unavailable`, and no earlier key is used).
- `LeakSuite` finds no credential value, no customer field and no private key in any table, log line or
  answer. Unity Finance's `checkoutId.<METHOD>` is the exception by design: data-model.md keeps it as
  `providerData.checkoutId`, the `ik_co_id` the webhook must name; it is an identifier the provider
  echoes and the browser is sent, not a secret. Spans are not scanned: the integration kit exposes none.

## R8. Webhooks: raw body first, verify, cross-check, settle, answer as the provider expects

Each webhook route takes the body as a `String` (`postBody`, as `merchant`'s capture route does). It
verifies over those bytes before parsing anything else (FR-012). Then:

1. **Resolve** the session (R4). An unknown reference is acknowledged and nothing moves.
2. **Read credentials** (R7). If they cannot be read: `502` where the provider redelivers on a non-2xx
   (BounceMoney, Digitarium, as the original); otherwise acknowledge and log.
3. **Verify**, by the provider's scheme (R9).
4. **Cross-check** against the session (FR-013): the checkout or terminal id the provider names; the
   amount and currency where the provider reports them; the session not settled and not
   `InitiationFailed`. A mismatch is "does not verify". For seven providers this is new (*Changes*);
   for EuPaymentz it closes a replay of one session's signed success against another's reference
   (*Findings* 4).
5. **Map** the native status to an outcome (R9). An open status moves nothing.
6. **Settle**: persist `Settled` on the session, or nothing if it is already settled. Concurrent
   deliveries are serialised by the entity.
7. **Answer** the provider exactly as the original does (R9's *answer* column).

## R9. Per provider, as the original has it

The port keeps each line below; *new* marks what the port adds. The original's tests named in the
last column are ported as unit cases (R14).

| Provider | Initiate | Verify | Status → outcome | Answer to the provider | Original's tests |
|---|---|---|---|---|---|
| DreamzPay | no outbound call; relay page auto-POSTs `{base}/transaction/Checkout`; `amount` `%.2f`; `checksum` = MD5 hex of `memberId\|totype\|amount\|ref\|redirectUrl\|secureKey`; `terminalId.{METHOD}` overrides `terminalId` (`dreamzpay/adapter.go:22-105`) | MD5 hex of `paymentId\|ref\|amount\|status\|secureKey`, form or JSON body by content type with the other as fallback (`webhook.go:61-247`); the port compares in constant time | `transactionStatus` (fallback `status`) `Y` → captured; else failed "Payment declined" | `200 {"result":"accepted","paymentId"}`; unknown `200 {"result":"unknown_txn"}`; bad checksum **`400 {"error":"invalid_checksum"}`** | `signature_test.go`, `handler/failure_paths_test.go` |
| Unity Finance | form POST to `UNITYFINANCE_PAY_URL`; `ik_co_id` = `checkoutId.{METHOD}`, `ik_am` `%.2f`, `ik_int=json`; customer fields by method class (card, wallet, open banking); `ik_sign` = base64 SHA-256 of sorted-key values joined `:` + `:signKey`; redirect from `resultData.paymentForm` (`unityfinance/adapter.go:45-226`) | the same signature over the form minus `ik_sign`, constant time; `ik_co_id` equals the session's checkout id (`webhook.go:345-364`); the IP allowlist is logged only, as the original | `success` → captured; `canceled`/`expired` → failed (code `ik_inv_cancel_code` or the state; reason `ik_error_desc` or "Payment cancelled"/"Payment session expired"); `on_review`, `process`, `wait_accept`, `new`, `chargeback`, `blocked` open; `refunded`, `partial_refunded`, `chargebacked` logged (the original's "Phase 2") | always `200`, empty body | `adapter_test.go`, `signature_test.go` |
| T365 | JSON POST `{base}/api/v1/external/payment/hosted`, header `apikey`; `amount` `%.2f`; `orderId` dashless; `midId` an int when it parses; `paymentUrl` required (`t365/adapter.go:41-174`) | uppercase hex SHA-256 of `secretKey={key padded to 16}&amount=&currency=&id={orderId}&orderId=&status=` (`t365/signature.go:30-38`); `apikey` header checked only when present (`webhook.go:500-505`) | `SUCCESS` → captured; `REJECTED`/`FAIL` → failed "Payment declined"; `PENDING` open | always `200` | `signature_test.go`, `handler/session_secrets_test.go` |
| Cozeni | `GET {base}/pub/rate/{crypto}/{fiat}`; JSON POST `/mxapi/{merchantId}/payintxn`, `X-cozeni-txnauthz` = hex SHA-256 of `merchantId + salt + ref + apiKey`; `requestAmount` fiat ÷ rate; `dupCheck:true`; answers address, `cryptoAmount` to 8 places, deadline (`cozeni/adapter.go:114-247`) | `txnauthz` body field, same derivation with the session's salt, constant time, fails closed without the salt (`webhook.go:849-878`) | `COMPLETED` → captured; resultCode `02` captured with `OVERPAYMENT`; `03` captured with `UNDERPAYMENT` and the received values when `convertAmount` > 0, else failed code `03` "Less crypto arrived…"; `FAILED`/`CANCELLED` → failed (`resultMsg`) (`webhook.go:1072-1125`) | always `200` | `adapter_test.go`, `handler/callback_auth_test.go` |
| EuPaymentz | JSON POST `{base}/api/v2/processTx`, credentials in the body; `account_sha` = hex SHA-256 of `passphrase + amount + accountId + email + ip`; amount `%d.%02d`; `PEND` + `UrlToRedirect` required; POST-method redirects through the relay page (`eupaymentz/adapter.go:116-248`) | hex SHA-256 of `passphrase + resp_trans_id + resp_trans_amount + resp_trans_status`, case-insensitive (`eupaymentz/signature.go:23-26`); the port compares in constant time | `00000` → captured; `PEND` open; else failed (`resp_trans_description_status`) | always `200` | `signature_test.go`, `adapter_test.go`, `handler/webhook_eupaymentz_test.go` |
| Gigadat | CAD only; JSON POST `{base}/api/payment-token/{campaignId}`, Basic `accessToken:securityToken`; `amount` dollar float; `sandbox:true` only for a sandbox credential; mobile digits only; redirect `{base}/webflow?transaction=&token=` (`gigadat/adapter.go:115-190`) | **none** in the original (OQ-8: the cookie is logged, `handler/gigadat.go:83-86`); the port admits on the reference and the cross-check (R8.4, *new*) | `STATUS_SUCCESS` → captured/completed; `ABORTED1`, `EXPIRED`, `REJECTED`, `REJECTED1`, `FAILED` → failed with `rcode`; `CPI` deposits, `ETO`/`ACH` payouts (`gigadat/webhook.go:97-128`) | always `200` | `adapter_test.go`, `webhook_test.go`, `handler/gigadat_test.go` |
| BounceMoney | minimum 1,500 minor units ("minimum deposit amount is 15.00", a player error); JSON POST `{base}/checkout/sessions`, Ed25519 headers `X-BounceMoney-Key-Id`, `-Timestamp`, `-Signature: v1=` base64url of `ts.body`; `Idempotency-Key`; `201` with `data.url` (`bouncemoney/adapter.go:88-204`) | `X-BounceMoney-Signature: t=…,v1=…`, HMAC-SHA256 of `t.body` under `signingSecret`, ±300 s (`bouncemoney/signature.go:43-63`) | `checkout.session.completed` → captured; `.failed`/`.expired` → failed (code the type) | `400` malformed or bad signature; `500` no signing secret; `502` merchant or store unreadable; `200` unknown or other types (`webhook.go:1414-1563`) | `adapter_test.go` (20), `signature_test.go` (13), `handler/webhook_bouncemoney_test.go` (13) |
| Digitarium | two-decimal currencies only; JSON POST `{base}/api/v1/payments`, Bearer `apiKey`; `paymentType=DEPOSIT`, `paymentMethod=BASIC_CARD`, `amount` float; `result.redirectUrl` (`digitarium/adapter.go:111-213`) | header `Signature` = HMAC-SHA256 of the raw body under `signingKey`, hex or any base64 (`digitarium/signature.go:21-52`); body flat or `{result:{…}}` | `COMPLETED` → captured; `DECLINED`/`CANCELLED` → failed (`errorCode`, `errorMessage`); else open | as BounceMoney's (`handler/digitarium.go:39-158`) | `adapter_test.go`, `handler/webhook_digitarium_test.go` (16) |
| Morphosis | form POST `{base}?action=insert_deposit` with `user`, `pass`, `account`; `amount` `%.2f`; `method` by QVEI's names (`morphosis/adapter.go:64-123`); `notification_url` carries a fresh token; `result=ok`, id and address required | the token (a 36-character UUID, `morphosis/sessions.go:27-30`); `id` equals the session's QVEI id | `ac` → captured unless `Underpaid(expected, received)` (exact rational; unreadable counts as short) → failed `underpaid`; `de` → failed (`message`); `pe` open | always `200 OK`, plain text (`morphosis.go:67-70`) | `adapter_test.go`, `handler/webhook_morphosis_test.go` (24) |

Payouts, as the original has them (`handler/payout.go`, `handler/gigadat.go:168-296`):

| Provider | Call | Reference | Answer read | Status read | Webhook |
|---|---|---|---|---|---|
| Unity Finance | form POST `{UNITYFINANCE_API_URL}/withdraw`, Basic `userId:withdrawalApiKey`, `Ik-Api-Account-Id`; `method=eubanking`, IBAN and names | `paymentNo` = request id | `401`/`403` → rejected; `status:ok` with an id → initiated; an error naming "duplicate" or "already exist" → already initiated (`409`); other errors → rejected; unreadable → in doubt | `GET {API}/withdraw?paymentNo=`: `data: []` → no record; state `8` completed, `9`/`20` failed, `11` reversed, else pending | `/v1/webhooks/unityfinance/withdrawal`, form, signed over the body only (`PostForm`); `state` by name: `success` completed, `canceled`/`limitExceeded` failed, `chargebacked` reversed |
| Cozeni | JSON POST `/mxapi/{merchantId}/payouttxn`, the same auth header | `paymentRef` = request id | resultCode `00` completed, `01` initiated, else rejected; transport or `422` in doubt | none | `/v1/webhooks/cozeni/withdrawal`, form, `txnauthz` with the payout's salt |
| Morphosis | form POST `?action=insert_payout`; USD only (`422` otherwise); the request id is **not** sent | the token, in the notification address | `result != ok` rejected; transport error in doubt; else initiated with QVEI's id | none | `/v1/webhooks/morphosis/withdrawal/{token}`, the same codes |
| Gigadat | token call (ETO with email and mobile; ACH with account, institution, transit), then two `webflow` GETs; CAD only, ETO/ACH only | `transactionId` = request id | any failure in doubt; else initiated | none | the deposit route, `type` `ETO`/`ACH` |

Amounts: the original takes the payout amount as a decimal string and converts it, truncating for
Cozeni (`payout.go:134`) and rounding for Morphosis (`:469`). The port takes minor units (FR-004) and
renders each provider's form from them (`contract.money.Amounts`). Neither conversion survives
(*Changes*).

## R10. What a provider's base URL is

**Decision**: each provider's base URL is an installation setting with no default (FR-020):

- `DREAMZPAY_BASE_URL`, `UNITYFINANCE_PAY_URL`, `UNITYFINANCE_API_URL`, `T365_BASE_URL`,
  `COZENI_BASE_URL`, `EUPAYMENTZ_BASE_URL`, `GIGADAT_BASE_URL`, `BOUNCEMONEY_BASE_URL`,
  `MORPHOSIS_BASE_URL`;
- `DIGITARIUM_SANDBOX_URL` and `DIGITARIUM_PRODUCTION_URL`, chosen by the credential's `environment`,
  as the original chooses its host (`digitarium/adapter.go:27-35`; spec correction 11);
- `PUBLIC_URL`, the base of every webhook and return address the gateway hands a provider (the
  original's `PSP_GATEWAY_BASE_URL`).

A missing setting refuses initiation with `502 provider_not_configured: <provider>`. Gigadat's
`environment` sets `sandbox:true` in the body; its host is one setting.

**Rationale**: the original defaults seven of these to production or sandbox hosts
(`config/config.go:79-215`), and `MORPHOSIS_BASE_URL` to `https://wpb-api.qvei.com/`. The recordings had
to override every one to stop requests leaving the machine (`tests/port/README.md`, merchant).

## R11. Outbound calls: the JDK client, bounded, per-provider health

**Decision**:

- `ProviderHttp` wraps `java.net.http.HttpClient`: 5 s to connect, `PROVIDER_TIMEOUT` (default 20 s)
  per request, no redirects followed.
- Each call's result (answered, timed out, refused) and its latency update a per-provider window in
  memory. The window is reported by `GET /status` as `up`, `degraded` (more than half of the last 20
  calls failed or timed out) or `down` (the last 5 failed), and as gauges `eitheror_psp_calls_failed`
  and `eitheror_psp_call_seconds`, each tagged with the provider.

**Rationale**: FR-023. The original has no timeout on any outbound call (`http.DefaultClient`, *Findings*
12), no metrics and no per-provider health (`health.go:6`). An in-memory window is per node; that is
enough for an operator to see a provider failing, and keeps a write off every call.

**Alternatives**: a key value entity per provider. That is a journal write on every payment, for a figure
an operator reads as "is it down".

## R12. The payout surface `payouts` (007) calls, and the grants it needs

**Decision**: three routes, all admitted for any brand project's `payouts` (`Acl.AllowIf` on
`Caller.Service(_, "payouts")`, as `merchant`'s erasure route admits `players`). The brand is the
caller's project; a body or query naming another brand is refused `403`.

- `GET /v1/payments/withdrawal-terminals`: FR-009a. It is read from a new `merchant` route,
  `GET /internal/v1/brands/{brand}/terminals` (finding 2). The gateway adds, per provider:
  - `hasStatus` (Unity Finance only);
  - `reviewWindow` (Cozeni 24 h; Gigadat and Morphosis 5 days; Unity Finance none; spec 007
    clarification);
  - `coins` from the terminal's `supportedCurrencies` property (verified at T049; see *As built*).
- `POST /v1/payments/withdrawals`: FR-004, keyed by the request id.
- `GET /v1/payments/withdrawals/{requestId}`: FR-009.

**As built (T049)**: the property is `supportedCurrencies`, not `supportedCoins`. No `supportedCoins`
exists anywhere in the original. Its casino-service reads the terminal's `supportedCurrencies`
(`substrate/casino-service/src/main/scala/io/eitheror/casino/WithdrawalTerminalEligibility.scala:177-189`):
inside the stringified `properties` blob first, else a flat key; comma-joined or an array; split,
trimmed, upper-cased, blanks and repeats dropped. Empty means unconstrained. The withdrawal-terminals
listing answers it as the array `supportedCurrencies`, omitted when empty
(`WithdrawalTerminalRoutes.scala:92,104-105`). The withdrawal POST refuses a coin outside it, `422
CRYPTO_CURRENCY_NOT_SUPPORTED`, for Cozeni and Morphosis terminals only (`WithdrawalRoutes.scala:385-418`).
The operator sets it as a required property of Cozeni's and Morphosis's `CRYPTO` schemes
(merchant-service `MerchantRoutes.scala:81,286`), as `merchant`'s catalogue does (`Catalogue.scala:34-35,67`),
and it is not a secret, so `merchant`'s `GET /internal/v1/brands/{brand}/terminals` carries it in
`properties{}`. The gateway's `coins` reads `supportedCurrencies` with the original's normalisation.

`Layout.brandGrantsReceived` today grants only the initiate. It gains the other two routes (finding 3,
a contract change in this spec), so the brands tool renders the three grants into every brand's
manifest.

**As built (implementation)**: the listing reads `supportedCurrencies` with the original's
normalisation (comma-joined, trimmed, upper-cased, without repeats); `contracts/payouts.md` names it.

## R13. The outcome to `merchant`: unsigned in the original, signed here when a secret exists

**Finding**: `merchant` verifies `X-PSP-Signature` and `X-PSP-Timestamp` when the provider's credential
has a `webhookSecret` (`CaptureRoutes.scala`, `security/Hmac.scala`; the original's
`CashierRoutes.scala:673-700`). But the original gateway never sends either header (`webhook.go:768-769`).
So in the original, a credential with a `webhookSecret` makes every capture `401 missing_token`, which
the gateway does not retry. 004's `gateway.md` says the gateway "passes the provider's signature
through". It cannot, since the providers sign their own payloads.

**Decision**: when the credential the gateway read has a `webhookSecret`, the gateway signs the capture
or fail body it sends (hex HMAC-SHA256 under it, with Unix seconds). This is the scheme `merchant`'s
`Hmac` checks. Without the secret it sends neither header.

**Rationale**: it makes 004's documented contract true, and makes a `webhookSecret` usable. It is new,
so it is declared (*Divergences*), and 004's `gateway.md` wording is corrected (finding 4).

**Alternative**: keep the original's unsigned call. It is identical for every credential without a
secret, but a credential with one would refuse every capture.

## R14. Testing

**Decision**: munit, at three levels, as `merchant`:

- **Pure:** each provider's signing, verification, status map and amount rendering, with the original's
  `_test.go` vectors ported case for case (R9's last column: about 170 cases). Also `Delivery.backoff`
  and `Underpaid`.
- **Kits:**
  - `EventSourcedTestKit` for both sessions;
  - `KeyValueEntityTestKit` for `ReferenceIndex`;
  - `ConsumerTestKit` for `OutcomeDeliverer`, with a scripted receiver: 2xx, 4xx, 5xx, silence and
    budget exhaustion.
- **Integration:** `AnkkaTestKit.start` over Postgres, with:
  - `FakeMerchant` (the internal credential routes, capture, fail, the terminal listing) and
    `FakePayouts` (the outcome route), both `FakeServer`s with settable answers;
  - `psp-stub` as a testcontainers container answering every provider (R15);
  - `TimerRuntime` and a settable clock for the re-drive.
- **Bound features:** `GherkinSuite` binds `features/payments/{providers,crypto,withdrawal-payments,outcomes}.feature`.
- **Parity:** `ParitySuite` replays `tests/port/psp-gateway/` (R16).

SC-002 (five deliveries, one capture), SC-003 (receiver down past the budget, delivered within one
re-drive of recovery) and SC-004 (grep the journal, the view tables, the logs and the responses for
every credential `psp-stub` was given) are suites of their own.

## R15. `psp-stub`: one Go programme over the nine stubs

**Decision**:

- **Packaging:** `tests/stubs/psp-stub/` is one `main` that serves all nine providers, each on its own
  port as today (BounceMoney 18101, Cozeni 18102, DreamzPay 18103, EuPaymentz 18104, Gigadat 18105,
  Morphosis 18106, T365 18107, Unity Finance 18108, Digitarium 18109). It is built into one image,
  `psp-stub`.
- **Two new providers:** `tests/stubs/unityfinance/` and `tests/stubs/digitarium/`.
  - Unity Finance carries the live shapes: `status:"ok"` with `data.withdraw.id`; the status read's
    array; the withdrawal webhook's `paymentNo`/`state` by name, confirmed 2026-08-06.
  - The original's `tests/uf-stub` speaks none of these (*Findings* 1), which is why it cannot be used.
- **Control:** `stubhttp`'s `/__control` grows from three outcomes to FR-025's set: `success`, `failure`
  (with `code`), `pending` then settle, `duplicate`, `no_record`, `reversed`, `underpayment`,
  `overpayment`, `unsigned` and `unauthorised` (`401` on empty credentials). `POST /__webhook` makes the
  stub send its provider's webhook for a reference now, so a suite does not wait on a delay.
- **Compose:** `compose.override.yml` runs one `psp-stub` service in place of the seven.

**Rationale**:

- **Go:** spec 001 built the seven stubs in Go, in the original's toolchain (`tests/README.md`), and they
  have their own tests. Rewriting them in Scala would re-derive shapes already checked against the
  original's adapters.
- **One port per provider:** the original reads one base URL per provider, and the existing READMEs and
  recordings name those ports.

## R16. Recordings: the Go suite gives one provider, a driver gives the rest

The original's e2e suite reaches the gateway in scenarios 13 and 14 (DreamzPay). Both post their own
signed webhook (`tests/e2e/scenario13_dreamzpay_e2e_test.go:145-184`). They are already recorded under
`tests/port/merchant/` and are replayed here for the gateway's steps. Every other provider, the payouts
and the outcome rules have no recording (spec *Recordings*).

**Decision**: `tests/original/gateway-driver/` is a Go programme, like `merchant-driver/`. It drives the
original's psp-gateway directly, with `psp-stub` as every provider. In place of merchant-service and
casino-service it runs a receiver that records each outcome call as an `observe:` step. It has one
scenario per scenario of the four features, per provider where the feature is per provider:

- **Deposits:** nine providers × (initiate, capture, fail, bad signature, unknown reference, repeat,
  open status), plus the crypto five for Cozeni and Morphosis.
- **Payouts:** four providers × (initiate, outcome by webhook), Unity Finance's status read
  (completed, no record), duplicate, and empty credential.
- **Outcomes:** the receiver answering `503`, `400`, and silent past the budget, then the original's
  re-drive route (`POST /internal/v1/psp/redrive-undelivered`, with the secret).

Each scenario is named exactly as the feature names it, with the provider as a suffix
(`…/cozeni`). It is recorded twice on fresh ids and merged by `tests/original/merge`, as spec 004's were.

**What a replay checks**: the gateway's answers to the initiate and to each webhook (status and body);
the outbound provider call (path, form or JSON fields, signature header), from `psp-stub`'s
`/__received`; and each outcome call's path and body as the receiver recorded it. Every place the port
differs is a declared divergence (plan.md *Divergences*), scoped to its steps.

**Known gaps in the original, each recorded as it behaves and then declared**:

- the T365 undelivered marker's write likely fails (*Findings* 6);
- an initiate repeated under one id opens a second provider session (R3);
- the DreamzPay return POST settles a payment (*Changes*);
- a Gigadat webhook is admitted with no check (R9).

## Spec corrections (applied to spec.md on this branch)

1. **Language**: Scala, not TypeScript (R1). The clarification, *Context*, the title (nine providers,
   not eight) and `CLAUDE.md`'s table are amended.
2. **Cozeni underpayment**: the original captures an underpayment Cozeni has valued (`convertAmount` >
   0) with an `UNDERPAYMENT` variance, and `merchant` credits the valuation, never the invoice
   (`Variance.credited`). Only an unvalued underpayment, and every Morphosis underpayment, fails.
   US2, `crypto.feature`'s below-the-quote scenario and SC-005 said every short payment fails; they
   now say a short payment is never credited in full.
3. **Gigadat's webhook is unauthenticated** in the original (OQ-8). FR-010's "security token" is wrong.
   The port admits it on the reference and the session cross-check (R8.4). Whether that suffices for
   real money joins Morphosis as an open product decision for Sheldon.
4. **Not every webhook is acknowledged.** DreamzPay answers a bad checksum `400`, and Digitarium answers
   as BounceMoney does (`400`, `500`, `502`). Decided 2026-10-19: every signed provider answers a webhook
   that does not verify `400` (`Reply.invalidSignature`), new for Unity Finance, T365, Cozeni and
   EuPaymentz; FR-011 says so.
5. **No wait for an unreadable session** in the original. The edge case now says the session is claimed
   before the provider is called (R3).
6. **Initiation idempotency and the payment id as the provider's reference are new** (R3); listed under
   *Changes*.
7. **The amount, currency and settled-session cross-check is new** for seven of the nine (R8.4); listed
   under *Changes*.
8. **The DreamzPay return POST settled payments** in the original, using a `cb=` address taken from the
   browser, to which it sent the internal secret (`hpp.go:942-1040`). The port drops it (US6, *Changes*).
9. **Return routes show the original's neutral "processing" page.** They never redirected to the
   cashier; the original's `redirectUrl` field is unread. US6 and its two scenarios now say "shown the
   processing page".
10. **New: timeouts and per-provider health** (FR-023). The original had neither; listed under *Changes*.
11. **Digitarium's `environment` selects its host**, as Gigadat's selects its sandbox (FR-020).
12. **Payout amounts arrive in minor units** (FR-004). The original's decimal conversions (truncating
    for Cozeni, rounding for Morphosis) are gone; listed under *Changes*.

## Findings for other specs

1. **004:**
   - `merchant` copies a terminal's secret properties into the initiate's `terminalProperties`
     (`DepositWorkflow.scala:167-173`). It should send `{}`; the gateway reads the routes (R7).
   - Check that the credential route matches the provider's name case-insensitively.
2. **004:** a route for the gateway to list a brand's terminals, `GET /internal/v1/brands/{brand}/terminals`
   (id, name, scheme, gateway, status, the three capabilities and the properties' non-secret values).
   Additive.
3. **001/contract:** `Layout.brandGrantsReceived` gains `GET /v1/payments/withdrawals/{requestId}` and
   `GET /v1/payments/withdrawal-terminals` for `{brand}/payouts`. Made in this spec.
4. **004:** `contracts/gateway.md` says the gateway "passes the provider's signature through". It
   signs with the credential's `webhookSecret` (R13). Its wording is corrected on this branch.
5. **007:** the gateway's payout answers are `initiated`, `completed`, `rejected`, `in_doubt` and
   `already_initiated`, the original's words ([contracts/payouts.md](contracts/payouts.md)). 007 FR-018
   reads all but `already_initiated`, which it should read as *initiated* (the provider already holds
   the request).
6. **003:** unchanged. The four free-spins recordings still need re-merging.
7. **004:** `merchant`'s deadline reads the session status as `undelivered: true` → wait, anything else
   → expire. It must read `refused: true` the same way as `undelivered`: a refused capture is one an
   operator will re-drive, and expiring it would lose a payment the provider captured
   ([contracts/merchant.md](contracts/merchant.md) §2).
8. **002 (found by T072, 2026-10-21, in a cluster):** the wallet's `ResponsibleGambling.http` makes the
   `may-deposit` call inside `CompletableFuture.supplyAsync(…).get(500 ms)`. A workflow's service client
   answers only on the step's own thread (ankka's `StepScope`: "a workflow calls another service in a
   step, not in a command handler"), so the call is refused before anything is sent, every deposit ends
   `RG_UNAVAILABLE`, and `responsible-gambling` is never asked. The call must be made on the step's
   thread; the 500 ms bound is the service client's timeout (`ankka.service-client.timeout`, the wallet's
   only outbound call), not a thread hop. Locally the integration suites give the workflow a fake, which
   is why nothing failed before. *Fixed in the wallet the same day: the call runs on the step's thread,
   the wallet's `application.conf` sets the client's timeout to 500 ms, and `ResponsibleGamblingItSuite`
   runs the real client against the test kit's scripted service.*
9. **002 and 004 (the same run):** the wallet's deposit flow ends `refused` on `RG_UNAVAILABLE` and
   answers that outcome to every later `start` of the same payment, while `merchant` treats
   `RG_UNAVAILABLE` as transient and retries with backoff without bound (004 FR-022). One of the two
   must change: either the wallet re-runs the check when a refused flow's code is `RG_UNAVAILABLE` (the
   check is transient by its own definition), or merchant counts it final and parks the capture for an
   operator. The first is the one spec 002's fail-closed rule implies. *Done the same day: a `start`
   of a flow refused as `RG_UNAVAILABLE` runs the check again (002 FR-022, clarification 2026-10-21);
   merchant's retry is unchanged.*
10. **004:** `KEYCLOAK_URL` in `shared/payments/manifest.json` is the installation's public name with
    `:8443`, which inside the cluster resolves to the gateway on 443 only, so `operator-sync` times out on
    every tick in a cluster. It should come from the installation (`setting:`), as `PUBLIC_URL` now does,
    or be the in-cluster address that `ANKKA_AUTH_STAFF_JWKS_URL` already uses.

## Open for Sheldon

Decided 2026-10-19 (spec *Clarifications*): 1 and 2 are enough for now; 3 is `already_initiated`, and the
scenario says so; 4 is `400` for every signed provider. With them, a provider's `401`/`403` to a payout
is `rejected` / `unauthorised` for all four payout providers (`PayoutAnswer.unauthorised`), not Unity
Finance alone; for Gigadat only on the token call, since after it Gigadat holds the payout. The message
as it was sent:

> Four things in the payment gateway need a product call before it takes real money.
>
> 1. **Gigadat's webhook has no signature.** The original admits it unchecked. The port admits it only
>    for a session it holds, with the amount and currency matching. Is that enough for real money, or do
>    we ask Gigadat for a signed callback, or read the status back from Gigadat before settling?
> 2. **Morphosis's webhook is authenticated by its address only**: a one-time token in the notification
>    URL, which the port maps back to the session. Same question: enough, or a read-back first?
> 3. **A duplicate withdrawal.** `withdrawal-payments.feature` says a duplicate reference is reported to
>    the brand as refused. The original answers `already_initiated`, and the port does too, since the
>    provider already holds the request; spec 007 would read it as initiated. Which is right? The
>    scenario is unbound until this is settled.
> 4. **A webhook whose signature does not verify.** `providers.feature` says it is acknowledged to the
>    provider. Most providers are answered `200` either way, but DreamzPay is answered `400` on a bad
>    checksum, as the original does, so DreamzPay redelivers. Keep the original's `400` (and reword the
>    scenario), or acknowledge everything?

## Findings in the original (not ported)

1. `tests/uf-stub` speaks superseded Unity Finance shapes on all three calls.
2. The gateway never signs the capture it sends `merchant-service` (R13).
3. The Gigadat webhook is unauthenticated, and its payout brand lived only in memory.
4. EuPaymentz's lookup field is outside its signature, and its comparison is not constant time.
5. DreamzPay's return POST settles payments and can send the internal secret to a browser-supplied address.
6. The T365 undelivered marker is written to a key that is not a UUID. Morphosis's is written beside its
   session, not on it. The re-drive lists every session ever made.
7. No outbound call has a timeout.
8. Unity Finance's payout 401 is decoded as JSON before its status is read, so a non-JSON 401 is in doubt.
9. Provider-name case differs between the payout and webhook credential reads.
