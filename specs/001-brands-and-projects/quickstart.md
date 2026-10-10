# Quickstart: proving Brands and Projects end to end

Each section is one of the spec's independent tests, in the order the tasks build them. Commands
run from the repository root. Expected outcomes are the acceptance scenarios in
`features/brands/*.feature` and `features/wallet/money.feature`.

## Prerequisites

- `ankka` CLI (`brew install thinkmorestupidless/tap/ankka`), the `ankka@ankka` plugin
  (`/plugin install ankka@ankka`), Docker, kind, `sbt`, Go 1.22+, `uv`.
- A local ankka installation from `installation/overlays/dev-kind` (the `ankka-platform` skill's
  kind path), with the casino's Keycloak from `installation/base/keycloak`.
- ankka at the pinned version; for grants, personal data and retention, a local publish of the
  `040-`, `042-` and `043-…-impl` branches (research R5, R7, R3). Until then those sections report
  "platform lacks" and the rest still passes.
- `just features` green: `0 findings in 1017 scenarios and 20 specs`.

## 1. The contract compiles and its schemas match (US2, US5)

```bash
sbt contract/test
```

Expect: every family round-trips through its serializer; `contract/schemas/<topic>.v1.json` equals
what `sbt contract/schemas` generates (the build fails on drift); `Money` refuses `"10.50"` naming
the amount and accepts `1050`; a money command without a key is refused; the `GherkinSuite` binds
every scenario of `features/wallet/money.feature` and `features/brands/facts.feature`.

## 2. A manifest is validated before anything is created (US1, US7)

```bash
sbt "brands/run validate brands/goldvibe/manifest.json"
sbt "brands/run validate tests/fixtures/manifests/bad-slug.json"        # slug "payments"
sbt "brands/run validate tests/fixtures/manifests/unknown-topic.json"   # a topic not in the contract
sbt "brands/run validate tests/fixtures/manifests/real-money.json"      # realMoney on dev-kind
```

Expect: the first passes; the others are refused naming the rule (`slug reserved`, `topic not in
contract`, and for real money the first unmet condition with its source), with no control-plane
call made (the tool prints `calls: 0`).

## 3. A brand is created from its manifest (US1)

```bash
sbt "brands/run apply brands/goldvibe/manifest.json --installation dev-kind"
sbt "brands/run status goldvibe"
```

Expect, within 15 minutes (SC-001): project `goldvibe`; ten topics `provisioned` (the first apply
also answers R3: a dotted name accepted or refused); every service `Ready` at the manifest's image
tag; exposed services reachable at `<service>-goldvibe.<base>`; realm `casino-goldvibe` present and
role `brand_goldvibe` in `eitheror`; grants listed with their effect (or `platform lacks 040`).
Re-run the apply: `changes: 0`.

## 4. Two brands do not touch (US1)

```bash
cp -r brands/goldvibe brands/testbrand && sed -i '' 's/goldvibe/testbrand/g' brands/testbrand/manifest.json
sbt "brands/run apply brands/testbrand/manifest.json --installation dev-kind"
# change one service's tag in brands/testbrand/manifest.json, then:
sbt "brands/run apply brands/testbrand/manifest.json --installation dev-kind"
sbt "brands/run status goldvibe"
```

Expect: `testbrand` created in the same time; only `testbrand`'s service shows a new generation;
`goldvibe`'s generations are unchanged (`ankka services history <svc> -p goldvibe`). Delete
`testbrand` afterwards and confirm a second create of the same slug is refused (tombstone).

## 5. The right person reaches the right route (US3)

```bash
sbt "brands/integration/testOnly *AccessSuite"
```

With a `casino-goldvibe` player token, a `casino-testbrand` player token, an `eitheror` operator
token with and without `brand_goldvibe`, an affiliate token and a service identity, call one route
of each class on the sample service the suite deploys. Expect the admissions and refusals of
`contracts/routes.md`, and `features/brands/access.feature` green in the `GherkinSuite`.

## 6. Personal data is marked and erased (US4) — needs 042

```bash
sbt "brands/integration/testOnly *ErasureSuite"
```

Expect: a sample message with a `Personal` field shows the subject `player/<id>` in clear and no
value; `ankka projects erasures request player/<id>` with a hold is `held`, then applied when the
hold lapses; the field reads `Erased` on the topic and in the store within the bound; amounts, ids
and times unchanged. `features/brands/personal-data.feature` green.

## 7. The original is recorded (US6)

```bash
# in eitheror-platform, with tests/original/compose.override.yml and e2e-recording.patch applied
just dev-stack && go test -tags=e2e -count=1 ./tests/e2e/... -record-to ../ankka-casino/tests/port
```

Expect: recording refuses to start if any of the seven stubs fails `/healthz`; afterwards one JSON
per test function under `tests/port/<service>/`, `suite-result.json` beside them, and
`sbt "brands/run recordings check"` reports every recording well-formed and every scenario run twice
with `vary` populated. `features/brands/recordings.feature` green.

## 8. Real money is refused until the installation is fit (US7)

```bash
sbt "brands/run validate brands/goldvibe/manifest.json --installation gke --real-money"
```

On the `gke` overlay with each of FR-035 to FR-038 removed in turn (`installation/overlays/gke`
variants under `tests/fixtures/installations/`): expect refusal naming the condition and its
source; with all present, acceptance. `features/brands/manifest.feature` outline green (SC-007).

## Done when

All eight sections pass on `dev-kind` (6 and the grant lines of 3 once 042 and 040 are published),
`just features` is green, and `sbt test` at the root is green.
