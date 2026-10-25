# Research: players

**Spec**: [spec.md](spec.md) | **Date**: 2026-10-25

What was read: the spec after its 2026-10-25 clarifications; the constitution; spec 001's contracts
(`routes.md`, `topics.md`, `grants.md`, `recording.md`); the contract library as built
(`contract.facts.CasinoPlayers`, `contract.facts.Others` for `casino.sessions`,
`contract.access.Routes`, `contract.personal`, `contract.port.Replay`, `Layout`); the wallet's and
merchant's plans and code as the pattern for a brand service; ankka 0.13.0's docs (`timers.md`,
`workflows.md` "Waiting for the end", `topics.md` start positions, `component-client.md`,
`limitations.md`) and spec 042's status; the original's `casino-service` (`PlayerRoutes.scala`,
`PlayerProfileRoutes.scala`, `BackofficePlayerRoutes.scala`, `SessionRoutes.scala`,
`PlayerEmailRoutes.scala`, `PlayerEmailChangeRoutes.scala`, `KeycloakWebhookRoutes.scala`,
`KeycloakSessionReconciler.scala`, `MultiAccountDetector.scala`, `EmailVerificationTokenStore.scala`,
`SubstrateTwinClient.scala`), its JSON schemas (`shared/json-schemas/`), the Go capability
`close_child_sessions`, the Keycloak plugins (`bundle/keycloak-plugins/`), and the Go end-to-end
scenarios the spec names. Each decision names what established it.

## R1. The recordings come from a driver; the named Go scenarios do not reach the player routes

**Decision**: `tests/original/players-driver/` (Go, build tag `e2e`, the shape of `ingest-driver/`)
drives the original's `casino-service` player, session, email, backoffice and webhook routes on its
KinD stack, one scenario per scenario of `features/players/*.feature` the original can show, named
exactly as the feature names it, and writes `tests/port/players/<scenario>.json`. The Go suite's
scenarios are recorded only where they reach a player route: `TestScenario18_MultiAccountDetection_*`
(two registrations each; the rest of each test reads twins and fraud cases from the substrate, which
are `observe:` steps).

**Rationale**: the spec's Recordings section was written from the suite's names, and reading the
tests shows what they call. `TestScenario7_CasinoGate3_*` (`scenario7_casino_player_journey_test.go`)
never calls a player route: it drives `POST /internal/actions/execute` on the substrate, and all four
fail or skip on the original (`tests/port/README.md`, "Not recorded"). `TestScenario9_CasinoRF_SessionDurationLimitCheck`
publishes Kafka events and waits for one that the TL function never emits; it is skipped. `TestScenario12`
and `TestScenario14` are merchant's and ingest's recordings already; their registration and sign-in
steps are the suite's set-up, not a requirement of this service beyond FR-001 and FR-010, which the
driver records directly. The Playwright specs are spec 001 FR-033's check against the rebuilt brand,
not recordings.

**What the original cannot show** is marked `inspected` or `new` in `contracts/surface.md` and gets no
recording: one session at a time, a player's own closure and the reopening, the export's fan-out,
the migrated KYC deadline (spec 009's), the realm's authenticator asking `players` (the plugin reads
the twin today), the session timer ending a session (the TL function is an empty rule), a sign-in
refused by `may-log-in` (the original's authenticator refuses inside Keycloak, before the route).

**Alternatives**: recording through the Playwright suite (rejected: the recorder is the Go suite's
transport, spec 001 FR-030); patching the suite's scenario 7 to use the routes (rejected: that is
writing new tests of the original, and the driver does the same with fewer moving parts).

## R2. One event sourced entity per player; sessions, messages, notes and cases are entities of their own

**Decision**: `PlayerEntity`, event sourced, id = the minted player id (UUID). Its state holds the
status and the account trail (FR-031), the profile with every personal field as `Personal` (FR-023),
the email addresses (FR-026), consents and cookie decisions (FR-028), favourites (FR-029), VIP tier
and PotVIP (FR-033), the fraud flag and linked ids (FR-036), the session limit and the game sessions
started this UTC day (FR-019), the open player sessions (FR-012, R5), the pending bonus code, the
times of registration, last sign-in and last refused sign-in, and the verification-email send time
(the resend rate). `PlayerSessionEntity` and `GameSessionEntity` (FR-016, FR-017), `MessageEntity`
(FR-030), `NoteEntity` (FR-034) and `FraudCaseEntity` (FR-037) are event sourced entities keyed by
their own ids: an inbox and a note trail are unbounded, and a session has a life of its own.

**Rationale**: every refusal the spec names on the player (status at sign-in and launch, a locked
field, a country off the allow-list, a resend too soon, a second flag) is a check followed by a
change on one player, which an entity makes atomic (`ankka-design` step 1). The account trail and
the facts of FR-035 are the player's history, so the entity is event sourced. The original's
`account_status` is a string property with no transitions; here the transition is the event, and
the fact is folded from it (R7).

**Alternatives**: a key value player (rejected: the trail and the facts need the events); profile
and status as two entities (rejected: a sign-in reads both, and the lock-once rule and the status
gate are one player's invariants); sessions inside the player (rejected: a session is reached by its
own id from the webhook, the timer and the provider, and a player's sessions over 90 days are many).

**Bounded state**: the trail is one event per operator or player action; today's game sessions are
pruned by UTC day in the fold (the event carries the time; the fold compares to the event's day,
never the clock); open player sessions are a set bounded by the realm's own session limit.

## R3. Registration is an awaiting workflow, keyed by the address, with the realm user as the first step

**Decision**: `RegistrationWorkflow`, id = `sha256(<slug>:<lower-cased address>)`, started by the
public route and awaited with 0.13's `thenAwaitEnd` (bound 5 s, else `503 service_unavailable`).
Steps: `mint` (the player id, kept in the workflow's state so a retry reuses it); `realm` (create the
user with the id as `eitheror_twin_id`; `email_already_exists`, `password_too_weak` and
`brand_not_found` end the workflow refused, as the realm reports them, FR-002); `player` (the
`Register` command on `PlayerEntity`); `match` (R9, duplicate detection); `email` (the verification
send through `engagement`, R16). A `player` step that fails after retries runs `undo-realm` (remove
the user) and ends `service_unavailable`. The route answers `201 {playerId, status}` from the
workflow's state. A second registration with the same address while the first runs joins it
(`start` is the atomic claim); after it ends, the realm answers `email_already_exists`.

**Rationale**: FR-002's last clause (no player without its realm user, no realm user without its
player) is two writes to two systems; a workflow is the one component that survives a crash between
them and runs the compensation (`ankka-workflows`). ankka 0.13 can await a workflow's end, so the
route no longer polls as the wallet's deposit route did on 0.11 (spec 002 R17).

**Alternatives**: the endpoint calling the realm then the entity (rejected: a crash between them
leaves the orphan the spec forbids, which is what the original does today); the player first and the
realm second (rejected: the realm is the authority on the address and the password policy, so its
refusal must come before anything is written).

## R4. Sign-in: the realm's direct grant, the status from the entity, `may-log-in` once, fail closed

**Decision**: the public sign-in route calls the realm's token endpoint (direct grant, client
`casino-frontend`), verifies the token with the same verifier the player routes use, reads the player
id and realm session id, then calls `PlayerEntity.SignIn(details)`: refused `account_restricted`
naming a status of `suspended`, `self_excluded`, `blocked` or `closed` (local, cannot fail), and
refused `email_not_verified` for `pending_email_verification`. Only then the route asks
`responsible-gambling`'s `may-log-in` within the project, bound 500 ms, refused `cooling_off` naming
the end or `account_restricted` when there is no answer; a refusal is recorded on the player as the
refused attempt (FR-011). Then `PlayerSessionEntity.Open`, `PlayerEntity.SessionOpened` (which stamps
the last sign-in and answers the player's other open sessions), and, when `brand-config` sets
`singleSession`, `SessionEnd` (R6) for each other session with reason `superseded` before the
answer (FR-012). The answer keeps the original's fields (`token`, `refreshToken`, `brandId`,
`playerId`, `currency`, `country`, `pendingBonusCode`).

**Rationale**: FR-011's order; SC-007 (the realm and `may-log-in` are the only calls); the original's
`login` does the same in `PlayerRoutes.scala:601-729` except that it reads the twin and never asks
RG (the realm's authenticator did, inside Keycloak).

**The realm's authenticator** (FR-013): `POST /internal/realm/<slug>/may-sign-in` on a public endpoint
served only when `X-Realm-Secret` verifies; it answers the entity's status and `may-log-in` the same
way, bound 80 ms on the RG call so the whole answer is under 100 ms at p99 (SC-004); no answer is a
refusal, and the plugin fails closed on a non-200 (today it fails closed on a non-200 from the
substrate, `RgStatusAuthenticator.java:35-128`, so only its URL and header change). The GAMSTOP
authenticator's refusal is reported on `POST /internal/realm/<slug>/gamstop-refusal` and passed to
RG (spec 010 FR-022). The plugin changes are in the original's `bundle/keycloak-plugins/` and the
installation's Keycloak image: a task of this spec that lives in two other places (plan, "What the
next command needs to know").

## R5. Sessions: two entities, a timed action per game session, a recurring timer for the realm

**Decision**: `PlayerSessionEntity` (id = session id, holds the realm session id, the personal
network address and user agent, the device fingerprint, status, end, and its game session ids) and
`GameSessionEntity` (id = session id, holds player, parent, game, currency, demo, start, end, minutes,
reason). Game session open is a service route for `game-provider` (FR-017): `GameSessionEntity.Open`,
then `PlayerEntity.GameSessionOpened(id, at)`, which refuses when the status bars play, when the
parent session is not open, or when today's minutes already meet the limit, and otherwise answers
the minutes left; the route then schedules a single timer `game-session-limit/<id>` on the
`SessionLimits` timed action for that many minutes (FR-019; no timer when there is no limit). The
timer's handler ends the game session with reason `limit`. An `rg_limit_applied` fact for
`SESSION_MINUTES` (a consumer over `casino.players`, `StartFrom.Latest`) commands
`PlayerEntity.SetSessionLimit`, whose answer lists the open game sessions and their new remainders;
the consumer reschedules each timer by name, and ends at once any already over. The realm's live
sessions are asked on a recurring timer `realm-reconcile` every 60 s (the original's cadence,
`KeycloakSessionReconciler.scala:70-71`) on the `RealmReconcile` timed action: every open player
session whose realm session is not live ends `expired`, and the gauge is set.

**Rationale**: FR-019 says no call at open and exact minutes; the player entity holds today's
sessions, so the arithmetic is the fold's, not a view's. A timer per session replaces the hourly scan
(spec Changes). Rescheduling by name is ankka's replace-by-name rule (`timers.md`).

**Alternatives**: a workflow per game session pausing until the limit (rejected: a workflow per
launch, thousands a day, for one deadline; a timer is the primitive for that); the minutes from a
view (rejected: lags, and FR-019 forbids a call).

## R6. Ending a player session is a short workflow, so the cascade survives a crash

**Decision**: `SessionEndWorkflow`, id = the player session id, started by sign-out, the webhook
(`LOGOUT`, `SESSION_EXPIRED`), the reconciler, suspension, exclusion, closure and `superseded`, with
the reason. Steps: `games` (end every game session the player session lists, same reason, in id
order; each end is idempotent), `session` (`PlayerSessionEntity.End`, whose event is the source of
`player_session_ended` and `player_logged_out`), `player` (`PlayerEntity.SessionEnded`). A second
start under the same id with any reason joins the first. Sign-out awaits the end (bound 5 s); the
others do not. Ending a game session by itself (the player's end, the timer, an operator's force-end
of one, `support-agent`'s `close_active_session`) is a direct command with no workflow.

**Rationale**: FR-020 fixes the order (games first, then the fact); the original's
`close_child_sessions` capability does the same from a TL trigger and swallows failures; SC-003 wants
every game session ended within 5 s of the player session for every reason, which a durable process
with retries gives and a fire-and-forget call does not.

## R7. Facts fold from events in consumers; the two session families fill the gap the original has

**Decision**: `PlayersPublisher` (events of `PlayerEntity`, `MessageEntity` and `FraudCaseEntity` →
`casino.players`), `SessionsPublisher` (events of the two session entities → `casino.sessions`) and
`AuditPublisher` (every operator action → `platform.audit`), each a consumer emitting the contract's
families with `ce-id` = the event's id, keyed by the player id (`topics.md`). Field names are the
original's schemas': `previous_value`, `new_value`, `actor`, `actor_id`, `reason` on the status
families; `registered_at`, `btag`, `country`, `currency`, `vip_tier` on `player_registered`;
`session_id`, `close_reason`; `session_id`, `game_id`, `duration_minutes`. `player_session_started`
and `player_session_ended` have no schema in the original (the start is a twin property, the end is
a property write with no event); the contract already types them (`facts/Others.scala`), with the
network address and user agent as `Personal`, and this service is their first producer.
`player_reopened` (FR-032a) is a new family, added to the contract beside `player_closed` with the
same five fields.

**Rationale**: constitution IV; the original's `export_account_event.tl` and `kafka_publish`
handler map exactly these names (`vip_tier_changed` is `previous_value`/`new_value` in TL and
renamed by the Go handler, `kafka_publish/handler.go:316-319`; the contract keeps the published
names).

## R8. Personal data without ankka 042: the marker, an `Erased` event, redacted rows, and a named gap

**Decision**: every field FR-023 names is `Personal[String]` in state, events and view rows, as the
wallet and merchant do. The erasure handler of this service is `PlayerEntity.Erase`: its event blanks
every personal field in the fold (the values are gone from state and from every later snapshot), the
views project the event as a redacted row, the two lookup tokens are removed from the index (R9),
the player session rows keep no address or agent. The journal's earlier events still hold the values
until ankka 042's crypto-shredding exists, and so do the facts already on `casino.players` and
`casino.sessions` (`player_profile_updated`'s eight `Personal` fields, which spec 009 reads;
`player_session_started`'s address and agent), whose topics the core module keeps for ever; that is
the gap SC-006 names "within ankka 042's bound",
and the plan reports it as "platform lacks 042" exactly as the modules tool reports `erasure` as not
stated. Nothing here is a workaround for 042: when it ships, `Personal` becomes its type and the
journal is covered.

**Rationale**: constitution V and scope ("features the casino needs and ankka lacks are specs in
ankka, not workarounds here"); ankka 0.13.0 `limitations.md:38-40` says erasure is not provided;
spec 042 has a spec and a checklist and no plan at v0.13.0. The wallet took the same stance (spec 002
R12, "`Personal` is a marker until 042").

## R9. Duplicate detection: a keyed index per token, matched at registration, never a scan

**Decision**: a lookup token is `HMAC-SHA256(<project lookup key>, <normalised value>)` rendered hex,
where the identity value is `trim.toLowerCase` of given name, family name and date of birth joined by
`|` (the original's `identity_hash` input, `SubstrateTwinClient.scala:373-378`, minus its UUID
wrapping) and the device value is the fingerprint as sent. `LookupIndexEntity`, key value, id = the
token, holds the player ids that carry it. The registration workflow's `match` step reads the two
index entities, adds the new player, and when either already held players: flags every one and the
new player as suspected duplicates linked to each other (`PlayerEntity.FlagDuplicate`, idempotent
under the case id), opens one `FraudCaseEntity` naming the signals (`identity`, `device`) and the
players, and the publisher emits `player_fraud_flagged`. The step does nothing when `brand-config`'s
`multiAccountDetection` is off (the original's kill switch flag) or no signal is present (FR-036).
Erasure removes the player's two tokens from their index entities (R8). The lookup key is a service
secret (`players-lookup-key`, ankka 038) and is never on a fact or in a row.

**Rationale**: SC-005 (no scan; within 10 s); the original scans every twin of the brand for both
properties (`MultiAccountDetector.scala:52-65`). ankka 042's lookup tokens (its FR-012) are the same
shape, keyed under a project key and removed on erasure; this index is replaced by them when 042
ships, with the same matching rule.

**Alternative**: a view over `player_registered` with a declared query by token (rejected: lags, and
0.13 views have no index on a payload field, so the query scans — spec 006 finding 6).

## R10. Erasure and the export are workflows fanning out to the services' own routes

**Decision**: `ErasureWorkflow`, id = `erasure/<playerId>`, started by the player's or an operator's
request (refused `not_closed` unless the status is `closed`, FR-038): step `file` sets the status
`erasure_pending` with the hold's date (the brand's retention period from closure, `brand-config`'s
`retentionDays`, default the licence profile's; an open withdrawal is a hold too, read from
`casino.withdrawals` facts into the player's state); step `hold` pauses on a single timer named by
the request; step `apply` calls `POST /erasure/players/{playerId}` on every brand service the module
manifest lists that serves it (today: `wallet`, and `merchant` in `payments` under the brand's
erasure grant, `grants.md`; `kyc`, `responsible-gambling`, `rewards`, `engagement`, `payouts` and
`attribution` as their specs build the route) and `PlayerEntity.Erase` on itself, retrying each
until it answers, and keeps each answer as that service's certificate; step `realm` removes the user;
step `done` sets `erased`. `ExportWorkflow`, id = `export/<playerId>/<requestId>`: step `own`
assembles the profile, consents, sessions, activity and inbox from this service's state and views;
step `others` calls `GET /exports/players/{playerId}` on the same services for their part (the
wallet's transactions, the payments, the document records without the images), bound 10 s each, and
names a service that did not answer as `missing` in the response, never omitting it (FR-040); the
response is kept in the workflow's state for 24 hours and read by the download route (the original's
`exports/{id}` shape, `PlayerProfileRoutes.scala:1507-1690`).

**Rationale**: the erasure's hold, fan-out and completion span days and services; a workflow holds
its state and retries (`ankka-workflows`); the services already serve the erasure route to `players`
(`wallet/api/ServiceEndpoint.scala:212-221`, `merchant/api/ErasureEndpoint.scala`). The export route
is new on each service: `contracts/routes.md` names it as a route every brand service serves, and
specs 002 and 004 gain it as a task (plan, "What the next command needs to know"). Until ankka 042
ships, the certificates are the routes' answers, and the workflow is the brand's filer (001 FR-025).

## R11. Reads: views for the operator, the inbox, the sessions and the activity; the player reads the entity

**Decision**: `PlayersView` (one row per player from `PlayerEntity`'s state: status, tier, flags,
the display name and the primary address as `Personal`, registration and last sign-in; the operator's
list and search, FR-034); `SessionsView` (rows from both session entities, by player, for the
player's reads, the operator's views, the reconciler's open set and the rescheduling of R5);
`MessagesView` (rows from `MessageEntity`, by player, with the unread count computed by the route from
the rows, FR-030); `NotesView`; `FraudCasesView`; `WalletSummaryView` (rows from `money.transactions`
facts: real and restricted balance after the last movement per player and currency, FR-034);
`RecentlyPlayedView` (rows from `casino.rounds`, FR-029); `ActivityView` (rows from the brand's
topics in the player-visible categories: `money.transactions`, `casino.rounds`, `casino.rewards`,
`casino.players` for the account families, `casino.sessions`; one view per topic source if 0.13 takes
one source per view, merged by time in the route; the task checks `views.md`). Topic sources declare
`StartFrom.Earliest` so a redeploy rebuilds. A player reads their own profile, consents, favourites
and emails from `PlayerEntity` queries (read your own write); the operator's profile read is the
same query.

**Rationale**: the operator's list and search are questions over many players; the inbox is many
messages; the activity is other services' facts (constitution IV: a reader builds its own view).
Views are never the input to a refusal: every refusal is the entity's.

## R12. Configuration: three key value entities, served in-project

**Decision**: `BrandConfigEntity` (id `brand`: names, addresses, `playerSiteBase`, `reportingZone`
(IANA, default `UTC`), `singleSession` (default false), `multiAccountDetection` (default true),
`retentionDays`, feature switches), `CurrencyConfigEntity` (the offered currencies and their
settings) and `AllowListEntity` (the countries; empty means every country). Operators with the
brand's role edit them; the brand's other services read them on `GET /v1/casino/brands/<slug>/players/config/{name}`
(service class). The original's `GET/PUT /{brandId}/brand-config` keeps its shape on the operator
endpoint.

**Rationale**: FR-041; the latest value is all anyone reads (as spec 002 R7 for the wallet's brand
config); audit of a change is `platform.audit`'s.

## R13. The realm client and the plugins

**Decision**: `Realm` (the client), after merchant's `operators/KeycloakAdmin.scala`: the
administrative credential from the secret store (`players-realm-admin`), used only for FR-007's
list (create, disable, enable, remove a user; set the address verified or change it; revoke a
session; list live sessions of client `casino-frontend`; send a reset link; verify a password by a
direct grant). Direct sign-in and refresh use the realm's token endpoint with client
`casino-frontend` and no credential. The webhook stays as the original's audit listener sends it
(`POST /internal/keycloak-webhook`, header `X-Webhook-Secret`, body `type`, `realmId`, `sessionId`,
`userId`, `timestamp`; `LOGOUT` is also synthesised from an admin `USER_SESSION DELETE`; the listener
never sends `SESSION_EXPIRED` on Keycloak 24, so the reconciler is what ends idle sessions). The two
authenticators change only their read: from `GET {substrate}/twins/casino-{brand}/{derived id}` to
`POST {players}/internal/realm/{brand}/may-sign-in` with `X-Realm-Secret`, keeping their fail-closed
behaviour; the GAMSTOP authenticator gains the report call.

**Rationale**: FR-007, FR-008, FR-013; the plugin sources are read
(`RgStatusAuthenticator.java:35-128`, `GamstopAuthenticator.java:21-82`,
`EitherOrAuditListener.java:69-147`). The plugins live in the original's repository and the
installation's Keycloak image (`installation/base/keycloak/`); this plan's tasks name the change
there and the image rebuild, and the kind quickstart proves it.

## R14. Route grouping under 0.13's limits, and the original's paths

**Decision**: six endpoints, each one prefix with the brand as a literal in it (as the wallet does,
spec 002 R16), so no route takes more than two parameters and no two endpoints share a prefix:

| Endpoint | Prefix | Class |
|---|---|---|
| `PlayerEndpoint` | `/v1/casino/players/<slug>` | `player(slug)`; `withAcl(public)` on register, login, refresh, verify-email, resend, password-reset, password-change/verify, emails/verify, email-change/verify, kyc/requirements (moved to 009; see surface) |
| `SessionEndpoint` | `/v1/casino/sessions/<slug>` | `player(slug)` (reads, end; `launch` is spec 003's and not served here) |
| `OperatorEndpoint` | `/v1/casino/backoffice/<slug>` | `operator(slug)`; carries the modules page rows of spec 021 (`contracts/routes.md`, kept) |
| `ServiceEndpoint` | `/v1/casino/brands/<slug>/players` | `service`: game session open and the status at launch (003), the suspend command (009), the status commands (010), the message route (014), `support-agent`'s reads and end (017), the configuration reads (R12), the erasure and export of this service's own part |
| `RealmEndpoint` | `/internal/realm/<slug>` | `public`, each route verifying `X-Realm-Secret` or `X-Webhook-Secret` |
| `GeoEndpoint` | `/v1/casino/geo` | `public` |

The original's operator block at `/v1/casino/{brandId}/players/{playerId}/...` (no `backoffice`
segment, `BackofficePlayerRoutes.scala:1457`) is served at `/v1/casino/backoffice/<slug>/players/{playerId}/...`
as spec 001's `routes.md` fixes; the parity translation (`contracts/wire.md`) maps the paths. The
original's three-parameter routes are two here because the brand is in the prefix.

**Rationale**: `CLAUDE.md` ("a route takes at most two path parameters and no two endpoints may
share a prefix"); spec 001's addresses.

## R15. Starting a deposit (FR-043): the endpoint, in order, with bounds; no workflow

**Decision**: the player's deposit-initiation route asks `kyc`'s deposit check, reads the wallet's
sums, asks `responsible-gambling`'s `may-deposit`, then opens the cashier session on `ingest`'s public
route under the brand's merchant key (secret `players-merchant-key`) with the customer fields spec
001 FR-018 names, read from the player's profile, and answers the cashier's redirect. Each call is
bound (500 ms for the two checks, 2 s for the cashier) and a refusal or silence is a refusal naming
the service. No idempotency key: a retry opens another cashier session, as the original does, and
the wallet's check at credit is the authority.

**Rationale**: the route is a pre-check that spares a refused payment; nothing it does moves money
or must survive a crash. SC-007 names the routes that call nothing and this is not one.

## R16. The verification email, and running without `engagement`

**Decision**: the verification token is the original's: stateless, HMAC-SHA256 over
`{b, p, e, x, t}` (`EmailVerificationTokenStore.scala:42-100`), valid 24 hours, replayable until it
expires (the spec keeps this on purpose), signed with the secret `players-email-token`. The send is
the registration workflow's last step and the resend route's call, to `engagement`'s send route
within the project, bound 2 s, retried by the workflow for a minute and then recorded on the player
as `verificationEmailPending`, so a failed send never refuses a registration. Resend is refused
`rate_limited` with `retry_after_seconds: 300` within 5 minutes of the last send, from the entity's
state (the original keeps it in memory). Until `engagement` exists (spec 014), and on a brand whose
`CASINO_REAL_MONEY` is false, the operator's player view carries the current verification link, so
the quickstart and the Playwright suite can verify an address; on a real-money brand it never does.

**Rationale**: FR-004, FR-005; the resend's 5 minutes and the token's 24 hours are the original's.

## R17. Settings and secrets

`CASINO_BRAND` (the slug, `Routes.brandFromEnvironment`), `CASINO_REAL_MONEY`, `KEYCLOAK_URL`,
`PLAYER_SITE_BASE`, `REALM_RECONCILE_INTERVAL` (60s), `VERIFICATION_VALIDITY` (24h),
`VERIFICATION_RESEND_INTERVAL` (5m), `MAY_LOG_IN_BOUND` (500ms), `REALM_ANSWER_BOUND` (80ms),
`SESSION_END_BOUND` (5s), `EXPORT_PART_BOUND` (10s), `EXPORT_RETENTION` (24h), `INGEST_URL`. Secrets
(ankka 038, named in the core module): `players-realm-admin`, `players-webhook-secret`,
`players-realm-secret`, `players-email-token`, `players-lookup-key`, `players-merchant-key`. Read by a
`Settings.fromEnvironment` that refuses naming the variable, as merchant's.

## R18. Divergences from the original (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| The player id is minted; a registration never derives it from brand and address | Clarifications; an erased player registering again must be new | `registration.feature`, `erasure.feature`; the recordings' `{player}` placeholder |
| The operator routes are at `/v1/casino/backoffice/<slug>/players/...`; the original's are at `/v1/casino/<brand>/players/...` | spec 001 `routes.md` | every backoffice recording, through `WireTranslation` |
| A refusal carries `message` beside `error`; a missing token is `401 unauthenticated`, not Akka's rejection page | the rebuild says why; `contract.access` | `WireTranslation` keeps the original's fields only; `sessions.feature` |
| A registration whose player cannot be written removes the realm user and answers `503` | FR-002 | the registration workflow's test with the entity refusing; no recording |
| Sign-in asks `may-log-in` and fails closed; the realm's authenticator asks `players` | FR-011, FR-013 | `sessions.feature`; `FakeResponsibleGambling` silent; the plugin on kind |
| Idle sessions end by the reconciler's timer; the webhook's `SESSION_EXPIRED` is accepted and never sent | FR-008, FR-009; Keycloak 24 | `sessions.feature` "gone idle" |
| The session timer ends a game session with reason `limit`; the original's rule is empty | FR-019 | `sessions.feature`; no recording (inspected) |
| Duplicates are matched by token, not by a scan | FR-036 | `duplicates.feature`; Scenario 18's recordings pass either way |
| The export carries every service's part | FR-040 | `erasure.feature`; no recording (new) |
| A player may close their own account; an operator may reopen it | FR-032a | `status.feature`; no recording (new) |
| `singleSession` ends the older session | FR-012 | `sessions.feature`; no recording (new) |
| A verification email that cannot be sent does not refuse the registration | R16 | `registration.feature`; the original answers 500 |
| The player-facing KYC routes, `launch`, the RG limit routes and the wallet adjust route of the original's player and backoffice files are other services' | spec Context; 003, 009, 010, 002 | `contracts/surface.md` dispositions |

## For ankka (checked against 0.13.0)

- 042 (personal data erasure, lookup tokens): spec only; R8 and R9 stand in and are replaced by it.
- A view with several topic sources (R11): to be checked in `views.md` at task time; one view per
  source otherwise.
- No per-call service-client timeout (spec 006 R6): every bound here is a race against the call, as
  `ingest` does.
