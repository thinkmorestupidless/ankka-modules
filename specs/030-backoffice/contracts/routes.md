# The backoffice's own routes

What `backoffice` (the page's server) serves the browser, and what `backoffice-state` serves it.
The browser calls nothing else (FR-002).

## `backoffice`: the page's server

Every `/api` route needs the session cookie; without one it answers `401 {"error":"signed_out"}`
and the page goes to `/auth/sign-in`. Every route that is not `GET` needs the header
`X-Backoffice: 1` and an `Origin` equal to the address the proxy states (`X-Forwarded-Host`); else
`403 {"error":"bad_origin"}`.

### Sign-in

| Route | Does |
|---|---|
| `GET /auth/sign-in?return=` | starts the code flow with PKCE at the `eitheror` realm; `return` must be a path of this site |
| `GET /auth/callback` | asks `backoffice-state` to exchange the code; sets `__Host-bo` (`HttpOnly`, `Secure`, `SameSite=Lax`, the session's life); redirects to `return` |
| `POST /auth/sign-out` | ends the session at `backoffice-state` and at the realm; clears the cookie |
| `GET /api/me` | `{subject, name, roles, brands: [{slug, name, modules}], lastBrand, preferences, actions: {<brand>: [action]}}`; the token itself is never answered |

### Pass-through, by the route table (research R4)

`<METHOD> /api/s/<service>/<scope>/<rest>` is served only when `routes.ts` holds a row for that
method and path template. `<scope>` is a brand's slug (the call goes to `<service>.<slug>`), or
`payments`, `programme`, `reasoner`, `games` (the shared project of that name). The server:

1. refuses a row not in the table: `404 {"error":"no_such_route"}`;
2. refuses a brand the operator's roles do not name: `403 {"error":"brand_not_in_roles"}`, without
   calling (the owner would refuse it; this saves the call and adds no rule);
3. takes a token from `backoffice-state`, calls the owner, and answers what the owner answered:
   status, body and `Content-Disposition` as given. A proxy's own answer (`X-Ankka-Answered-By`)
   becomes `503 {"error":"source_unavailable","source":"<service>"}`.

Refusals reach the page as the owner gave them; the page maps the code to a catalogue message and
its next step, and shows an unknown code as given (FR-006).

### Composite routes

| Route | Answers |
|---|---|
| `GET /api/b/{brand}/worklist?kinds=` | `{kinds: [{kind, state: ok or unavailable, reason?, total, items: [{id, subject, since, summary}]}]}` ([sources.md](sources.md)) |
| `GET /api/b/{brand}/worklist/{kind}?page=` | one kind's page, oldest first |
| `GET /api/b/{brand}/search?q=` | `{matches: [{kind, id, player?, summary}], asked: [kind], unavailable: [source]}` |
| `GET /api/b/{brand}/players/{p}/standing` | the player page's top: profile, account status, identity status, limits and breaks, balances per currency, lifetime figures, pinned notes, flags, presence; `{parts: {<part>: {state, data or reason}}}` |
| `GET /api/b/{brand}/players/{p}/timeline?from&to&kinds&after` | `{lines, next, gaps}` ([sources.md](sources.md)) |
| `POST /api/b/{brand}/players/{p}/timeline/export` | starts the owner's export job; answers its reference |
| `POST /api/b/{brand}/adjustment-batches` | body `{reason, category, cause?, rows: [{player, currency, walletType, direction, amountMinor}]}`; answers a stream of `{row, outcome: made or refused or already_made, reason?, before?, after?}` and a last `{done, made, refused, alreadyMade}` (research R15) |
| `GET /api/b/{brand}/kyc/{p}/documents/{d}/view` | streams kyc's `views/{d}` for this operator; the document id is the only input (FR-031) |
| `GET /api/b/{brand}/freshness?services=` | each named service's `status` |
| `GET /api/events?brand=` | server-sent events: `player_online` (when the operator asked for notices), `job_done`, `roles_changed`, `signed_out` |
| `GET /api/jobs`, `POST /api/jobs` | the operator's started jobs (reference, kind, state), kept in preferences |
| `GET /healthz` | `200` when the process serves |

### Static

`/assets/*` are named by content and served `Cache-Control: public, max-age=31536000, immutable`;
`/` and every other path serve `index.html` with `Cache-Control: no-cache` (FR-042).

## `backoffice-state` (Scala), admitted only to `Callers.service("backoffice")`

| Route | Does |
|---|---|
| `POST /sessions` | body `{code, verifier, redirectUri}`; exchanges at the realm; answers `{session, subject, expiresAt}` |
| `POST /sessions/{id}/token` | `{accessToken, expiresAt, roles}`, refreshed when under 30 seconds remain; `404` when the session ended |
| `DELETE /sessions/{id}` | ends it and revokes the refresh token at the realm |
| `GET /preferences/{subject}`, `PUT /preferences/{subject}` | `{theme, lastBrand, worklistKinds, onlineNotices, jobs}` |
| `DELETE /subjects/{subject}` | erasure of an operator's sessions and preferences |

## The page's routes (for reference; all one built page)

`/` the worklist · `/search` · `/players`, `/players/{p}` (standing, timeline, wallets, rewards,
identity, protection, sessions, devices, games, messages) · `/withdrawals`, `/withdrawals/{id}` ·
`/adjustments/batch` · `/rewards`, `/offers` · `/identity` · `/protection` · `/fraud` ·
`/dashboard` · `/reports/{report}` · `/configuration/{area}` · `/payments/{area}` · `/team` ·
`/affiliates/{area}` · `/jobs` · `/preferences`. The brand is `?b=<slug>` on every one, so a link
opens in the brand it was made in.
