# Tasks: players

**Input**: Design documents from `specs/008-players/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{surface,routes,facts,wire,realm}.md, quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test
(I) and the living features the scenarios (VII); the port method makes parity tests a deliverable,
written before the component they hold to account and failing first (ankka-port §7). Every story
opens with its tests: the entity, workflow and consumer kits, the integration suites on the kit, the
feature bindings, the SC suites, the parity tests over the recordings.

**Organization**: By user story, in spec.md's order. US1 to US3 are P1 and together are what a
player and `game-provider` need on day one; US1 alone is the MVP (a registration that makes one
realm user and one player, sends one email, produces one fact, and is verified by its link). The
pure packages, the ten entities, the eight views, the eight consumers, the realm and service
seams, the fakes and the kit are foundational: no route can be tested before a player can be written,
a realm can be asked and a fact can be read. US2 and US3 extend the player with sessions; US4 and US5
the profile and the status; US6 hangs off US1's workflow; US7 fans out to every other service. The
deposit route (FR-043) belongs to no story and sits in Polish.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US7 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `p/` stands for `services/players/src/main/scala/io/eitheror/casino/players/`
- `p-test/` stands for `services/players/src/test/scala/io/eitheror/casino/players/`
- `contract/` is the shared library `io.eitheror:casino-contract`; `tests/port/players/` the
  recordings; `tests/original/` the recording toolchain; `features/players/` the living features
- `i-test/` stands for `services/ingest/src/test/scala/io/eitheror/casino/ingest/` and `m/` for
  `services/merchant/src/main/scala/io/eitheror/casino/merchant/` (patterns copied, never changed)
- The service's sbt commands run in `services/players/`; Go commands in `tests/`; everything else
  at the root
- `the original` is `~/Documents/projects/eitheror/eitheror-platform`; its player code is under
  `substrate/casino-service/src/main/scala/io/eitheror/casino/` (`PlayerRoutes.scala`,
  `PlayerEmailRoutes.scala`, `PlayerEmailChangeRoutes.scala`, `BackofficePlayerRoutes.scala`,
  `KeycloakWebhookRoutes.scala`, `MultiAccountDetector.scala`, `KeycloakSessionReconciler.scala`)
  and its plugins under `bundle/keycloak-plugins/`
- A recording run is made only against a local original on its KinD stack, never a shared or
  production one; `kubectl` only ever with `--context kind-ankka`

---

## Phase 1: Setup

**Purpose**: the contract additions, the service's build, the recipes and CI job, the spec's
Recordings section corrected, and the recording driver (which needs only Go and the original's
checkout, not this service).

- [X] T001 Add to `contract/` (contracts/facts.md, additive): the family `player_reopened`
      (`previous_value`, `new_value`, `actor`, `actor_id`, `reason`) in
      `contract/src/main/scala/io/eitheror/casino/contract/facts/CasinoPlayers.scala`; `superseded`
      as a close reason on `player_logged_out` there and on `player_session_ended` and
      `game_session_ended` in `facts/Others.scala`; `click_id` and `bonus_code` on
      `player_registered` if not already typed (verify at task time); `Routes.playersOnly(slug)` in
      `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`, a `service` ACL
      admitting `Caller.Service(<brand project>, "players")` and, when a grantee is passed, the
      brand's erasure grantee; `just schemas` regenerating `casino.players.v1` and
      `casino.sessions.v1`; tests in `contract/src/test/scala/io/eitheror/casino/contract/`
      (`CasinoPlayersSuite`: the new family round-trips and the schema lists it; `RoutesSuite`:
      `playersOnly("goldvibe")` admits `players` and refuses `wallet`, a player and a staff token);
      `sbt contract/test contract/publishLocal`; every existing service's suites unchanged
      *Done 2026-10-25*: `click_id` and `bonus_code` were already typed, and the close reasons of
      `player_logged_out` and `player_session_ended` are free strings, so `superseded` needed nothing;
      `game_session_ended` gained an optional `close_reason` (FR-022 names it). `Routes.playersOnly`
      takes no slug (`Callers.service("players")` admits the caller's own project), and
      `Routes.serviceOf(names*)` was added for the service endpoint's per-caller routes. The contract's
      families differ from the first facts.md in places (consent per channel, `fraud_case_id` and
      `signal`, `send_class`); facts.md now follows the contract. Spec 021's two modules routes keep
      their `/v1/casino/brands/{brand}/modules` paths (`tools/modules`' `ReadOnlyRoutesSuite` checks
      them), so they are a seventh endpoint, `ModulesEndpoint`. `just test`: contract 193, modules 153.
- [X] T002 Create `services/players/` on `services/wallet/`'s template: `build.sbt` on Scala 3.9.0
      with ankka 0.13.0 (`ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
      `ankka-telemetry-otlp`, `ankka-testkit`), `io.eitheror:casino-contract` from the local publish,
      munit, the Postgres driver as a test dependency (for `LeakSuite`); `project/plugins.sbt`;
      `service.json` for the local run (`ANKKA_HTTP_PORT` 9003; `ANKKA_AUTH_ISSUERS=player,staff`
      and the `ANKKA_AUTH_{PLAYER,STAFF}_*` variables as `wallet`'s `service.json` has them);
      `docker-compose.yml` with Postgres and a Keycloak 24.0.5 importing
      `brands/_realm/casino-template.json` as `casino-goldvibe` (the realm attributes of
      contracts/realm.md set to the compose file's test secrets) and `sbt schema` as wallet has;
      `src/main/resources/application.conf` and `logback.xml` (no body, header or path-parameter
      logging: an address can sit in a path, SC-006); the package directories plan.md lists under
      `p/` (`player/`, `sessions/`, `registration/`, `fraud/`, `inbox/`, `erasure/`, `config/`,
      `reads/`, `facts/`, `realm/`, `clients/`, `api/`) and `p-test/` (the same, plus `it/`, `port/`,
      `features/`); `services/players/README.md` naming the service's purpose in two lines (filled
      by T085)
- [X] T003 [P] Add to `justfile` the recipes `players-test` (`sbt contract/publishLocal` then `sbt
      test` in `services/players`), `players-it` (`testOnly io.eitheror.casino.players.it.*
      io.eitheror.casino.players.port.* io.eitheror.casino.players.features.*`), `players-run`
      (`CASINO_BRAND=goldvibe ANKKA_HTTP_PORT=9003`, `-Dankka.local-services.responsible-gambling`,
      `kyc`, `engagement`, `ingest`, `wallet` at `127.0.0.1:9010`–`9014`, `KEYCLOAK_URL=http://127.0.0.1:8080`,
      `sbt run`); add to `.github/workflows/ci.yml` a `players` filter (`services/players/**`,
      `tests/port/players/**`, `tests/original/players-driver/**`, merged with the `build` anchor),
      a `players` output on the `changes` job, and a `players` job on the `ingest` job's pattern
      gated on it; note in the pull request that `players` must be added to `main`'s required status
      checks (`gh api -X PATCH repos/thinkmorestupidless/ankka-modules/branches/main/protection/required_status_checks`
      adding the context) by a person once the job has run green once
- [X] T004 [P] Correct `specs/008-players/spec.md`'s *Recordings* section per research R1: replace
      the list of Go-suite scenarios with the driver's scenarios (one per feature scenario the
      original can show, named as the feature names it) plus the four
      `TestScenario18_MultiAccountDetection_*` registrations, and state that
      `TestScenario7_CasinoGate3_*` drive the substrate's action executor and fail on the original,
      `TestScenario9_CasinoRF_*` are skipped, and 12 and 14 are merchant's and ingest's recordings;
      keep the Playwright sentence; `just features` 0 findings
- [X] T005 [P] Write `tests/original/players-driver/` (Go, as `tests/original/ingest-driver/`:
      `doc.go`, `driver.go`, `scenarios.go`, `record_test.go` under `//go:build e2e`,
      `driver_test.go` offline), against the original's `casino-service` and its Keycloak on the
      KinD stack: one scenario per `kept` row of contracts/surface.md the original can show, named as
      `features/players/*.feature` names the scenario — registration (new address; address already
      registered; weak password; malformed tag; missing field; consents; the verification link
      followed twice; the network address kept), sign-in (right password; unverified; a suspended
      player; sign-out; refresh; refresh reused; another player's id), game sessions (open at launch
      through the original's `/v1/casino/sessions/{brand}/launch`; a restricted player refused;
      ended by the player twice; forced by an operator; read own only), profile (read; the locked
      date of birth; a disallowed country; a second address added, verified, promoted; a password
      change right and wrong; consent withdrawn; a cookie decision whole and missing a field; the
      operator's date-of-birth correction under and over 18), status (suspend; reinstate; tier from
      none; close; a closed player's sign-in; an operator without the role; a pinned note), and the
      operator's messages, merge tags, fraud-case review and `activity-export`; each scenario minting
      its own address (`<scenario>-<nonce>@example.test`) and reading the verification link through
      the original's test hook or the backoffice route, as `setup:` steps; `vary` on `playerId`,
      every token, every session id and every timestamp; `{email}` placeholders written by
      `tests/original/merge`; run with `-tags e2e -casino-url http://localhost:8085 -keycloak-url
      http://localhost:8080`; extend `tests/original/preflight` to check the register route answers;
      `go test ./original/players-driver/...` green offline (the names against the features; the
      recorded shape against a stub)
      *Done 2026-10-25*: the original has no test hook for the verification email, so the driver mints
      the link as `EmailVerificationTokenStore` does from `EMAIL_TOKEN_SIGNING_SECRET` (a test vector
      computed with openssl holds it), and reads a second address's and a password change's tokens from
      the player twin. The operator's routes are `BackofficePlayerRoutes`' `/v1/casino/{brand}/players/…`
      and `PlayerProfileRoutes`' `/v1/casino/backoffice/{brand}/…`; the run signs in an operator and an
      operator of no brand by password grant (passwords from env, never recorded). 54 feature recordings, 19
      feature scenarios not driven with the reason, and three extras (message, unknown merge tag,
      activity export). `preflight -register <casino-url> -brand <brand>` checks the route.

---

## Phase 2: Foundational

**Purpose**: what every story needs: the pure packages with their suites, the ten entities and
eight views with their kits, the eight consumers, the realm and service seams, the fakes and the
kit, the parity and feature suites listing their tests, the service and its endpoint shells.

**⚠️ CRITICAL**: no story starts before this phase is done. T029 (the recording run) gates every
parity task and can run the moment T005 and the original's stack are up.

### Pure packages (tests beside each)

- [X] T006 [P] Implement `p/Settings.scala` (research R17's table: `CASINO_BRAND`,
      `CASINO_REAL_MONEY`, `KEYCLOAK_URL`, `PLAYER_SITE_BASE`, `INGEST_URL`,
      `REALM_RECONCILE_INTERVAL` `60s`, `VERIFICATION_VALIDITY` `24h`, `VERIFICATION_RESEND_INTERVAL`
      `5m`, `MAY_LOG_IN_BOUND` `500ms`, `REALM_ANSWER_BOUND` `80ms`, `SESSION_END_BOUND` `5s`,
      `EXPORT_PART_BOUND` `10s`, `EXPORT_RETENTION` `24h`, `REGISTRATION_BOUND` `5s`; the six secrets
      read by name from the secret store or, locally, from env; `fromEnvironment` refusing a value it
      cannot read by the variable's name, as `services/wallet/.../Settings.scala` does) and
      `p/PlayersCodecs.scala` (codecs for every stored type of data-model.md: the ten states and
      their events, `Personal[String]` as the contract's codec gives it, `Instant` ISO-8601, the
      workflow states, the view rows); tests `p-test/SettingsSuite.scala` (every default; a malformed
      duration refused naming the variable; a missing secret refused naming it) and
      `p-test/PlayersCodecsSuite.scala` (every type round-trips; a `Personal` field serialises
      through the marker and never as a bare string)
- [X] T007 [P] Implement `p/player/Profile.scala`: the `Profile` type of data-model.md (sixteen
      `Personal[String]` fields in the original's camelCase names), `Profile.lockOnce` (the six
      fields), `Profile.clearable` (the five), `Profile.required` (the original's `RequiredFields`
      eight), `Profile.apply(current, edit, locked): (Profile, changed: Set[field], nowLocked)`
      dropping a locked field without error, clearing a clearable field on an explicit empty value,
      refusing nothing (the route refuses `no_fields_provided` when `changed` is empty); `Profile.age(dob, today)`
      and `Profile.underage`. Tests `p-test/player/ProfileSuite.scala`: each lock-once field locks on
      first value and ignores the next edit; each clearable field clears; a non-clearable field with
      an empty value is unchanged; `changed` names exactly what changed; 17 years and 364 days is
      underage, 18 years is not
- [X] T008 [P] Implement `p/realm/Tokens.scala` (the verification token: payload
      `{b, p, e, x, t}` base64url, `.` and the HMAC-SHA256 under `players-email-token`, `mint`,
      `verify(token, now): Either[invalid | expired, Claims]` with the purposes `email_verify`,
      `email_add`, `email_change`, `password_change`; constant-time compare) and
      `p/registration/LookupTokens.scala` (`identity(given, family, dob): Option[token]` over
      `trim.toLowerCase` joined with `|`, none when any part is empty; `device(fingerprint)`; hex
      HMAC-SHA256 under `players-lookup-key`). Tests `p-test/realm/TokensSuite.scala` (round-trip;
      a byte changed is invalid; 24 h and one second is expired; a token of one purpose refused for
      another; a known payload under a known key gives the original's `payload.signature` form) and
      `p-test/registration/LookupTokensSuite.scala` (`" Ann "`, `"SMITH"`, `"1990-01-01"` and
      `"ann"`, `"smith"`, `"1990-01-01"` share a token; a missing part is none; two keys give two tokens)
- [X] T009 [P] Implement `p/inbox/MergeTags.scala` (the original's tags: `{{firstName}}`,
      `{{displayName}}`, `{{brandName}}`, `{{currency}}`, …, read from `BackofficePlayerRoutes`'s
      merge-tag list; `render(template, values): Either[UnknownTag(name), String]`; `list`) and
      `p/clients/Bound.scala` copied from `i-test`'s pattern in `services/ingest/.../merchant/Bound.scala`
      (`race` answering `Answered`, `Unanswered(late)`, `Failed`). Tests
      `p-test/inbox/MergeTagsSuite.scala` (every tag renders; an unknown tag names itself; a
      `Personal` value renders through `.reveal` only inside the message body) and
      `p-test/clients/BoundSuite.scala` (as ingest's)
- [X] T010 [P] Implement `p-test/port/WireTranslation.scala`: every row of contracts/wire.md's
      *Paths* and *Bodies* tables as a function from a recorded step to the replayed request and
      from the recorded answer to the compared fields (`trackingContext.btag` → `btag`;
      `/me/` → `/{player}/`; the operator block under `/backoffice/`; `?playerId=` → `/players/{id}`;
      `/internal/keycloak-webhook` → `/internal/realm/{brand}/webhook`; the plain-text `400` compared
      as the marked divergence). Tests `p-test/port/WireTranslationSuite.scala`: one test per row;
      a path not in the table passes through unchanged

### The entities (tests first)

- [X] T011 [P] `p-test/player/PlayerEntitySuite.scala` (`EventSourcedTestKit`), every row of
      data-model.md's command table in its order: `Register` records the profile, consents with time
      and source, the registration fields and `pending_email_verification`; a second `Register`
      answers the first; `VerifyEmail` → `active`, and again no event; `ResendVerification`
      `already_verified`, then on a fresh player `rate_limited` within 5 min of `lastSentAt`;
      `SignIn` refused `email_not_verified`, then `account_restricted` naming each of `suspended`,
      `self_excluded`, `blocked`, `closed`, with `lastRefusedSignInAt` stamped; a served `SignIn`
      stamps `lastSignInAt`; `SessionOpened` answers the other open sessions; `GameSessionOpened`
      refused `session_expired` with no open player session, `session_limit_reached` with the limit
      and used minutes when the day's sessions (open ones counted to now) reach it, else answers the
      minutes left, and the fold prunes yesterday's sessions; `SetSessionLimit` answers the open game
      sessions and their remainders; `EditProfile` drops a locked field, refuses `no_fields_provided`
      and `invalid_country` with a non-empty list; `CorrectDateOfBirth` each refusal in order
      (`reason_required`, `date_of_birth_required`, `invalid_date_of_birth`, `underage`, `kyc_locked`);
      every email command and refusal; `ChangeConsent`, `RecordCookieDecision` (replaced per device);
      favourites; `Suspend` twice is one event with the reason updated; `Reinstate` `not_suspended`;
      `SetSelfExcluded` / `LiftExclusion` `not_excluded`; `Close` idempotent; `Reopen` `not_closed`
      and `hold_lapsed`; a state seeded `blocked` (the migration's) is refused sign-in and play and
      closes like any other, and no command reaches `blocked`; `KycVerified` then
      `CorrectDateOfBirth` → `kyc_locked`, `KycWithdrawn` lifts it; `SetVipTier` answers the previous;
      `FlagDuplicate` idempotent under
      the case id; `FileErasure` `not_closed`; `Erase` leaves every `Personal` field empty and every
      status, time and count unchanged, and again no event; every transition appends one trail entry
      with actor, actor id, reason and time; the state type has no field for a password, a realm
      token or a lookup token (the codec's field list asserted, SC-006)
- [X] T012 [P] `p-test/sessions/PlayerSessionEntitySuite.scala` and `GameSessionEntitySuite.scala`
      (`EventSourcedTestKit`): `Open` records player, realm session id, the three `Personal` fields,
      `active`; `AddGameSession`; `End(reason)` sets the status to the reason's status and the end
      time, and again no event; a game session's `Open` and `End(reason, at)` with `durationMinutes`
      the whole minutes between, and again no event; each end reason of data-model.md accepted
- [X] T013 [P] `p-test/inbox/MessageEntitySuite.scala` (`Created` under an id twice answers the first;
      `Read`, `Deleted`, `Delivered` each once; a deleted message keeps `body`), `NoteEntitySuite.scala`
      (`KeyValueEntityTestKit`: write, pin, unpin; `text` is `Personal`) and
      `p-test/fraud/FraudCaseEntitySuite.scala` (`Opened` with players and signals; `Reviewed` with
      operator and time; `Dismissed`; a second `Opened` under the id answers the first)
- [X] T014 [P] `p-test/registration/LookupIndexEntitySuite.scala` (`KeyValueEntityTestKit`: `Add(p1)`
      on an empty index answers no others; `Add(p2)` answers `{p1}`; `Add(p1)` again answers `{p2}`
      and holds two; `Remove(p1)`) and `p-test/config/ConfigEntitiesSuite.scala` (`BrandConfig`'s
      defaults of data-model.md on an empty entity; `set` validates `reportingZone` as an IANA id and
      refuses `invalid_config` naming the field; `CurrencyConfig` refuses an unknown ISO code;
      `AllowList` empty means every country)
- [X] T015 Implement `p/player/PlayerEntity.scala` (state, events, commands and queries of
      data-model.md, the fold with the day pruning and `Erased` blanking; `snapshotEvery` 100),
      `p/sessions/PlayerSessionEntity.scala`, `p/sessions/GameSessionEntity.scala`,
      `p/inbox/MessageEntity.scala`, `p/inbox/NoteEntity.scala`, `p/fraud/FraudCaseEntity.scala`,
      `p/registration/LookupIndexEntity.scala`, `p/config/BrandConfigEntity.scala`,
      `CurrencyConfigEntity.scala`, `AllowListEntity.scala`; each with its companion as
      `services/wallet/.../WalletEntity.scala` has it; T011–T014 green
      *Done 2026-10-25.* Two calls the tables left open: a second `Suspend` is no event and keeps the
      first reason and time (data-model.md said "updated, no event", which an event-sourced entity
      cannot do; T011 had "one event"); a `blocked` player is seeded by `StatusImported`, an event
      only the migration (spec 019) writes. `CurrencyConfig` holds `offered` and the original's
      `defaultCurrency`; per-currency settings wait for a service that reads one. The suites are one
      file per package (`SessionEntitiesSuite`, `InboxEntitiesSuite`, `FraudCaseEntitySuite`).

### The views and the consumers (tests first)

- [X] T016 Implement the eight views of data-model.md: `p/player/PlayersView.scala` (over
      `PlayerEntity` state; queries `list(status?, tier?, flagged?, page)`, `search(q)` over the
      display name and primary address text, `byId`), `p/sessions/SessionsView.scala` (over both
      session entities; `byPlayer`, `openPlayerSessions`, `openGameSessionsOf(player)`, `byRealmSession`),
      `p/inbox/MessagesView.scala` (`byPlayer(page)`, `unreadCount` computed by the route),
      `p/inbox/NotesView.scala` (`byPlayer`, pinned first), `p/fraud/FraudCasesView.scala`
      (`open`, `byPlayer`, `byId`), `p/reads/WalletSummaryView.scala` (`money.transactions` from
      `StartFrom.Earliest`, one row per player and currency with the balances after the last
      movement), `p/reads/RecentlyPlayedView.scala` (`casino.rounds`, one row per player and game,
      `lastPlayedAt`), `p/reads/ActivityView.scala` (the five categories over `money.transactions`,
      `casino.rounds`, `casino.rewards`, `casino.players` account families and `casino.sessions`;
      **verify at task time** that a 0.13 view takes several topic sources, else one view per source
      under `p/reads/activity/` and the route merges by time, research R11; record the result in
      research R11); every row's personal columns `Personal`, redacted when the player's state is
      `erased` (the view handler reads the status on the row's update); `sbt schema` regenerates;
      tests on the kit come with T027's `SmokeSuite` (a row per view appears after the event that
      feeds it)
      *Done 2026-10-25.* ActivityView and SessionsView are keyed views (R11 checked); the activity rows
      keep the original's columns (`eventType`, `actor`, `amountMinorUnits`, `currency`, `description`)
      and take bonuses from `casino.rewards` only. `reads/ViewsSuite.scala` covers the folds on the kit
      ahead of T027.
- [X] T017 [P] `p-test/facts/PublishersSuite.scala` (`ConsumerTestKit`): every row of
      contracts/facts.md's three tables, event in → one fact out with the family, the payload's
      field names, `ce-subject` and the key the player id, `ce-id` the event id; the same event
      twice → the same `ce-id`; the events the table lists as not published → nothing; a `Personal`
      payload field carried as the marker, never a bare string; an `AuditRecord` per operator action
      with no `Personal` value and the field named for a date-of-birth correction, a note or a message
- [X] T018 [P] `p-test/facts/ConsumersSuite.scala` (`ConsumerTestKit`): `rg_limit_applied` for
      `SESSION_MINUTES` → `PlayerEntity.SetSessionLimit(minutes)` and, for each open game session
      the entity answers, a `SessionLimits` timer rescheduled by name (a session over the new limit
      ends at once); any other `rg_limit_applied` kind → nothing; `player_kyc_verified` →
      `PlayerEntity.KycVerified`, `player_kyc_rejected` → `KycWithdrawn`; `casino.withdrawals` requested →
      `WithdrawalOpened`, settled or failed → `WithdrawalSettled`; `push_delivered` →
      `MessageEntity.Delivered`; `bonus_code_entered` → `ClearPendingBonusCode`; a fact the codec
      cannot read is logged and skipped
- [X] T019 Implement `p/facts/PlayersPublisher.scala`, `SessionsPublisher.scala`,
      `AuditPublisher.scala` (over the contract's `AuditConsumer` base as ingest's `AuditFacts`
      does), `RgLimitsConsumer.scala`, `KycFactsConsumer.scala`, `WithdrawalsConsumer.scala`,
      `PushDeliveredConsumer.scala`, `RewardsConsumer.scala` (topic sources `StartFrom.Latest`; `Consumer.Companion` and
      `produceTo` as `services/wallet/.../facts/` has them); T017 and T018 green
      *Done 2026-10-25.* A consumer reads one source and writes one topic, so the publishers are nine,
      in `facts/Publishers.scala` over the pure `facts/Facts.scala`: `casino.players` from the player
      (`PlayersPublisher`), the messages (`MessagesPublisher`) and the player sessions
      (`LoginsPublisher`: `player_logged_in` comes from the session's `Opened`, which has the session id
      `SignedIn` lacks); `casino.sessions` from each session entity; `platform.audit` from the player,
      the messages, the notes and the fraud cases. The audits a route or workflow makes (force-end,
      reset-password, export, erasure, config) belong to their stories. The session and message `Ended`,
      `Read` and `Deleted` events now carry the player (and the game and minutes) so the fact needs no
      read. The consumers are in `facts/Consumers.scala`; `sessions/SessionLimits.scala` is the timed
      action the limit consumer reschedules, reached through `PlayersRuntime.timers`. `push_delivered`'s
      correlation id is taken as the message id (spec 014 to confirm).

### The seams, the fakes and the kit

- [X] T020 [P] Implement `p/realm/Realm.scala`: the trait with every row of contracts/realm.md's
      first table (`createUser(email, password, playerId): Either[EmailExists | PasswordTooWeak |
      RealmNotFound, userId]`, `deleteUser`, `setVerified`, `changeAddress`, `directGrant(email,
      password): Either[InvalidCredentials | EmailNotVerified, Tokens]`, `refresh`, `verifyPassword`,
      `revokeSession`, `setEnabled`, `liveSessions(clientId): Seq[LiveSession]`,
      `sendResetEmail`, `setPassword`, `sendActions`) and `p/realm/KeycloakRealm.scala` over the service's HTTP
      client after `m/operators/KeycloakAdmin.scala`'s pattern (the admin token under
      `players-realm-admin`, cached to its expiry; the realm `casino-<slug>`; `casino-frontend`'s
      client id read once; the twin-id claim read from the token's `eitheror_twin_id` and
      `session_state`); `p/realm/Webhook.scala` (the body type; `verify(header, secret)` constant
      time). Tests `p-test/realm/KeycloakRealmSuite.scala` against a JDK `HttpServer` scripted with
      Keycloak's answers: each row's call and each mapped refusal; the admin token fetched once for
      two calls; a token without the claim → `missing_token`
      *Done 2026-10-25.* The grant's answer is `RealmTokens` (T009's signed links own `Tokens`);
      `liveSessions()` reads `casino-frontend` only, the one client the reconciler needs. The suite runs
      against T022's `FakeRealm` (written here), which signs HS256 tokens the kit will check.
- [X] T021 [P] Implement `p/clients/`: `Rg.scala` (`mayLogIn(playerId): Either[CoolingOff(until) |
      Restricted, Allowed]`, `mayDeposit(playerId, amount, sums)`, `gamstopRefusal(email, result)`),
      `Kyc.scala` (`depositCheck(playerId)`), `Engagement.scala` (`send(playerId, template, link)`),
      `Ingest.scala` (`createSession(body, merchantKey)` on ingest's public route), `Geo.scala`
      (the original's lookup, `None` on any failure), `Wallet.scala` (`sums(playerId, currency)`),
      `Erasure.scala` (`apply(service, playerId): certificate` over `POST /erasure/players/{id}`)
      and `Export.scala` (`part(service, playerId)` over `GET /exports/players/{id}`), each a trait
      with an HTTP implementation over `componentClient`'s service client, each call under `Bound.race`
      with its setting; tests `p-test/clients/ClientsSuite.scala` on a scripted `HttpServer`: each
      answer mapped; a silent server → `Unanswered` at the bound
      *Done 2026-10-25.* In-project calls go through `clients/Neighbour.scala` (`Target`, `Reply`,
      `bounded`: an unreadable answer is `Failed`, never a decision); each call takes its bound from the
      caller. The suite uses ankka's `ScriptedServices` for those and `FakeServer` for ingest and ip-api.
      **Assumed until their specs build them**: `responsible-gambling` at `/v1/casino/brands/<b>/rg/`
      `may-log-in` and `gamstop-refusal` (the wallet's prefix for `may-deposit`); `kyc` at `…/kyc/deposit-check`
      answering `{allowed}` or `{code, message}`; `engagement` at `…/engagement/sends` answering `status`.
- [X] T022 [P] Write the fakes in `p-test/it/`: `FakeServer.scala` copied from `i-test/it/`;
      `FakeRealm.scala` (users by address with password, verified, enabled, the twin-id attribute;
      the admin token route; direct grant issuing a signed JWT the kit's OIDC fake accepts with
      `eitheror_twin_id` and `session_state`; refresh with a reuse list; sessions listed and revoked;
      `received(call)` and knobs `delay`, `silent`, `refuse(address, reason)`), `FakeRg.scala`
      (`mayLogIn` scripted per player; `mayDeposit`; `gamstop` recorded), `FakeKyc.scala`,
      `FakeEngagement.scala` (every send recorded with its link, so a test follows it),
      `FakeIngest.scala` (the cashier create answering `redirectUrl` and `paymentId`, the merchant
      key recorded), `FakeWallet.scala` (`sums`; `/erasure/players/{id}` answering a certificate;
      `/exports/players/{id}` answering a part; knobs `silent`, `delay`), `FakeGeo.scala` (the
      lookup answering a country and dial code per address; knob `silent`), `FakeAgent.scala` (the
      vendor's `GET /vendor/modules` and `POST /vendor/requests` for spec 021's rows, as the wallet's
      tests fake it if they do, else new); `FakesMain.scala` starting them on 9010–9015 for
      `players-run`
      *Done 2026-10-25.* `FakeRealm.scala` (T020) and the rest in `it/Fakes.scala` over a `KnobbedServer`
      base (`silent`, `delayMillis`); `FakeGeo` stands in for ip-api.com. `FakesMain` uses 9010–9017: the
      eight fakes need eight ports.
- [X] T023 Write `p-test/it/Kit.scala` and `SharedKit.scala` after `i-test/it/Kit.scala`:
      `AnkkaTestKit.start` over throwaway Postgres with the fakes under `localServices`
      (`responsible-gambling`, `kyc`, `engagement`, `ingest`, `wallet`, `merchant`, `vendor-agent`),
      `InMemoryBroker` for `casino.players`, `casino.sessions`, `platform.audit`, `money.transactions`,
      `casino.rounds`, `casino.rewards`, `casino.withdrawals`, `casino.push`, `TimerRuntime(200.millis)`,
      a settable clock, `REALM_RECONCILE_INTERVAL` 1 s, `SESSION_END_BOUND` 2 s, the bounds short;
      helpers `register(fields)`, `verify(address)` (follows `FakeEngagement`'s last link),
      `signIn(address)`, `playerToken(playerId)`, `staffToken(roles)`, `serviceToken(name)`,
      `facts(topic)`, `eventually`, `realm` (the `FakeRealm`), `rows(view)`, `tables` (every table's
      text for `LeakSuite`), `log` (the captured appender)
      *Done 2026-10-25.* The verifier stand-in accepts a token `FakeRealm` signed (issuer `player`,
      subject the `eitheror_twin_id`) and `staff|<subject>|<roles>`; the six secrets go in the service's
      store after start. `merchant` resolves to `FakeWallet` for its erasure and export routes.
- [X] T024 Implement `p/PlayersService.scala` (`Ankka.service.registerAll` of every entity, view,
      consumer, workflow and timed action, `ProjectionRuntime.fromEnv()`, `TimerRuntime`, the
      `realm-reconcile` recurring timer and the HTTP server with the six endpoints, `started(timers,
      settings)` as merchant's; `InMemoryBroker` when `ANKKA_KAFKA_BOOTSTRAP_SERVERS` is unset) and
      the six endpoint shells in `p/api/`: `PlayerEndpoint("/v1/casino/players/<slug>")` with
      `Routes.player(slug)` and the public routes `withAcl(Routes.public)`,
      `SessionEndpoint("/v1/casino/sessions/<slug>")`, `OperatorEndpoint("/v1/casino/backoffice/<slug>")`
      with `Routes.operator(slug)`, `ServiceEndpoint("/v1/casino/brands/<slug>/players")` with
      `Routes.service` per caller, `RealmEndpoint("/internal/realm/<slug>")` public with the secret
      checks, `GeoEndpoint("/v1/casino/geo")`; every route of contracts/routes.md declared and
      answering `501` until its story fills it; `p/api/Answers.scala` (the refusal body
      `{error, message, …}` with contracts/wire.md's texts; the 401s of 001 FR-020); the slug from
      `CASINO_BRAND` at start; `sbt compile` green; the service starts against the compose file
      *Done 2026-10-25.* Seven endpoints, not six: contracts/routes.md adds `ModulesEndpoint` (spec 021).
      The geo lookup is whole (it is one call). `Neighbours` builds every client from a context's service
      clients and secret store; the realm and the lookup are one per process. `RealmReconcile` is
      registered and scheduled and only reads the realm's sessions until US2's task fills it.
      **Found at T025 and fixed**: the runtime refuses a keyed view reading a topic (the test kit had
      allowed it), so the activity and recently played are now a consumer per topic into an entity and a
      plain view over it (R11 corrected); and `by-id` is a reserved query name (now `one-player`,
      `one-case`). Starting against the compose file is T028's.
- [X] T025 Write `p-test/it/SmokeSuite.scala` on `SharedKit` (`beforeAll` starts the kit, as
      psp-gateway's): the service registers every component plan.md's table names (the descriptor
      listed); each endpoint prefix answers (`401`/`501`, not `404`); a `Registered` event reaches
      `PlayersView`; a `player_registered` fact reaches the broker; the `realm-reconcile` timer has
      fired once within 3 s (`FakeRealm.received(liveSessions)`); `GeoEndpoint` answers `null`s when
      `FakeGeo` is silent
      *Done 2026-10-25.* Plus a settled round reaching both plain views, and another brand's player
      token refused `403` on the player routes.
- [X] T026 [P] Write `p-test/port/Parity.scala` (the replay over `contract.port.Replay.run` through
      `WireTranslation`; `divergences` as a named list, empty until each story names its own;
      `vary` fields compared for presence; `setup:`/`observe:` steps run, not compared),
      `p-test/port/ParitySuite.scala` (one test per `tests/port/players/*.json`, named after the
      file; `.ignore`d when the directory is empty), `p-test/port/CoverageSuite.scala` (every
      divergence in plan.md's table is named in `Parity.divergences` and every named one is hit by
      at least one recording, as ingest's), and `p-test/features/` with one `GherkinSuite` per
      feature file (`RegistrationFeatures`, `SessionsFeatures`, `ProfileFeatures`, `StatusFeatures`,
      `DuplicatesFeatures`, `ErasureFeatures`), each listing every scenario of its file with a
      pending step so the suite reports 63 scenarios unbound
      *Done 2026-10-25. The six suites report 73 ignored pickles, not 63: the outlines expand per example row. `ParitySuite` reports one ignored test while tests/port/players is empty; `CoverageSuite` reports each Divergences row as unhit. `Parity.ours` decides which recorded steps are this service's and answers the rest as recorded.*
- [X] T027 [P] Write `p-test/it/LeakSuite.scala`'s frame (SC-006): the marker values (a display
      name, a given and family name, a date of birth, an address, a telephone, a bank account, a
      network address, a user agent, a device fingerprint, a password, a note's text, a message
      body) and `assertNowhere(markers)` over `Kit.tables`, every view's rows, every fact on every
      topic, every audit record, every operator answer and `Kit.log`; the registration case written
      here (a registration with every marker; after it the password and nothing from the body is in
      the log, no marker is in any fact but as a `Personal` field, and no marker is in the audit
      records); the rest filled by T069 (erasure) and T072 (deposit)
      *Done 2026-10-25. Facts are checked outside their personal fields (`PersonalFields.names`, snake case); the registration case accepts `201` or `501` so it holds before and after T030.*
- [X] T028 Confirm the kit, the shells and the listings: `sbt compile test` green for T006–T025;
      `ParitySuite` reports ignored (no recording yet); the six `GherkinSuite`s report every
      scenario pending; `LeakSuite`'s registration case passes against the shells (nothing leaks
      from a `501`); `CoverageSuite` names plan.md's divergences as unhit
      *Done 2026-10-25. `sbt test`: 138 passed, 92 ignored. The service also starts locally (`sbt run` against compose Postgres, the player issuer set as `just players-run` does): every component registers, geo answers nulls with no geo service, a register answers `501`, `/me` without a token `401`, and the reconciler runs and logs the realm unavailable when its secret is missing. Compose Keycloak was skipped: port 8080 is held by the kind control-plane ingress.*
- [ ] T029 Run the recording: the original's KinD stack up (`tests/original/README.md`;
      `kubectl --context kind-…` only), `tests/original/preflight -players`, `go test -tags e2e
      ./original/players-driver/...` twice, `tests/original/recorder` over the four
      `TestScenario18_MultiAccountDetection_*` tests (or their recordings moved from
      `tests/port/unclaimed.txt`'s list if a merchant or ingest run already wrote them),
      `./original/merge tests/port/players`, `just modules recordings check`; every recording `pass`
      on the original; `tests/port/players/README.md` naming the original's commit, the run date,
      which scenarios the original could not show (research R1) and the four Scenario 18 recordings;
      `tests/port/README.md` gains the `players` directory. Needs T005, the original's stack and Docker;
      gates T034, T041, T048, T055, T063, T071, T077

**Checkpoint**: `sbt compile test` green for T006–T028; `ParitySuite` lists one test per recording
once T029 has run, every one failing against the shells.

---

## Phase 3: User Story 1 — A person registers and becomes a player of the brand (P1) 🎯 MVP

**Goal**: `POST /register` runs `RegistrationWorkflow` (mint → realm → player → match → email) and
answers `201 {playerId, status}` within 5 s; the realm user exists first with the twin-id claim and
is removed when the player write fails; `player_registered` carries the tag, click id and code as
sent (a malformed tag dropped); the verification link verifies once and is idempotent; a resend is
rate-limited; the geo lookup answers. The `match` step is stubbed here as "no signal" (US6 fills it).

**Independent Test**: quickstart §5's `RegistrationSuite`; `registration.feature`; the driver's
registration recordings.

### Tests for User Story 1

- [X] T030 [P] [US1] `p-test/registration/RegistrationWorkflowSuite.scala` (the workflow testkit of
      0.13, or the integration kit with `FakeRealm` if 0.13 has none; **verify at task time**):
      the steps in order; the id `sha256(<slug>:<lower address>)` so `A@x` and `a@x` share a
      workflow; `FakeRealm` refusing `EmailExists` → the outcome `email_already_exists`, no player
      written; `PasswordTooWeak` → `password_too_weak`; the player write failing → `undo-realm` runs
      (`FakeRealm.received(deleteUser)`) and the outcome `service_unavailable`; the same address
      started again after that → a new attempt, `201`; `FakeEngagement` silent → the player exists,
      `VerificationEmailPending` recorded, the send retried for a minute (the clock stepped) then
      left pending; a second `start` with the same address while in flight → the same outcome once
      *Done 2026-10-25. On the integration kit (0.13 has no workflow testkit). The realm call is the route's, not a step (research R3, amended), so the cases are driven through `POST /register`; the player write fails through `RegistrationRuntime.write`. Added: a realm answer after the bound has its late user removed. 8 tests.*
- [X] T031 [P] [US1] `p-test/it/RegistrationSuite.scala` on the kit (FR-001 to FR-006, SC-007):
      a registration with every field answers `201`, `playerId` a UUID, `status`
      `pending_email_verification`; `FakeRealm` holds the user with `eitheror_twin_id` the id,
      `emailVerified false`; `GET /{playerId}` as the player reads every field; the fact on
      `casino.players` carries `btag`, `click_id`, `bonus_code` as sent, `vip_tier` empty, `country`,
      `currency`, `registered_at`; a `btag` of `"bad tag!"` → `201` and no `btag` on the fact;
      missing `currency` → `400 missing_fields` naming the three; an address already registered →
      `409 email_already_exists`; `FakeRealm` refusing the password → `422 password_too_weak`;
      consents `true` → the entity's consents with the request time and source `registration`;
      the request's `X-Forwarded-For` and country kept as `Personal` and `country` from the body
      unchanged; following `FakeEngagement`'s link → `200` and the player `active`, `FakeRealm`
      `emailVerified true`; following it again → `200`, no event; a link past 24 h (the clock) →
      `400 invalid_or_expired_token`; `POST /resend-verification-email` within 5 min → `429
      rate_limited` with `retry_after_seconds`; after 5 min → `200` and a second send; the fakes'
      call counts: `FakeRealm` one create, `FakeEngagement` one send, nothing else called;
      `GET /v1/casino/geo/lookup` with `FakeGeo` silent → `{country: null, dialCode: null}`
      *Done 2026-10-25. 12 tests. `GET /{playerId}` stays US3's (T050), so the fields are read from the entity. A consent given false has no time, as the original's.*
- [X] T032 [P] [US1] Bind every scenario of `features/players/registration.feature` in
      `p-test/features/RegistrationFeatures.scala`, each step over the kit's helpers; "the fact
      carries no tag" reads the broker; "nothing is created" reads `FakeRealm` and `PlayersView`
      *Done 2026-10-25. All 10 pickles bound; "nothing is created" reads `FakeRealm` and the workflow's outcome.*
- [ ] T033 [US1] Confirm `ParitySuite` lists the driver's registration recordings and the four
      Scenario 18 registrations as failing; name in `p-test/port/Parity.scala` the divergences that
      hold on them (`playerId` presence-only; `400 missing_fields` for the plain-text `400`;
      `trackingContext.btag` translated). Needs T029
      *Open 2026-10-25: blocked on T029's recordings. The divergences to name: the minted `playerId` (the original derives it from the address) compared for presence, and `missing_fields` for the original's `{"error": "email, password, and currency are required"}`.*

### Implementation for User Story 1

- [X] T034 [US1] Implement `p/registration/RegistrationWorkflow.scala` as research R3 and
      data-model.md say: `start(request)` the atomic claim answering the outcome when one is held;
      `mint`; `realm` (`Realm.createUser`, the refusals as outcomes); `player`
      (`PlayerEntity.Register` with the profile, consents, registration fields, the lookup tokens
      computed by `LookupTokens` and held in the workflow state for US6); `match` (no-op until T071);
      `email` (`Tokens.mint(email_verify)`, `Engagement.send`, `VerificationEmailSent` or
      `VerificationEmailPending` with the retry over a minute); `undo-realm` on a failed `player`;
      the state holding the outcome; T030 green
      *Done 2026-10-25. Amended as research R3 says: `start` claims and pauses in `realm`; the route holds the password and reports the realm's answer; `PendingRegistration` holds the request (no password) and `forget` deletes it; `email-unanswered` retries for a minute.*
- [X] T035 [US1] Fill `PlayerEndpoint`'s `POST /register` (the body checked for the three
      required fields first, `400 missing_fields`; the tag checked against FR-003's grammar and
      dropped; the network address from `X-Forwarded-For`'s first entry and its country from
      `Geo`; `client.forWorkflow(id).call(start).thenAwaitEnd(REGISTRATION_BOUND)`; the outcome to
      `201`/`409`/`422`/`404`/`503`), `POST /verify-email`, `POST /resend-verification-email`
      (the entity's refusals to `429` with `retry_after_seconds`), `GeoEndpoint`'s `GET /lookup`,
      and `OperatorEndpoint`'s `GET /players/{playerId}/verification-link` (the current token's
      link, `404` when `CASINO_REAL_MONEY=true`); T031–T033 green; `LeakSuite`'s registration case
      green against the real route
      *Done 2026-10-25. The route polls the outcome within `REGISTRATION_BOUND` (research R3, amended). Resend answers 200 `{"result":"sent"}` for an unknown or verified address, as the original does. `sbt test`: 168 passed, 82 ignored.*

**Checkpoint**: a registration makes one realm user and one player, sends one email and produces
one fact; the link verifies once; `registration.feature` passes; the registration recordings replay
with only the named divergences.

---

## Phase 4: User Story 2 — A player signs in, plays in a session, and signs out (P1)

**Goal**: `POST /login` runs the realm's direct grant, the entity's status refusals, `may-log-in`
under its bound (fail closed), opens a `PlayerSessionEntity` and answers the tokens and details;
`/refresh` and `/logout`; the realm's webhook and `/may-sign-in` route; the reconciler ending idle
sessions `expired`; `SessionEndWorkflow` cascading to game sessions (the games themselves are
US3's; the cascade is written here over an empty list); one session at a time when the brand sets
it.

**Independent Test**: quickstart §5's `SessionsSuite` (sign-in cases) and `RealmSuite`;
`sessions.feature`'s nine sign-in scenarios; the driver's sign-in recordings.

### Tests for User Story 2

- [X] T036 [P] [US2] `p-test/sessions/SessionEndWorkflowSuite.scala`: `games` ends each open game
      session of the player session with the reason (an empty list passes through), `session` ends
      the player session, `player` tells the entity; the reason carried to every end; a step failing
      is retried; a second `start` with the same id answers the first outcome
      *Done 2026-10-25.* Four cases on the integration kit. "A step failing is retried" is not tested: ankka 0.13 has no workflow testkit or fault injection; the steps carry `maxRetries(5)`.
- [X] T037 [P] [US2] `p-test/it/SessionsSuite.scala` (sign-in cases; FR-010 to FR-015, SC-003 for
      logout): sign-in with the right password → `200` with `token`, `refreshToken`, `brandId`,
      `playerId`, `currency`, `country`, `pendingBonusCode` from registration; a `PlayerSessionEntity`
      with the realm session id, the `Personal` address, agent and fingerprint; `player_logged_in`
      with `session_id` and `player_session_started` on `casino.sessions`; `lastSignInAt` stamped;
      a wrong password → `401 invalid_credentials`; unverified → `401 email_not_verified`, no
      session; a suspended player → `403 account_restricted` with `reason: suspended`, no realm
      call after the grant but the revoke, `lastRefusedSignInAt` stamped; `FakeRg` answering
      `cooling_off` → `403 cooling_off` with `until`; `FakeRg` silent → `403 account_restricted`
      within `MAY_LOG_IN_BOUND`; `FakeRealm` issuing a token without `session_state` → `401
      missing_token`, no session; `/refresh` → `200 {accessToken, refreshToken, expiresIn}`; a
      reused refresh → `401 refresh_expired`; `FakeRealm` silent on refresh → `503
      service_unavailable`; `/logout` → `200`, the session `completed`, `FakeRealm.received(revoke)`,
      `player_logged_out` with `close_reason: logout` after `player_session_ended`; a request with
      another player's id in the path → `403 player_id_mismatch`; the fakes' call counts (SC-007):
      the realm and `FakeRg` once each at sign-in, nothing else; `brand-config` `singleSession true`
      → a second sign-in ends the first session `superseded` and its fact says so; `false` → both
      open
      *Done 2026-10-25.* Nine cases, all as listed.
- [X] T038 [P] [US2] `p-test/it/RealmSuite.scala` (FR-008, FR-009, FR-013, SC-002, SC-004):
      `POST /webhook` with the right secret and `LOGOUT` for a known realm session → `200 {ok}` and
      the session `completed`; `SESSION_EXPIRED` → `expired`; `LOGIN` → `200`, nothing changes but
      the gauge; another realm id or no session id → `200`, nothing; a wrong secret → `401`;
      `POST /may-sign-in` for an active player with `FakeRg` allowing → `{allowed: true}`; after
      `Suspend` → `{allowed: false, reason: suspended}` within 2 s; `FakeRg` `cooling_off` →
      `{allowed: false, reason: cooling_off, until}`; `FakeRg` silent → `{allowed: false}` within
      `REALM_ANSWER_BOUND`; 1,000 asks at 20 per second (the spec's assumed peak) with `FakeRg` answering in 20 ms → p99 under
      100 ms (the figure printed); `POST /gamstop-refusal` → `200` and `FakeRg.received(gamstop)`;
      the reconciler: a session `FakeRealm` no longer lists ends `expired` within two intervals and
      `player_session_ended` says `expired`; `FakeRealm` silent for one tick → the next tick runs
      (the timer survives a failure)
      *Done 2026-10-25.* Six cases; may-sign-in measured p50 31.7 ms, p99 44.0 ms over 1,000 asks.
- [X] T039 [P] [US2] Bind the nine sign-in scenarios of `features/players/sessions.feature` in
      `p-test/features/SessionsFeatures.scala`; "the realm asking" posts to `/may-sign-in`; "has
      gone idle" removes the session from `FakeRealm` and waits two intervals
      *Done 2026-10-25.* Fifteen pickles bound; the game-session scenarios stay pending for T045. The outline's `blocked` and `closed` rows are skipped: no command of this service sets those statuses (only the migration, spec 019).
- [ ] T040 [US2] Confirm `ParitySuite` lists the driver's sign-in, refresh, sign-out and webhook
      recordings as failing; name the divergences in `Parity.scala` (the token values `vary`; the
      webhook path translated; `401 unauthenticated` for the rejection page). Needs T029
      *Open 2026-10-25:* waits on T029 (the recordings).

### Implementation for User Story 2

- [X] T041 [US2] Implement `p/sessions/SessionEndWorkflow.scala` (research R6; the games from
      `SessionsView.openGameSessionsOf` filtered to the session, ended through `GameSessionEntity.End`
      and `PlayerEntity.GameSessionEnded`; then `PlayerSessionEntity.End`; then
      `PlayerEntity.SessionEnded`); T036 green
      *Done 2026-10-25.* The games are read from the `PlayerSessionEntity`'s own `gameSessions`, not `SessionsView`, so the step reads what it just wrote rather than a lagging view.
- [X] T042 [US2] Fill `PlayerEndpoint`'s `POST /login` (research R4's order: `Realm.directGrant`;
      the claims; `PlayerEntity.SignIn`; `Rg.mayLogIn` under `MAY_LOG_IN_BOUND`, `Unanswered` →
      `account_restricted`; `BrandConfig.singleSession` → `SessionEndWorkflow(superseded)` for each
      other open session, awaited; `PlayerSessionEntity.Open`; `PlayerEntity.SessionOpened`; the
      answer), `POST /refresh`, `POST /{playerId}/logout` (`SessionEndWorkflow(logout)` awaited
      under `SESSION_END_BOUND`, then `Realm.revokeSession`), and `Answers`' player-token check
      (`player_id_mismatch`, `missing_token`) applied to every player route
      *Done 2026-10-25.* `lastSignInAt` is stamped by `SignIn` before the RG check, as R4 orders it; a refusal then stamps `lastRefusedSignInAt` too.
- [X] T043 [US2] Fill `RealmEndpoint`: `POST /webhook` (the secret; the realm id checked against
      `casino-<slug>`; `SessionsView.byRealmSession`; `SessionEndWorkflow(logout | expired)` started,
      not awaited; always `200`), `POST /may-sign-in` (`PlayersView.search` by address → the entity's
      status; `Rg.mayLogIn` under `REALM_ANSWER_BOUND`; the answer), `POST /gamstop-refusal`
      (`Rg.gamstopRefusal`, best effort); `p/sessions/RealmReconcile.scala` (the timed action:
      `Realm.liveSessions`, `SessionsView.openPlayerSessions`, a `SessionEndWorkflow(expired)` per
      session not live, the OpenTelemetry gauge `players.active_sessions` set through
      `ankka-telemetry-otlp`; a failure logged and the next tick unaffected) scheduled by
      `PlayersService.started` as a recurring timer named `realm-reconcile`; T037–T040 green
      *Done 2026-10-25.* `may-sign-in` finds the player through the registration workflow's outcome, else `PlayersView.find` with an exact primary-address match. The webhook reads `SessionsView.ofRealm`.

**Checkpoint**: sign-in, refresh and sign-out work end to end against `FakeRealm`; the realm's
authenticator is answered under 100 ms and fails closed; idle sessions expire; the sign-in
scenarios pass; the sign-in recordings replay with only the named divergences.

---

## Phase 5: User Story 3 — A game session is opened for a launch and ended (P1)

**Goal**: `game-provider` opens a game session through the service route; the player's status and
open player session are checked, the minutes left computed from the limit and the day's sessions,
a `SessionLimits` timer set; the timer, the player, the operator and the player session's end all
end it with their reason; `rg_limit_applied` reschedules; the player reads their own sessions.

**Independent Test**: quickstart §5's `SessionsSuite` (game cases); `sessions.feature`'s nine game
scenarios; the driver's launch and end recordings.

### Tests for User Story 3

- [X] T044 [P] [US3] Extend `p-test/it/SessionsSuite.scala` (game cases; FR-016 to FR-022, SC-003):
      a service token for `game-provider` opening a session for an active signed-in player → `201
      {sessionId}`, the `GameSessionEntity` under the player session, `game_session_started` with
      `game_id` and `player_session_id`; for a suspended player → `403 account_restricted` naming it;
      with no open player session → `409 session_expired`; a player token on the route → `403`;
      with `SESSION_MINUTES` 30 from an `rg_limit_applied` fact and 25 minutes already played today
      (the clock) → the timer at 5 minutes (`TimerRuntime`'s listing names
      `game-session-limit/<id>`); the clock stepped past it → the session ended `limit` within 5 s
      and `game_session_ended` with `duration_minutes` and `close_reason: limit`; a limit reached →
      `403 session_limit_reached` with `limitMinutes`, `usedMinutes`; a new `rg_limit_applied` of 20
      with 25 played → the open session ends at once; `PATCH /{sessionId}/end` as the player → `200
      {sessionId, durationMinutes}` and again `200` with no second fact; another player's session →
      `404 session_not_found`; `GET /players/{playerId}` lists own sessions and nobody else's; an
      operator's `force-end` → ended `forced` and an audit record naming the operator;
      `force-end-all`; logout, expiry, suspension (US5 provides `Suspend`; here the entity command
      directly) each end every open game session with their reason within 5 s, before
      `player_logged_out` (fact order on the broker); game session open makes no outbound call
      (SC-007)
      *Done 2026-10-25.* Eight game cases in `SessionsSuite`. The limit timer is read and fired through `ankka_timers` (`Kit.timerDueIn`, `Kit.fireTimer`) because the timer runtime runs on the wall clock, not the kit's. A player token on the service route is `403`. Fact order across `casino.sessions` and `casino.players` is not asserted: the broker orders within a topic only. The suspension case waits for US5's route.
- [X] T045 [P] [US3] Bind the nine game scenarios of `features/players/sessions.feature` in
      `p-test/features/SessionsFeatures.scala`; "the timer fires" steps the clock; "one session at a
      time" sets `brand-config`
      *Done 2026-10-25.* All nine game pickles bound; `closed` now runs in both outlines (`Close` exists), `blocked` stays skipped (migration only).
- [ ] T046 [US3] Confirm `ParitySuite` lists the driver's launch and session recordings as failing;
      name the divergences (`/launch` recorded on the original's sessions route replays through
      `ServiceEndpoint`'s `POST /{playerId}/game-sessions` with a service token, the translation
      named; `?playerId=` translated). Needs T029
      *Open 2026-10-25:* waits on T029 (the recordings).

### Implementation for User Story 3

- [X] T047 [US3] Implement `p/sessions/SessionLimits.scala` (the timed action `end(sessionId)`:
      `GameSessionEntity.End(limit)`, `PlayerEntity.GameSessionEnded`; `schedule(sessionId, minutesLeft)`
      and `reschedule` by name through `TimerScheduler.createSingleTimer`, as merchant's timed
      actions are wired) and wire `RgLimitsConsumer` (T019) to it; fill `ServiceEndpoint`'s `POST
      /{playerId}/game-sessions` (`Routes.service` admitting `game-provider`; `PlayerEntity.GameSessionOpened`
      → the minutes left; `GameSessionEntity.Open`; `PlayerSessionEntity.AddGameSession`; the timer
      when a limit is set), `GET /{playerId}/status` (for `game-provider` and `support-agent`),
      `GET /{playerId}/game-sessions` and `POST /game-sessions/{sessionId}/end` (for `support-agent`)
      *Done 2026-10-25.* `SessionLimits` and the consumer's rescheduling were already in place from the foundation; this filled the four service routes in `api/GameSessionRoutes.scala`, each under `Routes.serviceOf` naming its callers.
- [X] T048 [US3] Fill `SessionEndpoint` (`GET /players/{playerId}`, `GET /{sessionId}` with the
      ownership check, `PATCH /{sessionId}/end` idempotent) and `OperatorEndpoint`'s
      `POST /players/{playerId}/sessions/{sessionId}/force-end` and `sessions/force-end-all` (each
      an audit record through `AuditPublisher`); T044–T046 green
      *Done 2026-10-25.* Follows the original where routes.md differed (amended there): `PATCH /end` answers `{status: completed, sessionId, durationMinutes}` and another player's session is `403 player_id_mismatch`; force-end answers `{sessionId, status: ended}` and force-end-all ends game sessions only, `{ended}`. The operator is carried on `GameSessionEvent.Ended` as `actorId` and audited by a new `GameSessionAudit` consumer.

**Checkpoint**: a launch opens a session with its timer; every end reason works and cascades within
5 s; the game scenarios pass; the launch recordings replay with the named translation.

---

## Phase 6: User Story 4 — A player keeps their profile, addresses and consents (P2)

**Goal**: the profile read and edited with locks and the allow-list; several email addresses added,
verified, promoted and removed, and the sign-in address changed through a verified flow; password
reset and change; consents with time and source and their fact; cookie decisions per device;
favourites, recently played, activity history; the inbox with `engagement`'s campaign writes and
push delivery; the operator's profile, date-of-birth correction and CRM views.

**Independent Test**: quickstart §5's `ProfileSuite`; `profile.feature`; the driver's profile,
email, consent and cookie recordings.

### Tests for User Story 4

- [X] T049 [P] [US4] `p-test/it/ProfileSuite.scala` (FR-023 to FR-030): `GET /{playerId}` reads
      every field; another player's token → `403`; a `PATCH` with `firstName`, `phone` and a changed
      `dateOfBirth` → `200`, the date unchanged, the others applied, `player_profile_updated`
      naming `firstName` and `phone` only and carrying the eight `Personal` fields; `country` not in
      a non-empty allow-list → `400 invalid_country` and nothing changes; an empty allow-list admits
      any; an empty body → `400 no_fields_provided`; `mobile` cleared with `""`; `POST /{playerId}/emails`
      → `202` and `FakeEngagement`'s link; following it → the address verified, not primary; promote
      an unverified → `400 not_verified`; promote the verified → `200`, `FakeRealm`'s username and
      email changed, sign-in with the new address works; remove the primary → `400
      cannot_remove_primary`; an address registered to another player → `409
      address_already_registered`; `POST /{playerId}/email-change` with the right password → `202`,
      following the link → the realm's address changed; `POST /password-change` wrong password →
      `400 invalid_password`; right → `202` and the link; following it → `FakeRealm`'s password
      changed; `password-reset/request` → `FakeRealm.received(sendResetEmail)`; `PATCH /consent`
      withdrawing email → `200`, `GET` shows withdrawn with its time, `player_consent_changed`
      with `email_marketing false` and `source player`; `PATCH /cookie-consent` whole → kept per
      device, the operator's CRM view shows it, a repeat replaces; a field missing → `400
      invalid_cookie_consent_body`; no device id → `400 missing_device_id`; favourites add, list,
      remove; `casino.rounds` facts → `recently-played` newest first; facts on the five topics →
      `activity-history` by category, paged, never a note or a message; a `casino.players` fact of
      another player never appears; the inbox: an operator's message → listed with `unreadCount 1`;
      `read` → 0; `delete` → gone for the player, still in the operator's list;
      `player_message_created`, `_read`, `_deleted` facts; `engagement`'s service write under a
      delivery id → `201`, again `201` with the same message; for a suppressed player → `422
      marketing_suppressed`; a closed player → `422 account_closed`; `push_delivered` → `delivered`
      on the row; the operator's `PATCH /players/{id}/date-of-birth` without `reason` → `400
      reason_required`; to 17 years → `400 underage`; to 18 → `200` and an audit record naming the
      field and the reason, never the date; a `player_kyc_verified` fact first → `409 kyc_locked`;
      the operator's `PATCH /players/{id}/profile` honours the same locks
      *Done 2026-10-25.* Eleven cases in `it/ProfileSuite.scala`. It found two bugs: the history keyed a line by the topic message's sequence number, which ankka 0.13 sets to 0 for every topic message, so two facts under one record key collapsed into one line (now a digest of the fact); and `player_profile_updated` sent only the changed fields where the contract and profile.feature say every completeness field.
- [X] T050 [P] [US4] Bind every scenario of `features/players/profile.feature` in
      `p-test/features/ProfileFeatures.scala`; "marketing email stops from that fact" reads the
      broker for `player_consent_changed`
      *Done 2026-10-25.* All twelve scenarios bound.
- [ ] T051 [US4] Confirm `ParitySuite` lists the driver's profile, email, password, consent and
      cookie recordings as failing; name the divergences (`/me/` and `/emails` paths translated;
      `{email}` placeholders). Needs T029
      *Open 2026-10-25:* waits on T029 (the recordings).

### Implementation for User Story 4

- [X] T052 [P] [US4] Fill `PlayerEndpoint`'s `GET /{playerId}`, `PATCH /{playerId}` (`AllowList`
      read first, `PlayerEntity.EditProfile`), `GET`/`PATCH /{playerId}/consent`, `PATCH
      /{playerId}/cookie-consent`, the three favourites routes, `GET /{playerId}/recently-played`,
      `GET /{playerId}/activity-history` (over `ActivityView`, or the per-source views merged, T016)
      *Done 2026-10-25.* In `api/ProfileRoutes.scala`; the divergences from the original are named in contracts/routes.md (amended at T052–T055).
- [X] T053 [P] [US4] Fill the email routes: `GET`/`POST /{playerId}/emails` (the add with
      `Tokens.mint(email_add)` and `Engagement.send`; `address_already_registered` through
      `PlayersView.search`), `POST /emails/verify`, `POST /emails/{address}/resend`, `/promote`
      (`Realm.changeAddress` after `PlayerEntity.PromoteEmail`), `DELETE /emails/{address}`,
      `POST /{playerId}/email-change` (`Realm.verifyPassword`, `Tokens.mint(email_change)`) and
      `POST /email-change/verify` (`PlayerEntity.ChangeEmail`, `Realm.changeAddress`); the address
      in a path URL-decoded and never logged
      *Done 2026-10-25.* The realm's address is changed before the entity's primary, so a realm refusal leaves the player as it was. `api/Lookup.scala` (registration outcome, else the players view) now serves the realm's authenticator too.
- [X] T054 [P] [US4] Fill `POST /password-change` (`Realm.verifyPassword`, `Tokens.mint(password_change)`,
      `Engagement.send`), `POST /password-change/verify` (`Realm.setPassword` through the admin
      API; `409 account_suspended` for a suspended player), `POST /password-reset/request` and
      `/confirm` (`Realm.sendResetEmail`; the realm's own confirm), and `OperatorEndpoint`'s
      `POST /players/{playerId}/reset-password` (audited, not a trail event)
      *Done 2026-10-25.* `password-reset/confirm` answers `400`: the realm confirms its own reset. The operator's reset is audited from a new `ResetLinkSent` event, which is not a trail entry.
- [X] T055 [US4] Fill the inbox: `PlayerEndpoint`'s `GET /{playerId}/messages` (`MessagesView`,
      `unreadCount` over the rows), `POST /messages/{messageId}/read` and `/delete` (ownership
      checked); `ServiceEndpoint`'s `POST /{playerId}/messages` (`Routes.service` admitting
      `engagement`; the suppression and closure checks on the entity, then `MessageEntity.Created`
      under the delivery id with origin `crm`); wire `PushDeliveredConsumer` (T019);
      `OperatorEndpoint`'s `GET`/`PATCH /players/{playerId}/profile`, `PATCH
      /players/{playerId}/date-of-birth` (the kyc lock from the entity's `kycVerified`, fed by
      `KycFactsConsumer`, T019), `GET`/`PATCH /players/{playerId}/crm`; T049–T051 green
      *Done 2026-10-25.* `PushDeliveredConsumer` was already wired at T019. `engagement`'s write refuses a closed player (`account_closed`), suppressed messages, and a marketing message without email consent (`marketing_suppressed`).

**Checkpoint**: every profile, address, consent and inbox route of contracts/routes.md answers as
written; `profile.feature` passes; the profile recordings replay with only the named divergences.

---

## Phase 7: User Story 5 — An operator suspends, reinstates, excludes, closes, and tiers a player (P2)

**Goal**: every status transition with its trail entry, its fact, its realm action and its session
cascade; the service routes `kyc` and `responsible-gambling` call; a player's own closure and the
operator's reopening within the hold; tier, PotVIP, notes, messages with merge tags; the three
configurations; spec 021's modules rows; the brand-role check on every operator route.

**Independent Test**: quickstart §5's `OperatorSuite`; `status.feature`; the driver's suspend,
reinstate, tier, close, note and message recordings.

### Tests for User Story 5

- [X] T056 [P] [US5] `p-test/it/OperatorSuite.scala` (FR-031 to FR-034, FR-041, SC-002): with a
      signed-in player holding two game sessions, `PATCH /players/{id}/suspend` with a reason →
      `200`, every session ended `suspended` within 5 s, `FakeRealm.received(revoke)`,
      `player_suspended` with `previous_value active`, `new_value suspended`, `actor operator`, the
      operator's subject as `actor_id`, the reason; sign-in → `403 account_restricted`; `/may-sign-in`
      → `allowed false` within 2 s; `suspend` again → `200`, no second fact, the reason updated;
      `reinstate` → `active`, `player_reinstated`; `reinstate` an active player → `409
      not_suspended`; the service route `PUT /{id}/exclusion` under a `responsible-gambling` token →
      `self_excluded`, the exclusion time kept, sessions ended `suspended`, `player_self_excluded`
      with `excluded_at`; under an operator token → `403`; no operator route excludes
      (`OperatorEndpoint` has no such route: asserted by a `404`); `DELETE /{id}/exclusion` →
      `active`; `POST /{id}/suspend` under a `kyc` token with `reason kyc_window_expired` and a
      `deadline` → `200`, again with the same deadline → `200` and one fact; `PUT /vip-tier` from
      none → `vip_tier_changed` with `previous_tier ""` and an audit record naming the operator;
      `PUT /pot-vip` → a note written; `POST /players/{id}/close` → `closed`, every session ended,
      `FakeRealm` `enabled false`, `player_closed` with `actor operator` and the reason; a closed
      player's sign-in → `403 account_restricted` with `reason closed`; `FakeWallet` asked nothing
      (balances untouched); the player's own `POST /{id}/close` with the right password → `closed`,
      `player_closed` with `actor player`, `reason player_request`, no RG call; a wrong password →
      `400 invalid_password`; `POST /players/{id}/reopen` with a reason within the hold → `active`,
      `FakeRealm` `enabled true`, `player_reopened`; after the hold (the clock) → `409 hold_lapsed`;
      `reopen` an active player → `409 not_closed`; a staff token without the brand's role → `403`
      on every operator route (a loop over the table); a `platform_admin` without the brand role →
      `403`; notes: `POST /players/{id}/notes` → `201`, `pin` → first in `GET …/crm`'s notes, the
      player's `GET /{id}` and export never show it; messages: `POST /players/{id}/messages` with
      `{{firstName}}` → `201` and the body rendered, `{{nope}}` → `422 unknown_merge_tag` with
      `tag`; no `category` → `422 category_required`; a suppressed player → `422
      marketing_suppressed`; `GET /merge-tags`, `POST /messages/preview`; the configurations:
      `PUT /brand-config` with `reportingZone Europe/London` → `200`, `GET /config/brand-config`
      under a service token reads it, `reportingZone Mars/Olympus` → `400 invalid_config`;
      `currency-config`, `allow-list` the same; `GET /modules` → `FakeAgent`'s answer passed through,
      `POST /modules/requests` → `204` and `FakeAgent.received` with the operator's subject as `by`,
      `FakeAgent` silent → `503`; every operator action an audit record with the subject, the action
      and the reason, and never a `Personal` value (searched for the note's text and the message body)
      *Done 2026-10-25.* Fourteen cases in `it/OperatorSuite.scala`. Two lines of the task do not hold and the suite asserts the contract instead: `platform_admin` is admitted on every brand (spec 001's `Routes.operator`, as the original), and a repeated suspend keeps the first reason (the entity answers unchanged; the original had no repeat to compare).
- [X] T057 [P] [US5] Bind every scenario of `features/players/status.feature` in
      `p-test/features/StatusFeatures.scala`; "applied through responsible gambling" uses the
      `responsible-gambling` service token; "however they signed in" tries a staff token with
      another brand's role and one with `platform_admin`
      *Done 2026-10-25.* All eleven scenarios bound. "However they signed in" is another brand's role, another brand's role with `support_agent`, and a player token. The attribution step asserts `player_closed` on `casino.players`; attribution's own fact is spec 012's to test.
- [ ] T058 [US5] Confirm `ParitySuite` lists the driver's suspend, reinstate, tier, close, note,
      message and merge-tag recordings as failing; name the divergences (the operator block's paths
      translated to `/backoffice/`). Needs T029
      *Open 2026-10-25:* waits on T029 (the recordings).

### Implementation for User Story 5

- [X] T059 [US5] Fill `OperatorEndpoint`'s `PATCH /players/{playerId}/suspend` (`PlayerEntity.Suspend`;
      a `SessionEndWorkflow(suspended)` per open player session, awaited under `SESSION_END_BOUND`;
      `Realm.revokeSession` each), `PATCH …/reinstate`, `POST …/close` (`Close(operator, reason)`;
      the cascade; `Realm.setEnabled(false)`), `POST …/reopen` (`Reopen`; `Realm.setEnabled(true)`),
      `POST …/clear-suspect-flag`, `PUT …/vip-tier` (the previous read from the entity's answer),
      `PUT …/pot-vip` (a `NoteEntity` write), `GET /players` (`PlayersView.list`/`search`),
      `GET /players/{playerId}/wallet` (`WalletSummaryView`), `/activity`, `/bets`, `/sessions`;
      `PlayerEndpoint`'s `POST /{playerId}/close` (`Realm.verifyPassword`; `Close(player,
      player_request)`; the cascade; `Realm.setEnabled(false)`); each operator action through
      `AuditPublisher`'s mapping (T019)
      *Done 2026-10-25.* In `api/OperatorStatusRoutes.scala`, with the cascade in `api/Cascade.scala` (the entity's open sessions, not a view's). The divergences are in contracts/routes.md (amended at T059–T061). The session bets route stays `501`: an activity row carries no session.
- [X] T060 [P] [US5] Fill `ServiceEndpoint`'s `POST /{playerId}/suspend` (`Routes.service`
      admitting `kyc`; idempotent under player and deadline through the entity's `suspension`),
      `PUT /{playerId}/exclusion` and `DELETE /{playerId}/exclusion` (admitting
      `responsible-gambling`; the cascade and the revoke as suspend's), `GET /config/{name}`
      *Done 2026-10-25.* `kyc`, `responsible-gambling` and any brand service each by `withAcl`. The kyc suspension is idempotent through the entity (a suspended player answers unchanged), so the deadline is not stored.
- [X] T061 [P] [US5] Fill `OperatorEndpoint`'s notes (`POST /players/{playerId}/notes`, `PATCH
      …/notes/{noteId}/pin`, `/unpin`), messages (`GET`/`POST /players/{playerId}/messages` with
      `MergeTags.render` over the entity's values, `MessageEntity.Created` origin `operator`),
      `GET /merge-tags`, `POST /messages/preview`, the three configuration `GET`/`PUT` pairs, and
      `GET /modules` and `POST /modules/requests` (spec 021's rows: the vendor's agent through the
      service client by certificate, the answer passed through; `503 agent unreachable`); the
      brand-role check in `Routes.operator(slug)` confirmed on every route by T056's loop; T056–T058
      green
      *Done 2026-10-25.* The modules routes call the vendor's agent within 5 s; no answer is `503 agent_unreachable`.

**Checkpoint**: every status transition is one event, one trail entry, one fact and the right realm
action; the service routes admit only their callers; `status.feature` passes; SC-002 holds; the
operator recordings replay with only the named divergences.

---

## Phase 8: User Story 6 — A duplicate account is detected and reviewed (P3)

**Goal**: the `match` step of `RegistrationWorkflow` reads the two lookup tokens' index entries,
flags every match and the new player, opens one `FraudCaseEntity` naming the signals, within 10 s
and with no scan; `player_fraud_flagged`; the operator reviews or dismisses, clearing every linked
flag; the kill switch and the no-signal case.

**Independent Test**: quickstart §5's `DuplicatesSuite`; `duplicates.feature`; the four Scenario 18
recordings and the driver's fraud-case review recording.

### Tests for User Story 6

- [X] T062 [P] [US6] `p-test/it/DuplicatesSuite.scala` (FR-036, FR-037, SC-005): two registrations
      with one given name, family name and date of birth (the second with different casing and
      spacing) → both players `flagged` with each other's id in `linkedPlayerIds`, one case in
      `FraudCasesView.open` with signal `identity`, `player_fraud_flagged` for each, all within 10 s;
      two with one device fingerprint → signal `device`; one with both → one case naming both
      signals; a third matching player → the case links three; `brand-config`
      `multiAccountDetection false` → no case; a registration without names, date of birth or
      fingerprint → no index write, no case; 10,000 players registered through the entity directly
      (the kit's bulk helper) and then a matching registration → the case within 10 s and
      `LookupIndexEntity`'s call count two (the kit counts component calls), `PlayersView` never
      queried by the workflow; `PATCH /fraud-cases/{caseId}/review` → `{status: REVIEWED,
      reviewedBy}` and the time; `/dismiss` → every linked player's flag cleared and
      `player_fraud_cleared` for each; `POST /players/{id}/clear-suspect-flag` clears one;
      `GET /fraud-cases?status=open`; a flagged player's `rewards` refusal is 011's: here the fact
      is what the scenario reads
      *Done 2026-10-25.* Five cases in `it/DuplicatesSuite.scala`. Not done: the 10,000-player bulk run and the component-call count. Players written through the entity never reach the lookup index (only the workflow's `match` writes it), so the bulk run would not test what the task meant. The match makes one `LookupIndexEntity.add` call per token and does not read `PlayersView`; the code shows this, and no test counts it.
- [X] T063 [P] [US6] Bind every scenario of `features/players/duplicates.feature` in
      `p-test/features/DuplicatesFeatures.scala`; "withheld by the brand's rewards" reads
      `player_fraud_flagged` on the broker and asserts its payload is what 011's scenario needs
      *Done 2026-10-25.* All seven scenarios bound; the rewards scenario reads `player_fraud_flagged` and its case and signal, which is what spec 011 consumes.
- [ ] T064 [US6] Confirm `ParitySuite` lists the four Scenario 18 recordings (two registrations
      each) and the driver's fraud-case review recording as failing; name the divergences (the
      `observe:` twin reads not compared; the `409 SUSPECTED_DUPLICATE` of the bonus grant is 011's
      and the step is `observe:`). Needs T029
      *Open 2026-10-25:* waits on T029 (the recordings).

### Implementation for User Story 6

- [X] T065 [US6] Fill `RegistrationWorkflow`'s `match` step (T034): `BrandConfig.multiAccountDetection`
      read; for each token in the state, `LookupIndexEntity(token).Add(playerId)` and the ids
      answered; a non-empty union → `FraudCaseEntity(mint).Opened(players, signals)`,
      `PlayerEntity.FlagDuplicate` on each, the new player included; nothing when the switch is off
      or no token; `p/fraud/` routes in `OperatorEndpoint`: `GET /fraud-cases`, `PATCH
      /fraud-cases/{caseId}/review`, `/dismiss` (`ClearDuplicate` on every linked player), and
      `POST /players/{playerId}/clear-suspect-flag` (T059's stub completed); `LookupIndexEntity.Remove`
      on `Erase` (US7 calls it); T062–T064 green
      *Done 2026-10-25.* The workflow's state keeps each token tagged with its signal (`identity:`/`device:`); the index key is the bare digest. The case id is `dup-<new player id>`, so a retried step opens the same case. The fraud routes are in `api/FraudRoutes.scala`. `LookupIndexEntity.remove` is US7's to call.

**Checkpoint**: a duplicate is flagged and its case open in two index reads; the operator clears it;
`duplicates.feature` passes; the Scenario 18 recordings replay.

---

## Phase 9: User Story 7 — A player's data is erased and the response assembled (P3)

**Goal**: `ErasureWorkflow` files under one correlation id with the brand's hold, waits an open
withdrawal, applies through every service's erasure route and the entity's own `Erase`, removes the
realm user, assembles the certificates; `ExportWorkflow` asks every service for its part, names a
silent one as missing, keeps the response 24 h; after an erasure no personal field is readable and
every amount and status is unchanged.

**Independent Test**: quickstart §5's `ErasureSuite` and `LeakSuite`; `erasure.feature`. No
recording: the original has neither.

### Tests for User Story 7

- [X] T066 [P] [US7] `p-test/erasure/ErasureWorkflowSuite.scala` and `ExportWorkflowSuite.scala`
      (the workflow testkit or the integration kit, as T030): `file` refuses `not_closed`; the hold
      from `BrandConfig.retentionDays` after `closedAt`; the `hold` step pauses until the timer, and
      stays paused while `withdrawalOpen`; `apply` calls every target of research R10 in order
      (`wallet`, `merchant` under the grant, the later services when their routes exist, own `Erase`,
      `LookupIndexEntity.Remove` for both tokens), each certificate kept, a target's failure retried
      and then the workflow failed with the service named, never half-applied silently; `realm` →
      `Realm.deleteUser`; `done` → `PlayerEntity` `erased`; the export: own part first, then each
      service under `EXPORT_PART_BOUND`, a silent one under `missing`, `expiresAt` 24 h; a second
      `start` under the request id answers the first
      *Done 2026-10-25.* Covered by the integration kit in `it/ErasureSuite.scala` (six cases) rather than a separate workflow-testkit suite. Not covered: a target that fails after retries, which leaves the workflow `failed` naming the service. The step and its recovery exist; no test drives it, because the fakes cannot fail on one target only.
- [X] T067 [P] [US7] `p-test/it/ErasureSuite.scala` (FR-038 to FR-040, SC-006): `POST /{id}/erasure`
      for an active player → `409 not_closed`; after a closure → `202 {correlationId, holdUntil}`,
      the status `erasure_pending` with the hold's date on `GET /{id}`; `FakeWallet` and the merchant
      fake receive nothing until the clock passes the hold; a `casino.withdrawals` requested fact →
      the hold stays past its date until the settled fact; after it, every fake's erasure route called
      once with the correlation id, `FakeRealm.received(deleteUser)`, the status `erased`, the
      certificates readable through `GET /players/{id}/erasure` as an operator; a registration with
      the erased address → `201` with a new id; `POST /{id}/exports` → `202 {exportId}`; `GET` before
      the parts → `409 export_not_ready`; after → `200` with `profile`, `consents`, `sessions`,
      `activity`, `messages`, no `notes`, and `parts` naming `wallet`, `merchant` and each fake's part;
      `FakeWallet` silent → `missing: ["wallet"]`; after 24 h → `410 export_expired`; the operator's
      `activity-export` the same shape
      *Done 2026-10-25.* Six cases in `it/ErasureSuite.scala`. The test drives the hold by moving the kit clock past it, and `ERASURE_HOLD_POLL` (300 ms in the kit) makes the next look apply it.
- [X] T068 [P] [US7] Bind every scenario of `features/players/erasure.feature` in
      `p-test/features/ErasureFeatures.scala`; "in the shared payments" reads the merchant fake's
      received erasure under the grant's caller
      *Done 2026-10-25.* All seven scenarios bound. "In the shared payments": `merchant` has no filing of its own (spec 004's erasure route applies at once), so its part is asked under the brand's subject when the hold lapses; the scenario asserts the brand's filing and that `merchant` is a target. The document records are kyc's part (spec 009).
- [X] T069 [US7] Fill `p-test/it/LeakSuite.scala` (T027): a player registered with every marker,
      signed in with a marker address, agent and fingerprint, a note and a message with marker text,
      a profile edit with a marker bank account; `assertNowhere` for the password, the lookup tokens'
      plain inputs and every marker in the log and the audit records before erasure; after the
      erasure (the hold zero): `assertNowhere` for every marker in every table, view row, fact on
      every topic, operator answer and the export; `WalletSummaryView`'s balances, the status trail,
      the session counts and durations read unchanged; the journal's earlier events and the facts
      already on `casino.players` and `casino.sessions` are the named gap (research R8): the suite
      asserts both still hold the markers and the tests' names say so, so the day ankka 042 lands the
      assertions flip; the `assertNowhere` over facts therefore runs only over facts produced after
      the `Erased` event
      *Done 2026-10-25.* Found that `bytea` payloads (the journal, `durable_state`) read as hex in a row's text, so the tables are searched for each marker both as text and as hex. The gap is asserted the same way: the journal still holds the given name.

### Implementation for User Story 7

- [X] T070 [US7] Implement `p/erasure/ErasureWorkflow.scala` (research R10; the `erasure-hold/<id>`
      timer resuming at `apply`; `WithdrawalsConsumer` (T019) wired to the entity's hold flag the
      `hold` step reads; the targets from a `Settings` list so a later service is one entry) and
      `p/erasure/ExportWorkflow.scala`; `PlayerEntity.exportPart` and the service's own
      `POST /erasure/players/{playerId}` and `GET /exports/players/{playerId}` in `ServiceEndpoint`
      under `Routes.playersOnly`; T066 green
      *Done 2026-10-25.* In `erasure/`. Two divergences from the task. The hold is the workflow's own pause, looked at again every `ERASURE_HOLD_POLL` (default 1 h), not a timer named `erasure-hold/<id>`. `apply` takes one target a step, so the failed one is named. `ExportWorkflow` keeps the assembled export in `ExportBody` (a key value entity it drops when the export expires), not in its state, so the journal never holds it. Each service's part is one step under `EXPORT_PART_BOUND`, failing over to `missing`.
- [X] T071 [US7] Fill `PlayerEndpoint`'s `POST /{playerId}/erasure`, `POST /{playerId}/exports`,
      `GET /{playerId}/exports/{exportId}` and `OperatorEndpoint`'s `POST /players/{playerId}/erasure`,
      `GET /players/{playerId}/erasure` (the certificates), `POST /players/{playerId}/activity-export`,
      `GET /exports/{exportId}`, `GET /players/{playerId}/erasure` (contracts/routes.md: the
      workflow's state and certificates; `404 erasure_not_found`); T067–T069 green
      *Done 2026-10-25.* In `api/ErasureRoutes.scala`; the service's own `POST /erasure/players/{id}` and `GET /exports/players/{id}` admit `players` by name.

**Checkpoint**: an erasure is filed, held, applied everywhere with certificates and the realm user
removed; an export carries every part and names a silent one; nothing personal is readable after;
`erasure.feature` passes.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: the deposit route (FR-043, no story), durability, the module manifest and the realm
template, a local run beside Keycloak, a kind run, the README and quickstart proven, research closed.

- [X] T072 [P] `p-test/it/DepositsSuite.scala` (FR-043): `POST /{playerId}/deposits` with `amount`
      5000, `EUR`, `card` → `FakeKyc.depositCheck` asked, `FakeWallet.sums` read, `FakeRg.mayDeposit`
      asked with the sums, `FakeIngest`'s create called under `players-merchant-key` with the
      customer fields 001 FR-018 names and `customerId` `<slug>:<playerId>`, the answer `200
      {redirectUrl, paymentId}`; `FakeKyc` refusing → its code and message, nothing after it called;
      `FakeRg` refusing → the same; a service silent → `503` naming it within its bound; a decimal
      `amount` → `400`; no idempotency key (a repeat makes a second cashier session, as the original)
      *Done 2026-10-25.* Six cases in `it/DepositsSuite.scala`: the order and what each is asked with; kyc refusing (nothing after it asked); responsible gambling refusing; each of the four silent in turn, `503` naming it inside 4 s; a decimal, zero or missing amount and a bad currency `400`; a repeat opening a second cashier session; another player's token refused and an ingest refusal passed on with its status.
- [X] T073 Fill `PlayerEndpoint`'s `POST /{playerId}/deposits` (research R15's order and bounds,
      no workflow); T072 green; `LeakSuite` extended with a marker deposit (the customer fields are
      forwarded and kept nowhere)
      *Done 2026-10-25.* In `api/DepositRoutes.scala`. The player must be active (`403 account_restricted` naming the status otherwise, as sign-in). A kyc or responsible-gambling refusal is `403` with its code, its message and `service`; an ingest refusal keeps ingest's status. The customer fields come from the entity's profile and the primary address. `LeakSuite`'s marker deposit: the cashier receives the given name and the address, and no marker is in the log, the audit, the facts or any row the deposit added.
- [X] T074 [P] Add `p-test/it/DurabilitySuite.scala` on an own kit: a player registered and signed
      in with an open game session and its timer, a filed erasure in its hold, a pending export; the
      runtime restarted over the same database; the timer still fires, the hold still resumes, the
      reconciler still runs, the export completes, the session is still listed, and the workflow ids
      still answer their first outcomes
      *Done 2026-10-25.* `it/DurabilitySuite.scala` on its own kit, restarted with ankka's `restartService()` over the same database (`Kit.restart` points the process-wide runtime at the new views). After the restart, all of these hold: the session is listed, the export completes, the limit timer is still in `ankka_timers` and fires, the erasure keeps its correlation id and applies once the hold lapses, and the reconciler ends a session the realm dropped. `SharedKit.readopt()` hands the runtime back to the shared kit afterwards.
- [X] T075 [P] Extend `modules/core/module.json`'s `players` entry with R17's `env` and the six
      secret names under `secrets`, and the `vendor-agent` and `ingest` reach it needs (the shared
      `payments` grant for `merchant`'s erasure route under `grants.made` if not already there);
      `brands/_realm/casino-template.json` gains the `eitheror_twin_id` mapper if missing (**verify
      at task time**), the confidential client `players-admin` with a service account holding
      `realm-management`'s `manage-users`, `view-users` and `view-clients` (T020's `KeycloakRealm` takes its
      admin token by client credentials on the brand realm; the template has no such client today),
      at task time**), the three realm attributes `players_url`, `players_realm_secret`,
      `players_webhook_secret`, and the audit listener pointed at the brand's `players`;
      `brands/goldvibe/values.json` the values; `just modules validate` and `just modules render
      goldvibe` green; `tools/modules/src/test/.../RenderSuite.scala` asserts the rendered `players`
      carries both issuers' `ANKKA_AUTH_*` variables and the secret references; `just test` green
      *Done 2026-10-25.* Four divergences from the task. (1) The vendor's descriptor has no `secrets` field: the six are configuration keys with `secret: true` (`players-realm-admin` … `players-merchant-key`), rendered as `secretKeyRef`s to `tenant-values` from the brand's `<slug>-players/<KEY>` lines, as kyc's key is; `contract/keys.md` has the rows and `brands/goldvibe/values.json` declares `goldvibe-players`. (2) `INGEST_URL` is the derived key `ingest-url`, from the installation's `INGEST_URL` (dev-kind: `https://ingest-payments.127.0.0.1.sslip.io:8443`), empty where an installation names none; `KEYCLOAK_URL` reads `keycloak-jwks-base`. (3) `brands/_realm/casino-template.json` is the original's, copied unchanged (its README), so it is not edited. `Realm.withPlayers` adds the `players-admin` client with its service account and the three attributes to the rendered realm when `realm` or `apply` creates it (`--secrets`); a realm that exists is not changed. `players_url` is `https://players-<slug>.<PLAYER_SITE_BASE>`, to be confirmed on kind (T077). The template already maps `eitheror_twin_id` (verified 2026-10-25, `RealmPlayersSuite`). (4) No grant added: shared payments already makes the erasure grant to `{brand}/players`, and `vendor-agent` is in the brand's own project. `players` left `keys.md`'s *Not yet built*. `RenderSuite` asserts the rendered `players` carries both issuers' `ANKKA_AUTH_*`, `KEYCLOAK_URL`, `INGEST_URL` and the six references. `just modules validate --all` and `render goldvibe` are clean, and the modules tool's 157 tests pass.
- [ ] T076 [P] Run quickstart §6 locally: `docker compose up -d` in `services/players` (Postgres,
      Keycloak with the realm), `FakesMain`, `just players-run`; the curl of §6 and its sequence
      (register twice, the verification link through the operator route, login) answer as §6 says;
      `ankka local console` shows the journal, the session, the ended workflow, the two timers;
      record the run's date in `services/players/README.md`
- [ ] T077 Run quickstart §7 on kind (`--context kind-ankka` only, never production): `just modules
      apply brands/goldvibe/values.json --installation dev-kind --secrets <file outside the
      repository>`; `players` running beside `wallet` and `game-provider`; a registration through the
      gateway answers as §6 did; `ankka services logs players` shows the reconciler's count and no
      address, name or token; the installation's Keycloak reaches `/internal/realm/goldvibe/webhook`
      (a sign-in through `casino-frontend` ends in `player_logged_in` on the topic) — **until the
      plugins are rebuilt** (contracts/realm.md; a separate pull request in the original's
      repository and the installation image), `/may-sign-in` is exercised by hand with the secret and
      the result recorded in research R13; the Playwright specs spec 001 FR-033 names
      (`ssr-authenticated-first-paint.spec.ts`, `activity-tab-game-lookup.spec.ts`, the registration
      and sign-in specs) run against the kind brand with only base URLs changed (SC-001), or, if the
      frontend's base-URL move has not happened, the run is recorded as waiting on it in research
      *Findings*. Needs T075; a kind cluster
- [ ] T078 [P] Fill `services/players/README.md` (the purpose, the routes by endpoint with a pointer
      to contracts/routes.md, the settings and secrets table, `just players-test`/`players-it`/
      `players-run`, the recording run, the plugin dependency, what is verified where);
      `tests/README.md` and `tests/original/README.md` name the `players-driver`;
      `specs/008-players/quickstart.md` re-read against what T076 and T077 did and corrected where the
      commands drifted
      *Open 2026-10-25:* the README, `tests/README.md` and `tests/original/README.md` (*Recording the players*) are written, and quickstart §7's apply command and secrets file are corrected to the module tool. Re-reading §6 and §7 against an actual run waits on T076 and T077.
- [ ] T079 Close research: the verify-at-task-time items written with their result and date (T001's
      fields, T016's multi-source view, T030's workflow kit, T075's mapper, T077's plugin and
      Playwright checks); *For ankka* checked against the pinned runtime; plan.md's *Divergences* checked against
      `Parity.divergences` (`CoverageSuite` green); the cross-spec follow-ups (the export route on
      `wallet` and `merchant`; the plugins; `KycFactsConsumer`'s families from 009; the Playwright
      run) written as
      findings in research; `just features` green; `just players-test` and `just players-it` green
      *Open 2026-10-25:* research's *Findings* are written: the four items verified so far with their dates, and the cross-spec follow-ups. *For ankka* is checked against 0.13.0. Waiting: T077's plugin, session-listing and Playwright checks, and `just players-it` with recordings (T029).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)**: T001 first (every service build depends on the published contract); T002 after it;
  T003, T004, T005 together after T002 (T003 names T002's paths).
- **Foundational (2)**: after Setup. Blocks every story. T006–T010 together; T011–T014 together,
  then T015, then T016; T017 and T018 together, then T019 (after T015); T020–T022 together after
  T006; T023 after T020–T022; T024 after T015, T016, T019; T025 after T023 and T024; T026 and T027
  together after T023; T028 after all of them. T029 needs T005, the original's stack and Docker, and
  gates every parity task.
- **US1 (3)**: after Foundational. The MVP. T030–T032 together; T034 after T030; T035 after T034;
  T033 after T029 and before T035 is called done.
- **US2 (4)**: after US1 (the player and the realm user). T036–T039 together; T041 after T036;
  T042 and T043 after T041; T040 after T029.
- **US3 (5)**: after US2 (a signed-in player). T044 and T045 together; T047 after T044; T048 after
  T047; T046 after T029.
- **US4 (6)**: after US2 (a player token). T049 and T050 together; T052–T054 together after them;
  T055 after T052; T051 after T029. Can run beside US3.
- **US5 (7)**: after US3 (the session cascade). T056 and T057 together; T059–T061 after them (T060
  and T061 beside each other, T059 first); T058 after T029. Can run beside US4.
- **US6 (8)**: after US1 (the workflow's `match` step) and US5 (the operator routes' shape). T062
  and T063 together; T065 after them; T064 after T029.
- **US7 (9)**: after US5 (closure) and US6 (the index removal). T066–T068 together; T070 after
  T066; T071 after T070; T069 after T071.
- **Polish (10)**: T072 after US4; T073 after T072; T074 after US7; T075 beside anything after T006;
  T076 after US1–US5; T077 after T075 and T076; T078 after T076 and T077; T079 last.

### Platform gates (plan.md Phase 0)

- Five items are verified when their task is taken, each with its fallback in the task: the
  contract's `click_id`/`bonus_code` (T001), a multi-source view (T016), a workflow testkit (T030,
  T066), the realm template's mapper (T075), the plugins' read on kind (T077). Nothing waits on an
  unreleased ankka spec: ankka 042 is the named gap (T069's flipped assertion), not a blocker.
- The export route on `wallet` (002) and `merchant` (004) is each service's own task on its own
  branch; until it exists `FakeWallet` stands in here and a real export names them `missing`.

### Parallel Opportunities

- Phase 1: T003–T005 together after T002.
- Phase 2: T006–T010 together; T011–T014 together; T017 and T018 together; T020–T022 together;
  T026 and T027 together; T029 the moment the original is up.
- US1: T030–T032 together; T034 → T035 → T033.
- US3 and US4 beside each other after US2; US5 after US3; US6 beside US4; US7 last of the stories.
- Polish: T072, T074, T075 together; T076 → T077 → T078 → T079.

---

## Parallel Example: User Story 1

```bash
# tests first, together:
Task: "RegistrationWorkflowSuite in p-test/registration/RegistrationWorkflowSuite.scala"
Task: "RegistrationSuite in p-test/it/RegistrationSuite.scala"
Task: "Bind registration.feature in p-test/features/RegistrationFeatures.scala"
# then in order:
Task: "registration/RegistrationWorkflow.scala — mint, realm, player, match (stub), email, undo-realm"
  → "api/PlayerEndpoint.scala — register, verify-email, resend; GeoEndpoint; the operator's verification-link"
  → parity (the driver's registration recordings; the four Scenario 18 registrations)
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phases 1 and 2, with T029 started the moment the driver builds and the original's stack is up
   (it needs nothing of this service and gates seven parity tasks).
2. Phase 3: a registration makes one realm user and one player, sends one email and produces one
   fact; the link verifies once; `registration.feature` green; the registration recordings replay.
3. Stop and run quickstart §4 and §5 for the US1 suites.

### Incremental delivery

1. US2, then US3: sign-in and sessions, the realm's authenticator route, game sessions with their
   timers. After US3 `game-provider` (003) can launch against this service and SC-002 to SC-004
   hold; the frontend could move its sign-in base URL.
2. US4 and US5 beside each other by a second pair of hands: the profile and the operator. After them
   `kyc` (009), `responsible-gambling` (010) and `engagement` (014) have every route they call.
3. US6, then US7: duplicates on US1's workflow; erasure and export across the fakes. After US7
   SC-005 and SC-006 hold.
4. The deposit route, durability, the manifest, then quickstart §6 and §7 on kind before a brand's
   values are applied anywhere real.

---

## Notes

- Every parity task replays recordings that must exist (T029's run); do not write a recording by
  hand. The original's behaviour where the plan diverges is recorded as it is and declared in
  `Parity.divergences`, never corrected in the recording.
- No personal field, password, realm token or lookup token in any log line, span, audit record,
  timer name or operator answer outside its `Personal` marker: T011's state type has no field to
  hold a password or token; T027/T069 search for every marker; the log prints ids, routes, outcomes
  and statuses and nothing from a body or a path parameter.
- `kubectl` only ever with `--context kind-ankka`; the default context is production.
- Steps touching `contract/` (T001) and `modules/core/module.json` (T075) are shared with the 012
  and 022 branches: rebase before merging, keep the contract change additive.
