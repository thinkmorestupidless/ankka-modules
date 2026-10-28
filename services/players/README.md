# players

The brand project's account service (spec 008): it registers a person as a player of the brand,
signs them in and out through the brand's realm, and keeps their profile, sessions, status, inbox,
duplicates, erasure and export. It is the brand's door for a deposit too: it asks `kyc`, the wallet's
sums and `responsible-gambling` in turn, then opens the cashier at the shared payments' `ingest`.

The realm is the authority on the address and the password; `players` is the authority on everything
else about the player. No address, name, token or password is in a log, an audit record or a fact
outside its personal fields; the password is in no table (SC-006, `LeakSuite`).

## The routes

Seven endpoints, one prefix each (ankka 0.13 lets no two share a prefix). `contracts/routes.md` in
`specs/008-players/` has every route, its answers and where it differs from the original.

| Endpoint | Prefix | Who calls it |
|---|---|---|
| `PlayerEndpoint` | `/v1/casino/players/<slug>` | a visitor (register, verify, login, refresh) and the player (profile, addresses, consents, inbox, game history, close, erasure, export, deposits) |
| `SessionEndpoint` | `/v1/casino/sessions/<slug>` | the player: sessions, ending a game session |
| `OperatorEndpoint` | `/v1/casino/backoffice/<slug>` | an operator of the brand (`brand_<slug>`) or `platform_admin`: search, status actions, notes, messages, fraud cases, configuration, erasure and export |
| `ModulesEndpoint` | `/v1/casino/brands/<slug>/modules` | an operator: the brand's modules, through the vendor's agent |
| `ServiceEndpoint` | `/v1/casino/brands/<slug>/players` | the brand's services by name: `game-provider`, `support-agent`, `kyc`, `responsible-gambling`, `engagement`, and `players` itself |
| `RealmEndpoint` | `/internal/realm/<slug>` | the brand realm's plugins, under the realm's shared secret: the audit webhook, `may-sign-in` |
| `GeoEndpoint` | `/v1/casino/geo` | anyone: the country for the caller's address |

## The components

- `player/`: `PlayerEntity` (one per player: profile, status, sessions open, flags, the erasure filed)
  and `PlayersView` for the operator's search.
- `registration/`: `RegistrationWorkflow` (the realm user first, then the player, undone if the
  player cannot be written), `PendingRegistration`, `LookupIndexEntity` (the duplicate tokens) and
  the verification email.
- `sessions/`: `PlayerSessionEntity`, `GameSessionEntity`, `SessionEndWorkflow` (the cascade that
  survives a crash), the game-session limit timer and the realm reconciler's recurring timer.
- `inbox/`: messages, notes and merge tags. `fraud/`: duplicate cases.
- `erasure/`: `ErasureWorkflow` (the hold, each service's part, the realm user removed) and
  `ExportWorkflow` (each service's part under `EXPORT_PART_BOUND`, kept `EXPORT_RETENTION`).
- `reads/`: the activity, recently-played and wallet-summary views folded from the topics.
- `facts/`: the consumers that put `casino.players` and `casino.sessions` facts on the topics.
- `config/`: the brand's configuration, currencies and allow-list.
- `realm/`: the realm client (`KeycloakRealm`, admin token by client credentials as `players-admin`).

## Settings and secrets

| Variable | Default | What |
|---|---|---|
| `CASINO_BRAND` | (required) | the brand's slug |
| `CASINO_REAL_MONEY` | `false` | whether the brand takes real money |
| `KEYCLOAK_URL` | | the Keycloak serving `casino-<slug>`; the core reads `keycloak-jwks-base` |
| `PLAYER_SITE_BASE` | | the player site the verification link points at |
| `INGEST_URL` | none | the shared payments' `ingest`; without it a deposit answers `503` naming `ingest` |
| `REALM_RECONCILE_INTERVAL` | `60s` | how often sessions the realm no longer holds are ended |
| `VERIFICATION_VALIDITY`, `VERIFICATION_RESEND_INTERVAL` | `24h`, `5m` | the verification link |
| `MAY_LOG_IN_BOUND`, `REALM_ANSWER_BOUND`, `SESSION_END_BOUND`, `REGISTRATION_BOUND` | | the bounds (research R4–R6) |
| `EXPORT_PART_BOUND`, `EXPORT_RETENTION` | `10s`, `24h` | the export |
| `ERASURE_HOLD_POLL` | `1h` | how often a held erasure looks again |

The six secrets are read by name from the service's secret store, else from the environment
(`players-realm-admin` is `PLAYERS_REALM_ADMIN`). On an installation the core module renders each as
a `secretKeyRef` to `tenant-values`, filled by `just modules secrets <slug> --secrets <file>` from
the file's `<slug>-players/<KEY>` lines.

| Secret | Line | What |
|---|---|---|
| `players-realm-admin` | `<slug>-players/REALM_ADMIN` | `players-admin`'s client secret (bare, or `{clientId, clientSecret}`) |
| `players-webhook-secret` | `<slug>-players/WEBHOOK_SECRET` | the realm listener's shared secret; also the realm attribute `players_webhook_secret` |
| `players-realm-secret` | `<slug>-players/REALM_SECRET` | the authenticators' shared secret; also `players_realm_secret` |
| `players-email-token` | `<slug>-players/EMAIL_TOKEN` | signs the verification links |
| `players-lookup-key` | `<slug>-players/LOOKUP_KEY` | keys the duplicate-detection tokens |
| `players-merchant-key` | `<slug>-players/MERCHANT_KEY` | the brand's merchant key at `ingest` |

The brand's realm gets the `players-admin` client (a service account with `realm-management`'s
`manage-users`, `view-users` and `view-clients`) and the three realm attributes from `ankka casino
realm <slug> --secrets <file>`, or `apply`. Both add them only when they create the realm; a realm
that exists is left as it is.

## Run it locally

```bash
cd services/players && sbt schema && docker compose up -d     # Postgres on 5435, an empty Keycloak on 8080
CASINO_KEYCLOAK_URL=http://127.0.0.1:8080 CASINO_KEYCLOAK_ADMIN_USERNAME=admin CASINO_KEYCLOAK_ADMIN_PASSWORD=admin \
  just modules realm goldvibe --secrets <file>                # the brand realm, as on an installation
sbt "Test/runMain io.eitheror.casino.players.it.fakes"        # the neighbours, on 9010–9017; prints each link
just players-run                                              # on 9003 as goldvibe, the six PLAYERS_* set
```

`specs/008-players/quickstart.md` §6 has the register, verify and sign-in sequence; last run
2026-10-27, as §6 says. The realm must come from the tool: the template imported as it is has no
`players-admin`, and Keycloak 24's default user profile drops `eitheror_twin_id` and holds every
sign-in at `VERIFY_PROFILE` for the names a registration need not give.

## Test it

```bash
just players-test   # every suite; the integration kit needs Docker
just players-it     # the integration, parity and feature suites only
```

- Unit: the domain, the entities, the workflows and the codecs, each in its own package.
- `it/`: the whole service against a throwaway Postgres with the neighbours as fakes (`Kit`). One
  suite per story (`RegistrationSuite`, `SessionsSuite`, `ProfileSuite`, `OperatorSuite`,
  `DuplicatesSuite`, `ErasureSuite`, `DepositsSuite`), `LeakSuite` for the personal data,
  `RealmSuite` for the plugins' routes, `DurabilitySuite` for a restart over the same database.
- `features/`: the scenarios of `features/players/*.feature`, bound.
- `port/`: one parity test per recording under `tests/port/players/`, through `WireTranslation`.
  Ignored until the recordings exist (`tests/original/README.md`, "Recording the players").

## Depends on

- The realm's plugins (`contracts/realm.md`): `EitherOrAuditListener` and the authenticators must be
  rebuilt to call this service, not the original's. Until they are, the webhook and `may-sign-in` are
  exercised by hand.
- The export routes of `wallet` (spec 002) and `merchant` (spec 004): until each serves its own, its
  part of an export is named under `missing`.
