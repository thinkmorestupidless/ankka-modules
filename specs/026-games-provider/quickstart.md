# Quickstart: proving the house provider

## Prerequisites

- JDK, sbt, Docker (the test kits start Postgres), `uv` (the features checker), Node 22 (only for a
  game with a client; none in this spec).
- The ankka plugin for `ankka-inspect`; a `dev-kind` installation for step 4
  (`installation/overlays/dev-kind`, memory: always `--context kind-…`).

## 1. The library and the simulator still pass

```bash
sbt providerCore/test                       # SeamSuite, the core's entity suites, FakeBrand and TestGame kits
sbt contract/publishLocal providerCore/publishLocal
cd services/game-provider-simulator && sbt test   # unchanged suites over the moved core (SC-007)
```

Expected: every simulator suite green; `features/simulator/*.feature` bound as before.

## 2. The provider's own suites

```bash
just service provider test
```

Runs, in `services/provider`:

- `ProductSuite`: no simulator class on the classpath.
- `OperatorApiParitySuite`: `tests/port/simulator/OperatorApi_*.json` replayed with `TestGame`
  registered (SC-001, the `game_url` host difference named).
- `steps/*Features.scala`: `features/games/{brand,contract,brands,keys,free-rounds,operators}.feature`
  against the provider, `TestGame` and `FakeBrand`.
- `ProviderIntegrationSuite` (Docker): a brand that answers late, answers `error` and never answers
  (SC-004: 15 minutes of silence, every callback acknowledged after, the balance moved once).

## 3. The brand side

```bash
just service game-provider test     # 003's suites with casino-providers = st8:st8,eitheror:st8
just service kyc test               # the Anjouan profile scenario
sbt "modules/run validate --all"    # the casino manifest with the new key; shared/games/manifest.json
just features                       # 0 findings
```

## 4. On `dev-kind`: Goldvibe configures the house provider beside the simulator

The only deployable game is spec 027's; `TestGame` is a test fixture. Until 027 lands, this step
proves the brand side and the edge, not a round.

```bash
just modules apply shared/games/manifest.json --installation dev-kind
```

Then, as Goldvibe's operator:

1. `PUT /v1/game-provider/goldvibe/providers/eitheror` with a generated operator key pair, the
   provider's address and `providerKeyDocumentUrl = https://provider-games…/.well-known/st8-keys`
   (Goldvibe takes no real money).
2. Read the configuration back; copy `operatorPublicKey`.
3. `PUT https://provider-games…/v1/provider/brands/goldvibe` with the entry
   (`contracts/brand-entry.md`), `keyDocument: true`, `language: "pt"`.
4. Trigger the catalogue refresh (`POST /v1/game-provider/goldvibe/catalogue/refresh`); the refresh
   status for `eitheror` is a success with an empty list; nothing of ST8's catalogue changes.
5. `GET https://provider-games…/v1/provider/brands/goldvibe` as Goldvibe's operator reads the
   entry; as an operator without the `games` role the `PUT` is refused.
6. Launch `eitheror/anything` as a player: refused `unknown_game` by the provider, answered by the
   brand as the launch of an unknown game is.

With 027 deployed, repeat step 4, then launch `eitheror/loteca`: the `game_url` is the pools
service's page, a round moves the wallet, `casino.rounds` has one `round_settled` naming
`eitheror` (SC-002: no brand-side write beyond this configuration), and `ankka-inspect` runs the
provider against `features/games/brand.feature`. SC-003 (launch and check within 2 s at p99) is
measured by `just service provider perf` against `FakeBrand` and `TestGame`.

## 5. Keys

On the provider: `POST /v1/provider/keys/start`, read `/.well-known/st8-keys` (two keys), play a
round (signed with the current key), `POST /keys/switch`, play a round within 60 s (Goldvibe
refreshes the document; admitted), `POST /keys/withdraw`, read the document (one key). SC-005.

## 6. Two brands

Add a second test brand entry on `dev-kind` (the simulator's `spinvibe` test slug or a second brand
project); play at both; `GET /v1/provider/brands/<slug>/rounds` shows only that brand's (SC-006).

## What is not proved here

A real game client (027), real money (no `realMoney` entry on `dev-kind`), ST8's actual retry
figures (research R10's caveat carries over).

## Run on dev-kind

**2026-11-10, kind-ankka (ankka platform 0.14.2, runtime 0.13.0).**

- `just modules validate shared/games/manifest.json --installation dev-kind`: valid, one service and one topic. Both settings resolve (`GAMES_PROVIDER_URL`, `GAMES_REPORTING_ZONE`). FR-035 to FR-038 are reported unmet, as for `tools`: dev-kind takes no real money.
- `just modules apply shared/games/manifest.json --installation dev-kind --secrets <file>`: project `games` created, `platform.audit` declared (6 partitions), secret `games-provider` set, `provider` applied at `provider:0.1.0` and exposed. Five changes.
- The first start failed: `PROVIDER_LOCAL_CA is not a PEM certificate`. The secrets file carries base64 of the PEM; `Settings` now reads both (research, T055).
- The second start failed: `consumer 'provider-audit' publishes to 'platform.audit' but no MessagePublisher was configured`. The suites supply a broker; `main` did not. It now takes the installation's broker, or one in memory for a local run, as kyc does.
- The rollout then waited on the earlier generation's crash-looping pod, which the new pod tried as a bootstrap contact. A pause and resume (`ankka services pause|resume provider --project games`) brought it up `Ready` 1/1.
- Through the gateway: `GET /health/` is `{"status":"ready"}`. `GET /.well-known/st8-keys` answers the current key with its `spki` and the `keys` list. `GET /v1/provider/brands` with no token is 401. `POST /api/operator/v1/launch` for a brand with no entry is `{"status":"error","error":"auth_failed"}`. Plain HTTP is 301.

**Not run here:** the operator half of step 4 (items 1 to 6).

- goldvibe's `game-provider` on this cluster is paused, and its image predates `casino-providers`. Running it means rebuilding the brand's services on this branch's contract.
- The steps also need an eitheror staff token holding `games`, which a person must create (the realm role is in `brands/_realm/eitheror-operators.json`).
- The same exchanges are covered in process by `OperatorsFeatures`, `KeysFeatures`, `ProviderIntegrationSuite` and game-provider's `LaunchFeatures` ("a second game provider of the same protocol under its own name").
