# Routes

Research R5, R11, R12, R14, R15. Four endpoints, no two sharing a prefix, at most two path parameters per
route, the ACL set per route group with `withAcl`. The brand is this project's, fixed in the prefix
(spec 001 FR-005). Refusals are `HttpProblem`s with a code; `INVALID_REQUEST` names the field.

## Public: `TrackEndpoint("/v1/track")`, `Routes.public`

| Route | Body | Answer |
|---|---|---|
| `POST /v1/track/click` | `btag` (required), `clickId`, `deviceType`, `country`, `ip`, `userAgent`, `utmSource`, `utmMedium`, `utmCampaign`, `utmContent`, `utmTerm`, `landingPage`, `referrer`, `brandId` (ignored) | `200 {"ok":true,"clickId":"…"}`; a refused tag `200 {"ok":false,"clickId":"…","reason":"…"}`; no `btag` `400 {"error":"btag is required"}` (the original's) |

Verbatim from the original (the path, the field names, the `ok` answer); the divergences are plan.md's.

## Public and machines: `AttributionEndpoint("/v1/casino/brands/{brand}/attribution")`

| Route | ACL | Answer |
|---|---|---|
| `GET …/keys` | `Routes.public` | the brand's key document (protocol.md) |
| `GET …/networks/{network}/feed?cursor=&limit=` | `Routes.machine()`: a machine whose grant names the route; the handler answers only the machine `Network.machine` names | a signed page; `404 not_found` for another machine or an unknown network; `400 INVALID_REQUEST` for a cursor of another network or a limit over 1,000 |

## Service: on the same endpoint, `Routes.service()` for `players`

| Route | Does |
|---|---|
| `POST …/players/{player}/erasure` | records `Erased`, emits `player-closed` when none was; answers `{"player":…,"erased":true,"personalFields":[]}` |

## Operator: `BackofficeEndpoint("/v1/casino/backoffice/{brand}/attribution")`, `Routes.operator(brand)`

| Route | Does |
|---|---|
| `GET …/settings`, `PUT …/settings` | the windows (`attributionWindowDays`, `bonusCodeWindowDays`, 1 to 365) |
| `GET …/status` | networks with reachability and last fetch, counts of bound players per network, the transition, undelivered counts, open days |
| `GET …/networks`, `POST …/networks` | list; add (`id`, `keyDocument`, `revocationList`, `legacyTagDocument?`, `codeDocument?`, `roundStream?`, `machine?`) |
| `GET …/networks/{id}`, `PUT …/networks/{id}`, `DELETE …/networks/{id}` | read, update the same fields, remove (`removed` set; nothing deleted) |
| `POST …/networks/{id}/pseudonym-key` | generates the key into the store; `409 ALREADY_EXISTS` when one exists; never returns it |
| `GET …/records/{player}` | the attribution record (binding, refused tag, touches, first deposit, closed, erased) |
| `GET …/signing-keys`, `POST …/signing-keys`, `DELETE …/signing-keys/{kid}` | list, make, retire (the last live key refused `409`) |

`…/networks/{id}/pseudonym-key` and `…/signing-keys/{kid}` are two path parameters with the brand: the
limit holds because `{id}` and `{kid}` are each the second.

## Operator: `WebhooksEndpoint("/v1/casino/backoffice/{brand}/attribution-webhooks")`, `Routes.operator(brand)`

| Route | Does |
|---|---|
| `GET …`, `POST …` | list; create (`network`, `address` https, `secret` written to the store and never read back) |
| `GET …/{id}`, `PUT …/{id}`, `DELETE …/{id}` | read (no secret), update address or `active`, delete |
| `GET …/undelivered?network=` | the undelivered facts (`Deliveries.undelivered`) |
| `POST …/undelivered/{deliveryId}/resend`, `POST …/undelivered/{deliveryId}/discard` | `Delivery.resend`, `Delivery.discard`; `409` unless undelivered |

## Not served

The original's seven `notify/*` routes and `X-Internal-Secret`; the postback; the backfill routes; any
route a network could call to change a binding, a fact or an amount (FR-024); `OPTIONS` and CORS (the
player site calls the click route through its own server, as it does the cashier: 006 R1).
