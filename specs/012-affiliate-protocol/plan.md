# Implementation Plan: attribution

**Branch**: `012-affiliate-protocol` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/012-affiliate-protocol/spec.md`

## Summary

`attribution` is the brand's side of the affiliate protocol: a core service (spec 021) that serves the
original's click route verbatim, binds a player once from the brand's own facts (a signed tag or an
affiliate's bonus code at registration, or a code within the window after it), keeps the attribution
record as the brand's regulated book, derives a pseudonym per network, and emits the fourteen kinds of
fact on `affiliates.attribution` from the player's deposits, disputes, withdrawals, awards and real-money
play, with GGR per reporting day. It carries the facts three ways: the topic, a signed cursor-paged feed
for a registered machine, and signed webhooks with a day of retries and an undelivered list. It trusts a
network through four public documents it caches, and refuses everything else a network could say.

Four event sourced entities, seven key value entities, one workflow, four views, ten consumers, two
timed actions. Decisions and their evidence are in [research.md](research.md); the two that reshape the
spec are R9 (bonus cost and chargebacks from core facts) and R10 (GGR from the wallet's real movements,
not `casino.rounds`), both spec corrections on this branch.

## Technical Context

**Language/Version**: Scala 3.9 on ankka 0.13.0 (constitution VI). Go for the recording driver, in the
original's toolchain.

**Primary Dependencies**:

- `com.thinkmorestupidless` `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-telemetry-otlp`, `ankka-testkit`;
- `io.eitheror:casino-contract`, published locally, with two additions on this branch: `Routes.machine`
  and `GgrDaily.beforeBinding` (research R18);
- the JDK: `java.security.Signature("Ed25519")`, `KeyPairGenerator("Ed25519")`, `Mac("HmacSHA256")`,
  `MessageDigest`, `java.net.http.HttpClient`. No cryptography library.

**Storage**: the service's own CNPG Postgres from ankka (journals, state, view tables, timers, the secret
store). Service secrets: a pseudonym key per network, the brand's signing keys, each webhook's secret, the
click salt (data-model.md *Settings*). Nothing personal stored (research R5, R11).

**Testing**:

- munit; the entity, workflow and consumer kits with `InMemorySecretStore`;
- `AnkkaTestKit.start` over Postgres with `InMemoryBroker` carrying `casino.players`, `casino.rewards`,
  `payments.payments`, `casino.withdrawals`, `money.transactions` and the brand's own
  `affiliates.attribution`, `TimerRuntime` on a `MovableClock`, a `FakeNetwork` (the four documents, a
  tag minter, a webhook receiver), a machine-token stub beside the staff stub;
- `GherkinSuite` binding the six `features/attribution/*.feature`;
- `contract.port.Recording` for `tests/port/attribution/` (research R19).

**Target Platform**: the brand project on the self-hosted installation (kind locally, GKE). Exposed: the
click route is public and the feed is a machine's; the operator routes admit the brand's operators.

**Project Type**: one ankka service (`services/attribution/`), one Go driver (`tests/original/attribution-driver/`),
two contract additions, manifest and key-map changes, six spec corrections (research).

**Performance Goals**:

| Criterion | Target |
|---|---|
| SC-004 | a fact on the topic within 60 s of its input, and on the feed within the same (two consumers' lag, both seconds) |
| SC-005 | no webhook delivery lost: a delivery is a workflow in the database; the dead letter only after 24 h of failures |
| SC-006 | zero personal values in a day's facts on every carrier (`LeakSuite`) |
| SC-007 | a token of a test network at one installation verifies at a brand holding only its key document |
| SC-008 | every legacy tag in the document resolves on day one; the share of legacy tags visible in the key document's `transition` |
| FR-016 | a day's tallies closed within `CLOSE_GRACE` + 5 minutes of the reporting zone's midnight |

**Constraints**:

- minor units as `Long` from the facts; every amount on a fact whole, signed only where the contract
  says (`ggr`, `bonus-cost`);
- at most one binding per player, ever; nothing a network says changes a record, a fact or an amount;
- no `Personal` value and no player id on any fact or carrier; the pseudonym key never leaves the
  service's store and is never rotated;
- every fact's id, key and content the same on every emission (FR-022);
- no call to any brand service; one route called by one (`players`, erasure); no call to a network at
  registration (documents cached);
- four endpoints with no shared prefix, at most two path parameters per route, ACL per route group;
- the service runs with or without the casino module (`casino.rewards` optional).

**Scale/Scope**:

| Item | Count |
|---|---|
| Public routes | 2 (the click, the brand's key document) |
| Machine routes | 1 (the feed) |
| Service routes | 1 (erasure) |
| Operator routes | 18 at 2 prefixes |
| Event sourced entities | 4 (`AttributionRecord`, `DayTally`, `NetworkFeed`, and `Delivery` as a workflow) |
| Key value entities | 7 |
| Views | 4 |
| Consumers | 5 of the brand's topics, 3 publishers, 2 carriers |
| Timed actions | 2 |
| Feature scenarios bound | 46 (outlines once per row: landing 8, bonus-codes 3, carriers 7, facts 13, closure 5, networks 10) |
| Recordings replayed | 4 from the suite, 8 from the driver (contracts/recording.md) |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | The plan names the recordings and the parity tests it leaves; divergences declared | Pass, with the caveat that no affiliate recording exists yet (research R1, R19): contracts/recording.md names the four suite scenarios to record with the affiliate service running and the driver's eight, one parity test per recording, with the translation from the original's notifications and twin reads to facts. *Divergences* below lists every change with what demonstrates it. Six spec corrections (research) ground the spec in the original where it had drifted (bonus cost, the GGR source) or left a hole (the code document, the window). |
| II. Money is exact, the wallet is the ledger | `Long` minor units; every money command keyed; no balance kept | Pass. Every amount comes from a fact in minor units and is re-emitted unchanged; GGR is a sum of the wallet's real movements, read from the wallet's own balances-after (R10). Every money event is keyed by its dedupe id and applied once (`AttributionRecord.money`, `DayTally.leg`). Nothing here holds a balance; the tally is a report of the ledger, not a ledger. |
| III. A brand is a project | A shared project reaches a brand only through a grant; public routes by their own credential | Pass. The programme reads the topic by the grant core declares; a machine reads the feed by a grant on the route and a token (R14); the click is public and verifies the tag itself. `attribution` calls no service; `players` calls its erasure route within the project. The programme holds only pseudonyms (R4). |
| IV. Commands over HTTP, facts over topics | No store read across services | Pass. Every input is a fact on a brand topic (FR-030); every output a fact on the brand's topic, produced by consumers over the entities' events, as the wallet's are (R13). The feed is a view over the brand's own facts. Documents are public reads, not calls. |
| V. Personal data marked, erasure honoured | | Pass. Nothing stored is personal (data-model.md); the click's address digest is salted and never emitted (R5, finding 1); the erasure route exists and answers what the record holds, which is nothing to erase (R11); facts stand after erasure (FR-028). `LeakSuite` is SC-006. |
| VI. Scala by default | | Pass: Scala. Go only for the driver. |
| VII. Specs in the domain's words | `just features` passes; every requirement traced | Pass: 0 findings in 1,106 scenarios after correction 1 removed two outline rows (1,108 before). |
| Workflow: clarify before plan | | Pass: clarified 2026-10-08 (twice) and 2026-10-25. |
| Scope: ankka gaps are ankka specs | | Pass. Three verify-at-task-time items with fallbacks (Phase 0); nothing worked around. A seventh route class and a machine grant made at runtime are findings for 001 and 021, not workarounds. |

No violations; Complexity Tracking is empty.

## Component table (ankka-design)

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-006, FR-010: the click | `TrackEndpoint` (public); `Click` (key value, id the click id, 60-day expiry); `ClickFacts` publisher | one entity per click makes a repeat under the same id a no-op, and the fact's id is the click's | an event on the record (no player yet); a view of clicks (nothing reads clicks but the fact) (R5) |
| FR-002, FR-003, FR-005: the tag | `Tag` (pure), `Signing` (pure) over `NetworkDocuments` | verification is a function of the tag and the cached documents; nothing to persist | a call to the network (FR-023 forbids) (R2, R6) |
| FR-004, SC-008: the transition | `Settings` (key value, id `brand`); `DayCloser` ends it; the brand's key document publishes it | one place for the brand's dates; the daily timer already runs | a route of its own for the programme (a grant for a public figure) (R16) |
| FR-007, FR-008, FR-009, FR-013: the binding | `PlayersFacts`, `RewardsFacts` consumers; `AttributionRecord` (event sourced, id the player id) | the record is the regulated book: every binding, refusal and touch an event; the pseudonym derived once in the consumer that holds the store | a key value record (loses the history a regulator asks for); deriving the pseudonym in the entity (no store there) (R4, R8) |
| FR-011, FR-012, FR-014, FR-015, FR-017, FR-027: the facts | `RecordFacts`, `TallyFacts` publishers; `Facts`, `FactIds` (pure) | one publisher per source entity, as the wallet; deterministic ids from the record's state | a fact entity per fact (a journal of copies) (R13) |
| FR-016, SC-001: GGR | `MoneyFacts` consumer; `WalletCursor`, `RoundIndex` (key value); `DayTally` (event sourced, id `player/currency/day`); `OpenDays` view; `DayCloser` timed action | the real movement is the wallet's balance difference; a tally per day closes once and is small; a timer closes what the view lists | `casino.rounds` as the source (no wallet split, a product module's); tallies on the record (a journal of every bet) (R10) |
| FR-018, FR-019, FR-022: the topic | the three publishers with the contract; the grant in `modules/core/module.json` | already declared and granted (spec 021) | — |
| FR-020: the feed | `FeedAppender` (own-topic consumer); `NetworkFeed` (event sourced, id the network); `FeedRows` view; `AttributionEndpoint` feed route under `Routes.machine` | the feed is the topic's order per network, append-only, so a cursor is a sequence number; signing from the endpoint, which has the store | a view straight over the topic (no sequence numbers; a page could change) (R14) |
| FR-021, SC-005: webhooks | `WebhookSubscription` (key value); `WebhookFanout` consumer; `Delivery` workflow per fact and subscription; `Deliveries` view; `WebhooksEndpoint` | a workflow's pause is the retry schedule and survives restarts; an undelivered workflow is the dead letter | a timer per attempt; one workflow per subscription (a slow receiver blocks its network's facts) (R15) |
| FR-023, FR-024: the documents | `Network`, `NetworkDocuments` (key value, id the network); `DocumentRefresh` timed action; `Documents` (pure) | the cache is state the operator can read; the refresh runs on the stated interval | fetching on every tag (FR-023 forbids a call at registration; a down network would refuse every tag) (R3) |
| FR-025, FR-026: networks | `Network`; the operator routes; the pseudonym key in the store | a network is configuration an operator edits; the key is a secret | the network in the manifest (networks come and go at runtime) (R12) |
| FR-028: erasure | the erasure route on `AttributionEndpoint`, `Routes.service()` for `players` | as `merchant`'s `ErasureEndpoint` | an erasure fact (008 has none) (R11) |
| FR-029: one service | `services/attribution/` | — | — |
| FR-030: the inputs | the five topic consumers | every input a fact; `casino.rewards` registered only with the casino module | — (R7, R9) |

Pure logic with no component: `Tag`, `Documents`, `Pseudonyms`, `Signing`, `Jcs`, `RealMoney`, `Days`,
`FactIds`, `Facts`, `Backoff`, `Pages`, `Settings`.

## Project Structure

### Documentation (this feature)

```text
specs/012-affiliate-protocol/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R20, spec corrections, findings, open for Sheldon, for ankka
├── data-model.md        # Phase 1: the entities, the workflow, the views, the consumers, the timers, the settings
├── quickstart.md        # Phase 1: proving it, in build order
├── contracts/
│   ├── protocol.md      # the tag, the four documents, the pseudonym, a page, a webhook post
│   ├── routes.md        # every route by audience; bodies and refusals; what is not served
│   ├── facts.md         # each input family to its output fact, with amounts, references and ids
│   └── recording.md     # the suite scenarios to record, the driver's scenarios, the translation, the marked divergences
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/
├── access/Routes.scala                     # + machine() (research R18)
└── facts/AffiliatesAttribution.scala       # + GgrDaily.beforeBinding; schema regenerated
modules/core/module.json                    # attribution's variables; reporting-zone key
contract/keys.md                            # + reporting-zone; attribution built
brands/goldvibe/values.json                 # + reporting-zone
services/attribution/                       # ankka init; one sbt build; depends on casino-contract
├── build.sbt
├── service.json
├── README.md
├── src/main/scala/io/eitheror/casino/attribution/
│   ├── tag/                                # Tag, Signing, Jcs, Pseudonyms
│   ├── networks/                           # Network, NetworkDocuments, Documents, DocumentFetch (HttpClient), DocumentRefresh
│   ├── settings/                           # Settings (the entity), Transition
│   ├── clicks/                             # Click, ClickFacts
│   ├── record/                             # AttributionRecord (events, state, commands), Touches
│   ├── money/                              # WalletCursor, RoundIndex, DayTally, RealMoney, Days, OpenDays, DayCloser
│   ├── facts/                              # Facts, FactIds, RecordFacts, TallyFacts
│   ├── inputs/                             # PlayersFacts, RewardsFacts, PaymentsFacts, WithdrawalsFacts, MoneyFacts
│   ├── carriers/                           # NetworkFeed, FeedRows, FeedAppender, Pages; WebhookSubscription, WebhookFanout, Delivery, Deliveries, Backoff, WebhookHttp
│   ├── views/                              # Records
│   ├── api/                                # TrackEndpoint, AttributionEndpoint, BackofficeEndpoint, WebhooksEndpoint, Wire
│   ├── AttributionCodecs.scala
│   ├── Settings.scala                      # from the environment
│   └── AttributionService.scala            # registration; the timers set at start; firstDay at first start
└── src/test/scala/io/eitheror/casino/attribution/
    ├── tag/, networks/, money/, facts/     # pure
    ├── record/, clicks/, carriers/, settings/, inputs/   # entity, workflow and consumer kits
    ├── it/                                 # Kit, FakeNetwork, WebhookReceiver; LandingSuite, BindingSuite, MoneySuite, GgrSuite, CarriersSuite, NetworksSuite, ClosureSuite, LeakSuite, OperatorSuite
    ├── port/                               # ParitySuite, Translate, CoverageSuite
    └── features/                           # GherkinSuite bindings
tests/original/attribution-driver/          # Go: one scenario per feature scenario the original can show
tests/port/attribution/                     # the recordings (the suite's four, the driver's eight)
.github/workflows/ci.yml                    # + attribution filter and job
justfile                                    # + attribution-test, attribution-it, attribution-run
```

**Structure Decision**: one service directory, as ankka's tooling expects, with a package per capability,
in the shape `services/wallet/` and `services/ingest/` have. The two outbound seams, `DocumentFetch` and
`WebhookHttp`, are traits with an `HttpClient` implementation and the kit's fakes behind `FakeNetwork`
and `WebhookReceiver`. The driver sits beside `ingest-driver/` and reuses its recorder.

## Phase 0: research

Done: [research.md](research.md), R1–R20. No unknown remains. Three items are *verify at task time*, each
with a fallback:

- whether ankka refuses a consumer of a topic the project does not declare (`casino.rewards` without the
  casino module): fallback already designed, `CASINO_MODULES` gates the consumer's registration (R7);
- whether a topic consumer can read the message's `ce-id`: fallback, the wallet cursor dedupes by
  family, transaction id and award id (R10, data-model.md);
- what header the installation's gateway sets for the visitor's country: fallback, `country` absent on
  clicks (R5).

Found at implementation (2026-10-25):

- `casino.rewards`: the fallback is built and tested (StartSuite: `CASINO_MODULES` empty leaves
  `RewardsFacts` unregistered). ankka does not refuse an undeclared topic's consumer: it waits and reads
  the topic once it is declared, and `ankka services get` lists it as an undeclared topic (ankka
  `docs/build/topics.md`, checked in its source 2026-10-28). A missing broker is what is refused at start.
- `ce-id`: not readable. ankka 0.13's `ChangeContext` has the subject and the sequence number only, so
  every dedupe id is the fallback (T057).
- The country header: open until the kind run (T073); `COUNTRY_HEADER` keeps `CF-IPCountry`.

The research also records 6 spec corrections (applied), 11 findings for other specs and 4 questions for
Sheldon.

## Phase 1: design

- [data-model.md](data-model.md): the entities with their commands and events, the workflow's steps,
  the views' declared queries, the consumers' mappings, the timers, the settings, the pure packages.
- [contracts/](contracts/): the protocol a network implements; every route with bodies and refusals;
  every input family to its fact; the recordings to make and the translation that replays them.
- [quickstart.md](quickstart.md): features, contract, unit suites, integration suites, the recording
  run, a local run with a fake network, a kind installation with a machine's grant.

### Constitution re-check after design

Unchanged. The design adds one service with its own database, reading five brand topics and producing to
one, both through consumers; one route called by `players`; two additive contract changes; no new grant
in a manifest (the machine's is made at runtime by an owner). Nothing reads another service's store,
nothing holds a balance, a key leaves no store, and no fact carries a person.

## Divergences (ankka-port, section 6)

| Divergence | Why | Demonstrated by |
|---|---|---|
| The click answers the `clickId` the link carried and mints one only when none came (the original always minted) | FR-006, FR-010; bug 2 | the driver's click recording, marked |
| A click's tag is verified; a refused tag answers `ok: false` with the reason (the original answered `unknown` for a tag no link named) | FR-005, FR-006 | the driver's click recordings; `LandingSuite` |
| A signed token under `btag`; a bare UUID only through the legacy tag document, only in the transition | FR-002, FR-004 | `NetworksSuite`; `TagSuite` |
| An attribution window enforced at registration; a stale tag is organic with the tag kept | FR-008 | `BindingSuite`; no recording differs (the original enforced none) |
| An affiliate's bonus code binds the attribution record, at registration or within the bonus code window after | FR-007; bug 1 | `BindingSuite`; SC-002 |
| The seven `notify/*` routes are not served; every input is a fact on a brand topic | FR-030 | `ParitySuite`'s translation: every recorded notification becomes the fact that now carries it |
| `first-deposit` once in a player's life (the original: once per CPA period) | FR-015 | the driver's third-deposit recording, marked; `MoneySuite` |
| `withdrawal-requested` at the hold and `withdrawal-completed` at completion (the original notified once, at the operator's approval); approved and cancelled emit nothing | FR-030, spec 007 FR-026 | the driver's withdrawal recording, marked |
| `bonus-cost` at the wallet's award, never negative (the original's call always failed `400`) | FR-030, spec 011 FR-028; correction 1 | the driver's bonus-cost recording, marked; `MoneySuite` |
| `chargeback` from a dispute opened or a refund (the original's route was never called) | FR-030; correction 5 | the driver's chargeback recording, marked; `MoneySuite` |
| GGR per player per reporting day, with adjustments, from the wallet's real movements; money converted from restricted funds counts (the original: per round, deposit-origin only, switched off) | FR-016, correction 5, R10 | the suite's three GGR recordings: `ConvertedMoneyRound…` (the original 0, the port 1000) and `SplitRound…` (500, 1000: half its real stake was converted in the same bet) marked and observable, `RestrictedOnlyRound…` unmarked (neither makes a fact); `GgrSuite` |
| `ggr-round` only for a network that opted in | FR-016 | `GgrSuite`; the feature's two scenarios |
| Facts carry a pseudonym and no player id; commission, plans and the postback leave the brand | FR-012, FR-013, FR-021 | `LeakSuite`; `CarriersSuite` |
| Signed webhooks with a day of retries and an undelivered list, replacing the one-shot postback and its macros | FR-021 | `CarriersSuite` |
| A feed and a machine grant, which the original had no equivalent of | FR-019, FR-020 | `CarriersSuite` |
| `player-closed` once, with a category and no reason; erasure through the service route | FR-027, FR-028 | `ClosureSuite`; no recording (the original had no closure notification) |
| Health is the runtime's readiness, not `/health` | ankka runtime | `Parity.divergences` on any recorded `/health` step |

## What the next command needs to know

**Build order:**

1. the six spec corrections are already applied on this branch (spec.md, `facts.feature`, the glossary);
   `just features` passes;
2. the contract: `Routes.machine`, `GgrDaily.beforeBinding`, `just schemas`, `publishLocal`; the manifest,
   `keys.md` and `values.json` changes; `just modules validate --all`;
3. the service skeleton: `ankka init`, `build.sbt` on `casino-contract`, `Settings`, `AttributionCodecs`,
   the `just` recipes, the CI filter and job;
4. the pure packages with their suites: `Tag`, `Signing`, `Jcs`, `Pseudonyms`, `Documents`, `RealMoney`,
   `Days`, `FactIds`, `Facts`, `Backoff`, `Pages`;
5. the entities with kits: `Settings`, `Network`, `NetworkDocuments`, `Click`, `AttributionRecord` (every
   row of its command table), `WalletCursor`, `RoundIndex`, `DayTally`, `NetworkFeed`,
   `WebhookSubscription`; the `Delivery` workflow;
6. the views; the five input consumers and the three publishers with kits; `FeedAppender`, `WebhookFanout`;
   `DayCloser`, `DocumentRefresh`;
7. `FakeNetwork`, `WebhookReceiver` and the integration kit (`InMemoryBroker` on six topics,
   `MovableClock`, the machine-token stub);
8. the click route and `ClickFacts` end to end, fail-first on the driver's click parity test;
9. registration and binding end to end; the pseudonym key route;
10. money, GGR and the day close; closure and erasure;
11. the feed, the brand's keys, the machine ACL; webhooks and the undelivered list; the operator routes;
12. the driver and the recording run (needs the original's KinD stack with the affiliate service);
13. `ParitySuite` with `Translate` over every recording, each marked divergence named; `CoverageSuite`;
14. `LeakSuite`, `GgrSuite`, `CarriersSuite`, `NetworksSuite`, `ClosureSuite`, `OperatorSuite`,
    `GherkinSuite`;
15. a kind deploy of `brands/goldvibe` with `attribution`, a network added, a machine registered and
    granted (quickstart §7).

**Constraints on that order:**

- Parity tests are written before the components they test and must fail first (ankka-port §7); every
  recording waits on step 12, so the parity tests fail until it lands.
- The recording run is against a local original only, never a shared or production one.
- The three verify-at-task-time items are resolved at steps 2 (`ce-id`), 6 (`casino.rewards`) and 15
  (the country header).

**Elsewhere:**

- **Spec corrections** 1 to 6 (research) are on this branch: FR-004, FR-007, FR-023, FR-030, *Changes
  from the original*; `facts.feature`'s outline; the glossary's *bonus cost*.
- **For Sheldon** (research *Open*), answered 2026-10-28: a late code binds forward only; the bonus
  code window is 30 days; a refund is a chargeback with a `/refund` reference; converted money never
  counts in GGR, so the wallet gains deposit-origin and converted pools (spec 025) and `attribution`
  counts the deposit pool's movement, a change to its GGR input once spec 025 is built.
- **For other specs** (research *Findings*): 001 (the digest, the seventh route class), 008 (the tag
  grammar, `player_reopened`, the reporting zone key), 013 (refunds, the double fold, the document
  shapes), 019 (the pseudonym key), 021 (`CASINO_MODULES`, machine grants at runtime).
- **Frontend (out of scope)**: the click proxy's target becomes `attribution`'s hostname; it already
  sends `btag`, `clickId` and `utm_*`; a signed token is opaque to it.
- **For ankka (not blocking)**: the three items under research *For ankka*.
