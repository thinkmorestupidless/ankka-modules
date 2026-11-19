# Review: spec 030, the backoffice (origin/main...HEAD, 228 commits)

**Verdict:** Request changes
**What it does:** It adds a per-brand backoffice module: a web-hosted TypeScript page and server that call the owning services as the signed-in operator, and a Scala `backoffice-state` service that keeps sessions and preferences. It also moves every operator route of seventeen services from role names to permissions. Roles become bags of permissions that the brand keeps in `players` and that every service polls through `RoleBags`.
**Should it exist:** Yes. The spec, the constitution amendment 1.3.0 and research R1 to R20 make the case for replacing the original console rather than porting it. The design (owner admits the person, one route table, no rule in the page) is sound and is held to in the code read.
**Read:** The server tree `services/backoffice/src/server/` was read in full (main, session, auth, proxy, roles, routes, events, every composite) with `scripts/owners.ts`. All of `services/backoffice-state/src/main` and its build, service.json, SessionSuite and RealmBackofficeClientSuite were read in full. The `players` roles package (`Role`, `RoleAssignment`, `RoleRules`, `Roles`, `RolesRoutes`), `OperatorSessionRoutes`, `Rounds`, `Freshness`, `Devices` and all of `merchant/src/main` were read in full. Read through the diff: the rest of the players amendments; `tools/modules` (Realm, Keys, Values, LocalApply, BrandCommands); `modules/backoffice/module.json`; `brands/_realm/*`; `.github/workflows/ci.yml` and `release.yml`; the Justfile; the constitution diff; the `tests/original/*-driver` NotDriven entries; `tests/spikes/backoffice-v1`; `personas.sh`. Skimmed: the generated owner clients, `package-lock.json`, `index.json`, the two generated JSON files, and most test bodies other than those named. The surrounding code opened outside the diff was kyc's `ObjectNames` and `DocumentRoutes`, `Routes.serviceOf`, and the `eitheror` realm's `profile` scope mappers.
**Not reviewed (needs another pass before merge):**
- The permission amendments to wallet, payouts, kyc, responsible-gambling, rewards, game-provider, engagement, support-agent, reporting, attribution, public-api, psp-gateway and affiliate-programme.
- The contract's `OperatorPermissions`, `RoleBags` and `Routes.operatorMay`, and the schema writer.
- `services/backoffice/src/shared/` and `src/web/` (about 27k lines), with the web, feature and a11y tests.

The reviews of these three areas had not reported when this report was written. Nothing below speaks for them.
**Size:** 741 files, +79,085 −820 (hand-written +70,231 −820). That is far above what one review can check line by line. Defect-finding on this range is necessarily partial. A split this review would accept:
1. The contract and `RoleBags`, the `players` roles and merchant H6.
2. The permission amendments, service by service (each already one task).
3. `backoffice-state` and the modules tool.
4. The backoffice server.
5. The page, by phase.

## Blocking

- `services/merchant/src/main/scala/io/eitheror/casino/merchant/api/OperatorsEndpoint.scala:180` and `services/players/src/main/scala/io/eitheror/casino/players/api/RolesRoutes.scala:77`
  - issue (blocking): Giving a role in one brand also gives it in every other brand the operator belongs to. Realm role names are shared across brands (spec.md:824, contracts/roles.md:16), and `setBrandRoles` assigns the realm role itself.
  - Why it matters: brand A's administrator can make an operator who also holds `brand_b` a `brand_admin` or `brand_finance` of brand B, under B's bags, without B's consent. The escalation check in `give` weighs only A's bags. The `project != brand` guard only stops A writing B's record. This contradicts constitution III, "a brand is a project".
  - Suggestion: either scope the realm role per brand (for example `brand_admin@<slug>`, which `RoleBags` and `/api/me` would read), or refuse in H6 a role for an operator who holds another brand's `brand_<slug>` until that brand agrees. Either way, record the decision in contracts/roles.md.
  - Declined: not fixed in 030. The person chose per-brand realm roles (2026-11-17): role names in the realm that say which brand they hold in, read by `Routes.operatorMay` for its own brand only. That changes the contract, every service's role reading, H6, players, the backoffice and the Spinvibe migration's role mapping, so it is spec 032, cut from this branch; 030's pull request waits on it.
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/session/Session.scala:51` and `session/RealmClient.scala:26`
  - issue (blocking): `Opened` and `Refreshed` journal the access and refresh tokens in the clear, because `Secret` encodes `x.reveal`. The access token is a JWT whose `profile` scope carries `email` and `preferred_username` (`brands/_realm/eitheror-operators.json:117-135`; the client takes `profile` at `tools/modules/.../Realm.scala:171`).
  - Why it matters: the same name and email the session carefully seals as `Personal` sit unsealed in every event. Destroying the subject's key on erasure does not remove them, and an ended session is only expired after 24 h. That breaks constitution V. Research R2's "tokens are credentials, not player data" misses the claims inside the token.
  - Suggestion: seal `Secret` under `operator/<subject>` (or a service key) in the event codec, or give `backoffice-<slug>` a scope without the email and username mappers. If ankka 0.16.3's delete physically purges the journal, say so in R2 and cite it.
  - Resolved: `Signed` and `Renewed` record both tokens as `Personal` of `operator/<subject>`; `Opened` and `Refreshed` are read, never written; the unit suite holds the events to it and replays an `Opened`, the integration suite holds the database to no token in the clear; research R2 and data-model.md corrected, 0c0866f5
- `services/backoffice/src/server/composite/worklist.ts:203`
  - issue (blocking): the `undelivered_outcomes` kind asks psp-gateway for `/outcomes/undelivered` and `/outcomes/refused`, but the table's rows are `/psp/outcomes/undelivered` and `/psp/outcomes/refused` (`routes.ts:357-358`). `match` answers `no_source` and the kind is never shown, so FR-016 is not met for payments operators.
  - Why the test misses it: `tests/unit/worklist.test.ts:149-150` supplies its own rows with the same wrong paths, so it cannot fail on this.
  - Suggestion: use the `/psp/…` paths, and build the worklist test's rows from the real `table`. A test that every worklist ask matches a table row would catch the next one.
  - Resolved: the kind asks `/psp/outcomes/…`; the test uses the real table, its doubles refuse an operator with no payments role as psp-gateway does, and a new test holds every source a kind asks to a row of the table, 379d2fa5; the access scenario now holds shared-project calls to naming no other brand, 5c85b3c6

## Should fix

Roles and operators (players, merchant):

- `services/players/src/main/scala/io/eitheror/casino/players/api/RolesRoutes.scala:88` — issue (non-blocking): `give` replaces the operator's whole set, but `escalation` checks only the roles asked for, not those taken away. Anyone holding `roles.give` can strip `brand_admin` from every administrator, including the last holder of `roles.change`. Suggestion: check the permissions of `before -- asked` too, and refuse a giving that leaves no operator holding `roles.change` (FR-008d).
  - Resolved: give is held to the permissions of the roles it takes away as well as those it gives (what the brand gave last, from RoleAssignment, less what is asked; RoleRules.givingLacks), with RoleSuite and RoleGivingSuite tests, e21d1e8d, fea37a66. Refusing a giving that leaves no operator holding roles.change is not added: players records only its own givings, not the cutover's, and realm role names are shared across brands until 032, so the brand cannot count holders; carried to 032.
- `services/players/src/main/scala/io/eitheror/casino/players/api/RolesRoutes.scala:47` (also :58, :69) — issue (non-blocking): `leavesNoChanger` reads the `Roles` view, which lags the entities. Two concurrent changes, each removing `roles.change` from a different role, both pass. While the view is still empty at start, any change passes. Suggestion: keep the brand's bags in one entity (one writer), or re-check after the write and compensate.
  - Resolved: a defect 032 does not remove, since the permission sets stay players' own. The new RoleChangers entity is the one writer of which roles carry roles.change: a change taking it away is claimed there first and refused when none would remain; RoleChangersSuite and a concurrent-PUT test in RoleChangesSuite (one 200, one 409 last_role_changer), 5db9324c
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/api/OperatorsEndpoint.scala:200` — issue (non-blocking): `gone` removes only roles this brand recorded in `brandRoles`.
  - The cutover of FR-008a assigns roles in the realm directly, so a brand can never take away a migrated operator's role. That contradicts FR-008c ("give and take away").
  - Conversely, a role held from cutover, then given by a brand and later dropped by it, is removed realm-wide.
  - Suggestion: seed `brandRoles` at the cutover (spec 019 X1), or derive ownership from the realm plus the record.
  - Declined: moved to spec 032 (per-brand realm roles, the person's decision of 2026-11-17), which rewrites how a brand's roles are kept and set in the realm (H6), so a fix here would be rewritten; recorded in 032's description.
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/api/OperatorsEndpoint.scala:196` — issue (non-blocking): the realm is changed role by role before `setBrandRoles` is recorded.
  - A failure part-way leaves realm roles no brand owns, so none can ever remove them.
  - Two brands' concurrent PUTs read-then-act on `others`.
  - Suggestion: record the intended set first, then apply it idempotently from a workflow keyed by the operator.
  - Declined: moved to spec 032 (per-brand realm roles, the person's decision of 2026-11-17), which rewrites how a brand's roles are kept and set in the realm (H6), so a fix here would be rewritten; recorded in 032's description.
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/KeycloakAdmin.scala:109` (and :127) — issue (non-blocking): the operator id goes into Keycloak admin URLs unencoded. H6 does not require the operator entity to exist, so the id is no longer one merchant made. Suggestion: validate it as a UUID at the route, or `enc(userId)`.
  - Resolved: every user id in a Keycloak admin path is encoded as one segment (KeycloakAdmin.segment; . and .. encoded too), and H6 refuses an id not of the realm's shape with 400 INVALID_OPERATOR_ID before the realm is asked; KeycloakAdminSuite and an OperatorsSuite test; also settles the out-of-scope note on the older calls, c235cbf6

Lists and paging (players, merchant):

- `services/players/src/main/scala/io/eitheror/casino/players/api/OperatorSessionRoutes.scala:144` — issue (non-blocking): `limit` on `GET players/{p}/bets` has no upper bound (`filter(_ >= 1)`), so one request can return 100k rows. Suggestion: cap it at 200 like the other lists.
  - Resolved: limit on GET players/{p}/bets is capped at 200 (MaxBets), tested in SessionsBetsSuite, d8cb023c
- `services/players/src/main/scala/io/eitheror/casino/players/api/OperatorSessionRoutes.scala:127` (and :156, :51-71) — issue (non-blocking): every bets request loads up to 100k rows through `RoundsView.all` to compute `total` and the sums. `total` is wrong past 100k, and `RoundsView.total` is unused. The sessions list makes one view and one entity call per game session (N+1). Suggestion: SQL `count`/`sum`, and use `RoundsView.total`.
  - Resolved: total and totalBets come from the view's count (RoundsView.total); the sums stay over at most SumLimit rounds because ankka views answer rows, never aggregates (build/views.md), and summary.truncated says when the cap was reached. The sessions page reads its game sessions in one query, their rounds in one, and the wallet's bets at once; tests in ListBoundsSuite and SessionsBetsSuite, d8cb023c
- `services/players/src/main/scala/io/eitheror/casino/players/api/OperatorStatusRoutes.scala:250` — issue (non-blocking): `facets=true` runs one count query per distinct value of each field. An `Await.result` timeout becomes a 500. Suggestion: one `GROUP BY` per field, and answer without facets on a timeout.
  - Resolved: on a late or failed count the list is answered without facets, with facetsUnavailable: true, and logged (ListBoundsSuite), 9e452495. GROUP BY is declined: an ankka view statement selects the rows' payload and the view client counts only by condition (build/views.md), so the counts stay one per value, run at once.
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/api/OperatorsEndpoint.scala:113` — issue (non-blocking) (if-minor): the operators list fetches up to 10,000 entities to sort by `createdAt` before it pages. Suggestion: put `createdAt` in `OperatorRow` and page in the view.
  - Resolved: OperatorRow carries createdAt, scope is SQL (OperatorsView.scoped, the inScope rule), and the view orders, pages and counts; only the page's entities are read. The view is at version 2 and rebuilds on its next deploy; OperatorsSuite's H5 test covers ancestor scope and another holding's none, c77463a2

The backoffice server:

- `services/backoffice/src/server/proxy.ts:16` (and `composite/document.ts:45`) — issue (non-blocking): `Answered` passes the owner's `content-length` back but not `content-encoding`. Node's fetch decompresses, so a compressed answer goes out with the wrong length (a truncated body, or a corrupt keep-alive connection). Suggestion: drop `content-length`, or pass it only when the owner sent no encoding.
  - Resolved: Content-Length passed on only when the owner sent no Content-Encoding (fetch decodes the body); every passed answer is nosniff, b53d1e9c
- `services/backoffice/src/server/routes.ts:180` — issue (non-blocking): `GET /api/s/kyc/{playerId}/views/{documentId}` is an ordinary pass-through row. The browser can fetch a document without the `nosniff`, CSP, `no-store` and attachment headers `composite/document.ts` adds, which weakens FR-031. Suggestion: mark the row composite-only so `/api/s` refuses it. Add `X-Content-Type-Options: nosniff` to every proxied answer.
  - Resolved: the kyc views row is marked composite-only and /api/s answers it 404, so only /api/kyc/{p}/documents/{d}/view (with its headers) reaches it; nosniff on every proxied answer, 63d8a77b (nosniff in b53d1e9c)
- `services/backoffice/src/server/proxy.ts:61` (and `main.ts:149`, `:239`, `:320`, `:339`) — issue (non-blocking): request bodies are buffered with no size limit, from any signed-in session. Suggestion: `hono/body-limit` on `/api/*` (1 MiB, larger only on upload rows).
  - Resolved: hono bodyLimit of 1 MiB on /api/*, refused 413 body_too_large, checked by stated length or while streaming, before the session or any owner; no upload row needs more (kyc uploads and creatives go by link), 4a91b4f7
- `services/backoffice/src/server/main.ts:475` — issue (non-blocking): on SIGTERM, `server.close()` waits for open `/api/events` streams, so every rollout runs to the grace period and is killed with requests in flight. Suggestion: a shutdown `AbortController` the events loop watches, then `closeAllConnections()` after a deadline. Handle SIGINT too.
  - Resolved: a shutdown AbortSignal ends every /api/events stream, including its wait; SIGTERM and SIGINT close the server and idle connections, then call closeAllConnections after 8 s (ankka's proxy allows 10), fc36a6d7
- `services/backoffice/src/server/composite/timeline.ts:394` — issue (non-blocking): `operatorNames` caches merchant's answer for 5 minutes per process, filled by whichever operator's token asked first. A refused first caller blanks names for everyone; an admitted one shows names to operators merchant would refuse. Suggestion: cache only successful answers, and key the cache by subject or serve it only to admitted callers.
  - Resolved: merchant's names are kept per subject (5 min, at most 1,000); a refusal is kept for that operator only, and an unavailable, late or unreadable answer is not kept, d671fa2a
- `services/backoffice/src/server/composite/timeline.ts:164` (and :226, :373-380) — issue (non-blocking): messages and withdrawals are read newest-first across all time, not for the period. A source that says `more` with no lines in the period never sets `cut`, so `next` can be `null` with older lines missing and no gap named (FR-022). Lines at exactly the cut instant are also lost, because the next page asks for `t < cut`. Suggestion: set `cut` or a gap whenever a source says `more` and its oldest line is newer than `period.from`. Ask those sources for the period where they support it.
  - Resolved: a period-less source (messages, withdrawals) that says more and gave only lines after the period is a beyond_page gap; lines at the cut instant move to the next page, which takes lines at or before `after`; contracts/sources.md updated, 0b2677aa
- `services/backoffice/src/server/main.ts:362` — issue (non-blocking): `/api/permissions` returns the brand's whole role map to any signed-in realm user, including one whose roles do not name the brand. `/api/search`, `/api/freshness`, `/api/jobs` and `/api/s` also skip the check, though the owners still refuse there. Suggestion: apply `brand_not_in_roles` in the `/api/*` middleware, except for `/api/me` and `/api/events`.
  - Resolved: brand_not_in_roles is checked in the /api/* middleware for every route except /api/me and /api/events, and the per-route copies are removed, 0b43ec7a

backoffice-state and the modules tool:

- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/session/Refresh.scala:42` (and :49-53, :69-70) — issue (non-blocking): a step retry, or a restarted `Failed` flow, asks the realm again with the same refresh token after an `Unavailable` (a lost response). Where refresh-token rotation is on (`Realm.scala:49,65` turns it on for brand realms; the `eitheror` realm's setting on existing installations is unknown), the second ask is refused and the operator is signed out. Suggestion: assert `revokeRefreshToken=false` for the `eitheror` realm in the modules tool, or treat a refusal after an unavailable attempt as one to end without retrying.
  - Resolved: the modules tool refuses a backoffice client in a realm with revokeRefreshToken on (the eitheror template has it off), so a retried refresh cannot be refused; Refresh documents the reliance, 3b478652
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/Erasure.scala:27` — issue (non-blocking): erasure finds sessions through the eventually consistent `SessionsBySubject` view, capped at 1000 rows (`SessionsBySubject.scala:36`). A session opened just before the erasure stays open with live tokens. Suggestion: answer not-done until the view has caught up, or retry; at least document the window.
  - Resolved: erasure ends sessions, waits 2 s and looks again (3 looks) for sessions the view had not shown; the remaining window is documented in data-model.md, d60a1a77
- `tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala:217` — issue (non-blocking): an existing `backoffice-<slug>` client never has its `redirectUris`, `webOrigins`, `rootUrl` or post-logout URIs updated. A changed backoffice host breaks sign-in while the tool reports no change. Suggestion: compare the address-derived fields and update them, or at least print a note when they differ.
  - Resolved: an existing backoffice client's redirectUris, webOrigins, rootUrl, post-logout URIs and secret are compared and patched, and the change is reported, e4f41337
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/Settings.scala:42` — issue (non-blocking) (if-minor): an empty `BACKOFFICE_CLIENT_SECRET` is accepted at start. Every sign-in then fails as `realm_unavailable`. Suggestion: refuse to start, naming the variable, outside an explicit local mode.
  - Resolved: an empty BACKOFFICE_CLIENT_SECRET is refused at start, naming the variable, unless BACKOFFICE_LOCAL=true, 47be20a7

The branch:

- `Description` — todo: T123 to T128, T133 and T136's cluster half are unticked. research.md:928 says they are written but not run, waiting on spec 031's GKE installation. So SC-004 to SC-007 and R20's targets are unproven against a running brand. The pull request must say so, as research.md promises, and name what still gates a real-money brand on them.
  - Resolved: the pull request's body says T123 to T128, T133 and T136's cluster half have not run, that they wait on spec 031's ephemeral GKE installation, and that SC-004 to SC-007 and R20's targets are unproven against a running brand until then; and that 030 also waits on spec 032 (per-brand realm roles).
- `Commit 3c5652ad` (branch head) — todo: the branch is 2 commits behind `origin/main`. `9a922c4f` changes `WalletEntity`'s adjustment handler (an operator's credit opens the wallet) and `features/wallet/adjustments.feature`, the same code W2 amends. The merge is textually clean; rebase, and run the wallet suites and features again on the result.
  - Resolved: rebased onto origin/main (9a922c4f, c975ca20), clean; on the result wallet's suite passes 813 of 813 and `just features` reports 0 findings in 1477 scenarios.

## Nits

- `CLAUDE.md:86` — nitpick (non-blocking): it still says `backoffice-seed`, `backoffice-it` and `backoffice-record-original` are "declared for scripts not yet written (T123 to T128, T131)". `tests/it/*.it.ts`, `seed.ts` and `tests/original/backoffice-screens/record.ts` exist, and T131 is ticked.
  - Resolved: CLAUDE.md says what the three recipes do and that the suites wait on spec 031, d3df8127
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/session/RealmClient.scala:81` — nitpick (non-blocking): `revoke` short-circuits, so a failed revoke skips the logout that ends the realm's SSO session. Attempt both and report the first failure.
  - Resolved: revoke attempts both the revocation and the logout and reports the first failure, 3dfe27b4
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/session/Tokens.scala:54` — nitpick (non-blocking): the endpoint polls with `Thread.sleep(50ms)` for up to 20 s. That is fine on virtual threads; otherwise it holds a shared thread. Confirm ankka 0.16's endpoint threading.
  - Answered: ankka 0.16.3 runs HTTP handlers on virtual threads (HttpServer, AnkkaExecutors.virtual), so the poll holds no shared thread; no change
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/preferences/Preferences.scala:49` — nitpick (non-blocking): "names a player" is a prefix heuristic, so a bare player id passes as a job reference. Say so in data-model.md; FR-038's "refused" reads stronger than this.
  - Resolved: data-model.md names what 'names a player' refuses and that a bare player id passes, 2f6dbf33
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/session/RealmClient.scala:29` — nitpick (non-blocking): Scala hygiene, one pattern in three places:
  - `nullValue = null` here;
  - `asInstanceOf` in `InMemoryRealms` in main sources (`tools/modules/.../Realm.scala:329`);
  - no request timeout on `KeycloakAdmin.clientExists` and `setClientSecret` (`Realm.scala:268-286`).
  - Resolved: nullValue is Secret("") (3dfe27b4); asInstanceOf is removed from InMemoryRealms, and KeycloakAdmin has a 30 s request timeout (e4f41337)
- `services/backoffice-state/src/test/scala/io/eitheror/casino/backoffice/session/SessionSuite.scala:100` — nitpick (non-blocking): "two concurrent token calls" runs sequentially in the unit kit, so it tests generation gating, not a race. The race is covered by `it/StateIt.scala:196`; name the unit test for what it checks.
  - Resolved: the unit test is named for the generation gating it checks; the race stays in it/StateIt.scala, 2f6dbf33
- `.github/workflows/ci.yml` — nitpick (non-blocking): the new jobs pin actions by tag (`@v4`, `dorny/paths-filter@v3`), not by SHA. This matches the rest of the repository.
  - Left: nit (tags, as the rest of the repository's workflows pin them)
- `services/backoffice/src/server/main.ts:199` (also :222, :248, :407, `composite/withdrawals.ts:36,59`) — nitpick (non-blocking): a malformed `%` (`URIError`) or an owner's non-JSON 2xx becomes a 500. Answer 400 or 404, or `unreadable`.
  - Resolved: a malformed escape in a player id is 400 invalid_player and in a built path 404; a non-JSON 2xx is 502 unreadable (timeline export, withdrawals' players and payouts reads), 03ba4ee9
- `services/backoffice/src/server/composite/batch.ts:191` — nitpick (non-blocking): small server points.
  - The batch stream has no `cancel()`; it stops by `enqueue` throwing.
  - `composite/jobs.ts:92-101` can drop a job when two quick posts race.
  - `events.ts:220,225` uses `Date.now()` where the rest uses `deps.now`.
  - `main.ts:370` takes `:service` and `rest` from different parses of the path.
  - Resolved: the batch stream has cancel() and stops taking rows; job keeps are serialised per subject within one instance; the relay uses the server's clock; /api/s takes the service and the rest from one parse of the path, a8241f5e
- `services/players/src/main/scala/io/eitheror/casino/players/api/RolesRoutes.scala:54` — nitpick (non-blocking): small players and merchant points.
  - `nameRefusal(name).get` behind a guard; bind it in the pattern.
  - `KeycloakAdmin.scala:127`: `Json.parse(body).get`.
  - `PlayersService.scala:106`: `seedUntilItHolds` runs before the shutdown hook is registered at :131.
  - `DepositBounds.validate` accepts any method name, so a typo is stored and never applies.
  - Resolved: nameRefusal bound in the pattern in reset, and the shutdown hook registered before the role seeding, 81cea9f3; KeycloakAdmin's Json.parse(body).get is a Failed realm error, c235cbf6. DepositBounds.validate accepting any method name is Left: nit (a method may be a scheme slug outside the catalogue's categories; restricting it needs a decision).

## Questions

- `services/players/src/main/scala/io/eitheror/casino/players/api/RolesRoutes.scala:47` — question: `set` lets a holder of `roles.change` add any permission to any role, including their own, so `roles.change` amounts to every permission. Is that intended? If not, refuse a `set` that adds a permission the changer lacks, as `give` does. Either way, state it in contracts/roles.md.
  - Answered: not intended, and 032 does not remove it, since it is about the permission sets, not realm names. A set or reset that adds a permission the changer lacks is now refused 403 forbidden, naming it, as give refuses; platform_admin and the default brand_admin are unaffected; tested in RoleChangesSuite, 1a06b1af; contracts/roles.md states the rule.
- `tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala:284` — question: `PUT /clients/{id}` sends only `{clientId, secret}`. Does the installation's Keycloak keep the client's other fields on a partial update? A run against the it kit's container, followed by a GET and a comparison, settles it.
  - Answered: probed against Keycloak 24.0.5 in a Docker container: a partial PUT /clients/{id} keeps omitted fields and merges attributes; the tool now sends a patch of the existing client either way, e4f41337
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/api/OperatorsEndpoint.scala:215` — question: `BrandRoles.acl` admits `Caller.Local`, which ankka gives every call outside a cluster with caller TLS. Does any deployed installation run without caller TLS? This matches `ErasureEndpoint`.
  - Answered: no. Per ankka's platform/networking.md every connection a workload accepts in an installation is mutual TLS, and one without a client certificate is refused in the handshake before any ACL runs; Caller.Local is given only to a service running outside a cluster, so no deployed installation admits it.
- `services/backoffice/src/server/session.ts:114` — question: `TokenCache` has no single-flight, so parallel requests on an expiring token each ask `backoffice-state` to refresh. The state service gates by generation; confirm a burst cannot trip the lost-response retry above.
  - Answered: a burst cannot trip the lost-response retry: backoffice-state joins all callers to one SessionRefresh workflow per session and generation (start is the atomic claim) and the retry runs inside it. The cache now waits on one ask per session and reports rolesChanged once, 95e96f44
- `services/backoffice/src/server/session.ts:116` — question: after sign-out, other instances serve the cached token until it expires. Is that window within FR-003's intent?
  - Answered: no; the window was up to the access token's life less 30 s, short of FR-003's 'signing out ends both'. A cached token is now reused for at most 10 s, so a sign-out holds at every instance within 10 s, 95e96f44
- `services/backoffice/src/server/proxy.ts:69` — question: `AbortSignal.timeout(15 s)` also covers reading the body, so a slow export or document download is cut mid-stream. Is the plan's 15 s bound meant for the first byte or the whole body?
  - Answered: the bound is for the first byte, like ankka's own 60 s bound. It now covers the start of the answer and each wait for the next body chunk, so steady downloads pass whole and a silent owner is cut, fc0f7c71
- `services/backoffice/src/server/composite/batch.ts:101` — question: the batch id hashes the whole list, so re-sending an edited list (one row fixed after a dropped connection) gives every row a new key and remakes rows already made. Is that what R15 intends? Per-row keys derived from the row and an operator-chosen batch id would avoid it.
  - Answered: yes, a defect against FR-028. Keys are now per row (batch id from reason, category and cause; row id from what the row adjusts and its occurrence), so an edited batch sent again makes only the rows not yet made; R15 updated, 8463738d (test typing 75699586)
- `services/backoffice/src/server/events.ts:263` — question: nothing limits `/api/events` streams per operator. Each tab polls three sources every 5 s and may hold its own engagement connection. Is a per-subject cap wanted?
  - Answered: yes; one sign-in (shared by a browser's tabs) holds at most 8 event streams, and a ninth is 429 too_many_streams until one ends, d648dfc2
- `services/backoffice/src/server/main.ts:72` — question: the Origin check falls back to `http` when `X-Forwarded-Proto` is missing, which would refuse every write behind TLS. Does ankka's ingress always set it? Comparing against the configured address removes the dependency.
  - Answered: ankka always sets X-Forwarded-Proto, except on an installation with no base domain, where it sends no X-Forwarded-* header (reference/web-hosting.md), so writes would have been refused there. The Origin is now compared with BACKOFFICE_HOST's origin, 620d766d

## Praise

- `services/backoffice/src/server/proxy.ts` — praise: the pass-through is careful.
  - Request headers are an allow-list (no browser `Cookie` or hop-by-hop headers reach an owner), and owners' `Set-Cookie` is dropped.
  - `safe()` refuses `.`, `..`, `%2f`, `%5c` and `\` in a segment.
  - The brand is URL-encoded into templates, and 503 `source_unavailable` is answered only for the proxy's own failures.
- `services/backoffice/src/server/auth.ts` — praise: the sign-in is right.
  - `__Host-bo` is `HttpOnly; Secure; SameSite=Lax; Path=/` with no domain.
  - `return` refuses `//`, `/\`, absolute URLs and control characters, and is checked again on the way back.
  - The state is compared in constant time with S256 PKCE, and the Origin and `X-Backoffice` check runs before every mutating route, sign-out included.
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/session/RealmClient.scala:26` — praise: `Secret` and `RealmTokens` redact in `toString`, a test asserts nothing prints a token, name or email, realm failures are described by status only, and the token route answers `no-store`.
- `services/players/src/main/scala/io/eitheror/casino/players/player/Devices.scala` — praise: the user agent and fingerprint are never stored (digest and family only). Devices are `Personal`, cleared on erasure, included in the export and capped at 50. Every new field on a stored type in players and merchant has a default, and new events were appended rather than old ones edited.
- `.github/workflows/release.yml` — praise: one list of service directories covers both sbt and Node builds, the Node image is tagged as sbt tags the others, and `CASINO_RELEASE_REQUIRE_BUILDS` accepts `backoffice`. There is no `pull_request_target`, and secrets reach only the login and vendor steps.

## Out of scope

- note: `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/KeycloakAdmin.scala:104` and :133-168 already put ids into admin URLs unencoded before this change. File an issue to encode every path segment in `KeycloakAdmin`.

## Checks applied

**General checklist groups:**
- Applied: Correctness, Design and scope, Error handling, Concurrency and state, Security, Data and migrations (stored-type evolution; there is no SQL DDL in the areas read), Performance, Tests, Observability, API and compatibility, Dependencies and build (CI and release workflows, Dockerfiles), Documentation, and Commit and PR hygiene.
- Commit hygiene: all 228 headers parse as Conventional Commits with the attribution trailer, and there are no fixup or WIP commits.

**Language skills:** the bundled `pr-review-scala` and `pr-review-typescript` (no repository override exists). `pr-review-go` was not loaded: the six Go files change only `NotDriven` maps of strings.

**Project rules read:**
- `CLAUDE.md` and the workspace `CLAUDE.md`.
- `.specify/memory/constitution.md` (principles III, V and VI were the ones decisive here).
- spec.md, plan.md, tasks.md, research.md (R2, V1, the 2026-11-17 decision), contracts/roles.md and contracts/amendments.md.
- No root `REVIEW.md`; the per-spec `REVIEW.md` files belong to other specs' directories.

**Enforced by tooling, not reviewed:** formatting (`.scalafmt.conf`, the backoffice's eslint configuration).

**Tests run:**
- `npm run typecheck` in `services/backoffice`: clean.
- `npx vitest run --dir tests/unit`: 42 files, 626 tests passed.
- Contract published under unique `CASINO_VERSION`s.
- `services/backoffice-state` `sbt test`: 28 passed.
- Root `modules` suites (RealmBackofficeClient, RealmPlayers, Render, ModuleManifest, ReleaseRender): 66 passed.
- `services/players`: RoleSuite, SeedRetrySuite, RoundsSuite, FreshnessSuite, PlayerEntitySuite, PlayersCodecsSuite and AuditMappingSuite, 66 passed.
- `services/merchant` DepositBoundsSuite: 5 passed.

**Not run:**
- The integration suites (Docker or a running brand).
- The playwright feature and a11y suites, and `just features`.
- The suites of the thirteen amended services listed under *Not reviewed*.

## Second report: the owning services and the contract

This reviewer's report arrived after the first was written. It covers the first two areas listed under
*Not reviewed* above: the permission amendments in the thirteen services, and the contract's
`OperatorPermissions`, `RoleBags` and `Routes.operatorMay`. Its verdict: nothing blocking. Every operator
route reads its permission through `Routes.operatorMay`, and every per-service ankka pin is unchanged.
Tests it ran: `sbt contract/test` 291 passed; wallet `sbt test` 812 passed; payouts `sbt test` 384 passed.

### Should fix

- `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/AudienceEndpoints.scala:86`
  - issue (should fix, S1): transaction rows now show the adjustment's personal `reason` to anyone with `wallet.read` (support, by default). Wallet erasure deletes nothing until ankka 042, so an erased player's reasons are now served where before they were only stored. W2 asked for `cause` and `actor` on the row, not `reason`.
  - Suggestion: leave `reason` off the row until 042, hide it for erased players, or confirm support may see it.
  - Resolved: operators keep the reason (research R10's timeline shows it); erasure holds because the wallet records erased players (ErasedPlayerEntity) and drops the reason for them; the player's own route had been leaking reason, category, cause and actor and no longer does; integration test added, 015d69b2. Open: ServiceEndpoint's service-to-service transactions route, older than this branch, still carries the reason.
- `contract/src/main/scala/io/eitheror/casino/contract/access/RoleBags.scala:107-116`
  - issue (should fix, S2): `{"roles":[]}`, or an answer whose roles all lack a name, counts as a good read and replaces the kept copy. If `players` answers empty while seeding or recovering, every operator but `platform_admin` is refused across the brand for up to 10 seconds. Default roles cannot be deleted, so an empty answer is never real.
  - Suggestion: treat a read with no roles, or missing a default role, as failed; add a test.
  - Resolved: an answer that is empty or missing any default role is a failed read and keeps the last good copy; tests added, dd26376b
- `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/AudienceEndpoints.scala:100`
  - issue (should fix, S3): `amountMinor` is accepted as any `Long`; the text `amount` is capped at 17 digits, which still overflows in `Major.parse` (older than the branch). An adjustment near `Long.MaxValue` can wrap a balance.
  - Suggestion: bound both forms (for example |amount| ≤ 10^15 minor units) and refuse with `INVALID_REQUEST`.
  - Resolved: amountMinor and the text amount are bounded to 10^15 minor units either way (Long.MinValue refused) with INVALID_REQUEST, and parse can no longer overflow; MajorAmountSuite added, 6fea62ba
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/queue/WithdrawalQueue.scala:136` and `services/affiliate-programme/src/main/scala/io/eitheror/casino/affiliates/api/Backoffice.scala:213`
  - issue (should fix, S4): page arithmetic overflows. payouts' `p * size` is an `Int` (page above about 10.7M), and `OperatorEndpoint.scala:79,106` sets no upper bound; the programme's `(pageNo - 1) * size` goes negative and `slice` serves the first page.
  - Suggestion: cap `page` as kyc and responsible-gambling do, or compute in `Long`.
  - Resolved: pages past LastPage are refused at the routes, page() clamps, and the programme computes the offset in Long; tests added, 113c8098
- `contract/src/test/scala/io/eitheror/casino/contract/facts/PublishedSuite.scala:31`
  - issue (should fix, S5): "the committed files are what the contract writes" compares only when the file exists, so a deleted file or a wrong `casino.root` passes.
  - Suggestion: assert that the file exists.
  - Resolved: the suite asserts every published file exists before comparing, aaa159a5
- `services/reporting/src/main/scala/io/eitheror/casino/reporting/api/Access.scala:32` and `Documents.scala:55,85`
  - issue (should fix, S6): `/documents` keeps a bare `Routes.operator` (justified by the per-record `permissionOf` that follows, but not noted in amendments.md), and answers unread roles with `503 {"unavailable"}` and no `Retry-After`, where `operatorMay` answers `roles_unread` with one.
  - Suggestion: the same code and header; note the exception in amendments.md.
  - Resolved: documents answer unread roles 503 roles_unread with Retry-After: 5 as operatorMay does; the bare Routes.operator exception is noted in amendments.md row E3; DocumentsUnreadSuite added, 8b481e24

### Questions

- `services/kyc/src/main/scala/io/eitheror/casino/kyc/api/OperatorEndpoint.scala:44`
  - question (Q1): `GET /policy` requires `config.brand`, so compliance cannot read the identity policy, where payouts and wallet read their configuration under a read permission. Intended?
  - Answered: intended; contracts/roles.md puts the identity policy under config.brand, and no identity screen reads it; no change
- `services/rewards/src/main/scala/io/eitheror/casino/rewards/api/OffersEndpoint.scala:24`
  - question (Q2): every offers route, the list included, requires `offer.manage`, which only admin holds. How does a finance operator (`reward.grant`) choose an offer to award?
  - Answered: intended and kept from the original (spec 011 FR-031, admin roles only); a finance operator grants by offer id or by hand; widening the list to reward.grant is a rewards amendment and a product call
- `services/wallet/src/main/scala/io/eitheror/casino/wallet/freshness/Freshness.scala:64-71` (copied to payouts, kyc, responsible-gambling, rewards)
  - question (Q3): `asOf` is the follower's wall-clock time when it handled a change, not the event's time: it reads "now" while a backlog replays, and looks stale when caught up and idle. Does R13 mean "data current to"?
  - Answered: asOf means 'last applied at', not 'data current to'; ankka 0.16.3 gives a consumer neither the change's time nor the view's offset, so FR-011's catching-up case cannot be detected; recorded in research.md as a gap to raise with ankka, 27a61cad
- `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala` (`operatorMay`) and contracts/roles.md
  - question (Q4): role names are realm-wide, so an operator who is `brand_admin` at A and `brand_support` at B carries `brand_admin` in B's tokens. Was that accepted knowingly? (The first report's first blocking finding.)
  - Declined: moved to spec 032 (per-brand realm roles, the person's decision of 2026-11-17)
- `services/psp-gateway/src/main/scala/io/eitheror/casino/gateway/deposits/TestPayment.scala:30`
  - question (Q5): UnityFinance's test mode keeps the credential and relies only on `CARD_TEST`. With a production credential, can a `CARD_TEST` charge reach live money? Gigadat and Digitarium force `environment=sandbox`.
  - Resolved: yes, it could reach live money: Unity Finance has no request-level sandbox and CARD_TEST is the generic card payway on whatever checkout is configured; Unity Finance's test payment now answers 501, with a test, and the suites use Digitarium, c768c8da
- `services/reporting` market report
  - question (Q6): the market report no longer admits the realm role `markets` (`report.read` only). Is that loss intended? roles.md has no markets row.
  - Answered: the loss was made knowingly in 8251f24d and PredictionsSuite asserts it; the markets realm role carries nothing in a brand's bags; now recorded in contracts/roles.md, 27a61cad

### Nits

- `contract/.../access/RoleBags.scala:69-75`
  - nit: a second `start()` leaks the first scheduler; `refreshIfDue` is used only by tests; `status` hides why the latest read failed when none ever succeeded.
  - Resolved: start() is idempotent, refreshIfDue is private[access], and status shows the last failure while unread; tests added, dd26376b
- `contract/.../access/RoleBags.scala:35` and `Routes.scala:104`
  - nit: `read.toOption.get` behind a guard, and an unreachable `case other => other`; restructure as a `match`.
  - Resolved: refresh is a match and operatorMay and refine share one decision, with no unreachable case, dd26376b
- `services/*/BrandRoles.scala`
  - nit: copied 11 times with small drifts (responsible-gambling catches only `NonFatal`).
  - Left: nit
- `services/game-provider/.../Endpoints.scala:328,446`
  - nit: the unacknowledged `total` is capped at 10,000 rounds' open entries and the answer does not say so.
  - Resolved: the unacknowledged answer says limited when the 10,000 rounds it reads reach the cap, 27a61cad
- `services/payouts/.../player/Model.scala:459`
  - nit: says "real balance"; `WithdrawalWorkflow.scala:573` stores `preview.available` (real less reserved).
  - Resolved: the balancesAtRequest doc says available real balance, real less reserved, 27a61cad
- `specs/030-backoffice/contracts/roles.md`
  - nit: public-api is missing under `config.brand`, and engagement and support-agent under `player.read`.
  - Resolved: public-api status under config.brand, and engagement backoffice and support-agent config under player.read, 27a61cad
- `services/wallet` OperatorRolesFeatures and the contract's RoleBagsSuite
  - nit: `PUT /wallet-config/rewards` and `GET /wallet-config` are not covered; `Retry-After` and the scheduler's lifecycle are not asserted.
  - Left: nit (the scheduler's lifecycle is now asserted in RoleBagsSuite, dd26376b, and Retry-After in reporting's DocumentsUnreadSuite; the wallet-config routes are still not covered by OperatorRolesFeatures)
- `contract/.../Topics.scala` `Published.currencies`
  - nit: a code `Currency.from` rejects is written silently with exponent 2.
  - Resolved: a code Currency.from refuses throws instead of getting exponent 2; every code is tested, aaa159a5

## Third report: the page and the shared code

This covers the third area listed under *Not reviewed* above: `services/backoffice/src/shared/` and `src/web/`, with `tests/unit` (the files that test them), `features/backoffice/` with its bound steps, and `tests/a11y`.

**Verdict:** Request changes, on two blocking findings. Both show wrong figures or flags to operators against the real owners, and the doubles hide both.

**Read:**
- Read in full:
  - every file of `src/shared` except the generated owner clients and `messages/en.ts`;
  - `src/web/frame`, `components` (action, list, config-form, links, status, money, date, date-input, freshness, charts), `actions/index.ts`, `App.tsx` and `main.tsx`;
  - every `.ts`/`.tsx` file under `src/web/areas`.
- Skimmed: the CSS (checked for focus and contrast only), `en.ts` and the generated clients and JSON.
- Tests: `tests/a11y` was read. `features/backoffice/*.feature` and the step files were spot-checked (withdrawals and access); the unit tests for dates, money, list, action, components and frame were read by their cases.
- Opened outside the area to confirm findings: wallet `Wire.major`, players `OperatorProfileRoutes.profile`, `server/routes.ts` (export.player), `server/main.ts` (timeline export) and the doubles' players fixtures.

**Tests run:**
- `npm run typecheck`: clean.
- `npx vitest run --dir tests/unit`: 42 files, 626 passed.
- `npx eslint src/web src/shared`: clean.
- Not run: the playwright features and a11y suites. They need Chromium and the doubles server; CI runs both.

### Blocking

- `services/backoffice/src/shared/money.ts:82` (`minorOfText`), used at `src/web/areas/players/wallets.tsx:63` and `areas/players/page.tsx:162-167`, and on the server by `composite/standing.ts:8`
  - issue (blocking): the wallet writes every amount as minor units over 100 with two decimals, whatever the currency (`services/wallet/.../wire/Wire.scala:188`). `minorOfText` reads that text by the currency's own exponent instead. A KWD balance of 1234 minor units arrives as `"12.34"` and is shown as 12.340 KWD, ten times too much. For JPY, CLP and PYG (exponent 0), `minorOfText` gives `undefined` and the raw text is shown. This affects wallet movements, balance after, the standing's balances and lifetime figures (FR-013). CLP and PYG are South American brand currencies. `areas/corrections/shared.tsx:28-39` (`walletMinor`) states the right rule, and `areas/withdrawals/read.ts:93` is a third copy.
  - Suggestion: one wallet-text reader in `shared/money.ts` that follows `walletMinor`'s rule, used by the page and by `standing.ts`; delete the other copies. Add unit tests with a JPY and a KWD wallet answer.
  - Resolved: `walletMinor` in shared/money.ts, the wallet's rule, replaces `minorOfText` and the copies in corrections and withdrawals; the standing's tests now hold the wallet's rule; tests for EUR, JPY, CLP and KWD, 6666c959
- `services/backoffice/src/web/areas/fraud/cases.tsx:46-47` and `areas/players/tier.tsx:109,126`
  - issue (blocking): both screens read `suspectedDuplicate`, `username`/`email`, `vipTier` and `potVip` from the top level of `GET players/{p}`. players answers `{player: {…}, wallet, activeSessions, pinnedNotes}` (`OperatorProfileRoutes.scala:42-56`). Against the real service:
    - every player in a fraud case shows as not flagged, by id only;
    - the VIP form shows no tier and potential-VIP off for every player.
  - Why the tests miss it: the doubles answer a flat shape (`tests/doubles/fraud.ts:80-96`, `tests/doubles/fixtures/players.json:33-36`), and `features/steps/compliance.steps.ts:199` asserts "flagged" against it. `players/read.ts:57` and `profile.tsx:134` already handle both shapes.
  - Suggestion: one `readProfile` that unwraps `player`, used by every screen, and a players double that answers the real shape (or is generated from a recording).
  - Resolved: players/read.ts holds the one `profileQuery`; the fraud cases, the VIP form, the profile, the wallets and adjust take their part with `select` (`flagOf`, `tierOf`, `profileCurrency`), tested against the wrapped answer; the fixture, the fraud double and the steps answer and read the real shape, b7a5d1f5

### Should fix

- `services/backoffice/src/shared/dates.ts:22-35`
  - issue (non-blocking): `startOfDay` is wrong on a day whose midnight does not exist. In America/Santiago on 2026-09-06 (clocks go from 00:00 to 01:00), it answers 05 Sept 23:00 GMT-4 (03:00Z); the day starts at 04:00Z. A filter from that day takes in the previous day's last hour, and `endOfDay("2026-09-05")` loses that day's last hour (FR-015). Chile and Paraguay both move their clocks at midnight. The tests cover only Europe/London's 01:00 change.
  - Suggestion: when the guess lands in the gap, take the first instant whose local date is `day`, for example by checking the local date of `t` and adding the difference. Add Santiago cases for both changes.
  - Resolved: startOfDay takes the first instant at or after local midnight (firstAtOrAfter), so endOfDay follows; added todayIn, addDays, instantOfLocal and localOfInstant; Santiago tests for both changes, 4201d1e2
- `services/backoffice/src/web/areas/players/protection.tsx:237-240`, `areas/rewards/offers.tsx:81-88` and `areas/players/page.tsx:148-149`
  - issue (non-blocking): `datetime-local` text is read with `Date.parse`, so in the browser's zone, not the brand's. The "until" dates are cut from UTC ISO strings (FR-015). An operator in Cape Town sets a São Paulo brand's break end 5 hours off. On the offers screen the `ZoneNote` at `offers.tsx:367` names the brand's zone, which makes it misleading. The eslint `browserZone` rule does not catch this form.
  - Suggestion: `instantOfLocal(text, zone)` and `localOfInstant(instant, zone)` in `shared/dates.ts`, on `offsetAt`, and `formatInstant` for the "until" dates.
  - Resolved: datetime-local text is read and written in the brand's zone (instantOfLocal/localOfInstant) on protection and offers, the break shows its ZoneNote, and page.tsx's until dates use formatInstant, 6c4363aa
- `services/backoffice/src/web/areas/reports/ReportsPage.tsx:449`
  - issue (non-blocking): `yesterday()` is the UTC day, not the brand's zone's (and not the browser's, as its comment says). From 21:00 to midnight in São Paulo it is the brand's today, so day-end opens on a day that has not closed and is refused.
  - Suggestion: today in `zone` (already in `ReportProps`) through `Intl.DateTimeFormat("en-CA", {timeZone: zone})`, less one calendar day.
  - Resolved: yesterday is addDays(todayIn(zone), -1), the day before the brand's today, 6c4363aa
- `services/backoffice/src/web/areas/payments/merchant.tsx:91`
  - issue (non-blocking): the edge request log shows `${r.amount} ${r.currency}`. ingest's amount is in minor units, so 10.50 USD reads "1050 USD" (FR-013).
  - Suggestion: `<Money value={{ minor: String(r.amount), currency: r.currency }} />` when the currency is present.
  - Resolved: the edge log's amount is shown through <Money> from its minor units, 9d4dcfbc
- `services/backoffice/src/web/areas/players/wallets.tsx:178`, `profile.tsx:131`, `fraud/cases.tsx:64` and `corrections/adjust.tsx:245`
  - issue (non-blocking): the four share the query key `["players", "profile", player]` but store four shapes: the raw body, the unwrapped player, `{flagged, username}`, and the raw body again. The screen that reads first fills the cache. The next one renders the wrong shape until it refetches. For example, Profile after Wallets treats the wrapped body as a player, so its fields are empty and the erased check is skipped.
  - Suggestion: one `queryFn` for the key (the `readProfile` above), with a `select` per screen.
  - Resolved: every screen reads profileKey through one queryFn (profileQuery), with a select per screen, b7a5d1f5
- `services/backoffice/src/web/areas/players/timeline.tsx:156` (and :124-133)
  - issue (non-blocking): Export is offered to every operator, but its route requires `export.player` (`routes.ts:125`), so it is offered and then refused (FR-005). The export's reference lives only in local state: it is not a job, it is lost on navigation, and no notice comes when it ends (FR-029).
  - Suggestion: offer it through `Action` (or `holds(me, "export.player")`) and register it with `/api/jobs`, or record in research.md why it is not a job.
  - Resolved: Export is an Action needing export.player (shown disabled with its roles), and its message no longer claims a Jobs entry, 44b59535. Registering it as a job (FR-029) needs a job kind in the server; left open, named in the pull request
- `services/backoffice/src/web/areas/corrections/adjust.tsx:146-153` and `corrections/accounts.tsx:57-61`
  - issue (non-blocking): after an unknown outcome (503 or a network failure) the idempotency key is kept, which is right, but amount, direction, wallet and cause stay editable. A confirmed edit goes to the wallet under the first key with a different body. The wallet may answer that the first adjustment was made while the screen describes the second (constitution II).
  - Suggestion: freeze the fields while a key's outcome is unknown and say so; take a new key only once the first is settled (for example from the wallet's recent adjustments).
  - Resolved: useKeyed keeps the key and the first body while the outcome is unknown, renews it only on a settled answer (2xx, or 4xx but 408 and 429), locks the fields and says so; adjust shows repeated, 54b85f13
- `services/backoffice/src/web/areas/rewards/grant.tsx:97-100` against :111 (fields at :184, :204)
  - issue (non-blocking): `maxConvertible` has one label and one form state. It is read in major units for a bonus (`parseMoney`) and as whole minor units for free rounds (`wholeOf`), so "50" typed under bonus and then switched to free rounds is sent as 50 minor units, 100 times less. Free rounds' `spinValue` is raw minor units with no currency shown (FR-013).
  - Suggestion: separate fields per award type, both typed as amounts in the currency.
  - Resolved: separate fields for bonus and free rounds, free rounds typed in the player's currency, and the amounts shown with Money before confirm, 8cb2c139
- `services/backoffice/src/web/areas/worklist/WorklistPage.tsx:229-234` (the same pattern in `areas/identity/queue.tsx:36`)
  - issue (non-blocking): "all kinds" merges each kind's first page and pages it in the browser. While any kind has more, `complete` is false, so Next (`List.tsx:203`) leads to empty pages and the rest of a large kind cannot be reached from this view (FR-009, FR-017). The identity queue does the same once kyc's 1,000-row cap is reached.
  - Suggestion: end paging at the merged length, and show "N more in <kind>" linking to that kind's filter; name the kyc cap.
  - Resolved: paging ends at the merged length (List lastPage), each kind with more shows 'N more in <kind>', and the identity queue names kyc's cap, 008cff66
- `services/backoffice/src/web/areas/team/assign.tsx:25`, :27 and :76
  - issue (non-blocking): the chosen roles start empty, and `PUT role-assignments` replaces every role this brand gave. The confirmation lists only the ticked roles (an empty paragraph when none are), so confirming with nothing ticked takes every role away without saying so (FR-025, FR-008c). Separately, `if (!roles.data) return loading` stays on "loading" for ever when the roles read is refused (FR-006).
  - Suggestion: preselect the operator's current roles (`operators.tsx` has `o.roles`), state in the effect what is given and what is taken away, and show `RefusalNotice` on `roles.isError`.
  - Resolved: starts from the operator's current roles, the effect states what is given and what is taken away (all, when nothing is ticked), and a refused roles read shows RefusalNotice, 71ecf2ec
- `services/backoffice/src/web/areas/affiliates/account.tsx:563` with `affiliates/payouts.tsx:52-70`
  - issue (non-blocking): "view as affiliate" renders `PayoutsTable` with `done={() => undefined}`. Every row shows live approve, reject and pay actions in what is meant to be the affiliate's view, and nothing is re-read after one runs.
  - Suggestion: a `readOnly` prop on `PayoutsTable` that renders no actions.
  - Resolved in part: PayoutsTable takes readOnly and renders no actions, 6bddde5a. The call site is not changed: account.tsx is open in the person's editor; the change is to pass readOnly to the view-as PayoutsTable at account.tsx:563, named in the pull request
- `services/backoffice/src/web/areas/configuration/games.tsx:69`, `configuration/crm.tsx:39-41` and `components/config-form.tsx:216`
  - issue (non-blocking): `operatorPrivateKey` and the CRM track, app and webhook keys are visible `text` fields. ConfigForm's confirmation echoes `label: value`, so the private key is printed in the dialog.
  - Suggestion: a `secret` field kind (`type="password"`) whose value the confirmation shows as "changed", never as text.
  - Resolved: a secret ConfigField is a password input and the confirmation says 'changed (not shown)'; applied to operatorPrivateKey and the CRM keys, da261328
- `services/backoffice/src/web/areas/configuration/page.tsx:13` (the same in `affiliates/programme.ts:11` and `team/roles.tsx:33`)
  - issue (non-blocking): a refused read goes through `explain(status, body)` without the bags, so the operator is told "forbidden" and never which role would give it (FR-005, FR-006). These helpers also throw a plain `Refusal` object, not an `Error`, and `RefusalNotice` takes it through `as never`.
  - Suggestion: pass `useBags()`'s bags, and throw an `Error` subclass carrying the refusal, as `reports/read.ts`'s `Refused` does.
  - Resolved: reads throw RefusedError (an Error carrying the refusal); RefusalNotice takes unknown through refusalOf and adds the roles from the cached bags; every 'as never' removed, f996c919
- `services/backoffice/src/web/areas/dashboard/DashboardPage.tsx:199-204`
  - issue (non-blocking): the trend, rankings, bonus and health panels are enabled only once `/dashboard/kpis` answers. If that read is refused or fails, they stay "loading" for ever under the refusal.
  - Suggestion: when the first read is in error, show the refusal, or a note, in those panels.
  - Resolved: the panels waiting on the first read say why when it is refused, 1f466c23 (feature step adjusted in 80261b55)
- `services/backoffice/src/web/areas/payments/deposit-bounds.tsx:80`
  - issue (non-blocking): `key={keyOf([held, rows.length])}` remounts the `ConfigForm` when a row is added, so amounts typed in the other rows are lost.
  - Suggestion: key it on `held` only.
  - Resolved: the form is keyed on held only, c3fad9aa
- `services/backoffice/src/web/frame/areas.ts:25`
  - issue (non-blocking): the worklist area opens on `player.read`, but its `undelivered_outcomes` kind is admitted by a payments role, not a brand permission (`shared/worklist.ts:25`). An operator whose roles in the brand are payments roles only never gets a worklist: `home.tsx` sends them to `/payments`. FR-016 says that kind is shown to a payments administrator.
  - Suggestion: open the area when `kindsFor(me)` is not empty (or add `payments.read` to its permissions).
  - Resolved: the worklist also opens with payments.read, so a payments role's home is the worklist, ced776a8

### Nits

- `services/backoffice/src/shared/refusals.ts:81` — nitpick (non-blocking): `source ?? "A service"`, and `areas/players/messages.tsx:59`'s merge-tag fallback `"there"`, are English literals outside the catalogue (FR-040a). The lint sees only JSX. Use catalogue keys.
  - Resolved: catalogue keys refusal.aService and messages.mergeTag.fallback, 73c82be9
- `services/backoffice/src/web/frame/events.ts:24` — nitpick (non-blocking): `permissions_changed` invalidates `["me"]` but not `["permissions"]`, and `team/roles.tsx:162-166` does not re-read it after a change either. A disabled action's "needs role" hint stays stale until a remount. Invalidate both.
  - Resolved: permissions_changed also invalidates ['permissions'], and roles.tsx invalidates it after a change, 546fb9dc
- `services/backoffice/src/web/components/list/List.tsx:81-87` — nitpick (non-blocking): the scroll offset is restored once per mount. Paging sets `scroll: 0` in the URL but leaves the table where the last page was scrolled. Scroll the table to `state.scroll` whenever the place changes.
  - Resolved: the scroll is restored whenever the place changes, 008cff66
- `services/backoffice/src/web/frame/session.ts:75` — nitpick (non-blocking): `useReadOnly` makes a new `MediaQueryList` every render, so the effect removes and adds its listener on every render. Memoise the query.
  - Resolved: the MediaQueryList is made once per component (useState), 69367534
- `services/backoffice/src/web/components/date.tsx:14` — nitpick (non-blocking): `new Date(instant).toISOString()` (and `formatInstant`) throws on an instant an owner answers unparseable, which takes down the whole page through the router's error boundary. Show the value as given when it does not parse.
  - Resolved: DateTime and formatInstant show an unparseable instant as given, 0b1fba4f
- `services/backoffice/src/web/components/config-form.tsx:56` — nitpick (non-blocking): `same` says "numbers and their text alike" but compares strictly, so an owner answering a number for a text field marks it changed. The money kind sends `Number(bigint)`, which is exact only below 2^53; `payments/routing.tsx:209` does the same.
  - Resolved: same compares numbers as their text; whole and money values past MAX_SAFE_INTEGER are refused, as are routing's samples, b40d90b3
- `services/backoffice/src/web/areas/players/timeline.tsx:110` — nitpick (non-blocking): gaps are de-duplicated by source alone, so a source missing in two periods is named for one (FR-022); key them by source and period. At :94, unticking the last kind writes `kinds=""`, which reads back as every kind.
  - Resolved: gaps are keyed by source and period, and the last ticked kind cannot be unticked, 44b59535
- `services/backoffice/src/web/areas/players/profile.tsx:78` — nitpick (non-blocking): correcting the date of birth is offered below 1280 px, where every other action is hidden (FR-041).
  - Resolved: the date-of-birth correction is hidden in read-only width, and its today is the brand's, 179bf890
- `services/backoffice/src/web/areas/players/page.tsx:143` — nitpick (non-blocking): small display points.
  - A session-minutes limit shows a bare number with no unit; `protection.tsx:27`'s `Value` handles it.
  - `sessions.tsx:211` and `notes.tsx:91` have Next but no Previous.
  - `affiliates/account.tsx:338`'s effect shows the typed amount, not the formatted one.
  - Resolved: limits use protection's value formatter (LimitValue), 6c4363aa; sessions and notes page back as well as forward, 17b2c7b9. The account.tsx:338 part is left: the file is open in the person's editor; the change is to show the amount there with <Money> from amountOf(f.amount, currency)
- `services/backoffice/src/web/areas/team/roles.tsx:145` — nitpick (non-blocking): creating a role has no `ready`, so an empty name sends `PUT …/roles/`. Use `ready={name.trim() !== ""}`.
  - Resolved: create has ready={name !== '' && !taken}, 546fb9dc
- `services/backoffice/src/web/areas/payments/routing.tsx:236-241` — nitpick (non-blocking): after a deploy the state is folded locally with `setQueryData` and never re-read, and `answer.json()` has no catch. `team/assign.tsx:78`'s async `onDone` can reject unhandled in the same way. Invalidate and re-read.
  - Resolved: the deploy is folded, then re-read whatever the body, with the json() caught, b40d90b3 (feature step 80261b55); assign.tsx's onDone catches its json(), 71ecf2ec
- `services/backoffice/src/web/areas/payments/merchant.tsx:56` — nitpick (non-blocking): re-render and request churn.
  - The query key holds the typed text, so ingest is asked on every keystroke.
  - `configuration/games.tsx:164-168` trims the new provider's name as it is typed and remounts its form on every keystroke.
  - `payments/providers.tsx:68` keeps typed credentials after the dialog is cancelled.
  - Debounce, or ask on submit.
  - Resolved: the request id is asked on submit, 9d4dcfbc; the new provider's name is kept as typed with one form, and typed credentials are dropped when their dialog closes (Action onClose), 6194f52a
- `services/backoffice/src/web/areas/payments/providers.tsx:19` and `terminals.tsx:19` — nitpick (non-blocking): the provider and gateway lists are copies of the owners' catalogues and will drift. Read them from the owner or from the contract.
  - Left: nit
- `services/backoffice/src/web/frame/areas.ts:50` — nitpick (non-blocking): `areaOf("/adjustments/accounts")` finds no area, because the area's path is `/adjustments/batch`. The rail marks nothing current there. Give the area the path prefix `/adjustments`.
  - Resolved: areas take a prefix; adjustments has /adjustments, ced776a8
- `services/backoffice/src/web/areas/affiliates/plans.tsx:42` — nitpick (non-blocking): a new plan defaults to EUR, and `DashboardPage.tsx:196` and :224 read the default currency's figures twice. Default to the brand's currency, and share the read.
  - Resolved for the dashboard: the default currency's figures reuse the first read, 1f466c23. Plans' EUR default left (nit): the page is given no brand currency to default to
- `services/backoffice/tests/a11y/axe.spec.ts:17` — nitpick (non-blocking): axe runs only as `platform_admin`, so the disabled-action state (lock and role text), refusals and not-in-brand pages are never checked. Add one pass as `brand_support` over a few routes.
  - Resolved: axe passes as brand_support over 6 routes (a disabled action among them), on the not-in-brand page, and with a dialog open, 4c9515a7

### Questions

- `services/backoffice/src/web/areas/affiliates/account.tsx:342` — question: the adjustment keeps its idempotency key only on a 503 or when the fetch throws. A 502 or 504 from the ingress, or a 500 after the programme committed, gets a new key, so a retry can adjust twice. Should every 5xx or unknown outcome keep the key?
  - Answered: yes, any 5xx or thrown fetch is an unknown outcome and must keep the key; the page's rule is now settles() in corrections/shared.tsx (54b85f13). account.tsx is not changed: it is open in the person's editor; the change is to use useKeyed/settles there and lock the fields while the outcome is unknown, named in the pull request
- `services/backoffice/src/web/areas/rewards/grant.tsx:230` — question: the award is posted with no idempotency key, and rewards' operator awards read none. Is a double award after a lost answer guarded only by rewards' open-reward and duplicate rules? If not, the fix is an amendment to rewards (FR-007), not the page.
  - Answered: no. Bonuses go through the PlayerRewards claim, which holds the duplicate and open-reward rules, but free rounds by hand make a new manual:<uuid> offer each time (OperatorAwards.freeRounds), so a retry after a lost answer awards twice. Rewards reads no key, so the fix is a rewards amendment (FR-007), not the page; left open, named in the pull request
- `services/backoffice/src/web/areas/team/roles.tsx:96` — question: creating a role is a `PUT` upsert. Does players refuse a new role whose name is already taken, or does it silently replace that role's permissions?
  - Answered: players' Role.set silently replaces an existing role; the page now refuses a name already taken (roles.nameTaken), d3225c2e
- `services/backoffice/src/web/areas/payments/hierarchy.tsx:228` — question: a sub-brand is created with any typed slug. Does merchant refuse another brand's slug (FR-004)?
  - Answered: no. merchant's SubBrandsEndpoint passes the brand through unchecked (HierarchyEndpoints.scala:132); the payments project is shared and FR-004 leaves payments administration at the operator's level per spec 004, so the page defaults to the operator's brand. Holding the slug to known brands would be a merchant change (spec 004)
- `services/backoffice/src/web/areas/players/rounds.tsx:300-303` and `players/sessions.tsx:294-301` — question: the bet and session date filters are whole UTC days, because players takes `LocalDate`s. FR-015 asks for the brand's zone. Is that accepted as a limit of players, and recorded?
  - Answered: yes, a limit of players: OperatorSessionRoutes parses fromDate and toDate as LocalDate, and the page says 'Dates are whole days in UTC' (bets.daysNote); recorded in research.md
- `services/backoffice/src/web/areas/reports/ReportsPage.tsx:579` — question: without a report's permission the page names the permission, not the role that would give it. Is that intended here, where `Action` names roles?
  - Answered: not intended; the report now names the roles that give its permission (the permission only while the roles are unread or none gives it), 73bccc12

## Fourth report: the page's TypeScript (the first reviewer's last sub-review)

It reported after the first report was written. Where a finding repeats one of the third report's, the
resolution line names the one it shares.

### Blocking

- `services/backoffice/src/web/areas/players/wallets.tsx:63`
  - issue (blocking): the movements tab read the wallet's text by the currency's own exponent (`minorOfText`); the wallet writes minor units over 100 with two decimals in every currency. The third report's first blocking finding.
  - Suggestion: one wallet-text reader in shared/money.ts; tests for JPY, CLP and KWD.
  - Resolved: as the third report's first blocking finding, 6666c959

### Should fix

- `services/backoffice/src/web/areas/withdrawals/request.tsx:308` (and about 58 more `refusal={q.error as never}` in src/web)
  - issue (should fix): a read that fails before an answer (a fetch `TypeError`) or with a non-JSON 200 (`SyntaxError`) reaches `RefusalNotice` as a plain Error, and `render(refusal.message)` throws, so the page falls to the router's English error screen.
  - Suggestion: `refusalOf(q.error)` everywhere; drop the cast.
  - Resolved: as the third report's configuration/page.tsx:13, f996c919
- `services/backoffice/src/web/areas/corrections/adjust.tsx:152`, `corrections/accounts.tsx:60`, `affiliates/account.tsx:342`
  - issue (should fix): the idempotency key is renewed on any status but 503; a 502 or 504 from the ingress, or a wallet 500 after it committed, also means "answer not known", and a retry then makes a second adjustment.
  - Suggestion: renew only on a definite answer (2xx, or 4xx but 408 and 429); keep the key on 5xx and on a thrown fetch.
  - Resolved: as the third report's corrections/adjust.tsx:146-153, 54b85f13. The account.tsx:342 part is not changed: the file is open in the person's editor
- `services/backoffice/src/web/areas/corrections/adjust.tsx:132-153`
  - issue (should fix): after an unknown answer the key is kept but the fields stay editable; the wallet then answers 409 `KEY_REUSED`, the page renews the key and tells the operator to start again, though the first adjustment may have been made. `affiliates/account.tsx:311` renews the key on any edit instead.
  - Suggestion: lock the composed fields while a key's outcome is unknown, or word `refusal.key_reused.next` to check the recent adjustments first; show the wallet's `repeated: true`.
  - Resolved: as the third report's corrections/adjust.tsx:146-153, 54b85f13 (fields locked while unknown, repeated shown, key_reused.next reworded). The account.tsx:311 part is not changed: the file is open in the person's editor
- `services/backoffice/src/shared/dates.ts:22-29`
  - issue (should fix): `startOfDay` is an hour early where daylight saving skips midnight (America/Santiago 2026-09-06 gives 03:00Z, not 04:00Z), and `endOfDay` the day before ends an hour early.
  - Suggestion: move forward to the first instant of the day; a Santiago test beside the London one.
  - Resolved: as the third report's shared/dates.ts:22-35, 4201d1e2
- `services/backoffice/src/web/areas/rewards/grant.tsx:218`
  - issue (should fix): the grant carries no idempotency key, so a 503 and a retry can award twice; the typed amounts are never shown back with `<Money>` before confirm (FR-025).
  - Suggestion: a key per composition, as players/messages.tsx does; the amounts in the confirmation.
  - Resolved for the amounts: shown with Money before confirm, 8cb2c139. Declined for the key: rewards' operator awards read no key, so a page key would be ignored; it needs a rewards amendment (FR-007), as answered for the third report's grant.tsx:230
- `services/backoffice/src/web/areas/rewards/grant.tsx:180,200`
  - issue (should fix): `form.maxConvertible` is a decimal amount in bonus mode and raw minor units in free-rounds mode, so "10" changes meaning when the mode changes.
  - Suggestion: separate fields, or clear the field on a mode change.
  - Resolved: as the third report's grant.tsx:97-100, 8cb2c139

### Nits

- `services/backoffice/src/web/actions/index.ts:359`
  - nit: `withReason` and `reasonField` are unused; the call sites build the reason body by hand.
  - Left: nit
- `services/backoffice/src/web/components/config-form.tsx:73,88,93` and `payments/routing.tsx:209`
  - nit: `Number(text)` with no `MAX_SAFE_INTEGER` check, and `decimalOf` throws on an unsafe number.
  - Resolved: as the third report's config-form.tsx:56, b40d90b3
- `services/backoffice/src/shared/money.ts:65`
  - nit: "1,500" typed for KWD is 1500, not 1.5; say in the hint that "," groups thousands.
  - Resolved: parseMoney's doc and the amount hint say a comma groups thousands; a test for '1,500' KWD, 21111ece
- `services/backoffice/src/web/areas/withdrawals/request.tsx:303`
  - nit: the 10-second refetch can move the request while a confirmation is open, and the dialog vanishes.
  - Left: nit
- `services/backoffice/src/web/components/status.tsx:19`
  - nit: `toneOf` is a plain object; "constructor" resolves to `Object.prototype`. Use `Object.hasOwn`.
  - Resolved: toneFor reads own keys only (Object.hasOwn), 0b1fba4f

### Questions

- `services/backoffice/src/shared/access.ts:27-89`
  - question: the realm and payments role tables are a mirror of the contract's `Routes.*`; does tests/unit/routes.test.ts hold them equal to it?
  - Answered: no, routes.test only checked the permissions were known. A new unit test reads Routes.PaymentsRoles, Routes.AffiliateManagerRoles and the programme's Access.ManagerAdminRoles from their Scala source and holds access.ts and the server's PaymentsRoles equal to them, 3bcc4bff
- `services/backoffice/tests/a11y/axe.spec.ts`
  - question: axe never runs with a dialog open or as a role short of an action; add a pass with a dialog open and one as brand_support?
  - Answered: yes, added: a pass as brand_support, one on the not-in-brand page, and one with a confirmation dialog open, 4c9515a7

## Second round

# Review: the fixes to the four reports (a90714a7...HEAD, 75 commits)

**Verdict:** Approve with comments. Nothing is blocking. The three should-fix findings are expected before the pull request is opened, and the account.tsx one above all, since it is a defect the first round found and this round leaves on the branch.
**What it does:** It answers the four reports above, one commit per finding or group of findings. The fixes include: the tokens sealed under the operator, the worklist's psp paths, the wallet's amount rule, one profile query, and per-row batch keys. They also include RoleChangers as the one writer of `roles.change`, the brand check in the `/api/*` middleware, the body limit, the pass-through's bound and the shutdown. The rest are the paging, date and refusal fixes in the page and the bounds in the Scala services.
**Should it exist:** Yes. Each commit is scoped to one finding and says which one it resolves.
**Read:**
- Read in full in the diff:
  - every hand-written hunk under `services/backoffice-state`, `services/players/src/main`, `services/merchant/src/main`, `contract/src/main` and `tools/modules/src/main`;
  - the `src/main` hunks of wallet, payouts, affiliate-programme, reporting, game-provider and psp-gateway;
  - `services/backoffice/src/server`, `src/shared` (not the message catalogue) and `src/web`.
- Tests: the new and changed tests were read by their cases. ErasureSuite, RealmClientSuite, SessionSuite, RoleChangesSuite and the proxy tests were read in full, along with the test hunk of `b7a5d1f5` (the doubles' fixtures, the fraud double).
- Opened outside the diff:
  - `Session.scala`'s serializers;
  - `StateCodecs`;
  - `Routes.operator` and `refine`;
  - `OperatorPermissions.DefaultRoles`;
  - players' `PlayersCodecs.bags`;
  - wallet `Wire.major`, `MajorLifetime` and `WalletState.RecentKeys`;
  - the timeline's `within`;
  - `standing.ts`;
  - `affiliates/account.tsx`, unchanged in the range;
  - research R15;
  - ankka's `build/event-sourced-entities.md` (*Snapshots*);
  - `brands/_realm/eitheror-operators.json`;
  - `modules/backoffice/module.json`.
**Size:** 180 files, +3,690 −829, about 75 commits.

### Should fix

- `services/backoffice/src/server/composite/batch.ts:113`
  - issue (non-blocking): the fix for the batch-id question also dedupes across batches. A row's key no longer depends on the list it is in, only on its reason, category and cause (`batchId`), on what it adjusts, and on which of its identical rows it is. A later batch that means to make the same adjustment again (a weekly "Goodwill" or "Cashback" credit of the same amount to the same player, with no cause) gets the first batch's keys. The wallet answers "already_made" and pays nothing, for as long as the key is among the wallet's last 1,000 per wallet (`WalletState.RecentKeys`). Research R15 records this ("already made in any list"), but FR-028 only asks that a resent or corrected batch not be made twice. An operator who runs the same batch next week is not resending.
  - Suggestion: the page mints a batch id when the operator composes the list and keeps it until every row has an outcome (in the page's state, or the jobs preference), and the server takes it in place of hashing reason, category and cause. A resend or a corrected list carries the same id, and a new batch gets a new one. Add a test where two batches with the same reason and rows are both made.
  - Resolved: the page makes a UUID per batch composed and sends it with every send of that batch; the id is that key with the reason, category and cause, so a corrected list resent is the same batch and a later batch with the same reason and rows is paid; readBatch refuses a batch without a UUID key; tests in batch.test.ts, 60c79158
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/session/Session.scala:28`
  - issue (non-blocking) (if-minor): `SessionState.accessToken` and `refreshToken` changed from `Option[Secret]` (stored as a JSON string) to `Option[Personal[String]]`, under the same state serializer `backoffice-state.session-state`. The commit keeps `Opened` and `Refreshed` so that "a journal that holds them still recovers". But a Scala entity snapshots every 100 events by default, and snapshots are written with the state serializer (ankka `build/event-sourced-entities.md`, *Snapshots*). A session refreshed about every 4.5 minutes reaches 100 events in a working day, and its snapshot from before this change will not decode. The claim holds for the journal and not for the snapshot.
  - Suggestion: either decode both forms of the two fields in the state codec (a string read as `Personal(subject, value)`), or set `snapshotEvery` to `None` on the companion. Alternatively, say in data-model.md that no installation holds sessions written before 0c0866f5 (none has run 030 against a brand yet) and drop `Opened`/`Refreshed`. Add a test that decodes a stored state of the old shape.
  - Resolved by the third option: backoffice-state has never been deployed (it is not on main), so no installation holds a session or snapshot in the old form; data-model.md says so and names what a later change to SessionState must do, a7af553c
- `services/backoffice/src/web/areas/affiliates/account.tsx:342` (and :311, :563)
  - issue (non-blocking): four findings were marked resolved or answered with "account.tsx is not changed: it is open in the person's editor". The defects are still on the branch (the file is unchanged in the range, and `.account.tsx.swp` is untracked beside it):
    - :342 renews the programme adjustment's key on any status but 503, so a 502, 504 or 500 after commit followed by a retry adjusts twice;
    - :311 renews the key on any edit while the outcome is unknown;
    - :563 renders `PayoutsTable` without `readOnly`, so the "view as affiliate" view still shows live approve, reject and pay actions.
  - Suggestion: once the editor session is closed, apply the changes the resolutions describe: `useKeyed`/`settles` from `corrections/shared.tsx` with the fields locked while the outcome is unknown, `readOnly` on the view-as `PayoutsTable`, and `<Money>` for the :338 effect. Then add unit tests like the ones `corrections` and `affiliates-payouts` already have.
  - Declined for now: account.tsx has been open in the person's vimdiff (since 2026-11-16) for the whole review, so no agent wrote to it; the three changes are named in the pull request as open and are made once the file is free

### Nits

- `services/backoffice/src/server/events.ts:272`
  - nitpick (non-blocking): `StreamsPerSession` is counted in a map local to each instance, so with N instances a sign-in may hold 8 × N streams, not the "at most 8" the answer states. A browser's `EventSource` also does not reconnect after a non-200 answer, so the ninth tab never gets `signed_out` or `permissions_changed` for as long as it stays open. `frame/events.ts` does not handle the error.
  - Suggestion: word the answer and the doc comment as "per instance", and have the page's `listen` reopen after a delay when `source.readyState` is `CLOSED`.
  - Left: nit
- `services/backoffice-state/src/main/scala/io/eitheror/casino/backoffice/Settings.scala:36`
  - nitpick (non-blocking): `BACKOFFICE_LOCAL` is documented only in this file. The service README, `contract/keys.md` and the backoffice README say nothing of it. The service now exits 2 at start when the modules tool has just minted the client's secret and `backoffice-client-secret` is not yet set. That is intended, but the operator needs to be told.
  - Suggestion: one line in `services/backoffice-state/README.md` naming the variable and the exit, and the minted-secret note in the modules tool's output pointing to it.
  - Resolved: backoffice-state's README names its client secret and BACKOFFICE_LOCAL, 84a51bd1
- `services/backoffice/src/web/areas/players/rewards.tsx:213`
  - nitpick (non-blocking): `GrantReward key={currency}` remounts the form when the profile query answers ("" to the player's currency). Anything typed before the profile arrived is lost.
  - Suggestion: render `GrantReward` once the currency query has settled, or pass the currency without keying on it and let `bodyOf` read it.
  - Resolved: the grant form is shown once the currency read has settled, and is no longer keyed on it, 57641f93

### Questions

- `services/players/src/main/scala/io/eitheror/casino/players/api/RolesRoutes.scala:82`
  - question: `oneWriter` claims before writing when a change takes `roles.change` away, and after writing when it gives it. Two concurrent PUTs to the same role in opposite directions (T1 gives it, T2 takes it away) can interleave as T1 write, T2 claim, T2 write, T1 claim. The role then lacks `roles.change`, but `RoleChangers` counts it as a holder. That over-count is the one direction the class comment says cannot happen ("can only refuse more"), and it would later let the last real holder lose the permission. Is a same-role race in opposite directions accepted as out of reach? If it is not, the claim for "gives" could carry the role's state version from `Role.set`'s answer, with `RoleChangers` keeping the latest per role.
  - Suggestion: either state the accepted race in the class comment, or add the version and a test like RoleChangesSuite's, where both PUTs go to one role.
  - Answered: accepted for 030 and stated in RoleChangers' comment (it needs two operators changing one role in opposite directions at the same moment); the fix, the role's version on the giving's claim, is carried to spec 032, 674b5efa

### Resolutions

The resolutions below do not hold. Every other Resolved line was checked against its commit and holds, and each has a test that would fail without the fix. Every other Declined and Answered reason would satisfy this reviewer.

- First report, `Description` todo (T123 to T128, T133 and T136 not run): "Resolved: the pull request's body says …". There is no pull request yet, so nothing says it. The same applies to the other resolutions that leave something "named in the pull request" (the timeline export as a job, rewards' free-rounds key, the account.tsx changes). It holds once the body is written. Until then it is a todo.
  - Answered: the pull request is raised after this report; its body carries every item named 'in the pull request'
- Third report, Should fix, `affiliates/account.tsx:563` ("Resolved in part"): `PayoutsTable` takes `readOnly`, but the one call site the finding named does not pass it. The defect is unchanged.
  - Declined for now: account.tsx has been open in the person's vimdiff (since 2026-11-16) for the whole review, so no agent wrote to it; the three changes are named in the pull request as open and are made once the file is free
- Third report, Questions, `affiliates/account.tsx:342` ("Answered: yes …"): the answer agrees it is a defect and leaves it unfixed. An answer that confirms a defect resolves the question, but not the defect.
  - Declined for now: account.tsx has been open in the person's vimdiff (since 2026-11-16) for the whole review, so no agent wrote to it; the three changes are named in the pull request as open and are made once the file is free
- Fourth report, Should fix, `corrections/adjust.tsx:152` …, the `affiliates/account.tsx:342` part: not changed, so a 502, 504 or 500 still renews the key there.
  - Declined for now: account.tsx has been open in the person's vimdiff (since 2026-11-16) for the whole review, so no agent wrote to it; the three changes are named in the pull request as open and are made once the file is free
- Fourth report, Should fix, `corrections/adjust.tsx:132-153`, the `affiliates/account.tsx:311` part: not changed, so the key is still renewed on any edit while the outcome is unknown.
  - Declined for now: account.tsx has been open in the person's vimdiff (since 2026-11-16) for the whole review, so no agent wrote to it; the three changes are named in the pull request as open and are made once the file is free

### Checks applied

- General checklist groups applied: Correctness, Design and scope, Error handling, Concurrency and state, Security, Data and migrations, Performance, Tests, Observability, API and compatibility, and Commit and PR hygiene. Data and migrations covered stored-type evolution (the Session events and state, `OperatorRow.createdAt` with the view at version 2, `ErasedPlayer`, `RoleChangers`).
- Skipped: Dependencies and build (no dependency was added; `hono/body-limit` comes from the existing `hono`).
- Commit hygiene: all 75 headers parse as Conventional Commits under scope 030, every commit has the attribution trailer, and the branch contains `origin/main` (0 commits behind).
- Language skills: the bundled `pr-review-scala` and `pr-review-typescript`. There is no repository override.
- Project rules read: `CLAUDE.md`, the workspace `CLAUDE.md`, spec.md, plan.md and tasks.md for scope, research R15, and the review above.
- Tests: not run. The suites of every changed service were being run separately at the time, and running them here would have collided on the same builds and ports.
