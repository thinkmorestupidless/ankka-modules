# Research: Game provider simulator

**Spec**: [spec.md](spec.md) | **Date**: 2026-10-09

What was read: the original's stand-in `tests/fake-st8/main.go` (1,329 lines, standard library
only), its Helm chart and Playwright fixture; the original's ST8 adapter
(`substrate/casino-st8-adapter/.../St8Routes.scala`, `EcdsaVerifier.scala`, `application.conf`);
the Go harness `tests/e2e/scenario15_st8_wallet_e2e_test.go`; the incident of 2026-08-19
(`docs/incidents/2026-08-19-st8-open-rounds-property-truncation.md`); ankka 0.11.0's docs through
the plugin and its source under `modules/`; ankka specs 022, 023, 030, 032, 038 and 040; this
repository's `contract/`, `tools/brands/`, `tests/fixtures/sample-service/` and spec 001's plan and
contracts. Each decision names what established it.

## R1. The `tools` project is a shared project with a manifest

**Decision**: `tools` is deployed the way every other project is: `shared/tools/manifest.json`,
applied by the brands tool. `contract.Layout.SharedProjects` gains
`"tools" -> Vector("game-provider-simulator")`, `ReservedSlugs` therefore gains `tools`, and the
shared-manifest schema's two enums gain the project and the service. Spec 001 FR-003 is amended
from three shared projects to four; the amendment is one sentence and is made on this branch.

**Rationale**: `brands/<slug>/manifest.json` and `shared/<project>/manifest.json` are how anything
is deployed here (`CLAUDE.md`); a second deployment path for one service would be a path nobody
tests. The simulator has no topics and makes no grants, so its manifest is the project, one
service, its environment and `expose: true`.

**Alternatives**: `ankka services apply` by hand from the quickstart (rejected: no validation, no
status, not what spec 019's migration rehearsal will run); a brand-shaped manifest (rejected:
`tools` is not a brand, has no realm and no wallet).

**Established by**: `contract/src/main/scala/io/eitheror/casino/contract/Layout.scala`
(`SharedProjects`, `ReservedSlugs`), `tools/brands/.../Manifest.scala:158,172`,
`specs/001-brands-and-projects/contracts/shared-manifest.schema.json` (enums at `project.id` and
`services[].name`).

## R2. Operator routes are keyed by the brand they act on

**Decision**: every operator route of the simulator carries the brand it acts on
(`/v1/simulator/brands/{brand}/...`) and is declared `Routes.operator(brand)` with the segment's
value, exactly as a brand service declares its own. The two installation-wide operator routes, the
scenario library (FR-019) and the simulator's status, are declared `Routes.operator("tools")`: a
role `brand_tools` in the `eitheror` realm, or `platform_admin`, which the recipe admits for any
slug. No new recipe is added to `contract.access`.

**Rationale**: a scenario selection, a load profile, a run and its export all belong to one brand's
sessions, so the brand is already in the request; the operator who may read a brand's runs is the
operator who may run that brand. `platform_admin` reaches everything, which is who runs a test
installation today.

**Alternatives**: a new `Routes.tools` recipe admitting any `brand_*` role (rejected: a contract
change under spec 001 for one service, and it would let one brand's operator read another's runs);
`platform_admin` only (rejected: the recipe already admits it; naming `tools` costs one realm role).

**Established by**: `contract/.../access/Routes.scala` (`operator(slug)` admits `brand_<slug>` or
`platform_admin`), `specs/001-brands-and-projects/contracts/routes.md`.

## R3. The brand is reached with the JDK HTTP client, never ankka's service client

**Decision**: callbacks and operator-API answers to a brand go through `java.net.http.HttpClient`
at the brand's configured base address, with a per-request timeout (the provider's wait), the
`x-st8-sign` header and `Content-Type: application/json`. ankka's `ServiceClient` is not used for
any call to a brand.

**Rationale**: `clients.services(project, name)` resolves by name inside the cluster over mTLS and
presents the simulator's certificate, so the brand would see `Caller.Service("tools",
"game-provider-simulator")`: a platform identity, which FR-024 forbids, and the very thing 040
grants are about. It also cannot address an external hostname and has one service-wide timeout.
A request to `https://game-provider-<slug>.<base>` arrives through the gateway as `Caller.Gateway`,
"the internet", whoever sent it, which is what the brand's public, signature-verified provider
routes expect.

**Hazards, carried into the brand configuration (contracts/brand-config.md)**:
- TLS trust on a local installation: the gateway's wildcard certificate is signed by the local CA
  (`~/.ankka/local-ca.crt`), which the simulator's pod does not hold. A brand entry may name a CA
  PEM (`caPem`, from the descriptor's environment) loaded into the client's `SSLContext`. On GKE
  nothing is needed.
- Reachability from inside the same kind cluster: `*.127.0.0.1.sslip.io` resolves to loopback,
  which inside a pod is the pod. **Verified at the first `dev-kind` deploy (2026-10-11)**: a pod
  cannot reach a gateway hostname by its name (connection refused), and reaches it through the
  gateway's in-cluster service on 443 with the hostname kept as SNI and Host (200). The planned
  `connectTo` could not do that: the JDK client takes SNI from the URI's host, so swapping the host
  for the service's name breaks routing and the certificate, and the client has no resolver hook. It
  was removed. The installation arranges it instead: `installation/overlays/dev-kind/gateway-dns.sh`
  adds one CoreDNS rewrite, `*.127.0.0.1.sslip.io` to the gateway's service, and a brand entry names
  `https://game-provider-<slug>.127.0.0.1.sslip.io` with no port (inside the cluster the gateway
  listens on 443; 8443 is the host's mapping) and the local CA as `caPem`. Proved end to end: the
  deployed simulator's pod sent a signed `POST /st8/check` to a gateway hostname over TLS, the
  gateway logged it with that hostname as SNI and routed it. In ankka's local mode (a laptop,
  FR-026) the base address is `http://localhost:<port>` and neither hazard exists.

**Established by**: `build/calling-services.md`, `platform/networking.md` ("Who is calling", "What
is not isolated"), `modules/http/.../HttpServiceClients.scala` (wraps the JDK client).

## R4. The scheduler is one workflow per callback; the sweep is a timer

**Decision**: every callback the simulator sends is one `Callback` workflow instance (id: the
callback id) whose single step `send` signs and posts the body, records the attempt on the
`Callback` entity, reads the answer and *decides*: a terminal status word or the cap ends the
workflow; "error" or no answer within the wait pauses it for the scenario's next interval
(`thenPause(interval, send.ref)`). The retry schedule (intervals and cap) comes from the scenario
(FR-005); the brand's "error" is a value the step returns, never an exception, so the runtime's own
recovery never replaces the scenario's schedule. The step's timeout is the provider's wait plus a
margin, and the workflow's hard `timeout` is not used for the cap, because it fails the workflow
rather than ending it.

The default scenario sends synchronously: the instruction route calls the same `Sender` once, with
no retry, and returns the brand's answer verbatim as `fake-st8` does. The workflow is for anything
scheduled, delayed or retried.

A scripted scenario is driven by one `ScenarioPlay` workflow per session: it walks the steps,
starts a `Callback` workflow per `send`, waits for its outcome, asks the `Session` entity to record
the verdict, and pauses for `wait` steps. The load profile is one `LoadProfile` workflow per
profile: `launch` (check every session), `tick` (play the rounds due this interval, then pause),
`settle` (credit `0.00` for every open round; `stop` moves to it).

The retention sweep (FR-022) is a recurring timer (`sweep-runs`, hourly, set at every start) whose
`TimedAction` lists runs past the cutoff through the view, discards each (`persist(RunDiscarded)`
then `deleteEntity()`, so the view drops the row), and counts on the `SimulatorStatus` entity.
It returns `done` even with nothing to do.

**Alternatives**: a `TimedAction` per callback rescheduling itself (rejected: payload at most
1,024 bytes; a handler error is retried on the runtime's 3 s to 30 s schedule forever, so every
brand answer would have to be mapped to `done` by hand; no lifecycle to read; the sweeper is a
cluster singleton polled once a second, through which a retry storm would serialise);
`expireAfter(30.days)` on the run's entities for retention (rejected: an expired entity's view row
stays, and there is no count for the status).

**Established by**: `build/workflows.md` (`thenPause`, `stepTimeout`, "every step may run twice"),
`build/timers.md` (recurring timers, `TimerRuntime.MaxPayloadBytes`, error retry), ankka spec 032,
`concepts/designing-services.md`.

## R5. Sessions and callbacks are event-sourced entities; the run is a view; the verdict is state

**Decision**:
- `Session` (id: the launch token) is an event-sourced entity: brand, player id, currency, game
  code, wallet token, selected scenario, rounds (state and the transaction ids sent), the scenario
  cursor and the per-step verdicts. Rules about one session live here: a scenario is selected
  before launch and a second selection is refused (`Conflict`); a cancel names a transaction the
  session sent; one round per round id.
- `Callback` (id: callback id) is an event-sourced entity: `Scheduled`, `AttemptSent(attempt,
  keyId, sentAt, body)`, `Answered(attempt, status, balance, took)`, `GaveUp`. Recording an attempt
  is idempotent on `(callbackId, attempt)`, which is what makes a rerun of the workflow step safe.
- `RunCallbacks` is a plain view over `Callback`'s events: one row per callback with its attempts,
  queried by run id ordered by the time sent (`jsonNumber("sentAtMillis")`), with keyset paging
  on an `after` parameter, because the view API has a row limit and no offset.
- The verdict (expected against observed per step, passed or failed with the first failing step)
  is read from the `Session` entity, never the view: a decision reads its own writes.
- Every non-repeatable value (a timestamp, a generated transaction id, a seeded draw) is produced
  in the command handler and persisted in the event, never computed in `applyEvent`.

**Rationale**: the run record *is* the history, so event sourcing is the store and the view is
free; a key-value entity may skip intermediate values. A `Callback` entity of its own is the only
way to get one view row per callback ordered by time, since a plain view keeps one row per source
id.

**Established by**: `build/event-sourced-entities.md`, `build/key-value-entities.md` (the
comparison table), `build/views.md` (`ordered`, declared queries, no offset), ankka-entities rule 2.

## R6. Seeded randomness is ours; time comes from the caller

**Decision**: each run holds a `java.util.SplittableRandom` seed (FR-004); every draw (a win
amount, a delay in a range, which session acts) is made in a command handler or workflow step and
written into the event with its step index. Timestamps come from the step or endpoint into the
event. Tests drive the sweep through `TimerRuntime(pollInterval, probe, clock)` with a movable
clock; whether a workflow's `thenPause` honours that clock is not documented, so every scenario
delay is data and the test scenarios use milliseconds.

**Established by**: ankka spec 030 (refuses a seedable random), `TimerRuntime.scala:82-100`,
`TimedActionContext.clock`.

## R7. What `fake-st8` and the harness actually send, and where the spec was wrong

Read from `tests/fake-st8/main.go` and `scenario15_st8_wallet_e2e_test.go`; `contracts/st8-wire.md`
carries the field-level detail.

**Found**:
- The stand-in sends only `check`, `debit`, `credit` and `cancel`. `balance`, `player_profile`,
  `buyin` and `payout` are sent only by the harness, whose bodies also carry `player`, `site`,
  `developer_code`, `round_closed`, `bonus` and the nested `provider` object; the stand-in sends
  none of those. The default scenario therefore sends the stand-in's bodies and a scripted scenario
  the harness's, by a `shape` field on the step (`stub` or `harness`).
- Bodies are `json.Marshal` of a string map: keys sorted, every value a string, no trailing
  newline. Responses from `writeJSON` end in `\n` and HTML-escape `<`, `>` and `&`. Error paths
  are plain text with a newline. The simulator reproduces these so the original's suite, which
  reads them loosely, and byte comparisons of outbound answers both hold.
- Two outputs are in Go map order and change between calls: the `developers` and `categories`
  lists of the games listing, and which bonus a free spin draws from. The simulator sorts both
  (by id); a deterministic order is one of the orders the original produces. Listed under
  *Changes* as a tightening, not a divergence.
- `no_free_rounds` is HTTP 409 with body `{"error":"no_free_rounds"}`; `bonus/get` and
  `bonus/cancel` on an unknown id answer `{"status":"error"}` with no `error` field; `launch`
  answers `{"game_url": ...}` with no `status`; the launch token is placed in the URL unencoded.
- The stand-in registers its key by `POST <adapter>/st8/test/set-public-key` at start, 60 attempts
  2 s apart, and only then seeds the catalogue into the original's substrate and reports healthy.
- The adapter answers `auth_failed` before parsing; a malformed body is HTTP 400
  `{"status":"session_expired"}` (`player_not_found` for the profile); the status words are the
  nine spec 003 lists, with `spending_limit` passed through from the wallet.
- Signing: `ecdsa.SignASN1(sha256(body))`, base64, header `x-st8-sign`; the public key is PKIX
  DER base64. Signatures use a random nonce, so they are never byte-reproducible and a recording's
  `vary` must list the header.
- The incident record gives counts, not a schedule: 86 retries over 8 hours for one €0.03 win
  (about 5.6 minutes apart on average), 2,894 attempts in total. The spec's default schedule gives
  67 attempts in 8 hours; it stays the default as data (clarification of 2026-10-09).

**Spec corrections made in this pass** (as spec 001's plan corrected #662): Scenario 15 has 29
test functions, not 30; `TableSwitch` is a credit under a different game code within a round and
`NewParams` a balance read carrying two unknown fields, so the table-game assumption is rewritten
around `BasicBuyin`, `BasicPayout` and `CorrectionDebitNegativeBalance`; the stand-in's four
missing callbacks are recorded under *Assumptions*.

## R8. Running the original's suite against the simulator (FR-026)

**Decision**: a brand entry may carry `registerKeyAt`, the address of an original adapter's
`/st8/test/set-public-key`. When present the simulator pushes its current public key there at
start, 60 attempts 2 s apart, as the stand-in does, and after every rotation. It is absent for every
rebuilt brand, which reads the key document (FR-011, spec 003 FR-026). The simulator never writes
the original's catalogue: the quickstart triggers the original's own catalogue sync
(`run-catalog-sync`, a test endpoint of its casino service) against the simulator's games listing
before the suite runs.

**Rationale**: the original has no key document reader, and FR-026 requires the original's suite
to run against the simulator by changing one address. Seeding the substrate directly reached past
the provider (the spec drops the two hooks that did the same), and the original already has a
seeder that reads the provider's listing.

**Risk**: a test of the original that assumes the catalogue was seeded before the suite started
fails until the sync has run; the quickstart orders it first. If a test depends on the seeded
`thumbnail_url`, which only the stand-in's direct write set, that test is noted as blocked by this
change, not patched.

**Established by**: `main.go:530-548,645-685`, `St8GameSeeder.scala:113,128`, `St8Routes.scala`
(test endpoints behind `ST8_TEST_ENDPOINTS_ENABLED`).

## R9. Load-profile sessions are launched by the brand, not the simulator

**Decision**: a load profile is started with the launch tokens of sessions the brand has already
launched; the profile performs `check` on each, plays rounds at the rate, and settles open rounds
as losses when stopped or done. The brand's test kit (spec 003's, with spec 008's players) mints
the players and launches the sessions; the simulator's quickstart shows the two steps together.

**Rationale**: a session exists at the simulator only after the brand calls the simulator's launch,
and the brand's launch is a player route requiring a player token. Holding player credentials would
give the simulator an identity at the brand, which FR-024 rules out. The feature's "100 sessions
are launched" holds: the launches happen while the profile starts; the simulator's part is the check.

**Alternative**: the simulator holding test-player credentials per brand (rejected, above).

## R10. Scenarios, brands and the catalogue are data, not files

**Decision**: a service descriptor has environment variables but no file or ConfigMap mount, so:
- Scenario definitions are JSON documents (`contracts/scenario.schema.json`), shipped on the
  classpath under `scenarios/` and seeded into one `Scenario` key-value entity each at start; an
  operator adds or replaces one through `PUT /v1/simulator/scenarios/{name}` (FR-019, SC-005). A
  shipped scenario is re-seeded only when its `version` is higher than the stored one, so an
  operator's edit survives a restart.
- The brand table (FR-025) is one JSON environment variable, `SIMULATOR_BRANDS`, rendered by the
  brands tool from the manifest's `env`; the shape is `contracts/brand-config.md`.
- The catalogue (FR-001) is `games.json` on the classpath, the three games of R7, extended by
  `SIMULATOR_GAMES_EXTRA` (JSON) when an installation needs more.
- The signing key is in the secret store (`keys/<kid>`, `keys/current`, `keys/next`), read in a
  workflow step or endpoint, never in an entity; generated once at start when `keys/current` is
  absent.

**Established by**: `reference/service-descriptor.md` ("Fields you will not find"),
`reference/configuration.md`, `build/secrets.md`, ankka spec 038 FR-001 (the backend changes
nothing in the service).

## R11. Testing: the brand is a scripted service; the features bind in munit

**Decision**: unit tests with `EventSourcedTestKit` and `KeyValueEntityTestKit` for the entities;
everything with a workflow, view, timer or endpoint through `AnkkaTestKit.start(...)` with
`settings` pointing the brand table at a `ScriptedService` that answers, delays (`delay(d)`) or
fails (`failNext`) as each scenario needs and keeps every request for the step definitions to
verify the `x-st8-sign` header. `GherkinSuite("features/simulator")` binds the five feature files.
The parity tests the simulator leaves are one per outbound request shape the original's recordings
hold against `fake-st8` (launch, games, bonus create, get and cancel), replayed against the
simulator's operator API from `tests/port/simulator/`; the parity of the brand's answers under the
simulator belongs to spec 003's kit, which runs with the simulator as its provider.

**Established by**: `build/testing.md`, `modules/testkit/.../ScriptedService.scala`,
`modules/testkit/.../AnkkaTestKit.scala:247`, `specs/001-brands-and-projects/contracts/recording.md`.

## R12. Deferred, recorded so it is not lost

- Settled (R3): in-cluster reachability of a gateway hostname is the installation's CoreDNS
  rewrite on `dev-kind`. ankka's networking page could say what a service in one project should do
  to call another project's public routes from inside a local cluster; worth raising there.
- A "testing pot cap" scenario waits on a captured sandbox answer (clarification of 2026-10-09).
- The provider's documented retry schedule replaces the default as scenario data when it arrives.
- `Routes.operator("tools")` needs the `brand_tools` realm role in `installation/base`'s
  `eitheror` realm; `platform_admin` works without it.

## R13. What implementation changed, and why

Found while building and testing (2026-10-10); each is reflected in the code, its tests and, where it
touches a contract, the contract.

- **One scenario workflow per session, not one workflow per callback (R4).** A scripted scenario's
  sends are sequential, so `ScenarioPlay` (keyed by the launch ticket) sends, retries on the
  schedule by pausing, and judges, one scenario step per workflow step. A per-callback workflow would
  only have added polling between them. Every attempt is still recorded on the `Callback` entity. The
  default scenario sends from the instruction route, once, as planned.
- **A stop is recorded outside the load profile's workflow.** A workflow answers commands while a step
  runs (ankka `build/workflows.md`, "Queries while a step runs"), so a `stop` that moved the workflow
  was overwritten by the tick in flight and left rounds open. The stop is a `LoadStop` key-value
  entity the tick reads at its start; the tick then settles and ends.
- **Ticks are due from the profile's start, and a pause resolves to about a second.** A tick's own
  time no longer slows the rate; an interval under a second cannot be honoured (SC-004's is six).
- **Callback ids are the launch ticket and the step.** Ids drawn from the seed alone collided between
  two sessions on one seed and merged their runs; ticket plus step is unique and stable across a rerun.
- **A selection may fix the run's seed** (`PUT .../scenario {"name", "seed"}`), which SC-003 needs.
- **The launch names its brand by `site`.** The stand-in's launch carries no brand; the simulator
  matches the launch's `site` to a brand entry's `site` (default: its slug), or takes the only brand.
- **Free-round bonuses are one key-value entity (`bonus-book`).** A free spin searches every bonus for
  the player's usable instance; one entity answers that without a view, and the counts are small.
- **A cancel naming a transaction the session never sent is sent, not refused.** The stand-in passes
  any id through, and the harness's `StandaloneCancel` depends on it.
- **A load profile plays the default scenario's sends.** A scripted scenario under a profile would
  need a `ScenarioPlay` per session; a profile naming one is refused `400 scenario_not_instruction_driven`.
- **ankka 0.11's `TimerRuntime` takes no clock.** The retention test drives the sweep's logic with the
  core's clock (`Wiring`), and the recurring timer is set at start as planned.
- **An unbound Gherkin step fails its scenario** (it is not pending), so each story binds its whole
  feature when it is built, and what cannot run here is named in `ranElsewhere`: the original's
  recordings replayed through spec 003's kit, and the 100-session, five-minute profile (quickstart §7).
- **The session's check is recorded under the wire name `record-check`**, so the seam suite's ban on
  protocol words in the core holds for wire names too.

## Unknowns resolved

| Technical Context item | Resolution |
|---|---|
| How the simulator reaches a brand as an outside caller | R3: JDK client to the gateway hostname |
| Scheduler and retry component | R4: workflow per callback |
| Store and read model | R5 |
| Deterministic runs | R6 |
| Exact wire of the stand-in and the harness | R7, contracts/st8-wire.md |
| Original's suite against the simulator | R8 |
| Who launches load-profile sessions | R9 |
| Where scenarios, brands and games live | R10 |
| How `tools` is deployed | R1 |
| Operator ACL for a many-brand service | R2 |
