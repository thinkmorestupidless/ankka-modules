# Recording the original

How `tests/port/` is made (spec 001 FR-029 to FR-032; `specs/001-brands-and-projects/contracts/recording.md`).
Run only against a local original, never a shared or production one. Nothing here is applied to the
original's repository except in your own local checkout, and nothing is pushed there.

## What is here

| Path | What it is |
|---|---|
| `compose.override.yml` | the seven payment-provider stubs beside the original's stack, and the gateway pointed at them |
| `e2e-recording.patch` | adds `record_test.go` to the original's `tests/e2e`: with `EITHEROR_RECORD_TO` set, every request a test makes is recorded, and any to a host off this machine is refused |
| `e2e-rg-kyc.patch` | the responsible-gambling and KYC scenarios the original's suite lacks (FR-030); see `e2e-rg-kyc.md` |
| `recorder/` | the transport `record_test.go` installs |
| `merge/` | turns two runs into the committed recordings: `vary`, captures, `suite-result.json` |
| `preflight/` | refuses to start while any outside system the original calls has no stub answering |

## Steps

The two repositories are checked out side by side (`~/Documents/projects/eitheror/{eitheror-platform,ankka-casino}`).

```bash
cd ../eitheror-platform                                   # your local checkout of the original
git switch -c local/recording                             # never pushed
git apply ../ankka-casino/tests/original/e2e-recording.patch ../ankka-casino/tests/original/e2e-rg-kyc.patch
docker compose -f dev/compose/full-stack.yml -f ../ankka-casino/tests/original/compose.override.yml up -d
just dev-substrate & just dev-wallet-service & …         # the original's native services, as its README says
(cd tests/fake-st8 && go run .) & (cd tests/uf-stub && go run .) &

cd ../ankka-casino/tests && go run ./original/preflight -original ../../eitheror-platform   # must say every system is stubbed

cd ../../eitheror-platform/tests/e2e
for run in 1 2; do
  EITHEROR_SUBSTRATE_URL=http://localhost:9000 EITHEROR_RECORD_TO=/tmp/recording-$run \
  EITHEROR_ORIGINAL_COMMIT=$(git rev-parse --short HEAD) \
    go test -tags e2e -count=1 -json ./... > /tmp/recording-$run.json
done

cd ../../../ankka-casino/tests
go run ./original/merge -run1 /tmp/recording-1 -run2 /tmp/recording-2 \
  -results1 /tmp/recording-1.json -results2 /tmp/recording-2.json -out port
cd .. && just brands recordings check
```

Each run uses fresh ids because the suite mints them per run. A recording that did not repeat step for
step keeps `runs: 1`, and `recordings check` refuses it until the scenario is made repeatable.

## Recording the wallet directly

The suite reaches the wallet only through casino-service and the ST8 adapter, so its recordings
are those services'. `wallet-driver/` drives the original's `wallet-service` itself (spec 002
research R13): one scenario per scenario of `features/wallet/*.feature`, each named exactly as the
feature names it, plus the ST8 wallet shapes and `TestScenario22`'s resolver shapes.

- It calls the `/internal/v1/wallet/{brand}/{player}/{currency}/...` routes, the same handlers as
  the player-token `/v1/wallet/...` routes, signed as casino-service signs them:
  `X-Internal-Brand-Id: <brand>` and `X-Internal-Secret: hex(HMAC-SHA256(WALLET_INTERNAL_SECRET, brand))`.
  The secret is never recorded.
- Each scenario takes a fresh player and creates its player and wallet twins on substrate as
  casino-service does at registration (the original's deposit refuses `WALLET_NOT_FOUND`
  without them). Those calls are `setup:` steps; reads of the wallet twin the original has no
  route for (deposit turnover pending) are `observe:` steps. A replay skips both kinds.
- Requests carry placeholders, never a run's values: `{brand}`, `{player}`, `{walletTwin}`, an id
  the scenario minted (`{r1}`, `{p1}`, `{session}`, a grant's UUID by its award's name) and an id
  the original minted, captured from the answer that gave it (`{w1}` from `$.requestId`). The
  parity harness mints the first kinds and fills captures from the rebuild's answers.
- Amounts go in as the original takes them: major-unit decimal strings on deposit, bet, win,
  award and create-withdrawal-request; cents as numbers on the withdrawal lifecycle routes.

```bash
just dev-wallet-service                     # in the original: wallet-service on :8081, substrate on :8080
cd tests
for run in 1 2; do
  EITHEROR_RECORD_TO=/tmp/wallet-$run EITHEROR_ORIGINAL_COMMIT=$(git -C ../../eitheror-platform rev-parse --short HEAD) \
    go test -tags e2e -count=1 -json ./original/wallet-driver/... \
      -args -wallet-url http://localhost:8081 -substrate-url http://localhost:8080 -brand spinvibe > /tmp/wallet-$run.json
done
go run ./original/merge -run1 /tmp/wallet-1 -run2 /tmp/wallet-2 -results1 /tmp/wallet-1.json -results2 /tmp/wallet-2.json -out port
```

A scenario whose answer differs from what its feature expects fails in the run's result and is
still recorded: the recording is the original's behaviour, and the failure is a finding for the
spec. `go test ./original/wallet-driver/...` without the tag checks the driver against a stub.

## What a stub cannot do

Each stub's README says what it does not reproduce. The ones that change a recording:
- **DreamzPay** is reached by the player's browser, not the gateway; the suite posts its own signed
  webhook, so a recording never calls the stub. Set `DREAMZPAY_SECURE_KEY` to the stub's key.
- **Terminals' secrets**: T365, EuPaymentz and BounceMoney verify webhooks with secrets held on the
  terminal in the original's seed. Seed the local terminals with the stubs' defaults
  (`t365-stub-secret`, `sk-test`, `test-signing-secret-unit-only`).
- **Hosted pages** redirect to `PSP_GATEWAY_BASE_URL`, which inside compose is the gateway's service name.
- **Outbound calls** are in each stub's `GET /__received`; the recorder does not yet attach them to a
  scenario's `outbound` list.
