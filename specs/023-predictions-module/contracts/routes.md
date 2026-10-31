# Routes

Prefix `/v1/casino/brands/{brand}` (a brand project refuses any brand but its own). At most two
path parameters a route, no shared prefix between endpoints (ankka 0.13, kept in 0.14).

## `markets`

| Route | Class | Body | Answer | Refusals |
|---|---|---|---|---|
| `POST /markets/operator/drafts` | operator, `markets` role | the definition (spec 006), optional `maxOrderCost`, `maxPosition` | the market | `forbidden` (permission), `category`, `currency`, the library's field refusals |
| `POST /markets/operator/{market}/{command}` with `command` ∈ change, approve, open (early, before its open time), suspend, resume, close, move-close, resolve, correct, void | operator, `markets` role | the command's fields | the market | `forbidden`, `category`, `currency` (change), the library's |
| `GET /markets/operator/{market}` | operator | | the market with its record | |
| `GET /markets/catalogue` `?category=` | player | | open markets | |
| `GET /markets/catalogue/{market}` | player | | an open market | 404 for a draft or approved one |

## `exchange`

| Route | Class | Body | Answer | Refusals |
|---|---|---|---|---|
| `POST /exchange/orders/{market}` | player | `order`, `outcome`, `side`, `price`, `quantity`, `duration` | the placement's outcome | the gate's reasons; `insufficient funds`; the library's |
| `POST /exchange/orders/{market}/{order}/cancel` | player | | the cancel | `owner`, `state` |
| `GET /exchange/markets/{market}/prices`, `…/depth/{outcome}` | player | | with `sequence` | |
| `GET /exchange/markets/{market}/stream` | player | | SSE, numbered by `sequence` | |
| `GET /exchange/players/{player}/orders`, `…/positions`, `…/trades` | player (own only) | | with the `sequence` seen | `forbidden` for another player |
| `POST /exchange/markets/{market}/{command}` with `command` ∈ open, suspend, resume, close, finalise | service, `markets` only | open: outcomes, currency, unit, limits, fee rate; finalise: outcome or void | | `state` (taken as done by the driver) |
| `POST /exchange/retries/{key}/abandon` | operator, `markets` role | `reason` | the retry | |
| `POST /erasure/players/{player}` | service, `players` only | | the erasure answer | |

No split or merge route (FR-016).
