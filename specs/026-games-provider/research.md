# Research: games — eitheror's own game provider

Each decision names what established it: the simulator's and the brand adapter's source, the specs
they implement, and the discussion of 2026-11-07.

## R1 — The core becomes a root-build library, `provider-core`

**Decision**: a new subproject `provider-core/` in the root `build.sbt` (`io.eitheror:casino-provider-core`,
published with `sbt provider-core/publishLocal` as the contract is), depended on by both
`services/game-provider-simulator/` and `services/provider/`. It holds, renamed from
`io.eitheror.casino.simulator.*` to `io.eitheror.casino.provider.*`:

| Package | From the simulator | What it is |
|---|---|---|
| `core` | `Session`, `SessionEntity`, `Callback`, `CallbackEntity`, `Play`, `Protocol`, `Schedule`, `Durations`, `Sweep`, `KeyRotation`, `Wiring`, `RunCallbacks` | sessions, rounds, callbacks, the seam, the retry schedule, the sweep, rotation, the view |
| `core` (new) | `Deliver` | the product's retry driver: a workflow per callback that sends, pauses on a retryable answer for the schedule's next interval, and ends `acknowledged`, `exhausted` or `abandoned` (R6) |
| `st8` | `St8Protocol`, `Signer`, `Keys`, `GoJson`, `Http`, `Catalogue`, `BonusBook`, `KeyDocument`, `Health` | the ST8 protocol adapter, signing, the key document |
| `brands` | `Brand`, `Brands` (trait), `Sender` | the brand entry's shape and the outbound JDK client |

What stays in the simulator: `Scenario`, `ScenarioEntity`, `ScenarioLibrary`, `ScenarioPlay`,
`LoadProfile`, `Seeded` (used only by scripted play; moves to the library only if `Play` turns out
to need it), `Instructions`, `GamePage` (the stand-in's page), `OperatorApi` (the stand-in's
launch, which selects a scenario: the provider's launch is its own, R4), `Export`,
`SimulatorEndpoint`, `Access`, `BrandTable.fromEnvironment` (the simulator keeps `SIMULATOR_BRANDS`;
the provider's table is entities, R3).

**Rationale**: spec 020 kept the seam as a package boundary because one deployable needed no more;
FR-005 now needs two deployables with one core, and a library is the only shape in which the
scenarios can be kept off the product's classpath. The root build already does this for the
contract and the modules tool.

**Consequences**: the package rename changes the type names the simulator's journals carry. The
simulator holds test runs only (30-day retention, never real money), so a dev installation's
simulator data is discarded at the upgrade; the plan says so and the simulator's README notes it.
`SeamSuite` moves to the library and keeps holding `core` to naming no protocol word; a new
`ProductSuite` in the provider asserts no class of `io.eitheror.casino.simulator` is on its
classpath (FR-005).

**Alternatives**: one service with a `--product` flag (rejected: a flag is a deployable
misbehaviour); copying the core (rejected: two copies of a protocol speaker drift).

## R2 — `games` is a shared project; `provider` is its first service

**Decision**: `Layout.SharedProjects += "games" -> Vector("provider")` (027 adds `"pools"`),
`ReservedSlugs` follows; `shared-manifest.schema.json` (spec 001's and the modules tool's vendored
copy) gains `games` in the project enum and `provider` in the services enum;
`shared/games/manifest.json` declares the service, its env, the `platform.audit` topic and no
grant. The modules tool's real-money refusal names `tools` only (`Main.scala:294`), so `games`
applies on a real-money installation with no change; a test asserts it.

**Rationale**: constitution III: a shared project holds no player data beyond the player id and
reaches a brand only through its public routes or a grant; the provider reaches a brand exactly as
the simulator does (020 FR-024), by signature, with no grant. Spec 020's R1 is the precedent.

## R3 — The brand table is operator-written entities, not environment

**Decision**: one `BrandEntryEntity` (key-value, id the slug) per brand, written and read over
operator routes under `/v1/provider/brands/{slug}` (R8), plus a `BrandIndex` key-value entity
holding the slugs so `Brands.slugs` needs no scan. The provider's `Brands` implementation (the
core's trait) reads a process-local cache refreshed on every write through the component client
and at start; a change applies within a second and without a redeploy (FR-017). Fields: `contracts/brand-entry.md`.

**Rationale**: FR-017 asks for writes without a redeploy; the simulator's `SIMULATOR_BRANDS` env is
rendered into the manifest and needs an apply. The simulator keeps its env table (its brands are
test fixtures).

**Alternatives**: the secret store (rejected: the entry holds one public key and no secret; the
store has no listing); a brand value at the vendor (deferred by the clarification of 2026-11-07).

## R4 — Launch, the game page handoff and `check`

**Decision**: the provider's `OperatorApi` is its own (the stand-in's selects scenarios and
hardcodes `game_url`): it verifies the brand's signature (R5), finds the brand by `site`, stores the
session through `SessionEntity.launch` with the registered game's code, demo flag, the entry's
language and the profile's country, and answers `game_url` as the game's registered page address
with `?token=<launch ticket>` appended. The game page is served by the game service; on load the
game service calls the provider's in-project route `POST /v1/games/sessions/{token}/check` (R7),
and the provider sends the signed `check` to the brand through `Play.send`, records it
(`SessionEntity.check`) and answers the session. The provider serves no page (FR-016).

**Rationale**: FR-009 and FR-015; the browser cannot sign, and the game service must not hold a
key, so the check is the provider's and the page the game's. The simulator's `GamePage` is the
pattern (`check` on load, expired page on refusal) moved one service over.

## R5 — Verifying the brand's operator API calls

**Decision**: a POST is verified over its body and a GET over its query string as written, with
`St8Signature.verify` against the brand entry's `operatorPublicKey`, the header being the one the
brand's `St8Adapter` sends (`St8Signature.Header`, `x-st8-sign`). The brand is found by `site`
(the body's `site`, a string or `{id}`, or the query's `site=`) before verifying; a site no entry
names, a signature that fails, or a `site` that is not the found brand's is refused HTTP 200
`{"status":"error","error":"auth_failed"}` for the operator API's shape, and nothing is stored.

**Rationale**: FR-004. The brand already signs every operator API call (`St8Adapter.scala:27-43`);
`fake-st8` and the simulator ignore the header. A real-money provider cannot.

**Consequence**: the brand's operator must copy the brand's operator *public* key into the entry.
Spec 003's configuration route never answers the private key and today answers no public key
either; the amendment (R11) adds the public key, derived from the private one, to the read.

## R6 — Pending debits and the retry driver

**Decision**: a `Deliver` workflow per callback id in `core`. The game's debit request runs
`Play.send` synchronously for the brand entry's `providerWaitMillis` (default 5 s); an accepted or
refused answer within it is returned to the game. Past the wait, or on a retryable answer
(`error`, a transport failure, HTTP 5xx), `Deliver` is started for the callback and the game is
answered `pending`; `Deliver` re-sends under the same transaction id at each interval of the
`Schedule` (R10) until the brand answers (`acknowledged`), the cap passes (`exhausted`, listed as
an unanswered call) or an operator abandons it. A credit or cancel always runs through `Deliver`
and is answered `accepted` at once. The game reads a callback's state at `GET
/v1/games/callbacks/{id}`.

**Rationale**: FR-010 to FR-013. Spec 020's plan first drew a `CallbackWorkflow` and built
`ScenarioPlay` instead because scripted play needed a cursor (020 R13); the product needs exactly
the workflow that was drawn.

**Alternatives**: a timed action rescheduling itself (rejected in 020 R13 for the same reasons:
payload bound, runtime retry policy, no lifecycle).

## R7 — The game contract is in-project HTTP, service class

**Decision**: the provider's routes under `/v1/games` are admitted by `Routes.service()` (any
service principal of the `games` project; the project boundary is the trust boundary) and the
route handlers check that the caller's service name is the game's registered owner for anything
touching a code or a session (FR-009's "only its own codes", FR-018). A game service's routes the
provider calls (`/v1/game/bonus/*`, R9) are admitted by `Routes.serviceOf("provider")`. Bodies and
answers: `contracts/game-contract.md`.

**Rationale**: constitution IV (commands over HTTP); spec 001's service class; nothing of the
contract is reachable from outside the project (Assumptions).

## R8 — The provider's endpoints and their classes

**Decision**: six endpoints at distinct prefixes, as ankka requires (no shared prefix, at most two
path parameters): `/api/operator/v1` (public, brand-signed: launch, games, bonus create/get/cancel),
`/.well-known` (public: `st8-keys`), `/health` (public), `/v1/games` (service class: registration,
sessions, callbacks, bonus handoff), `/v1/provider` (operator: brands, sessions, rounds, unanswered,
abandon, export, keys), `/v1/provider-reads` is not needed: reads of one brand live under
`/v1/provider/brands/{slug}/...` with `Routes.operatorHolding(slug, "games")` for writes and
`Routes.operator(slug)` (the brand's own operators) or `operatorHolding("games")` for reads. The
`games` permission is a realm role in `brands/_realm`, as `markets` is. Every route: `contracts/provider-routes.md`.

## R9 — Free rounds are handed to the game; the provider keeps only the index

**Decision**: `bonus/create` finds the game by the request's `game_codes` (one game per bonus;
two codes of two games is `unknown_game`, as `fake-st8` refuses an unknown code), verifies the
brand, calls the game service's `POST /v1/game/bonus` with the brand, players, count, value,
currency and the brand's requested duration, records `BonusIndex(bonus id → brand, game code)`
(key-value) and answers the game's envelope in the stand-in's shape (`OperatorApi.envelope`).
`bonus/get` and `bonus/cancel` route by the index. The game holds the instances and the claim
deadline (FR-014); the simulator's `BonusBook` stays in the library for the simulator and the test
game, which uses it as its instance store.

## R10 — One retry schedule, one setting

**Decision**: `Schedule(answerWait = entry's wait, intervals = 30 s for 10 min then 10 min, cap = 8 h,
retryOn = St8Protocol.defaultRetryOn)` built from `PROVIDER_RETRY_SCHEDULE` (the simulator's schedule
JSON shape from `scenario.schema.json`'s `schedule` object, default as above) once at start.

**Rationale**: FR-013; spec 020's shipped default and its caveat (ST8's figures not to hand) carry
over; a setting, not a scenario.

## R11 — The brand side: a provider's protocol is configuration

**Decision** (amending spec 003): a brand value `casino-providers` lists the provider names the
brand's `game-provider` serves, each with its protocol, default `st8:st8,eitheror:st8`; at start
`Settings.adapters` is one `St8Adapter(name)` per entry whose protocol is `st8` (the only
protocol), and `ProviderPathEndpoint(name)` is created per name; the root form stays ST8's only.
`ProviderConfiguration` (secret store, per name) is unchanged in shape; its read gains
`operatorPublicKey`, derived from the private key, so the operator can copy it into the provider's
brand entry (R5). The catalogue, launch, free rounds and callbacks already key by provider name.

**Rationale**: endpoints are registered at service start, so the set of names is a start-time
setting (a brand value), while keys and addresses stay runtime configuration (003 FR-026). This is
the smallest change that makes 003's "a second provider is a second adapter and a second
configuration" true for a second provider of the same protocol.

## R12 — Game clients: TypeScript in the game service, built into its image

**Decision**: `services/<game>/client/` is a Vite + TypeScript project (`package.json`,
`vite.config.ts` with `base: '/game/'` and a flat `assetsDir`), built by `npm ci && npm run build`
into `src/main/resources/client/` (gitignored) before `sbt Docker/publishLocal`; a `just service
<game> client` recipe and the game's CI job (with `actions/setup-node`) run it. The game service
serves `GET /game` (the page, `Http.resource("/client/index.html")`) and `GET /game/assets/{file}`
from the same resources; language bundles `client/src/i18n/{es,pt}.json`; the session's language
from the check, overridden by `?lang=`. This spec writes the layout and recipe as a contract
(`contracts/game-client.md`) and the test game has no client; 027 writes the first.

**Rationale**: FR-015 and constitution VI; the simulator already serves pages from resources
(`Http.resource`), and a flat asset directory keeps the route at one path parameter.

## R13 — The Anjouan profile

**Decision** (amending spec 009): `Profile.Anjouan extends Profile("ANJOUAN")`;
`Profiles.Anjouan = Profiles.Curacao.copy(profile = Profile.Anjouan)`; `byName += "ANJOUAN"`. The
"known profiles" answer gains `ANJOUAN`: a divergence from the original's recording of the unknown
profile refusal, named under *Changes*.

## R14 — Records and the audit

**Decision**: `Sweep` runs on `PROVIDER_RETENTION_DAYS` (default 90); operator acts publish the
contract's `platform.audit` family as `merchant` does, with the actor from the staff token;
metrics through `ankka-telemetry-otlp` counters named in FR-025. The day export is JSON lines of
settled rounds for a brand and a calendar day in the installation's reporting zone, read from the
`RunCallbacks` view's successor `RoundsByDay` (a view over `SessionEntity` keyed by brand and
settlement day).

## R15 — How the oracle is run

**Decision**: three suites hold the edge to the simulator's behaviour. (1) `OperatorApiParitySuite`
in the provider's build replays `tests/port/simulator/OperatorApi_*.json` against the provider with
the test game registered, with the one named difference that the launch answer's `game_url` host
is the test game's. (2) `ProviderIntegrationSuite` (Docker) starts the provider, the test game and
`FakeBrand` (moved from the simulator's tests into the library's test kit) and runs the rounds,
free rounds, keys and operators features. (3) On `dev-kind`, Goldvibe is configured with the
provider under `eitheror` beside the simulator under `st8`, and spec 003's `tests/port/game-provider/`
recordings whose provider side is the stand-in's are replayed by game-provider's kit with the
provider's key as the brand's provider key (quickstart). Spec 003's unit-level parity suites are
unchanged: they run against their scripted provider, and the amendment of R11 keeps them green.

## Declined remediations

(none yet)
