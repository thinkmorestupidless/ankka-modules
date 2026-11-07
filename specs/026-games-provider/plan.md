# Implementation Plan: games — eitheror's own game provider, beside ST8

**Branch**: `026-games-provider` | **Date**: 2026-11-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/026-games-provider/spec.md`

## Summary

eitheror becomes a game provider for its own brands. A shared project `games` holds one `provider`
service, the edge, and later one service per game. The edge is the simulator's core and ST8
adapter (spec 020) moved into a root-build library, `provider-core`, with the scenarios left
behind in the simulator, plus what a product needs that a test double did not: verification of the
brand's signature, an operator-written brand table, a retry workflow per callback with a pending
state, a three-act key rotation, audit records, a day export, and a game registry with an in-project
contract a game service implements (launch handoff, check, stake, settle, cancel, free rounds). It
speaks ST8's protocol exactly, so a brand's `game-provider` needs only a second provider
configuration; spec 003 is amended to instantiate its ST8 adapter under any configured name, spec
009 to know the Anjouan licence profile, spec 001 to count a fifth shared project. Game clients are
TypeScript in their game service; this spec fixes the layout and builds none.

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.13.0 (the provider, the library, the amendments);
TypeScript for game clients (layout only here, research R12).

**Primary Dependencies**: `casino-contract` and the new `casino-provider-core` from the root build;
ankka sdk, runtime, http, auth-oidc, telemetry-otlp; Jackson for the wire; the JDK HTTP client
for the outbound path (research R3 of spec 020 carries over: never ankka's service client).

**Storage**: the provider's own Postgres (every service owns its database, constitution VI):
`SessionEntity`, `CallbackEntity`, `GameEntity`, `BrandEntryEntity`, `BonusIndex`, views; the key
pair in the secret store.

**Testing**: munit; the library's test kit (`FakeBrand`, `TestGame`, `SeamSuite`); the provider's
`ProductSuite`, `OperatorApiParitySuite`, bound features for `features/games/*.feature`, a Docker
integration suite; the simulator's suites unchanged (SC-007); game-provider's and kyc's suites for
the amendments.

**Target Platform**: an ankka installation (GKE or `dev-kind`); the project `games` exposed through
the gateway; local mode on a laptop beside a brand.

**Project Type**: a shared project with one web service; a library in the root build.

**Performance Goals**: launch and check within 2 s at p99 (SC-003); a brand silent for 15 minutes
loses nothing (SC-004); the retry schedule's cap of 8 hours.

**Constraints**: no key, address or scenario reachable from a game service; no personal field
beyond the player id, currency and country; no grant under ankka 040; six endpoints at distinct
prefixes with at most two path parameters.

**Scale/Scope**: a handful of brands on one installation; rounds that stay open for days (a pools
coupon); tens of launches a second at most. Nothing here is high-frequency.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings it replays and the parity tests it leaves | Pass with the spec's *Changes*: the original has no first-party provider, so everything is new except the protocol, which is held to the simulator's recordings (`tests/port/simulator/OperatorApi_*`, replayed by `OperatorApiParitySuite`, research R15) and spec 003's brand-side suites, kept green under the R11 amendment. The one divergence from an original recording (the known-profile list gains Anjouan) is named in spec 009's *Changes*. |
| II. Money is exact, the wallet is the ledger | No balance of its own; minor units where money is ours | Pass: the provider holds no balance; a game's amounts are minor units as `Long` and the wire's decimal strings are rendered by the currency's exponent at the edge (R6); the brand's answered balances are recorded as strings for the operator's reads only. |
| III. A brand is a project | A shared project reaches a brand by grant or, for a public route, as the internet does; no player data beyond the id | Pass: `games` is the fifth shared project (R2); it reaches a brand's public callback routes under signature with no grant (FR-002); a session holds the player id, currency and a country code and the launch's profile is not kept (FR-023); a brand's entry, sessions and rounds are keyed by slug and read only by that brand's operators or the `games` role (R8). |
| IV. Commands over HTTP, facts over topics | | Pass: every command is HTTP; the one topic is `platform.audit` with the contract's family (R14); the game contract is in-project HTTP (R7); no store is read across services. |
| V. Personal data marked, erasure honoured | | Pass: no personal field (FR-023); `player_profile` answers redacted before recording (020 FR-023 carried in the library); the player id is a pseudonym (spec 001 FR-024). |
| VI. Scala by default, a reason for anything else | | Pass: Scala everywhere but the game clients, which are browser code and not the money path (FR-015, R12); each service owns its database. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1224 scenarios and 25 specs after clarify. |
| Workflow: `just features` before plan | | Pass. |
| Scope: ankka gaps are ankka specs, not workarounds | | Pass: nothing here needs an ankka change; the outbound JDK client is spec 020's deliberate choice (its R3), not a workaround. |

## Project Structure

### Documentation (this feature)

```text
specs/026-games-provider/
├── plan.md              # this file
├── research.md          # R1–R15 and what established them; Declined remediations
├── data-model.md        # Game, BrandEntry, Session, Round, Callback, Deliver, keys, BonusIndex, views, audit records
├── quickstart.md        # the library and simulator, the provider's suites, the brand side, dev-kind, keys, two brands
├── contracts/
│   ├── game-contract.md       # the in-project API between a game service and the provider; TestGame
│   ├── provider-routes.md     # every route and its class; the `games` permission
│   ├── brand-entry.md         # the brand entry and shared/games/manifest.json
│   ├── provider-core.md       # what moves into the library, what stays, the product rule, journals
│   ├── brand-amendments.md    # specs 003 (casino-providers), 009 (Anjouan), 001 (games); the brain
│   └── game-client.md         # the client layout, build, serving, language and money
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
build.sbt                                                   # + providerCore subproject (contracts/provider-core.md)
provider-core/                                              # NEW: the library
├── src/main/scala/io/eitheror/casino/provider/
│   ├── core/     Session Callback Play Protocol Schedule Sweep KeyRotation Wiring RunCallbacks Deliver(new)
│   ├── st8/      St8Protocol Signer Keys GoJson Http Catalogue BonusBook KeyDocument Health
│   └── brands/   Brand Sender
└── src/test/scala/io/eitheror/casino/provider/   SeamSuite FakeBrand TestGame + the core's entity suites

services/game-provider-simulator/                           # CHANGED: depends on provider-core; keeps scenarios, instructions, load, export, its page and operator API
├── build.sbt                                               # + casino-provider-core
└── src/main/scala/io/eitheror/casino/simulator/            # Scenario* ScenarioPlay LoadProfile Seeded Instructions GamePage OperatorApi Export SimulatorEndpoint Access BrandTable Simulator Main
    (README: journals dropped at the upgrade)

services/provider/                                          # NEW: the edge (ankka init)
├── build.sbt  service.json  docker-compose.yml  project/
├── src/main/resources/application.conf                    # provider.* with ${?PROVIDER_*}
├── src/main/scala/io/eitheror/casino/provider/edge/
│   ├── Main.scala                                          # register components; key at start; brand cache; sweep timer
│   ├── registry/   GameEntity GameIndex Registration (the /v1/games/registry routes)
│   ├── brands/     BrandEntryEntity BrandIndex BrandCache (the core's Brands over entities)
│   ├── operator/   OperatorApi (brand-signed launch, games, bonus; research R4, R5, R9) BonusIndex
│   ├── games/      GamesEndpoint (/v1/games: sessions check/stake/settle/cancel, callbacks)
│   ├── admin/      ProviderEndpoint (/v1/provider: brands, reads, unanswered, abandon, export, keys) Audit
│   ├── views/      SessionsByBrand RoundsByDay UnansweredCalls
│   └── Settings.scala                                      # PROVIDER_* : external url, retention, schedule, CA
└── src/test/scala/io/eitheror/casino/provider/edge/
    ├── ProductSuite.scala                                  # no simulator class on the classpath
    ├── port/OperatorApiParitySuite.scala                   # tests/port/simulator/OperatorApi_*.json with TestGame
    ├── steps/*Features.scala                               # features/games/*.feature
    └── ProviderIntegrationSuite.scala                      # Docker: late, error, silent brands (SC-004)

services/game-provider/                                     # AMENDED (spec 003, R11)
├── .../Settings.scala  Context.scala                       # adapters from casino-providers; ProviderPathEndpoint per name
├── .../api/Endpoints.scala                                 # configuration read answers operatorPublicKey
└── src/test/...                                            # features/game-provider/launch.feature's new scenario

services/kyc/src/main/scala/io/eitheror/casino/kyc/policy/  # AMENDED (spec 009, R13): Profile.Anjouan, Profiles.Anjouan

contract/src/main/scala/io/eitheror/casino/contract/Layout.scala       # SharedProjects += games
contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala # installationOperatorHolding(roles*)
contract/keys.md  modules/casino/module.json                           # casino-providers key
specs/001-brands-and-projects/contracts/shared-manifest.schema.json    # enums += games, provider
tools/modules/src/main/resources/shared-manifest.v1.json               # the same
shared/games/manifest.json                                             # NEW (contracts/brand-entry.md)
brands/_realm/                                                         # the games realm role
Justfile                                                               # just service publishes providerCore too; provider-test, provider-perf
.github/workflows/ci.yml                                               # paths: provider-core/**, services/provider/**; a provider job
```

**Structure Decision**: one new service under `services/` as every service is, depending on the
published contract and the new published library; the library in the root build beside the
contract, because the root build is where shared code already lives and is published from. The
simulator changes only its dependency and its package imports; its deployable shape is untouched.

## Component table

| Concern | Component | Id | Close alternative, rejected because |
|---|---|---|---|
| A session, its rounds and sent ids | `SessionEntity` (library, event sourced) | launch ticket | a new entity: the simulator's already models exactly this |
| One callback's attempts and answers | `CallbackEntity` (library, + `Abandoned`) | callback id | inside Session: no per-callback ordering, no abandon |
| Sending, retrying, giving up | `Deliver` workflow (library, new) | callback id | a timed action rescheduling itself: 1 KiB payload, runtime retry policy, no lifecycle (020 R13) |
| The registry | `GameEntity` (key-value) + `GameIndex` | code / `index` | a view over registrations: registration is a command, and the index is one row |
| The brand table | `BrandEntryEntity` (key-value) + `BrandIndex` + `BrandCache` | slug / `index` | env (`SIMULATOR_BRANDS`): needs an apply (R3) |
| Free rounds routing | `BonusIndex` (key-value) | bonus id | the game alone: `bonus/get` carries no game code |
| Keys | secret store + `Keys` + `KeyRotation` (library) | `keys/*` | config: forbidden by FR-019 |
| Reads | `SessionsByBrand`, `RoundsByDay`, `UnansweredCalls` views; `RunCallbacks` (library) | | reading journals: no paging |
| Retention | `Sweep` (library) on a recurring timer | `sweep` | `expireAfter`: the view row stays |
| Audit | a consumer-less publisher: the endpoint writes the record to `platform.audit` after the command | | a consumer over the entities: the actor is on the token, not in the entity |
| Endpoints | six at distinct prefixes (`/api/operator/v1`, `/.well-known`, `/health`, `/v1/games`, `/v1/provider`) | | one root endpoint: ankka refuses shared prefixes |

## Implementation notes

- **Order inside the build**: the library first (move, rename, `Deliver`, kits), the simulator
  re-pointed and green (SC-007), then the provider, then the brand-side and KYC amendments, then
  the manifest and CI. Nothing in the provider is written until the simulator passes on the library.
- **The move is a `git mv`** of the named files with a package rename; imports in the simulator
  follow. No behaviour changes in the move commit.
- **`Deliver` and `Play.send`**: `Play.send` stays synchronous (one attempt); `Deliver` calls it per
  attempt and owns the pause. The stake route calls `Play.send` once and starts `Deliver` only when
  the answer is retryable or absent.
- **Signature verification** reuses `St8Signature` from the contract? It lives in game-provider
  today (`provider/st8/St8Signature.scala`); the library's `Signer` has the signing half. Add
  `Signer.verify` to the library rather than depending on a service's code.
- **The `games` role**: `brands/_realm` is where realm roles are declared (`markets` is the
  precedent); the role is added there and the ACL recipe to `Routes`.
- **Spec 003's start-time names**: `casino-providers` is read once at start; a configuration write
  for an unlisted name is refused naming the value, so an operator learns the value must change
  (and the brand redeploy) before a third provider.
- **Journals**: the simulator's renamed types drop its dev data (contracts/provider-core.md);
  the provider starts empty.

## Phase 0: research

`research.md`: R1 (the library), R2 (the shared project), R3 (the brand table as entities), R4
(launch and check), R5 (verifying the brand), R6 (pending and `Deliver`), R7 (the contract's
transport), R8 (endpoints and classes), R9 (free rounds), R10 (the schedule), R11 (spec 003),
R12 (clients), R13 (Anjouan), R14 (records and audit), R15 (the oracle). No NEEDS CLARIFICATION
remains in the technical context.

## Phase 1: design

`data-model.md`, `contracts/` (six documents), `quickstart.md`.

### Constitution re-check after design

Unchanged from the table above. Two notes from design: (1) the brand's operator public key must
travel from the brand to the provider by an operator's hand (R5, R11); it is public, so nothing in
constitution III or V is touched, and the alternative (the provider fetching it) would be a brand
route reachable by the provider, which spec 001 forbids outside the callback routes. (2) The audit
publisher writes to `platform.audit` from an endpoint after the command, as `merchant` does; a
failed publish after a successful command is logged and retried by the endpoint's own bounded
retry, which is how the shared payments does it today and is noted for a later hardening spec.

## What the next command needs to know

- Build order (Implementation notes): library → simulator green → provider → amendments → manifest
  and CI. Tasks follow it.
- `TestGame` and `FakeBrand` are the fixtures every provider suite uses; they are in the library's
  test kit so 027's suites use them too.
- Scenario references in the spec name `features/games/*.feature`, `features/game-provider/launch.feature`
  (one scenario) and `features/kyc/policy.feature` (one scenario); bind them in the provider's,
  game-provider's and kyc's step suites respectively.
- The brain updates (contracts/brand-amendments.md) are tasks outside this repository's build.
- 027 (the pools game) starts from `contracts/game-contract.md` and `contracts/game-client.md`.
