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
