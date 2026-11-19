# Data model: generated player clients

Nothing here is stored by a service. These are files in the repository and the values the build
passes between them.

## Route description (Scala, in a service)

What `described(…)` states round one player route.

| Part | ankka 0.18.0 | Rule |
|---|---|---|
| summary | `summary(text)` | one sentence in the glossary's words |
| what it takes | `body[A]` | `A` is a case class in `api/Described.scala` with `ShapeOf`; absent for a route with no body |
| what it answers | `answers[B](200)`, `answers(204)` | every success status the route answers |
| refusals | `refuses(status, reason)` | every status the handler answers outside 400, 401, 403, 413, 500, 503, 504 of the platform; the reason is the refusal's code |
| refusal body | `refusalShape[R]` on the endpoint's `description` | the service's own refusal body, once per endpoint |
| query | `query[A](name)`, `queryRequired[A](name)` | every parameter the handler reads |
| marks | `tag("player")`, and `tag("player", "ticket")` on ingest's three | `player` selects the route into the kept description; the second mark is an audience its admission does not show |

## Service description (kept file)

`services/<service>/openapi.player.json`: OpenAPI 3.1, written by the service's build in document
mode with the tag `player`, strict, the brand `the-brand`, `info.version` `0.0.0`.

- one operation per player route; `operationId` is ankka's (`get_v1_casino_…`);
- `security: []` (a visitor), `[{"bearer": []}]` (a signed-in player);
- a stream's `200` has content `text/event-stream` and no schema;
- `components.schemas` holds only what a kept operation reaches;
- no operation is `x-ankka-incomplete`.

## Unrecorded list (kept file)

`tests/port/unrecorded-player-routes.txt`: one line per player route no recording calls,
`<service> <METHOD> <path as the description has it>`, sorted. Each service's `DescriptionSuite`
compares its own lines.

## Original route table (Scala, in a service's test)

Per service, in `port/DescriptionSuite.scala`: rows of `(method, pattern of the original's path,
the original's host, operationId)`. It is how a recorded step is matched to an operation where the
original's path differs (kyc, rewards, support-agent, ingest under the host `merchant`).

## Selection (TypeScript, by hand)

`sdk/player/src/<module>/selection.ts`, one entry per row of the module's client:

| Field | Meaning |
|---|---|
| `id` | the row's id, which is the client function's name |
| `service` | which kept description |
| `operation` | `METHOD path` as the description has it |
| `rename` | path parameters renamed to the SDK's (`playerId: "player"`, `gameCode: "gameId"`) |
| `fixed` | path parameters fixed to a value (`route: "chat_with_player"`) |
| `fixedQuery` | a query the module fixes (`status=unread&page=1&pageSize=1`) |
| `query` | the subset of the operation's query parameters a page may send; absent means none |
| `brandQuery` | the brand goes as `?brand=` |
| `serverOnly`, `visitor` | as `Route` has them today |
| `takes`, `answers` | the names its shapes get in `shapes.ts` (`Registered`, `MajorBalance`), where a row has them |

## Generated route table

`sdk/player/src/<module>/routes.ts`: the `Route` rows of spec 027 (`route.ts` is unchanged), in the
selection's order. From the description: `service`, `method`, `path` (with `the-brand` as
`{brand}`, renames and fixes applied, the fixed query appended), `audience` (R9), `stream`,
and `query` checked to be parameters the operation has.

## Generated shapes

`sdk/player/src/<module>/shapes.ts`: for each `takes` and `answers` a selection names, a Zod parser
and `export type X = z.infer<typeof X>`, with every schema it references. A status with no body has
no shape.

## Mapping (TypeScript, by hand)

`sdk/player/src/<module>/client.ts`: the interface of spec 027 and one function per row, each
`ask(transport, row(routes, id), Shape, …)` and, where the SDK's words differ from the wire's, the
conversion (`readMajor`, a terminal's names, a refusal's words). It declares no shape and no path.

## Runtime check

| Value | From |
|---|---|
| the installation's platform version | `GET /installation` `platformVersion`, else the overlay's `PLATFORM_VERSION` |
| the runtimes an apply names | every service of every module or shared manifest being applied |
| verdict | supported when the major versions are equal and the runtime's minor is the platform's or one below; not reported is unsupported |
