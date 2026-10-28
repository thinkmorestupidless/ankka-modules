# Tasks: attribution

**Input**: Design documents from `specs/012-affiliate-protocol/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{protocol,routes,facts,recording}.md, quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test (I)
and the living features the scenarios (VII); the port method makes parity tests a deliverable, written
before the component they hold to account and failing first (ankka-port §7). Every story opens with its
tests: the entity, workflow and consumer kits, the integration suites on the kit, the feature bindings,
the SC suites, the parity tests over the recordings.

**Organization**: By user story, in spec.md's order. US1 to US4 are P1 and together are what a programme
needs to pay commission; US1 alone is the MVP (a click recorded, a player bound at registration from a
signed tag, a `registration` fact on the topic). The pure packages, the eleven entities and the
workflow, the views, the consumers' shells, the fakes and the kit are foundational: no story can be
tested before a tag verifies, a record binds and a fact publishes. US2 (codes) and US4 (money) extend
the record's consumers; US3 (carriers) sits beside the topic; US5 and US6 (networks) extend the
documents; US7 (closure) is the last consumer path.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US7 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `a/` stands for `services/attribution/src/main/scala/io/eitheror/casino/attribution/`
- `a-test/` stands for `services/attribution/src/test/scala/io/eitheror/casino/attribution/`
- `contract/` is the shared library `io.eitheror:casino-contract`; `tests/port/attribution/` the
  recordings; `tests/original/` the recording toolchain; `features/attribution/` the living features
- `w/` stands for `services/wallet/src/main/scala/io/eitheror/casino/wallet/` and `g/` for
  `services/game-provider/src/main/scala/io/eitheror/casino/gameprovider/` (patterns copied, never
  changed); `i-test/` for `services/ingest/src/test/scala/io/eitheror/casino/ingest/`
- The service's sbt commands run in `services/attribution/`; Go commands in `tests/`; everything else
  at the root
- `the original` is `~/Documents/projects/eitheror/eitheror-platform` at `659c2d6b`; its affiliate code
  is under `substrate/affiliate-service/src/main/scala/io/eitheror/affiliate/` and
  `substrate/casino-service/src/main/scala/io/eitheror/casino/`
- A recording run is made only against a local original on its KinD stack with the affiliate service
  installed, never a shared or production one

---

## Phase 1: Setup

**Purpose**: the service's build, the two contract additions, the manifest and key map, the recipes and
CI job, and the recording driver (which needs only Go and the original's checkout, not this service).

- [X] T001 Create `services/attribution/` on `services/wallet/`'s template: `build.sbt` on Scala 3.9 with
      ankka 0.13.0 (`ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`, `ankka-telemetry-otlp`,
      `ankka-testkit`), `io.eitheror:casino-contract` from the local publish, munit, the Postgres driver as
      a test dependency (for `LeakSuite`); `project/plugins.sbt`; `service.json` for the local run
      (`ANKKA_HTTP_PORT` 9003); `docker-compose.yml` and `sbt schema` as the wallet has;
      `src/main/resources/application.conf` and `logback.xml` (no body and no header logging: SC-006);
      `Test / testOptions += Tests.Filter(n => !n.contains(".it.") && !n.contains(".port.") &&
      !n.contains(".features."))` so `sbt test` runs the unit suites only (quickstart §3) and
      `attribution-it` names the three packages by `testOnly`;
      the package directories plan.md lists under `a/` (`tag/`, `networks/`, `settings/`, `clicks/`,
      `record/`, `money/`, `facts/`, `inputs/`, `carriers/`, `views/`, `api/`) and `a-test/` (the same
      plus `it/`, `port/`, `features/`); `services/attribution/README.md` naming the service's purpose
      in two lines (filled by T071)
- [X] T002 [P] Add to `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala`
      `machine(authenticate: => Acl = Oidc.authenticate()): Acl`
      (research R14, R18): `Acl.Authenticate` admitting `Caller.Machine(organization, name)` on a route
      a grant names (`Callers.granted`), with the principal `machine:<organization>/<name>` (which
      machine a network admits is the feed handler's check against `Network.machine`, not the ACL's), `Caller.Local`
      admitted for a local run, and everything else `Forbidden` with the new `Refusal.NotMachine`
      (`not_machine`); add `beforeBinding: Boolean = false` to `GgrDaily` in
      `contract/src/main/scala/io/eitheror/casino/contract/facts/AffiliatesAttribution.scala` with the
      scaladoc naming FR-016 and research R10; `just schemas` regenerates
      `contract/schemas/affiliates.attribution.v1.json`; tests
      `contract/src/test/scala/io/eitheror/casino/contract/access/MachineRouteSuite.scala` (a machine
      caller admitted with its principal, a service caller refused, a gateway caller with no token
      refused) and the existing facts suite gaining a `ggr-daily` round-trip with `beforeBinding` true
      and absent; `sbt contract/test contract/publishLocal` green
- [X] T003 [P] Extend `modules/core/module.json`'s `attribution` entry with `env`: `CASINO_REPORTING_ZONE`
      from the new configuration key `reporting-zone` (`string`, not secret), `CASINO_MODULES` from a new
      key `modules` (`string`, not secret, the brand's modules comma-separated), `COUNTRY_HEADER`
      `CF-IPCountry`, `DOCUMENT_TIMEOUT` `10s`, `CLOSE_GRACE` `15m`, `WEBHOOK_TIMEOUT` `10s`; add
      `reporting-zone` and `modules` to `contract/keys.md`'s core table (derived: `UTC` when the brand's
      values leave the zone out; the modules from the brand's entitlement in local mode) and move
      `attribution | core | 012` out of "Not yet built"; add `"reporting-zone": "Africa/Johannesburg"`
      to `brands/goldvibe/values.json`; have `tools/modules` derive both defaults
      (`tools/modules/src/main/scala/io/eitheror/casino/modules/Values.scala`, beside `keycloak-jwks-base`) with
      a test in its `RenderSuite`; `just modules validate --all` and `just test` green
- [X] T004 [P] Add to `justfile` the recipes `attribution-test` (`sbt contract/publishLocal` then `sbt
      test` in `services/attribution`, as `wallet-test`), `attribution-it` (`testOnly
      io.eitheror.casino.attribution.it.* io.eitheror.casino.attribution.port.*
      io.eitheror.casino.attribution.features.*`), `attribution-run` (`ANKKA_HTTP_PORT=9003`,
      `ANKKA_DB_PORT=5435`, `CASINO_BRAND=goldvibe`, `CASINO_PROJECT=goldvibe`, `CASINO_REAL_MONEY=false`,
      `CASINO_REPORTING_ZONE=Africa/Johannesburg`, the staff issuer variables as `ingest-run` has them,
      `sbt run`); add an `attribution` filter (`services/attribution/**`, `features/attribution/**`,
      `tests/port/attribution/**`, `tests/original/attribution-driver/**`, `*build`) and job to
      `.github/workflows/ci.yml` after the `ingest` job's pattern
- [X] T005 [P] Write `tests/original/attribution-driver/` (Go, as `tests/original/ingest-driver/`: `doc.go`,
      `driver.go`, `scenarios.go`, `record_test.go` for the `e2e` run, `driver_test.go` offline), driving
      the original's `affiliate-service` (`-affiliate-url`, KinD `localhost:8082`) and `casino-service`
      (`-casino-url`): one scenario per row of contracts/recording.md's driver table, named as the
      feature names it; each seeds its own commission plan, affiliate account and `affiliate-links`
      twins through the substrate as `scenario17PostSubstrate` does (`tests/e2e/scenario17_affiliate_cpa_e2e_test.go`
      lines 160–210 of the original), the seeded `btag` UUID kept in the recording as the legacy tag;
      the click with a `clickId`, `deviceType` and `utm*`; the click with no `btag`; `notify/registration`
      with the tag then the registration twin read; two registrations with two tags; `notify/deposit`
      twice then a third dated a month later, with the revenue twin read after each;
      `notify/withdrawal` then `notify/withdrawal-reversal`; `notify/bonus-cost` with `claimId` as
      `AffiliateServiceClient.notifyBonusCost` sends it (the `400` is the recording); `notify/chargeback`;
      `vary` on every minted id and timestamp; `X-Internal-Secret` from `-internal-secret`, never kept
      (the recorder's `kept()` list); extend `tests/original/preflight` with `-affiliate` checking the
      affiliate service answers `/health`; `go test ./original/attribution-driver/...` green offline
      (the names against `features/attribution/*.feature`, the recorded shape against a stub)
- [X] T006 [P] Add `tests/port/attribution/README.md` naming what the directory will hold
      (contracts/recording.md: the suite's four affiliate scenarios recorded with the affiliate service
      installed and `affiliate_notify_ggr` on, the driver's eight), how the suite's four are run (the
      original's `just test-st8` and scenario 17 with `EITHEROR_AFFILIATE_URL` set, twice, on fresh ids,
      merged with `tests/original/merge`), and that the translation `ParitySuite` applies is
      `a-test/port/Translate.scala`; add the `attribution` section to `tests/port/README.md`; add the
      four suite scenario names to `tests/port/unclaimed.txt`'s header note as claimed by spec 012 once
      recorded

---

## Phase 2: Foundational

**Purpose**: what every story needs: the pure packages with their suites, the entities and the workflow
with their kits, the views, the consumers' shells, the fakes and the kit, the parity and feature suites
listing their tests, the service and its endpoint shells.

**⚠️ CRITICAL**: no story starts before this phase is done. T035 (the recording run) gates every parity
task and can run the moment T005 and the original's stack are up.

### Pure packages (tests beside each)

- [X] T007 [P] Implement `a/Settings.scala` (data-model.md *Settings*: `CASINO_REPORTING_ZONE` `UTC`,
      `CASINO_MODULES` empty, `COUNTRY_HEADER` `CF-IPCountry`, `DOCUMENT_TIMEOUT` `10s`, `CLOSE_GRACE`
      `15m`, `WEBHOOK_TIMEOUT` `10s`; `fromEnvironment` refusing a value it cannot read by the
      variable's name, a zone `ZoneId.of` refuses named; `Routes.brandFromEnvironment` for the brand)
      and `a/AttributionCodecs.scala` (codecs for every stored type of data-model.md: `Binding`,
      `TagOutcome`, `Touch`, `MoneyEvent`, the entities' states and events, the workflow's state,
      `Long` minor units as a JSON number, `Instant` as ISO-8601, after `WalletCodecs`); tests
      `a-test/SettingsSuite.scala` and `a-test/AttributionCodecsSuite.scala` (every type round-trips;
      a `Binding` with no campaign and five sub-ids survives)
- [X] T008 [P] Implement `a/tag/Signing.scala` (Ed25519 through `java.security`: `generate(): (publicKey
      base64url, privateKey base64url)`, `sign(privateKey, bytes)`, `verify(publicKey, bytes, signature)`,
      `publicKeyFromX(x)` for a key document's `x`) and `a/tag/Jcs.scala` (RFC 8785 canonical form of a
      `JsonNode`: sorted members, no whitespace, numbers and strings as the RFC says); tests
      `a-test/tag/SigningSuite.scala` (a generated pair signs and verifies; a flipped byte fails; a key
      from `x` verifies what its private half signed) and `a-test/tag/JcsSuite.scala` (the RFC's
      examples; member order does not change the output; a nested array of facts)
- [X] T009 [P] Implement `a/tag/Tag.scala` (contracts/protocol.md and research R2, R6): `parse(value):
      Either[malformed, Parsed(header kid, payload fields, signingInput, signature)]` enforcing the 1,024
      bound, the character set, three segments, `alg` `EdDSA`, `typ` `eo-tag`, `v` 1, the field
      lengths, at most five sub-ids; `isLegacy(value)` the UUID pattern; `verify(parsed, keys: Map[kid,
      publicKey], revocations, networkKnown, removed, at: Option[(registeredAt, window)]): TagOutcome`
      in R6's order with its reasons (`unknown_network`, `withdrawn_key`, `bad_signature`,
      `revoked_link`, `revoked_affiliate`, `stale`); `resolveLegacy(uuid, legacyDocument, programme):
      TagOutcome` (`legacy_unknown` when unlisted); `mint(fields, kid, privateKey)` for the kit and the
      fake network; tests `a-test/tag/TagSuite.scala`: one test per reason, a token of 1,025 characters,
      a token with a `+`, a header naming `HS256`, six sub-ids, a campaign of 65 characters, a UUID in
      upper case recognised as legacy, a valid token's fields read back exactly, a tag at the window's
      edge (issued exactly 30 days before registration binds; one second older is `stale`)
- [X] T010 [P] Implement `a/tag/Pseudonyms.scala` (`derive(key: Array[Byte], playerId): String`,
      HMAC-SHA256, base64url no padding; `newKey(): String` 32 random bytes base64; `secretName(network)`
      `network/<id>/pseudonym-key`); tests `a-test/tag/PseudonymsSuite.scala`: a fixed key and
      `player-1` give the digest written into the test as the documented vector; two networks' keys
      give different pseudonyms for one player; 43 characters; the same twice
- [X] T011 [P] Implement `a/networks/Documents.scala` (parse the four documents of contracts/protocol.md
      into `KeyDocument(keys: Map[kid, publicKey], cacheSeconds, transition: Option)`,
      `RevocationList(links, affiliates, refreshSeconds, asOf)`, `LegacyTagDocument(tags, version,
      cacheSeconds)`, `CodeDocument(brand, version, at, codes: Map[lowercased code, (offer, tag)],
      cacheSeconds)`; a document over 1 MiB, not JSON, missing its cache field or with a key whose `crv`
      is not `Ed25519` refused naming why) and `a/networks/DocumentFetch.scala` (a trait `fetch(url):
      Either[error, (status, body)]` with `DocumentFetch.http(timeout)` over `java.net.http.HttpClient`
      after `services/psp-gateway/.../http/ProviderHttp.scala`: 5 s connect, the timeout per request, no
      redirects, https or http, the body read to at most 1 MiB); tests `a-test/networks/DocumentsSuite.scala`
      (each document's example from protocol.md parses; each refusal; codes matched regardless of case)
- [X] T012 [P] Implement `a/money/RealMoney.scala` (`counted(fact: MoneyTransactionsFact, delta: Long):
      Option[Leg(kind, roundId, dedupeId)]`: `bet_placed` → `stake` with `−delta`, `win_credited` and a
      `provider_credited` landing on `real` → `win` with `delta`, `bet_rolled_back` → `rollback` with
      `delta`, `round_settled_as_loss` → `loss` with 0, every other family `None`; `bonusCost(fact):
      Option[(amount, reference)]` for `restricted_funds_awarded` and a `provider_credited` landing on
      `restricted`; `dedupeId(fact, metadata)` the message's `ce-id` when present, else
      `<family>/<transactionId>/<awardId or empty>`) and `a/money/Days.scala` (`dayOf(instant, zone):
      String` ISO date; `closeDue(day, zone, grace, now): Boolean`; `nextClose(now, zone, grace)`;
      `endOfTransition(firstDay, lastLegacyDay, today): Option[(day, endedBy)]` with R16's two rules);
      tests `a-test/money/RealMoneySuite.scala` (every family of `MoneyTransactions.families` against the
      table of contracts/facts.md; a split bet's real part from the balance difference; a restricted
      award's cost) and `a-test/money/DaysSuite.scala` (23:30 in `Africa/Johannesburg` is that day and
      21:30 UTC is the next; a day closes 15 minutes after its midnight and not before; 12 months from
      `2026-11-01` is `2027-11-01`; 90 days after the last legacy day; the earlier of the two wins)
- [X] T013 [P] Implement `a/facts/FactIds.scala` (research R13's forms, one function per kind) and
      `a/facts/Facts.scala` (`Facts.fromRecord(brand, state, event): Vector[Published(fact, factId, key)]`
      and `Facts.fromTally(brand, binding, event, roundStream): Vector[Published]` and
      `Facts.click(brand, click)`: the contract's families filled as contracts/facts.md says, `occurredAt`
      the input's, the key the pseudonym or the link, `ggr = stake − win`, `beforeBinding` as given;
      nothing for an unbound record; nothing for a money event before `boundAt`); tests
      `a-test/facts/FactIdsSuite.scala` (every id form; no player id in any) and
      `a-test/facts/FactsSuite.scala`: one test per row of contracts/facts.md's table, the same
      event twice gives equal facts and ids, a `Bound` event with pending events emits each at or
      after `boundAt` and none before, a `Closed` tally with no movement emits nothing, a settling leg
      with `roundStream` off emits nothing and with it on emits the deltas since the last emission
- [X] T014 [P] Implement `a/carriers/Backoff.scala` (research R15's schedule: `next(attempts): Duration`
      1, 5, 15, 60 minutes then hourly; `exhausted(firstAttemptAt, now): Boolean` at 24 hours) and
      `a/carriers/Pages.scala` (`Cursor.encode(network, seq)`, `decode(text): Either[invalid, (network,
      seq)]`, the empty cursor as `(network, 0)`; `Pages.assemble(facts, network, lastSeq, kid,
      privateKey): JsonNode` signing `Jcs` of the `facts` array; `Pages.verify(page, publicKey)` for the
      kit); tests `a-test/carriers/BackoffSuite.scala` (the sequence; exhausted at 24 h and not at
      23 h 59 m) and `a-test/carriers/PagesSuite.scala` (a page verifies under its key; a reordered fact
      fails; a cursor round-trips; another network's cursor refused; the empty page returns the given
      cursor)

### The entities and the workflow (tests first)

- [X] T015 [P] `a-test/settings/SettingsEntitySuite.scala` (`KeyValueEntityTestKit`): every command of
      data-model.md's `Settings` (`setWindows` refuses 0 and 366 days; `start` sets `firstDay` once;
      `legacySeen` counts per day and sets `lastLegacyDay`; `endTransition` once; `addSigningKey`,
      `retireSigningKey` refusing the last live key; `get`), then implement
      `a/settings/SettingsEntity.scala` (key value, `ComponentId("settings")`, id `brand`, after
      `w/config/BrandConfigEntity.scala`) until green
- [X] T016 [P] `a-test/networks/NetworkSuite.scala` and `a-test/networks/NetworkDocumentsSuite.scala`
      (`add` refused on an existing live network, allowed after `remove`; `update`, `setRoundStream`,
      `setMachine`, `pseudonymKeyMade`, `remove` keeps everything; a network id of 65 characters or
      with a capital refused; `NetworkDocuments.put(kind, parsed, fetchedAt, expiresAt)`, `failed(kind,
      error, at)` keeps the old body, `fresh(kind, now)`), then implement `a/networks/Network.scala`
      and `a/networks/NetworkDocuments.scala` (key value, ids the network id) until green
- [X] T017 [P] `a-test/clicks/ClickSuite.scala` (`record` sets every field; a second `record` under
      the id changes nothing and replies the first; `refused` kept; `expireAfter` 60 days declared),
      then implement `a/clicks/Click.scala` (key value, `ComponentId("click")`) until green
- [X] T018 [P] `a-test/record/AttributionRecordSuite.scala` (`EventSourcedEntityTestKit`): every row of
      data-model.md's `AttributionRecord` command table: `registered` with a valid tag emits `Registered`
      and `Bound` with touch `tag`; with a refused tag `TagRefused` and the player organic; with a tag
      and a code, `Bound` by the tag and `TouchRecorded` for the code; a second `registered` a no-op;
      `bind` on a bound record `AlreadyBound`; `touch` keeps the last 100; `money(deposit)` while
      unbound → `PendingRecorded` and `firstDepositAt` set; `money(deposit)` when bound → `DepositRecorded(first
      = true)` then `false`; a seen `dedupeId` a no-op; `Bound` after pending deposits carries those at
      or after `boundAt` only; pending pruned past 200 and 48 hours; a chargeback for a disputed payment
      with the same reference a no-op; `close` once; `erase` emits `Erased` and `Closed(closed)` when
      none was; `get`; then implement `a/record/AttributionRecord.scala` (events, state, commands,
      `ComponentId("attribution-record")`, snapshots every 100 events) and `a/record/Touches.scala` until green
- [X] T019 [P] `a-test/money/WalletCursorSuite.scala`, `a-test/money/RoundIndexSuite.scala`,
      `a-test/money/DayTallySuite.scala` (`advance` from 0: a deposit of 5000 → `Delta(5000)`; a bet →
      `Delta(−1000)`; the same dedupe id → `Seen`; `recent` bounded at 200; `remember` the first day
      wins, `expireAfter` 7 days declared; `leg(stake)` adds, `leg(win)` adds, `leg(rollback)` reduces
      stake, `leg(loss)` adds nothing but marks the round settled, a seen id a no-op; `close` once with
      the totals in `Closed`; a `leg` after `close` → `Adjusted(delta)` and `adjustments + 1`; a settling
      leg's event carries the round's unemitted totals and marks them emitted), then implement
      `a/money/WalletCursor.scala`, `a/money/RoundIndex.scala` (key value) and `a/money/DayTally.scala`
      (event sourced, `ComponentId("day-tally")`) until green
- [X] T020 [P] `a-test/carriers/NetworkFeedSuite.scala` (`append` → `Appended(1, fact)`, `Appended(2,
      …)`; the same fact id → `Seen`; `recent` bounded at 10,000) and
      `a-test/carriers/WebhookSubscriptionSuite.scala` (`create` with an `http://` address refused;
      `update`; `delete`; `get` never holds a secret), then implement `a/carriers/NetworkFeed.scala`
      (event sourced) and `a/carriers/WebhookSubscription.scala` (key value) until green
- [X] T021 [P] `a-test/carriers/DeliverySuite.scala` (`WorkflowTestKit` with a stubbed `WebhookHttp`):
      `start` posts the fact signed under the kit's secret with the four headers of
      contracts/protocol.md and ends `delivered` on `200`; a `503` pauses 1 minute, then 5, 15, 60,
      60; after 24 hours from the first attempt it ends `undelivered` with the last status; `resend`
      from `undelivered` posts again; `discard` ends `discarded`; `start` on an existing workflow a
      no-op; a thrown error is an attempt, not a failure (`RecoverStrategy`); the step reads the secret
      through `context.secrets` and the state never holds it), then implement
      `a/carriers/Delivery.scala` (workflow, `ComponentId("delivery")`, id `<subscription>/<factId>`,
      after `w/deposits/DepositWorkflow.scala`'s shape) and `a/carriers/WebhookHttp.scala` (a trait
      `post(address, body, headers): Either[error, status]` with an `HttpClient` implementation,
      `WEBHOOK_TIMEOUT`, no redirects) until green
      *Done note*: ankka 0.13 has no Scala `WorkflowTestKit` (build/workflows.md: a Scala workflow is tested through
      the integration kit), so the decisions live in a pure `DeliveryRules` that `DeliverySuite` holds, and the
      workflow's run over a stub post is in the integration suite (T030 onward).

### The views, the consumers' shells, the timers

- [X] T022 [P] Implement `a/views/Records.scala` (over `eventsOf(AttributionRecord)`: one row per
      player with `playerId`, `network`, `affiliate`, `bound`, `touches`, `firstDepositAt`, `closed`,
      `erased`; declared queries `byPlayer(id)` and `countsByNetwork`), `a/money/OpenDays.scala` (over
      `eventsOf(DayTally)`: one row per open tally with `player`, `currency`, `day`, deleted on
      `Closed`; `openBefore(day)`; and `closedFor(player)` over a second row set of closed days),
      `a/carriers/FeedRows.scala` (over `eventsOf(NetworkFeed)`: `network`, `seq`, `factId`, `kind`,
      `fact`; `page(network, afterSeq, limit)` ordered by `seq`) and `a/carriers/Deliveries.scala`
      (over `stateOf(Delivery)`: `undelivered(network)`); tests `a-test/views/ViewsSuite.scala` on
      `AnkkaTestKit` (each query after the events that fill it; `page` returns 1,000 at most and in
      order; a closed day leaves `openBefore`)
      *Done note*: Scala views run only on the integration kit, so the suite is `a-test/it/ViewsSuite.scala` (own
      JVM). `FeedRows` is a keyed view, one row per fact keyed `<network>/<seq>`, since a plain view keeps one
      row per source id; `OpenDays` keeps closed rows marked rather than deleted, for `closedFor`; a declared
      query answers rows, so `countsByNetwork` counts through the view client. The pure delivery rules are
      `DeliveryRules`, the view `Deliveries`.
- [X] T023 [P] `a-test/inputs/InputsSuite.scala` (`ConsumerTestKit` with an `InMemorySecretStore` holding
      `network/programme/pseudonym-key` and stubbed clients): the shells of the five input consumers
      answer `ignore` for every family they do not handle and call nothing; then implement the shells
      `a/inputs/PlayersFacts.scala`, `a/inputs/RewardsFacts.scala`, `a/inputs/PaymentsFacts.scala`,
      `a/inputs/WithdrawalsFacts.scala`, `a/inputs/MoneyFacts.scala` (each
      `ChangeSource.fromTopic(<Topic>.name, LenientFacts.serializer, StartFrom.Earliest,
      TopicOptions(Some(<Topic>.contract), None, false))` as `g/` consumes `casino.players`;
      `RewardsFacts` registered only when `CASINO_MODULES` names `casino`) with the handled families
      left as `???`-free `ignore` stubs the stories fill
- [X] T024 [P] `a-test/facts/PublishersSuite.scala` (`ConsumerTestKit`): `ClickFacts` over a `Click`
      state with no `refused` publishes one `click` keyed by the link with `ce-id` `click/<id>`, and
      nothing for a refused click; `RecordFacts` over a `Bound` event publishes `registration` keyed by
      the pseudonym, `ce-id` `registration/<pseudonym>`, the same twice; `TallyFacts` over a `Closed`
      event reads the record through the stubbed client and publishes `ggr-daily`, nothing for an
      unbound player; then implement `a/clicks/ClickFacts.scala`, `a/facts/RecordFacts.scala`,
      `a/facts/TallyFacts.scala` (after `w/publish/Publishers.scala`: `outputSerializer`
      `AffiliatesAttribution.serializer`, `produceTo`, `produces` with the contract, `withKey`,
      `Metadata` `ce-id`) until green
- [X] T025 [P] `a-test/carriers/CarrierConsumersSuite.scala` (`ConsumerTestKit`): `FeedAppender` on an
      `affiliates.attribution` fact calls `NetworkFeed(fact.network).append`; `WebhookFanout` on an
      `Appended` event starts one `Delivery` per active subscription of the network and none for an
      inactive one; then implement `a/carriers/FeedAppender.scala` (a consumer of the brand's own
      topic, `StartFrom.Earliest`, with the contract) and `a/carriers/WebhookFanout.scala` (over
      `eventsOf(NetworkFeed)`, the subscriptions read through a `Subscriptions` view added to
      `a/carriers/WebhookSubscription.scala`'s file: `activeFor(network)`) until green
- [X] T026 [P] `a-test/money/DayCloserSuite.scala` and `a-test/networks/DocumentRefreshSuite.scala`
      (the timed actions called directly with stubbed clients and a `FakeFetch`): `DayCloser.run` closes
      every row `openBefore` returns whose day is due by `Days.closeDue`, leaves the rest, ends the
      transition when `Days.endOfTransition` says, prunes `legacyDaily`, and is `done` with nothing to
      do; `DocumentRefresh.run` fetches each network's expired documents, writes them, records a
      failure and keeps the old body; then implement `a/money/DayCloser.scala` and
      `a/networks/DocumentRefresh.scala` (`TimedAction`s with `handler("close-days")` and
      `handler("refresh-documents")`, `effects.done()` always, the clock from the context) until green
      *Done note*: the runs are `DayClosing` and `DocumentRefresher`, which the suites call with a recording client;
      the timed actions wrap them. The refresh lists networks from a `Networks` view (`a/networks/Network.scala`),
      read through `Wiring`, as consumers and timed actions have no view client.

### The fakes, the kit, the suites that list their tests, the shells

- [X] T027 [P] Write `a-test/it/FakeNetwork.scala`: an in-process HTTP server (as
      `services/game-provider/src/test/.../it/St8Stub.scala` is built) serving `/keys`,
      `/revocations`, `/legacy`, `/codes/{brand}` from mutable documents the test sets, `/tag?aff=&lnk=&cmp=&sub=&iat=&kid=`
      minting a token under one of its keys, a request log, a `fail(kind)` switch, and `rotate()` that
      adds a key and withdraws the oldest; and `a-test/it/WebhookReceiver.scala`: a server recording
      every post with its headers and body, answering what the test scripted (`200`, `503`, no answer),
      and verifying each signature under the secret the test gave it
- [X] T028 Implement `a/AttributionService.scala` after `w/WalletService.scala`: `descriptors(settings)`
      registering every entity, the workflow, the views, the consumers (`RewardsFacts` conditionally),
      the publishers, the carrier consumers and the two timed actions; `endpoints(settings)` the four
      of contracts/routes.md; `@main def run()` with `ProjectionRuntime.fromEnv()`, `TimerRuntime`, and
      at start `Settings(brand).start(today)`, the recurring timers `close-days` (every 5 minutes) and
      `refresh-documents` (every minute) set each start, and `click-salt` made in the store when absent
      (from the endpoint's store, in a start hook as `g/GameProviderService.scala` wires its refresh)
- [X] T029 Implement the endpoint shells `a/api/TrackEndpoint.scala` (`HttpEndpoint("/v1/track")`,
      `Routes.public`), `a/api/AttributionEndpoint.scala` (`/v1/casino/brands/<brand>/attribution`,
      `withAcl` per group: `public` for `/keys`, `Routes.machine()` for the feed, `Routes.service()`
      for the erasure), `a/api/BackofficeEndpoint.scala` and `a/api/WebhooksEndpoint.scala`
      (`Routes.operator(brand)`), every route of contracts/routes.md declared and answering `501
      not_built` with its name, and `a/api/Wire.scala` (refusals as `HttpProblem` with a code and
      `field`; the request and answer shapes of routes.md as case classes with codecs); `a-test/api/WireSuite.scala`
      (every shape round-trips; a refusal's body has `code` and `message`)
- [X] T030 Write `a-test/it/Kit.scala` after `services/game-provider/src/test/.../it/Kit.scala`:
      `AnkkaTestKit.start(AttributionService.descriptors(settings), Seq(ProjectionRuntime.withBroker(broker,
      broker), timers, HttpServer.at("127.0.0.1", 0)(AttributionService.endpoints(settings)*)))` with
      `InMemoryBroker` carrying the six topics, `TimerRuntime(200.millis, clock = MovableClock)`,
      `DocumentFetch` and `WebhookHttp` pointed at `FakeNetwork` and `WebhookReceiver`, a
      `Settings` with the zone `Africa/Johannesburg` and `CASINO_MODULES` `casino`; the staff stub
      `staff|<id>|brand_goldvibe` and a machine stub `machine|<org>|<name>` read as
      `Caller.Machine`; helpers `publish(topic, fact)` on the broker, `factsOn(network)` reading the
      brand's topic, `addNetwork`, `makePseudonymKey`, `bindPlayer(tag)`, `moveClock(to)`,
      `request(method, path, body, headers)`, `asOperator`, `asMachine(org, name)`, `reset()`
- [X] T031 [P] Write `a-test/port/Translate.scala` (contracts/recording.md's translation: a recorded
      step to one of `Replay(request)`, `Publish(topic fact)`, `Expect(kind, pseudonymOf(playerId),
      amount, reference)`, `Seed(legacy tag, affiliate, link)`; major-unit strings to minor units by the
      currency's exponent, exactly; the daily expectation summed from recorded `ggr` strings by the
      recorded `occurredAt` in the kit's zone) with `a-test/port/TranslateSuite.scala` (each kind of
      step from a hand-written recording fragment; `"12.34"` EUR → 1234; a sum of three rounds on one
      day); `a-test/port/ParitySuite.scala` after `i-test/port/ParitySuite.scala`: one test per file
      under `tests/port/attribution/`, replayed through `Translate` on the kit, the marked divergences
      from `a-test/port/Parity.scala` (`divergences`: the click id, the third deposit, the withdrawal
      at the hold, the bonus cost, the chargeback, the converted money); and
      `a-test/port/CoverageSuite.scala` after `i-test/port/CoverageSuite.scala` checking every marked
      divergence is a row of plan.md's *Divergences*. The suite lists its tests from the directory and
      fails for each until T035 lands
- [X] T032 [P] Write `a-test/features/GherkinSuite.scala` after `i-test/features/` binding the six
      `features/attribution/*.feature` through the kit's helpers, every step `pending` until its story
      binds it; the suite fails naming the unbound steps
- [X] T033 `a-test/it/StartSuite.scala`: the kit starts; `Settings` `brand` has today's `firstDay`;
      both recurring timers exist (`TimerProbe`); `click-salt` is in the store and in no table; every
      route of routes.md answers `501` or its ACL's refusal (a public route without a token `501`, an
      operator route without a token `401`, the feed under a service caller `403`, the erasure under
      an operator `403`); `CASINO_MODULES` empty leaves `RewardsFacts` unregistered (a second kit)
- [X] T034 `a-test/it/LeakSuite.scala` listing its checks (filled by T067): the markers it will send
      (a name, an email, a raw address, a fingerprint, a date of birth), the places it will search
      (every table, every fact on the broker, every feed page, every webhook body, every operator
      answer, the captured log), each `pending`
- [X] T035 Make the recordings (needs the original's KinD stack with `affiliate-service` installed and
      `affiliate_notify_ggr` on; T005; Docker): `tests/original/README.md`'s steps with the
      `preflight -affiliate` check; the four suite scenarios of contracts/recording.md run twice on fresh
      ids with `EITHEROR_AFFILIATE_URL` set; the driver's run twice; `go run ./original/merge … -out
      port`; `just modules recordings check` 0 problems; every file `pass` on the original both runs;
      `tests/port/attribution/README.md` records the date, the commit and the stack. Then T031's
      `ParitySuite` lists twelve failing tests, no more and no fewer. Never against a shared original
      *Done 2026-10-28:* on the original's KinD stack at `a78decf1` (spec 008's recording worktree), the
      four suite scenarios and the driver's eight each `pass` on both runs; `recordings check` 239, 0
      problems; `tests/port/attribution/README.md` has the run. Scenario 17 also read its revenue twin under
      a namespace the service never writes (`affiliate-revenue/<a>/<b>` for `affiliate-revenue-<a>-<b>`),
      corrected in the local checkout. The affiliate service answers on KinD's host port 8093; the driver's
      `doc.go`, quickstart §5 and contracts/recording.md said a forward to 8082, which is
      `substrate-graphql`, and now say 8093. The recordings came after the implementation, so the replay
      did not fail first as this task expected: it found three faults of the harness, not of the port. A
      placeholder filled as `name-<run>` made the bare-UUID tag `malformed` (now a fresh UUID); a capture
      never reached a later step, as the whole recording was translated before the replay (now step by
      step, the port's answer captured); the kit's clock, behind the recorded registration, dated the
      replayed deposit before the binding (the replay now moves it past the recording's latest instant).
      Scenario 17's link seed in the brandless tag index is now a seed too. Scenario 15's three then
      replayed green while asserting nothing: the original's GGR notification is internal to its cluster,
      so no `notify/ggr` was recorded. The translation now takes the settled round twin as the wallet's real
      legs and the affiliate facts read as the day's expected GGR (or none), and closes the day; recorded
      answers' run-1 ids map to this replay's through the captures; each replay's instants move onto a
      day of its own. `ParitySuite` 13 of 13, `TranslateSuite` 11 (one new) and `CoverageSuite` pass

---

## Phase 3: User Story 1 — A visitor arrives on an affiliate's link and registers (P1) 🎯 MVP

**Goal**: the click route records one click per click id and emits a `click` fact; a `player_registered`
with a valid, in-window tag binds the player once, with the pseudonym, and emits `registration`; a stale,
refused or revoked tag is organic with its reason; a later tag is a touch.

**Independent Test**: quickstart §4's `LandingSuite` and `BindingSuite`'s tag cases; `landing.feature`
bound; the driver's click and registration recordings.

### Tests for User Story 1

- [X] T036 [P] [US1] `a-test/it/LandingSuite.scala` on the kit: a `POST /v1/track/click` with a tag
      `FakeNetwork` minted, `clickId` `c-1`, `deviceType` `mobile`, `country` `ZA`, the five `utm_*`
      and `X-Forwarded-For` → `200 {"ok":true,"clickId":"c-1"}`, `Click` `c-1` holding every field,
      `addressDigest` set and not equal to the address, and one `click` fact on the topic keyed by the
      link with `ce-id` `click/c-1`, `country` `ZA`, `deviceType` `mobile`; the same request again →
      the same answer and no second fact; no `clickId` → a UUID answered and used as the fact's id;
      no `btag` → `400`; a tag of `nonsense` → `200 {"ok":false,…,"reason":"malformed"}` and no fact;
      `deviceType` absent with an iPhone user agent → `mobile`; `country` absent and the
      `CF-IPCountry` header `GB` → `GB`; neither → absent; no answer carries `Set-Cookie`, and a
      request with no consent header or cookie is recorded like any other (FR-006)
- [X] T037 [P] [US1] `a-test/it/BindingSuite.scala` (tag cases): a `player_registered` with a tag
      minted 1 day ago binds: `Records.byPlayer` bound to the network, affiliate, link, campaign,
      sub-ids and `clickId`, and one `registration` fact keyed by the pseudonym with `ce-id`
      `registration/<pseudonym>`, `country` the fact's; the same fact delivered again → no second
      fact; a tag minted 31 days ago → organic, `refused` `stale` with the tag kept, no fact; a tag
      with a bad signature, an unknown network, a withdrawn `kid` (after `rotate()` and the cache's
      refresh), a revoked link, a revoked affiliate → organic with each reason; a network with no
      pseudonym key → organic `network_not_ready`; a tag naming a `kid` the cache lacks, with the key
      document cached → organic `withdrawn_key` and `FakeNetwork`'s request log unchanged (FR-023:
      registration never fetches); with nothing cached → `key_unreachable`, the log unchanged; a bound player's second `player_registered` with
      another tag → nothing; a tag for a bound player sent as a `bonus_code_entered`'s sibling touch
      path is US2's; the pseudonym differs under a second network's key for the same player
- [X] T038 [P] [US1] Bind `landing.feature`'s six scenarios (the outline's three rows) in
      `a-test/features/LandingFeatures.scala` over the kit's helpers: "one click per browser session"
      reads the `Click` entity and the topic; "later touch" reads `Records.byPlayer`'s touches
- [X] T039 [US1] Confirm `ParitySuite` lists the driver's two click recordings and the registration
      and later-tag recordings as failing; name in `a-test/port/Parity.scala` the divergence that
      holds on the click (the port answers the `clickId` sent; the original a minted UUID). Needs T035
      *Done 2026-10-28:* the four replay green. The click's divergence is named, but not `observable`: the
      original's click id varied between its two runs, so the recording holds it for presence only, and
      the port's own id passes. The refused-tag divergence is reached by every click and shows nowhere, as
      the driver's tags are valid

### Implementation for User Story 1

- [X] T040 [US1] Fill `a/api/TrackEndpoint.scala`'s `POST /click` (research R5): the body parsed
      (`btag` required → `400 {"error":"btag is required"}`), the click id from the body or minted, the
      country from the body or `COUNTRY_HEADER`, the device type from the body or the user agent, the
      digest from the first `X-Forwarded-For` entry under `click-salt` read from the endpoint's store
      once and cached, `Tag.parse` and `Tag.verify` over `NetworkDocuments` and `Network` (with an
      on-demand key document fetch through `DocumentFetch` when the `kid` is unknown, written to the
      cache), `Click(id).record(...)`, the answer of routes.md; nothing logged but the click id and
      the outcome
- [X] T041 [US1] Fill `a/inputs/PlayersFacts.scala`'s `player_registered` (research R6): `Tag.parse`,
      the legacy rule through the programme's cached legacy document (`Settings.legacySeen` on a legacy
      tag), `Tag.verify` with the window from `Settings`, the pseudonym derived under the network's key
      from the store (cached per process), `AttributionRecord(playerId).registered(...)`; a bonus code
      beside the tag recorded as a touch (its resolution is US2's: pass the raw code as a `code` touch
      with outcome `unresolved` here, replaced by T047); `RecordFacts`'s `Bound` → `registration`
      already publishes (T024); T036–T038 green; T039's recordings replay with only the named divergence
      *Done:* the tag's verdict is shared by the click and the registration (`a/tag/TagCheck.scala`);
      the registration is `a/inputs/Registrar.scala`. A repeat `player_registered` carrying a tag the
      record has not seen records it as a later touch (FR-009, landing.feature's later-touch scenario); a
      redelivered fact still records nothing. T039's replay waits on T035.

**Checkpoint**: a click is recorded once per id and emitted; a tag within the window binds once and
emits `registration` with the pseudonym; every refusal of R6 leaves the player organic with its reason;
`landing.feature` passes; the driver's click and registration recordings replay.

---

## Phase 4: User Story 2 — A player registers with an affiliate's bonus code (P1)

**Goal**: an affiliate's code, told from the brand's own by the cached code document, binds at
registration as a tag would, or within the bonus code window after an organic registration; a code for
a bound player is a touch and changes nothing.

**Independent Test**: `BindingSuite`'s code cases; `bonus-codes.feature` bound; SC-002 by the
`MoneySuite` case of US4 (every later fact attributed).

### Tests for User Story 2

- [X] T042 [P] [US2] `a-test/it/BindingSuite.scala` (code cases): `FakeNetwork`'s code document for
      `goldvibe` lists `SUMMER50` with a tag; a `player_registered` with `bonusCode` `summer50` and no
      tag → bound with touch `registration_code`, no click id, one `registration` fact; with a tag and
      the code → bound by the tag, the code a touch; a code the document does not list → organic with
      a `code` touch `unknown`; a `bonus_code_entered` 10 days after an organic registration → bound
      with touch `later_code`, `registration` dated to the entry; 31 days after → a touch, organic;
      a `bonus_code_entered` for a bound player → a touch and no change; with `CASINO_MODULES` empty
      (a second kit) the `bonus_code_entered` is never read
- [X] T043 [P] [US2] Bind `bonus-codes.feature`'s three scenarios in
      `a-test/features/BonusCodesFeatures.scala`; "the code's reward applies as the brand's reward rules
      say" is the kit asserting nothing of `rewards` and the binding unchanged
- [X] T044 [P] [US2] `a-test/settings/WindowsSuite.scala` on the kit: `PUT …/attribution/settings` with
      `bonusCodeWindowDays` 7 then a code entered 8 days after registration → a touch; the defaults
      read back as 30 and 30; 0 and 366 refused `400 INVALID_REQUEST` naming the field

### Implementation for User Story 2

- [X] T045 [US2] Implement `a/networks/Codes.scala`: `resolve(code, networks: Vector[(Network,
      NetworkDocuments)]): Option[(network, tag)]` over every known network's fresh code document,
      lower-cased; tests `a-test/networks/CodesSuite.scala` (found in the second network; two networks
      listing one code → the first added wins and a warning line; stale document → not found)
- [X] T046 [US2] Fill `a/inputs/RewardsFacts.scala`'s `bonus_code_entered` (research R7): `Codes.resolve`,
      `Tag.verify` of the code's tag without the window, the pseudonym, `AttributionRecord.bind` when
      the record is unbound and `registeredAt` is within `Settings.bonusCodeWindow`, else `touch`
- [X] T047 [US2] Complete T041's code path in `a/inputs/PlayersFacts.scala` with `Codes.resolve` (a
      resolved code binds with touch `registration_code` when no valid tag; a code beside a valid tag
      is a touch with outcome `later`); fill `BackofficeEndpoint`'s `GET`/`PUT …/settings`
      (`SettingsEntity.setWindows`); T042–T044 green
      *Done:* WindowsSuite is `a-test/it/WindowsSuite.scala` (it needs the kit, so it sits with the
      suites the unit run leaves out); the check that a brand without the casino module never reads a
      `bonus_code_entered` is in StartSuite's second kit, since a suite starting its own service runs in
      a JVM of its own. Consumers judge documents fresh against `Wiring.clock`.

**Checkpoint**: a code binds at registration or within its window and never twice; the window is the
operator's to set; `bonus-codes.feature` passes.

---

## Phase 5: User Story 3 — A programme reads a brand's facts (P1)

**Goal**: every fact is on the topic with its id, kind and version in the attributes; a registered
machine reads the same facts from the brand's feed in signed pages from a cursor, and only its own
network's; a webhook posts each fact signed, retries for a day, and keeps the rest for the operator.

**Independent Test**: `CarriersSuite`; `carriers.feature` bound; quickstart §7's machine grant.

### Tests for User Story 3

- [X] T048 [P] [US3] `a-test/it/CarriersSuite.scala`: after a binding and a deposit (the deposit path
      is US4's; publish a `payment.captured` and accept that the `deposit` fact appears only once T055
      lands, so this suite's deposit cases are `pending` until then), every fact on the broker carries
      `ce-id` the fact id, `ce-type` the contract, the body's `type` and `protocolVersion`; the feed
      from the empty cursor under `asMachine("acme", "reader")` for a network whose `machine` is
      `acme/reader` → the facts in emission order, `Pages.verify` under the key document's key,
      `cursor` set; the same cursor twice → the same page byte for byte; the next cursor → the rest;
      `limit` 1001 → `400`; a machine the network does not name → `404`; an operator token → `403`; a
      second network's facts never on the first's feed; a webhook subscription created with
      `WebhookReceiver`'s address and secret → each fact posted once with the four headers and a
      signature the receiver verifies, the `registration`'s body carrying `clickId` `c-1` (SC-003); the receiver scripted `503` → posts at 1, 5, 15, 60, 120
      minutes as the clock moves, then `undelivered` after 24 hours, listed under
      `GET …/attribution-webhooks/undelivered?network=`; `resend` → posted and `delivered`; `discard`
      → gone from the list; a fact delivered twice by the broker (the kit redelivers) → one post
- [X] T049 [P] [US3] Bind `carriers.feature`'s seven scenarios in `a-test/features/CarriersFeatures.scala`;
      "the programme reads it within a minute" is the broker's delivery under 60 s on the kit's clock;
      "a network registered as a machine reads … with its own reader" is the broker's topic read under
      the machine's group with every other topic refused (the kit's broker refuses a group it does not
      know); "two brands" is two kits with two brands and one affiliate id

### Implementation for User Story 3

- [X] T050 [US3] Fill `a/api/AttributionEndpoint.scala`'s `GET /keys` (the brand's key document from
      `Settings.signingKeys` with `cacheSeconds` 3600 and `transition` of research R16) and the feed
      route (`Routes.machine()` admitted; the caller's `organization/name` against `Network.machine`,
      else `404`; `Cursor.decode`; `FeedRows.page`; `Pages.assemble` under the newest live key read
      from the store); fill `BackofficeEndpoint`'s `GET`/`POST …/signing-keys` and `DELETE
      …/signing-keys/{kid}` (`Signing.generate`, the private key to `signing-key/<kid>`, the public to
      `Settings.addSigningKey`)
- [X] T051 [US3] Fill `a/api/WebhooksEndpoint.scala`: create (the secret to `webhook/<id>/secret` and
      `WebhookSubscription.create`), read, update, delete (the secret deleted), `undelivered`, `resend`,
      `discard`; `FeedAppender`, `WebhookFanout` and `Delivery` are already wired (T025, T021); T048
      (bar its deposit cases) and T049 green
      *Done:* two changes the tests forced. A retry waits on a named service timer (`delivery-retry/<id>`,
      the timed action `DeliveryRetry` calling `Delivery.retry`), not on `thenPause(after, …)`: ankka
      0.13 times a pause by the wall clock, so the service's clock, the one that decides the 24 hours,
      would not move it. `WEBHOOK_PLAIN_HTTP` (`Settings.webhookPlainHttp`, default false) admits an http
      address for a local run and the kit; every other webhook is https. The broker has no topic ACL in the
      kit, so "refused every other fact" checks the machine's other routes; the topic grant is T073's.
      `CarriersSuite` and `CarriersFeatures` run in JVMs of their own (they move the clock a day).

**Checkpoint**: the three carriers hold the same facts with the same ids; a page verifies under the
brand's published key and a cursor is stable; a failing receiver gets a day of retries and the
operator the rest; `carriers.feature` passes.

---

## Phase 6: User Story 4 — A player's money and play reach the programme as facts (P1)

**Goal**: deposits, first deposits, chargebacks, withdrawals and bonus costs from the brand's facts, the
real-money GGR per reporting day with adjustments and the optional round stream, every amount a whole
number of minor units with its reference, and nothing personal.

**Independent Test**: `MoneySuite`, `GgrSuite`; `facts.feature` bound; the suite's four recordings and
the driver's deposit, withdrawal, bonus-cost and chargeback recordings.

### Tests for User Story 4

- [X] T052 [P] [US4] `a-test/it/MoneySuite.scala`: for a bound player, a `payment.captured` of 5000 EUR →
      `first-deposit` and `deposit`, each with `amount` 5000, `currency` EUR, `reference` the payment id,
      `ce-id` `deposit/<pseudonym>/<paymentId>` and `first-deposit/<pseudonym>`; a second → `deposit`
      only; the first delivered again → nothing more; a `payment.dispute.opened` → `chargeback` with the
      disputed amount; a `payment.refunded` → `chargeback` with reference `<paymentId>/refund`; a
      `payment.dispute.closed` → nothing; the four withdrawal families → their kinds with `payoutId`;
      `withdrawal_approved` and `withdrawal_cancelled` → nothing; a `restricted_funds_awarded` of 2000
      → `bonus-cost` 2000 with the award id; `restricted_funds_forfeited` and `converted` → nothing; a
      `provider_credited` landing on `restricted` → `bonus-cost`; a `payment.captured` published
      before the `player_registered` → `first-deposit` and `deposit` emitted at the binding; a code
      bound 3 days after an organic registration with a deposit in between → no `deposit` for it
      (research R8); an unbound player's deposits → no fact and `firstDepositAt` set; SC-002: the
      US2 code-bound player's deposit, withdrawal and day all attributed
- [X] T053 [P] [US4] `a-test/it/GgrSuite.scala` (the zone `Africa/Johannesburg`, the clock moved): a
      bound player's `bet_placed` 1000 at 23:30 local with `realBalanceAfter` down 600 (a split bet),
      `win_credited` 400 at 00:10 next day with real up 400 → after the first day's close (`CLOSE_GRACE`
      past midnight) one `ggr-daily` for the first day with `stake` 600, `win` 400, `ggr` 200,
      `reportingDay` the first day, `reference` `EUR/<day>`, `ce-id` `ggr-daily/<pseudonym>/EUR/<day>`;
      a bet funded by restricted funds only (real unchanged) → nothing in any day; a free round's win
      landing restricted → nothing; converted money's bet (real moves) → counted; a `bet_rolled_back`
      → stake reduced; a `win_credited` for a round of a closed day published after the close →
      `ggr-adjustment` with `ggr` −400 for that day and the daily fact not re-emitted; a day with no
      real movement → no fact; the day of a player unbound at the close → nothing, then a
      `bonus_code_entered` that binds → that day's `ggr-daily` with `beforeBinding` true; a network
      with `roundStream` on → one `ggr-round` at the win with the round's 600, 400, 200 and reference
      the round id, plus the daily fact; a network without → no `ggr-round`; the same `money.transactions`
      fact delivered twice → counted once; `OpenDays` empty after the close; `DayCloser` run twice →
      one fact
- [X] T054 [P] [US4] Bind `facts.feature`'s seven scenarios and the outline's six rows in
      `a-test/features/FactsFeatures.scala`; "no fact carries a personal field" reads every fact
      emitted so far against the kit's markers
- [X] T055 [US4] Confirm `ParitySuite` lists the suite's four recordings and the driver's deposit,
      withdrawal, bonus-cost and chargeback recordings as failing; name in `a-test/port/Parity.scala`
      the divergences that hold on them (the third deposit's FTD, the withdrawal at the hold, the bonus
      cost that the original refused, the chargeback nothing called, the converted-money round the port
      counts). Needs T035
      *Done 2026-10-28:* the eight replay green with the five divergences named. Converted money is
      `observable`, and on two recordings, not one: the converted round (the original 0, the port 1000)
      and the split round (500, 1000: half its 10.00 real stake was converted in the same bet; plan.md's
      row corrected). The restricted-only round makes no fact on either. The withdrawal, bonus-cost and
      chargeback differences are in the `notify/*` answers, which the translation turns into facts rather
      than replays, and no recording reaches a third deposit, so those four are not observable

### Implementation for User Story 4

- [X] T056 [US4] Fill `a/inputs/PaymentsFacts.scala` and `a/inputs/WithdrawalsFacts.scala` (research
      R9: `AttributionRecord.money` with the `MoneyEvent` of contracts/facts.md, the dedupe id the
      message's `ce-id`); fill `RecordFacts`'s money events (T024's shell) and the pending events at
      `Bound`; `PaymentsFacts` on `payment.captured` sets `firstDepositAt` through `money` whether or
      not bound
- [X] T057 [US4] Fill `a/inputs/MoneyFacts.scala` (research R10): `WalletCursor.advance` on every
      family; `RealMoney.counted` → `RoundIndex.remember` on a stake, the day from the index or the
      leg's own time, `DayTally(player/currency/day).leg`; `RealMoney.bonusCost` →
      `AttributionRecord.money(bonus-cost)`; fill `TallyFacts` (T024's shell) for `Closed`, `Adjusted`
      and settling legs with the network's `roundStream` read from `Network`; fill `RecordFacts`'s
      before-binding days at `Bound` from `OpenDays.closedFor`; `DayCloser` already closes (T026);
      T052–T054 green; T055's recordings replay with only the named divergences
      *Done:* ankka 0.13 hands a consumer no message `ce-id` (its `ChangeContext` has the subject only), so
      every dedupe id is the data model's fallback: `<family>/<paymentId>`, `<family>/<payoutId>`, and
      `RealMoney.dedupeId(fact, None)`. `WalletCursor` answers a repeated id `Seen(delta)` with the delta it
      gave the first time, so a delivery cut short after the cursor is finished by the next (every write
      after it is idempotent by the id). SC-002's day is in `GgrSuite` (a kit of its own); its deposit and
      withdrawal in `MoneySuite`. `GgrSuite` and `FactsFeatures` run in JVMs of their own, through the
      kit's `DayClock`, which closes a day only once the wallet's legs are in its tallies and the
      `OpenDays` view. T048's deposit case and the bonus-codes feature's "later facts" step now check the
      `deposit` fact itself.

**Checkpoint**: every money kind of FR-011 is emitted from the brand's own facts with exact amounts and
references; GGR is per day, real money only, adjusted when late, per round only on request; the suite's
GGR recordings replay; `facts.feature` passes.

---

## Phase 7: User Story 5 — A network's tokens are trusted, rotated and retired (P2)

**Goal**: an unknown key makes the brand fetch and cache the network's key document; a withdrawn key is
refused once the cache refreshes; a legacy tag binds through the programme's legacy tag document during
the transition and is refused after; a removed network's tags are refused and its facts stand.

**Independent Test**: `NetworksSuite`'s trust cases; `networks.feature`'s first six scenarios bound;
SC-007 (a token minted by `FakeNetwork` with a key the kit never saw verifies after one fetch).

### Tests for User Story 5

- [X] T058 [P] [US5] `a-test/it/NetworksSuite.scala` (trust cases): a network added through `POST
      …/networks` with `FakeNetwork`'s addresses and no documents cached; a click with a tag under its
      key → `FakeNetwork` served `/keys` once and the click is recorded (SC-007); `rotate()` → a tag
      under the old key still verifies until `cacheSeconds` pass on the clock, then `withdrawn_key`
      and the new key verifies; `fail("keys")` with an expired cache → `key_unreachable`, `GET …/status`
      naming the network unreachable, and the old keys used again once `fail` is lifted; the programme
      network with a legacy document listing the driver's UUID → a `player_registered` with that UUID
      binds to its affiliate and link with no sub-ids and `Settings.lastLegacyDay` today; a UUID the
      document does not list → `legacy_unknown`; after three legacy and one signed registration,
      `GET …/status` shows the legacy count per day and `GET …/keys`'s `transition` has
      `legacyTagsLast30Days` 3 (SC-008); the clock moved 12 months → `legacy` and the key
      document's `transition.endedBy` `12_months`; a second kit with no legacy tag for 90 days →
      `90_days_without_legacy`; `DELETE …/networks/{id}` → its tag `unknown_network`, the facts
      already on the topic unchanged, `GET …/networks/{id}` showing `removed`
- [X] T059 [P] [US5] Bind `networks.feature`'s first six scenarios (the outline's two rows) in
      `a-test/features/NetworksFeatures.scala`

### Implementation for User Story 5

- [X] T060 [US5] Fill `BackofficeEndpoint`'s network routes (`GET`/`POST …/networks`, `GET`/`PUT`/`DELETE
      …/networks/{id}`, `POST …/networks/{id}/pseudonym-key` writing `Pseudonyms.newKey()` to the store
      and `Network.pseudonymKeyMade`, `409` when made) and `GET …/status` (networks with their
      documents' freshness and last error, `Records.countsByNetwork`, the transition,
      `Deliveries.undelivered` counts, `OpenDays` count); T040's on-demand fetch moved into
      `a/networks/Cache.scala` (`keysFor(network, kid, fetch: Boolean)`: the cache, else one fetch when
      `fetch` is true, which only the click route passes; the registration path of T041 reads the
      cache and never fetches, FR-023); `DocumentRefresh` already refreshes (T026); T058 and T059 green
      *Done:* `networks/Cache.scala` holds the rule rather than a `keysFor` call: `Cache.keys(docs, now)` is
      the key document's keys while it has not expired and none after (so an unreachable document's tags
      verify for the time it said, then `key_unreachable`), and `Cache.fetchFor` is the click's on-demand
      fetch, also taken when the held document has expired. `TagCheck.Sources` gained `now` (the wired
      clock). `GET …/status` adds `legacyPerDay` for SC-008's count per day. `NetworksSuite` and
      `NetworksFeatures` run in JVMs of their own (a year on the clock; a second brand for the 90 days).

**Checkpoint**: trust comes only from cached documents; rotation and withdrawal follow the document's
cache time; the transition ends by either rule and is published; `networks.feature`'s trust scenarios pass.

---

## Phase 8: User Story 6 — A programme revokes a link, and nothing else (P2)

**Goal**: a revoked link or affiliate binds no new player while the bound stay bound and keep being
emitted; nothing else a network sends reaches a binding, a fact or an amount.

**Independent Test**: `NetworksSuite`'s revocation cases; `networks.feature`'s last four scenarios bound.

### Tests for User Story 6

- [X] T061 [P] [US6] `a-test/it/NetworksSuite.scala` (revocation cases): a revocation list naming a link
      → after the refresh a tag for it is `revoked_link` and a player bound to it earlier still has
      their deposit emitted; a list naming an affiliate → every tag naming them `revoked_affiliate`;
      a `POST` or `PUT` from `FakeNetwork` to any route of the service under no token → `401`/`404`,
      and under a machine token to anything but the feed → `403`/`404` (FR-024); the operator's
      `GET …/records/{player}` shows the binding unchanged after both revocations
- [X] T062 [P] [US6] Bind `networks.feature`'s last three scenarios in
      `a-test/features/NetworksFeatures.scala`; "anything but signing keys and revocations" is the
      route sweep of T061

### Implementation for User Story 6

- [X] T063 [US6] Fill `BackofficeEndpoint`'s `GET …/records/{player}` (`AttributionRecord.get`, rendered
      as routes.md says, no secret and no personal field); confirm `Tag.verify`'s revocation step reads
      `NetworkDocuments`'s fresh revocation list through `Cache` (T060) in both the click and the
      registration paths; T061 and T062 green
      *Done:* the revocation list is read as its last good copy in both paths (a stale list can only
      refuse more); the route sweep's writes under no token or a machine's answer `401`, `403`, `404` or
      `405` and change no record.

**Checkpoint**: the reverse channel is the two documents and nothing more; `networks.feature` passes whole.

---

## Phase 9: User Story 7 — A player is closed or erased (P3)

**Goal**: one `player-closed` fact with a category and no reason when an attributed player is closed,
permanently self-excluded or erased; the record keeps its binding and the facts stand.

**Independent Test**: `ClosureSuite`; `closure.feature` bound.

### Tests for User Story 7

- [X] T064 [P] [US7] `a-test/it/ClosureSuite.scala`: `player_closed` for a bound player → one
      `player-closed` `closed`, `ce-id` `player-closed/<pseudonym>`, no `reason`; a second `player_closed`
      → nothing; `player_self_excluded` with `until` absent → `self-excluded`; with `until` set →
      nothing; `POST /v1/casino/brands/goldvibe/attribution/players/{player}/erasure` under the kit's
      `players` service caller → `200` with `erased` true and `personalFields` empty, a `player-closed`
      `closed` when none was emitted, nothing when one was; under an operator token → `403`; the record
      after erasure keeps network, affiliate, link, sub-ids, click id, pseudonym and dates; the facts on
      the topic unchanged (compared before and after); an unbound player's closure → nothing
- [X] T065 [P] [US7] Bind `closure.feature`'s two scenarios and the outline's three rows in
      `a-test/features/ClosureFeatures.scala`

### Implementation for User Story 7

- [X] T066 [US7] Fill `PlayersFacts`'s `player_closed` and `player_self_excluded` (research R11) and
      `AttributionEndpoint`'s erasure route (`Routes.service()` as `services/merchant/.../api/ErasureEndpoint.scala`
      admits `players`; `AttributionRecord.erase`); `RecordFacts`'s `Closed` → `player-closed` (T024's
      shell); T064 and T065 green
      *Done:* `closure.feature`'s erased-record step reads the record through the backoffice's
      `GET …/records/{player}`; the record holds no personal field to erase, so "every personal field reads as
      erased" checks that none is held and the erasure answer names none.

**Checkpoint**: closure is reported once with its category; erasure changes no fact; `closure.feature` passes.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: the leak check whole, durability over a restart, the operator suite, a local run and a kind
run, the README and quickstart proven, research closed, findings and questions raised where they belong.

- [X] T067 [P] Fill `a-test/it/LeakSuite.scala` (T034): a click with a marker user agent and address, a
      registration whose `player_registered` is published beside a marker `Personal` sibling fact on
      `casino.players` (a `player_profile_changed` with a name and email), a deposit, a withdrawal, a
      day's play, a closure, an erasure; every table of the service's database, every fact on the
      broker, every feed page, every `WebhookReceiver` body, every operator answer and the captured log
      searched for every marker and for the player id on the topic, the feed and the webhooks: none
      found; the address digest present on the click row and nowhere else; SC-006 holds
      *Done:* the contract has no email-bearing player fact, so the sibling is a `player_profile_updated`
      with a name, a date of birth and a street; the email rides a click's `From` header and a closure's
      reason. The suite found the player id in every player fact's `ce-subject` (ankka's default subject is
      the source entity's id); `Publish.of` now sets the subject to the fact's key, contracts/facts.md says so.
- [X] T068 [P] Add `a-test/it/DurabilitySuite.scala` on a kit of its own: a binding, a deposit, an open
      day, an undelivered delivery, a cached key document; the runtime restarted over the same
      database; the record still bound, the day closes after the restart and the clock, the delivery
      is still listed and resends, the key document is still cached, the recurring timers exist once
      (`TimerProbe`), `firstDay` unchanged, and `click-salt` not remade
      *Done:* the undelivered delivery is made by one failed post and the clock moved 25 hours; the
      timers are counted in `ankka_timers`; the day closes by its own timer after the restart.
- [X] T069 [P] Add `a-test/it/OperatorSuite.scala`: a staff token with `brand_goldvibe` reads and changes
      every operator route of contracts/routes.md; one with `brand_other` → `403 brand_not_in_roles`;
      a player token → `403 wrong_issuer`; the pseudonym key route's answer and every network read hold
      no key; the webhook read holds no secret; `GET …/status` has every section with the counts the
      suite's state implies
      *Done:* the kit's Keycloak stand-in reads `player|<id>` as a player token. A removed network stays
      on the status, never reachable: FR-025 changes nothing else about it.
- [X] T070 [P] Add `a-test/it/ScaleSuite.scala` (SC-004, FR-016): 2,000 bound players with a day's bets
      and wins published; the clock moved past the close; every `ggr-daily` on the topic within 60 s
      of the close on the kit's clock, and on the feed within the same; one `DayCloser` run closing
      2,000 tallies under the 5-minute period
      *Done:* the run found a defect: the minute's refresh fetched only expired documents, so a
      registration between a key document's expiry and the next run was refused `key_unreachable` and
      the player stayed organic. `DocumentRefresher` now fetches a document expiring within two minutes
      (`DocumentRefresh.Ahead`); a NetworksSuite test holds it. Measured 2026-10-25: the closing run 789 ms,
      all 2,000 `ggr-daily` on the topic at 2.9 s and in the feed at 5.3 s after the close.
- [X] T071 [P] Write `services/attribution/README.md` after `services/ingest/README.md`: the components
      by package, the suites and how to run them (`just attribution-test`, `attribution-it`), the local
      run of quickstart §6 with the fake network (`a-test/it/FakesMain.scala`: `FakeNetwork` on 9103),
      the secrets the service keeps and what never leaves it, the three verify-at-task-time items of
      plan.md and what was found at T057 (`ce-id`), T033 (`casino.rewards`) and T073 (the country
      header)
- [X] T072 Run quickstart §6 locally: `just attribution-run` with `FakesMain`; the three `curl`s answer as
      §6 says; `ankka local console` shows `Click` `c-1`, `NetworkDocuments` `programme`, `Settings`
      `brand`; record the run's date in `services/attribution/README.md`
      *Partly done, 2026-10-25:* the service runs locally (it needed an in-memory broker when
      `ANKKA_KAFKA_BOOTSTRAP_SERVERS` is unset, as ingest has, and `ANKKA_SECRET_KEY`; quickstart §6 and the
      recipe now say so). The malformed and unknown-network clicks, the `401`, `/keys` and the local
      entity reads are recorded in the README. Open: the network's add, the pseudonym key and a bound
      click, which need a staff token from dev-kind's Keycloak; the user mints it.
      *Done 2026-10-28*, the open half on kind instead (the same routes, under a real staff token from the
      `eitheror` realm): the network added, its pseudonym key made, a bound click answered twice the same
      (T073's note).
- [ ] T073 Run quickstart §7 on kind (`--context kind-ankka` only, never production): `just modules apply
      brands/goldvibe --installation dev-kind`; `attribution` running and exposed; a network added, a
      pseudonym key made, a click through the gateway with a tag `FakesMain` minted; a machine registered
      and granted the feed route and the topic (`ankka projects grants make machine:<org>/<name> topic
      affiliates.attribution consume -p goldvibe`, FR-019); a page read under its token and `404`
      under another machine's; the topic read under the machine's group from the broker's external
      listener and `casino.players` refused to it; the
      header the gateway sets for the country checked and `COUNTRY_HEADER`'s default corrected in
      `modules/core/module.json` if it is not `CF-IPCountry`; `ankka services logs attribution` shows
      no tag value, no key and no address; record the run in the README
      *Not run, 2026-10-25:* the image builds and goldvibe's values validate against dev-kind. The apply
      was held back: kind-ankka had 81% of its CPU requested before goldvibe's seven core services, and the
      checks after it need a staff token and a machine's client credentials, which the user mints.
      *Run 2026-10-28* on a rebuilt kind-ankka (platform 0.14.2, services on 0.13.0), all but the topic
      read; the record is in the service's README. Through the gateway: an empty click `400`, an unknown tag
      `200 malformed`, a minted tag `200 ok` twice and one fact, `country` from `CF-IPCountry` (the gateway
      sets none and passes it through, so the default stands); the operator routes `401` without a token;
      under a staff token the network, its pseudonym key and a signing key made; under machine tokens the
      granted machine read a signed page, an ungranted one `403`, the granted one on another network `404`;
      the log held no tag, key, token or address. Fixed from the run: `casino-modules` never empty (the
      platform refuses an empty entry), attribution on `medium` (OOM-killed twice at 512 MiB), the
      network's `machine` taking the CLI's `machine:` form, and the modules tool comparing with the newest
      generation that recorded a descriptor (restarts record none; history is newest first). Raised for
      ankka (research *For ankka*): the keys port 7629 never started, the machine issuer derived as
      `http://localhost:9000`; both worked around on kind's control plane by hand. Open: the topic read
      under the machine's group and `casino.players` refused, which need the broker's external listener;
      dev-kind has none (the grant reports `broker not exposed`).
- [X] T074 Close research: mark the three verify-at-task-time items of plan.md's Phase 0 with what was
      found (`research.md` *For ankka*, plan.md Phase 0); add a *Changed at implementation* note to
      data-model.md and research.md for anything T040–T073 changed; confirm plan.md's *Divergences* and
      `a-test/port/Parity.scala` agree (`CoverageSuite` green); update `specs/012-affiliate-protocol/spec.md`'s
      *Status* to implemented with the date
      *Done:* plan.md Phase 0 records what was found for the three items; research.md and data-model.md have
      *Changed at implementation*; the status reads implemented but for T073 (T035 recorded 2026-10-28).
      `CoverageSuite` passes, its recording checks among them.
- [ ] T075 Raise what belongs elsewhere: research *Findings* 1 to 11 as comments on the plan doc's
      threads or notes in `specs/001-brands-and-projects/spec.md`, `specs/008-players/spec.md`,
      `specs/013-affiliate-programme/spec.md`, `specs/019-spinvibe-migration/spec.md` and
      `specs/021-core-and-modules/spec.md` *Open Questions* (a one-line pointer each, never a rewrite of
      another spec); send research's *The message for Sheldon*; file the three *For ankka* items in
      `~/Documents/projects/thinkmorestupidless/ankka` as issues or spec notes; `just features` 0
      findings; `git status` clean but for this branch's work
      *Partly done, 2026-10-25:* the one-line pointers are in the *Open Questions* of specs 001, 008, 013, 019
      and 021 (finding 5 is the contract's own; 11 asks nothing); `just features` reports 0 findings in 1,106
      scenarios. Open, each for the user to send: the comments on the plan doc's threads, the message for
      Sheldon, and the *For ankka* items in the ankka repository.
      *2026-10-28:* the *For ankka* items checked against ankka's source (main, v0.14.2): the undeclared
      topic is answered by its docs (it waits; plan.md and research.md say so); the other two and the two
      added at implementation are written as four issues, which the session was not allowed to file. The
      plan doc could not be read from this account. The message for Sheldon gained the recorded GGR numbers.
      Still for the user: file the four issues, post to the plan doc, send the message.
      *Later 2026-10-28:* six issues filed in thinkmorestupidless/ankka, #197 to #202 (the four, and two
      from the kind run). The plan doc is still refused to this account (as an artifact and as a doc); the
      message for Sheldon was handed to Trevor to send.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)**: none. T001 first (T004's recipes and CI name its paths); T002–T006 together after it.
  T002 publishes the contract every service task depends on.
- **Foundational (2)**: after Setup. Blocks every story. T007–T014 together (T009 after T008; T014
  after T008); T015–T021 together after T007; T022 after T018, T019, T020, T021; T023 and T024 after
  T013 and T018; T025 after T020, T021; T026 after T016, T019; T027 after T007; T028 after T015–T026; T029 after T028; T030
  after T027 and T029; T031 and T032 after T030; T033 and T034 after T030. T035 needs T005, the
  original's KinD stack with the affiliate service, and Docker,
  and gates T039 and T055.
- **US1 (3)**: after Foundational. The MVP. T036–T038 together; T040 after T009, T011, T017; T041
  after T009, T010, T018; T039 after T041 and T035.
- **US2 (4)**: after US1 (T047 completes T041). T042–T044 together; T045 after T011; T046 and T047
  after T045.
- **US3 (5)**: after US1 (a `registration` fact to carry). T048 and T049 together; T050 after T014,
  T020, T022; T051 after T021, T025. Its deposit cases pass after T056.
- **US4 (6)**: after US2 (SC-002's code-bound player) and US1. T052–T054 together; T056 after T018,
  T024; T057 after T019, T022, T026, T056; T055 after T057 and T035. Can run beside US3.
- **US5 (7)**: after US1 (the fetch of T040 and T041). T058 and T059 together; T060 after them and
  T026. Can run beside US3 and US4.
- **US6 (8)**: after US5 (the cache of T060). T061 and T062 together; T063 after them.
- **US7 (9)**: after US1. T064 and T065 together; T066 after them. Can run beside US2–US6.
- **Polish (10)**: T067 after US1–US7 (every fact kind in the leak check); T068 after US3–US5; T069
  after US2, US3, US5, US6; T070 after US4; T071 beside anything after US4; T072 after US1, US5; T073
  after T003, T072; T074 after T073; T075 last.

### Platform gates (plan.md Phase 0, research *For ankka*)

- Whether a consumer of a topic the project does not declare stops the service is settled at T033
  (the second kit with `CASINO_MODULES` empty on a broker with no `casino.rewards`): the gate is
  already designed in, so nothing waits on the answer.
- Whether a topic message's `ce-id` reaches `messageContext` is settled at T057; `RealMoney.dedupeId`
  has the fallback.
- The country header is settled at T073; `COUNTRY_HEADER` is a variable with a default.
- `Routes.machine` (T002) is written against 0.13.0's `Caller.Machine` and `Callers.granted`; a machine's
  grant is made with the CLI at T073, never by a manifest.

### Parallel Opportunities

- Phase 1: T002–T006 together after T001.
- Phase 2: T007–T014 together; T015–T021 together; T023–T026 together; T027 beside T028 and
  T029; T031 and T032 together; T035 the moment the original is up.
- US1: T036–T038 together; T040 and T041 together; then T039.
- US3, US5 and US7 can run beside each other once US1 is done; US4 once US2 is; US6 once US5 is.
- Polish: T067–T071 together; T072 → T073 → T074 → T075.

---

## Parallel Example: User Story 1

```bash
# tests first, together:
Task: "LandingSuite in a-test/it/LandingSuite.scala"
Task: "BindingSuite (tag cases) in a-test/it/BindingSuite.scala"
Task: "Bind landing.feature in a-test/features/LandingFeatures.scala"
# then together:
Task: "api/TrackEndpoint.scala — the click of research R5"
Task: "inputs/PlayersFacts.scala — player_registered of research R6"
# then:
Task: "parity: the driver's click, registration and later-tag recordings"
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phases 1 and 2, with T035 started the moment the driver builds and the original's stack, with the
   affiliate service, is up (it needs nothing of this service and gates two parity tasks).
2. Phase 3: a click is recorded and emitted; a tag within the window binds once with the pseudonym and
   emits `registration`; every refusal leaves the player organic with its reason. `landing.feature`
   green; the driver's click and registration recordings replay.
3. Stop and run quickstart §3 and §4 for the US1 suites.

### Incremental delivery

1. US2: an affiliate's code binds, at registration or within the window. After it bug 1 is fixed
   (SC-002 is proven once US4 emits the money).
2. US3: the feed and the webhooks beside the topic. After it a network with no Kafka client can read
   the brand, and the postback is replaced.
3. US4: the money and the GGR. After it SC-001 holds on the recordings and the programme has every
   input of ADR 0042's fold; `facts.feature` green.
4. US5 and US6: rotation, the transition and revocation. After them SC-007 and SC-008 hold and
   `networks.feature` is green.
5. US7: closure and erasure. Then Polish, quickstart §6 and §7 on kind before the manifest is applied
   anywhere real.

---

## Notes

- Every parity task replays recordings that must exist (T035); do not write a recording by hand. The
  original's behaviour where the plan diverges is recorded as it is and declared in
  `Parity.divergences`, never corrected in the recording.
- No personal value and no player id in any fact, page, webhook body, log line or span: T007's codecs
  give the fact types no field to hold one; T034/T067 search for them; the log prints a click id, a
  player id, an outcome and a network and nothing from a body or a tag.
- A pseudonym key, a signing key, a webhook secret and the click salt live in the secret store and
  nowhere else: no entity state, event, view row or answer has a field for them (T015–T021), and
  T067 and T069 check every answer.
- Every consumer and timer is safe to repeat: entity commands dedupe by id (T018, T019, T020), the
  workflow's `start` is a no-op on an existing one (T021), the timers return `done` with nothing to
  do (T026).
