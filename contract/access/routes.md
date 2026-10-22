# Routes of the contract, by service

The routes a brand service serves that another repository, a frontend or a check in this one reads
by name: one row each, with the route class of spec 001 `contracts/routes.md` that admits it. A
service's own spec owns its full route list; this file holds the rows the contract is held to.

Rows are read by `ReadOnlyRoutesSuite` (spec 021 SC-003): every route whose path holds `/modules`
is a `GET` or the request `POST …/modules/requests`. No route on an installation enables, disables,
pins or moves a module; that is the vendor's act (spec 021 FR-013).

| Service | Method | Path | Class | Spec |
|---|---|---|---|---|
| players | GET | /v1/casino/brands/{brand}/modules | operator | 021 (contracts/routes.md), 008 |
| players | POST | /v1/casino/brands/{brand}/modules/requests | operator | 021 (contracts/routes.md), 008 |
