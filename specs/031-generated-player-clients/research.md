# Research: generated player clients

Read 2026-11-19: ankka at the tags `v0.13.0`, `v0.16.3` and `v0.18.0` (`docs/build/openapi.md`,
`docs/deploy/upgrading.md`, `modules/http/.../openapi/`, `modules/testkit/.../HeldToDocument.scala`,
`cli/.../openapi/OpenApiCommand.scala`, `controlplane-api/.../Compatibility.scala`, ankka specs 066
and 067); this repository's 21 service builds on ankka, the nine services' endpoints and test kits,
`tests/port/` (590 recordings), `tools/modules/`, `.github/workflows/ci.yml`, the `Justfile`,
`local/`, and the SDK's four route tables and clients.

## R1. Every service moves, because the platform runs two minor versions

- **Decision**: all 21 services on ankka, the root build and every manifest name ankka 0.18.0.
- **Rationale**: `Compatibility.supports` (and `upgrading.md`): a platform runs a runtime of its
  own major version whose minor is its own or one below. Platform 0.18.0 runs 0.17.x and 0.18.x; a
  descriptor naming 0.16.x or 0.13.x is `Unavailable` and no instance starts. A platform at 0.16
  runs no 0.18 runtime. An installation therefore moves whole, and so does this repository.
- **Alternatives**: the 13 services on 0.16.x and `ingest` (the first recommendation): leaves eight
  services no 0.18 installation can run.

## R2. What changes in a service's source

| From | Change a service must make | Source |
|---|---|---|
| 0.13.0, 0.14.2 | route builders take no type arguments (`post[String, Done](…)` becomes `post(…)`); handler parameters are annotated; a handler answering `Done` ends with `Done.done` | ankka `e09ff6ea7 feat(http)!`, 0.16.1 |
| 0.13.0, 0.14.2 | whatever 0.14 to 0.16.0 changed that the services on 0.16 already followed: read from the diff of a service that made that move (`players` at PR #38) | this repository's history |
| any | 0.17.0: `ankka local up` and `ankka context-map check` refuse a project whose context map has a topic crossing and no `topics.json`; `local/guyanabet/topics.json` exists | ankka `f78fe3924 feat(066)!` |
| any | 0.18.0: `FromBody.json` and `ToResponse.json` take `using ShapeOf.Maybe[A]`; a type with no shape makes a route incomplete, not a compile error. A hand-written `ToResponse` gains defaulted `shape` and `fixedStatus` | ankka `4f8a14dc5 feat(067)` |

- No rename or removal was found between 0.16.3 and 0.18.0 in ankka's main sources, and the journal
  and snapshot code is not in that diff. `upgrading.md`: schema changes are additive, applied at
  each instance start. No statement was found that data written by 0.13 is read by 0.18; FR-003
  therefore forbids a change to any stored type and leaves the replay of old data to spec 019.
- "Two endpoints may not share a prefix" still holds at 0.18.0; "at most two path parameters" does
  not (removed in 0.16.1). `CLAUDE.md` is corrected.
- The 15 services that lift sbt's early-semver check for ankka's artifacts (`libraryDependencySchemes
  … VersionScheme.Always`, because the contract was built on 0.13.0) lose that block: the root
  build's default becomes 0.18.0 and one version is on every class path.
- `exchange` and `markets` take ankka-predictions 0.3.0 (`PREDICTIONS_VERSION` default), built on
  ankka 0.18.0 (the person, 2026-11-19; the tag `v0.3.0` is on its remote). What changed in its
  API between 0.1.1 and 0.3.0 is read at task time (V7).

## R3. The order of the move, and its pull request

- **Decision**: one pull request, `031-ankka-0-18` against `main`, in this order, each group's
  suites green before the next: (1) the root build (contract, provider-core, modules tool) and its
  fixtures; (2) the nine on 0.16.3; (3) audit, engagement, reporting, support-agent; (4) exchange,
  markets, with ankka-predictions 0.3.0; (5) the six on 0.13.0; (6) module and shared manifests,
  `local/`, the tool's suites, CI comments, READMEs, `CLAUDE.md`; (7) the runtime check (R11).
  The move is built on `031-generated-player-clients`; `031-ankka-0-18` is that branch at the
  move's last commit, pushed and raised (T031). Work then continues on
  `031-generated-player-clients`, whose own pull request is raised on top of the first.
- **Rationale**: the person's decision (Clarifications). The contract first, because every service
  builds against the published contract. Manifests last, because a manifest naming 0.18.0 for a
  service still built on 0.13.0 is a lie between commits.
- **A service's suites** are `sbt test` and, where CI runs one, `sbt -Dit=true "testOnly …it.*
  …port.* …features.*"`: the commands `ci.yml` runs, so the local result is CI's.

## R4. A description is written by the service's own build, not by `ankka openapi <dir>`

- **Decision**: `just describe <service>` runs the service's own sbt build in document mode:
  `JAVA_TOOL_OPTIONS="-Dankka.document=openapi -Dankka.document.out=<file> -Dankka.document.tags=player
  -Dankka.document.strict=true -Dankka.service.name=<service>" sbt run` in `services/<service>/`,
  writing `services/<service>/openapi.player.json`. `just describe-check <service>` writes to a
  temporary file and compares byte for byte. CI runs the check in each of the nine services' jobs.
- **Rationale**: `OpenApiCommand.locate` treats a directory whose parent is named `services` as a
  member of one root build and runs `sbt <service>/run` at the repository root; here each service
  is a build of its own, so `ankka openapi services/wallet` runs the wrong build. The properties
  are the documented equivalent and are what the command itself passes; the output is the same
  file. `JAVA_TOOL_OPTIONS`, not `sbt -D`, because the command uses it and it reaches a forked run.
  The document is deterministic (fixed key order, two-space indent, final newline, no timestamp).
- **Alternatives**: `ankka openapi --check` (same `locate` problem); an in-process suite calling
  `OpenApi.build` (a second way to write the same file, and its API is not a documented one).
  ankka's `locate` rule is reported to ankka as an issue, not worked around further here.
- `--api-version` is not passed: the kept description's `info.version` is `0.0.0`, and the
  modules' release does not rewrite it (the spec's assumption that a release writes the service's
  version is dropped: a version in a kept file would fail the check at every release).

## R5. Document mode needs `main` to reach `start` with no environment

- `ServiceBuilder.start` writes the document and exits before it needs a database or a broker, but
  the nine services read settings from the environment first (`CASINO_BRAND`, issuer addresses,
  keys) and `Oidc.authenticate()` gives a no-issuer authenticator only when `ankka.document` is set.
- **Decision**: `just describe` exports a fixed environment per service, kept in
  `services/<service>/describe.env`: every variable the service's `Settings` demands, at values
  that are plainly placeholders, with the brand `the-brand`. Where a `main` does work before
  `start` that needs a live neighbour, that work moves behind `start` (V2).
- **The brand in a path**: most of the nine put the brand's slug in the endpoint's prefix as a
  literal, so a description written for `guyanabet` would name `guyanabet`. Written for
  `the-brand`, the generator turns that one segment into `{brand}`, which the site server fills
  (as it does today). `game-provider`'s launch has `{brand}` as a real parameter and needs nothing.

## R6. Every shape is stated, because no route of the nine has a type that says it

- Every body-taking player route of the nine reads its body as a `String` and every answer is a
  `Respond[Bytes]` built from a Jackson `JsonNode` (the wallet builds case classes and still
  answers bytes). Underived, every one would be `x-ankka-incomplete` (`request body`, `answer 200`).
- **Decision**: per service, `api/Described.scala` holds the case classes of what its player
  routes take and answer, with `ShapeOf` derived, used by the route descriptions alone:
  `described(_.summary(…).body[A].answers[B](200).refuses(409, "…").query[String]("currency").tag("player")) { … }`.
  Where a service already has the type (the wallet's `MajorBalance`), that type is used. A refusal's
  body is the service's own (`{error, message, …}`; the wallet's `RefusalBody`; ingest's), declared
  once per endpoint with `refusalShape[A]` in the endpoint's `description`; support-agent's and the
  stream's refusals are ankka's `AnkkaProblem`, the default.
- **Rationale**: FR-009 forbids changing what a route does, so the handlers stay as they are; the
  stated shape is held to the handler's bytes by the held test kit (R7), which validates every
  JSON answer against its schema. `body(jsonSchema: String)` was rejected: a schema as text has no
  compiler behind it.
- **What it does not give**: a stated shape can be looser than the handler (a field marked
  optional that is always there). The recordings (R8) and the SDK's contract tests are what catch
  one that is wrong in the other direction.
- **Scale**: 79 player routes: players 40 (36 + 3 + 1), wallet 3, ingest 3 (submit, abandon, the
  payment read; `POST /sessions` is called by `players` under the cashier key and is not a player
  route), game-provider 5, kyc 5, responsible-gambling 10, rewards 10 (`/free-rounds/eligible` is
  a service's), engagement 1, support-agent 2.

## R7. The held test kit, limited to player routes

- `HeldToDocument.server(server, into: Violations)` wraps a server; it checks a status the route
  does not name (400, 401, 403, 413, 500, 503 and 504 excepted as the platform's), a JSON answer
  against its schema, an accepted request body against its schema, and a query parameter read that
  is not declared. In Scala it fails after each test through the trait `HeldToDocument`, or at
  `assertHeld()`. It has no tag limit: its document is every served endpoint's, so an operator's
  or a service's route with an undescribed refusal is a violation too.
- **Decision**: each of the nine kits (one `HttpServer.at` and one `AnkkaTestKit.start` each, in
  `it/Kit.scala` or the wallet's `it/Harness.scala`) wraps its server through
  `contract.port.HeldToPlayerRoutes` (the contract's main scope, with `ankka-testkit` as a
  `Provided` dependency: every service has it on its test class path) and, when it closes, fails
  on the violations whose route is a player route of the kept description, listing each. Violations on other routes are
  dropped: describing 200 service and operator routes is not this spec.
- **V1** decides how the filter reads a violation's route. If a violation does not carry its
  method and path, the fallback is the endpoint's own `description` with the refusals every route
  of that endpoint makes, for the non-player endpoints of the nine, and no filter.
- Suites that name a kit: players 9 files, wallet 15, ingest 10, game-provider 6, kyc 4,
  responsible-gambling 2, rewards 9, engagement 7, support-agent 7. None changes: the kit does.

## R8. The description check

- **Recordings**: one JSON object per scenario (`scenario`, `requirement`, `steps[]`, each with
  `request{method, path, service, body}` and `response{status, body}`), loaded by
  `io.eitheror.casino.contract.port.Recording`. `request.service` is the original's host. 590
  recordings in 14 directories; calls of one service's player routes lie in many directories
  (players' registration is in 294 recordings; responsible gambling's routes are in 23
  recordings of `tests/port/engagement/` and one of `tests/port/reporting/`).
- **Decision**: `contract.port.DescriptionCheck` (beside `Recording` and `Replay`), given a kept
  description, a table of the original's routes (`method`, a path pattern, the operation it is),
  and an optional translation of a step: for every recording under `tests/port/` and every step
  the table matches, the status must be one the operation names and a JSON body must fit that
  answer's schema. It returns the differences and the operations no step matched. Each of the
  nine services has `port/DescriptionSuite.scala`: its table, its translation where it has one
  (`WireTranslation` in players, kyc, rewards, engagement), and two assertions: no difference, and
  the unmatched operations equal `tests/port/unrecorded-player-routes/<service>.txt`.
- **A named divergence**: where the service's `Parity.divergences` names a step, the check reads
  the step as the parity test does (translated, or skipped where the parity test skips it).
  ingest, game-provider and support-agent have no `WireTranslation`: their recordings are read as
  recorded. responsible-gambling has no parity package at all: its suite reads
  `tests/port/engagement/`'s and `tests/port/reporting/`'s recordings with the original's paths (`/v1/casino/rg/…`,
  the same as the rebuilt ones) and no translation; a difference found there is either a missing
  refusal in the description or a divergence, which is then named in spec 010's plan and in the
  suite (V4).
- **The wallet (FR-016)**: `PortSuite` reads `tests/port/wallet/`, which does not exist, and
  registers one ignored test; when its recordings exist (spec 002 T074) they are service routes,
  not player routes. The original has no player wallet route: its site read the balance through
  the casino's own route. The wallet's three player routes are on the unrecorded list and are held
  by R7 alone.
- **The validator**: ankka's `DocumentCheck` (`modules/http/.../openapi/DocumentCheck.scala`) is
  what `HeldToDocument` uses; `DescriptionCheck` uses it if it is public (V3), else
  `com.networknt:json-schema-validator` in the contract's main scope.
- **Unrecorded at the time of writing**, of the SDK's 27 rows: resendVerification,
  requestPasswordReset, startDeposit, country, acknowledgeRealityCheck, abandonCashier, games,
  game, freeRounds, balance. The full list over 79 routes is written by the first run of the check
  and reviewed line by line (T-level task), then kept.

## R9. What a description cannot say, and where it goes

| What | Where it comes from |
|---|---|
| open to a visitor / a signed-in player | the document's `security`: `[]` is public, `bearer` is player |
| admitted by a cashier ticket (ingest's three, served under `Routes.public`) | the route description's second tag, `ticket` |
| the push connection (a custom ACL, `Stream.acl`) | `bearer` if the ACL holds an authenticator; else the tag `signed-in`, which the generator reads as a signed-in player before it reads `security` (V5) |
| a stream | the operation's `200` content is `text/event-stream` |
| query parameters | the operation's parameters |
| a header a route reads (the payment read's ticket header) | nowhere: ankka 067 describes no header. The site server sends the ticket by the row's audience, as today |
| the row's id, its module, server-only, visitor, a fixed query, a fixed path value | the selection (R10) |

## R10. The generator

- **Decision**: `sdk/player/scripts/generate.ts`, run by Node 24 as `scripts/steps.ts` is, with no
  new dependency. It reads `services/*/openapi.player.json` and `sdk/player/src/<module>/selection.ts`
  and writes, per module, `routes.ts` (the table, `as const satisfies Routes`) and `shapes.ts` (a
  Zod parser and its inferred type per shape a selected row takes or answers), each headed
  `// Generated by scripts/generate.ts from services/*/openapi.player.json. Do not edit.`
  `npm run generate -w sdk/player` writes; `npm run generate:check` generates to memory and fails
  on a difference; `just player-sdk-test` and CI's `player-sdk` job run the check.
- **`client.ts` stays by hand** and becomes the mapping alone: it imports its shapes from
  `shapes.ts` and no longer declares a `z.object`. An eslint rule forbids `zod` imports and path
  literals under `src/<module>/` outside the generated files (SC-006), except `events.ts` (below).
- **JSON Schema to Zod**, the subset ankka writes: `object` with `required`, the scalar types,
  `array`, `enum`, `$ref`, a nullable type, `oneOf` discriminated by `"type"`, `additionalProperties`.
  Objects are not strict: an unknown field passes, as the codecs allow. Anything else fails the
  generation naming the schema.
- **Names**: a path parameter is renamed where the SDK's differs (`{playerId}` to `{player}`,
  `{gameCode}` to `{gameId}`), by the selection, so `Transport` and the site server's fill of
  `{brand}` and `{player}` are untouched. `turn`'s `{route}` is fixed to `chat_with_player` by the
  selection. `unread`'s fixed query stays in the row's path as it is today.
- **Streams**: the events of the push connection and the chat have no shape in a description
  (ankka gives a stream's `200` no schema). Their parsers move to `src/<module>/events.ts`, by
  hand, named as the exception in the eslint rule and in the spec's assumptions.
- **Leniency lost**: the hand-written parsers forgive (`partial().passthrough()`, `.catch(null)` on
  responsible gambling's interval). A generated parser is exactly the description. Where a unit
  test of a client relied on forgiveness the service never needs, that test changes and is listed
  in the pull request; behaviours', components' and the site's tests do not (FR-024).
- **Alternatives**: `openapi-typescript` (types only: no parse at the boundary, FR-018);
  `@hey-api/openapi-ts` or `openapi-zod-client` (whole clients we would discard, a dependency whose
  output shifts between versions under a byte-for-byte check).

## R11. The runtime check in the modules tool

- Nothing in `tools/modules/src/main` compares a runtime with an installation; `Readiness`
  evaluates `requires[]` for real-money brands only. `GET /installation` reports `platformVersion`.
- **Decision**: `Runtimes.unsupported(platform: Option[String], runtimes: Set[String])` in the
  modules tool, by ankka's rule (same major; the platform's minor or one below), called by every
  apply (brand and shared project, real money or not, local mode and vendor mode) before anything
  is deployed. The platform version is `GET /installation`'s when the control plane is asked, else
  the overlay's `PLATFORM_VERSION` in `installation/overlays/<name>/settings.env`, a new setting
  (V6 checks no existing one says it), set to what that installation runs today and never to
  what this spec would like it to run. It is read on its own, apart from the readiness facts, and
  the tool's apply suites read a fixture root whose overlay states 0.18.0. Not reported is refused. It lands in the first pull
  request, with the scenario it implements.

## R12. Where things run

- No kind (Clarifications). `ApplyOnKindSuite` (`just it`) is not run locally and no CI job runs
  it either: no job uses kind today. The rendered runtime is proved by `RenderSuite` and the
  runtime check's unit suite, and `ApplyOnKindSuite`'s default runtime is moved to 0.18.0
  untested. FR-025 says so.
- The local brand: `just local-images` (every service's image from this checkout), `just
  local-project`, `ankka local up` in `local/guyanabet/`, `just local-onboard`, `just
  player-site-it`. It runs 13 services; support-agent is left out, so the contract tests' chat
  cases see `unavailable` as they did in spec 027 (24 of 24 was measured so).
- The CLI: `brew upgrade thinkmorestupidless/tap/ankka` to 0.18.0, for `ankka local up` with 0.18
  runtimes. CI installs no CLI and needs none (R4).

## To verify at task time

| | What | If it is not so |
|---|---|---|
| V1 | a `HeldToDocument` violation carries its route's method and path | R7's fallback: endpoint-wide descriptions on the non-player endpoints |
| V2 | each of the nine `main`s reaches `start` in document mode under `describe.env` | move what runs before `start` behind it, in that service |
| V3 | `DocumentCheck` validates a JSON value against a document's schema from outside ankka | `json-schema-validator` in the contract |
| V4 | the recorded calls of responsible gambling's routes pass against its description untranslated | name the divergence in spec 010's plan and the suite |
| V5 | the push connection's ACL yields `bearer` in the document | the tag `signed-in` |
| V6 | no overlay setting already states the platform's version | use it, add none |
| V7 | ankka-predictions 0.3.0 changes nothing `exchange` and `markets` serve or store | a finding; stop and ask |
| V8 | `--strict` with a tag refuses only incomplete routes that carry the tag | describe to completeness whatever it names, or write without strict and assert completeness in the check |

## Design finished

2026-11-19: the person proceeded with 3 findings above LOW, all MEDIUM, from the third analysis
round; each was fixed after that round and not analysed again:

- T029: reading the platform's version through the control plane's facts would have switched
  where every readiness fact comes from, and the apply suites had no way to a fixture overlay.
  Fixed: the version is read on its own, and `Fixtures` gains a second root.
- T028: the step "no recording was changed" had no definition. Fixed: it asserts every recording
  loads, and the comparison with `main` is T031's evidence.
- `contracts/runtime-check.md`: "an overlay is never set to 0.18.0" against the fixture overlay.
  Fixed: the rule is an installation's; a fixture's overlay is not one.

## Found during implementation

- **T001** (2026-11-19): `brew upgrade thinkmorestupidless/tap/ankka` installed the command-line
  client 0.18.0 (`ankka version` prints `0.18.0`).
- **T003, the 0.13 to 0.16 changes, from players**: PR #38 (`f05f51d8`) moved wallet, players, kyc,
  responsible-gambling, payouts, attribution, game-provider and rewards from 0.13.0 to 0.16.3 with
  "no source change": the library version, each descriptor's runtime, the manifests, and the
  eviction block for the contract's 0.13 artifacts. Its suites passed except players' `RealmSuite`
  p99 bound and payouts' `RequestSuite` and `ScaleSuite`, which failed the same way on `main` at
  0.13 on this machine; payouts' SC-005 check was narrowed to its own requests' gateway calls,
  because on 0.16 earlier suites' polls landed in its window. ankka 0.16.1's breaking note (type
  arguments on route builders, annotated handler parameters, `Done.done`) touches none of the
  eight services still on 0.13.0 or 0.14.2: none writes a route builder with type arguments
  (`grep` of `get[`, `post[`, `postBody[` and the rest under each `src/`: 0 in each). The
  checklist for T018 to T024 is therefore: the version, the descriptor, the eviction block, and
  whatever the compiler then reports.
- **T018** (2026-11-19): ankka-predictions 0.3.0 is on Maven Central and its POM names ankka 0.18.0
  (`ankka-sdk_3`, `ankka-testkit_3`); exchange and markets default to it and compile unchanged.
- **T005 to T024, the move**: every service changed only its `ankkaVersion`, its descriptor's
  `runtime` and, for the 15 that had it, the eviction block (`libraryDependencySchemes …
  VersionScheme.Always`) with its comment: the contract is now on the same ankka, so nothing is
  evicted. All 21 compile with no source change. public-api's Jackson pin stays 2.21.6: ankka 0.18.0
  brings pekko-serialization-jackson 1.7.1, as 0.16.3 did.
- **T025, the fixtures**: the manifests under `tests/fixtures/manifests/` and
  `MT/test/resources/manifests/predictions.json` move to 0.18.0, so the runtime check passes them.
  `MT/test/resources/spec-001-goldvibe/plan.json` keeps 0.13.0: it is what spec 001's tool rendered,
  and `RenderSuite` compares every field but the runtime with it. `tests/fixtures/sample-service/`
  keeps 0.13.0 too: nothing builds or applies it, and its descriptor names the runtime its own build
  is on.
- **T029, `gke`'s platform**: asked; the person answered that `gke` runs platform 0.18.0, so its
  overlay states `PLATFORM_VERSION=0.18.0`; `dev-kind`'s states 0.16.3. The fixture installations
  under `tests/fixtures/installations/` state 0.18.0 and say they are fixtures. `GET /installation`'s
  `platformVersion` is a top-level string (ankka `controlplane-api` `Installation`), read first when
  present. The rule mirrors ankka's `Compatibility.supports`; anything after `+` or `-` is dropped,
  so a snapshot platform's version parses. The check reads every module of the plan, held ones
  included, because a held module is applied on the same platform once its keys are set.
- **T028, scenario 2**: bound as `ranElsewhere`, as `RecordingFeatures` binds the runs it does not
  make, naming the parity suites and T031's evidence. The step "no recording was changed" has no
  definition: a definition here could assert only that the recordings load, which is not what the
  step says. `Fixtures.applyRoot` is a temporary tree of links to the repository with only
  `dev-kind`'s `settings.env` copied.
- **T032, V1, V3 and V8 at ankka `v0.18.0`** (read from the tag, `modules/testkit/…/HeldToDocument.scala`
  `HTD`, `modules/http/…/HttpServer.scala` `HS`, `modules/http/…/openapi/OpenApi.scala` `OA`):
  - V1: `HeldToDocument.server` wraps the server and changes no answer (HTD:57-59). It checks each
    answer's status and body (HTD:130-152), a request body when the answer is below 400
    (HTD:154-164), and query parameters a handler reads that the description does not declare
    (HTD:166-175). A status the description does not name is a violation unless it is one of
    400, 401, 403, 413, 500, 503 and 504 (HTD:73, 135-137), or the operation names only `default`. A
    streamed body is not checked (HS:1175-1177). A violation is a `String` naming `METHOD /template`, the
    status and networknt's messages, never the body (HTD:126-163); `assertHeld()` throws
    `NotHeldToDocument`, a `RuntimeException` (HTD:41-44, 181-184). The route is the matched route's
    template, prefix included, looked up as an exact key of the served document (HS:951-955,
    HTD:87-93), so a brand in a prefix is a literal on both sides and matches; an unmatched request
    is not reported. `HeldToPlayerRoutes` therefore reads the method and path out of each violation's
    text, keeps those that are an operation tagged `player` in the served document, and fails with
    them as an `AssertionError`.
  - V3: no public validator. `DocumentCheck` is `private[ankka]` and a listener (DocumentCheck.scala:8-19).
    Fallback, as research.md names it: `DescriptionCheck` validates with `com.networknt:json-schema-validator`
    1.5.9, the version ankka's testkit brings, at draft 2020-12, the operation's schema with the
    document's `components` copied in, as HTD:77 and 119-123 do; the contract takes it at that version.
  - V8: strict refuses only the incomplete operations the tag filter keeps (OA:90-99, 123-124); a tag
    no route carries is an error (OA:128-129). An operation is incomplete only for a `request body` or
    an `answer <status>` whose type has no shape and none described (OA:209-211, 245-249, 315); a
    missing summary or tag is not incomplete, so the descriptions' summaries are held by T046's
    outline, not by strict mode.
- T037 (the wallet): a `Respond[Bytes]` route's described answers came out under
  `application/octet-stream`. With no fixed status, ankka takes the route's own content type for
  them (`OpenApi.scala:254-255`), and `Bytes` reports octet-stream because its real type is chosen
  per value. Fixed on our side with `contract.http.Json`, a JSON body with its own `ToResponse`
  (`application/json`), the same bytes and header on the wire. It belongs in T062's issue draft:
  line 256 already uses `describedContent` for a fixed status, and 255 could do the same.
  `DescriptionCheck.recordings` skips a JSON file with no `steps`, such as the programme's
  `import/bundle.json`.
- T038 (responsible gambling): a route reading its own JSON as `String` files a described body
  under `text/plain` (`FromBody[String]`), so `contract.http.Json` is a `FromBody` too
  (`application/json`). ankka reads the bytes whatever the request's type, so callers are unaffected.
  The description check found two things the original's recordings lack: a refusal's `message` and a
  sum's `currency`. Both are optional in the description, since a field the original never sent is
  not one a client may rely on; the rebuild's answers, which have both, are held by the kit.
- T039 (ingest): ingest relays merchant's answers, so its description vouches for what ingest
  guarantees: a payment's `status`, a submit's `paymentId` and `status`. Every other field of the
  read is optional. The kit's `FakeMerchant` answers reads with fewer fields than merchant, and the
  hold failed four suites on it until then. Ingest's kits are plain classes that some suites start
  lazily, so `it.Held` asserts on the kits started (`Kit.started`) and starts none.
- T040 (game-provider): a route that checks the brand (`ownBrand`, `?brand=`) refuses another
  brand with ankka's problem (`{status, error, details?}`). Its `error` is the service's
  refusal shape's one required field, so each such route names `404 wrong_brand` and needs no shape
  of its own. The launch and the popular games keep `{brand}` as a path parameter, not the literal:
  their prefixes do not hold the brand.
- T041 (kyc): `WireTranslation` runs from the original's requests to the rebuild's and from the
  rebuild's answers to the original's, never back, so the original's recorded answers (`docType`,
  `kycStatus`) cannot be read as the rebuild's. `port/DescriptionSuite` therefore checks each
  translated step's status only; the bodies are checked by `ParitySuite`, which replays the same
  steps through the held kit, so the rebuild's own answers to the original's requests meet the
  description. All five routes are recorded (the unrecorded list is empty).
- T042 (rewards): a 409 is either a refusal `{error}` or the evaluator's decline as the original
  answered it, `{status: "ineligible", reason}`, so the select and the code entry give their 409 a
  shape of its own (`refuses[Conflict]`). As for kyc, the recordings are read through
  `WireTranslation` for their statuses and the parity replay holds the bodies. An operator's
  recorded offer or grant body holds `{captures}` where amounts go, which the translation cannot
  parse outside a replay; such a step is translated by its path alone, since no player route's
  translation reads an amount. `/free-rounds/eligible` is game-provider's and is not described.
  rewards, engagement and support-agent run their kit suites only under `-Dit=true`.
- T043 (engagement), V5: ankka writes `bearer` only for an ACL holding a `TokenAuthenticator`
  (`OpenApi.access`). `Stream.acl`, and `Routes.player` in every service, wrap the authenticator in
  a function, so no signed-in route of any kept description has a `security` entry; a public route
  has `security: []`. The stream is tagged `signed-in` as V5 decided, and the generator's last rule
  (no `security` is a signed-in player) covers the rest. The stream's refusals are ankka's problems,
  so the endpoint sets no refusal shape. Its path is the original's (`/v1/stream`, host `push`), read
  as recorded for status.
- T044 (support-agent): the service does not start, so writes no document, without its issuers'
  settings and a model-provider key; `describe.env` holds placeholders for them (addresses under
  `auth.invalid`, a key that says it is none), nothing being fetched or called while the document
  is written. A stream route's body taken as `String` is `text/plain` in the document, as an
  answer's was; `sseEventsBody` takes the contract's `Json` the same way. The chat's last segment
  stays a path parameter (`/run/{route}`), the one value served being `chat_with_player`.
