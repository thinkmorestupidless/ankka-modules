# Quickstart: proving psp-gateway

In build order. Each section says what must hold. The routes are [contracts/routes.md](contracts/routes.md),
and the components are [data-model.md](data-model.md).

## 1. The stubs

```bash
cd tests && go test ./stubs/... && go build -o stubs/bin/ ./stubs/psp-stub
./stubs/bin/psp-stub &                         # 18101–18109, one provider each
curl -s localhost:18108/healthz                # {"stub":"unityfinance","status":"ok"}
curl -s -XPOST localhost:18109/__control -d '{"outcome":"failure","code":"DECLINED"}'
```

What holds: every provider answers `/healthz`. The Unity Finance stub answers the live shapes
(`status:"ok"`, `data.withdraw.id`, the status array, `state` by name). `/__received` lists what was sent.

## 2. The recordings (needs the original's KinD stack; [contracts/recording.md](contracts/recording.md))

```bash
cd tests && go run ./original/preflight -original ../../eitheror-platform
go run ./original/gateway-driver -gateway-url http://localhost:8087 -out /tmp/gw-1   # and again to /tmp/gw-2
go run ./original/merge -run1 /tmp/gw-1 -run2 /tmp/gw-2 … -out port
cd .. && just brands recordings check
```

What holds: `tests/port/psp-gateway/` has one recording per scenario of the four features, per provider,
each `runs: 2`. `recordings check` finds 0 problems.

## 3. Unit suites

```bash
just service psp-gateway "testOnly io.eitheror.casino.pspgateway.providers.* io.eitheror.casino.pspgateway.money.* io.eitheror.casino.pspgateway.delivery.*"
```

What holds: each provider's signature, verification and status-map cases from the original's `_test.go`
files pass (research R9's last column). This includes the known vectors (DreamzPay MD5, Unity Finance
`ik_sign`, T365's padded key, BounceMoney's Ed25519, Digitarium's encodings) and `Underpaid`'s rational
cases.

## 4. Entity and consumer kits

```bash
just service psp-gateway "testOnly *SessionSuite *ReferenceIndexSuite *OutcomeDelivererSuite"
```

What holds:

- **Sessions:**
  - a second `claim` persists nothing;
  - `InitiationFailed` refuses `settle`;
  - two `settle`s persist one `Settled`;
  - `Reversed` follows `Completed` and nothing else follows a final outcome.
- **The deliverer:** with a scripted receiver,
  - `2xx` → `Delivered`; `400` → `Refused`, after one attempt;
  - `503` × 4 → `Undelivered` within 15 s;
  - silence → `Undelivered` after 10 s per call, within the budget.

## 5. Integration, features and parity (Docker)

```bash
just psp-gateway-it            # AnkkaTestKit over Postgres, FakeMerchant, FakePayouts, psp-stub in a container
just features                  # 0 findings
```

What holds:

- `GherkinSuite` passes every scenario of `providers`, `crypto`, `withdrawal-payments` and `outcomes`.
- `ParitySuite` replays every `tests/port/psp-gateway/*.json`, with only the declared divergences
  differing.
- The SC suites:

  | Suite | What holds |
  |---|---|
  | `RepeatSuite` (SC-002) | five deliveries of each webhook, one capture at `FakeMerchant` |
  | `RedriveSuite` (SC-003) | `FakeMerchant` down past the budget; every outcome listed undelivered; the clock advanced 2 minutes after recovery; each delivered once |
  | `LeakSuite` (SC-004) | none of `psp-stub`'s configured credentials in the journal, the view tables, the captured logs or any response body |
  | `RotationSuite` (SC-006) | rotate at `FakeMerchant` between two initiations; the second provider call carries the new value; two reads logged |
  | `ShortSuite` (SC-005) | a Morphosis confirmation one unit short fails; an unvalued Cozeni underpayment fails; a valued one is captured with `UNDERPAYMENT` |

## 6. Locally, against `merchant` (spec 004's §7 run)

```bash
./tests/stubs/bin/psp-stub &
cd services/psp-gateway && sbt schema && docker compose up -d
PUBLIC_URL=http://localhost:9001 DREAMZPAY_BASE_URL=http://localhost:18103 … \
  sbt -Dankka.local-services.merchant=http://localhost:9000 "run"        # the gateway on 9001
```

Run `merchant` on 9000 with `-Dankka.local-services.psp-gateway=http://localhost:9001` and, since a
capture credits the brand's wallet, merchant's `FakesMain` with `-Dankka.local-services.wallet=http://127.0.0.1:18302`
(spec 004 quickstart §7 has the rest of merchant's environment). Register the DreamzPay credential with
the stub's `secureKey` (`dreamzpay-test-key`) and the Cozeni one with `scheme: CRYPTO`: a credential without
a scheme is named after its provider, as the original did, and merchant then sends no crypto currency
(run 2026-10-21). Then, through ankka-inspect:

1. Open a session as `ingest` and submit a DreamzPay deposit.
2. Drive `psp-stub` to `success` and `POST /__webhook`.
3. Check:
   - the payment is captured at `merchant`;
   - `GET /v1/psp/sessions/{paymentId}` reads `resolved`, `captured`, `delivered: true`;
   - stopping and starting the gateway leaves it so.

## 7. An installation

```bash
just brands apply shared/payments/manifest.json      # psp-gateway image, its env (base URLs → psp-stub on kind)
```

What holds:

- `psp-gateway` is Ready and exposed.
- A webhook to `https://psp-gateway-payments.<host>/v1/webhooks/dreamzpay` from outside is answered.
- `GET /v1/psp/sessions/x` with a staff token is `403`.
- T081's last clause of spec 004: a deposit initiated from inside the cluster reaches `payment.initiated`
  on the brand's `payments.payments`.
