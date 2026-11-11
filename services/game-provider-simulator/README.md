# game-provider-simulator

A stand-in for ST8 (spec 020). In its default scenario a rebuilt brand cannot tell it from the
original's `fake-st8`; in every other scenario it misbehaves on purpose, as a real provider does when
things go wrong, and judges what the brand answers. It runs in the shared project `tools` and reaches
a brand as the real provider does: from outside, through the gateway, admitted only by its signature.

Design: `specs/020-game-provider-simulator/` (plan, research, data model, contracts). The wire is
`contracts/st8-wire.md`.

## Layout

The core is a library since spec 026: `provider-core/` in the root build (`io.eitheror:casino-provider-core`),
shared with the house provider (`services/provider`). It holds sessions, rounds, callbacks, the
protocol seam, the schedule, the run view, retention, the key rotation, the ST8 adapter (bodies,
signing, keys, the catalogue, the bonus book, the key document) and the outbound sender. What only a
test double needs stays here:

| Package | What |
|---|---|
| `core` | scenarios, the scenario library and workflow, the load profile, the seeded generator. Names no protocol word (`SeamSuite` holds it to that, FR-006). |
| `st8` | the stand-in's operator API, its game page and the instruction routes. |
| `brands` | the brand table (`SIMULATOR_BRANDS`). |
| `api` | the operator routes, their ACLs (only `contract.access.Routes` recipes) and the run export. |

Scenarios are data: `src/main/resources/scenarios/*.json`, listed in `index.txt`, validated against
`specs/020-game-provider-simulator/contracts/scenario.schema.json` and seeded into the library at
start. An operator adds or replaces one with `PUT /v1/simulator/scenarios/{name}` without a release.
The shipped files are written by `scenarios.py` (`python3 scenarios.py src/main/resources/scenarios`):
change the script, not the JSON, so the files stay uniform.

## Test

```bash
sbt contract/publishLocal providerCore/publishLocal   # at the repository root
cd services/game-provider-simulator
sbt test                           # needs Docker: the test kit starts a throwaway Postgres
```

`just service game-provider-simulator test` does both. The suites run the living features
`features/simulator/*.feature` against the simulator and a fake brand (`FakeBrand`, from the
library's test kit), and replay `tests/port/simulator/*.json`, recorded from `fake-st8`.

## Run locally beside a brand (FR-026)

```bash
sbt schema && docker compose up -d
ANKKA_HTTP_PORT=9100 ANKKA_SECRET_KEY=$(openssl rand -base64 32) \
SIMULATOR_BRANDS='[{"slug":"goldvibe","baseAddress":"http://localhost:9000","testBrand":true}]' \
SIMULATOR_EXTERNAL_URL=http://localhost:9100 \
ANKKA_AUTH_ISSUERS=staff \
ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
ANKKA_AUTH_STAFF_AUDIENCE=operator-console \
sbt run
```

The brand's `game-provider` names `http://localhost:9100/.well-known/st8-keys` as its provider public
key. Against the original, add `"registerKeyAt": "http://localhost:8095/st8/test/set-public-key"` to the
brand's entry and the simulator pushes its key there as `fake-st8` does. Every field of a brand entry
is in `contracts/brand-config.md`.

## Deploy

`shared/tools/manifest.json` at the repository root, applied with `just brands apply`; the image is
`sbt Docker/publishLocal`. Never on an installation that takes real money.

**Upgrading past spec 026.** The stored types moved package when the core became a library. A
simulator keeps test runs only, so a dev installation drops its simulator data at that upgrade
rather than reading old journals back: `ankka services delete game-provider-simulator`, then apply.
