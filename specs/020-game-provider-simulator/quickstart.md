# Quickstart: proving the game provider simulator end to end

Each section is one of the spec's independent tests, in the order the tasks build them. Commands
run from the repository root unless said otherwise. Expected outcomes are the acceptance scenarios
in `features/simulator/*.feature`.

## Prerequisites

- `sbt`, Docker, the `ankka` CLI and the `ankka@ankka` plugin; `uv` for `just features`.
- `sbt contract/publishLocal` after the `Layout` change (research R1), so the service build finds
  `io.eitheror:casino-contract` with `tools` in it.
- A rebuilt brand's `game-provider` (spec 003) running locally on port 9000 for sections 3 to 7,
  with its provider public key configured as the simulator's key document address
  (`http://localhost:9100/.well-known/st8-keys`). Until spec 003 exists, sections 2, 4 and 8 run
  against the testkit's scripted brand only.
- The original at `~/Documents/projects/eitheror/eitheror-platform` for section 9.
- The casino's Keycloak from `installation/base` running locally for the operator routes in
  sections 3 to 7 (`contracts/brand-config.md`, local mode); `$STAFF` below is a token from its
  `eitheror` realm with role `platform_admin`.
- `just features` green: `0 findings in 1016 scenarios and 20 specs`.

## 1. The service builds, its unit suites pass, the features bind (all stories)

```bash
cd services/game-provider-simulator
sbt test
```

Expect: the `Session`, `Callback`, `Scenario` and `Bonus` entity suites pass; `GherkinSuite` binds
every scenario of the five feature files (pending ones named, none unbound); the scenario JSON files
under `src/main/resources/scenarios/` validate against `specs/020-.../contracts/scenario.schema.json`;
a body built in `stub` shape for a bet is byte-identical to the stand-in's
(`{"amount":"1.50","currency":"EUR","game_code":"book-of-ra","provider_kind":"debit","round":"…","token":"…","transaction_id":"…"}`).

## 2. A round against the scripted brand, default scenario (US1)

In the testkit (`sbt "testOnly *RoundsFeatures"`): a `ScriptedService` plays the brand. Expect:
launch stores the session and answers `{"game_url":"http://…/game?token=<token>"}\n`; `GET /game`
sends `check` with `{"token":…}` and serves the game page; `POST /test/bet` sends one signed debit
(the brand's recorded request carries `x-st8-sign` that verifies against the key document's
`current.spki`) and returns the brand's body and status unchanged; win, lose and cancel likewise;
`/test/cancel` names the bet's transaction id and a fresh cancel id.

## 3. The same round against a rebuilt brand (US1, SC-001)

```bash
cd services/game-provider-simulator
ANKKA_DB_NAME=simulator ANKKA_HTTP_PORT=9100 \
SIMULATOR_BRANDS='[{"slug":"goldvibe","baseAddress":"http://localhost:9000","testBrand":true}]' \
SIMULATOR_EXTERNAL_URL=http://localhost:9100 ANKKA_SECRET_KEY=$(openssl rand -base64 32) \
ANKKA_AUTH_ISSUERS=staff \
ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
ANKKA_AUTH_STAFF_AUDIENCE=operator-console sbt run     # the casino's Keycloak from installation/base, running locally
```

Then, from spec 003's test kit with the provider address set to `http://localhost:9100`, run the
recordings the spec names under *Recordings* (`TestScenarioFreeSpinsPlay_*`, `TestScenario19`,
`TestScenario244_*`, the game roundtrip). Expect: every parity test passes with the simulator as
the provider, zero differences in callbacks or answers (SC-001). `ankka local console` lists
`game-provider-simulator` with its components.

## 4. A scenario misbehaves on purpose (US2, SC-002)

```bash
curl -X PUT -H "Authorization: Bearer $STAFF" -d '{"name":"slow-operator"}' \
  http://localhost:9100/v1/simulator/brands/goldvibe/sessions/$TOKEN/scenario
# launch and load the game page for $TOKEN at the brand, then:
curl -d '{"launchToken":"'$TOKEN'","amount":"1.50"}' http://localhost:9100/test/bet
curl -H "Authorization: Bearer $STAFF" http://localhost:9100/v1/simulator/brands/goldvibe/runs/$TOKEN/verdict
```

Expect, per scenario (with the scripted brand delaying or failing as each needs in the testkit, and
against the rebuilt brand for `slow-operator`, `cancel-after-credit`, `unknown-token` and
`orphaned-bet`): the verdict lists each `expect` step with expected against observed and `passed`;
`slow-operator` shows two attempts under one `transaction_id`, equal answers, balance moved once;
`retry-storm` shows the attempt count and time to settle; `rotated-key` shows calls under the next
key admitted within the bound and calls under the withdrawn key refused `auth_failed`;
`unknown-token` shows `session_expired` and no balance movement; `wrong-key` and `malformed-body`
show one attempt each, never retried. A second `PUT …/scenario` on the same session answers 409.
A `PUT` without a staff token answers 401 and the run shows nothing.

## 5. The operator API and free rounds (US3)

```bash
curl http://localhost:9100/api/operator/v1/games | jq '.games[].code'
curl -d '{"bonus_id":"b1","players":["p1"],"count":3,"value":"0.10","type":"free_rounds","currency":"EUR","game_codes":["book-of-ra"],"site":"s"}' \
  http://localhost:9100/api/operator/v1/bonus/create
curl -d '{"launchToken":"'$TOKEN'","winAmount":"0.50"}' http://localhost:9100/test/free-spin
```

Expect: the three games in seed order, developers and categories sorted; `create` answers `ok`
with one `active` instance of 3; an unknown game answers `{"error":"unknown_game","status":"error"}`;
`free-spin` sends a `free_debit` of `"0.00"` then a credit of `"0.50"` on one round and answers
`remaining: 2`; a player with no instance gets `409 {"error":"no_free_rounds"}` and the brand
receives nothing; after `bonus/cancel` the instance is `canceled` and the next spin is refused.

## 6. The run is readable and becomes a parity test (US4, SC-006)

```bash
curl -H "Authorization: Bearer $STAFF" "http://localhost:9100/v1/simulator/brands/goldvibe/runs/$TOKEN" | jq .
curl -H "Authorization: Bearer $STAFF" http://localhost:9100/v1/simulator/brands/goldvibe/runs/$TOKEN/export > /tmp/run.json
```

Expect: each callback with body, key id, times, attempt, answer and step; the export loads with
`Recording.load` and replays in spec 003's kit against the same brand with no edits (SC-006). In
the testkit, moving the `TimerRuntime` clock past 30 days fires `sweep-runs`, the run's rows are
gone and `GET /v1/simulator/status` counts it.

## 7. A load profile (US5, SC-004)

```bash
# spec 003's kit launches 100 sessions for 100 test players and prints their launch tokens
curl -X POST -H "Authorization: Bearer $STAFF" -d '{"sessions":[…100…],"roundsPerMinute":10,"duration":"5m"}' \
  http://localhost:9100/v1/simulator/brands/goldvibe/load-profiles
```

Expect: `GET …/load-profiles/{id}` ends `done` with rounds sent (about 5,000), answers by word,
p50/p95/p99; the brand's answer time at p99 within spec 002 SC-003's bound; `stop` while playing
settles every open round as a loss before `stopped`. Against a brand whose entry has
`testBrand: false`: `403 {"error":"not_a_test_brand"}` and no session touched.

## 8. Determinism (SC-003)

Run `win-larger-than-any-bet` twice with `seed: 42` against the scripted brand on two machines (or
two databases): the exports' callbacks are the same kinds, amounts and rounds in the same order;
only `vary` fields differ.

## 9. The original's suite against the simulator (FR-026)

```bash
cd ~/Documents/projects/eitheror/eitheror-platform
# the original's stack as tests/original/README.md starts it, without fake-st8; then
cd ../ankka-casino/services/game-provider-simulator
SIMULATOR_BRANDS='[{"slug":"spinvibe","baseAddress":"http://localhost:8095","testBrand":true,"registerKeyAt":"http://localhost:8095/st8/test/set-public-key"}]' \
SIMULATOR_EXTERNAL_URL=http://localhost:9100 ANKKA_HTTP_PORT=9100 … sbt run
curl -X POST http://localhost:8095/st8/test/run-catalog-sync      # the original seeds its catalogue from the simulator's listing
cd ~/Documents/projects/eitheror/eitheror-platform/tests/e2e && FAKE_ST8_URL=http://localhost:9100 go test -tags e2e -run 'FreeSpinsPlay|Scenario19|Scenario244' ./...
```

Expect: the simulator reports `/health` `ready` after the key is registered; the tests that drove
`fake-st8` pass unchanged. A test that needed the stand-in's direct substrate seed is listed as
blocked by the catalogue change (research R8), not patched.

## 10. Deployed on `dev-kind` (FR-024)

```bash
sbt contract/publishLocal && just brands validate shared/tools/manifest.json --installation dev-kind
cd services/game-provider-simulator && sbt Docker/publishLocal && kind load docker-image game-provider-simulator:latest --name ankka
just brands apply shared/tools/manifest.json
curl -k https://game-provider-simulator-tools.127.0.0.1.sslip.io:8443/.well-known/st8-keys
```

Expect: the project `tools` with one service `Ready` and exposed; the brand's `game-provider`
admits a callback signed with the document's current key as `Caller.Gateway`; the brands tool
refuses the manifest with `--real-money` on the `gke` overlay.

Before the apply: `installation/overlays/dev-kind/gateway-dns.sh` (after ankka's `just up`), and a
`--secrets` file holding `tools-simulator/LOCAL_CA=<base64 of ~/.ankka/local-ca.crt>`.

**Run 2026-10-11 on a fresh cluster** (ankka at v0.11.0-7): project `tools` created, the service
`Ready` 1/1 and exposed at `https://game-provider-simulator-tools.127.0.0.1.sslip.io:8443`; through
the gateway `/health` ready, the key document with a key generated in the secret store, the games
listing, a launch answering its `game_url`, an operator route without a token 401, plain HTTP 301.
From inside the cluster (research R3) the deployed simulator reached a gateway hostname over TLS,
trusting the local CA from the project secret. A brand's `game-provider` admitting the simulator
waits for spec 003.

## Done when

Sections 1, 2, 4 (scripted brand), 5, 6 and 8 pass in CI; 3, 4 (rebuilt brand), 7, 9 and 10 pass
by hand on a laptop and are recorded in the PR; `just features` is green; `tests/port/simulator/`
holds one recording per operator-API shape with a parity test each.
