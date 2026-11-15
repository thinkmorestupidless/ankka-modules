# Implementation Plan: the backoffice

**Branch**: `030-backoffice` | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/030-backoffice/spec.md`

## Summary

The backoffice is a shared project, `backoffice`, of two services. `backoffice` is a web-hosted
service: one built page (React) and the small server that serves it. `backoffice-state` is a Scala
service that keeps what must outlive an instance: each operator's sign-in and preferences.

The browser talks only to the page's server. The server holds a session id in a cookie the page's
scripts cannot read, takes the operator's token from `backoffice-state`, and calls the owning
service at ankka's calling address with that token. The owner admits or refuses the person, as it
does today. The server serves only the routes in one table, and combines sources in six places:
worklist, timeline, search, adjustment batch, document view and events.

Every operator route requires one permission, and a role is a bag of permissions, held once in the
contract (`OperatorPermissions`). A token carries roles only. Each service opens the bags
in-process; the page's server does the same and answers the operator's permissions, so the page
offers exactly what the owner will admit. Only the five casino roles have a bag.

The route check of 2026-11-15 found what the services lack for the spec: paging and totals on nine
lists, freshness on every view-backed list, sixteen read routes, and six capabilities that need new
stored data or behaviour. Each is an amendment to its owner, built here with its own tests
([contracts/amendments.md](contracts/amendments.md)).

All six phases are built in one task list, in phase order. Decisions and evidence are in
[research.md](research.md), R1 to R20, with five things to verify at task time (V1 to V5).

## Technical Context

**Language/Version**: TypeScript 5 on Node 24 for `backoffice` (constitution VI: it speaks HTTP to
the browser and to services, and holds no money path and no state). Scala 3.9.0 on ankka **0.16.3**
for `backoffice-state` and every amended service; services pinned below 0.16.3 that this spec
amends (`merchant`, `psp-gateway`, `affiliate-programme` on 0.13.0; `engagement`, `reporting` on
0.16.0; `support-agent` on 0.16.2) are amended at their own pin, not moved.

**Primary Dependencies**:

- `backoffice`: the `ankka init --language web` template (React 19, Vite); Hono; TanStack Router
  and Query; Tailwind 4; Radix primitives; Recharts; `d3-geo`; `intl-messageformat`; Zod (R7);
- `backoffice-state`: `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-auth-oidc`,
  `ankka-testkit`, all 0.16.3; `io.eitheror:casino-contract`.

**Storage**: `backoffice-state`'s own Postgres (sessions, preferences). `backoffice` has none.

**Testing**: vitest and Testing Library; `playwright-bdd` over `features/backoffice/` against
scripted service doubles; `@axe-core/playwright`; munit and `AnkkaTestKit` for the Scala services,
with each amended service's `GherkinSuite`; `just backoffice-it` against a seeded brand on
`dev-kind` (R18).

**Target Platform**: the shared project `backoffice` on the self-hosted installation (kind
locally, GKE), exposed at its own hostname; operators' desktop browsers, 1280 px and wider.

**Project Type**: a web application (one web-hosted service, one Scala service), amendments to
the contract library, the modules tool, the realm, CI, and seventeen services.

**Performance Goals**: research R20: worklist 2 s p95; a player's standing 1.5 s p95; search 2 s
p95; a proxied read within 30 ms of its owner.

**Constraints**:

- the browser calls only the backoffice; the backoffice calls only routes in its table (FR-002, R4);
- the operator's token is never in a response, a log, a trace or the page (FR-003);
- no grant from a brand to `backoffice`, and no rule of an owner's repeated (constitution III, FR-007);
- amounts are minor units end to end; the currency table is the contract's (FR-013, R6);
- every string from the catalogue (FR-040a); every screen AA (FR-040);
- at most two path parameters per route and one endpoint per prefix in the Scala services;
- built files named by content, the index uncached (FR-042);
- a call's bound is 60 seconds at the proxy; the server's own bounds are 3 s per source in a
  composite route and 15 s for a pass-through.

**Scale/Scope**:

| Item | Count |
|---|---|
| Stories, phases | 11, 6 |
| Screens | about 60, in 14 areas |
| Operator routes in the table | about 300, over 17 services |
| Composite routes | 12 |
| Services amended | 17, plus the contract, the modules tool, the realm, CI |
| New stored data in owners | 5: manager tags, seen devices, deposit bounds, an adjustment's cause, balances at a request |
| Entities in `backoffice-state` | 2 (`Session`, `Preferences`), 1 view |
| Feature files | 12 under `features/backoffice/` (41 scenarios); 17 new files and 8 added scenarios in the owners' features (the spec's *Amendments to the owning services*) |
| Seed for SC-005 | 10,000 players, 1,000 rewards, 5,000 withdrawal requests |

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 below.*

| Principle | Gate | Status |
|---|---|---|
| I. The original is the specification | parity named; divergences declared | Pass. [parity.md](parity.md) holds every capability of the original with its owner and status, confirmed against the code; every change is under the spec's *Changes from the original*. The risk blocklist is dropped as spec 004 FR-036 already dropped it. No recording is replayed (R19): the owners' recordings hold their routes, and the original's screens are recorded as the *before*. |
| II. Money is exact, the wallet is the ledger | `Long`; idempotent | Pass. The backoffice holds no balance. An adjustment batch is one wallet adjustment per row under its own key (R15). Amounts cross the page as minor units. |
| III. A brand is a project | no player data; no grant | Pass, as amended (1.3.0). `backoffice-state` refuses a preference that names a player. Calls carry the operator's own token; no grant is made (R3, V1). |
| IV. Commands over HTTP, facts over topics | no store read across services | Pass. Every read is an owner's route; the timeline reads owners, not topics (R10). |
| V. Personal data marked, erasure honoured | `Personal`; handler | Pass. An operator's subject, name and email are `Personal` under `operator/<subject>` with an erasure route. Players' devices (P8) are `Personal` under the player's subject in `players`. |
| VI. Scala by default | reason for anything else | Pass. TypeScript for the web-hosted program only, for the reason the spec and amendment 1.3.0 give; everything that keeps state is Scala. |
| VII. Specs in the domain's words | `just features` passes | Pass: 0 findings in 1,456 scenarios and 26 specs. |
| Workflow: clarify before plan | | Pass: clarified 2026-11-15, nine answers. |
| Scope: ankka gaps are ankka specs | | Pass. V1 is the one platform assumption; if it fails it is an ankka request and the build stops. |

No violations; Complexity Tracking is empty.

## Component table

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-001, FR-042 | `backoffice`, web-hosted; Vite's content-named assets | ankka's way to serve a browser | a service with components (it has no state) |
| FR-002, FR-007 | the route table and pass-through (`server/proxy.ts`, `routes.ts`) | one list of what may be asked | a handler per route (R4) |
| FR-003 | `Session` (ES) in `backoffice-state`; the `__Host-` cookie | one writer for refresh; survives a rollout | a sealed cookie (R1) |
| FR-004, FR-005, FR-008 | `OperatorPermissions` in the contract (permissions, and roles as bags of them), published; `Routes.operatorMay(slug, permission)`; `/api/me`'s permissions; `access.ts` | one table for screen and service; no role named in a route | permissions in the token (bloat; R5); a copy in the page (the original's drift) |
| FR-006 | the refusal map in the catalogue (`refusals.ts`) | the owner's code, the domain's words | prose from the owner (untranslatable) |
| FR-009 to FR-012 | owners' paging (L); `status` routes (F); Query's kept rows and list state in the URL | the owner counts; the page keeps place | client paging (the original's loss of rows) |
| FR-013 to FR-015 | `money.ts` over `currencies.v1.json`; `dates.ts` over the brand's zone | one rule | `Intl` defaults (R6) |
| FR-016 to FR-018 | `composite/worklist.ts` | parallel, bounded, per-kind state | a stored worklist (never stored) |
| FR-019 | `composite/search.ts` and its classifier | only shaped ids are sent as ids | one search route in one owner (it would read others' stores) |
| FR-020 to FR-024 | `composite/standing.ts`, `composite/timeline.ts` | gaps stay visible per source | a wider consumer in `players` (R10) |
| FR-025, FR-026 | `actions/` (one description per action: effect, reason, states) | effect before confirmation, from one place | per-screen dialogs |
| FR-027, FR-028 | `composite/batch.ts`; wallet W2 | one keyed adjustment per row | a bulk route in the wallet (a new money command) |
| FR-029 | owners' jobs; `jobs` in preferences; `/api/events` | no job state of its own | a job service |
| FR-031 | `composite/document.ts` | the document id is the only input | a signed URL (an address the page is given) |
| FR-038 | `Preferences` (KV) | per operator, small | browser storage (not kept across devices) |
| FR-039 to FR-041 | `tokens.css`, the frame ([contracts/design.md](contracts/design.md)) | checked values | — |
| FR-040a | `messages/en.ts`, typed keys, `intl-messageformat` | a translation changes no screen | strings in place |

## Project Structure

### Documentation (this feature)

```text
specs/030-backoffice/
├── plan.md              # this file
├── research.md          # how the original is used; R1–R20, V1–V5
├── parity.md            # every capability, its owner and status
├── data-model.md        # what is stored, and the shapes the server makes
├── quickstart.md        # proving it, in build order
├── contracts/
│   ├── roles.md         # the matrix, its published form, the cutover mapping
│   ├── sources.md       # worklist, timeline and search sources
│   ├── amendments.md    # every change to an owning service, the contract, the tool, CI
│   ├── routes.md        # the backoffice's own routes
│   └── design.md        # tokens and the frame
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
contract/src/main/scala/io/eitheror/casino/contract/access/OperatorPermissions.scala, Routes.scala
contract/src/main/scala/io/eitheror/casino/contract/Layout.scala, facts/
contract/schemas/access/operator-permissions.v1.json, money/currencies.v1.json
brands/_realm/eitheror-operators.json
tools/modules/src/main/scala/io/eitheror/casino/modules/   # Manifest, Render, Realm: web hosting, client upsert
shared/backoffice/manifest.json
services/<each of the 17>/                                 # contracts/amendments.md
services/backoffice-state/
├── build.sbt, service.json, README.md
└── src/{main,test}/scala/io/eitheror/casino/backoffice/
    ├── session/         # Session, Realm client, SessionsBySubject, SessionEndpoint
    ├── preferences/     # Preferences, PreferencesEndpoint
    └── Erasure.scala, Settings.scala, BackofficeStateService.scala
services/backoffice/
├── package.json, tsconfig.json, vite.config.ts, service.json, Dockerfile, README.md
├── src/server/
│   ├── main.ts, session.ts, auth.ts, proxy.ts, routes.ts, events.ts
│   ├── composite/       # worklist, search, standing, timeline, batch, document, freshness, jobs
│   └── owners/          # one typed client per owning service, made from routes.ts
├── src/shared/          # access.ts, money.ts, dates.ts, messages/en.ts, refusals.ts, generated/
├── src/web/
│   ├── tokens.css, frame/, components/ (table, list state, money, action dialog, freshness)
│   ├── actions/         # one description per action
│   └── areas/           # worklist, search, players, withdrawals, corrections, rewards, identity,
│                        # protection, fraud, dashboard, reports, configuration, payments, team, affiliates
└── tests/
    ├── unit/, doubles/ (one per owner), features/ (steps), a11y/, it/ (seed, role sweep, SC-005–SC-007)
tests/original/backoffice-screens/                         # the recording script and index
features/backoffice/                                       # the scenarios (written)
.github/workflows/ci.yml, release.yml, Justfile
```

**Structure Decision**: two service directories under `services/`, as every service is; the web
one split into `server`, `shared` and `web` so that the matrix, money and messages are one copy
for both sides.

## Phase 0: research

Done: [research.md](research.md). No unknown remains open; V1 to V5 are verified by the first tasks
that depend on them, each with what happens if it does not hold.

## Phase 1: design

[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

### Constitution re-check after design

Unchanged. The only stored data is the operator's own; the owners gain routes and five pieces of
stored data inside their own projects; nothing reads another service's store.

## Divergences

Every entry under the spec's *Changes from the original* is shown by a scenario in
`features/backoffice/` or a quickstart §6 check. The amended services' changes are shown in their
own suites.

## What the next command needs to know

**Build order**:

1. Setup: the spike for V1; the two service skeletons; the Justfile and CI entries.
2. Foundation: the contract (matrix, published files, layout, facts); the realm; the modules
   tool; `backoffice-state`; the server (session, proxy, route table); the frame, tokens, money,
   dates, catalogue, list and action components; the doubles and the feature harness.
3. Phase 1 (stories 1 to 3): the matrix in every service (M), freshness (F) and lists (L) in the
   services phase 1 reads; access; worklist; search; the player page and timeline.
4. Phase 2 (stories 4, 5): payouts and wallet amendments; withdrawals; adjustments and batches.
5. Phase 3 (stories 6, 7): rewards, kyc, responsible-gambling and players' fraud amendments;
   rewards and offers; identity, protection and fraud.
6. Phase 4 (story 8): dashboard and reports.
7. Phase 5 (story 9): merchant, psp-gateway, wallet-config and players amendments; configuration,
   payments administration, team.
8. Phase 6 (stories 10, 11): the programme's amendments; affiliates; outreach.
9. Polish: the role sweep, the seed and SC-005 to SC-008, the migration mapping, the recordings of
   the original, the module and manifest, documentation.

**Do not**:

- make a grant from a brand to `backoffice`, or admit `Callers.service("backoffice")` on a brand's
  operator route: the operator's token is the admission;
- put a rule in the page or its server that the owner does not hold; a missing route is an
  amendment in contracts/amendments.md;
- name a role in a service or in the page: a route requires a permission, and roles appear only
  in `OperatorPermissions`' bags;
- parse or format an amount outside `money.ts`, or write an operator-facing string outside the
  catalogue;
- run two sbt processes in one service directory, or use another worktree's contract snapshot
  (use a unique `CASINO_VERSION`);
- run `kubectl` without `--context kind-…`.
