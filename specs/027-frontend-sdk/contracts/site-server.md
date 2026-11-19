# Contract: the site server

What a page may ask of the site server of its own origin. Every answer is JSON unless said.

## Session routes

| Route | Body | Answer |
|---|---|---|
| `POST /api/session/sign-in` | `{email, password, deviceFingerprint?}` | `200 {playerId, brand, currency, country}` and the cookies; or `players`' refusal, unchanged in status and body |
| `POST /api/session/refresh` | — | `204` and new cookies; `401 {error: "refresh_expired"}` and the cookies removed; `503 {error: "service_unavailable", service: "players"}` and the cookies kept |
| `POST /api/session/sign-out` | — | `204`, the cookies removed, whatever `players` answered |
| `GET /api/session` | — | `200 {playerId, brand, currency, country}` or `200 {signedOut: true}`; asks no service |
| `GET /api/site` | — | `200 {brand, languages, currency, modules: ["core", …]}`; never cached |

The visitor's network address and user agent are forwarded to `players` on `signIn`, `register`
and `country`, as `X-Forwarded-For` and `X-Real-IP` (the last entry of the `X-Forwarded-For` the
gateway wrote) and `User-Agent` (V3: the calling address passes them to `players` as sent). Sign-out with no session cookie refreshes first, so that
`players` can still be asked to end the player session.

## Pass-through

`<METHOD> /api/c/<module>/<id>`, the method being the row's own, with the path's values in the
query (`?player=…&sessionId=…`) and the body as the service takes it. A cashier ticket is never a
query value. The server:

1. finds `<id>` in `<module>`'s route table; not found, a wrong method, or a row marked server
   only: `404 {error: "not_named"}`, and no service is asked;
2. refuses a module the brand does not run: `404 {error: "module_not_run"}`;
3. fills `{brand}` from its configuration; a `brand` the page sent that is not it:
   `403 {error: "wrong_brand"}`;
4. for audience `player`: with no session cookie, `401 {error: "signed_out"}`; else adds
   `Authorization`, and a `401` of the service is answered `401 {error: "signed_out"}` so that
   the page refreshes; for `ticket`: the session cookie is required as for `player`, `{ticket}` is
   filled from the header `cashierTicket`, which is passed on, no `Authorization` is added, and a
   `401` of the cashier is returned as it is;
5. calls the service and returns its status and body unchanged; a stream is piped as it arrives,
   with `Cache-Control: no-store` and no buffering;
6. the service not answering within 15 s (a stream: not beginning within 15 s):
   `503 {error: "service_unavailable", service}`.

Passed on: `traceparent`, `tracestate`. Never passed on: `Cookie`, `Authorization` from the page.

## Every request

- a method other than `GET` whose `Origin` is not `siteBase`: `403 {error: "wrong_origin"}`;
- no `GET` route changes anything;
- logged with method, route id, status and duration; never a header, a cookie, a query or a body;
- the server writes no span of its own; it passes `traceparent` on.

## Cookies

| Name | Holds | Attributes | Life |
|---|---|---|---|
| `__Host-eo_session` | what `players` issued for calls | `HttpOnly; Secure; SameSite=Lax; Path=/` | until the `exp` it carries |
| `__Secure-eo_refresh` | what refreshes it | `HttpOnly; Secure; SameSite=Lax; Path=/api/session` | 30 days |
| `__Host-eo_player` | `{playerId, currency, country}` | `HttpOnly; Secure; SameSite=Lax; Path=/` | 30 days |
| `eo_lang` | the visitor's language | `Secure; SameSite=Lax; Path=/` | 1 year |

When the configuration's `local` is set (`PLAYER_SITE_LOCAL`, never a request's header: `ankka
local web`, plain HTTP) the prefixes and `Secure` are left off.

## The deposit address

Any page address with `deposit=true` in its query, as the original's `/?deposit=true`; a brand's
game provider configuration names it as its deposit address.

## Static files

`/assets/*` named by content, `Cache-Control: public, max-age=31536000, immutable`; every other
`GET` answers the first page with `Cache-Control: no-cache`.

## Composing it

```ts
import { siteServer } from "@eitheror/player-sdk/server"
serve(siteServer({ static: "./dist" }))   // configuration from the environment (data-model.md)
```
