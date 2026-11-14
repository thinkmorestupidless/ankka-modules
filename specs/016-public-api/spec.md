# Feature Specification: public-api — A Brand Reads Its Own Records Through Keys It Issues

**Feature Branch**: `016-public-api` (was `016-partner-api`)

**Created**: 2026-10-08; **rewritten** 2026-11-12

**Status**: Draft

**Input**: User description: "The brand project's `public-api`: the original's read-only REST API
(its spec 255, with 256's player statistics), kept to its published OpenAPI contract, served from
views the service builds from the brand's topics. Access is by access keys an operator of the brand
creates, enables, disables and deletes from the backoffice; there is no partner record. Each key
has an optional expiry, the identifying fields it may read (none by default) and its own requests
per minute."

## Context

This spec was first written on 2026-10-08 against the original's GraphQL partner API (its spec
211). On 2026-10-22 the original replaced that API with a GET-only REST API over its twin graph
(its spec 255, PR #713), added player statistics (its spec 256) and a reference page, and deleted
the GraphQL surface, the read model's use, the cost ceiling, cursors, opaque ids and the freshness
field. Constitution I makes the running original the specification, so this spec is rewritten
against the REST API. The 2026-10-08 text is in git history (`specs/016-partner-api/`).

Today `casino/public-api` in the original is a TypeScript service serving 22 GET operations
described by one hand-written OpenAPI 3.1 document, `public-api-v1.openapi.yaml` (807 lines):

- a brand's players, and per player their sessions, rounds, bets, transactions and bonus awards;
- each of those by id, a session's rounds and a round's bets;
- the brand's games catalogue and one game by code;
- game statistics over the last 1, 7 and 30 days, per game and ranked;
- player statistics over the same windows, per player and ranked, paged through hourly snapshots;
- the contract itself and a reference page.

Collections page by `limit` (1–100) and `offset`, oldest first, with a total. Money is a decimal
string in major units beside its currency. Ids are the platform's own record ids. It reads the
twin store per request with a five-second budget. A caller is a confidential Keycloak client in the
`eitheror` realm, scoped to one brand by three claims and matched to a grant in a Helm value that
holds its identifying fields, its data processing agreement and its requests per minute. Every
request writes one audit line to Loki.

The first consumer is eitheror's own BI system (the original's spec 256, *Input*). Two things
change here, decided by Trevor on 2026-11-11 and 2026-11-12.

First, **there is no partner.** A brand's operator creates access keys in the backoffice and uses them
for whatever the brand wants: its BI, a reporting tool, an outside company it shares the key with.
Each key is its own credential with its own settings, and this service issues and checks it. There
is no realm client, no token exchange, no grant record and no agreement on file.

Second, the records come from the brand's topics, not a shared store (constitution IV). The service
builds its own views and computes its own statistics from them.

The contract keeps every operation, parameter, response and format the original publishes. Only
the parts that describe the credential change (FR-002).

## Clarifications

### Session 2026-10-08

These answers were given against the GraphQL spec. The ones still standing are restated under the
sessions below; the rest are superseded by the rewrite.

### Session 2026-11-11

- Q: Is a partner contracted? → A: Yes, contracted or close (Trevor): the BI system is the first
  consumer. The service is built now, not last. It is its own module (spec 021), so a brand runs it
  only when entitled to it.
- Q: Which language? → A: Scala. ankka's TypeScript SDK cannot shape a response: a refusal is
  always `text/plain`, with no status the service chooses and no headers (spec 005 research R1). The
  contract's `{error}` bodies and the 429's `Retry-After` need both. Nothing here moves money
  (constitution VI).

### Session 2026-11-12

- Q: The original's API is REST since 2026-10-22, not GraphQL. Which surface? → A: The REST API, its
  spec 255 and 256, held to its OpenAPI contract (constitution I).
- Q: How does a caller get access? → A: Through an access key an operator of the brand creates in the
  backoffice. There is no partner record: a key is the brand's, and what it is used for is the
  brand's business (Trevor). Keys are created, listed, enabled, disabled, changed and deleted
  through this service's operator routes. The backoffice's key-management screen is the backoffice
  frontend's, which is out of scope here (constitution, *Scope*); this spec serves the routes it
  calls.
- Q: How is a key presented? → A: On every request, as `Authorization: Bearer <key>`, and checked
  on every request. There is no token exchange. A disabled, deleted or expired key is refused on the
  next request after the change.
- Q: Do keys expire? → A: An expiry date is optional, set when the key is created; none is the
  default.
- Q: May a key read identifying fields? → A: Only the ones the operator ticked for that key; none by
  default. No agreement record is required: who the brand gives a key to is the brand's decision. Each
  identifying field served is audited against the key.
- Q: Requests per minute? → A: Per key, 600 by default, changeable per key. One key at its limit
  does not slow or refuse another.
- Q: Who may manage keys? → A: An operator of the brand who also holds the `api_keys` permission
  (the realm's roles are the permissions, as spec 023's `markets`), or a platform administrator. A key
  gives bulk read access, so ordinary operator access is not enough.
- Q: Does a partner read several brands under one credential? → A: No. A key belongs to one brand's
  project and reads that brand only. Reading several brands takes a key at each, at each brand's
  hostname (standing from 2026-11-12, first answered for partners).
- Q: Ids? → A: The brand's own record ids, as the original serves its platform record ids (its spec
  255 FR-007). Opaque ids were dropped by the original and are not brought back. The Spinvibe
  migration (spec 019) keeps record ids, so an id stored before the cutover still resolves.
- Q: Seven contract fields (a round's start and the open status, a bet's round and session, a
  player's language and state, a bonus award's expiry, a free-spins award's value and currency, a
  round's real-money stake and win) and the games catalogue have no fact or route today. How far does
  this spec go? → A: It amends every producer, additively: `players`, `game-provider`, `wallet` and
  `rewards` (Trevor). The amendments are listed in the plan's `contracts/facts.md`.

### Session 2026-11-14

- Q: Is a request presenting a well-formed key id that no key was created under recorded (review of
  the build)? → A: No. Only a key of the brand is recorded, deleted or not, with a wrong secret; an
  unknown id would let anyone write to the brand's journal and audit without limit (FR-036).
- Q: Does "neither repeated nor skipped" hold for a record arriving late with an earlier time? → A:
  No: it holds for records later than the last one read, as offset paging in the original gives
  (FR-014).
- Q: Must every refusal of a key of the brand be recorded, when a leaked key may be retried in a loop
  (second review round)? → A: Up to 60 a minute for each key on each instance; the rest are counted
  and named on the key's next recorded refusal (FR-036).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An operator creates a key and a caller reads the brand with it (Priority: P1)

An operator with the `api_keys` permission on a brand creates a key: they give it a name, and may
set an expiry, the identifying fields it may read and its requests per minute. The secret is shown
once, at creation, and never again. A caller presents the key and reads the brand's players. The
operator lists the brand's keys and sees each one's name, the start of the key, its settings, who
created it, when, and when it was last used. They disable a key, then enable it again, change its
fields or its rate, and delete it. Every change is recorded with who made it.

**Why this priority**: Without a key nothing else can be reached, and the operator owns the key's
whole life.

**Independent Test**: As an operator, create a key, read a player with it, disable it, read again,
enable it, delete it, read again, and list the audit records of the changes.

**Acceptance Scenarios**:

- added `features/public-api/keys.feature`: an operator with the access keys permission creates a key and is shown its secret once
- added `features/public-api/keys.feature`: a key's secret is never shown again after it is created
- added `features/public-api/keys.feature`: an operator without the access keys permission, or whose roles do not name the brand, cannot see or change the brand's keys
- added `features/public-api/keys.feature`: the brand's keys are listed with their name, the start of the key, their settings, who created them, when, and when each was last used
- added `features/public-api/keys.feature`: a disabled key is refused from the next request and is accepted again once enabled
- added `features/public-api/keys.feature`: a deleted key is refused from the next request and can never be enabled again
- added `features/public-api/keys.feature`: a key past its expiry is refused
- added `features/public-api/keys.feature`: a change to a key's fields or rate applies from the next request
- added `features/public-api/keys.feature`: every change to a key is an audit record naming the operator, the key and what changed, and never the secret

---

### User Story 2 - A caller reads only its own brand (Priority: P1)

A caller with a key reads the brand's players and, per player, their sessions, rounds, bets,
transactions and bonus awards, and any of those by id. Everything returned is the key's brand's. An
id of another brand answers "not found", exactly as an id that does not exist. A request with no
key, an unknown key, a disabled, deleted or expired key, or a key of another brand is refused and
returns no data. Nothing can be written.

**Why this priority**: Isolation is the condition for the surface existing at all (the original's
`isolation`, `scope-levels`, `requestPipeline` and `partnerToken` suites).

**Independent Test**: Seed two brands with the same shapes of records; with a key for one, walk
every route with the other's ids; send each kind of untrusted key; send a write.

**Acceptance Scenarios**:

- added `features/public-api/access.feature`: a caller with a key for a brand is returned only that brand's players
- added `features/public-api/access.feature`: a caller asking for another brand's record by id is told it is not found, exactly as for a record that does not exist
- added `features/public-api/access.feature`: a request with a key that cannot be trusted is refused and returns no data
- added `features/public-api/access.feature`: any request that is not a read is refused, because the public contract has no write
- added `features/public-api/access.feature`: a malformed id is refused as an invalid request before anything is looked up

---

### User Story 3 - A caller reads a player's activity page by page (Priority: P1)

A caller lists the brand's players, oldest registration first, and per player their sessions,
rounds, bets, transactions and bonus awards, oldest first. Each record carries the ids of its
parents. Money is exact, in major units beside its currency. A page holds at most 100 records and
says the total and whether more follow; a page past the end is empty with the true total; an empty
collection is an empty page. Activity arriving while the caller pages does not repeat or skip an
older record.

**Why this priority**: Reading activity is why a caller integrates; it is the original's US1 (its
`paging`, `boundaries`, `money`, `enums`, `routes/*` suites).

**Independent Test**: Seed a player with 250 transactions; page through at 100 while new ones
arrive; ask for 101; ask past the end; list a player with no bonus awards.

**Acceptance Scenarios**:

- added `features/public-api/activity.feature`: paging a player's transactions at the maximum page size returns every one exactly once, oldest first
- added `features/public-api/activity.feature`: activity written while a caller is part way through paging is neither served again nor skipped
- added `features/public-api/activity.feature`: a page size above the maximum is refused, naming the maximum
- added `features/public-api/activity.feature`: a page past the end is empty and still gives the total
- added `features/public-api/activity.feature`: a collection with nothing in it is an empty page, not an error
- added `features/public-api/activity.feature`: a record read by id carries the ids of its player and, where it has them, its session and round
- added `features/public-api/activity.feature`: an amount is served as an exact decimal in major units beside its currency
- added `features/public-api/activity.feature`: a status the public contract does not name is served as null, never mapped to another
- added `features/public-api/activity.feature`: a closed or self-excluded player is listed and readable, with their status on the record

---

### User Story 4 - A caller is served only the identifying fields its key allows (Priority: P1)

A key with no identifying fields reads players with every identifying field absent, not empty. A
key allowed email reads email and nothing else identifying. A field withdrawn from the key stops
arriving on the next request. An erased player's identifying fields are absent whatever the key
allows; their id, status and amounts remain.

**Why this priority**: The brand is answerable for every identifying field that leaves it
(constitution V; the original's `entitlements`, `lawful-basis`, `no-pii-leak` suites).

**Independent Test**: Read one player with three keys: none, email, all. Withdraw email and read
again. Erase the player and read with the third key.

**Acceptance Scenarios**:

- added `features/public-api/identifying-fields.feature`: a key with no identifying fields reads players with every identifying field absent
- added `features/public-api/identifying-fields.feature`: a key allowed some identifying fields is served those and no others
- added `features/public-api/identifying-fields.feature`: an identifying field withdrawn from a key stops arriving from the next request
- added `features/public-api/identifying-fields.feature`: an erased player read with any key has every identifying field absent and keeps their id, status and amounts
- added `features/public-api/identifying-fields.feature`: no identifying value appears in a log, a trace or an error

---

### User Story 5 - A caller lists the brand's games and reads their statistics (Priority: P2)

A caller lists the games the brand offers, in game-code order, each with its catalogue details, and
reads one by code. A game the brand disabled, the provider withdrew or that is not yet released is
absent. The caller ranks the brand's games by rounds, sessions, players, stake or GGR over the last
1, 7 or 30 days, and reads one game's figures for all three windows. Money rankings need a currency
and never mix currencies. Figures are computed hourly and every answer says when.

**Why this priority**: The original's US2 and US3 (its 255): what the business asked the REST API for
beyond the GraphQL API's records.

**Independent Test**: Seed a catalogue with a disabled game and a future one, and rounds across
games with known stakes; run the hourly computation; compare the rankings and one game's figures
with a hand calculation.

**Acceptance Scenarios**:

- added `features/public-api/games.feature`: the games listing holds only the games available on the brand, each with its catalogue details
- added `features/public-api/games.feature`: a game the brand disabled, the provider withdrew or that is not yet released is not found
- added `features/public-api/games.feature`: a change to the brand's catalogue reaches callers within five minutes
- added `features/public-api/games.feature`: games ranked by a figure over a window match a hand count, with when they were computed
- added `features/public-api/games.feature`: a money ranking without a currency is refused, and with one ranks in that currency only
- added `features/public-api/games.feature`: a game with no activity in a window reports zeros for it
- added `features/public-api/games.feature`: cancelled rounds count nowhere, and free-spins rounds count as rounds with no stake, win or GGR
- added `features/public-api/games.feature`: before the first computation a ranking is empty with no computation time

---

### User Story 6 - A caller ranks the brand's players without seeing a ranking change under it (Priority: P2)

A caller ranks the brand's active players by rounds, sessions, stake, GGR or deposits over a window,
optionally only some statuses, and pages through. Every page names one snapshot, so the ranking it
assembles is complete even if another one is published mid-read. A retired snapshot is refused with
"snapshot expired", and the caller starts again. The caller reads one player's figures for all three
windows. Rankings never carry identifying fields.

**Why this priority**: The original's 256, the BI system's main reason to integrate.

**Independent Test**: Seed 250 players with known figures; page the 7-day GGR ranking; publish a new
snapshot between pages; page on; name a retired snapshot.

**Acceptance Scenarios**:

- added `features/public-api/player-stats.feature`: paging a player ranking returns every ranked player once, in order, all pages naming one snapshot
- added `features/public-api/player-stats.feature`: another snapshot published mid-read does not change the pages of the snapshot being read
- added `features/public-api/player-stats.feature`: a snapshot retired after its retention is refused as expired
- added `features/public-api/player-stats.feature`: a ranking restricted to some statuses keeps each player's rank in the whole ranking
- added `features/public-api/player-stats.feature`: one player's figures for all three windows match a hand count, and are zeros when they had no activity
- added `features/public-api/player-stats.feature`: a ranking never carries an identifying field, whatever the key allows

---

### User Story 7 - A caller builds against a published, stable contract (Priority: P2)

A caller fetches the contract without a key, generates a client and builds against it; the
reference page describes it for people. A release that changes the contract by accident fails before
it reaches a brand; a breaking change is a new version beside the old for six months. Every response
validates against the contract.

**Why this priority**: The original's US4 (its `contract/*` suites); what makes a public contract
safe to publish.

**Independent Test**: Fetch the contract; validate every route's answers against it; change a
response field and build.

**Acceptance Scenarios**:

- added `features/public-api/contract.feature`: the public contract is served without a key, complete enough to generate a working client
- added `features/public-api/contract.feature`: every answer, success or refusal, matches the public contract
- added `features/public-api/contract.feature`: a release that does not intend to change the public contract serves an identical one or fails to build
- added `features/public-api/contract.feature`: a breaking change ships as a new version with the prior one served for at least six months

---

### User Story 8 - Every read is recorded, and a key's limits are kept (Priority: P2)

Every request leaves one audit record naming the key, the operation, the players whose identifying
fields were served and which fields, how long it took and how it ended, and never a value. A
subject access request is answered from those records: which keys were served which of a player's
fields, and when. A key over its requests per minute is refused with when to retry, and no other key
is slowed. A request that cannot be answered in five seconds is told the service is temporarily
unavailable.

**Why this priority**: The audit is what a regulator asks for; the limits keep one integration from
hurting the brand (the original's `audit`, `quota` suites).

**Independent Test**: Serve a player's email to a key; list the audit for the player. Drive one key
past its rate while another reads.

**Acceptance Scenarios**:

- added `features/public-api/audit.feature`: a request that served identifying fields leaves one audit record naming the key, the operation, the players and the field names, and no value
- added `features/public-api/audit.feature`: a request that served no identifying field, or was refused, still leaves an audit record
- added `features/public-api/audit.feature`: a subject access request lists every key served an identifying field of the player, with the fields and the times
- added `features/public-api/audit.feature`: a key at its requests per minute has the next request refused with when to retry, and another key is unaffected
- added `features/public-api/audit.feature`: a request the service cannot answer within its time budget is told the service is temporarily unavailable

---

### Edge Cases

- **A key's secret is lost**: it cannot be shown again; the operator creates a new key and deletes
  the old one.
- **Rotation**: a brand holds several keys at once, so a new one can be in use before the old one is
  deleted.
- **A key changed while a caller is mid-page**: the change applies from the next request; offset
  paging is unaffected.
- **Two keys with the same name**: allowed; keys are told apart by id and by the start of the key
  shown in the list.
- **An expiry in the past at creation**: refused.
- **A brand retired while it holds keys**: every key is refused with the brand, by construction: the
  keys live in the brand's project, which retirement removes with its services.
- **A key of another brand** presented at this brand's hostname: unknown here, refused as an
  untrusted key.
- **A player record the original held with session-shaped data** (its known defect, dropped from its
  listings): no such record exists here, because the players view is built from `casino.players`.
  The page-short behaviour is not ported.
- **A replayed fact** is applied once; no record is listed twice.
- **An amount in a currency whose exponent the service does not know** is refused when it is
  projected and reported, never served rounded.
- **A statistics computation that fails** leaves the previous figures served with their time.
- **A player closed or self-excluded after a snapshot** keeps their old status in that snapshot until
  the next (the original's 256 rule).

## Requirements *(mandatory)*

### Functional Requirements

**The contract**

- **FR-001**: The service MUST serve the original's `public-api-v1.openapi.yaml` operations, every
  path, parameter, schema, enum member, default and status, at the original's paths under `/v1`, at
  a hostname of the brand's own (001 FR-006), over encrypted transport. Only the parts FR-002 names
  may differ. *Established by the original's contract and its `contract/snapshot` suite.*
- **FR-002**: The only differences MUST be those that describe the credential: the contract's title
  and version number (`1.1.0`), its `servers` (the brand's hostname), its authentication guide, its
  security scheme (a bearer access key in place of a client-credentials JWT), the authentication codes
  of its error enum (`key_missing`, `key_invalid`, `key_expired`, `key_disabled` in place of `jwt_*`,
  `actor_type_invalid`, `scope_*` and `grant_*`), and the wording of descriptions and summaries that
  name a partner, a grant or a token. The version path stays `/v1`, because no caller outside
  eitheror is on it and every caller must take a new credential at the cutover anyway (spec 019).
  *Decision here, Clarifications 2026-11-12, recorded under* Changes; *the paths are in the plan's
  `contracts/amendments.md`.*
- **FR-003**: `GET /v1/openapi.json` MUST return the committed contract, and `GET /v1/docs` the
  reference page, both without a key; `GET /v1/contract` MUST return the version and the maximum page
  size, with a key. *Original routes, `serve.ts`.*
- **FR-004**: The service MUST offer no write: any method but GET on a contract path MUST be refused
  as an invalid request. *Original 255 FR-002, `router.ts`.*
- **FR-005**: A release MUST fail when the served contract differs from the committed one, or when
  any route's answer, success or refusal, does not validate against it. *Original 255 FR-027,
  `contract/*` suites.*
- **FR-006**: A breaking change MUST be a new version at a new path prefix with the prior one served
  for at least six months; an additive change MUST NOT change the version. *Original 255 FR-028,
  versioning document.*

**Records**

- **FR-007**: The service MUST serve players; per player their sessions, rounds, bets, transactions
  and bonus awards; each of those by id; a session's rounds; and a round's bets, with every record
  carrying its parents' ids. *Original 255 FR-001, FR-003, FR-004, FR-006.*
- **FR-008**: The service MUST build its own views from the brand's topics and read no other
  service's store: players and their statuses from `casino.players`, sessions from `casino.sessions`,
  rounds and bets from `casino.rounds` and `money.transactions`, transactions from
  `money.transactions` and `casino.withdrawals`, bonus awards from `casino.rewards` (001 FR-011). Each
  fact MUST be applied once. *Constitution IV.*
- **FR-009**: Ids MUST be the brand's own record ids; a game's id MUST be its provider game code.
  *Original 255 FR-007.*
- **FR-010**: Every amount MUST be an exact decimal string in major units with two decimal places
  beside its currency, converted from the fact's minor units by the currency's exponent; every
  timestamp MUST carry an explicit offset. *Original 255 FR-008, `money` suite.*
- **FR-011**: Status words MUST map onto the contract's enums as the original's mappers do, and a
  word with no published member MUST be served as null. *Original 255 FR-009, `enums` suite.*
- **FR-012**: Every player MUST be listed and readable whatever their status, with the status on the
  record. *Original 255 FR-005.*

**Paging**

- **FR-013**: A collection MUST page by `limit` (1–100, default 100) and `offset`, MUST state the
  total and whether more follow, MUST refuse a limit above 100 as `limit_exceeded`, and MUST answer a
  page past the end, or an empty collection, with an empty page and the true total. *Original 255
  FR-010, FR-011, `paging` and `boundaries` suites.*
- **FR-014**: A collection's order MUST be fixed and ascending by registration, start, creation or
  grant time, and by game code for games, with the record id breaking ties, so a record later than
  the last one read, arriving while a caller pages, is neither repeated nor skipped. A record that
  arrives late with an earlier time, or one no longer served, can shift a later page by one: paging
  is by offset, as the original's is. *Original 255 FR-012; the tie-break is new,
  under* Changes.

**Keys**

- **FR-015**: An operator of the brand holding the `api_keys` permission, or a platform
  administrator, MUST be able to create, list, read, change, disable, enable and delete the brand's
  keys through the service's operator routes (001 FR-020). Any other caller MUST be refused. *Decision
  here, Clarifications 2026-11-12.*
- **FR-016**: A key MUST hold: a name; an id; the start of the key, shown in lists; a secret shown
  once at creation and stored only as a hash the service cannot reverse; enabled or not; an optional
  expiry, which MUST be in the future when set; the identifying fields it may read, none by default;
  its requests per minute, 600 by default; who created it and when; and when it was last used.
- **FR-017**: A key's secret MUST be generated by the service with at least 256 bits of randomness and
  MUST NOT appear in any answer after creation, in any log, trace or audit record, or in any view.
- **FR-018**: A request MUST be served only when it presents `Authorization: Bearer <key>` naming a
  key of this brand that exists, is enabled and has not expired. The refusals are `key_missing` (no
  key), `key_invalid` (malformed, unknown or deleted), `key_expired` and `key_disabled`, and none of
  them may say whether a key exists at another brand. *Decision here, replacing the original's token
  checks (`partnerToken` suite).*
- **FR-019**: A change to a key, including disabling or deleting it, MUST apply from the next request
  after the change is recorded, whatever the caller did before. *Original 211 FR-020, tightened.*
- **FR-020**: A deleted key MUST be refused for ever and MUST NOT be enabled again; its record MUST
  remain for the audit, without its hash.
- **FR-021**: Every change to a key MUST be an audit record on the brand's `platform.audit` topic
  naming the operator, the key, the fields changed and the time, and never the secret or its hash.

**Isolation and identifying fields**

- **FR-022**: Every answer MUST hold only the key's brand's records. A record of another brand MUST be
  `not_found`, indistinguishable by status, body or timing from a record that does not exist.
  *Original 255 FR-013, FR-016, `isolation` suite.*
- **FR-023**: Every identifying field a view holds (email, given and family names, date of birth,
  telephone, address line, city, state or province, post code) MUST be a `Personal` value under the
  player's subject (001 FR-023, ankka 042). *Constitution V.*
- **FR-024**: A player's identifying fields MUST be served only when the key allows each one, and
  MUST otherwise be absent from the answer, never null or empty. *Original 211 FR-021c,
  `entitlements` suite; the agreement condition is dropped, under* Changes.
- **FR-025**: An erased player's records MUST remain, with their id, status and amounts, and every
  identifying field MUST be absent from every answer, within ankka 042's bound. *Constitution V.*
- **FR-026**: No identifying value and no key secret MUST appear in the service's logs, traces,
  metrics or error messages. *Original `no-pii-leak`, `logger` suites; ankka 042 FR-029.*

**Games and statistics**

- **FR-027**: The games listing MUST hold only games available on the brand: enabled, still in the
  provider's feed, released, and not disabled by the brand's curation (by developer, category or
  game), each with the catalogue details the contract names, in game-code order. A change to the
  brand's catalogue MUST reach callers within five minutes. *Original 255 FR-017–FR-019.* The
  catalogue is `game-provider`'s (spec 003), read through a route it serves to this service, added on
  this branch.
- **FR-028**: Game statistics MUST be computed at least hourly for the last 1, 7 and 30 days per game:
  rounds, sessions started, unique players and, per currency, real-money stake, win and GGR.
  Cancelled rounds MUST count nowhere; free-spins rounds MUST count as rounds, sessions and players
  with no stake, win or GGR; bonus-funded stake and win MUST be excluded. *Original 255 FR-020–FR-021.*
- **FR-029**: Player statistics MUST be computed in the same run for the same windows: rounds,
  sessions, distinct games, per currency real-money stake, win, GGR, settled deposits and completed
  withdrawals by count and amount, and the last-active time. *Original 256 FR-001–FR-005.*
- **FR-030**: Rankings MUST be descending by one figure in one window, with ties broken by id; money
  rankings MUST require a currency and never mix currencies; game rankings MUST hold at most 100
  entries; per-game and per-player reads MUST report zeros for a window with no activity. *Original
  255 FR-023–FR-024, 256 FR-007–FR-012.*
- **FR-031**: Each run MUST publish a complete snapshot at once. A player ranking MUST name its
  snapshot; a request naming a snapshot MUST be served from it; a snapshot MUST stay readable for at
  least 30 minutes after it is replaced; a retired or unknown snapshot MUST be refused as
  `snapshot_expired`; a status filter MUST keep each entry's rank in the whole ranking. *Original 256
  FR-015–FR-018, FR-023–FR-024.*
- **FR-032**: Every statistics answer MUST say when its figures were computed. A failed run MUST
  leave the previous figures served. Figures more than three hours old MUST be reported to the
  operator, naming the brand: on the service's status route and as a warning the installation's
  alerting reads (001 FR-034), ankka having no alerting of its own. *Original 255 FR-022, FR-022a; 256 FR-019.*
- **FR-033**: No ranking or statistics answer MUST carry an identifying field. *Original 256 FR-013.*

**Protection and audit**

- **FR-034**: A key above its requests per minute MUST be refused with `quota_exceeded` and a
  `Retry-After` in seconds, counted across every instance of the service, and MUST NOT slow another
  key. *Original 211 FR-028–FR-029, `quota` suite; counting across instances is a correction, under*
  Changes.
- **FR-035**: A request the service cannot answer within five seconds MUST be refused with
  `unavailable`, distinguishable from `not_found` and `invalid_request`. *Original 255 edge case,
  `REQUEST_TIMEOUT_MS`.*
- **FR-036**: Every request that named a key of the brand MUST produce one audit record on the brand's
  `platform.audit` topic naming the key, the operation, the ids of players whose identifying fields
  were served with the field names, the duration, the time and the outcome, and never a value. A
  key of the brand is one an operator created, deleted or not, so a wrong secret for it is recorded
  against it. A request refused before a key is named (no key, a malformed key), or naming a key id
  no key was created under, names none of the brand's and is not recorded: an unknown key costs the
  brand no write. Each instance records at most 60 refusals of one key a minute (any `4xx`: at
  admission, over its quota, a bad id); the refusals beyond
  are counted, and the count is named on the key's next recorded refusal (`refused_unrecorded`).
  *Original `audit.ts`, `server.ts:181-193`, `audit` suite; the Loki line replaced.*
- **FR-037**: Each player whose identifying fields a request served MUST also be the subject of an
  audit record naming the key, the field names and the time, so a subject access request answers from
  the shared audit by the player (spec 018): every key served an identifying field of theirs, with the
  fields and the times. *Original 211 SC-011.*
- **FR-038**: Each key's request volume, error rate and latency MUST be answerable for the operator
  from the key's audit records. *Original 211 FR-030.*

**Test doubles and recordings**

- **FR-039**: The service's test kit MUST seed two brands' facts, a key of every kind (none, some and
  all identifying fields; disabled; expired; deleted) and a catalogue, so every scenario runs with no
  realm and no other service.
- **FR-040**: The original's test suites (`casino/public-api/test/`, its contract, routes and games
  directories) MUST be re-expressed as this service's own, one for one where the behaviour is kept,
  and the recordings under *Recordings* MUST be made against the original before the port (001
  FR-029, FR-030).

### Key Entities

- **access key**: one brand's credential for the public API: a name, an id, the start of the key, a
  hashed secret, enabled or not, an optional expiry, the identifying fields it may read, its requests
  per minute, who created it and when, when it was last used; deleted keys are kept, without the hash.
- **Record views**: the service's own projections of the brand's players, sessions, rounds, bets,
  transactions and bonus awards, keyed by the brand's ids, with identifying fields as `Personal`
  values.
- **Statistics snapshot**: one hourly run's game and player figures and rankings, with its id and
  computation time; current, retained or retired.
- **Audit record**: one per request and one per key change, naming the key, the operation, the
  players and field names served, never a value or a secret.
- **Public contract**: the published OpenAPI document, immutable per version, served beside its
  successor for six months.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero cross-brand records: every route walked with another brand's ids answers
  `not_found`, verified on every release.
- **SC-002**: 95% of single-record reads answer within 500 ms and 95% of full pages within one
  second, on a brand with twelve months of history and a player with 10,000 transactions.
  *Deferred for full pages* (2026-11-12): by-id reads are met (7 ms p95); a player's rounds page
  over a million rounds is 2.66 s p95, because ankka 0.16 views declare no index (research.md,
  *The brand at its size*; the finding for ankka: indexes declared on a view). `StatsPerfSuite`
  keeps the 1 s limit, so it fails until then.
- **SC-003**: A fact is visible through the API within 30 seconds for 95% of facts.
- **SC-004**: An operator creates a key and a caller reads with it within five minutes, with no
  realm change and no release.
- **SC-005**: A disabled or deleted key is refused on the first request after the change, every time.
- **SC-006**: A key at its full rate causes no measurable change to another key's response times.
- **SC-007**: Across 1,000 automated crawls of a player ranking that span a snapshot change, every
  crawl returns each ranked player exactly once or is told its snapshot expired.
- **SC-008**: An hourly run finishes within five minutes for a brand with one million rounds in 30
  days.
- **SC-009**: Zero identifying values or key secrets in diagnostics, checked automatically on every
  release.
- **SC-010**: An unintended contract change is caught by the build on every release.

## Changes from the original

- **Keys replace partner clients.** The original's caller is a Keycloak confidential client in the
  `eitheror` realm with a five-minute JWT, scope claims and a grant in a Helm value. Here a brand's
  operator creates keys in the backoffice; the service issues them, stores a hash and checks them on
  every request. There is no partner record, no realm client, no scope claim and no grant file. The
  contract's authentication guide, security scheme and authentication error codes change (FR-002).
- **No agreement is required for identifying fields.** The original served them only with a data
  processing agreement reference and lawful basis on the grant. Here the operator chooses per key;
  the audit records every field served.
- **Records come from the brand's topics**, into the service's own views, not from the shared twin
  store per request. The original's five-second store budget becomes the service's request budget.
- **Statistics are computed by this service** from its own views, not by the substrate's hourly job
  through its aggregate route; the windows, rules, snapshots and retention are the original's.
- **Rate limits count across instances.** The original counts per replica, so its effective limit is
  the limit times the replica count.
- **Paging breaks ties by id.** The original orders by one timestamp with no tie-break.
- **Revocation is immediate.** The original's disable took up to a minute (the grant file re-read)
  plus the token's remaining five minutes.
- **The audit record goes to the shared audit**, not a Loki line (spec 018).
- **Scope levels are gone.** A key belongs to one brand's project.
- **The player-listing defect is not ported.** The original drops session-shaped records from its
  player listing, so its pages can be short; here no such record exists.
- **The grant's `cost` and `maxConcurrentQueries`/`monthlyQueryBudget` fields are not carried
  over**: parsed by the original and read by nothing.
- **A bet and its BET transaction share one id.** The original held them as two twins with two ids.
- **A bonus award is ACTIVE where the original could say WAGERING**: no fact carries the word.
- **Four transaction families are not served**: `restricted_funds_closed` (no amount), and the
  account and prediction families, which the original did not have.

## Recordings

The original's Go end-to-end suite has one scenario for this API,
`tests/e2e/scenario252_partner_api_e2e_test.go`, a two-brand walk validated against the contract. It
and the following are recorded against the original on kind with a seeded partner client and grant,
under `tests/port/public-api/` (001 FR-029, FR-030). Parity tests compare bodies and statuses; the
credential differs by design (FR-002), so authentication refusals are compared by status and code
class only.

1. the two-brand walk of every route (scenario 252);
2. another brand's id on every by-id route and as every collection's parent;
3. a player's transactions paged to the end at 100, and a limit of 101;
4. a page past the end, and an empty collection;
5. a player read with no identifying fields and with all of them;
6. the games listing, one game, a disabled game;
7. a game ranking by each figure and window, and one game's figures;
8. a player ranking paged across a snapshot change, a retired snapshot, a status filter;
9. one player's figures;
10. a key over its rate;
11. the contract and reference routes.

The contract file, `specs/255-casino-public-rest-api/contracts/public-api-v1.openapi.yaml`, is copied
into this repository, amended only as FR-002 says, and is the parity test for FR-001.

## Assumptions

- The BI system is the first caller; others follow when a brand chooses to issue them a key.
- No caller outside eitheror is on the original's `/v1` today (its spec 255 *Assumptions*), so `/v1`
  is kept with the changed credential rather than a `/v2`.
- `casino.sessions` stays at 90 days (001 FR-015). The service's views keep what they projected; a
  view rebuilt from the topic recovers 90 days of sessions (Clarifications 2026-11-12, first
  answered for the GraphQL spec).
- The catalogue is small (low thousands) and may be held whole for five minutes.
- The backoffice's key screen is built in the backoffice frontend, outside this repository, against
  the operator routes here.

## Dependencies

- Spec 001: topics (FR-011, FR-015), route classes (FR-020), personal data (FR-023–FR-025), the
  gateway (FR-034), the port method (FR-029–FR-033). Its partner route class and partner wording are
  amended by this spec.
- Spec 003: the catalogue and the brand's curation.
- Specs 002, 007, 008, 009, 011: the facts and status words the views map. `players`, `game-provider`,
  `wallet` and `rewards` are amended on this branch, additively (Clarifications 2026-11-12).
- Spec 018: the shared audit and its subject access query.
- Spec 019: record ids kept at the migration.
- Spec 021: the module this service is (renamed from `partner`).
- ankka 042 (`Personal`, erasure), 043 (topic retention in a view's status).

## Out of scope

- Any write for callers.
- The backoffice's key screen (frontend).
- A second contract version.
- Filtering, search, brand-wide feeds and arbitrary statistics periods (the original's follow-ups).
- Reading across brands with one key.
