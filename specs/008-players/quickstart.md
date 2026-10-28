# Quickstart: proving players

In build order. Every command runs from the repository root unless it says otherwise; the ones that
need Docker say so. Expected outcomes are what [plan.md](plan.md)'s *Divergences* and the contracts
say; nothing here repeats them.

## 1. The features and the glossary

```bash
just features
```

Expected: 0 findings. The six `features/players/*.feature` files hold 63 scenarios and the spec's
acceptance lists name each one.

## 2. The contract

```bash
just schemas
sbt contract/test contract/publishLocal
```

Expected: `casino.players.v1` carries `player_reopened`; `casino.sessions.v1` admits `superseded` as a
close reason; `Routes.playersOnly("goldvibe")` admits `players` of the brand and refuses `wallet`,
a player and a staff token (`RoutesSuite`).

## 3. The recordings (needs the original's KinD stack; [research R1](research.md))

```bash
cd tests/original/players-driver && go test -tags e2e -casino-url http://localhost:8085 -keycloak-url http://localhost:8080 ./...
cd ../../.. && ./tests/original/merge tests/port/players
just modules recordings check
```

Expected: one recording per driver scenario, every one `pass` on the original, named as the feature
names it; the four Scenario 18 registrations alongside; addresses, passwords and tokens redacted to
placeholders. The recording run is a task of its own and the parity suite waits on it.

## 4. Unit suites

```bash
just players-test   # sbt test without the it and port packages
```

Expected: `ProfileSuite` (every lock-once, required and clearable field), `TokensSuite` (the
verification token round-trips and expires at 24 h; two normalisations of one identity give one
lookup token), `MergeTagsSuite`, `WireTranslationSuite` (every row of `contracts/wire.md`),
`SettingsSuite` (each variable refused by name); the entity kits for `PlayerEntity` (every row of
data-model.md's command table; `Erase` leaves no `Personal` field), the two sessions, `MessageEntity`,
`NoteEntity`, `FraudCaseEntity`, `LookupIndexEntity`, the three configurations; the workflow kits for
the four workflows over fakes (the registration's `undo-realm` runs when the player write fails; the
erasure waits its hold and collects every certificate; the export names a silent service under
`missing`); the consumer kits (`PublishersSuite`: every event to its family, `ce-id` the event's;
`RgLimitsConsumerSuite`: a new limit reschedules by name).

## 5. Integration, features and parity (Docker)

```bash
just players-it     # AnkkaTestKit over Postgres with FakeRealm, FakeRg, FakeKyc, FakeEngagement, FakeIngest, FakeWallet, InMemoryBroker, a settable clock
```

Expected, by suite:

- `RegistrationSuite` (FR-001 to FR-004, SC-007): a registration makes one realm user and one player
  and sends one email; a repeat of the address returns the first player; a failed player write deletes
  the realm user; the fakes record no other call.
- `SessionsSuite` (FR-010 to FR-022, SC-003): sign-in, refresh, sign-out end to end; a restricted
  player refused before the realm is asked; an idle realm session ends `expired` within two reconcile
  intervals; a game session's timer fires at the minutes left and a new limit moves it; every game
  session ended within 5 s of the player session for logout, expiry, suspension and closure; a brand
  with one session at a time supersedes the older.
- `RealmSuite` (FR-013, SC-002, SC-004): `/may-sign-in` refuses a suspended player within 2 s of the
  command; 1,000 asks at the kit's rate under 100 ms at p99 with `FakeRg` answering in 20 ms;
  `FakeRg` silent → `allowed: false`; a wrong secret → `401`.
- `ProfileSuite` (FR-023 to FR-029): the locks, the country allow-list, the second address, consents
  with time and source, cookie decisions, favourites.
- `DuplicatesSuite` (FR-036, FR-037, SC-005): 10,000 players registered; a second registration with one
  identity is flagged and its case open within 10 s with two index reads (`LookupIndexEntity` call
  count); a device match the same; the kill switch off → no case.
- `OperatorSuite` (FR-031 to FR-034): suspend, reinstate, close, reopen within the hold, reopen after
  it refused, tier with the previous on the fact, notes, messages with merge tags, fraud review, the
  configurations; every action an audit record without a `Personal` value; a token without the brand's
  role `403`; spec 021's two modules rows pass through `FakeAgent`.
- `DepositsSuite` (FR-043): the four calls in order; a `kyc` refusal named; `FakeRg` refusing named;
  `FakeIngest`'s `redirectUrl` returned; a silent service `503` naming it within its bound.
- `ErasureSuite` (FR-038 to FR-040): an erasure refused while open; filed, held, applied to every fake
  with a certificate, the realm user removed; an open withdrawal holds it; the export carries every
  part and names a silent service under `missing`; a read after 24 h is `410`.
- `LeakSuite` (SC-006): every personal field, a known address and a known device fingerprint sent;
  after `Erase` no table, fact or captured log holds any of them; the balance summary, statuses and
  session counts read unchanged.
- `GherkinSuite` over the six features: every scenario bound and green.
- `ParitySuite` over `tests/port/players/`: every recording replayed through `WireTranslation`, the
  marked divergences named.

## 6. Locally, beside a Keycloak

In `services/players`: `sbt schema && docker compose up -d` (Postgres on 5435, an empty Keycloak 24 on
8080). Then the brand realm, made by the modules tool as on an installation, from the repository root:

```bash
CASINO_KEYCLOAK_URL=http://127.0.0.1:8080 CASINO_KEYCLOAK_ADMIN_USERNAME=admin CASINO_KEYCLOAK_ADMIN_PASSWORD=admin \
  just modules realm goldvibe --secrets <a file outside the repository>
```

The file's `goldvibe-players/<KEY>` lines (§7) give `players-admin` its secret and the realm its two
shared secrets; the tool also declares the user profile `players` needs (`eitheror_twin_id`, no
required names) and makes the operators' realm `eitheror`. Without the other services,
`sbt "Test/runMain io.eitheror.casino.players.it.fakes"` in `services/players` stands them in on
9010–9017 and prints each link `engagement` is asked to send. Then, with the same six values in the
environment as `PLAYERS_REALM_ADMIN` … `PLAYERS_MERCHANT_KEY`:

```bash
just players-run    # CASINO_BRAND=goldvibe; ANKKA_HTTP_PORT=9003; the local-services at the fakes
curl -s -X POST localhost:9003/v1/casino/players/goldvibe/register -H 'Content-Type: application/json' \
  -d '{"email":"p1@example.test","password":"Correct-horse-1","currency":"EUR","country":"GB","dateOfBirth":"1990-01-01"}'
```

Expected (run 2026-10-27): `201` with `playerId` and `status: pending_email_verification`; the same
command again: `409 email_already_exists`; the printed link's `token` posted to `POST …/verify-email`:
`200 {"status":"verified"}`; then `POST …/login` with the address and password: `200` with `token`,
`refreshToken`, `brandId`, `playerId`, `currency`, `country`. The operator route
`GET /v1/casino/backoffice/goldvibe/players/{id}/verification-link` needs a staff token and
`players-run` trusts only the player issuer, so locally the printed link stands in for it. Inspect
(`ankka local console`, or the MCP tools `describe_local_service` and `query_local_entity`): the
player `active` with one sign-in session in `openPlayerSessions`, the `player-session` entity
`active`, the `registration` workflow (id: the SHA-256 of `goldvibe:<address>`) at `ended` with one
send, and the `realm-reconcile` timer firing every 60 s. A launch through
`POST /v1/casino/brands/goldvibe/players/{id}/game-sessions` answers `201 {sessionId}`; a
`game-session-limit/<sessionId>` timer is scheduled only for a player with a session limit, which
comes from `responsible-gambling`'s facts.

## 7. An installation (kind)

The images first, as the modules name them (`europe-west1-docker.pkg.dev/eitheror/casino/<service>:0.0.0`),
built in each service with `DOCKER_REPOSITORY=europe-west1-docker.pkg.dev/eitheror/casino
SERVICE_VERSION=0.0.0 sbt Docker/publishLocal` and loaded with `kind load docker-image <image> --name
ankka`. Then, with `CASINO_KEYCLOAK_ADMIN_USERNAME`/`_PASSWORD` (dev-kind: `admin`/`admin`) and
`CASINO_KEYCLOAK_CA=~/.ankka/local-ca.crt`:

```bash
just modules render goldvibe
just modules apply goldvibe --installation dev-kind --modules core --allow-unbuilt --secrets <a file outside the repository>
just modules secrets goldvibe --installation dev-kind --secrets <the same file>
just modules apply goldvibe --installation dev-kind --modules core --allow-unbuilt --secrets <the same file>
```

`--allow-unbuilt` because the core still names `kyc`, `payouts` and `attribution`, which have no build
(`contract/keys.md`): apply refuses them otherwise. They have no image on kind, so they never become
Ready and apply waits on them; remove them with `ankka services delete <name> --project goldvibe`.

The secrets file holds `<secret>/<KEY>=<value>` lines: `goldvibe-kyc/API_KEY`, `goldvibe-st8/API_KEY`, and
`goldvibe-players/` `REALM_ADMIN`, `WEBHOOK_SECRET`, `REALM_SECRET`, `EMAIL_TOKEN`, `LOOKUP_KEY`,
`MERCHANT_KEY` (`contract/keys.md`). The first `apply` creates the project and the realm (the
`players-admin` secret, the two shared ones and the user profile from the file) and holds the core
module for its missing keys; `secrets` needs the project, so it comes second; the second `apply`
creates the services. A realm that already exists is not changed: delete it first. A service without
an image takes CPU it cannot use; pause it (`ankka services pause <name> --project goldvibe`) so the
others schedule. The installation's `INGEST_URL` (`installation/overlays/dev-kind/settings.env`) is
where a deposit opens its cashier.

Run 2026-10-27 on `kind-ankka` (never production). Results:

- `wallet` and `players` Ready.
- `players` exposed at `https://players-goldvibe.127.0.0.1.sslip.io:8443`, and every topic check
  `checked`.
- A registration through the gateway: `201`, then `409`.
- Verification: `200`.
- The operator's verification link, with a `brand_goldvibe` operator's token from the `eitheror`
  realm: `200`; without a token, `401`.
- `players` needs `medium` (1Gi): at `small` it was OOM-killed. The core module sets it.
- The realm routes by hand, with the secrets: `may-sign-in` and the webhook answer as
  `contracts/realm.md` says (research *Findings*).
- No address, password or token in the pod's log.

Not reached on that run:

- `game-provider`: the casino module was not applied (research *Findings*).
- A sign-in: refused `rg_unavailable` until `responsible-gambling` (010) runs, since sign-in fails
  closed (FR-011).
- The reconciler: it logs only when it ends expired sessions or the realm does not answer.
- Whether the installation's Keycloak reaches the realm routes from inside the cluster: waits for the
  rebuilt plugins.
