# Tasks: ingest

**Input**: Design documents from `specs/006-ingest/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{routes,merchant,recording}.md, quickstart.md

**Tests**: Included. The constitution makes the original's recorded behaviour the acceptance test
(I) and the living features the scenarios (VII); the port method makes parity tests a deliverable,
written before the component they hold to account and failing first (ankka-port §7). Every story
opens with its tests: the entity and consumer kits, the integration suites on the kit, the feature
bindings, the SC suites, the parity tests over the recordings.

**Organization**: By user story, in spec.md's order. US1 to US3 are P1 and together are the deposit
path a sub-brand sees; US1 alone is the MVP (a create under a key, verified against the fact-fed
index, checked, forwarded once to `FakeMerchant`, recorded and audited). The pure packages, the
five entities, the two views, the two consumers, the `merchant` seam, the fake and the kit are
foundational: no route can be tested before a key is known, a record can be written and a command
can be forwarded. Idempotency (US2) and the ticket's routes (US3) extend the create's pipeline; the
rate (US4) and the operator (US5) sit beside it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US5 as spec.md numbers them
- Paths are from the repository root; the layout is plan.md's

## Path Conventions

- `i/` stands for `services/ingest/src/main/scala/io/eitheror/casino/ingest/`
- `i-test/` stands for `services/ingest/src/test/scala/io/eitheror/casino/ingest/`
- `contract/` is the shared library `io.eitheror:casino-contract`; `tests/port/ingest/` the
  recordings; `tests/original/` the recording toolchain; `features/payments/` the living features
- `m/` stands for `services/merchant/src/main/scala/io/eitheror/casino/merchant/` (the one contract
  hoist only); `g-test/` for `services/psp-gateway/src/test/scala/io/eitheror/casino/pspgateway/`
  (patterns copied, never changed)
- The service's sbt commands run in `services/ingest/`; Go commands in `tests/`; everything else at
  the root
- `the original` is `~/Documents/projects/eitheror/eitheror-platform` at `659c2d6b`;
  `CashierRoutes.scala` and `MerchantServiceClient.scala` are under its
  `substrate/merchant-service/src/main/scala/io/eitheror/merchant/`
- A recording run is made only against a local original on its KinD stack, never a shared or
  production one

---

## Phase 1: Setup

**Purpose**: the service's build, the recipes and CI job, the audit base hoisted into the contract,
the three recordings copied, and the recording driver (which needs only Go and the original's
checkout, not this service).

- [X] T001 Create `services/ingest/` on `services/psp-gateway/`'s template: `build.sbt` on Scala 3.9
      with ankka 0.11.0 (`ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
      `ankka-telemetry-otlp`, `ankka-testkit`), `io.eitheror:casino-contract` from the local publish,
      munit, the Postgres driver as a test dependency (for `LeakSuite`); `project/plugins.sbt`;
      `service.json` for the local run (`ANKKA_HTTP_PORT` 9002 so it runs beside `merchant` on 9000
      and `psp-gateway` on 9001); `docker-compose.yml` and `sbt schema` as psp-gateway has;
      `src/main/resources/application.conf` and `logback.xml` (no body and no header logging: SC-006);
      the package directories plan.md lists under `i/` (`keys/`, `rate/`, `requests/`, `audit/`,
      `merchant/`, `shape/`, `api/`) and `i-test/` (`shape/`, `merchant/`, `keys/`, `rate/`,
      `requests/`, `audit/`, `it/`, `port/`, `features/`); `services/ingest/README.md` naming the
      service's purpose in two lines (filled by T060)
- [X] T002 [P] Add to `justfile` the recipes `ingest-test` (`sbt contract/publishLocal` then `sbt
      test` in `services/ingest`, as `psp-gateway-test`), `ingest-it` (`testOnly
      io.eitheror.casino.ingest.it.* io.eitheror.casino.ingest.port.*
      io.eitheror.casino.ingest.features.*`), `ingest-run` (`ANKKA_HTTP_PORT=9002
      -Dankka.local-services.merchant=http://127.0.0.1:9000`, `ANSWER_BOUND=10s`, `sbt run`); add an
      `ingest` job to `.github/workflows/ci.yml` after the `psp-gateway` job's pattern (publish the
      contract, `sbt test` in `services/ingest/`); the `check` job's `go test ./...` under `tests/`
      covers the driver once T004 exists
- [X] T003 [P] Hoist `AuditConsumer` and `AuditCompanion` from `m/audit/AuditFacts.scala` into
      `contract/src/main/scala/io/eitheror/casino/contract/audit/AuditConsumer.scala` (research R9,
      finding 4): the base consumer shaping an `AuditRecord` onto `platform.audit` with `ce-id` set,
      parameterised by the service name and the mapping from a state change to an optional record;
      `m/audit/AuditFacts.scala` becomes merchant's mapping over the hoisted base with no behaviour
      change (`services/merchant/src/test/.../audit/` suites green unchanged); a contract test
      `contract/src/test/scala/io/eitheror/casino/contract/audit/AuditConsumerSuite.scala` (one
      record per change the mapping names, none otherwise, `ce-id` the subject id); `sbt
      contract/publishLocal`; `just merchant-test` green. Fallback if the hoist touches more of
      merchant than its audit package: copy the two classes into `i/audit/` and leave finding 4 open
- [X] T004 [P] Write `tests/original/ingest-driver/` (Go, as `tests/original/merchant-driver/`:
      `doc.go`, `driver.go`, `api.go`, `scenarios.go`, `driver_test.go` offline), reusing
      `merchant-driver`'s `Session.Open/Submit/Read` with `MerchantKey` and `NoAuth`: one scenario
      per row of contracts/recording.md's table, named as `cashier.feature` names the scenario, each
      creating its own holding, company, sub-brand, key and tree as `setup:` steps (the key step's
      `201` answer is where the replay finds the raw key); the no-key create, the made-up key, the
      disabled key, every missing or malformed field (each FR-007 row, one scenario per row), the
      two repeats under `t-1`, two sub-brands under `t-1`, two creates without `transactionId`,
      create-submit-submit, submit `sim-gbp`, submit a made-up token, read with the key, read with
      another sub-brand's key, create with the header spelt `MerchantKey`; `vary` on the session
      token, the payment id, every timestamp and the minted `transactionId`; run with `-tags e2e
      -merchant-url http://localhost:8086 -substrate-url http://localhost:9000`. Extend
      `tests/original/preflight` to check the cashier route answers. `go test ./original/ingest-driver/...`
      green offline
      *Done 2026-10-24*: `doc.go`, `driver.go` (the helpers `api.go` was to hold), `scenarios.go`,
      `record_test.go` (the `e2e` run), `driver_test.go` (offline: the names against `cashier.feature`,
      the recorded shape against a stub). The seed routes give each sub-brand its key and tree, as
      merchant-driver's do, so the raw key is the seed's `$.merchantKey`, not a key step's `201`;
      `Parity` reads both. merchant-driver gained `Config.Service`, `NoKey`, `Remember`, a `testing.TB`
      `Scenario`, and extra headers sent as spelt. Run at `-merchant-url http://localhost:8085
      -substrate-url http://localhost:8080` (the original run natively; see T026). `preflight -cashier`.
- [X] T005 [P] Copy `tests/port/merchant/TestScenario12_OnboardingGate3_FullChain.json`,
      `TestScenario13_DreamzPay_FullChain.json` and `TestScenario14_CasinoDeposit_PlayerToWallet.json`
      unchanged into `tests/port/ingest/`; add `tests/port/ingest/README.md` naming the three as
      reused from 004's run at `659c2d6b` (contracts/recording.md), which steps of each are this
      service's (the public create, submit and read) and which are `FakeMerchant`'s script, and that
      the key is reconstructed from the key-generation step's answer; `just brands recordings check`
      0 problems; `tests/port/README.md` gains the `ingest` directory

---

## Phase 2: Foundational

**Purpose**: what every story needs: the pure packages with their suites, the five entities and two
views with their kits, the two consumers, the `merchant` seam and the start refresh, the fake and
the kit, the parity and feature suites listing their tests, the service and its endpoint shells.

**⚠️ CRITICAL**: no story starts before this phase is done. T026 (the recording run) gates every
parity task and can run the moment T004 and the original's stack are up.

### Pure packages (tests beside each)

- [X] T006 [P] Implement `i/Settings.scala` (research R13's table: `MERCHANT_SERVICE` `merchant`,
      `ANSWER_BOUND` `10s`, `RATE_LIMIT` `600`, `BODY_BOUND` `65536`, `REQUEST_ID_BOUND` `128`,
      `REQUEST_ID_RETENTION` `24h`, `RECORD_RETENTION` `90d`; `fromEnvironment` refusing a value it
      cannot read by the variable's name, as `services/psp-gateway/.../Settings.scala` does) and
      `i/IngestCodecs.scala` (codecs for every stored type of data-model.md: `Outcome`, `Route`,
      `Answer` with its `JsonNode` body, `Digest`, the five entities' states, `Long` minor units as
      a JSON number, `Instant` as ISO-8601, after `GatewayCodecs`); tests `i-test/SettingsSuite.scala`
      (every default; a malformed duration or count refused naming the variable) and
      `i-test/IngestCodecsSuite.scala` (every type round-trips; an `Answer` body with nested
      `available_terminals` survives unchanged)
- [X] T007 [P] Implement `i/shape/Fields.scala`: `Fields.check(body: JsonNode, requestIdBound: Int):
      Either[Refused(field), Checked]` over the public create body (contracts/routes.md's table):
      `amountCents` present, a JSON integer, `> 0`, within `Long`; `currency`, `country`,
      `redirectUrl` present and non-empty strings; `customerId` `brand:player` with both parts
      non-empty; `transactionId` absent or a string of at most the bound with no control character;
      the first failing field named in the table's order; `Checked` carrying `amount: Long`, the
      strings and `transactionId: Option[String]`. Tests `i-test/shape/FieldsSuite.scala`: one test
      per `cashier.feature` field-outline row (lines 85–91) plus a decimal amount, a zero, a
      negative, a string amount, an amount over `Long`, a `customerId` with an empty player part, a
      `transactionId` at the bound (accepted), one over it, one with a tab; the valid body of
      quickstart §5 passes with every `customer*` field left untouched
- [X] T008 [P] Implement `i/shape/Digests.scala` (`ofKey(raw: String): Digest` lower-case SHA-256
      hex, as `MerchantServiceClient.validateKey` hashes; `ofBody(body: JsonNode): Digest` over the
      body serialised with object keys sorted at every depth; `same(a: Digest, b: Digest)` through
      `MessageDigest.isEqual` over the decoded bytes) and `i/shape/Shape.scala`
      (`Shape.toCommand(subBrand, requestId, checked, body, clientIp): JsonNode`: 004
      `contracts/commands.md`'s create body with `subBrandId`, `transactionId`, `amount` from
      `amountCents`, `currency`, `country`, `customerId`, `redirectUrl`, `clientIp`, and every
      `customer*` field copied as sent; `Shape.clientIp(forwardedFor: Option[String]): String` the
      first comma-separated entry trimmed, else empty; `Shape.submitBody` and `abandonBody` passing
      the received body through). Tests `i-test/shape/DigestsSuite.scala` (a known key's digest
      matches the original's hex; two bodies differing only in key order share a digest; a changed
      `amountCents` does not) and `i-test/shape/ShapeSuite.scala` (every 004 field present and named
      as 004 spells it; the body's own `subBrandId`, if any, never reaches the command, FR-003;
      `"1.2.3.4, 10.0.0.1"` → `1.2.3.4`; none → empty)
- [X] T009 [P] Implement `i/merchant/Bound.scala`: `Bound.race[A](call: => Future[A], bound:
      FiniteDuration, clock, scheduler): Raced[A]` answering `Answered(a)` when the call completes
      within the bound, `Unanswered(late: Future[A])` otherwise with the still-running future (never
      cancelled; research R6), and `Failed(cause)` when the call fails before the bound
      (`ServiceUnresolvable`, `ServiceCallFailed`). Tests `i-test/merchant/BoundSuite.scala`: a
      promise completed at 50 ms under a 500 ms bound is `Answered`; one completed at 800 ms is
      `Unanswered` and `late` completes afterwards with the value; a failed promise is `Failed` at
      once, not at the bound

### The entities and the views (tests first)

- [X] T010 [P] `i-test/keys/KeySuite.scala` (`KeyValueEntityTestKit`): `issue(digest, subBrand, at)`
      on an empty `Key` sets digest, sub-brand, `active = true`, `since`; a second `issue` with the
      same digest changes nothing and replies the state; `deactivate(at)` sets `active = false`; an
      `issue` after a `deactivate` leaves `active = false` (data-model.md: a deactivated key is never
      revived by the start refresh); `read` answers the state; `deactivate` on an empty entity records
      `active = false` with no digest so a later `issue` cannot revive it
- [X] T011 [P] `i-test/rate/RateWindowSuite.scala` (`KeyValueEntityTestKit`): `take(now, 600)` on an
      empty `RateWindow` replies `Served` with count 1 and the minute of `now`; the 600th take is
      `Served`, the 601st `Refused(waitSeconds)` where `waitSeconds` is the seconds to the next minute
      boundary (at `12:00:15` it is 45); a take in the next minute resets the count and is `Served`;
      `setLimit(6000)` then 700 takes all `Served`; `take(now, 600)` after `setLimit(6000)` uses 6000
      (the raise outlives the default); `read` answers `{minute, count, limit}`; the refused take does
      not count
- [X] T012 [P] `i-test/requests/RequestSuite.scala` (`KeyValueEntityTestKit`), every row of
      data-model.md's command table: `begin(d1, r1)` on an empty `Request` → `inFlight`, attempt 1,
      reply `Forward(1)`; `begin(d1, r2)` while in flight → `InFlight`, nothing changed; `begin(d2,
      r3)` → `Mismatch`, nothing changed; `answer(1, a)` → `answered`, answer `a`; `begin(d1, r4)`
      when answered → `Answered(a)`, attempt still 1; `begin(d2, r5)` when answered → `Mismatch`;
      `answer(1, b)` when answered → `Late`, answer still `a`; `unanswered(1)` from in flight → `free`,
      attempt stays 1, then `begin(d1, r6)` → `Forward(2)`; `answer(1, c)` while attempt 2 is in
      flight → `Late`; `answer(2, c)` → `answered`; `refused(1)` from in flight → `free` (R5); the
      `recordIds` list holds every attempt's record id; the entity declares `expireAfter` of the
      retention it is built with
- [X] T013 [P] `i-test/requests/OwnershipSuite.scala` (`KeyValueEntityTestKit`: `set` on
      `ticket/<t>` records sub-brand, request id, record id and ticket; a later `set` adding
      `paymentId` keeps the rest; `set` on `payment/<p>` records the owner; `read` of an empty id is
      none) and `i-test/requests/RequestRecordSuite.scala` (`KeyValueEntityTestKit`: `write(...)`
      records every field of data-model.md's table and a second `write` is refused (written once);
      `lostAnswer(at, ticket, paymentId)` on an `unanswered` record sets outcome `lost_answer`,
      `seen = false` and the lost answer's fields; on any other outcome it is refused; `markSeen` sets
      `seen`; a `write` carrying a `customerEmail`, a `clientIp` or a `merchantKey` field does not
      compile — the state type has no such field, and the test asserts the codec's field list is
      exactly data-model.md's, SC-006)
- [X] T014 Implement `i/keys/Key.scala`, `i/rate/RateWindow.scala`, `i/requests/Request.scala`,
      `i/requests/Ownership.scala` and `i/requests/RequestRecord.scala` as data-model.md and T010–T013
      say, each a key value entity with `expireAfter` from `Settings` where the model names a
      retention (`Request`: `REQUEST_ID_RETENTION`; `Ownership`, `RequestRecord`:
      `RECORD_RETENTION`); shared types `Outcome`, `Route`, `Answer` in `i/requests/Types.scala`;
      T010–T013 green
- [X] T015 Implement `i/keys/KeysView.scala` (view over `stateOf(Key)`, one row per key, declared
      query `byDigest(digest)` → `{keyId, subBrand, active}` or none, the digest column indexed) and
      `i/requests/RequestsView.scala` (view over `stateOf(RequestRecord)`, declared queries
      `bySubBrand(subBrand, from, to, limit)` newest first, `byRequestId(subBrand, requestId)` every
      attempt oldest first, `lostAnswers(subBrand: Option)` where outcome is `lost_answer` and not
      seen); `sbt schema` regenerates; tests on the kit come with T022's `SmokeSuite` (a row appears
      after an `issue`; a record is found by request id)
      *Done, then changed (T028)*: `KeysView` missed SC-003 and was replaced by `KeyIndex`, an entity keyed by
      the digest (research R3).

### The consumers (tests first)

- [X] T016 [P] `i-test/keys/KeyFactsSuite.scala` (`ConsumerTestKit` over `PaymentsMerchants`'s fact
      types): `merchant_key_issued(keyId, subBrand, digest, occurredAt)` → `Key(keyId).issue(digest,
      subBrand, occurredAt)`; `merchant_key_deactivated(keyId, subBrand, occurredAt)` →
      `Key(keyId).deactivate(occurredAt)`; the same fact twice → the same command twice (the entity
      makes it idempotent); every other fact on the topic (sub-brand created, tree deployed, …) →
      nothing; a fact the codec cannot read is logged and skipped, never thrown
- [X] T017 [P] `i-test/audit/AuditFactsSuite.scala` (`ConsumerTestKit` over `stateOf(RequestRecord)`):
      a record with outcome `key_missing` → one `AuditRecord` with `service = ingest`, `action =
      <route>`, `actorKind = unknown`, `subjectKind = request`, `subjectId = recordId`, `outcome =
      refused`, `reasonCode = key_missing`, `ce-id = recordId`; `key_unknown` the same with
      `actorKind = unknown` (no sub-brand known); `key_disabled` and `rate` with `actorKind =
      sub-brand` and the sub-brand as the actor (a disabled key's row names its sub-brand: FR-019's
      "where known"); `served`, `repeated`, `refused`, `unanswered`, `invalid_field`,
      `request_id_reused`, `not_found`, `body_too_large`, `not_json` → nothing; a later change of an
      audited record (`markSeen`) → nothing; the record's `ticket` and `requestId` never appear in the
      audit record; no field of the record that could hold a key exists (FR-019)
- [X] T018 Implement `i/keys/KeyFacts.scala` (consumer of `payments.merchants` from
      `StartFrom.Earliest`) and `i/audit/AuditFacts.scala` (over T003's hoisted base, or the copied
      classes under the fallback); T016 and T017 green

### The seam, the refresh, the fake and the kit

- [X] T019 [P] Implement `i/merchant/MerchantClient.scala`: the trait of contracts/merchant.md
      (`create(command): Future[Answer]`, `submit(ticket, body)`, `abandon(ticket)`,
      `read(paymentId)`, `keys(): Future[Seq[KeyEntry]]`), each answering `merchant`'s status and body
      as an `Answer`, never throwing on a `4xx`; `MerchantClient.http` over
      `clients.services(MERCHANT_SERVICE)` by identity as `services/psp-gateway/.../merchant/MerchantClient.scala`
      makes its calls, paths from 004 `contracts/commands.md` (`POST /v1/cashier/sessions`,
      `POST /v1/cashier/sessions/{token}/submit`, `/abandon`, `GET /v1/cashier/payments/{id}`,
      `GET /v1/cashier/keys`); and `i/keys/KeyRefresh.scala` (at start: `keys()` → one
      `Key(keyId).issue(digest, subBrandId, now)` per entry; a failure or a `503` logged at warn with
      the cause and no key, and the service starts; the count of keys issued logged, never a digest).
      Tests `i-test/merchant/MerchantClientSuite.scala` over a scripted HTTP stub (each command's path
      and body; a `400 NO_ROUTING_TREE` is an `Answer(400, …)`, not a failure; a connection refused is
      a failed future) and `i-test/keys/KeyRefreshSuite.scala` (three entries → three issues; a
      failing stub → zero issues, no exception, the warn line without a digest)
- [X] T020 [P] Write the fakes in `i-test/it/`: `FakeServer.scala` (copied from `g-test/it/`),
      `FakeMerchant.scala` serving the five routes of contracts/merchant.md from a script: `create`
      answers a session (`session_token` minted, `transactionId` echoed, `redirectUrl`,
      `available_terminals` from a settable list, `resolvedFrom`) or a scripted refusal
      (`NO_ROUTING_TREE`, `NO_ROUTING_MATCH`, `WRONG_BRAND`, `INVALID_AMOUNT`, `SUB_BRAND_NOT_FOUND`,
      `SUB_BRAND_INACTIVE`) and can be told to delay a given create by a duration or to never answer
      it; `submit` answers `paymentId` and `status` once per token and the first answer again on a
      repeat (004 correction 2), or `400 terminal_not_found` for a terminal outside the create's list,
      `400 PAYMENT_REJECTED` with a `message`, `404 SESSION_NOT_FOUND`, `503 OUTCOME_PENDING` when
      scripted; `abandon` answers the payment read; `read` answers the payment's state in the
      original's fields or `404 PAYMENT_NOT_FOUND`; `keys` answers a settable `{digest, keyId,
      subBrandId}` list; every call recorded by route with the body it received (`received(route)`),
      and the sub-brand it was told (`FR-003`); `stop()`/`start()` for "unreachable"; and
      `FakesMain.scala` (starts `FakeMerchant` on a fixed port for quickstart §5 when `merchant` is
      not run)
- [X] T021 Implement `i-test/it/Kit.scala`: `AnkkaTestKit.start` with the service's descriptors
      over Postgres, `HttpServer.at("127.0.0.1", 0)`, `clients.services` wired to `FakeMerchant` as
      `merchant`, `InMemoryBroker` for `payments.merchants` and `platform.audit` (with
      `audit(): Seq[AuditRecord]` reading what was produced), a settable clock `RateWindow.take` and
      the record's `receivedAt` read, `ANSWER_BOUND` 500 ms, `BODY_BOUND` 4096, `RATE_LIMIT` 600;
      helpers `issueKey(subBrand): RawKey` (publishes `merchant_key_issued` with the digest and
      awaits the view row), `deactivateKey(keyId)`, `create(key, body, headers*)`, `submit(ticket,
      body)`, `abandon(ticket)`, `readPayment(paymentId, key | ticket)`, `records(subBrand)`,
      `record(recordId)`, `advance(duration)`; callers `asOperator` (a staff token with the payments
      role at platform scope, as 005's T027 mints), `asStaff` (a staff token without the role, for
      `403`), `asNobody`; `OwnKit` for suites that stop the fake or move the clock, as merchant's
      `SharedKit`/`OwnKit`. **Verify at task time** (research R11): that `request.header("merchantkey")`
      finds a header sent as `MerchantKey` on 0.11's `HttpEndpoint`; record in research R11; fallback:
      `Context.header(name)` scanning all headers lower-cased, used everywhere a header is read

### The suites that list their tests, the shells

- [X] T022 Implement `i/IngestService.scala` (registration of the five entities, the two views, the
      two consumers; `KeyRefresh` run once the runtime is ready) and the endpoint shells in `i/api/`,
      one per prefix with each route's audience (contracts/routes.md): `CashierEndpoint.scala`
      (`/v1/cashier`, `Routes.public`: `postBody /sessions`, `postBody /sessions/{ticket}/submit`,
      `post /sessions/{ticket}/abandon`), `PaymentsEndpoint.scala` (`/v1/payments`, `Routes.public`:
      `get /{paymentId}`), `IngestEndpoint.scala` (`/v1/ingest`, `Routes.paymentsOperator()`: the
      five operator routes); every route declared with a `501` body the stories replace;
      `i/api/Answers.scala` rendering a refusal `{"error", "field"?, "transactionId"?,
      "retryAfterSeconds"?, "message"?}` with the `Retry-After` header where routes.md names it, and
      `merchant`'s `Answer` passed through with its status and body; `i/api/Context.scala` the shell
      of the request pipeline (the record id minted, `receivedAt`, the raw body, the headers) that
      US1 fills. A smoke test `i-test/it/SmokeSuite.scala`: the service starts and `KeyRefresh` issued
      `FakeMerchant`'s two keys (the view answers `byDigest` for each); `POST /v1/cashier/sessions`
      with no identity reaches the handler (`501` for now); `GET /v1/ingest/lost-answers` without a
      token is `401`, with `asStaff` `403`, with `asOperator` `501`; a record written through the
      entity is found by `byRequestId` within 2 s; `POST /v1/payments`, `POST /v1/payouts`,
      `POST /v1/payments/verify` and `GET /v1/cashier/sessions` answer the runtime's not-found
      (FR-006), and `OPTIONS /v1/cashier/sessions` is not `200` and carries no `Access-Control-*`
      header (FR-020). **Verify at task time** (research R11): that a
      request to a `Routes.public` route carrying `Authorization: Bearer not-a-token` reaches the
      handler and is not refused by the runtime; record in research R11; if it is refused, raise it as
      an ankka finding (finding 5) and note that the brand's proxy must drop the bearer when it moves
      its base URL (finding 3)
- [X] T023 [P] Add `i-test/port/ParitySuite.scala`: one munit test per `tests/port/ingest/*.json`
      through `contract.port.Replay` against T021's kit, failing when the directory holds fewer
      than the three copied recordings, and `i-test/port/Parity.scala` mapping what is not this
      service's (contracts/recording.md): the operator, Keycloak, hierarchy, tree, registration,
      gateway and twin steps of the three Go-suite recordings → `FakeMerchant`'s script and kit
      setup; the key-generation step's `201` answer → `issueKey` with that raw key's digest, and
      `merchantKey` added to each public step; the driver's `setup:` steps → the same; the public
      steps → the service's routes; `/health` → the runtime's readiness (marked); `Parity.divergences`
      naming each row of plan.md *Divergences* at the steps it holds, checked to still differ and
      nothing undeclared to. `ParityFixtureSuite` proves the key reconstruction on a hand-written
      fixture under `tests/fixtures/port/ingest/`. Until T026 records, the driver's tests are absent,
      never passed; `i-test/port/CoverageSuite.scala` as `g-test/port/CoverageSuite.scala`: every
      recording in the directory has a test and every marked divergence is a plan.md row
- [X] T024 [P] Add `i-test/features/CashierFeatures.scala` and `RequestsFeatures.scala`:
      `GherkinSuite` over `features/payments/cashier.feature` and `requests.feature` on T021's kit,
      every step pending so each scenario fails as unbound (25 + 3 scenarios, the three outlines once
      per row); the cashier is `FakeMerchant`, the sub-brand a key from `issueKey`. Scenarios
      merchant's `services/merchant/src/test/.../features/CashierFeatures.scala` binds through its
      own routes stay bound there too: here every one runs through the edge
- [X] T025 [P] `i-test/it/LeakSuite.scala` shell (SC-006): a create with every `customer*` field
      filled with a marker value, an `X-Forwarded-For` of a marker address and a key whose raw value
      is a marker; after the stories fill the routes, every table of the service's database (read
      through the Postgres driver), every view row, every `AuditRecord` the broker holds and the
      captured log are searched for each marker and hold none; a known `session_token` and
      `paymentId` are allowed to appear. Listed pending until T037; the search itself is written now
- [X] T026 Record the original per `tests/original/README.md` and contracts/recording.md: on the
      original's KinD stack at `659c2d6b` (as spec 004's recordings were made), the driver (T004)
      twice on fresh ids; `preflight` green; `merge` into `tests/port/ingest/` beside the three
      copied recordings, with `suite-result.json`; `just brands recordings check` 0 problems; list in
      `tests/port/ingest/README.md` what was recorded at which original commit and what the original
      answered where the plan diverges (the `400` for a missing header, the defaults for missing
      fields, the second session on a repeat, the read served to another sub-brand), each a row of
      plan.md *Divergences*, never corrected in the recording. Needs the original's checkout, kind and
      Docker; a local original only
      *Done 2026-10-24*, at `659c2d6b` from the `eitheror-platform-recording` worktree, not the KinD
      stack: the `eitheror-dev` cluster no longer existed and the disk could not take a rebuild, so
      substrate, merchant-service and psp-gateway ran natively (`tests/port/ingest/README.md` has every
      setting). 25 recordings, the same 12 pass and 13 fail on both runs; `recordings check` 227, 0
      problems. `preflight` passed the nine providers and the cashier and refused `st8`, whose fake is
      healthy only once the casino's ST8 adapter registers, and nothing in this run calls ST8.
      Divergences recorded as the plan lists them; two answers the plan did not expect, both
      `merchant`'s and passed through: a second submit makes a second payment, and an unoffered terminal
      is `500 terminal_not_found` (spec 004's `merchant` answers `400`, which 004's plan does not list).

**Checkpoint**: `sbt compile test` green for T006–T021 and T022; `ParitySuite` lists one failing test
per recording (three before T026, 28 after); the two feature suites list 28 pending scenarios;
a key issued through the broker is found by digest; `FakeMerchant` answers a create from its script;
nothing is served but refusals and `501`.

---

## Phase 3: User Story 1 — A sub-brand creates a cashier session under its key (P1) 🎯 MVP

**Goal**: `POST /v1/cashier/sessions` under an active key, checked in research R2's order (body
bound, key, rate as a pass-through take, fields), shaped into 004's command and forwarded once to
`FakeMerchant` as the key's sub-brand, the answer returned unchanged; every outcome a request
record; a key refusal an audit record naming no key; a deactivation fact refusing the key on the
next request. The request id's idempotency (US2) and the rate's refusal (US4) are stubbed here as
"always forward" and "always served"; the record and the take are written from the start.

**Independent Test**: quickstart §4's `CashierSuite` create cases and `KeysSuite`; the parity
recordings for scenario 14's session step and the driver's create scenarios.

### Tests for User Story 1

- [X] T027 [P] [US1] `i-test/it/CashierSuite.scala` (create cases) on the kit: a create under an
      active key with quickstart §5's body answers `200` with `FakeMerchant`'s `session_token`,
      `transactionId`, `redirectUrl`, `available_terminals` and `resolvedFrom` byte for byte, and
      `FakeMerchant.received(create)` shows one command with `subBrandId` the key's sub-brand,
      `amount` 5000, `clientIp` from the test's `X-Forwarded-For` first entry, every `customer*` field
      as sent; a body naming another `subBrandId` is forwarded under the key's (FR-003); no
      `merchantKey` → `401 UNAUTHENTICATED`, the record `key_missing` under no sub-brand,
      `FakeMerchant` not asked; a made-up key → `401 INVALID_MERCHANT_KEY`, the record `key_unknown`;
      a deactivated key (`deactivateKey`) → the same answer, the record `key_disabled` under the
      key's sub-brand (the two answers compared equal, FR-005; the records told apart, and the
      disabled key's listed under its sub-brand while the unknown key's is under none); the header spelt `MerchantKey` and `merchantkey` accepted
      (T021's verification); a body of `BODY_BOUND + 1` bytes → `413 body_too_large` before any key
      check (a bad key and a big body → `413`), the record under no sub-brand; a body that is not a
      JSON object (`[]`, `"x"`, `{` truncated) → `400 not_json`; each FR-007 row → `400
      INVALID_REQUEST` naming the field, `FakeMerchant` not asked, the record `invalid_field` with
      the field as `refusal`; `FakeMerchant` scripted `400 NO_ROUTING_TREE` → the same status and body
      returned, the record `refused` with `NO_ROUTING_TREE`; every served create's record holds
      sub-brand, `create`, `receivedAt`, the request id, 5000, `EUR`, `GB`, `served`, the ticket, and
      no `customer*`, `clientIp` or key field (FR-016, FR-018); the audit broker holds one record per
      key refusal with `ce-id` the record id and no key in it (FR-019)
- [X] T028 [P] [US1] `i-test/it/KeysSuite.scala` (SC-003, SC-004): 10,000 `merchant_key_issued` facts
      over 1,000 sub-brands through the broker; `FakeMerchant.received(keys)` is one call (the start
      refresh) and `received(create)` grows by one per served create and never by a `keys` call; 1,000
      creates under random issued keys timed at the handler's key step (`Context` exposes the step's
      duration in a test hook) with p99 under 5 ms; a `merchant_key_deactivated` fact for a key that
      just served → the next create under it `401 INVALID_MERCHANT_KEY` within 60 s (polled, the wait
      reported); a deactivation fact arriving while a create under that key is in flight
      (`FakeMerchant` delayed 300 ms) does not stop the forward, and the next create is refused (the
      spec's edge case); a `merchant_key_issued` fact after a deactivation for the same key id leaves it
      refused; `KeyRefresh` when `FakeMerchant` is stopped at start → the service starts, the warn
      line names no digest, and the consumer alone builds the index
- [X] T029 [P] [US1] Bind `cashier.feature` lines 9, 26 (both rows, `FakeMerchant` scripted
      `NO_ROUTING_TREE` and `NO_ROUTING_MATCH`), 36, 42, 48 (both rows), 59, 64, 70 and 77 (every
      row) in `i-test/features/CashierFeatures.scala`, each step over the kit's helpers; "the cashier
      is not asked" reads `FakeMerchant.received`; "the refusal is recorded" reads `records`;
      "within a minute" polls `create` against the clock; "the shared audit is read" reads the broker
- [X] T030 [US1] Confirm `ParitySuite` lists scenario 14's session step, scenario 12's step 10a and the
      driver's create recordings (active key, no key, made-up key, disabled key, every field row,
      `MerchantKey`) as failing; name in `i-test/port/Parity.scala` the divergences that hold on them
      (the missing header's `401`; each field refusal's `400 INVALID_REQUEST` with `FakeMerchant`
      recording no call; the brandless player id refused). Needs T026 for the driver's; the two
      Go-suite steps are available from T005

### Implementation for User Story 1

- [X] T031 [US1] Fill `i/api/Context.scala`: the create pipeline of research R2 over the raw bytes —
      mint the record id and `receivedAt`; `BODY_BOUND` → `413`; parse to a JSON object → `400
      not_json`; the `merchantKey` header (any case) → `401 UNAUTHENTICATED`; `Digests.ofKey` →
      `KeysView.byDigest` → none or inactive `401 INVALID_MERCHANT_KEY` after `Digests.same` on the
      row (never a string compare); `RateWindow(subBrand).take(now, RATE_LIMIT)` → served (the
      refusal is US4's: wire the answer path now, `429` with `Retry-After` and `retryAfterSeconds`,
      and let US4's tests prove it); `Fields.check` → `400 INVALID_REQUEST` naming the field; the
      request id given or minted (`UUID.randomUUID`); then `Create.run`; every exit writes
      `RequestRecord.write` once with the outcome, the sub-brand where known, the amounts where
      parsed, and the ticket on success; nothing logged but the record id, the route, the outcome and
      the sub-brand
- [X] T032 [US1] Implement `i/api/Create.scala`: `Shape.toCommand` with `Shape.clientIp(header)`,
      `MerchantClient.create` under `Bound.race(ANSWER_BOUND)`; `Answered(2xx)` → the answer returned
      as is, `Ownership(ticket/<token>).set(...)` written (US3 reads it), the record `served` with the
      ticket; `Answered(4xx)` → returned as is, the record `refused` with `merchant`'s `error`;
      `Unanswered` and `Failed` → `503 TRY_AGAIN` with `Retry-After: 5` and `retryAfterSeconds`, the
      record `unanswered`, and `late` kept with a callback that calls `RequestRecord.lostAnswer` on a
      `2xx` (the rest of FR-014 is US2's; the callback is written here so no late answer is ever
      dropped); the `Request` entity's `begin/answer/unanswered/refused` calls wired as US2's
      data-model.md table says, with `InFlight` and `Mismatch` answered as US2 specifies — written
      here, proven by US2's tests; `CashierEndpoint`'s `POST /sessions` → `Context` → `Create`;
      register `AuditFacts` and `KeyFacts` in `IngestService` if T022 left them out; T027–T029 green;
      T030's create recordings replay with only the named divergences

**Checkpoint**: a create under an issued key reaches `FakeMerchant` once as the key's sub-brand and
is answered unchanged; every refusal in R2's order answers as routes.md says and is recorded; a key
refusal is an audit record; a deactivation fact refuses the key; `cashier.feature`'s US1 scenarios
pass; scenario 14's session step replays.

---

## Phase 4: User Story 2 — A repeated request does nothing twice (P1)

**Goal**: a repeat under one request id with the same body returns the first session without a
second forward, under concurrent sends too; a different body is `409 REQUEST_ID_REUSED`; no request
id is served under a minted one that is returned; an unanswered create answers `503 TRY_AGAIN`, a
repeat is forwarded again as a fresh attempt, and the late answer becomes a lost answer the operator
reads, never the request id's answer; a refused create leaves the id free.

**Independent Test**: quickstart §4's `RepeatSuite` and `UnansweredSuite`; the driver's two repeat
recordings and the two no-request-id recordings.

### Tests for User Story 2

- [X] T033 [P] [US2] `i-test/it/RepeatSuite.scala` (SC-002, FR-010): a create under `t-1` then the
      same body under `t-1` → the same answer byte for byte, `FakeMerchant.received(create)` grown by
      one in total, the second record `repeated` with the first's ticket; 10 concurrent creates under
      `t-2` with the same body (a `FakeMerchant` delay of 200 ms so they overlap) → ten identical
      answers, one forward, one `Request` attempt, ten records (one `served`, nine `repeated`); the
      same body under `t-1` with `amountCents` 6000 → `409 REQUEST_ID_REUSED` with `transactionId`
      `t-1`, no forward, the record `request_id_reused`; a body differing only in key order under
      `t-1` → the first answer (the digest ignores order); two sub-brands each under `t-1` → two
      sessions, two forwards (FR-010's "belongs to the sub-brand"); no `transactionId` → `200` with a
      `transactionId` in the answer that `FakeMerchant` received as the command's, and the same body
      again → a second session with another id; `FakeMerchant` scripted `400 NO_ROUTING_TREE` under
      `t-3` then, the script cleared, `t-3` again → forwarded again and served (R5, correction 3);
      a repeat under `t-1` after `REQUEST_ID_RETENTION` has passed on the clock → forwarded again
- [X] T034 [P] [US2] `i-test/it/UnansweredSuite.scala` (FR-013, FR-014) on `OwnKit`: `FakeMerchant`
      told never to answer `t-4` → `503 TRY_AGAIN` with `Retry-After` at the kit's 500 ms bound (the
      elapsed time asserted under 1 s), the record `unanswered` with attempt 1, nothing queued
      (`FakeMerchant.received(create)` grows by one and stays); `t-4` again with the same body →
      forwarded again (received grows to two), attempt 2, served; then `FakeMerchant` releases the
      first create's answer → the first record becomes `lost_answer` with `answeredAt` and the
      ticket, the `Request`'s answer is still attempt 2's, `Ownership` holds attempt 2's ticket only,
      and `RequestsView.lostAnswers(subBrand)` lists the first record; `FakeMerchant` stopped → `503
      TRY_AGAIN` at once (not after the bound), the record `unanswered`; a `FakeMerchant` delay of 300
      ms (under the bound) → served normally; a late answer that is a `4xx` → the record stays
      `unanswered` and no lost answer (nothing was made)
- [X] T035 [P] [US2] Bind `cashier.feature` lines 93, 99, 104, 109, 115 and 121 in
      `i-test/features/CashierFeatures.scala`; line 121 ("submitting a session already submitted")
      runs through US3's submit route and is listed pending until T041
- [X] T036 [US2] Confirm `ParitySuite` lists the driver's two repeat recordings and the two
      no-request-id recordings as failing; name in `i-test/port/Parity.scala` the divergences (the
      first session returned and `FakeMerchant` recording one call; the `409`). Needs T026

### Implementation for User Story 2

- [X] T037 [US2] Complete the `Request` path in `i/api/Create.scala`: `Request(<subBrand>/<id>).begin(Digests.ofBody(body), recordId)`;
      `Forward(attempt)` → forward and `answer(attempt, a)` on a `2xx`, `refused(attempt)` on a `4xx`,
      `unanswered(attempt)` on `Unanswered`/`Failed`; `Answered(a)` → `a` returned, the record
      `repeated`, no forward; `InFlight` → poll `read` every 200 ms until answered (→ as `Answered`)
      or free (→ a fresh `begin`) within the handler's own `ANSWER_BOUND`, else `503 TRY_AGAIN` with
      the record `unanswered`; `Mismatch` → `409 REQUEST_ID_REUSED` naming `transactionId`, the record
      `request_id_reused`; the late callback: `Request.answer(attempt, a)` → `Late` →
      `RequestRecord.lostAnswer(now, ticket, none)` on that attempt's record; the `Ownership` is not
      written for a late answer (R6); T033–T036 green

**Checkpoint**: SC-002 holds under concurrent sends; a refused and an unanswered id are free again;
a late answer is listed as a lost answer and never returned; the US2 scenarios pass but line 121.

---

## Phase 5: User Story 3 — The session is submitted and the payment is read (P1)

**Goal**: a submit or abandon by the ticket reaches `FakeMerchant` only when an ownership names the
ticket, and the answer comes back unchanged (the payment id, the status, the provider's rejection
with its words); a payment is read with the creating sub-brand's key or the session's `cashierTicket`
header and `404` to anyone else; the payment's ownership is written on the submit's answer.

**Independent Test**: quickstart §4's `CashierSuite` submit, abandon and read cases; scenario 12's
steps 10b and 12 and scenario 13's submit and read replayed.

### Tests for User Story 3

- [X] T038 [P] [US3] Extend `i-test/it/CashierSuite.scala`: after a create, `POST
      /v1/cashier/sessions/{ticket}/submit` with `selectedTerminalEntityId` `sim-eur` → `200` with
      `FakeMerchant`'s `paymentId` and `status` as is, `FakeMerchant.received(submit)` showing the
      token and body unchanged, the record `served` under the create's sub-brand with the ticket and
      the payment id, `Ownership(payment/<id>)` set and `Ownership(ticket/<t>)` carrying the payment
      id; a second submit → the first's answer from `FakeMerchant` (received grows to two; the record
      `served`); `sim-gbp` → `400 terminal_not_found` passed through; a scripted `400
      PAYMENT_REJECTED` with `message` → passed through with the message; a made-up ticket → `404
      SESSION_NOT_FOUND`, `FakeMerchant` not asked, the record `not_found` under no sub-brand; a
      scripted `404` from `FakeMerchant` → passed through; `abandon` on an owned ticket → `200` with
      the read, forwarded once; `GET /v1/payments/{id}` with the creating key → `200` with
      `FakeMerchant`'s read as is, the record `served` with the payment id; with another sub-brand's
      active key → `404 PAYMENT_NOT_FOUND` and `FakeMerchant` not asked (compared equal to the answer
      for a made-up id under the owner's key); with `cashierTicket` of the session → `200`; with a
      `cashierTicket` of another session → `404`; with neither header → `401 UNAUTHENTICATED`; with a
      bad key → `401 INVALID_MERCHANT_KEY`; a submit body over `BODY_BOUND` → `413`; a submit, an
      abandon and a read each take a `RateWindow` count under the owner (read with `RateWindow.read`);
      on `OwnKit`, `FakeMerchant` told never to answer a submit → `503 TRY_AGAIN` at the bound, the
      record `unanswered`, then the answer released → the record `lost_answer` with the `paymentId`
      the cashier made and listed by `RequestsView.lostAnswers`, and no `Ownership(payment/…)`
      written (FR-014's "the session or payment the cashier made")
- [X] T039 [P] [US3] Bind `cashier.feature` lines 14, 21, 121, 127, 132, 137, 142 and 147 in
      `i-test/features/CashierFeatures.scala`; line 17's "the gateway is asked to initiate" and line
      18's "recorded as awaiting redirect" are `FakeMerchant`'s script answering what merchant would
      (the gateway and the payment are 004's; the step asserts the submit was forwarded and the
      answer carried the status), named so in the binding
- [X] T040 [US3] Confirm `ParitySuite` lists scenario 12's 10b and 12, scenario 13's submit and read,
      scenario 14's submit and read, and the driver's submit, double-submit, `sim-gbp`, made-up token,
      own-key read and other-sub-brand read as failing; name in `i-test/port/Parity.scala` the
      divergences (the other sub-brand's `404`; the made-up token with `FakeMerchant` recording no
      call). Needs T026 for the driver's

### Implementation for User Story 3

- [X] T041 [US3] Implement `i/api/Ticket.scala` (submit and abandon: `Context`'s body bound and parse
      for the submit, then `Ownership(ticket/<t>).read` → none `404 SESSION_NOT_FOUND` recorded under
      no sub-brand, else the owner's `RateWindow.take`, then `MerchantClient.submit`/`abandon` under
      `Bound.race` with the same `TRY_AGAIN` path, and a late `2xx` submit answer →
      `RequestRecord.lostAnswer(now, ticket, paymentId)` on the submit's record with no ownership
      written (there is no `Request` entity for a submit: merchant makes it idempotent by the token);
      a `2xx` submit within the bound writes `Ownership(payment/<id>)` and the ticket's `paymentId`;
      the record under the owner with the ticket and the payment id)
      and `i/api/Read.scala` (`merchantKey` → the key step of `Context` → `Ownership(payment/<id>).read`
      must name that sub-brand; else `cashierTicket` → `Ownership(ticket/<t>).read.paymentId` must
      equal the id; neither header `401`; a mismatch or none `404 PAYMENT_NOT_FOUND` with
      `FakeMerchant` not asked; then the owner's take and `MerchantClient.read` passed through); fill
      `CashierEndpoint`'s submit and abandon routes and `PaymentsEndpoint`'s `GET /{paymentId}`;
      T038–T040 green; T035's line 121 bound

**Checkpoint**: the deposit path a sub-brand sees is whole (create, submit, read; abandon); scenario
12, 13 and 14's public steps replay; `cashier.feature` passes but the three rate scenarios.

---

## Phase 6: User Story 4 — A sub-brand that floods is slowed, and the rest are not (P2)

**Goal**: a sub-brand over its minute's limit is answered `429 TOO_MANY_REQUESTS` with the seconds to
wait and `FakeMerchant` receives only what was served; another sub-brand is served in the same
minute; an operator raises one sub-brand's limit and the raise holds; each rate refusal is an audit
record naming the sub-brand.

**Independent Test**: quickstart §4's `RateSuite`; `cashier.feature` lines 152, 159 and 164.

### Tests for User Story 4

- [X] T042 [P] [US4] `i-test/it/RateSuite.scala` (FR-015, SC-005) on `OwnKit` with the clock held in
      one minute: 700 creates from one sub-brand (same body, distinct request ids) → 600 `200` and
      100 `429 TOO_MANY_REQUESTS` with `Retry-After` and `retryAfterSeconds` equal to the seconds to
      the next minute on the clock, `FakeMerchant.received(create)` grown by exactly 600, 100 records
      `rate` under the sub-brand, 100 audit records with `actorKind = sub-brand`; a create from another
      sub-brand in the same minute → `200`; `advance(1 minute)` → the first sub-brand served again;
      `asOperator` `PUT /v1/ingest/sub-brands/{subBrand}/rate {"perMinute": 6000}` → `204`, `GET` →
      `{perMinute: 6000, default: 600}`, then 700 creates → 700 served; `GET` for a sub-brand never
      raised → `{perMinute: 600, default: 600}`; `PUT` with `0` or a negative → `400`; `asStaff` →
      `403`; a submit, an abandon and a read count toward the owner's minute (one of each after 599
      creates → the next create `429`); while the first sub-brand is flooding (700 sent concurrently),
      the other sub-brand's create answers within the kit's `ANSWER_BOUND` (SC-005)
- [X] T043 [P] [US4] Bind `cashier.feature` lines 152, 159 and 164 in
      `i-test/features/CashierFeatures.scala`; "the installation's rate limit of 600" is the kit's
      `RATE_LIMIT`; "an operator raised to 6000" is the `PUT` as `asOperator`

### Implementation for User Story 4

- [X] T044 [US4] Complete the rate step in `i/api/Context.scala` (`Refused(waitSeconds)` → `429` with
      `Retry-After: <waitSeconds>` and `retryAfterSeconds`, the record `rate` under the sub-brand,
      `FakeMerchant` not asked; `Fields.check` runs after the take, R2) and implement
      `i/api/Rate.scala` for `IngestEndpoint`'s `GET`/`PUT /sub-brands/{subBrand}/rate`
      (`RateWindow.read` → `{perMinute: limit.getOrElse(RATE_LIMIT), default: RATE_LIMIT}`;
      `setLimit(perMinute)` for a positive integer, else `400`); T042 and T043 green

**Checkpoint**: a flood is refused at its own entity and the neighbour is served; the raise holds
across the minute; `cashier.feature` passes whole.

---

## Phase 7: User Story 5 — An operator sees what sub-brands sent (P2)

**Goal**: a payments operator lists a sub-brand's requests by time and by request id, each with its
time, request id, amounts, country, outcome and ids and nothing that names a player; lists the lost
answers with what the cashier made and marks one seen; an operator without the payments role is
refused.

**Independent Test**: quickstart §4's `OperatorSuite`; `requests.feature`.

### Tests for User Story 5

- [X] T045 [P] [US5] `i-test/it/OperatorSuite.scala` (FR-017, SC-007): after two served creates, one
      `key_disabled` refusal under `acme`'s deactivated key and one `FakeMerchant` `NO_ROUTING_MATCH`
      refusal for sub-brand `acme` (the four creates of `requests.feature` line 6), plus one
      `key_unknown` refusal under a made-up key, `asOperator` `GET /v1/ingest/sub-brands/acme/requests`
      → `requests[]` of four (the unknown key's refusal is under no sub-brand and absent), newest
      first, each with `recordId`,
      `receivedAt`, `route`, `requestId`, `amount`, `currency`, `country`, `outcome`, `refusal`,
      `ticket`, `paymentId`, `attempt`, and no other field (the response's key set asserted equal to
      routes.md's); `?from&to` bounds by `receivedAt`; `?limit=2` → two; `?requestId=t-1` → every
      attempt under `t-1` oldest first (after T034's two attempts: the `lost_answer` and the `served`);
      a request found by `?requestId` within 2 s of being served (polled, the wait reported, SC-007);
      `GET /v1/ingest/lost-answers` → the lost answer of T034's shape with `lostAnswer.ticket`,
      `?subBrand=acme` filters; `POST /v1/ingest/lost-answers/{recordId}/seen` → `204` and the record
      leaves the list; `seen` on a record that is not a lost answer → `409`; on an unknown record →
      `404`; `asStaff` on each route → `403`; no token → `401`; every answer searched for the markers
      of `LeakSuite`'s create and holds none
- [X] T046 [P] [US5] Bind `requests.feature` lines 6, 12 and 18 in `i-test/features/RequestsFeatures.scala`;
      "whose roles do not name the shared payments" is `asStaff`

### Implementation for User Story 5

- [X] T047 [US5] Implement `i/api/Operator.scala` for `IngestEndpoint`'s `GET
      /sub-brands/{subBrand}/requests` (`RequestsView.bySubBrand` with `from` and `to` defaulting to
      the last 24 hours and `limit` to 100, capped at 1,000; with `requestId`, `byRequestId`), `GET
      /lost-answers` (`RequestsView.lostAnswers(subBrand)`) and `POST /lost-answers/{recordId}/seen`
      (`RequestRecord.read` → `404` none, `409` not a lost answer, else `markSeen` → `204`); the
      response fields exactly routes.md's; T045 and T046 green

**Checkpoint**: `requests.feature` passes; every record an operator reads is data-model.md's field
set and nothing more; a lost answer is found, read and marked.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: the leak check whole, the manifest and a kind run, the gateway's forwarded-for
verified, the README and quickstart proven, research closed, findings and questions raised where
they belong.

- [X] T048 [P] Fill `i-test/it/LeakSuite.scala` (T025): the marker create served, a marker submit,
      a marker read, a marker create refused for its key, one refused for rate, one unanswered then
      lost; every table of the service's database, every view row, every `AuditRecord`, every
      operator answer and the captured log searched for every marker (each `customer*` value, the
      forwarded-for address, the raw key, the key's digest in the log only); none found; SC-006 holds
- [X] T049 [P] Add `i-test/it/DurabilitySuite.scala` on `OwnKit`: a create served, a key issued, a
      rate raised, a lost answer listed; the kit's runtime restarted over the same database; the
      repeat under the request id still returns the first session, the key still admits, the raise
      still holds, the lost answer is still listed, and `KeyRefresh` on the restart issues nothing new
      (`FakeMerchant.received(keys)` grows by one and the view's rows are unchanged)
- [X] T050 [P] Extend `shared/payments/manifest.json`'s `ingest` entry with `env` (research R13:
      `MERCHANT_SERVICE` `merchant`, `ANSWER_BOUND` `10s`, `RATE_LIMIT` `600`, `BODY_BOUND` `65536`,
      `REQUEST_ID_BOUND` `128`, `REQUEST_ID_RETENTION` `24h`, `RECORD_RETENTION` `90d`); `just brands
      validate shared/payments/manifest.json` green; `tools/brands/src/test/.../RenderSuite.scala`
      gains an assertion that the rendered `ingest` service carries the staff issuer's `ANKKA_AUTH_*`
      variables like `merchant` and `psp-gateway`; `just test` green
- [X] T051 [P] Run quickstart §5 locally: `just merchant-run` (004's §7) and `just ingest-run`; a
      sub-brand and a key through merchant's backoffice routes; the curl of §5 and its three repeats
      answer as §5 says; `ankka local console` shows the `RequestRecord` rows, the `Request`
      answered, the `RateWindow` count; record the run's date in `services/ingest/README.md`
      *Not done as written (2026-10-22)*: `merchant`'s operator routes need dev-kind's Keycloak, whose cluster
      was down. Run instead against `FakeMerchant` on 9000 (`it/FakesMain.scala`): §5's four answers as
      expected; `ingest-run` gained the staff issuer variables it needed to start.
      *Done as written 2026-10-25* (ankka 0.13.0): the real `merchant` on 9000 beside its fakes, dev-kind's
      Keycloak forwarded; a key through `POST /v1/merchants/{id}/keys`; the refresh read `1 keys`; §5's four
      answers; the three records through the operator route. `ankka local console`'s entity read takes no
      id with a `/` (`Request`, `RateWindow`), so the records were read through the route and the window's
      count was not read.
- [X] T052 Run quickstart §6 on kind (`--context kind-ankka` only, never production): `just brands
      apply payments --context kind-ankka`; `ingest` running beside `merchant` and `psp-gateway`;
      `ankka services logs ingest` shows the refresh's key count and no key value; a create through
      the gateway with a key merchant issued answers as §5 did. **Verify at task time** (research
      R11): that the command `FakeMerchant`'s stand-in (`merchant`'s own log, or a one-off read of
      the session's `clientIp` through merchant's operator routes) received carries the caller's
      address from `X-Forwarded-For`; record the result in research R11; if the gateway does not
      forward it, `clientIp` is empty (the fallback) and the finding goes to ankka (finding 5).
      Needs T050; a kind cluster
      *Done 2026-10-25* on `kind-ankka` at ankka 0.13.0 (the services moved from 0.11 for it: the
      platform runs 0.12–0.13 only). The three services Ready; §5's four answers through the gateway
      (200, the same session, `409 REQUEST_ID_REUSED`, `401 UNAUTHENTICATED`); `KeyRefresh` logs
      `refreshed from merchant: 1 keys` after a restart (the first start ran before `merchant` answered
      and fell back to the facts, as designed); the raw key in neither service's log. R11 verified and
      `merchant`'s dropped `clientIp` fixed (research R11, finding 7). The `eitheror` realm was imported
      by hand: the brands tool imports it on a brand's apply, and only `payments` was applied.
- [X] T053 [P] Fill `services/ingest/README.md` (the purpose, the routes by audience with a pointer
      to contracts/routes.md, the settings table, `just ingest-test`/`ingest-it`/`ingest-run`, the
      recording run, what is verified where); `tests/README.md` and `tests/original/README.md` name
      the `ingest-driver`; `specs/006-ingest/quickstart.md` re-read against what T051 and T052 did and
      corrected where the commands drifted
      *The driver's README entries made with T004 and T026 (2026-10-24).*
- [X] T054 Close research: R11's three verifications written with their result and date (T021,
      T022, T052); *Findings for other specs* raised where they belong — finding 3 as a note in the
      frontend's base-URL list the repository keeps (or `specs/001-*/` where base URLs are listed);
      finding 4 closed by T003 (or left open under the fallback); finding 5 drafted as the ankka
      items in `specs/006-ingest/research.md` *For ankka*; *Open for Sheldon* drafted as the message
      for Sheldon with the three decisions and their alternatives; `plan.md`'s *Divergences* checked
      against `Parity.divergences` (`CoverageSuite` green); `just features` green; `just ingest-test`
      and `just ingest-it` green
      *Open (2026-10-22)*: R11's forwarded-for check waits on T052; finding 3 stays in research (no base-URL
      list exists in the repository yet); the rest is written.
      *Closed 2026-10-25*: R11's three verifications dated (the forwarded-for one on kind, with
      `merchant`'s dropped `clientIp` fixed as finding 7 and the first-entry trust left as a decision);
      *For ankka* checked against 0.13.0 (all still open) and the local entity read's `/` added; finding 3
      stays in research; `just features` (0 findings, 1051 scenarios), `just ingest-test` (185, which
      holds `ingest-it`'s suites) and `merchant`'s suites (254) green on 0.13.0.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)**: none. T001 first (T002's recipes and CI name its paths); T002–T005 together after
  it. T003 publishes the contract T018 depends on; its fallback keeps the phase moving.
- **Foundational (2)**: after Setup. Blocks every story. T006–T009 together; T010–T013 together,
  then T014, then T015; T016 and T017 together, then T018 (after T003 or its fallback); T019 and
  T020 together after T006; T021 after T014, T015, T019, T020; T022 after T021; T023–T025 together
  after T021. T026 needs T004, the original's kind stack and Docker, and gates T030's, T036's and
  T040's driver recordings.
- **US1 (3)**: after Foundational. The MVP. T027–T029 together; T031 after T007, T008, T014, T015;
  T032 after T031, T009, T019; T030 after T032 (the two Go-suite steps) and T026 (the driver's).
- **US2 (4)**: after US1 (T037 completes T032). T033–T035 together; T037 after T033 and T034;
  T036 after T037 and T026.
- **US3 (5)**: after US1 (the ownership T032 writes; the `Context` key step). T038 and T039
  together; T041 after T038; T040 after T041 and T026. T035's line 121 passes with T041.
- **US4 (6)**: after US1 (T044 completes T031's rate step). T042 and T043 together; T044 after
  them. Can run beside US2 and US3.
- **US5 (7)**: after US2 (the lost answer T034 makes) and US1. T045 and T046 together; T047 after
  them. Can run beside US3 and US4.
- **Polish (8)**: T048 after US1–US5 (every outcome kind in the leak check); T049 after US2 and US4;
  T050 beside anything; T051 after US1–US3; T052 after T050 and T051; T053 after T051 and T052;
  T054 last.

### Platform gates (plan.md Phase 0, research R11)

- The header's case (T021), the bearer on a public route (T022) and the gateway's `X-Forwarded-For`
  (T052) are each verified against the pinned 0.11 runtime or the kind installation when the task
  is taken, and each has its fallback in the task. Nothing waits on an unreleased ankka spec; the
  operator routes use the contract's `Routes.paymentsOperator()` as 005's outcome routes do.
- `contracts/merchant.md` is 004's `commands.md`, which merchant already serves to
  `Callers.service("payments", "ingest")`; nothing in merchant changes for this spec but T003's
  hoist, which changes no behaviour.

### Parallel Opportunities

- Phase 1: T002–T005 together after T001.
- Phase 2: T006–T009 together; T010–T013 together; T016 and T017 together; T019 and T020 together;
  T023–T025 together; T026 the moment the original is up.
- US1: T027–T029 together; T031 → T032 → T030.
- US2, US3 and US4 can run beside each other once US1 is done; US5 once US2 is.
- Polish: T048, T049, T050 together; T051 → T052 → T053 → T054.

---

## Parallel Example: User Story 1

```bash
# tests first, together:
Task: "CashierSuite (create cases) in i-test/it/CashierSuite.scala"
Task: "KeysSuite in i-test/it/KeysSuite.scala"
Task: "Bind cashier.feature 9/36/42/48/59/64/70/77 in i-test/features/CashierFeatures.scala"
# then in order:
Task: "api/Context.scala — the create pipeline in R2's order"
  → "api/Create.scala — shape, forward under the bound, record, ownership"
  → parity (scenario 14's session step, scenario 12's 10a, the driver's create recordings)
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phases 1 and 2, with T026 started the moment the driver builds and the original's stack is up
   (it needs nothing of this service and gates three parity tasks).
2. Phase 3: a create under an issued key reaches `FakeMerchant` once and is answered unchanged;
   every refusal of R2's order answers and is recorded; a key refusal is audited; a deactivation
   refuses. The US1 scenarios green; scenario 14's session step replays.
3. Stop and run quickstart §3 and §4 for the US1 suites.

### Incremental delivery

1. US2: the request id as an idempotency key, the unanswered create and the lost answer. After it
   SC-002 holds and the one guarantee the original lacked on the money path (a retry books once) is
   in place.
2. US3: submit, abandon and read by ticket or key; after it every Go-suite public step replays and
   the frontend could move its base URL.
3. US4 and US5 beside each other by a second pair of hands: the rate and the operator. After them
   SC-005 and SC-007 hold.
4. Polish, then quickstart §5 and §6 on kind before the manifest is applied anywhere real.

---

## Notes

- Every parity task replays recordings that must exist (T005's three, T026's driver run); do not
  write a recording by hand. The original's behaviour where the plan diverges is recorded as it is
  and declared in `Parity.divergences`, never corrected in the recording.
- No customer field, network address or key in any state, view row, log line, span, audit record or
  operator answer: T013's state types have no field to hold one; T025/T048 search for them; the
  log prints the record id, the route, the outcome and the sub-brand and nothing from the body.
- The check order is R2's and `CashierSuite` holds it: a big body under a bad key is `413`; a
  malformed body under a flooding sub-brand is `429`; a bad field under a fresh sub-brand is `400`
  and the take still counted.
- The answer bound is `Bound.race`, never the client timeout: `ANKKA_SERVICE_CLIENT_TIMEOUT` stays
  at its 30 s default in every kit and manifest so a late answer can still be caught (T034).
- The features and the parity suite run on one kit; scenarios 004 binds through merchant's own
  routes are bound here again through the edge, with `FakeMerchant` answering as merchant would.
- A recording run is made against a local original only; `kubectl` is never run bare (the default
  context is production); `--context kind-ankka` on every kind command (T052).
- Commit after each task or logical group; stop at any checkpoint to validate the story alone.
