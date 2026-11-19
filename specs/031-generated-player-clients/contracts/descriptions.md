# Contract: service descriptions

## The player routes

| Service | Endpoint (file under `services/<service>/src/main/scala/…/api/`) | Admission | Player routes |
|---|---|---|---|
| players | `Endpoints.scala` `PlayerEndpoint` | 10 public, 26 player | 36 |
| players | `Endpoints.scala` `SessionEndpoint` | player | 3 |
| players | `Endpoints.scala` `GeoEndpoint` | public | 1 |
| wallet | `AudienceEndpoints.scala` `PlayerEndpoint` | player | 3 |
| ingest | `Endpoints.scala` `CashierEndpoint`: submit, abandon | public, by ticket | 2 |
| ingest | `Endpoints.scala` `PaymentsEndpoint`: the payment read | public, by ticket or key | 1 |
| game-provider | `Endpoints.scala` `SessionsEndpoint`: launch | player | 1 |
| game-provider | `Endpoints.scala` `CatalogEndpoint` | public | 4 |
| kyc | `PlayerEndpoint.scala` | player | 4 |
| kyc | `PublicEndpoint.scala` | public | 1 |
| responsible-gambling | `Endpoints.scala` `PlayerEndpoint` | player | 10 |
| rewards | `RewardsEndpoint.scala`, less `/free-rounds/eligible` | player (grants: player or the brand's support agent) | 10 |
| engagement | `StreamEndpoint.scala` | the stream's own rule | 1 |
| support-agent | `ChatEndpoint.scala` | player | 2 |

79 routes. Not player routes, and never tagged: ingest's `POST /v1/cashier/sessions` (called by
`players` under the cashier key); every `Routes.operator`, `Routes.service` and provider route.

## Describing a route

```scala
described(
  _.summary("A player's balance in one currency")
    .answers[MajorBalance](200)
    .refuses(409, "player_id_mismatch")
    .tag("player")
) {
  get("/{currency}/balance") { (currency: String) => … }   // the handler is not touched
}
```

- The shapes are case classes in `api/Described.scala`, or the service's own where it has one.
  A field the handler omits when empty is an `Option` or a collection; a field it always writes is
  required. Field names are the wire's.
- An endpoint whose routes share a refusal body overrides `description` with `refusalShape[R]`.
- A refusal's `reason` is its code (`error` on the wire), so the document lists the codes a client
  can meet.
- A stream is described with its summary, its refusals before the stream opens, and the tag.
- ingest's three carry `tag("player", "ticket")`.

## Writing and checking

| Recipe | Does |
|---|---|
| `just describe <service>` | `sbt contract/publishLocal`, then in `services/<service>/`, with `describe.env` exported: `JAVA_TOOL_OPTIONS="-Dankka.document=openapi -Dankka.document.out=$PWD/openapi.player.json -Dankka.document.tags=player -Dankka.document.strict=true -Dankka.service.name=<service>" sbt run` |
| `just describe-check <service>` | the same to a temporary file, then `cmp` with the kept one; on a difference prints `diff -u` and exits 1 |
| `just descriptions` | `describe` for all nine, then `npm run generate -w sdk/player` |

`describe.env` holds every variable the service's settings demand, as placeholders, with
`CASINO_BRAND=the-brand` (or the service's own name for it). It holds no secret.

CI: each of the nine services' jobs gains a step that runs the check's command (no `just`, as the
other steps). A route whose answer, refusals or parameters changed without `just describe` fails
that job with the diff (FR-011).

## Holding the tests

Each service's one kit wraps its server: `HeldToDocument.server(server, violations)`. When the kit
closes it fails, listing each, on every violation whose method and path are an operation of
`openapi.player.json` (read from the service's directory). `the-brand` in a kept path matches the
kit's own brand.

A violation that shows the handler answering something the description does not allow is fixed in
the description when the handler is right (the recordings and the service's features decide), and
is a finding, reported and not fixed here, when the handler is wrong.
