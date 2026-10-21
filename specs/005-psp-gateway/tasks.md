# Tasks: psp-gateway

**Input**: Design documents from `specs/005-psp-gateway/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{routes,merchant,payouts,recording}.md, quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test
(I) and the living features the scenarios (VII); the port method makes parity tests a deliverable,
written before the component it holds to account and failing first (ankka-port §7). Every story
opens with its tests: the original's unit cases ported, the entity and consumer kits, the feature
bindings, the SC suites, the parity tests over the recordings.

**Organization**: By user story, in spec.md's order. US1 to US4 are P1 and together are the money
path; US1 alone is the MVP (a DreamzPay deposit initiated as `merchant`, a webhook from `psp-stub`,
a capture delivered once to `FakeMerchant`). The pure provider packages, the sessions, the index,
the seams and the kit are foundational: every story needs a claimed session, a credential read from
`merchant` and a provider that answers before anything can be tested. The deliverer is US1's
(a capture must reach `merchant`); what happens when it cannot is US4's.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US6 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `g/` stands for `services/psp-gateway/src/main/scala/io/eitheror/casino/pspgateway/`
- `g-test/` stands for `services/psp-gateway/src/test/scala/io/eitheror/casino/pspgateway/`
- `contract/` is the shared library `io.eitheror:casino-contract`; `tests/port/psp-gateway/` the
  recordings; `tests/original/` the recording toolchain; `tests/stubs/` the provider stubs;
  `features/payments/` the living features
- The service's sbt commands run in `services/psp-gateway/`; Go commands in `tests/`; everything
  else at the root
- `the original` is `~/Documents/projects/eitheror/eitheror-platform` at `659c2d6b`, its gateway at
  `services/psp-gateway/`; a bare `hpp.go`, `webhook.go`, `payout.go` is under its
  `internal/handler/`, and `<provider>/adapter.go` under `internal/<provider>/`
- `m/` stands for `services/merchant/src/main/scala/io/eitheror/casino/merchant/` (the 004
  follow-ups only)

---

## Phase 1: Setup

**Purpose**: the service's build, the contract's two grants, the recipes, and `psp-stub` with the
recording driver (which need only Go and the original's checkout, not this service).

- [X] T001 Create `services/psp-gateway/` on `services/merchant/`'s template (as 004's T001 did):
      `build.sbt` on Scala 3.9 with ankka 0.11.0 (`ankka-sdk`, `ankka-runtime`, `ankka-http`,
      `ankka-auth-oidc`, `ankka-telemetry-otlp`, `ankka-testkit`), `io.eitheror:casino-contract`
      from the local publish, munit, testcontainers (for `psp-stub`), the Postgres driver as a test
      dependency (for `LeakSuite`); `project/plugins.sbt`; `service.json` for the local run;
      `docker-compose.yml` and `sbt schema` as merchant has; `src/main/resources/application.conf`
      and `logback.xml` (no body logging: FR-022); the package directories plan.md lists under `g/`
      and `g-test/`; `services/psp-gateway/README.md` naming the service's purpose in two lines
      (filled by T094)
- [X] T002 [P] Extend `Layout.brandGrantsReceived` in
      `contract/src/main/scala/io/eitheror/casino/contract/Layout.scala` with
      `GET /v1/payments/withdrawals/{requestId}` and `GET /v1/payments/withdrawal-terminals` for
      `service:{slug}/payouts` on `psp-gateway` (research R12, finding 3); extend
      `contract/src/test/scala/io/eitheror/casino/contract/access/RoutesSuite.scala` (which holds
      the grants' admission) so a brand receives three gateway grants and `Routes.service` admits
      `payouts` on all three, and
      `tools/brands/src/test/scala/io/eitheror/casino/brands/RenderSuite.scala` so a rendered brand
      manifest and `shared/payments/manifest.json`'s `grants.made` carry all three; update
      `shared/payments/manifest.json`'s `grants.made` and every `brands/<slug>/manifest.json` the
      tool renders grants into; `just brands validate shared/payments/manifest.json` and `just test`
      green; `sbt contract/publishLocal`
- [X] T003 [P] Add to `justfile` the recipes `psp-gateway-test` (`sbt contract/publishLocal` then
      `sbt test` in `services/psp-gateway`), `psp-gateway-it` (`testOnly
      io.eitheror.casino.pspgateway.it.* io.eitheror.casino.pspgateway.port.*`), `psp-gateway-run`
      (`PUBLIC_URL=http://localhost:9001` and every base URL at `psp-stub`'s ports, `sbt run`),
      `psp-stub` (`go build -o stubs/bin/ ./stubs/psp-stub` in `tests/` then run it); add a
      `psp-gateway` job to `.github/workflows/ci.yml` after the `merchant` job's pattern (publish the
      contract, `sbt test` in `services/psp-gateway/`); the `check` job's `go test ./...` under
      `tests/` already covers the stubs and the driver
- [X] T004 [P] Write `tests/stubs/unityfinance/` (`main.go`, `provider.go`, `provider_test.go`,
      `README.md`, port 18108) on the seven stubs' pattern over `stubhttp`: the invoice POST
      (`ik_*` form, `ik_sign` verified with the stub's `signKey`, answering `resultData.paymentForm`
      JSON as the original reads it, `unityfinance/adapter.go:45-226`); `POST /v1/withdraw` with
      Basic auth and `Ik-Api-Account-Id` answering the live `{"status":"ok","data":{"withdraw":{"id"}}}`,
      `401` on empty Basic auth, a "duplicate" error on a repeated `paymentNo`; `GET /v1/withdraw?paymentNo=`
      answering the status array (`data: []` for no record, a state of `8`, `9`, `20`, `11`, `1` as
      controlled); the deposit webhook (form, `ik_sign` over the sorted `ik_` values, `ik_inv_st`
      `success`/`canceled`/`process`, `ik_co_id` the invoice's) and the withdrawal webhook (form,
      `paymentNo` and `state` by name: `success`, `canceled`, `limitExceeded`, `chargebacked`,
      confirmed live 2026-08-06) sent to `UNITYFINANCE_STUB_WEBHOOK_URL`; tests for every shape.
      Then `tests/stubs/digitarium/` (port 18109): `POST /api/v1/payments` with Bearer `apiKey`
      (`401` on a bad or empty key, `result.redirectUrl` and `result.id`), the webhook (`Signature`
      header HMAC-SHA256 of the raw body under `signingKey`, hex by default and base64 when
      controlled, flat or `{result:{…}}` body, `status` `COMPLETED`/`DECLINED`/`CANCELLED`/`PENDING`
      with `errorCode` and `errorMessage`, `referenceId` echoed) to `DIGITARIUM_STUB_WEBHOOK_URL`;
      tests. `go test ./stubs/...` green (research R15)
- [X] T005 [P] Extend `tests/stubs/internal/stubhttp/stubhttp.go` and its test: `/__control`'s
      outcomes grow from `success|failure|pending` to FR-025's set, `success`, `failure` (with
      `code`), `pending` (then `settle` on the next control), `duplicate`, `no_record`, `reversed`,
      `underpayment`, `overpayment`, `unsigned`, `unauthorised`, each with the fields it needs
      (`code`, `receivedAmount`, `convertAmount`, `signatureEncoding`); `POST /__webhook
      {"reference"}` makes the stub send its provider's webhook for that reference now, to the
      stub's configured webhook address, and answers the gateway's status and body; `GET /__received`
      unchanged. Apply the new outcomes in each of the seven existing providers' `provider.go` where
      the provider has the behaviour (Cozeni `02`/`03` with `convertAmount`; Morphosis `ac` with a
      short `amount`; Unity Finance and Cozeni `duplicate`; BounceMoney and Digitarium `unsigned`;
      Unity Finance `no_record` and `reversed`), each with a test; a provider without the behaviour
      answers `400` to that control
- [X] T006 Write `tests/stubs/psp-stub/main.go`: one programme starting the nine providers, each on
      its port (BounceMoney 18101, Cozeni 18102, DreamzPay 18103, EuPaymentz 18104, Gigadat 18105,
      Morphosis 18106, T365 18107, Unity Finance 18108, Digitarium 18109), each webhook address from
      `<PROVIDER>_STUB_WEBHOOK_URL` with a default of `PSP_STUB_GATEWAY_URL` + the provider's
      webhook path (contracts/routes.md); `tests/stubs/psp-stub/Dockerfile` (multi-stage, `golang:1.22`
      → distroless) building the image `psp-stub`; `tests/stubs/psp-stub/README.md` (ports, controls,
      the image); replace the seven stub services in `tests/original/compose.override.yml` with one
      `psp-stub` service and point the original's gateway's eleven base URLs at it
      (`UNITYFINANCE_API_URL` at `http://psp-stub:18108/v1`; `DIGITARIUM_*_URL` both at 18109);
      `tests/README.md` updated; `go vet ./... && go test ./...` green (quickstart §1)
- [X] T007 [P] Write `tests/original/gateway-driver/` (Go, as `merchant-driver/`: `driver.go`,
      `api.go`, `receiver.go`, `scenarios.go`, `driver_test.go` offline): a receiver standing in
      for merchant-service and casino-service that records each outcome call as an `observe:` step
      (path, body, the headers minus any secret) and answers as scripted (`200`, `503`, `400`,
      silent); one scenario per scenario of the four features, per provider where the feature is
      per provider (research R16): deposits for nine providers × initiate (`terminalProperties`
      carrying the stub's credentials, as the original requires), capture, fail, bad signature,
      unknown reference, repeat initiate, open status, tampered amount, plus the crypto five for
      Cozeni and Morphosis and the DreamzPay return POST; payouts for four providers × initiate and
      outcome by webhook, Unity Finance's status read (completed, no record), duplicate, empty
      credential; outcomes with the receiver answering `503`, `400`, silent past the budget, then
      `POST /internal/v1/psp/redrive-undelivered` with the secret. Each scenario named as the feature
      names it with the provider as a suffix; `psp-stub`'s `/__control`, `/__webhook` and `/__received`
      as `setup:` and `observe:` steps; `vary` on the payment id, the request id, the stub's ids,
      every timestamp, the Cozeni salt, the Morphosis token and every signature over them
      (contracts/recording.md). Extend `tests/original/preflight` to check `psp-stub`'s nine
      `/healthz` and the receiver. `go test ./original/gateway-driver/...` green offline

---

## Phase 2: Foundational

**Purpose**: what every story needs: the pure provider packages with the original's cases, the
sessions and the index with their kits, the two seams and their fakes, the test kit, the
recordings, the parity and feature suites listing their tests, and the endpoint shells.

**⚠️ CRITICAL**: no story starts before this phase is done. T026 (the recording run) gates every
parity task and can run the moment T006, T007 and the original's stack are up.

### Pure packages (the original's cases ported, each test named after the original's case)

- [X] T008 [P] Implement `g/money/Render.scala` over `contract.money.Amounts`: `twoDecimals(minor,
      currency)` (`%.2f` string: DreamzPay, Unity Finance, T365, Morphosis), `decimalNumber` (a JSON
      number: Digitarium, BounceMoney, Cozeni fiat), `dollars` (Gigadat's float), `dotted` (`%d.%02d`,
      EuPaymentz), `coin(amount: BigDecimal)` to 8 places (Cozeni); a currency whose exponent is not
      2 refused for Digitarium naming the currency (FR-006, R9); `g/crypto/Underpaid.scala`
      (`Underpaid(expected: String, received: Option[String]): Boolean` by exact rational comparison,
      a missing or unreadable received amount is short, as `morphosis.go`); tests
      `g-test/money/RenderSuite.scala` (each provider's form from the same minor units; `1500 EUR`
      is `15.00`, `1500.00` is refused as not whole) and `g-test/crypto/UnderpaidSuite.scala` with
      the original's `webhook_morphosis_test.go` amount cases
- [X] T009 [P] Implement `g/providers/Catalogue.scala`: per `Provider` the operations offered
      (deposit; payout for Unity Finance, Cozeni, Morphosis, Gigadat), the methods per currency
      (Gigadat CAD only; Morphosis payouts USD only; T365's 16 codes; Unity Finance's ten; DreamzPay
      and Unity Finance `{METHOD}`-suffixed credential keys), the minimum (BounceMoney 1,500 minor
      units, "minimum deposit amount is 15.00"), `hasStatus` (Unity Finance), `reviewWindow` (Cozeni
      24 h; Gigadat and Morphosis 5 days; Unity Finance none), the credential names per provider
      (spec table), the base URL setting names (R10), `webhookPath` and `returnPath`; `unsupported(provider,
      method, currency)` naming both; tests `g-test/providers/CatalogueSuite.scala`: every provider
      lists; Interac outside CAD is `unsupported_currency`; a Digitarium three-decimal currency is
      `unsupported_currency`; a method a provider lacks is `unsupported_method`
- [X] T010 [P] Implement `g/providers/dreamzpay/DreamzPay.scala`: `checkoutForm(session, credential,
      publicUrl)` (the relay form's fields, `amount` `%.2f`, `checksum` = MD5 hex of
      `memberId|totype|amount|ref|redirectUrl|secureKey`, `terminalId.{METHOD}` over `terminalId`,
      `dreamzpay/adapter.go:22-105`), `verify(rawBody, contentType, credential)` (form or JSON by
      content type with the other as fallback, MD5 of `paymentId|ref|amount|status|secureKey`
      compared with `MessageDigest.isEqual`), `outcome(fields)` (`transactionStatus` falling back to
      `status`, `Y` → captured, else failed "Payment declined"), `answer` (`200 {"result":"accepted",
      "paymentId"}`, `200 {"result":"unknown_txn"}`, `400 {"error":"invalid_checksum"}`); tests
      `g-test/providers/DreamzPaySuite.scala` with `dreamzpay/signature_test.go`'s vectors and
      `handler/failure_paths_test.go`'s cases
- [X] T011 [P] Implement `g/providers/unityfinance/UnityFinance.scala`: `invoice(session, credential,
      customer, publicUrl)` (the `ik_*` form by method class: card with full identity, wallet, open
      banking; `ik_co_id` = `checkoutId.{METHOD}`, `ik_am` `%.2f`, `ik_pm_no` the payment id,
      `ik_int=json`; `ik_sign` = base64 SHA-256 of the sorted-key values joined `:` plus `:signKey`,
      arrays sorted; `unityfinance/adapter.go:45-226`), `redirectFrom(json)` (`resultData.paymentForm`),
      `verify(form, signKey)` (the same signature minus `ik_sign`, constant time; the IP allowlist
      logged only), `outcome(form)` (`success` captured; `canceled`/`expired` failed with
      `ik_inv_cancel_code` or the state and `ik_error_desc` or the original's reasons; the open
      states; `refunded`, `partial_refunded`, `chargebacked` logged), `payout(request, credential)`
      (form `method=eubanking`, IBAN and names, `paymentNo` the request id, Basic `userId:withdrawalApiKey`,
      `Ik-Api-Account-Id`), `payoutAnswer(status, body)` (`401`/`403` rejected `unauthorised`;
      `status:ok` with an id initiated; "duplicate"/"already exist" already initiated; other errors
      rejected; unreadable in doubt, finding 8), `payoutStatus(json)` (`data: []` no record; `8`
      completed, `9`/`20` failed, `11` reversed, else pending), `withdrawalOutcome(form)` (`state`
      by name); tests `g-test/providers/UnityFinanceSuite.scala` with `unityfinance/adapter_test.go`,
      `signature_test.go`, `payout_test.go` and `sessions_test.go`'s cases
- [X] T012 [P] Implement `g/providers/t365/T365.scala`: `request(session, credential, customer,
      publicUrl)` (JSON for `/api/v1/external/payment/hosted`, header `apikey`, `amount` `%.2f`,
      `orderId` the payment id dashless, `midId` an int when it parses; `t365/adapter.go:41-174`),
      `redirectFrom(json)` (`paymentUrl` required), `verify(fields, secretKey, apikeyHeader)`
      (uppercase hex SHA-256 of `secretKey={key padded to 16}&amount=&currency=&id={orderId}&orderId=&status=`;
      the `apikey` header checked only when present), `outcome` (`SUCCESS` captured; `REJECTED`/`FAIL`
      failed "Payment declined"; `PENDING` open); tests `g-test/providers/T365Suite.scala` with
      `t365/signature_test.go` (the padded key) and `handler/session_secrets_test.go`'s cases
- [X] T013 [P] Implement `g/providers/cozeni/Cozeni.scala`: `rateRequest(crypto, fiat)`,
      `payinRequest(session, credential, salt, rate, publicUrl)` (`/mxapi/{merchantId}/payintxn`,
      `X-cozeni-txnauthz` = hex SHA-256 of `merchantId + salt + ref + apiKey`, `requestAmount` fiat ÷
      rate to 8 places, `dupCheck:true`, `paymentRef` the payment id; `cozeni/adapter.go:114-247`),
      `destinationFrom(json)` (address, `cryptoAmount`, deadline), `verify(form, credential, salt)`
      (`txnauthz` with the session's salt, constant time, fails closed without the salt),
      `outcome(form, session)` (`COMPLETED` captured; `02` captured with `OVERPAYMENT`; `03` captured
      with `UNDERPAYMENT` and the received values when `convertAmount` > 0, else failed `03` "Less
      crypto arrived…"; `FAILED`/`CANCELLED` failed with `resultMsg`; `webhook.go:1072-1125`),
      `payoutRequest` (`/payouttxn`, the same auth, `paymentRef` the request id, coin to 8 places),
      `payoutAnswer` (`00` completed, `01` initiated, else rejected; `422` or transport in doubt);
      tests `g-test/providers/CozeniSuite.scala` with `cozeni/adapter_test.go` and
      `handler/callback_auth_test.go`'s cases and the three `03` cases of spec correction 2
- [X] T014 [P] Implement `g/providers/eupaymentz/EuPaymentz.scala`: `request(session, credential,
      customer, publicUrl)` (`/api/v2/processTx`, credentials in the body, `account_sha` = hex SHA-256
      of `passphrase + amount + accountId + email + ip`, amount `%d.%02d`, `merchant_payment_id` the
      payment id; `eupaymentz/adapter.go:116-248`), `redirectFrom(json)` (`PEND` and `UrlToRedirect`
      required; a POST-method redirect becomes a relay `form`), `verify(fields, passphrase)` (hex
      SHA-256 of `passphrase + resp_trans_id + resp_trans_amount + resp_trans_status`, case-insensitive,
      constant time), `outcome` (`00000` captured; `PEND` open; else failed with
      `resp_trans_description_status`); tests `g-test/providers/EuPaymentzSuite.scala` with
      `eupaymentz/signature_test.go`, `adapter_test.go` and `handler/webhook_eupaymentz_test.go`'s
      cases
- [X] T015 [P] Implement `g/providers/gigadat/Gigadat.scala`: `tokenRequest(session, credential,
      customer, publicUrl)` (`/api/payment-token/{campaignId}`, Basic `accessToken:securityToken`,
      `amount` dollars, `sandbox:true` for a sandbox credential, mobile digits only, province
      required, `transactionId` the payment id; `gigadat/adapter.go:115-190`), `redirect(base,
      transaction, token)` (`/webflow?transaction=&token=`), `webhook(query)` (`transactionId`,
      `status`, `type`; `STATUS_SUCCESS` captured or completed; `ABORTED1`, `EXPIRED`, `REJECTED`,
      `REJECTED1`, `FAILED` failed with `rcode`; `CPI` a deposit, `ETO`/`ACH` a payout;
      `gigadat/webhook.go:97-128`), `payoutRequest` (ETO with email and mobile, ACH with account,
      institution, transit; CAD only) and the two `webflow` GETs; tests
      `g-test/providers/GigadatSuite.scala` with `gigadat/adapter_test.go`, `webhook_test.go`,
      `sessions_test.go` and `handler/gigadat_test.go`'s cases
- [X] T016 [P] Implement `g/providers/bouncemoney/BounceMoney.scala`: `sessionRequest(session,
      credential, customer, publicUrl, now)` (`/checkout/sessions`, `referenceId` and
      `Idempotency-Key` the payment id, the Ed25519 headers `X-BounceMoney-Key-Id`, `-Timestamp`,
      `-Signature: v1=` base64url over `ts.body`, the private key parsed from the credential per call;
      `bouncemoney/adapter.go:88-204`, `signature.go:82-137`), `minimum` (1,500, the player message),
      `redirectFrom(json)` (`201`, `data.url`), `verify(rawBody, header, signingSecret, now)`
      (`t=…,v1=…`, HMAC-SHA256 of `t.body`, ±300 s, constant time), `outcome(json)`
      (`checkout.session.completed` captured; `.failed`/`.expired` failed with the type as code;
      other types acknowledged), `answer` (`400` malformed or not verifying; `500` no signing secret;
      `502` unreadable dependency; `200` otherwise); tests `g-test/providers/BounceMoneySuite.scala`
      with `bouncemoney/adapter_test.go` (20), `signature_test.go` (13) and
      `handler/webhook_bouncemoney_test.go` (13)
- [X] T017 [P] Implement `g/providers/digitarium/Digitarium.scala`: `request(session, credential,
      customer, publicUrl)` (`/api/v1/payments`, Bearer `apiKey`, `paymentType=DEPOSIT`,
      `paymentMethod=BASIC_CARD`, `amount` a number, `referenceId` the payment id;
      `digitarium/adapter.go:111-213`), `host(credential, settings)` (`environment` selects
      `DIGITARIUM_SANDBOX_URL` or `_PRODUCTION_URL`), `redirectFrom(json)` (`result.redirectUrl`,
      `result.id`), `verify(rawBody, signatureHeader, signingKey)` (HMAC-SHA256 of the raw body, hex
      or any base64 padded or not, constant time; `digitarium/signature.go:21-52`), `outcome(json)`
      (flat or `{result:{…}}`; `COMPLETED` captured; `DECLINED`/`CANCELLED` failed with `errorCode`,
      `errorMessage`; else open), `answer` as BounceMoney's; tests
      `g-test/providers/DigitariumSuite.scala` with `digitarium/adapter_test.go` and
      `handler/webhook_digitarium_test.go` (16)
- [X] T018 [P] Implement `g/providers/morphosis/Morphosis.scala`: `depositRequest(session, credential,
      token, publicUrl)` (form `?action=insert_deposit` with `user`, `pass`, `account`, `amount` `%.2f`,
      `method` by QVEI's names, `trace_id` the payment id, `notification_url` with the token;
      `morphosis/adapter.go:64-123`), `destinationFrom(form)` (`result=ok`, id, address, coin amount,
      deadline), `verify(token, session, fields)` (the 36-character token matches the session's; `id`
      equals the session's QVEI id), `outcome(fields, session)` (`ac` captured unless `Underpaid` →
      failed `underpaid`; `de` failed with `message`; `pe` open), `payoutRequest` (`?action=insert_payout`,
      USD only else `422`, the request id not sent, the token in the notification address),
      `payoutAnswer` (`result != ok` rejected; transport in doubt; else initiated with QVEI's id),
      `answer` (`200 OK` plain text); tests `g-test/providers/MorphosisSuite.scala` with
      `morphosis/adapter_test.go` and `handler/webhook_morphosis_test.go` (24)
- [X] T019 [P] Implement `g/delivery/Backoff.scala` (the schedule of FR-015: 4 attempts, 250 ms
      doubling, a 15 s budget, 10 s per call; `retryable(answer)`: no answer, `429`, `5xx` including
      `503 OUTCOME_PENDING`; any other `4xx` is a refusal) and `g/delivery/Signature.scala`
      (`sign(body, secret, now)` → `X-PSP-Signature` hex HMAC-SHA256 of the body's UTF-8 bytes and
      `X-PSP-Timestamp` Unix seconds, the inverse of 004's `security/Hmac.verify`); tests
      `g-test/delivery/BackoffSuite.scala` with `handler/outcome_retry_internal_test.go`'s cases
      and `g-test/delivery/SignatureSuite.scala` round-tripping through merchant's `Hmac.verify`
      cases copied from `services/merchant/src/test/.../security/HmacSuite.scala`
- [X] T020 [P] Implement `g/http/Form.scala` and `g/http/Raw.scala` (form encoding and decoding over
      the raw body with repeated keys kept; content-type sniffing for DreamzPay and Gigadat; an empty
      form told apart from another content type, FR-012), `g/Settings.scala` (data-model.md's table:
      `PUBLIC_URL` required, the eleven base URLs each optional with no default, `PROVIDER_TIMEOUT`
      20 s, `REDRIVE_INTERVAL` 2 m, `MERCHANT_SERVICE` `merchant`; `baseUrl(provider, credential)`
      answering `ProviderNotConfigured(provider)` when missing) and `g/GatewayCodecs.scala` (codecs
      for every stored type of data-model.md: `Outcome`, `Variance`, `Receiver`, `Delivery`,
      `Destination`, the events, `Long` minor units as a JSON number, `Instant` as ISO-8601, after
      `MerchantCodecs`); tests `g-test/http/FormSuite.scala`, `g-test/SettingsSuite.scala` (no
      default reaches a host; a missing base URL names the provider; a malformed duration refused
      naming the variable) and `g-test/GatewayCodecsSuite.scala` (every type round-trips)

### The sessions and the index (tests first)

- [X] T021 [P] `g-test/sessions/DepositSessionSuite.scala` (`EventSourcedTestKit`): `claim` on an
      empty session persists `Claimed` with the generated salt, token and addresses; a second `claim`
      persists nothing and replies the current state (FR-008); `initiated` only from claimed;
      `initiationFailed` only from claimed and then `settle` is refused (FR-024); `settle` from claimed
      or initiated persists one `Settled`, a second replies `AlreadySettled(outcome)` (SC-002);
      `recordDelivery` only after `Settled` and ignored once `Delivered` or `Refused`; `redrive` by
      the timer only while `Undelivered`, by an operator while `Undelivered` or `Refused`, otherwise
      the delivery state and nothing persisted (FR-016); `status` answers 004's `state`, `outcome`,
      `delivered`, `undelivered`, `refused`, `updatedAt`; no event holds a credential or a customer
      field (FR-022)
- [X] T022 [P] `g-test/sessions/PayoutSessionSuite.scala` (`EventSourcedTestKit`): the deposit's
      rules; `claim` naming another brand than the session's is refused; `answered(Completed)`
      persists `Answered` then `Settled(Completed)` in one command; `Reversed` follows `Completed`;
      nothing follows a final outcome but that; `answered(AlreadyInitiated)` is recorded and a
      repeat `claim` answers it; the destination is in no event. `g-test/sessions/ReferenceIndexSuite.scala`
      (`KeyValueEntityTestKit`): `put` once, never updated, `get` answers the kind and key, a
      missing id is none
- [X] T023 Implement `g/sessions/Outcome.scala` (the shared types of data-model.md: `Provider`,
      `Outcome`, `Variance`, `Receiver`, `Delivery`, `Destination`), `g/sessions/DepositSession.scala`,
      `g/sessions/PayoutSession.scala` and `g/sessions/ReferenceIndex.scala` (`expireAfter` 400
      days); T021 and T022 green

### The seams, the fakes and the kit

- [X] T024 [P] Implement `g/merchant/MerchantClient.scala` (the trait of research R7: `credential(subBrandId,
      provider) → Either[Unavailable | None, Credential]`, `terminalProperties(terminalId)`,
      `brandTerminals(brand)`; `MerchantClient.http` over `clients.services(MERCHANT_SERVICE)` by
      identity, the provider in the catalogue's upper case; `404` is none, `503` is unavailable; the
      values returned in the call's scope and held in no field), `g/delivery/Receivers.scala` (the
      trait: `deliver(receiver, outcome, body, signature?) → Answer`; `Receivers.http` addressing
      `merchant`'s `POST /v1/payments/{paymentId}/capture` and `/fail` within the project and
      `{brand}/payouts`'s `POST /v1/casino/brands/{brand}/withdrawals/{requestId}/outcome` through
      `clients.services(brand, "payouts")`, as 004's `WalletClient` verified the form; each call
      bounded at 10 s) and `g/http/ProviderHttp.scala` (`java.net.http.HttpClient`, 5 s connect,
      `PROVIDER_TIMEOUT` per request, no redirects; `get`, `postJson`, `postForm` answering `Answered(status,
      headers, body)`, `TimedOut`, `Unreachable`; a per-provider window of the last 20 calls with
      `state` up, degraded (more than half failed or timed out) or down (the last 5 failed), the
      counters `calls`, `failed`, `p95Seconds`; R11). **Verify at task time** (research R7) that
      merchant's credential route matches the provider's name as the catalogue spells it; record in
      research R7 and, if it does not, make merchant's match case-insensitive in `m/api/Endpoints.scala`
      (finding 1). Tests `g-test/merchant/MerchantClientSuite.scala` and
      `g-test/delivery/ReceiversSuite.scala` over a scripted HTTP stub (every row of
      contracts/merchant.md §3 and §4, contracts/payouts.md's outcome call) and
      `g-test/http/ProviderHttpSuite.scala` against a silent and a failing local server (the timeout
      holds; the window reaches degraded then down then up)
- [X] T025 [P] Write the fakes in `g-test/it/`: `FakeServer.scala` (merchant's, copied),
      `FakeMerchant.scala` (serves `GET /internal/v1/sub-brands/{subBrandId}/psp-credentials/{provider}`,
      `GET /internal/v1/terminals/{terminalId}/properties` and `GET /internal/v1/brands/{brand}/terminals`
      from settable maps, with `rotate(subBrandId, provider, values)` and a read log; serves
      `POST /v1/payments/{paymentId}/capture` and `/fail` recording each call by `<paymentId>/<outcome>`
      with the body and the two signature headers, verifying the signature when a `webhookSecret`
      is set, answering `200`, `503 OUTCOME_PENDING`, `401 missing_token`, `403`, a scripted `400` or
      silence; `stop()`/`start()`), `FakePayouts.scala` (the outcome route of contracts/payouts.md
      per brand, recording by `<requestId>/<outcome>`, the same scripted answers, `403` when told the
      grant is revoked), `PspStub.scala` (a testcontainers `GenericContainer` of the `psp-stub` image
      built by T006 with the nine ports and `PSP_STUB_GATEWAY_URL` at the kit's address; `control(provider,
      outcome, fields)`, `webhook(provider, reference)`, `received(provider)`; the nine credentials
      it was configured with, for `LeakSuite`) and `FakesMain.scala` (starts `FakeMerchant` and
      `FakePayouts` on fixed ports for quickstart §6 when `merchant` is not run)
- [X] T026 Record the original per `tests/original/README.md` and contracts/recording.md: on the
      original's KinD stack at `659c2d6b` (as spec 004's recordings were made) with `psp-stub`
      deployed beside it (T006's image, `kind load`ed), the gateway's eleven base URLs pointed at it,
      `PSP_GATEWAY_BASE_URL` at the gateway's cluster address, `PSP_GATEWAY_MERCHANT_SERVICE_URL`
      and `CASINO_SERVICE_URL` at T007's receiver; `preflight` green; the driver twice on fresh ids;
      `merge` into `tests/port/psp-gateway/` with `suite-result.json`; `just brands recordings check`
      0 problems; list in `tests/port/README.md` what was recorded at which original commit, what
      failed on the original and why (each a finding in spec.md's *Recordings* and research
      *Findings in the original*, not a requirement: the T365 marker, the repeat initiate's second
      provider session, the DreamzPay return POST's capture, Gigadat's unchecked webhook, research
      R16). Scenario 13 and 14's recordings under `tests/port/merchant/` are not re-recorded. Needs
      the original's checkout, kind and Docker
- [X] T027 Implement `g-test/it/Kit.scala`: `AnkkaTestKit.start` with the service's descriptors over
      Postgres, `HttpServer.at("127.0.0.1", 0)`, `clients.services` wired to `FakeMerchant` (as
      `merchant`) and, for every brand slug the test names, `FakePayouts` (as `payouts` in that
      project), `PspStub` started once per JVM, `TimerRuntime` and a settable clock the `Redrive`
      timer and `ProviderHttp`'s window read, `PUBLIC_URL` at the kit's own address and every base
      URL at the container's ports; callers `asMerchant` (`Caller.Service("payments", "merchant")`),
      `asPayouts(brand)`, `asOperator` (a staff token with platform scope, as 004's T020 mints),
      `asBrowser` and `asProvider` (no identity, through the public routes), `asStaff` (a staff
      token on an identity route, for `403`); helpers `credential(subBrandId, provider, values)`,
      `terminal(brand, …)`, `initiate(provider, method, …)` answering the body, `webhook(provider,
      reference)`, `redrive(reference)`, `advance(duration)`; `OwnKit` for the suites that stop a
      fake or move the clock, as merchant's `SharedKit`/`OwnKit`. **Verify at task time** (plan
      Phase 0) how long a consumer handler may run on 0.11 (the deliverer's 15 s budget runs inside
      `OutcomeDeliverer`); record in research R5; if a handler is bounded under 15 s, the fallback is
      one attempt per handler run and a short `Redrive` cadence until the budget is spent, and T047's
      design changes with it

### The suites that list their tests, the shells

- [X] T028 [P] Add `g-test/port/ParitySuite.scala`: one munit test per `tests/port/psp-gateway/*.json`
      through `contract.port.Replay` against T027's kit, failing when the directory is empty, and
      `g-test/port/Parity.scala` mapping what is not this service's (contracts/recording.md):
      the driver's unauthenticated initiate → `asMerchant`, its `terminalProperties` → `FakeMerchant`'s
      credential routes serving the same values; `/__control`, `/__webhook` and `/__received` steps
      → `PspStub`; the receiver's `observe:` steps → `FakeMerchant`'s and `FakePayouts`'s logs; the
      payout routes → `POST /v1/payments/withdrawals` as `asPayouts(brand)` with the amount in minor
      units (marked); `POST /internal/v1/psp/redrive-undelivered` → `advance(REDRIVE_INTERVAL)`;
      `/health` → the runtime's readiness (marked); `Parity.divergences` naming each row of plan.md
      *Divergences* at the steps it holds, checked to still differ and nothing undeclared to. Until
      T026 records, the suite reports itself ignored, never passed; `ParityFixtureSuite` proves the
      mapping on a hand-written fixture under `tests/fixtures/port/psp-gateway/`
- [X] T029 [P] Add `g-test/features/ProvidersFeatures.scala`, `CryptoFeatures.scala`,
      `WithdrawalPaymentsFeatures.scala` and `OutcomesFeatures.scala`: `GherkinSuite` over
      `features/payments/<name>.feature` on T027's kit, one suite per file, every step pending so
      each scenario fails as unbound (14 + 6 + 6 + 5 scenarios, the two outlines once per row);
      `deposits.feature` and `cashier.feature` are 004's and `features/payouts/` is 007's; none is
      bound here
- [X] T030 Implement `g/PspGatewayService.scala` (registration of every component as the phases
      add them; the `Redrive` schedule hook) and the endpoint shells in `g/api/`, one per prefix
      with each route's audience (contracts/routes.md): `PspEndpoint.scala` (`/v1/psp`,
      `Callers.service("payments", "merchant")`), `CheckoutEndpoint.scala` (`/v1/psp/checkout`,
      `Routes.public`), `WebhooksEndpoint.scala` (`/v1/webhooks`, `Routes.public`, every route
      `postBody`), `WithdrawalsEndpoint.scala` (`/v1/payments/withdrawals`) and
      `WithdrawalTerminalsEndpoint.scala` (`/v1/payments/withdrawal-terminals`), both
      `Acl.AllowIf` admitting `Caller.Service(_, "payouts")` and `Caller.Local` as merchant's
      `ErasureEndpoint` does, `OutcomesEndpoint.scala` (`/v1/psp/outcomes`, `Routes.paymentsOperator`),
      `StatusEndpoint.scala` (`/status`, `Callers.anyInProject` and payments operators); every route
      declared with a `501` body the stories replace; `g/api/Refusal.scala` rendering `{"error",
      "message"?, "provider"?}` with contracts/merchant.md's words. **Verify at task time** (plan
      Phase 0) that a nested public prefix `/v1/psp/checkout` is served beside `/v1/psp` by ankka's
      longest match (`HttpServer.scala:318-321`); record in research R2; fallback: one `/v1/psp`
      endpoint with per-route `withAcl`. A smoke test `g-test/it/SmokeSuite.scala`: the service
      starts; `/status` answers the project's caller; `POST /v1/psp/hpp/initiate` as `payouts` or
      with a staff token is `403`; `POST /v1/payments/withdrawals` as `merchant` is `403`;
      `/v1/psp/outcomes/undelivered` without a token is `401`; `POST /v1/webhooks/dreamzpay` with no
      identity reaches the handler; `GET /v1/psp/checkout/t365/x/return` with no identity is `200`
      `text/html`

**Checkpoint**: `sbt compile test` green for T008–T024 and T030; `ParitySuite` lists one failing
test per recording (after T026); the four feature suites list 31 pending scenarios; a session can be
claimed through the kit and a credential read from `FakeMerchant`; `psp-stub` answers from its
container; nothing is served but `/status`, the return pages' shells and refusals.

---

## Phase 3: User Story 1 — A player is sent to a provider's page and the deposit comes back captured (P1) 🎯 MVP

**Goal**: `merchant` initiates a deposit for any of the nine providers and gets a redirect (or a
crypto destination); the credential is read from `FakeMerchant` at that moment; the session is
claimed before the provider is called; a repeat answers the first answer; `psp-stub`'s webhook is
verified over the raw body, cross-checked, settled once, and the capture or fail reaches
`FakeMerchant` once, signed when the credential has a `webhookSecret`; `merchant` reads the
session's status.

**Independent Test**: quickstart §5 for `providers.feature` lines 9–51 across nine providers,
`RepeatSuite`, and the parity recordings for the deposit scenarios.

### Tests for User Story 1

- [X] T031 [P] [US1] `g-test/delivery/OutcomeDelivererSuite.scala` (`ConsumerTestKit` with a
      scripted `Receivers`): on `Settled` of a deposit the capture body of 004's `gateway.md`
      (`outcome: "success"`, `psp_transaction_id`, the variance fields) or the fail body
      (`failureCode?`, `failureReason`) is sent to the session's receiver as this service, and
      `DeliveryRecorded(Delivered)` is persisted on `2xx`; signed with `X-PSP-Signature` and
      `X-PSP-Timestamp` when `FakeMerchant`'s credential has a `webhookSecret`, neither header
      otherwise (R13); `Settled` of a payout sends contracts/payouts.md's body to the brand's
      `payouts`; a `Delivered` session on a later event sends nothing; the handler never throws and
      returns `done` on every answer (the `400`, `503` and silence cases are US4's, named here as
      pending)
- [X] T032 [P] [US1] `g-test/it/InitiateSuite.scala` on the kit: for each of the nine providers, an
      initiate as `merchant` (`terminalProperties: {}`) answers `redirectUrl` (a relay address for
      DreamzPay and EuPaymentz POST flows; empty for Cozeni and Morphosis) and `pspTransactionId` =
      the payment id (dashless for T365), and `PspStub.received` shows one call carrying the
      payment id in the provider's reference field with the amount in the provider's form; the
      session is `Initiated`; a repeat under the same payment id answers the same body and
      `PspStub.received` grows by nothing (FR-008); a repeat while the first call is held open by
      the stub is `409 initiation_in_progress`; no credential: `404 no_credentials` naming provider
      and sub-brand and no provider call; a decimal amount `400 bad_request`; Interac in EUR `400
      unsupported_currency`; a provider with no base URL `502 provider_not_configured`; the stub
      down `502 provider_unavailable` and the session `InitiationFailed`, after which a webhook for
      it is acknowledged and settles nothing (FR-024); BounceMoney at 1,499 is `400 player_error`
      naming the minimum; `terminalProperties` carrying a value is never read (the credential comes
      from `FakeMerchant`, whose log shows one read per initiate); `FakeMerchant`'s terminal
      properties route is read when the initiate names a terminal
- [X] T033 [P] [US1] `g-test/it/WebhookSuite.scala` on the kit: for each provider, after an
      initiate, `PspStub.webhook` with `success` makes the session `Settled(Captured)` and one
      capture call at `FakeMerchant` with `psp_transaction_id`; with `failure` and a code, one fail
      call with the provider's code and the original's player-facing reason; the provider's answer
      is the original's (R9's column: DreamzPay's JSON, `200` empty, Morphosis `OK` text); a webhook
      with a bad signature (`unsigned` control, or a tampered body) is answered as the original
      answers it (DreamzPay `400 invalid_checksum`; BounceMoney and Digitarium `400`; the rest `200`),
      logged with the reason, and the session is unchanged; an unknown reference is acknowledged
      (DreamzPay `unknown_txn`) and nothing is called; a webhook whose amount or currency differs
      from the session's is treated as not verifying (FR-013; the EuPaymentz replay of finding 4
      included); the same webhook again after a capture is acknowledged and `FakeMerchant` holds
      one call; an open status (`pending` control) leaves the session `Initiated` and `FakeMerchant`
      uncalled; a T365 webhook finds its session through `ReferenceIndex`; a Morphosis webhook on a
      `Claimed` session is acknowledged and moves nothing (R3); a webhook whose credential cannot be
      read answers `502` for BounceMoney and Digitarium and `200` for the rest; `merchant`'s unsigned
      and signed captures as R13 (a credential with a `webhookSecret` → `FakeMerchant` verifies and
      answers `200`)
- [X] T034 [P] [US1] `g-test/it/SessionReadSuite.scala`: `GET /v1/psp/sessions/{paymentId}` as
      `merchant` answers `pending` after an initiate, `resolved` with `outcome` and `delivered: true`
      after a capture, `404` for an unknown id, `403` for another caller; `refused` and
      `undelivered` are present and false (US4 makes them true). `g-test/it/RepeatSuite.scala`
      (SC-002): for each provider, the success webhook delivered five times, concurrently and then
      again, makes one `Settled` and one capture at `FakeMerchant`
- [X] T035 [P] [US1] Bind `providers.feature` lines 9, 15, 21, 27, 32, 39, 45 and 51 in
      `g-test/features/ProvidersFeatures.scala`, each step over the kit's helpers, the provider
      taken from the step's words or DreamzPay by default; the cashier is `FakeMerchant`
- [X] T036 [US1] Confirm `ParitySuite` lists the deposit recordings (initiate, capture, fail, bad
      signature, unknown reference, repeat, open status, tampered amount for nine providers) as
      failing; name in `g-test/port/Parity.scala` the divergences that hold on them (the repeat's
      absent second provider call; the tampered amount's absent capture; the payment id as the
      provider's reference; `terminalProperties` served from `FakeMerchant`; the refusal codes;
      Gigadat admitted on the cross-check). Needs T026

### Implementation for User Story 1

- [X] T037 [US1] Implement `g/deposits/Initiate.scala`: parse the 004 `gateway.md` body (a decimal
      or missing amount `400 bad_request`; an unknown provider the same; `terminalProperties`
      ignored), `Catalogue` checks (method and currency `400 unsupported_*`; BounceMoney's minimum
      `400 player_error`), `Settings.baseUrl` (`502 provider_not_configured`), `MerchantClient`
      read (`404 no_credentials`; `503` → `502 provider_unavailable`), then `DepositSession.claim`
      (a repeat answers the recorded state: `Initiated` as the first body, `InitiationFailed` as the
      first refusal, `Claimed` as `409 initiation_in_progress`), `ReferenceIndex.put` for T365 and
      Morphosis, the provider's call through `ProviderHttp` (DreamzPay makes none: its relay address
      is the answer; Cozeni makes the rate call first), `initiated` or `initiationFailed`
      (`502 provider_unavailable`, or BounceMoney's `userMessage` as `400 player_error`), and the
      answer body; T032 green
- [X] T038 [US1] Implement `g/deposits/Webhooks.scala`: the pipeline of research R8 over the raw
      body (resolve by the reference or `ReferenceIndex`; `MerchantClient` read; the provider's
      `verify`; the cross-check of FR-013 with the session's checkout or terminal, amount, currency
      and state; the provider's `outcome`; `DepositSession.settle` or nothing; the provider's
      `answer`), with every refusal logged with its reason and no body logged; a Gigadat webhook
      routed to a deposit or a payout by `type` (US3 completes the payout half); T033 green
- [X] T039 [US1] Implement `g/delivery/OutcomeDeliverer.scala` (the consumer of both sessions'
      events reacting to `Settled` and `Redriven`: the steps of data-model.md with `Backoff`,
      `Signature` when the credential read for the session has a `webhookSecret`, `Receivers`;
      `recordDelivery`; `done` always) with the `Delivered` path; register it in
      `PspGatewayService`; T031's US1 cases green
- [X] T040 [US1] Fill `g/api/PspEndpoint.scala` (`POST /hpp/initiate` → `Initiate`;
      `GET /sessions/{reference}` → `DepositSession.status` with `refused`) and the nine deposit
      webhook routes of `g/api/WebhooksEndpoint.scala` → `Webhooks` (payout routes stay `501` until
      US3); T032–T035 green; T036's deposit recordings replay with only the named divergences
- [X] T041 [US1] 004 follow-up in `services/merchant/`: `m/deposits/DepositWorkflow.scala` sends
      `terminalProperties: {}` (the terminal's secrets no longer read into the initiate; finding 1)
      and `m/gateway/GatewayClient.scala` reads `refused: true` as `SessionStatus.Undelivered`'s
      twin (`SessionStatus.Refused`), which `expireStep` waits on as it waits on undelivered
      (finding 7, contracts/merchant.md §2); `services/merchant/src/test/.../it/FakeGateway.scala`
      answers `refused: true` when scripted; `DepositWorkflowSuite` gains "a refused outcome waits
      another deadline" and "the initiate carries no terminal property"; `specs/004-merchant/contracts/gateway.md`
      §2 gains `refused`; `just merchant-test` green

**Checkpoint**: nine providers initiate and capture on the kit; `RepeatSuite` holds SC-002;
`providers.feature` lines 9–51 pass; the deposit recordings replay; merchant sends no secret in an
initiate.

---

## Phase 4: User Story 2 — A crypto deposit is quoted, paid and checked for short payment (P1)

**Goal**: Cozeni and Morphosis answer a destination; a confirmation at or over the quote captures
(with `OVERPAYMENT`); a short one fails unless Cozeni valued it, when it captures with
`UNDERPAYMENT` and the received values; a missing amount is short; Morphosis is admitted only on its
token.

**Independent Test**: quickstart §5 for `crypto.feature` and `ShortSuite`.

### Tests for User Story 2

- [X] T042 [P] [US2] `g-test/it/CryptoSuite.scala` on the kit: a Cozeni initiate makes the rate
      call then the payin call and answers `paymentAddress`, `cryptoAmount` to 8 places,
      `cryptoCurrency`, `validUntil` and an empty `redirectUrl`; a Morphosis initiate the same with
      the token in its `notification_url`; `success` captures with no variance; `overpayment` (Cozeni
      `02`) captures with `OVERPAYMENT`, quoted and received at `FakeMerchant`; Cozeni `underpayment`
      with `convertAmount` 0 fails with `03` and the variance; with `convertAmount` > 0 captures with
      `UNDERPAYMENT` and `receivedConvertAmount`, `receivedConvertCurrency` (correction 2); a
      Morphosis `ac` one unit short fails `underpaid`; a Morphosis `ac` with no `amount` fails as
      short; a Morphosis webhook on another session's token is acknowledged and moves nothing; a
      Cozeni webhook without the session's salt fails closed. `g-test/it/ShortSuite.scala` (SC-005):
      the three one-unit-short cases, `FakeMerchant`'s capture never carries the invoice as credited
- [X] T043 [P] [US2] Bind `crypto.feature` lines 8, 14, 20, 26, 32 and 37 in
      `g-test/features/CryptoFeatures.scala`
- [X] T044 [US2] Confirm `ParitySuite` lists the crypto recordings for Cozeni and Morphosis as
      failing; name their divergences in `g-test/port/Parity.scala` (none expected beyond US1's).
      Needs T026

### Implementation for User Story 2

- [X] T045 [US2] Extend `g/deposits/Initiate.scala` with the crypto path (Cozeni's rate call and
      salt; Morphosis's token; `Destination.Crypto` in the answer) and `g/deposits/Webhooks.scala`
      with the variance mapping into the capture body (`Variance` to 004's fields) and the Morphosis
      token route (`POST /morphosis/deposit/{token}` in `WebhooksEndpoint`); T042–T044 green

**Checkpoint**: `crypto.feature` passes; `ShortSuite` holds SC-005; the crypto recordings replay.

---

## Phase 5: User Story 3 — A brand's payout is initiated and its outcome delivered (P1)

**Goal**: a brand's `payouts` lists its withdrawal terminals, initiates a payout under the request
id through Unity Finance, Cozeni, Morphosis or Gigadat, and reads its status; the outcome arrives by
webhook or status read and reaches `FakePayouts` once under grant.

**Independent Test**: quickstart §5 for `withdrawal-payments.feature` and the payout recordings.

### Tests for User Story 3

- [X] T046 [P] [US3] `g-test/it/PayoutSuite.scala` on the kit: `POST /v1/payments/withdrawals` as
      `asPayouts("goldvibe")` for each of the four providers answers `initiated` with the provider's
      reference and `PspStub.received` shows one call carrying the request id where the provider
      takes one (Unity Finance `paymentNo`, Cozeni `paymentRef`, Gigadat `transactionId`; Morphosis
      none) with the amount in the provider's form from minor units; a repeat under the request id
      answers the same and calls nothing; a body naming another brand is `403`; `brand` differing
      from the session's on a repeat is `409`; `duplicate` control → `already_initiated`; an empty
      credential at `FakeMerchant` → `rejected` / `missing_credentials` and no provider call; the
      stub's `401` → `rejected` / `unauthorised`; Cozeni `00` → `completed` and one outcome call at
      `FakePayouts`; the stub silent → `in_doubt`; Morphosis in EUR → `rejected` /
      `unsupported_currency`; each provider's withdrawal webhook (`PspStub.webhook` on the payout
      routes; Gigadat through the deposit route with `type` `ETO`) → one outcome call with
      `completed`, `failed` or `reversed` and the provider reference; a payout webhook after a kit
      restart still reaches the brand (`DurabilitySuite`, the Gigadat brand on the session). Status:
      `GET /v1/payments/withdrawals/{requestId}` for Unity Finance asks the stub (`completed`,
      `failed`, `reversed`, `pending`, `no_record`; a final state settles and delivers once), for
      the others answers the session (`pending` until a webhook), `404` for no session, `502
      status_unavailable` when the stub is down or the credential empty, never `no_record`.
      Terminals: `GET /v1/payments/withdrawal-terminals?currency=` lists `FakeMerchant`'s brand
      terminals with `supportsWithdrawal` only, each with `hasStatus`, `reviewWindow` and `coins`
- [X] T047 [P] [US3] Bind `withdrawal-payments.feature` lines 8, 14, 20 (the outline per row), 31,
      37 and 42 in `g-test/features/WithdrawalPaymentsFeatures.scala`; the brand is `FakePayouts`
- [X] T048 [US3] Confirm `ParitySuite` lists the payout recordings as failing; name their divergences
      in `g-test/port/Parity.scala` (the route family and minor units; the payout outcome delivered
      with retries; the status read's path; the Gigadat brand on the session). Needs T026

### Implementation for User Story 3

- [X] T049 [P] [US3] 004 follow-up in `services/merchant/`: `GET /internal/v1/brands/{brand}/terminals`
      on `m/api/Endpoints.scala`'s `InternalEndpoint` (finding 2): `terminals[]` with `id`,
      `subBrandId`, `gateway`, `scheme`, `name`, `status`, `supportsDeposit`, `supportsWithdrawal`,
      `requiresPayoutDestination` and `properties{}` with the catalogue's secret keys removed, over
      `HierarchyViews.subBrandByBrand` and `TerminalsBySubBrand`; `specs/004-merchant/contracts/routes.md`
      gains the route; `services/merchant/src/test/.../it/OperatorRoutesSuite.scala` (or a new
      `InternalRoutesSuite`) proves it as `psp-gateway`, `403` for another caller, no secret value in
      the answer; `just merchant-test` green. **Verify at task time** (research R12) which terminal
      property the original's casino-service read for a payout terminal's coins (`supportedCoins`
      assumed); record in research R12
- [X] T050 [US3] Implement `g/payouts/Initiate.scala` (parse contracts/payouts.md's body; the
      brand must be the caller's project; `Catalogue` checks; `MerchantClient` read; `PayoutSession.claim`;
      the provider's call with the request id as the reference; `answered`; `Completed` settles in
      the same command), `g/payouts/Status.scala` (Unity Finance's status read through `ProviderHttp`
      settling a final state; the session's outcome for the rest; `404`; `502 status_unavailable`)
      and `g/payouts/Terminals.scala` (`MerchantClient.brandTerminals` filtered to
      `supportsWithdrawal`, plus `Catalogue`'s `hasStatus`, `reviewWindow` and the `coins` property);
      the payout half of `g/deposits/Webhooks.scala` (Unity Finance and Cozeni withdrawal routes,
      Morphosis's payout token route, Gigadat's `ETO`/`ACH`) settling `PayoutSession`; the payout
      body in `OutcomeDeliverer` (T031's payout case green); fill `WithdrawalsEndpoint`,
      `WithdrawalTerminalsEndpoint` and the payout webhook routes; T046–T048 green

**Checkpoint**: `withdrawal-payments.feature` passes; every outcome reaches `FakePayouts` once; the
payout recordings replay; `merchant` serves the brand terminal listing.

---

## Phase 6: User Story 4 — An outcome the receiver could not take is not lost (P1)

**Goal**: a receiver answering `503`, `429` or nothing is retried under the original's budget; a
`4xx` is recorded refused after one attempt; an exhausted budget leaves the outcome undelivered,
listed, re-driven every two minutes by the timer and at once by an operator; a refused outcome is
left by the timer and re-driven by an operator.

**Independent Test**: quickstart §4's deliverer cases and §5's `RedriveSuite`; `outcomes.feature`.

### Tests for User Story 4

- [X] T051 [P] [US4] Extend `g-test/delivery/OutcomeDelivererSuite.scala` (T031's pending cases):
      `503` × 4 → `DeliveryRecorded(Undelivered(attempts 4, lastAnswer 503))` within 15 s with the
      250 ms doubling schedule observed; silence → `Undelivered` after 10 s per call within the
      budget; `503` then `200` → `Delivered` with 2 attempts; `429` retried; `400` →
      `Refused(answer)` after one attempt; `401 missing_token` and `403` refused; `Redriven(timer)`
      on an `Undelivered` session delivers again; `Redriven(operator)` on a `Refused` session
      delivers; a `Settled` event replayed after a crash (the handler run twice with no record
      between) calls the receiver twice and records once; nothing thrown in any case
- [X] T052 [P] [US4] `g-test/it/RedriveSuite.scala` (SC-003, `OwnKit`): `FakeMerchant` stopped;
      twenty captures across providers; each session `Undelivered` within the budget and listed by
      `GET /v1/psp/outcomes/undelivered` as an operator with reference, kind, receiver, outcome,
      attempts, last answer and since; `GET /v1/psp/sessions/{id}` as `merchant` reads
      `undelivered: true`; `advance(2 minutes)` with the fake still down leaves them listed;
      `FakeMerchant` started and `advance(2 minutes)`: every one delivered once, the list empty,
      none twice; `POST /v1/psp/outcomes/{reference}/redrive` on an undelivered one delivers at once
      (`202`) without the clock; on a delivered one `409 not_redrivable`; a `FakePayouts` outcome
      the same way. `FakeMerchant` answering `400`: the session `Refused`, listed by
      `GET /v1/psp/outcomes/refused` with the answer, `GET /v1/psp/sessions/{id}` reads
      `refused: true`, `advance(2 minutes)` sends nothing, the operator's re-drive after the fake is
      fixed delivers once (FR-016); `FakePayouts` with the grant revoked (`403`) the same way (edge
      case); `/status` reports `undelivered` and `refused` counts; a `SessionsView` row per session
      of either kind (`KeyedView`); the timer's run with 500 undelivered takes them oldest first
- [X] T053 [P] [US4] Bind `outcomes.feature` lines 6, 12, 18, 25 (the outline per row) and 36 in
      `g-test/features/OutcomesFeatures.scala`
- [X] T054 [US4] Confirm `ParitySuite` lists the outcome recordings (`503`, `400`, silent, the
      re-drive route) as failing; name their divergences in `g-test/port/Parity.scala` (the
      re-drive as the clock; the refused outcome kept and listed where the original dropped it, no
      marker in the recording). Needs T026

### Implementation for User Story 4

- [X] T055 [US4] Complete `g/delivery/OutcomeDeliverer.scala` with the retry loop over `Backoff`,
      the `Refused` and `Undelivered` records, and the `Redriven` reaction (an operator's on
      `Refused`, either's on `Undelivered`); T051 green
- [X] T056 [US4] Implement `g/sessions/SessionsView.scala` (a `KeyedView` over `eventsOf(DepositSession)`
      and `eventsOf(PayoutSession)`, data-model.md's row and the queries `undelivered`, `refused`,
      `byProvider`; ankka 0.11 `views.md` "Several entities into one table") and
      `g/delivery/Redrive.scala` (the timed action: `SessionsView.undelivered` limit 500 oldest
      first, `redrive(by = timer)` on each; scheduled recurring every `REDRIVE_INTERVAL` from
      `PspGatewayService` as merchant's `OperatorSync` is; a failed run left to the timer runtime's
      retry); fill `g/api/OutcomesEndpoint.scala` (`GET /undelivered?limit=`, `GET /refused?limit=`,
      `POST /{reference}/redrive` → `redrive(by = operator id)` answering `202` or `409 not_redrivable`
      with the delivery state) and `/status`'s two counts; T052–T054 green

**Checkpoint**: `outcomes.feature` passes; `RedriveSuite` holds SC-003; the deliverer kit holds
FR-015 and FR-016; the outcome recordings replay.

---

## Phase 7: User Story 5 — An operator saves credentials and the next deposit uses them (P2)

**Goal**: every provider call and webhook verification reads the credential from `merchant` at
that moment; a rotation applies to the next call with no restart; BounceMoney's key is parsed per
request; no credential value or player field is anywhere the gateway writes.

**Independent Test**: `RotationSuite` and `LeakSuite`; `providers.feature` lines 57–73.

### Tests for User Story 5

- [X] T057 [P] [US5] `g-test/it/RotationSuite.scala` (SC-006): initiate for each provider; `FakeMerchant.rotate`
      the credential; initiate again under a new payment id; `PspStub.received`'s second call
      carries the new value (the new `memberId`, `apikey`, Bearer, Basic, Ed25519 key id);
      `FakeMerchant`'s log shows two reads by `psp-gateway`; a webhook after the rotation verifies
      under the new secret and not the old; BounceMoney with a rotated private key signs the second
      call with the new key id and the parse happens on each call (a malformed key on the second
      read refuses that initiate only, `502 provider_unavailable`, and the first key is not reused);
      a kit restart between the two initiates changes nothing
- [X] T058 [P] [US5] `g-test/it/LeakSuite.scala` (SC-004): after an initiate, a webhook, a capture
      and a payout for every provider with customer fields filled, scan every table of the service's
      database over JDBC (the journal, the view tables, the key value store), the captured log lines
      and spans, every response body the kit received and `/status` for each of `PspStub`'s nine
      credentials' values, each customer field value and the Ed25519 private key; none found; a
      positive control proves the scan finds a value planted in a log line
- [X] T059 [P] [US5] Bind `providers.feature` lines 57, 62, 68 and 73 in
      `g-test/features/ProvidersFeatures.scala`

### Implementation for User Story 5

- [X] T060 [US5] Fix what T057 and T058 find in `g/merchant/MerchantClient.scala`, the providers and
      `logback.xml` (a credential held past the call; a body or header logged; a customer field in
      an event or a span attribute); confirm `BounceMoney.sessionRequest` parses the key from the
      read value on every call and holds no parsed key; T057–T059 green

**Checkpoint**: SC-004 and SC-006 hold; `providers.feature` lines 57–73 pass.

---

## Phase 8: User Story 6 — The player returns from the provider and sees the right page (P3)

**Goal**: every return route answers the original's neutral processing page and settles nothing;
the DreamzPay and EuPaymentz relay pages auto-POST the session's form; an unknown reference gets the
same page.

**Independent Test**: `PagesSuite`; `providers.feature` lines 79 and 85.

### Tests for User Story 6

- [X] T061 [P] [US6] `g-test/it/PagesSuite.scala`: `GET /v1/psp/checkout/dreamzpay/{paymentId}`
      after a DreamzPay initiate answers `200 text/html` with a form whose action is
      `{DREAMZPAY_BASE_URL}/transaction/Checkout` and whose fields are `DreamzPay.checkoutForm`'s
      (the checksum included, the secure key not); `GET /v1/psp/checkout/eupaymentz/{paymentId}`
      after an EuPaymentz initiate with a POST-method redirect answers the relay form; for each
      provider's return route (`/{reference}/return`, `/unityfinance/…`, `/t365/…`, `/eupaymentz/…`,
      `/gigadat/…`, `/bouncemoney/…`, `/digitarium/…`, `/dreamzpay/{reference}/return` GET and POST)
      the processing page is answered `200 text/html` with the original's text (`hpp.go:919-927`)
      and the session is unchanged, `FakeMerchant` uncalled; the DreamzPay return POST with a
      verified form settles nothing (correction 8); an unknown reference gets the same page; a relay
      for an unknown or non-relay session is the processing page too
- [X] T062 [P] [US6] Bind `providers.feature` lines 79 and 85 in
      `g-test/features/ProvidersFeatures.scala`
- [X] T063 [US6] Confirm `ParitySuite` lists the DreamzPay return-POST recording as failing; name
      its divergence (the original's capture call absent in replay). Needs T026

### Implementation for User Story 6

- [X] T064 [US6] Implement `g/http/Pages.scala` (the relay page from `dreamzpay/templates.go` and
      `hpp.go:846`, `:1046`, with the form fields escaped; the processing page from `hpp.go:919-927`)
      and fill `g/api/CheckoutEndpoint.scala`'s thirteen routes, every return route logging the
      form or query it received and settling nothing; T061–T063 green

**Checkpoint**: all 14 `providers.feature` scenarios pass; the return-POST recording replays with
its divergence.

---

## Phase 9: Polish and cross-cutting

**Purpose**: the status page and health, durability, SC-001's count, the README, the manifest, the
local run, the installation, and the findings for other specs.

- [X] T065 [P] `g-test/it/StatusSuite.scala` (FR-023): `GET /status` as the project's caller
      answers nine providers each with `state`, `calls`, `failed`, `p95Seconds`, `configured`;
      `psp-stub` made silent for one provider (a control that holds the answer past
      `PROVIDER_TIMEOUT`) moves that provider to `degraded` then `down` and leaves the others `up`;
      a provider with no base URL is `configured: false`; a staff token with platform scope reads it;
      another brand's service is `403`
- [X] T066 Set `PROVIDER_TIMEOUT` and the window in `g/http/ProviderHttp.scala` as T065 needs;
      fill `g/api/StatusEndpoint.scala` with the window and `SessionsView.byProvider`; T065 green
- [X] T067 [P] `g-test/it/DurabilitySuite.scala` (`OwnKit`): sessions of both kinds in every state
      (`Claimed`, `Initiated`, `InitiationFailed`, `Settled` undelivered, refused, delivered) read
      the same after a kit restart; an undelivered outcome is delivered by the first timer run after
      the restart; `ReferenceIndex` entries survive; a repeat initiate after the restart answers the
      first answer
- [X] T068 [P] `g-test/port/CoverageSuite.scala` (SC-001): `tests/port/psp-gateway/` holds at least
      one recording per deposit path (nine) and per payout path (four), each replayed by `ParitySuite`
      with `runs: 2`; the suite names any missing provider
- [X] T069 [P] Edit `shared/payments/manifest.json`: `psp-gateway`'s `env` with `PUBLIC_URL`
      (`https://psp-gateway-payments.<host>` from the overlay), the eleven base URL settings (set to
      `psp-stub`'s cluster addresses in `installation/overlays/dev-kind/settings.env` and left unset
      in `gke`'s, so a production installation refuses by name until an operator sets them),
      `PROVIDER_TIMEOUT`, `REDRIVE_INTERVAL`; the `grants.made` of T002; a `psp-stub` deployment
      for dev-kind (`installation/overlays/dev-kind/psp-stub.yaml`, the image `kind load`ed) so the
      kind installation has a provider to call; `just brands validate shared/payments/manifest.json`
      green; `ManifestSuite` extended where it checks the shared manifest
      *Done 2026-10-21 as a brands-tool change: `setting:<KEY>` in a manifest's env is resolved from the
      installation's `settings.env` at render time and left out when unset (`Render`, `RenderSuite`,
      `ManifestSuite`, the tool's README); `validate --installation` reports each setting. dev-kind names the
      twelve at psp-stub (`casino-stubs` namespace, webhooks back through ankka's gateway with the local CA),
      gke names the public URL only. The CA ConfigMap is created out of band (`psp-stub.yaml` says how).*
- [X] T070 [P] Update `services/psp-gateway/README.md` (purpose; build and test; the components
      table as merchant's: 2 event sourced, 1 key value, the view, the consumer, the timed action,
      the pure packages; the seven prefixes and their audiences, which are exposed; the nine
      providers and what each offers; the fakes and `FakesMain`; `psp-stub`'s controls; the
      settings; how to record), `tests/port/README.md` (the gateway recordings), `tests/README.md`
      (`psp-stub` in place of the seven), and research R2, R5, R7, R12 with every *verify at task
      time* finding under *As built (implementation)*
- [X] T071 Run quickstart §6: `psp-stub` from `tests/stubs/bin/`, `sbt schema && docker compose up -d`,
      `just psp-gateway-run` on 9001 with `-Dankka.local-services.merchant=http://localhost:9000`,
      `just merchant-run` on 9000 with `-Dankka.local-services.psp-gateway=http://localhost:9001`;
      through `ankka-inspect`: a credential saved at merchant for DreamzPay with the stub's values, a
      session as `ingest`, a submit, `psp-stub` driven to `success` and `POST /__webhook`; the
      payment captured at merchant, `GET /v1/psp/sessions/{paymentId}` reading `resolved`,
      `captured`, `delivered: true`, both still so after the gateway is stopped and started; the
      same for a Cozeni deposit (the crypto destination shown by merchant's session read); fix
      what the inspection finds, each fix with a test seen failing first
      *Run 2026-10-21: met on every clause (DreamzPay `5e30d544…`, Cozeni `5d5ef2ed…`; both read
      `resolved`, `captured`, `delivered: true` before and after the gateway restart; merchant's fake
      wallet credited each). Found and fixed: both services started under ankka's default name, so the
      local console and `ankka mcp` listed two services called `ankka` (`.start(name = …)` in both; a
      wiring line, no unit test holds it). Found and fixed in the Justfile: `psp-gateway-run` did not
      name `merchant` as a local service. Not a finding: a Cozeni credential registered without a
      scheme is named after its provider, as the original did, and then carries no crypto currency;
      the operator gives `scheme: CRYPTO`, which the quickstart now says.*
- [X] T072 Run quickstart §7 on kind: `psp-stub` deployed, `psp-gateway:0.1.0` built under the
      manifest's name and `kind load`ed, `just brands apply shared/payments/manifest.json`;
      `psp-gateway` Ready and exposed, `ingest` still waiting (spec 006); a webhook to
      `https://psp-gateway-payments.127.0.0.1.sslip.io/v1/webhooks/dreamzpay` from outside the
      cluster answered; `GET /v1/psp/sessions/x` with a staff token `403`; `/status` nine providers
      `configured: true`; and spec 004 T081's last clause: with the `goldvibe` wallet deployed, a
      session submitted at merchant from inside the cluster initiates at the gateway, `psp-stub`'s
      webhook captures it, and `payment.initiated` then the capture's facts appear on the brand's
      `payments.payments`; mark 004's T081 done in `specs/004-merchant/tasks.md`
      *Run 2026-10-21 on a rebuilt `kind-ankka` (ankka 0.12.0). Met: `psp-gateway` Ready 1/1 and
      exposed; `ingest` in ImagePullBackOff; the DreamzPay webhook from the host answered 400 `invalid
      request body` (the service's own answer, through the gateway); the session route 403 to a
      `platform_admin` staff token; `/status` (which admits a payments operator, not only the project's
      services as 004's T081 had read it) nine providers `configured: true`, `undelivered: 0`. Three Cozeni
      deposits submitted at merchant as `ingest` (its certificate, from inside its pod; spec 006 has no
      image, so a pod that only holds the certificate stood in for it), each initiated at the gateway, each
      captured by `psp-stub`'s webhook sent from inside the cluster to the gateway's public name, each
      `COMPLETED` at merchant, each with `payment.initiated` published by the wallet (merchant's
      outcome-reporter → wallet `/payments/{paymentId}/outcome` → `payments-publisher`, in the traces).
      Not met at first, and not this spec's: the capture's facts, because the brand's wallet never
      credited in a cluster. Met later the same day, once the wallet was fixed, rebuilt and rolled
      out: one more Cozeni deposit, captured and credited in one trace (`83dcabf7…`: `deposit-deliverer`
      → merchant `capture` → `deposit credit` → wallet `deposit-flow start` → `check-rg` →
      `responsible-gambling` 200 in 15 ms → `credit` → `wallet deposit` → merchant `payment credited` →
      `record-delivery`), and the three morning deposits merchant had been retrying as `RG_UNAVAILABLE`
      re-checked and credited by the same rollout with no operator action (`7617db94…`, `730f05b6…`,
      `8d16f364…`; the first check after the rollout took 479 ms, the stand-in's cold start, close to
      the 500 ms). Two defects in `services/wallet` (spec 002), found through the traces: (1) its
      `ResponsibleGambling.http` makes the RG call on a `CompletableFuture` thread for its 500 ms
      timeout, and ankka's workflow client refuses a call made off the step's own thread
      (`StepScope`: "a workflow calls another service in a step, not in a command handler"), so every
      deposit is `RG_UNAVAILABLE` within 6 ms and nothing reaches `responsible-gambling` (a stand-in
      answering `allowed` over mTLS received nothing); (2) the deposit flow ends `refused` on
      `RG_UNAVAILABLE`, which merchant retries as transient, so merchant's credit-wait loop re-reads a
      final refusal for ever. Found and fixed in the brands tool: grants were sent with a string
      target and the 0.12 control plane wants an object (`GrantJson`); a grant whose grantee project
      does not exist yet is a note, not a change; `psp-gateway` was still process-hosted from the
      TypeScript plan and was OOM-killed in the sidecar's 128Mi (`Render.ProcessHosted`). Also found,
      for ankka: the kind operator at 256Mi is OOM-killed with two projects; `pause_service` records
      Paused without scaling the deployment; the control plane's service status went stale while the
      operator was down. For spec 004: merchant's `KEYCLOAK_URL` is the public name on 8443, which a
      pod reaches on 443 only, so `operator-sync` times out in a cluster (make it a `setting:`).*
- [X] T073 Raise research's *Findings for other specs* where they belong: spec 007 reads
      `already_initiated` as initiated (finding 5) as a note in `specs/007-payouts/spec.md`'s
      Clarifications with contracts/payouts.md adopted; spec 016's TypeScript note (research R1) in
      `specs/016-partner-api/spec.md`; the Gigadat and Morphosis webhook decisions and R17 drafted as
      the message for Sheldon in `specs/005-psp-gateway/research.md` under *Open for Sheldon*; `just
      features` still green

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)**: none. T002 publishes the contract T001's build depends on; T004–T007 need only Go
  and the original's checkout. T006 after T004 and T005; T007 after T005 (the control set it
  drives).
- **Foundational (2)**: after Setup. Blocks every story. T008–T020 together; T021 and T022 together,
  then T023; T024 and T025 together (T025 needs T006's image); T027 after T020, T023–T025; T028 and
  T029 after T027; T030 after T020 and T027. T026 needs T006, T007, the original's kind stack and
  Docker, and gates every parity task (T036, T044, T048, T054, T063, T068).
- **US1 (3)**: after Foundational. The MVP. T031–T035 together; T037 after T009, T010–T018, T020,
  T023, T024; T038 after T037; T039 after T019, T024; T040 after T037–T039; T036 after T040 and
  T026; T041 beside anything after T040 (it changes merchant only).
- **US2 (4)**: after US1 (T045 extends T037 and T038). T042 and T043 together; T044 after T026.
- **US3 (5)**: after US1 (the deliverer, the webhook pipeline). T046 and T047 together; T049 beside
  them (merchant only); T050 after T046, T049 (the brand listing the fake mirrors) and T038; T048
  after T050 and T026.
- **US4 (6)**: after US1 (T055 completes T039). T051–T053 together; T055 after T051; T056 after T052
  and T055; T054 after T056 and T026. US3's payout outcomes gain their retries here.
- **US5 (7)**: after US1 for deposits, after US3 for the payout rows of `LeakSuite`. T057–T059
  together; T060 after them.
- **US6 (8)**: after US1 only. T061 and T062 together; T064 after them; T063 after T064 and T026.
- **Polish (9)**: after the stories wanted. T065 → T066; T067, T068, T069, T070 together; T071
  after US1–US5 and T041; T072 after T069 and T071; T073 last.

### Platform gates (plan.md Phase 0, research R5, R7, R12)

- The consumer handler's time budget (T027), merchant's credential-name case (T024), the nested
  public prefix (T030) and the payout terminal's coins property (T049) are each verified against
  the pinned SDK or merchant when the task is taken, and each has its fallback in the task. Nothing
  waits on an unreleased ankka spec; grants are the contract's named-caller list until 040.
- `contracts/merchant.md` is what 004's `GatewayClient` and `FakeGateway` already speak; T041
  changes merchant only where that contract gained `refused` and lost `terminalProperties`' values.
  `contracts/payouts.md` is what 007's plan adopts; `FakePayouts` is built to it so nothing here
  changes when `payouts` exists.

### Parallel Opportunities

- Phase 1: T002–T005 and T007 together after T001; T006 after T004 and T005.
- Phase 2: T008–T020 together (thirteen pure packages, one per pair of hands); T021 and T022
  together; T024 and T025 together; T026 the moment the original is up; T028–T030 together after
  T027.
- US1: T031–T035 together; T037 → T038 and T039 together → T040 → T036; T041 beside.
- US2, US3, US6 can run beside each other once US1 is done; US4 after US1 as well, and US3's
  outcome retries land with it.
- US5 after US3 for the payout rows; its three tests together.
- Polish: T065, T067, T068, T069, T070 together.

---

## Parallel Example: User Story 1

```bash
# tests first, together:
Task: "OutcomeDelivererSuite (US1 cases) in g-test/delivery/OutcomeDelivererSuite.scala"
Task: "InitiateSuite in g-test/it/InitiateSuite.scala"
Task: "WebhookSuite in g-test/it/WebhookSuite.scala"
Task: "SessionReadSuite and RepeatSuite in g-test/it/"
Task: "Bind providers.feature 9/15/21/27/32/39/45/51"
# then in order:
Task: "deposits/Initiate.scala"
  → "deposits/Webhooks.scala" + "delivery/OutcomeDeliverer.scala (Delivered path)"
  → "api/PspEndpoint.scala + api/WebhooksEndpoint.scala deposit routes"
  → parity (the deposit recordings, nine providers)
# beside, in services/merchant/:
Task: "DepositWorkflow sends terminalProperties: {}; GatewayClient reads refused"
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phases 1 and 2, with T026 started the moment `psp-stub` and the driver build and the original's
   stack is up (it is the longest task and needs nothing of this service).
2. Phase 3: nine providers initiate and capture on the kit; one capture per deposit at
   `FakeMerchant`; the US1 features green; the deposit recordings replay.
3. Stop and run quickstart §3, §4 and §5 for the US1 scenarios.

### Incremental delivery

1. US2, then US4: the short-payment rule and the undelivered outcome. After US4 every money-moving
   guarantee of the spec holds (SC-002, SC-003, SC-005).
2. US3: the payout surface and its contract with 007; after it `merchant` serves the brand listing
   and SC-001's four payout paths replay.
3. US5 and US6 in parallel by a second pair of hands (US6 needs only US1); after US5 SC-004 and
   SC-006 hold.
4. Polish, then quickstart §6 and §7 on kind before the manifest is applied anywhere real; 004's
   T081 closes with it.

---

## Notes

- Every parity task replays recordings that must exist (T026); do not write a recording by hand.
  The original's known gaps (R16) are recorded as they behave and declared, never corrected in the
  recording.
- No credential or customer field in any event, view row, log line, span or response: T021, T022,
  T058 and T060 hold it; `MerchantClient` is the only path to a credential, and it keeps none.
- A webhook handler verifies over the raw body before it parses, and cross-checks before it
  settles (T038); an unknown reference is acknowledged before either.
- The deliverer never throws (T031, T051); a receiver that is down must not stall another
  session's delivery.
- The features and the parity suite run on one kit; a scenario a neighbour owns (`deposits.feature`,
  `cashier.feature`, `features/payouts/`) is named, never passed.
- Commit after each task or logical group; stop at any checkpoint to validate the story alone.
