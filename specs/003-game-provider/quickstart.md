# Quickstart: proving game-provider end to end

What to run, in the order the tasks build it, and what each step must show. Paths are from the
repository root; the service's own commands run in `services/game-provider/`.

## Prerequisites

- JDK 21, sbt, Docker (the integration test kit starts Postgres), `uv` (`just features`).
- The `ankka@ankka` plugin installed (`/plugin install ankka@ankka`) for `ankka-port` and
  `ankka-inspect`; the `ankka` CLI on the path.
- The contract published locally with the `round_settled` additions: `sbt contract/publishLocal`
  from the root, then `just schemas` leaves no diff.
- For recording only: the original checked out beside this repository, as `tests/original/README.md`
  describes, with `fake-st8` running.

## 1. Recordings exist

```bash
ls tests/port/game-provider/            # TestScenario15_St8Wallet_*.json (30), TestScenario7_CasinoGate3_* (4), TestScenarioFreeSpinsPlay_* (4)
just brands recordings check            # every recording repeated (runs: 2), none refused
```

Expected: 38 recordings plus `suite-result.json`; each names its requirement (`003/FR-0xx`).

## 2. The service builds and its unit suites pass

```bash
cd services/game-provider
sbt compile test                        # entity kits: Ticket, Round, BetWorkflow, LaunchWindow, Game, BrandCatalogueConfiguration; consumer kits: RoundFacts, RoundCounter; St8 signature round trip; amount parsing
```

Expected: the `Round` kit shows a credit to a two-debit round settling the first bet with the win
and the second as a loss, a repeat answered from the stored outcome with no second movement, and a
conflicting repeat recorded. The signature test verifies the recordings' test key and refuses a
signature over different bytes.

## 3. The features bind and pass against the kit

```bash
sbt "testOnly *GameProviderFeatures"    # GherkinSuite("../../features/game-provider") over AnkkaTestKit with the fake wallet, the fake neighbours and the ST8 stub
```

Expected: every scenario of the nine features green, none ignored; the suite fails if the directory
has no scenarios. *Measured 2026-10-09*: 73 scenarios, 82 tests with the outline rows, all green, bound
one suite per feature file (`LaunchFeatures` … `FreeRoundsFeatures`, `sbt "testOnly *Features"`) on one
shared service.

## 4. Parity

```bash
sbt "testOnly *ParitySuite"             # one test per recording in tests/port/game-provider
```

Expected: SC-001, every `TestScenario15_St8Wallet_*` answers the same status word and balance to
the minor unit; `WrongSignature` is refused; `IdempotentDebit`, `IdempotentCredit`,
`IdempotentCancel` make no second wallet call (the fake wallet counts). The tests whose scenario
drives `/st8/test/*` set up through the kit and are marked with that divergence.

Break one on purpose once: change the credit to settle only the first bet and watch
`BigRoundMultipleDebits` fail on the leaked second bet (SC-003), then put it back.

## 5. Local run with the simulator

```bash
cd services/game-provider && sbt run                             # the service on :9000, local project `local`
# spec 020's simulator, or the in-process stub, on :8099
curl -X PUT localhost:9000/v1/casino/backoffice/goldvibe/game-provider/providers/st8 \
  -H 'Authorization: Bearer <operator token>' -d @../../tests/fixtures/specs/003/st8-local.json   # apiUrl http://localhost:8099, providerKeyDocumentUrl, site, keys, links
curl -X POST localhost:9000/v1/casino/backoffice/goldvibe/game-provider/providers/st8/catalogue/refresh -H 'Authorization: Bearer <operator token>'
curl localhost:9000/v1/casino/catalog/games?brand=goldvibe&limit=5 -H 'Authorization: Bearer <player token>'
curl -X POST localhost:9000/v1/casino/sessions/goldvibe/launch -H 'Authorization: Bearer <player token>' \
  -d '{"playerId":"<id>","gameId":"<code>","currency":"EUR"}'
```

Expected: the refresh status reaches `succeeded` with the simulator's count; the catalogue lists
games with `provider: "st8"`; the launch answers `launchToken`, `gameUrl` on the simulator and a
`sessionId`; opening `gameUrl` has the simulator `check` the ticket and then debit and credit a
round, visible on `GET …/game-provider/rounds?playerId=<id>` and as one `round_settled` on the
local broker. Rotate the simulator's key (020's "rotated key" scenario): the next callback verifies
within 60 seconds with no restart.

## 6. On an installation

```bash
just brands apply brands/goldvibe/manifest.json     # with game-provider's image pinned
ankka services get game-provider --project goldvibe # Ready; exposed at game-provider-goldvibe.<base>
```

Then step 5's operator and player calls against the exposed host, the provider configuration naming
the simulator's key document (goldvibe is not real money); `ankka-inspect` confirms the entities and
views listed in data-model.md exist and the `casino.rounds` consumer publishes.

Expected: SC-002, a money callback under 100 ms at the 95th percentile with the wallet in the same
project, read from the kit's timing report; SC-005, 100 callbacks under another key all `auth_failed`.

*Measured in the kit, 2026-10-09* (`LeakSuite`, `target/sc-002-timing.txt`): 3,250 money callbacks
over HTTP with the fake wallet on loopback, p50 9 ms, p95 57 ms; *after the bet workflow (2026-10-10)*:
p50 6 ms, p95 11 to 12 ms, max 47 to 74 ms over three runs. SC-003: 1,000 rounds, multi-debit,
zero credits and cancels included, no bet left open; breaking the settlement to the original's rule
fails the suite. SC-004: 1,000 repeats, no wallet call. SC-005: 100 of 100 refused. On an installation
these are still to be read.

*Step 6, 2026-10-10* (the local kind installation, ankka 0.11's `deploy-local.sh`; the full goldvibe
manifest applied with game-provider at a locally loaded image and the ten services that have no image
yet paused):

- **Running**: game-provider Ready at `medium` with no restart, every component registered. Two faults
  in the service stopped it on the first attempt, both fixed: it opened Kafka in plaintext
  (`ProjectionRuntime.withKafka`) where the installation's broker is TLS by the service's certificate,
  which a plaintext client misreads as a ~350 MB message and dies of `Java heap space` at any size
  (`fromEnv` now); and its topic view and producer named no contract, which ankka refuses where the
  project declares one (`Topic.contract` in the contract library, named by `player-status` and
  `round-facts`). At `small` (512Mi) it then runs at 507 MB resident, so the manifest gives it `medium`.
- **Shown through the public hostname**: no token is 401; an operator of `casino-goldvibe`'s brand in
  `eitheror` writes the ST8 configuration (204) and reads it back without the private key; a callback
  signed with the configured key passes the signature (a balance for an unknown player answers
  `session_expired`, FR-017); under another key it is `auth_failed` at the brand path and the root; the
  metrics are served. A signed payout with the wallet paused is answered `error` in 1.3 s, recorded
  unacknowledged with its body, listed on the operator route and counted by the gauge; its repeat
  resumes the workflow (research R18).
- **Fixed in the installation and the brands tool**: the casino Keycloak's route on the gateway; its
  issuer with the installation's port (`KC_HOSTNAME_URL`); the brands tool trusting the installation's
  authority for Keycloak (`CASINO_KEYCLOAK_CA`, else `ANKKA_CA`); and the keys read inside the cluster
  (`KEYCLOAK_JWKS_BASE`), since a pod resolves `*.127.0.0.1.sslip.io` to itself.
- **Fixed since (spec 001's)**: nothing issued the staff audience `eitheror-casino` the manifests give
  every service (the original's realm puts `account` in an operator's token), the `eitheror` realm was
  not created by anything, and the development Keycloak kept nothing across a restart. The brands tool
  now imports `brands/_realm/eitheror-operators.json` (every client's tokens carry `eitheror-casino`)
  and dev-kind keeps Keycloak's database on a volume: an operator created in the realm the tool made
  writes the ST8 configuration, and both realms survive a restart.
- **Platform, for the ankka repository**: the local operator (256Mi) is OOM-killed reconciling eleven
  services; kindnet once stopped enforcing new network policies after API timeouts under load; the
  control plane times out through the gateway now and then; a rolling update keeps a crash-looping old
  pod beside the new one, and two such pods never form a cluster.
- **The real wallet beside it** (spec 002's service, at `medium`): Ready once its four publishers named
  their topics' contracts, the same refusal as above. game-provider reaches it over the platform's
  mutual TLS and is answered: a payout for a player with no wallet is refused `WALLET_NOT_FOUND`, and
  the replay of the earlier unacknowledged payout is answered `still_unacknowledged` with that error.
- **Found, for specs 002 and 003 to settle**: only a deposit creates a wallet (spec 002), while the
  original's first payout creates one (the ST8 recordings fund a fresh player with `/payout`). On the
  real wallet those recordings' payouts would be refused. And a deposit asks `responsible-gambling`'s
  `may-deposit`, failing closed, so no money moves on an installation until that service answers.
- **Not reached**: a payout or round that moves money (above), a launch (needs `players`, `kyc`,
  `responsible-gambling`), the nightly refresh against a provider (needs spec 020's simulator), SC-002
  on the installation.
