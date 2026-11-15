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
