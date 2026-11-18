# Contract: the site server

What a page may ask of the site server of its own origin. Every answer is JSON unless said.

## Session routes

| Route | Body | Answer |
|---|---|---|
| `POST /api/session/sign-in` | `{email, password, deviceFingerprint?}` | `200 {playerId, brand, currency, country, pendingBonusCode}` and the cookies; or `players`' refusal, unchanged in status and body |
| `POST /api/session/refresh` | — | `204` and new cookies; `401 {error: "refresh_expired"}` and the cookies removed; `503 {error: "service_unavailable", service: "players"}` and the cookies kept |
| `POST /api/session/sign-out` | — | `204`, the cookies removed, whatever `players` answered |
| `GET /api/session` | — | `200 {playerId, brand, currency, country}` or `200 {signedOut: true}`; asks no service |
| `GET /api/site` | — | `200 {brand, languages, currency, modules: ["core", …]}`; never cached |

Sign-in forwards the visitor's network address and user agent to `players` (V3).

## Pass-through

`<METHOD> /api/c/<module>/<id>` with the path's values in the query (`?player=…&sessionId=…`) and
the body as the service takes it. The server:

1. finds `<id>` in `<module>`'s route table; not found, a wrong method, or a row marked server
   only: `404 {error: "not_named"}`, and no service is asked;
2. refuses a module the brand does not run: `404 {error: "module_not_run"}`;
3. fills `{brand}` from its configuration; a `brand` the page sent that is not it:
   `403 {error: "wrong_brand"}`;
4. for audience `player`: with no session cookie, `401 {error: "signed_out"}`; else adds
   `Authorization`; for `ticket`: passes the header `cashierTicket` and adds nothing;
5. calls the service and returns its status and body unchanged; a stream is piped as it arrives,
   with `Cache-Control: no-store` and no buffering;
6. the service not answering within 15 s (a stream: not beginning within 15 s):
   `503 {error: "service_unavailable", service}`.

Passed on: `traceparent`, `tracestate`. Never passed on: `Cookie`, `Authorization` from the page.

## Every request

- a method other than `GET` whose `Origin` is not `siteBase`: `403 {error: "wrong_origin"}`;
- no `GET` route changes anything;
- logged with method, route id, status and duration; never a header, a cookie or a body.

## Cookies

| Name | Holds | Attributes | Life |
|---|---|---|---|
| `__Host-eo_session` | what `players` issued for calls | `HttpOnly; Secure; SameSite=Lax; Path=/` | the issued life |
| `__Secure-eo_refresh` | what refreshes it | `HttpOnly; Secure; SameSite=Lax; Path=/api/session/refresh` | 30 days |
| `__Host-eo_player` | `{playerId, currency, country}` | `HttpOnly; Secure; SameSite=Lax; Path=/` | 30 days |
| `eo_lang` | the visitor's language | `Secure; SameSite=Lax; Path=/` | 1 year |

On a developer's machine (`ankka local web`, plain HTTP) the prefixes and `Secure` are left off.

## Static files

`/assets/*` named by content, `Cache-Control: public, max-age=31536000, immutable`; every other
`GET` answers the first page with `Cache-Control: no-cache`.

## Composing it

```ts
import { siteServer } from "@eitheror/player-sdk/server"
serve(siteServer({ static: "./dist" }))   // configuration from the environment (data-model.md)
```
