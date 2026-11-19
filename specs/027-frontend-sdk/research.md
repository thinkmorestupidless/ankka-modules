# Research: the player SDK

Decisions for the plan, each with what it rests on. Read 2026-11-18: the original site at
`eitheror-platform` a28d2e6f4 (`tools/casino-player-frontend-next`), the player routes on `main`
here, ankka 0.16.3's web hosting reference, and spec 030's research on its branch (not merged).

## R1. One package with a subpath per module, and the reference site as a service

- **Decision**: `sdk/player/` is one npm package, `@eitheror/player-sdk`, private, with subpath
  exports: `.` (behaviour kernel, amounts, refusals, language), `./core`, `./casino`, `./engagement`,
  `./support` (each: that module's client and behaviours), `./server` (the site server), `./react`
  (the adapter), `./testing` (doubles). `services/player-site/` is the reference site, a web-hosted
  service, depending on the package. A root `package.json` names both as npm workspaces.
- **Why**: FR-047 needs a seam per module and nothing needs a package per layer. One version, one
  build, one lockfile.
- **Not**: a package per layer (five versions to keep in step for no consumer who takes one alone);
  the SDK inside the site's own source (SC-008 and the next site need it apart).
- **With 030**: `services/backoffice/` has its own lockfile on its branch. Whichever merges second
  adds the other to the workspaces or leaves it standing alone; neither blocks the other.

## R2. A behaviour is a small hand-written machine

- **Decision**: `Behaviour<Step, Actions>`: `step()` answers the current step (a tagged object with
  its fields and `allowed`, the names of the actions it allows), `subscribe(listener)`, and
  `actions`, whose every member answers `{ok: true}` or `{ok: false, notAllowedAt: step.kind}`
  (FR-012). A machine is a `transition(step, event)` function, pure, and an `effects` table that
  runs calls through the module clients and feeds their answers back as events. Time is a `Clock`
  the machine is given (`now`, `after`, `every`), so every timer in the spec is tested without
  waiting.
- **Why**: the steps are few (9, 12, 9, 3, 7), the rule "no action goes past a refusal" is then a
  property of one function a test can enumerate (SC-003), and there is nothing to learn.
- **Not**: XState (a dependency and a vocabulary for what a `switch` holds; its visualiser is not
  worth the weight here); the original's shape, state in components.

## R3. The sign-in is in cookies, and the site server keeps no state

- **Decision**: as the original. `__Host-eo_session` holds what `players` issued for calls, for its
  life; `__Secure-eo_refresh`, path `/api/session` (so that sign-out can refresh first), 30 days, holds what refreshes it;
  `__Host-eo_player` holds `{playerId, currency, country}` for FR-007, readable by nothing but the
  server. All `HttpOnly`, `Secure`. The server has no store and any instance answers any request.
- **Changed from the original**, and declared in the spec: `SameSite=Lax`, not `Strict`. A player returning from the payment
  provider in the same tab, and the game provider's own deposit button (FR-025), arrive from
  another site, and under `Strict` arrive signed out. In exchange the server refuses any request
  that is not a `GET` whose `Origin` is not the site's own, and no `GET` changes anything.
- **Not**: a state service as the backoffice has (`backoffice-state`, 030 R2). An operator's token
  must never leave the server side and one writer must refresh it; a player's sign-in is already
  the player's own, a cookie the page cannot read holds it as well as a store, and the original
  ran real money this way. **Not**: the token in the page (decided 2026-11-18).
- **Two tabs**: the realm rotates on refresh with no reuse (`Realm.scala`). Tab B's refresh may be
  refused after tab A's. On `refresh_expired` the SDK reads `/api/session` once: if it now answers
  a player (tab A's cookies), the call is made again; only otherwise is the player signed out.

## R4. The page asks the site server, which asks the service at the calling address

- **Decision**: the page calls `/api/c/<module>/<route id>` and nothing else; the server looks the
  route id up in the module clients' route tables (R5), fills `{brand}` from its configuration,
  adds `Authorization`, and calls `ANKKA_SERVICES_URL/<service>/<path>`. Not a mount.
- **Why**: a mount reaches the service as the internet and gives the server no place to add the
  sign-in. Spec 030 verified on `dev-kind` (0.16.3) that a service's route judges a call from a
  web-hosted service by its token alone (030 research, V1 (a)).
- **The cashier** is in the shared `payments` project, and 030's V1 (b) found the calling address
  does not reach another project. The server calls the cashier's submit, abandon and payment read
  at the shared payments' public address, the configuration key `ingest-url`, as `players` does.

## R5. A module client is a route table and typed functions over it

- **Decision**: per module, `routes.ts` is a table `{id, service, method, path, audience, stream?}`
  and `client.ts` is an interface with one function per row and one implementation,
  `overTransport(transport)`. The page's transport sends the row's own method to `/api/c/<module>/<id>`; the server's own transport
  (sign-in, refresh, sign-out) calls the calling address. The server's allow-list is the same
  tables (FR-004). The later generated client produces the same two things from a service's
  OpenAPI document, which is why the seam is here (FR-034).
- **Types** are written by hand from the services' `contracts/routes.md` and checked by FR-036's
  test per function against the running service. Zod parses each answer at the boundary: an
  answer that does not fit is `malformed_answer`, never a wrong field on a page.

## R6. Amounts

- **Decision**: `Amount = {minor: bigint, currency: string}`. `readMajor("1234.50", "EUR")` splits
  the text at the point and checks it against the exponent from `currencies.v1.json`; it never
  calls `Number`. The table does not exist today (`Amounts.exponent` asks the JDK and `just
  schemas` writes topic schemas only): the contract gains a task that writes it from
  `java.util.Currency`, copied into the package, with a CI check that the copy is current.
- **The wallet** writes two decimals for every currency today (`Wire.major`), which is not major
  units for a currency of none or three. Decided 2026-11-18: the wallet is amended to write by the
  exponent (`Amounts.render`), not the SDK taught the fault. Two-decimal currencies are unchanged.
  `format(amount, language)` writes the minor units as decimal text by the exponent and gives
  that text to `Intl.NumberFormat` with the currency, which formats a decimal string exactly.
- **Where major units arrive**: the wallet's player balance (`realBalance`, `bonusBalance`,
  `available`). Everything else this slice reads is already minor, except a crypto payment's
  amount in its coin, which has no entry in any currency table and stays the cashier's text.
- **Why `bigint`**: an amount in minor units of a three-decimal currency can pass 2^53 before a
  balance is implausible; and it stops arithmetic with a stray `number`.

## R7. Refusals

- **Decision**: `Refusal = {status, code, fields, service?}`. `readRefusal(status, body)` reads the
  platform's `{status, error: "code: explanation", details?}`, a handler's `{error, message, …}`
  and a handler's `{error, …}`. `codes.ts` lists every code a behaviour can meet, by behaviour;
  a site's catalogue is typed `Record<RefusalCode, string>` per language, so a missing one is a
  type error and SC-006 is the compiler's.
- **The support agent's words** (FR-054) and a payment provider's rejection words (FR-022) are
  carried as `words` beside the code and are the only text of a service a behaviour passes on.

## R8. Language

- **Decision**: the SDK ships `chooseLanguage(offered, kept, browser)` and
  `catalogue(languages, words)` with the fallback of FR-041; no translation library. The reference
  site keeps the choice in a cookie, `eo_lang`, one year, as the original kept `eo_locale`.

## R9. Streams through the site server

- **Decision**: the push connection and the chat's two routes are passed through as they arrive:
  the server opens the service's stream with the sign-in and pipes it. The page reads both with
  `fetch` and a small event-stream reader in the SDK, since the chat's are `POST`s and
  `EventSource` cannot post.
- **To verify (V2)**: that the platform's proxy and gateway deliver the process's events
  unbuffered. 030 lists the same as its V5. If they are held back: the inbox falls back to reading
  the unread count every 30 seconds, and the chat's reply arrives whole.

## R10. Polling, as the original

- Balance every 5 s while the page is visible (FR-026); a payment every 1.5 s then 3 s for 20
  minutes (FR-020); the game session every 5 minutes (FR-011). All through the `Clock` (R2).
- **Not** a stream for balances: no service serves one, and adding one is a spec in the wallet.

## R11. `players`' deposit start, amended

- **Decision**: the start reads an optional `requestId`, sends it to the cashier as
  `transactionId`, and answers `{redirectUrl, paymentId, cashierTicket, terminals, requestId}`,
  where `cashierTicket` is the cashier's `session_token` and `terminals` its
  `available_terminals` unchanged. The cashier already returns the first session to a repeat with
  the same content and refuses another amount as `REQUEST_ID_REUSED` (006). No stored data.
- **Parity**: `tests/port/players/` recordings of the start still pass: the answer only gains
  fields.

## R11b. The catalogue before sign-in

- **Decision**: `game-provider`'s catalogue reads become public routes. The original's site proxy
  serves `catalog/` and `geo/lookup` with no session and nothing else; spec 003's routes contract
  says the reads become public if that is so. A lobby a visitor cannot see is not a player site.

## R12. The reference site

- **Decision**: React 19, Vite, TanStack Router, Hono for the process, Tailwind 4, as the
  backoffice (030 R7), so the two are one toolchain. Pages: `/` (lobby), `/register`,
  `/verify-email` (the link `players` mails), `/sign-in`, `/game/{code}`, `/cashier/return`, and
  the deposit and the chat as overlays. Words in `words/fr.ts`, `words/en.ts`.
- **Appearance**: eitheror's own, plain; built with the `frontend-design` guidance at task time.
  It is a reference, not a brand.
- **SC-001's test**: an import rule (`eslint no-restricted-imports`, `no-restricted-globals`, and
  a vitest that walks the built module graph) forbids `fetch`, `XMLHttpRequest`, `EventSource`,
  `navigator.sendBeacon`, and any import of `@eitheror/player-sdk/*/client`, everywhere under
  `services/player-site/src` except `server/`.
- **The deposit address** is any page with `deposit=true`, as the original's.

## R13. The module and the brand

- **Decision**: `modules/player-site/module.json`, kind `product`, depending on `core`; one service
  `player-site`, `hosting: web`, `expose: true`; env from keys `casino-brand`, `ingest-url`,
  `player-site-base`, and new keys `player-site-languages` (`fr,en`) and `player-site-currency`
  (`EUR`). Which behaviours the site offers is read from the existing key `casino-modules`
  (`CASINO_MODULES`), the brand's own list, never by asking whether a service answers: an outage
  must read as unavailable, not as a module the brand does not run. Through a vendor the brand
  writes that key by hand for now (`contract/keys.md`).
- `brands/guyanabet/values.json` after `goldvibe`'s: `realMoney: false`, providers
  `eitheror:st8`, `player-site-base` the site's derived hostname.
- **To verify (V1)**: that the modules tool on `main` renders and locally applies a module whose
  service is `hosting: web` with no database. 030 changed `LocalApply`, `Keys` and `Values` for
  it on its branch; if `main` lacks it, the same small change is made here and the two merge.

## R14. Tests

| Level | What | With |
|---|---|---|
| Unit | transitions, amounts, refusals, language, each client function's parsing | vitest, the original's tests ported (`DepositContext.test.ts`, `useGuardedPlay.test.ts`, `DepositModal.test.ts`, `unreadCount.test.ts`; `rgApi.test.ts`'s cases for the status this slice reads) |
| Behaviour | every scenario of `features/player-site/` except `reference-site.feature`, no browser | `vitest-cucumber` steps over the behaviours, the `Clock` double and `./testing`'s service doubles |
| Site server | cookies, the allow-list, refresh, origin check, stream pass-through | vitest against the server over doubles |
| Site | `reference-site.feature`, and SC-008's second appearance | `playwright-bdd` over the built site and doubles |
| Client contract | one test per client function against the running service (FR-036) | `just player-site-it`, on `dev-kind`, on purpose only |
| Scala | `features/players/deposit-start.feature`; the parity suite unchanged | `players`' `GherkinSuite` |

## R15. Constitution

The amendment (1.3.0 here; 1.4.0 if 030 merges first, the *Scope* paragraph then holding both):
the player site is in scope as the player SDK and a reference site; a site built on the SDK holds
no rule of a service and reaches one only as the signed-in player; principle VI's reason for
TypeScript is FR-048's.

## To verify at task time

| | What | If it fails |
|---|---|---|
| V1 | the modules tool renders and locally applies a `hosting: web` service | the change 030 made is made here |
| V2 | events from the process reach the browser unbuffered through the proxy and gateway | R9's fallbacks |
| V3 | `players`' sign-in, registration and country lookup see the visitor's network address and user agent the site server forwards (`X-Forwarded-For`, `User-Agent`) from a call at the calling address, where the proxy removes `X-Forwarded-For` from a service's request | the server sends them in the headers `players` reads for a trusted caller, or `players` is amended to read `X-Client-Address` from the service `player-site` only |
| V4 | `rewards`' free rounds answer names the games each grant is for | the funds rule counts any free rounds as playable for any game, and the launch decides |
| V5 | the catalogue's game answer carries its restricted and prohibited territories to a player | FR-031 reads what it carries; if neither, the rule is dropped and the launch decides |
| V6 | resolved in analysis: `players` derives no session totals; `RoundsActivity` consumes `casino.rounds`, whose facts carry `sessionId` and `occurredAt`, and tells `GameSessionEntity` | — |

## Analysis, 2026-11-18

An independent read of the artifacts against the services' code and the original found 30
findings (3 critical, 7 high, 15 medium, 5 low). All were applied. Those that were decisions:

| Finding | Decided |
|---|---|
| A recent bet "not idle" was invented, and `lastBetAt` is always empty | kept, declared as a change; `players` answers the last bet (FR-057) |
| `players` drops the figures of a refused start | passed on (FR-018) |
| The wallet writes hundredths for every currency | the wallet is amended (FR-058) |
| The reality check's count contradicted itself | time shown, cumulative, declared as a change (FR-033) |
| Eight scenario corrections | all applied |

A second round found one critical and seven medium findings, all applied. Decided: several games
may be open at once, as the original (FR-059); the wallet's amendment covers every route that
writes major-unit text (FR-058). Corrected: the brand's values write no `casino-modules`; the
last bet is stored on the game session; play at `needs_deposit` opens the deposit inside the SDK;
the task order; the quickstart.

A third round found one critical and three medium findings, all from several open games.
Decided: the reality check keeps one count for the player across the open games, as the
original; a game whose session the brand ended stands at ended at its next bet check (FR-060).
Corrected: `site.games` is a behaviour and `currentDeposit` is subscribed to; a pick of a game
standing at a `needs_*` step runs the checks again. No round checked these last edits.

In the first round, applied without a question, being corrections of fact: the cookie's `Lax` and the origin check
declared; the constitution amended first; the payment id is the submit's; a cashier ticket only in
a header and only for a signed-in player; which `401` refreshes; the refresh cookie's path; the
modules from the brand's own key; the session's missing transitions; the coin list's source; the
visitor's address forwarded for the country; every refusal code listed with one name per field;
the brand's onboarding on the installation; the task order.

## Known limits

- French Guiana is a department of France: French law, not the Anjouan licence, governs gambling
  offered there, and it does not permit online casino games. The brand takes no real money and
  this spec does not change that; the question is the person's before it ever does.
- The site server holds the sign-in in a cookie of at most 4 KB; the realm's tokens are about
  1.5 KB today.

## Found during implementation

- **V1, rendering (T004)**: `main`'s modules tool renders a module service's `hosting: web` as
  it is, with no protocol, on the wire (`RenderSuite`). What it lacked is 030's D4: a service with
  a `package.json` and no `build.sbt` counted as unbuilt, so an apply needed `--allow-unbuilt`.
  `Keys.built` now takes either, as on 030's branch. The local apply on `dev-kind` is checked with
  the other installation steps (T005, T006, T060, T051).
- **The coins (T023)**: the original's coin picker lists the crypto terminal's own
  `supportedCurrencies` (`DepositModal.tsx`, `select_crypto`), not a fixed list. `choosing_coin`'s
  `coins` are that list, as the original, and `schemes.ts` holds none.
- **`schemeUtils.ts` (T023)**: its labels and icons are words for a player, which the package holds
  none of (FR-022). Ported are its rules only: a coin's network (`coinNetwork`) and which
  terminals share a scheme (`sharingScheme`, the rule of `disambiguatedTerminalLabels`), with the
  original's cases for them. A site writes the labels in its own words.
- **Details asked (T023)**: the original asks a card's whole form when one detail is missing and
  Interac's name and mobile in two forms. The `details` step names only the needs the profile
  lacks or holds malformed, all at once, with the rest as `known`; the checks on what is given are
  the original's.
- **The package's scripts (T019)**: `vitest --dir` resolved the include patterns under the
  directory and found no file; the scripts filter by path.

- **The deposit start (T020, T021)**: a request id is 1 to 64 of `[A-Za-z0-9_-]`, as the
  amendments say (inside ingest's `REQUEST_ID_BOUND`); JSON `null` is absent; any other value is
  `400 invalid_request_id`, checked after the amount and the currency. With none, players sends no
  `transactionId` and ingest mints it. `409 REQUEST_ID_REUSED` passes on ingest's fields and adds
  `requestId` and `service: "ingest"`; every ingest refusal now passes on its fields beside
  `error`. A cashier answer with no `session_token` is unreadable, `503` naming `ingest`; no
  `available_terminals` is `[]`. Responsible gambling's `reason`, `endsAt`, `period`, `limit`,
  `used`, `remaining` and kyc's `kycStatus` are passed on whenever present. `tests/port/players/`
  holds no deposit recording, so there was no deposit parity to keep (the 60 parity tests pass).
  The feature's brand is `guyanabet`, so `DepositStartFeatures` runs its own kit in its own JVM.
- **The last bet (T058)**: `RecordBet` for an unknown session, or a time no later than the one
  held, is no event; an ended session still records a round settled after its end.
  `SessionRow.lastBetAt` is an added optional field (epoch milliseconds), so the view keeps its
  version. `Facts.gameSessions` gained a case for `BetRecorded`, which would otherwise have
  crashed the `casino.sessions` publisher.
- **The catalogue (T027)**: an old `SharedKitSuites` case asserted an anonymous read is `401`; it
  now asserts `200` for the four reads, `404` for another brand, and `401` for an anonymous launch.
- **The wallet's major units (T059)**: no reader of the wallet's major text outside the wallet on
  this branch; every other service uses the service routes' minor units. The operator's adjustment
  input (`Major.parse`) read two decimals; the review found that this would credit CLP 100 times
  over, so it now reads by the exponent too (amendments.md). Left open, outside this spec: 030's backoffice reads the text with two
  decimals whatever the currency (`services/backoffice/src/shared/money.ts:91`, `walletMinor`, and
  its callers and tests), which is wrong for a currency of 0 or 3 decimals once this merges.

- **The inbox's push (T036, T037)**: the inbox holds `engagement`'s stream, as R9 chose; T005,
  which would show whether the gateway holds events back, has not run (the installation is down),
  so the 30-second read R9 names as the fallback is not built. The stack harness's page fetch
  recorded each answer whole, which held a stream until it ended; it now passes a stream on as it
  arrives. A balance that does not fit its currency is refused `malformed_amount` (the feature's
  code), where the client first answered `malformed_answer`.

- **T054 (2026-11-18)**: the reference site's first page loads one script, 142.1 KB compressed (gzip,
  KiB) against the plan's 150 KB; `npm run size -w services/player-site` measures it after a build and
  CI's player-site job fails over the budget. React, the router and the SDK are most of it; the
  margin is 8 KB, so a page that adds a library splits it out of the first page. `npm audit
  --omit=dev` at the root: 0 vulnerabilities.

- **T055 (2026-11-18)**: the quickstart walked on a clean worktree of the branch, but for section 5
  (dev-kind, T060 and T051). Green: `just player-sdk-test` (296), `just player-sdk-features` (757),
  `just player-site-features` (5 scenarios in a browser), the site's suites (19), `just features`
  (0 findings in 1440 scenarios), `sbt test` at the root (272, 185, 36), the Go tests, and the
  amended services' suites under `CASINO_VERSION=0.2.0-027-SNAPSHOT`: players 444, wallet 791,
  game-provider 277. The walk found two misses, both fixed: the contract's `LayoutSuite` did not
  count `player-site`, and the original's players driver named none of spec 027's eight amended
  scenarios as not driven. The quickstart named `just provider-test` (the shared house provider)
  for the catalogue, which is `game-provider`'s; it now names `just service game-provider test`.
  The plan's scale table counted the deposit's steps as 12; there are 13 with `closed`. Section 3
  by hand over the doubles: every sign-in cookie `HttpOnly` (and `Secure` but locally), the
  sign-in's answer holds no token, and the page asked only its own origin and the game's frame.

- **One machine in place of `dev-kind` (T004 to T006, T060, T051; 2026-11-19)**: the person
  directed `ankka local up` (docker compose) in place of the kind installation, which ran out of
  CPU; kind was stopped while it ran. `local/generate.py` writes the brand as one project beside the
  shared services it calls (`local/README.md` and the script's docstring list every difference).
  The tasks' "on dev-kind" is read as on that project. What one machine does not stand in for
  (identity, certificates, the gateway, more than one instance) is ankka's own list.
- **ST8's key**: game-provider reads `ST8_API_KEY` from a secret, so a local value is enough. ST8
  is the game provider simulator (spec 020) as a second provider configuration `st8`, beside the
  house provider `eitheror`, which has no game yet. Nothing here reaches ST8.
- **V1 (T004)**: the modules tool renders the brand's plan with the web-hosted `player-site`
  (`hosting: web`, no database, no protocol) and `ankka local up` runs it as the proxy and its
  program. The local apply through the tool was not needed: the plan is what the project is made
  from (`just local-project`).
- **V2 (T005)**: through the site's proxy, `engagement`'s stream opens in about 30 ms with `ready`,
  and a signal arrives 3.0 s after an operator's message while the stream stays open: nothing on the
  way holds events back (`tests/contract`, `push`). R9's fallback is not needed. The gateway in
  front of an installation is not on one machine and is not checked here.
- **V3 (T005)**: the calling address passes `X-Forwarded-For`, `X-Real-IP` and `User-Agent` to
  `players` as the site server sends them (from inside the site's container, the country of a
  built-in address is found). The local proxy, with no gateway in front of it, passes on no
  visitor's address, so on one machine players finds no country. `contracts/site-server.md` names
  the headers. No fallback taken.
- **V4, V5 (T006)**: `rewards`' free rounds answer each grant with its `gameId` (with
  `spinsRemaining`, `expiresAt`; read in its code, and `[]` on the brand for a player with no
  grant); the catalogue's game answer carries `restrictedTerritories` and
  `prohibitedTerritories`. Shapes in `contracts/module-clients.md`. No fallback taken.
- **`gameCode` is `gameId` (T006)**: the catalogue answers `gameId`; the client and the doubles had
  `gameCode`. Renamed in the SDK, the doubles, the site and `contracts/module-clients.md`, as the
  person chose.
- **A country at registration**: a player registered with only an email address and a password
  could not deposit: `players` opened the cashier with the profile's country, and ingest refuses
  a session without one. The person chose: `players` falls back to the country registration found
  from the address (spec 008 FR-043, amended), and the reference site's registration asks for a
  country only when `session.visitorCountry()` finds none (contracts/behaviours.md).
- **The house provider's entry** allows `language` `es` or `pt` only (026 contracts/brand-entry.md),
  and `guyanabet` is French first; the local entry says `es`. Left for spec 026: whether the entry
  takes `fr`.
- **Merchant's operator routes** need a staff token holding `platform_admin` and the claim
  `eitheror_scope_level=platform`, from a user attribute through the `eitheror-operator-scope`
  client scope (`local/guyanabet/operator-token.sh`).
- **DreamzPay's address** is the player's browser's, so on one machine it is `localhost`, not the
  address the services use for one another.
- **Found in ankka's CLI, not fixed here**: the readiness check asks port 7630, which the 0.13
  runtime does not serve, so those services stand at "running, not ready" while working;
  `ankka local up <names>` stops every service it is not given; the schema cache's directories are
  made read-only and then cannot be written; 13 JVMs pass Postgres's default 100 connections.
- **T060**: each onboarding step's command is in `brands/README.md`; `just local-onboard` runs them.
- **T051 (2026-11-19)**: `just player-site-it`: the contract tests, 24 of 24, one per client
  function against the running brand, with the deposit completed at the provider stub as a browser
  would and a real-money game session; then `reference-site.feature`'s end-to-end scenario in a
  French browser, playing after 10.6 s (SC-007: within 3 minutes).

## Design finished

2026-11-18: the person proceeded with 4 findings above LOW from the third analysis round, each
applied after it and checked by no further round:

- CRITICAL: the reality check counted per open game, where the original keeps one count for the
  player. Now one count across the open games (FR-059).
- MEDIUM: nothing told a page when the open games or the current deposit changed. `site.games` is
  a behaviour; `site.subscribe` tells of `currentDeposit`.
- MEDIUM: a game standing at needing a deposit was not launched when picked again. A pick runs
  the checks again.
- MEDIUM: no transition for a game session the brand ended. A bet check that reads one stands at
  ended (FR-060).
