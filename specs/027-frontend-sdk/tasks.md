# Tasks: the player SDK — a site server, behaviours and a reference player site

**Input**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

**Tests**: required. Every scenario of `features/player-site/` except `reference-site.feature` is
bound to the behaviours with no browser; `reference-site.feature` is bound over the built site;
the amendments are bound in `players`' and `game-provider`'s `GherkinSuite`s; the original's unit
tests for the extracted logic are ported (research R14).

**Order**: the plan's phases. The kernel, the rules and the site server before any behaviour; then
the session, because every other behaviour runs under it; the deposit with `players`' amendment;
play; the adapter and the seam; the rules as features; inbox and chat; the site, the module and
the brand last, because they need every behaviour.

Paths: `K` = `sdk/player/src`, `KT` = `sdk/player/tests`, `S` = `services/player-site`, `O` =
`~/Documents/projects/eitheror/eitheror-platform/tools/casino-player-frontend-next/src` (the
original, read only).

## Phase 1: Setup and verification

- [ ] T001 Root `package.json` (private, workspaces `sdk/player`, `services/player-site`, Node `>=24`) and `sdk/player/package.json` (`@eitheror/player-sdk`, private, `type: module`, the subpath exports of research R1, scripts `typecheck`, `test`, `features`, `lint`), `tsconfig.json` (strict, `noUncheckedIndexedAccess`), `eslint.config.js`, `vitest.config.ts`; `.gitignore` for `node_modules`, `dist`; `npm ci && npm run typecheck -w sdk/player` passes on empty sources
- [ ] T002 [P] `Justfile`: `player-sdk-test`, `player-sdk-features`, `player-site-run`, `player-site-features`, `player-site-it`, per `quickstart.md`; `just schemas` also writes `currencies.v1.json` into `K/` (research R6); a line each in the root `README.md`
- [ ] T003 [P] `.github/workflows/ci.yml`: path filters `sdk/**`, `services/player-site/**`, `features/player-site/**`; a `player-sdk` job (`npm ci`, typecheck, lint, test, features) and a `player-site` job (build, features); `players`' and `game-provider`'s jobs unchanged
- [ ] T004 V1 (research): on a branch-local run, `just modules render` and `just modules apply … --installation dev-kind` of a throwaway module whose service is `hosting: web` with no database; record the result under "Found during implementation" in `research.md`; where the modules tool refuses or mis-renders, make the change in `tools/modules/src/main/scala/io/eitheror/casino/modules/` (`LocalApply`, `Keys`, `Values`) with a suite in `tools/modules/src/test/`
- [ ] T005 [P] V2 and V3: a probe web-hosted service on `dev-kind` (after `tests/spikes/backoffice-v1/` on branch `030-backoffice` if present, else new under `tests/spikes/player-site-v/`) that (a) pipes `engagement`'s `/v1/stream` under a player's sign-in and shows events arriving one by one at a browser through the gateway, (b) calls `players`' sign-in at the calling address with forwarded address and user agent and reads what the player session recorded; record both results in `research.md`, and take the stated fallback where one fails
- [ ] T006 [P] V4 and V5: read `rewards`' free-rounds answer and `game-provider`'s game answer on `dev-kind` for the games a grant names and the territories a game carries; record the shapes in `contracts/module-clients.md` and the results in `research.md`; take the stated fallback where one is missing

## Phase 2: Foundational — the kernel, the rules and the site server

- [ ] T007 `K/behaviour.ts`: `Behaviour<S, A>`, `machine({initial, transition, effects, clock})`, the `{ok} | {ok: false, notAllowedAt}` answer of every action from the step's `allowed`, `subscribe`, `stop` (`contracts/behaviours.md`); `K/clock.ts` (`now`, `after`, `every`, the real one) and `K/testing/clock.ts` (advanced by hand); suite `KT/unit/behaviour.test.ts`: an action not in `allowed` runs no effect; listeners told once per change; `stop` cancels every timer
- [ ] T008 [P] `K/amount.ts`: `Amount`, `readMajor(text, currency)` exact by the exponent from `K/currencies.v1.json`, refusing `malformed_amount`; `fromMajor(number | string, currency)` for a site's presets; `format(amount, language)`; suite `KT/unit/amount.test.ts` with the rows of `features/player-site/amounts-and-refusals.feature` and one case for every currency in the table (SC-005); an eslint rule in `sdk/player/eslint.config.js` forbidding `Number(`, `parseFloat` and `* 100` in files that import `amount.ts`
- [ ] T009 [P] `K/refusal.ts` (`Refusal`, `readRefusal(status, body)` over the three forms, the colon split) and `K/codes.ts` (every code by behaviour, `RefusalCode`, `unknown`, `malformed_answer`, `service_unavailable`, `not_named`, `signed_out`); suite `KT/unit/refusal.test.ts` with a recorded body of each form from `tests/port/players/`, `tests/port/kyc/`, `tests/port/game-provider/` and the platform's `wrong_brand`
- [ ] T010 [P] `K/language.ts`: `chooseLanguage(offered, kept, browser)`, `catalogue(languages, words)` with the fallback to the first language, typed so that a `Record<RefusalCode, string>` missing a code does not compile; suite `KT/unit/language.test.ts`
- [ ] T011 `K/transport.ts`: `Transport` (`call(module, id, {path, query, body, headers})` → `{ok, value} | {ok: false, refusal}`, `stream(...)`), `pageTransport(fetch)` posting to `/api/c/<module>/<id>` with one shared refresh per page on `signed_out` and the re-read of research R3; `K/eventStream.ts` (a reader of `text/event-stream` over a `fetch` body: event name, data, end); suites `KT/unit/transport.test.ts` (three calls refused at once refresh once and are each made once more; a refused refresh re-reads the session before signing out) and `KT/unit/eventStream.test.ts` (frames split across chunks; a body ending mid-frame)
- [ ] T012 `K/server/config.ts` (the configuration of `data-model.md`, read from the environment, refusing a missing field by name) and `K/server/session.ts`: the routes and cookies of `contracts/site-server.md` (`sign-in`, `refresh`, `sign-out`, `GET /api/session`, `GET /api/site`), plain names and no `Secure` when `X-Ankka-Caller` is `local`; suite `KT/server/session.test.ts` over a double of `players`
- [ ] T013 `K/server/passThrough.ts`: `/api/c/<module>/<id>` per `contracts/site-server.md` (the allow-list from the modules' route tables, `{brand}` filled, the three audiences, 15 s bound, streams piped unbuffered, `traceparent` passed, `Cookie` and the page's `Authorization` never passed); the `Origin` check on every non-`GET`; the request log with no header, cookie or body; `K/server/modules.ts` (which modules answer at the calling address, re-read every minute); `K/server/app.ts` (`siteServer({static})`, static files as the contract says); suites `KT/server/passThrough.test.ts`, `KT/server/origin.test.ts`, `KT/server/static.test.ts`
- [ ] T014 [P] `K/testing/doubles.ts`: scripted doubles of `players`, `wallet`, `responsible-gambling`, the cashier, `game-provider`, `rewards`, `engagement` and `support-agent` at the level of a `Transport`, and as HTTP servers for the site server's and the site's suites (each route of `contracts/module-clients.md`, each refusal the spec names, late and silent answers)
- [ ] T015 `KT/server/secrecy.test.ts` (SC-004): after a sign-in, a refresh and a pass-through call over the HTTP doubles, the issued values appear in no response body, no response header but `Set-Cookie`, no log line and no trace attribute
- [ ] T016 `KT/features/setup.ts`: `vitest-cucumber` wired to `../../features/player-site/*.feature` with a world holding a `Transport` over the doubles, the hand clock and a `playerSite`; a check that every scenario of a bound file has steps, so an unbound scenario fails the run

## Phase 3: User Story 1 — the sign-in is held by the site server (P1)

**Goal**: register, verify, sign in, stay signed in, sign out, with the sign-in never in the page.
**Independent test**: `features/player-site/session.feature` bound over the doubles; the server's
suites of Phase 2.

- [ ] T017 [US1] `K/core/routes.ts` and `K/core/client.ts`, the `players` session rows of `contracts/module-clients.md` (`register`, `verifyEmail`, `resendVerification`, `requestPasswordReset`, and server-only `signIn`, `refresh`, `signOut`), each answer parsed by its Zod shape; suite `KT/unit/coreClient.session.test.ts`
- [ ] T018 [US1] `K/core/session.ts`: the session behaviour (`data-model.md`: Session; `contracts/behaviours.md`), hydrated from `GET /api/session`, the idle warning and sign-out on the clock from key presses, clicks and scrolls given through an `activity()` input, `notIdle()` for play's report; suite `KT/unit/session.test.ts`
- [ ] T019 [US1] Bind `features/player-site/session.feature` in `KT/features/session.steps.ts`, the site-server scenarios (the page never given the sign-in; a request for what the SDK does not name; another brand) against the server over the HTTP doubles

## Phase 4: User Story 2 — a player deposits through the deposit behaviour (P1)

**Goal**: a deposit from amount to outcome that exists only after the brand's checks.
**Independent test**: `features/player-site/deposit.feature` over the doubles;
`features/players/deposit-start.feature` in `players`' suite.

- [ ] T020 [US2] `players`: `DepositRoutes.deposit` reads `requestId` (refusing `invalid_request_id`), sends it as the cashier's `transactionId`, and answers `cashierTicket`, `terminals`, `requestId` beside today's fields; `Ingest.read` and `CashierOutcome.Opened` carry them; a `409 REQUEST_ID_REUSED` of the cashier is passed on naming `ingest` (`contracts/amendments.md`), in `services/players/src/main/scala/io/eitheror/casino/players/api/DepositRoutes.scala` and `clients/Ingest.scala`; the answer never logged
- [ ] T021 [US2] Bind `features/players/deposit-start.feature` in `players`' `GherkinSuite` (`services/players/src/test/scala/.../steps/`), and confirm the parity suite's deposit recordings in `tests/port/players/` still pass; edit `specs/008-players/spec.md` FR-043 and `specs/008-players/contracts/routes.md` with "Amended by spec 027"
- [ ] T022 [P] [US2] `K/core/routes.ts`, `client.ts`: `startDeposit`, `profile`, `changeProfile`, `balance` (major text to `Amount`), `submitCashier`, `abandonCashier`, `payment`; suite `KT/unit/coreClient.deposit.test.ts`
- [ ] T023 [P] [US2] `K/core/schemes.ts`: the table of `contracts/behaviours.md` with the validations of `O/components/cashier/DepositModal.tsx` (`handleTerminalSelect`, `handleDetailsSubmit`), `O/lib/schemeUtils.ts` and `O/lib/canadianProvinces.ts`; port `O/lib/schemeUtils.test.ts`, `O/lib/canadianProvinces.test.ts` and the phone and preset cases of `O/components/cashier/DepositModal.test.ts` into `KT/unit/schemes.test.ts`
- [ ] T024 [P] [US2] `K/core/funds.ts`: `Funds`, `hasPlayableFunds(funds, game?)`, the balance read on sign-in, on demand, after a deposit and every 5 s while visible (FR-026, FR-027); port `O/context/DepositContext.test.ts` into `KT/unit/funds.test.ts`
- [ ] T025 [US2] `K/core/deposit.ts`: the deposit behaviour (`data-model.md`: Deposit; `contracts/behaviours.md`): one start per confirmation under a minted request id, the cashier ticket held in the machine and in no step, the two read intervals and the 20-minute bound on the clock, abandon on close not awaited, `depositAddress(session, deposit)` for FR-025; suite `KT/unit/deposit.test.ts` (each transition; a second confirm; a failed read ignored; the bound)
- [ ] T026 [US2] Bind `features/player-site/deposit.feature` in `KT/features/deposit.steps.ts`

## Phase 5: User Story 3 — a game is launched only when the brand allows it (P1)

**Goal**: pick, launch, play and leave a game, each refusal a step.
**Independent test**: `features/player-site/play.feature` over the doubles.

- [ ] T027 [US3] `game-provider`: `CatalogEndpoint`'s four reads move to `Routes.public` (`services/game-provider/src/main/scala/io/eitheror/casino/gameprovider/api/Endpoints.scala`); bind the added scenario of `features/game-provider/catalogue.feature` in its `GherkinSuite`; edit `specs/003-game-provider/spec.md` FR-027 and `contracts/routes.md` with "Amended by spec 027"
- [ ] T028 [P] [US3] `K/casino/routes.ts`, `client.ts` (`launch`, `games`, `game`, `freeRounds`) and the core rows play uses (`gameSession`, `endGameSession` with `keepalive`, `country`, `protection`, `acknowledgeRealityCheck`); suite `KT/unit/casinoClient.test.ts`
- [ ] T029 [US3] `K/casino/play.ts`: the play behaviour (`data-model.md`: Play; `contracts/behaviours.md`): the order visitor, funds, launch; fun mode; the territory rule by T006's result; the five-minute bet check reporting to the session; the reality check counting shown time only; the end of the game session on close, `pagehide` and sign-out; port `O/hooks/useGuardedPlay.test.ts` and the gate cases of `O/components/game/GameLaunchModal.test.tsx` into `KT/unit/play.test.ts`
- [ ] T030 [US3] Bind `features/player-site/play.feature` in `KT/features/play.steps.ts`
- [ ] T031 [US3] `KT/unit/noWayRound.test.ts` (SC-003): for every step of the deposit and play behaviours and every action name, an action not allowed sends nothing; from each `refused` step, a breadth-first walk of allowed actions reaches no `submitCashier` and no step carrying a game address without a new answer from the brand

## Phase 6: User Story 4 — composing with an appearance of one's own (P2)

**Goal**: one hook binds any behaviour; a module client is replaceable; a second appearance runs
unchanged. **Independent test**: `features/player-site/composing.feature`.

- [ ] T032 [US4] `K/index.ts`: `playerSite({transport, clock?})` giving `session`, `deposit()`, and `play`, `inbox`, `chat` only for the modules `/api/site` names (FR-047); the subpath exports resolve; suite `KT/unit/playerSite.test.ts`
- [ ] T033 [P] [US4] `K/react/useBehaviour.ts` (`useSyncExternalStore` over `subscribe` and `step`) and `K/react/PlayerSite.tsx` (context only); suite `KT/unit/react.test.tsx` with Testing Library: a component is rendered again on each step and given `allowed`; the adapter imports nothing but the kernel
- [ ] T034 [US4] Bind `features/player-site/composing.feature` in `KT/features/composing.steps.ts`: the deposit with no component; a second `core` client written in the test over the same interface (SC-009); a transport that records every call, asserting each came through a module client; a site of a brand without `casino`

## Phase 7: User Story 5 — amounts, refusals and language (P2)

- [ ] T035 [US5] Bind `features/player-site/amounts-and-refusals.feature` in `KT/features/rules.steps.ts` over `amount.ts`, `refusal.ts`, `language.ts` and the `balance` client function; the scenario on the reference site's words is bound in T046

## Phase 8: User Story 6 — the page is told when the inbox changes (P2)

- [ ] T036 [P] [US6] `K/engagement/routes.ts`, `client.ts` (`push` as a stream) and the core row `unread`; `K/engagement/inbox.ts`: the inbox behaviour (`contracts/behaviours.md`), the wait doubling to 30 s on the clock, `visible()` input, closed at sign-out; port `O/utils/unreadCount.test.ts` where its rule is kept; suite `KT/unit/inbox.test.ts`
- [ ] T037 [US6] Bind `features/player-site/inbox.feature` in `KT/features/inbox.steps.ts`; where T005 found events held back, the 30-second read of research R9 instead of the stream, behind the same behaviour, and say so in `research.md`

## Phase 9: User Story 8 — the chat with the support agent (P2)

- [ ] T038 [P] [US8] `K/support/routes.ts`, `client.ts` (`turn`, `decide` as posted streams, the refusals before the stream read by `readRefusal` with the agent's `words`); suite `KT/unit/supportClient.test.ts`
- [ ] T039 [US8] `K/support/chat.ts`: the chat behaviour (`data-model.md`: Chat; `contracts/behaviours.md`), the session id in `sessionStorage` through an injected store, removed at sign-out, a turn never sent again by itself; extracted from `O/components/chat/MotokoChat.tsx`; suite `KT/unit/chat.test.ts`, and the chat's steps added to `noWayRound.test.ts` (no `turn` reachable from `awaiting_approval`)
- [ ] T040 [US8] Bind `features/player-site/chat.feature` in `KT/features/chat.steps.ts`

## Phase 10: User Story 7 — the reference site, the module and the brand (P2)

- [ ] T041 [US7] Scaffold `S/` with `ankka init --language web` then fitted to the workspace: `package.json` (depends on `@eitheror/player-sdk`), `vite.config.ts` (content-named assets), `src/server/main.ts` (`siteServer({static: "./dist"})`), `service.json`, `Dockerfile` (built from the repository root so the workspace is in context; `USER 1000`), `README.md` (what it is, run, test)
- [ ] T042 [US7] `S/src/site.ts` (presets, idle times) and `S/src/words/fr.ts`, `en.ts`: every refusal code and every step's text, typed by `catalogue` so a missing code fails the build (SC-006); French written first, by the brand voice's rules for client copy
- [ ] T043 [US7] `S/src/pages/`: the frame and language switch; `/` the lobby from `games` (visitor and player); `/register`, `/verify-email`, `/sign-in`; the balance in the frame; the idle warning; each a component over `useBehaviour`, with the `frontend-design` skill's guidance for the appearance and phone width first
- [ ] T044 [US7] `S/src/pages/`: the deposit overlay over every step of the deposit behaviour (the provider's address opened in a new tab and offered as a link; the crypto address with its expiry); `/cashier/return` (tells the opener and closes); the deposit address rule (FR-025)
- [ ] T045 [US7] `S/src/pages/`: `/game/{code}` over the play behaviour (the game in a frame from the launch's address, the reality check over it, each refusal with its words, fun mode); the unread count in the frame over the inbox behaviour; the chat overlay over the chat behaviour (the approval request with approve and cancel and nothing else enabled)
- [ ] T046 [US7] `S/tests/features/`: `playwright-bdd` over `features/player-site/reference-site.feature` against the built site and the HTTP doubles (`ankka local web --service …`); the import rule of research R12 as `S/tests/imports.test.ts` (no `fetch`, no client import under `src/pages`); the rollout scenario by serving two builds' assets
- [ ] T047 [P] [US7] `S/tests/second-appearance/`: a second, deliberately different set of components for the deposit (a single page, no overlay), run through the same deposit scenario with no change under `sdk/` (SC-008)
- [ ] T048 [US7] `modules/player-site/module.json` per `contracts/amendments.md`; the keys `player-site-languages`, `player-site-currency` in `contract/keys.md` and the modules tool's key table; `modules/README.md` row; a manifest suite case in `tools/modules/src/test/`; edit `specs/021-core-and-modules/spec.md` with "Amended by spec 027"
- [ ] T049 [US7] `brands/guyanabet/values.json` per `contracts/amendments.md`; `tests/fixtures/manifests/guyanabet.json`; a render case in `tools/modules/src/test/.../RenderSuite.scala`; `brands/README.md`; bind the first scenario of `reference-site.feature` there; `just modules render guyanabet` passes
- [ ] T050 [US7] `.github/workflows/release.yml` and `just release-render`: the `player-site` image built from the root context and published with the others at a tag
- [ ] T051 [US7] On `dev-kind`: `just modules apply guyanabet --installation dev-kind` with the house provider (spec 026) and the payment provider stub; `KT/contract/*.test.ts`, one test per client function against the running services (FR-036), run by `just player-site-it`; then the end-to-end scenario of `reference-site.feature` in a browser against the installation, timed (SC-007); results in `research.md`

## Phase 11: Polish

- [ ] T052 `.specify/memory/constitution.md`: the *Scope* amendment of `contracts/amendments.md` and research R15, version and amendment note; check each open spec's wording against it
- [ ] T053 [P] `sdk/player/README.md`: what a site builder does, in order (run the site server, `playerSite`, `useBehaviour`, words), with the deposit as the worked example and the list of steps per behaviour generated from the sources; root `README.md` and `CLAUDE.md` rows for spec 027, the package and the site
- [ ] T054 [P] Script size of the reference site's first page measured in CI against the plan's 150 KB; `npm audit --omit=dev` clean or each finding answered in `research.md`
- [ ] T055 `quickstart.md` walked start to end on a clean checkout; every number in `plan.md`'s scale table made true; `just features` 0 findings; `just test` and `just player-sdk-test` green
- [ ] T056 [P] The brain: `brain/projects/ankka-modules.md` gains spec 027 and what follows it (the route descriptions and generated client once ankka 067 is released; the behaviours not carried), in the eitheror brain repository, as its own commit there

## Dependencies

- Phase 2 blocks every story. T007 blocks every behaviour; T011 every client; T012 and T013 the
  server scenarios of T019 and all of Phase 10.
- US1 (Phase 3) blocks US2, US3, US6, US8: each runs under the session.
- T020 before T022's `startDeposit` shape is final; T024 before T029.
- T027 before T043's visitor lobby. T004 before T048. T005 before T037. T006 before T029.
- Phase 10 needs Phases 3 to 9. T051 needs T048 to T050 and a running `dev-kind`.

## Requirements coverage

| Requirement | Tasks |
|---|---|
| FR-001, FR-004, FR-008 | T013, T019 |
| FR-002, FR-003, FR-006, FR-007 | T012, T015, T019 |
| FR-005 | T011, T012, T019 |
| FR-009 to FR-011 | T017, T018, T019, T029 |
| FR-012 to FR-017, FR-019 to FR-025 | T022, T023, T025, T026 |
| FR-018 | T020, T021 |
| FR-026, FR-027 | T024 |
| FR-028 to FR-033 | T028, T029, T030 |
| FR-034, FR-035 | T017, T022, T028, T036, T038, T034 |
| FR-036 | T051 |
| FR-037, FR-038 | T008, T035 |
| FR-039, FR-040 | T009, T035, T042 |
| FR-041, FR-042 | T010, T035, T042 |
| FR-043 | T049 |
| FR-044 | T033 |
| FR-045, FR-046 | T041 to T046 |
| FR-047 | T013, T032, T034 |
| FR-048 | T001, T052 |
| FR-049 to FR-055 | T038, T039, T040 |
| FR-056 | T027 |
| SC-001 | T046 |
| SC-002 | T016, T026, T030, T040 |
| SC-003 | T031, T039 |
| SC-004 | T015 |
| SC-005 | T008 |
| SC-006 | T042 |
| SC-007 | T051 |
| SC-008 | T047 |
| SC-009 | T034 |
| SC-010 | T055 |
