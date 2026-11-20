# backoffice

A brand's backoffice (spec 030): the operator console, a web-hosted service in the brand's own
project (the `backoffice` module). One built page (React, Vite, TanStack Router and Query) and the
small Node server that serves it, signs the operator in at the `eitheror` realm, and calls the
owning services as the signed-in operator. It stores nothing; `backoffice-state` keeps the sign-ins
and preferences.

```text
src/server/   the page's server (Node runs it directly): sign-in, /api/me, the relay, composites
src/shared/   what both sides use: permissions, money, dates, messages, the typed owner clients
src/web/      the page: frame/, components/, actions/, areas/ (one directory per area)
tests/        unit, doubles (one process standing in for every owner), features, a11y
```

- The browser reaches an owner only through `/api/s/<service>/<path>`, and only for a row of
  `src/server/routes.ts` (method, owner, path, the permission the owner requires). `npm run owners`
  makes the typed clients in `src/shared/owners/` from it; a test fails when they differ.
- Composite routes (`/api/worklist`, `/api/search`, `/api/players/{p}/standing` and `/timeline`,
  `/api/withdrawals`, `/api/jobs`, `/api/adjustment-batches`, `/api/freshness`) are in
  `src/server/composite/`, each source asked as the operator.
- Every string an operator reads is a message in `src/shared/messages/en.ts`.
- `tests/unit/parity.test.ts` holds every capability of `specs/030-backoffice/parity.md` to a route
  and a page that calls it.

```bash
npm ci
npm run typecheck && npm run lint && npm test
npm run features            # features/backoffice/ over the doubles (playwright-bdd)
npm run a11y                # axe on every screen, dark and light
npm run build && NODE_ENV=production PORT=3000 node src/server/main.ts
```

`just backoffice-run` runs it behind ankka's local proxy; `just backoffice-contract` copies the
contract's published permissions and currencies into `src/shared/generated/`.
