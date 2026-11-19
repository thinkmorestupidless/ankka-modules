# Review: the player SDK, a reference player site and the brand guyanabet (spec 027)

**Verdict:** Request changes
**What it does:** Adds `@eitheror/player-sdk` (module route tables and clients, a Hono site server
that holds the sign-in in `HttpOnly` cookies and passes allow-listed calls through, five behaviours
as pure machines on an injected clock, a React hook), the reference site `services/player-site` as
the module `player-site`, and the brand `guyanabet`. It also amends `players` (a deposit start
under a request id that answers the cashier ticket, and a game session's last bet), `game-provider`
(a public catalogue), the wallet (major-unit text by the currency's exponent), and the contract (a
currency table).
**Should it exist:** Yes. The constitution's scope is amended first (T052), and the design keeps
every rule (gates, limits, money) at the brand. The page holds no sign-in and no logic of its own
beyond steps. That is the right split for a site builder.
**Read:** In full: `sdk/player/src/server/*`, `transport.ts`, `eventStream.ts`, `behaviour.ts`,
`clock.ts`, `amount.ts`, `refusal.ts`, `client.ts`, `route.ts`, `site.ts`, `core/{routes,client,
deposit,session}.ts`, `casino/{routes,play,games}.ts`, `engagement/routes.ts`, `support/routes.ts`,
`core/funds.ts` (from line 40), every Scala main-code hunk (players, wallet, game-provider,
contract, tools/modules), `.github/workflows/{ci,release}.yml`, the player-site `Dockerfile`, its
ignore file, `service.json` and `src/server/main.ts`, `modules/player-site/module.json`,
`brands/guyanabet/values.json`, `local/guyanabet/{operator-token,realm}.sh`, and the spec's
`plan.md`, `tasks.md`, `contracts/site-server.md`, `contracts/amendments.md` and research's
"Found during implementation". Skimmed: `support/chat.ts` (steps and storage only),
`tests/server/session.test.ts`, `tests/features/{elsewhere,setup}.ts`, `local/README.md`, and the
site's pages, searched for DOM sinks (`iframe`, `window.open`, `href`, `postMessage`). Not read:
`package-lock.json` (generated), most step files and unit suites, `core/schemes.ts`, `codes.ts`,
`language.ts`, `engagement/inbox.ts`, the site's pages beyond that search, the feature files, and
`spec.md` beyond what the plan and tasks cite. Opened outside the diff: merchant's
`payments/Status.scala`, `PaymentRead.scala`, `DepositWorkflow.scala`, `gateway/GatewayClient.scala`,
ingest's `api/Create.scala` and `Answers.scala`, players' `api/SessionRoutes.scala` and `Json.scala`,
the wallet's `Major.parse`, the contract's `Amounts.render`, and the original's
`DepositModal.tsx` (poll) and `GameManagerContext.tsx` (reality-check read).
**Size:** 251 files, +18,535 −116 hand-written lines (plus a 4,462-line lockfile). That is far past
the point where a review finds defects reliably. I would accept this split: (1) the service
amendments and the contract's currency table (players, wallet, game-provider, contract), which
change running services and stored events; (2) the SDK and its suites; (3) the reference site, the
module, the brand, `local/` and CI. `local/` (the one-machine project, about 900 lines) was not in
the plan. Research declares it as the person's direction, but it is still scope a reviewer has to
read.
  Declined: the branch is one spec's slice, its tasks ordered so the service amendments land first; splitting now would ship an SDK whose contract tests have no amended services to run against. The pull request body points a reader at the three parts in that order.

## Blocking

- `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/AudienceEndpoints.scala:96` (and `:147`, `:66`) — issue (blocking): the operator's reads now write major units by the currency's exponent, but the operator's adjustment still reads `amount` through `Major.parse`, which assumes two decimals. Before this change both sides were two-decimal: wrong for JPY, but in agreement. After it, an operator who reads a CLP or PYG balance of `"5000"` and sends `"5000"` back is credited 500,000 minor units, 100 times too much. A KWD amount like `"1.234"`, written as the balance shows it, is refused. The casino brands target South American markets in local currencies, and CLP and PYG have exponent 0, so this is money moved in error, not a cosmetic difference. Research already names the gap ("Left open, outside this spec") along with 030's backoffice reading the text with two decimals (`services/backoffice/src/shared/money.ts:91`). But this change is what opens it.
  Suggestion: in the same change, read the operator's amount by the route's currency through the contract's parser, and give the adjustment suite an exponent 0 and an exponent 3 row:
  ```scala
  def parse(text: String, currency: Currency): Either[String, Long] =
    Amounts.parse(text, currency).left.map(_ => s"the amount '$text' is not an amount of $currency")
  // checked(currency) { c => (Key.from(r.key), Major.parse(r.amount, c)) match ... }
  ```
  The alternative is to keep `Wire.major` two-decimal on the operator's routes until spec 030 amends input and output together. Either way, 030's `walletMinor` needs the same change before it merges after this one.
  Resolved: both operator adjustments read the amount by the currency's exponent (`Major.parse(text, currency)`), with EUR, CLP, PYG, KWD and JPY rows and a round trip against `Amounts.render`; amendments.md says so and names 030's `walletMinor`, 33a524f3

## Should fix

- `sdk/player/src/server/session.ts:120` — issue (non-blocking): a refresh reissues the session and refresh cookies but never `__Host-eo_player`, which is set once at sign-in for 30 days. Each refresh moves the refresh cookie's life 30 days forward, so a player who keeps playing loses `eo_player` 30 days after signing in while the refresh cookie lives on. From then on, `/api/session` answers `signedOut` and every pass-through call answers `signed_out`. The page refreshes (204) and retries, gets `signed_out` again, and `transport.ts:166-172` returns that refusal without telling `onSignedOut`. The session behaviour then stays at `signed_in` while every call fails. Suggestion: `return noContent(issued(deps, r.access, r.refresh, h.player))`. Also, in `once`, tell the listeners when the retry after a successful refresh is still `signed_out`. Add a server case: after a refresh, the jar still holds the player cookie with a renewed `Max-Age`.
  Resolved: a refresh reissues the player cookie with the refresh cookie's life, and a call still `signed_out` after a refresh tells the listeners, both with tests, a1e5d207
- `services/players/src/main/scala/io/eitheror/casino/players/reads/ActivityView.scala:176-180` with `sessions/GameSessionEntity.scala:86` — issue (non-blocking): every `RoundOpened` and every `RoundSettled` on `casino.rounds` persists a `BetRecorded` event and updates a `SessionsView` row. That roughly doubles writes on the brand's highest-volume topic. The only reader is the SDK's five-minute check against a six-minute window (`casino/play.ts:53-54`). Suggestion (if-minor): coalesce in the entity, for example persist only when `c.at` is at least 60 s after the held `lastBetAt`. Write down the precision in FR-057, so the time answered is "within a minute of the latest bet".
  Resolved: `recordBet` persists only a bet at least a minute after the one held (`BetPrecision`); FR-057 says "to within a minute", b2bba6b9
- Description — todo: `BetRecorded` is a new event type in `GameSessionEntity`'s journal. A `players` image from before this change cannot replay an entity that holds one, so a rollback of `players` past this version breaks those sessions. Neither the amendment nor the research says so. State it in `contracts/amendments.md` and say whether a rollback is supported.
  Resolved: amendments.md and spec 008 state that rolling `players` back past this version is not supported once it has run; a rollback goes to this version or later, b2bba6b9

## Nits

- `sdk/player/src/server/modules.ts:15` — nitpick (non-blocking): `tables[module]` on a plain object answers `Object` for `/api/c/constructor/x`, and `table?.find` then throws a `TypeError`, so the request is a 500 rather than `404 not_named`. Use `Object.hasOwn(tables, module) ? tables[module] : undefined`, or a `Map`.
  Resolved: `Object.hasOwn`, with `/api/c/constructor/x` and `/api/c/toString/x` in the not-named test, c294a21e
- `sdk/player/src/server/static.ts:29` — nitpick (non-blocking): `decodeURIComponent` throws on a malformed escape (`/assets/%E0%A4%A`), so the answer is a 500. Catch it and answer `notFound`.
  Resolved: a malformed escape answers 404, tested, c294a21e
- `tools/modules/src/main/scala/io/eitheror/casino/modules/Keys.scala:59` — nitpick (non-blocking): the modules tool counts a service as built when it has a `package.json`. The release publishes a web image only for a `Dockerfile` with a `package.json` and no `build.sbt` (`release.yml`, `web=`). A `package.json` with no `Dockerfile` therefore validates and has no image. Use the same rule in both places.
  Resolved: a web service is built with a `package.json` and a `Dockerfile`, the release's rule (`KeysBuiltSuite`), c294a21e
- `sdk/player/src/amount.ts:72` — nitpick (non-blocking): `majorText(a) as unknown as number` relies on `Intl.NumberFormat` taking a decimal string (NumberFormat v3). An engine without v3 converts the string to a `Number`, and exactness is lost silently above 2^53. Name the browsers the site supports in the README, or say that the cast is why.
  Resolved: the engines named at the cast and in the README as the browsers the SDK supports, c294a21e

## Questions

- `sdk/player/src/core/deposit.ts:118-125` — question: `submitting` allows no action, `close` included. A payment read of `AWAITING_REDIRECT` with an empty `redirectUrl` maps to no step. Merchant writes `Initiated.redirectUrl` as a plain `String`, which can be empty. In that case the player stands at `submitting`, unable to close, until the 20-minute bound fires. `AWAITING_PAYMENT` (mapped to `waiting`) is a status merchant never writes (`payments/Status.scala`). The original polls the same way, but did its modal allow close while polling? If it did, allow `close` at `submitting` once the submit has answered. Either way, map an `AWAITING_REDIRECT` with no address to `waiting`.
  Resolved: `AWAITING_REDIRECT` with no address is `waiting`, which may be closed, tested, 7be478b7. `AWAITING_PAYMENT` is kept: it is the original's mapping and costs nothing if merchant never writes it.
- `sdk/player/src/transport.ts:142-155` with `server/session.ts:119` — question: two tabs share one cookie jar. If the realm revokes a refresh token on use (Keycloak `revokeRefreshToken`), a tab whose refresh races another's gets `401`. The server then clears every cookie, including the ones the winning tab has just set, and the re-read finds both tabs signed out. Is rotation with revocation off for the `casino-<brand>` realms? If it is on, answering a refresh `401` without clearing the cookies, and letting the re-read decide, would keep the winner signed in.
  Resolved: rotation with revocation is on (`Realm.scala:61`). As the person chose, a refused refresh keeps the cookies; the transport calls once more with what they hold, and signs out (removing them) when that call is still refused; contracts/site-server.md amended, with race tests, 2b7b780c
- `sdk/player/src/site.ts:173-177` — question: the comment says the games' sessions are ended "while the brand still holds the sign-in". But `closeAll()`'s `endGameSession` calls (keepalive) and `/api/session/sign-out` are sent concurrently, and nothing orders them. Does `players`' logout invalidate an access token the end calls are still carrying? If it does, the end calls can arrive `401` after the refresh cookie is cleared. If it does not, the comment is fine.
  Answered: `players`' logout ends the player session and revokes the realm session, but services check the access token by its signature until it expires, so the end calls carrying it are admitted whichever arrives first; the comment holds.
- thought: a reality-check interval that fails to read is `null` for the whole sign-in (`casino/games.ts:91`), so reality checks are off. That is the original's fail-open (`GameManagerContext.tsx:192`), kept as constitution I requires, so it is not a finding. It may still deserve a line in the spec's *Changes from the original* list for the Anjouan licence's compliance reading.
  Answered: kept as the original (constitution I); recorded under research.md *Known limits* for the licence's compliance reading.

## Praise

- `sdk/player/src/server/passThrough.ts` — praise: the allow-list is the clients' own route tables. Server-only rows are unreachable. The brand comes from configuration, and a page's `?brand=` is checked against it. The player id is checked against the cookie's. A cashier ticket travels only in its header, and a query `ticket` is refused. Only `content-type`, `content-language` and `retry-after` come back. `tests/server/secrecy.test.ts` asserts that the issued values appear nowhere but `Set-Cookie` and `Authorization`. That is the right shape for FR-003 and FR-004.
- `sdk/player/src/behaviour.ts:80-87` — praise: `action` refuses a name the step does not allow before any body runs, and `still(step)` drops a late answer. That makes SC-003 a property of the kernel, not of each behaviour. `noWayRound.test.ts`'s breadth-first walk from each `refused` step then tests that property.
- `services/players/.../sessions/GameSessionEntity.scala:86` — praise: `recordBet` records nothing for an unknown session or a time no later than the one held, so redelivered or out-of-order facts are harmless. `Facts.gameSessions` gained its `BetRecorded => None` case, which would otherwise have crashed the `casino.sessions` publisher.
- `contract/.../money/Currencies.scala` with the CI `cmp` step — praise: the SDK reads the same exponent table the services write by, generated from `Amounts.exponent`, and CI fails when the copy drifts.

## Out of scope

- note: `services/backoffice/src/shared/money.ts:91` (`walletMinor`, branch `030-backoffice`) reads the wallet's major text with two decimals. That is covered by the blocking item above; file it against spec 030 so its merge after this one is gated on it.
  Left: for spec 030, named in amendments.md.

## Checks applied

General checklist: Correctness, Design and scope, Error handling, Concurrency and state, Security,
Data and migrations, Performance, Tests, Observability, API and compatibility, Dependencies and
build, Documentation, Commit and PR hygiene. None skipped. Data and migrations was applied to
`BetRecorded` and the optional `SessionRow.lastBetAt`. There is no SQL in the diff.

`pr-review-typescript`: promises and async, error handling, security at the trust boundary (the
site server), Node runtime (type stripping in the image), modules, types and nullability, tests.
The items `tsc --strict`, `noUncheckedIndexedAccess` and the package's eslint config enforce were
not reviewed. Packaging was not applied: the package is `private`.

`pr-review-scala`: concurrency (consumer calls), serialization and stored-type evolution, types
and totality, error handling, build (`testGrouping`). The items `-Werror` and scalafmt cover were
not reviewed.

Project rules read: `CLAUDE.md` (repository and parent) and the constitution's amended *Scope*. No
`REVIEW.md` and no `.claude/rules/`.

Tests run: `npm test -w sdk/player` (28 files, 297 passed), `npm run typecheck`, `npm run lint`
and `npm run features -w sdk/player` (757 passed, 77 skipped: the scenarios `elsewhere.ts` binds in
other harnesses). Not run: the Scala suites (`players`, `wallet` and `game-provider` need Docker for
their integration kits, so they were read instead), the player site's playwright suites (a browser
over the built site), and `just player-site-it` (a running brand).

## Second round

# Review: the fixes to the first round (spec 027), 448628e6...fa50e612

**Verdict:** Approve with comments. The first round's blocking finding is fixed and holds. One
should-fix is expected before merge: the session behaviour's own refreshes do not follow the
refresh contract this round amended. The rest is optional.
**What it does:** Seven commits answer the first round. The wallet's operator adjustments read major
units by the currency's exponent. A refresh renews the player cookie. A call still `signed_out`
after a refresh signs the player out. A refused refresh keeps the cookies, so a tab that loses a
rotation race goes on with the winner's sign-in. A game session's last bet is kept to within a
minute. `AWAITING_REDIRECT` with no address is `waiting`. The four nits are fixed, and the rollback
limit and the reality-check fail-open are written down.
**Should it exist:** Yes. Each hunk answers a first-round finding, and no hunk goes beyond one.
**Read:** In full: every hunk of the range except `review.md`'s first round. Opened outside the
diff: `sdk/player/src/server/session.ts` (whole), `transport.ts:100-215`, `core/session.ts:60-200`,
`core/deposit.ts:60-200`, `casino/play.ts` (the bet check), `server/passThrough.ts` (where
`signed_out` is answered), the contract's `Amounts.scala` (`exponent`, `parse`, `render`),
merchant's `DepositWorkflow.initiateStep` (where a payment's `redirectUrl` is written),
`release.yml`'s `web=` line, `specs/002-wallet/spec.md` FR-003.
**Size:** 25 files, +339 −37 (119 of them the first round's report). Small; no split needed.

### The first round's resolutions

| First-round finding | Holds? |
|---|---|
| Blocking: operator adjustments read two decimals (`AudienceEndpoints.scala`) | **Holds.** Both adjustment routes pass the route's currency to `Major.parse`. `MajorSuite` covers exponents 0, 2 and 3, and a round trip against `Amounts.render` for EUR, CLP, KWD and JPY. The parser keeps its own sign handling because `Amounts.parse` refuses negatives, and a debit needs one. That choice is right. The spec record is incomplete (see Should fix 3), and there is an overflow at exponent 4 (Nit 1). |
| Should fix: the player cookie is not renewed on refresh (`session.ts:120`) | **Holds.** `issued(..., h.player)` reissues it with `ThirtyDays`. The server test asserts its `Max-Age` equals the refresh cookie's, and fails without the fix (the cookie is absent). The second half also holds: `transport.ts:172-177` tells the listeners when the retry after a 204 is still `signed_out`, and the new transport test fails without it. A player whose cookie lapsed before this version is not restored by a refresh (`h.player` is undefined), but the new branch signs them out instead of leaving them stuck. That is acceptable. |
| Should fix: `BetRecorded` per round doubles writes | **Holds** for the write rate: one event a minute of play. The boundary is right (`isBefore(held + 1 min)`: 59 s is dropped, 60 s is kept), and the entity suite and the bound feature were moved to match. It costs the SDK's margin, though (Should fix 2). |
| Todo: rollback past `BetRecorded` unstated | **Holds.** `amendments.md` *Rollback* and spec 008 FR-017's amendment both say a rollback goes to this version or later. |
| Nit: `tables[module]` answers `Object` for `constructor` | **Holds.** `Object.hasOwn`, with `constructor` and `toString` cases in the 404 test. |
| Nit: malformed escape is a 500 | **Holds.** Caught and answered 404, with a test. |
| Nit: built means `package.json` alone | **Holds.** It now matches `release.yml`'s `web=` rule exactly (a `build.sbt` wins in both). `KeysBuiltSuite` covers all four shapes, and `services/player-site/Dockerfile` exists. |
| Nit: the `Intl.NumberFormat` v3 cast | **Holds.** The engines are named at the cast and in the README. |
| Question: `submitting` cannot be closed with an empty `redirectUrl` | **Holds.** `waiting` allows `close`. A `waiting` step ignores later non-outcome reads (`deposit.ts:155`), so a later read that brought an address would never show it. Merchant writes `redirectUrl` once, at `initiateStep`, together with the status, so an empty one stays empty, and `waiting` is the right step. If a provider ever fills the address later, this line has to change. |
| Question: two tabs racing a rotated refresh | **Holds for calls through the transport**, which is what the commit claims: the server keeps the cookies on a refused refresh (asserted: no `Set-Cookie`, all three cookies still in the jar), and the transport reads, retries, and signs out only when the retry is still refused. **It does not hold for the session behaviour's own refreshes** (Should fix 1). The new client-side race test would also have passed before the change, because the double does not model the server clearing cookies. The server test is what pins the fix. |
| Question: sign-out ordering with `endGameSession` | Answer accepted. No code change was needed. |
| Thought: reality-check fail-open | Recorded in research *Known limits*, as answered. |
| Out of scope: 030's `walletMinor` | Named in `amendments.md` and research. It stays a gate on 030. |

## Blocking

None.

## Should fix

- `sdk/player/src/core/session.ts:167-170` and `:194-196` — issue (non-blocking): `stay` and
  `notIdle` call `transport.session.refresh()` themselves. On `refresh_expired` they move straight to
  `signed_out` and never call `signOut`. Before this range, the server's 401 also cleared the
  cookies, so that was consistent. Now a refused refresh keeps them (contracts/site-server.md: "the
  page calls again, and signs out when that call is still refused"), so this has two failure modes:
  - **A race.** `notIdle` runs on every recent-bet check (`site.ts:167`), so a tab playing a game
    refreshes every 5 minutes. When that refresh loses a rotation race to another tab, or to this
    tab's own `refreshOnce` (the two are not shared), the page shows "your session has ended"
    although the cookies hold a good sign-in. This is the race 2b7b780c set out to fix, still open
    on this path.
  - **A real expiry.** The page shows signed out, but `eo_player` and the dead `eo_refresh` stay for
    up to 30 days. `GET /api/session` reads only cookies, so it answers signed in. A reload therefore
    hydrates a signed-in shell until its first call signs it out.
  Resolved: `stay` and `notIdle` refresh through `renew()`: a refused refresh is tried once more with what the cookies hold (the winner's, in a race), and refused again signs out, which removes them; tests for the won race and the expiry of both, 169075fd

  Suggestion: send a refused refresh in both places through the same decision the transport makes.
  Either expose the transport's single-flight `refreshOnce` (refresh, then `read`, then sign out
  when the read finds no player) as what `session.refresh()` means to a behaviour, or, at the least:
  ```ts
  if (!r.ok && r.refusal.code === "refresh_expired") {
    const now = await transport.session.read();
    if (now.ok && now.player) return tell(signedIn(now.player)); // another tab refreshed
    await transport.session.signOut();
    tell(signedOut({ reason: "refresh_expired" }));
  }
  ```
  Add a session-behaviour case for each branch. The doubles' `refresh` (`testing/doubles.ts:442`)
  would need to model "refused, but the read still finds the player" for the race to be testable.
- `services/players/src/main/scala/io/eitheror/casino/players/sessions/GameSessionEntity.scala:102`
  with `sdk/player/src/casino/play.ts:53-54,96` — suggestion (non-blocking, if-minor): the SDK checks
  every 5 minutes whether `now − lastBetAt ≤ 6 minutes`, using the browser's clock against the
  fact's `occurredAt`. The extra minute was the margin for the `casino.rounds` consumer's lag and for
  clock skew. With `BetPrecision` at one minute, the held time can trail the real last bet by up to
  59 s, which uses that whole margin. A player who played a short burst just after one check can
  then be judged not recent at the next. The cost is small, because one missed check only delays the
  idle reset, but it costs nothing to restore the margin. Either set `BetPrecision` to 30 s (still
  one event per half-minute of play, not per round), or say in FR-057 and at `RecentBetMs` that the
  margin is now about one second plus lag.
  Resolved: `BetPrecision` is 30 seconds, leaving half the SDK's minute of margin; FR-057, spec 008 and amendments.md say so, 17f72d19
- `specs/002-wallet/spec.md` (FR-003) and `features/wallet/adjustments.feature` — todo: the operator
  input change is recorded only in spec 027's `amendments.md` and research. Spec 002's FR-003
  amendment covers only the replies, and spec 001 FR-026, the parse's cited rule, is untouched. Spec
  027's FR-058 says only "write", while `MajorSuite` cites FR-058 for the read. The wallet's living
  spec and its features are what the next reader of the wallet holds. Add the input to FR-003's
  amendment (and to FR-058, or a new FR), and add a CLP or KWD adjustment row to the adjustments
  feature, so the behaviour is bound above the unit level.
  Resolved: spec 002 FR-003 and spec 027 FR-058 state the operator's reads by exponent, and `reads.feature` has the operator's adjustment by EUR, CLP and KWD at the route, bound in `RouteFeatures`, 662696ee

## Nits

- `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/AudienceEndpoints.scala:347-348` —
  nitpick (non-blocking): `whole.toLong * math.pow(10, exp).toLong` overflows silently when the
  exponent is 4: CLF and UYW report 4, and a 15-digit whole number times 10^4 is past `Long.MaxValue`.
  No brand trades either today. Either use `Math.multiplyExact` and `Math.addExact` and refuse on
  `ArithmeticException`, or reuse the contract's parser for the magnitude:
  `Amounts.parse(text.trim.stripPrefix("-"), currency)` with the sign applied afterwards. The second
  also drops the second copy of the decimal rule.
  Resolved: exact arithmetic, an amount past a `Long` refused, with a CLF row, 662696ee
- `sdk/player/src/transport.ts:172-177` — nitpick (non-blocking): `refreshOnce` makes concurrent
  calls share one refresh, but the new still-refused branch is not shared in the same way. K calls in
  flight each send `/api/session/sign-out` and each notify every listener. On a real expiry, each
  sign-out also asks `players` for a refresh that is refused. The session listener ignores repeats,
  so nothing breaks. Share it with a `signingOut ??=` like `refreshing`.
  Resolved: one shared `signOutOnce`, sent once and told once, tested with three calls in flight, 169075fd

## Questions

- `sdk/player/src/transport.ts:172-177` — thought: a narrow race remains. Tab B's retry is refused,
  then tab A refreshes, then B's sign-out is sent. B's sign-out then carries A's new access token and
  logs the player out at `players`, so both tabs are signed out. B makes two round trips (the read and
  the retry) after its refused refresh, so A's 204 almost always lands first. No change is asked for.
  If it is ever seen, have the server's sign-out skip `players` when it receives a session cookie
  different from the one the refused call carried.
  Answered: accepted as the reviewer says; the narrow race signs out both tabs, which is safe, and the player signs in again.

## Praise

- `sdk/player/tests/server/session.test.ts:155-156` — praise: the refused-refresh case asserts both
  that no `Set-Cookie` is sent and that all three cookies survive in the jar. That pins the
  server-side half of the race fix, which the client double cannot.
- `services/wallet/src/test/scala/io/eitheror/casino/wallet/api/MajorSuite.scala:28-32` — praise: the
  round trip through `Amounts.render` for each exponent tests the property the blocking finding was
  about (what an operator reads is what is credited), not just a table of examples.
- `tools/modules/src/test/scala/io/eitheror/casino/modules/KeysBuiltSuite.scala` — praise: one test
  states the release's rule in all four shapes, including the half-built one, so the two sides cannot
  drift again unnoticed.

## Checks applied

General checklist: Correctness, Design and scope, Error handling, Concurrency and state (the refresh
race, concurrent sign-outs), Security (cookie lifetime and clearing on the site server's session
routes), Data and migrations (the `BetRecorded` rate and rollback note), Tests, API and compatibility
(the amended `refresh` contract, the stricter operator amount input), Documentation, Commit and PR
hygiene. Skipped: Performance beyond the event rate (no other hot path changed), Observability
(nothing new to log or measure), Dependencies and build (no dependency change; `Keys.built` was read
as build logic under Correctness).

`pr-review-typescript`: promises and async (the `.catch` on sign-out, the listener loops), error
handling, security at the trust boundary, types and nullability, tests. `pr-review-scala`: types and
totality (the parse regex and guard, `toLong` on an empty fraction, which the guard rules out),
arithmetic overflow, munit tests. Items owned by `tsc --strict`, eslint, `-Werror` and scalafmt were
not reviewed.

Project rules: `CLAUDE.md` (repository and parent). No `REVIEW.md` and no `.claude/rules/`.

Tests run: `npm test -w sdk/player` (28 files, 301 passed), `npm run features -w sdk/player` (757
passed, 77 skipped), `npm run typecheck -w sdk/player` and `npm run lint -w sdk/player` (clean). Not
run, read instead: the Scala suites (`MajorSuite`, `SessionEntitiesSuite`, `SessionsFeatures`,
`KeysBuiltSuite`). Running them publishes the contract snapshot into the shared `~/.ivy2`, which
other worktrees overwrite, and the features and integration kits need Docker.
