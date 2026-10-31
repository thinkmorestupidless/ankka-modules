# Routes

One endpoint per audience, each under the repository's prefix for it (decided in implementation): an
operator's at `/v1/casino/backoffice/<slug>/<service>`, a player's at `/v1/casino/<service>/<slug>`,
a service's at `/v1/casino/brands/<slug>/<service>`. A brand project refuses any brand but its own. At
most two path parameters a route (ankka 0.13, kept in 0.14). A refusal is
`{"error", "message", ...named fields}`.

## `markets`

Operator: `/v1/casino/backoffice/<slug>/markets`, the operator class with the `markets` role or
`platform_admin` (`Routes.operatorHolding`); any other operator of the brand is `403 forbidden`
naming the markets permission.

| Route | Body | Answer | Refusals |
|---|---|---|---|
| `POST /drafts` | `market`, the definition (spec 006: `question`, `description`, `category`, `outcomes`, `currency`, `openTime?`, `close`, `expectedResolution`, `criteria`, `authority`), optional `maxOrderCost`, `maxPosition` | the market | `400 category`, `400 currency` (FR-013), `400 field` naming the field, the library's |
| `POST /{market}/{command}`, `command` ∈ `change`, `approve`, `open` (early), `suspend`, `resume`, `close`, `move-close`, `resolve`, `correct`, `void` | the command's fields: change the definition (and limits); suspend, close and void a `reason`; move-close a `close` and a `reason`; resolve an `outcome`, `evidence` and `authority`; correct an `outcome` and `evidence`; void `evidence` | the market | as above; `409 state`, `409 out of order`, `400 outcome`, `400 evidence`, `409 review window` |
| `GET /{market}` | | the market with its record | `404` |

Player: `/v1/casino/markets/<slug>`, the player class.

| Route | Answer | Refusals |
|---|---|---|
| `GET /catalogue` `?category=` | open markets | |
| `GET /catalogue/{market}` | an open, suspended, closed or resolved market | `404` for an unknown, draft or approved one |

## `exchange`

Player: `/v1/casino/exchange/<slug>`, the player class; a player reads and acts for themselves only
(`403 forbidden` otherwise).

| Route | Body | Answer | Refusals |
|---|---|---|---|
| `POST /orders/{market}` | `order`, `outcome`, `side`, `price`, `quantity`, `duration` | the placement's outcome | the gate's reasons; `insufficient funds`; the library's |
| `POST /orders/{market}/{order}` with `{"cancel": true}` | | the cancel | `owner`, `state` |
| `GET /markets/{market}` `?outcome=` | | prices, or an outcome's depth, with `sequence` | |
| `GET /stream/{market}` | | SSE, numbered by `sequence` | |
| `GET /players/{player}` `?read=orders\|positions\|trades` | | with the `sequence` seen and `stale` | `forbidden` for another player |

Service: `/v1/casino/brands/<slug>/exchange`, the service class.

| Route | Who | Body | Answer | Refusals |
|---|---|---|---|---|
| `POST /markets/{market}/{command}`, `command` ∈ `open`, `suspend`, `resume`, `close`, `finalise` | `markets` | open: `outcomes`, `currency`, `unit`, `maxOrderCost`, `maxPosition`, `takerFeeBps`; finalise: `outcome` or `void` | | `409 state` (taken as done by the driver) |
| `POST /erasure/players/{player}` | `players` | | the erasure answer | |

Operator: `/v1/casino/backoffice/<slug>/exchange`, as `markets`' operator class.

| Route | Body | Answer |
|---|---|---|
| `POST /retries/{key}` with `{"abandon": true, "reason"}` | | the retry |

No split or merge route (FR-016).
