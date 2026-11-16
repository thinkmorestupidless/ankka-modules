# Research: how the original backoffice is used

Sources for the findings in [spec.md](spec.md), *Context*. Paths are in
`~/Documents/projects/eitheror/eitheror-platform` unless stated. **[E]** is what a source says;
**[I]** is an inference from it. Gathered 2026-11-11 from specs, commit history (258 commits touch
`casino/frontend` and `payments/frontend`), incident reports,
`docs/operator-assistant/reference/known-issues.md`, `scripts/`, `casino/operator-mcp`, the
eitheror brain and Claude project memory.

## 1. "Why did this happen?" cannot be answered in the backoffice

- [E] Original spec 258 (operator MCP server): "every non-trivial customer question ('why was this
  withdrawal capped', 'where did this bonus go', 'is this a bug') ends with an engineer reading twin
  history, wallet transactions, graph edges, and logs by hand." Its phase 2 is an assistant drawer
  inside the backoffice.
- [E] The withdrawal investigation recipe (project memory) uses substrate REST through a
  port-forward, twin history, `kubectl exec` with cypher-shell, Loki, and nginx access-log byte
  deltas.
- [E] `casino/operator-mcp/src/tools/` is the investigation surface operators lack: find_player,
  player_overview, player_timeline, wallet_transactions, withdrawal_requests, payment_attempts,
  rewards_and_restricted_funds, kyc_status, rg_status_and_history, player_sessions_and_rounds,
  resolve_reference, twin_history, search_logs, projection_status.
- [E] Original spec 128: the activity tab "silently drops event categories when any one data
  source fails. Operators make support and compliance decisions on incomplete information."
- [E] Original spec 194: before it, "an operator has no way to discover which rounds exist for a
  player"; disputes start from ids pasted from tickets and provider reports.
- [E] Known issue: player search sent free text as a player id, producing 760 HTTP 500s.

## 2. Lists silently lost rows

- [E] Generic list reads returned at most 500 rows, oldest first, or a default of 20. Fixes:
  db4076735, 5dfad1946, 91fe3e62e (pool exhaustion, empty Players and Wallets), original spec 242
  (Wallets), 246 (Bonuses: "operators could not find or revoke most bonuses", 542 grants with 20
  shown), 248 (Transactions and Analytics), cb4e8dab6 (duplicate rows).
- [E] Known issue `graphql-twins-oldest-first-cap`, still open: "If a row is not there but the
  detail page loads, suspect the cap."
- [E] Original spec 180: the Players page fetched 500 players and filtered eight filters in memory.
- [E] Known issue `twin-projection-hourly-lag`: lists 190 to 380 seconds stale between about :11
  and :20 every hour; the graph froze for eight hours on 2026-10-29. Nothing on screen says so.

## 3. Money on screen could not be trusted

- [E] Withdrawal amounts shown in minor units: 16c373b66, 094856b92. Inconsistent currency
  formatting: 7f056ccc4.
- [E] Known issue, open: the timeline shows €0.05 for a €5 withdrawal.
- [E] A refund a hundred times too large (a228241cf), corrected afterwards by an adjustment.
- [E] Known issue `silent-withdrawal-cap`, open: lists show `amount` only and hide the requested
  and voided amounts.

## 4. Reward states opaque, actions confused

- [E] Incident 2026-09-30 (free rounds reward not cancellable): support emptied a reward with an
  adjustment, which left it active; the right tool was a forfeit. "The agent was working without
  the information or tools they needed"; "Support had no guidance on … the difference between
  emptying a bonus and forfeiting it."
- [E] Original spec 174: after a suspected-duplicate flag was cleared, manual grants failed with a
  generic error; the revoke button called a route that did not exist.
- [E] Free rounds shown PAUSED and 0.00 "by construction", 100/100 while the player saw 95/100,
  two games of the same name indistinguishable: 9f6c90ed6, d5f6e07fe. "Every 30 s poll blanked or
  dimmed the list": 6393149d4.
- [E] Original spec 251: €750 of a player's deposits voided and "refunded by hand".
- [I] About 35 commits touch reward operations: the area most confusing to operators.

## 5. A withdrawal's story was scattered

- [E] Original spec 250: "Operators currently have no single place to see the complete story of a
  withdrawal"; the player tab showed only the pending request; no balance context, reward impact
  or approver. Reviewing a withdrawal is "the most common review action".
- [E] 2abe0b30b: the screen told operators to confirm or mark failed a payout needing review, but
  the buttons never rendered and the routes answered 409.

## 6. Corrections happened outside the backoffice

- [E] Original spec 170: the adjust button reused the deposit action; corrections inflated total
  deposited, GGR, provider and RG figures, and had no actor or reason.
- [E] Incident 2026-08-19: support made six adjustments totalling €100 by hand. Incident
  2026-08-14: "Someone with backoffice operator access needs to be assigned to apply the
  admin_adjustment credits".
- [E] `scripts/mark-st8-credit-settled.py`: a win paid by hand through an adjustment is not linked
  to the provider's credit, so the provider's retry pays it again.
- [E] Repairs by script, not screen: `backfill-forfeited-grants.sh` (383 grants),
  `repair-migrated-dead-records.py`, `dedupe-duplicate-deposit-twins.sh`,
  `reconcile-st8-unsettled.py`, `bonus-conversion-reconciliation.py`.

## 7. Roles and brand selection broke pages

- [E] 26d84ae02: an operator narrowed to a sub-brand got 403 on Transactions. de6162171: a
  holding-level operator with no selection got 403 on Players, the KYC queue, Wallets and RG.
  Also 23f5b95d7, d36d756cc, 12b9b1b93, 8df14f055, ac6ba02cc; brain decision 0045.
- [E] Inventory of `casino/frontend` (2026-11-11): `brand_finance` and `casino_admin` are checked
  by the screen and the backend but are not realm roles; the withdrawal queue shows approve and
  reject to every operator; the screen admits `resource_admin` to KYC actions the backend refuses
  it; the KYC policy is read-only on screen for `brand_compliance`, which the backend lets write.
- [E] Original spec 135 (from a security audit): support reads only; compliance handles RG and
  KYC; finance approves withdrawals; brand_admin configures. Not carried into the backend for
  adjustments, limits, suspension, self-exclusion or closing an account.
- [E] Inventory: the scope is loaded from browser storage and never checked against the token.

## 8. Daily friction

- [E] bf0da23de: KYC reviewers "lost their filter, page and scroll position every time they opened
  a player"; first-in first-out queues asked for.
- [E] 4487c2bba: clicking month by month to a birth year "was unusable".
- [E] 065e751ec: the "until" date filter excluded the chosen day.
- [E] 4ecd72483: debits silently clamped.
- [E] Original spec 182: agents asked to be told when a player comes online, to message them by
  hand.

## Most frequent operator tasks

[E] where a source ranks them, [I] otherwise: look up a player and read their standing; review
and settle withdrawals ([E] spec 250); work the identity queue first-in first-out; grant, forfeit
or reclaim rewards and find why a grant failed; adjust a balance; investigate a dispute through
the timeline, rounds and payments; set limits, breaks and self-exclusions; reach out to players
online; configure the brand; run the affiliate programme.

## Security findings not to port

- `casino/backoffice-bff` `GET /kyc-doc-view?url=` fetches any address it is given, unauthenticated.
- The content system's write token is compiled into the backoffice bundle.
- The working scope is read from browser storage unchecked.

## The inspiration

Four images supplied by the product owner on 2026-11-15 (dark wallet and trading dashboards; kept
outside the repository). *Visual direction* in the spec states what was taken from them.

---

# Plan research: decisions

Made by `/speckit-plan` on 2026-11-15, on `origin/main` at `c07af6d6`, ankka 0.16.3. Each is a
decision, its reason, and the alternative that was close. The route check behind R9 to R14 read the
endpoint code of all 17 services with an operator route; its results are in
[contracts/amendments.md](contracts/amendments.md) and [parity.md](parity.md).

## R1. Two services in the brand's `backoffice` module

- **Decision**: one backoffice per brand, in the brand's own project, as the module `backoffice`
  (clarified 2026-11-15): `backoffice` (web-hosted, TypeScript: the page and its server) and
  `backoffice-state` (Scala: operator sessions and preferences).
- **Why per brand**: a brand is deployed on its own, possibly in its own cluster; ankka's calling
  address reaches only the installation it is in. A backoffice inside the brand's project calls the
  brand's services by name and needs nothing to cross a project for casino work.
- **Why two services**: a web-hosted service has no database and nothing it holds survives its
  instances (ankka web hosting). FR-003 needs the sign-in held on the server across instances and
  rollouts, and FR-038 needs preferences kept; constitution VI puts anything that keeps state in
  Scala.
- **Alternatives**: one shared backoffice for every brand (the first draft). Rejected: it serves
  only brands of its own installation, and rests wholly on a cross-project call. The sign-in sealed
  into a cookie, with no session store. Rejected: a Keycloak access and refresh token is over one
  cookie's 4 KB, refresh-token rotation races between instances, and "sign out ends both" (FR-003)
  cannot be enforced for a stolen cookie.

## R2. The sign-in is exchanged and refreshed by `backoffice-state`

- **Decision**: `backoffice-state` holds the realm's confidential client secret. It makes the
  authorisation-code exchange (with PKCE), keeps the tokens in a `Session` entity, and answers
  `POST /sessions/{id}/token` with an access token that has at least 30 seconds left, refreshing it
  itself, one refresh at a time per session. The page's server holds only the session id it reads
  from an `HttpOnly`, `Secure`, `SameSite=Lax`, `__Host-` cookie, and caches a token for no longer
  than its remaining life.
- **Why**: one writer per session makes rotation safe; the secret and the tokens never reach the
  TypeScript process's disk or the browser.
- **Alternative**: the page's server does the exchange and stores tokens through a plain key value
  route. Rejected: two instances refreshing at once lose the session.
- **Session entity**: tokens are credentials, not player data; the operator's subject, name and
  email are `Personal` under `operator/<subject>` (constitution V) and erased by the handler.

## R3. Calls are made by the page's server at the calling address, never under a mount

- **Decision**: the descriptor has no mounts. A call to the brand's own service goes
  `$ANKKA_SERVICES_URL/<service>/<path>`; a call to a shared project's service goes to that
  project's address from the brand's values, by default
  `$ANKKA_SERVICES_URL/<service>.<project>/<path>`. Both carry
  `Authorization: Bearer <operator's token>`.
- **Why**: a request under a mount arrives as the internet with whatever the browser sent, and the
  browser holds no token (FR-003). The proxy passes `Authorization` as the process gave it (it
  removes only `X-Ankka-*` and hop-by-hop headers).
- **To verify first (V1)**: (a) that an operator route of a service in the same project answers a
  call from `Service(<brand>, backoffice)` carrying a valid operator token; (b) the same for a
  shared project's operator route, with no grant. `Routes.operator` and `paymentsOperator` refine
  only the token, so both should hold; the documentation does not say so in terms. If (a) fails it
  is an ankka request and the build stops at T001. If only (b) fails, the screens that read a
  shared project (payments administration, deposits, the programme, the audit) wait for the ankka
  request, and everything else is built. No grant is made for the operator's calls: constitution
  III.

## R4. One route table admits what the browser may ask for

- **Decision**: the page's server serves `/api/s/<service>/<brand or scope>/…` only for the method
  and path templates in one table, `routes.ts`, from which the typed client is also made. Composite
  routes exist only where the backoffice combines sources: worklist, timeline, search, adjustment
  batch, document view, events.
- **Why**: about 300 operator routes; a hand-written handler each would be the drift the original
  had. The table keeps the browser from naming a service or path the backoffice never meant to
  reach (FR-031's "fetches no address it is given", generalised), while each owning service still
  decides admission.
- **Alternative**: a GraphQL layer. Rejected: a second schema to keep true to seventeen services.

## R5. Routes require a permission; roles are bags the brand keeps at run time

- **Decision** (clarified 2026-11-15, three answers):
  1. Every operator route of a brand's services requires one permission,
     `Routes.operatorMay(slug, permission)`, and names no role. The contract holds the permissions'
     names and the role matrix (the default roles and bags).
  2. The brand's `players` service keeps the roles: a `Role` entity each, seeded from the matrix
     when the brand first starts, changed through `roles` routes by an operator holding
     `roles.change`, each change an audit record.
  3. Every other service of the brand holds a copy through `contract.access.RoleBags`: it asks
     `players` for the roles every 10 seconds and decides from the copy, with no call in a request.
     Until its first copy it answers `Unavailable`; if `players` cannot be reached it keeps the
     last copy.
  4. The token carries roles only. The backoffice's server reads the same roles to answer the
     operator's permissions (`/api/me`) and the bags (`/api/permissions`).
  5. Giving an operator roles: the backoffice asks the brand's `players` (`role-assignments`),
     which refuses when the giver lacks a permission the roles carry, then asks the shared
     payments' merchant, which owns operators, to set them in the realm, under a grant the brand
     makes for that one route.
- **Why `players`**: it already keeps the brand's own configuration (sites, countries, currencies,
  modules); a service for roles alone would be one more database and deployment per brand.
- **Why a polled copy and not facts on a topic**: one helper in the contract library, against a
  consumer and a stored copy in each of twelve services. The cost is at most 10 seconds of
  staleness, inside FR-008b's 15. The fetch is supplied by the service, so the contract library
  stays on its own ankka pin.
- **Why merchant sets them**: operators are realm users and merchant already invites, resets and
  revokes them; a realm admin credential in every brand project is avoided.
- **Alternatives rejected**: permissions in the token (bloat); bags in the contract's code (a bag
  change would be a release and a rollout); bags as deploy-time values (no code, but a restart, and
  no screen for an administrator).
- **Limits**: role names are the realm's. An operator of two brands holds the same role names in
  both; what a role carries is each brand's own. A different role per brand for one operator is out
  of scope (spec, *Out of scope*). Shared services (merchant, the programme, the audit) open no
  brand's bag: they keep their own role checks.
- **Payments roles**: in no bag, and not a brand's to give. They keep `Routes.paymentsOperator`.
- **To verify (V6)**: that an ACL refinement may decide `Unavailable`; if not, the unread case is
  refused with a `503` from the endpoint's own first line.

## R6. The currency table is the contract's, published

- **Decision**: minor-unit counts come from `currencies.v1.json`, written from the same
  `Amounts.exponent` the services use. The page formats with `Intl.NumberFormat` given those
  digits explicitly.
- **Why**: Node's ICU and the JDK disagree on some currencies' digits; FR-013 and SC-007 need one
  rule.

## R7. Stack

- **Decision**: Node 24; TypeScript 5; the template of `ankka init --language web` (React 19,
  Vite); Hono for the server's routes; TanStack Router and Query; Tailwind 4 with design tokens as
  CSS variables; Radix primitives; Recharts; `d3-geo` with a bundled world map; `intl-messageformat`
  over one typed catalogue (FR-040a); Zod at the server's edge; vitest, Testing Library,
  `playwright-bdd` and `@axe-core/playwright`.
- **Why**: the template is ankka's; React, Vite, Tailwind, Radix and Recharts are what the
  original's operators' screens are built in, so the recorded *before* is comparable. Query keeps
  the rows already shown while it refetches (FR-012) and keeps list state for return (FR-010).
- **Alternative**: a server-rendered framework. Rejected: the process must stay a thin HTTP program
  beside ankka's proxy, and Next's server would become a second place for rules.

## R8. Deploying the module

- **Decision**: `modules/backoffice/module.json`, depending on `core`: `backoffice` with
  `hosting: "web"`, exposed at the brand's backoffice host; `backoffice-state`, not exposed,
  admitting only `Callers.service("backoffice")`. The vendor's manifest schema already allows
  `hosting: "web"`; `Render` passes it through. The page's server listens on the default process
  port and needs no mount and no `callers`. `Realm.ensure` makes one confidential client per brand,
  `backoffice-<slug>` (audience `eitheror-casino`), and the role `brand_support`. `release.yml`
  builds an image for a service directory with a `package.json` as well as a `build.sbt`.
- **Why**: the tool renders `Embedded` or `Process` only, imports the realm only when it is
  missing, and CI enumerates `services/*/build.sbt`.

## R9. Worklist sources

- **Decision**: the worklist is the page's server asking each source's own queue, in parallel,
  with a 3 second bound each; a source that fails or is late is `unavailable` (FR-017). Kinds and
  sources are in [contracts/sources.md](contracts/sources.md). Counts come from each source's
  `total`.
- **Gaps closed by amendment**: a count for "payments needing review" (payouts), a brand-wide list
  of open cases (responsible-gambling), paging and totals on the identity queue, the fraud cases
  and the unacknowledged calls.
- **"Undelivered facts"** is the glossary's term: attribution's `attribution-webhooks/undelivered`.
  The payments project's undelivered outcomes are their own kind (FR-016).

## R10. Timeline sources

- **Decision**: the page's server merges, by time, the player's lines from `players` (activity:
  money, rounds, rewards, account, sessions; notes and messages), `payouts` (withdrawal history
  with states), `kyc` (decisions), `responsible-gambling` (the player's history) and `wallet`
  (adjustments with their reason and cause). Each source is read by period and cursor; the merge
  is a k-way merge that never emits past the earliest unread source's cursor. A source that cannot
  be read is named with the period missing (FR-022).
- **Why**: `players` `activity` merges five topics and has no identity, limits or withdrawal state
  lines; one more consumer in `players` would hide which source is late. Reading the owners keeps
  the gap visible and each owner's admission in force.
- **Gaps closed by amendment**: period and cursor on `activity`; an operator route for a player's
  responsible-gambling history; a decisions list in kyc; actor names (R12).

## R11. One search

- **Decision**: the server classifies what was pasted by shape (a UUID, an email, a provider
  round or transaction id, a payment reference, a document reference, free text) and asks only the
  resolvers that shape can match, in parallel; every match is listed by kind (FR-019). Free text
  goes only to `players` `search`.
- **Gaps closed by amendment**: game-provider resolves a round id without its provider, and a
  transaction id; merchant resolves a payment reference; rewards' reward read is admitted to
  readers.

## R12. Actors by name

- **Decision**: services answer the operator's subject id; the server resolves names through
  merchant's `/v1/operators` list (cached five minutes) and shows the id where the reader may not
  list operators.
- **Why**: FR-021 ("operator by name") without a second copy of operators.

## R13. Freshness

- **Decision**: every service with a view-backed operator list answers `GET …/status` in
  reporting's shape, `{views: [{name, asOf, rebuilding}]}`; the page asks it beside each list and
  shows "as of" and *provisional* (FR-011). `asOf` is the time of the last change the view applied.
- **To verify (V2)**: that ankka 0.16.3 lets a service read a view's last applied time. If not, the
  view's own consumer records it in a key value entity; the route shape does not change.

## R14. Lists

- **Decision**: every operator list is paged by its owner with `page`, `pageSize` (max 200) and
  `total`, in the order FR-009 names; the in-memory paging over at most 10,000 rows in `players`
  becomes a view query. Amended lists are named in contracts/amendments.md.
- **Why**: SC-005 seeds 10,000 players and 5,000 withdrawal requests; six lists are capped or
  unpaged today.

## R15. Adjustment batch

- **Decision**: the page sends the rows; the server makes one wallet adjustment per row with the
  key `batch/<batch id>/<row number>`, eight at a time, streaming each row's outcome. The batch id
  is the SHA-256 of the reason, the cause and the rows, so the same list repeated is the same batch
  and the wallet answers each row's first outcome (constitution II). At most 500 rows.
- **Why**: FR-028 and the spec's assumption (no state beyond the session).

## R16. Jobs and notices

- **Decision**: exports stay the owners' jobs (reporting's documents, players' exports). The page
  keeps the references it started in the operator's preferences (`jobs`, at most 20, no player
  data: a reference and a kind), polls them, and shows the result wherever the operator is. Online
  notices are engagement's `agent-stream`, relayed by the server as server-sent events on
  `/api/events` (the proxy streams event by event).

## R17. The brand and its modules

- **Decision**: the brand is the project's (`CASINO_BRAND`, as every brand service reads it). An
  operator whose roles do not name it is told so and sees nothing. The brand's enabled modules come
  from `players`' `modules` route; an area whose module is off is absent. The addresses of the
  shared projects are values of the brand.

## R18. Tests

- **Decision**: three levels, as the services have. Unit (vitest): money, dates, the matrix, the
  merge, the classifier, the route table. Bound features (`playwright-bdd` over
  `features/backoffice/`): the page and its server against a scripted services double at
  `ANKKA_SERVICES_URL`, one double per owner, answering from fixtures recorded from the real
  services' suites. Integration (`just backoffice-it`): a brand on the local installation
  (`dev-kind`), seeded, with the real services, running the quickstart's scenarios and SC-005 to
  SC-008.
- **Role scenarios** in `access.feature` that state what a service admits are also bound in that
  service's own `GherkinSuite`: the service is where the rule holds.

## R19. Recording the original (constitution, *Workflow*)

- **Decision**: no ankka-port recording is replayed: this spec replaces a frontend, and each
  owning service's recordings already hold its routes. What is recorded is the original's screens,
  one capture per screen per role, by a Playwright script against the original on kind, kept under
  `tests/original/backoffice-screens/` (images outside git; the index and script in it), as the
  *before* for the design review at the end of each phase.

## R20. Performance

| Measure | Target | How |
|---|---|---|
| Worklist | 2 s p95, never more than 3.5 s | parallel sources, 3 s bound each |
| Player page, first standing | 1.5 s p95 | five reads in parallel; the timeline loads after |
| Search | 2 s p95 | resolvers in parallel, 3 s bound |
| A proxied read | the owner's time plus 30 ms p95 | no body parsing on pass-through |
| Token for a request | under 5 ms p95 when cached | in-process cache to the token's expiry |

## To verify at task time

| | What | If it does not hold |
|---|---|---|
| V1 | an operator route answers the backoffice's call with the operator's token: (a) in the brand's project, (b) in a shared project | (a) an ankka request, and the build stops; (b) an ankka request, and the shared projects' screens wait |
| V2 | a view's last applied time is readable | the view's consumer records it |
| V3 | `ankka local web` accepts `--service name.project=url` for a shared project's service | the double listens under the dotted name |
| V4 | Keycloak issues the `backoffice-<slug>` client's token with audience `eitheror-casino` and the realm roles | a mapper is added in `Realm` |
| V5 | the proxy streams server-sent events from the process unbuffered through the gateway | long polling on `/api/events` |
| V6 | an ACL refinement may decide `Unavailable` | the endpoint answers `503` itself before its handler |

## Found during implementation

### V1, 2026-11-15 (T001)

On `dev-kind` (platform 0.16.3), the probe `tests/spikes/backoffice-v1/` ran as a web-hosted service
in goldvibe's project and called `players`' `GET /v1/casino/backoffice/goldvibe/players` at
`ANKKA_SERVICES_URL`, with no grant:

| Call | Answer | Answered by |
|---|---|---|
| (a) no token | `401 authentication required` | `players` |
| (a) a malformed token | `401 authentication required` | `players` |
| (a) a `brand_goldvibe` operator's token | `200`, the brand's players | `players` |
| (b) `merchant.payments`, no token | `503 no service 'merchant' in the project 'payments'` | the proxy |

(a) holds: the owner judges the person by the token, and the caller being the web-hosted service
changes nothing. The build goes on.

(b) is not yet known: this installation has no `payments` project, so the call never reached
merchant. The proxy's own answer shows the calling address does route `<service>.<project>`. It is
checked again when the shared projects are applied to `dev-kind`. Until then the backoffice's calls into shared projects (T071's reads, the shared rows of T108, T112,
T113, T117 to T119) are built against the doubles and wait on it for their checks against real
services.

### The brand's modules for every operator, 2026-11-15 (T037)

`/api/me` names the brand's modules so an area the brand does not run is absent (R17), but players'
`GET …/brands/<slug>/modules` requires `modules.read`, which only `brand_admin`'s default bag
carries. Decided (Trevor): players gains `GET /v1/casino/brands/<slug>/players/modules` on its
`ServiceEndpoint`, admitting the project's services as its `roles` route does (amendment P14); the
page's server reads it under its own identity and keeps it for one minute for every operator, as
the table *Held in the page's server* already said. `modules.read` stays the permission of the page
that lists and requests modules. Rejected: `modules.read` in every default bag (a change to the role
matrix, and a brand that took it out of a role would break that role's frame); reading with the
operator's token and showing every area when refused (against R17).

### `personal` does not reach a module's descriptor, 2026-11-15 (T034)

`backoffice-state` keeps operators' names and emails as ankka's sealing `Personal` and declares
`"personal": true` in its `service.json`. The vendor's manifest schema refuses `personal` on a
module's descriptor (no such property), so a service applied through a module carries no such
flag; engagement, reporting, public-api and support-agent are in the same position today. Whether
the installation's erasure notices reach a personal service applied through ankka-vendor is the
vendor's to say: an ankka-vendor question, recorded here, not worked around in the manifest.

### V2: a view's last applied time, 2026-11-15 (T051)

Fails. ankka 0.16.3 gives a service no reading of its views' progress: the view client queries rows
only, a change's context carries its subject, sequence number and origin but no time, and the
projections' offsets live on the management port and in the runtime's own tables, which a service
does not reach. R13's fallback is built: in each service, every view behind an operator list has its
source followed by a consumer of its own (`freshness.ViewFollower`, one per source, so a keyed view
with two sources has two) that records the time it handled a change in a key value entity named by
the view (`ViewMark`), at most once a second per instance. `asOf` reads it: when the view applied its
last change, to within the two projections' lag and that second; null until the source has a
change. `rebuilding` is false everywhere: a view rebuilt by raising its version replays where the
service cannot see and the follower is not rebuilt with it, so nothing honest can be inferred
(reporting infers it from its day consumers, which no other service has). The route is
`GET /v1/casino/brands/<slug>/<service>/status` on each service's service endpoint, admitting an
operator holding the permission that opens the service's lists, or a service of the project. The
paths are `players/status`, `wallet/status`, `payouts/status`, `kyc/status`, `rg/status` and
`rewards/status` under `/v1/casino/brands/<slug>/`; responsible-gambling answers no view until G2's
brand-wide cases list (T059) brings one, which adds its follower beside the others.

## Design finished

2026-11-15: reworked after the first finish, on three decisions made in review: one backoffice per
brand in the brand's project; permissions, with roles as bags the brand keeps at run time; merchant
sets an operator's roles. The checker is green; the analysis was repeated by hand for coverage and
leftovers, not by a full `/speckit-analyze` run. Nothing above LOW was found: FR-020's "without
scrolling" is checked by eye at 1440 by 900; SC-002 and SC-003 need people; role names are the
realm's and shared between brands that use it.

### Reserved role names, 2026-11-15 (T015)

The design reserved `platform_admin`, `brand_<slug>` and the payments roles only. Every other realm
role that an ACL checks by name was open, so a brand admin could create a role named
`audit_compliance` (or `affiliate_manager`, `api_keys`, `reidentify_<slug>`, …), give it, and merchant
would grant the real realm role. Decision (Trevor): reserve every realm role but the four default
roles, and every name under `brand_`, `audit_compliance_` and `reidentify_` but the defaults.
`OperatorPermissions.RealmRoles` lists the realm's roles; `OperatorPermissionsSuite` holds it equal to
`brands/_realm/eitheror-operators.json`. players and merchant both refuse a reserved name.
contracts/roles.md amended.

### The operator's preferences are written through the server, 2026-11-15 (T053)

contracts/routes.md had no route for the page to write preferences, and the page cannot call
`backoffice-state`. Decision (Trevor): `PUT /api/preferences`, taking `theme`, `worklistKinds` and
`onlineNotices`, the subject taken from the operator's token; `jobs` stay the server's own, written
only by `/api/jobs`. The row is added to contracts/routes.md. The worklist's kind ids
(`shared/worklist.ts`) are what `worklistKinds` holds.

### What "provisional" can mean after V2, 2026-11-15 (T052)

V2 failed (above), so a service's `asOf` is when its view's follower last handled a change, not how
far the view is behind its facts, and only reporting can say it is rebuilding. `/api/freshness` marks
a list provisional when a view says it is rebuilding or its service's status cannot be read, and
never by the age of `asOf`: on a quiet brand that is old and right. FR-011's "catching up" is
therefore shown only for reporting's views until ankka lets a service read a projection's lag; the
freshness scenario (`features/backoffice/freshness.feature`) seeds `rebuilding: true` and holds.
`Freshness.at` may be null: a view that has applied nothing shows no time, only *provisional* when it
is.

### Manager views of an affiliate's portal, 2026-11-15 (T116)

affiliate-programme keeps both manager views, `accounts/{a}/portal/{view}` (A2) and the port's
`accounts/{a}/view/{page}` (spec 013 O29–O34, the parity recording's path). Both go through one
function that writes `VIEWED_AS`, so no manager read skips the audit. `accounts` answers
`{items, total, page, pageSize}`; `limit` and `offset` are gone, since no caller used them. Two
postback-retry tests fail on HEAD as well ("not observed within 20 seconds"); they are not this
change's.

### A brand's operators read their sub-brands' payments, 2026-11-15 (T071)

merchant admits a brand reader (staff holding `brand_<slug>`, or `platform_admin`) to the cashier
list, the summary and a payment of the sub-brands that name their brand (`SubBrandState.brand`).
Another brand's sub-brand, one naming no brand, or an unknown one answers `403 scope_violation`. A
payments operator who also holds a brand role keeps exactly their payments access. `?reference=`
matches the payment id, the cashier's transaction id or the provider's transaction id, exactly.

### The dashboard and the reports, 2026-11-15 (T098–T102)

- Freshness reads reporting's own shape: `completeFrom` per view, the service's being the latest
  (T052, above).
- A job's state comes from reporting's `GET /documents`; `job_done` watches the operator's jobs
  started within the last 24 hours, and a job whose state reporting cannot give is looked at again
  next time.
- Export stays offered below 1280 px: it changes nothing, so FR-041's read-only mode does not
  withhold it.
- The reports area opens with `export.player` as well as with the report permissions.
- The charts are hand-written SVG (line and candle, donut, dot grid, the map). The map uses
  world-atlas 2.0.2 and topojson-client 3.1.0 (`@types/topojson-client` 3.1.5).
- Gap: reporting's `/dashboard/geo` takes no period, so the map shows all time whatever period the
  dashboard is set to.

### US7's commit under US8's message, 2026-11-15

`00a1d35a` carries the header of T099 and T100 but holds US7's 17 files: two agents committed through
the shared index at once. Nothing was lost; history is not rewritten. Agents now commit through a
private index (`commit-own.sh`).

### Identity and protection, 2026-11-15 (T093–T097)

- `00a1d35a` holds T095 and T096 (the identity queue, a player's identity and protection, the
  protection cases), under T099/T100's header (above).
- The document route (FR-031) follows kyc's signed link only under `BACKOFFICE_DOCUMENT_STORE`: same
  scheme, host and port, under the store's path, no credentials, no redirect. Nothing in a page's or
  another owner's body is ever fetched. No module value sets the variable yet, so until one does the
  route answers `503 document_store_unconfigured`.
- The brand's time zone is not readable by every operator (`/api/me` has none; `brand-config` needs
  `config.brand`). Times show in the browser's zone with a note naming it, as the frame does.
- Lists the owners do not page yet (K2, P11, G2) are paged on the page from what the owner answers;
  T057–T060 replace that.
- FR-034: kyc says only whether the whole policy is the default and which licence profile it follows,
  and responsible-gambling gives no defaults. So the policy page marks the policy as a whole, and an
  edited field is marked "changed, not saved".
- A player's tabs are components taking the player id (`PlayerIdentity`, `PlayerProtection`), routed
  through `tabs.tsx` until T075's player page takes them.

### The worklist and its sources, 2026-11-16 (T056–T064)

- Payments needing review are asked with `status=ALL&payoutStatus=NEEDS_REVIEW&sort=oldest`.
  payouts still defaults `status` to pending review, so `payoutStatus=` alone (as contracts/sources.md
  wrote it) answers nothing. sources.md is corrected to match.
- payouts' `counts {pendingReview, needsReview}` are brand-wide and ignore the query's filters, as kyc's
  counts do. kyc's `queue` pages when `page` or `pageSize` is given; unpaged it answers the first 1,000
  rows and now a `total`. responsible-gambling keeps a keyed view `rg-cases` (one row per case, no
  notes, so erasure has nothing to change in it) for `rg/cases`. players' `fraud-cases` lists open cases
  oldest first and other statuses newest first; an out-of-range page falls back to its default, as its
  other lists do.
- Undelivered facts show as `unavailable` (`no_source`) to administrators: attribution's `undelivered`
  needs `network=`, so the brand has no single list to ask. Undelivered outcomes are left out: they
  have no brand permission and psp-gateway has no row in the table. Both are gaps for a later spec.
- An item opens beside the list, linking to its investigation. Only withdrawals are acted on there.
  After acting, the item closes and the next row (or the one before it, at the end) becomes current,
  with the page and scroll unchanged (FR-018).
- "Who" acted is the operator's subject from payouts' decisions, or "you"; payouts records no name.
- The view of every kind together pages through the first 50 items of each kind. One kind's view pages
  through its owner.

### game-provider's rounds by id, 2026-11-16 (T069)

`rounds/by-round/{roundId}` reads the round at each configured provider in `casino-providers` order.
`rounds/by-transaction/{transactionId}` finds the round through the transaction's `BetWorkflow`, the
lookup a cancel already uses. It answers only when the round recorded that transaction, so a
transaction still in flight answers 404. `rounds` is now ordered in the view (newest opened first);
before, an unordered `LIMIT` was sorted in memory, so a long list came back as an arbitrary subset.

### Corrections and rewards in the owners, 2026-11-16 (T084, T089)

- The wallet answers `amountMinor`, `before` and `after` in minor units beside its major-unit text
  fields. `before`, `after` and `cause` come from the adjustment's stored first outcome, so a repeat
  answers what the first call did. The same key with another cause is `KEY_REUSED`. The transaction
  row gains `category` beside `cause`, as sources.md lists it.
- rewards answers `allowedBy`, a list of codes the backoffice words, on every ineligible answer, the
  player's included. A reason that binds an operator's award too never offers `operator_award` (FR-006).
- `features/backoffice/rewards.feature` has an operator's award refused for the claim limit. In
  rewards an operator's award skips the claim rules, so that refusal comes only from a player's path.
  The US6 binding must use a player's claim, or the scenario must be reworded.

### Deposit bounds, 2026-11-16 (T106)

A bound's "method" is the catalogue category of the terminal's gateway and scheme, slugged (`card`,
`bank_transfer`, `wallet`, …). A scheme the catalogue does not know falls back to the scheme itself.
`gateway-config`'s `methodGateways` uses "method" for the scheme; the two are different words for
different things. Bounds are kept at sub-brand level only and are not inherited. Both bounds are
inclusive, as the original compares them. A refused deposit answers 403 (the original preflight's
status), ends the workflow before the gateway is called, and records no payment.

### A raised limit, 2026-11-16 (T093)

`compliance.feature`'s third scenario said a compliance operator's raise takes effect later.
responsible-gambling applies an operator's change at once, as the original does; only a player's raise
waits. Decision (Trevor): the scenario is reworded to a player's pending raise, which the page shows
with the limit in force and when the raise applies. The binding uses a double that answers what
responsible-gambling answers.

### Identity documents are streamed by kyc, 2026-11-16 (FR-031)

The backoffice followed kyc's signed link only under `BACKOFFICE_DOCUMENT_STORE`, and no module value
set it, so every document would have answered 503. Decision (Trevor): kyc streams the document itself
to the operator who may review it, and the backoffice follows no address at all. This is a new kyc
amendment (K5 in contracts/amendments.md). The document route passes kyc's bytes through unchanged.

### Withdrawals, 2026-11-16 (T078–T082)

- `balancesAtRequest` (the real balance available, a Long) and `restrictedAtRequest` (the rewards'
  balance, a Long) come from the hold preview the request's checks read just before the hold. The
  hold's own answer does not give the restricted balance from before it.
- payouts answers `transitions[] {status, at, by, operatorId?, reason?}`, derived from the existing
  events so older journals have them too. "Every state with who moved it" needs a time and an actor
  for awaiting-manual and needs-review, which decisions and outcomes alone cannot give.
- The queue's `playerSearch` takes several exact ids separated by commas (at most 200), so a name or
  an email resolved through players' search (at most 100 ids) keeps the page and the total right.
  When players is unavailable, the queue answers 503, never "nobody".
- Confirm and mark-failed are offered in `awaiting_manual_payout` and `needs_review`, where payouts
  accepts them (`WithdrawalWorkflow.manual`), and no longer while a payment is in flight.
- The amounts shown: requested always; then held while open, paid when completed, or returned when
  rejected, cancelled or failed. Voided shows whenever it is more than zero, and it always means a
  reward's cash-out cap.
- The queue opens on pending review, newest first, as the original does.

### Corrections, 2026-11-16 (T083–T087)

- No owner holds a brand's list of adjustment reasons, and the original took free text only. The
  backoffice's catalogue gives the categories (compensation, goodwill, error_correction,
  provider_failure, chargeback, fraud_recovery, other), sent as the wallet's `category` beside the
  free-text `reason`.
- A batch's id is the SHA-256 of its reason, category, cause and rows, so the same form run again is
  the same batch. The reason is typed on the form, not in the confirmation dialog.
- A single adjustment keeps its key while the wallet's answer is unknown, so a retry cannot make it
  twice. The key is replaced after any answer the wallet gives.
- Decision (Trevor): the batch scenario's closed player is reworded. As in the original, an operator
  may adjust a closed player's balance, so the refused row becomes a refusal the wallet really makes.
- Decision (Trevor): the wallet's adjustment answer carries `repeated: true` when it replays a stored
  outcome, an extension of W2. The batch reads it for "already made" in place of reading the balance
  first.
- The wallet's major-unit text is minor units divided by 100 for every currency (`Wire.major`), so
  JPY 500 reads "5.00" and KWD's three decimals are lost in the text. The page reads the minor fields,
  and the text by the same rule. This is the wallet's own wire, not this spec's: a gap to raise.

### kyc's documents, decisions and protection's history, 2026-11-16 (K5, T070)

- K5 changes kyc's operator view route in place (`…/kyc/{p}/views/{d}` answers the bytes, inline,
  `no-store`, `nosniff`), because only operators call it; the player's route keeps its signed link.
  The original's operator view is a declared parity divergence (spec 009 plan.md). An object the
  store no longer holds answers 404 `document_not_found` and records no view. The backoffice passes
  the bytes through and answers a redirect from kyc 502.
- K3's `decisions` needs `identity.read`, with document ids only for `identity.review`, as `GET /{p}`
  does. An override's reason and a dismissal's note are personal and never included. K3 and G3 take
  `from` and `to`, as contracts/sources.md names them.
- The glossary's *document store* and spec.md's US7 lines are brought in line with K5 and the raise
  decision.
- Gap: a streamed document is still under `pass()`'s 15 s timeout for the whole body. That is enough
  at kyc's upload cap, but not for a slow, large transfer.

### players for the investigation, 2026-11-16 (T066–T068)

- The regression from P1 (bd098288): the operator endpoint's `Acl.DenyAll` is checked for any path no
  route matches, so an unknown route answered 403. The endpoint ACL is `Routes.operator(slug)` again,
  and every route keeps its own permission (8d6ede28).
- The players list combines `search` with the filters, as the original's spec 180 does. Each facet
  applies every other filter and leaves out its own. `identity` is `verified` or `unverified` only,
  which is all players knows. Online means an open player session. Birthday windows are today, 7 and
  30 days; a 29 February birthday counts on 28 February in a common year.
- A game session's gap follows the original: the wallet's bets naming the session against the sum of
  its rounds' stakes, rollbacks not counted. The gap compares two topics read by separate consumers,
  so their lag can show a gap for a moment, as in the original.
- Bets are read from rounds (a new `rounds` view, `RoundEntry` per round), so `betId` is now the round
  id. The totals are summed in memory over up to 100,000 matching rounds, because the view client
  cannot sum.
- The new consumers (`casino.rounds`, `money.transactions` into `SessionWagers`) read from the earliest
  message on first deployment; the players view is raised to version 2 and rebuilt.
- A player session ended by an operator has status `forced`, and its game sessions' audit records name
  the operator.
- Risks: the birthday filter, like the existing display-name search, compares a personal field as
  text. ankka 042's encryption of personal fields will refuse both queries at start. players'
  `lists.feature` freshness scenario stays unbound: "the views are catching up" cannot be shown while
  `rebuilding` is always false (V2).
- `repeated` (7432a7fd) is answered only on the operator's adjustment route; the support agent's route
  strips it, so its answer is unchanged. It is never stored. The batch scenario's refused row is a debit
  larger than the player's real balance (1b391777).
