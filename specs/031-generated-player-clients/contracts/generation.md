# Contract: generation

## Commands

| Command | Does |
|---|---|
| `npm run generate -w sdk/player` | writes `src/<module>/routes.ts` and `src/<module>/shapes.ts` for core, casino, engagement, support |
| `npm run generate:check -w sdk/player` | generates in memory; exits 1 naming each file that differs: `src/core/routes.ts is out of date: run npm run generate -w sdk/player` |
| `just player-sdk-generate` | the first |
| `just player-sdk-test` | gains the check, first |

CI's `player-sdk` job runs the check before typecheck. Inputs: `services/*/openapi.player.json`
and `src/<module>/selection.ts`. Nothing else is read; nothing runs (FR-023).

## A selection

```ts
export const selection = [
  { id: "profile", service: "players", operation: "GET /v1/casino/players/the-brand/{playerId}",
    rename: { playerId: "player" }, answers: "Profile" },
  { id: "unread", service: "players", operation: "GET /v1/casino/players/the-brand/{playerId}/messages",
    rename: { playerId: "player" }, fixedQuery: "status=unread&page=1&pageSize=1", answers: "Unread" },
  { id: "signIn", service: "players", operation: "POST /v1/casino/players/the-brand/login",
    serverOnly: true, visitor: true, takes: "SignIn", answers: "SignedIn" },
] as const satisfies Selection;
```

The four selections hold the 27 rows of spec 027 FR-035, in the order the tables have today.

## Rules

| Rule | Failure |
|---|---|
| a selection's operation is in its service's kept description | `core/selection.ts: profile names GET … which services/players/openapi.player.json does not hold` |
| `rename` and `fixed` name path parameters the operation has | names the row and the parameter |
| `query` and `fixedQuery` name query parameters the operation has | names the row and the parameter |
| two rows of a module do not share an id | names both |
| a schema uses only the subset of R10 | names the schema and the keyword |
| a row with `answers` has a JSON `200` (or `201`) answer; a stream has none | names the row |

## What a row gets from the description

- `method`, `path`: the operation's, with the segment `the-brand` as `{brand}`, renames and fixes
  applied, `?` and the fixed query appended.
- `audience`: `ticket` if the operation is tagged `ticket`; else `player` if it is tagged
  `signed-in`; else `public` for `security: []`; else `player`.
- `stream: true` where the `200` content is `text/event-stream`.
- `brandQuery`, `serverOnly`, `visitor`: the selection's.

A generated table equals the hand-written one it replaces, row for row, except that nothing is
compared by a path parameter's own name inside the service. `tests/unit/generate.test.ts` holds
the hand-written tables of spec 027 as its fixture and asserts that equality (story 4's first
scenario), so the first generated output is proved against what it replaces.

## Shapes

`shapes.ts` exports, per `takes` and `answers`, `export const X = z.object({ … })` and `export
type X = z.infer<typeof X>`. Optional in the schema is `.optional()`; a nullable type is
`.nullable()`; objects pass unknown keys. Referenced schemas are emitted once, before their first
use, in a stable order (by name).

## The mapping

`client.ts` keeps `CoreClient`, `CasinoClient`, `EngagementClient`, `SupportClient` exactly as
spec 027 has them. Its functions call `ask` with a generated shape and convert where the SDK's
words differ. A stream's events are parsed by `events.ts`.

eslint, for `src/{core,casino,engagement,support}/`: `zod` may be imported only by `shapes.ts` and
`events.ts`; a string literal beginning `/v1/` may appear only in `routes.ts` and `selection.ts`.

## The site server

Its allow-list is the tables, as before (spec 027 FR-004); it imports the same `routes.ts`. A
player route no selection names is in no table, and a page's call of it is refused as any unknown
row is.
