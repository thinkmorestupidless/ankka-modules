# backoffice

A brand's backoffice (spec 030): the operator console, a web-hosted service in the brand's own
project. One built page (React, Vite) and the small server that serves it, signs the operator in,
and calls the brand's services with the operator's token. It stores nothing; `backoffice-state`
keeps the sign-ins and preferences.

```text
src/server/   the page's server (Node runs it directly)
src/shared/   what both sides use: permissions, money, dates, messages
src/web/      the page
tests/        unit, doubles, features, a11y, it
```

```bash
npm ci
npm run typecheck && npm test
npm run build && NODE_ENV=production PORT=3000 node src/server/main.ts
```
