# Research: public-api

Decisions for [plan.md](plan.md), each with what was read. Paths under `original/` are
`~/Documents/projects/eitheror/eitheror-platform/`; paths under `ankka/` are
`~/Documents/projects/thinkmorestupidless/ankka/` at tag `v0.16.0`.

## R1. ankka 0.16.0, Scala 3.9

- **Decision**: ankka **0.16.0**, Scala 3.9.0, sbt 1.12.9, the contract evicted to 0.16.0 as specs
  014, 015 and 018 do.
- **Rationale**: `Personal` (ankka 042), which FR-023 and FR-025 need, exists from 0.16.0 (spec 015
  R2). At 0.16.0 a route takes at most two path parameters (`HttpEndpoint.scala`, arities 0 to 2) and
  no two endpoints share a prefix (`HttpServer.scala:388-397`). The contract's deepest path,
  `/v1/players/{playerId}/sessions`, has one.
- **Alternatives**: 0.13 (no `Personal`); 0.16.2 (lifts the parameter limit, which nothing here
  needs, and no service here pins it yet).

## R2. The contract: OpenAPI served from the committed file, and the drift check without GraphQL

- **Decision**: the original's REST API has no GraphQL left (it was deleted on 2026-10-22, original
  `a65612927`), so no GraphQL library is used. The planning note to compare a Caliban or Sangria
  rendering against committed SDL does not apply. The contract is OpenAPI 3.1 and is handled as data:
  - `contracts/original/public-api-v1.openapi.yaml` is the original's file, copied verbatim
    (807 lines, original `specs/255-casino-public-rest-api/contracts/`).
  - `services/public-api/src/main/resources/public-api-v1.openapi.yaml` is the served contract: the
    copy with FR-002's amendments and nothing else ([contracts/amendments.md](contracts/amendments.md)).
  - At start the service parses the YAML with `jackson-dataformat-yaml` into a `JsonNode`, renders
    it once as JSON for `GET /v1/openapi.json`, and refuses to start when the file is unreadable or
    `components.parameters.limit.schema.maximum` is not 100 (as original `src/contract/load.ts:7-32`).
  - `GET /v1/docs` serves the original's `reference.html` with the contract embedded (original
    `src/contract/serve.ts`, `9a1f13cf9`).
- **The drift check (FR-005)** is four suites in `services/public-api`, run by `just public-api-test`
  in CI:
  1. `ContractSnapshotSuite`: the served JSON deep-equals the committed YAML, and the endpoint's
     route table equals the contract's operations, 22 of them (original `contract/snapshot.test.ts`).
  2. `ContractAmendmentSuite`: the committed contract and the original's copy differ only at the
     JSON paths [contracts/amendments.md](contracts/amendments.md) lists (FR-001, FR-002). This is the
     guard that "only the credential changed".
  3. `ContractResponsesSuite`: every route, at every status the contract documents for it, is called
     through the kit, and each body is validated against its schema with
     `com.networknt:json-schema-validator` (draft 2020-12, as OpenAPI 3.1's dialect), already used by
     `services/game-provider-simulator` (original `contract/responses.test.ts`, `validator.test.ts`).
  4. `ClientGenerationSuite` is not ported: it compiled a TypeScript client with
     `openapi-typescript`. Its intent (a client can be generated) is met by validating the document as
     OpenAPI 3.1 with `swagger-parser` (`io.swagger.parser.v3:swagger-parser`), which fails on an
     unresolvable `$ref`.
- **Rationale**: the original's guarantee is the file, served verbatim and validated against
  responses, not a schema derived from code. Holding the same file makes byte-for-byte parity a
  comparison, not a rendering.
- **Alternatives**: deriving the document from Scala types with tapir (renders a different
  document; the contract would follow the code, the reverse of FR-005); a hand-written JSON copy (two
  files to keep equal).

## R3. Endpoints, prefixes and admission

- **Decision**: five endpoints, one prefix each.

  | Prefix | Routes | Admission |
  |---|---|---|
  | `/v1` | the 22 contract operations, `/v1/docs`, any other method on them | `Acl.AllowAll`; the handler checks the access key (R4) and shapes every refusal |
  | `/health` | liveness, as the original's | `Acl.AllowAll` |
  | `/access-keys` | `/access-keys/{brand}`, `/access-keys/{brand}/{keyId}` and its actions (contracts/routes.md) | `Routes.operatorHolding(slug, "api_keys")` with `Routes.brandSegment` |
  | `/public-api` | `/public-api/{brand}/status` | `Routes.operator(slug)` |
  | `/v1` (its own `withAcl`) | `/casino/brands/{brand}/public-api/erasure/players/{player}` | `Routes.playersOnly`, as every brand service (spec 015's route; see *Found during implementation*) |

- **Rationale**:
  - The contract's paths carry no brand (FR-001). The brand is the project's, and a key exists only
    in the project that made it, so a key of another brand is unknown here. This is constitution III's
    public-route case: "reached by any caller as the internet reaches them, admitted by the route's
    own ... key".
  - Every refusal on `/v1` must be the contract's `{error}` JSON. An ACL refusal is answered by ankka
    before the handler as `{"status","error"}` with `WWW-Authenticate` (`HttpServer.scala:836-854`),
    so the ACL admits all and the handler refuses, with `Respond(Bytes("application/json", …), status,
    headers)` (`modules/http/.../responses.scala:33`), as `services/psp-gateway/.../api/Refusal.scala`
    does.
  - A non-GET method on a contract path is `405 invalid_request` (original `router.ts:138`). Each path
    is declared once per method used (`get`, plus `post`, `put`, `patch` and `delete` answering 405),
    as `services/attribution/.../WebhooksEndpoint.scala:24-25` declares two methods on one path.
- **Alternatives**: an ankka ACL that verifies keys (none exists for a key the service issues);
  `/{brand}/v1/...` paths (break FR-001).

## R4. Access keys

- **Decision**:
  - **Format**: `ak_<keyId>_<secret>`. `keyId` is 16 lower-case base32 characters (80 bits) and names
    the key's entity. `secret` is 32 bytes from `SecureRandom`, base64url without padding (FR-017).
    "The start of the key" in lists is `ak_<keyId>`.
  - **Stored**: the hex SHA-256 of the whole key, never the key, as `services/merchant/.../keys/Keys.scala`
    does for cashier keys. The secret has 256 bits of entropy, so a slow hash adds nothing.
  - **`AccessKey`**: an event sourced entity per key id. Events: `KeyCreated`, `KeyChanged` (name,
    fields, rate, expiry), `KeyDisabled`, `KeyEnabled`, `KeyDeleted` (the digest dropped from state).
    Each carries the operator. A query `admit(digest, now)` answers `Admitted(fields, rate)` or a
    refusal code. The digest comparison is `MessageDigest.isEqual`.
  - **Admission on each request**: parse the header; a malformed key is `key_invalid` without a
    lookup. Otherwise call `AccessKey.admit` on the key id; an empty entity is `key_invalid`, exactly
    as a wrong secret. The entity is read on every request, so a change applies from the next one
    (FR-019); nothing caches it.
  - **Refusal statuses**: `key_missing` 401, `key_invalid` 401, `key_expired` 401, `key_disabled` 403.
    These follow the original's statuses for the codes they replace (`jwt_missing`, `jwt_*`,
    `jwt_expired`, `grant_disabled`; original `server.ts:59-71`).
- **Rationale**: the key id inside the key makes admission one consistent entity read. The
  merchant's digest-keyed index is kept by a consumer, so a disable reaches it eventually; FR-019 asks
  for the next request.
- **Alternatives**: a digest-keyed index (eventual, as above); bcrypt (no gain at this entropy, and a
  slower request); keys in the realm (needs a realm administration credential, which 001 FR-021
  forbids).

## R5. Rate and last use

- **Decision**: `KeyWindow`, a key value entity per key id: a fixed 60-second window, `take(now,
  limit)` answering `Allowed` or `Over(retryAfterSeconds)` with `retryAfter = max(1, ceil(remaining /
  1000))` (original `partnerQuota.ts:47-87`). It also records `lastUsedAt`. One write per request. A
  view over it, `KeyUse`, gives the key list its last use.
- **Rationale**: an entity counts across instances (FR-034; the original counted per replica) and
  keeps keys apart (SC-006). It is `services/ingest`'s `RateWindow` (spec 006 R4).
- **Alternatives**: in-process counters (per instance); folding the window into `AccessKey` (one
  journal event per request in the entity every admission reads).

## R6. The audit

- **Decision**:
  - **Per request**: after the answer, the endpoint calls `KeyLog`, an event sourced entity per key
    id, with `Served(operation, outcome, status, durationMs, players: Vector[(id, fields)], at)`. An
    `AuditConsumer` over `KeyLog` (`contract/.../audit/AuditConsumer.scala`) publishes:
    - one record for the request: actor and subject `access_key/<keyId>`, action
      `public_api.read`, outcome, and `changes` naming the operation, the status, the duration and
      each served player's field names (never a value);
    - one record per player whose identifying fields were served: subject `player/<id>`, actor
      `access_key/<keyId>`, action `public_api.identifying_fields_served`, the field names in
      `changes`.
  - **Key changes**: the same consumer maps `AccessKey` events to records with the operator as actor
    and `access_key/<keyId>` as subject; `changes` name the fields changed (FR-021). No event carries
    the key or its digest.
  - Refusals before a key id is known (`key_missing`, a malformed key) are not recorded: there is no
    key to name. The original likewise audits a refusal only "once the token named a partner"
    (`server.ts:181-193`).
- **Rationale**:
  - ankka has no publish from an endpoint; only a consumer publishes (`ankka/docs/build/topics.md:12`).
  - The shared audit pseudonymises subjects per kind and answers a player's history by subject (spec
    018). A record per player under `player/<id>` makes FR-037's subject access request a query the
    audit already serves; a player id buried in a request record's `changes` would not be found.
  - `durationMs` goes in `changes`, because `AuditRecord` has no duration field
    (`contract/.../facts/Others.scala:233-238`).
- **Cost**: two entity writes per request (R5, R6). At the default rate that is 20 writes a second
  per busy key. The `KeyLog` journal grows by one event per request; snapshots every 500 events keep
  recovery short, and the journal's size is a finding for ankka (an endpoint that can publish would
  need no journal).
- **Alternatives**: one record per request only (fails FR-037 against spec 018's pseudonyms); logs
  (the original's Loki line, which spec 018 replaces).

## R7. Records: intake and views

- **Decision**: reporting's three layers (spec 015 R3), sized to what the contract serves.
  - **Consumers**: one per topic (`casino.players`, `casino.sessions`, `casino.rounds`,
    `money.transactions`, `casino.withdrawals`, `casino.rewards`) with `Lenient` serializers and
    `StartFrom.Earliest`, routing each fact to the record it changes.
  - **Records**: a key value entity per record kind, keyed by the brand's id: `PlayerRecord`,
    `SessionRecord`, `RoundRecord`, `BetRecord`, `TransactionRecord`, `BonusAwardRecord`. Each keeps
    the last 64 fact ids it applied, `FactId.of(topic, fact)` (`services/reporting/.../intake/FactId.scala`),
    so a replayed fact changes nothing (FR-008).
  - **Views**: one keyed view per kind over its entity, with the row in the contract's shape less the
    formatting: minor units, enum words, `Personal` fields.
  - **Reads**: by id is `rows.get(id)`. A child collection is
    `rows.ordered(jsonText("playerId") = $p, order = (createdAt, id), limit, offset)` with
    `rows.count(...)` for the total (`ankka/docs/build/views.md:1016-1146`). Players are ordered by
    `(registeredAt, id)`; games by code.
  - **Indexes**: an expression index per child order, `(payload->>'playerId', payload->>'createdAt',
    payload->>'id')`, created by the service at start (`CREATE INDEX IF NOT EXISTS` on
    `ankka_view_<id>`; no service here creates one yet, so it is verified at task time, R16). The
    shared audit already pages a view with `view.ordered(...)` (`services/audit/.../store/Records.scala:34`).
- **Which facts make which record**: [data-model.md](data-model.md), from the field map in
  [contracts/fields.md](contracts/fields.md). Seven fields have no fact today; their producers are
  amended on this branch, additively ([contracts/facts.md](contracts/facts.md) G1–G7; decided by
  Trevor, 2026-11-12), as spec 015 R11 amended five producers.
- **Rationale**: a topic view keeps one row per `ce-subject` (`ankka/docs/build/topics.md:254-257`),
  and these topics are keyed by player or by player and currency. A transaction, a bet or a round
  each need a row of their own.
- **Alternatives**: event sourced records (no need: a record's latest state is what is served, and
  a key value entity's change stream gives the latest value at least once); a view per topic plus a
  join at read (no cross-view joins in ankka, `views.md:2642-2645`).

## R8. Statistics and snapshots

- **Decision**:
  - **The run**: a recurring timer `compute-stats` at minute 5 of every hour. It streams the
    `Rounds`, `Sessions` and `Transactions` views for the 30 days to now (`askStream`) and folds them
    in memory into per-game and per-player figures for 1, 7 and 30 days (the rules of FR-028 and
    FR-029; [data-model.md](data-model.md)).
  - **Snapshots, two slots as the original**: `StatsPointer`, a key value entity, holds the current
    slot (`a` or `b`) and per slot its snapshot id, `computedAt` and state (`building`, `ready`). The
    run writes the slot that is not current: one `PlayerFigures` row per player, keyed
    `<slot>/<playerId>`, carrying the snapshot id, the three windows and a precomputed rank per
    (period, sort, currency), `rk_<period>_<sort>[_<ccy>]`; and one `GameFigures` row per game, keyed
    `<slot>/<gameCode>`. Then it marks the slot `ready` and makes it current. The previous slot stays
    readable until the next run starts writing it, an hour later, which is more than FR-031's 30
    minutes (original `playerStatsPointer.ts:24-53`, `PlayerStatsRecompute.scala:48`).
  - **Reads**: a ranking is `ordered(snapshot = $s AND rk_… IS NOT NULL [AND status IN …], order =
    rk_…, limit, offset)` on `PlayerFigures`. A rank is a JSON number, so it orders numerically. A
    named snapshot that is in neither slot, or whose slot is `building`, is `410 snapshot_expired`.
    Game rankings read `GameFigures` the same way, at most 100 rows and no offset.
  - **A failed run** leaves the pointer unchanged (FR-032).
  - **Staleness**: `/public-api/{brand}/status` reports `computedAt` and `stale` when it is more
    than three hours old. Each run logs `public_api_stats_stale brand=<slug>` at WARN while stale, for
    the installation's log-based alert (spec 001 FR-034). ankka has no alerting of its own.
- **Rationale**: SC-008 (1 million rounds in five minutes) is a stream of about 1 million view rows
  and an in-memory fold of a few hundred megabytes at most. Unique players per window can't be summed
  from buckets, so the fold reads rows, not hourly sums. Two slots bound the rows kept to two runs.
- **Alternatives**: incremental hourly buckets (distinct counts don't sum); a workflow (one fold, no
  steps worth recovering; a failed run is simply retried by the next hour).

## R9. The games catalogue

- **Decision**: `game-provider` has no route that serves the available games with their details
  to a sibling service: the full listing is player-class, the operator listing has few details, and
  the one service-class read admits `rewards` alone ([contracts/fields.md](contracts/fields.md) §4).
  This branch adds `GET /v1/casino/brands/{brand}/game-provider/games` to its `BrandsEndpoint` under
  `Routes.serviceOf("rewards", "public-api")` ([contracts/facts.md](contracts/facts.md) G8). The
  service reads the brand's available games from it, applies the original's release-date gate
  (`browse` gates on deprecation, not release), and holds them in a
  cache for at most five minutes, loading once at a time and serving the last value while
  revalidating. A failed load is not cached (original `src/games/ttlCache.ts`). This is a command
  answered in the request path by the owner (constitution IV), not a read of its store.
- **Rationale**: no topic carries the catalogue, and the catalogue is `game-provider`'s (spec 003).
  FR-027's five minutes is the original's cache.
- **Alternatives**: a catalogue topic from `game-provider` (an amendment for a five-minute
  staleness the cache already meets).

## R10. Personal data and erasure

- **Decision**: `PlayerRecord` and the `Players` view hold the identifying fields as ankka
  `Personal` values under `player/<id>`, converted from the contract's marker as each fact is read
  (spec 015 R10, spec 014 R15). The descriptor declares `"personal": true` and serves the erasure route
  (`Routes.playersOnly`). A field is served only when the key allows it and the value is
  `Personal.Present`; `Erased` is absent (FR-024, FR-025). The ranking rows carry no personal field
  (FR-033).
- **Rationale**: constitution V; ankka 042 reduces an erased subject's fields within 60 seconds
  (`ankka/docs/build/personal-data.md:195-312`).

## R11. The request budget

- **Decision**: each `/v1` request runs under a five-second deadline. On expiry it answers `503
  unavailable` (FR-035; original `REQUEST_TIMEOUT_MS = 5000`). View reads use the `…Async` variants
  under the deadline. A view read that fails is also `unavailable`.

## R12. Money, times and enums

- **Decision**: `contract.money` gives each currency's exponent. A minor-unit `Long` becomes a
  string with two decimals by `BigDecimal(units).movePointLeft(exponent).setScale(2, HALF_UP)`. The
  contract's `Money` pattern is two decimals (`^-?[0-9]+\.[0-9]{2}$`), and every currency the brands
  run (EUR, BRL, USD, GBP) has exponent 2. A currency of another exponent is refused at projection
  and logged (spec edge case), never served rounded. Times are ISO 8601 with `Z`. The enum maps are in
  [contracts/fields.md](contracts/fields.md) §2, each with the original's suite (`enums.test.ts`)
  ported.

## R13. The module and the route class

- **Decision**: spec 021's module `partner` is renamed `public-api`, holding the service
  `public-api`, depending on `casino` (its catalogue is `game-provider`'s). `modules/public-api/module.json`
  names the topics read, the realm role `api_keys`, `"personal": true` and the erasure route. The
  realm gains the role `api_keys` in `brands/_realm/eitheror-operators.json` beside `markets`. In
  `contract.access.Routes`, `partner` and `Refusal.NotPartner` are removed, and an `AccessKeysRole =
  "api_keys"` constant is added for `operatorHolding`.
- **Spec amendments on this branch**: spec 001 FR-020's partner class and its partner wording; spec
  021's module list; this repository's `CLAUDE.md` table (016 `partner-api` → `public-api`); the
  brain's `projects/ankka-modules.md` gets a line.

## R14. Recordings

- **Decision**: a Go driver, `tests/original/public-api-driver/`, in the original's toolchain and
  modelled on `tests/e2e/scenario252_partner_api_e2e_test.go`. It seeds a partner client and grant on
  kind, drives the eleven recordings of the spec's *Recordings*, and writes them under
  `tests/port/public-api/` with `contract.port.Recording`. Parity tests replay them against the
  service with the credential replaced by an access key. Bodies and statuses are compared, except the
  authentication refusals, which are compared by status and by the code's class (missing, invalid,
  expired, disabled). Ids are compared by role, because the original's twin ids are not the seeded
  facts' ids ([contracts/recording.md](contracts/recording.md)).
- Recording against the original needs the original's kind installation; it is one task, run on purpose, and the parity suite skips when the recordings are absent.

## R15. Tests: the original's suites, re-expressed

[contracts/recording.md](contracts/recording.md) lists every original test file and the suite here
that re-expresses it, or why it is not ported (`substrateClient`, `unit` namespaces and
`playerStatsQuery` test the twin store's ids, which do not exist here).

## R16. Verified at task time, each with a fallback

1. **Expression indexes on a keyed view's table**: if the service cannot create them at start, a
   declared query per child order with `ORDER BY` and `OFFSET` (reporting's R5) is the fallback.
2. **`jackson-dataformat-yaml` and `swagger-parser` under Scala 3.9 and JRE 21**: Java libraries with
   no Scala dependency, so the risk is only their transitive Jackson version against ankka's; if they
   clash, `snakeyaml-engine` replaces the YAML parser and the OpenAPI check drops to the JSON Schema
   validation of R2's suite 3.
3. **`askStream` over 1 million rows within SC-008**: measured by `StatsPerfSuite`. If it is too
   slow, the fold reads one day at a time in parallel.
4. **`Personal` fields in a keyed view over a key value entity**: reporting does this at 0.16.0
   (spec 015 R10); if a key value entity's state cannot hold them, `PlayerRecord` becomes event sourced.
5. **G6's free-spins value**: the original's `face_value` for a free-spins claim, read from recording
   1 before `rewards` is amended; if the original writes none, `value` is `0.00` in the reward's
   currency.

## Spec corrections

Applied on this branch:

- FR-036 and FR-037: the request's record and the per-player records (R6).
- FR-032: the staleness report's mechanism (R8).
- FR-027: the catalogue route is new on `game-provider` (R9).
- *Changes*: a bet and its BET transaction share an id; bonus awards read ACTIVE where the original
  could say WAGERING; four transaction families are not served ([contracts/fields.md](contracts/fields.md) §6).
- *Dependencies*: the four producers amended ([contracts/facts.md](contracts/facts.md)).

## Findings for other specs

- **ankka**: an endpoint that can publish a fact would remove `KeyLog`'s journal (R6); a per-query
  statement time and alerting are absent (the original had neither either).
- **spec 018**: actor kind `access_key` is new; the audit accepts any kind (its FR-001 lists kinds,
  and its line 149 allows more).

## Found during implementation

- **The erasure route's path** (T005, 2026-11-12, Trevor). `POST /erasure/players/{playerId}`
  could not be reached: `players` reads `ERASURE_TARGETS` as `service=/prefix` and refuses an empty
  prefix. The route is `POST /v1/casino/brands/{brand}/public-api/erasure/players/{player}`, as
  engagement's, exchange's and reporting's are, served by the `/v1` endpoint under its own
  `withAcl(Routes.playersOnly)`, so no endpoint is added and `players` is unchanged. It is not in the
  public contract's file, which holds only the API's own paths, and no key reaches it: it admits
  `players` only. The modules tool renders `erasure-targets` with `public-api=/v1/casino/brands/<slug>/public-api`
  from its row in `contract/access/routes.md`.
- **What the module manifest cannot carry** (T005). ankka-vendor's manifest schema allows neither
  `"personal"` in a descriptor nor a list of topics read: `"personal": true` is in
  `services/public-api/service.json` (T007), as in every brand service, and a brand project's
  services read its topics without a grant, as `reporting`'s do. The service is exposed by
  `"expose": true`, as `reporting` is; the platform gives it no hostname, so the key `public-api-host`
  (`PUBLIC_API_HOST`) names the address the contract's `servers` shows, written in the brand's values.

- **G7's real parts come from `realLeg`.** The wallet's HTTP answers carry no `depositPartCents` or
  `convertedPartCents`; those are on its facts only. Its answers' `realLeg` is exactly their sum
  (bets split it with `Pools.bet`, wins with `Pools.winSplit`, rollbacks restore it), and a provider
  credit's answer says by `landedOn` whether it went to real balance. game-provider reads those and
  nets them as it nets `stakeCents` and `winCents`; a figure is left out when any movement it counts
  was recorded before the wallet's answer carried its real part.

- **Unknown paths four or more segments deep.** `/v1` catches unknown paths one and two segments
  deep, and three deep under each of the contract's first segments, answering `401` without a key
  and `404 not_found` with one, as the original does. A route takes at most two path parameters
  (ankka 0.16.0), so a path four or more segments deep that the contract does not hold reaches
  ankka's own `404` with or without a key. It serves nothing and reveals nothing; the difference is
  only the status without a key.

- **R16.1 settled: declared queries, no expression indexes.** ankka 0.16.0's `ViewQueries.ordered`
  takes no offset, and a service has no hook to create an index on a view's table at start. Each
  collection is therefore a declared query in data-model.md's order with `LIMIT (:limit)::bigint
  OFFSET (:offset)::bigint`, and its total is `count` over the same condition (reporting's R5).
  `Players` names its query `listed`: `all` is one of a view's fixed reads and is refused.
- **Records a fact reaches before the fact that makes them servable.** A player before
  `player_registered`, and a bonus award the wallet's progress reaches before its grant, are kept
  and not served; that is not a refusal and logs nothing. A record the contract cannot serve (a
  currency without two minor digits, a round without a game code) is refused at projection and
  logged with its kind and id only.
- **A `round_settled_as_loss` row's wallet type is MAIN**, the original mapper's default for an
  absent `wallet_type` (`transaction.ts:24`), not null.

- **What the erasure route erases.** public-api keeps no objects, so its part of an erasure is the
  player's record: the route (called by `players` as a target) and the installation's erasure
  handler both mark every identifying field of the player's record erased, and a fact read again
  afterwards keeps them erased. The installation's erasure also destroys the subject's key, so every
  stored copy reads erased and each view's rows are rewritten. The answer is
  `{"service":"public-api","subject":"player/<id>"}`.

- **Which refusals are recorded.** FR-036 as written: a request whose `Authorization` parses as a
  key (`ak_<id>_<secret>`) is recorded whatever its outcome, so a disabled, expired or unknown key
  and a wrong secret each leave a `public_api.read` record and a `KeyLog` event under the id it
  named; no header, or one that does not parse, is not recorded. A caller can therefore grow
  `KeyLog` journals under ids that name no key, at the gateway's rate, since the per-key rate only
  applies once a key is admitted. For the review: recording only ids `AccessKey` knows would need
  `admit` to tell an unknown key from a wrong secret.
- **SC-006 in the test kit.** "Within 10%" of a p95 of a few milliseconds is under the scheduler's
  noise on one JVM, so `QuotaSuite` allows the 10% plus 2 ms; the perf suites (T093) run at volume.
- **The second instance.** `QuotaSuite` and the budget's tests start a peer node in the kit's
  cluster (`AnkkaTestKit.startPeer`) with its own HTTP port; the count is shared because
  `KeyWindow(keyId)` is one entity across the cluster.
- **One keyring per test JVM (a finding for ankka).** ankka 0.16 installs a service's keyring
  process-wide (`Keyring.install`: the last service started wins, a stopped one clears it), and a
  view's projection decodes under that default rather than its own system's
  (`ServiceKeyring.of(system)`). Kits with keyrings of their own (the second brand's, the peers')
  then read each other's personal fields as unavailable and the players view stops. The test kit
  gives every kit one `InMemoryKeyring` and installs the shared kit's again after a peer stops. A
  production process runs one service, so this is the test kit's alone.
- **The suites' keys poll at the highest rate.** One kit serves every suite and their polling for
  views spent the default 600 a minute; the helper keys take 6000, and the default and the limit
  are tested with keys of their own.

- **Game rankings are ranked at read.** A game ranking ranks the snapshot's `GameFigures` rows of
  the games available now, in memory (at most a few hundred games), as the original does: ranks
  stored at the run could not drop a game the catalogue withdrew since, and the limit applies after
  that filter. Player rankings keep their stored ranks (paging over many rows).
- **A snapshot is published once the view holds it.** The run writes its rows, waits (at most two
  minutes) until `GameFiguresView` holds every row under the new snapshot id, and only then empties
  the slot's older rows and makes the slot current, so no read sees half a snapshot. A row is emptied
  only if its entity still holds an older snapshot: the view can lag the write it would otherwise
  undo.
- **The test kit runs the figures itself.** The hourly timer reads `StatsRuntime`, which is
  process-wide, so the kits (two brands, peers) do not schedule it; the suites call the runner.
- **A view's `lastAppliedAt` is its latest record's own time.** ankka (0.16) reports no applied
  offset or update time for a view, so `GET /public-api/{brand}/status` reports, per record view, the
  time of the latest record it holds (`latest`, one row by its time descending). An idle brand
  therefore looks behind; the figure is for spotting a stalled view, not an exact lag. A finding for
  ankka: a view's progress, as reporting's consumers tell theirs.
- **ankka's query parser misreads `->>(:name)`.** It did not see `:rank` in
  `payload::jsonb->'ranks'->>(:rank)` and refused the service at start ("values were read two
  ways"). The ranking reads the rank with `jsonb_extract_path_text(payload::jsonb->'ranks', :rank)`
  instead. A finding for ankka.
- **The ranking suites have a kit of their own** (`SharedKit.ranked`), so a ranking holds only the
  players they made and totals are exact; each test ranks in a currency of its own.
- **The crawl (SC-007) runs over real runs, with 120 players.** 1,000 crawls at page sizes 20 to
  100, a real run mid-crawl in one crawl of each hundred (it completes) and two in another (it is
  refused with `410`); each run moves the order. The original seeded slots directly with 250
  players; with real runs, 120 keep the suite near three minutes and still give each crawl two to
  six pages.
- **`ContractResponsesSuite` lives with the kit suites (`it/`), not `contract/`.** It drives the
  running service, which needs Docker, and `sbt test` runs only the suites that do not; CI runs it in
  the integration step. Its `503` cases run on a peer whose handlers are held past a 300 ms budget.
- **The endpoint's routes are read through a test-only accessor.** ankka keeps `HttpEndpoint.routes`
  package-private, so `ContractSnapshotSuite` reads them through `RoutesOf` in ankka's package
  (test sources only). A finding for ankka: a public, read-only view of an endpoint's routes.
- **One scenario of `contract.feature` is reported as run elsewhere.** "a breaking change ships as a
  new version…" is FR-006's release rule, and there is one version so far. The drift check fails any
  change to `/v1` that the amendments do not list, which is what forces a new prefix. Serving the
  prior version for six months belongs to the release, not to a test.
- **The driver (T090) and what the original holds (R16.5).**
  - **What the original seeds with a route, and what it cannot.** It seeds every record through its own
    routes: register, verify and log in; the signed wallet deposit; launch; fake-st8's bet, win and
    lose; offers and grants; the catalogue switch; and the internal statistics recompute. Two things
    have no route. Grants are Helm values (`casinoPublicApi.partners`), so `-write-grants` writes the
    overlay for the person's `helm upgrade`. The empty-grant and five-a-minute partner clients are
    created through Keycloak's admin API. Ids are written by role in each recording's `roles` map,
    brand A spinvibe and brand B goldvibe; `original/merge` does not keep `roles` yet, so a single
    run is committed as it is.
  - **The original's statistics are cut at the hour.** `GameStatsRecompute` and
    `PlayerStatsRecompute` truncate `now`, so the seed waits for the next hour. A second
    player-statistics run within 30 minutes of the last is skipped (`-retention-wait`).
  - **G6, the free-spins value.** A free-spins claim's `face_value` is always `0`
    (`BonusRoutes.scala:741`, `CustomerIOWebhookRoutes.scala:707`, noted at
    `BonusRoutes.scala:4224-4226`), rendered `"0.00"`. The spins' value lives only on the
    free-spins grant.
  - **fields.md §6, unactivated rewards.** The original's `bonus-claims` namespace holds AVAILABLE
    and PENDING claims (`BonusTargetingService.scala:117,185-187`), but its API does not serve them:
    their `face_value` and `granted_at` are null and the mapper drops them (`mappers/bonusAward.ts:12-15`).
    The port serves awards only, so the two agree.
  - **Two of the original's own defects, to confirm from the recording.** An award with no offer
    stores `offer_id = ""`, which the original's API drops (`BonusRoutes.scala:736`,
    `mappers/props.ts:9`). And new cash grants store integer cents (`BonusGrantService.scala:702`),
    which `decimalMoney` reads as major units, so a 50.00 award would read "5000.00". The parity
    suites compare by the recording and name either one where the port differs on purpose.
- **The brand at its size (T093, `StatsPerfSuite`).** This machine ran one kit with Postgres in
  Docker, holding 1,000,000 settled rounds over 30 days, 10,000 players, 200 games, and one player
  with 10,000 deposits:
  - **SC-008 is met.** The hourly run took 25.5 s against a 5-minute limit. Two changes got it
    there. The fold groups its rows once by game and by player; it had filtered every row for each
    key, which is quadratic. And the run reads the three views by keyset (`row_key > :after`; a
    record view's row key is its record's id), not by deep `OFFSET`.
  - **SC-003 is met.** Facts published one at a time were served within 3.1 s p95.
  - **SC-002 is met for by-id reads** (7 ms p95) and **missed for full pages** (2.66 s p95 against
    1 s). A player's rounds page filters the million-row rounds view on `payload->>'playerId'`, and
    ankka 0.16 views declare no index, so Postgres scans the table (spec 015 R17 met the same
    limit). The other pages read tables of about 10,000 rows. The suite prints p95 per page type
    and keeps the 1 s limit, so it fails until the index can be declared.
  - **The person decided (2026-11-12)** to record the miss with a finding for ankka, rather than
    rework the collections through per-player index entities. **A finding for ankka:** indexes
    declared on a view, on the terms its declared queries filter and order by.
  - Publishing the million facts took about two hours through the in-memory broker; a brand's live
    rate is far below that.
- **The order of the last tasks.** The dev-kind deploy (T095) needs ankka's control plane, and the
  original's kind stack (T091) needs it stopped. The person chose (2026-11-12) to deploy first, then
  record, with the parity suites (T092) written against the real recordings.
- **The dev-kind installation was at platform 0.14.2** (runtimes 0.13–0.14). public-api is built on
  ankka 0.16.0, so it was applied but `Unavailable` ("runtime 0.16.0 is outside the platform's
  supported range"), and engagement had been unavailable for the same reason. The person chose
  (2026-11-12) to rebuild kind-ankka at v0.16.3. The first apply on the 016 contract also showed
  an ordering trap in the modules tool. Core waits for `players` to be Ready, but players reads
  `casino.rounds` and `casino.rewards`, whose new schemas only the `casino` module declares, after
  core. So players was refused at start ("reads 'casino.rounds' as … project declares …"). Pausing
  players, applying, then resuming it got past it. A finding for spec 021's tool: declare every
  module's topics before waiting for any module's services.
- **No one installation runs the brand stack beside public-api.** On the rebuilt kind-ankka
  (platform 0.16.3, runtimes 0.15–0.16) every service of the `core` and `casino` modules (wallet,
  players, kyc, responsible-gambling, payouts, attribution, game-provider, rewards) is refused: they
  are built on ankka 0.13.0. Platform 0.14.2 refused public-api (0.16.0) the other way. The person
  chose (2026-11-13) to move those eight services to ankka 0.16 first, on a branch of its own, and
  to deploy goldvibe with public-api (T095) after it.
- **T095 stopped on kind's capacity (2026-11-13).** With the upgrade (PR #38, merged into this
  branch) applied, wallet, players, kyc, responsible-gambling, payouts and attribution reached Ready on
  runtime 0.16.2 in goldvibe on platform 0.16.3. game-provider and rewards could not sign in to the
  database: each ankka service opens Pekko R2DBC's default pool of 20 connections, ankka exposes no
  setting for it, and the project's Postgres keeps the default `max_connections` of 100 (97 for
  roles that aren't superusers). A brand of nine services needs up to 180. That's an ankka finding. Raising
  `max_connections` to 300 on goldvibe's kind database by hand restarted it, and the single kind node
  then locked up: load average ~260, 12 of 16 GiB, the API server and ankka's Keycloak timing out.
  Scaling deployments to zero is undone by ankka's operator. The person chose to stop T095 there. The
  deployed scenario check is unmet; the suites over Postgres (`just public-api-it`) stand for it.
- **T095 on a rebuilt kind-ankka (2026-11-14).** The cluster was rebuilt at v0.16.3, and every
  deployed service moved to runtime 0.16.3 (the eight of core and casino on PR #38, public-api here).
  goldvibe's database was raised to `max_connections` 300 as it appeared (by hand: ankka renders the
  Cluster and has no setting for it). All nine services reached Ready, one instance each. Found and
  fixed: public-api had no `logback.xml` (DEBUG everything) and was OOM-killed at `small`; it now
  logs at INFO and runs `medium`. The dev secrets named the realm's admin client `goldvibe-admin`;
  the realm's client is always `players-admin`, so registration answered 503 until it was corrected.
  On a 10-CPU node the brand's requests fill 93–98%, so a rolling restart of a `medium` service
  cannot schedule beside its old pod; pausing and resuming it does. payouts was paused for room.
  Checked against the deployed service (a script in the session's scratchpad, 41 checks, all met):
  `/health` and `/v1/openapi.json` without a key, the document the committed file with
  `servers[0].url` the brand's address; `key_missing`, `key_invalid`, `405` for other methods, `404`
  for an unknown path with a key, `400` for a bad id and for `limit` above 100; the operator routes
  (`401` without a token, `404 wrong_brand` for another brand, `400` for an empty name, the secret
  only at creation, list, read with `lastUsedAt`, disable → `403 key_disabled`, enable, delete →
  refused, change a deleted key → `409 key_deleted`); `429 quota_exceeded` with `Retry-After: 60`
  at 2 a minute; the status route. A player registered through the deployed players appeared in
  `/v1/players` within seconds, with no identifying field for a key allowed none, and exactly
  `email`, `firstName`, `lastName` for a key allowed those; its status ACTIVE as fields.md maps a
  pending player. `goldvibe.platform.audit` held 53 `public_api.read`, every `access_key.*` change,
  and one `public_api.identifying_fields_served` naming the player, the key and the field names,
  with no value. Every check has an integration test already (KeysSuite, KeyChangeSuite, AdmissionSuite,
  FieldsSuites, IdentifyingFieldsFeatures, AuditTrailSuite, StatusSuite, ContractResponsesSuite). Not exercised on kind:
  sessions, rounds, bets, transactions and bonus awards from real producers, which need ST8's
  callbacks and a deposit through the payments project.
  The person chose (2026-11-14) to count T095 met with that gap: the Postgres integration suites
  (`just public-api-it`) and the parity suites over the original's recordings cover those records.

- **The recordings (T091, 2026-11-14).** Recorded on the original at `7630feb0` (the rewards worktree),
  on a freshly built `eitheror-dev`: the original's statistics and rankings count every player on the
  stack, so a run on a stack that held earlier runs answered three runs' figures (381 rounds where the
  seed made 127) and ranks that skipped other runs' players. Only a run on a fresh stack can be
  replayed. Two driver defects were fixed on the way: the seed's own player-statistics run put the
  brand inside the original's 30-minute retention guard, so `player-ranking` could never be recorded
  with `-retention-wait 0` (now 31m by default); and the original merges `gameOverrides`, so the games
  recording's restore left the disabled game off (it now turns it back on by name). The recipe wrote
  under the driver's package directory (`go test` runs there); `EITHEROR_RECORD_TO` is absolute now.
  The MinIO server and `mc` images pull from nowhere public; built from their release tags and loaded.
- **What the original answers that the parity suites mark (T092).** Each is a decision this spec
  already took, named in `port/Parity.scala`'s divergences: a deposit and a bonus credit are `settled`
  there and `completed` here (fields.md Enums); a loss row copies the stake there and has no amount here;
  a lost round's bet record says `settled` there and `loss` here (fields.md Bet.status); a bet record has
  its own id and time there, here it is its BET transaction; an award's `value` is `face_value` cents
  rendered as major units there ("5000.00" for a 50.00 deposit match), here the reward's amount; free
  rounds with no offer are counted in a player's `bonus-awards` total there and never served (R16.5),
  here they are an award; its players list answers a `total` above the records it serves (6 for 3 on a
  fresh stack, 18 for 9 on one that held earlier runs), here the records served; and the OpenAPI
  document and reference page are the amended contract. Two more of the original's answers need no
  marking: its `Transaction.roundId` is the provider's round id, not the round record's, which the
  comparison by role absorbs; and it served a game disabled by the operator as `404` within about a
  minute, where the five minutes of R9 is the bound here (the replay moves its clock past it).
- **The suites (T096, 2026-11-14).** `just features`, `just test`, `just public-api-test`,
  `just public-api-it` and the suites of every amended producer (wallet, players, rewards and
  game-provider, units and integration) are green. The parity suites pass on recordings whose sign-in,
  refresh and launch tokens are written as their `{name}`. One run of `public-api-it` failed QuotaSuite's
  SC-006 check (p95 5.3 ms alone, 16.0 ms beside a busy key) with the original's kind cluster still up
  and the machine's load at 16 on 10 CPUs; with that cluster stopped, it passed three runs of three. The
  check compares latencies on the machine that runs it, so it fails under contention from outside the
  service.

## Declined remediations

## Design finished

2026-11-12: analyze reported nothing above LOW (round 1: four MEDIUM, two LOW, all applied; round 2:
none above LOW).
