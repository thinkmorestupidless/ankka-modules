# Implementation Plan: players

**Branch**: `008-players` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/008-players/spec.md`

## Summary

`players` is the brand project's account service and the hub the other brand services hang off: it
registers a person as a player of the brand, signs them in and out through the brand's realm, keeps
their profile, addresses, consents, favourites and inbox, opens and ends player and game sessions,
holds the account status and its trail, detects duplicate accounts, and files erasures and assembles
exports across every service. It is the one service holding the realm's administrative credential,
the one the realm's authenticators ask, and the one `game-provider`, `kyc`, `responsible-gambling`,
`engagement` and `support-agent` call for a player's state.

One event sourced entity per player and one per player session, game session, message and fraud case;
key value entities for notes, the lookup index and the three configurations; four workflows
(registration, session end, erasure, export); a timed action per game session, one recurring timer for
the realm; seven consumers; eight views; six endpoints. Decisions and their evidence are in
[research.md](research.md); the reading of the original's suite that replaced the spec's Recordings
list with a driver is R1.

## Technical Context

**Language/Version**: Scala 3.9.0 on ankka 0.13.0 (constitution VI; the versions every service here
pins). Go for the recording driver, in the original's toolchain (`tests/original/README.md`). Java for
the three Keycloak plugins' changes, in the original's repository (`contracts/realm.md`).

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract`: `Routes.public`, `Routes.player`, `Routes.operator`,
  `Routes.service`, `Personal`, `PlayerSubject`, `CasinoPlayers` and the `casino.sessions` families,
  `PlatformAudit`, `port.Recording`. **Contract changes** (additive, `contracts/facts.md`): the
  `player_reopened` family, `superseded` as a close reason, `Routes.playersOnly(slug)`;
- the JDK's `Mac` (HMAC-SHA256 for the verification token and the lookup tokens) and `MessageDigest`
  (the registration workflow's id);
- Keycloak 24's admin and token endpoints over the service's HTTP client (R13); no Keycloak library.

**Storage**: the service's own CNPG Postgres from ankka (journals, durable state, view tables, the
workflows' state, the timers). Secrets from the project's secret store (ankka 038), named in R17;
none in a row, an event or a fact.

**Testing**:

- munit; `EventSourcedTestKit` for the six event sourced entities, `KeyValueEntityTestKit` for the
  five key value ones, `ConsumerTestKit` for the seven consumers, `WorkflowTestKit` for the four
  workflows (the realm and the other services as traits with fakes);
- `AnkkaTestKit.start` over Postgres with `FakeRealm` (Keycloak's admin and token routes, scripted),
  `FakeRg`, `FakeKyc`, `FakeEngagement`, `FakeIngest`, `FakeWallet` under `localServices`,
  `InMemoryBroker` for the seven topics, `TimerRuntime(200.millis)` and a settable clock;
- `GherkinSuite` binding the six `features/players/*.feature` files (63 scenarios);
- `contract.port.Replay` over `tests/port/players/` through `WireTranslation` (`contracts/wire.md`).

**Target Platform**: every brand project on the self-hosted installation (kind locally, GKE), as the
core module's `players` entry already declares (`modules/core/module.json`: exposed, runtime 0.13.0,
the wallet's env keys plus R17's). The `/internal/realm/<slug>` routes are reached by the
installation's Keycloak inside the cluster.

**Project Type**: one ankka service (`services/players/`), one Go driver
(`tests/original/players-driver/`), one contract change, a manifest change, a realm template change,
a plugin change in the original's repository and the installation's Keycloak image.

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-002 | a suspended, excluded or closed player refused by `/may-sign-in` and by `/login` within 2 s of the transition (the entity answers; no view in the path) |
| SC-003 | every open game session ended within 5 s of the player session (the `SessionEndWorkflow`'s three steps; the session-end bound) |
| SC-004 | `/may-sign-in` under 100 ms at p99 at the brand's peak sign-in rate: one entity read plus `may-log-in` under its 80 ms bound, or the cached refusal |
| SC-005 | a duplicate flagged and its case open within 10 s of the second registration, by two index lookups, no scan |
| SC-006 | after `Erase`, no `Personal` field readable in any row, any event's projection or any fact; amounts and statuses unchanged (`LeakSuite`) |
| SC-007 | registration, sign-in and profile routes call only the realm and, at sign-in, `may-log-in`; game session open calls nothing (`FakeX` call counts) |

**Constraints**:

- six endpoints with no shared prefix, at most two path parameters per route, ACL per route, the
  brand slug a literal in each prefix (R14);
- the player id minted, never derived; the registration workflow keyed by the address so two
  registrations of one address make one player (R3);
- every field that identifies a person a `Personal` value in state, events, rows and facts; nothing
  personal in a log, span, audit record or timer name; no token the realm issued stored (R8);
- the realm asked by `players` only, with the credential only `players` holds (FR-007); the three
  internal routes verified by their secrets, not by a platform identity;
- the authenticator's answer bounded at 80 ms for `may-log-in` and refused on no answer (fail closed);
  sign-in's `may-log-in` bounded at 500 ms, the same way (R4);
- the deposit route's four calls in order with bounds, none a workflow (R15);
- erasure without ankka 042: the marker and the redaction are this service's; the journal gap is named,
  not hidden (R8).

**Scale/Scope**:

| Item | Count |
|---|---|
| Player routes | 34 at 1 prefix (`PlayerEndpoint`), 3 at `SessionEndpoint` |
| Operator routes | 36 at 1 prefix, including spec 021's two modules rows |
| Service routes | 10 at 1 prefix; realm routes 3; geo 1 |
| Event sourced entities | 6 (player, player session, game session, message, fraud case; `LookupIndex` is key value) |
| Key value entities | 5 (note, lookup index, brand config, currency config, allow-list) |
| Workflows | 4 |
| Timed actions | 2 (`SessionLimits`, `RealmReconcile`) |
| Consumers, views | 7, 8 |
| Feature scenarios bound | 63 (`registration` 8, `sessions` 18, `profile` 12, `status` 11, `duplicates` 7, `erasure` 7) |
| Recordings replayed | one per driver scenario the original can show (R1; `contracts/surface.md`'s `kept` rows), plus the four Scenario 18 registrations |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings and the parity tests it leaves; divergences declared | Pass. R1 replaces the spec's list of Go scenarios, read and found not to reach the player routes, with a driver recording each feature scenario the original can show; one parity test per recording through one documented translation (`contracts/wire.md`). *Divergences* below lists every change with what demonstrates it. `contracts/surface.md` disposes of every route and non-route of the original's player code. |
| II. Money is exact, the wallet is the ledger | `Long` minor units; every money command keyed; no balance kept | Pass. `players` holds no money. The deposit route passes `amount` through as a `Long`; `WalletSummaryView` folds `money.transactions` into a read, never a balance of its own (FR-034). |
| III. A brand is a project | Keys carry no brand; the realm per brand; shared projects by grant | Pass. Every entity id is brand-free; the slug is in the route prefix and the realm name. `merchant`'s erasure route is reached under the brand's erasure grant (R10); `ingest`'s cashier route as the internet reaches it, under the merchant key. The realm reaches `players` through public routes verified by per-realm secrets. |
| IV. Commands over HTTP, facts over topics | No store read across services | Pass. Every exchange with the realm, `responsible-gambling`, `kyc`, `engagement`, `ingest` and the erasure and export routes is a call. Every fact comes from a consumer folding events (R7); every read of another service's data is a view over its topic (R11). |
| V. Personal data marked, erasure honoured | `Personal` everywhere; the erasure handler | Pass with a named gap. Every identifying field is `Personal` (data-model.md); `Erase` blanks state, views redact, the realm user is removed. The journal's earlier events keep their `Personal` values until ankka 042 lands: the gap is R8's and the pilot blocker the brain already records, not a workaround. |
| VI. Scala by default | | Pass: Scala. Go for the driver; Java for the plugins because they are Keycloak's. |
| VII. Specs in the domain's words | `just features` passes; scenarios live in the features | Pass: 0 findings in 1,108 scenarios after the 2026-10-25 clarification. |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (three sessions) and 2026-10-25. |
| Scope: ankka gaps are ankka specs | | Pass. 042 (erasure, `Personal`, lookup tokens) is the one gap; it is an ankka spec, and this plan builds what runs without it and names what waits (R8, *For ankka*). |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001 to FR-004, FR-036: registration | `RegistrationWorkflow` (id = hash of slug and address), awaited 5 s; steps mint → realm → player → match → email, `undo-realm` compensation | the realm user is first (FR-002) and must be undone on a failed write; the address as the id makes a race one player; the match is a step so the case opens within SC-005 without a scan | the endpoint calling realm then entity (a crash leaves an orphan, as the original does); an entity keyed by the address (the id must be minted, Clarifications) (R3) |
| FR-005, FR-016's email, FR-026 | the stateless HMAC token (`players-email-token`), `PlayerEntity.VerifyEmail`, `EmailAddressVerified` | the original's choice, kept because verification is idempotent; nothing stored to leak or expire | a token entity per send (a row holding the address; the original moved off it) (R16) |
| FR-010 to FR-015: sign-in and sessions | `PlayerEntity.SignIn`, `PlayerSessionEntity`, `SessionEndWorkflow`, `Realm` client | the status refusals before any realm call cost; the cascade of a session's end survives a crash as a workflow | ending the games in the endpoint (a crash leaves them open past SC-003) (R4, R6) |
| FR-013, SC-004: the authenticator's route | `RealmEndpoint./may-sign-in`, `X-Realm-Secret`, `may-log-in` bounded 80 ms, fail closed | one entity read and one bounded call fit under 100 ms; the secret is per realm | the plugin reading a view (constitution IV) (R4) |
| FR-009: the reconciler | `RealmReconcile` recurring timer (60 s) → `SessionEndWorkflow(expired)` | the original's reconciler as a durable, replace-by-name timer | a consumer of the webhook alone (the listener never sends `SESSION_EXPIRED`) (R5) |
| FR-017 to FR-020, FR-022: game sessions and the limit | `GameSessionEntity`, `PlayerEntity.GameSessionOpened` (answers minutes left), `SessionLimits` timed action, one timer per session, `RgLimitsConsumer` | the minutes left are known at open, so one timer per session replaces the hourly scan; a new limit reschedules by name | the hourly scan as a recurring timer (late by up to an hour; the original's TL rule is empty) (R5) |
| FR-023 to FR-029: the profile | `PlayerEntity` (`EditProfile` dropping locked fields, `CorrectDateOfBirth`, emails, consents, cookies, favourites) | one aggregate, one fold, one `Erase` | an entity per concern (five erasure handlers for one subject) (R2) |
| FR-030: the inbox | `MessageEntity`, `MessagesView`; `ServiceEndpoint./messages` for `engagement` | the delivery id as the entity id makes a repeat send one message | messages as a list on the player (unbounded state) (R2, R11) |
| FR-031 to FR-033, FR-032a: status | `PlayerEntity`'s transitions and `trail`; `Realm` enable/disable/revoke | the trail is the entity's own; every transition one event and one fact | a status entity apart from the profile (two subjects for one person) (R2) |
| FR-034: notes, wallet summary, activity | `NoteEntity`, `NotesView`, `WalletSummaryView`, `ActivityView` | notes are personal data under the player; the wallet read is a fold of its facts | reading the wallet's store or route in the request path (constitution IV; SC-007) (R11) |
| FR-035: the facts | `PlayersPublisher`, `SessionsPublisher`, `AuditPublisher` | constitution IV as every service here does it; `ce-id` from the event | a produce from the handler (R7) |
| FR-036, FR-037, SC-005: duplicates | `LookupIndexEntity` (id = token), `FraudCaseEntity`, `FraudCasesView`, the `match` step | two reads by token, no scan; the case is its own subject with two players | the original's `MultiAccountDetector` scan; ankka 042's lookup tokens (not in 0.13) (R9) |
| FR-038, FR-039, SC-006: erasure | `ErasureWorkflow` (file → hold → apply → realm), `WithdrawalsConsumer` for the hold, `Erase` | the fan-out and the hold are durable; each service's certificate kept | ankka 042's erasure request (not in 0.13); an endpoint loop (a crash erases half) (R10) |
| FR-040: the export | `ExportWorkflow`, `GET /exports/players/{id}` on every brand service | each service names its own part; a silent one is `missing`, not a failure | `players` reading other services' views (constitution IV) (R10) |
| FR-041: configuration | `BrandConfigEntity`, `CurrencyConfigEntity`, `AllowListEntity`; `ServiceEndpoint./config/{name}` | key value, one per brand, served in-project | configuration in the manifest's env (an operator cannot change it) (R12) |
| FR-043: starting a deposit | the handler in `PlayerEndpoint`, four bounded calls in order | no state of its own; a workflow would answer slower than the cashier's own bound | a workflow (R15) |
| FR-006: geo | `GeoEndpoint`, the original's lookup, `null` on failure | public, stateless | — |
| FR-042: per-player rate limits | none here but the verification resend (`lastSentAt` on the entity) | the spec places every other limit where the action lives | — |
| FR-007, FR-008, FR-013: the realm | `Realm` client (R13), `RealmEndpoint`, three secrets | one client, one credential, the pattern of merchant's `KeycloakAdmin` | — |

Pure logic with no component: `Tokens` (verification and lookup HMACs), `Profile` (locks, required
fields, clearable), `MergeTags`, `WireTranslation`, `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/008-players/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R18, "For ankka"
├── data-model.md        # Phase 1: every entity's state, events, commands and refusals; workflows, timers, consumers, views, invariants, transitions
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── surface.md       # the original's routes and non-routes, each kept, moved, dropped or new
│   ├── routes.md        # every route by endpoint; bodies and refusals; the 021 modules rows kept
│   ├── facts.md         # events → families; the contract additions
│   ├── wire.md          # the parity translation; the refusal texts
│   └── realm.md         # what passes between the realm and players; the plugin change
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/
├── facts/CasinoPlayers.scala                   # + player_reopened; click_id, bonus_code checked
├── facts/Others.scala                          # + superseded close reason
└── access/Routes.scala                         # + playersOnly(slug)
modules/core/module.json                        # players' env and secrets (R17)
brands/_realm/casino-template.json              # the twin-id claim, the realm attributes, the listener
services/players/                               # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── README.md
├── src/main/scala/io/eitheror/casino/players/
│   ├── player/                                 # PlayerEntity, Profile, the Register/SignIn/… commands, PlayersView
│   ├── sessions/                               # PlayerSessionEntity, GameSessionEntity, SessionEndWorkflow, SessionLimits, RealmReconcile, SessionsView
│   ├── registration/                           # RegistrationWorkflow, LookupIndexEntity, Tokens (lookup)
│   ├── fraud/                                  # FraudCaseEntity, FraudCasesView
│   ├── inbox/                                  # MessageEntity, MessagesView, MergeTags, NoteEntity, NotesView
│   ├── erasure/                                # ErasureWorkflow, ExportWorkflow, the Erasure/Export client traits
│   ├── config/                                 # BrandConfigEntity, CurrencyConfigEntity, AllowListEntity
│   ├── reads/                                  # WalletSummaryView, RecentlyPlayedView, ActivityView
│   ├── facts/                                  # PlayersPublisher, SessionsPublisher, AuditPublisher, RgLimitsConsumer, WithdrawalsConsumer, PushDeliveredConsumer, RewardsConsumer
│   ├── realm/                                  # Realm (trait + Keycloak), Tokens (verification), the webhook body
│   ├── clients/                                # Rg, Kyc, Engagement, Ingest, Geo (traits + HTTP), Bound
│   ├── api/                                    # PlayerEndpoint, SessionEndpoint, OperatorEndpoint, ServiceEndpoint, RealmEndpoint, GeoEndpoint; Answers, Deposits
│   ├── PlayersCodecs.scala
│   ├── Settings.scala
│   └── PlayersService.scala                    # registration; timers started
└── src/test/scala/io/eitheror/casino/players/
    ├── player/, sessions/, registration/, fraud/, inbox/, erasure/, config/, facts/, realm/   # kits
    ├── it/                                     # Kit, FakeRealm, FakeRg, FakeKyc, FakeEngagement, FakeIngest, FakeWallet; the suites below
    ├── port/                                   # WireTranslation, ParitySuite
    └── features/                               # GherkinSuite bindings, one file per feature
tests/original/players-driver/                  # Go: one scenario per feature scenario the original can show
tests/port/players/                             # the recordings, after the driver's run
.github/workflows/ci.yml                        # + players filter, output and job; the check added to main's required checks
justfile                                        # + players-test, players-it, players-run
```

**Structure Decision**: one service directory in the shape `services/wallet/` and `services/ingest/`
have, a package per capability. Every outbound seam (`Realm`, `Rg`, `Kyc`, `Engagement`, `Ingest`,
the erasure and export targets) is a trait with an HTTP implementation and a fake in `it/`, so the
workflows and the handlers test without Keycloak or the other services. The driver sits beside
`ingest-driver/` and reuses its shape.

## Phase 0: research

Done: [research.md](research.md), R1–R18. No unknown remains. Items to *verify at task time*, each
with a fallback:

- the `casino-template.json` realm already maps `eitheror_twin_id` into the access token (fallback:
  the template gains the mapper, R13);
- `player_registered` in the contract already types `click_id` and `bonus_code` (fallback: added,
  additive);
- a 0.13 view takes several topic sources (fallback: one `ActivityView` per source, R11);
- the realm's `user-sessions` listing for `casino-frontend` returns the session ids the webhook names
  (fallback: the reconciler keys on user id and start time, as the original does on a miss).

## Phase 1: design

- [data-model.md](data-model.md): eleven entities with their state, events, commands and refusals
  in check order; the four workflows; the three timers; the seven consumers; the eight views; the
  invariants and the status transitions.
- [contracts/](contracts/): the original's surface disposed of; every route by endpoint with bodies
  and refusals; events to families and the contract additions; the parity translation and refusal
  texts; the realm exchange and the plugin change.
- [quickstart.md](quickstart.md): features, recordings, unit kits, integration suites, a local run
  beside a Keycloak, a kind installation.

### Constitution re-check after design

Unchanged. The design adds one brand service with its own database; produces to `casino.players`,
`casino.sessions` and `platform.audit` from consumers; reads `money.transactions`, `casino.rounds`,
`casino.rewards`, `casino.withdrawals` and `casino.push` through consumers and views; calls the realm,
`responsible-gambling`, `kyc`, `engagement`, `ingest` and every brand service's erasure and export
routes; reaches `merchant` under the brand's erasure grant and nothing else in a shared project. The
contract change is additive. Nothing reads another service's store, and nothing holds a balance or
an unmarked personal field.

## Divergences (ankka-port, section 6)

Research R18's table, with what demonstrates each.

| Divergence | Why | Demonstrated by |
|---|---|---|
| The player id is minted; `playerId` in every answer is `vary` | Clarifications; FR-001 | every registration recording, the id compared for presence |
| A realm user whose player could not be written is removed | FR-002, *Changes* | `RegistrationSuite`: `FakeRealm` records the delete; no recording (the original cannot be made to fail there) |
| `400 missing_fields` naming the fields (the original: a plain-text `400`) | FR-001 | the driver's missing-field recording, marked |
| A missing or invalid token is `401 unauthenticated` (the original: Akka's rejection page); `player_id_missing` is `401 missing_token` | 001 FR-020 | the driver's no-token recordings, marked |
| `500 refresh_failed` / `refresh_error` are `503 service_unavailable` | FR-010 | `SessionsSuite` with `FakeRealm` silent; no recording differs |
| The realm's authenticator asks `players`, fails closed | FR-013, *Changes* | `RealmSuite`; `FakeRg` silent → `allowed: false`; no recording (the plugin reads the twin today) |
| Sign-in asks `may-log-in`; `cooling_off` refusal | FR-011 | `SessionsSuite`; `sessions.feature` |
| The reconciler, `close_child_sessions` and the hourly scan are timers and a workflow | *Changes* | `SessionsSuite`: an idle realm session ends `expired` within two intervals; a limit timer fires; SC-003 timing |
| One session at a time, off by default; `superseded` | FR-012, Clarifications | `sessions.feature`'s two scenarios; the contract's new close reason |
| A player may close their own account; an operator may reopen within the hold | FR-032a, Clarifications | `status.feature`; `player_closed` with `actor: player`; the new `player_reopened` family |
| Duplicates by lookup token, never a scan | FR-036, SC-005 | `DuplicatesSuite`: 10,000 players, the match in two reads; the Scenario 18 recordings replay |
| The affiliate call and the attribution write-back are gone | *Changes*; spec 012 | `RegistrationSuite`: no outbound call but the realm and `engagement`; `player_registered` carries the tag |
| `player_login_failure_lockout` dropped (an empty function) | *Changes* | `contracts/surface.md`; no recording (`TestScenario9_CasinoRF_LoginFailureLockout` records nothing) |
| Four new families; `player_reopened` | FR-035 | `PublishersSuite`; the schemas regenerated |
| The deposit route here, asking `kyc` and RG first | FR-043 | `DepositsSuite` with the four fakes; the recorded cashier call unchanged |
| Erasure as a workflow with a hold and certificates; the export fans out | FR-038 to FR-040 | `ErasureSuite`, `LeakSuite`; `erasure.feature`; no recording (the original has neither) |
| The operator block moves under `/v1/casino/backoffice/<slug>`; `me` paths carry the id | R14; 0.13's route limits | `WireTranslation`; every recorded operator and `me` path replays through it |
| Health is the runtime's readiness, not `/health` | ankka runtime | `Parity.divergences` on any recorded `/health` step |

## What the next command needs to know

**Build order:**

1. the contract additions (`player_reopened`, `superseded`, `playersOnly`; `click_id`/`bonus_code`
   checked), `just schemas`, `sbt contract/publishLocal`; the CI filter, output and job for `players`,
   and the check added to `main`'s required checks;
2. the service skeleton: `ankka init`, `build.sbt` on `casino-contract`, `Settings` (R17),
   `PlayersCodecs`, the `just` recipes;
3. the pure packages with their suites: `Profile` (locks, required, clearable), `Tokens` (both HMACs,
   the normalisation), `MergeTags`, `Bound`, `WireTranslation` (every row of `contracts/wire.md`);
4. `PlayerEntity` with its kit, every row of data-model.md's command table, the fold's pruning of the
   day's game sessions, `Erase` blanking every `Personal` field;
5. the other entities with kits: the two sessions, `MessageEntity`, `NoteEntity`, `FraudCaseEntity`,
   `LookupIndexEntity`, the three configurations;
6. the eight views and the seven consumers with kits (`PublishersSuite`: every event of
   `contracts/facts.md` to its family, never a `Personal` value in a log);
7. the `Realm` trait and `FakeRealm` (users, credentials, sessions, the direct grant, the admin
   listing), the other client traits and fakes, the integration kit;
8. `RegistrationWorkflow` and the register route end to end, fail-first on the parity test of
   `registration.feature`'s first scenario; the Scenario 18 recordings;
9. sign-in, refresh, sign-out, `SessionEndWorkflow`, the webhook, `may-sign-in`, the reconciler,
   game sessions and `SessionLimits`, `RgLimitsConsumer`;
10. the profile, emails, consents, cookies, favourites, the inbox, notes, the operator's status
    routes, tier, the configurations, fraud review;
11. `ErasureWorkflow`, `ExportWorkflow` and the service's own two routes; the deposit route;
12. the driver and the recording run (needs the original's KinD stack); `ParitySuite` over every
    recording, each marked divergence named;
13. `RegistrationSuite`, `SessionsSuite`, `RealmSuite` (SC-004's percentile), `DuplicatesSuite`
    (SC-005's 10,000), `DepositsSuite`, `ErasureSuite`, `LeakSuite` (SC-006), `OperatorSuite`,
    `GherkinSuite` over the six features;
14. the core module's `players` entry (env and secrets), the realm template, a kind deploy beside
    `wallet` and `game-provider`.

**Constraints on that order:**

- Parity tests are written before the components they test and must fail first (ankka-port §7). The
  driver's recordings arrive at step 12; until then the suite runs the Scenario 18 recordings copied
  from the Go suite's run and is otherwise `.ignore`d, as every service here started.
- The recording run is against a local original only, never a shared or production one.
- Steps 1 and 14 touch `contract/` and `modules/core/module.json`, which the 012 and 022 branches
  also touch: rebase before merging, and keep the contract change additive.

**Elsewhere:**

- **Other specs' routes**: `GET /exports/players/{playerId}` is new on `wallet` (002) and `merchant`
  (004) and on every later brand service; `POST /erasure/players/{playerId}` exists on both. Each is a
  task on that service's branch, admitted by `Routes.playersOnly`; until a service has it, the export
  names it under `missing` (FR-040).
- **The plugins** (`contracts/realm.md`): `RgStatusAuthenticator` and `GamstopAuthenticator` change
  their read to `/internal/realm/<slug>/may-sign-in` and `/gamstop-refusal` with `X-Realm-Secret`;
  the listener's URL moves. That work is in the original's repository (`bundle/keycloak-plugins/`) and
  the installation's Keycloak image (`installation/base/keycloak/`), a separate pull request each;
  `FakeRealm` and `RealmSuite` prove this side without them.
- **Spec 010** reads `rg_limit_applied`'s consumer here and provides `may-log-in` (FR-009) and the
  exclusion commands' caller (FR-018 to FR-020); **009** calls `/suspend` (FR-025) and reads
  `player_profile_updated` (FR-016); **014** calls `/messages` and sends the verification emails;
  **011** reads `player_fraud_flagged`; **012** reads `player_registered` and `player_closed`.
- **For ankka (not blocking):** 042 is the one gap (R8); a view over several topic sources is a
  verify-at-task-time item (R11); nothing else in 0.13 is missing for this service.
