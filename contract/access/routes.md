# Routes of the contract, by service

The routes a brand service serves that another repository, a frontend or a check in this one reads
by name: one row each, with the route class of spec 001 `contracts/routes.md` that admits it. A
service's own spec owns its full route list; this file holds the rows the contract is held to.

Rows are read by `ReadOnlyRoutesSuite` (spec 021 SC-003): every route whose path holds `/modules`
is a `GET` or the request `POST …/modules/requests`. No route on an installation enables, disables,
pins or moves a module; that is the vendor's act (spec 021 FR-013). A module manifest's grant may
target only a route listed here; the modules tool refuses any other.

| Service | Method | Path | Class | Spec |
|---|---|---|---|---|
| players | GET | /v1/casino/brands/{brand}/modules | operator | 021 (contracts/routes.md), 008 |
| players | POST | /v1/casino/brands/{brand}/modules/requests | operator | 021 (contracts/routes.md), 008 |
| wallet | POST | /v1/casino/brands/{brand}/wallets/{player}/{currency}/deposits | service | 001 FR-016, 002 (granted to payments/merchant) |
| wallet | POST | /v1/casino/brands/{brand}/payments/{paymentId}/outcome | service | 001 FR-016, 002 (granted to payments/merchant) |
| wallet | POST | /v1/casino/brands/{brand}/wallets/{player}/{currency}/reservations | service | 022 FR-001 |
| wallet | POST | /v1/casino/brands/{brand}/wallets/{player}/{currency}/reservations/release | service | 022 FR-002 |
| wallet | POST | /v1/casino/brands/{brand}/settlements | service | 022 FR-004 |
| wallet | POST | /v1/casino/brands/{brand}/accounts/{kind}/{name}/splits | service | 022 FR-008 |
| wallet | POST | /v1/casino/brands/{brand}/accounts/{kind}/{name}/merges | service | 022 FR-008 |
| wallet | POST | /v1/casino/brands/{brand}/accounts/{kind}/{name}/payments | service | 022 FR-005 |
| wallet | POST | /v1/casino/brands/{brand}/accounts/{kind}/{name}/moves | service | 022 FR-006, FR-013 (granted to the pool's clearing by the predictions module's manifest) |
| wallet | PUT | /v1/casino/brands/{brand}/accounts/{kind}/{name}/credit-line | service | 022 FR-007 (granted likewise) |
| wallet | GET | /v1/casino/brands/{brand}/accounts/{kind}/{name}/balance | service | 022 FR-012 |
| wallet | POST | /v1/casino/backoffice/{brand}/accounts/{kind}/{name}/adjustments | operator | 022 FR-009 |
| wallet | GET | /v1/casino/backoffice/{brand}/accounts/{kind}/{name}/balance | operator | 022 FR-012 |
| payouts | POST | /v1/casino/brands/{brand}/withdrawals/{requestId}/outcome | service | 001 FR-016, 007 (granted to payments/psp-gateway) |
| rewards | POST | /v1/casino/brands/{brand}/reward-campaigns/{player}/target | service | 011 R7 (engagement, by `Routes.serviceOf`) |
| rewards | POST | /v1/casino/brands/{brand}/reward-campaigns/{player}/award | service | 011 R7 (engagement, by `Routes.serviceOf`) |
| rewards | GET | /v1/casino/brands/{brand}/rewards/{player}/grants | player | 011 R7 (also support-agent, by `Routes.serviceOf`) |
| rewards | GET | /v1/casino/brands/{brand}/rewards/{player}/free-rounds/eligible | service | 011 R7 (game-provider, by `Routes.serviceOf`) |
| players | POST | /v1/casino/brands/{brand}/players/{player}/messages | service | 014 R6 (engagement, by `Routes.serviceOf`: the webhook's `send_message`) |
| engagement | POST | /v1/casino/brands/{brand}/engagement/sends | service | 014 R7 (players, by `Routes.serviceOf`: transactional sends) |
| engagement | POST | /v1/casino/brands/{brand}/engagement/erasure/players/{player} | service | 014 R15 (an erasure target: players reads it from `erasure-targets`) |
| exchange | POST | /v1/casino/brands/{brand}/exchange/erasure/players/{player} | service | 023 R13 (an erasure target: players reads it from `erasure-targets`) |
| reporting | POST | /v1/casino/brands/{brand}/reporting/erasure/players/{player} | service | 015 R10 (an erasure target: players reads it from `erasure-targets`) |
