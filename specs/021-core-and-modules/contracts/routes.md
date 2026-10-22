# Contract: the operator's modules routes, and `platform.modules`

## Routes on `players` (spec 008's contract gains them)

| Route | Class | Does | Answers |
|---|---|---|---|
| `GET /v1/casino/brands/{brand}/modules` | `operator(brand)` | passes the agent's `GET /vendor/modules` through: modules with `status`, `version`, `follow` (channel or pin), each service's state, `lastChange`, `dataKeptUntil`; the open requests; the names of missing keys | 200; 503 `agent unreachable` |
| `POST /v1/casino/brands/{brand}/modules/requests` | `operator(brand)` | body `{"module", "note"?}`; forwards to the agent's `POST /vendor/requests` with the operator's subject as `by` | 204; 400 unknown module; 503 |

`players` calls the agent inside the project by its certificate (`Callers.anyInProject` on the
agent's side; asked of the vendor, research R5). Nothing else is served under `/modules`:
`ReadOnlyRoutesSuite` lists every route in `contract/access/routes.md` under that prefix and
asserts the methods (SC-003). The `platform_admin` role is admitted as any operator is and gains
no enable (FR-013).

## The page

The backoffice frontend (out of scope, constitution) renders the first route and offers the second.
Its copy says "ask eitheror for this module", never "enable".

## `platform.modules` (contract topic, family v1)

```json
{ "module": "casino", "change": "enabled", "version": "1.4.0", "channel": "stable",
  "actor": "token:…", "at": "2026-10-22T10:00:00Z" }
```

Keyed by `module`; a fact topic; written by the vendor's agent as CloudEvents whose `data` is this
family, `type` `io.eitheror.casino.platform.modules.v1`; read by `reporting` (where enabled) and by
`reasoner/audit` under the core's grant. The schema `contract/schemas/platform.modules.v1.json` is
inlined into the core's manifest at release so the agent declares the topic with it.
