# Implementation Plan: the player SDK

**Branch**: `027-frontend-sdk` | **Date**: 2026-11-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/027-frontend-sdk/spec.md`

## Summary

One TypeScript package, `@eitheror/player-sdk` in `sdk/player/`, holds what a player site does and
nothing of how it looks: a route table and typed client per module, a site server that keeps the
player's sign-in in cookies the page cannot read and makes every call for the page, five behaviours
(session, deposit, play, inbox, chat) as small pure machines with their timers on an injected
clock, a React adapter of one hook, and one rule each for amounts, refusals and language.

`services/player-site/` is the reference site: the package's site server and a few React pages,
deployed as the web-hosted service of a new module `player-site`. `brands/guyanabet/values.json`
is the brand that runs it, in French and English, with no real money.

`players`' deposit start is amended: it takes a request id and answers the cashier ticket and the
terminals, so a page can finish a deposit it could only start. The constitution's *Scope* is
amended to put the player site in scope.

Decisions and evidence are in [research.md](research.md), R1 to R15, with five things to verify
at task time (V1 to V5).

## Technical Context

**Language/Version**: TypeScript 5.9 on Node 24 for the package and the site (constitution VI, by
FR-048's reason). Scala 3 on ankka 0.16.3 for the two amended services, `players` and `game-provider`.

**Primary Dependencies**: the package: Zod (answers parsed at the boundary), Hono (the site
server), React 19 as a peer of `./react` only. The site: Vite, TanStack Router, Tailwind 4. No
state-machine library and no translation library (R2, R8).

**Storage**: none. The site server keeps no state between instances (R3).

**Testing**: vitest; `vitest-cucumber` binding `features/player-site/` to the behaviours with no
browser; `playwright-bdd` for `reference-site.feature`; munit `GherkinSuite` for `players`;
`just player-site-it` against `dev-kind` for the client contract (R14).

**Target Platform**: a brand's own project on the installation, from `modules/player-site`,
exposed at the brand's player site address; players' browsers, phone width upward.

**Project Type**: a library, a web application built on it, one amended Scala service, the
modules' manifests, a brand's values, CI.

**Performance Goals**: a pass-through call within 30 ms of its service; first page under 150 KB
of script compressed; a behaviour's transition synchronous.

**Constraints**:

- the page calls only the site server; the site server calls only routes in the modules' tables
  (FR-001, FR-004);
- the sign-in is never in a response, a log, a trace or page storage (FR-003);
- no behaviour has an action past a refusal, and none checks a gate or a limit itself (FR-016,
  FR-030);
- amounts are `bigint` minor units from the boundary inward; no `Number` on an amount (FR-037);
- no text for a player in the package except the support agent's own (FR-042, FR-054);
- at most two path parameters per route and one endpoint per prefix in `players`;
- built files named by content, the first page uncached (FR-045).

**Scale/Scope**:

| Item | Count |
|---|---|
| Stories | 8 |
| Behaviours, steps | 5; 9 + 12 + 9 + 3 + 7 |
| Module clients, routes | 4; 27 |
| Feature files, scenarios | 8 under `features/player-site/` (90), `features/players/deposit-start.feature` (4), 1 added to `features/game-provider/catalogue.feature` |
| Services amended | 2 (`players`, one route; `game-provider`, the catalogue's audience) |
| Pages of the reference site | 6 and 2 overlays |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | divergences declared | Pass. Each behaviour names the original file it is extracted from (*Established by*), the original's unit tests are ported (R14), and the spec's *Changes from the original* holds every divergence: the deposit's path, minor units in the page, one refusal form, the request id, the cookie's `Lax` (R3). No recording is replayed by the package: recordings hold services' routes, and `players`' parity suite still passes (R11). |
| II. Money is exact | `Long` minor units; idempotent | Pass. `bigint` minor units in the page; the deposit start now carries a request id (FR-014). The package moves no money. |
| III. A brand is a project | no data across brands | Pass, as amended (R15). The site is a module of the brand's project, serves one brand (FR-008), and reaches the shared payments only at its public routes under the cashier ticket. |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. Every read is an owner's route. |
| V. Personal data marked | | Pass. The site server stores nothing. The details step writes to the player profile through `players`, which marks them. |
| VI. Scala by default | reason for anything else | Pass. TypeScript by FR-048's reason; the one stateful change is in `players`, in Scala. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1,419 scenarios and 26 specs. |
| Workflow: clarify before plan | | Pass: clarified 2026-11-18, seven answers. |
| Scope: frontends out of scope | | Amended by this spec (R15), in its own task, stating what changes and why. |

No violations; Complexity Tracking is empty.

## Component table

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-004 | `server/passThrough.ts` over the modules' `routes.ts` | one list of what may be asked, shared with the client | mounts (no place for the sign-in; R4) |
| FR-002, FR-003, FR-005 to FR-008 | `server/session.ts`, cookies | the original's, stateless | a state service (R3) |
| FR-005 (page side) | `transport.ts`: one shared refresh per page | one place every call passes | a refresh per client |
| FR-009 to FR-011 | `core/session.ts` | | |
| FR-012 to FR-025 | `core/deposit.ts`, `core/schemes.ts` (the original's table) | pure transition, enumerable (SC-003) | XState (R2) |
| FR-018 | `players`' `DepositRoutes`, `Ingest.read` | the owner of the start | the site server opening the session (the original's fault) |
| FR-056 | `game-provider`'s `CatalogEndpoint` | spec 003 foresaw it | the site server reading the catalogue under an identity of its own (none exists for a visitor) |
| FR-026, FR-027 | `core/funds.ts` | the one funds rule, with its original tests | per behaviour |
| FR-028 to FR-033 | `casino/play.ts` | | |
| FR-034 to FR-036 | `<module>/routes.ts`, `<module>/client.ts`, `tests/contract/` | the seam the generated client will fill | types alone (no allow-list) |
| FR-037, FR-038 | `amount.ts` over `currencies.v1.json` | one rule, the contract's table | `Intl` defaults; `number` |
| FR-039, FR-040 | `refusal.ts`, `codes.ts` | typed codes make SC-006 a compile error | strings |
| FR-041, FR-042 | `language.ts` | two functions | a library (R8) |
| FR-044 | `react/useBehaviour.ts` (`useSyncExternalStore`) | adds no rule | a provider tree per behaviour |
| FR-045, FR-046 | `services/player-site/` | ankka's way to serve a browser | a static bucket (no server for the sign-in) |
| FR-043, FR-047 | `brands/guyanabet/values.json`, `modules/player-site/module.json`, `server/modules.ts` | values only | a build per module set |
| FR-049 to FR-055 | `support/chat.ts`, `eventStream.ts` | | `EventSource` (cannot post; R9) |

## Project Structure

### Documentation (this feature)

```text
specs/027-frontend-sdk/
├── plan.md, research.md, data-model.md, quickstart.md
├── contracts/
│   ├── behaviours.md      # each behaviour's steps, actions and transitions
│   ├── site-server.md     # the page's contract with the site server; cookies
│   ├── module-clients.md  # the 27 routes, by module
│   └── amendments.md      # players' deposit start; the module; the constitution
└── tasks.md
```

### Source Code (repository root)

```text
package.json                      # workspaces: sdk/player, services/player-site
sdk/player/
├── package.json                  # @eitheror/player-sdk, private, subpath exports
├── src/
│   ├── behaviour.ts  clock.ts  amount.ts  refusal.ts  codes.ts  language.ts
│   ├── transport.ts  eventStream.ts  currencies.v1.json
│   ├── core/        routes.ts client.ts session.ts deposit.ts schemes.ts funds.ts
│   ├── casino/      routes.ts client.ts play.ts
│   ├── engagement/  routes.ts client.ts inbox.ts
│   ├── support/     routes.ts client.ts chat.ts
│   ├── server/      app.ts session.ts passThrough.ts modules.ts config.ts
│   ├── react/       useBehaviour.ts PlayerSite.tsx
│   └── testing/     doubles.ts clock.ts
└── tests/
    ├── unit/        # incl. the original's ported tests
    ├── features/    # vitest-cucumber steps over features/player-site/
    ├── server/
    └── contract/    # one per client function, against dev-kind
services/player-site/
├── package.json  Dockerfile  service.json  vite.config.ts
├── src/server/main.ts            # the package's server with the site's configuration
├── src/pages/  src/words/fr.ts en.ts  src/site.ts
└── tests/features/  tests/second-appearance/
modules/player-site/module.json
brands/guyanabet/values.json
services/players/…/api/DepositRoutes.scala, clients/Ingest.scala   # amended
services/game-provider/…/api/Endpoints.scala (CatalogEndpoint)      # amended
features/player-site/*.feature, features/players/deposit-start.feature
```

**Structure Decision**: a library and the site built on it, side by side, in one npm workspace;
the Scala services keep their own builds.

## Phases

1. **Setup and verification**: the workspace, the package's skeleton, V1 to V5.
2. **Foundations**: the behaviour kernel and clock, amounts, refusals, language, the transport,
   the site server's session and pass-through, the testing doubles.
3. **Session** (US1), **deposit** (US2, with `players`' amendment), **play** (US3): each its
   module client rows, its machine, its feature steps.
4. **Composing** (US4): the adapter, the module seam, the second appearance.
5. **Amounts, refusals, language** (US5) as features; **inbox** (US6); **chat** (US8).
6. **The reference site, the module and the brand** (US7); the client contract tests on
   `dev-kind`.
7. **Polish**: the constitution's amendment, CI, the release job's image, READMEs, the brain.

## Complexity Tracking

Empty.
