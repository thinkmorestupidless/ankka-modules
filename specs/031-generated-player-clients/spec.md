# Feature Specification: Generated Player Clients — Service Descriptions and the Clients Generated from Them

**Feature Branch**: `031-generated-player-clients`

**Created**: 2026-11-19

**Status**: Draft

**Input**: User description: "Move the services from 0.16.3 to 0.18.0; add route descriptions to
the player routes of players, wallet, ingest, game-provider, kyc and responsible-gambling; check
each route description in tests against the recordings in tests/port/<service>/; generate the
SDK's clients with ankka openapi --tag player, replacing the hand-written ones. The contract tests
from 027 (24 of 24 on the local brand) show whether the generated clients behave like the
hand-written ones. Moving to 0.18.0 is the riskiest step, because many services move at once."

## Context

Spec 027 wrote the player SDK's module clients by hand: per module a route table and the shapes of
what each route takes and answers, copied from the services' `contracts/routes.md` and checked by
one contract test per function against the running brand. It named the seam (027 FR-034): a client
generated from a service's own description replaces the hand-written one behind the same interface,
once an ankka release can write that description. ankka 0.18.0 is that release (ankka spec 067): a
service's description is read from the routes it registers, a route says what its types do not (a
summary, its refusals, the query parameters it reads for itself, a tag), a kept description is
checked against the routes in the build, and a test kit held to its description fails a test whose
answer the description does not allow.

What was read, 2026-11-19: ankka's `docs/build/openapi.md` and `OpenApiCommand.scala` at the tag
`v0.18.0`; every `build.sbt` and `service.json` here for its ankka version; the SDK's four route
tables and clients (`sdk/player/src/{core,casino,engagement,support}/`), its contract test
(`sdk/player/tests/contract/clients.test.ts`); and `tests/port/`.

What that reading found, which the description above did not have:

- Nine services are on ankka 0.16.3 (attribution, game-provider, kyc, payouts, players, public-api,
  responsible-gambling, rewards, wallet), three on 0.16.0 (audit, engagement, reporting), one on
  0.16.2 (support-agent), two on 0.14.2 (exchange, markets), and six on 0.13.0 (affiliate-programme,
  ingest, merchant, psp-gateway, provider, game-provider-simulator), as is the root build of the
  contract and the modules tool.
- The SDK's clients call eight services, not five of the six named: `rewards` (the free rounds a player holds),
  `engagement` (the push connection) and `support-agent` (the chat's turn and decision) beside the
  five. No function of the SDK calls `kyc` yet; it is described for the behaviour that will.
- `tests/port/` has a directory for players, ingest, kyc, game-provider, rewards, engagement and
  support-agent, and none for the wallet or responsible gambling. Recorded calls of responsible
  gambling's player routes are in `tests/port/engagement/` (23 recordings) and one recording of
  `tests/port/reporting/`; the wallet's parity suite names `tests/port/wallet/`, which does not
  exist.

Three decisions of 2026-11-19 bind the spec (Clarifications).

## Clarifications

### Session 2026-11-19

- Q: Which services move to ankka 0.18.0? → A: Every service, and the root build of the contract
  and the modules tool: one ankka version in the repository (FR-001).
- Q: Which services describe their player routes? → A: The six named and the three the SDK also
  calls: players, wallet, ingest, game-provider, kyc, responsible-gambling, rewards, engagement and
  support-agent, so that all four module clients are generated and no hand-written route table is
  left (FR-006).
- Q: What does a generated client replace? → A: The route tables and every wire shape (what a
  route takes and answers, and the parse at the boundary). The mapping to the SDK's own names stays
  written by hand over the generated calls, so each module client's interface is unchanged
  (FR-017 to FR-019).
- Q: Do the six proposed glossary terms stand (player route, route description, service
  description, description check, selection, generated module client)? → A: All six, as proposed.
- Q: How does the spec ship? → A: As two pull requests. The move to ankka 0.18.0 (story 1) is its
  own, raised first with every suite green and merged first; the descriptions, the description
  check and the generated clients (stories 2 to 4) are a second, raised on top of it (FR-005).
- Q: What of ankka 0.18.0 may the build use on the developer's machine? → A: The command-line
  client is upgraded to 0.18.0. No kind installation is used locally, because it is too heavy for
  the machine: what runs locally runs under `ankka local up`, the local brand among it. The
  suite that applies a brand to a kind installation is not run by this spec, locally or in
  continuous integration, which has no kind job (FR-025, Assumptions).
- Q: How do `exchange` and `markets` get onto ankka 0.18.0, when ankka-predictions 0.1.1 is built
  on 0.14.2? → A: They move to ankka-predictions 0.3.0, which is built on ankka 0.18.0 (FR-001).
- Q: The modules tool has no check of an installation's platform version against a module's
  runtime; the platform alone refuses, by starting nothing. Should the tool check? → A: Yes: the
  tool refuses the apply before anything is deployed, naming both versions (FR-004).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every service runs on one ankka version, and nothing anyone observes changes (Priority: P1)

A brand's services, the shared projects' services and the contract are built on ankka 0.18.0 and
run on its runtime. A player, an operator, a game provider, a payment provider and an affiliate
see what they saw before: every route answers as it did, every fact is written as it was, every
recording replays as it did. A brand's values name no runtime, so a brand takes the new runtime
with the modules' next release and changes nothing of its own.

**Why this priority**: The service description exists only from 0.18.0, so nothing else in this
spec can start before it; and it is the riskiest step, because 21 services move at once, six of
them across five minor versions.

**Independent Test**: Every service's unit, feature, parity and integration suites pass on 0.18.0
with no expectation changed; `just modules render` shows 0.18.0 for every service of every module
and shared project; the local brand `guyanabet` comes up and its player site signs a player in under `ankka local up`.

**Acceptance Scenarios**:

- added `features/brands/platform-version.feature`: every service, every module and the contract name one version of the platform
- added `features/brands/platform-version.feature`: a service moved to a new version of the platform replays every recording as it did before
- added `features/brands/platform-version.feature`: a brand takes a new version of the platform with its values unchanged
- added `features/brands/platform-version.feature`: a brand is refused on an installation that cannot run the modules' version of the platform

---

### User Story 2 - A service describes its own player routes (Priority: P1)

Each of the nine services a player site calls gives a service description of its player routes:
for each route its method and path, what it takes, what it answers, every refusal it makes, the
query parameters it reads, and who may call it. The description is derived from the routes the
service registers and from what each route says of itself, so it cannot say something the code
does not do. It is kept in the repository beside the service, and a build in which the routes and
the kept description disagree fails, naming each route that differs.

**Why this priority**: The description is what the clients are generated from and what the tests
are held to; without it the hand-written tables stay.

**Independent Test**: Write each service's description of its player routes; none has an
incomplete route; change one route's answer without updating the kept description and the build
fails naming that route.

**Acceptance Scenarios**:

- added `features/brands/service-descriptions.feature`: a service a player site calls gives a service description of every player route and of no other route
- added `features/brands/service-descriptions.feature`: a route description states what the route takes, what it answers, every refusal and who may call it
- added `features/brands/service-descriptions.feature`: a service description with an incomplete player route is refused naming the route
- added `features/brands/service-descriptions.feature`: a route that changes without its kept service description fails the build naming the route
- added `features/brands/service-descriptions.feature`: a route answered as a stream says so in its route description
- added `features/brands/service-descriptions.feature`: describing a route changes nothing the route answers

---

### User Story 3 - A route description agrees with the recordings and with every test (Priority: P1)

A route description is a claim about what the route does, and the recordings are what the original
did. Every recorded call of a player route is checked against the route's description: the status
the original answered and the fields of its answer must be ones the description allows, after the
same translation the service's parity tests already apply. Each of the nine services' test kits is
held to its description, so a test that causes an answer the description does not allow fails. A
route no recording calls is listed by name; its description is held by the service's own tests
alone.

**Why this priority**: A description nobody checked would be the hand-written table again, one
step removed; the recordings are this repository's acceptance test (constitution I).

**Independent Test**: Run the description check over `tests/port/`; remove one refusal from one
route description and the check fails naming the recording and the route; restore it and the check
passes.

**Acceptance Scenarios**:

- added `features/brands/service-descriptions.feature`: every recorded call of a player route is allowed by its route description
- added `features/brands/service-descriptions.feature`: a route description that omits a refusal a recording shows fails the description check
- added `features/brands/service-descriptions.feature`: a test that causes an answer the service description does not allow fails
- added `features/brands/service-descriptions.feature`: the player routes no recording calls are listed and the list is kept
- added `features/brands/service-descriptions.feature`: a recorded answer is checked as the parity test reads it

---

### User Story 4 - The module clients are generated from the service descriptions (Priority: P1)

The player SDK's four module clients take their route tables and their wire shapes from the kept
service descriptions. Nobody states a route's method, its audience or the shape of its answer by hand in the SDK any
more; a path appears by hand only where a selection names the route it means, and generation
checks that name against the description. Each module client's interface is the one spec 027 wrote, so no
behaviour, no component and no site changes. The 24 contract tests of spec 027 pass against the
local brand as they did, and they are the proof that a generated client behaves as the hand-written
one did. When a service's description changes, the generated part of the SDK is out of date and the
build says so until it is generated again.

**Why this priority**: It is what the spec is for: one statement of a route, in the service, read
by the service's tests and by the SDK alike.

**Independent Test**: Generate; the SDK holds no hand-written route row or wire shape; its unit,
feature and contract suites pass with no behaviour's test changed; change a kept description and
the SDK's build fails until it is generated again.

**Acceptance Scenarios**:

- added `features/player-site/generated-clients.feature`: a generated module client holds the routes its module client held when written by hand
- added `features/player-site/generated-clients.feature`: the generated module clients answer the running brand as the module clients written by hand did
- added `features/player-site/generated-clients.feature`: generating the module clients changes no behaviour
- added `features/player-site/generated-clients.feature`: a service description that changed leaves the generated module clients out of date and the build says so
- added `features/player-site/generated-clients.feature`: an answer that does not fit its generated shape is refused as a malformed answer naming the service
- added `features/player-site/generated-clients.feature`: a selection naming a route that no service description holds fails the generation naming the route
- added `features/player-site/generated-clients.feature`: a player route no selection names has no call in a module client and is refused by the site server

---

### Edge Cases

- A route builds its own answer, or reads its body as text it parses itself (responsible
  gambling's limit and break routes), so its types say nothing: its route description states the
  shape, and an undescribed one is refused (story 2).
- A route's refusal is thrown deep in a handler and named nowhere: the held test kit finds it the
  first time a test causes it (story 3).
- A row of a module carries a fixed query (the unread count reads the inbox with `status=unread`,
  one item of one page): the fixed values are the module's, the parameters' names and types are the
  description's.
- A route reads the brand from a query parameter and not from its path (the catalogue): the
  selection says so, and the parameter it names is checked to be one the description has.
- The cashier's routes are in the shared payments project, reached at its public address under a
  cashier ticket: their description comes from `ingest`, and the audience is the ticket's holder.
- Two routes of one service would have the same operation name: ankka refuses the service
  description naming both. That is the platform's behaviour and is not tested here.
- A service that registers a route only under some configuration gives a description that depends
  on it: the kept description is written under one fixed configuration, kept beside the service.
- The original answers a status the rebuilt service never does, by a divergence a spec names: the
  description holds the rebuilt service's answer, and the recording is read through the parity
  test's translation (story 3).
- An installation still on an older platform: the modules' release at 0.18.0 is not applied there
  (story 1).
- A service's journal and snapshots written on its earlier ankka version are read by 0.18.0
  without a migration; a service whose stored data 0.18.0 cannot read does not move until it can,
  and the spec is not done.

## Requirements *(mandatory)*

### Functional Requirements

**One ankka version**

- **FR-001**: Every service's build (21 services run on ankka; `player-site` is web-hosted and
  names no runtime), every `service.json`, every module manifest, every shared
  project's manifest, the local brand's service files and the root build of the contract and the
  modules tool MUST name ankka 0.18.0. No service is left on an earlier version, and the
  workarounds that let a contract built on one version run on another are removed. `exchange` and
  `markets` take ankka-predictions 0.3.0, the release built on ankka 0.18.0.
- **FR-002**: The move MUST change nothing a caller observes: no route's path, method, admission,
  answer or refusal, no fact's shape or topic, no stored type's wire name. Every unit, feature,
  parity and integration suite of every service MUST pass on 0.18.0 with no expectation changed. A
  test changed for a reason other than a renamed or removed ankka API is a finding.
- **FR-003**: The move MUST change no stored type, no serializer and no manifest name of any
  service, so that what a service stored on its earlier version is read on 0.18.0 without a
  migration step; a change the move forces on one is a finding the build stops at. No brand holds
  real money yet, so no suite replays data written by an earlier runtime: that check belongs to
  the Spinvibe migration (spec 019).
- **FR-004**: A brand's values MUST not change. A rendered brand MUST show 0.18.0 for every
  service. The modules tool MUST refuse to apply a brand or a shared project to an installation
  whose platform cannot run a runtime it names, before anything is deployed, naming the runtime
  and the installation's platform version; the platform's own rule decides (the same major
  version, and the platform's minor version or the one below it). An installation that reports no
  platform version is refused as one that cannot. This check is new here: until now the platform
  alone refused, by starting no instance of the service.
- **FR-005**: The move MUST be made in an order that keeps the root build and every service
  already moved building and passing at every commit, and every check of continuous integration
  green at its last: the contract first, then the services that already run a 0.16 version, then those on
  0.14.2, then those on 0.13.0, each service's suites green before the next group starts. The
  move is one pull request; the pull request that holds FR-006 to FR-024 is raised on top of it
  and merges after it.

**Service descriptions**

- **FR-006**: Each of `players`, `wallet`, `ingest`, `game-provider`, `kyc`,
  `responsible-gambling`, `rewards`, `engagement` and `support-agent` MUST give a service
  description of its player routes. A player route is a route a player site calls for a visitor, a
  signed-in player or the holder of a cashier ticket; it is marked `player`. A route of an
  operator, of another service, of a provider or of a payment provider is not a player route and is
  in no description this spec writes.
- **FR-007**: Every player route of the nine services MUST be described, whether or not the SDK
  calls it today, so that a later behaviour needs no change to a service to gain its client.
- **FR-008**: A route description MUST state: a summary in the glossary's words; what the route
  takes; what it answers on success; every refusal it makes, with its status and its reason; every
  query parameter it reads; and that its answer is a stream of events where it is one. Whatever
  the route's own types already say MUST not be restated by hand. The routes of these nine
  services read their bodies as text and build their answers themselves, so their types say
  nothing and every shape is stated in the route description; FR-015 is what holds each stated
  shape to what the route answers.
- **FR-009**: A route description MUST change nothing the route does: a described route answers
  byte for byte as it did undescribed.
- **FR-010**: A service description with an incomplete player route MUST be refused, naming the
  route and what it lacks.
- **FR-011**: Each service's description MUST be kept in the repository beside the service, and
  the build of that service MUST fail when its routes and its kept description disagree, naming
  each route added, removed or changed by its method and path. Continuous integration MUST run
  that check for all nine.
- **FR-012**: A service description MUST state who may call each route as the service enforces
  it: open to a visitor, a signed-in player's token, or a cashier ticket. Where the route's
  admission is a rule its admission alone does not show (the cashier's routes are open to the
  network and admit by the ticket they are given), the route description marks the audience.

**Descriptions checked**

- **FR-013**: For every recording under `tests/port/`, in whichever directory it lies, each call
  it holds of a player route MUST be checked against that route's description: the status and the
  answer's fields, after the translation the owning service's parity tests apply. A call the
  description does not allow fails the check, naming the recording, the route and what differed.
- **FR-014**: The check MUST report the player routes no recording calls. That list is kept by name
  under `tests/port/`, one file per service, and the check fails when the reported list and the
  kept list differ, in either direction.
- **FR-015**: Each of the nine services' test kits MUST be held to its service description, in
  every suite that serves the service's routes through that kit: an answer of a player route whose
  status or shape the description does not allow fails the run, listing each.
- **FR-016**: The wallet's parity suite replays nothing, because `tests/port/wallet/` does not
  exist, and the original has no wallet route a player calls. The wallet's three player routes
  MUST be on the kept list of FR-014 and held by FR-015.

**Generated clients**

- **FR-017**: The four module clients' route tables MUST be generated from the kept service
  descriptions: a row's service, method, path, audience, query parameters and whether it streams
  come from the description. What a description cannot say is kept in one hand-written selection
  per module and nowhere else: the row's id, the module it belongs to, that only the site server
  calls it, that the service is given the visitor's address, that the brand goes as a query
  parameter, a query or a path value the module fixes, and a path value's name where the SDK's
  differs from the service's.
- **FR-018**: Every wire shape a module client sends or reads MUST be generated from the kept
  service descriptions, with the parse at the boundary: an answer that does not fit its generated
  shape is refused as a malformed answer naming the service (027 FR-034's rule, unchanged).
- **FR-019**: Each module client's interface MUST be the one spec 027 defines, function for
  function. The mapping between a wire shape and the SDK's own (an amount from the wallet's major
  units, a terminal's names, a refusal's words) stays written by hand over the generated calls.
- **FR-020**: The module clients MUST cover the rows spec 027 FR-035 names and no others. A player
  route no row names has no generated function, and the site server's allow-list, which is the
  generated tables, admits no call of it.
- **FR-021**: The generated part of the SDK MUST be kept in the repository and marked as
  generated. The SDK's build MUST fail when it differs from what the kept descriptions generate,
  and continuous integration MUST run that check.
- **FR-022**: A selection that names a route in no kept description, or a description whose
  route a selection names with a method or path it no longer has, MUST fail the generation naming
  the row.
- **FR-023**: Generation MUST need no running service, database or broker: it reads the kept
  descriptions only.
- **FR-024**: Spec 027's contract tests MUST pass against the local brand with the generated
  clients, all 24, with no test changed; and no behaviour's, component's or reference site's test
  is changed (027 SC-009).

**Where it runs**

- **FR-025**: Everything this spec's acceptance needs on a developer's machine MUST run without a
  kind installation: each service's suites, the description check, the generation and its check,
  and spec 027's contract tests against the local brand under `ankka local up`. The suite that
  applies a brand to a kind installation is not run: its default runtime is moved to 0.18.0, and
  the evidence that a rendered brand names a runtime an installation runs is the render's own
  suite and the runtime check's (FR-004).

### Amendments

- **Spec 027 FR-034 and FR-035**: "one implementation written by hand in this spec" becomes the
  generated one of FR-017 to FR-019; the rows are unchanged. Research R5 of 027 (types "written by
  hand from the services' `contracts/routes.md`") is superseded by FR-018.
- **Spec 001**: the pinned ankka version is 0.18.0 for every project (FR-001).
- **Every service spec of the nine (002, 003, 006, 008, 009, 010, 011, 014, 017)**: its
  `contracts/routes.md` stays as the record of what was ported; the kept service description is
  what the build checks, and where the two disagree the description is right and the contract file
  is corrected.
- **`CLAUDE.md`**: the specs table gains this row; *Working here* gains how a description is
  written, checked and generated from, and loses the note on ankka 0.13's route limits where
  0.18.0 no longer has them.

### Changes from the original

- **The modules tool refuses a runtime an installation cannot run** (FR-004). The original has
  no modules tool; the check is new to this repository.
- **A service describes its routes.** The original publishes no description its code is held to;
  its clients were written from reading it. This is new, and it changes no answer (FR-009).
- **Nothing else.** The move to 0.18.0 and the generated clients change nothing a player, an
  operator or a provider observes.

### Key Entities

- **Route description**: what one route says of itself beyond its types: summary, shapes it
  builds itself, refusals, query parameters, the `player` mark.
- **Service description**: one service's player routes, derived from the routes it registers and
  their route descriptions; kept in the repository; the source of the generated clients and of the
  held tests.
- **Selection**: per module, the rows its client has, by id, each naming one route of one service
  description, with what a description cannot say.
- **Generated module client**: a module client's route table and wire shapes, produced from the
  selections and the service descriptions, under the hand-written mapping and the interface of
  spec 027.
- **The kept list of player routes no recording calls**: by name, one file per service.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All 21 services on ankka and the contract build on one ankka version; a search of the
  repository's builds and manifests finds no other.
- **SC-002**: Every suite that passed before the move passes after it, with 0 expectations
  changed and 0 recordings changed.
- **SC-003**: The nine service descriptions hold every player route of their services with 0
  incomplete routes.
- **SC-004**: 100% of the recorded calls of player routes under `tests/port/` are allowed by
  their route descriptions, and the unrecorded list matches the kept one exactly.
- **SC-005**: Spec 027's contract tests pass 24 of 24 on the local brand with the generated
  clients.
- **SC-006**: The SDK holds 0 hand-written route rows and 0 hand-written wire shapes (a
  selection's reference to a route, which generation checks, is not a row); 0 tests of a
  behaviour, a component or the reference site were changed.
- **SC-007**: A change to a player route's path, method, shape or refusals that is not carried
  into the kept description and the generated clients fails the build of the same pull request.
- **SC-008**: Adding a client function for an already described route needs a change to one
  selection and its mapping, and no change to a service.
- **SC-009**: `just features` reports 0 findings.

## Assumptions

- ankka 0.18.0's description covers request routes and server-sent event routes of Scala services,
  which all nine are. `player-site` is web-hosted TypeScript, has no description, and needs none:
  it is the caller.
- No kind installation is rebuilt or used on the developer's machine (Clarifications). An
  installation's own upgrade to platform 0.18.0 is its operator's step and not this spec's; a
  real-money installation takes the modules' 0.18.0 release only after its platform is at 0.18.0
  (story 1). The other worktrees' services stay on their versions until they are rebased on the
  first pull request.
- The ankka command-line client on the developer's machine is upgraded to 0.18.0, for the local
  brand. Continuous integration installs no client: a description is written and checked by the
  service's own build (research R4).
- ankka-predictions 0.3.0 is built on ankka 0.18.0 (Clarifications). What it changed since 0.1.1
  that `exchange` and `markets` must follow is found when they are moved, and a change to what
  either serves or stores is a finding (FR-002, FR-003).
- A stream's route description says the answer is a stream; ankka 0.18.0 gives no shape to the
  events in it, so the events of the push connection and of the chat stay shaped by hand in the
  SDK's mapping, and are not counted as wire shapes by SC-006.
- A kept service description carries no version of its service: a version in it would fail the
  check of FR-011 at every release.
- The description states the rebuilt service's behaviour. Where a spec already names a divergence
  from the original, the description follows the rebuilt service.
- Generated code is committed, so that a site builder reads it and a build needs no generation
  step; the check of FR-021 keeps it honest.
- Operator, service-to-service, provider and public-API routes are not described here. `public-api`
  (spec 016) keeps its own published description; bringing it under the same mechanism is a later
  spec.
- No behaviour gains a function in this spec: `kyc`'s routes are described and wait for the
  behaviour that calls them.
