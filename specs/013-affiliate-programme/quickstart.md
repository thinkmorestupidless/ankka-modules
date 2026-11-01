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
just programme-run                # the service, Postgres, an InMemoryBroker carrying goldvibe's topic
```

Apply as an affiliate to `goldvibe`, approve as a manager, create a link, publish a `registration` and
a `first-deposit` fact on the topic, and read `/v1/my/{affiliate}/revenue?brand=goldvibe`: one CPA.

## 7. Kind

```bash
just modules apply goldvibe --installation dev-kind
ankka services apply shared/programme --context kind-…   # never the default context
```

Expected: the programme consumes `goldvibe`'s `affiliates.attribution` through `core`'s grant; a hosted
brand added by `POST /v1/programme/brands` posts a signed fact and it lands in the ledger.
