# Quickstart: proving merchant end to end

What to run, in the order the tasks build it, and what each step must show. Paths are from the
repository root; the service's own commands run in `services/merchant/`.

## Prerequisites

- JDK 21, sbt, Docker (the integration test kit starts Postgres), `uv` (`just features`), Go (the
  driver and the stubs).
- The `ankka@ankka` plugin installed (`/plugin install ankka@ankka`) for `ankka-port` and
  `ankka-inspect`; the `ankka` CLI on the path.
- The contract published locally with `Routes.paymentsOperator` and `money.Amounts`:
  `sbt contract/publishLocal` from the root; `just schemas` leaves no diff.
- For recording only: the original checked out beside this repository, as `tests/original/README.md`
  describes; its compose stack with the seven stubs for scenarios 12 and 13 and the driver; its KinD
  casino stack for scenario 14.

## 1. Recordings exist

```bash
cd ../eitheror-platform && git switch local/recording
git apply ../ankka-casino/tests/original/e2e-merchant.patch        # unskips 12's chain, corrects the routing-rules calls
export DREAMZPAY_MEMBER_ID=… DREAMZPAY_TOTYPE=… DREAMZPAY_SECURE_KEY=…   # the dreamzpay stub's README
# … the README's two runs of tests/e2e, scoped: -run 'TestScenario1[234]_'
cd ../ankka-casino/tests
for run in 1 2; do EITHEROR_RECORD_TO=/tmp/merchant-$run EITHEROR_ORIGINAL_COMMIT=$(git -C ../../eitheror-platform rev-parse --short HEAD) \
  go test -tags e2e -count=1 -json ./original/merchant-driver/... -args -merchant-url http://localhost:8085 > /tmp/merchant-$run.json; done
go run ./original/merge -run1 /tmp/merchant-1 -run2 /tmp/merchant-2 -results1 /tmp/merchant-1.json -results2 /tmp/merchant-2.json -out port
cd .. && just brands recordings check
ls tests/port/merchant/
```

Expected: the four Go scenarios and one file per driver scenario, each `runs: 2` and naming its
requirement (`004/FR-0xx`); `recordings check` refuses nothing. A driver scenario that fails on the
original is recorded as it fails and listed in the spec as a finding.

## 2. The contract additions

```bash
sbt contract/test                       # Routes.paymentsOperator: issuer, role, scope claims; Amounts by exponent
just schemas && git diff --exit-code contract/schemas
```

## 3. The service builds and its unit and entity suites pass

```bash
cd services/merchant
sbt compile "testOnly io.eitheror.casino.merchant.routing.* io.eitheror.casino.merchant.payments.VarianceSuite io.eitheror.casino.merchant.payments.StatusSuite io.eitheror.casino.merchant.security.* io.eitheror.casino.merchant.operators.*Suite io.eitheror.casino.merchant.terminals.CatalogueSuite"
sbt "testOnly io.eitheror.casino.merchant.hierarchy.* io.eitheror.casino.merchant.terminals.* io.eitheror.casino.merchant.cashier.* io.eitheror.casino.merchant.payments.* io.eitheror.casino.merchant.keys.*"
```

Expected: the evaluator's cases from `RoutingTreeRoutesSpec` (a cycle is no match; conditions
ignore case; `IN` splits on commas); the variance rules of `CashierSettlementVarianceSpec` (equal
is not over; four refusals); the HMAC window and header cases of `CashierCaptureAuthSpec`; the
scope and privilege cases of `TenantIsolationSpec` and `OperatorSurfaceActorTypeSpec`; a sub-brand
that returns an existing credential rather than duplicating it and creates its companion terminal; a
terminal that cannot be resurrected after deletion; a session that returns its first submit's answer;
a payment that records nothing after a terminal status and reads `EXPIRED` past its deadline.

## 4. The deposit workflow against the fakes

```bash
sbt "testOnly io.eitheror.casino.merchant.it.DepositWorkflowSuite io.eitheror.casino.merchant.it.LeakSuite io.eitheror.casino.merchant.it.RepeatSuite"
```

Expected: a capture with the fake wallet stopped is answered `503 OUTCOME_PENDING`, and credited
once within the backoff of the wallet's return (SC-002 at the kit's clock); a capture whose wallet
answer the fake forgot is confirmed again under the same payment id and credited once; a final
refusal stops the retries and lists the payment as uncredited, and an operator's retry credits it; a
deadline with the fake gateway answering `undelivered` waits again and says so; 1,000 captures with
one in ten first answered `OUTCOME_PENDING` leave zero expired that were captured (SC-003); ten
deliveries of one capture credit once (SC-004); a capture above 2^31 minor units credits exactly.

## 5. The features bind and pass

```bash
sbt "testOnly *Features"                # GherkinSuite over features/payments/{deposits,hierarchy,operators,summary}.feature and this spec's cashier scenarios
```

Expected: every scenario the spec names green; the scenarios of `cashier.feature` that are spec
006's are not bound here and the suite says so.

## 6. Parity

```bash
sbt "testOnly io.eitheror.casino.merchant.port.ParitySuite"
```

Expected: one test per file in `tests/port/merchant/`, each green or failing with the recording's
path and the first differing JSON path; the divergences of plan.md named in their tests (the
credential `activate` step's query form; the missing-secret `401` replayed as `403`; the sweep as
the clock); the four Go scenarios replayed with the other services' steps stood in for as
`contracts/recording.md` says.

## 7. A local run

The service on this machine, with `psp-gateway` and the brand's wallet as the suites fake them
(`FakesMain`, fixed ports) and operator tokens from the `dev-kind` installation's casino Keycloak,
reached through a port-forward so the service reads its keys over plain HTTP. Tokens still carry the
public issuer.

```bash
# the casino Keycloak (installation/overlays/dev-kind applied), with a kind-only kubeconfig
kubectl -n casino-identity port-forward svc/casino-keycloak 18180:80 &
# the eitheror realm from brands/_realm/eitheror-operators.json (the brands tool imports it on a brand's
# apply); an operator with platform_admin and eitheror_scope_level=platform, eitheror_scope_id=*

cd services/merchant && sbt schema && docker compose up -d            # Postgres
sbt "Test/runMain io.eitheror.casino.merchant.it.FakesMain" &         # psp-gateway :18301, wallet :18302
MERCHANT_BRANDS='[{"slug":"goldvibe","name":"Goldvibe"}]' CASINO_PROJECT=payments \
ANKKA_SECRET_KEY=<base64 of 32 random bytes, kept: the secret store is encrypted under it> \
ANKKA_AUTH_ISSUERS=staff ANKKA_AUTH_REALM=eitheror ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino \
ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
ANKKA_AUTH_STAFF_JWKS_URL=http://localhost:18180/realms/eitheror/protocol/openid-connect/certs \
KEYCLOAK_URL=http://localhost:18180 KEYCLOAK_ADMIN_USERNAME=admin KEYCLOAK_ADMIN_PASSWORD=admin \
sbt -Dankka.local-services.psp-gateway=http://127.0.0.1:18301 -Dankka.local-services.wallet=http://127.0.0.1:18302 run
curl -s localhost:9000/status                                         # {"uncreditedCaptures":0,"bound":"15m"}
```

With no `ANKKA_KAFKA_BOOTSTRAP_SERVERS` the facts are published to memory. Locally every caller is
`Caller.Local`, so the `ingest` and `psp-gateway` routes answer a plain `curl`; an operator route
without a token is `401`, and with a token naming no `eitheror_scope_level` `403 scope_violation`. On the
seeded sub-brand (`UUID.nameUUIDFromBytes("seed.sub-brand.goldvibe")`), as the operator: a DREAMZPAY
integration, its activation, a tree naming it and its deploy; then a session lists the terminal, a submit
answers `AWAITING_REDIRECT` (one initiate at the fake gateway, however often it is repeated), and a
capture answers `{"ok":true}` with one deposit at the fake wallet and the payment `captured` and
`credited`. `POST /v1/operators` creates the user in the real realm with its scope attributes and
`VERIFY_EMAIL`; with no transactional email configured the invitation is logged as not sent.

## 8. An installation

```bash
cd services/merchant && DOCKER_REPOSITORY=europe-west1-docker.pkg.dev/eitheror/casino SERVICE_VERSION=0.1.0 \
  sbt Docker/publishLocal && kind load docker-image europe-west1-docker.pkg.dev/eitheror/casino/merchant:0.1.0 --name ankka
ankka login                                           # the brands tool uses the CLI's login
just brands validate shared/payments/manifest.json --installation dev-kind
just brands apply shared/payments/manifest.json --installation dev-kind --secrets <file>
ankka services get merchant --project payments        # Ready; exposed (operator routes answer a staff token, identity routes refuse one)
```

The `--secrets` file holds `keycloak-operators-admin/USERNAME` and `/PASSWORD` (the casino Keycloak's
administrator); a pod naming a missing project secret does not start. The invitation provider's key is
not required: without it an operator is created and the invitation is logged as not sent. `psp-gateway` and `ingest` are applied with the project and stay unpulled until
specs 005 and 006 build them. `/status` admits the project's services only; from outside, the same
figure is `GET /v1/payments/cashier/uncredited` as an operator. A second apply changes nothing, and the
hierarchy is seeded once.

With the `goldvibe` project's wallet deployed (spec 002) and a gateway in the cluster (spec 005), a
session submitted makes `payment.initiated` on the brand's `payments.payments`; a capture through the
real gateway is spec 005's smoke.