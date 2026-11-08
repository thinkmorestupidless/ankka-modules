# Quickstart: proving the programme

Phase 1 of [plan.md](plan.md). In build order; each step names what it proves.

## 1. Features and contract

```bash
just features                     # 0 findings
just build && just schemas        # Routes.affiliateManager, brandsOf; schemas unchanged
sbt contract/publishLocal
just modules render goldvibe      # programme/affiliate-programme gets PROGRAMME_BRANDS=goldvibe
just modules validate --all
```

## 2. Unit and kit suites

```bash
just programme-test
```

Expected: the `commission` package passes the original's lifted `CommissionEngineSpec` and
`CommissionFormulasSpec` cases; `PlayerAttribution`, `CommissionAccount`, `Membership`, `Payout`,
`BonusCode`, `InviteLink`, `Token` pass every row of their command tables (data-model.md); `GherkinSuite`
binds every scenario of the ten `features/programme/*.feature`.

## 3. Integration suites (Docker)

```bash
just programme-it
```

| Suite | Proves |
|---|---|
| `IntakeSuite` | two brands' topics and a hosted brand's webhook into one ledger; duplicates on one and across channels write once (SC-004); a mismatched brand writes nothing; a bad signature is refused |
| `CommissionSuite` | CPA once in the plan's currency; the GGR source switch; before-binding days; carryover |
| `NetworkSuite` | three tiers of override; cycles; invitations |
| `CloseSuite` | a thousand closes with interleaved late facts: no fact claimed twice, none older than the last close left unclaimed (SC-006); approve with a figure; paid stays paid |
| `AccessSuite` | a manager of brand A sees nothing of B; a hosted brand's manager and an eitheror manager sharing an affiliate (SC-010); `platform_admin` only for usage and brands |
| `LeakSuite` | `pg_dump` of the service's database, journals included: no test name, email or IBAN; after erasure the secret store cannot read them either (SC-007) |
| `CommunicationsSuite` | `FakeCustomerIo` receives each send; a failing postback receiver gets retries to 24 h on a `MovableClock`, its outcome visible within a minute (SC-009) |
| `DocumentsSuite` | a tag signed here verifies with spec 012's `Tag` against the key document; revocations, legacy tags, the code document and its version |
| `LatencySuite` | a fact reflected in revenue within 10 s (SC-005) |
| `ImportSuite` | a recorded bundle imports, folds to the original's figures, and imports again with nothing written (SC-008) |

## 4. Recording the original

On the original's kind stack (memory: *Recording the original on kind*) with the affiliate service up:

```bash
cd tests/original/programme-driver && go run . --out ../../port/programme
```

Expected: one recording per scenario of contracts/recording.md in `tests/port/programme/`.

## 5. Parity

```bash
just programme-parity
```

Expected: every recording replays; every difference is named by a change number.

## 6. A local run

```bash
(cd services/affiliate-programme && sbt schema && docker compose up -d)   # Postgres on 5441
just programme-run                # the service on 9018; facts of a brand here are read in memory
just programme-walk               # in another shell: the walk-through below, each step checked
```

`programme-walk` (`tests/local/programme-walk.py`) is the local run's staff issuer while it runs: a
throwaway key under `target/`, its JWKS on `localhost:18180` where the recipe looks, and tokens with
the claims ankka reads. A brand here's topic is in memory, out of reach, so the walk uses a hosted
brand, whose webhook is the door a local run has. A platform administrator adds a hosted brand with a
25.00 EUR CPA plan; an affiliate applies; the brand's manager approves; the affiliate creates a link;
the brand posts a signed `registration` and a 50.00 EUR `first-deposit`. The affiliate's
`/v1/my/{affiliate}/revenue?brand=…&period=<this month>` then shows one CPA, 25.00 EUR.

## 7. Kind

```bash
just modules apply goldvibe --installation dev-kind
just modules apply shared/programme/manifest.json --installation dev-kind --secrets <file>
#   <file>, outside the repository: keycloak-operators-admin/USERNAME=… and /PASSWORD=… (dev-kind: admin/admin)
PROGRAMME=https://affiliate-programme-programme.127.0.0.1.sslip.io:8443 PROGRAMME_CA=~/.ankka/local-ca.crt \
  STAFF_TOKEN=<a platform_admin's access token from the casino realm> just programme-walk
```

Expected: the programme's log says it subscribed to `goldvibe/affiliates.attribution` through `core`'s
grant; the walk adds a hosted brand, whose signed registration and first deposit land in the ledger as
one 25.00 EUR CPA. With a real realm nothing is minted: the one staff token adds the brand, approves,
and makes the link through the back office (O36). The programme is `medium` (1 CPU, 1Gi): at 512Mi it
was killed under its 19 views. On a single kind node, other projects' services may need pausing for it
to be scheduled. Run on 2026-11-07, with goldvibe's `game-provider` and `rewards` paused.
