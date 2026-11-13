# provider

eitheror's own game provider (spec 026). It is the edge of the shared project `games` and serves
every brand on the installation. It speaks ST8's operator API and seamless-wallet protocol exactly,
so a brand's `game-provider` reaches it as a second provider configuration named `eitheror`, with
no adapter of its own (spec 003 FR-026, amended). Games are services behind it in the same project.
They implement `specs/026-games-provider/contracts/game-contract.md`, and a new game needs no new
brand integration.

The core is the `provider-core` library in the root build (sessions, rounds, signed callbacks, the
key ring and key document, the brand sender), shared with `game-provider-simulator`. None of the
simulator's scenarios, instruction routes or seeded generator are on this classpath. Scripted
misbehaviour cannot be deployed. The design is `specs/026-games-provider/plan.md`; the decisions and
what established them are in its `research.md`.

## Build and test

```bash
just provider-test          # publishes contract and provider-core, then sbt test (Docker: Postgres)
just provider-it            # the integration suite alone: a late, failing and silent brand (SC-004)
just provider-perf          # launch and check latency against the fake brand (SC-003)
```

Republishing `provider-core` under the same version needs the old copy removed first:
`rm -rf ~/.ivy2/local/io.eitheror/casino-provider-core_3/<version>`.

The suites cover three levels:
- the library's unit kit;
- the living features `features/games/*.feature`, bound against the whole service with a fake brand
  (`FakeBrand`) and a test game (`TestGame`), both from the library's test kit;
- the parity suites, which replay the simulator's recordings (`tests/port/simulator/`) against the
  provider. A brand talking to the provider sees what it sees from the simulator's default
  scenario, with one named exception: the `developer` field.

## The components

| Kind | Name | What it holds |
|---|---|---|
| event sourced | `session` (library) | one launch ticket's session: its rounds, stakes, wins, the callbacks planned |
| event sourced | `callback` (library) | one signed call to a brand: its attempts, the answer, abandoned or not |
| workflow | `deliver` (library) | sends a callback to its brand on the retry schedule; ends on an answer, exhaustion or an abandon |
| view | `run-callbacks` (library) | callbacks by session and brand; the unanswered calls |
| timed action | `sweep` (library) | removes sessions and callbacks past `PROVIDER_RETENTION_DAYS` |
| key value | `game`, `game-index` | each registered game and its owning game service |
| key value | `brand-entry`, `brand-index` | each brand's entry (below) and the table of slugs and sites |
| key value | `bonus-index` | which brand and game hold a bonus id, for `bonus/get` and `bonus/cancel` |
| view | `sessions-by-brand`, `rounds-by-day` | the operator's listings and the daily export |
| event sourced | `provider-acts` | every operator act (entry written, key acts, abandon, game removed) |
| consumer | `provider-audit` | turns each act into a `platform.audit` record (constitution IV) |

## The endpoints

There are five, each at its own prefix (ankka 0.13: no two endpoints share a prefix). The full
table is `specs/026-games-provider/contracts/provider-routes.md`.

| Prefix | Class | What |
|---|---|---|
| `/api/operator/v1` | public, brand-signed | ST8's operator API: `launch`, `games`, `bonus/create`, `bonus/get`, `bonus/cancel`; the signature is verified against the entry found by `site` |
| `/.well-known` | public | `st8-keys`: the key document (current, next and previous keys, each with its public key) |
| `/health` | public | ready once the key is held and the brand table loaded |
| `/v1/games` | service, in-project | the game contract: registration, sessions, round open, settle and cancel, free-round handoff |
| `/v1/provider` | operator | brand entries, sessions, rounds, unanswered calls, abandon, the daily export, the registry, the keys, `metrics` |

Reads of one brand are under `/v1/provider/brands/{slug}/…` for that brand's operators. Writes need
the `games` role at the eitheror realm, and each is audited. `platform_admin` reads the brand table
and does nothing else here.

`GET /v1/provider/metrics` sits behind a staff token holding `games`, like the other installation
reads. Nothing scrapes it yet: the installation's Prometheus has no token for it, and the
unanswered gauge runs one view query per brand on each read. Scraping waits for a scrape identity
the installation can issue.

## A brand's entry

The provider calls a brand only through the brand's public callback routes, from outside, admitted
by signature, as ST8 does. A brand is admitted only once an operator holding `games` has written its
entry: `PUT /v1/provider/brands/{slug}`. Every field is described in
`specs/026-games-provider/contracts/brand-entry.md`.

1. On the brand, write the configuration for provider `eitheror` with a fresh operator key pair:
   `PUT /v1/casino/backoffice/{brand}/game-provider/providers/eitheror`. A brand that takes no real
   money may name `providerKeyDocumentUrl = https://provider-games…/.well-known/st8-keys`; a
   real-money brand holds the provider's public key itself.
2. Read the configuration back and copy its `operatorPublicKey`.
3. Write the entry here with that key, the brand's `baseAddress`, its `site`, `language` (`es` or
   `pt`), `testBrand` and `realMoney`. Calls go to `<baseAddress>/eitheror/<callback>`, the
   second provider's own prefix.

An entry with `keyDocument: true` and `realMoney: true` refuses every launch, naming the entry.

A write applies at once at the replica that served it, and within 30 s at every other one: each
re-reads the brand table every 30 s on a thread of its own (a recurring ankka timer fires on one instance only).

## Rotating the key

There are three acts, each an operator route needing `games`, and each audited. `GET /v1/provider/keys`
shows the current, next and withdrawn ids at any point.

1. `POST /v1/provider/keys/start`: a next key is made and published in the key document beside the
   current one. Brands that read the document pick it up within their refresh bound (60 s by
   default); a brand holding the key itself is sent the new public key by its operators.
2. `POST /v1/provider/keys/switch`, once every brand holds the next key: calls are signed with it.
   The old key stays in the document as `previous`.
3. `POST /v1/provider/keys/withdraw`, once no call signed with the old key can still be in flight:
   the previous key leaves the document.

## Run locally beside a brand

```bash
sbt schema && docker compose up -d
ANKKA_HTTP_PORT=9110 ANKKA_SECRET_KEY=$(openssl rand -base64 32) \
PROVIDER_EXTERNAL_URL=http://localhost:9110 \
ANKKA_AUTH_ISSUERS=staff \
ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
ANKKA_AUTH_STAFF_AUDIENCE=operator-console \
sbt run
```

Run the brand's `game-provider` with `CASINO_PROVIDERS=st8:st8,eitheror:st8` (the default), then
write its entry as above with `baseAddress` `http://localhost:<its port>`.

## Settings

| Env | Default | Meaning |
|---|---|---|
| `PROVIDER_EXTERNAL_URL` | required | the address brands and players reach |
| `PROVIDER_RETENTION_DAYS` | 90 | how long sessions and callbacks are kept |
| `PROVIDER_RETRY_SCHEDULE` | 30 s for 10 min, then every 10 min, cap 8 h | the retry schedule of a call a brand did not answer |
| `PROVIDER_LOCAL_CA` | none | a local installation's gateway CA, as PEM or base64 of PEM |
| `CASINO_REPORTING_ZONE` | UTC | the zone a day of the export is cut in |

A malformed value is refused at start, naming the setting.

## Deploy

`shared/games/manifest.json`, applied with `just modules apply shared/games/manifest.json
--installation <name>`. Its address and reporting zone are installation settings (`GAMES_PROVIDER_URL`,
`GAMES_REPORTING_ZONE` in `installation/overlays/<name>/settings.env`). The image is built with
`SERVICE_VERSION=<v> DOCKER_REPOSITORY=europe-west1-docker.pkg.dev/eitheror/casino sbt Docker/publishLocal`.
