# The backoffice's own routes

What a brand's `backoffice` (the page's server) serves the browser, and what `backoffice-state`
serves it. Both are services of the brand's own project; the brand is the project's, so no route
names one. The browser calls nothing else (FR-002).

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
| `GET /api/permissions` | `{roles: {<role>: [permission]}}`: the brand's roles as `players` holds them now, so the page can say which role would give a permission the operator lacks |
| `GET /api/me` | `{subject, name, roles, brand: {slug, name, modules}, preferences, permissions: [permission]}`, the permissions opened by the server from the operator's roles with the brand's bags (contracts/roles.md), read again at most every 10 seconds; `403 {"error":"brand_not_in_roles"}` for an operator whose roles do not name the brand; the token itself is never answered |

### Pass-through, by the route table (research R4)

`<METHOD> /api/s/<service>/<rest>` is served only when `routes.ts` holds a row for that method and
path template. A row names its service and where it is: the brand's own project (the call goes to
`$ANKKA_SERVICES_URL/<service>/…`) or a shared project, `payments`, `programme`, `reasoner` or
`games` (the call goes to that project's address from the brand's values, by default
`$ANKKA_SERVICES_URL/<service>.<project>/…`). The server:

1. refuses a row not in the table: `404 {"error":"no_such_route"}`;
2. puts the brand's own slug wherever the template has `{brand}`; the browser never names one;
3. takes a token from `backoffice-state`, calls the owner, and answers what the owner answered:
   status, body and `Content-Disposition` as given. A proxy's own answer (`X-Ankka-Answered-By`)
   becomes `503 {"error":"source_unavailable","source":"<service>"}`.

Refusals reach the page as the owner gave them; the page maps the code to a catalogue message and
its next step, and shows an unknown code as given (FR-006).

### Composite routes

| Route | Answers |
|---|---|
| `GET /api/worklist?kinds=` | `{kinds: [{kind, state: ok or unavailable, reason?, total, items: [{id, subject, since, summary}]}]}` ([sources.md](sources.md)) |
| `GET /api/worklist/{kind}?page=` | one kind's page, oldest first |
| `GET /api/search?q=` | `{matches: [{kind, id, player?, summary}], asked: [kind], unavailable: [source]}` |
| `GET /api/players/{p}/standing` | the player page's top: profile, account status, identity status, limits and breaks, balances per currency, lifetime figures, pinned notes, flags, presence; `{parts: {<part>: {state, data or reason}}}` |
| `GET /api/players/{p}/timeline?from&to&kinds&after` | `{lines, next, gaps}` ([sources.md](sources.md)) |
| `POST /api/players/{p}/timeline/export` | starts the owner's export job; answers its reference |
| `POST /api/adjustment-batches` | body `{reason, category, cause?, rows: [{player, currency, walletType, direction, amountMinor}]}`; answers a stream of `{row, outcome: made or refused or already_made, reason?, before?, after?}` and a last `{done, made, refused, alreadyMade}` (research R15) |
| `GET /api/kyc/{p}/documents/{d}/view` | streams kyc's `views/{d}` for this operator; the document id is the only input (FR-031) |
| `GET /api/freshness?services=` | each named service's `status` |
| `GET /api/events` | server-sent events: `player_online` (when the operator asked for notices), `job_done`, `permissions_changed` (the operator's roles, or what they carry, changed), `signed_out` |
| `PUT /api/preferences` | the operator's own `{theme?, worklistKinds?, onlineNotices?}`, merged over what `backoffice-state` keeps for their subject; answers all four; `400 {"error":"unknown_preference"}` for `jobs` (written only by `/api/jobs`) or any other key, and `backoffice-state`'s own `400` as it gave it |
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
| `GET /preferences/{subject}`, `PUT /preferences/{subject}` | `{theme, worklistKinds, onlineNotices, jobs}` |
| `DELETE /subjects/{subject}` | erasure of an operator's sessions and preferences |

## The page's routes (for reference; all one built page)

`/` the worklist · `/search` · `/players`, `/players/{p}` (standing, timeline, wallets, rewards,
identity, protection, sessions, devices, games, messages) · `/withdrawals`, `/withdrawals/{id}` ·
`/adjustments/batch` · `/rewards`, `/offers` · `/identity` · `/protection` · `/fraud` ·
`/dashboard` · `/reports/{report}` · `/configuration/{area}` · `/payments/{area}` ·
`/team`, `/team/roles` · `/affiliates/{area}` · `/jobs` · `/preferences`. No route names the brand:
a link is to this brand's backoffice by its address.
