# Recording the original

How `tests/port/` is made (spec 001 FR-029 to FR-032; `specs/001-brands-and-projects/contracts/recording.md`).
Run only against a local original, never a shared or production one. Nothing here is applied to the
original's repository except in your own local checkout, and nothing is pushed there.

## What is here

| Path | What it is |
|---|---|
| `compose.override.yml` | `psp-stub` (the nine payment-provider stubs) beside the original's stack, and the gateway pointed at it |
| `e2e-recording.patch` | adds `record_test.go` to the original's `tests/e2e`: with `EITHEROR_RECORD_TO` set, every request a test makes is recorded, and any to a host off this machine is refused |
| `e2e-rg-kyc.patch` | the responsible-gambling and KYC scenarios the original's suite lacks (FR-030); see `e2e-rg-kyc.md` |
| `e2e-merchant.patch` | unskips `TestScenario12_OnboardingGate3_FullChain`; corrects scenario 12's and 13's routing-rules calls to the route as served (spec 004 research R6); registers scenarios 12 to 14's DreamzPay terminal as a gateway integration carrying its credentials, as the original has needed since `2add2353`; clears a new casino player's `VERIFY_EMAIL` in scenario 14; and corrects two stale assertions (`memberId`, the wallet's minor units). See `tests/port/README.md` |
| `merchant-driver/` | drives the original's `merchant-service` cashier for the behaviours the suite never reaches (spec 004); see *Recording the cashier directly* |
| `ingest-driver/` | drives the original's cashier edge, the routes spec 006's `ingest` takes over, for what the suite never sends; see *Recording the cashier edge* |
| `players-driver/` | drives the original's `casino-service` player, session and backoffice routes and its Keycloak for what spec 008's `players` takes over; see *Recording the players* |
| `payouts-driver/` | drives the original's `casino-service` withdrawal routes (the request, the terminals, the destinations, the queue, the decisions and the internal outcome route) for what spec 007's `payouts` takes over (scenario 30); see *Recording the payouts* |
| `gateway-driver/` | drives the original's `psp-gateway` against `psp-stub`, with a receiver standing in for merchant-service and casino-service (spec 005); see *Recording the gateway directly* |
| `recorder/` | the transport `record_test.go` installs |
| `merge/` | turns two runs into the committed recordings: `vary`, captures, `suite-result.json`; collapses a poll's repeated reads to the last, and redacts passwords and tokens from bodies and `X-Internal-Secret` from headers |
| `preflight/` | refuses to start while any outside system the original calls has no stub answering: psp-stub's nine ports, the game provider and, given `-receiver`, the gateway driver's receiver; given `-cashier`, also that the original's cashier route answers |

## Steps

The two repositories are checked out side by side (`~/Documents/projects/eitheror/{eitheror-platform,ankka-casino}`).

```bash
cd ../eitheror-platform                                   # your local checkout of the original
git switch -c local/recording                             # never pushed
git apply ../ankka-casino/tests/original/e2e-recording.patch ../ankka-casino/tests/original/e2e-rg-kyc.patch \
  ../ankka-casino/tests/original/e2e-merchant.patch
docker compose -f dev/compose/full-stack.yml -f ../ankka-casino/tests/original/compose.override.yml up -d
just dev-substrate & just dev-wallet-service & …         # the original's native services, as its README says
(cd tests/fake-st8 && go run .) &                         # the payment providers are psp-stub, in the compose override

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
cd .. && just modules recordings check
```

For the casino scenarios the original's KinD stack is the one that runs (`just dev-kind`,
`just kind-install-wallet`, `just kind-install-casino`): its compose file has no substrate.
`tests/port/README.md` records how spec 003's recordings were made on it.

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

## Recording the cashier directly

The suite reaches merchant-service's cashier only on its happy path (scenarios 12 to 14), so the
failure, expiry, abandon, signature and variance behaviours would rest on its unit suites, which mock
the substrate. `merchant-driver/` drives the cashier itself (spec 004 research R6): one scenario per
scenario of `features/payments/deposits.feature` and the cashier scenario spec 004 owns, each named
exactly as the feature names it.

- Each scenario seeds a fresh brand through the original's `DevSeedRoutes`
  (`/internal/v1/seed/hierarchy/{brand}`, then `/internal/v1/seed/merchants/{brand}`: a merchant key,
  a catch-all tree to `SIM` and the `SIMULATOR` credential the gateway's stub serves). No Keycloak
  token is needed. These are `setup:` steps a replay skips.
- The capture, fail, abandon and sweep routes take `X-Internal-Secret`
  (`MERCHANT_SERVICE_INTERNAL_SECRET`, `changeme` in dev; on the kind cluster it is in the
  `merchant-service-config` config map, read with the kind-only kubeconfig); it is never recorded. The replay sends
  those steps as `psp-gateway`'s identity.
- A webhook secret on the credential and the gateway's undelivered marker are written to substrate
  as merchant-service and psp-gateway write them (`eitheror-psp-credential`, `psp-checkout-session`).
- The deadline is the original's sweep with `olderThanMinutes=0`, named `time: …`. The sweep lists
  payments through substrate's namespace listing, which lags its writes: a sweep sent straight after
  the submit missed the payment and recorded it unexpired (found 2026-10-18). `Listed` waits for the
  listing, filtered to the sub-brand, as an `observe:` step (only the answer that held is recorded),
  and the read after the sweep is checked for its status (`WantField`), not only its code. The replay
  makes a sweeping recording's payments under a 3-second deadline and waits for it. "A signed capture arriving while the secret cannot be read" is not driven: it
  needs the substrate down mid-request, and `CashierCaptureAuthSpec`'s case is ported instead.

```bash
cd tests
for run in 1 2; do
  EITHEROR_RECORD_TO=/tmp/merchant-$run EITHEROR_ORIGINAL_COMMIT=$(git -C ../../eitheror-platform rev-parse --short HEAD) \
    go test -tags e2e -count=1 -json ./original/merchant-driver/... \
      -args -merchant-url http://localhost:8085 -substrate-url http://localhost:8080 > /tmp/merchant-$run.json
done
go run ./original/merge -run1 /tmp/merchant-1 -run2 /tmp/merchant-2 -results1 /tmp/merchant-1.json -results2 /tmp/merchant-2.json -out port
```

## Recording the cashier edge

`ingest-driver/` drives merchant-service's public cashier routes as a sub-brand's server would (spec 006;
`specs/006-ingest/contracts/recording.md`): one scenario per scenario and outline example of
`features/payments/cashier.feature` the original can show, named as the feature names it with the example
in brackets, and the header-case edge case. `NotDriven` says why the rest are not; `go test` checks every
name against the feature.

- It reuses merchant-driver's `Session` with `Service: "ingest"`, so recordings land under `ingest/`. Each
  scenario seeds its own sub-brand (and a second one where it needs another sub-brand's key) through the
  seed routes; the raw key is captured from the seed's answer.
- The key header is recorded as sent: `{merchantKey}` for the seeded key, `{otherMerchantKey}` for the
  second sub-brand's, empty when none was sent (`NoKey`), the literal made-up key otherwise. Extra headers
  go on the wire spelt as given, so `MerchantKey` is sent as `MerchantKey`.
- What the original has no route for is written to substrate as merchant-service writes it: a key
  disabled (the `eitheror-merchant-keys` twin with `active: false`), a tree with no deployed version or one
  that matches nothing (`eitheror-merchant-routing`). The key's hash, id and creation time are templated.

On 2026-10-24 the original's KinD cluster was gone and the disk had no room to rebuild it, so the run used
the original natively: Postgres, Neo4j and NATS in Docker; substrate, merchant-service and psp-gateway from a
worktree at the pinned commit (`tests/port/ingest/README.md` has every setting).

```bash
cd tests
go run ./original/preflight -original <the original's checkout> -cashier http://localhost:8085
for run in 1 2; do
  EITHEROR_RECORD_TO=/tmp/ingest-$run EITHEROR_ORIGINAL_COMMIT=$(git -C <the original's checkout> rev-parse --short HEAD) \
    go test -tags e2e -count=1 -json ./original/ingest-driver/... -run TestCashier \
      -args -merchant-url http://localhost:8085 -substrate-url http://localhost:8080 > /tmp/ingest-$run.json
done
go run ./original/merge -run1 /tmp/ingest-1 -run2 /tmp/ingest-2 -results1 /tmp/ingest-1.json -results2 /tmp/ingest-2.json -out port
```

## Recording the gateway directly

The suite reaches psp-gateway only through DreamzPay (scenarios 13 and 14, already recorded under
`tests/port/merchant/`), so the other providers, the payouts and the outcome rules would rest on its
unit suites, which mock the providers. `gateway-driver/` drives the gateway itself (spec 005 research
R16; `specs/005-psp-gateway/contracts/recording.md`): one scenario per scenario of
`features/payments/{providers,crypto,withdrawal-payments,outcomes}.feature`, per provider where the
feature is per provider, each named exactly as the feature names it with the provider as a suffix
(`…/cozeni`) and an outline's example in brackets. `scenarios.go` lists them; `go test` checks every
name against the features and says which feature scenario is not driven and why.

- **psp-stub is every provider.** Each scenario sets its provider's control to `pending` first, so no
  webhook reaches the gateway before the scenario asks psp-stub for one (`POST /__webhook`); the step's
  answer is the gateway's. A webhook the stub cannot make (a bad signature, a tampered amount, an
  unknown reference, a crypto amount missing or unreadable) the driver writes itself, signed with the
  stub's key or another, named `webhook (forged): …`. DreamzPay is reached by the player's browser, so the
  driver plays it: the gateway's relay page, then its form posted to the stub.
- **The receiver** (`gateway-driver/receiver`) stands in for merchant-service and casino-service:
  point the gateway's `PSP_GATEWAY_MERCHANT_SERVICE_URL` and `CASINO_SERVICE_URL` at it. It serves the
  terminal properties the gateway reads when a webhook arrives (each scenario's terminal carries the
  stub's credentials, as `terminalProperties` on the initiation does), logs every outcome call (path,
  body, headers without `X-Internal-Secret`), and answers as the scenario scripts: `200`, `503`, `400`,
  or nothing.
- **Steps.** psp-stub's `/__control` and the receiver's arrangement are `setup:`; psp-stub's
  `/__received` (the provider call) and the receiver's log (the outcome call) are `observe:`, cut to
  what the scenario caused, with form bodies as their fields, ids templated (`{payment}`,
  `{reference}`, `{request}`) and timestamps, salts, tokens and signatures marked `vary`; the re-drive
  (`POST /internal/v1/psp/redrive-undelivered`, the secret never recorded) is `time: …`. Credentials
  in a body or a header are recorded as `<redacted>`.
- **Settings the gateway needs** beyond the compose override's base URLs: `PSP_GATEWAY_BASE_URL` its
  own address as psp-stub reaches it (`PSP_STUB_GATEWAY_URL` on psp-stub likewise), and
  `MERCHANT_SERVICE_INTERNAL_SECRET` known to the driver (`-secret`). On the kind stack the receiver's
  address is the host's on the kind network (`172.18.0.1` on Linux, `host.docker.internal` on macOS).

```bash
cd tests
go run ./original/gateway-driver/receiver -addr :18110 &          # both runs share it
go run ./original/preflight -original ../../eitheror-platform -receiver http://localhost:18110/healthz
for run in 1 2; do
  EITHEROR_RECORD_TO=/tmp/gateway-$run EITHEROR_ORIGINAL_COMMIT=$(git -C ../../eitheror-platform rev-parse --short HEAD) \
    go test -tags e2e -count=1 -timeout 60m -json ./original/gateway-driver/... \
      -args -gateway-url http://localhost:8087 -receiver-url http://host.docker.internal:18110 > /tmp/gateway-$run.json
done
go run ./original/merge -run1 /tmp/gateway-1 -run2 /tmp/gateway-2 -results1 /tmp/gateway-1.json -results2 /tmp/gateway-2.json -out port
```

The outcome scenarios wait out the original's budgets (10 seconds an attempt, 15 a delivery), so a
run takes some minutes. A scenario the original answers otherwise than its feature expects fails in
the run's result and is still recorded. `go test ./original/gateway-driver/...` without the tag
checks the driver, the receiver and every forged signature against fakes.

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

## Recording the players

`players-driver/` drives the original's `casino-service` as the player site, the backoffice and the
game provider do (spec 008; `specs/008-players/research.md` R1): registration, sign-in and sessions,
the profile, the status actions, duplicates. One scenario per scenario of `features/players/*.feature`
the original can show; `NotDriven` says why the rest are not, and `go test ./original/players-driver/...`
checks every name against the features offline.

Its passwords come from the environment, never flags: `EITHEROR_KEYCLOAK_ADMIN_PASSWORD`,
`EITHEROR_OPERATOR_PASSWORD`, `EITHEROR_OUTSIDER_PASSWORD`. Creating the operator and outsider users
in the original's `eitheror` realm, and fetching their tokens, is done by hand.

```bash
cd tests
go run ./original/preflight -original <the original's checkout> -register http://localhost:8091 -brand spinvibe
for run in 1 2; do
  EITHEROR_RECORD_TO=/tmp/players-$run EITHEROR_ORIGINAL_COMMIT=$(git -C <the original's checkout> rev-parse --short HEAD) \
    go test -tags e2e -count=1 -json ./original/players-driver/... \
      -args -casino-url http://localhost:8091 -keycloak-url http://localhost:8180 -substrate-url http://localhost:9000 \
      -brand spinvibe -operator-client casino-backoffice -operator-user dev-operator \
      -outsider-user <an operator of no brand> > /tmp/players-$run.json
done
go run ./original/merge -run1 /tmp/players-1 -run2 /tmp/players-2 -results1 /tmp/players-1.json -results2 /tmp/players-2.json -out port
```

The ports are the original's KinD stack's: casino-service's node port is 8091, Keycloak's 8180 (8080 is
hugin), and the substrate's twin API is reached through
`kubectl --context kind-eitheror-dev port-forward -n eitheror svc/eitheror-bundle-substrate 9000:9000`.
`dev-operator`'s password is the one the realm-sync job logs. The run of 2026-10-26 (T029) and how it
differed from these steps are in `tests/port/players/README.md`.

## Recording the KYC scenarios

Scenarios 28 and 29 (`e2e-rg-kyc.patch`; spec 009 research R2) run from the original's own suite, on
its KinD stack with the casino pack's MinIO and its `casino-kyc` bucket:

```bash
cd <the original's checkout>/tests/e2e
for run in 1 2; do
  EITHEROR_RECORD_TO=/tmp/kyc-$run EITHEROR_ORIGINAL_COMMIT=$(git rev-parse --short HEAD) \
  EITHEROR_CASINO_URL=http://localhost:8091 EITHEROR_KEYCLOAK_URL=http://localhost:8180 \
  EITHEROR_SUBSTRATE_URL=http://localhost:9000 TEST_OPERATOR_EMAIL=dev-operator TEST_OPERATOR_PASSWORD=… \
    go test -tags e2e -count=1 -json -run 'TestScenario2[89]_' ./... > /tmp/kyc-$run.json
done
```

The merge files each recording by the original's service it reached first (the casino's routes, or the
realm's token step), so the recordings are moved to `tests/port/kyc/` afterwards. Before the run,
`casino/kyc-status-rollup` must be registered as the casino chart's registration job registers it
(`IS_A player.registered`): the original's `kind-register-casino-functions` recipe registers it again
with `IS_A Player`, a concept no twin carries, and the rollup then never runs. The run of 2026-10-31
(T047) is in `tests/port/kyc/README.md`.

## Recording the payouts

`payouts-driver/` (scenario 30; spec 007 research R2) drives the original's `casino-service` withdrawal
routes as the player site, the backoffice and psp-gateway do: the request and its gates, the terminal
listing, the destinations, the queue and its filters, approve, reject, confirm and mark failed, and the
internal outcome route. One scenario per scenario of `features/payouts/*.feature` the original can show;
`NotDriven` says why the rest are not, `Marked` names the recordings of a declared divergence (plan.md,
*Divergences*), which record the original and assert only what the port shares, and
`go test ./original/payouts-driver/...` checks every name against the features offline.

- Each scenario takes fresh players, registered, verified by the link the original would send, and
  funded through wallet-service's internal deposit route (`setup:`; its internal API, port 9001, which the public port answers 404). Its terminals are written to
  substrate under UUID ids and appended to the brand's terminal index, as merchant-service and scenario
  251 write them, made inactive and taken out of the index when the scenario ends (casino-service reads every terminal the index names at once, and past 64 its connection pool refuses them all as ineligible; scenario 251 leaves its own in the index, so clear them between runs). A brand's limits set by a scenario are put
  back when it ends.
- Identity status (`kyc_status`), deposit turnover and a payout's `NEEDS_REVIEW` are written where the
  KYC rollup, the wallet and the reconciliation sweeper write them; psp-stub's Unity Finance is held
  `pending` before an automated approval, so the scenario, not a webhook, settles the payout.
- The run's tag is in every address it mints, and the queue's scenarios filter by it (`playerSearch`).
  The original's queue reads at most 500 requests across every brand (research R1), so a stack with many
  old requests can hide a run's own; clear old ones, or read the recording before trusting it.

Its secrets and passwords come from the environment, never flags or files: `TEST_OPERATOR_EMAIL` and
`TEST_OPERATOR_PASSWORD` (an operator holding `brand_admin` at the brand: the limits' route needs it),
`EITHEROR_OUTSIDER_USER` and `EITHEROR_OUTSIDER_PASSWORD` (an operator of no brand), and
`WALLET_SERVICE_INTERNAL_SECRET`, the original's internal secret, read from the recording worktree's
environment (on the kind stack, casino-service's config). The outcome route takes it as it is in
`X-Internal-Secret` and wallet-service's internal routes its HMAC of the brand; the driver records
neither header, and the merge redacts `X-Internal-Secret` if a recording ever holds one.

Scenario 251 (`scenario251_withdrawal_cap_lifecycle_e2e_test.go`, four tests) runs beside the driver
from the original's own suite, unchanged, twice on fresh ids, and its recordings are merged the same way:

```bash
cd tests
for run in 1 2; do
  EITHEROR_RECORD_TO=/tmp/payouts-$run EITHEROR_ORIGINAL_COMMIT=$(git -C <the original's checkout> rev-parse --short HEAD) \
    go test -tags e2e -count=1 -timeout 60m -json ./original/payouts-driver/... \
      -args -casino-url http://localhost:8091 -keycloak-url http://localhost:8180 -substrate-url http://localhost:9000 \
      -wallet-url <wallet-service> -wallet-internal-url <its internal API> -stub-url <psp-stub's Unity Finance> -brand spinvibe \
      -operator-client casino-backoffice > /tmp/payouts-$run.json
done
go run ./original/merge -run1 /tmp/payouts-1 -run2 /tmp/payouts-2 -results1 /tmp/payouts-1.json -results2 /tmp/payouts-2.json -out port

cd <the original's checkout>/tests/e2e
for run in 1 2; do
  EITHEROR_RECORD_TO=/tmp/s251-$run EITHEROR_ORIGINAL_COMMIT=$(git rev-parse --short HEAD) \
  EITHEROR_CASINO_URL=http://localhost:8091 EITHEROR_KEYCLOAK_URL=http://localhost:8180 \
  EITHEROR_SUBSTRATE_URL=http://localhost:9000 EITHEROR_WALLET_URL=<wallet-service> \
  EITHEROR_WALLET_INTERNAL_URL=<its internal API> EITHEROR_WALLET_INTERNAL_SECRET=… \
  TEST_OPERATOR_EMAIL=dev-operator TEST_OPERATOR_PASSWORD=… \
    go test -tags e2e -count=1 -json -run 'TestScenario251_' ./... > /tmp/s251-$run.json
done
cd <this repository>/tests
go run ./original/merge -run1 /tmp/s251-1 -run2 /tmp/s251-2 -results1 /tmp/s251-1.json -results2 /tmp/s251-2.json -out port
```

psp-gateway must be pointed at psp-stub (`compose.override.yml`'s settings) and read terminals from
merchant-service, which serves the terminal's credentials the driver writes. The merge files scenario
251 by the original's service it reached first (casino-service's routes, so `players/`), and its
recordings are moved to `tests/port/payouts/` afterwards, as the KYC scenarios' were. Any gate the driver
could not reach is listed, with its reason, in `tests/port/payouts/README.md`.
